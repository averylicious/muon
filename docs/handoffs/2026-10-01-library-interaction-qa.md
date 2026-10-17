# Library interaction QA candidate — 2026-10-01

GPT-6/Codex desktop, effort not reported, owns `codex/library-interaction-qa` in its own persistent worktree. Combines #217 `09e1cf60a8a6fcd68880fbe0feb7a1ee821d5276`, #227 `d6c44cc5494f7114ad1ddb5f3e30203a8312ac58` and #229 `7a8240ac414ded3c3a48174f101f989005f9fa01`; each merged without conflict. Exact final head/destination/CI/artifact belongs on the candidate PR. Source self-review, not independent review; Claude idle, experimental checkout/session untouched.

## Source and scope

- Song actions run only after a successful hidden sheet and active coroutine. Cancellation, failed/refused hide does not dismiss/execute the action; the choosing latch rejects a second tap while hide is active. Five production-helper coroutine cases are retained. No claim that these reproduce Compose animation/disposal on a phone.
- Library mode effect remembers the initial current mode per model, preserving saveable restored positions on initial composition. Only an actual observed mode change resets Songs/Artists/Playlists/Albums/header together; Songs-at-top no longer skips other views. Existing explicit Disconnect reset stays. No fake test mirrors Compose wiring; compile/source review plus user saveable/rotation QA are required.
- Alphabet handle/bubble offset pins to zero when their viewport is shorter than the element. Normal clamping is unchanged. Both production call sites use the helper; original normal and two undersized-boundary regression cases stay. Real split-screen/font/rotation behavior is not established by a helper test.

No new design, dependency, gesture model, schema, network trust, signing or release change. Keep this and original PRs/issues #216/#226/#228 open until final-head CI and user QA, then reconcile/close originals after landing the verified combination. No automatic phone install. No Stable release/tag. The previous handoffs are historical individual-head evidence.

## Manual checks

1. Choose a normal song action: hide completes and one action occurs. Tap twice rapidly; rotate/background/dispose or interrupt dismissal while hiding: no delayed duplicate/unintended action. Inspect Download only if deliberately chosen; never delete the only media copy as a test.
2. Scroll Songs, Artists, Albums and Playlists separately, then rotate/background/reopen while remaining in the same online/offline mode: saved positions/header stay. Enter downloads/offline then reconnect: all top-level lists/header restart. Repeat with Songs already at top while another view had scrolled. Disconnect still resets. Offline mode is not proof the network is disabled.
3. Alphabet scrolling remains usable in normal portrait/landscape. With large fonts/split-screen making the list unusually short, no clamp-range crash and handle/bubble remain anchored safely. No device settings changed by this preparation.

## Integration and exclusions

Includes the final main recorded on the PR. #272's error mapper is independent and should be included once it lands before the candidate's final push. This candidate excludes #257 network/private-service/metadata, #264 card containment, #267 queue ownership and all other parked fixes/experimental UI unless explicitly reconciled later. A higher version code does not mean those fixes are present. Branch APK updates the same Canary data and expires after14days; it is not an Obtainium release. Keep current network .444 unless intentionally choosing this different QA boundary.

Next: inspect final-head test XML/artifact and leave open for the checks above. Before any eventual merge, refresh destination and verify the resulting source/CI/QA. Future #267 integration must retain both independent MuonApp hunks (browsing mode and insertion-Undo controller ownership); do not select one file wholesale.
