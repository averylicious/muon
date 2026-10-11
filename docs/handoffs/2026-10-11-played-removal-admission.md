# Explicit played-copy removal admission — 2026-10-11

Solo GPT-6 / Codex desktop; exact variant/effort not reported. No Claude or phones. Successor to OPEN #492. Production partition selection stays disabled; app acceptance remains open.

Explicit played-copy removal previously bypassed the Store gate and returned Sent even if SimpleCache.removeResource threw. It now owns shared admission for the real claim check/mutation call. Exclusive/draining storage refuses before either call; protected copies remain intact and known refusal drains the permit. Opaque mutation failure retains exact cache/key/failure and permit, quarantines the played evictor and returns Failed with a truthful restart message. It does not claim bytes were kept after an unknown mutation. Shared admission protects exclusive migration/startup; it does not freeze every other shared operation or grant deletion of migration originals.

Four actual native-cache controls cover mutation-lifetime admission, pre-call exclusion, protected original and thrown mutation with no false success/retry. Android Actions is first compile; record exact head/report/APK receipt on PR. This fixes one real destructive path; broader partition manager removal policy and production opt-in/recovery integration are still engineering, not merely UAT. POCO can remain deferred this cycle.
