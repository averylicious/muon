# Selected dependency advisory refresh — 2026-10-29

GPT-6 / Codex desktop; exact variant/effort not reported. Source/database self-check, not independent review or phone evidence.

Downloaded inventory 128 / [run](https://github.com/averylicious/muon/actions/runs/40726453313), source `31e98ce4385f42e961de17bc2f8d54f3469f74bf`, has the same app/build tree as verified #701/fdf3e269/.1650. Archive SHA256 `a898d85790f18f2fe68a365c386559ad2aac3d1642226ef0c322fea355bdca2f` matches the API. It selects the stable Media3 patch and existing transitive Guava correction. [Evidence at the committed maintenance head](https://github.com/averylicious/muon/blob/77e217e509a4d207465b543615604180f39352d2/docs/audits/evidence/2026-10-29-dependency-advisories.json) records exact public Maven coordinates, query times and match IDs.

## Database result and prior-work comparison

Fresh OSV batch queries sent only public Maven coordinates, checked cardinality and no pagination. The selected release runtime has 109 coordinates and **zero matches** after #755. The preceding runtime inventory's sole match was Guava 33.3.1-android / GHSA-xxph-c9ww-hj94; [#754](issues/754.md) records source, packaged-code and API28 reachability qualifications. A zero result is a dated database snapshot, not absence of vulnerabilities.

The separate build classpath has 151 coordinates, **13 matched coordinates / 48 unique IDs / 49 pairs**. Compared with the [October25 evidence](2026-10-25-dependency-advisory-refresh.md), restricted to those build coordinates, the only new pair is `com.google.guava:guava:33.3.1-jre` / GHSA-xxph-c9ww-hj94. The other Netty, Compress, JDOM, crypto/JOSE and Kotlin pairs were already known. No full advisory-text or modified-content comparison is claimed. Do not rediscover them as new application blockers.

The app Guava constraint does **not** patch or establish exploitability of AGP's build classpath. Leave that new tool-coordinate reachability question open; do not force internal AGP dependencies or silently disposition it under provenance deferral. Existing [caller/source follow-up](2026-09-30-dependency-follow-up.md), [crypto analysis](2026-10-01-build-crypto.md) and [trust disposition](2026-10-07-build-trust-disposition.md) stay qualified prior evidence. In particular the known Kotlin route concerns KAPT incremental-cache deserialization; this app does not apply KAPT/KSP/annotationProcessor tasks. That selected non-applicability is not all-toolchain clearance.

## Verified plugin-patch follow-up

Standalone #755/a7ec5ca6 / Android .1653 and inventory129 passed. Normal buildscript classpath constraints select Guava33.7.2-jre without changing AGP/Kotlin/Gradle/Compose pins; all four app configurations exactly equal prior app-only inventory127. The 152-coordinate build graph changes Guava and its companions, including checker-qual3.43.0→2.5.8 after the former Guava requirement disappears; TensorFlow metadata requests2.5.8. No annotation constraint was forced. Consulted pinned AGP local-state ObjectInputStream routes, published Gradle plugin resolution/filtering and graph/bytecode evidence are qualified in[#754](issues/754.md).

A fresh query of all152 plugin-build coordinates has **12 matched coordinates /47 unique IDs /48 pairs**, no pagination and no new pair: the old build-Guava pair alone disappeared. Full .1653 reports show1,821cases/variant, no failures/errors/skips/lint errors; actual release gates passed. The new #701 combination still needs its own verification. Source/archive/version observations do not authenticate publishers or establish an exploit.

## Scope and next action

Gradle's own v8.14.6 build-platform source declares Guava33.4.6-jre, also in the advisory range. This is a source pin, not a fresh distribution-JAR inspection or demonstrated reachable Gradle exploit. The official versions API still lists8.14.6 as the newest8.14 patch. The app/plugin constraints do not patch Gradle's internal loader. Consulted DefaultSerializer.read and ClassLoaderObjectInputStream show a selected Gradle-loader deserialization route, but no lower-trust input writer or runtime exploit was established; daemon-channel authentication cannot be inferred from a serializer alone. Keep that caller/upstream-availability question open; do not force a major toolchain upgrade or replace bundled files based only on a source version match. [Pinned platform source](https://github.com/gradle/gradle/blob/v8.14.6/build-logic-commons/build-platform/build.gradle.kts), SHA256 `ad2025626c5bfcb49a5a6151d5e699ad8857a321f06549ab5ac9444a68abd29e`.

This fresh query covers selected runtime and build Maven configurations, not all tests, Gradle/SDK/native/shaded code, JavaScript actions or a complete tool/APK SBOM. No publisher authentication, compromise, Binder/OOM exploit or phone test. User-deferred dependency verification/runner-SDK-cache provenance remains deferred for later maintenance, not abandoned. No forced versions, secret/workflow/protection changes or frozen-RC replacement.

Next: verify the combined plugin-patch candidate, assess the distinct Gradle-internal route and keep later ordinary playback/service acceptance pending. Runtime/source checks do not replace that work.
