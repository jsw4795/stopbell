package com.stopbell.notification.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.entity.Device;
import tools.jackson.databind.JsonNode;

public record DisableDeviceRequest(
        String installationId,
        long registrationRevision,
        long expectedOwnershipGeneration
) {

    public DisableDeviceRequest {
        try {
            installationId = Device.normalizeInstallationId(installationId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (registrationRevision < 0 || expectedOwnershipGeneration < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static DisableDeviceRequest fromJson(
            @JsonProperty("installationId") JsonNode installationId,
            @JsonProperty("registrationRevision") JsonNode registrationRevision,
            @JsonProperty("expectedOwnershipGeneration") JsonNode expectedOwnershipGeneration
    ) {
        return new DisableDeviceRequest(
                DeviceRequestFields.string(installationId), DeviceRequestFields.counter(registrationRevision),
                DeviceRequestFields.counter(expectedOwnershipGeneration)
        );
    }
}
