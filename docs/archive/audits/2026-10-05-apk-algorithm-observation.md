# Actual APK algorithms and unsigned provider coverage — 2026-10-05

Inspected main `ca0552ed3ab615ff5cc045457c8182f67bed37d1`. GPT-6 / Codex desktop, effort not reported: implementation/investigation/source self-review, not independent review. CI observation tool only; no app, dependency, signing identity, feed or device change. No real signing key, credentials or 1Password Environment read. Generated probe test keys are disposable/in-memory and never stored or printed.

## Built public input

Actual [Android 623](https://github.com/averylicious/muon/actions/runs/37268248092), source `1910f9f1fda3d3b37d2de22a3f5af96e4b669af7`. Debug [artifact 11326924650](https://github.com/averylicious/muon/actions/runs/37268248092/artifacts/11326924650), release [11327885621](https://github.com/averylicious/muon/actions/runs/37268248092/artifacts/11327885621). Downloaded ZIP CRC, APK SHA256SUMS and BUILD.txt full head/run verified for each:

| Variant | BUILD.txt version | APK SHA256 | Observed v2 signature/digest ID | Public RSA modulus |
| --- | --- | --- | --- | --- |
| Debug | 0.1.0-canary.623 | `f9b1e14250d24d1225e94dc7da203a5c81b8646fb8e6bbb97c0f22f8eace6fbd` | `0x0103` | 2048 bits |
| Release | 0.1.0 | `eb3537694fa29ec13faf18e5c4decd94f9cccc444fe701def971f7e9375f0b37` | `0x0104` | 4096 bits |

Release-variant version 0.1.0 is an ordinary branch artifact, **not a Stable publication or promotion**. App acceptance remains pending user QA. Only public signature metadata was inspected; no APK installed or executed.

A temporary bounded standard-library extractor located EOCD/central-directory/signing-block sizes and matching magic, checked pair/field boundaries, one v2 signer, matching ordered signature/digest IDs and the public SPKI RSA OID/modulus. Each has one certificate; public-key encodings are 294/550 bytes respectively. Present block IDs: `0x7109871a`, `0x42726577`, `0x504b4453`; unknown blocks not interpreted. No v1/v3/v4 verification, certificate validation, content digest or signature verification was performed by this extractor. ZIP/hash matching is not an independent signing verification.

An initial extractor rejected four trailing signed-data bytes because it modeled only three length-prefixed fields. Actual reviewed SDK `V2SchemeSigner.generateSignerBlock` bytecode at offsets 252–292 emits **four** fields, the fourth an empty byte array. Extractor updated to require that empty length-prefixed field for these inputs; malformed/truncated boundary controls still rejected. This is a corrected observation script assumption, not evidence of a malformed APK or a general format validator. Script is not used by CI/production; evidence above is self-contained.

## Exact SDK mapping and coverage change

[Android v2 format](https://source.android.com/docs/security/features/apksigning/v2) maps 0x0103 to RSA PKCS#1 v1.5/SHA-256 and 0x0104 to RSA PKCS#1 v1.5/SHA-512. Read actual public SDK build-tools 37 verifier JAR SHA256 `2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180` via local javap, never executing SDK classes: its SignatureAlgorithm initializer maps numeric 259/260 to SHA256withRSA/SHA512withRSA (offsets 98–151). The JAR remains separate from Maven apksig 8.13.2. Earlier [provider](2026-10-05-sdk-initialized-providers.md) and [native-loading](2026-10-05-sdk-native-loading-source.md) qualifications stand.

The unsigned probe previously tested SHA256withRSA on RSA-2048, its PSS name and EC-P256. Add **SHA512withRSA on a disposable RSA-4096 public key**, freshly reconstructed through KeyFactory and initialized before reading the provider, to cover the release artifact's observed algorithm/key-size combination. A separate uninitialized instance retains the candidate observation. Existing reviewed-JAR hash-before-load/private-copy/platform-parent loader, SDK provider bootstrap, bounded output, 60-second deadline and unset JVM-option safeguards unchanged.

This is still **test-key selection**, not selection using the actual APK certificate/public-key object, successful signature/payload verification, signing-provider observation, PSS support or crypto/native advisory clearance. No new private key extraction, certificate bytes or signature bytes are emitted. A fresh 4096-bit test-key generation adds work to the unsigned step; deadline remains fail-closed. No performance claim.

## Validation and handoff

Local javac --release17 compile and four SDK inventory tests passed. Invalid-argument (exit 2) and unreviewed-JAR (exit 3/skipped_unreviewed_hash) controls passed before SDK loading. An initial control wrongly expected exit 1; correcting that assertion to the existing documented exit/status passed without a probe change. Local SDK code not executed. Actual matching-JAR unsigned Dependency inventory and latest-head Android CI must pass; parse its exact structured record before claiming the new provider result. Device QA not needed for this CI-only tool change; existing application PRs remain open. No Stable release/tag or experimental integration. Author self-review only; no new Claude assignment.
