# Muon design assets

This directory contains only two active areas so coding and design agents always know what the app actually draws today:

- [`current/`](current/README.md) — **Real device screenshots** of the currently implemented app (Pixel 8, frozen M3 Expressive RC `.1420`). Always treat `current/` — then the Compose code — as ground truth for the existing UI. Never place unimplemented proposals inside `current/`.
- [`icons/`](icons/README.md) — **Active vector icon generator** (`muon_icons.py`) that produces `app/src/main/res/drawable/ic_*.xml`.

## Design generations & naming convention

Per [workstream names and release versions](../release-naming.md), use a **descriptive name** (never a release-like number such as "Muon 3.0" or `3.0/`) for each redesign pass so design milestones are never confused with APK semver tags.

| Generation | Canonical name | Retired aliases (do not reuse) | Track / baseline | Status | Folder |
| --- | --- | --- | --- | --- | --- |
| Gen 1 | **Initial** | MVP, v0.1 | `main` (`v0.1.0`) | Shipped & superseded | *(none)* |
| Gen 2 | **Collection redesign** | "Muon 2.0", "UI 2.0" | `main` (`v0.2.0`, Canary `.276`) | Shipped on `main`; mockups removed | *(retired `2.0/`, `landscape/`, `offline/`)* |
| Gen 3 | **M3 Expressive (Refined)** | "M3E", "Expressive alpha" | `claude/m3-expressive-alpha` (frozen RC `.1420`) | **Current implemented baseline** | [`current/`](current/README.md) |
| Gen 4 (Next) | **Descriptive theme slug** *(e.g. `proposal-<slug>`)* | Avoid "Muon 3.0" / "UI 3.0" | Dedicated branch off `claude/m3-expressive-alpha` | Planned next redesign | `docs/design/<slug>/` *(delete once shipped & `current/` is retaken)* |

## Lifecycle rule for future mockups

1. Put new unimplemented mockups in a dedicated descriptive folder such as `docs/design/<slug>/` with a short `README.md` marking them as **proposal / target**.
2. Once the redesign ships on a device, retake `docs/design/current/` from real hardware and delete the superseded proposal folder so only one truth remains in the repository.
