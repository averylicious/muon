# Move/remove characterization — 2026-09-30

Follow-up to [#230](https://github.com/averylicious/muon/issues/230). Production app baseline is main `b77619afd6f312584727aac809e4020475304b8b`; app storage code is unchanged from the earlier inspected main baseline. This slice adds tests/docs only: it does not fix move ownership, SD-card lifecycle or retained identity, and it does not touch user files or devices.

## Fixture and verification scope

`DownloadMoveCharacterizationTest` calls actual `OfflineStore.move`, `remove` and `removeAll`. It installs two disposable shelves into the store through reflection, using actual pinned `SimpleCache`, `DefaultDownloadIndex`, `DownloadManager`, CacheDataSource/CacheWriter and native SQLite. The production worker and main Handler are not replaced: a FIFO executor barrier waits for the queued worker; the paused main looper lets removals run before already-posted add callbacks. Reflection restores the previous singleton at teardown.

Robolectric captures actual `DownloadService.send*` service intents; it **does not instantiate/deliver those services**, drive Android mount broadcasts or exercise the app completion listener. Downloaders deliberately fail if started; the fixture never needs LAN/network access. Exact byte assertions and pre/post index assertions distinguish actual copying from empty fixtures.

| Case | Expected characterization |
| --- | --- |
| Normal move | Exact destination bytes, source retained, one destination Add with unchanged request after main dispatch. |
| Remove ID before pending Add | Actual Remove commands to both shelves followed by an Add for the removed ID when the callback drains. |
| Remove all before pending Add | Actual RemoveAll commands to both shelves followed by an Add. |
| Missing later source span | No Add, but partial destination spans without an indexed target download; unaffected source bytes remain. |
| Unknown source length | Copy rejected before destination writes, source bytes/index retained and no Add. |

Before CI execution these are **test expectations**, not confirmed reproductions. Latest-head CI must compile and execute both variants; check actual XML cases and failures. A successful unsafe-order case demonstrates the scheduled production command order, not end-to-end notification/service delivery or measured user data loss. Passing a characterization is not a fix.

## Pinned source reading

Pinned Media3 exoplayer/datasource source JARs from Google's Maven were inspected. DownloadService action/extra constants and `onStartCommand` map Add/Remove/RemoveAll intents to the matching DownloadManager operations. DefaultDownloadIndex stores the fixture's completed records; DownloadManager constructor/release and CacheWriter/cache-file behavior informed fixture ownership. Exact versions remain in build files; no runtime dependency or storage format change is made here.

## Next bounded fix design

Define operation ownership across queued/running move, explicit add, remove and removeAll; invalidate late add callbacks without erasing concurrent legitimate target data. A failed copy's partial spans need an ownership-aware policy. Preserve the source until destination completion is durable; #179's absent-card index-loss risk and #213 retained numeric identity remain separate blockers. A fix should add preservation and stale-operation rejection tests, not simply delete cache keys or release an absent cache. Device move/removal/notification QA stays pending and requires the user's current authorization.

Attribution: GPT-6, Codex desktop, effort not reported (Sol). Author source check, no independent reviewer or Claude session. CI is the first real compile; no local Android build claimed.
