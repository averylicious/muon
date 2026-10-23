"""Fail closed before signing or publishing a Stable tag outside the main track."""
import os
import re
import subprocess
import sys

import check_branch_policy as branches


STABLE_TAG = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')


def check(ref_type, ref_name, head):
    if ref_type == 'branch':
        return None  # Branch APKs and main Canary publication keep their existing behavior.
    if ref_type != 'tag' or not STABLE_TAG.fullmatch(ref_name):
        raise ValueError('Stable tags must be vMAJOR.MINOR.PATCH.')
    if not re.fullmatch(r'[0-9a-f]{40}', head):
        raise ValueError('Expected the full workflow commit SHA.')
    if branches.git('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Complete fetched history is required for Stable ancestry checks.')
    tagged = branches.git('rev-parse', '--verify', 'refs/tags/' + ref_name + '^{commit}')
    if tagged != head:
        raise ValueError('Stable tag no longer resolves to the workflow commit.')
    main = branches.git('rev-parse', '--verify', 'refs/remotes/origin/main^{commit}')
    branches.git('cat-file', '-e', branches.EXPERIMENT_START + '^{commit}')
    if branches.ancestor(branches.EXPERIMENT_START, head):
        raise ValueError('Stable tag contains the alpha experiment.')
    if not branches.ancestor(head, main):
        raise ValueError('Stable tag must be a commit already included in fetched main.')
    return main


def main():
    try:
        destination = check(os.environ.get('GITHUB_REF_TYPE', ''),
                            os.environ.get('GITHUB_REF_NAME', ''),
                            os.environ.get('GITHUB_SHA', ''))
    except (ValueError, subprocess.CalledProcessError, OSError) as error:
        print(f'Release policy failed: {error}', file=sys.stderr)
        return 1
    if destination is None:
        print('Branch build: Stable tag policy does not apply.')
    else:
        print(f'Stable tag policy passed: workflow commit belongs to main at {destination}.')
        print('This is a fetched-graph safeguard, not release authorization or a source audit.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
