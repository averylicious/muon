# Checkpoint: Claude's self-merge cycle, 2026-09-25 to 2026-09-26

**Authorization (per cycle, not standing):** Astra was unavailable. The user let Claude Opus 5.5 (`claude-opus-5-5`, Claude Code) review and merge its own PRs, and later reassigned Astra's open work to Claude. Every merge was pinned to the exact head that passed CI, and recorded as a **self-review, not an independent review**. The user expects **a later Astra audit** of everything below. No release tags, no Stable, no device or ADB access.

## Merged (audit list for Astra)

| PR | What | Issues |
|---|---|---|
| #105 | Library list positions and header fold kept across detail pages | — |
| #106 | Slide transitions for the artist and playlist pages | — |
| #107 | AGENTS.md Build and verification | — |
| #110 | Thin passive scroll indicator | — |
| #113 | Artwork disk cache (64 MB, identity-keyed, 90 days) | #111 |
| #114 | Sorting: Songs by Recently added or A–Z, Artists by Most songs or A–Z | #109 (part) |
| #115 | Draggable A–Z fast scroller | #109 |
| #126 | Refined rounded icon set (`docs/design/icons/`), settings from Material Symbols `tune` | #116 (part) |
| #127 | Emphasized title weight, readable credits, album line, sort chevron | #118 #119 #122 #124 |
| #129 | Volume slider, tonal toggles, lyrics bar colour, mini-player play | #117 #120 #121 #123 |
| #130 | Non-debuggable Canary, launcher label "Muon β"; the verifier rejects debuggable APKs | #125 #29 |
| #62 | Queue metadata (Astra's, taken over: main merged, conflicts resolved) | — |
| #131 | Queue screen v1: play order following shuffle, swipe to remove with Undo | #47 (part) |
| #132 | Offline listening mockups (this checkpoint's PR) | #112 (design) |

Main is the latest `0.1.0-canary.*` built from these merges; check the Releases page for the exact number. The user's QA passed through .166, and the release build was smooth. Everything after that is **pending phone QA**.

## Findings worth knowing

- **Canary lag was the debuggable build:** the release build of the same commit was smooth. #130 makes the Canary non-debuggable.
- **Tauon API limits:** `/api1/tracklist` has no dates, play counts or loved status. Tauon *stores* `loved_timestamp` and `modified_time` internally. "Recently added" uses the Tauon track ID (a running counter, confirmed in `t_jellyfin.py`). List thumbnails are 75 × 75 px. `/api1/fileopus` is fixed at 84 kbps.
- **Canary naming:** the launcher shows "Muon β", but release titles and APK names stay "Muon Canary", so Obtainium filters are unchanged.

## Open, in the user's agreed order

1. #47: drag-to-reorder in the Queue (needs stable per-entry keys).
2. #45: album and artist pages (no longer gated by #23, which is closed), then #46 song actions.
3. #48: Material 3 search bar. #128: mini-player swipe for previous and next. #65: artwork swipe style ("surprise me").
4. #112: offline listening, after the user reviews the mockups.
5. Taken over from Astra: #39 and PR #61 (auto-connect via NSD), #53 (partial library load), #26 (backend audit).
6. #116 mockup regeneration, draft #108 (target SDK 37), #97 loudness, #83 baseline profiles, #16 insets audit.

Live status is on issue #40.
