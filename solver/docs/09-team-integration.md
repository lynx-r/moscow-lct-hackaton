# 9. Team repository integration

Verified 2026-09-27 on branch `feature/integrate-cpp-python-solver`, based on team
`main` at `973f232`. Work continues in the team repository's `solver/` directory.

## Boundary and changes

The existing Java upload and JSON endpoints call an internal solver HTTP service.
Input and output stay in WGS84; Python performs projection and independent output
validation. Java preserves 16 decimal places when serializing geometry: the original
JTS default of eight changed input routes and invalidated exported costs.

Each calculation uses an isolated subprocess and temporary files, with timeout and
concurrency limits. Invalid input, busy state and timeout have explicit HTTP errors.
The frontend displays real summaries and one selected variant over source objects.
Local builds target localhost instead of the production API address.

Current source, specifications, original documents, datasets and regression tests
were imported. Compiled extensions and generated results are ignored. Algorithm
modules are unchanged; the C++ build omits CPU-specific flags for container portability.
The local and production Compose definitions include the solver, and the existing
main-only CI workflow builds its image. No remote push or deployment was performed.

## Verification

- **28 Python tests passed**, including the original 22 solver regressions and six
  HTTP service tests. The solver image rebuilds the kernel and runs these tests.
- **8 Java tests passed under Java 11**, including coordinate precision, WGS84
  forwarding, null-geometry summaries, error mapping and stream cleanup.
- Both standalone engines produced byte-identical corrected-sample outputs, also
  byte-identical to the original workspace's baseline.
- The containerized Java JSON endpoint processed the full corrected dataset. All
  three variants passed independent validation; parsed GeoJSON exactly matched the
  standalone result after the Java serialization round trip.
- The multipart endpoint passed a separate miniature routing/validation test.
- A concurrent API request returned 503; an invalid demand flow returned 422.
- Frontend type-check and production build passed. All three container images built;
  the UI and JavaScript bundles were served over HTTP, with localhost API targeting
  verified. Interactive browser behavior was not automated.

The best sample score remains **23.203034**, with **2,040.45 m** of new network and
**14/17 demands connected**. IDs 2, 5 and 10 retain explicit failure reasons and
penalties. The integration does not claim additional coverage or global optimality.

Commands are in [solver/README.md](../README.md). Generated local evidence is under
`solver/out/integration_python`, `integration_cpp`, `integration_api` and
`integration_upload`; it is not committed. The root API check script can regenerate it.

## Remaining scope

Requests are synchronous and materialized in memory, with a 64 MiB internal limit
and one calculation at a time by default. Multi-gigabyte streaming, persistent jobs,
production concurrency/load tests and optional depth remain future work. Geometry
interpretations and search limitations from [document 8](08-current-solver.md) still
apply. Use Java 11 for the existing backend dependency stack; host Java 21 fails
with its current Lombok version. Corepack versions that assume the old pnpm entry
point require the documented npm-exec fallback; the frontend image installs the
pinned pnpm version directly.
