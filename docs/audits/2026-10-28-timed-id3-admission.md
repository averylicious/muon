# Timed ID3 sample admission — #704 / PR #729

Updated 2026-10-28. Branch `codex/id3-frame-admission-oct27` (root's; follows #704 and #705). Post-RC; frozen RC .1420 is unchanged. The refusal policy below is a proposed maintenance compatibility change, **awaiting acceptance**. No phone, OOM, exploit or #671 cause has been demonstrated.

## Source finding (pinned Media3 1.11.0)

- **TS.** `Id3Reader.consume` (lines 90–124) sends every byte of a timed ID3 tag, header included, to the metadata `TrackOutput` (the `SampleQueue`) as it arrives. It commits with `sampleMetadata(…, size, offset 0, …)` only when the tag is complete (`packetFinished`, lines 127–137).
- **ADTS.** A mid-stream ADTS tag takes the same route: the 10-byte header, then the body, then `sampleMetadata(…, 0, null)` (`AdtsReader` lines 477–480 and 543–551).
- **Late decoder boundary.** The declaration is not a pre-queue admission check; partial bytes can accumulate before a complete sample reaches the decoder. ProgressiveMediaPeriod interval gates and DefaultLoadControl allocator/heap limits qualify whole-player accumulation. A data-aligned `packetStarted` restarts the reader without any output call, so abandoned bytes stay uncommitted in the queue.
- **What happens next** (`ExoPlayerImplInternal` lines 1553–1580): `PLAYBACK_STUCK_AFTER_MS = 4000` reports `STUCK_BUFFERING_NOT_LOADING`. The source shows a possible queued copy plus a decoder copy, and the earlier refusal here is clearer. When all detector predicates hold it can fail playback after four seconds; no indefinite-buffering guarantee or demonstrated OOM is claimed.

## Change

**`TimedId3Admission.kt`.** For outputs whose format is `APPLICATION_ID3` on the actual `TsExtractor` and `AdtsExtractor`, it counts the bytes sent since the last commit.

- **Size refusal.** It refuses with `StreamMetadataRefusal`, the existing large-tag notice, **before** forwarding a write past the cap: 4 MiB by default, an optional last constructor parameter.
- **Partial reads.** A `DataReader` write requests at most the remaining room. End-of-input isn't counted, and a zero-length write keeps the native contract.
- **Malformed refusals,** raised before the native queue sees them:
  - a negative write length;
  - a metadata range that is negative or larger than the bytes written since the last commit.
- **After a commit,** the count becomes the commit's `offset`, so any later bytes stay counted.
- **Repeated format.** A repeated `format()` keeps the count.
- **Seek.** An extractor seek clears every count lazily through one shared generation, with no registry, map or buffer.
- **Untouched.** Audio and other outputs are returned unwrapped, and other metadata formats pass through.

**`PlaybackExtractors.kt`.** `MetadataBoundedExtractor` adds `init` (the output wrapper) and `seek` (clear, then forward) for TS and ADTS only. Initial ID3 prefix admission is unchanged; root reconciles combined branches separately.

## Tests (`TimedId3AdmissionTest.kt`, Robolectric SDK 34)

The cap is scaled to 256 KiB, and the readers feed actual `SampleQueue`s.

1. **Real `Id3Reader`:**
   - a raw 1 MiB uncommitted allocation, versus a guarded refusal within the cap plus one 64 KiB allocation;
   - after seek, three small tags commit;
   - a truncated data-aligned restart stays counted and is refused;
   - after another seek, the same size is admitted.
2. **Real `AdtsReader` with ID3 exposed:** audio and metadata outputs are created and audio is unwrapped; a complete tag commits; an oversized mid-stream tag is refused within the cap and nothing more is committed.
3. **Write/commit contracts** with a recording output and a cap of 10:
   - a short read is counted, end-of-input isn't, a zero-length write is forwarded, and only the remaining room is requested;
   - a repeated format keeps the count;
   - negative lengths and invalid ranges are refused without forwarding;
   - a valid offset commit keeps the trailing bytes counted;
   - seek clears the count, and EMSG passes through.
4. **The default factory's `AdtsExtractor` through `MetadataBoundedExtractor`:** the tracks reach the outer output, a mid-stream 1 MiB tag is refused within the cap with no commit, and seek and release are forwarded.

**First compile and test run: PENDING (root GitHub Actions).** Nothing was compiled or run locally.

## Limits and open contract

- **TS source contract checked by root.** Actual pinned TsExtractor.resetPayloadReaders is called only by its constructor; PmtReader checks trackIds before creating/initializing a reader and sets each ID before init. The special HLS ID3 reader is initialized once; progressive seek retains readers and calls their seek. ADTS creates its outputs once in init. Therefore ordinary pinned native repeated PMT parsing does not silently recreate a counter over an existing pending queue. No custom-factory arbitrary reinitialization guarantee is claimed.
- **Over-counting risk.** Paths that reset the native queues without an extractor seek, such as some track-disable resets, leave the count conservatively high. That could falsely refuse a later large tag.
- **Proposed trade-off, pending acceptance:** an unfinished timed tag over 4 MiB is refused rather than silently dropped.
- Not covered: the decoder, the renderer's copy, the heap, and other extractors.

## Attribution

- **Implementation (helper, wiring, tests, this file):** Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, High effort requested (the runtime does not report effort).
- **Source assessment and corrections, including `ExoPlayerImplInternal` stuck detection and the added validation requirements:** root (GPT-6 / Codex).
- **Review:** pending, root.

Root corrected the fixtures to release actual native queues via use() (preserving original assertion failures), release the extractor in finally, and reset queued samples alongside seek. Pinned SampleQueue.reset() calls reset(false), preserving upstream format; the earlier claim that it clears the format was incorrect. No functional refusal, ordinary-metadata or byte-budget assertion was weakened. Root independently reviewed helper/wiring against actual pinned TrackOutput/TS/ADTS sources; these fixture/doc corrections are root-authored/self-reviewed. First Actions compile remains pending. The proposed wrapper is constant-state per existing native output and allocates no payload buffer or reset registry.
