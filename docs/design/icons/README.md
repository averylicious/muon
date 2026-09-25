# Muon icons

Approved by the user on 2026-09-25 (#116). This set replaces the flat-terminal icons drawn for the Collection redesign.

- **Style:** a 24 dp grid with 2 dp strokes and round caps and joins, matching Google Sans Flex at full roundness (`ROND` 100). Transport glyphs are filled, with corners softened by a round-join stroke.
- **Shuffle:** two crossing curves leave horizontally, so each band enters its chevron centred on the tip. This avoids both the old off-centre arrowheads and Material's diagonal arrows, which the user preferred not to use.
- **Settings:** Material Symbols Rounded `tune` (weight 400, grade 0, optical size 24), at the user's request. It is from [google/material-design-icons](https://github.com/google/material-design-icons), Apache-2.0; the licence ships as `app/src/main/assets/licenses/MaterialSymbols-Apache-2.0.txt` and `docs/licenses/MaterialSymbols-Apache-2.0.txt`.
- **Queue, play next and add to queue** are told apart by their glyph: a note, a ▶ and a ＋. The lower lines stop short, so the glyph has clear space. **Lyrics** is a speech bubble holding text.

`muon_icons.py` is the source of truth. It writes every `app/src/main/res/drawable/ic_<kind>.xml` for the kinds in `MuonIcons.kt`, and `--preview` renders `muon-icons.png` (needs `rsvg-convert`). Edit the script rather than the XML, then regenerate.

The launcher and notification icons are separate and unchanged.
