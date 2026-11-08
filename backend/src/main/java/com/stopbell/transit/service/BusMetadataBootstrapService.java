package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.domain.TransitCollectionFailureCode;
import com.stopbell.transit.domain.TransitCollectionFailureStage;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.metadata.GbisMetadataClient;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BusMetadataBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(BusMetadataBootstrapService.class);
    private final Map<TransitProvider, BusMetadataSource> sources = new EnumMap<>(TransitProvider.class);
    private final Map<TransitProvider, AtomicBoolean> running = new EnumMap<>(TransitProvider.class);
    private final BusMetadataSyncService syncService;
    private final TransitMetadataCollectionHistoryService history;
    private final TransitMetadataArchiveService archive;
    private final TransitMetadataDiffAnalysisService analysis;
    private final TransitMetadataUnsafeChangeReviewService review;
    private final TransitMetadataChangeApprovalService approvals;
    private final Clock clock;

    public BusMetadataBootstrapService(List<BusMetadataSource> sources, BusMetadataSyncService syncService,
            TransitMetadataCollectionHistoryService history, TransitMetadataArchiveService archive,
            TransitMetadataDiffAnalysisService analysis, TransitMetadataUnsafeChangeReviewService review, TransitMetadataChangeApprovalService approvals, @Qualifier("transitMetadataClock") Clock clock) {
        for (BusMetadataSource source : sources) {
            if (this.sources.put(source.provider(), source) != null) {
                throw new IllegalStateException("Duplicate metadata source for " + source.provider());
            }
            running.put(source.provider(), new AtomicBoolean());
        }
        this.syncService = syncService;
        this.history = history;
        this.archive = archive;
        this.analysis = analysis;
        this.review = review;
        this.approvals = approvals;
        this.clock = clock;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void bootstrap(TransitProvider provider) {
        execute(provider, null);
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void bootstrapWithApproval(TransitProvider provider, long approvalId) {
        if (approvalId <= 0) throw new IllegalArgumentException("Approval ID must be positive");
        execute(provider, approvalId);
    }

    private void execute(TransitProvider provider, Long approvalId) {
        BusMetadataSource source = sources.get(provider);
        if (source == null) throw new IllegalArgumentException("No metadata source configured for " + provider);
        if (!(source instanceof SeoulCsvMetadataSource) && !(source instanceof TagoMetadataSource)) {
            throw new IllegalArgumentException("Archive-enabled metadata source required");
        }
        AtomicBoolean guard = running.get(provider);
        if (!guard.compareAndSet(false, true)) throw new IllegalStateException("Metadata sync is already running for " + provider);
        try {
            long runId = history.start(provider).getId();
            TransitCollectionFailureStage stage = TransitCollectionFailureStage.COLLECTION;
            boolean claimed = false;
            try {
                checkInterrupted();
                archive.validateStorage();
                Instant collectedAt = clock.instant();
                Runnable sync;
                Supplier<TransitMetadataDiffAnalysisService.Analysis> analyze;
                if (source instanceof SeoulCsvMetadataSource seoul) {
                    var snapshot = seoul.fetchSnapshotWithRawRows(runId, archive);
                    stage = TransitCollectionFailureStage.VALIDATION;
                    sync = syncService.prepareSeoulSnapshot(seoul, snapshot, collectedAt);
                    analyze = () -> analysis.analyzeAndStore(runId, seoul, snapshot);
                } else {
                    var snapshot = ((TagoMetadataSource) source).fetchSnapshotWithRawRows(runId, archive);
                    stage = TransitCollectionFailureStage.VALIDATION;
                    sync = syncService.prepareTagoSnapshot(snapshot, collectedAt);
                    analyze = () -> analysis.analyzeAndStore(runId, snapshot);
                }
                checkInterrupted();
                try { history.collected(runId); } catch (RuntimeException e) { throw new HistoryFailure(); }
                checkInterrupted();
                TransitMetadataDiffAnalysisService.Analysis result;
                try { result = analyze.get(); } catch (RuntimeException e) { throw new AnalysisFailure(e); }
                checkInterrupted();
                var safety = new BusMetadataSafetyGuard().evaluate(result);
                if (safety.decision() == BusMetadataSafetyGuard.Decision.BLOCK) {
                    try { review.save(runId, result, safety); } catch (RuntimeException e) { throw new ReviewFailure(e); }
                    checkInterrupted();
                    if (approvalId == null) {
                        log.warn("Unsafe metadata change blocked: provider={}, runId={}, reasons={}", provider, runId, safety.reasons());
                        throw new UnsafeChange();
                    }
                    // Re-read the current baseline with the already collected snapshot immediately before claim.
                    try {
                        analyze.get();
                        checkInterrupted();
                        approvals.claim(approvalId, runId, provider);
                        claimed = true;
                    } catch (RuntimeException e) { throw new ApprovalFailure(e); }
                } else if (approvalId != null) {
                    throw new ApprovalFailure(new IllegalStateException("Approval supplied for a safe change"));
                }
                try { history.syncing(runId); } catch (RuntimeException e) { throw new HistoryFailure(); }
                stage = TransitCollectionFailureStage.SYNC;
                checkInterrupted();
                sync.run();
            } catch (RuntimeException failure) {
                boolean interrupted = isInterrupted(failure);
                TransitCollectionFailureStage failureStage = stage;
                TransitCollectionFailureCode code = classify(failure, stage);
                if (stage == TransitCollectionFailureStage.COLLECTION && code == TransitCollectionFailureCode.INVALID_SOURCE) {
                    failureStage = TransitCollectionFailureStage.VALIDATION;
                }
                if (claimed) finishApproval(approvalId, runId, TransitMetadataChangeApprovalService.Status.FAILED);
                try {
                    if (interrupted) history.interrupt(runId);
                    else history.fail(runId, failureStage, code);
                } catch (RuntimeException recordFailure) {
                    throw new IllegalStateException("Bootstrap failed; collection history recording also failed: runId=" + runId
                            + ", stage=" + failureStage + ", code=" + code + ", interrupted=" + interrupted
                            + ", failureType=" + failure.getClass().getSimpleName()
                            + ", historyFailureType=" + recordFailure.getClass().getSimpleName());
                }
                throw new IllegalStateException("Bootstrap failed: runId=" + runId + ", stage=" + failureStage
                        + ", code=" + code + ", interrupted=" + interrupted);
            }
            // The committed provider sync may have succeeded even if the history commit response was lost.
            // complete is idempotent, so one retry is safe; never mislabel this as a failed sync.
            try {
                history.complete(runId);
            } catch (RuntimeException first) {
                try { history.complete(runId); }
                catch (RuntimeException second) {
                    if (claimed) finishApproval(approvalId, runId, TransitMetadataChangeApprovalService.Status.UNCERTAIN);
                    throw new IllegalStateException("Provider sync succeeded but completion history is uncertain: runId=" + runId);
                }
            }
            if (claimed) {
                try { approvals.finish(approvalId, runId, TransitMetadataChangeApprovalService.Status.APPLIED); }
                catch (RuntimeException e) {
                    finishApproval(approvalId, runId, TransitMetadataChangeApprovalService.Status.UNCERTAIN);
                    throw new IllegalStateException("Provider sync completed but approval outcome is uncertain: runId=" + runId);
                }
            }
            log.info("Metadata bootstrap completed: provider={}, runId={}", provider, runId);
        } finally {
            guard.set(false);
        }
    }

    private void finishApproval(long approvalId, long runId, TransitMetadataChangeApprovalService.Status status) {
        try { approvals.finish(approvalId, runId, status); }
        catch (RuntimeException e) {
            // A claimed approval must never be returned to the available state, even if outcome recording fails.
            log.error("Approval outcome recording uncertain: approvalId={}, runId={}", approvalId, runId);
        }
    }

    private static TransitCollectionFailureCode classify(RuntimeException failure, TransitCollectionFailureStage stage) {
        if (failure instanceof ApprovalFailure) return TransitCollectionFailureCode.APPROVAL_MISMATCH;
        if (failure instanceof ReviewFailure) return TransitCollectionFailureCode.REVIEW_SAVE_FAILED;
        if (failure instanceof UnsafeChange) return TransitCollectionFailureCode.UNSAFE_CHANGE;
        if (failure instanceof AnalysisFailure && failure.getCause() instanceof TransitMetadataDiffAnalysisService.InvalidSnapshotException)
            return TransitCollectionFailureCode.INVALID_SOURCE;
        if (failure instanceof HistoryFailure || failure instanceof AnalysisFailure) return TransitCollectionFailureCode.UNEXPECTED_FAILURE;
        if (stage == TransitCollectionFailureStage.SYNC) return TransitCollectionFailureCode.SYNC_FAILED;
        if (failure instanceof TransitMetadataArchiveService.ArchiveFailure) return TransitCollectionFailureCode.UNEXPECTED_FAILURE;
        if (failure instanceof SeoulCsvMetadataSource.SourceUnavailableException
                || failure instanceof GbisMetadataClient.SourceUnavailableException) return TransitCollectionFailureCode.SOURCE_UNAVAILABLE;
        if (failure instanceof TransitProviderClientException client) {
            return client.failureKind() == TransitProviderClientFailureKind.PROTOCOL
                    ? TransitCollectionFailureCode.INVALID_SOURCE : TransitCollectionFailureCode.SOURCE_UNAVAILABLE;
        }
        return TransitCollectionFailureCode.INVALID_SOURCE;
    }

    private static final class AnalysisFailure extends IllegalStateException {
        private AnalysisFailure(RuntimeException cause) { super(cause); }
    }

    private static final class ReviewFailure extends IllegalStateException {
        private ReviewFailure(RuntimeException cause) { super(cause); }
    }

    private static final class ApprovalFailure extends IllegalStateException {
        private ApprovalFailure(RuntimeException cause) { super(cause); }
    }

    private static final class HistoryFailure extends IllegalStateException { }
    private static final class UnsafeChange extends IllegalStateException { }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Bootstrap interrupted");
    }

    private static boolean isInterrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) {
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }
}
