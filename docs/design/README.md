# Muon design assets

This directory contains only two active areas so coding and design agents always know what the app actually draws today:

- [`current/`](current/README.md) — **Real device screenshots** of the currently implemented app (Pixel 8, frozen M3 Expressive RC `.1420`). Always treat `current/` — then the Compose code — as ground truth for the existing UI. **Never place unimplemented proposals or drawn mockups inside `current/`.**
- [`icons/`](icons/README.md) — **Active vector icon generator** (`muon_icons.py`) that produces `app/src/main/res/drawable/ic_*.xml`.

## Design generations & naming convention

Per [workstream names and release versions](../release-naming.md), name UI/UX redesigns sequentially by generation (**`Gen N`** or **`Gen N - <optional descriptor>`**), never by a release-like number (`Muon 3.0`, `UI 3.0`, `docs/design/3.0/`), so design generations are never confused with APK semver tags. The generation number is the canonical identifier; adding a descriptor is optional (e.g., `Gen 4` → `docs/design/gen-4/`, or `Gen 4 - Bouncy Animations` → `docs/design/gen-4-bouncy-animations/`).

| Generation | Canonical name | Retired aliases (do not reuse) | Track / baseline | Status | Folder |
| --- | --- | --- | --- | --- | --- |
| **Gen 1** | **Gen 1 — Initial** | MVP, v0.1 | `main` (`v0.1.0`) | Shipped & superseded | *(none)* |
| **Gen 2** | **Gen 2 — Collection redesign** | "Muon 2.0", "UI 2.0" | `main` (`v0.2.0`, Canary `.276`) | Shipped on `main`; mockups retired | *(retired `2.0/`, `landscape/`, `offline/`)* |
| **Gen 3** | **Gen 3 — M3 Expressive (Refined)** | "M3E", "Expressive alpha" | `claude/m3-expressive-alpha` (frozen RC `.1420`) | **Current implemented baseline** | [`current/`](current/README.md) |
| **Gen 4+** | **`Gen N`** or **`Gen N - <descriptor>`** | Never "Muon 3.0" / "UI 3.0" | Dedicated branch off `claude/m3-expressive-alpha` | Planned next redesign | `docs/design/gen-N/` or `docs/design/gen-N-<slug>/` |

## Lifecycle rules for future mockups

1. **Never put unimplemented proposals inside `docs/design/current/`.** `current/` is strictly for real phone screenshots of what the code draws right now.
2. Put new redesign mockups in `docs/design/gen-N/` or `docs/design/gen-N-<slug>/` with a short `README.md` marking them as **proposal / target**.
3. **Retire on ship:** Once `Gen N` ships to a device, retake `docs/design/current/` from real hardware and delete the superseded `docs/design/gen-N*/` folder so only one visual truth remains in the repository.
