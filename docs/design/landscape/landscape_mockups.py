#!/usr/bin/env python3
"""
Landscape library mockups: where the mini player goes on a phone held sideways. Pixel 8 in landscape
(914 x 411 dp, 2400 x 1080 px), light theme, in the Collection redesign's palette, type and icons.

    python3 landscape_mockups.py [output-directory]      # needs rsvg-convert, magick and Google Sans Flex

A design proposal for review, not an implementation. A is what Canary .254 draws; B is the proposal.
"""
import os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "icons"))
import muon_icons as I

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
W, H = 914, 411
C = dict(bg="#fff8f6", ink="#231918", muted="#5e4f4d", acc="#8e4d45", on_acc="#ffffff", sel="#fadad5",
         outline="#a08c89", tonal="#f4cec8", card="#fbeeec", mini="#f7dfdb", div="#decac7", track="#f4cec8")
F = "Google Sans Flex, sans-serif"
RAIL = 80

def t(x, y, s, size=14, fill="ink", weight=400, anchor="start"):
    s = s.replace("&", "&amp;")
    return (f'<text x="{x}" y="{y}" font-family="{F}" font-size="{size}" font-weight="{weight}" '
            f'fill="{C.get(fill, fill)}" text-anchor="{anchor}">{s}</text>')
def rect(x, y, w, h, fill, r=0, extra=""): return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{C.get(fill, fill)}" {extra}/>'
def icon(kind, x, y, size=24, fill="ink"): return I.svg(kind, x, y, size, C.get(fill, fill))
def svg(body): return (f'<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="1080" viewBox="0 0 {W} {H}">'
                       f'<rect width="{W}" height="{H}" fill="{C["bg"]}"/>{body}</svg>')

SONGS = [("LOVELY BASTARDS", "ZWE1HVNDXR, yatashigang · LOVELY BASTARDS", "1:56", "#b0309a"),
         ("Crazy For You - Extended Mix", "Zonderling, BISHOP · Crazy For You", "4:59", "#3a3a3a"),
         ("I Want You To Know", "Zedd, Selena Gomez · True Colors", "3:59", "#6aa3c8"),
         ("The Middle", "Zedd, Maren Morris, Grey · The Middle", "3:04", "#1d2320"),
         ("Stay (with Alessia Cara) - Jonas Blue Remix", "Zedd, Alessia Cara, Jonas Blue · Stay", "4:26", "#6b4a52"),
         ("Stay (with Alessia Cara)", "Zedd, Alessia Cara · Stay", "3:30", "#8a8f98")]

def chrome():
    b = [t(40, 22, "2:06", 13, weight=500), t(890, 22, "80%", 12, "muted", anchor="end")]
    # The rail, its three destinations centred where a thumb reaches them.
    for i, (k, label) in enumerate([("library", "Library"), ("search", "Search"), ("settings", "Settings")]):
        cy = 150 + i * 62
        if i == 0: b.append(rect(RAIL / 2 - 28 + 12, cy - 16, 56, 32, "sel", 16))
        b.append(icon(k, RAIL / 2 - 12 + 12, cy - 12))
        b.append(t(RAIL / 2 + 12, cy + 32, label, 12, weight=500 if i == 0 else 400, anchor="middle"))
    b.append(rect(407, 402, 100, 4, "muted", 2))
    return "".join(b)

def chips_and_sort(x0, right):
    b, x = [], x0
    for i, (label, w) in enumerate([("Songs", 84), ("Albums", 76), ("Artists", 70), ("Playlists", 80)]):
        if i == 0:
            b.append(rect(x, 38, w, 32, "sel", 8) + icon("check", x + 8, 45, 18) + t(x + 32, 59, label, 14, weight=500))
        else:
            b.append(rect(x, 38, w, 32, "bg", 8, f'stroke="{C["outline"]}" stroke-width="1"') + t(x + w / 2, 59, label, 14, anchor="middle"))
        x += w + 8
    b.append(t(x0, 100, "962 songs", 14, "muted") + t(right - 22, 100, "Recently added", 14, "acc", 500, anchor="end")
             + f'<path d="M{right - 16} 94 L{right - 11} 99 L{right - 6} 94" fill="none" stroke="{C["acc"]}" stroke-width="1.8" stroke-linecap="round"/>')
    return "".join(b)

def songs(x0, right, y0, bottom, playing=3):
    b = [f'<clipPath id="list"><rect x="0" y="{y0}" width="{W}" height="{bottom - y0}"/></clipPath><g clip-path="url(#list)">']
    for i, (title, sub, time, colour) in enumerate(SONGS):
        y = y0 + i * 64
        if i == playing: b.append(rect(x0 - 8, y + 2, right - x0 + 8, 60, "sel", 16))
        b.append(rect(x0, y + 8, 48, 48, colour, 8))
        if i == playing:
            for j, h in enumerate((10, 18, 13)): b.append(rect(x0 + 15 + j * 7, y + 40 - h, 4, h, "on_acc", 2))
        title = title if len(title) < 40 else title[:37] + "…"
        b.append(t(x0 + 64, y + 29, title, 15.5, weight=500) + t(x0 + 64, y + 48, sub, 13, "muted"))
        b.append(t(right, y + 38, time, 12.5, "muted", anchor="end"))
    b.append("</g>")
    return "".join(b)

def play_button(cx, cy, r=22):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{C["acc"]}"/>' + icon("pause", cx - 10, cy - 10, 20, "on_acc")

# A. What Canary .254 draws: the mini player across the bottom, under the list.
def current():
    b = [chrome(), chips_and_sort(RAIL + 36, 890)]
    b.append(songs(RAIL + 36, 890, 114, 320))
    b.append(rect(RAIL + 12, 320, W - RAIL - 12, 72, "mini", 16) + rect(RAIL + 12, 376, W - RAIL - 12, 16, "mini"))
    b.append(rect(RAIL + 24, 332, 48, 48, "#1d2320", 10) + t(RAIL + 86, 353, "The Middle", 15, weight=500)
             + t(RAIL + 86, 372, "Zedd, Maren Morris, Grey", 13, "muted") + play_button(832, 356) + icon("next", 868, 344))
    return svg("".join(b))

# B. The proposal: the player is a panel beside the list, and the list keeps the full height.
PANEL = 272
def beside():
    right = W - PANEL - 32
    b = [chrome(), chips_and_sort(RAIL + 36, right)]
    b.append(songs(RAIL + 36, right, 114, 396))
    x = W - PANEL - 12
    b.append(rect(x, 34, PANEL, 362, "mini", 24))
    b.append(rect(x + 16, 50, 120, 120, "#1d2320", 16))
    b.append(t(x + 16, 200, "The Middle", 18, weight=500) + t(x + 16, 222, "Zedd, Maren Morris, Grey", 13.5, "muted"))
    b.append(rect(x + 16, 246, PANEL - 32, 6, "track", 3) + rect(x + 16, 246, 70, 6, "acc", 3))
    b.append(t(x + 16, 270, "0:48", 12, "muted") + t(x + PANEL - 16, 270, "3:04", 12, "muted", anchor="end"))
    cy = 320
    b.append(icon("previous", x + PANEL / 2 - 76, cy - 12) + play_button(x + PANEL / 2, cy, 28) + icon("next", x + PANEL / 2 + 52, cy - 12))
    b.append(icon("lyrics", x + 20, 360, 20, "muted") + icon("queue", x + PANEL - 40, 360, 20, "muted"))
    return svg("".join(b))

names = [("A-current", current), ("B-beside", beside)]
for name, fn in names:
    p = os.path.join(OUT, name + ".svg")
    open(p, "w").write(fn())
    subprocess.run(["rsvg-convert", p, "-o", p[:-4] + ".png"], check=True)
    os.remove(p)
subprocess.run(["magick", *[os.path.join(OUT, n + ".png") for n, _ in names], "-resize", "1200x",
                "-background", C["div"], "-splice", "0x24", "-append", "-gravity", "south", "-splice", "0x24",
                os.path.join(OUT, "overview.png")], check=True)
print("ok")
