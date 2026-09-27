# 2. Current interpretation and remaining questions

Updated 2026-09-27. The current written appendix and clarifications supersede the
September 22 discussion. See `../specs/route_rules.md`, `../specs/clarifications.md`
and `07-current-spec-audit.md`. The previous questions are historical; their
answers must not select an obsolete calculation mode.

## Resolved

Existing flow and reconstruction are excluded. Runs are per path; DN stays constant
between flow changes. Economic dropping is prohibited. Railway is prohibited.
Building approaches use a nearest boundary point. Polygon crossing angle is measured
at the entry boundary. New chambers include connection; existing chambers charge per
new ray. IDs may be numbers or strings. Extra output properties are permitted.
The score weights are cost 0.7 and length 0.3. Depth is optional and separate.

## Implementation conventions to document

- Numerical geometry tolerance must only accommodate coordinate serialization,
  not authorize meaningful clearance or angle violations.
- Search failure is not proof of physical impossibility; record search limits and
  fallback attempts for every unconnected demand.
- A chamber on an existing line interior has two existing incidences. Existing
  network intersections without a chamber do not automatically create a connection.
- Where several equally nearest building-boundary points exist, a legal one may
  be selected. Other obstacles still apply to the complete final approach.
- Capacity and reconstruction assumptions from historical experiments must not
  appear in current scoring or optimality certificates.

## Delivery decisions

The mandatory priority is valid 2D output. Production streaming, service deployment,
and optional depth are separate milestones. Hardware-scale performance must be
measured on larger and varied datasets rather than inferred from the small sample.
