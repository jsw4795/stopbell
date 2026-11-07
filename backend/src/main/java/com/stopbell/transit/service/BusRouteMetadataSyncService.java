package com.stopbell.transit.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.transit.service.TransitSourceMetadataService.SourceRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusRouteMetadataSyncService {

    private final BusRouteRepository busRouteRepository;
    private final BusStopRepository busStopRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;
    private final TransitSourceMetadataService sourceMetadataService;

    public BusRouteMetadataSyncService(
            BusRouteRepository busRouteRepository,
            BusStopRepository busStopRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository,
            TransitSourceMetadataService sourceMetadataService
    ) {
        this.busRouteRepository = busRouteRepository;
        this.busStopRepository = busStopRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.sourceMetadataService = sourceMetadataService;
    }

    @Transactional
    public BusRoute syncRoute(BusRouteMetadataSnapshot snapshot) {
        return reconcile(snapshot, null);
    }

    @Transactional
    public BusRoute syncSeoulRoute(BusRouteMetadataSnapshot snapshot, SeoulRouteRawData raw) {
        if (snapshot.provider() != TransitProvider.SEOUL_BUS) {
            throw new IllegalArgumentException("Raw Seoul metadata requires a Seoul route");
        }
        return reconcile(snapshot, Objects.requireNonNull(raw, "Seoul raw data must not be null"));
    }

    @Transactional
    public BusRoute syncTagoRoute(BusRouteMetadataSnapshot snapshot, TagoRouteRawData raw) {
        if (snapshot.provider() != TransitProvider.TAGO) {
            throw new IllegalArgumentException("Raw TAGO metadata requires a TAGO route");
        }
        Objects.requireNonNull(raw, "TAGO raw data must not be null");
        return reconcile(snapshot, null, raw);
    }

    private BusRoute reconcile(BusRouteMetadataSnapshot snapshot, SeoulRouteRawData raw) {
        return reconcile(snapshot, raw, null);
    }

    private BusRoute reconcile(BusRouteMetadataSnapshot snapshot, SeoulRouteRawData raw, TagoRouteRawData tagoRaw) {
        BusRoute route = busRouteRepository
                .findByProviderAndExternalRouteId(snapshot.provider(), snapshot.externalRouteId())
                .orElseGet(() -> busRouteRepository.save(new BusRoute(
                        snapshot.provider(),
                        snapshot.externalRouteId(),
                        snapshot.routeNumber(),
                        snapshot.cityCode()
                )));
        route.updateMetadata(snapshot.routeNumber(), snapshot.cityCode());

        Map<OccurrenceKey, BusRouteStopOccurrence> existingOccurrences = new HashMap<>();
        for (BusRouteStopOccurrence occurrence : occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route)) {
            existingOccurrences.put(new OccurrenceKey(
                    occurrence.getStop().getExternalStopId(), occurrence.getStopOrder()
            ), occurrence);
        }

        Map<OccurrenceKey, IncomingOccurrence> incomingOccurrences = new HashMap<>();
        for (BusStopOccurrenceMetadataSnapshot occurrenceSnapshot : snapshot.occurrences()) {
            OccurrenceKey key = new OccurrenceKey(occurrenceSnapshot.externalStopId(), occurrenceSnapshot.stopOrder());
            BusStop stop = busStopRepository
                    .findByProviderAndExternalStopId(snapshot.provider(), occurrenceSnapshot.externalStopId())
                    .orElseGet(() -> busStopRepository.save(new BusStop(
                            snapshot.provider(),
                            occurrenceSnapshot.externalStopId(),
                            occurrenceSnapshot.stopName(),
                            occurrenceSnapshot.latitude(),
                            occurrenceSnapshot.longitude()
                    )));
            stop.updateMetadata(
                    occurrenceSnapshot.stopName(), occurrenceSnapshot.latitude(), occurrenceSnapshot.longitude()
            );
            incomingOccurrences.put(key, new IncomingOccurrence(stop, occurrenceSnapshot.destinationName()));
        }

        existingOccurrences.entrySet().stream()
                .filter(entry -> !incomingOccurrences.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .forEach(occurrenceRepository::delete);
        occurrenceRepository.flush();

        List<SourceRecord> records = new ArrayList<>();
        if (raw != null) {
            records.add(record(TransitSourceDataKind.ROUTE, snapshot.externalRouteId(), route.getId(),
                    raw.routePayload(), raw.collectedAt()));
        } else if (tagoRaw != null) {
            records.add(tagoRecord(TransitSourceDataKind.ROUTE, snapshot.externalRouteId(), route.getId(),
                    tagoRaw.routePayload(), tagoRaw.collectedAt()));
        }
        Set<String> processedStops = new HashSet<>();
        for (Map.Entry<OccurrenceKey, IncomingOccurrence> incomingOccurrence : incomingOccurrences.entrySet()) {
            IncomingOccurrence incoming = incomingOccurrence.getValue();
            BusRouteStopOccurrence existing = existingOccurrences.get(incomingOccurrence.getKey());
            if (existing != null) {
                if (raw == null || incoming.destinationName() != null) {
                    existing.updateDestinationName(incoming.destinationName());
                }
            } else {
                existing = occurrenceRepository.save(new BusRouteStopOccurrence(
                        route,
                        incoming.stop(),
                        incomingOccurrence.getKey().stopOrder(),
                        incoming.destinationName()
                ));
            }
            if (raw != null) {
                String stopId = incomingOccurrence.getKey().externalStopId();
                if (processedStops.add(stopId) && raw.stopPayloads().containsKey(stopId)) {
                    records.add(record(TransitSourceDataKind.STOP, stopId, incoming.stop().getId(),
                            raw.stopPayloads().get(stopId), raw.collectedAt()));
                }
                String key = SeoulCsvMetadataSource.occurrenceSourceKey(
                        snapshot.externalRouteId(), incomingOccurrence.getKey().stopOrder());
                records.add(record(TransitSourceDataKind.OCCURRENCE, key, existing.getId(),
                        raw.occurrencePayloads().get(key), raw.collectedAt()));
            } else if (tagoRaw != null) {
                String key = TagoMetadataSource.occurrenceSourceKey(snapshot.externalRouteId(), incomingOccurrence.getKey().stopOrder());
                records.add(tagoRecord(TransitSourceDataKind.OCCURRENCE, key, existing.getId(),
                        tagoRaw.occurrencePayloads().get(key), tagoRaw.collectedAt()));
            }
        }
        if (raw != null) {
            sourceMetadataService.saveAll(records);
        } else if (tagoRaw != null) {
            occurrenceRepository.flush();
            sourceMetadataService.saveTagoLinkedBatch(records);
        }
        return route;
    }

    private static SourceRecord tagoRecord(TransitSourceDataKind kind, String key, Long id, String payload, Instant collectedAt) {
        if (id == null || payload == null) throw new IllegalStateException("Missing normalized ID or TAGO raw row: " + key);
        return new SourceRecord(TransitSourceSystem.TAGO, kind, key, id, payload, null, collectedAt);
    }

    private static SourceRecord record(TransitSourceDataKind kind, String key, Long id, String payload, Instant collectedAt) {
        if (id == null || payload == null) {
            throw new IllegalStateException("Missing normalized ID or Seoul raw row: " + key);
        }
        return new SourceRecord(TransitSourceSystem.SEOUL_TDATA, kind, key, id, payload, null, collectedAt);
    }

    public record SeoulRouteRawData(String routePayload, Map<String, String> stopPayloads,
            Map<String, String> occurrencePayloads, Instant collectedAt) {
    }

    public record TagoRouteRawData(String routePayload, Map<String, String> occurrencePayloads, Instant collectedAt) { }

    private record OccurrenceKey(String externalStopId, int stopOrder) {
    }

    private record IncomingOccurrence(BusStop stop, String destinationName) {
    }
}
