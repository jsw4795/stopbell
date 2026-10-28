package com.stopbell.boarding.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.boarding.entity.BoardingFingerprint;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

/** Legacy ON/OFF bodies are accepted only to reconcile an already stored request. */
public record CreateBoardingSessionRequest(String clientRequestId, String candidateRef, String confirmedVehicleNumber,
        Integer notificationOffsetStops, Boolean legacyPrepareNotificationEnabled) {
    public CreateBoardingSessionRequest(String id, String ref, String number) {
        this(id, ref, number, null, null);
    }
    public CreateBoardingSessionRequest(String id, String ref, String number, int offset) {
        this(id, ref, number, offset, null);
    }
    public CreateBoardingSessionRequest(String id, String ref, String number, boolean legacyEnabled) {
        this(id, ref, number, null, legacyEnabled);
    }
    public CreateBoardingSessionRequest {
        try {
            clientRequestId = BoardingSession.validateUuid(clientRequestId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (candidateRef == null || candidateRef.isBlank() || candidateRef.length() > 255
                || notificationOffsetStops != null && notificationOffsetStops != 1 && notificationOffsetStops != 2
                || (confirmedVehicleNumber != null && (confirmedVehicleNumber.isBlank() || confirmedVehicleNumber.length() > 255))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static CreateBoardingSessionRequest fromJson(
            @JsonProperty("clientRequestId") JsonNode id,
            @JsonProperty("candidateRef") JsonNode ref,
            @JsonProperty("confirmedVehicleNumber") JsonNode number,
            @JsonProperty("notificationOffsetStops") JsonNode offset,
            @JsonProperty("prepareNotificationEnabled") JsonNode legacy) {
        if (id == null || !id.isString() || ref == null || !ref.isString()
                || number != null && !number.isString()
                || offset != null && (!offset.isIntegralNumber() || !offset.canConvertToInt()
                    || offset.asInt() != 1 && offset.asInt() != 2)
                || legacy != null && (!legacy.isBoolean() || offset != null)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new CreateBoardingSessionRequest(id.asString(), ref.asString(), number == null ? null : number.asString(),
                offset == null ? null : Integer.valueOf(offset.asInt()), legacy == null ? null : legacy.asBoolean());
    }

    public int effectiveNotificationOffsetStops() { return notificationOffsetStops == null ? 2 : notificationOffsetStops; }

    public String legacyFingerprint() {
        return Boolean.FALSE.equals(legacyPrepareNotificationEnabled)
                ? BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber, "false")
                : BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber);
    }

    public String fingerprint() {
        return legacyPrepareNotificationEnabled != null ? legacyFingerprint()
                : BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber,
                    "PRE_DEPARTURE_V2", Integer.toString(effectiveNotificationOffsetStops()));
    }
}
