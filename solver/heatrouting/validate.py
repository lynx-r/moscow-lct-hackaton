"""Independent validation of serialized 2D results; never imports the optimizer.

Shares only the input contract and reference tables. Geometry checks are based on
original input, reprojected output, and topology reconstructed from node references.
"""

from __future__ import annotations

import collections
import json
import math
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import transform, substring

from . import rules
from .schema import read_input, scalar_id, number

TOL = 0.015  # metres; WGS84 rounding and GEOS arithmetic only


class ValidationError(ValueError):
    pass


def _load(source):
    if isinstance(source, (str, Path)):
        with open(source, encoding="utf-8") as f:
            return json.load(f)
    return source


def _parts(g):
    if g.is_empty:
        return []
    return list(g.geoms) if hasattr(g, "geoms") else [g]


def _turns(line):
    c = list(line.coords)
    for a, b, d in zip(c, c[1:], c[2:]):
        u, v = (b[0] - a[0], b[1] - a[1]), (d[0] - b[0], d[1] - b[1])
        norm = math.hypot(*u) * math.hypot(*v)
        if norm > 1e-12:
            yield math.degrees(
                math.acos(max(-1.0, min(1.0, (u[0] * v[0] + u[1] * v[1]) / norm)))
            )


def validate(source, result):
    """Return JSON-serializable diagnostics. Invalid outputs are never corrected."""
    inp = read_input(source)
    out = _load(result)
    errors = []

    def fail(msg):
        errors.append(msg)

    project = Transformer.from_crs(4326, 32637, always_xy=True).transform
    records = {}
    for f in inp["features"]:
        records[f["properties"]["id"]] = (
            f["properties"],
            transform(project, shape(f["geometry"])),
        )
    demands = {
        i: r
        for i, r in records.items()
        if r[0]["object_type"] == "oks_connection_point"
    }
    existing = {
        i: r for i, r in records.items() if r[0]["object_type"] == "heat_network"
    }
    chambers = {
        i: r for i, r in records.items() if r[0]["object_type"] == "heat_chamber"
    }
    obstacles = [
        (i, p, g) for i, (p, g) in records.items() if p["object_type"] == "restriction"
    ]
    if (
        not isinstance(out, dict)
        or out.get("type") != "FeatureCollection"
        or not isinstance(out.get("features"), list)
    ):
        return {
            "valid": False,
            "errors": ["Output must be a FeatureCollection"],
            "variants": [],
        }
    required = {
        "heat_network": [
            "start_node_id",
            "end_node_id",
            "flow_tph",
            "diameter",
            "length",
            "laying_method",
            "depth_start",
            "depth_end",
            "cost",
        ],
        "heat_chamber": ["diameter", "cost"],
        "technical_node": [],
        "variant_summary": [
            "rank",
            "construction_cost",
            "chamber_construction_cost",
            "existing_chamber_tie_in_count",
            "existing_chamber_tie_in_cost",
            "unconnected_penalty",
            "calculated_cost",
            "new_network_length",
            "score",
            "unconnected_oks_ids",
        ],
    }
    variants = collections.defaultdict(list)
    seen = set(records)
    for f in out["features"]:
        if (
            not isinstance(f, dict)
            or f.get("type") != "Feature"
            or not isinstance(f.get("properties"), dict)
        ):
            fail("Malformed output Feature")
            continue
        p = f["properties"]
        fid = p.get("id")
        typ = p.get("object_type")
        vid = p.get("variant_id")
        if not scalar_id(fid) or not scalar_id(vid):
            fail("Output id/variant_id must be finite numbers or strings")
            continue
        if fid in seen:
            fail(f"Duplicate output/input id {fid!r}")
        seen.add(fid)
        if typ not in required:
            fail(f"{fid}: unsupported output type {typ}")
            continue
        missing = [k for k in required[typ] if k not in p]
        if missing:
            fail(f"{fid}: missing attributes {missing}")
            continue
        numeric_fields = (
            ["flow_tph", "diameter", "length", "cost"]
            if typ == "heat_network"
            else (
                ["diameter", "cost"]
                if typ == "heat_chamber"
                else (
                    [k for k in required[typ] if k != "unconnected_oks_ids"]
                    if typ == "variant_summary"
                    else []
                )
            )
        )
        if any(not number(p[k]) or p[k] < 0 for k in numeric_fields):
            fail(f"{fid}: numeric attributes must be finite and nonnegative")
            continue
        if typ in ("heat_network", "heat_chamber") and (
            not isinstance(p["diameter"], int) or p["diameter"] not in rules.DNS
        ):
            fail(f"{fid}: diameter must be an integer from the table")
            continue
        if typ == "heat_network" and any(
            not scalar_id(p[k]) for k in ("start_node_id", "end_node_id")
        ):
            fail(f"{fid}: node references must be scalar IDs")
            continue
        if typ == "variant_summary" and (
            not isinstance(p["rank"], int)
            or not isinstance(p["existing_chamber_tie_in_count"], int)
            or not isinstance(p["unconnected_oks_ids"], list)
            or any(not scalar_id(i) for i in p["unconnected_oks_ids"])
        ):
            fail(f"{fid}: invalid rank, tie-in count or unconnected IDs")
            continue
        try:
            g = (
                None
                if f.get("geometry") is None
                else transform(project, shape(f["geometry"]))
            )
            expected = "LineString" if typ == "heat_network" else "Point"
            if typ == "variant_summary":
                if g is not None:
                    fail(f"{fid}: summary geometry must be null")
            elif (
                g is None
                or g.is_empty
                or not g.is_valid
                or g.geom_type != expected
                or g.has_z
            ):
                fail(f"{fid}: invalid 2D geometry")
                continue
        except Exception as exc:
            fail(f"{fid}: malformed geometry: {exc}")
            continue
        variants[vid].append((p, g))
    if not 1 <= len(variants) <= 3:
        fail("Expected one to three variants")
    summaries = []
    for vid, features in variants.items():
        prefix = f"variant {vid}: "

        def bad(msg):
            fail(prefix + msg)

        nodes = {**demands, **chambers}
        nodes.update(
            {
                p["id"]: (p, g)
                for p, g in features
                if p["object_type"] in ("heat_chamber", "technical_node")
            }
        )
        lines = {
            p["id"]: (p, g) for p, g in features if p["object_type"] == "heat_network"
        }
        sums = [p for p, g in features if p["object_type"] == "variant_summary"]
        if len(sums) != 1:
            bad("expected exactly one summary")
            continue
        summary = sums[0]
        summaries.append(summary)
        adjacency = collections.defaultdict(list)
        numeric_ok = True
        for eid, (p, g) in lines.items():
            for k in ("flow_tph", "diameter", "length", "cost"):
                if not number(p[k]) or p[k] < 0:
                    bad(f"{eid}: invalid {k}")
                    numeric_ok = False
            if p["diameter"] not in rules.DNS:
                bad(f"{eid}: unknown DN")
                numeric_ok = False
            if p["laying_method"] not in ("base", "special"):
                bad(f"{eid}: unknown laying_method")
            if p["depth_start"] is not None or p["depth_end"] is not None:
                bad(f"{eid}: 2D depths must be null")
            if g.length <= TOL or not g.is_simple:
                bad(f"{eid}: zero-length or self-intersecting pipe")
            if any(a > 90.01 for a in _turns(g)):
                bad(f"{eid}: turn exceeds 90 degrees")
            if number(p["length"]) and abs(p["length"] - g.length) > max(
                0.03, 1e-5 * g.length
            ):
                bad(f"{eid}: incorrect length")
            for k, xy in (
                ("start_node_id", g.coords[0]),
                ("end_node_id", g.coords[-1]),
            ):
                nid = p[k]
                if not scalar_id(nid) or nid not in nodes:
                    bad(f"{eid}: unresolved {k} {nid!r}")
                    numeric_ok = False
                    continue
                if nodes[nid][1].distance(Point(xy)) > TOL:
                    bad(f"{eid}: {k} differs from geometry endpoint")
            a, b = p["start_node_id"], p["end_node_id"]
            if scalar_id(a) and scalar_id(b) and a in nodes and b in nodes:
                adjacency[a].append((b, eid))
                adjacency[b].append((a, eid))
        if not numeric_ok:
            continue
        # Intersection checks include overlaps and T contacts, not just proper crossings.
        items = list(lines.items())
        for ix, (aid, (ap, ag)) in enumerate(items):
            for bid, (bp, bg) in items[ix + 1 :]:
                inter = ag.intersection(bg)
                if inter.is_empty:
                    continue
                common = {ap["start_node_id"], ap["end_node_id"]} & {
                    bp["start_node_id"],
                    bp["end_node_id"],
                }
                if (
                    inter.length > TOL
                    or not common
                    or any(
                        part.distance(nodes[n][1]) > TOL
                        for part in _parts(inter)
                        for n in common
                    )
                ):
                    bad(f"{aid}/{bid}: overlap or intersection outside a common node")
        for nid, (np_, ng) in nodes.items():
            if nid not in records and nid not in adjacency:
                bad(f"output node {nid}: no incident pipe")
        attachment = {}
        for n, links in adjacency.items():
            p, pt = nodes[n]
            kind = p["object_type"]
            incident = [
                (sp, sg) for sp, sg in existing.values() if sg.distance(pt) <= TOL
            ]
            old_degree = sum(
                (
                    1
                    if min(
                        Point(sg.coords[0]).distance(pt),
                        Point(sg.coords[-1]).distance(pt),
                    )
                    <= TOL
                    else 2
                )
                for sp, sg in incident
            )
            if kind == "heat_chamber":
                if len(links) + old_degree > 4:
                    bad(f"chamber {n}: degree exceeds four")
                if n in chambers or incident:
                    attachment[n] = incident
                if n not in chambers:
                    dn = max(
                        [lines[e][0]["diameter"] for _, e in links]
                        + [sp["diameter"] for sp, sg in incident]
                    )
                    if p["diameter"] != dn or p["cost"] != rules.chamber_cost(dn):
                        bad(f"chamber {n}: incorrect DN/cost")
                    if len(links) < 3 and not incident:
                        bad(f"chamber {n}: no branch or existing-network attachment")
            elif kind == "technical_node":
                if len(links) != 2:
                    bad(f"technical node {n}: must have degree two")
            elif kind == "oks_connection_point" and len(links) != 1:
                bad(f"demand {n}: must be a leaf")
            if len(links) == 2 and kind == "technical_node":
                coords = []
                for _, e in links:
                    ep, eg = lines[e]
                    c = list(eg.coords)
                    if ep["start_node_id"] != n:
                        c.reverse()
                    coords.append(c)
                joined = LineString(list(reversed(coords[0])) + coords[1][1:])
                if any(t > 90.01 for t in _turns(joined)):
                    bad(f"technical node {n}: turn exceeds 90 degrees")
        # A new attachment inside 10 m must use an eligible existing chamber.
        for n in attachment:
            if n in chambers:
                continue
            pt = nodes[n][1]
            for cid, (cp, cg) in chambers.items():
                old = sum(
                    (
                        1
                        if min(
                            Point(sg.coords[0]).distance(cg),
                            Point(sg.coords[-1]).distance(cg),
                        )
                        <= TOL
                        else 2
                    )
                    for sp, sg in existing.values()
                    if sg.distance(cg) <= TOL
                )
                if (
                    pt.distance(cg) <= 10 + TOL
                    and old + len(adjacency.get(cid, [])) + len(adjacency[n]) <= 4
                ):
                    bad(f"new chamber {n}: eligible existing chamber {cid} within 10 m")
        reached = set()
        parent = {}
        edge_to = {}
        roots = []
        for n in adjacency:
            if n in reached:
                continue
            component = set()
            stack = [n]
            while stack:
                v = stack.pop()
                if v in component:
                    continue
                component.add(v)
                stack.extend(u for u, e in adjacency[v])
            reached |= component
            rs = component & set(attachment)
            if len(rs) != 1:
                bad("each new component must have exactly one attachment")
                continue
            root = next(iter(rs))
            roots.append(root)
            order = [root]
            parent[root] = None
            for v in order:
                for u, e in adjacency[v]:
                    if u == parent[v]:
                        continue
                    if u in parent:
                        bad("new network contains a cycle")
                        continue
                    parent[u] = v
                    edge_to[u] = e
                    order.append(u)
            flow = {
                v: demands[v][0]["flow_tph"] if v in demands else 0.0 for v in order
            }
            for v in reversed(order[1:]):
                ep, eg = lines[edge_to[v]]
                if abs(ep["flow_tph"] - flow[v]) > 1e-5:
                    bad(f"{edge_to[v]}: incorrect downstream flow")
                if flow[v] > rules.capacity(ep["diameter"]) + 1e-8:
                    bad(f"{edge_to[v]}: capacity exceeded")
                flow[parent[v]] += flow[v]
            # Maximal constant-flow chains: independently calculate minimal admissible DN.
            # Each chain starts at a leaf/branch and ends at the next flow-change/root.
            computed = {}
            run = {}
            for bottom in reversed(order[1:]):
                if (
                    len([u for u, e in adjacency[bottom] if parent.get(u) == bottom])
                    == 1
                    and bottom not in demands
                ):
                    continue
                chain = []
                v = bottom
                while v != root:
                    chain.append(v)
                    up = parent[v]
                    if (
                        up == root
                        or len([u for u, e in adjacency[up] if parent.get(u) == up])
                        != 1
                        or up in demands
                    ):
                        break
                    v = up
                kids = [u for u, e in adjacency[bottom] if parent.get(u) == bottom]
                L = sum(lines[edge_to[v]][1].length for v in chain)
                below = max((computed[u] for u in kids if u in computed), default=0)
                chosen = None
                for dn in rules.DNS:
                    continuing = max(
                        (run[u] for u in kids if computed.get(u) == dn), default=0.0
                    )
                    if (
                        dn >= below
                        and rules.capacity(dn) + 1e-8 >= flow[bottom]
                        and L + continuing <= rules.max_len(dn) + 0.04
                    ):
                        chosen = dn
                        break
                if chosen is None:
                    bad("no admissible DN for constant-flow chain")
                    continue
                for v in chain:
                    if lines[edge_to[v]][0]["diameter"] != chosen:
                        bad(
                            f"{edge_to[v]}: non-minimal or changing DN on constant-flow chain"
                        )
                computed[chain[-1]] = chosen
                run[chain[-1]] = L + max(
                    (run[u] for u in kids if computed.get(u) == chosen), default=0.0
                )
        connected = set(demands) & reached
        missing = set(demands) - connected
        stated = summary["unconnected_oks_ids"]
        if (
            not isinstance(stated, list)
            or any(not scalar_id(i) for i in stated)
            or set(stated) != missing
            or len(stated) != len(missing)
        ):
            bad("incorrect unconnected_oks_ids")
        # Geometric legality and price, including crossing intervals across technical nodes.
        expected_cost = _geometry(
            lines, nodes, adjacency, attachment, obstacles, existing, demands, bad
        )
        new_ch = sum(
            p["cost"] for p, g in features if p["object_type"] == "heat_chamber"
        )
        tie_count = sum(len(adjacency[c]) for c in chambers)
        tie_cost = tie_count * rules.TIE_IN_COST
        construction = expected_cost + new_ch + tie_cost
        penalty = sum(rules.penalty(demands[d][0]["flow_tph"]) for d in missing)
        length = sum(g.length for p, g in lines.values())
        cost = construction + penalty
        expected = {
            "construction_cost": construction,
            "chamber_construction_cost": new_ch,
            "existing_chamber_tie_in_count": tie_count,
            "existing_chamber_tie_in_cost": tie_cost,
            "unconnected_penalty": penalty,
            "calculated_cost": cost,
            "new_network_length": length,
            "score": rules.score(cost, length),
        }
        for key, value in expected.items():
            if not number(summary[key]) or abs(summary[key] - value) > max(
                0.03 if key != "score" else 1e-5, abs(value) * 2e-6
            ):
                bad(f"incorrect summary {key}: expected {value:.6f}")
    if sorted(s.get("rank", 0) for s in summaries) != list(
        range(1, len(summaries) + 1)
    ):
        fail("Ranks must be unique consecutive integers from one")
    ranked = sorted(summaries, key=lambda s: s.get("rank", 0))
    if any(
        a.get("score", math.inf) > b.get("score", -math.inf) + 1e-6
        for a, b in zip(ranked, ranked[1:])
    ):
        fail("Ranks do not follow increasing score")
    return {
        "valid": not errors,
        "spec_version": rules.SPEC_VERSION,
        "errors": errors,
        "variants": [s.get("variant_id") for s in summaries],
    }


def require_valid(source, result):
    report = validate(source, result)
    if not report["valid"]:
        raise ValidationError("Output rejected:\n" + "\n".join(report["errors"][:30]))
    return report


def _geometry(lines, nodes, adjacency, attachments, obstacles, existing, demands, bad):
    """Check maximal chains across technical nodes against original input geometry."""
    used = set()
    total = 0.0
    for first, (fp, fg) in lines.items():
        if first in used:
            continue
        # Walk backwards through technical nodes to a physical endpoint.
        start = fp["start_node_id"]
        previous = first
        seen = set()
        while (
            start in nodes
            and nodes[start][0]["object_type"] == "technical_node"
            and len(adjacency[start]) == 2
            and start not in seen
        ):
            seen.add(start)
            nxt = [(u, e) for u, e in adjacency[start] if e != previous]
            if not nxt:
                break
            start, previous = nxt[0]
        if start not in nodes:
            continue
        coords = []
        pieces = []
        current = start
        previous = None
        offset = 0.0
        while True:
            options = [
                (u, e) for u, e in adjacency[current] if e != previous and e not in used
            ]
            if not options:
                break
            u, e = options[0]
            p, g = lines[e]
            used.add(e)
            c = list(g.coords)
            if p["start_node_id"] != current:
                c.reverse()
            coords.extend(c if not coords else c[1:])
            pieces.append((p, offset, offset + g.length))
            offset += g.length
            previous, current = e, u
            if nodes[current][0]["object_type"] != "technical_node":
                break
        if len(coords) < 2:
            continue
        # Technical splits introduce collinear vertices; they do not end a
        # straight approach. Collapse only sub-millimetre serialization noise.
        straight = []
        for coordinate in coords:
            while (
                len(straight) >= 2
                and Point(straight[-1]).distance(LineString([straight[-2], coordinate]))
                < 0.0001
            ):
                straight.pop()
            straight.append(coordinate)
        coords = straight
        chain = LineString(coords)
        length = chain.length
        dn = pieces[0][0]["diameter"]
        half = rules.DESIGN_WIDTH[dn] / 2
        events = []
        own = {}
        for endpoint in (start, current):
            if endpoint in demands:
                for oid, p, g in obstacles:
                    if p["restriction_type"] == "oks" and g.covers(
                        demands[endpoint][1]
                    ):
                        own[oid] = endpoint
        for oid, p, ob in obstacles:
            rt = p["restriction_type"]
            rule = rules.restriction_rule(rt)
            if rule["mode"] == "prohibited":
                clearance = (
                    rules.oks_clearance(dn)
                    if rule["clearance"] == "oks"
                    else rule["clearance"]
                )
                if oid in own:
                    d = own[oid]
                    cc = coords if d == start else list(reversed(coords))
                    leg = LineString(cc[:2])
                    point = demands[d][1]
                    x = leg.intersection(ob.boundary)
                    pts = [g for g in _parts(x) if g.geom_type == "Point"]
                    if (
                        len(pts) != 1
                        or abs(point.distance(pts[0]) - point.distance(ob.boundary))
                        > TOL
                    ):
                        bad(
                            f"demand {d}: final entry must use a nearest building boundary point"
                        )
                    if (
                        len(cc) > 2
                        and LineString(cc[1:]).distance(ob) < clearance + half - TOL
                    ):
                        bad(
                            f"demand {d}: building exemption extends beyond final straight approach"
                        )
                elif chain.distance(ob) < clearance + half - TOL:
                    bad(
                        f"pipe chain at {start}: clearance violation at restriction {oid}"
                    )
                continue
            events.extend(_crossing_events(chain, ob, rule, half, oid, bad))
        for oid, (p, ob) in existing.items():
            root_points = [
                nodes[n][1]
                for n in (start, current)
                if n in attachments and ob.distance(nodes[n][1]) <= TOL
            ]
            events.extend(
                _crossing_events(
                    chain,
                    ob,
                    rules.RESTRICTIONS["heat_network"],
                    half,
                    oid,
                    bad,
                    old_half=rules.DESIGN_WIDTH[p["diameter"]] / 2,
                    root_points=root_points,
                )
            )
        for p, a, b in pieces:
            cuts = sorted(
                {a, b} | {max(a, min(b, x)) for lo, hi, k in events for x in (lo, hi)}
            )
            cost = 0.0
            methods = set()
            for x, y in zip(cuts, cuts[1:]):
                if y - x < 1e-7:
                    continue
                mid = (x + y) / 2
                ks = [k for lo, hi, k in events if lo - 1e-7 <= mid <= hi + 1e-7]
                if y - x > TOL:
                    methods.add("special" if ks else "base")
                cost += (y - x) * rules.cost_new(p["diameter"]) * max(ks, default=1.0)
            if methods != {p["laying_method"]}:
                bad(
                    f"{p['id']}: incorrect laying_method or missing crossing boundary split"
                )
            # Even equal coefficients require a split when the set of crossed objects changes.
            if any(a + TOL < x < b - TOL for lo, hi, k in events for x in (lo, hi)):
                bad(f"{p['id']}: missing technical node at crossing boundary")
            if abs(p["cost"] - cost) > max(3.0, cost * 2e-6):
                bad(f"{p['id']}: incorrect pipe cost")
            total += cost
    return total


def _crossing_events(chain, ob, rule, half, oid, bad, old_half=None, root_points=()):
    events = []
    is_poly = ob.geom_type in ("Polygon", "MultiPolygon")
    width = (
        old_half
        if old_half is not None
        else rules.EXISTING_WIDTH.get(
            next((k for k, v in rules.RESTRICTIONS.items() if v is rule), ""), 0
        )
        / 2
    )
    # Restriction dictionaries from the registry have stable identity; heat sections pass width explicitly.
    distance = float(rule["clearance"]) + half + (width if not is_poly else 0)
    intersection = chain.intersection(ob)
    allowed = []
    spacing_zone = ob.buffer(max(0, distance - TOL))

    def crossing_approach(point):
        cc = list(chain.coords)
        for a, b in zip(cc, cc[1:]):
            leg = LineString([a, b])
            if leg.distance(point) > TOL:
                continue
            for interval in _parts(leg.intersection(spacing_zone)):
                if interval.distance(point) <= TOL:
                    allowed.append(interval.buffer(TOL * 2))

    for g in _parts(intersection):
        if is_poly and g.geom_type == "LineString" and g.length > TOL:
            a, b = sorted(
                (chain.project(Point(g.coords[0])), chain.project(Point(g.coords[-1])))
            )
            lo, hi = a - rule["zone"], b + rule["zone"]
            if lo < -TOL or hi > chain.length + TOL:
                bad(f"crossing {oid}: missing extension outside polygon")
            passage = substring(chain, max(0, lo), min(chain.length, hi))
            if (
                passage.length
                - Point(passage.coords[0]).distance(Point(passage.coords[-1]))
                > TOL
            ):
                bad(f"crossing {oid}: special passage must be straight")
            entry = chain.interpolate(a)
            direction = (
                passage.coords[-1][0] - passage.coords[0][0],
                passage.coords[-1][1] - passage.coords[0][1],
            )
            tangents = []
            for poly in _parts(ob):
                for ring in [poly.exterior, *poly.interiors]:
                    for p, q in zip(ring.coords, list(ring.coords)[1:]):
                        seg = LineString([p, q])
                        if entry.distance(seg) <= TOL:
                            tangents.append((q[0] - p[0], q[1] - p[1]))
            for t in tangents:
                cos = abs(sum(x * y for x, y in zip(t, direction))) / (
                    math.hypot(*t) * math.hypot(*direction)
                )
                if math.degrees(math.acos(min(1.0, cos))) + 0.01 < rule.get("angle", 0):
                    bad(f"crossing {oid}: entry angle below required minimum")
            events.append((max(0, lo), min(chain.length, hi), rule["k"]))
            crossing_approach(chain.interpolate((a + b) / 2))
        elif not is_poly and g.geom_type == "Point":
            if any(g.distance(pt) <= TOL for pt in root_points):
                continue
            at = chain.project(g)
            lo, hi = at - rule["zone"], at + rule["zone"]
            if lo < -TOL or hi > chain.length + TOL:
                bad(f"crossing {oid}: missing utility crossing extension")
            passage = substring(chain, max(0, lo), min(chain.length, hi))
            if (
                passage.length
                - Point(passage.coords[0]).distance(Point(passage.coords[-1]))
                > TOL
            ):
                bad(f"crossing {oid}: utility crossing must be straight")
            events.append((max(0, lo), min(chain.length, hi), rule["k"]))
            if rule.get("angle"):
                direction = (
                    passage.coords[-1][0] - passage.coords[0][0],
                    passage.coords[-1][1] - passage.coords[0][1],
                )
                for line in _parts(ob):
                    for a, b in zip(line.coords, list(line.coords)[1:]):
                        if LineString([a, b]).distance(g) > TOL:
                            continue
                        tangent = (b[0] - a[0], b[1] - a[1])
                        cosine = abs(sum(x * y for x, y in zip(direction, tangent))) / (
                            math.hypot(*direction) * math.hypot(*tangent)
                        )
                        if (
                            math.degrees(math.acos(min(1.0, cosine))) + 0.01
                            < rule["angle"]
                        ):
                            bad(f"crossing {oid}: angle below minimum")
            crossing_approach(g)
        elif not is_poly and g.geom_type == "LineString" and g.length > TOL:
            bad(f"pipe overlaps existing linear object {oid}")
    # Allow a direct approach to the attachment, not a parallel run along the network.
    for pt in root_points:
        crossing_approach(pt)
    near = chain.intersection(spacing_zone)
    for area in allowed:
        near = near.difference(area)
    if near.length > TOL:
        bad(f"pipe runs alongside {oid} without required clearance")
    return events
