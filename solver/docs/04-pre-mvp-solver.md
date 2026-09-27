# 4. Pre-MVP solver (Python)

A working prototype of the pipeline in [03-implementation-plan.md](03-implementation-plan.md), built on 2026-09-16 against the sample dataset. It is the phase 1 to 3 core without the service: ingest, canonical model, obstacle world, visibility graph, one-tree evaluation, agglomerative search with restarts, three options, matplotlib views of the process and the results, and a GeoJSON export per appendix 10.

## 4.1 Run

```bash
python run_sample.py data/data.geojson --out out/sample            # about 3 minutes with process frames
python run_sample.py data/data.geojson --out out/sample --no-frames  # about 1.5 minutes
python run_sample.py data/data.geojson --flow-policy fraction --flow-fraction 0.5   # existing-flow policy (Q3)
```

Requirements: Python 3.12 or 3.13 with `shapely` 2, `pyproj`, `numpy`, `scipy`, `matplotlib`, `pillow`.

Outputs in `out/sample/`:

| File | Content |
|---|---|
| `01_world.png` | obstacles, clearance zones, existing network by DN, tie-in candidates, demands |
| `02_visibility.png` | the visibility graph and one example shortest legal path |
| `03_option_k.png` | option k: new sections coloured by DN, tie-ins, chambers, technical nodes, reconstruction, cost box |
| `04_variants.png` | the three options side by side |
| `05_process.gif`, `frames/` | one frame per accepted search step, sections coloured by group |
| `result.geojson` | all options in one FeatureCollection with the appendix 10 attribute sets |
| `summary.md` | diagnostics, per-option cost breakdown, tie-in and reconstruction tables, search log |

## 4.2 What is implemented

| Module | Implements | Plan reference |
|---|---|---|
| `heatrouting/rules.py` | tables 4.1 and 4.2, DN selection, run limits, costs, tiers, penalty, score, λ, restriction registry with the `railway` alias and an unknown-type default | 3.1 rules |
| `heatrouting/model.py` | both schemas; topology from geometry (0.5 m snap, orientation by search from the source); chamber DN from adjoining sections; host building by containment; existing-flow policy; diagnostics | 3.2 canonical model |
| `heatrouting/world.py` | inflation by clearance plus half width with mitre joins; ROI; convex corners of the union of inflated obstacles as graph vertices; tie-in candidates every 25 m plus chambers plus foot points, snapped to chambers within 10 m; exemptions for terminals and candidates inside clearance zones (the own building is passable, other buildings are not) | 3.2 world |
| `heatrouting/routing.py` | visibility graph with vectorised line-of-sight tests, in-building length weighted by `k_inside` in the routing metric, Dijkstra from every terminal with other terminals impassable | 3.2 oracle |
| `heatrouting/network.py` | shortest-path heuristic trees rooted at the tie-in with attachment at the last crossing; one-tree pass: flows, lazy-upgrade DN assignment with the maximum-length rule and technical nodes, branch chambers, tie-in chambers, degree checks, portion-wise reconstruction with summed flows, chamber reconstruction, costs, score, cross-group crossing check | 3.2 evaluation |
| `heatrouting/search.py` | construct, greedy agglomeration, re-tie / move / split moves, seeded restarts with random candidate masks, pool of distinct plans, option 2 by re-run with option 1's tie-in objects forbidden, option 3 by a different partition | 3.2 search |
| `heatrouting/viz.py`, `export.py` | figures, process GIF, GeoJSON | 3.3 viewer, export |

## 4.3 Result on the sample

Best option (existing-flow policy `zero`, three restarts agree):

| Group | Demands | Flow t/h | Tie-in | New DN at tie-in |
|---|---|---|---|---|
| A | 2, 4 | 66.5 | section 119 at 5 m, the DN500 head just upstream of chamber 108 | DN200 |
| B | 16 | 76.3 | chamber 107 (DN300) | DN200 |
| C | 17 | 20.6 | section 129 at 66 m (DN400 body), new chamber | DN100 |
| D | the other 13 | 325.4 | chamber 106 (DN300), the downstream end of the DN300 stretch | DN300 |

Score 13.49; cost 275.6 M UNI (sections 209.6 M, chambers 46.0 M, tie-ins 20.0 M); 1 926 m of new pipe; no reconstruction; no unrouted demands; no rule violations found by the evaluator.

The plan is the behaviour the analysis predicted. The DN300 stretch carries B, C and D, 422 t/h against a 437 t/h capacity, and the remaining 66.5 t/h is carried north to the DN500 head instead of forcing reconstruction. With the in-building weight at 1 (its first run) the greedy search settled in a worse basin (score 16.0 with 60 m of reconstruction of section 121 and reconstruction of chamber 108), which is why restarts were added.

Option 2 (score 13.69) splits the large group differently; option 3 (score 13.81) uses mid-section tie-ins with new chambers on sections 118, 124, 126 and 128 instead of the chambers.

## 4.4 Performance and engines

Measured on the sample (17 demands, 736 graph vertices), search phase only (three restarts plus the constrained re-run, 29 k plan evaluations), same plan from every configuration:

| Configuration | Search time | Per evaluation |
|---|---|---|
| First prototype (Shapely objects, no caching) | 110 s | 6 ms |
| Python optimised: cached trees per (demand set, tie-in), cached pairwise crossing counts, cached ancestor chains, NumPy segment predicates, piece lists and geometry built only for shown plans | 5.2 s | 0.18 ms warm |
| C++ kernel (`heatrouting/core/core.cpp`, pybind11): trees, DN pass, reconstruction, chambers, crossings, on plain arrays with its own caches | 0.5 s | 11 to 19 µs |

Outside the search the run spends about 3.6 s on the visibility graph and 1 to 2 s per figure; a full run without frames takes 5.5 s with the kernel.

The kernel is a second implementation of `build_tree`, `eval_group` and `evaluate`, mirrored line by line. Python remains the reference: a differential test evaluates every plan a search generates with both and requires identical totals (8 212 plans, worst relative difference 5e-16 after making the crossing predicate tolerance-based and compiling without floating-point contraction). Figures, export and the summary always come from the Python evaluator through `Solver.materialize`. Rules and model data are passed into the kernel as arrays and a `Rules` record filled from `rules.py`; no number is typed in C++.

```bash
pip install pybind11 && python -m heatrouting.core.build     # builds heatrouting/core/_core*.so with g++
python run_sample.py data/data.geojson --engine cpp            # auto uses the kernel when built, python otherwise
```

## 4.5 Lower bound

`run_bounds.py` builds the Level 1 relaxation described in the C++ assessment (multi-commodity flow on the visibility graph, convex-hull DN cost per metre, chord-priced reconstruction, relaxed arc usage) and solves it with HiGHS through SciPy. `--clusters plan|one` aggregates commodities (a weaker but valid relaxation that shrinks the model), `--milp-seconds` continues with binary arc usage and reports the branch-and-bound dual bound. Logs are in `out/sample/bounds*.log`.

Results on the sample against the solver's upper bound S = 13.49:

| Relaxation | Size | Solve | Lower bound on S | Gap |
|---|---|---|---|---|
| One commodity, LP | 64 k variables | 7 s | 1.98 | 85% |
| Four commodities (the best plan's groups), LP | 127 k variables | 38 s | 4.34 | 68% |
| Seventeen commodities, LP (interior point) | 399 k variables, 1.9 M nonzeros | 732 s | 9.25 | 31.5% |
| One commodity, MILP dual bound after 300 s | 64 k variables, 22 k binaries | 300 s | 5.51 | 59% |

So the plan is provably within 31.5% of the optimum of the graph-restricted problem, and the true gap is smaller than that: the LP solutions show that most of the remaining slack is the relaxation's own. Arc usage comes out fractional (0.1 to 0.7 at the tie-ins), so the 5 M tie-in charges, the chamber tiers and the fixed part of the per-metre price are paid in fractions, and the DN hull prices a 325 t/h trunk at 76% of its real cost. Aggregating commodities loosens the bound quickly (four commodities 4.34, one commodity 1.98), which is the multi-commodity formulation doing its job. Branch-and-bound on the one-commodity model closed some of that in five minutes (5.51) and, notably, its best integer solution under the relaxed cost model scored 13.52, no better than the heuristic's 13.49 under the full rules. Tightening further needs branch-and-bound on the seventeen-commodity model with a better solver, or the exact set-partitioning route with per-group Steiner trees, which the C++ kernel makes affordable.

## 4.6 Exact trees and set partitioning

`run_partition.py` (Route B of `docs/06`) replaces the shortest-path-heuristic trees by exact ones from the kernel's Steiner DP and chooses the grouping with a set-partitioning MILP. On the sample it lowers the score from 13.49 to 12.62 in about 3.5 minutes, keeping the same four tie-ins. `run_cutbound.py` (Route A) computes the certificate side: the seventeen-commodity root bound, reduced-cost fixing, ring cuts and cut-and-branch with lazily separated Steiner cuts.

## 4.7 Amendments of 2026-09-22 in the solver

Implemented in the reference evaluator and mirrored in the kernel (differential test: 8 321 plans, 0 mismatches): run lengths at a branch are summed per DN (the subtree reading); a run over its limit may raise the DN by one table size only, beyond that it is a violation; a section leaving a turn that is neither straight, 45° nor 90° (tolerance 3°) costs 1.5 times; a demand may be left unconnected when the penalty beats its route (search moves `drop` and `reconnect`, the penalty is the arbiter). The appendix ranking formula is kept as the default. On the sample the bend surcharge alone moves the heuristic plan from 13.49 to 14.15 (three restarts; about fifteen sections leave non-standard turns), because visibility-graph paths bend at whatever angle the obstacle corner has; the routing metric and the DP do not yet steer toward 45° and 90° turns, which is the next change (a turn-aware graph search or a post-pass that squares corners where clearance allows). No demand is dropped on the sample: the cheapest route is far below the penalty.

## 4.8 Scaling on synthetic cities

`tools/make_toy.py` generates a block-grid city in the sample schema (buildings as `restriction_type=oks`, a tree-shaped existing network with chambers, demands inside random buildings, water and a railway corridor). Measured on 2026-09-22 with the kernel engine, no frames, three restarts plus the constrained re-run:

| Dataset | Buildings | Demands | Graph | Visibility graph | Search | Total | Peak memory | Score |
|---|---|---|---|---|---|---|---|---|
| Sample (ZIL) | 85 | 17 | 736 vertices, 11 k edges | 3.6 s | 0.5 s | 5.5 s | 0.3 GB | 14.15 |
| Toy medium, 1.8 × 1.4 km | 380 | 60 | 1 341 vertices, 29 k edges | 0.8 s | 3.2 s + 1.2 s | 9.5 s | 0.3 GB | 66.05 |
| Toy large, 3.9 × 2.7 km | 1 623 | 150 | 5 485 vertices, 130 k edges | 3.0 s | 32 s + 10 s | 60 s | 0.6 GB | 217.40 |

Two search changes were needed to get there: merges are scored lazily (a pair is re-scored only when it touches the last merged group or comes up as the best, with gains stored as deltas to the current base plan) and only pairs of groups within 800 m of legal path are tried; the pool of distinct plans is capped at 4 000. Before that the medium toy took 149 s and 8.6 GB. Lazy merging costs quality on small instances (the sample went from 14.15 to 15.67), so plans with up to 30 demands keep the exhaustive greedy (medium toy: exhaustive 63.93 in 149 s, lazy 66.05 in 3 s). The visibility graph is not the bottleneck at this scale; at a full city (tens of thousands of buildings) it will be, and the region-of-interest clipping plus the any-angle fallback of the plan apply.

## 4.9 Known limitations, in the order they should be removed

1. **No portals.** Crossable restrictions (road, tram, railway, gas, power, existing heat network) are bypassed, not crossed. Irrelevant on the sample, essential for the final dataset.
2. **No alongside rule for lines** and no 1 m clearance from the existing network for new sections running beside it.
3. **Branch chambers sit on graph vertices** (inflated obstacle corners). Two chambers can land a few metres apart. The Fermat-point relocation move is not implemented.
4. **Entry into the own building** is a straight segment from the outside vertex to the connection point, weighted by `k_inside` in the routing metric only; costs follow the rules with no surcharge. Whether the in-building segment counts as new network is Q5.
5. **Evaluator, not validator.** Rule checks are done by the same code that optimises. The independent validator of plan 3.2 does not exist yet.
6. **Existing flows are a policy** (`zero` by default). Option ranking on the sample changes under other policies; see Q3.
7. **Search is greedy with restarts**; no simulated annealing, no constrained re-optimisation for option 3.
8. **One project per file**; disconnected networks are reported, not routed (Q29).
9. Overlapping parallel sections of two groups leaving the same chamber are not flagged (only proper crossings are).
