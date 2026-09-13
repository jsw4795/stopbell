package com.stopbell.transit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "bus_route_stop_occurrences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bus_route_stop_occurrences_route_order",
                columnNames = {"route_id", "stop_order"}
        )
)
public class BusRouteStopOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private BusRoute route;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id", nullable = false)
    private BusStop stop;

    @Column(name = "stop_order", nullable = false)
    private int stopOrder;

    protected BusRouteStopOccurrence() {
    }

    public BusRouteStopOccurrence(BusRoute route, BusStop stop, int stopOrder) {
        if (route == null || stop == null) {
            throw new IllegalArgumentException("Route and stop must not be null");
        }
        if (route.getProvider() != stop.getProvider()) {
            throw new IllegalArgumentException("Route and stop providers must match");
        }
        if (stopOrder <= 0) {
            throw new IllegalArgumentException("Stop order must be positive");
        }
        this.route = route;
        this.stop = stop;
        this.stopOrder = stopOrder;
    }

    public Long getId() {
        return id;
    }

    public BusRoute getRoute() {
        return route;
    }

    public BusStop getStop() {
        return stop;
    }

    public int getStopOrder() {
        return stopOrder;
    }
}
