package com.stopbell.boarding.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.boarding.entity.BoardingFingerprint;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

public record CreateBoardingSessionRequest(String clientRequestId, String candidateRef, String confirmedVehicleNumber,
        boolean prepareNotificationEnabled) {
    public CreateBoardingSessionRequest(String clientRequestId, String candidateRef, String confirmedVehicleNumber) {
        this(clientRequestId, candidateRef, confirmedVehicleNumber, true);
    }
    public CreateBoardingSessionRequest {
        try {
            clientRequestId = BoardingSession.validateUuid(clientRequestId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (candidateRef == null || candidateRef.isBlank() || candidateRef.length() > 255
                || confirmedVehicleNumber == null || confirmedVehicleNumber.isBlank()
                || confirmedVehicleNumber.length() > 255) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static CreateBoardingSessionRequest fromJson(
            @JsonProperty("clientRequestId") JsonNode clientRequestId,
            @JsonProperty("candidateRef") JsonNode candidateRef,
            @JsonProperty("confirmedVehicleNumber") JsonNode confirmedVehicleNumber,
            @JsonProperty("prepareNotificationEnabled") JsonNode prepareNotificationEnabled) {
        if (clientRequestId == null || !clientRequestId.isString() || candidateRef == null || !candidateRef.isString()
                || confirmedVehicleNumber == null || !confirmedVehicleNumber.isString()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (prepareNotificationEnabled != null && !prepareNotificationEnabled.isBoolean()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new CreateBoardingSessionRequest(clientRequestId.asString(), candidateRef.asString(),
                confirmedVehicleNumber.asString(), prepareNotificationEnabled == null || prepareNotificationEnabled.asBoolean());
    }

    public String fingerprint() {
        // Preserve retries of existing requests that predate the optional setting.
        return prepareNotificationEnabled ? BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber)
                : BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber, "false");
    }
}
