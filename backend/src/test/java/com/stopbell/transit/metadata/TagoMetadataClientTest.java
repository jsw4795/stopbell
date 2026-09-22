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
