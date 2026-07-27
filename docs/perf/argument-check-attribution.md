# (CALL.2) step (a) — what is actually inside `checkArgumentsAgainstSignature`

*Round 735. Fourth in the sequence `docs/perf/dispatch-table.md` (732, six hot
handlers) → `docs/perf/spine-leave-attribution.md` (733, the passes' own work) →
`docs/perf/call-expression-attribution.md` (734, the call function's type-system
share) → here. Derived by instrumentation (`ArgSections`, opt-in `--argSections`
/ `--argSectionsCoarse`), verified by the whole corpus suite, an identical
profile `--listAll` in all three modes, and a cost gate at +0.00% on all 20
counters. The instrumentation is behaviour-free when off.*

> **HEADLINE — THE PRIOR HOLDS, BY ~48×, AND THE MECHANISM IS NOT THE ONE THE
> PRIOR NAMED.** The item's falsifiable expectation was *"most of the 61 µs is
> argument TYPE computation, not `checkTypeRelatedTo`"*. It is: **924 ms of the
> function's 1,624 ms is the `argType` computation, against 19 ms for the
> `checkTypeRelatedTo` + TS2345 section (10 ms for the relation call itself).**
> But the prior's supporting evidence — `getTypeOfExpression` at 3,911 ms and
> ×2.7 recompute — points at the wrong term. **Inside this function
> `getTypeOfExpression` is 196 ms (12%); FLOW NARROWING is 600 ms (37%).**
> So (CALL.2) does NOT lead to `ARCHITECTURE-RETHINK` § 0.1 **stage 3**
> (the `getTypeOfExpression` recompute factor). It leads to **stage 4**,
> flow narrowing — and it hands stage 4 its first above-band target:
> **compile-wide, 394 of 70,037 narrowing walks (0.56%) cost 1,485 ms — 47% of
> all narrowing and 4.9% of a 30.5 s compile, versus a ±2% band of ~610 ms.**
> **No optimisation was landed.** Three candidate mechanisms for those 394 were
> tested and all three are FALSE (§ 5); the next unit must attribute inside one.

---

## 1. What was built

`ArgSections` in `src/commonMain/kotlin/SpineDispatch.kt`, plus boundaries
inside `checkArgumentsAgainstSignature`, built on the round-733/734 model:

* **The function was split into a wrapper and `…Core`.** The wrapper branches
  once on `ArgSections.mode` and otherwise calls the core directly — no
  `try`/`finally` and no bookkeeping in production.
* **17 partition rows**: three pre-loop, thirteen inside the per-ARGUMENT loop,
  one post-loop. A running timestamp closed by `end()` from the wrapper's
  `finally`, so the partition survives the prologue's early `return`s and the
  loop's `continue`s.
* **Eight nested sub-measures**, of which two are the ones that decide the item:
  `getTypeOfExpression(arg)` and the `checkTypeRelatedTo(argType, paramType)`
  gate. Each is a single timestamp pair around a single call, so they are
  comparable to each other regardless of how boundary-inflated the surrounding
  partition is.

**The structural difference from `CallSections`, and it matters when reading the
report: most sections are inside the loop, so `calls[s]` counts loop ITERATIONS,
not invocations.** The drop between two consecutive loop sections is the number
of iterations that `continue`d inside the earlier one — the exit profile again
comes free, but per argument. `invocations` and `iterations` are counted
separately.

Three boundaries required naming a value that was previously an inline
expression (`isSimpleCheckableType(paramType)`, the TS2345 relation gate, the
M3.4 refinement gate) so a sub-measure could bracket it. Short-circuit order is
preserved in each; `ArgSectionProbeTest` pins that with a fixture that emits the
instrumented function's own TS2345.

## 2. Calibration — the in-situ empty span is wrong again, by 4.4×

Round 734 recorded two failed calibrations and settled on a DIFFERENTIAL. This
round generalises the differential into a **mode**: `--argSectionsCoarse` keeps
only three anchors (`PRO` / `L_PARAM` / `POST`), so every other boundary costs a
static read and a not-taken branch instead of a timestamp pair, while the
partition still spans the same wall time.

| run | partition raw | partition boundaries | nested reads |
|---|---:|---:|---:|
| `--argSections` (ON) | 1,624 ms | 404,358 | ~293,000 |
| `--argSectionsCoarse` | **1,569 ms** | 83,085 | 0 |

**Δ = 55 ms over ~615,000 extra timestamp reads = 89 ns each.** That
independently reproduces round 734's differential (86–92 ns) by a completely
different construction. The unrolled in-situ empty-span calibration in the same
ON run reported **391 ns** — **4.4× too high**, exactly the error round 734 saw
at 306 ns against 86.

**So: total probe inflation is 55 ms of 1,624 ms = 3.4%, and the probe-free cost
of the function on this profile is ~1,569 ms.** (Round 734's `--callSections`
measured 1,357 ms for the single-signature path alone; this partition also
covers the 274 invocations from the overload and type-argument paths, and
run-to-run drift on this box is ±13%.) The tables below quote RAW. **Sound for
RELATIVE attribution only.**

## 3. The exit profile — three quarters of arguments never reach the relation

Compiler profile, **22,419 invocations / 38,247 loop iterations**.

| iterations leave the loop body in… | count |
|---|---:|
| the arity `break` / spread / any-param gates | 868 |
| the foreign-TP + weak-target section | **14,663** |
| the `!isSimpleCheckableType` function-vs-function block | **12,280** |
| `paramType is TypeParam` | 211 |
| the optional-parameter / undefined gates | 79 |
| **the `checkTypeRelatedTo` + TS2345 section** | 10,146 |

**Only 10,146 of 38,247 arguments (27%) ever reach the assignability check** —
yet all 37,379 that get past the first gate pay for the full `argType`
computation at the top of the body, because every intervening block consumes
`argType`.

## 4. The attribution

Raw ms; percentages of the 1,624 ms raw partition.

| region | ms raw | share |
|---|---:|---:|
| **`argType` computation (one loop section)** | **924** | **56.9%** |
| — of which **`getNarrowedTypeForReference`** | **600** | **36.9%** |
| — of which `getTypeOfExpression(arg)` | 196 | 12.1% |
| — of which `literalTypeOfExpression` + `propTypeContainsLiteral` | 25 | 1.5% |
| — of which the M3.4 refinement gate's `checkTypeRelatedTo` | 10 | 0.6% |
| — residue (`stripNullishForNonNullArg`, `voidIifeArgType`, ctx save/restore) | ~93 | 5.7% |
| **`checkTypeRelatedTo` + the TS2345 emission** | **19** | **1.2%** |
| — of which the relation call itself | 10 | 0.6% |
| the `!isSimpleCheckableType` function-vs-function block (196 lines) | 103 | 6.3% |
| `tryInferSingleTypeParamFromArgs` + `instantiateSignature` | 110 | 6.8% |
| the ten generic-gated prologue walkers | 63 | 3.9% |
| `tryEmitWeakTypeAssignment` | 75 | 4.6% |
| B199/B204/B219 single-type-parameter walkers | 52 | 3.2% |
| the arg-kind + class/index-signature block | 33 | 2.0% |
| post-loop rest-args check | 28 | 1.7% |
| the nullish-argument blocks | 29 | 1.8% |
| everything else (six sections) | 88 | 5.4% |

**The item's split, stated as the item asked:** argument TYPE computation
**924 ms** versus RELATION work **19 ms** — a factor of **48**. Adding every
`checkTypeRelatedTo` reachable from this function at depth 0 (the M3.4 gate's
10 ms) does not change the verdict.

### The narrowing, split three ways

| narrowing site | ms raw | walks | µs each |
|---|---:|---:|---:|
| B469 — a UNION-typed `Identifier`/`PropertyAccess` argument | 284 | 2,339 | **121** |
| M3.4 — an Interface/`unknown`/`string`/`number` argument | 316 | 7,271 | 43 |
| round-441 — a `never` parameter | 0 | 5 | 81 |
| **total** | **600** | **9,615** | **62** |
| *of which returned the INPUT type unchanged* | *237* | *8,299 (86%)* | *28* |

**86% of the walks change nothing**, so a pre-test that could prove "this
reference has no flow facts" is worth **at most 237 ms** here — 0.8% of the
compile, inside the drift band. It is not the lever.

### The cost distribution is what the mean hides

This function's 9,615 walks:

| bucket | walks | ms |
|---|---:|---:|
| < 10 µs | 5,205 | 23 |
| 10–100 µs | 3,808 | 112 |
| 0.1–1 ms | 515 | 136 |
| **≥ 1 ms** | **87 (0.9%)** | **329 (55%)** |

A mean of 62 µs describes neither population.

## 5. Compile-wide — the finding this round hands forward

The same histogram was added to `flowWalkWithTripCheck` under `--passTiming`, so
it covers **all 70,037 walks from all 11 call-site kinds**, not just this
function's 9,615:

| bucket | walks | ms |
|---|---:|---:|
| < 10 µs | 40,046 | 159 |
| 10–100 µs | 25,695 | 811 |
| 0.1–1 ms | 3,902 | 827 |
| **≥ 1 ms** | **394 (0.56%)** | **1,485 (47%)** |

**394 walks carry 1,485 ms — 4.9% of a 30.5 s compile.** That is the first
single target measured above the ±2% drift band (~610 ms) since round 731, by
2.4×. By kind: `WK_NARROW` 336, `WK_NARROW_LOOP` 47, `WK_BASE_EXPR` 11 — so it
is one walk function, not one caller.

**Three mechanisms were hypothesised and all three are DISPROVED by the same
run — record them so they are not re-derived:**

1. **"The monsters are TRIPPED walks."** A tripped walk is deliberately never
   memoized (its result is truncated and its TS2563 side effect must keep
   firing), so a reference that trips would pay in full at every visit. **FALSE:
   `narrowWalk tripped: 0 walks` on this profile.** Nothing trips.
2. **"The monsters exhaust the 1,000,000-visit budget."** **FALSE:** the 394
   consume **630,641 flow-node arrivals in total — 1,601 each, maximum 19,515.**
   Two orders of magnitude below the budget.
3. **"The walk memo would serve them."** **FALSE:** `walkMiss split:
   cold=69,968 epochInvalidated=69` — essentially every launched walk is a
   FIRST sighting of that (reference, kind, input). The LIVE memo already serves
   40,709 walks; what remains is cold by construction and no cache reaches it.
   (This is § 0's law again, from a fourth direction.)

**What is left is arithmetic.** All 70,037 walks: 8,536,124 node arrivals,
3,173 ms → **372 ns per arrival**. The 394 monsters: 630,641 arrivals, 1,485 ms
→ **2,354 ns per arrival**. So the tail has **13× more arrivals AND 6.3× more
expensive arrivals**, and neither factor alone explains it.

## 6. What this means for § 0.1's staged plan

* **Stage 3 (cut the `getTypeOfExpression` ×2.7 recompute, "up to ~9%") is NOT
  reachable through this path.** This function makes 37,379 of the compile's
  701,736 `getTypeOfExpression` calls (5.3%) at 5.2 µs each against a 5.6 µs
  whole-compile mean, and it types each argument exactly ONCE — it is not a
  recompute site. Finding the recompute needs a different measurement:
  attribute the 701,736 calls **by caller**, since the factor exists because
  several handlers independently type the same node.
* **Stage 4 (flow narrowing, 3,173 ms) is where (CALL.2) actually lands**, and
  it now has a shape rather than a total: 47% of it is 394 walks.

## 7. The next unit, stated concretely

Attribute INSIDE a monster walk, exactly as this round attributed inside the
argument check. Two numbers decide the fix and neither is known:

* **Arrivals versus DISTINCT flow nodes per walk.** The intra-walk memo
  (`NarrowFlowMemo`, tsc's `sharedFlowNodes`) serves only entries stored at a
  same-or-deeper entry depth (`served(id, depth)` requires `depth <= stored`),
  so a revisit reached by a LONGER path misses and recomputes. If arrivals ≫
  distinct nodes, that depth condition is the lever and the question is whether
  it can be relaxed without changing a truncated result.
* **Per-arrival work.** 2,354 ns is not graph traversal. Split
  `narrowTypeFromFlow`'s per-node cost — `applyConditionNarrowing`,
  `flowAssignmentMightNarrow`, `flowCallMightNarrow`, the `FlowBranchLabel`
  fan-out and its `mark`/`popToMark` — the same way.

Anything proposed before those two numbers exist repeats the error of rounds
732, 733 and 734, which predicted levers 5×, 6–17× and ≥2× too large from a
plausible reading of an aggregate.

## 8. Verification

* Full corpus suite: **12,899 tests, 0 failures, 3 skipped** (12,892 + 7 new
  `ArgSectionProbeTest` pins).
* Compiler profile `--listAll`: production, `--argSections` and
  `--argSectionsCoarse` produce **identical** diagnostics (46 errors, identical
  sorted lines).
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**.
* Probe inflation, measured not asserted: 55 ms of 1,624 ms (§ 2).

## 9. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the attribution
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSections build/bench/tsc-project-*
# the calibration counterpart — subtract the partition totals, divide by the
# extra boundary count the ON run's own `calls` array reports
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSectionsCoarse build/bench/tsc-project-*
# the compile-wide walk histogram (grep `narrowWalk`)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming build/bench/tsc-project-*
```
