# Aggregate memory engineering checkpoint — 2026-10-08

## Scope and user decisions

Stable-main audit continues. #253 broader memory redesign is **required before Stable**, including saved paging and Media3/cache retention; it is not merely UAT or a documentation exercise. User selected complete saved-library queues within budget, explicit refusal and a smaller-selection offer above it. Preserve originals, exact display order, unknown/oversized metadata and all hidden ownership protections. No silent truncation, clipping or eviction of protected data.

#230's defined move-engineering boundary was completed in the preceding checkpoint; later #392 adds a concrete capacity fix. App acceptance stack remains OPEN until UAT; no application merge, Stable release, tag, auto-merge or administrator bypass. Full #179 SD recovery remains separately deferred/unresolved. Reviewed dependency verification and hosted runner/SDK/generated-cache provenance reviews are explicitly deferred for this release, planned after current main ships and mature M3 integration. Native dependencies do not remove those trust risks; existing protections remain.

No phone/device commands this cycle. Historical Pixel/POCO/root access is not standing authorization. Experimental checkout is untouched. Main at inspection: `5a12310982064825a6672765ea95c0e59c5e8efa`; its published app Canary .734 does NOT include the open acceptance stack. Docs-only main runs do not publish APKs.

## Exact-head receipts

Every app PR targets main but includes its predecessors, starting with the preceding #387 acceptance head `75c8046e96b99152a11cb3d4187926a3ffa6a1fb`. Do not squash/merge overlapping PRs blindly. They remain OPEN for acceptance; any landing/integration must refresh main, review the resulting diff and verify its latest head.

| PR / boundary | Exact head | Actions run / Canary | Tests per variant | Artifact |
| --- | --- | --- | --- | --- |
| #389 compact exact owner censuses | ab58f47553c9d162c36153fa2b08939114b32eda | 37742403339 / .745 | 739 | 11535235137 |
| #390 retained startup IDs, one raw row at a time | d0df73a64b3758375424293fca9638dad2b5dee4 | 37743489577 / .746 | 743 | 11534214019 |
| #391 JSON structure budget before DOM | 72ce09c917f831bfd3e4b85a2f0aad450fd5b76d | 37744923024 / .747 | 755 | 11534814769 |
| #392 move receipt admission before copying | ebce29276a850311f18f024a88c1b6fe363cdf8e | 37745196066 / .748 | 759 | 11535379269 |
| #393 streaming Connect saved count | edbf852a3b534c7845acf1e57618fe94670efadc | 37746700331 / .749 | 762 | 11537190558 |
| #394 explicit saved queue budget/single-copy offer | 91cf8540bccbf03d9b08feccf6f8314b6aa12592 | 37748460595 / .750 | 768 | 11537485154 |
| #395 native cursor failure containment | 558a0357567a71283adfb303fe45e491327ef1e1 | 37750435179 / .751 | 770 | 11538151704 |
| #397 move retained-payload and command admission | 2ac45207b5e9e1437e3c091a3f2e1be87a944427 | 37752098846 / .755 | 777 | 11539220105 |
| #398 exact saved-catalog/streaming foundation (UI not wired) | ac5d46cf7172719911f3cf581ee6ff2b35a04253 | 37755735793 / pending | pending | pending |

For .745–.751 and .755, actual downloaded XML reports verify zero failures/errors/skips for both variants, lint zero errors / 46 existing warnings each. Actual Canary APK BUILD.txt head/run/version, SHA256SUMS, unchanged signer/package, non-debuggable flag and three private services verified. Run URL format: https://github.com/averylicious/muon/actions/runs/RUN; artifact adds /artifacts/ARTIFACT. Artifact name `app-debug-FULL_SHA`; ZIP contains app-debug.apk, SHA256SUMS and BUILD.txt. Branch artifacts expire in 14 days and are not Obtainium releases.

.750 APK SHA256: `b9471c315574f36af4d000530a17831470546d5854bdac342222a628ab95c76f`. No local Android compile; Actions first executed tests. Passing source tests do not establish phone playback, dialog UX, measured heap or Binder delivery. #395 native-window fixtures passed in both variants; actual reports and APK identity/hash checks completed. .751 APK SHA256 acf365891caa0cf39956b8e0b5c99d44da2d4e25311806f11a0f105490a54072.

## Attribution and ownership

GPT-6 (Codex desktop; exact variant/effort not exposed) coordinated, source-reviewed, wrote #391/#393/#394/#395/#397/#398, corrected constant-time census lookup and move tests, and integrated the acceptance stack. Those authored portions have author self-review, not independent review. Claude Code runtime explicitly confirmed `claude-opus-5-5`, High: implemented #389/#390/#392 and provided the initial memory map. The corrected [finite plan](../audits/2026-10-08-aggregate-memory-plan.md) rejects unapproved metadata/sort-key clipping and corrects pinned manager-command assumptions. PR descriptions identify roles; independent whole-stack review is not claimed.

Allocated audit Claude session is idle and close to its reported usage boundary; do not assign additional work without checking quota and idle ownership. Do not resume the user's experimental Claude. Quota readings are volatile and must be refreshed from the current account/dashboard, not copied from dated receipts. No subprocess or device session is required for portable recovery.

Persistent branches/worktrees: `codex/resource-census-oct8`, `codex/retained-startup-census-oct8`, `codex/move-receipt-budget-oct8`, `codex/saved-count-oct8`, `codex/saved-queue-budget-oct8`, `codex/retained-cursor-failure-oct8`, `codex/move-metadata-budget-oct8`, `codex/saved-catalog-oct8`; similarly named directories under ~/.codex/worktrees/muon-*. JSON branch/worktree is `codex/json-structure-budget-oct8` / muon-json-structure-budget-oct8. Current documentation branch is codex/aggregate-memory-plan-oct8, upstream unset. Verify live heads, checks and dirty state before writing. The original Claude plan remains intentionally untracked on retained-startup-census; canonical corrected plan is committed with this checkpoint and supersedes it. No recovery depends on that untracked draft.

Main protection was read back this cycle: strict required Build, test and sign plus Branch direction from GitHub Actions, admin enforcement enabled. Recheck before any permitted documentation merge; do not assume protection remains unchanged.

## What remains and where to resume

1. Verify #398's latest-head saved-catalog foundation CI and actual reports/APK. #395/.751 native failure fixtures and #397/.755 request/command budget fixtures passed; no device crash or successful phone Binder delivery is claimed.
2. #398 implements the exact, private no-backup derived catalog foundation and real streaming native-index/cache projection, but is NOT wired to runtime inventory/model/UI. Implement bounded page hydration/LRU and store/model invalidation off main next. Initial proposed page budget 4×50 is not a hidden library cap. Do not alter Media3's private schema, clip titles or silently change Unicode/order semantics. The plan records test/rollback/generation and non-backed-up derived-data requirements.
3. Replace the still-retained STOPPED-task population safely. Pinned manager bulk removal and setStopReason do not automatically reload all omitted rows; a startup-only filtered view cannot be adopted without command/service proofs and preservation tests.
4. Bound full owner/name/cache/native cardinality and remaining live/service command budgets, preserving hidden owners and protected played entries. The move path now has4MiB logical retained-request payload and768KiB single-command admission including4KiB envelope, tested with real Parcel/captured Intent sizes; shared Binder contention remains outside that guarantee. Actual #397/.755 report/APK verification passed at its recorded head. Review live queue admission against existing load budgets.
5. Application UAT remains: saved/Unverified playback, copy/move/remove/refusal, duplicate queue/Undo, scroll/sheet interactions, TalkBack and Bluetooth hardware controls. SD recovery and Pixel lockscreen limitation remain separately documented; this engineering cycle did not perform them.

These are engineering requirements, not a percentage or a claim that the remaining gate is only UAT. Follow [the finite plan](../audits/2026-10-08-aggregate-memory-plan.md) and the per-slice handoffs on their PR branches. Do not close #253 from these preparatory fixes.

Catalog verification chronology: the7785d0e run failed2 fixtures on invalid SQL index names;54cb9c9 corrected them and passed CI. Author review then tightened two-connection/read generation consistency, reentrant operations and escaped/cross-thread callback boundaries. Final head ac5d46cf7172719911f3cf581ee6ff2b35a04253 / run37755735793 is required;11catalog controls are expected from source, not an actual pass until final reports are checked. No further feature writes planned before verifying and closing this evidence boundary.
