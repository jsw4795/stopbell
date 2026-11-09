package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransitMetadataCollectionTest {
    private static final String KEY = "fake-secret-service-key";
    private static final String TAGO = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService";
    private static final String GBIS = "https://apis.data.go.kr/6410000/baseinfoservice/v2/getBaseInfoItemv2";
    private static final String BASE = "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"baseInfoItem\":{"
            + "\"areaDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=area\",\"areaVersion\":\"v1\","
            + "\"routeLineDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=line\",\"routeLineVersion\":\"v1\","
            + "\"stationDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=stops\",\"stationVersion\":\"v1\","
            + "\"vehicleDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=vehicles\",\"vehicleVersion\":\"v1\","
            + "\"routeDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=route\",\"routeVersion\":\"v1\","
            + "\"routeStationDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=station\",\"routeStationVersion\":\"v1\"}}}}";
    private static final String ROUTE = "routeId|routeName|turnSeq^200|300|2";
    private static final String STATIONS = "routeId|routeName|upDown|staOrder|stationId|stationName|x|y^200|300|상행|1|10|turn|127|37^200|300|하행|2|20|end|127|37";
    @TempDir Path temp;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static CollectionBudget.Limits limits(int requests, long response, long total, long millis) {
        return new CollectionBudget.Limits(requests, response, total, Duration.ofMillis(millis),
                Duration.ofMillis(1), Duration.ofMillis(1000), Duration.ofMillis(2000));
    }
    private static String page(String item, int total, int page) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + item
                + "},\"totalCount\":" + total + ",\"pageNo\":" + page + "}}}";
    }
    private static final class Mock implements CollectionHttp.Transport {
        final AtomicInteger sent = new AtomicInteger();
        int failAt = -1;
        boolean duplicate;
        Runnable beforeCity = () -> { };
        String base = BASE;
        @Override public byte[] get(URI uri, CollectionBudget budget) {
            budget.reserve();
            int n = sent.incrementAndGet();
            if (n == failAt) throw new CollectionBudget.Failure(CollectionBudget.Code.HTTP_ERROR);
            String path = uri.getPath(), query = uri.getRawQuery(), body;
            if (path.endsWith("getBaseInfoItemv2")) body = base;
            else if (path.equals("/download")) body = query.equals("file=route") ? ROUTE : STATIONS;
            else if (path.endsWith("getCtyCodeList")) {
                beforeCity.run();
                String cities = TagoMetadataSource.fullCityScope().stream().map(c -> "{\"citycode\":\"" + c + "\"}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
                body = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + cities + "}}}}";
            } else if (path.endsWith("getRouteNoList")) {
                String first = TagoMetadataSource.fullCityScope().getFirst();
                boolean city = query.contains("cityCode=" + first);
                boolean second = query.contains("pageNo=2");
                body = page(city ? (second ? (duplicate ? "{\"routeid\":\"GGB200\",\"routeno\":\"300\"}" : "{\"routeid\":\"GGB201\",\"routeno\":\"301\"}") : "{\"routeid\":\"GGB200\",\"routeno\":\"300\"}") : "[]", city ? 2 : 0, second ? 2 : 1);
            } else {
                boolean second = query.contains("pageNo=2");
                body = page("{\"nodeid\":\"GGB" + (second ? "20" : "10") + "\",\"nodenm\":\"" + (second ? "end" : "turn")
                        + "\",\"nodeord\":" + (second ? "2" : "1") + "}", 2, second ? 2 : 1);
            }
            try { return budget.read(new ByteArrayInputStream(bytes(body))); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
    }
    private TransitMetadataCollector collector(CollectionBudget budget, CollectionHttp.Transport mock) throws Exception {
        return new TransitMetadataCollector(temp.toRealPath(), KEY, TAGO, GBIS, budget, mock);
    }
    @Test void collects_full_scope_pages_and_replays_identical_files() throws Exception {
        var budget = new CollectionBudget(limits(100, 10000, 100000, 10000)); var mock = new Mock();
        var result = collector(budget, mock).collect();
        assertThat(result.requests()).isEqualTo(40);
        assertThat(mock.sent).hasValue(40);
        assertThat(result.gbisRoutes()).isEqualTo(1); assertThat(result.tagoRoutes()).isEqualTo(2);
        assertThat(result.occurrences()).isEqualTo(4); assertThat(result.enriched()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1); assertThat(result.version()).isEqualTo("v1");
        assertThat(Files.readAllBytes(result.gbisPath().resolve("base-info.json"))).isEqualTo(bytes(BASE));
        assertThat(Files.readAllBytes(result.gbisPath().resolve("route.dat"))).isEqualTo(bytes(ROUTE));
        assertThat(TagoGbisOfflineSnapshotAssembler.withGbis(result.tagoPath(), result.gbisPath(), KEY).normalized().routes()).hasSize(2);
    }
    @Test void request_limit_stops_before_send_and_failed_requests_count() throws Exception {
        var budget = new CollectionBudget(limits(4, 10000, 100000, 10000)); var mock = new Mock(); var c = collector(budget, mock);
        assertThatThrownBy(c::collect).isInstanceOf(CollectionBudget.Failure.class).hasMessage("REQUEST_LIMIT");
        assertThat(mock.sent).hasValue(4); assertThat(budget.requests()).isEqualTo(4);
        assertThat(c.failureSummary(CollectionBudget.Code.REQUEST_LIMIT)).contains("tagoComplete=false", "gbisComplete=true");
        var failed = new Mock(); failed.failAt = 2; var second = new CollectionBudget(limits(20, 10000, 100000, 10000));
        assertThatThrownBy(() -> collector(second, failed).collect()).hasMessage("HTTP_ERROR");
        assertThat(second.requests()).isEqualTo(2);
    }
    @Test void limits_response_and_total_during_read() throws Exception {
        var small = new CollectionBudget(limits(2, 4, 100, 10000));
        assertThatThrownBy(() -> small.read(new ByteArrayInputStream(bytes("123456789")))).hasMessage("RESPONSE_SIZE_LIMIT");
        assertThat(small.bytes()).isEqualTo(5);
        var total = new CollectionBudget(limits(2, 20, 4, 10000));
        assertThatThrownBy(() -> total.read(new ByteArrayInputStream(bytes("123456789")))).hasMessage("TOTAL_BYTES_LIMIT");
        assertThat(total.bytes()).isEqualTo(5);
        var mock = new Mock(); var budget = new CollectionBudget(limits(50, 10, 1000, 10000)); var collector = collector(budget, mock);
        assertThatThrownBy(collector::collect).hasMessage("RESPONSE_SIZE_LIMIT");
        try (var files = Files.walk(temp)) {
            assertThat(files.noneMatch(p -> p.getFileName().toString().equals("manifest.json"))).isTrue();
        }
    }
    @Test void invalid_version_and_interrupt_stop_without_completed_manifest() throws Exception {
        var mock = new Mock(); mock.base = BASE.replace("routeStationVersion\":\"v1", "routeStationVersion\":\"v2");
        var budget = new CollectionBudget(limits(100, 10000, 100000, 10000));
        assertThatThrownBy(() -> collector(budget, mock).collect()).hasMessage("INVALID_RESPONSE");
        assertThat(mock.sent).hasValue(1);
        var interrupted = new CollectionBudget(limits(2, 100, 1000, 10000));
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(interrupted::reserve).hasMessage("INTERRUPTED"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
    @Test void interval_and_monotonic_deadline_are_enforced() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var budget = new CollectionBudget(limits(2, 100, 1000, 10), clock::get);
        budget.reserve(); clock.set(Duration.ofMillis(10).toNanos());
        assertThatThrownBy(budget::reserve).hasMessage("TIME_LIMIT");
        var timed = new CollectionBudget(new CollectionBudget.Limits(2, 100, 1000, Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        timed.reserve(); long start = System.nanoTime(); timed.reserve();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos());
    }
    @Test void cli_dry_run_and_validation_never_use_transport() throws Exception {
        var output = new ByteArrayOutputStream(); var errors = new ByteArrayOutputStream(); var mock = new Mock();
        var env = Map.of("PUBLIC_DATA_SERVICE_KEY", KEY);
        var path = "--directory=" + temp.toRealPath();
        assertThat(TransitMetadataCollectionCli.run(new String[]{path}, env, new PrintStream(output), new PrintStream(errors), mock)).isZero();
        assertThat(output.toString()).contains("DRY_RUN", "actual total is unknown").doesNotContain(KEY);
        assertThat(TransitMetadataCollectionCli.run(new String[]{path, "--live"}, env, new PrintStream(output), new PrintStream(errors), mock)).isEqualTo(2);
        assertThat(TransitMetadataCollectionCli.run(new String[]{path, "--live", "--confirm=COLLECT"}, env, new PrintStream(output), new PrintStream(errors), mock)).isEqualTo(2);
        assertThat(TransitMetadataCollectionCli.run(new String[]{path, "--max-requests=0"}, env, new PrintStream(output), new PrintStream(errors), mock)).isEqualTo(2);
        assertThat(TransitMetadataCollectionCli.run(new String[]{"--directory=relative"}, env, new PrintStream(output), new PrintStream(errors), mock)).isEqualTo(2);
        assertThat(mock.sent).hasValue(0);
    }
    @Test void explicit_live_collects_only_through_mock() throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream(); var mock = new Mock();
        var args = new String[]{"--directory=" + temp.toRealPath(), "--live", "--confirm=COLLECT", "--max-requests=50", "--interval-ms=1"};
        int status = TransitMetadataCollectionCli.run(args, Map.of("PUBLIC_DATA_SERVICE_KEY", KEY), new PrintStream(out), new PrintStream(err), mock);
        assertThat(status).isZero();
        assertThat(mock.sent).hasValue(40);
        assertThat(out.toString()).contains("complete=true", "requests=40", "enrichedRoutes=1").doesNotContain(KEY);
        assertThat(err.toString()).isEmpty();
    }
    @Test void invalid_official_download_and_storage_leave_incomplete_bundle() throws Exception {
        var mock = new Mock(); mock.base = BASE.replace("openapi.gbis.go.kr", "evil.test");
        var c = collector(new CollectionBudget(limits(50, 10000, 100000, 10000)), mock);
        assertThatThrownBy(c::collect).hasMessage("INVALID_RESPONSE");
        assertThat(mock.sent).hasValue(1);
        assertThat(c.failureSummary(CollectionBudget.Code.INVALID_RESPONSE)).contains("gbisComplete=false").doesNotContain(KEY);
    }
    @Test void socket_transport_rejects_redirect_and_stops_oversized_body() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        server.createContext("/redirect", e -> { hits.incrementAndGet(); e.getResponseHeaders().add("Location", "/target"); e.sendResponseHeaders(302, -1); e.close(); });
        server.createContext("/target", e -> { hits.addAndGet(100); e.sendResponseHeaders(200, -1); e.close(); });
        server.createContext("/denied", e -> { hits.incrementAndGet(); byte[] secret = bytes(KEY); e.sendResponseHeaders(429, secret.length); e.getResponseBody().write(secret); e.close(); });
        server.createContext("/large", e -> { hits.incrementAndGet(); byte[] data = new byte[20000]; e.sendResponseHeaders(200, data.length); try { e.getResponseBody().write(data); } catch (java.io.IOException ignored) { } e.close(); });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            var budget = new CollectionBudget(limits(10, 100, 10000, 5000));
            assertThatThrownBy(() -> CollectionHttp.NETWORK.get(URI.create(root + "/redirect"), budget)).hasMessage("HTTP_ERROR");
            assertThat(hits).hasValue(1); assertThat(budget.requests()).isEqualTo(1);
            assertThatThrownBy(() -> CollectionHttp.NETWORK.get(URI.create(root + "/large"), budget)).hasMessage("RESPONSE_SIZE_LIMIT");
            assertThat(hits).hasValue(2); assertThat(budget.requests()).isEqualTo(2);
            assertThatThrownBy(() -> CollectionHttp.NETWORK.get(URI.create(root + "/denied"), budget)).hasMessage("HTTP_ERROR").hasMessageNotContaining(KEY);
            assertThat(hits).hasValue(3); assertThat(budget.requests()).isEqualTo(3);
        } finally { server.stop(0); }
    }
    @Test void socket_timeout_ends_failed_attempt_without_retry() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        server.createContext("/slow", e -> { hits.incrementAndGet(); try { Thread.sleep(500); } catch (InterruptedException x) { Thread.currentThread().interrupt(); } e.close(); });
        server.start();
        try {
            var limits = new CollectionBudget.Limits(5, 1000, 10000, Duration.ofSeconds(2), Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofMillis(100));
            var budget = new CollectionBudget(limits);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            assertThatThrownBy(() -> CollectionHttp.NETWORK.get(uri, budget)).hasMessage("HTTP_ERROR");
            assertThat(budget.requests()).isEqualTo(1); assertThat(hits).hasValue(1);
        } finally { server.stop(0); }
    }
    @Test void duplicate_routes_never_publish_tago_manifest() throws Exception {
        var mock = new Mock(); mock.duplicate = true;
        var c = collector(new CollectionBudget(limits(50, 10000, 100000, 10000)), mock);
        assertThatThrownBy(c::collect).hasMessage("INVALID_RESPONSE");
        assertThat(c.failureSummary(CollectionBudget.Code.INVALID_RESPONSE)).contains("tagoComplete=false", "gbisComplete=true");
        try (var files = Files.walk(temp)) {
            assertThat(files.filter(p -> p.getFileName().toString().equals("manifest.json")).count()).isEqualTo(1);
        }
    }
    @Test void socket_total_deadline_terminates_slow_response() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", e -> { try { Thread.sleep(800); } catch (InterruptedException x) { Thread.currentThread().interrupt(); } e.close(); });
        server.start();
        try {
            var budget = new CollectionBudget(new CollectionBudget.Limits(2, 1000, 10000, Duration.ofMillis(250), Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(3)));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            assertThatThrownBy(() -> CollectionHttp.NETWORK.get(uri, budget)).hasMessage("TIME_LIMIT");
        } finally { server.stop(0); }
    }
    @Test void deadline_guard_prevents_both_bundle_manifests() throws Exception {
        var gbis = GbisLocalBundle.create(temp.toRealPath(), KEY);
        gbis.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE));
        gbis.add(GbisLocalBundle.Source.ROUTE, bytes(ROUTE));
        gbis.add(GbisLocalBundle.Source.ROUTE_STATION, bytes(STATIONS));
        assertThatThrownBy(() -> gbis.complete(() -> { throw new CollectionBudget.Failure(CollectionBudget.Code.TIME_LIMIT); })).hasMessage("TIME_LIMIT");
        assertThat(Files.exists(gbis.directory().resolve("manifest.json"))).isFalse();
        var tago = TagoLocalBundle.create(temp.toRealPath(), java.util.List.of("31010"), KEY);
        tago.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0), bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}"));
        tago.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, "31010", null, 1), bytes(page("{\"routeid\":\"GGB200\",\"routeno\":\"300\"}", 1, 1)));
        tago.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTE_STOPS, "31010", "GGB200", 1), bytes(page("{\"nodeid\":\"GGB10\",\"nodenm\":\"A\",\"nodeord\":1}", 1, 1)));
        assertThatThrownBy(() -> tago.complete(() -> { throw new CollectionBudget.Failure(CollectionBudget.Code.TIME_LIMIT); })).hasMessage("TIME_LIMIT");
        assertThat(Files.exists(tago.directory().resolve("manifest.json"))).isFalse();
    }
    @Test void socket_interruption_preserves_flag_and_does_not_retry() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var hits = new AtomicInteger();
        server.createContext("/slow", e -> { hits.incrementAndGet(); entered.countDown(); try { Thread.sleep(600); } catch (InterruptedException x) { Thread.currentThread().interrupt(); } e.close(); });
        server.start();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var budget = new CollectionBudget(limits(5, 1000, 10000, 5000));
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        var thread = new Thread(() -> { try { CollectionHttp.NETWORK.get(uri, budget); } catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); } });
        try {
            thread.start(); assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            thread.interrupt(); thread.join(3000);
            assertThat(thread.isAlive()).isFalse(); assertThat(failure.get()).hasMessage("INTERRUPTED");
            assertThat(interrupted).isTrue(); assertThat(hits).hasValue(1); assertThat(budget.requests()).isEqualTo(1);
        } finally { thread.interrupt(); server.stop(0); }
    }
    @Test void connection_failure_is_not_retried() throws Exception {
        var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(800);
        var accepted = new AtomicInteger();
        var worker = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) { accepted.incrementAndGet(); }
                }
            } catch (java.io.IOException ignored) { }
        });
        worker.start();
        try {
            var budget = new CollectionBudget(limits(5, 1000, 10000, 5000));
            URI uri = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/closed");
            Throwable failure = catchThrowable(() -> CollectionHttp.NETWORK.get(uri, budget));
            worker.join(2000);
            String diagnostics = "accepted=" + accepted.get() + " requests=" + budget.requests()
                    + " exception=" + (failure == null ? "none" : failure.getClass().getSimpleName());
            assertThat(worker.isAlive()).as(diagnostics).isFalse();
            assertThat(failure).as(diagnostics).isInstanceOf(CollectionBudget.Failure.class).hasMessage("HTTP_ERROR");
            assertThat(accepted.get()).as(diagnostics).isEqualTo(1);
            assertThat(budget.requests()).as(diagnostics).isEqualTo(1);
        } finally { server.close(); worker.join(2000); }
    }
    @Test void actual_request_starts_are_separated_even_after_slow_response() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var times = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        server.createContext("/ok", e -> {
            times.add(System.nanoTime());
            try { Thread.sleep(100); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
            byte[] payload = bytes("ok"); e.sendResponseHeaders(200, payload.length); e.getResponseBody().write(payload); e.close();
        });
        server.start();
        try {
            var l = new CollectionBudget.Limits(2, 1000, 10000, Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(2));
            var budget = new CollectionBudget(l);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
            assertThat(CollectionHttp.NETWORK.get(uri, budget)).isEqualTo(bytes("ok"));
            assertThat(CollectionHttp.NETWORK.get(uri, budget)).isEqualTo(bytes("ok"));
            assertThat(times).hasSize(2);
            assertThat(times.get(1) - times.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(150).toNanos());
        } finally { server.stop(0); }
    }
    @Test void storage_failure_never_publishes_partial_tago_bundle() throws Exception {
        var mock = new Mock();
        mock.beforeCity = () -> {
            try (var children = Files.list(temp)) {
                Path tago = children.filter(Files::isDirectory).filter(p -> !Files.exists(p.resolve("manifest.json"))).findFirst().orElseThrow();
                Files.writeString(tago.resolve("00000000.json"), "occupied");
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        var c = collector(new CollectionBudget(limits(50, 10000, 100000, 10000)), mock);
        assertThatThrownBy(c::collect).hasMessage("STORAGE_FAILURE");
        assertThat(mock.sent).hasValue(4);
        assertThat(c.failureSummary(CollectionBudget.Code.STORAGE_FAILURE)).contains("tagoComplete=false", "gbisComplete=true");
        try (var files = Files.walk(temp)) {
            assertThat(files.filter(p -> p.getFileName().toString().equals("manifest.json")).count()).isEqualTo(1);
        }
    }
    @Test void cli_failure_reports_partial_state_without_credentials() throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream(); var mock = new Mock(); mock.failAt = 2;
        var args = new String[]{"--directory=" + temp.toRealPath(), "--live", "--confirm=COLLECT", "--max-requests=50", "--interval-ms=1"};
        assertThat(TransitMetadataCollectionCli.run(args, Map.of("PUBLIC_DATA_SERVICE_KEY", KEY), new PrintStream(out), new PrintStream(err), mock)).isEqualTo(1);
        assertThat(mock.sent).hasValue(2);
        assertThat(err.toString()).contains("complete=false", "provider=GBIS", "stage=ROUTE", "failure=HTTP_ERROR", "requests=2", "gbisComplete=false").doesNotContain(KEY, "openapi.gbis.go.kr");
        assertThat(out.toString()).doesNotContain(KEY);
    }
    @Test void late_retry_properties_are_rejected_before_reserving_or_connecting() throws Exception {
        try (var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(1000);
            String uri = "http://127.0.0.1:" + server.getLocalPort() + "/closed";
            var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                    "-Djdk.httpclient.redirects.retrylimit=5", "-Djdk.httpclient.disableRetryConnect=false",
                    "-cp", System.getProperty("task811.test.classpath"), LateSettingsProbe.class.getName(), uri)
                    .redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as("child result: %s", output).isZero();
                assertThat(output).contains("failure=HTTP_ERROR requests=0");
                assertThatThrownBy(server::accept).isInstanceOf(java.net.SocketTimeoutException.class);
            } finally { process.destroyForcibly(); }
        }
    }
    public static final class LateSettingsProbe {
        public static void main(String[] args) throws Exception {
            Class.forName("jdk.internal.net.http.MultiExchange", true, ClassLoader.getPlatformClassLoader());
            System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
            System.setProperty("jdk.httpclient.disableRetryConnect", "true");
            var budget = new CollectionBudget(limits(5, 1000, 10000, 5000));
            try {
                CollectionHttp.NETWORK.get(URI.create(args[0]), budget);
                throw new AssertionError("Unexpected successful request");
            } catch (CollectionBudget.Failure failure) {
                if (failure.code != CollectionBudget.Code.HTTP_ERROR || budget.requests() != 0) throw failure;
                System.out.println("failure=" + failure.code + " requests=" + budget.requests());
            }
        }
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void cli_reports_safe_gbis_validation_without_more_requests_or_manifest(org.springframework.boot.test.system.CapturedOutput logs) throws Exception {
        var scenarios = java.util.Map.of(
                BASE.replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":\"v2\""), "VERSION_MISMATCH",
                BASE.replace("file=route", "file=route&signature=fake"), "URL_FORBIDDEN_QUERY",
                BASE.replace("file=route", "file=route&token=secret"), "URL_FORBIDDEN_QUERY",
                BASE.replace("file=route", "file=%73ecret"), "URL_ENCODED_OR_USERINFO",
                "{\"serviceKey\":\"" + KEY + "\",", "JSON_PARSE_ERROR",
                BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"password\":\"secret\""), "CONTENT_UNSAFE");
        for (var scenario : scenarios.entrySet()) {
            var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream(); var mock = new Mock(); mock.base = scenario.getKey();
            var args = new String[]{"--directory=" + temp.toRealPath(), "--live", "--confirm=COLLECT", "--max-requests=50", "--interval-ms=1"};
            assertThat(TransitMetadataCollectionCli.run(args, Map.of("PUBLIC_DATA_SERVICE_KEY", KEY), new PrintStream(output), new PrintStream(error), mock)).isEqualTo(1);
            assertThat(error.toString()).contains("provider=GBIS", "stage=BASE_INFO", "failure=INVALID_RESPONSE", "validation=" + scenario.getValue());
            assertThat(output.toString() + error).doesNotContain(KEY, "signature=fake", "token=secret", "openapi.gbis.go.kr", scenario.getKey());
            assertThat(mock.sent).hasValue(1);
        }
        assertThat(logs.getAll()).doesNotContain(KEY, "signature=fake", "token=secret", "openapi.gbis.go.kr");
        try (var files = Files.walk(temp)) {
            assertThat(files.noneMatch(p -> p.getFileName().toString().equals("manifest.json"))).isTrue();
        }
        var normalHttpFailure = new Mock(); normalHttpFailure.failAt = 1;
        var budget = new CollectionBudget(limits(50, 10000, 100000, 10000)); var collection = collector(budget, normalHttpFailure);
        assertThatThrownBy(collection::collect).hasMessage("HTTP_ERROR");
        assertThat(collection.failureSummary(CollectionBudget.Code.HTTP_ERROR)).doesNotContain("validation=");
    }
}
