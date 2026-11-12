# Post-RC audit continuation

Updated 2026-11-12. The broader source and CI audit remains active. The user set a 50% weekly usage reserve and asked for phone QA to stay queued while they use both devices.

## Ownership and candidate boundaries

GPT-6 in Codex desktop implemented or integrated the refreshed drafts and checked their sources and artifacts. Exact variant and effort were not reported. No independent review was added for these heads. Existing scoped reviews and Fable's experimental implementation retain attribution in their PRs. The user owns the experimental branch and its working checkout.

Main `1b9e508f9819e01acd8aec5cf7296bf77a3ad69f` contains the canonical phone queue and prior evidence. Experimental `b36b4b7ac293d3b5952b8fd5ffef997755630c80` includes Fable's RC2 and predictive Back work. Both refs were refreshed. #679 still pins the earlier RC1 `6b1a767c180a8b9e531bf1468c9e72e348635b74`. The audited drafts below are open. No phone, release or experimental-branch mutation occurred.

| PR and exact head | Verified CI | Ownership / next gate |
| --- | --- | --- |
| [#701](https://github.com/averylicious/muon/pull/701) `9f492fba26a0b0cf0ff7b3d3478cf04f6cb3efa4` | [.1705](https://github.com/averylicious/muon/actions/runs/43202550664), 2,011 cases per variant | `muon-post-rc-acceptance-oct25` idle. All 40 input heads present. User acceptance pending. |
| [#689](https://github.com/averylicious/muon/pull/689) `429084cc934b76a4a0c9acba9bee15a13203d7e3` | [.1704](https://github.com/averylicious/muon/actions/runs/43202366997), 1,825 cases per variant | `muon-notification-artwork-revision-oct24` idle. Refreshes notification cover keys. Also in .1705. |
| [#770](https://github.com/averylicious/muon/pull/770) `6659712772b756d78e78aaf1357b2a7864da6899` | [.1693](https://github.com/averylicious/muon/actions/runs/43192856537), 1,822 cases per variant | `artwork-key-nov12/muon` idle. In-app cache fix. Also in .1705. |
| [#692](https://github.com/averylicious/muon/pull/692) `2e0cb9daed2ae98406f323ab36758cf1b6d4fd4b` | [.1703](https://github.com/averylicious/muon/actions/runs/43201537206), 1,895 cases per variant. [Benchmark .51](https://github.com/averylicious/muon/actions/runs/43201536994) and [inventory .133](https://github.com/averylicious/muon/actions/runs/43201536970) passed. | `muon-forward-main-m3e-ci-oct24` idle. Current alpha app tree with Gradle 9.9.1 and CI hardening. Experimental owner lands at a clean boundary. |
| [#774](https://github.com/averylicious/muon/pull/774) `89cc6ddd3b92874c2c3acbf5279a16b3f82bbebc` | [.1701](https://github.com/averylicious/muon/actions/runs/43198601316), 1,821 cases per variant. [Benchmark .50](https://github.com/averylicious/muon/actions/runs/43198601229) and [inventory .132](https://github.com/averylicious/muon/actions/runs/43198601206) passed. | `muon-phone-qa-nov12` idle. Gradle 8.14.7 on main track. Build-tool draft. |
| [#772](https://github.com/averylicious/muon/pull/772) `cacb4303be8a6525c43b76514e6a658ce82cb665` | [.1702](https://github.com/averylicious/muon/actions/runs/43199542668), 1,821 cases per variant. [Benchmark .49](https://github.com/averylicious/muon/actions/runs/43196570946) and [inventory .131](https://github.com/averylicious/muon/actions/runs/43196571065) passed on prior `1321f7ca`, with identical workflow/app bytes. | `muon-post-rc-state-oct25` idle. Node 24 uploader draft. Publish downloader still declares Node 20. |
| [#766](https://github.com/averylicious/muon/pull/766) `87889a8f8e388ff82751eac191f63f87019fc32e` | [.1680](https://github.com/averylicious/muon/actions/runs/40929064542), 1,824 cases per variant | `settings-move-count-oct30/muon` idle. Also in .1705. |

All listed Android reports showed zero unit failures, errors or skips and zero lint errors. The current PR bodies record exact artifacts, signer/manifest checks and limits. The .1704 and .1705 reports both contain the four notification artwork methods, including the extended revision regression. Both APKs for each .1701–.1705 run passed offline checks against their pinned signers, compiled identity, exports and source receipts. .1693's release APK also passed inspection. No app was installed. Earlier input heads and evidence are in the [combined inventory](2026-10-25-combined-post-rc-candidate.md) and [experimental receipts](2026-11-10-m3e-rc2.md).

## Next actions

1. Keep the [phone QA queue](phone-qa-queue.md) pending until the user authorizes a device and the exact package. Start with the rapid resume tap and predictive Back, then the combined maintenance candidate. Isolated CI drafts do not need phone testing.
2. Before updating the main-track tooling drafts, inspect current destination and required checks. #772 addresses upload only. Review the Node 24 release-artifact downloader's same-run ZIP handoff in a non-publishing job before changing production publication.
3. Keep #693 open until the experimental owner lands the verified sync and fast-forwards their checkout. Gradle 8.14.7 and 9.9.1 still bundle the same inspected Guava bytes. #754 and the approved provenance deferral remain separate.

## Questions and decisions

No unanswered questions. Future user acceptance is pending. Accepted metadata limits and audio-only playback remain recorded in the linked issue snapshots. #730, #646, #401, #671, deep eject/restart and tool provenance retain their existing dispositions. For historical test counts and selected source-only outcomes, use the [older verified candidate](https://github.com/averylicious/muon/blob/1b9e508f9819e01acd8aec5cf7296bf77a3ad69f/docs/handoffs/2026-10-25-post-rc-continuation.md) and [source boundaries](../audits/2026-10-25-post-rc-source-boundaries.md).
