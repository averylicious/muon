# Stable tag ancestry safeguard — 2026-10-03

Inspected main fff143b630521268ec6b1f829ae335e0884ad176. GPT-6 / Codex desktop, effort not reported; investigation/implementation/author self-review, no independent review claimed. Issue #284.

## Confirmed gap and change

Android APKs selects Stable for a canonical vMAJOR.MINOR.PATCH tag and its publisher verifies that tag exists, but neither establishes main ancestry or excludes experimental commits. Branch direction gates PRs only. A trusted contributor could accidentally tag an unmerged audit/experimental commit and publish it as Stable. No such release or hostile exploitation was observed.

The read-only `tools/check_release_policy.py` reuses the existing immutable alpha anchor/ancestry helper. For tag builds it requires a canonical Stable tag/full workflow SHA, complete Git history, a tag resolving to that SHA, available fetched main/anchor, no experimental ancestry and inclusion of the tagged commit in main. Historical main release commits and annotated tags are accepted. Unknown history fails closed. Branch builds skip this tag-only policy.

Android APKs calls it before restoring signing files, then independently in the publication job before downloading/uploading assets; that job now fetches full history. No package/signing/version/channel change, secret lookup, real Stable tag, release, phone operation or experiment edit.

## Verification / limits

Disposable real Git graph regressions cover normal/historical annotated tags, experimental ancestry even if merged into main, renamed unmerged branches, tag movement, missing refs/anchor, a real shallow clone, noncanonical metadata and unchanged branch behavior. Workflow placement checks cover both guards and complete-history checkouts. Local tests passed; exact-head Actions evidence goes on the PR. No Stable-tag workflow is dispatched to test this because that could publish a release. Successful branch CI is not an end-to-end Stable publication test.

Fetched history is a snapshot, not a lock. The guard does not audit main's source or dependencies, detect manually copied/squashed alpha changes, or isolate signing credentials from a malicious trusted writer. A tag using an older workflow without this guard cannot be retroactively protected by a current-main script. The user's explicit Stable-release request remains necessary.
