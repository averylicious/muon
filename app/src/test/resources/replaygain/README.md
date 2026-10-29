# Original MP4 ReplayGain control

`tagged-gain.m4a` is original generated mono48kHz/50ms AAC silence, with no user audio or metadata. Root copied the original750-byte MP4 admission control (FFmpeg n9.0.2, anullsrc, metadata disabled, bitexact/no edit list/faststart), then used installed Mutagen1.48.1 on that fixture only to add the standard `----:com.apple.iTunes:` track gain/peak, unrelated album gain and an original1×1 transparent PNG. No music-library scan, re-encoding or new app dependency; CI reads committed bytes and does not need FFmpeg/Mutagen.

1,126bytes; SHA256 `da2b849735f2520d11a5605fdf1c693b3f48d6d48f1bbf8ed11024b7f67e2fdc`. The values intentionally request -2dB gain with peak2, to exercise the existing clipping/no-amplification path. The real pinned MP4 extractor must deliver InternalFrame metadata and preserve cover art.
