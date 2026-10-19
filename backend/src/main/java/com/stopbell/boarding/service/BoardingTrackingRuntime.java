package com.stopbell.boarding.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.transit.domain.TransitProvider;
import org.springframework.stereotype.Component;

/** Process-local, default-closed gate. Revision watermarks survive closure to reject late callbacks. */
@Component
public class BoardingTrackingRuntime {
    private record Entry(long revision, boolean registered, BoardingTrackingSnapshot validated) {
        BoardingCheckpoint checkpoint() { return validated == null ? null : validated.checkpoint(); }
    }
    private final ConcurrentSkipListMap<Long, Entry> revisions = new ConcurrentSkipListMap<>();

    // Ordered lookup index of validated entries within this same runtime gate, not a second authority.
    private final ConcurrentSkipListMap<Long, BoardingTrackingSnapshot> ready = new ConcurrentSkipListMap<>();

    private Entry indexed(Long id, Entry entry) {
        if (entry.validated() == null) {
            ready.remove(id);
        } else {
            ready.put(id, entry.validated());
        }
        return entry;
    }

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
            return indexed(id, new Entry(revision, current != null && current.registered(), tracking
                    && current != null && current.revision() == revision ? current.validated() : null));
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
                ? current : indexed(id, new Entry(session.revision(), true, session)));
    }

    /** Explicit process-state reset, also used by isolated restart fixtures. */
    public void forget(Long id) {
        revisions.computeIfPresent(id, (key, current) -> {
            ready.remove(key);
            return null;
        });
    }

    public Set<Long> admittedIds() {
        return revisions.entrySet().stream().filter(entry -> entry.getValue().registered())
                .map(java.util.Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public void retainLoaded(Set<Long> ids, Set<Long> beforeRead) {
        // Preserve concurrently created sessions and revision watermarks of ended sessions.
        beforeRead.stream().filter(id -> !ids.contains(id)).forEach(id -> revisions.computeIfPresent(id,
                (key, current) -> indexed(key, new Entry(current.revision(), false, null))));
    }

    public boolean isReady(BoardingTrackingSnapshot session, LocalDateTime now) {
        var entry = revisions.get(session.id());
        var checkpoint = session.checkpoint();
        boolean progressValid = checkpoint.getIndex() < session.traversal().targetIndex()
                && (session.provider() != TransitProvider.SEOUL_BUS
                    || checkpoint.getProviderDataTime() != null)
                && (checkpoint.getProviderDataTime() == null
                    || !checkpoint.getProviderDataTime().isAfter(checkpoint.getObservedAt()));
        boolean fresh = now.isBefore(session.expiresAt()) && !checkpoint.getObservedAt().isAfter(now)
                && !checkpoint.getObservedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                && (checkpoint.getProviderDataTime() == null || (!checkpoint.getProviderDataTime().isAfter(now)
                    && !checkpoint.getProviderDataTime().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))));
        if ((!fresh || !progressValid) && entry != null && entry.revision() == session.revision()) {
            if (revisions.replace(session.id(), entry, new Entry(entry.revision(), entry.registered(), null))
                    && entry.validated() != null) {
                ready.remove(session.id(), entry.validated());
            }
        }
        return session.status() == BoardingSessionStatus.TRACKING && entry != null && checkpoint.equals(entry.checkpoint())
                && entry.registered() && entry.revision() == session.revision() && progressValid && fresh
                && entry.equals(revisions.get(session.id()));
    }

    public record ReadyWindow(List<Long> ids, long cursor) { }

    /** Bounded keyset window of validated entries; recovery/hold tombstones never occupy it. */
    public ReadyWindow readyWindow(long afterId, int limit, LocalDateTime now) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Ready window requires a positive bound");
        }
        if (ready.higherKey(afterId) == null) {
            afterId = 0;
        }
        var ids = new ArrayList<Long>();
        long cursor = 0;
        int visited = 0;
        for (var item : ready.tailMap(afterId, false).entrySet()) {
            cursor = item.getKey();
            var snapshot = item.getValue();
            if (snapshot != null && isReady(snapshot, now)) {
                ids.add(item.getKey());
            }
            if (++visited == limit) {
                break;
            }
        }
        return new ReadyWindow(List.copyOf(ids), cursor);
    }

    public BoardingSessionResponse response(BoardingSession session, LocalDateTime now) {
        return BoardingSessionResponse.from(session, isReady(BoardingTrackingSnapshot.from(session), now)
                ? session.getTraversal().targetIndex() - session.getCheckpoint().getIndex() : null);
    }
}
