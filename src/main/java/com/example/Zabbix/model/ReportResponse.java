package com.example.Zabbix.model;

import java.util.List;

public record ReportResponse(
        String hostId,
        String hostName,
        String ip,
        boolean up,
        long from,
        long to,
        List<MetricSeries> metrics
) {
}
