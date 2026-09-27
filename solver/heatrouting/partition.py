"""Set partitioning over a finite family of geometrically checked group trees.

Heuristic and relaxed subset-DP trees are re-evaluated under the current rules.
The master prices shared attachment chambers, enforces coverage and chamber degree,
and adds geometric conflicts lazily. Its dual bound applies only to this fixed
column family; it is not a global routing certificate.
"""

from __future__ import annotations

import itertools
import time
from dataclasses import dataclass, field

import numpy as np
import scipy.sparse as sp
from scipy.optimize import Bounds, LinearConstraint, linprog, milp

from . import rules
from .network import (
    Group,
    Tree,
    crossings_between,
    eval_group,
    evaluate,
    finalize,
    tie_in_key,
)
from .world import World


# ---------------------------------------------------------------- pricing
@dataclass
class Column:
    id: int
    terminals: list  # demand ids
    cand: object  # Candidate
    group: Group
    lb: float  # DP cost + tie-in + chamber tier lower bound (+ λ inside DP)
    ub: float  # exact evaluator cost without plan-level coupling: construction + chambers + tie-in + λ·length
    flow: float
    root_kids: int
    dn_root: int  # largest DN of the root's children (exact)
    source: str = "dp"


class Pricer:
    def __init__(self, world: World, bridge, dij, log=print):
        self.w, self.bridge, self.dij, self.log = world, bridge, dij, log
        self.core = bridge.core
        self.term_ids = bridge.term_ids
        self.term_index = bridge.term_index
        self.vertex_demand = world.vertex_demand
        self.cand_vertices = [c.vertex for c in world.candidates]
        m = world.model
        self.max_branches = [
            min(3, 4 - m.chambers[c.chamber_id].degree) if c.is_chamber else 2
            for c in world.candidates
        ]
        self._dp_cache = {}

    def dp(self, terminals):
        """One DP per subset; tables are large (2^k x vertices), so only the most recent one is kept."""
        key = tuple(sorted(terminals, key=str))
        if self._dp_cache.get("key") != key:
            self._dp_cache = {
                "key": key,
                "dp": self.core.steiner_dp([self.term_index[d] for d in key]),
            }
        return self._dp_cache["dp"]

    def root_costs(self, terminals) -> np.ndarray:
        return np.array(
            self.core.dp_root_costs(
                self.dp(terminals), self.cand_vertices, self.max_branches
            )
        )

    def group_from_dp(self, terminals, cand) -> Group:
        edges = self.core.dp_tree(
            self.dp(terminals), cand.vertex, self.max_branches[cand.id]
        )
        t = Tree(
            root=cand.vertex,
            xy={cand.vertex: tuple(self.w.verts[cand.vertex])},
            children={cand.vertex: []},
        )
        seen_edges = set()
        for child, parent in edges:
            if (child, parent) in seen_edges:
                continue
            seen_edges.add((child, parent))
            if child == t.root or (child in t.parent and t.parent[child] != parent):
                t.unrouted = list(terminals)
                return Group(sorted(terminals, key=str), cand, t)
            t.xy[child] = tuple(self.w.verts[child])
            t.xy.setdefault(parent, tuple(self.w.verts[parent]))
            t.parent[child] = parent
            t.children.setdefault(parent, []).append(child)
            t.children.setdefault(child, [])
        reachable = set()
        stack = [t.root]
        while stack:
            node = stack.pop()
            if node in reachable:
                t.unrouted = list(terminals)
                return Group(sorted(terminals, key=str), cand, t)
            reachable.add(node)
            stack.extend(t.children.get(node, []))
        for d in terminals:
            v = self.w.terminal_vertex[d]
            if v in reachable:
                t.term_node[d] = v
            else:
                t.unrouted.append(d)
        return Group(sorted(terminals, key=str), cand, t)

    def column(self, cid, terminals, cand, dp_cost=None) -> Column | None:
        g = self.group_from_dp(terminals, cand)
        if g.tree.unrouted:
            return None
        ge = eval_group(self.w, g)
        m = self.w.model
        if cand.is_chamber:
            ch_lb = 0.0
        else:
            ch_lb = rules.chamber_cost(rules.dn_ge(m.sections[cand.section_id].dn))
        if dp_cost is None:
            dp_cost = float(self.root_costs(terminals)[cand.id])
        lb = dp_cost + (
            rules.TIE_IN_COST * len(g.tree.children[g.tree.root])
            if cand.is_chamber
            else 0.0
        )
        ev = evaluate(self.w, [g])
        t = ev.totals
        ub = (
            ge.construction
            + ge.chambers_cost
            + (
                rules.TIE_IN_COST * len(g.tree.children[g.tree.root])
                if cand.is_chamber
                else 0.0
            )
            + rules.LAMBDA * ge.new_length
        )
        if any(
            v.startswith("group") for v in ev.violations
        ):  # branch or tie-in degree, DN table, run length
            return None
        return Column(
            cid,
            list(g.terminals),
            cand,
            g,
            lb,
            ub,
            ge.flow_root,
            len(g.tree.children[g.tree.root]),
            max(ge.root_children_dn) if ge.root_children_dn else 0,
        )


# ---------------------------------------------------------------- column family
def proximity_graph(world: World, dij, radius: float, knn: int) -> dict:
    """Demand adjacency: within `radius` metres of legal path, or among the `knn` nearest."""
    ids = list(world.model.demands)
    d = {a: {} for a in ids}
    for a in ids:
        dist = dij[a][0]
        for b in ids:
            if a != b:
                d[a][b] = float(dist[world.terminal_vertex[b]])
    adj = {a: set() for a in ids}
    for a in ids:
        order = sorted(ids, key=lambda b: d[a].get(b, np.inf))
        for b in [b for b in order if b != a][:knn]:
            if np.isfinite(d[a][b]):
                adj[a].add(b)
                adj[b].add(a)
        for b in ids:
            if a != b and d[a][b] <= radius:
                adj[a].add(b)
                adj[b].add(a)
    return adj


def connected_subsets(adj: dict, max_size: int) -> list:
    """All connected vertex subsets of size <= max_size (Simple enumeration by extension)."""
    ids = sorted(adj, key=str)
    index = {a: i for i, a in enumerate(ids)}
    out = set()
    for start in ids:
        # grow subsets containing `start` as their smallest element
        frontier = [frozenset([start])]
        seen = {frozenset([start])}
        while frontier:
            nxt = []
            for S in frontier:
                out.add(S)
                if len(S) >= max_size:
                    continue
                for a in S:
                    for b in adj[a]:
                        if b in S or index[b] < index[start]:
                            continue
                        S2 = S | {b}
                        if S2 not in seen:
                            seen.add(S2)
                            nxt.append(S2)
            frontier = nxt
    return sorted(out, key=lambda S: (len(S), sorted(map(str, S))))


# ---------------------------------------------------------------- master problem
@dataclass
class MasterResult:
    chosen: list
    objective: float
    lower_bound_family: float
    lp_value: float
    evaluation: object = None
    conflicts_added: int = 0
    solves: int = 0


class Master:
    """Current-spec master: coverage, physical chamber activation/degree, conflicts.

    Group prices exclude new root chambers; activation variables charge each
    physical chamber once at the largest selected adjoining diameter. Existing
    chamber tie-ins are already priced per ray in each column.
    """

    def __init__(self, world, columns, log=print):
        self.w, self.cols, self.log = world, columns, log
        self.m = world.model

    def solve(self, use_lb=False, time_limit=120.0, max_rounds=20):
        from shapely.geometry import Point

        cols = self.cols
        n = len(cols)
        if not n:
            return MasterResult([], float("inf"), float("nan"), float("nan"))
        rootkey = lambda c: (round(c.cand.pt.x, 3), round(c.cand.pt.y, 3))
        new = {}
        for i, c in enumerate(cols):
            if not c.cand.is_chamber:
                new.setdefault(rootkey(c), []).append(i)
        objective = [c.lb if use_lb else c.ub for c in cols]
        activations = {}
        required = {}
        old_degrees = {}
        for key, indices in new.items():
            pt = cols[indices[0]].cand.pt
            incident = [
                s for s in self.m.sections.values() if s.line.distance(pt) <= 0.02
            ]
            old_dn = max(s.dn for s in incident)
            old_degrees[key] = sum(
                (
                    1
                    if min(
                        Point(s.line.coords[0]).distance(pt),
                        Point(s.line.coords[-1]).distance(pt),
                    )
                    <= 0.02
                    else 2
                )
                for s in incident
            )
            fees = sorted(
                {rules.chamber_cost(max(old_dn, cols[i].dn_root)) for i in indices}
            )
            activations[key] = [(len(objective) + j, fee) for j, fee in enumerate(fees)]
            objective.extend(fees)
            for i in indices:
                required[i] = rules.chamber_cost(max(old_dn, cols[i].dn_root))
        nv = len(objective)
        rows = []
        lower = []
        upper = []

        def row(coeff, lo=-np.inf, hi=np.inf):
            rows.append(coeff)
            lower.append(lo)
            upper.append(hi)

        possible = set(t for c in cols for t in c.terminals)
        missing = set(self.m.demands) - possible
        constant = sum(rules.penalty(self.m.demands[t].flow) for t in missing)
        for t in sorted(possible, key=str):
            row({i: 1.0 for i, c in enumerate(cols) if t in c.terminals}, 1.0, 1.0)
        for cid, ch in self.m.chambers.items():
            if ch.degree:
                row(
                    {
                        i: float(c.root_kids)
                        for i, c in enumerate(cols)
                        if c.cand.chamber_id == cid
                    },
                    hi=4 - ch.degree,
                )
        for key, indices in new.items():
            ys = activations[key]
            row({j: 1.0 for j, fee in ys}, hi=1.0)
            row({i: float(cols[i].root_kids) for i in indices}, hi=4 - old_degrees[key])
            for i in indices:
                coeff = {i: 1.0}
                coeff.update({j: -1.0 for j, fee in ys if fee >= required[i]})
                row(coeff, hi=0.0)
            # Conditional 10 m rule uses the final incidence, not input-order snapping.
            pt = cols[indices[0]].cand.pt
            for cid, ch in self.m.chambers.items():
                if ch.degree and pt.distance(ch.pt) <= 10 + 1e-8:
                    coeff = {i: float(cols[i].root_kids) for i in indices}
                    for i, c in enumerate(cols):
                        if c.cand.chamber_id == cid:
                            coeff[i] = float(c.root_kids)
                    coeff.update({j: -5.0 for j, fee in ys})
                    row(coeff, lo=-ch.degree)
        conflicts = set()
        lp_value = float("nan")
        dual = float("nan")
        for rnd in range(max_rounds):
            rs = []
            cs = []
            vs = []
            for r, coeff in enumerate(rows):
                for c, v in coeff.items():
                    rs.append(r)
                    cs.append(c)
                    vs.append(v)
            A = sp.coo_matrix((vs, (rs, cs)), shape=(len(rows), nv)).tocsr()
            cons = LinearConstraint(A, np.asarray(lower), np.asarray(upper))
            if rnd == 0:
                lp = milp(
                    np.asarray(objective),
                    constraints=cons,
                    integrality=np.zeros(nv),
                    bounds=Bounds(0, 1),
                    options={"time_limit": time_limit},
                )
                if lp.status == 0:
                    lp_value = float(lp.fun) + constant
            res = milp(
                np.asarray(objective),
                constraints=cons,
                integrality=np.ones(nv),
                bounds=Bounds(0, 1),
                options={"time_limit": time_limit},
            )
            bound = getattr(res, "mip_dual_bound", None)
            if bound is not None:
                dual = float(bound) + constant
            if res.x is None:
                return MasterResult([], np.inf, dual, lp_value, solves=rnd + 1)
            chosen = [c for i, c in enumerate(cols) if res.x[i] > 0.5]
            new_conflicts = []
            index = {id(c): i for i, c in enumerate(cols)}
            for ca, cb in itertools.combinations(chosen, 2):
                if crossings_between(
                    eval_group(self.w, ca.group), eval_group(self.w, cb.group)
                ):
                    pair = tuple(sorted((index[id(ca)], index[id(cb)])))
                    if pair not in conflicts:
                        new_conflicts.append(pair)
            if not new_conflicts:
                ev = self.evaluate_plan(chosen)
                if ev.violations:
                    # Reject this combination without claiming a feasible incumbent.
                    indices = [index[id(c)] for c in chosen]
                    row({i: 1.0 for i in indices}, hi=len(indices) - 1.0)
                    continue
                value = float(res.fun) + constant
                self.log(
                    f"  master: {len(chosen)} groups, score {rules.W_COST*value/rules.COST_BASE:.6f}, status {res.status}"
                )
                return MasterResult(
                    chosen, value, dual, lp_value, ev, len(conflicts), rnd + 1
                )
            for a, b in new_conflicts:
                conflicts.add((a, b))
                row({a: 1.0, b: 1.0}, hi=1.0)
        self.log("  master stopped before a conflict-free incumbent; returning no plan")
        return MasterResult(
            [],
            np.inf,
            dual,
            lp_value,
            conflicts_added=len(conflicts),
            solves=max_rounds,
        )

    def evaluate_plan(self, chosen):
        return finalize(self.w, evaluate(self.w, [c.group for c in chosen]))
