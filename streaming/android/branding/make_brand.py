"""Generates the Stream launcher icon (adaptive vector) and TV banner (PNG) from one geometry.

    python3 branding/make_brand.py app/src/main/res <preview directory>

Needs Pillow and Inter Display Bold (SIL Open Font License) at FONT; the banner's wordmark is rendered with it.
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = sys.argv[1]
PREVIEW = sys.argv[2]
START, END = (0x6D, 0x28, 0xD9), (0xF4, 0x3F, 0x5E)   # violet -> rose, top-left to bottom-right

# Glyph in the 108-unit adaptive icon grid (safe zone: radius 33 around 54,54).
TRI = [(30.0, 34.0), (30.0, 74.0), (64.0, 54.0)]
CORNER = 6.0
ARC_CENTER = (53.0, 54.0)
ARCS = [(19.0, 1.0), (27.0, 0.55)]          # radius, opacity
ARC_SPAN = 38.0                              # degrees either side of horizontal
STROKE = 5.0

def rounded_polygon(points, r):
    """Path commands (move, quad...) for a polygon with rounded corners."""
    out = []
    n = len(points)
    for i in range(n):
        p0, p1, p2 = points[i - 1], points[i], points[(i + 1) % n]
        def toward(a, b, d):
            dx, dy = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dy)
            return (a[0] + dx / L * d, a[1] + dy / L * d)
        a = toward(p1, p0, r); b = toward(p1, p2, r)
        out.append((a, p1, b))
    return out

def tri_path():
    corners = rounded_polygon(TRI, CORNER)
    s = "M%.2f,%.2f " % corners[0][2]
    for a, c, b in corners[1:] + corners[:1]:
        s += "L%.2f,%.2f Q%.2f,%.2f %.2f,%.2f " % (a[0], a[1], c[0], c[1], b[0], b[1])
    return s + "Z"

def tri_points(scale, dx, dy, steps=12):
    pts = []
    corners = rounded_polygon(TRI, CORNER)
    for a, c, b in corners:
        for t in [i / steps for i in range(steps + 1)]:
            x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0]
            y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]
            pts.append((x * scale + dx, y * scale + dy))
    return pts

def arc_ends(r):
    a = math.radians(ARC_SPAN)
    cx, cy = ARC_CENTER
    return (cx + r * math.cos(a), cy - r * math.sin(a)), (cx + r * math.cos(a), cy + r * math.sin(a))

def hexc(c, alpha=255): return "#%02X%02X%02X%02X" % (alpha, c[0], c[1], c[2])

# Vector resources.
os.makedirs(f"{RES}/drawable", exist_ok=True); os.makedirs(f"{RES}/mipmap-anydpi", exist_ok=True)
with open(f"{RES}/drawable/ic_launcher_background.xml", "w") as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Stream's icon field: violet to rose, top-left to bottom-right. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"
                android:startColor="{hexc(START)}" android:endColor="{hexc(END)}" />
        </aapt:attr>
    </path>
</vector>
''')
arcs = ""
for r, opacity in ARCS:
    (x1, y1), (x2, y2) = arc_ends(r)
    arcs += f'''    <path android:pathData="M{x1:.2f},{y1:.2f} A{r:.0f},{r:.0f} 0 0,1 {x2:.2f},{y2:.2f}"
        android:strokeColor="#FFFFFFFF" android:strokeAlpha="{opacity}" android:strokeWidth="{STROKE}" android:strokeLineCap="round" />
'''
with open(f"{RES}/drawable/ic_launcher_foreground.xml", "w") as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Stream's mark: a rounded play symbol sending out two waves. Inside the adaptive icon's 66 dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="{tri_path()}" />
{arcs}</vector>
''')
for name in ("ic_launcher", "ic_launcher_round"):
    with open(f"{RES}/mipmap-anydpi/{name}.xml", "w") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
''')

def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(1, w - 1) + y / max(1, h - 1)) / 2
            px[x, y] = tuple(round(START[i] + (END[i] - START[i]) * t) for i in range(3))
    return img

def draw_mark(img, scale, dx, dy):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.polygon(tri_points(scale, dx, dy), fill=(255, 255, 255, 255))
    for r, opacity in ARCS:
        cx, cy = ARC_CENTER
        # PIL strokes inside the box; widen it by half a stroke so the line is centred on the radius.
        R = r + STROKE / 2
        box = [(cx - R) * scale + dx, (cy - R) * scale + dy, (cx + R) * scale + dx, (cy + R) * scale + dy]
        a = round(255 * opacity)
        arc_layer = Image.new("RGBA", img.size, (0, 0, 0, 0)); ad = ImageDraw.Draw(arc_layer)
        ad.arc(box, -ARC_SPAN, ARC_SPAN, fill=(255, 255, 255, 255), width=round(STROKE * scale))
        for (ex, ey) in arc_ends(r):
            rr = STROKE * scale / 2
            ad.ellipse([ex * scale + dx - rr, ey * scale + dy - rr, ex * scale + dx + rr, ey * scale + dy + rr], fill=(255, 255, 255, 255))
        arc_layer.putalpha(arc_layer.getchannel("A").point(lambda v: v * a // 255))
        layer = Image.alpha_composite(layer, arc_layer)
    # A soft shadow under the mark gives it a little depth on the bright field.
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    shadow.putalpha(layer.getchannel("A").point(lambda v: v * 70 // 255))
    shadow = shadow.filter(ImageFilter.GaussianBlur(scale * 2.2))
    base = img.convert("RGBA")
    base.alpha_composite(shadow, (0, round(scale * 1.2)))
    base.alpha_composite(layer)
    return base

SS = 4  # supersampling
# Icon preview (what launchers show after masking): 432 px, rounded square.
size = 432 * SS
icon = draw_mark(gradient(size, size), size / 108, 0, 0)
mask = Image.new("L", (size, size), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=255)
icon.putalpha(mask)
icon.resize((432, 432), Image.LANCZOS).save(f"{PREVIEW}/icon-preview.png")

# TV banner: 320x180 dp (xhdpi 320x180 px). Mark on the left, wordmark on the right.
font_path = os.environ.get("FONT", "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf")
for folder, w in (("drawable-mdpi", 160), ("drawable-hdpi", 240), ("drawable-xhdpi", 320), ("drawable-xxhdpi", 480), ("drawable-xxxhdpi", 640)):
    W, H = w * SS, w * 9 // 16 * SS
    banner = gradient(W, H)
    mark_scale = H * 0.62 / 108
    mark_left = W * 0.075
    banner = draw_mark(banner, mark_scale, mark_left - 22 * mark_scale, (H - 108 * mark_scale) / 2)
    d = ImageDraw.Draw(banner)
    font = ImageFont.truetype(font_path, round(H * 0.30))
    text = "Stream"
    box = d.textbbox((0, 0), text, font=font)
    tx = mark_left + 62 * mark_scale + W * 0.035
    ty = (H - (box[3] - box[1])) / 2 - box[1]
    glow = Image.new("RGBA", banner.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).text((tx, ty + H * 0.012), text, font=font, fill=(30, 0, 40, 90))
    banner.alpha_composite(glow.filter(ImageFilter.GaussianBlur(H * 0.02)))
    d = ImageDraw.Draw(banner)
    d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))
    out = banner.convert("RGB").resize((w, w * 9 // 16), Image.LANCZOS)
    os.makedirs(f"{RES}/{folder}", exist_ok=True)
    out.save(f"{RES}/{folder}/tv_banner.png", optimize=True)
    if w == 640: out.save(f"{PREVIEW}/banner-preview.png")
print("ok", tri_path())
