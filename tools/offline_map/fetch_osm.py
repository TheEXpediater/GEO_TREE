"""
One-off OpenStreetMap extract for the offline field map (see build_offline_map.py).

Sends ONE Overpass API query for the bounding box in region.json and saves the answer as
osm_raw.json. This downloads OSM *data* (ODbL), not map tiles. Do not run it in a loop:
public Overpass instances are shared community infrastructure.
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]


def main():
    with open(os.path.join(HERE, "region.json"), encoding="utf-8") as f:
        r = json.load(f)
    bbox = f"{r['min_latitude']},{r['min_longitude']},{r['max_latitude']},{r['max_longitude']}"
    query = f"""[out:json][timeout:180][bbox:{bbox}];
(
  way["highway"]; way["building"]; way["landuse"]; way["natural"]; way["waterway"];
  way["leisure"]; way["amenity"]; way["railway"]; way["water"];
  relation["landuse"]; relation["natural"]; relation["leisure"]; relation["amenity"]; relation["building"];
  relation["type"="multipolygon"]["water"];
  node["place"]; node["amenity"="university"];
);
out geom;"""
    query_path = os.path.join(HERE, "overpass_query.txt")
    out_path = os.path.join(HERE, "osm_raw.json")
    with open(query_path, "w", encoding="utf-8") as f:
        f.write(query)
    # One attempt per mirror, with a pause: a busy server answers 504, which is not a reason to hammer it.
    for url in MIRRORS:
        result = subprocess.run(
            ["curl", "-sS", "-m", "300", "-w", "%{http_code}", "-A", "GEO-Tree-capstone-offline-map-builder/1.0",
             "--data-urlencode", f"data@{query_path}", url, "-o", out_path],
            capture_output=True, text=True,
        )
        ok = result.stdout.strip() == "200" and open(out_path, "rb").read(1) == b"{"
        print(f"{url}: HTTP {result.stdout.strip()} {'OK' if ok else result.stderr.strip()[:200]}")
        if ok:
            return 0
        time.sleep(30)
    print("All Overpass mirrors failed; try again later.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
