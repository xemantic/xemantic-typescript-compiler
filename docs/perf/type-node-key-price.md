# (WARM.30) — the price of `nodeTypes`' deep AST-VALUE key (round 903)

**Verdict: REFUSED at 0.085% (4.60 ms) against an arc floor of ~0.31% (~17 ms).**
The 57.1 ms JFR row is over by **6.3x**, and the reason is now measured rather
than argued: the "deep" hash is **2.76 nodes deep**, not the 25-40 its own
implied rate required.

Binary: `1a20ded2`. Profile: `build/bench/tsc-project-*` (78 files, 46 errors).
Warm, `BenchMain <proj> 6 3 <tiers>`, median rebuild 5,353-5,410 ms; the round's
stated denominator is **5,429 ms**. Captures under `build/bench/round903/`
(final binary) and `build/bench/round903-batch1/` (replication).

## 0. The candidate and why it survived to be measured

`state.nodeTypes` (`Checker.kt`) is a `HashMap<TypeNode, Type>` keyed by the AST
**value**. Every concrete `TypeNode` is a `data class … : NodeBase()`, so the
generated `hashCode()`/`equals()` recurse the subtree — round 471's hazard,
sitting in the checker's hottest resolution cache. `NodeBase`'s `nodeId` /
`parent` / `kindId` are body `var`s and so are NOT in `componentN`, which is why
each node carries a free, unhashed identity a successor key could use.

It was the ONE row in this arc that survived round 898's admission test. 354,131
deep-hash operations per rebuild against 57.1 ms is 161 ns each, and CLAUDE.md
recorded it as "worth believing" **because** the key is a recursively hashing
data class. What nobody had measured was the mean subtree — and the whole
belief rested on it.

## 1. The census (`--typeNodeKeyCensus` / tier `typenodekey`)

    calls=287062  hits=116999  misses=59283  bypassed=110780  UNINDEXED=0
    deep hashes/rebuild = get 176,282 + sentinel add/remove 118,566 + put 59,283 = 354,131
    PROBE-weighted  key subtree: mean 2.7567 nodes, max 337
      buckets 0..10 then 11+:  0 64910 146366 14827 28721 10800 5653 3299 1663 2800 844 7179
    OBJECT-weighted (59,283 distinct keys): mean 2.5856 nodes, unindexed 0
      buckets:                 0 13328 31786  1950  6463  2282 1245  539  296  435 146  813
    REACHED control: multiFileModuleTypeNames = 5

**73.6% of probes present a key of at most TWO nodes**; 2.50% reach 11 or more.
At the measured 5.47 ns per subtree node (arm A's 15.09 ns over 2.7567 nodes),
161 ns would need a **29.4-node** mean — squarely inside the 25-40 the design
predicted the row required, and **10.7x** the mean that exists. The row is
refuted by its own arithmetic once the population is known; the amplifier below
only confirms it.

**Round 902's law does NOT bite here, and that is itself a result.** The
probe-weighted mean (2.757) exceeds the object-weighted one (2.586) by 6.6%, not
by the 193x the lexical-scope case showed. Big keys are not preferentially
probed, so for this family a cache-shaped (object) histogram would have given
the right answer. The law is about which weighting you must *check*, not about
which one always wins.

## 2. The amplifier (`--typeNodeKeyAmp N` / tier `tnkamp<N>`)

Three arms under one timestamp pair each, cyclically rotated, at `r = 8` and
`r = 24`, ABBA inside each process and mirrored across two (rounds 869/891).
Sixteen draws per `r` across the two batches; every sink an exact multiple of
`r`; no arm flat between the two `r`, so none was elided.

| arm | what it is | slope, ns per operation |
|---|---|---:|
| **A** | `nodeTypes[node]` — deep hash, bucket, deep `equals` on a hit | **15.09** |
| **B** | the same probe on a `LongKeyMap` keyed `(file hash, nodeId + 2)` | **2.11** |
| **C** | `isPerFileDependentRefNode(node)` — the row's SECOND owner | **12.88** |

`A - B = 12.98 ns` (median-based 13.28; generous single-draw bound 19.1).

## 3. The decision

    prize = (A - B) x 354,131 = 4.60 ms = 0.085% of 5,429 ms
    generous upper bound      = 6.77 ms = 0.125%
    arc floor                 = ~0.31% (~17 ms)

**REFUSED by 3.7x**, and by 2.5x even against the most generous reading. And
`A - B` is an **upper bound** by construction: arm B's key is computed OUTSIDE
every timestamp pair, so it prices the ideal successor whose key is free. A real
re-key would additionally owe the owning file's identity, which today costs a
parent-chain climb. A refusal taken against an upper bound is a refusal with
certainty.

Corollary, refused with it: nothing here rescues round 896's sentinel-set
candidate. Two of the four deep-hash sites are `nodeTypeResolutionInProgress`
add/remove (118,566 operations, **max live 3**), and at a 12.98 ns premium they
are 1.54 ms.

## 4. The correction to the JFR attribution, which stands either way

The row has TWO OWNERS and a leaf-frame profile cannot separate them.
`isPerFileDependentRefNode` is a recursive walk over the SAME subtree the hash
walks, and it runs on **every** call — cacheable or not, all 287,062 — as the
last conjunct of the `cacheable` gate.

    arm A x 354,131 = 5.34 ms   the whole deep-key map traffic
    arm C x 287,062 = 3.70 ms   isPerFileDependentRefNode  (0.068%)
                    ---------
                      9.04 ms   against a 57.1 ms JFR row  =>  over by 6.3x

That is the ninth consecutive JFR owner row measured over by a non-JFR
instrument, and it sits at the top of the recorded 2.1-21x band.

**Arm C is LIVE, not dead (round 902).** `isPerFileDependentRefNode` opens with
`if (multiFileModuleTypeNames.isEmpty() || depth > 4) return false`, so on a
program without multi-file module type names the arm would price a field read
and a return while reading exactly like a subtree walk. The census reports the
set at **5** entries, so the `when (node)` dispatch is reached. Two caveats
stated rather than hidden: the arm is amplified only at the CACHEABLE site
(176,282 of 287,062 calls), so applying its rate to the bypassed calls assumes a
similar subtree distribution — restricted to the amplified population alone it is
2.27 ms; and arm C always answers `false` there by construction (a `true` is
exactly what makes a call non-cacheable), so it prices the refusal path, which is
the path 100% of cacheable calls take and 61% of all calls take.

## 5. What the deep key actually buys, measured

The two arms' sinks differ by exactly `130 x r` at both `r`, in both batches:

    (A - B) / r = 130 probes of 176,282  =  0.074%

Those are probes the STRUCTURAL key serves from a **different, structurally-equal
node** — something a `(file, nodeId)` key can never do, because it is injective
on nodes. That is the entire semantic content of the deep key on this program.
So the re-key is not only worth ~nothing, it would also cost ~nothing
semantically **in cache hits** — but that is NOT a licence to do it:
`PerFileTypeNameCacheCollisionTest` pins the case where structural sharing is
*wrong* (two files' identically-shaped annotations resolving to different
symbols), which is what `isPerFileDependentRefNode` exists to block. A re-key
would make that bypass dead code and change which resolutions are cached — for
0.085%.

## 6. For the next agent

* Do not re-open this row from a JFR profile. It is measured.
* The instrument survives: `--typeNodeKeyCensus`, `--typeNodeKeyAmp N`, the
  `typenodekey` / `tnkamp<N>` tiers, and `scripts/round903-census.sh`.
* `TypeNodeKeyCensusTest` also carries the FIRST pin on round 896's
  `MapCensus.nodeEnter`/`nodeLeave` sentinel hooks, which had shipped their
  numbers unpinned.
* The one thing this round did NOT price: a `HashMap` `put` and a `HashSet`
  `add` cost more than a `get` beyond the hash (insertion, resize). The premium
  measured here is the hash-and-equals part, which is the part a re-key removes;
  the rest is common to both keys and cancels.
