"""Optimize validated group trees under the current specification.

Bounds refer only to the generated finite column family, never the continuous or
unrestricted routing problem. Every published plan is independently validated.
"""

from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
import numpy as np
from heatrouting import (
    core_bridge as CB,
    model,
    world,
    routing,
    search,
    network,
    partition as PT,
    export,
    rules,
)
from heatrouting.validate import validate, ValidationError


def seed_columns(w, solver):
    columns = []
    seen = set()
    for group in solver.group_cache.values():
        if set(group.routed) != set(group.terminals):
            continue
        ge = network.eval_group(w, group)
        if ge.violations:
            continue
        key = (
            frozenset(group.terminals),
            group.cand.id,
            tuple((tuple(s["xy"]), s["dn"]) for s in ge.sections),
        )
        if key in seen:
            continue
        seen.add(key)
        count = len(ge.root_children_dn)
        cost = ge.construction + ge.chambers_cost + rules.LAMBDA * ge.new_length
        if group.cand.is_chamber:
            cost += count * rules.TIE_IN_COST
        columns.append(
            PT.Column(
                len(columns),
                group.terminals,
                group.cand,
                group,
                cost,
                cost,
                ge.flow_root,
                count,
                max(ge.root_children_dn),
                "heuristic",
            )
        )
    return columns


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input")
    ap.add_argument("--out", default="out/current_partition")
    ap.add_argument("--max-size", type=int, default=3)
    ap.add_argument("--radius", type=float, default=200.0)
    ap.add_argument("--knn", type=int, default=3)
    ap.add_argument("--per-subset", type=int, default=5)
    ap.add_argument("--time-limit", type=float, default=60.0)
    ap.add_argument("--restarts", type=int, default=2)
    ap.add_argument(
        "--no-dp",
        action="store_true",
        help="Use the heuristic tree family without extra subset DP pricing",
    )
    ap.add_argument("--no-figures", action="store_true")
    ap.add_argument("--graph-dn", type=int, choices=rules.DNS)
    args = ap.parse_args()
    start = time.time()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    m = model.load(args.input)
    w = world.World(m, world.Params(graph_dn=args.graph_dn))
    g = routing.Graph(w)
    dij = g.all_terminal_paths()
    bridge = CB.CoreBridge(w, dij, g) if CB.available() else None
    solver = search.Solver(w, dij, core=bridge, log=lambda _: None)
    _, best = solver.run(restarts=args.restarts)
    columns = seed_columns(w, solver)
    if not args.no_dp:
        if bridge is None:
            raise SystemExit("Build the current C++ kernel or use --no-dp")
        if not 1 <= args.max_size <= 14:
            raise SystemExit("--max-size must be between 1 and 14")
        pricer = PT.Pricer(w, bridge, dij)
        subsets = PT.connected_subsets(
            PT.proximity_graph(w, dij, args.radius, args.knn), args.max_size
        )
        for subset in subsets:
            costs = pricer.root_costs(subset)
            for cid in np.argsort(costs)[: args.per_subset]:
                if not np.isfinite(costs[cid]):
                    continue
                col = pricer.column(
                    len(columns),
                    list(subset),
                    w.candidates[int(cid)],
                    float(costs[cid]),
                )
                if col is not None:
                    columns.append(col)
    print(f"{len(columns)} geometrically checked columns", flush=True)
    master = PT.Master(w, columns)
    result = master.solve(time_limit=args.time_limit)
    ev = result.evaluation
    if ev is None:
        raise ValidationError("No conflict-free master incumbent; no output published")
    report = export.to_geojson(w, [ev], out / "result.geojson")
    (out / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
    dual = rules.W_COST * result.lower_bound_family / rules.COST_BASE
    summary = {
        "spec_version": rules.SPEC_VERSION,
        "score": ev.score,
        "heuristic_score": best.score if not best.violations else None,
        "column_count": len(columns),
        "family_dual_score": dual if np.isfinite(dual) else None,
        "bound_scope": "generated fixed-tree column family; not a global routing bound",
        "unconnected_ids": ev.unrouted,
        "seconds": time.time() - start,
    }
    (out / "partition.json").write_text(json.dumps(summary, indent=2) + "\n")
    if not args.no_figures:
        from heatrouting import viz

        viz.fig_result(w, ev, str(out / "partition.png"), ev.label())
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
