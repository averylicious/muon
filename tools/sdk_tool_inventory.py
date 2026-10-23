"""Public SDK verifier identity only; no execution, signing material or trust certification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import zipfile

MAX_FILE = 64 * 1024 * 1024
MAX_ENTRIES = 50000
PROVIDERS = (
    'org/bouncycastle/jce/provider/BouncyCastleProvider.class',
    'org/conscrypt/OpenSSLProvider.class',
)


def public_file(root, relative):
    path = root / relative
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE:
        raise ValueError(f'Invalid public SDK file: {relative}')
    # Fixed relative paths only, including intermediate directories; do not follow a lib symlink.
    if path.resolve().parent != (root.resolve() / Path(relative).parent):
        raise ValueError(f'Escaping SDK path: {relative}')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return path, {'path': relative, 'bytes': info.st_size, 'sha256': digest.hexdigest()}


def inventory(root, commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Commit must be a full lowercase SHA')
    root = Path(root)
    records = []
    for name in ('apksigner', 'lib/apksigner.jar', 'source.properties'):
        path, record = public_file(root, name)
        if name.endswith('.jar'):
            with zipfile.ZipFile(path) as archive:
                entries = archive.infolist()
                if len(entries) > MAX_ENTRIES:
                    raise ValueError('Too many SDK JAR entries')
                names = {entry.filename for entry in entries}
                record['provider_classes_present'] = {p: p in names for p in PROVIDERS}
                record['apksig_class_present'] = 'com/android/apksig/ApkVerifier.class' in names
                record['class_entries'] = sum(e.filename.endswith('.class') for e in entries)
        records.append(record)
    return {'schema': 1, 'commit': commit, 'files': records,
            'scope': 'SDK verifier public files; hashes are observations, not trusted allowlists; '
                     'class presence does not establish provider version, registration or use'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build_tools')
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    try:
        print('MUON_SDK_TOOL_INVENTORY=' + json.dumps(inventory(args.build_tools, args.commit), sort_keys=True))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f'SDK inventory failed: {error}\n')


if __name__ == '__main__':
    main()
