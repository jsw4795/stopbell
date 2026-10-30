package com.stopbell.notification.client;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.util.ExponentialBackOff;
import com.google.gson.JsonParser;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.transit.domain.TransitEventType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.stopbell.notification.entity.BoardingNotificationEventType;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.stopbell.notification.entity.NotificationProviderResult.*;

class FcmProviderClientTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final String FID = "synthetic-FID-do-not-log";
    private static final String TOKEN = "synthetic-access-token-do-not-log";
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> payload = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stop_server() {
        if (server != null) {
            server.stop(0);
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("한 번의 전송은 FID와 최소 navigation payload 및 iOS alert를 사용한다")
    void sends_fid_payload_and_alert() throws Exception {
        URI endpoint = server(exchange -> respond(exchange, 200, "{\"name\":\"projects/stopbell/messages/test\"}"));
        var result = client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request());
        assertThat(result.result()).isEqualTo(ACCEPTED);
        assertThat(result.fcmRequestStarted()).isTrue();
        assertThat(result.failureCode()).isNull();
        assertThat(result.receivedAt()).isEqualTo(NOW);
        assertThat(calls).hasValue(1);
        var message = JsonParser.parseString(payload.get()).getAsJsonObject().getAsJsonObject("message");
        assertThat(message.keySet()).containsExactlyInAnyOrder("fid", "data", "notification", "apns");
        assertThat(message.get("fid").getAsString()).isEqualTo(FID);
        var data = message.getAsJsonObject("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("type", "alarmId", "eventType", "notificationEventId");
        assertThat(data.get("type").getAsString()).isEqualTo("ALARM_EVENT");
        assertThat(data.get("alarmId").getAsString()).isEqualTo("123");
        assertThat(data.get("notificationEventId").getAsString()).isEqualTo("456");
        assertThat(data.get("eventType").getAsString()).isEqualTo("ARRIVED");
        var apns = message.getAsJsonObject("apns");
        assertThat(apns.getAsJsonObject("headers").get("apns-expiration").getAsString())
                .isEqualTo(Long.toString(NOW.plusSeconds(60).getEpochSecond()));
        assertThat(apns.getAsJsonObject("headers").get("apns-push-type").getAsString()).isEqualTo("alert");
        assertThat(apns.getAsJsonObject("payload").getAsJsonObject("aps").getAsJsonObject("alert"))
                .isEqualTo(message.getAsJsonObject("notification"));
    }

    @ParameterizedTest
    @EnumSource(BoardingNotificationEventType.class)
    @DisplayName("준비와 목적지 HTTP v1은 원래 기한과 최소 payload를 보존하며 실제 정차를 단정하지 않는다")
    void sends_boarding_payload_without_internal_state(BoardingNotificationEventType type) throws Exception {
        URI endpoint = server(exchange -> respond(exchange, 200, "{\"name\":\"projects/stopbell/messages/session\"}"));
        var source = NotificationSourceHint.boardingSession(123, type);
        var request = new NotificationSendRequest(FID, source, 456, NOW.plusSeconds(30), "실제 목적지");
        assertThat(client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request).result()).isEqualTo(ACCEPTED);
        var message = JsonParser.parseString(payload.get()).getAsJsonObject().getAsJsonObject("message");
        assertThat(message.get("fid").getAsString()).isEqualTo(FID);
        var data = message.getAsJsonObject("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("type", "boardingSessionId", "eventType", "notificationEventId");
        assertThat(data.get("type").getAsString()).isEqualTo("BOARDING_SESSION_EVENT");
        assertThat(data.get("boardingSessionId").getAsString()).isEqualTo("123");
        assertThat(data.get("eventType").getAsString()).isEqualTo(type.name());
        assertThat(data.get("notificationEventId").getAsString()).isEqualTo("456");
        data.entrySet().forEach(item -> assertThat(item.getValue().getAsJsonPrimitive().isString()).isTrue());
        var apns = message.getAsJsonObject("apns");
        assertThat(apns.getAsJsonObject("headers").get("apns-expiration").getAsString())
                .isEqualTo(Long.toString(request.expiresAt().getEpochSecond()));
        var aps = apns.getAsJsonObject("payload").getAsJsonObject("aps");
        assertThat(aps.getAsJsonObject("alert")).isEqualTo(message.getAsJsonObject("notification"));
        assertThat(aps.get("sound").getAsString()).isEqualTo("default");
        assertThat(message.getAsJsonObject("notification").get("body").getAsString())
                .contains(type == BoardingNotificationEventType.PREPARE_ALIGHT ? "하차를 준비" : "도달하거나 통과")
                .doesNotContain("정차했", "하차했");
        if (type == BoardingNotificationEventType.PREPARE_ALIGHT) {
            assertThat(message.getAsJsonObject("notification").get("body").getAsString()).contains("실제 목적지");
        }
        assertThat(payload.get()).doesNotContain("alarmId", "userId", "revision", "checkpoint", "fingerprint", "GPS");
    }

    @ParameterizedTest
    @CsvSource({"ONE_STOP_BEFORE,한 정거장", "ARRIVED,도착", "PASSED,지나갔", "ONE_STOP_AFTER,다음 정류장"})
    @DisplayName("네 Event type은 위치 상세 없이 자연스러운 한국어 알림을 만든다")
    void supports_all_event_types(TransitEventType type, String word) {
        var request = new NotificationSendRequest(FID, 123, type, 456, NOW.plusSeconds(60));
        var message = JsonParser.parseString(FcmProviderClient.payload(request)).getAsJsonObject()
                .getAsJsonObject("message");
        assertThat(message.getAsJsonObject("data")).isEqualTo(JsonParser.parseString(
                "{\"type\":\"ALARM_EVENT\",\"alarmId\":\"123\",\"eventType\":\"" + type.name()
                    + "\",\"notificationEventId\":\"456\"}"));
        assertThat(message.getAsJsonObject("notification").get("body").getAsString()).contains(word);
        assertThat(message.getAsJsonObject("data").get("eventType").getAsString()).isEqualTo(type.name());
    }

    @ParameterizedTest
    @CsvSource(value = {"강남역,3", "강남역,NULL", "NULL,3", "NULL,NULL", "강남역,0", "강남역,-1",
            "NULL,0", "NULL,-1"}, nullValues = "NULL")
    @DisplayName("PASSED는 표시명과 양수 정거장 수를 독립적으로 안내하고 두 alert body와 data identity를 보존한다")
    void passed_uses_optional_event_evidence(String name, Integer stops) {
        String expected = "버스가 목표 정류장을 지나갔어요.";
        if (name != null) {
            expected += "\n최근 확인된 정류장: 강남역";
            if (stops != null && stops > 0) expected += " (목표 정류장 이후 3정거장)";
        } else if (stops != null && stops > 0) {
            expected += "\n목표 정류장 이후 3정거장 이동한 것으로 확인됐어요.";
        }
        assertPassedPayload(name, stops, expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\u00a0", "강남\n역", "강남\r역", "강남\t역", "강남\u0000역",
            "강남\u007f역", "강남\u0085역", "강남\u2028역", "강남\u2029역", "강남\u202e역", "강남\ud800역"})
    @DisplayName("공백과 제어 문자 및 표시 불가능한 이름은 생략해도 검증된 정거장 수는 안내한다")
    void passed_omits_unprintable_name(String name) {
        assertPassedPayload(name, 3, "버스가 목표 정류장을 지나갔어요.\n목표 정류장 이후 3정거장 이동한 것으로 확인됐어요.");
        assertPassedPayload(name, null, "버스가 목표 정류장을 지나갔어요.");
    }

    @ParameterizedTest
    @ValueSource(ints = {79, 80, 81, 255})
    @DisplayName("긴 한글과 보조 평면 문자는 surrogate를 자르지 않고 말줄임표 포함 80 code point로 제한한다")
    void passed_bounds_stop_name(int length) {
        String name = "가".repeat(length - 2) + "🚏역";
        String expectedName = length <= 80 ? name : name.substring(0, 79) + "…";
        assertPassedPayload(name, 3, "버스가 목표 정류장을 지나갔어요.\n최근 확인된 정류장: "
                + expectedName + " (목표 정류장 이후 3정거장)");
        var context = new PassedNotificationContext("🚏".repeat(length), 3);
        assertThat(context.currentStopName().codePointCount(0, context.currentStopName().length()))
                .isEqualTo(Math.min(length, 80));
        assertThat(context.currentStopName()).isEqualTo(length <= 80 ? "🚏".repeat(length) : "🚏".repeat(79) + "…");
    }

    @Test
    @DisplayName("앞뒤 공백은 제거하고 한글 이름의 따옴표와 역슬래시는 JSON escape 후 그대로 표시한다")
    void passed_escapes_display_name() {
        assertPassedPayload("  강남\"역\\출구  ", 3,
                "버스가 목표 정류장을 지나갔어요.\n최근 확인된 정류장: 강남\"역\\출구 (목표 정류장 이후 3정거장)");
    }

    @ParameterizedTest
    @EnumSource(value = TransitEventType.class, names = "PASSED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("다른 Alarm Event에 PASSED 문맥이 입력되어도 기존 문구와 data를 유지한다")
    void ignores_passed_context_for_other_alarm_events(TransitEventType type) {
        var source = NotificationSourceHint.alarm(123, type);
        var request = new NotificationSendRequest(FID, source, 456, NOW.plusSeconds(60), null,
                new PassedNotificationContext("강남역", 3));
        assertThat(request.passedContext()).isNull();
        assertThat(FcmProviderClient.payload(request)).isEqualTo(FcmProviderClient.payload(
                new NotificationSendRequest(FID, source, 456, NOW.plusSeconds(60))));
    }

    @ParameterizedTest
    @EnumSource(BoardingNotificationEventType.class)
    @DisplayName("BoardingSession의 두 Event는 PASSED 문맥을 버리고 기존 문구와 data를 유지한다")
    void isolates_passed_context_from_boarding(BoardingNotificationEventType type) {
        var source = NotificationSourceHint.boardingSession(123, type);
        var request = new NotificationSendRequest(FID, source, 456, NOW.plusSeconds(60), "목적지",
                new PassedNotificationContext("강남역", 3));
        assertThat(request.passedContext()).isNull();
        assertThat(FcmProviderClient.payload(request)).isEqualTo(FcmProviderClient.payload(
                new NotificationSendRequest(FID, source, 456, NOW.plusSeconds(60), "목적지")));
    }

    private void assertPassedPayload(String name, Integer stops, String expectedBody) {
        var request = new NotificationSendRequest(FID, NotificationSourceHint.alarm(123, TransitEventType.PASSED),
                456, NOW.plusSeconds(60), null, new PassedNotificationContext(name, stops));
        var message = JsonParser.parseString(FcmProviderClient.payload(request)).getAsJsonObject().getAsJsonObject("message");
        assertThat(message.keySet()).containsExactlyInAnyOrder("fid", "data", "notification", "apns");
        assertThat(message.getAsJsonObject("notification").get("body").getAsString()).isEqualTo(expectedBody);
        var apns = message.getAsJsonObject("apns");
        assertThat(apns.getAsJsonObject("payload").getAsJsonObject("aps").getAsJsonObject("alert"))
                .isEqualTo(message.getAsJsonObject("notification"));
        assertThat(apns.getAsJsonObject("headers").get("apns-expiration").getAsString())
                .isEqualTo(Long.toString(request.expiresAt().getEpochSecond()));
        assertThat(message.getAsJsonObject("data")).isEqualTo(JsonParser.parseString(
                "{\"type\":\"ALARM_EVENT\",\"alarmId\":\"123\",\"eventType\":\"PASSED\",\"notificationEventId\":\"456\"}"));
    }

    @ParameterizedTest
    @CsvSource({"429,QUOTA_EXCEEDED", "500,INTERNAL", "503,UNAVAILABLE", "401,UNAUTHENTICATED", "403,PERMISSION_DENIED"})
    @DisplayName("오류와 Retry-After를 받아도 실제 HTTP 요청은 한 번만 발생한다")
    void never_retransmits_http_errors(int status, String code) throws Exception {
        URI endpoint = server(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            respond(exchange, status, "{\"error\":{\"status\":\"" + code + "\"}}");
        });
        var result = client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request());
        assertThat(result.result()).isEqualTo(status == 401 || status == 403 ? CONFIGURATION : RETRYABLE);
        assertThat(calls).hasValue(1);
        if (result.result() == RETRYABLE) {
            assertThat(result.minimumRetryDelay()).isEqualTo(Duration.ofSeconds(120));
        }
    }

    @Test
    @DisplayName("redirect 응답은 다른 endpoint로 재전송하지 않는다")
    void does_not_follow_redirect() throws Exception {
        URI endpoint = server(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            respond(exchange, 307, "{}");
        });
        assertThat(client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request()).result())
                .isEqualTo(PERMANENT_REQUEST);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    @DisplayName("원문 및 gzip response의 해제된 body 크기를 제한한다")
    void bounds_decoded_response(boolean gzip) throws Exception {
        byte[] content = "x".repeat(20_000).getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            var buffer = new java.io.ByteArrayOutputStream();
            try (var stream = new java.util.zip.GZIPOutputStream(buffer)) {
                stream.write(content);
            }
            content = buffer.toByteArray();
        }
        byte[] bytes = content;
        URI endpoint = server(exchange -> {
            if (gzip) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        assertThat(client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request()).result())
                .isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("중복 Retry-After에서 더 큰 Provider 지연을 무시하지 않는다")
    void respects_largest_retry_after() throws Exception {
        URI endpoint = server(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            exchange.getResponseHeaders().add("Retry-After", "Mon, 12 Oct 2026 00:02:00 GMT");
            respond(exchange, 503, "{}");
        });
        assertThat(client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request()).minimumRetryDelay())
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("응답 전 연결 종료는 자동 재전송 없이 접수 불명으로 반환한다")
    void disconnect_is_ambiguous_without_retry() throws Exception {
        URI endpoint = server(HttpExchange::close);
        assertThat(client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request()).result())
                .isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("인증 HTTP의 DNS 실패는 개별 전송 결과와 구분하여 설정 실패로 반환한다")
    void auth_connection_failure_is_configuration() {
        var client = client(URI.create("http://localhost:1/send"), Duration.ofSeconds(1), attempt -> {
            throw new UnknownHostException("sensitive auth details");
        });
        // Authentication failure is global configuration, even if its own HTTP request was unsent.
        assertThat(client.send(request()).result()).isEqualTo(CONFIGURATION);
    }

    @Test
    @DisplayName("전체 budget은 천천히 진행되는 실제 응답 body도 중단한다")
    void budget_aborts_slow_body() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        URI endpoint = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write('{');
                body.flush();
                // Keep the body progressing so the overall budget, rather than an idle
                // socket timeout clamped to the same deadline, is the condition under test.
                for (int chunk = 0; chunk < 60 && !release.await(50, TimeUnit.MILLISECONDS); chunk++) {
                    body.write(' ');
                    body.flush();
                }
                body.write("\"name\":\"projects/stopbell/messages/late\"}".getBytes(StandardCharsets.UTF_8));
                body.flush();
            } catch (Exception ignored) {
                closed.countDown();
            }
        });
        try {
            var result = client(endpoint, Duration.ofMillis(300), attempt -> TOKEN).send(request());
            assertThat(result.result()).isEqualTo(AMBIGUOUS_TIMEOUT);
            assertThat(result.failureCode()).isEqualTo("REQUEST_BUDGET_EXHAUSTED");
            assertThat(result.elapsedMs()).isBetween(250L, 1500L);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
        assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("read timeout은 connect 단계로 추정하지 않고 접수 불명으로 분류한다")
    void read_timeout_is_ambiguous() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        URI endpoint = server(exchange -> {
            try {
                release.await(3, TimeUnit.SECONDS);
                respond(exchange, 200, "{}");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        var properties = new FcmClientProperties(true, "stopbell", "unused", Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofSeconds(2));
        try {
            var result = new FcmProviderClient(properties, Clock.fixed(NOW, ZoneOffset.UTC), endpoint,
                    attempt -> TOKEN).send(request());
            assertThat(result.result()).isEqualTo(AMBIGUOUS_TIMEOUT);
            assertThat(result.elapsedMs()).isLessThan(1500);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("인증 budget 이후 늦게 완료된 작업은 FCM 요청을 시작할 수 없다")
    void auth_timeout_blocks_late_dispatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sourceFinished = new CountDownLatch(1);
        URI endpoint = server(exchange -> respond(exchange, 200, "{}"));
        var client = client(endpoint, Duration.ofMillis(100), attempt -> {
            boolean waiting = true;
            while (waiting) {
                try {
                    release.await();
                    waiting = false;
                } catch (InterruptedException ignored) { }
            }
            try {
                assertThatThrownBy(attempt::checkOpen).isInstanceOf(IOException.class);
                return TOKEN;
            } finally {
                sourceFinished.countDown();
            }
        });
        try {
            var result = client.send(request());
            assertThat(result.result()).isEqualTo(CONFIGURATION);
            assertThat(result.failureCode()).isEqualTo("AUTH_BUDGET_EXHAUSTED");
            assertThat(result.fcmRequestStarted()).isFalse();
        } finally {
            release.countDown();
        }
        assertThat(sourceFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("Google 인증 transport에서 숨겨진 두 번째 HTTP 요청도 차단한다")
    void auth_transport_blocks_hidden_retry() throws Exception {
        URI endpoint = server(exchange -> respond(exchange, 503, "{}"));
        var result = client(endpoint, Duration.ofSeconds(2), attempt -> {
            var request = attempt.authTransport().createRequestFactory().buildGetRequest(new GenericUrl(endpoint));
            request.setLoggingEnabled(false);
            request.setNumberOfRetries(2);
            request.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
                    new ExponentialBackOff.Builder().setInitialIntervalMillis(1).build()));
            request.execute();
            return TOKEN;
        }).send(request());
        assertThat(result.result()).isEqualTo(CONFIGURATION);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("인증 HTTP 지연도 동일한 budget으로 실제 연결을 중단한다")
    void auth_http_is_bounded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        URI endpoint = server(exchange -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        try {
            var result = client(endpoint, Duration.ofMillis(300), attempt -> {
                var request = attempt.authTransport().createRequestFactory().buildGetRequest(new GenericUrl(endpoint));
                request.setLoggingEnabled(false);
                request.setNumberOfRetries(0);
                request.execute();
                return TOKEN;
            }).send(request());
            assertThat(result.result()).isEqualTo(CONFIGURATION);
            assertThat(result.elapsedMs()).isBetween(250L, 1500L);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("설정 누락과 DB transaction 내부 호출은 실제 전송 없이 거부한다")
    void configuration_and_transaction_are_guarded() {
        var disabled = new FcmProviderClient(new FcmClientProperties(false, "", "", null, null, null));
        assertThat(disabled.send(request()).result()).isEqualTo(CONFIGURATION);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var client = client(URI.create("http://localhost:1/send"), Duration.ofSeconds(1), attempt -> {
            throw new AssertionError("Must not authenticate in transaction");
        });
        assertThat(client.send(request()).failureCode()).isEqualTo("TRANSACTION_ACTIVE");
    }

    @Test
    @DisplayName("로그와 결과 및 request 문자열에 target token 원문 오류가 노출되지 않는다")
    void sensitive_data_is_redacted() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(FcmProviderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            URI endpoint = server(exchange -> respond(exchange, 400,
                    "{\"error\":{\"message\":\"" + FID + TOKEN + "\",\"status\":\"INVALID_ARGUMENT\"}}"));
            var result = client(endpoint, Duration.ofSeconds(2), attempt -> TOKEN).send(request());
            var failure = client(endpoint, Duration.ofSeconds(2), attempt -> {
                throw new IOException(FID + TOKEN);
            }).send(request());
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertThat(logs + result + failure + request()).doesNotContain(FID, TOKEN, "INVALID_ARGUMENT\"}");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private FcmProviderClient client(URI endpoint, Duration budget, FcmProviderClient.TokenSource tokens) {
        return new FcmProviderClient(new FcmClientProperties(true, "stopbell", "unused", Duration.ofSeconds(2),
                Duration.ofSeconds(5), budget), Clock.fixed(NOW, ZoneOffset.UTC), endpoint, tokens);
    }

    private NotificationSendRequest request() {
        return new NotificationSendRequest(FID, 123, TransitEventType.ARRIVED, 456, NOW.plusSeconds(60));
    }

    private URI server(Handler handler) throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> Thread.startVirtualThread(command));
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
