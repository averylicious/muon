# October9: bounded cached-file reader lifetime (#253)

Root implementation: GPT-6 / Codex desktop; exact variant/effort not exposed. Branch `codex/partition-reader-lifetime-oct9` is the complete successor to #435 at c8e661995f48ba8df8cba2ffe7c3186b212b6e60, including #434/#433 and the previous OPEN acceptance stack. Main included e164a340b2a0367c8277f7d927ced8b6a7401bfa. No phone or experiment/session commands. Final exact head/CI/artifact receipts belong on its PR and the main coordinator checkpoint. Leave app PR OPEN for UAT.

## Gap addressed

The pinned Cache API returns cached spans but offers no cached-reader release callback. `PartitionResourceCache.release` therefore cannot establish that cached files are no longer in use. A generic pool alone could close an idle-looking partition during playback.

`PartitionSavedSource` is an UNWIRED read-only DataSource factory. Its actual Media3 CacheDataSource has no upstream and no sink: missing bytes fail, without streaming or re-downloading. It resolves the default exact CacheKeyFactory key, acquires a pool lease before creating/opening the reader and holds it until source close succeeds. EOF and a read failure do not release the pin. Failed open performs the required cleanup; clean cleanup returns the pin, unknown cleanup keeps capacity counted and refuses reuse/close retry. A stopped pool retires the native instance only after the last clean reader close. Reader callbacks cannot reenter open/read/close; listener slots are capped at four with identity deduplication. No pending request queue, source deletion or fallback is introduced.

The default pinned DataSource/CacheDataSource/CacheKeyFactory contracts were inspected in Google's published datasource sources JAR, SHA256 a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a. In particular, close is required after failed open; CacheDataSource.close can fail before completing cleanup. No dependency changes.

## Eleven focused native regressions

Real SimpleCache files and CacheDataSource reads cover reader pinning through EOF/idle replacement; two shared-instance readers and lease refusal; pool stop while cached bytes remain readable; missing saved bytes with no sink/network; unknown close retaining bounded native capacity; failed open plus failed cleanup preserving the original failure; clean failed open/factory creation returning pins; read failure holding its pin until clean close; a repeated Throwable not masking the original; exact URI-key fallback and duplicate-open refusal; and bounded transfer listeners refusing callback lifecycle reentry.

Actions is the first Android compile/test/lint. No local Android build, phone/heap/startup-speed or enabled routing claim. Expected combined test count993per variant; verify actual report XML rather than assuming that count.

## Remaining required integration

This only establishes cached-read lifetime over an owner-supplied pool. A production owner/factory must validate exact ready records, current volume/native UID and the separate bytes/metadata layout BEFORE native initialization can mutate a foreign/replaced directory. No production pool factory exists yet. `Shelf.savedSource` still uses the legacy concrete factory; downloader/played/cache removal and request/cover handover remain unchanged. Migration still requires a real source availability/writer/reader/removal/eviction barrier, space/cancel/deadline worker, opt-in controls and controlled full production restart/preservation tests. Overbudget/inconsistent legacy copies remain readable, with an explicit legacy full-index transition peak. Journal Ready is never source-removal authority.

Ask for renewed POCO rooted-ADB access after production routing/opt-in controls exist; then inspect private records/card identity and perform disposable-data cancel/restart/preservation checks. Do not disturb the user's card for an unwired helper. #401 measurements and inherited application UAT remain pending.

Claude Opus5.5/ClaudeCode/High finished normally/idle at78%five-hour/92%weekly USED this assignment, no hard cutoff. Root continues solo; current account quota is checked at boundaries. Portable PR/head/evidence is authoritative; local session IDs are optional.

## CI ordering correction

Initial .831 at ef796ba4228ef897c47e50a17ffefdbcbd2020e5 compiled and ran993tests per variant. Its release suite failed the inherited SavedLibraryModel pending-callback revocation test: expected1callback, got2. The test did not ensure work was still pending before refresh; a fast IO completion can legitimately publish before that command. The correction holds the actual private saved-work Mutex during prepare/cancel/replacement and prepare/refresh, then releases it and pumps the actual main/coroutine result. This tests the stated cancellation case deterministically without changing production behavior, adding sleeps or suppressing the assertion. Latest-head full CI and actual reports are required; .831 is not a successful receipt.
