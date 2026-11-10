#!/usr/bin/env python3
"""Validate archived TOPIS bus search pages and generate local MySQL preview/apply SQL."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path


def read_bundle(bundle):
    pages = list(bundle.glob('page-*.json'))
    if not pages:
        raise ValueError('No TOPIS pages')
    rows = {}
    digest = sha256()
    total = page_size = None
    for page_no in range(1, len(pages) + 1):
        raw = (bundle / f'page-{page_no}.json').read_bytes()
        digest.update(sha256(raw).digest())
        page = json.loads(raw)
        info = page['paginationInfo']
        if page_no == 1:
            total = info['totalRecordCount']
            page_size = info['recordCountPerPage']
            if total <= 0 or page_size <= 0 or (total + page_size - 1) // page_size != len(pages):
                raise ValueError('Incomplete TOPIS bundle')
        if (info['currentPageNo'] != page_no or info['totalRecordCount'] != total
                or info['recordCountPerPage'] != page_size or int(page['TotalRows']) != total
                or len(page['rows']) != min(page_size, total - (page_no - 1) * page_size)):
            raise ValueError(f'Inconsistent TOPIS page {page_no}')
        for item in page['rows']:
            route_id = item['routId']
            if (not isinstance(route_id, str) or len(route_id) != 9
                    or not route_id.isascii() or not route_id.isdecimal()):
                raise ValueError(f'Invalid official route ID on page {page_no}')
            if route_id in rows:
                raise ValueError(f'Duplicate official route ID {route_id}')
            rows[route_id] = (route_id, field(item.get('routName'), 100),
                              field(item.get('stnFirst'), 255), field(item.get('stnLast'), 255), page_no)
    collected_at = datetime.fromisoformat((bundle / 'collected-at.txt').read_text().strip())
    if collected_at.utcoffset() is None:
        raise ValueError('Collection timestamp must include a timezone')
    return list(rows.values()), digest.hexdigest(), collected_at.astimezone(timezone.utc)


def field(value, limit):
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value if value and len(value) <= limit and value.isprintable() else None


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, int):
        return str(value)
    return 'CONVERT(0x' + value.encode('utf-8').hex() + ' USING utf8mb4)'


def write_sql(output, rows, digest, collected_at):
    values = ',\n'.join('(' + ','.join(map(literal, row)) + ')' for row in rows)
    preview = f"""-- Run with other metadata writers stopped. No persistent writes.
SET NAMES utf8mb4;
CREATE TEMPORARY TABLE topis_input (
    external_route_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
    route_number VARCHAR(100), start_stop_name VARCHAR(255), end_stop_name VARCHAR(255), source_page INT
);
INSERT INTO topis_input VALUES
{values};
START TRANSACTION;
SELECT COUNT(*) AS locked_seoul_routes FROM bus_routes WHERE provider='SEOUL_BUS' FOR UPDATE;
CREATE TEMPORARY TABLE topis_review AS
SELECT r.id AS route_id, r.external_route_id, r.route_number, t.route_number AS official_number,
       t.start_stop_name, t.end_stop_name, t.source_page,
       CASE WHEN t.external_route_id IS NULL THEN 'MISSING_ID'
            WHEN t.route_number IS NULL OR BINARY r.route_number <> BINARY t.route_number THEN 'NUMBER_MISMATCH'
            WHEN t.start_stop_name IS NULL OR t.end_stop_name IS NULL THEN 'MISSING_NAME'
            WHEN BINARY t.start_stop_name = BINARY t.end_stop_name THEN 'SAME_NAME'
            ELSE 'ELIGIBLE' END AS outcome
FROM bus_routes r LEFT JOIN topis_input t ON BINARY r.external_route_id = BINARY t.external_route_id
WHERE r.provider='SEOUL_BUS';
SELECT outcome, COUNT(*) FROM topis_review GROUP BY outcome ORDER BY outcome;
SELECT route_id, external_route_id, route_number, official_number, outcome
FROM topis_review WHERE outcome <> 'ELIGIBLE' ORDER BY outcome, external_route_id;
-- Review before applying. ROLLBACK when running preview alone.
"""
    apply = f"""-- Use only in the same transaction after reviewing preview. Verify before COMMIT.
SET @collection_time = '{collected_at.strftime('%Y-%m-%d %H:%M:%S.%f')}';
SET @bundle_sha256 = '{digest}';
SET @bundle_allowed = EXISTS (SELECT 1 FROM topis_review WHERE outcome='ELIGIBLE') AND NOT EXISTS (
    SELECT 1 FROM seoul_route_endpoints
    WHERE collected_at > @collection_time
       OR (collected_at = @collection_time AND bundle_sha256 <> @bundle_sha256)
);
SELECT @bundle_allowed AS bundle_allowed;
DELETE e FROM seoul_route_endpoints e LEFT JOIN topis_review r
  ON r.route_id=e.route_id AND r.outcome='ELIGIBLE'
WHERE @bundle_allowed AND r.route_id IS NULL;
SELECT ROW_COUNT() AS removed_supplemental_rows;
INSERT INTO seoul_route_endpoints
    (route_id, external_route_id, route_number, start_stop_name, end_stop_name,
     collected_at, bundle_sha256, source_page)
SELECT incoming.route_id, incoming.external_route_id, incoming.route_number,
       incoming.start_stop_name, incoming.end_stop_name, @collection_time, @bundle_sha256, incoming.source_page
FROM topis_review incoming
LEFT JOIN seoul_route_endpoints existing ON existing.route_id = incoming.route_id
WHERE @bundle_allowed AND incoming.outcome = 'ELIGIBLE'
  AND (existing.route_id IS NULL OR existing.bundle_sha256 <> @bundle_sha256
       OR existing.collected_at <> @collection_time OR existing.source_page <> incoming.source_page
       OR BINARY existing.external_route_id <> BINARY incoming.external_route_id
       OR BINARY existing.route_number <> BINARY incoming.route_number
       OR BINARY existing.start_stop_name <> BINARY incoming.start_stop_name
       OR BINARY existing.end_stop_name <> BINARY incoming.end_stop_name)
ON DUPLICATE KEY UPDATE external_route_id=incoming.external_route_id, route_number=incoming.route_number,
    start_stop_name=incoming.start_stop_name, end_stop_name=incoming.end_stop_name,
    collected_at=@collection_time, bundle_sha256=@bundle_sha256, source_page=incoming.source_page;
SELECT ROW_COUNT() AS affected_rows;
SELECT COUNT(*) AS eligible_rows_verified FROM topis_review r JOIN seoul_route_endpoints e
  ON e.route_id=r.route_id AND BINARY e.external_route_id=BINARY r.external_route_id
 AND BINARY e.route_number=BINARY r.route_number AND BINARY e.start_stop_name=BINARY r.start_stop_name
 AND BINARY e.end_stop_name=BINARY r.end_stop_name AND e.bundle_sha256=@bundle_sha256
 AND e.collected_at=@collection_time AND e.source_page=r.source_page
WHERE r.outcome='ELIGIBLE';
"""
    output.mkdir()
    (output / 'preview.sql').write_text(preview, encoding='utf-8')
    (output / 'apply.sql').write_text(apply, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output_directory', type=Path)
    args = parser.parse_args()
    rows, digest, collected_at = read_bundle(args.bundle)
    write_sql(args.output_directory, rows, digest, collected_at)
    print(f'TOPIS rows={len(rows)} duplicates=0 collected_at={collected_at.isoformat()} bundle_sha256={digest}')


if __name__ == '__main__':
    main()
