package com.example.Zabbix.service;

import com.example.Zabbix.model.HistoryPoint;
import com.example.Zabbix.model.ZabbixHost;
import com.example.Zabbix.model.ZabbixItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ZabbixClient {

    private static final Logger log = LoggerFactory.getLogger(ZabbixClient.class);

    private static final String[] ITEM_SEARCH_KEYS = {
            "system.cpu.util", "perf_counter", "vm.memory", "vfs.fs.", "net.if."
    };

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestId = new AtomicLong(1);

    public ZabbixClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String getVersion(String baseUrl, String token) {
        JsonNode result = call(baseUrl, token, "apiinfo.version", objectMapper.createObjectNode(), false);
        return result == null || result.isNull() ? "РЅРµРёР·РІРµСЃС‚РЅРѕ" : result.asString();
    }

    public String testConnection(String baseUrl, String token) {
        String version = getVersion(baseUrl, token);
        if (token != null && !token.isBlank()) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("limit", 1);
            params.putArray("output").add("hostid");
            call(baseUrl, token, "host.get", params, true);
        }
        return version;
    }

    public List<ZabbixHost> getHosts(String baseUrl, String token) {
        ObjectNode params = objectMapper.createObjectNode();
        ArrayNode output = params.putArray("output");
        output.add("hostid");
        output.add("name");
        output.add("status");
        params.putArray("selectInterfaces")
                .add("interfaceid")
                .add("ip")
                .add("dns")
                .add("main")
                .add("type")
                .add("available")
                .add("error");

        JsonNode result = call(baseUrl, token, "host.get", params, true);
        List<ZabbixHost> hosts = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                hosts.add(toHost(node));
            }
        }
        return hosts;
    }

    public ZabbixHost getHost(String baseUrl, String token, String hostId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("hostids").add(hostId);
        ArrayNode output = params.putArray("output");
        output.add("hostid");
        output.add("name");
        output.add("status");
        params.putArray("selectInterfaces")
                .add("interfaceid")
                .add("ip")
                .add("dns")
                .add("main")
                .add("type")
                .add("available")
                .add("error");

        JsonNode result = call(baseUrl, token, "host.get", params, true);
        if (result == null || !result.isArray() || result.isEmpty()) {
            throw new ResourceNotFoundException("РЎРµСЂРІРµСЂ СЃ РёРґРµРЅС‚РёС„РёРєР°С‚РѕСЂРѕРј " + hostId + " РЅРµ РЅР°Р№РґРµРЅ РІ Zabbix.");
        }
        return toHost(result.get(0));
    }

    public Map<String, Integer> getProblemCounts(String baseUrl, String token) {
        ObjectNode params = objectMapper.createObjectNode();
        ArrayNode output = params.putArray("output");
        output.add("eventid");
        output.add("objectid");
        params.put("limit", 10000);

        JsonNode result = call(baseUrl, token, "problem.get", params, true);
        if (result == null || !result.isArray() || result.isEmpty()) {
            return Map.of();
        }

        Set<String> triggerIds = new LinkedHashSet<>();
        for (JsonNode problem : result) {
            String objectId = problem.path("objectid").asString("");
            if (!objectId.isBlank()) {
                triggerIds.add(objectId);
            }
        }
        if (triggerIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> counts = new HashMap<>();
        List<String> ids = new ArrayList<>(triggerIds);
        for (int start = 0; start < ids.size(); start += 500) {
            List<String> chunk = ids.subList(start, Math.min(start + 500, ids.size()));
            ObjectNode triggerParams = objectMapper.createObjectNode();
            ArrayNode triggerIdsNode = triggerParams.putArray("triggerids");
            chunk.forEach(triggerIdsNode::add);
            triggerParams.putArray("output").add("triggerid");
            triggerParams.putArray("selectHosts").add("hostid");

            try {
                JsonNode triggers = call(baseUrl, token, "trigger.get", triggerParams, true);
                if (triggers != null && triggers.isArray()) {
                    for (JsonNode trigger : triggers) {
                        JsonNode hosts = trigger.path("hosts");
                        if (hosts.isArray()) {
                            for (JsonNode host : hosts) {
                                String hostId = host.path("hostid").asString("");
                                if (!hostId.isBlank()) {
                                    counts.merge(hostId, 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            } catch (ZabbixApiException ex) {
                log.warn("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ РїСЂРёРІСЏР·РєСѓ РїСЂРѕР±Р»РµРј Рє СЃРµСЂРІРµСЂР°Рј: {}", ex.getUserMessage());
            }
        }
        return counts;
    }

    public List<ZabbixItem> getItems(String baseUrl, String token, String hostId) {
        ObjectNode params = objectMapper.createObjectNode();
        if (hostId != null && !hostId.isBlank()) {
            params.putArray("hostids").add(hostId);
        }
        ArrayNode output = params.putArray("output");
        output.add("itemid");
        output.add("hostid");
        output.add("name");
        output.add("key_");
        output.add("lastvalue");
        output.add("units");
        output.add("value_type");

        ObjectNode search = params.putObject("search");
        ArrayNode searchKeys = search.putArray("key_");
        for (String key : ITEM_SEARCH_KEYS) {
            searchKeys.add(key);
        }
        params.put("searchByAny", true);
        params.putObject("filter").put("status", 0);
        params.put("limit", 200000);

        JsonNode result = call(baseUrl, token, "item.get", params, true);
        List<ZabbixItem> items = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                int valueType = node.path("value_type").asInt(-1);
                if (valueType != 0 && valueType != 3) {
                    continue;
                }
                String rawValue = node.path("lastvalue").asString("");
                if (rawValue.isBlank()) {
                    continue;
                }
                double value;
                try {
                    value = Double.parseDouble(rawValue);
                } catch (NumberFormatException ex) {
                    continue;
                }
                items.add(new ZabbixItem(
                        node.path("itemid").asString(""),
                        node.path("hostid").asString(""),
                        node.path("name").asString(""),
                        node.path("key_").asString(""),
                        value,
                        node.path("units").asString(""),
                        valueType
                ));
            }
        }
        return items;
    }

    public List<HistoryPoint> getHistory(String baseUrl, String token, ZabbixItem item,
                                         long from, long to) {
        boolean useTrends = to - from > 3L * 24 * 3600;
        List<HistoryPoint> history = useTrends
                ? fetchTrends(baseUrl, token, item, from, to)
                : fetchHistory(baseUrl, token, item, from, to);
        if (history.isEmpty() && !useTrends) {
            history = fetchTrends(baseUrl, token, item, from, to);
        }
        return history;
    }

    private List<HistoryPoint> fetchHistory(String baseUrl, String token, ZabbixItem item,
                                            long from, long to) {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("itemids").add(item.itemId());
        params.put("history", item.valueType());
        params.put("time_from", from);
        params.put("time_till", to);
        ArrayNode output = params.putArray("output");
        output.add("clock");
        output.add("value");
        params.put("sortfield", "clock");
        params.put("sortorder", "ASC");
        params.put("limit", 50000);
        return parseHistory(call(baseUrl, token, "history.get", params, true), "value");
    }

    private List<HistoryPoint> fetchTrends(String baseUrl, String token, ZabbixItem item,
                                           long from, long to) {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("itemids").add(item.itemId());
        params.put("time_from", from);
        params.put("time_till", to);
        ArrayNode output = params.putArray("output");
        output.add("clock");
        output.add("value_avg");
        params.put("sortfield", "clock");
        params.put("sortorder", "ASC");
        params.put("limit", 50000);
        return parseHistory(call(baseUrl, token, "trend.get", params, true), "value_avg");
    }

    private List<HistoryPoint> parseHistory(JsonNode result, String valueField) {
        List<HistoryPoint> points = new ArrayList<>();
        if (result == null || !result.isArray()) {
            return points;
        }
        for (JsonNode node : result) {
            long clock = node.path("clock").asLong(0);
            String rawValue = node.path(valueField).asString("");
            if (clock <= 0 || rawValue.isBlank()) {
                continue;
            }
            try {
                points.add(new HistoryPoint(clock, Double.parseDouble(rawValue)));
            } catch (NumberFormatException ignored) {
                // РџСЂРѕРїСѓСЃРєР°РµРј РЅРµРєРѕСЂСЂРµРєС‚РЅС‹Рµ Р·РЅР°С‡РµРЅРёСЏ, РЅРµ РїСЂРµСЂС‹РІР°СЏ РїРѕСЃС‚СЂРѕРµРЅРёРµ РѕС‚С‡С‘С‚Р°.
            }
        }
        return points;
    }

    private ZabbixHost toHost(JsonNode node) {
        String hostId = node.path("hostid").asString("");
        String name = node.path("name").asString("");
        boolean enabled = "0".equals(node.path("status").asString("0"));
        JsonNode interfaces = node.path("interfaces");
        JsonNode primary = choosePrimaryInterface(interfaces);
        String ip = primary == null ? "" : interfaceAddress(primary);
        boolean up = enabled && isInterfaceUp(interfaces);
        return new ZabbixHost(hostId, name, ip, up);
    }

    private JsonNode choosePrimaryInterface(JsonNode interfaces) {
        if (interfaces == null || !interfaces.isArray() || interfaces.isEmpty()) {
            return null;
        }
        JsonNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonNode candidate : interfaces) {
            int score = 0;
            if ("1".equals(candidate.path("main").asString())) {
                score += 8;
            }
            int type = candidate.path("type").asInt(-1);
            if (type == 1) {
                score += 4;
            } else if (type == 2) {
                score += 2;
            }
            String ip = candidate.path("ip").asString("");
            if (!ip.isBlank() && !"127.0.0.1".equals(ip)) {
                score += 3;
            }
            if ("1".equals(candidate.path("available").asString())) {
                score += 1;
            }
            if (candidate.path("error").asString("").isBlank()) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private String interfaceAddress(JsonNode candidate) {
        String ip = candidate.path("ip").asString("");
        if (!ip.isBlank() && !"127.0.0.1".equals(ip)) {
            return ip;
        }
        String dns = candidate.path("dns").asString("");
        if (!dns.isBlank()) {
            return dns;
        }
        return ip.isBlank() ? "вЂ”" : ip;
    }

    private boolean isInterfaceUp(JsonNode interfaces) {
        if (interfaces == null || !interfaces.isArray() || interfaces.isEmpty()) {
            return true;
        }
        boolean sawUnavailable = false;
        for (JsonNode candidate : interfaces) {
            int type = candidate.path("type").asInt(-1);
            if (type < 1 || type > 4) {
                continue;
            }
            String available = candidate.path("available").asString("0");
            String error = candidate.path("error").asString("");
            if ("1".equals(available)) {
                return true;
            }
            if ("2".equals(available) || !error.isBlank()) {
                sawUnavailable = true;
            }
        }
        return !sawUnavailable;
    }

    private JsonNode call(String baseUrl, String token, String method, ObjectNode params, boolean authenticated) {
        URI uri = buildEndpoint(baseUrl);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        request.put("id", requestId.getAndIncrement());
        boolean tokenPresent = token != null && !token.isBlank();

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json-rpc")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request), StandardCharsets.UTF_8));
        if (authenticated && tokenPresent) {
            builder.header("Authorization", "Bearer " + token.trim());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new ZabbixApiException("Zabbix РЅРµ РѕС‚РІРµС‚РёР» РІРѕРІСЂРµРјСЏ. РџСЂРѕРІРµСЂСЊС‚Рµ РґРѕСЃС‚СѓРїРЅРѕСЃС‚СЊ СЃРµСЂРІРµСЂР° Рё РїРѕРІС‚РѕСЂРёС‚Рµ РїРѕРїС‹С‚РєСѓ.", ex);
        } catch (ConnectException ex) {
            throw new ZabbixApiException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРєР»СЋС‡РёС‚СЊСЃСЏ Рє Zabbix. РџСЂРѕРІРµСЂСЊС‚Рµ Р°РґСЂРµСЃ СЃРµСЂРІРµСЂР° Рё РґРѕСЃС‚СѓРїРЅРѕСЃС‚СЊ СЃРµС‚Рё.", ex);
        } catch (IOException ex) {
            throw new ZabbixApiException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРєР»СЋС‡РёС‚СЊСЃСЏ Рє Zabbix. РџСЂРѕРІРµСЂСЊС‚Рµ Р°РґСЂРµСЃ СЃРµСЂРІРµСЂР° Рё РґРѕСЃС‚СѓРїРЅРѕСЃС‚СЊ СЃРµС‚Рё.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ZabbixApiException("Р—Р°РїСЂРѕСЃ Рє Zabbix Р±С‹Р» РїСЂРµСЂРІР°РЅ. РџРѕРІС‚РѕСЂРёС‚Рµ РїРѕРїС‹С‚РєСѓ.", ex);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ZabbixApiException("Zabbix РѕС‚РєР»РѕРЅРёР» API-С‚РѕРєРµРЅ. РџСЂРѕРІРµСЂСЊС‚Рµ С‚РѕРєРµРЅ Рё РїСЂР°РІР° РґРѕСЃС‚СѓРїР°.");
        }
        if (response.statusCode() == 404) {
            throw new ZabbixApiException("Zabbix API РЅРµ РЅР°Р№РґРµРЅ РїРѕ СѓРєР°Р·Р°РЅРЅРѕРјСѓ Р°РґСЂРµСЃСѓ. РџСЂРѕРІРµСЂСЊС‚Рµ URL СЃРµСЂРІРµСЂР° Zabbix.");
        }
        if (response.statusCode() >= 500) {
            throw new ZabbixApiException("РЎРµСЂРІРµСЂ Zabbix РІСЂРµРјРµРЅРЅРѕ РЅРµРґРѕСЃС‚СѓРїРµРЅ. РџРѕРїСЂРѕР±СѓР№С‚Рµ РїРѕРІС‚РѕСЂРёС‚СЊ Р·Р°РїСЂРѕСЃ РїРѕР·Р¶Рµ.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ZabbixApiException("Zabbix РІРµСЂРЅСѓР» РЅРµРѕР¶РёРґР°РЅРЅС‹Р№ РѕС‚РІРµС‚ (РєРѕРґ " + response.statusCode() + ").");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException ex) {
            throw new ZabbixApiException("РџРѕ СѓРєР°Р·Р°РЅРЅРѕРјСѓ Р°РґСЂРµСЃСѓ РѕС‚РІРµС‡Р°РµС‚ РЅРµ Zabbix. РџСЂРѕРІРµСЂСЊС‚Рµ URL СЃРµСЂРІРµСЂР° Zabbix.", ex);
        }

        JsonNode error = root.path("error");
        if (error != null && !error.isMissingNode() && !error.isNull()) {
            throw new ZabbixApiException(translateApiError(error));
        }
        return root.path("result");
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (RuntimeException ex) {
            throw new ZabbixApiException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРіРѕС‚РѕРІРёС‚СЊ Р·Р°РїСЂРѕСЃ Рє Zabbix.", ex);
        }
    }

    private URI buildEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ZabbixApiException("РђРґСЂРµСЃ Zabbix РЅРµ СѓРєР°Р·Р°РЅ. Р—Р°РїРѕР»РЅРёС‚Рµ РЅР°СЃС‚СЂРѕР№РєРё РїРѕРґРєР»СЋС‡РµРЅРёСЏ.");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/api_jsonrpc.php")) {
            url = url.substring(0, url.length() - "/api_jsonrpc.php".length());
        }
        try {
            URI uri = URI.create(url + "/api_jsonrpc.php");
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new ZabbixApiException("РќРµРєРѕСЂСЂРµРєС‚РЅС‹Р№ Р°РґСЂРµСЃ Zabbix. РЈРєР°Р¶РёС‚Рµ Р°РґСЂРµСЃ РІ С„РѕСЂРјР°С‚Рµ https://zabbix.example.com.");
        }
    }

    private String translateApiError(JsonNode error) {
        String message = error.path("message").asString("");
        String data = error.path("data").asString("");
        String joined = (message + " " + data).toLowerCase();

        if (joined.contains("authorization") || joined.contains("token")
                || joined.contains("session") || joined.contains("not authorized")
                || joined.contains("permission")) {
            return "Zabbix РѕС‚РєР»РѕРЅРёР» API-С‚РѕРєРµРЅ. РџСЂРѕРІРµСЂСЊС‚Рµ С‚РѕРєРµРЅ Рё РїСЂР°РІР° РґРѕСЃС‚СѓРїР°.";
        }
        if (joined.contains("invalid params") || joined.contains("invalid parameter")) {
            return "Zabbix РЅРµ РїСЂРёРЅСЏР» РїР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСЂРѕСЃР°. РџСЂРѕРІРµСЂСЊС‚Рµ РІРµСЂСЃРёСЋ Zabbix Рё РїРѕРІС‚РѕСЂРёС‚Рµ РїРѕРїС‹С‚РєСѓ.";
        }
        if (joined.contains("not found")) {
            return "Р—Р°РїСЂРѕС€РµРЅРЅС‹Р№ РѕР±СЉРµРєС‚ РЅРµ РЅР°Р№РґРµРЅ РІ Zabbix.";
        }
        if (!message.isBlank() && !"application error.".equalsIgnoreCase(message.trim())) {
            return "Zabbix РІРµСЂРЅСѓР» РѕС€РёР±РєСѓ: " + message;
        }
        if (!data.isBlank()) {
            return "Zabbix РІРµСЂРЅСѓР» РѕС€РёР±РєСѓ: " + data;
        }
        return "Zabbix РІРµСЂРЅСѓР» РЅРµРёР·РІРµСЃС‚РЅСѓСЋ РѕС€РёР±РєСѓ. РџРѕРІС‚РѕСЂРёС‚Рµ РїРѕРїС‹С‚РєСѓ РїРѕР·Р¶Рµ.";
    }
}

