# Matroska metadata admission candidate

Post-RC [#726](issues/726.md), based on main20579cf1 plus verified #718 and raw #727. No phone/user media or frozen .1420 changes. No dependency, native extractor identity/order/flags/subtitle, package, signing, manifest, storage or publication changes. Official extractor1.11.0 sources SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`: DefaultEbmlReader, VarintReader, Sniffer and MatroskaExtractor.

## Initial production boundary

The actual factory MatroskaExtractor stays in place. Its public forwarded input observes DefaultEbmlReader’s real ID/size varint reads. Native VarintReader uses distinct allow-end first-byte reads for element IDs and sizes; sample/lacing varints use non-allow-end reads outside that header state. The observer admits a declaration after its last size byte arrives, before readString/binaryElement allocates. It retains unfinished varint state across I/O reopen; seek resets that state consistently with the native reader reset. No replacement processor/library fork or constructor-default reconstruction.

Exact native string IDs and non-media binary IDs are admitted against4MiB per element,16MiB aggregate declared payload and4,096 position-keyed high-water entries. Known stream and signed/overflow bounds are checked; SeekID also fits its actual fixed4-byte target. The bounded ledger keeps costs on seek because native track/chapter objects can remain retained. Rereading a prior payload position charges only growth. Unknown-length truncated bodies remain bounded and keep native EOF behavior. Segment/Cluster and media Block/SimpleBlock lengths are not whole-file/audio caps.

Sniff forwarding checks peek/advance size before DefaultExtractorInput can grow its buffer, with a64KiB window from the input position. This covers the unknown-length EBML header route in the raw proof. Existing ID3 admission still runs first. An invalid over-width/zero ID fails closed rather than entering the native byte-resynchronization path. That is a deliberate damaged-input compatibility difference needing later acceptance.

These are proposed resource policies, not a universal heap bound. Retained strings can also need character/object overhead. Ledger size is bounded, but track/cue/chapter/master object retention, private-codec nested counts, sample/lacing/subtitle/output buffers, decoder/GC overlap and external metadata copies are **not cleared by this first step**. Unknown-size masters need careful native live-WebM/seek compatibility assessment before imposing a nesting cap. Do not label source allocation evidence a device crash/exploit or #671 diagnosis.

## Verification and remaining engineering

CI is first compile. Five focused real-reader tests cover original tiny AAC/Opus default-factory sniff/format/reopen/seek, metadata refusal before payload-array reads on known/unknown lengths, unknown-length sniff refusal before actual native peek-buffer growth, aggregate/entry budgets and a failed size-varint read resumed with a larger delivered declaration. Fixtures are original silence or inline synthetic bytes, no large positive heap-pressure probe and no audio decode. Raw #727/.1535 separately passed all three dependency characterization cases in both variants.

Verify/fix this compile and actual reports, then continue track/cue/private-codec retention assessment before calling the Matroska guard complete. Normal saved/streamed Matroska/WebM playback, seeks/reconnects and policy compatibility need later explicit phone authorization/user acceptance. Keep draft and separate from #701 until deliberately verified/integrated. No Stable/frozen-RC gate is added by this candidate.

GPT-6 / Codex desktop source assessment, implementation, fixtures, tests and author self-check; exact variant/effort not reported. No Claude inspection of this Matroska work and no independent whole-candidate review.
