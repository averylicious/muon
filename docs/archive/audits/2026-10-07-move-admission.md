# Move cleanup admission: live-manager refusal control

Main audit base `609769f9a6459a5d43b4a2e063bde9760f6c2a0c`; current application assessment also read open #377 head `7587aed06e9f51208dd0f6fe5fcefbafd46a5abc`. GPT-6 / Codex desktop, exact variant/effort not reported: source self-review and disposable controls. No production cleanup, service gate, key migration or app behavior change.

## What the supported source says

Matching published Media31.11.0 source artifacts were inspected: datasource SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`, exoplayer SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. Build files remain the authority for pins.

- `CacheWriter.readBlockToCache` closes quietly after a callback/read exception and rethrows the original. `CacheWriterCloseReceiptTest` already proves a failed sink close/commit can be hidden; a second outer close does not repair that receipt. Writer return does not authenticate its output.
- `SimpleCache.startReadWriteNonBlocking` locks a hole range. `removeSpan`/`removeResource` remove spans without an application-wide index/writer drain transaction. API availability is not permission to clean up.
- `DownloadManager.Task.run` catches a downloader removal exception and posts task completion. `InternalHandler.onTaskStopped` logs that exception, then dispatches REMOVING to `onRemoveTaskStopped`, which removes the index row. A downloader-only gate that refuses removal therefore does not preserve the manager's catalog.
- Muon's mover posts publication and finish to main; single mover execution is not evidence that previous main/manager commands have drained. A span-position difference identifies observations during an interval, not their writer. No conflicting-byte Muon runtime reproduction is asserted here.

## Disposable boundary and positive control

Two controls extend `DownloadDrainPrototypeTest` using the actual manager, progressive downloader, SimpleCache and native index, and only the existing synthetic disposable upstream. Complete a real download, then ask the still-live manager to remove it. With test-local admission closed, the downloader refuses but the manager reports removal and drops its index; every byte/span/length stays. With admission open, both index and actual spans disappear. The second case checks that the fixture can observe real deletion; this is not a production gate or user-data loss reproduction.

CI is the first Android compile/execution; latest-head results belong on the PR. No phone QA needed for test/report-only changes; no local Gradle build was run. Do not mistake the characterization of a deliberately installed test gate for a defect in production Muon, which does not install this gate.

## Advisory correction / next implementation prerequisite

Allocated Claude Opus5.5 (`claude-opus-5-5`), Claude Code, High selected, read-only source advisory on #377 initially proposed deleting newly appearing spans after a failed move. Coordinator pinned-source review rejected its sole-writer/close assumptions. A tool-disabled follow-up withdrew the safe-cleanup claim. The first plan-mode client unexpectedly wrote a local plan outside the repository despite the no-write instruction; no repository/app files changed, and that local file is not a portable authority. The corrective response used no tools and wrote nothing. Claude ended idle at50% five-hour/53% weekly used.

Before any production cleanup: establish command admission before the manager sees a destructive command, exact writer/sink completion receipts, target generation and ownership, posted-command drain, and restart preservation. Refusing inside Downloader.remove is too late for row preservation; a timeout or empty census grants nothing. Unavailable storage, existing shared/legacy spans and unknown close/owner evidence continue to preserve bytes. #230 remains open. #179 full recovery remains user-deferred. App acceptance #376/#377 remains open; this test PR cannot clear either gate.
