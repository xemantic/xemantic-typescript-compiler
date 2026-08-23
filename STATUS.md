# Status

**THE RE-ENTRANT REPLAY IS 3.06x, IT LOSES A TYPE-PARAMETER CONSTRAINT ON 8 OF 75 FILES, AND
THE DIAGNOSTICS SWEEP NEVER NOTICED (2026-08-23, (INC.17) step 2 — BUILT, MEASURED, AND
REFUSED AS A DEFAULT PATH).** Answering a semantic query about a file the checker was never
asked about, by re-entering only the partition-DEPENDENT `init` passes instead of rebuilding,
measures **3.06x** on tsc's own 78 sources — `replay=12572 ms` against `freshBuilds=38498 ms`
over 75 questions — exactly the shape step 1's census predicted (211 partition-INVARIANT rows
carrying **350.89 ms of the 366.47 ms floor**; the 205 dependent ones **15.59 ms**, 204 of them
**0.69 ms** between them). **WHAT REFUSES IT IS THE SECOND SWEEP**, precisely as (INC.18)'s arm
a3 predicted: `scripts/replay-differential.sh` reads `compared: files=75 diagnosticRows=46
filesCarryingDiagnostics=5 typeSpans=373879 definitionSpans=352713` and then **`DIVERGED: 8 of
75 file(s)`** — with the **diagnostics half completely untouched**. The shape is a **lost
type-parameter constraint**: the replay renders `<T extends Node, U>` where a fresh build
renders `<T extends Node, U extends T>`. A wrong hover is worse than a slow one, and (INC.2)
set the precedent by refusing capture narrowing over 45 divergent SPANS; 8 divergent FILES is
far past it. **THE TRANSFERABLE OUTPUT, now a CLAUDE.md entry: a partition or replay change is
graded on the CAPTURE sweep, not only the diagnostics sweep** — a lost collector or a lost
type-parameter scope surfaces as a wrong TYPE, never as a missing error, i.e. it fails in the
reassuring direction. **WHAT LANDED** is the mechanism marked EXPERIMENTAL at every entry point
(`ProgramRecheck`, `RecheckHolder`, both `recheckHolder` parameters,
`Checker.recheckAdditionalFiles`), each carrying the divergence and a "do not serve hover from
this" instruction; the oracle (`scripts/replay-differential.sh` + `ReplayDifferentialMain`), so
(INC.19) starts from it rather than rebuilding it; and the `checkSubsequentVarTypes` SPLIT the
census demanded — one MIXED pass whose two halves have OPPOSITE partition behaviour and whose
SUM the census read as 14.90 ms, now 0.69, pinned on both sides by `PartitionCensusHookTest`.
**THE OPT-IN IS THE LOAD-BEARING CHECK AND IT IS PINNED, NOT ARGUED**: every parameter defaults
to null, `retainForRecheck = recheckHolder != null`, `recheckAdditionalFiles` `require`s it,
`Project` does not reference the type, and a new pin asserts arming a holder does not change
the build's own diagnostics (narrowed or whole-program) **with a control that the arming
actually happened** — two unarmed builds would agree and prove nothing. `ProjectRecheckTest`
pins what the replay ACTUALLY does, including its defect: the capture channel is asserted to
EXIST and deliberately **not** asserted equivalent, because a soundness pin there would be
false. **WHAT DID NOT WORK:** two attribution arms, both killed — the first died silently
(probable daemon starvation, BUILD.1), the second (`replayAllPasses`) ran **~100x over budget**
at 53 minutes of CPU over **7** targets without finishing, against ~50 s for the 205-pass
replay over **75**; that ratio is itself evidence of a pass that appends or re-emits per replay.
(INC.19)'s instrument is a BISECTION over the replay set, not that arm. Suite **15,725 / 0 / 3**
(+11), `cost_gate.py` PASS (largest **+1.02% `mapped.hits`**, the standing pre-existing drift;
next is +0.18%), `huge_methods.py --fail-over 0` green on core (763 classes) and `-project`
(50), `partition-gate.sh` **EQUIVALENT on BOTH arms** (78 and 76 files), `capture-equivalence`
**5 spans / 3 files, `narrowRendersMoreAny=0`**, `capture-channel` **286 rows / 49 files,
members=285 scopes=0 signatures=1**, `caret-vs-file` **EQUIVALENT, 904 spans**, and
`checker-reuse-differential` in BOTH orders — program order the known single `watchPublic.ts`
row, editor order **EQUIVALENT over 550,480 types** (101 queries, 25 revisits). `docs/language-service.md`, `Recheck.kt`.

**THE PARTITION GATE WAS VACUOUS ON EVERY PROFILE THIS REPO HAS, AND IT IS NOW ARMED AND
PROVEN ABLE TO FAIL (2026-08-23, (INC.18)).** `scripts/partition-equivalence.sh` — the
detector (INC.7)'s 68 gated walkers, (INC.9)'s deferral and (INC.17)'s replay would all be
graded by — is a DIFFERENTIAL, so its resolution is bounded by how many of the checker's 416
`init` passes contribute a row. On tsc's own 78 sources that bound is **ONE**: the full
build's 46 diagnostics are netted by `checkSpine` alone and only **5 of 78 files carry any
row**, so 73 comparisons are empty against empty — and all eight dashboard profiles are that
same codebase. `test-fixtures/partition-gate` is the sensitivity arm: **76 files, 72 carrying
rows, 182 diagnostics, 78 DISTINCT netting passes**, read off `PassTiming.diagNetByPass` (the
SIGNED accumulator — `diagsByPass` clamps to `d1 > d0`, so a retracting pass is absent from
it, and `Checker.kt` has 73 `removeAll` + 5 `removeAt` + 2 `clear` sites).
`scripts/partition-gate.sh` runs both arms and the sensitivity one REFUSES below its floors
rather than printing green. **THE PROOF IT CAN FAIL, five arms one mistake at a time:** a
partition-dependent walker made silent when narrowed reddens the sensitivity arm and NOT the
realism arm (a1 `checkMissingImplementations`, a2 `checkConflictMarkers`); a pass netting on
neither project reddens neither (a4, control); and **a5 — ablating the one pass that nets
every row tsc reports — reddens EXACTLY 5 of 78 files, which is the realism arm's entire
resolution, measured.** Arm a3 (round 609's collector starvation on
`init:buildFileLocalTypeMaps`) is an honest NEGATIVE recorded as a control: both arms green
even after the fixture gained cross-file structure, because that map's product feeds type
DISPLAY and not diagnostics ((INC.10): deferring it moved 2,722 capture spans and zero
diagnostics) — so a diagnostic-producing collector's starvation is still unpinned, and that is
the round's honest limit. **The finding underneath the finding:** mining all 6,451 conformance
cases for per-pass attribution found **241 distinct netting passes**, and past ~24 selected
files each case adds **exactly one** new pass — the tail walkers are ONE-SHAPE walkers, which
is why no real codebase can arm this gate and a hand-written fixture is the only instrument.
Two `commonTest` pins, each verified RED by its own arm and GREEN under the other's
(`PartitionSensitivityTest` a1/a5; `PassDiagNetSignTest` a6, the clamped accumulator).
**NO COMPILER CHANGE**, so `cost_gate.py` and both `huge_methods.py` censuses are CONTROLS this
round rather than gates — any movement would have meant the change escaped its module. Suite
**15,714 / 0 / 3** (+5 pins), `cost_gate.py` PASS (largest **+1.02% `mapped.hits`**, the standing
drift), `huge_methods.py --fail-over 0` green on core (755 classes) and `-project` (50),
`partition-gate.sh` **EQUIVALENT on BOTH arms** (78 and 76 files), `capture-equivalence` **5 spans
/ 3 files, `narrowRendersMoreAny=0`**, `capture-channel` **286 rows / 49 files, members=285
scopes=0 signatures=1**, `caret-vs-file` **EQUIVALENT, 904 spans**, and
`checker-reuse-differential` in BOTH orders — program order the known single `watchPublic.ts`
row, editor order EQUIVALENT over 550,480 types. `docs/partition-gate-sensitivity.md`.

**THE RE-ENTRANT CHECKER'S PRIZE IS 95.7% OF THE FLOOR AND ITS REPLAY COSTS 0.69 ms — AND
THE GATE THAT WOULD HAVE TO SEE IT IS VACUOUS (2026-08-23, (INC.17) step 1, the census).**
Of the checker's 416 `init` pass rows on tsc's own 78 sources: **211 are partition-INVARIANT
and carry 350.89 ms of the 366.47 ms floor**, 205 are partition-DEPENDENT and carry **15.59**.
And 204 of those 205 cost **0.69 ms between them** — **201 read the partition exactly once**,
being a single `for (result in checkedResults)` loop — while the 205th,
`checkSubsequentVarTypes`, is 14.90 ms with an EMPTY partition, i.e. a MIXED pass doing
program-wide work outside its loop. **The model is SMALLER than (INC.14) priced**: no
diagnostics prefix needs resetting, because a program-wide pass already emitted the newly
asked file's rows in the first build and `getDiagnostics()` merely filtered them out at the
end — a replay re-runs the 205 and re-filters. **What is REFUSED is landing it, and the
refusal is about the instrument.** On the tsc profile the full build's 46 diagnostics are
netted by exactly ONE pass (`checkSpine`; the new signed-delta census reads 46 against the
build's own 46, its positive control) and `partition-equivalence.sh` prints
`diagnostics=46 filesCarryingThem=5` — so 73 of 78 files compare empty to empty, all eight
profiles are that same codebase, and a replay that silently produced nothing from 204 of the
205 passes would be invisible. **The classification is also not yet the one soundness needs**:
it measures *reads the partition* where the replay needs *its OUTPUT depends on the
partition*, and the two part company at every spine-produces / program-wide-pass-consumes
pair. The instrument is a RUNTIME one on purpose — `checkedResults` is a getter recording
`PassTiming.currentPass`, so it cannot be wrong about who read it — with **416 rows and 205
read-sites identical in all six draws and all three partition shapes, `outside = 0` in every
one**. **Six ablations, six discriminating, and the seventh pin exists because one was not**:
removing the getter's hook left all seven original pins GREEN, because none asserted the
getter-routed population as opposed to `checkSpine`'s own explicit hook — the missing pin was
added and reddens it. Suite **15,709 / 0 / 3** (+8), `cost_gate.py` PASS (largest **+1.02%
`mapped.hits`**, the standing drift), `huge_methods.py --fail-over 0` green on core and
`-project`, and all four equivalence sweeps at their baselines (`partition-equivalence`
EQUIVALENT on 78 files; `capture-equivalence` 5 spans / 3 files, `narrowRendersMoreAny = 0`;
`capture-channel` 286 rows / 49 files; `caret-vs-file` EQUIVALENT, 904 spans). Successor
**(INC.18) DONE the same day** — the fixture is `test-fixtures/partition-gate` at
**78 netting passes**, and the gate is proven able to fail; the replay's two remaining
obligations are recorded on the queue item.

**AN EDITOR'S WHOLE WORKING SET IS NOW ONE `Checker`: 18 SEMANTIC QUERIES IN SIX BUFFERS
WENT 5,230 ms -> 737, AND SIX PER-BUFFER ERROR QUERIES 2,338 -> 526 WITH EVERY RE-ASK AT 0
(2026-08-23, (INC.14) LANDED).** 252-254 ms of every query's floor is the ~190 program-wide
`init` passes, and the census had already said a `Checker` shared by k queries answers all k
exactly as k fresh ones do. **The refactor the queue called for was not needed, and the
census's own model is why**: a checker asked a k-th query IS a checker whose partition is
those k files, so the arrangement is expressible with no checker surgery — hand `recheckOnly`
the working set once and capture all of it in the one walk. `Project.prepare(files)` is that,
made public; beside it and independent of it, `diagnosticsOf`'s memo is now keyed by the
PARTITION the build walked, so a question about any SUBSET is answered by filtering — which is
the error-reporting case whole, N builds for N buffers becoming ONE. **THE ORDER GAP THE
CENSUS LEFT IS CLOSED FIRST, AND IT CLOSED CLEANER THAN PROGRAM ORDER**: the differential's new
`editor` arm is a deterministic shuffled query SEQUENCE with REVISITS, compared position by
position, with the COLD arm run over the same sequence so the reference's own order-dependence
is a control that REFUSES the run rather than an assumption — **101 queries over 76 files, 25
revisits, 1,070,012 compared rows per run, and 0 divergent rows at k=3 (2.16x) and k=8
(3.88x)**, 1 at k=26 (5.18x) which is byte for byte the row program order already found and
already inside `capture-equivalence.sh`'s 5-span baseline; `coldSelfDiverged =
sharedSelfDiverged = 0` in all three, i.e. a revisited file is answered identically by a fresh
checker AND by a reused one. Replicated in a second warm run (4,997 -> 704 and 2,376 -> 539),
with the existing 15-query block unmoved as a CONTROL. **What a held prepared check costs has
a control rather than being an absolute — heap 163 -> 167 MB, identical to the MB in all six
rotations, ~4 MB for a 415 KB working set**, bounded at ONE and dropped by any edit.
**Seven ablations, seven discriminating, each with its own RED set** — the first round in this
arc with no arm recorded as a control. **REFUSED with its arithmetic**: making the working set
AUTOMATIC costs `k·floor + k(k+1)/2·perFile` against a cold `k·floor + k·perFile`, a loss at
every k with the floor at 365 ms and a median file at 31 — a host knows its open buffers and
this layer does not. Suite **15,701 / 0 / 3** (+13), `cost_gate.py` PASS (largest +1.02%
`mapped.hits`, the standing drift and the expected answer for a round that changed no compiler
code), `huge_methods.py --fail-over 0` green on core and `-project`, and all four equivalence
sweeps exactly at their baselines. Successor **(INC.17)**: the re-entrant checker buys exactly
what `prepare` cannot — a query about a file the host did not name — and its first step is a
count, not a rewrite.

**A `Checker` SHARED BY EIGHT QUERIES ANSWERS ALL EIGHT EXACTLY AS EIGHT FRESH CHECKERS DO —
ONE DIVERGENT ROW IN 741,864, AND IN IT THE SHARED ARM IS THE ONE THAT IS RIGHT (2026-08-23,
(INC.14) census).** (INC.14) is 63% of every query's floor and its blocker was never the
refactor but the caches a surviving checker carries — `symbolTypes` persists the FIRST
resolution, so reuse makes WHICH QUERY RAN FIRST observable, the mechanism that cost three
rounds in (INC.2)/(INC.5)/(INC.6). **It is answerable with no checker surgery, and that is the
part worth copying: a checker that has already answered k-1 queries and is asked a k-th IS a
checker whose partition is those k files**, because `recheckOnly` is a SET the spine walks in
program order either way. `scripts/checker-reuse-differential.sh` runs one build per query
against one build per GROUP and compares captured types, captured definitions AND diagnostics,
per file: **381,666 types + 360,152 definitions + 46 diagnostics over 76 of tsc's own sources,
1 row differs** — `definitions=0 diagnostics=0 sharedRendersMoreAny=0 absentInShared=0` — and
that row is the per-query arm inventing a redundant `X & X` for a function type, already one of
the 5 spans `capture-equivalence.sh` gates. **Replicated at k = 2, 8 and 26, which re-groups
every file: the SAME row each time**, with the prize measured in the same runs — **1.79x /
3.19x / 3.82x** (cold 38.4-39.5 s over 76 builds, replicating to +-1.4%). So what is left of
(INC.14) is the refactor alone. **(INC.15) — reusing only the BIND — is REFUSED with its
number**: the mechanism is sound (`--bindMutationCheck` reads `checked 15580, changed 0` beside
`mergeSingleSymbol: adopts 406, mutates 175`, all 175 on LIB symbols), but bind is 66-74 ms of
a 359-407 ms floor = 10.7% of a first hover, 3.1% of one in `checker.ts`, **2.75% of the whole
15-query editor sequence and 0 on the first query after an edit** — and a reused checker
carries its own bind, so (INC.14) subsumes it. The refusal's successor is (INC.16):
**`bindLexicalScopes` is 69 of the bind's 74 ms**, and one line blocks (INC.9)'s deferral
template — a program-wide block-scoped enum/alias collector that reads every file's scopes.
Suite **15,688 / 0 / 3** (+5 pins), `cost_gate.py` PASS (largest +1.02% `mapped.hits`, the same
pre-existing drift and the expected answer for a round that changed no compiler code),
`huge_methods.py --fail-over 0` green on core and `-project`, and all four equivalence sweeps
at their baselines. **Three ablations, all recorded as CONTROLS rather than claimed** — one
demonstrably a dead arm, two not provably reached — with the fixture's own structural
half-blindness stated: a capture is recorded DURING the walk, so a later-walked file cannot
influence an earlier one's answers in either arm.
