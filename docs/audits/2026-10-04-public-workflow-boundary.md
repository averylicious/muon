# Public repository: PR and CI trust boundaries — 2026-10-04

Inspected main `12ab1e44f4e4de9d91eff383bdd8f117f29818e0` and freshly fetched experimental head `e1bf045c1fa7139c4966e480f2f06941a703ddfc`. User requested verification after making the repository public and setting issue/PR creation restrictions. GPT-6 / Codex desktop, effort not reported: API/source investigation and own report self-review. Allocated Claude Opus5.5 / Claude Code / High requested completed a separate bounded read-only source review; the runtime confirmed claude-opus-5-5 but did not expose effort. Its review agreed no existing direct outside-PR privileged/signing route was established and highlighted mutable PR workflow YAML and trusted-writer qualification. Coordinator independently checked those qualifications and authored this report; own edits are self-reviewed. No app, repository settings, credentials, signing material or device changes. Claude is idle after the review; runtime stream snapshot 68% five-hour /6% weekly used, while its model response did not expose those values. Quota is a dated snapshot, not current availability.

## Live setting evidence

- Repository public, default branch main.
- GraphQL Repository `issueCreationPolicy` and `pullRequestCreationPolicy`: both **COLLABORATORS_ONLY**. REST also returns pull_request_creation_policy=collaborators_only. Those permanent creation settings are intact; write-access collaborators can create, and public visibility does not grant write access.
- Temporary Repository `interactionAbility`: NO_LIMIT; REST interaction-limits empty. This differs from permanent creation restrictions. Do not misreport the empty temporary response as open issue/PR creation. The user-level temporary interaction API required an additional read:user scope, which was not requested or added; effective repository settings above were independently available.
- Actions default token read-only, workflow PR review approval disabled; public fork workflow approval **first_time_contributors**. Optional tightening is **all_external_contributors** so a previously accepted external contributor still needs workflow approval. No setting changed in this check.
- Actions allowed_actions=all and sha_pinning_required=false globally. Current workflow uses are pinned to full SHAs. Optional global pin enforcement/allowlisting is separate from proof that the current workflow is unsafe; compatible changes need their own review.
- No repository self-hosted runners. Main strict required Build, test and sign + Branch direction (GitHub Actions app15368), admin enforcement enabled, force-push/deletion disabled.
- Secret scanning and push protection enabled; non-provider patterns and validity checks disabled. These settings do not prove absence of every secret in history, artifacts or installed-app access. Secret-scanning API metadata returned zero open alerts without reading secret values; this is not a new whole-history secret scan.

## Source execution and secret boundary

The four registered active workflows are Android APKs, Baseline Profile tools, Branch policy and Dependency inventory.

- `android.yml`: same-repository push/tag and workflow_dispatch only. It has no pull_request trigger. The build job is contents:read; signing material is restored only for a full build. Publication is a separate contents:write job only for main Canary or approved tag paths. Arbitrary forks cannot turn a push in their repository into a Muon secret-bearing push job. Manually dispatching or copying unreviewed external code into a Muon branch crosses the trusted-writer boundary.
- `baseline-profile.yml`: same-repository path-filtered push/manual dispatch; reads the debug signing secret. No pull_request trigger. Treat write/dispatch access as trusted here as well.
- `dependency-audit.yml`: unsigned, contents:read, trusted push/manual triggers, no signing-secret step.
- `branch-policy.yml`: pull_request trigger, contents:read, no named secrets or signing. On main it checks out destination policy and candidate history separately, executes only destination policy/test files with python -I, and supplies PR metadata through environment/quoted arguments. Candidate source is inspected as history, not selected as policy execution by main's checked-in steps. However, a PR can also propose changes to the workflow YAML itself; destination Python does not make that YAML immutable. Workflow edits still need source review. The second required signed-build check is produced by same-repository push, so an outsider cannot satisfy that gate simply by changing their fork PR workflow.
- No pull_request_target, workflow_run, issue_comment or repository_dispatch triggers were found at these inspected heads. No input-spliced shell evaluation was found in the named branch-policy invocations.

GitHub documents that ordinary fork pull_request jobs do not receive repository secrets (apart from the restricted GITHUB_TOKEN); approval to run is not approval to disclose secrets. An external PR may propose workflow changes, so this remains a fork/read-token boundary, not permission to trust its job output or merge the change. See [Actions settings](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository) and [workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

**Disposition:** no direct outside-PR route to the existing signed APK workflow/secrets was established. This is qualified source/settings evidence, not a claim that every GitHub App, OAuth client, trusted writer, third-party action, cache or future workflow is safe. Installed integration permissions and the user's account sessions were not audited here. Current protected-main gates do not stop a trusted branch writer from running branch code with signing access; that is the existing authorized build trust model.

## Experimental difference needing forward integration

At the inspected experimental head, Branch policy executes candidate policy/test code from the head checkout and lacks main's separate destination checkout and python isolation. A candidate can therefore alter its own validation: a policy-integrity gap, not an established fork secret leak. Recent pre-cache wrapper verification and Stable tag ancestry/publication safeguards are also absent, reflecting unsynced main hardening. Do not describe these history differences as deliberate removal or infer a new main vulnerability.

Have the experimental owner land a separate reviewed current-main forward integration at a clean boundary. Verify exact combined head and both required checks. Do not move the active experimental branch or resume the user's experimental Claude session from this audit.

## Unexpected PR closure

#302 closed without merging at 2026-10-03T19:57:48Z and was reopened at user request. Timeline attributes the shared account and no app, source or commit; that does not identify the acting client. No closing command was identified in repository workflow/tools or allocated audit-Claude tool calls around the event. Cause remains unknown. Report any further unexpected closure encountered during ongoing work, preserving timeline evidence; do not silently treat it as user rejection, rotate credentials automatically or claim account compromise without evidence.

## Acceptance and limits

Read-only validation only; no adversarial fork submitted and no workflow/setting changed. The latest [#302 combined build](https://github.com/averylicious/muon/pull/302) remains open for user phone acceptance. No Stable release/tag, device testing or experimental update is authorized by this report. GitHub's [permanent PR access controls](https://github.blog/changelog/2026-02-13-new-repository-settings-for-configuring-pull-request-access/) are distinct from [temporary interaction limits](https://docs.github.com/en/rest/interactions/repos).

## Continuation receipt

Rechecked after main #314 at `b1067d6d21849a71aa40787e2c4708faf236860e`: permanent creation policies remain COLLABORATORS_ONLY, fork approval remains first_time_contributors, and #302 timeline still shows only the known closure/reopening. Main workflow trigger/secret boundary is unchanged by intervening test/docs changes. Documentation now follows repository visibility rather than calling public Canary outputs private; no setting or credential change was made.
