# October 9: saved reads and durable new-save sealing checkpoint

This is a portable continuation receipt for the main audit, not a production migration switch or Stable approval. Read AGENTS.md, STATE.md and the [production integration map](../audits/2026-10-09-partitioned-production-plan.md). Exact PR heads and run outcomes below supersede pending wording in the source-slice handoffs; refresh them before takeover.

## Verified application stack

All three application PRs remain OPEN for inherited application acceptance. They are cumulative successors, not independent changes to squash separately. #446 contains #445, #444 and the complete earlier #441 stack plus main `b036d91811be3c6560d55dd6f1a948d248253bc8`.

| PR / boundary | Exact application head | Android run / report receipt | Canary artifact |
| --- | --- | --- | --- |
| [#444](https://github.com/averylicious/muon/pull/444): production saved-read boundary, legacy selected | `15dad7156f4039dd28ac8cde611f4a17e819e829` | [.851 /37915731194](https://github.com/averylicious/muon/actions/runs/37915731194): 1,052 tests each variant | [Canary .851](https://github.com/averylicious/muon/actions/runs/37915731194/artifacts/11609284192) |
| [#445](https://github.com/averylicious/muon/pull/445): prepared published-Ready reader | `ef0e4450006e9e0b725781f23bac498470a68a34` | [.853 /37917801704](https://github.com/averylicious/muon/actions/runs/37917801704): 1,059 tests each variant | [Canary .853](https://github.com/averylicious/muon/actions/runs/37917801704/artifacts/11610197645) |
| [#446](https://github.com/averylicious/muon/pull/446): prepared durable new-save completion | `c93e6451f755629c1cc99ef7c227596970d8dc4c` | [.855 /37919157107](https://github.com/averylicious/muon/actions/runs/37919157107): 1,068 tests each variant | [Canary .855](https://github.com/averylicious/muon/actions/runs/37919157107/artifacts/11610274370) |

Downloaded final receipts verified actual XML reports for both variants (zero failures/errors/skips; lint zero errors, 64 warnings each), actual BUILD.txt full commit/version/run, matching SHA256SUMS, original public signer, Canary package, non-debuggable identity and three private services. APK artifacts update existing Canary, preserve data, expire after 14 days and are not Obtainium releases. No Stable release/tag or new application publication occurred here. All final Android/Branch direction checks passed; publication skipped. #444 also had a successful exact-head dependency inventory; no separate inventory was attached to the corrected #445/#446 pushes.

Verified APK hashes: .851 `25e999dbbb71d42f9d29603e17b793bf7c95fffce6a2087d5e621974a721d266`; .853 `53e41c59c9a1888e60ab8ff8b39bc0d54da05e1da2b799fd2395610a97357f5f`. .855 `423994a9850a8edd430476bd2ef92bdb67700d1fd89b26e0bd1ebc540ed0150c`.

## Source behavior and corrected CI failures

- [SavedAudio boundary](2026-10-09-saved-audio-boundary.md): saved inventory/count/per-key reads accept a backend interface. The app still constructs LegacySavedAudio and legacy native caches/managers. Source factories preserve explicitly injected fixture readers; availability trailing-lambda compatibility is retained. This does not bound the legacy full index.
- [Published reader](2026-10-09-published-audio.md): prepared read-only Ready routes use bounded journal pages, pinned owned native instances and exact publication rechecks; route/volume loss is sticky for that reader. No silent legacy/live fallback. It is not selected by app services.
- [New-save sealing](2026-10-09-save-sealing.md): prepared default-off downloader mode requires exact new-save ownership, full declared coverage, known I/O closure and sole-writer durable native retirement before returning to the actual DownloadManager. A competing reader refuses completion without force-closing/quarantining it. Unknown close retains bounded native ownership and refuses later admission. Closed is not migration Ready, remote-audio authentication or authority to delete a source.

.852 at #445's initial head failed an inherited priority test after compilation. Corrected an added catalog guard: a later reservation must not invalidate or replace an older verified migration Ready publication. The existing contract/test was retained, and .853 passed at the corrected head. Do not reintroduce catalog equality as migration Ready authority; new-save admission has its separate exact-allocation contract.

.854 at #446's initial `66d05b389b949e18206a8e9eeafb2e72e77f0cf0` compiled but failed one debug test: the injected journal failure occurred in a progress callback, before final lifecycle validation. That verified pre-seal refusal, not unknown native close. Corrected the injection to fail the actual owned native release after pre-seal checks, keeping production safeguards and preservation assertions. Corrected .855 passed both variants, including the actual unknown-close failure control. [Final PR receipt](https://github.com/averylicious/muon/pull/446#issuecomment-6079386454).

## Finite remaining Stable gate

| Area | Engineering status / next evidence |
| --- | --- |
| #230 legacy move/removal | Defined engineering scope complete, application UAT pending. New backend still owes equivalent pre-manager destructive admission and exact record/cover/source/destination preservation. A refused Downloader.remove does not stop Media3 deleting its record. |
| #253 native-cache redesign | REQUIRED: production app-level coordinator/barrier, exact new-save allocation/request/service command/completion record/cover ownership; mixed legacy/Ready/new-save playback and inventory routing; played/download/move/removal/source/eviction integration. Prepared components remain disabled. |
| #253 migration/recovery | REQUIRED: bounded worker, temporary-space admission, deadline/cancel/progress, interrupted handover/restart/failure recovery and explicit opt-in controls. Preserve legacy and every uncertain/original copy; verification is not automatic deletion permission. |
| Legacy import peak | REQUIRED investigation/disposition: public Media3 legacy Cache initialization loads its full index before per-resource extraction. A bounded partition destination does not solve that transition peak. Do not claim fully bounded startup/heap without evidence. |
| #401 saved-card startup | Cause/fix unresolved. Existing timing observations are not performance proof; a focused device comparison needs fresh user authorization. |
| Application acceptance | Pending hardware Bluetooth/headset, notifications and library/queue/Undo/scroll/sheet and full spoken/focus TalkBack acceptance. Controlled JVM/CI checks do not substitute for these. |
| Full #179 recovery / dependency provenance | Explicitly deferred by user to later maintenance, unresolved rather than completed. Keep adopted protections and preservation checks; no reopening by assumption. |

## Ownership, quota and next slice

Root GPT-6 / Codex desktop implemented and source-self-checked these slices; exact model variant/effort is not exposed. Author self-check is not independent review. Inherited Claude Opus5.5 / Claude Code / High #434 contribution stays attributed on the source stack. No new Claude work here: allocated audit session was verified idle; its last runtime reading was dated 78% five-hour /92% weekly USED, not assumed reset. Latest root quota read: 64% weekly USED, no five-hour reading exposed; refresh rather than estimating another account’s quota.

One writer per isolated persistent branch/worktree; all source changes committed/pushed. Root coordinator yields idle after exact-head receipts; verify no active successor before resuming. Experimental `claude/m3-expressive-alpha` and the user's checkout/session remain untouched. Fresh experiment remote was `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; refresh before any separate integration. Main stays protected; no bypass/auto-merge or overlapping app merges.

Next bounded slice: implement the production new-save command owner tying one exact reservation/journal/request to manager admission and retained record/cover ownership, with actual DownloadManager/index failure controls. First refresh current heads/checks and this receipt, preserve cumulative dependencies, and leave the backend switch off until mixed routes and failure/recovery controls are complete. Do not jump to a whole migration UI rewrite.

Focused entry points for that next owner, inspected on #446: `OfflineStore.add` currently creates bounded new-save requests and `SaveDelivery` tokens before building service intents; `OfflineStore.deliverCommand` / `admitCommand` gate actual delivery before Media3; `MuonDownloadService.onStartCommand` and the card service route through that gate. `OfflineStore.shelf` still constructs the legacy manager/index/cache. Extend these existing ownership boundaries rather than creating a parallel intent path or treating downloader refusal as pre-manager admission. Preserve request-budget checks, stale-token refusal, card binding and move/removal exclusion. No production changes to those paths were made in this slice.

No POCO/ADB or other device commands this cycle. Notify the user once actual opt-in/recovery routes produce a candidate for disposable private-record, cancel/restart, availability/card identity and original-byte preservation checks, explaining the exact build and checks. Ask for renewed rooted-device authorization then; no phone is needed for disabled helpers. User hardware/accessibility UAT remains separate.
