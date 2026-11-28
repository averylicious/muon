# Atomic empty setup publication — 2026-10-13

## Ownership and scope

GPT-6 / Codex desktop implementation and source self-check; exact variant/effort not reported. No independent review, Claude, phones or subagents. Branch codex/partition-atomic-setup-oct13 inherits #557 14648348b2f403c15473e39f944c56e62426d358 and main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. Experiment untouched. Partition activation remains false; cumulative app PRs remain open for UAT.

## Recovery behavior

Previously Fresh setup exposed a final folder before all six journals existed. A process stop could leave a Preparing receipt plus a partial published root that strict Resume could not finish. Fresh setup now registers an actual PartitionBootstrapOwner before allocation, creates all six actual journals in a same-parent private temporary directory, closes them, verifies the exact file set, forces the directory, atomically moves the complete folder to its absent final name, then forces the parent. The normal shelf opens those already-complete journals with create=false. No native cache, saved audio, source deletion or manager starts during bootstrap. The app's one writer/process admission and storage barrier provide exclusion; filesystem observers are not a general lock against privileged external writers.

Unknown actual SQL/directory/channel/rename completion retains the actual bootstrap owner and quarantines its barrier. No retry, cleanup, deletion or repair occurs in that process. If publication completed but its acknowledgement failed, a new explicit review can validate the complete final root. If nothing was published, renewed consent uses a new temporary attempt and preserves the prior incomplete folder. At most four abandoned attempts are tolerated per target; beyond that, protected diagnostic/operator review is required. No staging folder is adopted as authoritative or silently discarded.

Append-only ReviewedSetup startup mode is granted only by explicit review of a Preparing receipt. It reuses an existing published root through strict Resume; it permits creation of a complete staged root only where the final root is absent. Normal Ready remains strictly Resume and cannot recreate a missing published root. Existing Fresh/Resume enum ordinals and startup receipt version are preserved; older builds refuse the new ordinal instead of downgrading it. Existing published roots with missing/unknown journals still refuse without repair. First opt-in still requires Fresh for every selected root, and all original Delete/Move support remains required before activation.

## Verification and limitations

Local diff/preflight; Android Actions first compile pending. New actual SQLite/filesystem tests cover closed six-journal publication, preserved partial attempts, finite attempt refusal, rename-then-throw, unknown actual SQL/directory close retention, and app composition with a published phone root plus unpublished card root after explicit review. Existing full lifecycle/journal/Delete/Move tests run on the new Fresh builder in CI.

These are synchronous JVM failure injections, not Android filesystem power-cut or device-performance evidence. Atomic directory move must be supported on the actual selected filesystem; failure refuses and preserves copies rather than falling back to non-atomic copying. Device/card acceptance stays pending. POCO will be needed after the production candidate is enabled for lifecycle/storage acceptance, not for this source boundary.

## Next engineering

Verify current CI heads and composed failure behavior before activation. Audit the global Media3 database helper's downgrade behavior before opening it; pinned StandaloneDatabaseProvider sources show onDowngrade can wipe feature tables, while existing guards currently run after helper allocation. Require a bounded read-only header/version guard before helper creation, without forcing a whole native index load. No Stable publication.
