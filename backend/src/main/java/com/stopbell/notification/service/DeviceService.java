package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.dto.DeviceResponse;
import com.stopbell.notification.dto.DeviceStateResponse;
import com.stopbell.notification.dto.DisableDeviceRequest;
import com.stopbell.notification.dto.RegisterDeviceRequest;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceService {

    private static final String INSTALLATION_UNIQUE = "uk_devices_installation_id";
    private static final String PUSH_TARGET_UNIQUE = "uk_devices_current_push_target_id";

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public DeviceService(
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("jwtClock") Clock clock
    ) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        writeTransaction = new TransactionTemplate(transactionManager);
        writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction.setReadOnly(true);
    }

    public RegistrationResult register(Long userId, String credential, RegisterDeviceRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String credentialHash = InstallationCredential.hash(credential);
        try {
            return writeTransaction.execute(status -> registerInTransaction(userId, credentialHash, request, null));
        } catch (DataIntegrityViolationException exception) {
            String constraint = deviceUniqueConstraint(exception);
            if (constraint == null) {
                throw exception;
            }
            // execute() has fully rolled back and closed the failed persistence context.
            // Recheck the original request once, with a new current locking read.
            try {
                return writeTransaction.execute(status -> registerInTransaction(
                        userId, credentialHash, request, constraint
                ));
            } catch (DataIntegrityViolationException retryException) {
                if (PUSH_TARGET_UNIQUE.equals(deviceUniqueConstraint(retryException))) {
                    throw new ApiException(ErrorCode.DEVICE_PUSH_TARGET_CONFLICT);
                }
                throw retryException;
            }
        }
    }

    private RegistrationResult registerInTransaction(
            Long userId, String credentialHash, RegisterDeviceRequest request, String conflictingConstraint
    ) {
        User user = authenticatedUser(userId);
        // Do not lock a missing UUID range: competing first inserts use the UNIQUE boundary.
        // The existence query loads no Entity; existing state is loaded only by the locking read.
        Optional<Device> existing = conflictingConstraint != null
                || deviceRepository.existsByInstallationId(request.installationId())
                ? deviceRepository.findByInstallationIdForUpdate(request.installationId()) : Optional.empty();
        if (existing.isEmpty()) {
            if (PUSH_TARGET_UNIQUE.equals(conflictingConstraint)) {
                throw new ApiException(ErrorCode.DEVICE_PUSH_TARGET_CONFLICT);
            }
            if (conflictingConstraint != null) {
                throw new DataRetrievalFailureException("Conflicting Device installation was not found");
            }
            if (request.expectedOwnershipGeneration() != 0 || request.transferOwnership()) {
                throw new ApiException(ErrorCode.DEVICE_OWNERSHIP_CONFLICT);
            }
            Device device = deviceRepository.saveAndFlush(new Device(
                    user, request.installationId(), credentialHash, request.platform(),
                    request.pushRegistrationId(), request.registrationRevision(), utcNow()
            ));
            return new RegistrationResult(DeviceResponse.from(device), true);
        }

        Device device = existing.orElseThrow();
        InstallationCredential.verify(device.getInstallationCredentialHash(), credentialHash);
        if (device.getPlatform() != request.platform()) {
            throw new ApiException(ErrorCode.DEVICE_PLATFORM_CONFLICT);
        }
        boolean sameOwner = device.getUser().getId().equals(userId);
        boolean sameResult = sameOwner && device.isEnabled()
                && request.pushRegistrationId().equals(device.getCurrentPushTargetId());
        boolean currentGeneration = request.expectedOwnershipGeneration() == device.getOwnershipGeneration();
        boolean lostTransferResponse = request.transferOwnership() && device.getOwnershipGeneration() > 0
                && request.expectedOwnershipGeneration() == device.getOwnershipGeneration() - 1;
        if (request.registrationRevision() == device.getRegistrationRevision() && sameResult
                && ((!request.transferOwnership() && currentGeneration) || lostTransferResponse)) {
            return new RegistrationResult(DeviceResponse.from(device), false);
        }
        if (!currentGeneration || (sameOwner ? request.transferOwnership() : !request.transferOwnership())) {
            throw new ApiException(ErrorCode.DEVICE_OWNERSHIP_CONFLICT);
        }
        checkRevision(device, request.registrationRevision());
        if (!sameOwner && device.getOwnershipGeneration() == Long.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (deviceRepository.existsByCurrentPushTargetIdAndIdNot(request.pushRegistrationId(), device.getId())) {
            throw new ApiException(ErrorCode.DEVICE_PUSH_TARGET_CONFLICT);
        }
        if (sameOwner) {
            device.register(request.pushRegistrationId(), request.registrationRevision(), utcNow());
        } else {
            device.transferOwnership(user, request.pushRegistrationId(), request.registrationRevision(), utcNow());
        }
        deviceRepository.flush();
        return new RegistrationResult(DeviceResponse.from(device), false);
    }

    public DeviceResponse disable(Long userId, String credential, DisableDeviceRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String credentialHash = InstallationCredential.hash(credential);
        return writeTransaction.execute(status -> {
            authenticatedUser(userId);
            Device device = deviceRepository.findByInstallationIdForUpdate(request.installationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_NOT_FOUND));
            InstallationCredential.verify(device.getInstallationCredentialHash(), credentialHash);
            if (!device.getUser().getId().equals(userId)) {
                throw new ApiException(ErrorCode.DEVICE_NOT_FOUND);
            }
            if (request.expectedOwnershipGeneration() != device.getOwnershipGeneration()) {
                throw new ApiException(ErrorCode.DEVICE_OWNERSHIP_CONFLICT);
            }
            if (request.registrationRevision() == device.getRegistrationRevision()
                    && !device.isEnabled() && device.getCurrentPushTargetId() == null) {
                return DeviceResponse.from(device);
            }
            checkRevision(device, request.registrationRevision());
            device.disable(request.registrationRevision());
            deviceRepository.flush();
            return DeviceResponse.from(device);
        });
    }

    public DeviceStateResponse findState(Long userId, String credential, String installationId) {
        String normalizedId;
        try {
            normalizedId = Device.normalizeInstallationId(installationId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String credentialHash = InstallationCredential.hash(credential);
        return readTransaction.execute(status -> {
            authenticatedUser(userId);
            Device device = deviceRepository.findByInstallationId(normalizedId)
                    .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_NOT_FOUND));
            InstallationCredential.verify(device.getInstallationCredentialHash(), credentialHash);
            return DeviceStateResponse.from(device, userId);
        });
    }

    private User authenticatedUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void checkRevision(Device device, long revision) {
        if (revision < device.getRegistrationRevision()) {
            throw new ApiException(ErrorCode.DEVICE_REGISTRATION_STALE);
        }
        if (revision == device.getRegistrationRevision()) {
            throw new ApiException(ErrorCode.DEVICE_REVISION_CONFLICT);
        }
    }

    private static String deviceUniqueConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getSQLException().getErrorCode() == 1062) {
                String name = violation.getConstraintName();
                for (String known : new String[] { INSTALLATION_UNIQUE, PUSH_TARGET_UNIQUE }) {
                    if (known.equals(name) || (name != null && name.endsWith("." + known))) {
                        return known;
                    }
                }
            }
        }
        return null;
    }

    public record RegistrationResult(DeviceResponse response, boolean created) {
    }
}
