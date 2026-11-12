package com.stopbell.transit.dto.gbis;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GbisBusLocationResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(MsgHeader msgHeader, MsgBody msgBody) {
    }

    /** {@code queryTime} is the Provider response time (Asia/Seoul local time, e.g. 2026-11-12 16:55:32.118). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgHeader(String queryTime, Integer resultCode, String resultMessage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgBody(List<GbisBusLocationItem> busLocationList) {
    }
}
