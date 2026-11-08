package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagoLocalBundleTest {
    @TempDir Path temp;
    private static final String KEY = "secret-service-key";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String page(String item, int total, int number) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + item + "},\"totalCount\":" + total + ",\"pageNo\":" + number + "}}}";
    }
    private TagoLocalBundle.Writer writer() throws Exception { return TagoLocalBundle.create(temp.toRealPath(), List.of("31010"), KEY); }
    private void city(TagoLocalBundle.Writer w) throws Exception {
        w.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0), bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}"));
    }
    private void route(TagoLocalBundle.Writer w, int page, int total, String id) throws Exception {
        w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, "31010", null, page), bytes(page("{\"routeid\":\"" + id + "\",\"routeno\":\"1\"}", total, page)));
    }
    private void stops(TagoLocalBundle.Writer w, String route) throws Exception {
        w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTE_STOPS, "31010", route, 1), bytes(page("[{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1},{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":2}]", 2, 1)));
    }
    private TagoLocalBundle.Writer full() throws Exception {
        var w = writer(); city(w); route(w, 1, 2, "r2"); route(w, 2, 2, "r1"); stops(w, "r2"); stops(w, "r1"); return w;
    }
    @Test void saves_replays_exact_bytes_order_and_repeated_stops_without_http() throws Exception {
        var w = full(); var original = w.complete(); var replay = TagoLocalBundle.replayForTest(w.directory(), KEY);
        assertThat(replay).isEqualTo(original);
        assertThat(replay.normalized().routes()).extracting(r -> r.externalRouteId()).containsExactly("r2", "r1");
        assertThat(replay.normalized().routes().getFirst().occurrences()).extracting(o -> o.externalStopId()).containsExactly("s", "s");
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isTrue();
        assertThat(Files.readString(w.directory().resolve("manifest.json"))).doesNotContain(KEY).contains("\"complete\":true");
        assertThat(Files.readString(w.directory().resolve("00000001.json"))).isEqualTo(page("{\"routeid\":\"r2\",\"routeno\":\"1\"}", 2, 1));
    }
    @Test void missing_and_tampered_files_are_rejected() throws Exception {
        var a = full(); a.complete(); Files.delete(a.directory().resolve("00000001.json"));
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(a.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        var b = full(); b.complete(); Files.writeString(b.directory().resolve("00000001.json"), "tampered");
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(b.directory(), KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void incomplete_bundle_and_missing_last_page_are_rejected() throws Exception {
        var w = writer(); city(w); route(w, 1, 2, "r");
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
    }
    @Test void duplicate_route_and_request_are_rejected() throws Exception {
        var w = writer(); city(w); route(w, 1, 2, "r"); route(w, 2, 2, "r"); stops(w, "r");
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        var duplicate = writer(); city(duplicate);
        assertThatThrownBy(() -> city(duplicate)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(duplicate::complete).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejects_reflected_credentials_and_never_publishes_manifest() throws Exception {
        var w = writer();
        assertThatThrownBy(() -> w.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0), bytes("{\"serviceKey\":\"" + KEY + "\"}")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(KEY);
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
    }

    @Test void captured_snapshot_matches_source_and_public_replay_rejects_partial_scope() throws Exception {
        var parser = new TagoMetadataClient(null, null);
        var fake = org.mockito.Mockito.mock(TagoMetadataClient.class, invocation -> { throw new AssertionError("Unexpected HTTP or operation"); });
        var cityBytes = bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}");
        var r2 = bytes(page("{\"routeid\":\"r2\",\"routeno\":\"1\"}", 2, 1));
        var r1 = bytes(page("{\"routeid\":\"r1\",\"routeno\":\"1\"}", 2, 2));
        var st = bytes(page("[{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1},{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":2}]", 2, 1));
        org.mockito.Mockito.doReturn(parser.parseCityCodes(cityBytes)).when(fake).cityCodes();
        org.mockito.Mockito.doReturn(parser.parseRoutes(r2)).when(fake).routesWithRaw("31010", 1);
        org.mockito.Mockito.doReturn(parser.parseRoutes(r1)).when(fake).routesWithRaw("31010", 2);
        org.mockito.Mockito.doReturn(parser.parseRouteStops(st)).when(fake).routeStopsWithRaw("31010", "r2", 1);
        org.mockito.Mockito.doReturn(parser.parseRouteStops(st)).when(fake).routeStopsWithRaw("31010", "r1", 1);
        var expected = new TagoMetadataSource(fake, List.of("31010")).fetchSnapshotWithRawRows();
        var w = full(); var saved = w.complete();
        assertThat(saved).isEqualTo(expected);
        assertThat(TagoLocalBundle.replayForTest(w.directory(), KEY)).isEqualTo(expected);
        assertThatThrownBy(() -> TagoLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void manifest_incomplete_or_size_changed_is_rejected() throws Exception {
        var w = full(); w.complete(); var file = w.directory().resolve("manifest.json");
        String manifest = Files.readString(file);
        Files.writeString(file, manifest.replace("\"complete\":true", "\"complete\":false"));
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        Files.writeString(file, manifest.replaceFirst("\"size\":[0-9]+", "\"size\":1"));
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void incomplete_city_and_unexpected_response_are_rejected() throws Exception {
        var w = TagoLocalBundle.create(temp.toRealPath(), List.of("31010", "31020"), KEY);
        city(w); route(w, 1, 1, "r"); stops(w, "r");
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        var extra = full();
        extra.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, "31010", null, 3), bytes(page("[]", 0, 3)));
        assertThatThrownBy(extra::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test void public_full_scope_is_replayable_without_network() throws Exception {
        var w = TagoLocalBundle.create(temp.toRealPath(), KEY);
        var cities = w.cityOrder();
        assertThat(cities).hasSize(31).doesNotHaveDuplicates();
        String items = cities.stream().map(c -> "{\"citycode\":\"" + c + "\"}").collect(java.util.stream.Collectors.joining(",", "[", "]"));
        w.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0), bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "}}}}"));
        for (int i = 0; i < cities.size(); i++) {
            String c = cities.get(i);
            w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, c, null, 1), bytes(page(i == 0 ? "{\"routeid\":\"r\",\"routeno\":\"1\"}" : "[]", i == 0 ? 1 : 0, 1)));
            if (i == 0) w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTE_STOPS, c, "r", 1), bytes(page("{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1}", 1, 1)));
        }
        var original = w.complete();
        assertThat(TagoLocalBundle.replay(w.directory(), KEY)).isEqualTo(original);
        assertThat(original.normalized().routes()).hasSize(1);
    }
    @Test void symlink_response_and_repository_directory_are_rejected() throws Exception {
        var w = full(); w.complete();
        var response = w.directory().resolve("00000001.json");
        var replacement = temp.resolve("replacement"); Files.copy(response, replacement); Files.delete(response); Files.createSymbolicLink(response, replacement);
        assertThatThrownBy(() -> TagoLocalBundle.replayForTest(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TagoLocalBundle.create(Path.of("").toAbsolutePath().toRealPath(), KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void write_failure_leaves_no_complete_manifest() throws Exception {
        var w = writer();
        Files.writeString(w.directory().resolve("00000000.json"), "occupied");
        assertThatThrownBy(() -> city(w)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
    }
    @Test void preserves_recorded_city_order_across_replay() throws Exception {
        var w = TagoLocalBundle.create(temp.toRealPath(), List.of("31020", "31010"), KEY);
        w.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0), bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[{\"citycode\":\"31010\"},{\"citycode\":\"31020\"}]}}}}"));
        for (String c : w.cityOrder()) {
            w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, c, null, 1), bytes(page("{\"routeid\":\"r" + c + "\",\"routeno\":\"1\"}", 1, 1)));
            w.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTE_STOPS, c, "r" + c, 1), bytes(page("{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1}", 1, 1)));
        }
        var first = w.complete();
        assertThat(TagoLocalBundle.replayForTest(w.directory(), KEY)).isEqualTo(first);
        assertThat(first.normalized().routes()).extracting(r -> r.cityCode()).containsExactly("31020", "31010");
    }
}
