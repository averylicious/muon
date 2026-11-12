# Post-RC audit continuation

Updated 2026-11-12. Continue the broader source and CI audit within the user's current usage and device instructions. This cycle stops at 50% weekly usage remaining. User decisions and unresolved questions are recorded below.

## Scope and ownership

GPT-6 / Codex desktop owns the isolated topic, integration and checkpoint worktrees. Exact model variant and effort were not reported. The user and their Claude session own the experiment. Device acceptance remains pending.

Refreshed local main to `046db9ad790a581f64f0e1d5bee95ea353460c88` and experiment to `b36b4b7ac293d3b5952b8fd5ffef997755630c80`. The main changes since #762 are documentation only. The experimental branch has RC2 and #769 predictive Back changes. [Experimental receipts and QA](2026-11-10-m3e-rc2.md). #679 still pins RC1 `6b1a767c180a8b9e531bf1468c9e72e348635b74`.

| Worktree / branch | Source and checks |
| --- | --- |
| `/home/avery/.codex/worktrees/artwork-key-nov12/muon`, `codex/artwork-key-nov12` | [#770](https://github.com/averylicious/muon/pull/770), `6659712772b756d78e78aaf1357b2a7864da6899`, [Android .1693](https://github.com/averylicious/muon/actions/runs/43192856537) passed. |
| `/home/avery/.codex/worktrees/muon-post-rc-acceptance-oct25`, `codex/post-rc-acceptance-oct25` | [#701](https://github.com/averylicious/muon/pull/701), `d9db04bf0c7a2ffc96ea401fa03317a89a4341d3`, 40 topic ancestors. [Android .1694](https://github.com/averylicious/muon/actions/runs/43193754370) passed. |
| `/home/avery/.codex/worktrees/settings-move-count-oct30/muon`, `codex/settings-move-count-oct30` | #766 at `87889a8f8e388ff82751eac191f63f87019fc32e` remains available for QA. [Android .1680](https://github.com/averylicious/muon/actions/runs/40929064542) passed 1,824 tests per variant. |
| `/home/avery/.codex/worktrees/muon-post-rc-state-oct25`, `codex/audit-nov12-checkpoint` | Documentation checkpoint. Prior #762 merged at `ff242defd1b9d15490a11b0279dd1cc9a01fb708`. |

Earlier topic heads and individual checks are in the combined input inventory and issue snapshots. The two new runtime drafts remain open for later acceptance.

## Verified boundary and current checks

**Previous37-topic0fe9d08b7d05200bb157d6fdc12a33cdcd596a1c / [Android .1675](https://github.com/averylicious/muon/actions/runs/40924150068)** passed downloaded2,007cases/variant, zero failures/errors/skips; lint0errors/62warnings plus3hints. All seven NSD lifecycle methods, oversized ID3/M4A negative controls, finite median, cached native MP4 cancellation, HEIC cutover/seek and uncertain cleanup methods ran in both. Report ZIP SHA256a7c6be60323541eb4f87f7886544a69587f222422671a5b394c3f4c651dfefde matched API. Actual release BUILD/source/checksum/pinned signer/identity/non-debuggable/compiled-export/profile checks passed offline; APK657f3fc9456fb8570c8afa33b3fdaf3d491fecddda4bf2bcad072907fb85cf9d, uninstalled. Earlier d146/.1662 and db1/.1666 receipts are historical, not current-head proof.

**Previous 39-topic head80348c145d15e1acd4f1e6e4a81b1057a5f28970 / [Android .1681](https://github.com/averylicious/muon/actions/runs/40931457152) passed.** Downloaded2,010cases/variant, zero failures/errors/skips; lint0errors/62warnings plus3hints. All20 selected lifecycle, metadata, median, native cancellation/cutover/seek, cleanup, Settings-count and lyric methods ran in both variants. Report ZIP SHA2566b4ab427d1aa01f53685d3bf15aa2665018b5ff353625e77ac79e8a3c0563550 matched API. Actual release ZIP SHA25650450d8631012018ad8c762774b934caad50ec5a8ba8818ccc8668f3a112a75a matched API; BUILD/source/checksum/pinned signer/identity/non-debuggable/compiled-export/profile-header gates passed offline. APK SHA256b708f4fc1869009344c183cb5897c1586f14ec708bc1b8f83db930c9cbd7908d, uninstalled. [Canary0.1.0-canary.1681 artifact](https://github.com/averylicious/muon/actions/runs/40931457152/artifacts/12923806964), app-debug-80348c145d15e1acd4f1e6e4a81b1057a5f28970. Static artifact/report inspection is not decoding, performance, whole-heap or device acceptance.

LyricsScreen matches standalonea3e2 exactly; SettingsScreen/count/test code matches8788 exactly. Only issue-index rows#757/#764 needed documentation merge resolution; runtime Settings merge was clean. All39 exact input ancestors remain. Preserve queue adapters, raw-PCM/guarded-renderer/container/timed policies, VIDEO/IMAGE/TEXT defaults, raw current-Tracks gain, both player wrappers, network generations and saved ownership/retirement checks.

Standalone lyrics .1678 and Settings .1680 have zero failures/errors/skips and lint0errors/65warnings plus3hints. Report ZIP hashes:51813358c46849e0cf60f4efa15023cdb3175c8ce2fdfa5466db87755bde7faa and ee3e027592b272bcd2b5a4e438cbd9e40ec9f3727f33bdfb9afdc73b9d46549f respectively. Selected methods ran in both; Actions APK gates passed, standalone APK bytes not separately inspected. See [#764](../audits/issues/764.md) and [#401](../audits/issues/401.md) for artifacts, source contracts, attribution and later acceptance. No local Android build, Compose UI, physical latency or device claim.

[Current combined receipt](2026-10-25-combined-post-rc-candidate.md) holds the 40 inputs, artifacts and acceptance checklist. .1694 passed 2,011 cases per variant and offline release inspection. .1693 passed 1,822 cases per variant and offline release inspection. Both artwork methods ran in all four reports. Branch artifacts expire after 14 days.

## Additional source outcomes

- [#207](../audits/issues/207.md) is addressed for in-app artwork in #770. The disk key version reloads derived covers. Notification encoding and same-label cover changes remain separate.
- Both downloaded experimental APKs and reports passed inspection. The current QA note records the source-level Play/Pause reconnect window and the experimental workflow's remaining signing-verifier drift. [Experimental receipts](2026-11-10-m3e-rc2.md).
- [#754](../audits/issues/754.md): official pinned Gradle binary really contains affected bundled Guava and selected Java-deserialization callers. No lower-trust writer/exploit or compatible8.14 patch established. Preserve user-accepted provenance/dependency deferral; no forced major tool upgrade or bundled-file replacement.
- Selected neighboring saved-close factories retain their native owner/permit; no second false-clean route found. Selected TS section/emsg/metadata/parcel reads produced qualified nonfindings, not new parser bans. See [source boundaries](../audits/2026-10-25-post-rc-source-boundaries.md). No whole-repository, whole-heap or opaque-SQLite clearance.

## Next actions

1. Keep #770 and #701 draft for later acceptance. The exact recorded builds and targeted tests passed.
2. Continue source review on the main track. The next experimental device pass should exercise rapid Play/Pause on resume, button Back, predictive Back cancellation and the full-player landing. Device authorization is task-specific.
3. Carry main's signing-verifier and signing-material ordering changes in the next reviewed forward sync. The current Android runs also flag the old upload-artifact action declaration. GitHub runs it on Node 24. Review the upstream Node 24 pin and ZIP compatibility in a separate CI-only branch.

## Questions and accepted dispositions

**No unanswered user questions.** Direct Claude usage reading now works through the built-in interactive `/usage` screen in a self-created empty status folder; zero model input/output/API time was reported and the status session exited normally. See continuous-engineering.md. Quota snapshots are not continuous meters; never read OAuth secrets, resume other chats or activate credits/reset without authorization.

Accepted restrictions remain32MiB HEIC preamble, provisional long-AAC/live-WebM,4MiB timed/text,64KiB lacing, skipped unused subtitles, audio-only playback and bounded controls. HEVC#730 stays playable/unresolved. #646 historic reset/SD refusal, #401 measured delays, #671 buffering, deep eject/restart and dependency/tool provenance retain their deferrals. Runtime tests/source do not replace later UAT; no policy decision is pending.
