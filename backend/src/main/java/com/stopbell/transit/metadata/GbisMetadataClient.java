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
    private final ObjectMapper mapper = new ObjectMapper();

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
            String response = restClient.get().uri(uri).retrieve().body(String.class);
            BaseInfo envelope = mapper.readValue(response, BaseInfo.class);
            if (envelope == null || envelope.response() == null || envelope.response().msgHeader() == null
                    || envelope.response().msgHeader().resultCode() == null
                    || envelope.response().msgHeader().resultCode() != 0
                    || envelope.response().msgBody() == null || envelope.response().msgBody().baseInfoItem() == null) {
                throw new IllegalStateException("GBIS base-info response failed");
            }
            Item item = envelope.response().msgBody().baseInfoItem();
            if (item.routeVersion() == null || !item.routeVersion().equals(item.routeStationVersion())) {
                throw new IllegalStateException("GBIS route and routeStation versions differ");
            }
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
