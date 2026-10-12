# Resource boundary checkpoint — 2026-10-01

## Live boundary and ownership

GPT-6 / Codex desktop (effort not reported), active coordinator in isolated persistent audit worktree; no assigned Claude task or phone process. Branch `codex/track-metadata-budget`, based on main `d60fe09fa0d401e69049a1d1da6d1084237ec0c1`. User/Claude experimental checkout/session remains untouched. No uncommitted work should be assumed; inspect status and final PR head before takeover.

[Main run430](https://github.com/averylicious/muon/actions/runs/36750627173) succeeded and [Canary .430](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.430) publication was verified. It contains #254 source/build-crypto docs, #255 payload characterization and #256 card-index characterization, not pending app fixes. Details and original heads are in the [network candidate checkpoint](2026-10-01-network-qa-candidate.md) on #257's branch; older app branches remain in the [source checkpoint](2026-10-01-source-audit.md) and [progress checklist](../audits/2026-09-30-progress.md).

## Network/reconnect candidate is available, not merged

[#257](https://github.com/averylicious/muon/pull/257), `codex/network-qa-candidate`, final reviewed head `36fbf83a295e282b3cc23213566b31ddf3cefc1b` combines #205/#206/#209/#245/#252/#250 plus LanProbe depth guarding. [Run431](https://github.com/averylicious/muon/actions/runs/36752450218) passed both signed variants, lint, identity/export/publication gates; downloaded XML confirms446 tests per variant, zero failures/errors/skips. Branch direction and dependency inventory checks passed, including policy refresh after PR description update.

Canary .431 [artifact app-debug-36fbf83a295e282b3cc23213566b31ddf3cefc1b](https://github.com/averylicious/muon/actions/runs/36752450218/artifacts/11114969078). It updates the same Canary package/data, can replace experimental UI, expires14days and is not an Obtainium release. Leave #257 and its original app PRs OPEN for user QA: normal/retry/disconnect library loading, cover/lyrics and offline fallback, audio/downloads beyond30seconds, background/lockscreen/headset/notification compatibility, internal reconnection and LAN grant/denial/regrant. Actual stall/deep-response and hostile helper cases require separately authorized fixtures; no device access occurred. Original issues are not fixed on main.

## Current bounded resource fix

[Resource inventory and policy](../audits/2026-10-01-library-resources.md) maps representations and their ownership. Incoming records and formatted display text each have a16KiB UTF-8 budget, with cheap character preflight. Oversized incoming tags yield recoverable refusal before entering library/queue/download transport; accepted tags remain intact. Existing partial-load retention/retry is preserved. Tests cover actual HTTP/JSON ingestion and actual pinned Media3 serializers, not remote Binder/kernel/device behavior. Latest head/run/artifact are recorded on this PR after CI.

Leave this app PR OPEN for phone QA: ordinary large library/search/grouping/queue/play/download/offline behavior, refresh and retry. Exceptional controlled tags can be tested only in a disposable server/library; don't modify user music or claim the user's normal-library test validates malformed cases. Existing retained exceptional records, aggregate library/group/queue resources, #179/#213/#230 remain unresolved; #253 stays open. This branch excludes other app candidates and does not alter retained records, signing, feeds or experimental libraries.

## Progress and next source work

Approximately70% of the planned first source pass excluding phone QA: planning estimate, not code-line coverage/security score/Stable readiness. First-pass network, CI/publication/exports, platform backup and broad interaction paths have evidence. Incomplete deep work includes storage lifecycle/data preservation (#179), retained numeric identity (#213), partial cleanup ownership (#230), aggregate memory/payloads (#253), dependency/provider/integrity scope and final release coverage/QA reconciliation. No full security/performance clearance.

Next: preservation-safe storage ownership design before a production SD fix; separately bound aggregate load while keeping large legitimate/shared playlists and the previous library. Refresh heads/PRs/checks and ownership before resuming. Do not start device work or Stable releases from a checkpoint. Reserve handoff capacity; update #181/#40 with exact final heads, checks and active/idle status before yielding.
