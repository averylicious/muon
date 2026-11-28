# Builds, installation and signing recovery

Repository: [averylicious/muon](https://github.com/averylicious/muon) (visibility is controlled by the repository owner).
Workflow: [Android APKs](https://github.com/averylicious/muon/actions/workflows/android.yml).

## What runs

Every push to any branch starts a check; **Actions → Android APKs → Run workflow** also works. A newer push to the same branch cancels an unfinished older run, so each pushed branch tip is built, not every intermediate commit. Successful `main` builds publish Canary prereleases with the repository's current visibility; pushed `vMAJOR.MINOR.PATCH` tags publish Stable releases; other branches only build artifacts. See [release channels and Obtainium setup](obtainium.md).

While the repository is public, source, history, release assets and Actions logs are publicly readable; do not treat former private releases or logs as confidential, and switching back to private does not erase copies or forks. Signing keys remain GitHub secrets; never upload them or private recovery bundles. See [the scoped public-readiness check](archive/audits/2026-09-30-public-readiness.md).

### Documentation-only checks

The workflow keeps the **Build, test and sign** check name; its scope summary says what actually ran. Documentation-only pushes run the Python scope/publication tests and validate changed Markdown/text (UTF-8, no conflict markers, no unclosed fences). They skip Java/SDK/Gradle setup, signing-key restoration, artifacts and publication, and need no phone QA.

`tools/ci_scope.py` selects the scope conservatively:

- **Allowlist:** root README/AGENTS/CLAUDE/CHANGELOG/CONTRIBUTING/LICENSE, the PR template, and Markdown/text/image/PDF files under `docs/`. Everything else takes the full Android path: app source/resources, Gradle, workflows, tools, documentation generator scripts, `docs/signing-certificates.txt` (an APK verification input) and unknown paths. Renames count both old and new paths.
- **Compared range:** main pushes compare the whole push and everything since the newest eligible published Canary; other branches also compare all unmerged changes against their merge base with main, so a README-only follow-up on a feature branch still builds the app. New branches compare against main. Missing history or an empty comparison selects a full build. Deleted-branch events build nothing; full history is checked out for the comparisons.
- **Main publication:** a documentation push on main publishes a Canary only when app changes since the last Canary are still unpublished (for example after it cancelled an app merge's run); otherwise it publishes nothing.
- **Refreshed documentation branches** may skip rebuilding inherited main changes only when the branch itself has no non-documentation changes, the push preserves ancestry, and those main changes already have a complete, matching Canary publication (optionally followed by documentation-only main commits). Removed feature code, force pushes, stale main integration, unpublished main code or missing evidence select a full build.
- **Publication evidence** comes from paginated GitHub Releases metadata with the build job's existing `contents: read` token. An eligible baseline is a non-draft Canary prerelease with a publication timestamp, its APK/BUILD.txt/SHA256SUMS present and nonempty, and a reachable tag matching the target SHA our publisher recorded; the highest eligible run number wins. Tags without releases, drafts, missing assets, moved tags and unrelated histories cannot hide unpublished app changes. This is metadata validation, not a re-download of old APKs. No eligible baseline, API/authentication failure, malformed responses or a timeout select the full build. Ordinary feature-branch documentation pushes make no lookup.

Tags and **Run workflow** always take the full signed path. Avoid commit-message skip directives and top-level path filters, which can leave required checks pending ([GitHub documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)). Fork PRs do not receive the signed build, and no secret permissions are broadened. Check live branch protection and the latest checkpoint for server-side enforcement; prose is not evidence it is enabled. The separate read-only **Branch policy / Branch direction** workflow checks branch direction and integration freshness without secrets or APKs, including for fork PRs. See [parallel tracks](parallel-tracks.md). CI also checks that the manifest and extraction rules exclude all Android storage domains from backup and device transfer.

### Stable tags and release notes

Stable `vMAJOR.MINOR.PATCH` tag runs check ancestry with `tools/check_release_policy.py` before restoring signing keys and again before publication: the tag must resolve to the workflow commit, and that commit must already be included in fetched `main`. Shallow history or missing refs fail closed. Branch artifacts and main Canary behaviour are unaffected. This guards accidental cross-track promotion, not trusted writers who can edit workflows or tags pointing at workflows that predate the guard. A passing check does not authorize a Stable release; the user must still request one.

Generated release notes describe access according to the repository's current visibility and link the Obtainium guide. Published releases are never overwritten on reruns. Run numbers keep increasing on documentation-only runs, so version codes may have gaps; compare APK versions only for runs that built them.

### Toolchain and signing boundaries

- **JDK:** all three Java/Gradle workflows verify the downloaded Temurin archive's detached signature with the key bundled in the pinned Java setup action. A hosted-Linux-only helper first moves the preinstalled Temurin namespace out of tool-cache lookup, since the action would otherwise accept it unverified. Invalid or missing signatures stop setup before Gradle or signing. This does not authenticate the runner, action bundle, SDK or Gradle caches. [Review and limits](archive/audits/2026-10-04-java-action-trust.md).
- **Gradle wrapper:** restored `GRADLE_USER_HOME/wrapper/dists` is moved out of lookup before SDK setup, so the pinned wrapper reinstalls against the checked-in distribution checksum. Changed wrapper properties, unexpected paths and incomplete moves are rejected; wrapper upgrades must refresh the source-review receipt. Dependency caches remain and post-action cleanup is disabled. This does not authenticate plugins or dependencies. [Source and run evidence](archive/audits/2026-10-05-gradle-distribution-refresh.md).
- **Build order:** the Ubuntu 24.04 runner installs JDK 17 and the Android SDK named in the workflow, validates the wrapper, and runs the explicit checks before any signing material is restored:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

Only then does it restore the keys and build the signed APKs:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

This keeps keys and the release password out of the unit tests and full lint. It reduces direct exposure; it is not an isolation boundary: earlier code on the runner can still alter the workspace or Gradle home, and the signed assemble runs AGP's implicit checks (such as release vital lint) and all plugins with the keys present.

Three artifacts are retained for 14 days: `app-debug-<commit>`, `app-release-<commit>` and `reports-<commit>` (unit tests, lint, release R8 mapping). Test builds rename the Canary artifact `app-diagnostic-<commit>` or `app-timings-<commit>` (below). APK archives include SHA256SUMS and commit/run/version information; copy anything you want to keep before expiry. Actions are pinned to verified commit SHAs, the build token has only `contents: read`, a separate publishing job receives `contents: write`, and checkout credentials are not persisted. See [GitHub's artifact documentation](https://docs.github.com/en/actions/tutorials/store-and-share-data).

The optional **Baseline Profile tools** workflow runs two storage-gate tests in the Benchmark variant before restoring its debug key; an XML check requires both exactly once with no skips or failures (reports kept seven days). Real Tauon/device instrumentation remains a manual LAN test (`tools/device-proof.sh`); hosted runners cannot reach the user's desktop or Pixel, and CI never opens a tunnel to the LAN.

## Which APK to install

| Variant | Package / launcher name | Signing | Installation behavior |
| --- | --- | --- | --- |
| Debug | `dev.avery.muon` / Muon β (the Canary channel) | Original milestone debug key | Updates the existing Pixel app and preserves its connection settings |
| Release | `dev.avery.muon.release` / Muon | Dedicated RSA 4096 release key | Installs alongside debug with its own settings; connect it to Tauon separately |

Release is non-debuggable, minified and resource-shrunk. Canary (the debug build type) is also non-debuggable, so phone QA reflects real performance (#125); it is not minified. `tools/verify-apks.py` rejects a debuggable APK of either variant, except the opt-in diagnostic app below. CI uses the workflow run number as Android versionCode and `0.1.0-canary.<run>` as Canary versionName. Stable versionName comes from the `vMAJOR.MINOR.PATCH` tag (without `v`); untagged release artifacts use `0.1.0`. Update the base version in the workflow when beginning a new development series. Older artifacts can be rejected as downgrades; use a newer run. Local builds default to versionCode 1; set `MUON_VERSION_CODE` higher than the installed version if needed. Each app controls its own Android player; audio focus coordinates them.

CI debug signing is stable, but the debug variant is intended for development. Release uses a separate permanent identity; its alias is `muon-release`. Public certificate fingerprints are in [signing-certificates.txt](signing-certificates.txt). Verify APKs with SDK `apksigner verify --verbose --print-certs` and compare these fingerprints.

The packaged APK gate requires exactly one app signer and the pinned certificate in every applicable signature-scheme record from Build Tools 37.0.0. A source-stamp certificate cannot satisfy the app identity, and extra signers are refused even when the expected certificate is also present. No key rotation or signing identity change is enabled by this check.

### Diagnostic build (opt-in, never published)

For temporary device diagnosis that needs a debuggable app (a debugger, `run-as` or other debug-only tooling), run **Actions → Android APKs → Run workflow** on a branch other than `main` and tick **diagnostic_debug** (default off). Only that manual branch request makes a separate diagnostic APK debuggable. A request on `main` or a tag, or an unexpected value, fails the run before the SDK or signing keys are set up (`tools/diagnostic_mode.py`). The Gradle switch `MUON_DIAGNOSTIC_DEBUG` is read only for the debug build type and must be exactly `true` or `false`. The release APK, and the benchmark type built from it, are non-debuggable in every run.

A diagnostic run never publishes: its channel is `none`, the publish job separately requires a non-diagnostic build, and `tools/publish-release.py` refuses a diagnostic artifact or setting. No extra secrets, permissions or triggers are involved, and Canary/Stable identities are unchanged. The diagnostic app is `dev.avery.muon.diagnostic`, signed with the original debug key, with the run number as versionCode and its own settings, download indexes and external directories. To identify one:

- The job summary is headed **Diagnostic build: debugging enabled**, and the artifact is `app-diagnostic-<full SHA>`.
- `BUILD.txt` has a `-diagnostic` version suffix and a `Mode: diagnostic` line; the versionName is `0.1.0-canary.<run>-diagnostic`.
- The launcher label is **Muon diag**, and Settings shows a red **Diagnostic build — debugging enabled** banner (read from the installed app's actual debuggable flag, so any debuggable build shows it).

`tools/verify-apks.py` makes the same selection from the same event: a diagnostic run requires the diagnostic APK to be debuggable with its label and version; every other run requires both APKs to be non-debuggable; the release APK is always non-debuggable. Package, versionCode, certificate and label are verified for both.

It installs **beside** Canary and Stable and shares none of their indexes, files or settings. Connect it separately and use disposable audio for failure testing. A separate app does not isolate the physical SD card: keep regular Muon stopped during unmount tests and always remount afterward; never use existing user downloads as fault-injection fixtures. Performance is **not representative** with debugging enabled (#125). Return to ordinary Canary or Stable for listening; never uninstall them to leave diagnostic mode.

## Temporary non-debuggable storage timings

For the preserved Canary legacy fixture, manually dispatch **Android APKs** on an isolated non-main branch with `storage_timings=true` and `diagnostic_debug=false`. The build keeps the ordinary Canary package, signing identity and data and stays **non-debuggable**; it adds a `-timings` version suffix, a **Storage timing test build** Settings notice and a mode line in `BUILD.txt`. The artifact is `app-timings-<full SHA>` (the APK inside is still `app-debug.apk`); with `diagnostic_debug=true` too, it is `app-diagnostic-<full SHA>`, and timing never enables debugging. Installing it does not opt in, migrate, import or delete data. Return with a newer ordinary build; never uninstall or bypass version-code/signing protection.

The mode is off by default and rejected on main, tags and non-manual events. Selection happens before SDK/signing and is rechecked against the raw input before APK upload; the channel and publication job both refuse timing builds, and release/benchmark keep timing disabled. What it records is listed under [diagnostic timing observers](#diagnostic-timing-observers).

## Where the originals are

Local originals are kept in ignored `.local/` on this desktop, with private file permissions:

- `.local/debug.keystore`: original debug identity.
- `.local/signing/muon-release.p12`: release private key and certificate.
- `.local/signing/credentials.json`: aliases and passwords.
- `.local/signing-backup.zip`: verified recovery bundle containing both keys, credentials and recovery instructions.

**The backup ZIP is not itself encrypted and contains keys plus passwords.** Save it as a **1Password Document** in a private vault, or on an encrypted drive. Do not attach it to an issue, commit it, put it in ordinary cloud sharing, or upload it as a build artifact. Retain an independent second backup if desired. Both complete keystores were also backed up as concealed Base64 values in the **Muon Android** 1Password Environment, together with store/key passwords and aliases. MCP confirmed all eight variable names; a restore from that Environment has not yet been tested. Base64 is reversible encoding, not a checksum or encryption. The ZIP itself has not been uploaded as a Document.

The password-protected keystore plus the alias and passwords are the signing backup; the SHA256 certificate fingerprint alone is not sufficient. Keep the same key for future updates. Losing the original key means ordinary updates of existing installations cannot be signed with a newly generated replacement. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## Local build without GitHub signing services

With JDK 17 and the Android SDK configured as in [Building Muon](building.md):

```sh
python3 tools/build-signed.py
# Or, after securely extracting the recovery ZIP elsewhere:
python3 tools/build-signed.py --signing-directory /secure/path/to/extracted-backup
```

The helper reads passwords locally and passes them as environment variables, not process arguments. It does not print or upload them. Source, JDK/SDK, Gradle and dependencies are still needed; preserve a source backup as well as the signing backup.

## Restore GitHub Actions after repository migration

Create these **repository Actions secrets** in the new private repository:

| Secret | Value |
| --- | --- |
| `MUON_DEBUG_KEYSTORE_BASE64` | Base64 of the original debug.keystore |
| `MUON_RELEASE_KEYSTORE_BASE64` | Base64 of muon-release.p12 |
| `MUON_RELEASE_PASSWORD` | Release password in credentials.json (used for store and key) |

The current repository has all three configured. GitHub secrets cannot be retrieved as a recovery backup; keep the local/1Password originals. Restored key files exist only under runner temporary storage, are removed in an always-run cleanup step, and are excluded from caches and uploaded artifacts. Missing secrets fail the build rather than silently generating new identities. Only grant repository push access to people trusted with signing; a modified workflow on a pushed branch can access repository secrets. Fork PR workflows are not enabled.

For workstream names versus APK versions, see [workstream names and release versions](release-naming.md).

### Disposable media-entry QA helper

An explicit manual diagnostic branch run also builds `mediaentryprobe`, a separate ordinary-app UID fixture for private-service denial checks. It has no network/storage/elevated permissions and refuses an exported target. It is compiled before Muon signing-key restoration with its own temporary debug key, uploaded as `media-entry-probe-<full-SHA>`, and never published; normal builds do not configure it. See [the probe boundaries and operation](archive/audits/2026-10-06-media-entry-device-probe.md). It does not replace the Canary acceptance app or establish headset/controller compatibility.

## Packaged component access

The APK verifier checks both variants, including the diagnostic app, after manifest merging and release shrinking. Muon's playback/download services must be private; only the launcher and the permission-protected Media3 Bluetooth/profile-installer components may be exported. `tools/apk_manifest.py` records the policy and `tools/test_ci_manifest.py` exercises failures. Manifests are decoded with the SDK command-line tools, so a missing analyzer or unexpected manifest fails CI. This is a static check; notification/headset compatibility still needs user QA.

## Untrusted verification candidate (manual, unsigned)

The **Dependency inventory** workflow accepts `verification_candidate=true` only on manual dispatch (default false). It runs unsigned compile/test/lint and baseline-generator compilation, then uploads `untrusted-verification-candidate-<full-SHA>` with bounded public `verification-metadata.xml` and `RECEIPT.json` for 14 days. It publishes no APK, restores no real keys and does not enable dependency verification: these are observed checksums needing independent review, not an allowlist. Non-Gradle test-runtime fetches (including Robolectric) are excluded. See [the candidate scope and trust limits](archive/audits/2026-10-07-verification-candidate.md).

### Isolated Robolectric runtime inventory

The same workflow's manual `runtime_inventory` flag (default false) runs both unit-test variants against a new empty per-run Maven repository and the public Robolectric repository `https://repo1.maven.org/maven2`, through a dispatch-only init script that ordinary builds never import. Host repositories are untouched, and no signing keys or publication are involved. Only a bounded public `runtime-inventory-<full-SHA>` JSON receipt (coordinates, filenames, sizes, hashes and checked SHA512 companions) is uploaded for 14 days. Cold fetches and matching server checksums do **not** authenticate the publisher, cover all native dependencies, or harden ordinary cache reuse.

## Diagnostic timing observers

Storage timing and debuggable builds may add capped, fixed-label numeric observations. Ordinary and Stable builds construct none of these observers and make no extra clock/log calls. Each family is capped at 32 events or operation tokens per process (`MIXED_ROUTE` shares the root/volume cap), logs no identifiers, paths, song/server/error text or history, and cannot change routing, admission, cleanup or ownership when its clock or sink fails. They are diagnostic measurements, not an uninstrumented benchmark; the debug-only startup logger stays debug-only.

| Observer | Enabled in | Notes |
| --- | --- | --- |
| Root/volume (`PHONE_ROOT`, `CARD_ROOT`, `PHONE_VOLUME`, `CARD_VOLUME`, `LEGACY_CARD_VOLUME`, `OWNED_CARD_VOLUME`) | Timing, diagnostic | Root totals overlap their volume totals; do not add them. The legacy and owned-partition volume labels are separate fresh lookups inside their owners' full checks. |
| `MuonRootTiming` `MIXED_ROUTE` | Timing, diagnostic | One process-wide observer for the mixed saved-copy decision across both shelves; includes navigation/listing work and overlaps nested totals. Compare the latest counter per phase; it caches no route and grants no authority. |
| `MuonReaderTiming` | Timing | Open, power-of-two read progress and final close/failure per process-local reader counter. Use only each reader's latest cumulative event; total includes idle time. Force-stop may leave no close event. |
| `MuonSavedTiming` | Timing | Preparation/admission/route/open/first-read/ready stages, which may overlap; not host Playing latency. |
| `MuonDigestTiming` | Timing, debuggable | One result per read after close and final binding check. Stages are disjoint at the wrapper; READ includes nested cache guards. |
| Move verification phases | Debuggable only | Admission/open/metadata/audio/after/close/final totals; nested root/projection costs overlap. |
| `MuonMoveBatch` | Debuggable only | Whole partition Move batch phases; result submission does not measure main-thread delivery. See [scope and acceptance boundaries](archive/audits/2026-10-19-move-batch-timing.md). |

Buffer bounds that these observations touch: Move verification uses two fixed 256 KiB comparison buffers per comparison, at most two comparisons (1 MiB total), and compares every byte, so cancellation can wait through one bounded refill. Full saved-audio digests use one fixed 1 MiB buffer per active digest under the exclusive storage gate, with a fresh checkpoint, length/EOF and binding checks on every read; cancellation can wait through up to 1 MiB of one read. Ordinary playback read-ahead is unchanged. Record device results before claiming a speedup.
