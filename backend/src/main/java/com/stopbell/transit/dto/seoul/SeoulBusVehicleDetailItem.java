package com.stopbell.transit.dto.seoul;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulBusVehicleDetailItem(
        String vehId,
        String plainNo,
        String stId,
        Integer stOrd,
        Integer stopFlag,
        String dataTm,
        BigDecimal tmX,
        BigDecimal tmY
) {
}
