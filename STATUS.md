# Status

**A HOVER HAS BEEN RENDERING AN UNBOUND `T` PROGRAM-WIDE ON THE ORDINARY SHIPPED BUILD, AND THE
FLOOR'S BIGGEST ROW IS REFUSED ON A PREMISE ITS OWN QUEUE ITEM HAD BACKWARDS (2026-08-23,
(INC.11)).** The item existed to unblock **66 ms** — `init:buildFileLocalTypeMaps`, the largest
single row in a 212 ms floor pass table — on the belief that the cost buys nothing but a
program-wide FIRST-TOUCH ORDER for interning and `aliasDisplayMap`. **A three-phase re-measurable
arm says part of it buys RESOLUTIONS**: fully deferred is **1,665 divergent capture spans in 47
files with `narrowRendersMoreAny = 321`** — 321 resolutions LOST TO `any`, which no display fix can
reach. (The arm beats (INC.10)'s own table 1.6x-3.4x — 137 / 10 files for `TYPEALIAS`-only against
462 / 18 — and refuses anyway.) **REFUSED, and the item is closed rather than re-tried.**
**CLASSIFYING THE RESIDUAL REFUTED THE QUEUE'S HYPOTHESIS AND FOUND A SHIPPED DEFECT.** The item
guessed the undiagnosed 462 spans "may be two genuinely different `Type` instances". They are **ONE
instance carrying TWO COMPETING NAMES**: an `Extract<ClassLikeDeclaration, Pick<T, "kind">>` whose
conditional CANNOT DECIDE (a free `T` in the second argument) answers its own CHECK TYPE — the
interned union — and the generic site then wrote `aliasDisplayMap[union.id] = ("Extract", args)`
**unconditionally**. So a caret on `ClassLikeDeclaration` reported
`Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>` — **an unbound `T` in a tooltip, for
every user, on the whole-program build** — and nothing in this repo could see it but the capture
sweeps, since no diagnostic moves. **FIXED**: an instantiation that answers one of its own arguments
unchanged no longer registers a name for it, verified against the ablated binary through the CLI
(`Type 'Pass<Shape, Pick<T, "a">>' is not assignable to type 'number'`).
**TWO INSTRUMENT LESSONS, BOTH OF THE FAILING-REASSURINGLY KIND.** The census had to be
**WITHIN-ARM** — `Type.id` is minted in resolution order, so no cross-arm comparison is possible —
and **the first hook watched the wrong site**, reading `0` clobbers at the last-wins site; **reading
that zero as "not a display question" would have been exactly wrong.** The sharpened census reads
**86** instantiations answering an argument unchanged (24x `Partial`, 18x `Extract`) with a positive
control that it is not dead: **6** different-name refusals at the first-wins site. And **the pin was
BLIND on its first draft** — the embedded lib declares no `Pick`, so the shape degraded to `any` and
it passed against the ablated binary; only the ablation said so. With `@useRealLibs` it goes RED
against the unfixed binary with its negative control green.
**WHAT REMAINS IS A CHANGE OF KEY, NOT OF POLICY**: the other half — 302 spans in `checker.ts` alone
under full deferral — is two SYNONYMOUS non-generic aliases resolving to one interned type, decided
first-wins, and **tsc picks by the REFERENCE's declaration site, which an id-keyed global map cannot
express**. Against round 754's deliberate `Type.Reference` exclusion and a union display order
pinned byte-for-byte across ~13k baselines, that is a logical-parity conversation and not worth
opening for a 66 ms already refused. **GATES**: suite **15,741 / 0 / 3** (+6 pins),
`capture-equivalence` **5 spans / 3 of 76, `narrowRendersMoreAny=0`** and `capture-channel` **286 /
49** both IDENTICAL, `partition-equivalence` EQUIVALENT on all 78, `partition-gate` EQUIVALENT on
both arms (78 netting passes), `cost_gate.py` PASS (`output.errors` 46, `spine.nodes` +0.00%,
largest `mapped.hits` **+1.02%**, the standing drift), `huge_methods.py --fail-over 0` clean on core
AND `-project`. **Floor 324 ms / median 357 / ratio 14.06x are DRAW-TO-DRAW against the round's
340 / 367 / 13.30x, NOT a saving — the landed change moves no work.**

**157 TAIL WALKERS ARE NOW PARTITION-SCOPED, THE INCREMENTAL FLOOR IS 340 ms, AND THE ONE-LINE
TECHNIQUE IS CLOSED BECAUSE 65% OF WHAT REMAINS IS REFUSED BY SHAPE (2026-08-23, (INC.7) batch 4).**
Batch 4 gated **89** more program-wide tail walkers onto the check partition in two independently
swept sub-batches, taking the arc to 157 across four batches: **floor 1,207 -> 340 ms, narrowed
query median 1,077 -> 367 ms, ratio at the median file 13.30x** (`partition-equivalence.sh`). The
diff is 89 loop headers and nothing else — `for (result in binderResults)` **221 -> 132**,
`checkedResults` **255 -> 344**. **THIS, NOT A REUSE MECHANISM, IS WHAT HELPS THE OWNER'S QUERY**:
(INC.15) measured that an edit invalidates every reuse mechanism, so the FIRST query after a
keystroke — the error-reporting query — reuses nothing and pays the whole floor. **THE FOURTH
DISCOUNT POINT IS THE LOWEST**: summed rows of the 89 **54.23 -> 0.13 ms**, whole floor pass table
**254.57 -> 212.16**, banked **42.41 ms = 78.2%**, next to 79.0 / 85.5 / 92.9. **DO NOT quote the
floor WALL for this** — the intermediate post-4a draw read **444 ms, HIGHER than before**, while
the deterministic pass table had already fallen 34.7 ms; a 42 ms effect is not resolvable in a
4-draw wall, which is round 716's "counters decide, wall time confirms" one instrument over.
**(INC.19)'s WRITE-ONCE-RACE HAZARD WAS CHECKED, NOT ASSUMED**: gating a walker is exactly the
operation that changes who wins such a race, and that failure is a plausible TYPE rather than a
diagnostic, so both capture sweeps ran after EACH sub-batch — `capture-equivalence` **5 spans /
3 files, `narrowRendersMoreAny=0`** and `capture-channel` **286 rows / 49 files**, byte-identical
throughout. **WHY THE TECHNIQUE IS DONE**: 172 ungated passes / 251.9 ms remain and the top TEN
rows are **165 ms** of it, every one refused — 53 of the 83 write a checker field or retract inside
the private closure, **43 retract via `diagnostics.removeAll`**, 4 carry more than one
`binderResults` reference, 4 hold a cross-file accumulator. Analyzer-CLEAN was only 54 ms in total.
A successor must change a pass's SHAPE, which is (INC.20), whose template is (INC.17)'s
`checkSubsequentVarTypes` split. **THE ANALYZER'S CONTROLS CAUGHT THREE DEFECTS IN THE ANALYZER,
ALL FAILING IN THE REASSURING DIRECTION**, the new one being that **a MULTI-LINE PARAMETER LIST
truncates a function's span to its header**, hiding the body and every field write in it — it
wrongly cleared two passes this queue had ALREADY REFUSED, so **the refusal list was the oracle
that caught the analyzer**. Also: a `pass("…")`-registering helper is not a caller, and without
excluding the 12 `initCheckPasses*` registrars the clean set is **0**. The pin is per-walker
attributable — an ablation reddens **exactly 3 of 22 arms, exactly the three naming that walker** —
and its first namespace-`this` fixture was VACUOUS until the PassLab said so (a `this` inside a
FUNCTION in a namespace body is reported by a different pass). **The sensitivity fixture is what
carried this batch**: it nets **16 of the 89** as real netting passes against **one** (`checkSpine`)
on every dashboard profile — (INC.18)'s whole point, collected one round later. Suite **15,735 / 0 /
3** (+7 pins), `cost_gate.py` identical (largest **+1.02% `mapped.hits`**, the standing drift),
`huge_methods.py --fail-over 0` clean (763 classes), `partition-gate.sh` **EQUIVALENT on both arms**
(realism 78/78; sensitivity 76/76, 78 netting passes).

**THE REPLAY'S LOST TYPE-PARAMETER CONSTRAINT WAS NEVER A REPLAY DEFECT — IT IS A WRITE-ONCE
INTERNED FIELD RESOLVED BEFORE ITS OWN SCOPE, FROZEN IN THE SEED BUILD, AND THE CORPUS IS
STRUCTURALLY BLIND TO IT (2026-08-23, (INC.19)).** The re-entrant replay diverged on **8 of 75
files**, and the queue's diagnosis was "the replay SET is too small — bisect it". The instrument
was built and **refuted that**: `Type.TypeParam.constraint` is interned per node and **write-once**
(24 guarded writers), and `checkConstraintsInStatements` resolved it BEFORE installing the
type-parameter scope — so `U extends T` resolved its sibling against the OUTER scope, answered
`errorType`, and froze. Two passes race for the field: `checkSpine` (dispatch row **28**,
partition-scoped) and `checkTypeArgumentConstraints` (row **261**, program-wide). **Unpartitioned,
`checkSpine` always wins, which is why all ~13k corpus baselines never saw it.** A setter probe
settles the direction: seed `binder.ts` -> target `debug.ts` reads `seedWrites=526 replayWrites=6
freshWrites=532` with the replay writing **ZERO** `U` constraints — **the damage happened in the
SEED, before any recheck**, so no replay-set change could ever have reached it. Exactly 6
constraints move from `checkTypeArgumentConstraints|error` to `checkSpine|T` between arms, and
exactly 6 renders differ. **THE FIX IS TWO SITES HOISTED AND A THIRD DELIBERATELY NOT** — and the
third is the round's best find: `withDeclTypeParamScope` serves the TYPE-ALIAS arm, where an alias
may constrain a parameter **by itself** (`type Shared<I, D extends Shared<I, D>>`, the react-redux
shape pinned by name), so hoisting there recurses without bound and the `init` guard reports a
spurious **TS2589 at (0,0)**. **The outer-scope resolution was accidentally load-bearing.** That
site took the missing write-once GUARD instead — its write was unconditional, i.e. it CLOBBERED a
correct constraint (measured: `U.constraint` going `T` -> `any`). **`replay-differential.sh`
realism 8 -> 5 diverging files, 23 spans of 373,879, and NOT ONE survivor is a lost constraint** —
they are lost generic INFERENCE (`Map<string, SeenPackageName>` -> `Map<any, any>`).
**THE PIN DISCRIMINATES IN BOTH DIRECTIONS**: `ProjectRecheckConstraintTest` is **2-of-3 RED
against HEAD** on the exact row `<T extends Nd, U extends T> != <T extends Nd, U>` **with its
control green**, and 0-of-3 against the fix. It had to be a **namespace-nested** generic function —
`init:buildFileLocalTypeMaps` (row 13) resolves every FILE-LEVEL `Function` symbol of every file,
partition or not, so the obvious top-level shapes are vacuous and cost an earlier attempt its
budget. **TWO NEGATIVES WORTH MORE THAN THE FIX.** (INC.8)(a)'s 167 `<K>` / `<K extends any>` rows
are **NOT** this bug — the channel is byte-identical after the fix (286 spans / 49 files), and a
probe reads `TPWRITE name=K was=any now=any`, i.e. the constraint is `any` *before*
`checkTypeArgumentConstraints` runs: a namespace-local type alias failing to resolve in constraint
position, a NAME-RESOLUTION defect. And **a replay set is a PER-PASS question, never a
superset/subset one**: `init:computeAllEnumValues` is classified partition-INVARIANT yet repairs a
file, `init:mergeLibGlobals` replayed is strictly WORSE, `init:wireGlobalArrayTypes` replayed **does
not terminate** — so a bisection may not assume monotonicity. Instrument (`scripts/replay-bisect.sh`,
`PassTiming.replayExtraPasses`, a RUN-TIME pass universe — a source grep of `pass("…"` reads **480**
names against the dispatch's **417**, so a grep-derived bisection cannot close) is committed and
resumable; 19 of 210 candidates swept. Suite **15,728 / 0 / 3** (+3), `cost_gate.py` PASS (largest
**+1.02% `mapped.hits`**, the standing drift), `huge_methods.py --fail-over 0` clean (763 classes),
`partition-gate.sh` **EQUIVALENT on BOTH arms**, `capture-equivalence` **5 spans / 3 files** and
`capture-channel` **286 / 49**, both unchanged. Three sites still resolve a constraint outside its
siblings' scope and are reported not fixed, the hardest being `Checker.kt:137404` — **inside
`typeParamInternCache.getOrPut`**, a first-touch freeze by construction.

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

