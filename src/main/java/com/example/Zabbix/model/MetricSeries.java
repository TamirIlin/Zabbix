package com.example.Zabbix.model;

import com.example.Zabbix.model.MetricType;

import java.util.List;

public record MetricSeries(
        String key,
        String title,
        String unit,
        String color,
        List<MetricPoint> points,
        double min,
        double max,
        double avg,
        int count
) {

    public static MetricSeries empty(MetricType type) {
        return new MetricSeries(type.getKey(), type.getTitle(), type.getUnit(), type.getColor(),
                List.of(), 0, 0, 0, 0);
    }
}
