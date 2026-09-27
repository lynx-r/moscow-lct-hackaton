# 8. Current solver and verification

Updated 2026-09-27 after the [specification audit](07-current-spec-audit.md).
This is a command-line 2D solver with an independent exported-result validator.
It is not yet the complete production service required by the specification.

## Run and verify

Run from the repository root. Python dependencies are `shapely>=2,<3`, `pyproj`,
`numpy`, `scipy`, `matplotlib`, and `pillow`. The optional kernel additionally needs
`pybind11` and a C++17 compiler. The migration was tested with Python 3.13.

```bash
python -m pip install 'shapely>=2,<3' pyproj numpy scipy matplotlib pillow pybind11
python -m heatrouting.core.build
python -m unittest discover -s tests -v
python run_sample.py 'docs/Датасет скорректированный.geojson' --engine python --no-frames --no-figures --out out/current_python
python run_sample.py 'docs/Датасет скорректированный.geojson' --engine cpp --no-frames --out out/current_cpp
python run_validate.py 'docs/Датасет скорректированный.geojson' out/current_cpp/result.geojson
python run_partition.py 'docs/Датасет скорректированный.geojson' --max-size 2 --per-subset 3 --restarts 3 --time-limit 30 --out out/current_partition
```

`run_sample.py` writes `result.geojson`, `validation.json`, `summary.md`, and optional
figures. Every published variant must pass independent validation. `run_validate.py`
returns a nonzero exit code on failure and can save its report with `--report`.
`run_partition.py` adds `partition.json`, including the scope of its bound.
Use `--no-dp` to run partitioning without the optional compiled subset DP.

## Implemented changes

- **Input and requirements:** retain original Word wording in the current Markdown
  rules; reject invalid geometry, duplicate IDs and invalid required properties.
- **DN sizing:** constant-flow chains have uniform minimum sufficient DN; continuous
  length follows each path through chambers, taking the longest parallel continuation.
  Diameter cannot decrease toward the existing network. There is no one-step upgrade cap.
- **Accounting:** existing-network capacity and reconstruction are excluded. Shared
  new chambers are charged once, including connection. Existing chambers charge each
  new incident ray. Degree and the conditional 10 m rule use the final plan.
- **Coverage:** remove deliberate economic dropping; prefer fuller feasible plans
  across restarts. Every input demand absent from output is reported and penalized.
- **Geometry:** check actual assigned-DN envelopes against original restrictions;
  generate private nearest-boundary building entries; enforce turns of at most 90°,
  railway prohibition, straight special passages, crossing angles and overlap costs.
- **Export:** emit only permitted new objects and summaries, resolve endpoints,
  retain ordinary bends inside polylines, and split at special-passage boundaries.
- **Search:** direction-aware visibility paths, entry approach waypoints, alternative
  tree attachment checks, unsnapped tie-in candidates, bounded visibility expansion,
  and shared-chamber-aware partitioning improve the candidate family.

The C++ path accelerates chain sizing and subset DP. Both engines use the same
reference plan evaluator and geometry checks; rebuilding is required after kernel
changes. The validator independently reconstructs topology, flow, DN, geometry,
cost and score from serialized GeoJSON rather than trusting optimizer diagnostics.

## Geometry conventions and limits

Horizontal spacing applies when passing alongside an object. For an allowed crossing
or attachment, the connected straight transverse approach through the spacing buffer
is exempt. Pricing still uses exactly the appendix's prescribed special-passage
extensions. This interprets “alongside” separately from the priced interval: for
example, a gas crossing priced at ±2 m also has a pipe envelope extending beyond
the axis. The exemption cannot hide unrelated nearby parallel legs. This convention
is an interpretation to confirm with an external checker, not an explicit extra
rule in the Word documents.

The final straight building approach extends from outside the host's clearance
buffer through its nearest boundary point to the demand. Other restrictions remain
active. Geometry checks allow millimetre-scale numerical tolerance, not engineering
clearance reductions.

On the corrected sample, the current search connects 14 of 17 demands. IDs 2, 5 and
10 fail generated nearest-boundary approaches because the straight ray re-enters
the concave host. Their reasons and penalties are exported. This is a search result,
not a proof that no alternative compliant construction exists.

Fresh verification produced three independently validated variants and byte-identical
Python/C++ GeoJSON. The best score is **23.203034**, with **2,040.45 m** of new network,
**271,065,494.47 rubles** of construction and **338,995,000 rubles** in missing-demand
penalties. Partitioning over 1,411 checked columns matched this score and its
finite-family dual bound; it did not establish global optimality. The 22 regression
tests cover sizing counterexamples, chamber accounting, crossing geometry, coverage,
invalid output, and complete miniature routing pipelines.

Reports: [`out/current_cpp/summary.md`](../out/current_cpp/summary.md),
[`validation.json`](../out/current_cpp/validation.json), and
[`partition.json`](../out/current_partition/partition.json).

The graph uses sampled attachments, a bounded region and conservative obstacle
buffers. It can miss routes through narrow corridors or outside the search region.
Subset DP is a candidate generator with relaxed rules; every accepted tree is checked
afterward. The master's dual bound applies only to the generated fixed-tree column
family, including its coverage and fixed missing-demand penalties. It is **not** a
global optimum or a global routing lower bound. The old `run_bounds.py`,
`run_cutbound.py` and `run_scipbound.py` fail explicitly because their reconstruction
formulations no longer describe the current specification.

## Next improvements

1. Enumerate equally nearest legal building entries and refine attachment candidates
   locally. Add demand-DN-specific corridor graphs and stronger fallback searches;
   evaluate coverage before score improvements.
2. Generate alternative trees with different approach directions and DN-dependent
   clearances. Use column generation to grow the partition family, keeping finite-family
   bounds distinct from any future valid global relaxation.
3. Expand adversarial datasets for concave buildings, crowded chambers, overlapping
   special passages and mixed ID types; compare with an external reference checker.
4. Profile larger datasets and enforce memory budgets before building streaming
   ingestion/export, persistent storage and the offline multi-user service.

Optional depth remains unimplemented. Production streaming, database, deployment,
concurrency and hardware-scale performance requirements also remain open; current
sample validation does not establish those capabilities.
