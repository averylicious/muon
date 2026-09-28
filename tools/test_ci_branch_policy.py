"""Real Git graph regressions for branch direction and integration freshness."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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
        self.alpha = self.commit('alpha.txt')
        self.git('update-ref', 'refs/remotes/origin/' + policy.EXPERIMENT, self.alpha)
        self.git('checkout', 'audit')
        self.fix = self.commit('fix.txt')
        self.git('update-ref', 'refs/remotes/origin/main', self.fix)
        anchor = patch.object(policy, 'EXPERIMENT_START', self.alpha)
        anchor.start()
        self.addCleanup(anchor.stop)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()

    def commit(self, name):
        Path(name).write_text(name)
        self.git('add', name)
        self.git('commit', '-qm', name)
        return self.git('rev-parse', 'HEAD')

    def test_current_audit_head_can_target_main(self):
        self.assertEqual(self.fix, policy.check('main', self.fix, 'codex/fix'))

    def test_renaming_a_branch_does_not_hide_experimental_ancestry(self):
        self.git('merge', '--no-edit', 'experiment')
        with self.assertRaisesRegex(ValueError, 'contains the alpha'):
            policy.check('main', self.git('rev-parse', 'HEAD'), 'codex/innocent-name')

    def test_sync_and_experiment_names_cannot_target_main(self):
        for name in (policy.EXPERIMENT, 'sync/main-into-expressive-123'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'must not target main'):
                policy.check('main', self.fix, name)

    def test_direct_main_to_experiment_pr_is_not_a_tested_combination(self):
        with self.assertRaisesRegex(ValueError, 'Destination advanced'):
            policy.check(policy.EXPERIMENT, self.fix, 'main')

    def test_forward_merge_checks_the_combined_head(self):
        self.git('checkout', 'experiment')
        self.git('merge', '--no-edit', 'audit')
        self.assertEqual(self.alpha, policy.check(policy.EXPERIMENT,
                         self.git('rev-parse', 'HEAD'), 'sync/main-into-expressive-123'))

    def test_destination_advance_invalidates_previous_snapshot(self):
        self.git('checkout', 'experiment')
        self.git('merge', '--no-edit', 'audit')
        checked = self.git('rev-parse', 'HEAD')
        newer = self.commit('new-ui.txt')
        self.git('update-ref', 'refs/remotes/origin/' + policy.EXPERIMENT, newer)
        with self.assertRaisesRegex(ValueError, 'Destination advanced'):
            policy.check(policy.EXPERIMENT, checked, 'sync/main-into-expressive-123')

    def test_forward_sync_cannot_omit_new_main_fixes(self):
        with self.assertRaisesRegex(ValueError, 'include current main'):
            policy.check(policy.EXPERIMENT, self.alpha, 'sync/main-into-expressive-123')

    def test_unknown_history_fails_instead_of_passing(self):
        with patch.object(policy, 'EXPERIMENT_START', 'f' * 40):
            with self.assertRaises(subprocess.CalledProcessError):
                policy.check('main', self.fix, 'codex/fix')

    def test_rejects_unsupported_destination_and_non_sha_head(self):
        for base, head in [('other', self.fix), ('main', 'HEAD')]:
            with self.subTest(base=base, head=head), self.assertRaises(ValueError):
                policy.check(base, head, 'codex/fix')


if __name__ == '__main__':
    unittest.main()
