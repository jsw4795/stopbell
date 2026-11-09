package com.stopbell.transit.metadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Synthetic full-scope captures for offline assembly and isolated preview tests. */
public final class OfflineBundleFixtures {
    public static final String KEY = "offline-test-service-key";
    private OfflineBundleFixtures() { }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String page(String items, int count) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items
                + "},\"totalCount\":" + count + ",\"pageNo\":1}}}";
    }
    public static Path tago(Path root, List<String> routeIds, Map<String, String> stops, String extra) throws Exception {
        var writer = TagoLocalBundle.create(root.toRealPath(), KEY);
        var cities = writer.cityOrder();
        String items = cities.stream().map(c -> "{\"citycode\":\"" + c + "\"}").collect(Collectors.joining(",", "[", "]"));
        writer.add(new TagoLocalBundle.Request(TagoMetadataClient.CITY_CODES, null, null, 0),
                bytes("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "}}}}"));
        for (int i = 0; i < cities.size(); i++) {
            String city = cities.get(i);
            String routes = i == 0 ? routeIds.stream().map(id -> "{\"routeid\":\"" + id
                    + "\",\"routeno\":\"300\",\"extra\":\"" + extra + "\"}").collect(Collectors.joining(",", "[", "]")) : "[]";
            writer.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTES, city, null, 1), bytes(page(routes, i == 0 ? routeIds.size() : 0)));
            if (i == 0) for (String id : routeIds) {
                String body = stops.get(id);
                int count = (int) body.chars().filter(c -> c == '{').count();
                writer.add(new TagoLocalBundle.Request(TagoMetadataClient.ROUTE_STOPS, city, id, 1), bytes(page(body, count)));
            }
        }
        writer.complete();
        return writer.directory();
    }
    public static String stops(String firstName, String secondName) {
        return "[{\"nodeid\":\"GGB10\",\"nodenm\":\"" + firstName + "\",\"nodeord\":1,\"extra\":\"raw\"},"
                + "{\"nodeid\":\"GGB10\",\"nodenm\":\"" + secondName + "\",\"nodeord\":2}]";
    }
    public static Path gbisBulk(Path root, String version, String routes, String stations) throws Exception {
        var writer = GbisLocalBundle.create(root.toRealPath(), KEY);
        String base = "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"baseInfoItem\":{"
                + "\"routeDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=route\",\"routeVersion\":\"" + version
                + "\",\"routeStationDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=station\",\"routeStationVersion\":\"" + version + "\"}}}}";
        writer.add(GbisLocalBundle.Source.BASE_INFO, bytes(base));
        writer.add(GbisLocalBundle.Source.ROUTE, bytes("routeId|routeName|turnSeq|extra^" + routes));
        writer.add(GbisLocalBundle.Source.ROUTE_STATION, bytes("routeId|routeName|upDown|staOrder|stationId|stationName|x|y^" + stations));
        writer.complete();
        return writer.directory();
    }
    public static Path gbis(Path root, String version, String turn, String end) throws Exception {
        return gbisBulk(root, version, "200|300|2|gbis raw", "200|300|상행|1|10|" + turn + "|127|37^200|300|하행|2|10|" + end + "|127|37");
    }
}
