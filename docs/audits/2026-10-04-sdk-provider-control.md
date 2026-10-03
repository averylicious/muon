# SDK apksigner provider bootstrap control — 2026-10-04

Inspected main `27c0f29bf3b76238582a779684e5cd55a17b7c0c`, branch `codex/sdk-provider-control`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Author implementation and self-check, **not independent review**. CI-only: no app, dependency, signing, credential, device or network change. The coordinator compiled the probe with javac --release17 and ran only an invalid, deliberately unreviewed local fixture. No SDK code was executed locally. The unsigned **Dependency inventory** matching-SDK observation is **pending**. Continues the [apksig source boundary](2026-10-04-apksig-source.md).

**Question:** after apksigner's own provider bootstrap runs, which JCA providers are registered, in what order, and which provider a plain JCA lookup picks first for the algorithms apksigner relies on?

## What changed

- **`tools/ApkSignerProviderProbe.java`**, run in Java 17 single-file source mode. It takes exactly two arguments: the jar path and a full 40-hex commit SHA. Anything else fails with the `arguments` stage, exit 2.
- **A step in the unsigned `dependency-audit.yml`**, labelled as a bootstrap observation that runs reviewed SDK code and may load native code. Its push path filter now includes the probe. The step runs immediately after the static, no-execution SDK inventory, before Gradle resolution; both existing inventories are unchanged. `permissions: contents: read`, the absence of secrets and the checkout without persisted credentials are unchanged.
- The step runs `java` with `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` unset for that process. Their values are never printed. The classpath is the in-memory compiled probe; the SDK jar is loaded only by the probe's own isolated loader.

## Hash before load

The probe reads the jar into memory and hashes it with the JDK's SHA-256 before anything from the jar is referenced.

- **The expected value** is `2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180`: SDK `lib/apksigner.jar` from run20/run21, which matched the jar inside the public `build-tools_r37_linux.zip` downloaded over HTTPS on October 4 (archive SHA1 matched Google's repository metadata). **This is a dated comparison against a public archive, not independent publisher signature authentication**, and not a general allowlist for other SDK or cache files.
- **On a mismatch** it prints a `skipped_unreviewed_hash` record with the observed hash and exits 3, so the step fails. No class from the jar is loaded and nothing is cleared silently.
- **On a match** it writes exactly the hashed bytes to an owner-only temporary file and loads that copy, so a later change to the SDK path can't swap in different bytes. The copy is deleted before exit.

## What runs after a match

- A `URLClassLoader` over the copy, with the platform class loader as parent, loads `com.android.apksigner.ApkSignerTool`. The probe reflects and invokes only its private static `addProviders()`. That also runs the class's static initialization.
- It never calls `main`, sign or verify, and parses no APK, certificate or key bytes. No key is generated, read or initialized.
- Per the earlier javap reading, `addProviders` constructs Conscrypt's `OpenSSLProvider` and appends it with `Security.addProvider`, catching `UnsatisfiedLinkError`. **Conscrypt attempts native library loading on the runner**; that's why the step is labelled as running code.

## Output

One line prefixed `MUON_SDK_PROVIDER_PROBE `, holding a JSON object:

- **Identity fields:** `commit`, `jar_sha256` and `reviewed_sha256`.
- **`status`:** `observed`, `skipped_unreviewed_hash` or `failed`.
- **`failure_stage`:** one of `arguments`, `read`, `copy`, `load`, `reflect`, `invoke` or `observe`. Failures print no exception message or stack trace.
- **When observed, the provider fields:**
  - `providers_before` and `providers_after`: provider names in priority order.
  - `sha256_message_digest`: the provider of `MessageDigest` SHA-256.
  - `sha256withrsa_uninitialized_candidate`: the provider an **uninitialized** `Signature` SHA256withRSA reports.
  - `sha256withrsa_supporting_providers`: every provider claiming `Signature.SHA256withRSA`.
  - `x509_certificate_factory`: the provider of `CertificateFactory` X.509.

Strings are printable ASCII, capped at 64 characters, with up to 32 providers per list. The exit codes are 0 for observed, 3 for a skip, 1 for a failure and 2 for bad arguments.

## Limits

- **A candidate, not a selection.** The JDK chooses a `Signature` provider lazily. The uninitialized candidate is the first provider that can be instantiated. Real signing or verification picks a provider when it is initialized with a key, and may skip providers that reject that key type. This is **not** signing, private-key initialization or apksigner's verifier algorithm selection. The digest and certificate-factory results are plain default lookups, not what apksig's own code requests in every path.
- **The priority result is one launch's.** The runner JDK's `java.security` decides which providers already exist, and the order of those existing providers. Appended Conscrypt sits behind them for any algorithm they already serve. The SDK wrapper's `java -jar` launch, `-J` options, other JDKs and other environments may differ. Observing Conscrypt in `providers_after` doesn't show that its native code loaded. If loading fails, Conscrypt's absence is consistent with the caught `UnsatisfiedLinkError`, but the probe doesn't separate the two.
- **Native and classpath scope:**
  - The native library's provenance, Conscrypt version and advisories aren't assessed.
  - Isolating the class loader keeps the probe and application classpath out, but not JDK modules or `java.security` configuration.
  - This is an observation of public SDK bytes on a hosted runner, not a sandbox.
- **No local SDK execution.** Coordinator Java17 compilation and a harmless hash-mismatch guard ran locally without SDK classes on the classpath. Matching-SDK execution remains CI's first observation.
- **Guard checked.** Java17 compilation passed; a deliberately invalid/unreviewed JAR yielded exactly one skipped_unreviewed_hash record and exit3 with no provider fields or SDK classes available. This tests refusal before SDK class loading, not the matching bootstrap/native path. There is no serialization-mirroring unit test; the CI step status and actual structured record establish the matching-path observation.
- **CI scope.** A `tools/` change is not documentation, so the main **Android APKs** workflow will also select a full build for this branch. The app is unchanged.

**Lightweight checks run:** `git diff --check`, and `python3 -m unittest discover -s tools -p 'test_ci*.py'` (50 tests, OK).

## Next task

Once a push to this branch triggers the unsigned **Dependency inventory** run, read the exact-head run's `MUON_SDK_PROVIDER_PROBE` line:

- Record its status, hash and provider order.
- Note whether Conscrypt appears after the JDK providers, and which provider each lookup selected.

If the status is `failed` or skipped, report the stage and don't widen the probe. A follow-up that wants actual selection must initialize with a public, ephemeral test key generated in CI, which needs its own review; it must never use the signing material.

## Coordinator review

GPT-6 / Codex desktop, effort not reported independently reviewed Claude's hash-before-load/private-copy/platform-parent loader and pinned bootstrap bytecode. Coordinator changes are self-review: bound the actual stream read (not only a prior file-size check), run the probe before Gradle resolution with a 60-second process deadline, and qualify native loading. The inspected SDK verifier JAR has no .so entry and the public build-tools archive has no conscrypt-named entry; do not call its native payload bundled or authenticated. Only addProviders is invoked, and the inspected class initializer initializes three digest fields to null. No key/parser method is invoked. No whole-JDK/classpath/native/advisory clearance is claimed. Exact-head CI matching-SDK execution remains pending.

The coordinator also delays the observed record until the isolated loader has closed, preventing a close failure from emitting both observed and failed records. Temporary public-JAR cleanup runs on either outcome.

## Verified observation and integration

#317 final80773ac757270fc60e7d68fcb29f1d3e96222992 mergedcd184a792a09f46aacf5bb0b477b7d42023e541b. Unsigned Dependency25 run37155106092 passed; actual parsed structured record matches exact commit/reviewed hash and status observed. Before/after provider list unchanged, no BC/Conscrypt; SHA256 and X509=SUN, uninitialized RSA candidate/supporting provider=SunRsaSign. These are scoped observations with the limits above, not actual signing selection or complete advisory clearance. Android55237155106088 passed; downloaded XML465 tests/variant zero failures/errors/skips and debug11286105685 BUILD.txt exact head/run/version verified. Fresh Branch direction37155564412 green; review receipt on #317 recorded before merge. Latest handoff records subsequent main publication. This supersedes author-time CI pending statements above.
