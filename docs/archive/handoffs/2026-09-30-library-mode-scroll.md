# Library mode scroll checkpoint

Main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7; branch codex/library-mode-scroll, isolated audit checkout. GPT-6 / Codex desktop; effort not reported. Author source self-check only.

#226: initial LaunchedEffect(model.offline) reset a saveable nonzero Songs list on recreation even without a mode transition. Its Songs-at-top shortcut skipped other lists, and Albums/header were never reset. Remember the initial mode per LibraryModel; only a different mode triggers the reset. Reset Songs, Artists, Playlists, Albums and greeting together. Disconnect's existing explicit reset is preserved. This is top-level browsing, not a new detail-page navigation contract.

No settings/storage/dependency/network/player/signing changes. Small Compose state wiring correction; no new test mirrors that wiring. Existing full JVM suites/lint and CI compile are required; actual saveable/rotation/layout behavior is pending user testing because no Compose UI/device test exists here. Same old effect at inspected experimental ref e1bf045c1fa7139c4966e480f2f06941a703ddfc; no experimental editing. #224 changes another area of MuonApp independently; reconcile both hunks at integration.

QA: scroll each view and rotate / background-reopen: retained position/fold should stay in the same mode. Enter offline and reconnect online: all top-level views/header restart, even if Songs was already at top and only Artists/Albums/Playlists had scrolled. Disconnect still clears positions. Keep PR open for phone QA and latest-head required checks. CI is first compile; run/head on PR. No artifact promised while storage quota persists.
