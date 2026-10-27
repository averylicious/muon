# Combined main-track post-RC acceptance candidate

Updated2026-10-27. [Draft #701](https://github.com/averylicious/muon/pull/701), branch `codex/post-rc-acceptance-oct25`, includes main `20579cf1a056b9c4a095aa860355f624877122de`. Ordinary merges retain topic ancestry. This uninstalled source/CI staging candidate is **not frozen M3E RC .1420**. Inclusion is not acceptance or permission to land runtime changes. No phone, alpha landing or Stable publication.

## Exact verified inputs

| PR | Fix | Included topic head | Passed standalone Android run |
| --- | --- | --- | --- |
| #682 | Selected-network discovery | `b80ef8966f92d6c70a2f3b523313dde8cb4203e2` | [.1457](https://github.com/averylicious/muon/actions/runs/40110501457) |
| #691 | Cancel initial Connect | `6a489a1baf0f05e37f23dcb985b8be872ba7831f` | [.1456](https://github.com/averylicious/muon/actions/runs/40110471543) |
| #684 | Worker collection projections | `15a545f01fefa6db4be8a98f647ce0d369832778` | [.1458](https://github.com/averylicious/muon/actions/runs/40110526288) |
| #686 | Single/bulk notices and played-claim retry | `d9edbb24d46751acb7834c69582f318a3a4585cd` | [.1508](https://github.com/averylicious/muon/actions/runs/40453530337) |
| #689 | Notification artwork revision | `966fcfe9d5a4c5aed5def5e326b5731e7ba20482` | [.1455](https://github.com/averylicious/muon/actions/runs/40110435508) |
| #696 | Current-audio gain policy | `d41714dcbb2c8b27a79a30e3791d6526a0b0df5e` | [.1466](https://github.com/averylicious/muon/actions/runs/40114501142) |
| #698 | Bounded notification artwork work | `613cf0667a10c7c131b813da90a648c9822c1e8f` | [.1461](https://github.com/averylicious/muon/actions/runs/40111785059) |
| #700 | Move-intent read checkpoints | `1c4b93c598313fe539b0dc0c3635abb09edfd8ab` | [.1469](https://github.com/averylicious/muon/actions/runs/40116143278) |
| #703 | ID3 ReplayGain parsing | `5565343debcfbc668b86be5a9788c81863a1ed4c` | [.1476](https://github.com/averylicious/muon/actions/runs/40120929933) |
| #705 | Initial ID3 allocation admission | `9f4cc1cac3fa3c60a31a714a9a783b5f0ef5876c` | [.1478](https://github.com/averylicious/muon/actions/runs/40121809786) |
| #707 | Removal preparation admission | `d53a67cad01afa6a30a50c884d1d423bbb7f1af6` | [.1481](https://github.com/averylicious/muon/actions/runs/40123985161) |
| #709 | Cross-service saved admission | `857e11e1b95a04a3d7c72d8e3521a792c72f9d70` | [.1480](https://github.com/averylicious/muon/actions/runs/40123460530) |
| #710 | Coalesced legacy Move checks | `836acfe9ba0dbf6f5dc07c7bfdc8cc24785a3536` | [.1485](https://github.com/averylicious/muon/actions/runs/40126060599) |
| #712 | Notification/platform grants | `e68b7b8a63d07e0d2f83c13f475e1781f2e6831c` | [.1491](https://github.com/averylicious/muon/actions/runs/40129516260) |

| #717 | Weak discovery resolver owner | `522c8c74f236098276386b774dd07b3efded056e` | [.1499](https://github.com/averylicious/muon/actions/runs/40134579673) |
| #718 | FLAC/Ogg allocation admission | `ef5b632b16b07c0ea39518f54957692ef041ca21` | [.1504](https://github.com/averylicious/muon/actions/runs/40451576758) |
| #719 | Known-close saved-cover temporary cleanup | `59ccdbb1ae70783f36becc1e0eb06fb9aaf7058a` | [.1506](https://github.com/averylicious/muon/actions/runs/40452824139) |

#715 raw Vorbis/picture characterization is included as #718's prerequisite, not another runtime fix. #679 promotion policy, #683 Benchmark tools, #692 forward sync, #721 raw MP4 proof and all alpha/M3E commits remain excluded. Every input's downloaded test/lint reports passed with zero failures/errors/skips and lint0errors; standalone passes do not verify this combination.

## Current integration boundary

Previous fourteen-input head `d90b41b754332989b2a88786f5f1afa3baef77a8` passed [combined .1492](https://github.com/averylicious/muon/actions/runs/40131122045): downloaded1,865cases/variant, zero failures/errors/skips; lint0errors/65warnings plus3hints. Its actual release APK passed source/BUILD/checksum, signer, package/version/non-debuggable/export/profile checks, SHA256 `06db5e43c793a1937cc5c9bf5fb7bac35825a193ecdba2d716acfded1d99844f`; uninstalled. [Immutable prior report](https://github.com/averylicious/muon/blob/d90b41b754332989b2a88786f5f1afa3baef77a8/docs/handoffs/2026-10-25-combined-post-rc-candidate.md) retains earlier .1471/.1484/.1487 receipts and detailed conflict history.

The refreshed candidate adds the verified newer #686 head and #717/#718/#719. **New combined CI is pending; .1492 does not pass the new combination.** App merges were clean; documentation conflicts preserve maintained current issue state and all independent index rows. Source review retains:

- Selected-network/stale-owner discovery plus owner-independent Handler/weak resolver capture; a request contains generation/id/key/data, not UI callback closures.
- Initial Connect cancellation, worker collection projections, current-selected-audio ReplayGain/ID3 parsing, notification artwork identity/queue and notification/platform grant restrictions.
- User-removal preparation, cross-service admission, bulk/unavailable notices and coalesced Move completion. Played-claim census preparation runs through the actual index before exclusive partition deletion; its final callback only queries the prepared claims. Automatic eviction remains fail-closed.
- New saved-cover temporaries are removed only after known closes; uncertain exact handles/files stay retained. No old-partial purge or audio/record deletion is introduced.
- Existing extractor identity/order, ID3 guard and production PlaybackExtractors wiring. The new FLAC/Ogg guard uses public pinned forwarding APIs, preserves valid gain/artwork in JVM tests and checks actual bytes after interrupted/nonzero reopens. It does not cover MP4/other codecs or the whole heap.

Build/dependency files, manifest, workflows/tools, signer pins and baseline profiles stay unchanged from main20579cf1. All listed inputs must remain ancestors and alpha excluded. Root checks full-head branch direction and whitespace before push; Actions is the Android compile, not a local build.

## Cleanup and proof limits

The existing `ArtworkDiskCache.write` finally cleanup remains from .1492; it attempts only its own derived-cover temporary after failed writes/renames. Existing tests passed, but no injected ENOSPC/partial-write guarantee exists for that separate cache. New #719 tests do exercise real disposable saved-cover write/rename failures and retain uncertain-close assertions; one proof is not silently substituted for the other.

#686/.1503 failed a new fixture's final idle-reader expectation. .1505 then passed raw-index tests, but source review caught preparation occurring inside exclusive admission. Final D9/.1508 uses actual StorageDownloadIndex admission with preparation before deletion; both native/partition cases passed. None reproduces the cleared historical reset/SD state. [#646](../audits/issues/646.md).

## Remaining acceptance

Later with explicit phone authorization: network handover/discovery, Connect cancellation, collection Back/scroll/search, single/bulk/played Delete and retry, notification covers/platform/Bluetooth, tagged/untagged/saved gain, normal MP3/FLAC/Opus/Vorbis and seek/reconnect, saved/live service reopen/cancel, disposable Move/recovery. New sixteen-MiB/4,096-entry container policy needs acceptance, alongside the earlier4MiB ID3 prefix policy. Unusually large legitimate files may be refused; Ogg can grow one page-run65,025bytes beyond its delivery budget before refusal and old/new buffers may coexist. No sound-level, physical routing, timing or universal memory claim.

#401 latency, #646 historical refusal, #671 buffering and new MP4 [#720](../audits/issues/720.md) stay separate qualified follow-ups. Raw MP4 #721 is not included and adds no guard. No new frozen-RC gate or installation is implied.

## Attribution / ownership

Allocated Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / explicitly High integrated the original eight fixes and authored #718's container guard; runtime confirms model, not effort telemetry. It source-reviewed root's played-claim ordering and saved-cover cleanup with the scoped limits recorded on those PRs. GPT-6 / Codex desktop owns the remaining inputs/refreshed integration, original synthetic fixtures, design constraints, source corrections, documentation/conflict resolutions and CI/report checks; exact variant/effort not reported. Per-topic PR attribution remains authoritative. Shared authorship/reviewer fixes are not wholly independent review of every component. Claude is idle after its bounded source reports; verify live ownership before reuse. Root is the only writer of this combined branch.
