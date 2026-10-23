# Resolved dependency advisory refresh — 2026-10-05

Inspected main `da3b3888ab56cab7b3bed85f4a8f179998348387`. GPT-6 / Codex desktop, effort not reported: author/source self-review, no independent review. No dependency, app, workflow, signing or device change. This refreshes exact-version advisory **matches**, not every advisory's revised details or exploitability.

## Resolved evidence and query method

Actual successful unsigned [Dependency inventory 41](https://github.com/averylicious/muon/actions/runs/37267466943), source `1b062c7ea36d60c9eb8b016d4740e1a2af99eb31`. Downloaded logs were parsed privately for the single distinct MUON_DEPENDENCY_INVENTORY record; no raw logs are published. tools/dependency_inventory.py validated exact commit/schema/scopes/coordinates/edges/artifact observations. Canonical JSON (sorted keys, compact separators, UTF-8) SHA256 **d7fa75a5196ca99a99e1df74a3e86a92c8e02aa3be54ad8da8c21c4a66acf989**. No Gradle or downloaded code executed locally.

All **286 unique selected Maven coordinates** exactly match the committed [September 30 inventory](evidence/2026-09-30-dependency-inventory.json): no coordinate added or removed. Inspected main build.gradle.kts, app/build.gradle.kts, settings.gradle.kts, gradle.properties and version-catalog path have no delta from that actual inventory head. Later main changes are separate source/report evidence; this is not a new Gradle resolution of the report head or proof of byte authenticity.

Queried [OSV querybatch](https://google.github.io/osv.dev/post-v1-querybatch/) using exact Maven name/version, 100/100/86 coordinates in three bounded HTTPS requests. Response positions matched request ordering, result counts checked, pagination handled if present; **no next-page token returned**. Only public dependency coordinates were sent, no private library, account or signing data. The API returns IDs/modified times, not full advisory details. No new full-detail review or modified-text comparison was performed.

| Batch | Coordinates | Raw response SHA256 |
| --- | --- | --- |
| 1 | 100 | `944db654f4a1bde30f0266bfad6de1f77f99d4db288b2d45764262cb91b25686` |
| 2 | 100 | `2d21f8a73e9f41f4a5c472e1993499ef77b7dbf6f4f1e72d4ea0855a297d5933` |
| 3 | 86 | `c1ba161990b0659f4a757f350bb4e221da1888847c472593d5aacd026813991d` |

## Result

**13 matched coordinates / 47 distinct advisory IDs**, exactly the same coordinate/ID pairs as the [prior recorded advisory snapshot](evidence/2026-09-30-dependency-advisories.json). No new/removed pairing. No match returned for either selected app runtime scope; matches remain confined to build-plugin and JVM-test scopes.

| Resolved scope | Coordinates | Matched coordinates |
| --- | --- | --- |
| `:app:debugRuntimeClasspath` | 109 | 0 |
| `:app:releaseRuntimeClasspath` | 109 | 0 |
| `:app:debugUnitTestRuntimeClasspath` | 141 | 1 |
| `:app:releaseUnitTestRuntimeClasspath` | 141 | 1 |
| `:build:classpath` | 151 | 12 |

| Matched coordinate | IDs | Exact matched advisory IDs |
| --- | --- | --- |
| `io.netty:netty-codec-http2:4.1.110.Final` | 8 | `GHSA-563q-j3cm-6jxm`, `GHSA-5x3r-wrvg-rp6q`, `GHSA-93wv-jw9v-4972`, `GHSA-c2gf-v879-257j`, `GHSA-c69g-56f8-xwqj`, `GHSA-f6hv-jmp6-3vwv`, `GHSA-prj3-ccx8-p6x4`, `GHSA-w9fj-cfpg-grvv` |
| `io.netty:netty-codec-http:4.1.110.Final` | 18 | `GHSA-38f8-5428-x5cv`, `GHSA-4mp9-239f-g9hg`, `GHSA-57rv-r2g8-2cj3`, `GHSA-6cqp-g7gg-8hr5`, `GHSA-6jqx-86gh-f27w`, `GHSA-84h7-rjj3-6jx4`, `GHSA-8c42-7qj2-3j46`, `GHSA-f6hv-jmp6-3vwv`, `GHSA-fghv-69vj-qj49`, `GHSA-gcjf-9mgh-3p7g`, `GHSA-hvcg-qmg6-jm4c`, `GHSA-jppx-w49h-x2qq`, `GHSA-m4cv-j2px-7723`, `GHSA-mvh2-crg5-v77c`, `GHSA-pwqr-wmgm-9rr8`, `GHSA-q4f6-jm68-57ww`, `GHSA-v8h7-rr48-vmmv`, `GHSA-xxqh-mfjm-7mv9` |
| `io.netty:netty-codec:4.1.110.Final` | 3 | `GHSA-3p8m-j85q-pgmj`, `GHSA-558v-64gr-wgg4`, `GHSA-mj4r-2hfc-f8p6` |
| `io.netty:netty-common:4.1.110.Final` | 2 | `GHSA-389x-839f-4rhx`, `GHSA-xq3w-v528-46rv` |
| `io.netty:netty-handler-proxy:4.1.110.Final` | 1 | `GHSA-45q3-82m4-75jr` |
| `io.netty:netty-handler:4.1.110.Final` | 6 | `GHSA-3qp7-7mw8-wx86`, `GHSA-4g8c-wm8x-jfhw`, `GHSA-c4c3-7fpv-j4q5`, `GHSA-c653-97m9-rcg9`, `GHSA-fccg-mwvh-qqg4`, `GHSA-x4gw-5cx5-pgmh` |
| `org.apache.commons:commons-compress:1.21` | 2 | `GHSA-4265-ccf5-phj5`, `GHSA-4g9r-vxhx-9pgx` |
| `org.bitbucket.b_c:jose4j:0.9.5` | 1 | `GHSA-3677-xxcr-wjqv` |
| `org.bouncycastle:bcpkix-jdk18on:1.79` | 1 | `GHSA-wg6q-6289-32hp` |
| `org.bouncycastle:bcprov-jdk18on:1.79` | 4 | `GHSA-574f-3g2m-x479`, `GHSA-9pwp-9qqc-pr26`, `GHSA-c3fc-8qff-9hwx`, `GHSA-qp49-qgx5-5m26` |
| `org.bouncycastle:bcprov-jdk18on:1.81` | 4 | `GHSA-574f-3g2m-x479`, `GHSA-9pwp-9qqc-pr26`, `GHSA-c3fc-8qff-9hwx`, `GHSA-qp49-qgx5-5m26` |
| `org.jdom:jdom2:2.0.6` | 1 | `GHSA-2363-cqg2-863c` |
| `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21` | 1 | `GHSA-r937-wjx7-w2jp` |

## What this does and does not settle

This is a dated database result, **not absence of vulnerabilities**. The scan covers these resolved Maven versions, not Android platform/SDK native code, shaded copies, JDK, Gradle distribution, JavaScript action dependencies or a complete APK/native SBOM. No matching runtime coordinates does not certify Muon or remove application/privacy/QA blockers. A match on a build/test dependency is not automatically a reachable Muon exploit.

Prior [qualified parent/source follow-up](2026-09-30-dependency-follow-up.md) and later SDK/crypto reports remain relevant: netty UTP instrumentation, Jetifier conditional execution, repository archive formats, Bundletool JWS versus compressed-JWE and signing/provider paths were narrowed individually. Their limited observations are not blanket clearance for all 47 advisories; this refresh does not re-review those callers or assert advisory text unchanged.

Do not force transitive upgrades or a beta compiler solely from IDs. Next dependency boundary: select one unresolved applicable caller/compatible upgrade, verify its pinned source and build/test interactions, or design pre-execution artifact verification with a reviewed publisher trust basis. Resolved/cache hashes observed **after configuration** are not pre-execution authentication. No compromised cache, attack or measured performance claim is made.

Local prose/diff checks only. Documentation-only latest-head CI/merge evidence belongs on the PR and #181. Device QA not needed for this report; all application PRs remain open pending user acceptance.
