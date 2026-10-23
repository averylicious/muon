#!/usr/bin/env python3
"""Select the opt-in diagnostic (debuggable diagnostic app) build, failing closed on anything unexpected.

Only a manual Actions run on a branch other than main, with diagnostic_debug explicitly true, is a
diagnostic build. Its debug APK is debuggable for temporary device diagnosis; it never publishes and
its release APK stays non-debuggable. A separate explicit storage_timings request enables bounded
timing counters in non-debuggable Canary, without a package change; Release/benchmark stay off.
Both test modes are manual non-main branch-only and cannot publish. Default runs stay standard. The workflow selects the
mode with this module before the SDK or signing keys are touched, and tools/verify-apks.py selects
it again from the same raw event, so a later step cannot change what the APKs are expected to be.
"""
import os
from pathlib import Path

# The workflow passes the raw input; push and tag events have no inputs, so it is empty there.
REQUEST = 'MUON_DIAGNOSTIC_REQUEST'
# What Gradle reads, only inside the debug build type (app/build.gradle.kts).
GRADLE = 'MUON_DIAGNOSTIC_DEBUG'
TIMING_REQUEST = 'MUON_STORAGE_TIMING_REQUEST'
TIMING_GRADLE = 'MUON_STORAGE_TIMINGS'
EVENTS = ('push', 'workflow_dispatch')


def select(event, ref_type, ref_name, requested, input_name="diagnostic_debug"):
    """Validate one manual branch-only test-mode input; return its boolean selection or raise."""
    if event not in EVENTS:
        raise ValueError(f'Unexpected workflow event: {event!r}')
    if ref_type not in ('branch', 'tag') or not ref_name:
        raise ValueError(f'Unexpected ref: {ref_type!r} {ref_name!r}')
    if event == 'push':
        # A push has no inputs; a value here means something other than the input set it.
        if requested != '':
            raise ValueError(f'{input_name} is only accepted from a manual run')
        return False
    if requested not in ('true', 'false'):
        raise ValueError(f'{input_name} must be true or false, not {requested!r}')
    if requested == 'false':
        return False
    if ref_type != 'branch' or ref_name == 'main':
        # Refuse rather than ignore: main and tags publish, and a published APK is never debuggable.
        raise ValueError(f'{input_name} is only allowed on a branch other than main')
    return True


def from_env(env=os.environ):
    return select(env.get('GITHUB_EVENT_NAME', ''), env.get('GITHUB_REF_TYPE', ''),
                  env.get('GITHUB_REF_NAME', ''), env.get(REQUEST, ''))


def timing_from_env(env=os.environ):
    # Absent input remains off for older dispatch callers. Only explicit true can enable it.
    raw = env.get(TIMING_REQUEST, '')
    if env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and raw == '':
        raw = 'false'
    return select(env.get('GITHUB_EVENT_NAME', ''), env.get('GITHUB_REF_TYPE', ''),
                  env.get('GITHUB_REF_NAME', ''), raw, input_name='storage_timings')


def main(env=os.environ):
    diagnostic = from_env(env)
    timings = timing_from_env(env)
    timing_value = 'true' if timings else 'false'
    value = 'true' if diagnostic else 'false'
    with Path(env['GITHUB_ENV']).open('a') as out:
        out.write(f'{GRADLE}={value}\n{TIMING_GRADLE}={timing_value}\n')
    with Path(env['GITHUB_OUTPUT']).open('a') as out:
        out.write(f'diagnostic={value}\nstorage_timings={timing_value}\n')
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
    if timings and 'GITHUB_STEP_SUMMARY' in env:
        with Path(env['GITHUB_STEP_SUMMARY']).open('a') as out:
            out.write('## Storage timing test build\n\n'
                      'Requested explicitly for a non-publishing branch. Normal Canary stays '
                      'non-debuggable, retains its package/data and shows a Settings notice with a '
                      '`-timings` version suffix (unless separate diagnostic debugging was also '
                      'requested). Only bounded constant-label root/projection counters and durations '
                      'are added; no song/server/path/error text. These are diagnostic observations, '
                      'not an uninstrumented benchmark. Release/benchmark timing remains disabled.\n')
    print(f'Diagnostic build: {value}; storage timings: {timing_value}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError) as error:
        raise SystemExit(f'Diagnostic build selection failed: {error}')
