# Timed ID3 pre-decoder buffering characterization

Updated 2026-10-28. Owner [#704](https://github.com/averylicious/muon/issues/704); isolated `codex/timed-id3-buffer-oct28` starts at main `20579cf1a056b9c4a095aa860355f624877122de`. Test-only post-RC source investigation; no runtime change, new release gate, phone or user file access.

## Question and source evidence

The existing leading-prefix admission and later decoded-frame guard cover different boundaries. Pinned Media3 1.11.0 `Id3Reader` (TS stream type ID3 via `DefaultTsPayloadReaderFactory`/`PesReader`) and `AdtsReader(exposeId3=true)` send an ID3 body to `TrackOutput.sampleData` before emitting `sampleMetadata`. `SampleQueue`/`SampleDataQueue` allocate native sample storage as those bytes arrive. A renderer/decoder guard therefore cannot refuse a partial timed tag before this queueing.

Two characterization methods feed the actual pinned readers into an actual `SampleQueue` and `DefaultAllocator`. A valid 8 MiB ID3 declaration receives only 5 MiB of body using one reusable 64 KiB input. Expected witness: at least 5 MiB of live queue allocation with zero committed samples; release returns live allocations. Audio output is discarded. No giant declared array, network, audio preparation, decoder or OOM attempt is used.

**Not yet verified:** CI is the first Android compile/execution. This direct native-component witness bypasses `ProgressiveMediaPeriod` and `DefaultLoadControl`; it cannot establish unbounded whole-player allocation or a production crash. Pinned load control checks its allocator target, buffered duration and heap headroom. A full loading-path assessment is required before making a whole-player resource claim or choosing mitigation. A suggested per-track staging buffer also needs aggregate accounting; it must not create a new multiplying allocation.

## Source integrity and next action

Published Google Maven Media3 1.11.0 extractor sources SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`; exoplayer sources `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. Checked actual reader constructors, header/body forwarding, SampleQueue format/write-index/release contracts and DefaultLoadControl stop conditions. These checks authenticate local content consistency, not a complete tool provenance chain.

Run existing Android APKs CI, inspect both actual reports, then assess the actual progressive loading loop and supported interception points. Reuse #704 rather than opening a duplicate finding. No compatibility refusal policy is approved by this characterization.

GPT-6 / Codex desktop implemented and source-self-reviewed (exact variant/effort not reported). Independent review pending; no local Android build or device acceptance.
