# Combined QA candidate refresh — 2026-10-05

Branch `codex/acceptance-oct5`, which refreshes [#302](https://github.com/averylicious/muon/pull/302)'s combined application candidate. Integration by Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). This is integration with an author self-check, **not independent review**. The user authorized refreshing the QA candidate, not merging application PRs.

## Commits

- **Start:** #302 head `51493a7a083ebdbb82d88e65b0b064e9759a0aed`.
- **Merged main** `e0c63069c394a60a0d3b6c1a9495bb8bdf7feae2`, recorded as a merge commit.
- **Merged #321** `4a39cd42fb7f75e1de484bdbbb42413513737998` (`codex/card-service-command-admission`), recorded as a merge commit.
- This note is committed on top. The final head is the branch tip that the coordinator pushes; it isn't recorded here, to avoid a self-referential SHA.

Ancestry is preserved. All three inputs are ancestors of the result.

## Conflicts

**None.** Both merges were textual auto-merges, with nothing resolved by hand and no follow-up correction commit.

- **Main since the candidate's base** (`12ab1e44`): only tests, CI and docs reached `app/` or the workflows:
  - `ServiceAttachLifetimeTest` and the extended `CardServiceCharacterizationTest`;
  - `ReplayGainSettingsTest`;
  - the Java action and dependency inventory workflow changes and their tools.
  - **Compatibility:** the candidate's `OfflineStore.Store` constructor and `Shelf` (whose new `present` parameter defaults to available) stay compatible with main's fixtures, including store injection through the private `store` field.
- **#321:** adds the guard to `MuonCardDownloadService`, its tests and its report. Production change relative to the start is only `MuonDownloadService.kt`.

## Component boundaries to keep in mind

- **#321 checks identity, not availability.** It admits the seven changing commands only when the service instance's bound manager is the store's current non-phone card manager (`OfflineStore.current()`, which the candidate keeps). It doesn't consult #302's `Shelf.available()`.
  - **The two cover different things:** a present-but-unavailable card's commands are still carried out at delivery, while #302's own sender checks still refuse new card commands while the card is unavailable.
  - **Follow-up, not done here:** adding the availability clause is a separate change.
- **#321 doesn't fix:**
  - the fallback resume when the card service is first created;
  - Media3's internal restarts and notification work;
  - generation teardown;
  - catalog, reader or cache preservation.
- **Unchanged:** main's attach-lifetime and thread-join controls, and the candidate's fixes (startup executor, metadata budget, artwork, network, storage, played copies, moves) are untouched by these merges.
- **Still open:** #179, #213, #230 and #253.

## Checks

**Run locally on the merged tree:**
- the CI policy tests (71 OK);
- the dependency inventory, SDK tool inventory and release tool tests (OK);
- `git diff --check`.

**Not run:** no Gradle, Kotlin compile, Robolectric or device run. The Android APKs workflow will be the first compile and test run of the merged head, once the coordinator has reviewed and pushed it. No CI or device result is claimed here.

## Remaining user QA (pending user testing)

The candidate's existing checklist still applies:
- reconnect, LAN and network permission;
- queue duplicates, Undo, library scroll and sheet cancellation;
- download, startup, offline and copy accounting;
- safe moves with a mounted card, on disposable copies;
- played-copy cancellation and budget;
- metadata, artwork identity and decode limits;
- normal notification, transport, headset and Bluetooth.

**Added for #321:**
- **Card in:** normal card download, pause, resume, remove and move.
- **No card:** phone downloads stay usable, and card actions never change the phone-only manager.

Use disposable copies only, with no removal or failure injection on originals. The Pixel lock screen stays deferred. Installing needs a fresh artifact of this exact head; never uninstall or change signing to get around a downgrade.

## Review questions

1. Should #321's availability clause wait for #302 to be accepted, or come as a follow-up on this candidate?
2. When CI runs, confirm that both `CardServiceCharacterizationTest` versions merged as intended. The merged file is #321's, which builds on main's controls.
