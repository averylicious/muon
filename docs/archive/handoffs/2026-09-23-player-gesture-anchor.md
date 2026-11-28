# Handoff: player gesture correctness — direction and grabber anchoring

2026-09-23. Gesture-correctness slice of #43, from the user's Canary .123 QA recorded in
https://github.com/averylicious/muon/issues/43#issuecomment-5790816825 (defects 1 and 2). Release
threshold tuning (defect 3) and spring/overscroll polish are the next slices and are not changed here.

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, as frontend owner. Astra (GPT-6,
Codex desktop) coordinates and reviews. Branch `codex/player-gesture-anchor` from main
`fbc58e1ad8fce6a6e90e3b19d1ce5ee8f9d3796c`, worktree `/home/avery/.codex/worktrees/muon-gesture-anchor/muon`.

## Causes, verified against source

The pinned `detectVerticalDragGestures` (foundation 1.9.3, `DragGestureDetector.kt:529-558`) calls
`onDragStart` once touch slop is crossed **in either direction**, then reports the signed over-slop as
the first `onVerticalDrag`. The slop distance itself is never reported.

1. **A downward drag from rest dimmed the library.** The mini player began its preview in
   `onDragStart`, before the direction was known, so `previewing` turned on the scrim and cleared the
   library's semantics even though the sheet never moved. The preview now begins only at the first
   move whose accumulated travel is upward (`playerPreviewMayBegin`). A drag that dips down and then
   rises begins when the finger comes back above where it started.
2. **The grabber slid out from under the finger.** Not animation latency. #90's inset reclaim lifts
   the player's content inside the sheet by `min(drop, topInset)` so no blank status-bar band shows
   above the handle — but the sheet's edge followed the finger exactly, so the content, and the
   grabber, lagged the finger by up to the whole top inset, and that lag grew precisely while the
   corners were appearing. The edge is now drawn lower by the same reclaimed amount
   (`playerSheetEdgeDrop`): the content moves exactly with the finger from the first pixel, and the
   edge still closes the blank inset above it, now by descending slightly faster for the first
   inset's worth of travel. The sheet's single position owner is unchanged; only how its edge is drawn
   changed, and corners, shadow and the pure black outline follow that drawn edge.

Remaining constant offset, deliberately left: the touch slop (a few dp, below a fingertip) is never
reported by the detector, so the grabber sits one slop behind where the finger first went down.
Compensating for it would jump the sheet by one slop the moment a drag is recognised.

## Files

- `app/src/main/java/dev/avery/muon/MiniPlayer.kt` — the preview begins on upward travel only.
- `app/src/main/java/dev/avery/muon/MiniPlayerDrag.kt` — `playerPreviewMayBegin`.
- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` — `playerSheetEdgeDrop`; the sheet layer
  takes the window insets and draws its edge there.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — passes `WindowInsets.safeDrawing` to the layer.
- Tests in `MiniPlayerDragTest.kt` and `PlayerDismissDragTest.kt`.

## Preserved

Touch slop (the detector is unchanged), Back ownership and preview tokens, same-frame begin/end
protection (`SheetTurn`), cancellation and controller-loss settling, rapid reopening, predictive Back,
the release thresholds, scrim, rounded edge, pure black outline and accessibility. A drag that never
goes upward now begins nothing, so it has nothing to cancel or settle.

## Validated vs pending

- Policy tests: a drag down from rest begins nothing; one that dips then rises begins only once it is
  above its start; the grabber's displacement equals the finger's at every point, including across
  the end of the inset; the edge closes the inset gap and simply follows the finger with no inset.
  These do not verify Compose input or rendering on a device.
- No local Android build (no Gradle cache or installed `platforms;android-36`); the repository's
  Python checks cover CI scope, not this code. CI: triggered by the push, **not inspected** — Astra
  owns it. Astra review pending. Phone QA pending.

## Next

Release-intent tuning (defect 3: distance/velocity and reversal), then spring settling and resisted
overdrag, then list overscroll — each its own slice. Defect 4 (Back during a held drag) remains a QA
note about system gesture arbitration, not changed here.

## Status

Clean and idle once pushed.
