package com.stopbell.transit.metadata;

import java.net.URI;

import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.TransitMetadataArchiveService;

import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** Downloads only the route and routeStation files from the official GBIS base-info service. */
public class GbisMetadataClient {
    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private static final java.util.Set<String> DOWNLOAD_URL_FIELDS = java.util.Set.of(
            "areaDownloadUrl", "routeDownloadUrl", "routeLineDownloadUrl",
            "routeStationDownloadUrl", "stationDownloadUrl", "vehicleDownloadUrl");
    private static final ObjectMapper MAPPER = new ObjectMapper(tools.jackson.core.json.JsonFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public GbisMetadataClient(RestClient restClient, String baseUrl, String serviceKey) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
    }

    public GbisBulkMetadata fetch() {
        return collect(false, null, null).normalized();
    }

    public GbisCollectedSnapshot fetchWithRaw() {
        return collect(true, null, null);
    }

    public GbisCollectedSnapshot fetchWithRaw(long runId, TransitMetadataArchiveService archive) {
        return collect(true, runId, java.util.Objects.requireNonNull(archive));
    }

    private GbisCollectedSnapshot collect(boolean preserveRaw, Long runId, TransitMetadataArchiveService archive) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .queryParam("format", "json").queryParam("serviceKey", serviceKey).build(true).toUri();
            byte[] response = restClient.get().uri(uri).retrieve().body(byte[].class);
            Item item = parseBaseInfo(response, serviceKey);
            byte[] routes = download(item.routeDownloadUrl());
            byte[] stations = download(item.routeStationDownloadUrl());
            if (preserveRaw) {
                GbisCollectedSnapshot snapshot = GbisBulkMetadata.parseWithRaw(routes, stations, item.routeVersion(), item.routeStationVersion());
                if (archive != null) {
                    ArchiveContentSafety safety = new ArchiveContentSafety(serviceKey);
                    java.util.Set<String> checkedFields = new java.util.HashSet<>();
                    snapshot.rawRows().forEach(row -> row.fields().forEach((name, value) -> {
                        if (checkedFields.add(name)) ArchiveContentSafety.checkField(name);
                        safety.checkText(value);
                    }));
                    archive.store(runId, TransitSourceSystem.GBIS, "gbis_route", routes);
                    archive.store(runId, TransitSourceSystem.GBIS, "gbis_route_station", stations);
                }
                return snapshot;
            }
            return new GbisCollectedSnapshot(
                    GbisBulkMetadata.parse(routes, stations, item.routeVersion(), item.routeStationVersion()),
                    java.util.List.of(), item.routeVersion());
        } catch (TransitMetadataArchiveService.ArchiveFailure e) {
            throw e;
        } catch (org.springframework.web.client.RestClientException e) {
            preserveInterruption(e);
            throw new SourceUnavailableException();
        } catch (Exception e) {
            preserveInterruption(e);
            // Provider exceptions may contain a request URI, including the service key.
            throw new IllegalStateException("GBIS base-info snapshot could not be validated: "
                    + e.getClass().getSimpleName());
        }
    }

    static Item parseBaseInfo(byte[] bytes, String key) {
        try {
            if (bytes == null || bytes.length == 0 || key == null || key.isBlank()) throw new IllegalArgumentException();
            var tree = MAPPER.readTree(bytes);
            var itemNode = tree.path("response").path("msgBody").path("baseInfoItem");
            checkBaseJson(tree, itemNode, new ArchiveContentSafety(key), key);
            BaseInfo envelope = MAPPER.readValue(bytes, BaseInfo.class);
            if (envelope == null || envelope.response() == null || envelope.response().msgHeader() == null
                    || envelope.response().msgHeader().resultCode() == null
                    || envelope.response().msgHeader().resultCode() != 0
                    || envelope.response().msgBody() == null || envelope.response().msgBody().baseInfoItem() == null)
                throw new IllegalArgumentException();
            Item item = envelope.response().msgBody().baseInfoItem();
            if (item.routeVersion() == null || item.routeVersion().isBlank()
                    || !item.routeVersion().equals(item.routeStationVersion())) throw new IllegalArgumentException();
            validateDownloadUrl(item.routeDownloadUrl(), key);
            validateDownloadUrl(item.routeStationDownloadUrl(), key);
            return item;
        } catch (RuntimeException e) {
            throw new IllegalStateException("GBIS base-info response could not be safely validated");
        }
    }

    private static void checkBaseJson(tools.jackson.databind.JsonNode node, tools.jackson.databind.JsonNode item,
                                      ArchiveContentSafety safety, String key) {
        if (node == null) throw new IllegalArgumentException();
        if (node.isObject()) {
            for (var field : node.properties()) {
                ArchiveContentSafety.checkField(field.getKey());
                if (node == item && DOWNLOAD_URL_FIELDS.contains(field.getKey())) {
                    if (!field.getValue().isString()) throw new IllegalArgumentException();
                    validateDownloadUrl(field.getValue().asString(), key);
                } else checkBaseJson(field.getValue(), item, safety, key);
            }
        } else if (node.isArray()) node.forEach(child -> checkBaseJson(child, item, safety, key));
        else if (node.isString()) safety.checkText(node.asString());
    }

    private static void validateDownloadUrl(String url, String key) {
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || !"openapi.gbis.go.kr".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().startsWith("/") || url.indexOf('%') >= 0
                    || url.indexOf('\\') >= 0) throw new IllegalArgumentException();
            ArchiveContentSafety safety = new ArchiveContentSafety(key);
            safety.checkText(uri.getRawPath());
            rejectCredentialWords(uri.getRawPath());
            String query = uri.getRawQuery();
            if (query != null) {
                if (query.isEmpty()) throw new IllegalArgumentException();
                for (String part : query.split("&", -1)) {
                    String[] pair = part.split("=", -1);
                    if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]+")
                            || !pair[1].matches("[A-Za-z0-9._/-]+")) throw new IllegalArgumentException();
                    ArchiveContentSafety.checkField(pair[0]);
                    safety.checkText(pair[1]);
                    rejectCredentialWords(pair[1]);
                }
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid GBIS download URL"); }
    }

    private static void rejectCredentialWords(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (lower.contains("token") || lower.contains("apikey") || lower.contains("servicekey")
                || lower.contains("authorization") || lower.contains("bearer") || lower.contains("password")
                || lower.contains("signature")) throw new IllegalArgumentException();
    }

    private static void preserveInterruption(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static final class SourceUnavailableException extends IllegalStateException {
        public SourceUnavailableException() { super("GBIS source unavailable"); }
    }

    private byte[] download(String url) {
        if (url == null) throw new IllegalArgumentException("GBIS download URL missing");
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !"openapi.gbis.go.kr".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("GBIS download URL is not an official endpoint");
        }
        byte[] body = restClient.get().uri(uri).retrieve().body(byte[].class);
        if (body == null || body.length == 0) throw new IllegalArgumentException("GBIS download is empty");
        return body;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BaseInfo(Response response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Header msgHeader, Body msgBody) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Header(Integer resultCode) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Body(Item baseInfoItem) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String routeDownloadUrl, String routeVersion,
            String routeStationDownloadUrl, String routeStationVersion) { }
}
