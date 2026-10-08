#!/usr/bin/env python3
"""Reject CI APKs with the wrong package, version, signing identity or debuggable flag."""
import os
from pathlib import Path
import re
import subprocess
import sys

import diagnostic_mode
from apk_manifest import verify_manifest


def expectations(env=os.environ):
    """The identity of each APK for this run. Only a diagnostic Canary is debuggable, and it must be."""
    # The same selection the workflow made before signing, from the same raw event; the Gradle
    # setting must agree with it, so neither a later step nor a stray variable changes the result.
    diagnostic = diagnostic_mode.from_env(env)
    if env.get(diagnostic_mode.GRADLE) != ('true' if diagnostic else 'false'):
        raise ValueError(f'{diagnostic_mode.GRADLE} does not match the selected build mode')
    canary = '-canary.' + env['MUON_VERSION_CODE']
    return [
        ('debug', 'dev.avery.muon.diagnostic' if diagnostic else 'dev.avery.muon',
         'Muon diag' if diagnostic else 'Muon β',
         canary + '-diagnostic' if diagnostic else canary, diagnostic),
        ('release', 'dev.avery.muon.release', 'Muon', '', False),
    ]


def check_badging(variant, badging, package, label, version_code, version_name, debuggable):
    lines = badging.splitlines()
    for field, value in [('name', package), ('versionCode', version_code), ('versionName', version_name)]:
        if not re.search(rf"\b{field}='{re.escape(value)}'", lines[0]):
            raise SystemExit(f'{variant}: unexpected {field}')
    if f"application-label:'{label}'" not in badging:
        raise SystemExit(f'{variant}: unexpected launcher label')
    # A debuggable Canary misrepresented performance in QA (#125): only a diagnostic Canary is.
    if ('application-debuggable' in badging) != debuggable:
        raise SystemExit(f'{variant}: APK must {"" if debuggable else "not "}be debuggable')


def main(build_tools, env=os.environ):
    fingerprints = Path('docs/signing-certificates.txt').read_text().splitlines()
    for variant, package, label, suffix, debuggable in expectations(env):
        apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
        cert = next(line.split(': ', 1)[1] for line in fingerprints if line.lower().startswith(variant))
        signing = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        if f'certificate SHA-256 digest: {cert}' not in signing:
            raise SystemExit(f'{variant}: unexpected signing certificate')
        # UTF-8 explicitly: Canary's label, "Muon β", is not ASCII, whatever the runner's locale.
        badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], encoding='utf-8')
        check_badging(variant, badging, package, label, env['MUON_VERSION_CODE'],
                      env['MUON_VERSION_NAME'] + suffix, debuggable)
        # Preserve the acceptance branch's compiled export gate in both build modes.
        analyzer = Path(env['ANDROID_HOME']) / 'cmdline-tools/latest/bin/apkanalyzer'
        manifest = subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)], encoding='utf-8')
        verify_manifest(manifest, package)
        print(f'{variant}: package, version, label, signing certificate, exported components and '
              f'{"debuggable (diagnostic)" if debuggable else "non-debuggable"} flag verified')


if __name__ == '__main__':
    try:
        main(Path(sys.argv[1]))
    except ValueError as error:
        raise SystemExit(f'Diagnostic build selection failed: {error}')
