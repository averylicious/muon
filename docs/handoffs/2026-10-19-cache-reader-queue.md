# October19 renewed solo cycle: cache readers and queue limits

Supersedes the quiescent end state in [the preceding checkpoint](2026-10-19-native-cost-fallback.md). This is an ACTIVE source/CI checkpoint; all device operations described as completed below have finished. No phone driver/Move/listener is currently active. GPT-6 / Codex desktop, exact variant/effort not reported; implementation and source self-check. Claude unavailable and not resumed; no delegated writer. Current user explicitly renewed rooted POCO Muon testing. Main remains a2db02b8ba0b4959f28aecf5fa89196cf92622bc; experimental checkout untouched. App acceptance stack OPEN; no Stable/tag/release/production activation or experimental integration.

## Source / checks

| PR | Exact head | Scope | State |
|---|---|---|---|
| #612 | 0905bdb70e08b85a52dead0a196adbf2a7c9aeda | Diagnostic whole-Move phases | Ordinary .1259 and separate diagnostic .1263 verified in preceding checkpoint |
| #613 | f57d7aa2385026914678e29314eedaec9e3503fe | API35+ read-only metadata for Move verification | Ordinary .1262 / diagnostic .1265 verified; selected .1265 installed this cycle only while idle; install preservation proof passed |
| [#614](https://github.com/averylicious/muon/pull/614) | a889b2879ce3f87a390c8633890fa7b07870682b | Avoid intermediate flattened occurrence list before unique all-songs projection | [.1268/run39204047725](https://github.com/averylicious/muon/actions/runs/39204047725) passed; actual reports1,767 tests/variant, lint0errors/66warnings each; actual selected APK safeguards verified, SHA2566e82ffbbc232344a1603c222d34b6e5d9e4f86099bed4c6d6ec4797ea6839ade, artifact12165203469/reports12165178439; not installed |
| [#615](https://github.com/averylicious/muon/pull/615) | 3be6b662a7ba22e51d247ab5ac5f3d743b8ca512 | Completed cache-only playback uses API35+ transactional read-only metadata; native touches and writable paths unchanged | [.1270/run39205151223](https://github.com/averylicious/muon/actions/runs/39205151223) passed; actual1,768 tests/variant, lint0errors/66warnings each, selected APK safeguards verified, SHA2560a256bfc0676613e0e42143a9681bf898fe6b952669b44d42cc3c03583a2d1f0, artifact12165476933/reports12165526841; not installed |

| [#616](https://github.com/averylicious/muon/pull/616) | 58fc0880c35099210933f6bf4dbb9d4d51225df7 | Current playlist order/remember keys fix the reproduced duplicate-winner refresh defect | [.1272/run39205782215](https://github.com/averylicious/muon/actions/runs/39205782215) running, first compile/test |
| [#617](https://github.com/averylicious/muon/pull/617) | 624fa3630fa9bbb4ff72fd5a066e5fc143f65bae | Release already-hidden group holders, actual job cancellation and lazy input/credit traversal | [run39206162459](https://github.com/averylicious/muon/actions/runs/39206162459) running |
| [#618](https://github.com/averylicious/muon/pull/618) | 6cf78ebe92ee95b40a92bd4f6241047af91841ee | Admit at most100,000 expanded artist memberships before publication, including retained fallback, preserving prior library on refusal | [.1274/run39206331432](https://github.com/averylicious/muon/actions/runs/39206331432) running |

New source branches use persistent isolated worktrees named for their topic, one solo writer each; each later numbered source PR includes the preceding one. Pinned Media31.11.0 source / API35 evidence and scope limitations are in their audit notes/PRs. Source author self-check is not independent review. PR attachment attempts hit the chat's100-identity cap; direct PR URLs above remain authoritative.

## Completed device evidence

Selected USB POCO surya, Android17/API37, UID0 shell reverified. No ROM-setting/SELinux changes, no eject, no Pixel use. Ordinary .1251 remains installed; separate diagnostic updated .1263→.1265 while idle. Private raw stopped snapshots/prefs/SQLite/card tars remain ignored locally, never uploaded.

- .1263 Phone→Card: complete=true, total310143ms; selection19/admission1542/copy46515/publish21709/cover61854/retire178503/submission1ms.
- .1265 Card→Phone: complete=true, total283396ms; selection7/admission701/copy39405/publish46076/cover67660/retire129546/submission1ms.
- Both passed full semantic Move proof: exact completed request, distinct destination UID/key, full audio SHA/length/chunked metadata/binding, matching own cover; exact source retirement; only expected journal/derived-row changes; all unrelated original/saved/private/card files and records preserved. Normal-app full saved/native/private/card comparison passed. ProfileInstaller runtime cache separately parsed, not silently ignored.
- Same-direction preceding .1263 Card→Phone reference278887ms. .1265's283396ms does NOT establish a speedup; one run each is not a controlled benchmark. Nested digest/native timings overlap and cannot be summed/subtracted as independent costs. #401 remains open.
- On completion, disposable full copy is on PHONE; older partial copy/originals unchanged. Move finished, both apps stopped, own numeric log reader stopped, original music11 restored/read back. Optional private marker `readonly1265-card-phone-progress.json`: complete-full-move-proof, maybeActiveMove=false, fullPreservationProof=true.

## Completed queue-refusal fixture

One solo read-only synthetic metadata UI driver completed on ordinary .1251, no Move/download/delete operation. It uses only a phone127.0.0.1 fixture, temporary exact-backed-up connection preference and muted music; finally restores the exact preference/volume, stops own app/listener and removes known own files. Two workloads:2049 short records (item-count refusal),1000 records with long titles (logical metadata-byte refusal). On each, require the explicit smaller-selection dialog, Cancel returning to library and NO audio request. This does not establish preservation of a pre-existing nonempty playback queue; source/unit controls cover that separately. No synthetic audio supplied.

First attempt waited for ID0000 while remembered Recently added order displayed ID2048 first. It timed out before tapping; cleanup and stopped full original/saved/native/private/card preservation passed for both apps. Not an app defect, not a refusal pass. Corrected driver selects an actual visible short-title endpoint row, preserving the existing sort preference.

Optional ignored marker `queue-refusal-v2-progress.json` and private helpers/snapshots under the coordinator worktree `.local/qa-oct19/` support local recovery but are not portable prerequisites. Check actual driver/marker/device state before issuing competing phone actions. Do not force-stop a possibly active Move; this fixture itself owns no Move. Raw connection/prefs and device identifiers stay private. Corrected run PASSED both refusal dialogs and Cancel, with zero audio requests. Observed host/UI intervals11.422s/11.762s are not app-only timings. Exact connection/music11 restored/read back, apps stopped, own listener/files removed. Stopped full saved/native/private/card preservation passed for both apps; marker complete-full-preservation-proof. No driver remains.

## Reproduced ordering defect / source follow-ups

Synthetic ordinary .1251 case: two playlists share one ID with conflicting titles; order1,2 displays title1, then refresh lists2,1. Playlists visibly reversed but Songs stayed title1. No playback/audio. Full stopped original/saved/native/private/card proof passed for both apps; exact connection/music11 restored, own listener/files removed, apps stopped. This proves the narrow reorder/equal-map defect, not that normal Tauon commonly returns conflicting duplicate metadata. #616 fixes explicit order and remember keys; pinned runtime1.9.3 source SHA256 a8af436829e801385e2e52e00e13283bcb1cdb1799e76beb5c9498b1955afd46 confirms structural equality/remember behavior. Fixed-head replay pending.

#617 removes hidden-old-group retention during replacement and makes cancelled grouping loops observe the real Job, while preserving all grouping rules. #618 bounds derived credit cardinality using the exact shared parser before publication; every fresh/fallback occurrence is charged, refusal does not spend partial counters or drop old data. These are source-supported memory fixes, not reproduced OOM/absolute heap or speed claims. Their notes/PRs preserve precise tests and runtime limitations.

## Remaining / next

1. Finish #616–#618 exact-head CI and actual reports/selected APK verification; fix failures in scope. #614/.1268 and #615/.1270 are verified. Initial verifier invocation guessed #615 version1269; actual BUILD.txt is1270, corrected argument and full verification passed; no app/build failure.
2. After newest cumulative APK verification, consider idle install with fresh stopped preservation, then synthetic order/credit-admission/grouping checks and selected saved-playback/seek/close. Ordinary root-accessible APK can cover these without a duplicate diagnostic build; choose actual identity/version before installation. Current normal .1251 has one completed3,149,055-byte card copy; diagnostic .1265 has the separate completed phone copy. Originals/older partial controls remain untouched.
3. #401 saved-card startup and minutes-long Move remain investigations. “Cover” phase includes published→Pending guard work, not just image I/O. Never weaken fresh destination checks, identity, known close or per-file retirement guards to obtain a lower number.
4. #253 broader queue/IPC, artwork-heavy browsing and large saved-native inventories still need bounded runtime evidence. Current accepted-load/credit/queue limits are logical budgets, not whole-process heap/Binder ceilings. No blanket #253 closure.
5. Cumulative hardware/spoken TalkBack/subjective UAT remains pending; #179 deep SD lifecycle and dependency/tool/cache provenance have the user's explicit prior deferrals. Do not silently expand release gates or claim Stable readiness.

Continue useful authorized engineering across verified boundaries while actual capacity permits. Current account exposes a weekly window, no five-hour window; do not borrow another account's reading. Keep reserve for cleanup, failures and committed portable evidence.
