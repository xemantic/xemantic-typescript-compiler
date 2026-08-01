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
