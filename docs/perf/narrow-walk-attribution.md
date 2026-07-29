# (CALL.3) — what is actually inside a monster narrowing walk

*Round 736. Fifth in the sequence `docs/perf/dispatch-table.md` (732) →
`docs/perf/spine-leave-attribution.md` (733) →
`docs/perf/call-expression-attribution.md` (734) →
`docs/perf/argument-check-attribution.md` (735) → here. Derived by
instrumentation (`NarrowSections` / `NarrowProbe`, opt-in `--narrowSections` /
`--narrowSectionsCoarse`), verified by the whole corpus suite, a byte-identical
profile `--listAll`, and an interleaved A/B. The instrumentation is
behaviour-free when off.*

> **HEADLINE — THE FIRST LANDED WIN OF THE ARC. `-4.53%` median, B wins 6/6.**
> Round 735 handed forward 394 walks of 70,037 costing 1,485 ms and named two
> numbers to measure first. Both are now measured, and they point at one line
> of code. **(i) The tail walks arrive at 1,900 flow nodes but only 214
> DISTINCT ones — a revisit factor of 8.85 against 1.48 for a typical walk.
> (ii) The revisits are not graph traversal: 53% of the whole walk population
> is `applyConditionNarrowing`, and 56% of its calls existed only because the
> intra-walk memo refused to answer.** `NarrowFlowMemo.served(id, depth)`
> required `depth <= storedDepth`, so a node reached again by a LONGER path
> recomputed its entire antecedent subtree. That condition guards exactly one
> thing — a deeper entry has less depth budget and might truncate at
> `NARROW_MAX_DEPTH` = 2000 — and that is **decidable, not approximable**: a
> stored entry now also carries the maximum depth its own subtree reached, so a
> deeper probe is answered exactly when a fresh computation from there provably
> cannot reach the cap. Measured effect: `narrowTypeFromFlow` invocations
> 1,455,915 → 659,592 (−55%), arrivals 4,759,476 → 3,500,214 (−26%) with
> DISTINCT nodes unchanged at 3,212,764, `applyConditionNarrowing` calls
> 759,784 → 333,031 (−56%), the `>= 1 ms` tail 429 walks → 230 and its arrivals
> 815,259 → 34,490. Diagnostics byte-identical; four cost counters FELL.

---

## 1. What was built

`NarrowSections` + `NarrowProbe` in `src/commonMain/kotlin/SpineDispatch.kt`,
plus boundaries inside `narrowTypeFromFlow`, on the round-733/734/735 model,
with one structural change forced by the target: **the function recurses**, so a
running-section partition of the round-735 kind is impossible. Instead:

* **`narrowTypeFromFlow` was split into a wrapper and `…Core`.** The wrapper
  brackets the OUTERMOST entry only (`narrowLiveDepth == 0`) — the population
  round 735's `>= 1 ms` tail is defined over — and the recursion calls the core
  directly, so a nested entry costs nothing extra.
* **Nested sub-measures only, never a partition.** Every row brackets a LEAF
  call (`applyConditionNarrowing`, `getUnionType`, `narrowByAssignmentRhs`,
  `narrowByAssertCall`, `narrowBySwitchClause`, `outerFlowForCapturedName`,
  `memo.putIfDeeper`) plus the fast-forward loop as a whole. Each excludes the
  recursion by construction, because the recursive call is a *separate
  statement* in every arm — so the rows are self time and sum toward the anchor.

### Counters, not timestamps, inside the arrival loop

Rounds 734/735 both measured a timestamp read at 86–89 ns. The compile makes
~4.8 M flow-node arrivals through this function, so one timestamp PAIR per
arrival would have added ~850 ms to a 2.75 s population — the probe would have
been the measurement. So the per-arrival structures are priced in **probe
steps**: a deterministic integer incremented inside the open-addressing loops of
`NarrowFlowMemo` and `NarrowSeen`. Steps are exactly what those structures cost
and the probe cannot inflate them.

**This is the round's methodological carry-forward**: when the population is
large enough that a boundary pair would dominate, price the structure in its own
deterministic unit instead of trying to calibrate the boundary away.

## 2. (i) — ARRIVALS versus DISTINCT flow nodes

Compiler profile, `narrowTypeFromFlow` only (the `FollowLoopEntry` mirror is
excluded from the census; round 735 attributed 85% of the tail to `WK_NARROW`).

| population | arrivals | distinct | revisit factor | per walk |
|---|---:|---:|---:|---|
| all 45,566 walks | 4,759,476 | 3,212,764 | **1.48** | 104 / 70 |
| the 429 `>= 1 ms` walks | 815,259 | 92,090 | **8.85** | 1,900 / 214 (max 19,515 / 823) |

**The answer to round 735's "13× more arrivals": a typical walk revisits almost
nothing (1.48), and a tail walk revisits everything nine times.** The tail is
not a bigger graph — 214 distinct nodes is a perfectly ordinary function — it is
the SAME small graph walked over and over.

### The memo, split by why a probe did not serve

| population | served | miss: no entry | miss: entry too shallow |
|---|---:|---:|---:|
| all walks | 290,011 | 3,837,880 | **631,585** |
| `>= 1 ms` | 118,224 | 337,657 | **359,378** |

**In the tail, "an entry exists but was stored at a shallower depth" (359,378)
outnumbers both the serves (118,224, 3×) and the genuine first sightings
(337,657).** And it is concentrated exactly where the money is:

| flow-node kind | served | no entry | too shallow | (tail) too shallow |
|---|---:|---:|---:|---:|
| `FlowCondition` | 115,917 | 333,031 | **426,753** | 254,105 |
| `FlowBranchLabel` | 144,875 | 124,970 | **178,150** | 105,492 |
| `FlowAssignment` | 9,710 | 580,155 | 9,472 | 6,066 |
| `FlowCall` | 1,228 | 2,743,997 | 1,152 | 589 |

`FlowCall` is 57% of all arrivals and essentially never has an entry — those are
the fast-forward loop's pass-through nodes, which are never memoized because
only the node the loop BREAKS at is stored. They are also the cheap ones. The
depth condition, by contrast, rejects serves almost exclusively at the two
EXPENSIVE kinds.

## 3. (ii) — the per-arrival split

Raw ms (probe-inflated; sound for RELATIVE attribution only), whole compile.

| region | ms | calls | ns each |
|---|---:|---:|---:|
| **the whole walk (anchor)** | **2,751** | 45,566 | 60,387 |
| — `applyConditionNarrowing` | **1,412** | 759,784 | 1,858 |
| — the fast-forward loop (per invocation) | 623 | 1,455,915 | 428 |
| — `getUnionType` at a branch label | 129 | 303,120 | 427 |
| — `narrowByAssignmentRhs` | 128 | 26,235 | 4,897 |
| — `memo.putIfDeeper` | 86 | 1,165,904 | 74 |
| — `narrowBySwitchClause` | 41 | 10,849 | 3,794 |
| — `narrowByAssertCall` | 38 | 4,980 | 7,773 |
| — `outerFlowForCapturedName` | 7 | 54,460 | 145 |
| residue (dispatch, `seen`, the `when`) | ~287 | | |

**`applyConditionNarrowing` is 51% of the entire narrowing population.** That is
the answer to round 735's "6.3× more expensive arrivals": the tail's arrival MIX
is different. `FlowCondition` is **41% of tail arrivals against 18% overall**,
and `FlowBranchLabel` 22% against 9%, while the cheap `FlowCall` pass-throughs
fall from 57% to 19%. A tail arrival is expensive because it is far more likely
to be a condition node, and a condition node costs 1,858 ns.

### The sub-population that made the fix obvious

**726,477 of the 759,784 `applyConditionNarrowing` calls (95.6%) returned their
INPUT type unchanged** — the condition said nothing about the walked reference.
That looks like an invitation to add a cheap "does this condition mention the
name" pre-test, and it is a trap: those 726,477 identity calls cost **689 ms
raw**, i.e. **949 ns each**, while the 33,307 calls that actually narrow cost
**21,708 ns each**. See § 6 — the pre-test is priced and rejected.

## 4. The fix, and why it is sound rather than merely green

`NarrowFlowMemo` gains a third parallel array `his` — the maximum `depth` the
stored subtree itself reached — maintained by a `narrowWalkHiDepth` field on
exactly the same save/raise/restore discipline as the existing
`narrowWalkTruncated`. `served` becomes:

```
depth <= storedDepth  ||  depth + (hi - storedDepth) < maxDepth
```

The argument, in full:

1. **`depth` influences the result through exactly one channel.** Grep the
   walker: `depth` is used in the `depth >= NARROW_MAX_DEPTH` test, in the two
   memo calls, and as `depth + 1` in the recursion. Nothing else reads it. So
   two computations of the same node at different depths can only differ if one
   of them hits the cap.
2. **Only NON-truncated results are ever stored** (`if (!narrowWalkTruncated)`,
   pre-existing). A stored entry's subtree therefore completed with no depth
   cap, no cycle bail and no visit-budget exhaustion.
3. **`hi - storedDepth` is that subtree's height**, so a fresh computation
   starting at `depth` reaches `depth + height`. When that is `< maxDepth` it
   provably cannot trip, and by (1) it provably produces the stored value.
4. **A memo shortcut must not shrink an ancestor's recorded height.** When a
   probe is served, the caller folds `depth + lastHitHeight` into its own
   `narrowWalkHiDepth`, so the height an ancestor records is the height a FRESH
   recomputation would reach, not the shortcut one. Without this the disjunct
   would be unsound under nesting; it is the one non-obvious part of the change.

The two truncation sources that are NOT depth-dependent (the 1,000,000-visit
budget and the `seen` cycle bail) are untouched, and the existing
shallower-direction serve already had the identical exposure to both — this adds
no new class of risk. Measured on the profile: **`maxDepth` reached is 249 of
2000, and depth / budget / cycle truncations are 0 / 0 / 0.** (The cycle bail is
structurally unreachable in this walker: the only back-edges in the flow graph
are loop back-edges, and `narrowTypeFromFlow` returns the declared type at
`FlowLoopLabel` without recursing, so the traversal is a DAG.)

The `FollowLoopEntry` mirror gets the identical change, per the walker-mirror
invariant.

## 5. Effect

| counter | before | after | |
|---|---:|---:|---|
| `narrowTypeFromFlowCore` invocations | 1,455,915 | 659,592 | **−55%** |
| flow-node arrivals | 4,759,476 | 3,500,214 | **−26%** |
| DISTINCT flow nodes | 3,212,764 | 3,212,764 | **±0** |
| revisit factor | 1.48 | 1.08 | |
| `applyConditionNarrowing` calls | 759,784 | 333,031 | **−56%** |
| `getUnionType` at branch labels | 303,120 | 124,970 | −59% |
| `memo.putIfDeeper` | 1,165,904 | 534,319 | −54% |
| walks `>= 1 ms` | 429 | **230** | |
| arrivals in those walks | 815,259 | **34,490** | **−96%** |
| tail revisit factor | 8.85 | 1.34 | |
| `seen` probe steps | 8,805,183 | 5,503,640 | −37% |

**DISTINCT is unchanged to the node.** That is the shape of a correct
memoisation change: the same work is discovered, it is simply not repeated.

Interleaved A/B (`scripts/ab-interleaved.sh`, 6 pairs; A and B differ ONLY in
the `served` condition — the probe scaffolding is identical on both sides, and
both report 46 errors on every run):

```
pair 1: A=31823ms  B=30519ms  delta=-1304ms
pair 2: A=32754ms  B=30260ms  delta=-2494ms
pair 3: A=31009ms  B=29467ms  delta=-1542ms
pair 4: A=31364ms  B=31135ms  delta= -229ms
pair 5: A=33251ms  B=32488ms  delta= -763ms
pair 6: A=32763ms  B=32531ms  delta= -232ms
MEDIAN: A=32288ms  B=30827ms  delta=-1462ms (-4.53%)   B wins 6/6
```

**−4.53% median, 6/6 win rate — outside the ±2% drift band by 2.3×.** The
probe scaffolding itself ships (same pattern as `ArgSections`) and is NOT
included in that A/B; its production cost is a static read plus a not-taken
branch per arrival and per invocation, estimated at well under 50 ms (0.16%).

## 6. Measured dead-ends — record these so they are not re-derived

1. **A "does this condition mention the name" pre-test in front of
   `applyConditionNarrowing`. REJECTED ON PRICE.** 95.6% of the calls are
   identity, which reads like a huge prize, but the identity calls are the CHEAP
   tail: 949 ns each against 21,708 ns for the calls that narrow. The whole
   identity population is **689 ms raw** before the change and **468 ms raw**
   after it — ~410 ms net of probe, i.e. 1.3% of the compile, INSIDE the ±2%
   band, before paying for the pre-test itself. This is § 0's law appearing for
   a fourth time in a shape that is not a cache at all: *the population you can
   skip cheaply is the population that was already cheap*.
2. **Memoising the fast-forward chain's pass-through nodes.** `FlowCall` is 57%
   of all arrivals and 2,743,997 of them find no memo entry, which looks like
   the biggest single miss population in the table. It is also the cheapest: the
   whole fast-forward loop is 623 ms over 1,455,915 invocations = ~131 ns per
   arrival, so the entire revisit share of it is ≲120 ms. Storing the chain head
   would cost an extra `putIfDeeper` (74 ns) per invocation to save it. Not a
   lever.
3. **"The monsters are tripped / budget-exhausted / memo-servable walks"** —
   all three were disproved in round 735 and are re-confirmed here from the
   other side: trips 0, budget truncations 0, cycle bails 0, and the LIVE
   inter-walk memo is irrelevant because the waste was INSIDE a single walk.
   **The lesson is that "the memo cannot reach them" (round 735, correct about
   the inter-walk memo) hid a memo failure one level down.** When a cache is
   measured as not helping, check whether a DIFFERENT cache at a different scope
   is the one failing.

## 7. Where this leaves § 0.1's staged plan

Stage 4 (flow narrowing) has now paid once. What is left of it:

* Narrowing's remaining cost is `applyConditionNarrowing`'s **33,307
  genuinely-narrowing calls at 21,708 ns each ≈ 723 ms** plus the walk
  scaffolding. That per-call figure is the largest unattributed number this arc
  has produced and is the natural next probe — but note it is a 723 ms
  population, i.e. 2.4% of the compile, only just outside the band.
  **SUPERSEDED, round 755 (`docs/perf/condition-narrowing-attribution.md`): the
  population is now 21,970 calls at 20,085 ns = 441 ms (1.6%) — the count fell
  34% while total calls rose 2.5% — and 80% of it is one callee,
  `narrowByCallPredicate`, with the dispatcher's own residue at 2%. Nothing
  landed; the whole population is smaller than one A/B pair's noise. § 6's
  rejected pre-test is also UNSOUND, not merely in-band.**
* The `>= 1 ms` tail is **gone as a distinct phenomenon** (230 walks, 34,490
  arrivals, revisit factor 1.34). There is no longer a monster population to
  attack.
* `narrow.walks` is unchanged at 70,037 — this round removed repeated work
  INSIDE walks, not walks. The stage-4 note that "the rest needs tsc's shape (a
  flow type computed once per reference and carried IN the type)" still stands
  for the walk COUNT.

## 8. Verification

* Full corpus suite: **12,910 tests, 0 failures, 3 skipped** (12,899 + 4 new
  `IntKeyMapTest` pins + 7 new `NarrowMemoDepthTest` pins).
* Compiler profile `--listAll`: **byte-identical**, A and B, 46 errors, sorted
  lines diff clean.
* `scripts/cost_gate.py`: `output.errors`, `spine.nodes`, `narrow.walks`,
  `typeOfExpr.distinct`, `mapped.*` all **+0.00%**; four counters FELL and were
  rebaselined in the same commit — `typeNode.cacheable` −10.6%,
  `typeNode.cacheHits` −14.6%, `globals.lookups` −8.4%, `globals.misses` −8.5%
  (all downstream of the 56% cut in `applyConditionNarrowing`, which resolves
  names and type nodes), `typeOfExpr.calls` −0.04%, `narrow.memoServed` −1.0%.
* Interleaved A/B: § 5.

**On the two negative controls that failed first:** `NarrowMemoDepthTest`
originally pinned TS2339 for `x.toFixed(2)` on a `string`-narrowed reference and
for a loop-widened receiver. Both failed — and both fail identically on the
BASELINE build, so they are pre-existing emitter gaps, not regressions. They
were rewritten onto TS2345 at a call argument, which is the code path this round
actually profiles. *A failing negative control must be run against the baseline
before it is believed or dismissed.*

## 9. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
# the census + the per-arrival split
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --narrowSections build/bench/tsc-project-*
# the calibration counterpart (anchor only; subtract and divide by the extra
# boundary count the ON run's own `calls` array reports)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --narrowSectionsCoarse build/bench/tsc-project-*
```

**The box must have ≥3 GB free before a `-Xmx4g` profile run.** One run in this
round was taken at 239 MB available and reported the walk anchor at 83,074 ms
against a true 2,751 ms — a 30× inflation. The COUNTERS in that run were
byte-identical to the clean one, which is exactly why this round's decisive
numbers are counters.
