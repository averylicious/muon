# Muon updates with Obtainium

Muon publishes two independent apps from the same repository:

| Channel | Launcher | Package ID | Release title | APK filename |
| --- | --- | --- | --- | --- |
| Canary | Muon β (diamond with a large C) | `dev.avery.muon` | `Muon Canary …` | `muon-canary-….apk` |
| Stable | Muon (circle with three bars) | `dev.avery.muon.release` | `Muon Stable …` | `muon-stable-….apk` |

Canary updates the original debug installation without uninstalling or losing settings. Stable updates previous “Muon Release” installations. Both keep their original certificates. They can coexist, but their server settings and app data are separate. Canary is built from the debug build type but is not debuggable, so it performs like stable (#125); stable is also minified. Canary's launcher label is "Muon β" (#29); release titles and APK names still say Muon Canary, so the filter below is unchanged.

## 1. Give Obtainium read access when private

While Muon is public, its published release assets do not require a token just to access them. Existing channel/package/signing filters below are unchanged. The steps in this section apply when the repository is private again; do not create a broader token because its visibility changed. Actions artifact downloads still require a GitHub login with read access and are not the Obtainium Releases feed.

For a private repository, edit or create a GitHub **fine-grained personal access token**:

1. Resource owner: **averylicious**.
2. Repository access: **Only select repositories → muon**.
3. Repository permissions: **Contents → Read-only**. Keep the automatically required **Metadata → Read-only**.
4. Remove **Variables** if selected. Actions, secrets, administration, workflow and write permissions are not needed.
5. Save/update the token. If you created a new token, copy it into Obtainium's GitHub source settings. If you edited the existing token's permissions, its value is unchanged.

Use the token in Obtainium's token field, never in the repository URL or an exported app configuration. It grants read access to this private repository, including source code, not just APKs. Leave any GitHub proxy prefix blank so Obtainium talks directly to GitHub. Keep your PAT out of Muon itself; GitHub Actions publishes with its own temporary token.

[GitHub's release-asset permission requirements](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).

## 2. Add Canary

Use **Add App** manually (not bulk URL import) and enter:

```text
https://github.com/averylicious/muon
```

Before completing Add, set the GitHub app-specific options. Labels may vary slightly with Obtainium version:

| Option | Value |
| --- | --- |
| Include prereleases | On |
| Filter release titles by regular expression | `^Muon Canary ` |
| Filter APKs by regular expression | `^muon-canary-.*\.apk$` |
| Fallback to older releases | On |
| Sort method | Release date / Date |
| Verify latest tag | Off (GitHub's “Latest” is the stable channel) |
| Try inferring app ID from source code | Off |
| Use release title as version string | Off |
| Track-only | Off |

Let Obtainium inspect/download the filtered APK to identify the package. Confirm **`dev.avery.muon`**. If the UI offers an explicit **App ID**, use that exact value. Complete Add; an existing Muon debug install should be recognized. Install/update only when you are ready.

## 3. Add Stable as a second entry

Use **Add App** again with the **same URL** and these settings:

| Option | Value |
| --- | --- |
| Include prereleases | Off |
| Filter release titles by regular expression | `^Muon Stable ` |
| Filter APKs by regular expression | `^muon-stable-.*\.apk$` |
| Fallback to older releases | On |
| Sort method | Release date / Date |
| Verify latest tag | On |
| Try inferring app ID from source code | Off |
| Use release title as version string | Off |
| Track-only | Off |

Confirm the APK's package is **`dev.avery.muon.release`**. This is a second app, not a switch that overwrites Canary. A published stable release must exist before this entry can be added.

Disable source-code ID inference for both: this Gradle project derives the stable ID with an application ID suffix, and a source parser can incorrectly pick the base Canary ID. The APK manifest is authoritative.

## Troubleshooting

- **App already added:** confirm you used manual Add App and selected the correct channel filter/package. Bulk URL import de-duplicates by URL; manual Add App de-duplicates by package ID. Do not invent a different ID to bypass a duplicate error.
- **404 / repository not found:** check selected repository, token expiry and Contents read permission. Private resources may return 404 when authorization is missing.
- **No matching releases/APKs:** the desired channel needs a published release with its APK attached. Drafts and Actions artifact ZIPs are not the feed. Check both regexes and enable fallback.
- **Wrong package:** stop and correct the filters / disable source-code inference. Do not uninstall the existing app to work around this.
- **Signature mismatch:** use the original signed channel APK; builds made with a different local key cannot replace it.
- **Downgrade rejected:** use a newer published build. Android versionCode must increase; changing a version label alone cannot make an older APK updateable.
- **Only old releases appear:** if many Canary releases exist, increase Obtainium's releases-to-fetch option if available; stable's Verify latest tag option follows the designated stable release.
- Expired Actions artifacts do not expire GitHub Release assets. Published releases stay available unless deliberately deleted.

## How releases happen

- Successful builds on `main` (including manual runs on `main`) publish unique Canary prereleases, such as `0.1.0-canary.7` (matching the APK version).
- Other branch builds produce test artifacts only.
- A pushed tag **`vMAJOR.MINOR.PATCH`**, such as `v0.1.0`, deliberately publishes a stable release and marks it Latest after checks pass. Keep stable tags on reviewed commits from `main`. Do not move or reuse published tags.
- Both use the existing Android workflow's increasing run number for versionCode. Preserve the workflow identity/run sequence when changing CI; never reset the counter below installed builds. A rerun of the same run is the same version, not a new update.
- Publication first uploads the verified APK, SHA256SUMS and BUILD.txt to a draft, then publishes it. Completed releases are never overwritten on reruns. A failed draft can be retried; Obtainium ignores drafts.
- Use GitHub's automatic token with Contents write permission in the publication job only. Signing remains in the build job with Contents read permission. The Obtainium PAT is not used by CI.

For maintainers, after validating the desired commit on `main`, publish a new stable version with `git tag vMAJOR.MINOR.PATCH <commit>` followed by `git push origin vMAJOR.MINOR.PATCH` (replace the placeholders). The tag push triggers the build; a tag alone without a successful publication is not an Obtainium update.

## Verification status

The setup follows [Obtainium's source/filter documentation](https://wiki.obtainium.imranr.dev/sources/) and its manual Add App behavior. CI verifies package IDs, labels, version codes and certificate fingerprints before publication. An actual private download and subsequent update on the user's Obtainium installation remain a device acceptance test; publication success alone does not prove those phone steps.
