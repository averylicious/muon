# #253 opt-in native-cache migration preparation

Current implementation boundary: [native-cache checkpoint](../handoffs/2026-10-09-native-cache-checkpoint.md). #433 adds persistent verified close/reopen publication; #434 hard per-resource native mutation admission; #435 separate durable exact metadata outside the scanned native byte folder; #436 cached-file reader pinning through clean close. All remain disabled. The stages below describe the full goal, not unstarted journal/admission work; production pre-open identity/index ownership, routing/downloader integration, source barriers, request/cover handover and opt-in controls still remain REQUIRED. The checkpoint has exact heads, actual receipts and the next bounded implementation.

User approved preparation on October9: migration is opt-in, old copies remain readable, temporary free space is needed, and an old copy is removed only after its replacement passes byte verification. This document does not authorize enabling migration, deleting user data or claiming #253 complete. No phone/experimental work in this cycle. App stack remains open for UAT.

## Source evidence and chosen direction

Pinned media3-datasource sources were inspected from Google's published 1.11.0 sources JAR, SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`. Versions remain defined by Gradle. SimpleCache is final; CachedContentIndex loads every key/metadata record, native content objects keep every span, and startup file metadata loads the full file index. A database-backed constructor does not make those indexes lazy. Application disk ledgers reduce separate holders, not this native retention.

Prepare one resource per independently openable partition, with exact private disk locators and a fixed maximum of active native cache leases. Resource keys/request IDs/signing identities stay unchanged. A partition must bound metadata and fragmentation as well as key count. Never close an instance while a reader, writer hole or registered listener owns its lease. When the lease budget is full, explicitly refuse/defer admission; never evict a saved copy or close an in-use cache. A full supported Cache adapter and routing tests are required before production use.

Legacy storage stays the read fallback until a replacement and its locator publish atomically. Creating a new partition does not authorize deleting a legacy copy. A one-time supported-API import still opens the legacy full index and can have a high memory peak; do not claim a bounded startup during transition. Budget-incompatible legacy copies remain readable there and migration incomplete. That limitation must be explicit in opt-in UI and release gate; no silent reset/truncation/retagging.

## First code stage

CacheMigrationPreparation is an unwired helper; it copies one exact resource to an exclusively owned fresh target. It refuses over16KiB UTF16 key identity, over4MiB combined metadata name/value payload, over256 metadata fields or over256 disjoint cached ranges before writing the target. Limits bound preparation collections only, not legacy native heap. getCachedLength walks full retained extents using the public scalar API, without getKeys/getCachedSpans copies. Broken/non-progress/overflow probes refuse.

Every retained range is copied, including partial bytes beyond inconsistent declared length. Direct read-only span-file access through supported cached-span acquisition preserves those bytes; CacheDataSource would obey declared length and miss them. One read span/file, one strict output and bounded buffers are held at a time. Flush/sync/close/commit must succeed. Known and unknown DefaultContentMetadata fields are copied verbatim, within budget. A post-copy byte comparison and extent/metadata rechecks must succeed. The caller must keep writer/availability exclusion and cancellation/deadline checkpoints effective throughout; source checks alone cannot establish exclusion.

Returned MigrationCopyEvidence is current preparation evidence only. It is NOT durable publication, crash recovery, index/request handover, source-removal authority or a phone measurement. On failure/cancellation, all source bytes remain; uncertain/partial target bytes can remain and need explicit recovery handling. A nonempty target is refused, never overwritten or adopted merely because names match. Metadata-only resources are preserved. No network is available to the copy/compare path.

Native tests cover adjacent/many fragmented spans, holes, retained bytes beyond declared length, unknown/metadata-only records, original byte preservation, target refusal, bounds, broken probes, cancellation/write failure and late source metadata change. Actions is the first compile/test/lint. No heap, speed, crash-recovery or real-device claim.

## Remaining reviewable stages

1. **Disk locator and lease pool:** transactional exact-key/resource directory routing, fixed resident native instances, no public/private Media3 schema edits; failure and concurrent ownership tests.
2. **Cache adapter:** complete pinned Cache contract, callback/evictor/UID semantics, per-resource metadata/span write admission, playback/downloader/played-cache integration. No unsupported command or streaming fallbacks.
3. **Migration journal and publication:** disk-backed bounded enumeration, request/index ownership and cover transfer, fresh destination reservation, free-space check, byte comparison and clean-close receipt, restart/cancellation recovery. Publish locator before any optional source removal. Revalidate original identity and writer exclusion at removal; unknown source/destination preserves both.
4. **Opt-in interface:** user-visible space estimate/warning, progress and pause/cancel; distinguish migrated, retained legacy and unavailable copies. Default stays legacy until explicit opt-in. Do not silently enable on upgrade.
5. **Controlled and acceptance testing:** real native cache/index integration with forced write/metadata/close/publish/deletion/restart failures and bounded residency. Device/card timing and preservation testing only after renewed authorization. #401 saved-card startup remains measurement pending, not fixed by instrumentation.

The native-cache gate remains REQUIRED before Stable. A partial migration is not a completed memory redesign. #230 defined engineering completion/UAT and user-deferred #179/trust work remain separate decisions.
