# Design documents

Start with [Current solver and verification](08-current-solver.md) for commands,
implemented rules, validation, and remaining limitations. Documents 1 and 3–6 below
are historical design material; conflicting rules, commands and results are superseded.

1. [Spec analysis](01-spec-analysis.md): the model behind the two specs, the economics of the score, and the structural insights the design rests on.
2. [Open questions](02-open-questions.md): what must be answered by the organisers, the dataset, or the team, each with the working assumption we build with until answered.
3. [Implementation plan](03-implementation-plan.md): architecture, algorithms, service, stack, phases, tests, risks, and the optional depth mode.
4. [Pre-MVP solver](04-pre-mvp-solver.md): the Python prototype (`heatrouting/`, `run_sample.py`), how to run it, its result on the sample, and its known limitations.
5. [C++ rewrite assessment](05-cpp-rewrite-assessment.md): what a compiled core would change per component, three options, and the decision (optimise Python first, then two C++ kernels behind the same interfaces).
6. [Bound tightening](06-bound-tightening.md): where the 31.5% gap comes from and two routes to close it, branch-and-bound with a cut formulation and DN binaries, or set partitioning with exact per-group trees.
7. [Current specification audit](07-current-spec-audit.md): the newly supplied Word documents and corrected dataset, reproduced compliance failures, and the recommended implementation and optimization sequence.

8. [Current solver and verification](08-current-solver.md): implementation following the audit, current commands, numerical conventions and remaining work.

Document 7 records the pre-migration audit. The current technical appendix and
clarifications are transcribed in `specs/route_rules.md` and `specs/clarifications.md`.
Previous specifications are preserved in `specs/archive/2026-09-22/`.

Source specs: [`../specs/spec.md`](../specs/spec.md), [`../specs/route_rules.md`](../specs/route_rules.md).

Status: current 2D command-line solver, updated 2026-09-27. Use
`Датасет скорректированный.geojson` for the current sample. The programming-language
requirement is waived; retained production service requirements remain open.
The root contributor guide predates this migration and was intentionally preserved;
use document 8 for current test and optimization commands.

9. [Team repository integration](09-team-integration.md): Java API, HTTP solver,
   Compose wiring, frontend results and integration verification.
