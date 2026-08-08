<!--
  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
-->

# (NARROW.2)(e) — what round 852's narrowed-`any` receiver opening actually costs

Round 854. Compiler profile (`build/bench/tsc-project-*`, 78 files, 46 errors),
`--passTiming` full tier, box quiet, daemons stopped before every measurement.

## 0. The question, and why the obvious instrument cannot answer it

Round 852 let `checkMemberAccessMissing` read an `any` receiver's **narrowed**
type (`cmamNarrowedAnyReceiverType`). Round 853, having repaired `cost_gate.py`'s
classpath, attributed **`narrow.walks` 17,851 → 31,961 (+79.04%)** to that one
hunk by ablation, and queued the wall-time price as (NARROW.2)(e).

A count is not a cost (round 801). The obvious instrument — difference the
`narrowWalks=<ms>` row between an ablated build and HEAD — **cannot settle this**:

| run of the SAME HEAD binary | `narrowWalks` |
|---|---|
| round 853's gate log | 1,423 ms |
| round 854 rep 1 | 1,602 ms |
| round 854 rep 2 | 1,460 ms |

±6–9%, i.e. ±90–150 ms, which is the same order as the object. So the span was
taken **where the cost is incurred** instead: one timestamp pair around the
opening's call to `getNarrowedTypeForReference`, plus the delta of
`PassTiming.narrowWalkNanos` across the same call, plus the three counts that say
whether anything came back (`PassTiming.cmamAny*`, printed by `--passTiming`).

Two figures per population, deliberately:

* **`span`** — wall inside the opening. INCLUSIVE of the tier-3 shadow-memo
  bookkeeping (`walkShadow.put`, the union-id sort, `depKeyedShadowClassify`,
  the bucket hooks) that exists only under the probe. An **upper** bound.
* **`walkOnly`** — the `narrowWalkNanos` delta, i.e. the walk body alone, which
  is the code a production run executes. The **representative** figure.

## 1. The population (deterministic — bit-identical across all six runs)

```
openings = 14117   narrowed = 1345 (9.5%)   accepted = 1051 (7.4%)
```

* **`openings` = 14,117** against round 853's **+14,110** walk delta: essentially
  every opening launches a walk, so the counter delta and this census are the
  same population measured two ways.
* **`narrowed`** — the flow answered something other than `any`: **9.5%**.
* **`accepted`** — survived every refusal (`Type.Object`, not a `Type.Reference`,
  not the global `Object`/`Function`, not enum-flavoured) and became the receiver
  type: **7.4%**.
* On this profile those 1,051 accepted receivers emit **nothing** — round 852's
  8-profile grid is 0 added / 0 removed, re-verified byte-identical by round 853.
  The diagnostics they buy are the conformance ones (`types/any`, 3 failing of 9
  → 1), which this profile does not contain.

**The work is ADDED, not MOVED.** Round 788's law is the first thing to check
before pricing any skip, and here it does not apply: `narrow.memoServed` moved
**42,766 → 42,867, i.e. +101**, for +14,110 launched walks. Almost nothing else
was ever going to ask these questions, so removing the opening deletes the work
rather than deferring it to the next asker.

## 2. The price

### Cold (3 reps, `--passTiming`, mean ± spread)

| | ms |
|---|---|
| `span` | 429 / 452 / 424 → **435** |
| ... of which the openings that narrowed | 60 / 59 / 63 → **61 (14.0%)** |
| `walkOnly` | 382 / 387 / 375 → **381** |
| ... of which the openings that narrowed | 55 / 54 / 58 → **56 (14.6%)** |
| whole `narrowWalks` row | 1,456 / 1,450 / 1,446 → **1,451** |

**The opening is 26.3% of every narrowing walk the compiler performs.** That
ratio is far steadier than either absolute (26.3 / 26.7 / 25.9%), which is how it
should be quoted.

### Warm — the shipping artifact's price (round 843)

`BenchMain <proj> 2 5 full`, one JVM: probe-free median rebuild **7,664 ms**
(min 7,388, max 7,982; every iteration 78 files / 46 errors, so the self-
falsification holds), instrumented rebuild 11,472 ms.

| | ms | % of the 7,664 ms rebuild |
|---|---|---|
| `span` | 185 | 2.41% |
| `walkOnly` | **146** | **1.91%** |
| ... of which narrowed | 21 | 0.27% |
| ... of which NOT narrowed | **125** | **1.63%** |
| whole `narrowWalks` row | 597 | 7.79% |

The opening is **24.5% of warm narrowing** against 26.3% cold — the two regimes
agree, and so does the wasted share (**85.6% warm, 85.4% cold**).

## 3. The verdict: (c) — real, and avoidable

* It is **not unmeasurable**: 1.91% of a warm rebuild is nearly twice the ±1.0%
  warm A/B band, and a quarter of all narrowing work in the compiler.
* It is **not simply the price of the diagnostics**: 85.6% of it is spent on
  `any` receivers the flow never narrows, which by construction cannot produce a
  diagnostic — 12,772 of 14,117 openings, **125 ms warm = 1.63%**.

**And this is the direction round 759 warns is not automatic.** Its law — "what
you could skip cheaply is what was already cheap" — holds only when the exit
predicate and the cost share a cause. Here they do not: the predicate is *did the
flow narrow*, the cost is *how far the walk had to traverse*, and the 90.5% that
answer `any` carry **85.4% of the cost**. Count share and cost share coincide for
once; that had to be measured, not assumed.

## 4. What a fix may and may not claim

**1.63% warm is a CEILING, not a yield.** It is what a *perfect* oracle would
return. The concrete design — a per-file set of root names reachable by any
narrowing flow node (`FlowCondition` / `FlowAssignment` / `FlowCall` /
`FlowSwitchClause` / `FlowArrayMutation`), consulted before the walk — is
**coarser** than that oracle: a receiver whose root name is narrowed *somewhere*
in the file, but not on this path, still walks. Nothing here says what fraction
of the 12,772 such a set would actually refuse.

So the next round must **census the pre-test before implementing it** — add the
candidate predicate as a probe that HONOURS NOTHING and count how many of the
12,772 it would refuse and how much of the 325 ms (cold) / 125 ms (warm) sits
behind them, exactly as round 792/793 priced their pre-gates. A design that
refuses, say, half of them lands at ~0.8% warm, i.e. inside the noise band, and
would not be worth its FP risk.

The soundness argument for that predicate, for the record: `narrowTypeFromFlow`
matches a dotted PATH against each narrowing node's expression, and the ROOT of
that path is an `Identifier` that must occur in the node's expression — so a name
occurring in none of them cannot be narrowed by any of them. Collecting every
identifier text in those expressions is a conservative superset. The set is
per-FILE, which also covers `FlowStart.outerFlow` (a closure reading a narrow
established in its enclosing scope).

**Do not revert (c) for this.** It closed two conformance cases with a 0/0 grid
on all eight profiles and a clean corpus.

## 4b. (NARROW.2)(f), round 855 — the pre-test was censused and it refuses NOTHING

§ 4 said the ceiling is not the yield and told the next round to census the
predicate before building it. It was censused. **Yield: 0 of 14,117 openings.**

```
cmamNarrowedAny  (e): openings=14117 narrowed=1345 accepted=1051  walkOnly=385/406/360 ms
cmamAnyPreTest   (f): refused=0  noPath=0  kept=14117
                      refusedNarrowed=0  refusedAccepted=0  keptNarrowed=1345
                      refusedSpan=0ms  refusedWalkOnly=0ms  preTestCost=211/162/150 ms
```

Three cold reps, compiler profile, 46 errors every run (the probe HONOURS
NOTHING — it evaluates the predicate, records the verdict and walks anyway, so
the population is bit-identical to round 854's and the run's output is
production's).

### The instrument is alive — the complement population refuses

Round 790: a verifier reading 0 reads 0 when it is dead, too. Two scratch-project
controls, run through the CLI on the same binary:

| fixture | openings | refused |
|---|---|---|
| `declare const g: any; g.alpha` (+ a `let`-declared and a parameter root) | 3 | **0** |
| the same, plus `import { imported } from "./dep"; imported.alpha` | 4 | **1** |
| the same again, with one `if (imported)` added | 4 | **0** |

So the predicate discriminates exactly on its intended axis, and the profile's
zero is a fact about the profile, not about the probe.

### Why no per-file name-keyed set can do better

**Every `VariableDeclaration`, `Parameter` and `BindingElement` mints a
`FlowAssignment`**, and that node's subtree contains the declared name. So every
root *declared in a file* is in that file's narrowable set **by construction** —
including `declare const g: any`, which is narrowed nowhere. The set can only
ever refuse a root with no declaration in the file at all: an import, or an
ambient global declared elsewhere. tsc's own sources reach
`checkMemberAccessMissing` with `any` receivers that are locals and parameters,
so it refuses none of them.

This is not a tuning failure and there is no coarser/finer version to try: the
declaration that makes a name exist is itself one of the narrowing nodes the set
is built from.

### And the predicate costs more than the walks it was meant to delete

`preTestCost` — the pre-test's own wall, measured at every opening — is
**150–211 ms cold**, against a `walkOnly` of **360–406 ms** for the whole
population it was supposed to shrink. Most of that is the one-off
`narrowableRoots()` construction per file (the identifier sweep over every
narrowing node's subtree), which a shipped gate pays exactly as the probe does.
Even at a hypothetical 100% yield the arithmetic would be marginal; at 0% it is
a pure loss.

### Verdict

**NO-GO on the ~70% threshold — measured 0%.** (NARROW.2)(f) is closed as a
measured negative. Round 852's opening stays: it is 1.91% of a warm rebuild and
85.6% of that is waste, but **the waste is not addressable by asking whether the
name is narrowable anywhere in the file**. A future attempt needs a
*path-and-position* oracle (does any narrowing node lie on THIS reference's flow
path), which is the walk itself — i.e. the cost is the answer, and the only
remaining lever is not to ask the question at all, which is what round 852
deliberately bought two conformance cases with.

The probe stays in the tree, `PassTiming.detailed`-gated (a production compile
keeps no inventory and `FlowGraph.narrowableRoots()` answers `null` = "unknown,
refuse nothing"), so the next candidate predicate can be censused the same way
for one build instead of a round. `NarrowableRootsPreTestTest` pins the finding,
both controls and the soundness zeroes.

## 5. Reproducing

```bash
./gradlew --stop && pkill -f 'KotlinCompile[D]aemon'   # measure on a quiet box
scripts/round854-narrow-price.sh /path/to/main/classes label 3   # cold
java -cp <main>:<test>:<deps> \
  com.xemantic.typescript.compiler.bench.BenchMainKt <proj> 2 5 full   # warm
```

Both print `cmamNarrowedAny (NARROW.2)(e): openings=… narrowed=… accepted=…
span=…ms (of which narrowed …ms) walkOnly=…ms (of which narrowed …ms)`.
`NarrowedAnyCensusTest` pins that the counters move, that they partition
(`accepted <= narrowed <= openings`, `walkOnly <= span`), and that a run with
`PassTiming` disabled records nothing.
