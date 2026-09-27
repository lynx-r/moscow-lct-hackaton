"""Ingest and canonical model.

Accepts both the appendix schema and the sample schema and derives what is missing:
topology from geometry, chamber DN from adjoining sections, host building from containment,
existing flow from a stated policy. Every derivation is recorded in `diagnostics`.
"""
from __future__ import annotations

import collections
import json
from dataclasses import dataclass, field

import pyproj
from shapely.geometry import LineString, Point, shape
from shapely.geometry.base import BaseGeometry
from shapely.ops import transform

from . import rules


@dataclass
class Demand:
    id: object
    flow: float
    pt: Point
    host: int | None = None          # index into Model.obstacles
    host_prov: str = "derived"


@dataclass
class Section:
    id: object
    dn: int
    line: LineString                 # oriented from up_node (toward source) to down_node
    length: float = 0.0
    up_node: int = -1
    down_node: int = -1
    flow_existing: float = 0.0
    flow_prov: str = "derived"
    connected: bool = True


@dataclass
class Chamber:
    id: object
    pt: Point
    node: int = -1
    dn: int = 0
    dn_prov: str = "derived"
    degree: int = 0


@dataclass
class Obstacle:
    id: object
    rtype: str
    geom: BaseGeometry
    rule: dict
    future: bool = False


@dataclass
class Model:
    source_id: object
    source_pt: Point
    sections: dict = field(default_factory=dict)
    chambers: dict = field(default_factory=dict)
    demands: dict = field(default_factory=dict)
    obstacles: list = field(default_factory=list)
    nodes: list = field(default_factory=list)                 # node index -> Point
    node_sections: dict = field(default_factory=dict)         # node -> [section ids]
    parent_section: dict = field(default_factory=dict)        # node -> section id toward source (None at source)
    node_chamber: dict = field(default_factory=dict)          # node -> chamber id
    source_node: int = -1
    diagnostics: list = field(default_factory=list)
    to_wgs: object = None

    def ancestors(self, section_id) -> list:
        """Sections strictly upstream of `section_id`, nearest first, ending at the source (cached)."""
        cache = self.__dict__.setdefault("_anc_cache", {})
        if section_id in cache:
            return cache[section_id]
        out = cache[section_id] = []
        node = self.sections[section_id].up_node
        while node != self.source_node and node >= 0:
            sid = self.parent_section.get(node)
            if sid is None or sid in out:
                break
            out.append(sid)
            node = self.sections[sid].up_node
        return out

    def existing_flow_total(self) -> float:
        return sum(d.flow for d in self.demands.values())


def load(path: str, existing_flow_policy: str = "zero", existing_flow_fraction: float = 0.0,
         snap_tol: float = 0.5) -> Model:
    from .schema import read_input
    fc = read_input(path)
    fwd = pyproj.Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
    inv = pyproj.Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
    P = lambda g: transform(fwd.transform, shape(g))
    diag = []
    by_type = collections.defaultdict(list)
    for f in fc["features"]:
        by_type[f["properties"].get("object_type")].append(f)

    srcs = by_type.get("source", [])
    if len(srcs) != 1:
        diag.append(f"expected one source, found {len(srcs)}; using the first")
    src = srcs[0]
    model = Model(source_id=src["properties"]["id"], source_pt=P(src["geometry"]), diagnostics=diag)
    model.to_wgs = inv.transform

    # obstacles: restrictions, existing OKS, future OKS polygons
    for f in by_type.get("restriction", []):
        rt = f["properties"].get("restriction_type", "unknown")
        rule = rules.restriction_rule(rt)
        if rule.get("unknown"):
            diag.append(f"restriction {f['properties']['id']}: unknown type '{rt}', treated as prohibited with 1 m clearance")
        model.obstacles.append(Obstacle(f["properties"]["id"], rt, P(f["geometry"]), rule))
    for f in by_type.get("oks_existing", []):
        model.obstacles.append(Obstacle(f["properties"]["id"], "oks", P(f["geometry"]), rules.restriction_rule("oks")))
    future_by_id = {}
    for f in by_type.get("oks_future", []):
        ob = Obstacle(f["properties"]["id"], "oks", P(f["geometry"]), rules.restriction_rule("oks"), future=True)
        model.obstacles.append(ob)
        future_by_id[f["properties"]["id"]] = (len(model.obstacles) - 1, f["properties"])
    if any(r.get("assumed") for r in (o.rule for o in model.obstacles)):
        diag.append("restriction type 'railway' handled with the tram-tracks rule (assumption Q27)")

    # sections
    for f in by_type.get("heat_network", []):
        p = f["properties"]
        geom = P(f["geometry"])
        if geom.geom_type != "LineString":
            diag.append(f"section {p['id']}: geometry {geom.geom_type} not supported, skipped")
            continue
        dn = int(p["diameter"])
        if dn not in rules.DNS:
            diag.append(f"section {p['id']}: DN{dn} not in the reference table; compared as DN{rules.dn_ge(dn)}")
        s = Section(p["id"], dn, geom, geom.length)
        s.flow_existing, s.flow_prov = 0.0, "excluded:current-spec"
        model.sections[p["id"]] = s

    # chambers
    for f in by_type.get("heat_chamber", []):
        p = f["properties"]
        c = Chamber(p["id"], P(f["geometry"]))
        if p.get("diameter") is not None:
            c.dn, c.dn_prov = int(p["diameter"]), "given"
        model.chambers[p["id"]] = c

    # demands: connection points carry the flow themselves (sample) or point at oks_future (appendix)
    for f in by_type.get("oks_connection_point", []):
        p = f["properties"]
        flow = p.get("flow_tph")
        if flow is None and p.get("oks_id") in future_by_id:
            flow = future_by_id[p["oks_id"]][1].get("flow_tph")
        if flow is None:
            diag.append(f"connection point {p['id']}: no flow found, skipped")
            continue
        d = Demand(p["id"], float(flow), P(f["geometry"]))
        if p.get("oks_id") in future_by_id:
            d.host, d.host_prov = future_by_id[p["oks_id"]][0], "given"
        model.demands[p["id"]] = d

    model.input_data = fc
    _build_topology(model, snap_tol)
    _derive_hosts(model)
    _check_upstream_ids(model, by_type)
    return model


def _build_topology(model: Model, tol: float) -> None:
    nodes: list[Point] = []

    def node_of(pt: Point) -> int:
        for i, q in enumerate(nodes):
            if q.distance(pt) <= tol:
                return i
        nodes.append(pt)
        return len(nodes) - 1

    ends = {}
    for s in model.sections.values():
        a = node_of(Point(s.line.coords[0]))
        b = node_of(Point(s.line.coords[-1]))
        if a == b:
            model.diagnostics.append(f"section {s.id}: both ends snap to one node (length {s.length:.1f} m)")
        ends[s.id] = (a, b)
    model.nodes = nodes
    if not nodes:
        model.diagnostics.append("no existing network sections")
        return
    d_src = [model.source_pt.distance(q) for q in nodes]
    model.source_node = min(range(len(nodes)), key=d_src.__getitem__)
    if d_src[model.source_node] > 5.0:
        model.diagnostics.append(f"source is {d_src[model.source_node]:.1f} m from the nearest network node; that node is used as root")

    adj = collections.defaultdict(list)
    for sid, (a, b) in ends.items():
        adj[a].append((sid, b))
        adj[b].append((sid, a))
    seen_nodes = {model.source_node}
    seen_secs = set()
    model.parent_section = {model.source_node: None}
    queue = collections.deque([model.source_node])
    while queue:
        n = queue.popleft()
        for sid, m in adj[n]:
            if sid in seen_secs:
                continue
            seen_secs.add(sid)
            s = model.sections[sid]
            if m in seen_nodes:
                model.diagnostics.append(f"section {sid}: closes a cycle at node {m}; oriented away from the source")
                s.up_node, s.down_node = n, m
                _orient(s, nodes[n])
                continue
            seen_nodes.add(m)
            model.parent_section[m] = sid
            s.up_node, s.down_node = n, m
            _orient(s, nodes[n])
            queue.append(m)
    for sid, (a, b) in ends.items():
        if sid not in seen_secs:
            s = model.sections[sid]
            s.connected = False
            s.up_node, s.down_node = a, b
            _orient(s, nodes[a])
            model.diagnostics.append(f"section {sid}: not connected to the source (Q29)")
    model.node_sections = collections.defaultdict(list)
    for sid, s in model.sections.items():
        model.node_sections[s.up_node].append(sid)
        model.node_sections[s.down_node].append(sid)

    # gap report
    gaps = []
    for i, p in enumerate(nodes):
        for j in range(i + 1, len(nodes)):
            d = p.distance(nodes[j])
            if tol < d <= 2.0:
                gaps.append(d)
    if gaps:
        model.diagnostics.append(f"{len(gaps)} node pair(s) between {tol} and 2 m apart, not merged")

    # Geometric incidence: a line passing through a chamber contributes two rays.
    for c in model.chambers.values():
        incident = [s for s in model.sections.values() if s.line.distance(c.pt) <= 0.02]
        c.degree = sum(1 if min(c.pt.distance(Point(s.line.coords[0])),
                               c.pt.distance(Point(s.line.coords[-1]))) <= 0.02 else 2 for s in incident)
        c.dn = max((s.dn for s in incident), default=0)
        c.dn_prov = "derived"
        if incident:
            c.node = node_of(c.pt)
            model.node_chamber[c.node] = c.id
            model.node_sections[c.node] = [s.id for s in incident]
        else:
            model.diagnostics.append(f"chamber {c.id}: not on existing network; cannot be a tie-in")

    # non-monotone DN toward the source (Q28)
    bad = 0
    for s in model.sections.values():
        par = model.parent_section.get(s.up_node)
        if par is not None and model.sections[par].dn < s.dn:
            bad += 1
    if bad:
        model.diagnostics.append(f"{bad} section(s) have a larger DN than their upstream neighbour (Q28)")


def _orient(s: Section, up_pt: Point) -> None:
    c = list(s.line.coords)
    if Point(c[0]).distance(up_pt) > Point(c[-1]).distance(up_pt):
        s.line = LineString(c[::-1])


def _derive_hosts(model: Model) -> None:
    n_inside = 0
    for d in model.demands.values():
        if d.host is not None:
            continue
        for i, ob in enumerate(model.obstacles):
            if ob.rtype == "oks" and ob.geom.covers(d.pt):
                d.host = i
                n_inside += 1
                break
    if n_inside:
        model.diagnostics.append(f"{n_inside} of {len(model.demands)} connection points lie inside a building; host derived by containment (Q5)")


def _check_upstream_ids(model: Model, by_type) -> None:
    given = sum(1 for f in by_type.get("heat_network", []) if f["properties"].get("upstream_object_id") is not None)
    if given == 0:
        model.diagnostics.append("upstream_object_id absent; topology derived from geometry and oriented from the source (Q2)")
    else:
        mism = 0
        for f in by_type.get("heat_network", []):
            sid = f["properties"]["id"]
            up = f["properties"].get("upstream_object_id")
            if up is None or sid not in model.sections:
                continue
            s = model.sections[sid]
            par = model.parent_section.get(s.up_node)
            chamber = model.node_chamber.get(s.up_node)
            if up not in (par, chamber, model.source_id):
                mism += 1
        if mism:
            model.diagnostics.append(f"{mism} upstream_object_id values disagree with the geometric topology")
