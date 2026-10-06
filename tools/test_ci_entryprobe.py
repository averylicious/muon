from pathlib import Path
import unittest
from unittest.mock import patch
import verify_entryprobe as probe

ROOT = Path(__file__).resolve().parent.parent
XML = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.avery.muon.entryprobe" android:versionCode="42" android:versionName="qa.42">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="37"/>
<queries><package android:name="dev.avery.muon"/></queries>
<application android:debuggable="true" android:allowBackup="false" android:usesCleartextTraffic="false" android:label="Muon QA probe"><activity android:name="dev.avery.muon.entryprobe.ProbeActivity" android:exported="true"/></application>
</manifest>'''


class ProbeArtifactTest(unittest.TestCase):
    def test_accepts_only_the_separate_diagnostic_identity(self):
        probe.check_manifest(XML, '42')
        for old, new in [('dev.avery.muon.entryprobe"', 'dev.avery.muon"'),
                         ('qa.42', '0.1.0-canary.42'), ('versionCode="42"', 'versionCode="41"'),
                         ('debuggable="true"', 'debuggable="false"'),
                         ('allowBackup="false"', 'allowBackup="true"')]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                probe.check_manifest(XML.replace(old, new), '42')

    def test_rejects_privileges_shared_uid_and_extra_components(self):
        for tag in ['uses-permission', 'uses-permission-sdk-23', 'instrumentation']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                probe.check_manifest(XML.replace('<uses-sdk', '<'+tag+' android:name="anything"/><uses-sdk'), '42')
        for tag in ['service', 'provider', 'receiver']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                probe.check_manifest(XML.replace('</application>', '<'+tag+'/></application>'), '42')
        with self.assertRaises(ValueError):
            probe.check_manifest(XML.replace(' package=', ' android:sharedUserId="android.uid.system" package='), '42')

    def test_rejects_broadened_queries_or_launcher_substitution(self):
        for changed in [XML.replace('android:name="dev.avery.muon"', 'android:name="dev.avery.muon.release"'),
                        XML.replace('</queries>', '<intent/></queries>'),
                        XML.replace('.ProbeActivity', '.OtherActivity'),
                        XML.replace('exported="true"', 'exported="false"')]:
            with self.subTest(xml=changed), self.assertRaises(ValueError):
                probe.check_manifest(changed, '42')

    def test_mode_guard_runs_before_any_apk_tool_on_disallowed_events(self):
        env = {'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF_TYPE':'branch',
               'GITHUB_REF_NAME':'codex/qa','MUON_DIAGNOSTIC_REQUEST':'true',
               'MUON_DIAGNOSTIC_DEBUG':'true','MUON_VERSION_CODE':'42'}
        with patch.object(probe.subprocess, 'check_output', return_value=XML) as tool:
            probe.main(Path('analyzer'), Path('probe.apk'), env)
            self.assertEqual(tool.call_count, 1)
        for changed in [dict(env, GITHUB_REF_NAME='main'), dict(env, MUON_DIAGNOSTIC_REQUEST='false'),
                        dict(env, GITHUB_EVENT_NAME='push', MUON_DIAGNOSTIC_REQUEST=''),
                        dict(env, MUON_DIAGNOSTIC_DEBUG='false')]:
            with self.subTest(env=changed), patch.object(probe.subprocess, 'check_output') as tool:
                with self.assertRaises(ValueError):
                    probe.main(Path('analyzer'), Path('probe.apk'), changed)
                tool.assert_not_called()

    def test_workflow_builds_before_signing_and_only_in_diagnostic_mode(self):
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        start = workflow.index('      - name: Build disposable media-entry QA helper')
        end = workflow.index('      - name: Restore signing keys')
        block = workflow[start:end]
        self.assertEqual(block.count("if: steps.diagnostic.outputs.diagnostic == 'true'"), 2)
        self.assertNotIn('secrets.', block)
        self.assertIn(':mediaentryprobe:assembleDebug :mediaentryprobe:lintDebug', block)
        self.assertIn('python3 tools/verify_entryprobe.py', block)
        self.assertIn('media-entry-probe-${{ github.sha }}', block)
        settings = (ROOT/'settings.gradle.kts').read_text()
        self.assertIn('if (providers.environmentVariable("MUON_DIAGNOSTIC_DEBUG").orNull == "true") include(":mediaentryprobe")', settings)
        gradle = (ROOT/'mediaentryprobe/build.gradle.kts').read_text()
        self.assertNotIn('MUON_DEBUG_KEYSTORE', gradle)
        self.assertNotIn('MUON_RELEASE_', gradle)


if __name__ == '__main__': unittest.main()
