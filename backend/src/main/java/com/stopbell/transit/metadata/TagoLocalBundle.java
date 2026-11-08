package com.stopbell.transit.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

/** Local, non-DB TAGO response capture. A manifest is published only after full replay succeeds. */
public final class TagoLocalBundle {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST = "manifest.json";
    private TagoLocalBundle() { }

    public record Request(String operation, String cityCode, String routeId, int pageNo) { }
    public record Entry(Request request, String file, long size, String sha256) { }
    public record Manifest(String id, String startedAt, String completedAt, boolean complete,
                           List<String> cities, List<Entry> responses) { }

    /** The caller supplies an existing absolute directory outside Git and the ordered collection scope. */
    public static Writer create(Path parent, String serviceKey) throws IOException {
        return new Writer(parent, TagoMetadataSource.fullCityScope(), serviceKey);
    }
    static Writer create(Path parent, List<String> orderedCities, String serviceKey) throws IOException {
        return new Writer(parent, orderedCities, serviceKey);
    }

    public static final class Writer {
        private final Path directory;
        private final String id = UUID.randomUUID().toString();
        private final String startedAt = Instant.now().toString();
        private final List<String> cities;
        private final ArchiveContentSafety safety;
        private final List<Entry> entries = new ArrayList<>();
        private final Set<Request> requests = new HashSet<>();
        private boolean closed;

        private Writer(Path parent, List<String> orderedCities, String key) throws IOException {
            Path root = safeDirectory(parent);
            ensureOutsideGit(root);
            this.cities = checkedCities(orderedCities);
            this.safety = safety(key);
            this.cities.forEach(this.safety::checkText);
            this.directory = Files.createDirectory(root.resolve(id));
        }
        public Path directory() { return directory; }
        public List<String> cityOrder() { return cities; }

        public void add(Request request, byte[] bytes) throws IOException {
            if (closed) throw new IllegalStateException("Bundle is closed");
            try {
                validateRequest(request, safety);
                if (!requests.add(request)) throw new IllegalStateException("Duplicate request");
                if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty response");
                checkContent(bytes, safety);
                String name = String.format(java.util.Locale.ROOT, "%08d.json", entries.size());
                writeNew(directory.resolve(name), bytes);
                entries.add(new Entry(request, name, bytes.length, digest(bytes)));
            } catch (RuntimeException | IOException e) {
                closed = true;
                throw e;
            }
        }
        public TagoMetadataSource.TagoSnapshot complete() throws IOException {
            if (closed) throw new IllegalStateException("Bundle is closed");
            closed = true;
            Manifest manifest = new Manifest(id, startedAt, Instant.now().toString(), true, cities, List.copyOf(entries));
            var snapshot = replay(directory, manifest, safety);
            writeNew(directory.resolve(MANIFEST), MAPPER.writeValueAsBytes(manifest));
            return snapshot;
        }
    }

    public static TagoMetadataSource.TagoSnapshot replay(Path directory, String serviceKey) throws IOException {
        Path root = safeDirectory(directory);
        ensureOutsideGit(root);
        Path path = root.resolve(MANIFEST);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Complete manifest missing");
        Manifest manifest;
        try { manifest = MAPPER.readValue(Files.readAllBytes(path), Manifest.class); }
        catch (RuntimeException e) { throw new IllegalStateException("Invalid manifest"); }
        if (manifest == null || manifest.cities() == null
                || !Set.copyOf(manifest.cities()).equals(Set.copyOf(TagoMetadataSource.fullCityScope())))
            throw new IllegalStateException("Incomplete city scope");
        return replay(root, manifest, safety(serviceKey));
    }

    static TagoMetadataSource.TagoSnapshot replayForTest(Path directory, String serviceKey) throws IOException {
        Path root = safeDirectory(directory);
        ensureOutsideGit(root);
        Path file = root.resolve(MANIFEST);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Complete manifest missing");
        return replay(root, MAPPER.readValue(Files.readAllBytes(file), Manifest.class), safety(serviceKey));
    }

    private static TagoMetadataSource.TagoSnapshot replay(Path root, Manifest manifest, ArchiveContentSafety safety) throws IOException {
        if (manifest == null || !manifest.complete() || manifest.id() == null || !manifest.id().equals(root.getFileName().toString())
                || manifest.startedAt() == null || manifest.completedAt() == null || manifest.responses() == null)
            throw new IllegalStateException("Incomplete manifest");
        try {
            if (Instant.parse(manifest.completedAt()).isBefore(Instant.parse(manifest.startedAt()))) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalStateException("Invalid collection time"); }
        List<String> cities = checkedCities(manifest.cities());
        cities.forEach(safety::checkText);
        List<byte[]> bodies = new ArrayList<>();
        Set<Request> unique = new HashSet<>();
        for (int i = 0; i < manifest.responses().size(); i++) {
            Entry entry = manifest.responses().get(i);
            if (entry == null) throw new IllegalStateException("Invalid entry");
            validateRequest(entry.request(), safety);
            String expected = String.format(java.util.Locale.ROOT, "%08d.json", i);
            if (!unique.add(entry.request()) || !expected.equals(entry.file()) || entry.size() <= 0
                    || entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Invalid response entry");
            Path file = root.resolve(expected);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Response missing");
            byte[] bytes = Files.readAllBytes(file);
            if (entry.size() != bytes.length || !entry.sha256().equals(digest(bytes))) throw new IllegalStateException("Response integrity failure");
            checkContent(bytes, safety);
            bodies.add(bytes);
        }
        var offline = new ReplayClient(manifest.responses(), bodies);
        var snapshot = new TagoMetadataSource(offline, cities).fetchSnapshotWithRawRows();
        if (offline.index != bodies.size()) throw new IllegalStateException("Unexpected response in bundle");
        return snapshot;
    }

    private static final class ReplayClient extends TagoMetadataClient {
        private final List<Entry> entries;
        private final List<byte[]> bodies;
        private int index;
        ReplayClient(List<Entry> entries, List<byte[]> bodies) { super(null, null); this.entries = entries; this.bodies = bodies; }
        private byte[] next(Request request) {
            if (index >= entries.size() || !request.equals(entries.get(index).request())) throw new IllegalStateException("Response missing or out of order");
            return bodies.get(index++);
        }
        @Override public List<City> cityCodes() { return parseCityCodes(next(new Request(CITY_CODES, null, null, 0))); }
        @Override public Page<RawItem<Route>> routesWithRaw(String city, int page) { return parseRoutes(next(new Request(ROUTES, city, null, page))); }
        @Override public Page<RawItem<Stop>> routeStopsWithRaw(String city, String route, int page) { return parseRouteStops(next(new Request(ROUTE_STOPS, city, route, page))); }
    }

    private static List<String> checkedCities(List<String> cities) {
        if (cities == null || cities.isEmpty() || cities.stream().anyMatch(c -> c == null || !c.matches("[0-9]{5}"))
                || new HashSet<>(cities).size() != cities.size()) throw new IllegalArgumentException("Invalid city scope");
        return List.copyOf(cities);
    }
    private static void validateRequest(Request r, ArchiveContentSafety safety) {
        if (r == null || r.operation() == null) throw new IllegalArgumentException("Invalid request");
        if (r.operation().equals(TagoMetadataClient.CITY_CODES) && r.cityCode() == null && r.routeId() == null && r.pageNo() == 0) return;
        if (r.cityCode() == null || !r.cityCode().matches("[0-9]{5}") || r.pageNo() < 1) throw new IllegalArgumentException("Invalid request");
        if (r.operation().equals(TagoMetadataClient.ROUTES) && r.routeId() == null) return;
        if (!r.operation().equals(TagoMetadataClient.ROUTE_STOPS) || r.routeId() == null || r.routeId().isBlank() || r.routeId().length() > 256)
            throw new IllegalArgumentException("Invalid request");
        safety.checkText(r.routeId());
    }
    private static Path safeDirectory(Path directory) throws IOException {
        if (directory == null || !directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Existing absolute directory required");
        Path absolute = directory.normalize();
        if (!absolute.toRealPath().equals(absolute)) throw new IllegalArgumentException("Symbolic link directory is not allowed");
        return absolute;
    }
    private static void ensureOutsideGit(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent())
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Bundle must be outside Git");
    }
    private static void writeNew(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        boolean published = false;
        try {
            Files.write(temp, bytes);
            Files.createLink(target, temp);
            published = true;
        } finally {
            if (published) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Orphan temp is not a published response. */ }
            } else {
                Files.deleteIfExists(temp);
            }
        }
    }
    private static ArchiveContentSafety safety(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Service key is required for content inspection");
        return new ArchiveContentSafety(key);
    }
    private static void checkContent(byte[] bytes, ArchiveContentSafety safety) {
        try { safety.checkJson(MAPPER.readTree(bytes)); }
        catch (RuntimeException e) { throw new IllegalStateException("Response content could not be safely stored"); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
