# #253 truthful badge failure signal — 2026-10-09

GPT-6 / Codex desktop implementation/author source self-check, exact variant/effort not exposed. Cumulative main audit stack through #431. Claude exhausted/idle; no phone/experiment commands.

A worker-only DownloadMarkLedger read failure revoked its private known flag but left Compose countsKnown unchanged until another native status event. Settings could continue advertising a known total while saved-page badges were unavailable. Each ledger instance now publishes at most one unknown notification on its first failure/close; production posts one application-looper update to countsKnown. No per-row failure Runnable accumulation and no need to wait for a download callback. Notification errors cannot restore trust or throw into read/native state callbacks. Counts/badges become unavailable, original saved rows/audio remain accessible and untouched.

Native fixtures exercise1000 worker-only reads with one main notification and no native download event, plus a throwing sink/continued events with exactly one notification. Existing count/exact-ID/restart/original-preservation controls retained. CI first compile/test/lint, final receipts on PR/coordinator checkpoint. Device UI latency/UAT remains pending. This fixes failure reporting, not #401 startup cause or native cache memory; approved migration foundations remain disabled and full Cache adapter/journal/per-resource admission/UI still required.

Leave app stack OPEN for user acceptance. No Stable/tag/app merge, device authorization or experimental integration is implied.
