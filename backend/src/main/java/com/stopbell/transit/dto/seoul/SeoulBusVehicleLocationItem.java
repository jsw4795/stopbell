package com.stopbell.transit.dto.seoul;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulBusVehicleLocationItem(
        String vehId,
        String plainNo,
        Integer sectOrd,
        String sectionId,
        Integer stopFlag,
        String dataTm,
        BigDecimal gpsX,
        BigDecimal gpsY,
        String nextStId,
        String isrunyn,
        String congetion
) {
}
