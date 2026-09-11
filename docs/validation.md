# Validation record

## 2026-09-11 — live CachyOS Tauon 12.0.0-1.1

The remote setting initially read enabled in the UI but TCP 7814 was closed. Restarting Tauon applied the setting; no firewall changes were made.

`python3 tools/probe_tauon.py http://192.168.100.69:7814` fetched local file ID 402:

- HTTP 200, `audio/flac`, 20,314,967 bytes, native `fLaC` signature.
- ffprobe: FLAC, 44,100 Hz, two channels, 16-bit; duration 180.122063 s.
- SHA256 `0e7daf2eb1c794059450322ae7cc5e39f53365ba79b264e90f78d53fb3d2c0b4`.
- `bytes=0-15`, `bytes=65536-`, `bytes=-16`: 206, correct Content-Range, exact byte equality against full download.
- `bytes=20314967-`: 416 with `Content-Range: bytes */20314967`.
- Private downloaded music and raw reports are ignored under `proof-private/`, not app assets.

`python3 tools/test_tauon_ranges.py`: 4 tests passed (multiple subcases). Uses the exact installed `send_file` function in an isolated HTTP harness; includes bounded, open, suffix, clamped, unsatisfiable, malformed and multi-range cases. This test alone does not demonstrate Android playback.

Device, build and full MVP results will be recorded below as performed.

Live `/api1/fileopus/402` returned HTTP 200, `audio/ogg`; ffprobe recognized 48 kHz stereo Opus. Response had no Content-Length or Accept-Ranges. This verifies transcoding availability, not seekability. `/api1/lyrics/402` returned nonempty stored lyrics.

First Pixel LAN attempt failed before HTTP with a socket connect timeout. Kernel logs explicitly showed UFW blocking source `192.168.100.70` → desktop `192.168.100.69:7814` on `enp2s0`. The server listener was `0.0.0.0:7814`; no codec inference was made from this network failure. User was given a firewall rule restricted to this phone, LAN interface and port.

## Milestone 1 accepted — real Pixel 8, direct LAN

After the user added the narrow UFW rule, `DirectPlaybackTest` passed on the Pixel 8 over Wireless ADB in 5.387 s. It used **the production MediaSessionService and MediaController**, with `http://192.168.100.69:7814/api1/file/402`, no proxy, no downloaded local copy, no transcoding.

`MuonProof` recorded `PASS direct playback + seek; id=402 position=91585 duration=180122` at 12:24:22 local time. Assertions covered playing progress, seekability, a 50% seek, post-seek progress, pause, and resume. This establishes decoder/playback-clock behavior on this device; it does not claim a human listening-quality assessment. Full Compose UI work began only after this result.

## Full MVP device checks

Expanded instrumentation passed in 10.283 s on Pixel 8: selected audio track, direct FLAC progress, 50% seek, pause/resume, two-track queue next/previous, background progress after Activity moved to background, and an active transport-category media notification. MuonProof logged both acceptance messages at 12:32 local time.

Manual connection loaded 962 unique tracks across 3 Tauon playlists. Album thumbnails and full-size artwork rendered from the real server. Screen checks and final build results are recorded below.

## Final acceptance

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`: BUILD SUCCESSFUL. 2 JVM tests passed; lint has no errors. Remaining warnings are dependency upgrade suggestions, optional KTX syntax suggestions and the intentionally exported MediaSessionService (controller checks are in `onConnect`/`onAddMediaItems`).
- Final device instrumentation: **OK (1 test)** in **15.157 s**. Covered direct FLAC decode/progress, seek, pause/resume, HTTP 404 error reporting and subsequent recovery, next/previous, background progress and media notification. Log: `position=91633 duration=180122`; subsequent error recovery and background assertions passed.
- UI checks: manual connect loaded 962 unique tracks; artwork displayed in list and Now Playing; seek slider moved to about 90.7 s; lyrics displayed; searching “Blinding” returned “Blinding Lights”; reopening automatically restored the server connection and reloaded the library. Status-bar contrast corrected and visually rechecked in the final APK.
- Screen-off media commands: active Muon session changed to PLAYING at 93,603 ms and PAUSED at 93,865 ms. Playback remained owned by the service. Physical lock-screen button placement was not separately tested; MediaSession/notification provide those controls.
- App left installed on the Pixel, connected to the desktop with library open and playback stopped. No Tauon source changes, router forwarding or VPS configuration.

Remaining coverage: DNS-SD did not produce a verified live discovery result on this desktop, so manual connection is the proven path. No long-duration soak, Wi-Fi loss/rejoin test, process-death queue resumption, alternate Android device or high-resolution FLAC matrix was performed. Decoder/progress assertions are objective results; no subjective audio-quality judgment is claimed.

Build tools were downloaded under `/tmp/muon-tools` for this session (JDK 17 and SDK Platform 36); the local, ignored `local.properties` points there. For builds after temporary files are cleaned, install these prerequisites in your normal development environment and update `sdk.dir` as described in README.

## Repository and CI setup — 2026-09-11

Added a private GitHub repository and a push/manual Actions workflow. Debug retains the milestone signing certificate; release uses a separate certificate and `dev.avery.muon.release` package so it can coexist with the app already being tested. The local signing recovery ZIP was verified byte-for-byte against both original keystores and credentials. Keys, passwords, music, screenshots and downloaded reference clients were checked absent from the staged Git source. Workflow syntax passed actionlint 1.7.12.

The CI-equivalent local build runs both APK variants, both JVM unit-test variants and both lint variants. Optimized release packaging and the first hosted workflow are verified separately before delivery; see the GitHub Actions run history for hosted results.

The CI-equivalent local build completed successfully in 5m20s, including both signed APKs, both unit-test variants and both lint variants. `apksigner` verified both APK signatures against the recorded certificate fingerprints. The optimized release is about 4.1 MiB versus about 35 MiB for debug. The first hosted run found `sdkmanager` missing from PATH; the workflow now invokes its explicit path from the official Ubuntu runner image.
