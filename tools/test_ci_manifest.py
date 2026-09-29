"""Regressions for the compiled-manifest export gate, including dependency components."""
import unittest
import xml.etree.ElementTree as ET

from apk_manifest import ANDROID, verify_manifest


class ManifestTest(unittest.TestCase):
    def manifest(self, package='dev.avery.muon'):
        root = ET.Element('manifest', {'package': package})
        app = ET.SubElement(root, 'application')
        for kind, name, exported, permission in [
            ('activity', 'dev.avery.muon.MainActivity', 'true', None),
            ('service', 'dev.avery.muon.PlaybackService', 'false', None),
            ('service', 'dev.avery.muon.MuonDownloadService', 'false', None),
            ('service', 'dev.avery.muon.MuonCardDownloadService', 'false', None),
            ('provider', 'androidx.startup.InitializationProvider', 'false', None),
            ('activity', 'androidx.media3.session.BluetoothValidationActivity', 'true',
             'android.permission.BLUETOOTH_PRIVILEGED'),
            ('receiver', 'androidx.profileinstaller.ProfileInstallReceiver', 'true', 'android.permission.DUMP'),
        ]:
            node = ET.SubElement(app, kind, {ANDROID + 'name': name, ANDROID + 'exported': exported})
            if permission:
                node.set(ANDROID + 'permission', permission)
        return root

    def check(self, root, package='dev.avery.muon'):
        return verify_manifest(ET.tostring(root, encoding='unicode'), package)

    def test_both_channels_preserve_source_namespace(self):
        for package in ('dev.avery.muon', 'dev.avery.muon.release'):
            self.check(self.manifest(package), package)

    def test_old_exported_playback_service_is_rejected(self):
        root = self.manifest()
        root.find('application/service').set(ANDROID + 'exported', 'true')
        with self.assertRaisesRegex(ValueError, 'Unapproved exported'):
            self.check(root)

    def test_new_dependency_exports_and_activity_aliases_are_rejected(self):
        for kind in ('activity', 'activity-alias', 'service', 'provider', 'receiver'):
            root = self.manifest()
            ET.SubElement(root.find('application'), kind,
                {ANDROID + 'name': 'unexpected.Component', ANDROID + 'exported': 'true'})
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Unapproved exported'):
                self.check(root)

    def test_dependency_permissions_cannot_be_removed_or_weakened(self):
        for kind in ('receiver', 'activity'):
            for permission in (None, '', 'android.permission.INTERNET'):
                root = self.manifest()
                node = root.findall('application/' + kind)[-1]
                node.attrib.pop(ANDROID + 'permission')
                if permission is not None:
                    node.set(ANDROID + 'permission', permission)
                with self.subTest(kind=kind, permission=permission), self.assertRaisesRegex(ValueError, 'permission'):
                    self.check(root)

    def test_missing_or_non_boolean_export_flag_fails_closed(self):
        for flag in (None, '', '0', '@bool/exported'):
            root = self.manifest()
            node = root.find('application/provider')
            node.attrib.pop(ANDROID + 'exported')
            if flag is not None:
                node.set(ANDROID + 'exported', flag)
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                self.check(root)

    def test_missing_required_service_is_rejected(self):
        root = self.manifest()
        app = root.find('application')
        app.remove(app.find('service'))
        with self.assertRaisesRegex(ValueError, 'Missing component'):
            self.check(root)

    def test_launcher_cannot_be_silently_disabled_or_restricted(self):
        root = self.manifest()
        root.find('application/activity').set(ANDROID + 'exported', 'false')
        with self.assertRaises(ValueError):
            self.check(root)
        root = self.manifest()
        root.find('application').set(ANDROID + 'permission', 'unexpected.permission')
        with self.assertRaises(ValueError):
            self.check(root)

    def test_private_dependency_component_is_allowed(self):
        root = self.manifest()
        ET.SubElement(root.find('application'), 'service',
            {ANDROID + 'name': '.PrivateService', ANDROID + 'exported': 'false'})
        self.check(root)

    def test_duplicate_or_malformed_manifest_is_rejected(self):
        root = self.manifest()
        app = root.find('application')
        app.append(ET.fromstring(ET.tostring(app.find('service'))))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.check(root)
        for xml in ('garbage', '<manifest/>', '<manifest package="dev.avery.muon"/>'):
            with self.subTest(xml=xml), self.assertRaises((ValueError, ET.ParseError)):
                verify_manifest(xml, 'dev.avery.muon')


if __name__ == '__main__':
    unittest.main()
