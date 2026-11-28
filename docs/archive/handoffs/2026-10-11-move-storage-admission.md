# Move I/O admission checkpoint — 2026-10-11

Solo GPT-6 / Codex desktop implementation; exact variant/effort not reported. No Claude or device use. Source PR is an open successor to #489; partition mode remains disabled. App behavior requires acceptance testing.

The actual Store now supplies its shared SavedStorageBarrier to MoveCopyLifetime and MoveByteComparison. Each operation acquires before source/target construction and owns admission through both real I/O cleanup paths, including destination sink close after upstream close. This closes the gap where upstream admission drained before destination close. No exclusive mutation authority, source deletion or new migration UI is added.

Unknown real close, typed partial ownership, or an unreturned factory retains the bounded entry, actual returned children, exact in-flight factory and failure plus its permit; no retry replaces those participants. Known cancellation/mismatch/read failure closes returned children and drains the operation. A throwing factory alone cannot prove no handle was allocated: prior optimistic factory-failure fixtures now expect conservative retention. Original bytes remain unchanged.

Seven additional controlled tests use actual files/native caches: destination close after upstream drain, exclusive admission before any allocation, unreturned descriptor, both comparison closes, cancellation, unknown close and comparison refusal. Android Actions is the first compile; record exact final head/run/artifact and outcomes on the PR. No phone verification is claimed.

Before another slice: inspect current heads/checks and source failures. Still required: remaining writer/census/removal/native lifecycle coverage, production partition selection/startup binding, explicit opt-in/recovery controls and integration/acceptance. Existing #486/#487 are CI-verified; #488/#489 received fixes for stopped-session construction refusal and quarantined-pool assertion; inspect replacement runs. Do not merge the application acceptance stack around pending QA.
