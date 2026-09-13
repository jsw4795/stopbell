package com.stopbell.transit.entity;

import com.stopbell.transit.domain.TransitProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "bus_routes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bus_routes_provider_external_route_id",
                columnNames = {"provider", "external_route_id"}
        )
)
public class BusRoute {

    private static final int EXTERNAL_ROUTE_ID_MAX_LENGTH = 255;
    private static final int ROUTE_NUMBER_MAX_LENGTH = 100;
    private static final int CITY_CODE_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransitProvider provider;

    @Column(name = "external_route_id", nullable = false, length = EXTERNAL_ROUTE_ID_MAX_LENGTH)
    private String externalRouteId;

    @Column(name = "route_number", nullable = false, length = ROUTE_NUMBER_MAX_LENGTH)
    private String routeNumber;

    @Column(name = "city_code", length = CITY_CODE_MAX_LENGTH)
    private String cityCode;

    protected BusRoute() {
    }

    public BusRoute(TransitProvider provider, String externalRouteId, String routeNumber, String cityCode) {
        validate(provider, externalRouteId, routeNumber, cityCode);
        this.provider = provider;
        this.externalRouteId = externalRouteId;
        this.routeNumber = routeNumber;
        this.cityCode = cityCode;
    }

    public void updateMetadata(String routeNumber, String cityCode) {
        validate(provider, externalRouteId, routeNumber, cityCode);
        this.routeNumber = routeNumber;
        this.cityCode = cityCode;
    }

    private static void validate(
            TransitProvider provider,
            String externalRouteId,
            String routeNumber,
            String cityCode
    ) {
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        validateRequiredText(externalRouteId, "External route ID", EXTERNAL_ROUTE_ID_MAX_LENGTH);
        validateRequiredText(routeNumber, "Route number", ROUTE_NUMBER_MAX_LENGTH);
        if (provider == TransitProvider.TAGO) {
            validateRequiredText(cityCode, "TAGO city code", CITY_CODE_MAX_LENGTH);
        } else if (cityCode != null) {
            throw new IllegalArgumentException("Seoul Bus route must not have a TAGO city code");
        }
    }

    private static void validateRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length " + maxLength);
        }
    }

    public Long getId() {
        return id;
    }

    public TransitProvider getProvider() {
        return provider;
    }

    public String getExternalRouteId() {
        return externalRouteId;
    }

    public String getRouteNumber() {
        return routeNumber;
    }

    public String getCityCode() {
        return cityCode;
    }
}
