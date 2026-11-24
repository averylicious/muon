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
    timings = diagnostic_mode.timing_from_env(env)
    if env.get(diagnostic_mode.TIMING_GRADLE, 'false') != ('true' if timings else 'false'):
        raise ValueError(f'{diagnostic_mode.TIMING_GRADLE} does not match the selected build mode')
    canary = '-canary.' + env['MUON_VERSION_CODE']
    return [
        ('debug', 'dev.avery.muon.diagnostic' if diagnostic else 'dev.avery.muon',
         'Muon diag' if diagnostic else 'Muon β',
         canary + '-diagnostic' if diagnostic else canary + '-timings' if timings else canary, diagnostic),
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



def check_signing(variant, signing, expected):
    """Pin every applicable scheme signer; source stamps cannot satisfy the app identity.

    Build Tools 37.0.0 prints the count only with --verbose. Single signers are unnumbered;
    v3 scheme records may carry SDK ranges. Rotation/new signature formats need explicit review.
    """
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected):
        raise SystemExit(f'{variant}: invalid pinned signing certificate')
    counts = [line for line in signing.splitlines() if line.startswith('Number of signers:')]
    if counts != ['Number of signers: 1']:
        raise SystemExit(f'{variant}: expected exactly one APK signer')
    labels = set()
    for line in signing.splitlines():
        if 'certificate SHA-256' not in line:
            continue
        # A source stamp authenticates distribution, not the app's update identity.
        if line.startswith('Source Stamp Signer: certificate SHA-256 digest: '):
            continue
        match = re.fullmatch(
            r'(V[12]|V3\.[012](?: Hybrid (?:Classical|PQC))?) Signer(?: #1)?:'
            r'(?: \(minSdkVersion=([0-9]+)( \(dev release=true\))?, maxSdkVersion=([0-9]+)\))?'
            r' certificate SHA-256 digest: ([0-9a-fA-F]{64})', line)
        if not match or match[5].lower() != expected.lower():
            raise SystemExit(f'{variant}: unexpected signing certificate or signer record')
        # Unnumbered and #1 mean the same signer. Normalize ranges too, rather than treating
        # alternate renderings as separate certificates. Distinct applicable SDK ranges may exist.
        identity = (match[1], int(match[2]) if match[2] else None,
                    bool(match[3]), int(match[4]) if match[4] else None)
        if identity in labels:
            raise SystemExit(f'{variant}: duplicate APK signer record')
        labels.add(identity)
    if not labels:
        raise SystemExit(f'{variant}: missing APK signer certificate')


def main(build_tools, env=os.environ):
    fingerprints = Path('docs/signing-certificates.txt').read_text().splitlines()
    for variant, package, label, suffix, debuggable in expectations(env):
        apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
        cert = next(line.split(': ', 1)[1] for line in fingerprints if line.lower().startswith(variant))
        signing = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
        check_signing(variant, signing, cert)
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
