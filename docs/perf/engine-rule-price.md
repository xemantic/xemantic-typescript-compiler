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

## 5. Status

**(ENGINE.1) is NOT complete.** Sites 2 and 3 are unmeasured; this round deliberately
did not start their instrumentation thin (each of rounds 733–738 spent a full session
on ONE function, and these two are 802 and 1,427 lines). What landed is the
correction to the statistic the next round would otherwise have extended, the method
those sites must use to stay comparable, and a scored prediction.
