# October 7 final cursor/bootstrap/trust boundary

Read [the preceding checkpoint](2026-10-07-cursor-copy-trust.md), [manager-admission evidence](../audits/2026-10-07-move-admission.md), AGENTS.md and the parallel-track protocol. Main audit only; user's experimental checkout/session was untouched. Allocated audit Claude is idle. No root, Pixel, card/storage availability or additional phone actions after the limited TalkBack checks. No Stable release or tag.

## Verified live heads / builds at handoff

| Boundary | Commit / state | Verified receipt |
| --- | --- | --- |
| App successor #381, codex/bootstrap-status-projection | OPEN503983c86ffb64450fcfb01396098a20dc629a6b, includes main8873848 and full #377/#376/#373 acceptance ancestry | [Android729](https://github.com/averylicious/muon/actions/runs/37590435716): actual downloaded reports711 tests EACH variant, zero failures/errors/skips; no fatal/error lint (46 warnings each). Branch direction and default dependency inventory passed. |
| Newest complete acceptance APK | app-debug-503983c86ffb64450fcfb01396098a20dc629a6b | [Artifact11468327306](https://github.com/averylicious/muon/actions/runs/37590435716/artifacts/11468327306), BUILD.txt/full SHA/run729/version0.1.0-canary.729; actual checksum f1b42cae445da3b4ed33eb185c1ae7185d895e8152b3e6dfb12bd14464a88bb5, known public certificate/package/non-debuggable and all three Muon services private verified. Updates existing Canary/data, expires14 days; Actions artifact, not Obtainium. |
| Test-only #380 | MERGED88738488d1cf70662b02f6286ce5580cc5b88aaf at verified8525adee974502b8c4142ac8abe44ca3175f66c3 | Android727: actual reports518 tests EACH variant, zero failures/errors/skips/blocking lint; both new native controls passed, actual artifact identity/checksum recorded in prior source checkpoint/PR. |
| Current main app publication | main88738488d1cf70662b02f6286ce5580cc5b88aaf | [Android728](https://github.com/averylicious/muon/actions/runs/37590426796) and [Canary728](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.728) passed/published. Non-draft prerelease has APK/BUILD.txt/SHA256SUMS; downloaded receipts match full SHA/run728/version. Published checksum42a391195c987681ae5ff17bade46e7a8d0348a8d77f92c8b83e02b74422659c; release APK not independently rehashed here. Main excludes open app stack; use729 for that acceptance. |
| CI-only #378 / docs#379 | MERGED9d77da5 /609769f | Normal exact-head merges under standing authorization; unsigned candidate69/public observations and main722 publication in preceding checkpoint. #379 final docs run725 and main docs check passed with zero artifacts/publication. |

#376 headbffbbfc0ae280deac753ec4f6a4db08c569fba55 and #377 head7587aed06e9f51208dd0f6fe5fcefbafd46a5abc remain OPEN behind acceptance; their fixes are included in729. Do not close or merge the inherited application stack simply because a later test/doc workflow passed. Refresh current main and final candidate CI before eventual application merge; main documentation may advance after this snapshot.

## Changes / attribution

#253 saved inventory now uses one cursor/two passes without retaining all raw metadata rows (#376). #381 startup status scan additionally retains only ID/state/recorded bytes while waiting for main. Live status recording matches it; removal/move callbacks keep actual records and stale-bootstrap suppression/mark precedence remain. Native fixtures use valid encoded song records and stored progress and verify metadata/byte preservation and exact statuses. No cardinality cap or measured performance claim.

Claude Opus5.5 (`claude-opus-5-5`), Claude Code, High selected: copy-stop implementation65a188e and startup projection386db4a, earlier bounded trust review and manager-admission advisory. GPT-6 / Codex desktop (exact variant/effort not reported): coordinator source review, fixtures, integration, CI/artifact checks and test/trust/docs authorship. Coordinator's own changes have self-review, not independent review. No local Android/Gradle compile; Actions supplied first real compilation.

#230 copy stop preserves partial output and validates/refuses a later retry. Test-only live-manager refusal now proves that throwing inside Downloader.remove can leave bytes while the manager drops its row; admitted-removal positive control deletes both. No production gate/deletion introduced. Coordinator rejected an initial span-position cleanup advisory because sink close can fail quietly, position differences do not identify writers, and snapshots do not drain queued manager commands. Claude withdrew it. Its first read-only plan-mode client wrote a local plan outside repo despite no-write instructions; it is not portable authority. Corrective response used no tools and wrote nothing.

#378 provides an optional unsigned observed-checksum candidate, deliberately unenforced. Public snapshots are inert .txt evidence outside gradle/verification-metadata.xml. Trust admission is not cleared. Pinned Robolectric4.16.1 runtime resolver separately fetches jars, reuses existing local jars without rehash, and checks new downloads against same-repository SHA512. MavenRoboSettings default is https://repo1.maven.org/maven2; source default, not an observed active endpoint/cache for a particular run. No poisoned cache or conflicting-byte production exploit demonstrated.

## POCO and pending acceptance

Older710 TalkBack activation/binding/visible focus and exposed labels were observed after the user dismissed first-run notification prompt. Injected focus/activation was inconsistent; spoken output, true gesture/focus order and Undo accessibility are NOT passed. Original Accessibility Menu-only service list and accessibility_enabled=1 restored, TalkBack off/unbound, test playback paused. First-run tutorial dismissal remains changed; no root/security/volume/lock/card change. Screen remained readable; secure-display disabling unnecessary. Older710 checks do not validate729. Raw screenshots/XML/session logs were not uploaded.

Manual729 acceptance: cold startup marks/byte totals; saved labels/art/playback; disposable add/remove during startup without stale completion; ordinary phone/card moves and inherited queue/library checks. TalkBack spoken/focus/Undo and Bluetooth hardware remain pending. Full SD recovery #179 remains user-deferred/unresolved, regardless of its closed issue state.

## Next bounded engineering / safe resume

1. #230: command admission before destructive commands reach manager; exact target/owner generation, writer/sink completion and posted-command/restart preservation before any cleanup. Keep unknown/shared/legacy and failed partial bytes; timeout/empty census grants nothing.
2. #253: total rows/keys/display/native-window/cache/DOM bounds or explicit disposition. Projections reduce one retention path but do not bound cardinality or all scans; paging must not hide owners.
3. Trust: independently reviewed publisher/repository basis and task coverage before enforcing observed Gradle hashes; separate actual Robolectric runtime artifact/reuse evidence, SDK/JDK/executable/generated-cache trust and remaining advisory callers.

Usage snapshots are USED: coordinator62% five-hour/10% weekly; Claude79% five-hour/55% weekly, idle. Neither exhausted a quota. Refresh live; reserve for corrections/handoff rather than another large retained-context Claude assignment. All source branches are clean/pushed, no active writers/builds at source-verification boundary. Final docs CI/merge receipts belong on their PR and #181; never require local plan/session or temporary files to resume.
