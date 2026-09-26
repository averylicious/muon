#!/usr/bin/env python3
"""
Offline listening (#112) design mockups: three screens at Pixel 8 size (411 x 914 dp, 1080 x 2400 px),
light theme, in the Collection redesign's palette, type and refined icons.

    python3 offline_mockups.py [output-directory]      # needs rsvg-convert and Google Sans Flex

A design proposal for review, not an implementation. The numbers shown are illustrative.
"""
import os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "icons"))
import muon_icons as I

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
W, H = 411, 914
C = dict(bg="#fff8f6", ink="#231918", muted="#5e4f4d", acc="#8e4d45", on_acc="#ffffff", sel="#fadad5",
         outline="#a08c89", tonal="#f4cec8", card="#fbeeec", mini="#f7dfdb", div="#decac7", ok="#3d6a45")
F = "Google Sans Flex, sans-serif"

def t(x, y, s, size=14, fill="ink", weight=400, anchor="start"):
    s = s.replace("&", "&amp;")
    return (f'<text x="{x}" y="{y}" font-family="{F}" font-size="{size}" font-weight="{weight}" '
            f'fill="{C.get(fill, fill)}" text-anchor="{anchor}">{s}</text>')
def rect(x, y, w, h, fill, r=0, extra=""): return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{C.get(fill, fill)}" {extra}/>'
def icon(kind, x, y, size=24, fill="ink"): return I.svg(kind, x, y, size, C.get(fill, fill))
def path(d, x, y, size=24, fill="ink", w=2):
    return (f'<g transform="translate({x},{y}) scale({size/24})"><path d="{d}" fill="none" stroke="{C.get(fill, fill)}" '
            f'stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/></g>')
DOWNLOAD = "M12 4 V14 M7.5 9.8 L12 14.3 L16.5 9.8 M5.5 19.5 H18.5"
DONE = "M4.5 12.5 L9.5 17.5 L19.5 7"

def status_bar(): return t(24, 30, "8:06", 14, weight=500) + t(387, 30, "80%", 12, "muted", anchor="end")
def nav(active):
    out = [rect(0, 822, W, 92, "bg")]
    for i, (k, label) in enumerate([("library", "Library"), ("search", "Search"), ("settings", "Settings")]):
        cx = 68 + i * 137
        if label == active: out.append(rect(cx - 32, 834, 64, 32, "sel", 16))
        out.append(icon(k, cx - 12, 838)); out.append(t(cx, 886, label, 12, weight=500 if label == active else 400, anchor="middle"))
    out.append(rect(155, 900, 100, 4, "muted", 2))
    return "".join(out)
def mini(title, artist):
    return (rect(0, 750, W, 72, "mini") + rect(0, 750, 130, 3, "acc") + rect(12, 762, 48, 48, "#3b3450", 10)
            + t(72, 783, title, 15, weight=500) + t(72, 802, artist, 13, "muted")
            + f'<circle cx="340" cy="786" r="22" fill="{C["acc"]}"/>' + icon("pause", 330, 776, 20, "on_acc") + icon("next", 372, 774))
def row(y, head, sub, right=None, top=False, bottom=False, h=64, sub_fill="muted"):
    r = 20 if (top or bottom) else 4
    shape = rect(16, y, W - 32, h, "card", r)
    if top and not bottom: shape += rect(16, y + h - 8, W - 32, 8, "card")
    if bottom and not top: shape += rect(16, y, W - 32, 8, "card")
    return shape + t(32, y + 27, head, 16) + t(32, y + 48, sub, 13.5, sub_fill) + (right or "")
def switch(x, y, on):
    return (rect(x, y, 52, 32, "acc" if on else "sel", 16, f'stroke="{C["outline"]}" stroke-width="{0 if on else 2}"')
            + f'<circle cx="{x + (36 if on else 16)}" cy="{y + 16}" r="{12 if on else 8}" fill="{C["on_acc"] if on else C["outline"]}"/>')
def text_button(x, y, label, anchor="end"): return t(x, y, label, 14, "acc", 500, anchor)
def svg(body): return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="2400" viewBox="0 0 {W} {H}">'
                       f'<rect width="{W}" height="{H}" fill="{C["bg"]}"/>{body}</svg>')

# 1. Settings -> Storage
def storage():
    b = [status_bar(), t(24, 108, "Settings", 36, weight=500)]
    b.append(t(32, 160, "Storage", 14, "muted", 500))
    # Stacked usage bar: downloads, cache, free space.
    b.append(rect(16, 176, W - 32, 96, "card", 20))
    b.append(t(32, 204, "Muon uses 1.4 GB", 16, weight=500) + t(379, 204, "18 GB free", 13.5, "muted", anchor="end"))
    b.append(rect(32, 218, 347, 12, "sel", 6) + rect(32, 218, 110, 12, "acc", 6) + rect(136, 218, 40, 12, "tonal"))
    b.append(f'<circle cx="38" cy="252" r="5" fill="{C["acc"]}"/>' + t(48, 256, "Downloads", 12.5, "muted")
             + f'<circle cx="126" cy="252" r="5" fill="{C["tonal"]}"/>' + t(136, 256, "Played-song cache", 12.5, "muted")
             + f'<circle cx="262" cy="252" r="5" fill="{C["sel"]}" stroke="{C["outline"]}"/>' + t(272, 256, "Free", 12.5, "muted"))
    y = 282
    b.append(row(y, "Downloads", "212 songs · 1.1 GB", text_button(363, y + 38, "Clear"), top=True))
    b.append(row(y + 68, "Played-song cache", "340 MB of 2 GB", text_button(363, y + 106, "Clear")))
    b.append(row(y + 136, "Cache limit", "2 GB", path("M9 6 L15 12 L9 18", 348, y + 156, 20, "muted")))
    b.append(row(y + 204, "Download quality", "Opus, 84 kbps · set by Tauon for now", None))
    b.append(row(y + 272, "Store on SD card", "SanDisk 128 GB · 96 GB free", switch(327, y + 288, True), bottom=True))
    b.append(t(24, y + 372, "Only shown when a card is inserted. Removing the card", 12.5, "muted"))
    b.append(t(24, y + 390, "hides its downloads until it is back.", 12.5, "muted"))
    b.append(rect(16, y + 410, W - 32, 1, "div"))
    b.append(t(24, y + 440, "The cache keeps Opus copies of songs you play. Lossless", 13.5, "muted"))
    b.append(t(24, y + 459, "streams play from memory and never touch storage.", 13.5, "muted"))
    b.append(mini("Thrill Over Fear", "A Flow Mobz, Luna Blake") + nav("Settings"))
    return svg("".join(b))

# 2. An artist page with download controls and per-song state
def artist():
    b = [status_bar(), icon("back", 20, 64), t(64, 84, "Vicetone", 22, weight=500), t(387, 84, "42", 14, "muted", anchor="end")]
    # Download action: tonal button, then progress while downloading.
    b.append(rect(16, 112, 190, 44, "tonal", 22) + path(DOWNLOAD, 32, 122, 22, "ink") + t(62, 139, "Download all", 15, weight=500))
    b.append(t(222, 132, "42 songs", 13.5, "muted") + t(222, 150, "about 110 MB", 13.5, "muted"))
    b.append(rect(16, 172, W - 32, 52, "card", 16) + t(32, 194, "Downloading 12 of 42", 14, weight=500)
             + text_button(379, 194, "Cancel") + rect(32, 206, 347, 6, "sel", 3) + rect(32, 206, 99, 6, "acc", 3))
    songs = [("I Want It All (Remix)", "Bonnie McKee, Vicetone", "3:43", "done"),
             ("Project: Yi (feat. Vicetone)", "League of Legends, Vicetone", "4:20", "done"),
             ("Ride or Die", "The Knocks, Foster The People", "3:15", "progress"),
             ("Harmony", "Vicetone", "6:16", "queued"), ("Heartbeat", "Vicetone, Collin McLoughlin", "6:33", "queued"),
             ("Angels (feat. Kat Nestel)", "Vicetone, Kat Nestel", "3:34", "queued")]
    colours = ["#e8d3c0", "#3b3450", "#b8362e", "#e7e2d6", "#9aa7b5", "#1e2a38", "#f2c9a8"]
    for i, (title, sub, time, state) in enumerate(songs):
        y = 240 + i * 72
        b.append(rect(24, y + 8, 52, 52, colours[i], 10) + t(92, y + 30, title, 15.5, weight=500) + t(92, y + 50, sub, 13, "muted"))
        b.append(t(352, y + 40, time, 12, "muted", anchor="end"))
        if state == "done": b.append(f'<circle cx="376" cy="{y + 36}" r="9" fill="{C["acc"]}"/>' + path(DONE, 370, y + 30, 12, "on_acc", 3))
        elif state == "progress":
            b.append(f'<circle cx="376" cy="{y + 36}" r="8" fill="none" stroke="{C["sel"]}" stroke-width="2.5"/>'
                     f'<path d="M376 {y + 28} A8 8 0 0 1 383.6 {y + 38.5}" fill="none" stroke="{C["acc"]}" stroke-width="2.5" stroke-linecap="round"/>')
        elif state == "queued": b.append(f'<circle cx="376" cy="{y + 36}" r="8" fill="none" stroke="{C["outline"]}" stroke-width="1.5" stroke-dasharray="2 2.2"/>')
    b.append(t(24, 700, "Downloaded songs are marked. Long-press a song to download", 12, "muted"))
    b.append(t(24, 716, "or remove just that one.", 12, "muted"))
    b.append(rect(0, 766, W, 56, "bg"))
    b.append(nav("Library"))
    return svg("".join(b))

# 3. Offline: Tauon unreachable, the library shows what is on the phone
def offline():
    b = [status_bar(), t(24, 100, "Library", 22, weight=500)]
    b.append(rect(16, 120, W - 32, 76, "card", 20))
    b.append(path("M4 16.5 A4 4 0 0 1 6.5 9.2 A6 6 0 0 1 17.5 8.2 A4.2 4.2 0 0 1 19 16.5 Z M4 4 L20 20", 32, 134, 24, "acc"))
    b.append(t(68, 148, "Tauon isn't reachable", 16, weight=500) + t(68, 168, "Playing from your downloads", 13.5, "muted"))
    b.append(text_button(379, 158, "Retry"))
    chips = [("Songs", True), ("Artists", False), ("Playlists", False)]
    x = 24
    for label, on in chips:
        w = 96 if label == "Songs" else (78 if label == "Artists" else 88)
        b.append(rect(x, 212, w, 32, "sel" if on else "bg", 8, f'stroke="{C["outline"]}" stroke-width="{0 if on else 1}"'))
        b.append((path(DONE, x + 12, 220, 16, "ink") if on else "") + t(x + (w / 2 + (10 if on else 0)), 233, label, 14, weight=500, anchor="middle"))
        x += w + 8
    b.append(t(24, 272, "212 downloaded songs", 13.5, "muted") + text_button(379, 272, "Recently added ⌄"))
    songs = [("Waiting For Love", "Avicii · Stories", "3:50"), ("Castle", "Clarx, Harddope · Castle", "2:38"),
             ("The One I See", "CVBE · The One I See", "2:35"), ("Part-Time Lover", "Dabin, Olivia Ridgely", "3:39"),
             ("Disarm You (Let Me)", "Dj Audiojack · Embers", "3:28"), ("Start Over", "ellis, Laura Brehm", "2:28")]
    colours = ["#5b6b7a", "#c43f35", "#20243a", "#8a9a7a", "#6b2a4a", "#3aa39a"]
    for i, (title, sub, time) in enumerate(songs):
        y = 288 + i * 72
        b.append(rect(24, y + 8, 52, 52, colours[i], 10) + t(92, y + 30, title, 15.5, weight=500) + t(92, y + 50, sub, 13, "muted"))
        b.append(t(387, y + 40, time, 12, "muted", anchor="end"))
    b.append(t(24, 736, "Songs that aren't downloaded are hidden until Tauon is back.", 12, "muted"))
    b.append(mini("Waiting For Love", "Avicii") + nav("Library"))
    return svg("".join(b))

for name, fn in [("01-storage-settings", storage), ("02-artist-download", artist), ("03-offline-library", offline)]:
    p = os.path.join(OUT, name + ".svg")
    open(p, "w").write(fn())
    subprocess.run(["rsvg-convert", p, "-o", p[:-4] + ".png"], check=True)
    os.remove(p)
subprocess.run(["magick", *[os.path.join(OUT, n + ".png") for n in ("01-storage-settings", "02-artist-download", "03-offline-library")],
                "-resize", "540x", "-background", C["div"], "-splice", "24x0", "+append", "-gravity", "east", "-splice", "24x0",
                os.path.join(OUT, "overview.png")], check=True)
print("ok")
