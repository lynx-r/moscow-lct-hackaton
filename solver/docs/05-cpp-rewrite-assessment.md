# 5. C++ rewrite: assessment

Question: should the routing core move to C++, and if so, which parts and when? Assessed 2026-09-16 against the pre-MVP profile ([04-pre-mvp-solver.md](04-pre-mvp-solver.md)).

Short answer: not a full rewrite. Do the algorithmic optimisations in Python first, because they are needed anyway and give most of the speed. Then move two kernels to C++ behind the same interfaces, not for the sample's sake but because they unlock algorithms Python cannot run: the plan evaluator (millions of evaluations for annealing and exact per-group trees) and the line-of-sight engine (city-scale visibility graphs). Everything else stays in Python.

## 5.1 Where the time goes today

One plan evaluation costs about 6 ms. The profile of one search restart (4 073 evaluations, 24 s):

| Share | What | Language issue or algorithm issue |
|---|---|---|
| 31% | recomputing where each terminal edge exits its building (Shapely intersection per evaluation) | algorithm: it is a per-edge constant, compute once |
| 17% | cross-group crossing check (STRtree per plan) | algorithm: check only the changed group |
| 15% | rebuilding the merged tree | algorithm: memoise per (terminal set, tie-in) |
| 10% | Shapely object creation | language: object overhead, disappears in C++ or with NumPy arrays |
| 8% | reconstruction portions (Shapely substring) | algorithm: geometry only for the final plan |
| rest | flows, DN pass, dictionaries | language: interpreter overhead |

About two thirds of today's cost is algorithmic waste that any language would pay. That is why Python optimisation comes first.

## 5.2 Estimated effect per component

Estimates, not measurements, except the first column.

| Component | Python now | Python optimised | C++ kernel |
|---|---|---|---|
| Visibility graph, sample (736 vertices, 123 k pairs) | 3.6 s | about 2 s | about 0.1 s |
| Visibility graph, city scale (50 k vertices, radius-limited) | minutes | 1 to 2 min | 5 to 10 s |
| One plan evaluation | 6 ms | about 1 ms | 20 to 50 µs |
| One search restart (4 k evaluations) | 24 s | about 3 s | under 0.5 s |
| Full search (16.6 k evaluations) | 110 s | about 15 s | about 1 s |
| Exact Steiner tree per group by DP, 8 terminals | hours | hours | seconds |
| Simulated annealing, 1 M evaluations | 1.7 h | 17 min | under 1 min |
| Dijkstra, LP and MILP bounds | already in C (SciPy, HiGHS) | same | same |
| Ingest, PostGIS, service, export, figures | I/O bound | same | no gain |

The evaluation kernel is small: a tree of tens of nodes, a DN pass, about 30 existing portions, a crossing check over about 50 segments. In C++ that is tens of microseconds. The gain is not "the same search 100 times faster" but a different class of search.

## 5.3 Options

**A. Stay in Python and optimise.** Memoised group results, precomputed exit points, changed-group crossing checks, lazy greedy merging, NumPy arrays instead of Shapely objects inside the evaluator, multiprocessing over restarts. Effort: two to three days. Result: full run in roughly 10 s at district scale. Enough for the demonstration and the 50-user service on datasets like the sample. No new algorithms become possible.

**B. Hybrid: C++ kernels behind the existing interfaces.** Move `RoutingOracle.build` (line-of-sight tests), `build_tree`, `evaluate`, an exact Steiner DP, and later the local-search loop into one C++ library exposed with pybind11 or nanobind. Python keeps ingest, the canonical model, obstacle inflation and union (GEOS through Shapely is already C++), tie-in candidates, the service, export, viz, and the LP bounds. Data crosses the boundary as NumPy arrays: vertices, CSR graph, section polylines with offsets, rules as one JSON read by both sides. Effort: one to two weeks for someone fluent in C++ and CMake, after A. Result: the times in the last column, plus annealing and exact per-group trees.

**C. Full C++ core with a Python or C++ service.** Needs GeoJSON parsing (nlohmann/json), CRS transforms (PROJ), buffering and union (GEOS C++ API or Clipper2), everything in 5.2, and its own tests. Effort: several weeks. Gains over B: none that the task needs. Costs: two implementations of the rules, a second geometry stack to keep robust, and a much thinner team able to touch the core. Only justified if the platform requirement flips back to a compiled runtime or the team is C++ first (Q24).

## 5.4 If B: how to do it without losing the elegance

- **Same abstractions, two implementations.** The C++ side implements exactly `path oracle`, `tree builder`, `evaluate(plan)`. The Python versions stay as the reference and as the test oracle: every C++ result must equal the Python result on the sample and on the synthetic scenarios (differential tests). This makes the rewrite safe and keeps the validator independent.
- **Rules stay data.** Tables 4.1 and 4.2, the restriction registry, and the formulas live in one JSON file loaded by both sides. No number is typed twice.
- **Geometry robustness.** Use GEOS from C++ for polygon predicates (headers are present on this machine: GEOS 3.12), or a small exact-predicate segment library. Do not hand-roll epsilon geometry for the line-of-sight engine.
- **Build and ship.** CMake with scikit-build-core, a wheel built in the Docker image (Ubuntu 22, g++ 11 supports C++20). Keep a pure-Python fallback path so the service still runs if the extension fails to build.
- **Order of kernels.** Evaluator and tree builder first (they gate the search), then the exact Steiner DP (improves the upper bound and measures the heuristic gap), then line-of-sight (only needed at city scale), then the search loop itself.

## 5.5 Middle grounds

- **Numba** is installed here. It can compile the DN pass and reconstruction arithmetic, but the bottleneck is object overhead, not arithmetic, so it pays only after the evaluator is array-based. It does not help the geometry.
- **Rust with PyO3** would give the same performance as B with a safer geometry stack (the `geo` crate). Reasonable if the team prefers it; the boundary and the order of kernels are identical.

## 5.6 What was done on 2026-09-16

Option A and the first kernel of option B were both implemented the same day, in that order, on the sample:

| Step | Search time | Note |
|---|---|---|
| Prototype | 110 s | 6 ms per evaluation, two thirds of it algorithmic waste |
| Option A, Python optimised | 5.2 s | identical plan; caches per group and per group pair, NumPy predicates, deferred geometry |
| Option B, kernel for trees and evaluation | 0.5 s | identical plan; 11 to 19 µs per evaluation; pybind11, 480 lines of C++, built with one g++ command |

The differential test found one real discrepancy: the crossing predicate on exactly overlapping segments (two groups leaving one chamber) depended on fused multiply-add rounding. Both implementations now use a 1e-6 m² tolerance and the kernel is compiled with `-ffp-contract=off`. That is precisely the class of bug section 5.4 warned about, caught by the reference-implementation rule.

The line-of-sight engine and the exact Steiner DP remain in Python and are the next kernels if the hidden dataset needs them.

## 5.7 Decision and triggers

Decision: option A done, B's evaluator kernel done. Add further kernels when any of these holds:

1. We want annealing or exact per-group trees to close the gap against the lower bound (see the bounds discussion in the plan).
2. A run on the hidden dataset exceeds the demonstration budget after A.
3. The hidden dataset's region of interest pushes the visibility graph past about a minute.

Do not start C unless Q24 says the team is C++ first, or the runtime requirement returns to a compiled platform.
