# (WARM.25) The copy families rounds 891/892 left behind — priced, round 898

Round 894 § 9 candidate **(8)** is the one item on that list that was never a
proposal: *"not a change; an instrument run"*. Its census measures
`Checker$EpochMap.<init>` at **38.1 ms/rebuild**; round 891 **derived 14-24 ms**
for the same family and refused the conversion on precondition grounds. Both
numbers are on record, they disagree by ~2x, and § 8(3) of the census says in
as many words that the discrepancy is "a reason to re-run the existing
instrument rather than to trust either".

This document is that run. It also prices candidate **(6)**,
`spineArgListOverlay`, which had never been censused at all — only
JFR-attributed.

**Both are refused. Neither refusal is about the precondition; both are about
the price**, and the price in both cases is ~3x smaller than the census says.

---

## 1. The answer to candidate (8), first

| | ms/rebuild | per entry |
| --- | ---: | ---: |
| round 894, JFR EXTENDED, `Checker$EpochMap.<init>` | **38.1** | 110 ns |
| round 891, derived, at its own entry count (471,726) | 14-24 | 30-51 ns |
| **round 891's derivation at TODAY's entry count (347,017)** | **10.4-17.7** | 30-51 ns |
| **round 898, measured, amplification** | **11.2 - 14.8** | 32-43 ns |

**Round 891's derivation was right and round 894's census figure is wrong by
2.6-3.4x.** The apparent 2x contradiction between them was never even a real
disagreement of 2x: round 892 removed 124,709 entries from that family (the
narrowing frames, moved onto `MapScopeStack`), so the derivation *as re-stated
for the population round 894 was looking at* reads 10.4-17.7 ms — and the
measurement lands inside it.

### 1a. Why the census figure cannot be right, without any measurement at all

38.1 ms over the family's own population is **1,394 ns to copy a 12.6-entry
map**, i.e. **110 ns per entry**. A `java.util.HashMap` insert whose `String`
key already carries a cached hash — which every one of these does, since the key
is an identifier interned by nothing and hashed once per process (round 897(A))
— is a hash read, an index mask, a `Node` allocation and a link: tens of
nanoseconds, not a hundred and ten.

**Dividing an owner row by its own population is the cheapest falsifier a
candidate has, and it takes no build.** Round 896 stated this after doing it to
candidate (4) (28.2 ms over 24,232 adds = 1,164 ns per add on a set whose max
live size is 3). It applies to this row equally and was not applied.

### 1b. The mechanism, and why it is not a defect in round 894's script

A JFR sample lands where the JVM can walk the stack, and a tight allocating loop
attracts them out of proportion to its wall time — CLAUDE.md's round-623 entry
is the repo's own measurement of exactly this (a 5.3% leaf whose elimination
measured −0.3%). `HashMap.putMapEntries` is that loop. The EXTENDED measure
round 894 invented makes it *worse*, not better, and deliberately so: it charges
everything above the outermost map frame to the owner below, which is right for
attributing *whose* map work it is and wrong for saying *how many milliseconds*
it is.

So the census's ranking is sound and its magnitudes are not. That is a
statement about what a leaf profile can do, not an error in the aggregation —
and § 0 of the census says so at the top before its own § 9 spends nine
candidates forgetting it.

### 1c. The competing explanation, refuted by its own arm

The obvious rescue for the census figure is that a whole-map copy costs
`K + c·n` and round 891's per-entry derivation dropped `K`. It is a good
hypothesis: `EpochMap`'s mean copy is 12.6 entries against `varTypes`' 37.6 and
the cta local family's 114.3, so a per-call term that is invisible in the
families the rate was derived from would be a third of this one.

It is false, and the `es` arm is why. **`EpochSet(paramBindings)` makes 35,015
copies of mean 1.1 elements** — the most call-dominated container of all six
families, by a wide margin — and its measured slope is **0.5-2.8 ms/rep, i.e.
unresolvable**. If a whole-map copy carried a fixed per-call cost worth
anything, that family would be the one to show it. It does not, so the per-entry
model holds and round 891's *method* is vindicated along with its number.

---

## 2. The census — deterministic, identical in all nine processes

`--frontEnd`, compiler profile, warm. `touchedCalls`/`touchedEntries` are new
this round: a copy is charged ONCE, on its first write, against the size it was
born with.

| family | copies | entries | mean | max | writes | never written (copies / entries) |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `EpochMap(localTypes)` | 27,337 | 347,017 | 12.6 | 253 | 42,378 | 6,598 / **188,774 (54.4%)** |
| `EpochSet(paramBindings)` | 35,015 | 39,522 | 1.1 | 20 | 7,969 | 32,008 / **37,643 (95.2%)** |
| `spineOs` / `spinePd` anns | 55,829 | **0** | — | — | 34,454 | *(undo log, round 869)* |
| `CtaFrame.varTypes` | 30,433 | **0** | — | — | 16,182 | *(undo log, round 891)* |
| `CtaFrame` local family | 11,016 | **0** | — | — | 28,695 | *(undo log, round 892)* |
| `spineArgListOverlay` nested-fn overlay | **365** | **231,130** | 633.2 | 2,123 | 8,123 | not instrumented |
| `spineArgListOverlay` shadow-minus | **28** | **21,086** | 753.0 | 2,053 | **29** | not instrumented |
| `SpineArgCtx` edge / ns copies | 64 | 32,689 | 510.7 | 2,123 | 52 | not instrumented |

Two things to read off it before any price.

**The two surviving copy families have opposite shapes.** `EpochMap` is 27,337
copies of a 12.6-entry map; `spineArgListOverlay` is **393 copies of a
641-entry map**. Nothing about one transfers to the other — which is round 789's
law ("a cost prior from one family of sites does not transfer to another") in
its container form.

**The single sharpest line in the table is the shadow-minus: 21,086 entries
copied to remove 29 names.** 727 entries per name removed. It is also 28 calls,
so it is 0.05% of a rebuild and nothing more; it is recorded because a reader
scanning for waste will find it and should know it is already priced.

**`writes/entries` is NOT the interesting ratio for a copy-on-write question,
and that is why `touched*` exists.** `copyMuts` is a whole-FAMILY write count:
12.2% for `EpochMap`. It answers "would an undo log be cheaper than a copy" and
nothing else, and it cannot tell one copy written a hundred times from a hundred
copies written once. The two shapes have different levers behind them, and only
the second makes a copy-on-write scheme — which needs no LIFO discipline —
worth anything.

---

## 3. The instrument

`copyampem<r>` / `copyampes<r>` / `copyampal<r>`: `r` EXTRA copies at every
censused site of exactly ONE family, **no timestamp pair anywhere**, read off
the whole-rebuild wall, so `wall(r) = base + r·C` and two values of `r` cancel
`base` algebraically (round 759). Falsification is arithmetic and held on every
one of the 60 instrumented rebuilds taken this round: `ampSink == r × entries`.

**The per-family arms are not a convenience.** Rounds 869/891/892 converted four
of the six families to undo logs, so a bare `copyamp<r>` now arms two live
families and four dead ones and its slope is their SUM. And `copyAmpKinds`
answers `-1` — *arm every family* — for any tier name it does not recognise,
which is also the legitimate default: a mis-spelled prefix therefore measures
the sum and reports it as one family, silently. That is round 856's shape and
`CopyCensusTest` pins the mask because nothing else can see it.

`ampCopyOrdered` is new for the same class of reason: the `SpineArgCtx` families
copy with `toMutableMap()` and `Map.minus`, both of which yield a
`LinkedHashMap`, and amplifying an ordered copy with `ampCopyMap`'s unordered
one under-reads the family being priced.

### 3a. Batch 1 was noise-limited, and its failure is the reusable part

Three arms (r = 0/16/32), 4 draws each, two mirrored rotations:

| family | rotation A | rotation B | pooled | sub-intervals |
| --- | ---: | ---: | ---: | --- |
| `em` | 21.41 | 0.90 | 11.15 | 19.06 / 3.24 |
| `al` | 31.61 | 8.33 | 19.97 | 14.88 / 25.07 |
| `es` | 5.51 | −4.47 | 0.52 | −4.11 / +5.15 |

**Both rotations were balanced palindromes, so a LINEAR drift cancels in each by
construction — and they still disagreed 2x, 4x and by sign.** Round 891's law
("run the mirror rotation as a second batch") is therefore necessary and not
sufficient: what is left after a palindrome is the *non-linear* remainder of a
warm-up that is still running at rebuild 14, and the raw draws show it plainly —
rotation B of `em` reads 5,869 / 5,848 / 5,483 / 5,544 / 5,571 / 5,100,
monotone downward across the whole ladder. An `r = 0` arm ranges **5,100-5,869
ms on one binary**, so a 350 ms effect sits at SNR ≈ 1.

**The fix is not more rotations; it is fewer arms and a bigger `r`.** A slope is
a difference of two arm means, so a third arm buys a mid-point nobody
differences against.

### 3b. Batch 2 — two arms, r = 64, eight draws per process

| family | process A | process B | **pooled** | leading draw dropped |
| --- | ---: | ---: | ---: | ---: |
| `em` (`EpochMap`) | 13.99 | 15.52 | **14.76** | 12.87 |
| `al` (`spineArgListOverlay`) | 11.46 | 13.93 | **12.69** | 9.63 |

Two independent processes agreeing to 10% and 18%, against batch 1's 2x and 4x.
And the separation is total: **every one of the 8 `r = 64` draws is above every
one of the 8 `r = 0` draws, in both families** (`em`: 5,803-6,649 vs
5,088-5,613; `al`: 5,663-6,744 vs 5,144-5,519).

**Which end of each range to believe.** At `r = 64` the `em` arm allocates ~22 M
extra map entries per rebuild, so if anything it over-reads through GC pressure
— and indeed batch 1's smaller `r` read lower (11.15). The honest statement is
**`EpochMap` 11-15 ms, `spineArgListOverlay` 10-20 ms**, and the bias is in the
direction that makes the census figures *less* defensible, not more.

---

## 4. Candidate (6), `spineArgListOverlay` — REFUSED, and the cost that decided it

**Prize: 12.7 ms = 0.23% of a warm rebuild** (range 9.6-20.0 over four
processes and two batches), against round 894's **41.2 ms** ceiling — over by
2.1-3.2x, the same factor as everything else on that list.

The proposed replacement is a **chained scope map**: a small per-list overlay
plus a parent pointer, so an O(all visible function names) copy becomes an
O(declared-here) allocation. The census says that trade is real — 252,216
entries copied to carry 8,152 writes, 3.2%.

**What it costs is the number this round added the counter for.** A chain
replaces an O(1) probe with an O(depth) walk, and a **MISS must walk the whole
chain** where a hit can stop early. So the prize and the price are two different
populations, and the census now has both:

> **`SpineArgCtx` lookups: 56,096 per rebuild — 26,393 hits, 29,703 (53.0%)
> misses.**

At a chain 2-3 deep that is ~56-112 k extra probes, i.e. **1-6 ms back out of
the 12.7**, leaving **~7-11 ms = 0.13-0.20%**.

Three further facts, each of which independently refuses it:

1. **It is below a floor this arc has already refused at.** Round 897 refused
   candidate (1) at **0.31%** measured, and rated it LOW risk and "a handful of
   lines". This is 0.13-0.20% and round 894 rates it MEDIUM.
2. **No gate in this repo could defend it.** Not one counter in
   `cost-counters.txt` moves — `spine.nodes`, `globals.*`, `typeOfExpr.*` and
   `narrow.*` are all blind to which container holds `funcParams`. The only
   available defence is a warm wall A/B at ~0.2%, which is a fifth of what this
   box settles (rounds 840(c)/858/886), so the change would land undefended.
3. **The blast radius is arity diagnostics.** `spineArgListOverlay`'s shadowing
   rules are bug-compatible with a deleted legacy walker (17.126 block-level
   shadowing, M1.11 var-shadows-function), and its output is TS2554/TS2555 —
   which means an 8-profile grid plus the corpus for a change worth 0.2%.

**The population is also simply too small to be worth an engine change**: 393
copies per rebuild. Whatever is spent designing, pinning and gating a persistent
overlay chain is spent on 393 events.

---

## 5. Candidate (8), `EpochMap`/`EpochSet` — REFUSED, with the price now measured

Round 891 refused the conversion because the LIFO precondition **cannot be
stated** over this family: three spine stacks (ccet, cpa, the cta narrowing
frame) plus ≥12 ad-hoc `currentLocalTypes = EpochMap(currentLocalTypes)`
install/restore sites whose restore is a POINTER SWAP, not a pop. Nothing about
that changed this round, and round 892's precedent (a family 891 refused, taken
after its instrument was built) does not transfer — 892's family *was* one
stack, and what was missing there was a measurement, not a discipline.

What is new is the price: **11-15 ms = 0.20-0.27%**, for the whole family,
including its `EpochSet` twin at **≤ 2.8 ms (0.05%)**. Even a perfect
conversion — one that removed every entry copy for free, which the round-892
machinery does not (its undo log costs O(writes), here 12.2% of entries) —
returns under 0.25%.

### 5a. The copy-on-write reading, recorded and not taken

`touchedCalls` was added because it answers a question `copyMuts` cannot, and
its answer is genuinely interesting:

> **6,598 of the 27,337 `EpochMap` copies (24.1%) are never written, and they
> hold 188,774 entries — 54.4% of the family's whole copy volume.** The
> never-written copies are the BIG ones: mean 28.6 entries against 7.6 for the
> written ones.

A copy-on-write `EpochMap` — share the parent's map, materialise on first write
— would recover that 54.4%, i.e. **~6-8 ms = 0.11-0.15%**, and, unlike an undo
log, **it needs no LIFO discipline at all**. That is the one structural argument
this family has never had.

It is still refused, on the audit: a CoW child is equivalent to a copy only
while nothing writes the PARENT between the install and the restore, and
establishing that is exactly the ≥12-site pointer-swap audit round 891 declined
— for 0.11-0.15%. It is recorded here so that a future round which needs that
audit for another reason knows what it is worth in passing.

`EpochSet` is refused outright: 95.2% of its volume is never written and its
whole cost is under the instrument's noise floor.

---

## 6. What this does to round 894's § 9

Eight of its nine candidates have now been priced by an instrument other than
JFR. **Every one is over.**

| candidate | § 9 ceiling | measured | over by | round |
| --- | ---: | ---: | ---: | --- |
| (1) scanner interning | 67.7 | 17.2 | **3.9x** | 897 |
| (2a) `perFileScope` | 34.7 | 6.4-33 | 1.1-5.4x | 896 |
| (2b) `moduleOnlyGlobalNames` | 42.9 | 2-9 | 4.8-21x | 897 |
| (3) `nodeToFlow` | 46.6 | 17.9 | **2.6x** | 896 |
| (4) `symbolTypeResolutionInProgress` | 28.2 | 2-5 | 5.6-14x | 896 |
| (5) `nodeTypeResolutionInProgress` | 14-20 | 3-5 | 2.8-6.7x | 896 |
| (6) `spineArgListOverlay` | 41.2 | 12.7 | **3.2x** | **898** |
| (8) `EpochMap` copies | 38.1 | 11-15 | **2.6-3.4x** | **898** |

Only (7) is unmeasured, and it is CLOSED for an unrelated reason.

**The ratios cluster at ~3x for the four candidates whose owner is dominated by
one map operation** ((1), (3), (6), (8)) and run much higher for the ones whose
owner row was mostly *something else* ((2b), (4), (5)). That is the signature of
a systematic attribution bias plus a per-candidate misidentification, not eight
independent over-estimates.

**So: § 9 is a LOCATION list, not a price list.** It says truthfully where the
map work is; it does not say what any of it is worth. Two cheap steps stand
between a row there and a decision, and neither needs a build:

1. **divide the owner row by its own population** — 110 ns to copy one map
   entry, 1,164 ns to add one `Integer` to a 3-element set, are both refutations
   on their face;
2. **name the ONE map operation the row is supposed to be**, and check the owner
   does nothing else — round 896 found candidate (4)'s owner row was real cost
   in a function that was not that set at all.

---

## 7. Reproducing this

```
bash scripts/round898-copies.sh setup
bash scripts/round898-copies.sh tier copyamp0 census 2     # the census
bash scripts/round898-ladders.sh                           # batch 1, 3 arms
bash scripts/round898-ladders2.sh                          # batch 2, 2 arms, r=64
python3 scripts/round898_ladders.py                        # read both
bash scripts/round898-ablate.sh --dry                      # every arm makes a diff
bash scripts/round898-ablate.sh                            # 8 arms, one mistake each
```

`round898_ladders.py` enforces the arithmetic falsifier per draw (a mismatch
voids the draw rather than widening its bar), requires the censused populations
to agree across every process, reports each process's leading draw separately,
and REFUSES to print an untouched share for a family that carries no first-write
hook — round 849's law applied to this instrument's own output.

## 8. The ablation

Eight single mistakes, one at a time, dry-run for a real diff first, on a
committed tree. **All eight discriminate; four have a uniquely-their-own pin
(A5, A6, A7, A8); A1/A2 and A3/A4 are discriminated as PAIRS and not from each
other**, which is stated rather than dressed up (round 807/892).

| arm | the mistake | red |
| --- | --- | ---: |
| A1 | a per-family arm falls through to arm-everything | 2 |
| A2 | the `em` prefix swallows `es` — two families, one mask | 2 |
| A3 | the first-write record is never CLEARED | 1 |
| A4 | the copy never records the size it was born with | 1 |
| A5 | the bulk write counter adds 1 instead of its argument | 1 |
| A6 | every arity lookup is counted a hit | 2 |
| A7 | the ordered amplifier ignores the family mask | 1 |
| A8 | the shadow-minus census hook is deleted | 1 |

**Two arms were BLIND on the first sweep and each needed a different repair —
that is what the ablation was for.**

- **A8 reddened nothing because the two copy sites inside `spineArgListOverlay`
  shared one census family**, so the surviving site kept the counter non-zero
  and no pin could see the other fail. The repair is the split the census wanted
  anyway (`CP_ARG_SHADOW`), which is also what produced § 2's sharpest row.
  *A census family that cannot be zero is a census family that cannot be wrong.*
- **A3 reddened nothing because the FIXTURE could not express the invariant.**
  Rounds 891/892 moved the fn-body local families onto `MapScopeStack`, so what
  survives in a single-file `diagnose()` compile copies near-EMPTY maps: the
  first fixture made 5 copies holding 4 entries and wrote none of them twice,
  which makes "a copy is counted at most once" vacuously true. A class with
  methods, `this` and two callback shapes reaches 21 copies and 34 writes, and
  the pin now ASSERTS that multiplicity rather than assuming it.

## 9. Gates

Suite **14,372 / 0 failures / 3 skipped** (+14 from 14,358 — exactly the
`CopyCensusTest` pins, no existing file gains a `@Test`). `cost_gate.py`
**+0.00% on all 20 counters**, which is the expected CONTROL for a round that
lands no behaviour change and here also says the hooks are inert.
`huge_methods.py --fail-over 0`: **0 over the limit**. **8-profile `--listAll`
grid, all eight `added=0 removed=0`** (46 diagnostics each, harness 94),
cross-round against round 897's committed captures with the identical recipe —
run because this round's hooks sit on `EpochMap.put`, on the arity read of every
call expression, and inside `spineArgListOverlay`'s shadow scan.

**No wall A/B is claimed.** The round lands an instrument and two refusals; what
is claimed is deterministic populations and a slope with an arithmetic
falsifier, 16/16 sign-separated across two processes.
