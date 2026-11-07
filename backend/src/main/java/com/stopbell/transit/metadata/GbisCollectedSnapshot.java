package com.stopbell.transit.metadata;

import java.util.List;
import java.util.Map;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;

/** Validated GBIS data and original string fields, before any persistence or TAGO linking. */
public record GbisCollectedSnapshot(GbisBulkMetadata normalized, List<RawRow> rawRows, String sourceVersion) {
    public GbisCollectedSnapshot {
        rawRows = List.copyOf(rawRows);
    }

    public record RawRow(TransitSourceSystem sourceSystem, TransitSourceDataKind dataKind, String sourceKey,
                         String externalRouteId, Integer stopOrder, Map<String, String> fields) {
        public RawRow {
            fields = Map.copyOf(fields);
        }
    }
}
