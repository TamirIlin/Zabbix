package com.example.Zabbix.model;

import java.time.Instant;
import java.util.List;

public record DashboardData(List<ServerRow> servers, boolean demo, Instant updatedAt) {

    public int totalServers() {
        return servers.size();
    }

    public long criticalServers() {
        return servers.stream().filter(ServerRow::critical).count();
    }

    public int totalProblems() {
        return servers.stream().mapToInt(ServerRow::problems).sum();
    }
}
