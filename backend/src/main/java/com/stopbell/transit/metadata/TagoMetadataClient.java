package com.stopbell.transit.metadata;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

public class TagoMetadataClient {
    static final String CITY_CODES = "getCtyCodeList";
    static final String ROUTES = "getRouteNoList";
    static final String ROUTE_STOPS = "getRouteAcctoThrghSttnList";
    private final RestClient restClient; private final TransitClientProperties.Tago properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public TagoMetadataClient(RestClient restClient, TransitClientProperties.Tago properties) { this.restClient = restClient; this.properties = properties; }
    public Page<City> cityCodes(int page) { try { CityResponse r=objectMapper.readValue(get(CITY_CODES,null,null,page),CityResponse.class); return page(r.response, CITY_CODES); } catch(Exception e){throw protocol(CITY_CODES,e);} }
    public Page<Route> routes(String cityCode, int page) { try { RouteResponse r=objectMapper.readValue(get(ROUTES,cityCode,null,page),RouteResponse.class); return page(r.response, ROUTES); } catch(Exception e){throw protocol(ROUTES,e);} }
    public Page<Stop> routeStops(String cityCode, String routeId, int page) { try { StopResponse r=objectMapper.readValue(get(ROUTE_STOPS,cityCode,routeId,page),StopResponse.class); return page(r.response, ROUTE_STOPS); } catch(Exception e){throw protocol(ROUTE_STOPS,e);} }
    private String get(String operation, String cityCode, String routeId, int page) {
        try {
            return restClient.get().uri(uri(operation, cityCode, routeId, page)).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (TransitProviderClientException e) { throw e;
        } catch (RestClientResponseException e) { throw TransitProviderClientException.http(TransitProvider.TAGO, operation, e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) { throw TransitProviderClientException.transport(TransitProvider.TAGO, operation, e);
        } catch (RestClientException e) { throw TransitProviderClientException.protocol(TransitProvider.TAGO, operation, e); }
    }
    private URI uri(String operation, String cityCode, String routeId, int page) { var b=UriComponentsBuilder.fromUriString(properties.metadataBaseUrl()).pathSegment(operation).queryParam("serviceKey", properties.serviceKey()).queryParam("pageNo", page).queryParam("numOfRows", 1000).queryParam("_type", "json"); if(cityCode!=null)b.queryParam("cityCode", UriUtils.encodeQueryParam(cityCode, StandardCharsets.UTF_8)); if(routeId!=null)b.queryParam("routeId", UriUtils.encodeQueryParam(routeId, StandardCharsets.UTF_8)); return b.build(true).toUri(); }
    private <T> Page<T> page(Envelope<T> response, String operation) { if(response==null||response.header==null||response.body==null||response.header.resultCode==null)throw TransitProviderClientException.protocol(TransitProvider.TAGO,operation,null); if(!"00".equals(response.header.resultCode))throw TransitProviderClientException.provider(TransitProvider.TAGO,operation,response.header.resultCode); if(response.body.totalCount==null||response.body.pageNo==null||response.body.items==null||response.body.items.item==null)throw TransitProviderClientException.protocol(TransitProvider.TAGO,operation,null); return new Page<>(response.body.items.item,response.body.totalCount,response.body.pageNo); }
    private TransitProviderClientException protocol(String operation, Exception e) { return e instanceof TransitProviderClientException x ? x : TransitProviderClientException.protocol(TransitProvider.TAGO, operation, e); }
    public record Page<T>(List<T> items, int totalCount, int pageNo) { }
    public record City(String citycode, String cityname) { }
    public record Route(String routeid, String routeno) { }
    public record Stop(String nodeid, String nodenm, Integer nodeord, BigDecimal gpslati, BigDecimal gpslong) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record CityResponse(Envelope<City> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record RouteResponse(Envelope<Route> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record StopResponse(Envelope<Stop> response) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Envelope<T>(Header header, Body<T> body) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Header(String resultCode) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Body<T>(Items<T> items, Integer totalCount, Integer pageNo) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true) record Items<T>(List<T> item) { }
}
