package com.stopbell.transit.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Candidate lookup only: never used by monitoring or selected-vehicle revalidation. */
@Component
public class BoardingCandidateDetailExecutor implements AutoCloseable {
    static final Duration BUDGET = Duration.ofSeconds(12);
    private static final int PER_REQUEST = 2;
    private static final int GLOBAL = 4;
    private final Object monitor = new Object();
    private final ThreadPoolExecutor executor;
    private final Duration budget;
    private final LongSupplier nanoTime;
    // Includes admitted work which has not entered the worker yet. Release only on actual exit.
    private int admitted;
    private boolean closed;

    @Autowired
    public BoardingCandidateDetailExecutor() {
        this(BUDGET, System::nanoTime);
    }

    BoardingCandidateDetailExecutor(Duration budget, LongSupplier nanoTime) {
        this.budget = budget;
        this.nanoTime = nanoTime;
        this.executor = new ThreadPoolExecutor(GLOBAL, GLOBAL, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(GLOBAL), Thread.ofPlatform().daemon()
                        .name("boarding-candidate-detail-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    Deadline deadline() {
        return new Deadline(nanoTime.getAsLong(), budget.toNanos(), nanoTime);
    }

    record Deadline(long startedAt, long budgetNanos, LongSupplier nanoTime) {
        long remainingNanos() {
            return Math.max(0, budgetNanos - (nanoTime.getAsLong() - startedAt));
        }
    }

    record Result<T>(T value, boolean started) {
    }

    /** At most two outstanding tasks per caller; no task is allocated for unadmitted items. */
    <I, T> List<Result<T>> collect(List<I> items, Deadline deadline, Function<I, T> work) {
        var request = new Request();
        List<Task<T>> tasks = new ArrayList<>();
        synchronized (monitor) {
            try {
                while (true) {
                    int outstanding = 0;
                    for (Task<T> task : tasks) {
                        if (task.failure instanceof RuntimeException exception) throw exception;
                        if (task.failure instanceof Error error) throw error;
                        if (!task.finished) outstanding++;
                    }
                    if (tasks.size() == items.size() && outstanding == 0) break;
                    if (closed || deadline.remainingNanos() == 0) break;
                    while (tasks.size() < items.size() && outstanding < PER_REQUEST && admitted < GLOBAL
                            && deadline.remainingNanos() > 0) {
                        I item = items.get(tasks.size());
                        var task = new Task<T>();
                        tasks.add(task);
                        admitted++;
                        // A bounded queue absorbs the brief gap between releasing admission and
                        // a pool worker returning to take its next task. Admission still caps all work at four.
                        try {
                            executor.execute(() -> run(request, task, deadline, () -> work.apply(item)));
                        } catch (RuntimeException | Error failure) {
                            admitted--;
                            tasks.removeLast();
                            monitor.notifyAll();
                            throw failure;
                        }
                        outstanding++;
                    }
                    long remaining = deadline.remainingNanos();
                    if (remaining == 0) break;
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Candidate detail collection interrupted", exception);
            } finally {
                // Workers cannot publish after the response snapshot is closed, even if HTTP ignores interruption.
                request.open = false;
            }
            List<Result<T>> results = new ArrayList<>(items.size());
            for (Task<T> task : tasks) results.add(new Result<>(task.value, task.started));
            while (results.size() < items.size()) results.add(new Result<>(null, false));
            return List.copyOf(results);
        }
    }

    private <T> void run(Request request, Task<T> task, Deadline deadline, java.util.function.Supplier<T> work) {
        try {
            synchronized (monitor) {
                if (closed || !request.open || deadline.remainingNanos() == 0) return;
                task.started = true;
            }
            T value = work.get();
            synchronized (monitor) {
                if (request.open && deadline.remainingNanos() > 0) task.value = value;
            }
        } catch (RuntimeException | Error exception) {
            synchronized (monitor) {
                if (request.open && deadline.remainingNanos() > 0) task.failure = exception;
            }
        } finally {
            synchronized (monitor) {
                task.finished = true;
                admitted--;
                monitor.notifyAll();
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (monitor) {
            closed = true;
            // No blind cancellation/permit release. Queued wrappers skip I/O; running calls
            // retain their slots until exit and use the existing HTTP timeout for cleanup.
            executor.shutdown();
            monitor.notifyAll();
        }
    }

    private static final class Request {
        private boolean open = true;
    }

    private static final class Task<T> {
        private boolean started;
        private boolean finished;
        private T value;
        private Throwable failure;
    }
}
