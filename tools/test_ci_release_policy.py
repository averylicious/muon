"""Real Git graph checks for accidental cross-track Stable promotion."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_branch_policy as branches
import check_release_policy as release


class ReleasePolicyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(temp.cleanup)
        previous = Path.cwd()
        os.chdir(temp.name)
        self.addCleanup(os.chdir, previous)
        self.git('init', '-q', '-b', 'main')
        for key, value in [('user.name', 'Policy test'), ('user.email', 'ci@example.invalid'),
                           ('commit.gpgsign', 'false'), ('tag.gpgsign', 'false'),
                           ('gc.auto', '0'), ('maintenance.auto', 'false')]:
            self.git('config', key, value)
        self.old_main = self.commit('baseline.txt')
        self.git('checkout', '-qb', 'experiment')
        self.alpha = self.commit('alpha.txt')
        self.git('checkout', 'main')
        self.main = self.commit('main-fix.txt')
        self.git('update-ref', 'refs/remotes/origin/main', self.main)
        anchor = patch.object(branches, 'EXPERIMENT_START', self.alpha)
        anchor.start()
        self.addCleanup(anchor.stop)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()

    def commit(self, name):
        Path(name).write_text(name)
        self.git('add', name)
        self.git('commit', '-qm', name)
        return self.git('rev-parse', 'HEAD')

    def tag(self, commit, annotated=False):
        args = ['tag', 'v1.2.3', commit]
        if annotated:
            args += ['-a', '-m', 'disposable test tag']
        self.git(*args)

    def test_lightweight_main_tag_passes(self):
        self.tag(self.main)
        self.assertEqual(self.main, release.check('tag', 'v1.2.3', self.main))

    def test_annotated_historical_main_tag_passes(self):
        self.tag(self.old_main, annotated=True)
        self.assertEqual(self.main, release.check('tag', 'v1.2.3', self.old_main))

    def test_experimental_tag_fails_even_after_alpha_reaches_main(self):
        self.git('merge', '--no-edit', 'experiment')
        polluted_main = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/main', polluted_main)
        self.tag(polluted_main)
        with self.assertRaisesRegex(ValueError, 'alpha experiment'):
            release.check('tag', 'v1.2.3', polluted_main)

    def test_renamed_unmerged_audit_branch_cannot_be_published_as_stable(self):
        self.git('checkout', '-qb', 'innocent-name')
        unmerged = self.commit('unreviewed.txt')
        self.tag(unmerged)
        with self.assertRaisesRegex(ValueError, 'already included'):
            release.check('tag', 'v1.2.3', unmerged)

    def test_moved_tag_cannot_publish_the_old_workflow_commit(self):
        self.tag(self.old_main)
        with self.assertRaisesRegex(ValueError, 'no longer resolves'):
            release.check('tag', 'v1.2.3', self.main)

    def test_missing_main_or_anchor_fails_closed(self):
        self.tag(self.main)
        with patch.object(branches, 'EXPERIMENT_START', 'f' * 40):
            with self.assertRaises(subprocess.CalledProcessError):
                release.check('tag', 'v1.2.3', self.main)
        self.git('update-ref', '-d', 'refs/remotes/origin/main')
        with self.assertRaises(subprocess.CalledProcessError):
            release.check('tag', 'v1.2.3', self.main)

    def test_shallow_history_fails_closed(self):
        self.tag(self.main)
        source = Path.cwd()
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as cloned:
            subprocess.check_output(['git', 'clone', '-q', '--depth', '1', source.as_uri(), cloned],
                                    stderr=subprocess.PIPE)
            os.chdir(cloned)
            try:
                self.assertEqual('true', self.git('rev-parse', '--is-shallow-repository'))
                with self.assertRaisesRegex(ValueError, 'Complete fetched'):
                    release.check('tag', 'v1.2.3', self.main)
            finally:
                os.chdir(source)

    def test_unknown_or_noncanonical_metadata_fails_closed(self):
        for ref_type, ref_name, head in [('other', 'v1.2.3', self.main),
                                       ('tag', 'v01.2.3', self.main),
                                       ('tag', 'v1.2.3-alpha', self.main),
                                       ('tag', 'v1.2.3', 'HEAD')]:
            with self.subTest(ref_type=ref_type, ref_name=ref_name, head=head), self.assertRaises(ValueError):
                release.check(ref_type, ref_name, head)

    def test_branches_skip_the_tag_only_guard(self):
        self.assertIsNone(release.check('branch', branches.EXPERIMENT, self.alpha))
        self.assertIsNone(release.check('branch', 'main', self.main))


class ReleaseWorkflowPlacementTest(unittest.TestCase):
    def test_gates_precede_signing_and_publication_with_complete_history(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/android.yml').read_text()
        build, publish = workflow.split('\n  publish:', 1)
        guard = 'run: python3 tools/check_release_policy.py'
        self.assertLess(build.index(guard), build.index('- name: Restore signing keys'))
        self.assertLess(publish.index(guard), publish.index('- name: Download verified APK artifact'))
        self.assertIn('fetch-depth: 0', build[:build.index(guard)])
        self.assertIn('fetch-depth: 0', publish[:publish.index(guard)])


if __name__ == '__main__':
    unittest.main()
