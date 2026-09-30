# Dependency parent paths and qualified source follow-up — 2026-09-30

This records completed source investigation before the user-requested CI pause. It does not change dependencies or establish runtime exploitability. Initial selected-version/advisory evidence is on [open PR #222](https://github.com/averylicious/muon/pull/222), not yet main.

## Provenance

Exact graph/tool head: `a41335d75b263101ded2d6cb13bb232a0a926d38`. [Inventory run2](https://github.com/averylicious/muon/actions/runs/36693609105) succeeded with seven parser tests and actual pinned Gradle selected dependency edges. [Qualified shortest nonconstraint paths](evidence/2026-09-30-advisory-parent-paths.json) record the source graph hash; full JSON is recoverable from that run's single `MUON_DEPENDENCY_INVENTORY` log record. Selected versions are unchanged from run1: 286 unique Maven coordinates, 109 per app runtime, 141 per JVM-test runtime, 151 build-plugin classpath. Edge counts: 650 per app runtime, 723 per test runtime, 427 build classpath. This is not artifact integrity or a complete platform/native SBOM.

The exact-version OSV first pass matched 13 coordinates / 47 advisory IDs, all build-plugin/JVM-test scopes; no matches returned for the selected production-runtime coordinates. This is a database result, not proof of absence of vulnerabilities.

## Parent paths and inspected pinned sources

- Netty build dependencies come through AGP → grpc-netty. In AGP `internal/testing/utp/UtpTestResultListenerServer.kt` (84–95), the inspected server configures certificates, trust and required client authentication. `UtpTestResultListenerServerRunner.kt` starts it. The checked Muon workflows build APKs, run JVM tests/lint, or assemble benchmark tools; no connected/managed instrumented-test execution was found there. This qualifies this inspected UTP path, not every plugin use or advisory.
- JDOM comes through AGP → jetifier-processor. AGP `options/BooleanOption.kt` sets `android.enableJetifier` default false; inspected project/workflow settings do not override it. Jetifier `processor/transform/pom/XmlUtils.kt` (48–53) uses `SAXBuilder().build(inputStream)` without disabling external entities in that function. Treat untrusted-POM applicability as conditional on execution/Jetifier being enabled, not a proven current Muon exploit.
- Commons Compress comes through AGP → Android repository tooling. `InstallerUtil.java` uses `ZipFile`/`ZipArchiveEntry` for SDK archives. The inspected repository source jar did not expose the Pack200/DUMP readers targeted by the matched advisories. This narrows the inspected path; other callers/formats remain untraced.
- Bouncy Castle comes directly through AGP/builder on build scope and Robolectric on JVM-test scope. Inspected AGP `TLSUtils.kt` uses locally generated RSA certificates and SHA256withRSA, not the GOST/LDAP paths in some matched advisories. Broader signing/builder crypto applicability remains unverified.
- jose4j comes through AGP → bundletool. Its compressed-JWE/tool applicability remains **untraced**. A proposed Bundletool source retrieval did not execute because automatic permission review timed out; no downloaded source or negative finding is claimed.
- Kotlin Gradle plugin has the selected-version advisory match recorded in #222; no KAPT/KSP/annotationProcessor usage was found in the inspected build files. Do not upgrade to a beta compiler based only on version matching.

Pinned primary sources inspected (read only, not executed): [AGP sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2-sources.jar), [repository sources](https://dl.google.com/dl/android/maven2/com/android/tools/repository/31.13.2/repository-31.13.2-sources.jar), [Jetifier sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-processor/1.0.0-beta10/jetifier-processor-1.0.0-beta10-sources.jar). No signing material, private library payloads or raw session logs are included.

## Resume

Keep #222 open until its required checks pass at the latest head. Qualify jose4j and broader crypto/tool execution next before choosing compatible upgrades; dependency locking/checksum trust needs its own reviewed design. No phone QA is needed for this documentation/tooling, and no device, performance or security-completeness claim follows from the inventory job.

Attribution: GPT-6, Codex desktop; effort not reported (user nickname Sol). Author investigation/self-check only, no independent review.
