# Owned played-copy removal — 2026-10-12

Successor to #499 / `a6cb27542cb9e6adc73fb957073c95b2d9992ac3` (its latest-head CI pending at creation). Main included `2a275286f28db78083567465a521b7ea46f32640`. #497/.970 and #498/.971 have actual verified reports/APKs, respectively 1,339 and 1,344 tests per variant. GPT-6/Codex desktop implementation/self-check; exact variant/effort not reported; independent review pending. No Claude/devices used.

## Implemented

Actual OfflineStore played-copy removal dispatch now reaches the owned shelf/session before any legacy `.cache` access. Only a new owned played-key with a clean Closed journal route is eligible. Legacy originals, migration Ready resources and any manager ID/custom-key/URI claim are protected, as is an explicit caller protection refusal. The manager census uses scalar SQL, never raw request metadata.

The real session takes exclusive admission before opening a deletion target: any active saved reader (including EOF), source/writer or migration prevents mutation. It retires known-idle pools and rechecks the exact journal/UID before removing only that target's bytes/metadata. Its existing Closed journal remains a durable no-fallback claim, so empty audio cannot reappear from a legacy same-key route after restart. No DownloadManager record is removed or fake completion fabricated.

A partial remove/native close retains the exact returned cache and pools with the exclusive permit; no second cleanup/close or forced reset follows. The native owner remains bounded by the same process budget. Tests compose real shelf/session/service-compatible manager binding, OfflineStore dispatch, active/EOF exclusion, oversized alias protection, clean removal and new-owner restart while preserving originals.

## Remaining engineering

Partition mode is still disabled. Aggregate played-library budget/eviction and explicit Clear, saved download/migrated-original deletion/move policy, production selected-root startup and opt-in/restart/recovery UI remain REQUIRED. This is removal of new owned played copies only, not permission to delete old transition originals. #253 remains a Stable blocker and app PRs stay OPEN for inherited acceptance. No POCO is requested for this source boundary.

Latest-head CI/reports/APK pending. Record final verified head/receipts on the PR and #253. No Stable tag/release authorized.
