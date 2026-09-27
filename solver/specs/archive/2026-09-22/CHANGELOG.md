# Specification changelog

## 2026-09-22: organisers' Q&A incorporated

Source: minutes of the Q&A with the case organisers (agreements 2 to 10 and the two open items). Item 1 (runtime stack) was intentionally not incorporated.

| Item | Where | Substance |
|---|---|---|
| 2 | spec.md §3.2 | No UI; GeoJSON correctness assessed; Swagger desirable; PostGIS allowed; no frontend container |
| 3 | spec.md §3.2 | Single instance, scaling optional; no CPU or time limits, speed is a criterion; fully offline; repository with Maven and Gradle deploy scripts; experts deploy |
| 4 | spec.md §2.1 | Connection point given, OKS geometry irrelevant; city scale; some data removed; realistic input 2 to 3 GB; invalid geometry is a diagnostic error |
| 5 | route_rules.md §5 | Angle from the obstacle axis (team builds it); polygon clearance from the outer boundary, linear from envelope boundaries (table 4.3); roads and tram 3 m beyond boundary; SP "Heat networks"; overlapping zones use the maximum coefficient once |
| 6 | spec.md A.4, route_rules.md §6 | No 0.5 m step; 0.7 m is the minimum vertical clearance from the crossed obstacle; ordinary depth 3 m; surcharge per depth unit; nearby crossings may share a dip |
| 7 | spec.md §2.4, route_rules.md §3 | Exceeding the run limit allows one DN step up only; branch lengths summed per DN; dataset has no such cases; existing network not checked |
| 8 | spec.md §2.3, §2.5; route_rules.md §3, §8.2 | Chamber, node and tie-in are one kind of node; chamber mandatory at every branch; 10 m rule with cheapest of several chambers; several branches into one chamber are several tie-ins; reconstruct only the affected part; non-tie-in chambers never costed |
| 9 | spec.md §2.3, §2.8, §2.9; route_rules.md §8.1, §9 | Weights restated as length 0.7 / cost 0.3 (conflicts with the appendix formula; flagged, default unchanged); non-standard bends cost 1.5 times and turns are minimised; maximum coefficient on overlaps; rounding up allowed; invalid geometry diagnosed; leaving an OKS unconnected allowed via the penalty |
| 10 | spec.md §8 | Two evaluation stages: plan geometry first, Z and depth only for up to ten solutions; extra criteria, obstacle variety and universality earn credit |
| open | spec.md §5a, route_rules.md §10 | Grid cell size (3 m?) and extra output attributes at stage one: answers promised |
