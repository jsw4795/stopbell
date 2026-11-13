import importlib.util
import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('backend_test_ci', Path(__file__).parents[1] / 'backend_test_ci.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class BackendCiTests(unittest.TestCase):
    def test_database_and_infrastructure_changes_require_full(self):
        for path in ['backend/src/main/resources/db/migration/V40.sql',
                     'backend/src/main/java/com/stopbell/user/entity/User.java',
                     'backend/src/main/java/com/stopbell/user/config/Settings.java',
                     'backend/src/test/java/com/stopbell/support/SharedMySqlExtension.java',
                     'backend/src/test/resources/reset-latest-schema.sql', 'backend/build.gradle',
                     'tools/backend_test_ci.py', '.github/workflows/ci.yml', 'docker-compose.yml',
                     'backend/Dockerfile', 'backend/src/main/java/other/DataUpgradeService.java',
                     'backend/src/main/java/other/DatabaseInitializer.java',
                     'backend/src/main/java/other/MetadataBootstrap.java']:
            with self.subTest(path=path):
                self.assertTrue(ci.database_related(path))

    def test_mapping_and_upgrade_code_outside_expected_package_require_full(self):
        for diff in ['-@Column(name="old")', '+ @Embeddable', '+ Flyway.configure()',
                     '+ new JdbcTemplate(source)', '- ALTER TABLE users']:
            self.assertTrue(ci.database_related('backend/src/main/java/other/Service.java', diff))

    def test_ordinary_algorithm_and_flutter_changes_allow_regular(self):
        self.assertFalse(ci.database_related('backend/src/main/java/com/stopbell/boarding/service/BoardingProgressEvaluator.java', '+ return decision;'))
        self.assertFalse(ci.database_related('app/lib/main.dart'))

    def test_missing_invalid_or_unknown_base_fails_closed(self):
        for base in ['', '0' * 40, 'not-a-sha', 'f' * 40]:
            self.assertEqual(ci.choose_suite(base)[0], 'full')
        self.assertEqual(ci.choose_suite('', manual=True)[0], 'full')

    def test_real_diff_checks_deleted_and_renamed_mappings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()
            git('init'); git('config', 'user.email', 'ci@example.test'); git('config', 'user.name', 'CI Test')
            tests = root / 'backend/src/test/java'; tests.mkdir(parents=True)
            (tests / 'MigrationTest.java').write_text('@Tag("migration") class MigrationTest { @Test void upgrade() {} }')
            mapping = root / 'backend/src/main/java/odd/Mapping.java'; mapping.parent.mkdir(parents=True)
            mapping.write_text('@Entity class Mapping {}')
            git('add', '.'); git('commit', '-m', 'base'); base = git('rev-parse', 'HEAD')
            renamed = mapping.with_name('Renamed.java')
            mapping.rename(renamed); git('add', '.'); git('commit', '-m', 'rename mapping')
            self.assertEqual(ci.choose_suite(base, root=root)[0], 'full')
            base = git('rev-parse', 'HEAD'); renamed.rename(mapping)
            mapping.unlink(); git('add', '.'); git('commit', '-m', 'delete mapping')
            self.assertEqual(ci.choose_suite(base, root=root)[0], 'full')
            base = git('rev-parse', 'HEAD')
            mapping.write_text('class Algorithm {}'); git('add', '.'); git('commit', '-m', 'algorithm')
            self.assertEqual(ci.choose_suite(base, root=root)[0], 'regular')
            base = git('rev-parse', 'HEAD')
            screen = root / 'app/lib/main.dart'; screen.parent.mkdir(parents=True)
            screen.write_text('void main() {}'); git('add', '.'); git('commit', '-m', 'flutter only')
            self.assertEqual(ci.choose_suite(base, root=root)[0], 'fast')
            base = git('rev-parse', 'HEAD')
            screen.write_text('void main() { }'); mapping.write_text('class Algorithm { }')
            git('add', '.'); git('commit', '-m', 'flutter and backend')
            self.assertEqual(ci.choose_suite(base, root=root)[0], 'regular')

    def test_historical_inventory_counts_cases_and_rejects_unknown_argument_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); tests = root / 'backend/src/test/java'; tests.mkdir(parents=True)
            source = tests / 'MigrationTest.java'
            source.write_text('@Tag("migration") class MigrationTest { @Test void fresh() {} @ParameterizedTest @CsvSource({"1,true", "2,false"}) void upgrade() {} }')
            (tests / 'MixedTest.java').write_text('class MixedTest { @Tag("migration") @Test void old() {} @Test void current() {} }')
            self.assertEqual(ci.migration_cases(root), 4)
            source.write_text('@Tag("migration") class MigrationTest { @ParameterizedTest @MethodSource("dynamic") void upgrade() {} }')
            with self.assertRaises(ValueError): ci.migration_cases(root)
            self.assertEqual(ci.choose_suite('f' * 40, root=root)[0], 'full')

    def test_report_distinguishes_exclusion_from_skip_and_rejects_missing_results(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.dict(ci.os.environ, {'GITHUB_STEP_SUMMARY': ''}), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            root = Path(directory)
            tests = root / 'backend/src/test/java'; tests.mkdir(parents=True)
            (tests / 'MigrationTest.java').write_text('@Tag("migration") class MigrationTest { @Test void old() {} }')
            with self.assertRaises(ValueError): ci.report(root, 'full')
            result = root / 'TEST-example.xml'
            result.write_text('<testsuite tests="10" failures="0" errors="0" skipped="0"/>')
            ci.report(root, 'regular', root=root)
            self.assertIn('intentionally excluded migration cases=1', output.getvalue())
            self.assertNotIn('db-tagged', output.getvalue())
            ci.report(root, 'fast', root=root)
            self.assertIn('db-tagged tests intentionally excluded', output.getvalue())
            result.write_text('<testsuite tests="10" failures="0" errors="0" skipped="1"/>')
            with self.assertRaises(ValueError): ci.report(root, 'full')
            result.write_text('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
            with self.assertRaises(ValueError): ci.report(root, 'full')


if __name__ == '__main__':
    unittest.main()
