"""Real Git graph regressions for branch direction and integration freshness."""
import os
import re
from pathlib import Path
import subprocess
import sys
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

    def test_candidate_cannot_replace_the_destination_checker_with_a_false_pass(self):
        # The trusted destination copy lives outside the candidate worktree, as in the workflow.
        trusted = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(trusted.cleanup)
        checker = Path(trusted.name) / 'check_branch_policy.py'
        source = Path(policy.__file__).read_text()
        # Bind the real checker to this disposable graph's experiment anchor.
        source = re.sub(r"EXPERIMENT_START = '[0-9a-f]{40}'",
                        "EXPERIMENT_START = '" + self.alpha + "'", source)
        checker.write_text(source)
        self.git('merge', '--no-edit', 'experiment')
        Path('tools').mkdir()
        candidate = Path('tools/check_branch_policy.py')
        candidate.write_text('print("false pass from candidate")\n')
        self.git('add', 'tools/check_branch_policy.py')
        self.git('commit', '-qm', 'replace candidate policy')
        head = self.git('rev-parse', 'HEAD')
        arguments = ['--base', 'main', '--head', head, '--source', 'codex/renamed-alpha']
        forged = subprocess.run([sys.executable, str(candidate), *arguments],
                                capture_output=True, text=True)
        self.assertEqual(0, forged.returncode)  # Old workflow would run this instead.
        checked = subprocess.run([sys.executable, '-I', str(checker), *arguments],
                                 capture_output=True, text=True)
        self.assertNotEqual(0, checked.returncode)
        self.assertIn('contains the alpha experiment', checked.stderr)
        self.assertNotIn('false pass from candidate', checked.stdout)

    def test_unknown_history_fails_instead_of_passing(self):
        with patch.object(policy, 'EXPERIMENT_START', 'f' * 40):
            with self.assertRaises(subprocess.CalledProcessError):
                policy.check('main', self.fix, 'codex/fix')

    def test_rejects_unsupported_destination_and_non_sha_head(self):
        for base, head in [('other', self.fix), ('main', 'HEAD')]:
            with self.subTest(base=base, head=head), self.assertRaises(ValueError):
                policy.check(base, head, 'codex/fix')


class PromotionGraph(unittest.TestCase):
    """A disposable graph shaped like the real promotion.

    main: base, then the promotion policy lands. experiment: the alpha anchor, then the accepted
    commit, which changes the app. The promotion merges that accepted commit into current main.
    """
    def setUp(self):
        temp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(temp.cleanup)
        old = Path.cwd()
        os.chdir(temp.name)
        self.addCleanup(os.chdir, old)
        self.git('init', '-q', '-b', 'main')
        for key, value in [('user.name', 'Policy test'), ('user.email', 'ci@example.invalid'),
                           ('commit.gpgsign', 'false'), ('tag.gpgsign', 'false'),
                           ('gc.auto', '0'), ('maintenance.auto', 'false')]:
            self.git('config', key, value)
        self.write({'app.txt': 'standard', 'docs/state.md': 'state',
                    **{name: 'old policy' for name in policy.POLICY_FILES}})
        self.base = self.commit('base')
        self.git('checkout', '-qb', 'experiment')
        self.alpha = self.change('alpha.txt', 'alpha')
        self.accepted = self.change('app.txt', 'expressive')
        self.git('update-ref', policy.EXPERIMENT_REF, self.accepted)
        self.git('checkout', '-q', 'main')
        self.write({name: 'promotion policy' for name in policy.POLICY_FILES})
        self.main = self.commit('policy lands on main')
        self.git('update-ref', 'refs/remotes/origin/main', self.main)
        for name, value in [('EXPERIMENT_START', self.alpha), ('PROMOTION_COMMIT', self.accepted)]:
            pinned = patch.object(policy, name, value)
            pinned.start()
            self.addCleanup(pinned.stop)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()

    def write(self, files):
        for name, text in files.items():
            Path(name).parent.mkdir(parents=True, exist_ok=True)
            Path(name).write_text(text)
            self.git('add', name)

    def commit(self, message):
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def change(self, name, text):
        self.write({name: text})
        return self.commit(name)

    def promote(self, branch='promote'):
        self.git('checkout', '-qb', branch, self.main)
        self.git('merge', '-q', '--no-edit', self.accepted)
        return self.git('rev-parse', 'HEAD')

    def land(self, commit):
        """Main after the promotion merged; forward sync brings it into the experiment."""
        self.git('update-ref', 'refs/remotes/origin/main', commit)
        self.git('checkout', '-q', '--detach', self.accepted)
        self.git('merge', '-q', '--no-edit', commit)
        self.git('update-ref', policy.EXPERIMENT_REF, self.git('rev-parse', 'HEAD'))


class PromotionPolicyTest(PromotionGraph):
    def test_the_approved_promotion_can_target_main(self):
        head = self.promote()
        self.assertEqual(self.main, policy.check('main', head, policy.PROMOTION_SOURCE))
        self.change('docs/state.md', 'promoted')  # Documentation may still be updated.
        self.assertEqual(self.main, policy.check('main', self.git('rev-parse', 'HEAD'),
                                                 policy.PROMOTION_SOURCE))

    def test_only_the_promotion_source_may_bring_the_alpha_before_promotion(self):
        head = self.promote()
        for source in ('codex/other', policy.EXPERIMENT, 'sync/main-into-expressive-9'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                policy.check('main', head, source)

    def test_a_copy_or_earlier_experiment_commit_is_not_the_accepted_history(self):
        # The alpha with a cherry-picked copy of the accepted change: same tree, other history.
        self.git('checkout', '-qb', 'copied', self.main)
        self.git('merge', '-q', '--no-edit', self.alpha)
        self.git('cherry-pick', self.accepted)
        self.assertEqual('expressive', Path('app.txt').read_text())
        with self.assertRaisesRegex(ValueError, 'accepted experiment commit itself'):
            policy.check('main', self.git('rev-parse', 'HEAD'), policy.PROMOTION_SOURCE)
        self.git('checkout', '-qb', 'partial', self.main)
        self.git('merge', '-q', '--no-edit', self.alpha)
        with self.assertRaisesRegex(ValueError, 'accepted experiment commit itself'):
            policy.check('main', self.git('rev-parse', 'HEAD'), policy.PROMOTION_SOURCE)

    def test_a_code_edit_after_acceptance_fails(self):
        self.promote()
        edited = self.change('app.txt', 'edited after UAT')
        with self.assertRaisesRegex(ValueError, 'outside docs'):
            policy.check('main', edited, policy.PROMOTION_SOURCE)

    def test_the_promotion_cannot_substitute_its_own_policy(self):
        for name in policy.POLICY_FILES:
            with self.subTest(name=name):
                self.promote('promote-' + Path(name).stem)
                substituted = self.change(name, 'always pass')
                with self.assertRaisesRegex(ValueError, 'policy files exactly'):
                    policy.check('main', substituted, policy.PROMOTION_SOURCE)

    def test_main_advancing_invalidates_the_promotion(self):
        head = self.promote()
        self.git('checkout', '-q', 'main')
        newer = self.change('fix.txt', 'a later main fix')
        self.git('update-ref', 'refs/remotes/origin/main', newer)
        with self.assertRaisesRegex(ValueError, 'Destination advanced'):
            policy.check('main', head, policy.PROMOTION_SOURCE)
        # Refreshing brings main's new code, which the accepted tree never had.
        self.git('checkout', '-q', 'promote')
        self.git('merge', '-q', '--no-edit', newer)
        with self.assertRaisesRegex(ValueError, 'outside docs'):
            policy.check('main', self.git('rev-parse', 'HEAD'), policy.PROMOTION_SOURCE)

    def test_later_experiment_commits_cannot_ride_along_even_when_undone(self):
        self.git('checkout', '-q', '--detach', self.accepted)
        later = self.change('later.txt', 'not accepted')
        self.git('update-ref', policy.EXPERIMENT_REF, later)
        self.promote()
        self.git('merge', '-q', '--no-edit', later)
        self.git('rm', '-q', 'later.txt')
        same_tree = self.commit('undo the later change')
        with self.assertRaisesRegex(ValueError, 'beyond the accepted commit'):
            policy.check('main', same_tree, policy.PROMOTION_SOURCE)

    def test_ordinary_main_fixes_work_after_the_promotion(self):
        promoted = self.promote()
        self.land(promoted)
        self.git('checkout', '-qb', 'codex/fix', promoted)
        fix = self.change('fix.txt', 'fix')
        self.assertEqual(promoted, policy.check('main', fix, 'codex/fix'))
        for name in (policy.EXPERIMENT, 'sync/main-into-expressive-9'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'must not target main'):
                policy.check('main', fix, name)

    def test_renamed_branches_cannot_import_new_experiment_commits_after_promotion(self):
        promoted = self.promote()
        self.land(promoted)
        self.git('checkout', '-q', '--detach', self.git('rev-parse', policy.EXPERIMENT_REF))
        newer = self.change('next-experiment.txt', 'unreviewed')
        self.git('update-ref', policy.EXPERIMENT_REF, newer)
        self.git('checkout', '-qb', 'codex/innocent-name', promoted)
        self.git('merge', '-q', '--no-edit', newer)
        imported = self.git('rev-parse', 'HEAD')
        for source in ('codex/innocent-name', policy.PROMOTION_SOURCE):
            with self.subTest(source=source), self.assertRaisesRegex(ValueError, 'not on main'):
                policy.check('main', imported, source)

    def test_main_polluted_without_the_approved_promotion_fails(self):
        self.git('checkout', '-qb', 'polluted', self.main)
        self.git('merge', '-q', '--no-edit', self.alpha)
        polluted = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/main', polluted)
        fix = self.change('fix.txt', 'fix')
        with self.assertRaisesRegex(ValueError, 'without its approved promotion'):
            policy.check('main', fix, 'codex/fix')

    def test_missing_promotion_or_experiment_history_fails_closed(self):
        head = self.promote()
        with patch.object(policy, 'PROMOTION_COMMIT', 'f' * 40):
            with self.assertRaisesRegex(ValueError, 'accepted experiment commit itself'):
                policy.check('main', head, policy.PROMOTION_SOURCE)
        self.git('update-ref', '-d', policy.EXPERIMENT_REF)
        with self.assertRaises(subprocess.CalledProcessError):
            policy.check('main', head, policy.PROMOTION_SOURCE)


if __name__ == '__main__':
    unittest.main()
