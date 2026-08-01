# (ENGINE.2) — what is inside the property-access path

*Round 787. Compiler profile (`build/bench/tsc-project-*`, 78 files, 864,963
nodes), `--noEmit`, `-Xmx4g`, box idle. Harness: `CpaSections` +
`--cpaSections{,Coarse,Census}` — opt-in, behaviour-free when off.*

This is the fourth site of the (ENGINE.1) arc and the first one that holds real
mass. (ENGINE.1) priced the dedicated-walker layer on the three largest
**assignability** sites at 613 ms; those three functions are 1,417 ms of a
~27.9 s compile between them. The **property-access** path is **3,815 ms**, and
no round had opened it: round 733 attributed the *handler* (`cpaSpineLeave`) and
stopped at "88.4% of it is the pass's own checking work", after which its ccet
twin was opened three times ((CALL.1)/(CALL.2)/(CALL.3)) and its cta sibling
twice ((TYPE.2)/(ENGINE.1)).

## 1. The headline

| | ms | share of the compile |
|---|---:|---:|
| the property-access path (level P, probe-free) | **3,815** | **13.7%** |
| — the ENGINE (`checkMemberAccessMissing`, property resolution) | **2,364** | 8.5% |
| — the dedicated-walker/firewall layer | **207** | **0.74%** |
| — traversal (dispatch + wrapper) | 379 | 1.4% |
| — contextual-typing machinery for call arguments | 459 | 1.6% |
| — everything else (element access, binary spine, scope, bodies) | 101 | 0.4% |

**The layer is 0.088× the engine work here** — against 0.67× / 0.40× / 0.39× at
(ENGINE.1)'s three sites. Rolled up over all four sites the dedicated-walker
layer is **820 ms = 2.9%** of a check-only compile, and **the site that holds the
mass has by far the smallest layer share**. That is the answer to
`docs/ARCHITECTURE-RETHINK.md` § 0.1's endgame paragraph: where the walkers are
thickest the time is thin, and where the time is thick the walkers are 8% of it.

## 2. Method, and the one thing that makes the numbers comparable

* **Level P** partitions `checkPropertyAccessInExpr`. It RECURSES, so it uses
  round 756's hand-back shape (`beginP` closes the caller's row and returns it,
  `endP` reopens it): every row is SELF time, exclusive of nested invocations, and
  the rows sum to the walk's true total. The count column is boundary CLOSES, not
  invocations — per-invocation populations come from the arm census.
* **Level Q** partitions `checkSinglePropertyAccess`, which does not recurse
  (`invocationsQNested` = 0, pinned), so it keeps the `depth != 1 ⇒ return` shape.
* Level P is windowed to `cpaSpineLeave`'s anchor blocks (`inCpa`), so its total
  is directly comparable to that handler's `--spineSections` rows. Measured:
  **399,336 in-window invocations, 0 outside** — every `checkPropertyAccessInExpr`
  call on this profile comes from the spine anchors.

### Calibration — reported as a BOUND

Three runs per mode, on the binary that carried NO census predicate (so the two
modes differ only in interior boundaries):

| | run 1 | run 2 | run 3 | median | spread |
|---|---:|---:|---:|---:|---:|
| ON | 4,366 | 4,405 | 4,474 | **4,405** | 108 |
| COARSE | 4,095 | 4,101 | 4,136 | **4,101** | 41 |

Δ = **304 ms** over **782,359** extra boundaries = **389 ns**, bracket
**198–579 ns** once both spreads are charged against it. Δ is **2.8×** the larger
spread — usable, unlike round 786's 1.4×, but not sharp; every net figure below
carries that bracket. Consistent with round 755's 739 ns and round 733's in-situ
~900 ns, and NOT with the 86–89 ns per-read figure.

**Why the classification does not hinge on it.** 79% of all level-P closes are in
the two TRAVERSAL rows (wrapper 399,336 + dispatch 801,892), so the probe inflates
exactly the rows that are not the finding; the leaf row closes 66,747 times and
carries 13–39 ms of probe against 2,899 ms of work. And **every level-Q row closes
exactly 66,747 times**, so a uniform per-boundary cost subtracts the SAME amount
from each row — it cannot reorder them.

## 3. Level P — the walk (probe-free, b = 389 ns)

| row | gross ms | closes | **net ms** | share |
|---|---:|---:|---:|---:|
| dispatch + pass-through arms (the walk itself) | 696 | 801,892 | **384** | 10.1% |
| wrapper transition (probe-only) | 150 | 399,336 | **~0** | — |
| **`checkSinglePropertyAccess` (level Q)** | 2,899 | 66,747 | **2,873** | **75.3%** |
| `cpaComputeArgCtxTypes` | 342 | 51,967 | **322** | 8.4% |
| call-argument contextual-type loop | 189 | 141,410 | **134** | 3.5% |
| `checkSingleElementAccess` | 35 | 1,709 | **34** | 0.9% |
| binary left-spine own work | 43 | 31,267 | **31** | 0.8% |
| block body (`checkPropertyAccessInStatements`) | 18 | 7,097 | **15** | 0.4% |
| **arrow / fn-expr SCOPE bookkeeping** | 20 | 4,971 | **18** | **0.47%** |
| arrow / fn-expr contextual params | 4 | 2,353 | 3 | 0.1% |
| object-literal contextual members | 3 | 7,822 | ~0 | — |
| ClassExpression arm | 0 | 2 | 0 | — |

Cross-check: the net `cpaComputeArgCtxTypes` row (322 ms) is independently
measured by a nested sub-measure on a different binary at **332 ms** — 3% apart.

## 4. Level Q — the per-property-access leaf

Every row closes exactly 66,747 times; net = gross − 26 ms (bracket 13–39).

| row | gross ms | **net ms** | classification |
|---|---:|---:|---|
| **`checkMemberAccessMissing` (TS2339/TS2551)** | 2,390 | **2,364** | **ENGINE** |
| `emitTs18048ForClosureCapturedUndefinedReceiver` (B464) | 298 | **272** | firewall |
| `checkPrivateMemberAccess` (TS2341) | 41 | 15 | firewall |
| `emitTs18048ForOptionalPropertyAccessReceiver` | 31 | 5 | firewall |
| `emitTs2532ForOptionalChainInstantiationReceiver` | 10 | ~0 | firewall |
| TS2748 ambient const enum | 9 | ~0 | firewall |
| TS2339 `.prototype` on an instance | 7 | ~0 | firewall |
| `super.X` cluster (TS2340/2855/2339) | 10 | ~0 | firewall |
| TS1209 new-expression optional chain | 5 | ~0 | firewall |
| wrapper transition | 4 | — | probe |

**Engine 2,364 ms = 91.9%, firewall 207 ms = 8.0%.** And the firewall is one
walker: **B464 alone is 272 of the 207–285 ms**, while the *other seven probes —
which run unconditionally at all 66,747 property accesses — cost between 0 and
25 ms COMBINED.* Eight emission sites, seven of them free.

**E4 holds a fourth time.** Every one of these eight emits a diagnostic tsc also
emits (TS1209 / TS2339 / TS2532 / TS18048 / TS2340 / TS2855 / TS2748 / TS2341),
from checks tsc performs inside `checkPropertyAccessExpression`. What is
*ours* is the closure RESTRICTION on B464 — a gate, not a rule. So the layer
**moves** into any replacement engine; **plainly deletable here is ~0 ms.**

## 5. The two candidates this partition exposes, priced

Neither clears the ±2.0% COLD band alone. Together they are 403 ms = **1.44%**,
which the ±1.0% WARM protocol can see. Queued as **(ENGINE.2b)**.

### (i) `cpaComputeArgCtxTypes` — 265 ms computed for nothing

`contextualType` is read by exactly three arms of `checkPropertyAccessInExpr`
(ArrowFunction, FunctionExpression, ObjectLiteralExpression). Measured with a
conservative subtree predicate:

| | calls | ms |
|---|---:|---:|
| all `cpaComputeArgCtxTypes` invocations | 51,967 | **332** |
| — whose ARGUMENT subtrees can consume the result | **2,020 (3.9%)** | **67** |
| — whose result nothing can read | 49,947 (96.1%) | **265** |

**This is the round-759 qualification pointing the OTHER way, and it is worth
stating.** Per call the skippable population is the CHEAP tail (5.3 µs vs 33 µs)
— exactly what § 0's law predicts — but there are **25× more of them**, so in
AGGREGATE the skippable share is **80%**. *A per-item cost law does not settle an
aggregate question.*

**The caveat that makes this a queue item rather than a landed fix:** the function
calls `getTypeOfIdentifier`, `resolveStructuredTypeMembers` and
`tryInferSingleTypeParamFromArgs`, all of which mutate resolution caches. Round
754 showed a resolution-ORDER accident can decide a verdict, so skipping it is not
free by inspection — it needs the full suite and the 8-profile grid, not a
reviewer's confidence.

### (ii) B464's innermost-closure lookup — 138 ms of linear scan

| | calls | ms |
|---|---:|---:|
| `getTypeOfExpression(recv)` — the round-489 pre-gate | 54,346 | 43 |
| the `closureStarts` scan | **15,483** | **138** (8.9 µs each) |
| `getNarrowedTypeForReferenceFollowLoopEntry` — a flow walk | **466** | 29 |

The round-489 pre-gate works: only 15,483 of 66,747 accesses get past it, and only
**466** ever launch the flow walk. What is left is an **O(closures in the file)
linear scan run per property access** to find the innermost enclosing closure —
8.9 µs each, 46% of the walker. Rounds 482 and 489 both optimised around this scan
without replacing it. It is answerable from the parent chain or a position-sorted
index; it is not a cache.

## 5b. What round 788 landed, and what the prices turned out to be

Both candidates landed, as two independently gated commits. Compiler profile,
same harness, `--cpaSections` ON:

| row | round 787 | round 788 | Δ |
|---|---:|---:|---:|
| `P: cpaComputeArgCtxTypes` (gross) | 342 | **233** | −109 |
| — nested sub-measure, calls with a consumable arg | 67 (2,020 calls) | 68 (2,964 calls) | — |
| `Q: emitTs18048 closure-captured receiver` (gross) | 298 | **177** | −121 |
| — nested sub-measure, the `closureStarts` scan | **138** (15,483) | **4** (15,483) | **−134** |

### (ii) is a clean elimination; (i) is not, and the difference is the finding

**(ii) B464.** The `closureStarts` scan went **138 ms → 4 ms** over the *same*
15,483 queries — a 34× cut, and the work does not reappear anywhere, because
nothing else wanted the answer. `FlowGraph` now precomputes, per file, a
pos-sorted array of closure containers plus each entry's nearest ENCLOSING entry
(one stack sweep in the constructor that already walks the tree); a query is a
binary search plus a walk bounded by the closure NESTING DEPTH.
`FlowGraph.innermostClosureAt` reproduces the scan's tie rule exactly (the scan's
`c.pos > bestPos` is strict, so among equal-`pos` containers the first in
`closureStarts` order won; the sort is stable and the query re-scans its tie
group leftward). Verified with an in-binary differential — **15,483 queries on
the compiler profile and 26,119 on harness, 0 mismatches** — and pinned by
`ClosureIndexEquivalenceTest`, which carries the replaced scan as its own
reference implementation and compares at *every position of every fixture*.

**(i) the `cpaComputeArgCtxTypes` pre-gate is real but its ms do not all
survive, and this is § 0's law wearing a third hat.** The gate skips **49,003 of
51,967 calls (94.3%)**, and the deterministic counters confirm the work is gone:
`typeOfExpr.calls` **−3.27%**, `globals.lookups` **−2.75%**,
`globals.misses` **−2.75%**, `narrow.memoServed` **−5.09%** (rebaselined in the
landing commit). But the timed row fell only **109 ms** against the 265 ms round
787 priced, and the level-Q engine row rose by a comparable amount.
**A resolution this function performs is CACHED, so skipping it does not delete
the work — it MOVES it to whoever asks next.** What is genuinely deleted is the
part with no other consumer: `resolveStructuredTypeMembers` on the callee, the
per-argument `mapIndexed`, and above all `tryInferSingleTypeParamFromArgs` +
`computeFixedConflictLiteralMapper` for a generic single signature. So round
787's 265 ms was an upper bound on the *skippable* population, never a
prediction of the *recoverable* time. **Round 787's own qualification (a
per-item law does not settle an aggregate question) has a mirror image: an
aggregate that is skippable is not thereby recoverable.**

The gate covers **four** node kinds, not round 787's three: the probe predicate
listed the three arms of `checkPropertyAccessInExpr` that read `contextualType`
and missed that `getTypeOfArrayLiteral` reads it too (for its element context),
so `ArrayLiteralExpression` is in the production predicate. That costs 944 calls
of the skippable population (49,947 → 49,003) and is not optional. The scan is
bounded at 128 nodes — exhaustion answers TRUE (compute as before), so the budget
can never change a verdict; measured, it is **never reached** on the compiler
profile, and the scan visits **3.6 nodes per call** on average.

### How the order hazard was ruled out

The queue item's warning was correct in kind: `cpaComputeArgCtxTypes` calls
`getTypeOfIdentifier`, `resolveStructuredTypeMembers` and
`tryInferSingleTypeParamFromArgs`, all of which mutate resolution caches, and a
purely syntactic predicate cannot see a read that happens through a *foreign*
node (`getTypeOfExpression` on an identifier can resolve a declaration elsewhere
whose initializer is an object literal, and that computation reads — and caches
under — the live `contextualType`). So the predicate was falsified empirically
before it was trusted, with a temporary probe that kept the OLD behaviour
(compute unconditionally) and counted, at each of the three foreign read sites,
every read of a non-null value the gate would have suppressed:

* compiler profile — 51,967 calls, 49,003 gate-skippable, **0 violations**;
* harness (the superset profile, 311 files) — 77,791 calls, 71,840
  gate-skippable, **1 violation**, in `applyContextualParameterTypes`
  (`server/session.ts`), where the contextual type is `{} | undefined` — a
  `Type.Union`, so the function returns on the very next line
  (`if (ctx !is Type.Object) return`). The read is inert.

Plus the standing gates: the 8-profile `--listAll` grid re-captured at HEAD and
diffed **set-for-set in both directions, 0 added and 0 removed on all eight**;
`--partitionCheck 2` EQUIVALENT.

### The warm A/B decides nothing, and that is the honest report

`scripts/ab-warm.sh /tmp/xtsc_A788 build/classes/kotlin/jvm/main 3`, box left
strictly alone (no polling of the log during the run):

| pair | A (ms) | B (ms) | Δ |
|---|---:|---:|---:|
| 1 | 11,930 | 12,020 | **+0.75%** |
| 2 | 12,035 | 11,955 | **−0.66%** |
| 3 | 12,100 | 11,920 | **−1.49%** |

**Median A = 12,035 / B = 11,955, Δ = −80 ms = −0.66%, B wins 2/3.** Arm sd
**0.71% (A)** and **0.42% (B)** — both under the ~1% quiet-box threshold, so the
run is admissible; but the per-pair spread is **270 ms** against an **80 ms**
median delta, and the driver's own rule fires: **`VERDICT: NOISE-DOMINATED —
this run decides NOTHING in either direction.`** Every iteration on both arms
reported `files/errors 78/46`, so the self-falsification held.

**So the wall-clock claim for this round is: not measurable at 3 warm pairs.**
The 403 ms combined estimate is **not confirmed**, and the direction the medians
lean (B faster by 0.66%) is inside the band. What IS established is
deterministic and load-immune, exactly as the driver advises when it reaches
this verdict: the `closureStarts` scan is gone (138 → 4 ms over an identical
query count) and four cost counters fell by 2.75–5.09%. A round that wants the
wall number needs more pairs or a higher `ITERS`, on a box doing nothing else —
and should note that a warm rebuild is **11.9 s** against a **~27 s** cold
compile, so a saving whose code the JIT has already compiled is a *smaller*
fraction of a warm run than of a cold one.

## 6. Predictions, scored

| | prediction | measured | verdict |
|---|---|---|---|
| **G1a** | leaf ≥ 50% of the path | **76.2%** | **HIT** |
| **G1b** | traversal < 10% | 9.9%, bracket **6.6–14%** | **UNDECIDED** — the calibration straddles it |
| **G2** | firewall 20–40%, below (ENGINE.1)'s band | **8.0%** (bracket 4.2–11.6%) | **MISS** — right direction, 2.5–5× too high |
| **G3** | scope bookkeeping ≥ 300 ms | **18 ms** | **FALSIFIED by 17×** |
| **G4** | visits/distinct = 1.0 ± 0.05 | **1.000** (399,336/399,336; 66,747/66,747) | **HIT** |

2 hit, 1 undecided, 2 missed. G3 was the round's hoped-for lever and it is dead:
per the item's own falsification clause ("if it comes in under 150 ms, say so and
stop"), said.

## 7. The methodological trap G4 walked into

The FIRST G4 measurement read **2.35× (level P) and 1.49× (level Q)** — i.e. "the
walk re-checks a third of its property accesses", which would have been the most
important finding of the round. It was an artifact.

**`indexSourceFile` restarts `nodeId` at 0 for EVERY `SourceFile`** (`NodeWalk.kt`:
`sourceFile.nodeId = 0; var nextId = 1`). A program-wide `HashSet<Int>` of raw
nodeIds therefore collapses one node per file onto each id: across 78 files the
distinct count is bounded by the largest file, and any visits/distinct ratio is
inflated by an unbounded factor. Keyed by `(fileName.hashCode(), nodeId)` the
answer is **exactly 1.000 in both levels** — the anchors are disjoint by
construction (`cpaM3MarkAnchored`) and nothing is visited twice.

An identity set is not the fix either: AST nodes are Kotlin data classes, so
`HashSet<Node>` deep-recurses `hashCode()` over the subtree (CLAUDE.md's explicit
anti-pattern, round 471).

## 8. Verification

* Compiler-profile `--listAll`: **46 errors, byte-identical** in production,
  `--cpaSections`, `--cpaSectionsCoarse` and `--cpaSectionsCensus`.
* Corpus suite and the 8-profile grid: see the round-787 session note.

## 9. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
P=build/bench/tsc-project-*
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll --cpaSections       $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll --cpaSectionsCoarse $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll --cpaSectionsCensus $P
```
