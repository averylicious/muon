# Loudness settings controls — 2026-10-04

Inspected main `8ed4e4ca9932fbb01115ec136a9c5643bf9749c2`. Author GPT-6 / Codex desktop, effort not reported; implementation and self-review, no independent review yet. Test-only, no app/API/settings/channel change or device work.

The existing nine ReplayGain arithmetic tests do not exercise `ReplayGainSettings` with actual SharedPreferences. Four disposable Robolectric cases cover initial off/unity behavior, enabling and remembered fallback, reconstruction with endpoint-specific media IDs, unrelated-value preservation, median invalidation after changed gain, non-gain/NaN exclusion from fallback and explicit toggle reload by a separate settings owner. Fixtures use private test preferences cleared before each case, no user library/network/files.

Reconstruction in the same test process is not a disk-durability, Android process-death or forced-stop proof. Explicit `reload()` is not execution of the actual PlaybackService listener. Arithmetic expectations use the already covered public conversion helper; tests target selection, retention and ownership rather than reimplementing conversion. No tagged audio/decoder/output/speaker or performance measurements. The scoped source report will be linked from the coordinator checkpoint. No remembered-gain cap, migration or deletion is proposed; aggregate-resource question stays #253.

Local whitespace/document checks only. No local Android compile/Robolectric execution; latest-head Actions is the first real compile/test and evidence belongs on the PR. No device QA needed for test-only changes; app candidates and Stable promotion retain their separate gates.
