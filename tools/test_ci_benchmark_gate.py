"""Fail closed when either filtered benchmark test is absent, skipped or unsuccessful."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_benchmark_gate_tests import CLASS, METHODS, verify_results


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/baseline-profile.yml').read_text()
STEPS = WORKFLOW.split('      - name: ')[1:]


def report(methods=METHODS, **attributes):
    suite = ET.Element('testsuite', {'name': CLASS, 'tests': str(len(methods)),
                                   'skipped': '0', 'failures': '0', 'errors': '0', **attributes})
    for method in methods:
        ET.SubElement(suite, 'testcase', {'classname': CLASS, 'name': method, 'time': '0.02'})
    return suite


class BenchmarkGateEvidenceTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.directory = Path(folder.name)
        self.path = self.directory / f'TEST-{CLASS}.xml'

    def write(self, suite):
        ET.ElementTree(suite).write(self.path, encoding='utf-8', xml_declaration=True)

    def test_exact_pinned_names_pass_and_cli_reports_success(self):
        self.write(report())
        verify_results(self.directory)
        result = subprocess.run([sys.executable, str(ROOT / 'tools/verify_benchmark_gate_tests.py'),
                                 str(self.directory)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_and_malformed_reports_fail_including_cli_exit(self):
        with self.assertRaises(ValueError):
            verify_results(self.directory)
        result = subprocess.run([sys.executable, str(ROOT / 'tools/verify_benchmark_gate_tests.py'),
                                 str(self.directory)], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.path.write_text('<broken', encoding='utf-8')
        with self.assertRaises(ET.ParseError):
            verify_results(self.directory)

    def test_one_matching_filter_duplicates_and_unexpected_methods_fail(self):
        for methods in [(METHODS[0],), (METHODS[1],), (), (METHODS[0], METHODS[0]),
                        (METHODS[0], METHODS[1] + '[34]'), (*METHODS, 'unselectedTest')]:
            with self.subTest(methods=methods):
                self.write(report(methods))
                with self.assertRaises(ValueError):
                    verify_results(self.directory)
        # A misleading suite count cannot conceal a missing testcase.
        self.write(report((METHODS[0],), tests='2'))
        with self.assertRaises(ValueError):
            verify_results(self.directory)

    def test_skips_failures_and_errors_fail_even_with_zero_summary(self):
        for outcome in ('skipped', 'failure', 'error'):
            with self.subTest(outcome=outcome):
                suite = report()
                ET.SubElement(suite.find('testcase'), outcome)
                self.write(suite)
                with self.assertRaises(ValueError):
                    verify_results(self.directory)
        for key in ('skipped', 'failures', 'errors'):
            with self.subTest(counter=key):
                self.write(report(**{key: '1'}))
                with self.assertRaises(ValueError):
                    verify_results(self.directory)

    def test_wrong_suite_class_and_extra_reports_fail(self):
        for change in ('suite', 'class'):
            with self.subTest(change=change):
                suite = report()
                (suite if change == 'suite' else suite.find('testcase')).set(
                    'name' if change == 'suite' else 'classname', 'wrong.Test')
                self.write(suite)
                with self.assertRaises(ValueError):
                    verify_results(self.directory)
        self.write(report())
        (self.directory / 'TEST-wrong.Test.xml').write_text('<testsuite/>', encoding='utf-8')
        with self.assertRaises(ValueError):
            verify_results(self.directory)


class BenchmarkGateWorkflowTest(unittest.TestCase):
    def test_only_two_exact_filters_and_evidence_check_precede_signing(self):
        tests = next(block for block in STEPS if block.startswith('Test benchmark storage gate before signing\n'))
        evidence = next(block for block in STEPS if block.startswith('Verify both benchmark gate tests passed\n'))
        restore = next(block for block in STEPS if block.startswith('Restore the debug signing key\n'))
        self.assertLess(STEPS.index(tests), STEPS.index(evidence))
        self.assertLess(STEPS.index(evidence), STEPS.index(restore))
        self.assertIn('./gradlew --no-daemon --console=plain :app:testBenchmarkUnitTest', tests)
        self.assertEqual([f'{CLASS}.{method}' for method in METHODS], re.findall(r"--tests '([^']+)'", tests))
        self.assertEqual([':app:testBenchmarkUnitTest'], re.findall(r'(?<!\S):app:\w+', tests))
        self.assertIn('run: python3 tools/verify_benchmark_gate_tests.py app/build/test-results/testBenchmarkUnitTest', evidence)
        for block in (tests, evidence):
            for forbidden in ('secrets.', 'env:', 'if:', 'continue-on-error'):
                self.assertNotIn(forbidden, block)

    def test_failure_reports_and_existing_apk_cleanup_boundaries_are_preserved(self):
        upload = next(block for block in STEPS if block.startswith('Upload benchmark gate test reports\n'))
        self.assertIn('if: always()', upload)
        self.assertIn('app/build/test-results/testBenchmarkUnitTest/', upload)
        self.assertIn('app/build/reports/tests/testBenchmarkUnitTest/', upload)
        self.assertIn('if-no-files-found: warn', upload)
        self.assertIn('retention-days: 7', upload)
        self.assertIn('run: ./gradlew --no-daemon --console=plain :app:assembleBenchmark :baselineprofile:assembleBenchmark', WORKFLOW)
        cleanup = next(block for block in STEPS if block.startswith('Remove signing files\n'))
        self.assertIn('if: always()', cleanup)
        self.assertIn('run: rm -rf "$RUNNER_TEMP/muon-signing"', cleanup)
        self.assertNotIn('continue-on-error', WORKFLOW)

    def test_optional_triggers_and_read_only_permissions_are_unchanged(self):
        triggers = WORKFLOW.split('\non:', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(['baselineprofile/**', '.github/workflows/baseline-profile.yml',
                          'tools/verify_gradle_wrapper.py', 'tools/test_ci_wrapper.py',
                          'tools/isolate_java_toolcache.py', 'tools/isolate_gradle_distribution.py',
                          'tools/test_ci_distribution.py'], re.findall(r"'([^']+)'", triggers))
        self.assertIn('  workflow_dispatch:\n  push:\n', triggers)
        self.assertEqual('contents: read', WORKFLOW.split('\npermissions:', 1)[1].split('\njobs:', 1)[0].strip())


if __name__ == '__main__':
    unittest.main()
