# Nested JPEG motion-photo MP4 admission (#720)

Post-RC maintenance, not a frozen .1420 gate. No phone, user-media or network access.

## Confirmed boundary

Pinned Media3 1.11.0 JPEG motion-photo extraction constructs its own ordinary Mp4Extractor, outside the app factory. Root's native-only #721/af158/.1607 witness passed both variants. Then actual default factory / BundledExtractorsAdapter / Muon forwarding-wrapper comparison `a918ebb7ed6b7cfe0b101cafd21d1c39dda9c97a` / [Android .1610](https://github.com/averylicious/muon/actions/runs/40671821489) passed downloaded1,862cases per variant, zero failures/errors/skips; lint0errors/65warnings plus3hints. Under an injected128-byte budget, direct MP4 refused but the JPEG route reached the native4096-byte parsed-leaf target. The generated XMP+movie is under8KiB; a controlled stop prevents body copying. Report ZIP SHA256fbf2be7269e7a1e712ba61032d0ee6c60be7b20135a5dec0306e7fce20f74e46 matches API. Canary0.1.0-canary.1610 [app-debug-a918ebb7ed6b7cfe0b101cafd21d1c39dda9c97a](https://github.com/averylicious/muon/actions/runs/40671821489/artifacts/12809681590), uninstalled. No OOM/image/audio decode or #671 diagnosis.

## Draft correction

JpegMotionPhotoAdmission preserves native JPEG selection/options, image/motion-photo behavior and native bytes/positions/maps. It uses only public forwarding input/output interfaces and MotionPhotoMetadata; no private production reflection, library-restricted offset helper, parser fork or generic format filter.

- Bound both native preamble sniffs (selection and first read) to32MiB requested lookahead. Native array growth headroom is not byte-exact heap accounting. This additional rare-large-preamble refusal is a maintenance compatibility proposal needing later acceptance.
- Recognize the pinned inner Sniffer's first8-byte allow-EOF peek into its64-byte scratch. JPEG marker/XMP peeks use different shapes. Apply existing Mp4SniffInput on a relative observation view **before** native ftyp payload allocation. Retry creates a new transient sniff view.
- Before reading MP4 atoms, cross-check the observed start against the native image format's public MotionPhotoMetadata.videoStartPosition. Reuse Mp4MetadataAdmission with relative read/peek/length positions. Native caller positions and data remain untouched. The native metadata publication happens after sniff, so a format-only hook would be too late for ftyp allocation.
- Seek0 clears the nested offset/reader state; nonzero seeks retain admitted native tables. Existing wrapper seek/output observers stay composed. No new payload copy, ledger, async work or metadata scan.

Four native controls now require leaf and inner-ftyp refusal before their destination/body operations; run an original valid nested M4A through actual default-factory audio/map/seek/restart and a genuinely triggered source interruption/reopen; exercise both preamble-sniff limits and the unchanged plain-image single-sample route. The old a918 characterization remains immutable; its positive unchecked expectation is deliberately replaced by the desired guard assertion. Raw native #721 remains a separate witness. CI is the first compile of this correction; earlier passes do not verify it.

## Limits

The recognition depends on the pinned JPEG/MP4 read sequence and needs rechecking on a library upgrade (JPEG classes are unchanged in1.11.1). This is ordinary inner MP4 admission only, not every nested container, standalone image sample/decoder, codec-private array, transient GC or whole-process bound. Generic image formats and album-art loading are not silently removed or changed. Runtime compatibility and large-preamble policy still need acceptance; no installed app changes.

Implementation/source self-check: GPT-6 / Codex desktop (exact variant/effort not reported). No Claude contribution to this delta, independent review or phone result.
