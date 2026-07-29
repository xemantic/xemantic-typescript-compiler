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

## 6. Scoring the round-739 predictions

| | prediction | outcome |
|---|---|---|
| **E1** | both sites show a walker layer of **25–50%** of their function | **HOLDS at site 2: 28.4%** (site 1: 37.4%). Site 3 unmeasured. |
| **E2** | both show a firewall/relation ratio **≥ 10×** | **FAILS at site 2: 177 / 39 = 4.5×.** The 14× does not even survive in its own terms — it was an artifact of site 1's relation being 2.2% of its function; here the relation is 6.3%. |
| **E3** | the three layers **sum to < 900 ms = < 3.5%** | **HOLDS, and by a bound rather than a measurement.** Sites 1+2 = 503 ms. Site 3's *entire* function is 373 ms raw (level A's `A: checkAssignmentExpression` row), so the three-site layer cannot exceed **876 ms = 3.3%** whatever site 3's split turns out to be. At the 28–37% both measured sites show, the honest estimate is **~605–630 ms = 2.3%**. |
| **E4** | at least one site's largest firewall group is a rule tsc also has | **HOLDS at BOTH.** Site 1: the weak-type rule (165 ms, half the layer). Site 2: excess-property checking (the bulk of 118 ms). Both are implemented inside `checkTypeRelatedTo` by tsc, so both MOVE. |

## 7. What (ENGINE.1) is worth, stated for the owner

**Deleting the dedicated-walker layer on the three largest assignability sites in
the compiler is worth ~2.3% of a check-only compile, bounded above at 3.3% — and
the deletable fraction is smaller than that, because the largest group at each of
the two measured sites is a rule tsc implements too and would move into the
replacement engine rather than disappear.**

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
* **Site 3 is still unmeasured.** E3 is closed by a bound, not by its partition.

## 8. Status

**(ENGINE.1) is answered for the decision it exists to inform, with site 3's
partition still open.** Sites 1 and 2 are measured by the same method; E1 and E4
hold, E2 fails, E3 is closed by a bound that site 3 cannot break. What round 739
left — "measure two more sites before believing the 14×" — is settled: the 14× is
gone, replaced by 0.67× and 0.40×, and the quantity a decision turns on is
~2.3% of the compile.

### Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSections       build/bench/tsc-project-*   # the partition
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSectionsCoarse build/bench/tsc-project-*   # the calibration
```
