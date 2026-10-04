# Builds, installation and signing recovery

Repository: [averylicious/muon](https://github.com/averylicious/muon) (visibility is controlled by the repository owner).
Workflow: [Android APKs](https://github.com/averylicious/muon/actions/workflows/android.yml).

## What runs

Every push to any branch starts a check; **Actions → Android APKs → Run workflow** also works. A newer push to the same branch cancels an unfinished older run. This builds each pushed branch tip, not every intermediate commit inside a multi-commit push. Successful `main` builds publish Canary prereleases with the repository's current visibility; pushed `vMAJOR.MINOR.PATCH` tags publish stable releases. Other branches only build artifacts. See [release channels and Obtainium setup](obtainium.md). While the repository is public, source, history, release assets and Actions logs are publicly accessible; do not treat former private releases/logs as confidential. Switching back to private does not erase existing copies or public forks. Signing keys remain GitHub secrets; never upload them or private recovery bundles. See [the scoped public-readiness check](audits/2026-09-30-public-readiness.md).

### Documentation-only checks

The workflow keeps the existing **Build, test and sign** check name, but its scope summary explains what actually ran. Documentation-only pushes run Python scope/publication tests and validate changed Markdown/text for UTF-8, unresolved conflict markers and unclosed fences. They skip Java/SDK/Gradle setup, signing-key restoration, APK/report artifacts and release publication. No phone QA or APK installation is needed.

The conservative allowlist is root README/AGENTS/CLAUDE/CHANGELOG/CONTRIBUTING/LICENSE, the PR template, and Markdown/text/image/PDF files under `docs/`. Changes to app resources/source, Gradle, workflows, tools, documentation generator scripts, `docs/signing-certificates.txt` (an APK verification input), or unknown paths take the full Android path. Renames inspect both old and new paths. Main pushes compare the entire before/after push, and also everything since the newest eligible published Canary in main's history; other branches also compare all unmerged changes against their merge base with main. Thus a README-only follow-up on an app feature branch still builds the app. New branches compare against main; missing history or empty comparisons default to a full build. A documentation push on main publishes a Canary only when app changes since the last Canary are still unpublished, for example when it cancelled the run of an app merge just before it. Otherwise it publishes nothing.

Deleted-branch events do not build APKs. Full history is checked out for conservative merge-base comparisons, including design assets; this is a small checkout cost in exchange for skipping Android work safely.

A tag alone is not proof that an APK was published. For main, the selector reads paginated GitHub Releases metadata using the build job's existing `contents: read` token. A baseline must be a non-draft Canary prerelease with a publication timestamp, its expected APK/BUILD.txt/SHA256SUMS uploaded and nonempty, and a reachable Git tag matching the immutable target SHA recorded by our publisher. It chooses the highest eligible run number, not release-list order. Tags with no release, drafts, missing assets, moved tags and unrelated histories cannot hide unpublished app changes. This is metadata validation, not another download/checksum verification of every old APK.

No eligible baseline, unavailable API/authentication, malformed responses or a lookup timeout conservatively selects the full build. Feature branches do not query Releases; manual/tag events still build unconditionally. No signing secret or additional token permission is needed for the lookup. CI also checks that the manifest and extraction rules keep all Android storage domains excluded from backup and device transfer.

Tags and **Run workflow** always take the full signed build path. Avoid commit-message skip directives and top-level path filters: those can leave required checks pending ([GitHub documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)). This change does not enable fork builds, broaden secret permissions or alter package/signing identities. Inspect live main protection and the latest checkpoint for server-side enforcement; prose is not evidence it is enabled. The separate read-only **Branch policy / Branch direction** PR workflow checks branch direction and integration freshness without signing secrets or APKs. It also runs for fork PRs, which still do not receive the signed Android build. See [parallel tracks](parallel-tracks.md).

Stable `vMAJOR.MINOR.PATCH` tag runs check their Git ancestry before restoring signing keys and again before publication. The tag must resolve to the workflow commit, that commit must already be included in fetched `main`, and it must not contain the immutable alpha-experiment anchor. Missing refs or shallow history fail closed. Valid historical main commits and annotated tags remain eligible; branch artifacts and main Canary behavior are unchanged. This guards accidental cross-track promotion, not malicious trusted writers who can edit workflows, hand-copied alpha changes, or tags pointing to historical workflows predating the guard. A passing check does not authorize a Stable release; the user must still request one.

Full builds still produce the artifacts described below; documentation-only runs produce none. Workflow run numbers continue increasing on docs-only runs, so version codes may have gaps. Only compare APK versions for runs that actually built them.

All three Java/Gradle workflows verify the downloaded Temurin archive's detached signature using the public key bundled in the pinned Java setup action. Before Java setup, a hosted-Linux-only helper preserves the preinstalled Temurin namespace in the job temp directory, outside tool-cache lookup. The action otherwise accepts those entries without archive verification, even with `verify-signature` enabled. Other runner tools stay in place, and failures in this preparation stop the job. Invalid or missing signatures stop setup before Gradle or signing-key restoration. This adds a JDK download per job and keeps the existing Java major-version selection; the exact patch still follows the upstream release. It does not authenticate the runner, action bundle, SDK or Gradle dependency/cache contents. See the [source review and verification limits](audits/2026-10-04-java-action-trust.md).

All three workflows also preserve restored `GRADLE_USER_HOME/wrapper/dists` outside wrapper lookup before SDK setup, Gradle execution and signing restoration. The pinned wrapper then performs a fresh installation with the checked-in distribution checksum; a cached extracted home and `.ok` marker would otherwise bypass that ZIP check. The hosted-Linux-only guard rejects changed wrapper properties, unexpected paths and incomplete moves. Wrapper upgrades must refresh its source-review receipt. Ordinary dependency caches remain; optional post-action cleanup is disabled to avoid an alternate Gradle-provisioning fallback. This adds a download and can retain more cached bytes. It does not authenticate plugins, dependencies or arbitrary job code. See the [source and actual-run evidence](audits/2026-10-05-gradle-distribution-refresh.md).

The Ubuntu 24.04 runner installs JDK 17 and Android SDK 37.0 / Build Tools 37.0.0, validates the Gradle wrapper, and runs:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

Three artifacts are retained for 14 days: `app-debug-<commit>`, `app-release-<commit>`, and `reports-<commit>`. APK archives include SHA256SUMS and commit/run/version information. Reports include unit tests, lint, and the release R8 mapping. Copy artifacts you want to keep permanently before expiry. Action dependencies are pinned to verified commit SHAs, the build token has only `contents: read`; a separate publishing job receives `contents: write`, and checkout credentials are not persisted. Gradle caching is enabled. See [GitHub's artifact documentation](https://docs.github.com/en/actions/tutorials/store-and-share-data).

Real Tauon/device instrumentation remains a manual LAN test (`tools/device-proof.sh`); hosted GitHub runners cannot reach the user's desktop or Pixel. CI never opens a tunnel to that LAN.

## Which APK to install

| Variant | Package / launcher name | Signing | Installation behavior |
| --- | --- | --- | --- |
| Debug | `dev.avery.muon` / Muon β (the Canary channel) | Original milestone debug key | Updates the existing Pixel app and preserves its connection settings |
| Release | `dev.avery.muon.release` / Muon | Dedicated RSA 4096 release key | Installs alongside debug with its own settings; connect it to Tauon separately |

Release is non-debuggable, minified and resource-shrunk. Canary (the debug build type) is also non-debuggable, so phone QA reflects real performance (#125); it is not minified. `tools/verify-apks.py` rejects a debuggable APK of either variant. CI uses the workflow run number as Android versionCode and `0.1.0-canary.<run>` as Canary versionName. Stable versionName comes from the `vMAJOR.MINOR.PATCH` tag (without `v`); untagged release artifacts use `0.1.0`. Update the base version in the workflow when beginning a new development series. Older artifacts can be rejected as downgrades; use a newer run. Local builds default to versionCode 1; set `MUON_VERSION_CODE` higher than the installed version if needed. Each app controls its own Android player; audio focus coordinates them.

CI debug signing is stable, but the debug variant is intended for development. Release uses a separate permanent identity; its alias is `muon-release`. Public certificate fingerprints are in [signing-certificates.txt](signing-certificates.txt). Verify APKs with SDK `apksigner verify --verbose --print-certs` and compare these fingerprints.

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

## Packaged component access

The APK verifier checks both variants after manifest merging and release shrinking. It requires the Muon playback/download services to be private and rejects unreviewed exported components. The launcher and explicitly permission-protected Media3 Bluetooth/profile-installer components are the only allowed exports. `tools/apk_manifest.py` records the policy; `tools/test_ci_manifest.py` exercises failures. It decodes APK manifests with the SDK command-line tools, so a missing analyzer or unexpected manifest fails CI. This is a static package check; notification/headset compatibility still needs user QA.
