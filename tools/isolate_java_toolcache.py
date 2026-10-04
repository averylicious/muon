"""Keep the runner's preinstalled Temurin cache out of setup-java's download path.

Only used on disposable GitHub-hosted Linux jobs. Move public JDK files aside;
never delete the runner's other tools or modify the user's local installation.
"""
import os
from pathlib import Path
import shutil
import sys


TEMURIN_CACHE = 'Java_Temurin-Hotspot_jdk'


def isolate_temurin_cache(tool_cache: Path, destination: Path) -> bool:
    source = tool_cache / TEMURIN_CACHE
    if source.is_symlink() or tool_cache.is_symlink():
        raise ValueError('Refusing a symlinked Java tool-cache root')
    if destination.exists() or destination.is_symlink():
        raise ValueError('Java quarantine destination must be unused')
    if destination.resolve().is_relative_to(tool_cache.resolve()):
        raise ValueError('Java quarantine must be outside the tool-cache root')
    if not source.exists():
        return False
    if not source.is_dir():
        raise ValueError('Expected a Java tool-cache directory')
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Handles separate runner filesystems while preserving the existing public files.
    # Any failure is propagated; a partial move never permits continuing to setup/signing.
    shutil.move(str(source), str(destination))
    if source.exists() or source.is_symlink():
        raise ValueError('Java cache isolation did not complete')
    return True


def main():
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or sys.platform != 'linux'):
        raise ValueError('Java cache isolation is only for disposable hosted Linux jobs')
    if len(sys.argv) != 3 or not all(Path(arg).is_absolute() for arg in sys.argv[1:]):
        raise ValueError('Expected absolute runner tool-cache and quarantine paths')
    moved = isolate_temurin_cache(Path(sys.argv[1]), Path(sys.argv[2]))
    print('Preinstalled Temurin cache preserved outside lookup' if moved else 'No preinstalled Temurin cache')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError):
        print('Java cache isolation failed; build must stop', file=sys.stderr)
        sys.exit(1)
