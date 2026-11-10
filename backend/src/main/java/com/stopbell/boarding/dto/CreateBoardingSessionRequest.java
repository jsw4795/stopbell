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
        Integer notificationOffsetStops, Boolean legacyPrepareNotificationEnabled, Long routeId,
        Long boardingStopOccurrenceId, Long targetStopOccurrenceId) {
    public CreateBoardingSessionRequest(String id, String ref, String number) {
        this(id, ref, number, null, null, null, null, null);
    }
    public CreateBoardingSessionRequest(String id, String ref, String number, int offset) {
        this(id, ref, number, offset, null, null, null, null);
    }
    public CreateBoardingSessionRequest(String id, String ref, String number, boolean legacyEnabled) {
        this(id, ref, number, null, legacyEnabled, null, null, null);
    }
    public CreateBoardingSessionRequest(String id, String ref, String number, Integer offset, Boolean legacy) {
        this(id, ref, number, offset, legacy, null, null, null);
    }
    public CreateBoardingSessionRequest {
        try {
            clientRequestId = BoardingSession.validateUuid(clientRequestId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if ((candidateRef != null && (candidateRef.isBlank() || candidateRef.length() > 255
                    || routeId != null || boardingStopOccurrenceId != null || targetStopOccurrenceId != null))
                || (candidateRef == null && (routeId == null || routeId <= 0 || boardingStopOccurrenceId == null
                    || boardingStopOccurrenceId <= 0 || targetStopOccurrenceId == null || targetStopOccurrenceId <= 0
                    || confirmedVehicleNumber == null || legacyPrepareNotificationEnabled != null))
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
            @JsonProperty("prepareNotificationEnabled") JsonNode legacy,
            @JsonProperty("routeId") JsonNode route,
            @JsonProperty("boardingStopOccurrenceId") JsonNode boarding,
            @JsonProperty("targetStopOccurrenceId") JsonNode target) {
        if (id == null || !id.isString() || ref != null && !ref.isString()
                || !validId(route) || !validId(boarding) || !validId(target)
                || number != null && !number.isString()
                || offset != null && (!offset.isIntegralNumber() || !offset.canConvertToInt()
                    || offset.asInt() != 1 && offset.asInt() != 2)
                || legacy != null && (!legacy.isBoolean() || offset != null)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new CreateBoardingSessionRequest(id.asString(), ref == null ? null : ref.asString(), number == null ? null : number.asString(),
                offset == null ? null : Integer.valueOf(offset.asInt()), legacy == null ? null : legacy.asBoolean(),
                route == null ? null : route.asLong(), boarding == null ? null : boarding.asLong(),
                target == null ? null : target.asLong());
    }

    private static boolean validId(JsonNode node) {
        return node == null || node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0;
    }

    public boolean currentVehicleRequest() { return candidateRef == null; }

    public int effectiveNotificationOffsetStops() { return notificationOffsetStops == null ? 2 : notificationOffsetStops; }

    public String legacyFingerprint() {
        return Boolean.FALSE.equals(legacyPrepareNotificationEnabled)
                ? BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber, "false")
                : BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber);
    }

    public String fingerprint() {
        if (currentVehicleRequest()) {
            return BoardingFingerprint.hash("CURRENT_VEHICLE_V1", clientRequestId, routeId.toString(),
                    boardingStopOccurrenceId.toString(), targetStopOccurrenceId.toString(), confirmedVehicleNumber,
                    Integer.toString(effectiveNotificationOffsetStops()));
        }
        return legacyPrepareNotificationEnabled != null ? legacyFingerprint()
                : BoardingFingerprint.hash(clientRequestId, candidateRef, confirmedVehicleNumber,
                    "PRE_DEPARTURE_V2", Integer.toString(effectiveNotificationOffsetStops()));
    }
}
