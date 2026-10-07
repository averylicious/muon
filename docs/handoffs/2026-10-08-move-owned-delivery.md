# Move delivery ownership — October8 continuation

Main-track #230 successor includes #386 strict output and #384 command admission with full open application acceptance ancestry. No local Android compile; Actions is first compile. Refresh this branch's PR/CI receipts before claiming tests passed. User experiment untouched; no device commands this slice.

## Implemented boundary

- Each mover Add and automatic source Remove carries one opaque process-local token, bound to the exact source/destination shelf objects and DownloadRequest. Both real services check it before calling Media3. Invalidation, mismatch, replay or receipt loss across restart converts the command to INIT, preserving available bytes/records. Normal user commands invalidate matching move ownership before reaching the manager.
- Owned Add must actually be admitted before its completion authorizes checking. The receipt persists after queuing source Remove: intent delivery is not manager acknowledgment.
- Destination/source full byte comparison and sole-owner censuses still run off main. A command epoch invalidates the checked snapshot after any ordinary mutating service command. At actual source Remove delivery, exact completed records, both managers quiet, shelf availability, cache length/coverage/extent and epoch are checked again.
- Supported producers use fresh save keys, played-only prefixes, and guarded service commands; these are the byte-writer ownership basis. A token is not filesystem authentication or an arbitrary-writer transaction. External/root filesystem mutation and full SD/hot-swap durability remain #179 scope, deferred.
- Manager-idle callbacks schedule completion checks only once both managers settle. Source removals queue serially, one until acknowledgment, preventing other target Adds/removals overtaking the original's deletion. Refused queued commands are not blindly replayed; original remains available for a later explicit retry.
- Once source Remove is admitted, conflicting UI/service mutations are refused until the matching onDownloadRemoved callback acknowledges the exact source request. Invalidation cannot release an already accepted removal's barrier prematurely. Failed/lost delivery retains both copies; a permanently stuck removal remains busy until recovery/restart, rather than authorizing uncertain deletion.
- RetainedDownloadIndex already stops old queued/downloading/removing operations after process recreation. No move token survives restart or automatically resumes a stale move. Completed destination records and available original/partial bytes remain; arbitrary power-loss/card recovery is not established by process-local guards.

## Failure/output policy

StrictMoveSink independently observes flush, descriptor sync, close and commit for each file it opens. Only its private reserved, never-committed files can be discarded on write/close failure. An uncertain commit, existing partial prefix, unknown/legacy/shared span or user-owned recorded copy is preserved. Retry compares every existing prefix against the still-present source before filling holes; mismatch or out-of-range spans refuse rather than truncate/relabel. No broad orphan garbage collection or storage migration is added. Keeping unknown bytes is the intentional preservation policy, not a missing permission to delete them.

## Verification

Native SQLite/disposable-cache service suite exercises actual move, both Muon services, manager Add/Remove callbacks and actual cache deletion by a network-free fixture downloader:
- healthy owned Add→completed target→owned source Remove→matching acknowledgment, then replay refusal;
- user removal before delayed Add delivery, proving no resurrection;
- old queued Add/Remove with a newly constructed ownership registry, proving refusal while records/bytes stay (not a phone reboot);
- changed source record and an intervening command epoch, proving queued automatic removal refusal;
- two entries, serial source-removal delivery and acknowledgment.

Existing captured-command copy fixtures explicitly admit their captured Add for receipt tests; they remain distinct from actual service delivery. Strict sink failure fixtures remain in the full candidate. Real-device acceptance, hardware/disk durability and visual/performance behavior are not established by these tests. Final CI/actual JUnit and APK metadata belong in this PR and the portable final checkpoint.

## #230 completion criterion

The original finding is late re-add after user removal and unsafe handling of failed move output. Source implementation now covers command admission, copy cancellation, exact output checks, process-owned delivery, stale/replayed command refusal, serial source-removal acknowledgment and preservation-first retry. Before marking engineering complete, verify all tests/compile and source review, resolve any findings, and record the defined preservation/retry contract. Issue stays open for application acceptance; this is not full #179 recovery or every possible cache transaction.

## User release policy, explicitly clarified October8

The user chose to defer both strict dependency verification and the remaining runner/SDK/generated-cache provenance work for this release. This is planned maintenance after shipping current main and integrating the mature M3 Expressive branch, not abandoned work and not security clearance. Existing Actions pins, wrapper/distribution/JDK checks, unsigned inventories, publication guards and restored main protection remain. Current generated checksums are observations, not a trusted publisher baseline. No compromise is demonstrated. Resume the trust plan/advisory dispositions later and review materially changed dependencies after experimental integration. No Stable tag/release is authorized by this disposition.

## Ownership and next step

GPT-6/Codex desktop, exact variant/effort not reported: delivery implementation/tests and self-review. Claude Opus5.5/Claude Code High selected: strict sink implementation, coordinator source review; no independent whole-stack review claim. Allocated Claude session idle at69%five-hour/61%weeklyUSED before optional bounded read-only review; later receipt supersedes. Next: first compile/tests, source-review corrections, actual artifacts, then pending manual acceptance; no app-stack merge before that gate. Save final head/checks and remaining questions durably before quota cutoff.
