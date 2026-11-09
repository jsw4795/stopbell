package com.stopbell.transit.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.client.TransitProviderClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** One sequential collection into fresh local bundles; no persistence services are used. */
final class TransitMetadataCollector {
    record Report(Path tagoPath, Path gbisPath, int tagoRoutes, int tagoStops, int occurrences,
                  int gbisRoutes, String version, int enriched, int skipped, int requests, long bytes, long elapsedMillis) { }
    private final Path root;
    private final String key;
    private final String tagoUrl;
    private final String gbisUrl;
    private final CollectionBudget budget;
    private final CollectionHttp.Transport transport;
    private String provider = "GBIS", stage = "CREATE";
    private Path tagoPath, gbisPath;
    private int responses;
    private boolean tagoComplete, gbisComplete;
    private GbisBaseInfoValidationException.Code validationCode;
    private GbisBaseInfoValidationException.Field validationField;

    TransitMetadataCollector(Path root, String key, String tagoUrl, String gbisUrl,
                             CollectionBudget budget, CollectionHttp.Transport transport) {
        this.root = root; this.key = key; this.tagoUrl = tagoUrl; this.gbisUrl = gbisUrl;
        this.budget = budget; this.transport = transport;
    }
    Report collect() {
        try {
            budget.check();
            GbisLocalBundle.Writer gbisWriter = GbisLocalBundle.create(root, key);
            gbisPath = gbisWriter.directory();
            stage = "BASE_INFO";
            URI base = UriComponentsBuilder.fromUriString(gbisUrl).queryParam("format", "json")
                    .queryParam("serviceKey", key).build(true).toUri();
            byte[] info = get(base);
            var item = GbisMetadataClient.parseBaseInfo(info, key);
            gbisWriter.add(GbisLocalBundle.Source.BASE_INFO, info); responses++;
            stage = "ROUTE";
            gbisWriter.add(GbisLocalBundle.Source.ROUTE, get(URI.create(item.routeDownloadUrl()))); responses++;
            stage = "ROUTE_STATION";
            gbisWriter.add(GbisLocalBundle.Source.ROUTE_STATION, get(URI.create(item.routeStationDownloadUrl()))); responses++;
            stage = "COMPLETE";
            budget.check();
            var gbis = gbisWriter.complete(budget::check); gbisComplete = true;
            budget.check();
            stage = "REPLAY";
            try {
                if (!gbis.equals(GbisLocalBundle.replay(gbisPath, key))) throw new IllegalStateException();
            } catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            provider = "TAGO"; stage = "CREATE";
            var writer = TagoLocalBundle.create(root, key);
            tagoPath = writer.directory();
            var properties = new TransitClientProperties.Tago(tagoUrl, tagoUrl, key);
            var client = new CapturingTagoClient(properties, writer);
            var source = new TagoMetadataSource(client, writer.cityOrder());
            var captured = source.fetchSnapshotWithRawRows();
            stage = "COMPLETE";
            budget.check();
            var saved = writer.complete(budget::check); tagoComplete = true;
            budget.check();
            stage = "REPLAY";
            try {
                var replay = TagoLocalBundle.replay(tagoPath, key);
                if (!captured.equals(saved) || !captured.equals(replay)) throw new IllegalStateException();
            } catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            provider = "COMBINED"; stage = "REPLAY";
            budget.check();
            TagoMetadataSource.TagoSnapshot combined;
            try { combined = TagoGbisOfflineSnapshotAssembler.withGbis(tagoPath, gbisPath, key); }
            catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            var enriched = GbisDestinationEnricher.enrich(captured.normalized().routes(), gbis.normalized());
            if (!combined.normalized().routes().equals(enriched.routes()) || !combined.rawRows().equals(captured.rawRows())
                    || !gbis.equals(combined.gbis())) throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE);
            budget.check();
            var stops = new HashSet<String>();
            int occurrences = 0;
            for (var route : combined.normalized().routes()) for (var stop : route.occurrences()) {
                stops.add(stop.externalStopId()); occurrences++;
            }
            return new Report(tagoPath, gbisPath, combined.normalized().routes().size(), stops.size(), occurrences,
                    gbis.normalized().routes().size(), gbis.sourceVersion(), enriched.enrichedRoutes(),
                    combined.normalized().routes().size() - enriched.enrichedRoutes(), budget.requests(), budget.bytes(), budget.elapsedMillis());
        } catch (CollectionBudget.Failure e) { throw e;
        } catch (GbisBaseInfoValidationException e) {
            validationCode = e.code(); validationField = e.field();
            throw new CollectionBudget.Failure(CollectionBudget.Code.INVALID_RESPONSE);
        } catch (TransitProviderClientException e) {
            throw new CollectionBudget.Failure(e.failureKind() == com.stopbell.transit.client.TransitProviderClientFailureKind.PROVIDER
                    ? CollectionBudget.Code.PROVIDER_ERROR : CollectionBudget.Code.INVALID_RESPONSE);
        } catch (IOException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.STORAGE_FAILURE);
        } catch (RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INVALID_RESPONSE); }
    }
    private byte[] get(URI uri) { return transport.get(uri, budget); }
    String failureSummary(CollectionBudget.Code code) {
        String validation = code == CollectionBudget.Code.INVALID_RESPONSE && validationCode != null
                ? " validation=" + validationCode + (validationField == GbisBaseInfoValidationException.Field.NONE ? "" : " field=" + validationField) : "";
        return "provider=" + provider + " stage=" + stage + " failure=" + code + validation + " requests=" + budget.requests()
                + " bytes=" + budget.bytes() + " responses=" + responses + " tagoBundle=" + tagoPath
                + " tagoComplete=" + tagoComplete + " gbisBundle=" + gbisPath + " gbisComplete=" + gbisComplete;
    }
    private final class CapturingTagoClient extends TagoMetadataClient {
        private final TagoLocalBundle.Writer writer;
        CapturingTagoClient(TransitClientProperties.Tago properties, TagoLocalBundle.Writer writer) {
            super(null, properties); this.writer = writer;
        }
        private byte[] fetch(String op, String city, String route, Integer page) {
            stage = op;
            return get(uri(op, city, route, page));
        }
        private void save(RequestWithBytes response) {
            try { writer.add(response.request(), response.bytes()); responses++; }
            catch (IOException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.STORAGE_FAILURE); }
        }
        @Override public List<City> cityCodes() {
            byte[] bytes = fetch(CITY_CODES, null, null, null);
            List<City> result = parseCityCodes(bytes);
            save(new RequestWithBytes(new TagoLocalBundle.Request(CITY_CODES, null, null, 0), bytes));
            return result;
        }
        @Override public Page<RawItem<Route>> routesWithRaw(String city, int page) {
            byte[] bytes = fetch(ROUTES, city, null, page);
            var result = parseRoutes(bytes);
            save(new RequestWithBytes(new TagoLocalBundle.Request(ROUTES, city, null, page), bytes));
            return result;
        }
        @Override public Page<RawItem<Stop>> routeStopsWithRaw(String city, String route, int page) {
            byte[] bytes = fetch(ROUTE_STOPS, city, route, page);
            var result = parseRouteStops(bytes);
            save(new RequestWithBytes(new TagoLocalBundle.Request(ROUTE_STOPS, city, route, page), bytes));
            return result;
        }
    }
    private record RequestWithBytes(TagoLocalBundle.Request request, byte[] bytes) { }
}
