package com.stopbell.transit.metadata;

import static com.stopbell.transit.metadata.OfflineBundleFixtures.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagoGbisOfflineSnapshotAssemblerTest {
    @TempDir Path temp;
    private Path tago() throws Exception { return OfflineBundleFixtures.tago(temp, List.of("GGB200"), Map.of("GGB200", stops("A", "A")), "tagoraw"); }
    @Test void combines_snapshots_preserving_identity_order_raw_and_version() throws Exception {
        Path tago = tago(), gbis = gbis(temp, "v1", "turn", "terminal");
        var original = TagoLocalBundle.replay(tago, KEY);
        var bulk = GbisLocalBundle.replay(gbis, KEY);
        var result = TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY);
        assertThat(result.normalized().routes()).extracting(r -> r.externalRouteId()).containsExactly("GGB200");
        var stops = result.normalized().routes().getFirst().occurrences();
        assertThat(stops).extracting(s -> s.externalStopId()).containsExactly("GGB10", "GGB10");
        assertThat(stops).extracting(s -> s.stopOrder()).containsExactly(1, 2);
        assertThat(stops).extracting(s -> s.destinationName()).containsExactly("terminal", "terminal");
        assertThat(result.rawRows()).isEqualTo(original.rawRows());
        assertThat(result.rawRows().getFirst().fields().get("extra").asString()).isEqualTo("tagoraw");
        assertThat(result.gbis()).isEqualTo(bulk);
        assertThat(result.gbis().sourceVersion()).isEqualTo("v1");
        assertThat(result.gbis().rawRows().getFirst().fields()).containsEntry("extra", "gbis raw");
        assertThat(TagoGbisOfflineSnapshotAssembler.tagoOnly(tago, KEY)).isEqualTo(original);
        assertThat(original.gbis()).isNull();
    }
    @Test void only_matching_routes_are_enriched_and_gbis_only_routes_are_excluded() throws Exception {
        var ids = List.of("GGB200", "GGB201");
        var tago = OfflineBundleFixtures.tago(temp, ids, Map.of("GGB200", stops("A", "A"), "GGB201", stops("B", "B")), "raw");
        var gbis = gbisBulk(temp, "v1", "200|300|2|a^201|different|2|b^999|300|1|c",
                "200|300|상행|1|10|turn|127|37^200|300|하행|2|10|end|127|37^"
                + "201|different|상행|1|10|turn|127|37^201|different|하행|2|10|end|127|37^999|300|상행|1|10|only|127|37");
        var original = TagoLocalBundle.replay(tago, KEY);
        var data = GbisLocalBundle.replay(gbis, KEY);
        var counts = GbisDestinationEnricher.enrich(original.normalized().routes(), data.normalized());
        assertThat(counts.enrichedRoutes()).isEqualTo(1);
        assertThat(counts.destinationOccurrences()).isEqualTo(2);
        assertThat(counts.skippedRoutes()).containsEntry("route_name", 1);
        var result = TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY);
        assertThat(result.normalized().routes()).extracting(r -> r.externalRouteId()).containsExactlyElementsOf(ids);
        assertThat(result.normalized().routes().get(1)).isEqualTo(original.normalized().routes().get(1));
        assertThat(result.normalized().routes().getFirst().occurrences().getFirst().destinationName()).isEqualTo("end");
    }
    @Test void mismatched_route_id_count_stop_and_order_remain_unchanged() throws Exception {
        var tago = tago(); var original = TagoLocalBundle.replay(tago, KEY);
        for (String[] test : List.of(
                new String[]{"201|300|1|a", "201|300|상행|1|10|end|127|37", "candidate_missing"},
                new String[]{"200|300|1|a", "200|300|상행|1|10|end|127|37", "occurrence_count"},
                new String[]{"200|300|2|a", "200|300|상행|1|11|turn|127|37^200|300|하행|2|10|end|127|37", "stop_id"},
                new String[]{"200|300|3|a", "200|300|상행|1|10|turn|127|37^200|300|하행|3|10|end|127|37", "order"})) {
            var path = gbisBulk(temp, "v1", test[0], test[1]);
            var result = GbisDestinationEnricher.enrich(original.normalized().routes(), GbisLocalBundle.replay(path, KEY).normalized());
            assertThat(result.skippedRoutes()).containsEntry(test[2], 1);
            assertThat(TagoGbisOfflineSnapshotAssembler.withGbis(tago, path, KEY).normalized()).isEqualTo(original.normalized());
        }
    }
    @Test void requested_missing_or_damaged_bundle_never_falls_back() throws Exception {
        var tago = tago(); var gbis = gbis(temp, "v1", "turn", "end");
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(temp.resolve("missing"), gbis, KEY)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(tago, temp.resolve("missing"), KEY)).isInstanceOf(Exception.class);
        Files.writeString(gbis.resolve("route.dat"), "tampered");
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY)).isInstanceOf(IllegalStateException.class);
        assertThat(TagoGbisOfflineSnapshotAssembler.tagoOnly(tago, KEY).gbis()).isNull();
        Files.writeString(tago.resolve("00000000.json"), "tampered");
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.tagoOnly(tago, KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejects_manifest_and_version_tampering() throws Exception {
        var tago = tago(); var gbis = gbis(temp, "v1", "turn", "end");
        var manifest = gbis.resolve("manifest.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":\"v2\""));
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY)).isInstanceOf(IllegalStateException.class);
        Files.writeString(manifest, "{}");
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejects_base_info_version_mismatch_even_when_hash_is_updated() throws Exception {
        var tago = tago(); var gbis = gbis(temp, "v1", "turn", "end");
        var base = gbis.resolve("base-info.json");
        byte[] original = Files.readAllBytes(base);
        String bad = Files.readString(base).replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":\"v2\"");
        byte[] bytes = bad.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(base, bytes);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var manifest = gbis.resolve("manifest.json");
        String oldHash = java.util.HexFormat.of().formatHex(digest.digest(original));
        String newHash = java.util.HexFormat.of().formatHex(digest.digest(bytes));
        Files.writeString(manifest, Files.readString(manifest).replace(oldHash, newHash));
        assertThatThrownBy(() -> TagoGbisOfflineSnapshotAssembler.withGbis(tago, gbis, KEY)).isInstanceOf(IllegalStateException.class);
    }
}
