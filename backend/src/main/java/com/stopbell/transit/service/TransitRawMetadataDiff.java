package com.stopbell.transit.service;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Source rows absent from a collection are not inferred to have been deleted. */
public final class TransitRawMetadataDiff {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Key(TransitSourceSystem source, TransitSourceDataKind kind, String sourceKey) {
        public Key {
            Objects.requireNonNull(source); Objects.requireNonNull(kind);
            if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("Source key is required");
        }
    }
    public record Row(Key key, JsonNode fields, String version) {
        public Row {
            Objects.requireNonNull(key); Objects.requireNonNull(fields);
            if (!fields.isObject()) throw new IllegalArgumentException("Raw fields must be a JSON object");
        }
    }
    public record Counts(boolean compared, int added, int modified, int unchanged, int absent, int versionChanged) { }

    public Map<TransitSourceSystem, Counts> analyze(List<Row> previous, List<Row> incoming, Set<TransitSourceSystem> compared) {
        Map<Key, Row> old = index(previous), next = index(incoming);
        if (next.keySet().stream().anyMatch(k -> !compared.contains(k.source()))) throw new IllegalArgumentException("Unexpected source");
        Map<TransitSourceSystem, Counts> result = new EnumMap<>(TransitSourceSystem.class);
        for (TransitSourceSystem source : TransitSourceSystem.values()) {
            if (!compared.contains(source)) { result.put(source, new Counts(false, 0, 0, 0, 0, 0)); continue; }
            int added = 0, modified = 0, unchanged = 0, absent = 0, versionChanged = 0;
            Set<Key> keys = new HashSet<>();
            old.keySet().stream().filter(k -> k.source() == source).forEach(keys::add);
            next.keySet().stream().filter(k -> k.source() == source).forEach(keys::add);
            for (Key key : keys) {
                Row before = old.get(key), after = next.get(key);
                if (before == null) added++;
                else if (after == null) absent++;
                else {
                    if (before.fields().equals(after.fields())) unchanged++; else modified++;
                    if (!Objects.equals(before.version(), after.version())) versionChanged++;
                }
            }
            result.put(source, new Counts(true, added, modified, unchanged, absent, versionChanged));
        }
        return Map.copyOf(result);
    }
    public static JsonNode parse(String json) { return JSON.readTree(json); }
    public static JsonNode fromMap(Map<String, String> fields) { return JSON.valueToTree(fields); }
    private static Map<Key, Row> index(List<Row> rows) {
        Map<Key, Row> index = new HashMap<>();
        for (Row row : rows) if (index.put(row.key(), row) != null) throw new IllegalArgumentException("Duplicate source identity");
        return index;
    }
}
