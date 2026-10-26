# Workstream names and release versions

A redesign name describes a body of work. It is not an APK version, release tag, or permission to publish Stable.

## Current workstream

Use **Collection redesign** for the UI/UX work historically called **Muon 2.0** or **UI 2.0**. The existing `docs/design/2.0/` paths, issue titles and branches remain valid historical references; do not rename them as part of this convention. On first mention in a handoff, write “Collection redesign (formerly UI 2.0)”. Use descriptive names for future reworks instead of a release-like number.

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

Use “release candidate” only for a specified commit/build with a defined remaining QA checklist. Use existing signed Actions artifacts (Canary for routine checks, the minified release variant for release-configuration acceptance); a main Canary publication is a moving feed, not a frozen build. The user may daily-drive an exact frozen RC while later candidates are audited separately. Installing a release-variant RC updates the existing Stable package but does not publish a new Stable release; its workflow development version name is not a release promise. Do not invent RC tags or another package identity.

## Review and promotion

Implementation → attributed open PR → latest-head CI and signed Canary artifact → user phone QA → requested review/merge → explicit Stable-release decision. Keep pending checks visible. A user approving the feature plan or reporting “LGTM” on a test build does not itself request Stable publication.
