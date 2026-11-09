package com.stopbell.transit.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
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
    private final Path existingGbis;
    private final java.util.function.Consumer<Duration> retryWait;
    private String provider = "GBIS", stage = "CREATE";
    private Path tagoPath, gbisPath;
    private int responses;
    private boolean tagoComplete, gbisComplete;
    private GbisBaseInfoValidationException.Code validationCode;
    private GbisBaseInfoValidationException.Field validationField;
    private String failureDetail;
    private String requestOperation, requestCity, requestRoute;
    private Integer requestPage;
    private boolean receivedResponse;
    private Path diagnosticFile;
    private String diagnosticStatus = "none";

    TransitMetadataCollector(Path root, String key, String tagoUrl, String gbisUrl,
                             CollectionBudget budget, CollectionHttp.Transport transport) {
        this(root, key, tagoUrl, gbisUrl, budget, transport, null);
    }
    TransitMetadataCollector(Path root, String key, String tagoUrl, String gbisUrl,
                             CollectionBudget budget, CollectionHttp.Transport transport, Path existingGbis) {
        this(root, key, tagoUrl, gbisUrl, budget, transport, existingGbis, budget::pause);
    }
    TransitMetadataCollector(Path root, String key, String tagoUrl, String gbisUrl,
                             CollectionBudget budget, CollectionHttp.Transport transport, Path existingGbis,
                             java.util.function.Consumer<Duration> retryWait) {
        this.root = root; this.key = key; this.tagoUrl = tagoUrl; this.gbisUrl = gbisUrl;
        this.budget = budget; this.transport = transport; this.existingGbis = existingGbis; this.retryWait = retryWait;
    }
    Report collect() {
        try {
            budget.check();
            GbisCollectedSnapshot gbis;
            if (existingGbis != null) {
                gbisPath = existingGbis;
                stage = "REPLAY";
                try { gbis = GbisLocalBundle.replay(gbisPath, key); gbisComplete = true; }
                catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            } else {
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
                gbis = gbisWriter.complete(budget::check); gbisComplete = true;
                budget.check();
                stage = "REPLAY";
                try {
                    if (!gbis.equals(GbisLocalBundle.replay(gbisPath, key))) throw new IllegalStateException();
                } catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            }
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
        } catch (RuntimeException e) {
            if ("TAGO".equals(provider) && e.getMessage() != null && (e.getMessage().equals("TAGO required field missing")
                    || e.getMessage().equals("TAGO stop order missing") || e.getMessage().equals("TAGO route has no stops"))) failureDetail = "REQUIRED_DATA_MISSING";
            throw new CollectionBudget.Failure(CollectionBudget.Code.INVALID_RESPONSE);
        }
    }
    private byte[] get(URI uri) {
        for (int attempt = 0; ; attempt++) {
            try { return transport.get(uri, budget); }
            catch (CollectionBudget.Failure e) { if (!e.retryable || attempt >= 2) throw e; }
        }
    }
    String failureSummary(CollectionBudget.Code code) {
        String validation = code == CollectionBudget.Code.INVALID_RESPONSE && validationCode != null
                ? " validation=" + validationCode + (validationField == GbisBaseInfoValidationException.Field.NONE ? "" : " field=" + validationField) : "";
        return "provider=" + provider + " stage=" + stage + " failure=" + code + validation
                + (failureDetail == null ? "" : " detail=" + failureDetail) + " requests=" + budget.requests()
                + ("TAGO".equals(provider) && requestOperation != null ? requestSummary() : "")
                + " bytes=" + budget.bytes() + " responses=" + responses + " tagoBundle=" + tagoPath
                + " tagoComplete=" + tagoComplete + " gbisBundle=" + gbisPath + " gbisComplete=" + gbisComplete;
    }
    private String requestSummary() {
        return " operation=" + requestOperation + " cityCode=" + safeId(requestCity)
                + " routeId=" + safeId(requestRoute) + " pageNo=" + (requestPage == null ? "none" : requestPage)
                + " httpStatus=" + (receivedResponse ? "2xx" : "unavailable")
                + " diagnosticFile=" + (diagnosticFile == null ? "none" : diagnosticFile)
                + " diagnosticStatus=" + diagnosticStatus;
    }
    private String safeId(String value) {
        if (value == null) return "none";
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) return "redacted";
        try { new ArchiveContentSafety(key).checkText(value); return value; }
        catch (RuntimeException e) { return "redacted"; }
    }
    private void saveDiagnostic(byte[] bytes) {
        diagnosticStatus = "storage_failed";
        try {
            var directory = Files.createTempDirectory(root, "tago-diagnostic-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var response = directory.resolve("response.bin");
            Files.createFile(response, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(response, bytes);
            diagnosticFile = response;
            diagnosticStatus = "saved";
            var metadata = directory.resolve("request.txt");
            Files.createFile(metadata, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(metadata, "provider=TAGO\n" + requestSummary() + "\nfailure=" + failureDetail + "\n");
        } catch (IOException | RuntimeException e) {
            diagnosticFile = null; diagnosticStatus = "storage_failed";
        }
    }
    private final class CapturingTagoClient extends TagoMetadataClient {
        private final TagoLocalBundle.Writer writer;
        CapturingTagoClient(TransitClientProperties.Tago properties, TagoLocalBundle.Writer writer) {
            super(null, properties); this.writer = writer;
        }
        private byte[] fetch(String op, String city, String route, Integer page) {
            stage = op; requestOperation = op; requestCity = city; requestRoute = route; requestPage = page;
            receivedResponse = false; diagnosticFile = null; diagnosticStatus = "no_response";
            byte[] result = transport.get(uri(op, city, route, page), budget);
            receivedResponse = true; diagnosticStatus = "not_saved";
            return result;
        }
        private void save(RequestWithBytes response) {
            try { writer.add(response.request(), response.bytes()); responses++; }
            catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.STORAGE_FAILURE); }
        }
        @Override public List<City> cityCodes() {
            return fetchAndSave(CITY_CODES, null, null, null, this::parseCityCodes);
        }
        @Override public Page<RawItem<Route>> routesWithRaw(String city, int page) {
            return fetchAndSave(ROUTES, city, null, page, this::parseRoutes);
        }
        @Override public Page<RawItem<Stop>> routeStopsWithRaw(String city, String route, int page) {
            return fetchAndSave(ROUTE_STOPS, city, route, page, this::parseRouteStops);
        }
        private <T> T fetchAndSave(String op, String city, String route, Integer page, java.util.function.Function<byte[], T> parser) {
            for (int attempt = 0; ; attempt++) {
                byte[] bytes;
                try { bytes = fetch(op, city, route, page); }
                catch (CollectionBudget.Failure e) { if (e.retryable && attempt < 2) continue; throw e; }
                try {
                    T result = parser.apply(bytes);
                    save(new RequestWithBytes(new TagoLocalBundle.Request(op, city, route, page == null ? 0 : page), bytes));
                    return result;
                } catch (TransitProviderClientException e) {
                    boolean sessionUnavailable = e.failureKind() == com.stopbell.transit.client.TransitProviderClientFailureKind.PROVIDER
                            && isSessionUnavailable(bytes);
                    if (sessionUnavailable && attempt < 2) {
                        budget.check(); retryWait.accept(Duration.ofSeconds(attempt == 0 ? 5 : 15)); budget.check();
                        continue;
                    }
                    if (sessionUnavailable) { failureDetail = "SESSION_UNAVAILABLE"; saveDiagnostic(bytes); }
                    else if (e.failureKind() != com.stopbell.transit.client.TransitProviderClientFailureKind.PROVIDER) {
                        try { new tools.jackson.databind.ObjectMapper().readTree(bytes); failureDetail = "TAGO_STRUCTURE"; }
                        catch (RuntimeException invalidJson) { failureDetail = "JSON_PARSE"; }
                        saveDiagnostic(bytes);
                    }
                    throw e;
                }
            }
        }
    }
    private record RequestWithBytes(TagoLocalBundle.Request request, byte[] bytes) { }
}
