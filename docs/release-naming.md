# Workstream names and release versions

A redesign name describes a body of work. It is not an APK version, release tag, or permission to publish Stable.

## Design generations and workstreams

Name UI/UX redesigns sequentially by generation (**`Gen N`** or **`Gen N - <optional descriptor>`**), never by a release-like number (`Muon 3.0`, `UI 3.0`, `docs/design/3.0/`), so design generations are never confused with APK semver tags. The generation number is the canonical identifier; adding a descriptor is optional (`Gen 4` → `docs/design/gen-4/`, or `Gen 4 - Bouncy Animations` → `docs/design/gen-4-bouncy-animations/`).

| Generation | Canonical name | Historical / retired aliases | Track / baseline | Status | Folder |
| --- | --- | --- | --- | --- | --- |
| **Gen 1** | **Gen 1 — Initial** | MVP, v0.1 | `main` (`v0.1.0`) | Shipped & superseded | *(none)* |
| **Gen 2** | **Gen 2 — Collection redesign** | "Muon 2.0", "UI 2.0" | `main` (`v0.2.0`) | Shipped on `main`; mockups retired | *(retired)* |
| **Gen 3** | **Gen 3 — M3 Expressive (Refined)** | "M3E", "Expressive alpha" | `main` (`v0.3.0`; retired `claude/m3-expressive-alpha`) | Current shipped baseline on `main` | `docs/design/current/` |
| **Gen 4** | **Gen 4 — Progressive Blur** | — | `gen-4-progressive-blur` (cut off `main` / `v0.3.0`) | Proposal mockups & active branch | `docs/design/gen-4-progressive-blur/` |
| **Gen 5+** | **`Gen N`** or **`Gen N - <descriptor>`** | Never "Muon 3.0" / "UI 3.0" | Dedicated branch off `main` | Planned next redesign | `docs/design/gen-N/` or `docs/design/gen-N-<slug>/` |

### Visual baseline rules

- **Never put unimplemented proposals inside `docs/design/current/`.** `current/` is strictly for real device screenshots of what the app draws today.
- **Retire on ship:** Once `Gen N` ships to a device, retake `docs/design/current/` from real hardware and delete the superseded `docs/design/gen-N*/` proposal folder so only one visual truth remains in the repository.
- **Keep `docs/design/icons/`:** It holds the active vector drawable generator (`muon_icons.py`).

Historically, Gen 2 (**Collection redesign**) was called **Muon 2.0** or **UI 2.0**; existing issue titles and branch names remain valid historical references. On first mention in a historical handoff, write “Collection redesign (formerly UI 2.0)”.

Track delivery with an issue and a small slice name, for example “#43 — player presentation host”. Identify a tested build by its Canary version, complete commit SHA and Actions run link. A successful branch build means ready for manual QA, not merged or released.

The original Collection redesign implementation sequence was:

1. Finish the current player interaction boundary.
2. Artwork performance dependency and collection browsing (Albums, Artists and detail pages).
3. Queue backend/UI and song actions.
4. Search and Connect.
5. Integration, correctness checks and release-candidate QA.

Baseline Profile tooling and selected work have their own evidence in [the profile guide](baseline-profile.md). Blur remains deferred to a future design rework. This does not imply blur requires multiple Android windows or a new architecture.

## APK versions and channels

Keep the existing package IDs, signing identities, version-code ordering and update channels described in [CI](ci.md) and [Obtainium](obtainium.md).

- **Canary:** currently `0.1.0-canary.<Actions run number>`. “Canary .114” is shorthand for a build, never a feature milestone. Branch builds are Actions artifacts; successful APK-producing `main` builds publish the Canary feed under the repository's current visibility. The current workflow's leading development version is independent of the latest Stable tag; the run number identifies the Canary build.
- **Stable:** an explicitly approved `vMAJOR.MINOR.PATCH` tag produces the corresponding version name. A redesign does not automatically increment MAJOR. Before tagging, choose the version against the current roadmap and intended compatibility scope.
- **Version code:** remains the workflow run number under the existing CI. Do not reset it when changing a version name; Android update ordering is separate from the display version.

For release planning, use PATCH for compatible fixes and measured optimizations without new feature scope, MINOR for compatible feature additions, and MAJOR for intentional breaking changes to supported user-facing behavior, data or integration contracts. These are planning conventions, not a promise that app APIs are publicly versioned. A Baseline Profile improvement alone would normally be a PATCH candidate. The user delegated the current release version/publication on 2026-10-23: **v0.2.0** follows the published v0.1.0 because this is a substantial compatible feature addition. Stable is the distribution channel; a 0.x version does not make it an experimental/Canary release. Future major changes should follow actual compatibility scope, not a redesign nickname.

Use “release candidate” only for a specified commit/build with a defined remaining QA checklist. Continue distributing that candidate through the existing Canary artifact/feed mechanism unless the user explicitly approves a channel change. Do not invent RC tags or another package identity.

## Review and promotion

Implementation → attributed open PR → latest-head CI and signed Canary artifact → user phone QA → requested review/merge → explicit Stable-release decision. Keep pending checks visible. A user approving the feature plan or reporting “LGTM” on a test build does not itself request Stable publication.

## Rolling changelog PR (`release-please`)

`.github/workflows/release-please.yml` maintains a single rolling Release PR against `main` that updates `CHANGELOG.md`, `version.txt`, and `.release-please-manifest.json` from conventional commits (`feat`, `fix`, `perf`, `revert`; `docs`/`ci`/`chore`/`build`/`test`/`refactor` stay hidden). It runs with `skip-github-release: true` and no signing secrets, so it never creates a tag or an APK-less GitHub Release. Merging the Release PR only lands the changelog/version update on `main`; Stable APK publication still requires an explicit `vMAJOR.MINOR.PATCH` tag push after phone QA.
