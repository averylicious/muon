# Gradle cache scope and current writer trust

Inspected main `20ea53a1fbfb3f117efc2cc093ded8eab268fa58`, continuing [cached distribution trust](2026-10-03-cached-distribution-trust.md). GPT-6 / Codex desktop, effort not reported; source investigation/self-review. No cache archive payload, credential or signing-store access; no workflow or cache deletion/change.

## Repository and pinned action

All three Gradle workflows (`android.yml`, `baseline-profile.yml`, `dependency-audit.yml`) use only push/manual triggers and the pinned setup-gradle commit `0723195856401067f7a2779048b490ace7a47d7c`. None runs Gradle on pull_request, pull_request_target, issue_comment, workflow_run, repository_dispatch or an external artifact cascade. The separate Branch policy workflow does not restore Gradle caches or access signing keys. Android signing keys are restored later under RUNNER_TEMP; that directory is not in the inspected Gradle cache definitions. This is source path inspection, not proof no build could ever write sensitive data into a cached path.

Pinned setup-gradle action.yml17–22 defaults cache-read-only true when repository metadata exists and ref_name differs from default_branch. Current workflow inputs do not override it. Pinned bundled code's cache configuration reads that input; its Gradle user-home entries include wrapper/dists plus dependency and compiled-script/transform caches. Main can save; other repository branches normally restore only. No configuration-cache encryption key is supplied, so the action does not restore/save configuration-cache state by that supported path. This does not audit every filesystem path or plugin side effect.

Primary pinned action source: [setup-gradle/action.yml](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/setup-gradle/action.yml). The prior cached-distribution report records the inspected bundle hash and executable-content scope.

## Host service isolation and live metadata

GitHub documents cache restore scope as current/default branch, plus PR base when applicable. Child/sibling branch or pull-request merge-ref caches cannot be restored by main. Low-trust events in default-branch context are read-only by default unless explicitly overridden; trusted push/manual runs can write. [GitHub cache reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching), consulted October3, and [June26 change](https://github.blog/changelog/2026-06-26-read-only-actions-cache-for-untrusted-triggers/).

Read-only REST inventory, all pages:278 cache entries listed, all refs/heads/main at inspection. This is metadata, not verified contents, creator attribution or lifetime history. It supports the current scope observation, not immutable provenance or absence of a compromised trusted writer.

## Disposition and remaining assurance

No untrusted-event-to-main-cache writer route was established in these workflows. Fork pull requests do not receive the signed Android/Gradle job; changing that boundary would require a new review. Trusted branch pushes already execute repository build code with signing access, so isolating a cache cannot defend against a malicious authorized writer or compromised action/tool.

W2 remains a supply-chain trust limit: wrapper checksum verification does not authenticate restored unpacked distributions, dependencies, compiled scripts, SDK/JDK or all runner inputs. No poisoned cache/exploit or secret leak was established. Avoid generating checksums from current cache contents and calling them independent trust. Keep cache/signing isolation, artifact integrity provenance and compatible dependency updates as separate remaining work.
