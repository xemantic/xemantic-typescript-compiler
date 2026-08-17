# Round 912 — the four unpriced round-903 hot-path candidates, priced

**Verdict: ALL FOUR REFUSED. The largest is 0.18% (9.4 ms) at a deliberately
generous ceiling; all four SUMMED are 15.9 ms (0.30%), still under the arc's
~17 ms floor for ONE low-risk change. One of them (the ambient sandwiches) is
additionally KILLED BY READING — the `inline` it asks for is not expressible.**

Binary: `0c3ca302` + throwaway counters (reverted). Profile:
`build/bench/tsc-project-637d5746` (78 files, 46 errors). Warm,
`BenchMain <proj> 6 2` and `<proj> 6 3`, instrumented medians **5,065.7** and
**5,170.8 ms**. Stated denominator, per PLAN-PHASE-5.md, is the round-899
warm rebuild **5,242.6 ms**, so **1% = 52.4 ms** and the **~17 ms floor is
0.324%**. (The plan's "1% = 54.3 ms" is against the JFR denominator 5,429 ms;
either reading refuses every candidate below.)

**No build of a fix was made and no amplifier was needed.** Every refusal is
population x a generous per-operation ceiling, checked against round 896's
divide-and-refuse test, exactly as round 904 refused the boxed-key family.

## 0. Where these four came from, and why the prior was already weak

They are the queue's "ALSO RECORDED, UNPRICED, from the round-903 hot-path
audit". Unlike every other candidate in this arc **they were never ranked by any
measurement at all** — `grep -rn` over `docs/` finds not one mention of
`mappedNodeTypeKey`, `collectTypeofGuardNames`, `spineOsWithAmbient` or
`spineTcDispatchWithAmbient`. They were ranked by *reading allocation shapes*,
and three of the four are pure-allocation candidates, which CLAUDE.md already
prices at zero by default:

> round 801 removed **367,189 `String` allocations** and measured **exactly
> 0 ms**; round 893 put warm GC at **~92-98 ms of a ~5.4 s rebuild (~1.7%)**.

## 1. The bar, set before any measurement (round 896's test)

What each candidate would have to cost PER OPERATION to reach 17 ms:

| candidate | ops/rebuild | ns/op needed for 17 ms |
|---|---:|---:|
| (1) `mappedNodeTypeKey` key build | 25,987 | **654** |
| (2) `collectTypeofGuardNames` &c set alloc | 22,798 | **746** |
| (3) the two ambient sandwiches | 2,841 | **5,983** |
| (4) `NarrowFlowMemo` construction | 31,768 | **535** |

Against this repo's own measured anchors, every one of those is physically
impossible:

* a whole `HashMap` get whose key **recursively hashes AND `equals` a 2.76-node
  AST subtree** is **15.09 ns** (round 903, arm A);
* a **recursive walk** over that same subtree is **12.88 ns** (round 903, arm C);
* a boxed `HashMap<Long,·>` probe is **8.53 ns**, a `LongKeyMap` probe **~2 ns**
  (round 904);
* an equal-but-distinct ~10-char `String.equals` is **14.6 ns** (round 897);
* a `nowNanos()` probe boundary is **97-202 ns warm** (round 850) — so 654 ns is
  the cost of ~4 timestamp pairs, to build a 13-character string.

**A general constant this yields, reusable for free:** at a generous **150 ns**
per allocation a pure-allocation candidate needs **> 113,000 allocations per
rebuild** to clear the floor, and **> 340,000** at a realistic 50 ns. Against a
whole-spine population of 856,962 nodes that is a real bar — and none of these
four reaches it.

## 2. The census

Throwaway counters at each site, printed after the LAST measured rebuild
(counters reset at the top of every measured iteration, so the numbers are
per-rebuild). **Two independent processes agree to the last digit on all 22
counters** — the census is deterministic, which is its own falsifier — and
`mappedNodeTypeKey calls = 110,780` reproduces `docs/perf/cost-counters.txt`'s
`typeNode.bypassed` exactly, which is a second, independent control.

    (1) mappedNodeTypeKey calls=110780 unindexed=0 noOwner=0 foreign=84793 KEYED=25987
    (1) fp: nsEntries=9008 tpNonNull=18637 tpEntries=23796 aliasNonNull=974
            aliasEntries=1849 chars=332257 empty=294
    (2) typeofGuard spine=15859 legacy=0 nonEmpty=45 |
        truthy calls=6939 nonEmpty=2247 plusBaseEntries=6
    (3) spineOsWithAmbient=54 (fast=47) spineTcDispatchWithAmbient=2787 (fast=2730)
    (4) NarrowFlowMemo alloc=31768 put=424774 grow=10857

---

## 3. Candidate (1) — `mappedNodeTypeKey` — **REFUSED at 0.09-0.18%**

### Mechanism

`Checker.kt`'s `getTypeFromTypeNodeBypassed` calls `mappedNodeTypeKey(node)` for
the INV.5(c) `mappedNodeTypes` cache. The function, in order: read `nodeId`;
`owningSourceFile(node)` (a PARENT-CHAIN CLIMB to the `SourceFile`); a
`String`-keyed probe `fileResults[owner.fileName]?.locals !== currentFileLocals`;
and only then a `StringBuilder`, an ns-stack loop, **two `entries.sortedBy { }`
copies**, a `toString()` and a `NodeCtxKey` allocation.

### Population

| | |
|---|---:|
| calls | **110,780** |
| rejected before any key work | **84,793** (76.5%) |
| — of which `foreign-file` | **84,793 (all of them)** |
| — of which `unindexed` / `no-owner` | **0 / 0** |
| **keys actually built** | **25,987** (23.5%) |
| `entries.sortedBy{}` invocations | **19,611** (18,637 tp + 974 alias) |
| mean `currentTypeParamScope` size, when non-null | **1.277** entries |
| mean `currentTypeAliasArgs` size, when non-null | **1.898** entries |
| mean built key length | **12.79 chars** (332,257 / 25,987) |
| built keys that are EMPTY | **294** (1.1%) |
| map ops on the built key | get 25,987 + put 19,589 = **45,576** |

**The queue's "~88 k/rebuild" is wrong in both directions at once.** It is a
transcription of a stale in-source comment at `Checker.kt` ("this is not the hot
loop — 88k calls"), not a measurement: the function is CALLED 110,780 times
(26% more than the comment says) and BUILDS A KEY 25,987 times (**3.4x fewer**
than the queue attributes to it).

### Price

Each rate below is set 3-10x above its nearest measured anchor, deliberately.

| item | ops | generous ns | ms |
|---|---:|---:|---:|
| `StringBuilder` + ~4.7 appends + `toString` (12.79 chars) | 25,987 | 150 | 3.90 |
| `entries.sortedBy{}` over 1.28 / 1.90 entries (list copy + fresh comparator + `Collections.sort` + write-back) | 19,611 | 200 | 3.92 |
| `NodeCtxKey` allocation | 25,987 | 10 | 0.26 |
| `String`-key probe premium over a `LongKeyMap` (uncached 13-char hash + `equals`) | 45,576 | 28 | 1.28 |
| **CEILING** | | | **9.36 ms = 0.179%** |
| midpoint (rates halved) | | | **~4.7 ms = 0.090%** |

**REFUSED by 1.8x at the ceiling and 3.6x at the midpoint.**

### Two things the candidate never named

* **`sortedBy` really does run** — the hope that the scopes are usually null is
  false: a type-param scope is in force for **71.7%** of built keys and only
  **1.1%** of keys are empty. What kills the price is that those maps hold
  **1.28** entries, so each sort is a 1-element `Collections.sort` that returns
  immediately.
* **The bigger half of the function is not in the candidate and a fix would not
  remove it.** `owningSourceFile` (a parent-chain climb) and the `fileResults`
  `String`-keyed probe run on **all 110,780** calls, ~76% of them only to answer
  "foreign file, no key". At ~25 ns each that is ~5.5 ms — comparable to the
  named mechanism, and structurally required by the gate. Even the WHOLE
  function, everything in it, at these generous rates is ~15 ms: still under the
  floor.

---

## 4. Candidate (2) — `collectTypeofGuardNames` &c — **REFUSED at 0.028%**

### Mechanism

`collectTypeofGuardNames(cond)` allocates `mutableSetOf<String>()` (a
`LinkedHashSet` + its `LinkedHashMap`), walks the condition, and returns the set;
the only thing every caller does with it is `isNotEmpty()`, then
`set = set + guarded` (a `Set.plus`, which allocates a third set and copies both
operands). Its sibling `collectArithTruthyNarrowableNames` has the same shape.

### Population

| site | calls | non-empty |
|---|---:|---:|
| `collectTypeofGuardNames` — spine `IfStatement` branch edge (`spineArithEnterNode`) | **15,859** | **45 (0.28%)** |
| `collectTypeofGuardNames` — legacy `checkArithmeticInStatement` `IfStatement` arm | **0** | 0 |
| `collectArithTruthyNarrowableNames` — `&&` right-operand edge | **6,939** | **2,247 (32.4%)** |
| `Set.plus` executions | **2,292** | — |
| total base entries copied by the typeof `plus`es | **6** | — |

**The premise is CORRECT and it is the reason the candidate is worthless**: the
guard site allocates a set 15,859 times to answer "empty" 99.72% of the time.
It is a textbook wasted allocation worth **0.9 ms**.

### Price

| item | ops | generous ns | ms |
|---|---:|---:|---:|
| `mutableSetOf<String>()` — 2 objects, no table until first put | 22,798 | 40 | 0.91 |
| `Set.plus` — new `LinkedHashSet` + two `addAll`s over ~1-3 elements | 2,292 | 250 | 0.57 |
| **CEILING** | | | **1.48 ms = 0.028%** |

**REFUSED by 11.5x.**

### Finding on the side

The LEGACY arm runs **zero** times on this profile — the walker's `IfStatement`
statement arm is fully superseded by the spine edge. That is a bound on its
frequency here, **not** evidence it is dead: round 753's law (an ablation that
counts zero has tested nothing) applies, and the corpus may well reach it.

---

## 5. Candidate (3) — the two ambient sandwiches — **KILLED BY READING**

### Two independent refutations, either one sufficient

**(a) The fix does not exist.** Both `spineOsWithAmbient` and
`spineTcDispatchWithAmbient` hand `block` on to a **recursive, non-inline**
function (`spineOsApplyTps(lists, 0, block)` / `spineTcApplyLevels(levels, 0,
block)`). Marking the wrapper `inline` makes that pass-through a compile error
unless `block` is declared `noinline` — and a `noinline` parameter is
materialised as a lambda object exactly as it is today. So `inline` buys
**nothing at all** for the 64 calls that take the recursive path, and only the
call-site frame for the 2,777 that do not.

**(b) The premise "on a measured-hot path" is false, and nothing ever measured
them.** Both are behind several pre-gates plus a reach classifier — the OS one
fires only at an object-binding-pattern rest declarator or a reached
`SpreadAssignment`, the TC one only at a `TypeAssertionExpression`/`AsExpression`
whose type is a bare-`Identifier` `TypeReference` and whose reach status is
`TC_SHARED`.

| | calls/rebuild | fast path (no ancestor level) |
|---|---:|---:|
| `spineOsWithAmbient` | **54** | 47 |
| `spineTcDispatchWithAmbient` | **2,787** | 2,730 |
| **total** | **2,841** | 2,777 |

### Price

Even at a generous **100 ns** per call for the *entire* wrapper (closure,
sandwich, save/restore), the family is **0.28 ms = 0.005%** — **refused by 60x**.
2,841 calls is one third of one percent of a single pass over the spine's
856,962 nodes.

---

## 6. Candidate (4) — `NarrowFlowMemo` default parameter — **REFUSED at ≤0.09%, and the fix is unsound**

### Mechanism

`narrowTypeFromFlow` / `narrowTypeFromFlowCore` / `narrowTypeFromFlowFollowLoopEntry`
each declare `memo: NarrowFlowMemo = NarrowFlowMemo()`. Every recursive call
passes `memo` explicitly, so the default fires **only at an outermost walk
entry** — the `$default` bridge allocates one memo per launched walk. The
constructor allocates the object plus `IntArray(32)` x3 and
`arrayOfNulls<Type>(32)`, and explicitly `fill`s the key array with `EMPTY`
(~624 bytes TLAB-allocated and zeroed, plus 128 bytes of explicit fill).

### Population

| | |
|---|---:|
| constructions | **31,768** |
| `putIfDeeper` calls | **424,774** (mean **13.37** per memo) |
| `grow()` calls | **10,857** — **34.2%** of memos outgrow 32 slots |

Cross-check: `cost-counters.txt` records `narrow.walks 31,961` on a nearby
commit — 0.6% away, i.e. the population is one memo per LAUNCHED narrowing walk,
which is exactly what the code intends. (Round 735's "70,037 walks" is a
pre-853 figure and must not be used here.)

### Price

| item | ops | generous ns | ms |
|---|---:|---:|---:|
| 5 allocations, ~624 B zeroed, + a 128 B `fill` | 31,768 | 150 | **4.77** |
| **CEILING** | | | **4.77 ms = 0.091%** |
| midpoint (70 ns) | | | **2.2 ms = 0.042%** |

**REFUSED by 3.6x at the ceiling.** 535 ns per construction — what 17 ms would
require — is ~1.2 bytes/ns, roughly 30x slower than JVM TLAB zeroing.

### And the obvious fix is a CORRECTNESS hazard, not merely a small prize

* `narrowTypeFromFlowCore` explicitly handles RE-ENTRANT outermost walks: "reset
  the per-request budget … at an OUTERMOST entry (`narrowLiveDepth == 0` ⇔ not
  inside any walk, **including re-entrant walks triggered by callee resolution
  below**)". A single shared instance would be cleared and overwritten by such a
  re-entrant walk while the outer walk still depends on it — and a wrong memo
  serve here is a WRONG NARROWED TYPE, which is round 736's whole soundness
  argument (`served`'s depth/height disjunct) undone from underneath.
* A shared memo must `clear()` its **largest-ever** capacity on every walk, and
  **34.2%** of walks already grow past 32 slots. The replacement's own cost is
  therefore not obviously smaller than the thing it replaces — round 899's law
  (price a container swap NET) applies.

That combination makes it a MEDIUM/HIGH-risk change for at most 4.8 ms.

---

## 7. The family total

| # | candidate | population | ceiling ms | ceiling % | verdict |
|---|---|---:|---:|---:|---|
| 1 | `mappedNodeTypeKey` key build | 25,987 keys (of 110,780 calls) | **9.36** | 0.179% | REFUSED (1.8x) |
| 4 | `NarrowFlowMemo` default | 31,768 | **4.77** | 0.091% | REFUSED (3.6x), fix unsound |
| 2 | `collectTypeofGuardNames` &c | 22,798 | **1.48** | 0.028% | REFUSED (11.5x) |
| 3 | the two ambient sandwiches | 2,841 | **0.28** | 0.005% | KILLED BY READING (60x) |
| | **ALL FOUR TOGETHER** | | **15.9** | **0.303%** | **still under the 17 ms floor** |

**No candidate clears 17 ms. Nor do all four combined, at ceilings.**

## 8. What a next agent may reuse

Deterministic populations per warm rebuild on the compiler profile (both
processes identical):

* `mappedNodeTypeKey`: **110,780** calls, **84,793** foreign-file rejects
  (unindexed 0, no-owner 0), **25,987** keys built, **19,611** `sortedBy`
  invocations, mean key **12.79 chars**, mean tp scope **1.277** entries, mean
  alias args **1.898** entries, ns-stack appends **9,008**.
* From that one site alone: **110,780** `owningSourceFile` parent-chain climbs
  and **110,780** `String`-keyed `fileResults` probes.
* `collectTypeofGuardNames`: **15,859** at the spine anchor (**45** non-empty),
  **0** at the legacy walker arm. `collectArithTruthyNarrowableNames`: **6,939**
  (**2,247** non-empty). `Set.plus`: **2,292**.
* `spineOsWithAmbient` **54**; `spineTcDispatchWithAmbient` **2,787**.
* `NarrowFlowMemo`: **31,768** constructions, **424,774** puts (13.37/memo),
  **10,857** grows (34.2% exceed 32 slots).

**The threshold constant, which refuses a new pure-allocation candidate for
free:** at a generous 150 ns per allocation a site needs **> 113,000
allocations/rebuild** to reach 17 ms, and **> 340,000** at a realistic 50 ns.
Compare the boxed-key twin (round 904): **~1.7 M map operations** at the 6.58 ns
premium. Both bars sit above almost every per-node population in this compiler,
whose spine visits 856,962 nodes.

## 9. What surprised me

1. **A queue population can be a transcribed SOURCE COMMENT.** The "~88 k/rebuild"
   attached to candidate (1) traces to a comment inside `getTypeFromTypeNode`
   ("this is not the hot loop — 88k calls"), and the queue applied it to the
   wrong quantity: the calls are 110,780 and the key builds 25,987. A number in
   a KDoc is not a measurement, and it aged 26% in one direction while being read
   3.4x wrong in the other.
2. **Two of `mappedNodeTypeKey`'s three reject branches never fire** on this
   profile (unindexed 0, no-owner 0). 100% of the 84,793 rejects are the
   foreign-file gate.
3. **The guard-set site allocates a `LinkedHashSet` 15,859 times to answer
   "empty" 15,814 times** — the most egregious wasted allocation in the four, and
   worth 0.9 ms. That is the clearest single illustration of round 801's law in
   this arc.
4. **Candidate (3)'s fix is not expressible in Kotlin.** A closure handed to a
   recursive non-inline callee cannot be inlined away; `noinline` restores the
   allocation exactly. A candidate can be dead on grounds of the LANGUAGE, before
   any population is counted — and reading the callee, not the wrapper, is what
   shows it.
5. **Candidate (4)'s "obvious" fix would be a soundness bug**, because the walk
   is re-entrant at depth 0 by design. The cheapest-looking of the four is the
   riskiest.
6. **Three of the four were pure-allocation candidates and none was ever in a
   profile.** The audit that produced them read allocation SHAPES. Round 801
   (367,189 `String` allocations = 0 ms) and round 893 (warm GC ~1.7% of wall)
   between them already price that whole genre near zero; this round is the
   fourth confirmation.
