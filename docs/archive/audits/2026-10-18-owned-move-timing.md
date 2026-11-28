# Fresh owned Move verification and timing

## Source and procedure

Source #609 `ad617a15146f2c449499b75f10838576d408456d`, verified separate diagnostic .1247 [run 39121989916](https://github.com/averylicious/muon/actions/runs/39121989916). Actual reports 1,742tests per variant and selected APK identities/signature/debug mode/export/BUILD/hash verified. Rooted user-authorized POCO custom Android17/API37. GPT-6 / Codex desktop, exact variant/effort not reported; no Claude/subagents. Self-check is not independent review.

Fresh UI Card-to-Phone and later Phone-to-Card Moves each operated on the one disposable complete 3,149,055-byte copy. Neither used an old completion receipt. A stopped immediate baseline and stopped after snapshot were compared after idle; no active storage work was force-stopped. Every request field, native metadata, complete audio, fresh allocation/UID/key and own cover passed; source allocation/row/own cover retired; older journals, partial and unrelated covers unchanged. Both parked pre-ROM inventories match private backups. Final full copy and older partial are on CARD; no media repair, original deletion, eject, ROM/security setting or Stable activation.

The Card-to-Phone monitor lost sight of Settings when the user accidentally opened a song while cleaning the screen. User confirmed this; it is not an app regression. Its later UI idle observations cannot measure actual completion latency. Rolling log eviction also prevents a complete phase aggregate for that operation.

## Clean Phone-to-Card observation

A new process retained only fixed bounded numeric timing events continuously (maximum 32 of each kind), not raw logs, addresses or private keys/metadata. All 13 full verification and13 nested digest events succeeded. Last busy287.354s, first idle305.177s, second idle322.878s. Thus observed completion lies in(287.354,305.177]s, including host/UI polling; second idle is only confirmation. This is diagnostic-mode work and cannot establish ordinary-build timing or a controlled directional/cache speedup.

| Full verification phase | Sum across 13 calls, ms |
| --- | ---: |
| Total | 124,202 |
| Admission | 7,355 |
| Open | 33,992 |
| Metadata | 3,697 |
| Audio (includes digest) | 33,857 |
| After | 8,195 |
| Close | 33,044 |
| Final | 4,062 |

| Nested digest phase | Sum across 13 calls, ms |
| --- | ---: |
| Total | 33,849 |
| Binding | 8,172 |
| Checkpoint | 21,452 |
| Create | 2 |
| Open | 4,036 |
| Read | 57 |
| Hash | 105 |
| Close | 10 |

Nested/overlapping phases must not be added as independent wall time. The named read/hash counters were small relative to checkpoint/binding and native open/close counters in this run; caches and diagnostic overhead were uncontrolled. The whole Move is not instrumented by these tables, so they do not attribute all remaining time. No overall speedup or SD throughput claim.

The proof wrapper initially used old snapshot paths. It was corrected to the fresh stopped snapshot pair and the full proof rerun successfully, without replaying the Move. Original volume 11 restored/read back and diagnostic stopped before the next ordinary .1251 library fixture. Current device ownership is in the handoff.

## Remaining engineering

#401 startup and total Move latency remain unresolved. Full identity/namespace checks, actual exclusive ownership, known native closure and target-before-every-source-unlink proof must remain. A different read-only native verification backend is a future implementation task, not justification to keep native ownership open across unlinks or weaken deletion checks. This selected device proof does not clear cumulative UAT or authorize Stable promotion.
