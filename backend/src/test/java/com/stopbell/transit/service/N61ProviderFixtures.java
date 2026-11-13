package com.stopbell.transit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Offline replay only; both HTTP responses pass through the production XML reader. */
final class N61ProviderFixtures {
    static final String VEHICLE_ID = "120003281";

    static List<BoardingRouteSnapshot.Occurrence> occurrences() throws IOException {
        return new ClassPathResource("transit/n61/occurrences.tsv").getContentAsString(StandardCharsets.UTF_8)
                .lines().skip(1).map(line -> {
                    String[] columns = line.split("\t", -1);
                    int order = Integer.parseInt(columns[0]);
                    return new BoardingRouteSnapshot.Occurrence((long) order, columns[1], order, columns[2]);
                }).toList();
    }

    static SeoulBusVehicleLocationResponse roster() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {}).andRespond(withSuccess(
                new ClassPathResource("transit/n61/getBusPosByRtid.xml"), MediaType.APPLICATION_XML));
        var response = new SeoulBusVehicleLocationClient(builder.build(), properties()).fetchVehicleLocations(
                new VehicleLocationRequest<>("100100589", new SeoulBusVehicleLocationRequestContext()));
        server.verify();
        return response;
    }

    static SeoulBusVehicleDetailResponse detail() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {}).andRespond(withSuccess(
                new ClassPathResource("transit/n61/getBusPosByVehId.xml"), MediaType.APPLICATION_XML));
        var response = new SeoulBusVehicleDetailClient(builder.build(), properties()).fetchVehicleDetail(VEHICLE_ID);
        server.verify();
        return response;
    }

    // Only 4601 has an authorized detail sample; do not invent detail for the other vehicles.
    static SeoulBusVehicleLocationResponse selectedRoster() {
        var response = roster();
        return new SeoulBusVehicleLocationResponse(response.header(), new SeoulBusVehicleLocationResponse.Body(
                response.body().itemList().stream().filter(item -> VEHICLE_ID.equals(item.vehId())).toList()));
    }

    private static TransitClientProperties.Seoul properties() {
        return new TransitClientProperties.Seoul("http://ws.bus.go.kr", "fixture-key");
    }
}
