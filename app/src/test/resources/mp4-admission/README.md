# Original MP4 admission fixture

`control.m4a` is original generated mono silence, 48 kHz / 50 ms, AAC in ordinary MP4. It contains no user recording, personal metadata or artwork. Root generated it with installed FFmpeg n9.0.2 from `anullsrc`, metadata mapping disabled, bitexact flags, `-use_editlist 0` and `-movflags +faststart`. CI reads the committed fixture and does not need FFmpeg.

750 bytes, SHA256 `c32242dc980e6e1e140c2126b2b107ce1c57b46f7c75b331ff180e7286576bdc`. Its native fixed-size `stsz` is20bytes; the tests edit only finite count/timing fields in a copy. They demux metadata/seek maps and never decode audio or attempt a huge allocation.

`fragmented.m4a` is separately generated original mono48kHz/50ms AAC silence with the same metadata/bitexact settings and `-movflags +empty_moov+frag_keyframe+default_base_moof`.879bytes, SHA256 `fa3c96ae81b2306ee53e6e8a4875934f55e2efb3424fde53f146961b3862014e`. Its four-sample fragment carries default duration/size in `tfhd`. The raw fragment test replaces four duration words with those existing defaults and a modest4,096-sample count, observes actual table capacity before `mdat`, and never decodes audio or attempts a large allocation.
