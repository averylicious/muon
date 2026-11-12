"""Feedback order and the informational build-identity summary of the Android APKs workflow."""
from pathlib import Path
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text()
BUILD = WORKFLOW.split('\n  publish:', 1)[0]
STEPS = BUILD.split('      - name: ')[1:]


def step(name):
    return next(block for block in STEPS if block.startswith(name + '\n'))


def position(name):
    return next(i for i, block in enumerate(STEPS) if block.startswith(name + '\n'))


class FeedbackTest(unittest.TestCase):
    def test_lint_is_requested_before_the_slower_unit_tests(self):
        block = step('Run unit tests and lint before signing')
        order = [block.index(task) for task in (':app:lintDebug', ':app:lintRelease',
                                                ':app:testDebugUnitTest', ':app:testReleaseUnitTest')]
        self.assertEqual(sorted(order), order)
        # Stopping at the first failure is what makes the order matter.
        self.assertNotIn('--continue', block)

    def test_summary_is_informational_and_sits_between_verification_and_upload(self):
        block = step('Summarize build identity')
        self.assertLess(position('Verify APK signatures and prepare artifacts'),
                        position('Summarize build identity'))
        self.assertLess(position('Summarize build identity'), position('Upload debug APK'))
        self.assertIn("if: steps.scope.outputs.build_apks == 'true'", block)
        # No continue-on-error (test_ci_wrapper forbids it): the script itself must not fail the job.
        self.assertIn("} >> \"$GITHUB_STEP_SUMMARY\" || echo '::warning::", block)
        for forbidden in ['secrets.', 'KEYSTORE', 'PASSWORD', './gradlew', 'GITHUB_ENV', 'GITHUB_OUTPUT']:
            self.assertNotIn(forbidden, block)

    def test_summary_script_reports_the_prepared_build_files(self):
        # The step reports its own failure as a warning, so CI cannot show a broken script: run it here.
        script = textwrap.dedent(step('Summarize build identity').split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
            def git(*args):
                return subprocess.check_output(['git', '-C', folder, *args],
                                               stderr=subprocess.PIPE, text=True).strip()
            git('init', '-q', '-b', 'topic')
            for key, value in [('user.name', 'Summary test'), ('user.email', 'ci@example.invalid'),
                               ('commit.gpgsign', 'false')]:
                git('config', key, value)
            Path(folder, 'source.txt').write_text('source')
            git('add', 'source.txt')
            git('commit', '-qm', 'source')
            for variant, version in [('debug', '0.1.0-canary.1685'), ('release', '0.1.0')]:
                Path(folder, 'artifacts', variant).mkdir(parents=True)
                Path(folder, 'artifacts', variant, 'BUILD.txt').write_text(
                    f'Commit: {"a" * 40}\nRun: 1685\nVersion: {version}\n')
            summary = Path(folder, 'summary.md')
            env = dict(os.environ, REF_TYPE='branch', REF_NAME='topic `$(false)`', CHANNEL='none',
                       GITHUB_SHA='a' * 40, GITHUB_RUN_NUMBER='1685', GITHUB_STEP_SUMMARY=str(summary))
            subprocess.run(['bash', '-e', '-c', script], cwd=folder, env=env, check=True)
            text = summary.read_text()
            # An unwritable summary must not fail the build that publishes from main.
            unwritable = subprocess.run(['bash', '-e', '-c', script], cwd=folder, capture_output=True,
                                        text=True, env=dict(env, GITHUB_STEP_SUMMARY=folder))
            self.assertEqual(0, unwritable.returncode)
            self.assertIn('::warning::Build identity summary unavailable', unwritable.stdout)
            for expected in ['## Build identity', 'branch `topic `$(false)``', 'a' * 40,
                             git('rev-parse', 'HEAD^{tree}'), '**1685**', '`none`',
                             'Version: 0.1.0-canary.1685', 'Version: 0.1.0\n```']:
                self.assertIn(expected, text)


if __name__ == '__main__':
    unittest.main()
