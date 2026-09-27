# 1. Spec analysis

Sources: `specs/spec.md` (general technical specification), `specs/route_rules.md` (technical appendix), and since 2026-09-16 the sample dataset `data/data.geojson`. This note distils them into the model we will build against and records the insights that drive the design. Open questions are tracked in [02-open-questions.md](02-open-questions.md); the plan is in [03-implementation-plan.md](03-implementation-plan.md).

## 1.1 The problem in one paragraph

We receive one GeoJSON file describing an existing district-heating tree (sections and chambers, each pointing at the next object toward a single source), a set of prospective buildings (OKS) each with a connection point and a fixed design flow, and typed spatial restrictions. We must return up to three ranked options. Each option is a forest of new pipe trees. Every tree ties into the existing network at exactly one point and reaches each of its OKS by a unique path. Branches happen only in chambers. Every section carries the summed flow of the OKS below it, gets the smallest diameter that fits, and obeys a maximum run length per diameter. The added flow propagates up the existing tree and forces reconstruction where capacity is exceeded. Everything is costed, scored, and exported in a fixed schema. Nothing may be hand-edited, and the same code must work on a second dataset.

## 1.2 Domain model

| Concept | Appendix schema | Sample schema | For us |
|---|---|---|---|
| Source | `source` point | same, plus `name` | root of the whole tree |
| Existing section | `heat_network` LineString with `diameter`, `flow_tph`, `upstream_object_id` | `diameter` only | directed edge of the existing arborescence, with capacity headroom |
| Existing chamber | `heat_chamber` point with `diameter`, `upstream_object_id` | `id` only | node with the degree-4 rule; tie-in candidate; may need reconstruction |
| Prospective OKS | `oks_future` polygon with `flow_tph`, `heat_load` | absent | a demand; its building is the polygon that contains the connection point |
| Connection point | `oks_connection_point` with `oks_id` | `flow_tph` on the point, no `oks_id` | leaf of a new tree (degree 1), the carrier of the demand |
| Existing OKS | `oks_existing` polygon | `restriction` with `restriction_type = oks`, MultiPolygon, `address` | impassable, DN-dependent clearance 5 / 7 / 9 m, except for its own connection point |
| Restriction | `restriction` with `restriction_type` from the table in appendix 5.1 | `oks`, `water`, `railway` (no rule for `railway`) | impassable with clearance, or crossable as a `special` section |

The existing network plus the new network is **one arborescence rooted at the source**. A tie-in is a node where a new subtree hangs off an existing edge or chamber. This is the single most useful modelling fact (see 1.6).

## 1.3 What the sample dataset shows

`data/data.geojson` holds one project: the ZIL area of Moscow, source "ТЭЦ ЗИЛ", 144 features, 233 KB, CRS84 with a `crs` member and a `name`. A rendered map is in [sample-map.svg](sample-map.svg). It departs from the appendix in ways that change the model.

| Appendix says | Sample has | Consequence |
|---|---|---|
| `id` is a string | integers 1 to 144, unique | ids are opaque scalars; output references echo the input value |
| Prospective OKS are `oks_future` polygons; connection points carry `oks_id` | no polygons at all; `flow_tph` sits on the 17 connection points; no `oks_id`, no `heat_load` | the demand is the connection point; its building is found by containment |
| Existing OKS are `oks_existing` polygons | 85 `restriction` features with `restriction_type = oks` | one obstacle registry keyed by type, with aliases |
| Sections carry `diameter`, `flow_tph`, `upstream_object_id` | `diameter` only | topology derived from geometry; existing flow unknown |
| Chambers carry `diameter`, `upstream_object_id` | `id` only | chamber DN derived as the maximum adjoining section DN |
| Restriction types are those of appendix 5.1 | `oks`, `water`, `railway`; all MultiPolygon | unknown types need a default rule and a loud diagnostic |
| Existing DN does not decrease toward the source | DN500, DN300, DN400, DN500 along the chain | the algorithm never needed the assumption, but the DN300 stretch is a real bottleneck |

Layout facts that matter for the optimiser:

- **The existing network is one chain.** 29 sections, 1 460 m, from the source at the north end to a DN500 tail that crosses the railway at the south end. Endpoints coincide to the millimetre except one 7 cm gap between sections 116 and 120. The 9 chambers sit exactly on section endpoints. Thirteen sections are 2 to 10 m long, probably fittings or crossings. No branch exists, so no chamber is near its degree limit.
- **The DN300 stretch is the bottleneck.** Chainage 320 to 721 m, 401 m, capacity 437 t/h, between the DN500 head and the DN400 body. Ten connection points are nearest to it (289 t/h) and seven to the DN400 body beyond it (200 t/h). Under the rules all 489 t/h of new flow passes through the DN300 stretch unless some of it ties in on the 320 m DN500 head. That exceeds the stretch's capacity even with zero existing flow, so the option set is decided by where flow is injected and by the existing-flow policy (Q3, Q26).
- **Every connection point lies inside a building.** 0.1 to 10.4 m from the outline; two (12 and 15) share one building. The building to be connected is therefore in the obstacle set itself, and the route must enter it.
- **The demands cluster.** Connection points are 23 to 480 m from the network (median 264 m) and 29 to 164 m from their nearest neighbour, in three visible groups west of the chain. Straight lines to the network cross 1 to 4 buildings, so real routes will be 1.2 to 1.5 times longer, and the maximum-length rule will force DN upgrades on the far ones: a 16 t/h demand needs DN100, whose run limit is 419 m.
- **Water and railway are out of the way** for this project. The river and a pond lie west of every demand and the railway corridor south-east; the network itself already crosses the railway. Neither needs special handling here, but the rule for `railway` must exist for the next project.
- **Scale is comfortable.** The region of interest is 0.9 km² with 51 buildings and about 4 900 outline vertices, well within reach of a visibility graph.

The consequence is one more principle, added in 1.6: **the model is canonical and derived.** Ingest normalises whichever schema arrives into one internal model, and every derived quantity (topology, chamber DN, host building, existing flow) is tagged as derived so a run report can separate what was measured from what was assumed.

## 1.4 Rules as constraints

| Rule | Kind | Source |
|---|---|---|
| One tie-in per new component; unique path to each OKS (a tree) | hard, structural | spec 2.2 |
| Branches only in chambers; chamber degree ≤ 4 (1 up, ≤ 3 down); existing sections count | hard, structural | spec 2.3, app. 3 |
| Tie-in ≤ 10 m from an existing chamber with spare degree uses that chamber; otherwise a new chamber at the tie-in | hard, deterministic | app. 8.2 |
| Technical node wherever DN, laying method, or depth changes, unless the point is a chamber or tie-in | hard, derived | app. 3, 8.1 |
| Straight segments, rational geometry, no zigzags, stair-steps, or unjustified bends | soft, judged by experts | spec 2.3, spec 8 |
| New sections never cross each other outside a shared node | hard, geometric | spec 2.3 |
| DN is the smallest with capacity ≥ flow; flow is the sum of OKS flows below | hard, formula | app. 3, 4.1 |
| Maximum length per continuous same-DN run; the count restarts only on a DN change | hard, may force DN up | spec 2.4, app. 4.1 |
| Prohibited restrictions: bypass with clearance measured envelope to envelope | hard, geometric | app. 5 |
| Crossable restrictions: separate `special` section with typed boundaries, `K_special`, angle ≥ 45° for road and tram | hard, geometric and cost | app. 5.1 |
| Existing sections: DN_req from G_existing + G_new; reconstruct the portion from the tie-in upstream when DN_req > DN | hard, formula | app. 7 |
| Existing tie-in chamber: reconstruct once if the max adjoining DN (post-reconstruction) exceeds the chamber DN | hard, formula | app. 8.2 |
| Costs: sections, chambers tiered by max adjoining DN, tie-ins at 5 M each, reconstruction, penalty | formula | app. 8 |
| Score S = 0.7·C / 25 M + 0.3·L / 100, with L = new plus reconstructed length | formula, ranking | app. 9 |
| Output: per-type mandatory attributes only, one summary per option, OKS points not re-exported | hard, format | app. 10 |
| Nothing dataset-specific; unchanged algorithm on a second dataset | hard, process | spec 2.14 |

## 1.5 Economics: what the score really rewards

S is linear, so ranking by S is the same as minimising an effective cost:

```text
C_eff = C + λ · L,   λ = (0.3 / 100) / (0.7 / 25 000 000) ≈ 107 143 UNI per metre of new or reconstructed pipe
```

That one number sets every trade-off. Equivalents in metres of DN100 new pipe (effective price 89 748 + 107 143 ≈ 197 k UNI/m):

| Item | UNI | ≈ m of DN100 |
|---|---|---|
| One tie-in | 5 000 000 | 25 |
| One chamber, DN ≤ 200 | 3 000 000 | 15 |
| One separate component (tie-in plus new chamber) | 8 000 000 | 41 |
| Joining two OKS: one component saved, one branch chamber added | −5 000 000 | −25 |
| Reconstructing 100 m from DN150 to DN200 (181 766 + λ per m) | 28 900 000 | 147 |
| Reconstructing the sample's 401 m DN300 stretch to DN400 (271 317 + λ per m) | 151 800 000 | 771 |
| Penalty for an unconnected OKS with 10 t/h | 105 000 000 | 533 |

Consequences for the design:

1. **Length matters almost as much as money.** The λ term roughly doubles every per-metre price relative to the fixed charges. Shorter routes and less reconstruction beat cheaper pipe.
2. **Joint connection pays only for near neighbours.** Merging two trees saves about 25 m of pipe-equivalent but costs a branch chamber, a larger trunk, and a detour to a common point. Whether it pays is a dataset fact, so the algorithm must evaluate merges exactly and never assume them. In the sample, neighbours 29 to 76 m apart and 250 m from the network are exactly the case where it does.
3. **Tie-in choice is dominated by upstream headroom.** Reconstructing the sample's DN300 stretch costs as much as 770 m of new DN100 pipe, so the optimiser will carry demand north to the DN500 head or split it around the bottleneck before it accepts that bill. The capacity headroom of every existing section is a first-class quantity.
4. **The penalty is finite and can be competitive.** A 10 t/h OKS about 500 m from the network scores roughly the same whether connected or left unconnected. We treat connection as mandatory whenever a legal route exists, because the spec calls an unrouted OKS a failure, but this is an explicit decision and a question for the organisers (Q14).
5. **Big trunks are cheap to use but expensive to open.** A new chamber on a DN500 trunk costs 5 M rather than 3 M because the existing pipe adjoins it. Tie-in chamber cost must use post-reconstruction DNs of every adjoining section.

## 1.6 Structural insights that shape the design

1. **One tree, one pass.** The combined existing and new network is a single arborescence. A bottom-up pass computes added flow per edge, total flow, required DN, reconstruction portions, chamber DNs, chamber reconstruction, and all costs. "Existing" versus "new" is an edge attribute, not a second algorithm.
2. **Legal geometry lives on a finite graph.** Inflate every prohibited object by its clearance plus half the pipe-pair width, take the visibility graph of the free space, and add portals (straight, angle-compliant crossing segments with special cost) through crossable restrictions. A building's own connection point is reached through an entry portal on its outline, the same mechanism as a road crossing. Shortest paths on that graph are taut, bend only at obstacle corners, and carry their special sections and technical nodes with them. Rational geometry becomes a property of the substrate rather than a clean-up step.
3. **Diameter assignment is a small tree DP.** Given flows, the max-length rule couples DN to distance from the leaf. With DN non-increasing away from the tie-in (engineering-sane, and it removes the incentive to game the "new count on DN change" wording), a lazy-upgrade pass from the leaves is cost-optimal and places the technical nodes at exact chainages.
4. **Evaluation is cheap; search is the hard part.** With paths cached and the tree pass linear, a whole option evaluates in milliseconds. The optimiser is therefore an exact-evaluated search over connection plans (partition of OKS into groups, a tie-in per group, a Steiner topology per group). The "meaningfully different options" fall out as distinct local optima or constrained re-optimisations, not as hand-picked alternatives.
5. **Rules are data.** Tables, formulas, clearances, and crossing rules live in one declarative module consumed by both the optimiser and an independent validator that re-checks every exported option. That makes generality on a second dataset testable and compliance demonstrable at the pitch.
6. **The model is canonical and derived.** Two schemas already exist for the same data. Ingest maps either onto one internal model through four explicit derivations (topology from geometry, chamber DN from adjoining sections, host building from containment, existing flow from a stated policy), and marks each derived value so reports and the jury can see what was measured and what was assumed.

## 1.7 What is genuinely hard

- **Ambiguities with cost impact.** The max-length semantics on branched trees, the reference for the 45° angle, the existing-flow policy when sections carry no flow, and whether a tie-in chamber's tier counts the trunk. Each is listed in part 2 with the assumption we build with.
- **Visibility-graph scale in a dense city.** The sample's 4 900 outline vertices are easy; a city-scale file may not be. Mitigated by region-of-interest clipping, reflex-vertex reduction, radius-limited candidate edges, and vectorised intersection tests. A lazily explored any-angle search sits behind the same oracle interface as a fallback.
- **Plane consistency of a forest.** Pairwise shortest paths can cross each other or another tree. This becomes a repair step inside the search loop (attach at the crossing, merge, or reroute with a mask), not a post-hoc fix.
- **Data quality and schema drift.** The sample already shows a 7 cm gap, missing upstream pointers, missing flows, a restriction type without a rule, and demands inside obstacles. A tolerant topology builder and a schema-mapping ingest emit diagnostics instead of failing.

## 1.8 Scope decisions

- The mandatory 2D task first and completely. The depth-aware mode is a separate optional phase designed to reuse the 2D machinery.
- The objective is S with the spec's weights as run parameters. Turn-count and similar tie-breakers never change S.
- Java requirement relaxed: the core is Python on GEOS, the service is a thin FastAPI shell, PostGIS is the primary store. Other platform requirements are kept; see the plan, section 3.3.
- A minimal browser map viewer is in scope because the demonstration criteria (spec 4) are visual. It is not a product UI.
- The sample dataset is the first fixture: every phase exit is demonstrated on it, and the final dataset is assumed to differ from it in schema details.
