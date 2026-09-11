# Tauon → Android feasibility — 2026-09-11

**Decision: feasible and demonstrated. Original FLAC streamed directly from the running Tauon instance to Media3 on the Pixel 8; decoding, progress and seeking passed before full UI work began.** No old Android implementation is reused. A compiler success or a simulated server is not proof of that milestone.

## Evidence inspected

- [Tauon source](https://github.com/Taiko2k/Tauon/blob/8ff7721373b3abc13447e1df5f652312fa26e155/src/tauon/t_modules/t_webserve.py), commit `8ff7721373b3abc13447e1df5f652312fa26e155`. Installed CachyOS `tauon-music-box 12.0.0-1.1` has an identical `t_webserve.py`: SHA-256 `acfd2181989c7e634d01347e608409f40a6a854b9acf0a63c215e878906bead4`.
- [Taiko2k/TauonMusicRemote](https://github.com/Taiko2k/TauonMusicRemote/tree/3bb2cc355273a809e26d0db1c1dde7a8f3bd3043), archived. `Controller.kt` uses Android MediaPlayer with `/api1/file/{id}`; remote commands target the desktop separately. README documents lifecycle/rotation weaknesses.
- [kimsultech/tauonremote_android](https://github.com/kimsultech/tauonremote_android/tree/efa4927cdca641d0a418334ce5f32044ab8b430c), **not marked archived** in GitHub's current API. API interface confirms JSON paths, MainActivity sends the same file URL to HXMusic. Its Java/HXAudio/Retrofit/Rx UI and service are reference only.
- [Media3 formats](https://developer.android.com/media/media3/exoplayer/supported-formats): progressive FLAC is supported; the default extractor uses platform FLAC decoding (required since API 27). This project starts at API 28 and uses Media3 1.11.0. The actual file/device decoding and seek checks passed; other Android devices and FLAC profiles still need coverage.

## Wire protocol

Plain HTTP on TCP **7814**; JSON API version currently remains `1`. Track duration is **milliseconds**. Playlist IDs are decimal strings (preserve exactly, never coerce to floating point); file IDs are integers. There is no authentication or TLS in this handler.

| Need | Endpoint / behavior |
| --- | --- |
| Handshake | `GET /api1/version` → `{"version":1}` |
| Discovery | `_tauon-remote._tcp.local.` DNS-SD, TXT `app=Tauon`, `path=/api1`, `version=1`; depends on optional desktop zeroconf and correct interface selection |
| Playlists | `GET /api1/playlists` → `playlists[{id,name,count}]` |
| Tracks | `GET /api1/tracklist/{playlistId}` → `tracks[{id,title,artist,album,album_artist,duration,position,has_lyrics,can_download,...}]` |
| Albums | `GET /api1/albums/{playlistId}`, `/api1/albumtracks/{playlistId}/{albumId}` |
| Audio | `GET /api1/file/{trackId}` → original bytes; FLAC gets `audio/flac` |
| Artwork | `GET /api1/pic/small/{id}` (75px), `/api1/pic/medium/{id}` (1000px); 404 means absent |
| Lyrics | `GET /api1/lyrics/{id}` → `{track_id,lyrics_text}`; stored text only, no guaranteed retrieval or synchronization |
| Desktop controls | `/api1/play`, `/pause`, `/next`, `/back`, `/seek/...`, `/status`; **not used** for phone playback |

`can_download=false` marks CUE and network tracks; skip these for independent original-file streaming. CUE endpoints otherwise expose whole backing files, and network sources may not have a local file. No global library enumeration, pagination, or search endpoint exists. MVP library/search can index playlist track lists and de-duplicate file IDs; clearly describe search as covering exposed playlists. Tracks outside all playlists are not discoverable.

## Audio and range assessment

`send_file()` reads original bytes without transcoding. It handles bounded (`bytes=0-15`), open-ended (`bytes=65536-`), suffix (`bytes=-16`) and unsatisfiable (416) ranges, returning 206 and Content-Range. Multi-range/malformed ranges are ignored with a full 200 response. It does not implement HEAD; use GET for probes. `tools/test_tauon_ranges.py` exercises the actual installed function over HTTP without starting a desktop player. That is an isolated handler test, not a live library test.

`/api1/fileopus/{id}` **does exist** in this source: ffmpeg produces Ogg Opus at 84 kb/s, with no byte-range/length handling. Do not route the first milestone through it: seekable original FLAC requires no Tauon extension or bundled ffmpeg decoder. Media3's progressive source can consume the direct URL and issue range requests. The device acceptance test confirmed selected audio, progress, a midpoint seek and subsequent progress on Pixel 8.

## Small architecture

One Android app module. `TauonApi` handles bounded JSON GETs; `ServerEndpoint` owns origin and trusted address validation. Coroutines + ViewModel keep connection operations off the UI thread and across rotations. `PlaybackService` owns ExoPlayer and MediaSession; the Activity connects through MediaController, so playback and notification state have one owner. Media3 provides foreground playback/lock-screen controls. Compose owns screen state only. No DI framework, database, Retrofit, or old player wrapper is needed for the first slice.

After passing the proof gate, the MVP implemented NSD + manual connection, playlists, a local searchable index, artwork, Now Playing and plain lyrics, a mini-player and settings. Keep queue, progress, play/pause, next/previous and seeks on Android. Surface buffering, HTTP/decode failures and explicit retry; recover from short transport interruptions with bounded Media3 retries, preserve position on manual retry. Later add persistent queue/resumption and library caching only if needed.

## Security and future VPN

Tauon binds `0.0.0.0`, exposes file paths in JSON, and accepts unauthenticated state-changing GETs. Trusted LAN only. Never forward 7814 publicly. Manual origins use private numeric IPv4/IPv6 (plus loopback for ADB/emulator testing); redirects are disabled for API and media. No arbitrary URLs are accepted from track metadata. Reject public, multicast and unspecified addresses. A future VPN mode can reuse the origin/transport boundary with explicit support for CGNAT addresses and hostname resolution. Private addressing does not prove a connection is physically on a LAN: private/ULA addresses can also route over a VPN. No VPN setup or security guarantee is supplied here. Desktop firewall rules, VPN ACLs and optional TLS/authentication would remain separate requirements.

No Tauon-side blocker for local file streaming was found. Small optional extensions: a paginated read-only library/search endpoint for tracks outside playlists; a versioned capabilities response for discovery/transcode support. If a particular older server fails range tests, fix its `send_file` implementation rather than proxying/transcoding in the client.

Implementation refinement: one shared OkHttp client is used for both API requests and the Media3 HTTP data source so redirects are consistently disabled and timeout policy is explicit. This small dependency replaces duplicated URLConnection handling; it does not introduce Retrofit or another serialization stack.

The exported MediaSessionService admits this app and Android-trusted controllers; queue URL submission is restricted to this app and validated against the numeric-private-origin/file-ID policy. The API, audio and notification-artwork loaders share the redirect-disabled transport.
