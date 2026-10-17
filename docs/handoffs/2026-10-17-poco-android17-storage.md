# POCO Android 17 storage verification — October 17

## Scope, ownership and recovery

User renewed rooted USB POCO testing for this task; Claude is reserved for another project and was not used. GPT-6 / Codex desktop is sole implementation, author self-review and device-check contributor; exact variant/effort not reported. No subagents or experimental checkout changes. Author checks are not independent review or subjective user acceptance. The original checkout belongs to the user/experiment and was not edited.

Main inspected a2db02b8ba0b4959f28aecf5fa89196cf92622bc; previous full candidate #562 615826a55f0b101a5a99de2b14683a4b39c181e6. New main-target application #563, cumulative #564 and resumed successors #566/#567 remain OPEN. This main-based documentation branch has no app code and must not be mistaken for a test APK. Verify current heads/checks/ownership before takeover; dated device authorization is not standing permission.

| Branch / worktree suffix | Head | Role / current receipt |
| --- | --- | --- |
| codex/partition-device-qa-oct17 / muon-partition-device-qa-oct17 | 0cf01755b231a0fcc68b5f7a4884af3bd54c4ad6 | #563 framework database parent alias fix; diagnostic .1109 verified and used; normal .1108 attempt2 final receipt on PR |
| codex/partition-journal-probes-oct17 / muon-partition-journal-probes-oct17 | 60f15ea74010411bea01e142a598b0d43f9a519a | #564 fixed-child journal probe consolidation; normal .1110 attempt2 and diagnostic .1111 fully verified; speedup not established |
| codex/retired-copy-inventory-oct17 / muon-retired-copy-inventory-oct17 | 4e5d3536e650268888bb3727bf87adf8038c5884 | #566 exact-key retirement inventory correction; normal .1113 in progress at checkpoint |
| codex/retirement-hash-blocks-oct17 / muon-retirement-hash-blocks-oct17 | c3226d01d8b5cbd23d67d87b90fba63e315ef054 | #567 fixed64KiB retirement digest; normal .1114 in progress at checkpoint |
| codex/poco-a17-checkpoint-oct17 / muon-poco-a17-checkpoint-oct17 | Resolve live PR head | Sole shared-checkpoint writer; documentation only |

Worktrees are under ~/.codex/worktrees/. Current exact checks/artifacts and final device disposition are on the PRs and [#253](https://github.com/averylicious/muon/issues/253). No other writer may reuse an active checkout. Source .1108 attempt1 and #564's superseded normal push run are not passes if cancelled; distinguish manual diagnostic and ordinary builds. No app merge, Stable promotion/tag/release or experimental sync is authorized by this report.

## Device and preservation

POCO surya/M2007J20CG reports Android17/API37 and October5 security patch. Rooted shell works. Normal shell run-as still fails SELinux seapp context lookup for targetSdk37 despite the diagnostic being debuggable. This reproduces a tooling limitation; it does not establish root-manager or Android17 causation. No SELinux/root-manager/lock/firewall/network settings were changed. LAN permission/discovery/connect worked through the app; existing desktop API was only read, not exposed or reconfigured. No phone/card unmount was performed.

After the ROM change, no Muon package was installed, but prior external normal/diagnostic folders remained. Ordinary Canary/Stable were not installed, initialized or modified. Before a fresh diagnostic launch, the two old diagnostic SD audio files were SHA256-checked and locally backed up; the old downloads folder was parked as downloads.pre-a17-20261017 beside the fresh diagnostic downloads directory. It was not adopted as a fresh library or deleted. The old ordinary Muon SD folder was untouched.

Fresh disposable data came from two normal UI Save a copy actions, approximately4.53MB combined. Private baseline records/request metadata and audio hashes were captured locally. Phone→card Move before partition opt-in preserved both complete audio hashes, full request identities/data, content lengths and terminal records. Two card COMPLETED rows remained; original phone rows were removed only after move success. Raw song metadata, addresses, private DBs, debugger state, backups and traces are not committed or uploaded. Restricted ignored .local/qa-oct17/ in the #563 worktree retains small private recovery backups; portable evidence is this report and PR/issue receipts, not that local folder.

Media volume was11 before tests, muted during playback diagnosis and restored to11 at the cutoff. Resumed live verification also read11. Installed separate diagnostic .1111 is currently stopped; no debugger forward/profiler is active and card remains mounted. Refresh the immediately pre-test value before more playback and confirm disposition at final cleanup. Do not uninstall Muon diag casually: its external folder also contains the preserved pre-ROM diagnostic files.

## Reproduced startup defect and fix (#563)

Ordinary pre-opt-in startup, library load and save/move worked. Cancelling initial Review opt-in left the choice absent. Explicit Save startup choice persisted the exact selection, paused saved storage and required Force stop/reopen. First partition startup then failed safely with Interrupted saved-storage setup; both original hashes/two completed rows remained and no partition root had been published.

Read-only inspection of retained diagnostic initialization found IOException: Legacy database identity unavailable. Owned files/root paths were canonical /data/data while the framework-provided database path remained /data/user/0. This is an app portability defect surfaced on this ROM, not proof the ROM upgrade introduced it. #563 normalizes only the trusted framework database PARENT at production preparation and pre-startup review. The leaf remains unresolved; strict arbitrary-alias, final-file-symlink and exact identity checks are retained. Actual SQLite/composed-preparer alias test plus strict raw-alias/same-target-leaf-link refusal test passed.

With fixed diagnostic .1109 installed data-preservingly, the existing interrupted receipt was preserved. Cancelling Review Resume left it byte-for-byte unchanged. Explicit Save Resume choice plus full process restart reached Ready with six actual journal files on each shelf and both originals/records listed. No preference reset, fallback backend, manual journal repair or deleted partial attempt was used. This validates this recovery path, not all power-loss/filesystem durability behavior.

## Playback/performance evidence and #564

Correction to October13's overly broad remaining-#401 wording: the narrow Ogg tiny-peek/read-ahead cause and fix were already measured on October9; see [the actual earlier receipt](https://github.com/averylicious/muon/issues/401#issuecomment-6080578934). This cycle validates the new ROM/cumulative path rather than re-deriving that issue.

Fresh pre-opt-in diagnostic .1107: saved playback READY phone571ms/card551ms. After partition opt-in on .1109, the same SD copy had PREPARATION5648ms, ADMISSION399ms, READY13885ms, OPEN3096/3558/3560ms and first reads1184/1040/1097ms. READY excludes the separate queue-preparation phase. This is a new partition-path latency problem on a small diagnostic fixture; not general normal-release performance or a matched Android16 comparison. No large-library or measured memory improvement claim.

A standard method-sampling attempt yielded an empty trace; it was stopped and its owned device file removed. A second selection during that attempt is excluded from equal timing comparisons. Actual SIGQUIT worker snapshots during slow optimization and original Delete show exactJournal/located doing File.isFile/canonicalize repeatedly inside legacy metadata snapshot / manifest digest. Those snapshots show active filesystem validation, not a demonstrated lock deadlock or complete inclusive-time profile.

#564 consolidates each FIXED direct child journal's type/key into one NOFOLLOW attributes read, brackets the sweep with canonical root type/key checks, and preserves per-operation volume, barrier, all six journal identities and sticky uncertainty. It does not cache mount state or validation across operations, drop a journal check, adopt aliases or add a worker. A link to the original journal inode must still refuse; exact direct-file return can recover known identity. New tests/full CI passed1,627tests per variant with zero failures/errors/skips and lint0errors/68warnings each; actual ordinary .1110 and diagnostic .1111 APK safeguards/reports verified. Same-fixture comparison must still pass before claiming improvement. Even an improvement does not automatically clear #401 or large-library acceptance.

## Mutation checks / remaining gates

First optimization on .1109 copied slowly. Explicit Cancel completed, retained the original/two COMPLETED rows and audio hashes, and listed an interrupted optimization for renewed-consent review. Snapshot preserved the exact partial migration state; no automatic cleanup/replay/Ready claim. An original-copy Remove request on the other disposable copy also took unexpectedly long; a live stack was still in manifest hashing's exactJournal/canonicalization. The process was intentionally stopped before completion, leaving one exact Pending retirement and both original hashes/two COMPLETED rows. On .1111, renewed Verify and delete also remained busy at the bounded observation and was stopped. Final snapshot confirms the same two originals/rows and Pending phase0, plus interrupted optimization. Delete completion is not established.

Settings can say SD card unavailable while the actual card remains mounted and an exclusive storage permit is held. Live inspection showed accepting=true, invalid=false, an exclusive permit with no retained uncertainty. Ordinary availability includes admission, so this label is misleading busy feedback; do not infer physical ejection/ROM card failure. Keep this separate from actual bytes/identity safety and confirm final availability after work drains.

| Gate | Result / next requirement |
| --- | --- |
| Framework path alias startup | #563 actual source tests and diagnostic Resume/restart passed; ordinary build receipt and final source checks on PR |
| Partition playback/optimization latency | Confirmed slow path; #564 CI verified, device speedup unestablished; #567 bounded hashing in CI; #401 stays OPEN |
| Original/new-copy Delete and Move | Pre-opt-in legacy phone→card move hash/metadata verified; partition mutation completion/recovery still needs inspection and further acceptance |
| Opt-in/cancel/Ready | Explicit choice/cancel, Preparing refusal, Resume cancel preservation and fixed Ready startup checked on two-copy diagnostic fixture |
| Interrupted migration | Actual cancellation preserves originals and exact review entry; explicit retry/completion still pending |
| Absent-card lifecycle and large library | Not tested this cycle; no card eject, actual large-library memory/startup characterization remains |
| Hardware / accessibility / user experience | Bluetooth/headset, spoken/focus TalkBack, listening quality and remaining queue/library acceptance still user-led |
| Stable promotion | Release/benchmark remain false; acceptance then separately reviewed promotion/full checks. No Stable release permission |
| Accepted deferrals | #179 deep SD recovery and dependency/tool provenance remain planned post-release work; do not reopen as newly unapproved policy |

## Build receipts

- #562 source 615826a55f0b101a5a99de2b14683a4b39c181e6 diagnostic [.1107](https://github.com/averylicious/muon/actions/runs/39021956573), artifact12097188000, SHA25693d994f7d593c49815febf785eb95c64aabdc7a93cb4d1515a051e39fde2ccd3. Actual downloaded reports1,624 tests per variant, zero failures/errors/skips; lint0errors/68warnings each; actual original certificate/diagnostic package/debuggable/export/checksum verified.
- #563 source 0cf01755b231a0fcc68b5f7a4884af3bd54c4ad6 diagnostic [.1109](https://github.com/averylicious/muon/actions/runs/39023392504), artifact12098140390, SHA25679eca16f6daecf64e58f5217a0687a2e0381b097d866ba2715a8c0a91fa80b08. Actual downloaded reports1,626 tests per variant, zero failures/errors/skips; lint0errors/68warnings each; same actual diagnostic safeguards verified. Installed only Muon diag. No normal APK was installed.
- #563 ordinary [.1108](https://github.com/averylicious/muon/actions/runs/39023350674) attempt2 SUCCESS; artifact12098316679, SHA256c85f8bce0929e175fccddf5fe5c8404ba206bfbe206be2564204a85e3f7b49bd. Reports1,626tests per variant, zero failures/errors/skips, lint0errors/68warnings; actual normal package/certificate/nondebuggable/export/checksum verified, not installed. Attempt1 cancelled, not a pass.
- #564 ordinary [.1110](https://github.com/averylicious/muon/actions/runs/39024705271) attempt2 SUCCESS, artifact12098314145, SHA25681c1afddfe0aac021c87b69d7f0378d99a80d81cb6bb2af8546cbe162a1842c6; separate diagnostic [.1111](https://github.com/averylicious/muon/actions/runs/39024733900) SUCCESS, artifact12098600934, SHA25698a33fbcd234d12aaa985ce3e8527e856b6963886edfc20402702a60461c2f2e. Both downloaded report sets1,627tests per variant, zero failures/errors/skips and lint0errors/68warnings; actual signing/package/debug/export/checksum checked. Only diagnostic .1111 installed; no speedup/Delete completion claim. Cancelled normal attempt1 not a pass.
- Resumed #566 normal [.1113](https://github.com/averylicious/muon/actions/runs/39027947281) and #567 normal [.1114](https://github.com/averylicious/muon/actions/runs/39028065571) are in progress at this durable checkpoint, not passes. Later PR receipts supersede this pending snapshot. Branch APKs are not Obtainium releases; diagnostics are not performance-representative.

## Resumed engineering after cutoff

#566 fixes a confirmed global inventory defect: a single Pending original Delete makes MixedSavedAudio.inspect throw and aborts every unrelated saved row. A typed exact-key SavedCopyRetired omission keeps unaffected inventory/count accessible; generic I/O, unknown ownership and wrong-key refusals still fail the atomic scan. Playback suppression, original files/rows, ownership census and exact-token Settings recovery remain. Production-shelf/real-cache/real-index restart and explicit played census regressions added. Device comparison awaits this new build.

#567 changes only the retirement digest buffer8KiB→64KiB, matching existing migration block size. Full-file hash/EOF, actual per-read callback, before-unlink bindings and unknown-close quarantine remain. Adds multiple-block/short-tail, changed-tail refusal and between-block cancellation tests. Extra56KiB transient synchronous memory; a stalled filesystem call still has no new wall-clock deadline. Operation count decreases, but wall-clock speedup is not claimed.

## Disposition / continuing owner

GPT-6 / Codex desktop remains active on the two clean committed successor branches, with this documentation branch the only shared-checkpoint writer. At this checkpoint the POCO diagnostic .1111 is stopped, volume11, card mounted, original hashes/rows preserved, no debugger/profile/forward running. Next: finish latest-head CI, install only a verified separate diagnostic successor, test unaffected listing while preserving Pending, then explicit deletion/migration recovery and measured latency. Reserve final cleanup/checkpoint capacity; continue useful authorized work rather than ending at green CI. No Claude or experimental work. Keep application PRs open. Do not rely on temporary helpers/active debugger/phone process to resume; exact source, documented fixtures, issue/PR receipts and fresh authorization are the shared state.
