package com.stopbell.transit.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stopbell.transit.dto.gbis.GbisBusLocationItem;
import com.stopbell.transit.dto.gbis.GbisBusLocationResponse;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Reads GBIS real-time bus locations of one route (seats and crowding). GBIS is the Gyeonggi source behind
 * TAGO, so errors keep provider TAGO with source label GBIS; this client is read-only and not wired to Alarm/Boarding.
 */
public class GbisBusLocationClient {

    static final String OPERATION = "getBusLocationListv2";
    /** GBIS result code for "no result" (no vehicle in service). */
    static final int RESULT_NO_DATA = 4;

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;

    public GbisBusLocationClient(RestClient restClient, String baseUrl, String serviceKey) {
        if (baseUrl == null || baseUrl.isBlank() || serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("GBIS bus location base URL and service key must not be blank");
        }
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
    }

    /** @return the response; vehicles are an empty list when GBIS reports no result */
    public GbisBusLocationResponse fetch(String gbisRouteId) {
        if (gbisRouteId == null || gbisRouteId.isBlank()) {
            throw new IllegalArgumentException("GBIS route ID must not be blank");
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("routeId", UriUtils.encodeQueryParam(gbisRouteId, StandardCharsets.UTF_8))
                    .queryParam("format", "json")
                    .build(true).toUri();
            GbisBusLocationResponse response = restClient.get().uri(uri)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(GbisBusLocationResponse.class);
            validate(response);
            return response;
        } catch (TransitProviderClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw TransitProviderClientException.gbisHttp(
                    OPERATION, exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw TransitProviderClientException.gbisTransport(OPERATION, exception);
        } catch (RestClientException exception) {
            throw TransitProviderClientException.gbisProtocol(OPERATION, exception);
        }
    }

    public static List<GbisBusLocationItem> vehicles(GbisBusLocationResponse response) {
        var body = response.response().msgBody();
        return body == null || body.busLocationList() == null ? List.of() : body.busLocationList();
    }

    private void validate(GbisBusLocationResponse response) {
        if (response == null || response.response() == null || response.response().msgHeader() == null
                || response.response().msgHeader().resultCode() == null) {
            throw TransitProviderClientException.gbisProtocol(OPERATION, null);
        }
        int code = response.response().msgHeader().resultCode();
        if (code != 0 && code != RESULT_NO_DATA) {
            throw TransitProviderClientException.gbisProvider(OPERATION, String.valueOf(code));
        }
    }
}
