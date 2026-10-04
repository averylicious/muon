# Artifact byte comparison: three samples — 2026-10-04

Inspected main `7d7d16ff3051ceab147cc2893f3615dff51a615d`, branch `codex/artifact-byte-comparison`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Comparison by the author, **not independent review**. Report only: no app, dependency, workflow or CI change.

## Observed record

These are the public artifact observations captured from unsigned **Dependency inventory** run 33, at commit `667a68283336c0e5edc0d9ef686dabfba8192e74`. Run 33 compiled the init script and hashed resolved artifacts, but **failed end to end**: the parser refused identical duplicate records for `androidx.core:core:1.15.0`. This record is therefore an observation of what that run resolved and hashed, **not a green provenance receipt**. The narrow fix (coalescing identical records) subsequently passed fresh Dependency34 and merged as #326; the coordinator receipt below supersedes this initial pending state.

## Samples, checked 2026-10-04 at 12:00 UTC

Downloaded read-only over HTTPS from Maven Central (`https://repo1.maven.org/maven2/`). The SHA-256 was streamed with Python's `hashlib`. Nothing was executed, loaded or disassembled.

| Scope (run 33) | Component | Selected file and URL | Bytes | SHA-256 | Agrees with run 33? |
| --- | --- | --- | --- | --- | --- |
| app runtime and unit-test runtime | `com.squareup.okhttp3:okhttp:4.12.0` | `com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar` | 789531 | `b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0` | yes; the `.sha256` file agrees |
| `:build:classpath` | `org.bouncycastle:bcprov-jdk18on:1.79` | `org/bouncycastle/bcprov-jdk18on/1.79/bcprov-jdk18on-1.79.jar` | 8575537 | `0d81ecc3124536b539bce9aa3fe9621b7f84c9cee371b635a5b31c78b79ab1da` | yes; the `.sha256` file agrees |
| both unit-test runtimes | `com.google.guava:guava:33.4.8-jre` | `com/google/guava/guava/33.4.8-android/guava-33.4.8-android.jar` | 2936569 | `a1f8a4bafe5a77232d333356843e194599b30837574a7f4be1063daaaaab102c` | yes; the `.sha1` file agrees (`0cb7510a…56e9`); no `.sha256` or `.sha512` file was published (404) |

## Why Guava's file name differs from its version

The component Gradle selected is `guava:33.4.8-jre`, but the hashed file is `guava-33.4.8-android.jar`. Its Gradle module metadata explains that:
- **Source:** `com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.module` on Central (7127 bytes, SHA-256 `58a3357303069a21930e7b9fe9b864de8bfb8bd4607433dbe48263459620cffc`). It is format 1.1, and its component is `com.google.guava:guava:33.4.8-jre`.
- **Variants:**
  - `jreApiElements`/`jreRuntimeElements` (`org.gradle.jvm.environment=standard-jvm`) point to `guava-33.4.8-jre.jar`.
  - `androidApiElements`/`androidRuntimeElements` (`android`) point to `../33.4.8-android/guava-33.4.8-android.jar`.
- **Resolution:** under Android consumer attributes, Gradle chooses an Android runtime variant of the `-jre` component and downloads that file from the sibling `33.4.8-android` directory.
- **What metadata can't check:** these `files` entries carry only a name and URL, with no size or hash. The module file therefore can't vouch for the jar's bytes; the comparison above uses the jar itself.

The coordinate version and the file-name version legitimately differ here. This agrees with published variant metadata; the differing names alone are not evidence of tampering.

## What agreement means

- **What it shows:** for these three files, the bytes run 33 hashed equal the bytes Maven Central served over HTTPS at the check time. Where present, they also agree with Central's own checksum file. That is **agreement within one repository**.
- **What it doesn't show:**
  - a publisher signature check (no `.asc` or GPG verification was attempted, and no key trust was set up);
  - trust in provenance or the build;
  - that Central or a mirror wasn't serving the same altered bytes to both;
  - CVE or advisory clearance, or runtime behaviour;
  - any protection before the build executed these files.
- **Scope:** it covers three files out of hundreds in run 33's record. It doesn't extend to other modules, other scopes, Gradle's cache or the plugin classpath beyond `bcprov`.

## Next

With Dependency34 green, a broader comparison could cover more components, or check publisher signatures where a key trust policy has been explicitly reviewed. Neither is started here.

**Checks run locally:** `git diff --check` and the CI prose check. No Gradle build, no phone.

## Coordinator contribution review

GPT-6 / Codex desktop, effort not reported independently reviewed Claude author3371753b and freshly downloaded the same three public artifacts via repo.maven.apache.org (no execution/load/disassembly). Streamed SHA256/byte counts independently agree with the successful exact-head unsigned Dependency34 record at a2ce1296624b263abed7e818e94bcefc0193e624; Guava module metadata bytes/hash and Android-variant file URLs also match. This is a second observation through the same repository, not an independent publisher trust anchor. Coordinator clarified unrecorded selected-variant attributes and integrated the report into #327; own report corrections/integration self-reviewed. Original run33 failed provenance checks as recorded; new34 passed after identical-record coalescing, but does not authenticate all artifacts or protect pre-configuration execution.
