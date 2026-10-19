package com.stopbell.boarding.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import org.springframework.stereotype.Component;

/** Default closed after restart. Step 3 will admit only after recovery commit acknowledgement. */
@Component
public class BoardingTrackingRuntime {
    private final ConcurrentHashMap<Long, Long> revisions = new ConcurrentHashMap<>();

    public void admitCreated(Long id) {
        revisions.putIfAbsent(id, -1L);
    }

    public boolean isAdmitted(Long id) {
        return revisions.containsKey(id);
    }

    public void committed(Long id, long revision, boolean tracking) {
        revisions.computeIfPresent(id, (key, current) -> Math.max(current, tracking ? revision : -1));
    }

    public void forget(Long id) {
        revisions.remove(id);
    }

    public Set<Long> admittedIds() {
        return Set.copyOf(revisions.keySet());
    }

    public void retainLoaded(Set<Long> ids, Set<Long> beforeRead) {
        // Do not evict a Session created/admitted concurrently with the database snapshot read.
        beforeRead.stream().filter(id -> !ids.contains(id)).forEach(revisions::remove);
    }

    public BoardingSessionResponse response(BoardingSession session, LocalDateTime now) {
        var checkpoint = session.getCheckpoint();
        boolean ready = session.getStatus() == BoardingSessionStatus.TRACKING
                && Long.valueOf(session.getRevision()).equals(revisions.get(session.getId()))
                && now.isBefore(session.getExpiresAt()) && !checkpoint.getObservedAt().isAfter(now)
                && !checkpoint.getObservedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                && (checkpoint.getProviderDataTime() == null || (!checkpoint.getProviderDataTime().isAfter(now)
                    && !checkpoint.getProviderDataTime().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))));
        return BoardingSessionResponse.from(session, ready
                ? session.getTraversal().targetIndex() - checkpoint.getIndex() : null);
    }
}
