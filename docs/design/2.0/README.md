# Muon 2.0 design mockups

The agreed visual target for the Muon 2.0 rework. The specification is issue #40; the build steps are #41 to #51.

Implementation PRs should match these screens. Audits compare against them, so design is settled here rather than negotiated during review.

## Contents

- `light/` and `dark/`: 17 screens each, at 1080×2400 (Pixel 8 resolution).
- `overview-light.png` and `overview-dark.png`: every screen on one sheet.
- `generate.py`: regenerates all of the above.

| Screen | File | Build step |
|---|---|---|
| Library: Songs, Albums, Artists, Playlists | `01`–`04` | #44 |
| Album page | `05-album` | #45 |
| Artist page | `06-artist` | #45 |
| Search: at rest, tapped, typing | `07`–`09` | #48 |
| Now Playing opening, and open | `10`, `11` | #43 |
| Lyrics | `12-lyrics` | #50 |
| Queue | `13-queue` | #47 |
| Song actions sheet | `14-song-actions` | #46 |
| Settings, and the Disconnect confirmation | `15`, `16` | #49 |
| Connect, first run | `17-connect` | #51 |

## What these show, and what they don't

- **Target:** layout, Material 3 components, type roles, icons, copy, and behaviour such as the collapsing titles and the swipeable Now Playing overlay.
- **Approximate:** colours. They imitate Material You derived from one wallpaper; the app takes its colours from the device.
- **Illustrative:** album art is drawn as flat colour, the album and artist pages use sample content, and the lyrics are placeholder text.
- **Not shown separately:** Pure black, which is the dark theme with a black background.

## Regenerating

`generate.py` is a design tool, not app code; nothing in the app build reads this directory. It needs Python 3 with Pillow, `rsvg-convert` from librsvg, and the Google Sans Flex variable font (SIL Open Font License).

```bash
GOOGLE_SANS_FLEX=/path/to/GoogleSansFlex-VariableFont.ttf python3 docs/design/2.0/generate.py
```

It writes into this directory by default, or into a directory given as the first argument.
