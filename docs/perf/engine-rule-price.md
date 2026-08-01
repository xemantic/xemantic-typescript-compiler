# What the dedicated-walker architecture actually costs — first pricing

*Round 739, queue item (ENGINE.1). No new measurement: this is a re-derivation from
round 738's own published partition (`docs/perf/var-decl-attribution.md` § 4), plus a
static census. It is here because the number the arc was about to carry forward —
**14×** — answers a question nobody is deciding.*

---

## 0. The claim under test

> "The var-decl path's FP-firewall prologue is **265 ms** against the **19 ms**
> assignability relation it exists to correct — the first price tag on § 0.1's
> *endgame* paragraph." — round 738, (TYPE.2)

The queue item that followed said: measure two more sites before believing the 14×.
**Before spending a round on that, the ratio was checked against its own source
data — and it does not survive, on the site it was measured on.**

## 1. The denominator is the wrong quantity

265 / 19 = 14× compares the FP-firewall walkers against **the final relation call
alone**. A general rule engine does not consist of the relation call. It must still:
resolve the target type node, compute the source type, infer the type of an
unannotated initializer, and narrow. Those are in the same partition, in the same
function, already measured.

Re-classifying round 738's level-B rows for `checkVarDeclAssignability`
(15,116 invocations, **872 ms**) by *what a general rule engine would also have to
do*:

| class | rows | ms | share |
|---|---|---:|---:|
| **engine work** — work any rule engine must also do | unannotated-init inference 405, SOURCE type computation 57, `canUseTypeEngine`+RELATION 19, target `getTypeFromTypeNode` 1, flow narrowing 1 | **483** | **55.4%** |
| **dedicated-walker layer** — what the scope change deletes | prologue 1 (50), prologue weak (165), prologue 2 (23), prologue 3 (27), clodule/B96/B231 (6), foreign-TP/B112/B207 (4), post-relation walkers ~30 (51) | **326** | **37.4%** |
| bookkeeping / dispatch | ObjectBindingPattern branch 31, varTypes recording 22, tail 1 | 54 | 6.2% |

(863 of 872 ms; the residue is rounding and rows measured at 0.)

**Same site, same data, honest denominator: the dedicated-walker layer is 0.67× the
engine work — not 14× it.** The 14× exists only because the relation call is 2.2% of
the function it lives in. It is a true ratio between two quantities, neither of which
is the one a scope decision turns on.

## 2. The quantity a decision turns on, and its value here

For the owner the question is not a ratio, it is: **how much compile time would
deleting the dedicated-walker layer return, and what does it cost in correctness?**

On this site the whole layer is **326 ms of a 26,896 ms check-only compile = 1.21%**.

And not all of it is deletable, which cuts it further:

* **The weak-type rule alone is 165 ms — half the layer — and it is real TypeScript
  semantics.** tsc implements it *inside* `checkTypeRelatedTo`; we implement it as
  dedicated walkers because "a weak target passes our relation vacuously"
  (CLAUDE.md). A general engine still has to have it. It would move, not vanish.
* The same is true, to an unknown degree, of the variance, clodule and foreign-TP
  groups: each encodes a rule tsc also has.

**So the honest range for this site is ~161–326 ms = 0.6–1.2% of the compile**, and
the upper end is only reached if the replacement engine implements those semantics
for free — which no engine does.

## 3. Static census — the scale of the thing being proposed

| | count |
|---|---:|
| `fun check[A-Z]…` in `Checker.kt` | 805 |
| `private fun emit[A-Z]…` | 181 |
| `private fun tryEmit…` | 60 (127 call sites) |
| `Checker.kt` | 171,934 lines |

The "~1,005 `check*` functions" of § 0.1's endgame paragraph is the right order
(805 + 181 + 60 = 1,046).

**But the naming convention does not identify the firewall, and this matters for the
next round's method.** Of the three assignability sites:

| site | lines | `tryEmit` calls | `checkTypeRelatedTo` calls | `canUseTypeEngine` |
|---|---:|---:|---:|---:|
| `checkVarDeclAssignabilityCore` | 1,504 | 15 | 11 | 1 |
| `checkReturnAssignability` | 802 | **0** | 14 | 5 |
| `checkAssignmentExpression` | 1,427 | 11 | 10 | 3 |

`checkReturnAssignability` has **no `tryEmit*` calls at all** — its FP firewall is
written as inline `if (…) return` guards (`aliasUnionContainsNullishKeyword`,
`returnUnionSyntacticallyContainsLiteral`, the QualifiedName-suggestion guard,
`arrayLiteralSatisfiesTupleTarget`). **A grep-based census of the firewall would have
scored that site at zero.** Sites 2 and 3 therefore need a real intra-function
partition, exactly as rounds 735/738 did — there is no cheap substitute.

## 4. What sites 2 and 3 must do differently

1. **Classify rows into engine / dedicated-walker / bookkeeping**, and report the
   dedicated-walker layer as **ms and as a share of the compile** — not as a ratio
   against the relation call. A ratio against a 2%-of-function denominator is not a
   decision input.
2. **Split the dedicated-walker layer into "re-implements a rule tsc also has"
   (moves) and "corrects our own relation" (deletes).** Only the second is a saving.
3. Sum the three sites and state the total as a share of the compile. That, plus the
   correctness cost, is what goes to the owner.

**Falsifiable expectation for the next round, stated now so it can be scored:**

| | prediction |
|---|---|
| E1 | Both sites show a dedicated-walker layer of **25–50%** of their own function — the same band as site 1's 37.4% |
| E2 | Both show a firewall/relation ratio **≥ 10×** — i.e. the 14× "survives" in its own terms while remaining the wrong statistic |
| E3 | The three sites' dedicated-walker layers **sum to < 900 ms = < 3.5%** of a check-only compile |
| E4 | At least one site's largest single firewall group is a rule tsc also implements (i.e. it moves rather than deletes) |

If E3 holds, the scope change — which trades the property that made the
byte-identical corpus reachable — is worth **single-digit percent** on the three
largest assignability sites in the compiler, and the arc's honest conclusion is that
there is no large constant left anywhere, including here.

## 5. Site 2 measured — `checkReturnAssignability` (round 755)

A level-C partition in `CtaSections` (its own index space and arrays, so the
level-A/B layout and the pins asserting it are untouched): 15 rows in source order,
a wrapper/`…Core` split so every one of the function's early returns closes its row,
and an exit-row census. `COARSE` keeps the whole function in one row, which makes it
level C's own calibration counterpart.

**Cross-check first, because it is what makes the numbers believable.** Level C
totals **741 ms** over 10,119 invocations. Level A — an independent partition,
opened on the *caller* — puts its `A: checkReturnAssignability` row at **740 ms**
over 9,926 reaches. Two partitions taken from opposite sides of the same call agree
to 0.1%.

**Probe calibration, differential and in situ.** ON carries 144,179 level-C
boundaries and reports 741 ms; COARSE carries 10,119 and reports 642 ms →
**739 ns per boundary**, far above the 86–89 ns per *read* of rounds 734/735 and
consistent with round 733's finding that an in-situ empty span reads ~900 ns. Rows
below are net of 739 ns × their own reach count.

| row | raw ms | net ms | reaches | class |
|---|---:|---:|---:|---|
| the SOURCE type | 226 | **219** | 9,706 | engine |
| the objlit / array / arrow guard cluster | 125 | **118** | 9,340 | **walker** |
| flow narrowing | 122 | **115** | 9,706 | engine |
| `checkConditionalReturnBranches` | 53 | **46** | 10,081 | engine |
| `canUseTypeEngine` + `checkTypeRelatedTo` | 52 | **39** | 17,702 | engine |
| nullish-alias / literal-union / qualified-name guards | 28 | **21** | 10,115 | **walker** |
| `getTypeFromTypeNode` — the TARGET | 27 | **20** | 10,081 | engine |
| the string-based fallback | 21 | **15** | 8,587 | **walker** |
| post-`canUse` guards | 19 | **12** | 9,114 | **walker** |
| `arrayLiteralSatisfiesTupleTarget` | 13 | **6** | 10,096 | **walker** |
| generator `TReturn` unwrap | 13 | **6** | 10,119 | engine |
| foreign-TP gate | 13 | **6** | 9,706 | **walker** |
| contextual-type selection | 10 | **3** | 9,706 | engine |
| TS2322 elaboration + emission | 0 | **0** | **1** | bookkeeping |
| wrapper transition (probe-only) | 11 | 4 | 10,119 | — |

| class | net ms | share of the function |
|---|---:|---:|
| **engine work** | **446** | **71.6%** |
| **dedicated-walker layer** | **177** | **28.4%** |
| bookkeeping | 0 | 0.0% |

**On this site the layer is 177 ms of a 26,778 ms compile = 0.66%, and 0.40× the
engine work.** Site 1 was 326 ms / 1.21% / 0.67×.

### Three things this site says that site 1 could not

1. **The TS2322 elaboration — 218 lines, the block every reader assumes is
   expensive — runs ONCE in the whole compile.** That is a property of the profile
   (zero TS2322 on tsc's own source), not of the code; but it means the elaboration
   is not part of the price of the architecture on a clean codebase.
2. **The legacy string checker is not a rare fallback: 8,587 of 10,119 invocations
   (85%) exit inside it**, i.e. they run the entire engine block *and then* re-check
   through the string path. That is the clearest example in the compiler of the
   double-checking the scope change would delete — and it is **15 ms**.
3. **The largest single firewall group is excess-property checking** (inside the
   118 ms objlit cluster: `checkExcessProperties` ×2, `excessPropDisplayTarget` ×2,
   plus the objlit-shape guards). tsc implements excess-property checking *inside*
   `checkTypeRelatedTo` (`hasExcessProperties`), so this group MOVES rather than
   vanishes — the same shape as site 1's weak-type rule.

## 5b. Site 3 measured — `checkAssignmentExpression` (round 786)

A level-E partition in `CtaSections`: 27 rows in source order, its own index space
and arrays, a wrapper/`…Core` split so every one of the function's ~40 early
returns closes its row, and an exit-row census. `COARSE` keeps the whole function
in one row, so it is level E's own calibration counterpart.

**The function is RECURSIVE, at exactly one place** — `a = b = c` descends into the
chained right-hand assignment at the top. That site gets its own row,
`E_RECURSE`, and `atE` keeps the "depth != 1 ⇒ return" shape, so the whole nested
descent is charged to the outer invocation's recursion row and every other row
stays exclusive of recursion. (Level D needed round 756's hand-back shape because
its recursion is spread across sixteen arms; one recursion site is better served
by one row, and `CtaSectionProbeTest` pins `invocationsENested == 1` on an
`a = b = c` fixture so a *second* recursion site added later cannot slip in
silently.)

**Cross-check first.** Level E totals **462 ms** over 17,179 invocations. Level A —
an independent partition opened on the *caller* — puts its
`A: checkAssignmentExpression` row at **465 ms** over 16,538 reaches. Two
partitions from opposite sides of the same call agree to 0.6%, and the 641-reach
difference is exactly the five legacy-walker call sites level A does not cover.
Probe-free (HEAD, levels A–D only) that row reads **406 ms of a 27,941 ms
compile = 1.45%**.

**Probe calibration — and it is WEAK, stated rather than buried.** Three runs per
mode: ON 462 / 460 / 469 ms, COARSE 445 / 420 / 426 ms. Δ of the medians is
**36 ms over 103,899 extra boundaries = 347 ns per boundary** — but the ON spread
is 9 ms and the COARSE spread **25 ms**, so Δ is only **1.4× the larger spread**.
Per CLAUDE.md's rule the differential is a *bound*, not a figure: taking the
extreme pairs gives **144–472 ns**. What rescues the round is that the
classification is stable across the whole of it — the walker layer reads
**30.3% / 27.9% / 26.4%** at 144 / 347 / 472 ns — because the boundaries are
distributed by *reach*, and reach is nearly uniform across the identifier
partition. Rows below are net of 347 ns × their own reach.

| row | raw ms | net ms | reaches | class |
|---|---:|---:|---:|---|
| the SOURCE type | 161 | **160** | 3,826 | engine |
| flow narrowing | 60 | **59** | 3,826 | engine |
| identifier target — array/identifier RHS guards | 35 | **33** | 4,364 | **walker** |
| `x.prop = value` (`checkPropertyAccessAssignment`) | 31 | **31** | 2,119 | engine |
| `canUseTypeEngine` + `checkTypeRelatedTo` | 21 | **20** | 3,791 | engine |
| foreign-TP target gate + `never` target (B8.1) | 16 | **15** | 3,829 | **walker** |
| the TARGET type (annotation + local + tuple) | 17 | **15** | 4,364 | engine |
| legacy `varTypes` string fallback | 12 | **11** | 3,824 | **walker** |
| post-relation walkers (index-sig / excess / array) | 11 | **10** | 3,791 | **walker** |
| the chained-assignment recursion | 12 | **10** | 6,747 | (recursion) |
| sort-comparator / deeply-nested / mutually-recursive | 12 | **9** | 6,747 | **walker** |
| module-alias `typeof import` shapes | 7 | **6** | 4,364 | **walker** |
| `x[k] = value` | 4 | **4** | 204 | **walker** |
| B127 interface-vs-interface guards | 5 | **4** | 3,791 | **walker** |
| `arguments = <primitive>` / foreign-TP source gate / literal-RHS exit | 14 | **9** | ~3.8k each | **walker** |
| `X.prototype.m = fn` | 5 | **3** | 6,747 | **walker** |
| B175 / B236 / construct-sig / call-sig / objlit / union guards | 12 | **5** | ~3.8k each | **walker** |
| `this.prop = value` | 1 | **1** | 58 | **walker** |
| TS2322 elaboration + emission | 2 | **0.4** | 3,791 | bookkeeping |
| wrapper transition (probe-only) | 22 | 16 | 17,179 | — |

| class | net ms | share of the function |
|---|---:|---:|
| **engine work** | **284** | **72.0%** |
| **dedicated-walker layer** | **110** | **27.9%** |
| bookkeeping | 0.4 | 0.1% |

**On this site the layer is 110 ms of a 27,941 ms compile = 0.39%, and 0.39× the
engine work.** Site 1: 326 ms / 1.21% / 0.67×. Site 2: 177 ms / 0.66% / 0.40×.

### Three things this site says that sites 1 and 2 could not

1. **61% of the invocations do nothing at all.** 10,432 of 17,179 exit in the
   entry row, because the expression is not an `=` `BinaryExpression` — the
   eligibility test lives *inside* the function, so unlike sites 1 and 2 (whose
   callers pre-filter) this site pays for its own dispatch. It costs about
   **13 ms**, so it is a shape rather than a lever, and only a partition opened
   on the WRAPPER can see it at all.
2. **The TS2322 elaboration is 0.4 ms over 3,791 reaches.** Third independent
   confirmation, on the third site: on a codebase that type-checks, the emission
   machinery every reader assumes is expensive is free. Site 2 reached it once in
   the whole compile; here it is reached 3,791 times and still costs nothing,
   which is the stronger form of the finding.
3. **"Compute the SOURCE type" is the largest row at all three sites** — 160 ms of
   394 classified here (41%), 219 of 623 at site 2. Whatever replaces the
   dedicated-walker layer still pays it.

**Deletable, as opposed to moved, at this site: ~14 ms.** The legacy `varTypes`
string fallback (11 ms) plus the literal-RHS exit that exists only to stop that
fallback re-widening a literal (3 ms) — the same pure double-checking as site 2's
string checker (15 ms), and 13% of the site's layer. The largest walker group,
the 33 ms identifier-target cluster, is `strictFunctionTypes` variance, declared
`in`/`out` alias variance, spread freshness and tuple arity — all rules tsc holds
inside `isRelatedTo`, so they MOVE.

## 6. Scoring the round-739 predictions

*All three sites are now measured; the round-755 scoring is superseded.*

| | prediction | outcome |
|---|---|---|
| **E1** | all sites show a walker layer of **25–50%** of their function | **HOLDS at all three: 37.4% / 28.4% / 27.9%.** The band was right and slightly high. |
| **E2** | all show a firewall/relation ratio **≥ 10×** | **FAILS at both sites that tested it: 4.5× (site 2) and 5.6× (site 3).** The 14× was an artifact of site 1's relation being 2.2% of its function; at sites 2 and 3 the relation is 6.3% and 7.2%, and the ratio halves. It was the wrong statistic *and* it does not survive in its own terms. |
| **E3** | the three layers **sum to < 900 ms = < 3.5%** | **HOLDS, now by MEASUREMENT rather than by a bound.** 326 + 177 + **110** = **613 ms = 2.2%** of a 27,941 ms check-only compile. Round 755 estimated 605–630 ms from the 28–37% band before site 3 was opened; the measurement landed **inside** that interval. |
| **E4** | at least one site's largest firewall group is a rule tsc also has | **HOLDS at ALL THREE.** Site 1: the weak-type rule (165 ms, half the layer). Site 2: excess-property checking (the bulk of 118 ms). Site 3: `strictFunctionTypes` / declared-variance / freshness (33 ms, the largest group). tsc implements all three inside `checkTypeRelatedTo`, so all three MOVE. |

## 7. What (ENGINE.1) is worth, stated for the owner

**Deleting the dedicated-walker layer on the three largest assignability sites in
the compiler is worth 613 ms = 2.2% of a check-only compile — and the deletable
fraction is far smaller than that, because the largest group at EACH of the three
sites is a rule tsc implements too and would move into the replacement engine
rather than disappear.** *(Round 786: this was "~2.3%, bounded above at 3.3%" while
site 3 was open; the bound is now a measurement and it came in at the low end.
The plainly-deletable double-checking — site 2's legacy string checker and site
3's — is **~29 ms, one tenth of one percent**.)*

Against a drift band re-derived the same session at **±2.0%** (five interleaved null
pairs: median −0.05%, range [−526, +569] ms on a 26,778 ms compile), **the whole
scope change is between one and two noise bands**, and it trades the property that
made a byte-identical corpus reachable.

Two caveats stated rather than buried:

* **This is three sites, not the architecture.** The static census counts 1,046
  `check*`/`emit*`/`tryEmit*` functions; these three are the largest *assignability*
  sites, chosen because round 738 had already measured them. The layer elsewhere is
  unmeasured, and there is no basis here for extrapolating a per-function share to
  1,046 functions.
* **The consistency across the three is itself the strongest evidence.** Three
  independently partitioned functions of 1,466 / 802 / 1,449 lines, written years
  apart, land at 37.4% / 28.4% / 27.9% walker layer and 0.67× / 0.40× / 0.39× the
  engine work. That is a property of the architecture, not of a function — and it
  is also why measuring a fourth site is very unlikely to change the answer.

## 8. Status

**(ENGINE.1) IS CLOSED (round 786).** All three sites are measured by the same
method. E1 holds at all three, E4 holds at all three, **E2 fails at both sites
that could test it**, and **E3 is closed by measurement rather than by a bound**:
the three layers sum to **613 ms = 2.2%** of a check-only compile, against a drift
band re-derived at ±2.0%. What round 739 left — "measure two more sites before
believing the 14×" — is settled: the 14× is gone, replaced by 0.67×, 0.40× and
0.39×, and the quantity a decision turns on is **2.2% of the compile, of which
~29 ms (0.1%) is plainly deletable and the rest either moves into the replacement
engine or is a firewall over our own relation.**

**Nothing was landed but the harness, at any of the three sites.** Every candidate
lever inside them measures below the drift band; the answer this document exists
to give is a scope answer, and it is that the scope change buys roughly one noise
band while trading the property that made a byte-identical corpus reachable.

### Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSections       build/bench/tsc-project-*   # the partition
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSectionsCoarse build/bench/tsc-project-*   # the calibration
```

Level A's rows price the four callees; level B partitions
`checkVarDeclAssignability` (site 1), level C `checkReturnAssignability` (site 2),
level D `walkFunctionBodiesInExpr`, level E `checkAssignmentExpression` (site 3).
Take THREE runs per mode and print both spreads before quoting a differential —
level E's Δ is only 1.4× the COARSE spread, which is why its boundary cost is a
144–472 ns bound rather than a number.
