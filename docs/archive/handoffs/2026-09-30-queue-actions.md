# Queue snapshot action checkpoint

#218, branch codex/queue-snapshot-actions, main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7. GPT-6 / Codex desktop; effort not reported; authorship/self-review only.

QueueScreen captures the actual timeline object/current index/shuffle/revision. Tap, remove, accessible move and drag-drop require that snapshot and matching full media item, with bounds checked before item access. Queue changes cancel local drag state; stale pointer completion cannot commit an old order. This is conservative: duration/timeline updates can cancel a drag too, and a stale row action is refused until recomposition. Five actual ExoPlayer playlist tests check ordinary/bad-index actions, shrink, same-ID replacement, transition/shuffle and revision changes. No prepare/audio/network/Compose UI tests.

Pinned Media3 session published source MediaControllerImplBase.getCurrentTimeline returns playerInfo.timeline: repeated reads of an unchanged controller state retain that object; timeline reference is used as a generation guard, not a string/content identifier. App PlaybackState advances revision on queue/transition/mode/command events. This does not fix occurrence-key renumbering when identical queued songs are removed, or Undo targeting after queue replacement; those need separate investigation.

Pending CI compile/test/lint/identity checks and user QA: reorder/swipe/remove/TalkBack moves normally, then change/clear/replace queue or advance playback mid-gesture. Expected: stale action refuses without crash or wrong-row mutation; next fresh action works. Leave open for phone QA. Upload quota may block a downloadable APK. No phone/Claude/experimental checkout used. Exact head/check results on PR.
