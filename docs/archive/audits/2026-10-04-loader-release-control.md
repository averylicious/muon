# #179 Loader release control — 2026-10-04

Base main `627a45fd796218fb968ef842e2ffdf004c8d2fa8`. GPT-6 / Codex desktop, effort not reported: test implementation and self-review. Follows the [pinned playback-reader source investigation](2026-10-04-playback-reader-release.md).

`LoaderReleaseControlTest` uses the real Media3 Loader and a test-owned serial ReleasableExecutor whose release calls ExecutorService.shutdown, matching the default Loader's executor contract. The Loadable is synthetic, held through interruption until a test-owned latch opens. No production code, network, phone, cache or user media is touched.

Two controls distinguish acknowledgments:

- An already-running load: release returns, cancellation is delivered, isLoading becomes false and executor shutdown is requested while load() remains held. The release callback cannot run until the load's finally completes. The test explicitly observes the interrupt before opening the gate.
- A submitted but executor-queued load: another test-owned task holds the serial executor. Release cancels the queued load, delivers released cancellation, and queues its callback. After the blocker exits, the canceled task never enters load(); the release callback still completes. This covers queued cancellation, not a retry delayed on an Android Handler.

Both fixtures release their gates even after assertion failures and require callback completion and executor termination with bounded waits. No dependent resource is released early. The worker blockers intentionally have no autonomous timeout: test assertions/cleanup own the release, so a slow CI scheduler cannot silently unblock the premise.

Claude Opus 5.5 / Claude Code, High requested: independent read-only test-structure review found no blockers and suggested waiting for worker termination even when the callback wait fails; that cleanup improvement is included. Claude could not access the external pinned source JAR during that review, so the coordinator retains responsibility for the direct pinned-source comparison. No runtime effort setting was exposed.

Validation: PR #309 head ce08eeb4d05547650155f3278972f9e582217fb6 passed [Android535](https://github.com/averylicious/muon/actions/runs/37147692034). Downloaded test XML verifies452 tests per variant, zero failures/errors/skips and both new controls executed. Debug BUILD.txt verifies that head/run535/Canary.535. This was the first real compile; no local Android build. Main merged it as19313fcb7fde291c32e7463c1891e22e77178118. Claude later confirmed the pinned source citations from local public JARs; the earlier access limitation no longer applies to that follow-up.

## Limits and next boundary

Passing these controls would establish the pinned Loader's behavior in this harness. It would not reproduce a real Muon cache/file/network read remaining stuck, establish a player-wide drain, or fix #179. The callback is later than preceding work on this serial executor; it is not proof that every source, external callback or separately admitted operation has drained.

Next: characterize actual Muon OfflineDataSource/CacheDataSource source-construction, failed-open and completed-close ownership on disposable cache data before designing production generation leases. Keep downloader/service/mover/copier/callback admission and retained catalog recovery separate. Timeout must grant no capture, cache release or recovery permission. Do not release an absent-card cache. App-fix PRs remain open for user acceptance; no Stable release is authorized.
