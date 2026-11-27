# Gen 4 - Progressive Blur: mockups (proposal / target)

**These are proposals, not the current app.** What Muon draws today is in [`../current/`](../current/README.md) (Gen 3, M3 Expressive Refined, RC .1420). The mockups are drawn on that baseline. Plan and decisions: [#796](https://github.com/averylicious/muon/issues/796); milestone **Gen 4 - Progressive Blur**.

Open [`index.html`](index.html) in a browser. It is one self-contained page: interactive phones you can scroll, tap and drag, with controls for device, navigation bar, floating style, bar surface, palette and theme. The fonts load from Google Fonts, with a system fallback. Its blur is the browser's approximation of Android's; costs need a Pixel trace. The songs and covers are fictional.

## What it proposes

| Area | Proposal | Needs Android 17 QPR2 (37.2)? |
| --- | --- | --- |
| Navigation bar | **Edge to edge** (default; today's Material bar with a softer top edge) or **Floating** (opt-in glass capsules). Floating is stacked by default; **Tuck in while scrolling** slides the mini player beside the current tab. | No: Frost can clip its blurred copy to capsules from Android 12 |
| Bar surface | **Frosted** (blur), **Tinted** (see-through colour, no blur), **Solid** (opaque Material You or Muon containers). It covers every bar, toolbar, search pill and snackbar. | Frosted only, for the 37.2 backdrop extras |
| Settings › Appearance | New rows: Navigation bar, Tuck in while scrolling (shown with Floating), Bar surface. The Blur switch narrows to Now Playing's cover and the blur behind the player. | No |
| Floating page toolbar | Tinted glass over album and artist pages. Build first. | Yes (fallback: today's solid toolbar) |
| Queue and Lyrics top bars, Search pill | Rows dissolve under a progressive top edge. | Yes, plus Compose 1.13 for the progressive edge (#795) |
| Notices over Now Playing | Glass snackbar over the cover glow. | Yes |
| Lyrics in Focus | Synced lyrics, where lines blur by distance from the sung line ([#797](https://github.com/averylicious/muon/issues/797)). | No (`Modifier.blur`, Android 12+); needs a synced-lyrics source |
| A–Z bubble lens | Blur plus saturation over the rows while dragging. Lowest priority. | Yes |

The page also lists where backdrop blur doesn't fit: sheets, dialogs and menus (separate windows), Now Playing's glow (already blurred once per song), the player's grow morph (its fade cuts a backdrop off) and landscape (the rail).

## Lifecycle

Per [`../README.md`](../README.md): when Gen 4 ships to a device, retake `../current/` from real hardware and delete this folder.

Made by Claude (Opus 5.5, Claude Code in T3 Code) with Avery's decisions, 2026-11-28.
