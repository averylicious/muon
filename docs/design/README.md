# Muon design assets

This directory separates what the app draws today from proposals, so coding and design agents always know which is which:

- [`current/`](current/README.md) — **Real device screenshots** of the currently implemented app (Pixel 8, Gen 3 — M3 Expressive, frozen RC `.1420`). Always treat `current/` — then the Compose code — as ground truth for the existing UI. **Never place unimplemented proposals or drawn mockups inside `current/`.**
- [`icons/`](icons/README.md) — **Active vector icon generator** (`muon_icons.py`) that produces `app/src/main/res/drawable/ic_*.xml`.
- [`gen-4-progressive-blur/`](gen-4-progressive-blur/README.md) — **Proposal / target mockups** for Gen 4 - Progressive Blur (plan: [#796](https://github.com/averylicious/muon/issues/796)). Not the current app.

## Design generations

Redesigns are named **`Gen N`** or **`Gen N - <descriptor>`** (folders `gen-N/` or `gen-N-<slug>/`), never release-like numbers. The canonical generation table lives in [workstream names and release versions](../release-naming.md#design-generations).

## Lifecycle rules for future mockups

1. **Never put unimplemented proposals inside `docs/design/current/`.** `current/` is strictly for real phone screenshots of what the code draws right now.
2. Put new redesign mockups in `docs/design/gen-N/` or `docs/design/gen-N-<slug>/` with a short `README.md` marking them as **proposal / target**.
3. **Retire on ship:** Once `Gen N` ships to a device, retake `docs/design/current/` from real hardware and delete the superseded `docs/design/gen-N*/` folder so only one visual truth remains in the repository.
