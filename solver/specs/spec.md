# District Heating Routing Service — Current Requirements

Updated 2026-09-27. The [current technical appendix](route_rules.md) and
[written clarifications](clarifications.md) govern calculation and output.
The September 22 documents in [archive/2026-09-22](archive/2026-09-22) are historical.
The programming-language requirement is waived for this repository.

## Mandatory calculation

Accept one GeoJSON FeatureCollection in WGS84; calculate geometry in EPSG:32637.
Process every connection point using its supplied flow, including multiple points
in one building. Preserve opaque string/number IDs. Reject invalid input geometry.
Automatically choose attachment locations and joint or separate connections.
Each new component is a tree with one attachment and one path per connected demand.
Branches require chambers, with at most four incident sections including existing
sections. Follow the conditional 10 m chamber rule.

Size maximal constant-flow chains using the smallest DN satisfying capacity,
continuous per-path run length, and nondecreasing diameter toward the attachment.
Parallel branch lengths are not summed. Do not split a constant-flow chain merely
to restart its length allowance. Chambers and technical nodes do not reset it.

Apply actual pipe-pair envelopes, nearest-boundary building entries, prohibited
restrictions, and straight special crossings with the specified angles, extensions,
and maximum overlapping coefficient. Arbitrary turns up to 90 degrees are allowed
without surcharge. Avoid unnecessary bends, overlaps, illegal crossings and detours.

Existing-network capacity, flow reserve, and reconstruction are excluded. Charge
new chambers once, including connection; charge 5,000,000 rubles per new linear
section ending at an existing chamber. Do not emit reconstruction or tie-in objects.
Unconnected demands are allowed only when no compliant route was found. Never drop
reachable demands to improve score. List every missing ID and charge its penalty.

## Result and ranking

Return up to three meaningfully different validated variants, with `variant_id`.
Use the appendix's required attributes for new `heat_network`, `heat_chamber`,
`technical_node`, and `variant_summary` objects. Extra properties are allowed.
References must resolve to permitted input/output nodes at the matching geometry
endpoints. Ordinary bends remain internal LineString vertices.

`construction_cost` includes pipes, chambers and existing-chamber tie-ins.
`calculated_cost` adds penalties. Rank by
`0.7 * calculated_cost / 25_000_000 + 0.3 * new_network_length / 100`.
Mandatory 2D output has null endpoint depths and depth coefficient one. Optional
depth has a separate output/ranking, minimum depth 0.7 m, ordinary depth 3 m and
maximum gradient 0.10, without mandatory Z, fixed depth steps or maximum depth.

## Retained service and delivery requirements

- Ubuntu Server 22 and a 16 GB memory envelope.
- PostgreSQL up to 18 (PostGIS allowed) or OpenSearch up to 2.18 as primary storage.
- Docker Compose deployment compatible with 1.29.2.
- Stream uploads up to 3 GB and exports up to 500 MB using temporary storage.
- Up to 50 users on a single offline server; no external API dependency. No fixed
  runtime limit, but speed contributes to evaluation.
- Deliver sources, reproducible build/deployment instructions, a working service,
  documentation, demonstration and exported results. OpenAPI/Swagger is desirable;
  a separate frontend is not required.
- Hidden datasets must work without hardcoded coordinates, counts, IDs, or manually
  corrected routes. Mandatory correctness precedes score.

These are requirements, not claims of completed service functionality. Java/Spring
and Maven/Gradle belong to the waived language-specific stack; unrelated delivery
requirements remain applicable.
