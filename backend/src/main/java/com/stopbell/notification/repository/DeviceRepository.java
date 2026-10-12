package com.stopbell.notification.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.notification.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from Device device where device.id = :id")
    Optional<Device> findByIdForUpdate(Long id);

    Optional<Device> findByInstallationId(String installationId);

    boolean existsByInstallationId(String installationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from Device device where device.installationId = :installationId")
    Optional<Device> findByInstallationIdForUpdate(String installationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select device from Device device
            where device.user.id = :userId and device.enabled = true and device.currentPushTargetId is not null
            order by device.id
            """)
    List<Device> findEligibleByUserIdForUpdate(Long userId);

    boolean existsByCurrentPushTargetIdAndIdNot(String currentPushTargetId, Long id);
}
