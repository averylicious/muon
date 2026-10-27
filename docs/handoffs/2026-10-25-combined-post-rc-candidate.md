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
| #703 | ID3 and M4A ReplayGain parsing | `795378b92f82f344eaaa7f44a4531f23b18c4ce5` | [.1518](https://github.com/averylicious/muon/actions/runs/40497013720) |
| #705 | Initial ID3 allocation admission | `9f4cc1cac3fa3c60a31a714a9a783b5f0ef5876c` | [.1478](https://github.com/averylicious/muon/actions/runs/40121809786) |
| #707 | Removal preparation admission | `d53a67cad01afa6a30a50c884d1d423bbb7f1af6` | [.1481](https://github.com/averylicious/muon/actions/runs/40123985161) |
| #709 | Cross-service saved admission | `857e11e1b95a04a3d7c72d8e3521a792c72f9d70` | [.1480](https://github.com/averylicious/muon/actions/runs/40123460530) |
| #710 | Coalesced legacy Move checks | `836acfe9ba0dbf6f5dc07c7bfdc8cc24785a3536` | [.1485](https://github.com/averylicious/muon/actions/runs/40126060599) |
| #712 | Notification/platform grants | `e68b7b8a63d07e0d2f83c13f475e1781f2e6831c` | [.1491](https://github.com/averylicious/muon/actions/runs/40129516260) |
| #717 | Weak discovery resolver owner | `522c8c74f236098276386b774dd07b3efded056e` | [.1499](https://github.com/averylicious/muon/actions/runs/40134579673) |
| #718 | FLAC/Ogg allocation admission | `ef5b632b16b07c0ea39518f54957692ef041ca21` | [.1504](https://github.com/averylicious/muon/actions/runs/40451576758) |
| #719 | Known-close saved-cover temporary cleanup | `59ccdbb1ae70783f36becc1e0eb06fb9aaf7058a` | [.1506](https://github.com/averylicious/muon/actions/runs/40452824139) |
| #722 | Ordinary/fragmented MP4 allocation admission | `fcca5f6ca4b11824ca68c71f8ed05943eca0dd8b` | [.1529](https://github.com/averylicious/muon/actions/runs/40510147704) |
| #725 | WAV fmt and writer allocation admission | `d0cdd44a95e975c0869c3b83bbc0c670994b39cc` | [.1534](https://github.com/averylicious/muon/actions/runs/40517654919) |
| #728 | Matroska metadata/sniff and retention admission | `b4ea65dbecd89bf6a0c2659e03df41c845ccaab9` | [.1541](https://github.com/averylicious/muon/actions/runs/40528858359) |
| #729 | Decoded ID3 cost and supplemental metadata admission | `22c3cf2c4495c734357263305aa4143bd6bfd10f` | [.1552](https://github.com/averylicious/muon/actions/runs/40545634270) |

#715 raw Vorbis/picture and #721 ordinary/fragmented MP4 characterization are included as test evidence, not additional runtime topics. #721 exact14cc43efeb3cbb2695c7ef913dfd9c6e3e7bbb5a / .1516 passed1,824cases/variant. Raw WAV #724 characterization is also included through #725. Raw Matroska #727 is included through #728. Raw ID3 characterization is included through #729. This candidate now has21 runtime topics, including the newer #703 head. #679 promotion policy, #683 Benchmark tools, raw HEVC #731, AC-4 #733, #692 forward sync and all alpha/M3E commits remain excluded. Every input's downloaded test/lint reports passed with zero failures/errors/skips and lint0errors; standalone passes do not verify this combination.

## Current integration boundary

Prior twenty-topic head `6137b6fffe1364349f1d878b49be96e2ec699f9c` / [.1543](https://github.com/averylicious/muon/actions/runs/40532376624) passed downloaded1,924cases/variant, zero failures/errors/skips, lint0errors/65warnings plus3hints. All eight Matroska guard and three raw cases ran in both, alongside the WAV/MP4/Ogg controls. Actual release source/BUILD/checksum, single signer/package/label/version/non-debuggable/export/profile gates passed; APK SHA256 `36641954ca9dea3c57e6a3d8a7c2dd6f1756ef1b75976f705b109773dfb5e4b8`, uninstalled. [Immutable earlier combination history](https://github.com/averylicious/muon/blob/6137b6fffe1364349f1d878b49be96e2ec699f9c/docs/handoffs/2026-10-25-combined-post-rc-candidate.md) preserves prior receipts/conflict history.

The new merge adds #729/22c3cf2c (.1552 actual1,840cases/variant, seven guard/four renderer/three raw cases). Native extractor identity/order and all MP4/WAV/Matroska/Ogg observers/output callbacks are preserved. ID3 body admission wraps the effective sniff/read input; prefix tag count is checked before advancing. PlaybackService supplies the guarded metadata renderer factory through the public builder overload. Documentation retains current verified evidence. **New twenty-one-topic CI is pending.** Source review retains:



- Selected-network/stale-owner discovery plus owner-independent Handler/weak resolver capture; a request contains generation/id/key/data, not UI callback closures.
- Initial Connect cancellation, worker collection projections, current-selected-audio ReplayGain/ID3/M4A parsing, notification artwork identity/queue and notification/platform grant restrictions.
- User-removal preparation, cross-service admission, bulk/unavailable notices and coalesced Move completion. Played-claim census preparation runs through the actual index before exclusive partition deletion; its final callback only queries the prepared claims. Automatic eviction remains fail-closed.
- New saved-cover temporaries are removed only after known closes; uncertain exact handles/files stay retained. No old-partial purge or audio/record deletion is introduced.
- Existing extractor identity/order, ID3 guard and production PlaybackExtractors wiring. The new FLAC/Ogg guard uses public pinned forwarding APIs, preserves valid gain/artwork in JVM tests and checks actual bytes after interrupted/nonzero reopens. The added #722 guard covers ordinary/nested and fragmented MP4 metadata, sample/index retention and selected size declarations; its controls/source review are recorded on #720. Other codecs/decoder/GC overlap remain qualified, not whole-heap clearance.

Build/dependency files, manifest, workflows/tools, signer pins and baseline profiles stay unchanged from main20579cf1. All listed inputs must remain ancestors and alpha excluded. Root checks full-head branch direction and whitespace before push; Actions is the Android compile, not a local build.

## Cleanup and proof limits

The existing `ArtworkDiskCache.write` finally cleanup remains from .1492; it attempts only its own derived-cover temporary after failed writes/renames. Existing tests passed, but no injected ENOSPC/partial-write guarantee exists for that separate cache. New #719 tests do exercise real disposable saved-cover write/rename failures and retain uncertain-close assertions; one proof is not silently substituted for the other.

#686/.1503 failed a new fixture's final idle-reader expectation. .1505 then passed raw-index tests, but source review caught preparation occurring inside exclusive admission. Final D9/.1508 uses actual StorageDownloadIndex admission with preparation before deletion; both native/partition cases passed. None reproduces the cleared historical reset/SD state. [#646](../audits/issues/646.md).

## Remaining acceptance

Later with explicit phone authorization: network handover/discovery, Connect cancellation, collection Back/scroll/search, single/bulk/played Delete and retry, notification covers/platform/Bluetooth, tagged/untagged/saved gain, normal MP3/FLAC/Opus/Vorbis and seek/reconnect, saved/live service reopen/cancel, disposable Move/recovery. New16MiB/4,096-entry FLAC/Ogg and4MiB ID3 prefix policies need acceptance. MP4 adds32MiB metadata/peek/encryption retention,1,048,576 weighted rows,16MiB selected samples,64KiB ftyp and4,096 box/entry/census keys, depth16. The weighted-row/long-AAC cost was provisionally accepted; other compatibility still needs acceptance. Include normal AAC/ALAC/PCM, M4A gain/artwork, fragment/index seeking/reconnect and ordinary audio regressions. Unusually large legitimate files may be refused; Ogg can grow one page-run65,025bytes beyond its delivery budget before refusal and old/new buffers may coexist. No sound-level, physical routing, timing or universal memory claim.

#401 latency, #646 historical refusal and #671 buffering stay qualified follow-ups. MP4 [#720](../audits/issues/720.md) is now staged here with its own acceptance/limits; raw #721 only characterizes the dependency. WAV #723 is now included: proposed64KiB fmt and16MiB writer limits, normal PCM/float/A-law/mu-law/extensible/IMA/RF64 and seek/retry acceptance remain. Matroska #726 is now staged: proposed4MiB element/16MiB metadata/4,096-entry/64KiB sniff,128 observed track keys including0,4,096 chapter UIDs and65,536 cue positions. On2026-10-27 the user provisionally accepted4,096 retained native master frames with a clear long/live-WebM restriction. Other compatibility policies and runtime acceptance remain pending. Native header/unknown-size behavior is source/JVM evidence, not audio/whole-heap certification. New ID3-frame #729 remains separate. No new frozen-RC gate or installation is implied.

## Attribution / ownership

Allocated Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / explicitly High integrated the original eight fixes and authored #718's container guard; runtime confirms model, not effort telemetry. It source-reviewed root's played-claim ordering, saved-cover cleanup and later MP4 nested/MFRA/fragment source with scoped limits recorded on those PRs. Root owns subsequent corrections/default-size controls; no whole-candidate independence is claimed. GPT-6 / Codex desktop owns the remaining inputs/refreshed integration, original synthetic fixtures, design constraints, source corrections, documentation/conflict resolutions and CI/report checks; exact variant/effort not reported. Per-topic PR attribution remains authoritative. Shared authorship/reviewer fixes are not wholly independent review of every component. Opus independently reviewed root1003cad2 ID3/renderer code and found the invalid-encoding delimiter undercount. Root confirmed/fixed that counter and added two real-parser regressions; those follow-ups passed .1552 and are root-self-reviewed. No whole-candidate review or runtime acceptance is claimed. Verify allocated-session ownership before reuse. Root is the only writer of this combined branch.
