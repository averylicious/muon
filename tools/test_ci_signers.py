"""The signer pin is an exact identity gate, not a substring search of tool output."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('signer_verifier', Path(__file__).with_name('verify-apks.py'))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
PIN = 'ab' * 32
OTHER = 'cd' * 32


def output(*rows, count='1'):
    return f'Number of signers: {count}\n' + '\n'.join(rows) + '\n'


def row(label='V2 Signer:', pin=PIN):
    return f'{label} certificate SHA-256 digest: {pin}'


class ApkSignerIdentityTest(unittest.TestCase):
    def test_pinned_single_signer_and_applicable_sdk_schemes(self):
        verify.check_signing('debug', output(row()), PIN)
        verify.check_signing('release', output(
            row('V3.0 Signer: (minSdkVersion=28, maxSdkVersion=32)'),
            row('V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)', PIN.upper())), PIN)
        verify.check_signing('release', output(row(
            'V3.1 Signer: (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)')), PIN)

    def test_extra_signer_is_rejected_even_when_pin_is_present(self):
        with self.assertRaises(SystemExit):
            verify.check_signing('release', output(row('V2 Signer #1:'),
                row('V2 Signer #2:', OTHER), count='2'), PIN)

    def test_summary_cannot_hide_wrong_missing_or_duplicate_signer_rows(self):
        bad = [output(row(pin=OTHER)), output(), output(row(), row()),
               output(row(), row('V2 Signer #1:')),
               output(row('V3.1 Signer: (minSdkVersion=33, maxSdkVersion=34)'),
                      row('V3.1 Signer #1: (minSdkVersion=033, maxSdkVersion=034)')),
               output(row('V2 Signer #2:')), output(row('Unknown Signer:')),
               output(row('V3.0 Signer: (minSdkVersion=28, maxSdkVersion=32)'),
                      row('V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)', OTHER)),
               output(row(pin=PIN[:-1])), output(row()).replace(' digest:', ':'),
               output(row()) + 'Number of signers: 1\n']
        for text in bad:
            with self.subTest(text=text), self.assertRaises(SystemExit):
                verify.check_signing('release', text, PIN)

    def test_source_stamp_cannot_stand_in_for_apk_identity(self):
        stamp = row('Source Stamp Signer:')
        for text in [output(stamp), output(row(pin=OTHER), stamp)]:
            with self.subTest(text=text), self.assertRaises(SystemExit):
                verify.check_signing('release', text, PIN)
        verify.check_signing('release', output(row(), row('Source Stamp Signer:', OTHER)), PIN)

    def test_verbose_summary_and_valid_pin_are_required(self):
        for text in [row(), output(row(), count='0'), output(row(), count='01'),
                     output(row(), count='2')]:
            with self.subTest(text=text), self.assertRaises(SystemExit):
                verify.check_signing('debug', text, PIN)
        with self.assertRaises(SystemExit):
            verify.check_signing('debug', output(row()), 'bad pin')


if __name__ == '__main__':
    unittest.main()
