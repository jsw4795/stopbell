package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitSourceSystem.*;
import static com.stopbell.transit.domain.TransitSourceDataKind.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransitRawMetadataDiffTest {
    private final TransitRawMetadataDiff diff = new TransitRawMetadataDiff();
    private static TransitRawMetadataDiff.Row row(com.stopbell.transit.domain.TransitSourceSystem source, String key, String json, String version) {
        return new TransitRawMetadataDiff.Row(new TransitRawMetadataDiff.Key(source, ROUTE, key), TransitRawMetadataDiff.parse(json), version);
    }
    @Test void structuralJsonAndVersions() {
        var previous = List.of(row(TAGO, "same", "{\"a\":1,\"b\":{\"c\":[1,2]}}", "1"),
                row(TAGO, "type", "{\"x\":1}", null), row(TAGO, "array", "{\"x\":[1,2]}", null),
                row(TAGO, "gone", "{}", null), row(GBIS, "only", "{}", "1"));
        var incoming = List.of(row(TAGO, "same", "{\"b\":{\"c\":[1,2]},\"a\":1}", "2"),
                row(TAGO, "type", "{\"x\":\"1\"}", null), row(TAGO, "array", "{\"x\":[2,1]}", null),
                row(TAGO, "new", "{}", null));
        var result = diff.analyze(previous, incoming, Set.of(TAGO));
        assertThat(result.get(TAGO)).isEqualTo(new TransitRawMetadataDiff.Counts(true, 1, 2, 1, 1, 1));
        assertThat(result.get(GBIS).compared()).isFalse();
        assertThat(result.get(GBIS).absent()).isZero();
    }
    @Test void separatesSourcesAndRejectsDuplicates() {
        var old = List.of(row(SEOUL_TDATA, "out-of-scope", "{\"v\":1}", null), row(GBIS, "only", "{}", "a"));
        var result = diff.analyze(old, List.of(row(SEOUL_TDATA, "out-of-scope", "{\"v\":2}", null),
                row(GBIS, "only", "{}", "b")), Set.of(SEOUL_TDATA, GBIS));
        assertThat(result.get(SEOUL_TDATA).modified()).isEqualTo(1);
        assertThat(result.get(GBIS).unchanged()).isEqualTo(1);
        assertThat(result.get(GBIS).versionChanged()).isEqualTo(1);
        assertThatThrownBy(() -> diff.analyze(old, List.of(old.get(0), old.get(0)), Set.of(SEOUL_TDATA))).isInstanceOf(IllegalArgumentException.class);
    }
}
