# Fonts

Muon ships one font: **Google Sans Flex**, the face the 2.0 mockups are drawn in. This file records
where it came from and how to reproduce the file in `app/src/main/res/font/`, because a font binary
is otherwise unreviewable.

## What ships

| | |
|---|---|
| File | `app/src/main/res/font/google_sans_flex.ttf`, 465,284 bytes |
| sha256 | `eae4cd8abc193e15dacf279109ee695c49ee7469bf4c75b5c6bbabc489a01434` |
| Axes left variable | `wght` 1–1000 (default 400), `opsz` 6–144 (default 18) |
| Axes instanced away | `ROND` at **100**, `GRAD` at 0, `slnt` at 0, `wdth` at 100 |
| Glyphs | 657, covering 516 characters — identical to the source. Nothing was subset. |
| Licence | SIL Open Font License 1.1, `docs/licenses/GoogleSansFlex-OFL.txt` |

Roundness 100 is the user's decision in #40: it matches their Pixel, where stroke terminals are
fully rounded. It is baked into the file rather than set at runtime, because it never varies.

## Source

| | |
|---|---|
| Public source | [`end-4/google-sans-flex`](https://github.com/end-4/google-sans-flex) at commit `251aa5abd30496368f634e54ce2a508fe5a2fdfa` |
| File | `GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf`, 3,997,148 bytes |
| sha256 | `2510a8b7a24beb1fe8163e9a49813ccfe96b5453444b9443d42665ca4fa320c9` |
| Version | `197067` in `head.fontRevision`, which is `Version 3.007;[58cd9cb9b]` in name ID 5 |
| Copyright | `Copyright 2015 Google LLC. All Rights Reserved.`, name ID 0 |

Fetch and check the source before instancing:

```bash
curl -sSL -o GoogleSansFlex.ttf \
  "https://raw.githubusercontent.com/end-4/google-sans-flex/251aa5abd30496368f634e54ce2a508fe5a2fdfa/GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf"
echo "2510a8b7a24beb1fe8163e9a49813ccfe96b5453444b9443d42665ca4fa320c9  GoogleSansFlex.ttf" | sha256sum -c
```

The same file is installed locally at `~/.local/share/fonts/illogical-impulse-google-sans-flex/`; the
pinned download was compared against it byte for byte.

**Do not substitute the font currently published by `google/fonts`:** it is a different binary. Pin
this commit, or re-record the hashes and measurements here after checking what changed.

The same source file rendered the mockups in `docs/design/2.0/`, so the app and the mockups use one
face.

**On the version number:** `docs/design/2.0/HANDOFF.md` records `197067`, and that is correct. It is
the raw `head.fontRevision` in its 16.16 fixed-point encoding: `3.0070037841796875 × 65536 = 197067`,
the same version the `name` table spells as `3.007`. Both forms describe this one file.

**Licence provenance.** The font declares its own terms, and the shipped instance keeps them: name
ID 0 carries the Google copyright, name ID 13 the OFL 1.1 statement and name ID 14 the licence URL.
Those embedded declarations travel with the binary in the APK.

`docs/licenses/GoogleSansFlex-OFL.txt` reproduces the licence for the repository. Its body is the
OFL 1.1 text distributed with the upstream package, unaltered; no licence text was drafted here. The
upstream package left the template's header placeholders (`<Copyright Holder>`) unfilled, so the
header quotes the font's own embedded declarations instead. Google publishes the authoritative
notice for this family as `OFL.txt` in
[`googlefonts/googlesans-flex`](https://github.com/googlefonts/googlesans-flex).

## Reproducing the shipped file

Needs [fontTools](https://github.com/fonttools/fonttools). Built with **fontTools 4.65.0** on
Python 3.14.

```bash
fonttools varLib.instancer --no-recalc-timestamp \
  -o app/src/main/res/font/google_sans_flex.ttf \
  "GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf" \
  ROND=100 GRAD=0 slnt=0 wdth=100
```

Naming an axis without a value pins it; `wght` and `opsz` are simply not named, which is what keeps
them variable. `--no-recalc-timestamp` keeps the output byte-identical between runs. The command
prints two `OTLOffsetOverflowError` lines while it reorganises `GPOS`; that is fontTools repacking
the table, not a failure.

## Why one variable font rather than four static weights

#42 proposed shipping static instances at weights 400, 500, 600 and 700. #40 also requires the
optical-size axis to follow the rendered size, which a static instance cannot do: `opsz` would be
frozen at one value for every size on screen. Measured, on this source:

| Approach | Size | Optical sizing | Weights available |
|---|---|---|---|
| Source, all six axes | 3,997,148 | yes | continuous |
| **Shipped: `wght` + `opsz` variable** | **465,284** | **yes** | **continuous** |
| `wght` variable, `opsz` pinned at 18 | 282,932 | no | continuous |
| Four static weights, `opsz` pinned at 18 | 515,476 (4 files) | no | 400/500/600/700 only |
| Four files, weight pinned per file, `opsz` variable | 1,139,996 (4 files) | yes | 400/500/600/700 only |

The last row was measured by the reviewing agent and is the alternative that keeps optical sizing
with fixed-weight files; it costs more than twice the shipped file. So the shipped file is the
smallest measured option that keeps optical sizing, and it is also smaller than the four static
weights it replaces.

**This changes the mechanism #42 approved**, which was static instance files, while keeping what #40
asked for. The app still registers only the four approved weights — 400, 500, 600 and 700 — even
though the file could serve any weight. Pinning `opsz` would save a further 180 KB and is one command
away if the size ever matters more than the optical axis.

Variable-font settings need API 26; Muon's `minSdk` is 28. `FontVariation.Settings` is marked
`@ExperimentalTextApi` in ui-text 1.9.3, so `MuonTypography.kt` opts in on the one function that
builds a family.

## How the app uses it

`MuonTypography.kt` builds one `FontFamily` per Material 3 role, each asking the font for that
role's size through `FontVariation.opticalSizing`, with the four weights above. Every family reads
the same file. Weight is a role property, except for the greeting on Connect, which is bold as the
deliberate exception recorded in #40.

DM Serif Display, the previous display face, is removed: nothing uses it now.
