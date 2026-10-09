# #253 JSON structure budget — 2026-10-08

Root GPT-6 (Codex desktop, exact variant/effort not exposed) implementation and self-review. Isolated `codex/json-structure-budget-oct8`, from #390 `d0df73a64b3758375424293fca9638dad2b5dee4`, including #389 and main `5a123109`. No device/experiment changes, no local Gradle; Actions is the first compile/test run. App acceptance remains OPEN.

## Source-confirmed problem and change

`TauonApi.json` buffers at most16MiB, but recognized array counts are checked only after `JSONObject` allocates a complete graph. The existing depth64 preflight allows very wide or many sibling arrays, including ignored extensions and lenient missing elements.

Extend that same lexical preflight with max50,000 slots per array and max1,600,000 scalar/name/container/omitted-slot values over the whole response. Object names count as well as values, and every container counts. The total gives ample space for a normal50,000-record response with the currently projected fields; it is an explicit structural ceiling, not a measured heap budget. Array budgets are independent at each nesting level. Comments and quoted punctuation do not count as containers or separators. Missing and trailing array elements allocate null slots in Android's parser, so they count too; otherwise many arrays of commas could bypass a scalar-only budget.

The guard throws `LibraryResourceLimit` before `JSONObject(text)`. `LibraryModel.connect` already propagates that exact category out of per-playlist loading, rather than silently combining an oversized new response as a partial successful load. Its previous-library/offline/error policy remains unchanged. Error messages identify the JSON limit. Per-response byte/depth, per-track tag and complete-load limits remain.

Verified Android API34 lexical behavior against primary platform `libcore` JSONTokener source (`android-14.0.0_r1`), specifically `readArray` omitted/trailing-null handling: https://android.googlesource.com/platform/libcore/+/android-14.0.0_r1/json/src/main/java/org/json/JSONTokener.java . Source SHA256 `744c9ffb50c2472d7269c8d64a7d8331ea1a1691fb6536619ac9590e9ebbbd4b`. Actual Robolectric tests use APIs28 and34; their CI results, not the one source reference, establish those fixture outcomes. No claim about every OEM/platform parser.

## Verification

Added array boundary/omitted/trailing-null cases, independently budgeted nested arrays, aggregate sibling/object-name/value accounting, quoted/commented punctuation, and a production-limit attack of32 individually legal50,000-slot arrays (under16MiB wire). The aggregate case must throw our resource category before DOM allocation. Existing depth/leniency fixtures and actual public Tauon API wide-array/retry cases remain. CI not yet run at this document's commit; exact-head results are recorded on the PR.

## Limits and release gate

This does not bound complete process heap, cached JSON strings, decoded saved catalogs, Media3 queues/tasks or cache key cardinality. The response byte array/String conversion and one lexical token's allocation remain within the existing response-byte envelope; no heap or timing measurement was made. The user requires broader saved-library paging and Media3/cache memory redesign before Stable. This is one preparatory guard, not #253 completion or an accepted deferral. Manual normal library/retry acceptance remains pending; no APK promises before CI verification.
