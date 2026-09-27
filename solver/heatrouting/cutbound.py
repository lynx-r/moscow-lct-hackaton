"""Route A: cut-and-branch lower bound on the visibility graph.

Variables: arc usage x_a (binary in the MILP phase), aggregate flow F_a, cost-per-metre z_a (convex hull of
the DN step cost, or DN choice binaries y_{a,d} when `dn_binaries` is on), reconstruction chords r_p,
branch chamber binaries b_v. Connectivity comes from Steiner cuts, sum of x over arcs entering S >= 1 for
every vertex set S that holds a terminal but not the root, generated lazily: max-flow separation on LP
solutions, breadth-first search on integer solutions. Reduced-cost fixing from the LP phase removes arcs that
cannot be in a model-optimal solution. The MILP's dual bound is a valid lower bound on the relaxed model, hence
on the graph-restricted problem; its incumbent is materialised into real groups and priced by the evaluator.
"""
from __future__ import annotations

import time

import numpy as np
import scipy.sparse as sp
from scipy.optimize import Bounds, LinearConstraint, linprog, milp
from scipy.sparse.csgraph import breadth_first_order, maximum_flow

from . import rules
from .bounds import _cost_hull
from .network import Group, Tree, evaluate, finalize
from .world import V_TERMINAL, World


class CutBound:
    def __init__(self, world: World, graph, log=print, dn_binaries: bool = False):
        raise NotImplementedError("Historical reconstruction-based bound; not a current-spec certificate. Use run_partition.py for a restricted-family bound.")
        self.w, self.g, self.log, self.dn_binaries = world, graph, log, dn_binaries
        self.cuts: list = []          # each cut: array of arc indices entering S
        self.fixed_zero = np.zeros(0, bool)
        self._build()

    # ---------------------------------------------------------------- model
    def _build(self):
        w, g, m = self.w, self.g, self.w.model
        term_ids = list(m.demands)
        self.term_ids = term_ids
        tv = np.array([w.terminal_vertex[d] for d in term_ids])
        self.tv = tv
        demand = np.array([m.demands[d].flow for d in term_ids])
        self.demand_at = dict(zip(tv.tolist(), demand.tolist()))
        total = float(demand.sum())
        self.total = total
        N = g.n
        is_term = np.zeros(N, bool); is_term[tv] = True
        eu, ev, elen = g.edges_u, g.edges_v, g.edges_len
        au = np.concatenate([eu[~is_term[eu]], ev[~is_term[ev]]])
        av = np.concatenate([ev[~is_term[eu]], eu[~is_term[ev]]])
        alen = np.concatenate([elen[~is_term[eu]], elen[~is_term[ev]]])
        R = N
        cands = w.candidates
        au = np.concatenate([au, np.full(len(cands), R)]).astype(np.int64)
        av = np.concatenate([av, np.array([c.vertex for c in cands])]).astype(np.int64)
        alen = np.concatenate([alen, np.zeros(len(cands))])
        A = len(au)
        self.au, self.av, self.alen, self.A, self.N, self.R = au, av, alen, A, N, R
        self.n_graph_arcs = A - len(cands)
        self.cands = cands
        fixed = np.zeros(A)
        for k, c in enumerate(cands):
            ch = 0.0 if c.is_chamber else rules.chamber_cost(rules.dn_ge(m.sections[c.section_id].dn))
            fixed[self.n_graph_arcs + k] = rules.TIE_IN_COST + ch
        self.fixed = fixed
        segs, dn_max = _cost_hull(total)
        self.segs = segs
        self.dn_list = [d for d in rules.DNS if d <= dn_max]
        # portions (as in bounds.py)
        self.portions = self._portions(total)
        self.in_arcs = [[] for _ in range(N + 1)]
        self.out_arcs = [[] for _ in range(N + 1)]
        for a in range(A):
            self.out_arcs[au[a]].append(a); self.in_arcs[av[a]].append(a)
        self.log(f"  cut model: {A} arcs ({len(cands)} root arcs), {N} vertices, {len(self.portions)} portions, "
                 f"DN binaries {'on' if self.dn_binaries else 'off'}")

    def _portions(self, total):
        w, m = self.w, self.w.model
        by_section = {}
        for c in w.candidates:
            by_section.setdefault(c.section_id, []).append(c)
        children = {}
        for sid, s in m.sections.items():
            par = m.parent_section.get(s.up_node)
            if par is not None:
                children.setdefault(par, []).append(sid)

        def downstream(sid):
            out, stack = [], list(children.get(sid, []))
            while stack:
                x = stack.pop(); out.append(x); stack.extend(children.get(x, []))
            return out
        portions = []
        for sid, s in m.sections.items():
            if not s.connected:
                continue
            H = rules.capacity(rules.dn_ge(s.dn)) - s.flow_existing
            if H >= total:
                continue
            dn_next = rules.next_dn(rules.dn_ge(s.dn))
            if dn_next is None:
                continue
            through = [c.id for d in downstream(sid) for c in by_section.get(d, [])]
            here = sorted(by_section.get(sid, []), key=lambda c: c.chainage)
            bounds = [0.0] + [c.chainage for c in here] + [s.length]
            for j in range(len(bounds) - 1):
                a, b = bounds[j], bounds[j + 1]
                if b - a <= 1e-6:
                    continue
                cids = through + [c.id for c in here[j:]]
                slope = (b - a) * rules.cost_recon(dn_next) / max(total - H, 1e-9)
                portions.append((sid, a, b, H, slope, cids, (b - a) * (rules.cost_recon(dn_next) + rules.LAMBDA)))
        return portions

    def _matrices(self, integer: bool, active: np.ndarray):
        """Build objective, constraints and bounds for the current cut set. `active` masks arcs not fixed to zero."""
        A, N, R = self.A, self.N, self.R
        P = len(self.portions)
        D = len(self.dn_list) if self.dn_binaries else 0
        # layout: x[A], F[A], z[A] (or y[A*D]), r[P], b[N]
        off_x, off_F = 0, A
        off_z = 2 * A
        off_r = off_z + (A * D if self.dn_binaries else A)
        off_b = off_r + P
        nv = off_b + N
        obj = np.zeros(nv)
        obj[off_x:off_x + A] = rules.LAMBDA * self.alen + self.fixed
        if self.dn_binaries:
            for j, d in enumerate(self.dn_list):
                obj[off_z + j * A: off_z + (j + 1) * A] = self.alen * rules.cost_new(d)
        else:
            obj[off_z:off_z + A] = self.alen
        obj[off_r:off_r + P] = 1.0
        obj[off_b:off_b + N] = rules.chamber_cost(rules.DNS[0])
        rows, cols, vals, lo, hi = [], [], [], [], []
        r = 0
        arc = np.arange(A)
        # flow conservation of the aggregate flow: out - in = total at R, -d_t at terminals, 0 elsewhere
        rows.append(r + self.au); cols.append(off_F + arc); vals.append(np.ones(A))
        rows.append(r + self.av); cols.append(off_F + arc); vals.append(-np.ones(A))
        rhs = np.zeros(N + 1); rhs[R] = self.total
        for v, d in self.demand_at.items():
            rhs[v] = -d
        lo.extend(rhs); hi.extend(rhs); r += N + 1
        # F <= total x
        rows.append(r + arc); cols.append(off_F + arc); vals.append(np.ones(A))
        rows.append(r + arc); cols.append(off_x + arc); vals.append(-self.total * np.ones(A))
        lo.extend([-np.inf] * A); hi.extend([0.0] * A); r += A
        if self.dn_binaries:
            # sum_d y = x ; F <= sum_d cap_d y
            for j in range(D):
                rows.append(r + arc); cols.append(off_z + j * A + arc); vals.append(np.ones(A))
            rows.append(r + arc); cols.append(off_x + arc); vals.append(-np.ones(A))
            lo.extend([0.0] * A); hi.extend([0.0] * A); r += A
            rows.append(r + arc); cols.append(off_F + arc); vals.append(np.ones(A))
            for j, d in enumerate(self.dn_list):
                rows.append(r + arc); cols.append(off_z + j * A + arc); vals.append(-rules.capacity(d) * np.ones(A))
            lo.extend([-np.inf] * A); hi.extend([0.0] * A); r += A
        else:
            for alpha, beta in self.segs:   # alpha F + beta x - z <= 0
                rows.append(r + arc); cols.append(off_z + arc); vals.append(-np.ones(A))
                rows.append(r + arc); cols.append(off_x + arc); vals.append(beta * np.ones(A))
                if alpha:
                    rows.append(r + arc); cols.append(off_F + arc); vals.append(alpha * np.ones(A))
                lo.extend([-np.inf] * A); hi.extend([0.0] * A); r += A
        # reconstruction chords on injected flow: slope * sum_{c in down(p)} F_{Rc} - r_p <= slope H
        for p, (sid, a, b, H, slope, cids, _) in enumerate(self.portions):
            for cid in cids:
                rows.append(np.array([r])); cols.append(np.array([off_F + self.n_graph_arcs + cid])); vals.append(np.array([slope]))
            rows.append(np.array([r])); cols.append(np.array([off_r + p])); vals.append(np.array([-1.0]))
            lo.append(-np.inf); hi.append(slope * H); r += 1
        # branch chambers: sum_out x - 2 b <= 1 (binary b allows up to three outgoing sections)
        rows.append(r + self.au[:self.n_graph_arcs]); cols.append(off_x + arc[:self.n_graph_arcs]); vals.append(np.ones(self.n_graph_arcs))
        rows.append(r + np.arange(N)); cols.append(off_b + np.arange(N)); vals.append(-2.0 * np.ones(N))
        lo.extend([-np.inf] * N); hi.extend([1.0] * N); r += N
        # Steiner cuts
        for cut in self.cuts:
            rows.append(np.full(len(cut), r)); cols.append(off_x + cut); vals.append(np.ones(len(cut)))
            lo.append(1.0); hi.append(np.inf); r += 1
        Amat = sp.coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(r, nv)).tocsr()
        lb = np.zeros(nv); ub = np.full(nv, np.inf)
        ub[off_x:off_x + A] = np.where(active, 1.0, 0.0)
        if self.dn_binaries:
            ub[off_z:off_z + A * D] = 1.0
        ub[off_b:off_b + N] = 1.0
        integrality = np.zeros(nv)
        if integer:
            integrality[off_x:off_x + A] = 1
            integrality[off_b:off_b + N] = 1
            if self.dn_binaries:
                integrality[off_z:off_z + A * D] = 1
        self.off = dict(x=off_x, F=off_F, z=off_z, r=off_r, b=off_b)
        return obj, Amat, np.array(lo), np.array(hi), lb, ub, integrality

    @staticmethod
    def _split(Amat, lo, hi):
        """Rows with lo == hi become equalities; the rest become A x <= hi and -A x <= -lo (finite sides only)."""
        eq = np.isfinite(lo) & np.isfinite(hi) & (lo == hi)
        up = np.isfinite(hi) & ~eq
        lw = np.isfinite(lo) & ~eq
        A_eq, b_eq = Amat[eq], hi[eq]
        A_ub = sp.vstack([Amat[up], -Amat[lw]]).tocsr()
        b_ub = np.concatenate([hi[up], -lo[lw]])
        return A_eq, b_eq, A_ub, b_ub

    # ---------------------------------------------------------------- separation
    def separate_integer(self, x: np.ndarray) -> int:
        """BFS from the root over used arcs; every unreached terminal yields a violated cut."""
        used = x > 0.5
        N, R = self.N, self.R
        reach = np.zeros(N + 1, bool); reach[R] = True
        stack = [R]
        while stack:
            v = stack.pop()
            for a in self.out_arcs[v]:
                if used[a] and not reach[self.av[a]]:
                    reach[self.av[a]] = True; stack.append(int(self.av[a]))
        added = 0
        for t in self.tv:
            if reach[t]:
                continue
            # S = vertices that can reach t (over any arc): the cut delta_in(S) has no used arc
            S = np.zeros(N + 1, bool); S[t] = True
            stack = [int(t)]
            while stack:
                v = stack.pop()
                for a in self.in_arcs[v]:
                    u = int(self.au[a])
                    if not S[u] and u != R:
                        S[u] = True; stack.append(u)
            cut = np.array([a for a in range(self.A) if S[self.av[a]] and not S[self.au[a]]])
            self.cuts.append(cut); added += 1
        return added

    def separate_fractional(self, x: np.ndarray, eps: float = 0.02) -> int:
        """Max-flow from the root to each terminal with capacities x; a flow below 1 gives a violated min cut.
        SciPy's flow matrix is antisymmetric, so the residual graph is capacity minus flow with negatives dropped."""
        N, R = self.N, self.R
        scale = 100000
        cap = np.maximum(0, np.round(x * scale)).astype(np.int32)
        G = sp.coo_matrix((cap, (self.au, self.av)), shape=(N + 1, N + 1)).tocsr()
        G.sum_duplicates()
        added = 0
        for t in self.tv:
            res = maximum_flow(G, R, int(t))
            if res.flow_value >= (1 - eps) * scale:
                continue
            Res = (G - res.flow).tocsr()
            Res.data[Res.data < 0] = 0
            Res.eliminate_zeros()
            order = breadth_first_order(Res, R, directed=True, return_predecessors=False)
            reach = np.zeros(N + 1, bool)
            reach[order] = True
            S = ~reach
            if not S[t]:
                continue
            cut = np.nonzero(S[self.av] & ~S[self.au])[0]
            if len(cut) and x[cut].sum() < 1 - eps:
                self.cuts.append(cut)
                added += 1
        return added

    # ---------------------------------------------------------------- phases
    def lp_phase(self, max_rounds: int = 25, time_limit: float = 600.0):
        active = np.ones(self.A, bool)
        value = None
        for rnd in range(max_rounds):
            obj, Amat, lo, hi, lb, ub, _ = self._matrices(False, active)
            t0 = time.time()
            A_eq, b_eq, A_ub, b_ub = self._split(Amat, lo, hi)
            res = linprog(obj, A_ub=A_ub, b_ub=b_ub, A_eq=A_eq, b_eq=b_eq,
                          bounds=list(zip(lb, ub)), method="highs", options={"time_limit": time_limit})
            if res.status != 0:
                self.log(f"  LP round {rnd}: status {res.status} {res.message}")
                break
            value = res.fun
            x = res.x[self.off["x"]:self.off["x"] + self.A]
            added = self.separate_fractional(x)
            self.log(f"  LP round {rnd}: value {value/1e6:.1f} M (S {rules.W_COST * value / rules.COST_BASE:.2f}), {len(self.cuts)} cuts, +{added}, {time.time() - t0:.1f} s")
            self.last_lp = res
            if added == 0:
                break
        return value

    def ring_cuts(self, layers: int = 8) -> int:
        """Initial Steiner cuts: for every terminal, the vertex sets within 1..layers hops (root excluded)."""
        N, R = self.N, self.R
        nbrs = [[] for _ in range(N + 1)]
        for a in range(self.n_graph_arcs):
            nbrs[self.au[a]].append(int(self.av[a])); nbrs[self.av[a]].append(int(self.au[a]))
        added = 0
        for t in self.tv:
            S = np.zeros(N + 1, bool); S[t] = True
            frontier = [int(t)]
            for _ in range(layers):
                nxt = []
                for v in frontier:
                    for u in nbrs[v]:
                        if not S[u] and u != R:
                            S[u] = True; nxt.append(u)
                frontier = nxt
                cut = np.nonzero(S[self.av] & ~S[self.au])[0]
                if len(cut):
                    self.cuts.append(cut); added += 1
                if not nxt:
                    break
        return added

    def fix_from_reduced_costs(self, rc_x: np.ndarray, lp_value: float, ub_model: float) -> int:
        """Reduced costs of the arc variables from any valid LP relaxation sharing this arc layout."""
        gap = ub_model - lp_value
        fix = rc_x > gap
        self.fixed_zero = fix
        self.log(f"  reduced-cost fixing: gap {gap/1e6:.1f} M, {int(fix.sum())} of {self.A} arcs fixed to zero")
        return int(fix.sum())

    def reduced_cost_fixing(self, ub_model: float) -> int:
        """Fix arcs to zero whose reduced cost proves them absent from any model solution better than ub_model."""
        res = self.last_lp
        gap = ub_model - res.fun
        rc = np.asarray(res.lower.marginals)[self.off["x"]:self.off["x"] + self.A]
        fix = rc > gap
        self.fixed_zero = fix
        self.log(f"  reduced-cost fixing: gap {gap/1e6:.1f} M, {int(fix.sum())} of {self.A} arcs fixed to zero")
        return int(fix.sum())

    def milp_phase(self, time_limit: float, max_rounds: int = 15):
        active = ~self.fixed_zero if len(self.fixed_zero) else np.ones(self.A, bool)
        best_dual, incumbent = -np.inf, None
        for rnd in range(max_rounds):
            obj, Amat, lo, hi, lb, ub, integrality = self._matrices(True, active)
            t0 = time.time()
            res = milp(obj, constraints=[LinearConstraint(Amat, lo, hi)], integrality=integrality,
                       bounds=Bounds(lb, ub), options={"time_limit": time_limit})
            dual = getattr(res, "mip_dual_bound", None)
            if dual is not None and np.isfinite(dual):
                best_dual = max(best_dual, dual)
            if res.x is None:
                self.log(f"  MILP round {rnd}: no solution ({res.message}); dual {dual}")
                break
            x = res.x[self.off["x"]:self.off["x"] + self.A]
            added = self.separate_integer(x)
            self.log(f"  MILP round {rnd}: obj {res.fun/1e6:.1f} M, dual {dual/1e6 if dual else float('nan'):.1f} M "
                     f"(S >= {rules.W_COST * best_dual / rules.COST_BASE:.2f}), {len(self.cuts)} cuts, +{added}, {time.time() - t0:.1f} s")
            incumbent = res
            if added == 0:
                break
        return best_dual, incumbent

    # ---------------------------------------------------------------- materialise an integer solution
    def plan_from_solution(self, res, dij):
        x = res.x[self.off["x"]:self.off["x"] + self.A]
        used = x > 0.5
        w = self.w
        groups = []
        vertex_demand = w.vertex_demand
        for k, c in enumerate(self.cands):
            a0 = self.n_graph_arcs + k
            if not used[a0]:
                continue
            root = c.vertex
            t = Tree(root=root, xy={root: tuple(w.verts[root])}, children={root: []})
            stack = [root]; seen = {root}; terms = []
            while stack:
                v = stack.pop()
                if v in vertex_demand and v != root:
                    terms.append(vertex_demand[v]); t.term_node[vertex_demand[v]] = v
                    continue
                for a in self.out_arcs[v]:
                    if used[a] and a < self.n_graph_arcs:
                        u = int(self.av[a])
                        if u in seen:
                            continue
                        seen.add(u)
                        t.xy[u] = tuple(w.verts[u]); t.parent[u] = v
                        t.children.setdefault(v, []).append(u); t.children.setdefault(u, [])
                        stack.append(u)
            if terms:
                groups.append(Group(sorted(terms, key=str), c, t))
        ev = finalize(w, evaluate(w, groups))
        return ev
