# Composed new-source and public partition Move — 2026-10-13

Cumulative successor to #532 6542eba4d6b4bde031232968535cccf5111ff507. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. No Claude/phones/independent review. Partition mode remains disabled; application stack OPEN for inherited acceptance.

Actual source save journal chooses legacy-original versus new-partition-source Move; never infer ownership from saved/ prefix, because older legacy copies can already use it. A composed new-source operation copies locally, publishes exact completion, durably binds cover/source-tree Pending and finishes target-aware cleanup. Each boundary records a scalar stage/byte count before another cancellation checkpoint. Existing Pending/Complete blocks a new copy and requires separate renewed recovery consent. Existing serial app Move dispatcher, bounded name-only selection and scalar main-thread status/removal callbacks are shared; no new task/request backlog, native-cache getter or destructive manager task.

Tests exercise actual composed Move without upstream, cancellation at Copied/Published/Pending with source preservation or exact consented recovery, public app Move from a new partition source with main-thread callbacks, and actual cross-shelf alias refusal/retry. Local diff check only; Actions first compile. Record exact-head run and downloaded actual artifact/report checks on PR. Manual QA remains pending; no device/durability/performance claims.

Next: full supported original-row disposition (nonterminal/unverified), honest old native metadata retention, persisted production Fresh/Resume opt-in/free-space/progress/cancel/retry/recovery controls, final composed lifecycle/failure coverage and inherited UAT/#401 startup evidence. Full Delete/Move before activation stays mandatory. No force preferences, Stable release/tag or experimental edits.

Initial .1042 compiled 1498 tests but one multi-scenario fixture reused a completed source ID in the same app database. Each scenario now has a distinct source key; this preserves the actual fresh-ID refusal. That failed run is not a pass; inspect replacement latest-head CI.
