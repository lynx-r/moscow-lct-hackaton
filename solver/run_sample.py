"""Route and independently validate a dataset under the current written specification."""

from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
from heatrouting import model, world, routing, search, network, export, rules
from heatrouting.validate import validate, ValidationError


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input")
    ap.add_argument("--out", default="out/current")
    ap.add_argument("--engine", choices=["auto", "python", "cpp"], default="auto")
    ap.add_argument("--top-k", type=int, default=6)
    ap.add_argument("--restarts", type=int, default=3)
    ap.add_argument("--no-frames", action="store_true")
    ap.add_argument("--no-figures", action="store_true")
    ap.add_argument("--graph-dn", type=int, choices=rules.DNS)
    ap.add_argument("--vis-radius", type=float, default=400.0)
    ap.add_argument("--roi-margin", type=float, default=120.0)
    args = ap.parse_args()
    start = time.time()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    def log(s):
        print(s, flush=True)

    log("[1/5] validate and ingest input")
    m = model.load(args.input)
    log("[2/5] geometry and route proposals")
    w = world.World(
        m,
        world.Params(
            graph_dn=args.graph_dn,
            vis_radius=args.vis_radius,
            roi_margin=args.roi_margin,
        ),
    )
    graph = routing.Graph(w)
    dij = graph.all_terminal_paths()
    for s in m.diagnostics + w.diagnostics:
        log("  " + s)
    core = None
    if args.engine in ("auto", "cpp"):
        from heatrouting import core_bridge

        if core_bridge.available():
            core = core_bridge.CoreBridge(w, dij, graph=graph)
        elif args.engine == "cpp":
            raise SystemExit(
                "Current C++ kernel not built: python -m heatrouting.core.build"
            )
    log(f"[3/5] search ({'C++ kernels with reference geometry' if core else 'Python'})")
    solver = search.Solver(w, dij, log=log, top_k=args.top_k, core=core)
    _, best = solver.run(restarts=args.restarts)
    log("[4/5] independently validate candidate outputs")
    evs = []
    rejected = []
    for candidate in solver.variants(best):
        ev = network.finalize(w, solver.materialize(candidate))
        result = export.to_collection(w, [ev])
        report = validate(m.input_data, result)
        if report["valid"]:
            evs.append(ev)
        else:
            rejected.extend(report["errors"])
    if not evs:
        report = {
            "valid": False,
            "errors": rejected or best.violations,
            "spec_version": rules.SPEC_VERSION,
        }
        (out / "validation.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False)
        )
        raise ValidationError("No valid variant: " + str(report["errors"][:12]))
    report = export.to_geojson(w, evs, out / "result.geojson")
    (out / "validation.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    )
    log("[5/5] reports")
    lines = [
        "# Current-spec run",
        "",
        f"Input: `{args.input}`; rules: {rules.SPEC_VERSION}",
        "",
        "All exported variants passed independent 2D validation.",
        "",
        "| Rank | Score | Cost (rubles) | New length (m) | Unconnected IDs |",
        "|---|---|---|---|---|",
    ]
    for i, ev in enumerate(evs, 1):
        t = ev.totals
        lines.append(
            f"| {i} | {ev.score:.6f} | {t['cost']:.2f} | {t['new_length']:.2f} | {ev.unrouted} |"
        )
    lines += ["", "## Diagnostics", ""] + [
        "- " + s for s in m.diagnostics + w.diagnostics
    ]
    if evs[0].unrouted:
        lines += [
            "",
            "Unconnected demands indicate search failure, not proof of physical impossibility.",
        ]
    if not args.no_figures:
        from heatrouting import viz

        viz.fig_world(w, str(out / "01_world.png"))
        for i, ev in enumerate(evs, 1):
            viz.fig_result(w, ev, str(out / f"03_option_{i}.png"), ev.label())
        viz.fig_variants(w, evs, str(out / "04_variants.png"))
        if not args.no_frames:
            frames = out / "frames"
            frames.mkdir(exist_ok=True)
            paths = []
            for i, (label, ev) in enumerate(solver.history):
                if ev.violations:
                    continue
                path = frames / f"step_{i:03d}.png"
                viz.fig_frame(
                    w, network.finalize(w, solver.materialize(ev)), str(path), label
                )
                paths.append(str(path))
            if paths:
                viz.make_gif(paths, str(out / "05_process.gif"))
    elapsed = time.time() - start
    lines += ["", f"Elapsed: {elapsed:.2f} seconds."]
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    log(f"done in {elapsed:.1f}s: {len(evs)} validated variants -> {out}")


if __name__ == "__main__":
    main()
