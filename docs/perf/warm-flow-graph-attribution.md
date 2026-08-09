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

## 8. Reproducing

```bash
scripts/round864-warm-flow.sh r864-base frontend,frontend 2   # the rows + census
scripts/round864-grid.sh build/bench/r864-grid                 # 8 profiles, both arms
scripts/round864-ablate.sh                                     # M1..M4, one at a time

CP="xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$(scripts/lib/dep-classpath.sh --print)"
P=build/bench/tsc-project-637d5746
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd --flowIndexLegacy $P
```
