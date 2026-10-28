package com.stopbell.transit.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

public class SeoulBusVehicleDetailClient {

    static final String OPERATION = "getBusPosByVehIdItem";
    // The guide distinguishes the operation name from its HTTP callback path.
    static final String REQUEST_PATH = "/api/rest/buspos/getBusPosByVehId";
    private static final String NORMAL_EMPTY_HEADER_CODE = "4";
    private static final String SUCCESS_HEADER_CODE = "0";

    private final RestClient restClient;
    private final TransitClientProperties.Seoul properties;

    public SeoulBusVehicleDetailClient(RestClient restClient, TransitClientProperties.Seoul properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public SeoulBusVehicleDetailResponse fetchVehicleDetail(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("Vehicle ID must not be blank");
        }

        try {
            var entity = restClient.get()
                    .uri(buildUri(vehicleId))
                    .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (httpRequest, response) -> {
                        throw TransitProviderClientException.http(provider(), OPERATION,
                                response.getStatusCode().value(), null);
                    })
                    .toEntity(byte[].class);
            var response = SeoulBusResponseReader.detail(entity.getBody(), entity.getHeaders().getContentType());
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

    private TransitProvider provider() {
        return TransitProvider.SEOUL_BUS;
    }

    private URI buildUri(String vehicleId) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .replacePath(REQUEST_PATH)
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("vehId", UriUtils.encodeQueryParam(vehicleId, StandardCharsets.UTF_8))
                .build(true)
                .toUri();
    }

    private void validate(SeoulBusVehicleDetailResponse response) {
        if (response == null || response.header() == null || response.header().headerCd() == null
                || response.header().headerCd().isBlank()) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, null);
        }

        String headerCode = response.header().headerCd();
        if (NORMAL_EMPTY_HEADER_CODE.equals(headerCode)) {
            return;
        }
        if (!SUCCESS_HEADER_CODE.equals(headerCode)) {
            throw TransitProviderClientException.provider(provider(), OPERATION, headerCode);
        }
        if (response.body() == null || response.body().itemList() == null) {
            throw TransitProviderClientException.protocol(provider(), OPERATION, null);
        }
    }
}
