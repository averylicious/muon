# Owned played-copy routing — 2026-10-12

Cumulative successor to #498 / `0a7d1ced76723b2ff847a740f171518d4e879fae`; #497/.970 already has verified reports/APK (1,339 tests per variant). Main included `2a275286f28db78083567465a521b7ea46f32640`. GPT-6/Codex desktop implementation/self-check; exact variant/effort not reported, independent review pending. Claude and phones excluded and unused.

## Implemented

The concrete owned shelf/session now performs a played-song copy in one fresh partition, off main and under the shared production gate. Metadata is measured before journal/native allocation; exact existing claims refuse. The real CacheDataSource and PlayedCopyWriter close before the native owner can publish a clean Closed lifecycle. A known failed copy cleans only its own unpublished key. An unknown source/native close retains the exact cache and writer with a gate permit and refuses further work; no second close, old-key reuse, fallback or automatic repair.

The existing mixed reader accepts clean owned played keys without inventing a DownloadManager completion row or migration Ready receipt. Inspection reports missing/partial attempts so they cannot poison saved-library projection; playback still requires exact full bytes. Reads preserve Closed and share the existing fixed native budget/pool. Production OfflineStore copy/deduplication and saved-page/count routes dispatch through the actual shelf when partitioned, rather than using `.cache` or a legacy-only played-key order. Legacy defaults remain unchanged.

Native SQLite/cache fixtures cover writer completion, fresh reader ownership, full mixed-shelf restart, metadata refusal before allocation, known-too-large cleanup and actual source-close uncertainty retaining both owners. No Android/device/mount/performance evidence is claimed from JVM tests.

## Remaining finite integration

Partition mode remains disabled. Aggregate played-library limit/eviction and explicit Clear still need owned destructive policy; per-copy limits alone do not satisfy the whole-library budget. Saved-copy deletion/move/retirement, production selected-root binding and explicit opt-in/restart/recovery UI remain engineering. #253 is still a Stable blocker, and inherited application acceptance PRs stay OPEN. No POCO is requested for this source-only boundary; fresh permission will be needed for enabled integration QA.

Latest-head CI/reports/APK pending. Final receipts belong on the PR/#253. No Stable tag/release is authorized.
