package com.example.Zabbix.model;

public record ZabbixHost(String hostId, String name, String ip, boolean up) {
}
