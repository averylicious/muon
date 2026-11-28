# #179 catalog commit receipt boundary — 2026-10-03

Inspected main `c357694c64fde20762e7a88de44c5ab03c8feecb`. GPT-6 / Codex desktop, effort not reported: implementation and source/self-review. Test/report only; no app behavior, dependencies, phone, network, user files or existing downloads changed. Production recovery remains unresolved.

## Evidence and narrow question

The merged [sidecar control](2026-10-02-cache-snapshot-feasibility.md) demonstrates a healthy test-only catalog round trip and explicit `failWrite` rollback. Those do not prove failure reporting or durability. Before a recovery caller treats a write as authoritative, does `AtomicFile.finishWrite` return prove it published the candidate?

Read the actual Android SDK source archive `source-37.0_r02.zip`, SHA-256 `f5274d87b59d3aaa53caaa018b4aa807046d58ecd755a717a6a100c714bdd1b9`, `android/util/AtomicFile.java`:

- `finishWrite` 170–183 logs false sync and close exceptions, then calls `rename` without returning a success receipt.
- `rename` 336–350 logs failed deletion of a target directory and failed rename; neither condition is thrown to the caller.

This source establishes a possible normal return despite unsuccessful publication. It does not establish that Muon currently loses a catalog: no such production catalog exists. A sync failure is not injected or claimed here.

## One new negative control

`AtomicCatalogCommitReceiptTest.finishWriteCanReturnWithoutPublishingAtAnInvalidDestination` uses the actual `android.util.AtomicFile` API under the existing Robolectric SDK34 configuration and a disposable nonempty destination directory. The sentinel ensures deletion/replacement cannot succeed without relying on permissions or storage exhaustion. It writes candidate bytes, calls `finishWrite`, expects a normal return, then checks the destination is still a directory, retained sentinel bytes are unchanged, and `readFully` raises `IOException`. Teardown uses `failWrite` to discard any uncommitted candidate.

No private filename is asserted and no framework shadow is customized. SDK37 published-source behavior and Robolectric SDK34 execution are separate evidence; this is not device validation or a power-loss simulation. CI is the first compile/execution; result pending at drafting.

## Recovery implication

A future adoption boundary must explicitly validate the committed, versioned catalog and its contents before treating a write as success, and fail closed on invalid/missing readback. Readback by itself still does **not** prove fsync durability, parent-directory persistence, process-death atomicity, cache capture completeness or a safe service/generation drain. Do not release an absent cache or rekey/delete retained audio based on a nominal `finishWrite` return.

No phone QA needed for this test-only PR. Existing #289/#290 app acceptance gates remain unchanged. Final head, CI and merge evidence belong on the PR and next checkpoint.
