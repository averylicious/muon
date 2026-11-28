# #253 saved queue budget and explicit smaller selection — 2026-10-08

GPT-6 (Codex desktop; exact variant/effort not exposed), implementation/self-review. Branch codex/saved-queue-budget-oct8 from #393 edbf852a3b534c7845acf1e57618fe94670efadc, full acceptance stack. No device/experiment change or local Android compile; Actions first execution.

User chose full-library saved queue within budget, explicit refusal/smaller-selection offer above it. prepareSavedQueue keeps complete-copy order, validates at most2,048 items/4MiB logical UTF-8 text before creating MediaItems/Bundles, then applies the whole plan. Text includes ID+URI, title/subtitle/album/art and occurrence UUID. No truncation/clipping. Missing/incomplete selection never defaults to another copy; repeated locators retain distinct occurrences.

SavedCopies drops its lifetime-long complete-entry filter. On refusal MuonApp offers Play this copy or Cancel in a Material dialog. The previous player/queue is untouched until explicit successful selection; stored rows/audio and shuffle/repeat flags stay unchanged. Single-copy playback is explicitly selected, not an automatic fallback. All copies remain available.

Pinned Google Maven media3-session source SHA256 9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715: MediaControllerImplBase.setMediaItems converts the entire list to BundleListRetriever, creates one masking window/period per item, and MediaSessionStub retrieves the full bundle list. Pinned exoplayer source SHA256 2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6: ExoPlayerImpl.createMediaSources builds one source per item. Chunking IPC is not bounded resident memory; no actual Binder failure/phone OOM claimed.

Six real MediaItem/Robolectric cases: order/complete-only/duplicate songs/art, count refusal+single selection, exact UTF-8 boundary/non-ASCII, missing/incomplete selection, duplicate locators/distinct occurrences, production2,048/2,049 boundary. Dialog cancel and unchanged player are source-reviewed, not Compose-tested. Manual acceptance pending.

Logical cardinality/text limits are not exact Binder-byte/heap guarantees. Saved display inventory is still unbounded; paging must later hydrate off main with generation checks. This bounded preparation still runs synchronously on tap; responsiveness needs UAT. Native/cache/manager and live-library queue costs remain. #253 broader redesign stays REQUIRED before Stable. Keep app PR OPEN; CI details on PR.
