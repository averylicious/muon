# October21 current-state workflow and verification boundary

Coordinator GPT-6 / Codex desktop (exact variant/effort not reported); Claude Opus 5.5 / Claude Code, explicitly selected High, completed a read-only closure inventory and is idle. No other agent owns this worktree. App stack remains OPEN; no Stable/experiment merge or production activation. This is an execution receipt, not another permanent status chronology: use the [maintained #253](../audits/issues/253.md), [#401](../audits/issues/401.md) and [release gates](../release-plan.md) for current findings and decisions.

## Source and workflow

- Main reverified `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`; cumulative app #634 `feaa0636c5c034eaa5070d163f2400d856c33bee` unchanged. Original experimental checkout untouched. Live remote experimental ref was `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; the user's local experimental checkout has its own later head and must not be reset to that remote snapshot.
- Workflow documentation #635, branch `codex/stable-closure-oct21`, persistent worktree `~/.codex/worktrees/muon-stable-closure-oct21`. It includes the pending #565 report history; do not independently re-land that old report after this successor lands. Verify live heads/checks before merging.
- AGENTS/CLAUDE/current entry points now direct agents to short maintained per-issue files. #181/#253/#401 bodies point to them; #207 records the narrow accepted notification deferral. Original bodies and prior entry-point chronologies are linked archives; user comments/history were not deleted. No routine checkpoint comment is needed.
- User accepted #401's selected measured delays and deferred #207's remaining notification-only cosmetic case. Both remain unresolved OPEN follow-ups. Existing #179/provenance deferrals unchanged. #253 and actual Stable promotion/acceptance remain required.

## Verified selected diagnostic evidence

Diagnostic [.1318/run39609886203](https://github.com/averylicious/muon/actions/runs/39609886203), exact #634 source: actual reports 1,792 tests per variant, no failures/errors/skips, lint0errors/66warnings each. Selected [diagnostic artifact12350477104](https://github.com/averylicious/muon/actions/runs/39609886203/artifacts/12350477104), reports12351105393; APK SHA256 `825624b8684dd8d35fc18a69d216935d1d6b2306ca425d5a4f84e123d6016973`. Actual package/certificate/debuggable/exports/BUILD/hash verified. No release APK installed or Stable acceptance claimed.

Idle Diagnostic .1309→.1318 update, CARD reader/pause/seek/resume and Card→Phone Move each completed their selected full stopped preservation proof. Exact timing and limits are in #401's state file. Full disposable new-partition copy is now PHONE; ordinary non-debuggable Canary .1284 remains unchanged. No active/uncertain Move remains.

## Interrupted artwork case and device pause

- First 144-scroll tiny-artwork attempt exercised 418 fresh image URLs, below the 500-URL coverage target because older cached fixture URLs were reused. It is not a completed coverage pass. Cleanup and full stopped saved/private/card preservation proof passed; no memory guard triggered.
- The fresh-ID repeat was interrupted by USB loss at79scrolls. No app OOM or storage failure is established. The user then requested **no further device audit**. Do not resume phone testing from earlier USB/root permission.
- Restoration-only recovery completed: both apps stopped, exact temporary connection restored/read back, original music11 restored/read back, owned loopback listener and synthetic files removed. No mount/eject/USB/security-setting change was made by the agent. The fresh repeat's final full saved-data proof and wide/small replacement/heap checks remain pending; do not call that attempt successful.
- Private ignored snapshots/heaps/numeric logs/optional helpers remain under the prior coordinator worktree's `.local/qa-oct19/`, directories0700/files0600. No private indices, audio, metadata, addresses or raw heaps uploaded. These local helpers are optional; the portable test description is in #253's state. Refresh authorization and use a stable connection before a future attempt.

## Next useful work

Complete the current documentation PR and its exact-head lightweight checks, then continue source/CI work on #253's finite validation/Stable opt-in promotion preparation. Keep app changes open for the defined release-configuration and user-acceptance gates. Do not investigate #401 timing or #207's deferred case again just to clear this RC. Reconcile the interrupted test's pending proof before starting a new device case whenever testing is explicitly reauthorized.
