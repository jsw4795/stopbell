package com.stopbell.transit.metadata;

import java.net.URI;

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
            return GbisBulkMetadata.parse(routes, stations, item.routeVersion(), item.routeStationVersion());
        } catch (Exception e) {
            // Provider exceptions may contain a request URI, including the service key.
            throw new IllegalStateException("GBIS base-info snapshot could not be validated: "
                    + e.getClass().getSimpleName());
        }
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
