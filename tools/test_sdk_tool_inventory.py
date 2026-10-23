import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from sdk_tool_inventory import inventory, MAX_FILE

SHA = 'a' * 40


class SdkInventoryTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / 'sdk'
        (self.root / 'lib').mkdir(parents=True)
        (self.root / 'apksigner').write_text('public wrapper')
        (self.root / 'source.properties').write_text('Pkg.Revision=37.0.0')
        self.jar = self.root / 'lib/apksigner.jar'
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('com/android/apksig/ApkVerifier.class', b'fixture, never executed')
            z.writestr('org/bouncycastle/jce/provider/BouncyCastleProvider.class', b'fixture')
            z.writestr('arbitrary/private.txt', b'do not publish this payload')

    def test_hashes_change_and_payloads_are_not_emitted(self):
        first = inventory(self.root, SHA)
        self.assertEqual(first['commit'], SHA)
        jar = first['files'][1]
        self.assertTrue(jar['apksig_class_present'])
        self.assertTrue(jar['provider_classes_present']['org/bouncycastle/jce/provider/BouncyCastleProvider.class'])
        self.assertFalse(jar['provider_classes_present']['org/conscrypt/OpenSSLProvider.class'])
        self.assertEqual(jar['class_entries'], 2)
        self.assertNotIn('do not publish', json.dumps(first))
        with self.jar.open('ab') as f:
            f.write(b'trailing byte change')
        second = inventory(self.root, SHA)
        self.assertNotEqual(jar['sha256'], second['files'][1]['sha256'])

    def test_missing_corrupt_and_oversized_files_refused(self):
        self.jar.unlink()
        with self.assertRaises(OSError): inventory(self.root, SHA)
        self.jar.write_text('not a jar')
        with self.assertRaises(zipfile.BadZipFile): inventory(self.root, SHA)
        with self.jar.open('wb') as f: f.truncate(MAX_FILE + 1)
        with self.assertRaises(ValueError): inventory(self.root, SHA)

    def test_file_and_intermediate_directory_symlinks_refused(self):
        wrapper = self.root / 'apksigner'
        other = self.root.parent / 'outside'
        other.write_text('private data must not be read')
        wrapper.unlink(); wrapper.symlink_to(other)
        with self.assertRaises(ValueError): inventory(self.root, SHA)
        wrapper.unlink(); wrapper.write_text('wrapper')
        self.jar.unlink(); (self.root / 'lib').rmdir()
        external = self.root.parent / 'external-lib'; external.mkdir()
        (self.root / 'lib').symlink_to(external, target_is_directory=True)
        (external / 'apksigner.jar').write_text('not read')
        with self.assertRaises(ValueError): inventory(self.root, SHA)

    def test_commit_provenance_required(self):
        for value in ('main', 'a' * 39, 'A' * 40, SHA + '\n'):
            with self.subTest(value=value), self.assertRaises(ValueError): inventory(self.root, value)


if __name__ == '__main__':
    unittest.main()
