# Test crypto provider: qualified source follow-up

Main `20ea53a1fbfb3f117efc2cc093ded8eab268fa58`; combined candidate `e04c4ecacd8f3917b4b471eed9b8141888106d1b`. GPT-6 / Codex desktop, effort not reported: source investigation/self-review. Continues [build crypto qualification](2026-10-01-build-crypto.md), not a new advisory scan, upgrade, exploit test or security clearance. No signing material/provider configuration was changed.

## Fresh selected graph and primary sources

[Dependency run19](https://github.com/averylicious/muon/actions/runs/37132633371) at `4a6e718a05b662a0fd5f804b2c4bbe87f42b7fce`: parsed the emitted MUON_DEPENDENCY_INVENTORY JSON from the successful job, not the declared dependency list alone. Both app runtime scopes have no Bouncy Castle Maven coordinate; both JVM-test scopes select bcprov-jdk18on1.81 through Robolectric4.16.1. Build classpath selects bcprov/bcpkix/bcutil1.79 through AGP/builder/sdk-common/apkzlib. No pinned Gradle dependency/build file changed between that inventory head and e04c4ec; not a new final-head inventory run or Android-platform crypto audit.

Published Maven sources, read-only:
- [robolectric4.16.1](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.16.1/robolectric-4.16.1-sources.jar), SHA256 `b354b256648453c5e83eb4ec19d80f23a6d8775e7684c93d5e3c0f327ba8d289`.
- [sandbox4.16.1](https://repo.maven.apache.org/maven2/org/robolectric/sandbox/4.16.1/sandbox-4.16.1-sources.jar), `db7ca1cae9e36d2c4231e10bf57dd48255ffbad778b5506b2886c94b8274ce51`.
- [shadows-framework4.16.1](https://repo.maven.apache.org/maven2/org/robolectric/shadows-framework/4.16.1/shadows-framework-4.16.1-sources.jar), `977c225559953d772cff539dff0ccf4bb757f297e6a18620cd609ba077108409`.
- [apkzlib8.13.2](https://dl.google.com/dl/android/maven2/com/android/tools/build/apkzlib/8.13.2/apkzlib-8.13.2-sources.jar), `6a6b5982b18e3646a1d1ddd1ab1538798909af3240d87ea4d07465317e2cb0e6`.

Source-to-compiled-artifact equivalence and provider execution/order were not independently measured.

## Actual observed setup/callers

`AndroidTestEnvironment.java`164–193 removes the Conscrypt provider; with Conscrypt enabled it removes BC and installs OpenSSLProvider first, configures a Conscrypt hostname verifier, then appends a BC provider when none exists. `ConscryptModeConfigurer.defaultValue` defaults ON except macOS aarch64. Inspected Ubuntu CI and Muon tests/build files contain no explicit ConscryptMode override. This is expected setup from sources, not a runtime provider dump or a guarantee which provider every algorithm resolves to.

`AndroidConfigurer.java`56–57 excludes BC/Conscrypt packages from acquiring them inside each instrumenting classloader. It does not exclude them from the JVM classpath or establish provider isolation between tests. `RobolectricTestRunner`77–80 sets org.bouncycastle.rsa.max_mr_tests=0 as its RSA-validation performance workaround; this test setup should not be treated as evidence of production crypto behavior.

Across the three inspected Robolectric source JARs, the direct BC references found were that setup/import, classloader exclusion and RSA property. Inspected Muon test/app/baseline sources did not invoke BC, PKIX/CertPath/LDAP/GOST APIs or explicit crypto signature/cipher selection. Existing loopback fixtures exercise HTTP rather than supplying adversarial crypto material. This narrows direct test-input applicability, not every Android framework/JVM/transitive algorithm route.

Across apkzlib's Java/Kotlin source entries no direct org.bouncycastle import/reference was found. Its signing facade calls apksig (SigningExtension imports ApkSignerEngine/ApkVerifier/DefaultApkSignerEngine); that is not proof apksig or every delegated provider is cleared. Broader compiled signing/transitive routes remain separate from the previously inspected sdk-common local certificate generation and AGP UTP path.

## Disposition

Keep selected-version advisory matches from the dated scan recorded. No affected mechanism was established by these specific test setup/direct-input and apkzlib import inspections; no global provider or build-security assurance follows. BC test presence is confirmed, unlike a claim it was unused or absent; the app-runtime graph result alone does not describe Android's own providers.

Next bounded dependency work: qualify remaining apksig/other transitive/tool algorithms against actual selected versions and reviewed primary advisories, or choose a compatible upstream tool update with exact-head CI. Locking/checksum provenance is a separate trust design; do not bless cache contents automatically. No app change/device QA is needed for this report.
