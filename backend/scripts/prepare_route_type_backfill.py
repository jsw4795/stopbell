#!/usr/bin/env python3
"""Generate reviewable MySQL scripts from the existing Seoul CSV; never connect to a database."""
import argparse
import csv
from pathlib import Path

SEOUL = {'간선': 'TRUNK', '지선': 'BRANCH', '광역': 'METROPOLITAN', '마을': 'VILLAGE',
         '순환': 'CIRCULAR', '공항': 'AIRPORT', '관광': 'TOUR'}
GBIS = {'11': 'CITY_EXPRESS_SEAT', '12': 'CITY_SEAT', '13': 'CITY_LOCAL',
        '14': 'CITY_METRO_EXPRESS', '15': 'CITY_DDABOK', '16': 'GYEONGGI_CIRCULAR',
        '21': 'RURAL_EXPRESS_SEAT', '22': 'RURAL_SEAT', '23': 'RURAL_LOCAL', '30': 'VILLAGE',
        '41': 'INTERCITY_EXPRESS', '42': 'INTERCITY_SEAT', '43': 'INTERCITY_LOCAL',
        '51': 'AIRPORT_LIMOUSINE', '52': 'AIRPORT_SEAT', '53': 'AIRPORT_LOCAL'}


def literal(value):
    return 'CONVERT(0x' + value.encode('utf-8').hex() + ' USING utf8mb4)'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('output_directory', type=Path)
    args = parser.parse_args()
    with args.csv.open(encoding='utf-8-sig', newline='') as source:
        reader = csv.DictReader(source)
        if not {'노선ID', '노선명', '노선유형'}.issubset(reader.fieldnames or []):
            raise ValueError('Required Seoul CSV columns are missing')
        seen, rows = set(), []
        for row in reader:
            route_id, name, raw = (row[k].strip() for k in ('노선ID', '노선명', '노선유형'))
            if not route_id or not name or route_id in seen:
                raise ValueError('Empty or duplicate Seoul route identity')
            seen.add(route_id)
            if raw in SEOUL:
                rows.append((route_id, name, SEOUL[raw]))
            elif raw not in ('', '경기', '인천'):
                raise ValueError('Unknown Seoul route type; inspect input before proceeding')
    if not rows:
        raise ValueError('No known Seoul route types in CSV')
    args.output_directory.mkdir(parents=True, exist_ok=True)
    preview = args.output_directory / 'route-type-preview.sql'
    update = args.output_directory / 'route-type-update.sql'
    if preview.exists() or update.exists():
        raise FileExistsError('Output already exists')
    values = ',\n'.join('(' + ','.join(literal(x) for x in row) + ')' for row in rows)
    cases = ' '.join('WHEN ' + literal(k) + ' THEN ' + literal(v) for k, v in GBIS.items())
    sql = f'''-- Run in one session with writers stopped. This script does not update persistent tables.
SET NAMES utf8mb4;
CREATE TEMPORARY TABLE seoul_route_type_input (external_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY, route_number VARCHAR(100) NOT NULL, new_type VARCHAR(24) NOT NULL);
INSERT INTO seoul_route_type_input VALUES\n{values};
CREATE TEMPORARY TABLE route_type_candidates (route_id BIGINT PRIMARY KEY, provider VARCHAR(20) NOT NULL, external_id VARCHAR(255) NOT NULL, new_type VARCHAR(24) NOT NULL);
START TRANSACTION;
-- Lock both tables before computing candidates; stop other metadata writers for the whole session.
SELECT id FROM bus_routes ORDER BY id FOR UPDATE;
SELECT id FROM transit_source_metadata WHERE source_system='GBIS' AND data_kind='ROUTE' ORDER BY id FOR UPDATE;
INSERT INTO route_type_candidates
SELECT r.id,r.provider,r.external_route_id,s.new_type FROM bus_routes r JOIN seoul_route_type_input s
 ON r.provider='SEOUL_BUS' AND BINARY r.external_route_id=BINARY s.external_id AND BINARY r.route_number=BINARY s.route_number
WHERE r.route_type IS NULL;
INSERT INTO route_type_candidates
SELECT r.id,r.provider,r.external_route_id,CASE TRIM(JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.routeTypeCd'))) {cases} END
FROM transit_source_metadata m JOIN bus_routes r ON r.id=m.route_id AND r.provider='TAGO'
WHERE m.source_system='GBIS' AND m.data_kind='ROUTE' AND m.source_version='20261109'
 AND BINARY r.external_route_id=BINARY CONCAT('GGB',m.source_key) AND r.route_type IS NULL
 AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.routeTypeCd'))) IN ({','.join(literal(k) for k in GBIS)});
SELECT provider,new_type,COUNT(*) AS candidate_count FROM route_type_candidates GROUP BY provider,new_type ORDER BY provider,new_type;
SELECT COUNT(*) AS candidate_count FROM route_type_candidates;
SELECT * FROM route_type_candidates ORDER BY provider,external_id;
-- Inspect the output, then either ROLLBACK or, only after explicit approval, source route-type-update.sql in this session.
'''
    apply = '''-- Explicit approval required. Use only in the same locked transaction after reviewing preview.
UPDATE bus_routes r JOIN route_type_candidates c ON c.route_id=r.id SET r.route_type=c.new_type WHERE r.route_type IS NULL;
SELECT ROW_COUNT() AS updated_count;
SELECT r.provider,r.route_type,COUNT(*) AS candidate_count FROM bus_routes r JOIN route_type_candidates c ON c.route_id=r.id GROUP BY r.provider,r.route_type ORDER BY r.provider,r.route_type;
SELECT COUNT(*) AS mismatches FROM bus_routes r JOIN route_type_candidates c ON c.route_id=r.id WHERE NOT (r.route_type <=> c.new_type);
-- Verify counts and non-target tables against preflight. Manually COMMIT or ROLLBACK; this file does neither.
'''
    preview.write_text(sql, encoding='utf-8')
    update.write_text(apply, encoding='utf-8')
    print(f'Generated {len(rows)} Seoul source rows: {preview}, {update}')


if __name__ == '__main__':
    main()
