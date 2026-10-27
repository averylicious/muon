# Container-admission fixtures

Original synthetic mono silence (48,000Hz,0.05seconds), generated for this repository; no user's library or third-party recording. The transparent1x1PNG is generated from a PNG signature, IHDR/IDAT/IEND chunks and standard-library zlib/CRC32.

All three containers include REPLAYGAIN_TRACK_GAIN=-6.00dB and a METADATA_BLOCK_PICTURE comment containing that PNG. Demux/metadata/seek controls do not establish device audio quality or actual memory pressure.

Generator: ffmpeg version n9.0.2 Copyright (c) 2000-2026 the FFmpeg developers. For each format, use ffmpeg with a lavfi anullsrc=r=48000:cl=mono input, duration0.05, -map_metadata -1, -fflags +bitexact, -flags:a +bitexact, and the two metadata values above. Codecs: flac; libopus at24k with vbr off; libvorbis q0. Reconstruct the picture value as base64 of a type3 FLAC picture block, image/png MIME, empty description,1x1,depth32,colors0, and cover.png bytes.

The encoded files are committed fixtures; CI does not need FFmpeg. Native FLAC's existing padding can be replaced/expanded in controlled tests without changing its encoded audio frames.
