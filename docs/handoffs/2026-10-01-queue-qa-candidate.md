# Combined queue QA candidate — 2026-10-01

GPT-6, Codex desktop (effort not reported), owns `codex/queue-qa-candidate` in an isolated persistent worktree. Claude remains idle near the user's reported93%five-hour/77%weekly quota. User/Claude experimental branch/session untouched. Source integration/self-review is contributor review, not independent review. No phone verification of this new candidate yet; no Stable release/tag.

## Included heads and destination

Main baseline `6dcb1bf4a822b948b5b7f55695fba87b34d7d0dc` includes merged duration/download-estimate arithmetic. The candidate also includes the source of documentation checkpoint #266 at `7a9ca39fd94d7d5fc1b44ed1ec38f53036203a2b`; recheck its landing and merge latest main before final-head CI. Original parked queue heads retained as merge ancestry:

- #219 `0cc8db2134a43ad8d3509d28b79cb8fa60ed3d65`: exact queue snapshot/action guard, stale drag cancellation.
- #224 `b403c15a65395152f5a6b6f85039eb3c3ca76943`: insertion-specific token makes Undo remove only its surviving occurrence.
- #242 `3d0d4543013c664e08f807a3abf2486958aeabd9`: transient occurrence UUIDs, stable duplicate row keys and removal Undo ownership.

QueueScreen's overlapping removal code was resolved by checking the current snapshot **before** calling the occurrence-bound removal helper. Retained row keys, drag stamps/cancellation, current-controller check, restoration notices and action closure. No production file selected wholesale. MuonApp's insertion Undo now refuses a changed controller, and its helper checks the current CHANGE_MEDIA_ITEMS command before claiming success. Temporary metadata is not authentication, persistent song identity or cache identity.

## Verification and remaining QA

Four extra integration regressions exercise actual production factories/helpers with ExoPlayer and metadata serialization: occurrence/song-record preservation through insertion, moved inserted duplicates preserving other rows, snapshot/removal Undo retaining surviving duplicate keys, and command-denied Undo refusing without mutation. Existing sibling regressions retained, including connected MediaController/MediaSession acknowledgement/replacement cases. JVM fixtures do not establish real cross-process Android17 behavior, Compose gestures/snackbar lifecycle or TalkBack. No prepare/audio/network in these new tests.

First combined run452 compiled production/APKs but failed one new fixture: MediaItem.fromBundle excludes local playback configuration, and the fixture fed it directly to ExoPlayer. Repair restores the validated URI while preserving all metadata assertions, consistent with the existing sibling round-trip fixture. Run452 is not a pass and produced no APK artifact. Final repaired-head CI pending; no local Android build. Final-head CI, artifact and result must be verified on the new PR. Original #219/#224 builds failed artifact upload; don't rewrite those as successes. Keep the combined candidate and original PRs open until current final-head checks and applicable phone QA are resolved. #218/#223/#241/#243 remain open until a verified landing.

Focused manual QA:
1. Add/Play next a duplicate and Undo; move that insertion first, remove it first or replace the queue before Undo. Other duplicate occurrences stay intact.
2. Remove an upcoming duplicate and Undo once; other rows remain independently swipeable/draggable. Replacing/reordering the queue invalidates old removal Undo; normal acknowledgement/shuffle/position updates preserve valid Undo.
3. Reorder/tap/remove normally; advance or replace a queue during an active drag. Stale input cancels/refuses and fresh input works.
4. Background/reopen with snackbar visible; no old-controller action or crash. Check accessible move/remove actions separately.

This candidate excludes #257 network/private-service/metadata guards, #264 card containment and other parked app fixes, plus experimental UI. Its APK updates the same Canary app and can replace those pending fixes/features. Do not infer content from version-code freshness. Installing and recommending a build must name these exclusions; no uninstall/signing workaround. Branch artifacts expire after14days and are not Obtainium releases.

Coordinator active; exact latest heads/checks and any phone result are recorded on the PR/#181/#40. At cutoff, preserve clean committed candidate and update the runbook pointer; check live ownership before resuming.
