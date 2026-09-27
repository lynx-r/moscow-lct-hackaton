"""Per-run obstacles, graph vertices, private nearest-boundary entries and attachments."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import shapely
from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union
from shapely.strtree import STRtree

from . import rules
from .model import Model

V_CORNER, V_TERMINAL, V_CANDIDATE = 0, 1, 2


@dataclass
class Candidate:
    id: int
    section_id: object
    chainage: float  # from the up end of the section
    pt: Point
    chamber_id: object | None = None
    node: int | None = None
    vertex: int = -1

    @property
    def is_chamber(self) -> bool:
        return self.chamber_id is not None


@dataclass
class Params:
    half_width: float = 0.5  # half of the pipe-pair design width used for inflation, m
    simplify_tol: float = 0.08
    roi_margin: float = 120.0
    candidate_spacing: float = 25.0
    chamber_snap: float = 10.0  # appendix 8.2
    candidate_dedupe: float = 3.0
    vis_radius: float = 400.0
    k_inside: float = (
        1.0  # routing-metric weight of the in-building segment (cost stays per rules)
    )
    existing_flow_policy: str = "zero"
    seed: int = 1
    graph_dn: int | None = None
    clearance_margin: float = 0.04


class World:
    def __init__(self, model: Model, params: Params):
        from .geometry import Geometry

        self.model, self.params = model, params
        self.diagnostics = []
        self.geometry = Geometry(model)
        self.dn_class = (
            params.graph_dn
            or rules.dn_for_flow(model.existing_flow_total())
            or rules.DNS[-1]
        )
        self.oks_clearance = rules.oks_clearance(self.dn_class)
        self._inflate()
        self._roi()
        self._vertices()
        self._candidates()
        self._terminals()
        self.verts = np.asarray(self._xy, dtype=float).reshape(-1, 2)
        self.vkind = np.asarray(self._kind, dtype=int)
        self.special = self.vkind != V_CORNER
        self.host_of = {
            v: model.demands[d].host for d, v in self.terminal_vertex.items()
        }

    def _inflate(self):
        self.raw = []
        self.inflated = []
        self.inflate_dist = []
        self.hard = []
        for oid, geom, rule, old_half, host_index in self.geometry.objects:
            clr = (
                self.oks_clearance if rule["clearance"] == "oks" else rule["clearance"]
            )
            d = (
                clr
                + rules.DESIGN_WIDTH[self.dn_class] / 2
                + old_half
                + self.params.clearance_margin
            )
            # Simplification proposals are checked against original geometry. Extra
            # outward padding bounds the simplifier's displacement.
            g = geom.simplify(self.params.simplify_tol, preserve_topology=True).buffer(
                d + self.params.simplify_tol, join_style="mitre", mitre_limit=3.0
            )
            self.raw.append(geom)
            self.inflated.append(g)
            self.inflate_dist.append(d)
            self.hard.append(rule["mode"] == "prohibited")
        self.tree_infl = STRtree(self.inflated)
        self.infl_shrunk = [g.buffer(-0.00001) for g in self.inflated]
        self.tree_shrunk = STRtree(self.infl_shrunk)
        self.hard = np.asarray(self.hard)

    def _roi(self):
        geoms = [d.pt for d in self.model.demands.values()] + [
            s.line for s in self.model.sections.values()
        ]
        self.roi = unary_union(geoms).convex_hull.buffer(self.params.roi_margin)

    def _vertices(self):
        self._xy = []
        self._kind = []
        self._ref = []
        self.roi_obstacles = sorted(
            map(int, self.tree_infl.query(self.roi, predicate="intersects"))
        )
        union = unary_union([self.inflated[i] for i in self.roi_obstacles])
        for poly in getattr(union, "geoms", [union]):
            if poly.is_empty or poly.geom_type != "Polygon":
                continue
            poly = orient(poly, sign=1.0)
            for ring in [poly.exterior, *poly.interiors]:
                c = np.asarray(ring.coords[:-1])
                prev = np.roll(c, 1, axis=0)
                nxt = np.roll(c, -1, axis=0)
                cross = (c[:, 0] - prev[:, 0]) * (nxt[:, 1] - c[:, 1]) - (
                    c[:, 1] - prev[:, 1]
                ) * (nxt[:, 0] - c[:, 0])
                # Free-space reflex vertices on exteriors and hole boundaries.
                for xy in c[cross > 1e-9]:
                    if self.roi.covers(Point(xy)):
                        self._xy.append(tuple(xy))
                        self._kind.append(V_CORNER)
                        self._ref.append(None)
        self.n_corner = len(self._xy)

    def _candidates(self):
        m = self.model
        raw = []
        # Keep real chambers and unsnapped locations. Eligibility depends on final
        # degree, so snapping cannot destructively discard the latter up front.
        for c in m.chambers.values():
            if c.degree >= 4 or c.node < 0:
                continue
            incident = [s for s in m.sections.values() if s.line.distance(c.pt) <= 0.02]
            if incident:
                s = incident[0]
                raw.append((s.id, s.line.project(c.pt), c.pt, c.id, c.node))
        for s in m.sections.values():
            n = max(1, int(np.ceil(s.length / self.params.candidate_spacing)))
            for k in range(n):
                at = (k + 0.5) * s.length / n
                raw.append((s.id, at, s.line.interpolate(at), None, None))
            # Demand/entry projections and nearby corner projections improve over a fixed grid.
            for d in m.demands.values():
                at = s.line.project(d.pt)
                if d.pt.distance(s.line) <= self.params.vis_radius * 2:
                    raw.append((s.id, at, s.line.interpolate(at), None, None))
        out = []
        for sid, at, pt, cid, node in raw:
            if any(pt.distance(c.pt) < 0.02 and cid == c.chamber_id for c in out):
                continue
            # Do not invent a new chamber at the identical location of an existing one.
            if cid is None and any(
                pt.distance(c.pt) < 0.02 for c in m.chambers.values()
            ):
                continue
            c = Candidate(len(out), sid, at, pt, cid, node, len(self._xy))
            out.append(c)
            self._xy.append((pt.x, pt.y))
            self._kind.append(V_CANDIDATE)
            self._ref.append(c.id)
        self.candidates = out
        self.candidate_by_vertex = {c.vertex: c for c in out}
        self.diagnostics.append(
            f"{len(out)} attachment candidates; degree-aware chamber choice deferred to evaluation"
        )

    def _terminals(self):
        from shapely.ops import nearest_points

        self.terminal_vertex = {}
        self.vertex_demand = {}
        self.entry_gate = {}
        self.entry_failures = {}
        self.entry_waypoint_demand = {}
        for did, d in self.model.demands.items():
            if d.host is not None:
                ob = self.model.obstacles[d.host].geom
                boundary = nearest_points(d.pt, ob.boundary)[1]
                vec = np.asarray(boundary.coords[0]) - d.pt.coords[0]
                dist = float(np.linalg.norm(vec))
                if dist < 1e-7:
                    # Probe outward normals at boundary points instead of exempting all directions.
                    ring = ob.boundary
                    step = 0.1
                    a = ring.interpolate(max(0, ring.project(boundary) - step))
                    b = ring.interpolate(ring.project(boundary) + step)
                    tangent = np.asarray(b.coords[0]) - a.coords[0]
                    vec = np.array([-tangent[1], tangent[0]])
                    vec /= max(np.linalg.norm(vec), 1e-12)
                    if ob.covers(Point(np.asarray(boundary.coords[0]) + vec * 0.01)):
                        vec = -vec
                else:
                    vec /= dist
                offset = (
                    self.oks_clearance
                    + rules.DESIGN_WIDTH[self.dn_class] / 2
                    + self.params.simplify_tol
                    + self.params.clearance_margin
                    + 0.04
                )
                # Continue the prescribed entry ray until it actually leaves the
                # inflated host, including concave footprints and oblique corners.
                ray = LineString(
                    [
                        d.pt,
                        Point(
                            np.asarray(boundary.coords[0]) + vec * max(200.0, ob.length)
                        ),
                    ]
                )
                intervals = []
                entry_dn = rules.dn_for_flow(d.flow) or rules.DNS[-1]
                entry_buffer = ob.buffer(
                    rules.oks_clearance(entry_dn)
                    + rules.DESIGN_WIDTH[entry_dn] / 2
                    + 0.08,
                    join_style="round",
                )
                inter = ray.intersection(entry_buffer)
                for part in getattr(inter, "geoms", [inter]):
                    if part.geom_type == "LineString":
                        intervals.append(
                            sorted(
                                (
                                    ray.project(Point(part.coords[0])),
                                    ray.project(Point(part.coords[-1])),
                                )
                            )
                        )
                # The first connected buffer interval containing the terminal is
                # the only permitted approach; traversing the host again is rejected.
                end = next(
                    (b for a, b in sorted(intervals) if a <= 1e-6), dist + offset
                )
                xy = np.asarray(d.pt.coords[0]) + vec * (end + 0.05)
                entry = LineString([d.pt, Point(xy)])
                crossing = entry.intersection(ob.boundary)
                hits = (
                    list(crossing.geoms) if hasattr(crossing, "geoms") else [crossing]
                )
                if len(hits) != 1:
                    self.entry_failures[did] = (
                        "nearest-boundary approach re-enters the host building"
                    )
                    self.diagnostics.append(
                        f"demand {did}: nearest-boundary entry ray re-enters its building; no legal entry generated"
                    )
                gate = len(self._xy)
                self._xy.append(tuple(xy))
                self._kind.append(V_CORNER)
                self._ref.append(None)
                self.entry_waypoint_demand[gate] = did
                # Give the prescribed incoming direction room for a legal 90-degree
                # turn in a narrow corridor; obstacle corners alone can all be behind it.
                side = np.array([-vec[1], vec[0]])
                if did not in self.entry_failures:
                    for distance in (-20.0, -5.0, 5.0, 20.0):
                        waypoint = xy + side * distance
                        _, errors = self.geometry.describe(
                            [tuple(xy), tuple(waypoint)], entry_dn
                        )
                        if errors:
                            continue
                        self.entry_waypoint_demand[len(self._xy)] = did
                        self._xy.append(tuple(waypoint))
                        self._kind.append(V_CORNER)
                        self._ref.append(None)
            else:
                gate = None
            v = len(self._xy)
            self._xy.append((d.pt.x, d.pt.y))
            self._kind.append(V_TERMINAL)
            self._ref.append(did)
            self.terminal_vertex[did] = v
            self.vertex_demand[v] = did
            if gate is not None:
                self.entry_gate[v] = gate

    def xy(self, v):
        return self.verts[v]
