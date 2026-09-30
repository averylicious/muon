# Qualified build-crypto reachability — 2026-10-01

Inspected Muon main: `17309b11dd339dd833a8bf135b1b30b2d0f273a7`. This continues the [dependency parent-path report](2026-09-30-dependency-follow-up.md), using the exact selected graph now merged through #222. No dependency, provider, signing, workflow or app change is made. GPT-6 / Codex desktop (Sol), effort not reported; author investigation, no independent review.

## Scope and source provenance

The recorded exact-version scan matches BC1.79 on the build-plugin classpath and BC1.81 on JVM-test paths. The resolved app-runtime graph contains no Bouncy Castle Maven coordinate. This distinction does not exclude Android's platform crypto or establish that every build dependency is safe. Advisory databases and library versions may change; the dated scan is preserved as evidence rather than refreshed implicitly.

Read the pinned published Google Maven [builder8.13.2 sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.13.2/builder-8.13.2-sources.jar), SHA256 `46785fe800dfdc013dd3b294d4bfc57abf8e3880a3f47dd28d85ec9b47d0377d`, and [sdk-common31.13.2 sources](https://dl.google.com/dl/android/maven2/com/android/tools/sdk-common/31.13.2/sdk-common-31.13.2-sources.jar), SHA256 `d628ff3faef817facebd17b6befbc32f13ec8719fc6502ac29c7f8820fcfdff9`. Also inspected callers in the previously recorded pinned AGP source JAR. Reading a published source JAR does not independently establish its equivalence to the compiled artifact.

Across those builder sources no direct org.bouncycastle import was found. The only sdk-common importer found was `com/android/ide/common/signing/KeystoreHelper.java`; the direct AGP importer was `internal/testing/utp/TLSUtils.kt`. This is a bounded import/caller inspection, not whole-classpath dynamic reachability analysis.

## Actual caller behavior

- `KeystoreHelper.createDebugStore` generates a local RSA key and SHA256withRSA certificate. Its private `generateKeyAndCertificate` supplies a BC provider to the signer and converter, using the locally generated public key and constructed certificate.
- `KeystoreHelper.getCertificateInfo`, called by AGP's `IncrementalPackagerBuilder.withSigning`, loads the configured KeyStore through the Java KeyStore API and retrieves its private-key/certificate entry. It does not explicitly select BC or call a PKIX path validator, LDAP store, composite-signature verifier or CRL holder. This is a description of the inspected method, not proof of a global provider configuration.
- `GradleKeystoreHelper.createDefaultDebugStore` uses the generation helper for a missing default debug store. Muon's APK workflow supplies existing signing stores; no new store was generated or inspected during this investigation.
- `TLSUtils.generateRsaKeyPair/createCert` constructs local RSA/SHA256withRSA UTP certificates. Current inspected workflows do not run connected or managed instrumented tests. This qualifies the earlier UTP path; it does not audit all gRPC/TLS implementations.

## Matched advisory mechanisms

The primary BC pages were read through their actual Unicode-hyphen wiki links; ASCII-hyphen guesses redirected to Home and were not treated as advisory evidence.

| Primary advisory | Affected mechanism | Qualified observation for the inspected callers |
| --- | --- | --- |
| [CVE2026-5588](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%905588) | Draft composite-signature verification accepts an empty signature sequence | The observed RSA certificate generation does not invoke composite verification. |
| [CVE2025-14813](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902025%E2%80%9014813) | G3413CTRBlockCipher counter wraps after 255 blocks | No GOSTCTR use was found in these callers. |
| [CVE2026-0636](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%900636) | Explicit LDAP certificate-store API wildcard injection | No LDAP certificate-store API use was found in these callers. |
| [CVE2026-8763](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%908763) | PKIX email/URI name-constraint trailing-dot bypass | No BC certificate-path validation invocation was established here. Certificate conversion alone does not establish that route. |
| [CVE2026-13506](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9013506) | Lazy ASN.1 sequence forcing resets depth budget, including CRL paths | Inspected conversion uses a locally constructed certificate; no attacker-supplied CRL route was established. This is not a universal ASN.1 input-bound guarantee. |

No affected route is established in these specific callers. Keep the selected-version matches open: Robolectric's provider registration, other transitive build callers, actual resolved provider behavior and compatible upstream upgrades remain untraced. Do not infer that an affected library is exploitable in the shipped app or that the whole build is cleared from these observations. No adversarial crypto execution, exploit test, signing-material access or device testing occurred.

## Next bounded dependency task

Trace remaining selected build/test callers or select a compatible upstream tool upgrade with actual CI. Keep dependency locking and independently trusted checksum provenance separate; do not bless the current cache/downloads by generating verification metadata without a reviewed trust basis. Existing Netty/JDOM/Compress/Kotlin and jose4j qualifications remain in the parent report. No phone QA is needed for this source-only report.
