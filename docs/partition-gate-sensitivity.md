# The partition gate was vacuous on every profile this repo has — (INC.18)

## The number

`scripts/partition-equivalence.sh` asks, for every file of a project: does
`recheckOnly = {file}` report for that file exactly what the full build reports for
it? It is the gate (INC.7)'s 68 walker gatings were graded by, (INC.9)'s deferral
was graded by, and the one (INC.17)'s re-entrant-checker replay would be graded by.

It is a DIFFERENTIAL, so it can only see a defect that makes the two arms DISAGREE,
and its resolution is bounded by how many of the checker's ~416 `init` passes get to
contribute a row to the comparison. Measured:

| project | files | diagnostics | files carrying a row | **distinct passes netting one** |
|---|---:|---:|---:|---:|
| `build/bench/tsc-project-*` (the arm that has always run) | 78 | 46 | **5** | **1** — `checkSpine` |
| `test-fixtures/partition-gate` (this round) | 71 | 175 | **70** | **78** |

So on the realism arm **73 of 78 per-file comparisons are empty against empty**, and
every row that does exist is netted by ONE pass. All eight dashboard profiles are
that same codebase, so this is not one unlucky project — it is every instrument this
repo had for partition work. What carried (INC.7) was the 13k-baseline CORPUS, which
has no partition at all.

**Consequence for work already landed:** (INC.7)'s 68 gated walkers and (INC.9)'s
deferral were profile-GREEN for a reason that says nothing about them. Only the
corpus stood behind those, and the corpus cannot see a partition. They are not
thereby wrong — they are unmeasured on this axis, and re-runnable now.

## Why the count is `diagNetByPass` and not `diagsByPass`

`PassTiming.diagsByPass` records `if (d1 > d0)`, so a pass whose net effect is a
RETRACTION is absent from it entirely. `Checker.kt` carries 73 `removeAll`, 5
`removeAt` and 2 `clear` sites, and CLAUDE.md already records that a pass which
retracts before it emits is invisible to a count-based ablation (round 749). The
signed twin `PassTiming.diagNetByPass` — landed by (INC.17) — is what both the gate
and `PassDiagNetSignTest` read.

The receipt prints `netTotal` beside `diagnostics` as its own positive control. On
the fixture they are 167 and 175: the eight-row gap is SYNTAX errors, which the
Parser emits and which therefore pass through no `pass(...)` wrapper at all. On the
tsc profile they are 46 and 46.

## How the fixture was built

`PassDiagMineMain` compiled every single-file conformance case **under the fixture's
own options** — the case's own `// @directive` header dropped, because the fixture is
one tsconfig and every file in it compiles under the same options — and recorded which
passes netted a diagnostic:

```
6,451 case files walked · 1,561 skipped (multi-file / oversized) · 0 failed
2,802 cases net at least one row · 241 DISTINCT passes across them
```

`scripts/partition_fixture_compose.py` greedy-covers that record. Its curve is the
finding underneath the finding: 20 files reach 44 passes, and beyond ~24 files each
additional case adds **exactly one** new pass. **The tail walkers are one-shape
walkers** — nearly every one is owned by a single conformance case, which is why a
real codebase reaches ONE.

The fixture files were then **written from scratch** against that map rather than
copied from it. This repo does not vendor TypeScript source (`typescript-repo/` is
gitignored and even the real lib sources are generated into `build/`), and a fixture
generated at gate time from a clone is a fixture that drifts.

## The proof that it can fail

A gate nobody has shown can fail has not been re-armed, only re-run.
`scripts/partition-gate-ablate.sh` injects, one at a time, the mistakes (INC.17)
refuses to land on, and reports each arm's split across BOTH gate arms.

See the session note in `PLAN-PHASE-5.md` for the measured table.

* **a1 / a2** — a partition-DEPENDENT tail walker (`checkMissingImplementations`,
  `checkConflictMarkers`) made to produce nothing once `assignedFileNames != null`.
  This IS (INC.17)'s fear: a replay that skipped a pass it had misclassified.
* **a3** — round 609's shape: a program-wide COLLECTOR
  (`buildFileLocalTypeMaps`) gated on the partition instead of on `binderResults`.
* **a4** — the CONTROL: a pass that nets a row on NEITHER project
  (`checkCloduleTest2`). Both arms must stay GREEN, or redness elsewhere is not
  attributable to the pass that was ablated.
* **a5** — the other control: `checkSpine`, the one pass that nets every row tsc's
  own sources report. BOTH arms must redden, which is what shows the realism arm is
  not permanently green.

## Running it

```
scripts/partition-gate.sh              # both arms, with the receipt printed for each
scripts/partition-gate.sh sensitivity  # the fixture only
scripts/partition-gate-ablate.sh       # the proof
```

The sensitivity arm REFUSES below its floors (40 netting passes, 40 files carrying a
row) rather than printing green. A gate that has stopped being able to fail must say
so — rounds 853/873/895 are three occasions on which one did not.
