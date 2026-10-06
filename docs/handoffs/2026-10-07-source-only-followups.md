# October 7 source-only follow-ups

Baseline main `89bb29acc098d98250b33f062284ea0b48012c96`; complete app parent #370 `97e2fa0b96032374a84c604c1e7ccccf82406338`. This task explicitly excludes automated device QA. Experiment checkout/session unchanged. GPT-6 / Codex desktop, exact variant/effort not reported, implementation/source self-review and integration; not independent review. The separate main-based build-trust branch also adds a strictly scoped public inventory artifact after validation; no dependency version/permission/secret change. No new Claude assignment, last allocated session idle at97% five-hour used/48% weekly, not a verified hard cutoff. Refresh allowances before reuse.

## New reviewable app boundaries (OPEN)

- [#372](https://github.com/averylicious/muon/pull/372), `codex/move-target-extent`, head `481e397597b3c65af41110bb8ca28b76ff07c123`: refuses completed target/trailing spans beyond the expected length in copy and move completion. Two real cache/index controls. [Scoped report](https://github.com/averylicious/muon/blob/481e397597b3c65af41110bb8ca28b76ff07c123/docs/handoffs/2026-10-07-move-target-extent.md).
- [#373](https://github.com/averylicious/muon/pull/373), `codex/saved-metadata-predecode`, head `dee35d0eae5535c7ad024b4e282cae17b02d2767`: includes #372; saved inventory checks encoded16 KiB metadata size before decoding while retaining original bytes/locators/playability. Two new real native-index/cache controls. [Scoped report](https://github.com/averylicious/muon/blob/dee35d0eae5535c7ad024b4e282cae17b02d2767/docs/handoffs/2026-10-07-saved-metadata-predecode.md).

Initial Android704/705 compiled and ran702/704 debug tests respectively, with the same single new fixture failure before assertions: SimpleCache.commitFile refuses a span beyond known length. Corrected prerequisite fixture481e397 unsets length before late append and restores the stale earlier metadata afterward, preserving assertions. Pinned Media3 datasource1.11.0 published source JAR SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`; SimpleCache.commitFile408–416 establishes constraint. No Android compile/test run locally.704/705 are not passes; replacement706/707 receipts must supersede them.

Latest-head CI/artifact verification pending in this draft. Neither app PR may merge around inherited #302/#365/#366/#369/#370 acceptance gates. Most complete previous successful QA build .701 remains an older receipt until new verification; branch builds are Actions artifacts, not Obtainium releases. No app installed this cycle.

## Remaining outcome, not just QA

| Area | Completed source boundary | Engineering still needed | User acceptance still needed |
| --- | --- | --- | --- |
| #230 | Extent refusal complements byte comparison, generation/removal guard, tracked move receipt and failure feedback | Explicit ownership of failed partial-copy/new spans and late writers; non-destructive byte accounting and cleanup/retry. Fresh/staging namespace is a design option, not an implemented atomic transaction. Never blanket-delete retained keys. | Normal move behavior on the complete candidate; physical loss/recovery remains separately deferred. |
| #253 | Incoming per-item/library-load caps, one saved ownership census and before-decode saved-tag cap | Aggregate saved/raw/index/DOM/decoded collection and shared IPC/native budget design, sorting/other censuses and controlled measurement. Per-record limit is not whole-process heap. | Representative legitimate large libraries and saved copies; no uncontrolled OOM fixtures on phones. |
| Build/dependency trust | [Focused disposition](../audits/2026-10-07-build-trust-disposition.md): live repo guards verified; linked Kotlin advisory path narrowed to unconfigured KAPT incremental cache; unsigned public inventory JSON retained after validation | Reviewed dependency verification/locking baseline and task coverage; SDK/generated/cache authentication policy; remaining advisory callers/compatible upgrades. Phone QA cannot establish any of these. | Explicit release-time residual-risk disposition if incomplete; not implied by CI or this report. |

The concrete next engineering slices are: (1) choose a prospective move-write ownership/staging contract and test failed/cancelled/restarted publication without touching legacy bytes; (2) define a compatible aggregate saved collection policy (paging/lazy projection versus hard refusal) preserving offline access and removal; (3) build an unsigned dependency verification candidate, review artifact/key trust separately before enforcing it on signed jobs. These are not marked implemented or silently waived as release risks.

## Tracking correction and broader gates

GitHub marks #179 CLOSED at2026-10-06T10:06:36Z. User deferred full SD recovery from current Stable gate; it remains technically unresolved. Earlier October7 checkpoint wording “open” was inaccurate. Do not use the closed tracker as proof of recovery; current #181/this report preserve the separate follow-up. No issue reopened or another client blamed.

#213 saved/live contract, hardware Bluetooth/headset/audio focus, TalkBack, fresh API37 discovery and library/queue interaction acceptance remain. Pixel lockscreen deferred. Source audit of main does not audit/promote the Expressive branch; Stable publication still requires explicit request. Main .678 remains last application publication; subsequent main docs-only runs produce no APK.

Owned source worktrees are distinct and committed/pushed at the heads above; no other agent launched. Final run evidence, clean-state/usage and report-merge receipts must be recorded on this document PR and #181/#40 before yielding. No raw session logs, databases, audio, tokens or signing material uploaded.
