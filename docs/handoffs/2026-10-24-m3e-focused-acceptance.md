# M3E focused next-RC acceptance

The user considers the design mature; these checks cover the audited delta rather than repeating the whole v0.2.0 storage audit. Exact builds and final evidence are recorded in the [audit](../audits/2026-10-24-m3e-delta.md) and #674/#675. Diagnostic is for investigation; acceptance of its debuggable build does not establish the actual release configuration.

- [ ] **Back ownership:** album opened from artist → start/cancel Back → immediately commit Back. Correct parent remains, no stale or translucent card. Repeat On this device → Settings, and Queue/Lyrics → Now Playing. Test both swipe edges; use ordinary Back/hardware Back if available without changing phone navigation settings just for this.
- [ ] **Song actions and rapid swipe:** from Settings, player ⋮ → album/artist → Back returns to Library Top; from Search it returns to Search. Two rapid horizontal song-row swipes must follow the new finger, queue only the intended action and keep Undo working, including shuffled duplicates.
- [ ] **Motion and accessibility:** with animations disabled, the seek wave stays still; normal settings restore its motion. Seek/volume remain adjustable and announce their purpose; menus, joined rows and Details retain reachable focus. Font/landscape checks should leave essential actions visible. Agent checks do not change these phone settings.
- [ ] **Saved/Search regression:** disposable saved copy → Details/Play/Delete still acts on that exact copy. Recent searches survive restart, newest first; Clear removes them. No fresh destructive SD stress is required by this UI delta.
- [ ] **Streaming #671:** if buffering recurs **inside Muon diag**, capture `MuonPlayback` from before it happens and note the time. The observer cannot read ordinary Canary's player. A Diagnostic result is evidence, not a reproduced/fixed cause claim. Review logs locally and post only a sanitized summary.

The observer caps output at 1,024 lines per playback-service lifetime; `captureLimitReached=true` marks the final line. If reached, force-stop **Muon diag only** and reopen it to begin a new service/capture; this does not require clearing data. Do not reset ordinary Canary or Stable. Router, PC, ROM and app explanations remain hypotheses until supported by evidence.

Existing release deferrals, including #646, remain as documented. After targeted acceptance, the main-promotion guard still needs a separate reviewed change before release approval/version/tag/publication.
