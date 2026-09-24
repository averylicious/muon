# Handoff: keep shuffle and repeat across restarts (#93)

2026-09-24. Backend slice for issue #93, delegated by Astra to Claude with an explicit file-ownership
exception: `PlaybackService.kt` plus a small preferences helper, its test and this note. No gesture or
UI change. Claude Opus 5.5 (`claude-opus-5-5`), Claude Code CLI, medium effort, implementing; Astra
(GPT-6, Codex desktop) keeps backend review responsibility, CI and merge.

Branch `codex/playback-mode-preferences`, worktree `/home/avery/.codex/worktrees/muon-playback-modes/muon`,
based on main `ac7a345427bd0e4ce14c9c94bd69035d6669276e` (not on the gesture branches).

## What changed

- `PlaybackModePreferences.kt` (new): `PlaybackModes` (Media3's defaults: shuffle off, repeat off),
  `repeatModeFrom` (any stored value other than One or All reads as Off), and
  `restoreAndPersistPlaybackModes`, which puts the stored modes on the player **and only then** starts
  listening. `PlaybackModePreferences` keeps them in their own `"playback"` preferences file, like
  `"appearance"` and `"library"`; Stable and Canary are separate apps, so each keeps its own.
- `PlaybackService.onCreate` restores the modes right after building the player and **before building
  the media session**, so no controller — the app, the notification, or anything else — ever sees the
  defaults. It listens on the player itself (`onShuffleModeEnabledChanged`, `onRepeatModeChanged`), so a
  change through any supported route is saved at the moment it happens, not at shutdown.

## Why restore-then-listen, and one mode per save

Restoring before the listener exists means the restore is never saved as if the user changed a mode.
Media3 may still deliver the restore's own change events after the listener is attached; those carry
the restored values, so saving them writes back what was already stored. Each event saves only the
mode it concerns: a handler that saved both could write one mode's still-default value over the other's
saved value.

## Robustness and limits

- A wrong-type value (from an older build or a damaged file) reads as the default instead of crashing
  the service (`ClassCastException` caught); an unknown repeat integer reads as Off.
- Saves use `apply()`, as the app's other settings do: in memory at once, written to disk shortly after,
  off the main thread. **A process killed within that short window — for example a force-stop
  immediately after tapping a mode — can lose that last change.** Nothing here guarantees more than
  that; the tests use an in-memory store and prove ordering and values, not disk durability.
- Out of scope, as the issue asks: no queue, shuffled order, track or position restore; no autoplay;
  no boot receiver. A force-stopped or rebooted app restores the modes when the user next opens it.

## Tests

`PlaybackModePreferencesTest`, with in-memory preferences whose getters cast as Android's do:
- a fresh install uses the defaults;
- every shuffle value × repeat mode survives a restart;
- unknown repeat values and wrong-type values fall back safely;
- the stored modes are on the player before anything listens, and the restore rewrites nothing;
- restore events delivered late write back only what was stored;
- changing one mode never overwrites the other;
- changes are saved as they happen.

No local Android build. CI: triggered by the push, **not inspected**. Astra review and phone QA pending.

## Manual QA (user)

1. Set shuffle on and repeat One; swipe Muon away from recents, reopen: both still set.
2. Set shuffle off and repeat All; force-stop Muon in Settings, reopen: both still set. (If a mode was
   changed in the very last moment before force-stop, note it — see the durability limit above.)
3. Reboot the phone, open Muon: both modes as last set. Nothing starts playing by itself.
4. Change repeat from the notification or a Bluetooth control, then reopen Muon: the change was kept.
5. Reconnecting the app to the service (leave and return) does not reset either mode.
6. A fresh install (or cleared data) starts with shuffle off and repeat off.

## Status

Clean and idle once pushed. Not starting another task.
