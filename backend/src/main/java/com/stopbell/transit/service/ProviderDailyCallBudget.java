package com.stopbell.transit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory per-day (KST) call budget of the vehicle view, per Provider. It only counts this feature's
 * calls (Alarm monitoring has its own traffic) and resets on restart.
 */
@Component
public class ProviderDailyCallBudget {

    public enum Source { SEOUL, TAGO, GBIS }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final Map<Source, Integer> limits = new EnumMap<>(Source.class);
    private final Map<Source, Integer> used = new EnumMap<>(Source.class);
    private LocalDate day;

    public ProviderDailyCallBudget(@Qualifier("transitMetadataClock") Clock clock,
            @Value("${transit.vehicle-view.daily-limit.seoul:800}") int seoul,
            @Value("${transit.vehicle-view.daily-limit.tago:8000}") int tago,
            @Value("${transit.vehicle-view.daily-limit.gbis:500}") int gbis) {
        this.clock = clock;
        limits.put(Source.SEOUL, seoul);
        limits.put(Source.TAGO, tago);
        limits.put(Source.GBIS, gbis);
    }

    /** @return true and counts the call when the Provider still has budget today */
    public synchronized boolean tryAcquire(Source source) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (!today.equals(day)) {
            day = today;
            used.clear();
        }
        int count = used.getOrDefault(source, 0);
        if (count >= limits.get(source)) {
            return false;
        }
        used.put(source, count + 1);
        return true;
    }
}
