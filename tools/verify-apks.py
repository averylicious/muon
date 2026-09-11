#!/usr/bin/env python3
"""Reject CI APKs with the wrong package, version or signing identity."""
import os
from pathlib import Path
import re
import subprocess
import sys

build_tools = Path(sys.argv[1])
fingerprints = Path('docs/signing-certificates.txt').read_text().splitlines()
for variant, package, label, suffix in [
    ('debug', 'dev.avery.muon', 'Muon Canary', '-canary.' + os.environ['MUON_VERSION_CODE']),
    ('release', 'dev.avery.muon.release', 'Muon', ''),
]:
    apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
    cert = next(line.split(': ', 1)[1] for line in fingerprints if line.lower().startswith(variant))
    signing = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    if f'certificate SHA-256 digest: {cert}' not in signing:
        raise SystemExit(f'{variant}: unexpected signing certificate')
    badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    header = badging.splitlines()[0]
    expected_version = os.environ['MUON_VERSION_NAME'] + suffix
    for field, value in [('name', package), ('versionCode', os.environ['MUON_VERSION_CODE']), ('versionName', expected_version)]:
        if not re.search(rf"\b{field}='{re.escape(value)}'", header):
            raise SystemExit(f'{variant}: unexpected {field}')
    if f"application-label:'{label}'" not in badging:
        raise SystemExit(f'{variant}: unexpected launcher label')
    if variant == 'release' and 'application-debuggable' in badging:
        raise SystemExit('Stable APK must not be debuggable')
    print(f'{variant}: package, version, label and signing certificate verified')
