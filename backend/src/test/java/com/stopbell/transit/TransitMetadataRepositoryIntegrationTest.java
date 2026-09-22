package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.transit.service.BusMetadataSyncService;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class TransitMetadataRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private BusRouteRepository busRouteRepository;

    @Autowired
    private BusStopRepository busStopRepository;

    @Autowired
    private BusRouteStopOccurrenceRepository occurrenceRepository;

    @Autowired
    private BusMetadataSyncService metadataSyncService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Bus metadata identity는 provider namespace마다 고유하고 필수 제약을 강제한다")
    void persist_metadata_with_provider_scoped_identity_and_constraints() {
        BusRoute tagoRoute = busRouteRepository.saveAndFlush(
                new BusRoute(TransitProvider.TAGO, "route-1", "7000", "41110")
        );
        BusRoute seoulRoute = busRouteRepository.saveAndFlush(
                new BusRoute(TransitProvider.SEOUL_BUS, "route-1", "7016", null)
        );
        BusStop tagoStop = busStopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO,
                "stop-1",
                "사색의광장",
                new BigDecimal("37.1234567"),
                new BigDecimal("127.1234567")
        ));
        BusStop seoulStop = busStopRepository.saveAndFlush(new BusStop(
                TransitProvider.SEOUL_BUS, "stop-1", "서울 정류장", null, null
        ));
        occurrenceRepository.saveAndFlush(new BusRouteStopOccurrence(tagoRoute, tagoStop, 1));

        assertThat(tagoRoute.getId()).isNotEqualTo(seoulRoute.getId());
        assertThat(tagoStop.getId()).isNotEqualTo(seoulStop.getId());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into bus_routes (provider, external_route_id, route_number, city_code) values (?, ?, ?, ?)",
                "TAGO", "route-without-city", "7002", null
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into bus_stops (provider, external_stop_id, stop_name, latitude, longitude) values (?, ?, ?, ?, ?)",
                "TAGO", "stop-with-one-coordinate", "좌표 오류", null, new BigDecimal("127.0000000")
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into bus_route_stop_occurrences (route_id, stop_id, stop_order) values (?, ?, ?)",
                tagoRoute.getId(), tagoStop.getId(), 0
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> busRouteRepository.saveAndFlush(
                new BusRoute(TransitProvider.TAGO, "route-1", "7001", "41110")
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> busStopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO, "stop-1", "다른 정류장", null, null
        ))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> occurrenceRepository.saveAndFlush(
                new BusRouteStopOccurrence(tagoRoute, tagoStop, 1)
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new BusRoute(TransitProvider.TAGO, "route-2", "7002", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusRoute(TransitProvider.SEOUL_BUS, "route-2", "7016", "11110"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusStop(TransitProvider.TAGO, "stop-2", "정류장", null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusStop(
                TransitProvider.TAGO, "stop-3", "정류장", new BigDecimal("90.0000001"), BigDecimal.ONE
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusRouteStopOccurrence(tagoRoute, tagoStop, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Route snapshot은 서로 다른 Stop이라도 중복 stopOrder를 생성 시점에 거부한다")
    void reject_duplicate_stop_order_when_creating_route_snapshot() {
        assertThatThrownBy(() -> new BusRouteMetadataSnapshot(
                TransitProvider.TAGO,
                "route-duplicate-order",
                "7000",
                "41110",
                List.of(stop("stop-a", "A", 3), stop("stop-b", "B", 3))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Route snapshot contains duplicate stop order");
    }

    @Test
    @DisplayName("동일 Route snapshot 재동기화는 모든 내부 ID를 유지하고 row를 늘리지 않는다")
    void resync_same_snapshot_preserves_ids_without_additional_rows() {
        BusRouteMetadataSnapshot snapshot = routeSnapshot("7000", "41110", stop("stop-1", "첫 정류장", 1));

        metadataSyncService.syncRoute(snapshot);
        BusRoute firstRoute = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        BusStop firstStop = busStopRepository.findByProviderAndExternalStopId(TransitProvider.TAGO, "stop-1").orElseThrow();
        BusRouteStopOccurrence firstOccurrence = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(firstRoute).getFirst();

        metadataSyncService.syncRoute(snapshot);
        entityManager.flush();
        entityManager.clear();

        BusRoute secondRoute = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        BusStop secondStop = busStopRepository.findByProviderAndExternalStopId(TransitProvider.TAGO, "stop-1").orElseThrow();
        BusRouteStopOccurrence secondOccurrence = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(secondRoute).getFirst();

        assertThat(secondRoute.getId()).isEqualTo(firstRoute.getId());
        assertThat(secondStop.getId()).isEqualTo(firstStop.getId());
        assertThat(secondOccurrence.getId()).isEqualTo(firstOccurrence.getId());
        assertThat(busRouteRepository.count()).isEqualTo(1);
        assertThat(busStopRepository.count()).isEqualTo(1);
        assertThat(occurrenceRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Route와 Stop metadata 변경은 identity row의 내부 ID를 유지한다")
    void update_route_and_stop_metadata_preserves_ids() {
        metadataSyncService.syncRoute(routeSnapshot("7000", "41110", stop("stop-1", "기존 정류장", 1)));
        Long routeId = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow().getId();
        Long stopId = busStopRepository.findByProviderAndExternalStopId(TransitProvider.TAGO, "stop-1").orElseThrow().getId();

        metadataSyncService.syncRoute(routeSnapshot(
                "7000-변경", "41111", stop("stop-1", "변경 정류장", 1, "37.1111111", "127.1111111")
        ));
        entityManager.flush();
        entityManager.clear();

        BusRoute route = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        BusStop busStop = busStopRepository.findByProviderAndExternalStopId(TransitProvider.TAGO, "stop-1").orElseThrow();
        assertThat(route.getId()).isEqualTo(routeId);
        assertThat(route.getRouteNumber()).isEqualTo("7000-변경");
        assertThat(route.getCityCode()).isEqualTo("41111");
        assertThat(busStop.getId()).isEqualTo(stopId);
        assertThat(busStop.getStopName()).isEqualTo("변경 정류장");
        assertThat(busStop.getLatitude()).isEqualByComparingTo("37.1111111");
    }

    @Test
    @DisplayName("Occurrence의 Stop 또는 order가 바뀌면 기존 ID를 재사용하지 않고 삭제 후 생성한다")
    void replace_occurrence_when_its_semantics_change() {
        metadataSyncService.syncRoute(routeSnapshot("7000", "41110", stop("stop-a", "A", 3)));
        BusRoute route = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        Long originalOccurrenceId = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route).getFirst().getId();

        metadataSyncService.syncRoute(routeSnapshot("7000", "41110", stop("stop-b", "B", 3)));
        entityManager.flush();
        entityManager.clear();

        BusRoute changedRoute = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        BusRouteStopOccurrence changedOccurrence = occurrenceRepository
                .findAllByRouteOrderByStopOrderAsc(changedRoute)
                .getFirst();
        assertThat(changedOccurrence.getId()).isNotEqualTo(originalOccurrenceId);
        assertThat(changedOccurrence.getStop().getExternalStopId()).isEqualTo("stop-b");

        Long changedOccurrenceId = changedOccurrence.getId();
        metadataSyncService.syncRoute(routeSnapshot("7000", "41110", stop("stop-b", "B", 4)));
        entityManager.flush();
        entityManager.clear();
        BusRoute movedRoute = busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1").orElseThrow();
        BusRouteStopOccurrence movedOccurrence = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(movedRoute).getFirst();
        assertThat(movedOccurrence.getId()).isNotEqualTo(changedOccurrenceId);
        assertThat(movedOccurrence.getStopOrder()).isEqualTo(4);
    }

    @Test
    @DisplayName("완전한 Provider snapshot만 Route를 삭제하고 공유 Stop은 마지막 occurrence가 사라질 때만 정리한다")
    void provider_snapshot_cleanup_removes_routes_and_only_orphaned_stops() {
        BusRouteMetadataSnapshot firstRoute = routeSnapshot("7000", "41110", stop("shared-stop", "공유 정류장", 1));
        BusRouteMetadataSnapshot secondRoute = new BusRouteMetadataSnapshot(
                TransitProvider.TAGO,
                "route-2",
                "7001",
                "41110",
                List.of(stop("shared-stop", "공유 정류장", 1), stop("exclusive-stop", "단독 정류장", 2))
        );
        metadataSyncService.syncCompleteProviderSnapshot(new CompleteBusMetadataSnapshot(
                TransitProvider.TAGO, List.of(firstRoute, secondRoute)
        ));

        metadataSyncService.syncCompleteProviderSnapshot(new CompleteBusMetadataSnapshot(
                TransitProvider.TAGO, List.of(secondRoute)
        ));
        entityManager.flush();
        entityManager.clear();
        assertThat(busRouteRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, "route-1")).isEmpty();
        assertThat(busStopRepository.findByProviderAndExternalStopId(TransitProvider.TAGO, "shared-stop")).isPresent();

        assertThatThrownBy(() -> new CompleteBusMetadataSnapshot(TransitProvider.TAGO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BusRouteMetadataSnapshot routeSnapshot(
            String routeNumber,
            String cityCode,
            BusStopOccurrenceMetadataSnapshot... occurrences
    ) {
        return new BusRouteMetadataSnapshot(
                TransitProvider.TAGO,
                "route-1",
                routeNumber,
                cityCode,
                List.of(occurrences)
        );
    }

    private BusStopOccurrenceMetadataSnapshot stop(String externalStopId, String stopName, int stopOrder) {
        return stop(externalStopId, stopName, stopOrder, "37.0000000", "127.0000000");
    }

    private BusStopOccurrenceMetadataSnapshot stop(
            String externalStopId,
            String stopName,
            int stopOrder,
            String latitude,
            String longitude
    ) {
        return new BusStopOccurrenceMetadataSnapshot(
                externalStopId,
                stopName,
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                stopOrder
        );
    }
}
