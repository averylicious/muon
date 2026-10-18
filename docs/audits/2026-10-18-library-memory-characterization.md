# POCO live-library memory and refusal characterization

## Inspected source and scope

Verified ordinary Canary .1235, source #608 `439c9cdab163636ced9d0fe0396f6a0e07c57c60`, [successful build](https://github.com/averylicious/muon/actions/runs/39103003081). Actual selected APK was verified non-debuggable with the original package/signature. These measurements are not execution of pending #609. No Claude/subagents; GPT-6 / Codex desktop, exact variant/effort not reported. Source self-check is not independent review.

User-authorized rooted USB POCO surya, custom Android 17/API 37. Reported heap growth limit 384m / maximum 512m; these properties are not measured current allocation. No profiler, forced GC, root-manager/SELinux change, card eject or external network listener.

## Workload and procedure

A disposable shell-UID netcat fixture bound only to `127.0.0.1:37814` on the phone. No tunnel, host/LAN listener, firewall change or real Tauon-library modification. Static synthetic API responses implement version/playlists/one tracklist; all track IDs are synthetic, metadata short, audio non-playable and artwork absent. Largest accepted response is 10,977,512 bytes for 50,000 tracks; refusal response is 10,977,731 bytes for 50,001. Request sizes remain below the existing wire bound.

The stopped normal app's connection file was privately backed up, temporarily pointed at loopback, then restored byte-for-byte after the app stopped. The normal app was cold-started for each accepted size. A bounded sampler used `dumpsys meminfo` approximately every 1–2 seconds while UI dumps confirmed the exact visible song count. It sampled five more seconds after that confirmation. A conservative memory guard would stop this read-only workload above 256 MiB reported Dalvik allocation or 220 MiB Java PSS; neither guard triggered in the completed tests. Neither sampling cadence nor those guards guarantees catching every instantaneous allocation.

After the 50,000-song load, a refresh fetched 50,001 entries. A fresh UI-derived scrollable-list bound was used for the pull gesture. The app displayed its JSON array-limit error and retained the visible 50,000-song library, rather than crashing or publishing a truncated/empty replacement.

## Results

All memory numbers below are KiB from Android's app-summary PSS/RSS counters. Each column's highest sampled value may come from a different observation; do not add columns or interpret them as simultaneous allocations, hard limits or absolute peaks.

| Requested records | Observed outcome | Samples | Highest Java PSS | Highest total PSS | Highest total RSS |
| --- | --- | ---: | ---: | ---: | ---: |
| 1,000 | Loaded 1,000 | 7 | 28,848 | 134,541 | 250,068 |
| 10,000 | Loaded 10,000 | 7 | 22,056 | 133,561 | 249,912 |
| 25,000 | Loaded 25,000 | 7 | 119,424 | 221,487 | 334,808 |
| 50,000 | Loaded 50,000 | 7 | 203,972 | 304,983 | 419,248 |
| 50,001 | Explicit limit error; previous 50,000 retained | 8 | 118,436 | 231,758 | 348,776 |

First visible accepted counts were observed about 5.6–6.0 seconds after launch, including host/ADB/UI-dump cadence. These are not precise application load times or proof of a speedup. GC and caches were uncontrolled: separate 50,000-entry attempts sampled total PSS around 223 MiB and 298 MiB. The final completed refusal sequence reached 304,983 KiB total PSS (about 298 MiB), not a 223 MiB ceiling. The 962-song real-library idle baseline sampled 148,401 KiB total PSS after five seconds; it is not a controlled comparison with synthetic metadata/artwork.

## Preservation and cleanup

Immediate stopped before/after comparisons passed for every saved request field, the complete legacy-card native metadata and all audio/cover bytes, with no phone completed row added. Original connection-file bytes match exactly. Both parked pre-ROM normal/diagnostic inventories again match every backup path, size and SHA (16 files / 38,335,541 bytes and 3 files / 4,532,167 bytes). No media deletion or index repair.

Both apps stopped; ordinary .1235 / diagnostic .1238 remain installed. Original music volume 11 restored and separately read back. Own loopback listener stopped, its synthetic files removed, temporary private connection backup removed. Raw UI, server addresses, private indices, audio and logs remain local and are not published.

Two earlier host-driver attempts are not refusal passes: one intentionally stopped the app before the refresh step; the next gesture started above the actual scrollable list. Both restored settings/volume safely. The corrected attempt is the completed load/refusal result above. These were driver sequencing/coordinates, not demonstrated app crashes.

## Release-gate disposition

This provides selected device evidence for the live-library record ceiling and rejection preservation with short metadata. It does not establish whole-process heap bounds, absolute peaks, long-tag/near-encoded-byte-ceiling behavior, artwork-heavy browsing, many-playlist refresh overlap, queue/IPC peaks or large native saved-copy inventory behavior. Those remaining dimensions must not be called complete based on this table. #401 SD startup/Move latency remains engineering. Hardware/spoken/subjective user acceptance remains separate. No Stable promotion or application merge follows from this report.

## Follow-up #610 ordinary .1248

Verified head `6d7f8db0402e39ed197a83582a25289618bbdfc5`, [run 39122002920](https://github.com/averylicious/muon/actions/runs/39122002920), actual reports 1,745tests per variant and selected signed/non-debuggable APK checked. Three cold loads used the identical short-tag50,000 fixture and procedure, followed by50,001 refresh in the last process. First visible count observations7.486/6.249/5.786s include host/UI cadence, not precise application timing.

| Workload | Samples | Highest Java PSS KiB | Highest total PSS KiB | Highest RSS KiB |
| --- | ---: | ---: | ---: | ---: |
| 50,000 run1 | 8 | 80,428 | 208,135 | 327,728 |
| 50,000 run2 | 7 | 105,796 | 211,645 | 329,128 |
| 50,000 run3 | 7 | 146,980 | 242,445 | 357,740 |
| 50,001 explicit refusal, old 50,000 retained | 8 | 116,620 | 224,279 | 343,300 |

No memory guard triggered. These observations vary across runs; they do not isolate causality or establish an absolute peak/speedup. Full stopped before/after proof passed for every saved request field/native/audio/cover/connection byte, and both parked original inventories. Own loopback listener/files and temporary preference backup removed; ordinary app stopped; original volume 11 restored/read back. The host proof helper's initial marker-serialization namespace collision was corrected and the existing snapshots reverified; no app crash or additional destructive operation occurred. Selected saved-card playback subsequently reached PLAYING at5,046ms including250ms polling/ADB overhead, so #401 startup improvement remains unproven.

Latest source #611 has not yet been verified on device. See the current handoff for installed versions and active ownership; this report's earlier .1235 final-state paragraph describes that earlier boundary only.

### Near-byte-ceiling long-tag case on .1248

A separate synthetic1,000-track response held16,000-character ASCII titles:16,200,562wire bytes,16,088,550 encoded track-record bytes, largest encoded track16,089bytes. Each remains within its existing limit; no data was truncated. It loaded all1,000 records; eight observations sampled Java PSS170,912KiB / total PSS268,723 / RSS382,388 at their respective maxima. First count observation6.679s includes host/UI cadence. No guard triggered. This is one accepted near-bound case, not an over-byte-limit refusal or absolute heap measurement.

Full stopped saved-row/native/audio/cover/exact-connection and both parked-original proofs passed. Own fixture/listener/backup removed and music 11 restored/read back. The ordinary real-library check separately confirmed962 songs. Current operation state is in the handoff, not inferred from this historical test.

## Follow-up #611 ordinary .1251

Verified head `da66f416fe09213a9bddfb4198dc32bbc5f4f88a`, [run 39123780912](https://github.com/averylicious/muon/actions/runs/39123780912), actual 1,761tests per variant, no failures/errors/skips and zero lint errors/66warnings each. Selected signed/non-debuggable Canary APK and BUILD/hash/export safeguards verified; release APK not downloaded/inspected. Same short-tag fixture, sampler and guard, with three cold loads and an in-process over-limit refresh.

| Workload | Samples | Highest Java PSS KiB | Highest total PSS KiB | Highest RSS KiB |
| --- | ---: | ---: | ---: | ---: |
| 50,000 run1 | 7 | 114,252 | 230,085 | 348,244 |
| 50,000 run2 | 7 | 72,512 | 172,576 | 288,552 |
| 50,000 run3 | 7 | 132,984 | 228,345 | 341,984 |
| 50,001 explicit refusal, old 50,000 retained | 8 | 90,144 | 207,196 | 324,372 |
| 1,000 long-tag accepted, same 16.2 MB input | 8 | 194,088 | 289,466 | 403,088 |

All requested accepted counts loaded;50,001refresh displayed the explicit structural array-limit error and retained50,000. No guard triggered. First accepted-count observations6.197/6.247/6.101s; long-tag6.545s, including host/UI cadence. Results overlap .1248 and the long-tag observation is higher: no controlled whole-process memory reduction or speedup is established. The source eliminates the full standard-tracklist DOM retention, but GC/caches/other allocations and sampling cadence remain uncontrolled. These values are neither simultaneous phase totals nor absolute peaks.

Separate full stopped proofs after both fixtures matched every saved request field/native/audio/cover/connection byte and both parked-original inventories. Own listener/files/private preference backups removed; music 11 restored/read back. The real library loaded 962 songs, selected saved-card playback reached PLAYING at5,293ms including250ms polling/ADB overhead, and a further stopped final control snapshot again matched complete saved records/bytes. Later multi-playlist work must use its own fresh cleanup/preservation evidence rather than treating this paragraph as final device state.

### Aggregate multi-playlist record and byte refusals on .1251

Two additional cold-load/refresh pairs used ten playlists. Short-tag50,000 accepted first;50,001 spread across ten arrays (maximum 5,001) triggered the aggregate resource-limit message and preserved the old 50,000. This exercises aggregate admission rather than the previous single-array structural refusal.

The second pair first accepted50,000 short-tag records, then refreshed to 1,060 long-tag tracks across ten playlists:17,053,863encoded track-record bytes, largest record16,089bytes and largest individual response2,592,100bytes. All per-track/response/array/count bounds were within their limits; total encoded metadata alone exceeded 16 MiB. The resource-limit message appeared and old 50,000 remained. The local driver's selector was named50001 for routing, but the actual refused refresh contained1,060tracks, not50,001.

| Workload | Samples | Highest Java PSS KiB | Highest total PSS KiB | Highest RSS KiB |
| --- | ---: | ---: | ---: | ---: |
| Ten playlists /50,000 cold load, record pair | 8 | 51,184 | 156,870 | 274,268 |
| Aggregate50,001 refused, prior 50,000 retained | 8 | 92,568 | 202,701 | 320,232 |
| Ten playlists /50,000 cold load, byte pair | 8 | 67,800 | 162,909 | 277,704 |
| Aggregate17,053,863B refresh refused, prior 50,000 retained | 8 | 99,232 | 206,549 | 325,132 |

No guard triggered. First accepted-count observations7.352/7.690s include host/UI cadence. Same sampling/GC/cache caveats; no absolute peak or causal speedup established. After EACH pair, full stopped comparison matched every saved request/native/audio/cover/connection byte and both parked original inventories. Own fixture/listener/backup removed, original music 11 restored/read back. Remaining many-playlist/failure-fallback/artwork/queue/native-inventory dimensions are not cleared by these selected tests. Current phone state and source ownership are in the handoff.
