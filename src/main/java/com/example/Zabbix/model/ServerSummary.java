package com.example.Zabbix.model;

public record ServerSummary(String hostId, String name, String ip, boolean up) {
}
