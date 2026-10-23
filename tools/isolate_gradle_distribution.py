"""Require a fresh wrapper distribution on disposable hosted Linux CI jobs.

Preserve restored public distributions in runner.temp; keep dependency caches.
The properties hash binds the wrapper's checksum and install paths to our review.
This does not authenticate plugins, dependencies or arbitrary code in the job.
"""
import hashlib
import os
from pathlib import Path
import shutil
import sys


PROPERTIES_SHA256 = '1bb6d43ff8af30abb0eaf03fe58f636ed9b37f758a748bf30a9cebd809639c9d'
QUARANTINE = 'muon-restored-gradle-distributions'


def verify_properties(path: Path, expected: str = PROPERTIES_SHA256) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError('Expected reviewed wrapper properties file')
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError('Wrapper install paths/checksum changed; refresh source review')


def isolate_distribution(gradle_home: Path, runner_home: Path, runner_temp: Path) -> bool:
    for path in (gradle_home, runner_home, runner_temp):
        if not path.is_absolute() or path.resolve() != path or path.is_symlink():
            raise ValueError('Expected canonical absolute runner paths')
    if runner_home == Path('/') or (gradle_home.exists() and not gradle_home.is_dir()):
        raise ValueError('Expected a runner home and Gradle directory')
    if gradle_home != runner_home / '.gradle':
        raise ValueError('Only the reviewed default Gradle User Home is supported')
    if not runner_home.is_dir() or not runner_temp.is_dir():
        raise ValueError('Expected existing disposable runner directories')
    if runner_temp.is_relative_to(gradle_home):
        raise ValueError('Quarantine must be outside Gradle User Home')
    source = gradle_home / 'wrapper' / 'dists'
    if source.parent.is_symlink() or source.is_symlink():
        raise ValueError('Refusing symlinked wrapper distribution paths')
    destination = runner_temp / QUARANTINE
    if destination.exists() or destination.is_symlink():
        raise ValueError('Distribution quarantine must be unused')
    if not source.exists():
        return False
    if not source.is_dir():
        raise ValueError('Expected a wrapper distribution directory')
    # Handles separate filesystems, preserving bytes. Any failed or partial move
    # must stop the build, even if action cache-exclusion cleanup only warned.
    shutil.move(str(source), str(destination))
    if source.exists() or source.is_symlink():
        raise ValueError('Restored distribution isolation did not complete')
    return True


def main():
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or sys.platform != 'linux'):
        raise ValueError('Distribution isolation is only for disposable hosted Linux jobs')
    if len(sys.argv) != 4:
        raise ValueError('Expected Gradle User Home, runner home and runner temp')
    verify_properties(Path(__file__).resolve().parents[1] / 'gradle/wrapper/gradle-wrapper.properties')
    moved = isolate_distribution(*(Path(arg) for arg in sys.argv[1:]))
    print('Restored Gradle distributions preserved outside wrapper lookup' if moved
          else 'No restored Gradle distributions in wrapper lookup')
    print('Fresh checksum-verified wrapper installation required before Gradle execution')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, shutil.Error):
        print('Gradle distribution isolation failed; build must stop', file=sys.stderr)
        sys.exit(1)
