#!/usr/bin/env python3
"""Reject CI APKs with the wrong package, version or signing identity."""
import os
from pathlib import Path
import re
import subprocess
import sys

from apk_manifest import verify_manifest

build_tools = Path(sys.argv[1])
fingerprints = Path('docs/signing-certificates.txt').read_text().splitlines()
for variant, package, label, suffix in [
    ('debug', 'dev.avery.muon', 'Muon β', '-canary.' + os.environ['MUON_VERSION_CODE']),
    ('release', 'dev.avery.muon.release', 'Muon', ''),
]:
    apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
    cert = next(line.split(': ', 1)[1] for line in fingerprints if line.lower().startswith(variant))
    signing = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    if f'certificate SHA-256 digest: {cert}' not in signing:
        raise SystemExit(f'{variant}: unexpected signing certificate')
    # UTF-8 explicitly: Canary's label, "Muon β", is not ASCII, whatever the runner's locale.
    badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], encoding='utf-8')
    header = badging.splitlines()[0]
    expected_version = os.environ['MUON_VERSION_NAME'] + suffix
    for field, value in [('name', package), ('versionCode', os.environ['MUON_VERSION_CODE']), ('versionName', expected_version)]:
        if not re.search(rf"\b{field}='{re.escape(value)}'", header):
            raise SystemExit(f'{variant}: unexpected {field}')
    if f"application-label:'{label}'" not in badging:
        raise SystemExit(f'{variant}: unexpected launcher label')
    # Neither channel is debuggable: a debuggable Canary misrepresented performance in QA (#125).
    if 'application-debuggable' in badging:
        raise SystemExit(f'{variant}: APK must not be debuggable')
    # Inspect the APK after manifest merging and release shrinking, including library components.
    analyzer = Path(os.environ['ANDROID_HOME']) / 'cmdline-tools/latest/bin/apkanalyzer'
    manifest = subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)], encoding='utf-8')
    verify_manifest(manifest, package)
    print(f'{variant}: package, version, label, signing certificate and exported components verified')
