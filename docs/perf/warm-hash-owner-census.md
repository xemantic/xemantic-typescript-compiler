# (WARM.19) Where the residual HashMap/HashSet family lives — round 894

**Offline census. No build, no benchmark, no source change.** Every number is
re-derived from dumps already on disk (`build/bench/round893/deep{1,2}.txt`,
`build/bench/round888/deep{1,2}.txt`) with one new aggregator,
`scripts/round894_hash_owners.py`. Nothing here is a measured saving; § 9 is a
ranked CANDIDATE list with an upper bound attached to each candidate, and
CLAUDE.md's standing caveat applies to all of it — **a JFR leaf share is not a
wall-clock price** (round 623 eliminated a 5.3% leaf and measured −0.3%).

## 0. Why this census, and what changed under it

Rounds 889/890 removed the degenerate-hash mechanism: `HashMap$TreeNode` as a
leaf was **5.91% of warm wall = 348.8 ms/rebuild at round 888** and is **zero
samples in 16,055 at round 893**. What is left of the HashMap/HashSet family is
therefore ordinary hashing plus *linear* bucket probing, so the lever can no
longer be "a better hash". It has to be **fewer map operations**, a **different
container**, or a **cheaper key**.

This document answers: where do those milliseconds live, by owner and by
operation, replicated across four processes.

**The headline is a shape, not a target.** The family is **1,449 ms/rebuild =
26.5%** of a 5,461 ms warm rebuild spread over **351 distinct owners** whose
largest is **55.5 ms = 1.0%**. Round 874's law holds for the sixth take running:
the ROW is the wrong unit. What this census adds is that even the *mechanism
family* is not one lever — the largest coherent mechanism is 223 ms — and that
the three biggest single-site prizes are all **key-shaped**, not container-shaped.

## 1. Method, and the six traps it honours

`scripts/round894_hash_owners.py`, run over 893 ×2 and 888 ×2. Each trap below
is a CLAUDE.md entry; the script implements the countermeasure and the docstring
names it.

1. **Substring, never prefix.** The family test is `("HashMap" in cls or
   "HashSet" in cls) and cls.startswith("java.util.")`. The prefix form
   `"java.util.HashMap."` silently excludes `HashMap$TreeNode.*` and
   `HashMap$Node.*` — one absent `$` hid 6.46% of compile-thread samples for two
   rounds. `HashMap$TreeNode` is reported as its own sub-family so its **zero**
   stays visible rather than merely absent.
2. **A share is a share of WALL TIME in a fixed window.** Every ms figure uses
   **that process's own `medianMs`**, read from its `warm-jfr*.log`: 893 →
   5,416.8 / 5,505.7; 888 → 5,918.6 / 5,891.2. Round-level means are 5,461 and
   5,905. Without this an unchanged cost "rises" 8% between the two rounds
   purely because the rebuild got shorter.
3. **Compile thread only** (`xtsc-deep-stack`): 29/27/44/37 samples on other
   threads were dropped.
4. **Owner attribution, not leaf attribution** — leaf frames move under C2
   inlining, owners replicate. § 2 shows this instrument catching itself doing it.
5. **Truncation refused.** Max observed stack depth 212 / 174 / 177 / 236 —
   well clear of the 512 cap and nowhere near the 5-frame default the script
   exits on.
6. **Replication is the gate.** Every row carries all four processes. Rows are
   also given a **1σ Poisson bar** (`√n / n` on the round's combined sample
   count, ~0.68 ms per sample), because a 20-sample row carries ±22% and a
   within-round "disagreement" smaller than that is noise, not a split.

### 1a. Two measures, and why the second one had to be invented

| measure | definition | 893 | 888 |
| --- | --- | ---: | ---: |
| **STRICT** | the sample's LEAF class matches the family test | **1,300.7 ms** (23.81%) | **1,560.5 ms** (26.43%) |
| **EXTENDED** | the stack contains a map frame ANYWHERE; charged to the nearest non-stdlib frame BELOW the OUTERMOST map frame | **1,449.1 ms** (26.53%) | **1,747.4 ms** (29.59%) |

STRICT is the measure rounds 886/888/893 used, and it reproduces round 893's
published 1,300.5 ms to **1,300.7** — the instrument's validity check.

EXTENDED exists because STRICT and a plain nearest-owner rule are both blind to
the same thing twice over. A `HashMap` can only re-enter user code through the
key, so **everything above a map frame is part of that map operation**:

- `String.hashCode` / `String.equals` / `Integer.equals` running under
  `HashMap.hash` / `HashMap.getNode` — STRICT charges these to the *String* and
  *other stdlib* families;
- **the key's own `hashCode()`/`equals()` when the key is an own-code object** —
  invisible to a "nearest non-stdlib owner" rule because the nearest non-stdlib
  frame **is** the `hashCode` itself. That is § 5, and it is a live instance of
  the round-471 AST-data-class hazard.

The rule "own-code frames above a map frame belong to the caller below it" would
be wrong for `computeIfAbsent` / `merge` / `forEach(BiConsumer)`, which run a
user lambda inside the map. **As of round 894 the codebase uses Kotlin's
`getOrPut`, which is a `get` then a `put` with no frame inside the map** — grep
before reusing the rule if any of those is introduced.

## 2. The instrument catching itself: an owner "mover" that is a C2 inlining migration

Round 888 § (C) warned that a large single-row delta is as often an inlining key
split as a real change. It happened again, and the script now detects it by
carrying each owner's **TOTAL** cost (all leaf families) beside its map cost:

| owner | map ms 888 | map ms 893 | owner TOTAL 888 | owner TOTAL 893 |
| --- | ---: | ---: | ---: | ---: |
| `Checker.getTypeOfSymbol` | 0.7 | **28.2** | 29.8 | 28.2 |
| `Checker.collectBindingNames` | 6.2 | 15.3 | 14.9 | 17.4 |

`getTypeOfSymbol` looks like a +27.5 ms regression and is not one. Direct
inspection of the stacks: at round 888, **34 of its 35 samples in process 1 had
the stdlib frames inlined away** (the leaf *was* `Checker.getTypeOfSymbol`), so
the same work sat in the "own code" family; at round 893, **0 of 30** were
inlined and the `HashSet.add` / `HashMap.put` frames are all present. Total cost
flat, family membership swapped.

**The consequence for reading the 888→893 family delta:** part of the −298 ms
(EXTENDED) is inlining migration in both directions, not removal. The delta is
still dominated by a real mechanism — `HashMap$TreeNode` went 386.5 → **0.0** ms
— and the paired A/B in round 893 measured −8.18% independently, so the
*direction* is safe. But **no single row's cross-round delta may be quoted here
without its TOTAL column**, and the script prints both.

## 3. The operation split — the fix class is decided here

EXTENDED, ms/rebuild, mean of two processes:

| operation | 893 | 888 | 893 p1 \| p2 |
| --- | ---: | ---: | --- |
| **lookup** | **943.2** | 1,129.3 | 924.2 \| 962.3 |
| **insert** | **309.6** | 315.7 | 313.1 \| 306.1 |
| **copy-construct** | **169.7** | 278.4 | 172.6 \| 166.9 |
| remove | 15.6 | 12.3 | 13.0 \| 18.3 |
| iterate | 7.1 | 6.9 | 3.4 \| 10.9 |
| other | 3.7 | 4.7 | 3.4 \| 4.1 |

**65% of the family is LOOKUP.** That rules out most of the obvious fixes at a
stroke: a lookup cannot be batched, deferred, or made lazy, and it does not
allocate. Only three things make a lookup cheaper — **ask fewer times** (hoist,
memo, restructure), **make the key cheaper** (intern, pack, use an int), or
**leave the map** (a side array indexed by a dense id).

**Copy-construct is only 170 ms and it fell 278 → 170 between the rounds**, i.e.
the per-scope copy arc (rounds 869/891/892) already took the large end of it.
What remains is § 6.

### 3a. Where the operations enter, round 893

| stdlib entry point nearest the owner | ms/rebuild |
| --- | ---: |
| `HashMap.get` | 290.8 |
| `HashSet.contains` | 281.0 |
| `LinkedHashMap.get` | 231.3 |
| `HashMap.put` | 169.1 |
| `HashSet.add` | 140.5 |
| `HashMap.containsKey` | 140.1 |
| `HashMap.<init>` (copy ctor) | 102.4 |
| `LinkedHashMap.<init>` (copy ctor) | 51.0 |
| everything else | < 11 each |

## 4. The sub-family split — and one thing it CANNOT tell you

EXTENDED ms/rebuild (STRICT shown for contrast; the STRICT HashMap row absorbs
all `HashSet`/`LinkedHashMap` work because both delegate to inherited
`java.util.HashMap` methods):

| sub-family | 893 STRICT | 893 EXT | 888 STRICT | 888 EXT |
| --- | ---: | ---: | ---: | ---: |
| `HashMap` | 1,222.1 | 672.8 | 1,105.5 | 683.3 |
| `HashSet` | 5.1 | 417.4 | 5.8 | 372.1 |
| `LinkedHashMap` | 72.1 | 357.2 | 100.9 | 304.7 |
| **`HashMap$TreeNode`** | **0.0** | **0.0** | 348.3 | 386.5 |
| `LinkedHashSet` | 1.4 | 1.7 | 0.0 | 0.7 |

**THE CAVEAT THAT MUST TRAVEL WITH THIS TABLE.** `LinkedHashMap` INHERITS almost
every hot method from `HashMap` (`getNode`, `putVal`, `resize`, `hash`), so a
`mutableMapOf` operation prints as `java.util.HashMap.*` unless a
LinkedHashMap-specific frame (`afterNodeInsertion`, `newNode`, the overridden
`get`, `LinkedHashMap$Entry`) survives inlining. **The LinkedHashMap row is a
LOWER BOUND on ordered-map cost, not a measurement of it.**

Crossing it with the operation split: `LinkedHashMap` insert 51.4 + copy-construct
65.7 = **117.1 ms** is where the ordered-container penalty could bite. But only
the *delta* over a plain `HashMap` — the extra `newNode`, `afterNodeInsertion`
and two link pointers — is recoverable, and **this instrument cannot measure that
delta.** Do not read "117 ms of LinkedHashMap insert" as "117 ms available from
`mutableMapOf` → `HashMap()`". The 231 ms of `LinkedHashMap.get` is very nearly
free of the penalty (`get` adds one not-taken `accessOrder` branch).

## 5. The key-side slice — the round-471 hazard, priced

Samples where an OWN-CODE frame is running INSIDE a map operation, i.e. the key's
own `hashCode()`/`equals()`:

| round | ms/rebuild | per process |
| --- | ---: | --- |
| 893 | **61.9** | 73.0 \| 50.9 |
| 888 | **59.9** | 58.6 \| 61.2 |

Stable across rounds; the within-893 spread is at the Poisson bar for ~180
samples. The top contributors at round 893:

| ms | frame |
| ---: | --- |
| 6.5 | `Checker$AliasedCondKey.equals` |
| 5.1 | `MethodDeclaration.hashCode` |
| 4.4 | `QualifiedName.hashCode` |
| 4.1 | `Parameter.hashCode` |
| 3.4 | `FunctionDeclaration.hashCode` |
| 2.4 each | `UnionType` / `KeywordTypeNode` / `Comment` `.hashCode` |
| 2.0 each | `VariableDeclaration` / `PropertyDeclaration` / `PropertyAccessExpression` `.hashCode` |

**Every one of those AST rows is a Kotlin `data class hashCode()` recursing over
a subtree** — CLAUDE.md's round-471 entry ("NEVER key a HashMap/HashSet by an AST
NODE"). It is live, it is ~47 ms of the 62, and § 7 names the two maps
responsible.

### 5a. And the String-key slice, which is the biggest single mechanism in the family

Leaf frames under a map operation (i.e. the cost of hashing and comparing the
KEY), both rounds:

| leaf | 893 in-map | 888 in-map |
| --- | ---: | ---: |
| `java.lang.String.equals` | **42.9** | 70.8 |
| `java.lang.String.hashCode` | **24.8** | 37.7 |
| `java.lang.Integer.equals` | **18.0** | (not separated) |
| `Intrinsics.areEqual` | 1.7 | 3.6 |
| **String key total** | **67.7** | 108.5 |

`String.hashCode` caches its result in the `String` object, so the 24.8 ms is
the *first* hash of each freshly-allocated identifier string.
`String.equals` begins with `if (this == anObject) return true`, so **the 42.9 ms
is precisely the case where the probe string and the stored key are different
instances holding equal characters.** § 9 candidate (1) is the direct consequence.

## 6. The mechanism grouping — and the residue that refuses to group

EXTENDED, ms/rebuild. Order is first-match-wins and the residue is printed, so
the partition is honest.

| mechanism | 893 | 888 | 893 p1 \| p2 | owners |
| --- | ---: | ---: | --- | ---: |
| **residue (unclassified)** | **609.5** | 718.2 | 575.6 \| 643.3 | **279** |
| type-system caches | 222.8 | 231.9 | 221.7 \| 223.9 | 15 |
| scope-frame copies | 201.7 | 244.3 | 205.3 \| 198.2 | 20 |
| per-file NAME resolution | 193.9 | 231.4 | 206.0 \| 181.9 | 15 |
| module / export resolution | 124.2 | 152.1 | 120.7 \| 127.6 | 12 |
| flow graph / narrowing | 71.8 | 149.1 | 73.0 \| 70.6 | 9 |
| INV.4 reach classifiers / memos | 25.2 | 20.3 | 27.3 \| 23.1 | 5 |
| **TOTAL** | **1,449.1** | **1,747.4** | | 351 |

**The residue is the finding, not a defect in the grouping.** Its 279 owners are
led by `aliasedConditionInitializer` (22.5), `EpochMap.get` (15.3),
`uniqueNestedVarDeclByName` (13.9), `spineUbdListDecls` (13.6),
`calleeParamGivesNoContext` (12.6) — and **229 of the 279 are under 4 ms each,
holding 238.5 ms between them.** They are ordinary checker predicates asking
ordinary maps ordinary questions. No owner-level fix, and no mechanism-level
fix, reaches that 238 ms. Only a CROSS-CUTTING change to what a key costs does.

Concentration, round 893, EXTENDED:

| top N owners | ms | % of family | % of rebuild |
| ---: | ---: | ---: | ---: |
| 1 | 46.9 | 3.4% | 0.86% |
| 5 | 199.0 | 14.3% | 3.64% |
| 10 | 344.6 | 24.8% | 6.31% |
| 30 | 655.5 | 47.2% | 12.00% |
| 80 | 1,052.1 | 75.7% | 19.27% |
| 351 | 1,389.5 | 100% | 25.44% |

(The top-N table is computed on the owner counter, which excludes the ~60 ms of
samples with no non-stdlib frame below the outermost map frame; hence 1,389.5
against the 1,449.1 family total.)

## 7. The top owners

Round 893 mean, with its 1σ Poisson bar, the owner's TOTAL cost across all leaf
families, and all four processes. `SPLIT?` marks a within-round disagreement
exceeding max(35%, 3σ) on a row over 8 ms — at these sample counts most of them
are Poisson, and each is called out where it matters.

| # | owner | mean | ±1σ | n | TOT | 893 p1 | 893 p2 | 888 p1 | 888 p2 | op mix |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | `Checker.getTypeFromTypeNodeCore` | 55.5 | 4.3 | 163 | 57.8 | 63.4 | 47.5 | 45.6 | 56.1 | lookup 71% insert 26% |
| 2 | `Checker.spineArgListOverlay` | 41.2 | 3.7 | 121 | 49.3 | 43.7 | 38.7 | 34.7 | 35.0 | copy 90% |
| 3 | `Checker$EpochMap.<init>` | 38.1 | 3.6 | 112 | 40.5 | 38.9 | 37.3 | 34.0 | 39.3 | copy 100% |
| 4 | `Checker.lookupPerFileForNode` | 38.1 | 3.6 | 112 | 41.2 | 36.1 | 40.0 | 52.1 | 47.3 | lookup 100% |
| 5 | `Checker.lexLevelHasName` | 35.0 | 3.5 | 103 | 41.2 | 34.1 | 36.0 | 47.7 | 43.0 | lookup 100% |
| 6 | `FlowGraphBuilder.recordFlow` | 34.0 | 3.4 | 100 | 35.4 | 36.1 | 31.9 | 81.7 | 89.6 | insert 100% |
| 7 | `Checker.lookupPerFile` | 29.6 | 3.2 | 87 | 29.6 | 35.5 | 23.8 | 20.2 | 21.8 | lookup 100% |
| 8 | `Checker.checkIdentifierResolved` | 28.6 | 3.1 | 84 | 35.0 | 32.1 | 25.1 | 34.0 | 32.0 | lookup 100% |
| 9 | `Checker.getTypeOfSymbol` | 28.2 | 3.1 | 83 | 28.2 | 20.5 | 36.0 | 0.7 | 0.7 | insert 100% — § 2 |
| 10 | `Checker.spineArithFnFrame` | 27.5 | 3.1 | 81 | 29.2 | 25.2 | 29.9 | 27.5 | 31.3 | copy 98% |
| 11 | `Checker.getTypeOfIdentifier` | 26.2 | 3.0 | 77 | 43.6 | 34.1 | 18.3 | 32.5 | 36.4 | lookup 100% |
| 12 | `Checker.aliasedConditionInitializer` | 22.4 | 2.8 | 66 | 23.1 | 18.4 | 26.5 | 24.6 | 25.5 | lookup 83% |
| 13 | `SuffixNameSet.materialize` | 19.4 | 2.6 | 57 | 19.4 | 19.8 | 19.0 | 23.1 | 21.1 | insert 100% |
| 14 | `Checker$Relation.get` | 18.7 | 2.5 | 55 | 18.7 | 13.0 | 24.4 | 42.7 | 56.1 | lookup 100% |
| 15 | `Checker.getPropertyOfType` | 18.4 | 2.5 | 54 | 18.7 | 22.5 | 14.3 | 19.5 | 18.2 | lookup 100% |
| 16 | `Checker.getUnionType` | 18.4 | 2.5 | 54 | 61.9 | 15.0 | 21.7 | 14.5 | 12.4 | insert 100% |
| 17 | `Checker.computeExportedFnDeclsThroughStarsCore` | 17.4 | 2.4 | 51 | 17.4 | 19.1 | 15.6 | 12.3 | 15.3 | lookup 74% |
| 18 | `Checker.globalsForFile` | 16.7 | 2.4 | 49 | 16.7 | 21.1 | 12.2 | 21.7 | 10.9 | lookup 100% |
| 19 | `Checker.collectBindingNames` | 15.3 | 2.3 | 45 | 17.4 | 21.1 | 9.5 | 5.8 | 6.6 | insert 93% — § 2 |
| 20 | `Checker.computeExportedSymbolThroughStarsCore` | 15.3 | 2.3 | 45 | 15.3 | 15.7 | 14.9 | 10.8 | 15.3 | lookup 91% |
| 21 | `Checker$EpochMap.get` | 15.3 | 2.3 | 45 | 15.3 | 10.9 | 19.7 | 18.8 | 16.0 | lookup 100% |
| 22 | `Checker$NameScope.has` | 14.3 | 2.2 | 42 | 23.8 | 14.3 | 14.3 | 31.1 | 19.7 | lookup 100% |
| 23 | `Checker.propertiesRelatedTo` | 14.3 | 2.2 | 42 | 20.7 | 11.6 | 17.0 | 24.6 | 17.5 | lookup 100% |
| 24 | `Checker.uniqueNestedVarDeclByName` | 13.9 | 2.2 | 41 | 16.3 | 13.6 | 14.3 | 12.3 | 13.8 | lookup 90% |
| 25 | `Checker.resolveGenericPropertyType` | 13.9 | 2.2 | 41 | 17.7 | 9.5 | 18.3 | 13.0 | 10.2 | lookup 82% |

Rows 26–36 (all 11–14 ms, all replicating): `markAliasReferenced`,
`spineUbdListDecls`, `resolveModuleSpecifierRelative`, `spineTavIdentifierCore`,
`spineCaCopyTop`, `calleeParamGivesNoContext`, `FlowGraph.<init>`,
`walkNeverDestructure`, `unresolvedLexOf`, `dddStmts`,
`resolveImportedFunctionLikeDecl`.

Two rows whose CROSS-ROUND fall is real and already explained by the round-893
note (their TOTAL fell with them): `FlowGraphBuilder.recordFlow` 85.7 → 34.0
(round 889's `nodeKey` finalizer) and `Checker$Relation.get` 49.4 → 18.7
(round 890's `packIdPair`).

## 8. What each top owner is actually hitting — read from the source

`xemantic-typescript-compiler-core/src/commonMain/kotlin/`. Line numbers are
source lines; note `Checker.kt` is 178,676 lines, so any `LineNumberTable`
figure from a stack or `javap` needs +65,536 (or +131,072) before it means
anything.

### (1) `Checker.getTypeFromTypeNodeCore` — 55.5 ms, lookup 71% / insert 26%

- **Map:** `CheckerState.nodeTypes = HashMap<TypeNode, Type>()` (Checker.kt:166),
  plus `nodeTypeResolutionInProgress = HashSet<TypeNode>()` (Checker.kt:183)
  which is `add`ed and `remove`d around **every miss**.
- **Key:** an **AST node**. `TypeNode` is a `sealed interface` implemented by
  data classes (`TypeReference`, `UnionType`, `KeywordTypeNode`, …), so every
  probe is a **deep structural `hashCode()`** and every hash collision a deep
  `equals()`. This is the round-471 hazard, and §5's `QualifiedName.hashCode`,
  `UnionType.hashCode`, `KeywordTypeNode.hashCode` rows are it.
- **Population:** per type-annotation occurrence, program-wide, gated by
  `cacheable` (no TP scope / no alias args / no inference namespace / not
  `isPerFileDependentRefNode`).
- **Iteration order consumed?** No — only `get`/`put`.
- **What could replace it:** nothing cheap. **The structural key is deliberate**
  (Checker.kt:103888–103891): identically-shaped annotation nodes in different
  files intentionally SHARE one cached resolution, and `isPerFileDependentRefNode`
  is the guard that keeps that sound. Re-keying on `nodeId`/identity would make
  the cache strictly finer, raising the miss rate — i.e. it converts map cost into
  resolution cost by an amount **this data cannot bound**. The tractable half is
  `nodeTypeResolutionInProgress`: it is a re-entrancy sentinel, wanted only on the
  miss path, and could be an identity-keyed structure or a small depth-bounded
  array instead of a second deep-hashing probe per miss.
- **Prize if this owner's map work went to zero: 55.5 ms = 1.02%** — upper bound,
  and not attainable, because the cache is what stops the resolution being redone.

### (2) `Checker.spineArgListOverlay` — 41.2 ms, copy-construct 90%

- **Map:** `SpineArgCtx.funcParams: Map<String, FuncParamInfo>` (Checker.kt:4764).
  Copies: `effective.toMutableMap()` at every statement list that declares a
  `FunctionDeclaration`; `effective - shadowed` (which allocates a fresh map);
  and `incoming.funcParams.toMutableMap()` + `classCtorParams.toMutableMap()` on
  the `ModuleBlock` arm.
- **Key:** `String` (a function name).
- **Population:** per statement-list owner, memoized in
  `spineArgListCtxMemo = HashMap<Int, SpineArgCtx>` (Checker.kt:4750). So the
  copy runs once per list owner — but each copy is O(all visible function names),
  which on `checker.ts` is large.
- **Iteration order consumed?** Not established here — the map is read by name.
- **What could replace it:** a **chained scope map** (a small per-list overlay
  plus a parent pointer, lookup walking the chain) turns an O(visible-functions)
  copy into an O(declared-here) allocation. Lookup is only 6% of this owner's
  cost, so trading copy for a short chain walk is the right direction. **The
  round-869/891/892 undo-log shape does NOT apply**: the memo RETAINS every ctx
  object, which is round 892's explicit retention disqualifier.
- **Prize: 41.2 ms = 0.75%**, upper bound; a chain still costs an allocation per
  list, so the realistic share is smaller.

### (3) `Checker$EpochMap.<init>` — 38.1 ms, copy-construct 100%

- **Map:** `EpochMap(m) : HashMap(m)` (Checker.kt:395–406) — a whole-map copy of
  `Map<String, Type>`.
- **Remaining call sites, post-round-892:** ~10 `CcetFrame(node,
  EpochMap(top.localTypes), EpochSet(top.paramBindings), …)` /
  `CpaFrame(…)` constructions (Checker.kt:1331, 1365, 1389, 1482, 1534, 2054,
  2083, 2127, 2151, 2171) plus ~20 ad-hoc
  `currentLocalTypes = EpochMap(currentLocalTypes)` snapshot installs.
- **Key:** `String`. **Population:** per ccet/cpa scope frame.
- **Iteration order consumed?** Not established here.
- **What could replace it:** this is the family
  `docs/perf/cta-frame-copy-families.md` § 3 **REFUSED** — "THREE spine stacks
  plus ≥12 ad-hoc install/restore sites whose restore is a POINTER SWAP, not a
  pop", so the LIFO precondition cannot even be *stated* over it. Round 891
  DERIVED its price at 14–24 ms from a census of 471,726 copied entries; **this
  census measures the surviving `EpochMap` copy constructor directly at 38.1 ms**,
  i.e. ~1.6–2.7× the derivation. Both numbers can be right (the derivation
  covered `EpochMap(localTypes)` only), but the discrepancy is a reason to
  re-run the existing instrument rather than to trust either.
- **The instrument already exists and needs no new code:** `FrontEnd`'s copy
  census (`CP_EPOCH_MAP` … `CP_CTA_LOCAL`, SpineDispatch.kt:4837–4848) and the
  `copyamp<r>` amplification arms.
- **Prize: 38.1 ms = 0.70%**, upper bound; unattainable while the precondition
  is unstatable, so the actionable step is a **re-census**, not a conversion.

### (4)(7)(18) The per-file name-resolution triple — 84.4 ms combined

`lookupPerFileForNode` 38.1 + `lookupPerFile` 29.6 + `globalsForFile` 16.7, all
**lookup 100%**, all replicating. Decomposed by which map they enter (round 893,
mean of two processes):

| site | ms | replication (893 p1\|p2, 888 mean) |
| --- | ---: | --- |
| `name in moduleOnlyGlobalNames` — `HashSet<String>.contains` | **42.9** | 47.1 \| 38.7, 888: 45.0 |
| `perFileScope[fileName]` — `LinkedHashMap.get` on a **file PATH string** | **34.7** | 36.8 \| 32.6, 888: 34.5 |

- **`moduleOnlyGlobalNames`** is `HashSet(moduleLocalNames)` (Checker.kt:11006),
  probed as the **very first statement** of `lookupPerFileForNode`
  (Checker.kt:11152) whose own comment says it "sits on getTypeOfIdentifier's
  fallback, which is consulted for ~2M identifiers per self-compile". So this is
  a String-keyed set membership test paid ~2 M times to answer a boolean.
- **`perFileScope`** is `MutableMap<String, SymbolTable> = mutableMapOf()`
  (Checker.kt:5410), `SymbolTable = MutableMap<String, Symbol>` (Types.kt:201).
  The key is a **full file path** — 60–100 characters to hash and to compare.
- **Iteration order consumed? NO — audited.** All 11 `perFileScope` references
  in `Checker.kt` are `[fileName]` reads (11081, 12091, 21915, 34020, 37338,
  37397), one `containsKey` (11112) and one `[…] =` write (10968); there is no
  `.keys`/`.values`/`.entries`/`.forEach`/`.iterator`/`.sorted` and no
  `for (x in perFileScope)`. `moduleOnlyGlobalNames` is a membership set only.
- **A second probe hides in `globalsForFile`**: line 11112 is
  `name in moduleOnlyGlobalNames && perFileScope.containsKey(fileName)`, i.e.
  the path string is hashed there too, and `lookupPerFile` immediately hashes it
  again at 11081.
- **What could replace it:** for `perFileScope`, resolve the `SymbolTable` ONCE
  per file rather than per name — a field on the `SourceFile` node, an int file
  index, or at minimum a one-entry `(lastFileName, lastScope)` memo compared by
  reference. For `moduleOnlyGlobalNames`, an interned-name id (candidate 1) turns
  the probe into an array read; a hash-bitset pre-filter answers "definitely not
  present" in one load without touching the set.
- **Prize: 77.6 ms = 1.42%** for the two sites together, upper bound.

### (5) `Checker.lexLevelHasName` — 35.0 ms, lookup 100%

- **Maps:** `l.symbols.containsKey(name)`, `name in unresolvedLexRootExcluded`,
  `ex.containsKey(name)` (Checker.kt:33801–33809) — up to **three String-keyed
  probes per LEVEL**, and the caller walks the whole level chain per identifier.
  Together with `lexLevelHasType` this is **37.1 ms** (888: 48.6), replicating.
- **Key:** `String`. **Population:** per (identifier × enclosing scope level).
- **What could replace it:** the container is not the problem — the *number of
  probes* is. Interned name ids (candidate 1) makes each probe an int hash;
  fusing the three probes into one per level would need a merged table, which
  the INV.4(c)(ii) hybrid semantics (§ CLAUDE.md: `symbols` only, never
  `existing`) explicitly forbid mixing.
- **Prize: 35.0 ms = 0.64%**, upper bound.

### (6) `FlowGraphBuilder.recordFlow` — 34.0 ms, insert 100%

- **Map:** `nodeToFlow: MutableMap<Long, FlowNode> = mutableMapOf()`
  (Flow.kt:603) — **a `LinkedHashMap` with BOXED `Long` keys**, written once per
  recorded node (round 864: 262,404 entries).
- **Key:** `nodeKey(pos, end)`, a packed `Long` with round 889's odd-constant
  finalizer (Types.kt:319).
- **Iteration order consumed? NO — audited: the only uses in Flow.kt are `get`
  (lines 252, 269, 396), `put` (794) and `.size` (661).** Round 889's KDoc warns
  that the three `nodeKey`-keyed containers are `mutableMapOf` and that a plain
  `HashMap` "would have made this an iteration-order change" — that warning is
  about `Binder.nodeToSymbol`, whose frontier IS iterated. It does not bind
  `nodeToFlow`.
- **What could replace it:** `LongKeyMap<FlowNode>` — which already exists
  (LongKeyMap.kt:44) and is already used for the three intern caches. Removes
  the `Long` boxing, the `LinkedHashMap` entry linking and `afterNodeInsertion`.
  `FlowGraph.<init>` (12.6 ms, lookup 100%) reads the same map back.
- **Prize: 34.0 + 12.6 = 46.6 ms = 0.85%**, upper bound. **Deflate it
  deliberately**: `Long.equals` is only 1.0 ms in the whole family, so the
  recoverable part is boxing allocation + link maintenance, and round 801's law
  ("an allocation count is not a cost") and round 893's GC budget (~1.7% total)
  both argue the allocation half is small. Treat 46.6 as a ceiling well above
  the likely answer.
- **Same shape, unmeasured here:** `Binder.nodeToSymbol` and
  `Binder.moduleInstanceStates` are the other two `mutableMapOf<Long, …>`
  containers — and `nodeToSymbol` **is** iterated, so it is NOT interchangeable.

### (9) `Checker.getTypeOfSymbol` — 28.2 ms, insert 100%

- **Set:** `symbolTypeResolutionInProgress = HashSet<Int>()` (Checker.kt:177) —
  a **boxed-`Integer` set**, `add`ed and `remove`d around every `symbolTypes`
  miss (Checker.kt:105988, 106011). The value cache beside it,
  `symbolTypes`, is already an `IntKeyMap` (M0.3(vi) converted it for exactly
  this reason); the sentinel set was left behind.
- **Key:** boxed `Int` — note `Integer.equals` is **18.0 ms** across the family.
- **Population:** per `getTypeOfSymbol` cache miss.
- **Iteration order consumed?** No — `add`/`remove` only.
- **What could replace it:** an int-keyed open-addressed set, or an `IntKeyMap`
  used as a set. `memberResolutionInProgress` (Checker.kt:194) is the identical
  shape, and `relationComparisonStack` / `elaborationStack` /
  `functionElaborationStack` are `HashSet<Long>` twins — but round 890 measured
  `relationComparisonStack`'s max LIVE size at **27**, so those three are
  bounded and are NOT the same candidate.
- **Prize: 28.2 ms = 0.52%**, upper bound. **Read § 2 first** — this row is flat
  in TOTAL across the two rounds and only became *visible* at 893.

### (12) `Checker.aliasedConditionInitializer` — 22.4 ms, lookup 83%

- **Map:** `aliasedConditionInitCache = HashMap<AliasedCondKey, Expression?>()`
  (Checker.kt:847), key `data class AliasedCondKey(startFlow: FlowNode,
  aliasName: String, rootOfName: String)` (Checker.kt:849).
- **Key cost:** a fresh key object per probe, then a `hashCode` folding one
  identity hash and two String hashes, then `equals` comparing two Strings —
  `AliasedCondKey.equals` is the **largest single key-side row at 6.5 ms** (§ 5).
  `FlowNode` is a plain class, not a data class (CLAUDE.md round 865 requires
  this), so the identity hash is cheap; the Strings are not.
- **What could replace it:** pack to a primitive key — `FlowNode.id` is an int
  and the two names could be interned ids (candidate 1), giving a `Long`/
  `LongKeyMap` key with no allocation and no String compare.
- **Prize: 22.4 ms = 0.41%**, upper bound.

## 9. The ranked candidate list

Prizes are **upper bounds** — "if this owner's map work went to zero" — over a
5,461 ms warm rebuild. Nothing below has been measured as a saving.

---

### (1) Intern identifier / name strings in the Scanner — **prize ≤ 67.7 ms (1.24%)**

**Mechanism.** `Scanner.scanIdentifier` builds every identifier with
`text.substring(start, pos)` (Scanner.kt:769) — no interning. Every occurrence of
`kind` in the program is a *distinct* `String` instance. Consequently
`String.equals` cannot take its `this == anObject` fast path when a probe string
meets a stored key, and it walks the characters: **42.9 ms/rebuild inside map
operations at round 893 (70.8 at 888)**, plus **24.8 ms** of first-hash
(`String.hashCode` caches per instance, so this is one hash per fresh instance).
Interning at the scanner makes the equality a pointer compare and amortises the
hash to once per distinct name in the program.

**Why it is the top candidate.** It is the only item here whose prize is
*spread across all 351 owners* — including the 238 ms in 229 sub-4 ms owners that
no targeted fix reaches — and it is a handful of lines in one function.

**Risk.** Low, and it is a **same-answers change**: `String` equality is by
value, so no comparison anywhere can change verdict. Three things to watch.
(a) The intern table is itself a hash lookup per token, so the change is net
only if each interned string is used as a map key more than about once — **this
is unpriced and must be established before landing**, by round 759's
amplification, not by a wall A/B (67 ms is ~1.2%, inside `ab-warm.sh`'s band).
(b) Interning must not retain a `String` view onto the whole file text —
`substring` on modern JVMs copies, so this is fine, but a "no-substring-on-hit"
intern table keyed on a char range must produce a fresh `String` on miss.
(c) The parser has other `text.substring` sites (string/number literals, JSX
text) which must NOT be interned — literal text is not a map key.

**Second-order benefit, unpriced:** it makes the AST data-class `equals()` of
§ 5 (~47 ms) cheaper too, since those compare `String` fields.

---

### (2) The per-file name-resolution triple — **prize ≤ 77.6 ms (1.42%)**, in two independent halves

**(2a) `perFileScope[fileName]` → resolve the `SymbolTable` once per file —
≤ 34.7 ms (0.64%).** Replicates to within 6% across all four processes
(36.8/32.6/29.7/39.3). The key is a full file PATH hashed and compared on every
name lookup. Mechanism: hold the resolved `SymbolTable` on the `SourceFile` node,
or key by a dense int file index, or a one-entry reference-compared memo.
**Risk: LOW — the iteration-order audit CLAUDE.md's HashMap-vs-LinkedHashMap
entry demands is done: all 11 `perFileScope` references are `[fileName]` reads,
one `containsKey` and one write; nothing iterates it.** Same-answers if the memo
is keyed by the same string. Note the path is hashed **twice** on the hot path —
`globalsForFile`'s `perFileScope.containsKey(fileName)` (11112) then
`lookupPerFile`'s `perFileScope[fileName]` (11081) — so a resolved-table hand-off
removes two probes, not one.

**(2b) `moduleOnlyGlobalNames.contains(name)` → an int-id or bitset pre-filter —
≤ 42.9 ms (0.79%).** Replicates (47.1/38.7/53.5/36.4). ~2 M probes per
self-compile of a `HashSet<String>` answering a boolean.
**Largely subsumed by candidate (1)** — with interned names this becomes an int
probe. Independently, a hash-bitset pre-filter (`bits[h & mask]`) answers
"definitely absent" in one load with no false negatives, which is sound because
the set is FROZEN after init step 1b.
**Risk:** the INV.3(c)/(d) resolution semantics are the most safety-critical in
this file — a wrong answer here silently resolves a name to a foreign module's
local. A pre-filter is safe only in the "definitely absent" direction; the
positive direction must still probe the set. **Same-answers if built that way**;
an 8-profile grid is still warranted because the blast radius is name resolution.

---

### (3) `nodeToFlow` → `LongKeyMap<FlowNode>` — **prize ≤ 46.6 ms (0.85%)**

**Mechanism.** `MutableMap<Long, FlowNode> = mutableMapOf()` (Flow.kt:603) —
LinkedHashMap plus boxed `Long`, ~262 k inserts per rebuild. `LongKeyMap` exists
and is already the house idiom for the three intern caches. Owners:
`recordFlow` 34.0 (insert 100%) + `FlowGraph.<init>` 12.6 (lookup 100%), both
replicating, both with their cross-round fall already explained by round 889.

**Risk: LOW and audited.** `nodeToFlow` is never iterated (`get` at Flow.kt:252,
269, 396; `put` at 794; `.size` at 661), so the round-754/776/778 iteration-order
hazard does not apply. **Same-answers.** Round 889's KDoc warning about
`mutableMapOf` binds `Binder.nodeToSymbol` (which IS iterated) — do NOT extend
this change to it without a separate audit.

**Deflate this one deliberately:** `Long.equals` is 1.0 ms in the whole family
and GC is ~1.7% of the rebuild, so most of the 46.6 ms is the LinkedHashMap link
maintenance and the extra indirection, not the boxing. Expect well under the
ceiling.

---

### (4) `symbolTypeResolutionInProgress` → an int set — **prize ≤ 28.2 ms (0.52%)**

`HashSet<Int>` (Checker.kt:177) with boxed keys, `add`+`remove` per
`getTypeOfSymbol` miss; its value-cache sibling `symbolTypes` was already
converted to `IntKeyMap`. `memberResolutionInProgress` is the identical shape.
**Risk: LOW, same-answers** — a membership set, never iterated. **But read § 2
before believing the prize**: this row's TOTAL is flat across 888 and 893 and it
became visible only through a C2 inlining change, so the honest reading is
"28 ms, first seen at 893, unconfirmed at 888".

---

### (5) `nodeTypeResolutionInProgress` → identity or depth-array — **prize ≤ ~20 ms, NOT separable from (6)**

The re-entrancy sentinel beside `nodeTypes`, `HashSet<TypeNode>`, paying a deep
data-class `hashCode` **twice per cache miss** (add + remove). It is the tractable
half of owner (1): unlike `nodeTypes` it has no sharing semantics to preserve —
it only needs "is this exact resolution already on the stack", which identity
answers. **Risk: LOW, same-answers**, provided identity is genuinely equivalent
(two structurally-equal nodes are different resolutions, so identity is if
anything MORE correct). **The prize cannot be separated from `getTypeFromTypeNodeCore`'s
55.5 ms by this data** — the miss share is 26% of that owner, which bounds it at
roughly 14–20 ms but only under an assumption this census cannot test.

---

### (6) `spineArgListOverlay` → a chained scope map — **prize ≤ 41.2 ms (0.75%)**

O(all visible function names) map copy per statement list that declares a
function. **Risk: MEDIUM.** The undo-log shape of rounds 869/891/892 does NOT
apply — `spineArgListCtxMemo` RETAINS each context, which is round 892's explicit
retention disqualifier; the applicable shape is a persistent overlay chain. That
changes lookup from O(1) to O(depth), which is acceptable only because lookup is
6% of this owner. **Needs an 8-profile grid**: it touches arity checking
(TS2554/TS2555), and `spineArgListOverlay`'s shadowing rules are bug-compatible
with a deleted legacy walker.

---

### (7) `AliasedCondKey` → a packed primitive key — **prize ≤ 22.4 ms (0.41%)**

A per-probe key allocation whose `equals` compares two Strings — the largest
single key-side row (6.5 ms). `FlowNode.id` is an int; with interned names
(candidate 1) the whole key packs into a `Long`. **Risk: LOW, same-answers**,
but the packing must be a BIJECTION with round 890's odd-constant finalizer, not
a hash — CLAUDE.md's `packIdPair` entry is the reference and `docs/perf/hash-key-spread.md`
§ 5 the sweep.

---

### (8) Re-census the surviving `EpochMap` copies — **not a change; an instrument run**

**≤ 38.1 ms (0.70%)**, replicating (38.9/37.3/34.0/39.3). Round 891 DERIVED
14–24 ms for `EpochMap(localTypes)` and REFUSED the conversion because the LIFO
precondition cannot be stated over three spine stacks plus ≥12 pointer-swap
install sites. This census measures the copy constructor at **1.6–2.7× the
derivation**. The instrument to settle it already exists and needs no new code:
`FrontEnd`'s `CP_EPOCH_MAP` census and the `copyamp<r>` amplification arms
(SpineDispatch.kt:4837). **Do this before proposing any conversion**, per
round 801's law that the produced-versus-consumed ratio comes first.

---

### What is NOT a candidate, and why

- **`mutableMapOf` → `HashMap()` as a sweep.** The measurable LinkedHashMap
  penalty is at insert/copy-construct (117.1 ms combined), and only the *delta*
  over a plain HashMap is recoverable — an amount this instrument cannot
  measure. The 231 ms of `LinkedHashMap.get` is very nearly free of the penalty.
  Per-field audits (as CLAUDE.md requires) are worth doing where a map is proven
  hot AND proven order-independent; a blanket sweep is unpriced.
- **A better hash.** `HashMap$TreeNode` is at exactly zero. Round 890's sweep is
  closed and `packIdPair` is the sole id-pair packer.
- **Anything targeting the residue's 279 owners individually.** 229 of them are
  under 4 ms. Only candidates (1) and (2b) reach that mass.

## 10. A bonus finding outside the map family: 116 ms of whole-source `String.indexOf`

Found while separating the key-side leaves, replicating almost exactly:

| round | ms/rebuild | owners | biggest single owner |
| --- | ---: | ---: | ---: |
| 893 | **116.3** | 51 | 8.8 ms (`checkBigintArbitraryIdentifierPin`) |
| 888 | 115.8 | 50 | 10.2 ms |

**111.2 ms of it is in 49 `Checker.check*` pin walkers** scanning whole source
text — `checkBigintArbitraryIdentifierPin`, `checkInfiniteConstraints`,
`checkPreEmitCountMismatchPins`, `checkDisallowedBlockScopedParseErrors`,
`checkComplexRecursiveCollections`, … This is the rounds-859/862/863
whole-program-text class in its *post-fix* form: those rounds replaced regexes
with "an EXACT hand-written scan anchored on a literal via `indexOf`", and the
scans are now 2.1% of a warm rebuild, invisible row by row (no owner above
0.16%) and invisible to `cost_gate.py`. `docs/perf/whole-program-regex-census.md`
is the existing census of the regex half; this is its `indexOf` sibling and it
has never been counted. **Not priced further here** — it is a separate arc, and
the gating shape (`checkExportAsNamespaceSelfCycle`'s cheap-guards-above-the-scan
idiom) is already known to work.

## 11. What round 896 did with this list — two taken, three refused

Round 896 priced candidates (2a), (3), (4), (5) and (7) with
`--mapCensus` / `--perFileScopeAmp N` / `--flowMapReplay N` (`MapCensus.kt`,
`scripts/round896-census.sh`) BEFORE building any of them, and the headline is
that **the ceilings in § 9 are ceilings in the strong sense: two of the five are
an order of magnitude above the answer.** § 9's own instruction to "deflate this
one deliberately" was right, and should be applied to the two that remain.

| # | ceiling here | measured | verdict |
| --- | ---: | ---: | --- |
| (3) `nodeToFlow` -> `LongKeyMap` | 46.6 ms | **17.9 / 17.6 ms** (two draws) | **TAKEN** |
| (2a) `perFileScope[path]` | 34.7 ms | probe 10-51 ns x 643,968 removed = **6.4-33 ms** | **TAKEN** |
| (4) `symbolTypeResolutionInProgress` | 28.2 ms | **24,232 adds, MAX LIVE 3** => <= ~2-5 ms | refused |
| (5) `nodeTypeResolutionInProgress` | ~14-20 ms | **59,283 adds, MAX LIVE 14** => ~3-5 ms | refused |
| (7) `AliasedCondKey` | 22.4 ms | gated on candidate (1) | refused |

Three things a next reader should carry:

1. **§ 2's inlining-migration warning was under-applied to its own table.** The
   `getTypeOfSymbol` row (28.2 ms, insert 100%) is flagged there as "first seen
   at 893, unconfirmed at 888" — and the population settles it: the only
   `java.util` insert in that function is `symbolTypeResolutionInProgress.add`,
   which runs **24,232 times per rebuild**. 28.2 ms over 24,232 adds is
   **1,164 ns per add**, ~20x a boxed `HashSet` probe on a table that never
   exceeds **3** live entries. The row is real cost in that owner; it is not
   that set. Any § 9 candidate whose owner row is not divided by its own
   population count is a candidate that has not been priced.
2. **Both sentinel sets are bounded, and round 890's law is why that matters:**
   max live 3 / 14 / 6 means their tables never leave the initial 16 slots, so
   they cannot treeify however bad the hash and there is no structural prize.
3. **The replay instrument's TOTAL replicates and its put/get SPLIT does not**
   (28.3/4.0 vs 22.1/10.8 ms across two runs, totals 32.3 vs 32.9). Quote the
   total; the split moves with C2's choices about where the loop's bounds check
   lands.

## 12. Reproducing this

```
python3 scripts/round894_hash_owners.py                       # 893 vs 888
python3 scripts/round894_hash_owners.py --rounds 893,888,874 --top 40
python3 scripts/round894_hash_owners.py --owner Checker.getTypeFromTypeNodeCore
```

The script carries its own trap documentation, refuses a truncated dump, prints
the Poisson bar and the owner TOTAL beside every row, and flags cross-round
movers whose TOTAL is flat as INLINE MIGRATION. Adding a round means adding one
line to `ROUNDS` with **that round's per-process `medianMs`**, taken from its
`warm-jfr*.log`.
