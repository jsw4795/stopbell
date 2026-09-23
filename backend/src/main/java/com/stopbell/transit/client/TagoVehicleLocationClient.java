package com.stopbell.transit.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

public class TagoVehicleLocationClient
        implements TransitProviderClient<TagoVehicleLocationResponse, TagoVehicleLocationRequestContext> {

    static final String OPERATION = "getRouteAcctoBusLcList";

    private final RestClient restClient;
    private final TransitClientProperties.Tago properties;

    public TagoVehicleLocationClient(RestClient restClient, TransitClientProperties.Tago properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public TransitProvider provider() {
        return TransitProvider.TAGO;
    }

    @Override
    public TagoVehicleLocationResponse fetchVehicleLocations(
            VehicleLocationRequest<TagoVehicleLocationRequestContext> request
    ) {
        try {
            TagoVehicleLocationResponse response = restClient.get()
                    .uri(buildUri(request))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(TagoVehicleLocationResponse.class);
            validate(response);
            return response;
        } catch (TransitProviderClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw TransitProviderClientException.http(
                    provider(), OPERATION, exception.getStatusCode().value(), exception
            );
        } catch (ResourceAccessException exception) {
            throw TransitProviderClientException.transport(provider(), OPERATION, exception);
        } catch (RestClientException exception) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, exception);
        }
    }

    private URI buildUri(VehicleLocationRequest<TagoVehicleLocationRequestContext> request) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .pathSegment(OPERATION)
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("cityCode", UriUtils.encodeQueryParam(request.context().cityCode(), StandardCharsets.UTF_8))
                .queryParam("routeId", UriUtils.encodeQueryParam(request.externalRouteId(), StandardCharsets.UTF_8))
                .queryParam("_type", "json")
                .build(true)
                .toUri();
    }

    private void validate(TagoVehicleLocationResponse response) {
        if (response == null || response.response() == null || response.response().header() == null) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, null);
        }

        String resultCode = response.response().header().resultCode();
        if (resultCode == null || resultCode.isBlank()) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, null);
        }
        if (!"00".equals(resultCode)) {
            throw TransitProviderClientException.provider(provider(), OPERATION, resultCode);
        }
        if (response.response().body() == null) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, null);
        }
    }
}
