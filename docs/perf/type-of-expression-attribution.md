# (TYPE.1) — `getTypeOfExpression` attributed BY CALLER, and the co-occurrence

*Round 737. Fifth in the sequence `docs/perf/dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736, the
arc's first landed win) → here. Derived by instrumentation (`--typeOfExprCallers`,
opt-in, behaviour-free when off), verified by the whole corpus suite, a
byte-identical profile `--listAll` in production and attribution modes, and a
cost gate at +0.00% on all 20 deterministic counters.*

> **HEADLINE — § 0.1 STAGE 3's MECHANISM IS EXACTLY RIGHT AND ITS SIZE IS WRONG
> BY 3.2×.** The claim was *"the factor exists because several handlers
> independently type the same node; single-visit discipline is what removes
> it"*, priced at *up to ~9%*. **The mechanism is confirmed and it is
> pervasive**: 177 distinct call sites initiate expression typing, **45.2% of
> all typed nodes (114,750 of 254,069) are typed by more than one of them**, and
> the ×2.76 factor decomposes as **2.05× cross-handler × 1.34× recursion** — so
> handler co-occurrence really is the dominant term, and no single handler
> re-types anything (per-caller factors are 1.00–1.11 almost everywhere).
> **The money is not there.** A PERFECT per-node type cache — the unreachable
> ceiling for stage 3 in ANY shape — saves **823 ms, 2.9% of a 28.7 s compile**;
> the realizable single-visit form saves **670 ms (2.3%)**; and the largest
> single handler-pair merge is **166 ms (0.58%)** against a ±2% band of ~590 ms.
> The reason is § 0's law once more, in its sharpest instance yet: **the four
> biggest co-occurrence pairs by COUNT are 141,388 repeat typings costing 71 ms
> (0.5 µs each), while the biggest by TIME is 2,603 typings costing 166 ms
> (64 µs each).** The redundantly-typed nodes are the cheap ones.
> **NO OPTIMISATION WAS LANDED** — everything measured is inside or barely at
> the drift band. Stage 3 should be struck from the staged plan.
>
> Two corrections fall out. **(1) The often-quoted "`getTypeOfExpression` =
> 3,911 ms" is a DOUBLE COUNT**: `typeOfExprNanos` sums every call's span
> including nested ones, so a subtree is charged once per level. The true total
> cost of all expression typing is **2,439 ms — 8.5% of checker-init**, which
> caps stage 3 at 8.5% even if typing were free. **(2) 74.4% of the calls are
> OUTERMOST**, not nested; the prior that recursion explains the factor is false.

---

## 1. What was built

`--typeOfExprCallers` (implies `--passTiming`), plus `PassTiming.callerAttr` and
a new multiplatform primitive `captureCallerFrames` (JVM `StackWalker`; native
returns `""`, interned as one `(unattributed)` site).

Inside `getTypeOfExpression`, already inside the `PassTiming.enabled` guard:

* **Only the OUTERMOST call walks the stack.** A `typeOfExprDepth` counter
  makes nested calls inherit the caller's site id, so a whole expression
  subtree is attributed to **the handler that asked for it** and the recursion
  can never inflate a caller's own factor. This is the difference between a
  measurement of *who initiates typing* and a measurement of *which type-system
  function called which*, and only the first can test the stage-3 claim.
* **A node key that is program-unique**: `nodeId` is only per-FILE preorder and
  `pos`/`end` are per-file offsets, so all three are mixed. The report prints a
  file-salted distinct count beside the unsalted one — **254,069 vs 272,124,
  a 7.1% gap** — which bounds the residual (cross-file collisions plus nodes
  typed while a different file is being checked) instead of assuming it away.
* **Two prize meters, deliberately different**:
  * `redundantOutermost` — a node typed at TOP LEVEL that had already been
    typed at top level. This is what single-visit discipline removes: two
    handlers sharing one computation.
  * `perfectMemo` — every call on a node already typed at ANY depth, each
    served subtree counted once via a guard. This is what a perfect
    `NodeLinks.resolvedType` would save, ignoring soundness and every ambient
    context. **Nothing that removes recomputation can beat it**, which is what
    makes it the right number to close the item with.

Pinned by `TypeOfExprCallerAttributionTest` (6 tests): behaviour-free when on,
nothing recorded when off, the depth discipline (`outermost < calls`, per-node
sums equal the site sums), the redundancy identity
(`redundant == outermost − everOutermostNodes`) and the ceiling's ordering
(`redundantOutermost ≤ perfectMemo < calls`).

## 2. Calibration — and why this round is deliberately COUNT-heavy

| run | checker-init |
|---|---:|
| `--passTiming` | 28,694 ms |
| `--typeOfExprCallers` | 37,429 ms |

**Δ = 8,735 ms over 522,102 stack walks = ~16.7 µs each**, dominated by
`StackWalker`. That is enormous next to round 735's 89 ns timestamp read, and
it is why the decisive numbers here are **counts**: all 20 deterministic
counters are byte-identical between the two runs and to `cost-counters.txt`
(`+0.00%`), so the call, distinct-node, outermost and co-occurrence figures are
exact and load-independent.

The times are RELATIVE. An outermost span excludes its own stack walk (taken
before the clock starts) but includes the ~179k nested calls' map writes,
≈540 ns each ⇒ **~97 ms of the 2,439 ms (4%)**. Read every per-site and
per-pair millisecond as attribution within that total, not as a wall-clock
price.

## 3. The decomposition — where ×2.76 actually comes from

| | count | ratio |
|---|---:|---:|
| calls | 701,463 | |
| **outermost** (a fresh entry from a handler) | **522,102 (74.4%)** | |
| nested (inherited origin) | 179,361 (25.6%) | |
| **distinct nodes** | **254,069** | |
| calls / distinct | | **2.76** |
| — **cross-handler term** (outermost / distinct) | | **2.05** |
| — recursion term (calls / outermost) | | 1.34 |

2.05 × 1.34 = 2.76. **The stage-3 mechanism is the dominant factor and the
recursion is the minor one** — the opposite of the prior stated before this
measurement, which expected >50% of calls to be nested.

## 4. By caller — 177 sites, and a long tail

Compiler profile. `factor` = calls / distinct for that origin. Line numbers are
raw `StackWalker` values: **they wrap modulo 65536 for `Checker.kt`** (the JVM
`LineNumberTable` is a `u2` and the file is 171,760 lines), so
`checkMemberAccessMissing$default:16` is really line 65,552 — the METHOD names
are the reliable identity.

| calls | outer | distinct | factor | incl.ms | origin |
|---:|---:|---:|---:|---:|---|
| 71,998 | 57,027 | 38,749 | 1.85 | 73 | `calleeParamGivesNoContext` ← `spineIanyEdgeEnter` |
| 68,123 | 63,347 | 63,760 | 1.06 | 51 | `checkMemberAccessMissing:141` |
| 66,438 | 62,182 | 62,813 | 1.05 | 41 | `checkMemberAccessMissing:805` |
| 57,997 | 37,379 | 56,968 | 1.01 | **307** | `checkArgumentsAgainstSignatureCore` |
| 54,346 | 54,346 | 54,346 | 1.00 | 53 | `emitTs18048ForClosureCapturedUndefinedReceiver` ← `checkSinglePropertyAccess` |
| 33,653 | 11,933 | 31,975 | 1.05 | **431** | `checkVarDeclAssignability:29166` ← `ctaM3StmtAnchor` |
| 30,595 | 30,595 | 555 | **55.12** | 6 | `getTypeOfObjectLiteral:44938` ← `ccetObjlitMemberFrame` |
| 25,388 | 20,986 | 12,915 | 1.96 | 26 | `computeRawTypeOfPropertyAccess` ← `getTypeOfPropertyAccess` |
| 23,613 | 9,639 | 22,710 | 1.03 | **295** | `checkReturnAssignability` |
| 16,251 | 8,671 | 16,053 | 1.01 | 51 | `arithOperandType` ← `checkBinaryOperatorTypes:32444` |
| 14,324 | 8,671 | 13,244 | 1.08 | 40 | `arithOperandType` ← `checkBinaryOperatorTypes:32443` |
| 13,747 | 8,203 | 13,732 | 1.00 | 37 | `checkEqualityComparisonNoOverlap:13636` |
| 13,479 | 6,029 | 12,904 | 1.04 | 128 | `collectUncalledTypedLocalsFromBody` ← `spineUncalledFnLevel` |
| 13,176 | 10,933 | 12,847 | 1.02 | 27 | `tryEmitDependentIndexedConstraintTs2345` |
| 12,878 | 8,203 | 12,869 | 1.00 | 31 | `checkEqualityComparisonNoOverlap:13637` |
| 11,739 | 6,999 | 10,556 | 1.11 | 55 | `cpaApplyDeclRecordings` ← `cpaSpineLeave` |
| 11,273 | 5,884 | 6,653 | 1.69 | 48 | `resolveCallOverload` ← `cpaComputeArgCtxTypes` |
| 10,476 | 8,409 | 10,418 | 1.00 | 53 | `emitPromiseConditionHelper` ← `walkUncalledChain` |
| 9,931 | 4,026 | 9,316 | 1.06 | 41 | `collectUncalledTypedLocalsFromBody` ← `spineUncalledLevelFor` |
| 5,473 | 774 | 5,369 | 1.01 | 101 | `tryEmitNestedWeakVarDecl` ← `checkVarDeclAssignability` |

Concentration:

| | calls | outermost | ms |
|---|---:|---:|---:|
| top 1 | 10.3% | 12.1% | 18.0% |
| top 3 | 29.4% | 35.0% | 43.2% |
| top 5 | 45.5% | 52.5% | 54.6% |
| top 20 | 81.5% | 84.6% | 87.5% |
| top 40 | 96.2% | 96.8% | 98.3% |

**Two readings that matter.**

1. **Every per-caller factor is ~1.** With one class of exception (below), a
   handler types each node it wants exactly once. So the ×2.05 cross-handler
   term is genuinely *between* handlers — there is nothing for a handler to fix
   on its own.
2. **The three biggest origins BY TIME are the three assignability checks** —
   `checkVarDeclAssignability` 431 ms over 11,933 top-level typings (36 µs
   each), `checkArgumentsAgainstSignature` 307 ms, `checkReturnAssignability`
   295 ms — and their factors are 1.05 / 1.01 / 1.03. **They are expensive, not
   redundant.** Round 735 already opened the middle one and found 37% of its
   argument typing is flow narrowing; the other two have never been attributed.

The factor outliers are worth naming because they look like a lever and are
not: `getTypeOfObjectLiteral` ← `ccetObjlitMemberFrame` types **555 distinct
nodes 30,595 times (×55)** for **6 ms** — object-literal freshness is
semantically load-bearing (the `freshObjLitRange` machinery), so those calls
must mint, and they are 0.2 µs each.

## 5. The co-occurrence — the number the item was written for

| origins typing one node | nodes |
|---:|---:|
| 1 | 139,319 |
| 2 | 27,514 |
| **3** | **35,173** |
| 4 | 25,865 |
| 5 | 7,279 |
| 6–9 | 15,344 |
| 10–17 | 3,575 |

**114,750 of 254,069 nodes (45.2%) are typed by more than one handler, and
531,865 of 701,463 calls (75.8%) land on them.** The modal multi-origin node has
**three** handlers on it.

The identifiable groups, largest first by node count:

* **The property-access receiver trio.**
  `emitTs18048ForClosureCapturedUndefinedReceiver` (via
  `checkSinglePropertyAccess`) types 54,346 receivers, and
  `checkMemberAccessMissing` types ~63,000 from two of its own call sites; the
  pair table shows **42,448 + 41,767 + 6,733 = 90,948 repeat typings** between
  them. **Cost: 51 ms.**
* **`getTypeOfObjectLiteral` ← `ccetObjlitMemberFrame` re-entering itself** —
  30,040 repeats, **6 ms**.
* **`calleeParamGivesNoContext` ← `spineIanyEdgeEnter` re-entering itself** —
  27,133 repeats, **23 ms**.
* **`isCalleeResolvable` ← `spineIanyEnterNode` against the call-checking
  cluster** (`computeRawTypeOfPropertyAccess`,
  `tryEmitDependentIndexedConstraintTs2345`, `checkMemberAccessMissing`,
  `resolveFlowCalleeDecl`, `checkSingleCallExpressionTypes`) — 14,440 + 7,648 +
  7,034 + 7,010 + 6,998 + 5,834 ≈ 49,000 repeats, **≈57 ms**.
* **The var-decl cluster** — `collectUncalledTypedLocalsFromBody`,
  `tryEmitNestedWeakVarDecl`, `cpaApplyDeclRecordings` and
  `checkVarDeclAssignability` typing the same initializers: 2,603 + 3,086 +
  4,694 + 980 + 755 ≈ 12,100 repeats, **≈326 ms**. *This one is 1.9% of the
  repeats and 49% of the redundant time.*

## 6. The prize — three numbers, all inside or at the band

| | count | ms | share of the 2,439 ms | share of a 28.7 s compile |
|---|---:|---:|---:|---:|
| **PERFECT per-node cache** (ceiling for ANY stage-3 shape) | 362,726 served roots | **823** | 33.7% | **2.9%** |
| **single-visit discipline** (repeat OUTERMOST typings) | 290,542 | **670** | 27.5% | **2.3%** |
| the LIVE-memo-servable population (round 665's whitelist, re-measured) | 71,252 | 46 | 1.9% | 0.16% |

Drift band, re-derived after round 736's speed-up: ±2% of ~29.5 s ≈ **±590 ms**.

The head of the redundant 670 ms:

| | ms | % of 670 |
|---|---:|---:|
| top-1 pair | 166 | 25% |
| top-3 | 274 | 41% |
| top-5 | 328 | 49% |
| top-10 | 428 | 64% |

| ms | repeats | µs each | first origin → repeat origin |
|---:|---:|---:|---|
| **166** | 2,603 | **63.8** | `collectUncalledTypedLocalsFromBody` → `checkVarDeclAssignability` |
| 70 | 755 | 92.7 | `tryEmitNestedWeakVarDecl` → `checkVarDeclAssignability:29494` |
| 38 | 3,086 | 12.3 | `checkVarDeclAssignability` → `collectUncalledTypedLocalsFromBody` |
| 31 | 4,694 | 6.6 | `checkVarDeclAssignability` → `cpaApplyDeclRecordings` |
| 23 | 27,133 | **0.8** | `calleeParamGivesNoContext` → itself |
| 22 | 42,448 | **0.5** | `emitTs18048For…Receiver` → `checkMemberAccessMissing:141` |
| 21 | 980 | 21.4 | `collectUncalledTypedLocalsFromBody` → `checkVarDeclAssignability` |
| 20 | 41,767 | **0.5** | `emitTs18048For…Receiver` → `checkMemberAccessMissing:805` |

**This table is the round's real product.** Sorted by count the head is
141,388 repeats worth 71 ms; sorted by time it is 2,603 repeats worth 166 ms.
The two orderings share nothing. Of the printed top-40 pairs, **cross-origin
repeats are 547 ms over 188,068 typings and same-origin repeats 68 ms over
61,215** — so the redundancy really is between handlers, and it really is cheap.

**The largest actionable merge in the compiler is 166 ms = 0.58%.** It is also
the least mergeable: `collectUncalledTypedLocalsFromBody` types an initializer
while collecting uncalled-function locals and `checkVarDeclAssignability` types
it inside the M3 statement anchor, under different `currentFlowGraph` /
`currentLocalTypes` installs — which is exactly why the second costs 64 µs
where the first averages 21 µs, and exactly why a memo across them was
unsound in round 596.

## 7. The prediction, scored

Stated in full before the run.

| | prediction | measured | |
|---|---|---|---|
| P1 | >50% of calls NESTED — recursion explains the factor | **25.6% nested**; the cross-handler term is 2.05 of the 2.76 | **FALSIFIED** |
| P2 | single-visit prize 0.5–2.0 s, below § 0.1's ~9% | **670 ms (2.3%)**; ceiling 823 ms (2.9%) | **HELD** |
| P3 | top-3 pairs < 50% of the redundant time; no pair above band | top-3 = **41%**; largest pair **166 ms** vs a 590 ms band | **HELD** |
| P4 | top-5 origins ≥ 60% of outermost calls | **52.5%** (54.6% of the time) | **FALSIFIED** |
| falsifier | any single pair > 600 ms ⇒ attempt the merge | largest is 166 ms | **not met** |

Two of four wrong again — the same hit rate as rounds 732–735. The one that
matters is P1: the reason to distrust stage 3 turned out to be the opposite of
the reason I expected, and the mechanism § 0.1 named was right all along.

## 8. What did NOT work / was priced and rejected

* **A per-node type cache of any shape.** Ceiling 823 ms, and that ceiling
  assumes an unsound cache that ignores `currentFlowGraph`, `currentLocalTypes`,
  `currentTypeParamScope` and the object-literal freshness rule. The SOUND
  version is measured in the same run at **46 ms** (round 665's whitelist +
  confirm-once). This is the fifth independent confirmation of § 0's law and the
  second one where the law shows up without a cache being involved at all.
* **Merging the property-access receiver trio** (the biggest co-occurrence group
  in the compiler, 90,948 repeat typings): **51 ms**. Two of the three sites are
  in `checkMemberAccessMissing` itself.
* **Merging the var-decl cluster** (the most expensive group): ≈326 ms, but
  spread over five ordered pairs whose members type under different ambient
  installs. Even a perfect merge of all five is 1.1% and the largest single one
  is 0.58%.
* **Anything keyed on the ×2.7 headline.** It is 2.05 × 1.34, and it is measured
  against a `typeOfExprNanos` total that double-counts nesting. Both halves of
  the stage-3 estimate were inflated.

## 9. What this means for § 0.1's staged plan

**Strike stage 3.** Its mechanism is real and its ceiling is 2.9% — reachable
only by an unsound cache, with the sound residue at 0.16%. The staged plan's
remaining live entries are stage 4 (flow narrowing, part-landed by round 736,
follow-on (CALL.4) priced at ~2.4% total) and stage 5 (the unprofiled front
end, 20%).

**What this measurement DOES hand forward** is the first ranked list of the
checker's expression-CONSUMING handlers by their typing cost, and it points
somewhere that has never been attributed: **`checkVarDeclAssignability` under
`spineCtaM3StatementAnchor`**. It is the largest single typing origin
(431 ms of typing over 11,933 top-level initializers = 36 µs each), its enclosing
handler was measured at **2,900 ms** in round 732 — the third-largest handler on
the spine and still unopened — and its typing is only 15% of that. Round 735's
method applies verbatim: split the function, price the sections, and expect the
answer to be somewhere other than where the aggregate points.

## 10. Verification

* Full corpus suite: **12,916 tests, 0 failures, 3 skipped** (12,910 + 6 new
  `TypeOfExprCallerAttributionTest` pins).
* Compiler profile `--listAll`: production and `--typeOfExprCallers` produce
  **byte-identical** diagnostics (46 errors, identical sorted lines).
* `scripts/cost_gate.py`: all 20 deterministic counters **+0.00%**.
* The attribution run's own counters (`701463 calls / 251853 distinct /
  83204 bypassed / 1262583 globals lookups`) match the plain `--passTiming` run
  and `cost-counters.txt` exactly.

## 11. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the attribution (grep '(TYPE.1)')
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --typeOfExprCallers build/bench/tsc-project-*
# the calibration counterpart — subtract checker-init totals, divide by the
# outermost count the attribution run reports
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming build/bench/tsc-project-*
```
