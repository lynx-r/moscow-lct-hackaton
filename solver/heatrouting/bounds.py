"""Lower bounds on the effective cost C + λ·L by relaxation, solved exactly with HiGHS.

Level 1: multi-commodity flow LP on the visibility graph. Relaxations (each only lowers the bound):
  - DN step cost per metre replaced by its lower convex hull over flow;
  - maximum-length rule, degree rule and chamber tiers dropped (branch chambers priced at the
    smallest tier, tie-in chambers at their tier lower bound, existing chambers at zero);
  - reconstruction of an existing portion priced by the chord from (headroom, 0) to
    (total demand, cost of the first DN step);
  - arc usage relaxed to [0, 1].
Level 2: the same model with binary arc usage, solved by branch-and-bound under a time limit; the
solver's dual bound is a valid, usually tighter, lower bound.
"""
from __future__ import annotations

import time

import numpy as np
import scipy.sparse as sp
from scipy.optimize import Bounds, LinearConstraint, linprog, milp

from . import rules
from .network import tie_in_key
from .world import V_TERMINAL, World


def _cost_hull(max_flow: float):
    """Lower convex hull of (capacity, new cost per metre) for DNs up to the one that carries max_flow.
    Returns segments (alpha, beta) with cost >= alpha*F + beta*x, plus the flat segment at DN50."""
    dn_max = rules.dn_for_flow(max_flow) or rules.DNS[-1]
    pts = [(rules.capacity(d), rules.cost_new(d)) for d in rules.DNS if d <= dn_max]
    hull = []
    for p in pts:
        while len(hull) >= 2:
            (x1, y1), (x2, y2) = hull[-2], hull[-1]
            if (y2 - y1) * (p[0] - x1) >= (p[1] - y1) * (x2 - x1):   # hull[-1] above the chord
                hull.pop()
            else:
                break
        hull.append(p)
    segs = [(0.0, pts[0][1])]                                            # flat: cost >= c50 * x
    for (x1, y1), (x2, y2) in zip(hull, hull[1:]):
        a = (y2 - y1) / (x2 - x1)
        segs.append((a, y1 - a * x1))
    return segs, dn_max


class LPBound:
    def __init__(self, world: World, graph, log=print, clusters=None):
        raise NotImplementedError("Historical reconstruction-based bound; not a current-spec certificate. Use run_partition.py for a restricted-family bound.")
        """`clusters`: optional list of lists of demand ids; demands in one cluster share one commodity
        (a weaker but valid relaxation that shrinks the model by the number of clusters)."""
        self.w, self.g, self.log = world, graph, log
        self.clusters = clusters
        self._build()

    def _build(self):
        w, g, m = self.w, self.g, self.w.model
        t0 = time.time()
        term_ids = list(m.demands)
        tv = np.array([w.terminal_vertex[d] for d in term_ids])
        term_flow = np.array([m.demands[d].flow for d in term_ids])
        total = float(term_flow.sum())
        N = g.n
        is_term = np.zeros(N, bool)
        is_term[tv] = True
        # commodities: one per demand, or one per cluster
        clusters = self.clusters or [[d] for d in term_ids]
        T = len(clusters)
        demand = np.array([sum(m.demands[d].flow for d in cl) for cl in clusters])   # D_k
        sinks = []   # per commodity: list of (terminal vertex, fraction of D_k)
        for k, cl in enumerate(clusters):
            sinks.append([(w.terminal_vertex[d], m.demands[d].flow / demand[k]) for d in cl])
        # arcs: both directions of every visible edge except arcs leaving a terminal; plus root arcs
        eu, ev, elen = g.edges_u, g.edges_v, g.edges_len
        au = np.concatenate([eu[~is_term[eu]], ev[~is_term[ev]]])
        av = np.concatenate([ev[~is_term[eu]], eu[~is_term[ev]]])
        alen = np.concatenate([elen[~is_term[eu]], elen[~is_term[ev]]])
        R = N
        cands = w.candidates
        cu = np.full(len(cands), R)
        cvv = np.array([c.vertex for c in cands])
        au = np.concatenate([au, cu]).astype(np.int64)
        av = np.concatenate([av, cvv]).astype(np.int64)
        alen = np.concatenate([alen, np.zeros(len(cands))])
        A = len(au)
        n_graph_arcs = A - len(cands)
        root_arc = {c.id: n_graph_arcs + k for k, c in enumerate(cands)}
        # fixed charge of a tie-in: tie-in cost plus the chamber tier lower bound
        fixed = np.zeros(A)
        for k, c in enumerate(cands):
            ch = 0.0 if c.is_chamber else rules.chamber_cost(rules.dn_ge(m.sections[c.section_id].dn))
            fixed[n_graph_arcs + k] = rules.TIE_IN_COST + ch
        # variable layout
        nf, nx = T * A, A
        off_f, off_x, off_z = 0, T * A, T * A + A
        # reconstruction portions with headroom below total demand
        portions = self._portions(total)
        off_r = off_z + A
        P = len(portions)
        off_b = off_r + P
        nvar = off_b + N
        self.off = dict(f=off_f, x=off_x, z=off_z, r=off_r, b=off_b)
        # objective
        c = np.zeros(nvar)
        c[off_z:off_z + A] = alen                       # z_a is cost per metre lower bound, times length
        c[off_x:off_x + A] = rules.LAMBDA * alen + fixed
        c[off_r:off_r + P] = 1.0
        c[off_b:off_b + N] = rules.chamber_cost(rules.DNS[0])
        c[off_b + tv] = 0.0
        # equalities: conservation per commodity and node (R included as node N)
        rows, cols, vals = [], [], []
        arc_idx = np.arange(A)
        for t in range(T):
            base_row = t * (N + 1)
            rows.append(base_row + au); cols.append(off_f + t * A + arc_idx); vals.append(np.ones(A))
            rows.append(base_row + av); cols.append(off_f + t * A + arc_idx); vals.append(-np.ones(A))
        A_eq = sp.coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))),
                             shape=(T * (N + 1), nvar)).tocsr()
        b_eq = np.zeros(T * (N + 1))
        for t in range(T):
            b_eq[t * (N + 1) + R] = 1.0
            for v_t, frac in sinks[t]:
                b_eq[t * (N + 1) + v_t] = -frac
        # inequalities
        rows, cols, vals, rhs = [], [], [], []
        r0 = 0
        # (i) f <= x
        for t in range(T):
            rr = r0 + np.arange(A)
            rows += [rr, rr]; cols += [off_f + t * A + arc_idx, off_x + arc_idx]; vals += [np.ones(A), -np.ones(A)]
            r0 += A
        rhs.append(np.zeros(T * A))
        # (ii) hull: alpha*F + beta*x - z <= 0 with F = sum_t d_t f[t,a]
        segs, dn_max = _cost_hull(total)
        for alpha, beta in segs:
            rr = r0 + np.arange(A)
            rows.append(rr); cols.append(off_z + arc_idx); vals.append(-np.ones(A))
            rows.append(rr); cols.append(off_x + arc_idx); vals.append(np.full(A, beta))
            if alpha:
                for t in range(T):
                    rows.append(rr); cols.append(off_f + t * A + arc_idx); vals.append(np.full(A, alpha * demand[t]))
            r0 += A
        rhs.append(np.zeros(len(segs) * A))
        # (iii) reconstruction chord: slope * sum_{c in down(p)} I_c - r_p <= slope * H_p
        for p, (sid, a, b, H, slope, cand_ids) in enumerate(portions):
            rows.append(np.array([r0])); cols.append(np.array([off_r + p])); vals.append(np.array([-1.0]))
            for cid in cand_ids:
                ai = root_arc[cid]
                for t in range(T):
                    rows.append(np.array([r0])); cols.append(np.array([off_f + t * A + ai])); vals.append(np.array([slope * demand[t]]))
            rhs.append(np.array([slope * H]))
            r0 += 1
        # (iv) branch chambers: sum_{a out of v} x_a - b_v <= 1 for graph vertices
        out_rows = r0 + au[:n_graph_arcs]
        rows.append(out_rows); cols.append(off_x + arc_idx[:n_graph_arcs]); vals.append(np.ones(n_graph_arcs))
        rows.append(r0 + np.arange(N)); cols.append(off_b + np.arange(N)); vals.append(-np.ones(N))
        rhs.append(np.ones(N))
        r0 += N
        A_ub = sp.coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))),
                             shape=(r0, nvar)).tocsr()
        b_ub = np.concatenate(rhs)
        lb = np.zeros(nvar)
        ub = np.full(nvar, np.inf)
        ub[:off_z] = 1.0
        self.c, self.A_eq, self.b_eq, self.A_ub, self.b_ub, self.lb, self.ub = c, A_eq, b_eq, A_ub, b_ub, lb, ub
        self.A, self.T, self.N, self.n_graph_arcs, self.total = A, T, N, n_graph_arcs, total
        self.au, self.av, self.alen, self.cands, self.term_ids = au, av, alen, cands, term_ids
        self.demand = demand
        self.log(f"  LP model ({T} commodities): {nvar} variables, {A_eq.shape[0]} equalities, {A_ub.shape[0]} inequalities, "
                 f"{A_eq.nnz + A_ub.nnz} nonzeros, hull segments {len(segs)} up to DN{dn_max}, "
                 f"{P} reconstruction portions, built in {time.time() - t0:.1f} s")

    def _portions(self, total: float):
        """Existing portions whose headroom is below total demand, with the candidates injecting below them."""
        w, m = self.w, self.w.model
        by_section = {}
        for c in w.candidates:
            by_section.setdefault(c.section_id, []).append(c)
        # candidates injecting into a section s: those on s, and those on any section downstream of s
        children = {}
        for sid, s in m.sections.items():
            par = m.parent_section.get(s.up_node)
            if par is not None:
                children.setdefault(par, []).append(sid)

        def downstream_sections(sid):
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
            through = [c.id for d in downstream_sections(sid) for c in by_section.get(d, [])]
            here = sorted(by_section.get(sid, []), key=lambda c: c.chainage)
            bounds = [0.0] + [c.chainage for c in here] + [s.length]
            for j in range(len(bounds) - 1):
                a, b = bounds[j], bounds[j + 1]
                if b - a <= 1e-6:
                    continue
                cids = through + [c.id for c in here[j:]]
                slope = (b - a) * rules.cost_recon(dn_next) / max(total - H, 1e-9)
                portions.append((sid, a, b, H, slope, cids))
        return portions

    # ---- solving
    def solve_lp(self, time_limit: float = 1800.0, method: str = "highs"):
        t0 = time.time()
        res = linprog(self.c, A_ub=self.A_ub, b_ub=self.b_ub, A_eq=self.A_eq, b_eq=self.b_eq,
                      bounds=list(zip(self.lb, self.ub)), method=method,
                      options={"time_limit": time_limit, "presolve": True})
        self.log(f"  LP: status {res.status} ({res.message.strip()}), {time.time() - t0:.1f} s")
        return (res.fun if res.status == 0 else None), res

    def solve_milp(self, time_limit: float = 300.0):
        t0 = time.time()
        integrality = np.zeros(len(self.c))
        integrality[self.off["x"]:self.off["x"] + self.A] = 1
        cons = [LinearConstraint(self.A_eq, self.b_eq, self.b_eq), LinearConstraint(self.A_ub, -np.inf, self.b_ub)]
        res = milp(self.c, constraints=cons, integrality=integrality, bounds=Bounds(self.lb, self.ub),
                   options={"time_limit": time_limit, "disp": False})
        dual = getattr(res, "mip_dual_bound", None)
        self.log(f"  MILP: status {res.status} ({res.message.strip()}), dual bound {dual}, "
                 f"primal {res.fun}, gap {getattr(res, 'mip_gap', None)}, {time.time() - t0:.1f} s")
        return dual, res

    @staticmethod
    def score_of(c_eff: float) -> float:
        return rules.W_COST * c_eff / rules.COST_BASE

    def describe_solution(self, res, top: int = 8) -> list:
        """Which tie-ins and how much flow the LP solution injects (fractional)."""
        x = res.x
        out = []
        for k, c in enumerate(self.cands):
            ai = self.n_graph_arcs + k
            inj = sum(self.demand[t] * x[self.off["f"] + t * self.A + ai] for t in range(self.T))
            if inj > 1e-3:
                out.append((inj, x[self.off["x"] + ai], tie_in_key(c)))
        out.sort(reverse=True)
        return out[:top]
