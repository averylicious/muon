# Using Muon

A tour of every screen and setting. For pictures of each one, see the [reference screens](design/current/README.md).

## Library

The library has four views, chosen with the chips under the greeting:

| View | Shows | Sort by |
|---|---|---|
| **Songs** | every track in Tauon's playlists, without duplicates | Recently added, A–Z |
| **Albums** | a grid of covers | Recently added, A–Z |
| **Artists** | each credited artist, pictured by their album covers | Most songs, A–Z |
| **Playlists** | Tauon's playlists, each with a mosaic of its covers | Tauon's order |

- **Sorting:** A–Z lists have a letter scroller at the edge for jumping through the alphabet. Muon remembers each view's order.
- **Refreshing:** pull down to reload changes from the desktop.
- **Album, artist and playlist pages:** each has **Play**, **Shuffle** and **Download all**, then its songs. An artist's page also shows the albums they appear on.
- **Long-press a song** for Play next, Add to queue, Download (or Remove download), Go to album, and Go to artist for each artist credited.

## Search

At rest, Search shows your top artists and recent albums. Typing searches titles, artists and albums across the whole library, and shows matching artists, albums and songs. Playing a song from the results continues through the whole library in the Songs order.

## Playing

- **Mini player:** it sits above the navigation bar. Swipe it sideways to skip, and tap it or swipe up to open Now Playing. With the phone sideways, it becomes a panel beside the list instead.
- **Now Playing:**
  - It takes its colours from the song's cover.
  - Swipe the cover sideways to skip.
  - It has a seek bar, previous, play/pause, next, shuffle and repeat, and a media volume slider.
  - **Lyrics** shows the lyrics Tauon has stored.
  - **Queue** shows what's playing and what's next. Drag to reorder when shuffle is off.
- **Shuffle and repeat:** Repeat cycles Off, All and One. Both settings are remembered.
- **In the background:** playback carries on with the screen off, from the notification and the lock screen. Unplugging headphones pauses it.
- **Volume:** the slider is the phone's own media volume, for whatever speaker or headphones are in use. It doesn't change the volume on the desktop.

## Offline listening

- **Downloads:** download a song from its long-press menu, or a whole album, artist or playlist with **Download all**. Downloaded songs show a check, play with no network, and stay until you remove them. They are Opus copies at 84 kbps, made by Tauon.
- **Played-song cache:** songs you play are also kept automatically, up to a limit you choose in Settings (1, 2, 5 or 10 GB; 2 GB by default). When it's full, the oldest go first.
- **When Tauon can't be reached:** Muon shows only what's on the phone, and Retry reconnects.
- **Lossless streams:** these play from memory and are never written to storage.
- **SD card:** on phones with a card slot, **Store on SD card** sends new downloads to the card. Changing it offers to move the downloads already saved to the other side. Taking the card out hides its downloads until it's back.

## Settings

- **Connection:** the server, Refresh library, and Disconnect. Disconnect stops playback and forgets the server, but downloads are kept.
- **Playback:** **Even out volume** plays songs at a similar loudness, using the ReplayGain tags in your music files. It turns loud songs down and never turns quiet ones up. It's off by default. See [tagging](#even-out-volume) below.
- **Storage:** how much Muon uses, split into downloads, the played-song cache and free space. It also has the cache limit, download quality and the SD card switch.
- **Appearance:**
  - **Material You** takes colours from your wallpaper (Android 12 and later). **Muon** uses the app's own palette.
  - **Pure black** makes dark mode backgrounds true black for OLED screens.
  - Light and dark follow the system.

## Even out volume

This needs ReplayGain tags in the music files on the desktop. Muon can't measure loudness itself. The user's library was tagged with `rsgain` on 2026-09-27, and `~/CHANGES.md` on the desktop has the steps for tagging new songs. Songs without tags are turned down by the library's typical amount, so they stay close to their neighbours.

## Two apps, side by side

**Muon** (Stable) and **Muon β** (Canary) are separate apps with separate settings and downloads. Canary gets every change first. See [Obtainium](obtainium.md).
