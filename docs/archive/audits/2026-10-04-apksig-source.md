# APK-signing crypto: source boundary — 2026-10-04

Inspected main `9957a02b173bc13350807dbe1d83b243b622c9fc`. GPT-6 / Codex desktop, effort not reported: investigation and own self-review. Continues [test-provider report](2026-10-03-test-crypto-provider.md) and [build-crypto qualification](2026-10-01-build-crypto.md). No crypto configuration, signing material, dependency or app change.

## Selected artifact and sources

Dependency run19 at `4a6e718a05b662a0fd5f804b2c4bbe87f42b7fce` recorded `com.android.tools.build:apksig:8.13.2` through apkzlib, builder and signflinger. Build/dependency files are unchanged at the inspected main. This is reuse of the recorded resolved graph, not a new inventory run.

Read the published [apksig8.13.2 source JAR](https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.13.2/apksig-8.13.2-sources.jar), SHA256 `c7a0f43d1e40fdf39725ac090f5c7bbaeae82d1e0bfd9cf2ee507a70eb5136b5`. Inspected the named callers and searched its125 Java entries for BC imports, provider registration, PKIX validators/builders and CRL parsing. Source-to-binary equivalence is not independently established. Source lines below count newline characters.

## Calls and constraints

- `SignerEngineFactory`39–64 routes local keys to `JcaSignerEngine`; KMS uses ServiceLoader implementations. `JcaSignerEngine.sign`49–58 uses `Signature.getInstance`, initializes with the supplied private key and signs. No KMS configuration was found in Muon's build/workflow/tools; external plugins/provider changes are not excluded by this bounded search.
- `ApkSigningBlockUtils.generateSignaturesOverData`1138–1188 signs through that factory, then verifies the generated signature using JCA and the configured certificate's public key. V1 signing541–567 likewise verifies, then encodes PKCS7 using apksig's own ASN.1 model/encoder.
- `SignatureAlgorithm`34–153 maps APK block IDs to RSA/PSS, RSA/PKCS1, EC and DSA algorithms. V2 verifier274–276 and V3 verifier376–378 reject unknown entries from the supported set rather than treating an arbitrary ID as a JCA algorithm name. V1 `AlgorithmIdentifier.getJcaSignatureAlgorithm`132–171 uses its OID map and RSA/DSA/ECDSA fallback with a mapped digest, rejecting unsupported choices. This inspection does not certify every scheme/parser.
- `V2SchemeVerifier`311–335 uses a JCA KeyFactory/Signature for APK-supplied key/signature bytes, then352 uses `X509CertificateUtils` for embedded certificates. That utility106–145 tries the selected JCA CertificateFactory and falls back to apksig's own BER parser/DER encoder before retrying. Thus parsing is real; absence of a BC import does not mean absence of crypto input or provider delegation.
- Across the inspected125 Java entries no direct org.bouncycastle, Security.addProvider/insertProvider, CertPathValidator/CertPathBuilder/PKIXParameters or generateCRL reference was found. JCA calls generally do not name a provider, so actual JVM provider selection remains unmeasured. V1 verifier665–709 checks critical extensions/key usage and verifies the APK signature; it is not evidence of BC PKIX trust-path validation.

## Qualified advisory disposition

Reopened the primary [composite verifier advisory](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%905588) and [lazy ASN.1/CRL advisory](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9013506) on October4. The constrained signature selections and absence of a direct BC composite/CRL call narrow those mechanisms for these named callers. They do not clear arbitrary transitive inputs, provider implementations, ASN.1 bounds or the whole selected classpath. Existing dated matches remain recorded; no exploit, new full advisory scan or affected production route was established here.

Muon additionally runs `$ANDROID_HOME/build-tools/37.0.0/apksigner` in `android.yml`118–120 and `tools/verify-apks.py`. **That SDK command-line tool is a separate distribution from Maven apksig8.13.2.** Its binary/provider/CLI bootstrap was not inspected here; do not infer its version/provider behavior from the Gradle graph or this source JAR. Successful signature verification establishes the checked output under that tool, not independent verifier provenance.

Next bounded trust task: inspect the actually installed SDK verifier and provider bootstrap without accessing keys, or design independent tool/cache integrity checks with a reviewed trust basis. No phone QA is needed for this report.

## Separate unsigned SDK inventory observation

Companion CI-only PR306 adds public-file inventory, not verifier execution. [Dependency run20](https://github.com/averylicious/muon/actions/runs/37136621104) at `a0255ec80c6140b8e7b4371f9e88a03458a9d960` passed; its actual JSON identifies SDK lib/apksigner.jar as1181416 bytes, SHA256 `2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180`,702 class entries, ApkVerifier present, Conscrypt OpenSSLProvider present and BC BouncyCastleProvider absent at those exact class paths. These flags do not exclude every shaded/other BC class, establish provider registration/version/selection, or independently authenticate this SDK download. The following later binary inspection narrows the bootstrap; actual runtime provider selection remains unmeasured.

## Published SDK byte comparison and compiled bootstrap

Downloaded only the public [build-tools37 Linux archive](https://dl.google.com/android/repository/build-tools_r37_linux.zip) referenced by Google's [repository metadata](https://dl.google.com/android/repository/repository2-3.xml). Archive size66135704 and published SHA1 `70954e99f4c3d9d46ee70fa32624672fe7cd6ebe` matched; observed archive SHA256 `01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060`. Its wrapper, verifier JAR and properties bytes each match the CI inventory SHA256. This is a current HTTPS-published archive comparison, not independent publisher signature verification, a new trusted allowlist or clearance of other SDK/cache files. No SDK was installed or signing material read.

Read the extracted public wrapper and used local javap to disassemble `com.android.apksigner.ApkSignerTool` without executing it. Manifest names that main class; wrapper97 launches java -jar and accepts -J JVM options. Main bytecode invokes addProviders before the sign/verify dispatch (help/version return earlier). addProviders constructs Conscrypt OpenSSLProvider and calls Security.addProvider, catching UnsatisfiedLinkError. It appends rather than inserting at first priority; existing JVM providers/actual algorithm choice and native loading were not observed. Version branch contains literal0.9, which must not be confused with Maven artifact8.13.2 or SDK package37.0.0.

The inspected JAR has zero named org/bouncycastle class entries and417 named org/conscrypt class entries. This narrows BC packaging for this exact standalone JAR only; external JVM providers/shaded classes/native crypto and Conscrypt advisories are not thereby cleared. The unsigned current-head [dependency run21](https://github.com/averylicious/muon/actions/runs/37136996064) at `5ab95836a52a3a13463c99ee02ab085a35054b29` passed and emitted identical SDK file observations to run20. The build graph's BC matches should not be assigned to this separate SDK CLI solely from AGP versions.

Next bounded trust work is runtime/native/provider reachability or independent cache/SDK/JDK/action provenance, without reading keys or treating recorded hashes as authenticated allowlists. This source/binary boundary is qualified, not a global build-security clearance.
