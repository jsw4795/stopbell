package com.stopbell.transit.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Offline GBIS capture; a manifest is published only after complete validation. */
public final class GbisLocalBundle {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private GbisLocalBundle() { }
    public enum Source { BASE_INFO, ROUTE, ROUTE_STATION }
    public record Entry(Source source, String file, long size, String sha256) { }
    public record ArchivedReplay(GbisCollectedSnapshot snapshot, Instant startedAt) { }
    public record Manifest(String id, String startedAt, String completedAt, boolean complete,
                           String sourceSystem, String routeVersion, String routeStationVersion, List<Entry> files) { }

    public static Writer create(Path parent, String key) throws IOException { return new Writer(parent, key); }
    public static final class Writer {
        private final String id = UUID.randomUUID().toString();
        private final String startedAt = Instant.now().toString();
        private final Path directory;
        private final String key;
        private final Map<Source, Entry> entries = new HashMap<>();
        private boolean closed;
        private Writer(Path parent, String key) throws IOException {
            Path root = safeDirectory(parent);
            ensureOutsideGit(root);
            requireKey(key);
            this.key = key;
            directory = Files.createDirectory(root.resolve(id));
        }
        public Path directory() { return directory; }
        public void add(Source source, byte[] bytes) throws IOException {
            if (closed) throw new IllegalStateException("Bundle is closed");
            try {
                safeDirectory(directory);
                ensureOutsideGit(directory);
                if (source == null || entries.containsKey(source)) throw new IllegalArgumentException("Invalid or duplicate source");
                if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty source");
                if (source == Source.BASE_INFO) GbisMetadataClient.parseBaseInfo(bytes, key);
                else {
                    String content = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    new ArchiveContentSafety(key).checkText(content);
                }
                String file = filename(source);
                writeNew(directory.resolve(file), bytes);
                entries.put(source, new Entry(source, file, bytes.length, digest(bytes)));
            } catch (RuntimeException | IOException e) { closed = true; throw e; }
        }
        public GbisCollectedSnapshot complete() throws IOException {
            return complete(() -> { });
        }
        GbisCollectedSnapshot complete(Runnable beforePublish) throws IOException {
            if (closed) throw new IllegalStateException("Bundle is closed");
            closed = true;
            if (entries.size() != Source.values().length) throw new IllegalStateException("Incomplete bundle");
            safeDirectory(directory);
            ensureOutsideGit(directory);
            var item = GbisMetadataClient.parseBaseInfo(readEntry(directory, entries.get(Source.BASE_INFO)), key);
            Manifest manifest = new Manifest(id, startedAt, Instant.now().toString(), true, "GBIS",
                    item.routeVersion(), item.routeStationVersion(), List.of(entries.get(Source.BASE_INFO),
                    entries.get(Source.ROUTE), entries.get(Source.ROUTE_STATION)));
            var snapshot = replay(directory, manifest, key);
            beforePublish.run();
            writeNew(directory.resolve("manifest.json"), MAPPER.writeValueAsBytes(manifest));
            return snapshot;
        }
    }
    public static GbisCollectedSnapshot replay(Path directory, String key) throws IOException {
        return replayWithArchive(directory, key, (source, bytes) -> {}).snapshot();
    }
    public static ArchivedReplay replayWithArchive(Path directory, String key, java.util.function.BiConsumer<Source, byte[]> archive) throws IOException {
        java.util.Objects.requireNonNull(archive);
        Path root = safeDirectory(directory);
        ensureOutsideGit(root);
        requireKey(key);
        Path file = root.resolve("manifest.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Complete manifest missing");
        Manifest manifest;
        try { manifest = MAPPER.readValue(Files.readAllBytes(file), Manifest.class); }
        catch (RuntimeException e) { throw new IllegalStateException("Invalid manifest"); }
        var snapshot = replay(root, manifest, key, archive);
        return new ArchivedReplay(snapshot, Instant.parse(manifest.startedAt()));
    }
    private static GbisCollectedSnapshot replay(Path root, Manifest manifest, String key) throws IOException {
        return replay(root, manifest, key, (source, bytes) -> {});
    }
    private static GbisCollectedSnapshot replay(Path root, Manifest manifest, String key, java.util.function.BiConsumer<Source, byte[]> archive) throws IOException {
        if (manifest == null || !manifest.complete() || !root.getFileName().toString().equals(manifest.id())
                || !"GBIS".equals(manifest.sourceSystem()) || manifest.files() == null
                || manifest.files().size() != Source.values().length) throw new IllegalStateException("Incomplete manifest");
        try {
            if (Instant.parse(manifest.completedAt()).isBefore(Instant.parse(manifest.startedAt()))) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalStateException("Invalid collection time"); }
        Map<Source, byte[]> bodies = new HashMap<>();
        for (Entry entry : manifest.files()) {
            if (entry == null || entry.source() == null || bodies.containsKey(entry.source())) throw new IllegalStateException("Invalid entry");
            bodies.put(entry.source(), readEntry(root, entry));
        }
        var item = GbisMetadataClient.parseBaseInfo(bodies.get(Source.BASE_INFO), key);
        if (!item.routeVersion().equals(manifest.routeVersion()) || !item.routeStationVersion().equals(manifest.routeStationVersion()))
            throw new IllegalStateException("Manifest version mismatch");
        var snapshot = GbisBulkMetadata.parseWithRaw(bodies.get(Source.ROUTE), bodies.get(Source.ROUTE_STATION),
                item.routeVersion(), item.routeStationVersion());
        ArchiveContentSafety safety = new ArchiveContentSafety(key);
        snapshot.rawRows().forEach(row -> row.fields().forEach((name, value) -> {
            ArchiveContentSafety.checkField(name);
            safety.checkText(value);
        }));
        for (Source source : Source.values()) archive.accept(source, bodies.get(source));
        return snapshot;
    }
    private static byte[] readEntry(Path root, Entry entry) throws IOException {
        if (entry == null || entry.source() == null || !filename(entry.source()).equals(entry.file())
                || entry.size() <= 0 || entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Invalid entry");
        Path path = root.resolve(entry.file());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Source missing");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != entry.size() || !digest(bytes).equals(entry.sha256())) throw new IllegalStateException("Source integrity failure");
        return bytes;
    }
    private static String filename(Source source) {
        return switch (source) {
            case BASE_INFO -> "base-info.json";
            case ROUTE -> "route.dat";
            case ROUTE_STATION -> "route-station.dat";
        };
    }
    private static Path safeDirectory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Existing absolute directory required");
        Path absolute = path.normalize();
        if (!absolute.toRealPath().equals(absolute)) throw new IllegalArgumentException("Symbolic link directory is not allowed");
        return absolute;
    }
    private static void ensureOutsideGit(Path path) {
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
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Unpublished temporary link. */ }
            } else Files.deleteIfExists(temp);
        }
    }
    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Service key is required for content inspection");
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
