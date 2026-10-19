package com.stopbell.boarding.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

public record CancelBoardingSessionRequest(long expectedRevision) {
    public CancelBoardingSessionRequest {
        if (expectedRevision < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    @JsonCreator
    public static CancelBoardingSessionRequest fromJson(@JsonProperty("expectedRevision") JsonNode revision) {
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return new CancelBoardingSessionRequest(revision.longValue());
    }
}
