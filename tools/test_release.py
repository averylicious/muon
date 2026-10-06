"""Exercise publication without network access or real signing material."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_release', Path(__file__).with_name('publish-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, previous)
        self.env = patch.dict(os.environ, {
            'CHANNEL': 'canary', 'VERSION': '0.1.0', 'GITHUB_RUN_NUMBER': '7',
            'GITHUB_REPOSITORY': 'example/muon', 'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123',
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.calls = []
        self.existing = None

    def artifact(self, variant='debug', version='0.1.0-canary.7'):
        source = Path('release-input')
        source.mkdir()
        (source / f'app-{variant}.apk').write_bytes(b'test artifact')
        (source / 'SHA256SUMS').write_text(hashlib.sha256(b'test artifact').hexdigest() + f'  app-{variant}.apk\n')
        (source / 'BUILD.txt').write_text(f'Commit: {"a" * 40}\nRun: 7\nVersion: {version}\n')

    def fake_gh(self, *args):
        self.calls.append(args)
        if args[0] == 'api':
            return json.dumps([[self.existing] if self.existing else []])
        return 'https://example.invalid/release'

    def test_canary_upload_before_publish(self):
        self.artifact()
        with patch.object(release, 'gh', self.fake_gh):
            release.publish()
        create, upload, publish = self.calls[1:4]
        self.assertEqual(create[:3], ('release', 'create', '0.1.0-canary.7'))
        self.assertIn('--draft', create)
        self.assertIn('--prerelease', create)
        self.assertEqual(upload[1], 'upload')
        self.assertIn('--latest=false', publish)
        self.assertIn('--draft=false', publish)
        self.assertTrue(Path('release-output/muon-canary-0.1.0-canary.7.apk').exists())

    def test_stable_requires_existing_tag_and_marks_latest(self):
        os.environ['CHANNEL'] = 'stable'
        self.artifact('release', '0.1.0')
        with patch.object(release, 'gh', self.fake_gh):
            release.publish()
        self.assertIn('--verify-tag', self.calls[1])
        self.assertIn('--latest=true', self.calls[3])
        self.assertIn('--prerelease=false', self.calls[3])

    def test_corruption_never_calls_github(self):
        self.artifact()
        Path('release-input/app-debug.apk').write_bytes(b'corrupt')
        with patch.object(release, 'gh', self.fake_gh), self.assertRaises(SystemExit):
            release.publish()
        self.assertEqual(self.calls, [])

    def test_wrong_commit_never_calls_github(self):
        self.artifact()
        os.environ['GITHUB_SHA'] = 'b' * 40
        with patch.object(release, 'gh', self.fake_gh), self.assertRaises(SystemExit):
            release.publish()
        self.assertEqual(self.calls, [])

    def test_diagnostic_build_is_never_published(self):
        # Even with an otherwise matching Version line, a Mode line marks a diagnostic artifact.
        self.artifact()
        build = Path('release-input/BUILD.txt')
        build.write_text(build.read_text() + 'Mode: diagnostic (debuggable; temporary testing only, never published)\n')
        with patch.object(release, 'gh', self.fake_gh), self.assertRaisesRegex(SystemExit, 'never published'):
            release.publish()
        self.assertEqual(self.calls, [])

    def test_diagnostic_artifact_version_does_not_match_canary(self):
        self.artifact(version='0.1.0-canary.7-diagnostic')
        with patch.object(release, 'gh', self.fake_gh), self.assertRaises(SystemExit):
            release.publish()
        self.assertEqual(self.calls, [])

    def test_diagnostic_setting_is_refused_for_either_channel(self):
        for channel, variant, version in [('canary', 'debug', '0.1.0-canary.7'), ('stable', 'release', '0.1.0')]:
            for value in ['true', 'TRUE', '']:
                with self.subTest(channel=channel, value=value), tempfile.TemporaryDirectory() as temp:
                    os.chdir(temp)
                    self.artifact(variant, version)
                    with patch.dict(os.environ, {'CHANNEL': channel, 'MUON_DIAGNOSTIC_DEBUG': value}), \
                            patch.object(release, 'gh', self.fake_gh), \
                            self.assertRaisesRegex(SystemExit, 'never published'):
                        release.publish()
                    self.assertEqual(self.calls, [])

    def test_published_release_is_unchanged(self):
        self.artifact()
        self.existing = {'tag_name': '0.1.0-canary.7', 'draft': False, 'html_url': 'example'}
        with patch.object(release, 'gh', self.fake_gh):
            release.publish()
        self.assertEqual(len(self.calls), 1)

    def test_draft_from_different_commit_is_not_overwritten(self):
        self.artifact()
        self.existing = {'tag_name': '0.1.0-canary.7', 'draft': True, 'target_commitish': 'b' * 40}
        with patch.object(release, 'gh', self.fake_gh), self.assertRaises(SystemExit):
            release.publish()
        self.assertEqual(len(self.calls), 1)


if __name__ == '__main__':
    unittest.main()
