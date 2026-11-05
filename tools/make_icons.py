"""Generate legacy launcher PNGs for FundRadar (adaptive icon uses vectors)."""
import math
import os
from PIL import Image, ImageDraw

OUT = r"D:\DeepSeekharness\work\FundRadar\app\src\main\res"
SS = 4  # supersampling factor
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_icon(size, round_icon):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # vertical gradient background
    grad = Image.new("RGBA", (s, s))
    gd = ImageDraw.Draw(grad)
    top, bottom = (0x00, 0x6B, 0x5F), (0x00, 0x3E, 0x38)
    for y in range(s):
        gd.line([(0, y), (s, y)], fill=lerp(top, bottom, y / max(1, s - 1)) + (255,))

    if round_icon:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    else:
        mask = Image.new("L", (s, s), 0)
        radius = int(s * 0.23)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=255)

    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img, "RGBA")

    cx, cy = s * 0.5, s * 0.52
    r = s * 0.30
    stroke = max(2, int(s * 0.032))

    for radius, alpha in ((r, 190), (r * 0.56, 150)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                  outline=(255, 255, 255, alpha), width=stroke)

    # sweep wedge from -90deg to -25deg
    pts = [(cx, cy)]
    for step in range(0, 25):
        ang = math.radians(-90 + step * (65 / 24))
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    d.polygon(pts, fill=(255, 255, 255, 235))

    # centre dot
    cd = s * 0.045
    d.ellipse([cx - cd, cy - cd, cx + cd, cy + cd], fill=(255, 255, 255, 255))

    # red "blip"
    ang = math.radians(-30)
    bx, by = cx + r * 0.78 * math.cos(ang), cy + r * 0.78 * math.sin(ang)
    br = s * 0.038
    d.ellipse([bx - br, by - br, bx + br, by + br], fill=(0xFF, 0x8A, 0x65, 255))

    return img.resize((size, size), Image.LANCZOS)


for density, size in DENSITIES.items():
    folder = os.path.join(OUT, f"mipmap-{density}")
    os.makedirs(folder, exist_ok=True)
    draw_icon(size, False).save(os.path.join(folder, "ic_launcher.png"))
    draw_icon(size, True).save(os.path.join(folder, "ic_launcher_round.png"))
    print("wrote", folder, size)

print("done")
