# Selected dependency advisory refresh — 2026-10-29

GPT-6 / Codex desktop; exact variant/effort not reported. Source/database self-check, not independent review or phone evidence.

Downloaded inventory 128 / [run](https://github.com/averylicious/muon/actions/runs/40726453313), source `31e98ce4385f42e961de17bc2f8d54f3469f74bf`, has the same app/build tree as verified #701/fdf3e269/.1650. Archive SHA256 `a898d85790f18f2fe68a365c386559ad2aac3d1642226ef0c322fea355bdca2f` matches the API. It selects the stable Media3 patch and existing transitive Guava correction. [Evidence](evidence/2026-10-29-dependency-advisories.json) records exact public Maven coordinates, query times and match IDs.

## Database result and prior-work comparison

Fresh OSV batch queries sent only public Maven coordinates, checked cardinality and no pagination. The selected release runtime has 109 coordinates and **zero matches** after #755. The preceding runtime inventory's sole match was Guava 33.3.1-android / GHSA-xxph-c9ww-hj94; [#754](issues/754.md) records source, packaged-code and API28 reachability qualifications. A zero result is a dated database snapshot, not absence of vulnerabilities.

The separate build classpath has 151 coordinates, **13 matched coordinates / 48 unique IDs / 49 pairs**. Compared with the [October25 evidence](2026-10-25-dependency-advisory-refresh.md), restricted to those build coordinates, the only new pair is `com.google.guava:guava:33.3.1-jre` / GHSA-xxph-c9ww-hj94. The other Netty, Compress, JDOM, crypto/JOSE and Kotlin pairs were already known. No full advisory-text or modified-content comparison is claimed. Do not rediscover them as new application blockers.

The app Guava constraint does **not** patch or establish exploitability of AGP's build classpath. Leave that new tool-coordinate reachability question open; do not force internal AGP dependencies or silently disposition it under provenance deferral. Existing [caller/source follow-up](2026-09-30-dependency-follow-up.md), [crypto analysis](2026-10-01-build-crypto.md) and [trust disposition](2026-10-07-build-trust-disposition.md) stay qualified prior evidence. In particular the known Kotlin route concerns KAPT incremental-cache deserialization; this app does not apply KAPT/KSP/annotationProcessor tasks. That selected non-applicability is not all-toolchain clearance.

## Scope and next action

The fresh query covers selected runtime and build Maven configurations, not all tests, Gradle/SDK/native/shaded code, JavaScript actions or a complete tool/APK SBOM. No publisher authentication, compromise, Binder/OOM exploit or phone test is claimed. User-deferred dependency verification/runner-SDK-cache provenance remains deferred for later maintenance, not abandoned. No forced versions, secret access, workflow/protection changes or frozen-RC replacement.

Next: assess the new build-Guava caller path and a compatible toolchain correction separately; keep later ordinary playback/service acceptance pending. Selected runtime source/version checks do not replace that work.
