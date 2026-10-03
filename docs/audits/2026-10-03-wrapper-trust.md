# Gradle wrapper trust in CI — 2026-10-03

Inspected main `8b443ba1cafa9f2ced961c195892027ccafcec93`, branch `codex/wrapper-trust-evidence`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Read-only source review, **not independent review**, and not a supply-chain clearance. GPT-6 / Codex desktop independently reviewed the focused action sources and the current JAR checksum; full bundle/platform-cache review remains incomplete. Nothing was executed: no Gradle, no wrapper JAR, no action code, no signing material.

**Question:** does each workflow run the checked-in Gradle wrapper only after checksum validation?

**Answer:** yes, in all three workflows that run Gradle, the validating `setup-gradle` step runs first, and a validation failure stops the job. But the validation trusts a checksum allowlist restored from the GitHub Actions cache before it runs (trust-boundary observation W1).

## Exact artifacts

| Item | Value |
| --- | --- |
| Tracked `gradle/wrapper/gradle-wrapper.jar` | git blob `9bbc975c742b298b441bfb90dbc124400a3751b9`, mode 100644, sha256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`. It's the only tracked wrapper JAR |
| Official [gradle-8.13-wrapper.jar.sha256](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256) | `81a82aae…7ae45f`: identical (also checked independently by the coordinator) |
| `gradle-wrapper.properties` | `gradle-8.13-bin.zip` with `distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` and `validateDistributionUrl=true`. The ZIP hash was not recomputed here |
| `gradle/actions` commit used by all three workflows | `0723195856401067f7a2779048b490ace7a47d7c` |
| `setup-gradle/action.yml` at that commit | sha256 `9f962f9604df64f176316d7eadb169348cd1b7041a754f417945f524bdd20840` |
| Executed `dist/setup-gradle/main/index.js` | sha256 `15bdf4c803b9f5c11dba1de77b4d46877af5d12c8d16f9a6bfd335926a3b4bdb` |

The local copies under ignored `build/sources/` were fetched by the coordinator from that commit and hashed here; I did not re-fetch them.

## Workflow evidence

| Workflow | Validating step | First Gradle execution | Notes |
| --- | --- | --- | --- |
| `android.yml` (push to any branch or tag, dispatch) | `setup-gradle` at line 73 | `./gradlew` at line 102 | Both gated on `steps.scope.outputs.build_apks`. The earlier Python steps don't run Gradle |
| `baseline-profile.yml` (path-filtered push, dispatch) | line 33 | line 50 | |
| `dependency-audit.yml` (path-filtered push, dispatch) | line 39 | line 43 | |
| `branch-policy.yml` | none | none | Python only |
| `android.yml` publish job | none | none | No Gradle |

**What holds across all three Gradle workflows:**
- No `with:` overrides. So `validate-wrappers` defaults to `true` and `allow-snapshot-wrappers` to `false` (`action.yml:192-204`).
- No `ALLOWED_GRADLE_WRAPPER_CHECKSUMS`, no `GRADLE_USER_HOME` override, and no `continue-on-error`.
- The steps between setup and Gradle (SDK packages, signing keys) don't touch the wrapper.
- The Gradle steps use the default success condition; only artifact upload and cleanup steps use `always()`.

## Action source, and what the executed bundle actually does

- **Order:** `setup-gradle.ts:27-59` restores the Gradle User Home cache (`caches.restore`, line 50), then validates (`validateWrappers`, line 54), before any Gradle provisioning (`main.ts:30-37`). The bundle's `bIt` keeps that order: `await bat(...)`, then `await yIt(...)`.
- **What is accepted** (`validate.ts:6-45`; bundle `BIt`): every `gradle-wrapper.jar` in the workspace is hashed. A hash passes if it's in
  - the `ALLOWED_GRADLE_WRAPPER_CHECKSUMS` environment variable,
  - the **previously validated** list,
  - or the embedded known list.

  Otherwise checksums are fetched from `services.gradle.org` (snapshots only when allowed). The current hash is in the bundle's embedded list (near byte offset 4245307), so today no fetch is needed.
- **Fail-closed:**
  - an invalid JAR throws `JobFailure` (`wrapper-validator.ts:24-37`);
  - any error reaches `core.setFailed` (`errors.ts:15-35`), and the step fails, skipping the Gradle steps;
  - the fetch doesn't check HTTP status, but a non-checksum body can't match, and malformed JSON or network failure after four attempts throws (`checksums.ts:67-86`; bundle `VBe`/`GZr`).
  - Nothing fails open was found.
- **Discovery:** `find.ts` walks the workspace with `lstat` and doesn't follow symlinked directories. The tracked wrapper is a regular file, so this only matters to someone who can already commit.

## W1 — cached validation state is a trust boundary (defense-in-depth observation)

- **Mechanism:**
  - `ChecksumCache` reads `$GRADLE_USER_HOME/.setup-gradle/valid-wrappers.json` (`cache.ts`; bundle `I9`, with `oQ=".setup-gradle"`).
  - The Gradle User Home cache entry **always includes `.setup-gradle`**: the bundle's `getCachePath()` pushes `oQ`.
  - Because restore runs first, a hash listed in a restored file passes without comparison to the embedded or official lists.
- **Trigger:**
  1. Code running in a cache-writing job writes extra hashes to that file, and the post step saves a new cache entry. Cache writing is on by default only on the default branch (`cache-read-only` default, `action.yml:17-22`). The code could be a Gradle plugin, build script or dependency.
  2. A later run that restores that entry is given a different wrapper JAR with a listed hash.
- **Classification:** confirmed source behavior, not a demonstrated vulnerability or compromised artifact. Cache provenance/scoping and a viable attacker route were not established.
- **Impact:** this only lowers the assurance of validation. Code running in a build already has arbitrary execution, and changing the JAR needs repository write. The checked-in JAR currently matches the official hash.
- **Mitigation, not implemented:** validate before any cache restore, for example with a separate validation step that doesn't use Gradle User Home. That step's source at the same commit hasn't been inspected, and the choice needs coordinator review.

## Limits and non-findings

- **Distribution ZIP from cache:** the cache also restores unpacked distributions (`wrapper/dists/*/*/`; zips deleted before save, bundle `deleteWrapperZips`). Whether the wrapper re-checks `distributionSha256Sum` for an already-unpacked distribution was **not inspected** (Gradle wrapper source not read). This is the same cache trust boundary as W1.
- **Trust assumptions:** repository writers and workflow editors, GitHub's cache scoping between branches (platform behaviour, not inspected), and GitHub's delivery of the pinned action commit.
- **Source-to-bundle comparison was partial:** focused bundle snippets only, no complete source-to-bundle audit. The post-step bundle was not read.
- **Not covered:** `setup-java`/Temurin JDK, `sdkmanager` packages, Maven/Google dependency artifacts (no dependency locking or verification metadata), other actions, and runners. The wrapper match doesn't imply any of their checksums.
- **Local runs:** Gradle runs outside CI, such as `tools/build-signed.py`, get no wrapper validation from this action.
- **Earlier work:** the [dependency reports](2026-09-30-dependency-follow-up.md) stand. No advisories were re-checked here.
