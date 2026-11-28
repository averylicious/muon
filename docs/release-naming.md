# Workstream names and release versions

A redesign name describes a body of work. It is not an APK version, a release tag, or permission to publish Stable.

## Design generations

Name redesigns **`Gen N`** or **`Gen N - <descriptor>`**, never release-like numbers (`Muon 3.0`, `UI 3.0`, `docs/design/3.0/`). Folders: `docs/design/gen-N/` or `docs/design/gen-N-<slug>/`. This table is the canonical list.

| Generation | Name | Former aliases | Track / baseline | Status | Folder |
| --- | --- | --- | --- | --- | --- |
| Gen 1 | Initial | MVP, v0.1 | `main` (`v0.1.0`) | Shipped, superseded | none |
| Gen 2 | Collection redesign | "Muon 2.0", "UI 2.0" | `main` (`v0.2.0`) | Shipped, mockups retired | none |
| Gen 3 | M3 Expressive (Refined) | "M3E", "Expressive alpha" | `main` (`v0.3.0`; retired `claude/m3-expressive-alpha`) | Current shipped baseline | `docs/design/current/` |
| Gen 4 | Progressive Blur | — | `gen-4-progressive-blur` (from `v0.3.0`) | Proposal mockups, active branch | `docs/design/gen-4-progressive-blur/` |
| Gen 5+ | `Gen N` / `Gen N - <descriptor>` | — | Dedicated branch off `main` | Future | `docs/design/gen-N*/` |

- `docs/design/current/` holds **only real device screenshots** of what the app draws today, never proposals.
- **Retire on ship:** retake `docs/design/current/` from real hardware and delete the superseded `docs/design/gen-N*/` folder.
- Keep `docs/design/icons/` (`muon_icons.py`, the active drawable generator).
- Old issue titles and branch names using "UI 2.0" stay valid history; on first mention write "Collection redesign (formerly UI 2.0)".

Track delivery by issue and slice ("#43 — player presentation host"). Identify a tested build by Canary version, full commit SHA and Actions run. A green branch build means ready for QA, not merged or released.

## APK versions and channels

Keep package IDs, signing identities, version-code ordering and channels as in [CI](ci.md) and [Obtainium](obtainium.md).

- **Canary:** `0.1.0-canary.<run number>`. "Canary .114" names a build, not a milestone. Branch builds are Actions artifacts; successful APK-producing `main` builds publish the Canary feed. The leading version is independent of the latest Stable tag.
- **Stable:** an explicitly approved `vMAJOR.MINOR.PATCH` tag. PATCH for compatible fixes and measured optimizations, MINOR for compatible features, MAJOR for intentional breaking changes to user-facing behaviour, data or integration contracts. A redesign does not by itself bump MAJOR.
- **Version code:** the workflow run number; never reset it.
- **Release candidate:** a specified commit/build with a defined QA checklist, distributed through Canary. No RC tags or extra package identities.

## Review and promotion

Implementation → attributed PR → latest-head CI and signed Canary artifact → user phone QA → merge → explicit Stable decision. "LGTM" on a test build is not a Stable request.

`.github/workflows/release-please.yml` keeps one rolling Release PR against `main` that updates `CHANGELOG.md`, `version.txt` and `.release-please-manifest.json` from conventional commits (`feat`, `fix`, `perf`, `revert`; others hidden). It uses `skip-github-release: true` and no signing secrets, so it never tags or publishes. Stable APKs still need an explicit tag push after phone QA.
