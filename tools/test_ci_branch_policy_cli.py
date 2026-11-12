"""Command-line contract of the branch preflight, run as the Branch policy workflow runs it.

These cases use the experiment destination, whose rules consult no alpha anchor, so the real script
runs unmodified. Graph rules for main are covered in test_ci_branch_policy.py.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).resolve().with_name('check_branch_policy.py')
EXPERIMENT = 'claude/m3-expressive-alpha'
DESTINATION = 'refs/remotes/origin/' + EXPERIMENT


class BranchPolicyCommandTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(temp.cleanup)
        self.repo = temp.name
        self.git('init', '-q', '-b', 'topic')
        for key, value in [('user.name', 'Policy test'), ('user.email', 'ci@example.invalid'),
                           ('commit.gpgsign', 'false'), ('gc.auto', '0'), ('maintenance.auto', 'false')]:
            self.git('config', key, value)
        self.destination = self.commit('destination.txt')
        self.git('update-ref', DESTINATION, self.destination)
        self.head = self.commit('change.txt')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', self.repo, *args],
                                       stderr=subprocess.PIPE, text=True).strip()

    def commit(self, name):
        Path(self.repo, name).write_text(name)
        self.git('add', name)
        self.git('commit', '-qm', name)
        return self.git('rev-parse', 'HEAD')

    def check(self, head, source='claude/topic'):
        return subprocess.run([sys.executable, '-I', str(CHECKER), '--base', EXPERIMENT,
                               '--head', head, '--source', source],
                              cwd=self.repo, capture_output=True, text=True)

    def assert_refused(self, result, reason=''):
        self.assertEqual(1, result.returncode)
        self.assertIn('Branch policy failed: ' + reason, result.stderr)
        self.assertNotIn('passed', result.stdout)

    def test_ordinary_branch_containing_the_experiment_passes_and_names_the_destination(self):
        result = self.check(self.head)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f'Branch policy passed: {self.head} includes {EXPERIMENT} at {self.destination}.',
                      result.stdout)

    def test_destination_that_moved_on_is_refused_with_exit_one(self):
        self.git('checkout', '-q', '--detach', self.destination)
        self.git('update-ref', DESTINATION, self.commit('newer.txt'))
        self.assert_refused(self.check(self.head), 'Destination advanced')

    def test_missing_destination_ref_fails_closed(self):
        self.git('update-ref', '-d', DESTINATION)
        self.assert_refused(self.check(self.head))

    def test_head_commit_that_is_not_in_the_clone_fails_closed(self):
        self.assert_refused(self.check('f' * 40))


if __name__ == '__main__':
    unittest.main()
