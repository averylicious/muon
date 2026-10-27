# Original MP4 admission fixture

`control.m4a` is original generated mono silence, 48 kHz / 50 ms, AAC in ordinary MP4. It contains no user recording, personal metadata or artwork. Root generated it with installed FFmpeg n9.0.2 from `anullsrc`, metadata mapping disabled, bitexact flags, `-use_editlist 0` and `-movflags +faststart`. CI reads the committed fixture and does not need FFmpeg.

750 bytes, SHA256 `c32242dc980e6e1e140c2126b2b107ce1c57b46f7c75b331ff180e7286576bdc`. Its native fixed-size `stsz` is20bytes; the tests edit only finite count/timing fields in a copy. They demux metadata/seek maps and never decode audio or attempt a huge allocation.

## Additional original controls for admission

Root generated mono48kHz/50ms silence with FFmpeg n9.0.2, `-map_metadata -1 -fflags +bitexact -flags:a +bitexact`. `pcm.mov` uses pcm_s16le and `-use_editlist 0`; `alac.m4a` uses alac and no edit list; `aac-edit.m4a` uses AAC's default single edit. Those three use `+faststart`. `tail-*` counterparts omit faststart, keeping mdat before moov so finite metadata mutations preserve real chunk offsets. `tail-control.m4a` uses AAC/no edit, corresponding to the original unchanged control. No user media, downloads, audio decoding or CI FFmpeg dependency.

| File | Bytes | SHA256 |
| --- | --- | --- |
| `pcm.mov` | 5417 | `cba971de94f752161c2d05a3819de6811e2559ad81a6a44484d691f1e688b1cc` |
| `alac.m4a` | 677 | `3d5ed18023892b7fe4c7447a1157745984a86b8d5bb03b3044f6ef4b234a38af` |
| `aac-edit.m4a` | 786 | `b27b421e6ffb3f68683003de83d1c87069df3053bb397dc0492d7165bfcc4004` |
| `fragmented.m4a` | 879 | `fa3c96ae81b2306ee53e6e8a4875934f55e2efb3424fde53f146961b3862014e` |
| `tail-control.m4a` | 750 | `2bc0652a58c13cff6c9d883a254e2e4fe343d3b5e72ba97b8748b2d47518221b` |
| `tail-pcm.mov` | 5417 | `510acb096ca6330433651df8a0c9c78ad1fb924c807c04b9f24581034dbdd739` |
| `tail-alac.m4a` | 677 | `c501879e23156d25dba9fd5007613083f059fa807eb2779076e384dfa11c88aa` |
| `tail-aac-edit.m4a` | 786 | `5b6bbbbadf5d922251650fd00f18a3bfbea66975154cba20a001524cdcde3b4b` |
