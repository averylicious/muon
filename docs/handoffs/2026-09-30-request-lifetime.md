# Main audit checkpoint: finite request deadlines and cancellation

## Ownership and prerequisites

The user asked to continue the main audit on 2026-09-30 and clarified that every PR requiring phone QA stays open while code auditing continues. GPT-6 (Codex desktop; effort not reported) is the sole writer on `codex/request-lifetime`, starting from main `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`. No Claude session or device task is assigned. The prior `codex/private-playback-service` branch is clean/pushed and preserved; [#205](https://github.com/averylicious/muon/pull/205) stays open for its compatibility gate. Its exact head `b3e86da810810d51c1bf91769642119a2e810971` passed run 364. This new branch does not include the private-service manifest/export changes.

[#202](https://github.com/averylicious/muon/pull/202), [#203](https://github.com/averylicious/muon/pull/203) and [#204](https://github.com/averylicious/muon/pull/204) are merged. [Main Canary .363](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.363) contains the controller/queue fix. Experimental work remains independently owned by the user and their Claude; no checkout or session was touched. The user's current nickname for this contributor is Sol; actual attribution above avoids inferring a different model from that name.

## N3 implementation

[Source finding N3](../audits/2026-09-30-network-entry-points.md) showed finite requests without a total deadline and blocking socket reads surviving coroutine cancellation.

- `Transport.metadataClient`, derived from the existing streaming client, has a 30-second whole-call timeout. Tauon JSON, Compose artwork, saved download covers and Media3 notification artwork use it. Audio and audio downloads retain the original client without a call timeout. Existing redirect prohibitions, connection/read limits and body-size caps remain.
- `Call.readCancellable` attaches `Call.cancel()` until the response is consumed and closed. It executes synchronously on the caller's existing IO dispatcher: switching the probe's limited dispatcher to unrestricted IO here would bypass its lane limit. API/artwork already use IO and probe requests run on its existing 48 IO lanes. The helper returns the parsed/copied value, never an open Response.
- API/artwork and LAN probes use the helper. Artwork/probe fallback explicitly rethrows `CancellationException`. Probes keep their 2.5-second deadline.
- Download covers run on an existing blocking executor; they gain the finite deadline, not coroutine cancellation. Notification artwork likewise gains a deadline without a new coroutine contract.
- Total-call `InterruptedIOException` uses the existing retry guidance for read timeouts.

The limit is per HTTP call, not a deadline for loading the whole library or a bound on aggregate memory/CPU work. JSON parsing and bitmap decoding are outside the network-call deadline. Discovery trust/auto-connect policy (Q1) is unchanged. No new dependency is introduced.

## Pinned sources and checks

Checked the published `com.squareup.okhttp3:okhttp:4.12.0` sources JAR from Maven Central: `OkHttpClient.Builder.callTimeout`, `RealCall.execute`, cancellation and timeout exit span response-body consumption. Checked `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2` sources JAR: `CancellableContinuation` and `suspendCancellableCoroutine` cancellation handler registration, synchronous cancellation and concurrent cancellation/resume behavior. Resolve future versions from Gradle files rather than treating these inspected versions as policy.

Eight JVM tests use the real OkHttp client and a loopback `ServerSocket`: finite-versus-stream policy, normal body completion, close on reader failure, dribbling bytes exceeding a total deadline, cancellation before headers and during body reads, already-cancelled calls and retry guidance. They need no phone, Tauon server or new dependency. The dribble test uses a shorter test-only deadline; production is 30 seconds. These tests prove modeled transport behavior, not Android lifecycle or real-device performance.

Local diff/branch checks run; no local Android build is claimed. GitHub Actions is the first app compile. The PR and #181 must record the exact head's run, tests/lint, signed variants, artifacts and failures before a successor treats this slice as verified.

## Manual QA and merge boundary

Phone QA remains pending; leave this PR open per the user's current instruction.

- Connect/retry/refresh Tauon and search/open lyrics; normal library requests must still finish and partial-playlist handling remain usable.
- Scroll artwork, change pages and reconnect while images load; covers should recover normally without stale visible results or stuck loading.
- Play a track longer than 30 seconds and try offline audio downloads; audio must continue past the metadata deadline.
- Check notification covers and controls, plus saved download covers offline. Existing private-service compatibility QA belongs to #205, which is not included in this build.
- A deliberately stalled server should produce a retryable finite-request error; do not claim ordinary phone QA proves deadline/cancellation without reproducing that setup.

The branch APK updates the same Canary package and may replace experimental features. Branch artifacts are Actions-only and expire after 14 days. No Stable release or device access is authorized here.

## Resume and remaining work

Verify live main/PR heads and final checks on #181 before resuming. No overlapping agent or local background build is required by these commits. Reserve capacity to fix CI and leave durable evidence; keep account quotas out of repository documents.

Next audit question: aggregate library/artwork bounds and stale asynchronous work, or a fresh-install discovery reproduction with separately authorized phone QA. #179 removable-cache preservation remains unresolved. Keep #205's gate, Q1's product decision and the independent experimental track intact. Forward integration into the experiment goes through its owner's separate tested sync boundary.
