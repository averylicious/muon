# Explicit migration progress and recovery controls — 2026-10-13

Cumulative successor to #546 bd9823371065892bb279f6dab813f64278435b97; includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. No Claude/phones/independent review.

Settings exposes current scalar migration phase/accepted bytes and two-entry interrupted-only pages for an actually prepared partition store. Cooperative cancellation explains acknowledged worker exit; exact attempt identity prevents a stale Cancel button affecting a newer same-key operation. Retry requires confirmation of the shown durable record and preserves original/older partial copies. No editable internal IDs, automatic retry, target cleanup or backend activation. Each panel keeps one scalar progress snapshot and at most two receipts, polling only while an attempt is active; no history or unbounded observer queue. CancellationException is rethrown from recovery reads.

One actual application-controller regression test covers stale same-key cancellation; inherited dispatch/recovery tests cover actual I/O/publication/ownership. Local diff/branch checks; Actions first Android/Compose compile. There are no Compose UI tests. Visual quality, spoken/focus behavior and device interaction remain pending user testing; do not claim source reading establishes them.

Default startup remains legacy; partition mode disabled. Next engineering: explicit fresh migration from a selected saved copy, trusted platform root/volume resolution and absent-card policy, fresh-install native bootstrap, opt-in/setup recovery UI and final composed failure/lifecycle coverage. Current preparation does not need a phone; cumulative application PRs remain OPEN for inherited UAT.
