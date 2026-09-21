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

class SeoulBusVehicleLocationClientTest {

    private MockRestServiceServer server;
    private SeoulBusVehicleLocationClient client;

    @BeforeEach
    void set_up() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SeoulBusVehicleLocationClient(builder.build(), new TransitClientProperties.Seoul(
                "https://provider.test/seoul", "test-service-key"
        ));
    }

    @Test
    @DisplayName("서울 노선 roster의 정상 차량 목록을 반환한다")
    void fetch_with_vehicles_returns_response() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"0"},"msgBody":{"itemList":[{
                        "vehId":"111033668","plainNo":"서울75사2646","sectOrd":21,
                        "sectionId":"112000001","nextStId":"112000003"}]}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchVehicleLocations(request());

        assertThat(response.body().itemList()).singleElement().satisfies(item -> {
            assertThat(item.vehId()).isEqualTo("111033668");
            assertThat(item.sectOrd()).isEqualTo(21);
            assertThat(item.nextStId()).isEqualTo("112000003");
        });
        server.verify();
    }

    @Test
    @DisplayName("서울 roster의 headerCd 4 no-result는 정상 empty로 반환한다")
    void fetch_with_normal_empty_response_returns_response() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"4","headerMsg":"결과가 없습니다."},"msgBody":{"itemList":[]}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchVehicleLocations(request());

        assertThat(response.header().headerCd()).isEqualTo("4");
        assertThat(response.body().itemList()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("서울 roster logical failure는 PROVIDER 예외로 구분한다")
    void fetch_with_provider_failure_throws_provider_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"7","headerMsg":"provider failure"},"msgBody":{"itemList":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROVIDER, () -> client.fetchVehicleLocations(request()));
    }

    @Test
    @DisplayName("서울 roster malformed envelope는 PROTOCOL 예외로 구분한다")
    void fetch_with_malformed_response_throws_protocol_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"0"}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, () -> client.fetchVehicleLocations(request()));
    }

    @Test
    @DisplayName("서울 roster HTTP non-success는 HTTP 예외로 구분한다")
    void fetch_with_http_failure_throws_http_exception() {
        expectRequest().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetchVehicleLocations(request()))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.HTTP);
                    assertThat(exception.httpStatus()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("서울 roster transport failure는 TRANSPORT 예외로 구분한다")
    void fetch_with_transport_failure_throws_transport_exception() {
        expectRequest().andRespond(withException(new IOException("offline")));

        assertFailure(TransitProviderClientFailureKind.TRANSPORT, () -> client.fetchVehicleLocations(request()));
    }

    private VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request() {
        return new VehicleLocationRequest<>("100100118", new SeoulBusVehicleLocationRequestContext());
    }

    private ResponseActions expectRequest() {
        return server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/seoul/getBusPosByRtid");
            assertThat(request.getURI().getQuery())
                    .isEqualTo("serviceKey=test-service-key&busRouteId=100100118");
        });
    }

    private void assertFailure(TransitProviderClientFailureKind failureKind, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(failureKind);
                    assertThat(exception.provider()).isEqualTo(TransitProvider.SEOUL_BUS);
                });
        server.verify();
    }
}
