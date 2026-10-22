"""Generates the Stream launcher icon (adaptive vector) and TV banner (PNG) from one geometry.

    python3 branding/make_brand.py app/src/main/res <preview directory>

The look follows Apple's glass icons: a graphite field lit softly from above and one glass play mark, translucent,
lighter at its edges, with a bright rim and a soft shadow. Needs Pillow, and Inter Display SemiBold (SIL Open Font
License) at FONT for the banner's wordmark.
"""
import math, os, sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

RES = sys.argv[1]
PREVIEW = sys.argv[2]

# Graphite field, lighter at the top, with a cool light behind the mark.
TOP, BOTTOM = (0x33, 0x35, 0x3C), (0x0B, 0x0B, 0x0E)
GLOW = (0x9F, 0xB6, 0xDC)
GLOW_ALPHA = 0.30
GLOW_CENTER, GLOW_RADIUS = (54.0, 40.0), 58.0

# Play mark in the 108-unit adaptive icon grid (safe zone: radius 33 around 54,54). The box sits a little right of
# centre so the triangle reads as centred.
TRI = [(40.5, 35.5), (40.5, 72.5), (73.0, 54.0)]
CORNER = 6.5
BODY_TOP, BODY_BOTTOM = 0.70, 0.36      # the glass: white, clearer towards the bottom
RIM = 2.0                               # specular rim, half of it inside the mark
RIM_STOPS = [(0, 1.0), (0.42, 0.3), (0.78, 0.12), (1, 0.6)]   # top-left to bottom-right
SHADOW = 0.45

def rounded_polygon(points, r):
    """Corners as (start, control, end) of a quadratic curve, for a polygon with rounded corners."""
    out = []
    n = len(points)
    for i in range(n):
        p0, p1, p2 = points[i - 1], points[i], points[(i + 1) % n]
        def toward(a, b, d):
            dx, dy = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dy)
            return (a[0] + dx / L * d, a[1] + dy / L * d)
        out.append((toward(p1, p0, r), p1, toward(p1, p2, r)))
    return out

def tri_path():
    corners = rounded_polygon(TRI, CORNER)
    s = "M%.2f,%.2f " % corners[0][2]
    for a, c, b in corners[1:] + corners[:1]:
        s += "L%.2f,%.2f Q%.2f,%.2f %.2f,%.2f " % (a[0], a[1], c[0], c[1], b[0], b[1])
    return s + "Z"

def tri_points(scale, dx, dy, steps=16):
    pts = []
    for a, c, b in rounded_polygon(TRI, CORNER):
        for t in [i / steps for i in range(steps + 1)]:
            x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0]
            y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]
            pts.append((x * scale + dx, y * scale + dy))
    return pts

def argb(c, alpha=1.0): return "#%02X%02X%02X%02X" % (round(alpha * 255), c[0], c[1], c[2])
def white(alpha): return argb((255, 255, 255), alpha)

TOP_Y, BOTTOM_Y = min(p[1] for p in TRI), max(p[1] for p in TRI)
LEFT_X, RIGHT_X = min(p[0] for p in TRI), max(p[0] for p in TRI)
GLOW_STOPS = [(0, 1.0), (0.5, 0.35), (1, 0)]

# Vector resources.
os.makedirs(f"{RES}/drawable", exist_ok=True); os.makedirs(f"{RES}/mipmap-anydpi", exist_ok=True)
glow_items = "".join(f'\n                <item android:offset="{o}" android:color="{argb(GLOW, GLOW_ALPHA * a)}" />' for o, a in GLOW_STOPS)
with open(f"{RES}/drawable/ic_launcher_background.xml", "w") as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Stream's icon field: graphite, lighter at the top, with a cool light behind the mark. Made by branding/make_brand.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="108"
                android:startColor="{argb(TOP)}" android:endColor="{argb(BOTTOM)}" />
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{GLOW_CENTER[0]}" android:centerY="{GLOW_CENTER[1]}"
                android:gradientRadius="{GLOW_RADIUS}">{glow_items}
            </gradient>
        </aapt:attr>
    </path>
</vector>
''')
cx, cy = (LEFT_X + RIGHT_X) / 2, (TOP_Y + BOTTOM_Y) / 2
rim_items = "".join(f'\n                    <item android:offset="{o}" android:color="{white(a)}" />' for o, a in RIM_STOPS)
with open(f"{RES}/drawable/ic_launcher_foreground.xml", "w") as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Stream's mark: a glass play symbol with a bright rim and a soft shadow, inside the 66 dp safe zone. Made by branding/make_brand.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{cx - 3:.1f}" android:centerY="{cy + 5:.1f}" android:gradientRadius="30">
                <item android:offset="0" android:color="{argb((0, 0, 0), SHADOW)}" />
                <item android:offset="0.6" android:color="{argb((0, 0, 0), SHADOW * 0.35)}" />
                <item android:offset="1" android:color="#00000000" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="{tri_path()}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="{TOP_Y}" android:endX="0" android:endY="{BOTTOM_Y}"
                android:startColor="{white(BODY_TOP)}" android:endColor="{white(BODY_BOTTOM)}" />
        </aapt:attr>
    </path>
    <group>
        <clip-path android:pathData="{tri_path()}" />
        <path android:pathData="{tri_path()}" android:strokeWidth="{RIM}">
            <aapt:attr name="android:strokeColor">
                <gradient android:type="linear" android:startX="{LEFT_X}" android:startY="{TOP_Y}" android:endX="{RIGHT_X}"
                    android:endY="{BOTTOM_Y}">{rim_items}
                </gradient>
            </aapt:attr>
        </path>
    </group>
</vector>
''')
with open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w") as f:
    f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Stream's mark in one colour, for themed launcher icons. Made by branding/make_brand.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="{tri_path()}" />
</vector>
''')
for name in ("ic_launcher", "ic_launcher_round"):
    with open(f"{RES}/mipmap-anydpi/{name}.xml", "w") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')

# Bitmaps: the banner and an icon preview, drawn the same way with real blur where the vector uses gradients.
def ramp(stops):
    """A 256-entry lookup from position (0-255) to opacity (0-255) through (position, opacity) stops."""
    out = []
    for i in range(256):
        u = i / 255
        for (u0, a0), (u1, a1) in zip(stops, stops[1:]):
            if u <= u1:
                out.append(round(255 * (a0 + (a1 - a0) * (u - u0) / (u1 - u0)))); break
        else: out.append(round(255 * stops[-1][1]))
    return out

def sampled(W, H, f):
    """A smooth greyscale image from f(x, y) -> 0..1, sampled coarsely and scaled up."""
    n = 160
    g = Image.new("L", (n, n)); p = g.load()
    for j in range(n):
        for i in range(n):
            p[i, j] = max(0, min(255, round(255 * f(i * (W - 1) / (n - 1), j * (H - 1) / (n - 1)))))
    return g.resize((W, H), Image.BILINEAR)

def field(W, H, glow_center, glow_radius):
    top = Image.new("RGB", (W, H), TOP); bottom = Image.new("RGB", (W, H), BOTTOM)
    img = Image.composite(bottom, top, Image.linear_gradient("L").resize((W, H))).convert("RGBA")
    gx, gy = glow_center
    distance = sampled(W, H, lambda x, y: math.hypot(x - gx, y - gy) / glow_radius)
    glow = Image.new("RGBA", (W, H), GLOW)
    glow.putalpha(distance.point([round(v * GLOW_ALPHA) for v in ramp(GLOW_STOPS)]))
    return Image.alpha_composite(img, glow)

def glass_mark(img, scale, dx, dy):
    """Composites the glass play mark onto img: shadow, translucent body, lighter edges and the specular rim."""
    W, H = img.size
    shape = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shape).polygon(tri_points(scale, dx, dy), fill=255)
    base = img.copy()
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow.putalpha(shape.point(lambda v: round(v * SHADOW)).filter(ImageFilter.GaussianBlur(scale * 3.4)))
    base.alpha_composite(shadow, (0, round(scale * 2.6)))
    t, b = TOP_Y * scale + dy, BOTTOM_Y * scale + dy
    body = sampled(W, H, lambda x, y: BODY_TOP + (BODY_BOTTOM - BODY_TOP) * min(1, max(0, (y - t) / (b - t))))
    # Glass is lighter towards its edges: a soft inner band, then the rim.
    inner = ImageChops.subtract(shape, shape.filter(ImageFilter.GaussianBlur(scale * 2.6))).point(lambda v: min(255, v * 2))
    edge = inner.point(lambda v: round(v * 0.32))
    light = ImageChops.add(ImageChops.multiply(body, shape), edge)
    glass = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    glass.putalpha(light)
    base.alpha_composite(glass)
    l, r = LEFT_X * scale + dx, RIGHT_X * scale + dx
    rim = ImageChops.subtract(shape, shape.filter(ImageFilter.MinFilter(max(1, round(scale * RIM / 2)) * 2 + 1)))
    vx, vy = r - l, b - t
    along = sampled(W, H, lambda x, y: ((x - l) * vx + (y - t) * vy) / (vx * vx + vy * vy)).point(ramp(RIM_STOPS))
    spec = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    spec.putalpha(ImageChops.multiply(rim, along))
    base.alpha_composite(spec)
    return base

SS = 4  # supersampling
# Icon preview: launchers show the middle 72 of the 108 units, masked.
size = 432 * SS
s = size / 72
full = glass_mark(field(round(108 * s), round(108 * s), (GLOW_CENTER[0] * s, GLOW_CENTER[1] * s), GLOW_RADIUS * s), s, 0, 0)
icon = full.crop((round(18 * s), round(18 * s), round(18 * s) + size, round(18 * s) + size))
mask = Image.new("L", (size, size), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=255)
icon.putalpha(mask)
os.makedirs(PREVIEW, exist_ok=True)
icon.resize((432, 432), Image.LANCZOS).save(f"{PREVIEW}/icon-preview.png")

# TV banner: 320x180 dp. The mark and the wordmark centred as one group.
font_path = os.environ.get("FONT", "/usr/share/fonts/opentype/inter/InterDisplay-SemiBold.otf")
for folder, w in (("drawable-mdpi", 160), ("drawable-hdpi", 240), ("drawable-xhdpi", 320), ("drawable-xxhdpi", 480), ("drawable-xxxhdpi", 640)):
    W, H = w * SS, w * 9 // 16 * SS
    font = ImageFont.truetype(font_path, round(H * 0.24))
    text = "Stream"
    box = ImageDraw.Draw(Image.new("L", (1, 1))).textbbox((0, 0), text, font=font)
    mark_scale = H * 0.30 / (BOTTOM_Y - TOP_Y)
    mark_w = (RIGHT_X - LEFT_X) * mark_scale
    gap = H * 0.085
    left = (W - (mark_w + gap + box[2] - box[0])) / 2
    banner = field(W, H, (left + mark_w / 2, H * 0.38), H * 0.95)
    banner = glass_mark(banner, mark_scale, left - LEFT_X * mark_scale, H / 2 - (TOP_Y + BOTTOM_Y) / 2 * mark_scale)
    tx = left + mark_w + gap - box[0]
    ty = H / 2 - (box[1] + box[3]) / 2
    shadow = Image.new("RGBA", banner.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((tx, ty + H * 0.012), text, font=font, fill=(0, 0, 0, 110))
    banner.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(H * 0.018)))
    ImageDraw.Draw(banner).text((tx, ty), text, font=font, fill=(245, 245, 247, 255))
    out = banner.convert("RGB").resize((w, w * 9 // 16), Image.LANCZOS)
    os.makedirs(f"{RES}/{folder}", exist_ok=True)
    out.save(f"{RES}/{folder}/tv_banner.png", optimize=True)
    if w == 640: out.save(f"{PREVIEW}/banner-preview.png")
print("ok")
