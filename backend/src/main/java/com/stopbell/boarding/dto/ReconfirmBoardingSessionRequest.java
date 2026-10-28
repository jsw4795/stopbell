package com.stopbell.boarding.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

public record ReconfirmBoardingSessionRequest(long expectedRevision, String candidateRef, String confirmedVehicleNumber) {
    public ReconfirmBoardingSessionRequest {
        if (expectedRevision < 0 || candidateRef == null || candidateRef.isBlank() || candidateRef.length() > 255
                || (confirmedVehicleNumber != null && (confirmedVehicleNumber.isBlank() || confirmedVehicleNumber.length() > 255))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static ReconfirmBoardingSessionRequest fromJson(@JsonProperty("expectedRevision") JsonNode revision,
            @JsonProperty("candidateRef") JsonNode reference, @JsonProperty("confirmedVehicleNumber") JsonNode number) {
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                || reference == null || !reference.isString() || (number != null && !number.isString())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new ReconfirmBoardingSessionRequest(revision.longValue(), reference.asString(), number == null ? null : number.asString());
    }
}
