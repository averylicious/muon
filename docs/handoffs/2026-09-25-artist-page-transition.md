# Handoff: transition into and out of an artist's songs

2026-09-25. Second of two user-requested frontend slices for this cycle. Astra is unavailable for
review, so the PR stays open for Astra. Implemented by Claude Opus 5.5 (`claude-opus-5-5`) in
Claude Code; effort not reported for this cycle.

Branch `codex/artist-page-transition`, worktree
`/home/avery/.codex/worktrees/muon-artist-transition/muon`, **stacked on #105**
(`codex/artist-list-scroll`, now `48dad8c` after its QA follow-up, merged into this branch). Both slices edit the same Library branch in `MuonApp`, so
two main-based branches would conflict. The transition also keeps the list composed only while it
animates, so returning to the same position still depends on #105's hoisted list state. Merge #105
first. Until then, the PR diff also shows #105; this slice's own commit is `ef167fa`, plus the merge
commit that brought in #105's follow-up and resolved the one expected conflict in `MuonApp`. #106's
animated Library block kept its shape, and #105's three call-site changes were applied to it: the
header state, the Songs list state and the Playlists list state.

## Conventions found

- Tabs are siblings and cross-fade with a small lift (`AnimatedContent` in `MuonApp`). The player is
  an overlay with its own predictive Back (`PlayerBack.kt`). Motion is `motionShort`/`motionMedium`
  with Material's standard easing (`Motion.kt`).
- Opening a playlist or an artist swapped pages instantly with an `if`. Back is one decision
  (`backTarget`) read by the `BackHandler`: Lyrics, then Player, then Tab, then the detail page.
- `targetSdk` is 36, so on Android 16 and later predictive Back is on by default. The player's
  `PredictiveBackHandler` receives gesture progress; the detail pages' plain `BackHandler` acts only
  when Back is committed.
- Compose follows the system animation scale (`WindowRecomposer` feeds `ANIMATOR_DURATION_SCALE`
  into `MotionDurationScale`, checked in ui 1.9.3). With animations off, these transitions finish at
  once.

## What changed

- `LibraryPages.kt` (new):
  - `LibraryPage` is Top, Playlist(id) or Artist(key): identity only, with data read live.
    `AnimatedContent` decides a child's visibility by state equality (checked in animation 1.9.3), so a
    page carrying its songs would replay its entrance on every refresh.
  - `libraryMotion`: going deeper is Forward, coming up is Back, and the same depth is Across.
  - `libraryPageTransform`: Material shared axis X. The page slides a fixed 30 dp while fading, and
    `Start`/`End` follow the layout direction. Same depth only cross-fades.
  - `Modifier.leaving`: a page on its way out takes no touches and leaves the accessibility tree.
  - `keptWhileLeaving`: a closing page keeps showing what it showed. It is never used while the
    page is on show, so a live page waiting for a regroup cannot show old songs.
- `MuonApp`: the Library tab's three pages sit in one `AnimatedContent` keyed by page. The playlist
  page moves the same way as the artist page: it is the same list-to-detail step, and leaving it
  instant would be the odd one out. Back handling is unchanged. Back is still decided from the
  logical state, never from the animation, so Now Playing and Lyrics keep priority.

## Predictive Back: evaluated, not added

A predictive preview would need the Artists list composed beneath the page during the gesture, and
the page driven by gesture progress (`SeekableTransitionState`, or a shrink like the player's). It
would also need the cancel, reversal and re-entry handling the player needed across several review
rounds, and a second `PredictiveBackHandler` ordered against the player's. None of this is needed for
a clear transition. With predictive Back enabled, the app already owns the gesture on this page, so
there is no system preview, and a cancelled gesture does nothing because the handler never runs.
This matches the Tab target. The main thing lost is the "peek" at the list before committing. If
wanted, it is best built when #45 brings real artist pages, together with the back stack the
existing comment in `MuonApp` anticipates, rather than on this interim page.

## Edge cases

- **Quick navigation:** Back during the opening reverses it smoothly. `AnimatedContent` retargets
  the list that is still composed, and the direction rule gives the reverse motion. A second tap on
  the leaving list is consumed, so it cannot open another artist.
- **Cancelled gesture:** nothing moves, because nothing ran.
- **A waiting artist page** (grouping pending) animates in like any other and shows its loading rows.
  A discarded selection (server change, artist removed) animates back to the list.
- **Disconnect** leaves through the tab-level transition to the connect screen, as before.

## Tests

`LibraryPagesTest`:
- page precedence (playlist, then an open or waiting artist, then the list);
- stable identity: a refresh is the same page, and playlist and artist keys never collide;
- the direction rule, including a quick reversal and same-depth cross-fades;
- the slide distance: fixed, signed and clamped.

Not covered by unit tests: the animation itself, input blocking and the RTL direction. These are
Compose runtime behaviour, with no Compose UI tests and no device use in this repo, so they are left
to CI's compile, lint and manual QA.

## Manual QA (user)

1. Artists → tap an artist: the page slides in slightly from the right while fading. The Back button
   and a completed system Back gesture slide it back the other way. The list is where you left it
   (#105).
2. Start the system Back gesture on the artist page and cancel it: nothing moves or changes.
3. With the player, then Lyrics, open over an artist page: Back closes Lyrics, then the player, and
   only then the artist page, with its transition.
4. Tap an artist and press Back immediately, or tap twice quickly: no second artist opens, and the
   motion reverses smoothly.
5. Playlists behave the same way.
6. Developer options → Animator duration scale off (or Remove animations): pages switch instantly. At
   5x: the same motion, slowed.
7. Pull to refresh on an artist page: no re-entrance animation when the songs update.
8. TalkBack: after opening or closing, focus lands on the new page only.

## Status

Clean and idle once pushed. Both slices done. Neither PR is merged; Astra review and phone QA are
pending.
