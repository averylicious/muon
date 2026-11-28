# Explicit partition Move recovery entry — 2026-10-13

Cumulative successor to #533, including its cancellation source-ID fixture correction 186e855bfc6d4a296e4fdf584a2b9abac3363ad4. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. No Claude/phones/independent review. Partition remains disabled; app stack OPEN for inherited UAT.

Explicit worker-only recovery selects a bounded key AND exact durable token, rereads actual Move/original journals, refuses conflicting/stale/non-Pending entries and requires renewed consent. The final target-aware controller revalidates current state under exclusive admission. Existing app serial Move worker/exclusion owns submission; only confirmed success posts scalar destination status/source removal on main. Return true means submission, not completion. No automatic replay, generic Delete delegation or adoption of another target/root.

Recovery page reads now quarantine their actual shared gate/root on unknown actual cursor/statement/file closure, retaining real participants instead of releasing admission over a live unknown child. Actual Cursor-close injection test proves the real SQLite connection stays open, only one close is attempted and later reads/ownership are blocked. Fixture-only cleanup knows its injection did not close the child; no production reset/retry API is added.

Other tests cover exact token/renewed consent/scalar result, original Move recovery and missing entries, public recovery submission/exclusion/main callbacks. Local diff check only; Actions first Android compile. Latest-head reports/APK evidence on PR; no phone/performance/durability claim.

Next engineering: production persistence/opt-in/free-space warning and recovery UI; complete supported original disposition/old native metadata retention; final lifecycle/UAT/#401 startup characterization. Full Delete/Move before activation required; no forced preferences, Stable tag/release or experiment changes.
