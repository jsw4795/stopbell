package com.stopbell.transit.metadata;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/** Local staging and publication for one collection root. */
final class CollectionOutput {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);
    private final Path root;

    CollectionOutput(Path root) {
        this.root = root;
    }

    Path incomplete(Instant startedAt) throws IOException {
        return directories("incomplete", date(startedAt));
    }

    Path diagnostics(Instant failedAt) throws IOException {
        return directories(date(failedAt), "diagnostics");
    }

    Path publish(Path staged, String provider, Instant startedAt) throws IOException {
        requireRealDirectory(staged);
        Path destination = directories(date(startedAt), provider, TIME.format(startedAt.atZone(SEOUL)))
                .resolve(staged.getFileName().toString());
        // mkdir reserves this exact UUID across processes. ATOMIC_MOVE may replace an existing
        // directory, so only invoke it after creating our own empty reservation.
        Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        try {
            return Files.move(staged, destination, ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.delete(destination);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Path directories(String... segments) throws IOException {
        Path current = root;
        requireRealDirectory(current);
        for (String segment : segments) {
            current = current.resolve(segment);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException ignored) {
                // Other runs may already have created this shared parent.
            }
            requireRealDirectory(current);
            if (Files.exists(current.resolve(".git"), NOFOLLOW_LINKS)) {
                throw new IOException("Output must be outside Git");
            }
        }
        return current;
    }

    private static void requireRealDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Output requires a real directory");
        }
    }

    private static String date(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate().toString();
    }
}
