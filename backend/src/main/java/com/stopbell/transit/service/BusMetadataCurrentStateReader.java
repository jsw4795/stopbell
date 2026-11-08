package com.stopbell.transit.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stopbell.transit.domain.TransitProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads only the requested provider. Caller supplies a repeatable-read transaction. */
@Repository
public class BusMetadataCurrentStateReader {
    private final JdbcTemplate jdbc;

    public BusMetadataCurrentStateReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public BusMetadataDiffService.CurrentState read(TransitProvider provider) {
        Map<String, RouteRow> routes = new LinkedHashMap<>();
        jdbc.query("select external_route_id, route_number, city_code from bus_routes where provider = ? order by external_route_id",
                rs -> { routes.put(rs.getString(1), new RouteRow(rs.getString(2), rs.getString(3), new ArrayList<>())); }, provider.name());
        List<BusMetadataDiffService.Stop> stops = jdbc.query(
                "select external_stop_id, stop_name, latitude, longitude from bus_stops where provider = ? order by external_stop_id",
                (rs, n) -> new BusMetadataDiffService.Stop(provider, rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)), provider.name());
        jdbc.query("""
                select r.external_route_id, s.provider, s.external_stop_id, s.stop_name, s.latitude, s.longitude,
                       o.stop_order, o.destination_name
                from bus_route_stop_occurrences o join bus_routes r on r.id = o.route_id
                join bus_stops s on s.id = o.stop_id where r.provider = ?
                order by r.external_route_id, o.stop_order
                """, rs -> {
            if (!provider.name().equals(rs.getString(2))) throw new IllegalStateException("Occurrence references another provider's stop");
            routes.get(rs.getString(1)).occurrences().add(new BusStopOccurrenceMetadataSnapshot(
                    rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getInt(7), rs.getString(8)));
        }, provider.name());
        List<BusRouteMetadataSnapshot> result = new ArrayList<>(routes.size());
        routes.forEach((id, row) -> result.add(new BusRouteMetadataSnapshot(provider, id, row.number(), row.city(), row.occurrences())));
        return new BusMetadataDiffService.CurrentState(result, stops);
    }

    private record RouteRow(String number, String city, List<BusStopOccurrenceMetadataSnapshot> occurrences) { }
}
