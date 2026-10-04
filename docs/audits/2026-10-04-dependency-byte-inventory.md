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
- No extra code runs: it only reads files Gradle already selected.
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

- **What it is:** an **observation** of which files the Gradle run actually selected, made after build configuration. It gives later audits exact bytes to compare against published checksums or signatures.
- **What it isn't:**
  - publisher authentication;
  - verified dependency admission;
  - Gradle dependency verification;
  - trust in the cache;
  - protection before execution: plugins and build logic have already run by then.
- **No admission step:** nothing generates an allowlist or verification metadata, and no dependency changes.
- **Coverage:** modules with no artifact (platforms and BOMs) appear in `modules` but not in `artifacts`. The parser doesn't require every module to have one.

## Limits

- **Gradle API not checked locally:** `ResolvableDependencies.getArtifacts()`, `ArtifactCollection.getArtifacts()`, `ResolvedArtifactResult.getFile()/getId()` and `ComponentArtifactIdentifier.getComponentIdentifier()` were written from long-standing public Gradle API knowledge. They were not checked against Gradle 8.13 sources (the wrapper pins `gradle-8.13-bin.zip`). The unsigned workflow's compile and run are the first check.
- **Android scopes:** resolving artifacts this way is expected to give the selected AAR and JAR files without transforms, but that is unverified until CI shows the actual record. The record should be inspected, not just the green job.
- **Size and sorting:** the log line grows with hundreds of artifact records. Sorting is lexicographic in both Kotlin and Python, which matches for the enforced ASCII names. A real artifact name outside `[A-Za-z0-9._+-]` would fail the parser visibly rather than pass silently. If that happens, widen the pattern deliberately.
- **No published-checksum comparison yet:** that is the next bounded task, once a CI record exists.

**Checks run locally:**
- `python3 -m unittest discover -s tools -p 'test_dependency_inventory.py'` (10 OK);
- the CI policy tests (54 OK);
- `git diff --check` and the CI prose check.

No Gradle or Android build was run, and no phone QA is needed.
