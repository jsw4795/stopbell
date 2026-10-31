package com.stopbell.transit.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;

import com.stopbell.common.observability.OperationalMetrics;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.Test;

class VehiclePollingMetricsTest {
    @Test
    void shared_request_is_measured_once_and_provider_failure_is_classified() {
        var tago = mock(TagoVehicleLocationClient.class);
        var seoul = mock(SeoulBusVehicleLocationClient.class);
        var detail = mock(SeoulBusVehicleDetailClient.class);
        var metrics = mock(OperationalMetrics.class);
        var cycle = new VehiclePollingCycle(tago, seoul, detail, Clock.systemUTC(), metrics);
        cycle.tago("synthetic-route", "123");
        cycle.tago("synthetic-route", "123");
        verify(tago).fetchVehicleLocations(any());
        verify(metrics).provider(eq(TransitProvider.TAGO), eq(OperationalMetrics.Operation.TAGO_LOCATION),
                isNull(), anyLong());
        when(detail.fetchVehicleDetail(anyString())).thenThrow(TransitProviderClientException.http(
                TransitProvider.SEOUL_BUS, "detail", 503, new RuntimeException("secret")));
        for (int i = 0; i < 2; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> cycle.detail("synthetic-vehicle"))
                    .isInstanceOf(TransitProviderClientException.class);
        }
        verify(metrics).provider(eq(TransitProvider.SEOUL_BUS), eq(OperationalMetrics.Operation.SEOUL_DETAIL),
                eq(TransitProviderClientFailureKind.HTTP), anyLong());
        verify(detail).fetchVehicleDetail(anyString());
    }
}
