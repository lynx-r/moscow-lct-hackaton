# Specification changelog

## 2026-09-27: current written appendix and clarifications

The original Russian Word documents are transcribed in `route_rules.md` and
`clarifications.md`. They supersede conflicting September 22 amendments.

- Remove existing-network capacity and reconstruction calculations.
- Check continuous same-DN length per path, not summed branches; use uniform DN
  between flow changes and the smallest sufficient table diameter, without a one-step cap.
- Remove bend surcharges; enforce turns at most 90 degrees.
- Disallow economic demand dropping; retain penalties for search failure.
- Require nearest-boundary building entries and actual pipe-envelope clearances.
- Prohibit railway crossings; check polygon crossing angles against entry boundaries.
- New chambers include connection costs; existing chambers cost per new incident ray.
- Remove tie-in/reconstruction objects; correct summary fields and endpoint references.
- Confirm cost weight 0.7, length weight 0.3; extra output properties are allowed.
- Optional depth uses separate outputs, no required Z or fixed step.

Previous specifications and changelog: `archive/2026-09-22/`.

Implementation status and verification commands are recorded in
[`docs/08-current-solver.md`](../docs/08-current-solver.md). The independent validator
and current 2D solver do not imply completion of the retained service requirements.
