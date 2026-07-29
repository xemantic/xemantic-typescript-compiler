# (CALL.4) — what the 21,708 ns of a narrowing condition actually is

*Round 755. Companion to `docs/perf/narrow-walk-attribution.md` (round 736), which
left this as "the largest unattributed number this arc has produced". It is
attributed here, and the item closes as a measurement: **80% of it is one callee,
and the whole population is smaller than one A/B pair's noise.***

---

## 0. The claim under test, and a correction to it before we start

> "Narrowing's remaining cost is `applyConditionNarrowing`'s **33,307
> genuinely-narrowing calls at 21,708 ns each ≈ 723 ms** … note it is a 723 ms
> population, i.e. 2.4% of the compile, only just outside the band."
> — round 736, `narrow-walk-attribution.md` § 7

**Re-measured on today's compiler profile, that population no longer exists at
that size.**

| | round 736 | round 755 | Δ |
|---|---:|---:|---:|
| `applyConditionNarrowing` outermost calls | 333,031 | 341,240 | **+2.5%** |
| — of which return the INPUT unchanged | 319,724 (96%) | 319,270 (**93.6%**) | −0.1% |
| — of which genuinely narrow | 33,307 | **21,970** | **−34%** |
| ns per narrowing call | 21,708 | **20,085** | −7.5% |
| the narrowing population | 723 ms | **441 ms** | **−39%** |

The per-call cost is stable. **The call COUNT moved**: a third of the conditions
that narrowed in round 736 no longer do, while the total number of conditions
walked went slightly up. Rounds 737–754 landed no narrowing change; the shift is
downstream of the relation and generic-resolution work of (REL.1) and round 754,
which changes what the declared types ARE and therefore how often a condition has
anything to remove. Behaviour is pinned in both directions (corpus green, profile
46 errors, composition unchanged), so this is a shape change, not a loss.

**Method note worth carrying: an item defined by a measured number should
re-measure that number before spending a round inside it.** This one halved while
sitting in the queue.

## 1. What was built

`NarrowSections` gains a second index space, `C_*` — eleven nested sub-measures,
one per leaf `applyConditionNarrowing` can call — plus an arm census mirroring its
`when`, and a third mode.

Three design points, each forced by something the round-736 harness learned:

* **The rows are split by whether the OUTERMOST call narrowed.** The two
  populations differ by 14× in per-call cost, so a single mean answers nothing.
  A per-call scratch is cleared at the `FlowCondition` entry (`beginCond`) and
  folded at its close into NARROWING or IDENTITY columns. Clearing at entry is
  what makes the fold safe: `applyConditionNarrowing` has four other entry points
  (the `FollowLoopEntry` mirror, `narrowBySwitchClause`, `narrowByAssertCall`,
  and the `checkCondition` helper) that are not bracketed, and whatever they
  leave in the scratch is discarded rather than charged to the next condition.
* **`getReferencePath` is COUNTED in every mode but TIMED only in `DEEP`.** It is
  reached 172,957 times against ~522,924 for all ten `narrowBy*` leaves together,
  so its boundary pairs would inflate every other row. `--narrowSectionsDeep` is
  its own differential against `--narrowSections`.
* **The rows are self time and cannot contain the recursion.** Every recursive arm
  (wrappers, `!`, `||`/`&&`/`??`, the alias inline) calls the dispatcher as a
  separate statement; every bracketed leaf provably does not re-enter it.

## 2. The census — counters only, zero timestamp inflation (`--narrowSectionsCoarse`)

```
walks 46,917   invocations 676,239   arrivals 3,532,884   distinct 3,237,923 (revisit 1.09)
applyConditionNarrowing: 341,240 outermost calls, 319,270 identity (93.6%)
                       → 21,970 genuinely narrowing (6.4%)
applyConditionNarrowing INVOCATIONS incl. recursion: 1,485,724
   fan-out per outermost call: narrowing 2.89, identity 2.71 (max 69)
```

Only 62.5% of the 1.49 M invocations sit under the bracketed `FlowCondition`
entry; the rest arrive through the four unbracketed entry points. That is the
first thing the numbers say: **the dispatcher is called far more often than the
narrowing walk's condition arm calls it.**

The arm census — which `when` arm each invocation took:

| arm | all invocations | under a NARROWING outermost call | share of narrowing |
|---|---:|---:|---:|
| call | 246,210 | **23,155** | **36.4%** |
| `\|\|` `&&` `??` | 333,432 | 16,445 | 25.8% |
| `===` `!==` `==` `!=` | 218,447 | 6,642 | 10.4% |
| prefix `!` | 142,976 | 5,757 | 9.0% |
| identifier | 163,183 | 5,301 | 8.3% |
| property access | 130,401 | 3,460 | 5.4% |
| wrapper (paren/as/satisfies/nonnull) | 57,929 | 2,224 | 3.5% |
| binary, other operator | 177,842 | 580 | 0.9% |
| other expression kind | 9,256 | 35 | 0.1% |
| `=` (truthy assignment) | 5,968 | 26 | 0.0% |
| `in` | 42 | 22 | 0.0% |
| `instanceof` | 38 | 3 | 0.0% |

`instanceof` and `in` are **38 and 42 invocations in the whole compile** — the two
arms with the most conspicuous narrowing semantics are statistically absent from
the TypeScript compiler's own source. `||`/`&&`/`??` at 333,432 is second by
volume purely because it recurses twice per invocation.

## 3. The split — where the 20,085 ns goes

`--narrowSections`, whole compile. Raw ns, probe-inflated: sound for RELATIVE
attribution only (§ 5).

| row | NARROWING ms | calls | ns each | IDENTITY ms | calls | ns each |
|---|---:|---:|---:|---:|---:|---:|
| **`narrowByCallPredicate`** | **351** | 23,138 | **15,181** | **121** | 142,174 | 854 |
| `narrowByEquality` | 50 | 6,642 | 7,647 | 91 | 123,679 | 736 |
| `narrowByTruthiness` | 16 | 4,253 | 3,918 | 1 | 2,605 | 613 |
| `getUnionType` at `\|\|`/`&&`/`??` | 6 | 5,044 | 1,239 | 37 | 97,092 | 390 |
| `narrowByBooleanDiscriminantTruthiness` | 5 | 2,072 | 2,700 | 4 | 15,102 | 307 |
| `aliasedConditionInitializer` | 0 | 1,873 | 341 | **77** | 99,002 | 778 |
| `narrowByExcludingNullUndefined` | 0 | 160 | 1,481 | 0 | 17 | 528 |
| `narrowByInOperator` | 0 | 22 | 5,865 | 0 | 14 | 4,012 |
| `narrowByArrayIsArray` | 0 | 4 | 5,450 | 0 | 4 | 4,435 |
| `narrowByInstanceOf` | 0 | 3 | 106,983 | 0 | 24 | 1,061 |
| `getReferencePath` (DEEP) | 0 | 6,225 | 65 | 14 | 166,732 | 84 |
| **sum of the rows** | **431** | | | **333** | | |
| **the anchor** | **441** | 21,970 | **20,085** | **454** | 319,270 | 1,424 |
| residue (dispatch + recursion) | **9** | | | 121 | | |

**The answer to (CALL.4): `narrowByCallPredicate` is 80% of a genuinely-narrowing
call, and the dispatcher's own residue is 2%.** `applyConditionNarrowing` is not a
function with an expensive body — it is a `when` that reaches one expensive callee.

Replicated across the two timing runs (ON and DEEP are independent measurements of
the same code):

| | ON | DEEP |
|---|---:|---:|
| `narrowByCallPredicate` share of the narrowing anchor | 79.6% | 80.8% |
| dispatcher residue share | 2.0% | 1.9% |

Individual small rows move ±25% between those runs; the headline moves 1.2 points.

### The IDENTITY column is not free dispatch

Round 736 characterised the 93.6% identity majority as "the cheap tail, 949 ns".
It is 1,424 ns today, and **73% of it is inside three leaves that resolve
something**: `narrowByCallPredicate` 121 ms, `narrowByEquality` 91 ms,
`aliasedConditionInitializer` 77 ms. An identity call is not a call that did
nothing; it is a call that resolved a callee, or a memoised alias chain, and then
found nothing to remove.

## 4. The rejected pre-test is not only in-band — it is UNSOUND

Round 736 priced "does this condition mention the name" at ~410 ms and rejected it
on price. This round adds a second, independent reason, and it is the stronger one.

**99,002 identity calls (31% of them) reach `aliasedConditionInitializer`** — the
Identifier arm, taken when the condition is a bare name that is *not* the walked
reference. That is the aliased-condition feature: `const isFrag = isSq(s); if
(isFrag) { … }` narrows `s` through a condition that mentions only `isFrag`.
**1,873 of those alias resolutions produce a real narrowing.** A syntactic
"mentions the name" pre-test rejects every one of them, and following the alias to
find out is the work the pre-test exists to avoid.

Pinned by `NarrowSectionProbeTest.an aliased condition narrows though it never
mentions the reference` — the TS2339 there exists only because the alias inlined.

## 5. What the probe costs, measured rather than asserted

The ON run carries ~2.34 M boundary pairs (1.77 M in the `S_*` rows, 523 k in the
`C_*` rows, 47 k anchors).

* **In-situ differential:** ON→DEEP adds exactly 172,957 pairs and moves the walk
  anchor 1,963 → 2,010 ms, i.e. **≤271 ns per pair**. This is an UPPER BOUND, not
  a measurement: individual rows move ±25% between the same two runs, so 47 ms on
  a 1,963 ms anchor is not separable from run-to-run drift. The DEEP row's own
  measured span is 81 ns, i.e. what a boundary pair ATTRIBUTES is a third of what
  it appears to COST — consistent with round 733's finding that in-situ
  calibration is necessary but not sufficient.
* **At rounds 734/735's 86–89 ns per timestamp read** (~175 ns per pair), the ON
  run's total inflation is **~410 ms of the 1,963 ms walk anchor = 21%**.

**Therefore: every per-section nanosecond in § 3 is RELATIVE attribution.** The
shares are robust (they replicate across two runs at different boundary counts);
the absolute ms are inflated by roughly a fifth.

## 6. The size, against a band re-derived today

Five interleaved NULL pairs (the same class directory on both sides), compiler
profile, this session:

```
MEDIAN self-time: A=26778ms  B=26764ms  delta=-14ms (-0.05%)   B wins 3/5
per-pair delta: median=-14ms  spread=1095ms  range=[-526, +569]
```

**The drift band is ±2.0% = ±536 ms, re-derived on a 26,778 ms compile.**

| population | raw ms | net of probe | share of compile |
|---|---:|---:|---:|
| genuinely-narrowing `applyConditionNarrowing` | 441 | ~433 | **1.6%** |
| `narrowByCallPredicate` inside it | 351 | ~347 | 1.3% |
| the whole `applyConditionNarrowing` anchor | 895 | ~800 | 3.0% |

**A single A/B pair moved ±570 ms today. The entire genuinely-narrowing population
is 441 ms.** Deleting it outright would not be reliably visible in the harness that
would have to confirm it.

## 7. Verdict — (CALL.4) closes as a measurement

**There is no lever here, and the reason is structural rather than incidental.**

1. The prize is 1.6% of the compile, inside the band. Round 736's own rule —
   *measure the PRIZE first, never hits × a mean call cost* — applies to this
   round's own tempting follow-on: whether `narrowByCallPredicate`'s 15,181 ns is
   a REPEAT population that a memo could serve. It does not matter what the repeat
   factor is. A perfect memo over the whole leaf is capped at 472 ms = 1.75%,
   which is inside the band before the memo costs anything, and § 0's law says the
   servable subset would be the cheap tail of that.
2. 80% of the prize is a single callee that does **type-predicate resolution** —
   resolve the callee, get its signature, read its type predicate, filter the
   union. That is type-system work, not dispatch machinery. It belongs to the
   M3.1 relation/inference arc, not to a narrowing optimisation.
3. The dispatcher's own overhead — the `when`, the recursion, the fan-out of 2.89
   invocations per outermost call — is **9 ms of 441 ms**. There is nothing to
   restructure.

This is the sixth consecutive attribution to end without a landed change, and the
fifth in which § 0's law appears in a shape that is not a cache.

## 8. Verification

* `NarrowSectionProbeTest`: 18 pins. All four modes agree on diagnostics,
  non-vacuously. The arm mirror is pinned **shape by shape**, each compiled alone
  with its confusable arms asserted zero — a fixture lighting every arm at once
  cannot detect a swap between two of them.
* Compiler profile `--listAll` under `--narrowSectionsDeep`: **byte-identical** to
  the probe-off run, 46 errors, TS2591×43 / TS2304×2 / TS2584×1.
* Filtered suite (`*Narrow*` `*Flow*` `*Guard*` `*Inv0*`): 504 tests, 0 failures.

**Two fixtures failed first and taught something.** `if (n > 1) { n.toFixed() }`
and `if (d = f()) { d.bark() }` recorded ZERO invocations — no walk reaches those
conditions. `n` is not a union so nothing walks for it at all; and a walk for `d`
breaks out of the fast-forward loop at the `FlowAssignment` the same expression
produced, so `narrowByAssignmentRhs` answers and the condition is never seen.
**The `=` arm's 5,968 invocations are therefore entirely OTHER references walking
past** — which is why only 26 of them narrow.

## 9. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the census — counters only, no timestamp inflation
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --narrowSectionsCoarse build/bench/tsc-project-*
# the split
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --narrowSections build/bench/tsc-project-*
# the getReferencePath rows + the boundary differential
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --narrowSectionsDeep build/bench/tsc-project-*
```

**≥3 GB free before any `-Xmx4g` profile run** (round 736 recorded a 30×
inflation at 239 MB available).
