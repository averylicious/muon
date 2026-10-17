# Partition played maintenance disk order — 2026-10-12

## Scope and evidence

Main audit; cumulative successor to #506 at 9ace4194917fc591bf9a31c5e2194b2db81088b4. Main included: 2a275286f28db78083567465a521b7ea46f32640. GPT-6 / Codex desktop implementation and source self-check; exact variant/effort not reported. Claude and phones unused. Partition mode remains disabled. User requires full Delete/Move of legacy originals before activation; protected/read-only originals are not an accepted release substitute.

Resize builds one disposable private disk order from fixed journal pages and one actual native resource probe at a time. SQLite stores only key/age observations; no resident all-copy LRU set is added. Normal uncontended maintenance performs one age build and a scalar usage census, instead of reopening all candidates/recounting the whole library after each deletion. Real concurrent removal can require another census. This is an algorithmic source change, not a measured device performance claim; a requested SQLite pager size is not an exact process memory bound.

Each removal still requires current Closed ownership, exact native UID, caller/index claims, current age and exclusive admission. The derived observation never authorizes deletion by itself. An epoch check under exclusive access prevents a concurrent manual deletion from making resize over-evict from an old byte total. A newer actual read invalidates its candidate and leaves resize incomplete rather than deleting it.

The exact derived database owner is retained before I/O, including uncertain close/quarantine. Canonical path/symlink checks precede its creation. Stale disk rows are rebuilt; failed ordering refuses deletion. Session close refuses an active maintenance actor even between its individual storage operations.

## Verification

Local git diff --check passed. Actions is the first Android compile; exact-head CI, downloaded test/lint reports and APK policy verification must be recorded on the PR before calling this verified. Added tests exercise interleaved manual deletion, stale derived rows, close while maintenance is active and refusal of a foreign-file symlink; inherited tests cover actual last read, reader at EOF, protected originals, 19-copy paging and restart. Pinned Media3 datasource sources were checked for CacheSpan's public observation constructor and CacheEvictor touch callbacks. No phone, visual, mount, heap or timing evidence is claimed.

## Remaining work

Automatic make-room for new optional played copies, full legacy/Ready Delete and Move, persistent production startup/routing selection, opt-in/recovery controls and final composed failure/restart coverage remain engineering. Application PR stays open for inherited UAT. No Stable tag/release or backend activation is authorized by this checkpoint. Continue useful authorized work after verified boundaries; source/checkpoint evidence is portable, temporary tool files are not prerequisites.
