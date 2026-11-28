# Muon coordinator checkpoint — 2026-09-30 (network and entry-point audit)

Additive to the [2026-09-29 cache checkpoint](2026-09-29-cache-audit.md) and its successors; Astra updated the coordinator pointer after incorporating #202 (see Resume). Unknown is stated as unknown.

## Scope and ownership
- Work: one bounded, documentation-only audit of network requests and external media-controller entry points on main (workstream 2 of the [audit map](../audits/README.md)). Report: [2026-09-30-network-entry-points](../audits/2026-09-30-network-entry-points.md). Excluded and not done: any code, manifest or dependency change; ADB or phone; LAN traffic; a local build; merging; a Stable tag or release; edits to the experimental checkout or branch.
- Author: Claude Sonnet 5.5 (`claude-sonnet-5-5`), Claude Code desktop app; effort not reported. Independent report/source review: GPT-6 Astra (Codex; effort not reported), including corrections and a published-source addendum. No runtime/device reproduction claimed.
- Owned: branch `claude/audit-network-entry-points` in worktree `~/.codex/worktrees/muon-network-audit`, from main `8d0b24b236f73a2c2b846abffcf229ec8a72ca34`, upstream unset on purpose so a plain `git push` cannot reach main. Nothing dirty at handoff other than what the PR contains. Its session is idle after the PR and the [#181](https://github.com/averylicious/muon/issues/181) comment are posted.
- Other workers: Astra owns #202 (`codex/published-canary-baseline`); the user and their Claude session own the experiment. Neither was contacted or disturbed. The experiment's checkout only had remote-tracking refs fetched.

## Verified state
- Inspected 2026-09-30 against `origin` (`averylicious/muon`): main `8d0b24b236f73a2c2b846abffcf229ec8a72ca34` (run 331 passed, Canary .331); experiment `e1bf045c1fa7139c4966e480f2f06941a703ddfc`, 62 ahead and 2 behind main; the only other open PR was #202 at head `4eed3fcd88b752232a01e6156aef325b0515a040`, checks green at inspection.
- Superseded or historical: nothing. Findings A2/A3 (fixed), A5 and #179 (open) are referenced, not restated.
- Attribution as above. Astra reviewed the report against the pinned published Media3 session source JAR; see its addendum. #202 is now merged at `a281073eddb90813d8d4a95078567c57a0b47e4a` and incorporated here; exact checks/publication are on the PR and #181.

## Validation and user QA
- Documentation-only change: the Android APKs workflow runs its lightweight checks (Markdown UTF-8, conflict markers, fences) and builds no APK. The exact-head result is on the PR and in the #181 comment; this file does not predeclare it. Device QA is not needed for this PR.
- No user QA is claimed. The findings were **not reproduced** at runtime. The initial report used the source tag; Astra independently checked the relevant published sources JAR. No production callback or cross-process controller test has run in this documentation PR.

## Next bounded slice
- **Harden `PlaybackService` external entry points** (N1 media-button start intents, N2 external play requests and command allowlist, and the one-line UID-based hardening from W1), one small PR from a fresh main-based branch. Acceptance: exercise the real callback with the pinned test-only controller factory, including default set-items delegation; `exported="false"` or a recorded reason to keep it; failed futures for external item requests; PR open, CI green, QA checklist pending the user. Excluded: the auto-connect policy, deadlines, parser changes.
- Then: assert exported components in CI (Q4), then request deadlines and cancellation (N3). Forward-sync each to the experiment through the usual `sync/main-into-expressive-*` PR; the audited files are identical there apart from `ConnectScreen.kt`.
- Needs the user: the Q1 decision on auto-connecting to the only discovered server; authorization for ADB to run Q3's discovery checks and to QA the manifest change.
- Usage: not exposed to this session; unknown.

## Resume
1. `gh pr list`, then read the report and the #181 comment. Verify the PR's latest head and its checks; do not assume a pass.
2. `git worktree list`; do not reuse or clean the experiment's checkout or another agent's worktree.
3. #202 is incorporated and the latest pointers now name this checkpoint. Refresh live #203 checks before merging; retain #202's completed release-history evidence. Main's current publication status belongs on #181/#40.
4. If interrupted mid-publish, check whether the PR and the #181 comment already exist before retrying either.

Leave the PR open for Astra's review. No device access or Stable release is implied.

Astra is the active review coordinator on #203; Sonnet's author session was reported idle. No Claude session was resumed. Current account at review start exposed 1% weekly usage and no five-hour reading; refresh rather than reusing old account limits. A clean handoff must include final-head checks and whether the next implementation branch has started.
