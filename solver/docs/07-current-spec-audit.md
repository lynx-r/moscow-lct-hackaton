# 7. Current specification and algorithm audit

> Historical pre-migration findings. The subsequent implementation and its remaining
> limitations are documented in [Current solver and verification](08-current-solver.md).

Reviewed 2026-09-27. Scope: the two newly supplied Word documents, the corrected dataset, `specs/`, design notes 01–06, Python routing/evaluation/export, the C++ evaluator, and the optimization formulations. The programming-language requirement is excluded. This is a review; solver behavior has not been changed.

## Conclusion and source precedence

The prototype is a useful optimization foundation, but **does not currently comply with the latest mandatory 2D specification**. Some failures come from implementing the superseded September 22 interpretation; others are independent geometry, accounting, and export defects. A reported `viol=0` is not a compliance certificate.

The authoritative calculation rules for this review are [Техническое приложение ЛЦТ](<Техническое  приложение ЛЦТ.docx>) and [Разъяснение](Разьяснение.docx). Clarification 16 explicitly makes the current technical appendix authoritative when rules disagree. The Markdown specifications and earlier design notes still describe the older model. Retain general service requirements from `specs/spec.md` where the new appendix does not replace them.

The new appendix resolves several former open questions:

| Subject | Current rule | Superseded behavior still in the code/docs |
| --- | --- | --- |
| Existing network | No reconstruction; existing flow and spare capacity are not calculated | Upstream flow propagation, pipe/chamber reconstruction, headroom-driven choices |
| Diameter and length | Minimum DN satisfying flow and length; constant DN between flow changes; each continuous path checked separately | One-size upgrade limit, summed branches, DN changes partway along a constant-flow run |
| Turns | Any turn up to 90°; no bend surcharge | 1.5 surcharge outside 0°/45°/90° tolerances |
| Unconnected demands | Only when no compliant route was found | Deliberate dropping when penalty is cheaper |
| Railway | Prohibited, with 1 m clearance | Assumed tram-like crossing rule |
| Building approach | One straight final approach through a nearest boundary point of the containing building | General host exemption and a heuristic in-building weight |
| New tie-in chamber | Its construction price includes joining the existing network | Chamber price plus another 5 M tie-in charge |
| Existing chamber | 5 M per new incident linear section | 5 M per group, regardless of root degree |
| Polygon crossing angle | Relative to the boundary at entry | Older discussion of constructing an obstacle axis |
| Output | New pipes, new chambers, technical nodes, summaries; extra properties allowed | Separate tie-in and reconstruction objects; old summary fields |
| Ranking | Cost weight 0.7, new-length weight 0.3 | Formula coefficients match, but obsolete costs/lengths feed it |
| Optional depth | Separate result file/ranking; depth attributes, no required Z or fixed step | Older proposed Z output and discretized-depth assumptions |

## Corrected dataset

Both inputs have 144 features: 17 demands, 29 existing sections, nine chambers, one source, and 88 restrictions (85 buildings, two water objects, one railway). Total demand remains **488.72 t/h**. All 17 points are inside buildings; points 12 and 15 share building 92. Geometry validity checks passed for both datasets.

Changes relative to `data/data.geojson`:

- Sections 121–127: DN300 → DN400.
- Section 142: DN500 → DN400; section 143: DN500 → DN300.
- Section 116 geometry changes by about 0.073 m in Hausdorff distance; the source moves about 0.066 m and its name changes.
- Demand flows, demand positions, and restriction geometries are unchanged.

Consequently, the old explanation about avoiding the sample's DN300 upstream bottleneck is obsolete both because the data changed and because reconstruction/headroom are no longer part of the required model. The larger file size does not represent a larger routing instance. It still cannot exercise road, tram, gas, or power crossings.

## What the algorithm currently does

1. `model.py` projects WGS84 into EPSG:32637, infers topology from endpoints, associates chambers with nodes, and finds building hosts.
2. `world.py` buffers obstacles, collects obstacle corners, samples existing-network tie-ins roughly every 25 m, and adds demand projections and chamber candidates.
3. `routing.py` builds visibility edges within 400 m and caches Dijkstra paths from each demand. Paths through a terminal's building receive an artificial weight of three.
4. `network.py` constructs trees by repeatedly attaching the closest unconnected demand to the tree, joining proper crossings encountered during attachment. A bottom-up evaluator assigns flows/DNs and charges pipes, chambers, reconstruction, and penalties.
5. `search.py` greedily merges groups, then tries re-ties, moves, splits, and drops across seeded restarts. It caches groups and crossing counts. Larger instances use geographically restricted lazy merging.
6. Optional `partition.py` uses C++ subset/Steiner dynamic programming to generate group trees and a set-partitioning MILP to choose groups. `bounds.py`, `cutbound.py`, and `scipbound.py` provide separate relaxation/certificate experiments.

The architectural strengths remain useful: explicit groups/trees, flow aggregation, centralized reference tables, geometric host discovery, cached evaluation, a Python reference, a C++ kernel, and separate export/materialization. The capacity, pipe-price, chamber-tier, penalty, and score tables match the new appendix. Supply/return flow is not doubled. Returning fewer than three variants and omitting optional depth are allowed.

## Findings that block compliance

### 1. Diameter assignment implements the wrong constraints

**Source:** appendix §2.3; clarifications 1–2. **Code:** `network.py:309–359`, `rules.py:136–139`, mirrored in `core/core.cpp`.

The evaluator sums same-DN child lengths, upgrades only one table size, and splits a constant-flow edge at the old DN's length limit. All three conflict with the latest rules.

Two direct evaluator probes reproduce this:

- One 600 m chain carrying 3 t/h should use uniform DN150: DN125 permits only 554 m. The evaluator creates 181 m of DN50, then 419 m of DN65, and reports a violation instead of finding the allowed uniform diameter.
- Two 80 m branches carrying 1 t/h each, followed by a 50 m shared trunk, need only DN50: each path is 130 m, below 181 m. The evaluator instead splits the trunk into 21 m DN50 and 29 m DN65, with **no reported violation**.

Replace arbitrary edge splitting with assignment on maximal constant-flow chains. Continue same-DN run lengths through chambers; combine branches using the longest relevant continuing path, not their sum. Preserve nondecreasing DN toward the tie-in and allow all table sizes when necessary.

### 2. Geometry is not checked using actual segment diameters

**Source:** appendix §§2.2, 3.1, 4. **Code:** `world.py:34–82,184–210`; `routing.py:49–57`.

Obstacle inflation uses a fixed 0.5 m half-width and one building-clearance class selected from total demand. Actual half-width ranges from 0.20 to 1.725 m. Small pipes can be excluded unnecessarily; larger pipes can violate clearance. Length-driven upsizing can also change the required building-clearance class. Simplification and the 1 cm obstacle shrink are not followed by validation against original geometry.

The exemption for *any* obstacle containing a terminal/candidate is broader than permitted. Only the specified final approach to the terminal's own building is exempt, and other obstacles still apply. Weighting in-building distance does not enforce the nearest-boundary rule, especially for concave buildings or multiple demands sharing a building.

The fresh best sample output has a **0.186 m clearance shortfall** at building 88 on `v1-new-36`, using actual DN and original geometry. Of 15 terminal segments with a single boundary intersection, 14 enter farther from the terminal than its nearest boundary point by more than 3 cm; the largest excess is about 8.54 m. These are independent checks, not evaluator diagnostics.

### 3. Mandatory special crossings are absent

**Source:** appendix §4; clarifications 6–10. **Code:** `world.py:67–82`, `network.py:355–357`, `model.py:115–149`.

All restrictions are treated as bypass obstacles; every emitted pipe section is `base`. Existing `heat_network` features are not incorporated into the obstacle/crossing layer, so their alongside clearance and special-crossing requirements are absent too.

Implement straight special passages, road/tram entry angles ≥45°, 3 m extensions along the route beyond polygon boundaries, and 2 m each side of utility crossing points. Split at each applicable crossing-set change; use the maximum overlapping coefficient. Preserve straightness through artificial split points. Railway must remain prohibited with 1 m clearance, not become a tram portal.

The sample does not establish crossing compliance. Apparent sample intersections within fractions of a millimeter of a tie-in endpoint were excluded as rounding artifacts; this finding follows from the implementation and missing feature support.

### 4. Infeasible and unconnected solutions are mishandled

**Source:** appendix §§2.1, 2.5; clarification 15. **Code:** `search.py:106–122,195–228,295–333`; `network.py:393–424`.

`_drop` explicitly removes reachable demands to improve score. Conversely, `construct()` silently skips a demand with no reachable candidate, and `evaluate()` only discovers missing demands within submitted groups or an explicit dropped list. A probe with one unreachable demand returns zero groups, **an empty unconnected list, and zero penalty**.

Feasibility is a soft penalty of three score units per reported violation. There is no final validation gate before exporting variants. Proper-crossing tests omit collinear overlaps and endpoint-on-interior contacts. No hard ≤90° turn check exists. Thus the search can output invalid plans, and `viol=0` omits whole classes of constraints.

Compute unconnected IDs as the full input demand set minus validated connected demands. Remove economic dropping. Keep a feasible incumbent; repair/reject invalid candidates and validate every final variant independently. Search-radius or candidate-sampling failure should be reported as search failure, not proof of physical impossibility.

### 5. Chamber selection, incidence, and pricing are wrong

**Source:** appendix §§2.1, 2.4, 3.2; clarifications 11–14. **Code:** `world.py:125–135`; `model.py:181–269`; `network.py:424–490`.

Candidate snapping chooses the first chamber within 10 m without checking available incidence. An overfull chamber can therefore absorb a candidate that should create a new chamber; eligible alternatives are discarded. Topology only nodes existing section endpoints, so a chamber in the middle of a LineString is not reliably recognized as having two existing incidences.

New mid-section chambers incur an extra 5 M charge. Existing-chamber tie-ins are counted per group, undercharging when a group has multiple root branches. Multiple groups at the same new chamber are not consolidated into one physical chamber with aggregate degree and a single construction cost. Reconstruction can alter both candidate preference and costs despite being excluded by the current model.

Selection must consider final incidence, price each new ray entering an existing chamber, and charge a new chamber once with no extra tie-in fee. Node existing geometry at chambers and tie-in positions for degree counting; upstream capacity analysis is unnecessary.

### 6. Export does not describe the computed network correctly

**Source:** appendix §7. **Code:** `export.py:37–110`.

- `start_node_id` and `end_node_id` are reversed relative to the LineString coordinate order.
- Ordinary turn vertices get generated `n` references but no corresponding features. Turns without parameter changes should be internal LineString vertices.
- Roots reference obsolete `tie_in` objects instead of the actual existing/new heat chamber. A new root chamber receives a different `-ch` ID from the one referenced by its pipes.
- `construction_cost` contains only pipe construction, but must include new chambers and existing-chamber tie-ins.
- Required `existing_chamber_tie_in_count` and `existing_chamber_tie_in_cost` are absent. Extra properties do not substitute for required attributes or authorize obsolete object types.

The best freshly generated variant contains 43 pipe features, **20 distinct node IDs unresolved as permitted input/output nodes**, and **49 endpoint/reference mismatches among references that do resolve**. It emits four prohibited separate tie-in objects. Serialization alone is enough to fail this result.

### 7. Generality, validation, and service delivery remain incomplete

**Source:** appendix §1 and clarification 17; general specification §§2.1, 3. **Code:** `model.py`, `search.py`, root CLI scripts.

There is no explicit schema/geometry-validity gate. A self-intersecting building substituted into the input was accepted by `model.load` without an invalid-geometry diagnostic. Duplicate IDs overwrite dictionary entries; missing demand flow skips the demand. Mixed string/numeric IDs encounter untyped `sorted(...)` calls in search logging despite being valid input.

`json.load`, whole-world buffering, all-terminal distance arrays, quadratic topology scans, and unbounded group/pair caches are incompatible with an established 3 GB / 16 GB operating envelope without further engineering. The 400 m visibility radius and 120 m region margin have no adaptive completeness fallback. No API, primary database, streaming upload/export, container deployment, or multi-user job handling is implemented. These are delivery gaps distinct from the waived language requirement. Optional depth is not a mandatory gap.

## Execution evidence and limitations

The following fresh runs completed without modifying code or baseline outputs:

```bash
python3 run_sample.py 'docs/Датасет скорректированный.geojson' \
  --out /tmp/heat-routing-audit-current --no-frames --engine python
python3 run_sample.py 'docs/Датасет скорректированный.geojson' \
  --out /tmp/heat-routing-audit-cpp --no-frames --engine cpp
```

Python took 12.6 s; the installed C++ extension run took 6.4 s, including figures. GeoJSON outputs were byte-identical. Their three reported scores are **13.748390, 13.880845, 14.079161**. Best reported cost is **289,024,922.61**, with **1,885.23 m** of new pipe, all 17 demands connected, and zero internally reported violations. These are results under the old evaluator, **not valid scores for compliant current-spec solutions**. Timing is one run per engine, not a performance benchmark; the extension was not rebuilt.

Additional checks covered dataset differences/validity, exported references and endpoints, actual-DN obstacle clearance, building-entry distance, invalid-input acceptance, two diameter counterexamples, and missing-demand accounting. This review is not a complete independent validator. The expensive partition/LP/SCIP workflows were inspected but not rerun against the obsolete model.

## Improvement sequence

### First: establish a trustworthy mandatory baseline

1. Reconcile `specs/route_rules.md`, the general-spec conflicts, changelog, and open questions against the new documents. Keep older results explicitly historical. Version the rules used for each run.
2. Implement an independent exported-GeoJSON validator, sharing reference tables but not evaluator logic. Rebuild topology and recompute flows, costs, lengths, degree, DN runs, clearances, entries, crossings, and missing-demand penalties.
3. Correct the evaluator and export together: reconstruction removal, chamber accounting, DN chains, no bend surcharge, no economic drops, valid references, and complete summaries. Mirror rule changes in C++ and rebuild it.
4. Introduce explicit private building-entry segments through nearest boundary points, actual-DN geometry checks, and legal special crossings. Reject broad clearance exemptions.
5. Require a legal single-option baseline on the corrected sample and small adversarial fixtures before optimizing score. Fewer valid variants are preferable to manufacturing three alternatives by excluding needed tie-ins.

Useful acceptance fixtures include the two DN examples above; a chamber with four existing rays and a nearby valid new tie-in; two new rays into one existing chamber; two demands in one building; a concave building; overlapping road/gas crossings; a prohibited railway; a non-tie-in existing-network crossing; mixed IDs; malformed geometry; and a truly unreachable demand whose ID and exact penalty remain present.

### Then: improve route and grouping quality

**Geometry before extra search iterations.** Use a sparse, adaptively expanded visibility graph with crossing portals and explicit building entries. Evaluate edges with actual DN-dependent clearance, either through cached DN-specific feasibility or conservative proposal generation followed by exact checking and rerouting. A worst-case global buffer alone can erase valid narrow routes. Enforce turns ≤90° through incoming-edge state or a checked repair step; do not force 45°/90° turns now that arbitrary angles are allowed. Simplify only when exact geometry and section semantics remain valid.

**Optimize real incremental score.** Keep `C + λL`, where `λ ≈ 107,142.86` rubles/m, as the equivalent objective. A special segment costs `c_new(DN) × K_special + λ` per meter in this objective: the length term is not multiplied by the crossing coefficient. Remove the arbitrary in-building weight once entry geometry is fixed. Rank tie-in candidates using actual path and chamber costs, not summed distance alone. Adaptively refine promising positions along existing sections instead of relying entirely on the 25 m sampling grid.

**Reuse set partitioning after simplifying it.** Removing reconstruction eliminates a major coupling between groups. Price each legal group tree independently; let the master cover demands once, enforce aggregate chamber incidence and shared new-chamber costs, and exclude geometric conflicts. Include multiple route topologies for a group so one shortest tree does not permanently determine its geometry. Grow the column family from heuristic plans and promising neighborhoods; use local destroy/repair moves to change several groups jointly. New-chamber sharing and the conditional 10 m rule still require coordination, so the problem is not completely separable.

**Make diameter state explicit in improved tree pricing.** The existing subset DP uses flow-based diameters and relaxes the run rule; it is not an exact solver for the current full problem. A fixed-tree sizing algorithm should work on constant-flow chains with states tracking upstream DN and continuing same-DN length, retaining only valid minimal assignments. Tree search can use a cheaper relaxation, but every reconstructed tree must pass the exact sizing and geometry checks. Relocate branch chambers within free space using weighted pipe costs and revalidation; do not assume an unweighted Fermat point is optimal.

**Treat optimality certificates carefully.** Historical bounds and gaps apply to older data/rules and a restricted graph. Rebuild the LP/MILP objectives and legal graph before reporting any current certificate. A restricted column-family optimum is not a global lower bound. Also, `Master.solve()` currently assigns the primal `res.fun` to `lower_bound_family` when `use_lb=True`; at a time limit that is an incumbent, not a certified lower bound. Use the solver's dual bound, distinguish termination status, and label the scope of every bound. Correctness and valid incumbents should precede more work on cut separation.

### Finally: scale and deliver

Stream ingestion into an indexed store, retrieve local obstacle neighborhoods, build graph edges lazily, and bound per-run caches. Partition large demand sets with overlapping neighborhoods and reconcile boundaries; test quality loss as well as speed. Add the offline API/job queue, database, reproducible dependencies, and Compose deployment. Make figure generation optional for production runs. Retain Python plus focused C++ kernels unless profiling demonstrates a new bottleneck; a language rewrite would not correct these semantic defects.

If depth is pursued later, keep it a separate mode/result file. Use continuous piecewise-linear depth profiles with the 0.10 gradient constraint, the correct utility envelopes, and splitting at 3 m for cost integration. No obligatory 0.5 m grid, Z coordinates, or artificial maximum depth is specified.
