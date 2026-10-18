# October 18 resumed storage and memory engineering

## Ownership and scope

Solo GPT-6 / Codex desktop; exact variant and effort not reported. No Claude/subagents. Current user renewed rooted POCO testing and refreshed usage. Application PRs stay OPEN for cumulative acceptance. No Stable/tag/release, activation or experiment integration.

Main last refreshed at `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`; original experiment checkout clean and untouched at `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c`. The preceding completed #608 device history remains [startup verification](2026-10-18-startup-verification.md).

## Current source stack

| PR / head | Branch / worktree suffix | Scope | Current verification |
| --- | --- | --- | --- |
| [#609](https://github.com/averylicious/muon/pull/609) / `ad617a15146f2c449499b75f10838576d408456d` | `codex/owned-move-read-checkpoint-oct18` / `muon-owned-move-read-checkpoint-oct18` | Remove duplicate preliminary destination journal sweep; keep full enclosing checkpoints and binding/deletion checks. | Ordinary .1245 and diagnostic .1247 CI/reports/selected actual APK verified. Fresh diagnostic Card-to-Phone Move passed full stopped byte/record/native/cover/source-retirement/original-preservation proof. |
| [#610](https://github.com/averylicious/muon/pull/610) / `6d7f8db0402e39ed197a83582a25289618bbdfc5` | `codex/library-load-admission-oct18` / `muon-library-load-admission-oct18` | One load-owned fresh metadata admission; still charge fallback before publication. | Ordinary .1248 CI/reports/selected actual APK verified and installed; three50,000 loads and50,001 refusal/old-library preservation passed; full saved/original proof and cleanup passed. Selected saved playback reached PLAYING. |
| [#611](https://github.com/averylicious/muon/pull/611) / `da66f416fe09213a9bddfb4198dc32bbc5f4f88a` | `codex/streamed-track-records-oct18` / `muon-streamed-track-records-oct18` | Standard JSON per-record projection; same structural/field limits, old bounded lenient path. | Initial 39123095273 compiled; one older string-identity assertion failed although all 16 parser cases passed. Corrected characterization; latest .1251/run39123780912 pending, expected 1,761 per variant. |

Each builds on the preceding head. Worktrees live under `~/.codex/worktrees/`; source commits are clean. Self-check is not independent review. The app attachment tool hit the existing 100-identity cap; use direct PR links without removing unrelated attachments.

Source rationale/invariants on their respective branches: `docs/audits/2026-10-18-owned-move-checkpoint.md`, `2026-10-18-library-load-admission.md`, `2026-10-18-track-record-projection.md`. No count/byte ceilings, dependency, schema, package/signing or release behavior changed. Necessary distinct tests remain approved, consolidation afterward/current coverage unchanged.

## Verified builds

Actual downloaded Debug/Release reports: zero failures/errors/skips, lint zero errors / 66 warnings each. Selected APK package/original certificate/debuggable/export policy/BUILD/checksum checked. Release APKs not downloaded/inspected; unused diagnostic media probe not downloaded/used.

| Head / run | Mode / tests per variant | Selected artifact / SHA256 |
| --- | --- | --- |
| #609 [.1245 / 39121004089](https://github.com/averylicious/muon/actions/runs/39121004089) | Ordinary / 1,742 | [app-debug-ad617a15146f2c449499b75f10838576d408456d](https://github.com/averylicious/muon/actions/runs/39121004089/artifacts/12134745886), reports12133903540; `2a811ba912103eb65b61141e344f2533e1fda1dc81ffd1bc1e8070c3a4b2ec84`. Not installed. |
| #609 [.1247 / 39121989916](https://github.com/averylicious/muon/actions/runs/39121989916) | Separate debuggable diagnostic / 1,742 | [app-diagnostic-ad617a15146f2c449499b75f10838576d408456d](https://github.com/averylicious/muon/actions/runs/39121989916/artifacts/12134457342), reports12134173697; `2539be817b44b18b5b8dc5a35bcb0580e8dd00809b01f024025f970772ec5fc4`. Installed; fresh Card-to-Phone full proof passed, diagnostic stopped. |
| #610 [.1248 / 39122002920](https://github.com/averylicious/muon/actions/runs/39122002920) | Ordinary / 1,745 | [app-debug-6d7f8db0402e39ed197a83582a25289618bbdfc5](https://github.com/averylicious/muon/actions/runs/39122002920/artifacts/12135022035), reports12134453912; `6aa6ae8df0d9da6377fcbef1ccabbce6c35e7e29a7b3dc72d8e67ed189f86d61`. Installed; three50,000 loads/refusal/full preservation and selected saved playback passed. |

#609 first head/run39120112852 was superseded/cancelled. Run39120604782 compiled and ran1,742Debug tests with one fixture failure: wrong journal filename. Corrected to `new-save-journal-v1.db`. Target namespace mutation regressions now occur immediately after known native close; mutation while open may require quarantine, never forced release/retry. Final ordinary/diagnostic runs above passed.

## Device verification and ACTIVE phone operation

POCO USB `3b34b8e6`, surya / custom Android17 API37 / root UID0 reidentified. Fresh diagnostic .1247 Card-to-Phone Move completed with full stopped proof: every request field, full3,149,055-byte C14 audio/native metadata, fresh UID/key/own cover and source retirement passed; all older journals/partial and both parked pre-ROM inventories unchanged. Diagnostic is STOPPED. Full disposable copy is now on PHONE; older partial remains on CARD. Original music11 was restored/read back before subsequent work.

The user confirmed accidentally opening a song while cleaning the screen. This is not an app regression. It obscured the Settings idle row; navigating back and scrolling exposed it. The monitor first saw idle at783.074s and confirmed again at800.788s, but these are delayed UI observations, not actual completion latency; no speed comparison is valid. Successful bounded timing events were observed, but the rolling log later evicted them, so no complete aggregate is claimed. No active Move was force-stopped. Private full proof marker is `complete-full-stopped-proof` / `fullPreservationProof:true`; portable requirements and aggregate results are recorded here rather than depending on local files.

Ordinary .1248 was installed after both apps were stopped and an immediate normal baseline captured. Its controlled fixture completed three cold50,000-song loads and an explicit50,001 refresh/refusal retaining50,000. Highest sampled total PSS per accepted run208,135 /211,645 /242,445KiB; refusal224,279KiB. No memory guard triggered. Same short-tag synthetic loopback-only workload, no playable audio/artwork. Every normal saved request field/native metadata/audio/cover and both parked inventories matched; connection file restored exact bytes, own listener/files/private backup removed, original music11 restored/read back. A host proof-helper namespace collision prevented serializing its final marker after checks passed; isolated execution fixed the helper, the existing stopped snapshots were reverified and proof recorded. This was not an app failure or repeated phone operation.

Selected ordinary .1248 saved legacy-card playback reached PLAYING at5,046ms including host/ADB/250ms polling. App stopped, music11 restored/read back. No controlled startup improvement established. Ordinary .1248 also loaded the restored real962-song library. The follow-up1,000 long-tag fixture passed: wire16,200,562B / encoded track records16,088,550B, largest record16,089B,16,000-character titles. Eight samples: highest Java PSS170,912KiB / total268,723 / RSS382,388. No guard triggered. Full saved/native/audio/cover/connection and parked-original proofs passed; own fixture/listener removed and volume11 restored. This is one accepted near-wire/metadata case, not an over-byte-limit refusal or general heap bound.

A NEW diagnostic .1247 Phone-to-Card Move is now ACTIVE, on the same verified source. Fresh baseline captured and dialog/flags verified by driver. Normal .1248 stopped; music temporarily0, restoration11 in driver finally. Continuous fixed-event capture retains timing events rather than rereading a rolling log; bounded32 events per kind, no private text. Optional local recovery driver `/tmp/muon-oct18-run-captured-move.py`, marker `/tmp/muon-oct18-captured1247-phone-card.json`, sourcephone. No app install/force-stop/snapshot/playback/second UI driver until known idle and full stopped proof. Do not force-stop active storage work to meet a quota bound. Later source/device work must recapture current state.

#611 first-run failure was an older characterization assumption: it required four distinct equal title Strings. JsonReader pools a repeated short string within a response. Latest head records actual identity count within occurrence bound while preserving all four distinct track objects, equality/order and fallback identity; no record/metadata acceptance was relaxed. Initial run is not a pass. Current framework API contracts checked, Android17 implementation sources not independently checked.

## Completed library memory/refusal boundary

[Measured report](../audits/2026-10-18-library-memory-characterization.md) on verified #608 ordinary .1235: synthetic short-metadata 1,000/10,000/25,000/50,000-song loads completed. A50,001-entry refresh produced the explicit JSON array-limit error and retained the old50,000. Highest sampled final repeated total PSS304,983KiB (~298MiB); earlier50,000attempts sampled~223MiB, so do not call that a ceiling or absolute peak. No controlled speedup or whole-process bound inferred.

Phone-local shell-UID fixture bound only127.0.0.1, no tunnel/host/LAN listener/firewall change/real Tauon library change. Synthetic tracks had no playable audio/artwork. Original connection file restored byte-for-byte; every saved request/native/audio/cover and both parked-original inventories matched after testing; no phone completed row added. Own listener/fixture files/private preference backup removed, normal stopped and music11 restored/read back before the later active Move. Two corrected driver attempts (stopped-before-refresh and gesture-above-list) are not app crashes/refusal passes.

## Next engineering / verification

1. #609 fresh Move proof and ordinary .1248 repeated fixture/full preservation/cleanup are complete; delayed user-navigation idle detection is not latency evidence.
2. Ordinary .1248 selected saved playback passed; later cumulative source still requires its own verified APK and device comparison. Use fresh current package flags/actual bounds; restore connection/volume and preserve all saved/original bytes.
3. Verify #611 latest-head CI/reports/actual APK before installing. Fix compile/test failures within scope, save exact superseded/final evidence and repeat library/refusal comparisons. Prior #608/#610 results are baselines, not this source's runtime proof.
4. Remaining #401 guarded open/checkpoint and total Move/startup costs remain engineering. Direct read-only native-index verification backend is not implemented. Do not keep native ownership open across source unlinks merely to save time: current policy requires known closure and full binding before deletion.
5. Remaining memory dimensions include near-encoded-byte ceilings/long metadata, many-playlist overlap, artwork-heavy browsing, final queue/IPC peaks and large native saved inventories. Short-tag50,000live-library success does not clear those. Hardware/headset/spoken TalkBack/subjective UI-listening UAT stays distinct. #179 deep SD lifecycle and tool/dependency provenance remain user-approved post-release deferrals; Stable promotion requires explicit release authorization.

Latest quota at verification boundary71%five-hour /26%weekly USED; refresh at meaningful boundaries. A completed PR/checkpoint is not a turn stop; continue useful authorized engineering while preserving verification/handoff reserve.
