"""Route A with SCIP: branch-and-cut on the cut model with a constraint handler for Steiner cuts.

Reuses CutBound's arc layout, hull segments, portions and separation routines. Steiner cuts are enforced
lazily: `conscheck` rejects integer solutions with an unreached terminal, `consenfolp` adds the violated
cuts at nodes, `conssepalp` separates fractional LP solutions by max-flow. A partial solution built from
DP trees of a known plan is handed to SCIP as a MIP start. The dual bound is a valid lower bound on the
relaxed model, hence on the graph-restricted problem.
"""
from __future__ import annotations

import time

import numpy as np
from pyscipopt import Conshdlr, Model, SCIP_RESULT, quicksum

from . import rules
from .cutbound import CutBound


class SteinerCuts(Conshdlr):
    def __init__(self, cb: CutBound, x: list, log):
        super().__init__()
        self.cb, self.x, self.log = cb, x, log
        self.n_int_cuts = 0
        self.n_frac_cuts = 0
        self.n_sepa_calls = 0

    def _xval(self, sol):
        return np.array([self.model.getSolVal(sol, v) for v in self.x])

    def _integer_cuts(self, xval):
        before = len(self.cb.cuts)
        self.cb.separate_integer(xval)
        return self.cb.cuts[before:]

    def conscheck(self, constraints, solution, checkintegrality, checklprows, printreason, completely):
        cuts = self._integer_cuts(self._xval(solution))
        del self.cb.cuts[len(self.cb.cuts) - len(cuts):]
        return {"result": SCIP_RESULT.INFEASIBLE if cuts else SCIP_RESULT.FEASIBLE}

    def consenfolp(self, constraints, nusefulconss, solinfeasible):
        cuts = self._integer_cuts(self._xval(None))
        for cut in cuts:
            self.model.addCons(quicksum(self.x[int(a)] for a in cut) >= 1)
        self.n_int_cuts += len(cuts)
        return {"result": SCIP_RESULT.CONSADDED if cuts else SCIP_RESULT.FEASIBLE}

    def consenfops(self, constraints, nusefulconss, solinfeasible, objinfeasible):
        cuts = self._integer_cuts(self._xval(None))
        del self.cb.cuts[len(self.cb.cuts) - len(cuts):]
        return {"result": SCIP_RESULT.INFEASIBLE if cuts else SCIP_RESULT.FEASIBLE}

    def conssepalp(self, constraints, nusefulconss):
        self.n_sepa_calls += 1
        if self.n_sepa_calls > 20000:
            return {"result": SCIP_RESULT.DIDNOTRUN}
        xval = self._xval(None)
        before = len(self.cb.cuts)
        self.cb.separate_fractional(xval, eps=0.05)
        cuts = self.cb.cuts[before:]
        for cut in cuts:
            self.model.addCons(quicksum(self.x[int(a)] for a in cut) >= 1)
        self.n_frac_cuts += len(cuts)
        return {"result": SCIP_RESULT.CONSADDED if cuts else SCIP_RESULT.DIDNOTFIND}

    def conslock(self, constraint, locktype, nlockspos, nlocksneg):
        for v in self.x:
            self.model.addVarLocks(v, nlocksneg, nlockspos)


class ScipBound:
    def __init__(self, cb: CutBound, log=print, dn_binaries: bool = False):
        raise NotImplementedError("Historical reconstruction-based bound. Use the current restricted-family partition master.")
        self.cb, self.log, self.dn_binaries = cb, log, dn_binaries
        self._build()

    def _build(self):
        cb = self.cb
        A, N, R = cb.A, cb.N, cb.R
        m = Model("heat_routing_cut_model")
        m.hideOutput(True)
        x = [m.addVar(vtype="B", name=f"x{a}", obj=rules.LAMBDA * cb.alen[a] + cb.fixed[a]) for a in range(A)]
        F = [m.addVar(vtype="C", lb=0.0, name=f"F{a}") for a in range(A)]
        b = [m.addVar(vtype="B", name=f"b{v}", obj=rules.chamber_cost(rules.DNS[0])) for v in range(N)]
        # flow conservation (aggregate)
        out_arcs, in_arcs = cb.out_arcs, cb.in_arcs
        for v in range(N + 1):
            rhs = cb.total if v == R else -cb.demand_at.get(v, 0.0)
            m.addCons(quicksum(F[a] for a in out_arcs[v]) - quicksum(F[a] for a in in_arcs[v]) == rhs)
        for a in range(A):
            m.addCons(F[a] <= cb.total * x[a])
        if self.dn_binaries:
            dns = cb.dn_list
            y = [[m.addVar(vtype="B", name=f"y{a}_{d}", obj=cb.alen[a] * rules.cost_new(d)) for d in dns] for a in range(A)]
            for a in range(A):
                m.addCons(quicksum(y[a]) == x[a])
                m.addCons(F[a] <= quicksum(rules.capacity(d) * y[a][j] for j, d in enumerate(dns)))
            self.y = y
        else:
            z = [m.addVar(vtype="C", lb=0.0, name=f"z{a}", obj=cb.alen[a]) for a in range(A)]
            for a in range(A):
                for alpha, beta in cb.segs:
                    m.addCons(z[a] >= alpha * F[a] + beta * x[a])
            self.z = z
        r = []
        for p, (sid, a0, b0, H, slope, cids, _) in enumerate(cb.portions):
            rp = m.addVar(vtype="C", lb=0.0, name=f"r{p}", obj=1.0)
            m.addCons(rp >= slope * (quicksum(F[cb.n_graph_arcs + cid] for cid in cids) - H))
            r.append(rp)
        for v in range(N):
            outs = [a for a in out_arcs[v] if a < cb.n_graph_arcs]
            if outs:
                m.addCons(quicksum(x[a] for a in outs) <= 1 + 2 * b[v])
        for cut in cb.cuts:
            m.addCons(quicksum(x[int(a)] for a in cut) >= 1)
        for a in range(A):
            if len(cb.fixed_zero) and cb.fixed_zero[a]:
                m.chgVarUb(x[a], 0.0)
        self.handler = SteinerCuts(cb, x, self.log)
        m.includeConshdlr(self.handler, "steinercuts", "lazy Steiner cuts", sepapriority=0, enfopriority=-1, chckpriority=-1,
                          sepafreq=1, propfreq=-1, eagerfreq=100, maxprerounds=0, delaysepa=False, delayprop=False,
                          needscons=False)
        self.m, self.x, self.F, self.b, self.r = m, x, F, b, r
        self.log(f"  SCIP model: {A} arc binaries, {len(cb.cuts)} initial cuts, DN binaries {'on' if self.dn_binaries else 'off'}")

    def add_start(self, arcs_used: list, flows: dict) -> bool:
        """Complete solution from a known plan: arc usage, flows, hull costs, chords and branch chambers."""
        m, cb = self.m, self.cb
        sol = m.createSol()
        used = set(arcs_used)
        for a in range(cb.A):
            xa = 1.0 if a in used else 0.0
            Fa = flows.get(a, 0.0) if a in used else 0.0
            m.setSolVal(sol, self.x[a], xa)
            m.setSolVal(sol, self.F[a], Fa)
            if not self.dn_binaries:
                m.setSolVal(sol, self.z[a], max(alpha * Fa + beta * xa for alpha, beta in cb.segs) if xa else 0.0)
            else:
                d_idx = None
                if xa:
                    d_idx = next(j for j, d in enumerate(cb.dn_list) if rules.capacity(d) >= Fa - 1e-9)
                for j in range(len(cb.dn_list)):
                    m.setSolVal(sol, self.y[a][j], 1.0 if j == d_idx else 0.0)
        for p, (sid, a0, b0, H, slope, cids, _) in enumerate(cb.portions):
            inj = sum(flows.get(cb.n_graph_arcs + cid, 0.0) for cid in cids if (cb.n_graph_arcs + cid) in used)
            m.setSolVal(sol, self.r[p], max(0.0, slope * (inj - H)))
        outdeg = {}
        for a in arcs_used:
            if a < cb.n_graph_arcs:
                outdeg[int(cb.au[a])] = outdeg.get(int(cb.au[a]), 0) + 1
        for v in range(cb.N):
            m.setSolVal(sol, self.b[v], 1.0 if outdeg.get(v, 0) >= 2 else 0.0)
        ok = m.addSol(sol, free=True)
        self.log(f"  start solution {'accepted' if ok else 'REJECTED'} by SCIP")
        return ok

    def solve(self, time_limit: float):
        m = self.m
        m.setParam("limits/time", time_limit)
        m.setParam("display/verblevel", 0)
        t0 = time.time()
        m.optimize()
        st = m.getStatus()
        dual, primal = m.getDualbound(), m.getPrimalbound()
        self.log(f"  SCIP: status {st}, dual {dual/1e6:.1f} M (S >= {rules.W_COST * dual / rules.COST_BASE:.2f}), "
                 f"primal {primal/1e6:.1f} M, gap {m.getGap()*100:.1f}%, nodes {m.getNNodes()}, "
                 f"cuts int {self.handler.n_int_cuts} frac {self.handler.n_frac_cuts}, {time.time() - t0:.0f} s")
        xval = None
        if m.getNSols() > 0:
            sol = m.getBestSol()
            xval = np.array([m.getSolVal(sol, v) for v in self.x])
        return dual, primal, xval


def arcs_of_plan(cb: CutBound, groups) -> tuple:
    """Arc indices and flows for a plan whose trees live on graph vertices (DP trees)."""
    lookup = {}
    for a in range(cb.n_graph_arcs):
        lookup[(int(cb.au[a]), int(cb.av[a]))] = a
    arcs, flows = [], {}
    m = cb.w.model
    for g in groups:
        t = g.tree
        arcs.append(cb.n_graph_arcs + g.cand.id)
        order = t.order()
        flow = {n: 0.0 for n in order}
        node_demand = {n: d for d, n in t.term_node.items()}
        for n in reversed(order):
            flow[n] += m.demands[node_demand[n]].flow if n in node_demand else 0.0
            if n != t.root:
                flow[t.parent[n]] += flow[n]
        flows[cb.n_graph_arcs + g.cand.id] = flow[t.root]
        for child, parent in t.parent.items():
            a = lookup.get((int(parent), int(child)))
            if a is None:
                return None, None      # tree uses a split node; not representable
            arcs.append(a); flows[a] = flow[child]
    return arcs, flows
