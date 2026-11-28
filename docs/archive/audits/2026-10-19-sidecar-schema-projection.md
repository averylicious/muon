# Bound sidecar schema projection (#253)

Inspected cumulative #627 head6a8fbe9c4d04929fcbad498143328df3fb32ddf0/main a2db02b8ba0b4959f28aecf5fa89196cf92622bc. PartitionContentMetadata checked at most two materialized schema rows, but SELECT name,sql could first hydrate arbitrary table names/SQL into CursorWindow and Java. A corrupted/foreign sidecar could therefore exceed the intended small schema projection before rejection. No real-device OOM or compromise was reproduced.

Compare exact known table type/name/SQL in SQLite and project only numeric classification, at most three rows. Require exactly chunks then owner; unknown/missing/extra tables, views/indexes/triggers and version/owner/chunk mismatches still refuse. Fresh version0 creation still requires empty application schema. No schema migration/repair, metadata truncation, routing/close/ownership or activation change. This bounds the result projection, NOT SQLite's own parsing/page/schema memory or arbitrary database safety.

Existing API35 read-only unknown-schema fixture now uses a stored SQL comment over3MiB (larger than normal CursorWindow), requires IOException and exact full-file preservation. Existing writable unknown-schema case also proves full-file preservation. Normal fresh/reopen/chunk/UID/unsupported-version controls remain. No new test count. CI first compile/run; selected phone reader testing remains on #627's independently verified diagnostic build. No measured performance claim.

GPT-6 / Codex desktop implementation and source author self-check, exact variant/effort not reported; no Claude/independent review. Separate isolated worktree, cumulative on #627. App stack OPEN for inherited acceptance. No main/experiment merge, Stable/tag/release/activation.
