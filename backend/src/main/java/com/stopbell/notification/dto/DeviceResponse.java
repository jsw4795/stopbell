package com.stopbell.notification.dto;

import com.stopbell.notification.entity.Device;

public record DeviceResponse(
        Long id,
        String installationId,
        boolean enabled,
        long registrationRevision,
        long ownershipGeneration
) {

    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getId(), device.getInstallationId(), device.isEnabled(),
                device.getRegistrationRevision(), device.getOwnershipGeneration()
        );
    }
}
