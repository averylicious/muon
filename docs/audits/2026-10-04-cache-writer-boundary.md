# Gradle cache writer boundary — 2026-10-04

Inspected main `224fd29becbdf4b30f6b2cf2de38332745d975de`. GPT-6 / Codex desktop, effort not reported: source and retained public CI-log self-review. Continues [W1](2026-10-03-wrapper-trust.md) and [W2](2026-10-03-cached-distribution-trust.md); no workflow, cache, dependency or signing change.

## Action and repository evidence

All three Gradle workflows (`android.yml:89`, `baseline-profile.yml:44`, `dependency-audit.yml:58`) pin setup-gradle `0723195856401067f7a2779048b490ace7a47d7c`, with no cache-read-only/write-only override. Repository default branch is main, verified via GitHub API on 2026-10-04. Their triggers are push and manual dispatch, not pull_request or pull_request_target; Android push also includes Stable tags. This does not audit every executable on those jobs.

The [pinned action metadata](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/setup-gradle/action.yml) (previously recorded SHA256 `9f962f9604df64f176316d7eadb169348cd1b7041a754f417945f524bdd20840`, lines14–25) defaults to read-only outside the default branch. A write-only override would defeat that setting, but Muon does not supply it. Lines31–36 require an encryption key for configuration-cache saving/restoring; these workflows supply none. This is action behavior, not an authorization barrier against arbitrary code already executing in a job.

Actual post-step observations:
- [Unsigned Dependency34](https://github.com/averylicious/muon/actions/runs/37200593874), feature head `a2ce1296624b263abed7e818e94bcefc0193e624`: 12:00:39 UTC post log reports read-only and no state saving.
- [Unsigned main Dependency35](https://github.com/averylicious/muon/actions/runs/37201147486), `224fd29becbdf4b30f6b2cf2de38332745d975de`: 12:10:27–31 UTC post logs record successful instrumented-JAR, Groovy/Kotlin DSL, dependency and Gradle-home saves. The home key includes the full main commit; its listed paths include caches, notifications and .setup-gradle.

These two observations establish neither all trigger combinations nor the contents/trust of every saved entry. No cache archive was downloaded, poisoned, modified or cleared.

## Platform scope and remaining trust

[GitHub's documented cache scope](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache) allows current/default-branch restores (and the base branch for PR runs); parent/default-branch jobs cannot restore child-branch caches, and sibling/tag scopes are separate. This is the platform's documented contract, not an independent token/control-plane probe. Action read-only defaults alone must not be described as making branch code unable to invoke cache APIs.

On those documented rules, an ordinary separate feature/fork cache is not shown to flow into main. But trusted main jobs can share main cache state: unsigned inventory is a writer, so absence of signing secrets there does not isolate its executable dependencies from later main consumers. Existing code execution or workflow/repository write access remains outside wrapper validation's protection. No untrusted-writer route or compromise was established here.

W2 remains: a shape-valid unpacked distribution plus marker is not reauthenticated by its ZIP checksum. Artifact byte observations happen after configuration, not before plugin execution. Full cache payload authentication, raw-API authorization, fallback-key behavior and selected runtime provenance remain follow-ups. Signing files are configured under runner.temp, outside listed Gradle-home paths; that does not prove absence of credentials in arbitrary plugin outputs, logs or caches. No secret material was read.

## Verification and next boundary

Source/log report only; local prose/diff checks and documentation CI, no phone QA. Next decide a bounded pre-execution cache mitigation or independently authenticated artifact policy, after tracing actual restore selection and latency costs. Do not promote a hash manifest generated from an existing cache into a trust anchor. No whole-repository security or measured-performance clearance.
