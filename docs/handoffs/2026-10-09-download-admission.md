# Current-process download command admission — 2026-10-09

Solo main-audit slice from #404 aa8a12b174924497f5ab9d27d9095cd8dcc60ba8, including main777c25d4d31c73118c3ea486d41f3d53db8ae056. User experiment untouched. Claude idle/unavailable this cycle. GPT-6 / Codex desktop implemented and author-self-checked; exact variant/effort not exposed. Application PR remains open for UAT.

## Change and evidence

Each Shelf has a main-thread DownloadCommandBudget. Add/Remove must pass it before Media3, move-token admission or ordinary receipt invalidation. Manager initialization is required; index failure refuses. Existing row payload is counted because the pinned manager can hydrate an omitted row during Add/Remove. Per manager: at most128 resident/newly reserved requests,128 pending command reservations,4MiB logical raw request metadata. An Add also uses the existing768KiB measured parcel/envelope check. No request or ID list is retained by the budget itself.

Reservations are recomputed from currentDownloads only when the manager is idle (or initially seeded after initialization). A completed callback alone cannot free reservations while earlier/later handler commands await acknowledgement. STOPPED requests still count after idle. Resident removal is separately admitted when the row budget is full, so the user can free it. Refusal keeps stored records/audio and offers retry/fewer songs. Refused queued move tokens release their receipt rather than authorize removal or wedge future moves.

Pinned Google Maven media3-exoplayer1.11.0 published sources SHA2562d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6 inspected: addDownload/removeDownload increment pendingMessages synchronously, handler updates replace application currentDownloads before listener delivery, isIdle requires zero pendingMessages and activeTaskCount, per-ID operations reread omitted index rows, same-ID Add merges request metadata/stream keys. This is source evidence, not supplier authentication or measured heap reduction.

Actual MuonDownloadService + Media3 manager/native SQLite/SimpleCache tests cover rapid Adds before callbacks, retained STOPPED count after idle, removing a resident at capacity and subsequent retry, oversized old Remove preservation/exact-boundary retry, small Add plus old large payload refusal, repeated same-ID command reservation, and refused move-token cleanup. CI is the first actual compile; final head/run/artifact and test results to be recorded after completion.

## Remaining work and acceptance

This gate bounds Media3 service-delivered Add/Remove retention, not all producer-side executor/Binder queues or artwork work. New-save batch preparation, Remove-all/move/taken-name inventories, startup pending-ID list, full native cache keys/spans/metadata, played/origin holders and other playback queues still need required #253 engineering. No original eviction/clipping, no silent smaller playback queue, no whole-process heap/performance pass. #401 saved SD startup delay remains unresolved. #230 engineering remains complete with UAT pending; #179 full SD recovery and dependency/tool provenance are separately deferred per the user.

Manual UAT pending: ordinary Save/Remove on phone and card, burst/large operations give honest retry refusals and preserve remaining copies, stopped tasks/requirements state, retry after tasks settle, and moves/completion/remove-all interactions. No device mutation/testing in this initial source receipt. No app merge/Stable publication authorized by this checkpoint.
