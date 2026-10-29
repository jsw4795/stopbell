package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardingCandidateDetailExecutorTest {
    @Test
    @DisplayName("요청당 두 작업만 시작하고 응답 없는 작업을 기다리지 않은 채 미시작과 미완료를 구분한다")
    void deadline_limits_running_and_pending_work() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        var calls = new AtomicInteger();
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofMillis(150), System::nanoTime)) {
            long before = System.nanoTime();
            var results = executor.collect(IntStream.range(0, 40).boxed().toList(), executor.deadline(), id -> {
                calls.incrementAndGet();
                started.countDown();
                await(release);
                return id;
            });
            assertThat(started.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(2));
            assertThat(calls).hasValue(2);
            assertThat(results.subList(0, 2)).allMatch(r -> r.started() && r.value() == null);
            assertThat(results.subList(2, 40)).allMatch(r -> !r.started() && r.value() == null);
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("다중 요청 전체 네 작업이 끝나기 전에는 만료된 호출의 실행 자리를 반환하지 않는다")
    void global_slots_remain_owned_until_actual_completion() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        var finished = new CountDownLatch(4);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        try (var callers = Executors.newFixedThreadPool(2);
                var executor = new BoardingCandidateDetailExecutor(Duration.ofMillis(250), System::nanoTime)) {
            java.util.function.Function<Integer, Integer> work = id -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                started.countDown();
                try {
                    await(release);
                    return id;
                } finally {
                    active.decrementAndGet();
                    finished.countDown();
                }
            };
            var first = callers.submit(() -> executor.collect(List.of(1, 2, 3), executor.deadline(), work));
            var second = callers.submit(() -> executor.collect(List.of(4, 5, 6), executor.deadline(), work));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.get(2, TimeUnit.SECONDS)).hasSize(3);
            assertThat(second.get(2, TimeUnit.SECONDS)).hasSize(3);
            assertThat(maximum).hasValue(4);
            assertThat(active).hasValue(4);
            var waiting = executor.collect(List.of(7, 8), executor.deadline(), work);
            assertThat(waiting).allMatch(r -> !r.started() && r.value() == null);
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            var recovered = executor.collect(List.of(9, 10), executor.deadline(), id -> id);
            assertThat(recovered).extracting(BoardingCandidateDetailExecutor.Result::value).containsExactly(9, 10);
            assertThat(active).hasValue(0);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("단조 시간의 deadline 직전 결과는 보존하고 경계 이후 결과와 후속 시작은 차단한다")
    void monotonic_deadline_filters_late_results() {
        var nanos = new AtomicLong();
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofSeconds(12), nanos::get)) {
            var deadline = executor.deadline();
            var result = executor.collect(List.of(1), deadline, id -> {
                nanos.set(Duration.ofSeconds(12).toNanos() - 1);
                return id;
            });
            assertThat(result.getFirst().value()).isEqualTo(1);
            result = executor.collect(List.of(2, 3, 4), executor.deadline(), id -> {
                nanos.addAndGet(Duration.ofSeconds(12).toNanos());
                return id;
            });
            assertThat(result).allMatch(r -> r.value() == null);
            assertThat(result).anyMatch(r -> r.started());
            assertThat(result.getLast().started()).isFalse();
            assertThat(executor.collect(List.of(5), deadline, id -> id).getFirst().started()).isFalse();
        }
    }

    @Test
    @DisplayName("종료 시 신규 작업을 차단하고 이미 실행 중인 작업은 실제 종료까지 정리한다")
    void shutdown_rejects_new_provider_work() {
        var executor = new BoardingCandidateDetailExecutor();
        executor.close();
        assertThat(executor.collect(List.of(1, 2), executor.deadline(), id -> {
            throw new AssertionError("No provider call after shutdown");
        })).allMatch(r -> !r.started());
    }

    @Test
    @DisplayName("후보 네 작업이 막혀 있어도 Monitoring 및 Dispatch Scheduler는 독립 실행되고 종료 후 스레드를 정리한다")
    void candidate_pool_does_not_occupy_monitoring_schedulers() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                com.stopbell.common.config.SchedulingConfiguration.class, BoardingCandidateDetailExecutor.class);
                var callers = Executors.newFixedThreadPool(2)) {
            var executor = context.getBean(BoardingCandidateDetailExecutor.class);
            java.util.function.Function<Integer, Integer> work = id -> {
                assertThat(Thread.currentThread().getName()).startsWith("boarding-candidate-detail-");
                started.countDown();
                await(release);
                return id;
            };
            var first = callers.submit(() -> executor.collect(List.of(1, 2), executor.deadline(), work));
            var second = callers.submit(() -> executor.collect(List.of(3, 4), executor.deadline(), work));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                for (String name : List.of("transitMonitoringScheduler", "notificationDispatchScheduler")) {
                    var scheduler = context.getBean(name, org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.submit(() -> Thread.currentThread().getName()).get(1, TimeUnit.SECONDS))
                            .doesNotStartWith("boarding-candidate-detail-");
                }
                executor.close();
                assertThat(first.get(1, TimeUnit.SECONDS)).allMatch(r -> r.started() && r.value() == null);
                assertThat(second.get(1, TimeUnit.SECONDS)).allMatch(r -> r.started() && r.value() == null);
                var pool = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils
                        .getField(executor, "executor");
                assertThat(pool.isShutdown()).isTrue();
                assertThat(pool.isTerminated()).isFalse();
                release.countDown();
                assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deadline 이전 내부 예외와 Error는 미완료 결과 대신 원래 객체로 호출자에게 전달한다")
    void propagate_unchecked_failures_before_deadline(boolean error) {
        Throwable failure = error ? new AssertionError("Detail failed") : new IllegalStateException("Detail failed");
        try (var executor = new BoardingCandidateDetailExecutor()) {
            assertThatThrownBy(() -> executor.collect(List.of(1), executor.deadline(), id -> {
                if (failure instanceof RuntimeException exception) throw exception;
                throw (Error) failure;
            })).isSameAs(failure);
            assertThat(executor.collect(List.of(2), executor.deadline(), id -> id).getFirst().value()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deadline 이후 내부 예외와 Error는 늦은 결과와 동일하게 응답에서 제외한다")
    void ignore_unchecked_failures_after_deadline(boolean error) {
        var nanos = new AtomicLong();
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofSeconds(12), nanos::get)) {
            var result = executor.collect(List.of(1), executor.deadline(), id -> {
                nanos.set(Duration.ofSeconds(12).toNanos());
                if (error) throw new AssertionError("Late detail failure");
                throw new IllegalStateException("Late detail failure");
            });
            assertThat(result.getFirst().started()).isTrue();
            assertThat(result.getFirst().value()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("작업 등록이 예외나 Error로 실패하면 admission을 복구하고 후속 요청에 네 실행 자리를 모두 제공한다")
    void restore_admission_after_submission_failure(boolean error) throws Exception {
        Throwable failure = error ? new AssertionError("Worker creation failed")
                : new java.util.concurrent.RejectedExecutionException("Worker creation rejected");
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        try (var executor = new BoardingCandidateDetailExecutor(); var callers = Executors.newFixedThreadPool(2)) {
            var pool = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils
                    .getField(executor, "executor");
            var factory = pool.getThreadFactory();
            // Exercise failure from execute() itself without replacing the pool or running Provider I/O.
            pool.setThreadFactory(runnable -> {
                if (failure instanceof RuntimeException exception) throw exception;
                throw (Error) failure;
            });
            assertThatThrownBy(() -> executor.collect(List.of(1), executor.deadline(), id -> {
                throw new AssertionError("Rejected work must not run");
            })).isSameAs(failure);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(executor, "admitted")).isEqualTo(0);
            pool.setThreadFactory(factory);
            java.util.function.Function<Integer, Integer> work = id -> {
                started.countDown();
                await(release);
                return id;
            };
            var first = callers.submit(() -> executor.collect(List.of(1, 2), executor.deadline(), work));
            var second = callers.submit(() -> executor.collect(List.of(3, 4), executor.deadline(), work));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(first.get(2, TimeUnit.SECONDS)).extracting(BoardingCandidateDetailExecutor.Result::value)
                        .containsExactly(1, 2);
                assertThat(second.get(2, TimeUnit.SECONDS)).extracting(BoardingCandidateDetailExecutor.Result::value)
                        .containsExactly(3, 4);
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(executor, "admitted")).isEqualTo(0);
            } finally {
                release.countDown();
            }
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
