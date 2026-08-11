# The hash family, opened: co-access says NO, hash QUALITY says yes (round 889)

Round 886 left one structural item on the board and deliberately did not start
it (`tsgo-portability-census.md` § 3):

> Hash probing is **24.3-24.6% of compile-thread samples as a LEAF**. tsgo's
> answer is ~25 narrow `core.LinkStore[K,V]`s, each ONE probe returning a struct
> of CO-ACCESSED fields; tsc goes further and makes it a field on the node.
> **Ours is one map per FACT.** Max single owner is 1.19% with a long tail, so no
> row clears a candidate floor — a checker-wide refactor, priced as such.

This round asked the question that decides whether that refactor is worth
starting: **which containers are probed with the SAME KEY at the SAME SITE**, so
that one probe returning a struct would replace N? The answer is **almost none**
— and the census that established it found the family's real defect on the way
past, which is not how many probes there are but **how badly the keys hash**.

Everything below is measured on round 888's kept warm dumps
(`build/bench/round888/deep{1,2}.txt`, 16,273 compile-thread samples over two
processes, median rebuild ~5,905 ms). Round 870's denominator law applies: a
share is a share of WALL TIME in a fixed window, so ms figures are
`share x 5,905 ms` for THIS round and are not comparable to another round's
without redoing that multiplication.

## 0. First, a correction to round 886's own number

`scripts/round886_hash_owners.py` matches the family with the prefix
`"java.util.HashMap."`. A treeified bucket's frames are
`java.util.HashMap$TreeNode.find` / `.putTreeVal` / `.split` — **`HashMap$TreeNode.`,
not `HashMap.`** — so every sample inside a red-black bucket fell OUTSIDE the
family it belongs to.

| | leaf share |
| --- | ---: |
| round 886's family (`HashMap.` &c) | 19.2% on this binary (24.3/24.6% on round 874's) |
| `HashMap$TreeNode.*` + `compareComparables` + `treeifyBin` | **6.46%** |
| **corrected family** | **26.42%** |

The correction is not cosmetic: the excluded 6.46% is precisely the part with a
mechanism and a fix, and it was invisible for two rounds because of one absent
`$`.

## 1. The co-access census — the question round 886 posed

`scripts/round889_coaccess.py` reads `Checker.kt` through the round-809
length-preserving comment/string stripper, finds every container probe whose key
is a comparable expression (a bare identifier or a simple dotted path — anything
with a call or an operator in it is skipped, because two occurrences of such a
text are not reliably the same value), attributes each to its INNERMOST enclosing
`fun`, and reports every `(function, key)` at which two or more DISTINCT
containers are probed.

Clusters are ranked by the hash-family samples their enclosing function OWNS, not
by static site count — round 732's law, since a cluster of twenty cold sites must
not outrank one on a two-million-call path.

**254 clusters exist. The largest by measured weight:**

| hash samples | maps | function | key | the containers |
| ---: | ---: | --- | --- | --- |
| 79 | 2 | `getTypeFromTypeNodeCore` | `node` | `nodeTypes`, `nodeTypeResolutionInProgress` |
| 59 | 2 | `getTypeOfIdentifier` | `id.text` | `currentLocalTypes`, `currentParamBindingNames` |
| 32 | 2 | `isOptionalProperty` | `symbol.id` | `mappedRequiredMemberIds`, `optionalTupleMemberIds` |
| 30 | 2 | `walkMemoServe` | `root` | `currentLocalTypes`, `narrowedDeclaredTypes` |
| 23 | 2 | `spineExEnterNode` | `name` | `spineExDeclared`, `spineExCands` |
| 22 | 2 | `computeExportedSymbolThroughStarsCore` | `file.fileName` | `visited`, `fileResults` |
| 6 | 4 | `checkAssignmentExpressionCore` | `target.text` | `currentLocalTypes`, `narrowedDeclaredTypes`, `currentLocalDeclTypeNodes`, `currentShadowedNames` |
| 1 | 2 | `getTypeOfSymbol` | `symbol.id` | `symbolTypes`, `symbolTypeResolutionInProgress` |

**Read the first and last rows together and the verdict writes itself.** The two
clusters that ARE tsc's `NodeLinks`/`SymbolLinks` shape exactly — a type cache
plus its in-progress sentinel, same key, same site — are the top cluster at
**79 samples = 0.49% of the profile** and the bottom one at **1 sample**. The
merge saves at most ONE of the two probes on the MISS path only (a cache HIT
never touches the sentinel today), so the top cluster's prize is a fraction of
0.49%, and the whole ranked list is a long tail of 2-container pairs.

So: **the `LinkStore` shape does not port, and it is not a near miss.** Our maps
are not co-accessed, because the checker's per-entity facts are consulted one at
a time from many different sites rather than in the fixed bundles tsgo's ~25
stores group. Round 886's "checker-wide refactor, priced as such" can be closed
as *priced and refused*, not merely unstarted.

The other half of the same census says the same thing from the other direction.
`scripts/round889_keyshape.py` weights every hash sample by the KEY TYPE of the
containers its owner probes:

| class | share of the family |
| --- | ---: |
| unclassified (probes through a property path — `l.symbols`, `result.locals`, …) | 40.8% |
| **map COPY / construction** (`putMapEntries`, `resize`, `<init>`) | **18.0%** |
| probe: `Int`-keyed | 15.8% |
| probe: `String`-keyed | 14.3% |
| everything else | 11.1% |

The largest identified mechanism is not per-entity fact probing at all — it is
**per-scope map copying** (`ctaFnBodyFrame`, `spineArgListOverlay`,
`ctaSpineEnter`, `EpochMap.<init>`), which is the known (C2)/(WARM.18) family,
and the largest probe class is String-keyed NAME resolution. A LinkStore fixes
neither.

## 2. What the census found instead: the keys do not hash

Splitting the corrected family by the container being probed:

| group | family share | of which TREEIFIED | tree share of group |
| --- | ---: | ---: | ---: |
| **`nodeToFlow`** — `Long`, `(pos shl 32) or end` | **2.02%** | **1.60%** | **79%** |
| **`Relation.cache`** — `Long`, `(srcId shl 32) or tgtId` | **0.97%** | **0.54%** | **56%** |
| `nodeTypes` — AST data-class key | 0.74% | 0.25% | 34% |
| everything else (mostly `String`-keyed) | 22.69% | 4.07% | 18% |

Top owners of treeified-bucket samples:

| share | owner |
| ---: | --- |
| 1.11% | `FlowGraphBuilder.recordFlow` |
| 0.48% | `FlowGraph.<init>` |
| 0.47% | `Checker$Relation.get` |
| 0.25% | `Checker.getTypeFromTypeNodeCore` |
| 0.23% | `Checker.ctaSpineEnter` |
| 0.22% | `Checker.lookupPerFileForNode` |

A bucket becomes a red-black tree at eight entries. Two of the top three owners
probe a map keyed by a **packed pair of 32-bit ints**, and that is the whole
mechanism:

```
java.lang.Long.hashCode(v)  ==  (int) (v xor (v ushr 32))
nodeKey(pos, end)           ==  (pos.toLong() shl 32) or (end.toLong() and 0xFFFFFFFF)
                     hash   ==  pos xor end
```

For an AST node `end` is `pos` plus the node's LENGTH, so `pos xor end` is
dominated by its low bits and its entire range is about "the set of node lengths
in this file" — **a few hundred distinct values no matter how many nodes there
are**. `HashMap`'s own spread (`h xor (h ushr 16)`) cannot recover a dimension
that the XOR has already destroyed.

Modelled on a 20,000-node file (`nodeToFlow` is per FILE), lengths drawn from a
realistic spread:

| packing | buckets used / 32,768 | max bucket | keys in treeified buckets |
| --- | ---: | ---: | ---: |
| `(pos shl 32) or end` | **278** | **1,765** | **98.3%** |
| `… * 0x9E3779B97F4A7C15` | 14,896 | 6 | **0%** |

At the program-wide flow-node population (262,404 keys, capacity 524,288) the
un-mixed packing fills **399 buckets, max 23,118, 99.9% treeified**; the mixed
one fills 202,500 with max 7 and none treeified.

`Relation.packKey(a, b)` is the same construction over type ids, hashing to
`srcId xor tgtId` — which collapses an N x N key space onto one dimension (every
pair on the diagonal hashes to 0) and measures 56% treeified.

## 3. What landed

**(HASH.1)(a) — `nodeKey` gets a multiplicative finalizer.** One line in
`Types.kt`: the packed key is multiplied by the golden-ratio odd constant
`0x9E3779B97F4A7C15`, the same one `LongKeyMap` already uses for the same reason.
Multiplication by an ODD constant modulo 2^64 is a **bijection**, so the key
stays exact and collision-free as an identity; only its bit pattern changes. It
fixes `FlowGraphBuilder.nodeToFlow`, `Binder.nodeToSymbol` and
`Binder.moduleInstanceStates` at once.

**The soundness argument is that nothing may depend on the key's VALUE**, and two
things could:

- **Unpacking.** No consumer recovers `pos`/`end` from a node key. The only
  unpacking site in the repo is `PassTiming`'s `redundantPairNanos`, whose key is
  a *site-id* pair and not a node key.
- **Iteration order.** All three containers are `mutableMapOf`, i.e. a
  **LinkedHashMap whose iteration is INSERTION order**, so the one place a
  `nodeToSymbol` is iterated (`TypeScriptCompiler`'s symbol frontier) cannot
  move. Had any of them been a plain `HashMap`, this would have been an
  iteration-order change — the rounds-754/776/778 hazard, invisible in every
  output diff.

`Binder`'s synthetic-node guard `key != nodeKey(-1, -1)` compares against the
packer's own answer, so it is unaffected.

## 4. What is NOT claimed

**No wall-time number.** The mechanism is worth ~1.6% of samples on the
`nodeToFlow` group alone, which is at the edge of what two batches on this box
have settled recently (rounds 840(c), 858, 886 all produced a sign-consistent
batch that did not replicate). The claim is the **bucket arithmetic**, which is
deterministic and reproducible offline, plus `cost_gate.py` at +0.00% as the
control that no checker decision moved.

**`cost_gate.py` +0.00% is the EXPECTED answer here, not a green light** (round
876): this change moves no counter by construction — same keys, same answers,
same order. It is read as evidence that the semantics are untouched.

## 5. Queued

- **(HASH.1)(b)** — the same finalizer for the CHECKER's packed id-pair keys:
  `Relation.packKey` / `packRelationKey` (0.97% of the family, 56% treeified),
  `resolvedPropertyTypes`, `ts2403IdentityStack`, `relationComparisonStack`,
  `elaborationStack`, `functionElaborationStack`. All are membership/lookup only
  — none is iterated, which is the same soundness check as § 3.
- **(HASH.1)(c)** — `nodeTypes`, keyed by an AST data class, is 0.74% with 34%
  treeified AND pays a deep structural `hashCode`. Its structural equality is
  deliberate and load-bearing (`getTypeFromTypeNodeCore`'s comment: same-shaped
  annotation nodes SHARE one cached resolution), so this is not a re-key — it
  needs a cached-hash node or nothing.
- **The 4.07% of treeified samples in String-keyed maps is unexplained.**
  `String.hashCode` is not degenerate, so treeification there means genuinely
  large per-bucket populations — worth one census before anything is designed.
