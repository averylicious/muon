# WAV allocation admission candidate

Post-RC [#723](issues/723.md), based on main20579cf1 plus #718’s extractor wrapper and raw #724. No phone/user media, dependency/signing/package/manifest/storage/Stable publication or frozen .1420 changes. Root owns `codex/wav-metadata-admission-oct27`; no competing writer. Source read against official extractor1.11.0 SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`, plus its common WavUtil. No library fork/flag/order/identity change.

## Actual header and writer boundary

WavHeaderReader peeks the fmt header, reads16 fixed fields, allocates the remaining extension, peeks it and commits using skipFully. The real header's unsigned little-endian length is admitted **before** that extension exists, against64KiB and known stream/overflow bounds. Unknown chunks and the actual data/audio length are not buffered or capped by this policy. On unknown length, the declaration cap still bounds allocation; a truncated bounded body can retain native EOF behavior.

The observer reads actual delivered fixed/extra fields before WavExtractor constructs its writer. Standard PCM/float/A-law/mu-law/extensible formats retain native supported-format handling, frame-size validation and a bounded published maximum input size. IMA’s frame-per-block relationship is checked and its input/decoded array sizes calculated with wide arithmetic before native arrays. Proposed16MiB applies to the input+decoded pair and separately to maximum published output; signed/int overflow cannot pass those admissions. No arbitrary song-duration/rate/channel limit is introduced. Unsupported formats keep native validation.

A successful field peek does **not** retire the guard. Only successful format skip/commit does: a failed extension peek reopens at the fmt header and may return different bytes. Nonzero reopens retain pending state; seek0 resets observation, while the native cached data-start fast path reuses its already accepted writer. This does not claim WAV reparses changed metadata on every seek.

RF64’s ds64 data-size body also uses an8-byte peek and can literally contain the bytes `fmt `. It is not a header: actual chunk-header peeks start at the read position. Positive controls preserve that distinction. Normal WAVEFORMATEXTENSIBLE resolution uses the delivered24-byte extension like native; this is not a replacement GUID/channel-mask codec parser.

Resource versus malformed refusals remain permanent ParserExceptions. Audio-format refusals use fixed short messages about the audio format, preserving existing tag/art messages for ID3/FLAC/Ogg. No paths/server text are exposed. PCM maximum-size and IMA array arithmetic are assessed; every native bitrate/decoder/codec arithmetic path and global heap/GC overlap are not certified.

## Verification

CI is the first compile. Four actual default-reader cases cover normal PCM/float/extensible/RF64/IMA and seek0/nonzero seeking; fmt header refusal before an extension-array body peek; moderate writer/input-budget acceptance/refusal; and successful changed-response re-admission after an interrupted unused PCM extension. The last check would catch retiring the guard immediately after fixed fields. Small inline original RIFF arrays only. IMA is format-only (no packet decoding); ordinary PCM is discarded without rendering. Raw #724 separately observes the unguarded dependency's actual modest arrays, not desired production behavior. No giant positive allocation attempt, decoder/device/sound claim or #671 diagnosis.

## Remaining

Verify/fix the new compile and actual reports, then inspect the combined maintenance integration separately. Resource/format compatibility acceptance remains future work; do not install over frozen .1420 or call this all-container/decoder clearance. Real WAV saved/streamed playback and seeking need later explicit phone authorization. WAV guard is not yet in #701; MP4 remains a separate candidate/prerequisite-independent topic.

GPT-6 / Codex desktop implementation/source assessment and author self-check, exact variant/effort not reported. Claude did not review this WAV change; earlier Opus MP4 reviews do not certify it. Independent review pending.
