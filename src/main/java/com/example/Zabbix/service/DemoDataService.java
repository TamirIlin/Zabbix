package com.example.Zabbix.service;

import com.example.Zabbix.model.MetricPoint;
import com.example.Zabbix.model.MetricSeries;
import com.example.Zabbix.model.MetricType;
import com.example.Zabbix.model.MetricView;
import com.example.Zabbix.model.ServerRow;
import com.example.Zabbix.model.ServerSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DemoDataService {

    private final Map<String, ServerRow> servers = new LinkedHashMap<>();

    public DemoDataService() {
        add("demo-1", "srv-web-01", "10.10.1.11", true, 34.2, 58.1, 47.5, 0);
        add("demo-2", "srv-web-02", "10.10.1.12", true, 72.4, 63.2, 55.1, 1);
        add("demo-3", "srv-db-01", "10.10.2.21", true, 84.6, 78.3, 81.2, 3);
        add("demo-4", "srv-db-02", "10.10.2.22", false, 12.4, 40.7, 66.3, 2);
        add("demo-5", "srv-app-01", "10.10.3.31", true, 45.8, 86.4, 72.0, 1);
        add("demo-6", "srv-app-02", "10.10.3.32", true, 28.1, 49.9, 64.3, 0);
        add("demo-7", "srv-cache-01", "10.10.3.41", true, 63.0, 55.2, 38.4, 0);
        add("demo-8", "srv-backup-01", "10.10.4.51", true, 8.4, 31.7, 91.3, 2);
        add("demo-9", "srv-monitor-01", "10.10.5.61", true, 22.5, 42.8, 52.6, 0);
        add("demo-10", "srv-proxy-01", "10.10.5.62", false, null, null, null, 0);
        add("demo-11", "srv-file-01", "10.10.6.71", true, 15.7, 39.4, 68.9, 1);
        add("demo-12", "srv-mail-01", "10.10.6.72", true, 51.2, 67.5, 44.1, 0);
    }

    private void add(String id, String name, String ip, boolean up,
                     Double cpu, Double memory, Double disk, int problems) {
        servers.put(id, new ServerRow(id, name, ip, up,
                MetricView.of(cpu), MetricView.of(memory), MetricView.of(disk), problems));
    }

    public List<ServerRow> getServers() {
        return List.copyOf(servers.values());
    }

    public ServerSummary getSummary(String hostId) {
        ServerRow row = find(hostId);
        return new ServerSummary(row.hostId(), row.name(), row.ip(), row.up());
    }

    public MetricSeries buildSeries(String hostId, MetricType type, long from, long to) {
        ServerRow row = find(hostId);
        long span = Math.max(60, to - from);
        long step = Math.max(60, span / 240);
        long seed = Math.abs((hostId + type.getKey()).hashCode());
        int index = 0;
        List<MetricPoint> points = new ArrayList<>();

        double current = switch (type) {
            case CPU -> row.cpu().value() == null ? 35 : row.cpu().value();
            case MEMORY -> row.memory().value() == null ? 55 : row.memory().value();
            case DISK -> row.disk().value() == null ? 65 : row.disk().value();
            case NETWORK -> 900 + (Math.abs(hostId.hashCode()) % 12) * 240.0;
        };

        for (long timestamp = from; timestamp <= to; timestamp += step) {
            double phase = index / 9.0;
            double noise = deterministicNoise(seed, index);
            double value = switch (type) {
                case CPU -> current + 17 * Math.sin(phase) + 7 * Math.sin(phase * 2.7 + 1.1) + noise * 5;
                case MEMORY -> current + 5.5 * Math.sin(phase / 2.2) + noise * 2.2;
                case DISK -> current + 1.8 * Math.sin(phase / 3.1) + noise * 0.6;
                case NETWORK -> Math.max(0, current + 420 * Math.sin(phase / 1.7)
                        + 260 * Math.sin(phase * 3.1) + noise * 120);
            };
            if (type != MetricType.NETWORK) {
                value = Math.max(0.4, Math.min(99.5, value));
            } else {
                value = Math.max(0, value);
            }
            points.add(new MetricPoint(timestamp, round(value)));
            index++;
        }

        double min = points.stream().mapToDouble(MetricPoint::v).min().orElse(0);
        double max = points.stream().mapToDouble(MetricPoint::v).max().orElse(0);
        double avg = points.stream().mapToDouble(MetricPoint::v).average().orElse(0);
        return new MetricSeries(type.getKey(), type.getTitle(), type.getUnit(), type.getColor(),
                points, round(min), round(max), round(avg), points.size());
    }

    public boolean exists(String hostId) {
        return servers.containsKey(hostId);
    }

    private ServerRow find(String hostId) {
        ServerRow row = servers.get(hostId);
        if (row == null) {
            throw new ResourceNotFoundException("Демонстрационный сервер не найден.");
        }
        return row;
    }

    private double deterministicNoise(long seed, int index) {
        double x = Math.sin((seed % 100000) * 0.0001 + index * 12.9898) * 43758.5453;
        return (x - Math.floor(x)) * 2 - 1;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
