# Mini-player drag lifecycle — 2026-09-22

User authorized Astra to continue directly while Claude is at its five-hour limit. Claude is idle; do not resume until allowance is refreshed. Current Codex live quota unavailable in this instance.

- Base: PR86 `b78b4711ed0be4e9829f92c6a238b9a71be438c7`, run110 passed; Canary .110 checksum verified. Includes the new drag handle and all PR85 prerequisites.
- Branch: `codex/mini-drag-lifecycle`. Astra (GPT-6, Codex desktop; effort not reported) authors this fix. Author self-check only; independent review pending.
- Fix: mini-player drag/click eligibility now follows connected controller, current item and overlay visibility. Losing eligibility cancels the detector and gives the next presentation a separate lift animation. Each movement cancels its predecessor, captures the event offset, and cancellation restores through the composition scope. Final opening also checks current eligibility.
- Existing drag threshold, resistance and appearance retained. No full finger-following opening implementation in this slice.
- Added policy test for a long drag whose eligibility is lost; existing drag policy tests retained. `git diff --check` passed. Final-head build results belong in PR description. Policy tests do not validate Compose lifecycle/input.
- Phone QA pending: long upward drag interrupted by opening the player with another finger, queue empty/disconnect, rapid close/reopen; ordinary upward pull/cancel, tap, play/pause and next remain functional. Test light/dark handle from86 as well. No phone access by agents.
- Keep PRs open. User reported overall .108 LGTM, not exhaustive lifecycle validation. No merge/release authorized.
- Next substantial work remains issue43's two-part presentation/opening plan in issue40 successor checkpoint. Preserve behavior and do not begin a large animation rewrite without review/CI reserve.
