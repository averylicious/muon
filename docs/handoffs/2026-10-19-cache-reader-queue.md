# October19 renewed solo cycle: cache readers and queue limits

Supersedes the quiescent end state in [the preceding checkpoint](2026-10-19-native-cost-fallback.md). This is an ACTIVE checkpoint, not a claim that every operation below has finished. GPT-6 / Codex desktop, exact variant/effort not reported; implementation and source self-check. Claude unavailable and not resumed; no delegated writer. Current user explicitly renewed rooted POCO Muon testing. Main remains a2db02b8ba0b4959f28aecf5fa89196cf92622bc; experimental checkout untouched. App acceptance stack OPEN; no Stable/tag/release/production activation or experimental integration.

## Source / checks

| PR | Exact head | Scope | State |
|---|---|---|---|
| #612 | 0905bdb70e08b85a52dead0a196adbf2a7c9aeda | Diagnostic whole-Move phases | Ordinary .1259 and separate diagnostic .1263 verified in preceding checkpoint |
| #613 | f57d7aa2385026914678e29314eedaec9e3503fe | API35+ read-only metadata for Move verification | Ordinary .1262 / diagnostic .1265 verified; selected .1265 installed this cycle only while idle; install preservation proof passed |
| [#614](https://github.com/averylicious/muon/pull/614) | a889b2879ce3f87a390c8633890fa7b07870682b | Avoid intermediate flattened occurrence list before unique all-songs projection | [.1268/run39204047725](https://github.com/averylicious/muon/actions/runs/39204047725) passed; downloaded actual ordinary APK/reports verification running, not yet claimed |
| [#615](https://github.com/averylicious/muon/pull/615) | 3be6b662a7ba22e51d247ab5ac5f3d743b8ca512 | Completed cache-only playback uses API35+ transactional read-only metadata; native touches and writable paths unchanged | [run39205151223](https://github.com/averylicious/muon/actions/runs/39205151223) running, first Android compile/tests |

Both new branches use persistent isolated worktrees named for their topic, one solo writer; #615 includes #614. Pinned Media31.11.0 source / API35 evidence and scope limitations are in their audit notes/PRs. Source author self-check is not independent review. PR attachment attempts hit the chat's100-identity cap; direct PR URLs above remain authoritative.

## Completed device evidence

Selected USB POCO surya, Android17/API37, UID0 shell reverified. No ROM-setting/SELinux changes, no eject, no Pixel use. Ordinary .1251 remains installed; separate diagnostic updated .1263→.1265 while idle. Private raw stopped snapshots/prefs/SQLite/card tars remain ignored locally, never uploaded.

- .1263 Phone→Card: complete=true, total310143ms; selection19/admission1542/copy46515/publish21709/cover61854/retire178503/submission1ms.
- .1265 Card→Phone: complete=true, total283396ms; selection7/admission701/copy39405/publish46076/cover67660/retire129546/submission1ms.
- Both passed full semantic Move proof: exact completed request, distinct destination UID/key, full audio SHA/length/chunked metadata/binding, matching own cover; exact source retirement; only expected journal/derived-row changes; all unrelated original/saved/private/card files and records preserved. Normal-app full saved/native/private/card comparison passed. ProfileInstaller runtime cache separately parsed, not silently ignored.
- Same-direction preceding .1263 Card→Phone reference278887ms. .1265's283396ms does NOT establish a speedup; one run each is not a controlled benchmark. Nested digest/native timings overlap and cannot be summed/subtracted as independent costs. #401 remains open.
- On completion, disposable full copy is on PHONE; older partial copy/originals unchanged. Move finished, both apps stopped, own numeric log reader stopped, original music11 restored/read back. Optional private marker `readonly1265-card-phone-progress.json`: complete-full-move-proof, maybeActiveMove=false, fullPreservationProof=true.

## Active queue-refusal fixture

One solo read-only synthetic metadata UI driver is ACTIVE on ordinary .1251, no Move/download/delete operation. It uses only a phone127.0.0.1 fixture, temporary exact-backed-up connection preference and muted music; finally restores the exact preference/volume, stops own app/listener and removes known own files. Two workloads:2049 short records (item-count refusal),1000 records with long titles (logical metadata-byte refusal). On each, require the explicit smaller-selection dialog, Cancel returning to library and NO audio request. This does not establish preservation of a pre-existing nonempty playback queue; source/unit controls cover that separately. No synthetic audio supplied.

First attempt waited for ID0000 while remembered Recently added order displayed ID2048 first. It timed out before tapping; cleanup and stopped full original/saved/native/private/card preservation passed for both apps. Not an app defect, not a refusal pass. Corrected driver selects an actual visible short-title endpoint row, preserving the existing sort preference.

Optional ignored marker `queue-refusal-v2-progress.json` and private helpers/snapshots under the coordinator worktree `.local/qa-oct19/` support local recovery but are not portable prerequisites. Check actual driver/marker/device state before issuing competing phone actions. Do not force-stop a possibly active Move; this fixture itself owns no Move. Raw connection/prefs and device identifiers stay private. Final completed result/cleanup/proof must be appended before a handoff.

## Remaining / next

1. Verify #614 actual reports/selected APK and update its PR; finish #615 exact-head compile/check/reports/APK, fix failures within scope.
2. Finish queue-refusal observations and stopped preservation proof; record failed/unverified cases accurately. Only after cleanup consider a selected #615 diagnostic playback/seek/close check with disposable data. Installing alone is not storage opt-in.
3. #401 saved-card startup and minutes-long Move remain engineering/performance investigations; do not weaken fresh destination checks, identity, known close or per-file retirement guards to obtain a lower number.
4. #253 broader queue/IPC, artwork-heavy browsing and large saved-native inventories still need bounded runtime evidence. Current accepted-load/queue limits are logical budgets, not whole-process heap/Binder ceilings. User-required memory redesign is not cleared by this checkpoint.
5. Cumulative hardware/spoken TalkBack/subjective UAT remains pending; #179 deep SD lifecycle and dependency/tool/cache provenance have the user's explicit prior deferrals. Do not silently expand release gates or claim Stable readiness.

Continue useful authorized engineering across verified boundaries while actual capacity permits. Current account exposes a weekly window, no five-hour window; do not borrow another account's reading. Keep reserve for cleanup, failures and committed portable evidence.
