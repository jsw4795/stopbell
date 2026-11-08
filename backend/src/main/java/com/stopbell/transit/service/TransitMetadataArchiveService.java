package com.stopbell.transit.service;

import com.stopbell.transit.domain.TransitSourceSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent archive; callers must never put credentials in the logical identifier or content. */
@Service
public class TransitMetadataArchiveService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String directory;
    private final TransactionTemplate transaction;

    public TransitMetadataArchiveService(JdbcTemplate jdbc, @Qualifier("transitMetadataClock") Clock clock,
            @Value("${transit.metadata.archive.directory:}") String directory, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.directory = directory;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void validateStorage() {
        try { root(); } catch (RuntimeException e) { throw new ArchiveFailure(); }
    }

    public static final class ArchiveFailure extends IllegalStateException {
        public ArchiveFailure() { super("Archive storage unavailable"); }
    }

    /** Identity is scoped to a run and source. Content is committed before its metadata. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized Archive store(long runId, TransitSourceSystem source, String logicalId, byte[] bytes) {
        try { return storeInternal(runId, source, logicalId, bytes); }
        catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) { throw new ArchiveFailure(); }
    }

    private Archive storeInternal(long runId, TransitSourceSystem source, String logicalId, byte[] bytes) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(bytes);
        if (runId <= 0 || logicalId == null || !logicalId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid archive identity");
        }
        byte[] content = bytes.clone();
        String keyHash = hash(logicalId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String name = hash((runId + ":" + source.name() + ":" + keyHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String checksum = hash(content);
        Path root = root();
        var rows = jdbc.query("SELECT provider FROM transit_metadata_collection_runs WHERE id = ?",
                (rs, n) -> rs.getString(1), runId);
        if (rows.size() != 1 || !rows.getFirst().equals(source == TransitSourceSystem.SEOUL_TDATA ? "SEOUL_BUS" : "TAGO")) {
            throw new IllegalArgumentException("Collection run and source do not match");
        }
        var existing = lookup(runId, source, keyHash);
        if (existing != null) {
            if (existing.sizeBytes() != content.length || !existing.sha256().equals(checksum)) {
                throw new IllegalStateException("Archive identity conflict");
            }
            verifyFile(root, existing);
            return existing;
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".archive-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Path target = root.resolve(name);
            try {
                // A hard link publishes the complete inode without ever replacing an existing target.
                Files.createLink(target, temporary);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A previous crash or failed DB commit may have left this identity's file.
            }
            Archive candidate = new Archive(0, runId, source, keyHash, name, checksum, content.length, clock.instant());
            verifyFile(root, candidate);
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO transit_metadata_archives
                    (collection_run_id, source_system, logical_key_hash, file_name, sha256, size_bytes, stored_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, runId, source.name(), keyHash, name, checksum, content.length, Timestamp.from(candidate.storedAt())));
            return Objects.requireNonNull(lookup(runId, source, keyHash));
        } catch (IOException e) {
            throw new IllegalStateException("Archive file operation failed");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* orphan cleanup is manual */ }
            }
        }
    }

    public Archive find(long id) {
        var rows = jdbc.query("SELECT * FROM transit_metadata_archives WHERE id = ?", this::map, id);
        if (rows.size() != 1) throw new IllegalArgumentException("Archive not found");
        return rows.getFirst();
    }

    public void verify(long id) { verifyFile(root(), find(id)); }

    private Archive lookup(long runId, TransitSourceSystem source, String keyHash) {
        var rows = jdbc.query("SELECT * FROM transit_metadata_archives WHERE collection_run_id = ? AND source_system = ? AND logical_key_hash = ?",
                this::map, runId, source.name(), keyHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Archive map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Archive(rs.getLong("id"), rs.getLong("collection_run_id"), TransitSourceSystem.valueOf(rs.getString("source_system")),
                rs.getString("logical_key_hash"), rs.getString("file_name"), rs.getString("sha256"),
                rs.getLong("size_bytes"), rs.getTimestamp("stored_at").toInstant());
    }

    private Path root() {
        if (directory == null || directory.isBlank()) throw new IllegalStateException("Archive directory is not configured");
        try {
            Path path = Path.of(directory);
            if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalStateException("Invalid archive directory");
            Path real = path.toRealPath();
            if (!Files.isDirectory(real) || !Files.isWritable(real)) throw new IllegalStateException("Archive directory is unavailable");
            return real;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new IllegalStateException("Archive directory is unavailable");
        }
    }

    private void verifyFile(Path root, Archive archive) {
        if (!archive.fileName().matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid archive file name");
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(root.resolve(archive.fileName()),
                java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = in.read(buffer)) != -1) { digest.update(buffer, 0, count); size += count; }
            if (size != archive.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(archive.sha256())) {
                throw new IllegalStateException("Archive integrity check failed");
            }
        } catch (IOException e) { throw new IllegalStateException("Archive file unavailable"); }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    public record Archive(long id, long collectionRunId, TransitSourceSystem source, String logicalKeyHash,
            String fileName, String sha256, long sizeBytes, Instant storedAt) { }
}
