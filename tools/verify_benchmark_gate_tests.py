"""Require both selected benchmark gate tests to have passed, not just a matching filter."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


CLASS = 'dev.avery.muon.PartitionStorePreparationTest'
# Exact unsuffixed names observed in the pinned runner's debug/release XML in Android run 1336.
METHODS = (
    'disabledSelectedStartupRefusesPartitionBeforeAnyProviderOrLegacyNativeFallback',
    'compiledDefaultSelectorHonorsChannelWithoutLegacyNativeFallback',
)
EXPECTED = Counter((CLASS, method) for method in METHODS)


def verify_results(directory):
    reports = sorted(Path(directory).glob('TEST-*.xml'))
    if len(reports) != 1:
        raise ValueError('Expected exactly one benchmark gate test report')
    suite = ET.parse(reports[0]).getroot()
    if suite.tag != 'testsuite' or suite.get('name') != CLASS:
        raise ValueError('Unexpected benchmark gate test suite')
    if any(suite.get(key) != value for key, value in
           [('tests', '2'), ('skipped', '0'), ('failures', '0'), ('errors', '0')]):
        raise ValueError('Both benchmark gate tests must pass without skips, failures or errors')
    cases = suite.findall('testcase')
    if Counter((case.get('classname'), case.get('name')) for case in cases) != EXPECTED:
        raise ValueError('Expected each benchmark gate test exactly once')
    if any(suite.findall('.//' + outcome) for outcome in ('skipped', 'failure', 'error')):
        raise ValueError('Benchmark gate test report contains a skipped or failed case')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: verify_benchmark_gate_tests.py <testBenchmarkUnitTest results>')
    try:
        verify_results(sys.argv[1])
    except (OSError, ET.ParseError, ValueError) as error:
        raise SystemExit(f'Benchmark gate test evidence failed: {error}')
    print('Both selected benchmark storage gate tests passed exactly once.')
