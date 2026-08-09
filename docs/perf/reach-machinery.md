# (WARM.22) The INV.4 reach machinery, as ONE population — round 875

## 0. HEADLINE — the verdict, before the tables

Round 874 § 29 closed the leaf-profile arc by naming its successor: the reach
machinery is the largest single mechanism in a warm rebuild, ~338 ms after that
round's TAV fix, spread over 43 classifiers of which the largest is 0.86% — so
it is **one design question, not a candidate list**, and the question is whether
the pull-based memoized ancestor climb can be shared, hoisted, or replaced by
state the spine already maintains as it walks.

**The question is now answered, and the answer is that every mechanism inside
the family prices below the 1% bar.**

| candidate answer | what it would cost | verdict |
| --- | --- | --- |
| **(a) push-based** status maintained by the walk | forces **36.9 M** edge evaluations per rebuild where the pull scheme performs **3.32 M** — **11.1×** more work | **priced negative by arithmetic**, § 5.1 |
| **(b) pack / transpose** the 43 per-file `nodeId`-keyed memos into one | attacks memo PROBES; the whole probe+ascent+fold bookkeeping is ~200 ms and a transposition can halve at most its cache misses | **≤ ~0.8%**, and it rewrites the memo access of all 43 classifiers, § 5.2 |
| **(c) make the FOLD cheaper** — the edge predicates are `when (parent) { is X -> … }` over 41–119 node classes, i.e. linear `instanceof` chains, and `NodeBase.kindId` exists (M0.2) so such a dispatch can be a `tableswitch` | the ENTIRE compute of every edge predicate in the family is **3.32 M × 13.3 ns = 44 ms = 0.67%**, and a 106-arm chain measures the SAME as a 49-arm one, so a tableswitch cannot take most of even that | **priced negative by measurement**, § 4 — and it is the one a static reading of the source would have committed a whole arc to |
| **(d) per-pass gates** in round 874's shape | the largest remaining classifier is Iany at 0.86%, then Ce 0.65% and URes 0.54% | below the bar per slice, § 3 |

So the round lands the INSTRUMENT and the verdict, and no change to the
machinery. **The family is diffuse not only across 43 classifiers but across
MECHANISMS, and that is the finding**: 6.9% of a warm rebuild that decomposes
into no single lever worth taking.

**The most useful thing here for a next agent is § 4.** The static reading —
"20 edge predicates dispatch through up to 119 sequential `instanceof` tests,
convert them to `kindId` tableswitches" — is compelling, mechanical, has a
documented precedent in this repo (round 803 split `forEachChild` on exactly
that key for −3.93%), and is **wrong**. It was killed by one amplification
measurement costing two runs, and it would have cost an arc.

## 1. What the machinery is

The INV.4 migration moved ~46 recursive checker walkers onto the check spine.
Each migrated pass kept its own answer to the question its deleted walker
answered by construction — *would that walker have visited this node?* — as a
**memoized reach classifier**, and all 43 of them share one shape:

1. a `nodeId`-keyed, per-FILE memo probe (`ByteArray`/`ShortArray`);
2. on a miss, an **ascent** to the first memoized or terminal ancestor;
3. a **fold-down** that evaluates one **EDGE predicate** per chain element —
   the deleted walker's descent arms, transcribed verbatim — and memoizes each
   result on the way.

Five of them (`Iany`, `Arith`, `Uncalled`, `DupId`, `Tav`) evaluate the edge
DURING the ascent instead and fill the chain afterwards; two (`UResExpr`,
`UResType`) interleave probe and edge in one loop. The difference matters for
counting and is what the pins caught (§ 2.1).

Round 732 already ruled out the lever that looks like it should apply: a
per-KIND dispatch table removes handler CONSULTATIONS, and "handlers keyed on
PARENT edges, FRAME identity and nodeId REGISTRIES cannot be closed by the
node's own kind AND are the expensive ones". This family is exactly that set.

## 2. The census — before any timing (round 801)

`ReachCensus`, armed by `--reachCensus` or `BenchMain`'s `reach` tier. Counters
only: every classifier is below the price of its own timestamp pair (round 850),
and what a design change acts on is a count of structure.

The counters are injected at the two anchors every classifier carries verbatim,
by `scripts/round875_instrument.py`, **which also generates the id table** so
the two cannot drift. They are DETERMINISTIC — identical on every rebuild of
every process this round took — which is the instrument's own falsifier.

### 2.1 The instrument was wrong first, and a pin said so

The first injection put the fold counter at the tail (`folds += chain.size`),
which is right for the 36 classifiers whose ascent pushes every node it visits
and wrong for the five whose ascent evaluates the edge and pushes only when it
CONTINUES: there `chain.size` is one short per ascent. `ReachCensusTest`'s
`folds >= misses` failed on its first run and the counter moved to the edge call
itself. **The wrong instrument read 2,956,401 edge evaluations; the right one
reads 3,324,977** — a 12% under-count, in the direction that would have made
this round's negative verdict look stronger than it is.

### 2.2 The table

Compiler profile (78 files, 46 errors, `--noEmit`), warm, per REBUILD.
`spine.nodes` is 856,962, so a classifier's `consults` is directly comparable to
it: `UResExpr` is consulted at 50.3% of all nodes.

| classifier | consults | memo hit | ascents | folds (= EDGE EVALS) | folds/consult |
| --- | ---: | ---: | ---: | ---: | ---: |
| UResExpr | 431,104 | 0.0% | 431,104 | 581,368 | 1.34 |
| Iany | 232,973 | 20.4% | 185,244 | 282,532 | 1.21 |
| Ce | 187,740 | 0.0% | 187,740 | 444,812 | 2.36 |
| Tpo | 144,408 | 0.0% | 144,408 | 289,187 | 2.00 |
| Arith | 123,923 | 25.5% | 92,243 | 210,972 | 1.70 |
| Pmr | 110,541 | 6.0% | 103,874 | 261,948 | 2.36 |
| Nu | 108,726 | 0.0% | 108,726 | 250,865 | 2.30 |
| Pd | 86,069 | 0.0% | 86,069 | 101,935 | 1.18 |
| Ubd | 69,127 | 58.3% | 28,800 | 53,341 | 0.77 |
| Fp | 50,847 | 0.0% | 50,847 | 170,640 | 3.35 |
| Os | 49,931 | 0.0% | 49,931 | 110,557 | 2.21 |
| UResType | 40,466 | 58.4% | 16,811 | 22,098 | 0.54 |
| Ca | 39,824 | 0.0% | 39,824 | 75,283 | 1.89 |
| Uncalled | 38,291 | 0.0% | 38,291 | 77,375 | 2.02 |
| At | 37,590 | 0.0% | 37,590 | 76,658 | 2.03 |
| Da | 34,141 | 27.8% | 24,624 | 50,291 | 1.47 |
| DupId | 29,046 | 0.0% | 29,046 | 42,876 | 1.47 |
| Ev | 24,624 | 0.0% | 24,624 | 50,291 | 2.04 |
| B94 | 18,160 | 0.0% | 18,160 | 41,937 | 2.30 |
| Tav | 15,887 | 0.0% | 15,887 | 32,217 | 2.02 |
| Ir | 13,021 | 10.8% | 11,607 | 22,439 | 1.72 |
| Tc | 10,825 | 74.2% | 2,787 | 18,398 | 1.69 |
| Co | 6,230 | 0.0% | 6,230 | 30,825 | 4.94 |
| Sm | 1,844 | 0.0% | 1,844 | 4,363 | 2.36 |
| …18 more, 16 of them under 1,000 consults | 3,371 | | 3,364 | 21,769 | |
| **TOTAL** | **1,909,715** | **8%** | **1,740,677** | **3,324,977** | **1.74** |

`Tav` at 15,887 is the round-874 name-candidate gate working, down from 381,670
— a free positive control that this census is reading a live, post-fix binary.

### 2.3 Two readings the table forces

**The memo answers 8% of consultations, and TWENTY-THREE classifiers are at
exactly 0%.** That is not a defect: a classifier is consulted **at most once per
node** (its handler dispatches once), so a node's own status is never asked
twice and the memo can never answer the query it was written for. **Its only job
is to TERMINATE AN ASCENT** — and it does that well, which is why
`folds/consult` is 1.74 and not the tree depth. Anyone reading "8% hit rate" as
a cache to fix should stop here.

**2.23 classifiers are consulted per node** (1.91 M / 857 k). That number is
what makes (b) worth stating at all and what bounds it.

## 3. Where the ms are — the family by CLASSIFIER

`scripts/round875_reach_family.py` re-reads round 874's committed JFR dumps
(both processes) and groups the reach owners by classifier. Same caveats as
every table in `warm-leaf-profile.md`: a share is a share of WALL TIME, leaf
attribution moves with C2 inlining between processes, and **no number here is a
price**.

| classifier | r1 | r2 | ms/rebuild | owners |
| --- | ---: | ---: | ---: | ---: |
| Tav | 2.11% | 1.92% | 133.1 | 13 |
| Iany | 0.83% | 0.89% | 56.9 | 8 |
| Ce | 0.68% | 0.62% | 43.2 | 2 |
| URes | 0.62% | 0.45% | 35.5 | 5 |
| Nu | 0.29% | 0.48% | 25.4 | 2 |
| Pmr | 0.37% | 0.28% | 21.4 | 2 |
| Arith | 0.33% | 0.28% | 20.2 | 2 |
| Fp | 0.26% | 0.29% | 18.2 | 2 |
| Os | 0.22% | 0.28% | 16.5 | 2 |
| Uncalled | 0.31% | 0.20% | 16.5 | 3 |
| …21 more, none above 14 ms | | | 106.9 | 32 |
| **TOTAL** | **7.52%** | **7.46%** | **493.9** | **73** |

Round 874's own aggregation read 458.8 ms; this one is wider by the `tav*` level
helpers (`tavHasValue`, `tavLevelFor`, …), which belong to the same mechanism.
**Round 874 removed ~133 ms of the `Tav` row, so the LIVE family is ~361 ms =
5.5% of a 6.6 s warm rebuild**, and its largest member is `Iany` at 0.86%.

Split by sub-mechanism, post-874: **~200 ms of Status/ascent/fold/memo
bookkeeping, ~106 ms charged to the edge predicates, ~55 ms of per-pass scope
LEVEL walks**.

## 4. The measurement that killed candidate (c)

### 4.1 Why (c) looked certain

Every edge predicate is `when (parent) { is Block -> …; is IfStatement -> … }`,
i.e. a LINEAR `instanceof` chain, and they are big: `spineAiEdge` 119 arms,
`spineNaEdge` 114, `spineSyEdge` 111, `spineB94Edge` 109, `spineFpEdge` 106,
`spineAcEdge` 100, `spineItEdge` 96, `spineTdEdge` 90, `spineUResExprEdge` 85 —
down to 41. `NodeBase.kindId` (M0.2) exists precisely so a dispatch like this can
be a `tableswitch`, `forEachChild` was converted on that key, and round 803
measured that split at **−3.93%, 5/5 pairs**. Three million evaluations a
rebuild through chains averaging tens of type tests is a textbook prize.

### 4.2 What it actually costs

`--reachAmp N` puts `r` EXTRA evaluations of the SAME edge on the SAME
`(parent, child)` under ONE timestamp pair, so `nanos(r) = boundary + r·c` and
two values of `r` cancel the boundary algebraically (round 759). **Two sites,
chosen by ARM COUNT and not by cost** — `spineCeEdge` at 49 arms and
`spineFpEdge` at 106 — so the pair also gives the slope IN ARMS, which is the
whole question. Falsifiers are arithmetic and printed with the numbers:
`ampCalls == r·ampBrackets`, and `ampSink % r == 0` (each bracket contributes 0
or `r`, so a hoisted call breaks the identity). Both read OK in all eight draws.

Two processes, arms ABBA-rotated, `r ∈ {8, 24}`, `ns/bracket = boundary + r·c`:

| process | site | arms | r=8 | r=24 | **c (ns/edge)** | implied boundary |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | Ce | 49 | 215.1 | 398.4 | **11.46** | 123.4 ns |
| 1 | Ce | 49 | 192.2 | 573.4 | *23.82* | *1.7 ns* — **discarded** |
| 1 | Fp | 106 | 217.3 | 387.3 | **10.63** | 132.3 ns |
| 1 | Fp | 106 | 191.3 | 407.9 | **13.53** | 83.1 ns |
| 2 | Ce | 49 | 191.8 | 404.9 | **13.32** | 85.2 ns |
| 2 | Ce | 49 | 190.7 | 426.2 | **14.72** | 73.0 ns |
| 2 | Fp | 106 | 185.1 | 408.1 | **13.94** | 73.6 ns |
| 2 | Fp | 106 | 182.5 | 393.5 | **13.19** | 77.0 ns |

**The implied boundary is the fit's own falsifier and it does its job**: seven
pairs land at 73–132 ns, inside round 850's independently measured 97–202 ns for
a warm timestamp pair, and the eighth implies 1.7 ns — physically impossible, so
that draw is discarded by a criterion that has nothing to do with the answer it
would have given.

Two conclusions:

- **c ≈ 13.3 ns per edge evaluation.**
- **106 arms costs the same as 49 arms.** Ce reads 11.5 / 13.3 / 14.7 and Fp
  reads 10.6 / 13.5 / 13.9 / 13.2. If chain LENGTH were the cost, Fp would be
  ~2× Ce; the slope in arms is indistinguishable from zero. C2's type profile
  and the branch predictor handle the chain, and the walkers list their common
  parents (statements, blocks) first, so the hot cases exit early.

### 4.3 The arithmetic

**3,324,977 edge evaluations × 13.3 ns = 44.2 ms = 0.67% of a 6,597 ms warm
rebuild** — that is the ENTIRE compute of every edge predicate in the family,
before any conversion, and a `tableswitch` can only take the part that scales
with the chain, which § 4.2 measures as none of it.

The leaf profile charges 106 ms to those same predicates. The gap is what an
amplifier cannot see and a conversion cannot remove either: the CALL itself
(these methods are far over `FreqInlineSize`, so each is a real frame) and the
first, cold touch of the parent node. Even taking the profile's number whole,
the population is 1.6% and the removable part of it is the 0.67%.

**Caveat, stated because it points the same way:** the amplifier measures the
WARM MARGINAL cost — everything the predicate touches is in L1 by the second
iteration — so 13.3 ns is a LOWER bound on a production call and the prize
computed from it is a lower bound too. It does not move the verdict, because the
verdict rests on the arm-count independence, which is a RATIO between two sites
measured the same way.

## 5. The other two answers, priced

### 5.1 (a) push-based — negative by arithmetic, 11.1×

The spine walks in preorder and knows the path, so it could maintain each
classifier's status on a per-depth stack and hand it to the handler for free: no
memo, no probe, no ascent, no chain. It is the obvious answer and it is **11.1×
more work**.

A push scheme must compute a status for EVERY classifier at EVERY node it
descends through, because it cannot know which handler will ask: 43 × 856,962 =
**36.9 M** edge evaluations. The pull scheme performs **3.32 M**, because a
classifier consulted at a rare kind only ever folds the ancestors of the nodes
of that kind — 16 of the 43 are consulted under 1,000 times a rebuild and one is
consulted ONCE. At 13.3 ns that is +447 ms on a family that costs 361.

The census also shows why the pull scheme is already most of the way to a push
one: `folds/consult` is **1.74**, i.e. the ascent typically finds the parent
already memoized, which is exactly the state a push scheme would have kept — the
memo is doing the push scheme's job on demand.

### 5.2 (b) pack the memos — ≤ ~0.8%, and it touches all 43

43 separate `ByteArray`s per file, each probed at a scattered `nodeId`, is a
cache-hostile layout, and at 2.23 classifiers per node a transposition (one
array of 43 statuses per node, so a node's whole row is one or two cache lines)
would let those 2.23 consultations share a line instead of taking 2.23 separate
misses. It also removes 43 × 856,962 = **36.9 MB** of `ByteArray` allocated and
zeroed per rebuild — real but small (~4 ms of `memset` plus its GC share).

The ceiling is the ~200 ms of Status/ascent/fold/memo bookkeeping (§ 3), the
realistic share of that which is memo cache misses is at most half, and halving
those gives **≤ ~0.8%**. Against that: the memo access of ALL 43 classifiers is
rewritten, and every one of them decides whether a diagnostic is CONSIDERED.
Queued rather than taken, with this number attached so it is not re-estimated
upward.

## 6. What landed

Instrument only — **no change to the machinery**, and the round's own decision
rule is why (below ~1% ⇒ priced negative ⇒ write it up and stop).

- `ReachCensus` — per-classifier consults/hits/ascents/folds, `--reachCensus`,
  `BenchMain` tier `reach`; and the edge amplifier, `--reachAmp N`, tiers
  `reachamp8/16/24/32`.
- `scripts/round875_instrument.py` — injects the counters and GENERATES the id
  table from the same scan (`--ids`, `--check`, `--revert`).
- `scripts/round875_reach_family.py` — the family-by-classifier reading of round
  874's dumps.
- `scripts/round875-reach.sh` — the harness, round 851's order.
- `scripts/round875-ablate.sh` — the seven-arm single-mistake ablation.

## 7. Gates

- Suite **14,229 / 0 failures / 3 skipped** over all four modules
  (`xml.etree`) = round 874's 14,220 + 9 new pins, exactly.
- `cost_gate.py` **+0.00% on all 20 counters**, 46 errors / 78 files.
- `huge_methods.py --fail-over 0` — 677 classes, **0 over the limit**. Worth
  checking despite the change being counters: 43 hot functions each grew two
  lines and `Checker.<init>` is 5,624 of its 8,000 (round 814).
- **No 8-profile grid.** Every injected line is guarded by `ReachCensus.on`,
  which is false in production and which the negative-control pin and its own
  ablation arm (A7) both police; the two equivalence pins compare full
  diagnostic-code lists with the census armed and disarmed and are non-vacuous
  in both directions (§ 8). The corpus's 14,117 core baselines are the wider
  instrument here and they are green.

## 8. Pins and ablation

`ReachCensusTest`, 9 pins, all over counts and diagnostic CODES (never an AST
node, whose power-assert rendering is its whole subtree). Seven single-mistake
ablations, one arm per invocation, each dry-run for a real diff and reverted
before the next, on a committed tree:

| arm | the mistake | pins reddened |
| --- | --- | --- |
| A1 | a classifier's `calls` increment dropped | 2 — *folded but not consulted*, *ascent/consult bounds* |
| A2 | the fold counter back on the wrong path for the ascend-with-edge shape | 1 — *ascent/consult bounds* |
| A3 | the id table renumbered without the name table | 1 — *id/name alignment* |
| A4 | the amplifier's loop runs once whatever `r` is | 1 — *the amplifier multiplies exactly* |
| A5 | the census makes the const-assignment classifier answer NONE | 2 — both *equivalence* pins |
| A6 | the same, in the type-as-value classifier | 2 — both *equivalence* pins |
| A7 | a counter loses its `ReachCensus.on` guard | 1 — *negative control* |

**A5 and A6 share a red set by design** — A6 exists to show the equivalence pins
are not a property of one classifier. Every other arm's red set is distinct.

**A1 and A2 initially reddened the SAME single pin**, and a ninth pin (`calls > 0
|| folds == 0`) was added to separate them rather than counting the coincidence
as coverage (round 869's rule).

**A5 reddened NOTHING on its first run, and that was the round's sharpest
lesson.** It perturbed the const-ENUM classifier, whose pass emits nothing on the
fixture, so forcing it to answer UNREACHED changed no byte of the output — round
813's "a pin that stays green under its own ablation is as often BLIND as the
guard is redundant", arriving on schedule. The fixture now carries one deliberate
TS2588 and one TS2693, the equivalence pins assert both codes are PRESENT (two
empty lists agree), and the arm targets the classifiers that own them.

**One pin is reported as un-ablated rather than counted**: *the census counts a
non-empty population of consultations and folds*. No single-mistake source edit
can empty it — dropping one classifier's counters leaves the other 42 — so it
guards the whole instrument going dark, which is a build-level failure, not a
seam.

## 9. What a next round should NOT do

- **Do not convert the edge predicates to `kindId` tableswitches.** § 4 is the
  measurement, and its decisive part is the RATIO (49 arms vs 106 arms), which
  no re-measurement of one site can overturn.
- **Do not build a push-based status.** § 5.1, 11.1× by arithmetic.
- **Do not re-take `warm-leaf-profile.md`'s table** for this family. It has said
  everything it can; the census is the instrument now, and it is committed and
  deterministic.
- **Do queue (b)** if the arc ever wants ~0.8% badly enough to rewrite 43
  classifiers' memo access — with § 5.2's number, not a fresh estimate.
- The remaining named place with more than 1% in it is
  `warm-spine-attribution.md`'s per-handler table (`cpaSpineLeave`,
  `ctaSpineEnter`, `ctaFnBodyFrame`, `recordFlow`), which round 874 § 29 also
  pointed at and which this round did not open.
