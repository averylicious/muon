# Resolved dependency advisory first pass

Inspected tool head `e1a6e46205229ff6e0c3862f9a94cea4f9c0849a`, based on main `792df8c2d9ad09938630e9b516b9b90bbf536ca7`. App dependencies are unchanged by #222. Investigator: GPT-6, Codex desktop, effort not reported; author's investigation, not independent review. No app/library versions, signing, settings or experimental checkout changed.

## Verified inventory and advisory matches

The [read-only inventory run](https://github.com/averylicious/muon/actions/runs/36687847589) succeeded on the inspected full SHA. Its Gradle task executed against the pinned wrapper, resolved selected module IDs, rejected unresolved/empty results, and produced a provenance-checked log record. The [inventory JSON](evidence/2026-09-30-dependency-inventory.json) retains that evidence: 109 modules in each app runtime scope, 141 in each JVM-test runtime scope, 151 in the root build-plugin classpath; 286 unique coordinates across all five scopes. Counts overlap and must not be summed as unique dependencies.

On 2026-09-30, those 286 public Maven name/version pairs were queried through OSV v1 querybatch. No code, secrets or private project identity was sent. All 286 results returned without pagination tokens. There are **13 matched coordinates and 47 unique advisory IDs**, exclusively in build-plugin and/or JVM-test scopes. **No matches returned for the 109 selected production-runtime modules.** This is a database/version-match result, not proof that the app has no vulnerabilities or that every matched advisory is reachable. [Scoped matches and primary references](evidence/2026-09-30-dependency-advisories.json) preserve IDs, aliases and query provenance without copying advisory text.

| Selected component | Scope | Follow-up / applicability |
| --- | --- | --- |
| Netty codec-http2, codec-http, codec, common, handler-proxy and handler 4.1.110.Final | Root build plugins | Matches span HTTP/2, HTTP parsing/decompression, proxy/TLS/platform conditions. Determine the dependency parent and actual tool call sites before treating them as exploitable. Muon's app HTTP path uses OkHttp, not these Netty components. |
| Commons Compress 1.21 | Root build plugins | CVE-2024-26308 and CVE-2024-25710 concern malformed Pack200/DUMP input; Apache lists fixes in 1.26.0. Trace build archive readers and input trust; a selected old version alone does not prove those formats are exercised. |
| jose4j 0.9.5 | Root build plugins | CVE-2024-29371 concerns compressed JWE; upstream fix 0.9.6. No compressed-JWE build input path established. |
| JDOM 2.0.6 | Root build plugins | CVE-2021-33813 concerns XML external entity defaults; upstream fix 2.0.6.1. Determine whether tool callers already disable external entities and which XML is trusted. |
| Bouncy Castle bcpkix 1.79 and bcprov 1.79 | Root build plugins | Algorithm, certificate, LDAP and ASN.1-specific matches require call-site/input analysis; latest fixed versions differ by advisory. No blanket override performed. |
| Bouncy Castle bcprov 1.81 | JVM tests | Same applicability qualification; not shipped as an app-runtime dependency in this inventory. |
| Kotlin Gradle plugin 2.2.21 | Root build plugins | GHSA-r937-wjx7-w2jp / CVE-2026-53914 targets KAPT incremental-cache deserialization. Published fix is in KAPT cache readers. Repository Gradle files do not apply KAPT/KSP/annotationProcessor; no KAPT execution path found. Do not force a beta Kotlin upgrade solely from this coordinate match. Shared build-cache trust still needs its own review. |

Primary sources checked include [Apache's security page](https://commons.apache.org/proper/commons-compress/security.html), [Kotlin's KAPT fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6), [JDOM's fix](https://github.com/hunterhacker/jdom/commit/dd4f3c2fc7893edd914954c73eb577f925a7d361), [jose4j's fix](https://bitbucket.org/b_c/jose4j/commits/19a90a64c47bb07c4aa5462f1316d5c293d81fcf) and the referenced Bouncy Castle/Netty upstream changes. OSV supplied exact-version matches; upstream changes informed the narrow qualifications above. No runtime exploit attempt occurred.

## Remaining supply-chain work

1. Capture resolution-parent edges / focused dependencyInsight for matched build/test components and trace relevant tool call sites. Separate confirmed reachable paths from version-only alerts; prioritize parsing untrusted inputs and secret-bearing build execution.
2. Propose compatible upstream/plugin upgrades where justified, in a separate PR with exact-head compile/tests/lint/identity verification. Do not globally force transitive versions or change the experimental dependency bundle as part of this report.
3. Review dependency locking/checksum verification and Gradle cache provenance separately. Neither lockfiles nor verification metadata are currently present. A checksum baseline generated from today's downloads is not independently verified provenance.
4. Audit the experiment's own resolved graph separately; fetching its ref does not establish identical alpha dependency transitives.

Excluded from this first pass: platform/native/system libraries, Gradle's embedded implementation, action transitives, benchmark/instrumentation graphs and composite builds. No complete SBOM, artifact-integrity or measured performance claim. The ordinary Android run377 passed builds/unit suites/lint/identity stages but failed artifact upload due storage quota; zero APK/report artifacts are available. Inventory success does not waive the required Android check or authorize merging.
