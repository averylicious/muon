# Legacy inventory cursor ownership — 2026-10-12

Cumulative successor to #517 at 260d55ecf94e3b9b81cbb0213e71de7713c657c8; includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. Solo; no Claude/phone. Partition mode remains disabled; full Delete/Move before activation remains required.

Read-only legacy contains and paged inventory used ordinary cursor use blocks, unlike the newer bounded completion and projection paths. If actual close threw, the cursor could become unreachable and the shared gate looked healthy. Each scoped query/validation now owns actual cursors through MigrationIoOwnership; failed close throws the typed owner so BarrierSavedAudio retains it and invalidates the gate. A visitor stopping on its first row still must close the actual cursor before returning. Per-operation trackers avoid sharing an unsynchronized attempt across readers; no whole inventory is collected.

Full/audio projection uses the same injectable per-attempt factory for consistent actual ownership evidence. Known-closed callback failure preserves the original error and releases the permit. No file/index mutation, retry/reset or provider-close workaround was added.

Local diff check passed; Actions is first Android compile, latest-head results pending. Tests retain actual contains/page cursors with deliberately failed close, assert no retry/gate acquisition, and cover known-closed visitor cancellation. No device or performance claims; PR remains open for inherited acceptance.

Continue Move destination publication and durable destination-bound source retirement/recovery, supported original Delete disposition, production opt-in/Fresh/Resume controls and final composed coverage. Do not use source-helper availability as production activation approval.
