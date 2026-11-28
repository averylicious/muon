# Selected dependency advisory refresh — 2026-10-25

GPT-6 / Codex desktop; exact variant/effort not reported. Source/database assessment and self-check, not independent review or device evidence.

## Inventory and query

Actual unsigned [inventory .114](https://github.com/averylicious/muon/actions/runs/40106669032), source `f29f87323c57c8552e759502c38784ad80732056`, records the five selected application/test/build-plugin configurations below. That security-tooling change merged as main `f0a744273e58645490a1f918a807ad2447d64662`. Canonical compact sorted-key UTF-8 inventory SHA256: `f2d5dc0340c0e1f16074145157caf5a276ddb016b83bef7d207096ccfa9dea1a`. These configurations exactly match earlier inventory .112 at `61307b62e8fbd17f80a4df39264ecb71988882f8`; changing Gradle did not change these resolved modules/edges/artifact observations. This does not cover every tool configuration or authenticate their publishers.

There are **288 selected Maven coordinates**, versus 286 in the September30/October5 snapshot: only `kotlinx-coroutines-test:1.10.2` and `kotlinx-coroutines-test-jvm:1.10.2` were added, on test scopes. No coordinate was removed.

Queried [OSV's documented batch API](https://google.github.io/osv.dev/post-v1-querybatch/) with exact Maven name/version in three HTTPS batches of100/100/88. Checked HTTP success and result/input cardinality and ordering; no pagination token returned. Only public dependency coordinates were sent. The [compact evidence](evidence/2026-10-25-dependency-advisories.json) records query time, response digests, matched IDs/modified timestamps and scope counts. The API returns IDs and modified times, not full advisory details.

## Result and limits

**13 matched coordinates /47 unique IDs /52 coordinate-ID pairs**, exactly the same pair set as the earlier committed advisory evidence. No added/removed pair. No result was returned for either selected application-runtime configuration; matches remain on build-plugin/JVM-test scopes.

| Resolved scope | Coordinates | Matched coordinates |
| --- | --- | --- |
| `:app:debugRuntimeClasspath` | 109 | 0 |
| `:app:releaseRuntimeClasspath` | 109 | 0 |
| `:app:debugUnitTestRuntimeClasspath` | 143 | 1 |
| `:app:releaseUnitTestRuntimeClasspath` | 143 | 1 |
| `:build:classpath` | 151 | 12 |

This is a dated database match, **not absence of vulnerabilities**. It excludes the Gradle distribution, SDK/platform/native/shaded code, JavaScript actions and a complete APK/tool SBOM. In particular, the separately confirmed [Gradle advisories](issues/693.md) were found through direct maintainer sources and are not contradicted by this Maven result. A matched build/test version alone also does not prove a reachable Muon exploit.

No full-detail review or advisory-text comparison was performed; unchanged pair sets do not mean unchanged advisory content. Prior [qualified caller/source analysis](2026-09-30-dependency-follow-up.md), [crypto analysis](2026-10-01-build-crypto.md) and [trust disposition](2026-10-07-build-trust-disposition.md) remain dated evidence, not blanket suppressions. This report does not force transitive upgrades or remove the user's deferred publisher/dependency-verification work. No compromise or new runtime regression is claimed.

Next action: preserve exact pins and existing verification gates; assess one compatible, applicable upgrade/caller at a time. Documentation-only checks apply to this report; no phone testing, local Gradle execution or installed RC change.
