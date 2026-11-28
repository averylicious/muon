# Legacy original retirement manifest — 2026-10-12

Main audit, cumulative successor to #508 at d4e9c138553909b734aebbe8c629d1868668d7fc. Included main: 2a275286f28db78083567465a521b7ea46f32640; main-only portable checkpoint #504 subsequently merged as 5a2c47f8c21517de7801ccadfe108c9ea6206dbf and is not app integration. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. Claude and phones unused. Partition mode remains disabled. Full legacy-original Delete/Move is required before activation, as explicitly selected by the user.

## Why this primitive is needed

Opening the full old SimpleCache to use resource removal defeats the native-index memory redesign. Media3's whole-cache delete also uses whole-directory file arrays and cannot delete one original. The proposed transition uses a durable app-owned, bounded exact-file retirement intent while keeping the old native index read-only. The pinned v3 format is already checked by the read-only projection; it is not a generic cache/filesystem cleanup API. It must refuse unsupported formats rather than repair/adopt them.

This change adds only the bounded exact-file observation and revalidation/encoding primitive. It performs no deletion, no native SQL write, no manager command and no startup/activation switch. A manifest grants no deletion authority by itself. The next coordinator must durably record explicit Delete or byte-verified Move intent, hold the actual exclusive volume/source/writer/reader gate, check aliases and update route/manager/cover state in the correct order. None of those steps is supplied by a folder-lock snapshot or a decoded manifest alone.

## Invariants

- Capture uses the actual bounded read-only projection of the closed cache: at most 256 selected spans and the existing exact migration metadata ceiling; no full native index in Java.
- Root/database/UID-marker file identities, exact content ID, raw metadata digest, each shard-relative v3 path, extent/file identity and streamed SHA-256 bind the observation. Actual file reads use a fixed 8 KiB buffer and the tracked close owner. Unknown close retains its exact handles through MigrationIoUncertain; a future controller must quarantine its gate, never retry close/unlink.
- Before use, bounded SQL checks require the same content/metadata and exact selected file-index names/lengths. Added/unrecorded spans refuse. Foreign/redirected shards/files and changed bytes refuse without cleanup. Reads stop at the known extent and reject growth/short data.
- Binary v1 encoding and decoding are limited to 256 KiB and 256 entries, reject malformed/trailing/unsupported input, and never enumerate other resources into retained collections. Hashes identify changed bytes; they do not authenticate untrusted data.
- Missing files may be observed only in the explicit pending-recovery verification mode. That observation is NOT proof of completed deletion; durable intent, route tombstones and final index disposition are still required.

## Verification and remaining engineering

Local git diff --check passed. Actions is the first Android compile; latest-head reports/APK verification must be recorded on the PR. New native SQLite/real-cache tests cover selected-only roundtrip without native reopen, changed bytes/oversized changed metadata, missing pending files versus fresh authority, redirected missing-file shards, added indexed spans and malformed encodings. No phone, mount, visual or performance claims.

Next: durable retirement journal, exact owner/controller and restart-safe physical source cleanup; published-Ready/new-copy routing and alias-safe terminal row/cover retirement; full partition Move and bounded bulk mutation; production persisted opt-in/recovery/startup activation and composed lifecycle verification. Protected/read-only legacy originals do not satisfy the user's full-support requirement. Keep application PR OPEN for inherited acceptance; no Stable tags/releases or backend activation.
