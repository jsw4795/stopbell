package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.GbisBusLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteVehiclesResponse;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.SeatStatus;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.Status;
import com.stopbell.transit.dto.gbis.GbisBusLocationItem;
import com.stopbell.transit.dto.gbis.GbisBusLocationResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.service.BusRouteVehicleMetadataReader.Occurrence;
import com.stopbell.transit.service.BusRouteVehicleMetadataReader.RouteMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusRouteVehicleServiceTest {
    private final BusRouteVehicleMetadataReader metadata = mock(BusRouteVehicleMetadataReader.class);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final GbisBusLocationClient gbis = mock(GbisBusLocationClient.class);
    private final MutableClock clock = new MutableClock();

    private BusRouteVehicleService service(int seoulLimit, int tagoLimit, int gbisLimit) {
        return new BusRouteVehicleService(metadata, tago, seoul, gbis,
                new ProviderDailyCallBudget(clock, seoulLimit, tagoLimit, gbisLimit), clock,
                Duration.ofSeconds(15), Duration.ofMinutes(5));
    }

    private BusRouteVehicleService service() {
        return service(100, 100, 100);
    }

    private static List<Occurrence> stops() {
        return List.of(new Occurrence(11L, "s1", 10), new Occurrence(12L, "s2", 20),
                new Occurrence(13L, "s3", 30));
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

    private static GbisBusLocationItem gbisItem(String plate, int seats, Integer crowded) {
        return new GbisBusLocationItem(plate, 1L, 200000123L, 11, 1L, 1, 1, seats, crowded, null, null);
    }

    @Test
    @DisplayName("경기 좌석 노선은 정규화한 번호와 노선이 일치하는 GBIS 좌석만 결합하고 Provider ID는 노출하지 않는다")
    void gyeonggi_merges_seats_by_normalized_number() {
        gyeonggi(BusRouteType.CITY_EXPRESS_SEAT, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(
                tago("경기74아3491", "s2", 20), tago("경기74아 3492", "s3", 30)));
        when(gbis.fetch("200000123")).thenReturn(gbisResponse(gbisItem("경기74아3491", 12, null),
                gbisItem("경기74아3492", 0, null)));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.AVAILABLE);
        assertThat(response.observedAt()).isEqualTo(NOW);
        var first = response.vehicles().get(0);
        assertThat(first.vehicleNumber()).isEqualTo("경기74아3491");
        assertThat(first.occurrenceId()).isEqualTo(12L);
        assertThat(first.seat().status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(first.seat().remaining()).isEqualTo(12);
        assertThat(response.vehicles().get(1).seat().status()).isEqualTo(SeatStatus.NONE);
    }

    @Test
    @DisplayName("번호가 GBIS에 없거나 -1이면 좌석을 숨기고 PARTIAL로 표시한다")
    void number_mismatch_hides_seat() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아9999", 5, null)));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.PARTIAL);
        assertThat(response.vehicles().getFirst().seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        assertThat(response.vehicles().getFirst().seat().remaining()).isNull();
    }

    @Test
    @DisplayName("GBIS 중복 번호는 좌석을 결합하지 않는다")
    void duplicate_gbis_number_hides_seat() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", 5, null),
                gbisItem("경기74아3491", 6, null)));

        var response = service().find(1L);

        assertThat(response.vehicles().getFirst().seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        assertThat(response.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("GBIS 실패는 TAGO 위치를 유지한 채 좌석만 숨기고 PARTIAL로 응답한다")
    void gbis_failure_is_partial() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        when(gbis.fetch(any())).thenThrow(TransitProviderClientException.gbisTransport("op", null));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.PARTIAL);
        assertThat(response.vehicles()).hasSize(1);
        assertThat(response.vehicles().getFirst().occurrenceId()).isEqualTo(12L);
    }

    @Test
    @DisplayName("좌석·혼잡도 비대상 유형은 GBIS를 호출하지 않고 일반형은 GBIS 혼잡도를 결합한다")
    void gbis_called_only_for_supported_types() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        assertThat(service().find(1L).status()).isEqualTo(Status.AVAILABLE);
        verifyNoInteractions(gbis);

        gyeonggi(BusRouteType.CITY_LOCAL, stops());
        when(gbis.fetch(any())).thenReturn(gbisResponse(gbisItem("경기74아3491", -1, 3)));
        var local = service().find(1L);
        assertThat(local.vehicles().getFirst().congestion()).isEqualTo(3);
        assertThat(local.vehicles().getFirst().seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("차량이 없으면 EMPTY이고 GBIS를 호출하지 않는다")
    void empty_roster() {
        gyeonggi(BusRouteType.CITY_SEAT, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse());

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.EMPTY);
        assertThat(response.vehicles()).isEmpty();
        verifyNoInteractions(gbis);
    }

    @Test
    @DisplayName("반복 정류장 노선은 occurrenceId를 null로 두고 위치 미확인 PARTIAL로 응답한다")
    void repeated_stop_has_null_occurrence() {
        gyeonggi(BusRouteType.VILLAGE, List.of(new Occurrence(11L, "same", 10), new Occurrence(12L, "x", 20),
                new Occurrence(13L, "same", 30)));
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "same", 10)));

        var response = service().find(1L);

        assertThat(response.vehicles().getFirst().occurrenceId()).isNull();
        assertThat(response.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("서울은 roster의 위치와 혼잡도를 매핑하고 좌석은 제공하지 않으며 운행 종료 차량은 제외한다")
    void seoul_congestion_and_position() {
        seoulRoute(stops());
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                seoulItem("서울74사1234", 10, "s2", "1", "5"), seoulItem("서울74사1235", 20, "s3", "1", "0"),
                seoulItem("서울74사1236", 20, "s3", "1", "6"), seoulItem("서울74사9999", 20, "s3", "0", "3")));

        var response = service().find(1L);

        assertThat(response.status()).isEqualTo(Status.AVAILABLE);
        assertThat(response.vehicles()).extracting("vehicleNumber")
                .containsExactly("서울74사1234", "서울74사1235", "서울74사1236");
        assertThat(response.vehicles().get(0).occurrenceId()).isEqualTo(11L);
        assertThat(response.vehicles().get(0).congestion()).isEqualTo(3);
        assertThat(response.vehicles().get(1).congestion()).isNull();
        assertThat(response.vehicles().get(2).congestion()).isEqualTo(4);
        assertThat(response.vehicles().get(0).seat().status()).isEqualTo(SeatStatus.UNAVAILABLE);
        verifyNoInteractions(gbis, tago);
    }

    private static SeoulBusVehicleLocationItem seoulItem(String number, int sectOrd, String next, String run,
            String congestion) {
        return new SeoulBusVehicleLocationItem("v" + number, number, sectOrd, "sec", 1, DATA_TIME,
                null, null, next, run, congestion);
    }

    @Test
    @DisplayName("서울 구간 순번과 다음 정류장이 맞지 않으면 위치를 연결하지 않는다")
    void seoul_unverified_position() {
        seoulRoute(stops());
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                seoulItem("서울74사1234", 10, "not-next", "1", "3")));

        var response = service().find(1L);

        assertThat(response.vehicles().getFirst().occurrenceId()).isNull();
        assertThat(response.status()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("TTL 안의 재조회는 Provider를 다시 호출하지 않고 만료 후에는 호출한다")
    void cache_hit_and_expiry() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        var service = service();

        service.find(1L);
        clock.now = clock.now.plusSeconds(10);
        service.find(1L);
        verify(tago, times(1)).fetchVehicleLocations(any());

        clock.now = clock.now.plusSeconds(10);
        service.find(1L);
        verify(tago, times(2)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("동시 요청은 하나의 Provider 호출로 합친다")
    void concurrent_requests_are_coalesced() throws Exception {
        gyeonggi(BusRouteType.VILLAGE, stops());
        var release = new CountDownLatch(1);
        when(tago.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return tagoResponse(tago("경기74아3491", "s2", 20));
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
        verify(tago, times(1)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("일일 상한에 도달하면 Provider를 호출하지 않고 마지막 성공 결과를 STALE로, 없으면 503으로 응답한다")
    void daily_limit_returns_stale_or_unavailable() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)));
        var service = service(100, 1, 100);

        assertThat(service.find(1L).status()).isEqualTo(Status.AVAILABLE);
        clock.now = clock.now.plusSeconds(20);
        var stale = service.find(1L);
        assertThat(stale.status()).isEqualTo(Status.STALE);
        assertThat(stale.observedAt()).isEqualTo(NOW);
        assertThat(stale.vehicles()).hasSize(1);
        verify(tago, times(1)).fetchVehicleLocations(any());

        var fresh = service(100, 0, 100);
        assertThatThrownBy(() -> fresh.find(1L)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        verify(tago, times(1)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("Provider 실패는 마지막 성공 결과를 STALE로 돌려주고 운행 종료(EMPTY)로 해석하지 않는다")
    void provider_failure_is_stale_not_empty() {
        gyeonggi(BusRouteType.VILLAGE, stops());
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "s2", 20)))
                .thenThrow(TransitProviderClientException.transport(TransitProvider.TAGO, "op", null));
        var service = service();

        service.find(1L);
        clock.now = clock.now.plusSeconds(20);

        assertThat(service.find(1L).status()).isEqualTo(Status.STALE);
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.find(1L)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("없는 Route는 BUS_ROUTE_NOT_FOUND를 그대로 전달한다")
    void unknown_route() {
        when(metadata.read(9L)).thenThrow(new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        assertThatThrownBy(() -> service().find(9L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(tago, seoul, gbis);
    }
}
