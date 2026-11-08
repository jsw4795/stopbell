package com.stopbell.transit.metadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.BusMetadataSource;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import com.stopbell.transit.service.TransitMetadataArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

public final class TagoMetadataSource implements BusMetadataSource {
    // The 31 codes returned for Gyeonggi by the documented TAGO PoC; no prefix/name heuristic is used.
    private static final Set<String> GYEONGGI_CODES = Set.of("31010","31020","31030","31040","31050","31060","31070","31080","31090","31100","31110","31120","31130","31140","31150","31160","31170","31180","31190","31200","31210","31220","31230","31240","31250","31260","31270","31320","31350","31370","31380");
    private static final Logger log = LoggerFactory.getLogger(TagoMetadataSource.class);
    private static final int PROGRESS_LOG_INTERVAL = 100;

    private final TagoMetadataClient client; private final GbisMetadataClient gbisClient;
    private final long requestIntervalMillis; private final Set<String> gyeonggiCodes;
    public TagoMetadataSource(TagoMetadataClient client, GbisMetadataClient gbisClient, long requestIntervalMillis) {
        this(client, gbisClient, requestIntervalMillis, GYEONGGI_CODES);
    }
    TagoMetadataSource(TagoMetadataClient client, long requestIntervalMillis) { this(client, null, requestIntervalMillis, GYEONGGI_CODES); }
    TagoMetadataSource(TagoMetadataClient client, long requestIntervalMillis, Set<String> gyeonggiCodes) { this(client, null, requestIntervalMillis, gyeonggiCodes); }
    TagoMetadataSource(TagoMetadataClient client, GbisMetadataClient gbisClient, long requestIntervalMillis, Set<String> gyeonggiCodes) {
        this.client=client; this.gbisClient=gbisClient; this.requestIntervalMillis=requestIntervalMillis;
        this.gyeonggiCodes=Set.copyOf(gyeonggiCodes);
    }
    @Override public TransitProvider provider() { return TransitProvider.TAGO; }
    @Override public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
        return collect(false).normalized();
    }
    public TagoSnapshot fetchSnapshotWithRawRows() {
        return collect(true, null, null);
    }
    private TagoSnapshot collect(boolean preserveRaw) {
        return collect(preserveRaw, null, null);
    }
    public TagoSnapshot fetchSnapshotWithRawRows(long runId, TransitMetadataArchiveService archive) {
        return collect(true, runId, java.util.Objects.requireNonNull(archive));
    }
    private TagoSnapshot collect(boolean preserveRaw, Long runId, TransitMetadataArchiveService archive) {
        // A failed bulk fetch is never treated as an empty, complete enrichment source.
        GbisCollectedSnapshot gbisCollected = gbisClient != null && preserveRaw
                ? java.util.Objects.requireNonNull(archive == null ? gbisClient.fetchWithRaw()
                        : gbisClient.fetchWithRaw(runId, archive), "GBIS snapshot missing") : null;
        GbisBulkMetadata gbis = gbisClient == null ? null
                : preserveRaw ? gbisCollected.normalized() : gbisClient.fetch();
        pace(); Set<String> discovered = new HashSet<>(); for (TagoMetadataClient.City city : archive == null ? client.cityCodes()
                : client.cityCodes(bytes -> archive.store(runId, TransitSourceSystem.TAGO, "tago_city_codes", bytes))) { if (city.citycode()==null) throw new IllegalStateException("TAGO city code missing"); discovered.add(city.citycode()); }
        if (!discovered.containsAll(gyeonggiCodes)) throw new IllegalStateException("TAGO city discovery did not verify every Gyeonggi city code");
        log.info("TAGO city discovery complete: gyeonggiCities={}", gyeonggiCodes.size());
        List<BusRouteMetadataSnapshot> snapshots=new ArrayList<>(); Set<String> routeIds=new HashSet<>();
        List<RawSourceRow> rawRows = new ArrayList<>();
        int routesProcessed = 0;
        for (String city : gyeonggiCodes) {
            List<TagoMetadataClient.RawItem<TagoMetadataClient.Route>> routes = pages(page -> preserveRaw
                    ? archive == null ? client.routesWithRaw(city, page) : client.routesWithRaw(city, page,
                            bytes -> archive.store(runId, TransitSourceSystem.TAGO, archiveKey("route", city, "", page), bytes))
                    : wrap(client.routes(city, page)));
            log.info("TAGO route discovery complete: cityCode={}, routes={}", city, routes.size());
            for (TagoMetadataClient.RawItem<TagoMetadataClient.Route> routeItem : routes) {
                TagoMetadataClient.Route route = routeItem.normalized();
                required(route.routeid()); required(route.routeno());
                if(!routeIds.add(route.routeid())) throw new IllegalStateException("TAGO duplicate route identity");
                if (preserveRaw) rawRows.add(new RawSourceRow(TransitSourceSystem.TAGO, TransitSourceDataKind.ROUTE, route.routeid(), route.routeid(), null, routeItem.fields()));
                List<BusStopOccurrenceMetadataSnapshot> stops=new ArrayList<>();
                for (TagoMetadataClient.RawItem<TagoMetadataClient.Stop> stopItem : pages(p -> preserveRaw
                        ? archive == null ? client.routeStopsWithRaw(city, route.routeid(), p) : client.routeStopsWithRaw(city, route.routeid(), p,
                                bytes -> archive.store(runId, TransitSourceSystem.TAGO, archiveKey("stops", city, route.routeid(), p), bytes))
                        : wrap(client.routeStops(city, route.routeid(), p)))) {
                    TagoMetadataClient.Stop stop = stopItem.normalized();
                    int order = required(stop.nodeord());
                    stops.add(new BusStopOccurrenceMetadataSnapshot(required(stop.nodeid()),required(stop.nodenm()),stop.gpslati(),stop.gpslong(),order));
                    if (preserveRaw) rawRows.add(new RawSourceRow(TransitSourceSystem.TAGO, TransitSourceDataKind.OCCURRENCE,
                            occurrenceSourceKey(route.routeid(), order), route.routeid(), order, stopItem.fields()));
                }
                if(stops.isEmpty()) throw new IllegalStateException("TAGO route has no stops");
                snapshots.add(new BusRouteMetadataSnapshot(provider(),route.routeid(),route.routeno(),city,stops));
                routesProcessed++;
                if (routesProcessed % PROGRESS_LOG_INTERVAL == 0) {
                    log.info("TAGO route stop collection progress: routesProcessed={}", routesProcessed);
                }
            }
        }
        int stopOccurrences = snapshots.stream().mapToInt(snapshot -> snapshot.occurrences().size()).sum();
        log.info("TAGO metadata collection complete: routes={}, stopOccurrences={}", snapshots.size(), stopOccurrences);
        if (gbis != null) {
            GbisDestinationEnricher.Result enriched = GbisDestinationEnricher.enrich(snapshots, gbis);
            log.info("GBIS destination enrichment complete: tagoRoutes={}, gbisRoutes={}, enrichedRoutes={}, skippedRoutes={}, destinationOccurrences={}, skipReasons={}",
                    snapshots.size(), gbis.routes().size(), enriched.enrichedRoutes(),
                    snapshots.size() - enriched.enrichedRoutes(), enriched.destinationOccurrences(), enriched.skippedRoutes());
            snapshots = enriched.routes();
        }
        return new TagoSnapshot(new CompleteBusMetadataSnapshot(provider(), snapshots), rawRows, gbisCollected);
    }
    private static <T> TagoMetadataClient.Page<TagoMetadataClient.RawItem<T>> wrap(TagoMetadataClient.Page<T> page) {
        return new TagoMetadataClient.Page<>(page.items().stream().map(item -> new TagoMetadataClient.RawItem<>(item, null)).toList(), page.totalCount(), page.pageNo());
    }
    public static String occurrenceSourceKey(String routeId, int order) { return routeId.length() + ":" + routeId + ":" + order; }
    static String archiveKey(String operation, String city, String route, int page) {
        String identity = operation.length() + ":" + operation + city.length() + ":" + city
                + route.length() + ":" + route + ":" + page;
        try {
            var encoded = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(identity));
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(encoded);
            byte[] hash = digest.digest();
            return "tago_" + java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable");
        } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Invalid archive identity"); }
    }
    public record TagoSnapshot(CompleteBusMetadataSnapshot normalized, List<RawSourceRow> rawRows, GbisCollectedSnapshot gbis) {
        public TagoSnapshot(CompleteBusMetadataSnapshot normalized, List<RawSourceRow> rawRows) { this(normalized, rawRows, null); }
        public TagoSnapshot { rawRows = List.copyOf(rawRows); }
    }
    /** Fields are the original TAGO item; route and order are external linking identifiers. */
    public record RawSourceRow(TransitSourceSystem sourceSystem, TransitSourceDataKind dataKind, String sourceKey,
                               String externalRouteId, Integer stopOrder, JsonNode fields) { }
    private <T> List<T> pages(java.util.function.IntFunction<TagoMetadataClient.Page<T>> fetch) { List<T> all=new ArrayList<>(); int page=1,total=-1; while(total<0||all.size()<total){ pace(); TagoMetadataClient.Page<T> result=fetch.apply(page++); if(result.totalCount()<0||result.pageNo()!=page-1||result.items()==null) throw new IllegalStateException("TAGO pagination protocol failure"); if(total<0)total=result.totalCount(); else if(total!=result.totalCount())throw new IllegalStateException("TAGO pagination total changed"); if(result.items().isEmpty()&&all.size()<total)throw new IllegalStateException("TAGO pagination incomplete"); all.addAll(result.items()); if(all.size()>total)throw new IllegalStateException("TAGO pagination overflow"); } return all; }
    private void pace(){ if(requestIntervalMillis<=0)return; try{Thread.sleep(requestIntervalMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("TAGO sync interrupted",e);} }
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalStateException("TAGO required field missing");return value;} private static int required(Integer value){if(value==null||value<=0)throw new IllegalStateException("TAGO stop order missing");return value;}
}
