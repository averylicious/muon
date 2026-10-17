# Completed partition deletion — October 12

Solo #253 successor to #503. No Claude/phones; experimental checkout untouched. Partition mode remains disabled. The user explicitly requires **full Delete/Move support for legacy originals before activation**; this boundary does not satisfy that entire gate.

Explicit Delete of a new clean completed partition save now removes its exact owned bytes under exclusive access, waits for actual native release, rechecks completion/aliases and removes the terminal row through Media3's public DefaultDownloadIndex.removeDownload API. No manager Remove command is sent. Closed catalog/save claims remain as durable no-fallback/no-ID-reuse tombstones. Active readers, including EOF, unfinished/unknown owners, other-row aliases and other-shelf claims refuse before byte removal. Unknown native/index mutation retains the real owners and gate; no cleanup retry.

OfflineStore routes this before legacy index hydration/service delivery and posts only a scalar removal ID to the shelf's main-loop bootstrap tombstone/marks/artwork callback. No full Download is retained for that callback. Bulk Delete, Ready migration/original retirement and Move are not completed by this change.

Pinned published Google Maven Media3 1.11.0 sources checked: DownloadManager.java 788-806 loads only nonterminal states, 1144-1158 removes terminal rows from its task list before index completion; DefaultDownloadIndex.java 216-224 removes a row by ID without loading its data. External raw destructive commands remain refused before the partition manager. This relies on those existing one-writer/service gates and never permits arbitrary direct manager Add/Remove calls.

Verification: git diff --check; Actions is the first Android compile. Native SQLite/Robolectric tests cover actual manager completion, EOF reader refusal, owned byte/row deletion, huge alias and cross-shelf refusal, unsupported/unfinished/oversized rows, actual OfflineStore scalar callback and whole-root restart tombstones. Exact final head/run/reports/APK receipt belongs on the PR/#253/current checkpoint. UAT pending; app PR stays OPEN.

Remaining engineering: legacy/Ready retirement and full Move; automatic played eviction/resize; persisted opt-in/full-restart recovery and user controls; production selection and final lifecycle verification. Full Delete/Move is required before activation, not an accepted deferral. Continue useful authorized engineering after verified boundaries while capacity permits.
