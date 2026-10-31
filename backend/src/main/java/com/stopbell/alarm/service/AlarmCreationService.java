package com.stopbell.alarm.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.regex.Pattern;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.entity.AlarmCreationRequest;
import com.stopbell.alarm.repository.AlarmCreationRequestRepository;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlarmCreationService {
    private static final Pattern UUID_V4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private final AlarmService alarms;
    private final AlarmCreationRequestRepository requests;
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AlarmCreationService(AlarmService alarms, AlarmCreationRequestRepository requests,
            UserRepository users, PlatformTransactionManager manager, @Qualifier("transitMetadataClock") Clock clock) {
        this.alarms = alarms;
        this.requests = requests;
        this.users = users;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AlarmResponse create(Long userId, CreateAlarmRequest request) {
        if (request == null || request.targetStopOccurrenceId() == null || request.targetStopOccurrenceId() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (request.clientRequestId() == null) {
            return alarms.create(userId, request);
        }
        validateId(request.clientRequestId());
        byte[] fingerprint = fingerprint(request);
        try {
            return transaction.execute(status -> {
                // Serialize same-user starts before metadata validation; UNIQUE remains the final boundary.
                var user = users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
                var existing = requests.findForUpdate(userId, request.clientRequestId());
                if (existing.isPresent()) {
                    return resolve(userId, existing.get(), fingerprint);
                }
                AlarmResponse result = alarms.create(userId, request);
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                requests.saveAndFlush(new AlarmCreationRequest(user, request.clientRequestId(), fingerprint, result.id(), now));
                return result;
            });
        } catch (DataIntegrityViolationException exception) {
            if (!isRequestDuplicate(exception)) {
                throw exception;
            }
            return transaction.execute(status -> resolve(userId,
                    requests.findForUpdate(userId, request.clientRequestId()).orElseThrow(() -> exception), fingerprint));
        }
    }

    public AlarmResponse find(Long userId, String requestId) {
        validateId(requestId);
        return transaction.execute(status -> resolve(userId, requests.findForUpdate(userId, requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_CREATION_REQUEST_NOT_FOUND)), null));
    }

    private AlarmResponse resolve(Long userId, AlarmCreationRequest request, byte[] fingerprint) {
        if (fingerprint != null && !Arrays.equals(request.getRequestFingerprint(), fingerprint)) {
            throw new ApiException(ErrorCode.ALARM_CREATION_REQUEST_CONFLICT);
        }
        try {
            return alarms.findById(userId, request.getAlarmId());
        } catch (ApiException exception) {
            if (exception.getErrorCode() == ErrorCode.ALARM_NOT_FOUND) {
                throw new ApiException(ErrorCode.ALARM_CREATION_RESULT_GONE);
            }
            throw exception;
        }
    }

    private static void validateId(String id) {
        if (id == null || !UUID_V4.matcher(id).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static byte[] fingerprint(CreateAlarmRequest request) {
        String canonical = "v1:" + request.targetStopOccurrenceId() + ":" + (request.notifyOneStopBefore() ? 1 : 0)
                + ":" + (request.notifyOneStopAfter() ? 1 : 0);
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean isRequestDuplicate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException().getErrorCode() == 1062) {
                String name = violation.getConstraintName();
                return "uk_alarm_creation_requests_user_request".equals(name)
                        || (name != null && name.endsWith(".uk_alarm_creation_requests_user_request"));
            }
        }
        return false;
    }
}
