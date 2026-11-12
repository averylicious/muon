"""Explicit tests/full lint run before signing material exists; test-mode APKs keep distinct names.

Reduces direct secret exposure only; not an isolation boundary (docs/ci.md).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text()
BUILD = WORKFLOW.split('\n  publish:', 1)[0]
STEPS = BUILD.split('      - name: ')[1:]
CHECKS = {':app:testDebugUnitTest', ':app:testReleaseUnitTest', ':app:lintDebug', ':app:lintRelease'}
SIGNED = {':app:assembleDebug', ':app:assembleRelease'}


def named(name):
    return next(i for i, block in enumerate(STEPS) if block.startswith(name + '\n'))


def app_tasks(block):
    return set(re.findall(r'(?<!\S):app:\w+', block))


def artifact_name(outputs):
    """Evaluate the actual upload expression's `a && 'x' || ... || 'y'` form with these step outputs."""
    upload = STEPS[named('Upload debug APK')]
    expression = re.search(r"name: app-\$\{\{ (.+?) \}\}-\$\{\{ github\.sha \}\}", upload).group(1)
    for alternative in expression.split(' || '):
        parts = alternative.split(' && ')
        if len(parts) == 1:
            return parts[0].strip("'")
        condition, value = parts
        key, expected = re.fullmatch(r"steps\.diagnostic\.outputs\.(\w+) == '(\w+)'", condition).groups()
        if outputs[key] == expected:
            return value.strip("'")
    raise AssertionError('Expression has no default name')


class SigningScopeTest(unittest.TestCase):
    def test_explicit_checks_run_unsigned_before_keys_and_signed_step_only_assembles(self):
        checks, restore, signed = (named('Run unit tests and lint before signing'),
                                   named('Restore signing keys'), named('Build signed APKs'))
        self.assertLess(checks, restore)
        self.assertLess(restore, signed)
        block = STEPS[checks]
        self.assertIn("if: steps.scope.outputs.build_apks == 'true'", block)
        self.assertIn('./gradlew --no-daemon', block)
        self.assertEqual(CHECKS, app_tasks(block))
        for forbidden in ['secrets.', 'KEYSTORE', 'PASSWORD', 'env:']:
            self.assertNotIn(forbidden, block)
        self.assertEqual(SIGNED, app_tasks(STEPS[signed]))
        # Moved, not dropped: every app check runs exactly once, and never once keys exist.
        gradle = [i for i, block in enumerate(STEPS) if './gradlew' in block and ':app:' in block]
        self.assertEqual([checks, signed], gradle)
        for later in STEPS[restore:]:
            self.assertFalse(CHECKS & app_tasks(later), later.splitlines()[0])

    def test_secrets_appear_only_in_key_restoration_and_signed_assemble(self):
        holders = [block.splitlines()[0] for block in STEPS if '${{ secrets.' in block]
        self.assertEqual(['Restore signing keys', 'Build signed APKs'], holders)
        cleanup = STEPS[named('Remove signing files')]
        self.assertIn("if: always() && steps.scope.outputs.build_apks == 'true'", cleanup)
        self.assertGreater(named('Remove signing files'), named('Build signed APKs'))

    def test_test_mode_artifacts_have_distinct_names_and_diagnostic_wins(self):
        self.assertEqual('debug', artifact_name({'diagnostic': 'false', 'storage_timings': 'false'}))
        self.assertEqual('timings', artifact_name({'diagnostic': 'false', 'storage_timings': 'true'}))
        self.assertEqual('diagnostic', artifact_name({'diagnostic': 'true', 'storage_timings': 'false'}))
        self.assertEqual('diagnostic', artifact_name({'diagnostic': 'true', 'storage_timings': 'true'}))
        # Only ordinary artifacts are ever published; the download names are unchanged.
        publish = WORKFLOW.split('\n  publish:', 1)[1]
        self.assertIn("name: app-${{ needs.build.outputs.channel == 'canary' && 'debug' || 'release' }}"
                      "-${{ github.sha }}", publish)


if __name__ == '__main__':
    unittest.main()
