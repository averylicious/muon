"""Exercise real Git histories so docs commits cannot hide unmerged application work."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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
        self.published = {}
        releases = patch.object(ci_scope, 'published_canaries', return_value=self.published, create=True)
        self.release_query = releases.start()
        self.addCleanup(releases.stop)
        self.base = self.commit('README.md', '# Hello\n')
        self.git('update-ref', 'refs/remotes/origin/main', self.base)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode().strip()

    def tag(self, name, ref='HEAD', published=True):
        self.git('tag', name, ref)
        if published:
            self.published[name] = self.git('rev-parse', ref)

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
        self.tag('0.1.0-canary.9', self.base)
        self.assertFalse(self.scope(head, ref='refs/heads/main'))

    def test_main_without_a_reachable_canary_baseline_builds(self):
        # A docs push can cancel an app build before the first publication, or after history loss.
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_canary_on_an_unrelated_branch_is_not_a_publication_baseline(self):
        self.git('checkout', '-qb', 'experiment')
        self.commit('app/Experimental.kt', 'class Experimental')
        self.tag('0.1.0-canary.999')
        self.git('checkout', 'main')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, ref='refs/heads/main'))

    def test_docs_after_code_on_feature_branch_still_build(self):
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('README.md', '# Updated\n')
        self.assertTrue(self.scope(head, code))

    def test_docs_after_unpublished_code_on_main_still_builds(self):
        # Code merged, its run cancelled by a docs merge: the last Canary is still the base.
        self.tag('0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_docs_after_published_code_on_main_skips(self):
        self.tag('0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.tag('0.1.0-canary.10', code)
        head = self.commit('docs/note.md', '# Note\n')
        self.assertFalse(self.scope(head, code, ref='refs/heads/main'))

    def test_tag_without_published_release_does_not_hide_app_work(self):
        self.tag('0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.tag('0.1.0-canary.10', code, published=False)
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_tag_moved_away_from_release_commit_is_not_a_baseline(self):
        self.tag('0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.git('tag', '-f', '0.1.0-canary.9', code)
        head = self.commit('docs/note.md', '# Note\n')
        self.assertTrue(self.scope(head, code, ref='refs/heads/main'))

    def test_unknown_release_status_builds_instead_of_skipping(self):
        self.tag('0.1.0-canary.9', self.base)
        head = self.commit('docs/note.md', '# Note\n')
        for error in [OSError('unavailable'), ValueError('malformed JSON'),
                      subprocess.CalledProcessError(1, 'gh'),
                      subprocess.TimeoutExpired('gh', 30)]:
            with self.subTest(error=type(error).__name__):
                self.release_query.side_effect = error
                self.assertTrue(self.scope(head, ref='refs/heads/main'))

    def test_feature_docs_do_not_need_release_api(self):
        self.release_query.side_effect = AssertionError('No release lookup on a feature branch')
        head = self.commit('docs/note.md', '# Note\n')
        self.assertFalse(self.scope(head))
        self.release_query.assert_not_called()

    def docs_merge(self, path='app/Thing.kt', publish=True, main_docs=False,
                   previous_code=False):
        self.git('checkout', '-qb', 'docs')
        if previous_code:
            self.commit('app/Feature.kt', 'class Feature')
        before = self.commit('docs/note.md', '# Note\n')
        self.git('checkout', 'main')
        code = self.commit(path, 'main update')
        self.tag('0.1.0-canary.10', code, published=publish)
        if main_docs:
            self.commit('README.md', '# Main note\n')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.git('checkout', 'docs')
        self.git('merge', '--no-ff', '-m', 'Refresh main', 'main')
        return before, self.git('rev-parse', 'HEAD'), code

    def test_docs_merge_of_published_main_skips(self):
        for path in ['app/Thing.kt', 'tools/check.py', 'docs/signing-certificates.txt']:
            with self.subTest(path=path):
                # Each fixture starts a separate real history.
                before, head, _ = self.docs_merge(path=path)
                build, reason, paths = ci_scope.classify(
                    'push', 'refs/heads/docs', head, {'before': before})
                self.assertFalse(build)
                self.assertIn('inherited main code already published', reason)
                self.assertEqual({'docs/note.md'}, paths)
                self.git('checkout', 'main')
                self.git('branch', '-D', 'docs')
                self.git('tag', '-d', '0.1.0-canary.10')

    def test_docs_merge_after_published_main_and_main_docs_skips(self):
        before, head, _ = self.docs_merge(main_docs=True)
        self.assertFalse(self.scope(head, before))

    def test_docs_merge_of_unpublished_main_builds(self):
        before, head, _ = self.docs_merge(publish=False)
        self.assertTrue(self.scope(head, before))

    def test_docs_merge_with_unknown_publication_builds(self):
        before, head, _ = self.docs_merge()
        self.release_query.side_effect = OSError('unavailable')
        self.assertTrue(self.scope(head, before))

    def test_docs_merge_with_moved_publication_tag_builds(self):
        before, head, _ = self.docs_merge()
        self.git('tag', '-f', '0.1.0-canary.10', self.base)
        self.assertTrue(self.scope(head, before))

    def test_main_code_after_last_publication_still_builds_on_docs_merge(self):
        before, _, _ = self.docs_merge()
        self.git('checkout', 'main')
        self.commit('app/Unpublished.kt', 'class Unpublished')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.git('checkout', 'docs')
        self.git('merge', '--no-ff', '-m', 'Refresh main again', 'main')
        self.assertTrue(self.scope(self.git('rev-parse', 'HEAD'), before))

    def test_docs_merge_with_feature_code_still_builds(self):
        before, head, _ = self.docs_merge(previous_code=True)
        self.assertTrue(self.scope(head, before))
        self.release_query.assert_not_called()

    def test_removing_previous_feature_code_during_merge_still_builds(self):
        before, _, _ = self.docs_merge(previous_code=True)
        self.git('rm', 'app/Feature.kt')
        self.git('commit', '-qm', 'Remove feature code')
        self.assertTrue(self.scope(self.git('rev-parse', 'HEAD'), before))
        self.release_query.assert_not_called()

    def test_force_push_replacing_docs_history_with_published_main_builds(self):
        before, head, code = self.docs_merge()
        self.git('checkout', '-qb', 'replacement', code)
        replacement = self.commit('docs/replacement.md', '# Replacement\n')
        self.assertTrue(self.scope(replacement, before))

    def test_docs_head_missing_current_main_still_builds(self):
        before, head, _ = self.docs_merge()
        self.git('checkout', 'main')
        self.commit('README.md', '# New main note\n')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.assertTrue(self.scope(head, before))

    def test_newest_canary_is_chosen_by_run_number(self):
        self.tag('0.1.0-canary.9', self.base)
        code = self.commit('app/Thing.kt', 'class Thing')
        self.tag('0.1.0-canary.10', code)
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


class PublishedCanaryTest(unittest.TestCase):
    def release(self, tag='0.1.0-canary.10'):
        return {'tag_name': tag, 'target_commitish': 'a' * 40, 'draft': False,
                'prerelease': True, 'published_at': '2026-09-29T00:00:00Z',
                'assets': [{'name': name, 'state': 'uploaded', 'size': 10}
                           for name in [f'muon-canary-{tag}.apk', 'SHA256SUMS', 'BUILD.txt']]}

    def query(self, pages):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'owner/repo'}), \
                patch.object(ci_scope.subprocess, 'check_output', return_value=json.dumps(pages)) as api:
            result = ci_scope.published_canaries()
            api.assert_called_once_with(
                ['gh', 'api', '--paginate', '--slurp', 'repos/owner/repo/releases?per_page=100'],
                stderr=subprocess.PIPE, text=True, timeout=30)
            return result

    def test_complete_release_on_a_later_page_is_included(self):
        self.assertEqual({'0.1.0-canary.10': 'a' * 40}, self.query([[], [self.release()]]))

    def test_unpublished_or_unverifiable_releases_are_not_baselines(self):
        for change in [{'draft': True}, {'prerelease': False}, {'published_at': None},
                       {'target_commitish': 'main'}, {'target_commitish': 'b' * 39},
                       {'tag_name': 'v1.0.0'}, {'draft': None}, {'assets': None}]:
            with self.subTest(change=change):
                release = self.release()
                release.update(change)
                self.assertEqual({}, self.query([[release]]))

    def test_missing_pending_empty_or_wrongly_named_assets_are_rejected(self):
        for index in range(3):
            for change in [None, {'state': 'new'}, {'size': 0}, {'size': '10'},
                           {'size': True}, {'name': 'unrelated.txt'}]:
                with self.subTest(asset=index, change=change):
                    release = copy.deepcopy(self.release())
                    if change is None:
                        release['assets'].pop(index)
                    else:
                        release['assets'][index].update(change)
                    self.assertEqual({}, self.query([[release]]))

    def test_malformed_top_level_response_fails_conservatively(self):
        for pages in [{}, [None], [[None]]]:
            with self.subTest(pages=pages), self.assertRaises(ValueError):
                self.query(pages)

    def test_unknown_repository_never_calls_api(self):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': ''}), \
                patch.object(ci_scope.subprocess, 'check_output') as api, \
                self.assertRaises(ValueError):
            ci_scope.published_canaries()
        api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
