package com.example.Zabbix.model;

import java.util.Locale;

public record MetricView(Double value, int width, String cssClass, String text) {

    public static MetricView of(Double rawValue) {
        if (rawValue == null || rawValue.isNaN() || rawValue.isInfinite()) {
            return new MetricView(null, 0, "unknown", "—");
        }
        double value = Math.max(0, Math.min(100, rawValue));
        String cssClass;
        if (value <= 50) {
            cssClass = "ok";
        } else if (value <= 80) {
            cssClass = "warn";
        } else {
            cssClass = "bad";
        }
        String text = String.format(Locale.ROOT, "%.1f%%", value);
        return new MetricView(value, (int) Math.round(value), cssClass, text);
    }

    public boolean isCritical() {
        return value != null && value > 80;
    }
}
