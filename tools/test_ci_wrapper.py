"""Protect the wrapper check and its ordering before cache restore/signing in every Gradle workflow."""
import hashlib
from pathlib import Path
import tempfile
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

    def test_auxiliary_push_filters_include_guard_changes(self):
        for name in ('baseline-profile.yml', 'dependency-audit.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            self.assertIn('tools/verify_gradle_wrapper.py', workflow.split('permissions:')[0])
            self.assertIn('tools/test_ci_wrapper.py', workflow.split('permissions:')[0])


if __name__ == '__main__':
    unittest.main()
