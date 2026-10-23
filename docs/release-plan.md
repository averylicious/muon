# Release sequence and current Stable closure

Agreed with the user on 2026-10-21. This names release generations and fixes the current finish line; it does not authorize a Stable tag/publication, change app identities, or waive an existing release requirement. Read the latest [coordinator checkpoint](coordinator-handoff.md) for live execution state.

## One published Stable, two development generations

| Name | Meaning | Ownership |
| --- | --- | --- |
| Published Stable | The version currently distributed through Obtainium | Release channel; explicit user approval for a new Stable |
| Upcoming Stable candidate | Current main-track audit changes, before release | Astra/Sol audit track and user acceptance |
| M3E experiment | The separate Material 3 Expressive experiment, potentially the following release | User and their experimental Claude session |

`main` is an integration branch, not a claim that every commit is already released or accepted. RC means a frozen release candidate, not an Android build type. Workstream names, Canary run numbers, RC labels and explicitly chosen Stable major.minor.patch versions remain separate. Do not rename tags or choose a release version merely to adopt these labels.

The sequence is:

1. Finish the current **locked** audit scope and integrate its reviewed fixes. Freeze an exact RC source commit and build configuration.
2. Run the defined acceptance checks on the RC. Fix regressions against this scope and repeat affected checks; do not reopen a general audit at each passing build.
3. Publish the upcoming Stable only after its gates pass and the user explicitly requests the release.
4. Integrate that released main baseline into M3E using the separate forward-sync procedure. Its owner lands the tested integration at a clean boundary.
5. Continue M3E experimentation alongside maintenance of the published Stable. Maintenance is not an indefinitely open release gate for the version already shipped.
6. When the user chooses an M3E feature freeze, audit the **changes since the released baseline**, plus shared storage, playback, dependency and release interfaces affected by those changes. Reuse valid earlier evidence; do not automatically repeat the entire audit from zero.
7. Prepare the next RC and Stable under the same acceptance and explicit-publication rules. A future M3E promotion needs its own integration/policy review; the current alpha-ancestry guard must not be bypassed to merge it.

A reproducible security, privacy, data-loss or crash defect, or a regression against the frozen acceptance criteria, may interrupt a frozen RC. Record the actual defect, why it blocks, affected acceptance checks and revised candidate. Other newly discovered polish, speculative hardening and performance investigations go to the maintenance backlog unless the user changes the scope.

## Keep everyday Canary separate from audit tools

Current identities are preserved:

- Stable: `dev.avery.muon.release`.
- Canary: `dev.avery.muon`. Main-track and M3E Canary APKs share this identity; installing either replaces the other and shares that app's data.
- Muon Diagnostic: `dev.avery.muon.diagnostic`, the separate branch-only diagnostic app. It is debuggable, carries its visible warning and is never published to the normal feed.

Use Diagnostic for agent-controlled disposable audit fixtures when authorized, preserving the user's everyday Canary. Do not recommend a main-track Canary installation as if it could coexist with the experimental Canary. Ask which track the user wants to keep before proposing an ordinary Canary replacement. A newer run may be needed when switching because Android prevents normal version-code downgrades.

Diagnostic evidence does **not** replace acceptance on the actual non-debuggable release configuration: debug status, optimization, feature flags and existing-data upgrade behavior may differ. Installing a release-identity RC would update the existing Stable app; identify that explicitly and arrange the user's acceptance step before doing it. No third everyday app, signing change, new Obtainium feed or package rename is introduced here.

## Frozen source inventory, October 21

Verified main: `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`. Current cumulative candidate: [#634](https://github.com/averylicious/muon/pull/634), `feaa0636c5c034eaa5070d163f2400d856c33bee`. Successors are limited to closing the gates below or confirmed release-blocking regressions. Neither branch is a released Stable merely because CI passes.

There are 25 open issues in this snapshot: two umbrellas (#40/#181), one deferred performance item (#83), and **22 defect/workstream issues**. Those 22 consist of #253 and #401 plus 20 earlier findings. The closure inventory found implementation in the cumulative candidate for 19 of those earlier findings and partial implementation for #207. That is an implementation/ancestry inventory with focused source checks, not 19 new independent reviews or acceptance passes. The 275 open PRs are mostly accumulated component work; they are not 275 independent remaining defects.

The 20 earlier findings are #278, #251, #249, #247, #244, #243, #241, #239, #230, #228, #226, #225, #223, #220, #218, #216, #213, #211, #208 and #207. Keep actual fix, acceptance and merge states separate. In particular, #230 and #213 still need data-preservation/identity acceptance, while #207's in-app artwork fix does not fix the notification artwork cache.

## Finite remaining gates

| Gate | Exit condition | Current state |
| --- | --- | --- |
| 1. Candidate and scope | Exact candidate/configuration and this issue list recorded; later findings triaged rather than automatically added | Current #636 app/build source af70c50c; checkpoint carries exact CI/artifacts. Final RC not yet frozen |
| 2. #253 memory redesign | Close the existing production retention/admission cases; record a finite combined library/queue/native-cache/artwork matrix, refusal/recovery behavior and evidence limitations | Broad redesign implemented on open #636; focused source closure and selected minified-release storage checks completed. Final tiny-artwork/library replacement and selected unoptimized legacy/queue checks passed on resume; defined user/RC acceptance remains |
| 3. #401 startup/Move latency | Measure the guarded path and obtain a specific release disposition | Selected .1318 READY 7.778s and 3 MiB Card→Phone Move 265.163s preserved data. User explicitly accepted these delays for this release on October21; further performance work deferred, issue remains unresolved |
| 4. Known partial #207 | Fix notification artwork identity, or obtain an explicit release disposition and retain an accurately described follow-up | User explicitly deferred the notification-only stale-cover case on October21. Keep #207 open; in-app implementation still needs landing/acceptance, notification follow-up is not fixed |
| 5. Production configuration | Review the actual Stable route, opt-in/migration/recovery controls, legacy full Delete/Move support, and non-debuggable release behavior | Unmerged #636 enables explicit Stable opt-in (benchmark disabled). Actual minified fresh-install opt-in/restart/original/optimization/Move/Delete/queue-refusal checks passed; selected unoptimized legacy actions passed on Diagnostic; installing alone still uses legacy mode. This does not claim pre-opt-in native-index bounds or full RC acceptance |
| 6. RC acceptance | Final-head CI/reports/APK identity checks plus approved compatibility/storage/identity, queue/library/sheet, discovery/layout, Bluetooth and spoken/focus TalkBack results; record every untested case | Selected minified storage/queue, Diagnostic legacy/memory and approved controlled-compatibility checks passed. User has no old Stable data; real-user upgrade explicitly unperformed. Two user acceptance groups remain: interactions/identity feedback and hardware/accessibility |
| 7. Integrate and release | Merge only the reviewed current head after its gates; verify main Canary publication; publish Stable only on explicit request | App stack open; no release authorized by this document |
| 8. Forward integration | Separate tested main-to-M3E integration, landed by the experimental owner, then the next development cycle | Experiment checkout and branch remain untouched |

After the October21 decisions, the current engineering/verification gate is **#253 plus production promotion**, followed by defined acceptance/integration steps. #401 performance and #207's notification-only case are explicitly deferred unresolved follow-ups, not current blockers. No reliable hours-to-finish or audited-percentage figure follows from the number of PRs. Update this ledger by closing concrete gates rather than expanding an unbounded “make Stable stable” task.

Already agreed deferrals stay deferred: remaining measured startup/Move performance (#401), notification-only stale artwork after track-ID reuse (#207), deeper SD eject/live-process/restart recovery (#179), and dependency verification/hosted tool/cache provenance. They remain unresolved maintenance work, not security guarantees. Do not revive them as current release blockers without a new defect or user decision. Other previously approved deferrals remain recorded in their issue/checkpoint.

After the cumulative candidate lands, close component PRs only after checking their actual heads are contained in main, or their behavior has been reviewed as superseded. Non-ancestor history alone is not proof a fix is missing: #451's read-ahead implementation is present and wired in #634 despite its original PR head not being an ancestor. Do not blindly merge every old PR, close unrelated reports, or reimplement an already integrated fix.

## Attribution and evidence limits

Prepared by GPT-6 / Codex desktop, coordinator/source self-check (exact variant and effort not reported). Claude Opus 5.5 / Claude Code, explicitly invoked at High effort, supplied a read-only closure inventory with ancestry checks and selected source inspection; it did not implement changes or independently review every component. The coordinator verified live main/experimental refs, candidate source flags and read-ahead wiring, and corrected recommendations that would otherwise bypass the user's required memory redesign. CI/device/acceptance receipts belong in the checkpoint; this plan itself changes no app behavior.

October22 clarification: the user has no existing Stable data and approved controlled compatibility instead of the unavailable real-user upgrade UAT. Published v0.1.0 is streaming-only; it has no saved-copy index to migrate. Exact APK identity/signing/version and source-derived persisted-connection checks, plus the separate legacy-storage evidence, are recorded in the [current checkpoint](handoffs/2026-10-22-release-acceptance.md). This replaces that criterion, not the remaining acceptance or explicit-release requirement.

## Post-release Material3 Beta experiment

User decision, 2026-10-23: after the current audited RC ships, evaluate Material3 1.5.0 Beta with Claude on the separate M3E experimental track, building on the earlier alpha exploration. Keep the current RC on stable Material3 1.4.0; no dependency/BOM upgrade is authorized for this release.

The [official AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) listed 1.4.0 Stable and 1.5.0-beta02 on 2026-10-23. Treat this as dated research, not a permanent pin: recheck the available Beta, Compose/BOM/Kotlin/SDK requirements and migration notes when starting the experiment. Use the normal isolated experiment and delta-review/promotion procedure, retaining app identities and update paths. Beta exploration is post-release work, not another gate for this RC.

The user approved the Saved copies / On this device frontend direction with spacing refinements and kept the current playback-cache/artwork backend behavior; automatic artwork fetching for played copies is not part of that redesign. Functional normalization and deletion fixes remain independently required.
