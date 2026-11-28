# Dependency byte inventory — 2026-10-04

Inspected main `21d704bc871f883ce3973339452af9a4398c0126`, branch `codex/dependency-byte-inventory`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and self-check by the author, **not independent review**. CI-only: no app, dependency, workflow, permission or secret change. Not compiled locally: the unsigned **Dependency inventory** workflow is the first run of the changed init script.

**Gap:** `tools/dependency-inventory.init.gradle.kts` records the selected modules and edges for five scopes: the debug and release runtime classpaths, both unit-test runtime classpaths, and the root buildscript classpath. It records nothing about the actual bytes Gradle resolved.

## Change

**Init script.** For each scope, each resolved artifact of the configuration's own selection (`configuration.incoming.artifacts`) that belongs to a Maven module is recorded as:
- `module`: its coordinate;
- `file`: the file **name only**, with no runner or cache path;
- `size`: the byte count;
- `sha256`: streamed in 64 KiB chunks.

**What it doesn't touch:**
- No artifact view, attribute change or transform is requested.
- No sources, javadoc or other variants are resolved.
- The task hashes files without loading/executing their classes. Accessing the configuration's artifacts can trigger downloads and already-configured transforms; build configuration/plugins have already executed. It is not a no-execution boundary.
- A file seen in several scopes is hashed once.
- Non-module artifacts (project or file dependencies) are skipped.

**Failure:** a scope with no module artifacts, or a resolved artifact that isn't a regular file, fails the task.

**Compatibility:** the record stays schema 1. `artifacts` is a new optional key per scope; modules and edges are unchanged.

**Parser (`tools/dependency_inventory.py`).** `artifacts`, when present, must be a non-empty list of exactly those four keys, and each entry must have:
- a module that is in that scope's selected modules;
- a bare ASCII file name without separators, whitespace, `.` or `..`;
- a size that is a non-negative JSON integer (not a boolean, float, NaN or Infinity);
- a lowercase 64-hex SHA-256.

Entries must be unique and sorted by module and file. Records without `artifacts` are still accepted, and the command line now reports how many scopes have byte observations and how many don't (older records).

**Tests (`tools/test_dependency_inventory.py`; 10 pass locally):**
- an older record without artifacts, and how it's reported;
- a valid observation, including a zero-byte file;
- malformed entries: unselected modules, absolute-path or space file names, boolean, negative, float, string, NaN, Infinity and exponent sizes, and bad hashes;
- duplicate or unsorted entries, and conflicting hashes for one file name.

## What this is, and what it isn't

- **What it is:** an **observation** of which files the Gradle run actually selected, made after build configuration. It gives later audits byte observations to compare against separately authenticated artifacts/checksums/signatures. It is not immutable evidence against already-running build logic or concurrent file mutation.
- **What it isn't:**
  - publisher authentication;
  - verified dependency admission;
  - Gradle dependency verification;
  - trust in the cache;
  - protection before execution: plugins and build logic have already run by then.
- **No admission step:** nothing generates an allowlist or verification metadata, and no dependency changes.
- **Coverage:** modules with no artifact (platforms and BOMs) appear in `modules` but not in `artifacts`. The parser doesn't require every module to have one.

## Limits

- **Gradle API:** coordinator read public [Gradle v8.13.0 core API sources](https://github.com/gradle/gradle/tree/v8.13.0/subprojects/core-api/src/main/java/org/gradle/api/artifacts) for `ResolvableDependencies.getArtifacts()`, `ArtifactCollection.getArtifacts()`, `ResolvedArtifactResult.getFile()`, inherited `ArtifactResult.getId()` and `ComponentArtifactIdentifier.getComponentIdentifier()`. This verifies the declared API; the unsigned workflow is the first actual Kotlin-script compile/resolution/hash run.
- **Android scopes:** selected files/variants must be inspected in CI; no additional view is requested, but existing configuration attributes/transforms still apply. The record should be inspected, not just the green job.
- **Size and sorting:** the log line grows with hundreds of artifact records. Sorting is lexicographic in both Kotlin and Python, which matches for the enforced ASCII names. A real artifact name outside `[A-Za-z0-9._+-]` would fail the parser visibly rather than pass silently. If that happens, widen the pattern deliberately.
- **No published-checksum comparison yet:** that is the next bounded task, once a CI record exists.

## First CI run and the identical-observation correction

**Unsigned Dependency run 33 compiled the script and hashed artifacts, then the parser refused the record.** The refusal was "Artifacts must be unique and sorted". The captured public metadata shows why:
- **The duplicate:** an identical record, `androidx.core:core:1.15.0`, `core-1.15.0.aar`, 1336135 bytes, SHA-256 `432b85a1974076e14b487ece4a28c59a84f1b9efc3fc8be72cd7f05d32055e51`. It appears twice in each of the four app scopes.
- **No conflict:** no differing bytes were observed for any module and file.

**Correction:** the init script now applies a **whole-record** `distinct()` before sorting. Records identical in module, file, size and hash coalesce. Records for the same module and file with a different size or hash are kept, so the parser's existing duplicate check still refuses them rather than hiding a conflict. The parser, its controls, workflows and artifacts are unchanged.

**Local rehearsal:** the same normalization, applied to the captured run 33 record, takes the scope counts from 77/77/109/109/145 to 76/76/108/108/145. The unchanged parser then accepts all five scopes as observed. This rehearses the parser on real public metadata. It is not a Gradle run: a fresh unsigned workflow must confirm the script's actual output.

**Checks run locally:**
- `python3 -m unittest discover -s tools -p 'test_dependency_inventory.py'` (10 OK);
- the CI policy tests (54 OK);
- `git diff --check` and the CI prose check.

No Gradle or Android build was run, and no phone QA is needed.

## Coordinator review and source receipts

GPT-6 / Codex desktop, effort not reported independently reviewed Claude authorab88fb63: existing five-scope/edges compatibility, streaming size/hash, optional strict parser, legacy reporting and malformed/duplicate controls. Qualified resolution versus execution claims; own comments/report corrections and main integration self-reviewed. No workflow permissions/signing/artifact-upload changes. Public v8.13.0 Java source SHA256 receipts: ResolvableDependencies `4daf5e1a9a27cae87bcbb700dc3a3eeb2eb1bd2f480b5181431f9d86910616b5`; ArtifactCollection `dc980f1fc1c6000539a2ca51527853e252f6de6284587789a062606a443f96b0`; ResolvedArtifactResult `40ca0a1a68e9883a138384a13ebc783c9057b932cf9f00e0e84379d07bc984ae`; ComponentArtifactIdentifier `0a711e3e92aaf5518dd1f4fce07aee5cab624b2b5d224e41f9799292b763bddc`. ArtifactResult receipt follows in PR. No local Gradle/Android run or checksum authenticity claim.
