"""Keep the opt-in diagnostic build manual, branch-only, debuggable only in Canary and never published."""
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import diagnostic_mode as mode  # noqa: E402

spec = importlib.util.spec_from_file_location('verify_apks', TOOLS / 'verify-apks.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text()
BUILD, PUBLISH = WORKFLOW.split('\n  publish:', 1)


def env(event='workflow_dispatch', ref_type='branch', ref_name='topic', request='true', gradle=None, **extra):
    values = {'GITHUB_EVENT_NAME': event, 'GITHUB_REF_TYPE': ref_type, 'GITHUB_REF_NAME': ref_name,
              mode.REQUEST: request, 'MUON_VERSION_CODE': '42', 'MUON_VERSION_NAME': '0.1.0', **extra}
    if gradle is not None:
        values[mode.GRADLE] = gradle
    return values


def step(name):
    return next(block for block in BUILD.split('      - name: ') if block.startswith(name))


class SelectionTest(unittest.TestCase):
    def test_only_an_explicit_manual_branch_request_is_diagnostic(self):
        self.assertTrue(mode.select('workflow_dispatch', 'branch', 'codex/topic', 'true'))
        for args in [('push', 'branch', 'codex/topic', ''), ('push', 'branch', 'main', ''),
                     ('push', 'tag', 'v1.2.3', ''), ('workflow_dispatch', 'branch', 'main', 'false'),
                     ('workflow_dispatch', 'tag', 'v1.2.3', 'false'),
                     ('workflow_dispatch', 'branch', 'codex/topic', 'false')]:
            with self.subTest(args=args):
                self.assertFalse(mode.select(*args))

    def test_requests_that_could_publish_fail_rather_than_being_ignored(self):
        for ref_type, ref_name in [('branch', 'main'), ('tag', 'v1.2.3'), ('tag', 'topic')]:
            with self.subTest(ref=ref_name), self.assertRaises(ValueError):
                mode.select('workflow_dispatch', ref_type, ref_name, 'true')

    def test_invalid_or_unexpected_input_fails_closed(self):
        for request in ['True', 'TRUE', '1', 'yes', ' true', 'true\n', 'false ', '0', '']:
            with self.subTest(request=request), self.assertRaises(ValueError):
                mode.select('workflow_dispatch', 'branch', 'topic', request)
        # A push has no inputs, so any value came from somewhere else.
        for request in ['true', 'false', 'x']:
            with self.subTest(push=request), self.assertRaises(ValueError):
                mode.select('push', 'branch', 'topic', request)
        for event, ref_type, ref_name in [('pull_request', 'branch', 'topic'), ('schedule', 'branch', 'topic'),
                                          ('', 'branch', 'topic'), ('workflow_dispatch', '', 'topic'),
                                          ('workflow_dispatch', 'branch', '')]:
            with self.subTest(event=event, ref_type=ref_type), self.assertRaises(ValueError):
                mode.select(event, ref_type, ref_name, 'false')

    def test_step_writes_gradle_setting_output_and_summary(self):
        for values, expected in [(env(), 'true'), (env(request='false'), 'false'),
                                 (env(event='push', ref_name='main', request=''), 'false')]:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temp:
                files = {key: Path(temp, key) for key in ['GITHUB_ENV', 'GITHUB_OUTPUT', 'GITHUB_STEP_SUMMARY']}
                mode.main({**values, 'GITHUB_SHA': 'a' * 40, **{key: str(path) for key, path in files.items()}})
                self.assertEqual(files['GITHUB_ENV'].read_text(), f'{mode.GRADLE}={expected}\n')
                self.assertEqual(files['GITHUB_OUTPUT'].read_text(), f'diagnostic={expected}\n')
                summary = files['GITHUB_STEP_SUMMARY']
                if expected == 'true':
                    text = summary.read_text()
                    for phrase in ['debugging enabled', f'app-diagnostic-{"a" * 40}', 'not representative',
                                   'Nothing is published', 'non-debuggable']:
                        self.assertIn(phrase, text)
                else:
                    self.assertFalse(summary.exists())


class ApkExpectationTest(unittest.TestCase):
    @staticmethod
    def badging(package, version_name, label, debuggable):
        return (f"package: name='{package}' versionCode='42' versionName='{version_name}' platformBuildVersionName='17'\n"
                f"application-label:'{label}'\n" + ('application-debuggable\n' if debuggable else ''))

    def check(self, expectation, debuggable, label=None, version=None):
        variant, package, expected_label, suffix, expected_debuggable = expectation
        verify.check_badging(variant, self.badging(package, version or '0.1.0' + suffix, label or expected_label,
                                                   debuggable),
                             package, expected_label, '42', '0.1.0' + suffix, expected_debuggable)

    def test_standard_identity_is_unchanged_and_never_debuggable(self):
        for values in [env(event='push', ref_name='main', request='', gradle='false'),
                       env(event='push', ref_type='tag', ref_name='v1.2.3', request='', gradle='false'),
                       env(request='false', gradle='false')]:
            with self.subTest(event=values['GITHUB_EVENT_NAME'], ref=values['GITHUB_REF_NAME']):
                debug, release = verify.expectations(values)
                self.assertEqual(debug, ('debug', 'dev.avery.muon', 'Muon β', '-canary.42', False))
                self.assertEqual(release, ('release', 'dev.avery.muon.release', 'Muon', '', False))
                self.check(debug, False)
                self.check(release, False)
                for expectation in (debug, release):
                    with self.assertRaisesRegex(SystemExit, 'must not be debuggable'):
                        self.check(expectation, True)

    def test_diagnostic_canary_must_be_debuggable_and_release_must_not(self):
        debug, release = verify.expectations(env(gradle='true'))
        self.assertEqual(debug, ('debug', 'dev.avery.muon.diagnostic', 'Muon diag', '-canary.42-diagnostic', True))
        self.assertEqual(release, ('release', 'dev.avery.muon.release', 'Muon', '', False))
        self.check(debug, True)
        self.check(release, False)
        with self.assertRaisesRegex(SystemExit, 'debug: APK must be debuggable'):
            self.check(debug, False)
        with self.assertRaisesRegex(SystemExit, 'release: APK must not be debuggable'):
            self.check(release, True)
        # A standard-looking Canary in a diagnostic run is not accepted either.
        with self.assertRaisesRegex(SystemExit, 'unexpected launcher label'):
            self.check(debug, True, label='Muon β')
        with self.assertRaisesRegex(SystemExit, 'unexpected versionName'):
            self.check(debug, True, version='0.1.0-canary.42')

    def test_diagnostic_and_normal_packages_cannot_be_substituted(self):
        diagnostic = verify.expectations(env(gradle='true'))[0]
        normal = verify.expectations(env(request='false', gradle='false'))[0]
        for expected, other in [(diagnostic, normal), (normal, diagnostic)]:
            variant, package, label, suffix, debuggable = expected
            with self.subTest(package=package), self.assertRaisesRegex(SystemExit, 'unexpected name'):
                verify.check_badging(variant, self.badging(other[1], '0.1.0' + suffix, label, debuggable),
                                     package, label, '42', '0.1.0' + suffix, debuggable)

    def test_gradle_setting_must_match_the_raw_event(self):
        for values in [env(gradle='false'), env(request='false', gradle='true'), env(),
                       env(event='push', ref_name='main', request='', gradle='true'),
                       env(event='push', ref_name='main', request='', gradle='')]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                verify.expectations(values)
        with self.assertRaises(ValueError):
            verify.expectations(env(ref_name='main', gradle='true'))


class WorkflowTest(unittest.TestCase):
    def test_input_is_an_opt_in_boolean_and_triggers_are_unchanged(self):
        self.assertIn('  workflow_dispatch:\n    inputs:\n      diagnostic_debug:\n', WORKFLOW)
        block = WORKFLOW[WORKFLOW.index('      diagnostic_debug:'):WORKFLOW.index('\npermissions:')]
        self.assertIn('type: boolean', block)
        self.assertIn('default: false', block)
        self.assertEqual(WORKFLOW.count('inputs.diagnostic_debug'), 2)
        self.assertNotIn('pull_request', WORKFLOW)
        self.assertIn('permissions:\n  contents: read\n', WORKFLOW)

    def test_selection_precedes_sdk_and_signing_and_adds_no_secrets(self):
        selection = step('Select diagnostic build mode')
        self.assertIn('run: python3 tools/diagnostic_mode.py', selection)
        self.assertIn('MUON_DIAGNOSTIC_REQUEST: ${{ inputs.diagnostic_debug }}', selection)
        self.assertNotIn('secrets.', selection)
        self.assertNotIn('continue-on-error', WORKFLOW)
        index = BUILD.index('run: python3 tools/diagnostic_mode.py')
        for later in ['- name: Select version and publication channel', 'uses: actions/setup-java@',
                      '- name: Install Android SDK packages', '- name: Restore signing keys', './gradlew']:
            self.assertLess(index, BUILD.index(later), later)
        self.assertEqual(WORKFLOW.count('${{ secrets.'), 4)
        self.assertIn('MUON_DIAGNOSTIC_REQUEST: ${{ inputs.diagnostic_debug }}',
                      step('Verify APK signatures and prepare artifacts'))

    def test_diagnostic_build_has_no_channel_and_publish_job_refuses_it(self):
        version = step('Select version and publication channel')
        self.assertIn('DIAGNOSTIC: ${{ steps.diagnostic.outputs.diagnostic }}', version)
        self.assertIn('[[ "$DIAGNOSTIC" == false ]] || channel=none', version)
        self.assertLess(version.index('channel=canary'), version.index('|| channel=none'))
        self.assertIn('diagnostic: ${{ steps.diagnostic.outputs.diagnostic }}', BUILD)
        self.assertIn("    if: needs.build.outputs.diagnostic == 'false' && (needs.build.outputs.channel == 'canary'"
                      " || needs.build.outputs.channel == 'stable')\n", PUBLISH)
        self.assertIn('    permissions:\n      contents: write\n', PUBLISH)

    def test_artifacts_and_build_metadata_identify_the_mode(self):
        self.assertIn("name: app-${{ steps.diagnostic.outputs.diagnostic == 'true' && 'diagnostic' || 'debug' }}"
                      "-${{ github.sha }}", BUILD)
        self.assertIn('name: app-release-${{ github.sha }}', BUILD)
        # The publish job still downloads only standard artifact names.
        self.assertIn("name: app-${{ needs.build.outputs.channel == 'canary' && 'debug' || 'release' }}"
                      "-${{ github.sha }}", PUBLISH)
        prepare = step('Verify APK signatures and prepare artifacts')
        self.assertIn('Version: %s-canary.%s-diagnostic\\nMode: diagnostic', prepare)
        self.assertIn("printf 'Commit: %s\\nRun: %s\\nVersion: %s-canary.%s\\n'", prepare)
        self.assertIn("printf 'Commit: %s\\nRun: %s\\nVersion: %s\\n'", prepare)
        self.assertEqual(prepare.count('Mode:'), 1)


class GradleTest(unittest.TestCase):
    def test_only_the_debug_build_type_can_be_debuggable(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertEqual(gradle.count('MUON_DIAGNOSTIC_DEBUG'), 2)
        self.assertIn('.getOrElse("false")', gradle)
        self.assertIn('"true" -> true\n        "false" -> false\n        else -> throw GradleException', gradle)
        self.assertNotIn('findProperty', gradle)
        self.assertNotIn('gradleProperty', gradle)
        debug = gradle[gradle.index('getByName("debug") {'):gradle.index('getByName("release") {')]
        release = gradle[gradle.index('getByName("release") {'):gradle.index('create("benchmark")')]
        self.assertIn('isDebuggable = diagnosticDebug', debug)
        self.assertIn('isDebuggable = false', release)
        self.assertNotIn('diagnosticDebug', release)
        self.assertEqual(gradle.count('isDebuggable'), 2)
        self.assertEqual(gradle.count('diagnosticDebug'), 5)
        self.assertIn('if (diagnosticDebug) applicationIdSuffix = ".diagnostic"', debug)


if __name__ == '__main__':
    unittest.main()
