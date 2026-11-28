# Partition startup review — 2026-10-13

## Scope and ownership

GPT-6 / Codex desktop implementation and source self-check; exact variant/effort not reported. No independent review, Claude, phones or subagents. Main audit branch `codex/partition-startup-review-oct13`, based on #555 `82893cd353e0a765a0e974cab178fb301043e9c7`, includes main `5a2c47f8c21517de7801ccadfe108c9ea6206dbf`. Experiment untouched. App stack stays open for inherited UAT; activation still disabled.

## Behavior

A large legacy library must be able to choose bounded storage before loading the old full native index. The staged fixed app-icon shortcut opens MainActivity in review mode, before MuonApp/LibraryModel/MediaController creation. One synchronized process admission chooses normal startup or review; all production OfflineStore.await entry points choose normal before initialization. Review cannot interrupt an already-selected normal process, and ordinary relaunch in a review process cannot reset admission.

Review performs actual bounded read-only legacy UID/schema validation through owned LegacyTransitionOwner connections, then closes those connections before recording the exact Fresh startup preference. It never instantiates SimpleCache or StandaloneDatabaseProvider, opens the full native index, creates/repairs missing bootstrap, migrates audio, or grants deletion. Settings are owned before writes; unknown child/connection/commit completion stays retained and prevents retries until a real process restart. Existing Preparing receipts use the separate explicit Resume control without retrying Fresh. Unsupported/missing receipts cannot be overwritten by this path.

Any initialized card download index, even empty, requires the original card for first opt-in: absent public rows do not prove absence of orphaned native audio. Completed Ready mode can still read phone copies with the original card absent; mutations remain paused as established in #553.

Controls explicitly direct the user to Android App info → Force stop → reopen. An activity close or recents swipe is not promised to destroy native owners. The app-info button only opens its own system details on user tap. No new exported component, path/configuration extras, arbitrary intent dispatch or system-setting changes. Shortcut, UI and production selector share the still-false PARTITION_OPT_IN_AVAILABLE gate. Existing initialized ordinary users can also review opt-in in Settings via #554/#555.

## Verification and limits

Local diff/branch preflight only; Actions is the first Android compile. CI/actual APK receipts belong on the PR after completion. JVM tests cover process arbitration, actual read-only SQLite/UID checks, oversized raw metadata remaining untouched, missing consent/bootstrap/card/existing root/native owner refusals, uncertain actual cursor and preference commit ownership. These do not establish platform shortcut behavior, UI layout, Force-stop semantics on the user's ROM, device performance or power-loss durability.

Pinned Media3 database sources checked at https://dl.google.com/dl/android/maven2/androidx/media3/media3-database/1.11.0/media3-database-1.11.0-sources.jar: StandaloneDatabaseProvider.DATABASE_NAME identifies the existing database; its normal helper can create/upgrade/downgrade, so review instead uses the existing LegacyTransitionOwner SQLite OPEN_READONLY connection. Dependency versions remain authoritative in Gradle.

## Next engineering

Compose final startup/recovery and absent-card offer behavior, then review activation readiness. Full supported original Delete/Move remains required by the user, provided in the preceding stack; no read-only substitute. Do not switch the activation gate until the composed engineering is reviewed. Inherited app/storage/playback/queue/TalkBack UAT remains pending. POCO not needed for this source/CI boundary, but will be needed for production lifecycle/card acceptance after engineering is enabled. No Stable release is authorized.
