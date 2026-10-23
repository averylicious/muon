# Bound native schema projection (#253)

Same source-confirmed projection gap as #628, in PartitionNativeAdmission.inspect on d7acd5de475c99d1ffc3c96ace49eca0a333365f/main a2db02b8ba0b4959f28aecf5fa89196cf92622bc. The count-bounded native preflight projected type/name/SQL for a matching native table, including unknown trigger SQL. CursorWindow fills rows before the Kotlin rejection branch, even when that branch does not call getString(sql). No device OOM/compromise was reproduced.

Compare the unchanged pinned exact native table SQL/type/name and the nullable implicit index inside SQLite; return scalar1/2/3 or0. Keep the same table/object limits, checkpoints, versions, bounded native keys/metadata/files, complete span/file/UID census and native-opening exclusion. No schema editing/migration, skipped validation, routing or activation change. SQLite internal parsing/schema/page allocations are outside this narrow result bound.

Expand the existing actual-native unknown-trigger/file-format test with over3MiB stored trigger text, require IOException, unchanged content row and all audio bytes, and retained trigger. Existing normal actual Media3 init/reopen/empty/unknown-format/cancelled-preflight controls remain. No new test count. CI is first compile/run; reader device follow-up uses separately built #627 and does not verify this head. No speed claim.

GPT-6 / Codex desktop implementation/source author self-check; exact variant/effort not reported. No Claude/independent review. Separate isolated worktree, cumulative #628. App stack OPEN, no main/experiment merge, Stable/tag/release/activation.
