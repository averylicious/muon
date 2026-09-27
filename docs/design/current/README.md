# Muon as it is: reference screens

These are real screenshots of Canary .276 on the user's Pixel 8 (Android 17, 1080 × 2400, 420 dpi), taken on 2026-09-27 against their 962-song Tauon library. Every screen is shown in light and dark, and two are shown in landscape. They are here so an agent without phone access can see what the code draws today.

**This folder is the reference for the current app.** The drawn mockups in `../2.0/`, `../offline/` and `../landscape/` are the proposals that led here, and they no longer match in places. Where they differ, trust these screenshots, then the code. Retake them (see the end) after a change a reviewer would need to see.

`hero.jpg`, at the top of the project README, shows four of these: Songs, an artist page, and Now Playing for two covers, taken on Canary .276 the same day.

![Light](overview-light.jpg)

![Dark](overview-dark.jpg)

## The screens

| File | Screen | Worth knowing |
|---|---|---|
| `*-01-songs` | Library, Songs | The greeting folds into a "Library" bar as the list scrolls. The subtitle says where the music comes from, and each view's bar has its own count and sort. |
| `*-02-albums` | Library, Albums | A grid of covers, two columns on a phone. |
| `*-03-artists` | Library, Artists | Each artist shows their newest album cover (#170). The initials only come back offline. |
| `*-04-playlists` | Library, Playlists | Each playlist shows a 2×2 mosaic of its first four albums' covers (#171). |
| `*-05-songs-miniplayer` | Songs, with a song loaded | The playing row is tinted with moving bars. The mini player sits above the navigation bar: swipe it sideways to skip, and up or tap to open Now Playing. |
| `*-06-album` | An album page | Cover, title, artist and length, then Play, Shuffle and Download all, then the songs, numbered. |
| `*-07-artist` | An artist page | A 2×2 cover mosaic, Play, Shuffle and Download all, their albums in a row, then their songs. |
| `*-08-playlist` | A playlist page | "16 songs" in the bar, Play, Shuffle and Download all (#169, #168). |
| `*-09-now-playing` | Now Playing | Coloured from the song's cover (#172); these covers are near-neutral, so the tint is light. Artwork swipes to skip. It has seek, the transport controls, a media volume slider, and Lyrics and Queue. |
| `*-10-queue` | Queue | Now playing on its own tinted row, then Next up with its length. You can reorder by dragging when shuffle is off. |
| `*-11-lyrics` | Lyrics | The words are blurred here only because they are copyrighted. In the app they sit on the title's 16 dp keyline and can be selected. |
| `*-12-search` | Search at rest | Artists as cover mosaics, and recent albums. Typing shows artists, albums and songs. |
| `*-13-song-actions` | Long-press on a song | Play next, Add to queue, Download, Go to album, and Go to artist for each credited artist. |
| `*-14-settings` | Settings | Connection, Playback (Even out volume, #163), Storage (usage bar, downloads, cache limit, SD card on phones with a slot), and Appearance. |
| `landscape-01-library` | Library, sideways | Navigation rail on the left, the list at full height, and the player as a panel on the right (#164). |
| `landscape-02-now-playing` | Now Playing, sideways | Cover on the left, controls on the right, and Lyrics and Queue in the top bar (#162). |

Not shown: the Connect screen (the first run, or after Disconnect), the offline library, the Poco's SD card row, and large text. The `../offline/` mockups and issue #16's QA notes describe those.

## Retaking them

Over ADB, and only with the user's go-ahead for device access that session. Mute media volume, switch dark mode with `cmd uimode night yes|no`, and landscape with `wm user-rotation lock 1`. Afterwards, restore what the phone had: dark mode `auto`, rotation `free`, auto-rotate on, and the media volume. Save each screen at 540 px wide (1200 px for landscape) as JPEG, and blur any lyrics.
