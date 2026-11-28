# Migrated-original cleanup — 2026-10-12

Cumulative successor to #512 at d8258de2cb21bc7fd62636123cdbd41fc14b4af2; includes main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. Solo, no Claude/phone. Full legacy Delete/Move remains required before activation; partition mode stays disabled.

The actual legacy-owner physical cleanup stage now retires a Ready migrated duplicate before original files. It uses the SAME live exclusive storage lease, verifies its owning barrier, the exact Pending retirement record, no conflicting new-save claim, Ready/source UID and current receipt. It drains only known-idle native pools, opens the bounded target with its actual native owner, checks target UID/key ownership, removes the selected resource, confirms absence and closes the actual cache before original-file cleanup. Pending/unverified target claims refuse; no partial migration is adopted.

The Ready receipt and retirement tombstone stay durable to prevent ID reuse/fallback. Unknown native remove/close or post-mutation disposition keeps actual cache/session owners and quarantines the source gate. Successful return remains copies-only: no download-index/artwork removal or Complete transition. Exact Pending recovery can recheck an already empty Ready partition after restart.

Local git diff --check passed; Actions first Android compile. Added real-shelf tests cover original+Ready removal, no-consent preservation, no resurrection on actual restart, repeated explicit Pending recovery and rejecting a foreign exclusive permit before native mutation. Record latest-head CI/report/APK evidence on PR. No phone/performance claims; inherited UAT pending, application PR open.

Next: persist exact completed-download identity with retirement intent, alias-safe terminal-index/artwork disposition, wire complete Delete and full Move, persisted production opt-in/recovery controls and final composed lifecycle/failure tests. Optional played make-room and saved-card startup evidence remain separate engineering. No Stable release/backend activation.
