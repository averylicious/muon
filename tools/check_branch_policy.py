"""Read-only PR preflight for main and active parallel design/experiment tracks."""
import argparse
import re
import subprocess


EXPERIMENT = 'gen-4-progressive-blur'
EXPERIMENTS = ('claude/m3-expressive-alpha', EXPERIMENT)


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()


def ancestor(older, newer):
    result = subprocess.run(['git', 'merge-base', '--is-ancestor', older, newer],
                            stderr=subprocess.PIPE, text=True)
    if result.returncode not in (0, 1):
        raise ValueError('Ancestry unavailable; fetch complete branch history before retrying.')
    return result.returncode == 0


def check(base, head, source):
    if base not in ('main', *EXPERIMENTS):
        raise ValueError('Choose main or an active experiment branch as the PR destination.')
    if not re.fullmatch(r'[0-9a-f]{40}', head):
        raise ValueError('Head must be the full commit SHA that CI will verify.')
    git('cat-file', '-e', head + '^{commit}')
    base_sha = git('rev-parse', '--verify', 'refs/remotes/origin/' + base + '^{commit}')
    if base == 'main':
        if source == EXPERIMENT or source.startswith('sync/main-into-'):
            raise ValueError('Experimental work and forward-sync branches must not target main.')
    if not ancestor(base_sha, head):
        raise ValueError('Destination advanced or is absent from this head. Merge it into your '
                         'isolated branch, review the combination, push and rerun CI.')
    if base in EXPERIMENTS and source.startswith('sync/main-into-'):
        main_sha = git('rev-parse', '--verify', 'refs/remotes/origin/main^{commit}')
        if not ancestor(main_sha, head):
            raise ValueError('Forward sync must include current main before it is tested.')
    return base_sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--source', required=True)
    args = parser.parse_args()
    try:
        base_sha = check(args.base, args.head, args.source)
    except (ValueError, subprocess.CalledProcessError, OSError) as error:
        parser.exit(1, f'Branch policy failed: {error}\n')
    print(f'Branch policy passed: {args.head} includes {args.base} at {base_sha}.')
    print('Recheck destination and PR head before merging; this is a snapshot, not a lock.')


if __name__ == '__main__':
    main()
