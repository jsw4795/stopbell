package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProviderObservedFixtureTest {
    private static final Instant RECEIVED = Instant.parse("2026-09-09T10:06:51Z");
    private final Clock clock = Clock.fixed(RECEIVED, ZoneOffset.UTC);

    @Test
    @DisplayName("TAGO 실측 재구성은 차량과 정류장 및 GPS를 요청 노선의 도착 미확인 관측으로 변환한다")
    void reconstructed_tago_location_maps_to_observation() throws IOException {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new TagoVehicleLocationClient(builder.build(),
                new TransitClientProperties.Tago("https://provider.test", "https://metadata.test", "test-key"));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("routeId=route-a"))
                .andRespond(withSuccess(fixture("tago-route300.json"), MediaType.APPLICATION_JSON));
        var request = new VehicleLocationRequest<>("route-a", new TagoVehicleLocationRequestContext("31010"));
        var response = client.fetchVehicleLocations(request);
        assertThat(response.response().body().items().item()).singleElement().satisfies(item -> {
            assertThat(item.vehicleNo()).isEqualTo("vehicle-a");
            assertThat(item.nodeId()).isEqualTo("stop-a");
            assertThat(item.nodeOrder()).isEqualTo(7);
        });
        assertThat(new TagoTransitObservationMapper(clock).map(request, response)).singleElement().satisfies(o -> {
            assertThat(o.provider()).isEqualTo(TransitProvider.TAGO);
            assertThat(o.externalRouteId()).isEqualTo("route-a");
            assertThat(o.vehicleTrackingId()).isEqualTo("vehicle-a");
            assertThat(o.currentStopExternalId()).isEqualTo("stop-a");
            assertThat(o.currentStopOrder()).isEqualTo(7);
            assertThat(o.latitude()).isEqualByComparingTo("37.1");
            assertThat(o.longitude()).isEqualByComparingTo("127.1");
            assertThat(o.arrivalEvidence()).isEqualTo(ArrivalEvidence.UNAVAILABLE);
            assertThat(o.providerDataTime()).isNull();
            assertThat(o.observedAt()).isEqualTo(RECEIVED);
        });
        server.verify();
    }

    @Test
    @DisplayName("서울 실측 재구성은 roster 구간과 detail 정류장 및 도착에서 이동으로의 변화를 구분한다")
    void reconstructed_seoul_roster_and_detail_map_separately() throws IOException {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new TransitClientProperties.Seoul("http://ws.bus.go.kr", "test-key");
        var restClient = builder.build();
        var roster = new SeoulBusVehicleLocationClient(restClient, properties);
        var detail = new SeoulBusVehicleDetailClient(restClient, properties);
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("getBusPosByRtid"))
                .andRespond(withSuccess(fixture("seoul-roster.json"), MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("vehId=vehicle-a"))
                .andRespond(withSuccess(fixture("seoul-detail-arrived.xml"), MediaType.APPLICATION_XML));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("vehId=vehicle-a"))
                .andRespond(withSuccess(fixture("seoul-detail-moving.xml"), MediaType.APPLICATION_XML));
        var request = new VehicleLocationRequest<>("route-a", new SeoulBusVehicleLocationRequestContext());
        assertThat(roster.fetchVehicleLocations(request).body().itemList()).singleElement().satisfies(item -> {
            assertThat(item.vehId()).isEqualTo("vehicle-a");
            assertThat(item.sectOrd()).isEqualTo(21);
            assertThat(item.stopFlag()).isZero();
        });
        var mapper = new SeoulBusTransitObservationMapper(clock);
        var arrived = detail.fetchVehicleDetail("vehicle-a");
        assertThat(arrived.body().itemList()).singleElement().satisfies(item -> {
            assertThat(item.vehId()).isEqualTo("vehicle-a");
            assertThat(item.stId()).isEqualTo("stop-a");
            assertThat(item.stOrd()).isEqualTo(22);
            assertThat(item.dataTm()).isEqualTo("20260909190634");
        });
        assertThat(mapper.map(request, "vehicle-a", arrived)).singleElement().satisfies(o -> {
            assertThat(o.provider()).isEqualTo(TransitProvider.SEOUL_BUS);
            assertThat(o.externalRouteId()).isEqualTo("route-a");
            assertThat(o.vehicleTrackingId()).isEqualTo("vehicle-a");
            assertThat(o.currentStopExternalId()).isEqualTo("stop-a");
            assertThat(o.currentStopOrder()).isEqualTo(22);
            assertThat(o.arrivalEvidence()).isEqualTo(ArrivalEvidence.ARRIVED);
            assertThat(o.providerDataTime()).isEqualTo(Instant.parse("2026-09-09T10:06:34Z"));
        });
        assertThat(mapper.map(request, "vehicle-a", detail.fetchVehicleDetail("vehicle-a")))
                .singleElement().satisfies(o -> {
                    assertThat(o.currentStopExternalId()).isEqualTo("stop-a");
                    assertThat(o.currentStopOrder()).isEqualTo(22);
                    assertThat(o.arrivalEvidence()).isEqualTo(ArrivalEvidence.MOVING);
                    assertThat(o.providerDataTime()).isEqualTo(Instant.parse("2026-09-09T10:06:48Z"));
                });
        server.verify();
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/transit/task803/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
