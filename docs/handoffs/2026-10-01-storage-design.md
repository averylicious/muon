# Storage design audit checkpoint — 2026-10-01

## Owned boundary

GPT-6 / Codex desktop, effort not reported. Documentation-only branch `codex/storage-preservation-design`, based on main `d60fe09fa0d401e69049a1d1da6d1084237ec0c1`. Main [run430](https://github.com/averylicious/muon/actions/runs/36750627173) and [Canary .430 publication](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.430) verified; no pending app fixes included. Experimental checkout/session untouched; no assigned Claude task or phone process. This design does not authorize a storage rewrite, dependency fork or device reproduction.

## App QA candidates (leave open)

- [#257](https://github.com/averylicious/muon/pull/257), head `36fbf83a295e282b3cc23213566b31ddf3cefc1b`: combines #205/#206/#209/#245/#252/#250 and a discovery JSON-depth integration guard. [Run431](https://github.com/averylicious/muon/actions/runs/36752450218) passed both signed variants, lint, identity/export safeguards. Downloaded XML:446 tests/variant, no failures/errors/skips. Branch direction/dependency inventory passed. [Canary .431 artifact](https://github.com/averylicious/muon/actions/runs/36752450218/artifacts/11114969078), name app-debug-36fbf83a295e282b3cc23213566b31ddf3cefc1b. Test normal/rapid connect-disconnect-retry, covers/lyrics/offline fallback, audio/downloads beyond30seconds, notification/lockscreen/headset compatibility and LAN deny/grant/retry. Original six app PRs remain open; no issue closed as fixed on main.
- [#258](https://github.com/averylicious/muon/pull/258), head `11edc09af719b61485b38600799f820ac512b363`: incoming track record and formatted display text each have a16KiB UTF-8 budget, cheap character preflight, recoverable refusal and no truncation/retained-record rewrite. [Run432](https://github.com/averylicious/muon/actions/runs/36754012053) is the first compile; inspect live conclusion/reports/artifact on its PR, do not assume success. Resource inventory/checkpoint live on that branch at docs/audits/2026-10-01-library-resources.md and docs/handoffs/2026-10-01-resource-boundary.md. Device QA remains pending even if CI passes. Existing retained exceptional records and aggregate resources stay unresolved (#253).

Branch artifacts expire14days, update the same Canary data/package and can replace experimental UI. They are not Obtainium releases. No uninstall/signing change to bypass downgrades. Previous [source checkpoint](2026-10-01-source-audit.md) and [progress checklist](../audits/2026-09-30-progress.md) retain all excluded app branches and historical failures. Neither candidate includes all audit fixes.

## New source/design evidence

[Storage preservation design](../audits/2026-10-01-storage-preservation-design.md) records reviewed cache/service constraints, invariants and six ordered slices. It builds on merged #192 real-cache loss characterization and #256 real different-card index identity characterization, not new phone loss. Do not release/recreate an absent cache, pin content mappings with metadata/listeners, clear active global service helpers, or equate path/cache UID with ownership.

Next bounded task: actual service-helper selection/restart/no-card fallback characterization (S4), followed by explicitly limited availability containment (S1). Durable volume catalog, preservation feasibility and non-destructive ambiguous-index migration remain deeper work. #179 is unresolved; #213 retained media identity and #230 partial-cleanup ownership remain independent.

## Progress and recovery

Approximately70% of the planned first source pass excluding phone QA; planning estimate, not security score or Stable readiness. Remaining deep work: storage preservation, retained identity/partial cleanup, aggregate metadata/library resources, dependency/provider/integrity and final coverage/QA reconciliation. No source-only claim of measured performance or device stability.

Coordinator is active at creation; final head/docs-only CI/self-review/merge and active/idle status must be recorded on #181/#40 before yielding. Reserve capacity; if interrupted, preserve worktree and inspect current heads/status rather than restarting or editing another session's checkout. No local logs/secrets/session history required. All app fixes stay open until user QA; no Stable release/tag or experimental integration.
