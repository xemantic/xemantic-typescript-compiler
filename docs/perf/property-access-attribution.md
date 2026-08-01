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

---

# Level R (round 789, (ENGINE.2c)) — inside `checkMemberAccessMissing`

*Compiler profile, `--noEmit`, `-Xmx4g`, box idle. Harness: `CpaSections` level R
+ six nested sub-measures, opt-in via the same `--cpaSections{,Coarse,Census}`
flags, behaviour-free when off.*

## 10. The headline

Level Q's engine row is ONE function of ~1,965 lines, entered 66,747 times at
34.3 µs each. Re-measured at HEAD before the item was written: **2,292 ms gross,
6× the next row of either level.** Opening it inverts the picture levels P and Q
gave:

| | ms (net) | share of the function | share of the compile |
|---|---:|---:|---:|
| **the three flow-graph SUPPRESSION blocks** | **1,505** | **57.1%** | **~5.4%** |
| computing the receiver type (8 rows) | 697 | 26.4% | 2.5% |
| the other seven receiver-shape firewall blocks | 267 | 10.1% | 1.0% |
| post-type gates, member resolution, THE LOOKUP | 53 | 2.0% | 0.2% |
| entry / unwrap / `never` probe | 123 | 4.7% | 0.4% |
| suggestion + `typeToString` + emission | **0** | **0.0%** | 0 |

**At levels P and Q the dedicated-walker firewall was 8.0% and the engine 91.9%.
One level down, inside the "engine", the firewall is 67% and the property lookup
it defends is 0.2%.** The function is not mostly a property lookup; it is mostly
a suppression apparatus in front of one.

## 11. Method and calibration

Level R keeps level Q's non-recursive `depth != 1 ⇒ return` shape
(`invocationsRNested` = 0, pinned) and adds two instruments:

* **an exit census** (`rExitIn`) — which row each call RETURNS from. Nothing else
  can see a gate that is cheap to evaluate but placed after expensive work;
* **a walker-restricted exit census** (`rExitWalk`) — the same census over just
  the calls that paid for a flow walk. That is what turns "this row is big" into
  "these specific calls paid for nothing".

It has TWO callers (property access and element access), so its 67,258
invocations legitimately exceed level Q's 66,747; the 511 difference is the
element accesses, and the two counts are cross-checked rather than assumed equal.

**Calibration — sharper than round 787's, and still reported as a bound.** Three
runs per mode on the same binary:

| | run 1 | run 2 | run 3 | median | spread |
|---|---:|---:|---:|---:|---:|
| ON | 3,013 | 2,938 | 3,006 | **3,006** | 75 |
| COARSE | 2,580 | 2,654 | 2,655 | **2,654** | 75 |

Δ = **352 ms** over **1,177,373** extra boundaries (1,244,631 closes under ON
against 67,258 under COARSE) = **299 ns**, bracket **240–368 ns** once both
spreads are charged against it. Δ is **4.7×** the larger spread — against round
787's 2.8× and round 786's 1.4×. Every net figure below carries that bracket.

**Two honest caveats.** (1) The net rows sum to 2,641 ms, which agrees with the
COARSE median (2,654) to 0.5%, but the probe-free anchor from level Q is
2,292 ms — so the harness's own footprint is **~250–350 ms even in COARSE**
(the `try`/`finally` plus 26 inert boundary checks in a function this size).
Absolute row figures therefore carry ±13%; the SHARES do not, because the probe
distributes by boundary count and is subtracted per row. (2) The largest row is
also the one with the fewest boundaries per unit time, so the calibration cannot
be what produced it: R_FLOW closes 67,067 times for 1,524 ms, i.e. 20 ms of probe
against 1,505 ms of work.

## 12. Level R — the partition

Net = gross − closes × 299 ns.

| row | gross ms | closes | **net ms** | exits | of which walkers |
|---|---:|---:|---:|---:|---:|
| wrapper transition (probe-only) | 16 | 67,258 | ~0 | 0 | 0 |
| pre (empty name, unwrap, intersection-`never`) | 146 | 67,258 | 126 | 0 | 0 |
| shadowed-name receivers | 74 | 67,258 | 55 | 191 | 0 |
| **the three flow-graph receiver blocks** | **1,524** | 67,067 | **1,505** | 1,169 | **914** |
| identifier-receiver special cases | 179 | 65,898 | 160 | **0** | 0 |
| string/regex/empty-objlit receivers | 36 | 65,898 | 17 | 7 | 0 |
| NewExpression receiver | 25 | 65,891 | 6 | 3 | 0 |
| CallExpression receiver | 30 | 65,888 | 11 | 832 | 0 |
| PropertyAccess/ElementAccess receiver | 33 | 65,056 | 14 | 37 | 0 |
| this-in-static-method | 23 | 65,019 | 4 | 0 | 0 |
| type = this / ArrayLiteral arms | 30 | 65,019 | 11 | 55 | 0 |
| type = ns-member/enum-member/cast emissions | 52 | 64,886 | 34 | 0 | 0 |
| type = narrowing-eligibility gate | 29 | 64,886 | 10 | 2,271 | 0 |
| type = `getTypeOfExpression(receiver)` | 50 | 62,615 | 32 | 0 | 0 |
| type = union-receiver narrowing | 243 | 62,615 | 225 | 216 | 0 |
| type = non-Identifier receiver | 35 | 62,399 | 17 | 3,852 | 129 |
| type = identifier symbol resolution | 105 | 58,547 | 88 | 0 | 0 |
| **type = resolved-symbol branch** | 297 | 58,547 | 280 | **40,308** | **21,064** |
| objectType gates (`!is Object`, enum-flavored) | 14 | 18,317 | 10 | 0 | 0 |
| `resolveStructuredTypeMembers` | 7 | 18,317 | 2 | 0 | 0 |
| member-less receiver block | 10 | 18,317 | 5 | 46 | 15 |
| post-type gates (base/Reference/runtime props) | 35 | 18,271 | 30 | 8,944 | 71 |
| **`getPropertyOfType` — THE LOOKUP** | 8 | 9,327 | **6** | 9,250 | **0** |
| late suppression + index signatures | 0 | 77 | 0 | 77 | 77 |
| suggestion + `typeToString` + emission | — | **0** | **0** | 0 | 0 |

**The exit census.** 86.1% of the 67,258 calls return before the property lookup
is reached, and the emission tail is **never reached at all** — 77 calls get as
far as the index-signature gates and all 77 stop there. So on this profile the
function's message-building code, spelling suggestion included, costs exactly
nothing, which is the strongest possible form of round 786's 0.4 ms
TS2322-elaboration finding and the wiring control this partition needed.

## 13. Inside the dominant row: 1,219 ms of flow walking for 886 suppressions

Six nested sub-measures split it (values from the reference run; a second run
reproduced every COUNT exactly and every time within ±10%):

| | calls | ms |
|---|---:|---:|
| b1 `getTypeOfExpression(receiver)` | 63,797 | 72 |
| b1 the round-489 pre-gate (two `getPropertyOfType`) | 63,797 | **50** |
| **b1 the PLAIN narrowing flow walk** | **22,270** | **731** |
| **b1 the round-425 loop-entry RETRY walk** | **21,384** | **488** |
| b2 base type + `getFlowAt` | 3,584 | 5 |
| b2 the base-projection flow walk | 470 | 47 |
| b3 the whole `this`-receiver block | 140 | 1 |

**The round-489 pre-gate is excellent and it is not the cost**: 50 ms to stop
41,527 of 63,797 accesses (65%) from walking. What is left is a genuine flow
walk, twice, at every third property access.

**22,270 walks is 31% of the entire compile's 71,377 narrowing walks** — this one
firewall block is a third of all flow narrowing xtsc does.

**The arithmetic that prices it.** The retry runs exactly when the plain walk did
not suppress, so `22,270 − 21,384 = 886` is an exact count of plain-walk
suppressions, not an estimate. The walker-restricted census shows **914** walkers
exiting in this row in total, so **the retry and everything downstream of it in
the row account for at most 28 more suppressions** — against 488 ms and 21,384
walks. That is **≥17 ms per suppression**, and the round-425 retry is the
narrowest, best-priced deletion candidate the arc has produced.

**And where the other 21,356 walkers go is the finding.** Of the 22,270 calls
that pay for a walk: 914 (4.1%) suppress; **21,064 (94.6%) exit in the
receiver-type resolved-symbol branch**, downstream of the walk and with the
walk's answer consulted by nothing; 129 exit in the non-Identifier branch, 71 in
the post-type gates, 15 in the member-less block, 77 at the index-signature
gates; and **0 reach the property lookup.** The suppression apparatus runs at the
TOP of a function whose emission sites are at the BOTTOM, and 95% of the calls
that pay for it never get near them.

## 14. Predictions, scored

| | prediction | measured | verdict |
|---|---|---|---|
| **H1** | the receiver-type computation is ≥ 50% of the function | **26.4%** | **FALSIFIED** (threshold was 35%) |
| **H2** | the pre-type firewall is < 20% | **67.2%** | **FALSIFIED by 3.4×** — and it is the round's finding |
| **H3** | ≥ 60% of calls exit before the property lookup | **86.1%** | **HIT** |
| **H4** | a cheap gate sits after expensive work, ≥ 150 ms | see below | **HIT in the opposite direction** |
| **H5** | the emission tail is ~0 ms | **0 ms, 0 reaches** | **HIT** |

**H1 and H2 were wrong in the same direction and the error is instructive.** Both
were extrapolated from (ENGINE.1)'s three assignability sites, where "compute the
SOURCE type" was the largest row at every site. The analogy is false here: at an
assignability site the source type IS the work, while at a property access the
receiver type is mostly already resolved and the expensive thing is the
FP-firewall built in front of the lookup. A prior taken from three sites of one
shape does not transfer to a fourth of another shape — which is the same lesson
round 787 recorded when its firewall prediction missed by 2.5–5×, now with the
sign reversed.

**H4 was aimed at the wrong kind of gate.** The named candidates
(`RUNTIME_PROPERTIES`, `isEnumFlavoredObjectType`) are in rows worth 10 and 30 ms
— nothing. What the census found instead is bigger and structurally different:
not a cheap gate placed late, but an EXPENSIVE gate placed early, whose 95%
majority of payers exit long before the emission it defends.

## 15. Verification

* Compiler-profile `--listAll`: **46 errors, byte-identical** in production, ON,
  and COARSE, and identical again after the harness was reconstructed.
* Corpus suite **13,380 / 0 / 3**; 8-profile grid **46/46/46/46/46/46/46/94**
  captured at HEAD and after, diffed set-for-set BOTH directions: **0 added, 0
  removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**; cost gate
  **all 20 counters +0.00%**.

## 16. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt)
P=build/bench/tsc-project-*
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll --cpaSections       $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll --cpaSectionsCoarse $P
```

---

# Round 790 — (ENGINE.2d)(a): the round-425 loop-entry retry is a pure repeat, 88.7% of the time

## 17. The headline

`checkMemberAccessMissing`'s block 1 walks the flow graph, and then — whenever
that walk did not suppress — walks it AGAIN through the loop-entry-following
mirror. Re-measured at HEAD before anything was written:

| | calls | gross ms |
|---|---:|---:|
| the PLAIN narrowing flow walk | 22,270 | 758 |
| **the round-425 loop-entry RETRY walk** | **21,384** | **528** |

Every count of round 789's reproduced exactly (67,258 / 22,270 / 21,384 / 914 /
40,308 / 63,797), so the target was stable while it sat in the queue — which is
not something to assume (round 786's had grown, round 755's had halved).

**The retry is now skipped when the plain walk provably made it redundant, and
that is 88.7% of the time.** Measured after:

| | calls | gross ms |
|---|---:|---:|
| the PLAIN narrowing flow walk | 22,270 | 756 |
| the round-425 loop-entry RETRY walk | **2,408** | **90** |

and, one level up, the row that contains them: **R_FLOW 1,548 → 1,111 ms**,
level R total **3,094 → 2,618**, level Q total **3,488 → 3,026**. The saving is
visible at every level of the partition; it does not get absorbed one level up.

## 18. The equivalence, and why it is provable rather than probable

`narrowTypeFromFlow` and `narrowTypeFromFlowFollowLoopEntry` are line-by-line
mirrors (CLAUDE.md's walker-mirror invariant). Arm by arm — `FlowStart`,
`FlowUnreachable`, `FlowCondition`, `FlowBranchLabel`, `FlowAssignment`,
`FlowCall`, `FlowSwitchClause`, `FlowArrayMutation` — plus the fast-forward
pass-through loop, the depth/visit budgets, the `seen` set and both memos, they
are identical. **The one difference is `FlowLoopLabel`**: the plain walker
returns the declared type there, the mirror recurses into `antecedents[0]`.

So if the plain walk ARRIVED at no `FlowLoopLabel`, the mirror makes exactly the
same traversal decision at every node the plain walk visited, reaches the same
nodes, and returns the same type. Stronger than "same result": since every
resolution the mirror would drive was already driven (and cached) by the plain
walk moments earlier, the second walk is a pure REPEAT — which also disposes of
the cache-mutation-order hazard (round 754) that kept round 789 from landing it,
because a repeat mutates nothing new.

**Three ways the claim can fail, all handled by treating unknown as "run it".**

1. **The walk never ran.** `isFlowAnalysisDisabledAt`, a missing reference path,
   a missing flow node, or a serve from the round-664 inter-walk memo all return
   without traversing anything, so no observation was made and none may be used.
2. **The walk TRUNCATED** — the depth trip, the re-entry/visit budget, or the
   `seen` cycle break. Such a walk saw only a prefix of its own traversal.
3. **A serve from the round-736 intra-walk `NarrowFlowMemo` hid a subtree.** This
   one is not a hazard: that memo is constructed fresh at each outermost entry
   (`memo: NarrowFlowMemo = NarrowFlowMemo()`), so anything it serves was walked
   EARLIER IN THE SAME WALK and its loop labels were already observed.

Two monotone counters on the checker implement it. `narrowWalkLaunches` is bumped
INSIDE `getNarrowedTypeForReference`'s walk lambda, so case 1 leaves it unchanged.
`narrowRetryRelevantObs` is bumped at both walkers' `FlowLoopLabel` arms and at
every truncation exit, covering case 2. **Monotone and never reset is what makes
them re-entrancy-safe**: a nested walk launched from inside `applyConditionNarrowing`
can only push the bracketed reading in the conservative direction. The bracket is
taken IMMEDIATELY around the walk call — the `suppresses` fold that follows
re-enters the checker and would poison it.

## 19. The hazard was falsified by measurement, not by the argument above

Round 788's protocol. `--verifyLoopRetry` keeps the PRE-gate behaviour — the retry
runs at every call and is honoured — and counts, for every call the gate would
have skipped, whether the retry returned a different `Type` INSTANCE and whether
it suppressed where the plain walk had not:

| profile | skippable | verified | type-diff | **verdict-diff** |
|---|---:|---:|---:|---:|
| compiler | 18,976 | 18,976 | 0 | **0** |
| services | 24,290 | 24,290 | 0 | **0** |
| harness | 24,681 | 24,681 | 0 | **0** |

**67,947 comparisons, zero divergences, at the strongest available granularity:
not "the same verdict" but the same `Type` instance.**

**And the zero is not the reading of a dead instrument.** `--verifyLoopRetryAll`
runs the same comparison over the population the gate never skips — the calls
whose plain walk DID cross a loop label. On the round's fixture it reports
`VERIFIED 4, type-diff 2, VERDICT-DIFF 2`: the loop-crossing calls really do
disagree, and disagree in the direction that matters (the retry suppresses where
the plain walk did not). That is CLAUDE.md's "record a deliberately bogus
baseline" discipline, obtained here without a bogus baseline, because the
complement population supplies the positive control for free.

## 20. Law 2 does not apply here, and the counters say so to the unit

Round 788's finding — an aggregate that is *skippable* is not thereby
*recoverable*, because the skipped resolution was cached and the bill passed to
the next asker — is the thing this round had to check before claiming anything.
The queue item wrote the prediction down first: a narrowing walk's result is
memoed, but round 735 measured essentially every launched walk COLD, so the bill
should NOT move. It did not:

```
narrow.walks     71,377 -> 52,401   -26.59%      = -18,976, EXACTLY the skipped count
globals.lookups 942,637 -> 902,299   -4.28%
globals.misses  924,862 -> 884,538   -4.36%
typeOfExpr.calls 689,726 -> 688,878  -0.12%
typeNode.cacheHits 132,660 -> 131,983 -0.51%
output.errors / spine.nodes / typeOfExpr.distinct / mapped.*   +0.00%
```

**No counter rose.** The `narrow.walks` drop matching the skipped-retry count to
the unit is the cleanest evidence in this arc that a removal removed work rather
than relocating it: 18,976 walks were launched, and 18,976 fewer are launched.
One block of one function was **26.6% of every flow-narrowing walk xtsc performs**;
it is now 4.6% of it.

## 20b. The warm A/B: direction yes, magnitude no

3 pairs, `scripts/ab-warm.sh`, box left alone for the whole run:

| pair | delta |
|---|---:|
| 1 | -216 ms (-1.83%) |
| 2 | -635 ms (-5.13%) |
| 3 | -751 ms (-6.11%) |

median of the medians **-696 ms (-5.66%), B wins 3/3**, and the smallest delta is
already outside the +-1.0% warm band. **But the magnitude is not measured**: arm
A's sd is **2.47%** (arm B's 0.88%), above the ~1% quietness criterion the driver
itself sets for discarding a warm verdict, and the per-pair spread is 535 ms
against a 635 ms delta — law 7's ratio is 1.19, not the several-fold margin a
magnitude claim needs. Arm A drifted upward across the run (11,819 -> 12,381 ->
12,299 ms) while arm B did not, and the quoted -696 ms EXCEEDS the 438 ms the
partition measured directly, which is the tell of an inflated arm rather than a
larger win.

So: **the sign is confirmed 3/3 and the size is the partition's 438 ms, not the
wall's 696.** The deterministic counters remain the primary evidence — as round
788 also had to conclude, from the opposite position (its verdict was
NOISE-DOMINATED and could not even confirm the sign).

## 21. Verification

* Corpus suite **13,380 -> 13,389 / 0 failures / 3 skipped** (+9 pins,
  `LoopEntryRetryGateTest`).
* 8-profile `--listAll` grid captured at HEAD FIRST and again after, diffed
  set-for-set in BOTH directions: **46/46/46/46/46/46/46/94, 0 added and 0
  removed on all eight.** Both directions matters here and nowhere more: a
  suppression that starts firing DELETES a true positive and makes the grid look
  better.
* `--partitionCheck 2` **EQUIVALENT — 46**.
* Cost gate rebaselined in the same commit, with the mechanism and population
  named above.
* **Pin discrimination: 7 of 9.** Against a binary whose `loopFree` is forced true,
  seven of the nine `LoopEntryRetryGateTest` pins fail; the two that hold are the
  two deliberate controls (the unguarded negative control, and the
  production-inertness pin). No two faults cancelled — round 789 reported a
  cancellation, so this round checked for one instead of assuming.

## 22. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt); P=build/bench/tsc-project-*
# the yield and the equivalence, in one run
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSectionsCensus --verifyLoopRetry $P
# the control: the same comparison over the population the gate never skips
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSectionsCensus --verifyLoopRetryAll $P
# the price
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSections $P
```

---

# Round 791 — (ENGINE.2d)(b): the suppression apparatus moves to the emission

## 23. The headline

`checkMemberAccessMissing`'s three flow-graph suppression blocks ran at the TOP
of a 2,035-line function whose 42 emission sites are at the bottom. Round 789
measured the consequence and round 790 left it standing: of the 22,270 calls that
paid for a flow walk there, **0 reached the property lookup and 21,064 exited far
downstream with the walk's answer consulted by nothing.**

Re-measured at HEAD before anything was written (law 1 — and unlike round 790's,
one number HAD moved: the plain walk read 794 ms against round 790's 756):

| row | before | after |
|---|---:|---:|
| `R_FLOW` — the three suppression blocks | **1,132 ms** / 67,067 closes, 1,169 exits | **49 ms** / 67,067 closes, **0 exits** |
| b1 `getTypeOfExpression(receiver)` | 50 ms / 63,797 | 0 ms / **47** |
| b1 the round-489 pre-gate | 49 ms / 63,797 | 0 ms / **47** |
| **b1 the PLAIN narrowing flow walk** | **794 ms / 22,270** | **4 ms / 47** |
| b1 the round-425 loop-entry retry | 83 ms / 2,408 | 0 ms / 3 |
| b2 base type + `getFlowAt` | 5 ms / 3,584 | 0 ms / 0 |
| b2 the base-projection flow walk | 39 ms / 470 | 0 ms / 0 |
| b3 the `this`-receiver block | 1 ms / 140 | 0 ms / 10 |
| **level R total** | **2,686 ms** | **1,702 ms** |

**The whole apparatus is now evaluated 57 times per compile instead of 67,067**,
because it is asked only when the body actually appended a diagnostic. Level R
falls **984 ms** — more than the 781 ms the queue item priced, because the item
priced the walks and the deferral also removes the `getTypeOfExpression` and the
pre-gate in front of them.

## 24. Why this was landable when round 790 said it was not

Round 790 declined (b) with a reason about falsifiability, not budget: (a)'s
correctness was a two-way walker diff a counter could falsify, while (b)'s looked
like "a 20-way case analysis over emission sites, and no counter can falsify it,
because the counter would have to know what those emissions WOULD have said."

That framing assumed the deferral has to *decide, per emission site, whether the
suppression applies*. It does not. **The three blocks did exactly one thing:
`return` — suppress everything the rest of the function would emit.** So the
deferral needs no per-site decision at all; it needs only to undo the body's
effect, and the body's effect is enumerable in one grep:

```
$ awk 'NR>=133738 && NR<=135774' Checker.kt | grep -c 'diagnostics.add(Diagnostic('
42
$ ... | grep 'diagnostics' | grep -v 'diagnostics.add(Diagnostic('     # 1 comment line
```

**In 2,035 lines the only mutation of checker state is an append to
`diagnostics`** — no `removeAll`, no side-set write, no ambient install, no read
of `diagnostics` back — and the same holds for all seven `tryEmit*`/`emit*`
helpers it calls (checked mechanically, see § 27). So

> run the body, then remove everything it appended at or after the blocks' old
> position

is, for diagnostics, indistinguishable from never having run the body. No
emission site is enumerated; **adding a 43rd cannot break it.** That is what
makes the question mechanical, and it is a stronger property than the lazy-thunk
shape round 790 proposed (which still requires every reader to force the thunk,
i.e. still an enumeration — the same objection that killed round 788's lazy
`contextualType`, whose thunk would have had to survive ~14 save/restore sites in
a DIFFERENT dynamic scope; here the deferred call happens inside the very
invocation that deferred it, so no ambient state can have escaped).

**One subtlety decides whether the floor is right.** There IS an emission ABOVE
the blocks' old position — the intersection-reduction `never` TS2339 — and it was
never subject to them. The retraction floor is therefore `cmamFlowBase`, set at
the blocks' old position and left at `-1` when the body returns above it, not
`diagnostics.size` at function entry. A floor at entry would have silently made
that one diagnostic suppressible.

## 25. What is NOT settled by that argument, and how it was measured

Cache-mutation ORDER (round 754). The body now runs BETWEEN the two positions,
and for a call the predicate suppresses it now runs at all. The predicate could
therefore see a different resolution state than it used to.

`--verifyDeferSuppression` evaluates the predicate **twice** — eagerly, where the
blocks used to run, and again deferred, after the body — and compares. The EAGER
verdict is the one honoured, so **the verify run's output equals the pre-change
binary's by construction**, and the two halves of the claim are independent: a
byte-identical `--listAll`, and a zero verdict-diff.

| profile | compared | type-diff | **verdict-diff** |
|---|---:|---:|---:|
| compiler | 67,067 | 0 | **0** |
| services | 92,174 | 0 | **0** |
| harness | 109,622 | 0 | **0** |

**268,863 comparisons, zero divergences**, at `Type`-INSTANCE granularity rather
than verdict granularity (a verdict comparison alone would miss a changed type
that happens to resolve the same property).

**And the zero is not a dead instrument.** `--verifyDeferSuppressionBogus` hands
the deferred evaluation a property name nothing can resolve — CLAUDE.md's
"record a deliberately BOGUS baseline" discipline — which makes the round-489
pre-gate pass everywhere and the suppression decline everywhere. On the compiler
profile it reports **type-diff 19,864, VERDICT-DIFF 1,164**. The comparator is
alive, and it is comparing the thing it claims to compare.

## 26. Law 2 does not bite, and the counters say so

Round 788: an aggregate that is *skippable* is not thereby *recoverable*, because
a skipped CACHED resolution hands its bill to the next asker. Checked, not
assumed:

```
narrow.walks       52,401 -> 27,249   -48.00%   (= -25,152; the removed population is ~25,288)
typeOfExpr.calls  688,878 -> 617,600  -10.35%
globals.lookups   902,299 -> 830,368   -7.97%
globals.misses    884,538 -> 812,921   -8.10%
typeNode.cacheHits 131,983 -> 124,861  -5.40%
typeNode.cacheable 191,197 -> 184,065  -3.73%
output.errors / spine.nodes / mapped.* / typeNode.bypassed   +0.00%
```

**No counter rose.** `narrow.walks` falls by 25,152 against a removed population
of 25,288 walks (22,270 plain + 2,408 retry + 470 base-projection + 140 `this`,
less the ~60 still performed) — a 0.5% residual, i.e. essentially all of it
disappeared rather than moving. `typeOfExpr.distinct` fell by **7**: seven nodes
in the whole program were typed ONLY because this block asked, and nothing else
ever needed them.

## 27. The mechanical check, in full

The claim in § 24 is a grep, and it is worth recording exactly which one, because
it is the whole correctness argument:

* `checkMemberAccessMissingCore`, lines 133738–135774 pre-change: **42**
  `diagnostics.add(Diagnostic(` and **zero** other references to `diagnostics`;
  **zero** `.put(` / `.remove*` / `.clear()` on any collection; **zero**
  assignments to a `current*` ambient field; **zero** stack pushes.
* Its emitting/resolving helpers — `tryEmitStaticAccessTs2576`,
  `tryEmitClassInstanceMissingTs2339`, `tryEmitUtilityWrapperTs2339`,
  `tryEmitNamespaceMemberTs2339`, `tryEmitEnumTypedIdentReceiverTs2339`,
  `tryEmitEnumMemberAccessTs2339`, `emitClassChainTs2551Suggestion`,
  `shouldEmitTs2339ForHiddenNamespaceMember`,
  `lookupInstanceMemberInResolvableChain` — **one `diagnostics.add` each at most,
  and no other mutation** (the one hit, `v.add(`, is a local list).

A future change that adds a side-set write or a retraction anywhere in that range
invalidates the argument. That is a real obligation, and it is a *checkable* one,
which is exactly what the case analysis over ~20 emission sites was not.

## 28. Verification

* Corpus suite **13,389 -> 13,399 / 0 failures / 3 skipped** (+10
  `DeferredSuppressionTest` pins).
* 8-profile `--listAll` grid captured at HEAD FIRST and again after, diffed
  set-for-set in BOTH directions: **46/46/46/46/46/46/46/94, 0 added and 0
  removed on all eight.** Both directions matter more here than anywhere: a
  suppression that starts firing DELETES a true positive and makes the grid look
  better.
* `--partitionCheck 2` **EQUIVALENT — 46**.
* Cost gate rebaselined in the landing commit, mechanism and population named.
* **Pin discrimination: 8 of 10, across TWO complementary ablations.** Fault A
  (the predicate always declines — the lost-suppression direction) fails 5;
  fault B (the retraction always fires — the deleted-true-positive direction)
  fails 4, and three of those four are pins fault A leaves standing. The two that
  hold under both are the eager-vs-deferred ORDER pin (neither fault perturbs
  order; its live counterpart is the 268,863-comparison profile run) and the
  population cross-check. The two faults were run separately, so no cancellation
  is possible between them — round 789 had exactly that happen.
* Three pre-existing probe pins were RESTATED rather than deleted, because their
  subject moved: `CmamSectionProbeTest`'s walker-census non-vacuity and its
  "the flow-suppression row both fires and suppresses" now state the new
  invariant (the row is empty, the deferred call suppresses), and
  `NarrowSectionProbeTest`'s element-access arm pin needed a new fixture — its
  old one's ONLY flow walk was the one this block used to launch, so after the
  deferral it measured an empty census for a reason that had nothing to do with
  element accesses. That is the round-790 "the pin was aimed at nothing" failure
  mode, caught here by the suite rather than by inspection.

## 29. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt); P=build/bench/tsc-project-*
# the price and the yield
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSections $P
# the equivalence: both evaluations, compared at Type-instance granularity
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --verifyDeferSuppression $P
# the control: it must diverge
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --verifyDeferSuppressionBogus $P
```

---

# Round 792 — (ENGINE.2e): level R re-derived, and the gate that skips a third of it

## 30. The headline

Round 791's closing caveat was that level R's partition had gone stale — (b)
removed the row that was 42% of it. Re-derived at HEAD, the function is **1,629 ms
gross / ~1,239 ms net over 67,258 invocations**, and its top four rows are:

| row | net ms | closes | exits |
|---|---:|---:|---:|
| `type = resolved-symbol branch` | 280 | 59,266 | 40,346 |
| `type = union-receiver narrowing` | 271 | 63,771 | 225 |
| `identifier-receiver special cases` | 159 | 67,067 | **0** |
| `pre` (empty name, unwrap, intersection-`never`) | 129 | 67,258 | **0** |

68% of the function between them — and **not one of them is what § 12's table
said to look at**, which is the item's own law-1 demand paying off.

A level-S sub-partition (13 new nested sub-measures) then opened all four, and
what it found is that **none of them holds a lever worth landing**:

| level S row | ms | calls | verdict |
|---|---:|---:|---|
| `R_OT_UNION` the PLAIN narrowing walk | 157 | 4,218 | needed by the emissions it feeds |
| `R_OT_UNION` the round-424 loop-entry RETRY | 65 | 1,859 | **49% provably redundant → 32 ms** |
| `R_OT_UNION` the TS2339 union elaboration | 37 | 3,993 | the emission itself |
| `R_IDENT` b1/b2/b3 (clodule / `{}`-annot / enum recv) | 65 + 67 + 40 | 67,067 each | 0 exits, all three load-bearing |
| `R_OT_IDENT` the specialised emission gates | 16 | 4,380 | — |
| `R_OT_IDENT` `getTypeOfSymbol(identSymbol)` | 1 | 4,380 | — |
| `R_OT_IDENT` the `identSymbol == null` branch | 110 | 14,715 | the general path |
| `R_OT_IDENTSYM` `lookupPerFileForNode` | 22 | 59,266 | the same lookup runs up to 3× |
| `R_PRE` the intersection-`never` probe | 35 | 67,258 | — |

The whole inventory of in-row levers came to **~80 ms (0.3%)**: the redundant half
of the union retry (32 ms), the receiver identifier resolved up to three times
per access (≤44 ms), and a `".$propName"` string built at every call for two
emission sites (~5 ms). **That is the round's negative result, and it is what sent
the question up one level.**

## 31. The lever is not IN a row — it is IN FRONT of all of them

The item's structural question was "what is computed above an exit and consulted
only below one". Level S answers: almost nothing, because the rows ARE the exits.
The generalisation that does work is the opposite one — **every one of this
function's ~42 emissions asserts that a property is ABSENT from the receiver**, so
a call whose property is PRESENT has nothing to say, whatever row it would have
exited from. That is round 489's pre-gate, asked once for the whole function
instead of inside one block.

Priced with `--cmamPreGate`, which computes the gate and **honours nothing** (so
the run reproduces the pre-change binary) and splits the body's measured time by
the gate's verdict:

| | calls | ms |
|---|---:|---:|
| the gate's own cost | 67,258 | **124** |
| the body for calls it would SKIP | **20,939 (31%)** | **563** |
| the body for calls it would keep | 46,319 | 1,285 |

**Net ≈ 440 ms ≈ 1.6% of the compile** — a MEASURE of the population, not
`count × mean` (law 1), and the only reason the number is trustworthy.

## 32. The two exclusions, both found by the corpus and neither by inspection

The first cut of the gate — "the property resolves on the receiver's own or
apparent type" — measured **0 emitting calls in a 22,187-call skip set** on the
compiler profile and still **failed 7 corpus baselines**. Both failures are places
where *resolves* and *legal* come apart:

1. **A later-lib member RESOLVES and is still an error.** TS2550 says "not at this
   target", never "does not exist" — the embedded lib declares `RegExp.dotAll`.
   Excluded by name: `LIB_MIN_TARGET_PROPS`, the property names of
   `LIB_MIN_TARGET` ∪ `LIB_MIN_TARGET_SOFT`. (1 baseline.)
2. **A class receiver's two sides are not cleanly separated in our member
   tables** — an INSTANCE type resolves a STATIC member — and TS2576 ("did you
   mean to access the static member") is exactly the diagnostic that says so.
   Excluded by receiver: a `Type.Object` whose symbol is a Class, raw or apparent.
   (6 baselines: `typeofClass`, `classStaticPropertyAccess`, `classSideInheritance1`,
   `classImplementsClass6`, `staticInstanceResolution4`, `staticMemberExportAccess`.)

**Neither was visible in the 0/22,187 measurement**, because the compiler profile
contains no instance-reads-a-static and no under-target lib access. The
zero was true and useless on its own; the 13k-baseline corpus is what made the
gate landable. Read that as the general lesson: a profile-measured zero bounds
the FREQUENCY of a hazard on that profile, never its EXISTENCE.

## 33. The equivalence, measured with a shipped control

`--cmamPreGate` counts, per call, whether the body the gate would have skipped
appended a diagnostic:

| profile | calls | would skip | of those, EMITTED | kept calls that emitted |
|---|---:|---:|---:|---:|
| compiler | 67,258 | 20,939 (31%) | **0** | 57 |
| services | 92,371 | 29,924 (32%) | **0** | 83 |
| harness | 109,826 | 32,463 (29%) | **0** | 89 |

**83,326 skipped calls, 0 emissions**, and the whole emitting population sits in
the kept complement. `--cmamPreGateBogus` (the gate answers yes everywhere) is the
positive control and reports **57**, so the falsifier column is not a dead
instrument — CLAUDE.md's round-765 rule, satisfied by a shipped flag rather than
an argument.

## 34. Verification

* Corpus suite **13,399 → 13,405 / 0 failures / 3 skipped** (+6 `PreGateGuardTest`).
* 8-profile `--listAll` grid, diffed set-for-set in BOTH directions against the
  same binary run with `--cmamPreGate` (which honours nothing, so it IS the
  pre-change output): **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
  eight.** Both directions matter: a gate that fires too often deletes a true
  positive and makes the grid look better.
* `--partitionCheck 2` **EQUIVALENT — 46**.
* **Pin discrimination 5 of 6, across two complementary ablations run SEPARATELY.**
  Fault A (the gate accepts everything) fails 5; fault B (both exclusions removed)
  fails 3, all inside A's set. The pin that holds under both is the probe's own
  bogus control, which by construction ignores the gate's content.
* **Cost gate: rebaselined in the landing commit, with the mechanism named.**
  `globals.lookups` **−6.05%** and `globals.misses` **−6.09%** are the skipped
  bodies' name resolutions no longer happening; `typeOfExpr.calls` **+7.43%**
  (+45,886) is the gate itself — one `getTypeOfExpression(receiver)` per
  invocation, un-memoized (round 737), which is precisely the 124 ms measured
  above. Nothing else moved more than 0.4%; `narrow.walks` +0.03%.
* **Warm A/B (`scripts/ab-warm.sh`, 2 pairs, 6 iters): B wins 2/2, deltas −2.91%
  and −1.41%, median −244 ms (−2.17%)**, arm sd **0.38% / 0.71%** — both inside
  the ~1% quietness criterion, so the verdict is quotable. It CONFIRMS the
  direction; the MAGNITUDE is taken from the partition (−440 ms), because a warm
  rebuild is 11.3 s against the cold 26.5 s the partition was measured on and the
  two cannot be equated (round 791 met the same gap in the same direction).

## 35. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt); P=build/bench/tsc-project-*
# level R + the level-S sub-partition
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSections $P
# the gate: its price, its yield, and the falsifier column
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cmamPreGate $P
# the control: the falsifier column MUST fire
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cmamPreGateBogus $P
```

---

# Round 794 — (ENGINE.2f): the UNION loop-entry retry, substituted

## 36. The headline

The prize was re-measured at HEAD before anything was built (law 1), and it had
survived rounds 792-793 intact: `R_OT_UNION`'s round-424 loop-entry retry still
reaches **1,859 calls for 66 ms** on the compiler profile, of which round 790's
loop-free bracket calls **916 (49%)** provably redundant.

But the item's own warning is what decides the shape of the fix, and it is
right: **this is not (ENGINE.2d)(a), and a skip is not equivalent here.** The
consumer's first test is the IDENTITY

```kotlin
if (loopNarrowed !== rawForNarrowing && loopNarrowed !== neverType) { … }
```

and a loop-free repeat whose flow path crosses a two-antecedent
`FlowBranchLabel` unions `[declared, declared]`, which `getUnionType` mints
FRESH because it does not intern. So the block genuinely runs from a
structurally-washed state, and on the compiler profile it **suppresses 61 times
from exactly there** (services 66, harness 71). Skipping loses those; the
landable form is the SUBSTITUTION — hand the consumer the plain walk's own
result, which preserves both the identity relationship (fresh vs the declared
instance) and the member set, i.e. everything the consumer reads.

## 37. The prize, measured on its own population

The item quoted 32 ms as "49% of 66 ms". That is a `count × mean` extrapolation
— the error every over-estimate in this codebase came from — so the round split
the row instead: `N_U_RETRY_LF` for the loop-free half, `N_U_RETRY` for the
loop-crossing one. Both arms keep exactly one `t()`/`closeN` boundary pair in
both runs, so no round-793 boundary correction applies: this is the "ONE-SPAN
sub-measure whose count does not change".

| row | calls | pre-change (`--verifyUnionRetry`) | after |
|---|---:|---:|---:|
| `R_OT_UNION` the RETRY, **loop-free** half | 916 | **32 ms** | **2 ms** |
| `R_OT_UNION` the RETRY, loop-crossing half | 943 | 42 ms | 50 ms |
| `R_OT_UNION` the PLAIN walk | 4,218 | 183 ms | 168 ms |
| `R_OT_UNION` the TS2339 elaboration | 3,993 | 43 ms | 30 ms |
| level R total | 46,319 | 1,305 ms | 1,237 ms |

**≈30 ms = 0.11% of a check-only compile.** The extrapolation was, for once,
right — but it was right by luck, and the row that says so is the one to quote.
The other rows move ±10 ms between the two runs in both directions, which is
this harness's run-to-run noise at that scale and is why the containing row's
329 → 279 ms is NOT the number.

**No A/B was run, deliberately.** 0.11% is an order of magnitude below the warm
protocol's ±1.0% band and two below the cold one's ±2.0%; a verdict from either
would be noise wearing a sign. `cost_gate.py` decides instead, and it does so to
the unit — see § 39.

## 38. The equivalence, and the control that costs nothing

`--verifyUnionRetry` re-walks and HONOURS the re-walk, so a run under it
reproduces the pre-change binary by construction and IS the grid baseline. It
compares the substituted candidate against the re-walked one at three
granularities — `Type` INSTANCE, union MEMBER-ID SET, and the consumer's own
suppression VERDICT:

| profile | reached | loop-free (compared) | instance-diff | member-diff | VERDICT-diff |
|---|---:|---:|---:|---:|---:|
| compiler | 1,859 | 916 | **0** | **0** | **0** |
| services | 2,526 | 1,191 | **0** | **0** | **0** |
| harness | 2,722 | 1,313 | **0** | **0** | **0** |

**3,420 comparisons, 0 diffs of any kind** — and instance-diff 0 is stronger
than the argument needed: the loop-free mirror does not merely agree, it returns
the very same `Type` instance.

The zero is not a dead instrument, and no deliberately bogus flag was needed to
show it. `--verifyUnionRetryAll` runs the same comparison over the COMPLEMENT —
the loop-CROSSING calls the substitution never serves, where round 424's whole
reason for existing says the two walks must disagree:

| profile | compared | instance-diff | member-diff | VERDICT-diff |
|---|---:|---:|---:|---:|
| compiler | 1,859 | 149 | 101 | **118** |
| services | 2,526 | 166 | 118 | **135** |
| harness | 2,722 | 169 | 121 | **138** |

That is round 790's law applied as intended: **a skip's positive control is its
complement population, and it ships for free.**

## 39. Law 2 does not bite, and the counter says so to the unit

`narrow.walks` **27,256 → 26,340 = −916 exactly**, the substituted population to
the walk. Nothing rose: `typeOfExpr.calls` −0.03%, `globals.lookups` −0.26%,
`spine.nodes` / `typeNode.bypassed` / `mapped.*` +0.00%. Round 788's law (what
is skippable is not thereby recoverable, because a memoized callee's first later
asker pays instead) is checked rather than assumed, and here it does not apply:
the retry's resolutions were driven and cached by the plain walk moments
earlier, so the second walk had no distinct work to move.

## 40. What did NOT work, and it is the more useful half

**The SKIP — the fix the item told us not to write — is empirically
indistinguishable from the substitution everywhere this repository can measure.**
Ablated in (`suppress = false` on the loop-free arm) and run in full:

* the 8-profile grid: **0 added, 0 removed** on compiler, services and harness;
* the corpus suite: **13,419 / 0 failures / 3 skipped** — not one baseline moved.

So the 61/66/71 suppressions the skip loses are all DOWNSTREAM-REDUNDANT: the
retry's fold (`resolveMemberPropertyType(m, propName) != null` for every
non-nullish member) strictly implies the union elaboration's own `memberHasIt`
for the same members, so `missingMembers` is empty a few lines later and the
elaboration would not have emitted anyway.

Two things follow, and they point in opposite directions:

1. **The item's veto was analytically right and empirically unnecessary.** A
   future agent reading "skipping changes the verdict" should know that the
   verdict it changes is, on every profile and every one of 13,419 baselines,
   consumed by nothing.
2. **Ship the provable one anyway.** The substitution and the skip cost the same
   (2 ms vs 0 for a fold over an already-narrowed member list), and only one of
   them is equivalent by construction rather than by "nothing we ran noticed".
   When the provable form is free, "no test caught it" is not a reason to prefer
   the unprovable one — it is only a reason not to spend a round proving it.

The round's other negative result is a fixture-construction trap: **a bare
expression-statement property access is the only shape in a small file whose
plain walk is LOOP-FREE.** In an argument or a `return`, an earlier pass has
already walked the same reference, the round-664 inter-walk memo serves the
walk, `narrowWalkLaunches` does not move, and the bracket answers "unknown" —
the conservative arm. Six candidate fixtures read `loopFree = 0` before that was
understood; the pins' substituted population would have been empty and the two
verifier pins vacuous.

## 41. Verification

* Corpus suite **13,412 → 13,419 / 0 failures / 3 skipped** (+7
  `UnionRetrySubstitutionTest`).
* 8-profile `--listAll` grid, diffed set-for-set in BOTH directions against the
  same binary under `--verifyUnionRetry`: **46/46/46/46/46/46/46/94, 0 added and
  0 removed on all eight.**
* `--partitionCheck 2` **EQUIVALENT — 46**.
* **Pin discrimination 2 of 7**, against fault A (the substitution ignores the
  loop-free bracket and serves every call): `the round-424 loop-entry shapes
  still suppress` and `the substitution emits exactly what the re-walking binary
  emitted`. Fault B (the skip) is caught by **none** of them — and by nothing
  else in the repository either, per § 40; that is reported rather than papered
  over with a pin that could only assert an unobservable.
* **Cost gate rebaselined in the landing commit**, mechanism named: `narrow.walks`
  −916 exactly, nothing rose.

## 42. Reproducing

```bash
CP=$(cat build/bench/cp-cache.txt); P=build/bench/tsc-project-*
# the prize, split by the loop-free bracket (pre-change arm re-walks)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSections --verifyUnionRetry $P
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --cpaSections $P
# the equivalence over the substituted population …
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --verifyUnionRetry $P
# … and the complement, which MUST diverge
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --listAll --verifyUnionRetryAll $P
```
