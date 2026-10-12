package com.stopbell.notification.repository;

import java.util.Optional;

import com.stopbell.notification.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByInstallationId(String installationId);

    boolean existsByInstallationId(String installationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from Device device where device.installationId = :installationId")
    Optional<Device> findByInstallationIdForUpdate(String installationId);

    boolean existsByCurrentPushTargetIdAndIdNot(String currentPushTargetId, Long id);
}
