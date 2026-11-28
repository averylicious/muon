# Post-RC maintenance continuation

Updated 2026-10-25. Broad source/CI audits and fixes continue separately from installed frozen M3E RC .1420. Published Stable remains v0.2.0. **No phones this cycle**, no installation, profile replacement, Stable tag/publication or moving experimental owner checkout.

## Current verified baseline

Main `f0a744273e58645490a1f918a807ad2447d64662` includes CI #685 and Gradle-security #694. Main [Android .1452](https://github.com/averylicious/muon/actions/runs/40109801615), tools .37 and inventory .116 passed; downloaded main XML1,821cases/variant, zero failures/errors/skips, lint0errors/65warnings. [Canary .1452](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.1452) auto-published at that source, not installed. BUILD/checksum/release metadata agree on APK digest `d97a4cc16cd96357512c63d9b0f6e934cfcb830fade9e64d1ddd2984ac8d415d`.

Remote M3E remains `ee7ddb7521855a7422036d7cd4f384d11b308caa`. Forward sync #692 at `212b9997f29052421bdc8c118000f28f3564b757` includes patched main, retains its app/library trees exactly and uses verified Gradle9.8.2. Combined .1454 passed1,883cases/variant, lint0errors/71warnings. Experimental owner lands it with a merge commit at their clean boundary; root must not advance their branch. #693 stays open until then.

## Draft runtime/tool candidates

All include patched main above. Downloaded XML for .1453–.1459 confirms no failures/errors/skips and lint0errors/65warnings. Exact artifacts and attribution live on each PR; these are separate candidates, not a single installed acceptance build.

| Work / maintained state | Exact candidate / Android run | Remaining action |
| --- | --- | --- |
| [Network #638 / PR #682](../audits/issues/638.md) | `b80ef8966f92d6c70a2f3b523313dde8cb4203e2`, .1457,1,832cases/variant | LAN/permission/VPN/IPv6/handover acceptance |
| [Recorder/measurement #83 / PR #683](../audits/issues/83.md) | `a22c9c8ac59b676f48b459cc5a65d748cef15bbd`, .1453,1,821cases/variant; tools .38 recording/.39 measurement passed | Physical recorder traversal; controlled minified first-frame pilot; wider journeys remain future engineering |
| [Collections #639 / PR #684](../audits/issues/639.md) | `15a545f01fefa6db4be8a98f647ce0d369832778`, .1458,1,825cases/variant | Loading/focus/scroll/query/order/source acceptance |
| [Delete errors #646 / PR #686](../audits/issues/646.md) | `e8bd003086e2b9fcf5cc464fc1349e5e3c557eb1`, .1459,1,823cases/variant | Error-notice acceptance; historical reset/SD refusal stays unresolved |
| [Notification revision #207 / PR #689](../audits/issues/207.md) | `966fcfe9d5a4c5aed5def5e326b5731e7ba20482`, .1455,1,825cases/variant | Reused-ID notification/Compose/saved-cover acceptance |
| [Connect cancellation #690 / PR #691](../audits/issues/690.md) | `6a489a1baf0f05e37f23dcb985b8be872ba7831f`, .1456,1,823cases/variant | Initial Cancel/retry/auto-connect/pointer-completion acceptance |
| [ReplayGain identity #695 / PR #696](../audits/issues/695.md) | `061d1b9918e1007d0c0c8c91f5e96aeb220975fc`, fresh CI pending; earlier b828/.1460 passed | Verify callback-state correction, then tagged/untagged/repeat/toggle/saved acceptance |
| [Notification queue #697 / PR #698](../audits/issues/697.md) | `613cf0667a10c7c131b813da90a648c9822c1e8f`, first .1461 pending | First compile/tests/lint; then source/notification acceptance |

Benchmark pairs were downloaded and checked for checksums, exact pinned signers, package/instrumentation, selected recording/measurement mode, target API37 and non-debuggable/profileable target. No new profile or timing measurement. The earlier ephemeral helper signer was corrected only in this tool PR; do not silently uninstall any helper to bypass its signature.

## Ownership and next work

Root owns the listed isolated candidate branches and `codex/post-rc-evidence-oct25`; current documentation edits are in that PR until merged. Allocated audit Claude is idle; check the user's reset time and actual model/session state before reuse. No other allocated audit worker or phone/storage operation is active. The original experimental checkout is preserved. Fresh live branch/check/protection verification still precedes any future merge; a later docs-only main commit does not make old-head CI a new-head pass.

Continue useful source engineering while allowance permits. Next: verify #696/#698 current CI; review the notification executor's concurrency/closure policy; consider one narrowly justified #401 read-checkpoint optimization without weakening full ownership/destination/source-retirement proofs. #401 latency is not measured anew and remains deferred from the frozen RC. A blocker in one area does not end other authorized work. Save current evidence in place before capacity becomes low, then continue if useful work remains.

[Latest selected Maven advisory scan](../audits/2026-10-25-dependency-advisory-refresh.md):288coordinates,13matched/47IDs, unchanged pairs; this is not vulnerability absence or publisher authentication. Gradle remediation is separate. #679 future promotion policy remains draft/open for an actual explicit promotion. No numerical suite reduction is authorized (#650 closed not planned). Old #641–#644 remain superseded, not new blockers.
