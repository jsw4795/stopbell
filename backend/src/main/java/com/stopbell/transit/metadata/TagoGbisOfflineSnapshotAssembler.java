package com.stopbell.transit.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import com.stopbell.transit.service.TransitMetadataArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Replays complete bundles without network access and applies the existing GBIS matching policy. */
public final class TagoGbisOfflineSnapshotAssembler {
    private static final Logger log = LoggerFactory.getLogger(TagoGbisOfflineSnapshotAssembler.class);
    private TagoGbisOfflineSnapshotAssembler() { }

    public static TagoMetadataSource.TagoSnapshot tagoOnly(Path tagoDirectory, String serviceKey) throws IOException {
        return TagoLocalBundle.replay(tagoDirectory, serviceKey);
    }

    public static TagoMetadataSource.TagoSnapshot withGbis(Path tagoDirectory, Path gbisDirectory,
                                                            String serviceKey) throws IOException {
        var tago = TagoLocalBundle.replay(tagoDirectory, serviceKey);
        var gbis = GbisLocalBundle.replay(gbisDirectory, serviceKey);
        return assemble(tago, gbis);
    }

    public record ArchivedSnapshot(TagoMetadataSource.TagoSnapshot snapshot, Instant tagoStartedAt, Instant gbisStartedAt) { }

    /** Archives the exact bytes validated by each replay under the existing live-collection identities. */
    public static ArchivedSnapshot withGbisAndArchive(Path tagoDirectory, Path gbisDirectory, String serviceKey,
            long runId, TransitMetadataArchiveService archive) throws IOException {
        var tago = TagoLocalBundle.replayWithArchive(tagoDirectory, serviceKey, (request, bytes) -> {
            String key = switch (request.operation()) {
                case TagoMetadataClient.CITY_CODES -> "tago_city_codes";
                case TagoMetadataClient.ROUTES -> TagoMetadataSource.archiveKey("route", request.cityCode(), "", request.pageNo());
                case TagoMetadataClient.ROUTE_STOPS -> TagoMetadataSource.archiveKey("stops", request.cityCode(), request.routeId(), request.pageNo());
                default -> throw new IllegalStateException("Unexpected TAGO request");
            };
            archive.store(runId, TransitSourceSystem.TAGO, key, bytes);
        });
        var gbis = GbisLocalBundle.replayWithArchive(gbisDirectory, serviceKey, (source, bytes) -> {
            if (source == GbisLocalBundle.Source.ROUTE) archive.store(runId, TransitSourceSystem.GBIS, "gbis_route", bytes);
            if (source == GbisLocalBundle.Source.ROUTE_STATION) archive.store(runId, TransitSourceSystem.GBIS, "gbis_route_station", bytes);
        });
        return new ArchivedSnapshot(assemble(tago.snapshot(), gbis.snapshot()), tago.startedAt(), gbis.startedAt());
    }

    private static TagoMetadataSource.TagoSnapshot assemble(TagoMetadataSource.TagoSnapshot tago, GbisCollectedSnapshot gbis) {
        var enriched = GbisDestinationEnricher.enrich(tago.normalized().routes(), gbis.normalized());
        int routes = tago.normalized().routes().size();
        log.info("Offline GBIS destination enrichment: tagoRoutes={}, gbisRoutes={}, enrichedRoutes={}, skippedRoutes={}, destinationOccurrences={}, skipReasons={}",
                routes, gbis.normalized().routes().size(), enriched.enrichedRoutes(), routes - enriched.enrichedRoutes(),
                enriched.destinationOccurrences(), enriched.skippedRoutes());
        return new TagoMetadataSource.TagoSnapshot(
                new CompleteBusMetadataSnapshot(tago.normalized().provider(), enriched.routes()), tago.rawRows(), gbis);
    }
}
