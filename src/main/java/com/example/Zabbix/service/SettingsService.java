package com.example.Zabbix.service;

import com.example.Zabbix.entity.ZabbixSettings;
import com.example.Zabbix.repository.ZabbixSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Optional;

@Service
public class SettingsService {

    private final ZabbixSettingsRepository repository;

    public SettingsService(ZabbixSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ZabbixSettings> getSettings() {
        return repository.findFirstByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public boolean isConfigured() {
        return getSettings()
                .map(settings -> !settings.getZabbixUrl().isBlank() && !settings.getApiToken().isBlank())
                .orElse(false);
    }

    @Transactional
    public ZabbixSettings save(String rawUrl, String rawToken) {
        String url = normalizeUrl(rawUrl);
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isBlank()) {
            throw new IllegalArgumentException("Введите API-токен Zabbix.");
        }

        ZabbixSettings settings = repository.findFirstByOrderByIdAsc().orElseGet(ZabbixSettings::new);
        settings.setZabbixUrl(url);
        settings.setApiToken(token);
        return repository.save(settings);
    }

    public String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Введите адрес сервера Zabbix.");
        }
        String url = rawUrl.trim();
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/api_jsonrpc.php")) {
            url = url.substring(0, url.length() - "/api_jsonrpc.php".length());
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Некорректный адрес Zabbix. Укажите адрес в формате https://zabbix.example.com.");
        }
        return url;
    }
}
