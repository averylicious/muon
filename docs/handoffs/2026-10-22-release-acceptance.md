# October22 release-configuration acceptance checkpoint

User requested an early stop to change model settings. All agent-controlled phone work is stopped/restored. Resume from the [maintained #253 state](../audits/issues/253.md), not the full issue timeline. No app changes, merge, Stable tag/publication or experimental integration occurred in this cycle.

## Exact source and ownership

- Cumulative candidate: DRAFT/OPEN [#636](https://github.com/averylicious/muon/pull/636), app/build source `af70c50c226cfafb5320052bf71950a40eef60a2`, including the five-file opt-in implementation `3e609759ef1eedf3e0c3ba19276f700b6f55f188`. Later checkpoint commits change documents only; verify live heads before resuming.
- Main reverified `ec02b64f41996ec4a456c764ed94360675570df1`. Experimental remote `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; original checkout `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c` untouched. Do not reset an owner's checkout to a remembered remote head.
- Coordinator GPT-6 / Codex desktop (exact variant/effort not reported), source/device author self-check. Allocated audit Claude Opus5.5 (`claude-opus-5-5`) / Claude Code, explicitly invoked High; completed read-only native/session and library/artwork closure passes, now idle. Six file-read/search calls finished the latter pass. This is focused review, not an independent audit of every cumulative component or a whole-process memory guarantee.
- Persistent coordinator checkout: `~/.codex/worktrees/muon-stable-storage-opt-in-oct21`, branch `codex/stable-storage-opt-in-oct21`. No other writer, parked code edits or active storage operation. No successor needs local Claude identifiers to resume.

## Actual CI and artifacts

Both runs built exactly `af70c50c226cfafb5320052bf71950a40eef60a2`:

| Run | Actual evidence |
| --- | --- |
| Ordinary [.1323](https://github.com/averylicious/muon/actions/runs/39619334938) | Success, required branch-direction check passed. Actual reports: **1,792 tests each Debug/Release**, zero failures/errors/skips; lint zero errors/66 warnings each. Actual APK package, certificate, non-debuggable flag, exports, BUILD commit/version and SHA256 verified |
| Diagnostic [.1324](https://github.com/averylicious/muon/actions/runs/39784955224) | Success, same actual test/lint totals. Separate debuggable Diagnostic package, certificate, exports, BUILD/version and SHA256 verified |

- Ordinary [Canary artifact12355095673](https://github.com/averylicious/muon/actions/runs/39619334938/artifacts/12355095673): `app-debug-af70c50c226cfafb5320052bf71950a40eef60a2`, version `0.1.0-canary.1323`, SHA256 `105d2f54e781338207578b505fcbeaefa0b3ba9e9aab0203620a3475f3d3f5ec`. **Not installed**; it would replace everyday experimental Canary.
- Actual minified [release artifact12355240699](https://github.com/averylicious/muon/actions/runs/39619334938/artifacts/12355240699): `app-release-af70c50c226cfafb5320052bf71950a40eef60a2`, version0.1.0/code1323, SHA256 `300af762eaf8226aa86c746e59dec658afb6ef3eb226e662a6c707dc476e75be`. Installed fresh into the absent Stable identity for authorized acceptance; **not a published Stable**.
- Separate [Diagnostic artifact12428848315](https://github.com/averylicious/muon/actions/runs/39784955224/artifacts/12428848315): `app-diagnostic-af70c50c226cfafb5320052bf71950a40eef60a2`, version `0.1.0-canary.1324-diagnostic`, SHA256 `6a3fd96a67e53207e5d62d7a95c44ea83ab183db79c19216ea37fbffed95fdc6`.
- No Android build ran locally. Benchmark configuration was source-checked against pinned AGP sources in the previous checkpoint, not built here. Later document-only head checks must be reported separately from these actual APK sources.

## Fresh-ROM device baseline and selected release results

The user renewed rooted POCO testing after a clean flash. Current Android17/API37 root shell works. `run-as` still fails its target-SDK37 SELinux context lookup; no policy or root-manager setting was changed. The flash removed installed Muon apps/private indexes but left SD folders. With explicit approval, the old Diagnostic-only folder was renamed to a separate Android/data sibling. Before/after relative file/type/content proof passed; the regular Muon folder stayed unchanged. Final hashes of both protected old folders also passed after current tests.

The minified release and separate Diagnostic were installed into absent identities, with actual installed APK hashes verified. This is **fresh-install evidence, not an existing-private-data upgrade test**. The pre-flash interrupted experiment cannot be retroactively proved after its private data was erased; keep it historical/incomplete and use the new baseline for future tests.

Selected minified-release acceptance used one generated silent20-second Opus fixture (211,941bytes), served only at device loopback; no user music or public/network configuration was changed:

1. Fresh normal startup kept **legacy mode**. Installing did not select partition mode automatically.
2. Stable opt-in wording and explicit consent appeared. Saving the choice required a full restart; it did not replace live owners.
3. After restart, the original saved row remained readable. Its playback UI started, and saved playback made no extra network audio request. Silent output was not an audible-quality check.
4. Per-copy optimization reached `Replacement verified. Original copy kept.` Stopped proof found the unchanged full original and matching partition replacement SHA256; complete legacy saved-request rows were unchanged. Optimization correctly keeps one visible saved row; an initial test expectation of two visible rows was corrected, not treated as an app bug.
5. Explicit Phone→SD Move completed. Stopped proof checked full request/data/completion fields, fresh target key/UID/native ownership, exact audio/metadata at destination, and retirement of both phone original and replacement payloads.
6. Explicit Delete completed; neither shelf retained the disposable saved row or audio spans.
7. The release connection was backed up and restored **only on the phone**, verified byte-for-byte there. Original media volume restored, app stopped, owned loopback listener/files removed. An initial host-export attempt was automatically rejected; no private connection contents were exported. No active/uncertain Move remains.

These are selected happy-path automation checks, not full release acceptance of every failure/refusal, nonoptimized legacy action, upgrade, Bluetooth or spoken/focus TalkBack path. #401 timing stays explicitly deferred; no speed investigation was reopened.

## #253 finite source closure and interrupted memory case

Focused source checks found no confirmed new blocker in the inspected paths:

- Production partition owners share the six-native-instance admission budget; all three per-shelf pools open through it, and uncertain ownership keeps its permit quarantined. The old pre-opt-in index remains outside that budget by design.
- Final MediaSession player checks every growing overload against the resulting queue (2,048 items/4MiB counted metadata), with app-side refusal before full queue construction and a second final session check. Pure/real-session tests exist; this is logical accounting, not a Binder/process-heap guarantee.
- Production library load charges playlist/occurrence/metadata/artist-membership budgets including old-library fallback. Grouping uses the same artist splitter, deduplicates tracks, clears superseded graphs and checks cancellation.
- Actual artwork LRU charges allocation/key payload plus32KiB minimum, at most384 cache keys in12MiB; two concurrent loads, bounded decodes, no recycle on eviction. Coordinator read the full two actual-cache regression tests; CI ran them. Visible/shared bitmaps, refresh overlap and parsed response peaks remain outside exact logical retained-cache accounting.

Fresh Diagnostic .1324 loaded the initial one-record synthetic case and a complete initial managed heap was privately parsed. The user then requested an early stop **during the wide-load wait**, before wide browsing/replacement verification. This is **not a pass or an app failure**. SIGINT entered cleanup; connection restored to its original absent state, original volume restored, initially denied Diagnostic local-network permission restored, app stopped, owned listener/fixture/heap files removed. No cleanup failures. Private receipts/heaps/optional helpers stay ignored under this checkout's `.local/qa-oct22/`; none uploaded.

Next authorized device task: rerun the **fresh-ID tiny-artwork case** from a new stopped baseline: one→50,000 distinct groups, enough genuinely fresh image URLs to exceed the384-key cache (target600), managed-cache/object observations, then replace with one record and verify old graphs release. One UI automation owner; do not run overlapping UI dumps. This case serves no audio. Preserve the regular/preserved old card folders and restore only owned temporary state. Earlier USB-interrupted attempts remain incomplete, not additional indefinite gates.

## Remaining release boundary

Keep #636 DRAFT/OPEN. Remaining: final tiny-artwork verification and defined actual-RC acceptance (including existing-data upgrade, relevant legacy/failure/recovery cases, identity and interaction checks; user Bluetooth and spoken/focus TalkBack). Source closure does not waive these. No new implementation defect was confirmed this cycle. Do not create work to consume unused quota or reopen approved #401/#207/#179/provenance deferrals. After acceptance, exact-head integration/main publication and an explicitly requested Stable release remain separate steps.
