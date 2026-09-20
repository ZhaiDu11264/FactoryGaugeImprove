"""Render a Minecraft JSON block model (with its display transform) to a PNG.

Covers what this job needs: axis-aligned cuboid elements, per-element
single-axis rotation, per-face uv (+ uv rotation), one texture atlas,
z-buffered orthographic rasteriser.  Enough to reproduce Create's factory
gauge item icon the way the game draws it in an inventory slot.

Conventions that matter (easy to get wrong):
  * model JSON uv is in 1/16 of the texture, origin top-left, matching PNG
    pixel coordinates directly -> no v flip;
  * per face the four uv entries are TL, BL, BR, TR = (u1,v1) (u1,v2)
    (u2,v2) (u2,v1);
  * the display transform is T * Rz * Ry * Rx * S applied to a model point
    already centred onto [-0.5, 0.5];
  * GUI lighting is two opposite lights, so the top face reads bright while
    the vertical faces fall off.
"""
import io
import json
import math
import os
import zipfile

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _source_jars() -> list[str]:
    """Archives to read assets from, highest priority first.

    Defaults to the Create jar in ``libs/``. Set ``FGI_ASSET_JARS`` (separated by
    ``os.pathsep``) to point somewhere else, or to stack a resource pack on top
    of Create - the same "first hit wins" order the game uses.
    """
    override = os.environ.get("FGI_ASSET_JARS", "")
    if override:
        return [p for p in override.split(os.pathsep) if p]
    return [os.path.join(ROOT, "libs", "create-1.21.1-6.0.10.jar")]


# vanilla Create assets only - no resource packs
SOURCES = _source_jars()
SUPERSAMPLE = 4

_archives = None


def archives():
    global _archives
    if _archives is None:
        out = []
        for p in SOURCES:
            try:
                out.append(zipfile.ZipFile(p))
            except Exception as exc:  # pragma: no cover
                print("skip", p, exc)
        _archives = out
    return _archives


def read_asset(path):
    for z in archives():
        try:
            return z.read(path)
        except KeyError:
            continue
    raise KeyError(path)


# ---------------------------------------------------------------- atlas


def load_texture(name):
    ns, path = name.split(":", 1)
    return Image.open(io.BytesIO(read_asset(f"assets/{ns}/textures/{path}.png"))).convert("RGBA")


# ---------------------------------------------------------------- maths


def mat_identity():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def mat_apply(m, v):
    x, y, z = v
    return (
        m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3],
        m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
        m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3],
    )


def mat_apply_dir(m, v):
    x, y, z = v
    return (
        m[0][0] * x + m[0][1] * y + m[0][2] * z,
        m[1][0] * x + m[1][1] * y + m[1][2] * z,
        m[2][0] * x + m[2][1] * y + m[2][2] * z,
    )


def mat_translate(tx, ty, tz):
    m = mat_identity()
    m[0][3], m[1][3], m[2][3] = tx, ty, tz
    return m


def mat_scale(sx, sy, sz):
    m = mat_identity()
    m[0][0], m[1][1], m[2][2] = sx, sy, sz
    return m


def mat_rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = mat_identity()
    m[1][1], m[1][2], m[2][1], m[2][2] = c, -s, s, c
    return m


def mat_rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = mat_identity()
    m[0][0], m[0][2], m[2][0], m[2][2] = c, s, -s, c
    return m


def mat_rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = mat_identity()
    m[0][0], m[0][1], m[1][0], m[1][1] = c, -s, s, c
    return m


def normalize(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


# ---------------------------------------------------------------- faces

# corner order in uv order TL, BL, BR, TR
FACE_DEFS = {
    "north": ([(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)], (0, 0, -1)),
    "south": ([(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)], (0, 0, 1)),
    "east": ([(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)], (1, 0, 0)),
    "west": ([(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)], (-1, 0, 0)),
    "up": ([(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)], (0, 1, 0)),
    "down": ([(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)], (0, -1, 0)),
}
UV_ORDER = [(0, 0), (0, 1), (1, 1), (1, 0)]

LIGHT0 = normalize((0.2, 1.0, -0.7))
LIGHT1 = tuple(-c for c in LIGHT0)


def face_shade(normal, ambient=0.4, gain=0.7):
    d0 = max(0.0, sum(a * b for a, b in zip(normal, LIGHT0)))
    d1 = max(0.0, sum(a * b for a, b in zip(normal, LIGHT1)))
    return min(1.0, ambient + gain * (d0 + d1))


def rotate_uv(corners, rotation):
    r = int(rotation or 0) % 360
    out = list(corners)
    for _ in range(r // 90):
        out = out[-1:] + out[:-1]
    return out


# ---------------------------------------------------------------- model


def element_matrix(el):
    rot = el.get("rotation")
    if not rot:
        return mat_identity()
    org = rot["origin"]
    r = {"x": mat_rot_x, "y": mat_rot_y, "z": mat_rot_z}[rot["axis"]](rot["angle"])
    return mat_mul(mat_translate(*org), mat_mul(r, mat_translate(-org[0], -org[1], -org[2])))


def resolve_model(root):
    while "elements" not in root and "parent" in root:
        ns, path = root["parent"].split(":", 1)
        parent = json.loads(read_asset(f"assets/{ns}/models/{path}.json"))
        merged = dict(parent)
        merged.update({k: v for k, v in root.items() if k != "parent"})
        textures = dict(parent.get("textures", {}))
        textures.update(root.get("textures", {}))
        merged["textures"] = textures
        root = merged
    return root


def build_quads(root):
    quads = []
    for el in root["elements"]:
        x0, y0, z0 = el["from"]
        x1, y1, z1 = el["to"]
        em = element_matrix(el)
        for fname, (corners, normal) in FACE_DEFS.items():
            face = el["faces"].get(fname)
            if not face:
                continue
            u1, v1, u2, v2 = face["uv"]
            order = rotate_uv(UV_ORDER, face.get("rotation"))
            quad_uv = [(u1 + (u2 - u1) * a, v1 + (v2 - v1) * b) for a, b in order]
            pts = [mat_apply(em, (x0 + (x1 - x0) * a, y0 + (y1 - y0) * b, z0 + (z1 - z0) * c))
                   for a, b, c in corners]
            normal_out = mat_apply_dir(em, normal)
            quads.append({"pts": pts, "uv": quad_uv, "tex": face.get("texture"),
                          "normal": normal_out, "face": fname})
    return quads


# ---------------------------------------------------------------- render


def render(model, size, display="gui", transform=None):
    root = resolve_model(model)
    quads = build_quads(root)

    textures = {}
    for q in quads:
        key = q["tex"]
        values = root.get("textures", {})
        seen = set()
        while key and key.startswith("#") and key not in seen:
            seen.add(key)
            key = values.get(key[1:], "#missing")
        q["texname"] = key
        if key not in textures:
            textures[key] = load_texture(key)

    if transform is not None:
        tx, ty, tz = transform[0]
        rx, ry, rz = transform[1]
        sx, sy, sz = transform[2]
    else:
        d = root.get("display", {}).get(display, {})
        tx, ty, tz = d.get("translation", [0, 0, 0])
        rx, ry, rz = d.get("rotation", [0, 0, 0])
        sx, sy, sz = d.get("scale", [1, 1, 1])
    rot = mat_mul(mat_rot_z(rz), mat_mul(mat_rot_y(ry), mat_rot_x(rx)))
    disp = mat_mul(mat_translate(tx / 16.0, ty / 16.0, tz / 16.0), mat_mul(rot, mat_scale(sx, sy, sz)))

    proj = [[mat_apply(disp, (p[0] / 16.0 - 0.5, p[1] / 16.0 - 0.5, p[2] / 16.0 - 0.5)) for p in q["pts"]]
            for q in quads]

    xs = [v[0] for poly in proj for v in poly]
    ys = [v[1] for poly in proj for v in poly]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    span = max(maxx - minx, maxy - miny)
    margin = 0.03
    factor = (1.0 - 2 * margin) / span
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2

    W = H = size * SUPERSAMPLE
    zbuf = np.full((H, W), -1e9, dtype=np.float32)
    rgba = np.zeros((H, W, 4), dtype=np.float32)

    for qi, q in enumerate(quads):
        normal_view = mat_apply_dir(rot, q["normal"])
        # Minecraft culls by the *nominal* face normal, not by winding: the model
        # uses "from > to" boxes whose visible surface is the face whose nominal
        # normal points at the camera, even where the geometry sits behind.
        if normal_view[2] <= 1e-9:
            continue
        tex = np.asarray(textures[q["texname"]], dtype=np.float32)
        th, tw = tex.shape[:2]
        shade = face_shade(normal_view)
        uvpx = [(a / 16.0 * tw, b / 16.0 * th) for a, b in q["uv"]]
        poly = []
        for v in proj[qi]:
            poly.append(((v[0] - cx) * factor * W + W / 2.0,
                         H / 2.0 - (v[1] - cy) * factor * W,
                         v[2]))
        for tri in ((0, 1, 2), (0, 2, 3)):
            p0, p1, p2 = (poly[i] for i in tri)
            (u0, v0), (u1, v1), (u2, v2) = (uvpx[i] for i in tri)
            x_lo = max(0, int(math.floor(min(p0[0], p1[0], p2[0]))))
            x_hi = min(W, int(math.ceil(max(p0[0], p1[0], p2[0]))) + 1)
            y_lo = max(0, int(math.floor(min(p0[1], p1[1], p2[1]))))
            y_hi = min(H, int(math.ceil(max(p0[1], p1[1], p2[1]))) + 1)
            if x_lo >= x_hi or y_lo >= y_hi:
                continue
            det = (p1[1] - p2[1]) * (p0[0] - p2[0]) + (p2[0] - p1[0]) * (p0[1] - p2[1])
            if abs(det) < 1e-12:
                continue
            ys, xs = np.mgrid[y_lo:y_hi, x_lo:x_hi]
            fx = xs + 0.5
            fy = ys + 0.5
            w0 = ((p1[1] - p2[1]) * (fx - p2[0]) + (p2[0] - p1[0]) * (fy - p2[1])) / det
            w1 = ((p2[1] - p0[1]) * (fx - p2[0]) + (p0[0] - p2[0]) * (fy - p2[1])) / det
            w2 = 1.0 - w0 - w1
            inside = (w0 > -1e-6) & (w1 > -1e-6) & (w2 > -1e-6)
            if not inside.any():
                continue
            z = w0 * p0[2] + w1 * p1[2] + w2 * p2[2]
            view = zbuf[y_lo:y_hi, x_lo:x_hi]
            hit = inside & (z > view)
            if not hit.any():
                continue
            u = w0 * u0 + w1 * u1 + w2 * u2
            v = w0 * v0 + w1 * v1 + w2 * v2
            tx = np.clip(u.astype(np.int32) % tw, 0, tw - 1)
            ty = np.clip(v.astype(np.int32) % th, 0, th - 1)
            texel = tex[ty, tx]
            hit &= texel[:, :, 3] > 0
            if not hit.any():
                continue
            view[hit] = z[hit]
            block = rgba[y_lo:y_hi, x_lo:x_hi]
            src = texel[hit].copy()
            src[:, :3] = np.clip(src[:, :3] * shade, 0, 255)
            block[hit] = src
            rgba[y_lo:y_hi, x_lo:x_hi] = block

    out = Image.fromarray(rgba.astype(np.uint8), "RGBA")
    return out.resize((size, size), Image.LANCZOS)


def crop_alpha(img, pad=0):
    box = img.split()[3].getbbox()
    if box is None:
        return img
    x0, y0, x1, y1 = box
    return img.crop((max(0, x0 - pad), max(0, y0 - pad),
                     min(img.width, x1 + pad), min(img.height, y1 + pad)))


if __name__ == "__main__":
    import sys

    model = json.loads(read_asset("assets/create/models/block/factory_gauge/item.json"))

    if len(sys.argv) > 1 and sys.argv[1] == "sheet":
        angles = [(30, 135, 0), (30, 180, 0), (30, 225, 0),
                  (50, 135, 0), (50, 180, 0), (50, 225, 0),
                  (65, 135, 0), (65, 180, 0), (65, 225, 0),
                  (80, 180, 0), (80, 135, 0), (80, 225, 0)]
        tile = 240
        cols = 4
        sheet = Image.new("RGBA", (cols * (tile + 12) + 12,
                                   ((len(angles) + cols - 1) // cols) * (tile + 12) + 12),
                          (62, 62, 70, 255))
        for i, (rx, ry, rz) in enumerate(angles):
            img = crop_alpha(render(model, 320, transform=((0, 0, 0), (rx, ry, rz), (1, 1, 1))))
            img.thumbnail((tile, tile), Image.LANCZOS)
            cx = 12 + (i % cols) * (tile + 12)
            cy = 12 + (i // cols) * (tile + 12)
            sheet.alpha_composite(img, (cx + (tile - img.width) // 2, cy + (tile - img.height) // 2))
        sheet.save("angle_sheet.png")
        print("angles:", angles)
        print("wrote angle_sheet.png", sheet.size)
        raise SystemExit

    icon = crop_alpha(render(model, 512))
    icon.resize((512, max(1, int(512 * icon.height / icon.width))), Image.LANCZOS).save("icon.png")
    print("wrote icon.png from", icon.size)
