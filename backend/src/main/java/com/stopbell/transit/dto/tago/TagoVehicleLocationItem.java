package com.stopbell.transit.dto.tago;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TagoVehicleLocationItem(
        @JsonProperty("vehicleno") String vehicleNo,
        @JsonProperty("nodeid") String nodeId,
        @JsonProperty("nodeord") Integer nodeOrder,
        @JsonProperty("gpslati") BigDecimal gpsLatitude,
        @JsonProperty("gpslong") BigDecimal gpsLongitude
) {
}
