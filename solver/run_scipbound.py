"""Route A with SCIP: branch-and-cut with lazy Steiner cuts and a MIP start from DP trees.

    python run_scipbound.py data/data.geojson --seconds 1200 [--dn-binaries]
"""
from __future__ import annotations

import argparse
import time

import numpy as np

from heatrouting import core_bridge as CB, cutbound as CBnd, model as M, network as N, partition as PT, routing as R, rules, scipbound as SB, search as S, world as W


def main():
    raise SystemExit("This historical bound uses superseded reconstruction rules. Use run_partition.py; its bound is limited to the generated column family.")
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--seconds", type=float, default=1200.0)
    ap.add_argument("--ring-layers", type=int, default=8)
    ap.add_argument("--dn-binaries", action="store_true")
    args = ap.parse_args()
    t0 = time.time()
    m = M.load(args.input)
    w = W.World(m, W.Params())
    g = R.Graph(w)
    dij = g.all_terminal_paths()
    bridge = CB.CoreBridge(w, dij, graph=g)
    solver = S.Solver(w, dij, log=lambda *_: None, core=bridge)
    groups, best = solver.run(restarts=1)
    ev = N.finalize(w, solver.materialize(best))
    # MIP start: DP trees of the heuristic plan's groups at their tie-ins (pure graph-vertex trees)
    pricer = PT.Pricer(w, bridge, dij)
    start_groups = [pricer.group_from_dp(grp.terminals, grp.cand) for grp in ev.groups if grp.routed]
    start_ev = N.finalize(w, N.evaluate(w, start_groups))
    print(f"[setup] {time.time() - t0:.1f} s; heuristic S = {ev.score:.2f}; MIP start (DP trees at the same tie-ins) S = {start_ev.score:.2f}")
    cb = CBnd.CutBound(w, g, dn_binaries=args.dn_binaries)
    cb.ring_cuts(args.ring_layers)
    sb = SB.ScipBound(cb, dn_binaries=args.dn_binaries)
    arcs, flows = SB.arcs_of_plan(cb, start_groups)
    if arcs is not None:
        sb.add_start(arcs, flows)
        print(f"  start solution handed to SCIP: {len(arcs)} arcs")
    print(f"[SCIP] branch-and-cut, {args.seconds:.0f} s")
    dual, primal, xval = sb.solve(args.seconds)
    ds = rules.W_COST * dual / rules.COST_BASE
    print(f"  lower bound S >= {ds:.2f}  (gap to heuristic {ev.score:.2f}: {(ev.score - ds) / ev.score * 100:.1f}%; to Route B 12.62: {(12.62 - ds) / 12.62 * 100:.1f}%)")
    if xval is not None:
        class _R: pass
        res = _R(); res.x = np.zeros(cb.A * 3); res.x[:cb.A] = xval
        cb.off = dict(x=0)
        plan = cb.plan_from_solution(res, dij)
        print(f"  SCIP incumbent under the model: S {rules.W_COST * primal / rules.COST_BASE:.2f}; evaluated exactly: {plan.label()}")
        for t in plan.tie_ins:
            grp = plan.groups[t["gi"]]
            where = f"chamber {t['existing_id']}" if t["existing_type"] == "heat_chamber" else f"section {t['existing_id']} at {t['chainage']:.0f} m"
            print(f"    {sorted(map(str, grp.terminals))} -> {where}")
    print(f"done in {time.time() - t0:.1f} s")


if __name__ == "__main__":
    main()
