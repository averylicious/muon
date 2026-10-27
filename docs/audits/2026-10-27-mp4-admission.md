# Ordinary MP4 admission candidate

Post-RC work for [#720](issues/720.md); not part of frozen .1420. Main20579cf1, with verified FLAC/Ogg #718 and raw MP4 #721 prerequisites. Source reviewed against official Media3 extractor1.11.0 JAR SHA256 `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`. No dependency/flag/order/identity changes or library fork. CI is the first Android compile; no phones or user media.

## Admission before the real parser

`PlaybackExtractors` forwards ordinary `Mp4Extractor` reads through a stateful input observer. The actual8-byte header is checked before Media3 allocates a parsed leaf. Extended sizes, parent/file ends, overflow, size0, the pinned small-free workaround and meta headers retain their relevant semantics. Parsed leaves share a32MiB retained-byte budget,4,096 parsed-box limit and depth16. Whole mdat/file size is not capped. Seek0 resets; nonzero source reopen keeps pending state.

The real leaf array is checked after its body read and before native parsing. Sample/offset/timing/edit/description counts need physical backing. At moov completion, all tracks share a weighted table-row budget, including primary/edit/accumulated tables and rechunk arrays. A large unparsed final child can trigger native moov completion without another read; admission therefore also runs at that child's header.

For one proven raw sample description with no codec children, the guard uses a conservative PCM frame/rechunk bound, following the actual QuickTime/ordinary prefix. **Ambiguity is not assumed safer:** multiple descriptions, encryption or children can still select raw indexing independently of a small stsz count. Other fixed tracks are charged against both normal and worst-case chunk-based allocation paths. Unknown formats may be over-counted/refused. No byte-substring codec classification or hidden table-omission flag.

Format detection is separate: both plain and fragmented MP4 candidates use the pinned Sniffer. Its moov/uuid search can grow the peek buffer and its ftyp array is allocated before a payload peek. Peeks/advances are bounded before input-buffer growth; an ftyp over64KiB is refused at its header. These are resource policies, not assertions every oversized file is malformed.

## Proposed policy and compatibility

Defaults are engineering proposals requiring later acceptance:32MiB metadata/peek window,1,048,576 weighted table rows,16MiB declared sample cap,64KiB ftyp,4,096 parsed boxes and depth16. Roughly two row units per ordinary sample, or three with a single media edit, means long48kHz/1024-frame AAC can be refused around2–3hours. PCM with ambiguous descriptions may be refused earlier despite efficient actual rechunking. These costs are explicit; no long-audiobook or universal heap/decoder guarantee. Decoder overhead, nested allocations and other containers are not silently cleared.

The sample cap does not bound every downstream maxInputSize/decoder allocation. Pinned TrueHD grouping is considered for known entries; codec changes/encryption need further review. Unusual huge metadata padding may fail during sniff. A rejection is a permanent content/resource failure through existing player handling, not an infinite metadata retry.

## Verification and remaining work

Nine focused real-extractor cases (CI pending) cover AAC/ALAC/PCM plus fragmented compatibility controls, faststart/tail layouts, seeks/reopens/seek0, header/parent/length limits, backed counts and shared-track cost, large-final-child behavior, edits, simple PCM, ambiguous PCM tables and real header/body interruption. The ambiguous controls use at most64 native rows, not heap pressure. Root-generated50ms silence/PNG fixtures have committed hashes/provenance; no user music or CI FFmpeg. Raw dependency characterization remains separate.

**Still engineering:** nested keys/stsd codec/esds/udta/ilst declarations, and fragmented read-time admission including default MFRA. The default factory adds FLAG_READ_MFRA_FOR_SEEK_MAP; a bare constructor is not the app's whole path. Ordinary auxiliary/SEF modes and chapter/codec internals remain qualified. Do not call #720 complete or merge this as whole-MP4 clearance. Later acceptance needs normal tagged/saved/streamed audio, seeking/reconnect and a policy decision before installation.

Claude Opus5.5 / Claude Code / High requested authored the initial guard and eight tests; author self-check only. GPT-6 / Codex desktop independently checked the design/source, identified sniff and ambiguous-PCM gaps, supplied fixtures, corrected admission/test setup, added the real ambiguous-path regression and owns final validation. Exact Codex variant/effort unreported. A later capped Claude correction attempt ended without edits; it did not verify root's final correction. No independent whole-candidate review or phone acceptance claimed.
