# Combined main-track post-RC acceptance candidate — 2026-10-25

Branch `codex/post-rc-acceptance-oct25`, from main `dd8782a0426437093a99ea3f7205878b6af775bc`. One isolated **staging candidate** for combined CI and later user acceptance of eight independent post-RC fixes. Including a head here is not acceptance, review approval or permission to merge it into main. This is not the frozen M3E RC .1420 source; that freeze and its installs are unchanged.

## Included heads (ordinary merge commits, ancestry preserved)

| PR | Fix | Head | State file | Evidence before this candidate |
| --- | --- | --- | --- | --- |
| #682 | Selected-network discovery ownership | `b80ef8966f92d6c70a2f3b523313dde8cb4203e2` | [#638](../audits/issues/638.md) | Latest-head Android passed (root-reported; not rechecked here) |
| #691 | Cancel a slow initial Connect | `6a489a1baf0f05e37f23dcb985b8be872ba7831f` | [#690](../audits/issues/690.md) | .1456 passed |
| #684 | Worker collection projections | `15a545f01fefa6db4be8a98f647ce0d369832778` | [#639](../audits/issues/639.md) | Latest-head Android passed (root-reported; not rechecked here) |
| #686 | Saved-delete error classification | `e8bd003086e2b9fcf5cc464fc1349e5e3c557eb1` | [#646](../audits/issues/646.md) | Latest-head Android passed (root-reported; not rechecked here) |
| #689 | Notification artwork revision | `966fcfe9d5a4c5aed5def5e326b5731e7ba20482` | [#207](../audits/issues/207.md) | .1455 passed |
| #696 | Current-audio ReplayGain policy | `d41714dcbb2c8b27a79a30e3791d6526a0b0df5e` | [#695](../audits/issues/695.md) | Latest-head Android passed (root-reported; not rechecked here) |
| #698 | Bounded notification-artwork queue | `613cf0667a10c7c131b813da90a648c9822c1e8f` | [#697](../audits/issues/697.md) | .1461 passed |
| #700 | Move intent read checkpoints | `1c4b93c598313fe539b0dc0c3635abb09edfd8ab` | [#401](../audits/issues/401.md) | .1469 passed at this exact input head; downloaded report verification recorded on #700 |

Excluded: #679 promotion policy, #683 Benchmark tools, #692 forward sync, every alpha/M3E commit and any `sync/*` branch. Across the eight heads, the only commits added over main are their own topic commits and their own merges of `origin/main`.

## Conflict resolutions

- **Code:** no textual conflicts.
- **`ConnectScreen`:** #691's Cancel sets `autoTried` before `cancelLoad()`, and #682's auto-connect effect still requires `!autoTried`, `!model.busy` and the current scan owner. A cancelled load therefore can't be restarted by a late discovery result, and a stale owner can't connect.
- **`PlaybackService`:** keeps both #696's current-tracks gain (`volumeFor(id, applied)` and `playbackGainFor`) and #689's `liveArtworkUrl(endpoint)` artwork URI.
- **Documentation conflicts (#691: `690.md`, `README.md`; #689: `207.md`, plus a duplicate #207 index row; #698: `697.md`, `README.md`):** main already held the newer record (later date, PR links, CI receipts), so main's text was kept unchanged.
- **`695.md`** auto-merged to #696's newer text, which records its own API cleanup.

## Review follow-ups added here

- `ReplayGainSettingsTest.savedCopyHintsRemainAvailableButOnlyCurrentTagsOverrideThem`, extended:
  - one more live gain moves the library median to −15 dB;
  - with normalization on, `volumeFor(saved)` uses the saved handle's exact −24 dB while a live ID gets −15 dB;
  - switching off restores unity, and the stored hint stays −24.

  All existing assertions remain.
- `OwnedPartitionShelf.completeCoveredSavedMove` and `moveCompletedTo` KDoc now state that `checkpoint` is only a cancellation/availability probe, not content I/O or a shelf writer.
  - Source confirms this: both production paths, `OfflineStore.movePartitionBatch` and Move recovery, pass a `checked()` that only reads `locationAvailable()` and `moveOwnership.permits` and throws `InterruptedIOException`.
  - No runtime guard changed.

## Checks run here (no Android build)

- Ancestry:
  - main and all eight heads are ancestors of the candidate;
  - `tools/check_branch_policy.py --base main` passed on the merge head; alpha anchor `454350a0` is absent.
- `python3 -m unittest discover -s tools -p 'test_ci*.py'`: 128 OK. `test_release.py`: 9 OK.
- `git diff --check` against main: clean. No conflict markers.
- Tests over main: 25 `@Test` added and none removed.
- Main's build files, workflows, manifest, `tools/`, signer fingerprints and `baseline-prof.txt` are unchanged.

## Remaining (root/user)

- **CI:** this combination has never been compiled. Run Android CI on the final combined head. #700's separate .1469 is now successful, but that does not compile this combination.
- **Integration risk to watch in CI:** #689's loopback test now runs through #698's process-wide one-slot artwork executor. It submits one request at a time, as the existing `NotificationArtworkTest` already does under #698, but only CI shows the combined suite is stable.
- **User acceptance:** each state file above lists its device checks. Combined, they cover:
  - discovery during network changes;
  - Cancel during a slow Connect;
  - collection sort, search and scroll;
  - the Delete error wording;
  - notification covers after a catalog refresh and during rapid skips;
  - ReplayGain on/off and saved-copy levels;
  - Move timing and recovery on a real card.

  No speedup is claimed.

## Attribution

Integration, conflict resolution, the two follow-ups and this record: Claude Opus 5.5 (`claude-opus-5-5`) / Claude Code, the allocated audit session. High effort was requested; the runtime doesn't report effort. Each included fix keeps the authorship recorded in its own state file. The integration is author self-checked only, not independently reviewed. Claude handed off clean head `40448dd01a4adec736473c4de298266fa2729849` and is idle. GPT-6 / Codex desktop reviewed ancestry, the automatic ConnectScreen/PlaybackService merges and the two follow-ups, and owns push/CI and the draft PR. This is integration review of work partly authored by GPT-6, not independent review of every fix. No phone action or application merge is implied.
