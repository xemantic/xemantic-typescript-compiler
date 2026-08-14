# (WARM.32) — the price of the ITERATOR-ALLOCATION family (round 905)

**Verdict: REFUSED at 0.074% (3.90 ms) against an arc floor of ~0.31% (~17 ms).**
The family is refused by **4.4x**, and it is refused by the CENSUS alone — the
whole family performs **495,305 list iterations per warm rebuild**, where 17 ms
would need a premium of **34.3 ns per call** and the measured premium is
**11.95 ns** (`forEachChild`) and **2.75 ns** (the edge classifiers).

Binary: `26b9e994`. Profile: `build/bench/tsc-project-*` (78 files, 46 errors on
every one of the round's 16 instrumented rebuilds). Warm, `BenchMain <proj> 6 3
<tiers>`; the four process medians are 5,237.9 / 5,304.6 / 5,287.3 / 5,325.4 ms,
so the round's stated denominator is **5,290 ms** (1% = 52.9 ms). Captures under
`build/bench/round905/`.

## 0. The candidate, and where it came from

This one did not come from a JFR row. It came from a **transfer audit against a
sibling Kotlin project** (`../xemantic-rust-compiler`), which landed the
equivalent change and measured **−3.1% wall**. It had never been swept here.

The mechanism is real and is not in dispute. Kotlin's `Iterable.any` / `all` /
`none` / `forEach` are `inline`, but their bodies are `for (element in this)` on
an `Iterable` receiver, and the Kotlin compiler lowers a `for` to a counted loop
only for arrays, `CharSequence`s and ranges. Over a `List` it asks the receiver
for a **heap iterator** and then pays `hasNext()`/`next()` interface dispatch per
element. An indexed `for (i in xs.indices)` loop removes the allocation and both
dispatches.

Two populations, both named before any measurement:

* **`NodeWalk.forEachChild`** — 70 `list.forEach(action)` child positions, run
  once per node by three sweeps (`spineWalkFile`, `Binder.pushChildren`,
  `FlowGraph`'s side-table fill). #5 in the warm leaf table at **1.40%**.
* **The INV.4 edge classifiers** — 140 `.any { it === child }` plus 5
  `.any { it === node }` identity tests in `Checker.kt`, run per (parent, child)
  edge against round 875's **3.32 M edge evaluations at 13.3 ns = 44 ms**, which
  was the stated ceiling for that half.

### 0a. THE CAVEAT THAT DECIDED THE INSTRUMENT

**The value here is NOT the allocated bytes, and no allocation counter was
built.** This repo has twice measured allocation reduction as a non-lever:
round 801 removed **367,189 `String` allocations for exactly 0 ms**, and round
893 measured warm GC at **~92-98 ms of a ~5.4 s rebuild (~1.7%)** with the
FASTER binary taking MORE pauses. So the sibling project's MB figures do not
transfer. What transfers is only its stated reason its sampler did not
over-promise — an object handed to an iterator escapes by construction, so
escape analysis was never going to fold it away for free — and that is an
argument about DISPATCH, which is a time.

## 1. The refactor that made the family measurable at all

Before anything could be counted, the 215 sites had to become 2. Both
populations now route through one place each in `NodeWalk.kt` — `walkList` and
`anyIdentical` — whose bodies are the **exact lowering** of what they replaced
(`for (e in xs) action(e)`, `for (e in this) if (e === x) return true`), so the
extraction is shape-preserving and the fix, had it cleared, would have been a
one-line change in each.

**Measured side effect, independent of the verdict**: `forEachChild`'s three
(JIT.1) partitions drop from **4,353 / 2,728 / 2,175 = 9,256** bytecodes to
**2,750 / 1,626 / 1,553 = 5,929**, a **36% shrink of the compiler's traversal
primitive**, because 70 inlined iterator loops became 70 static calls (58
bytecodes each). `huge_methods.py --fail-over 0` stays at **0 over the limit**.
That function was over HotSpot's 8,000-byte `HugeMethodLimit` once already
(round 803, `-3.93%` when it was split), so the headroom is the currency this
repo tracks.

## 2. The census (`--iterCensus` / tier `itercensus`)

Per warm rebuild. **Two independent processes are IDENTICAL to the last digit**
— the census is deterministic, which is its own falsifier.

| | calls | elements | mean | EMPTY | SINGLETON | 2+ |
|---|---:|---:|---:|---:|---:|---:|
| `forEachChild` list positions | **275,477** | **547,102** | 1.986 | 19,529 (7.0%) | 144,377 (52.4%) | 111,571 |
| `anyIdentical` | **219,828** | 378,400 visited / 521,728 long | 1.721 | 1,915 (0.9%) | 72,240 (32.8%) | 145,673 |
| **FAMILY** | **495,305** | **925,502** visited | | | | |

Size histograms, buckets 0..10 then 11+:

    forEachChild  19529 144377 63020 22181 13468 4600 2943 1586 1054 499 397 1823
    anyIdentical   1915  72240 74184 32165 21723 8321 4322 2545 1582 417 207  207

`anyIdentical` additionally: **207,591 of 219,828 calls (94.4%) HIT**, and a hit
stops the scan — which is why 378,400 elements are visited out of 521,728
present, i.e. **1.72 steps per call**.

### 2a. The census refuses the candidate on its own

    17 ms / 495,305 calls = 34.3 ns per call

An iterator allocation plus two interface dispatches over a **two-element**
list cannot be 34 ns. Round 904's reference constants are the yardstick: a
**whole** boxed `HashMap<Long,·>` probe — hash, bucket, pointer chase, `equals`
— is **8.53 ns**, and a `LongKeyMap` probe is ~2 ns. Round 894/896's law
(divide the row by its own population and refuse an impossible per-op cost
BEFORE a build) refuses this family at the census, and everything below only
sharpens it.

**The population is the surprise, and it is 4-6x smaller than the queue entry
implied.** The entry projected `forEachChild` at ~857 k nodes x three sweeps and
the edge half at round 875's 3.32 M evaluations. Measured: only **275,477** list
positions exist across all three sweeps (most nodes' children are direct
`action(x)` / `?.let(action)` positions — `IDENTIFIER` alone is 44.5% of nodes
and has no children at all), and only **219,828** of the 3.32 M edge evaluations
ever reach an `.any` (the `when (parent)` dispatch means at most one arm runs,
and most arms have no membership test). **A count of SITES is not a count of
CALLS** — 215 sites, 495,305 calls.

### 2b. The concrete-class census, which is what makes the amplifier honest

    concrete List classes (sampled 1/1024): {ArrayList=268, SingletonList=1}

**99.6% `ArrayList`.** This was censused for one reason: an in-situ amplifier
runs arm A at ONE call site, where production runs it at 70 (resp. 145) sites
that may each be monomorphic and so inline `iterator()`/`hasNext`/`next` and
scalar-replace the iterator. That bias would make arm A an over-read. It does
not apply: the receivers are one class, so the amplifier's single site sees the
same distribution production's sites see, and the two are comparable.

## 3. The amplifier (`--iterAmp N` / tier `iteramp<N>`)

Two arms under one timestamp pair each, IN SITU at both populations' real call
sites, ABBA inside each process (the arms alternate which runs first) with the
rotation MIRRORED across two processes (round 891). `r = 8` and `r = 24`, four
draws per (arm, r); the leading draw of each process is dropped, because round
869's law bit visibly here — `forEachChild` arm A's leading draw reads **35.60
ns against 25.1-27.8** for the other three at the same `r`.

    p(r) = cost + boundary / r        fitted PER ARM

### 3a. `forEachChild` list positions

| arm | p(8) | p(24) | **cost** | implied boundary |
|---|---:|---:|---:|---:|
| **A** `for (e in xs) action(e)` — heap iterator | 25.574 | 19.914 | **17.084 ns** | 67.9 ns |
| **B** `for (i in xs.indices)` — indexed | 12.806 | 7.693 | **5.136 ns** | 61.4 ns |

    PREMIUM  =  cost(A) - cost(B)  =  11.95 ns per call

### 3b. `anyIdentical`

| arm | p(8) | p(24) | **cost** | implied boundary |
|---|---:|---:|---:|---:|
| **A** `for (e in this) if (e === x) return true` | 13.254 | 8.251 | **5.750 ns** | 60.0 ns |
| **B** `for (i in indices) if (this[i] === x) …` | 8.412 | 4.801 | **2.996 ns** | 43.3 ns |

    PREMIUM  =  2.75 ns per call

### 3c. The falsifiers, all four passing

1. **Sinks EQUAL between arms** in all 16 draws — the two arms visit the same
   elements in the same order and fold the same value, so this is an
   equivalence assertion and not merely a liveness one.
2. **Every sink an exact multiple of `r`** (`sink mod r = 0` printed for all 16),
   which rules out an elided loop.
3. **No arm flat between the two `r`** (A 25.6 -> 19.9, B 12.8 -> 7.7, and
   likewise on the other half), which is the hoisting falsifier — the slope,
   never the sink (round 903).
4. **The model closes on itself to 0.01 ns.** The residual between a single-`r`
   `A - B` and the fitted difference-of-costs must be exactly
   `(boundary_A - boundary_B) / r`, and it is, at both `r`, on both halves:

       forEachChild  r= 8:  11.95 + (67.9-61.4)/8  = 12.77   measured 12.77
       forEachChild  r=24:  11.95 + (67.9-61.4)/24 = 12.22   measured 12.22
       anyIdentical  r= 8:   2.75 + (60.0-43.3)/8  =  4.84   measured  4.84
       anyIdentical  r=24:   2.75 + (60.0-43.3)/24 =  3.45   measured  3.45

### 3d. Round 904's correction is MANDATORY here, and this round doubles its size

Round 904 recorded a single-`r` `A - B` over-reading the premium by **up to
23%**. On `anyIdentical` it over-reads by **76% at r = 8** and 25% at r = 24,
because the two arms' boundaries are 60.0 and 43.3 ns — a 39% disagreement,
where round 904's were 88.0 and 76.1. **The two arms' per-call fixed costs are
not the same quantity and there is no reason they should be**: the "boundary" a
fit recovers absorbs everything charged per CALL rather than per rep, and arm A
carries an iterator construction there that arm B does not. Had this round taken
the single-`r` form at `r = 8` it would have priced the family at 4.58 ms
instead of 3.90 — still a refusal, but the error is now measured at 3.3x the
recorded band and the law should be quoted with **76%**, not 23%.

## 4. The decision

| half | calls | premium | ms | % of 5,290 ms |
|---|---:|---:|---:|---:|
| `forEachChild`'s 70 list child positions | 275,477 | 11.95 ns | **3.29** | 0.062% |
| the INV.4 edge classifiers' 145 identity tests | 219,828 | 2.75 ns | **0.60** | 0.011% |
| **FAMILY TOTAL** | **495,305** | | **3.90** | **0.074%** |

**REFUSED by 4.4x.** Three readings, all refusing:

* leading-draw dropped (the primary, round 891's prescription): **3.90 ms**
* all four draws pooled: **4.49 ms** (refused by 3.8x)
* the most generous reading available — the single-`r` `A - B` at `r = 8`, which
  §3d shows is an over-read: **4.58 ms** (refused by 3.7x)

And the measured premium is itself an **upper bound**, for a reason worth
stating: both amplifier arms fold `kindId` into a sink, i.e. the cheapest
possible loop body, where the iterator's fixed cost is maximally exposed. In
production the body is `action(e)` — a megamorphic interface call to a real
checker lambda — so the same iterator overhead is at best equally visible and
plausibly better hidden. A refusal taken against an upper bound is a refusal
with certainty (round 903's law).

### 4a. Two ceilings corrected, which is the ninth and tenth in this arc

* **`forEachChild` is #5 in the warm leaf table at 1.40% = ~74 ms. The iterator
  is 3.29 ms of it, i.e. 4.4%.** The row is a LOCATION; 95.6% of it is the
  `kindId` load, the tableswitch, the checkcasts and `action.invoke`.
* **Round 875 bounded the edge-predicate half at 44 ms** (3.32 M evaluations x
  13.3 ns). The `.any` inside it is **0.60 ms = 1.4% of that ceiling** — because
  only 219,828 of the 3.32 M evaluations reach one at all.

### 4b. Why the sibling project's −3.1% does not transfer

Nothing here contradicts it. The mechanism is identical and the premium is real
and non-zero (11.95 ns is not noise; it is 1.4x round 904's whole boxed-key
premium). What differs is **shape**: this family's population is 495 k calls
over lists averaging **1.99 elements**, with 7.0% empty and 52.4% singleton, so
the per-call fixed cost is amortised over ~2 elements and there is simply not
enough of it. A transfer audit imports a MECHANISM; it cannot import a
POPULATION, and the population is what the census exists to measure.

## 5. What was kept, and what it is not

The refactor is KEPT and the one-line fix is NOT taken. What is kept:

* `walkList` / `anyIdentical` — one home for a family that was 215 sites, so a
  future agent cannot re-open it blind, and so this document has something to
  point at.
* The 36% bytecode shrink of `forEachChild`'s three partitions (§ 1), which is
  (JIT.1) headroom on the compiler's traversal primitive.
* `--iterCensus`, `--iterAmp N`, the `itercensus` / `iteramp<N>` tiers,
  `scripts/round905-census.sh`, and `IterCensusTest`.

**Stated rather than hidden: the extraction itself is NOT priced by a warm
A/B.** It converts 70 (resp. 145) inlined loops into static calls to 58- and
65-bytecode methods, which C2 inlines at `FreqInlineSize` on a path this hot;
its expected effect is ~0 and this arc's warm band is ±1.0%, so an A/B could
not have resolved it either way. It is gated on behaviour (the suite, the
8-profile `--listAll` grid, `cost_gate.py`) and not on wall.

## 6. For the next agent

* **Do not re-open this family from the leaf table or from a sibling-project
  transfer.** It is measured: 495,305 calls, 11.95 and 2.75 ns, 3.90 ms.
* **Reusable constants**: an `ArrayList` heap-iterator loop over a ~2-element
  list costs **11.95 ns per call** more than the indexed loop; over a ~1.7-step
  identity search, **2.75 ns**. A `PassTiming.nowNanos()` pair fitted as a
  per-call boundary is **43-68 ns** here, below round 904's 76-88 — a boundary
  is a property of the ARM, not a constant of the harness.
* **The general law this round adds**: a count of SITES is not a count of CALLS.
  215 sites produced 495,305 calls, where the queue entry's projection from
  node and edge populations implied 4-6x more. Census the calls before pricing
  anything per-call — it is a counter, not a build.
