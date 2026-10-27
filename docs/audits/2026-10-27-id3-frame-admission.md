# Derived ID3 metadata allocation follow-up

Owner [#704](issues/704.md), inspected main20579cf1 plus verified prefix guard #705/9f4cc1ca. Separate post-RC source work, not frozen .1420 or #701 engineering already accepted. The4MiB prefix check admits declared tag bytes before the peeker array, not all decoded tables, strings or chapter recursion.

Official extractor1.11.0 source SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`: Id3Peeker, metadata/id3/Id3Decoder, mp3/Mp3Extractor and MlltSeeker. Official exoplayer1.11.0 source SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`: upstream/Loader. No new app/test dependency or source fork.

## Confirmed source boundaries

- Id3Decoder.decodeMlltFrame derives references from encoded body bits divided by the two declared deviation widths, then allocates two int arrays. One-bit widths are accepted by this implementation. Default Mp3Extractor may build two more long arrays in MlltSeeker.create from the decoded count. Existing prefix admission does not bound decoded reference rows separately. Frame byte backing is not a practical decoded-memory budget.
- decodeChapterFrame/decodeChapterTOCFrame recursively call decodeFrame. No explicit depth/frame census was found in the inspected path. Native catches OutOfMemoryError and Exception around a frame, but not arbitrary Error; Loader separately catches Exception/OOM and sends other Error as fatal. This is source/control-flow evidence, not a reproduced stack overflow or phone crash.
- TextInformationFrame decodeStringValues can retain many separately decoded nonempty values from one backed frame. The tag-byte cap bounds input bytes but not a small object/value count.

## Small characterization

Three raw-library cases: a4KiB packed MLLT through the real default MP3 extractor, inspecting the two deviation arrays plus two actual seeker arrays;12 shallow backed chapters ending in a title; and1,024 nonempty artist values from a roughly2KiB tag. MPEG controls contain synthetic frame headers, not valid encoded audio; there is no decoder/sound claim. Arrays remain modest, no giant positive allocation, OOM/stack pressure, phone/user media or #671 diagnosis.

Raw653867a4 / [.1542](https://github.com/averylicious/muon/actions/runs/40530609094) passed downloaded1,829cases/variant, zero failures/errors/skips and lint0errors/65warnings plus3hints; all three raw cases executed in both. These actual reports confirm the safe-sized control paths, not an attempted failure on a phone.

## Initial-peeker guard, first compile pending

The new public input wrapper inspects the actual Id3Peeker body array after its complete peek, before native decode. Prefix admission still bounds its allocation before that peek. A private bounded working copy preserves native input/unsynchronization; no private API or decoder fork. Proposed limits:4,096 prefix tags,4,096 decoded frames,16 nested chapter levels,4,096 text/CTOC values and1,048,576 total MLLT reference rows per decoded tag. MLLT's two int arrays and the selected first seeker's two long arrays are thus bounded separately from encoded bytes; this is not a universal heap/GC/externally retained metadata bound.

Header/extended/footer handling follows supported2/3/4 layouts; top-level validation retains native v4 raw-size fallback and ignored invalid tags. Compressed/encrypted frames keep native skip behavior. The scanner handles global pre-v4 and per-frame v4 unsynchronization on its own copy. Chapter children must stay inside their parent and have complete headers: this is deliberately stricter than native malformed-data tolerance, preventing cross-parent uninspected allocation. Group/data-length flags retain their effective-body offsets. Refusal messages remain fixed/sanitized.

Six actual-default-MP3 tests cover table admission/refusal on sniff/read, tight frame/depth/value limits, version/extended/footer/raw-size compatibility, original binary bytes after both unsynchronization forms, ignored unsupported frames/top-level damage, nested parent crossing, prefix-tag census and a failed body peek followed by larger changed data. Synthetic MPEG headers are not encoded audio; no rendering/phone claim. Initial production compile/reports still need verification.

## Remaining boundaries

Verify/fix the new guard compile and actual reports, preserve ordinary TXXX/ReplayGain/artwork/seeking, then inspect combined integration and obtain later policy/runtime acceptance. Do not claim a header-only helper fixes decoded metadata, or that initial peeker coverage covers later ADTS/HLS/in-band metadata renderers. Native unsynchronization uses repeated array shifting; this guard models bytes/objects, not a certified CPU deadline for that separate path. Guard scope/policies and later runtime acceptance remain explicit.

A separate source question remains in HEVC configuration/VPS/SPS derived arrays (NalUnitUtil, also called by MP4/Matroska video-track initialization). Byte caps alone do not certify those codec paths. No unsafe allocation probe or new finding about normal user files is claimed from that question; assess and record independently rather than silently expanding this ID3 implementation.

GPT-6 / Codex desktop source assessment, synthetic characterization and author self-check; exact variant/effort not reported. No Claude inspection of this follow-up. No installed RC, package/signing/release, phone or user music changes.
