"""Construct group trees and evaluate current-specification plans.

Cache each group's flows, uniform-chain diameters, checked geometry and costs.
Plan evaluation accounts for shared attachment chambers, incident rays, conflicts,
and every missing demand. Existing-network capacity and reconstruction are excluded.
"""

from __future__ import annotations

import collections
import itertools
import math
from dataclasses import dataclass, field

import numpy as np
from shapely.geometry import LineString, Point
from shapely.ops import substring

from . import rules
from .routing import Graph
from .world import Candidate, World

VIOLATION_WEIGHT = 1_000_000_000.0  # score units added per violation while searching


# ---------------------------------------------------------------- geometry helpers (NumPy, no objects)
def _orient(a, b, c):
    return (b[..., 0] - a[..., 0]) * (c[..., 1] - a[..., 1]) - (
        b[..., 1] - a[..., 1]
    ) * (c[..., 0] - a[..., 0])


EPS_ORIENT = 1e-6  # m²; collinear or shared endpoints must never count as a crossing


def _opposite(d1, d2):
    return (np.minimum(d1, d2) < -EPS_ORIENT) & (np.maximum(d1, d2) > EPS_ORIENT)


def _crossings(p, q, A, B):
    """Proper crossings of segment p->q with segments A[i]->B[i]. Returns mask, points, position t along p->q."""
    if len(A) == 0:
        return np.zeros(0, bool), np.zeros((0, 2)), np.zeros(0)
    d1, d2 = _orient(A, B, p), _orient(A, B, q)
    d3, d4 = _orient(p, q, A), _orient(p, q, B)
    mask = _opposite(d1, d2) & _opposite(d3, d4)
    r, s = q - p, B - A
    denom = r[0] * s[:, 1] - r[1] * s[:, 0]
    denom = np.where(denom == 0, 1.0, denom)
    t = ((A[:, 0] - p[0]) * s[:, 1] - (A[:, 1] - p[1]) * s[:, 0]) / denom
    return mask, p + t[:, None] * r, t


def _count_crossings(SA, SB):
    count = 0
    for a in SA:
        la = LineString(a)
        for b in SB:
            lb = LineString(b)
            if not la.intersects(lb):
                continue
            inter = la.intersection(lb)
            shared = [Point(x) for x in a for y in b if np.linalg.norm(x - y) < 1e-6]
            if (
                inter.length > 1e-6
                or not shared
                or not any(inter.distance(p) < 1e-6 for p in shared)
            ):
                count += 1
    return count


# ---------------------------------------------------------------- trees
@dataclass
class Tree:
    root: int
    xy: dict = field(default_factory=dict)  # node -> (x, y)
    parent: dict = field(default_factory=dict)  # child -> parent
    children: dict = field(default_factory=dict)  # node -> [children]
    term_node: dict = field(default_factory=dict)  # demand id -> node
    unrouted: list = field(default_factory=list)
    next_split: int = -1

    def order(self) -> list:
        out, q = [], collections.deque([self.root])
        while q:
            n = q.popleft()
            out.append(n)
            q.extend(self.children.get(n, []))
        return out

    def length(self, child) -> float:
        a, b = self.xy[child], self.xy[self.parent[child]]
        return math.hypot(a[0] - b[0], a[1] - b[1])

    def segments(self) -> np.ndarray:
        if not self.parent:
            return np.zeros((0, 2, 2))
        return np.array(
            [[self.xy[c], self.xy[p]] for c, p in self.parent.items()], dtype=float
        )


def build_tree(world: World, dij: dict, terminals: list, cand: Candidate) -> Tree:
    """Attach shortest legal paths, rejecting branch splices with illegal turns.

    Multiple attachment vertices are tried: the closest one can require a reversal
    even when each separately calculated shortest path has legal bends.
    """
    import copy
    from .geometry import angle

    root = cand.vertex
    t = Tree(root=root, xy={root: tuple(world.verts[root])}, children={root: []})
    targets = [root]
    edges = []
    aa = []
    bb = []
    remaining = list(terminals)
    while remaining:
        choices = []
        for did in remaining:
            for v in targets:
                distance = float(dij[did][0][v])
                if np.isfinite(distance):
                    choices.append((distance, str(did), did, v))
        choices.sort(key=lambda x: (x[0], x[1], x[3]))
        accepted = False
        for _, _, did, v in choices:
            src = world.terminal_vertex[did]
            path = Graph.path(dij[did][1], src, v)
            if not path:
                continue
            trial = copy.deepcopy(t)
            ee = list(edges)
            a = list(aa)
            b = list(bb)
            tt = list(targets)
            _attach(world, trial, ee, a, b, tt, path, did, src)
            legal = True
            for n, kids in trial.children.items():
                if len(kids) + (n != root) > 4:
                    legal = False
                    break
                if n != root and any(
                    angle(trial.xy[c], trial.xy[n], trial.xy[trial.parent[n]]) > 90.001
                    for c in kids
                ):
                    legal = False
                    break
            if not legal:
                continue
            t, edges, aa, bb, targets = trial, ee, a, b, tt
            remaining.remove(did)
            accepted = True
            break
        if not accepted:
            t.unrouted.extend(remaining)
            break
    return t


def _attach(
    world, t: Tree, edges, seg_a, seg_b, attach_targets, path, did, src
) -> None:
    pxy = world.verts[path]
    last_j = 0
    for j in range(1, len(path)):
        if path[j] in t.xy:
            last_j = j
    contact = ("node", last_j, None)
    if edges:
        A, B = np.array(seg_a), np.array(seg_b)
        for i in range(last_j, len(path) - 1):
            mask, pts, tt = _crossings(pxy[i], pxy[i + 1], A, B)
            if mask.any():
                k = int(np.argmax(np.where(mask, tt, -1.0)))
                contact = ("split", i, (pts[k], k))
    if contact[0] == "node":
        attach_node = path[contact[1]]
        rest = range(contact[1] + 1, len(path))
    else:
        i, (X, k) = contact[1], contact[2]
        c, p = edges[k]
        sid = t.next_split
        t.next_split -= 1
        t.xy[sid] = (float(X[0]), float(X[1]))
        t.parent[c] = sid
        t.parent[sid] = p
        t.children[p].remove(c)
        t.children[p].append(sid)
        t.children[sid] = [c]
        edges[k] = (c, sid)
        seg_b[k] = t.xy[sid]
        edges.append((sid, p))
        seg_a.append(t.xy[sid])
        seg_b.append(t.xy[p])
        attach_node = sid
        rest = range(i + 1, len(path))
    prev = attach_node
    for k in rest:
        q = path[k]
        t.xy[q] = (float(pxy[k][0]), float(pxy[k][1]))
        t.parent[q] = prev
        t.children.setdefault(prev, []).append(q)
        t.children[q] = []
        edges.append((q, prev))
        seg_a.append(t.xy[q])
        seg_b.append(t.xy[prev])
        if q != src:
            attach_targets.append(q)
        prev = q
    t.term_node[did] = src


def tie_in_key(cand: Candidate):
    """Existing object used by a tie-in: the chamber, or the section with a 50 m chainage bucket."""
    if cand.is_chamber:
        return ("chamber", cand.chamber_id)
    return ("section", cand.section_id, int(cand.chainage // 50))


# ---------------------------------------------------------------- groups and their cached evaluation
_serial = itertools.count()
_cross_cache: dict = {}


@dataclass
class GroupEval:
    sections: list
    tech_nodes: list
    chambers: list
    root_children_dn: list
    flow_root: float
    violations: list
    segs: np.ndarray
    construction: float
    new_length: float
    chambers_cost: float = 0.0
    uid: int = -1
    bbox: tuple = ()

    def __post_init__(self):
        self.uid = next(_serial)
        self.chambers_cost = sum(c["cost"] for c in self.chambers)
        if len(self.segs):
            pts = self.segs.reshape(-1, 2)
            self.bbox = (pts.min(0), pts.max(0))


def crossings_between(ga: "GroupEval", gb: "GroupEval") -> int:
    key = (ga.uid, gb.uid) if ga.uid < gb.uid else (gb.uid, ga.uid)
    n = _cross_cache.get(key)
    if n is None:
        if not ga.bbox or not gb.bbox:
            n = 0
        else:
            (lo1, hi1), (lo2, hi2) = ga.bbox, gb.bbox
            n = (
                _count_crossings(ga.segs, gb.segs)
                if ((lo1 <= hi2).all() and (lo2 <= hi1).all())
                else 0
            )
        if len(_cross_cache) >= 200000:
            _cross_cache.clear()
        _cross_cache[key] = n
    return n


@dataclass
class Group:
    terminals: list
    cand: Candidate
    tree: Tree
    ge: GroupEval | None = None

    @property
    def routed(self) -> list:
        return [d for d in self.terminals if d in self.tree.term_node]


def make_group(world: World, dij: dict, terminals: list, cand: Candidate) -> Group:
    return Group(list(terminals), cand, build_tree(world, dij, terminals, cand))


def eval_group(world: World, g: Group) -> GroupEval:
    if g.ge is not None:
        return g.ge
    from .sizing import assign
    from .geometry import angle

    t = g.tree
    if not g.routed:
        g.ge = GroupEval([], [], [], [], 0.0, [], np.zeros((0, 2, 2)), 0.0, 0.0)
        return g.ge
    chains, flow, violations = assign(
        t, world.model.demands, getattr(world, "sizing_backend", None)
    )
    sections = []
    chambers = []
    tech = []
    edge_dn = {}
    for chain in chains:
        for n in chain["nodes"]:
            edge_dn[n] = chain["dn"]
        if hasattr(world, "geometry"):
            pieces, errors = world.geometry.describe(
                chain["xy"],
                chain["dn"],
                chain["demand"],
                g.cand if chain["parent"] == t.root else None,
            )
            violations.extend(errors)
        else:
            pieces = [
                dict(
                    xy=chain["xy"],
                    length=chain["length"],
                    cost=chain["length"] * rules.cost_new(chain["dn"]),
                    laying="base",
                    inside=False,
                    k_special=1.0,
                    crossings=(),
                )
            ]
        for piece in pieces:
            sections.append(
                dict(
                    piece,
                    child=chain["child"],
                    parent=chain["parent"],
                    dn=chain["dn"],
                    flow=chain["flow"],
                )
            )
        tech.extend(dict(xy=p["xy"][-1]) for p in pieces[:-1])
    for n in t.order():
        kids = t.children.get(n, [])
        if n != t.root:
            for c in kids:
                if angle(t.xy[c], t.xy[n], t.xy[t.parent[n]]) > 90.001:
                    violations.append(f"turn at {n} exceeds 90 degrees")
            if len(kids) >= 2:
                dn = max([edge_dn[n]] + [edge_dn[c] for c in kids])
                chambers.append(
                    dict(
                        node=n,
                        xy=t.xy[n],
                        dn=dn,
                        cost=rules.chamber_cost(dn),
                        kind="branch",
                    )
                )
                if len(kids) + 1 > 4:
                    violations.append(f"branch {n}: degree exceeds four")
    g.ge = GroupEval(
        sections,
        tech,
        chambers,
        [edge_dn[c] for c in t.children[t.root]],
        flow[t.root],
        violations,
        t.segments(),
        sum(s["cost"] for s in sections),
        sum(s["length"] for s in sections),
    )
    return g.ge


# ---------------------------------------------------------------- plan evaluation
@dataclass
class Evaluation:
    groups: list
    sections: list = field(default_factory=list)
    chambers: list = field(default_factory=list)
    tie_ins: list = field(default_factory=list)
    tech_nodes: list = field(default_factory=list)
    recon: list = field(default_factory=list)
    chamber_recon: list = field(default_factory=list)
    unrouted: list = field(default_factory=list)
    totals: dict = field(default_factory=dict)
    violations: list = field(default_factory=list)
    signature: tuple = ()
    finalized: bool = False
    n_tech: int = 0
    dropped: list = field(default_factory=list)

    @property
    def score(self) -> float:
        return self.totals["score"]

    @property
    def penalized(self) -> float:
        return self.totals["score"] + VIOLATION_WEIGHT * len(self.violations)

    def label(self) -> str:
        t = self.totals
        return (
            f"S={t['score']:.2f}  C={t['cost']/1e6:.1f}M  L={t['length']:.0f} m  "
            f"groups={t['n_groups']}  missing={len(self.unrouted)}  tn={self.n_tech}  viol={len(self.violations)}"
        )


def evaluate(world: World, groups: list, dropped=()) -> Evaluation:
    m = world.model
    ev = Evaluation(groups=groups)
    ges = [eval_group(world, g) for g in groups]
    routed = [d for g in groups for d in g.routed]
    ev.unrouted = sorted(set(m.demands) - set(routed), key=str)
    ev.dropped = []
    if len(routed) != len(set(routed)):
        ev.violations.append("a demand appears in multiple groups")
    roots = collections.defaultdict(list)
    for gi, (g, ge) in enumerate(zip(groups, ges)):
        if set(g.terminals) - set(g.routed):
            ev.violations.append(f"group {gi}: incomplete group route")
        if not g.routed:
            continue
        ev.violations.extend(f"group {gi}: {v}" for v in ge.violations)
        key = (
            ("existing", g.cand.chamber_id)
            if g.cand.is_chamber
            else ("new", round(g.cand.pt.x, 3), round(g.cand.pt.y, 3))
        )
        roots[key].append(gi)
    existing_use = {
        key[1]: sum(len(ges[i].root_children_dn) for i in gis)
        for key, gis in roots.items()
        if key[0] == "existing"
    }
    tie_count = 0
    for key, gis in roots.items():
        first = gis[0]
        cand = groups[first].cand
        kids = sum(len(ges[i].root_children_dn) for i in gis)
        incident = [s for s in m.sections.values() if s.line.distance(cand.pt) <= 0.02]
        existing_degree = sum(
            (
                1
                if min(
                    cand.pt.distance(Point(s.line.coords[0])),
                    cand.pt.distance(Point(s.line.coords[-1])),
                )
                <= 0.02
                else 2
            )
            for s in incident
        )
        if kids + existing_degree > 4:
            ev.violations.append(f"attachment {key}: degree exceeds four")
        if cand.is_chamber:
            tie_count += kids
        else:
            for c in m.chambers.values():
                if (
                    c.node >= 0
                    and cand.pt.distance(c.pt) <= 10 + 1e-8
                    and c.degree + existing_use.get(c.id, 0) + kids <= 4
                ):
                    ev.violations.append(
                        f"attachment {key}: must use nearby chamber {c.id}"
                    )
            dn = max(
                [s.dn for s in incident]
                + [d for i in gis for d in ges[i].root_children_dn]
            )
            ev.chambers.append(
                dict(
                    gi=first,
                    node=groups[first].tree.root,
                    xy=tuple(cand.pt.coords[0]),
                    dn=dn,
                    cost=rules.chamber_cost(dn),
                    kind="tie_in",
                    shared_gis=gis,
                )
            )
        for gi in gis:
            ge = ges[gi]
            g = groups[gi]
            ev.tie_ins.append(
                dict(
                    gi=gi,
                    xy=g.tree.xy[g.tree.root],
                    existing_id=cand.chamber_id if cand.is_chamber else cand.section_id,
                    existing_type="heat_chamber" if cand.is_chamber else "heat_network",
                    existing_dn=max((s.dn for s in incident), default=0),
                    required_dn=max(ge.root_children_dn),
                    cost=(
                        rules.TIE_IN_COST * len(ge.root_children_dn)
                        if cand.is_chamber
                        else 0.0
                    ),
                    flow=ge.flow_root,
                    section_id=cand.section_id,
                    chainage=cand.chainage,
                )
            )
    active = [i for i, g in enumerate(groups) if g.routed]
    for pos, i in enumerate(active):
        for j in active[pos + 1 :]:
            if crossings_between(ges[i], ges[j]):
                ev.violations.append(f"groups {i}/{j}: geometric conflict")
    construction = sum(ges[i].construction for i in active)
    length = sum(ges[i].new_length for i in active)
    chambers_cost = sum(c["cost"] for c in ev.chambers) + sum(
        ges[i].chambers_cost for i in active
    )
    penalty = sum(rules.penalty(m.demands[d].flow) for d in ev.unrouted)
    tie = tie_count * rules.TIE_IN_COST
    cost = construction + chambers_cost + tie + penalty
    ev.n_tech = sum(len(ges[i].tech_nodes) for i in active)
    ev.totals = dict(
        construction=construction,
        chambers=chambers_cost,
        tie_in=tie,
        tie_in_count=tie_count,
        recon=0.0,
        chamber_recon=0.0,
        penalty=penalty,
        cost=cost,
        new_length=length,
        recon_length=0.0,
        length=length,
        score=rules.score(cost, length),
        n_groups=len(roots),
    )
    ev.signature = (
        frozenset(tie_in_key(groups[i].cand) for i in active),
        frozenset(frozenset(groups[i].routed) for i in active),
        frozenset(ev.unrouted),
    )
    return ev


def finalize(world: World, ev: Evaluation) -> Evaluation:
    if not ev.finalized:
        ties = list(ev.chambers)
        ev.sections = []
        ev.tech_nodes = []
        ev.chambers = []
        for gi, g in enumerate(ev.groups):
            if not g.routed:
                continue
            ge = eval_group(world, g)
            ev.sections.extend(dict(s, gi=gi) for s in ge.sections)
            ev.tech_nodes.extend(dict(n, gi=gi) for n in ge.tech_nodes)
            ev.chambers.extend(dict(c, gi=gi) for c in ge.chambers)
        ev.chambers.extend(ties)
        ev.finalized = True
    return ev
