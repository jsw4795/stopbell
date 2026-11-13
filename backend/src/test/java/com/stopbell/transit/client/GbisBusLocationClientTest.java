package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GbisBusLocationClientTest {

    private MockRestServiceServer server;
    private GbisBusLocationClient client;

    @BeforeEach
    void set_up() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GbisBusLocationClient(builder.build(), "https://provider.test/gbis/getBusLocationListv2", "test-key");
    }

    private void respond(String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://provider.test/gbis/getBusLocationListv2")))
                .andExpect(queryParam("routeId", "222000209"))
                .andExpect(queryParam("format", "json"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @CsvSource({"single-object, 1", "array, 2", "empty-array, 0", "missing-list, 0", "null-list, 0",
            "missing-body, 0", "no-data, 0", "missing-optional-fields, 1"})
    @DisplayName("GBIS 단일 객체·배열·빈 응답·누락 필드 fixture를 HTTP 응답에서 파싱한다")
    void parses_response_fixtures(String fixture, int count) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://provider.test/gbis")))
                .andExpect(queryParam("routeId", "229000013"))
                .andRespond(withSuccess(new ClassPathResource("transit/gbis-vehicle-location/" + fixture + ".json"),
                        MediaType.APPLICATION_JSON));

        var vehicles = GbisBusLocationClient.vehicles(client.fetch("229000013"));

        assertThat(vehicles).hasSize(count);
        if (fixture.equals("single-object")) {
            assertThat(vehicles.getFirst().plateNo()).isEqualTo("경기00가1001");
            assertThat(vehicles.getFirst().stationSeq()).isEqualTo(1);
            assertThat(vehicles.getFirst().remainSeats()).isEqualTo(7);
        }
        if (fixture.equals("array")) {
            assertThat(vehicles).extracting(v -> v.plateNo()).containsExactly("경기00가1001", "경기00가1002");
        }
        if (fixture.equals("missing-optional-fields")) {
            assertThat(vehicles.getFirst().stationSeq()).isNull();
            assertThat(vehicles.getFirst().remainSeats()).isNull();
            assertThat(vehicles.getFirst().crowded()).isNull();
        }
        server.verify();
    }

    @Test
    @DisplayName("GBIS 정상 응답의 좌석·혼잡도·정류장 순번을 파싱한다")
    void fetch_parses_vehicles() {
        respond("""
                {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-11-12 16:55:32.118","resultCode":0,"resultMessage":"ok"},
                "msgBody":{"busLocationList":[{"crowded":3,"lowPlate":0,"plateNo":"경기74아3499","remainSeatCnt":2,
                "routeId":222000209,"routeTypeCd":14,"stateCd":0,"stationId":277103313,"stationSeq":24,"taglessCd":0,"vehId":222001158}]}}}
                """);

        var response = client.fetch("222000209");

        assertThat(response.response().msgHeader().queryTime()).isEqualTo("2026-11-12 16:55:32.118");
        assertThat(GbisBusLocationClient.vehicles(response)).singleElement().satisfies(v -> {
            assertThat(v.plateNo()).isEqualTo("경기74아3499");
            assertThat(v.stationSeq()).isEqualTo(24);
            assertThat(v.stationId()).isEqualTo(277103313L);
            assertThat(v.remainSeats()).isEqualTo(2);
            assertThat(v.crowded()).isEqualTo(3);
        });
        server.verify();
    }

    @Test
    @DisplayName("remainSeatCnt가 -1이거나 누락되면 좌석 미제공(null)으로 구분한다")
    void remain_seat_minus_one_or_missing_is_null() {
        respond("""
                {"response":{"msgHeader":{"resultCode":0},"msgBody":{"busLocationList":[
                {"plateNo":"A","remainSeatCnt":-1,"stationSeq":1},{"plateNo":"B","stationSeq":2},{"plateNo":"C","remainSeatCnt":0,"stationSeq":3}]}}}
                """);

        var vehicles = GbisBusLocationClient.vehicles(client.fetch("222000209"));

        assertThat(vehicles).extracting(v -> v.remainSeats()).containsExactly(null, null, 0);
    }

    @Test
    @DisplayName("결과 없음(resultCode 4)과 빈 목록은 빈 차량 목록으로 처리한다")
    void no_data_and_empty_list_are_empty() {
        respond("{\"response\":{\"msgHeader\":{\"resultCode\":4,\"resultMessage\":\"no data\"}}}");
        assertThat(GbisBusLocationClient.vehicles(client.fetch("222000209"))).isEmpty();
        server.reset();

        respond("{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busLocationList\":[]}}}");
        assertThat(GbisBusLocationClient.vehicles(client.fetch("222000209"))).isEmpty();
    }

    @Test
    @DisplayName("Provider 오류 코드, HTTP 오류, 형식 오류는 예외로 변환한다")
    void errors_throw() {
        respond("{\"response\":{\"msgHeader\":{\"resultCode\":30,\"resultMessage\":\"key\"}}}");
        assertThatThrownBy(() -> client.fetch("222000209")).isInstanceOf(TransitProviderClientException.class);
        server.reset();

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://provider.test/gbis")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.fetch("222000209")).isInstanceOf(TransitProviderClientException.class);
        server.reset();

        respond("{\"response\":{}}");
        assertThatThrownBy(() -> client.fetch("222000209")).isInstanceOf(TransitProviderClientException.class);
    }
}
