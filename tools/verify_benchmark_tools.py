"""Verify the two optional tool APKs share the pinned debug identity and declared build mode."""
from pathlib import Path
import os
import runpy
import subprocess
import sys
import xml.etree.ElementTree as ET

from apk_manifest import verify_manifest

ANDROID = '{http://schemas.android.com/apk/res/android}'
TARGET = 'dev.avery.muon.benchmark'
HELPER = 'dev.avery.muon.baselineprofile'


def verify_tool_manifest(xml, helper, measurement):
    root = ET.fromstring(xml)
    package = HELPER if helper else TARGET
    if root.tag != 'manifest' or root.get('package') != package:
        raise ValueError('Unexpected tool package')
    applications = root.findall('application')
    if len(applications) != 1:
        raise ValueError('Expected one tool application')
    app = applications[0]
    debug = app.get(ANDROID + 'debuggable', 'false')
    if debug not in {'true', 'false'} or (debug == 'true') != helper:
        raise ValueError('Wrong tool debuggable flag')
    if helper:
        instrumentation = root.findall('instrumentation')
        if (len(instrumentation) != 1 or instrumentation[0].get(ANDROID + 'targetPackage') != HELPER
                or instrumentation[0].get(ANDROID + 'name') != 'androidx.test.runner.AndroidJUnitRunner'):
            raise ValueError('Helper must instrument itself, never Stable or Canary')
    else:
        verify_manifest(xml, TARGET)
        profiles = app.findall('profileable')
        if len(profiles) != 1 or profiles[0].get(ANDROID + 'shell') != 'true' or profiles[0].get(ANDROID + 'enabled', 'true') != 'true':
            raise ValueError('Benchmark target must be profileable by shell')
        modes = [x.get(ANDROID + 'value') for x in app.findall('meta-data')
                 if x.get(ANDROID + 'name') == 'dev.avery.muon.benchmark.mode']
        if modes != ['measurement' if measurement else 'recording']:
            raise ValueError('Target mode does not match the selected build')


def verify_pair(folder, build_tools, analyzer, pin, measurement, run=subprocess.check_output):
    if {x.name for x in folder.glob('*.apk')} != {'app-benchmark.apk', 'baselineprofile-benchmark.apk'}:
        raise ValueError('Expected exactly the two collected tool APKs')
    check_signing = runpy.run_path(str(Path(__file__).with_name('verify-apks.py')))['check_signing']
    for helper, name in ((False, 'app-benchmark.apk'), (True, 'baselineprofile-benchmark.apk')):
        apk = folder / name
        signing = run([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], encoding='utf-8')
        check_signing(name, signing, pin)
        xml = run([str(analyzer), 'manifest', 'print', str(apk)], encoding='utf-8')
        verify_tool_manifest(xml, helper, measurement)
    print('Both tools: pinned debug signer, package, instrumentation and selected mode verified.')


def main():
    if len(sys.argv) != 2:
        raise ValueError('Usage: verify_benchmark_tools.py <collected APK directory>')
    raw = os.environ.get('MUON_BENCHMARK_MINIFIED', 'false')
    if raw not in {'true', 'false'}:
        raise ValueError('MUON_BENCHMARK_MINIFIED must be true or false')
    lines = Path('docs/signing-certificates.txt').read_text().splitlines()
    pins = [x.split(': ', 1)[1] for x in lines if x.startswith('Debug certificate SHA-256: ')]
    if len(pins) != 1:
        raise ValueError('Expected one pinned debug certificate')
    sdk = Path(os.environ['ANDROID_HOME'])
    verify_pair(Path(sys.argv[1]), sdk / 'build-tools/37.0.0',
                sdk / 'cmdline-tools/latest/bin/apkanalyzer', pins[0], raw == 'true')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
