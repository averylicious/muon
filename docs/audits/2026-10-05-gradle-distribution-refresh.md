# Fresh Gradle wrapper distribution boundary — 2026-10-05

Inspected main6cadbb5b3d6fbaa4a8f786b71481a27f931dc7df and implementation3b6615eb3c8f8de60c56cf6f4b986134078c7581, merged as e0c63069c394a60a0d3b6c1a9495bb8bdf7feae2 in [#329](https://github.com/averylicious/muon/pull/329). GPT-6 / Codex desktop, effort not reported authored/self-reviewed the change and verified source/CI. Claude Opus5.5 / Claude Code, High selected independently reviewed design and then the exact implementation, with no implementation blockers; runtime confirms model, not effort. Its read-only review did not rerun tests or inspect CI. No device, signing-secret or experimental-checkout access.

## Problem and resulting behavior

The pinned wrapper's `Install.createDist` accepts an extracted home with its `.ok` marker and expected structure before the fresh-install ZIP checksum path. The pinned Gradle action restores extracted wrapper homes separately; its after-restore exclusion errors warn rather than fail. Existing wrapper-JAR verification therefore does not authenticate a restored executable distribution. This is a source-supported trust boundary, not evidence of a compromised cache or an untrusted writer reaching a signed build. See [restore selection](2026-10-04-cache-restore-selection.md) and [cached distribution trust](2026-10-03-cached-distribution-trust.md).

All three workflows now run `tools/isolate_gradle_distribution.py` after setup-gradle and before SDK installation, first Gradle invocation and signing restoration. On the reviewed disposable hosted-Linux/default-home configuration, it preserves only `GRADLE_USER_HOME/wrapper/dists` outside wrapper lookup, in the job's temporary directory. Ordinary dependency/plugin/transform/toolchain caches remain. The subsequent trusted wrapper must perform fresh installation using the checked-in ZIP checksum. This adds a ZIP download per job; it is not a performance optimization.

The helper refuses local/non-hosted execution, noncanonical/symlinked paths, unexpected Gradle home, changed wrapper properties, occupied destinations and incomplete moves. Separate-filesystem moves preserve bytes; a failed/partial move stops the job. Full-byte properties SHA256 `1bb6d43ff8af30abb0eaf03fe58f636ed9b37f758a748bf30a9cebd809639c9d` binds the inspected install/ZIP paths and distribution checksum. A wrapper/configuration upgrade must update this receipt after source review. No general Java-properties parser or arbitrary deletion is introduced.

`cache-cleanup: never` on all three setup steps disables an optional post-action Gradle-provisioning fallback that could otherwise execute after a guard failure. It trades optional cleanup/cache size for this narrower execution boundary. Post-action daemon stop remains: the inspected route reads this job's build-results JSON and uses homes recorded by actual builds, which normally point at the freshly installed distribution. Arbitrary plugins/job code forging metadata or modifying executables is outside this guarantee.

## Immutable primary sources

Read public sources at [gradle/actions0723195856401067f7a2779048b490ace7a47d7c](https://github.com/gradle/actions/tree/0723195856401067f7a2779048b490ace7a47d7c/sources/src), without executing fetched source. Focused source receipts:

| Relative source | SHA256 |
| --- | --- |
| setup-gradle.ts | 95f841c9e91db0f3ae4ad6a2080ab07eed863ab2f5f6edaef673428b0c21bbf7 |
| execution/provision.ts | 6963d7b4433bfbff344ee2d1be0295ea100384547ec2a21cc4aad52f84c6b15a |
| caching/cache-cleaner.ts | f8c0208f0135f404a520cc4d34ec7dcaea31df403037d0f52a864370ce453c3e |
| daemon-controller.ts | 00676e311db074ce869d99ef104502d912a18bfaecfde67b4ce53f672fa7a7dd |
| build-results.ts | 656d6081302d6a5c3c07dcd7e4af73f4744ec2fd07a60dfbdad5a6f096b81b72 |
| configuration.ts | 03ae4b8107c06f6f597c0157683953fd4a84a119251131451a7851422c479ddb |

Main/post entrypoints, wrapper-validator, build-scan, dependency-graph and execution routes were also read. Default setup restores cache and hashes the wrapper JAR, but its unspecified gradle-version does not launch/provision Gradle before the new guard. Java home lookup is separate from Gradle execution. Cache-cleaner prepare writes a timestamp; post cleanup is disabled by the new input. Restore/extractor receipts remain in the preceding report. Prior focused compiled main snippets agree, but a complete main/post source-to-bundle equivalence audit is not claimed.

The [pinned Gradle Install source](https://github.com/gradle/gradle/blob/v8.13.0/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/Install.java) and previously inspected checked-in JAR bytecode establish marker reuse versus fresh ZIP verification. Wrapper JAR SHA256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` is already checked before cache restore. The ZIP checksum in the reviewed properties is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

## Exact-head verification and limits

62 local Python CI-policy tests passed, including disposable-file preservation, refusal paths, property changes, partial moves, CLI environment restrictions and workflow order. These are not Android tests. Android CI is the first compilation.

At implementation3b6615eb3c8f8de60c56cf6f4b986134078c7581, [Android586](https://github.com/averylicious/muon/actions/runs/37215034883), [unsigned Dependency37](https://github.com/averylicious/muon/actions/runs/37215034828) and [Baseline15](https://github.com/averylicious/muon/actions/runs/37215034930) passed. Actual logs in all three show restored distributions preserved outside lookup, then a fresh Gradle ZIP download. Downloaded Android XML:471 tests per variant, zero failures/errors; debug BUILD.txt matches full SHA/run586/Canary.586. [Artifact app-debug-3b6615eb3c8f8de60c56cf6f4b986134078c7581](https://github.com/averylicious/muon/actions/runs/37215034883/artifacts/11307289080). Final edited-body Branch check passed; live main6cadbb5, strict required checks/admin enforcement verified before exact-SHA merge. No device QA needed for this CI-only fix.

Main Android587/Dependency38/Baseline16 passed. Actual [Canary.587](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.587) BUILD.txt matches e0c63069c394a60a0d3b6c1a9495bb8bdf7feae2/run587/version.587. Main Android logs also show preservation before fresh ZIP download, followed by normal post daemon stop at the newly installed wrapper home. Wider post/cache behavior is not fully audited. The guard does not authenticate plugins, dependencies, transformed/generated jars, SDK/JDK toolchains, runners, arbitrary job code or all action bundles. It does not prove equal bytes across every artifact, a malicious-code isolation boundary or app behavior. No dependency versions, package/signing identities or feeds changed. Full supply-chain and production storage work remain open.
