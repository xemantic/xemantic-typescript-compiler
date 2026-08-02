# (CALL.2) step (a) — what is actually inside `checkArgumentsAgainstSignature`

*Round 735. Fourth in the sequence `docs/perf/dispatch-table.md` (732, six hot
handlers) → `docs/perf/spine-leave-attribution.md` (733, the passes' own work) →
`docs/perf/call-expression-attribution.md` (734, the call function's type-system
share) → here. Derived by instrumentation (`ArgSections`, opt-in `--argSections`
/ `--argSectionsCoarse`), verified by the whole corpus suite, an identical
profile `--listAll` in all three modes, and a cost gate at +0.00% on all 20
counters. The instrumentation is behaviour-free when off.*

> **HEADLINE — THE PRIOR HOLDS, BY ~48×, AND THE MECHANISM IS NOT THE ONE THE
> PRIOR NAMED.** *(ROUND-759 CORRECTION: the `argType` row is **689 ms** on
> `5f787d79`, not 924 — the counts are unchanged to the unit but the
> milliseconds fell 25% while (AUDIT.2) sat in the queue. The 48× verdict is
> unaffected, since the relation section is 19 ms either way. And the § 3 box
> below now carries the exit-class split, which INVERTS what that section
> implied about the 73%.)* The item's falsifiable expectation was *"most of the 61 µs is
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

> **ROUND-759 MEASUREMENT OF THAT SENTENCE — IT IS TRUE, AND UNDERSTATED. THIS
> IS THE ARC'S ONE COUNTER-EXAMPLE TO § 0's LAW.** (AUDIT.2) split the `argType`
> row by exit class (`ArgSections.N_ARGTYPE_RELATING` / `_NONRELATING`, an exact
> partition, pinned). **The 72% that never reach the relation carry 89% of the
> argument-typing time, at 22,604 ns each against 7,134 ns for the 28% that
> do — 3.2× the wrong way.** Round 758 predicted "< 40%" and round 759 predicted
> 35%; both were wrong by ~2.5×, in the same direction, because both applied a
> law that does not hold here. **Mechanism, measured not residual:**
> `getTypeOfExpression` costs **8,252 ns** for a non-relating argument against
> **1,344 ns** for a relating one (**6.1×**), and **7,917 of the 9,674 narrowing
> walks (82%)** are non-relating. The reason is that the exit predicate is a
> property of the **parameter** (`isSimpleCheckableType`, foreign type
> parameters) while the cost is a property of the **argument** — and complex
> parameters attract complex arguments: the 15,140 that exit at the
> function-vs-function block are arrows and callbacks typed under an installed
> contextual type. **The prize still does not exist**, for an unrelated reason:
> *paying for `argType` is not wasting it* — eleven blocks below consume the
> value, and the assignability relation is the cheapest consumer in the function
> at 19 ms. Full derivation: `docs/perf/claim-audit-round758.md` § 10.
>
> **AND THE NUMBERS IN THIS SECTION ARE STALE BY 25%.** Round 759 re-measured on
> `5f787d79`: the counts are identical to the unit (22,419 / 38,247), but the
> `argType` row is **689 ms raw, not 924** (three runs: 748 / 656 / 689, a ±13%
> box), and the exit profile has shifted downstream of (REL.1) and rounds
> 736/738 — the foreign-TP exit **14,663 → 11,689**, the function-vs-function
> exit **12,280 → 15,140**, the relation **10,146 → 10,560**. Round 755's rule
> again: *an item defined by a measured number must re-measure it before a round
> is spent inside it.*

## 4. The attribution

Raw ms; percentages of the 1,624 ms raw partition.

*(Round-759 note: the `argType` row below is **689 ms** on `5f787d79`, and it
splits **75 ms / 613 ms** between arguments that reach the relation and those
that do not — see the § 3 box. The rest of this table has not been
re-measured.)*

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

---

# (CALL.5) round 796 — the EXIT CENSUS, and the lever it named

*Re-measured at HEAD (`160290ef`) before any code was written, per round 755's
rule and round 795's law 1. Every number in § 1–§ 4 above is from round 735/759
and is now stale; the table in § 11 below is the live one.*

> **HEADLINE.** The function is **~1,500 ms probe-free over 23,494 invocations
> and 39,593 loop iterations = ~5.4% of a check-only compile**, and the exit
> census says where that goes with a precision `leftIn` could not reach.
> **Every single invocation returns from `POST`** — the thirteen dedicated
> prologue walkers (103 ms net) fire ZERO times. **39% of the iterations leave
> at the `!isSimpleCheckableType` block carrying 69% of the argument-typing time
> and 81% of the flow narrowing.** And **narrowing is 33% of the whole
> function** (503 ms / 9,858 walks), of which 86% returns its input unchanged.
> The lever that follows is a pre-gate: **walk only when the un-narrowed type
> does NOT already satisfy the parameter** — refusing 8,905 of 9,823 walks
> (91%) for a measured **259 ms** (~0.96%), corpus- and grid-clean.

## 10. What was built, and why it costs nothing

`ArgSections` gained an **EXIT CENSUS**: `exitRow`, `exitInvRow`,
`exitArgTypeNanos`, `exitNarrowNanos`, `exitNarrowCalls`.

`leftIn(s)` — the pre-existing exit profile — is a DIFFERENCE of adjacent rows'
`calls`. That is sound only while every iteration crosses every boundary in
order: it cannot see the two `return`s inside the loop, it charges the
prologue's early returns to nothing, and above all it can say *where* an
iteration left but not *what that iteration had already paid for*. The census
answers both, because it records the **row that is already open** at boundaries
the partition was **already crossing** (`at(L_PARAM)` opens the next iteration,
`at(POST)` follows the last one, `end()` catches a `return`).

**It therefore adds NO boundary** — the ON run's boundary count is 448,473 with
it and without it. That is what keeps a before/after ROW comparison honest
(round 793: removing a section removes its boundaries too). Four totals are
printed as a partition check and all four read `EXACT`: iterations, the
`L_ARGTYPE` row's nanos, and the `N_NARROW` row's nanos *and* calls.

## 11. The live table (compiler profile, HEAD, `--argSections` raw)

| | ON | COARSE |
|---|---:|---:|
| partition | 1,621 ms raw / 448,473 boundaries | **1,506 ms** / 86,581 |

Δ = 115 ms over ~860,000 extra timestamp reads ⇒ **~133 ns per boundary**
(round 735's construction gave 89 ns; the two bracket the honest figure). The
probe-free function is therefore **~1,500 ms**.

| region | ms net | reached | left here |
|---|---:|---:|---:|
| the ten generic-gated prologue walkers | 55 | 23,494 | **0** |
| `tryInferSingleTypeParamFromArgs` + `instantiateSignature` | 124 | 23,494 | **0** |
| B199/B204/B219 single-TP walkers | 48 | 23,494 | **0** |
| loop: gates + `getTypeOfSymbol(param)` | 35 | 39,593 | 557 |
| **loop: `argType` computation** | **824** | 39,036 | 0 |
| — of which `getNarrowedTypeForReference` | **478** | **9,858** | |
| — of which `getTypeOfExpression(arg)` | 175 | 39,036 | |
| — of which the M3.4 refinement gate | 28 | 7,368 | |
| loop: foreign-TP + rest + weak target | 51 | 39,036 | **12,121** |
| loop: `tryEmitWeakTypeAssignment` | 82 | 26,915 | 0 |
| loop: arg-kind + class/index-sig block | 46 | 26,676 | 0 |
| loop: `!isSimpleCheckableType` (196 ln) | 87 | 26,676 | **15,637** |
| loop: `checkTypeRelatedTo` + TS2345 emit | 12 | 10,951 | 10,951 |
| post-loop rest-args check | 17 | 23,494 | |

## 12. The exit census — the two results `leftIn` could not give

**(a) Every one of the 23,494 INVOCATIONS returns from `POST`.** Not one of the
thirteen dedicated prologue walkers ever answers `true` on this profile. That is
round 793's shape (a firewall that never fires) at **103 ms net = 0.38%** — too
small to be worth a pre-gate on its own, and recorded so nobody re-derives it.

**(b) The argument-typing time follows the ITERATIONS, and it concentrates on
one exit.** Charging each iteration's `argType` and narrowing spans to the row
it left from:

| left at | iterations | argType | narrowing | walks |
|---|---:|---:|---:|---:|
| `L_PARAM` (arity/spread/any gates) | 557 | 0 ms | 0 ms | 0 |
| `L_PRE` (any/error + foreign-TP) | 12,121 | 194 ms | 53 ms | 14 |
| `L_TYPEPARAM` | 239 | 3 ms | 1 ms | 102 |
| **`L_NOTSIMPLE`** | **15,637** | **633 ms** | **409 ms** | **7,880** |
| `L_TAILGATE` | 88 | 0 ms | 0 ms | 37 |
| `L_RELATION` (reached the relation) | 10,951 | 79 ms | 38 ms | 1,825 |

**39% of the iterations carry 69% of the argument-typing time and 81% of the
narrowing, and they all leave at the same `continue`.** Round 759's
90/10 split (`argType` of args that never reach the relation) is reproduced and
LOCALISED: it is not spread over eleven exits, it is one.

## 13. The lever — and the debt it collects

Round 764 gave the ENUM narrowing arm a **second chance**: walk only when the
raw type does NOT already satisfy the parameter, *because a narrow can then only
turn a rejection into an acceptance and never the reverse*. Its comment then
declined to generalise and named its creditor: *"Deliberately enum-ONLY: the
Interface/`unknown`/`string`/`number` arms below are corpus-pinned (round
428b/429c/438) and walk unconditionally."* Round 783's rule — **a deliberate
exclusion is a debt with a named creditor; re-test it** — applies, and the debt
turns out to be payable.

The structural argument is that both remaining arms exist to SUPPRESS a TS2345
that the wide type would have caused (M3.4 substitutes only a `refined` type
that relates; B469 substitutes a sub-union). **Where the wide type already
relates there is no TS2345 to suppress**, so the corpus pins that motivated
those arms are all in the population the gate KEEPS.

**Refusal rates, measured (`--argNarrowCensus`):**

| arm | reached | refused | % |
|---|---:|---:|---:|
| B469 union argument | 2,455 | 1,916 | 78% |
| M3.4 iface/`unknown`/`string`/`number` | 7,368 | 6,989 | **94%** |
| **total** | **9,823** | **8,905** | **91%** |

## 14. Equivalence — measured, because the argument here is NOT airtight

The narrowed type is read by ~11 blocks below the arm (the weak-type rule's
shared-property test, the `!isSimpleCheckableType` shape classification, a
message's display), so "the relation verdict cannot move" does not by itself
license the change. The census therefore counts, over the OLD behaviour, how
many refusals **would have substituted a different type** — the exact set of
argument types that differ between the two binaries:

| profile | refusals that would substitute | kept-complement substitutions (control) |
|---|---:|---:|
| compiler | 787 (B469 401 + M3.4 386) | 578 |
| services | 1,134 (523 + 611) | 902 |
| harness | 1,138 (527 + 611) | 927 |

**That is not zero, and it is reported rather than smoothed.** What IS zero is
everything downstream: the 8-profile grid is identical set-for-set in BOTH
directions (46/46/46/46/46/46/46/94, 0 added and 0 removed), `--partitionCheck
2` is EQUIVALENT at 46, and the full corpus suite — the only instrument that
sees shapes tsc's own sources do not contain (round 792) — is unchanged at
13,435 / 0 / 3.

The control is FREE (round 790): the same counter over the complement the gate
never refuses reports 578 / 902 / 927 substitutions, so the zero above is not a
dead instrument and no deliberately bogus flag was needed.

## 15. The prize, measured the way round 794 measured its own

The `L_ARGTYPE` row has an **identical boundary count in both arms** (39,036),
so its difference needs no round-793 boundary correction. Both arms run **twice
on one binary** (round 795's law 3 — a single before/after pair cannot tell "the
work moved" from "this run was slow"):

| arm | run 1 | run 2 | mean |
|---|---:|---:|---:|
| gate ON | 601.7 ms | 637.7 ms | **619.7** |
| gate OFF (`--argNarrowGateOff`) | 910.6 ms | 847.8 ms | **879.2** |

**Δ = 259 ms**, against a within-arm spread of 36 / 63 ms — Δ is 4.1× the larger
spread. The whole-partition raw total gives Δ = 295 ms (1,429 vs 1,724) on a
boundary count differing by 8. So the honest figure is **259–295 ms = 0.95–1.1%
of a check-only compile.**

The narrowing sub-measure falls **503 → 148 ms** (9,858 → 953 walks), i.e. ~330
ms of walking removed against ~70 ms of gate relation calls — which is why the
row moves less than the walks do. Note the KEPT walks are the EXPENSIVE ones:
155 µs each against 51 µs before, because a reference whose wide type does not
satisfy its parameter is exactly the one with real flow facts to find.

**No wall-clock A/B was run, deliberately.** 0.95–1.1% is the warm protocol's
±1.0% band, not "well above" it; a verdict there would be noise wearing a sign
(round 788: a saving already compiled by C2 is a SMALLER fraction of the warm
run). The counters decide.

## 16. Round 788's law, checked at the counters

Skipping a CACHED computation moves work rather than deleting it, so the
question is which of the skipped callees memoize. Rebaselined in the landing
commit:

| counter | before | after | |
|---|---:|---:|---|
| `narrow.walks` | 26,340 | 17,851 | **−32.2%** |
| `typeNode.cacheable` | 183,843 | 171,681 | −6.6% |
| `typeNode.cacheHits` | 124,608 | 112,458 | −9.8% |
| `typeNode.bypassed` | 107,280 | 110,653 | **+3.1%** |
| `globals.lookups` | 774,370 | 758,673 | −2.0% |
| `globals.misses` | 757,968 | 742,183 | −2.1% |
| `typeOfExpr.calls` | 650,995 | 649,410 | −0.2% |
| every other counter | | | +0.00% |

`narrow.walks` −8,489 against 8,905 refusals: the 416 difference is refusals
whose walk would have returned at `getReferencePath`/`getFlowAt` before
`flowWalkWithTripCheck` counted anything.

**`typeNode.bypassed` rising 3.1% IS round 788's law, and it is small.** Total
type-node resolutions fall 291,123 → 282,334 (**−3.0%**); what rises is the
BYPASSED share, because a resolution that used to happen inside a narrowing walk
(in a context the round-548 gate calls cacheable) now happens later, in a
bypassed one. Work moved, but far less of it than was removed — which the 259 ms
row delta independently confirms. (That counter is also ~1% program-order
sensitive, so part of the +3.1% is not even attributable here.)

## 17. What did NOT work, and one thing that nearly wasted the round

* **The first fixture pinned nothing.** Its guards were `if (isIdent(n)) { … }`
  — and since round 785 a type-guard CALL in an `if` condition writes its narrow
  into `currentLocalTypes` for the THEN branch, so the argument arrives ALREADY
  narrowed, the flow read never happens, and the gate refuses trivially. The
  census read `refused == reached` on both arms with **zero** substitutions.
  **Every guard in a narrowing-at-an-argument fixture must be an EARLY RETURN**
  (or a ternary / `&&`), which is the same list CLAUDE.md gives for enums.
* **`Cat | Dog` → `Cat` emits nothing**, so it is useless as a negative control
  for the union arm; `Dog` → `Cat` was used instead.
* **A pre-existing probe pin died and was RESTATED, not deleted** — for the
  fourth round running. `ArgSectionProbeTest`'s "the narrowing sites agree with
  the combined row" asserted `perSite > 0`, and the gate refuses BOTH of that
  fixture's walks (its union parameter accepts the un-narrowed union, and its
  `if`-body argument is pre-narrowed by round 785). An early-return-guarded call
  was added to the fixture, so `> 0` now asserts something strictly stronger:
  that the gate has a live complement.
* **BUILD.1 bit again**: a `compileKotlinJvm` launched with an idle Kotlin
  daemon still holding its heap failed with *Not enough memory to run
  compilation* after **15m43s**, where the same compile takes **2m26s** once
  `./gradlew --stop` + a graceful `pkill` have run FIRST. Stop the daemons
  BEFORE the build, never after.

## 18. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt)
# the partition + the exit census
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSections build/bench/tsc-project-*
# the same with the gate disabled — the A arm of § 15
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSections --argNarrowGateOff build/bench/tsc-project-*
# the equivalence instrument: old behaviour + the verdict
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argNarrowCensus build/bench/tsc-project-*
```

---

# (CALL.6) round 797 — the LEVEL-S sub-partition: by ARGUMENT KIND, and into the arm chain

*Re-measured at HEAD (`ee3a3809`) before any code was written, per round 755's
rule and round 795's law 1. The § 11 table above is round 796's; § 20 below is
the live one.*

> **HEADLINE — ROUND 796's HYPOTHESIS ABOUT ITS OWN BIGGEST POPULATION IS
> FALSE, AND THE MISS IS 14×.** The 15,640 iterations that leave at the
> `!isSimpleCheckableType` `continue` were expected (round 759's sentence,
> repeated by round 796 and never measured) to be *"arrows and callbacks typed
> under an installed contextual type"*. They are **48% bare Identifiers and 32%
> PropertyAccesses**; arrows and function expressions together are **527 of
> 15,640 = 3.4% of the iterations and 3.2% of the argType time they carry**, and
> a CONTEXTUAL type is installed for **575 of all 39,036 iterations (1.5%)**.
> The item's literal question — split `getTypeOfExpression` by argument kind —
> answers the same way from the other side: **PropertyAccess + Call are 26.6% of
> the iterations and 73% of that row**, while Identifier + literal + keyword are
> **65% of the iterations and 7%**. **NO LEVER WAS FOUND** and none was landed:
> after the round-796 gate the whole row is ~600 ms (2.2% of a check-only
> compile) and its largest term is 200 ms of irreducible type resolution.

## 19. What was built

Two things, both inside `ArgSections`, both `ON`-only:

* **The KIND census** (`kindIters` / `kindCtx` / `kindArgType` / `kindGtoe` /
  `kindNarrow` / `kindNarrowCalls`, plus the `kindExitIters` / `kindExitArgType`
  **cross-tab against the exit row**). A 14-way classification of the argument
  node (`Checker.argSectionKindOf`), taken INSIDE the already-open `L_ARGTYPE`
  row. **Like round 796's exit census it adds no boundary** — every nanosecond
  it attributes is a span the partition was already timing, so a before/after
  row comparison stays valid (round 793). Five totals print as partition checks
  and all five read **EXACT**: iterations, the argType row, the
  `getTypeOfExpression` row, the narrowing row in nanos AND calls, and the
  cross-tab against `iterations`.
* **`N_ARM_CHAIN` and `N_GATE_REL`** — the whole narrowing arm chain as one
  sub-measure, and the (CALL.5)(b) pre-gate's own `checkTypeRelatedTo`. Before
  them the argType row's second-largest term was a RESIDUAL; § 0's history is
  what a named residual costs.

`true` / `false` / `null` / `undefined` / `this` are split out of `K_IDENT`
because Parser.kt's keyword arms all produce an `Identifier`: they are 938
iterations with no symbol to resolve and would otherwise dilute the identifier
row with the cheapest arguments in the compile.

## 20. HEAD re-measured first (law 1) — the item's numbers held, one moved

Two runs, compiler profile, `--argSections` at `ee3a3809`. **ms NET**, the same
column the item quoted (raw in brackets):

| | run 1 | run 2 | item said |
|---|---:|---:|---|
| partition | 1,254 [1,443] | 1,240 [1,432] | ~1,240 |
| `L_ARGTYPE` | 598 [614] | 601 [617] | ~600 |
| — `getTypeOfExpression` | 183 [200] | 208 [225] | 175 |
| — narrowing | 146 [147] | 139 [140] | 148, 953 walks (953 here) |
| `INFER` | 135 | 130 | 124 |
| the 13 prologue walkers | 101 | 106 | 103 |
| `L_NOTSIMPLE` | 103 | 93 | 87 |
| `L_WEAK` | 84 | 76 | 82 |

Invocations **23,494** and iterations **39,593** reproduce to the unit, as do
every exit count (`L_NOTSIMPLE` 15,640 vs 15,637 — three iterations of drift
across two builds).

## 21. The composition — what the 39,036 typed arguments ARE

`--argSections`, run 2 (the quieter run; run 1 in brackets where it differs
materially). Raw ms.

| kind | iterations | ctx installed | argType | of which gToExpr | narrow | walks |
|---|---:|---:|---:|---:|---:|---:|
| Identifier | 18,165 (46.5%) | 0 | **367** (54%) | 14 | 131 | 831 |
| PropertyAccess | 7,774 (19.9%) | 0 | 151 | **87** | 3 | 118 |
| literal (string/number/template) | 7,272 (18.6%) | 0 | 23 | 1 | 0 | 0 |
| Call / New | 2,612 (6.7%) | 0 | 76 | **60** | 0 | 0 |
| operator (binary/cond/unary) | 1,017 | 0 | 22 | 17 | 0 | 0 |
| true/false/null/undefined/this | 938 | 0 | 4 | 0 | 0 | 3 |
| **ArrowFunction** | **546 (1.4%)** | **532** | **16** | 14 | 0 | 0 |
| as / `!` / `(…)` / satisfies | 350 | 0 | 4 | 1 | 0 | 0 |
| ElementAccess | 160 | 0 | 2 | 0 | 0 | 1 |
| ArrayLiteral | 112 | 0 | 2 | 2 | 0 | 0 |
| ObjectLiteral | 88 | 41 | 4 | 3 | 0 | 0 |
| FunctionExpression | 2 | 2 | 0 | 0 | 0 | 0 |
| **total** | **39,036** | **575** | **675** [754] | **204** [250] | **135** [166] | **953** |

**`getTypeOfExpression` by kind is the item's question, answered:**
PropertyAccess **87 ms / 7,774 = 11.2 µs** and Call **60 ms / 2,612 = 23.0 µs**
are **72% of the row over 26.6% of the iterations**, while Identifier is
**14 ms / 18,165 = 0.8 µs** — a bare name costs a map read, a composite
expression costs a resolution. Round 758's population-vs-frequency law, with
the ratio running the other way from the usual: here the *rare* kinds are the
expensive ones, which is exactly why a partition and not a count was required.

## 22. KIND × EXIT — the falsification

Iterations / argType ms charged to the row the iteration left from (run 1):

| kind | `L_PRE` (any/error, foreign-TP) | `L_NOTSIMPLE` | `L_RELATION` | `L_TYPEPARAM` | `L_TAILGATE` |
|---|---|---|---|---|---|
| Identifier | **8,574 / 99 ms** | **7,575 / 270 ms** | 1,827 / 26 ms | 147 / 3 | 42 / 0 |
| PropertyAccess | 1,968 / 18 ms | **5,032 / 171 ms** | 749 / 11 ms | 24 / 0 | 1 / 0 |
| literal | — | 74 / 0 ms | **7,185 / 22 ms** | 13 / 0 | — |
| Call / New | 1,176 / 49 ms | 1,060 / 12 ms | 362 / 3 ms | 14 / 0 | — |
| operator | 175 / 2 ms | 545 / 18 ms | 291 / 3 ms | 5 / 0 | 1 / 0 |
| keyword | — | 410 / 2 ms | 469 / 5 ms | 13 / 0 | 46 / 0 |
| **ArrowFunction** | — | **525 / 16 ms** | 21 / 3 ms | — | — |
| as/`!`/`(…)` | 75 / 0 | 237 / 3 ms | 33 / 0 | 5 / 0 | — |
| ObjectLiteral | 2 / 0 | 73 / 3 ms | — | 13 / 0 | — |
| ArrayLiteral | 25 / 0 | 87 / 2 ms | — | — | — |
| ElementAccess | 126 / 1 ms | 20 / 0 | 9 / 0 | 5 / 0 | — |
| FunctionExpression | — | 2 / 0 | — | — | — |

**Three results.**

**(a) The `L_NOTSIMPLE` population is not what it was said to be.** Identifier
7,575 + PropertyAccess 5,032 = **80% of its 15,640 iterations and 89% of its
497 ms**; arrows + function expressions are **527 iterations (3.4%) and 16 ms
(3.2%)**. The prior was not merely imprecise, it named a population 14× too
small — and it was inherited unchallenged from round 759 through round 796
because *a `!isSimpleCheckableType` parameter* sounds like *a callback*. It is
not: it is any parameter that is not a simple checkable type, which most
INTERFACE parameters are, and their arguments are ordinary names.

**(b) The check the function exists for runs mostly on trivia.** Of the 10,946
iterations that reach the assignability relation, **7,185 (66%) are literals**,
costing 22 ms of argType between them. That is why the TS2345 relation call is
5–7 ms over 10,946 (round 796 measured 12 ms for the whole row): it is mostly
`isSimpleTypeRelatedTo` on a literal, not a structural comparison.

**(c) 22% of all iterations type an identifier to `any` and throw it away.**
8,574 identifiers (plus 1,968 property accesses and 1,176 calls) leave at
`argType === anyType || errorType`. Nothing can know that without typing them,
so it is not a lever — but it is the largest single "paid for, discarded"
population in the function, and it is a modelling signal, not a perf one.

## 23. The argType row, closed to a named residue

Run 2, raw, with the two new sub-measures live (which is why the row reads 675
rather than HEAD's 617 — see § 24):

| term | ms | reached | each |
|---|---:|---:|---:|
| `getTypeOfExpression(arg)` | 204 | 39,036 | 5.2 µs |
| the narrowing ARM CHAIN | **320** | 39,036 | 8.2 µs |
| — of which the **(CALL.5)(b) pre-gate relation** | **131** | **9,823** | **13.3 µs** |
| — of which `getNarrowedTypeForReference` | 135 | 953 | 141 µs |
| — of which the M3.4 refinement relation | 10 | 379 | 28 µs |
| — chain residue | 44 | | 1.1 µs |
| `literalTypeOfExpression` + `propTypeContainsLiteral` | 24 | 39,036 | 0.6 µs |
| row residue | 127 | 39,036 | 3.3 µs |

**The one number that changes the map: round 796 priced its own gate at "~70 ms
of gate relation calls" by subtraction. Measured directly it is 131–137 ms** —
9,823 calls at 13.3 µs, because the sources it compares are tsc's big
interfaces. That does not overturn the gate (it removes ~390 ms of walking for
that 133), but it halves the headroom anyone would have assumed was there, and
it is now a measurement rather than a residual.

**The row residue is the probe, quantitatively.** Everything unbracketed inside
the row is four `is` tests, `stripNullishForNonNullArg` (an immediate return
unless the type is a union), `voidIifeArgType` (an immediate return unless the
argument is a call) and a `finally`. The row carries **7 nested timestamp reads
per iteration**, and the harness's own in-situ empty-span calibration reads
**423–429 ns** — 7 × 423 = **2.96 µs against the measured 3.3 µs**. The residue
is not code.

## 24. Why no lever, stated as a bound

After (CALL.5)(b) the row is ~600 ms probe-free = **2.2% of a check-only
compile**, and it decomposes into four things, none of which is above half a
noise band (±2% ≈ 550 ms):

* **~200 ms `getTypeOfExpression`**, 73% of it resolving property accesses and
  calls. Round 737 already closed the recompute direction program-wide (×2.76 =
  2.05 cross-handler × 1.34 recursion, largest actionable merge 166 ms), and
  this function types each argument exactly once.
* **~135 ms of narrowing over 953 walks at 141 µs.** This is round 735's
  monster tail after round 796 removed 91% of the walks: what remains is
  precisely the population with real flow facts to find, and round 736 already
  closed the memo failure behind it.
* **~131 ms the gate's own relation**, which buys the ~390 ms above. Its
  refusal population cannot be reached more cheaply: `checkTypeRelatedToCore`
  already short-circuits on `source === target`, so the 13.3 µs is genuine
  structural work; and round 796 measured that only 416 of 8,905 refusals would
  have bailed at `getReferencePath`/`getFlowAt`, so a "does this reference have
  flow facts at all" pre-refutation reaches 4.7% of it.
* **~24 ms of literal preservation** and a residue that is the probe.

**Both remaining arms stay gated**: priced separately, B469 pays 33 ms of gate
to avoid ~90 ms of walking and M3.4 pays 99 ms to avoid ~286 ms. Neither
inverts.

## 25. Verification

* Full corpus suite: **13,441 tests, 0 failures, 3 skipped** (13,435 + 6 new
  `ArgKindCensusTest` pins).
* Compiler profile `--listAll`: production, `--argSections` and
  `--argSectionsCoarse` are **identical** (46 errors, identical sorted lines).
* `--partitionCheck 2`: **EQUIVALENT — 46**.
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**, exit 0, no
  rebaseline — the change is probe-only (`argSectionKindOf` is unreachable with
  `ArgSections.mode == OFF`; the single production edit names the arm chain's
  value).
* **No 8-profile grid and no wall-clock A/B were run, deliberately.** Nothing
  was landed that production executes, so the grid's question is answered more
  sharply by the cost gate's 20 counters at +0.00% on the profile plus 13,441
  unchanged corpus baselines; and there is no prize to A/B.
* **Ablations, run separately.** Fault A (the kind hook records nothing) fails
  **3** of the 6 pins; fault B (the arm-chain bracket opened at its own close,
  i.e. measuring the wrong span) fails **1**, disjoint from A's. The two pins
  neither fault reaches are the index-alignment pin and the records-nothing-when-
  off pin, both structural — reported rather than papered over.

## 26. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSections build/bench/tsc-project-*
# the level-S tables are the two "(CALL.6) LEVEL-S" blocks of the report
```
