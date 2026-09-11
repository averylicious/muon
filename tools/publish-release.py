#!/usr/bin/env python3
"""Publish a verified Actions artifact atomically through a draft GitHub Release."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def publish():
    channel = os.environ['CHANNEL']
    version = os.environ['VERSION']
    run = os.environ['GITHUB_RUN_NUMBER']
    repo = os.environ['GITHUB_REPOSITORY']
    sha = os.environ['GITHUB_SHA']
    if channel not in ('canary', 'stable') or not re.fullmatch(r'\d+\.\d+\.\d+', version) or not run.isdigit():
        raise SystemExit('Invalid publication metadata')
    canary = channel == 'canary'
    tag = f'{version}-canary.{run}' if canary else f'v{version}'
    display_version = f'{version}-canary.{run}' if canary else version
    title = f'Muon {"Canary" if canary else "Stable"} {display_version}'
    source = Path('release-input')
    variant = 'debug' if canary else 'release'
    apk = source / f'app-{variant}.apk'
    checksum = (source / 'SHA256SUMS').read_text().split()[0]
    if hashlib.sha256(apk.read_bytes()).hexdigest() != checksum:
        raise SystemExit('Downloaded artifact checksum mismatch')
    if any(line not in (source / 'BUILD.txt').read_text().splitlines() for line in
           [f'Commit: {sha}', f'Run: {run}', f'Version: {display_version}']):
        raise SystemExit('Artifact does not belong to this commit/run')
    output = Path('release-output')
    output.mkdir(exist_ok=True)
    name = f'muon-{channel}-{display_version}.apk'
    shutil.copyfile(apk, output / name)
    (output / 'SHA256SUMS').write_text(f'{checksum}  {name}\n')
    shutil.copyfile(source / 'BUILD.txt', output / 'BUILD.txt')
    package = 'dev.avery.muon' if canary else 'dev.avery.muon.release'
    notes = Path('release-notes.md')
    notes.write_text(
        f'{title}\n\nPackage: `{package}`. Android 9 or newer. Version code: {run}.\n\n'
        + ('Development build; may contain regressions. Updates the original Muon debug app.\n\n' if canary else
           'Stable channel; installs alongside Muon Canary with separate settings.\n\n')
        + f'Commit: `{sha}`\nBuild and checks: https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}\n\n'
        + 'Download the APK directly or follow the [Obtainium setup guide]'
        + f'(https://github.com/{repo}/blob/{sha}/docs/obtainium.md). '
        + 'This is a private repository; an authorized GitHub token is needed for Obtainium.\n\n'
        + 'Tauon remains trusted-LAN-only. No server exposure or Internet streaming is configured.\n'
    )
    # A failed listing must fail publication rather than being mistaken for "not found".
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    existing = next((r for page in pages for r in page if r['tag_name'] == tag), None)
    if existing and not existing['draft']:
        print(f'Release already published; leaving it unchanged: {existing["html_url"]}')
        return
    if existing and existing['target_commitish'] != sha:
        raise SystemExit('Existing draft targets another commit; refusing to overwrite')
    if not existing:
        args = ['release', 'create', tag, '--repo', repo, '--target', sha, '--title', title,
                '--notes-file', str(notes), '--draft']
        if canary:
            args.append('--prerelease')
        else:
            args.append('--verify-tag')
        gh(*args)
    gh('release', 'upload', tag, '--repo', repo, '--clobber',
       str(output / name), str(output / 'SHA256SUMS'), str(output / 'BUILD.txt'))
    gh('release', 'edit', tag, '--repo', repo, '--title', title, '--notes-file', str(notes),
       '--draft=false', f'--prerelease={str(canary).lower()}', f'--latest={str(not canary).lower()}')
    print(gh('release', 'view', tag, '--repo', repo, '--json', 'url', '--jq', '.url'))


if __name__ == '__main__':
    publish()
