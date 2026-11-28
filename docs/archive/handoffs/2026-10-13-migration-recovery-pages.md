# Bounded migration recovery pages — 2026-10-13

Cumulative successor to #545 7d935c207aa42c0562be29aedda1a73e1a6181df; includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. No Claude/phones or independent review.

Interrupted migration inventory now filters Ready in SQLite before hydration and retains at most two exact scalar receipts. Keyset continuation may conservatively offer one empty final page; no full history is retained. Invalid phase types remain selected and fail bounded decoding rather than becoming false absence. The actual recovery cursor is retained through MigrationIoOwnership: unknown close keeps its exact borrowed connection/root reachable, blocks further journal operations/close and quarantines the actual shelf gate through recoveryRead. No reset, automatic retry or target cleanup authority is introduced.

Three actual SQLite tests cover Ready filtering/two-entry continuation, oversized malformed phases and unknown real cursor closure with every later operation refused. One actual shelf test confirms gate quarantine and retained connection/original bytes. This change hardens the new recovery read path; it does not claim all earlier journal constructor/write I/O is comprehensively characterized. Local diff/branch checks; Actions first Android compile. No runtime/device, power-loss or performance evidence.

Partition mode stays disabled and cumulative application PRs remain OPEN for inherited UAT. Next engineering: migration progress/cancel/retry UI, explicit fresh migration from a selected saved copy, platform roots/volume resolution and absent-card policy, native bootstrap, opt-in/setup recovery and final composed failures. No phone needed for preparation.
