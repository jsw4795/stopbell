package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.GbisBusLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteVehiclesResponse;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.PositionState;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.SeatStatus;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.Status;
import com.stopbell.transit.dto.gbis.GbisBusLocationItem;
import com.stopbell.transit.dto.gbis.GbisBusLocationResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.service.BusRouteVehicleMetadataReader.Occurrence;
import com.stopbell.transit.service.BusRouteVehicleMetadataReader.RouteMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BusRouteVehicleServiceTest {
    private final BusRouteVehicleMetadataReader metadata = mock(BusRouteVehicleMetadataReader.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final GbisBusLocationClient gbis = mock(GbisBusLocationClient.class);
    private final MutableClock clock = new MutableClock();

    private BusRouteVehicleService service(int seoulLimit, int tagoLimit, int gbisLimit) {
        return new BusRouteVehicleService(metadata, seoul, gbis,
                new ProviderDailyCallBudget(clock, seoulLimit, tagoLimit, gbisLimit), clock,
                Duration.ofSeconds(15), Duration.ofMinutes(5));
    }

    private BusRouteVehicleService service() {
        return service(100, 100, 100);
    }

    private static List<Occurrence> stops() {
        return List.of(new Occurrence(11L, "s1", 1), new Occurrence(12L, "s2", 2),
                new Occurrence(13L, "s3", 3));
    }

    private void gyeonggi(BusRouteType type, List<Occurrence> traversal) {
        when(metadata.read(1L)).thenReturn(new RouteMeta(1L, TransitProvider.TAGO, "GGB200000123", "31010",
                type, traversal));
    }

    private void seoulRoute(List<Occurrence> traversal) {
        when(metadata.read(1L)).thenReturn(new RouteMeta(1L, TransitProvider.SEOUL_BUS, "100100118", null,
                BusRouteType.TRUNK, traversal));
    }

    private static GbisBusLocationResponse gbisResponse(GbisBusLocationItem... items) {
        return new GbisBusLocationResponse(new GbisBusLocationResponse.Response(
                new GbisBusLocationResponse.MsgHeader(null, 0, null),
                new GbisBusLocationResponse.MsgBody(List.of(items))));
    }

    private static GbisBusLocationItem gbisItem(String plate, int seq, int seats, Integer crowded, int state) {
        return new GbisBusLocationItem(plate, 1L, 200000123L, 11, 1L, seq, state, seats, crowded, null, null);
    }

    @Test
    @DisplayName("경기는 GBIS 한 번 호출로 위치·상태·좌석·혼잡을 매핑하고 유형과 무관하게 TAGO를 호출하지 않는다")
    void gyeonggi_uses_gbis_only() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(gbis.fetch("200000123")).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 12, null, 1),
                gbisItem("경기74아3492", 3, 0, null, 2), gbisItem("경기74아3493", 1, -1, 3, 0)));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.AVAILABLE);
        assertThat(response.observedAt()).isEqualTo(NOW);
        var v = response.vehicles();
        assertThat(v.get(0).vehicleNumber()).isEqualTo("경기74아3493");
        assertThat(v.get(0).occurrenceId()).isEqualTo(11L);
        assertThat(v.get(0).positionState()).isEqualTo(PositionState.DEPARTED);
        assertThat(v.get(0).seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        assertThat(v.get(0).congestion()).isNull();
        assertThat(v.get(1).occurrenceId()).isEqualTo(12L);
        assertThat(v.get(1).positionState()).isEqualTo(PositionState.AT_STOP);
        assertThat(v.get(1).seat().remaining()).isEqualTo(12);
        assertThat(v.get(2).positionState()).isEqualTo(PositionState.DEPARTED);
        assertThat(v.get(2).seat().status()).isEqualTo(SeatStatus.NONE);
        verify(gbis, times(1)).fetch(any());
    }

    @Test
    @DisplayName("좌석 유형은 좌석만, 혼잡도 유형은 혼잡도만 표시한다")
    void seat_or_crowded_by_route_type() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 12, 3, 1)));
        var seat = service().find(1L).vehicles().getFirst();
        assertThat(seat.seat().remaining()).isEqualTo(12);
        assertThat(seat.congestion()).isNull();

        gyeonggi(BusRouteType.CITY_LOCAL, stops());
        var crowded = service().find(1L).vehicles().getFirst();
        assertThat(crowded.seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        assertThat(crowded.congestion()).isEqualTo(3);
    }

    @Test
    @DisplayName("GBIS 실패는 TAGO fallback 없이 이전 결과가 없으면 503으로 처리한다")
    void gbis_failure_is_unavailable() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(gbis.fetch(any())).thenThrow(TransitProviderClientException.gbisTransport("op", null));

        assertThatThrownBy(() -> service().find(1L)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("차량이 없으면 EMPTY이다")
    void empty_roster() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse());

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.EMPTY);
        assertThat(response.vehicles()).isEmpty();
    }

    @Test
    @DisplayName("stationSeq에 해당하는 정류장이 없으면 해당 차량만 위치를 연결하지 않고 PARTIAL로 표시한다")
    void gyeonggi_unverified_position() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 99, 5, null, 1)));

        var response = service().find(1L);

        assertThat(response.vehicles().getFirst().occurrenceId()).isNull();
        assertThat(response.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("서울은 roster의 위치와 혼잡도를 매핑하고 좌석은 제공하지 않으며 운행 종료 차량은 제외한다")
    void seoul_congestion_and_position() {
        seoulRoute(stops());
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                seoulItem("서울74사1234", 1, "s2", "1", "5"), seoulItem("서울74사1235", 2, "s3", "1", "0"),
                seoulItem("서울74사1236", 2, "s3", "1", "6"), seoulItem("서울74사9999", 2, "s3", "0", "3")));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.AVAILABLE);
        assertThat(response.vehicles()).extracting("vehicleNumber")
                .containsExactly("서울74사1234", "서울74사1235", "서울74사1236");
        assertThat(response.vehicles().get(0).occurrenceId()).isEqualTo(11L);
        assertThat(response.vehicles().get(0).congestion()).isEqualTo(3);
        assertThat(response.vehicles().get(1).congestion()).isNull();
        assertThat(response.vehicles().get(2).congestion()).isEqualTo(4);
        assertThat(response.vehicles().get(0).seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        assertThat(response.vehicles()).extracting("positionState")
                .containsExactly(PositionState.AT_STOP, PositionState.AT_STOP, PositionState.AT_STOP);
        verifyNoInteractions(gbis);
    }

    private static SeoulBusVehicleLocationItem seoulItem(String number, int sectOrd, String next, String run,
            String congestion) {
        return seoulItem(number, sectOrd, 1, next, run, congestion);
    }

    private static SeoulBusVehicleLocationItem seoulItem(String number, int sectOrd, int stopFlag, String next,
            String run, String congestion) {
        return new SeoulBusVehicleLocationItem("v" + number, number, sectOrd, "sec", stopFlag, DATA_TIME,
                null, null, next, run, congestion);
    }

    @Test
    @DisplayName("서울 구간 순번에 해당하는 정류장이 없으면 해당 차량만 위치를 연결하지 않는다")
    void seoul_unverified_position() {
        seoulRoute(stops());
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                seoulItem("서울74사1234", 99, 0, "s2", "1", "3")));

        var response = service().find(1L);

        assertThat(response.vehicles().getFirst().occurrenceId()).isNull();
        assertThat(response.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("TTL 안의 재조회는 Provider를 다시 호출하지 않고 만료 후에는 호출한다")
    void cache_hit_and_expiry() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 5, null, 1)));
        var service = service();

        service.find(1L);
        clock.now = clock.now.plusSeconds(10);
        service.find(1L);
        verify(gbis, times(1)).fetch(any());

        clock.now = clock.now.plusSeconds(10);
        service.find(1L);
        verify(gbis, times(2)).fetch(any());
    }

    @Test
    @DisplayName("단일 객체 1대 캐시는 TTL 경계에서 배열 2대로 갱신하고 이후 빈 응답도 반영한다")
    void fixture_cache_refreshes_vehicle_count() {
        when(metadata.read(1L)).thenReturn(new RouteMeta(1L, TransitProvider.TAGO, "GGB229000013", "31200",
                BusRouteType.CITY_SEAT, stops()));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GbisBusLocationClient(builder.build(), "https://provider.test/gbis", "test-key");
        for (String fixture : List.of("single-object", "array", "empty-array")) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://provider.test/gbis")))
                    .andRespond(withSuccess(
                            new ClassPathResource(
                                    "transit/gbis-vehicle-location/" + fixture + ".json"),
                            MediaType.APPLICATION_JSON));
        }
        var service = new BusRouteVehicleService(metadata, seoul, client,
                new ProviderDailyCallBudget(clock, 100, 100, 100), clock,
                Duration.ofSeconds(15), Duration.ofMinutes(5));

        var single = service.find(1L);
        assertThat(single.status()).isEqualTo(Status.AVAILABLE);
        assertThat(single.vehicles()).hasSize(1);
        clock.now = clock.now.plusSeconds(14);
        assertThat(service.find(1L)).isSameAs(single);
        clock.now = clock.now.plusSeconds(1);
        var multiple = service.find(1L);
        assertThat(multiple.status()).isEqualTo(Status.AVAILABLE);
        assertThat(multiple.vehicles()).hasSize(2);
        assertThat(multiple.observedAt()).isEqualTo(clock.now);
        assertThat(service.find(1L)).isSameAs(multiple);
        clock.now = clock.now.plusSeconds(15);
        var empty = service.find(1L);
        assertThat(empty.status()).isEqualTo(Status.EMPTY);
        assertThat(empty.vehicles()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("동시 요청은 하나의 Provider 호출로 합친다")
    void concurrent_requests_are_coalesced() throws Exception {
        gyeonggi(BusRouteType.VILLAGE, stops());
        var release = new CountDownLatch(1);
        when(gbis.fetch(any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return gbisResponse(gbisItem("경기74아3491", 2, 5, null, 1));
        });
        var service = service();
        var pool = Executors.newFixedThreadPool(5);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<BusRouteVehiclesResponse>>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> service.find(1L)));
            }
            Thread.sleep(300);
            release.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).vehicles()).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(gbis, times(1)).fetch(any());
    }

    @Test
    @DisplayName("호출권을 얻은 직후 캐시가 이미 갱신돼 있으면 Provider를 다시 호출하지 않는다")
    @SuppressWarnings("unchecked")
    void rechecks_cache_after_acquiring_call_right() throws Exception {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 5, null, 1)));
        var service = service();
        service.find(1L);
        clock.now = clock.now.plusSeconds(20);
        var cache = (java.util.Map<Long, Object>) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "cache");
        Object expired = cache.get(1L);
        var entryType = expired.getClass();
        var ctor = entryType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var response = (BusRouteVehiclesResponse) entryType.getDeclaredMethod("response").invoke(expired);
        // Another request refreshes the cache right before this one wins the call right.
        var racing = new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<BusRouteVehiclesResponse>>() {
            @Override
            public java.util.concurrent.CompletableFuture<BusRouteVehiclesResponse> putIfAbsent(Long key,
                    java.util.concurrent.CompletableFuture<BusRouteVehiclesResponse> value) {
                try {
                    var refreshed = ctor.newInstance(response, clock.now.plusSeconds(15));
                    cache.put(key, refreshed);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
                return super.putIfAbsent(key, value);
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service, "inFlight", racing);

        assertThat(service.find(1L).status()).isEqualTo(Status.AVAILABLE);
        verify(gbis, times(1)).fetch(any());
        assertThat(racing).isEmpty();
    }

    @Test
    @DisplayName("일일 상한에 도달하면 Provider를 호출하지 않고 마지막 성공 결과를 STALE로, 없으면 503으로 응답한다")
    void daily_limit_returns_stale_or_unavailable() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 5, null, 1)));
        var service = service(100, 100, 1);

        assertThat(service.find(1L).status()).isEqualTo(Status.AVAILABLE);
        clock.now = clock.now.plusSeconds(20);
        var stale = service.find(1L);
        assertThat(stale.status()).isEqualTo(Status.STALE);
        assertThat(stale.observedAt()).isEqualTo(NOW);
        assertThat(stale.vehicles()).hasSize(1);
        verify(gbis, times(1)).fetch(any());

        var fresh = service(100, 100, 0);
        assertThatThrownBy(() -> fresh.find(1L)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        verify(gbis, times(1)).fetch(any());
    }

    @Test
    @DisplayName("Provider 실패는 마지막 성공 결과를 STALE로 돌려주고 운행 종료(EMPTY)로 해석하지 않는다")
    void provider_failure_is_stale_not_empty() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 2, 5, null, 1)))
                .thenThrow(TransitProviderClientException.gbisTransport("op", null));
        var service = service();

        service.find(1L);
        clock.now = clock.now.plusSeconds(20);

        var stale = service.find(1L);
        assertThat(stale.status()).isEqualTo(Status.STALE);
        assertThat(stale.vehicles().get(0).seat()).isEqualTo(BusRouteVehiclesResponse.Seat.UNAVAILABLE);
        assertThat(stale.vehicles().get(0).occurrenceId()).isNotNull();
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.find(1L)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("없는 Route는 BUS_ROUTE_NOT_FOUND를 그대로 전달한다")
    void unknown_route() {
        when(metadata.read(9L)).thenThrow(new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        assertThatThrownBy(() -> service().find(9L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(seoul, gbis);
    }
}
