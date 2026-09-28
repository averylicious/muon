"""Exercise real Git histories so docs commits cannot hide unmerged application work."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import ci_scope


class ScopeTest(unittest.TestCase):
    def setUp(self):
        # Git can still be writing to .git (auto gc or maintenance) when cleanup runs, which made
        # the removal fail with "Directory not empty" on a CI runner. Turn those off, and don't let
        # a leftover temp file fail a test that passed.
        temp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(temp.cleanup)
        old = Path.cwd()
        os.chdir(temp.name)
        self.addCleanup(os.chdir, old)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'CI test')
        self.git('config', 'user.email', 'ci@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('config', 'gc.auto', '0')
        self.git('config', 'maintenance.auto', 'false')
        self.base = self.commit('README.md', '# Hello\n')
        self.git('update-ref', 'refs/remotes/origin/main', self.base)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

    def commit(self, name, content):
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        self.git('add', '.')
        self.git('commit', '-qm', 'test')
        return self.git('rev-parse', 'HEAD')

    def scope(self, head, before=None, ref='refs/heads/docs'):
        return ci_scope.classify('push', ref, head, {'before': before or self.base})[0]

    def test_docs_and_new_docs_branch_skip(self):
        head = self.commit('docs/a guide.md', '# Guide\n')
        self.assertFalse(self.scope(head))
        self.assertFalse(self.scope(head, '0' * 40))
        self.git('tag', '0.1.0-canary.9', self.base)
        self.assertFalse(self.scope(head, ref='refs/heads/main'))

    def test_main_without_a_reachable_canary_baseline_builds(self):
        # A docs push can cancel an app build before the first publication, or after history loss.
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_canary_on_an_unrelated_branch_is_not_a_publication_baseline(self):
        self.git('checkout', '-qb', 'experiment')
        self.commit('app/Experimental.kt', 'class Experimental')
        self.git('tag', '0.1.0-canary.999')
        self.git('checkout', 'main')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, ref='refs/heads/main'))

    def test_docs_after_code_on_feature_branch_still_build(self):
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('README.md', '# Updated\n')
        self.assertTrue(self.scope(head, code))

    def test_docs_after_unpublished_code_on_main_still_builds(self):
        # Code merged, its run cancelled by a docs merge: the last Canary is still the base.
        self.git('tag', '0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_docs_after_published_code_on_main_skips(self):
        self.git('tag', '0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.git('tag', '0.1.0-canary.10', code)
        head = self.commit('docs/note.md', '# Note\n')
        self.assertFalse(self.scope(head, code, ref='refs/heads/main'))

    def test_newest_canary_is_chosen_by_run_number(self):
        self.git('tag', '0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.git('tag', '0.1.0-canary.10', code)
        self.assertEqual('0.1.0-canary.10', ci_scope.last_canary(code))

    def test_multi_commit_push_includes_code(self):
        self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, ref='refs/heads/main'))

    def test_sensitive_and_unknown_paths_build(self):
        for path in ['app/a.md', '.github/workflows/android.yml', 'gradle/x',
                     'tools/x.py', 'docs/generate.py', 'docs/signing-certificates.txt', '.gitignore', 'unknown.file']:
            with self.subTest(path=path):
                self.assertFalse(ci_scope.is_documentation(path))
        for path in ['AGENTS.md', 'CLAUDE.md', 'docs/design/dark/player.png', '.github/pull_request_template.md']:
            self.assertTrue(ci_scope.is_documentation(path))

    def test_rename_from_code_to_docs_builds(self):
        code = self.commit('app/code.kt', 'class Code')
        self.git('update-ref', 'refs/remotes/origin/main', code)
        Path('docs').mkdir()
        self.git('mv', 'app/code.kt', 'docs/code.md')
        self.git('commit', '-qm', 'move')
        self.assertTrue(self.scope(self.git('rev-parse', 'HEAD'), code))

    def test_docs_deletion_can_skip(self):
        Path('README.md').unlink()
        self.git('add', '-u')
        self.git('commit', '-qm', 'delete')
        self.assertFalse(self.scope(self.git('rev-parse', 'HEAD')))

    def test_force_push_compares_removed_code(self):
        code = self.commit('app/code.kt', 'class Code')
        self.git('reset', '--hard', self.base)
        head = self.commit('docs/note.md', '# Note')
        self.assertTrue(self.scope(head, code))

    def test_unknown_history_or_empty_diff_builds(self):
        self.assertTrue(self.scope(self.base))
        self.assertTrue(self.scope(self.base, 'f' * 40))
        self.assertTrue(self.scope(self.base, '--bad-revision'))
        self.git('update-ref', '-d', 'refs/remotes/origin/main')
        head = self.commit('README.md', '# Updated')
        self.assertTrue(self.scope(head))

    def test_deleted_branch_needs_no_apks(self):
        self.assertFalse(ci_scope.classify('push', 'refs/heads/old', self.base,
                                          {'deleted': True, 'after': '0' * 40})[0])

    def test_manual_and_tag_always_build(self):
        for event, ref in [('workflow_dispatch', 'refs/heads/docs'), ('push', 'refs/tags/v1.0.0')]:
            self.assertTrue(ci_scope.classify(event, ref, self.base, {})[0])

    def test_document_checks(self):
        Path('docs').mkdir()
        path = Path('docs/check.md')
        path.write_text('Heading\n=======\n~~~text\n```\n~~~~\n')
        ci_scope.check_documents([str(path), 'docs/deleted.md'])
        for bad in ['```python\nmissing close', '<<<<<<< HEAD\ntext', 'bad\0text']:
            path.write_text(bad)
            with self.assertRaises(ValueError):
                ci_scope.check_documents([str(path)])
        path.write_bytes(b'\xff')
        with self.assertRaises(UnicodeError):
            ci_scope.check_documents([str(path)])


if __name__ == '__main__':
    unittest.main()
