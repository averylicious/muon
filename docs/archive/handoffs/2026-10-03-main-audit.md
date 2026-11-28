# Main audit checkpoint — 2026-10-03

This supersedes the [Oct2 checkpoint](2026-10-02-main-audit.md). Refresh current heads, checks, protection, ownership and device authorization before resuming. Final PR/#181/#40 receipts supersede this dated snapshot.

## Scope and ownership

Main correctness/security/privacy/performance/CI audit continues. The user's Expressive checkout/session was not switched or edited. GPT-6 / Codex desktop coordinates; effort not reported. The explicitly allocated audit Claude ran Opus5.5 (`claude-opus-5-5`) with High selected; init model verified, runtime does not expose effort. One writer per dedicated checkout; portable state does not require a local session ID.

Claude finished cache path/metadata controls, wrapper report and both QA integrations and is **idle**. Coordinator finished source/CI reviews and the limited device checks below. No unfinished app edits or running Claude assignment at this boundary. App candidates remain open for **user acceptance**; coordinator observations do not waive that gate. No Stable release/tag or auto-merge.

## Landed this cycle

| PR | Exact reviewed head / CI | Result |
| --- | --- | --- |
| [#285](https://github.com/averylicious/muon/pull/285) cache span paths | `2aace9f97e7e1bbdf94a5a2348f1c994c3535219`, [run484](https://github.com/averylicious/muon/actions/runs/37110668873),438 tests/variant,0 failures/errors/skips | Database-backed touches replace span objects without renaming their files; legacy-index control renames and a public listener follows. Coordinator fixed clock dependence deterministically. Test-only, not recovery. Main8b443ba/.486 publication and BUILD verified. |
| [#286](https://github.com/averylicious/muon/pull/286) Stable tag ancestry | `23a9285c3c0f6401e28f9d8b59a2f0efc040e092`, [run487](https://github.com/averylicious/muon/actions/runs/37111161011),438 tests/variant,0 failures/errors/skips | Canonical Stable tags must resolve to workflow SHA, belong to fetched main and exclude alpha ancestry, with complete history. Ten real-Git-graph controls; #284 closed. Main28a1329/.488 publication verified. No tag created. Trusted workflow writers/hand-copied alpha remain outside guard. |
| [#287](https://github.com/averylicious/muon/pull/287) metadata sidecar feasibility | `2a54fd7db2322abe3c68c5adfeff3a70b49979a9`, [run489](https://github.com/averylicious/muon/actions/runs/37112200809),440 tests/variant,0 failures/errors/skips | Actual SimpleCache/AtomicFile controls: multiple spans/full raw metadata round-trip and explicit failWrite rollback; originals retained. Not production recovery, capture completeness or crash durability. Concrete metadata cast and SDK34-runtime/SDK37-source limits recorded. Merged at main7bf5bed; .492 publication verified. |
| [#288](https://github.com/averylicious/muon/pull/288) wrapper trust report | `162a4b70c4bc5a8280fc0572850019833df685b4`, [run493](https://github.com/averylicious/muon/actions/runs/37112710603),full build successful after destination refresh | Tracked wrapper JAR matches official checksum. Cached checksum allowlist is a trust-boundary observation, **not a demonstrated vulnerability**. Focused source/partial-bundle review; cache provenance, full bundle/post action and wider supply chain remain unresolved. Original490 was docs-only; final493 built due unpublished app-test ancestry. Main `275b647fb217381ea3a8479ed0de04118912a899`; [run495](https://github.com/averylicious/muon/actions/runs/37113323032) documentation-only success,zero artifacts. |

Latest verified released Canary: [.492](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.492), [main run492](https://github.com/averylicious/muon/actions/runs/37112703008), target `7bf5bed09ab82cf973272e2c5edc95b798efb7e3`. Prerelease/assets and downloaded BUILD.txt agree with the full commit. Later report-only main495 generated no APK/release. Installed QA candidates below are **Actions artifacts**, not Obtainium releases.

Strict main protection was read back before merges: Build, test and sign plus Branch direction required from GitHub Actions; up-to-date checks/admin enforcement on, force push/deletion off. Reverify before merging.

## Open combined QA candidates

### #289: mounted-card and move protections

[#289](https://github.com/averylicious/muon/pull/289), branch `codex/storage-qa-oct3`, head `3635908752b1f572bd15d22c1fd26ad43ee7bcf3`, includes main7bf5bed (behind only wrapper report at this snapshot).

Exact components: #264 `215f2ea762fb10d1083607ddd38d3ec0c96c68ec`, #281 `2b7ad536acd2b4f500a9a38418e1d3a5cface2b3`, #234 `370f74d2d43684cec7a313db3fb7b1feb79fb901`. Claude resolved OfflineStore conflicts retaining availability decisions, full cache-only byte comparison and removal/publication ownership. Coordinator reviewed actual combined source; old self-authored fixes are self-checks, not independent review. Excludes #212/#237/#240/#248, network/queue/library and alpha.

[Run494](https://github.com/averylicious/muon/actions/runs/37112859436) successful, **461 tests/variant**,0 failures/errors/skips; downloaded XML confirms move/ownership/availability controls. Lint, APK identity/manifest/publication guards pass. Former491 cancelled on destination refresh.

[Canary .494 artifact](https://github.com/averylicious/muon/actions/runs/37112859436/artifacts/11271360096), `app-debug-3635908752b1f572bd15d22c1fd26ad43ee7bcf3`. BUILD.txt/SHA256 verified before `adb install -r` on POCO; Stable untouched.

### #290: network, queue and library interactions

[#290](https://github.com/averylicious/muon/pull/290), branch `codex/interaction-qa-oct3`, head `d004ab4046bcd7e67c0873cac7d37a4ced540cfe`, includes main275b647.

Exact components: #257 `555bd0c2117a7e963cc5522ec7e7272acf733802`, #267 `a6974c32a7cc674a3c756c678a4e5ea51be65f86`, #273 `18542f7593076925ce9f9a0726faf6f295bd7a5f`. Claude integration, no textual conflicts; coordinator reviewed shared source wiring. Occurrence UUID retains SONG_EXTRA/mediaId, sheet cancellation gates queue action, current-load gating owns offline state, Undo checks current controller. No storage or alpha included. Attribution distinguishes participating component reviews from self-authored fixes.

[Run496](https://github.com/averylicious/muon/actions/runs/37113428681) successful, **514 tests/variant**,0 failures/errors/skips; downloaded XML confirms queue insertion/removal, sheet actions, network requests and library loads. Lint, identities/manifest/publication guards pass. Separate unsigned inventory run15 also passed; no artifact-integrity/supply-chain clearance inferred.

[Canary .496 artifact](https://github.com/averylicious/muon/actions/runs/37113428681/artifacts/11271106641), `app-debug-d004ab4046bcd7e67c0873cac7d37a4ced540cfe`. BUILD.txt/SHA256 verified before `adb install -r` on Pixel; Stable untouched. This preserves the network candidate fixes previously installed as .474.

Both candidates and their constituents **remain open** until user acceptance. Refresh current main, latest head and required checks before eventual merges. Older component APKs can be Android version-code downgrades; do not uninstall or change signing. Branch artifacts expire after14days.

## Device checks: observed, not user acceptance

User authorized both phones this cycle and explicitly **deferred Pixel lockscreen controls**. No settings/ADB experiments to force lockscreen controls. Official [Android17 notes](https://developer.android.com/about/versions/17/release-notes) document a disappearing-widget fix in Beta4; [QPR2 notes](https://developer.android.com/about/versions/17/qpr2/release-notes) document unwanted lockscreen flashing with notifications disabled. Neither establishes this QPR1 Stable phone's cause or an intentional removal. Lockscreen gate remains deferred.

**POCO X3 NFC, Android16, mounted SanDisk, .331 → .494:**

- Before installation, copied all16 external app files (38,335,541 bytes) and verified their SHA256 against the phone. Non-debuggable run-as denied protected DB/index access: **file backup only, not complete app-state/ledger recovery**. Private filenames/addresses/card IDs/screenshots remain local, outside GitHub.
- Declined moving the15 existing card downloads to the phone. Downloaded **one new disposable copy** onto the phone, then moved the source shelf's **sole** download onto the mounted card. Desktop original retained. Download count changed15→16; all existing card hashes unchanged.
- Selected test copy while online; session progressed without reported playback error. **Offline playback is inconclusive:** approved single-server auto-connect raced the offline-opening attempt and reconnected. One coordinate action selected another song after that UI transition; playback was paused. No Wi-Fi/settings workaround used, and no offline/audibility pass claimed (volume0).
- Removed only the new test download through its verified Remove download action. Final UI15 songs/38MB; card file count16, **all original hashes unchanged**, zero added/missing files. Store on SD=true/cache limit5GB/original endpoint restored. Played-song cache grew from about22MB to25MB during QA; deliberately not cleared. Playback paused, Home, media volume remains baseline0/25.
- No eject/unmount, absent-card, replacement-card, mid-IO interruption, reverse bulk move or Remove all. Those risks, including #179, remain unresolved. Do not run destructive tests against existing user downloads.

**Pixel8, Android17, .474 → .496:**

- Paused a library-started962-entry queue. Play next on the same current song followed by the observed Undo preserved queue962, current occurrence and paused position52127ms. Intermediate963 count not measured; no broad queue/race coverage inferred.
- Opened another song's actions and cancelled via Android Back: queue/current position unchanged, no reported playback error. Mid-animation/double-tap cancellation remains untested on-device.
- Scrolled Artists, opened an artist and returned: **eight visible rows matched their prior exact bounds**, header fold retained. Scrolled that artist page, opened one album and returned: **seven labels/rows matched exact bounds**.
- Home then returning the **same existing task** retained all eight root artist row bounds and playback. An earlier unflagged `am start` created a new Activity and reset that new Activity's list; excluded as an invalid resume test. Removed the extra test Activity by Back; task returned to its original instance. No fix inferred from that harness artifact.
- Restored Songs/A–Z. Paused playback, Home, volume remains baseline0/25. No permission/OS/Wi-Fi/rotation/font/theme settings changed; no phone uninstall/data clear. Only our own temporary UI dump was removed.

Remaining user/hardware checks: headset/Bluetooth and notification/controller compatibility; Pixel lockscreen deferred; genuine offline use, unavailable/remounted card and removal/move races in an isolated disposable setup; queue reorder/removal/reconnect Undo, mid-animation sheet cancellation, network load interruption, rotation/short viewport. Coordinator observations are a narrow subset, not a complete QA pass.

## Resume and next source boundary

1. Read live #289/#290 comments and ask for user acceptance of the tested candidates only where needed; do not merge them on the strength of coordinator observations.
2. #179 production preservation remains the next major source question: generation catalogue, quiescent writer ownership, durable completeness, volume identity, migration and service-swap ownership. Span/path/metadata fixtures do not implement those invariants. #213 identity, #230 partial-target cleanup and #253 aggregate retained-resource budget remain open.
3. Source audit can continue independently with one bounded ownership/generation question or aggregate resource policy. No phone OOM/data-loss experiment or unapproved destructive cap. Preserve the user experiment; forward integration is a separate PR for its owner.
4. No overall security score, measured performance benefit or Stable-readiness percentage claimed. Earlier parked PRs/gates are in Oct2 and live #181. Recheck both agents' usage before assigning more work; Claude is idle and capacity was reserved for fixes/handoff rather than deliberately reaching a hard limit.

Owned QA branches are clean/committed. The checkpoint revision/CI is recorded on its documentation PR and #181/#40; no self-referential SHA is embedded here. A successor needs repository/PR access, not private backups, OAuth credentials, session transcripts or local tool history.
