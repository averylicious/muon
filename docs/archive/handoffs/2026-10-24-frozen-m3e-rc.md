# Frozen M3E RC — 2026-10-24

The user reports all focused UAT passed and requests a frozen daily-driver RC while later audits/candidates proceed separately. Published Stable remains **v0.2.0**; no newer Stable tag or publication is authorized.

## Frozen source and acceptance

- Source: `6b1a767c180a8b9e531bf1468c9e72e348635b74`, [#678](https://github.com/averylicious/muon/pull/678), includes #674 `dbd87477` and #675 `980046d0` plus main `11dd2cd2` documentation.
- App/build/workflow/tools are identical to accepted combined `209247be9634cd60145525ed15a98af46b5f47fc` / Diagnostic .1415. The user reports the entire [focused checklist](2026-10-24-m3e-focused-acceptance.md) passed. No repeat of unchanged interaction testing is requested.
- #671 has not recurred; cause remains unknown. Keep it open as a non-blocking follow-up, without claiming a fix. #646, #401, #207, #179, dependency/tool provenance and #83 retain their deferrals.

## Build and installation receipt

- Final signed non-Diagnostic [Actions .1420](https://github.com/averylicious/muon/actions/runs/40091793867) passed at frozen head: **1,883 tests per variant**, zero failures/errors/skips; lint zero errors / 72 warnings / 3 hints per variant. Branch direction passed. No local Android build; publication skipped on the feature branch.
- Installed [release artifact](https://github.com/averylicious/muon/actions/runs/40091793867/artifacts/12559726947): `app-release-6b1a767c180a8b9e531bf1468c9e72e348635b74`, package `dev.avery.muon.release`, version name `0.1.0`, code **1420**, minified/non-debuggable. ZIP contains `app-release.apk`, `SHA256SUMS`, `BUILD.txt` (unlike the Canary ZIP's `app-debug.apk`).
- APK SHA256 **`6dd8e03861967502b708f7c12b0e611d7d8f6e67ca16d7bf869d6590a613a19c`**. BUILD/source/run, ZIP checksum, pinned release certificate (`fb5e1219…` in `docs/signing-certificates.txt`) and compiled manifest/export policy verified from the actual downloaded APK.
- User requested both-phone installation in this cycle. In-place `adb install -r` succeeded on **POCO and Pixel 8**; each installed base APK hash matched the verified artifact, code/name/debug flag matched, and Stable's UID was preserved. Canary/Diagnostic package versions and UIDs stayed unchanged. No app data clear/uninstall, playback/settings change or SD operation. Neither app was launched or a song selected for this install receipt. Installed identity is not playback acceptance. POCO root worked; Pixel wireless ADB worked. No connection failure was observed during these operations.
- Optional [normal Canary artifact](https://github.com/averylicious/muon/actions/runs/40091793867/artifacts/12559612366): `app-debug-6b1a767c180a8b9e531bf1468c9e72e348635b74`, `0.1.0-canary.1420`. **Not installed**; the user's frozen daily driver is the release-package artifact above.
- #678 landed as `ee7ddb7521855a7422036d7cd4f384d11b308caa`; #674/#675 are marked merged through its retained ancestry. The frozen APK is still the exact .1420 artifact, not a rebuild from the merge commit. Any experiment-branch merge build is separate and was not installed.
- Promotion policy #679 head `7f0803323e8c06083a6cec429c869687d0a9977a` passed [.1421](https://github.com/averylicious/muon/actions/runs/40092159144) and its Branch direction check; 128 local CI-tool tests passed. GPT-6 reviewed Claude's policy and checked a temporary real-history graph: current main rejects unlanded policy substitution, while the reviewed policy plus frozen source passes without app/build/workflow changes. That temporary graph changed no real refs or tags. #679 remains open and must be refreshed against current main before future merge.

## Remaining before a new Stable

1. User acceptance of the **actual minified release-package configuration** after install: open Muon (not Muon β/diag), connect using existing settings, play/skip, restart and play once. Existing data should remain; report a regression rather than redoing the complete focused checklist.
2. Land reviewed [promotion policy #679](https://github.com/averylicious/muon/pull/679), then merge current main policy into the exact frozen source on `codex/promote-m3e-rc-oct24`. Keep all accepted app/build/workflow files unchanged, run exact-head checks and review the integration. Never merge a later experiment tip or bypass protected-main checks. Policy preparation is open deliberately; main remains the v0.2.0 app.
3. Explicit final Stable version/publication decision. RC preparation or installation does not create a new Stable release.

## Daily use and later audit work

The signed release artifact uses the existing Stable package/certificate and updates it in place, independently of Canary. Its branch workflow version name remains `0.1.0`; **run/version code .1420 and exact hash identify this unreleased RC**, not that display name. A final Stable version will be chosen at publication. Canary still follows future main publications; Diagnostic is separate and its observer cannot read ordinary Muon’s playback.

Keep this source/artifact fixed. Later RCs use separate reviewed branches and explicit retest notes; do not install them over the user's frozen RC without a request. Broader audits, including #83, are subsequent work, not new blockers for this candidate. A confirmed serious defect may still block final promotion. Do not deliberately hide such a defect under the scope lock.

## Ownership and attribution

GPT-6 / Codex desktop: delta audit, fixes, candidate integration, policy review/final pin, receipts and authorized installation verification. Claude Opus 5.5 / Claude Code, explicitly High effort (runtime confirmed): selected prior navigation review and promotion-policy implementation/tests. Author pin/docs edits are self-reviewed; CI, installed identity, user acceptance and source review are separate evidence. The allocated Claude is idle. Original user/Claude checkout untouched; no signing secrets or raw phone dumps published.
