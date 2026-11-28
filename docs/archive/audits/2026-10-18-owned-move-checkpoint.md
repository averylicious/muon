# Owned Move digest checkpoint composition

Scope: main audit, successor to #608 `439c9cdab163636ced9d0fe0396f6a0e07c57c60`. No library or schema changes. Source self-check, not independent review or device acceptance.

## Duplicate observation

`PartitionStorageSession.moveDestinationIdentity.live()` previously called `originalPermit()`, whose `requireOpen()` calls the owned shelf's full `available()/located()` journal sweep. It then called the supplied enclosing checkpoint. Every production caller is in `OwnedPartitionShelf` and already finishes that checkpoint with full destination `located()`. The complete-new-save path additionally validates both source and destination journals after the user cancellation callback. This repeats a full destination sweep before the same physical read without removing the later required sweep.

The session now accepts a private-construction `moveReadAvailable` observation, defaulting to the original full `available`. Only `OwnedPartitionShelf` supplies fresh phase/root/volume checks for it. Only Move identity checkpoints use it. The enclosing checkpoint still supplies full journal/root checks before physical I/O; session state and the actual exclusive lease are checked again after that callback. Nothing is cached between reads.

Production caller review:

- `beginCoveredSavedMove`: local `live()` brackets cancellation with full source/destination `owned()`.
- `completeCoveredSavedMove`: `digestLive()` checks fresh roots, cancellation, then full source/destination `owned()`.
- `beginCopiedOriginalMove`: destination checkpoint calls `destination.located()`.
- `beginCoveredOriginalMove`: destination checkpoint calls `destination.located()`.
- `completeCoveredOriginalMove`: destination checkpoint calls both `located()` methods.

The constructor option is not a general permission to use a partial availability callback. Future production Move callers must finish their checkpoint with full destination validation before returning to the session. Generic callers continue to receive full availability by default.

## Preserved boundaries

Initial `originalPermit`, native open/release and non-Move paths retain their existing full checks. Exact Closed save ticket, allocation, migration/disposition claims, completion/native UID/metadata/bytes, immutable binding around full digest and known close, expected receipt equality, Pending/source checks and full target-before-every-source-file-removal remain. Unknown closure/ownership is retained, not retried or treated as success.

Regression additions observe the actual target native owner and then replace its save journal with a link or alias its parent without changing the root inode immediately after known native closure. Both must fail final verification before source removal, preserve exact Pending and allow an explicit retry after restoration. Replacement while native ownership is still open can require quarantine instead of known closure; these tests do not authorize force-closing that uncertainty. A generic-session test rejects loss of full availability while native verification is open. Existing source-journal, volume-loss, cancellation, byte/metadata/binding and restart tests remain enabled.

## Verification status

Local: source/caller review, `git diff --check`, branch preflight. No local Android compile or unit execution. GitHub Actions is the first actual compile/test. Device measurements and full preservation proofs remain pending until current-head reports and selected APK safeguards pass. Do not infer a latency gain from source changes or compare overlapping diagnostic totals as independent costs.
