# Queue insertion Undo checkpoint

Main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7; branch codex/queue-undo-characterization (initial investigation name retained), isolated audit worktree. GPT-6 / Codex desktop; effort not reported. Author source self-check, not independent review.

#223: MuonApp.queueSong Undo selected the nearest matching media ID, which can remove a pre-existing duplicate after the insertion moves, disappears or the queue is replaced. Fix only insertion Undo: a random token in a copied MediaMetadata extras Bundle identifies the queued insertion; Undo scans for exactly one surviving token and otherwise does nothing. No mediaId/cache/lyrics/song-record/signing/dependency changes. Existing song extras and local URI preserved; original metadata Bundle not mutated. A replacement queue built from tracks has no old token.

Pinned Google Maven Media3 common 1.11.0 MediaMetadata.toBundle/fromBundle retains extras (published source); own-UID PlaybackSessionCallback.onAddMediaItems preserves submitted validated MediaItems. Actual controller IPC not device-tested. Six Robolectric actual-ExoPlayer playlist/serialization cases cover normal Undo, moved duplicate, removed insertion, replacement, metadata Bundle round-trip and ambiguous repeated token. No prepare/audio/network/phone access. CI is first compile/test; exact head/run is on PR.

Does not fix QueueScreen removal Undo restoring into a replacement queue, duplicate row occurrenceKeys renumbering, or stale input guarded in separate #219. Follow those separately; insertion-only token does not solve all queue identity.

Manual QA pending: Add/Play next a duplicate, move it then Undo removes the inserted occurrence; remove it before Undo and the original duplicate remains; replace the queue before Undo and new queue remains. Normal insertion/shuffle/snackbar still works. No APK promised while quota persists; leave PR open for required checks/phone QA.
