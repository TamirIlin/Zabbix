package com.example.Zabbix.model;

public record ServerRow(
        String hostId,
        String name,
        String ip,
        boolean up,
        MetricView cpu,
        MetricView memory,
        MetricView disk,
        int problems
) {

    public boolean critical() {
        return cpu.isCritical() || memory.isCritical() || disk.isCritical();
    }
}
