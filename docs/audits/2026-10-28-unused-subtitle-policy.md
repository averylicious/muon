# Proposed music-client subtitle policy — #726

Updated2026-10-28. Isolated `codex/unused-subtitle-parsing-oct28`, main baseline20579cf1a056b9c4a095aa860355f624877122de. **Proposal pending user choice and CI**, not integrated/installed or a new frozen-RC gate. No phones or existing files changed.

Muon has no caption UI or onCues consumer, yet the native default factory transcodes supported embedded subtitles before downstream queue/renderer limits. Source/characterization #727 identifies native PGS inflation and declared bitmap buffers; native text-sample scratch is a separate boundary. These are resource behaviors, not a demonstrated OOM, exploit, privacy incident or #671 cause. Published1.11.1 retains the PGS/transcoding code and subtitle scratch path; no dependency upgrade attempted.

## Proposal

Use the pinned public built-in SubtitleParser.Factory.UNSUPPORTED via DefaultMediaSourceFactory.setSubtitleParserFactory, and disable C.TRACK_TYPE_TEXT in the player's existing track-selection parameters before any prepare. Both are needed: pinned TextRenderer still advertises raw subtitle-format support and would fail when legacy decoding is disabled if such a track were selected. Preserve default renderer slots, other track selection, extractors/options, audio/video, ReplayGain, ID3 metadata, lyrics/UI, original stream/saved bytes and release channels. No parser fork, new dependency or custom factory abstraction.

This avoids unused extraction-time subtitle decoding and derived PGS buffers. It does **not** prevent Matroska's separate raw text-packet scratch allocation, every native demuxer/codec allocation, or arbitrary same-process code changing parameters later. Larger text blocks still need a narrow admission assessment or explicit unresolved disposition. Caption-only media is not a supported music use case. Revisit the policy if Muon adds visible subtitle support.

Three SDK34 Robolectric cases exercise actual pinned public contracts: media-source factory propagation through a supplied forwarding factory to the actual Matroska instance; actual SubtitleTranscodingExtractorOutput raw PGS passthrough with untouched audio; and actual DefaultTrackSelector keeping audio while disabling a raw PGS track it otherwise selects. Controlled audio capability avoids hardware decoder claims. These tests do not launch PlaybackService or prove audio/OEM behavior; production wiring is source-reviewed and CI is the first compile. No local Android build.

Checked published1.11.0 DefaultMediaSourceFactory/ProgressiveMediaSource, ForwardingExtractorsFactory, SubtitleParser.Factory.UNSUPPORTED, SubtitleTranscoding output, TextRenderer support/legacy trap and DefaultTrackSelector activation/disabled-track rules. Source SHA256: extractor76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e; exoplayer2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6. Factory return values/observer composition must be rechecked on later #701 integration.

GPT-6 / Codex desktop implemented and source-self-reviewed; exact variant/effort not reported. Independent review and actual audio/format/device acceptance pending. Claude not used for this proposal.

First a68e4e80 / .1603/40661001769 failed test compilation: the synthetic TextOutput lacked pinned onCues(CueGroup). No unit-test execution is claimed. Root checked the actual published interface and adds the no-op required callback, leaving production settings and every functional assertion unchanged. Fresh exact-head CI is required.
