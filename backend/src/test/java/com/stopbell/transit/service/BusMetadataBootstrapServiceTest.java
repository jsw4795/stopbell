package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class BusMetadataBootstrapServiceTest {

    @Test
    @DisplayName("source parser 검증 실패 시 provider cleanup 경로를 호출하지 않는다")
    void skip_sync_when_source_validation_fails() {
        SeoulCsvMetadataSource parser = new SeoulCsvMetadataSource(
                resource("노선ID,다른노선명,노선유형,거리\n"),
                resource("노선ID,노드ID,링크거리누계,정류장순번\n"),
                resource("정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n")
        );
        BusMetadataSource source = new BusMetadataSource() {
            @Override
            public TransitProvider provider() {
                return TransitProvider.SEOUL_BUS;
            }

            @Override
            public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
                parser.fetchCompleteSourceSnapshot();
                throw new AssertionError("Invalid source parser result must not reach provider conversion");
            }
        };
        BusMetadataSyncService syncService = mock(BusMetadataSyncService.class);
        BusMetadataBootstrapService bootstrapService = new BusMetadataBootstrapService(List.of(source), syncService);

        assertThatThrownBy(() -> bootstrapService.bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("headers do not match");

        verifyNoInteractions(syncService);
    }

    @Test
    @DisplayName("같은 Provider bootstrap이 실행 중이면 두 번째 요청을 명시적으로 거부한다")
    void reject_concurrent_bootstrap_for_same_provider() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchCompletion = new CountDownLatch(1);
        BusMetadataSource source = source(TransitProvider.TAGO, () -> {
            fetchStarted.countDown();
            await(allowFetchCompletion);
            return completeSnapshot(TransitProvider.TAGO);
        });
        BusMetadataSyncService syncService = mock(BusMetadataSyncService.class);
        BusMetadataBootstrapService bootstrapService = new BusMetadataBootstrapService(List.of(source), syncService);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> bootstrapService.bootstrap(TransitProvider.TAGO));
            org.assertj.core.api.Assertions.assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> bootstrapService.bootstrap(TransitProvider.TAGO))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");

            allowFetchCompletion.countDown();
            first.get(5, TimeUnit.SECONDS);
            verify(syncService).syncCompleteProviderSnapshot(completeSnapshot(TransitProvider.TAGO));
        } finally {
            allowFetchCompletion.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("metadata source 실패 뒤에는 같은 Provider bootstrap이 다시 실행될 수 있다")
    void release_single_flight_after_failure() {
        AtomicInteger attempts = new AtomicInteger();
        BusMetadataSource source = source(TransitProvider.TAGO, () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("source failure");
            }
            return completeSnapshot(TransitProvider.TAGO);
        });
        BusMetadataSyncService syncService = mock(BusMetadataSyncService.class);
        BusMetadataBootstrapService bootstrapService = new BusMetadataBootstrapService(List.of(source), syncService);

        assertThatThrownBy(() -> bootstrapService.bootstrap(TransitProvider.TAGO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source failure");

        bootstrapService.bootstrap(TransitProvider.TAGO);

        assertThat(attempts.get()).isEqualTo(2);
        verify(syncService, times(1)).syncCompleteProviderSnapshot(completeSnapshot(TransitProvider.TAGO));
    }

    private BusMetadataSource source(TransitProvider provider, java.util.function.Supplier<CompleteBusMetadataSnapshot> fetch) {
        return new BusMetadataSource() {
            @Override
            public TransitProvider provider() {
                return provider;
            }

            @Override
            public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
                return fetch.get();
            }
        };
    }

    private CompleteBusMetadataSnapshot completeSnapshot(TransitProvider provider) {
        return new CompleteBusMetadataSnapshot(provider, List.of(new BusRouteMetadataSnapshot(
                provider,
                provider == TransitProvider.TAGO ? "route-tago" : "route-seoul",
                "1",
                provider == TransitProvider.TAGO ? "31010" : null,
                List.of()
        )));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test source fetch interrupted", exception);
        }
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
