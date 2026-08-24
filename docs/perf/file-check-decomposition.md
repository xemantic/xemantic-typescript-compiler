# (INC.37) step 1 — the decomposition of ONE FILE'S OWN CHECKING

*2026-08-24, tree at `ca2673cd`. First in its sequence; the FLOOR half is
`FloorDecompositionMain` / (INC.3). **Nothing was optimised this round — this is
an instrument and a table.***

> **HEADLINE — FIVE THINGS.**
>
> **(1) `own(F)` is LINEAR in the file's node count, and `checker.ts` is at the
> *10th percentile* of per-node cost.** 6.27 µs/node against a population median
> of **9.71** over the 51 files with >2,000 nodes; `parser.ts` (5.8x smaller)
> reads **6.08** and `utilities.ts` **6.96**. The 1,726 ms is 275,478 nodes at a
> below-average price, not a super-linearity. **There is no quadratic to find.**
>
> **(2) `checkSpine` is 89–92% of `own(F)` for every file above ~15,000 nodes**
> — 1,576 of 1,771 ms on `checker.ts`, 143 of 155 on `binder.ts`. The ~400
> partition-scoped tail walkers are **10.5% on `checker.ts` and 5–7% elsewhere**,
> and they are FLAT: 78 rows above 0.5 ms, largest **11.45 ms**.
>
> **(3) Inside `checkSpine` the whole type system is ~16%.** On `checker.ts`:
> relation **39 ms**, type-node resolution **43**, member resolution **7**, flow
> narrowing **167** — 256 ms of 1,576. **84% is the walk and the handler bodies.**
>
> **(4) Round 847's SIX-handler set survives a single-file partition; its ORDER
> does not.** The six hold **65.7%** here against 63.0% whole-program-warm — but
> the top two swap *again*: `cpaSpineLeave` **22.9%** (round 847: third),
> `spineCtaM3StatementAnchor` 17.4%, `ccetSpineLeave` **10.9%** (round 847:
> first, 18.2%). A handler's share is a property of the POPULATION, and one file
> is a different population from 78.
>
> **(5) Σ`own(F)` over all 78 files is 6,841 ms against a whole-program check of
> 4,935 ms — a 1.39x RE-DERIVATION TAX.** The spine walk itself partitions
> exactly (Σ per-file nodes = **856,962** = the whole-program figure), so the
> extra 1,906 ms is shared type resolution a full build amortises across files
> and a per-file query pays again. That is the one term in this table that a
> better *architecture* rather than a faster *handler* could remove.

---

## 1. How this was measured

One binary, one profile (`build/bench/tsc-project-637d5746` — the compiler
profile: 78 files, 46 errors), `--noEmit`, this box (8 cores, 15.6 GB, zero
swap), Gradle and Kotlin daemons stopped between the build and the first sample.
The box was not touched while any run was in flight.

The quantity is a DIFFERENCE and **both arms are stated**:

```
own(F) = build(recheckOnly = {F})  −  build(recheckOnly = {a name the program does not contain})
```

taken per WALL and per PASS. Its control is built in: every floor-resident pass
row must cancel to ~0 in the per-pass subtraction, and every one does.

| instrument | what it draws | artifact |
|---|---|---|
| probe-free | `own(F)` wall, all 78 files, 4 draws each, ascending **and** descending | `run4`, `run6` |
| `rows` ([PassTiming] tier 1) | the ~480-row per-pass table, 3 draws/target | `run2` |
| `full` (tier 3) | `relation`/`typeNode`/`memberResolve`/`narrowWalk`/`typeOfExpr` | `run2` |
| `spine` (tier 2) | `spineNodes` per file, all 78 files | `run6` |
| `dispatch` (`SpineDispatch.PROBE`) | the per-HANDLER split | `run2` |

**Why `rows` and not `full` for the per-pass ms.** Round 846 measured the `full`
tier's own cost landing ~100% inside `checkSpine`, which is exactly the row this
question turns on. Measured again here, in this regime: `full`/`rows` on the
`checkSpine` row is **1.08–1.37x** (1.33x on `checker.ts`). So the ms come from
`rows` and the type-system sub-counters are read as SHARES of the *same arm's*
`checkSpine`, then applied to the `rows` arm's ms.

**Three things that had to be fixed before the table meant anything.**

* The `dispatch` tier needs a **warm-up of its own**. `SpineDispatch.PROBE` runs
  the handlers through a by-id dispatcher the production walk never executes, so
  without one the first target in the ladder absorbs the whole ramp — run 1 read
  `spineCtaM3StatementAnchor` at **82 µs per consultation** on the 171-node file
  it happened to measure first. That is a warm-up artifact wearing a handler's
  name (round 894's "a row is a LOCATION, not a price", one instrument over).
  The ladder is now walked down and back up after two discarded warm-up draws.
* The probe's timestamp-pair cost is **measured in situ** (34–36 ns here) rather
  than inheriting round 847's 38–40 ns, and subtracted per consultation.
* **A six-point size ladder cannot answer the scaling question**, and its
  "intercept" is whichever small file it drew. `corePublic.ts` (1,337 B) costs
  7.5 ms and `transformers/es2019.ts` (1,533 B) costs 26.5 ms — 3.5x apart at the
  same size. The ladder was replaced by a sweep of all 78 files.

---

## 2. The shape of a query

Floor **56 ms**; `own(F)` over all 78 files:

| | min | p25 | median | p75 | p90 | max |
|---|---:|---:|---:|---:|---:|---:|
| `own(F)` ms | 4 | 30 | **52** | 90 | 138 | **1,726** |
| query = floor + own | 60 | 86 | **108** | 146 | 194 | **1,782** |
| floor's share | 93% | 65% | **52%** | 38% | 29% | **3.1%** |

The median query reproduces (INC.31)'s 108–113 ms independently, which is the
cheapest available check that this subtraction is measuring the right thing.

**So there are two different latency problems and they need different levers.**
At the median file the floor is *still the larger half* (52%) and the file's own
check is 52 ms. At the tail — the file an IDE user is most likely to be *in*, and
the worst thing they will feel — the floor is **3.1%** and everything is
`checkSpine` on one file.

The ten slowest queries in the program:

| query ms | own ms | nodes | file |
|---:|---:|---:|---|
| 1,782 | 1,726 | 275,478 | `checker.ts` |
| 405 | 349 | 50,176 | `utilities.ts` |
| 348 | 292 | 48,009 | `parser.ts` |
| 274 | 218 | 34,845 | `factory/nodeFactory.ts` |
| 243 | 187 | 23,173 | `program.ts` |
| 215 | 159 | 26,810 | `emitter.ts` |
| 196 | 140 | 16,842 | `binder.ts` |
| 194 | 138 | 16,290 | `moduleNameResolver.ts` |
| 191 | 135 | 11,488 | `transformers/classFields.ts` |
| 184 | 128 | 16,252 | `commandLineParser.ts` |

---

## 3. THE SCALING ANSWER — linear in nodes, and `checker.ts` is *cheap* per node

**Bytes is a 10x-noisy proxy and must not be used.** Over files >100 KB the
per-byte price ranges 76 → 739 µs/KB: `diagnosticInformationMap.generated.ts`
(577 KB of data literals) is 76 and `utilities.ts` is 739, because a
declaration-only or data-only file has almost no expressions to check. Every
size-based regression in this round's first pass was corrupted by exactly that,
and each of them "predicted" `checker.ts` low by 1.2–4.3x — i.e. **would have
been read as super-linearity that is not there.**

Per NODE the picture is flat and the answer is unambiguous:

| nodes | own ms | **µs/node** | file |
|---:|---:|---:|---|
| 275,478 | 1,726 | **6.27** | `checker.ts` |
| 50,176 | 349 | 6.96 | `utilities.ts` |
| 48,009 | 292 | 6.08 | `parser.ts` |
| 39,249 | 103 | 2.62 | `types.ts` |
| 34,845 | 218 | 6.26 | `factory/nodeFactory.ts` |
| 27,770 | 46 | 1.66 | `diagnosticInformationMap.generated.ts` |
| 23,173 | 187 | 8.07 | `program.ts` |
| 16,842 | 140 | 8.31 | `binder.ts` |
| 11,488 | 135 | 11.75 | `transformers/classFields.ts` |

Distribution of µs/node over the 51 files with >2,000 nodes:
**p10 = 6.08, median = 9.71, p90 = 19.63, max = 27.89** — and **`checker.ts` is
6.27, i.e. at the p10.**

> **VERDICT: `own(F)` is LINEAR in nodes. `checker.ts` is not super-linear and is
> not even average — it is one of the cheapest files per node in the program.
> The 1,726 ms is 275,478 nodes at a below-median price, so the only lever on it
> is the CONSTANT FACTOR per node, and a hunt for something quadratic is a hunt
> for something that does not exist.**

The 4.5x spread in µs/node across files is CONTENT (expression density, generic
depth), not size — and it runs the *wrong* way for a "big files are pathological"
story: the two files above 200,000 nodes-equivalent in size are the two cheapest
per node in the whole table.

**A second, independent confirmation that the spine partitions exactly:**
Σ per-file `spineNodes` over the 78 narrowed builds = **856,962**, which is the
whole-program figure this repo has quoted since round 847, to the node.

---

## 4. `own(F)` per PASS — `checkSpine` is essentially all of it

`rows` tier, `narrowed(F) − floor`, min over 3 draws:

| file | own(pass) ms | **`checkSpine`** | share | `init:buildFileLocalTypeMaps` | tail walkers | share | tail rows >0.5 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| `transformers/es2019.ts` | 21.9 | 17.7 | 80.9% | 2.1 | 2.1 | 9.7% | 1 |
| `semver.ts` | 61.7 | 39.8 | 64.5% | **18.1** | 3.8 | 6.2% | 1 |
| `path.ts` | 41.2 | 34.7 | 84.3% | 2.0 | 4.5 | 10.9% | 1 |
| `binder.ts` | 154.9 | 143.1 | **92.4%** | 3.4 | 8.4 | 5.4% | 1 |
| `parser.ts` | 275.3 | 250.4 | **90.9%** | 5.2 | 19.7 | 7.2% | 9 |
| `checker.ts` | 1,771.0 | 1,576.3 | **89.0%** | 9.3 | 185.3 | 10.5% | 78 |

The per-pass sum reconstructs the wall to within 2–4% at every point above
`path.ts`, which is the arithmetic control on the whole table.

**The tail is FLAT and that closes it by round 830's arithmetic.** On
`checker.ts` the ~400 partition-scoped tail walkers cost 185.3 ms spread over 78
rows above 0.5 ms; the largest single row is `checkDestructuringDefaultTypeMismatches`
at **11.45 ms = 0.65%** of the query. Below `parser.ts` the whole tail is 2–8 ms.
There is no walker here to make cheaper.

**One outlier worth a name:** `init:buildFileLocalTypeMaps` is **18.1 ms = 29%**
of `own(semver.ts)` and 2–9 ms everywhere else, reproducibly (16.8 ms in the
independent run 1). (INC.25) made that pass partition-scoped; on this one file it
is the dominant term. Not pursued this round.

---

## 5. Inside `checkSpine` — the type system is ~16%

`full` tier, share of the SAME arm's `checkSpine`, applied to the `rows` arm's ms:

| file | `checkSpine` (rows) | relation | typeNode | member | flow narrowing | typeOfExpr\* |
|---|---:|---:|---:|---:|---:|---:|
| `binder.ts` | 143.1 | 8.6 (6.0%) | 7.1 (5.0%) | 2.6 (1.8%) | 22.9 (16.0%) | 31.9 (22.3%) |
| `parser.ts` | 250.5 | 12.2 (4.9%) | 23.0 (9.2%) | 4.9 (2.0%) | 19.5 (7.8%) | 60.8 (24.3%) |
| **`checker.ts`** | **1,576.4** | **38.7 (2.5%)** | **42.5 (2.7%)** | **7.1 (0.4%)** | **166.7 (10.6%)** | **335.1 (21.3%)** |

\* `typeOfExprNanos` double-counts a nested subtree once per level (CLAUDE.md) and
OVERLAPS the other four, so it is an upper bound and is not additive with them.
`relation` / `typeNode` / `memberResolve` / `narrowWalk` are outermost-guarded and
mutually disjoint at the top frame.

**So on `checker.ts` the four disjoint type-system rows are 255 ms = 16.2% of
`checkSpine`, and 84% of the file's own check is the walk plus the handler
bodies.** That is round 758's whole-program "the type system is only ~30% of
`checkSpine`" again, and *sharper* under a partition.

Narrowing walk counts: 10,479 on `checker.ts` (167 ms = **15.9 µs/walk**), 918 on
`parser.ts`, 748 on `binder.ts`, 4 on `es2019.ts` — 1.7–3.9 walks per KB, the
extreme-tail distribution round 735 documented.

---

## 6. Inside `checkSpine` — the per-HANDLER split

`SpineDispatch.PROBE`, per-consultation probe cost measured in situ and
subtracted. **59.0 consultations per node at every point on the ladder**, exactly
as `docs/perf/dispatch-table.md` records.

`checker.ts` — 275,478 nodes, 16,253,202 consultations, probe pair **36 ns**,
handlers(net) **1,259 ms** against a `rows`-tier `checkSpine` of 1,576 ms (so the
handlers are ~80% of the row and the walk machinery ~20%):

| handler | net ms | share of handlers | ns/consult |
|---|---:|---:|---:|
| leave `cpaSpineLeave` | **288.6** | **22.9%** | 1,048 |
| enter `spineCtaM3StatementAnchor` | **218.9** | **17.4%** | 795 |
| leave `ccetSpineLeave` | **136.8** | **10.9%** | 497 |
| enter `spineIanyEnterNode` | 68.0 | 5.4% | 247 |
| enter `ctaSpineEnter` | 61.8 | 4.9% | 224 |
| enter `spineArithEnterNode` | 53.0 | 4.2% | 192 |
| leave `spineArithLeaveNode` | 45.4 | 3.6% | 165 |
| enter `spineCeEnterNode` | 29.9 | 2.4% | 108 |

`parser.ts` (48,009 nodes, handlers(net) 226.5 ms) puts
`spineCtaM3StatementAnchor` first (21.2%), `cpaSpineLeave` second (18.4%),
`ccetSpineLeave` third (17.4%). `binder.ts` (16,842 nodes, 107.1 ms) puts
`ccetSpineLeave` first (26.1%), then `spineIrLeaveNode` (14.7% — a handler that is
in nobody's top six whole-program), `cpaSpineLeave`, `spineCtaM3StatementAnchor`.

> **CONFIRMED: round 847's six-handler SET.** `ccetSpineLeave`,
> `spineCtaM3StatementAnchor`, `cpaSpineLeave`, `ctaSpineEnter`,
> `spineIanyEnterNode`, `spineArithEnterNode` hold **65.7%** of handler cost on a
> single-file `checker.ts` partition against **63.0%** whole-program warm.
>
> **REFUTED: the ORDER.** It is not stable across files at all — the top-three
> permutation differs on all three of `binder.ts` / `parser.ts` / `checker.ts`,
> and `cpaSpineLeave` moves from round 847's third place to first. Round 847
> explained its own swap by differing warm-up rates; here every arm is warm, so
> the remaining cause is the POPULATION. **A per-handler ordering is a claim about
> a codebase's shape, not about the compiler** — quote the file with the ranking.

**The three biggest handlers are 645 ms = 51% of handler cost and 37% of
`own(checker.ts)`.** Below ~4,000 nodes the per-handler table degenerates into
first-touch resolution charged to whichever handler asks first (a different
handler on each of the three small files, at 4.7–82 µs/consultation) — read those
rows as a location, never as a handler price.

---

## 7. The re-derivation tax — Σ`own(F)` is 1.39x the whole-program check

Same process, same binary: warm full rebuild **4,949 / 5,190 ms** (two batches),
floor **63–72 ms**, so the whole-program CHECK is ~**4,935 ms**.
Σ`own(F)` over all 78 files is **6,841 ms**.

```
Σ own(F) / (full − floor) = 6,841 / 4,935 = 1.39x
```

The spine WALK partitions exactly (node counts sum to the whole-program figure),
so the extra **1,906 ms** is not duplicated walking — it is shared type
resolution (lib types, foreign declarations, instantiations) that a whole-program
build resolves once and every per-file query re-derives inside its own fresh
`Checker`. Averaged it is ~24 ms per query, i.e. **roughly half of the median
file's entire own check.**

This is the same object (INC.14) measured from the other side: reusing one
`Checker` across k queries is worth **1.79x / 3.19x / 3.82x** at k = 2 / 8 / 26,
and it collapses the floor and this tax together.

---

## 8. What this NAMES, with a prize and a grader

| # | lever | prize on `checker.ts` | prize at the median file | how it would be graded |
|---|---|---:|---:|---|
| 1 | the three big spine handlers (`cpaSpineLeave` / `spineCtaM3StatementAnchor` / `ccetSpineLeave`) — (SPINE.1) | **645 ms is the whole object; a 30% cut = ~195 ms = 11% of the query** | ~9 ms of 108 | `scripts/file-check-decomposition.sh` `dispatch` arm before/after, plus the corpus and `cost_gate.py` |
| 2 | the cross-query re-derivation tax — (INC.14) checker reuse | 0 (it is already the only file walked) | **~24 ms of 108 = 22%** | `scripts/checker-reuse-differential.sh` (already exists; 1.79–3.82x measured) + `capture-equivalence.sh` |
| 3 | flow narrowing | ≤ **167 ms = 9.4%** of the query, 15.9 µs over 10,479 walks | ~5 ms | `--passTiming` `narrowWalk*`; round 735's tail census says 0.56% of walks carry 47% of the cost, so the prize is concentrated and the population is known |

**And three things this table CLOSES, on arithmetic:**

* **A hunt for super-linearity in `own(F)`.** There is none: `checker.ts` is at
  the p10 of µs/node. Any future "big files are pathological" claim must first
  divide by nodes.
* **The partition-scoped tail walkers.** 185 ms on `checker.ts` over 78 rows,
  largest 11.45 ms (0.65% of the query), and 2–8 ms on everything else. Round
  830's arithmetic: there is nobody to make cheaper.
* **Caching in front of type resolution, for the per-file query.** relation +
  typeNode + memberResolve is **89 ms = 5.6%** of `own(checker.ts)` and ~2 ms at
  the median. The whole population is smaller than the measurement band, which
  is CLAUDE.md's standing closed direction reproduced in a new regime.

---

## 9. Reproducing

```bash
./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm
./gradlew --stop; pkill -f 'KotlinCompile[D]aemon'

# the ladder + all four tiers (~6 min)
scripts/file-check-decomposition.sh > run.txt
python3 scripts/file_check_decomposition_report.py run.txt

# the scaling sweep: probe-free own(F) + spineNodes for every program file (~4 min)
XTSC_FCD_ALL=1 scripts/file-check-decomposition.sh > sweep.txt

# a chosen set instead of the default size ladder
XTSC_FCD_FILES=binder.ts,parser.ts scripts/file-check-decomposition.sh
```

The script REFUSES (exit 2) rather than skipping when the project or the runner
is absent, and the runner REFUSES when the full build reports no diagnostics or
the floor build reports any — a decomposition that quietly measures nothing is
round 853's defect and its symptom is a plausible table.

Artifacts of this round: `build/bench/inc37/run{1,2,3-tiny,4-sweep,5-floor,6-sweep}.txt`
(gitignored; the tables above are the record).
