# Retained download native cursor failures — 2026-10-08

Main-track acceptance stack from #394, 91cf8540bccbf03d9b08feccf6f8314b6aa12592. GPT-6 (Codex desktop; exact variant/effort not exposed), implementation and author self-review. No device commands; no local Android build. Keep app PR open for acceptance.

Pinned Google Maven media3-exoplayer source SHA256 2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6: DefaultDownloadIndex.getDownload catches SQLiteException, but getDownloads/DownloadCursorImpl lazy movement/row reads do not. DownloadManager.InternalHandler.initialize catches only IOException. This creates an unhandled runtime exception path on lazy native CursorWindow failures, not a demonstrated phone crash.

RetainedDownloadIndex now converts specifically SQLiteException into IOException during the initial preservation scan (latched), and at its returned lazy cursor reads/close. Existing IO refusals and other programming errors are not broadly swallowed. Failed preservation starts no tasks and never rescans newer process commands. A later manager stopped-row scan failure is an IO refusal; original requests/bytes remain.

Two native SQLite/Robolectric tests use real pinned Media3 cursors with a deliberately small 32KiB window and a 256KiB raw record. Test-only reflection selects that native window, no Media3 schema queries or production reflection. Positive control expects SQLiteBlobTooBigException; guarded startup and later manager scan must complete with no tasks, preserve records/audio, close cursors, and expose IO with its original cause. CI is first execution; final exact-head result must be recorded on the PR. No claim about default OEM window sizes, measured heap or runtime UI.

#253 remains required before Stable: full saved paging, retained tasks, cache/census cardinality and legacy command budgets still need engineering. This fixes a failure path rather than paging or deleting oversized data. Acceptance stack unchanged; trust and #179 release deferrals remain separate.
