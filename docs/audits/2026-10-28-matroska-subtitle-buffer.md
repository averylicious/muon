# Matroska subtitle sample scratch — source characterization

Updated2026-10-28. Existing owner [#726](https://github.com/averylicious/muon/issues/726); raw characterization branch `codex/mkv-allocation-proof-oct27` / draft #727. Post-RC source/CI work, no runtime or frozen-RC change, phone or user file access.

Pinned Media3 1.11.0 `MatroskaExtractor.writeSubtitleSampleData` allocates `samplePrefix.length + 2 * size` before reading a SubRip/SSA/WebVTT sample body. Its own pending subtitle scratch is outside SampleQueue allocator accounting. This is separate from the known metadata-element/codec-private and timed-ID3 boundaries. Existing #728 deliberately leaves ordinary Segment/Cluster/audio Block lengths uncapped; no full subtitle-sample bound is claimed there.

One modest real-default-extractor witness declares a32KiB subtitle payload but supplies only its valid EBML/Info/Tracks/Cluster/Block headers. A forwarding input observes the actual native target array before body EOF. Expected: valid SubRip track output and scratch between64KiB and64KiB+128 bytes, with a fixture below128bytes. No giant declaration, audio decoding, memory pressure, OOM or device incident is attempted. CI is the first compile/execution; result pending.

Next: inspect actual report and determine whether known text-track Block headers can be checked before native scratch allocation using supported output/input wrappers. Preserve audio blocks, lacing, unknown tracks, seek/reopen, default native identity and aggregate accounting. Do not impose a whole-audio cap, claim arbitrary heap control or a #671 diagnosis. A proposed compatibility limit needs separate review/acceptance.

Published Google Maven extractor1.11.0 sources SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`, actual binaryElement→writeSampleData→writeSubtitleSampleData path inspected. GPT-6 / Codex desktop implemented/source-self-reviewed; exact variant/effort not reported, independent review pending.
