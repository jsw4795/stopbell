"""Fail-closed Backend CI selection and JUnit XML reporting (Python standard library)."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def migration_cases(root=ROOT):
    """Count only the explicitly tagged historical cases; unsupported sources require full CI."""
    total = 0
    for path in (root / 'backend/src/test/java').rglob('*.java'):
        text = path.read_text()
        if '@Tag("migration")' not in text:
            continue
        if re.search(r'@(RepeatedTest|TestFactory|TestTemplate|Nested)\b', text):
            raise ValueError(f'Unrecognized migration case expansion: {path}')
        header, body = re.split(r'\bclass\s+\w+', text, maxsplit=1)
        if '@Tag("migration")' in header:
            total += len(re.findall(r'@Test\b', body))
            # The existing historical parameterized test uses a literal CsvSource.
            params = list(re.finditer(r'@ParameterizedTest\b(.*?)(?:void|public void)\s+\w+\(', body, re.S))
            if len(params) != len(re.findall(r'@ParameterizedTest\b', body)):
                raise ValueError(f'Unrecognized parameterized migration test: {path}')
            for param in params:
                csv = re.search(r'@CsvSource\(\{(.*?)\}\)', param[1], re.S)
                if not csv:
                    raise ValueError(f'Unrecognized migration arguments: {path}')
                total += len(re.findall(r'"(?:[^"\\]|\\.)*"', csv[1]))
        else:
            # Method tags are intentionally restricted to ordinary, single-invocation tests.
            tags = text.count('@Tag("migration")')
            tagged_tests = re.findall(r'@Tag\("migration"\)\s*@Test\b', body)
            if len(tagged_tests) != tags:
                raise ValueError(f'Unrecognized method migration tag: {path}')
            total += tags
    if total == 0:
        raise ValueError('No migration tests found')
    return total


def restart_cases(root=ROOT):
    """Method-level child-JVM restart probes that only the full suite runs."""
    return sum(path.read_text().count('@Tag("process-restart")')
               for path in (root / 'backend/src/test/java').rglob('*.java'))


def database_related(path, diff=''):
    if path in {'docker-compose.yml', 'compose.yml', 'compose.yaml', '.env.example'}:
        return True
    if path.startswith(('.github/workflows/', 'tools/backend_test_ci', 'tools/tests/test_backend_test_ci')):
        return True
    if not path.startswith('backend/'):
        return False
    if path.startswith(('backend/src/test/', 'backend/src/main/resources/', 'backend/gradle/')):
        return True
    if path in {'backend/build.gradle', 'backend/settings.gradle', 'backend/gradle.properties',
                'backend/gradlew', 'backend/gradlew.bat'}:
        return True
    if re.search(r'(?i)(migration|upgrade|bootstrap|initializer|persistence|database|schema|constraint|/entity/|/repository/|/mapper/|/config/)', path):
        return True
    if not path.startswith('backend/src/main/java/'):
        return True  # Unknown Backend infrastructure changes are not assumed safe.
    # Also catches mappings/SQL moved outside the conventional packages, including deletions.
    return bool(re.search(r'(?i)(@(?:Entity|Table|Column|JoinColumn|JoinTable|MappedSuperclass|Embeddable|Embedded|Convert|Converter|Id|Index)\b|Flyway|JdbcTemplate|EntityManager|CREATE\s+TABLE|ALTER\s+TABLE|CREATE\s+INDEX|\.migrate\s*\()', diff))


def choose_suite(base, manual=False, root=ROOT):
    if manual:
        return 'full', 'manual full verification'
    try:
        migration_cases(root)
        if not base or not re.fullmatch(r'[0-9a-fA-F]{40,64}', base) or set(base) == {'0'}:
            return 'full', 'missing or initial-commit base'
        subprocess.run(['git', 'cat-file', '-e', base + '^{commit}'], cwd=root, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        paths = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', base, 'HEAD'], cwd=root)
        backend_changed = False
        for raw in paths.split(b'\0'):
            if not raw:
                continue
            path = os.fsdecode(raw)
            diff = subprocess.check_output(['git', 'diff', '--no-renames', '--unified=0', base, 'HEAD', '--', path], cwd=root).decode(errors='replace')
            if database_related(path, diff):
                return 'full', 'database or test infrastructure change'
            backend_changed = backend_changed or path.startswith('backend/')
        if backend_changed:
            return 'regular', 'no database-related changes'
        # Nothing under backend/ changed: the DB regression would only repeat the previous result.
        return 'fast', 'no Backend changes'
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        return 'full', f'change detection/inventory unavailable ({type(error).__name__})'


def report(result_dir, suite, root=ROOT):
    files = sorted(Path(result_dir).glob('TEST-*.xml'))
    if not files:
        raise ValueError('JUnit XML results are missing')
    results = [ET.parse(file).getroot() for file in files]
    totals = {key: sum(int(result.get(key, '0')) for result in results)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    if totals['tests'] == 0:
        raise ValueError('No tests were executed')
    excluded = migration_cases(root) if suite != 'full' else 0
    restarts = restart_cases(root) if suite != 'full' else 0
    message = (f"Backend {suite}: executed={totals['tests']}, "
               f"passed={totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']}, "
               f"failures={totals['failures']}, errors={totals['errors']}, skipped={totals['skipped']}, "
               f"intentionally excluded migration cases={excluded}, process-restart cases={restarts}"
               + (', db-tagged tests intentionally excluded (no Backend changes)' if suite == 'fast' else '') + '\n')
    print(message, end='')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as output:
            output.write(message)
    if totals['failures'] or totals['errors'] or totals['skipped']:
        raise ValueError('Failed, errored or unexpectedly skipped tests')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    selection = sub.add_parser('select')
    selection.add_argument('--base', default='')
    selection.add_argument('--manual', action='store_true')
    summary = sub.add_parser('report')
    summary.add_argument('--suite', choices=['fast', 'regular', 'full'], required=True)
    summary.add_argument('--results', required=True)
    args = parser.parse_args()
    if args.command == 'select':
        suite, reason = choose_suite(args.base, args.manual)
        print(f'Backend suite={suite}: {reason}')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'suite={suite}\n')
    else:
        report(args.results, args.suite)
