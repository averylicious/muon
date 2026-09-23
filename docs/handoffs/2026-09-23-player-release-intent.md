# Handoff: player release intent — slow pulls return, deliberate pulls and flicks commit

2026-09-23. Release-intent slice of #43, from the user's Canary .123 QA
(https://github.com/averylicious/muon/issues/43#issuecomment-5790816825, defect 3: thresholds too eager
in both directions). Spring settling and overscroll are the next slice and are not changed here.

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, as frontend owner. Astra (GPT-6,
Codex desktop) coordinates and reviews. Branch `codex/player-release-intent`, worktree
`/home/avery/.codex/worktrees/muon-release-intent/muon`, built on PR #98's head
`01e4932afa7174203f910c5c79dd06dbe27ad617` (**#98 is a prerequisite**; its CI was pending at the time,
Astra's source read found no blocker).

## What changed

One shared, pure policy now decides both releases (`sheetReleaseCommits`), from the finger's own travel
and velocity towards the committing direction — up for opening from the mini player, down for closing
from the top bar:

- **Clear flick towards** commits, but only if the finger travelled at least 24 dp, so a stray jab
  cannot open or dismiss anything.
- **Clear flick away** returns, however far the finger had come: that is a deliberate reversal.
- **Anything slower** commits only past 30% of the sheet's height (never less than the gesture's old
  minimum: 48 dp opening, 96 dp closing). A slow short pull returns to where it started, and a slight
  slow drift back after a long pull does not undo it.

Velocity comes from Compose's `VelocityTracker` (stable in ui 1.9.3; `addPosition`,
`calculateVelocity`, `resetTracking`), fitted over recent movement and zero after the finger pauses, so
the last tiny delta before lifting is not mistaken for a flick or a reversal. It is fed the **finger's
own accumulated travel**, not the pointer's position within the grabber: since #98 the grabber moves
with the finger, so its local position barely changes and would read almost no velocity.

The existing distance rules (`miniDragOpens`, `playerDismissCloses`, `playerDismissCommits`,
`playerPreviewOpens`) are now thin wrappers over the shared policy. Their new velocity and flick
parameters default to "no flick", which reduces them to the old distance check, so every earlier test
still describes the same slow-release behaviour.

## Numbers are starting values

30% of the sheet, 600 dp/s and 24 dp are reasonable starting choices, **not measured optima**. Tuning
them on the device remains pending and is expected.

## Preserved

#98's direction guard and grabber anchoring; preview tokens and Back ownership; same-frame begin/end
protection; cancellation, controller-loss and rapid-reopen settling; predictive Back; the scrim, edges
and accessibility. Baseline and travel are still separate: where the sheet was when the finger took it
over never counts towards committing.

## Files

`PlayerDismissDrag.kt` (shared policy, flick values, dismiss velocity), `MiniPlayerDrag.kt`,
`MiniPlayer.kt` (opening velocity), `SheetReleaseTest.kt` (new), this note.

## Validated vs pending

- `SheetReleaseTest`: slow short pulls return and slow long pulls commit, in both directions; flicks
  commit from short distances; a stray jab does not; a deliberate reversal returns; a slight drift back
  does not undo a long pull; a flick cannot rescue a stale or ineligible gesture; with no flick it is
  distance alone. Policy only — no claims about Compose input, velocity on a real screen, or feel.
- Verified `VelocityTracker`'s API in the pinned ui 1.9.3 sources (`VelocityTracker1D`'s public
  constructor exists, but its default constructor is internal, so the 2D tracker is used).
- No local Android build. CI: triggered by the push, **not inspected** — Astra owns it. Astra review
  and phone QA pending.

## Manual QA

1. Pull the mini player up slowly a short way and let go: the player goes back down.
2. Pull it up slowly past about a third of the screen and let go: it opens.
3. Flick it up quickly from a short distance: it opens. Tap-jab it up very briefly: nothing opens.
4. Pull it up past a third, then flick back down: it returns closed. Drift back slowly instead: it still
   opens.
5. From the open player, repeat 1–4 downwards with the top bar: short slow pulls return open, long
   pulls and real flicks close, a reverse flick keeps it open, a stray jab does nothing.
6. Everything #98 fixed still holds: no dimming on a downward drag from rest, and the grabber stays
   under the finger. Back mid-preview, rapid reopening, queue emptying and disconnect behave as before.
7. Note how the thresholds feel; that is the tuning input for the next pass.

## Status

Clean and idle once pushed. Next: spring settling and resisted overdrag, then list overscroll.
