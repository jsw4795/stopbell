package com.stopbell.transit.metadata;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** A single non-redirecting HTTP attempt with limits enforced while reading. */
final class CollectionHttp {
    interface Transport { byte[] get(URI uri, CollectionBudget budget); }
    static final Transport NETWORK = (uri, budget) -> {
        try (var connection = new Connection(budget.limits())) { return connection.get(uri, budget); }
    };
    private CollectionHttp() { }
    static final class Connection implements Transport, AutoCloseable {
        private final HttpClient client;
        Connection(CollectionBudget.Limits limits) {
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(limits.connectTimeout()).version(HttpClient.Version.HTTP_1_1).build();
        }
        @Override public byte[] get(URI uri, CollectionBudget budget) { return CollectionHttp.get(client, uri, budget); }
        @Override public void close() { client.close(); }
        boolean isClosed() { return client.isTerminated(); }
    }
    private static byte[] get(HttpClient client, URI uri, CollectionBudget budget) {
        // The one-shot JVM must set these before the JDK HTTP client is initialized.
        if (!startupProperty("jdk.httpclient.redirects.retrylimit", "1")
                || !startupProperty("jdk.httpclient.disableRetryConnect", "true"))
            throw new CollectionBudget.Failure(CollectionBudget.Code.HTTP_ERROR);
        budget.check();
        boolean reserved = false;
        try {
            var request = HttpRequest.newBuilder(uri).GET().timeout(budget.limits().responseTimeout()).build();
            budget.reserve();
            reserved = true;
            var future = client.sendAsync(request, response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    return new RejectSubscriber();
                return new LimitedSubscriber(budget);
            });
            boolean totalDeadlineFirst = false;
            try {
                long remaining = budget.remainingNanos();
                totalDeadlineFirst = remaining <= budget.limits().responseTimeout().toNanos();
                long timeout = Math.min(remaining, budget.limits().responseTimeout().toNanos());
                var response = future.get(timeout, TimeUnit.NANOSECONDS);
                budget.check();
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new CollectionBudget.Failure(CollectionBudget.Code.HTTP_ERROR, response.statusCode() >= 500 && response.statusCode() <= 599);
                return response.body();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new CollectionBudget.Failure(CollectionBudget.Code.INTERRUPTED);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                budget.check();
                throw new CollectionBudget.Failure(totalDeadlineFirst ? CollectionBudget.Code.TIME_LIMIT : CollectionBudget.Code.HTTP_ERROR);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                boolean connectionFailure = false;
                while (cause != null) {
                    if (cause instanceof CollectionBudget.Failure failure) throw failure;
                    if (cause instanceof java.net.ConnectException || cause instanceof java.net.http.HttpConnectTimeoutException) connectionFailure = true;
                    cause = cause.getCause();
                }
                budget.check();
                throw new CollectionBudget.Failure(CollectionBudget.Code.HTTP_ERROR, connectionFailure);
            } catch (CollectionBudget.Failure e) {
                future.cancel(true);
                throw e;
            }
        } finally { if (reserved) budget.finished(); }
    }
    private static boolean startupProperty(String key, String expected) {
        String prefix = "-D" + key + "=";
        String startupValue = null;
        for (String argument : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith(prefix)) startupValue = argument.substring(prefix.length());
        }
        return expected.equals(startupValue) && expected.equals(System.getProperty(key));
    }
    private static final class RejectSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { value.cancel(); result.complete(new byte[0]); }
        @Override public void onNext(List<ByteBuffer> ignored) { }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(new byte[0]); }
    }
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CollectionBudget budget;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedSubscriber(CollectionBudget budget) { this.budget = budget; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) budget.accept(buffer, output);
                subscription.request(1);
            } catch (RuntimeException e) {
                subscription.cancel(); result.completeExceptionally(e);
            }
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
