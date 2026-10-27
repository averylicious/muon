# Derived ID3 metadata allocation follow-up

Owner [#704](issues/704.md), inspected main20579cf1 plus verified prefix guard #705/9f4cc1ca. Separate post-RC source work, not frozen .1420 or #701 engineering already accepted. The4MiB prefix check admits declared tag bytes before the peeker array, not all decoded tables, strings or chapter recursion.

Official extractor1.11.0 source SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`: Id3Peeker, metadata/id3/Id3Decoder, mp3/Mp3Extractor and MlltSeeker. Official exoplayer1.11.0 source SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`: upstream/Loader. No new app/test dependency or source fork.

## Confirmed source boundaries

- Id3Decoder.decodeMlltFrame derives references from encoded body bits divided by the two declared deviation widths, then allocates two int arrays. One-bit widths are accepted by this implementation. Default Mp3Extractor may build two more long arrays in MlltSeeker.create from the decoded count. Existing prefix admission does not bound decoded reference rows separately. Frame byte backing is not a practical decoded-memory budget.
- decodeChapterFrame/decodeChapterTOCFrame recursively call decodeFrame. No explicit depth/frame census was found in the inspected path. Native catches OutOfMemoryError and Exception around a frame, but not arbitrary Error; Loader separately catches Exception/OOM and sends other Error as fatal. This is source/control-flow evidence, not a reproduced stack overflow or phone crash.
- TextInformationFrame decodeStringValues can retain many separately decoded nonempty values from one backed frame. The tag-byte cap bounds input bytes but not a small object/value count.

## Small characterization, first compile pending

Three raw-library cases: a4KiB packed MLLT through the real default MP3 extractor, inspecting the two deviation arrays plus two actual seeker arrays;12 shallow backed chapters ending in a title; and1,024 nonempty artist values from a roughly2KiB tag. MPEG controls contain synthetic frame headers, not valid encoded audio; there is no decoder/sound claim. Arrays remain modest, no giant positive allocation, OOM/stack pressure, phone/user media or #671 diagnosis.

## Next engineering

Verify/fix actual reports, then prepare public-input admission of actual initial Id3Peeker body bytes before native decode: bounded frame/depth/value/reference-row costs, preserving ordinary TXXX/ReplayGain/artwork/seeking and supported ID3 versions/flags/unsynchronization. Unsupported/ignored native frames and v4 size fallback need source checks. Do not claim a header-only helper fixes decoded metadata, or that initial peeker coverage covers later ADTS/HLS/in-band metadata renderers. Guard scope/policies and later runtime acceptance remain explicit.

A separate source question remains in HEVC configuration/VPS/SPS derived arrays (NalUnitUtil, also called by MP4/Matroska video-track initialization). Byte caps alone do not certify those codec paths. No unsafe allocation probe or new finding about normal user files is claimed from that question; assess and record independently rather than silently expanding this ID3 implementation.

GPT-6 / Codex desktop source assessment, synthetic characterization and author self-check; exact variant/effort not reported. No Claude inspection of this follow-up. No installed RC, package/signing/release, phone or user music changes.
