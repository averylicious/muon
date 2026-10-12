# October 7 continuation: startup statuses and manager admission

Read [the preceding cursor/copy/trust checkpoint](2026-10-07-cursor-copy-trust.md) for #376/#377/#378 receipts, public verification observations and restored POCO accessibility state. This source-only extension uses separate worktrees; no additional phone, card, root, signing, experimental-session or Stable-release work.

## Application stack: remain open

New `codex/bootstrap-status-projection` includes #377 `7587aed06e9f51208dd0f6fe5fcefbafd46a5abc` plus main609769f and later integration receipts on the PR. Claude Opus5.5 (`claude-opus-5-5`), Claude Code, High selected, implementation386db4a25dc6733d2ae1710c74967988e7f6d5ac; GPT-6 / Codex desktop (exact variant/effort not reported) source review and fixture adjustment2ec0960896bc8bc0b7503c9dc2fd395cd114b151. Normal hooks, no local Android/Gradle build. Allocated audit Claude ended idle; experiment checkout/session untouched.

`OfflineStore.watch` startup cursor projects ID/state/recorded bytes into `DownloadStatus`; the queued main callback no longer retains each full DownloadRequest/tag blob. Live status recording uses the same projection; removal callbacks and moved-copy verification still carry the real record. Changed-ID suppression, mark precedence and byte accounting remain. Native bootstrap fixtures use encoded song records and stored progress and check exact statuses plus preserved metadata/bytes. No source cap, metadata truncation, paging, saved-row/audio/cover mutation, measured memory improvement or full #253 closure is claimed. Per-row/native-window reads, final status/list/key cardinality and other scans/cache/DOM resources remain.

CI is the first compile; final-head run, reports, APK commit/version/certificate/checksum and manual checklist must be verified and recorded on this PR before declaring a build ready. App stack remains OPEN for inherited user acceptance; no test-only main merge clears it. Previous .721 remains the known complete acceptance build until a newer full-stack head passes. Main-based .722/.727 exclude the open app stack; higher version number alone is not a better acceptance build.

## Manager-admission source boundary

#380 `codex/move-admission-next` inspected head8525adee974502b8c4142ac8abe44ca3175f66c3 includes main609769f. Android727 https://github.com/averylicious/muon/actions/runs/37589359282 passed518 tests EACH variant, zero failures/errors/skips, zero fatal/error lint (46 warnings each). Actual downloaded reports include both new live-manager controls. Actual artifact11467609849 BUILD.txt confirms full SHA/run727/version0.1.0-canary.727; checksum `7a7159b6d48e4ff444621379ff4e78c9b86224dd12642a78718bb32b10295119`, expected certificate/package/non-debuggable and existing main component policy verified. No application behavior changed, phone QA unnecessary. PR/final merge and main publication receipts supersede this snapshot.

[Admission report](../audits/2026-10-07-move-admission.md): a test-local downloader gate refuses a live manager removal; real manager still reports removal and deletes the index row, leaving bytes. Open-gate positive control removes both. This characterizes the deliberately installed test gate, not current production user-data loss. A production admission boundary must precede the manager command; refusing only inside Downloader.remove is too late. No production gate/cleanup implemented.

Earlier Claude source advisory proposed deleting new span positions after failed copying. Coordinator independently checked pinned Media3 CacheWriter/SimpleCache/DownloadManager and existing sink-close controls; the safe-cleanup claim was withdrawn in a tool-disabled correction. Position differences do not identify writers, quiet close can conceal sink failure, and empty index snapshots do not drain queued manager commands. A conflicting-byte production interleaving was not demonstrated. The initial read-only plan-mode client wrote a local plan outside the repo despite no-write instructions; it is not portable authority. The corrective response wrote nothing.

## Next bounded work and acceptance

| Area | Remaining work |
| --- | --- |
| #230 | Prospective failed-output ownership and command admission, all relevant late writers/posted completion, successful sink receipts and conservative restart/generation rules before any cleanup. Keep current partial bytes; retry validates or refuses. |
| #253 | Native/cache/DOM and aggregate row/key/display bounds or explicit dispositions; projected startup statuses and saved cursor scans do not cap total cardinality. Never hide destructive owners through paging. |
| Build trust | Authenticate publisher/repository policy before admitting observed candidate hashes; separate Robolectric runtime-fetch artifacts/reuse, SDK/JDK/executable and cache trust. #378 merged; candidate is deliberately unenforced. |
| App acceptance | Existing #376/#377/full successor: normal saved-copy labels/art/playback, download marks/byte totals, ordinary phone/card moves and inherited queue/library checks. TalkBack spoken/focus/Undo and Bluetooth hardware remain pending. POCO accessibility already restored; no automatic new device access. |
| #179 | Full SD recovery user-deferred/unresolved; closed GitHub state is not proof of recovery. |

Latest snapshots are USED: Claude79% five-hour/55% weekly, idle after implementation; coordinator refresh live at final boundary. Reserve room for fixes/handoff, not another large Claude assignment. All portable evidence belongs in the PR/checkpoint/issue, never require local plan/session or temporary files. Before merge/resume verify live main and candidate heads, exact-head checks and branch ownership. App PRs stay open; own blocker-free test/CI/doc PRs may merge normally under standing authorization. No Stable tags/releases.
