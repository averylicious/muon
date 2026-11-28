# Actual partition store preparation — October 12

Solo #253 main-audit successor to #502, with its exact corrected head in ancestry. Main included: `2a275286f28db78083567465a521b7ea46f32640`. No Claude, phone, experimental edits or Stable release. Partition selection remains disabled.

## Actual integration

- `OfflineStore.preparePartition` prepares the app's real common storage resources plus phone/card `OwnedPartitionShelf` instances off main. It registers each actual owner before its prepare and returns the same typed prepared Store consumed by the common main-looper binder. The binder uses `Shelf.partitioned` for partition shelves and the original native path for legacy shelves. No legacy SimpleCache is opened by partition preparation, and no manager is bound on the preparation worker.
- `PartitionStorePlan` validates exactly one phone and at most one card volume binding, canonical absolute paths and non-overlapping legacy/partition roots before resource allocation. It does not infer Fresh/Resume from missing files, serialize preferences, repair a plan or perform a live switch. Failed/partial startup remains sticky and retains actual owners; no phone-only demotion or legacy fallback.
- Name admission checks the other prepared shelf's original/index/catalog/new-save/migration claims, including unstarted and empty journal claims. Scalar phone/card index probes also preserve retained absent-card aliases without hydrating request data. A never-created optional index can be absent; orphaned versions, views and unsupported schemas remain uncertainty, with no implicit Media3 initialization/migration.
- The common binder and limit/Settings feedback route partition usage through the mixed audio path. It does not promise LRU eviction for the conservative aggregate-admission policy. The legacy evictor remains an unused compatibility field for partition Stores; no partition target uses its native cache/order.

## Verification and remaining gate

`git diff --check` passed; Actions is the first Android compile. Actual native SQLite/Robolectric tests cover both prepared shelves and main manager binding, original playback without legacy native locks, app-wide pending-name refusal, retained absent-card aliases with 8 MiB payload, plan/main-thread refusal before allocation, and sticky partial-card failure. Optional-index tests distinguish genuine absence from orphaned version/view uncertainty. Final exact-head checks and downloaded reports/APK receipts belong on the PR/#253/current checkpoint.

**Not yet enabled:** production selection still refuses PartitionV1 before legacy provider/cache allocation. Persisted opt-in/full-restart plan controls, final automatic eviction/resize, general original/Ready-copy retirement and recovery UI remain engineering. This factory alone does not clear #253. Application PRs stay OPEN for inherited acceptance. No device/visual/mount/performance evidence is claimed; current task prohibits device use.

Continue authorized engineering across verified boundaries while capacity permits. Preserve isolated writers, actual owners on uncertainty, reviewed heads and portable receipts. See continuous-engineering.md; no one-PR-per-turn stopping rule.
