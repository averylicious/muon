# Release plan

Updated 2026-11-28. Workstream names are not version numbers ([release naming](release-naming.md)); live heads are in [STATE.md](STATE.md).

## Current release: v0.3.0

[Stable v0.3.0](https://github.com/averylicious/muon/releases/tag/v0.3.0) (tag `89b933a7`, #801) ships Gen 3 — M3 Expressive with stable Material 3 `1.5.0` — graduated from the retired `claude/m3-expressive-alpha` branch. It supersedes v0.2.0 (Gen 2 Collection redesign). Its changelog and version files landed through release-please #779.

## Open follow-ups, not fixed claims

- **#646** post-reset/SD-card Delete refusal: open and unresolved by user decision. Keep exact-route evidence if it recurs.
- **#401** saved-card startup and Move latency: accepted for release, further work deferred.
- **#671** intermittent stream buffering: not recurred, non-blocking.
- **#83** measured Baseline Profile benefit, with draft PR #683.
- Dependency verification and runner/SDK/cache provenance limits remain deferred, with no compromise shown.

A confirmed security, privacy, data-loss or crash regression can block a release; speculative hardening and polish go to maintenance. Keep deferred issues open rather than labelling them fixed. Keep meaningful regression coverage; there is no test-count target.

## Next cycle: Gen 4 - Progressive Blur

Active on `gen-4-progressive-blur` (#796, #797): Compose BOM `2026.11.01` / Compose `1.13.0`, Android SDK `37.2`, progressive/backdrop blur. It ships to `main` only through a reviewed promotion following [parallel tracks](parallel-tracks.md), with release-configuration acceptance and an explicit Stable request. Distribute candidates through existing Canary artifacts; no RC tags or new channels.

Earlier gates and receipts: [v0.2.0 release](handoffs/2026-10-23-stable-020-release.md), [frozen M3E RC](handoffs/2026-10-24-frozen-m3e-rc.md), [issue state](audits/issues/README.md).
