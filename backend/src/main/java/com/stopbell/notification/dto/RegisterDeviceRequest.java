package com.stopbell.notification.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.DevicePlatform;
import tools.jackson.databind.JsonNode;

public record RegisterDeviceRequest(
        String installationId,
        DevicePlatform platform,
        String pushRegistrationId,
        long registrationRevision,
        long expectedOwnershipGeneration,
        boolean transferOwnership
) {

    public RegisterDeviceRequest {
        try {
            installationId = Device.normalizeInstallationId(installationId);
            Device.validatePushTarget(pushRegistrationId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (platform == null || registrationRevision < 0 || expectedOwnershipGeneration < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static RegisterDeviceRequest fromJson(
            @JsonProperty("installationId") JsonNode installationId,
            @JsonProperty("platform") JsonNode platform,
            @JsonProperty("pushRegistrationId") JsonNode pushRegistrationId,
            @JsonProperty("registrationRevision") JsonNode registrationRevision,
            @JsonProperty("expectedOwnershipGeneration") JsonNode expectedOwnershipGeneration,
            @JsonProperty("transferOwnership") JsonNode transferOwnership
    ) {
        DevicePlatform parsedPlatform;
        try {
            parsedPlatform = DevicePlatform.valueOf(DeviceRequestFields.string(platform));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new RegisterDeviceRequest(
                DeviceRequestFields.string(installationId), parsedPlatform,
                DeviceRequestFields.string(pushRegistrationId), DeviceRequestFields.counter(registrationRevision),
                DeviceRequestFields.counter(expectedOwnershipGeneration), DeviceRequestFields.bool(transferOwnership)
        );
    }
}
