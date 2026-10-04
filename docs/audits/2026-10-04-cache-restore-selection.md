# Gradle restore selection and exclusion failure — 2026-10-04

Inspected Muon main `224fd29becbdf4b30f6b2cf2de38332745d975de`. GPT-6 / Codex desktop, effort not reported: author source/self-review. Bounded continuation of the [cache-writer report](2026-10-04-cache-writer-boundary.md) and W2 in [cached distribution trust](2026-10-03-cached-distribution-trust.md). No cache/workflow/dependency change, phone access, signing material or action execution.

## Immutable source receipts

Fetched these files from pinned [gradle/actions commit0723195856401067f7a2779048b490ace7a47d7c](https://github.com/gradle/actions/tree/0723195856401067f7a2779048b490ace7a47d7c/sources/src/caching), over HTTPS without executing them:

| File | SHA256 |
| --- | --- |
| cache-key.ts | 9cd2894629b833e2a47d609daf1549fdc929d857c18f5c205956c166de771a22 |
| gradle-user-home-cache.ts | 3357a86569034a4537a5ed8ae26cf2de4afeeed866a44cac6c0b0f9e7fc18d58 |
| gradle-home-extry-extractor.ts | 6ea65cd9df9068c4bf379d50a697e2e6187954e19feead507e2c8f0339f8ea4e |
| caches.ts | 40f8c5acb93f483f2434e34c6f8ed5989a8e7d6f2610ad3f2f1e0c15e3282099 |
| cache-utils.ts | c14ac950bb5dc5bc386b06c8bfe3232007fe78393064d78f0a20af44f38092f0 |

Previously retrieved executed main bundle still hashes `15bdf4c803b9f5c11dba1de77b4d46877af5d12c8d16f9a6bfd335926a3b4bdb`. Focused compiled snippets for fallback generation, restore catch and excluded-path deletion agree with the source. This is not a full source-to-bundle equivalence or post-bundle audit.

## What selection permits

`generateCacheKey` (cache-key.ts:45–67) constructs an exact OS/architecture, job, workflow/matrix hash and commit key. Without strict matching, restore prefixes progressively omit commit, workflow/matrix and finally job. The broadest prefix retains cache kind/protocol and OS/architecture. Muon's three Gradle workflows set neither strict matching nor key overrides.

Thus a commit suffix is not proof of exclusive same-commit reuse; the normal fallback permits another eligible job's Gradle-home entry. It does not override GitHub's platform branch scope. No actual cross-job fallback selection, raw cache API permissions or untrusted-writer exploit was tested. Unsigned34/35 public logs do record restoration of extracted dependencies, transforms, generated Gradle API JAR and unpacked wrapper home. The generic restoration message prints the requested key (`cache-utils.ts:50`), not returned entry.key; do not infer an exact same-commit hit from that message. Main35 subsequently saved a new home key, whereas save102–104 skips an exact-key hit: this is consistent with a non-exact home restore, without identifying its originating job. Existing source/log writer receipts remain separate from runtime selection.

## Exclusions are cleanup, not an integrity gate

`GradleUserHomeCache.restore`52–77 restores the home, then `afterRestore`82–87 restores extracted entries and deletes excluded paths. The restore method catches afterRestore errors and emits a warning rather than failing the job. `deleteExcludedPaths`146–160 uses configured glob paths plus cc-keystore cleanup; `tryDelete`105–130 retries five times, then throws. That thrown cleanup failure is therefore absorbed by the restore catch on this route.

Before saving, exclusions are deleted before extraction (beforeSave131–139). Its error catch skips saving rather than certifying cleanup. Extractor definitions354–361 separately cache wrapper homes, toolchains, dependencies and transformed/generated executable state. No failure was injected into a runner.

Adding wrapper/dists to exclusions could normally remove a restored unpacked home, but an input alone does not guarantee removal before Gradle runs. Strict-match input could narrow cross-job home fallback; it is marked experimental by the pinned metadata and would neither authenticate cached bytes nor isolate all extracted entries. These are scoped mitigation candidates, not completed fixes or universal cache-security guarantees.

## Next reviewable boundary

Trace an actual restore receipt and decide whether to bypass restored executable homes or enforce a checked absence/re-authentication gate before the wrapper. Include download/CI latency and preservation of ordinary dependency caching. Do not trust a manifest produced from the suspect cache itself, introduce arbitrary deletion outside the job's disposable Gradle home, or call new checks pre-execution protection without verifying step order and failure handling.

Local prose/diff checks and latest-head documentation CI will be recorded on the PR. No APK or device QA is needed for this report. All application acceptance and broader W2/dependency provenance work remain open.
