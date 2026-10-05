# SDK apksigner: initialized verification providers — 2026-10-05

Inspected main `a54e3ca6c4352dc4ff8bfe0abf6c40c514c122e0`, branch `codex/sdk-initialized-providers`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and self-check by the author, **not independent review**. CI-only: no app, dependency, signing, credential, device or network change. Continues the [provider bootstrap control](2026-10-04-sdk-provider-control.md).

**Question:** the existing probe asks an **uninitialized** `Signature` for its provider. Java delays choosing a `Signature` provider until it is initialized, and asking an uninitialized one fixes its choice early. So which provider does a **fresh** `Signature` settle on once it is initialized for verification, for algorithm names that the reviewed SDK apksigner actually uses?

## Algorithm names from the reviewed binary

- **Archive:** downloaded `https://dl.google.com/android/repository/build-tools_r37_linux.zip` over HTTPS into the ignored `build/audit-sources/`. It is 66135704 bytes, SHA1 `70954e99f4c3d9d46ee70fa32624672fe7cd6ebe` and SHA256 `01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060`, matching the earlier report.
- **Jar:** its `android-37.0/lib/apksigner.jar` is 1181416 bytes with SHA256 `2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180`, the reviewed hash.
- **How it was read:** class-file **constant pools** only, with a Python byte parser. No SDK code was loaded, run or disassembled.
- **`com/android/apksig/internal/apk/SignatureAlgorithm.class`** (5172 bytes, SHA256 `ea32fea81662d3758eaab8891278dbe2dd76a7a2222e543b441827fe9ce21b52`) holds:
  - the names `SHA256withRSA/PSS`, `SHA512withRSA/PSS`, `SHA256withRSA`, `SHA512withRSA`, `SHA256withECDSA`, `SHA512withECDSA`, `SHA256withDSA` and `SHA256withDetDSA`;
  - `java/security/spec/PSSParameterSpec`.
- **Key rebuilding:** the v2, v3 and v4 verifiers and `V1SchemeVerifier$Signer` reference `KeyFactory`, `X509EncodedKeySpec`, `generatePublic`, `Signature` and `initVerify`. The v2-v4 verifiers also reference `setParameter`.
- **What the constant pool can't show:**
  - which enum constant uses which name;
  - the call order (for example, `setParameter` before or after `initVerify`);
  - which names apksigner's CLI uses on a given path.
- **Not inferred:** nothing here comes from Maven `apksig` 8.13.2 or current documentation.

## Change

**`tools/ApkSignerProviderProbe.java`** (the hash gate, private copy, isolated loader, `addProviders`-only call, timeout and JVM-option unsetting are unchanged). After the existing observations, the record gains `initialized_verification`. For a fixed list of three names (`SHA256withRSA`, `SHA256withRSA/PSS`, `SHA256withECDSA`), each case runs:
1. `uninitialized_candidate` comes from a **separate** `Signature` instance.
2. A disposable public key is generated **in memory**: RSA 2048, or EC `secp256r1`.
3. The key is rebuilt from its X.509 encoding through `KeyFactory`, as apksig's verifiers do.
4. A **fresh** `Signature` gets `initVerify`, and `initialized_provider` is read from it afterwards.

Each entry records the algorithm, the test key label, `status` (`initialized`, `unsupported` or `failed`) and the failure's simple class name, with no exception text. No key, signature or certificate bytes are kept or printed.

**Workflow comment:** the comment above the probe step in `dependency-audit.yml` now says "no signing, APKs or real keys; only disposable in-memory test keys". The commands, permissions and step order are unchanged.

## What this is and isn't

- **It is:** the provider that **these test keys** select for verification in the hosted runner's JDK after apksigner's bootstrap.
- **It isn't:**
  - APK verification or signing, crypto correctness, or a native or Conscrypt advisory clearance;
  - selection for Muon's real signing keys, or the keys inside APKs;
  - selection when the SDK wrapper launches apksigner with its own JVM options.
- **Why test keys only:** delayed selection can depend on the key's class and parameters.
- **PSS:** the case calls no `setParameter`, so PSS parameters aren't applied. Selection is fixed at `initVerify`.
- **Not covered:** DSA, `SHA256withDetDSA` and the SHA-512 variants.
- **Unsupported names are recorded, not passed:** `unsupported` is an observation. If the runner's JDK lacks a name (for example `SHA256withRSA/PSS` without an extra provider), the record says so, and it isn't counted as a pass.

## Checks and pending evidence

**Run locally:**
- `javac --release 17` compiled the probe, under local JDK 25.
- **Hash gate:** a deliberately unreviewed dummy file was refused (`skipped_unreviewed_hash`, exit 3) and bad arguments failed (exit 2), so no SDK code ran.
- The CI policy and SDK inventory tests passed.
- `git diff --check` and the CI prose check passed.

**Not done:** the probe was **not** run against the reviewed jar locally. The unsigned **Dependency inventory** workflow is the first real run, and its record should be read for each entry's status and providers. No Gradle or Android build, and no phone QA is needed.
