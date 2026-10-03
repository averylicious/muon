"""Check the reviewed wrapper JAR before any Gradle User Home cache restoration."""
import hashlib
from pathlib import Path
import sys


# Official Gradle 8.13 wrapper checksum, independently compared on 2026-10-03:
# https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256
# Review and update this pin when intentionally replacing the wrapper; never read a cache allowlist.
APPROVED_WRAPPER_SHA256 = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
ROOT = Path(__file__).resolve().parents[1]


def verify_wrapper(path, expected=APPROVED_WRAPPER_SHA256):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Gradle wrapper must be a regular, non-symlink file')
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError('Gradle wrapper checksum differs from the reviewed pin')


def main():
    try:
        verify_wrapper(ROOT / 'gradle/wrapper/gradle-wrapper.jar')
    except (OSError, ValueError) as error:
        print(f'Wrapper verification failed: {error}', file=sys.stderr)
        return 1
    print('Gradle wrapper matches the reviewed checksum (no cache allowlist used).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
