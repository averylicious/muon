#!/usr/bin/env python3
"""
Muon's in-app icon set: writes app/src/main/res/drawable/ic_<kind>.xml and a preview sheet.

    python3 docs/design/icons/muon_icons.py [--preview out.png]

The set (approved 2026-09-25, #116): a 24 dp grid with 2 dp strokes and round caps and joins, to match
Google Sans Flex at full roundness. Transport glyphs are filled, their corners softened by a round-join
stroke. "settings" is Material Symbols Rounded "tune" (Apache-2.0; see
app/src/main/assets/licenses/MaterialSymbols-Apache-2.0.txt). Every other icon is drawn here.
The preview needs rsvg-convert.
"""
import os, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1,0 {cx + r},{cy} A{r},{r} 0 1,0 {cx - r},{cy} Z"

def rrect(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0,1 {x + w},{y + r} V{y + h - r} "
            f"A{r},{r} 0 0,1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0,1 {x},{y + h - r} "
            f"V{y + r} A{r},{r} 0 0,1 {x + r},{y} Z")

# Each part: ("s", d) a 2 dp round stroke, ("s1.7", d) a thinner stroke, ("f", d) a fill softened by a
# round-join stroke, ("F", d) a plain fill.
REPEAT = [("s", "M4 11.5 V10.5 A3.5 3.5 0 0 1 7.5 7 H19.5"), ("s", "M16.8 4.2 L19.6 7 L16.8 9.8"),
          ("s", "M20 12.5 V13.5 A3.5 3.5 0 0 1 16.5 17 H4.5"), ("s", "M7.2 14.2 L4.4 17 L7.2 19.8")]
ICONS = {
    "play": [("f", "M8.5 5.8 L18.5 12 L8.5 18.2 Z")],
    "pause": [("F", rrect(6, 5, 4, 14, 1.5)), ("F", rrect(14, 5, 4, 14, 1.5))],
    "previous": [("F", rrect(5, 5.5, 2.6, 13, 1.3)), ("f", "M18.5 6.3 L10.5 12 L18.5 17.7 Z")],
    "next": [("F", rrect(16.4, 5.5, 2.6, 13, 1.3)), ("f", "M5.5 6.3 L13.5 12 L5.5 17.7 Z")],
    # Two crossing curves leave horizontally, so each band enters its chevron centred on the tip.
    "shuffle": [("s", "M3.5 7 H6.6 C7.9 7 9.1 7.6 9.8 8.7 L14.2 15.3 C14.9 16.4 16.1 17 17.4 17 H20"),
                ("s", "M3.5 17 H6.6 C7.9 17 9.1 16.4 9.8 15.3 L14.2 8.7 C14.9 7.6 16.1 7 17.4 7 H20"),
                ("s", "M17.5 4.2 L20.3 7 L17.5 9.8"), ("s", "M17.5 14.2 L20.3 17 L17.5 19.8")],
    "repeat": REPEAT,
    "repeat-one": REPEAT + [("s1.7", "M11 10.6 L12.4 9.9 V14.1")],
    "volume": [("f", "M4 9.8 H7 L11.5 6 V18 L7 14.2 H4 Z"), ("s", "M15 9.2 A4 4 0 0 1 15 14.8"),
               ("s", "M17.6 6.6 A7.6 7.6 0 0 1 17.6 17.4")],
    "volume-low": [("f", "M5.5 9.8 H8.5 L13 6 V18 L8.5 14.2 H5.5 Z"), ("s", "M16.5 9.2 A4 4 0 0 1 16.5 14.8")],
    "search": [("s", circle(10.5, 10.5, 6)), ("s", "M15 15 L20 20")],
    "library": [("s", rrect(x, y, 6.5, 6.5, 2)) for x in (4, 13.5) for y in (4, 13.5)],
    "music": [("F", rrect(x, y, 3.2, 20 - y, 1.6)) for x, y in ((4.8, 13.5), (10.4, 5.5), (16, 10))],
    "check": [("s", "M5 12.5 L9.6 17 L19 7.5")],
    "back": [("s", "M19.5 12 H5"), ("s", "M11 6 L5 12 L11 18")],
    "collapse": [("s", "M6 9.5 L12 15.5 L18 9.5")],
    "close": [("s", "M6.5 6.5 L17.5 17.5 M17.5 6.5 L6.5 17.5")],
    # A list with a note: the queue of music. Distinct from play-next's triangle and add-queue's plus.
    "queue": [("s", "M4 6.5 H14 M4 11.5 H14 M4 16.5 H10"), ("s", "M18 17.2 V7.5 L20.5 8.5"),
              ("F", circle(16.1, 17.2, 2))],
    # A speech bubble holding text: words to sing along to.
    "lyrics": [("s", "M5.5 4.5 H18.5 A2 2 0 0 1 20.5 6.5 V14.5 A2 2 0 0 1 18.5 16.5 H10 L6 19.8 V16.5 H5.5 "
                     "A2 2 0 0 1 3.5 14.5 V6.5 A2 2 0 0 1 5.5 4.5 Z"),
               ("s", "M7.5 8.5 H16.5 M7.5 12.5 H13")],
    # The lower lines stop short, so the glyph at the bottom right has clear space around it.
    "play-next": [("s", "M4 6.5 H16 M4 11.5 H12 M4 16.5 H10"), ("f", "M15 14 V20 L20 17 Z")],
    "add-queue": [("s", "M4 6.5 H16 M4 11.5 H12 M4 16.5 H10"), ("s", "M17.5 14 V20 M14.5 17 H20.5")],
    "album": [("s", circle(12, 12, 8)), ("s", circle(12, 12, 2.2))],
    "artist": [("s", circle(12, 8, 3.6)), ("s", "M5 19.5 C5.8 16.2 8.6 14 12 14 C15.4 14 18.2 16.2 19 19.5")],
    "delete": [("s", "M4.5 7 H19.5 M9.5 4 H14.5"),
               ("s", "M6.5 7 L7.4 18.2 A2 2 0 0 0 9.4 20 H14.6 A2 2 0 0 0 16.6 18.2 L17.5 7"),
               ("s", "M10.3 11 V16 M13.7 11 V16")],
    "drag-handle": [("s", "M5 9 H19 M5 15 H19")],
}
# Material Symbols Rounded "tune", weight 400, grade 0, optical size 24; viewBox 0 -960 960 960.
TUNE = "M480-120q-17 0-28.5-11.5T440-160v-160q0-17 11.5-28.5T480-360q17 0 28.5 11.5T520-320v40h280q17 0 28.5 11.5T840-240q0 17-11.5 28.5T800-200H520v40q0 17-11.5 28.5T480-120Zm-320-80q-17 0-28.5-11.5T120-240q0-17 11.5-28.5T160-280h160q17 0 28.5 11.5T360-240q0 17-11.5 28.5T320-200H160Zm160-160q-17 0-28.5-11.5T280-400v-40H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h120v-40q0-17 11.5-28.5T320-600q17 0 28.5 11.5T360-560v160q0 17-11.5 28.5T320-360Zm160-80q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm160-160q-17 0-28.5-11.5T600-640v-160q0-17 11.5-28.5T640-840q17 0 28.5 11.5T680-800v40h120q17 0 28.5 11.5T840-720q0 17-11.5 28.5T800-680H680v40q0 17-11.5 28.5T640-600Zm-480-80q-17 0-28.5-11.5T120-720q0-17 11.5-28.5T160-760h320q17 0 28.5 11.5T520-720q0 17-11.5 28.5T480-680H160Z"

def vector_xml(kind):
    head = ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated by docs/design/icons/muon_icons.py; edit there, not here. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n')
    if kind == "settings":
        return (head + '    android:viewportWidth="960" android:viewportHeight="960">\n'
                '    <!-- Material Symbols Rounded "tune", Apache-2.0. -->\n'
                '    <group android:translateY="960">\n'
                f'        <path android:fillColor="#FF000000" android:pathData="{TUNE}"/>\n'
                '    </group>\n</vector>\n')
    body = []
    for style, d in ICONS[kind]:
        if style.startswith("s"):
            width = style[1:] or "2"
            body.append(f'    <path android:fillColor="#00000000" android:strokeColor="#FF000000"\n'
                        f'        android:strokeWidth="{width}" android:strokeLineCap="round"\n'
                        f'        android:strokeLineJoin="round" android:pathData="{d}"/>')
        elif style == "f":
            body.append(f'    <path android:fillColor="#FF000000" android:strokeColor="#FF000000"\n'
                        f'        android:strokeWidth="2" android:strokeLineJoin="round" android:pathData="{d}"/>')
        else:
            body.append(f'    <path android:fillColor="#FF000000" android:pathData="{d}"/>')
    return head + '    android:viewportWidth="24" android:viewportHeight="24">\n' + "\n".join(body) + "\n</vector>\n"

def svg(kind, x, y, size, ink="#231918"):
    if kind == "settings":
        return (f'<g transform="translate({x},{y}) scale({size / 960}) translate(0,960)">'
                f'<path fill="{ink}" d="{TUNE}"/></g>')
    parts = []
    for style, d in ICONS[kind]:
        if style.startswith("s"):
            parts.append(f'<path d="{d}" fill="none" stroke="{ink}" stroke-width="{style[1:] or 2}" '
                         'stroke-linecap="round" stroke-linejoin="round"/>')
        elif style == "f":
            parts.append(f'<path d="{d}" fill="{ink}" stroke="{ink}" stroke-width="2" stroke-linejoin="round"/>')
        else:
            parts.append(f'<path d="{d}" fill="{ink}"/>')
    return f'<g transform="translate({x},{y}) scale({size / 24})">' + "".join(parts) + "</g>"

KINDS = list(ICONS) + ["settings"]

def preview(path):
    cols, cell = 6, 150
    rows = -(-len(KINDS) // cols)
    w, h = cols * cell + 40, rows * cell + 40
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}"><rect width="{w}" height="{h}" fill="#fff8f6"/>']
    for i, kind in enumerate(KINDS):
        x, y = 20 + (i % cols) * cell, 20 + (i // cols) * cell
        out.append(svg(kind, x + 43, y + 18, 64))
        out.append(f'<text x="{x + cell / 2}" y="{y + 115}" font-family="Google Sans Flex, sans-serif" '
                   f'font-size="17" fill="#5e4f4d" text-anchor="middle">{kind}</text>')
    out.append("</svg>")
    svg_path = path[:-4] + ".svg"
    open(svg_path, "w").write("".join(out))
    subprocess.run(["rsvg-convert", svg_path, "-o", path], check=True)
    os.remove(svg_path)

if __name__ == "__main__":
    for kind in KINDS:
        with open(os.path.join(OUT, f"ic_{kind.replace('-', '_')}.xml"), "w") as f:
            f.write(vector_xml(kind))
    if "--preview" in sys.argv:
        preview(sys.argv[sys.argv.index("--preview") + 1])
    print(f"wrote {len(KINDS)} icons")
