"""Exact route checks and section splitting against original input geometry.

The graph proposes paths; this module evaluates them using their assigned DN.
The independent exported-result validator has its own implementation.
"""

from __future__ import annotations

import math
import numpy as np
from shapely.geometry import LineString, Point
from shapely.ops import substring
from shapely.strtree import STRtree

from . import rules

EPS = 0.003


def parts(g):
    return [] if g.is_empty else list(g.geoms) if hasattr(g, "geoms") else [g]


def angle(a, b, c):
    u = np.asarray(b) - a
    v = np.asarray(c) - b
    den = float(np.linalg.norm(u) * np.linalg.norm(v))
    return (
        0.0
        if den < 1e-12
        else math.degrees(math.acos(float(np.clip(np.dot(u, v) / den, -1, 1))))
    )


class Geometry:
    def __init__(self, model):
        self.m = model
        self.objects = [
            (
                o.id,
                o.geom,
                o.rule,
                (
                    0.0
                    if o.geom.geom_type in ("Polygon", "MultiPolygon")
                    else rules.EXISTING_WIDTH.get(o.rtype, 0) / 2
                ),
                i,
            )
            for i, o in enumerate(model.obstacles)
        ]
        self.objects += [
            (
                s.id,
                s.line,
                rules.RESTRICTIONS["heat_network"],
                rules.DESIGN_WIDTH[s.dn] / 2,
                None,
            )
            for s in model.sections.values()
        ]
        self.tree = STRtree([o[1] for o in self.objects])
        self.cache = {}
        self.buffer_cache = {}

    def describe(self, xy, dn, demand=None, candidate=None):
        key = (tuple(map(tuple, xy)), dn, demand, candidate.id if candidate else None)
        if key in self.cache:
            return self.cache[key]
        result = self._describe(xy, dn, demand, candidate)
        if len(self.cache) > 50000:
            self.cache.clear()
        self.cache[key] = result
        return result

    def _describe(self, xy, dn, demand, candidate):
        compact = []
        for point in xy:
            while (
                len(compact) >= 2
                and LineString([compact[-2], point]).distance(Point(compact[-1])) < 1e-5
            ):
                compact.pop()
            compact.append(tuple(point))
        xy = compact
        line = LineString(xy)
        length = line.length
        half = rules.DESIGN_WIDTH[dn] / 2
        errors = []
        events = []
        if length < 1e-7:
            return [], ["zero-length route"]
        if not line.is_simple:
            errors.append("self-intersecting route")
        if any(angle(a, b, c) > 90.001 for a, b, c in zip(xy, xy[1:], xy[2:])):
            errors.append("turn exceeds 90 degrees")
        host = self.m.demands[demand].host if demand is not None else None
        query = line.buffer(12 + half)
        for ix in self.tree.query(query, predicate="intersects"):
            oid, ob, rule, old_half, host_index = self.objects[int(ix)]
            clearance = (
                rules.oks_clearance(dn)
                if rule["clearance"] == "oks"
                else float(rule["clearance"])
            )
            req = clearance + half + old_half
            if rule["mode"] == "prohibited":
                if host is not None and host_index == host:
                    # Caller always supplies chains from consumer toward the attachment.
                    pt = self.m.demands[demand].pt
                    leg = LineString(xy[:2])
                    inter = leg.intersection(ob.boundary)
                    hits = [p for p in parts(inter) if p.geom_type == "Point"]
                    if (
                        len(hits) != 1
                        or abs(pt.distance(hits[0]) - pt.distance(ob.boundary)) > EPS
                    ):
                        errors.append(
                            f"demand {demand}: not a nearest-boundary straight entry"
                        )
                    if len(xy) > 2 and LineString(xy[1:]).distance(ob) < req - EPS:
                        errors.append(f"host {oid}: clearance outside final approach")
                elif line.distance(ob) < req - EPS:
                    errors.append(f"restriction {oid}: clearance")
                continue
            if line.distance(ob) >= req + EPS:
                continue
            poly = ob.geom_type in ("Polygon", "MultiPolygon")
            crossings = line.intersection(ob)
            permitted = []
            bk = (int(ix), dn)
            if bk not in self.buffer_cache:
                self.buffer_cache[bk] = ob.buffer(max(0, req - EPS))
            spacing_zone = self.buffer_cache[bk]

            def approach(point):
                # Exempt only the connected transverse straight approach, not a
                # neighborhood in which unrelated parallel segments could hide.
                for a, b in zip(xy, xy[1:]):
                    leg = LineString([a, b])
                    if leg.distance(point) > EPS:
                        continue
                    for portion in parts(leg.intersection(spacing_zone)):
                        if portion.distance(point) <= 0.02:
                            permitted.append(portion.buffer(0.025))

            for item in parts(crossings):
                if poly and item.geom_type == "LineString" and item.length > EPS:
                    a, b = sorted(
                        (
                            line.project(Point(item.coords[0])),
                            line.project(Point(item.coords[-1])),
                        )
                    )
                    lo, hi = a - rule["zone"], b + rule["zone"]
                    if lo < -EPS or hi > length + EPS:
                        errors.append(f"crossing {oid}: extension truncated")
                        continue
                    passage = substring(line, max(lo, 0), min(hi, length))
                    if (
                        passage.length
                        - Point(passage.coords[0]).distance(Point(passage.coords[-1]))
                        > EPS
                    ):
                        errors.append(f"crossing {oid}: passage bends")
                    entry = line.interpolate(a)
                    direction = np.asarray(passage.coords[-1]) - passage.coords[0]
                    for polygon in parts(ob):
                        for ring in [polygon.exterior, *polygon.interiors]:
                            for u, v in zip(ring.coords, list(ring.coords)[1:]):
                                seg = LineString([u, v])
                                if entry.distance(seg) < EPS:
                                    tangent = np.asarray(v) - u
                                    cos = abs(float(np.dot(direction, tangent))) / (
                                        np.linalg.norm(direction)
                                        * np.linalg.norm(tangent)
                                    )
                                    if math.degrees(
                                        math.acos(min(1.0, cos))
                                    ) + 0.001 < rule.get("angle", 0):
                                        errors.append(f"crossing {oid}: angle")
                    events.append((max(0, lo), min(length, hi), rule["k"], int(ix)))
                    approach(line.interpolate((a + b) / 2))
                elif not poly and item.geom_type == "Point":
                    if (
                        candidate is not None
                        and ob.distance(candidate.pt) <= 0.02
                        and item.distance(candidate.pt) <= 0.02
                    ):
                        continue
                    at = line.project(item)
                    lo, hi = at - rule["zone"], at + rule["zone"]
                    if lo < -EPS or hi > length + EPS:
                        errors.append(f"crossing {oid}: extension truncated")
                        continue
                    passage = substring(line, max(lo, 0), min(hi, length))
                    if (
                        passage.length
                        - Point(passage.coords[0]).distance(Point(passage.coords[-1]))
                        > EPS
                    ):
                        errors.append(f"crossing {oid}: passage bends")
                    events.append((max(0, lo), min(length, hi), rule["k"], int(ix)))
                    if rule.get("angle"):
                        direction = np.asarray(passage.coords[-1]) - passage.coords[0]
                        for segment in parts(ob):
                            for a, b in zip(segment.coords, list(segment.coords)[1:]):
                                if LineString([a, b]).distance(item) > EPS:
                                    continue
                                tangent = np.asarray(b) - a
                                cos = abs(float(np.dot(direction, tangent))) / (
                                    np.linalg.norm(direction) * np.linalg.norm(tangent)
                                )
                                if (
                                    math.degrees(math.acos(min(1.0, cos))) + 0.001
                                    < rule["angle"]
                                ):
                                    errors.append(f"crossing {oid}: angle")
                    approach(item)
                elif not poly and item.geom_type == "LineString" and item.length > EPS:
                    errors.append(f"overlap with linear object {oid}")
            if (
                candidate is not None
                and ob.distance(candidate.pt) <= 0.02
                and host_index is None
            ):
                approach(candidate.pt)
            near = line.intersection(spacing_zone)
            for area in permitted:
                near = near.difference(area)
            if near.length > EPS:
                errors.append(f"alongside clearance {oid}")
        cuts = sorted({0.0, length} | {x for lo, hi, k, ix in events for x in (lo, hi)})
        out = []
        for a, b in zip(cuts, cuts[1:]):
            if b - a < 1e-7:
                continue
            mid = (a + b) / 2
            active = [
                (k, ix) for lo, hi, k, ix in events if lo - 1e-7 <= mid <= hi + 1e-7
            ]
            piece = substring(line, a, b)
            coef = max((k for k, ix in active), default=1.0)
            out.append(
                dict(
                    xy=list(piece.coords),
                    length=b - a,
                    laying="special" if active else "base",
                    k_special=coef,
                    crossings=tuple(ix for k, ix in active),
                    cost=(b - a) * rules.cost_new(dn) * coef,
                    inside=False,
                )
            )
        return out, errors
