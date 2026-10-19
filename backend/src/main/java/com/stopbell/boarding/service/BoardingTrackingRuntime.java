package com.stopbell.boarding.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import org.springframework.stereotype.Component;

/** Process-local, default-closed gate. Revision watermarks survive closure to reject late callbacks. */
@Component
public class BoardingTrackingRuntime {
    private record Entry(long revision, boolean registered, BoardingCheckpoint checkpoint) { }
    private final ConcurrentHashMap<Long, Entry> revisions = new ConcurrentHashMap<>();

    public void admitCreated(Long id) {
        revisions.putIfAbsent(id, new Entry(-1, true, null));
    }

    public boolean isAdmitted(Long id) {
        var entry = revisions.get(id);
        return entry != null && entry.registered();
    }

    public void committed(Long id, long revision, boolean tracking) {
        revisions.compute(id, (key, current) -> {
            if (current != null && (revision < current.revision()
                    || (revision == current.revision() && current.checkpoint() == null && tracking))) {
                return current;
            }
            // Only verified() may open a session which has never been admitted in this process.
            return new Entry(revision, current != null && current.registered(), tracking
                    && current != null && current.revision() == revision ? current.checkpoint() : null);
        });
    }

    /** Called only with the exact state whose validation and database commit were acknowledged. */
    public void verified(BoardingTrackingSnapshot session) {
        if (session.status() != BoardingSessionStatus.TRACKING) {
            committed(session.id(), session.revision(), false);
            return;
        }
        revisions.compute(session.id(), (id, current) -> current != null
                && (current.revision() > session.revision()
                    || (current.revision() == session.revision() && current.checkpoint() == null))
                ? current : new Entry(session.revision(), true, session.checkpoint()));
    }

    /** Explicit process-state reset, also used by isolated restart fixtures. */
    public void forget(Long id) {
        revisions.remove(id);
    }

    public Set<Long> admittedIds() {
        return revisions.entrySet().stream().filter(entry -> entry.getValue().registered())
                .map(java.util.Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public void retainLoaded(Set<Long> ids, Set<Long> beforeRead) {
        // Preserve concurrently created sessions and revision watermarks of ended sessions.
        beforeRead.stream().filter(id -> !ids.contains(id)).forEach(id -> revisions.computeIfPresent(id,
                (key, current) -> new Entry(current.revision(), false, null)));
    }

    public boolean isReady(BoardingTrackingSnapshot session, LocalDateTime now) {
        var entry = revisions.get(session.id());
        var checkpoint = session.checkpoint();
        boolean fresh = now.isBefore(session.expiresAt()) && !checkpoint.getObservedAt().isAfter(now)
                && !checkpoint.getObservedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                && (checkpoint.getProviderDataTime() == null || (!checkpoint.getProviderDataTime().isAfter(now)
                    && !checkpoint.getProviderDataTime().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))));
        if (!fresh && entry != null && entry.revision() == session.revision()) {
            revisions.replace(session.id(), entry, new Entry(entry.revision(), entry.registered(), null));
        }
        return session.status() == BoardingSessionStatus.TRACKING && entry != null && checkpoint.equals(entry.checkpoint())
                && entry.revision() == session.revision() && fresh && entry.equals(revisions.get(session.id()));
    }

    public BoardingSessionResponse response(BoardingSession session, LocalDateTime now) {
        return BoardingSessionResponse.from(session, isReady(BoardingTrackingSnapshot.from(session), now)
                ? session.getTraversal().targetIndex() - session.getCheckpoint().getIndex() : null);
    }
}
