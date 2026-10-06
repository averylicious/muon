# October 7 final application acceptance boundary

This is a dated portable checkpoint. Verify current heads, destination, checks, ownership and user QA before resuming. No phone access or experimental checkout/session changes occurred this continuation. Full #179 SD eject/reinsert/live-process/restart recovery remains user-deferred from the current Stable gate, open/unfixed. No Stable tag/release or app-stack merge.

## Verified application heads

| Candidate | Exact head / build | Scope |
| --- | --- | --- |
| #302 combined | `4921c68dc7290ea43f5f287da1c908ff67a62219`; [Android696](https://github.com/averylicious/muon/actions/runs/37518625512) PASS,696 tests per variant | #362/#363 reader/move safeguards, #365 aggregate library budget, #366 Unverified saved copies/startup preservation/move receipts and three model integration controls. |
| #365 library budget | `87cbb75a9d10b528ea4987e45263ff83188d4ae2`; Android687 PASS,676 tests per variant | 2,048 playlists /50,000 entry occurrences /16 MiB encoded retained batch, previous data preserved on rejection. Not a peak parser/native/heap budget. |
| #366 saved access | `3ac98a05769e523c3585698e9766ba3e9ec4ae3c`; Android695 PASS,684 tests per variant | Live streams independently; exact saved handles/local playback/owned art, conservative alias removal, retained pending operations stopped on restart, byte-verified process-local move receipt. |
| #369 move failure message | `d2900901eed8698a1c62382a0bb65cf451a89de2`; [Android699](https://github.com/averylicious/muon/actions/runs/37520690208) PASS,698 tests per variant | Includes #302 and main3291aa3. Failed permitted attempts report count; prior prefix/source entries retained in real failure controls. No unsafe automatic leftover cleanup. |
| #370 inventory census | `97e2fa0b96032374a84c604c1e7ccccf82406338`; [Android701](https://github.com/averylicious/muon/actions/runs/37521729207) PASS,700 tests per variant | Includes #369 and all acceptance prerequisites. Listing makes one ID/key census instead of quadratic rescans; unlisted aliases/duplicate IDs remain protected. |

For successful receipts above, downloaded actual test/lint reports and Canary ZIP were checked: full BUILD.txt commit/run/version, SHA256, pinned public signer/package/label, ordinary non-debuggable app and all three private playback/download services. No failures/errors/skips in either successful test variant and no blocking lint. CI was the Android compile, not a local build or phone result. Android700's fixture-name SQL failure is historical, not a pass; its replacement only changes the invalid index-name punctuation and keeps the assertions.

- Recommended .701 [artifact11440588148](https://github.com/averylicious/muon/actions/runs/37521729207/artifacts/11440588148), `app-debug-97e2fa0b96032374a84c604c1e7ccccf82406338`, version `0.1.0-canary.701`, SHA256 `e3a831d58c3a4f5074bc3380ef19748c6153676d1f8b00ab1156f4a9912356af`. Includes every row above; .699 lacks the new census, .696 lacks both follow-ups.

- .699 [artifact11440376676](https://github.com/averylicious/muon/actions/runs/37520690208/artifacts/11440376676), `app-debug-d2900901eed8698a1c62382a0bb65cf451a89de2`, SHA256 `e52948f0d3871d9dcc58588244086d67185694a1ce666c7c690eebd5c310ba66`.
- .696 [artifact11439500341](https://github.com/averylicious/muon/actions/runs/37518625512/artifacts/11439500341), `app-debug-4921c68dc7290ea43f5f287da1c908ff67a62219`, SHA256 `c4c51e230d88d2fb0d7ff8f5193811f6c15899d93fc45f978e466e978d41c6a1`.
- Use the newest complete candidate for one QA pass. ZIP contains app-debug.apk, SHA256SUMS and BUILD.txt, updates existing Canary/data and expires after14 days. Actions artifacts are not Obtainium publications. Main's last actual application publication remains .678; main693/698 were documentation-only, zero artifacts/publication skipped. Do not bypass downgrade/signing restrictions.

## Ownership and review

- GPT-6 / Codex desktop / exact variant and effort not exposed: coordinator, draft reviewer/coauthor and integrator; authored #365/#369/#370, startup/metadata/art/move fixes and combined model controls. Self-review is not independent review of its own fixes or the whole stack.
- Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / High explicitly selected: saved-access author and focused reviewer of coordinator startup additions at93d7836. Runtime model confirmed, effort not reported by runtime. Its identified rescan/main-thread/presentation concerns were subsequently fixed by coordinator self-review, not a new whole-change Claude review. Session idle after normal completion at the last97% five-hour used /48% weekly reading; not a hard quota cutoff. No new assignments for #369/#370.
- Existing component independent reviews stay attributed on their PRs. Independent whole-change review is pending user request; app/hardware acceptance remains pending user testing. No new Codex subagents running.
- All own application worktrees are clean/pushed/idle after these receipts: `codex/acceptance-oct7-storage` (remote codex/artwork-acceptance), `codex/library-load-budget`, `codex/saved-presentation-oct7`, `codex/move-failure-feedback`, `codex/saved-inventory-census`; Claude's `codex/saved-access-oct7` is clean/idle at a02dac8bdc41c2bb85a4ad06bde6d86a19961451. Dedicated persistent worktrees are separate; never edit the user experiment or another session's checkout.
- Main inspected at3291aa3a7df74e45829bdcdfa3c71fef14c9b6c4 before this docs-only report. The report may advance main by documentation. Refresh any application merge head and repeat required checks; an older artifact remains a QA receipt, not approval of a future head. Strict main protection/admin enforcement requires Build,testandsign plus Branchdirection; do not bypass either.

## Remaining gate / next work

| Area | Current evidence | Still needed |
| --- | --- | --- |
| #213 retained identity | Implemented in open acceptance candidate; real reused-ID/live-cache-collision, local metadata/art, removal/Undo and startup controls passed | User acceptance: live vs Unverified saved copies, Disconnect/played-only/unknown-origin, saved art, per-copy refusal/remove, restart-paused entries and queue Undo. Do not close from CI alone. |
| #230 moves | Request equality, fragment/full-target refusal, tracked publication and current-byte completion; failed-copy feedback/preservation controls | General partial-span/late-writer ownership and non-destructive accounting/cleanup design. No blanket removeResource/removeSpan; uncertain ownership keeps bytes. Normal move acceptance. |
| #253 resources/performance | Incoming metadata/batch limits and inventory ownership traversal reduced | Saved inventory totals, DOM/decoded records, sorting/other censuses, aggregate IPC/native heap and controlled performance measurement. No device speed/jank/battery claim. |
| #179 full SD recovery | Narrow containment/service refusal and historical disposable POCO mapping/hash checks; OS pre-empted live eject | Deferred separate follow-up, open/unfixed. Deterministic in-process disappearance and restart tests differ from repeating platform-kill eject. No current phone permission. |
| Platform/interaction | Prior source/tests and qualified POCO evidence on earlier candidate | Bluetooth/headset/companion, audibility/audio focus, TalkBack, fresh API37 discovery and remaining current-stack interaction acceptance. Pixel lockscreen deferred. |

Leave #302/#365/#366/#369/#370 and inherited acceptance PRs OPEN. A child carries the prerequisites and cannot merge around their gates. No Stable publication is authorized.

Next bounded source task: inspect general #230 leftovers/late-writer ownership at the current integrated head. Consider a prospective private staging/new-copy namespace with explicit ownership; this is a design question, not permission for destructive legacy rekeying or an assertion that cleanup is already safe. Reuse production move/native-index/cache controls; distinguish preservation, byte accounting and safe deletion. #213 new names remove a prospective same-live-ID adoption path, but that is not cache/index/file atomicity. Follow with a specific #253 saved-inventory or aggregate IPC question rather than a broad refactor. Coordinate through branches/PR evidence; no routine takeover of the user's experimental Claude.

The census trades repeated row scans for temporary O(n) maps; no transient-heap reduction is claimed. Latest Codex reading:44% weekly used /56% remaining, no five-hour reading exposed. Refresh on takeover; these are dated account snapshots.

## Portable evidence links

- [Parent saved-access/budget checkpoint](2026-10-07-saved-access-and-budgets.md).
- [#369 scoped move report](https://github.com/averylicious/muon/blob/d2900901eed8698a1c62382a0bb65cf451a89de2/docs/handoffs/2026-10-07-move-failure-feedback.md).
- [#370 scoped census report](https://github.com/averylicious/muon/blob/97e2fa0b96032374a84c604c1e7ccccf82406338/docs/handoffs/2026-10-07-saved-inventory-census.md).
- #366 carries source/draft review and implementation handoffs. Later exact receipts supersede their explicitly historical pending states. Live #181/#40/PR comments supersede dated snapshots.

No private database, audio, raw session logs, credentials or signing material uploaded. Refresh account allowances and session ownership before assigning new work; local session IDs and previous chat are conveniences, not takeover prerequisites.
