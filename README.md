# Muon

A LAN-first Android streaming companion for Tauon Music Box. Kotlin, Compose and Media3. Audio plays on the phone; controls do not change desktop playback.

See [feasibility and protocol](docs/feasibility.md) and [measured validation](docs/validation.md). The real-track milestone passed on a Pixel 8 before the full UI was built. This MVP includes connection/settings, playlist browsing, local search, Now Playing, artwork and lyrics, plus background playback with notification and MediaSession controls.

## Desktop setup

1. In Tauon, enable **remote control / server for remote app**. **Restart Tauon** after changing it. Listen Along is a separate server and is not required.
2. Keep Tauon running with local music in a playlist. Original-file streaming does not cover CUE segments or tracks backed only by another network service.
3. Put phone and desktop on the same trusted LAN. Find the desktop address with `ip -brief address`; connect to `http://<desktop-LAN-IP>:7814`.
4. If connection fails, first test `curl http://<desktop-LAN-IP>:7814/api1/version`. If localhost works but the phone cannot connect, inspect the LAN firewall, guest-Wi-Fi isolation, and VPN LAN-access settings. Only permit this port from your trusted LAN if necessary; never port-forward it to the Internet.

**Security:** Tauon's API has no authentication or encryption and exposes desktop controls and file paths. Muon currently accepts numeric private LAN addresses and loopback only. It neither configures a server nor opens ports. A VPN/Tailscale transport can be added later with explicit address policy and ACLs; direct Internet use is out of scope.

## GitHub builds

For contributing with coding agents, read [AGENTS.md](AGENTS.md) and the [PR workflow and starter prompts](docs/agent-workflow.md). Implementation agents supply a PR and signed test APK; the user handles phone QA, then requests Astra review and merging in a later cycle.

The private [repository](https://github.com/averylicious/muon) builds signed APKs on every branch push. Successful `main` builds publish **Muon Canary** prereleases; explicit `vMAJOR.MINOR.PATCH` tags publish **Muon** stable releases. Both can be installed together and updated separately using Obtainium. See the **[Obtainium and PAT setup guide](docs/obtainium.md)** and [Releases](https://github.com/averylicious/muon/releases).

Canary keeps the milestone/debug app's identity and uses a diamond with a large C. Stable keeps the previous Muon Release identity and uses a circle with three bars. Their shapes distinguish the channels without relying on colour. [Actions](https://github.com/averylicious/muon/actions/workflows/android.yml) also retains debug/release APK artifacts for 14 days; published release assets have no such expiry. See [CI and signing recovery](docs/ci.md).

## Build

Use JDK 17, Android SDK Platform 36 and Build Tools 36.0.0. Set `sdk.dir=/absolute/path/to/Android/Sdk` in an untracked `local.properties`, or use `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing uses an ignored `.local/debug.keystore`; preserve it to install future debug updates without uninstalling. A fresh checkout can generate a different local debug key, so restore the original key or use a CI artifact to update the existing app. For a signed local release, use `python3 tools/build-signed.py`; without signing environment variables, `assembleRelease` produces an unsigned APK. Android 9/API 28 or newer. Targets Android 16/API 36. Gradle 8.13 wrapper includes its distribution checksum. Dependencies are pinned; no old Android client source is included in the app.

## Reproduce the first milestone

```sh
python3 tools/probe_tauon.py http://192.168.1.10:7814
ffprobe proof-private/track.flac
# With a paired Android device and the two debug APKs built:
./tools/device-proof.sh PHONE_IP:ADB_PORT http://192.168.1.10:7814 TRACK_ID
```

The HTTP probe finds a downloadable FLAC in the exposed playlists, fetches original bytes, compares three range responses byte-for-byte and checks 416. It stores music/report data in ignored `proof-private/`. Use the reported `track_id` for the device test.

The instrumentation test connects through the production MediaController/MediaSessionService, requires decoded playback progress, seeks to 50%, verifies resumed progress, and tests pause/resume, next/previous, background progress and the media notification. It requires a second playable track for queue checks. It briefly plays audio on the device. Separately use Home/lock-screen media controls to validate background behavior.

`python3 tools/test_tauon_ranges.py` tests the installed Tauon `send_file` function in isolation on CachyOS. It does not alter Tauon or substitute for the live/device tests.

## Scope and limitations

- Library/search can cover tracks in exposed Tauon playlists, not a complete server-wide library API. Tauon has no search/pagination endpoint.
- Direct original audio is the default. FLAC is supported through Media3/platform decoding; uncommon codecs and unusually high-resolution profiles remain device-dependent.
- Tauon's optional Opus transcode endpoint exists but lacks the seek/range behavior needed for the default player path.
- Stored plain lyrics and available artwork can be fetched; lyrics retrieval/synchronization is not guaranteed by Tauon.
- No offline download manager, account system, public hosting, persistent queue restoration after process death, or desktop remote-control mode is planned for this first MVP.

For this CachyOS machine, UFW was confirmed to drop the Pixel's incoming TCP 7814 packets. The proposed narrow rule was:

```sh
sudo ufw allow in on enp2s0 from 192.168.100.70 to 192.168.100.69 port 7814 proto tcp comment 'Tauon from Pixel LAN'
# To remove that same rule:
sudo ufw delete allow in on enp2s0 from 192.168.100.70 to 192.168.100.69 port 7814 proto tcp
```

These addresses are specific to this LAN. DHCP address changes require reviewing the rule. It does not permit the whole subnet or alter router forwarding.

## Using the app

- Connect with a numeric private LAN address, or try **Find Tauon on my LAN**. Discovery requires Tauon to advertise `_tauon-remote._tcp`; manual connection is the reliable fallback.
- The library combines exposed playlists and removes duplicate track IDs. Choose a playlist chip to browse it. **Refresh** reloads desktop changes. Search matches titles, artists and albums across the loaded playlists.
- Tap a track to play. The current list becomes the Android queue; unavailable network/CUE tracks are skipped. The mini-player opens Now Playing. Use the slider, play/pause and previous/next controls, or the Android media notification.
- In Now Playing, **Shuffle** changes the queue order and **Repeat** cycles Off → All → One. Repeat All loops the active Android queue (including a search-results queue), not the whole desktop library. The modes remain active while the playback service lives; they are not saved after process death.
- **Volume** opens the Android media-volume slider; hardware volume changes are reflected while it is open. It controls the device media stream, including the current headphone/Bluetooth route, rather than desktop volume. Fixed-volume devices cannot be adjusted here.
- Colours follow the system light/dark theme and, on Android 12+, the system dynamic colour palette. Older Android versions use Muon's fallback palettes.
- **Open lyrics** displays stored lyrics for the phone's current track, independent of what Tauon is playing.
- Short transport failures receive Media3's bounded retries. Persistent failures show a retry action; the queue/position stay in the service. Library refresh failures retain the prior in-memory list. Changing servers requires Disconnect, which stops the queue to avoid mixing server track IDs.
- The last server is saved; reopening the app reconnects. Queue restoration after process death is not implemented. Android battery policies and vendor codec differences need broader device testing.

For the playback and appearance changes, see the [device acceptance checklist](docs/qol-validation.md).
