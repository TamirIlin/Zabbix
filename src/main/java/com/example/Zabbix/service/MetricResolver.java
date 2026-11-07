package com.example.Zabbix.service;

import com.example.Zabbix.model.MetricType;
import com.example.Zabbix.model.ZabbixItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class MetricResolver {

    public enum PlanMode {
        SINGLE,
        NETWORK_SUM,
        MEMORY_USED_TOTAL,
        MEMORY_AVAILABLE_TOTAL
    }

    public record PlanItem(ZabbixItem item, double multiplier, double offset) {
    }

    public record MetricPlan(MetricType type, PlanMode mode, List<PlanItem> items) {
    }

    public Optional<MetricPlan> resolve(MetricType type, List<ZabbixItem> items) {
        return switch (type) {
            case CPU -> resolveCpu(items);
            case MEMORY -> resolveMemory(items);
            case DISK -> resolveDisk(items);
            case NETWORK -> resolveNetwork(items);
        };
    }

    public Double currentValue(MetricPlan plan) {
        List<PlanItem> items = plan.items();
        if (items.isEmpty()) {
            return null;
        }
        switch (plan.mode()) {
            case SINGLE -> {
                return percentValue(items.get(0).item().lastValue(),
                        items.get(0).multiplier(), items.get(0).offset());
            }
            case NETWORK_SUM -> {
                double total = 0;
                for (PlanItem item : items) {
                    if (item.item().lastValue() < 0) {
                        continue;
                    }
                    total += item.item().lastValue() * item.multiplier();
                }
                return Math.max(0, total);
            }
            case MEMORY_USED_TOTAL -> {
                if (items.size() < 2) {
                    return null;
                }
                double used = items.get(0).item().lastValue();
                double total = items.get(1).item().lastValue();
                if (used < 0 || total <= 0) {
                    return null;
                }
                return percentValue(used / total * 100, 1, 0);
            }
            case MEMORY_AVAILABLE_TOTAL -> {
                if (items.size() < 2) {
                    return null;
                }
                double available = items.get(0).item().lastValue();
                double total = items.get(1).item().lastValue();
                if (available < 0 || total <= 0) {
                    return null;
                }
                return percentValue((total - available) / total * 100, 1, 0);
            }
        }
        return null;
    }

    private Optional<MetricPlan> resolveCpu(List<ZabbixItem> items) {
        List<ZabbixItem> candidates = items.stream()
                .filter(item -> {
                    String key = item.key().toLowerCase(Locale.ROOT);
                    if (key.startsWith("system.cpu.util")) {
                        return true;
                    }
                    return key.startsWith("perf_counter")
                            && key.contains("processor")
                            && !key.contains("idle");
                })
                .sorted(Comparator.comparingInt(this::cpuScore).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ZabbixItem item = candidates.get(0);
        boolean idle = item.key().toLowerCase(Locale.ROOT).contains("idle");
        PlanItem planItem = idle
                ? new PlanItem(item, -1, 100)
                : new PlanItem(item, 1, 0);
        return Optional.of(new MetricPlan(MetricType.CPU, PlanMode.SINGLE, List.of(planItem)));
    }

    private int cpuScore(ZabbixItem item) {
        String key = item.key().toLowerCase(Locale.ROOT);
        if (key.equals("system.cpu.util")) {
            return 100;
        }
        if (key.startsWith("system.cpu.util")) {
            return key.contains("idle") ? 70 : 90;
        }
        if (key.contains("total") && !key.contains("idle")) {
            return 60;
        }
        return 50;
    }

    private Optional<MetricPlan> resolveMemory(List<ZabbixItem> items) {
        Optional<ZabbixItem> utilization = findByKey(items, "vm.memory.utilization");
        if (utilization.isPresent()) {
            return single(MetricType.MEMORY, utilization.get(), 1, 0);
        }

        Optional<ZabbixItem> pushed = findByKey(items, "vm.memory.size[pused]");
        if (pushed.isPresent()) {
            return single(MetricType.MEMORY, pushed.get(), 1, 0);
        }

        Optional<ZabbixItem> pavailable = findByKey(items, "vm.memory.size[pavailable]");
        if (pavailable.isPresent()) {
            return single(MetricType.MEMORY, pavailable.get(), -1, 100);
        }

        Optional<ZabbixItem> total = items.stream()
                .filter(item -> item.key().equalsIgnoreCase("vm.memory.size[total]"))
                .findFirst();
        if (total.isPresent()) {
            Optional<ZabbixItem> used = items.stream()
                    .filter(item -> item.key().equalsIgnoreCase("vm.memory.size[used]"))
                    .findFirst();
            if (used.isPresent()) {
                return Optional.of(new MetricPlan(MetricType.MEMORY, PlanMode.MEMORY_USED_TOTAL,
                        List.of(new PlanItem(used.get(), 1, 0), new PlanItem(total.get(), 1, 0))));
            }
            Optional<ZabbixItem> available = items.stream()
                    .filter(item -> item.key().equalsIgnoreCase("vm.memory.size[available]"))
                    .findFirst();
            if (available.isPresent()) {
                return Optional.of(new MetricPlan(MetricType.MEMORY, PlanMode.MEMORY_AVAILABLE_TOTAL,
                        List.of(new PlanItem(available.get(), 1, 0), new PlanItem(total.get(), 1, 0))));
            }
        }
        return Optional.empty();
    }

    private Optional<MetricPlan> resolveDisk(List<ZabbixItem> items) {
        Optional<ZabbixItem> worst = items.stream()
                .filter(item -> {
                    String key = item.key().toLowerCase(Locale.ROOT);
                    return (key.startsWith("vfs.fs.size[") && key.contains("pused"))
                            || key.equals("vfs.fs.pused");
                })
                .filter(item -> item.lastValue() >= 0)
                .max(Comparator.comparingDouble(ZabbixItem::lastValue));
        return worst.flatMap(item -> single(MetricType.DISK, item, 1, 0));
    }

    private Optional<MetricPlan> resolveNetwork(List<ZabbixItem> items) {
        Map<String, List<ZabbixItem>> byInterface = new LinkedHashMap<>();
        for (ZabbixItem item : items) {
            String key = item.key().toLowerCase(Locale.ROOT);
            if (!key.startsWith("net.if.in[") && !key.startsWith("net.if.out[")) {
                continue;
            }
            String iface = interfaceName(item.key());
            if (iface == null || iface.isBlank() || iface.equals("lo")) {
                continue;
            }
            byInterface.computeIfAbsent(iface, ignored -> new ArrayList<>()).add(item);
        }
        if (byInterface.isEmpty()) {
            return Optional.empty();
        }

        String bestInterface = null;
        double bestTotal = -1;
        for (Map.Entry<String, List<ZabbixItem>> entry : byInterface.entrySet()) {
            double total = 0;
            for (ZabbixItem item : entry.getValue()) {
                if (item.lastValue() >= 0) {
                    total += item.lastValue() * networkMultiplier(item.units());
                }
            }
            if (total > bestTotal) {
                bestTotal = total;
                bestInterface = entry.getKey();
            }
        }
        if (bestInterface == null) {
            return Optional.empty();
        }

        List<PlanItem> planItems = new ArrayList<>();
        for (ZabbixItem item : byInterface.get(bestInterface)) {
            if (item.lastValue() >= 0) {
                planItems.add(new PlanItem(item, networkMultiplier(item.units()), 0));
            }
        }
        if (planItems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetricPlan(MetricType.NETWORK, PlanMode.NETWORK_SUM, planItems));
    }

    private Optional<MetricPlan> single(MetricType type, ZabbixItem item, double multiplier, double offset) {
        if (item.lastValue() < 0) {
            return Optional.empty();
        }
        return Optional.of(new MetricPlan(type, PlanMode.SINGLE,
                List.of(new PlanItem(item, multiplier, offset))));
    }

    private Optional<ZabbixItem> findByKey(List<ZabbixItem> items, String key) {
        return items.stream().filter(item -> item.key().equalsIgnoreCase(key)).findFirst();
    }

    private String interfaceName(String key) {
        int start = key.indexOf('[');
        if (start < 0) {
            return null;
        }
        int end = key.indexOf(',', start + 1);
        if (end < 0) {
            end = key.indexOf(']', start + 1);
        }
        if (end < 0) {
            return null;
        }
        return key.substring(start + 1, end).trim();
    }

    public double networkMultiplier(String units) {
        if (units == null || units.isBlank()) {
            return 0.001;
        }
        String original = units.trim();
        String normalized = original.toLowerCase(Locale.ROOT).replace(" ", "");
        boolean bytes = original.equals("Bps") || original.equals("B/s")
                || normalized.contains("byte") || normalized.equals("bytes/s");

        if (normalized.equals("bps") || normalized.equals("bit/s")) {
            return bytes ? 0.008 : 0.001;
        }
        if (normalized.equals("kbps") || normalized.equals("kbit/s") || normalized.equals("kbit")) {
            return 1;
        }
        if (normalized.equals("mbps") || normalized.equals("mbit/s") || normalized.equals("mbit")) {
            return 1000;
        }
        if (normalized.equals("gbps") || normalized.equals("gbit/s") || normalized.equals("gbit")) {
            return 1_000_000;
        }
        if (normalized.equals("b/s") || normalized.equals("bytes/s") || normalized.equals("byte/s")) {
            return 0.008;
        }
        if (normalized.equals("kb/s")) {
            return 8;
        }
        if (normalized.equals("mb/s")) {
            return 8000;
        }
        return bytes ? 0.008 : 0.001;
    }

    private Double percentValue(double raw, double multiplier, double offset) {
        if (raw < 0) {
            return null;
        }
        double value = raw * multiplier + offset;
        if (value < 0) {
            value = 0;
        }
        if (value > 100) {
            value = 100;
        }
        return value;
    }
}
