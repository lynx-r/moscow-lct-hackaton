"""Current appendix export. Validate serialized geometry before atomic publication."""

from __future__ import annotations
import json
import os
import tempfile
from pathlib import Path
from shapely.geometry import LineString, Point, mapping
from shapely.ops import transform
from . import rules


def _round(c):
    return [_round(x) for x in c] if isinstance(c, (list, tuple)) else round(c, 10)


def _feat(geom, props, to_wgs):
    g = None if geom is None else mapping(transform(to_wgs, geom))
    if g is not None:
        g["coordinates"] = _round(g["coordinates"])
    return {"type": "Feature", "geometry": g, "properties": props}


def to_collection(world, evs):
    feats = []
    used = (
        set(world.model.demands) | set(world.model.chambers) | set(world.model.sections)
    )
    if hasattr(world.model, "input_data"):
        used = {f["properties"]["id"] for f in world.model.input_data["features"]}

    def unique(base):
        name = base
        seq = 1
        while name in used:
            name = f"{base}_{seq}"
            seq += 1
        used.add(name)
        return name

    for rank, ev in enumerate(sorted(evs, key=lambda e: e.score), 1):
        if ev.violations:
            raise ValueError(
                "Cannot export a plan with violations: " + str(ev.violations[:5])
            )
        vid = str(rank)
        node_ids = {}

        def feature(g, p):
            feats.append(_feat(g, dict(p, variant_id=vid), world.model.to_wgs))

        for gi, g in enumerate(ev.groups):
            for did, n in g.tree.term_node.items():
                node_ids[(gi, n)] = did
            if g.cand.is_chamber:
                node_ids[(gi, g.tree.root)] = g.cand.chamber_id
        for c in ev.chambers:
            cid = unique(f"v{vid}-ch-{len(node_ids)+1}")
            for gi in c.get("shared_gis", [c["gi"]]):
                n = ev.groups[gi].tree.root if c["kind"] == "tie_in" else c["node"]
                node_ids[(gi, n)] = cid
            feature(
                Point(c["xy"]),
                dict(
                    id=cid, object_type="heat_chamber", diameter=c["dn"], cost=c["cost"]
                ),
            )
        # Each evaluator section is a polyline on one constant-flow chain, already
        # split at every special passage boundary. Ordinary turns are internal vertices.
        by_chain = {}
        for s in ev.sections:
            by_chain.setdefault((s["gi"], s["child"], s["parent"]), []).append(s)
        for (gi, bottom, top), pieces in by_chain.items():
            if (gi, bottom) not in node_ids or (gi, top) not in node_ids:
                raise ValueError("Unclassified physical endpoint in evaluated tree")
            previous = node_ids[(gi, bottom)]
            for i, s in enumerate(pieces):
                if i == len(pieces) - 1:
                    end = node_ids[(gi, top)]
                else:
                    end = unique(f"v{vid}-tn-{len(feats)+1}")
                    feature(
                        Point(s["xy"][-1]), dict(id=end, object_type="technical_node")
                    )
                feature(
                    LineString(s["xy"]),
                    dict(
                        id=unique(f"v{vid}-pipe-{len(feats)+1}"),
                        object_type="heat_network",
                        start_node_id=previous,
                        end_node_id=end,
                        flow_tph=s["flow"],
                        diameter=s["dn"],
                        length=round(s["length"], 6),
                        laying_method=s["laying"],
                        depth_start=None,
                        depth_end=None,
                        cost=round(s["cost"], 4),
                    ),
                )
                previous = end
        t = ev.totals
        feature(
            None,
            dict(
                id=unique(f"v{vid}-summary"),
                object_type="variant_summary",
                rank=rank,
                construction_cost=round(
                    t["construction"] + t["chambers"] + t["tie_in"], 4
                ),
                chamber_construction_cost=t["chambers"],
                existing_chamber_tie_in_count=t["tie_in_count"],
                existing_chamber_tie_in_cost=t["tie_in"],
                unconnected_penalty=t["penalty"],
                calculated_cost=round(t["cost"], 4),
                new_network_length=round(t["new_length"], 6),
                score=round(t["score"], 9),
                unconnected_oks_ids=list(ev.unrouted),
                spec_version=rules.SPEC_VERSION,
                unconnected_reasons=[
                    dict(
                        id=d,
                        reason=getattr(world, "entry_failures", {}).get(
                            d, "no compliant route found within the configured search"
                        ),
                    )
                    for d in ev.unrouted
                ],
            ),
        )
    return {"type": "FeatureCollection", "features": feats}


def to_geojson(world, evs, path):
    from .validate import require_valid

    result = to_collection(world, evs)
    report = require_valid(world.model.input_data, result)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix=".result-", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, allow_nan=False)
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)
    return report
