package com.stopbell.transit.client;

public sealed interface VehicleLocationRequestContext
        permits SeoulBusVehicleLocationRequestContext, TagoVehicleLocationRequestContext {
}
