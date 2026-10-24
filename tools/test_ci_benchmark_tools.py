"""Reject the diagnosed ephemeral helper key and mismatched optional-tool install pairs."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from apk_manifest import REQUIRED
from verify_benchmark_tools import ANDROID, HELPER, TARGET, verify_pair, verify_tool_manifest

PIN = 'ab' * 32
OTHER = 'cd' * 32


def manifest(helper=False, measurement=False):
    root = ET.Element('manifest', {'package': HELPER if helper else TARGET})
    app = ET.SubElement(root, 'application', {ANDROID + 'debuggable': 'true' if helper else 'false'})
    if helper:
        ET.SubElement(root, 'instrumentation', {ANDROID + 'targetPackage': HELPER,
            ANDROID + 'name': 'androidx.test.runner.AndroidJUnitRunner'})
    else:
        for (kind, name), exported in REQUIRED.items():
            ET.SubElement(app, kind, {ANDROID + 'name': name, ANDROID + 'exported': str(exported).lower()})
        ET.SubElement(app, 'profileable', {ANDROID + 'shell': 'true', ANDROID + 'enabled': 'true'})
        ET.SubElement(app, 'meta-data', {ANDROID + 'name': 'dev.avery.muon.benchmark.mode',
            ANDROID + 'value': 'measurement' if measurement else 'recording'})
    return root


def xml(root):
    return ET.tostring(root, encoding='unicode')


class BenchmarkToolIdentityTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.folder = Path(folder.name)
        for name in ('app-benchmark.apk', 'baselineprofile-benchmark.apk'):
            (self.folder / name).touch()

    def run_pair(self, measurement=False, helper_pin=PIN):
        def command(args, **kwargs):
            helper = args[-1].endswith('/baselineprofile-benchmark.apk')
            if 'apksigner' in args[0]:
                return 'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: ' + (helper_pin if helper else PIN)
            return xml(manifest(helper, measurement))
        verify_pair(self.folder, Path('/sdk/build-tools'), Path('/sdk/apkanalyzer'), PIN, measurement, run=command)

    def test_both_modes_accept_only_a_complete_pinned_pair(self):
        self.run_pair()
        self.run_pair(measurement=True)
        (self.folder / 'unexpected.apk').touch()
        with self.assertRaises(ValueError):
            self.run_pair()

    def test_runner_generated_helper_key_is_rejected_even_when_the_target_matches(self):
        with self.assertRaisesRegex(SystemExit, 'unexpected signing certificate'):
            self.run_pair(helper_pin=OTHER)

    def test_wrong_mode_debugging_or_instrumentation_target_fails_closed(self):
        mutations = (
            (False, lambda r: r.set('package', 'dev.avery.muon.release')),
            (False, lambda r: r.find('application').set(ANDROID + 'debuggable', 'true')),
            (False, lambda r: r.find('application').set(ANDROID + 'debuggable', 'invalid')),
            (False, lambda r: r.find('application/profileable').set(ANDROID + 'shell', 'false')),
            (False, lambda r: r.find('application/meta-data').set(ANDROID + 'value', 'measurement')),
            (True, lambda r: r.find('application').set(ANDROID + 'debuggable', 'false')),
            (True, lambda r: r.find('instrumentation').set(ANDROID + 'targetPackage', 'dev.avery.muon.release')),
            (True, lambda r: r.find('instrumentation').set(ANDROID + 'name', 'unexpected.Runner')),
        )
        for helper, change in mutations:
            with self.subTest(helper=helper, mutation=change):
                root = manifest(helper); change(root)
                with self.assertRaises(ValueError):
                    verify_tool_manifest(xml(root), helper, False)
