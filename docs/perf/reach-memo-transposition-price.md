# (WARM.33) — the price of TRANSPOSING the 43 per-file INV.4 reach memos (round 906)

**Status: IN PROGRESS — census landed, layout simulation pending.**

Binary: `27a03c4c` + this round's instrument. Profile: `build/bench/tsc-project-*`
(78 files, 46 errors). Warm, `BenchMain <proj> 6 3 <tiers>`. Captures under
`build/bench/round906/`.

## 0. The candidate, and the trap it is designed around

Round 875 § 5.2 queued it, with its number attached so it could not be
re-estimated upward:

> 43 separate `ByteArray`s per file, each probed at a scattered `nodeId`, is a
> cache-hostile layout, and at 2.23 classifiers per node a transposition (one
> array of 43 statuses per node, so a node's whole row is one or two cache
> lines) would let those 2.23 consultations share a line instead of taking 2.23
> separate misses. … The ceiling is the ~200 ms of Status/ascent/fold/memo
> bookkeeping, the realistic share of that which is memo cache misses is at most
> half, and halving those gives **≤ ~0.8%**.

**THE TRAP: this is a CACHE-LOCALITY question, and round 759's amplifier cannot
measure one.** Repeating the same probe `r` times under one timestamp pair makes
the line L1-hot from the second repetition, so the amplifier prices an L1 hit —
which is exactly the cost the transposition exists to remove. The sibling
Kotlin/Rust project hit this precisely (a memo that removed 35.6% of repeat
reads moved its mechanism 16.18% → 15.44%, because the repeat read was already
in L1), and round 897 records the same law from the other side: a leaf-frame
profile cannot see a working-set collapse, because the cost of a cache miss is
charged to whatever frame dereferences the object.

So there is **no clock in this round's instrument**. Every memo array access is
hooked at its own line, and the exact access stream is fed to a set-associative
LRU model of BOTH layouts at this box's geometry. The answer is a **miss-count
delta per cache level**, which is a counter — and counters here are
deterministic, which is the instrument's own falsifier (rounds 904, 905).

## 1. The reach census, re-taken on today's binary (`--reachCensus`)

Two processes, **identical to the last digit**, and identical to round 875's
table: the family has not moved in 31 rounds, so round 875's numbers are LIVE
and can be quoted.

| | per warm rebuild |
| --- | ---: |
| consultations | **1,909,715** |
| memo hits | 169,038 (8%) |
| ascents | 1,740,677 |
| folds (= edge evaluations) | **3,324,977** |

`spine.nodes` is **856,962**, so the family is consulted **2.23 times per node**
— the number § 5.2's estimate rests on.

## 2. The one arithmetic correction the queue entry needs, before anything else

> "Also deletes 36.9 MB/rebuild of allocated+zeroed `ByteArray`."

**It deletes none of it.** 43 arrays of `n` bytes and one array of `43n` bytes
are the same `43n` bytes; the transposition changes each byte's ADDRESS, not the
count. What it deletes is 42 of the 43 array HEADERS per file — 78 files × 42 ×
16 bytes = **52 KB per rebuild**, which is nothing. The zeroing, the GC share
and the DRAM bandwidth to stream the arrays once are identical in both layouts,
so the whole prize has to come from LATENCY: accesses moving from a slower cache
level to a faster one.

That also bounds the mechanism from the other side. The total memo footprint is
touched once whatever the layout, so **compulsory misses are equal** and only
capacity/conflict misses can differ.
