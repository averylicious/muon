# Published-Canary baseline audit — 2026-09-29

Follow-up to A6 in [the audit baseline](2026-09-27-main.md), tracked under [#181](https://github.com/averylicious/muon/issues/181). Inspected main `8d0b24b236f73a2c2b846abffcf229ec8a72ca34`, after #192. Author and self-reviewer: GPT-6 Astra, Codex; effort not reported. No other agent assigned.

## Reproduced problem

`tools/ci_scope.py::last_canary` treated the newest reachable Canary Git tag as a completed publication. It never checked the associated GitHub Release. A tag without a published release, or a tag moved away from a release's original target, could therefore hide app changes from the subsequent docs-only push comparison.

Two real temporary-Git-history tests failed before the fix: both incorrectly selected documentation-only checks after app work was tagged but not verifiably published. No production release was deleted, moved or interrupted to reproduce this. A failed publication leaving a tag was a recovery scenario, not evidence that the user's latest successful release was incomplete.

## Change and tests

The selector now intersects reachable Canary tags with paginated published-release metadata. Eligible releases are non-draft prereleases with a publication timestamp and all expected assets uploaded/nonempty. The tag must resolve to the full target SHA recorded by Muon's publisher. The highest eligible run number wins. Failed API access, timeout, malformed data or no eligible baseline selects a full build; ordinary feature/docs branches still need no API call.

Regression coverage includes both original failures, successful published/docs-only behavior, incomplete assets, drafts, unknown commit targets, pagination, unrelated Git histories, timeouts and API errors. The build scope step receives only the existing read-only GitHub token. Signing secrets remain in their later build step; publication retains its separate write-permission job. No app, signing identity, package, channel or Stable-promotion behavior is changed.

GitHub's [List releases API](https://docs.github.com/en/rest/releases/releases#list-releases) distinguishes releases from ordinary tags, supports pagination and requires only Contents read permission for private-repository metadata. Muon's existing publisher records a full target SHA and verifies artifact commit/run/version/checksum before upload. This selector validates metadata consistency; it does not re-download/hash every historic APK, protect against a malicious repository writer, or prove old releases remain downloadable after a later mutation.

Exact-head checks, self-review, PR/merge and publication evidence are on the implementation PR and #181. Local Python regressions are the first verification; the latest-head Android APKs and Branch direction checks remain required before merging. No phone test is needed for this workflow-only behavior. API failure may spend extra build time, deliberately preferring that to suppressing a missing update.
