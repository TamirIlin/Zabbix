package com.example.Zabbix.model;

public record ZabbixItem(
        String itemId,
        String hostId,
        String name,
        String key,
        double lastValue,
        String units,
        int valueType
) {
}
