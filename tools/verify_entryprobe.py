#!/usr/bin/env python3
"""Fail closed on QA helper identity/permissions; only validated diagnostic branch runs may build it."""
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import diagnostic_mode

ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'dev.avery.muon.entryprobe'


def check_manifest(text, code):
    root = ET.fromstring(text)
    if root.get('package') != PACKAGE or root.get(ANDROID + 'sharedUserId') is not None:
        raise ValueError('Helper must use its separate package and UID')
    if root.get(ANDROID + 'versionCode') != code or root.get(ANDROID + 'versionName') != 'qa.' + code:
        raise ValueError('Unexpected helper build identity')
    if any(e.tag not in ('uses-sdk', 'queries', 'application') for e in root):
        raise ValueError('Helper may not request permissions or declare instrumentation')
    app = root.find('application')
    if app is None or app.get(ANDROID + 'debuggable') != 'true' or app.get(ANDROID + 'allowBackup') != 'false':
        raise ValueError('Helper must be explicitly diagnostic and excluded from backup')
    if app.get(ANDROID + 'usesCleartextTraffic') != 'false' or app.get(ANDROID + 'label') != 'Muon QA probe':
        raise ValueError('Unexpected helper network policy or label')
    if [e.tag for e in app] != ['activity']:
        raise ValueError('Helper must contain only its launcher activity')
    activity = app.find('activity')
    if activity.get(ANDROID + 'name') != PACKAGE + '.ProbeActivity' or activity.get(ANDROID + 'exported') != 'true':
        raise ValueError('Unexpected helper entry point')
    queries = root.findall('queries')
    if len(queries) != 1 or [e.tag for e in queries[0]] != ['package'] or queries[0][0].get(ANDROID + 'name') != 'dev.avery.muon':
        raise ValueError('Helper visibility must be limited to Canary')


def main(analyzer, apk, env=os.environ):
    if not diagnostic_mode.from_env(env) or env.get(diagnostic_mode.GRADLE) != 'true':
        raise ValueError('Helper is limited to validated diagnostic branch runs')
    text = subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)], encoding='utf-8')
    check_manifest(text, env['MUON_VERSION_CODE'])
    print('Disposable QA helper: separate package/UID declaration, no requested permissions, diagnostic identity verified')


if __name__ == '__main__':
    try:
        main(Path(sys.argv[1]), Path(sys.argv[2]))
    except (ValueError, ET.ParseError) as error:
        raise SystemExit(str(error))
