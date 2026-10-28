package com.stopbell.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.DevicePlatform;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DeviceApiIntegrationTest {

    private static final String HEADER = "X-Installation-Credential";
    private static final String CREDENTIAL = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String OTHER_CREDENTIAL = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    private static final LocalDateTime OLD_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DeviceRepository devices;
    @Autowired
    private UserRepository users;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("최초 등록은 201과 정규화한 최소 응답을 반환하고 자격증명 hash 및 UTC 시각만 저장한다")
    void register_new_installation() throws Exception {
        User owner = user();
        String installation = installation();
        String target = target();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        JsonNode response = register(owner, CREDENTIAL, payload(
                installation.toUpperCase(Locale.ROOT), "ANDROID", target, 0, 0, false
        ), 201);
        assertResponseFields(response, false);
        assertThat(response.get("installationId").asString()).isEqualTo(installation);
        assertThat(response.get("enabled").booleanValue()).isTrue();
        assertThat(response.get("ownershipGeneration").longValue()).isZero();
        Device stored = devices.findByInstallationId(installation).orElseThrow();
        assertThat(stored.getInstallationCredentialHash()).isEqualTo(hash(CREDENTIAL));
        assertThat(stored.getUser().getId()).isEqualTo(owner.getId());
        assertThat(stored.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(stored.getCurrentPushTargetId()).isEqualTo(target);
        assertThat(stored.getLastRegisteredAt()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
        assertThat(jdbc.queryForObject(
                "select max(cast(version as unsigned)) from flyway_schema_history where success = true", Integer.class
        )).isEqualTo(20);
    }

    @Test
    @DisplayName("FID rotation은 높은 revision만 적용하고 동일 상태 재전송은 시각을 유지하며 지연 및 충돌 요청을 거부한다")
    void rotate_target_and_enforce_revision() throws Exception {
        User owner = user();
        String installation = installation();
        String firstTarget = target();
        String secondTarget = target();
        Map<String, Object> first = payload(installation, "IOS", firstTarget, 1, 0, false);
        register(owner, CREDENTIAL, first, 201);
        ageTimestamps(installation);
        Map<String, Object> second = payload(installation, "IOS", secondTarget, 2, 0, false);
        register(owner, CREDENTIAL, second, 200);
        Snapshot rotated = snapshot(installation);
        assertThat(rotated.target()).isEqualTo(secondTarget);
        assertThat(rotated.lastRegistered()).isAfter(OLD_TIME);
        assertThat(rotated.updated()).isAfter(OLD_TIME);
        assertThat(rotated.generation()).isZero();

        register(owner, CREDENTIAL, second, 200);
        assertThat(snapshot(installation)).isEqualTo(rotated);
        error(registerResult(owner, CREDENTIAL, first), 409, "DEVICE_REGISTRATION_STALE");
        error(registerResult(owner, CREDENTIAL, payload(installation, "IOS", firstTarget, 2, 0, false)),
                409, "DEVICE_REVISION_CONFLICT");
        assertThat(snapshot(installation)).isEqualTo(rotated);

        ageTimestamps(installation);
        register(owner, CREDENTIAL, payload(installation, "IOS", secondTarget, 3, 0, false), 200);
        assertThat(snapshot(installation).lastRegistered()).isAfter(OLD_TIME);
    }

    @Test
    @DisplayName("disable은 target만 해제하고 멱등 시각과 다른 Device를 보존하며 오래된 등록은 복구할 수 없다")
    void disable_and_reactivate_only_current_device() throws Exception {
        User owner = user();
        String installation = installation();
        String unrelated = installation();
        String target = target();
        register(owner, CREDENTIAL, payload(installation, "IOS", target, 1, 0, false), 201);
        register(owner, CREDENTIAL, payload(unrelated, "IOS", target(), 1, 0, false), 201);
        ageTimestamps(installation);
        Snapshot before = snapshot(installation);
        Snapshot unrelatedBefore = snapshot(unrelated);
        error(disableResult(owner, CREDENTIAL, installation, 1, 0), 409, "DEVICE_REVISION_CONFLICT");
        JsonNode response = body(disableResult(owner, CREDENTIAL, installation, 2, 0), 200);
        assertResponseFields(response, false);
        Snapshot disabled = snapshot(installation);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.target()).isNull();
        assertThat(disabled.owner()).isEqualTo(before.owner());
        assertThat(disabled.lastRegistered()).isEqualTo(before.lastRegistered());
        assertThat(disabled.created()).isEqualTo(before.created());
        assertThat(disabled.updated()).isAfter(before.updated());

        body(disableResult(owner, CREDENTIAL, installation, 2, 0), 200);
        assertThat(snapshot(installation)).isEqualTo(disabled);
        error(disableResult(owner, CREDENTIAL, installation, 1, 0), 409, "DEVICE_REGISTRATION_STALE");
        error(registerResult(owner, CREDENTIAL, payload(installation, "IOS", target, 1, 0, false)),
                409, "DEVICE_REGISTRATION_STALE");
        error(registerResult(owner, CREDENTIAL, payload(installation, "IOS", target, 2, 0, false)),
                409, "DEVICE_REVISION_CONFLICT");
        assertThat(snapshot(installation)).isEqualTo(disabled);
        assertThat(snapshot(unrelated)).isEqualTo(unrelatedBefore);

        // Nullable UNIQUE releases the old target; a differently cased FID is independent.
        register(owner, CREDENTIAL, payload(installation(), "IOS", target, 0, 0, false), 201);
        register(owner, CREDENTIAL, payload(installation, "IOS", target.toUpperCase(Locale.ROOT), 3, 0, false), 200);
        assertThat(snapshot(installation).enabled()).isTrue();
        assertThat(snapshot(installation).generation()).isZero();
    }

    @Test
    @DisplayName("A→B→A 이전은 같은 row의 세대만 증가시키고 응답 유실 재전송 및 과거 owner 요청을 안전하게 처리한다")
    void transfer_ownership_and_reject_old_generations() throws Exception {
        User first = user();
        User second = user();
        String installation = installation();
        String target = target();
        register(first, CREDENTIAL, payload(installation, "IOS", target, 3, 0, false), 201);
        Snapshot original = snapshot(installation);
        Map<String, Object> transfer = payload(installation, "IOS", target, 4, 0, true);
        error(registerResult(second, CREDENTIAL, payload(installation, "IOS", target, 4, 0, false)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(second, CREDENTIAL, payload(installation, "IOS", target, 3, 0, true)),
                409, "DEVICE_REVISION_CONFLICT");
        register(second, CREDENTIAL, transfer, 200);
        Snapshot transferred = snapshot(installation);
        assertThat(transferred.id()).isEqualTo(original.id());
        assertThat(transferred.owner()).isEqualTo(second.getId());
        assertThat(transferred.generation()).isEqualTo(1);
        assertThat(transferred.created()).isEqualTo(original.created());
        register(second, CREDENTIAL, transfer, 200);
        assertThat(snapshot(installation)).isEqualTo(transferred);

        error(registerResult(first, CREDENTIAL, payload(installation, "IOS", target, Long.MAX_VALUE, 0, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(disableResult(first, CREDENTIAL, installation, Long.MAX_VALUE, 0), 404, "DEVICE_NOT_FOUND");
        error(disableResult(second, CREDENTIAL, installation, 5, 0), 409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(second, CREDENTIAL, payload(installation, "IOS", target, 5, 0, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(second, CREDENTIAL, payload(installation, "IOS", target(), 4, 0, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(second, CREDENTIAL, payload(installation, "IOS", target, 4, 1, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        assertThat(snapshot(installation)).isEqualTo(transferred);

        register(first, CREDENTIAL, payload(installation, "IOS", target, 5, 1, true), 200);
        Snapshot returned = snapshot(installation);
        assertThat(returned.id()).isEqualTo(original.id());
        assertThat(returned.generation()).isEqualTo(2);
        error(registerResult(first, CREDENTIAL, payload(installation, "IOS", target, Long.MAX_VALUE, 0, false)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(disableResult(first, CREDENTIAL, installation, Long.MAX_VALUE, 0), 409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(second, CREDENTIAL, transfer), 409, "DEVICE_OWNERSHIP_CONFLICT");
        assertThat(snapshot(installation)).isEqualTo(returned);

        body(disableResult(first, CREDENTIAL, installation, 6, 2), 200);
        register(second, CREDENTIAL, payload(installation, "IOS", target, 7, 2, true), 200);
        assertThat(snapshot(installation).generation()).isEqualTo(3);
        assertThat(snapshot(installation).enabled()).isTrue();
    }

    @Test
    @DisplayName("자격증명과 불변 platform 및 owner와 세대를 revision보다 먼저 검증하고 overflow와 FID 탈취를 거부한다")
    void enforce_credentials_platform_authority_and_limits() throws Exception {
        User owner = user();
        User other = user();
        String installation = installation();
        String target = target();
        register(owner, CREDENTIAL, payload(installation, "IOS", target, 1, 0, false), 201);
        Snapshot original = snapshot(installation);
        error(registerResult(other, OTHER_CREDENTIAL, payload(installation, "ANDROID", target, 0, 8, true)),
                403, "DEVICE_CREDENTIAL_INVALID");
        error(disableResult(other, OTHER_CREDENTIAL, installation, 0, 8), 403, "DEVICE_CREDENTIAL_INVALID");
        error(registerResult(owner, CREDENTIAL, payload(installation, "ANDROID", target, 2, 0, false)),
                409, "DEVICE_PLATFORM_CONFLICT");
        error(registerResult(owner, CREDENTIAL, payload(installation, "IOS", target, 0, 1, false)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(owner, CREDENTIAL, payload(installation, "IOS", target, 2, 0, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(other, CREDENTIAL, payload(installation(), "IOS", target, 1, 0, false)),
                409, "DEVICE_PUSH_TARGET_CONFLICT");
        error(registerResult(other, CREDENTIAL, payload(installation(), "IOS", target(), 1, 1, false)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        error(registerResult(other, CREDENTIAL, payload(installation(), "IOS", target(), 1, 0, true)),
                409, "DEVICE_OWNERSHIP_CONFLICT");
        assertThat(snapshot(installation)).isEqualTo(original);

        jdbc.update("update devices set ownership_generation = ? where installation_id = ?", Long.MAX_VALUE, installation);
        Snapshot atGenerationLimit = snapshot(installation);
        error(registerResult(other, CREDENTIAL, payload(installation, "IOS", target, 2, Long.MAX_VALUE, true)),
                400, "INVALID_REQUEST");
        assertThat(snapshot(installation)).isEqualTo(atGenerationLimit);
        register(owner, CREDENTIAL, payload(installation, "IOS", target, Long.MAX_VALUE, Long.MAX_VALUE, false), 200);
        Snapshot atRevisionLimit = snapshot(installation);
        register(owner, CREDENTIAL, payload(installation, "IOS", target, Long.MAX_VALUE, Long.MAX_VALUE, false), 200);
        assertThat(snapshot(installation)).isEqualTo(atRevisionLimit);
        error(disableResult(owner, CREDENTIAL, installation, Long.MAX_VALUE, Long.MAX_VALUE),
                409, "DEVICE_REVISION_CONFLICT");
    }

    @Test
    @DisplayName("설치본 조회는 같은 자격증명의 다른 User에게도 최소 상태를 반환하고 row와 시각을 변경하지 않는다")
    void look_up_state_without_mutation_or_owner_disclosure() throws Exception {
        User owner = user();
        User other = user();
        String installation = installation();
        register(owner, CREDENTIAL, payload(installation, "IOS", target(), 1, 0, false), 201);
        Snapshot before = snapshot(installation);
        JsonNode ownState = body(stateResult(owner, CREDENTIAL, installation.toUpperCase(Locale.ROOT)), 200);
        assertResponseFields(ownState, true);
        assertThat(ownState.get("ownedByCurrentUser").booleanValue()).isTrue();
        JsonNode otherState = body(stateResult(other, CREDENTIAL, installation), 200);
        assertResponseFields(otherState, true);
        assertThat(otherState.get("ownedByCurrentUser").booleanValue()).isFalse();
        assertThat(otherState.get("installationId").asString()).isEqualTo(installation);
        error(stateResult(other, OTHER_CREDENTIAL, installation), 403, "DEVICE_CREDENTIAL_INVALID");
        error(stateResult(owner, CREDENTIAL, installation()), 404, "DEVICE_NOT_FOUND");
        error(disableResult(owner, CREDENTIAL, installation(), 1, 0), 404, "DEVICE_NOT_FOUND");
        assertThat(snapshot(installation)).isEqualTo(before);
    }

    @Test
    @DisplayName("Device 요청에서 필수 필드와 엄격한 JSON 타입 및 UUID와 FID와 canonical credential 형식을 검증한다")
    void reject_invalid_input_without_coercion() throws Exception {
        User owner = user();
        String installation = installation();
        Map<String, Object> valid = payload(installation, "IOS", target(), 1, 0, false);
        List<Map<String, Object>> invalidRequests = new ArrayList<>();
        for (String field : valid.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            invalidRequests.add(missing);
            invalidRequests.add(changed(valid, field, null));
        }
        for (String field : List.of("registrationRevision", "expectedOwnershipGeneration")) {
            for (Object value : List.of("1", true, 1.0, 1.5, -1, new java.math.BigInteger("9223372036854775808"))) {
                invalidRequests.add(changed(valid, field, value));
            }
        }
        for (Object value : List.of("false", 0, List.of(false))) {
            invalidRequests.add(changed(valid, "transferOwnership", value));
        }
        for (String value : List.of(" " + installation, installation + " ", installation.replace("-", ""),
                "a1234567-1234-1123-8123-123456789abc", "a1234567-1234-4123-7123-123456789abc")) {
            invalidRequests.add(changed(valid, "installationId", value));
            error(stateResult(owner, CREDENTIAL, value), 400, "INVALID_REQUEST");
        }
        for (Object value : List.of("ios", "WEB", 0)) {
            invalidRequests.add(changed(valid, "platform", value));
        }
        for (Object value : List.of("", " ", "a b", "a\tb", "a\nb", "a\u00a0b", "a".repeat(256), 123)) {
            invalidRequests.add(changed(valid, "pushRegistrationId", value));
        }
        for (Map<String, Object> invalid : invalidRequests) {
            error(registerResult(owner, CREDENTIAL, invalid), 400, "INVALID_REQUEST");
        }
        for (String credential : List.of("", "short", CREDENTIAL + "=", CREDENTIAL + " ",
                "+" + CREDENTIAL.substring(1), "/" + CREDENTIAL.substring(1), "A".repeat(42) + "B")) {
            error(registerResult(owner, credential, valid), 400, "INVALID_REQUEST");
            error(stateResult(owner, credential, installation), 400, "INVALID_REQUEST");
            error(disableResult(owner, credential, installation, 2, 0), 400, "INVALID_REQUEST");
        }
        error(registerResult(owner, null, valid), 400, "INVALID_REQUEST");
        error(stateResult(owner, null, installation), 400, "INVALID_REQUEST");
        error(disableResult(owner, null, installation, 2, 0), 400, "INVALID_REQUEST");
        Map<String, Object> disable = disablePayload(installation, 2, 0);
        for (String field : disable.keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(disable);
            missing.remove(field);
            error(perform(post("/api/v1/devices/disable"), owner, CREDENTIAL, missing), 400, "INVALID_REQUEST");
            error(perform(post("/api/v1/devices/disable"), owner, CREDENTIAL, changed(disable, field, null)),
                    400, "INVALID_REQUEST");
        }
        for (String field : List.of("registrationRevision", "expectedOwnershipGeneration")) {
            for (Object value : List.of("1", false, 2.0, -1, new java.math.BigInteger("9223372036854775808"))) {
                error(perform(post("/api/v1/devices/disable"), owner, CREDENTIAL, changed(disable, field, value)),
                        400, "INVALID_REQUEST");
            }
        }
        assertThat(devices.findByInstallationId(installation)).isEmpty();
        // Upper boundary and short opaque FIDs remain valid; no prefix or observed length assumption.
        register(owner, CREDENTIAL, payload(installation, "IOS", "x", Long.MAX_VALUE, 0, false), 201);
    }

    @Test
    @DisplayName("세 Device Endpoint 모두 JWT 누락과 잘못된 JWT를 기존 Security의 401로 거부한다")
    void require_jwt_for_all_endpoints() throws Exception {
        for (boolean invalidToken : List.of(false, true)) {
            for (MockHttpServletRequestBuilder request : List.of(
                    post("/api/v1/devices").content("{}"),
                    post("/api/v1/devices/disable").content("{}"),
                    get("/api/v1/devices/{installationId}", installation())
            )) {
                request.contentType("application/json").header(HEADER, CREDENTIAL);
                if (invalidToken) {
                    request.header("Authorization", "Bearer invalid-token");
                }
                mvc.perform(request).andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    @DisplayName("동시 최초 등록의 installation UNIQUE 충돌은 rollback 뒤 같은 요청만 멱등 성공하고 다른 자격증명은 403이다")
    void resolve_first_registration_races(CapturedOutput output) throws Exception {
        User owner = user();
        for (String credential : List.of(CREDENTIAL, OTHER_CREDENTIAL)) {
            String installation = installation();
            String target = target();
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                CountDownLatch inserted = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Future<?> winner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    devices.saveAndFlush(new Device(
                            owner, installation, hash(CREDENTIAL), DevicePlatform.IOS, target, 1, OLD_TIME
                    ));
                    inserted.countDown();
                    await(release);
                    return null;
                }));
                Future<MvcResult> contender = null;
                try {
                    await(inserted);
                    contender = executor.submit(() -> registerResult(owner, credential,
                            payload(installation, "IOS", target, 1, 0, false)));
                    waitForDatabaseWaiters(1);
                } finally {
                    release.countDown();
                }
                winner.get(10, TimeUnit.SECONDS);
                MvcResult result = contender.get(10, TimeUnit.SECONDS);
                if (credential.equals(CREDENTIAL)) {
                    body(result, 200);
                } else {
                    error(result, 403, "DEVICE_CREDENTIAL_INVALID");
                }
                Snapshot persisted = snapshot(installation);
                assertThat(persisted.revision()).isEqualTo(1);
                assertThat(persisted.generation()).isZero();
                assertThat(persisted.lastRegistered()).isEqualTo(OLD_TIME);
                assertThat(persisted.updated()).isEqualTo(persisted.created());
                assertThat(jdbc.queryForObject("select count(*) from devices where installation_id = ?", Integer.class,
                        installation)).isEqualTo(1);
            }
            assertThat(output.getAll()).doesNotContain(installation, target, CREDENTIAL, OTHER_CREDENTIAL, hash(CREDENTIAL));
        }
    }

    @Test
    @DisplayName("동시 FID UNIQUE 충돌은 신규 등록과 owner 이전을 모두 rollback하고 기존 target과 owner를 보호한다")
    void roll_back_target_uniqueness_races(CapturedOutput output) throws Exception {
        User owner = user();
        User newOwner = user();
        for (boolean transfer : List.of(false, true)) {
            String installation = installation();
            String occupiedInstallation = installation();
            String target = target();
            Snapshot original = null;
            if (transfer) {
                register(owner, CREDENTIAL, payload(installation, "IOS", target(), 1, 0, false), 201);
                original = snapshot(installation);
            }
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                CountDownLatch inserted = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Future<?> winner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    devices.saveAndFlush(new Device(
                            owner, occupiedInstallation, hash(CREDENTIAL), DevicePlatform.IOS, target, 1, OLD_TIME
                    ));
                    inserted.countDown();
                    await(release);
                    return null;
                }));
                Future<MvcResult> contender = null;
                try {
                    await(inserted);
                    contender = executor.submit(() -> registerResult(newOwner, CREDENTIAL,
                            payload(installation, "IOS", target, 2, 0, transfer)));
                    waitForDatabaseWaiters(1);
                } finally {
                    release.countDown();
                }
                winner.get(10, TimeUnit.SECONDS);
                error(contender.get(10, TimeUnit.SECONDS), 409, "DEVICE_PUSH_TARGET_CONFLICT");
            }
            if (transfer) {
                assertThat(snapshot(installation)).isEqualTo(original);
            } else {
                assertThat(devices.findByInstallationId(installation)).isEmpty();
            }
            assertThat(snapshot(occupiedInstallation).target()).isEqualTo(target);
            assertThat(output.getAll()).doesNotContain(installation, occupiedInstallation, target, CREDENTIAL);
        }
    }

    @Test
    @DisplayName("동시 재등록과 disable은 실제 MySQL write lock에서 직렬화되고 높은 revision의 disabled 상태를 보존한다")
    void serialize_existing_device_mutations() throws Exception {
        User owner = user();
        String installation = installation();
        register(owner, CREDENTIAL, payload(installation, "IOS", target(), 1, 0, false), 201);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                devices.findByInstallationIdForUpdate(installation).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));
            Future<MvcResult> register = null;
            Future<MvcResult> disable = null;
            try {
                await(locked);
                register = executor.submit(() -> registerResult(owner, CREDENTIAL,
                        payload(installation, "IOS", target(), 2, 0, false)));
                disable = executor.submit(() -> disableResult(owner, CREDENTIAL, installation, 3, 0));
                waitForDatabaseWaiters(2);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            body(disable.get(10, TimeUnit.SECONDS), 200);
            MvcResult registration = register.get(10, TimeUnit.SECONDS);
            if (registration.getResponse().getStatus() == 409) {
                error(registration, 409, "DEVICE_REGISTRATION_STALE");
            } else {
                body(registration, 200);
            }
        }
        Snapshot finalState = snapshot(installation);
        assertThat(finalState.revision()).isEqualTo(3);
        assertThat(finalState.enabled()).isFalse();
        assertThat(finalState.target()).isNull();
        assertThat(finalState.generation()).isZero();
    }

    @Test
    @DisplayName("알려지지 않은 DB 무결성 오류는 target conflict로 위장하지 않고 원문 로그와 오류 상세 없이 500을 반환한다")
    void report_unclassified_database_failure_safely(CapturedOutput output) throws Exception {
        User owner = user();
        String installation = installation();
        String target = target();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch deleted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                users.deleteById(owner.getId());
                users.flush();
                deleted.countDown();
                await(release);
                return null;
            }));
            Future<MvcResult> registration = null;
            try {
                await(deleted);
                registration = executor.submit(() -> registerResult(owner, CREDENTIAL,
                        payload(installation, "IOS", target, 1, 0, false)));
                waitForDatabaseWaiters(1);
            } finally {
                release.countDown();
            }
            deletion.get(10, TimeUnit.SECONDS);
            MvcResult response = registration.get(10, TimeUnit.SECONDS);
            assertThat(response.getResponse().getStatus()).isEqualTo(500);
            assertThat(response.getResponse().getContentAsString()).isEmpty();
        }
        assertThat(devices.findByInstallationId(installation)).isEmpty();
        assertThat(output.getAll()).contains("Device persistence failed: DataIntegrityViolationException")
                .doesNotContain(installation, target, CREDENTIAL, hash(CREDENTIAL), "fk_devices_user_id");
    }

    private JsonNode register(User user, String credential, Map<String, Object> payload, int expectedStatus) throws Exception {
        return body(registerResult(user, credential, payload), expectedStatus);
    }

    private MvcResult registerResult(User user, String credential, Map<String, Object> payload) throws Exception {
        return perform(post("/api/v1/devices"), user, credential, payload);
    }

    private MvcResult disableResult(User user, String credential, String installation, long revision, long generation)
            throws Exception {
        return perform(post("/api/v1/devices/disable"), user, credential, disablePayload(installation, revision, generation));
    }

    private MvcResult stateResult(User user, String credential, String installation) throws Exception {
        return perform(get("/api/v1/devices/{installationId}", installation), user, credential, null);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, User user, String credential, Object payload)
            throws Exception {
        request.header("Authorization", "Bearer " + tokens.createAccessToken(user.getId()));
        if (credential != null) {
            request.header(HEADER, credential);
        }
        if (payload != null) {
            request.contentType("application/json").content(JSON.writeValueAsString(payload));
        }
        return mvc.perform(request).andReturn();
    }

    private static JsonNode body(MvcResult result, int expectedStatus) {
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static void error(MvcResult result, int status, String code) {
        JsonNode response = body(result, status);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.get("code").asString()).isEqualTo(code);
        assertThat(response.get("message").asString())
                .isEqualTo(com.stopbell.common.error.ErrorCode.valueOf(code).getMessage());
    }

    private static void assertResponseFields(JsonNode response, boolean lookup) {
        List<String> fields = new ArrayList<>();
        response.propertyNames().forEach(fields::add);
        List<String> expected = new ArrayList<>(List.of(
                "id", "installationId", "enabled", "registrationRevision", "ownershipGeneration"
        ));
        if (lookup) {
            expected.add("ownedByCurrentUser");
        }
        assertThat(fields).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Map<String, Object> payload(String installation, String platform, String target,
            long revision, long generation, boolean transfer) {
        Map<String, Object> payload = disablePayload(installation, revision, generation);
        payload.put("platform", platform);
        payload.put("pushRegistrationId", target);
        payload.put("transferOwnership", transfer);
        return payload;
    }

    private static Map<String, Object> disablePayload(String installation, long revision, long generation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installationId", installation);
        payload.put("registrationRevision", revision);
        payload.put("expectedOwnershipGeneration", generation);
        return payload;
    }

    private static Map<String, Object> changed(Map<String, Object> original, String field, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>(original);
        payload.put(field, value);
        return payload;
    }

    private User user() {
        return users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
    }

    private static String installation() {
        return UUID.randomUUID().toString();
    }

    private static String target() {
        return "fid-" + UUID.randomUUID();
    }

    private static String hash(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Base64.getUrlDecoder().decode(credential)
            ));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private void ageTimestamps(String installation) {
        jdbc.update("update devices set last_registered_at = ?, updated_at = ? where installation_id = ?",
                OLD_TIME, OLD_TIME, installation);
    }

    private Snapshot snapshot(String installation) {
        return jdbc.queryForObject("select * from devices where installation_id = ?", (row, number) -> new Snapshot(
                row.getLong("id"), row.getLong("user_id"), row.getString("current_push_target_id"),
                row.getLong("registration_revision"), row.getLong("ownership_generation"), row.getBoolean("enabled"),
                row.getTimestamp("last_registered_at").toLocalDateTime(), row.getTimestamp("created_at").toLocalDateTime(),
                row.getTimestamp("updated_at").toLocalDateTime()
        ), installation);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void waitForDatabaseWaiters(int minimum) throws InterruptedException {
        // Inspect real InnoDB waits, rather than assuming a sleep proved that requests collided.
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Integer waiters = admin.queryForObject("select count(*) from performance_schema.data_lock_waits", Integer.class);
            if (waiters >= minimum) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected concurrent requests waiting on actual InnoDB locks");
    }

    private record Snapshot(long id, long owner, String target, long revision, long generation, boolean enabled,
            LocalDateTime lastRegistered, LocalDateTime created, LocalDateTime updated) {
    }
}
