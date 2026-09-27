"""Route A: cut-and-branch lower bound with lazily separated Steiner cuts.

    python run_cutbound.py data/data.geojson --milp-seconds 600 [--dn-binaries]
"""
from __future__ import annotations

import argparse
import time

import numpy as np

from heatrouting import core_bridge as CB, cutbound as CBnd, model as M, network as N, routing as R, rules, search as S, world as W
from heatrouting.bounds import _cost_hull


def model_cost_of_plan(ev, total):
    """The heuristic plan priced under the relaxed model: a valid upper bound on the model optimum."""
    segs, _ = _cost_hull(total)
    hull = lambda F: max(a * F + b for a, b in segs)
    pipes = sum(s["length"] * (hull(s["flow"]) + rules.LAMBDA) for s in ev.sections)
    tie = ev.totals["tie_in"] + sum(c["cost"] for c in ev.chambers if c["kind"] == "tie_in")
    branch = sum(1 for c in ev.chambers if c["kind"] == "branch") * rules.chamber_cost(rules.DNS[0])
    recon = sum(r["cost"] + rules.LAMBDA * r["length"] for r in ev.recon)   # chord <= step, so this is >= model cost
    return pipes + tie + branch + recon


def main():
    raise SystemExit("This historical bound uses superseded reconstruction rules. Use run_partition.py; its bound is limited to the generated column family.")
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--lp-seconds", type=float, default=1800.0)
    ap.add_argument("--ring-layers", type=int, default=8)
    ap.add_argument("--milp-seconds", type=float, default=600.0)
    ap.add_argument("--milp-rounds", type=int, default=10)
    ap.add_argument("--dn-binaries", action="store_true")
    args = ap.parse_args()
    t0 = time.time()
    m = M.load(args.input)
    w = W.World(m, W.Params())
    g = R.Graph(w)
    dij = g.all_terminal_paths()
    core = CB.CoreBridge(w, dij, graph=g) if CB.available() else None
    solver = S.Solver(w, dij, log=lambda *_: None, core=core)
    groups, best = solver.run(restarts=1)
    ev = N.finalize(w, solver.materialize(best))
    total = m.existing_flow_total()
    ub_model = model_cost_of_plan(ev, total)
    print(f"[setup] {time.time() - t0:.1f} s; heuristic S = {ev.score:.2f}; its cost under the relaxed model {ub_model/1e6:.1f} M "
          f"(S {rules.W_COST * ub_model / rules.COST_BASE:.2f}) is the ceiling of the hull-based bound")
    from heatrouting import bounds as B
    print("[root LP] seventeen-commodity relaxation (interior point)")
    lpb = B.LPBound(w, g)
    lp_value, res = lpb.solve_lp(args.lp_seconds, "highs-ipm")
    if lp_value is None:
        return
    print(f"  root bound S >= {rules.W_COST * lp_value / rules.COST_BASE:.2f}")
    rc_x = np.asarray(res.lower.marginals)[lpb.off["x"]:lpb.off["x"] + lpb.A]
    cb = CBnd.CutBound(w, g, dn_binaries=args.dn_binaries)
    assert cb.A == lpb.A, "arc layouts differ"
    cb.fix_from_reduced_costs(rc_x, lp_value, ub_model)
    n_ring = cb.ring_cuts(args.ring_layers)
    print(f"  {n_ring} ring cuts seeded")
    print(f"[MILP phase] cut-and-branch, {args.milp_seconds:.0f} s per solve")
    dual, inc = cb.milp_phase(args.milp_seconds, max_rounds=args.milp_rounds)
    dual = max(dual, lp_value)
    print(f"  dual bound S >= {rules.W_COST * dual / rules.COST_BASE:.2f}  (gap to {ev.score:.2f}: {(ev.score - rules.W_COST * dual / rules.COST_BASE) / ev.score * 100:.1f}%)")
    if inc is not None and inc.x is not None:
        plan = cb.plan_from_solution(inc, dij)
        print(f"  incumbent under the model: S {rules.W_COST * inc.fun / rules.COST_BASE:.2f}; evaluated exactly: {plan.label()}")
        for t in plan.tie_ins:
            grp = plan.groups[t["gi"]]
            where = f"chamber {t['existing_id']}" if t["existing_type"] == "heat_chamber" else f"section {t['existing_id']} at {t['chainage']:.0f} m"
            print(f"    {sorted(map(str, grp.terminals))} -> {where}")
    print(f"done in {time.time() - t0:.1f} s")


if __name__ == "__main__":
    main()
