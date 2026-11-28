# Test-suite cost and maintenance assessment — October 18

## Scope and evidence

User raised concern about continuing growth into thousands of unit tests. User subsequently approved continuing necessary tests for distinct audit contracts and trimming/consolidating after this audit; do not use a rising count as a quality or completion metric. No coverage/check was removed and no workflow scheduling changed by this report. Existing #606/#607 checks and safe device cleanup continue. Additional tests are appropriate where a distinct failure path warrants them; a growing count is not a quality metric.

Evidence: downloaded reports from #606099b15edc0718db9f065577247cdb6029d3f95dc / [ordinary .1224](https://github.com/averylicious/muon/actions/runs/39100765303), report artifact12127265138; classified against that exact source tree. Each variant has211test classes and1,734executions. Source contains1,721@Test methods; SDK parameterization contributes additional executions. Debug and Release repeat the same suite structure; they are not3,468distinct methods.

| Category | Executions per variant | Debug summed class time | Release summed class time |
| --- | ---: | ---: | ---: |
| Plain JVM |544|3.5s|3.5s|
| Robolectric Android simulation|1,190|145.7s|174.7s|
| Total|1,734|149.1s|178.3s|

Rounding can differ by0.1s. Native SQLite is enabled for971of the simulated cases (a subset, not another total). These simulations execute on CI, not the user's phone. Summed XML suite times are not the full job/critical-path duration; compilation, lint, setup, downloads and artifacts have separate costs.

Largest reported class: OwnedPartitionShelfTest140executions,47.6sDebug/82.1sRelease. Other expensive classes include ArtworkRequestIntegrationTest2cases9.5sDebug/6.8sRelease, DownloadMoveCharacterizationTest51cases8.0s/7.1s, CacheMigrationControlTest13cases8.0s/6.2s, and HttpDownloaderBoundaryTest3cases6.3s/6.4s. Case count alone is therefore a poor runtime proxy. Fixture setup, Android/native simulation and intentional timeout/failure coverage need separate review.

## Maintenance concern

Many audit additions exercise storage data-loss boundaries. Distinct root/volume replacement, exact-byte preservation, cancellation, unconfirmed writes and actual unknown-close ownership are important contracts; this observation does not establish every test's value. Repeated fixtures or nearly identical assertions can still impose unnecessary maintenance and CI cost. Do not claim that211classes have been independently audited or that any number of tests proves release readiness.

Review candidates by protected contract, trigger and assertion strength. Consolidate duplicated cases/fixtures only after showing which remaining case preserves the behavior. Replace brittle implementation-detail assertions with contract assertions where practical. Keep actual filesystem/SQLite/Media3 integration at the relevant boundary; a pure fake cannot alone prove actual close or persisted-byte safety. No arbitrary numeric cap or mass deletion.

## Scheduling proposal — not implemented

1. Keep the fast plain-JVM suite on routine application changes.
2. Evaluate a full Android integration suite on one primary variant for routine pushes, plus focused configuration/variant smoke coverage on the other. Both signed APKs, lint and manifest/identity/publication safeguards remain required.
3. Retain full both-variant coverage for main/release candidates and deliberate scheduled/manual validation, and for changes where variant differences need it. Define triggers before changing defaults.
4. Inventory variant-sensitive coverage first. A direct textual scan currently finds BuildConfig/storage timing/partition opt-in/debuggable references in SavedStartupTimingTest and PartitionStorePreparationTest; this is only an initial inventory, NOT proof that all other tests are variant-independent. Production compilation, resources and build flags can differ indirectly.
5. Review wall-clock improvement and escaped-regression risk before adopting reduced routine repetition. Do not bypass branch protection or label unrun checks a pass. Any CI change needs its own reviewed head and accurate handoff.

## Next decision

The user's concern is acknowledged, but no user instruction to delete a specific test or weaken a required check was inferred. Continue safe engineering and meaningful audit tests under the user's renewed approval; retain the focused redundancy/scheduling review as follow-up work. Keep the engineering/acceptance gates separate from test count. Source author/self-check: GPT-6 / Codex desktop; exact variant/effort not reported. No Claude/subagents or independent review claimed.
