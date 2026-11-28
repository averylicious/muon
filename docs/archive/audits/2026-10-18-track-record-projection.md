# Per-record tracklist projection

## Evidence and change

The controlled ordinary #608 .1235 50,000-track fixture sampled up to 304,983 KiB total PSS. This is not an absolute peak or proof that the JSON graph caused all of that memory. Source inspection separately confirms that `TauonApi.tracks` constructed a complete `JSONObject`/`JSONArray` graph before building its `TauonTrack` list.

The standard-JSON tracklist path now projects one record at a time through the platform `JsonReader`, discarding its record object afterward. `projectTauonTrack` is the extracted existing JSONObject field-conversion/title/metadata-admission code. Numeric lexical values pass through `JSONTokener` so its number types/coercions are preserved. Nested record/extension values use the same JSON value types and validation rather than silently ignoring malformed values.

The exact existing iterative structural preflight was separated from DOM construction and still runs first for all text, including ignored fields and overridden properties. The original 16 MiB response cap, whole-call timeout, cancellable response ownership, track count, depth/value/array and per-track/aggregate metadata limits are unchanged. Parsing stays on IO.

A strict-reader syntax failure uses the original bounded JSONObject compatibility path. This preserves platform lenient syntax rather than changing what server data is accepted merely for performance. Duplicate top-level tracks properties retain last-value behavior: earlier projection errors are deferred until the final value is known, while structural JSON failures still abort. No successful prefix is returned after a record/projection failure. Other metadata endpoints retain their existing DOM parser.

## Limits

This is not network streaming: the wire-bounded response text is still retained. A nested individual record or discarded unknown outer value can still build its own bounded DOM, and the compatibility path can still build the full graph. It is not a whole-process heap bound or a guarantee of lower peak memory. Old-library refresh overlap, artwork and native saved-store memory need separate evidence. No dependency, schema, setting, package/signing or release policy changes.

## API evidence and verification

Public contracts checked: [Android JsonReader](https://developer.android.com/reference/android/util/JsonReader), including strict default, numeric `nextString`, value/container APIs and `MalformedJsonException` compatibility handling. This is framework API, not an added parser library. Current Android 17 implementation sources were not independently checked; compilation and actual device checks remain required.

Eight focused regressions run on the existing Android 28/34 test environments and compare the prior projection: wide ordinary input, Unicode/NUL/metadata, numeric/string/null/boolean coercions, established lenient syntax, duplicate last-value/error behavior, ignored-extension structural limits, malformed/type refusals and oversized metadata. Existing TauonJson structural tests remain unchanged. No timing threshold or test-count reduction.

Local source/diff/preflight checks do not establish compilation or device behavior. CI is first compile/test; repeat the controlled ordinary-build fixture after current-head reports and selected APK safeguards pass. Source self-check is not independent review. No measured speedup/memory gain claimed yet.

Initial CI compiled and ran 1,761 Debug executions; the 16 new cross-platform parser cases passed, while an older inventory assertion required four distinct equal title strings. Android JsonReader pooled a repeated short string within one response, yielding three. The characterization now records the actual string-object count within the occurrence bound while still requiring all four distinct track occurrences, equality, ordering and fallback identity. No accepted record/metadata behavior was relaxed; unused duplicate string allocation is not a compatibility requirement.
