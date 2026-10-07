"""Public, bounded observations of an isolated Robolectric Maven runtime repository.

Same-repository SHA512 agreement is integrity evidence, not publisher authentication.
Only the JSON receipt is uploaded; never the repository, jars, or host paths.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat

REPOSITORY = "https://repo1.maven.org/maven2"
MAX_FILES = 64
MAX_FILE_BYTES = 768 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,119}\Z")
ARTIFACTS = {"android-all", "android-all-instrumented"}


def inventory(root: Path, commit: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("invalid commit")
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError("repository must be a real directory")
    records = []
    total = 0
    # Never traverse symlink directories or serialize unknown files/paths.
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs:
            p = Path(directory) / name
            if not stat.S_ISDIR(p.lstat().st_mode):
                raise ValueError("repository contains a non-directory")
            rel = p.relative_to(root).parts
            valid = (rel == ("org",) or rel == ("org", "robolectric") or
                     len(rel) == 3 and rel[:2] == ("org", "robolectric") and rel[2] in ARTIFACTS or
                     len(rel) == 4 and rel[:2] == ("org", "robolectric") and rel[2] in ARTIFACTS and VERSION.fullmatch(rel[3]))
            if not valid:
                raise ValueError("unexpected repository directory")
        for name in files:
            p = Path(directory) / name
            rel = p.relative_to(root).parts
            if len(rel) != 5 or rel[:2] != ("org", "robolectric") or rel[2] not in ARTIFACTS or not VERSION.fullmatch(rel[3]):
                raise ValueError("unexpected repository file")
            artifact, version = rel[2:4]
            prefix = f"{artifact}-{version}"
            if name not in {prefix + suffix for suffix in (".jar", ".pom", ".jar.sha512", ".pom.sha512")}:
                raise ValueError("unexpected artifact filename")
            size = p.lstat().st_size
            if not stat.S_ISREG(p.lstat().st_mode) or not 0 < size <= MAX_FILE_BYTES:
                raise ValueError("invalid artifact file")
            total += size
            if total > MAX_TOTAL_BYTES or len(records) >= MAX_FILES:
                raise ValueError("runtime repository exceeds inventory budget")
            sha256, sha512 = hashlib.sha256(), hashlib.sha512()
            with p.open("rb") as f:
                read = 0
                for block in iter(lambda: f.read(1024 * 1024), b""):
                    read += len(block)
                    if read > size:
                        raise ValueError("artifact changed during inventory")
                    sha256.update(block)
                    sha512.update(block)
            if read != size:
                raise ValueError("artifact changed during inventory")
            records.append({"coordinate": f"org.robolectric:{artifact}:{version}", "file": name,
                            "bytes": size, "sha256": sha256.hexdigest(), "sha512": sha512.hexdigest()})
    if not records:
        raise ValueError("no Robolectric runtime artifacts observed")
    by_file = {(r["coordinate"], r["file"]): r for r in records}
    for r in records:
        if r["file"].endswith((".jar", ".pom")):
            checksum_name = r["file"] + ".sha512"
            checksum = by_file.get((r["coordinate"], checksum_name))
            if not checksum or checksum["bytes"] > 130:
                raise ValueError("missing or invalid SHA512 companion")
            _, artifact, version = r["coordinate"].split(":")
            checksum_path = root / "org" / "robolectric" / artifact / version / checksum_name
            expected = checksum_path.read_text(encoding="ascii").strip()
            if not re.fullmatch(r"[0-9a-fA-F]{128}", expected) or expected.lower() != r["sha512"]:
                raise ValueError("same-repository SHA512 mismatch")
            r["same_repository_sha512_match"] = True
    return {"schema": 1, "commit": commit, "configured_repository": REPOSITORY,
            "publisher_authenticated": False, "records": sorted(records, key=lambda r: (r["coordinate"], r["file"]))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = inventory(args.root, args.commit)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Public runtime receipt: {len(result['records'])} files; publisher trust not established")


if __name__ == "__main__":
    main()
