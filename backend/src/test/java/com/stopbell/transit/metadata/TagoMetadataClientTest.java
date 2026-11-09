package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

class TagoMetadataClientTest {

    @Test
    @DisplayName("빈 body가 있는 Provider 오류는 정상 데이터 역직렬화 전에 분류한다")
    void classifies_provider_error_before_body_deserialization() {
        String response = "{\"response\":{\"header\":{\"resultCode\":\"99\",\"resultMsg\":\"가용한 세션이 존재하지 않습니다. (40/40)\"},\"body\":\"\"}}";
        byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.parseRouteStops(bytes)).isInstanceOfSatisfying(TransitProviderClientException.class, e -> {
            assertThat(e.failureKind()).isEqualTo(TransitProviderClientFailureKind.PROVIDER);
            assertThat(e.providerResultCode()).isEqualTo("99");
        });
        assertThat(client.isSessionUnavailable(bytes)).isTrue();
    }

    @Test
    void parses_saved_pages_without_http_and_preserves_raw_fields() {
        var city = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}";
        var route = pageJson("{\"routeid\":\"r\",\"routeno\":\"1\",\"extra\":\"kept\"}", 1, 1);
        var stop = pageJson("{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1}", 1, 1);
        var charset = java.nio.charset.StandardCharsets.UTF_8;
        assertThat(client.parseCityCodes(city.getBytes(charset))).extracting(TagoMetadataClient.City::citycode).containsExactly("31010");
        assertThat(client.parseRoutes(route.getBytes(charset)).items().getFirst().fields().get("extra").asString()).isEqualTo("kept");
        assertThat(client.parseRouteStops(stop.getBytes(charset)).items().getFirst().normalized().nodeid()).isEqualTo("s");
        assertThatThrownBy(() -> client.parseRoutes("{}".getBytes(charset))).isInstanceOf(TransitProviderClientException.class);
        server.verify();
    }

    @Test
    void archive_receives_exact_page_bytes_and_failure_propagates() {
        String body = " {\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}\r\n";
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json").andRespond(json(body));
        var captured = new java.util.ArrayList<byte[]>();
        assertThat(client.cityCodes(captured::add)).hasSize(1);
        assertThat(captured).singleElement().isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.verify();
    }

    @Test
    void rejects_reflected_credentials_without_storing_content() {
        String body = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\",\"serviceKey\":\"test-service-key\"}}}}}";
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json").andRespond(json(body));
        var captured = new java.util.ArrayList<byte[]>();
        assertThatThrownBy(() -> client.cityCodes(captured::add)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("test-service-key");
        assertThat(captured).isEmpty();
        server.verify();
    }

    @Test
    void archive_callback_failure_is_not_swallowed() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}"));
        assertThatThrownBy(() -> client.cityCodes(bytes -> { throw new IllegalStateException("archive failed"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("archive failed");
        server.verify();
    }

    @Test
    void http_failure_does_not_expose_request_uri_or_response_body() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError()
                        .body("test-service-key https://provider.test/secret"));
        assertThatThrownBy(() -> client.cityCodes(bytes -> { }))
                .isInstanceOf(TransitProviderClientException.class).hasNoCause()
                .hasMessageNotContaining("test-service-key").hasMessageNotContaining("provider.test");
        server.verify();
    }

    @Test
    void invalid_credential_uri_does_not_expose_key() {
        var invalid = new TagoMetadataClient(RestClient.create(), new TransitClientProperties.Tago(
                "https://provider.test/tago", "https://provider.test/tago-metadata", "fake%invalid-key"));
        assertThatThrownBy(invalid::cityCodes).isInstanceOf(TransitProviderClientException.class)
                .hasNoCause().hasMessageNotContaining("fake%invalid-key");
    }

    @Test
    void identifiers_distinguish_cities_routes_and_pages_without_exposing_them() {
        String special = "../../route/" + "x".repeat(500);
        String key = TagoMetadataSource.archiveKey("stops", "31010", special, 1);
        assertThat(key).matches("tago_[0-9a-f]{64}");
        assertThat(key).isEqualTo(TagoMetadataSource.archiveKey("stops", "31010", special, 1));
        assertThat(key).isNotEqualTo(TagoMetadataSource.archiveKey("stops", "31020", special, 1))
                .isNotEqualTo(TagoMetadataSource.archiveKey("stops", "31010", special, 2))
                .isNotEqualTo(TagoMetadataSource.archiveKey("stops", "31010", special + "x", 1));
        assertThatThrownBy(() -> TagoMetadataSource.archiveKey("stops", "31010", "\uD800", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void source_archives_distinct_cities_and_route_pages_without_extra_requests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer multi = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        String base = "https://provider.test/tago/";
        multi.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(base + "getCtyCodeList?serviceKey=test-service-key&_type=json"))
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[{\"citycode\":\"31010\"},{\"citycode\":\"31020\"}]}}}}"));
        for (String city : java.util.List.of("31010", "31020")) {
            int pages = city.equals("31010") ? 2 : 1;
            for (int p = 1; p <= pages; p++) {
                String routeId = city + "r" + p;
                String routeUrl = base + "getRouteNoList?serviceKey=test-service-key&pageNo=" + p + "&numOfRows=1000&_type=json&cityCode=" + city;
                String stopUrl = base + "getRouteAcctoThrghSttnList?serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=" + city + "&routeId=" + routeId;
                multi.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(routeUrl))
                        .andRespond(json(pageJson("{\"routeid\":\"" + routeId + "\",\"routeno\":\"1\"}", pages, p)));
                multi.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(stopUrl))
                        .andRespond(json(pageJson("{\"nodeid\":\"s1\",\"nodenm\":\"A\",\"nodeord\":1}", 1, 1)));
            }
        }
        var client = new TagoMetadataClient(builder.build(), new TransitClientProperties.Tago("https://provider.test/unused", base.substring(0, base.length() - 1), "test-service-key"));
        var archive = org.mockito.Mockito.mock(com.stopbell.transit.service.TransitMetadataArchiveService.class);
        var snapshot = new TagoMetadataSource(client, 0, java.util.Set.of("31010", "31020")).fetchSnapshotWithRawRows(7, archive);
        assertThat(snapshot.normalized().routes()).hasSize(3);
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(archive, org.mockito.Mockito.times(7)).store(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(com.stopbell.transit.domain.TransitSourceSystem.TAGO), keys.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates().hasSize(7);
        multi.verify();
    }

    private static String pageJson(String item, int total, int page) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + item + "},\"totalCount\":" + total + ",\"pageNo\":" + page + "}}}";
    }

    private MockRestServiceServer server;
    private TagoMetadataClient client;

    @BeforeEach
    void set_up() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TagoMetadataClient(builder.build(), new TransitClientProperties.Tago(
                "https://provider.test/tago", "https://provider.test/tago-metadata", "test-service-key"
        ));
    }

    @Test
    @DisplayName("TAGO cityCode JSON 응답을 metadata 목록으로 변환한다")
    void city_codes_with_valid_response_maps_list() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                        {"citycode":"31010","cityname":"수원시"}]}}}}
                        """));

        java.util.List<TagoMetadataClient.City> cities = client.cityCodes();

        assertThat(cities).containsExactly(new TagoMetadataClient.City("31010", "수원시"));
        server.verify();
    }

    @Test
    @DisplayName("TAGO Route JSON 응답을 metadata page로 변환한다")
    void routes_with_valid_response_maps_page() {
        expect(TagoMetadataClient.ROUTES,
                "serviceKey=test-service-key&pageNo=3&numOfRows=1000&_type=json&cityCode=31010")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                        {"routeid":"GGB200000112","routeno":"7000"}]},"totalCount":1,"pageNo":3}}}
                        """));

        TagoMetadataClient.Page<TagoMetadataClient.Route> page = client.routes("31010", 3);

        assertThat(page.items()).containsExactly(new TagoMetadataClient.Route("GGB200000112", "7000"));
        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.pageNo()).isEqualTo(3);
        server.verify();
    }

    @Test
    @DisplayName("TAGO 단일 노선 객체를 metadata page로 변환한다")
    void routes_with_single_item_maps_page() {
        expect(TagoMetadataClient.ROUTES,
                "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31130")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                        {"routeid":"GGB222000028","routeno":165,"routetp":"일반버스"}},
                        "totalCount":1,"pageNo":1}}}
                        """));

        TagoMetadataClient.Page<TagoMetadataClient.Route> page = client.routes("31130", 1);

        assertThat(page.items()).containsExactly(new TagoMetadataClient.Route("GGB222000028", "165"));
        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.pageNo()).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("단일 TAGO 노선의 모든 원본 필드와 JSON 타입을 보존한다")
    void raw_route_preserves_fields_and_types() {
        expect(TagoMetadataClient.ROUTES, "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31130")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                        {"routeid":"r1","routeno":165,"routetp":"일반버스","extra":{"flag":true},"text":"123"}},
                        "totalCount":1,"pageNo":1}}}
                        """));
        var item = client.routesWithRaw("31130", 1).items().getFirst();
        assertThat(item.normalized()).isEqualTo(new TagoMetadataClient.Route("r1", "165"));
        assertThat(item.fields().get("routeno").isNumber()).isTrue();
        assertThat(item.fields().get("text").isString()).isTrue();
        assertThat(item.fields().get("routetp").asString()).isEqualTo("일반버스");
        assertThat(item.fields().get("extra").get("flag").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("TAGO 경유 정류장 배열의 추가 원본 필드를 보존한다")
    void raw_stops_preserve_array_fields() {
        expect(TagoMetadataClient.ROUTE_STOPS, "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=r1")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                        {"nodeid":"s1","nodenm":"A","nodeord":1,"extra":null},
                        {"nodeid":"s1","nodenm":"A","nodeord":2,"extra":42}]},"totalCount":2,"pageNo":1}}}
                        """));
        var items = client.routeStopsWithRaw("31010", "r1", 1).items();
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().fields().get("extra").isNull()).isTrue();
        assertThat(items.get(1).fields().get("extra").asInt()).isEqualTo(42);
        assertThat(items.get(1).normalized().nodeord()).isEqualTo(2);
        server.verify();
    }

    @Test
    @DisplayName("원본 수집은 추가 요청 없이 모든 페이지와 반복 정류장을 정규화 결과와 함께 반환한다")
    void source_collects_raw_and_normalized_from_same_requests() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}"));
        expect(TagoMetadataClient.ROUTES, "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010")
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"routeid\":\"r:1\",\"routeno\":123,\"routetp\":\"일반\"}},\"totalCount\":1,\"pageNo\":1}}}"));
        expect(TagoMetadataClient.ROUTE_STOPS, "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=r:1")
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":1,\"extra\":true}},\"totalCount\":2,\"pageNo\":1}}}"));
        expect(TagoMetadataClient.ROUTE_STOPS, "serviceKey=test-service-key&pageNo=2&numOfRows=1000&_type=json&cityCode=31010&routeId=r:1")
                .andRespond(json("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[{\"nodeid\":\"s\",\"nodenm\":\"A\",\"nodeord\":2,\"extra\":2}]},\"totalCount\":2,\"pageNo\":2}}}"));
        var result = new TagoMetadataSource(client, 0, java.util.Set.of("31010")).fetchSnapshotWithRawRows();
        assertThat(result.normalized().routes()).singleElement().satisfies(route -> {
            assertThat(route.routeNumber()).isEqualTo("123");
            assertThat(route.occurrences()).extracting("externalStopId").containsExactly("s", "s");
            assertThat(route.occurrences()).extracting("stopOrder").containsExactly(1, 2);
        });
        assertThat(result.rawRows()).extracting("sourceKey").containsExactly("r:1", "3:r:1:1", "3:r:1:2");
        assertThat(result.rawRows().get(2).fields().get("extra").asInt()).isEqualTo(2);
        assertThat(result.rawRows()).allSatisfy(row -> assertThat(row.sourceSystem()).isEqualTo(com.stopbell.transit.domain.TransitSourceSystem.TAGO));
        server.verify();
    }

    @Test
    @DisplayName("TAGO Route Stop JSON 응답을 metadata page로 변환한다")
    void route_stops_with_valid_response_maps_page() {
        expect(TagoMetadataClient.ROUTE_STOPS,
                "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=GGB200000112")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{
                        "nodeid":"GGB228001174","nodenm":"수원역","nodeord":1,
                        "gpslati":37.2402833,"gpslong":127.0824}]},"totalCount":1,"pageNo":1}}}
                        """));

        TagoMetadataClient.Page<TagoMetadataClient.Stop> page = client.routeStops("31010", "GGB200000112", 1);

        assertThat(page.items()).singleElement().satisfies(stop -> {
            assertThat(stop.nodeid()).isEqualTo("GGB228001174");
            assertThat(stop.nodenm()).isEqualTo("수원역");
            assertThat(stop.nodeord()).isEqualTo(1);
            assertThat(stop.gpslati()).isEqualByComparingTo("37.2402833");
            assertThat(stop.gpslong()).isEqualByComparingTo("127.0824");
        });
        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.pageNo()).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("TAGO logical failure는 정상 empty가 아닌 PROVIDER 예외로 전달한다")
    void provider_failure_is_not_converted_to_empty_page() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"99"},"body":{"items":{"item":[]}}}}
                        """));

        assertFailure(TransitProviderClientFailureKind.PROVIDER, client::cityCodes);
    }

    @Test
    @DisplayName("TAGO cityCode 응답에 item 목록이 없으면 PROTOCOL 예외로 전달한다")
    void malformed_city_code_response_is_not_converted_to_empty_list() {
        expect(TagoMetadataClient.CITY_CODES, "serviceKey=test-service-key&_type=json")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{}}}}
                        """));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, client::cityCodes);
    }

    @Test
    @DisplayName("TAGO metadata protocol 손상은 정상 empty가 아닌 PROTOCOL 예외로 전달한다")
    void malformed_response_is_not_converted_to_empty_page() {
        expect(TagoMetadataClient.ROUTES,
                "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010")
                .andRespond(json("""
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[]},"pageNo":1}}}
                        """));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, () -> client.routes("31010", 1));
    }

    @Test
    @DisplayName("TAGO malformed JSON은 정상 empty가 아닌 PROTOCOL 예외로 전달한다")
    void malformed_json_is_not_converted_to_empty_page() {
        expect(TagoMetadataClient.ROUTE_STOPS,
                "serviceKey=test-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=GGB200000112")
                .andRespond(json("{"));

        assertFailure(TransitProviderClientFailureKind.PROTOCOL, () -> client.routeStops("31010", "GGB200000112", 1));
    }

    private ResponseActions expect(String operation, String query) {
        return server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/tago-metadata/" + operation);
            assertThat(request.getURI().getQuery()).isEqualTo(query);
        });
    }

    private org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private void assertFailure(TransitProviderClientFailureKind failureKind, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.failureKind()).isEqualTo(failureKind);
                    assertThat(exception.provider()).isEqualTo(TransitProvider.TAGO);
                });
        server.verify();
    }
}
