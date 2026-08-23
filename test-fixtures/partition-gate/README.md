# The partition gate's SENSITIVITY fixture

`scripts/partition-equivalence.sh` asks one question: for every file of a project,
does `recheckOnly = {file}` report for that file exactly what the full build
reports for it? It is a DIFFERENTIAL, so it can only see a defect that makes the
two arms disagree — and its resolution is bounded by how much of the checker the
comparison gets to compare.

Measured while censusing (INC.17), on tsc's own 78 sources — the project that gate
has always been pointed at, and the codebase all eight dashboard profiles are:

| | files | diagnostics | files carrying a row | **distinct passes netting one** |
|---|---:|---:|---:|---:|
| tsc's own sources | 78 | 46 | **5** | **1** (`checkSpine`) |
| this fixture | 71 | 175 | **70** | **78** |

So on the realism arm 73 of 78 per-file comparisons are empty against empty, and
every diagnostic that does exist is netted by ONE pass. A defect that silently
starved 204 of the 205 partition-dependent passes — which is exactly what a
re-entrant checker's replay would do if a pass were misclassified — would be
invisible there. What carried (INC.7)'s 68 gated walkers was the 13k-baseline
CORPUS, which has no partition at all; there was no instrument in this repo that
exercised *many emitting passes* and *a partition* at once.

This fixture is the missing instrument. Each file is a MODULE (so nothing collides
across the program) carrying a shape a different dedicated walker owns. It is
deliberately NOT realistic — the realism arm stays, and neither replaces the other.

## How it was built, and why it is hand-written

`PassDiagMineMain` compiled every single-file conformance case under THIS project's
own options and recorded, from `PassTiming.diagNetByPass` (the SIGNED per-pass
delta — `diagsByPass` clamps to positive and so cannot see a retractor), which
passes each shape nets a diagnostic from: **241 distinct passes across 2,802
cases**. `scripts/partition_fixture_compose.py` greedy-covers that record.

The files here were then written from scratch against that map rather than copied
from it: this repo does not vendor TypeScript source — `typescript-repo/` is
gitignored and even the real lib sources are GENERATED into `build/`, never
committed — and a fixture generated at gate time from a clone is a fixture that
drifts.

## Running it

```
scripts/partition-gate.sh              # both arms
scripts/partition-gate.sh sensitivity  # this one only
scripts/partition-gate-ablate.sh       # the proof that it can FAIL
```

The sensitivity arm REFUSES below its floors (40 netting passes, 40 files carrying
a row) instead of printing green: a gate that has stopped being able to fail must
say so.

## Editing it

Diagnostics here are pinned by nothing — the fixture's VALUE is the count of
distinct passes that net one, not the rows themselves, so a walker that changes
its message or its span costs nothing. What must not happen is the count falling:
add a file rather than repurposing one, and re-run the sensitivity arm.
