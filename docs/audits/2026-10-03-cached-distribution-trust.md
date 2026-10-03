# Gradle cached distribution trust — 2026-10-03

Inspected main `69d78619875042077cdc104a1714ce43796454c3`. GPT-6 / Codex desktop, effort not reported: source/bytecode self-review. Continues the unresolved cached-distribution question in [the wrapper report](2026-10-03-wrapper-trust.md). [#299](https://github.com/averylicious/muon/pull/299) now independently verifies the wrapper JAR before Gradle cache restoration; it does not authenticate cached Gradle distribution contents.

## Exact evidence

- Current tracked wrapper SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, independently compared with official Gradle8.13 checksum. `javap -p -c` inspected `Install` and `Install$1` in this actual JAR without executing the wrapper or Gradle.
- Gradle release source [Install.java at v8.13.0](https://github.com/gradle/gradle/blob/v8.13.0/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/Install.java), git blob `20a3fc8c65009dd0a213e27802888fc4a9fb1847`, SHA-256 `647df52c3637266d59877ccfbb55df4cba751cd4c360267e3242767465b62c33`. Focused source/bytecode control-flow correspondence only, not a reproducible-build comparison.
- Pinned setup-gradle bundle from prior report, commit `0723195856401067f7a2779048b490ace7a47d7c`, SHA-256 `15bdf4c803b9f5c11dba1de77b4d46877af5d12c8d16f9a6bfd335926a3b4bdb`: extracted cache definitions include `wrapper/dists/*/*/` (near byte offset3841571); `deleteWrapperZips` removes ZIPs before extraction/caching (near3841056). This is a focused bundle reading, not a full post-step/cache-platform audit.

## W2 — unpacked-cache reuse does not recheck the ZIP checksum

Confirmed source/control flow:
1. `createDist` 72–77 finds a distribution directory plus `.ok` marker and returns the structurally verified home immediately. Actual `Install$1.call` bytecode offsets42–83 branches to the return at865 before checksum loading (134) or verification (330).
2. `verifyDistributionRoot` 209–222 checks exactly one top-level directory and a launcher JAR, not content hashes. Actual `Install.access$000` checks directory count and launcher presence at offsets79–255; it does not compute distribution checksums.
3. `fetchDistribution` 98–113 checks `distributionSha256Sum` only when using/installing the ZIP, before unpacking. `verifyDownloadChecksum` 225–247 compares SHA-256 and throws on mismatch. Muon's property is present.

Thus the checked-in ZIP checksum protects that installation path; it is not a fresh integrity check for an already-unpacked restored distribution. The prior report's uncertainty about this distinction is now narrowed by actual wrapper bytecode, not inferred merely from cache configuration.

## Impact, trust basis and next bounded decision

This is a confirmed cache-trust boundary, **not a demonstrated attacker route or compromised distribution**. A shape-valid cached home could have different executable bytes while retaining its marker; nothing here establishes that an untrusted party can write a cache Muon restores. Code already executing in a trusted cache-writing build and repository/workflow writers remain outside this guard's isolation. No cache was poisoned, modified, executed or cleared in this audit.

A further mitigation could avoid restored distribution homes or verify their complete contents against independently authenticated official artifacts. Such work must trace cache save/restore keys, write privileges, fallback and actual runtime provenance first, then account for CI latency/download costs. Do not generate a checksum manifest from the existing cache and call it trusted. Dependency artifacts/plugins/JDK/SDK/action bundles remain separate supply-chain questions. #299 is still useful for W1, with this narrower assurance explicitly retained.

Report-only: no phone QA needed, no package/signing/feed/dependency change or Stable authorization. No whole-repository security clearance or measured performance claim.
