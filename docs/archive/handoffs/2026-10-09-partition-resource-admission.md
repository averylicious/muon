# #253 partition resource admission (unwired) — 2026-10-09

One bounded slice on `codex/partition-resource-admission-oct9` (`~/.codex/worktrees/muon-partition-resource-admission-oct9`). The branch is based on open acceptance successor #432 plus docs main `e164a340b2a0367c8277f7d927ced8b6a7401bfa`, as the assignment states; Bash was denied in this session, so the head, status and ancestry were not re-checked here.

Source and tests only, **uncommitted for root review**. No local Gradle/Android build: CI will be the first compile and run, and **every test is pending**. No device, network, experiment, commit, push, PR or merge. This grants no production enabling, Stable release or merge permission.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort selected by CLI; implementation. Not independently reviewed. Root's journal, catalog and preparation files, STATE and the runbook were not touched.

## Source evidence

**What was read.** Bash was denied in this session, so `/tmp/muon-oct8-datasource-1.11.0-sources.jar` could not be opened. I relied on the pinned 1.11.0 sources already extracted in `/tmp/muon-oct8-cache-sources/`: `SimpleCache.java`, `CachedContent.java`, `CachedContentIndex.java` and `DefaultContentMetadata.java`.

**Not re-verified against the jar:**
- that those files match it byte for byte;
- the `Cache` interface file itself. The override list was taken from `SimpleCache`'s 19 `@Override` methods.

**Root should check both before relying on this.**

Pinned behaviour the design depends on (`SimpleCache` line numbers):

- **`startFile` (373)** returns a pathname naming the content id, position and timestamp.
- **`commitFile` (398):**
  - It parses the key back from the file name through the content index, so a foreign file would be committed under whatever key its name encodes.
  - It returns silently when the file is missing, and deletes the file when the length is 0.
  - Its `checkState` refuses a span past the declared content length, before any mutation.
- **`releaseHoleSpan` (437)** calls `maybeRemove`. `CachedContentIndex.maybeRemove` (284) drops a content record with no spans and no locks, **including its metadata**.
- **Startup `removeEmpty`** (`initialize`, 567) does the same to every bytes-less record. So a metadata-only resource does not survive a hole release or a reopen in pinned Media3.
- **`removeSpan` → `removeSpanInternal` (700):**
  - `CachedContent.removeSpan` (249) matches by `TreeSet` key and position.
  - It then deletes **the file of the span object passed in**. A look-alike span would delete its own file and remove the real index entry.
- **`startReadWriteNonBlocking` (350):**
  - It returns a cached read span, a write lock (hole, `isCached == false`) or null.
  - `getOrAdd` creates a content record for a new key.
  - `lockRange` adds an entry to a per-content `ArrayList`.
- **Blocking `startReadWrite` (328)** calls `wait()` on the SimpleCache monitor, which every SimpleCache method synchronizes on.
- **`DefaultContentMetadata.copyWithMutationsApplied` (57)** computes Media3's exact stored result without storing it.

## Change: new `PartitionResourceCache.kt` (no production caller)

**`PartitionResourceCache.open(cache: SimpleCache, key, limits)`** admits only a cache that names no other key and whose current state fits the limits. Otherwise it throws `IOException` and changes nothing. It checks:
- the key: UTF-16 size ≤ 16 KiB;
- the metadata: it must be a `DefaultContentMetadata`, with ≤ 256 fields and ≤ 4 MiB of name/value payload;
- the native span count: ≤ 256.

The defaults equal the migration-preparation budgets.

**What it enforces**, in a full `Cache` implementation of all 19 pinned methods:

- **Exact key only.** Every key-taking method refuses any other key with `IllegalArgumentException` before the native cache is touched. That includes reads, so routing bugs surface instead of silently creating content.
- **Write locks.** Each lock goes in one of a fixed number of slots (default 4). The slot is reserved *before* the native call, so a blocked `startReadWrite` waiter counts against it. The reservation is returned when the call yields a read span or null, or when it throws or is interrupted. `releaseHoleSpan` accepts only the identical span object this instance handed out.
- **Files.** `startFile` requires a held lock that covers the range, a free file slot (default 4) and room in the span budget. Committed spans plus pending files must stay ≤ the limit, because each file can become one native span.
  - `commitFile` accepts only a file this instance started, and only a length within the started range and the declared length.
  - Releasing a lock drops its uncommitted file records; the native cache would refuse those commits anyway.
- **Fragmentation.** The budget counts native spans, not coalesced ranges: adjacent spans are one range but separate files and objects.
- **Metadata.** The merged result is checked before mutation. Unknown fields always carry over, and nothing is removed to make room. Three cases are refused:
  - a declared length below the last retained byte, which would hide bytes from readers;
  - metadata with neither bytes nor a held lock, because Media3 would drop it;
  - any write lock on a resource admitted as metadata-only (it stays read-only and preserved for as long as this instance exists).
- **Removal** happens only by explicit caller request, and is refused while locks or files are outstanding. `removeSpan` passes Media3 its *own* current span, matched by position, length and file. It refuses the last span when metadata exists, because Media3 would drop the metadata; use `removeResource` for that.
- **Uncertainty.** If a native mutation throws, or its follow-up read does, the instance is marked `uncertain` and refuses every later mutation; it must be re-opened and re-admitted. Nothing is rolled back, deleted or retried.
- **`release()`** is refused while locks, reservations, pending files or listeners remain. Listeners are capped at 4 slots. After release every call fails.
- **Locking.** All bookkeeping is guarded by the SimpleCache's own monitor, the one lock Media3 also takes. Callbacks running inside the native cache therefore cannot deadlock against a second lock.
- **Footprint.** Fixed slot arrays and two counters. Native span-set copies are bounded by the admitted span limit plus pending files. No collection grows per call.

**Ownership contract (documented; the class cannot verify it):**
- The caller opens a fresh, independent `SimpleCache` with `NoOpCacheEvictor` and lets nothing else use it.
- `open` uses the supported key-set copy, so it **cannot bound or make safe an already-open legacy or untrusted index**; it only refuses one. Never wrap the legacy cache.
- Cached-span readers have no release event in the Cache API, so the caller's own lease must outlive every reader.
- Listener and evictor callbacks must not start writes on the instance.

## Tests (`PartitionResourceCacheTest`, Robolectric, native SQLite, real SimpleCache span files; pending CI)

1. **Real lifecycle.** The actual `CacheDataSource` + `CacheWriter` write through the wrapper: bytes, content length and a clean release.
2. **Never reaching the native cache:**
   - another key's lock, metadata and read;
   - a real foreign file from a second cache under the same key;
   - a real foreign hole;
   - a real look-alike span with the same key, position and length but a different file.

   Afterwards no foreign key exists, the foreign file and the look-alike's file survive, and the original span and bytes are unchanged.
3. **Admission refusals**, each leaving the cache unchanged: another resource that holds only metadata; too many unknown fields; too many spans; an oversized key.
4. **Fragmentation.** Two adjacent spans read as one 2000-byte range but fill a two-span budget; a third file is refused, with bytes kept.
5. **Concurrent reservations:**
   - the hole budget is enforced;
   - an outstanding `startFile` reserves its span;
   - a file outside every held lock is refused;
   - a failed writer's lock release frees its slot, and its file can no longer be committed;
   - an over-long commit is refused and the slot kept;
   - a retry succeeds and the instance releases cleanly.
6. **Metadata:**
   - metadata with no bytes is refused, and no key is created;
   - too many fields and too many bytes are refused on the merged result, with the unknown field preserved;
   - a declared length is accepted, but one below the retained bytes is refused.
7. **Metadata-only resources:**
   - the pinned Media3 drop is demonstrated on a separate raw cache;
   - an admitted metadata-only resource refuses both lock calls, and its metadata survives `removeResource`.
8. **Removal:**
   - removing the last span with metadata is refused;
   - `removeResource` is refused during a held lock;
   - explicit `removeResource` afterwards works.
9. **Blocking and interruption:**
   - a blocked `startReadWrite` holds its reservation (a further lock is refused, not queued) and wakes to a committed read span;
   - an interrupted waiter returns its reservation;
   - the instance releases cleanly.
10. **Native failure:** the SimpleCache is released behind the wrapper. The commit throws and the instance becomes `uncertain`; locks, metadata and removal are then refused, and the committed span file remains on disk.
11. **Release:** refused while a listener or a lock is outstanding; the listener budget holds; after release, calls fail.

## Limitations and later integration (not solved here)

- **Unwired.** No routing, lease pinning, journal, locator publication, request/cover handover or opt-in UI. Legacy caches and every production caller are unchanged.
- **What the bounds cover.** They limit what this wrapper lets a partition grow to. They don't bound:
  - an index opened elsewhere;
  - the SimpleCache's own per-instance overhead;
  - the Android file system;
  - total bytes on disk (no byte budget was added; free space belongs to the migration).
- **Metadata-only state is not durable** in pinned Media3: it is lost on reopen regardless. The wrapper refuses to *create* it and keeps an admitted one read-only, but it cannot persist it.
- **After an uncertain failure,** uncommitted or partly committed span files are left in place for explicit recovery. Re-admission with `open` is the only way back to writable.
- **Not verified here:** the jar byte-match and the `Cache.java` method list. No heap, speed or device claim.

Root source review/fixes: GPT-6 / Codex desktop (exact variant/effort not exposed) independently checked the pinned Cache.java/mutation contracts and byte-matched all four inspected extracted sources to the published JAR (SHA256 a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a). Fixed hard upper configuration budgets, empty exact-key preservation, range/end overflow, exact staged-file length/identity and poisoning after native acquisition/startFile failure. Listener bridges expose the facade and refuse callback write/removal/release reentry before stale counters could over-admit native spans. Five added native regression controls cover these findings. This root contribution is source review plus fixes, not an independent review of its own edits. No Android local compile; latest-head CI is the authority. Claude ended normally/idle at78%five-hour/92%weekly USED (runtime events override its final claim of no events); no quota exhaustion this assignment.
