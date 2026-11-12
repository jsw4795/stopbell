package com.stopbell.notification.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.Retryable;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.gson.Gson;
import com.stopbell.notification.client.FcmFailureMapper.Classification;
import com.stopbell.notification.entity.NotificationSourceType;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.stopbell.notification.entity.NotificationProviderResult.*;

/** Single FID dispatch only. No DB writes, worker, retries or cleanup. */
public final class FcmProviderClient {
    private static final Logger log = LoggerFactory.getLogger(FcmProviderClient.class);
    private static final Gson JSON = new Gson();
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @FunctionalInterface
    interface TokenSource {
        String token(FcmAttempt attempt) throws IOException;

        /** FCM rejected the token with 401. True if a cached token was dropped, so the next attempt refreshes. */
        default boolean reject(String token) {
            return false;
        }
    }

    /** Credential/permission problem that retrying cannot fix. Everything else from the token source is transient. */
    static final class PermanentAuthException extends IOException {
        PermanentAuthException(Throwable cause) {
            super("FCM credential invalid", cause);
        }
    }

    private final FcmClientProperties properties;
    private final Clock clock;
    private final URI endpoint;
    private final TokenSource tokenSource;

    public FcmProviderClient(FcmClientProperties properties) {
        this(properties, Clock.systemUTC(), URI.create("https://fcm.googleapis.com/v1/projects/"
                + (properties.configured() ? properties.projectId() : "unconfigured") + "/messages:send"),
                new ServiceAccountTokenCache(properties, Clock.systemUTC()));
    }

    // Package-private seam: tests use loopback HTTP and synthetic tokens, never Firebase credentials.
    FcmProviderClient(FcmClientProperties properties, Clock clock, URI endpoint, TokenSource tokenSource) {
        this.properties = properties;
        this.clock = clock;
        this.endpoint = endpoint;
        this.tokenSource = tokenSource;
    }

    public NotificationSendResult send(NotificationSendRequest request) {
        long started = System.nanoTime();
        if (!properties.configured()) {
            return result(FcmFailureMapper.classified(CONFIGURATION, "PROVIDER_NOT_CONFIGURED"), started, false);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return result(FcmFailureMapper.classified(CONFIGURATION, "TRANSACTION_ACTIVE"), started, false);
        }
        FcmAttempt attempt = new FcmAttempt(properties, started);
        FutureTask<Classification> task = new FutureTask<>(() -> dispatch(request, attempt));
        Thread.startVirtualThread(task);
        Classification classification;
        try {
            long remaining = properties.requestBudget().toNanos() - (System.nanoTime() - started);
            classification = task.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            close(attempt);
            task.cancel(true);
            classification = attempt.messagingStarted()
                    ? FcmFailureMapper.classified(AMBIGUOUS_TIMEOUT, "REQUEST_BUDGET_EXHAUSTED")
                    : FcmFailureMapper.classified(RETRYABLE, "AUTH_BUDGET_EXHAUSTED");
        } catch (InterruptedException e) {
            close(attempt);
            task.cancel(true);
            Thread.currentThread().interrupt();
            classification = FcmFailureMapper.classified(attempt.messagingStarted() ? AMBIGUOUS_TIMEOUT
                    : CONFIGURATION, "CALL_INTERRUPTED");
        } catch (ExecutionException e) {
            classification = FcmFailureMapper.classified(attempt.messagingStarted() ? AMBIGUOUS_TIMEOUT
                    : CONFIGURATION, "UNKNOWN_ACCEPTANCE");
        } finally {
            close(attempt);
        }
        return result(classification, started, attempt.messagingStarted());
    }

    private Classification dispatch(NotificationSendRequest request, FcmAttempt attempt) {
        String token;
        try {
            token = tokenSource.token(attempt);
            attempt.checkOpen();
            if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
                return FcmFailureMapper.classified(CONFIGURATION, "AUTH_INITIALIZATION_FAILED");
            }
        } catch (PermanentAuthException e) {
            return FcmFailureMapper.classified(CONFIGURATION, "AUTH_INITIALIZATION_FAILED");
        } catch (IOException | RuntimeException e) {
            // Auth server connectivity, timeout, 5xx or budget exhaustion: nothing was sent, retry later.
            return FcmFailureMapper.classified(RETRYABLE, "AUTH_UNAVAILABLE");
        }
        try {
            HttpPost post = new HttpPost(endpoint);
            int remaining = attempt.remainingMillis();
            post.setConfig(RequestConfig.custom()
                    .setConnectTimeout((int) Math.min(properties.connectTimeout().toMillis(), remaining))
                    .setSocketTimeout((int) Math.min(properties.readTimeout().toMillis(), remaining))
                    .setConnectionRequestTimeout(remaining)
                    .setRedirectsEnabled(false).setAuthenticationEnabled(false).build());
            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("X-GOOG-API-FORMAT-VERSION", "2");
            post.setEntity(new StringEntity(payload(request), ContentType.APPLICATION_JSON));
            attempt.beginMessaging();
            try (CloseableHttpResponse response = attempt.execute(post)) {
                String body = "";
                if (response.getEntity() != null) {
                    try (InputStream stream = response.getEntity().getContent()) {
                        // FcmAttempt limits decoded response bytes for both FCM and OAuth.
                        body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                Instant receivedAt = clock.instant();
                Duration retryDelay = Duration.ZERO;
                for (Header header : response.getHeaders("Retry-After")) {
                    Duration delay = FcmFailureMapper.retryAfter(header.getValue(), receivedAt);
                    if (delay.compareTo(retryDelay) > 0) {
                        retryDelay = delay;
                    }
                }
                if (response.getStatusLine().getStatusCode() == 401 && tokenSource.reject(token)) {
                    return FcmFailureMapper.classified(RETRYABLE, "AUTH_TOKEN_EXPIRED");
                }
                return FcmFailureMapper.response(response.getStatusLine().getStatusCode(), body,
                        Long.toString(retryDelay.getSeconds()), receivedAt);
            }
        } catch (IOException | RuntimeException e) {
            return FcmFailureMapper.transportFailure(e);
        }
    }

    static String payload(NotificationSendRequest request) {
        boolean boarding = request.source().sourceType() == NotificationSourceType.BOARDING_SESSION;
        String body = boarding ? switch (request.source().boardingEventType()) {
            case PREPARE_ALIGHT -> (request.targetName() == null ? "선택한 정류장" : request.targetName())
                    + "이(가) 가까워졌어요. 하차를 준비해 주세요.";
            case DESTINATION_REACHED -> "버스가 선택한 정류장에 도달하거나 통과했어요.";
        } : switch (request.eventType()) {
            case ONE_STOP_BEFORE -> "목표 정류장까지 한 정거장 남았어요.";
            case ARRIVED -> "버스가 목표 정류장에 도착했어요.";
            case PASSED -> passedBody(request.passedContext());
            case ONE_STOP_AFTER -> "버스가 목표 정류장 다음 정류장에 도착했어요.";
        };
        Map<String, String> data = boarding
                ? Map.of("type", "BOARDING_SESSION_EVENT", "boardingSessionId", Long.toString(request.source().boardingSessionId()),
                    "eventType", request.source().boardingEventType().name(),
                    "notificationEventId", Long.toString(request.notificationEventId()))
                : Map.of("type", "ALARM_EVENT", "alarmId", Long.toString(request.alarmId()),
                    "eventType", request.eventType().name(),
                    "notificationEventId", Long.toString(request.notificationEventId()));
        Map<String, String> alert = Map.of("title", "StopBell 알림", "body", body);
        return JSON.toJson(Map.of("message", Map.of(
                "fid", request.fid(),
                "data", data,
                "notification", alert,
                "apns", Map.of("headers", Map.of("apns-push-type", "alert", "apns-priority", "10",
                                "apns-expiration", Long.toString(request.expiresAt().getEpochSecond())),
                        "payload", Map.of("aps", Map.of("alert", alert, "sound", "default")))
        )));
    }

    private static String passedBody(PassedNotificationContext context) {
        String base = "버스가 목표 정류장을 지나갔어요.";
        if (context == null) {
            return base;
        }
        String name = context.currentStopName();
        Integer stops = context.stopsPastTarget();
        if (name != null) {
            return base + "\n최근 확인된 정류장: " + name
                    + (stops == null ? "" : " (목표 정류장 이후 " + stops + "정거장)");
        }
        return stops == null ? base : base + "\n목표 정류장 이후 " + stops + "정거장 이동한 것으로 확인됐어요.";
    }

    private NotificationSendResult result(Classification value, long started, boolean fcmRequestStarted) {
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        log.info("FCM result={} code={} elapsedMs={}", value.result(), value.code(), elapsed);
        return new NotificationSendResult(value.result(), value.code(), value.minimumDelay(), clock.instant(), elapsed,
                fcmRequestStarted);
    }

    private static void close(FcmAttempt attempt) {
        try {
            attempt.close();
        } catch (IOException ignored) { }
    }

    /** Reuses the access token until shortly before it expires; the credential file is read only on refresh. */
    static final class ServiceAccountTokenCache implements TokenSource {
        private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

        @FunctionalInterface
        interface Refresher {
            AccessToken refresh(FcmAttempt attempt) throws IOException;
        }

        private final Clock clock;
        private final Refresher refresher;
        private AccessToken cached;
        private String refreshedThisCall;

        ServiceAccountTokenCache(FcmClientProperties properties, Clock clock) {
            this(clock, attempt -> refresh(properties, attempt));
        }

        ServiceAccountTokenCache(Clock clock, Refresher refresher) {
            this.clock = clock;
            this.refresher = refresher;
        }

        @Override
        public synchronized String token(FcmAttempt attempt) throws IOException {
            attempt.checkOpen();
            refreshedThisCall = null;
            if (cached != null && cached.getExpirationTime() != null
                    && clock.instant().plus(REFRESH_MARGIN).isBefore(cached.getExpirationTime().toInstant())) {
                return cached.getTokenValue();
            }
            cached = null;
            AccessToken fresh = refresher.refresh(attempt);
            attempt.checkOpen();
            cached = fresh;
            refreshedThisCall = fresh.getTokenValue();
            return refreshedThisCall;
        }

        /** A 401 for a token reused from cache may just be expired: drop it so the next attempt refreshes. */
        @Override
        public synchronized boolean reject(String token) {
            boolean reused = cached != null && cached.getTokenValue().equals(token)
                    && !token.equals(refreshedThisCall);
            if (cached != null && cached.getTokenValue().equals(token)) {
                cached = null;
            }
            return reused;
        }

        private static AccessToken refresh(FcmClientProperties properties, FcmAttempt attempt) throws IOException {
            try (InputStream stream = Files.newInputStream(Path.of(properties.credentialPath()))) {
                return ServiceAccountCredentials.fromStream(stream, attempt::authTransport)
                        .createScoped(SCOPE).createWithCustomRetryStrategy(false).refreshAccessToken();
            } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException e) {
                throw new PermanentAuthException(e);
            } catch (IOException | RuntimeException e) {
                // Token endpoint errors say whether they are retryable; credential parse failures are not.
                boolean transientFailure = e instanceof Retryable r ? r.isRetryable()
                        : e instanceof IOException io && isNetwork(io);
                if (transientFailure) {
                    throw (IOException) (e instanceof IOException ? e : new IOException(e));
                }
                throw new PermanentAuthException(e);
            }
        }

        private static boolean isNetwork(IOException e) {
            return e instanceof java.net.SocketException || e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.UnknownHostException || e instanceof javax.net.ssl.SSLException
                    || e.getMessage() != null && e.getMessage().startsWith("FCM ");
        }
    }
}
