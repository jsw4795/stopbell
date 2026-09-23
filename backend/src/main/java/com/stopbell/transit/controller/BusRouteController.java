package com.stopbell.transit.controller;

import java.util.List;

import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.dto.BusRouteStopResponse;
import com.stopbell.transit.service.BusRouteSearchService;
import com.stopbell.transit.service.BusRouteStopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusRouteController {

    private final BusRouteSearchService busRouteSearchService;
    private final BusRouteStopService busRouteStopService;

    public BusRouteController(BusRouteSearchService busRouteSearchService, BusRouteStopService busRouteStopService) {
        this.busRouteSearchService = busRouteSearchService;
        this.busRouteStopService = busRouteStopService;
    }

    @GetMapping("/api/v1/bus-routes")
    public List<BusRouteSearchResponse> search(@RequestParam("query") String query) {
        return busRouteSearchService.search(query);
    }

    @GetMapping("/api/v1/bus-routes/{routeId}/stops")
    public List<BusRouteStopResponse> findStops(@PathVariable Long routeId) {
        return busRouteStopService.findAll(routeId);
    }
}
