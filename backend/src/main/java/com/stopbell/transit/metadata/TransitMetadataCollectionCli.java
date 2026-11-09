package com.stopbell.transit.metadata;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Explicit CLI. Does not create a Spring context or connect to any database. */
public final class TransitMetadataCollectionCli {
    private static final String TAGO_DEFAULT = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService";
    private static final String GBIS_DEFAULT = "https://apis.data.go.kr/6410000/baseinfoservice/v2/getBaseInfoItemv2";
    private TransitMetadataCollectionCli() { }
    public static void main(String[] args) {
        int status = run(args, System.getenv(), System.out, System.err, CollectionHttp.NETWORK);
        if (status != 0) System.exit(status);
    }
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err, CollectionHttp.Transport transport) {
        try {
            Map<String, String> options = parse(args);
            boolean live = "true".equals(options.get("live"));
            if (live && !"COLLECT".equals(options.get("confirm"))) throw new IllegalArgumentException();
            if (!live && options.containsKey("confirm")) throw new IllegalArgumentException();
            String key = env.get("PUBLIC_DATA_SERVICE_KEY");
            if (key == null || key.isBlank()) throw new IllegalArgumentException();
            Path directory = checkDirectory(options.get("directory"), key);
            String tago = endpoint(env.getOrDefault("TAGO_METADATA_BASE_URL", TAGO_DEFAULT));
            String gbis = endpoint(env.getOrDefault("GBIS_METADATA_BASE_URL", GBIS_DEFAULT));
            Path existingGbis = options.containsKey("gbis-bundle") ? Path.of(options.get("gbis-bundle")) : null;
            if (existingGbis != null) {
                if (!existingGbis.isAbsolute()) throw new IllegalArgumentException();
                new ArchiveContentSafety(key).checkText(existingGbis.toString());
            }
            if (live && !options.containsKey("max-requests")) throw new IllegalArgumentException();
            var limits = new CollectionBudget.Limits(integer(options, "max-requests", 1000),
                    number(options, "max-response-bytes", 50_000_000), number(options, "max-total-bytes", 500_000_000),
                    Duration.ofMillis(number(options, "max-duration-ms", 3_600_000)),
                    Duration.ofMillis(number(options, "interval-ms", 100)),
                    Duration.ofMillis(number(options, "connect-timeout-ms", 2000)),
                    Duration.ofMillis(number(options, "response-timeout-ms", 30000)));
            out.println("mode=" + (live ? "LIVE" : "DRY_RUN") + " directory=" + directory
                    + " maxRequests=" + limits.requests() + " maxResponseBytes=" + limits.responseBytes()
                    + " maxTotalBytes=" + limits.totalBytes() + " maxDurationMs=" + limits.duration().toMillis()
                    + " intervalMs=" + limits.interval().toMillis() + " connectTimeoutMs=" + limits.connectTimeout().toMillis()
                    + " responseTimeoutMs=" + limits.responseTimeout().toMillis());
            if (!live) {
                out.println(existingGbis == null ? "No HTTP requests. Steps: GBIS base and two files; TAGO cities, routes and stop pages; validate and replay bundles."
                        : "No HTTP requests. Steps: replay GBIS bundle; collect TAGO cities, routes and stop pages; validate and replay bundles.");
                out.println("Minimum requests=" + (existingGbis == null ? 35 : 32) + "; actual total is unknown until all pages and routes are discovered.");
                return 0;
            }
            var budget = new CollectionBudget(limits);
            try (var connection = transport == CollectionHttp.NETWORK ? new CollectionHttp.Connection(limits) : null) {
                var collector = new TransitMetadataCollector(directory, key, tago, gbis, budget, connection == null ? transport : connection, existingGbis);
                try {
                    var result = collector.collect();
                    out.println("complete=true tagoBundle=" + result.tagoPath() + " tagoComplete=true gbisBundle=" + result.gbisPath()
                            + " gbisComplete=true tagoRoutes=" + result.tagoRoutes() + " tagoStops=" + result.tagoStops()
                            + " occurrences=" + result.occurrences() + " gbisRoutes=" + result.gbisRoutes()
                            + " gbisVersion=" + result.version().replaceAll("[\\p{Cntrl}]", "?") + " enrichedRoutes=" + result.enriched()
                            + " skippedRoutes=" + result.skipped() + " requests=" + result.requests()
                            + " bytes=" + result.bytes() + " elapsedMs=" + result.elapsedMillis());
                    return 0;
                } catch (CollectionBudget.Failure e) {
                    err.println("complete=false " + collector.failureSummary(e.code));
                    return 1;
                }
            }
        } catch (RuntimeException | java.io.IOException e) {
            err.println("Invalid collection configuration or storage directory; no collection started");
            return 2;
        }
    }
    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        var allowed = java.util.Set.of("directory", "max-requests", "max-response-bytes", "max-total-bytes",
                "max-duration-ms", "interval-ms", "connect-timeout-ms", "response-timeout-ms", "live", "confirm", "gbis-bundle");
        for (String arg : args) {
            if (arg.equals("--live")) arg = "--live=true";
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException();
            int separator = arg.indexOf('=');
            String key = arg.substring(2, separator), value = arg.substring(separator + 1);
            if (!allowed.contains(key) || value.isBlank() || result.putIfAbsent(key, value) != null
                    || (key.equals("live") && !value.equals("true"))) throw new IllegalArgumentException();
        }
        return result;
    }
    private static long number(Map<String, String> options, String key, long fallback) {
        return options.containsKey(key) ? Long.parseLong(options.get(key)) : fallback;
    }
    private static int integer(Map<String, String> options, String key, int fallback) {
        return Math.toIntExact(number(options, key, fallback));
    }
    private static String endpoint(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"apis.data.go.kr".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 || uri.getRawPath() == null || uri.getRawPath().isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static Path checkDirectory(String value, String key) throws java.io.IOException {
        if (value == null) throw new IllegalArgumentException();
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        path = path.normalize();
        if (!path.toRealPath().equals(path) || !Files.isWritable(path)) throw new IllegalArgumentException();
        for (Path p = path; p != null; p = p.getParent())
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
        new ArchiveContentSafety(key).checkText(path.toString());
        Path temp = Files.createTempFile(path, ".collection-check-", ".tmp");
        Path link = temp.resolveSibling(temp.getFileName() + ".link");
        try { Files.createLink(link, temp); }
        finally { Files.deleteIfExists(link); Files.deleteIfExists(temp); }
        return path;
    }
}
