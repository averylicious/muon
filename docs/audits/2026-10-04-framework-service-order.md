# #179 framework service create/stop ordering — 2026-10-04

Inspected main `f270cce8c646bc2e0d0f926adcbef38a89de6365`, branch `codex/framework-service-order`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading by the author, **not independent review**. Documentation only: no app, test or workflow change, and no device. #179 remains open.

**Question:** [#322](https://github.com/averylicious/muon/pull/322) (`docs/audits/2026-10-04-service-attach-lifetime.md`, not yet on this base) called `onCreate()` and then `onDestroy()` before draining the main looper. That showed Media3's queued attach callback restarting foreground updates on a destroyed instance. Does Android's ordinary create/stop dispatch ever produce that order?

## Sources

**AOSP** `platform/frameworks/base` at commit `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, from the verified `android17-release` ref. Paths are under `https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/`. Files are read-only, and line numbers count `\n`.

| File | SHA256 |
| --- | --- |
| `core/java/android/app/ActivityThread.java` | `2d7bd7d9a9978258776a98ffe60589585a215688da303da7c71be7af961dc210` |
| `core/java/android/os/Handler.java` | `002ea23610c36245b39d97d377e1abcd2c440708b0b2af9e6a1f16b3fdfcc22a` |
| `core/java/android/os/Looper.java` | `89f15ad65f57e53c36ee91b51ce0761cdb0fa949c4f5c4bd60b7604748acfe33` |
| `core/java/android/os/Message.java` (fetched for `compareMessages`) | `a556e56c188e83beab3bb140680babaf771e24062a246fd2d19e90d7e5b79185` |
| `LegacyMessageQueue.java` | `043ecf5c85fdb164c5df3da683831a66e823b5b93b8afb546943afed218cb537` |
| `CombinedMessageQueue.java` | `723297b2b18572bbc0433d53923c4ce869d5ea71703ee2c500f7906d8e7058df` |
| `CombinedDeliMessageQueue.java` | `b7afd4f6bbaad953388efcc368930d5d043d0cf599f89a9187cba8cc1d797eb3` |

The three queue variants come from subdirectories of `core/java/android/os/`. The root `MessageQueue.java` path returns 404, and **which variant a build selects at runtime was not verified**.

**Pinned Media3:** 1.11.0 `exoplayer.jar` sources, SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`. Coordinator additionally read Google Maven `media3-common-1.11.0-sources.jar`, SHA256 `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e`: `Util.createHandlerForCurrentOrMainLooper`807-829 and `createHandler`845-847 delegate to ordinary `createHandler` on the current/main looper; no asynchronous handler request.

**Not matched to the user's phones:** this commit is not verified as the Pixel QPR1 or POCO build.

## The ordinary route

1. **Binder to main queue.** `ApplicationThread.scheduleCreateService` (1308-1323) and `scheduleStopService` (1391-1397) each call `sendMessage(H.CREATE_SERVICE/STOP_SERVICE, …)`.
   - That builds an ordinary message (4376-4402; `async` is false at 4377) and calls `mH.sendMessage`.
   - `mH` is the main-thread `H` (437, class at 2611).
   - Start arguments follow the same route as `SERVICE_ARGS` (1367-1388).
2. **One message per callback.**
   - `H.handleMessage` sends `CREATE_SERVICE` to `handleCreateService` (2791-2812), which calls `service.onCreate()` (5644).
   - `STOP_SERVICE` goes to `handleStopService` (2865-2873), which calls `s.onDestroy()` (5844).
   - `onCreate` and `onDestroy` therefore run in **separate** main-thread messages.
3. **Sequential dispatch.** `Looper.loop` (373-400) repeatedly runs `loopOnce` (230-361). That takes **one** message from `next()` (232) and dispatches it (296) before taking the next. Re-entering `loop()` only logs a warning (378-381).
4. **Media3's post.** `attachService` posts `notifyDownloads` with `Util.createHandlerForCurrentOrMainLooper().postAtFrontOfQueue(…)` (`DownloadService.java` 984-986). This happens during `onCreate`, on the main thread, so the post goes to the main looper's queue.
5. **Front-of-queue placement.** `postAtFrontOfQueue` (609-611) leads to `sendMessageAtFrontOfQueue` (813-822), then `enqueueMessage(queue, msg, 0)` (845-855).
   - **Legacy queue:** puts any `when == 0` message at the head (`LegacyMessageQueue` 721-728).
   - **Combined and Deli queues:**
     - Each gives a `when == 0` insert a decreasing negative sequence number (`CombinedMessageQueue` 2876-2879, `CombinedDeliMessageQueue` 400-403). Their own comments call front inserts the documented LIFO exception (C2641-2646, D124-129).
     - Their legacy fallbacks use the same head rule (C1479, D511).
     - `Message.compareMessages` (656-668) orders first by `when`, then by sequence.
   - `STOP_SERVICE`, sent with an uptime `when > 0`, therefore sorts after it in every inspected variant. That holds whether the stop message was queued before or during `onCreate`.

**Source-supported expected ordering:** the inspected insertion and dispatch route puts Media3's front-of-queue callback ahead of the normal stop. A stop is a separate ordinary message with `when > 0`. A front-of-queue post made during `onCreate` sorts ahead of it in each variant's inspected insertion code (and, for the concurrent variants, by the comparator they use). No ordinary counter-route was established in these sources.

This is **not** proof for every queue or platform route. It assumes:
- the concurrent variants' `next()` honours that comparator, including under barriers (not audited);
- the service lifecycle runs on the ordinary main looper, as the inspected ActivityThread route does;
- the build selects one of these variants.

#322's destroy-first ordering therefore stays a **callback-retirement design constraint**. It is not a demonstrated device behaviour. Its fixture passed in Android run 569 at `0c299a033229d422fd772e903ad7707741f17e03` ([#322](https://github.com/averylicious/muon/pull/322) CI), which shows JVM behaviour under that artificial order. It is no device observation.

## What remains open

- **Variant selection:** the runtime queue variant, its feature flags, and whether the concurrent paths' `next()` honours the comparator under barriers (C781, D654) were not audited for correctness. Pinned `Util` creates an ordinary handler; both its post and the ordinary stop message are synchronous. Queue/barrier implementation correctness remains outside this source pass.
- **System server:** `ActiveServices` was not inspected. Nothing here shows when the system server sends a stop, or that create and stop for one service always arrive in this order.
- **Other routes, all unknown:**
  - instrumentation or tests calling lifecycle methods directly (as #322 does);
  - exceptions in `onCreate` (5653-5658): the service isn't registered, so a later stop finds nothing (5866-5868);
  - nested loopers;
  - vendor patches;
  - the phones' actual builds.
- **Process death:** it skips `onDestroy` entirely. Nothing here says whether delayed service intents survive process death.
- **Muon on main:** `MuonDownloadService` and `MuonCardDownloadService` override neither `onCreate` nor `onDestroy`, and no Muon main source calls `stopService` or `stopSelf`. Media3's `stopSelf`/`stopSelfResult` (`onIdle` 853-857, `onTimeout` 705-706) are requests. On the inspected app-side route, the stop comes back as a later `STOP_SERVICE` message rather than destroying the service within the current message. The system-server side wasn't inspected. The pending #321 overrides (not on main) only add work after `super.onCreate()` and don't stop the service.

## Constraints for future barriers and manager release

- **What ordering doesn't retire:** the expected queue order makes the *destroy before attach callback* case unexpected on the inspected ordinary route. It doesn't rule that case out for unverified queue implementations, handlers or platform routes. Even where the order holds, it doesn't retire:
  - the helper's listener;
  - its restart path;
  - the periodic updater of a **live** service;
  - the manager's tasks, readers or copiers;
  - old card generations.
- **A barrier still isn't a receipt:** a main-thread barrier after `onDestroy` orders work behind earlier messages, but it doesn't prove that no callback or task will touch a manager later.
- **No release permitted:** any in-process manager release still needs the full ordered stop from the [service-owner contract](2026-10-04-service-owner-contract.md). Nothing here permits a cache release, rebind or adoption, or rules out other architectures.

**Checks run locally:** `git diff --check` and the CI prose check. No device QA is needed for this report.

## Coordinator review

GPT-6 / Codex desktop, effort not reported independently reviewed Claude authors08dfb146/76a064c7 against immutable AOSP insertion/dispatch/comparator bytes and pinned Media3 paths. Requested qualification of universal ordering claims; no ordinary counter-route was established, but concurrent next()/vendor correctness is not proved. Own common-handler source follow-up and main integration are self-reviewed. JVM fixture passage is separate from platform ordering. No phone QA needed.
