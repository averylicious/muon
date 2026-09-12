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
