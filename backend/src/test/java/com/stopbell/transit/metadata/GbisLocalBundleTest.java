package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GbisLocalBundleTest {
    private static final String KEY = "secret-service-key";
    private static final String URL = "http://openapi.gbis.go.kr/ws/download?file=route";
    private static final String BASE = """
            {"response":{"msgHeader":{"resultCode":0},"msgBody":{"baseInfoItem":{
            "areaDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=area","areaVersion":"v1",
            "routeDownloadUrl":"%s","routeVersion":"v1",
            "routeLineDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=line","routeLineVersion":"v1",
            "routeStationDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=station","routeStationVersion":"v1",
            "stationDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=stops","stationVersion":"v1",
            "vehicleDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=vehicles","vehicleVersion":"v1"}}}}
            """.formatted(URL);
    private static final String ROUTE = "routeId|routeName|turnSeq|extra^200|300|2| raw ";
    private static final String STATION = "routeId|routeName|upDown|staOrder|stationId|stationName|x|y^"
            + "200|300|상행|2|20|회차|127|37^200|300|상행|1|10|출발|127|37";
    @TempDir Path temp;
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private GbisLocalBundle.Writer writer() throws Exception { return GbisLocalBundle.create(temp.toRealPath(), KEY); }
    private void addAll(GbisLocalBundle.Writer w, String base, String route, String station) throws Exception {
        w.add(GbisLocalBundle.Source.BASE_INFO, bytes(base));
        w.add(GbisLocalBundle.Source.ROUTE, bytes(route));
        w.add(GbisLocalBundle.Source.ROUTE_STATION, bytes(station));
    }
    @Test void stores_bytes_manifest_and_replays_identical_snapshot() throws Exception {
        var w = writer(); addAll(w, BASE, ROUTE, STATION);
        var expected = GbisBulkMetadata.parseWithRaw(bytes(ROUTE), bytes(STATION), "v1", "v1");
        assertThat(w.complete()).isEqualTo(expected);
        assertThat(GbisLocalBundle.replay(w.directory(), KEY)).isEqualTo(expected);
        assertThat(Files.readAllBytes(w.directory().resolve("base-info.json"))).isEqualTo(bytes(BASE));
        assertThat(Files.readAllBytes(w.directory().resolve("route.dat"))).isEqualTo(bytes(ROUTE));
        assertThat(Files.readAllBytes(w.directory().resolve("route-station.dat"))).isEqualTo(bytes(STATION));
        String manifest = Files.readString(w.directory().resolve("manifest.json"));
        assertThat(manifest).contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(ROUTE))))
                .doesNotContain(KEY, URL);
        assertThat(expected.normalized().routes().get("200").stations()).extracting(GbisBulkMetadata.Station::order).containsExactly(1, 2);
        assertThat(expected.rawRows().getFirst().fields()).containsEntry("extra", " raw ");
    }
    @Test void rejects_missing_duplicate_and_incomplete_sources() throws Exception {
        var w = writer(); w.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE));
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GbisLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        var duplicate = writer(); duplicate.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE));
        assertThatThrownBy(() -> duplicate.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(duplicate::complete).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejects_missing_and_tampered_files_and_manifest() throws Exception {
        var w = writer(); addAll(w, BASE, ROUTE, STATION); w.complete();
        Path route = w.directory().resolve("route.dat"); Files.writeString(route, ROUTE.replace("300", "301"));
        assertThatThrownBy(() -> GbisLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        Files.delete(route);
        assertThatThrownBy(() -> GbisLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        Path manifest = w.directory().resolve("manifest.json"); Files.writeString(manifest, "{}");
        assertThatThrownBy(() -> GbisLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
        Files.delete(manifest);
        assertThatThrownBy(() -> GbisLocalBundle.replay(w.directory(), KEY)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejects_unsafe_urls_without_disclosing_secrets() throws Exception {
        for (String url : List.of("http://evil.test/a", "http://openapi.gbis.go.kr.evil.test/a",
                "http://user:password@openapi.gbis.go.kr/a", URL + "&serviceKey=" + KEY,
                URL + "&file=%73ecret", URL + "&token=secret", URL + "&apiKey=secret",
                URL + "&authorization=secret", "http://openapi.gbis.go.kr/" + KEY, "http://openapi.gbis.go.kr/token/secret")) {
            var w = writer();
            assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE.replace(URL, url))))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(KEY).hasMessageNotContaining(url).hasNoCause();
            assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rejects_other_url_fields_and_bad_versions() throws Exception {
        for (String base : List.of(BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"extra\":\"https://evil.test\""),
                BASE.replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":\"v2\""),
                BASE.replace("\"routeVersion\":\"v1\"", "\"routeVersion\":\"\""))) {
            var w = writer();
            assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(base))).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void invalid_bulk_and_unsafe_raw_prevent_completion() throws Exception {
        for (String route : List.of(ROUTE.replace("|2|", "|3|"), ROUTE.replace("|extra", "|token"))) {
            var w = writer(); addAll(w, BASE, route, STATION);
            assertThatThrownBy(w::complete).isInstanceOf(RuntimeException.class);
            assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
        }
        var w = writer(); addAll(w, BASE, ROUTE, STATION.replace("200|300|상행|1", "999|300|상행|1"));
        assertThatThrownBy(w::complete).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejects_duplicate_json_fields_and_unsafe_paths() throws Exception {
        var w = writer();
        String duplicate = BASE.replace("\"routeDownloadUrl\":", "\"routeDownloadUrl\":\"https://evil.test/?token=secret\",\"routeDownloadUrl\":");
        assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(duplicate))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GbisLocalBundle.create(Path.of("."), KEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GbisLocalBundle.create(Path.of(System.getProperty("user.dir")).toRealPath(), KEY)).isInstanceOf(IllegalArgumentException.class);
        Path link = temp.resolve("link"); Files.createSymbolicLink(link, temp.toRealPath());
        assertThatThrownBy(() -> GbisLocalBundle.create(link, KEY)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void no_overwrite_and_failed_publication_leave_no_manifest() throws Exception {
        var w = writer();
        Files.writeString(w.directory().resolve("base-info.json"), "existing");
        assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(BASE))).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(w.directory().resolve("base-info.json"))).isEqualTo("existing");
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
    }
    @Test void provider_snapshot_equals_offline_replay() throws Exception {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.test/base?format=json&serviceKey=" + KEY))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(BASE, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(ROUTE, org.springframework.http.MediaType.TEXT_PLAIN));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://openapi.gbis.go.kr/ws/download?file=station"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(STATION, org.springframework.http.MediaType.TEXT_PLAIN));
        var provider = new GbisMetadataClient(builder.build(), "https://provider.test/base", KEY).fetchWithRaw();
        server.verify();
        var w = writer(); addAll(w, BASE, ROUTE, STATION);
        assertThat(w.complete()).isEqualTo(provider);
        assertThat(GbisLocalBundle.replay(w.directory(), KEY)).isEqualTo(provider);
        server.verify();
    }
    @Test void tampering_before_completion_prevents_manifest() throws Exception {
        var w = writer(); addAll(w, BASE, ROUTE, STATION);
        Files.writeString(w.directory().resolve("route.dat"), ROUTE + "x");
        assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(w.directory().resolve("manifest.json"))).isFalse();
    }
    @Test void every_official_url_field_rejects_unsafe_values_without_disclosure() throws Exception {
        for (String field : List.of("areaDownloadUrl", "routeDownloadUrl", "routeLineDownloadUrl", "routeStationDownloadUrl", "stationDownloadUrl", "vehicleDownloadUrl")) {
            String prefix = "\"" + field + "\":\"";
            int start = BASE.indexOf(prefix) + prefix.length();
            int end = BASE.indexOf('"', start);
            for (String url : List.of("http://evil.test/a", "https://openapi.gbis.go.kr.evil.test/a",
                    "http://user:secret@openapi.gbis.go.kr/a", "http://openapi.gbis.go.kr/a?signature=fake",
                    "http://openapi.gbis.go.kr/a?token=secret", "http://openapi.gbis.go.kr/a?apiKey=secret",
                    "http://openapi.gbis.go.kr/a?authorization=secret", "http://openapi.gbis.go.kr/a?serviceKey=" + KEY,
                    "http://openapi.gbis.go.kr/a?file=%73ecret", "ftp://openapi.gbis.go.kr/a")) {
                String invalid = BASE.substring(0, start) + url + BASE.substring(end);
                var w = writer();
                assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(invalid)))
                        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(KEY).hasMessageNotContaining(url).hasNoCause();
                assertThat(Files.exists(w.directory().resolve("base-info.json"))).isFalse();
            }
        }
    }
    @Test void official_field_name_is_not_exempt_in_other_locations() throws Exception {
        String invalid = BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"areaDownloadUrl\":\"http://openapi.gbis.go.kr/a\"");
        var w = writer();
        assertThatThrownBy(() -> w.add(GbisLocalBundle.Source.BASE_INFO, bytes(invalid))).isInstanceOf(IllegalStateException.class);
    }
}
