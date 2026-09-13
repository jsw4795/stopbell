package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.service.BusMetadataSyncService;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

        assertThatThrownBy(() -> metadataSyncService.syncProviderSnapshot(
                TransitProvider.TAGO,
                List.of(completedRoute, failingRoute)
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
