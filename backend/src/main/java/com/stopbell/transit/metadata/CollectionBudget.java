package com.stopbell.transit.metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Shared request and streaming limits for a single sequential collection. */
final class CollectionBudget {
    enum Code { REQUEST_LIMIT, RESPONSE_SIZE_LIMIT, TOTAL_BYTES_LIMIT, TIME_LIMIT, HTTP_ERROR,
        PROVIDER_ERROR, INVALID_RESPONSE, INTEGRITY_FAILURE, STORAGE_FAILURE, INTERRUPTED }
    static final class Failure extends RuntimeException {
        final Code code;
        Failure(Code code) { super(code.name()); this.code = code; }
    }
    record Limits(int requests, long responseBytes, long totalBytes, Duration duration,
                  Duration interval, Duration connectTimeout, Duration responseTimeout) {
        Limits {
            if (requests <= 0 || responseBytes <= 0 || responseBytes > Integer.MAX_VALUE - 8L || totalBytes <= 0
                    || !positive(duration) || !positive(interval) || !positive(connectTimeout) || !positive(responseTimeout))
                throw new IllegalArgumentException("All collection limits must be finite positive values");
            try {
                duration.toNanos(); interval.toNanos(); connectTimeout.toNanos(); responseTimeout.toNanos();
            } catch (ArithmeticException e) { throw new IllegalArgumentException("Collection limits are too large"); }
        }
        private static boolean positive(Duration d) { return d != null && !d.isNegative() && !d.isZero(); }
    }
    private final Limits limits;
    private final LongSupplier time;
    private final long start;
    private long previous;
    private int requests;
    private long bytes;
    CollectionBudget(Limits limits) { this(limits, System::nanoTime); }
    CollectionBudget(Limits limits, LongSupplier time) {
        this.limits = limits; this.time = time; this.start = time.getAsLong();
    }
    void check() {
        if (Thread.currentThread().isInterrupted()) throw new Failure(Code.INTERRUPTED);
        if (time.getAsLong() - start >= limits.duration().toNanos()) throw new Failure(Code.TIME_LIMIT);
    }
    void reserve() {
        check();
        if (requests >= limits.requests()) throw new Failure(Code.REQUEST_LIMIT);
        if (requests > 0) {
            long wait = limits.interval().toNanos() - (time.getAsLong() - previous);
            if (wait > 0) {
                long remaining = limits.duration().toNanos() - (time.getAsLong() - start);
                if (wait >= remaining) throw new Failure(Code.TIME_LIMIT);
                try { TimeUnit.NANOSECONDS.sleep(wait); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(Code.INTERRUPTED); }
                check();
            }
        }
        requests++;
        previous = time.getAsLong();
    }
    byte[] read(InputStream input) throws IOException {
        var result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long response = 0;
        while (true) {
            check();
            long individualRemaining = limits.responseBytes() - response;
            long totalRemaining = limits.totalBytes() - bytes;
            int allowed = (int) Math.min(buffer.length, Math.min(individualRemaining, totalRemaining) + 1);
            int count = input.read(buffer, 0, allowed);
            check();
            if (count < 0) break;
            if (count == 0) continue;
            response += count;
            bytes += count;
            if (response > limits.responseBytes()) throw new Failure(Code.RESPONSE_SIZE_LIMIT);
            if (bytes > limits.totalBytes()) throw new Failure(Code.TOTAL_BYTES_LIMIT);
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    synchronized void accept(java.nio.ByteBuffer source, ByteArrayOutputStream output) {
        check();
        int count = source.remaining();
        long responseRemaining = limits.responseBytes() - output.size();
        long totalRemaining = limits.totalBytes() - bytes;
        long accepted = Math.min(count, Math.min(responseRemaining, totalRemaining));
        bytes = Math.addExact(bytes, count);
        if (count > accepted) {
            throw new Failure(responseRemaining <= totalRemaining ? Code.RESPONSE_SIZE_LIMIT : Code.TOTAL_BYTES_LIMIT);
        }
        byte[] chunk = new byte[count];
        source.get(chunk);
        output.writeBytes(chunk);
    }
    long remainingNanos() {
        check();
        return limits.duration().toNanos() - (time.getAsLong() - start);
    }
    Limits limits() { return limits; }
    void finished() { previous = time.getAsLong(); }
    int requests() { return requests; }
    long bytes() { return bytes; }
    long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(time.getAsLong() - start); }
}
