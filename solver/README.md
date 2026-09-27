# Python/C++ heat-network solver

This directory is the maintained solver workspace inside the team repository.
The Java backend calls it over an internal HTTP connection; the calculation remains
Python with optional C++ kernels. GeoJSON entering and leaving the solver is WGS84.
The solver owns projection, geometric checks, scoring and independent validation.

## Local development

Use Python 3.12 or 3.13 and a C++17 compiler; verification used Python 3.13.
From this directory:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python -m heatrouting.core.build
python -m unittest discover -s tests -v
python run_sample.py 'docs/Датасет скорректированный.geojson' --engine cpp --no-frames --out out/current
python run_validate.py 'docs/Датасет скорректированный.geojson' out/current/result.geojson
python -m heatrouting.service
```

The service listens on `127.0.0.1:8090`. `GET /health` checks readiness;
`POST /solve` accepts a GeoJSON FeatureCollection and returns the validated output
FeatureCollection. It starts a separate process and temporary directory for every
calculation, removes temporary files afterward, and kills timed-out calculations.
Only independently validated results are returned.

Use `SOLVER_ENGINE=python` for the reference implementation, or `cpp` (the default)
after compiling. `auto` explicitly permits fallback. Compiled binaries are ignored
by Git and built for the current Python ABI without CPU-specific compiler flags.

## Integrated Java API

From the repository root, build and run the isolated integration stack:

```bash
docker-compose -p heat-routing-integration -f docker/docker-compose-solver.yml up -d --build
python3 solver/tools/check_integration.py 'solver/docs/Датасет скорректированный.geojson' --out solver/out/integration_api
python3 solver/tools/check_integration.py 'solver/docs/Датасет скорректированный.geojson' --multipart --out solver/out/integration_upload
docker-compose -p heat-routing-integration -f docker/docker-compose-solver.yml down
```

The integration stack serves the UI at `http://localhost:8088` and binds the backend
to `127.0.0.1:8080`; the solver is reachable only inside its Docker network.
The local frontend build explicitly targets localhost, overriding the repository’s
production API URL. Database autoconfiguration is disabled in this stack
because the routing path does not use persistence. The existing local full-stack
Compose file also includes the solver, retaining its database and frontend services.
No production deployment or registry upload is performed by these commands.

For native development, run Java **11** in `backend/` with `SOLVER_URL=http://localhost:8090`.
The existing Lombok version does not compile on the host Java 21. Run the frontend
with its pinned pnpm version; if an older Corepack cannot launch pnpm 12, use
`npm exec --yes --package=pnpm@12.4.2 -- pnpm install --frozen-lockfile`.

Both existing endpoints are connected: `POST /routing/process` takes multipart field
`file`; `POST /routing/process-json` takes raw GeoJSON. Their response shape remains
`{"featureCollection": ...}`. The backend forwards WGS84 without a second projection.
The frontend shows the selected variant over the original input and reads costs,
length and missing IDs from `variant_summary`. Its default input is the corrected sample.

## Configuration and errors

| Variable | Default | Meaning |
| --- | --- | --- |
| `ROUTING_BACKEND` | `solver` | Java implementation; `mock` explicitly enables identity output |
| `SOLVER_URL` | `http://localhost:8090` | Internal solver URL used by Java |
| `SOLVER_HTTP_TIMEOUT_SECONDS` | `900` | Java wait for solver response |
| `SOLVER_ENGINE` | `cpp` | `cpp`, `python`, or `auto` |
| `SOLVER_TIMEOUT_SECONDS` | `840` | Maximum child-process runtime |
| `SOLVER_WORKERS` | `1` | Concurrent calculations; excess requests receive 503 |
| `SOLVER_MAX_REQUEST_BYTES` | `67108864` | Internal request size limit |

The internal service returns 422 for invalid input, 413 for oversized input, 503 when
busy, 504 on calculation timeout, and 500 when no validated output is produced.
The Java adapter preserves the first four statuses and maps internal failures to 502.
There is no silent fallback to mock data. Java multipart limits are 64 MB/file and
65 MB/request. Keep the internal service private; user authentication remains the
responsibility of the surrounding application.

## Scope and further work

This integration materializes GeoJSON in memory and processes requests synchronously.
It does not yet provide the required multi-gigabyte streaming, durable job queue,
50-user scheduling, or depth mode. Sample search connects 14/17 demands; IDs 2, 5 and
10 are reported and penalized. Search failure is not proof of physical impossibility.
See [current solver notes](docs/08-current-solver.md) for the crossing-clearance
interpretation, bounded-search limitations and future optimization work.

Current rules and original documents are in `specs/` and `docs/`. Older bound programs
remain explicitly disabled because they model obsolete reconstruction rules.
Generated outputs, compiled extensions and virtual environments stay outside Git.

Verification evidence and migration details: [team integration](docs/09-team-integration.md).
