# 6. Tightening the lower bound: two routes

Context (sample dataset, 2026-09-16): the solver's best plan scores 13.49; the multi-commodity LP relaxation proves S ≥ 9.25. The gap of 4.24 score units (31.5%) is slack in the relaxation, and it splits cleanly into two kinds, because our own plan priced under the LP's cost model is worth 11.94:

| Where the slack comes from | Score units | Closed by |
|---|---|---|
| Fractional arc usage and tie-ins (x between 0.14 and 0.86 in the LP solution) | 2.69 | integrality: branch-and-bound, or set partitioning |
| DN cost per metre replaced by its convex hull (underprices our pipes by 49.4 M, of which 23.2 M on the DN200 trunk and 10.2 M on DN125) | 1.38 | DN choice binaries, or exact per-group trees |
| Branch chambers priced at the smallest tier (6 M) | 0.17 | tier binaries, or exact per-group trees |
| Rules dropped by the relaxation (maximum length, degree, chamber reconstruction) | 0 on this plan | nothing needed here |

So no amount of branching on the current model can prove better than 11.94 (an 11.5% gap); proving tighter needs the cost model upgraded as well. Both routes below do that, in different ways.

## 6.1 Route A: branch-and-bound on the multi-commodity model

**Mechanism.** The root LP gives 9.25. Branching on a fractional arc variable x_a creates two children, each with an LP value at least the parent's; the dual bound is the minimum over open nodes and rises as nodes are resolved. Every integer solution met on the way is a real plan (a set of trees on the visibility graph), which the reference evaluator can price exactly, so the MILP is also a second optimiser: its incumbents may beat the heuristic. With integrality, a used arc pays its full fixed charge and a used tie-in its full 5 M, which removes the 2.69.

**Why the current setup cannot do it.** The root LP alone takes 12 minutes by interior point and over 20 by simplex, SciPy exposes no warm starts, cuts or callbacks, and branch-and-bound needs hundreds of warm-started node LPs. The one-commodity model, which is small enough for HiGHS, reached only 5.51 in 300 s because its root bound is 1.98.

**Three changes make it practical.**

1. *Cut formulation instead of 17 commodities.* Keep only x_a (22 k binaries), the total flow F_a for the cost, and the hull variables. Enforce connectivity with Steiner cuts, Σ x_a over the arcs entering S ≥ 1 for every vertex set S that contains a terminal but not the root, generated lazily: for an integer solution, breadth-first search from the root over the used arcs; a terminal not reached yields a violated cut (S is the set of vertices from which that terminal is reachable). For fractional solutions, a max-flow separation. This relaxation has the same value as the multi-commodity LP, with a twentieth of the size, so node LPs take seconds.
2. *Reductions from the root LP.* Reduced-cost fixing: an arc whose reduced cost exceeds UB minus LB cannot belong to a better solution and is fixed to zero. With a 150 M UNI gap this removes long and off-corridor arcs, and the Steiner degree and special-distance tests remove more.
3. *Model upgrades that raise the ceiling.* DN choice binaries y_{a,d} with Σ_d y = x_a, F_a ≤ Σ_d cap_d y_{a,d} and cost Σ_d len_a c_d y_{a,d} make the step cost exact (ceiling 11.94 to 13.32); chamber tier binaries at branch vertices bring it to 13.49; reconstruction step binaries z_p (flow through a portion ≤ headroom + M z_p) are exact when one DN step suffices, as here. After these, the model's optimum is the true graph-restricted optimum except for the maximum-length rule, which only ever adds cost to a plan and therefore keeps the bound valid.

**Tooling and expectation.** SCIP through PySCIPOpt (free, supports lazy constraints) or Gurobi. With the cut formulation, reductions and DN binaries, I would expect a proof within a few percent of 13.49 in minutes to an hour on this instance, or a better plan. Effort: two to three days.

## 6.2 Route B: set partitioning with exact per-group trees

**Mechanism.** A plan is a partition of the demands into groups with one tie-in each. A group's cost is self-contained; groups interact only through the existing network (reconstruction of portions, chamber degree and tiers) and through crossings between trees. That structure suggests pricing groups exactly and letting a small master problem choose the partition.

*Columns.* A column is (subset of demands S, tie-in candidate c, tree, cost). Its exact cost under the full rules is construction, branch chambers, the tie-in and its chamber tier.

*Master MILP.* Binary x_i per column, binary z_p per reconstruction portion. Constraints: each demand in exactly one chosen column; Σ flow_i x_i over columns injecting below portion p ≤ headroom_p + M z_p; Σ root-children of columns at chamber c ≤ 4 minus the chamber's existing degree; x_i + x_j ≤ 1 for pairs of columns whose trees cross; chamber reconstruction linearised the same way. Objective: Σ cost_i x_i + Σ (L_p c_recon + λ L_p) z_p. Thousands of columns and about fifty rows: seconds to solve.

*Pricing a column exactly: the extended Dreyfus-Wagner recursion.* Let T(v, S) be the minimum cost of a tree that spans terminal subset S and hangs from graph vertex v. The property that makes this work is that the flow on any edge is the sum of the demands below it, so the DN of an edge is fixed by the terminal subset it serves, and the recursion needs no extra state:

- start: T(v, {t}) is the shortest legal path from t to v priced at the DN of t's own flow;
- merge at v: T(v, S) ≤ T(v, S') + T(v, S ∖ S') + chamber cost at the tier of DN(flow(S)), because the largest DN adjoining a branch node is its parent edge's;
- extend along an edge: T(v, S) ≤ T(u, S) + len(u, v) × (c_new(DN(flow(S))) + λ).

One run per subset computes T(v, S) for every vertex v, so every tie-in candidate is priced at once. Cost: 3^k n merges plus 2^k shortest-path sweeps; for k = 8 on 736 vertices about 30 M operations, milliseconds in the C++ kernel, seconds in NumPy; k = 13 is still seconds per subset. The recursion ignores the maximum-length rule, so T is a valid lower bound on the column; evaluating the DP tree with the reference evaluator gives the exact cost, usually equal.

*Column family.* Enumerate subsets that are connected in a proximity graph of the demands (nearest neighbours within about 150 m) up to size K, times the best m tie-in candidates by summed distance. On the sample this is thousands to tens of thousands of columns, all priced in well under a minute.

**What comes out.**

- With exact costs: the optimum over the family under the full rules, a certified plan and probably an improvement on 13.49, since the shortest-path-heuristic trees typically lose one to four percent against exact Steiner trees. The master also explains the answer: which groupings were considered and why this partition wins.
- With the DP lower-bound costs and a complete family: a valid lower bound. The family is complete only with all subsets, which is too many to price by DP at k = 17, so the rigorous version is column generation: solve the master's LP relaxation, read the duals π_t, and search for a column with negative reduced cost, min over S of cost(S, c) − Σ_{t ∈ S} π_t per candidate c. That pricing problem is a rooted prize-collecting Steiner arborescence with flow-dependent costs, solvable by the DP for bounded k or by Route A's cut-and-branch on a single root. When no such column exists, the master LP value is a valid lower bound, and branch-and-price closes the rest. Integrality gaps of set-partitioning masters are usually small, so this bound should land far closer than 11.94.

## 6.3 Comparison and recommendation

| | Route A | Route B |
|---|---|---|
| What it exploits | generic MILP machinery | the problem's structure: grouping is the hard part, trees are exactly solvable per group, coupling is tiny |
| Bound quality | ceiling 11.94 until DN and tier binaries are added; then near-exact | near-exact per group by construction; master gap small |
| Upper bound | incumbents as a by-product | the main product: exact optimum over a natural family |
| Explainability | low | high (which groupings, why this one) |
| Code | cut-and-branch loop plus a solver with callbacks | DP in the kernel, master in SciPy or SCIP, pricing loop |
| Effort | two to three days with SCIP | three to five days; the DP is the planned exact-Steiner kernel |

Recommendation: Route B first, because it improves the plan and gives the jury a grouping argument, then Route A's cut-and-branch as the certificate if a formal gap is wanted. Both share one caveat: they bound the graph-restricted problem, where branch chambers sit on obstacle corners. Adding candidate Steiner vertices (Fermat points of nearby terminal triples) to the graph before bounding extends the guarantee toward the continuous problem.

## 6.4 Results (2026-09-16)

### Route B, implemented

- `heatrouting/core/core.cpp`: extended Dreyfus-Wagner DP (`steiner_dp`, `dp_root_costs`, `dp_tree`) with tables for one, two and three branches per vertex, so the three-outgoing-sections rule at chambers is exact, and a per-candidate branch limit at the root (two for a mid-section tie-in, four minus the existing degree at a chamber). Edge cost per subset is the DN of the subset flow plus λ; a merge charges the chamber tier of the parent edge's DN. Only the maximum-length rule is relaxed, so the DP cost is a lower bound and the evaluator's cost of the same tree is exact.
- `heatrouting/partition.py`: pricer (DP tree to Group, exact and lower-bound column costs, degree filter), proximity graph, connected-subset enumeration, set-partitioning master (SciPy HiGHS) with reconstruction step binaries on injected flow, chamber degree and reconstruction, and lazily added crossing conflicts.
- `run_partition.py data/data.geojson --out out/sample_cpp --max-size 6 --per-subset 5 --seeds 20`.

| | Heuristic plan | Route B plan |
|---|---|---|
| Score S | 13.49 | 12.62 |
| Cost C | 275.6 M | 256.9 M |
| New pipe | 1 926 m | 1 807 m |
| Tie-ins | 106, 107, section 119, section 129 | same four |
| Reconstruction | none | none |

The gain comes from the trees, not the grouping: at chamber 106 the exact tree for the 13-demand group costs 295.4 M against 319.9 M for the shortest-path heuristic (8%), and the two singletons improved by 3.5 M each. Pricing took 154 s for 4 058 subsets (up to six demands, 20 297 columns); the master solved in 23 s with no crossing conflict. With the DP lower-bound costs the master gives 12.62 as well and its LP relaxation 12.51, so within the family the partition is optimal and the master's integrality gap is under 1%. The family is not complete (subsets above six demands enter only as seeds from the heuristic pool), so this is not a bound on the full problem; that is Route A's job.

Two observations for later: the DP prices true lengths, so it will run under a demand's own building where that is cheapest (the routing metric's in-building weight does not apply to it), and the DP tables must not be cached per subset (2^k times the vertex count each; the pricer keeps only the current one).

### Route A, implemented and solver-limited

- `heatrouting/cutbound.py`: cut model (arc binaries, aggregate flow, hull cost or DN binaries, reconstruction chords, branch chamber binaries with at most three outgoing sections), Steiner cuts separated by max-flow on LP solutions and by breadth-first search on integer solutions, ring cuts around every terminal as a seed, reduced-cost fixing from any LP sharing the arc layout, and materialisation of an integer solution into groups for exact evaluation.
- `run_cutbound.py data/data.geojson --milp-seconds 300`: seventeen-commodity root LP, reduced-cost fixing, ring cuts, cut-and-branch rounds.

| Step | Result |
|---|---|
| Root LP (17 commodities, interior point) | S ≥ 9.25 in 1 107 s |
| Reduced-cost fixing against the plan's model cost (426.6 M) | gap 96.3 M, 0 of 20 973 arcs fixed |
| Cutting planes from the single-commodity LP | 1.98 to 1.99 in four rounds of 14 s, abandoned |
| Cut-and-branch MILP with 136 ring cuts, 300 s | no feasible integer solution found, no dual bound |

With HiGHS through SciPy the certificate stays at 9.25. That solver has no warm start, no MIP start and no callbacks, so each cut round is a cold LP or MILP, and the branch-and-bound could not even reach feasibility on 21 k binaries in five minutes.

**SCIP branch-and-cut** (`heatrouting/scipbound.py`, `run_scipbound.py`, SCIP 10 through PySCIPOpt, installed the same day). The same cut model with a constraint handler: integer solutions are checked by breadth-first search, violated cuts are added at nodes, fractional LP solutions are separated by max-flow at every node. A complete start solution (the DP trees of the heuristic plan, 12.67 exact) is accepted as the incumbent.

| Run | Result |
|---|---|
| 90 s | root node, 272 fractional cuts, dual bound 4.02 |
| 1 500 s | still the root node, 595 fractional cuts, dual bound 4.39; incumbent 12.67 exact (11.31 under the model) |

So the certificate is still 9.25, from the seventeen-commodity LP: 26.7% below the Route B plan and 31.5% below the heuristic. The obstacle has moved from the solver to the separation: one min-cut per terminal per round, at about two seconds a round, raises the root bound by a few hundredths per round, and the root needs hundreds of rounds to reach what the flow formulation gives directly. Reduced-cost fixing is inert at this gap (96 M is more than the reduced cost of any arc).

What would move it, in order: (1) stronger separation, several nested and back cuts per terminal per round with creep flow, which is how dedicated Steiner codes such as SCIP-Jack reach the flow bound in seconds; (2) the multi-commodity formulation inside SCIP with warm-started node LPs, expensive per node but starting at 9.25; (3) DN binaries so the ceiling becomes the true optimum instead of 11.94. The model, separation, fixing and materialisation code carries over to all three.

### Where this leaves the two routes

| | Route B | Route A |
|---|---|---|
| Delivered | a better plan, 12.62, provably optimal within a 20 297-column family | the formulation, two solver back-ends (SciPy, SCIP) and the machinery; bound unchanged at 9.25 |
| Cost | 3.5 minutes per run, no new dependencies | 24 minutes (SciPy) or 25 minutes (SCIP) per run without passing the LP bound |
| Next step | larger families (size 7 to 8, more candidates), column generation for a rigorous bound, the in-building weight in the DP | stronger separation (nested and back cuts, creep flow), then DN binaries |
