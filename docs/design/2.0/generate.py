#!/usr/bin/env python3
"""
Muon 2.0 design mockups: every screen, light and dark, at Pixel 8 resolution (1080x2400).

    python3 final.py [output-directory]

Requires Pillow, rsvg-convert, and the Google Sans Flex variable font. Point GOOGLE_SANS_FLEX at the
font file if it is not in the default location. Colours approximate Material You derived from the
user's wallpaper; album art is drawn as flat colour, and album and artist page content is illustrative.
"""
import functools, io, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont, ImageChops

GSF = os.environ.get("GOOGLE_SANS_FLEX", "/home/avery/.local/share/fonts/illogical-impulse-google-sans-flex/"
                     "GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf")
S = 2.625                                  # Pixel 8 density: 411x914dp is 1080x2400px
W, H = 1080 / S, 2400 / S
ROND = 100                                 # decided: fully rounded, matching the user's Pixel
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))

def dp(v): return int(round(v * S))

THEMES = {
    "light": dict(bg=(255, 248, 246), ground=(252, 234, 233), ink=(35, 25, 24), muted=(94, 79, 77), acc=(142, 77, 69),
                  on_acc=(255, 255, 255), sel=(250, 218, 213), outline=(160, 140, 137), off=(249, 216, 211),
                  mini=(247, 223, 219), tonal=(244, 206, 200), card=(254, 246, 245), sch=(244, 224, 221),
                  sheet=(250, 236, 233), div=(222, 202, 199), err=(179, 38, 30), on_err=(255, 255, 255),
                  inv=(56, 46, 44), inv_on=(250, 238, 236), inv_acc=(255, 180, 168), pressed=(240, 222, 219),
                  keys=(240, 229, 227), key=(255, 251, 250), keydark=(228, 215, 213), gesture=(70, 60, 58),
                  dialog=(252, 238, 236)),
    "dark": dict(bg=(26, 17, 16), ground=(26, 17, 16), ink=(241, 223, 220), muted=(216, 194, 190), acc=(255, 180, 168),
                 on_acc=(86, 30, 22), sel=(93, 64, 59), outline=(160, 140, 137), off=(93, 64, 59),
                 mini=(39, 29, 28), tonal=(93, 64, 59), card=(43, 32, 31), sch=(50, 40, 38),
                 sheet=(34, 25, 24), div=(83, 67, 64), err=(255, 180, 171), on_err=(105, 0, 5),
                 inv=(241, 223, 220), inv_on=(56, 46, 44), inv_acc=(142, 77, 69), pressed=(52, 40, 38),
                 keys=(35, 27, 26), key=(62, 52, 50), keydark=(48, 39, 37), gesture=(220, 205, 202),
                 dialog=(50, 40, 38)),
}
P = THEMES["light"]

# ---------- type, shapes, icons ----------
@functools.lru_cache(None)
def fr(sp, weight=400):
    f = ImageFont.truetype(GSF, dp(sp))
    vals = []
    for a in f.get_variation_axes():
        n = (a["name"].decode() if isinstance(a["name"], bytes) else a["name"]).lower()
        v = a["default"]
        if "weight" in n: v = weight
        elif "round" in n: v = ROND
        elif "optical" in n: v = sp
        vals.append(max(a["minimum"], min(a["maximum"], v)))
    f.set_variation_by_axes(vals)
    return f

def T(d, xy, s, sp, w, fill, anchor="la"):
    d.text((dp(xy[0]), dp(xy[1])), s, font=fr(sp, w), fill=fill, anchor=anchor)

def tw(s, sp, w): return fr(sp, w).getlength(s) / S

def rr(d, x, y, w, h, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([dp(x), dp(y), dp(x + w), dp(y + h)], radius=dp(r), fill=fill, outline=outline, width=max(1, dp(width)))

F, St = "f", "s"
ICONS = {  # one rule: lines at 0/45/90 degrees, flat terminals, square joins, 2dp; circles allowed
 "play": [(F, "M8,5 L19,12 L8,19 Z")], "pause": [(F, "M7.5,5 H10.5 V19 H7.5 Z"), (F, "M13.5,5 H16.5 V19 H13.5 Z")],
 "previous": [(F, "M5.5,5 H8 V19 H5.5 Z"), (F, "M18.5,5 L9.5,12 L18.5,19 Z")],
 "next": [(F, "M5.5,5 L14.5,12 L5.5,19 Z"), (F, "M16,5 H18.5 V19 H16 Z")],
 "search": [(St, "M10.5,4.5 a6,6 0 1 0 0.01,0"), (St, "M14.8,14.8 L20,20")],
 "library": [(St, "M4,4 H10.5 V10.5 H4 Z"), (St, "M13.5,4 H20 V10.5 H13.5 Z"), (St, "M4,13.5 H10.5 V20 H4 Z"), (St, "M13.5,13.5 H20 V20 H13.5 Z")],
 "music": [(F, "M4.5,14 H7.5 V20 H4.5 Z"), (F, "M10.5,7 H13.5 V20 H10.5 Z"), (F, "M16.5,10.5 H19.5 V20 H16.5 Z")],
 "settings": [(St, "M3.5,7 H20.5"), (St, "M3.5,12 H20.5"), (St, "M3.5,17 H20.5"), (F, "M7,4.75 H10 V9.25 H7 Z"),
              (F, "M14,9.75 H17 V14.25 H14 Z"), (F, "M9,14.75 H12 V19.25 H9 Z")],
 "shuffle": [(St, "M3.5,7 H7 L17,17 H16.5"), (St, "M3.5,17 H7 L17,7 H16.5"), (F, "M16.5,3.5 L20.5,7 L16.5,10.5 Z"), (F, "M16.5,13.5 L20.5,17 L16.5,20.5 Z")],
 "repeat": [(St, "M6.5,7 H18 V12"), (St, "M17.5,17 H6 V12"), (F, "M15,11.5 L18,16 L21,11.5 Z"), (F, "M3,12.5 L6,8 L9,12.5 Z")],
 "vol_low": [(F, "M4,9.5 H8 L12.5,5.5 V18.5 L8,14.5 H4 Z")],
 "vol_high": [(F, "M4,9.5 H8 L12.5,5.5 V18.5 L8,14.5 H4 Z"), (St, "M15.5,9 L15.5,15"), (St, "M18.5,6.5 L18.5,17.5")],
 "back": [(St, "M19,12 H5"), (St, "M11,6 L5,12 L11,18")], "down": [(St, "M6,9 L12,15 L18,9")],
 "close": [(St, "M6,6 L18,18"), (St, "M18,6 L6,18")], "drag": [(St, "M6,9.5 H18"), (St, "M6,14.5 H18")],
 "queue": [(St, "M4,7 H16"), (St, "M4,12 H16"), (St, "M4,17 H11"), (F, "M14.5,14 L20,17 L14.5,20 Z")],
 "lyrics": [(St, "M4,7 H20"), (St, "M4,12 H20"), (St, "M4,17 H14")],
 "play_next": [(St, "M4,7 H13"), (St, "M4,12 H13"), (St, "M4,17 H9"), (F, "M12.5,13.5 L18,17 L12.5,20.5 Z"), (St, "M20,13.5 V20.5")],
 "add_queue": [(St, "M4,7 H15"), (St, "M4,12 H15"), (St, "M4,17 H11"), (St, "M18,13.5 V20.5"), (St, "M14.5,17 H21.5")],
 "album": [(St, "M12,3.5 a8.5,8.5 0 1 0 0.01,0"), (F, "M12,10 a2,2 0 1 0 0.01,0")],
 "artist": [(St, "M12,4 a4,4 0 1 0 0.01,0"), (St, "M4.5,20.5 a7.5,6.5 0 0 1 15,0")],
 "delete": [(St, "M4,6.5 H20"), (St, "M9,6.5 V4 H15 V6.5"), (St, "M6.5,6.5 L7.5,20 H16.5 L17.5,6.5"), (St, "M10,10 V16.5"), (St, "M14,10 V16.5")],
}
@functools.lru_cache(None)
def icon_img(name, px, rgb):
    c = "#%02X%02X%02X" % rgb
    body = "".join(f'<path d="{p}" fill="{c}"/>' if m == F else
                   f'<path d="{p}" fill="none" stroke="{c}" stroke-width="2" stroke-linecap="butt" stroke-linejoin="miter"/>'
                   for m, p in ICONS[name])
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="0 0 24 24">{body}</svg>'
    png = subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px)], input=svg.encode(), capture_output=True, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")
def icon(img, name, cx, cy, size, rgb):
    im = icon_img(name, dp(size), rgb)
    img.paste(im, (dp(cx) - im.width // 2, dp(cy) - im.height // 2), im)

ART = {"After Hours": (120, 45, 40), "Youngblood (Deluxe)": (70, 45, 110), "Unite": (48, 48, 50), "Ain't You": (205, 120, 70),
       "Speed of Sound": (30, 70, 130), "Chasing Dreams": (38, 70, 83), "Hope": (20, 60, 40), "You Want Me": (200, 190, 150),
       "Bubbles & Boathouses": (60, 110, 150), "Say Goodbye": (180, 150, 200), "Dancin (Krono Remix)": (150, 120, 90)}
def art(d, key, x, y, size, r=10): rr(d, x, y, size, size, r, fill=ART.get(key, (120, 110, 100)))
def avatar(d, cx, cy, r, ini, col):
    d.ellipse([dp(cx - r), dp(cy - r), dp(cx + r), dp(cy + r)], fill=col)
    T(d, (cx, cy - r * 0.42), ini, r * 0.62, 600, (255, 255, 255), "ma")

# ---------- shared pieces ----------
def status(d, label):
    T(d, (26, 16), "8:06", 15, 600, P["ink"])
    w = tw(label, 12, 700) + 24
    rr(d, W - 16 - w, 12, w, 24, 12, fill=P["sel"]); T(d, (W - 16 - w / 2, 16), label, 12, 700, P["acc"], "ma")
def frame(ground, label):
    img = Image.new("RGB", (1080, 2400), P[ground]); d = ImageDraw.Draw(img); status(d, label)
    return img, d
def gesture(d): rr(d, W / 2 - 54, H - 12, 108, 4, 2, fill=P["gesture"])
def scrim(img, label, alpha=82):
    img = Image.alpha_composite(img.convert("RGBA"), Image.new("RGBA", img.size, (0, 0, 0, alpha))).convert("RGB")
    d = ImageDraw.Draw(img); status(d, label); return img, d
def back(img, d):
    d.ellipse([dp(23.8), dp(58.1), dp(64), dp(98.3)], fill=P["sch"]); icon(img, "back", 43.9, 78.2, 22, P["ink"])
def large_title(d, s, sub=None):             # Material 3 large top app bar, expanded: displaySmall, regular
    T(d, (25.6, 197.5), s, 36, 400, P["ink"], "ls")
    if sub: T(d, (25.6, 210), sub, 16, 400, P["muted"])
def mini_nav(img, d, sel, ground="bg"):
    my, ny = H - 146, H - 80
    rr(d, 0, my, W, 90, 22, fill=P["mini"])
    rr(d, 22, my + 4, W - 44, 3, 1.5, fill=P["off"]); rr(d, 22, my + 4, (W - 44) * 0.2, 3, 1.5, fill=P["acc"])
    art(d, "Unite", 14, my + 14, 44, 9)
    T(d, (70, my + 16), "Unite", 15, 600, P["ink"]); T(d, (70, my + 38), "Ahrix", 13, 400, P["muted"])
    d.ellipse([dp(W - 102), dp(my + 14), dp(W - 58), dp(my + 58)], fill=P["tonal"])
    icon(img, "pause", W - 80, my + 36, 20, P["ink"]); icon(img, "next", W - 34, my + 36, 22, P["ink"])
    d.rectangle([0, dp(ny), 1080, 2400], fill=P[ground])
    for i, (ic, lab) in enumerate([("library", "Library"), ("search", "Search"), ("settings", "Settings")]):
        cx = W / 6 + i * W / 3; on = i == sel
        if on: rr(d, cx - 30, ny + 10, 60, 32, 16, fill=P["sel"])
        icon(img, ic, cx, ny + 26, 22, P["ink"] if on else P["muted"])
        T(d, (cx, ny + 50), lab, 12, 700 if on else 500, P["ink"] if on else P["muted"], "ma")
    gesture(d)
def pills(d, y, sel):
    x = 20
    for i, lab in enumerate(["Songs", "Albums", "Artists", "Playlists"]):
        w = tw(lab, 14, 600) + 36; on = i == sel
        rr(d, x, y, w, 36, 18, fill=P["sel"] if on else None, outline=None if on else P["outline"])
        T(d, (x + w / 2, y + 10), lab, 14, 600, P["ink"], "ma"); x += w + 8
def song_row(img, d, y, title, sub, dur, key, current=False, pressed=False):
    if pressed: d.rectangle([0, dp(y), 1080, dp(y + 72)], fill=P["pressed"])
    if current: rr(d, 6, y + 20, 3, 32, 1.5, fill=P["acc"])
    art(d, key, 20, y + 10, 52)
    T(d, (86, y + 17), title, 16, 600, P["acc"] if current else P["ink"])
    T(d, (86, y + 41), sub, 13, 400, P["muted"])
    if dur: T(d, (W - 20, y + 30), dur, 13, 500, P["muted"], "ra")
def group(img, x, y, w, heights, gap=2.5):
    ys, yy = [], y
    for i, h in enumerate(heights):
        bx, by, bw, bh = dp(x), dp(yy), dp(w), dp(h)
        m1 = Image.new("L", (bw, bh), 0); ImageDraw.Draw(m1).rounded_rectangle([0, 0, bw - 1, bh - 1], radius=dp(24 if i == 0 else 5), fill=255, corners=(True, True, False, False))
        m2 = Image.new("L", (bw, bh), 0); ImageDraw.Draw(m2).rounded_rectangle([0, 0, bw - 1, bh - 1], radius=dp(24 if i == len(heights) - 1 else 5), fill=255, corners=(False, False, True, True))
        img.paste(Image.new("RGB", (bw, bh), P["card"]), (bx, by), ImageChops.multiply(m1, m2))
        ys.append(yy); yy += h + gap
    return ys
def two(d, x, y, title, sub, tcolor=None):
    T(d, (x, y + 14), title, 16, 600, tcolor or P["ink"]); T(d, (x, y + 38), sub, 13, 400, P["muted"])
def keyboard(img, d, suggestions):
    top = H - 292
    d.rectangle([0, dp(top), 1080, 2400], fill=P["keys"])
    for i, s in enumerate(suggestions):
        T(d, (W / 6 + i * W / 3, top + 13), s, 16, 500, P["ink"], "ma")
        if i < 2: d.line([dp(W / 3 * (i + 1)), dp(top + 12), dp(W / 3 * (i + 1)), dp(top + 36)], fill=P["div"], width=dp(1))
    gap, kh = 6, 46; kw = (W - 8 - 9 * gap) / 10; ky = top + 52
    for r, row in enumerate(["qwertyuiop", "asdfghjkl", "zxcvbnm"]):
        off = (W - (len(row) * kw + (len(row) - 1) * gap)) / 2
        for i, ch in enumerate(row):
            x, y = off + i * (kw + gap), ky + r * (kh + 10)
            rr(d, x, y, kw, kh, 7, fill=P["key"]); T(d, (x + kw / 2, y + 11), ch, 21, 400, P["ink"], "ma")
    y3, y4 = ky + 2 * (kh + 10), ky + 3 * (kh + 10)
    rr(d, 4, y3, kw * 1.4, kh, 7, fill=P["keydark"]); rr(d, W - 4 - kw * 1.4, y3, kw * 1.4, kh, 7, fill=P["keydark"])
    rr(d, 4, y4, kw * 1.4, kh, 7, fill=P["keydark"]); T(d, (4 + kw * 0.7, y4 + 13), "?123", 15, 500, P["ink"], "ma")
    rr(d, 8 + kw * 1.4 + gap, y4, kw, kh, 7, fill=P["keydark"])
    sx = 8 + kw * 2.4 + 2 * gap
    rr(d, sx, y4, W - sx - kw * 2.4 - 3 * gap - 4, kh, 7, fill=P["key"])
    rr(d, W - 4 - kw * 1.4 - gap - kw, y4, kw, kh, 7, fill=P["keydark"])
    rr(d, W - 4 - kw * 1.4, y4, kw * 1.4, kh, kh / 2, fill=P["acc"]); icon(img, "search", W - 4 - kw * 0.7, y4 + kh / 2, 22, P["on_acc"])
    gesture(d)
def snackbar(d, y, message, action):
    rr(d, 16, y, W - 32, 52, 4, fill=P["inv"])
    T(d, (32, y + 16), message, 15, 500, P["inv_on"]); T(d, (W - 32, y + 16), action, 15, 600, P["inv_acc"], "ra")
def text_mid(d, x, cy, s, sp, w, fill, align="l"):
    """One line of text with its capital height centred on cy."""
    f = fr(sp, w); cap = -f.getbbox("H", anchor="ls")[1]
    d.text((dp(x), dp(cy) + cap / 2), s, font=f, fill=fill, anchor=align + "s")
def icon_text_width(ic, isize, label, sp, weight, gap=8):
    l, _, r, _ = icon_img(ic, dp(isize), (0, 0, 0)).getchannel("A").getbbox()
    tl, _, tr, _ = fr(sp, weight).getbbox(label, anchor="ls")
    return ((r - l) + dp(gap) + (tr - tl)) / S
def icon_text(img, d, x, cy, ic, isize, label, sp, weight, icolor, tcolor=None, gap=8):
    """An icon beside one line of text, aligned by what is actually drawn: the icon's shape and the
    text's capital height share a centre line, and the gap runs between the two drawn edges.
    Aligning the icon's 24dp frame instead left glyphs such as play visibly off-centre."""
    im = icon_img(ic, dp(isize), icolor)
    l, t, r, b = im.getchannel("A").getbbox()
    f = fr(sp, weight); tl = f.getbbox(label, anchor="ls")[0]; cap = -f.getbbox("H", anchor="ls")[1]
    px, pcy = dp(x), dp(cy)
    img.paste(im, (px - l, pcy - (t + b) // 2), im)
    d.text((px + (r - l) + dp(gap) - tl, pcy + cap / 2), label, font=f, fill=tcolor or icolor, anchor="ls")
def button(img, d, x, y, w, label, ic, filled, h=48):
    rr(d, x, y, w, h, h / 2, fill=P["acc"] if filled else P["tonal"])
    fg = P["on_acc"] if filled else P["ink"]
    icon_text(img, d, x + (w - icon_text_width(ic, 20, label, 16, 500)) / 2, y + h / 2, ic, 20, label, 16, 500, fg)

SONGS = [("Blinding Lights", "The Weeknd", "3:22", "After Hours"), ("Unite", "Ahrix", "3:03", "Unite"),
         ("Chasing Dreams", "Jim Yosef; Valentina Franco", "3:00", "Chasing Dreams"), ("Hope", "Shiv; Dylan Smith", "2:49", "Hope"),
         ("You Want Me", "Dirtyphonics; Circadian", "4:45", "You Want Me"),
         ("If Walls Could Talk", "5 Seconds of Summer", "3:02", "Youngblood (Deluxe)"),
         ("Bubbles & Boathouses", "12 Feet Deep", "3:05", "Bubbles & Boathouses")]
ALBUMS = [("After Hours", "The Weeknd"), ("Youngblood (Deluxe)", "5 Seconds of Summer"), ("Unite", "Ahrix"),
          ("Ain't You", "Aexcit"), ("Speed of Sound", "Aerreo; Stereohats"), ("Dancin (Krono Remix)", "Aaron Smith; Krono")]
ARTISTS = [("The Weeknd", "W", (120, 45, 40), "After Hours"), ("Ahrix", "A", (48, 48, 50), "Unite"),
           ("Jim Yosef", "J", (38, 70, 83), "Chasing Dreams"), ("Aexcit", "Ae", (205, 120, 70), "Ain't You"),
           ("5 Seconds of Summer", "5S", (70, 45, 110), "Youngblood (Deluxe)"), ("Dirtyphonics", "D", (140, 128, 90), "You Want Me"),
           ("12 Feet Deep", "12", (60, 110, 150), "Bubbles & Boathouses"), ("A5ura", "A5", (150, 120, 170), "Say Goodbye")]
short = lambda s, n: s if len(s) <= n else s[:n - 1] + "…"

# ---------- screens ----------
def library_songs_content(img, d, pressed=None):
    T(d, (20, 66), "Your music,", 34, 700, P["ink"]); T(d, (20, 108), "nearby.", 34, 700, P["ink"])
    T(d, (20, 156), "962 tracks from your desktop", 16, 400, P["muted"])
    pills(d, 198, 0)
    for i, (t, a, dur, k) in enumerate(SONGS):
        song_row(img, d, 250 + i * 72, t, a, dur, k, current=i == 1, pressed=i == pressed)

def s01():
    img, d = frame("bg", "Library · Songs"); library_songs_content(img, d); mini_nav(img, d, 0); return img
def folded(d, sel):
    T(d, (20, 56), "Your music", 22, 700, P["ink"]); pills(d, 96, sel)
def s02():
    img, d = frame("bg", "Library · Albums"); folded(d, 1)
    size = (W - 56) / 2
    for i, (t, a) in enumerate(ALBUMS):
        x, y = 20 + (i % 2) * (size + 16), 148 + (i // 2) * 232
        art(d, t, x, y, size, 14); T(d, (x + 2, y + size + 8), t, 15, 600, P["ink"]); T(d, (x + 2, y + size + 29), a, 13, 400, P["muted"])
    mini_nav(img, d, 0); return img
def s03():
    img, d = frame("bg", "Library · Artists"); folded(d, 2)
    for i, (n, ini, col, alb) in enumerate(ARTISTS):
        y = 148 + i * 68
        avatar(d, 44, y + 34, 24, ini, col); T(d, (84, y + 15), n, 16, 600, P["ink"]); T(d, (84, y + 39), alb, 13, 400, P["muted"])
    mini_nav(img, d, 0); return img
def s04():
    img, d = frame("bg", "Library · Playlists"); folded(d, 3)
    for i, (n, c) in enumerate([("Music", "962 songs"), ("bad", "4 songs")]):     # empty playlists are hidden
        y = 148 + i * 76
        rr(d, 20, y + 10, 56, 56, 14, fill=P["tonal"]); icon(img, "queue", 48, y + 38, 26, P["ink"])
        T(d, (92, y + 18), n, 16, 600, P["ink"]); T(d, (92, y + 42), c, 13, 400, P["muted"])
    mini_nav(img, d, 0); return img
def s05():
    img, d = frame("bg", "Album"); back(img, d)
    art(d, "After Hours", 20, 112, 150, 16)
    T(d, (186, 124), "After Hours", 26, 700, P["ink"]); T(d, (186, 160), "The Weeknd", 16, 400, P["muted"])
    T(d, (186, 184), "14 songs, 56 minutes", 13, 400, P["muted"])
    bw = (W - 52) / 2
    button(img, d, 20, 282, bw, "Play", "play", True); button(img, d, 32 + bw, 282, bw, "Shuffle", "shuffle", False)
    for i, (t, dur) in enumerate([("Alone Again", "4:10"), ("Too Late", "3:59"), ("Hardest to Love", "3:31"), ("Scared to Live", "3:11"),
                                  ("Snowchild", "4:07"), ("Escape from LA", "5:56"), ("Heartless", "3:18"), ("Faith", "4:43")]):
        y = 350 + i * 52
        T(d, (32, y + 16), str(i + 1), 15, 500, P["muted"], "ma"); T(d, (60, y + 15), t, 16, 600, P["ink"])
        T(d, (W - 20, y + 16), dur, 13, 500, P["muted"], "ra")
    mini_nav(img, d, 0); return img
def s06():
    img, d = frame("bg", "Artist"); back(img, d)
    avatar(d, 64, 156, 44, "W", (120, 45, 40))
    T(d, (124, 130), "The Weeknd", 28, 700, P["ink"]); T(d, (124, 168), "1 album, 14 songs", 14, 400, P["muted"])
    bw = (W - 52) / 2
    button(img, d, 20, 226, bw, "Play", "play", True); button(img, d, 32 + bw, 226, bw, "Shuffle", "shuffle", False)
    T(d, (20, 300), "Albums", 17, 700, P["ink"])
    art(d, "After Hours", 20, 332, 150, 14); T(d, (22, 490), "After Hours", 15, 600, P["ink"]); T(d, (22, 511), "Album", 13, 400, P["muted"])
    T(d, (20, 548), "Songs", 17, 700, P["ink"])
    for i, (t, dur) in enumerate([("Blinding Lights", "3:22"), ("Save Your Tears", "3:35")]):
        song_row(img, d, 572 + i * 72, t, "After Hours", dur, "After Hours")
    mini_nav(img, d, 0); return img
def search_bar_top(img, d):
    rr(d, 16, 50, W - 32, 56, 28, fill=P["sch"])
    icon_text(img, d, 34, 78, "search", 22, "Search songs, artists, albums", 16, 400, P["muted"], gap=16)
def s07():
    img, d = frame("bg", "Search"); search_bar_top(img, d)
    T(d, (20, 134), "Artists", 17, 700, P["ink"])
    for i, (n, ini, col, _) in enumerate(ARTISTS[:4]):
        cx = 58 + i * 98; avatar(d, cx, 202, 34, ini, col); T(d, (cx, 246), n, 13, 500, P["ink"], "ma")
    T(d, (20, 292), "Albums", 17, 700, P["ink"])
    for i, (t, a) in enumerate(ALBUMS):
        x, y = 20 + (i % 3) * 126, 324 + (i // 3) * 176
        art(d, t, x, y, 114, 12); T(d, (x + 1, y + 122), short(t, 14), 13, 600, P["ink"]); T(d, (x + 1, y + 140), short(a, 17), 12, 400, P["muted"])
    mini_nav(img, d, 1); return img
def search_header(img, d, query):
    icon(img, "back", 30, 78, 22, P["ink"])
    if query:
        text_mid(d, 76, 78, query, 16, 400, P["ink"]); rr(d, 76 + tw(query, 16, 400) + 2, 64, 2, 28, 1, fill=P["acc"])
        icon(img, "close", W - 34, 78, 22, P["muted"])
    else:
        rr(d, 70, 64, 2, 28, 1, fill=P["acc"]); text_mid(d, 76, 78, "Search songs, artists, albums", 16, 400, P["muted"])
    d.rectangle([0, dp(114), 1080, dp(114) + max(1, dp(1))], fill=P["div"])
def s08():
    img, d = frame("sch", "Search · tapped"); search_header(img, d, "")
    T(d, (20, 132), "From your library", 14, 600, P["muted"])
    for i, (kind, title, sub, ini, col) in enumerate([("a", "The Weeknd", "Artist", "W", (120, 45, 40)), ("l", "After Hours", "Album · The Weeknd", 0, 0),
                                                      ("a", "Ahrix", "Artist", "A", (48, 48, 50)), ("l", "Youngblood (Deluxe)", "Album · 5 Seconds of Summer", 0, 0),
                                                      ("a", "Jim Yosef", "Artist", "J", (38, 70, 83))]):
        y = 158 + i * 64
        if kind == "a": avatar(d, 40, y + 26, 20, ini, col)
        else: art(d, title, 20, y + 6, 40, 6)
        T(d, (76, y + 8), title, 16, 500, P["ink"]); T(d, (76, y + 30), sub, 13, 400, P["muted"])
    keyboard(img, d, []); return img
def s09():
    img, d = frame("sch", "Search · typing"); search_header(img, d, "you")
    T(d, (20, 132), "Songs", 15, 700, P["ink"])
    for i, (t, a, dur, k) in enumerate([("You Want Me", "Dirtyphonics; Circadian", "4:45", "You Want Me"), ("Ain't You", "Aexcit", "3:13", "Ain't You"),
                                        ("If Walls Could Talk", "5 Seconds of Summer", "3:02", "Youngblood (Deluxe)")]):
        y = 156 + i * 66
        art(d, k, 20, y + 8, 48, 8); T(d, (82, y + 12), t, 16, 600, P["ink"]); T(d, (82, y + 35), a, 13, 400, P["muted"])
        T(d, (W - 20, y + 24), dur, 13, 500, P["muted"], "ra")
    T(d, (20, 368), "Albums", 15, 700, P["ink"])
    art(d, "Youngblood (Deluxe)", 20, 400, 48, 8)
    T(d, (82, 404), "Youngblood (Deluxe)", 16, 600, P["ink"]); T(d, (82, 427), "Album · 5 Seconds of Summer", 13, 400, P["muted"])
    keyboard(img, d, ["you", "your", "young"]); return img
def s10():
    img, d = frame("bg", ""); library_songs_content(img, d); mini_nav(img, d, 0)
    img, d = scrim(img, "Now Playing · opening", 70)
    rr(d, 0, 300, W, 640, 28, fill=P["bg"]); rr(d, W / 2 - 20, 312, 40, 5, 2.5, fill=P["outline"])
    art(d, "Unite", (W - 220) / 2, 340, 220, 20)
    T(d, (24, 582), "Unite", 26, 700, P["ink"]); T(d, (24, 618), "Ahrix", 16, 400, P["muted"])
    gesture(d); return img
def s11():
    img, d = frame("bg", "Now Playing")
    rr(d, W / 2 - 20, 40, 40, 5, 2.5, fill=P["outline"])
    d.ellipse([dp(20), dp(54), dp(60), dp(94)], fill=P["sch"]); icon(img, "down", 40, 74, 22, P["ink"])
    size = W - 48; art(d, "Unite", 24, 112, size, 24)
    ty = 112 + size + 20
    T(d, (24, ty), "Unite", 28, 700, P["ink"]); T(d, (24, ty + 38), "Ahrix", 17, 400, P["muted"])
    sy, x0, x1 = ty + 93, 24, W - 24; tx = x0 + (x1 - x0) * 0.42
    rr(d, x0, sy - 6, tx - 6 - x0, 12, 6, fill=P["acc"]); rr(d, tx + 6, sy - 6, x1 - tx - 6, 12, 6, fill=P["off"])
    rr(d, tx - 2, sy - 20, 4, 40, 2, fill=P["acc"])
    T(d, (24, ty + 120), "1:17", 13, 500, P["muted"]); T(d, (W - 24, ty + 120), "3:03", 13, 500, P["muted"], "ra")
    cy = ty + 190
    icon(img, "shuffle", 62, cy, 24, P["muted"]); icon(img, "previous", 132, cy, 28, P["ink"])
    d.ellipse([dp(W / 2 - 36), dp(cy - 36), dp(W / 2 + 36), dp(cy + 36)], fill=P["acc"]); icon(img, "pause", W / 2, cy, 30, P["on_acc"])
    icon(img, "next", W - 132, cy, 28, P["ink"]); icon(img, "repeat", W - 62, cy, 24, P["muted"])
    fy = cy + 90; fx = 52 + (W - 104) * 0.56
    icon(img, "vol_low", 30, fy, 20, P["muted"])
    rr(d, 52, fy - 6, fx - 58, 12, 6, fill=P["acc"]); rr(d, fx + 6, fy - 6, W - 52 - fx - 6, 12, 6, fill=P["off"])
    rr(d, fx - 2, fy - 20, 4, 40, 2, fill=P["acc"]); icon(img, "vol_high", W - 30, fy, 22, P["muted"])
    by = fy + 58
    icon_text(img, d, 26, by, "lyrics", 22, "Lyrics", 15, 600, P["acc"])
    icon_text(img, d, W - 24 - icon_text_width("queue", 22, "Queue", 15, 600), by, "queue", 22, "Queue", 15, 600, P["acc"])
    gesture(d); return img
def s12():
    img, d = frame("bg", "Lyrics"); back(img, d); large_title(d, "Unite", "Ahrix")
    for i, line in enumerate(["These lines stand in for", "lyrics stored in Tauon.", "", "Set large enough to read", "from arm's length, and",
                              "left-aligned, so each new", "line starts where the eye", "already is."]):
        if line: T(d, (20, 262 + i * 42), line, 24, 500, P["ink"])
    T(d, (20, 620), "Stored lyrics from Tauon.", 13, 400, P["muted"]); T(d, (20, 640), "Not time-synchronised.", 13, 400, P["muted"])
    gesture(d); return img
def s13():
    img, d = frame("bg", "Queue"); back(img, d); large_title(d, "Queue")
    y0 = 236
    T(d, (24, y0), "Now playing", 14, 600, P["muted"])
    rr(d, 16, y0 + 22, W - 32, 72, 18, fill=P["sel"]); art(d, "Unite", 28, y0 + 32, 52)
    T(d, (94, y0 + 40), "Unite", 16, 600, P["acc"]); T(d, (94, y0 + 64), "Ahrix", 13, 400, P["muted"])
    icon(img, "music", W - 40, y0 + 58, 22, P["acc"])
    T(d, (24, y0 + 118), "Next up", 14, 600, P["muted"]); T(d, (W - 24, y0 + 118), "10 songs, 34 minutes", 13, 400, P["muted"], "ra")
    nxt = [("Blinding Lights", "The Weeknd", "After Hours"), ("Chasing Dreams", "Jim Yosef; Valentina Franco", "Chasing Dreams"),
           ("Hope", "Shiv; Dylan Smith", "Hope"), ("You Want Me", "Dirtyphonics; Circadian", "You Want Me"),
           ("If Walls Could Talk", "5 Seconds of Summer", "Youngblood (Deluxe)"), ("Bubbles & Boathouses", "12 Feet Deep", "Bubbles & Boathouses"),
           ("Say Goodbye", "A5ura; Jade Key", "Say Goodbye")]
    for i, (t, a, k) in enumerate(nxt):
        y = y0 + 142 + i * 70; off = 0
        if i == 2:                                # mid-swipe: the row slides left over the delete action
            off = -56
            rr(d, 12, y + 4, W - 24, 62, 16, fill=P["err"]); rr(d, 12 + off, y + 4, W - 24, 62, 16, fill=P["bg"])
            icon(img, "delete", W - 44, y + 35, 22, P["on_err"])
        art(d, k, 24 + off, y + 10, 48); T(d, (86 + off, y + 15), t, 16, 600, P["ink"]); T(d, (86 + off, y + 38), a, 13, 400, P["muted"])
        icon(img, "drag", W - 40 + off, y + 35, 22, P["muted"])
    gesture(d); return img
def s14():
    img, d = frame("bg", ""); library_songs_content(img, d, pressed=0); mini_nav(img, d, 0)
    img, d = scrim(img, "Song actions")
    top = H - 392
    rr(d, 0, top, W, 420, 28, fill=P["sheet"]); rr(d, W / 2 - 16, top + 14, 32, 4, 2, fill=P["outline"])
    art(d, "After Hours", 20, top + 42, 56, 10)
    T(d, (90, top + 44), "Blinding Lights", 18, 600, P["ink"]); T(d, (90, top + 70), "The Weeknd", 14, 400, P["muted"])
    T(d, (90, top + 90), "After Hours", 13, 400, P["muted"])
    d.rectangle([dp(20), dp(top + 120), dp(W - 20), dp(top + 120) + max(1, dp(1))], fill=P["div"])
    for i, (ic, lab) in enumerate([("play_next", "Play next"), ("add_queue", "Add to queue"), ("album", "Go to album"), ("artist", "Go to artist")]):
        y = top + 132 + i * 56; icon_text(img, d, 28, y + 28, ic, 24, lab, 16, 500, P["ink"], gap=24)
    gesture(d); return img
def settings_screen(label):
    img, d = frame("ground", label); large_title(d, "Settings")
    y0 = 236
    T(d, (24, y0), "Connection", 14, 600, P["muted"])
    ys = group(img, 16, y0 + 24, W - 32, [72, 72, 72])
    two(d, 36, ys[0], "Tauon desktop", "192.168.100.69:7814")
    rr(d, W - 121, ys[0] + 22, 86, 28, 14, fill=P["sel"]); T(d, (W - 78, ys[0] + 28), "Connected", 12, 600, P["ink"], "ma")
    two(d, 36, ys[1], "Refresh library", "962 tracks loaded")
    two(d, 36, ys[2], "Disconnect", "Stops playback and forgets this server", P["err"])
    y = ys[2] + 96
    T(d, (24, y), "Appearance", 14, 600, P["muted"])
    ys = group(img, 16, y + 24, W - 32, [72, 72, 72])
    for yy, (t, sub, on) in zip(ys[:2], [("Material You", "Colours from your wallpaper", True), ("Muon", "The app's own palette", False)]):
        two(d, 36, yy, t, sub); cx = W - 46
        d.ellipse([dp(cx - 10), dp(yy + 26), dp(cx + 10), dp(yy + 46)], outline=P["acc"] if on else P["outline"], width=dp(2))
        if on: d.ellipse([dp(cx - 5), dp(yy + 31), dp(cx + 5), dp(yy + 41)], fill=P["acc"])
    two(d, 36, ys[2], "Pure black", "Black backgrounds in dark mode")
    rr(d, W - 86, ys[2] + 20, 52, 32, 16, outline=P["outline"], width=2)
    d.ellipse([dp(W - 78), dp(ys[2] + 28), dp(W - 62), dp(ys[2] + 44)], fill=P["outline"])
    mini_nav(img, d, 2, "ground"); return img
def s15(): return settings_screen("Settings")
def s16():
    img, d = scrim(settings_screen(""), "Disconnect asks first")
    x, w, y, h = 24, W - 48, 300, 230
    rr(d, x, y, w, h, 28, fill=P["dialog"])
    T(d, (x + 24, y + 24), "Disconnect from Tauon?", 24, 400, P["ink"])
    for i, line in enumerate(["Playback stops, and Muon forgets", "192.168.100.69:7814. You'll need the", "address or a scan to reconnect."]):
        T(d, (x + 24, y + 72 + i * 22), line, 14, 400, P["muted"])
    T(d, (x + w - 24, y + h - 44), "Disconnect", 14, 600, P["err"], "ra")
    T(d, (x + w - 24 - tw("Disconnect", 14, 600) - 32, y + h - 44), "Cancel", 14, 600, P["acc"], "ra")
    return img
def s17():
    img, d = frame("ground", "Connect · first run")
    T(d, (20, 76), "Bring your", 34, 700, P["ink"]); T(d, (20, 118), "library along.", 34, 700, P["ink"])
    T(d, (20, 168), "Play your Tauon library on this phone,", 16, 400, P["muted"]); T(d, (20, 190), "over your own network.", 16, 400, P["muted"])
    group(img, 16, 240, W - 32, [190])
    T(d, (36, 258), "Server address", 13, 500, P["muted"])
    rr(d, 36, 280, W - 72, 56, 14, outline=P["outline"], width=1.5); T(d, (54, 298), "192.168.1.10:7814", 16, 400, P["outline"])
    rr(d, 36, 350, W - 72, 52, 26, fill=P["acc"]); text_mid(d, W / 2, 376, "Connect", 16, 500, P["on_acc"], "m")
    T(d, (24, 460), "On this network", 14, 600, P["muted"])
    ys = group(img, 16, 484, W - 32, [72, 56])
    two(d, 36, ys[0], "Tauon", "192.168.100.69:7814"); T(d, (W - 36, ys[0] + 26), "Use", 15, 600, P["acc"], "ra")
    T(d, (36, ys[1] + 18), "Scan again", 15, 600, P["acc"])
    T(d, (24, 650), "Tauon's API has no login or encryption.", 13, 400, P["muted"])
    T(d, (24, 668), "Only connect on a network you trust.", 13, 400, P["muted"])
    gesture(d); return img

SCREENS = [("01-library-songs", s01), ("02-library-albums", s02), ("03-library-artists", s03), ("04-library-playlists", s04),
           ("05-album", s05), ("06-artist", s06), ("07-search", s07), ("08-search-tapped", s08), ("09-search-typing", s09),
           ("10-now-playing-opening", s10), ("11-now-playing", s11), ("12-lyrics", s12), ("13-queue", s13),
           ("14-song-actions", s14), ("15-settings", s15), ("16-disconnect", s16), ("17-connect", s17)]

if __name__ == "__main__":
    for theme in ("light", "dark"):
        P = THEMES[theme]
        os.makedirs(os.path.join(OUT, theme), exist_ok=True)
        thumbs = []
        for name, fn in SCREENS:
            im = fn(); assert im.size == (1080, 2400), name
            im.save(os.path.join(OUT, theme, name + ".png"), optimize=True)
            thumbs.append((name, im.resize((270, 600), Image.LANCZOS)))
        cols, pad, cap = 6, 20, 34
        sheet = Image.new("RGB", (cols * 270 + (cols + 1) * pad, 3 * (600 + cap) + 4 * pad), (60, 52, 50) if theme == "dark" else (205, 198, 195))
        sd = ImageDraw.Draw(sheet); lf = fr(5.2, 600)
        for i, (name, th) in enumerate(thumbs):
            x, y = pad + (i % cols) * (270 + pad), pad + (i // cols) * (600 + cap + pad)
            sheet.paste(th, (x, y)); sd.text((x + 4, y + 606), name, font=lf, fill=(240, 235, 233) if theme == "dark" else (35, 25, 24))
        sheet.save(os.path.join(OUT, f"overview-{theme}.png"), optimize=True)
        print(f"{theme}: {len(SCREENS)} screens and overview")
