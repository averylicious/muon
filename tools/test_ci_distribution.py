"""Check file preservation and failure/ordering boundaries of CI distribution isolation."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from isolate_gradle_distribution import isolate_distribution, verify_properties, QUARANTINE, main

ROOT = Path(__file__).resolve().parents[1]


class DistributionIsolationTest(unittest.TestCase):
    def layout(self, directory):
        home = Path(directory) / 'home'
        temp = home / 'work/_temp'
        temp.mkdir(parents=True)
        gradle = home / '.gradle'
        source = gradle / 'wrapper/dists'
        source.mkdir(parents=True)
        (source / 'fixture-launcher.jar').write_bytes(b'public restored fixture')
        (gradle / 'caches').mkdir()
        (gradle / 'caches/keep').write_bytes(b'dependency fixture')
        return home, temp, gradle, source

    def test_preserves_distribution_bytes_and_dependency_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            home, temp, gradle, source = self.layout(directory)
            self.assertTrue(isolate_distribution(gradle, home, temp))
            self.assertFalse(source.exists())
            self.assertEqual((temp / QUARANTINE / 'fixture-launcher.jar').read_bytes(), b'public restored fixture')
            self.assertEqual((gradle / 'caches/keep').read_bytes(), b'dependency fixture')

    def test_clean_runner_needs_no_move_or_created_distribution(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / 'home'
            temp = home / 'work/_temp'
            temp.mkdir(parents=True)
            self.assertFalse(isolate_distribution(home / '.gradle', home, temp))
            self.assertFalse((home / '.gradle/wrapper/dists').exists())
            self.assertFalse((temp / QUARANTINE).exists())

    def test_unsafe_paths_leave_restored_bytes_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            home, temp, gradle, source = self.layout(directory)
            cases = [(Path('.gradle'), home, temp), (home, home, temp),
                     (gradle, home, gradle / 'temp'), (gradle, home, temp / 'missing')]
            for paths in cases:
                with self.subTest(paths=paths), self.assertRaises(ValueError):
                    isolate_distribution(*paths)
            (temp / QUARANTINE).mkdir()
            with self.assertRaises(ValueError):
                isolate_distribution(gradle, home, temp)
            self.assertEqual((source / 'fixture-launcher.jar').read_bytes(), b'public restored fixture')

    def test_symlinked_home_wrapper_and_distribution_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            home, temp, gradle, source = self.layout(directory)
            linked = Path(directory) / 'linked-home'
            linked.symlink_to(home, target_is_directory=True)
            with self.assertRaises(ValueError):
                isolate_distribution(linked / '.gradle', linked, temp)
            source.rename(gradle / 'original')
            source.symlink_to(gradle / 'original', target_is_directory=True)
            with self.assertRaises(ValueError):
                isolate_distribution(gradle, home, temp)
            source.unlink()
            source.parent.rmdir()
            source.parent.symlink_to(gradle / 'original', target_is_directory=True)
            with self.assertRaises(ValueError):
                isolate_distribution(gradle, home, temp)
            self.assertEqual((gradle / 'original/fixture-launcher.jar').read_bytes(), b'public restored fixture')

    def test_move_error_and_incomplete_move_never_return_success(self):
        with tempfile.TemporaryDirectory() as directory:
            home, temp, gradle, source = self.layout(directory)
            for side_effect in (OSError('fixture'), None):
                with self.subTest(error=side_effect), mock.patch('isolate_gradle_distribution.shutil.move', side_effect=side_effect):
                    with self.assertRaises((OSError, ValueError)):
                        isolate_distribution(gradle, home, temp)
                self.assertTrue(source.is_dir())

    def test_reviewed_properties_and_changed_missing_symlink_refusal(self):
        verify_properties(ROOT / 'gradle/wrapper/gradle-wrapper.properties')
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'properties'
            fixture = b'reviewed install paths/checksum'
            digest = hashlib.sha256(fixture).hexdigest()
            p.write_bytes(fixture)
            verify_properties(p, digest)
            p.write_bytes(fixture + b'changed')
            with self.assertRaises(ValueError):
                verify_properties(p, digest)
            p.unlink()
            with self.assertRaises(ValueError):
                verify_properties(p, digest)
            target = p.with_name('target')
            target.write_bytes(fixture)
            p.symlink_to(target)
            with self.assertRaises(ValueError):
                verify_properties(p, digest)

    def test_cli_refuses_non_hosted_and_local_execution_before_move(self):
        for environment in ({'GITHUB_ACTIONS': 'false', 'RUNNER_ENVIRONMENT': 'github-hosted'},
                            {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}):
            with self.subTest(environment=environment), mock.patch.dict('os.environ', environment), \
                    mock.patch('isolate_gradle_distribution.isolate_distribution') as move:
                with self.assertRaises(ValueError):
                    main()
                move.assert_not_called()

    def test_all_workflows_guard_after_restore_before_execution_and_signing(self):
        for name in ('android.yml', 'baseline-profile.yml', 'dependency-audit.yml'):
            with self.subTest(workflow=name):
                text = (ROOT / '.github/workflows' / name).read_text()
                blocks = text.split('      - name: ')
                setup = next(b for b in blocks if b.startswith('Set up Gradle and validate wrapper'))
                guard = next(b for b in blocks if b.startswith('Isolate restored Gradle distributions before execution'))
                self.assertIn('cache-cleanup: never', setup)
                for bypass in ('gradle-version:', 'arguments:', 'continue-on-error', 'dependency-graph:'):
                    self.assertNotIn(bypass, setup)
                self.assertIn('python3 -I tools/isolate_gradle_distribution.py', guard)
                self.assertNotIn('continue-on-error', guard)
                self.assertNotIn('always()', guard)
                if name == 'android.yml':
                    self.assertIn("if: steps.scope.outputs.build_apks == 'true'", guard)
                else:
                    self.assertNotIn('if:', guard)
                i = text.index('python3 -I tools/isolate_gradle_distribution.py')
                self.assertLess(text.index('uses: gradle/actions/setup-gradle@'), i)
                self.assertLess(i, text.index('./gradlew'))
                if 'Restore signing' in text:
                    self.assertLess(i, text.index('Restore signing'))
                if name != 'android.yml':
                    self.assertIn('tools/isolate_gradle_distribution.py', text.split('permissions:')[0])
                    self.assertIn('tools/test_ci_distribution.py', text.split('permissions:')[0])


if __name__ == '__main__':
    unittest.main()
