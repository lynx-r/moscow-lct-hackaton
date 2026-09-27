"""Compute lower bounds on the score and compare with the solver's upper bound.

    python run_bounds.py data/data.geojson --ub-score 13.49 --milp-seconds 300
"""
from __future__ import annotations

import argparse
import time

from heatrouting import bounds as B, model as M, routing as R, world as W, rules


def main():
    raise SystemExit("This historical bound uses superseded reconstruction rules. Use run_partition.py; its bound is limited to the generated column family.")
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--ub-score", type=float, default=None, help="score of the best known plan (upper bound)")
    ap.add_argument("--lp-seconds", type=float, default=1800)
    ap.add_argument("--milp-seconds", type=float, default=0, help="0 skips the MILP")
    ap.add_argument("--lp-method", default="highs", choices=["highs", "highs-ds", "highs-ipm"])
    ap.add_argument("--clusters", default="none", choices=["none", "plan", "one"],
                    help="commodity aggregation: none (one per demand), plan (groups of the solver's best plan), one (single commodity)")
    args = ap.parse_args()
    t0 = time.time()
    m = M.load(args.input)
    w = W.World(m, W.Params())
    g = R.Graph(w)
    print(f"world and graph in {time.time() - t0:.1f} s: {g.n} vertices, {len(g.edges_u)} edges, {len(w.candidates)} candidates")
    clusters = None
    if args.clusters == "one":
        clusters = [list(m.demands)]
    elif args.clusters == "plan":
        from heatrouting import search as S, core_bridge as CB
        dij = g.all_terminal_paths()
        core = CB.CoreBridge(w, dij) if CB.available() else None
        solver = S.Solver(w, dij, log=lambda *_: None, core=core)
        groups, best = solver.run(restarts=1)
        clusters = [list(gr.terminals) for gr in groups if gr.terminals]
        print(f"clusters from the best plan (S={best.score:.2f}): {[sorted(map(str, c)) for c in clusters]}")
    lpb = B.LPBound(w, g, clusters=clusters)
    lb_eff, res = lpb.solve_lp(args.lp_seconds, args.lp_method)
    if lb_eff is None:
        print("LP failed")
        return
    lb_s = lpb.score_of(lb_eff)
    print(f"LP lower bound: C_eff >= {lb_eff/1e6:.1f} M UNI  ->  score S >= {lb_s:.2f}")
    for inj, xval, key in lpb.describe_solution(res):
        print(f"    LP injects {inj:6.1f} t/h at {key} (x={xval:.2f})")
    if args.ub_score is not None:
        print(f"upper bound S = {args.ub_score:.2f}  ->  gap (UB-LB)/UB = {(args.ub_score - lb_s) / args.ub_score * 100:.1f}%")
    if args.milp_seconds > 0:
        dual, mres = lpb.solve_milp(args.milp_seconds)
        if dual is not None:
            ds = lpb.score_of(dual)
            print(f"MILP dual bound after {args.milp_seconds:.0f} s: S >= {ds:.2f}"
                  + (f"  ->  gap {(args.ub_score - ds) / args.ub_score * 100:.1f}%" if args.ub_score else ""))
            if mres.x is not None and mres.fun is not None:
                print(f"MILP incumbent (relaxed rules): S = {lpb.score_of(mres.fun):.2f}")
    print(f"done in {time.time() - t0:.1f} s")


if __name__ == "__main__":
    main()
