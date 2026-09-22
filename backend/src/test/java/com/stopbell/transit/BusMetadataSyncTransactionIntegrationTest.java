package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import com.stopbell.transit.service.BusMetadataSyncService;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BusMetadataSyncTransactionIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private BusMetadataSyncService metadataSyncService;

    @Autowired
    private BusRouteRepository busRouteRepository;

    @Autowired
    private BusMetadataSyncStateRepository syncStateRepository;

    @Test
    @DisplayName("Provider sync 중 Route 실패가 발생하면 완료된 Route는 유지하고 cleanup을 실행하지 않는다")
    void retain_completed_routes_and_skip_cleanup_when_a_route_sync_fails() {
        metadataSyncService.syncRoute(routeSnapshot("existing-route", "7000", validStop("existing-stop", "기존 정류장", 1)));

        BusRouteMetadataSnapshot completedRoute = routeSnapshot(
                "completed-route", "7001", validStop("completed-stop", "완료 정류장", 1)
        );
        BusRouteMetadataSnapshot failingRoute = routeSnapshot(
                "failing-route", "7002", validStop("failing-stop", "x".repeat(256), 1)
        );

        assertThatThrownBy(() -> metadataSyncService.syncCompleteProviderSnapshot(
                new CompleteBusMetadataSnapshot(TransitProvider.TAGO, List.of(completedRoute, failingRoute))
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(busRouteRepository
                .findByProviderAndExternalRouteId(TransitProvider.TAGO, "completed-route"))
                .isPresent();
        assertThat(busRouteRepository
                .findByProviderAndExternalRouteId(TransitProvider.TAGO, "existing-route"))
                .isPresent();
        assertThat(busRouteRepository
                .findByProviderAndExternalRouteId(TransitProvider.TAGO, "failing-route"))
                .isEmpty();
    }

    @Test
    @DisplayName("complete provider sync가 성공하면 마지막 complete sync 시각을 갱신한다")
    void update_last_complete_sync_at_after_successful_complete_sync() {
        Instant previousCompletion = Instant.parse("2026-09-01T00:00:00Z");
        syncStateRepository.saveAndFlush(new BusMetadataSyncState(TransitProvider.SEOUL_BUS, previousCompletion));

        metadataSyncService.syncCompleteProviderSnapshot(new CompleteBusMetadataSnapshot(
                TransitProvider.SEOUL_BUS,
                List.of(new BusRouteMetadataSnapshot(
                        TransitProvider.SEOUL_BUS,
                        "100100447",
                        "7016",
                        null,
                        List.of(new BusStopOccurrenceMetadataSnapshot(
                                "111000907",
                                "은평공영차고지",
                                new BigDecimal("37.5918053"),
                                new BigDecimal("126.8838079"),
                                1
                        ))
                ))
        ));

        assertThat(syncStateRepository.findById(TransitProvider.SEOUL_BUS).orElseThrow().getLastCompleteSyncAt())
                .isAfter(previousCompletion);
    }

    @Test
    @DisplayName("필터링된 서울 complete snapshot cleanup은 경기와 인천을 SEOUL_BUS에 남기지 않고 TAGO 영역을 유지한다")
    void clean_up_only_seoul_provider_after_route_scope_filtering() {
        metadataSyncService.syncRoute(new BusRouteMetadataSnapshot(
                TransitProvider.SEOUL_BUS,
                "241006973",
                "잘못 저장된 경기 노선",
                null,
                List.of(new BusStopOccurrenceMetadataSnapshot(
                        "seoul-stale-stop", "기존 정류장", new BigDecimal("37.0"), new BigDecimal("127.0"), 1
                ))
        ));
        metadataSyncService.syncRoute(routeSnapshot("241006973", "8455안성", validStop("tago-stop", "경기 정류장", 1)));

        metadataSyncService.syncCompleteProviderSnapshot(scopedSeoulSource().fetchCompleteSnapshot());

        assertThat(busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.SEOUL_BUS, "100100447"))
                .isPresent();
        assertThat(busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.SEOUL_BUS, "241006973"))
                .isEmpty();
        assertThat(busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.SEOUL_BUS, "300000001"))
                .isEmpty();
        assertThat(busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "241006973"))
                .isPresent();
        assertThat(syncStateRepository.findById(TransitProvider.SEOUL_BUS)).isPresent();
    }

    private SeoulCsvMetadataSource scopedSeoulSource() {
        return new SeoulCsvMetadataSource(
                resource("""
                        \uFEFF노선ID,노선명,노선유형,거리
                        100100447,7016,지선,50.0
                        241006973,8455안성,경기,
                        300000001,인천01,인천,10.0
                        100100586,N26,,50.0
                        """),
                resource("""
                        \uFEFF노선ID,노드ID,링크거리누계,정류장순번
                        100100447,111000907,0.0,1
                        100100586,115000321,0.0,1
                        """),
                resource("""
                        \uFEFF정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부
                        111000907,은평공영차고지,일반차로,35331,126.883807875,37.5918053134,미설치
                        115000321,개화역광역환승센터,일반차로,16435,126.797978,37.57822,설치
                        """)
        );
    }

    private ByteArrayResource resource(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private BusRouteMetadataSnapshot routeSnapshot(
            String externalRouteId,
            String routeNumber,
            BusStopOccurrenceMetadataSnapshot... occurrences
    ) {
        return new BusRouteMetadataSnapshot(
                TransitProvider.TAGO,
                externalRouteId,
                routeNumber,
                "41110",
                List.of(occurrences)
        );
    }

    private BusStopOccurrenceMetadataSnapshot validStop(String externalStopId, String stopName, int stopOrder) {
        return new BusStopOccurrenceMetadataSnapshot(
                externalStopId,
                stopName,
                new BigDecimal("37.0000000"),
                new BigDecimal("127.0000000"),
                stopOrder
        );
    }
}
