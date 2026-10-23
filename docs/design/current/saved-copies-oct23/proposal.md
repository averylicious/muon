# Saved copies → “On this device”: UI/UX proposal

**Status: layout and direction approved by the user on 2026-10-23; refine spacing during implementation.** This is a mockup and rationale only. Nothing here is implemented, runtime-verified or a statement of current backend behaviour. The user authorized implementing this frontend direction while preserving current playback-cache/artwork behavior. This document does not authorize bypassing backend guards or upgrading dependencies. Fictional songs and abstract covers; no device, network or personal data.

- Interactive prototype source: [proposal.html.txt](proposal.html.txt) (save as `proposal.html` to open locally; demo-state controls sit outside the phone). The static review image below is directly viewable. Keeping source as documentation avoids producing an unrelated APK for this design-only proposal.
- Review image: [overview.svg](overview.svg) (list, open row menu, delete confirmation).
- Inspected source: RC `467ab92885dd2c26735245ebcdedc9b7a4d755e9` (.1343): `SavedScreen.kt`, `SavedEntries.kt`, `SavedMigrationDialog.kt`, `MuonIcons.kt`, and the current-design reference screenshots in `docs/design/current/`. Experiment reference: excerpts in [experiment-reference.md](experiment-reference.md) (`e1bf045c…`), used for visual direction only. Three user screenshots of the current screen were inspected and not copied.
- Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort set by the CLI. Design proposal only. Root GPT-6 / Codex desktop reviewed the source constraints and rendered export, requested the user’s clarified Delete semantics and corrected caption layout, and rechecked the revised artifacts. This is a scoped design/source self-check, not runtime app verification. Exact root model variant and current effort are not reported.

## What the user saw

1. A three-line warning paragraph at the top on every visit, then Previous/Next and Refresh before any song.
2. Rows crowded with Optimize, a check badge and a bin; titles truncated to “If The Sun Burns …”.
3. Optimize offered on a copy that then said it couldn’t be optimized.
4. Remove that silently kept the copy, explained only by a toast about “bytes”.
5. The same song twice (download with cover, played copy without) with nothing explaining why; music-note placeholders that look broken.

## User decision this proposal follows: Delete honours the user’s choice

Deleting a listed copy is a normal action: one confirmation, then the copy is deleted. It is not refused because a copy is unverified against Tauon, and not because of a general doubt about who owns its bytes. Users should never need Android’s Clear data to get rid of a copy.

Only a concrete, actual problem justifies a failure, and each one is shown as an actionable state with Retry:

- the SD card holding the copy is unavailable;
- an I/O error while deleting;
- a path proven unsafe or not Muon’s own;
- an operation on that copy is committing right now and can’t safely be interrupted (retry after it finishes).

“Unverified against Tauon” stays a separate, compact identity note (plays only from this device). It never controls whether Delete is available.

## Proposed name: “On this device”

“Saved copies” suggests everything was deliberately saved and kept. The list actually holds two different things: **downloads** the user chose, and **copies kept from listening** that the played-song cache retains and may later evict. “On this device” is true for both, covers phone and SD card, and makes no promise of permanence. If the user prefers to keep “Saved copies”, the rest still applies.

## Layout

- **Top app bar** (`TopAppBar`): Back, title, one `IconButton` “Refresh list”. No large header block.
- **One compact info line** (tonal, clickable `Surface`): “Plays from this device” with a trailing “Details ›”. It opens a short `ModalBottomSheet` explaining why. This replaces the per-visit paragraph and the word “Unverified” on every row, without dropping the safety meaning.
- **SD card unavailable**: one tonal banner with the SD icon, words and “Try again”.
- **Downloads**, then **Kept from listening** (collapsed by default with “Show”, plus one line of explanation). See backend item 1: the sections depend on proposed source-aware paging and counts. The UI must not group one mixed page as if it were the whole catalog.
- **Rows** (`ListItem`): 52 dp cover, title (1 line), artist (1 line), and an optional third line only when it matters, each with an icon *and* words: “Playing” (bars), “SD card”, “Not deleted · SD card not found” (warning). Trailing content is a single `IconButton` with a vertical three-dot icon, 48 dp, at the far right. Title and artist get the rest of the width.
- **Paging**: a small count footer (“6 on this device”); Previous/Next only when there is more than one page. Existing page size and exact-copy handles unchanged.
- **Placeholder cover**: a tonal square with concentric grooves and a centre dot (an abstract record), replacing the music-note glyph. Decorative; `contentDescription = null`.
- **Mini player and navigation**: unchanged.

Duplicates: each row is still one listed copy. A download and a copy kept from listening of the same song appear in their own sections, and Details names the kind. The UI never merges them, never says they’re identical, and doesn’t search other pages for matches.

## Reusable song-row menu pattern

One trailing `IconButton` (vertical three dots, 48 × 48 dp, `contentDescription = "More options for <title>, <artist>"`) opening a `DropdownMenu` anchored to it. The row body keeps its own tap action. Library and Search can adopt this later; they aren’t changed now. The menu has no permanently disabled items.

| Menu item (`DropdownMenuItem`) | Shown when |
|---|---|
| Play | The copy is complete and playback is ready (same gate as today). |
| Details | Always. |
| Update storage format | Only after the existing fresh eligibility check has **confirmed** it for this copy. Omitted while checking, for current-format copies, when unavailable or unsupported, and for kept-from-listening copies. |
| *(divider)* | |
| Delete download / Delete from device | Always, for every listed app-managed copy. Opens one confirmation. |
| Try delete again | Instead of Delete, after a concrete failure on this copy (for example, card removed mid-delete). |

“Optimize” is renamed **Update storage format**: it makes a new copy in the current format, checks it, then replaces the old one, and needs temporary space. “Optimize” promised a benefit the user couldn’t see.

## Details sheet (`ModalBottomSheet`)

Header with cover, title and artist, then labelled facts:

- **Kept as**: Download (“You chose to keep this.”) or Kept from listening (“Saved automatically. It can make room for newer songs.”).
- **Stored on**: Phone or SD card, with size.
- **Plays from**: This device only (“Muon can’t confirm it still matches the song in Tauon.”).
- **Storage format** (downloads only): Current, or “Older format · update available” only when confirmed.
- **Last delete** (only after a concrete failure): “Didn’t finish. The SD card was removed. Put it back and try again.”

Actions: filled Play, tonal Update format (only when confirmed), outlined Delete (or Try again). Delete is never disabled for a listed copy.

## Copy list

| Where | Text |
|---|---|
| Title | On this device |
| Info line | Plays from this device · Details |
| Info sheet | **Why these play from here** · Tauon can give a song’s number to a different song after its library changes. Muon can’t confirm that a copy here still matches what Tauon has now. · So these play only from this device, and your Tauon library never uses them in its place. You can still play or delete any of them. · [Got it] |
| Sections | Downloads · *n* / Kept from listening · *n* · Show/Hide |
| Listening explanation | Saved automatically while you listened. When the listening cache is full, older ones make room. |
| Row states | Playing · SD card · Not deleted · SD card not found |
| Card banner | **SD card not found** Copies on it are hidden and stay on the card. Put the same card back, then try again. [Try again] |
| Empty | **Nothing on this device yet** Long-press a song and choose Download to keep a copy here. Songs you listen to may also be kept for a while. |
| Delete dialog | **Delete this download?** / **Delete from this device?** “*Title*” will be deleted from this phone (or SD card). Other copies here and your Tauon library aren’t changed. [Cancel] [Delete] |
| Delete failed (snackbar) | Couldn’t delete “*Title*”. The SD card isn’t available. [Retry] |
| Delete failed (other concrete causes) | Couldn’t delete “*Title*”. Storage couldn’t be written. [Retry] · Couldn’t delete “*Title*” yet. Another change to it is finishing. [Retry] |
| Update dialog | **Update storage format?** Muon makes a new copy of “*Title*” in the current format and checks it before replacing this one. It needs temporary space for a second copy. You can follow or cancel it in Settings. [Cancel] [Update] |

## Engineering requirements and backend questions (not UI decisions)

1. **Source-aware paging is new backend work.** Paging today walks one combined catalog. The two sections need either paging per source (downloads, kept from listening) with per-source counts, or a stable catalog order that groups by source. Until that exists, the fallback is one list where each row’s third line says “Download” or “Kept from listening” (icon and words) and there is no collapse.
2. **Delete must honour the user’s choice; this is required work, not current behaviour.** Today `removable == false` hides the bin and shows “Kept: Muon can’t tell its bytes belong to it alone”. The requirement: deleting a selected copy removes that copy’s reference and makes it disappear from the list, while preserving every other valid copy; physical bytes are reclaimed only when no other valid reference still needs them. Shared-byte bookkeeping must therefore be able to drop one reference without deleting shared audio. This is an engineering design task to scope separately; this proposal does not claim it exists.
3. **Concrete failure reasons.** The UI needs a per-attempt result that distinguishes: card unavailable, I/O failure, unsafe or foreign path, and operation still committing. Is the last failure kept per entry, or only reported once? If only once, show the snackbar and refresh the row; don’t persist a claim.
4. **Eviction wording.** Are retained played copies always subject to the played-cache limit? If some are not, change “older ones make room” to match.
5. **Eligibility timing.** The storage-format check currently runs inside the Optimize dialog. Showing the menu item only when confirmed means checking when the menu opens (cancellable, off the main thread, for that one copy). Is that cost acceptable, or should the item live only in Details after an explicit check?
6. **Size.** Confirm the size shown is the copy’s own bytes, not a shared total.
7. **Icon asset.** `MuonIcons` has no vertical three-dot icon; one vector drawable is needed. No new dependency.

## Implementation acceptance checklist

- [ ] Stable Material3 1.4.0 only (`TopAppBar`, `ListItem`, `IconButton`, `DropdownMenu`/`DropdownMenuItem`, `ModalBottomSheet`, `AlertDialog`, `Snackbar`); no glass, blur or alpha APIs.
- [ ] Every row has exactly one trailing three-dot button, far right, with a 48 dp target and a title-specific label; title and artist keep the remaining width.
- [ ] Delete is offered for every listed app-managed copy, asks once, then deletes it. It is never disabled or refused because of unverified identity or generic ownership doubt.
- [ ] Deleting one copy never removes or corrupts another valid copy; shared bytes are reclaimed only when safe (engineering item 2).
- [ ] Only the concrete causes listed above produce a failure, each shown in words on the row or snackbar with Retry; the row stays until a delete actually finishes.
- [ ] No existing data-preservation check is weakened or bypassed to make Delete appear to succeed.
- [ ] “Update storage format” appears only after a confirmed eligibility result for that exact copy; never while checking, and never for current-format, unavailable or unsupported copies.
- [ ] Rows stay one per listed copy (exact handles); no merging by title or artist; no full-catalog deduplication; existing page size and memory paging unchanged. Sections only with source-aware paging (engineering item 1).
- [ ] The “plays only from this device” meaning stays reachable from the info line and in Details, separate from Delete.
- [ ] Every status uses an icon and words; nothing is conveyed by colour alone. TalkBack reads rows, statuses and menu items meaningfully.
- [ ] SD card unavailable, empty, loading and error states match the copy list.
- [ ] Mini player, navigation, queue Undo and the queue rows’ no-blink behaviour are unchanged.
- [ ] Phone UAT in light and dark, with large text, before any claim of completion.

## Approval boundaries and post-RC work

The user approved the name/layout/idea and wants spacing refined in actual Compose. Keep the current backend policy: played copies do not gain automatic artwork downloads in this RC; manual downloads and kept-from-listening remain distinct. This does not cancel the separately required normalization and Delete bug fixes (#645/#646). Keep bounded paging and preserve existing saved bytes/identities.

After the current RC ships, evaluate Compose Material3 1.5.0 Beta on the separate M3E experimental track. Current RC stays on stable 1.4.0. This is a future experiment, not a dependency upgrade or new current release blocker. See [release sequence](../../../release-plan.md#post-release-material3-beta-experiment).
