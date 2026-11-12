package com.stopbell.transit.dto.gbis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One vehicle of GBIS {@code getBusLocationListv2}. {@code remainSeatCnt} is {@code -1} or absent when the
 * route type does not provide seats; use {@link #remainSeats()} to get a nullable value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GbisBusLocationItem(
        String plateNo,
        Long vehId,
        Long routeId,
        Integer routeTypeCd,
        Long stationId,
        Integer stationSeq,
        Integer stateCd,
        Integer remainSeatCnt,
        Integer crowded,
        Integer lowPlate,
        Integer taglessCd
) {

    /** Remaining seats, or null when the Provider does not provide them (-1, negative or missing). */
    public Integer remainSeats() {
        return remainSeatCnt == null || remainSeatCnt < 0 ? null : remainSeatCnt;
    }
}
