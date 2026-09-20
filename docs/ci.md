# Builds, installation and signing recovery

Repository: [averylicious/muon](https://github.com/averylicious/muon) (private).
Workflow: [Android APKs](https://github.com/averylicious/muon/actions/workflows/android.yml).

## What runs

Every push to any branch starts a check; **Actions → Android APKs → Run workflow** also works. A newer push to the same branch cancels an unfinished older run. This builds each pushed branch tip, not every intermediate commit inside a multi-commit push. Successful `main` builds publish private Canary prereleases; pushed `vMAJOR.MINOR.PATCH` tags publish stable releases. Other branches only build artifacts. See [release channels and Obtainium setup](obtainium.md). Nothing is published publicly.

### Documentation-only checks

The workflow keeps the existing **Build, test and sign** check name, but its scope summary explains what actually ran. Documentation-only pushes run Python scope/publication tests and validate changed Markdown/text for UTF-8, unresolved conflict markers and unclosed fences. They skip Java/SDK/Gradle setup, signing-key restoration, APK/report artifacts and release publication. No phone QA or APK installation is needed.

The conservative allowlist is root README/AGENTS/CHANGELOG/CONTRIBUTING/LICENSE, the PR template, and Markdown/text/image/PDF files under `docs/`. Changes to app resources/source, Gradle, workflows, tools, documentation generator scripts or unknown paths take the full Android path. Renames inspect both old and new paths. Main pushes compare the entire before/after push; other branches also compare all unmerged changes against their merge base with main. Thus a README-only follow-up on an app feature branch still builds the app. New branches compare against main; missing history or empty comparisons default to a full build. A new documentation push on main does not itself publish a Canary; if it cancels an earlier main run before publication, use a manual main run when that release is wanted.

Tags and **Run workflow** always take the full signed build path. Avoid commit-message skip directives and top-level path filters: those can leave required checks pending ([GitHub documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)). This change does not enable fork builds, broaden secret permissions or alter package/signing identities. No branch protection is configured as of 2026-09-20; the existing check name remains available if protection is added later.

Full builds still produce the artifacts described below; documentation-only runs produce none. Workflow run numbers continue increasing on docs-only runs, so version codes may have gaps. Only compare APK versions for runs that actually built them.

The Ubuntu 24.04 runner installs JDK 17 and Android SDK 36 / Build Tools 36.0.0, validates the Gradle wrapper, and runs:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

Three artifacts are retained for 14 days: `app-debug-<commit>`, `app-release-<commit>`, and `reports-<commit>`. APK archives include SHA256SUMS and commit/run/version information. Reports include unit tests, lint, and the release R8 mapping. Copy artifacts you want to keep permanently before expiry. Action dependencies are pinned to verified commit SHAs, the build token has only `contents: read`; a separate publishing job receives `contents: write`, and checkout credentials are not persisted. Gradle caching is enabled. See [GitHub's artifact documentation](https://docs.github.com/en/actions/tutorials/store-and-share-data).

Real Tauon/device instrumentation remains a manual LAN test (`tools/device-proof.sh`); hosted GitHub runners cannot reach the user's desktop or Pixel. CI never opens a tunnel to that LAN.

## Which APK to install

| Variant | Package / launcher name | Signing | Installation behavior |
| --- | --- | --- | --- |
| Debug | `dev.avery.muon` / Muon Canary | Original milestone debug key | Updates the existing Pixel app and preserves its connection settings |
| Release | `dev.avery.muon.release` / Muon | Dedicated RSA 4096 release key | Installs alongside debug with its own settings; connect it to Tauon separately |

Release is non-debuggable, minified and resource-shrunk. CI uses the workflow run number as Android versionCode and `0.1.0-canary.<run>` as Canary versionName. Stable versionName comes from the `vMAJOR.MINOR.PATCH` tag (without `v`); untagged release artifacts use `0.1.0`. Update the base version in the workflow when beginning a new development series. Older artifacts can be rejected as downgrades; use a newer run. Local builds default to versionCode 1; set `MUON_VERSION_CODE` higher than the installed version if needed. Each app controls its own Android player; audio focus coordinates them.

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

With JDK 17 and SDK 36 configured as in README:

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
