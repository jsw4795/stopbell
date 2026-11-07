package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransitMetadataCollectionHistoryIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "collection_history");
    }
    @Autowired TransitMetadataCollectionHistoryService service;

    @Test @DisplayName("실행을 독립 저장하고 조회와 완료 재요청에서 ID와 시각을 보존한다")
    void persists_runs_and_transitions() {
        var first = service.start(TransitProvider.SEOUL_BUS);
        var second = service.start(TransitProvider.TAGO);
        assertThat(first.getId()).isNotEqualTo(second.getId());
        service.collected(first.getId());
        service.syncing(first.getId());
        var completed = service.complete(first.getId());
        var retried = service.complete(first.getId());
        assertThat(retried.getId()).isEqualTo(first.getId());
        assertThat(retried.getFinishedAt()).isEqualTo(completed.getFinishedAt());
        assertThat(service.find(first.getId()).getStatus()).isEqualTo(TransitCollectionStatus.COMPLETED);
        assertThat(service.findByProvider(TransitProvider.SEOUL_BUS)).extracting("id").contains(first.getId());
        assertThat(service.findByStatus(TransitCollectionStatus.COLLECTING)).extracting("id").contains(second.getId());
        var failed = service.fail(second.getId(), TransitCollectionFailureStage.COLLECTION, TransitCollectionFailureCode.SOURCE_UNAVAILABLE);
        assertThat(service.find(failed.getId()).getFailureMessage()).isEqualTo("Source unavailable");
        var interrupted = service.start(TransitProvider.TAGO);
        service.interrupt(interrupted.getId());
        assertThat(service.find(interrupted.getId()).getStatus()).isEqualTo(TransitCollectionStatus.INTERRUPTED);
    }

    @Test @DisplayName("같은 실행의 경쟁하는 종료 요청은 하나만 성공한다")
    void serializes_competing_transitions() throws Exception {
        Long id = service.start(TransitProvider.TAGO).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> fail = () -> { ready.countDown(); start.await(); try {
                service.fail(id, TransitCollectionFailureStage.COLLECTION, TransitCollectionFailureCode.SOURCE_UNAVAILABLE); return true;
            } catch (IllegalStateException e) { return false; } };
            Callable<Boolean> interrupt = () -> { ready.countDown(); start.await(); try {
                service.interrupt(id); return true;
            } catch (IllegalStateException e) { return false; } };
            var a = pool.submit(fail);
            var b = pool.submit(interrupt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat((a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(service.find(id).getStatus()).isIn(TransitCollectionStatus.FAILED, TransitCollectionStatus.INTERRUPTED);
        }
    }
}
