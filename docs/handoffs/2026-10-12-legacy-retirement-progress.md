# Partition retirement engineering checkpoint — 2026-10-12

This supplements [the startup/played checkpoint](2026-10-12-storage-startup-played-integration.md). Latest product decision: **full Delete/Move for legacy originals is required before partition activation**; protected/read-only originals are not the accepted substitute. Partition mode remains disabled. Main remains 5a2c47f8c21517de7801ccadfe108c9ea6206dbf (docs-only #504); the application stack below is OPEN for inherited acceptance and has not been published from main.

## Ownership and scope

GPT-6 / Codex desktop is sole implementation/source-self-check writer; exact variant/effort not reported. No independent review claimed. No Claude/phones this cycle. The experimental checkout/session remains untouched. Isolated persistent worktrees use branch-name topics under ~/.codex/worktrees/. #507–#514 worktrees are committed and idle except evidence checks. #516 Delete and #517 local-copy preparation are committed under CI; the main-based checkpoint worktree is the only documentation writer. Further authorized engineering continues after this checkpoint; refresh actual dirty work and live GitHub before takeover.

Last live account reading: 30% weekly used, no five-hour reading exposed. This is a dated usage observation, not a future budget/stop signal. Continue useful authorized engineering beyond a green build/PR/checkpoint, reserving capacity for repairs and handoff. No device permission is implied by old reports.

## Exact-head verified builds

Downloaded reports and actual APK verification were inspected, rather than treating a green job as complete evidence. Every row below has zero test failures/errors/skips in BOTH variants, lint zero errors/69 warnings, and verified checksum/certificate/package/nondebuggable/export policy. Branch artifacts expire after 14 days, update existing Canary and are not Obtainium/main releases.

| PR | Head | Android run | Tests per variant | Canary artifact ID |
| --- | --- | --- | --- | --- |
| [#507](https://github.com/averylicious/muon/pull/507) | 357430faed3f7e7ca2ba8cb22cc8c025cafc716a | [.993](https://github.com/averylicious/muon/actions/runs/38246028823) | 1382 | 11745982975 |
| [#508](https://github.com/averylicious/muon/pull/508) | d4e9c138553909b734aebbe8c629d1868668d7fc | [.997](https://github.com/averylicious/muon/actions/runs/38246944116) | 1384 | 11745859026 |
| [#509](https://github.com/averylicious/muon/pull/509) | 0015d182a211df4dd0432302ec4a091cfc02936e | [.998](https://github.com/averylicious/muon/actions/runs/38247478092) | 1388 | 11746114586 |
| [#510](https://github.com/averylicious/muon/pull/510) | c8e543cfa9969c1173bd3733a3b0670c8c71f9a4 | [.1000](https://github.com/averylicious/muon/actions/runs/38248511291) | 1393 | 11747786930 |
| [#511](https://github.com/averylicious/muon/pull/511) | 858ccf9a1d0d641518040dad15a8a2457c7e2229 | [.1001](https://github.com/averylicious/muon/actions/runs/38250052259) | 1397 | 11746969364 |
| [#512](https://github.com/averylicious/muon/pull/512) | d8258de2cb21bc7fd62636123cdbd41fc14b4af2 | [.1002](https://github.com/averylicious/muon/actions/runs/38250761155) | 1403 | 11749235309 |

An artifact name is app-debug-FULL_HEAD from this table; its link is the corresponding run URL plus /artifacts/ARTIFACT_ID. ZIP contains app-debug.apk, SHA256SUMS and BUILD.txt. Latest verified APK is 0.1.0-canary.1002, SHA256 3f37269d6cfed4e5ff9639f4bd301bcc3d68a6a0b359e78985a2ecc960525d3e. It contains the cumulative main audit stack, not the user's experimental UI. No installation/phone/performance verification occurred.

#507's first run .991 had a new fixture native-capacity mismatch; .993 corrected it. #508 .994 had a missing test import; .997 corrected it. #510 .999 was superseded by .1000's owning-worker fixture cleanup correction. These failed/cancelled runs are not passes. #507–#510 also predate main's docs-only #504 and their Branch direction checks require refreshed ancestry; successful Android builds alone do not make them merge-ready. #511 included current main and Branch direction .948 passed at its exact head. Refresh all required checks on whichever cumulative acceptance head is eventually merged.

## Prepared engineering

- #507 uses a disposable disk-backed played order and mutation-generation checks rather than repeated full-library native opens after every eviction. Unknown derived-index close retains the actual owner. A known active played admission prevents session close.
- #508 routes bounded Remove all partition selections through confirmed actual copy/row removal, never destructive Media3 Remove tasks. Outcomes distinguish removed, deferred and unconfirmed work; unsupported originals remain intact at this revision.
- #509 captures bounded identity/digest manifests from the supported read-only old native index; no legacy SimpleCache or private native SQL write.
- #510 durably records explicit Pending intent and persistent Complete tombstones; constructor has no I/O. Actual cursor/statement/database uncertainty remains owned, with no rollback/close over unknown children.
- #511 registers/pins the real journal within shelf lifetime; Pending/Complete suppress legacy and Ready routes, reserve names and block later migration resurrection. Actual EOF readers block exclusive intent. Preparation runs off main; restart and foreign/replaced-journal checks are covered.
- #512 removes only consented manifest files under actual source/gate ownership. Exact Pending can recover already-missing selected files. Unknown unlink/reader/cursor close quarantines actual owners. Journal remains Pending because the files stage is not complete user-visible Delete.

## Further verified and active heads

- #513 head 80d423cb1dd82003b15f9f636e89bdf3a1d3338f passed .1003 (38251513510): 1405 tests per variant, lint zero errors/69 warnings, actual APK checksum/package/certificate/nondebuggable/export verification. Artifact 11748681995; APK SHA256 fa4f0c7f2bacf7949c795c109406f0e9182486a0ef4cbe05101881b41829d1ec.
- #514 head 3536bf82bc7710e9f938c75383c9fc2b829b67aa passed .1004 (38251991546): 1409 tests per variant and same report/APK safeguards. Artifact 11749242205; APK SHA256 22210406c2a29ecd60b71df5fbb1a8eeb672a51e7100071c07d1e024d77e3e1d. These supersede the .1002 latest-verification snapshot above.
- [#516](https://github.com/averylicious/muon/pull/516), codex/legacy-completed-delete-oct12, head 37828e9a46fa1989d8472e6d5f52ce6bdf83a027: completed-original Delete coordinates exact row-bound intent, Ready/original bytes, public terminal-row removal, queued-cover cancellation and confirmed own-cover absence before Complete. Exact Pending recovery requires renewed consent. .1006 compiled but failed an older bulk fixture that expected supported originals protected and lacked actual cover gates. Corrected .1007 (38254925320) is green; downloaded reports/APK verification still in progress, so do not call the receipt complete yet. Checkpoint docs/handoffs/2026-10-12-legacy-completed-delete.md.
- [#517](https://github.com/averylicious/muon/pull/517), codex/partition-local-saved-copy-oct12, head 260d55ecf94e3b9b81cbb0213e71de7713c657c8: actual source/destination owner copy preparation preserves full metadata, positive exact length and local-only source. It reopens exact Closed destination and byte-compares, keeping actual source/target/native ownership through close. Source record/audio stay intact and no destination manager completion is invented. Both shelves require the identical gate. .1010 (38255911006) compile/tests/APK pending; .1008/.1009 superseded. Checkpoint docs/handoffs/2026-10-12-partition-local-saved-copy.md.

Each application PR adds its focused source checkpoint. All are cumulative main-target successors; do not merge independently out of order or mistake source-stage availability for production activation. Attachment attempts hit the chat's 100-identity limit; PR/issue/document links are portable and unrelated attachments were preserved.

## Remaining engineering and release gates

1. Finish completed-original Delete verification/failure coverage (#516), plus supported nonterminal/unverified-original disposition. The old read-only native index retains obsolete per-resource/file metadata after selected audio files and terminal row are removed; document/resolve retention honestly before activation. Files-only/unbound intent grants no terminal-row deletion authority.
2. Full partition-aware Move with verified destination before source retirement, bounded retention, clear cancellation and exact crash/restart recovery. #517 prepares/byte-compares the actual destination; durable Move intent, destination-row/cover publication and destination-bound source retirement/recovery remain engineering. Existing same-ID legacy-cache move cannot simply be reused over partition writers/fresh-name claims.
3. Persist production Fresh/Resume selection, explicit opt-in/free-space warning, migration progress/cancel/retry and readable Pending/Uncertain recovery controls. Do not enable the selector before full supported Delete/Move and composed failures are verified.
4. Final composed lifecycle/failure coverage, optional new-copy played LRU make-room (currently declines when full), and #401 saved-card startup characterization. Source/CI tests do not establish device startup latency or storage-removal behavior.
5. Manual release acceptance remains pending from the inherited stack, including hardware/storage, playback, queue/library and spoken TalkBack checks. Notify the user when a production-capable candidate specifically needs POCO testing; no phone needed for the preparations above.

#179 deeper SD recovery and dependency/tool provenance follow-ups retain the user's documented deferrals. This checkpoint does not reopen those accepted deferrals, publish Stable, activate partition mode or alter experimental ownership.
