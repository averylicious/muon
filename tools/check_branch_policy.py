"""Read-only PR preflight for the main audit and the isolated Expressive experiment."""
import argparse
import re
import subprocess


EXPERIMENT = 'claude/m3-expressive-alpha'
EXPERIMENT_REF = 'refs/remotes/origin/' + EXPERIMENT
# First alpha/AGP-upgrade commit. Its parent f0909f0 is on main (verified 2026-09-28).
# An immutable anchor also catches renamed branches.
EXPERIMENT_START = '454350a058fc4e272731afd0fff23bd9cd22e0f3'
# The one approved promotion of the experiment into main (user UAT passed 2026-10-24): this exact
# accepted commit, merged (never squashed or rebased) from this source branch, with its tree
# unchanged outside docs/ and the policy files below, which must be main's own.
PROMOTION_SOURCE = 'codex/promote-m3e-rc-oct24'
PROMOTION_COMMIT = '6b1a767c180a8b9e531bf1468c9e72e348635b74'
POLICY_FILES = ('tools/check_branch_policy.py', 'tools/check_release_policy.py',
                'tools/test_ci_branch_policy.py', 'tools/test_ci_release_policy.py')


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, text=True).strip()


def ancestor(older, newer):
    result = subprocess.run(['git', 'merge-base', '--is-ancestor', older, newer],
                            stderr=subprocess.PIPE, text=True)
    if result.returncode not in (0, 1):
        raise ValueError('Ancestry unavailable; fetch complete branch history before retrying.')
    return result.returncode == 0


def promoted(commit):
    """Whether this commit contains the approved promotion. A missing accepted commit is not one."""
    present = subprocess.run(['git', 'cat-file', '-e', PROMOTION_COMMIT + '^{commit}'],
                             stderr=subprocess.PIPE, text=True).returncode == 0
    return present and ancestor(PROMOTION_COMMIT, commit)


def changed(older, newer, *pathspecs):
    return git('diff', '--no-renames', '--name-only', older, newer, '--', *pathspecs)


def imports_experiment(head, *allowed):
    """Whether head shares experiment commits that none of [allowed] already contain.

    Every commit the two histories share lies under one of their merge bases, so it is enough that
    each merge base is already in an allowed commit. A missing experiment ref fails closed.
    """
    experiment = git('rev-parse', '--verify', EXPERIMENT_REF + '^{commit}')
    result = subprocess.run(['git', 'merge-base', '--all', head, experiment],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode not in (0, 1):
        raise ValueError('Experiment history unavailable; fetch complete branch history before retrying.')
    return any(not any(ancestor(shared, commit) for commit in allowed)
               for shared in result.stdout.split())


def check_promotion(head, main_sha):
    if not promoted(head):
        raise ValueError('The promotion must merge the accepted experiment commit itself, '
                         'not a squash, rebase or other copy.')
    if changed(PROMOTION_COMMIT, head, ':/', ':(top,exclude)docs',
               *(':(top,exclude)' + name for name in POLICY_FILES)):
        raise ValueError('The promotion changes the accepted code outside docs/; '
                         'the accepted commit must be reviewed and accepted again.')
    if changed(main_sha, head, *(':(top)' + name for name in POLICY_FILES)):
        raise ValueError("The promotion must keep main's branch and release policy files exactly.")
    if imports_experiment(head, PROMOTION_COMMIT, main_sha):
        raise ValueError('The promotion contains experiment commits beyond the accepted commit.')


def check(base, head, source):
    if base not in ('main', EXPERIMENT):
        raise ValueError('Choose main or the Expressive experiment as the PR destination.')
    if not re.fullmatch(r'[0-9a-f]{40}', head):
        raise ValueError('Head must be the full commit SHA that CI will verify.')
    git('cat-file', '-e', head + '^{commit}')
    base_sha = git('rev-parse', '--verify', 'refs/remotes/origin/' + base + '^{commit}')
    if base == 'main':
        if source == EXPERIMENT or source.startswith('sync/main-into-expressive-'):
            raise ValueError('Experimental work and forward-sync branches must not target main.')
        git('cat-file', '-e', EXPERIMENT_START + '^{commit}')
        if ancestor(EXPERIMENT_START, head):
            if ancestor(EXPERIMENT_START, base_sha):
                # After the promotion, every main head contains the alpha. Main itself must have
                # it only through the approved promotion, and a head may not bring in more.
                if not promoted(base_sha):
                    raise ValueError('Main contains the alpha experiment without its approved '
                                     'promotion; repair main before merging anything else.')
                if imports_experiment(head, base_sha):
                    raise ValueError('This head contains experiment commits that are not on main; '
                                     'do not merge them into main.')
            elif source == PROMOTION_SOURCE:
                check_promotion(head, base_sha)
            else:
                raise ValueError('This head contains the alpha experiment; do not merge it into main.')
    if not ancestor(base_sha, head):
        raise ValueError('Destination advanced or is absent from this head. Merge it into your '
                         'isolated branch, review the combination, push and rerun CI.')
    if source.startswith('sync/main-into-expressive-'):
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
