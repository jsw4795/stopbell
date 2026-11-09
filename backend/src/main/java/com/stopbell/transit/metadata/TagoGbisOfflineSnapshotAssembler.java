package com.stopbell.transit.metadata;

import java.io.IOException;
import java.nio.file.Path;

import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
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
        var enriched = GbisDestinationEnricher.enrich(tago.normalized().routes(), gbis.normalized());
        int routes = tago.normalized().routes().size();
        log.info("Offline GBIS destination enrichment: tagoRoutes={}, gbisRoutes={}, enrichedRoutes={}, skippedRoutes={}, destinationOccurrences={}, skipReasons={}",
                routes, gbis.normalized().routes().size(), enriched.enrichedRoutes(), routes - enriched.enrichedRoutes(),
                enriched.destinationOccurrences(), enriched.skippedRoutes());
        return new TagoMetadataSource.TagoSnapshot(
                new CompleteBusMetadataSnapshot(tago.normalized().provider(), enriched.routes()), tago.rawRows(), gbis);
    }
}
