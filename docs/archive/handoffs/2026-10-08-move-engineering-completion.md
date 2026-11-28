# Move engineering completion and release-trust disposition — 2026-10-08

This supersedes the partial #230 engineering status in the earlier October8 command/runtime checkpoint. Refresh live heads, CI and user QA before resuming. Source candidate PRs remain OPEN; this main-based checkpoint does not land application changes or authorize Stable publication.

## Verified application heads

| Candidate | Exact head | Latest full build | Downloaded test evidence |
| --- | --- | --- | --- |
| [#386 strict move output](https://github.com/averylicious/muon/pull/386) | `6eb95a9df4cbd34a997248545e7629ce79866adc` | [Android740](https://github.com/averylicious/muon/actions/runs/37674963883) | 727 tests EACH variant; zero failures/errors/skips |
| [#387 full acceptance successor](https://github.com/averylicious/muon/pull/387) | `75c8046e96b99152a11cb3d4187926a3ffa6a1fb` | [Android742](https://github.com/averylicious/muon/actions/runs/37675878017) | 734 tests EACH variant; zero failures/errors/skips |

Both actual downloaded lint reports: zero errors, 46 warnings per variant. After final PR-body evidence updates, [refreshed strict-sink direction](https://github.com/averylicious/muon/actions/runs/37677166912) and [refreshed delivery direction](https://github.com/averylicious/muon/actions/runs/37677172623) passed on these exact heads. Read latest checks before merging. Android741 at superseded `2cafac5d6ea3800e0a81377ffdf019a1cb88265c` was cancelled, not passed. Earlier739/732 tests and738/726 tests passed but are superseded. No local Gradle/Android compile; Actions was the first compile/test run.

Both heads include main `2a9346d36438459926f4bc21d92ca7eea818e7eb`. #387 integrates #386 and the full #384/#381/#377/#376/#373 acceptance ancestry. Later documentation merges may advance main: refresh destination ancestry and required checks before any eventual application merge.

## Actual Canary742 artifact

- [Successful Actions run](https://github.com/averylicious/muon/actions/runs/37675878017), workflow run742, version `0.1.0-canary.742`.
- [Canary artifact11506778845](https://github.com/averylicious/muon/actions/runs/37675878017/artifacts/11506778845), `app-debug-75c8046e96b99152a11cb3d4187926a3ffa6a1fb`. Reports artifact11507058598; release artifact11506229221 also exists.
- Downloaded BUILD.txt identifies the full head/run/version. APK SHA256 `95720ca04121b5a908582b68576a8de90936d41bcf6f0ce6141ca8c4b670302d` matches SHA256SUMS.
- Actual apksigner certificate SHA256 `d3d79b0ec48ff937b0dc3093f8a47ac8b946d4bd6ac38e2bef54a87d10525fb7`, existing Canary identity. Manifest: package `dev.avery.muon`, non-debuggable; PlaybackService, MuonDownloadService and MuonCardDownloadService private.
- Updates existing Canary/data; artifact expires after14days and is not an Obtainium release. The artifact is the full acceptance stack, whereas main's last app publication [.734](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.734) excludes it. Main737 ran lightweight docs checks without APKs.

## #230 defined engineering scope complete; UAT pending

Original finding: move completion could re-add a saved song after user removal, and failed output lacked safe ownership/cleanup evidence. The final candidate provides:

1. Commands admitted before Media3 manager mutation, copy cancellation and refusal of overlapping destructive work.
2. Full source/destination extent, sole-owner and bounded byte comparisons, including existing prefixes on retry. Unknown/mismatched/shared spans are refused and preserved.
3. A move-only strict writer observes flush, descriptor sync and close before committing each span; sticky failure survives Media3 quiet-close paths. Coordinator review found Media3 startFile only returns a pathname: atomic createNewFile now refuses pre-existing bytes without opening/truncating/deleting them.
4. Opaque process-local move tokens bind exact shelf objects and requests. Owned Add must actually be admitted; stale, replayed, mismatched or process-recreated commands become INIT. An epoch rejects automatic removal after intervening ordinary mutation.
5. Source Remove is queued serially only after target completion and checks, revalidated at real service delivery, and ownership retained until the exact manager callback acknowledges it. Invalidation cannot drop an accepted removal's barrier. Conflicting operations refuse with retry rather than deleting uncertain records.
6. The earlier card binding/availability refusal now releases only a queued owned receipt, permitting a healthy explicit retry; it never releases a Remove already admitted to the manager.

Native SQLite/disposable SimpleCache tests exercise actual OfflineStore.move, both real Muon services and manager callbacks with a network-free fixture downloader. Coverage includes healthy copy/removal/acknowledgment, delayed Add after user removal, replay/mismatch, recreated ownership registry, changed source request/epoch, serial multi-copy removal, wrong acknowledgment, cancellation while Removing, and injected late card refusal followed by explicit retry. Strict sink fixtures cover open/write/flush/sync/close/commit failures, exclusive-file collision and CacheWriter quiet-close. Production copy fixtures prove sync/close refusal preserves source rows/bytes and permits retry. Fixture requirements differ from production to avoid network use; no hardware/network behavior is established.

**Preservation contract:** delete only exclusively created, never-committed private sink files. Preserve uncertain commits, existing partial prefixes, unknown/legacy/shared bytes and recorded copies. Retry validates retained bytes against the original. No automatic orphan collection, storage migration or general cache/index transaction added. A stuck accepted removal remains busy until recovery/restart rather than authorizing uncertain deletion. This conservative behavior and retained storage are explicit tradeoffs.

**Not claimed:** arbitrary/root writer exclusion, physical card disappearance while alive, cache/index/power-loss durability, phone reboot or real-device playback/accessibility acceptance. Full SD recovery #179 stays separately user-deferred/unresolved; do not reintroduce it as a #230 Stable blocker without a new decision. Issue230 stays open until UAT and implementation landing.

## Acceptance and remaining release gate

| Area | Next work |
| --- | --- |
| #230 | User acceptance on disposable phone/card data: normal multi-song moves and destination playback; remove/save/Remove all conflict refusal; cancellation/failed move retains originals and safe explicit retry. Keep #386/#387 and inherited app stack open. |
| #213 retained identity | Selected live-stream/unverified-saved-copy policy implemented in acceptance ancestry; user acceptance still pending. |
| #253 aggregate resources | Engineering remains: existing bounded readers/predecode/cursor controls do not establish a total memory/resource budget. Inspect its current checklist before one bounded next slice. |
| Other inherited application gates | Bluetooth hardware controls, full spoken/focus TalkBack acceptance and remaining queue/library interactions require recorded user results. Prior automated receipts are limited evidence, not blanket UAT. |
| #179 full SD recovery | User-deferred separate follow-up. Issue closure is not proof of recovery. |
| Build/dependency trust | Both remaining verification and hosted-tool/cache provenance deferred for this release by explicit user decision below. |

## Explicit release-trust disposition

The user chose **defer both reviewed dependency verification and remaining hosted runner/SDK/generated-cache provenance for this release**, then clarified the intent: resume planned maintenance after the current main release and mature M3 Expressive integration. This does not abandon the work or declare the dependencies safe. No Stable tag/release is authorized by the disposition.

Keep current full action pins, reviewed wrapper/distribution/JDK controls, inventories/advisory evidence, signing/publication boundaries and required-check protection. Generated inventory checksums are observations, not independent publisher authentication. Remaining accepted limitations include unauthenticated dependency/tool/generated-cache bytes, incomplete task coverage and qualified advisory caller questions. No demonstrated compromise was established. Native Kotlin/Compose avoids a JavaScript/npm layer, but Gradle/Maven/plugins/SDK/runner still form a supply chain; framework choice alone is not a quantified risk assessment.

Resume from [dated build-trust disposition](../audits/2026-10-07-build-trust-disposition.md) and the runtime-inventory checkpoint; establish a reviewed trust basis for all relevant build/test/lint/baseline/plugin/detached resolutions before enforcing verification. Re-evaluate changed libraries/build tools after experimental integration. Do not silently turn current-cache hashes into trusted metadata or disable present controls while work is deferred.

## Ownership, usage and safe resumption

- GPT-6 / Codex desktop, exact variant/effort not reported: delivery implementation/tests, strict writer exclusive-file correction, late card refusal correction, integration, Claude initial source review and author self-review of own changes. No independent whole-stack approval claimed.
- Claude Opus5.5 (`claude-opus-5-5`), Claude Code, explicitly High: initial strict writer implementation/tests completed. Allocated audit session stopped idle after that work; a proposed read-only delivery review did NOT execute because automatic compaction reached the session quota. Last runtime106%five-hour/64%weekly USED, no overage; Claude exhausted first. Do not claim that review completed or re-run the exhausted session this window.
- Coordinator account last measured60%five-hour/20%weekly USED before final checkpoint work; refresh current readings rather than reuse them. No need to exhaust capacity after this reviewable boundary.
- No ADB/device command or experimental checkout change this continuation. Current-cycle device authorization is not a successor's standing permission. No keys/credentials/1Password Environment accessed or committed.
- Source worktrees: `~/.codex/worktrees/muon-move-completion-receipt-oct8` and `~/.codex/worktrees/muon-move-delivery-ownership-oct8`; final checkpoint `~/.codex/worktrees/muon-move-completion-final-checkpoint`. Sources clean and pushed at recorded heads. Verify status, live agents, PR heads and destination before reuse; one writer per branch/session. Local logs/session IDs are optional, never recovery prerequisites.
- Live main protection read-back: strict/up-to-date Build, test and sign + Branch direction from GitHub Actions app15368; admin enforcement on, zero approving reviewers, force-push/deletion off. Recheck before relying on it. No auto-merge/admin bypass, Stable tag/release or app-stack merge in this boundary.
- This checkpoint's own PR/CI and later issue230/#181 receipts supersede its snapshot. Documentation-only branch generates no APK; do not advertise a main-based APK for application acceptance. Next engineering slice is #253; #230 is at user acceptance under its defined contract.
