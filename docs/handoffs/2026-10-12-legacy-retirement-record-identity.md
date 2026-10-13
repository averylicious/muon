# Exact saved-record retirement identity — 2026-10-12

Cumulative successor to #513 at 80d423cb1dd82003b15f9f636e89bdf3a1d3338f; includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. Solo, no Claude/phone. Partition storage disabled; full legacy Delete/Move required before activation.

Prepare bounded original-record lookup and persistable completion binding for the final Delete controller. The original-only lookup supports bounded own-key full-progressive completed records predating saved/ names, with the same SQL payload/type/state guards and scalar alias census. It cannot admit old names through fresh allocation/Add/token commands: those retain the saved/ prefix requirement. A fixed hash binds key, request digest, declared length and timestamps without retaining full request data.

Retirement manifest v2 optionally stores that fixed completion identity. v1 manifests still decode as unbound files-stage recovery records and cannot establish terminal-index deletion authority. Binding refuses another original's key; trailing/over-budget/malformed digests refuse. Journal schema/name remain unchanged. This is private disabled preparation, not an existing production migration.

Completion-index cursors now use the actual retained I/O owner. Unknown close keeps the cursor and borrowed provider alive through typed uncertainty; callers must quarantine their shared gate. No terminal row, cover or original is removed by binding/lookup. The final controller still must bind before persisting intent and recheck current completion/aliases before any mutation and terminal record removal.

Local git diff --check passed; Actions first Android compile. Added native SQLite/source tests cover numeric original IDs without relaxing fresh commands, changed timestamps/request-data identities, oversized original payload and alias refusal, unknown actual cursor close, v2 binding roundtrip and v1 unbound recovery. Record exact-head test/lint/APK receipts on PR; manual/device/performance verification not claimed. Application PR remains open for inherited UAT.

Next: integrate exact completed identity into consented original Delete/recovery; remove terminal row and cover only after both original/Ready bytes and native closure are verified. Then full Move, persisted production opt-in/recovery and final lifecycle/failure coverage. Played make-room/card-startup evidence remain separate. No Stable publication or backend activation.
