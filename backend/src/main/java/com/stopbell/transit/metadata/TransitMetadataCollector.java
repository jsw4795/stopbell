package com.stopbell.transit.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.client.TransitProviderClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** One sequential collection into fresh local bundles; no persistence services are used. */
final class TransitMetadataCollector {
    record Report(Path tagoPath, Path gbisPath, int tagoRoutes, int tagoStops, int occurrences,
                  int gbisRoutes, String version, int enriched, int skipped, int requests, long bytes, long elapsedMillis) { }
    private final CollectionOutput output;
    private final Clock clock;
    private final String key;
    private final String tagoUrl;
    private final String gbisUrl;
    private final CollectionBudget budget;
    private final CollectionHttp.Transport transport;
    private final Path existingGbis;
    private final java.util.function.Consumer<Duration> retryWait;
    private String provider = "GBIS", stage = "CREATE";
    private Path tagoPath, gbisPath;
    private int responses, citiesCompleted, routesProcessed;
    private static final int TAGO_CITIES = TagoMetadataSource.fullCityScope().size();
    private java.io.PrintStream progress;
    private boolean tagoManifest, gbisManifest, tagoComplete, gbisComplete, tagoPublished, gbisPublished;
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
        this(root, key, tagoUrl, gbisUrl, budget, transport, existingGbis, retryWait, Clock.systemUTC());
    }
    TransitMetadataCollector(Path root, String key, String tagoUrl, String gbisUrl,
                             CollectionBudget budget, CollectionHttp.Transport transport, Path existingGbis,
                             java.util.function.Consumer<Duration> retryWait, Clock clock) {
        this.output = new CollectionOutput(root); this.clock = clock;
        this.key = key; this.tagoUrl = tagoUrl; this.gbisUrl = gbisUrl;
        this.budget = budget; this.transport = transport; this.existingGbis = existingGbis; this.retryWait = retryWait;
    }
    Report collect(java.io.PrintStream out) {
        progress = out;
        return collect();
    }

    private void report(String event) {
        if (progress == null) return;
        progress.println("[" + provider + "] stage=" + stage + " event=" + event
                + ("TAGO".equals(provider) ? traversalSummary() : "")
                + " requests=" + budget.requests() + " responses=" + responses
                + " bytes=" + budget.bytes() + " elapsedMs=" + budget.elapsedMillis());
    }

    int responsesStored() { return responses; }

    String traversalSummary() {
        return " citiesCompleted=" + citiesCompleted + "/" + TAGO_CITIES + " routesProcessed=" + routesProcessed;
    }

    private void enter(String nextStage) {
        stage = nextStage;
        report("START");
    }

    Report collect() {
        try {
            budget.check();
            GbisCollectedSnapshot gbis;
            if (existingGbis != null) {
                gbisPath = existingGbis;
                enter("REPLAY");
                report("REUSE bundle=" + gbisPath);
                try { gbis = GbisLocalBundle.replay(gbisPath, key); gbisManifest = true; gbisComplete = true; report("VERIFIED"); }
                catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            } else {
                report("START");
                Instant startedAt = clock.instant();
                var gbisWriter = GbisLocalBundle.create(output.incomplete(startedAt), key, startedAt);
                gbisPath = gbisWriter.directory();
                enter("BASE_INFO");
                URI base = UriComponentsBuilder.fromUriString(gbisUrl).queryParam("format", "json")
                        .queryParam("serviceKey", key).build(true).toUri();
                byte[] info = get(base);
                var item = GbisMetadataClient.parseBaseInfo(info, key);
                gbisWriter.add(GbisLocalBundle.Source.BASE_INFO, info); responses++;
                enter("ROUTE");
                gbisWriter.add(GbisLocalBundle.Source.ROUTE, get(URI.create(item.routeDownloadUrl()))); responses++;
                enter("ROUTE_STATION");
                gbisWriter.add(GbisLocalBundle.Source.ROUTE_STATION, get(URI.create(item.routeStationDownloadUrl()))); responses++;
                enter("COMPLETE");
                budget.check();
                gbis = gbisWriter.complete(budget::check); gbisManifest = true;
                budget.check();
                enter("REPLAY");
                try {
                    if (!gbis.equals(GbisLocalBundle.replay(gbisPath, key))) throw new IllegalStateException();
                } catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
                gbisComplete = true;
                report("VERIFIED");
                gbisPath = publish(gbisPath, startedAt);
                gbisPublished = true;
                report("PUBLISHED");
            }
            provider = "TAGO"; enter("CREATE");
            Instant startedAt = clock.instant();
            var writer = TagoLocalBundle.create(output.incomplete(startedAt), key, startedAt);
            tagoPath = writer.directory();
            var properties = new TransitClientProperties.Tago(tagoUrl, tagoUrl, key);
            var client = new CapturingTagoClient(properties, writer);
            var source = new TagoMetadataSource(client, writer.cityOrder());
            var captured = source.fetchSnapshotWithRawRows((cities, routes, emit) -> {
                boolean cityCompleted = cities > citiesCompleted;
                citiesCompleted = cities;
                routesProcessed = routes;
                if (emit) report(cityCompleted ? "CITY_COMPLETE" : "ROUTE_MILESTONE");
            });
            enter("COMPLETE");
            budget.check();
            var saved = writer.complete(budget::check); tagoManifest = true;
            budget.check();
            enter("REPLAY");
            try {
                var replay = TagoLocalBundle.replay(tagoPath, key);
                if (!captured.equals(saved) || !captured.equals(replay)) throw new IllegalStateException();
            } catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            tagoComplete = true;
            report("VERIFIED");
            tagoPath = publish(tagoPath, startedAt);
            tagoPublished = true;
            report("PUBLISHED");
            provider = "COMBINED"; enter("REPLAY");
            budget.check();
            TagoMetadataSource.TagoSnapshot combined;
            try { combined = TagoGbisOfflineSnapshotAssembler.withGbis(tagoPath, gbisPath, key); }
            catch (IOException | RuntimeException e) { throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE); }
            var enriched = GbisDestinationEnricher.enrich(captured.normalized().routes(), gbis.normalized());
            if (!combined.normalized().routes().equals(enriched.routes()) || !combined.rawRows().equals(captured.rawRows())
                    || !gbis.equals(combined.gbis())) throw new CollectionBudget.Failure(CollectionBudget.Code.INTEGRITY_FAILURE);
            budget.check();
            report("VERIFIED");
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
    private Path publish(Path staged, Instant startedAt) throws IOException {
        enter("PUBLISH");
        budget.check();
        try {
            return output.publish(staged, provider, startedAt);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            failureDetail = "OUTPUT_COLLISION";
            throw e;
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            failureDetail = "ATOMIC_MOVE_UNAVAILABLE";
            throw e;
        } catch (IOException e) {
            failureDetail = "OUTPUT_IO_FAILURE";
            throw e;
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
                + " tagoComplete=" + tagoComplete + " tagoManifest=" + tagoManifest + " tagoPublished=" + tagoPublished
                + " gbisBundle=" + gbisPath + " gbisComplete=" + gbisComplete + " gbisManifest=" + gbisManifest
                + " gbisPublished=" + gbisPublished + " gbisReused=" + (existingGbis != null && gbisComplete)
                + traversalSummary() + " elapsedMs=" + budget.elapsedMillis()
                + ("TAGO".equals(provider) && requestOperation != null ? "" : " diagnosticFile=none diagnosticStatus=none");
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
    private void saveDiagnostic(byte[] bytes, Instant failedAt) {
        diagnosticStatus = "storage_failed";
        try {
            var directory = Files.createTempDirectory(output.diagnostics(failedAt), "tago-diagnostic-",
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
            if (!op.equals(stage)) enter(op);
            requestOperation = op; requestCity = city; requestRoute = route; requestPage = page;
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
                    Instant failedAt = clock.instant();
                    boolean sessionUnavailable = e.failureKind() == com.stopbell.transit.client.TransitProviderClientFailureKind.PROVIDER
                            && isSessionUnavailable(bytes);
                    if (sessionUnavailable && attempt < 2) {
                        budget.check(); retryWait.accept(Duration.ofSeconds(attempt == 0 ? 5 : 15)); budget.check();
                        continue;
                    }
                    if (sessionUnavailable) { failureDetail = "SESSION_UNAVAILABLE"; saveDiagnostic(bytes, failedAt); }
                    else if (e.failureKind() != com.stopbell.transit.client.TransitProviderClientFailureKind.PROVIDER) {
                        try { new tools.jackson.databind.ObjectMapper().readTree(bytes); failureDetail = "TAGO_STRUCTURE"; }
                        catch (RuntimeException invalidJson) { failureDetail = "JSON_PARSE"; }
                        saveDiagnostic(bytes, failedAt);
                    }
                    throw e;
                }
            }
        }
    }
    private record RequestWithBytes(TagoLocalBundle.Request request, byte[] bytes) { }
}
