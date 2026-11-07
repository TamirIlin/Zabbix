package com.example.Zabbix.service;

import com.example.Zabbix.entity.ZabbixSettings;
import com.example.Zabbix.model.HistoryPoint;
import com.example.Zabbix.model.MetricPoint;
import com.example.Zabbix.model.MetricSeries;
import com.example.Zabbix.model.MetricType;
import com.example.Zabbix.model.ReportResponse;
import com.example.Zabbix.model.ServerSummary;
import com.example.Zabbix.model.ZabbixHost;
import com.example.Zabbix.model.ZabbixItem;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ReportService {

    private static final int MAX_POINTS = 250;
    private static final long MAX_RANGE_SECONDS = 366L * 24 * 3600;

    private final SettingsService settingsService;
    private final ZabbixClient zabbixClient;
    private final MetricResolver metricResolver;
    private final DemoDataService demoDataService;

    public ReportService(SettingsService settingsService, ZabbixClient zabbixClient,
                         MetricResolver metricResolver, DemoDataService demoDataService) {
        this.settingsService = settingsService;
        this.zabbixClient = zabbixClient;
        this.metricResolver = metricResolver;
        this.demoDataService = demoDataService;
    }

    public ServerSummary getSummary(String hostId) {
        Optional<ZabbixSettings> settings = settingsService.getSettings();
        if (settings.isEmpty()) {
            return demoDataService.getSummary(hostId);
        }
        ZabbixHost host = zabbixClient.getHost(
                settings.get().getZabbixUrl(), settings.get().getApiToken(), hostId);
        return new ServerSummary(host.hostId(), host.name(), host.ip(), host.up());
    }

    public ReportResponse buildReport(String hostId, long from, long to, Set<MetricType> requestedMetrics) {
        validateRange(from, to);

        Optional<ZabbixSettings> settingsOptional = settingsService.getSettings();
        if (settingsOptional.isEmpty()) {
            ServerSummary summary = demoDataService.getSummary(hostId);
            List<MetricSeries> series = requestedMetrics.stream()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .map(type -> demoDataService.buildSeries(hostId, type, from, to))
                    .toList();
            return new ReportResponse(summary.hostId(), summary.name(), summary.ip(),
                    summary.up(), from, to, series);
        }

        ZabbixSettings settings = settingsOptional.get();
        ZabbixHost host = zabbixClient.getHost(settings.getZabbixUrl(), settings.getApiToken(), hostId);
        List<ZabbixItem> items = zabbixClient.getItems(
                settings.getZabbixUrl(), settings.getApiToken(), hostId);

        List<MetricSeries> series = new ArrayList<>();
        for (MetricType type : MetricType.values()) {
            if (!requestedMetrics.contains(type)) {
                continue;
            }
            Optional<MetricResolver.MetricPlan> plan = metricResolver.resolve(type, items);
            if (plan.isEmpty()) {
                series.add(MetricSeries.empty(type));
                continue;
            }
            List<MetricPoint> points = loadPoints(settings, plan.get(), from, to);
            series.add(toSeries(type, points));
        }

        return new ReportResponse(host.hostId(), host.name(), host.ip(), host.up(), from, to, series);
    }

    private List<MetricPoint> loadPoints(ZabbixSettings settings, MetricResolver.MetricPlan plan,
                                         long from, long to) {
        List<MetricPoint> points = switch (plan.mode()) {
            case SINGLE -> singleSeries(settings, plan, from, to);
            case NETWORK_SUM -> networkSeries(settings, plan, from, to);
            case MEMORY_USED_TOTAL -> memorySeries(settings, plan, from, to, false);
            case MEMORY_AVAILABLE_TOTAL -> memorySeries(settings, plan, from, to, true);
        };
        points.sort(Comparator.comparingLong(MetricPoint::t));
        return points;
    }

    private List<MetricPoint> singleSeries(ZabbixSettings settings, MetricResolver.MetricPlan plan,
                                           long from, long to) {
        MetricResolver.PlanItem planItem = plan.items().get(0);
        List<HistoryPoint> raw = zabbixClient.getHistory(settings.getZabbixUrl(), settings.getApiToken(),
                planItem.item(), from, to);
        List<MetricPoint> points = new ArrayList<>();
        for (HistoryPoint point : raw) {
            if (point.value() < 0) {
                continue;
            }
            double value = point.value() * planItem.multiplier() + planItem.offset();
            value = normalize(plan.type(), value);
            points.add(new MetricPoint(point.clock(), round(value)));
        }
        return points;
    }

    private List<MetricPoint> networkSeries(ZabbixSettings settings, MetricResolver.MetricPlan plan,
                                            long from, long to) {
        Map<Long, Double> totals = new HashMap<>();
        for (MetricResolver.PlanItem planItem : plan.items()) {
            List<HistoryPoint> raw = zabbixClient.getHistory(settings.getZabbixUrl(), settings.getApiToken(),
                    planItem.item(), from, to);
            Map<Long, double[]> buckets = bucket(raw, planItem.multiplier());
            for (Map.Entry<Long, double[]> bucket : buckets.entrySet()) {
                totals.merge(bucket.getKey(), bucket.getValue()[0], Double::sum);
            }
        }
        List<MetricPoint> points = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : totals.entrySet()) {
            points.add(new MetricPoint(entry.getKey(), round(Math.max(0, entry.getValue()))));
        }
        return points;
    }

    private List<MetricPoint> memorySeries(ZabbixSettings settings, MetricResolver.MetricPlan plan,
                                           long from, long to, boolean availableMode) {
        if (plan.items().size() < 2) {
            return List.of();
        }
        List<HistoryPoint> numerator = zabbixClient.getHistory(settings.getZabbixUrl(), settings.getApiToken(),
                plan.items().get(0).item(), from, to);
        List<HistoryPoint> denominator = zabbixClient.getHistory(settings.getZabbixUrl(), settings.getApiToken(),
                plan.items().get(1).item(), from, to);

        Map<Long, double[]> numeratorBuckets = bucket(numerator, 1);
        Map<Long, double[]> denominatorBuckets = bucket(denominator, 1);
        List<MetricPoint> points = new ArrayList<>();
        for (Map.Entry<Long, double[]> denominatorEntry : denominatorBuckets.entrySet()) {
            double[] num = numeratorBuckets.get(denominatorEntry.getKey());
            if (num == null) {
                continue;
            }
            double total = denominatorEntry.getValue()[0];
            if (total <= 0) {
                continue;
            }
            double value = availableMode
                    ? (total - num[0]) / total * 100
                    : num[0] / total * 100;
            points.add(new MetricPoint(denominatorEntry.getKey(), round(normalize(MetricType.MEMORY, value))));
        }
        return points;
    }

    private Map<Long, double[]> bucket(List<HistoryPoint> points, double multiplier) {
        Map<Long, double[]> sums = new HashMap<>();
        for (HistoryPoint point : points) {
            if (point.value() < 0) {
                continue;
            }
            long bucket = point.clock() / 60 * 60;
            double[] accumulator = sums.computeIfAbsent(bucket, ignored -> new double[2]);
            accumulator[0] += point.value() * multiplier;
            accumulator[1] += 1;
        }
        Map<Long, double[]> averages = new HashMap<>();
        for (Map.Entry<Long, double[]> entry : sums.entrySet()) {
            averages.put(entry.getKey(), new double[]{entry.getValue()[0] / entry.getValue()[1]});
        }
        return averages;
    }

    private MetricSeries toSeries(MetricType type, List<MetricPoint> points) {
        if (points.isEmpty()) {
            return MetricSeries.empty(type);
        }
        double min = points.stream().mapToDouble(MetricPoint::v).min().orElse(0);
        double max = points.stream().mapToDouble(MetricPoint::v).max().orElse(0);
        double avg = points.stream().mapToDouble(MetricPoint::v).average().orElse(0);
        List<MetricPoint> displayed = points.size() > MAX_POINTS ? downsample(points) : points;
        return new MetricSeries(type.getKey(), type.getTitle(), type.getUnit(), type.getColor(),
                displayed, round(min), round(max), round(avg), points.size());
    }

    private List<MetricPoint> downsample(List<MetricPoint> points) {
        long span = points.get(points.size() - 1).t() - points.get(0).t();
        long bucketSize = Math.max(60, span / MAX_POINTS);
        Map<Long, double[]> buckets = new HashMap<>();
        for (MetricPoint point : points) {
            long bucket = point.t() / bucketSize * bucketSize;
            double[] accumulator = buckets.computeIfAbsent(bucket, ignored -> new double[2]);
            accumulator[0] += point.v();
            accumulator[1] += 1;
        }
        List<MetricPoint> result = new ArrayList<>();
        for (Map.Entry<Long, double[]> entry : buckets.entrySet()) {
            result.add(new MetricPoint(entry.getKey(), round(entry.getValue()[0] / entry.getValue()[1])));
        }
        result.sort(Comparator.comparingLong(MetricPoint::t));
        return result;
    }

    private double normalize(MetricType type, double value) {
        if (type == MetricType.NETWORK) {
            return Math.max(0, value);
        }
        return Math.max(0, Math.min(100, value));
    }

    private void validateRange(long from, long to) {
        long now = Instant.now().getEpochSecond();
        if (from <= 0 || to <= 0) {
            throw new IllegalArgumentException("Укажите корректный период отчёта.");
        }
        if (from >= to) {
            throw new IllegalArgumentException("Дата «от» должна быть раньше даты «до».");
        }
        if (to - from > MAX_RANGE_SECONDS) {
            throw new IllegalArgumentException("Период отчёта не может быть больше одного года.");
        }
        if (from > now + 86400) {
            throw new IllegalArgumentException("Дата «от» не может быть в будущем.");
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
