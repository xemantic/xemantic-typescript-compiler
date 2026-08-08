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
