#!/usr/bin/env python3
"""Build using a local signing backup, without placing passwords on the command line."""
import argparse
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--signing-directory', type=Path, default=root / '.local/signing',
                    help='Directory containing muon-release.p12 and credentials.json; an extracted backup also works')
parser.add_argument('tasks', nargs='*', default=[':app:assembleDebug', ':app:assembleRelease'])
args = parser.parse_args()
signing = args.signing_directory.resolve()
try:
    credentials = json.loads((signing / 'credentials.json').read_text())
    release = signing / 'muon-release.p12'
    debug = signing / 'debug.keystore'
    if not debug.exists():
        debug = root / '.local/debug.keystore'
    if not debug.is_file() or not release.is_file():
        raise ValueError('Both debug and release keystores are required')
    if credentials['release']['alias'] != 'muon-release':
        raise ValueError('Unexpected release alias')
    if credentials['release']['store_password'] != credentials['release']['key_password']:
        raise ValueError('This PKCS12 configuration expects one password for store and key')
except (OSError, ValueError, KeyError) as exc:
    parser.exit(2, f'Cannot load signing backup: {exc}\n')
env = os.environ.copy()
env.update(MUON_DEBUG_KEYSTORE=str(debug), MUON_RELEASE_KEYSTORE=str(release),
           MUON_RELEASE_PASSWORD=credentials['release']['store_password'])
raise SystemExit(subprocess.run([str(root / 'gradlew'), '--no-daemon', *args.tasks], cwd=root, env=env).returncode)
