package com.stopbell.transit.service;

import com.stopbell.transit.domain.TransitProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local database inspection only; does not collect or change metadata. */
@Service
public class TransitMetadataPreflightService {
    private final JdbcTemplate jdbc;
    private final String archiveDirectory;

    public TransitMetadataPreflightService(JdbcTemplate jdbc, @Value("${transit.metadata.archive.directory:}") String archiveDirectory) {
        this.jdbc = jdbc;
        this.archiveDirectory = archiveDirectory;
    }

    @Transactional(readOnly = true)
    public Result inspect(TransitProvider provider) {
        if (provider == null) throw new IllegalArgumentException("Provider required");
        String p = provider.name();
        Map<String, Object> normalized = row("""
                select (select count(*) from bus_routes where provider=?) routes,
                  (select count(*) from bus_stops where provider=?) stops,
                  (select count(*) from bus_route_stop_occurrences o join bus_routes r on r.id=o.route_id where r.provider=?) occurrences,
                  (select count(*) from bus_stops s where s.provider=? and not exists (select 1 from bus_route_stop_occurrences o where o.stop_id=s.id)) orphan_stops,
                  (select count(*) from bus_stops where provider=? and (latitude is null or longitude is null)) stops_missing_coordinates,
                  (select count(*) from (select o.route_id,o.stop_id from bus_route_stop_occurrences o join bus_routes r on r.id=o.route_id where r.provider=? group by o.route_id,o.stop_id having count(*)>1) repeats) repeated_route_stop_pairs
                """, p, p, p, p, p, p);
        var routeTypes = rows("select route_type, count(*) total from bus_routes where provider=? group by route_type order by route_type", p);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String source : provider == TransitProvider.SEOUL_BUS ? List.of("SEOUL_TDATA") : List.of("TAGO", "GBIS")) {
            for (String kind : List.of("ROUTE", "STOP", "OCCURRENCE")) {
                var aggregate = row("""
                        select count(*) total,
                          coalesce(sum(case when source_version is not null and trim(source_version) <> '' then 1 else 0 end),0) with_version,
                          count(distinct source_version) distinct_versions, min(collected_at) earliest_collected_at,
                          max(collected_at) latest_collected_at,
                          coalesce(sum(case when (data_kind='ROUTE' and route_id is not null) or (data_kind='STOP' and stop_id is not null)
                            or (data_kind='OCCURRENCE' and occurrence_id is not null) then 1 else 0 end),0) linked
                        from transit_source_metadata where source_system=? and data_kind=?
                        """, source, kind);
                Map<String, Object> sourceRow = new LinkedHashMap<>();
                sourceRow.put("source_system", source);
                sourceRow.put("data_kind", kind);
                sourceRow.putAll(aggregate);
                sources.add(Collections.unmodifiableMap(sourceRow));
            }
        }
        var collectedDistribution = rows("""
                select source_system,date(collected_at) collected_date,count(*) total from transit_source_metadata
                where source_system in ('SEOUL_TDATA','TAGO','GBIS') and
                  ((?='SEOUL_BUS' and source_system='SEOUL_TDATA') or (?='TAGO' and source_system in ('TAGO','GBIS')))
                group by source_system,date(collected_at) order by source_system,collected_date
                """, p, p);
        var runs = rows("""
                select id,status,started_at,finished_at,failure_stage,failure_code from transit_metadata_collection_runs r
                where provider=? and (status in ('COLLECTING','COLLECTED','SYNCING') or id in
                  (select id from (select id,row_number() over (partition by status order by finished_at desc,id desc) n
                    from transit_metadata_collection_runs where provider=? and status in ('COMPLETED','FAILED','INTERRUPTED')) ranked where n=1))
                order by started_at desc,id desc
                """, p, p);
        var sync = rows("select last_complete_sync_at from bus_metadata_sync_states where provider=?", p);
        var latest = rows("select id from transit_metadata_collection_runs where provider=? order by started_at desc,id desc limit 1", p);
        Long latestId = latest.isEmpty() ? null : ((Number) latest.getFirst().get("id")).longValue();
        List<Map<String, Object>> archives = latestId == null ? List.of() : rows(
                "select id,source_system,file_name,size_bytes,stored_at from transit_metadata_archives where collection_run_id=? order by source_system,id", latestId);
        List<ArchiveStatus> archiveStatuses = archives.stream().map(this::archiveStatus).toList();
        var unsafe = row("""
                select coalesce(sum(case when failure_code='UNSAFE_CHANGE' then 1 else 0 end),0) unsafe_runs,
                       coalesce(sum(case when failure_code='REVIEW_SAVE_FAILED' then 1 else 0 end),0) review_save_failed_runs
                from transit_metadata_collection_runs where provider=?
                """, p);
        var reviewFailures = rows("select id,started_at,finished_at,failure_stage,failure_code from transit_metadata_collection_runs where provider=? and failure_code='REVIEW_SAVE_FAILED' order by id desc", p);
        var pending = rows("""
                select r.id,r.finished_at,count(d.id) details from transit_metadata_collection_runs r
                join transit_metadata_unsafe_change_details d on d.collection_run_id=r.id
                left join transit_metadata_change_approvals a on a.source_run_id=r.id
                where r.provider=? and r.failure_code='UNSAFE_CHANGE' and a.id is null
                group by r.id,r.finished_at order by r.id desc
                """, p);
        var approvals = rows("select id,source_run_id,apply_run_id,status,approved_at,expires_at from transit_metadata_change_approvals where provider=? and status in ('APPROVED','CLAIMED','FAILED','UNCERTAIN') order by id desc", p);
        List<String> warnings = new ArrayList<>();
        if (((Number) normalized.get("routes")).longValue() == 0) warnings.add("정규화 Route가 없습니다.");
        if (runs.isEmpty()) warnings.add("수집 이력이 없습니다.");
        if (archiveStatuses.isEmpty()) warnings.add("최신 수집 실행의 아카이브가 없습니다.");
        if (archiveStatuses.stream().anyMatch(a -> !a.available())) warnings.add("접근할 수 없거나 크기가 다른 아카이브가 있습니다.");
        boolean incomplete = runs.stream().anyMatch(r -> List.of("COLLECTING", "COLLECTED", "SYNCING").contains(r.get("status")));
        if (incomplete) warnings.add("미완료 수집 실행의 실행 여부와 상태를 먼저 확인해야 합니다.");
        if (((Number) normalized.get("orphan_stops")).longValue() > 0) warnings.add("기존 고아 Stop이 있습니다.");
        if (((Number) normalized.get("stops_missing_coordinates")).longValue() > 0) warnings.add("좌표가 없는 Stop이 있습니다.");
        if (!approvals.isEmpty()) warnings.add("기존 승인 상태를 검토해야 합니다.");
        if (!pending.isEmpty()) warnings.add("검토 대기 위험 변경이 있습니다.");
        if (approvals.stream().anyMatch(a -> List.of("CLAIMED", "UNCERTAIN").contains(a.get("status")))) warnings.add("CLAIMED 또는 UNCERTAIN 승인을 확인해야 합니다.");
        if (((Number) unsafe.get("review_save_failed_runs")).longValue() > 0) warnings.add("위험 변경 상세 저장 실패 이력이 있습니다.");
        warnings.add("과거 동기화 실패에서 일부 Route가 저장됐을 수 있으며 마지막 성공 시각은 현재 DB와 성공 Snapshot의 일치를 보장하지 않습니다.");
        // The universal caveat is informational and does not by itself determine readiness.
        Status status = incomplete ? Status.BLOCKED : warnings.size() > 1 ? Status.WARNING : Status.READY;
        return new Result(provider, status, normalized, routeTypes, List.copyOf(sources), collectedDistribution, runs, sync, latestId,
                archiveStatuses, unsafe, reviewFailures, pending, approvals, List.copyOf(warnings));
    }

    private ArchiveStatus archiveStatus(Map<String, Object> archive) {
        String name = (String) archive.get("file_name");
        Long actualSize = null;
        try {
            Path root = Path.of(archiveDirectory);
            if (root.isAbsolute() && root.normalize().equals(root) && name.matches("[0-9a-f]{64}")) {
                Path file = root.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(file)) actualSize = Files.size(file);
            }
        } catch (IOException | RuntimeException ignored) { /* report unavailable */ }
        return new ArchiveStatus(archive, actualSize, actualSize != null && actualSize == ((Number) archive.get("size_bytes")).longValue());
    }

    private Map<String, Object> row(String sql, Object... args) { return rows(sql, args).getFirst(); }
    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(r -> {
            Map<String, Object> copy = new LinkedHashMap<>();
            r.forEach((key, value) -> copy.put(key, value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant() : value instanceof java.sql.Date date ? date.toLocalDate() : value));
            return Collections.unmodifiableMap(copy);
        }).toList();
    }

    public enum Status { READY, WARNING, BLOCKED }
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> value) {
        return value.stream().map(TransitMetadataPreflightService::immutableMap).toList();
    }
    public record ArchiveStatus(Map<String, Object> metadata, Long actualSizeBytes, boolean available) {
        public ArchiveStatus { metadata = immutableMap(metadata); }
    }
    public record Result(TransitProvider provider, Status status, Map<String, Object> normalized,
            List<Map<String, Object>> routeTypes, List<Map<String, Object>> sources, List<Map<String, Object>> collectedDistribution, List<Map<String, Object>> runs,
            List<Map<String, Object>> syncState, Long latestRunId, List<ArchiveStatus> archives,
            Map<String, Object> unsafeRuns, List<Map<String, Object>> reviewFailures, List<Map<String, Object>> pendingReviews,
            List<Map<String, Object>> approvals, List<String> warnings) {
        public Result {
            normalized = immutableMap(normalized);
            routeTypes = immutableRows(routeTypes);
            sources = immutableRows(sources);
            collectedDistribution = immutableRows(collectedDistribution);
            runs = immutableRows(runs);
            syncState = immutableRows(syncState);
            archives = List.copyOf(archives);
            unsafeRuns = immutableMap(unsafeRuns);
            reviewFailures = immutableRows(reviewFailures);
            pendingReviews = immutableRows(pendingReviews);
            approvals = immutableRows(approvals);
            warnings = List.copyOf(warnings);
        }
    }
}
