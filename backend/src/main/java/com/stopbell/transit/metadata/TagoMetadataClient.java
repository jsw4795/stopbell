package com.stopbell.transit.metadata;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.TransitProvider;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class TagoMetadataClient {
    static final String CITY_CODES = "getCtyCodeList";
    static final String ROUTES = "getRouteNoList";
    static final String ROUTE_STOPS = "getRouteAcctoThrghSttnList";
    private final RestClient restClient; private final TransitClientProperties.Tago properties;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();
    public TagoMetadataClient(RestClient restClient, TransitClientProperties.Tago properties) { this.restClient = restClient; this.properties = properties; }
    public List<City> cityCodes() { try { byte[] bytes=get(CITY_CODES,null,null,null); checkHeader(bytes,CITY_CODES); CityResponse r=objectMapper.readValue(bytes,CityResponse.class); return items(r.response, CITY_CODES); } catch(Exception e){throw protocol(CITY_CODES,e);} }
    public Page<Route> routes(String cityCode, int page) { try { byte[] bytes=get(ROUTES,cityCode,null,page); checkHeader(bytes,ROUTES); RouteResponse r=objectMapper.readValue(bytes,RouteResponse.class); return page(r.response, ROUTES); } catch(Exception e){throw protocol(ROUTES,e);} }
    public Page<Stop> routeStops(String cityCode, String routeId, int page) { try { byte[] bytes=get(ROUTE_STOPS,cityCode,routeId,page); checkHeader(bytes,ROUTE_STOPS); StopResponse r=objectMapper.readValue(bytes,StopResponse.class); return page(r.response, ROUTE_STOPS); } catch(Exception e){throw protocol(ROUTE_STOPS,e);} }
    public Page<RawItem<Route>> routesWithRaw(String cityCode, int page) { return rawPage(ROUTES, cityCode, null, page, Route.class); }
    public Page<RawItem<Stop>> routeStopsWithRaw(String cityCode, String routeId, int page) { return rawPage(ROUTE_STOPS, cityCode, routeId, page, Stop.class); }
    public List<City> cityCodes(Consumer<byte[]> archive) {
        byte[] bytes = get(CITY_CODES, null, null, null);
        List<City> result = parseCityCodes(bytes);
        archiveSafe(bytes, archive);
        return result;
    }
    public Page<RawItem<Route>> routesWithRaw(String cityCode, int page, Consumer<byte[]> archive) {
        return rawPage(ROUTES, cityCode, null, page, Route.class, archive);
    }
    public Page<RawItem<Stop>> routeStopsWithRaw(String cityCode, String routeId, int page, Consumer<byte[]> archive) {
        return rawPage(ROUTE_STOPS, cityCode, routeId, page, Stop.class, archive);
    }
    private <T> Page<RawItem<T>> rawPage(String operation, String cityCode, String routeId, int number, Class<T> type) {
        return rawPage(operation, cityCode, routeId, number, type, null);
    }
    private <T> Page<RawItem<T>> rawPage(String operation, String cityCode, String routeId, int number, Class<T> type, Consumer<byte[]> archive) {
        byte[] bytes = get(operation, cityCode, routeId, number);
        Page<RawItem<T>> result = parseRawPage(bytes, operation, type);
        if (archive != null) archiveSafe(bytes, archive);
        return result;
    }
    List<City> parseCityCodes(byte[] bytes) {
        try { checkHeader(bytes, CITY_CODES); return items(objectMapper.readValue(bytes, CityResponse.class).response, CITY_CODES); }
        catch (Exception e) { throw protocol(CITY_CODES, e); }
    }
    Page<RawItem<Route>> parseRoutes(byte[] bytes) { return parseRawPage(bytes, ROUTES, Route.class); }
    Page<RawItem<Stop>> parseRouteStops(byte[] bytes) { return parseRawPage(bytes, ROUTE_STOPS, Stop.class); }
    private <T> Page<RawItem<T>> parseRawPage(byte[] bytes, String operation, Class<T> type) {
        try {
            checkHeader(bytes, operation);
            RawResponse response = objectMapper.readValue(bytes, RawResponse.class);
            Page<JsonNode> page = page(response.response, operation);
            List<RawItem<T>> items = page.items().stream().map(node -> {
                if (!node.isObject()) throw TransitProviderClientException.protocol(TransitProvider.TAGO, operation, null);
                return new RawItem<>(objectMapper.treeToValue(node, type), node);
            }).toList();
            return new Page<>(items, page.totalCount(), page.pageNo());
        } catch (Exception e) { throw protocol(operation, e); }
    }
    private void checkHeader(byte[] bytes, String operation) {
        JsonNode code = objectMapper.readTree(bytes).path("response").path("header").path("resultCode");
        if (!code.isMissingNode() && !code.isNull() && (code.isString() || code.isNumber()) && !"00".equals(code.asString()))
            throw TransitProviderClientException.provider(TransitProvider.TAGO, operation, code.asString());
    }
    boolean isSessionUnavailable(byte[] bytes) {
        try {
            JsonNode header = objectMapper.readTree(bytes).path("response").path("header");
            String message = header.path("resultMsg").asString("");
            return "99".equals(header.path("resultCode").asString()) && message.contains("가용한 세션이 존재하지 않습니다");
        } catch (RuntimeException e) { return false; }
    }
    private void archiveSafe(byte[] bytes, Consumer<byte[]> archive) {
        JsonNode tree;
        try { tree = objectMapper.readTree(bytes); }
        catch (Exception e) { throw new IllegalStateException("TAGO archive content could not be inspected"); }
        new ArchiveContentSafety(properties.serviceKey()).checkJson(tree);
        archive.accept(bytes);
    }
    private byte[] get(String operation, String cityCode, String routeId, Integer page) {
        try {
            return restClient.get().uri(uri(operation, cityCode, routeId, page)).accept(MediaType.APPLICATION_JSON).retrieve().body(byte[].class);
        } catch (TransitProviderClientException e) { throw e;
        } catch (RestClientResponseException e) { throw TransitProviderClientException.http(TransitProvider.TAGO, operation, e.getStatusCode().value(), null);
        } catch (ResourceAccessException e) { preserveInterruption(e); throw TransitProviderClientException.transport(TransitProvider.TAGO, operation, null);
        } catch (IllegalArgumentException e) { throw TransitProviderClientException.protocol(TransitProvider.TAGO, operation, null);
        } catch (RestClientException e) { throw TransitProviderClientException.protocol(TransitProvider.TAGO, operation, null); }
    }
    private static void preserveInterruption(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
    URI uri(String operation, String cityCode, String routeId, Integer page) { var b=UriComponentsBuilder.fromUriString(properties.metadataBaseUrl()).pathSegment(operation).queryParam("serviceKey", properties.serviceKey()); if(page!=null)b.queryParam("pageNo", page).queryParam("numOfRows", 1000); b.queryParam("_type", "json"); if(cityCode!=null)b.queryParam("cityCode", UriUtils.encodeQueryParam(cityCode, StandardCharsets.UTF_8)); if(routeId!=null)b.queryParam("routeId", UriUtils.encodeQueryParam(routeId, StandardCharsets.UTF_8)); return b.build(true).toUri(); }
    private <T> List<T> items(Envelope<T> response, String operation) { Body<T> body=body(response,operation); if(body.items==null||body.items.item==null)throw TransitProviderClientException.protocol(TransitProvider.TAGO,operation,null); return body.items.item; }
    private <T> Page<T> page(Envelope<T> response, String operation) { Body<T> body=body(response,operation); if(body.totalCount==null||body.pageNo==null||body.items==null||body.items.item==null)throw TransitProviderClientException.protocol(TransitProvider.TAGO,operation,null); return new Page<>(body.items.item,body.totalCount,body.pageNo); }
    private <T> Body<T> body(Envelope<T> response, String operation) { if(response==null||response.header==null||response.body==null||response.header.resultCode==null)throw TransitProviderClientException.protocol(TransitProvider.TAGO,operation,null); if(!"00".equals(response.header.resultCode))throw TransitProviderClientException.provider(TransitProvider.TAGO,operation,response.header.resultCode); return response.body; }
    private TransitProviderClientException protocol(String operation, Exception e) { return e instanceof TransitProviderClientException x ? x : TransitProviderClientException.protocol(TransitProvider.TAGO, operation, null); }
    public record Page<T>(List<T> items, int totalCount, int pageNo) { }
    public record RawItem<T>(T normalized, JsonNode fields) { }
    public record City(String citycode, String cityname) { }
    public record Route(String routeid, String routeno) { }
    public record Stop(String nodeid, String nodenm, Integer nodeord, BigDecimal gpslati, BigDecimal gpslong) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record CityResponse(Envelope<City> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record RouteResponse(Envelope<Route> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record StopResponse(Envelope<Stop> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record RawResponse(Envelope<JsonNode> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Envelope<T>(Header header, Body<T> body) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Header(String resultCode) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Body<T>(Items<T> items, Integer totalCount, Integer pageNo) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Items<T>(List<T> item) { }
}
