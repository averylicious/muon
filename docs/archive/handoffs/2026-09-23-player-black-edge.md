# Handoff: outline the dragged player on pure black, and a plain handle

2026-09-23. Visual and accessibility polish on #43, following PR #88
(`docs/handoffs/2026-09-23-player-sheet-separation.md`).

## Ownership

Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort as requested (not independently
observable from inside the session), as frontend implementer. Astra (GPT-6, Codex desktop)
coordinates, owns CI and review. No other agent edited this branch.

- `app/src/main/java/dev/avery/muon/PlayerDismissDrag.kt` — the edge policy and drawing.
- `app/src/main/java/dev/avery/muon/MuonApp.kt` — passes the outline colour on pure black only.
- `app/src/main/java/dev/avery/muon/NowPlayingScreen.kt` — the handle is a painted `Box`.
- `app/src/test/java/dev/avery/muon/PlayerDismissDragTest.kt` — two edge policy tests.

## Branch and base

- Branch `codex/player-black-edge`, worktree `/home/avery/.codex/worktrees/muon-black-edge/muon`.
- Base: PR #88 head `4983bc5db3281a83248e12c8b60ba7c138947b1f` (Astra reviewed, no blocker; run
  112 passed; phone QA pending). **#88 is a prerequisite**, stacked on #87, #86 and #85. The commit
  carrying this note is the whole slice.

## What changed

- **Pure black edge.** #88 left one known gap: on pure black the player and library are both black,
  so neither the scrim nor a shadow separates them. The drag layer now traces the player's top edge
  in `outlineVariant`, only when the theme's background is true black and only while the player is
  displaced. It follows exactly the same corner radius the layer clips to, is drawn half a stroke
  inside on a concentric radius so the clip cannot shave it, and covers only the top edge and its
  corners — the sides are the screen's own edges. It is read in the draw phase only.
- **Handle.** #86's decorative handle was a non-clickable `Surface`, which added an empty
  traversal-group node for TalkBack and a hit target of its own. It is now a `Box` painted with the
  same 32×4 dp size, `RoundedCornerShape(50)`, `onSurfaceVariant` colour and centred position. The
  drag region is the bar around it, so touch behaviour is unchanged.

Light and dark themes are unchanged: no outline is passed there, because the scrim and shadow from
#88 already separate the player.

## Validated vs pending

- Two policy tests: an open player on pure black keeps no outline at rest, and only pure black gets
  one. These are the two requirements for this slice; the drawing itself is not mirrored in tests.
- Read the diff. **No local build** — no Gradle cache and no installed `platforms;android-36`.
- CI: triggered by the push, **not inspected**; Astra owns final-head CI and review.
- Astra review: pending. Phone QA: pending.

Limitations:

- If a predictive Back gesture and a top-bar drag happen **together**, the outline stays at the drag
  layer's unscaled bounds while the Back layer shrinks the content inside it. Neither alone shows
  this; predictive Back alone draws no outline, because it does not displace the drag layer.
- The 1 dp stroke and `outlineVariant` colour are untuned on a real pure black device.
- Pure black is detected as `background == Color.Black`, which is exactly what `MuonTheme` sets for
  that mode; a future theme with a black background would get the outline too.

## Status

Clean and idle once pushed. Stop after this slice; no next feature.
