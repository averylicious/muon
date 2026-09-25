# Muon 2.0 handoff

A checkpoint for resuming the Muon 2.0 rework without the conversation that produced it. Read this before touching 2.0 work. The specification is **#40**; this file records the state of play around it and does not repeat it.

- **Checkpoint:** 2026-09-14, at the end of design and before any 2.0 implementation.
- **Written by:** Claude Opus 5 (`claude-opus-5`), Claude Code desktop, implementation agent.
- **Status legend:** **Verified** means checked live on GitHub or in git when this was written. **Approved** means the user approved it. **Proposed** means an agent suggested it and the user has not approved it.

## Read first

| Document | For |
|---|---|
| [#40](https://github.com/averylicious/muon/issues/40) (description) | The final 2.0 specification. Its comments record how each decision was reached; where they disagree, the description wins. |
| [`README.md`](README.md) | Mockup contents, screen-to-issue mapping, and what the mockups do and do not show. |
| [`AGENTS.md`](../../../AGENTS.md) | Branching, PR, attribution, QA and merge rules for both agents. |
| [`docs/agent-workflow.md`](../../agent-workflow.md) | The human handoff sequence and starter prompts. |
| [`docs/codebase-map.md`](../../codebase-map.md) | Current architecture, state ownership and invariants that must not break. |
| [`docs/roadmap.md`](../../roadmap.md) | Phases toward 1.0. |

## Verified state

As of 2026-09-14:

- **`main`** is at `5d2e2db` (#38). No 2.0 implementation has started, and no 2.0 code branches exist.
- **Mockups:** open [PR #52](https://github.com/averylicious/muon/pull/52), branch `codex/design-2.0-mockups`. It is the only open PR and has not been merged.
  - Initial mockups: `c134bb30e1ce120f9db8a1f0decc236ef0e299b5`.
  - **Approved mockups:** `0d3f6d1a0f44c76d2952fb995f012d9b13c8a788`. That commit fixed icon-to-label alignment on screens 05, 06, 07, 08, 09, 11, 14 and 17 in both themes, plus the overviews and `generate.py`.
  - [Android APKs run #50](https://github.com/averylicious/muon/actions/runs/34760226913) succeeded on `0d3f6d1`.
  - The commit adding this file creates a new PR head. Check CI on that head; do not assume it.
- **Review of #52:** GPT-6 Astra (Codex desktop, High effort) reviewed `0d3f6d1`: no blocking findings, left open pending the user's merge instruction. Its follow-ups are listed under [Contradictions and follow-ups](#contradictions-and-follow-ups).
- **Build steps:** #41–#51 are open with no comments, meaning none has started.
- **Other open issues:**
  - Astra-owned, milestone *0.3 Correctness and performance*: #23 (thumbnail and scroll performance), #26 (backend audit), #39 (mDNS auto-connect).
  - Milestone *0.4 Verification*: #16 (insets and font scales).
  - Milestone *UI/UX rework*: #11 (chip overflow) and #29 (Canary label).
- **Milestones:** *UI/UX rework* has 14 open and 15 closed issues. Fifteen pre-2.0 issues delivered by merged PRs were closed on 2026-09-13; #15 was closed as superseded by #42.

## Ownership

- **Claude:** frontend design and frontend implementation.
- **Astra:** backend implementation, technical review and project coordination.
- **User:** authorises which batch starts, performs phone QA, and instructs merges. Under AGENTS.md, implementation agents do not merge.
- **Shared behaviour and file boundaries** are to be agreed jointly; nothing here transfers backend ownership. #47 (Queue) is labelled both `frontend` and `backend` and needs that split agreed.

## Approved decisions

Each with its rationale.

**Process**
- **Focused PRs straight to `main`, with no feature toggle and no long-lived 2.0 branch.** The user chose this after seeing the full mockups. Canary publishes only from `main`, and a long-lived branch would drift and end in a single large merge.
- **Rewrite the UI layer; leave `ServerEndpoint`, `TauonApi`, `PlaybackService` and `LibraryModel` alone unless there is a concrete reason.** `ServerEndpoint` is the security boundary and these files carry tested invariants (see `docs/codebase-map.md`).
- **Stable Material 3 only (1.4.0 today), no alpha dependencies.** Expressive-only components wait for a stable release; #40 lists them.
- **Material 3 fidelity over the frontend-design skill's push for a distinctive look.** The user prefers Google's design language.
- **The #23 thumbnail fix lands before the Albums grid in #44.** The grid multiplies the decode cost, and the user made this an explicit dependency.

**Structure**
- **Three tabs: Library, Search, Settings.** Now Playing becomes an overlay that grows out of the mini player, so the Playing tab has no remaining job.
- **Now Playing overlay.** It closes with a downward swipe, Back, or a visible collapse button; swiping the artwork left or right changes track. The user asked for a swipeable Now Playing.
- **Library views: Songs, Albums, Artists, Playlists.**
  - Songs stays a scrollable list, which is how the user browsed on Spotify.
  - The app remembers the last view used.
  - The empty-playlist chip goes away.
- **Library header.** The bold greeting *Your music, nearby.* returns because the original header felt cozy to the user, and it folds on scroll because the old header took too much space.
- **Pull to refresh replaces the Refresh button.** Green-lit by the user.
- **Album and artist pages.** First shown in the final mockup set; the user's only feedback on that set was the alignment fix.
- **Song actions sheet.** Play next, Add to queue, Go to album, Go to artist, confirmed by a snackbar with Undo. Green-lit and mocked.
- **Queue screen,** opened from Now Playing. Swipe to remove with Undo, and a handle to reorder. Requested and green-lit; its semantics are unresolved.
- **Search.** A Material 3 search bar that expands to full screen, with suggestions from the library and results grouped into songs and albums. Mocked at the user's request and accepted in the final set.
- **Settings.** A grouped list in Android Settings style with a collapsing large title, and a Disconnect confirmation. The user identified this look as native.
- **Now Playing controls.**
  - A flat seek slider.
  - Volume as a slider inside the player that **keeps the floating percentage bubble**; the bubble is the user's own design choice, replacing the old dialog.
  - Lyrics and Queue in a row at the bottom.

**Visual**
- **Google Sans Flex at roundness 100.** It matches the user's Pixel; 75 felt off.
- **Type roles:** expanded titles use `displaySmall` (36sp, regular) and collapsed titles use `titleLarge` (22sp), measured against Android 17 Settings. The Library and Connect greetings stay bold, because regular weight read wrong on a two-line greeting.
- **Colour:** Material You by default (an intended feature), the Muon palette as an opt-in, and Pure black as a modifier for dark mode. All three already ship.
- **In-app icons:** the flat-terminal set is approved. The level-meter *music* icon is approved as a trial, with the music note as the fallback.
- **Launcher:** the rounded pills stay, and Canary keeps its dot.
- **Motion direction:** animations should make the app feel alive. Specific techniques are proposals (see below).

## Rejected alternatives

| Rejected | Why |
|---|---|
| A feature toggle or long-lived 2.0 branch | The user chose normal PRs; see Process above. |
| DM Serif Display | It did not feel like Muon to the user. |
| Squiggly seek bar | Dropped by choice, since the wavy component is absent from stable 1.4.0 and the user avoids alphas. A stable `Slider` could have drawn it, so this was not a technical block. |
| A Playing tab | Redundant with the overlay. |
| A Refresh button | Replaced by pull to refresh. |
| Launcher candidate B (squared meter) and C (muon track) | The user prefers rounded pills; C read as a strikethrough. |
| Regular weight on the two-line greeting | It felt off; regular suits short single-line titles with space above them. |
| Roundness 0, 50 and 75 | The Pixel appears fully rounded; 75 felt off. |
| The volume dialog | A modal for one slider, with dead space. |
| A full-bleed artwork-coloured Now Playing background | The default streaming-app move; rejected in #40's design review. |
| Animating the colour scheme on palette changes | Recomposes every surface while #23 is unmeasured. |
| "Recently played" in empty search | Muon keeps no play history. |

## Unresolved

These are not approved and belong to the joint session.

- **Queue semantics:**
  - Whether *Next up* follows shuffled order.
  - Removing or moving the playing item.
  - Play-next behaviour while shuffled.
  - Duplicate entries. Today a duplicate track ID resolves to the first occurrence (`docs/codebase-map.md`).
- **Library view switcher:** chips as mocked, or segmented buttons (Material 3's intended component for switching views).
- **Recent searches.**
- **Splitting multi-artist credits** such as `Shiv; Dylan Smith` into separate artists. #44's description asserts this, but the user never reviewed it.
- **Hero title weight** on the album and artist pages (bold in the mockups, not explicitly decided).
- **Motion specifics:** predictive-back preview, haptic tick on the artwork swipe, spring parameters.
- **The Lacquer and Brass Muon palette** from #40's first draft, never reviewed with the user.
- **Behaviour the mockups do not settle:** small screens, large fonts, and backend feasibility (noted in Astra's review).
- **Connect discovery states,** which wait on #39.
- **#29** (Canary label) and **#11** (probably moot).

## Contradictions and follow-ups

Flagged here, not resolved.

1. **The README says design is "settled"**, yet #40 still has open choices. Astra's review asks that they stay open.
2. **Three "A · B" meta strings remain** in `generate.py` lines 306, 307 and 324 (screens 08 and 09), although #40 removes such strings.
3. **The `generate.py` docstring says `python3 final.py`** (Astra's review).
4. **Regeneration is not reproducible yet** (Astra's review). The mockups were built with Python 3.14.7, Pillow 12.3.0, rsvg-convert 2.62.3, and Google Sans Flex version 197067 (sha256 `2510a8b7a24beb1fe8163e9a49813ccfe96b5453444b9443d42665ca4fa320c9`). None of this is documented in the README yet.
5. **"2.0" names the rework, not a version.** The app is 0.1.x and `docs/roadmap.md` calls this phase 0.2.
6. **Overlapping milestones:** *Performance and backend reliability* is empty and overlaps *0.3 Correctness and performance*. Coordination call for Astra.
7. **#26 has no labels.**
8. **`docs/codebase-map.md` goes stale with #41**, because it describes the single-file `MuonApp.kt`.

Items 1–4 could be fixed on #52 before merge. That was not done in this checkpoint.

## Dependencies

- **#52 merged:** makes the mockups available on `main`.
- **#41 → #42 → everything visual:**
  - #43 before #47 and #50.
  - #44 before #45 before #46.
  - #48 and #49 after #42.
  - #51 after #39.
- **#23 before #44's Albums grid.**

## Next steps

1. ~~**User:** compact the session.~~ Done.
2. ~~**User:** says "continue" to authorise Part 2 (below).~~ Done.
3. ~~**Claude:** Part 2, a documentation-only implementation map and batch plan.~~ Done: [`IMPLEMENTATION-MAP.md`](IMPLEMENTATION-MAP.md), proposed and not yet reviewed.
4. **Joint session (Astra and Claude):** agree batch order, file ownership and the decisions in the map's section 7.
5. **User:** authorises the first batch. Nothing is implemented before that.

## Part 2: preserved instructions

**Start only after the user says to continue after compaction.** First reread this handoff and the applicable repository instructions.

**Produce:**
- A mockup-to-code frontend plan.
- A proposed list of the backend data and actions the UI needs from Astra.

**Identify:**
- Affected files and proposed ownership, especially for shared files.
- Existing reusable UI and behaviour.
- Missing frontend state and backend capabilities.
- Dependencies and focused PR boundaries.
- Acceptance checks covering loading, error and empty states, long metadata, large fonts and accessibility.

**Tie claims to evidence:**
- Link each implementation claim to file paths and symbols.
- Record the inspected commit and any relevant uncommitted changes.
- Separate verified behaviour, suspected gaps and proposed changes.

**Queue:** describe what the UI needs — the current track, upcoming playback order, and actions to select, remove or reorder entries. Preserve the behaviour the user approved. Present shuffle, play-next and duplicate-entry handling only as recommendations for the joint session.

**Also:** keep the thumbnail fix as an explicit dependency before Albums. Inspect only the relevant code; this is not a repository-wide audit.

**Batching:**
- Number the batches and use real issue numbers only; never invent issue IDs.
- Each batch delivers one coherent, reviewable outcome: normally one issue or a named slice of one. Group two or three issues only if they are small and closely related.
- Each batch must be independently implementable, validated and handed off, and leave the app buildable. Prefer independently mergeable PRs, and name any unavoidable stacking.
- Separate implementation, review and user QA responsibilities.
- Split an oversized issue into named slices under that issue; do not create new issues to look granular.

For every batch, record:
- Outcome and issue IDs, plus explicit exclusions.
- Owner and expected files.
- Dependencies and required decisions.
- PR boundaries.
- Automated checks and a short manual QA list.
- Completion condition, and the checkpoint to use if interrupted.
- Relative effort or uncertainty, with no token, quota or time guarantees.

**Execution protocol** (to document for both agents, not to start):
1. Astra and Claude agree the next batch and file ownership, and the user authorises which batch or small group starts.
2. Work only in the authorised scope. Never continue into the next batch automatically.
3. Coordinate around concrete decisions, blockers and handoffs. Avoid repeated full-codebase exploration and long agent-to-agent discussion without a deliverable.
4. Save concise progress after each meaningful slice, and before switching owners, starting expensive validation, or waiting for review.
5. At the batch boundary, report PRs, exact commits, completed and pending checks, and the next proposed batch. Then pause for the user.
6. If one agent hits its limit, the other must not take over its role or expand scope. It finishes only authorised independent work, preserves the handoff, and reports what is blocked.
7. Never assume an agent resumes automatically when usage resets.

**Interruption checkpoint:** keep one per active batch, containing:
- Batch and issue IDs.
- Branch or worktree, and last commit.
- Completed and remaining work.
- Uncommitted changes and their purpose.
- Checks actually run, with results.
- Workflow run IDs, marked running, failed or unverified.
- Open decisions and blockers.
- The exact next action and whose it is (Claude, Astra or the user).

Save incrementally rather than at a limit warning. Make coherent commits with no secrets and no unrelated work. Record a mid-edit interruption honestly.

**Boundaries:**
- Documentation changes only; no app code, no backend behaviour.
- No merges, releases, phone access, or agent-to-agent implementation.
- No open-ended refactors, and no reopening approved design without a concrete conflict.
- Preserve unrelated work, and follow the documentation PR and attribution workflow.
- Reuse an existing documentation PR where appropriate, and leave it open for Astra.

**Deliverables:**
- Links to this handoff and the implementation map.
- The documentation PR link.
- A compact batch table.
- The recommended first batch, with reasoning.
- The decisions needing the joint session.

Then stop. Part 2 authorises preparation, not execution.

## If resuming after an interruption

1. Check that this file exists on #52's branch and on `main`, then check #52's live head and CI with `gh pr view 52`.
2. Compare the open issues against [Verified state](#verified-state).
3. Do not invent decisions. If something is missing here, it is unresolved.
