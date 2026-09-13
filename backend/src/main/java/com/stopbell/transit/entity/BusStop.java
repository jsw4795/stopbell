package com.stopbell.transit.entity;

import java.math.BigDecimal;

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
        name = "bus_stops",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bus_stops_provider_external_stop_id",
                columnNames = {"provider", "external_stop_id"}
        )
)
public class BusStop {

    private static final int EXTERNAL_STOP_ID_MAX_LENGTH = 255;
    private static final int STOP_NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransitProvider provider;

    @Column(name = "external_stop_id", nullable = false, length = EXTERNAL_STOP_ID_MAX_LENGTH)
    private String externalStopId;

    @Column(name = "stop_name", nullable = false, length = STOP_NAME_MAX_LENGTH)
    private String stopName;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    protected BusStop() {
    }

    public BusStop(
            TransitProvider provider,
            String externalStopId,
            String stopName,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        validate(provider, externalStopId, stopName, latitude, longitude);
        this.provider = provider;
        this.externalStopId = externalStopId;
        this.stopName = stopName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void updateMetadata(String stopName, BigDecimal latitude, BigDecimal longitude) {
        validate(provider, externalStopId, stopName, latitude, longitude);
        this.stopName = stopName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    private static void validate(
            TransitProvider provider,
            String externalStopId,
            String stopName,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        validateRequiredText(externalStopId, "External stop ID", EXTERNAL_STOP_ID_MAX_LENGTH);
        validateRequiredText(stopName, "Stop name", STOP_NAME_MAX_LENGTH);
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Stop latitude and longitude must both be present or absent");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalArgumentException("Stop latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("Stop longitude must be between -180 and 180");
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

    public String getExternalStopId() {
        return externalStopId;
    }

    public String getStopName() {
        return stopName;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }
}
