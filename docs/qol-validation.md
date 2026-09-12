# Playback and appearance acceptance checks

Use a Canary APK from the reviewed commit. Record the APK version, Android version,
device, and results below before promoting a stable release. Hosted CI checks do
not establish on-device playback, system volume, or visual behaviour.

## Shuffle and repeat

Use a playlist with at least three playable, recognizable tracks.

- Start a track, enable shuffle, and confirm it does not restart or change the
  current track. Next/previous should follow the shuffled queue. A random order
  may occasionally match the original order; that alone is not a failure.
- Disable shuffle and check that playback continues at the same position and
  subsequent navigation uses the original list order.
- With repeat off, seek near the end of the final queue item. Playback should end.
- With repeat all, repeat that check: playback should wrap to the first item in
  the active queue order. Also check a one-item queue.
- With repeat one, seek near the end of a track: that track should start again.
  Explicit Next should still move to the next track when one exists.
- Switch between app screens, background/reopen Muon, and reconnect the UI to the
  running playback service. The displayed modes must match the service state.
- Start a new queue from search and from a playlist. Confirm the selected track
  starts and shuffle/repeat still apply to the new queue.
- Verify the visible mode labels and TalkBack descriptions distinguish off,
  shuffle on, repeat all, and repeat one without relying on colour.

These checks follow [Media3's queue semantics](https://developer.android.com/media/media3/exoplayer/playlists).
Modes need not survive destruction of the playback service: persistent queue
restoration remains outside the MVP.

## Appearance and launcher identity

- Install Canary and stable side by side without uninstalling either existing
  app. Their package IDs, signing certificates, and saved server settings must
  remain unchanged.
- Distinguish both launcher icons by their shapes at normal launcher size and in
  grayscale. Check circular and rounded-square launcher masks where available.
- On Android 12 or newer, verify the app follows the device's dynamic colour
  palette. Check both light and dark system themes, including status/navigation
  bar legibility. On Android 9–11, check the fallback light/dark palettes.
- In Settings → Appearance, switch between Material You and Muon. The whole app
  should recolour immediately without a restart, in both light and dark. Confirm
  the selected option is marked by the radio control rather than colour alone.
- Reopen Muon after switching: the choice must persist. Disconnect from the
  server and reconnect: the appearance choice must survive, since disconnecting
  clears the connection preferences.
- On Android 9–11, Material You should be unavailable rather than silently doing
  nothing, with the Muon palette shown as the one in use.
- Check library, search, connection, lyrics, and Now Playing, including errors,
  selected navigation items, disabled controls, and artwork placeholders.
- Check Now Playing at a narrow width and with enlarged font/display settings;
  controls must remain reachable by scrolling and labels must not overlap.

## Media volume

- At a comfortable listening level, open the in-app media-volume control. Its
  value should match Android's media volume for the current output route.
- Move the slider and confirm Android's media stream changes, while ringtone and
  alarm volume remain unchanged. Closing/reopening should retain the system value.
- With the control open, press hardware volume keys: the slider should follow.
  Also change volume while Muon is backgrounded and check after returning.
- Check muted/zero volume, maximum volume, and a headphone/Bluetooth route change.
  Respect Android's safe-volume restrictions; no automatic increase on opening.
- On a device with fixed or restricted volume, confirm a clear explanation and
  disabled or gracefully rejected adjustment rather than a crash.

## Verification record

The initial QoL change receives CI builds, unit tests, lint, and APK identity checks.
The phone checks above remain pending until performed and recorded; do not infer
they passed from CI or from the original streaming milestone.

## Follow-up audit: compact Now Playing and asynchronous search

The PR #18 portrait layout is retained. The audit adds a scrolling fallback for short/narrow
windows, larger text, or stream errors; narrow windows move Shuffle/Repeat to their own row.
Active modes also show a dot so their state does not depend only on colour.
These follow-up cases remain for user manual QA; no phone automation was performed:

- Rotate or use split screen, then increase font/display size: reach every control without overlap;
  Lyrics/Volume may wrap, and scrolling is allowed when needed.
- Trigger a stream error: reach its Retry action and the remaining playback controls.
- Toggle Shuffle and Repeat: the dot appears only while active; Repeat One also has a “1”.
- Search, disconnect, and connect a different library: old-library results must disappear immediately.
- Seek while paused/playing, change tracks, and return from another tab: progress remains correct.

CI verifies compilation, existing unit tests, lint and signing, not layout rendering or frame timing.

## Follow-up: Library screen

The marketing header is replaced by a Library bar, the duplicated track counts are consolidated into
the playlist chips, and list items are keyed by track identity rather than list position. CI does not
render any of this:

- The list should start near the top of the screen, with Refresh in the bar and no second track-count
  row. Refresh is disabled while a load is running.
- Scroll the playlist chips: the row should fade at whichever edge still has chips behind it, and not
  fade when every chip fits. The selected chip carries a check mark, not only a fill colour.
- Chip counts should match the playlist sizes, and "All music" should match the combined library.
- Track rows should be comfortable to tap, with durations aligned on one right edge.
- Play a track: its row gains a bar at the start, and TalkBack announces it as "Now playing".
- Scroll a long playlist, switch playlists, then refresh the library. Rows should not jump or flicker
  when the list changes; a playlist containing the same track twice must show both entries.
- Check a long title, a long artist/album line, and an unavailable track's explanatory second line at
  a large font size.
