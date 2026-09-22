package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

class TagoVehicleLocationClientTest {

    private MockRestServiceServer server;
    private TagoVehicleLocationClient client;

    @BeforeEach
    void set_up() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TagoVehicleLocationClient(builder.build(), new TransitClientProperties.Tago(
                "https://provider.test/tago", "https://provider.test/tago-metadata", "test-service-key"
        ));
    }

    @Test
    @DisplayName("TAGO 노선 차량 위치의 정상 차량 목록을 반환한다")
    void fetch_with_vehicles_returns_response() {
        expectRequest().andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{
                        "vehicleno":"경기70바5770","nodeid":"GGB228001174","nodeord":1,
                        "gpslati":37.2402833,"gpslong":127.0824}]},"totalCount":1}}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchVehicleLocations(request());

        assertThat(response.response().body().items().item()).singleElement().satisfies(item -> {
            assertThat(item.vehicleNo()).isEqualTo("경기70바5770");
            assertThat(item.nodeOrder()).isEqualTo(1);
        });
        server.verify();
    }

    @Test
    @DisplayName("TAGO 정상 empty 차량 위치 응답은 예외 없이 반환한다")
    void fetch_with_empty_response_returns_response() {
        expectRequest().andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[]},"totalCount":0}}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchVehicleLocations(request());

        assertThat(response.response().body().items().item()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("TAGO logical failure는 PROVIDER 예외로 구분한다")
    void fetch_with_provider_failure_throws_provider_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"99","resultMsg":"provider failure"},"body":{"items":{"item":[]}}}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROVIDER, () -> client.fetchVehicleLocations(request()));
    }

    @Test
    @DisplayName("TAGO malformed envelope는 PROTOCOL 예외로 구분한다")
    void fetch_with_malformed_response_throws_protocol_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00"}}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, () -> client.fetchVehicleLocations(request()));
    }

    @Test
    @DisplayName("TAGO HTTP non-success는 HTTP 예외로 구분한다")
    void fetch_with_http_failure_throws_http_exception() {
        expectRequest().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetchVehicleLocations(request()))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.HTTP);
                    assertThat(exception.httpStatus()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("TAGO transport failure는 TRANSPORT 예외로 구분한다")
    void fetch_with_transport_failure_throws_transport_exception() {
        expectRequest().andRespond(withException(new IOException("offline")));

        assertFailure(TransitProviderClientFailureKind.TRANSPORT, () -> client.fetchVehicleLocations(request()));
    }

    private VehicleLocationRequest<TagoVehicleLocationRequestContext> request() {
        return new VehicleLocationRequest<>("GGB200000112", new TagoVehicleLocationRequestContext("31010"));
    }

    private ResponseActions expectRequest() {
        return server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/tago/getRouteAcctoBusLcList");
            assertThat(request.getURI().getQuery()).isEqualTo(
                    "serviceKey=test-service-key&cityCode=31010&routeId=GGB200000112&_type=json"
            );
        });
    }

    private void assertFailure(TransitProviderClientFailureKind failureKind, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(failureKind);
                    assertThat(exception.provider()).isEqualTo(TransitProvider.TAGO);
                });
        server.verify();
    }
}
