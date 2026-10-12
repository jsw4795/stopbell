package com.stopbell.notification.dto;

import com.stopbell.notification.entity.Device;

public record DeviceStateResponse(
        Long id,
        String installationId,
        boolean enabled,
        long registrationRevision,
        long ownershipGeneration,
        boolean ownedByCurrentUser
) {

    public static DeviceStateResponse from(Device device, Long userId) {
        return new DeviceStateResponse(
                device.getId(), device.getInstallationId(), device.isEnabled(),
                device.getRegistrationRevision(), device.getOwnershipGeneration(),
                device.getUser().getId().equals(userId)
        );
    }
}
