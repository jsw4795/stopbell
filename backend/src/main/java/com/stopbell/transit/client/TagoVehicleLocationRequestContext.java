package com.stopbell.transit.client;

public record TagoVehicleLocationRequestContext(String cityCode) implements VehicleLocationRequestContext {

    public TagoVehicleLocationRequestContext {
        if (cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("TAGO city code must not be blank");
        }
    }
}
