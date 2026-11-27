"""Real Git graph regressions for branch direction and integration freshness."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import check_branch_policy as policy


class BranchPolicyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(temp.cleanup)
        old = Path.cwd()
        os.chdir(temp.name)
        self.addCleanup(os.chdir, old)
        self.git('init', '-q', '-b', 'main')
        for key, value in [('user.name', 'Policy test'), ('user.email', 'ci@example.invalid'),
                           ('commit.gpgsign', 'false'), ('gc.auto', '0'), ('maintenance.auto', 'false')]:
            self.git('config', key, value)
        self.base = self.commit('base.txt')
        self.git('branch', 'audit')
        self.git('checkout', '-qb', 'experiment')
        self.exp = self.commit('exp.txt')
        self.git('update-ref', 'refs/remotes/origin/' + policy.EXPERIMENT, self.exp)
        self.git('checkout', 'audit')
        self.fix = self.commit('fix.txt')
        self.git('update-ref', 'refs/remotes/origin/main', self.fix)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()

    def commit(self, name):
        Path(name).write_text(name)
        self.git('add', name)
        self.git('commit', '-qm', name)
        return self.git('rev-parse', 'HEAD')

    def test_current_audit_head_can_target_main(self):
        self.assertEqual(self.fix, policy.check('main', self.fix, 'codex/fix'))

    def test_sync_and_experiment_names_cannot_target_main(self):
        for name in (policy.EXPERIMENT, 'sync/main-into-gen-4-123', 'sync/main-into-expressive-123'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'must not target main'):
                policy.check('main', self.fix, name)

    def test_direct_main_to_experiment_pr_is_not_a_tested_combination(self):
        with self.assertRaisesRegex(ValueError, 'Destination advanced'):
            policy.check(policy.EXPERIMENT, self.fix, 'main')

    def test_forward_merge_checks_the_combined_head(self):
        self.git('checkout', 'experiment')
        self.git('merge', '--no-edit', 'audit')
        self.assertEqual(self.exp, policy.check(policy.EXPERIMENT,
                         self.git('rev-parse', 'HEAD'), 'sync/main-into-gen-4-123'))

    def test_destination_advance_invalidates_previous_snapshot(self):
        self.git('checkout', 'experiment')
        self.git('merge', '--no-edit', 'audit')
        checked = self.git('rev-parse', 'HEAD')
        newer = self.commit('new-ui.txt')
        self.git('update-ref', 'refs/remotes/origin/' + policy.EXPERIMENT, newer)
        with self.assertRaisesRegex(ValueError, 'Destination advanced'):
            policy.check(policy.EXPERIMENT, checked, 'sync/main-into-gen-4-123')

    def test_forward_sync_cannot_omit_new_main_fixes(self):
        with self.assertRaisesRegex(ValueError, 'include current main'):
            policy.check(policy.EXPERIMENT, self.exp, 'sync/main-into-gen-4-123')

    def test_candidate_cannot_replace_the_destination_checker_with_a_false_pass(self):
        trusted = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(trusted.cleanup)
        checker = Path(trusted.name) / 'check_branch_policy.py'
        checker.write_text(Path(policy.__file__).read_text())
        self.git('checkout', 'experiment')
        Path('tools').mkdir()
        candidate = Path('tools/check_branch_policy.py')
        candidate.write_text('print("false pass from candidate")\n')
        self.git('add', 'tools/check_branch_policy.py')
        self.git('commit', '-qm', 'replace candidate policy')
        head = self.git('rev-parse', 'HEAD')
        result = subprocess.run([sys.executable, '-I', str(checker),
                                 '--base', 'main', '--head', head, '--source', policy.EXPERIMENT],
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn('must not target main', result.stderr)

    def test_unknown_destination_or_bad_sha_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Choose main'):
            policy.check('release', self.fix, 'codex/fix')
        with self.assertRaisesRegex(ValueError, 'full commit SHA'):
            policy.check('main', 'HEAD', 'codex/fix')


if __name__ == '__main__':
    unittest.main()
