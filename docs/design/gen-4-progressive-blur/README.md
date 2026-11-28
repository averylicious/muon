# Gen 4 - Progressive Blur: mockups (proposal / target)

**These are proposals, not the current app.** What Muon draws today is in [`../current/`](../current/README.md) (Gen 3, M3 Expressive Refined, RC .1420). The mockups are drawn on that baseline. Plan and decisions: [#796](https://github.com/averylicious/muon/issues/796); milestone **Gen 4 - Progressive Blur**.

Open [`index.html`](index.html) in a browser (a copy of the [claude.ai mockup](https://claude.ai/code/artifact/aa418011-b3f2-47fb-95f6-31a6fe028303), version 5). It is one self-contained page: interactive phones you can scroll, tap and drag, with controls for device, navigation bar, floating style, bar surface, palette and theme. The fonts load from Google Fonts, with a system fallback. Its blur is the browser's approximation of Android's; costs need a Pixel trace. The songs and covers are fictional.

## What it proposes

| Area | Proposal | Needs Android 17 QPR2 (37.2)? |
| --- | --- | --- |
| Navigation bar | **Edge to edge** (default; today's Material bar with a softer top edge) or **Floating** (opt-in glass capsules). Floating is stacked by default; **Tuck in while scrolling** slides the mini player beside the current tab. | No: Frost can clip its blurred copy to capsules from Android 12 |
| Bar surface | **Frosted** (blur), **Tinted** (see-through colour, no blur), **Solid** (opaque Material You or Muon containers). It covers every bar, toolbar, search pill and snackbar. | Frosted only, for the 37.2 backdrop extras |
| Settings › Appearance | As built (Canary .1791): Navigation bar, then with Floating Bar width, Tab height and Tuck in while scrolling; Blur, now the switch for every blur, which greys out Frosted when off; Bar surface. The Material 3 Expressive experiment section is gone. | No |
| Floating page toolbar | Tinted glass over album and artist pages. Build first. | Yes (fallback: today's solid toolbar) |
| Queue and Lyrics top bars, Search pill | Rows dissolve under a progressive top edge. | Yes, plus Compose 1.13 for the progressive edge (#795) |
| Notices over Now Playing | Glass snackbar over the cover glow. | Yes |
| Now Playing, reworked | Lyrics and Up next become views inside the player, chosen from a glass capsule: the cover shrinks into the header, the view scrolls between progressive edges, and the controls stay. Includes Lyrics in Focus (lines blur by distance from the sung line) and #797's plain-lyrics hint ([#797](https://github.com/averylicious/muon/issues/797)). Proposal, not yet decided. | No: the edges blur the player's own content (Compose 1.13); the line blur is `Modifier.blur` (Android 12+) |
| A–Z bubble lens | Blur plus saturation over the rows while dragging. Lowest priority. | Yes |

The page also lists where backdrop blur doesn't fit: sheets, dialogs and menus (separate windows), Now Playing's glow (already blurred once per song), the player's grow morph (its fade cuts a backdrop off) and landscape (the rail).

## Lifecycle

Per [`../README.md`](../README.md): when Gen 4 ships to a device, retake `../current/` from real hardware and delete this folder.

Made by Claude (Opus 5.5, Claude Code in T3 Code) with Avery's decisions, 2026-11-28; Now Playing rework and the as-built Settings added the same day.
