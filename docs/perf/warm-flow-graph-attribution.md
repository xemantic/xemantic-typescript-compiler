# (WARM.11) — the WARM attribution of `FlowGraphBuilder.build`, and the second walk nobody had named

*Round 864, 2026-08-09. Twenty-first in the sequence `dispatch-table.md` (732) →
… → `bind-attribution.md` (801) → `warm-spine-attribution.md` (847) →
`narrowed-any-opening-price.md` (854) → `warm-tail-attribution.md` (859–862) →
`whole-program-regex-census.md` (863) → here. This document is to round 801 what
`warm-intra-handler.md` is to the cold section tables: the same region, re-taken
inside a JIT-warm process — except that this time the regime change did not
merely re-order the rows, it exposed a row that the cold partition had folded
into a residue and called "the flow walk".*

> **HEADLINE.**
>
> **(1) THE 4.20% "FLOW WALK" IS TWO WALKS, AND THE SECOND ONE IS NOT A FLOW
> WALK.** Round 859 measured `FlowGraphBuilder.build`'s residue —
> `BIND_FLOW` minus its three B464 collectors — at **316.7 ms = 4.20% of a warm
> rebuild**, the largest single region outside `checkSpine`, and round 801 had
> closed the region cold without asking what the residue contains. Partitioned:
> the flow-MINTING walk is **196.3 ms** and the `FlowGraph` constructor's nodeId
> side table is **163.5 ms**, of which **162.3 ms** is a *second whole-tree
> walk* that visits **876,324** nodes to ask a `(pos,end)`-keyed map about each
> one. § 2.
>
> **(2) 70% OF THOSE QUESTIONS HAVE NO ANSWER, AND THE ONES THAT DO SERVE 168
> QUERIES.** Only **264,104** of the 876,324 slots ever receive a flow node,
> from **262,404** recorded keys. The table has ONE reader, `flowAt`, which
> falls back to that same map lookup for any node it does not own — so filling
> it from the RECORDED nodes alone is exactly equivalent, and round 788's
> question ("deleted or moved?") is answerable by census rather than by
> argument: of **176,935** `flowAt` calls per compile, **168** take the
> fallback. **Ratio 0.0003, not 1.000.** § 3.
>
> **(3) LANDED: 172.99 → 58.63 ms = 114.4 ms = 1.665% of a warm rebuild, a
> 66.1% fall in the row.** Second-draws-only, the conservative reading, is
> 108.1 ms = 1.573%. Gates: suite 14,094/0/3, `cost_gate.py` +0.00% on all 20
> counters, `huge_methods.py --fail-over 0` 0 over the limit, the 8-profile grid
> `added=0 removed=0` in both directions, and the compiler profile's emit tree
> byte-identical at 78 files. § 4.
>
> **(4) A PRICED NEGATIVE IN THE SAME REGION: `mutableMapOf()` → `HashMap()` on
> the flow map measures NOTHING** — `−8.9 ms` all-draw and `+10.5 ms` on second
> draws, **the two readings disagreeing in sign**. 262,404 puts and as many gets
> is not enough for `LinkedHashMap`'s `afterNodeInsertion` to clear this row's
> draw spread. Reverted rather than kept. § 6.
>
> **(5*) ROUND 865 ADDED § 9: the minting walk's own produced-vs-consumed
> census. 52.3% of every flow node this compiler mints is never read by any
> consumer — and it is a PRICED NEGATIVE, because only 22.0% of the walk sits
> in containers nothing reads (43 ms = 0.63% warm, under the floor) and the
> failure direction there is inverted from (3)'s: a missing flow node is a
> false positive, not a fallback.**
>
> **(5) SO CLAUDE.md's "`Binder.bind` IS MEASURED AND CLOSED — DO NOT RE-OPEN
> IT" WAS TRUE OF WHAT ROUND 801 MEASURED AND FALSE OF THE REGION.** The cold
> partition stopped one level short and named its remainder after the thing it
> expected to find. § 7.

---

## 1. How this was measured

One binary, one profile (`build/bench/tsc-project-637d5746` — 78 files, 46
errors, 9,977,097 source characters), `--noEmit` throughout, this box (8 cores,
15.6 GB, zero swap). `scripts/round864-warm-flow.sh`.

**Round-851 order:** every gradle-invoking step before the daemon stop — build,
the full suite, `cost_gate.py`, `huge_methods.py --fail-over 0`, and the
classpath through `scripts/lib/dep-classpath.sh` (round 858) — then
`./gradlew --stop` plus a bracket-pattern `pkill -f 'KotlinCompile[D]aemon'`,
then the first sample. The box was not touched while the script ran (round 774).

**The positive control (round 853), in its sharp form.** The script aborts
unless the module's main class dir holds `MainKt`, the test dir holds
`BenchMainKt`, **and `javap` finds `FrontEnd.addFlowIndexCensus`** — a member
that did not exist before this round, so a leftover class directory cannot
satisfy it. It held: 653 classes before the change, 655 after.

**The harness:** `BenchMain <proj> 3 8 frontend,frontend`, two processes, two
instrumented draws each — 3 warm-up plus 8 measured rebuilds per process before
any probe runs, and all 8 instrumented rebuilds across both arms answered
78 files / 46 errors.

**Boundary cost.** These are per-FILE spans: 4 timestamp pairs × 123 graphs =
492 pairs against a ~360 ms row. At round 850's warm 97–202 ns that is
48–99 µs, i.e. **0.03%** of the row. Round 734's differential calibration is for
per-NODE probes and is neither needed nor claimed here — which is also why the
`FLOW_BIND` + `FLOW_INDEX` residue can be read as a partition check rather than
as a boundary artefact.

## 2. The partition, and it is exhaustive by construction

`build()` is four statements — set the text, clear two per-file caches, mint the
start node, walk the statements, construct the graph — so `FLOW_BIND` and
`FLOW_INDEX` abut and cover it. Measured residue: **189 µs of 359,962 µs
(0.05%)**, and **158/247/284 µs** in the other three draws.

Compiler profile, warm, process 1 draw 2 (the same shape in all four draws):

| row | warm ms | % of the warm wall | population |
|---|---:|---:|---|
| `bind` (all program files) | 460 | 6.9% | 123 files |
| — `bindStatements` | 14 | 0.2% | 123 |
| — `bindLexicalScopes` | 89 | 1.3% | 876,201 node pops |
| — **`FlowGraphBuilder.build`** | **359.9** | **5.4%** | 123 graphs |
| — — **the flow-MINTING walk** | **196.3** | **2.9%** | 236,587 flow nodes |
| — — — B464 `collectReassignedNamesInRange` | 39 | 0.5% | 2,014 |
| — — — B464 `collectClosureLocalNames` | 1 | 0.0% | 2,014 |
| — — — B467 `collectEnclosingVarDecls` | 7 | 0.1% | 2,014 |
| — — **the `FlowGraph` side table** | **163.5** | **2.4%** | 123 |
| — — — the nodeId side-table walk | **162.3** | **2.4%** | **876,324 nodes** |
| — — — the closure-interval arrays | 1.2 | 0.0% | 2,014 closures |
| — — residue (partition check) | 0.19 | — | |

**What round 859 called "the flow WALK (residue), 316.7 ms" is 196 ms of flow
walk and 163 ms of something else**, and the something else is
`FlowGraph`'s constructor: a `forEachChild` traversal of the entire tree that,
for every node, computes a `(pos,end)` `Long` key, boxes it, and asks the flow
map. The INV.2(b) side table (round 495's pilot) is what `flowAt` reads; nothing
before this round had ever timed its construction, because the cold partition
(round 801 § 4) subtracted the three collectors from `BIND_FLOW` and named the
remainder after the walk it expected to be there.

**The census is what makes the row readable**, in round 758's converse
direction — a total with no population attached cannot be compared to anything:

```
flow map census: recordFlow calls 262404 -> 262404 distinct keys (0 aliased/overwritten);
                 side-table walk visited 876324 nodes, 264104 answered (30%)
```

Three things follow. The walk asks **876,324** questions and **612,220** of them
answer `null`. `recordFlow` writes **262,404** keys and **none** of them
collides with another — so the extent-ALIASING the `Long` key can produce never
happens on the WRITE side here. And it does happen on the READ side, exactly
**1,700** times: 264,104 nodes are answered from 262,404 keys, so 1,700 nodes
receive a flow node recorded for a same-extent sibling or wrapper.

## 3. Round 788's question, asked BEFORE the fix

The side table has **one** reader:

```kotlin
fun flowAt(node: Node): FlowNode? {
    val id = (node as NodeBase).nodeId
    if (id >= 0 && id < nodeById.size && nodeById[id] === node) return flowById[id]
    return nodeToFlow[nodeKey(node)]          // the fallback
}
```

Fill the table from the **recorded** nodes only, and take any node of the file:

* **recorded** — both fills store `nodeToFlow[nodeKey(node)]`, read from the
  FINISHED map, so a key written twice lands on the same final value either way;
* **in the tree but not recorded** — the whole-tree walk stored
  `nodeToFlow[nodeKey(node)]`, which is `null` unless a recorded node shares its
  extent; the recorded-node fill leaves the slot empty and `flowAt` performs
  that identical lookup itself;
* **not in this tree** — neither fill touches it and `flowAt` took the map path
  before and takes it now.

So the two fills differ **only in where the map lookup happens for the second
class**, and the whole question is how many queries land there. That is a
census, not an argument, and it was taken on the unchanged binary:

```
flowAt census: calls 176935, of which in-tree-but-null 168 and map-fallback 0
```

**168.** Six hundred and twelve thousand build-time lookups exist to spare
**168** query-time ones. Produced-to-consumed is **0.0003**; round 801's
"1.000 means MOVED" is nowhere in sight, and this is a DELETION.

After the change the same census reads `in-tree-but-null 0 and map-fallback
168` — the population did not move, it changed which side of `flowAt` pays for
it, and the count is identical to the prediction because it is the same set of
nodes.

## 4. What landed, and what it measured

The `FlowGraphBuilder` keeps the nodes `recordFlow` wrote (in write order,
duplicates included — the fill re-reads the map per entry, so de-duplicating
would cost a hash per record to save nothing) and the `FlowGraph` constructor
fills its arrays from that list. `--flowIndexLegacy` restores the whole-tree
walk in the same binary: the A/B's other arm, the differential pin's oracle and
the ablation's target (round 795 — build the verify flag so it doubles as the
instrument).

| arm | p1 d1 | p1 d2 | p2 d1 | p2 d2 | mean | d2-only |
|---|---:|---:|---:|---:|---:|---:|
| whole-tree walk (before) | 192.48 | 162.33 | 180.66 | 156.48 | **172.99** | 159.41 |
| recorded-node fill (after) | 65.49 | 51.48 | 66.31 | 51.21 | **58.63** | 51.35 |

**Saving 114.36 ms = 1.665% of the before arm's warm wall (6,869 ms), a 66.1%
fall in the row.** On second draws only — the conservative reading, since round
846's first-draw-is-slowest law holds **4/4** here — it is **108.06 ms =
1.573%**. The census flips as predicted: **876,324 nodes at 30% answered →
262,404 at 100%**.

**The neighbouring MINT row also reads ~20 ms lower after the change, and that
is NOT claimed.** The change can only ADD work there (one list append per
recorded node), so the direction is wrong for a causal reading; it is either
draw noise — the mint row's first-draw values span 283–343 ms — or the
young-generation relief of 612,220 fewer boxed `Long` keys landing somewhere
else. Only the row the round targeted is quoted, which is round 854's rule one
level in.

**Gates.** Suite 14,090 → **14,094 / 0 failures / 3 skipped** (a real XML parser
over all four modules). `cost_gate.py` **+0.00% on all 20 counters**.
`huge_methods.py --fail-over 0` **0 over the limit**, 655 classes. The
8-profile grid **`added=0 removed=0` in BOTH directions on every profile**
(46 × 7 and 94 for harness, no truncated or empty capture), and the compiler
profile's **emit tree byte-identical at 78 files**.

**The grid's two arms are ONE binary selected by `--flowIndexLegacy`**, not two
class directories. That is deliberate and stronger here: the flag picks the
pre-864 code path inside the *committed* binary, so a stale or mis-pointed class
dir (round 853) cannot make the arms agree, and there is no build-to-build
variance between them. The binary's own freshness is checked separately, by a
member this round added.

## 5. The ablation — four faults, one at a time (round 807)

`scripts/round864-ablate.sh`, 120 pins per arm (this round's four plus the
neighbouring INV.2(b), B464 and mode-restore classes).

| arm | the fault | RED | uniquely its own |
|---|---|---:|---|
| **M1** | `--flowIndexLegacy` made INERT — both arms are the new fill | **1** | `the two fills visit different populations - the arm is not inert` |
| **M2** | the fill reads the WRONG KEY (`nodeKey(pos, pos)`) | **10** | `every node the recorded-node fill visits is answered by the map` |
| **M3** | the fill also claims the recorded node's PARENT — an entry present and wrong | **2** | — (a strict subset of M2's set) |
| **M4** | `recordFlow` stops listing `Identifier` nodes | **0** | — (expected; see below) |

**M1 is the round's most important arm** and it is the reason the non-vacuity
pin exists: with the flag inert, the differential compares a binary against
itself and passes forever. That is round 807's blind-pin mechanism, and one pin
sees it and nothing else does.

**M2 reaches three neighbouring classes** — `FlowScanEquivalenceTest`,
`NarrowableRootsPreTestTest`, `Inv2FlowLookupTest` — which is worth recording
for its own sake: a wrong flow answer is visible to the narrowing pins, so the
region is protected by more than this round's own tests.

**M3 has no uniquely-its-own failure and is reported as such.** It is caught
only by the two differentials, which are the general net; they attribute nothing
and are not claimed as coverage. This is round 863's framing of its own battery,
and the honest reading is that the *dangerous* direction (an entry that is
present and wrong) is covered by a net rather than by a named pin.

**M4 is GREEN on purpose, and its greenness is the safety argument.** Dropping
entries from the list cannot change an answer, because a missing slot degrades
to `flowAt`'s map fallback — the property the whole design rests on. The arm
demonstrates it rather than asserting it, and it also says plainly what the
list's correctness obligation is *not*: completeness is a speed property here,
not a correctness one. **The correctness obligation is the other direction** —
never put a slot in for a node `recordFlow` did not write.

## 6. What did NOT work

* **`mutableMapOf()` → `HashMap()` on the flow map. ZERO, with the sign
  undecided.** The map takes 262,404 puts and as many gets per compile and its
  iteration order is read by nothing (the only non-`[…]` access in the repo is
  `.size`), so CLAUDE.md's round-483 rule applies on paper. Measured over the
  same 2 × 2 draws: the mint row reads **245.2 ms** (LinkedHashMap) against
  **236.2 ms** (HashMap) all-draw, and **177.0** against **187.5** on second
  draws — **−8.9 ms one way and +10.5 ms the other**. A row whose first draws
  span 261–343 ms cannot price a per-put field write. **Reverted**, because an
  unmeasurable change to a narrowing-critical structure buys risk and nothing
  else; round 801 kept its own zero-effect change only because it was one arm of
  a verifier, and this one is not. Recorded as the third measured instance of
  *an operation count is not a cost*.
* **The M3 arm, first attempt, did not compile** — `NodeBase` is not `Node`, so
  a parent cast to `NodeBase` cannot be stored back into an `Array<Node?>`. A
  compile error is a result, but it is not the fault the arm was written to
  inject, and an ablation batch that records it as one would be crediting a pin
  with discrimination it was never tested for (rounds 855/856, in miniature).
* **A per-node partition of the MINTING walk was not attempted, and the reason
  is arithmetic.** It visits ~857,000 nodes; at round 850's warm 97–202 ns a
  single timestamp pair per node is 83–173 ms against a 196 ms row — the
  instrument would BE the measurement. Round 756's hand-back shape does not help
  (it reduces attribution error, not boundary count). Anything inside that walk
  has to be priced by counters plus an arm, as § 6's first bullet did.

## 7. The bound — what the region is now, and the amended verdict

| | warm ms | % warm | status |
|---|---:|---:|---|
| `bindStatements` | 14 | 0.19% | nothing to find |
| `bindLexicalScopes` | 89 | 1.15% | one iterative whole-tree walk, 876,201 pops = 102 ns/pop |
| B464 collectors | 47 | 0.6% | round 801's, unchanged |
| the flow-MINTING walk | ~196 | ~2.9% | 236,587 flow nodes at **0.83 µs each**; no sub-structure a probe can reach (§ 6) |
| the nodeId side table | 163 → **59** | 2.4% → **0.9%** | **this round** |
| the closure-interval arrays | 1.2 | 0.02% | (ENGINE.2b), already cheap |

**CLAUDE.md said "`Binder.bind` IS THREE STATEMENTS AND IS NOW MEASURED AND
CLOSED — do not re-open it looking for a lever". That sentence was true of what
round 801 measured and false of the region**, and the mechanism is worth naming
because it is not a regime effect: round 801's level-2 partition subtracted the
three B464 collectors from `BIND_FLOW` and **named the remainder after the walk
it expected to find there**. A residue is not a measurement of whatever you then
call it — round 758 states exactly that about `checkSpine`'s "dispatch
machinery, 42%" — and here the residue was 57% the thing it was named after and
43% a different walk in a different class.

What is left is the minting walk at ~2.9%, which is a single-pass construction
of a graph the checker requires, has no measurable sub-structure, and cannot be
partitioned at a price below its own size. **On that, round 801's verdict now
holds for a measured reason rather than by default.**

## 9. (WARM.12) round 865 — the produced-vs-consumed census of the MINTING walk, and why it is a priced negative

*Round 865, 2026-08-09. § 6 above closed the minting walk to timing with an
arithmetic argument; this section opens it with counters, which is round 736's
escape and the play that produced both of the last two wins.*

> **HEADLINE.** **52.3% of the flow nodes this compiler mints are never looked
> at by any consumer** — 123,880 of 236,464 on the compiler profile — and
> **that is not a prize.** The never-read mass is not concentrated in anything
> a builder could decline to build: **0.3%** of it is in files nothing ever
> reads, **22.0%** is in function-like containers nothing ever reads, and the
> remaining 30% is interleaved with read nodes inside containers that ARE read,
> where the chain makes it unskippable. The whole implementable population is
> therefore **22.0% of the walk = 43 ms = 0.63% of a warm rebuild**, below this
> arc's 1% floor, and it would have to be bought with a LAZY per-container flow
> graph whose failure direction is inverted from § 5's: a missing side-table
> entry degrades to a correct fallback, a missing FLOW NODE is a false positive.
> **Priced negative; nothing was changed in the flow graph.**

### 9.1 The instrument

`--flowCensus` (`FlowCensus`, `SpineDispatch.kt`) registers every `nextId++` in
`FlowGraphBuilder` against the FILE and the function-like CONTAINER it was
minted in, and marks every flow node any consumer in the checker ever looks at,
over eight channels: the two narrowing walkers, `isAssignedAtFlow`,
`isPostSuperFlowNode`, `evolvingArrayWalkTrips`, `walkAliasedConditionInit`,
`FlowGraph.flowAt`'s hand-out, and the container/closure-start readers. Off (the
default) every hook is one static read and a not-taken branch, and nothing is
retained.

Four laws it is built to obey, each of which has cost this arc a round:

* **Keyed on the MINTS, not on what survives** (round 829). The parts sum to
  `FrontEnd.flowNodesBuilt` exactly: `236,464 = 236,587 − 123`, the 123 being
  one placeholder `FlowStart` per graph that the builder's field initializer
  mints and `build` immediately overwrites. A pin asserts that identity, so a
  mint site added later without a registration reddens instead of silently
  shrinking the denominator.
* **Keyed on boundaries no caller short-circuits** (round 849). Each hook sits
  where its consumer READS the node, above that consumer's own memo/budget/seen
  guards.
* **`FlowNode.id` restarts at 0 in every file**, exactly as `nodeId` does
  (round 787), so nothing is keyed on it: the inventory is per file and the
  touched set is an IDENTITY set. That is sound *only* because the `FlowNode`
  implementations are plain classes and not data classes — round 471's
  `HashSet<Node>` hazard is about data-class `hashCode()` deep recursion, and
  `FlowBranchLabel`'s mutable antecedent list would be exactly that hazard the
  day anyone adds `data` to these declarations.
* **A count is not a cost** (round 732) — hence the per-container AST-VISIT
  axis beside the node counts. It is the only column that may be read as a
  share of the walk, and § 9.3 is why that distinction decides the round.

### 9.2 The census (compiler profile, `--noEmit`, 123 graphs)

```
files 123 (of which declaration files 45)   minted 236464   read 112584   never read 123880 (52.3%)
```

| kind | minted | read | never read | read % | of which in a `.d.ts` (minted/read) |
|---|---:|---:|---:|---:|---|
| `FlowStart` | 11,668 | 5,730 | 5,938 | 49.1% | 75 / 0 |
| **`FlowUnreachable`** | **17,161** | **0** | **17,161** | **0.0%** | 0 / 0 |
| `FlowBranchLabel` | 37,695 | 13,564 | 24,131 | 35.9% | 2 / 0 |
| `FlowLoopLabel` | 1,295 | 910 | 385 | 70.2% | 0 / 0 |
| `FlowAssignment` | 45,511 | 28,450 | 17,061 | 62.5% | 102 / 0 |
| `FlowCondition` | 64,655 | 37,491 | 27,164 | 57.9% | 4 / 0 |
| `FlowSwitchClause` | 5,970 | 2,008 | 3,962 | 33.6% | 0 / 0 |
| `FlowCall` | 52,509 | 24,431 | 28,078 | 46.5% | 0 / 0 |
| **`FlowArrayMutation`** | **0** | 0 | 0 | — | 0 / 0 |
| **total** | **236,464** | **112,584** | **123,880** | **47.6%** | 183 / 0 |

Consumers, by channel (touch CALLS, so repeats included):

| channel | calls |
|---|---:|
| `narrowTypeFromFlow` | 991,970 |
| `walkAliasedConditionInit` | 219,920 |
| `FlowGraph.flowAt` (hand-out) | 176,767 |
| `…FollowLoopEntry` (the mirror) | 64,959 |
| container / closure starts | 1,538 |
| `isAssignedAtFlow` | 1,182 |
| `isPostSuperFlowNode` | 0 |
| `evolvingArrayWalkTrips` | 0 |

Three by-products worth recording on their own account:

* **`FlowArrayMutation` is minted NOWHERE.** The class exists, the sealed `when`
  arms that handle it exist in six walkers, and `grep` finds no constructor call
  outside its own declaration. It is dead weight in the type hierarchy, not in
  the compile.
* **`FlowUnreachable` is minted 17,161 times and read ZERO times.** Not "rarely"
  — exactly zero, over 78 program files.
* **Declaration files mint 183 nodes in total and none is ever read.** The
  intuition that we build flow graphs for `.d.ts` files for nothing is correct
  and worth **0.08%** of the mints.

### 9.3 The decision, and the arithmetic that makes it a negative

The question is not how many nodes are unread but how much of the WALK could
have been skipped, and those differ by construction: a flow node minted inside a
container the checker does read is on somebody's antecedent chain and cannot be
declined individually. So the census reports the population three ways.

| candidate rule | population | share of mints | share of the WALK |
|---|---:|---:|---:|
| skip a FILE whose graph is never read | 52 of 123 files | 885 nodes, 0.3% | — |
| skip a CONTAINER whose flow is never read | 5,652 of 11,715 | 52,248 nodes, 22.0% | **107,985 of 490,565 = 22.0%** |
| skip every never-read NODE (not implementable) | — | 123,880, 52.3% | — |

**The prize is at most 22.0% of the 196.3 ms minting row = 43 ms = 0.63% of a
warm rebuild**, and that is a *perfect-oracle* bound: it assumes the container
set is known before the walk, which is the one thing a single forward pass
cannot know. The 52.3% headline is worth 1.5% and is not reachable by any rule.

Two further reasons not to spend the round buying it:

* **What is left over is ALLOCATION, and allocation is not a cost here.** Every
  never-read node inside a read container costs exactly one object; round 801
  measured the removal of 367,189 `String` allocations at **0 ms**, and round
  864 § 6 measured a per-put field write on this same map at −8.9/+10.5 ms with
  the sign undecided. The 17,161 unread `FlowUnreachable`s and the 24,131 unread
  `FlowBranchLabel`s (each carrying an eagerly allocated antecedent list) are
  that same class of non-prize.
* **The failure direction is INVERTED from § 5's.** Round 864's M4 arm
  established that a MISSING side-table entry degrades to `flowAt`'s map
  fallback — completeness there is a speed property. For the flow GRAPH itself
  the opposite holds: a missing flow node makes `flowAt` answer `null`, the
  reference is not narrowed, and the compiler emits a FALSE POSITIVE. So laziness
  here may not omit; it must defer and then build, and the deferral has to
  reconstruct the enclosing flow at the container's definition point
  (`FlowStart.outerFlow`) before it can build anything. Round 855's refutation
  applies to the obvious shortcut: a per-file, name-keyed "could this root ever
  be narrowed" pre-filter refuses **0 of 14,117** openings, because every
  declaration mints a `FlowAssignment` whose subtree contains the declared name.

**Decision: priced negative, stop.** The instrument lands; the flow graph is
untouched.

### 9.4 What the round tried to measure and could not

**The probe's own cost is below this row's measurement floor, and both readings
disagree in sign** (`scripts/round865-probe-cost.sh`, two class dirs in a
rotated interleave `before, after, after, before`, two instrumented draws each):

| arm | draw 1 | draw 2 | draw 3 | draw 4 | all-draw mean | second draws |
|---|---:|---:|---:|---:|---:|---:|
| before (no census) | 300.80 | 211.42 | 236.31 | 191.22 | **234.94** | 201.32 |
| after (census, flag off) | 335.88 | 188.39 | 332.88 | 187.88 | **261.26** | 188.14 |

All-draw the instrumented arm reads **+26 ms** and on second draws **−13 ms** —
the same shape as round 864 § 6's `HashMap` non-result, and for the same reason:
the row's own draw spread is **187.9–335.9 ms, a factor of 1.8**, which is more
than an order of magnitude larger than any plausible price for ~1.5 M not-taken
branches. Round 846's first-draw-is-slowest law holds **4/4**. What the second
draws DO say is that the row is where round 864 left it: 187.9–211.4 ms brackets
its quoted 196.3 ms in both arms.

### 9.5 The ablation — five faults, one at a time (round 807)

`scripts/round865-ablate.sh`, 56 pins per arm (this round's six plus the
neighbouring INV.2(b), B464, narrowing and mode-restore classes).

| arm | the fault | RED | uniquely its own |
|---|---|---:|---|
| **M1** | `newUnreachable` mints without registering | 2 | `every kind the builder mints is registered` |
| **M2** | the inventory is opened AFTER the first mint | 2 | `no file's inventory holds a node another file minted` |
| **M3** | the main narrowing walk stops reporting what it looked at | 1 | `the flow chain of a narrowed reference is reported as read` |
| **M4** | the walk-volume axis is container-blind | 1 | `a function nothing narrows is reported as an entirely unread container` |
| **M5** | the touch channel's gate is inert | 1 | `negative control - with the flag off the census records nothing` |

**Every arm has a uniquely-its-own failure and no two failing sets coincide** —
but only after the pins were re-cut, and the FIRST batch is the part worth
recording:

* **M1 and M2 originally reddened the SAME two pins**, so neither was
  attributable. The fix was a per-KIND registration pin (only a lost mint site
  moves it) and restating the per-file pin as an IDENTITY question — does file
  A's inventory hold a `FlowStart` whose container is file B's `SourceFile` —
  which only a late-opened inventory moves.
* **M3 was GREEN**, and the reason is round 807's redundant-signal mechanism seen
  from the pin side: `flowAt`'s hand-out observes the same ENTRY node through a
  different channel, so every assertion about "something was read" survived
  deleting the hook that walks the whole antecedent CHAIN. The pin now asserts
  that channel's own counter is live.
* **M5 was GREEN as first written, and that is a finding about the CODE, not the
  pin: the `if (!on) return` inside `mint`/`visit`/`touch` is a REDUNDANT GUARD**,
  because every call site is already `if (FlowCensus.on) …`. Ablating the inner
  guard alone changes nothing observable. The arm now removes the call-site guard
  as well — one gate, expressed at the two places that implement it — and the
  negative control reddens uniquely.
* One trap the re-cut exposed: **a counter read AFTER the `underCensus` helper is
  0 on a working instrument**, because the helper resets in its `finally` — the
  same reading a dead instrument gives. Capture inside the block.

### 9.6 Gates

Suite 14,094 → **14,100 / 0 failures / 3 skipped** (a real XML parser over all
four modules; the § 9.5 re-cut replaced one pin with two, hence +6 not +5). `cost_gate.py` **+0.00% on all 20 counters**.
`huge_methods.py --fail-over 0` **0 over the limit**, 659 classes. The 8-profile
grid **`added=0 removed=0` in both directions on every profile** (46 × 7 and 94
for harness, no truncated or empty capture), run from TWO class directories with
round 853's positive control asserted in BOTH directions — the after dir must
contain `FlowCensus` and the before dir must not, so a mis-pointed dir cannot
make the arms agree by being one build twice.

### 9.7 A fixture law this round paid for again

The census read **`read 0`** on its first working run, on a fixture whose narrowed
reference was inside `if (x) { … }`. Nothing was broken: since round 785 a guard
in that position writes its narrow into `currentLocalTypes` for the THEN branch,
so the read is answered with **no flow walk at all**. CLAUDE.md already states
this for enum and argument-position fixtures (round 796); the flow-node census is
its third consumer, and the fixture needs an **early return** (or a ternary /
`&&`) before any flow node is consulted.

## 10. Reproducing

```bash
scripts/round864-warm-flow.sh r864-base frontend,frontend 2   # the rows + census
scripts/round864-grid.sh build/bench/r864-grid                 # 8 profiles, both arms
scripts/round864-ablate.sh                                     # M1..M4, one at a time

CP="xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$(scripts/lib/dep-classpath.sh --print)"
P=build/bench/tsc-project-637d5746
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd --flowIndexLegacy $P
```

```bash
# (WARM.12) round 865
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --flowCensus --frontEnd $P               # the census + the rows
scripts/round865-grid.sh <before-cls> <after-cls>      # 8 profiles, two class dirs
scripts/round865-probe-cost.sh <before-cls> <after-cls>  # what the probe costs
scripts/round865-ablate.sh                             # M1..M5, one at a time
```
