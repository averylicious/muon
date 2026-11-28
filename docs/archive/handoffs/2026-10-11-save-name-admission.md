# Save-name scratch admission — 2026-10-11

Solo GPT-6 / Codex desktop; exact variant/effort not reported. No Claude/device use. Successor to OPEN #490; partition mode remains disabled, all application acceptance stays open.

OfflineStore.takenNames passes the actual Store barrier to SavedNameRegistry. Shared admission spans the real SQLite census/reservation transaction and both statements through real close/rollback. Reads/writes check the retained lease. Known caller population failure rolls back and drains normally. Exclusive admission refuses before database allocation. A failed opaque construction or actual close conservatively retains the exact returned participants/context/failure and permit; later retry cannot replace them or silently reset storage. This is not a new schema, native cache key bound or manager deletion policy.

Four native SQLite controls cover lifetime through close, pre-allocation refusal, known population rollback and an injected actual transaction-close failure. Android Actions is first compile; record latest head/run/artifact/results on PR. Device behavior and performance are unverified. Ordinary successful naming is unchanged; a new UAT candidate should verify saved copies still get distinct names and cancellation leaves originals.

Next: verify this and preceding #488–490 final CI, fix failures, and continue remaining cache/census/removal/native owner lifetimes and production partition startup/opt-in/recovery controls. No device is needed for these tests; POCO becomes useful once an enabled production migration/recovery candidate exists.
