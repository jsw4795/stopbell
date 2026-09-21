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

class SeoulBusVehicleDetailClientTest {

    private MockRestServiceServer server;
    private SeoulBusVehicleDetailClient client;

    @BeforeEach
    void set_up() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SeoulBusVehicleDetailClient(builder.build(), new TransitClientProperties.Seoul(
                "https://provider.test/seoul", "test-service-key"
        ));
    }

    @Test
    @DisplayName("서울 차량 detail의 stId, stOrd, stopFlag를 역직렬화한다")
    void fetch_with_detail_returns_response() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"0"},"msgBody":{"itemList":[{
                        "vehId":"111033668","plainNo":"서울75사2646","stId":"112000001","stOrd":22,
                        "stopFlag":1,"dataTm":"20260920190556","tmX":126.904572,"tmY":37.575465}]}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.fetchVehicleDetail("111033668");

        assertThat(response.body().itemList()).singleElement().satisfies(item -> {
            assertThat(item.stId()).isEqualTo("112000001");
            assertThat(item.stOrd()).isEqualTo(22);
            assertThat(item.stopFlag()).isOne();
            assertThat(item.tmX()).isEqualByComparingTo("126.904572");
            assertThat(item.tmY()).isEqualByComparingTo("37.575465");
        });
        server.verify();
    }

    @Test
    @DisplayName("서울 차량 detail logical failure는 PROVIDER 예외로 구분한다")
    void fetch_with_provider_failure_throws_provider_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"7","headerMsg":"provider failure"},"msgBody":{"itemList":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROVIDER, () -> client.fetchVehicleDetail("111033668"));
    }

    @Test
    @DisplayName("서울 차량 detail malformed envelope는 PROTOCOL 예외로 구분한다")
    void fetch_with_malformed_response_throws_protocol_exception() {
        expectRequest().andRespond(withSuccess("""
                        {"msgHeader":{"headerCd":"0"}}
                        """, MediaType.APPLICATION_JSON));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, () -> client.fetchVehicleDetail("111033668"));
    }

    @Test
    @DisplayName("서울 차량 detail HTTP non-success는 HTTP 예외로 구분한다")
    void fetch_with_http_failure_throws_http_exception() {
        expectRequest().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetchVehicleDetail("111033668"))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.HTTP);
                    assertThat(exception.httpStatus()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("서울 차량 detail transport failure는 TRANSPORT 예외로 구분한다")
    void fetch_with_transport_failure_throws_transport_exception() {
        expectRequest().andRespond(withException(new IOException("offline")));

        assertFailure(TransitProviderClientFailureKind.TRANSPORT, () -> client.fetchVehicleDetail("111033668"));
    }

    private ResponseActions expectRequest() {
        return server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/seoul/getBusPosByVehIdItem");
            assertThat(request.getURI().getQuery()).isEqualTo("serviceKey=test-service-key&vehId=111033668");
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
