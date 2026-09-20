package com.stopbell.transit.dto.seoul;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulBusVehicleLocationResponse(
        @JsonProperty("msgHeader") Header header,
        @JsonProperty("msgBody") Body body
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(
            String headerCd,
            String headerMsg
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(
            List<SeoulBusVehicleLocationItem> itemList
    ) {
    }
}
