package com.stopbell.transit.controller;

import java.util.List;

import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.service.BusRouteSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusRouteController {

    private final BusRouteSearchService busRouteSearchService;

    public BusRouteController(BusRouteSearchService busRouteSearchService) {
        this.busRouteSearchService = busRouteSearchService;
    }

    @GetMapping("/api/v1/bus-routes")
    public List<BusRouteSearchResponse> search(@RequestParam("query") String query) {
        return busRouteSearchService.search(query);
    }
}
