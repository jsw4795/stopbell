package com.stopbell.alarm.entity;

import java.math.BigDecimal;

import com.stopbell.transit.domain.TransitProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "bus_alarm_targets")
public class BusAlarmTarget {

    private static final int EXTERNAL_ID_MAX_LENGTH = 255;
    private static final int ROUTE_NUMBER_MAX_LENGTH = 100;
    private static final int STOP_NAME_MAX_LENGTH = 255;
    private static final int CITY_CODE_MAX_LENGTH = 50;

    @Id
    @Column(name = "alarm_id")
    private Long alarmId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alarm_id", nullable = false)
    private Alarm alarm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransitProvider provider;

    @Column(name = "external_route_id", nullable = false, length = EXTERNAL_ID_MAX_LENGTH)
    private String externalRouteId;

    @Column(name = "external_stop_id", nullable = false, length = EXTERNAL_ID_MAX_LENGTH)
    private String externalStopId;

    @Column(name = "target_stop_order", nullable = false)
    private int targetStopOrder;

    @Column(name = "route_number", nullable = false, length = ROUTE_NUMBER_MAX_LENGTH)
    private String routeNumber;

    @Column(name = "stop_name", nullable = false, length = STOP_NAME_MAX_LENGTH)
    private String stopName;

    @Column(name = "target_stop_latitude", precision = 10, scale = 7)
    private BigDecimal targetStopLatitude;

    @Column(name = "target_stop_longitude", precision = 10, scale = 7)
    private BigDecimal targetStopLongitude;

    @Column(name = "city_code", length = CITY_CODE_MAX_LENGTH)
    private String cityCode;

    @Column(name = "notify_one_stop_before", nullable = false)
    private boolean notifyOneStopBefore;

    @Column(name = "predecessor_external_stop_id", length = EXTERNAL_ID_MAX_LENGTH)
    private String predecessorExternalStopId;

    @Column(name = "predecessor_stop_order")
    private Integer predecessorStopOrder;

    @Column(name = "notify_one_stop_after", nullable = false)
    private boolean notifyOneStopAfter;

    @Column(name = "successor_external_stop_id", length = EXTERNAL_ID_MAX_LENGTH)
    private String successorExternalStopId;

    @Column(name = "successor_stop_order")
    private Integer successorStopOrder;

    protected BusAlarmTarget() {
    }

    public BusAlarmTarget(
            TransitProvider provider,
            String externalRouteId,
            String externalStopId,
            int targetStopOrder,
            String routeNumber,
            String stopName,
            BigDecimal targetStopLatitude,
            BigDecimal targetStopLongitude,
            String cityCode,
            AdjacentStopSnapshot predecessor,
            AdjacentStopSnapshot successor
    ) {
        validateRequiredText(externalRouteId, "External route ID", EXTERNAL_ID_MAX_LENGTH);
        validateRequiredText(externalStopId, "External stop ID", EXTERNAL_ID_MAX_LENGTH);
        validateRequiredText(routeNumber, "Route number", ROUTE_NUMBER_MAX_LENGTH);
        validateRequiredText(stopName, "Stop name", STOP_NAME_MAX_LENGTH);
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        if (targetStopOrder <= 0) {
            throw new IllegalArgumentException("Target stop order must be positive");
        }

        validateCoordinates(targetStopLatitude, targetStopLongitude);
        validateProviderContext(provider, cityCode);
        validateAdjacentStop(predecessor, "Predecessor");
        validateAdjacentStop(successor, "Successor");

        this.provider = provider;
        this.externalRouteId = externalRouteId;
        this.externalStopId = externalStopId;
        this.targetStopOrder = targetStopOrder;
        this.routeNumber = routeNumber;
        this.stopName = stopName;
        this.targetStopLatitude = targetStopLatitude;
        this.targetStopLongitude = targetStopLongitude;
        this.cityCode = cityCode;
        this.notifyOneStopBefore = predecessor != null;
        this.predecessorExternalStopId = predecessor == null ? null : predecessor.externalStopId();
        this.predecessorStopOrder = predecessor == null ? null : predecessor.stopOrder();
        this.notifyOneStopAfter = successor != null;
        this.successorExternalStopId = successor == null ? null : successor.externalStopId();
        this.successorStopOrder = successor == null ? null : successor.stopOrder();
    }

    void assignTo(Alarm alarm) {
        if (this.alarm != null && this.alarm != alarm) {
            throw new IllegalStateException("Bus alarm target is already assigned to an alarm");
        }
        this.alarm = alarm;
    }

    private static void validateRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length " + maxLength);
        }
    }

    private static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Target stop latitude and longitude must both be present or absent");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalArgumentException("Target stop latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("Target stop longitude must be between -180 and 180");
        }
    }

    private static void validateProviderContext(TransitProvider provider, String cityCode) {
        if (provider == TransitProvider.TAGO) {
            validateRequiredText(cityCode, "TAGO city code", CITY_CODE_MAX_LENGTH);
        } else if (cityCode != null) {
            throw new IllegalArgumentException("Seoul Bus target must not have a TAGO city code");
        }
    }

    private static void validateAdjacentStop(AdjacentStopSnapshot adjacentStop, String fieldName) {
        if (adjacentStop != null && adjacentStop.externalStopId().length() > EXTERNAL_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " external stop ID exceeds maximum length "
                    + EXTERNAL_ID_MAX_LENGTH);
        }
    }

    public Long getAlarmId() {
        return alarmId;
    }

    public TransitProvider getProvider() {
        return provider;
    }

    public String getExternalRouteId() {
        return externalRouteId;
    }

    public String getExternalStopId() {
        return externalStopId;
    }

    public int getTargetStopOrder() {
        return targetStopOrder;
    }

    public String getRouteNumber() {
        return routeNumber;
    }

    public String getStopName() {
        return stopName;
    }

    public BigDecimal getTargetStopLatitude() {
        return targetStopLatitude;
    }

    public BigDecimal getTargetStopLongitude() {
        return targetStopLongitude;
    }

    public String getCityCode() {
        return cityCode;
    }

    public boolean isNotifyOneStopBefore() {
        return notifyOneStopBefore;
    }

    public String getPredecessorExternalStopId() {
        return predecessorExternalStopId;
    }

    public Integer getPredecessorStopOrder() {
        return predecessorStopOrder;
    }

    public boolean isNotifyOneStopAfter() {
        return notifyOneStopAfter;
    }

    public String getSuccessorExternalStopId() {
        return successorExternalStopId;
    }

    public Integer getSuccessorStopOrder() {
        return successorStopOrder;
    }
}
