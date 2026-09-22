package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusMetadataSyncServiceTest {

    @Test
    @DisplayName("complete sync 성공 시 fixed UTC Clock의 시각을 lastCompleteSyncAt으로 저장한다")
    void store_fixed_clock_time_after_complete_sync() {
        BusRouteMetadataSyncService routeSyncService = mock(BusRouteMetadataSyncService.class);
        BusProviderMetadataCleanupService cleanupService = mock(BusProviderMetadataCleanupService.class);
        BusMetadataSyncStateRepository stateRepository = mock(BusMetadataSyncStateRepository.class);
        Instant completedAt = Instant.parse("2026-09-22T08:00:00Z");
        when(stateRepository.findById(TransitProvider.SEOUL_BUS)).thenReturn(Optional.empty());
        BusMetadataSyncService service = new BusMetadataSyncService(
                routeSyncService,
                cleanupService,
                stateRepository,
                Clock.fixed(completedAt, ZoneOffset.UTC)
        );

        service.syncCompleteProviderSnapshot(snapshot(TransitProvider.SEOUL_BUS));

        ArgumentCaptor<BusMetadataSyncState> savedState = ArgumentCaptor.forClass(BusMetadataSyncState.class);
        verify(stateRepository).save(savedState.capture());
        assertThat(savedState.getValue().getProvider()).isEqualTo(TransitProvider.SEOUL_BUS);
        assertThat(savedState.getValue().getLastCompleteSyncAt()).isEqualTo(completedAt);
        verify(cleanupService).cleanupProvider(TransitProvider.SEOUL_BUS, java.util.Set.of("seoul-route"));
    }

    @Test
    @DisplayName("Route reconciliation 실패 시 lastCompleteSyncAt을 읽거나 변경하지 않는다")
    void do_not_update_last_complete_sync_at_when_route_sync_fails() {
        BusRouteMetadataSyncService routeSyncService = mock(BusRouteMetadataSyncService.class);
        BusProviderMetadataCleanupService cleanupService = mock(BusProviderMetadataCleanupService.class);
        BusMetadataSyncStateRepository stateRepository = mock(BusMetadataSyncStateRepository.class);
        BusMetadataSyncState existingState = new BusMetadataSyncState(
                TransitProvider.SEOUL_BUS, Instant.parse("2026-09-01T00:00:00Z")
        );
        when(stateRepository.findById(TransitProvider.SEOUL_BUS)).thenReturn(Optional.of(existingState));
        when(routeSyncService.syncRoute(any())).thenThrow(new IllegalStateException("route failure"));
        BusMetadataSyncService service = new BusMetadataSyncService(
                routeSyncService,
                cleanupService,
                stateRepository,
                Clock.fixed(Instant.parse("2026-09-22T08:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.syncCompleteProviderSnapshot(snapshot(TransitProvider.SEOUL_BUS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("route failure");

        assertThat(existingState.getLastCompleteSyncAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        verify(cleanupService, never()).cleanupProvider(any(), any());
        verify(stateRepository, never()).findById(any());
        verify(stateRepository, never()).save(any());
    }

    private CompleteBusMetadataSnapshot snapshot(TransitProvider provider) {
        return new CompleteBusMetadataSnapshot(provider, List.of(new BusRouteMetadataSnapshot(
                provider,
                provider == TransitProvider.SEOUL_BUS ? "seoul-route" : "tago-route",
                "1",
                provider == TransitProvider.TAGO ? "31010" : null,
                List.of()
        )));
    }
}
