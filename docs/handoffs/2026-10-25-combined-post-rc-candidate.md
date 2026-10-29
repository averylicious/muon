# Combined main-track post-RC acceptance candidate

Updated2026-10-30. [Draft #701](https://github.com/averylicious/muon/pull/701), branch `codex/post-rc-acceptance-oct25`, includes current documentation main `2ec8b69892beda2ef691e0c4b3ac01f1b9e462ef` (application baseline20579cf1). Ordinary merges retain topic ancestry. This uninstalled source/CI staging candidate is **not frozen M3E RC .1420**. Inclusion is not acceptance or permission to land runtime changes. No phone, alpha landing or Stable publication.

## Exact verified inputs

| PR | Fix | Included topic head | Passed standalone Android run |
| --- | --- | --- | --- |
| #682 | Selected-network discovery | `b80ef8966f92d6c70a2f3b523313dde8cb4203e2` | [.1457](https://github.com/averylicious/muon/actions/runs/40110501457) |
| #691 | Cancel initial Connect | `6a489a1baf0f05e37f23dcb985b8be872ba7831f` | [.1456](https://github.com/averylicious/muon/actions/runs/40110471543) |
| #684 | Worker collection projections | `15a545f01fefa6db4be8a98f647ce0d369832778` | [.1458](https://github.com/averylicious/muon/actions/runs/40110526288) |
| #686 | Single/bulk notices and played-claim retry | `d9edbb24d46751acb7834c69582f318a3a4585cd` | [.1508](https://github.com/averylicious/muon/actions/runs/40453530337) |
| #689 | Notification artwork revision | `966fcfe9d5a4c5aed5def5e326b5731e7ba20482` | [.1455](https://github.com/averylicious/muon/actions/runs/40110435508) |
| #696 | Current-audio gain and reminder identity | `f08b1b7f55888eb985e7fb7958d0502ccaefd4d9` | [.1577](https://github.com/averylicious/muon/actions/runs/40571738622) |
| #698 | Bounded notification artwork work | `613cf0667a10c7c131b813da90a648c9822c1e8f` | [.1461](https://github.com/averylicious/muon/actions/runs/40111785059) |
| #700 | Move-intent read checkpoints | `1c4b93c598313fe539b0dc0c3635abb09edfd8ab` | [.1469](https://github.com/averylicious/muon/actions/runs/40116143278) |
| #703 | ID3 and M4A ReplayGain parsing | `795378b92f82f344eaaa7f44a4531f23b18c4ce5` | [.1518](https://github.com/averylicious/muon/actions/runs/40497013720) |
| #705 | Initial ID3 allocation admission | `9f4cc1cac3fa3c60a31a714a9a783b5f0ef5876c` | [.1478](https://github.com/averylicious/muon/actions/runs/40121809786) |
| #707 | Removal preparation admission | `d53a67cad01afa6a30a50c884d1d423bbb7f1af6` | [.1481](https://github.com/averylicious/muon/actions/runs/40123985161) |
| #709 | Cross-service saved admission | `857e11e1b95a04a3d7c72d8e3521a792c72f9d70` | [.1480](https://github.com/averylicious/muon/actions/runs/40123460530) |
| #710 | Coalesced legacy Move checks | `836acfe9ba0dbf6f5dc07c7bfdc8cc24785a3536` | [.1485](https://github.com/averylicious/muon/actions/runs/40126060599) |
| #712 | Notification/platform grants | `e6151630116ded1db0c44072cd9c9b11eb4b5713` | [.1571](https://github.com/averylicious/muon/actions/runs/40565365537) |
| #717 | Weak discovery resolver owner | `522c8c74f236098276386b774dd07b3efded056e` | [.1499](https://github.com/averylicious/muon/actions/runs/40134579673) |
| #718 | FLAC/Ogg allocation admission | `ef5b632b16b07c0ea39518f54957692ef041ca21` | [.1504](https://github.com/averylicious/muon/actions/runs/40451576758) |
| #719 | Saved-cover path checks and known-close temporary cleanup | `ccbfa9202a45b04dc0d71157f5e43eab0c4b91cd` | [.1584](https://github.com/averylicious/muon/actions/runs/40582254296) |
| #722 | Ordinary/fragmented and nested motion-photo MP4 admission | `e22402b5e5ebce3c0e7e1edfa4cdd17b56e85f0d` | [.1661](https://github.com/averylicious/muon/actions/runs/40902775706) |
| #725 | WAV fmt and writer allocation admission | `d0cdd44a95e975c0869c3b83bbc0c670994b39cc` | [.1534](https://github.com/averylicious/muon/actions/runs/40517654919) |
| #728 | Matroska metadata/retention, text-packet and lacing-header admission | `8986609865859e7609eb6921aa50fe6947463d0f` | [.1632](https://github.com/averylicious/muon/actions/runs/40697510074) |
| #729 | Decoded ID3 cost and approved unfinished timed-tag admission | `8c596c758181315aecbc1e4e1abc2cd9d4f58233` | [.1599](https://github.com/averylicious/muon/actions/runs/40656822277) |
| #733 | AC-4 native sniff lookahead | `38bb04811f5d06f7665a86847c980f6dc967a40c` | [.1554](https://github.com/averylicious/muon/actions/runs/40547783673) |
| #735 | AVI header/index admission | `4c868137f5709742b497729f7c87782fddd58d5e` | [.1561](https://github.com/averylicious/muon/actions/runs/40554516480) |
| #737 | FLV startup/SCRIPT admission | `ba73a8d29e1789bd29505f76784d75788348c213` | [.1569](https://github.com/averylicious/muon/actions/runs/40563928849) |
| #739 | Bounded selected-song controller metadata | `74fef8d3fc82d5ffc42eb0e52b16e23d4bff9e39` | [.1593](https://github.com/averylicious/muon/actions/runs/40652452658) |
| #741 | Discard unused strict tracklist JSON graphs | `020a051a10396d65a7e7e8154c6e617ffddd6c9d` | [.1580](https://github.com/averylicious/muon/actions/runs/40576282449) |
| #743 | Skip unused subtitle decoding and disable text selection | `35300ff737d7501c230e1e7ff4c3fe4527bccfb9` | [.1605](https://github.com/averylicious/muon/actions/runs/40663637501) |
| #745 | Coalesced queue feedback and shared-host action receipts | `403647525a94f65166784a15363f6b80fc15cd58` | [.1627](https://github.com/averylicious/muon/actions/runs/40692063345) |
| #747 | Light artwork-theme normal-text contrast | `f8c06c3a5a9fa71056caea7bb601cc42be3c08b0` | [.1634](https://github.com/averylicious/muon/actions/runs/40701146866) |
| #749 | Exact repeated-playlist-row selection | `db401da750aa8fd9356daedebe20f34d6b6c308e` | [.1638](https://github.com/averylicious/muon/actions/runs/40707105195) |
| #751 | Audio-only track defaults, preserving tags and subtitle policy | `9688fb60310e66fdab7bfd136396e0734b86de62` | [.1643](https://github.com/averylicious/muon/actions/runs/40715489798) |
| #753 | Stable Media3 audio-output lifecycle patch and guard compatibility | `ec3543ba1e1124f8622da50e5916b0072fadf342` | [.1644](https://github.com/averylicious/muon/actions/runs/40717264755) |
| #755 | Existing transitive Guava deserialization-allocation patch | `a7ec5ca682f98bcf7f409a77cac5777d12798d92` | [.1653](https://github.com/averylicious/muon/actions/runs/40733686893) |
| #758 | Preserve uncertain completed-save cleanup ownership | `5f5e76fb739b414ce6a3122489669b4331dfc01b` | [.1663](https://github.com/averylicious/muon/actions/runs/40906829125) |

| #760 | Neutral outcome wording for unconfirmed queue edits | `994e7f0949b488fb0228b07a9a632ebd7a05920e` | [.1667](https://github.com/averylicious/muon/actions/runs/40911558711) |
| #761 | Finite remembered-gain median arithmetic | `13f5cce8efbdb1398dc446c0cc4aae2eb9824e30` | [.1668](https://github.com/averylicious/muon/actions/runs/40913729226) |

| #763 | Modern resolve cleanup and weak discovery owner | `fceb47c2515b80baf1560eed27e30a52e0d0358e` | [.1673](https://github.com/averylicious/muon/actions/runs/40921716539) |

## Current integration boundary

Remote verified34-topic head **db1ee908dc5573da2c870d62e6259f04ea936bf5 / [Android .1666](https://github.com/averylicious/muon/actions/runs/40909630273)** passed downloaded2,000cases/variant, zero failures/errors/skips and lint0errors/62warnings plus3hints. Report SHA256 bc910d45c04a6bffb0be92cf0d3d9b5b3e35671fd87d026b3716198bd01a2248 matched API. Selected cached-sniff cancellation, HEIC cutover, native seek/restart and completed-save uncertainty controls ran in both. Actions APK gates passed; this head's actual APK bytes were not separately inspected. [Canary .1666 artifact](https://github.com/averylicious/muon/actions/runs/40909630273/artifacts/12914466953), app-debug-db1ee908dc5573da2c870d62e6259f04ea936bf5, uninstalled.

The last separately inspected release bytes are prior33-topic d1462a1c970a4b11135bef32d61e457d7a66f067 / [Android .1662](https://github.com/averylicious/muon/actions/runs/40905616983):1,999cases/variant, zero failures/errors/skips; lint0errors/62warnings plus3hints; report SHA256 de93ee7d0129c3a63b92c12cb06040d102c0dad0da9a81168137489c625c8ae0. BUILD/source/checksum/pinned signer/identity/non-debuggable/export/profile checks passed offline; APK SHA2566f8211aba5d27230d1bb1173d7d4b4677ea902579c2a9826748165979d1d02c4, uninstalled. These bytes are not proof of the newer composition.

**New37-topic composition is staged after40c0d1f7; its publication/full CI is next.** #760/#761/#763 inputs passed independently. The direct#702 gain-key correction skips case conversion for oversized unrelated ID3/MP4 descriptions, extending existing negative controls without adding test cases. This combination is not verified by old .1666 or by standalone .1673. Root resolved the ServerDiscovery conflict with the complete fceb47c2 source (byte-identical production/test files), preserving prior weak ownership. Queue integration retains QueueNotices, not the older direct snackbar implementation. All37 exact input ancestors were checked; manifest/workflows/tools/signer/Baseline-Profile inputs are unchanged from current main.

## Integration invariants

- Preserve every exact topic ancestor above; #715/#721/#724/#727 and raw ID3 fixtures are supporting test inputs, not extra runtime topics. Alpha/M3E, #679 promotion policy, #683 Benchmark tools, raw HEVC#731 and #692 forward-sync remain excluded.
- Keep selected-network/generation ownership, Connect cancellation and worker projections; saved-removal preparation/exclusive admission, played-claim retry, cross-service admission and coalesced Move checks.
- Keep raw current-Tracks gain, ID3/M4A/Vorbis handling and reminder identity; bounded controller metadata/artwork work, current notification art identity and repeated-playlist row selection.
- Keep stable-library raw-PCM admission, guarded metadata/renderers and all container/timed guards; VIDEO/IMAGE/TEXT disabled policy, both controller/queue wrappers and **all three** notice action adapters.
- Only the separately reviewed Media3/app/plugin Guava changes affect dependency surfaces. Compose/Activity, package/signer/manifest/workflow/profile inputs remain outside these runtime deltas. Bundled Gradle-core Guava remains unresolved with the explicit provenance deferral.
- Approved32MiB HEIC preamble, provisional long-AAC/live-WebM,4MiB timed/text and64KiB lacing policies remain compatibility restrictions. These are scoped guards, not whole-heap/decoder/native-quiescence guarantees.

## Acceptance / disposition

Runtime topics stay draft/open. Later authorized acceptance must cover network/permission handover and manual fallback; ordinary/saved/container playback and normalization; notification artwork/controls; queue bursts/Undo/duplicates and full-player notices; collection loading/scroll/focus; and saved Move/Delete/recovery. No phone access this cycle. No measured battery/performance improvement or independent whole-candidate approval is claimed.

Frozen .1420 is not replaced. Main application's20579cf1 baseline, published Stablev0.2.0 and alpha remain separate. #646 historical reset/SD Delete refusal, #401 delays, #671 buffering, deep eject/restart, HEVC#730 and dependency provenance keep their recorded open/accepted deferrals. Do not invent new frozen-RC gates or claim an unresolved finding fixed.

Exact older inventories/receipts and conflict history are preserved in the [prior snapshot](https://github.com/averylicious/muon/blob/db1ee908dc5573da2c870d62e6259f04ea936bf5/docs/handoffs/2026-10-25-combined-post-rc-candidate.md), topic PRs and Git history. Resume ownership/check status from the [current continuation](2026-10-25-post-rc-continuation.md). This maintained snapshot replaces duplicated receipt chronology; historical proofs remain historical.
