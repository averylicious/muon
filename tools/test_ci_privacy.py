"""Keep the declared no-backup policy complete as app storage grows."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class BackupPolicyTest(unittest.TestCase):
    def test_backup_and_transfer_exclude_every_android_storage_domain(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        app = manifest.getroot().find('application')
        self.assertEqual('false', app.get(ANDROID + 'allowBackup'))
        self.assertEqual('false', app.get(ANDROID + 'fullBackupContent'))
        self.assertEqual('@xml/data_extraction_rules', app.get(ANDROID + 'dataExtractionRules'))
        self.assertIsNone(app.get(ANDROID + 'backupAgent'))

        rules = ET.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
        domains = {'root', 'file', 'database', 'sharedpref', 'external',
                   'device_root', 'device_file', 'device_database', 'device_sharedpref'}
        for mode in ('cloud-backup', 'device-transfer'):
            with self.subTest(mode=mode):
                section = rules.find(mode)
                self.assertIsNotNone(section)
                self.assertEqual([], section.findall('include'))
                excluded = {entry.get('domain') for entry in section.findall('exclude')
                            if entry.get('path') == '.'}
                self.assertEqual(domains, excluded)


if __name__ == '__main__':
    unittest.main()
