"""
Builds GEO Tree's offline field basemap (raster MBTiles) for the region in region.json.

Data:   OpenStreetMap data for the region's bounding box, fetched ONCE from the Overpass API
        (fetch_osm.py) and stored as osm_raw.json. OSM data is (c) OpenStreetMap contributors,
        licensed under the ODbL; the rendered tiles must keep that attribution.
Tiles:  rendered locally by this script with Pillow. No public tile server is contacted.

Usage (from tools/offline_map):
    python -m venv .venv
    .venv\\Scripts\\python.exe -m pip install pillow
    .venv\\Scripts\\python.exe fetch_osm.py            # only when the data should be refreshed
    .venv\\Scripts\\python.exe build_offline_map.py     # writes the package into the app's assets
"""
import hashlib
import json
import math
import os
import sqlite3
import sys
import time
from datetime import datetime, timezone
from io import BytesIO

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSET_DIR = os.path.join(REPO, "android", "app", "src", "main", "assets", "offline_map")

TILE = 512          # output pixels per tile (2x density; MapLibre style uses tileSize 256)
SS = 2              # supersampling factor for anti-aliasing
CANVAS = TILE * SS
K = (TILE / 256) * SS   # canvas units per logical pixel
WEBP_QUALITY = 82
FORMAT_VERSION = 1      # bump when the renderer output changes

FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
FONT_REGULAR = os.path.join(FONT_DIR, "arial.ttf")
FONT_BOLD = os.path.join(FONT_DIR, "arialbd.ttf")
FONT_ITALIC = os.path.join(FONT_DIR, "ariali.ttf")

# ---- Palette: subdued sand/moss so markers, the red route and the blue GPS dot dominate ----
C = {
    "land": (238, 232, 218),
    "outside": (226, 220, 206),
    "edge": (181, 101, 58),
    "farmland": (228, 230, 205),
    "orchard": (216, 224, 190),
    "grass": (225, 230, 204),
    "wood": (204, 217, 182),
    "aquaculture": (207, 224, 227),
    "water": (188, 211, 217),
    "waterway": (169, 198, 207),
    "residential": (234, 227, 211),
    "commercial": (234, 220, 203),
    "industrial": (226, 220, 208),
    "cemetery": (217, 225, 200),
    "institution": (233, 225, 203),
    "institution_edge": (205, 182, 145),
    "pitch": (211, 224, 188),
    "building": (216, 204, 184),
    "building_edge": (196, 182, 158),
    "casing": (203, 191, 168),
    "primary": (246, 222, 190),
    "secondary": (248, 232, 206),
    "tertiary": (251, 244, 230),
    "minor": (255, 255, 255),
    "track": (172, 146, 112),
    "path": (176, 125, 96),
    "rail": (150, 140, 128),
    "text": (61, 64, 58),
    "text_soft": (92, 96, 90),
    "halo": (246, 241, 228),
    "psau": (142, 74, 38),
}

LANDUSE_COLORS = {
    ("landuse", "farmland"): "farmland", ("landuse", "farmyard"): "farmland", ("landuse", "meadow"): "grass",
    ("landuse", "orchard"): "orchard", ("landuse", "plant_nursery"): "orchard", ("landuse", "vineyard"): "orchard",
    ("landuse", "grass"): "grass", ("natural", "grassland"): "grass", ("natural", "heath"): "grass",
    ("natural", "scrub"): "grass", ("leisure", "park"): "grass", ("leisure", "garden"): "grass",
    ("landuse", "forest"): "wood", ("natural", "wood"): "wood",
    ("landuse", "aquaculture"): "aquaculture", ("landuse", "basin"): "water", ("landuse", "reservoir"): "water",
    ("natural", "water"): "water", ("waterway", "riverbank"): "water",
    ("landuse", "residential"): "residential",
    ("landuse", "commercial"): "commercial", ("landuse", "retail"): "commercial",
    ("landuse", "industrial"): "industrial", ("landuse", "quarry"): "industrial", ("landuse", "brownfield"): "industrial",
    ("landuse", "construction"): "industrial",
    ("landuse", "cemetery"): "cemetery", ("amenity", "grave_yard"): "cemetery",
    ("landuse", "religious"): "institution", ("amenity", "place_of_worship"): "institution",
    ("amenity", "school"): "institution", ("amenity", "university"): "institution", ("amenity", "college"): "institution",
    ("amenity", "kindergarten"): "institution", ("amenity", "hospital"): "institution",
    ("leisure", "pitch"): "pitch", ("leisure", "stadium"): "pitch", ("leisure", "sports_centre"): "pitch",
    ("leisure", "playground"): "pitch",
}
# Painter's order for area fills (later draws on top).
AREA_ORDER = ["residential", "commercial", "industrial", "farmland", "orchard", "grass", "cemetery",
              "institution", "pitch", "wood", "aquaculture", "water"]

# Road classes: (min zoom, width in logical px at z17, fill colour, casing?)
ROADS = {
    "primary": (13, 11.0, "primary", True), "primary_link": (14, 8.0, "primary", True), "trunk": (13, 12.0, "primary", True),
    "secondary": (13, 10.0, "secondary", True), "secondary_link": (14, 7.0, "secondary", True),
    "tertiary": (13, 9.0, "tertiary", True), "tertiary_link": (15, 6.5, "tertiary", True),
    "unclassified": (14, 7.5, "minor", True), "residential": (14, 7.0, "minor", True),
    "living_street": (15, 6.5, "minor", True), "road": (15, 6.0, "minor", True),
    "service": (16, 4.5, "minor", True), "pedestrian": (16, 4.5, "minor", True),
}
DASHED = {"track": (15, 2.4, "track", (9, 5)), "path": (16, 1.6, "path", (5, 4)),
          "footway": (16, 1.6, "path", (5, 4)), "cycleway": (16, 1.6, "path", (5, 4)),
          "bridleway": (16, 1.6, "path", (5, 4)), "steps": (17, 2.0, "path", (2, 2))}
ROAD_DRAW_ORDER = ["service", "pedestrian", "road", "living_street", "residential", "unclassified",
                   "tertiary_link", "tertiary", "secondary_link", "secondary", "primary_link", "primary", "trunk"]
WATERWAYS = {"river": 7.0, "canal": 4.0, "stream": 2.6, "drain": 1.8, "ditch": 1.5}


def load_json(name):
    with open(os.path.join(HERE, name), encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- projection

def world_px(lat, lon, z):
    """Web Mercator position in canvas units at zoom z."""
    scale = 256 * (2 ** z) * K
    x = (lon + 180.0) / 360.0 * scale
    s = math.sin(math.radians(max(min(lat, 85.0511), -85.0511)))
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * scale
    return x, y


def tile_range(region, z):
    n = 2 ** z

    def tx(lon):
        return int((lon + 180.0) / 360.0 * n)

    def ty(lat):
        la = math.radians(lat)
        return int((1 - math.log(math.tan(la) + 1 / math.cos(la)) / math.pi) / 2 * n)

    return (tx(region["min_longitude"]), ty(region["max_latitude"]), tx(region["max_longitude"]), ty(region["min_latitude"]))


def zoom_factor(z):
    """Line widths are specified at z17; scale them by zoom with sensible floors."""
    return 2 ** (z - 17)


# ---------------------------------------------------------------- OSM parsing

def closed(coords):
    return len(coords) >= 4 and coords[0] == coords[-1]


def join_rings(parts):
    """Joins open member ways of a multipolygon into closed rings."""
    parts = [list(p) for p in parts if len(p) >= 2]
    rings = []
    while parts:
        ring = parts.pop(0)
        changed = True
        while not closed(ring) and changed:
            changed = False
            for i, p in enumerate(parts):
                if p[0] == ring[-1]:
                    ring += p[1:]
                elif p[-1] == ring[-1]:
                    ring += list(reversed(p))[1:]
                elif p[-1] == ring[0]:
                    ring = p[:-1] + ring
                elif p[0] == ring[0]:
                    ring = list(reversed(p))[:-1] + ring
                else:
                    continue
                parts.pop(i)
                changed = True
                break
        if len(ring) >= 3:
            if ring[0] != ring[-1]:
                ring.append(ring[0])
            rings.append(ring)
    return rings


def area_class(tags):
    for key in ("landuse", "natural", "leisure", "amenity", "waterway"):
        value = tags.get(key)
        if value and (key, value) in LANDUSE_COLORS:
            return LANDUSE_COLORS[(key, value)]
    if "water" in tags:
        return "water"
    return None


def parse(osm):
    areas, buildings, roads, dashed, waterways, rails, places, named_areas = [], [], [], [], [], [], [], []
    for e in osm["elements"]:
        tags = e.get("tags", {})
        if e["type"] == "node":
            if "place" in tags or tags.get("amenity") == "university":
                places.append({"lat": e["lat"], "lon": e["lon"], "tags": tags})
            continue
        if e["type"] == "way":
            geom = e.get("geometry") or []
            coords = [(p["lat"], p["lon"]) for p in geom if p]
            if len(coords) < 2:
                continue
            hw = tags.get("highway")
            if hw and tags.get("area") != "yes":
                if hw in ROADS:
                    roads.append({"cls": hw, "coords": coords, "name": tags.get("name"), "bridge": tags.get("bridge") == "yes"})
                elif hw in DASHED:
                    dashed.append({"cls": hw, "coords": coords})
                continue
            if tags.get("waterway") in WATERWAYS and not closed(coords):
                waterways.append({"cls": tags["waterway"], "coords": coords})
                continue
            if tags.get("railway") in ("rail", "narrow_gauge", "abandoned", "disused") and not closed(coords):
                rails.append({"coords": coords})
                continue
            if not closed(coords):
                continue
            if "building" in tags and tags["building"] != "no":
                buildings.append({"outer": [coords], "inner": []})
                continue
            cls = area_class(tags)
            if cls:
                areas.append({"cls": cls, "outer": [coords], "inner": []})
                if tags.get("name") and cls in ("institution", "cemetery", "pitch"):
                    named_areas.append({"name": tags["name"], "rings": [coords], "tags": tags})
        elif e["type"] == "relation":
            outer, inner = [], []
            for m in e.get("members", []):
                if m.get("type") != "way" or not m.get("geometry"):
                    continue
                pts = [(p["lat"], p["lon"]) for p in m["geometry"] if p]
                (inner if m.get("role") == "inner" else outer).append(pts)
            outer_rings, inner_rings = join_rings(outer), join_rings(inner)
            if not outer_rings:
                continue
            if "building" in tags:
                buildings.append({"outer": outer_rings, "inner": inner_rings})
                continue
            cls = area_class(tags)
            if cls:
                areas.append({"cls": cls, "outer": outer_rings, "inner": inner_rings})
                if tags.get("name") and cls in ("institution", "cemetery", "pitch"):
                    named_areas.append({"name": tags["name"], "rings": outer_rings, "tags": tags})
    return areas, buildings, roads, dashed, waterways, rails, places, named_areas


# ---------------------------------------------------------------- spatial bucketing per zoom

def bbox_of(rings):
    lats = [p[0] for r in rings for p in r]
    lons = [p[1] for r in rings for p in r]
    return min(lats), min(lons), max(lats), max(lons)


def bucket(features, z, rings_of, margin_px):
    """Maps (tx, ty) -> features whose canvas bbox (plus margin) touches that tile."""
    out = {}
    for f in features:
        s, w, n, e = bbox_of(rings_of(f))
        x0, y0 = world_px(n, w, z)
        x1, y1 = world_px(s, e, z)
        for tx in range(int((x0 - margin_px) // CANVAS), int((x1 + margin_px) // CANVAS) + 1):
            for ty in range(int((y0 - margin_px) // CANVAS), int((y1 + margin_px) // CANVAS) + 1):
                out.setdefault((tx, ty), []).append(f)
    return out


# ---------------------------------------------------------------- drawing helpers

def project(coords, z, ox, oy):
    return [(x - ox, y - oy) for x, y in (world_px(lat, lon, z) for lat, lon in coords)]


def draw_area(img, rings_outer, rings_inner, z, ox, oy, fill, outline=None, outline_w=0):
    if not rings_inner:
        d = ImageDraw.Draw(img)
        for r in rings_outer:
            pts = project(r, z, ox, oy)
            if len(pts) >= 3:
                d.polygon(pts, fill=fill)
                if outline:
                    d.line(pts, fill=outline, width=outline_w)
        return
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    for r in rings_outer:
        md.polygon(project(r, z, ox, oy), fill=255)
    for r in rings_inner:
        md.polygon(project(r, z, ox, oy), fill=0)
    img.paste(Image.new("RGB", img.size, fill), (0, 0), mask)


def draw_polyline(d, pts, color, width):
    w = max(1, int(round(width)))
    d.line(pts, fill=color, width=w, joint="curve")
    if w >= 4:
        r = w / 2.0
        for x, y in (pts[0], pts[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=color)


def dash_segments(pts, on, off):
    """Splits a polyline into dash segments using a phase measured from the way's start,
    so dashes line up across tile edges."""
    out, pattern, idx, remain, drawing, current = [], (on, off), 0, on, True, [pts[0]]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        pos = 0.0
        while seg - pos > remain:
            pos += remain
            t = pos / seg
            p = (x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            if drawing:
                current.append(p)
                out.append(current)
            current = [p]
            drawing = not drawing
            idx = 1 - idx
            remain = pattern[idx]
        remain -= seg - pos
        if drawing:
            current.append((x1, y1))
        else:
            current = [(x1, y1)]
    if drawing and len(current) >= 2:
        out.append(current)
    return out


# ---------------------------------------------------------------- labels (placed once per zoom, globally)

class Label:
    __slots__ = ("img", "x", "y", "rect")

    def __init__(self, img, x, y):
        self.img, self.x, self.y = img, x, y
        self.rect = (x, y, x + img.width, y + img.height)


_font_cache = {}


def font(path, size):
    key = (path, int(size))
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(path, int(size))
    return _font_cache[key]


def text_image(text, fnt, color, halo=C["halo"], halo_w=None):
    halo_w = int(halo_w if halo_w is not None else 2.2 * K)
    l, t, r, b = fnt.getbbox(text, stroke_width=halo_w)
    img = Image.new("RGBA", (r - l + 4, b - t + 4), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((2 - l, 2 - t), text, font=fnt, fill=color + (255,), stroke_width=halo_w, stroke_fill=halo + (235,))
    return img


def overlaps(rect, placed, pad):
    x0, y0, x1, y1 = rect
    for a0, b0, a1, b1 in placed:
        if x0 - pad < a1 and x1 + pad > a0 and y0 - pad < b1 and y1 + pad > b0:
            return True
    return False


PLACE_STYLE = {
    # place: (min zoom, logical font size, font, colour)
    "town": (13, 15, FONT_BOLD, "text"),
    "village": (14, 12, FONT_BOLD, "text_soft"),
    "quarter": (15, 11, FONT_REGULAR, "text_soft"),
    "hamlet": (15, 10.5, FONT_REGULAR, "text_soft"),
    "neighbourhood": (17, 10, FONT_ITALIC, "text_soft"),
}


def place_labels(z, places, roads, named_areas):
    placed_rects, labels = [], []

    def try_add(img, cx, cy, pad=6 * K):
        x, y = int(cx - img.width / 2), int(cy - img.height / 2)
        rect = (x, y, x + img.width, y + img.height)
        if overlaps(rect, placed_rects, pad):
            return False
        placed_rects.append(rect)
        labels.append(Label(img, x, y))
        return True

    # 1. The university always wins.
    for p in places:
        if p["tags"].get("amenity") == "university":
            size = 13 if z <= 14 else 15
            img = text_image(p["tags"].get("name", "PSAU") if z >= 15 else "PSAU", font(FONT_BOLD, size * K), C["psau"])
            try_add(img, *world_px(p["lat"], p["lon"], z))

    # 2. Places by rank.
    for rank in ("town", "village", "quarter", "hamlet"):
        for p in places:
            if p["tags"].get("place") != rank:
                continue
            minz, size, fpath, color = PLACE_STYLE[rank]
            if z >= minz and p["tags"].get("name"):
                try_add(text_image(p["tags"]["name"], font(fpath, size * K), C[color]), *world_px(p["lat"], p["lon"], z))

    # 3. Road names along straight runs.
    road_label_min = {"primary": 15, "trunk": 15, "secondary": 15, "tertiary": 16}
    by_rank = sorted((r for r in roads if r["name"]), key=lambda r: ROAD_DRAW_ORDER.index(r["cls"]) if r["cls"] in ROAD_DRAW_ORDER else 0, reverse=True)
    last_by_name = {}
    for r in by_rank:
        if z < road_label_min.get(r["cls"], 17):
            continue
        fnt = font(FONT_REGULAR, 10.5 * K)
        text_w = fnt.getlength(r["name"]) + 16 * K
        pts = [world_px(lat, lon, z) for lat, lon in r["coords"]]
        for (x0, y0), (x1, y1) in straight_runs(pts):
            length = math.hypot(x1 - x0, y1 - y0)
            if length < text_w:
                continue
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            # The same street name is repeated at most every ~260 logical px.
            if any(math.hypot(cx - px, cy - py) < 260 * K for px, py in last_by_name.get(r["name"], [])):
                continue
            angle = math.degrees(math.atan2(y1 - y0, x1 - x0))
            if angle > 90:
                angle -= 180
            elif angle < -90:
                angle += 180
            img = text_image(r["name"], fnt, C["text"]).rotate(-angle, expand=True, resample=Image.BICUBIC)
            if try_add(img, cx, cy, pad=2 * K):
                last_by_name.setdefault(r["name"], []).append((cx, cy))

    # 4. Named schools / grounds when large enough on screen.
    if z >= 17:
        for a in named_areas:
            s, w, n, e = bbox_of(a["rings"])
            x0, y0 = world_px(n, w, z)
            x1, y1 = world_px(s, e, z)
            fnt = font(FONT_ITALIC, 10 * K)
            if (x1 - x0) < fnt.getlength(a["name"]) * 0.8:
                continue
            try_add(text_image(a["name"], fnt, C["text_soft"]), (x0 + x1) / 2, (y0 + y1) / 2)

    # 5. Neighbourhood / purok names last.
    for p in places:
        if p["tags"].get("place") == "neighbourhood" and z >= PLACE_STYLE["neighbourhood"][0] and p["tags"].get("name"):
            _, size, fpath, color = PLACE_STYLE["neighbourhood"]
            try_add(text_image(p["tags"]["name"], font(fpath, size * K), C[color]), *world_px(p["lat"], p["lon"], z))
    return labels


def straight_runs(pts, max_turn_deg=10):
    """Splits a projected polyline into nearly straight runs (start, end), comparing each
    segment's heading with the heading at the start of the current run."""
    runs, start, heading = [], 0, None
    for i in range(1, len(pts)):
        (x0, y0), (x1, y1) = pts[i - 1], pts[i]
        h = math.degrees(math.atan2(y1 - y0, x1 - x0))
        if heading is None:
            heading = h
        elif abs((h - heading + 180) % 360 - 180) > max_turn_deg:
            runs.append((pts[start], pts[i - 1]))
            start, heading = i - 1, h
    runs.append((pts[start], pts[-1]))
    return runs


# ---------------------------------------------------------------- tile rendering

def render_tile(z, tx, ty, region, data, buckets, labels):
    areas_b, buildings_b, roads_b, dashed_b, water_b, rail_b = buckets
    ox, oy = tx * CANVAS, ty * CANVAS
    img = Image.new("RGB", (CANVAS, CANVAS), C["land"])
    key = (tx, ty)
    f = zoom_factor(z)

    tile_areas = areas_b.get(key, [])
    for cls in AREA_ORDER:
        for a in tile_areas:
            if a["cls"] == cls:
                outline = C["institution_edge"] if cls == "institution" and z >= 15 else None
                draw_area(img, a["outer"], a["inner"], z, ox, oy, C[cls], outline, int(max(1, 1.2 * K)))

    d = ImageDraw.Draw(img)
    for w in water_b.get(key, []):
        width = max(1.0 * K, WATERWAYS[w["cls"]] * K * max(f, 0.35))
        draw_polyline(d, project(w["coords"], z, ox, oy), C["waterway"], width)

    if z >= 15:
        edge = C["building_edge"] if z >= 16 else None
        for b in buildings_b.get(key, []):
            draw_area(img, b["outer"], b["inner"], z, ox, oy, C["building"], edge, int(max(1, 0.9 * K)) if edge else 0)
        d = ImageDraw.Draw(img)

    for r in rail_b.get(key, []):
        draw_polyline(d, project(r["coords"], z, ox, oy), C["rail"], 1.6 * K)

    for dsh in dashed_b.get(key, []):
        minz, width, color, (on, off) = DASHED[dsh["cls"]]
        if z < minz:
            continue
        pts = project(dsh["coords"], z, ox, oy)
        for seg in dash_segments(pts, on * K, off * K):
            draw_polyline(d, seg, C[color], width * K * max(f, 0.6))

    tile_roads = [r for r in roads_b.get(key, []) if z >= ROADS[r["cls"]][0]]
    projected = {id(r): project(r["coords"], z, ox, oy) for r in tile_roads}

    def road_width(cls):
        base = ROADS[cls][1]
        return max(base * K * f, (2.2 if cls in ("primary", "trunk", "secondary") else 1.4) * K)

    for cls in ROAD_DRAW_ORDER:
        for r in tile_roads:
            if r["cls"] == cls and ROADS[cls][3]:
                draw_polyline(d, projected[id(r)], C["casing"], road_width(cls) + 2.4 * K * min(1.0, max(f, 0.5)))
    for cls in ROAD_DRAW_ORDER:
        for r in tile_roads:
            if r["cls"] == cls:
                draw_polyline(d, projected[id(r)], C[ROADS[cls][2]], road_width(cls))

    # Shade everything outside the packaged region so the coverage edge is visible.
    x0, y0 = world_px(region["max_latitude"], region["min_longitude"], z)
    x1, y1 = world_px(region["min_latitude"], region["max_longitude"], z)
    x0, y0, x1, y1 = x0 - ox, y0 - oy, x1 - ox, y1 - oy
    if x0 > 0 or y0 > 0 or x1 < CANVAS or y1 < CANVAS:
        shade = Image.new("L", img.size, 150)
        ImageDraw.Draw(shade).rectangle((x0, y0, x1, y1), fill=0)
        img.paste(Image.new("RGB", img.size, C["outside"]), (0, 0), shade)
        d = ImageDraw.Draw(img)
        border = [(x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)]
        for seg in dash_segments(border, 10 * K, 6 * K):
            d.line(seg, fill=C["edge"], width=int(1.6 * K))

    for lab in labels:
        lx0, ly0, lx1, ly1 = lab.rect
        if lx1 > ox and lx0 < ox + CANVAS and ly1 > oy and ly0 < oy + CANVAS:
            img.paste(lab.img, (int(lab.x - ox), int(lab.y - oy)), lab.img)

    return img.resize((TILE, TILE), Image.LANCZOS)


def encode(img):
    buf = BytesIO()
    img.save(buf, "WEBP", quality=WEBP_QUALITY, method=6)
    return buf.getvalue()


def main():
    region = load_json("region.json")
    osm = load_json("osm_raw.json")
    osm_base = osm.get("osm3s", {}).get("timestamp_osm_base", "unknown")
    areas, buildings, roads, dashed, waterways, rails, places, named_areas = parse(osm)
    print(f"areas={len(areas)} buildings={len(buildings)} roads={len(roads)} dashed={len(dashed)} "
          f"waterways={len(waterways)} rails={len(rails)} places={len(places)}")

    os.makedirs(ASSET_DIR, exist_ok=True)
    out_name = f"{region['id']}.mbtiles"
    tmp_path = os.path.join(HERE, out_name + ".tmp")
    if os.path.exists(tmp_path):
        os.remove(tmp_path)
    db = sqlite3.connect(tmp_path)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")

    started = time.time()
    total = 0
    tiles_per_zoom = {}
    for z in range(region["min_zoom"], region["max_zoom"] + 1):
        margin = 16 * K
        buckets = (
            bucket(areas, z, lambda a: a["outer"], margin),
            bucket(buildings, z, lambda b: b["outer"], margin) if z >= 15 else {},
            bucket(roads, z, lambda r: [r["coords"]], margin),
            bucket(dashed, z, lambda r: [r["coords"]], margin),
            bucket(waterways, z, lambda r: [r["coords"]], margin),
            bucket(rails, z, lambda r: [r["coords"]], margin),
        )
        labels = place_labels(z, places, roads, named_areas)
        x0, y0, x1, y1 = tile_range(region, z)
        count = 0
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                data = encode(render_tile(z, tx, ty, region, None, buckets, labels))
                db.execute("INSERT INTO tiles VALUES (?,?,?,?)", (z, tx, (2 ** z - 1) - ty, sqlite3.Binary(data)))
                count += 1
        tiles_per_zoom[z] = count
        total += count
        print(f"z{z}: {count} tiles, {len(labels)} labels, {time.time() - started:.0f}s")

    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    version = f"{datetime.now(timezone.utc):%Y.%m.%d}-r{FORMAT_VERSION}"
    bounds = f"{region['min_longitude']},{region['min_latitude']},{region['max_longitude']},{region['max_latitude']}"
    meta = {
        "name": f"GEO Tree {region['name']} field map",
        "format": "webp",
        "type": "baselayer",
        "version": version,
        "description": region["description"],
        "attribution": "\u00a9 OpenStreetMap contributors",
        "bounds": bounds,
        "center": f"{region['center_longitude']},{region['center_latitude']},15",
        "minzoom": str(region["min_zoom"]),
        "maxzoom": str(region["max_zoom"]),
    }
    db.executemany("INSERT INTO metadata VALUES (?,?)", list(meta.items()))
    db.commit()
    db.execute("VACUUM")
    db.close()

    final_path = os.path.join(ASSET_DIR, out_name)
    os.replace(tmp_path, final_path)
    size = os.path.getsize(final_path)
    with open(final_path, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()

    package = {
        "id": region["id"],
        "name": region["name"],
        "version": version,
        "file": out_name,
        "format": "mbtiles",
        "tile_encoding": "webp",
        "tile_pixels": TILE,
        "bytes": size,
        "sha256": sha,
        "min_zoom": region["min_zoom"],
        "max_zoom": region["max_zoom"],
        "min_latitude": region["min_latitude"],
        "max_latitude": region["max_latitude"],
        "min_longitude": region["min_longitude"],
        "max_longitude": region["max_longitude"],
        "center_latitude": region["center_latitude"],
        "center_longitude": region["center_longitude"],
        "tiles": total,
        "tiles_per_zoom": {str(k): v for k, v in tiles_per_zoom.items()},
        "source": "OpenStreetMap data via Overpass API bbox extract, rendered locally by tools/offline_map/build_offline_map.py",
        "osm_data_timestamp": osm_base,
        "license": "Map data \u00a9 OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright)",
        "attribution": "\u00a9 OpenStreetMap contributors",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    with open(os.path.join(ASSET_DIR, "metadata.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(package, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {final_path} ({size / 1e6:.1f} MB, {total} tiles, sha256 {sha[:12]}…) in {time.time() - started:.0f}s")


if __name__ == "__main__":
    sys.exit(main())
