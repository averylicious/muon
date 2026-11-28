# Matroska subtitle sample scratch — source characterization

Updated2026-10-28. Existing owner [#726](https://github.com/averylicious/muon/issues/726); raw characterization branch `codex/mkv-allocation-proof-oct27` / draft #727. Post-RC source/CI work, no runtime or frozen-RC change, phone or user file access.

Pinned Media3 1.11.0 `MatroskaExtractor.writeSubtitleSampleData` allocates `samplePrefix.length + 2 * size` before reading a SubRip/SSA/WebVTT sample body. Its own pending subtitle scratch is outside SampleQueue allocator accounting. This is separate from the known metadata-element/codec-private and timed-ID3 boundaries. Existing #728 deliberately leaves ordinary Segment/Cluster/audio Block lengths uncapped; no full subtitle-sample bound is claimed there.

One modest real-default-extractor witness declares a32KiB subtitle payload but supplies only its valid EBML/Info/Tracks/Cluster/Block headers. A forwarding input observes the actual native target array before body EOF. Expected: native extraction-time-transcoded cue output whose codecs retains SubRip and scratch between64KiB and64KiB+128 bytes, with a fixture below128bytes. No giant declaration, audio decoding, memory pressure, OOM or device incident is attempted. CI is the first compile/execution; result pending.

Next: inspect actual report and determine whether known text-track Block headers can be checked before native scratch allocation using supported output/input wrappers. Preserve audio blocks, lacing, unknown tracks, seek/reopen, default native identity and aggregate accounting. Do not impose a whole-audio cap, claim arbitrary heap control or a #671 diagnosis. A proposed compatibility limit needs separate review/acceptance.

Published Google Maven extractor1.11.0 sources SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`, actual binaryElement→writeSampleData→writeSubtitleSampleData path inspected. GPT-6 / Codex desktop implemented/source-self-reviewed; exact variant/effort not reported, independent review pending.

Source follow-up: default factory enables text-track transcoding. Root checked actual SubtitleTranscodingTrackOutput.format and kept that default: the fixture expects APPLICATION_MEDIA3_CUES with APPLICATION_SUBRIP codecs, rather than disabling transcoding to obtain the expected format. First1cda2a0b run is superseded by this source-corrected fixture; no pass inferred. No allocation assertion changed.

## PGS derived buffers — additional native characterization

Pinned DefaultSubtitleParserFactory supports PGS and returns PgsParser. With extraction-time text transcoding enabled by default, Matroska PGS samples can reach it before downstream queue/renderer limits. PgsParser calls Util.maybeInflate/Util.inflate, whose output capacity doubles until zlib completion; an encoded sample cap alone does not bound this derived buffer. A base picture section also calls bitmapData.reset(totalLength-4) from a24-bit declaration before picture bytes arrive. Bitmap pixel-array sizing is a further separate source question, not exercised here.

Two small actual-factory witnesses are added: less than1KiB compressed data inflates to about256KiB of valid unknown PGS sections, with no bitmap output; and a14-byte base picture section reserves32KiB of missing picture data. Test-only reflection observes actual native arrays, not hypothetical counters. First latest-head CI remains pending. No runtime patch, giant data/OOM probe, incident claim, compatibility choice or new release gate. These cases distinguish encoded input, native derived arrays and whole-player behavior; a solution still needs source review of supported subtitle-factory hooks and valid audio/text compatibility.
