# Proposed Matroska text Block admission — #726 / PR #728

Updated2026-10-28. Existing `codex/mkv-metadata-admission-oct27` includes verified raw characterization24f5f59b/#727 plus a new proposed runtime check. Post-RC isolated work; no phone/install/frozen gate, original byte rewrite, parser fork, dependency or release change. Compatibility policy is not yet user-accepted.

## Source and implementation

Pinned MatroskaExtractor.writeSubtitleSampleData reserves a sample prefix plus twice a text sample's size before its body arrives. Supported subtitle-factory configuration runs later and cannot prevent that scratch. The observer now captures native output text-track IDs (bounded by the existing128-track policy), and watches the fixed8-byte VarintReader scratch for a Block/SimpleBlock's track number. For a known text ID and declared Block length above existing elementBytes4MiB, refuse before native subtitle scratch allocation. No added data read, peek reset, payload transformation, per-sample ledger or payload buffer. Source track-number casting/default0 follows pinned native behavior, with all1–8-byte widths handled.

Audio/unknown-track Blocks, Segment/Cluster lengths, native factory/flags/transcoding, timestamps/lacing and other output behavior stay native. Seek resets the pending track-varint state while retaining text IDs just as native retains tracks. This is a conservative per-text-Block cap including framing/lacing, not a whole-audio or per-cue-only cap. An unusually large legitimate text block would reject the stream with the fixed embedded-information notice. PGS decompression/bitmap buffers remain separate and are the subject of proposal #743; no universal heap bound or OOM/#671 cause claimed.

## Verification plan

Two new SDK34 actual-default-extractor cases: Block and SimpleBlock refusal with1/2/8-byte track numbers, native missing-number0, known/unknown source length and actual seek; no native32KiB target observed before a scaled64-byte cap. Positive controls preserve a32KiB audio sample and ordinary native-transcoded small SubRip delivery; unknown track remains native EOF/skip. Existing AAC/Opus/Vorbis/seek/census/master-retention cases and all raw subtitle/PGS witnesses stay intact. Actions is first compile; nothing built/run locally. No decoded audio/visual/OEM claim.

Google Maven extractor1.11.0 sources SHA25676404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e: actual binaryElement track-varint read, default/missing Track.number0, writeSubtitleSampleData, default transcription output and VarintReader fixed scratch checked. Existing observer is deliberately tied to pinned read shapes; arbitrary custom parser implementations are not covered. GPT-6 / Codex desktop implemented/source-self-reviewed; exact variant/effort not reported, independent review pending. No Claude used for this delta.

## Disposition

On 2026-10-28 the user approved later maintenance to keep the text-only 4 MiB packet limit and document that unusually large legitimate captions may stop a stream. This is policy approval, not device acceptance or a change to the frozen RC. Refreshed combined CI is required.
