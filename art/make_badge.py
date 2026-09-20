"""Builds the mod's badge: the vanilla factory gauge (white outlined) with a
green up arrow on its top-right corner, inside a white-ringed circle filled
with the blue grid.

Vanilla Create assets only - no resource packs. Run from the project root:

    python art/make_badge.py

Needs ``libs/create-1.21.1-6.0.10.jar`` (see README) and ``art/background.png``.
Writes ``promo/factory_gauge_icon.png`` (+ a 512px copy) and the banner.
"""
import copy
import json
import os
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import mc_model_render as R                                        # noqa: E402

OUT = os.path.join(ROOT, "promo")
BACKGROUND = os.path.join(ROOT, "art", "background.png")
os.makedirs(OUT, exist_ok=True)

# ---------------------------------------------------------------- the gauge
full = json.loads(R.read_asset("assets/create/models/block/factory_gauge/item.json"))
GAUGE = copy.deepcopy(full)
# element 1 = brass ring (hollow), element 2 = the beige cell plate seen through
# it. The wall connector, the 8x8 backing quad and the two little arms are left
# out: they render as stray grey/green slabs from an icon angle.
GAUGE["elements"] = [full["elements"][1], full["elements"][2]]

D = 1024                 # badge size
RING = 15                # width of the white ring hugging the circle
radius = D // 2 - RING   # radius of the grid disc

# The model's front faces -Y, so rz=180 turns it towards the camera; rx=76 tips
# it enough to show the ring's thickness.
panel = R.crop_alpha(R.render(GAUGE, 1024, transform=((0, 0, 0), (76, -16, 180), (1, 1, 1))))


def white_outline(img, r, color=(255, 255, 255, 255)):
    """Thick, crisp white ring hugging the silhouette, drawn *under* the image."""
    pad = r + 2
    canvas = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    canvas.alpha_composite(img, (pad, pad))
    # binarise first: a soft resize edge would make the ring look dashed
    a = canvas.split()[3].point(lambda v: 255 if v > 110 else 0)
    grown = a.filter(ImageFilter.MaxFilter(2 * r + 1))
    ring = ImageChops.subtract(grown, a)
    base = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    base.paste(Image.new("RGBA", canvas.size, color), (0, 0), ring)
    base.alpha_composite(canvas)
    return base


def pixel_arrow(w=17, h=22, head=9, shaft_half=3, fill=(74, 178, 70, 255),
                edge=(255, 255, 255, 255)):
    """A pixel-grid arrow: upscaled with NEAREST so it keeps the game's grain."""
    m = np.zeros((h, w), dtype=bool)
    cx = (w - 1) // 2
    for y in range(head):
        half = min(cx, y + 1)
        m[y, cx - half:cx + half + 1] = True
    m[head - 1:, cx - shaft_half:cx + shaft_half + 1] = True
    ring = np.zeros_like(m)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                ring |= np.roll(np.roll(m, dy, axis=0), dx, axis=1)
    ring &= ~m
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[ring] = edge
    out[m] = fill
    return Image.fromarray(out, "RGBA")


# ---------------------------------------------------------------- the disc
# The background has a white margin all around it, so a centre *square* crop of
# a wide image drags those bands inside the circle (they show up as gaps at the
# top and the bottom). Take the grid's own bounding box, then cover the square
# so the disc is grid edge to edge.
bg = Image.open(BACKGROUND).convert("RGB")
arr = np.asarray(bg).astype(np.int16)
grid_px = (arr < 248).any(axis=2)                    # anything that is not the margin
ys, xs = np.nonzero(grid_px)
inner = bg.crop((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))
print("grid inner box:", inner.size, "of", bg.size)


def cover(img, w, h):
    """Scale to completely cover w x h, preserving the aspect, then centre-crop."""
    scale = max(w / img.width, h / img.height)       # never letterbox
    grown = img.resize((max(w, round(img.width * scale)), max(h, round(img.height * scale))),
                       Image.LANCZOS)
    ox, oy = (grown.width - w) // 2, (grown.height - h) // 2
    return grown.crop((ox, oy, ox + w, oy + h))


target = 2 * radius
disc = cover(inner, target, target).convert("RGBA")

# ---------------------------------------------------------------- assemble
panel_h = int(D * 0.545)
panel = panel.resize((max(1, round(panel.width * panel_h / panel.height)), panel_h), Image.LANCZOS)
panel = white_outline(panel, 14)

arw_h = int(D * 0.215)
arw = pixel_arrow()
arw = arw.resize((max(1, round(arw.width * arw_h / arw.height)), arw_h), Image.NEAREST)

pcx, pcy = int(D * 0.485), int(D * 0.575)         # panel centre
acx, acy = int(D * 0.755), int(D * 0.325)         # arrow centre, on the panel's top-right

layer = Image.new("RGBA", (D, D), (0, 0, 0, 0))
layer.alpha_composite(panel, (pcx - panel.width // 2, pcy - panel.height // 2))
layer.alpha_composite(arw, (acx - arw.width // 2, acy - arw.height // 2))

mask = Image.new("L", (D, D), 0)
ImageDraw.Draw(mask).ellipse(((D - 2 * radius) // 2, (D - 2 * radius) // 2,
                              D - 1 - (D - 2 * radius) // 2, D - 1 - (D - 2 * radius) // 2), fill=255)
# white ring = a slightly bigger circle painted white before the disc
badge = Image.new("RGBA", (D, D), (0, 0, 0, 0))
big = Image.new("L", (D, D), 0)
ImageDraw.Draw(big).ellipse((0, 0, D - 1, D - 1), fill=255)
badge.paste(Image.new("RGBA", (D, D), (255, 255, 255, 255)), (0, 0), big)
off = ((D - disc.width) // 2, (D - disc.height) // 2)
badge.paste(disc, off, mask.crop((off[0], off[1], off[0] + disc.width, off[1] + disc.height)))
badge.alpha_composite(layer)

badge.save(os.path.join(OUT, "factory_gauge_icon.png"))
badge.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "factory_gauge_icon_512.png"))
print("wrote round icon", badge.size, "+ 512px copy")

# ---------------------------------------------------------------- banner
bw, bh = 1728, 1080
grid = cover(inner, bw, bh).convert("RGBA")
# dim the grid behind the badge, otherwise the badge's own grid is invisible
grid.alpha_composite(Image.new("RGBA", (bw, bh), (6, 20, 44, 145)))
bh_icon = int(bh * 0.86)
icon_big = badge.resize((bh_icon, bh_icon), Image.LANCZOS)
banner = grid.copy()
shadow = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
shadow.paste(Image.new("RGBA", icon_big.size, (0, 0, 0, 150)),
             ((bw - bh_icon) // 2, (bh - bh_icon) // 2), icon_big.split()[3])
banner.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(30)))
banner.alpha_composite(icon_big, ((bw - icon_big.width) // 2, (bh - icon_big.height) // 2))
banner.convert("RGB").save(os.path.join(OUT, "factory_gauge_banner.png"))
print("wrote banner", banner.size)
