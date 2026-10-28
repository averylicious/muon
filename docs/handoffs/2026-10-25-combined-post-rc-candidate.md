# Combined main-track post-RC acceptance candidate

Updated2026-10-28. [Draft #701](https://github.com/averylicious/muon/pull/701), branch `codex/post-rc-acceptance-oct25`, includes main `20579cf1a056b9c4a095aa860355f624877122de`. Ordinary merges retain topic ancestry. This uninstalled source/CI staging candidate is **not frozen M3E RC .1420**. Inclusion is not acceptance or permission to land runtime changes. No phone, alpha landing or Stable publication.

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
| #722 | Ordinary/fragmented and nested motion-photo MP4 admission | `7b77c6784e1cd8927f625acf1aad6a0f3bbb3797` | [.1626](https://github.com/averylicious/muon/actions/runs/40691594063) |
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

#715 raw Vorbis/picture and #721 ordinary/fragmented MP4 characterization are included as test evidence, not additional runtime topics. #721 exact14cc43efeb3cbb2695c7ef913dfd9c6e3e7bbb5a / .1516 passed1,824cases/variant. Raw WAV #724 characterization is also included through #725. Raw Matroska #727 is included through #728. Raw ID3 characterization is included through #729. This candidate now has29 runtime topics, including the newer #703 head. #679 promotion policy, #683 Benchmark tools, raw HEVC #731 and #692 forward sync and all alpha/M3E commits remain excluded. Every input's downloaded test/lint reports passed with zero failures/errors/skips and lint0errors; standalone passes do not verify this combination.

## Current integration boundary

Latest verified28-topic `025be17485b128dae42ab18815137400ec56bf34` / [Android .1635](https://github.com/averylicious/muon/actions/runs/40701426716) passed downloaded1,994cases/variant, zero failures/errors/skips; lint0errors/65warnings plus3hints. Report ZIP SHA256 `57b5720965f633c46c1a726b769991317932e00e873ea496cb21d8d7bcb3b05a` matches the artifact API. Actual signed-release source/BUILD/checksum/pinned signer/identity/non-debuggable/export/profile gates passed; APK SHA256 `7fc59934d62f281c37f9a0470276aecc7a65a9af50d0f2158af7145023589ed5`, uninstalled. All eleven guard/five raw Matroska, five nested JPEG and five queue methods ran in both variants. The approved64KiB lacing-header policy and native header/seek controls are included. Native backing may already double before interception; this is not a byte-exact heap cap or blanket audio-packet limit.

This29-topic refresh adds verified#747/f8c06c3a / [Android .1634](https://github.com/averylicious/muon/actions/runs/40701146866): actual1,821cases/variant including all five ArtworkThemeTest methods, zero failures/errors/skips/lint errors. Light primary is darker so small primary text meets4.5:1 in the expanded settled-color sweep. No test methods added or dark/pure-black palette/dependency change. Animated/rendered appearance remains untested. The only merge conflict was the issue index; both queue and contrast rows were retained. The automatic ArtworkTheme merge retains the existing revision-aware artwork identity behavior.

**This new combined source needs its own Actions/reports/APK; .1635 does not verify the added contrast correction.** It remains uninstalled and separate from frozen M3E RC .1420.

Approved4MiB unfinished-tag/text-packet policies and unused-subtitle skipping remain included, with their restrictions documented. Nested JPEG reuses ordinary MP4 admission, verifies public motion-photo offset, bounds both preamble sniff passes to32MiB requested lookahead and responds to buffered cancellation. The rare-large-preamble restriction still needs later compatibility acceptance. No standalone image-decoder, arbitrary partial-body retry or whole-heap guarantee. [Prior immutable source/receipts](https://github.com/averylicious/muon/blob/73843e53317e3c1161c84691e7e08a1a4dbff3aa/docs/handoffs/2026-10-25-combined-post-rc-candidate.md); opening old history is optional for resumption.

Source/integration checks retain:

- All29 exact input ancestors; alpha/M3E commits excluded. Build/dependency/manifest/workflow/tools/signer/profile surfaces unchanged from main20579cf1.
- Selected-network discovery/stale ownership, Connect cancellation and worker projections; single/bulk/played Delete preparation before exclusive admission and coalesced Move checks.
- Current-audio ReplayGain identity and finite hints, existing ID3/M4A/Vorbis parsing, bounded reminder labels, notification artwork identity/queue and narrowed external grants. Reminder labels never authorize audio or select gain.
- Original extractor identity/order; all ID3/FLAC/Ogg/MP4/WAV/Matroska/AVI/FLV read/seek/output guards, AC-4/FLV lookahead and supplied supplemental metadata renderer. The timed output and seek-generation reset are composed with the existing guards; no decoder buffer/global heap guarantee.
- Saved-cover cleanup removes only exact temporaries after known closes; uncertain exact handles/files are retained. No automatic old-partial/audio/record purge.

Root checks branch direction and whitespace before push. Actions is the Android compile; no local Gradle build or phone test.

## Cleanup and proof limits

The existing `ArtworkDiskCache.write` finally cleanup remains from .1492; it attempts only its own derived-cover temporary after failed writes/renames. Existing tests passed, but no injected ENOSPC/partial-write guarantee exists for that separate cache. New #719 tests do exercise real disposable saved-cover write/rename failures and retain uncertain-close assertions; one proof is not silently substituted for the other.

#686/.1503 failed a new fixture's final idle-reader expectation. .1505 then passed raw-index tests, but source review caught preparation occurring inside exclusive admission. Final D9/.1508 uses actual StorageDownloadIndex admission with preparation before deletion; both native/partition cases passed. None reproduces the cleared historical reset/SD state. [#646](../audits/issues/646.md).

## Remaining acceptance

Later with explicit phone authorization: light artwork labels/controls with yellow, neutral and other covers, large fonts and dark/pure-black regression checks; rapid removal/Add/Play-next feedback and Undo alongside unrelated notices, default Snackbar focus and screen close/reopen; network handover/discovery, Connect cancellation, collection Back/scroll/search, single/bulk/played Delete and retry, notification covers/platform/Bluetooth, tagged/untagged/saved gain, normal MP3/FLAC/Opus/Vorbis and seek/reconnect, saved/live service reopen/cancel, disposable Move/recovery. New16MiB/4,096-entry FLAC/Ogg and4MiB ID3 prefix policies need acceptance. MP4 adds32MiB metadata/peek/encryption retention,1,048,576 weighted rows,16MiB selected samples,64KiB ftyp and4,096 box/entry/census keys, depth16. The weighted-row/long-AAC cost was provisionally accepted; other compatibility still needs acceptance. Include normal AAC/ALAC/PCM, M4A gain/artwork, fragment/index seeking/reconnect and ordinary audio regressions. Unusually large legitimate files may be refused; Ogg can grow one page-run65,025bytes beyond its delivery budget before refusal and old/new buffers may coexist. No sound-level, physical routing, timing or universal memory claim.

#401 latency, #646 historical refusal and #671 buffering stay qualified follow-ups. MP4 [#720](../audits/issues/720.md) is now staged here with its own acceptance/limits; raw #721 only characterizes the dependency. WAV #723 is now included: proposed64KiB fmt and16MiB writer limits, normal PCM/float/A-law/mu-law/extensible/IMA/RF64 and seek/retry acceptance remain. Matroska #726 is now staged: proposed4MiB element/16MiB metadata/4,096-entry/64KiB sniff,128 observed track keys including0,4,096 chapter UIDs and65,536 cue positions. On2026-10-27 the user provisionally accepted4,096 retained native master frames with a clear long/live-WebM restriction. The later4MiB text-only Block refusal, unused-subtitle policy and64KiB lacing-header limit were approved on2026-10-28; device compatibility remains pending. Native header/unknown-size behavior is source/JVM evidence, not audio/whole-heap certification. Decoded ID3 #729 and FLV #737 are staged here; bounded optional-text transport #738 is now staged with separate actual-platform acceptance pending. No new frozen-RC gate or installation is implied.

## Attribution / ownership

Allocated Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / explicitly High integrated the original eight fixes and authored #718's container guard; runtime confirms model, not effort telemetry. It source-reviewed root's played-claim ordering, saved-cover cleanup and later MP4 nested/MFRA/fragment source with scoped limits recorded on those PRs. Root owns subsequent corrections/default-size controls; no whole-candidate independence is claimed. GPT-6 / Codex desktop owns the remaining inputs/refreshed integration, original synthetic fixtures, design constraints, source corrections, documentation/conflict resolutions and CI/report checks; exact variant/effort not reported. Per-topic PR attribution remains authoritative. Shared authorship/reviewer fixes are not wholly independent review of every component. Opus independently reviewed root1003cad2 ID3/renderer code and found the invalid-encoding delimiter undercount. Root confirmed/fixed that counter and added two real-parser regressions; those follow-ups passed .1552 and are root-self-reviewed. No whole-candidate review or runtime acceptance is claimed. Verify allocated-session ownership before reuse. Root is the only writer of this combined branch.

FLV integration attribution: Opus5.5 High authored the original guard/tests; root assessed source first, supplied fixture, corrected output/wide-position/cancellation/test-policy evidence and integrated it. Opus reviewed root corrections and self-checked its own code; final public-test-hook control/root integration are self-reviewed. Pinned ParsableByteArray test-only enforcement initially invalidated a raw expectation; .1569 confirms both raw production-equivalent stale duration and guarded refusal. No full-candidate independent review or device claim.

JSON integration attribution: Opus5.5 / Claude Code / explicitly High implemented #741 after root source assessment. Root independently checked the implementation against AOSP SDK28/34 JSON semantics, added a field-alignment comment, inspected standalone reports and performed this integration. The final comment/integration are root self-checks; no whole-candidate independence or runtime acceptance is claimed.

Integration attribution: root resolved timed-output/seek and service-factory conflicts and strengthened the supplied-factory test. Opus authored the timed guard on its topic branch; root authored the text-packet and unused-parser changes. These integration corrections are root-self-reviewed, not independent whole-candidate review. No phones or installed builds changed.

Queue-feedback integration attribution: GPT-6 / Codex desktop authored/source-self-reviewed #745 and checked its automatic MuonApp merge. Actual pinned Material action/mutex/cancellation APIs were checked; no independent whole-candidate or rendered/device acceptance is claimed. The old-session Opus JPEG attempt did not complete; a subsequent fresh scoped review did, with limits recorded on #722. Fresh Opus independently reviewed queue6e6, found the missing full-player host, and rechecked root40364752 without a remaining blocker in that delta. No whole-candidate or device approval is claimed.

Artwork-contrast integration attribution: GPT-6 / Codex desktop authored#747 and strengthened the existing palette test, checked pinned Material Button source and actual standalone reports, resolved the issue-index conflict and performed this integration. Author self-check; no independent review or rendered/device acceptance.
