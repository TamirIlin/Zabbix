package com.example.Zabbix.web;

import com.example.Zabbix.model.MetricType;
import com.example.Zabbix.model.ReportResponse;
import com.example.Zabbix.service.ReportService;
import com.example.Zabbix.service.ResourceNotFoundException;
import com.example.Zabbix.service.SettingsService;
import com.example.Zabbix.service.ZabbixApiException;
import com.example.Zabbix.service.ZabbixClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final SettingsService settingsService;
    private final ZabbixClient zabbixClient;
    private final ReportService reportService;

    public ApiController(SettingsService settingsService, ZabbixClient zabbixClient,
                         ReportService reportService) {
        this.settingsService = settingsService;
        this.zabbixClient = zabbixClient;
        this.reportService = reportService;
    }

    @PostMapping("/api/settings/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestParam(required = false) String zabbixUrl,
            @RequestParam(required = false) String apiToken) {
        try {
            String url = settingsService.normalizeUrl(zabbixUrl);
            String token = apiToken == null ? "" : apiToken.trim();
            if (token.isBlank()) {
                token = settingsService.getSettings()
                        .map(settings -> settings.getApiToken())
                        .orElse("");
            }
            if (token.isBlank()) {
                throw new IllegalArgumentException("Введите API-токен Zabbix.");
            }
            String version = zabbixClient.testConnection(url, token);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Подключение установлено. Версия Zabbix API: " + version);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | ZabbixApiException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", ex.getMessage());
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            log.error("Не удалось проверить подключение к Zabbix", ex);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "Не удалось проверить подключение. Попробуйте ещё раз.");
            return ResponseEntity.ok(body);
        }
    }

    @GetMapping("/api/servers/{hostId}/report")
    public ResponseEntity<Map<String, Object>> report(
            @PathVariable String hostId,
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(defaultValue = "cpu,memory,disk,network") String metrics) {
        try {
            Set<MetricType> requested = parseMetrics(metrics);
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("Выберите хотя бы одну метрику для отчёта.");
            }
            ReportResponse response = reportService.buildReport(hostId, from, to, requested);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("report", response);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (ZabbixApiException ex) {
            return error(HttpStatus.BAD_GATEWAY, ex.getUserMessage());
        } catch (Exception ex) {
            log.error("Не удалось построить отчёт по серверу {}", hostId, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось построить отчёт. Проверьте подключение к Zabbix и повторите попытку.");
        }
    }

    private Set<MetricType> parseMetrics(String metrics) {
        Set<MetricType> result = EnumSet.noneOf(MetricType.class);
        if (metrics == null || metrics.isBlank()) {
            return result;
        }
        List<String> invalid = new ArrayList<>();
        for (String part : metrics.split(",")) {
            String key = part.trim();
            if (key.isEmpty()) {
                continue;
            }
            MetricType.fromKey(key).ifPresentOrElse(result::add, () -> invalid.add(key));
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Неизвестная метрика: " + String.join(", ", invalid) + ".");
        }
        return result;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
