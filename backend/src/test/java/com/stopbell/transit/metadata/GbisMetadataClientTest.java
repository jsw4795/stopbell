package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GbisMetadataClientTest {
    @Test
    void downloads_two_files_after_matching_version() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GbisMetadataClient client = new GbisMetadataClient(builder.build(), "https://provider.test/base", "key");
        server.expect(requestTo("https://provider.test/base?format=json&serviceKey=key"))
                .andRespond(withSuccess(response("1", "1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://openapi.gbis.go.kr/ws/download?file=route"))
                .andRespond(withSuccess("routeId|routeName|turnSeq^200|300|1", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://openapi.gbis.go.kr/ws/download?file=station"))
                .andRespond(withSuccess("routeId|routeName|upDown|staOrder|stationId|stationName|x|y^"
                        + "200|300|상행|1|10|종착|127|37", MediaType.TEXT_PLAIN));

        assertThat(client.fetch().routes()).containsKey("200");
        server.verify();
    }

    @Test
    void version_mismatch_fails_before_any_file_is_downloaded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GbisMetadataClient client = new GbisMetadataClient(builder.build(), "https://provider.test/base", "key");
        server.expect(requestTo("https://provider.test/base?format=json&serviceKey=key"))
                .andRespond(withSuccess(response("1", "2"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetch).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    @DisplayName("원본 수집은 기반정보와 각 파일을 한 번씩 요청하고 공식 버전을 보존한다")
    void fetches_raw_without_duplicate_requests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GbisMetadataClient client = new GbisMetadataClient(builder.build(), "https://provider.test/base", "key");
        server.expect(requestTo("https://provider.test/base?format=json&serviceKey=key"))
                .andRespond(withSuccess(response("20261001", "20261001"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://openapi.gbis.go.kr/ws/download?file=route"))
                .andRespond(withSuccess("routeId|routeName|turnSeq|routeTypeCd^200|300|1|014", MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://openapi.gbis.go.kr/ws/download?file=station"))
                .andRespond(withSuccess("routeId|routeName|upDown|staOrder|stationId|stationName|x|y^200|300|상행|1|10|종착|127|37", MediaType.TEXT_PLAIN));
        var snapshot = client.fetchWithRaw();
        assertThat(snapshot.sourceVersion()).isEqualTo("20261001");
        assertThat(snapshot.normalized().routes()).containsKey("200");
        assertThat(snapshot.rawRows()).hasSize(2);
        assertThat(snapshot.rawRows().getFirst().fields()).containsEntry("routeTypeCd", "014");
        server.verify();
    }

    @Test
    @DisplayName("원본 수집에서 버전 불일치와 누락을 거부한다")
    void raw_fetch_rejects_missing_or_mismatched_versions() {
        for (String responseBody : new String[]{response("1", "2"), response("", "").replace("\"routeVersion\":\"\"", "\"routeVersion\":null")}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            GbisMetadataClient client = new GbisMetadataClient(builder.build(), "https://provider.test/base", "key");
            server.expect(requestTo("https://provider.test/base?format=json&serviceKey=key"))
                    .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
            assertThatThrownBy(client::fetchWithRaw).isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }

    private static String response(String routeVersion, String stationVersion) {
        return """
                {"response":{"msgHeader":{"resultCode":0},"msgBody":{"baseInfoItem":{
                "routeDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=route",
                "routeVersion":"%s",
                "routeStationDownloadUrl":"http://openapi.gbis.go.kr/ws/download?file=station",
                "routeStationVersion":"%s"}}}}
                """.formatted(routeVersion, stationVersion);
    }
}
