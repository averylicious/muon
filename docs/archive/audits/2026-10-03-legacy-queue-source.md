# Legacy playback queue: API37 source follow-up

Inspected main `20ea53a1fbfb3f117efc2cc093ded8eab268fa58` and combined candidate `e04c4ecacd8f3917b4b471eed9b8141888106d1b`. Continues #253 and [resource inventory](2026-10-02-resource-budget-inventory.md). GPT-6 / Codex desktop, effort not reported: source/self-review, no phone, runtime Binder test or measured heap/latency.

## Published sources

- Pinned Media3 session1.11.0 sources from Google's Maven: SHA256 `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715`.
- Published SDK API37 sources package `source-37.0_r02.zip`, SHA256 `f5274d87b59d3aaa53caaa018b4aa807046d58ecd755a717a6a100c714bdd1b9`. This is SDK source, not verification of either phone's installed OS/OEM framework binary.

## Narrowed source questions

`MediaSessionLegacyStub.isQueueEnabled`1262–1265 requires COMMAND_GET_TIMELINE both in its available commands and the player's commands. `MediaSessionImpl`234–250 initially supplies DEFAULT_PLAYER_COMMANDS to that stub; notification connection updates those commands at1019–1020, and later changes flow through766–783. Muon's callback grants same-UID notification controllers default commands and trusted external controllers an explicit read/transport set including timeline. No Muon source command policy here intentionally hides the legacy queue. Actual connection/player command availability and publication timing still need runtime confirmation.

`MediaSessionLegacyStub.updateQueue`1635–1685 converts the full timeline into media items, allocates a bitmap-future list and QueueItem list, and publishes that list. URI artwork produces null futures on this path; it does not fetch every queue cover simply because the app uses artworkUri. Embedded artwork has a different decode path. `LegacyConversions.convertToMediaDescriptionCompat` carries metadata extras, including Muon's encoded SONG_EXTRA. No legacy sliding window is applied by this code.

Media3's legacy MediaSessionCompat.setQueue561–575 validates IDs; its platform implementation2056–2066 retains the compat queue reference and builds a framework QueueItem list to pass onward. This establishes a source representation/lifetime, not deep-copy byte totals or system_server persistent retention.

**Correction to the older mechanism assumption:** the Media3 comment1683–1684 mentions ParceledListSlice, but inspected API37 `android.media.session.MediaSession.setQueue`640–652 calls ParcelableListBinder.send, not ParceledListSlice directly. The actual API37 helper:
- stores received items in an ArrayList (`ParcelableListBinder.java`53–60), accumulates across calls and invokes its consumer when the announced count is reached (79–107);
- checks parcel size BEFORE writing each whole Parcelable (117–138), so chunking does not split one oversized item;
- has no aggregate byte/record cap in those inspected methods.

These source facts narrow the older framework hypothesis. The consumer/system-server record is outside this SDK source package, so persistent system_server storage/lifetime remains untraced. No TransactionTooLargeException, Android17 defect, unbounded resident heap or practical OOM was reproduced. Different SDK/OEM versions can use different mechanisms.

## Result for #253

The combined candidate's per-record guard and NUL-safe codec interaction are independently testable, but neither is an aggregate library/queue heap or traffic bound. Whole-queue conversions and retained extras remain part of the resource design. Existing legitimate queue length/duplicates and controller compatibility must survive any later compact-reference/window/budget change; do not silently shorten a queue or strip played-copy metadata.

Next: trace the matching system-server consumer only with verified source/build provenance, or retain that limitation; define representative large-library measurements and preservation/retry policy before choosing aggregate caps. Source size checks are not live memory measurements. No source defect requiring an immediate platform workaround was established here.
