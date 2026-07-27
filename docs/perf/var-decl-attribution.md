# (TYPE.2) — inside `spineCtaM3StatementAnchor` and `checkVarDeclAssignability`

*Round 738. Sixth in the sequence `docs/perf/dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736, the
arc's first landed win) → `type-of-expression-attribution.md` (737) → here.
Derived by instrumentation (`CtaSections`, opt-in `--ctaSections` /
`--ctaSectionsCoarse`), verified by the whole corpus suite, a byte-identical
profile `--listAll` in all three modes, and a cost gate at +0.00% on all 20
deterministic counters.*

> **HEADLINE — BOTH OF THE ITEM'S PRIORS ARE FALSE, THE SECOND ONE BY 65×, AND
> THE FUNCTION IS NOT WHAT ITS NAME SAYS.** The item asked whether the 36 µs per
> initializer is mostly **flow narrowing** (prior i) and whether the ~2,470 ms
> outside the typing is the **assignability relation** (prior ii). Measured
> inside the function: **flow narrowing is 1 ms of 872 ms (0.11%)** and the
> **relation is 13 ms (1.5%)**. What is actually there is that
> **12,960 of 15,116 invocations (86%) never reach any assignability check at
> all** — they are UNANNOTATED declarations that exist only to type an
> initializer and record it in `currentLocalTypes`, and that branch alone is
> **405 ms, 46% of the function**. `checkVarDeclAssignability` is two functions
> sharing a name: 12,960 unannotated declarations at **34 µs** each and 1,881
> annotated ones at **227 µs** each.
>
> **The handler is 2,363 ms and it is all callee work.** The eligibility gate
> and its parent-chain climbs — consulted at all **856,976** nodes — are
> **194 ms (8.2%)**, and the whole ambient install/restore/dispatch scaffolding
> is another **158 ms (6.7%)**. The remaining 85% is four passes:
> `checkVarDeclAssignability` 891, `checkReturnAssignability` 615,
> `checkAssignmentExpression` 318, `walkFunctionBodiesInExpr` 181. **Round 733's
> rule, third confirmation: a handler's nanos are its WORK, never its
> scaffolding.**
>
> **NO OPTIMISATION WAS LANDED.** The largest candidate the attribution
> surfaced — hoisting the unannotated branch above the ~18-walker prologue — was
> priced and is worth **≈0**, because every prologue walker already bails on
> `decl.type ?: return false` in its first line. Everything else is inside the
> ±2% band (~590 ms).

---

## 1. What was built

`CtaSections` in `src/commonMain/kotlin/SpineDispatch.kt`, plus boundaries in
`spineCtaM3StatementAnchor`, `ctaM3StmtAnchor` and `checkVarDeclAssignability`.

* **Two independent running-section partitions in one object**, with their own
  depth counters, because the handler and the function nest:
  * **level A** — the whole handler, split by CALLEE.
  * **level B** — `checkVarDeclAssignability`, split into its prologue walker
    groups, the type computations, the narrowing site, the relation, the
    elaboration and the tail.
* **Level A opens on the HANDLER, not on `ctaM3StmtAnchor`.** This is the one
  structural difference from rounds 733–735 and it is what makes the round's
  second-most-useful number exist: the eligibility decision (a kind test, a
  parent test and the `ctaM3NestedChainOk` / `ctaM3FnBodyAnchorScope` /
  `ctaM3NearestList` parent-chain climbs) is a partition ROW rather than an
  unmeasured remainder. Round 732 measured this handler at 2,900 ms; a partition
  that started inside `ctaM3StmtAnchor` would have left ~700 ms of that
  unexplained and invited exactly the "it must be the dispatch machinery"
  inference § 0's round-733 correction warns against.
* **Both functions were split into a wrapper and a `…Core`.** Production
  branches once on a static read; the probe opens the partition and closes it
  from a `finally`, so it survives `checkVarDeclAssignability`'s ~40 early
  `return`s and the handler's `when` arms. Level B's `finally` records WHICH row
  the invocation left in, so the **exit profile comes free** — and it is the
  number that decides this item.
* **Four expressions were rewritten to name a value** so a sub-measure could
  bracket it: the source-type computation, the two `getNarrowedTypeForReference`
  calls, the narrowing's confirming `checkTypeRelatedTo` and the assignability
  relation itself. Short-circuit order is preserved in each;
  `CtaSectionProbeTest` pins that with a fixture that emits the instrumented
  function's own TS2322.

## 2. Calibration — and an honest admission about this box

| run | wall | level A partition | level A boundaries | level B partition | level B boundaries |
|---|---:|---:|---:|---:|---:|
| `--ctaSections` (ON) | 29,524 ms | **2,363 ms** | 1,264,382 | **872 ms** | 99,853 |
| `--ctaSectionsCoarse` | 31,014 ms | 2,465 ms | 930,278 | 861 ms | 15,116 |

**Level B's differential works**: Δ = +11 ms over 84,737 extra boundaries =
**130 ns each**, the same order as rounds 734/735's independently-derived
86–89 ns, and it bounds level B's probe inflation at ~1.3%.

**Level A's differential does NOT work, and saying so is the point.** Its Δ is
**−102 ms over +334,104 boundaries** — negative, i.e. entirely swamped by
run-to-run drift (the two runs' walls differ by 1,490 ms, and this box's drift
band is ±13%). A 334 k-boundary difference should cost ~30 ms at the established
89 ns; that is 1.3% of a 2,363 ms partition and cannot be resolved against a
±3.8 s wall swing. **So level A's inflation is COMPUTED from the established
per-read cost, not measured**: 1,264,382 reads × 89 ns ≈ **113 ms of 2,363 ms
(4.8%)**, and the tables below quote RAW. Every millisecond here is **relative
attribution within its partition**, not a wall-clock price.

The in-situ empty-span calibration was deliberately **not** taken: rounds
734/735 measured that construction over-reading by 3.6× and 4.4× in consecutive
rounds, and re-deriving a known-wrong number is not evidence.

## 3. Level A — the handler, by callee

Compiler profile. **856,976 handler invocations** (it is consulted about every
node), of which **58,581 anchor** a statement.

| row | ms raw | share | reached |
|---|---:|---:|---:|
| **`checkVarDeclAssignability`** | **891** | **37.7%** | 14,735 declarations |
| **`checkReturnAssignability`** | **615** | **26.0%** | 9,926 |
| `checkAssignmentExpression` | 318 | 13.5% | 16,538 |
| **eligibility gate + parent climbs** | **194** | **8.2%** | 915,543 |
| `walkFunctionBodiesInExpr` | 181 | 7.7% | 28,940 |
| the `when` dispatch + declaration loop | 65 | 2.7% | 137,143 |
| frame + ambient install + ns push | 50 | 2.1% | 58,581 |
| `finally` (truncate + twelve restores) | 18 | 0.8% | 58,581 |
| `checkFlowNoOverlapCondition` | 17 | 0.7% | 12,878 |
| `registerConstLiteralUnionNarrowing` | 8 | 0.3% | 11,514 |
| `checkPropertyInitAssignability` | 0 | — | 3 |
| **total** | **2,363** | | |

Anchored statements by kind: `var` 14,712, `expr` 16,372, `return` 14,605,
`if` 12,878, `property` 14. By mode: list arm 55,921, bare surface 2,010,
recordOnly 650.

**Two readings.**

1. **The gate is 212 ns per node and 194 ms in total.** That is the
   CONSULTATION cost § 0 named, isolated for the first time on a real handler,
   and it is 0.7% of a 29.5 s compile. It includes the parent-chain climbs, which
   are the part that looked expensive from the outside. Round 732 sized
   (DISPATCH.1) at 1.0–2.5 s and its own follow-up corrected that to ~100–300 ms
   across ALL handlers; this row is the per-handler shape of the same finding.
2. **Gate + scaffolding = 352 ms = 14.9% of the handler, 1.2% of the compile.**
   The other 85% is the four passes' own checking. A handler's per-handler nanos
   are its WORK.

## 4. Level B — inside `checkVarDeclAssignability`, and the exit profile

**15,116 invocations, 872 ms.** The rows are in source order; `exited` is the
count of invocations that returned inside that row.

| row | ms raw | share | reached | exited |
|---|---:|---:|---:|---:|
| `ObjectBindingPattern` branch | 31 | 3.6% | 15,116 | 275 |
| prologue 1 (variance/B526/2820/B554/B470) | 50 | 5.7% | 14,841 | |
| **prologue weak (B482 ×3 + B582)** | **165** | **18.9%** | 14,841 | |
| prologue 2 (B286/B422/B294/B296/B298) | 23 | 2.6% | 14,841 | |
| prologue 3 (B101/B206/B181/B208) | 27 | 3.1% | 14,841 | |
| **unannotated-init inference** | **405** | **46.4%** | 14,841 | **12,960** |
| varTypes / localTypes recording | 22 | 2.5% | 1,881 | 1,083 |
| `noUncheckedIndexedAccess` | 0 | — | 798 | |
| `null!` + B85.3 ternary | 0 | — | 798 | 1 |
| `getTypeFromTypeNode` (TARGET) | 1 | 0.1% | 797 | |
| clodule / B96 / B231 / class blocks | 6 | 0.7% | 797 | |
| SOURCE type computation | 57 | 6.5% | 797 | |
| **flow narrowing block** | **1** | **0.11%** | 797 | |
| foreign-TP + B112 + B207 | 4 | 0.5% | 797 | 26 |
| **`canUseTypeEngine` + RELATION** | **19** | **2.2%** | 771 | |
| post-relation walkers (~30) | 51 | 5.8% | 771 | 7 |
| TS2322 elaboration (408 lines) | 0 | — | 764 | |
| tail (numeric literal + varTypes) | 1 | 0.1% | 764 | 764 |

Nested sub-measures:

| | ms raw | calls | ns each |
|---|---:|---:|---:|
| `getTypeOfExpression(init)` (SOURCE row) | 55 | 797 | 69,585 |
| `checkTypeRelatedTo` (the relation) | 13 | 631 | 22,146 |
| `canUseTypeEngine` | 3 | 771 | 5,075 |
| `getNarrowedTypeForReference` | ~0 | 37 | 22,000 |
| — of which returned the INPUT unchanged | ~0 | 34 | 10,862 |
| the narrowing's confirming relation | ~0 | 3 | 29,512 |
| `getTypeFromTypeNode` (TARGET) | ~0 | 797 | 563 |

**The exit profile is the answer.** Of 15,116 invocations:

* 275 (1.8%) are destructuring patterns and leave in the first row;
* **12,960 (85.7%) leave in the unannotated-init branch** — no annotation, so
  there is nothing to check; the work is `getTypeOfExpression(init)` plus a
  widening and a map write;
* 1,083 (7.2%) are annotated with no initializer and leave in the recording row;
* **only 797 (5.3%) reach the target-type computation**, and 764 run to the tail.

**So the two populations are:**

| | invocations | ms | µs each |
|---|---:|---:|---:|
| UNANNOTATED (`const x = init`) | 12,960 | ~436 | **34** |
| ANNOTATED (`const x: T = init`) | 1,881 | ~427 | **227** |

Round 737's 36 µs per initializer was the mean of these two.

### The cost distribution, which the mean hides again

| bucket | invocations | ms |
|---|---:|---:|
| < 10 µs | 2,852 | 23 |
| 10–100 µs | 11,282 | 271 |
| 0.1–1 ms | 911 | 185 |
| **≥ 1 ms** | **71 (0.47%)** | **406 (47%)** |

Same shape as round 735's argument walks: **71 invocations carry 47% of the
function at 5.7 ms each.** Which population they belong to was not recorded and
is the one loose end here; at 406 ms (1.4%) it is inside the band either way,
and the row it must pass through is the source-type or unannotated typing, i.e.
the population round 737 measured and struck.

## 5. The prologue — the price of the dedicated-walker architecture, measured

The four prologue groups are **265 ms (30% of the function)** against a
**19 ms** relation and a **13 ms** `checkTypeRelatedTo`. **The FP-firewall
walkers that exist to correct the relation cost 14× the relation itself.**

They are not, however, a lever, and the reason is worth recording because it is
the obvious idea:

* **"Hoist the unannotated branch above the prologue" saves ≈0.** Every prologue
  walker's first or second line is `decl.type ?: return false` (verified for
  `tryEmitVarianceMeasurementVarDecl`, `tryEmitNestedWeakVarDecl`,
  `tryEmitObjectLiteralWeakLeaves`, `tryEmitTopLevelWeakVarDecl`,
  `tryEmitGenericMappedAsClauseVarDecl`), so the 12,960 unannotated declarations
  already pay one field read each. The 265 ms is spent on the **1,881 annotated**
  declarations — 141 µs each — not on the 86% majority. An estimate of
  "265 ms × 86% = 231 ms" would have been wrong for exactly the reason § 0's law
  keeps producing: the population that looked skippable was already the cheap
  one.

## 6. The prediction, scored

Stated in full before the run (`predictions.md`, reproduced here).

| | prediction | measured | |
|---|---|---|---|
| P1 | flow narrowing ≤ 25% of level B — prior (i) FALSE as stated | **1 ms = 0.11%**; prior (i) wrong by ~200× | **HELD** |
| P2 | `checkTypeRelatedTo` ≤ 10% of level B — prior (ii) FALSE | **13 ms = 1.5%**; prior (ii) wrong by ~65× | **HELD** |
| P3 | `A_VDECL` largest but < 50%, AND `walkFunctionBodiesInExpr` ≥ 15% | largest ✓ at 37.7% ✓, but walkFn is **7.7%** | **FALSIFIED** |
| P4 | level-A partition 2,000–3,000 ms | **2,363 ms** | **HELD** |
| falsifier | any row > ~590 ms that is not an already-known population ⇒ optimise | largest new row is 405 ms of expression typing | **not met** |

Three of four, against two of four in each of rounds 732–737. The one that
failed is P3's second clause: `walkFunctionBodiesInExpr` sounds like a full
sub-walk and is 7.7%, because the bodies it walks are mostly already walked.

## 7. What did NOT work / was priced and rejected

* **Both of the item's priors.** Narrowing 0.11%, relation 1.5%. Round 735
  found the relation prior wrong by 48× one function over; here the same prior
  is wrong by 65×. **The prior "an assignability check's cost is the relation"
  has now been falsified twice, in the two largest assignability sites in the
  compiler.** It should not be proposed a third time without measuring first.
* **Hoisting the unannotated branch above the prologue** — ≈0, § 5.
* **Attacking the eligibility gate / the ambient install.** 194 + 158 = 352 ms
  = 1.2% of the compile, and the install is semantically load-bearing (twelve
  fields whose save/restore reproduce `checkFunctionBody`'s ambient).
* **Anything keyed on "the handler is 2,900 ms".** It is 2,363 ms after round
  736's −4.53%, and 85% of it is four named passes' own checking work, each of
  which is separately below the band.

## 8. What this means for § 0.1's staged plan

Level A's biggest row after `checkVarDeclAssignability` is
**`checkReturnAssignability` at 615 ms**, which no round has opened and which
round 737 credited with 295 ms of expression typing. It is below the band, so it
is a measurement rather than a lever, and by the shape established here
(assignability sites are typing + walkers, not relation) its answer is
predictable enough not to be worth a round on its own.

**The var-decl path contributes nothing new to stages 1–5.** Its expression
typing is stage 3's population (struck by round 737); its narrowing is 0.11%; its
relation is 1.5%. What it does contribute is the third independent measurement of
the same structural fact — **the checker's cost is spread across hundreds of
dedicated walkers, each individually far below the drift band** — which is § 0.1's
"endgame" paragraph, now with a price on one instance of it: on this path the
walker prologue costs 14× the relation it corrects.

## 9. Verification

* Full corpus suite: **12,923 tests, 0 failures, 3 skipped** (12,916 + 7 new
  `CtaSectionProbeTest` pins).
* Compiler profile `--listAll`: production, `--ctaSections` and
  `--ctaSectionsCoarse` produce **identical** diagnostics (46 errors, identical
  sorted lines).
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**.
* Probe inflation: level B measured at 11 ms (1.3%); level A computed at ~113 ms
  (4.8%) because its differential is drift-dominated (§ 2).

## 10. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the attribution (grep '(TYPE.2)')
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSections build/bench/tsc-project-*
# the calibration counterpart — subtract the partition totals, divide by the
# extra boundary count the ON run's own `calls` array reports
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSectionsCoarse build/bench/tsc-project-*
```
