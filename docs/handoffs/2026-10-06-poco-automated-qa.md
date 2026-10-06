# POCO automated acceptance checkpoint — 2026-10-06

The user explicitly authorized automated POCO lockscreen QA and suitable application merges this cycle. Pixel17 lockscreen behavior remains deferred: no Pixel settings, lockscreen experiments or device commands performed. Main inspected at `5d1c86620620c5f306ad1da844d9af4953a21895`; experimental checkout/session preserved. Refresh final PR/#181/#40 receipts before acting on the pending snapshot below.

## Build and device boundary

POCO X3 NFC (custom ROM Android16/API36), USB connected. Canary .494 updated with `adb install -r` to signed combined acceptance #302 .652 at `10f2e72a96ed4f81dd5f570600a1b6de6e16d396`, preserving app data. Actual downloaded BUILD.txt names the full SHA/run652/version0.1.0-canary.652; SHA256SUMS verified, APK SHA256 `e9f33aba666e025c68871608b1c90b91b090b76171d39a717362a8718187cd08`. [Successful Android652](https://github.com/averylicious/muon/actions/runs/37295996633); [debug artifact11339411420](https://github.com/averylicious/muon/actions/runs/37295996633/artifacts/11339411420), `app-debug-10f2e72a96ed4f81dd5f570600a1b6de6e16d396` (Actions-only14days, updates existing Canary, not Obtainium).

This APK includes other unmerged app fixes; a later standalone/main artifact will not. No #213 production identity fix or full #179 recovery. No uninstallation, signing change, cache migration/deletion, SD failure injection or Stable promotion. Previously verified CI646 tests/variant is distinct from this device evidence.

## Observed automated checks

ADB input, UIAutomator semantics and filtered platform media-session snapshots were used. These establish specific UI/control/state observations, not audible playback, human acceptance or hostile-helper-app equivalence. Private screenshots/XML/session observations remain local; phone notifications/wallpaper were not committed or uploaded.

| Area | Observed result | Limit |
| --- | --- | --- |
| Data-preserving upgrade / connection | App loaded962-song live library; ordinary Refresh retained962 tracks; reopening usable. | Not fresh-install discovery, LAN denial/grant on API37, slow-server or whole-library performance proof. |
| POCO lockscreen presentation | Secure keyguard visible; Muon mark, current artwork, title and SystemUI transport/seek controls present. | Does not explain Pixel17 missing controls or establish other ROM behavior. |
| Lock transport | Play/pause state changed; next title changed then previous returned to original; paused tap-to-seek moved position from3901 to80248ms. Playback session had previously advanced beyond30seconds. | Initial drag-to-seek did not establish a jump; only the later tap is a pass. State/timing is not audibility or uninterrupted-output measurement. |
| Private service | Explicit shell media-button start denied as non-exported; published session lock controls still worked. | Shell UID is not an ordinary hostile helper app; required direct-binding companions and real headset/Bluetooth remain unresolved. |
| Artists navigation/recreation | Scrolled Artists → detail → Back retained same visible rows/offset and folded header; landscape→portrait recreation retained the position. | Did not measure animation quality or all top-level/offline transitions. |
| Songs lifecycle | Scrolled Songs survived Home/background→existing-task reopen at same rows/offset. Large-font/landscape UI remained available. | Explicitly finishing an Activity is distinct from returning to an existing task. No zero-height held-scroller, full split-screen or all-mode reset confirmation. |
| Song-sheet cancellation | Long-press sheet opened; Back dismissed it without queuing. | No rapid double-tap/disposal-during-hide/rotation race proof. |
| Queue smoke | Normal removal/Undo returned queue size to962; Play next added a duplicate occurrence962→963, Undo returned963→962. Current paused track stayed unchanged. | Shuffle can choose a new placement on restoration; this did not prove old playing order or duplicate identity under concurrent replacement/reordering. |
| Loudness preference lifecycle | Initial switch enabled→disabled; force-stop/reopen retained disabled; enabled restored. App/player/settings construction remained usable. | No corruption injected into user preferences. Wrong-type paths remain disposable JVM tests; no subjective loudness, unknown-track audio or listener-scheduling claim. |

The force-stop check discarded only the queue created for this QA session; initially there was no active Muon queue. Queue/position recovery across process death is a documented unfixed limitation, not a new regression. All testing playback was muted. Original media volume21/25, font scale1.0, user_rotation0 and accelerometer_rotation0 were restored and read back. Song order restored to Recently added, normalization enabled restored. Temporary device XML removed. No audio playing at completion. Existing downloads untouched; ordinary playback may create its configured played-copy cache as normal.

## Application decisions and remaining user QA

#331's narrow wrong-type preference-read fix is being refreshed in isolated `codex/loudness-qa-oct6`, publishing to existing `codex/loudness-preference-types`: exact `1fc3d5dd0b2e5b8dd660ae68305028f87deadc6d` contains main5d1c866 without conflicts. Final production/test patch unchanged from original independently reviewed8c6494d and combined .652. Local diff/destination checks pass. [Android653](https://github.com/averylicious/muon/actions/runs/37436882379) pending in this snapshot. Merge only if its final full checks/current destination/protection pass; final receipt records outcome. Ordinary recreation QA is cleared for this type-read change under the current user authorization. Valid gain/output behavior is unchanged; subjective normalization is separate, not claimed verified. Audit Claude idle, not used for this refresh.

**#302 and the other application candidates remain OPEN:**

- #205/#257: real Bluetooth/wired headset buttons, disconnect/unplug and audible pause/resume; required companion/direct-binding integrations; hostile ordinary-app access test still absent. POCO lockscreen now passed, Pixel lockscreen stays deferred.
- #267/#273/#290/#335: rapid queue replacement/reorder/Undo with duplicates, cancelled/interrupted song/queue/lyrics sheets, all-mode scroll/header restoration, tiny viewports and actual TalkBack focus/speech/actions. Some can be automated in later bounded scenarios; today's normal smoke is not all of them.
- #289/#300/#302/#321: disposable copied-audio download/pause/resume/move/offline totals and startup; real card removal/reinsertion during relevant I/O requires hands-on disposable data. Do not inject failure on existing downloads. Full #179 preservation is still unimplemented and cannot be cleared by ordinary card playback.
- #210/#221: changed/corrupt/large/in-flight artwork cases and media identity; today's ordinary artwork observation is not those cases.
- Audio focus (calls/other audio), audibility/clipping/loudness and perceived motion/smoothness need user/hardware confirmation. ADB transport keys are not Bluetooth/headset hardware evidence. Baseline Profile timing remains separate #83 measurement work.

Hardware scenarios need the real accessory/card/user; visual taste and screen-reader speech need direct acceptance. Some race/layout/download checks are automatable with safe fixtures, but remain unrun, rather than inherently impossible. No report claims every manual check is outside automation.

## Next bounded work / recovery

Record final-head CI/artifact/merge/publication receipts on #331/#302/#181/#40, update this pointer after final results, then use [saved-production boundary](../audits/2026-10-05-saved-production-boundary.md) for #213 prospective save-key/cover/alias ownership controls. Full #179/#213/#230/#253, build provenance and performance gates remain; source-pass estimate unchanged qualitative75%±10, not release readiness.

Coordinator GPT-6 (Codex desktop; exact variant/effort not exposed) owns the two isolated October6 branches. Self-review, not new independent review. No other live Codex agent or active audit Claude process found. Quota read this cycle exposes weekly usage only, not a five-hour bucket; no old account percentages reused. Reserve capacity for final evidence and clean handoff; transient quota/logs are not prerequisites for another contributor. This document records evidence and current-cycle permission, not standing future phone authorization.
