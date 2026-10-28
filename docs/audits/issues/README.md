# Current audit issue state

Start here when resuming the main audit. These are **maintained current snapshots**, edited in place, not append-only journals. Read the [release gates](../../release-plan.md) and the relevant issue file; open older evidence only for the specific question being investigated.

| Workstream | Current state | GitHub owner record |
| --- | --- | --- |
| Queue feedback backlog — post-RC engineering | [#744](744.md) | [Issue #744](https://github.com/averylicious/muon/issues/744) |
| Unused strict tracklist JSON graphs — post-RC implementation | [#740](740.md) | [Issue #740](https://github.com/averylicious/muon/issues/740) |
| Aggregate memory and partition storage — implemented/accepted/released | [#253](253.md) | [Issue #253](https://github.com/averylicious/muon/issues/253) |
| Saved-card startup and Move latency — explicitly deferred post-release | [#401](401.md) | [Issue #401](https://github.com/averylicious/muon/issues/401) |
| User removal preparation — post-RC implementation | [#706](706.md) | [Issue #706](https://github.com/averylicious/muon/issues/706) |
| Saved-copy Delete refusal and reset compatibility | [#646](646.md) | [Issue #646](https://github.com/averylicious/muon/issues/646) |
| First-song normalization and missing-tags reminder — implemented/accepted/released | [#645](645.md) | [Issue #645](https://github.com/averylicious/muon/issues/645) |
| CI signing / Benchmark boundary — completed | [#640 / #685](685.md) | [Issue #640](https://github.com/averylicious/muon/issues/640) |
| Vorbis/Ogg/FLAC admission — post-RC candidate | [#714](714.md) | [Issue #714](https://github.com/averylicious/muon/issues/714) |
| Remote metadata transport payload — source assessment | [#738](738.md) | [Issue #738](https://github.com/averylicious/muon/issues/738) |
| FLV startup/SCRIPT admission — post-RC engineering | [#736](736.md) | [Issue #736](https://github.com/averylicious/muon/issues/736) |
| AVI header/index admission — post-RC engineering | [#734](734.md) | [Issue #734](https://github.com/averylicious/muon/issues/734) |
| AC-4 format-sniff lookahead — post-RC engineering | [#732](732.md) | [Issue #732](https://github.com/averylicious/muon/issues/732) |
| HEVC derived-array characterization — post-RC source finding | [#730](730.md) | [Issue #730](https://github.com/averylicious/muon/issues/730) |
| Matroska metadata/sniff admission — post-RC engineering | [#726](726.md) | [Issue #726](https://github.com/averylicious/muon/issues/726) |
| WAV format/writer admission — post-RC candidate | [#723](723.md) | [Issue #723](https://github.com/averylicious/muon/issues/723) |
| MP4 retained-box and sample-table admission — source finding | [#720](720.md) | [Issue #720](https://github.com/averylicious/muon/issues/720) |
| Notification/platform command grants — post-RC hardening | [#711](711.md) | [Issue #711](https://github.com/averylicious/muon/issues/711) |
| Notification artwork revision — post-RC acceptance | [#207](207.md) | [Issue #207](https://github.com/averylicious/muon/issues/207) |
| Initial connection cancellation — post-RC acceptance | [#690](690.md) | [Issue #690](https://github.com/averylicious/muon/issues/690) |
| Live ReplayGain ID reuse — post-RC implementation | [#695](695.md) | [Issue #695](https://github.com/averylicious/muon/issues/695) |
| ReplayGain container metadata — post-RC implementation | [#702](702.md) | [Issue #702](https://github.com/averylicious/muon/issues/702) |
| ID3 prefix and decoded-frame admission — post-RC implementation | [#704](704.md) | [Issue #704](https://github.com/averylicious/muon/issues/704) |
| Saved-admission lifecycle — post-RC implementation | [#708](708.md) | [Issue #708](https://github.com/averylicious/muon/issues/708) |
| Notification artwork queue — post-RC implementation | [#697](697.md) | [Issue #697](https://github.com/averylicious/muon/issues/697) |
| Published Gradle advisories — main/experimental tool updates | [#693](693.md) | [Issue #693](https://github.com/averylicious/muon/issues/693) |
| Selected-network discovery — post-RC acceptance | [#638](638.md) | [Issue #638](https://github.com/averylicious/muon/issues/638) |
| Collection worker projections — post-RC acceptance | [#639](639.md) | [Issue #639](https://github.com/averylicious/muon/issues/639) |
| Measured Baseline Profile benefit — post-RC assessment / engineering next | [#83](83.md) | [Issue #83](https://github.com/averylicious/muon/issues/83) |
| Refined M3E candidate / finite next-RC delta audit | [#661](661.md) | [Issue #661](https://github.com/averylicious/muon/issues/661) |
| M3E predictive Back lifecycle | [#658](658.md) | [Issue #658](https://github.com/averylicious/muon/issues/658) |
| Intermittent M3E stream buffering | [#671](671.md) | [Issue #671](https://github.com/averylicious/muon/issues/671) |
| Overall release sequence and acceptance gates | [Release plan](../../release-plan.md) | [Audit #181](https://github.com/averylicious/muon/issues/181) |

GitHub remains authoritative for current heads/checks/merge state. A snapshot must identify its date, inspected head and verification limits; it does not grant device access or override user decisions. Latest execution state belongs in the concise [coordinator entry point](../../coordinator-handoff.md), with history linked rather than repeated. Files on an open documentation PR are proposals/working state until merged; verify the linked branch/PR before using a main-branch copy.

## Reporting contract for all agents

- Keep one existing issue as the workstream's owner record. Keep its body short: problem, current status, link to its maintained Markdown state and relevant PR. Reuse an existing PR for an unchanged scope; do not create tracking duplicates.
- Use one current Markdown file per **active issue**, following [the template](TEMPLATE.md). Edit the current sections in place. Do not append each session's transcript or a new dated block forever. Aim for roughly one page; extract lengthy supporting analysis into a linked evidence document.
- Record the exact candidate/source commit, implementation versus merge state, completed evidence, unresolved findings, remaining release gates and next action. Separate CI/source, selected automated device checks and user acceptance. Keep active/uncertain operations visible until reconciled.
- Comment on GitHub only for a needed decision/question, a blocker, actionable review feedback, a material verification result, or a merge/release change. Link the state file and short receipt. Routine quota readings, unchanged polling, every successful intermediate CI and repeated checkpoints do not need new issue/PR comments.
- Amend a short maintained summary comment only when necessary if the issue body cannot be edited. Do not delete/rewrite historical user comments, decisions, review conversations or original evidence to reduce noise. Do not copy private runtime dumps, addresses, credentials or user metadata into public Markdown.
- Keep crash/quota recovery checkpoints when useful, but write the detailed handoff once, link it from the state file and the coordinator entry point, and avoid copying the same checkpoint into STATE, the runbook, the audit README and multiple issue comments.
- On resume, read this index, the relevant current file and its latest checkpoint first. Inspect live branch/head/check state. Query individual older comments or evidence only when needed; do not stream the complete issue timeline by default.
- Once an issue closes, leave a short final status and links to the landed fix and acceptance evidence. Reopen/update that file for a real regression; do not label an unresolved deferral as fixed.
