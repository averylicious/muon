# Optional played-copy ownership — 2026-09-30

Follow-up to [#225](https://github.com/averylicious/muon/issues/225); based on merged main `d1dcfe9c11fbff05032c6bc73c04340c8b856723`, including #236's verified characterization. This app fix requires final-head CI and user phone QA; leave its PR open.

## Behavior

- One active optional copy and at most the most recently requested waiting song. Active duplicates do not replace a different next song; finished/cancelled identities do not become permanent suppression keys.
- Clear drops older waiting copies; clear/resize cancel the active copy before doing maintenance on the same existing worker. Repeated maintenance coalesces to the latest clear/resize. A deliberately newer copy can run after maintenance.
- Each optional job has a two-minute lifetime from actual worker start. Expiry cancels its CacheWriter and active OkHttp Call. Late-installed calls/writers observe cancellation. Foreground audio and explicit downloads retain their existing clients/lifetimes.
- Cache operations remain on the worker: no blind concurrent deletion, cache release or explicit-download removal. An interrupted partial copy is not marked playable; resizing may retain incomplete bytes within the chosen budget for later retry.

The two-minute policy allows optional prefetch to be abandoned without changing ordinary playback. It is not a hard bound on uninterruptible file/storage I/O. No real-device timing claim or socket-wide/dispatcher cancellation is made.

## Verification boundary

The existing actual-production loopback/cache fixtures become regressions: normal exact bytes/metadata and explicit-download preservation, clear/resize completing without the server releasing its body gate, and truncated input followed by maintenance. Nine queue/cancellation/deadline cases cover bounded latest work, duplicate handling, maintenance priority/coalescing, later intent, failure recovery, late cancellation and a shortened test-only scheduler deadline. The production timer uses remove-on-cancel so finished-job closures do not accumulate.

CI is the first real Android compile/execution; expectations are not passes until final-head XML is checked. Source API reading uses pinned Media3 Maven sources and OkHttp4.12.0 sources; the production Call.Factory is explicitly implemented, not assumed to be a Kotlin fun interface. Device QA is separate.

## Remaining scope

#212's byte-budget fix remains independent and is not included here; reconcile both OfflineStore changes in any combined candidate. This fix does not repair #179 SD-card lifecycle, #213 retained numeric identity, #230 partial move cleanup, aggregate library heap use, or background artwork/move queues. It does not restore queue/position after process death or change update/signing paths.

Manual QA: normal playback and subsequent offline copy; rapid track switching; clear cache and reduce its size during optional caching; explicit downloads remain; later listening can cache again. Report any app/error/control regressions. No agent phone access is implied.

Attribution: GPT-6, Codex desktop, effort not reported (user nickname Sol), author implementation/source self-check; independent review not performed, no Claude session.
