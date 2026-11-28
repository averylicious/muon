# Library-load ownership audit checkpoint — 2026-09-30

## Scope, ownership and authorization

GPT-6, Codex desktop, effort not reported (user nickname Sol), owns `codex/library-load-ownership` in the isolated main-audit worktree. Destination is main `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`; the user/Claude own the experimental checkout. No Claude session or phone was used. The user's 2026-09-30 instruction keeps every phone-QA-dependent PR open. No Stable publication or artifact deletion is authorized.

## Source finding and change

[#208](https://github.com/averylicious/muon/issues/208): Disconnect cancelled a load without joining, and an old unconditional `finally { busy = false }` could mark its replacement idle. Both online refresh and opening downloads now share `LibraryLoads`: install an ownership token before launch, revoke it before cancellation, and let only the current token clear loading. Disconnect remains immediate. Check cancellation/ownership after suspension before publishing library data, offline fallback, errors or progress. Partial-playlist and same-server fallback rules are retained.

Undispatched startup deliberately reaches cleanup even when the ViewModel scope is already cancelled, and avoids depending on assigning a returned Job before synchronous completion. Reviewed this against the pinned coroutine core sources from Maven Central (`kotlinx-coroutines-core-jvm` sources) rather than only latest documentation. The matching coroutine-test published sources were also inspected; versions live in Gradle.

Five JVM tests exercise the actual production load coordinator using controlled coroutine ordering: delayed old cleanup cannot clear replacement busy or admit a third load; cancelled results and obsolete errors cannot publish; synchronous completion/handled error permits Retry; cancelled scopes cannot publish or leave busy set. This is not a Robolectric ViewModel integration test or device test. Coroutine-test is a JVM-test-only dependency; no production dependency was added.

## Checks, build and pending manual QA

Local whitespace/branch-policy checks only; no local Android compile. GitHub Actions is the first compile. Consult the implementation PR and #181 for the exact head/run and final results; do not assume the initial push passed. No test APK is promised while artifact storage is blocked.

Manual QA remains pending: start a slow refresh, Disconnect and immediately reconnect, then repeat with Listen offline/Retry. The replacement must retain loading until it ends, repeated taps must not start extra loads, and old errors/library results must not return after Disconnect. Confirm partial playlists and downloaded fallback still work. A successful automated suite does not establish this phone behavior.

## Other parked boundaries

- [#205](https://github.com/averylicious/muon/pull/205), head `b3e86da810810d51c1bf91769642119a2e810971`, has successful run 364 and a signed Canary artifact; service-private notification/headset/lockscreen compatibility QA is pending. Leave open.
- [#206](https://github.com/averylicious/muon/pull/206), head `d85c602eb2bc27b7852e14f75c4e229c73cf1c63`, passed builds, both unit suites, lint and APK identity in run 366, but the overall workflow failed uploading artifacts due to storage quota. No APK/report artifact or release exists for that run. Leave open for full checks and QA; it is not included in this independent main-based fix.
- Latest verified published main is Canary .363, on the destination above. It does not include #205, #206 or this fix and may replace the experimental UI if installed.
- Artifact deletion approval is still pending. Do not interpret generic audit continuation as permission to delete old artifacts; do not retry unchanged heads while the storage condition remains unchanged.
- [#207](https://github.com/averylicious/muon/issues/207) is the next bounded artwork identity/in-memory invalidation fix. Offline cache-removal/data-preservation #179 remains a significant unresolved release question.

## Resume

Verify live heads, checks, main and experiment before changing anything. Finish this PR's exact-head checks and retain its pending phone QA. Then take #207 or a documented audit workstream. CI upload failure is distinct from compile/test failure. Use #181 and PR comments for newer results rather than treating this checkpoint as frozen live status. Keep one writer per branch/worktree and leave a durable checkpoint before quota exhaustion.
