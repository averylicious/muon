# Using Muon

A tour of every screen and setting. For pictures of each one, see the [reference screens](design/current/README.md).

## Library

The library has four views, chosen with the connected buttons under the greeting:

| View | Shows | Sort by |
|---|---|---|
| **Songs** | every track in Tauon's playlists, without duplicates | Recently added, A–Z |
| **Albums** | **Jump back in** (once three or more albums have been played), then a grid of covers | Recently added, A–Z |
| **Artists** | each credited artist, pictured by their album covers | Most songs, A–Z |
| **Playlists** | Tauon's playlists, each with a mosaic of its covers | Tauon's order |

- **Sorting:** A–Z lists have a letter scroller at the edge for jumping through the alphabet; it ticks lightly at each new letter. Muon remembers each view's order.
- **Refreshing:** pull down to reload changes from the desktop.
- **Album, artist and playlist pages:** each starts with **Play**, **Shuffle** and **Save copies**, then its songs. An artist's page also shows the albums they appear on. On album and artist pages, once those buttons scroll away, a floating toolbar brings back Play, Shuffle, Back and Back to top.
- **Back:** hold the Back gesture on a page to preview the page underneath, then let go or cancel.
- **Song menu:** long-press a song, or tap its ⋮, for Play next, Add to queue, Go to album, Go to artist for each artist credited, and Save a copy.
- **Swipe a song** towards the end for Play next, or towards the start for Add to queue. Each queue action offers Undo.

## Search

At rest, Search shows your recent searches, top artists and recent albums. Typing searches titles, artists and albums across the whole library and shows matching artists, albums and songs. Playing a song from the results continues through the whole library in the Songs order. Recent searches stay on the phone (at most eight); **Clear** forgets them.

## Playing

- **Mini player:** it sits above the navigation bar. Swipe it sideways to skip, and tap it or swipe up to grow it into Now Playing. With the phone sideways, it becomes a panel beside the list instead.
- **Now Playing:**
  - It takes its colours from the song's cover and, with **Blur** on, sits on a blurred copy of it.
  - Swipe the cover sideways to skip; drag the top down, or use Back, to put the player away.
  - It has a seek bar, previous, play/pause, next, shuffle and repeat, a media volume slider and a ⋮ for the song menu. With **Expressive motion** on, the played part of the seek bar is a wave that rolls while the song plays, and it turns wavy in place while a track buffers.
  - Previous restarts the song; pressed again within three seconds, or in a song's first three seconds, it goes to the song before.
  - **Lyrics** shows the lyrics Tauon has stored; the button stands out when the song has some.
  - **Queue** shows what's playing and what's next. Drag to reorder when shuffle is off; swipe a song away to remove it.
- **Shuffle and repeat:** Repeat cycles Off, All and One. Both settings are remembered.
- **In the background:** playback carries on with the screen off, from the notification and the lock screen. Unplugging headphones pauses it.
- **Volume:** the slider is the phone's own media volume, for whatever speaker or headphones are in use. It doesn't change the volume on the desktop.

## Offline listening

- **Saved copies:** save a song from its menu, or a whole album, artist or playlist with **Save copies**. Saved songs show a check, play with no network, and stay until you delete them. They are Opus copies at 84 kbps, made by Tauon.
- **Played-song cache:** songs you play are also kept automatically, up to a limit you choose in Settings (1, 2, 5 or 10 GB; 2 GB by default). When it's full, the oldest go first.
- **On this device:** opened from Settings, it lists every copy on the phone or its SD card, downloads and copies kept from listening apart, with each copy's details and Delete.
- **When Tauon can't be reached:** Muon shows only what's on the phone, and Retry reconnects.
- **Lossless streams:** these play from memory and are never written to storage.
- **SD card:** on phones with a card slot, **Store on SD card** sends new copies to the card. Changing it offers to move the copies already saved to the other side. Taking the card out hides its copies until it's back.

## Settings

- **Connection:** the server, Refresh library, and Disconnect. Disconnect stops playback and forgets the server, but saved copies are kept.
- **Playback:** **Even out volume** plays songs at a similar loudness, using the ReplayGain tags in your music files. It turns loud songs down and never turns quiet ones up. It's off by default. See [tagging](#even-out-volume) below.
- **Storage:** On this device, the played-song cache and its limit, download quality and the SD card switch.
- **Appearance:**
  - **Material You** takes colours from your wallpaper (Android 12 and later). **Muon** uses the app's own palette.
  - **Pure black** makes dark mode backgrounds true black for OLED screens.
  - Light and dark follow the system.
- **Material 3 Expressive experiment:** two switches, both on by default, so each part can be compared with it off on the same phone.
  - **Expressive motion:** springy transitions, the mini player growing into Now Playing, connected button groups, joined song lists with the playing song as its own pill, shape-morphing play and toggle buttons, and wavy progress.
  - **Blur:** Now Playing on its blurred cover; the library blurred behind the player and the song menu, and a frosted mini player and navigation bar in portrait. The library blur and frosted bars need Android 12 or newer.

## Even out volume

This needs ReplayGain tags in the music files on the desktop; Muon can't measure loudness itself. Tag the library with a ReplayGain tool such as `rsgain`, and tag new songs as you add them. Songs without tags are turned down by the library's typical amount, so they stay close to their neighbours.

## Two apps, side by side

**Muon** (Stable) and **Muon β** (Canary) are separate apps with separate settings and saved copies. Canary gets every change first. See [Obtainium](obtainium.md).
