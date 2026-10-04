# UI overlay focus and semantics boundary — 2026-10-05

Inspected main `fa6d47fba0ee131f1860fb087154877401c855fc`, branch `codex/ui-focus-audit`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading by the author, **not independent review**. Documentation only: no app, build or test change, and no device, TalkBack, keyboard or screenshot evidence. Compose BOM `2025.10.00` (`app/build.gradle.kts` 78). The author did not check Compose sources. Coordinator follow-up below checks the pinned published sources; device timing, native focus and accessibility results remain unverified.

**Question:** while the player, lyrics or queue is shown, or is previewing or exiting, do hidden controls keep actionable semantics, touch or focus? Do gesture-only controls have accessible alternatives? Are the volatile-state guards coherent?

**Files read:** `MuonApp.kt` (overlay state 207-262, tabs 356-420, panel, overlays and hosts 607-773), `MiniPlayer.kt` (50-244), `LyricsScreen.kt` (42-119), plus targeted greps of `QueueScreen`, `NowPlayingScreen`, `SongActions`, `SearchScreen` and `PlayerPanel`. #302 (`ee4871281deecbde722f8ca4c199f7cf29b43d02`) already changes queue stamps, Undo, insertion and the offline list reset in `MuonApp`/`QueueScreen`/`SongActions`. Those are pending fixes, not repeated here.

## Confirmed from source (no defect)

- **Tabs hidden behind overlays:** the whole `Row` holding the rail, scaffold, tabs, mini player and sideways `PlayerPanel` gets `clearAndSetSemantics {}` while `overlayOpen || sheet.previewing` (`MuonApp.kt` 368-369). The mini player's own gesture detector isn't touched, so a preview in progress continues.
- **A closing or previewing player:** `PlayerHost` clears the content's merged accessibility semantics and adds a non-semantic new-touch cover whenever it isn't logically open (769-770). That covers both the closing slide and the preview carried up from the mini player; it does not establish hardware focus removal or cancellation of already captured pointers. `PlayerScrim` blocks touches and adds nothing focusable (650-653, 719-724).
- **Back:**
  - One `backTarget` decision feeds both handlers (233-258).
  - A preview takes Back first and only cancels itself (262).
  - A held predictive gesture commits only to lyrics, queue or player (`playerGestureCommits`, 709-710).
  - The drag's collapse is guarded by `playerShown` (666).
- **Gesture alternatives:**
  - **Mini player:** tap opens with the label "Open Now Playing" (`MiniPlayer.kt` 65). Swipe-previous has a `"Previous track"` custom action (66-69), and swipe-next has a visible button (240). The upward drag duplicates the tap.
  - **Queue:** reorder and remove have the custom actions "Move up", "Move down" and "Remove from queue" (`QueueScreen.kt` 316-321).
- **Lyrics load:** keyed on the media ID and the retry attempt. It rethrows cancellation, so a song change doesn't inherit an old result (`LyricsScreen.kt` 49-74).
- **Mini preview:** `playerPreviewOwned` ties each release and teardown to its own preview generation (`MiniPlayer.kt` 134-209).

## Findings (low severity; none confirmed on a device)

1. **Lyrics and queue lack logical-close action guards while exiting.**
   - **Trigger:** close lyrics or queue (Back or its back button), or the queue empties.
   - **Where:** `FullScreenOverlay` (`MuonApp.kt` 733-741) wraps the content in `AnimatedVisibility`. Unlike `PlayerHost` (769-770), it neither clears semantics nor covers touches when `visible` turns false.
   - **Expected:** consistent with `PlayerHost`, an exiting surface isn't usable.
   - **Source-confirmed lifecycle:** pinned `AnimatedVisibility` keeps exiting content composed; this wrapper does not remove its content semantics or new pointer hit targets at logical close. Zero-alpha content is hidden from accessibility by Compose, so this is a window before transparency/disposal, not a claim that every exiting frame is exposed.
     - **What else is live meanwhile:** for about the `motionMedium` duration (`Motion.kt` 28), the re-presented player becomes active under it. When the player itself is closing (`overlayShouldClose`, 207-210), the library's tabs come back instead.
     - **What can be activated:** an exiting queue row or lyrics Retry can still be activated.
   - **Impact:** a brief double set of TalkBack targets, and taps on a closing surface. Queue actions still go through the live player and its guards, so no data loss is suggested.
   - **Confidence:** high for retained composition and the missing logical-close guard after the pinned-source check; actual TalkBack exposure, timing and interaction remain unverified.
2. **The mini player's error text promises a tap that doesn't retry.**
   - **Where:** `MiniPlayer.kt` 227 shows "Playback interrupted · tap to retry".
   - **Actual:** a tap opens Now Playing (65, `MuonApp.kt` 402-405), where "Retry stream" calls `prepare()` then `play()` (`NowPlayingScreen.kt` 152). The mini play button only calls `play()` (`MuonApp.kt` 406).
   - **Unverified:** whether Media3's `play()` alone recovers from an error state wasn't checked in pinned source.
   - **Impact:** the wording is misleading, and retry takes two steps.
   - **Confidence:** high for the wording; unverified for play-button recovery.
3. **Stale comment:** `FullScreenOverlay`'s KDoc says "Lyrics uses this" (`MuonApp.kt` 729), but Queue does too (673-675). A documentation nit only.

## Questions (not established from source)

- **Hardware keyboard or D-pad focus:** `clearAndSetSemantics` removes accessibility semantics but, as far as the call structure shows, not focusability. Can Tab or arrow keys reach the composed tabs, rail or library behind an open player or overlay? Nothing in scope sets `focusProperties` or a focus group. Unknown until a keyboard is tested.
- **Pane announcements:** TalkBack traversal order, and whether the player, lyrics and queue are announced as panes. A missing `paneTitle` alone isn't treated as a defect.
- **IME across overlays:** whether Search's keyboard stays up when a mini-player tap or preview opens the player from Search. `SearchScreen` hides it only when leaving for a page (43-46).

## Proposals (separate from findings; not made)

- Give `FullScreenOverlay` the same closing treatment as `PlayerHost`: clear semantics and add the non-semantic touch cover while it is not `visible`. Do it in its own app PR with QA; no colour-only cue is involved.
- Make the mini player's error line say what a tap does (for example, open the player to retry), or make the tap retry. That is a behaviour decision for the user, not made here.

## Manual checklist (pending user testing, phone evidence blocked here)

- **TalkBack:**
  - With Now Playing, lyrics and queue each open, swipe through everything: no library, tab or mini-player items.
  - Close lyrics or queue and swipe immediately: does focus land on the exiting screen?
- **Hardware keyboard:** with the player open, does Tab or the arrow keys reach anything behind it?
- **Playback error:** in the mini player's error state, does a tap open Now Playing rather than retry? Does the mini play button resume, or does only "Retry stream" work?
- **Rail:** sideways with a rail, open lyrics from the panel; Back returns through Now Playing.

**Checks run locally:** `git diff --check` and the CI prose check. #179, #213, #230 and #253 are unaffected.

## Coordinator pinned-source review

GPT-6 / Codex desktop, effort not reported independently reviewed author eb20d15c13958cf2ee1a8178044f33edeb4674a3 against main cee7864fcf6b44436d1b739cf88d364b376a65f5 (app source unchanged from fa6d47f). These qualifications are the coordinator's own documentation edits, self-reviewed.

BOM `2025.10.00`'s published Google Maven POM resolves animation and UI to `1.9.3`. Published source JARs, read as ZIP/text without execution:

- [animation-android sources](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.9.3/animation-android-1.9.3-sources.jar), SHA256 `8b6272d8af64354c29e237c850400776d04c3553536745d3faf99317f71f238c`.
- [ui-android sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.9.3/ui-android-1.9.3-sources.jar), SHA256 `9fa260e2e6d2a8e312103a4409ffe8f0ec93cab07bb9c682c03347a9c71ad8ee`.

`AnimatedVisibility.kt` 668–777 keeps content through exit until `PostExit`. `EnterExitTransition.createModifier` applies geometry/graphics, no logical visibility semantics clear. `SemanticsOwner.kt` 136–143 and `NodeCoordinator.isTransparent` hide accessibility at alpha zero; that does not provide an immediate logical-close gate. `InnerNodeCoordinator.kt` 208–237 stops sibling pointer hit paths at a non-sharing top hit, supporting Muon's existing last-child cover pattern for new touches. `SemanticsModifier.clearAndSetSemantics` explicitly clears descendants in the merged semantics tree; it is not a hardware focus primitive.

The mini-player's tap callback opens the player; wording can be corrected without changing retry behavior or deciding whether `play()` recovers. No Media3 recovery claim is made. A separate application fix is being prepared with user QA pending. This report does not establish keyboard focus, screen-reader traversal, active-pointer cancellation or runtime visual behavior.
