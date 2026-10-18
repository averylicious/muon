# October 18 resumed storage and memory engineering

## Ownership and scope

Solo GPT-6 / Codex desktop; exact variant and effort not reported. No Claude/subagents. Current user renewed rooted POCO testing and refreshed usage. Application PRs stay OPEN for cumulative acceptance. No Stable/tag/release, activation or experiment integration.

Main last refreshed at `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`; original experiment checkout clean and untouched at `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c`. The preceding completed #608 device history remains [startup verification](2026-10-18-startup-verification.md).

## Current source stack

| PR / head | Branch / worktree suffix | Scope | Current verification |
| --- | --- | --- | --- |
| [#609](https://github.com/averylicious/muon/pull/609) / `ad617a15146f2c449499b75f10838576d408456d` | `codex/owned-move-read-checkpoint-oct18` / `muon-owned-move-read-checkpoint-oct18` | Remove duplicate preliminary destination journal sweep; keep full enclosing checkpoints and binding/deletion checks. | Ordinary .1245 and diagnostic .1247 CI/reports/selected actual APK verified. Fresh diagnostic Card-to-Phone Move ACTIVE; no new runtime pass until full stopped proof. |
| [#610](https://github.com/averylicious/muon/pull/610) / `6d7f8db0402e39ed197a83582a25289618bbdfc5` | `codex/library-load-admission-oct18` / `muon-library-load-admission-oct18` | One load-owned fresh metadata admission; still charge fallback before publication. | Ordinary .1248 CI/reports/selected actual APK verified; not installed yet. |
| [#611](https://github.com/averylicious/muon/pull/611) / `d83f6ba8c25c7f5a111b735863a53fbaa8d14ffc` | `codex/streamed-track-records-oct18` / `muon-streamed-track-records-oct18` | Standard JSON per-record projection; same structural/field limits, old bounded lenient path. | Run 39123095273 pending first compile/test; expected 1,761 executions per variant, not yet verified. |

Each builds on the preceding head. Worktrees live under `~/.codex/worktrees/`; source commits are clean. Self-check is not independent review. The app attachment tool hit the existing 100-identity cap; use direct PR links without removing unrelated attachments.

Source rationale/invariants on their respective branches: `docs/audits/2026-10-18-owned-move-checkpoint.md`, `2026-10-18-library-load-admission.md`, `2026-10-18-track-record-projection.md`. No count/byte ceilings, dependency, schema, package/signing or release behavior changed. Necessary distinct tests remain approved, consolidation afterward/current coverage unchanged.

## Verified builds

Actual downloaded Debug/Release reports: zero failures/errors/skips, lint zero errors / 66 warnings each. Selected APK package/original certificate/debuggable/export policy/BUILD/checksum checked. Release APKs not downloaded/inspected; unused diagnostic media probe not downloaded/used.

| Head / run | Mode / tests per variant | Selected artifact / SHA256 |
| --- | --- | --- |
| #609 [.1245 / 39121004089](https://github.com/averylicious/muon/actions/runs/39121004089) | Ordinary / 1,742 | [app-debug-ad617a15146f2c449499b75f10838576d408456d](https://github.com/averylicious/muon/actions/runs/39121004089/artifacts/12134745886), reports12133903540; `2a811ba912103eb65b61141e344f2533e1fda1dc81ffd1bc1e8070c3a4b2ec84`. Not installed. |
| #609 [.1247 / 39121989916](https://github.com/averylicious/muon/actions/runs/39121989916) | Separate debuggable diagnostic / 1,742 | [app-diagnostic-ad617a15146f2c449499b75f10838576d408456d](https://github.com/averylicious/muon/actions/runs/39121989916/artifacts/12134457342), reports12134173697; `2539be817b44b18b5b8dc5a35bcb0580e8dd00809b01f024025f970772ec5fc4`. Installed; fresh Move ACTIVE. |
| #610 [.1248 / 39122002920](https://github.com/averylicious/muon/actions/runs/39122002920) | Ordinary / 1,745 | [app-debug-6d7f8db0402e39ed197a83582a25289618bbdfc5](https://github.com/averylicious/muon/actions/runs/39122002920/artifacts/12135022035), reports12134453912; `6aa6ae8df0d9da6377fcbef1ccabbce6c35e7e29a7b3dc72d8e67ed189f86d61`. Not installed. |

#609 first head/run39120112852 was superseded/cancelled. Run39120604782 compiled and ran1,742Debug tests with one fixture failure: wrong journal filename. Corrected to `new-save-journal-v1.db`. Target namespace mutation regressions now occur immediately after known native close; mutation while open may require quarantine, never forced release/retry. Final ordinary/diagnostic runs above passed.

## ACTIVE phone operation — verify ownership before commands

POCO USB `3b34b8e6`, surya / custom Android17 API37 / root UID0, reidentified this cycle. Normal ordinary .1235 is STOPPED. Separate diagnostic .1247 is performing one NEW UI Card-to-Phone Move of the disposable full copy. Before-operation stopped private/card baseline captured. Music is temporarily0; driver restores original11 and separately reads it back in finally. No concurrent playback, app install, force-stop, snapshot, memory fixture or competing UI driver until known idle/full proof. In particular do not force-stop an active Move because a monitor bound or quota is near.

Local optional recovery: host driver `/tmp/muon-oct18-run-bounded-digest-move.py fresh-1247-card-to-phone 1247 ad617a15146f2c449499b75f10838576d408456d card`; marker `/tmp/muon-oct18-fresh-1247-card-to-phone.json`. It monitors fixed bounded Move/digest observations, waits for two idle UI observations, snapshots only then, performs full byte/record/cover/source-retirement and parked-original proofs, and restores volume. `complete-full-stopped-proof` plus `fullPreservationProof:true` is completion; idle UI alone is not. Previous partial copy on CARD must remain. A transient null UI root was retried before fresh flags/banner/dialog confirmation; no ROM cause established.

Portable proof requirements: every request field and complete3,149,055-byte C14 audio, full native metadata, new allocation/UID/key/own-cover, source allocation/row/cover retirement; older Move/Delete rows, partial and unrelated covers unchanged. Compare both parked pre-ROM inventories against private backups. Never replay an old Complete receipt as a new Move, manually edit an index to manufacture success, or upload raw private records/audio/logs. Last busy to FIRST idle bounds observed completion; second idle is not the upper bound. Timing phases nest/overlap and may not be summed as independent wall time.

## Completed library memory/refusal boundary

[Measured report](../audits/2026-10-18-library-memory-characterization.md) on verified #608 ordinary .1235: synthetic short-metadata 1,000/10,000/25,000/50,000-song loads completed. A50,001-entry refresh produced the explicit JSON array-limit error and retained the old50,000. Highest sampled final repeated total PSS304,983KiB (~298MiB); earlier50,000attempts sampled~223MiB, so do not call that a ceiling or absolute peak. No controlled speedup or whole-process bound inferred.

Phone-local shell-UID fixture bound only127.0.0.1, no tunnel/host/LAN listener/firewall change/real Tauon library change. Synthetic tracks had no playable audio/artwork. Original connection file restored byte-for-byte; every saved request/native/audio/cover and both parked-original inventories matched after testing; no phone completed row added. Own listener/fixture files/private preference backup removed, normal stopped and music11 restored/read back before the later active Move. Two corrected driver attempts (stopped-before-refresh and gesture-above-list) are not app crashes/refusal passes.

## Next engineering / verification

1. Let active #609 Move finish its own full proof/restore. Record actual first-idle interval, fixed stage totals and result. No performance claim without direction/cache/debug caveats.
2. Install the verified higher ordinary #610 .1248 only when both apps are idle. Repeat selected real-library/saved-playback/control checks and controlled large-library/refusal measurements. Use fresh current package flags/actual bounds; restore connection/volume and preserve all saved/original bytes.
3. Verify #611 latest-head CI/reports/actual APK before installing. Fix compile/test failures within scope, save exact superseded/final evidence and repeat library/refusal comparisons. Prior #608/#610 results are baselines, not this source's runtime proof.
4. Remaining #401 guarded open/checkpoint and total Move/startup costs remain engineering. Direct read-only native-index verification backend is not implemented. Do not keep native ownership open across source unlinks merely to save time: current policy requires known closure and full binding before deletion.
5. Remaining memory dimensions include near-encoded-byte ceilings/long metadata, many-playlist overlap, artwork-heavy browsing, final queue/IPC peaks and large native saved inventories. Short-tag50,000live-library success does not clear those. Hardware/headset/spoken TalkBack/subjective UI-listening UAT stays distinct. #179 deep SD lifecycle and tool/dependency provenance remain user-approved post-release deferrals; Stable promotion requires explicit release authorization.

Latest quota at source boundary49%five-hour /23%weekly USED; refresh at meaningful boundaries. A completed PR/checkpoint is not a turn stop; continue useful authorized engineering while preserving verification/handoff reserve.
