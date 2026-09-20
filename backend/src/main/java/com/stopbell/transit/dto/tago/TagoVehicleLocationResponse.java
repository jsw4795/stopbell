package com.stopbell.transit.dto.tago;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TagoVehicleLocationResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Header header, Body body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String resultCode, String resultMsg) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Items items, Integer totalCount, Integer pageNo, Integer numOfRows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Items(List<TagoVehicleLocationItem> item) {
    }
}
