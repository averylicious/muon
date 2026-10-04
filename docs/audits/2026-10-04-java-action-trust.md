# Java action archive verification — 2026-10-04

Inspected main `b1b572abc8d83789798006483be7509ed484013d`, branch `codex/java-action-trust`. GPT-6 / Codex desktop, effort not reported: source investigation, implementation and self-review; no independent review yet. CI-only change, no app/dependency/signing-identity/channel change. No phone QA needed. Source read and public-key parsing locally; no downloaded action/JDK/SDK code executed locally. Actions is the first full build under the changed setup.

## Confirmed boundary

Each of `.github/workflows/android.yml`, `baseline-profile.yml` and `dependency-audit.yml` uses the pinned [actions/setup-java commit](https://github.com/actions/setup-java/tree/b6effb05e454b25005698d916606bdc6ffcbf961), Temurin major17, no explicit signature input or Java dependency-cache input. Its `action.yml`38–40 defaults `verify-signature` to false. The inspected action package calls itself5.6.0; no new action version is adopted here.

The published TypeScript and executed `dist/setup/index.js` agree on these named paths:

- `src/distributions/base-installer.ts`57–93 (bundle77127–77164): `findInToolcache` wins when `checkLatest` is false; even checking latest uses a matching cached version without downloading. A true signature input by itself therefore does **not** verify a cached/preinstalled runtime.
- `src/distributions/temurin/installer.ts`79–127 (bundle79796–79825): only `downloadTool` invokes `verifyPackageSignature`; missing signature URL throws. Verification precedes archive extraction, tool caching and selection. Its API-derived package/signature URLs select the latest satisfying release when no cache entry exists.
- `src/gpg.ts`78–120 (bundle80219–80258): download detached signature, create temporary GPG home, import the action-bundled Adoptium public key, call `gpg --verify signature archive`, clean up. Exec/import/verify errors propagate; `setup-java.ts`124–126 marks the action failed. No private key or signing material is involved in this route.
- `src/util.ts`79–90 and bundled tool-cache `_getCacheDirectory`10608: `RUNNER_TOOL_CACHE` controls the discovery/cache root. GitHub's clean job temp directory is a separate root from the runner's preinstalled JDK cache.

This is a hardening gap, **not evidence of a malicious JDK, attacker access or current compromise**. The previous configuration trusted HTTPS distribution metadata/downloads and the runner tool cache. [Adoptium's archive guidance](https://adoptium.net/installation/archives/) offers detached signatures in addition to checksums.

## Change and tradeoff

Enable `verify-signature: true` and scope `RUNNER_TOOL_CACHE` to `${{ runner.temp }}/muon-verified-jdk` on each Java setup step. No cross-job restore populates that path. This deliberately takes the archive verification route instead of accepting the runner's warm JDK. The input is supported by this exact pinned action/bundle, no action update or custom download/verification framework is added. A lightweight policy test protects the signature input, fresh namespace, single pinned setup step and absence of a custom key/archive/cache or continue-on-error bypass in all three workflows.

A missing/invalid signature or unsupported future signing key stops the job before Gradle/SDK setup/signing-key restoration; do not disable verification to hide such a failure. The usual always-run report/cleanup paths may still run. Additional downloads depend on upstream availability and increase job work; no timing claim yet. Major17 remains unchanged, but a fresh resolution may choose a newer patch than the former warm runner image. The first exact-head CI must verify the actual signature route and subsequent compilation/tests/signatures, not just a green YAML check. Auxiliary workflows must also be checked.

## Dated source receipts and trust limits

Downloaded the above **public source bytes** from the exact commit using HTTPS, read only:

| File | SHA256 |
| --- | --- |
| action.yml | 54e5e8ef36785d9800698a0889ed17bacbdeb9f9fedcf3bbb0999b722c230bae |
| src/setup-java.ts | 8268ae8e751e553bb7348f97d5e4c8666d8db8e2e7bf99bfa7f416f4583efd54 |
| src/distributions/base-installer.ts | 597254b2a5cc10bf546257e74bd605d5260f5d2699ec9f33085d2b7a706b99d3 |
| src/distributions/temurin/installer.ts | 7a0f13cede297ecf9d7e518e5c699e32b50bfe9e12ca4f55e551f7e344827597 |
| src/gpg.ts | 4d6dcb5f709a74125f3a72fc8e40925d9cfcc16454b9417fd9570d64c4c36000 |
| dist/setup/index.js | fb752b406a248f3b1d6b2a475fdaa3ab38fd790e3e32013bb1babe957e7d50b8 |

A temporary isolated GPG home parsed only the action's public key: primary fingerprint `3B04D753C9050D9A5D343F39843C48A565F8F04B`, signing subkey `45B0F08FF61F64C1C1F61E173F4A517504A9CD61`. No user keyring was used. The live Adoptium packages public-key endpoint returned403, so no independently fetched current-key match is claimed; the pinned action's embedded key remains the trust basis. Adoptium's linked GPG documentation returned404 when opened. These are HTTP availability observations, not permission/approval rejections.

Hashes identify inspected bytes, not an independent action-bundle reproduction or a full bundle audit. Archive verification authenticates against that embedded key, not a transparency/reproducibility proof; runner/node/GPG/action/key/distribution metadata remain trust dependencies. Gradle caches, dependencies, Android SDK and other JDK execution/classpaths remain separate audit questions. No content hash for the extracted runtime or exact patch pin is introduced; a trusted repository writer can still alter build code after setup.

## Validation and next boundary

Local51 CI policy tests and whitespace check passed. Exact-head APK/unsigned dependency/baseline-profile CI and evidence that verification ran are pending. No device acceptance required for this CI-only PR; all existing app candidates stay open. Record final run/build receipts on the PR. After a verified main merge, the experimental owner may forward-integrate these workflow changes at a clean boundary. Do not touch their active checkout/session or publish Stable.
