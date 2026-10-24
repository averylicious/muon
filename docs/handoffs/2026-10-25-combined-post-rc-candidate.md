# Combined main-track post-RC acceptance candidate

Updated2026-10-25. [Draft #701](https://github.com/averylicious/muon/pull/701), branch `codex/post-rc-acceptance-oct25`, includes main `dd8782a0426437093a99ea3f7205878b6af775bc`. Ordinary merges preserve topic ancestry. This is an uninstalled source/CI staging candidate, **not frozen M3E RC .1420**. Inclusion is not acceptance or permission to land runtime changes. The original experimental checkout and installed apps remain unchanged.

## Exact inputs

| PR | Fix | Included topic head | Passed standalone Android run |
| --- | --- | --- | --- |
| #682 | Selected-network discovery | `b80ef8966f92d6c70a2f3b523313dde8cb4203e2` | [.1457](https://github.com/averylicious/muon/actions/runs/40110501457) |
| #691 | Cancel initial Connect | `6a489a1baf0f05e37f23dcb985b8be872ba7831f` | [.1456](https://github.com/averylicious/muon/actions/runs/40110471543) |
| #684 | Worker collection projections | `15a545f01fefa6db4be8a98f647ce0d369832778` | [.1458](https://github.com/averylicious/muon/actions/runs/40110526288) |
| #686 | Single/bulk deletion notices | `ef5341ab5d38f65b57d7d7c3d4aee4240c3bc201` | [.1483](https://github.com/averylicious/muon/actions/runs/40124279216) |
| #689 | Notification artwork revision | `966fcfe9d5a4c5aed5def5e326b5731e7ba20482` | [.1455](https://github.com/averylicious/muon/actions/runs/40110435508) |
| #696 | Current-audio gain policy | `d41714dcbb2c8b27a79a30e3791d6526a0b0df5e` | [.1466](https://github.com/averylicious/muon/actions/runs/40114501142) |
| #698 | Bounded notification artwork work | `613cf0667a10c7c131b813da90a648c9822c1e8f` | [.1461](https://github.com/averylicious/muon/actions/runs/40111785059) |
| #700 | Move-intent read checkpoints | `1c4b93c598313fe539b0dc0c3635abb09edfd8ab` | [.1469](https://github.com/averylicious/muon/actions/runs/40116143278) |
| #703 | ID3 ReplayGain parsing | `5565343debcfbc668b86be5a9788c81863a1ed4c` | [.1476](https://github.com/averylicious/muon/actions/runs/40120929933) |
| #705 | Initial ID3 allocation admission | `9f4cc1cac3fa3c60a31a714a9a783b5f0ef5876c` | [.1478](https://github.com/averylicious/muon/actions/runs/40121809786) |
| #707 | Removal preparation admission | `d53a67cad01afa6a30a50c884d1d423bbb7f1af6` | [.1481](https://github.com/averylicious/muon/actions/runs/40123985161) |
| #709 | Cross-service saved admission | `857e11e1b95a04a3d7c72d8e3521a792c72f9d70` | [.1480](https://github.com/averylicious/muon/actions/runs/40123460530) |

Excluded: #679 promotion policy, #683 Benchmark tools, #692 forward sync and all alpha/M3E commits. Every input's downloaded test/lint reports passed with zero test failures/errors/skips and zero lint errors. Separate input builds do not validate the combination.

## Current integration / review

The first eight-fix head `101508d12b9e45e41e9046a700141f8a17a32b54` passed [combined .1471](https://github.com/averylicious/muon/actions/runs/40118467225):1,851executions/variant, zero failures/errors/skips; lint0errors/65warnings. Its downloaded release APK passed source/BUILD/checksum, release signer, package/version/non-debuggable/export checks: SHA256 `ecdb1e1abdf228804bc58e10f93a78ec6796850c0b3fcc7ab41428f597b0d8ef`. It was not installed. That old build excludes the newer bulk notice and four new topics.

The current integration app head `d699417e43567f92a01d417e21943cde972a2abe` includes all twelve inputs. New final-head combined CI is required. Source review preserved:

- Connect cancellation's `autoTried` and selected-network/stale-owner checks.
- PlaybackService's current-selected-Tracks gain policy, revised notification artwork URI and new bounded extractor factory. ReplayGain keeps both current-audio identity rules and ID3 TXXX parsing.
- Both OfflineStore conflict resolutions retain application-context capture plus pre-enqueue removal admission **and** phone/card-aware Unavailable/bulk-unreadable notices. Actual ownership/byte/move/delivery checks remain. The merged native fixtures exercise both paths together.
- Issue-index conflicts retain all independent rows; no state file was discarded. Main's build/dependency files, manifest, workflows, `tools/`, signer pins and baseline profile are unchanged. No conflict markers/whitespace errors; main/all twelve inputs are ancestors and the alpha anchor is absent.

Local128CI-policy and9release-policy cases passed on the integration. These are lightweight checks, not an Android build. Existing test methods are retained. The new total is expected to be1,863executions/variant; only downloaded final-head XML can confirm that.

## Remaining acceptance / limitations

Run final-head Android CI and inspect both variants/new cases. Later, with new phone authorization: selected-network discovery, slow Connect cancellation, collection search/sort/Back scroll, accurate single/bulk deletion feedback and no delayed extra removal, notification covers during refresh/skips, tagged/untagged and saved ReplayGain, normal/oversized-tag MP3 plus FLAC/Ogg and seek, saved/live service reopen/cancel/retry, disposable Move/recovery. No speedup, sound-level, physical handover or total-memory guarantee. The4MiB ID3 prefix policy deliberately refuses unusually large valid tags/padding; this tradeoff needs acceptance. Historical #401 latency, #646 reset refusal and #671 intermittent buffering remain unresolved, not diagnosed by these source fixes.

## Attribution / ownership

Allocated Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / High explicitly selected integrated the original eight fixes and added the saved-hint median assertion/read-only Move callback KDoc at `40448dd01a4adec736473c4de298266fa2729849`; the runtime reports model, not effort telemetry. GPT-6 / Codex desktop checked that integration and owns the twelve-input refresh, new code/document conflict resolutions, report/APK verification and CI. Exact GPT-6 variant/effort not reported. Each topic retains its PR attribution; reviewing work partly authored by the reviewer is not independent review of every fix. Claude is idle. No phone action, alpha landing or Stable publication/tag.
