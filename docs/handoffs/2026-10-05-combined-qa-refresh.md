# Combined QA candidate refresh — 2026-10-05

Branch `codex/acceptance-oct5`, which refreshes [#302](https://github.com/averylicious/muon/pull/302)'s combined application candidate. Integration by Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). This is integration with an author self-check, **not independent review**. The user authorized refreshing the QA candidate, not merging application PRs.

## Commits

- **Start:** #302 head `51493a7a083ebdbb82d88e65b0b064e9759a0aed`.
- **Merged main** `e0c63069c394a60a0d3b6c1a9495bb8bdf7feae2`, recorded as a merge commit.
- **Merged #321** `4a39cd42fb7f75e1de484bdbbb42413513737998` (`codex/card-service-command-admission`), recorded as a merge commit.
- **Correction commit** on top, after the coordinator's integration review (described below), together with this note's update.
- The final head is the branch tip that the coordinator pushes; it isn't recorded here, to avoid a self-referential SHA.

Ancestry is preserved. All three inputs are ancestors of the result.

## Conflicts

**None.** Both merges were textual auto-merges, with nothing resolved by hand. The one semantic gap they left is corrected in a separate commit (next section).

- **Main since the candidate's base** (`12ab1e44`): only tests, CI and docs reached `app/` or the workflows:
  - `ServiceAttachLifetimeTest` and the extended `CardServiceCharacterizationTest`;
  - `ReplayGainSettingsTest`;
  - the Java action and dependency inventory workflow changes and their tools.
  - **Compatibility:** the candidate's `OfflineStore.Store` constructor and `Shelf` (whose new `present` parameter defaults to available) stay compatible with main's fixtures, including store injection through the private `store` field.
- **#321:** adds the guard to `MuonCardDownloadService`, its tests and its report. Production change relative to the start is only `MuonDownloadService.kt`.

## Component boundaries to keep in mind

- **The gap the merges left:**
  - **Source:** #321's guard admitted the seven changing commands when the bound manager was the store's current non-phone card manager, without consulting #302's `Shelf.available()`.
  - **Consequence:** a command already queued while the card was available was still carried out at delivery after the card became unavailable. That held even though #302's senders, its resume and its leftover removal already refuse unavailable cards.
  - **Status:** this was a source-supported gap, not a reproduced device failure.
- **Correction (combined candidate only):** `MuonCardDownloadService.isCardManager` now also requires the store's current card to report `available()`.
  - **Probe:** `Shelf.available()` already treats a throwing probe as unavailable.
  - **Refusal:** a refused command still reaches Media3 as a copied INIT with the original extras, so a foreground start keeps its notification and the delivered intent is unchanged.
  - **Not added:** no helper or manager resets.
  - **No atomic or hardware guarantee:** the probe is a snapshot, and the card can go right after it.
  - **#321 itself:** stays at its own head and QA gate; this correction lives only on the combined candidate.
- **Tests added** (`CardServiceCharacterizationTest`; the fixture's `shelf` helper gains an optional `present` probe that defaults to available, so existing controls keep their behaviour):
  - **All seven refused:** a bound card that was available when the service was created becomes unavailable. All seven changing commands are then refused. The card index (a seeded stopped row, inert downloaders), requirements, stop reason, pause and resume all stay unchanged, and the phone is untouched. When the probe answers available again, the same binding admits a later RESUME. That is not card generation adoption.
  - **Throwing probe:** a probe that throws refuses without crashing.
  - **Foreground:** a refused foreground command for an unavailable card still shows the notification, and the original intent and extras are preserved.
  - **Existing positive controls:** first and recreated available-card admission is unchanged.
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
- `git diff --check` and the CI prose check, including after the correction.

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

**Added for #321 and the availability correction:**
- **Card in:** normal card download, pause, resume, remove and move.
- **No card:** phone downloads stay usable, and card actions never change the phone-only manager.
- **Card made unavailable:** if this is feasible on a disposable card, card actions do nothing and phone downloads are unaffected. This needs a disposable card; don't remove a card holding originals.

Use disposable copies only, with no removal or failure injection on originals. The Pixel lock screen stays deferred. Installing needs a fresh artifact of this exact head; never uninstall or change signing to get around a downgrade.

## Review questions

1. When CI runs, confirm that the merged `CardServiceCharacterizationTest` (#321's version, building on main's controls, plus the three availability regressions) compiles and passes against #302's `Shelf`.
2. Should #321's own PR later carry the same availability clause, or leave it to the combined candidate? That decision stays with the coordinator and user.
