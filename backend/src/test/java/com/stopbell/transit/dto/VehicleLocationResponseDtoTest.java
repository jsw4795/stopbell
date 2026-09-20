package com.stopbell.transit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VehicleLocationResponseDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TAGO 차량 위치 응답의 envelope와 차량 위치 item을 역직렬화한다")
    void deserialize_tago_vehicle_location_response() throws Exception {
        TagoVehicleLocationResponse response = objectMapper.readValue("""
                {
                  "response": {
                    "header": {
                      "resultCode": "00",
                      "resultMsg": "NORMAL SERVICE."
                    },
                    "body": {
                      "items": {
                        "item": [{
                          "vehicleno": "경기70바5770",
                          "nodeid": "GGB228001174",
                          "nodeord": 1,
                          "gpslati": 37.2402833,
                          "gpslong": 127.0824,
                          "routenm": "7000"
                        }]
                      },
                      "totalCount": 1,
                      "pageNo": 1,
                      "numOfRows": 10
                    }
                  }
                }
                """, TagoVehicleLocationResponse.class);

        assertThat(response.response().header().resultCode()).isEqualTo("00");
        assertThat(response.response().header().resultMsg()).isEqualTo("NORMAL SERVICE.");
        assertThat(response.response().body().items().item()).singleElement().satisfies(item -> {
            assertThat(item.vehicleNo()).isEqualTo("경기70바5770");
            assertThat(item.nodeId()).isEqualTo("GGB228001174");
            assertThat(item.nodeOrder()).isEqualTo(1);
            assertThat(item.gpsLatitude()).isEqualByComparingTo("37.2402833");
            assertThat(item.gpsLongitude()).isEqualByComparingTo("127.0824");
        });
    }

    @Test
    @DisplayName("TAGO의 정상 empty 차량 위치 응답을 역직렬화한다")
    void deserialize_empty_tago_vehicle_location_response() throws Exception {
        TagoVehicleLocationResponse response = objectMapper.readValue("""
                {
                  "response": {
                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                    "body": {
                      "items": {"item": []},
                      "totalCount": 0,
                      "pageNo": 1,
                      "numOfRows": 10
                    }
                  }
                }
                """, TagoVehicleLocationResponse.class);

        assertThat(response.response().body().items().item()).isEmpty();
        assertThat(response.response().body().totalCount()).isZero();
    }

    @Test
    @DisplayName("서울 노선 전체 차량 위치 응답의 envelope와 차량 위치 item을 역직렬화한다")
    void deserialize_seoul_bus_vehicle_location_response() throws Exception {
        SeoulBusVehicleLocationResponse response = objectMapper.readValue("""
                {
                  "comMsgHeader": {"requestMsgID": "ignored"},
                  "msgHeader": {
                    "headerCd": "0",
                    "headerMsg": "정상적으로 처리되었습니다."
                  },
                  "msgBody": {
                    "itemList": [{
                      "vehId": "111033668",
                      "plainNo": "서울75사2646",
                      "sectOrd": 21,
                      "sectionId": "112000001",
                      "stopFlag": 0,
                      "dataTm": "20260920190434",
                      "gpsX": 126.904572,
                      "gpsY": 37.575465,
                      "nextStId": "112000003",
                      "isrunyn": "1",
                      "congetion": "3",
                      "providerExtra": "ignored"
                    }]
                  }
                }
                """, SeoulBusVehicleLocationResponse.class);

        assertThat(response.header().headerCd()).isEqualTo("0");
        assertThat(response.header().headerMsg()).isEqualTo("정상적으로 처리되었습니다.");
        assertThat(response.body().itemList()).singleElement().satisfies(item -> {
            assertThat(item.vehId()).isEqualTo("111033668");
            assertThat(item.plainNo()).isEqualTo("서울75사2646");
            assertThat(item.sectOrd()).isEqualTo(21);
            assertThat(item.sectionId()).isEqualTo("112000001");
            assertThat(item.stopFlag()).isZero();
            assertThat(item.dataTm()).isEqualTo("20260920190434");
            assertThat(item.gpsX()).isEqualByComparingTo(new BigDecimal("126.904572"));
            assertThat(item.gpsY()).isEqualByComparingTo(new BigDecimal("37.575465"));
            assertThat(item.nextStId()).isEqualTo("112000003");
            assertThat(item.isrunyn()).isEqualTo("1");
            assertThat(item.congetion()).isEqualTo("3");
        });
    }

    @Test
    @DisplayName("서울의 결과 없음 차량 위치 응답을 역직렬화한다")
    void deserialize_empty_seoul_bus_vehicle_location_response() throws Exception {
        SeoulBusVehicleLocationResponse response = objectMapper.readValue("""
                {
                  "msgHeader": {
                    "headerCd": "4",
                    "headerMsg": "결과가 없습니다."
                  },
                  "msgBody": {"itemList": []}
                }
                """, SeoulBusVehicleLocationResponse.class);

        assertThat(response.header().headerCd()).isEqualTo("4");
        assertThat(response.header().headerMsg()).isEqualTo("결과가 없습니다.");
        assertThat(response.body().itemList()).isEmpty();
    }
}
