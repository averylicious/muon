# Saved copies → “On this device”: frontend checkpoint (2026-10-23)

Branch `codex/saved-copies-ui-oct23`, base `467ab92885dd2c26735245ebcdedc9b7a4d755e9` (RC .1343). Claude implementation integrated with GPT-6 source corrections. Check the PR head for the exact commit and latest CI before resuming. Implements the user-approved proposal (docs/design/current/saved-copies-oct23 on the proposal branch). Frontend only.

**Not run:** no local Android build, unit test, lint or device check. CI is the first compile. No phone UAT. Nothing here fixes #645 (normalization) or #646 (deletion of shared or unverifiable copies).

## Files

- `app/src/main/java/dev/avery/muon/SavedScreen.kt`: screen rewritten.
- `app/src/main/java/dev/avery/muon/SavedPresentation.kt` (new): page sections, collapse rule, storage-update eligibility, row text.
- `app/src/main/java/dev/avery/muon/SavedMigrationDialog.kt`: title and copy only (“Update storage format?”, “Update”); check and start calls unchanged.
- `app/src/main/java/dev/avery/muon/MuonIcons.kt`: six new kinds: `more`, `info`, `sd-card`, `history`, `storage-update`, `refresh`.
- `app/src/main/res/drawable/ic_more_vert.xml`, `ic_info.xml`, `ic_sd_card.xml`, `ic_history.xml`, `ic_storage_update.xml`, `ic_refresh.xml` (new). Registered in `docs/design/icons/muon_icons.py`; existing icons were not regenerated.
- `app/src/test/java/dev/avery/muon/SavedPresentationTest.kt` (new).

## Behaviour

- Title “On this device”; Back (when opened from Settings) and a 48 dp Refresh icon button with a label.
- One compact info line, “Plays from this device · Details”, opening a short sheet on why copies play only from here. The per-row “Unverified … copy” wording is gone from this screen; `SavedEntry.subtitle()` and queue metadata are unchanged.
- Downloads, then Kept from listening (collapsed at first, with a Show/Hide toggle and one line of explanation).
- Rows: 52 dp cover, one-line title, one-line artist, and one optional status line in words with an icon (Now playing, SD card, Waiting to save, Saving…, Saving failed, Incomplete, Kept after restart). The current row keeps its tonal background and also says “Now playing”.
- One far-right 48 dp ⋮ per row, labelled “More options for <title>, <artist>”, opening a `DropdownMenu`:
  - **Play**: enabled under the same readiness gate as row tap.
  - **Details**.
  - **Update storage format**: only after `freshMigrationAvailable` for exactly that copy returns true. The check runs off the main thread when the menu opens and is cancelled when it closes. A failed check is false. The dialog checks again.
  - **Delete download / Delete from device**.
- **Delete** is always offered for a listed copy; it is never hidden for being Unverified or for `removable == false`. It is disabled only under the old list-level guard (loading or list error). Confirmation then calls the existing `remove` callback; The backend still decides: an unverifiable or shared copy is still refused with the existing notice until #646 is engineered.
- **Details sheet**: cover, title, artist, Kept as, Stored on (phone/card · size), Plays from (this device only), Status; Play and Delete buttons.
- **Placeholder**: an abstract record drawn in Compose, used only for saved rows without their own cover. Global `Artwork` fallback unchanged; no new covers fetched.
- **Unchanged**: card-unavailable banner (shorter copy), Preparing playback with Cancel, busy progress, error text and empty state. Mini player, navigation, playback, queue, admission, paging callbacks and exact handles are untouched.

## Bounded per-page sections (compromise)

The catalog still pages every copy together, in its own order, `SAVED_PAGE_SIZE` at a time. The screen groups only the page it was given; it doesn't load other pages, add indexes or sort across pages.

- **Single page:** the sections exactly match the mockup, with counts that are the true totals.
- **Several pages:** a page bar above and below the list reads “On this page: 51–100 of 120”, with Previous/Next. Section counts then describe that page only. No global per-source count is implied.
- **Collapse can't hide a page's rows:** Kept from listening opens automatically, with no toggle, when a page has no downloads or when the playing copy is in it. Its header and count always show when the page has listening copies.
- **Queue order:** playing a row still queues in the underlying catalog order (`prepareSavedPlayback`). It isn't reordered to match the sections.

Global source-aware paging and counts remain proposed backend work, not done here.

## Tests added (not run)

`SavedScreenPresentationTest`:

- a page splits by source, keeping catalog order within each section, with every copy kept exactly once;
- page scoping happens exactly when more than one page exists;
- listening collapse never hides a page's only rows or the playing copy;
- storage update is offered only on a confirmed true for that copy: no check runs for listening, incomplete, missing-card or no-storage copies, and a failure is false;
- cancelling before the check runs means it never runs, and a cancellation from inside is rethrown.

No Compose UI test exists for row/menu labels or 48 dp targets; those need TalkBack UAT.

## Integration and remaining gates

- GPT-6 preserved all baseline presentation tests, added the new cases in a separate file, fixed cancelled sheet-hide actions, made sheets scroll at larger fonts, stabilized storage-check effect keys, and renamed the Settings entry.
- Removal still calls the existing backend. #646 must resolve the actual ownership refusal; the design does not clear it.
- #645 root cause and follow-up are recorded in [its maintained state](../audits/issues/645.md). Missing tags/readiness/reminder changes now accompany this UI candidate; exact-head CI and notice UAT must be recorded before calling them verified.
- Counts and grouping remain per page; queue ordering stays in catalog order. No artwork fetching or storage engine/schema changed.
- Material3 stays on the pinned Stable dependency. The post-RC Beta evaluation is documented in release-plan.md, not part of this RC.

## Attribution

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort selected by the user (the runtime doesn't report effort). Implementation only; no independent review, compile or device verification yet.

GPT-6, Codex desktop: integration fixes and source self-check; exact variant/effort not reported. This is not an independent whole-app audit.

## Normalization follow-up integrated by GPT-6

The user explicitly approved a future untagged-song notice and requested Opus UI/UX advice. Opus 5.5 High recommended a Snackbar; root used concise text + Details, corrected the desktop tagging wording, bounded recent-ID suppression to 64, and added a player-overlay host so it cannot hide behind the player. Current selected-audio state is transient; no per-song missing-tag preferences are added. The fresh first-song test passed immediately after the user's requested tagging, at 0/−12/0 dB. No fabricated audio-hearing claim. Latest implementation CI pending; #646 remains open.

Claude is idle, with latest emitted quota 95% five-hour / 14% weekly used; do not treat this snapshot as future availability. Root has not requested another Claude task.
