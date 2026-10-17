"""Timing-only Canary is explicit, non-debuggable, labelled and unable to publish."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import diagnostic_mode as mode
spec = importlib.util.spec_from_file_location('timing_apk_verifier', TOOLS / 'verify-apks.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text()
BUILD, PUBLISH = WORKFLOW.split('\n  publish:', 1)


def env(request='true', event='workflow_dispatch', ref_type='branch', ref='codex/timing', **extra):
    return {'GITHUB_EVENT_NAME': event, 'GITHUB_REF_TYPE': ref_type, 'GITHUB_REF_NAME': ref,
            mode.REQUEST: 'false' if event == 'workflow_dispatch' else '', mode.GRADLE: 'false',
            mode.TIMING_REQUEST: request, mode.TIMING_GRADLE: 'true' if request == 'true' else 'false',
            'MUON_VERSION_CODE': '42', 'MUON_VERSION_NAME': '0.1.0', **extra}


def step(name):
    return next(x for x in BUILD.split('      - name: ') if x.startswith(name))


def script_lines(text):
    return '\n'.join(line[10:] for line in text.splitlines() if line.startswith('          '))


class StorageTimingModeTest(unittest.TestCase):
    def test_only_explicit_manual_branch_true_enables_timings(self):
        self.assertTrue(mode.timing_from_env(env()))
        for values in [env('false'), env(''), env('', event='push'),
                       env('', event='push', ref='main'), env('', event='push', ref_type='tag', ref='v1.2.3'),
                       env('false', ref='main'), env('false', ref_type='tag', ref='v1.2.3')]:
            with self.subTest(values=values):
                self.assertFalse(mode.timing_from_env(values))

    def test_main_tags_fork_events_and_malformed_inputs_refuse(self):
        bad = [env(ref='main'), env(ref_type='tag', ref='v1.2.3'),
               env(event='pull_request'), env(event='pull_request_target'), env(event='schedule'),
               env('true', event='push'), env('false', event='push')]
        bad += [env(value) for value in ['True', '1', ' true', 'true\n', 'false ', 'yes']]
        for values in bad:
            with self.subTest(values=values), self.assertRaises(ValueError):
                mode.timing_from_env(values)

    def test_selection_writes_both_flags_and_clear_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = {k: Path(folder, k) for k in ['GITHUB_ENV', 'GITHUB_OUTPUT', 'GITHUB_STEP_SUMMARY']}
            mode.main({**env(), **{k: str(v) for k, v in paths.items()}})
            self.assertEqual(paths['GITHUB_ENV'].read_text(),
                             'MUON_DIAGNOSTIC_DEBUG=false\nMUON_STORAGE_TIMINGS=true\n')
            self.assertEqual(paths['GITHUB_OUTPUT'].read_text(),
                             'diagnostic=false\nstorage_timings=true\n')
            summary = paths['GITHUB_STEP_SUMMARY'].read_text()
            for text in ['non-debuggable', 'package/data', '-timings', 'no song/server/path/error text',
                         'not an uninstrumented benchmark', 'Release/benchmark timing remains disabled']:
                self.assertIn(text, summary)

    def test_invalid_request_does_not_partially_write_any_selection(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = {k: Path(folder, k) for k in ['GITHUB_ENV', 'GITHUB_OUTPUT', 'GITHUB_STEP_SUMMARY']}
            for path in paths.values():
                path.write_text('sentinel\n')
            with self.assertRaises(ValueError):
                mode.main({**env(ref='main'), **{k: str(v) for k, v in paths.items()}})
            self.assertTrue(all(p.read_text() == 'sentinel\n' for p in paths.values()))

    def test_apk_expectations_keep_package_and_debugging_off_with_distinct_version(self):
        debug, release = verify.expectations(env())
        self.assertEqual(debug, ('debug', 'dev.avery.muon', 'Muon β', '-canary.42-timings', False))
        self.assertEqual(release, ('release', 'dev.avery.muon.release', 'Muon', '', False))
        variant, package, label, suffix, debuggable = debug
        def badging(version, debugging=False):
            return (f"package: name='{package}' versionCode='42' versionName='{version}'\n"
                    f"application-label:'{label}'\n" + ('application-debuggable\n' if debugging else ''))
        verify.check_badging(variant, badging('0.1.0'+suffix), package, label, '42', '0.1.0'+suffix, False)
        with self.assertRaisesRegex(SystemExit, 'must not be debuggable'):
            verify.check_badging(variant, badging('0.1.0'+suffix, True), package, label, '42', '0.1.0'+suffix, False)
        with self.assertRaisesRegex(SystemExit, 'unexpected versionName'):
            verify.check_badging(variant, badging('0.1.0-canary.42'), package, label, '42', '0.1.0'+suffix, False)

    def test_late_gradle_flag_change_cannot_disagree_with_raw_request(self):
        for values in [env(**{mode.TIMING_GRADLE: 'false'}), env(**{mode.TIMING_GRADLE: ''}),
                       env('false', **{mode.TIMING_GRADLE: 'true'})]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                verify.expectations(values)
        diagnostic = env(**{mode.REQUEST: 'true', mode.GRADLE: 'true'})
        self.assertEqual(verify.expectations(diagnostic)[0],
                         ('debug', 'dev.avery.muon.diagnostic', 'Muon diag', '-canary.42-diagnostic', True))

    def test_real_channel_script_refuses_timing_publication(self):
        script = script_lines(step('Select version and publication channel').split('run: |\n', 1)[1])
        for ref_type, ref_name, timing, expected in [('branch', 'main', 'false', 'canary'),
                ('branch', 'main', 'true', 'none'), ('tag', 'v1.2.3', 'true', 'none'),
                ('tag', 'v1.2.3', 'false', 'stable'), ('branch', 'codex/timing', 'true', 'none')]:
            with self.subTest(ref=ref_name, timing=timing), tempfile.TemporaryDirectory() as folder:
                output, environment = Path(folder, 'output'), Path(folder, 'env')
                values = {**os.environ, 'REF_TYPE': ref_type, 'REF_NAME': ref_name,
                          'DIAGNOSTIC': 'false', 'STORAGE_TIMINGS': timing,
                          'GITHUB_ENV': str(environment), 'GITHUB_OUTPUT': str(output)}
                subprocess.run(['bash', '-euo', 'pipefail', '-c', script], env=values,
                               capture_output=True, text=True, check=True)
                self.assertIn('channel='+expected+'\n', output.read_text())
        self.assertIn("needs.build.outputs.storage_timings == 'false'", PUBLISH)

    def test_workflow_checks_raw_input_before_signing_and_again_before_upload(self):
        selection = step('Select diagnostic build mode')
        self.assertIn('MUON_STORAGE_TIMING_REQUEST: ${{ inputs.storage_timings }}', selection)
        self.assertIn('MUON_STORAGE_TIMING_REQUEST: ${{ inputs.storage_timings }}',
                      step('Verify APK signatures and prepare artifacts'))
        self.assertLess(BUILD.index('run: python3 tools/diagnostic_mode.py'), BUILD.index('- name: Restore signing keys'))
        self.assertIn('storage_timings: ${{ steps.diagnostic.outputs.storage_timings }}', BUILD)
        block = WORKFLOW.split('      storage_timings:', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn('type: boolean', block)
        self.assertIn('default: false', block)
        self.assertNotIn('pull_request', WORKFLOW)
        self.assertEqual(WORKFLOW.count('${{ secrets.'), 4)

    def test_real_build_metadata_labels_timing_without_implying_debugging(self):
        prepare = step('Verify APK signatures and prepare artifacts')
        metadata = prepare[prepare.index('          if [[ "$MUON_DIAGNOSTIC_DEBUG"'):]
        metadata = metadata[:metadata.index('          fi\n')+len('          fi\n')]
        script = script_lines(metadata)
        for diagnostic, timing, suffix, mode_text in [('false', 'true', '-timings', 'non-debuggable'),
                ('true', 'true', '-diagnostic', 'debuggable; temporary'), ('false', 'false', '', None)]:
            with self.subTest(diagnostic=diagnostic, timing=timing), tempfile.TemporaryDirectory() as folder:
                Path(folder, 'artifacts/debug').mkdir(parents=True)
                values = {**os.environ, 'MUON_DIAGNOSTIC_DEBUG': diagnostic, 'MUON_STORAGE_TIMINGS': timing,
                          'MUON_VERSION_NAME': '0.1.0', 'GITHUB_RUN_NUMBER': '42', 'GITHUB_SHA': 'a'*40}
                subprocess.run(['bash', '-euo', 'pipefail', '-c', script], cwd=folder, env=values,
                               capture_output=True, text=True, check=True)
                info = Path(folder, 'artifacts/debug/BUILD.txt').read_text()
                self.assertIn('Version: 0.1.0-canary.42'+suffix+'\n', info)
                if mode_text:
                    self.assertIn(mode_text, info)
                else:
                    self.assertNotIn('Mode:', info)

    def test_release_benchmark_and_normal_factory_keep_timing_disabled(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        defaults = gradle.split('defaultConfig {', 1)[1].split('signingConfigs', 1)[0]
        self.assertIn('buildConfigField("boolean", "STORAGE_TIMINGS", "false")', defaults)
        debug = gradle.split('getByName("debug") {', 1)[1].split('getByName("release") {', 1)[0]
        self.assertIn('buildConfigField("boolean", "STORAGE_TIMINGS", storageTimings.toString())', debug)
        release = gradle.split('getByName("release") {', 1)[1].split('create("benchmark")', 1)[0]
        self.assertNotIn('storageTimings', release)
        self.assertIn('initWith(getByName("release"))', gradle.split('create("benchmark")', 1)[1])
        settings = (ROOT/'app/src/main/java/dev/avery/muon/SettingsScreen.kt').read_text()
        self.assertIn('else if (BuildConfig.STORAGE_TIMINGS) StorageTimingBuildBanner()', settings)
        self.assertIn('Storage timing test build', settings)
        self.assertIn('Temporary, non-debuggable', settings)


if __name__ == '__main__':
    unittest.main()
