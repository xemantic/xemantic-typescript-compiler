# (WARM.33) — the price of TRANSPOSING the 43 per-file INV.4 reach memos (round 906)

**Verdict: REFUSED, and the candidate is a REGRESSION.** The ceiling on *any*
memo-layout change — what the access stream costs today minus what it would cost
if every one of its 8,888,467 accesses were an L1 hit — is **2.65 ms (0.05%) at
this box's geometry and 15.99 ms (0.30%) at the most hostile one modelled**,
against an arc floor of ~17 ms (0.31%). The specific candidate measures **+3.90
to +24.20 ms WORSE** than today's layout at the five geometries, and its
strongest form (a row padded to a full cache line) is worse still, **+22.41 to
+46.21 ms**.

Binary: `3b733030`. Profile: `build/bench/tsc-project-*` (78 files, 46 errors on
every rebuild). Warm, `BenchMain <proj> 6 3 <tiers>`; today's six probe-free
process medians are 5,459.8 / 5,445.7 / 5,147.8 / 5,220.6 / 5,196.7 / 5,289.9 ms,
so the round keeps round 905's stated denominator of **5,290 ms** (1% = 52.9 ms)
— the top of today's range, which is the reading most generous to the candidate.
Captures under `build/bench/round906/`.

## 0. The candidate, and the trap the instrument is designed around

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
exactly the cost the transposition exists to remove. The sibling Kotlin/Rust
project hit this precisely (a memo that removed 35.6% of repeat reads moved its
mechanism 16.18% → 15.44%, because the repeat read was already in L1), and round
897 records the same law from the other side: a leaf-frame profile cannot see a
working-set collapse, because the cost is charged to whatever frame dereferences
the object.

So **there is no clock in this round's instrument**. Every one of the 139
`memo[...]` access lines is hooked at its own line, so the access stream is
EXACT rather than reconstructed, and the stream is fed to a set-associative LRU
model of three layouts at five geometries. The answer is a **miss-count delta
per cache level**, which is a counter — and counters here are deterministic,
which is the instrument's own falsifier (rounds 904, 905).

## 1. The one arithmetic correction the queue entry needs, before anything else

> "Also deletes 36.9 MB/rebuild of allocated+zeroed `ByteArray`."

**It deletes none of it.** 43 arrays of `n` bytes and one array of `43n` bytes
are the same `43n` bytes; the transposition changes each byte's ADDRESS, not the
count. Measured, the real footprint is **40,277,214 B = 38.4 MiB per rebuild**
(43 reach memos at 1 byte a node plus `Nu`, `ArgDepth` and `IaDepth`, which are
`ShortArray`s), and the transposed array is 38.6 MB of it. What the
transposition actually deletes is 44 of the 45 array HEADERS per file — 78 × 44
× 16 = **55 KB a rebuild**, which is nothing. And the padded variant makes it
WORSE: 54.8 MB.

That also bounds the mechanism from the other side and is why § 4's ceiling is
computed the way it is: **the footprint is streamed once whatever the layout, so
compulsory misses are equal**, and only capacity/conflict misses can differ.
(The zeroing is a sequential streaming write and therefore bandwidth-bound —
~40 MB at ~10 GB/s ≈ 4 ms, which is round 875's own "~4 ms of memset". Charging
it a 90 ns DRAM latency per line, as a naive reading of the model's `dram`
column would, invents ~57 ms of cost that no layout change can touch. The pricer
separates it for exactly this reason.)

## 2. The reach census, re-taken on today's binary (`--reachCensus`)

Two processes, identical to the last digit, and **identical to round 875's table
in every cell**: the family has not moved in 31 rounds, so round 875's numbers
are LIVE and quotable.

| | per warm rebuild |
| --- | ---: |
| consultations | **1,909,715** |
| memo hits | 169,038 (8%) |
| ascents | 1,740,677 |
| folds (= edge evaluations) | **3,324,977** |

`spine.nodes` is 856,962, so the family is consulted **2.23 times per node** —
the number § 5.2's estimate rests on.

## 3. The ACCESS census (`--reachMemoCensus`, this round)

Per warm rebuild; **the two processes are identical to the last digit**, which
is the instrument's own falsifier.

| | accesses |
| --- | ---: |
| probe — at the classifier's own node | **1,960,176** |
| ascent — at an ancestor | **3,166,496** |
| write — one per folded chain element | **3,761,795** |
| **TOTAL** | **8,888,467** |

**Two falsifiers pass EXACTLY**, which is what says the 139 hooks sit at the
accesses they claim to:

* the 43 reach classifiers' probes sum to **1,909,715** — `ReachCensus`'s
  consultation count, to the digit; the remaining 50,461 are `ArgDepth`
  (42,012) + `IaDepth` (8,449), the two memos `ReachCensus` does not know about,
  and 42,012 + 8,449 = 50,461 exactly;
* the ascent-gap histogram records **2,816,334** steps and the two interleaved
  classifiers record none by construction — `UResExpr` 329,346 + `UResType`
  20,816 = **350,162**, and 2,816,334 + 350,162 = 3,166,496 exactly.

### 3.1 Round 902's law: the mean 2.23 is not the quantity, the distribution is

Consultations per NODE, over the 856,962 spine nodes:

| consults | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11+ |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nodes | 118,770 | 257,177 | 182,479 | 101,049 | 93,220 | 51,124 | 17,209 | 21,116 | 6,614 | 2,844 | 1,971 | 3,389 |

**13.9% of nodes are never consulted at all**, and of the 738,192 that are, the
mean is **2.655** rather than 2.23. The population the transposition could
convert — a consultation that is the SECOND or later at its node, and therefore
the only kind that can find its row already resident — is

    1,960,176 - 738,192 = 1,221,984  =  62.3% of all consultations.

That is the candidate's whole prize population, and § 4 shows it is an order of
magnitude smaller than what the current layout already amortises.

### 3.2 The ascent is NOT the scattered access the queue entry assumed

`nodeId` is assigned in preorder, so a node's parent is a SMALL number of ids
below it. Measured, over the 2,816,334 recorded ascent steps:

| gap | 1 | 2-3 | 4-7 | 8-15 | 16-31 | 32-63 | 64-127 | … | 1024+ |
|---|---:|---:|---:|---:|---:|---:|---:|---|---:|
| steps | 1,189,500 | 399,082 | 356,484 | 301,040 | 174,852 | 107,805 | 66,731 | … | 136,084 |

**42.2% of ascent steps go to the immediately preceding `nodeId`, and 89.8% have
a gap under 64** — i.e. in today's one-byte-per-node layout they land in the
same 64-byte line as the child, or the one next to it. In a transposed layout
with a 45-byte row, a gap of TWO already spans 90 bytes and is always a
different line.

## 4. The layout simulation, and why the current layout wins

`ReachMemoCensus` models three layouts, each with a bump allocator per file (16
byte headers, so the arrays cannot alias onto identical cache sets by
construction) and the zeroing of every fresh array:

* **A** — today: 45 arrays, one per classifier, 1 byte a node (2 for the three
  `ShortArray`s).
* **B** — the candidate: one array, `row = nodeId * 45 + classifier`.
* **C** — the candidate in its BEST possible form: the row PADDED to 64 bytes,
  so it is always exactly one cache line and never straddles two. It costs 42%
  more memory and is modelled because a refusal taken against the strongest
  form is a refusal with certainty (round 903).

Five geometries. The first is this box (`lscpu`: L1d 32 KiB/core, L2 512
KiB/core, L3 16 MiB); the other four shrink it, because **the model cannot see
the checker's own memory traffic between two consultations, and what that
traffic does is evict L1**. That is the one direction that could have flipped
the verdict — layout A runs 45 concurrent sequential streams and needs 45 lines
resident where B runs one and needs two — so `flushed(4K/64K/512K)` was measured
rather than argued.

Access-stream cost in ms (zeroing separated out, § 1; Zen 2 latencies at 2.5
GHz: L1 1.6 ns, L2 5.2, L3 15.6, DRAM 90):

| geometry | **A** (today) | **B** | **C** | ceiling on ANY layout |
|---|---:|---:|---:|---:|
| box(32K/512K/16M) | **16.87** | 20.77 (**+3.90**) | 40.74 (+23.88) | **2.65 ms = 0.05%** |
| shrunk(32K/256K/4M) | 23.19 | 36.17 (+12.99) | 45.60 (+22.41) | 8.97 ms = 0.17% |
| mid(8K/128K/2M) | 24.60 | 40.25 (+15.65) | 58.27 (+33.67) | 10.38 ms = 0.20% |
| hostile(32K/128K/1M) | 27.02 | 47.98 (+20.96) | 65.00 (+37.98) | 12.80 ms = 0.24% |
| flushed(4K/64K/512K) | 30.22 | 54.41 (+24.20) | 76.42 (+46.21) | 15.99 ms = 0.30% |

**Every arrow points the same way, at every geometry, for both transposed
variants.** Shrinking the cache — the only direction in which the model's
optimism could be hiding the prize — makes the candidate WORSE, not better,
because it is the layout that touches more lines.

### 4.1 The mechanism, in one line of arithmetic

**The spine walks in preorder, so each classifier's array is SWEPT
SEQUENTIALLY.** A 64-byte line of `array_c` covers 64 consecutive `nodeId`s and
is amortised over every one of them that classifier `c` consults. The
consult-weighted mean density of a classifier over the node space is

    sum(consults_c^2) / (nodes x sum(consults_c))  =  0.2216

so a line in layout A serves **~14.2** of that classifier's consultations — plus
the 89.8% of its ascent steps and their writes that land within 64 ids (§ 3.2).
A line in layout B holds `64/45 = 1.42` nodes, and a node carries 2.655
consultations, so it serves **~3.8**. Layout A amortises a line over roughly
**3.7x more accesses**, and the cross-classifier sharing the candidate was
designed to capture (62.3% of consultations, § 3.1) does not begin to pay for
it.

That is why layout A already answers **97.0%** of its 8,888,467 accesses out of
L1 at this box's geometry, and why the whole remaining prize is 2.65 ms.

### 4.2 The generous readings, all still refusing

* Grant the transposed layout a PERFECT hardware prefetcher and perfect L1
  residency — i.e. give it the all-L1 floor of 14.22 ms — and add the one real
  saving it has, 28,585 fewer zeroing lines from the per-array line rounding
  (2.6 ms at a DRAM latency, far less at streaming bandwidth): the best case is
  **5.2 ms at this box and 18.6 ms at the deliberately hostile `flushed`
  geometry**, against a change that rewrites the memo access of all 43
  classifiers.
* Take the largest of today's six process medians as the denominator, as this
  document does; taking the smallest would make every percentage 6% larger and
  change nothing.
* Take the LEAST favourable geometry for the ceiling (15.99 ms) and the most
  favourable for the candidate (+3.90 ms). The ceiling is still below the floor
  and the candidate is still negative.

## 5. What this corrects, and what it leaves

* **Round 875 § 5.2's "≤ ~0.8%" is over by 2.7x–16x** and, more importantly, has
  the SIGN wrong: it read "43 arrays probed at a scattered nodeId" as
  cache-hostile, when the scatter is the *ascent's* and the ascent is 89.8%
  within one line, while the probe stream is a sequential sweep. This is the
  eleventh JFR/estimate ceiling in this arc to be measured and found over — and
  the first to be found NEGATIVE.
* **The queue entry's "deletes 36.9 MB/rebuild" is arithmetically empty** (§ 1).
* **The whole memo family's memory cost is 16.9–30.2 ms of a 5,290 ms rebuild =
  0.32–0.57%**, plus ~4 ms of bandwidth-bound zeroing. That is a bound on
  everything a next round could try here, not just on the transposition.

**One direction this round did NOT price, and it is the only one left with a
number in it.** 17 of the 43 classifiers are consulted **fewer than 1,000 times
per rebuild** (`Sr` once, `Del` and `Ex` twice, `Aa`/`Ab`/`Cp` nine or ten
times) and each still gets a full `ByteArray(nodeCount)` allocated and zeroed in
**every one of the 78 files**. Allocating a memo lazily, on a classifier's first
consultation in a file, would delete a large share of the 38.4 MB — but the
saving is BANDWIDTH, at ~10 GB/s, so the whole 38.4 MB is only ~4 ms and a
large share of it is ~2-3 ms. It is below the floor before it is started, and it
is recorded here so it is not re-opened as though it were the 57 ms a naive
reading of the `dram` column suggests.

## 6. What landed

Instrument only — no change to the machinery.

* `ReachMemoCensus` — the access census, the per-node consultation histogram,
  the ascent-gap histogram, and the three-layout × five-geometry LRU model.
  `--reachMemoCensus`, `BenchMain` tier `reachmemo` (which arms `reach` too, so
  the falsifier is read in the SAME rebuild rather than across draws).
* `scripts/round906_instrument.py` — injects the 139 hooks and takes each one's
  classifier from the nearest enclosing `val memo = spine<X>Memo` binding, so an
  access in a marker or backfill helper is attributed correctly; `--check` /
  `--revert`.
* `scripts/round906_price.py`, `scripts/round906-census.sh`.
* `ReachMemoCensusTest`, six pins.

## 7. For the next agent

* **Do not re-open the transposition.** It is measured: 8,888,467 accesses,
  97.0% already L1, a ceiling of 2.65–15.99 ms for ANY layout, and the specific
  candidate is +3.90 to +24.20 ms in the wrong direction.
* **Reusable constants**: the INV.4 memos are **8,888,467 accesses and 38.4 MiB
  a rebuild**; **13.9%** of spine nodes are consulted by no classifier and the
  consulted ones average **2.655**; **42.2%** of ascent steps go to
  `nodeId - 1` and **89.8%** stay within 64 ids.
* **The general law this round adds**: *a locality change cannot be amplified,
  and it does not have to be — the access stream is a counter.* Hook the
  accesses, model the layouts, and read a miss-count delta. It is deterministic
  (two processes identical to the digit), it costs one build, and unlike an
  A/B it can resolve an effect far below the ±1.0% warm band — which is where
  every remaining candidate in this arc lives.
* **And the corollary that decided this one**: before believing that a
  per-object row is more local than a per-attribute column, ask what ORDER the
  attribute array is swept in. A preorder walk sweeps it sequentially, and a
  sequential sweep of a 1-byte-per-node array is the most cache-efficient thing
  in the family.
