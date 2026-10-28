# Nested MP4 admission boundary (#720)

This is a post-RC characterization, not a runtime restriction or a new frozen .1420 gate.

The pinned Media3 1.11.0 JPEG extractor constructs a separate MP4 extractor for motion-photo video. Muon's current `MetadataBoundedExtractor` selects its MP4 observer from the outer native extractor's identity. A nested instance does not pass through `PlaybackExtractors` again. #721's native-only witness passed both variants in run .1607; that does not by itself prove the application's wrapper route.

The new `NestedMp4BoundaryTest` compares a direct MP4 refusal under an injected 128-byte retained-leaf budget with a generated motion-photo route through the actual default factory, the same forwarding wrapper and `BundledExtractorsAdapter`. Both factory overloads wrap every native candidate. A JPEG response MIME hint selects that native candidate; the URL and bytes are entirely local fixtures, with no HTTP request. A controlled reader observes the modest 4 KiB native destination and stops before the body is copied. The fixture is under 8 KiB; it neither attempts an OOM nor decodes an image/audio/video. The test's nested-allocation expectation characterizes a suspected remaining gap; it is not a desired safety guarantee. CI is the first compile and execution.

No production JPEG/motion-photo policy, image selection change or new parser was implemented. Album-art loading is a separate route. No claim about ordinary Tauon responses, real-phone memory, exploitability or #671 follows from this fixture. If confirmed, disposition must preserve or explicitly decide motion-photo compatibility rather than silently removing a media format.

Implementation/self-check: GPT-6 / Codex desktop (exact variant/effort not reported). No Claude contribution to this delta, independent review, phone or user-media access.
