# Explicit partition recovery admission — 2026-10-12

Successor to #497 / `14db08d00e30b2e576cd42de1d59714074afdb52`, whose Android .970 and downloaded reports/APK passed (1,339 tests per variant). Main included: `2a275286f28db78083567465a521b7ea46f32640`. GPT-6/Codex desktop implementation and self-check, exact variant/effort not reported; independent review pending. No Claude or devices this cycle.

## Implemented

The owned shelf exposes off-main, shared-gate recovery pages: at most 16 migration records or 16 new-save lifecycle records per call, with a scalar keyset cursor. These are observations, not authority to open, adopt, repair or delete targets. Interrupted new saves remain preserved and unavailable to automatic resume.

Ordinary worker conversion now expects no existing attempt. An interrupted conversion needs a separate explicit retry with the exact record that was shown, including allocation, token, phase and evidence. Fresh/retry consent is carried by the one worker control through source projection and publication. Publication refuses stale consent before changing the locator; the journal repeats the check inside its begin transaction. A race after locator reservation may leave an unused reservation but cannot open a target or overwrite the changed migration record. Ready is never retry authority.

A retry allocates a fresh target and byte-verifies publication through the existing native lifecycle. Original and earlier partial targets are retained; no target adoption, source cleanup, metadata history, automatic restart replay or extra worker queue is added. Unknown current-process I/O remains blocked. Controlled native-cache tests cover same-token phase changes, fresh-vs-retry admission, bounded restart pages, explicit retry after owner recreation, and preservation of original/partial bytes.

## Remaining engineering

Partition mode is still disabled. This completes a recovery-admission boundary, not production selection or a user-facing migration screen. Production root startup/routing, played-cache and saved-copy destructive policies, explicit opt-in/restart selection and recovery UI still need integration. #253 remains a Stable engineering blocker; inherited application acceptance PRs remain OPEN. Full SD recovery and dependency/tool trust keep their accepted deferrals. Fresh device permission will be requested only when enabled integration requires it; no POCO testing is needed for this source boundary.

Latest-head CI/report/APK verification is pending; record the final receipt on the PR and #253 before proceeding. No unrun check or device acceptance is claimed.
