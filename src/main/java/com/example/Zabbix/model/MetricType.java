package com.example.Zabbix.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum MetricType {
    CPU("cpu", "Процессор", "%", "#2563eb"),
    MEMORY("memory", "Оперативная память", "%", "#7c3aed"),
    DISK("disk", "Диск", "%", "#f59e0b"),
    NETWORK("network", "Сеть", "Кбит/с", "#0d9488");

    private final String key;
    private final String title;
    private final String unit;
    private final String color;

    MetricType(String key, String title, String unit, String color) {
        this.key = key;
        this.title = title;
        this.unit = unit;
        this.color = color;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getUnit() {
        return unit;
    }

    public String getColor() {
        return color;
    }

    public static Optional<MetricType> fromKey(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.key.equals(normalized))
                .findFirst();
    }
}
