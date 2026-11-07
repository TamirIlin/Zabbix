package com.example.Zabbix.service;

import com.example.Zabbix.entity.ZabbixSettings;
import com.example.Zabbix.model.DashboardData;
import com.example.Zabbix.model.MetricType;
import com.example.Zabbix.model.MetricView;
import com.example.Zabbix.model.ServerRow;
import com.example.Zabbix.model.ZabbixHost;
import com.example.Zabbix.model.ZabbixItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final long CACHE_TTL_MILLIS = 30_000;

    private final SettingsService settingsService;
    private final ZabbixClient zabbixClient;
    private final MetricResolver metricResolver;
    private final DemoDataService demoDataService;

    private volatile CacheEntry cache;

    public DashboardService(SettingsService settingsService, ZabbixClient zabbixClient,
                            MetricResolver metricResolver, DemoDataService demoDataService) {
        this.settingsService = settingsService;
        this.zabbixClient = zabbixClient;
        this.metricResolver = metricResolver;
        this.demoDataService = demoDataService;
    }

    public DashboardData getDashboard() {
        Optional<ZabbixSettings> settingsOptional = settingsService.getSettings();
        if (settingsOptional.isEmpty()) {
            return new DashboardData(demoDataService.getServers(), true, Instant.now());
        }

        ZabbixSettings settings = settingsOptional.get();
        long settingsVersion = settings.getUpdatedAt() == null ? 0 : settings.getUpdatedAt().toEpochMilli();
        CacheEntry current = cache;
        long now = System.currentTimeMillis();
        if (current != null
                && current.settingsVersion == settingsVersion
                && now - current.fetchedAt < CACHE_TTL_MILLIS) {
            return current.data;
        }

        synchronized (this) {
            current = cache;
            now = System.currentTimeMillis();
            if (current != null
                    && current.settingsVersion == settingsVersion
                    && now - current.fetchedAt < CACHE_TTL_MILLIS) {
                return current.data;
            }
            DashboardData data = loadDashboard(settings);
            cache = new CacheEntry(settingsVersion, now, data);
            return data;
        }
    }

    public void invalidateCache() {
        cache = null;
    }

    private DashboardData loadDashboard(ZabbixSettings settings) {
        List<ZabbixHost> hosts = zabbixClient.getHosts(settings.getZabbixUrl(), settings.getApiToken());

        Map<String, Integer> problemCounts;
        try {
            problemCounts = zabbixClient.getProblemCounts(settings.getZabbixUrl(), settings.getApiToken());
        } catch (ZabbixApiException ex) {
            log.warn("Не удалось получить активные проблемы Zabbix: {}", ex.getUserMessage());
            problemCounts = Map.of();
        }

        Map<String, List<ZabbixItem>> itemsByHost;
        try {
            itemsByHost = zabbixClient.getItems(settings.getZabbixUrl(), settings.getApiToken(), null)
                    .stream()
                    .collect(Collectors.groupingBy(ZabbixItem::hostId));
        } catch (ZabbixApiException ex) {
            log.warn("Не удалось получить метрики Zabbix: {}", ex.getUserMessage());
            itemsByHost = Map.of();
        }

        List<ServerRow> rows = new ArrayList<>();
        for (ZabbixHost host : hosts) {
            List<ZabbixItem> items = itemsByHost.getOrDefault(host.hostId(), List.of());
            Double cpu = currentMetric(MetricType.CPU, items);
            Double memory = currentMetric(MetricType.MEMORY, items);
            Double disk = currentMetric(MetricType.DISK, items);
            rows.add(new ServerRow(
                    host.hostId(),
                    host.name(),
                    host.ip(),
                    host.up(),
                    MetricView.of(cpu),
                    MetricView.of(memory),
                    MetricView.of(disk),
                    problemCounts.getOrDefault(host.hostId(), 0)
            ));
        }

        rows.sort(Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT)));
        return new DashboardData(List.copyOf(rows), false, Instant.now());
    }

    private Double currentMetric(MetricType type, List<ZabbixItem> items) {
        return metricResolver.resolve(type, items)
                .map(metricResolver::currentValue)
                .orElse(null);
    }

    private record CacheEntry(long settingsVersion, long fetchedAt, DashboardData data) {
    }
}
