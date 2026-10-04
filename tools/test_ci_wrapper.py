"""Protect the wrapper check and its ordering before cache restore/signing in every Gradle workflow."""
import hashlib
from pathlib import Path
import tempfile
from unittest import mock

from isolate_java_toolcache import isolate_temurin_cache, TEMURIN_CACHE
import unittest

from verify_gradle_wrapper import verify_wrapper


ROOT = Path(__file__).resolve().parents[1]


class WrapperVerificationTest(unittest.TestCase):
    def test_reviewed_tracked_wrapper_passes(self):
        verify_wrapper(ROOT / 'gradle/wrapper/gradle-wrapper.jar')

    def test_modified_missing_and_symlinked_wrappers_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wrapper.jar'
            original = b'approved fixture'
            expected = hashlib.sha256(original).hexdigest()
            path.write_bytes(original)
            verify_wrapper(path, expected)
            path.write_bytes(original + b'changed')
            with self.assertRaises(ValueError):
                verify_wrapper(path, expected)
            path.unlink()
            with self.assertRaises(ValueError):
                verify_wrapper(path, expected)
            target = Path(directory) / 'target.jar'
            target.write_bytes(original)
            path.symlink_to(target)
            with self.assertRaises(ValueError):
                verify_wrapper(path, expected)

    def test_all_gradle_workflows_check_before_cache_restore_and_gradle(self):
        for name in ('android.yml', 'baseline-profile.yml', 'dependency-audit.yml'):
            with self.subTest(workflow=name):
                workflow = (ROOT / '.github/workflows' / name).read_text()
                blocks = workflow.split('      - name: ')
                guard = next(block for block in blocks if block.startswith('Verify wrapper before Gradle cache restore'))
                self.assertIn('run: python3 tools/verify_gradle_wrapper.py', guard)
                self.assertNotIn('continue-on-error', guard)
                if name == 'android.yml':
                    self.assertIn("if: steps.scope.outputs.build_apks == 'true'", guard)
                else:
                    self.assertNotIn('if:', guard)
                index = workflow.index('run: python3 tools/verify_gradle_wrapper.py')
                self.assertLess(index, workflow.index('uses: gradle/actions/setup-gradle@'))
                self.assertLess(index, workflow.index('./gradlew'))
                self.assertNotIn('continue-on-error', workflow)
                self.assertNotIn('if: always()', blocks[blocks.index(guard) + 1])
                if 'Restore signing' in workflow:
                    self.assertLess(index, workflow.index('Restore signing'))

    def test_all_gradle_workflows_verify_fresh_temurin_archives(self):
        for name in ('android.yml', 'baseline-profile.yml', 'dependency-audit.yml'):
            with self.subTest(workflow=name):
                workflow = (ROOT / '.github/workflows' / name).read_text()
                setup = next(block for block in workflow.split('      - name: ')
                             if block.startswith('Set up Java 17'))
                self.assertIn('uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961', setup)
                self.assertIn('distribution: temurin', setup)
                self.assertIn('verify-signature: true', setup)
                guard = next(block for block in workflow.split('      - name: ')
                             if block.startswith('Isolate preinstalled Temurin cache before verification'))
                self.assertIn('run: python3 -I tools/isolate_java_toolcache.py', guard)
                self.assertIn('MUON_TOOL_CACHE_ROOT: ${{ runner.tool_cache }}', guard)
                self.assertIn('MUON_JDK_QUARANTINE: ${{ runner.temp }}/muon-preinstalled-temurin', guard)
                self.assertNotIn('continue-on-error', guard)
                self.assertNotIn('RUNNER_TOOL_CACHE:', setup)
                if name == 'android.yml':
                    self.assertIn("if: steps.scope.outputs.build_apks == 'true'", guard)
                else:
                    self.assertNotIn('if:', guard)
                self.assertLess(workflow.index('run: python3 -I tools/isolate_java_toolcache.py'),
                                workflow.index('uses: actions/setup-java@'))
                for bypass in ('continue-on-error', 'verify-signature-public-key:', 'jdk-file:', 'jdkFile:', 'cache:'):
                    self.assertNotIn(bypass, setup)
                # Keep one reviewed Java setup step; do not silently add a second runtime selection.
                self.assertEqual(workflow.count('uses: actions/setup-java@'), 1)
                self.assertLess(workflow.index('uses: actions/setup-java@'), workflow.index('./gradlew'))

    def test_auxiliary_push_filters_include_guard_changes(self):
        for name in ('baseline-profile.yml', 'dependency-audit.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            self.assertIn('tools/verify_gradle_wrapper.py', workflow.split('permissions:')[0])
            self.assertIn('tools/test_ci_wrapper.py', workflow.split('permissions:')[0])
            self.assertIn('tools/isolate_java_toolcache.py', workflow.split('permissions:')[0])


class JavaCacheIsolationTest(unittest.TestCase):
    def test_preserves_java_bytes_and_leaves_other_tools_alone(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'tools'
            java = root / TEMURIN_CACHE / 'fixture-version'
            java.mkdir(parents=True)
            (java / 'java').write_bytes(b'public JDK fixture')
            other = root / 'OtherTool'
            other.mkdir()
            (other / 'keep').write_bytes(b'other tool')
            destination = Path(directory) / 'temp' / 'old-java'
            self.assertTrue(isolate_temurin_cache(root, destination))
            self.assertFalse((root / TEMURIN_CACHE).exists())
            self.assertEqual((destination / 'fixture-version/java').read_bytes(), b'public JDK fixture')
            self.assertEqual((other / 'keep').read_bytes(), b'other tool')
            self.assertFalse(isolate_temurin_cache(root, Path(directory) / 'unused'))

    def test_refuses_existing_nested_and_symlinked_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'tools'
            source = root / TEMURIN_CACHE
            source.mkdir(parents=True)
            (source / 'keep').write_bytes(b'preserve')
            destination = Path(directory) / 'existing'
            destination.mkdir()
            for bad in (destination, root / 'nested', source / 'nested'):
                with self.assertRaises(ValueError):
                    isolate_temurin_cache(root, bad)
            linked_root = Path(directory) / 'linked'
            linked_root.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                isolate_temurin_cache(linked_root, Path(directory) / 'new')
            source.rename(root / 'original')
            source.symlink_to(root / 'original', target_is_directory=True)
            with self.assertRaises(ValueError):
                isolate_temurin_cache(root, Path(directory) / 'new')
            self.assertEqual((root / 'original/keep').read_bytes(), b'preserve')

    def test_move_failure_propagates_without_a_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'tools'
            source = root / TEMURIN_CACHE
            source.mkdir(parents=True)
            with mock.patch('isolate_java_toolcache.shutil.move', side_effect=OSError('fixture')):
                with self.assertRaises(OSError):
                    isolate_temurin_cache(root, Path(directory) / 'new')
            self.assertTrue(source.is_dir())


if __name__ == '__main__':
    unittest.main()
