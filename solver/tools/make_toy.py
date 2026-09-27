"""Synthetic city-scale dataset in the sample schema.

    python tools/make_toy.py --blocks 30 20 --demands 120 --out data/toy_large.geojson

Block grid with rectangular buildings (restriction_type=oks), streets between blocks, an existing heating network as
a tree along the streets (DN500 trunk, DN300/400 branches, chambers at junctions), demands inside random buildings,
one water polygon and one railway corridor at the edge. Coordinates are generated in EPSG:32637 and written in WGS84.
"""
from __future__ import annotations

import argparse
import json
import random

import pyproj
from shapely.geometry import LineString, MultiPolygon, Point, Polygon, box, mapping
from shapely.ops import transform


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--blocks", type=int, nargs=2, default=[30, 20])
    ap.add_argument("--block", type=float, default=90.0, help="block size, m")
    ap.add_argument("--street", type=float, default=24.0, help="street width, m")
    ap.add_argument("--demands", type=int, default=120)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default="data/toy_large.geojson")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    X0, Y0 = 414000.0, 6172600.0
    bx, by = args.blocks
    pitch = args.block + args.street
    inv = pyproj.Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
    feats, fid = [], 1

    def feat(geom, props):
        nonlocal fid
        g = transform(inv, geom)
        f = {"type": "Feature", "properties": {"id": fid, **props}, "geometry": mapping(g)}
        feats.append(f); fid += 1
        return f

    # buildings: 1 to 3 per block, courtyard-like layouts
    buildings = []
    for i in range(bx):
        for j in range(by):
            x = X0 + i * pitch; y = Y0 + j * pitch
            n = rng.choice([1, 2, 2, 3])
            if n == 1:
                polys = [box(x + 8, y + 8, x + args.block - 8, y + args.block - 8)]
            elif n == 2:
                polys = [box(x + 6, y + 6, x + args.block - 6, y + args.block / 2 - 6),
                         box(x + 6, y + args.block / 2 + 6, x + args.block - 6, y + args.block - 6)]
            else:
                polys = [box(x + 6, y + 6, x + args.block / 2 - 6, y + args.block - 6),
                         box(x + args.block / 2 + 6, y + 6, x + args.block - 6, y + args.block / 2 - 6),
                         box(x + args.block / 2 + 6, y + args.block / 2 + 6, x + args.block - 6, y + args.block - 6)]
            for p in polys:
                buildings.append(p)
                feat(MultiPolygon([p]), {"object_type": "restriction", "restriction_type": "oks", "address": f"block {i}-{j}"})
    # water on the west, railway on the south
    feat(MultiPolygon([box(X0 - 300, Y0 - 100, X0 - 60, Y0 + by * pitch + 100)]), {"object_type": "restriction", "restriction_type": "water"})
    feat(MultiPolygon([Polygon([(X0 - 50, Y0 - 80), (X0 + bx * pitch + 50, Y0 - 80), (X0 + bx * pitch + 50, Y0 - 40), (X0 - 50, Y0 - 40)])]),
         {"object_type": "restriction", "restriction_type": "railway"})
    # existing network: trunk along street column 0 going north, branches east every 4th street row
    sx = X0 - args.street / 2
    source = Point(sx, Y0 - 20)
    feat(source, {"object_type": "source", "name": "toy source"})
    sec_id_start = fid
    trunk_y = [Y0 - 20] + [Y0 + j * pitch - args.street / 2 for j in range(0, by + 1, 4)]
    nodes_chambers = []
    for a, b in zip(trunk_y, trunk_y[1:]):
        feat(LineString([(sx, a), (sx, b)]), {"object_type": "heat_network", "diameter": 500})
    for j in range(0, by + 1, 4):
        y = Y0 + j * pitch - args.street / 2
        nodes_chambers.append((sx, y))
        length_blocks = rng.randint(bx // 3, bx)
        xs = [sx] + [X0 + i * pitch - args.street / 2 for i in range(1, length_blocks + 1)]
        for k, (a, b) in enumerate(zip(xs, xs[1:])):
            dn = 400 if k < len(xs) // 2 else 300
            feat(LineString([(a, y), (b, y)]), {"object_type": "heat_network", "diameter": dn})
            if k % 3 == 2:
                nodes_chambers.append((b, y))
    for (x, y) in nodes_chambers:
        feat(Point(x, y), {"object_type": "heat_chamber"})
    # demands inside random buildings, 2 to 60 t/h
    chosen = rng.sample(range(len(buildings)), min(args.demands, len(buildings)))
    for k in chosen:
        p = buildings[k]
        cx, cy = p.centroid.x, p.centroid.y
        minx, miny, maxx, maxy = p.bounds
        pt = Point(rng.uniform(minx + 3, maxx - 3), rng.uniform(miny + 3, maxy - 3))
        feat(pt, {"object_type": "oks_connection_point", "flow_tph": round(rng.uniform(2, 60), 2)})
    fc = {"type": "FeatureCollection", "name": "toy", "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}}, "features": feats}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(fc, f)
    print(f"{args.out}: {len(feats)} features, {len(buildings)} buildings, {len(chosen)} demands, "
          f"{sum(1 for f in feats if f['properties']['object_type']=='heat_network')} sections, {len(nodes_chambers)} chambers, "
          f"area {bx*pitch/1000:.1f} x {by*pitch/1000:.1f} km")


if __name__ == "__main__":
    main()
