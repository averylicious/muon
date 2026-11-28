# Disposable ordinary-app service-access probe

Inspected main `6c6cc1ed13f114c6bb8a45311bb231bfb9440333`; target acceptance app is open [#302](https://github.com/averylicious/muon/pull/302), including [#205](https://github.com/averylicious/muon/pull/205)'s private PlaybackService. This fixture changes no Muon runtime code, schema or normal package/signing/feed. It is not a main service-hardening fix: #205 remains unmerged.

Author/self-review: GPT-6 / Codex desktop; exact variant/effort not exposed. Claude Code runtime `claude-opus-5-5`, High explicitly selected, was allocated the first draft but its provider rejected the request with a cybersecurity safeguard before any edits. No Claude implementation or independent review is claimed. Quota exhaustion did not cause that rejection.

## Fixture and CI isolation

`mediaentryprobe` is configured only when the existing validated `MUON_DIAGNOSTIC_DEBUG` mode is true. A manual **Android APKs** branch dispatch with `diagnostic_debug=true` builds it before Muon's signing keys are restored, using AGP's default temporary debug key. It uses the app's selected SDK and root-pinned AGP, no third-party runtime dependency. Standard main/branch/tag builds do not include this module.

The helper APK has package `dev.avery.muon.entryprobe`, label "Muon QA probe", version `qa.<run>` and its own UID/data. The diagnostic-only artifact `media-entry-probe-<full-SHA>` contains `mediaentryprobe-debug.apk`, SHA256SUMS and BUILD.txt; expires after14 days. It is never a release/Obtainium asset. CI validates the raw mode again and decodes the actual helper manifest: separate identity, no shared UID, no requested permissions/instrumentation, no extra components, no backup, exact launcher and only Canary package visibility. No original app/signing/secret access is granted to this fixture.

## Operations and evidence

From a visible foreground Activity, an explicit Run button inspects Canary's PlaybackService and refuses if absent, exported, disabled or same-UID. It never targets Stable/diagnostic by default and does not operate an exported baseline. Preconditions are recorded in visible text/logcat `MuonEntryProbe`.

With an enabled private target and a distinct UID it attempts:

1. Explicit `ACTION_MEDIA_BUTTON` service start with a pause key event. A `SecurityException` message identifying a non-exported target supports denial. A generic background restriction or unrelated exception does not.
2. Explicit service bind. Record the actual exception/message, returned Boolean and callback. False alone, null binding or timeout is inconclusive.
3. Framework `MediaBrowser` direct component connection, without any transport command. Connection failure is recorded separately; attribute it using narrow platform logs rather than treating every failed callback as proof of the gate.

Connections and pending callbacks are bounded to3 seconds, disconnected/unbound on completion or Activity stop; a repeated run begins after cleanup. No active-session discovery, notification-listener privilege, elevated permission, root, network, files or original storage are used. An unexpected connected/reached result requires investigation and does not trigger further transport operations. Since manifest inspection and invocation are separate, this is not an atomic proof against package replacement during a run.

API references: [Context startService/bindService contracts](https://developer.android.com/reference/android/content/Context) and [MediaBrowser callback outcomes](https://developer.android.com/reference/android/media/browse/MediaBrowser.ConnectionCallback). These API references establish how results are represented, not installed-device behavior. Compile against the selected SDK is CI's first Java/Gradle validation.

## Verification and manual boundary

Local CI/publication Python tests90+9 and whitespace check passed. The artifact/mode tests include forbidden identities/permissions/components, main/push/disabled-mode refusal before APK-tool execution, and diagnostic-before-signing wiring. No local Android compile. Exact-head full CI, diagnostic compile/lint/artifact and phone results belong on the PR and checkpoint; source-only status is not a runtime pass.

With current user authorization, coordinator may install this new fixture alongside the combined acceptance app, confirm distinct UIDs/no permissions and private target, keep original playback stopped/muted, run the button and inspect bounded result/platform logs. Remove only the disposable probe afterward, restore volume/settings, and preserve original app data. Do not alter Pixel lockscreen or POCO root/SELinux policy.

This narrows only ordinary foreign-UID **direct component** access. It does not establish hardware Bluetooth/headset compatibility, privileged/notification/platform session policy, malicious same-UID behavior, all companion integrations or full Stable readiness. #205/#302 stay open for their remaining gates even if this helper passes.
