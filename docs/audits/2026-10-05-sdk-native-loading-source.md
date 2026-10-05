# SDK apksigner: Conscrypt native-loading route — 2026-10-05

Inspected main `aca170516b84bb2198d7ad5a94a55193be8eff74`, branch `codex/sdk-native-loading-source`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source and bytecode reading by the author, **not independent review**. Report only: no code, workflow or dependency change. No SDK class was loaded or run. Continues the [provider bootstrap control](2026-10-04-sdk-provider-control.md) and the [apksig source boundary](2026-10-04-apksig-source.md).

**Question:** #317's unsigned run observed **no** Conscrypt provider before or after apksigner's `addProviders`. What does the reviewed compiled code say about how that can happen, and what remains unknown?

## Reviewed bytes

**Archive:** `https://dl.google.com/android/repository/build-tools_r37_linux.zip`, downloaded earlier over HTTPS. It is 66135704 bytes, SHA1 `70954e99f4c3d9d46ee70fa32624672fe7cd6ebe` and SHA256 `01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060`.

**Its contents:**
- **Jar:** `android-37.0/lib/apksigner.jar`, SHA256 `2defad215d7ff52968a409cde528cdaef7918b115e276b8e3378ca7a178e4180` (the reviewed hash).
- **Wrapper:** `android-37.0/apksigner`, 2959 bytes, SHA256 `b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0`.

**How they were read:** classes were extracted from the jar and read with the local JDK's `javap -c -p` and a constant-pool parser. That reads class files only; it executes nothing from them. Offsets are bytecode offsets.

| Class | SHA256 |
| --- | --- |
| `com/android/apksigner/ApkSignerTool.class` | `c178eabb51d7a31ddc9725504e4661635fa349010d0206947b4ae34b233f1b26` |
| `org/conscrypt/OpenSSLProvider.class` | `c76562a5655c57d66c829bf13dceb06f665083da4213cad6e503ecd6d990d170` |
| `org/conscrypt/NativeCrypto.class` | `2b1f4b990e96ede35b237e5a73e61c481a545be5af991551e43d7228e0cccfce` |
| `org/conscrypt/NativeCryptoJni.class` | `269e264035f6e7c5b110c79f481a178043e68d1a0c15719f860b8cfda510169d` |
| `org/conscrypt/NativeLibraryLoader.class` | `f7d8033eaec7f8206133bc3434a81bb3c13b2a2d8fe5df25f2edc1f841caf06b` |
| `org/conscrypt/NativeLibraryUtil.class` | `6d58f7d930824d3f226246199b9ea98659ec76b4fb055a09e9978e9da07c2a5c` |

## The compiled route

1. **`ApkSignerTool.main`** calls `addProviders` at offset 53, before dispatching sign or verify (60 onwards).
2. **`ApkSignerTool.addProviders`:**
   - `new OpenSSLProvider` (0), `<init>` (4), then `Security.addProvider` (7).
   - Its only exception-table entry covers 0-11, handled at 14, for **`UnsatisfiedLinkError` only**. Any other throwable propagates.
3. **`OpenSSLProvider()`** calls `Platform.getDefaultProviderName`, then the `(String)` constructor and its `Platform` queries. The full constructor `(String, boolean, String, boolean, boolean)` then:
   - runs `Provider.<init>` (5);
   - calls `NativeCrypto.checkAvailability()` (8) **before** registering any services (`Platform.setup` at 35 and the rest).
4. **`NativeCrypto.<clinit>`:**
   - calls `NativeCryptoJni.init()` (2). The exception table covers 2-5, handled at 8, for `UnsatisfiedLinkError`, which is stored into the static `loadError` (12).
   - At 85-88, a non-null `loadError` skips the native `get_cipher_names`.
   - **`checkAvailability`** (0-9) rethrows the stored `loadError`.
5. **`NativeCryptoJni.init`** tries three library names in order, through `NativeLibraryLoader.loadFirstAvailable` (32) with `NativeCrypto`'s class loader:
   - `platformLibName()`, which is `"conscrypt_openjdk_jni-"` + the OS + `-` + the architecture (from `HostProperties`);
   - `"conscrypt_openjdk_jni"`;
   - `"conscrypt"`.

   On failure it logs (39) and calls `throwBestError` (43).
6. **`NativeLibraryLoader.load`:**
   - **Bundled resource first:** `loadFromWorkdir` (3) looks up the class-loader resource `META-INF/native/` + `System.mapLibraryName(name)`. If found, it copies the file to a work directory (`org.conscrypt.native.workdir`) and loads it.
   - **Otherwise:** `loadLibrary` (13), whose current-class-loader path reaches `NativeLibraryUtil.loadLibrary(name, false)`, i.e. `System.loadLibrary(name)`.
7. **Nothing bundled:**
   - **The jar's only non-class entries** are `META-INF/MANIFEST.MF` and five `com/android/apksigner/help*.txt` files. There is no `META-INF/native/` resource.
   - **The archive's `lib64/`** holds `libLLVM_android.so`, `libbcc.so`, `libbcinfo.so`, `libc++.so`, `libc++.so.1` and `libclang_android.so`. None has a Conscrypt name.
   - **So the only remaining route is** `System.loadLibrary` against the JVM's library path.

## The wrapper differs from the probe's launch

`android-37.0/apksigner` sets `providerLibdir` only in the Android-tree case, when `lib/apksigner.jar` isn't readable (lines 46-51). It then appends `-Djava.library.path=$providerLibdir` whenever no `-J-Djava.library.path=` was given (86-88), and runs `exec java $javaOpts -jar` (97).

- **The quirk:** the test is unquoted (`[ -n $providerLibdir ]`). With an empty value it becomes the one-argument `[ -n ]`, which is true.
- **Consequence (shell semantics, not run here):** in the SDK layout the wrapper would pass an **empty** `-Djava.library.path=`.
- **The probe doesn't use the wrapper:** it runs `java tools/ApkSignerProviderProbe.java` with the JDK's default library path. Its result therefore describes the probe's launch, not apksigner's own launch.

## What is supported, what was observed, what is unknown

- **Source-supported:**
  - On this route, a missing Conscrypt registration with a normal return from `addProviders` follows from an `UnsatisfiedLinkError`: rethrown by `checkAvailability`, propagated out of the constructor, and caught by `addProviders` before `Security.addProvider` runs.
  - Every other throwable from this route would escape `addProviders`. The probe would then have recorded `failed` at its `invoke` stage.
- **Observed in CI (#317, Dependency 25):**
  - the probe status was `observed`;
  - the provider order was identical before and after;
  - no Conscrypt provider appeared.
- **Inferred, not measured:** that combination is consistent with the caught `UnsatisfiedLinkError`.
- **Unknown at runtime:**
  - which of the three names were tried and why each failed (not found, refused, or linked then failed);
  - the JDK's handling of the runner's library path, and of an empty one under the wrapper;
  - whether any Conscrypt-named library exists anywhere on hosted runners;
  - the native library's provenance and version, if one were ever loaded;
  - advisories for either.
- **Not inferred from absence:** none of this comes from import names or current documentation, and it says nothing about native code in general.
- **Not claimed:** no claim of global absence of native execution, signing failure, runner compromise or security clearance. Host library locations were not inspected.

## Next bounded trust task (optional)

If apksigner's own provider set during CI verification matters, the meaningful next observation is in the unsigned workflow. It would use the **wrapper's actual launch shape** (`-jar`, the empty library path the wrapper's shell code implies, no `-J` options) behind the existing hash gate, and record which providers are registered. That would replace the current inference with a recorded observation for that launch. Nothing here makes it mandatory, and no attack is assumed.

**Checks run locally:** `git diff --check` and the CI prose check. CI for this document is pending. No Gradle or Android build, and no device.
