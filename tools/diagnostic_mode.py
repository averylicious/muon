#!/usr/bin/env python3
"""Select the opt-in diagnostic (debuggable diagnostic app) build, failing closed on anything unexpected.

Only a manual Actions run on a branch other than main, with diagnostic_debug explicitly true, is a
diagnostic build. Its debug APK is debuggable for temporary device diagnosis; it never publishes and
its release APK stays non-debuggable. Every other run is a standard build. The workflow selects the
mode with this module before the SDK or signing keys are touched, and tools/verify-apks.py selects
it again from the same raw event, so a later step cannot change what the APKs are expected to be.
"""
import os
from pathlib import Path

# The workflow passes the raw input; push and tag events have no inputs, so it is empty there.
REQUEST = 'MUON_DIAGNOSTIC_REQUEST'
# What Gradle reads, only inside the debug build type (app/build.gradle.kts).
GRADLE = 'MUON_DIAGNOSTIC_DEBUG'
EVENTS = ('push', 'workflow_dispatch')


def select(event, ref_type, ref_name, requested):
    """Return True for an authorized diagnostic build, False for a standard one; raise otherwise."""
    if event not in EVENTS:
        raise ValueError(f'Unexpected workflow event: {event!r}')
    if ref_type not in ('branch', 'tag') or not ref_name:
        raise ValueError(f'Unexpected ref: {ref_type!r} {ref_name!r}')
    if event == 'push':
        # A push has no inputs; a value here means something other than the input set it.
        if requested != '':
            raise ValueError('diagnostic_debug is only accepted from a manual run')
        return False
    if requested not in ('true', 'false'):
        raise ValueError(f'diagnostic_debug must be true or false, not {requested!r}')
    if requested == 'false':
        return False
    if ref_type != 'branch' or ref_name == 'main':
        # Refuse rather than ignore: main and tags publish, and a published APK is never debuggable.
        raise ValueError('diagnostic_debug is only allowed on a branch other than main')
    return True


def from_env(env=os.environ):
    return select(env.get('GITHUB_EVENT_NAME', ''), env.get('GITHUB_REF_TYPE', ''),
                  env.get('GITHUB_REF_NAME', ''), env.get(REQUEST, ''))


def main(env=os.environ):
    diagnostic = from_env(env)
    value = 'true' if diagnostic else 'false'
    with Path(env['GITHUB_ENV']).open('a') as out:
        out.write(f'{GRADLE}={value}\n')
    with Path(env['GITHUB_OUTPUT']).open('a') as out:
        out.write(f'diagnostic={value}\n')
    if diagnostic and 'GITHUB_STEP_SUMMARY' in env:
        with Path(env['GITHUB_STEP_SUMMARY']).open('a') as out:
            out.write(
                '## Diagnostic build: debugging enabled\n\n'
                f'Requested manually for `{env["GITHUB_REF_NAME"]}`. The separate diagnostic APK in '
                f'`app-diagnostic-{env.get("GITHUB_SHA", "<commit>")}` is **debuggable**, labelled '
                '"Muon diag", with a `-diagnostic` version suffix, and Settings shows a red '
                '"Diagnostic build — debugging enabled" banner. It is for temporary testing only: '
                'performance is not representative. The release APK stays non-debuggable. Nothing '
                'is published to Canary or Stable. Package `dev.avery.muon.diagnostic` installs beside '
                'Canary and Stable, with separate settings and download indexes (see docs/ci.md).\n')
    print(f'Diagnostic build: {value}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError) as error:
        raise SystemExit(f'Diagnostic build selection failed: {error}')
