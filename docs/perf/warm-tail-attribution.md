# (WARM.6) — the WARM attribution of the ~416 TAIL passes and the FRONT END

*Round 859, 2026-08-08. Sixteenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → … → `warm-spine-attribution.md` (847) →
`lib-type-rederivation.md` (849) → `warm-intra-handler.md` (850) →
`narrowed-any-opening-price.md` (854) → here. This document is to
`front-end-attribution.md` (round 738) and `bind-attribution.md` (round 801)
what `warm-intra-handler.md` is to the cold section tables: the same partitions,
re-taken inside a JIT-warm process, because round 847's law is that a cold
ordering does not transfer.*

> **HEADLINE — FOUR THINGS, THE FIRST OF WHICH IS THE NEGATIVE THE ROUND WAS
> COMMISSIONED TO FIND.**
>
> **(1) ROUND 801's "THE TAIL IS FLAT" SURVIVES THE REGIME CHANGE, AND THAT WAS
> NOT KNOWN.** Warm, the ~416 tail passes are **1,530 ms = 20.3% of the
> artifact**, and **exactly ONE of them clears 1%**: `init:buildFileLocalTypeMaps`
> at **268.4 ms = 3.56%**. The second-largest is **50.0 ms = 0.66%**, and
> **344 of the 416 are under 5 ms each**, summing 325 ms. There is no warm-only
> giant hiding in the tail. § 3.
>
> **(2) BUT THE WARM-UP RATIO IS *NOT* UNIFORM, AND ITS BOTTOM IS A PRICED
> CANDIDATE.** Over the 72 tail passes worth ≥ 5 ms the ratio spans **0.85× to
> 5.68×** (median 2.90×), against `checkSpine`'s 3.27×. The two slowest —
> `checkUmdGlobalVsDeclareGlobalConst` (**0.85×**, i.e. *slower warm than cold*,
> every warm draw above every cold draw) and
> `checkCrossFileModuleAugmentationDuplicates` (**1.05×**) — are together
> **98.2 ms = 1.30% of the warm artifact against 0.38% cold, a 3.4× share
> increase**. **Both run the SAME `java.util.regex` scan over all 9,977,097
> characters of the program, and on this profile it matches ZERO times.** A
> standalone JVM running that exact pattern over that exact text measures
> **84 ms cold → 54–59 ms warm**, i.e. more than each pass's whole warm row —
> the scan IS these passes. § 4 and § 7 (WARM.7).
>
> **(3) THE FRONT END IS 8.8%, NOT 11.1% — THE RESIDUAL IS NOT THE FRONT END.**
> Round 846 priced it as `wall − checkerInitNanos`. Measured with the `FrontEnd`
> probe in the SAME warm process: front end proper (config+crawl+parse+imports+
> bind) **663 ms = 8.79%**, post-checker tails **143 ms = 1.90%**; the two sum to
> **806 ms against the residual's 813 ms — a 99.2% partition check across two
> independent instruments inside one process.** Their warm-up could hardly
> differ more: the front end warms **4.38×** and the post-checker tails
> **1.27×**. § 5.
>
> **(4) INSIDE THE FRONT END THE WARM COST IS THE FLOW GRAPH, NOT THE I/O.**
> The crawl collapses (7.52×), config collapses (41×), `bindLexicalScopes`
> collapses (6.44×) — and `FlowGraphBuilder.build` does not (**2.73×**), leaving
> its walk at **317 ms = 4.20% of the warm artifact**, the largest single region
> outside `checkSpine`. Round 801 measured and CLOSED that region cold; its warm
> share is **higher** (3.30% → 4.20%), so the closure is worth re-reading, not
> re-deriving. § 6.
>
> **Nothing was optimized. Nothing under `commonMain` changed** — the only code
> change is a `frontend` tier in the `commonTest` bench harness plus its pin.

---

## 1. How this was measured

One binary (`./gradlew compileKotlinJvm compileTestKotlinJvm`, BUILD SUCCESSFUL
in 2m37s, **0 compiler warnings**), one profile
(`build/bench/tsc-project-637d5746` — the compiler profile: 78 files, 46 errors,
9,977,097 source characters), `--noEmit` throughout, this box (8 cores, 15.6 GB,
zero swap).

**Order, and it is the round-851 order:** every gradle-invoking step ran
*before* the daemons were stopped — build, the full suite, `cost_gate.py`,
`huge_methods.py --fail-over 0`, and the classpath resolution through
`scripts/lib/dep-classpath.sh` (round 858; `build/bench/cp.txt` has no readers).
Only then `./gradlew --stop` + a bracket-pattern `pkill -f
'KotlinCompile[D]aemon'`, and only then the first sample, with 9.9 GB free.
The box was not touched while the script ran (round 774).

**The positive control the instrument needs (round 853).** A gate that reads a
class *directory* must prove the code under test is in it. The measuring script
aborts unless the module's own main dir holds `MainKt` **and** the test dir
holds `BenchFrontEndTierTest.class` — a class that did not exist before this
round, so a stale directory cannot satisfy it. It held: 649 main classes, 706
test classes.

| arm | harness | n | tier |
|---|---|---:|---|
| cold, probe-free | `MainKt --noEmit` | 2 | — |
| cold, per-pass | `MainKt --noEmit --passTimingRows` | 2 | `rows` |
| cold, front end | `MainKt --noEmit --frontEnd` | 2 | — |
| **warm, per-pass** | `BenchMain <proj> 3 8 rows,frontend,rows,frontend` | **4** | `rows` |
| **warm, front end** | *(the same two processes)* | **4** | `frontend` |

Two draws of each tier per process, in two processes — round 846 measured that
the probe's own cost warms up (3,457 ms on the first instrumented rebuild, 1,856
on the second), so **a single draw is a first draw, not a steady-state one.**
The cold arms are interleaved `free / rows / frontend` × 2, never blocked.

**Falsification.** All 8 instrumented rebuilds answered **78 files / 46 errors**;
`BenchMain` aborts non-zero if an instrumented rebuild disagrees with its own
measured loop, and none did. `cost_gate.py` reads **+0.00% on all 20 counters**
against the recorded baseline, so the compile being attributed is the compile
the baseline describes.

**Why this round was cheap.** Round 846 established that the `rows` tier keeps
the whole per-pass table for **+0.25% cold and 0.0% warm**, and that the probe
lands 99.5–99.8% inside checker-init and 101–109% inside `checkSpine` — so the
**tail rows and the front-end rows of a `rows` run are probe-FREE** and readable
as absolutes. This round's own overheads reproduce that: the `rows` tier
measured −327/−655/+152/+215 ms against its process median and the new
`frontend` tier −356/−612/+70/+271 ms — both straddling zero, i.e. both free.

### 1.1 What was BUILT this round

`BenchMain` gained a **`frontend` tier**. It is not a `PassTiming` tier: it
leaves the pass probe off and arms round 738's `FrontEnd` probe for one
rebuild. That probe had never been run inside a warm process, for the same
reason round 851 gave for the largest spine handler going un-partitioned for
three rounds — **it had no tier name.** It needs no `*coarse` twin: its spans
are per-FILE (78 files, ~20 pairs each) rather than per-node, so its boundary
cost is microseconds against a ~900 ms region, and that is regime-independent.

`BenchFrontEndTierTest` pins it, and is built to fail **if the tier were
inert** — the one thing a tier pin gets wrong. A tier that is listed,
dispatches, and prints a well-formed header while never arming `FrontEnd.mode`
produces a report full of zeros: structurally valid, and a silent measurement of
nothing. So the fixture RECORDS through the probe's own entry points inside
`measureTier`'s build lambda and asserts the recorded values are in the text
(`files read: 1 (4242 chars)`, and the bind row, which the report prints only
when its `calls` is non-zero). Both entry points begin `if (mode != ON) return`,
so dropping the arm reddens it; a negative control asserts the same calls are
no-ops off the tier.

---

## 2. The budget this round measures against

Absolutes for THIS round only — CLAUDE.md's cross-round rule stands (the
sequential anchor has a 12.8% cross-round spread; this round's cold probe-free
median is 25,199 ms and round 846's was 26,843).

| | cold (`rows`, n=2) | warm (`rows`, n=4) | cold→warm |
|---|---:|---:|---:|
| wall | 24,715.0 | **7,542.9** | 3.28× |
| checker-init | 21,199.5 | **6,730.2** | 3.15× |
| `checkSpine` | 16,924.3 | **5,179.4** | **3.27×** |
| the other 416 passes | 4,089.3 | **1,529.9** | **2.67×** |
| front end + tails (residual) | 3,515.5 | **812.7** | 4.33× |
| `checkSpine` / wall | 68.48% | **68.67%** | |
| the 416 / wall | 16.55% | **20.28%** | |

Round 846's central ratios **replicate**: it read `checkSpine` 3.46× against
the tail's 2.59×; this round reads **3.27× against 2.67×**, on a different day,
a different build and a fresh dependency tail. The tail's share rising from
16.6% to 20.3% is the regime shift this round was commissioned to explain.

Warm draw-to-draw spread is **10.5%** on the `rows` wall and 10.7% on the
`frontend` wall (four draws across two processes) — so a per-row difference
below ~10% is not resolved by this instrument, and every verdict below is
either far outside that band or explicitly labelled as unresolved.

---

## 3. § THE TAIL, RANKED WARM — and it is flat

416 rows. `checkSpine` is excluded (it is 68.67% and has its own three
documents).

| # | pass | warm ms | % warm wall | cold ms | % cold wall | warm/cold | warm spread |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | `init:buildFileLocalTypeMaps` | **268.4** | 3.56% | 690.0 | 2.79% | 2.57× | 41% |
| 2 | `checkUmdGlobalVsDeclareGlobalConst` | **50.0** | 0.66% | 42.5 | 0.17% | **0.85×** | 19% |
| 3 | `checkCrossFileModuleAugmentationDuplicates` | **48.3** | 0.64% | 50.6 | 0.20% | **1.05×** | 14% |
| 4 | `init:trackAllImportReferences` | **47.3** | 0.63% | 83.9 | 0.34% | 1.77× | 59% |
| 5 | `checkBigintPropertyNames` | **26.7** | 0.35% | 60.7 | 0.25% | 2.27× | 12% |
| 6 | `checkSpreadPropertyOverrides` | **26.0** | 0.34% | 71.6 | 0.29% | 2.76× | 11% |
| 7 | `checkDestructuringDefaultTypeMismatches` | **25.1** | 0.33% | 45.8 | 0.19% | 1.82× | 29% |
| 8 | `checkNamespaceUsedAsType` | **24.3** | 0.32% | 57.4 | 0.23% | 2.36× | 12% |
| 9 | `checkThisTypeInObjectLiterals` | **21.8** | 0.29% | 57.1 | 0.23% | 2.62× | 13% |
| 10 | `checkTypeArgumentConstraints` | **21.4** | 0.28% | 62.2 | 0.25% | 2.92× | 5% |
| 11 | `checkCallTypeArgCount` | **21.2** | 0.28% | 75.1 | 0.30% | 3.55× | 9% |
| 12 | `checkEnumNominalClassMismatches` | **20.0** | 0.27% | 50.1 | 0.20% | 2.50× | 12% |

**The concentration, which is the negative:**

| | warm ms | % of the tail | % of the warm wall |
|---|---:|---:|---:|
| top 1 | 268.4 | 17.5% | 3.56% |
| top 3 | 366.6 | 24.0% | 4.86% |
| top 10 | 559.2 | 36.6% | 7.41% |
| top 20 | 730.9 | 47.8% | 9.69% |
| top 50 | 1,041.5 | 68.1% | 13.81% |
| all 416 | 1,529.9 | 100% | 20.28% |

**344 of the 416 passes are under 5 ms warm and sum to 325 ms (4.3%).** Round
801's cold verdict — "the ~400 tail passes are FLAT, largest 75 ms = 0.26%" —
is a statement about the cold regime that this round can now say also holds
warm, with the numbers rescaled: largest 268 ms = 3.56%, second 0.66%.

**Partition check.** The 417 rows sum to **6,725.7 ms against a directly
measured `checkerInitNanos` of 6,730.2 ms — 99.93%, an outside-pass residue of
4.5 ms.** Cold the same check reads 21,031.2 against 21,199.5 (99.2%, residue
168.2 ms); the residue shrinking 37× warm is itself a JIT effect on the
dispatch between passes and is the only thing in this document that the `rows`
tier's own boundaries could be charged with.

---

## 4. § THE RATIO IS NOT UNIFORM — and its bottom is one regex

Restricted to the 72 tail passes worth ≥ 5 ms warm (below that, a 10% draw
spread on a 3 ms row says nothing):

| statistic | warm/cold ratio |
|---|---:|
| min | **0.85×** |
| p25 | 2.50× |
| **median** | **2.90×** |
| p75 | 3.46× |
| max | 5.68× |

`checkSpine` is 3.27×. So the median tail pass warms *slightly worse* than the
spine, and the distribution is wide enough that reading "the tail warms 2.67×"
as a property of a pass is exactly round 847's error one level down.

### 4.1 The two passes that do not warm at all

| pass | cold draws | warm draws | ratio |
|---|---|---|---:|
| `checkUmdGlobalVsDeclareGlobalConst` | 41.6, 43.3 | 47.2, 48.1, 56.5, 48.0 | **0.85×** |
| `checkCrossFileModuleAugmentationDuplicates` | 49.8, 51.5 | 50.7, 44.1, 48.9, 49.4 | **1.05×** |

The first is the sharper result: **every warm draw exceeds every cold draw**,
non-overlapping ranges — it is genuinely *slower* in a warm process. Together
the two are **98.2 ms = 1.30% of the warm artifact** against **93.1 ms = 0.38%
of the cold one**, i.e. their share **more than triples** while their absolute
cost does not move (0.95× combined). Cold, they are two unremarkable rows in a
flat tail; warm, they are the second and third largest passes in the compiler
outside `checkSpine`.

### 4.2 WHY, and it is not an inference about which line is hot

Both passes contain the same statement — a `java.util.regex` scan of the FULL
SOURCE TEXT of every checked file:

```kotlin
val umdRegex = Regex("""(?m)^[ \t]*export[ \t]+as[ \t]+namespace[ \t]+([A-Za-z_$][A-Za-z0-9_$]*)""")
for (result in checkedResults) { for (m in umdRegex.findAll(result.sourceFile.text)) { … } }
```

`Checker.kt:173428` (`checkUmdGlobalVsDeclareGlobalConst`) and `:173543`
(`checkCrossFileModuleAugmentationDuplicates`) — the identical pattern,
compiled twice, run twice, over the same 9,977,097 characters.

**And the compiler already contains the control that proves the point.** There
is a THIRD reader of essentially this pattern, `checkExportAsNamespaceSelfCycle`
(`Checker.kt:86436`), and it measures **0.0 ms in every cold and warm draw** —
because it reaches `findAll` only after two cheap guards have passed (the file
must be a `.d.ts`, and it must contain an `export = X` statement, whose
`?: continue` sits *above* the scan). Same pattern, same corpus, same regime:
gated, it is free; ungated, it is 1.30% of a warm compile. Nothing about
`java.util.regex` is being blamed that a guard does not fix.

A standalone JVM running **that exact pattern over that exact text** (78 files
read from the profile, 12 iterations, `scripts`-free, `java.util.regex`
directly — Kotlin's `Regex` *is* `java.util.regex` on the JVM):

```
iter  0     84.0 ms      iter  6     55.7 ms
iter  1     98.8 ms      iter  7     55.8 ms
iter  2     79.6 ms      iter  8     53.9 ms
iter  5     75.6 ms      iter 11     59.4 ms     hits: 0
```

**84 ms cold, 54–59 ms in steady state — more than either pass's entire warm
row (50.0 and 48.3 ms).** The scan is not *part* of these passes' cost; within
the resolution of this instrument it *is* their cost, and the ~1.5× it warms is
what caps the passes at 0.85–1.05×. It also finds **zero** matches: tsc's own
sources contain no `export as namespace` at all, so the compiler reads ten
megabytes twice per compile to emit nothing.

**Why a regex does not warm while our own scanner does.** `java.util.regex` is
already-compiled library code driving a data-dependent automaton; a warm process
has nothing new to teach C2 about it. The comparison is available in this same
round's data: the hand-written B464 text scan in `FlowGraphBuilder` (§ 6) covers
6.3 M characters and warms **3.27×**. Same kind of work, one written as
bytecode C2 can specialise and one delegated to a library matcher.

**Stated as what it is:** the sub-partition of these two passes was NOT taken
with an in-situ probe — the attribution rests on (a) the standalone scan costing
more than each pass's whole row, and (b) both passes containing that scan and
nothing else in common. A one-span probe around each `findAll` loop would settle
it and is the first step of (WARM.7).

---

## 5. § THE FRONT END — the residual is not the front end

Round 846 priced the front end as `wall − checkerInitNanos` and got 11.1%. That
residual contains everything the compile core does after the checker as well.
With both instruments in the SAME warm process:

| region | warm ms | % warm wall | cold ms | % cold wall | warm/cold |
|---|---:|---:|---:|---:|---:|
| **front end proper** (config+crawl+parse+imports+bind) | **663.2** | **8.79%** | 2,902.5 | 11.74% | **4.38×** |
| **post-checker tails** | **143.2** | **1.90%** | 182.5 | 0.74% | **1.27×** |
| sum | 806.4 | 10.69% | | | |
| the `rows` tier's residual | 812.7 | 10.77% | 3,515.5 | 14.22% | |

**PARTITION CHECK: 806.4 against 812.7 — 99.2%, and both numbers come from the
same process, minutes apart, from two probes that share no code.** That is the
strongest check in this document. (The cold column's equivalent does NOT check
out — 3,085 against 3,515 — because there the two arms are different JVMs whose
walls differ by 3%; a cross-process residual is not a partition check and is not
quoted as one.)

Two corrections to round 846 follow. **The front end is 8.8% of a warm compile,
not 11.1%**, and it warms **4.38×**, not 4.08×. And the 1.9% that is not the
front end has the *worst* warm-up ratio of any region measured this round
(1.27×) — under `--noEmit` its transform/emit/decl-emit sub-rows have **zero
calls** (round 738's gate, still holding), so those 143 ms are the post-checker
*tails*, and there is no probe below them. **That is 1.90% of the warm
artifact with no attribution at all** — see (WARM.8).

---

## 6. § INSIDE THE FRONT END — the flow graph is what stays

| phase | warm ms | % warm wall | cold ms | warm/cold | calls |
|---|---:|---:|---:|---:|---:|
| config load + `@types` + root glob | 2.2 | 0.03% | 93.0 | **41.3×** | 1 |
| import-graph crawl (WALL) | 139.5 | 1.85% | 1,049.0 | **7.52×** | 1 |
|  — read+decode (CPU sum) | 78.5 | — | 1,008.5 | 12.85× | 78 |
|  — pre-parse (CPU sum) | 779.2 | — | 8,555.0 | 10.98× | 78 |
| `extractRelativeImports` | 1.0 | 0.01% | 17.5 | 17.5× | 78 |
| **bind (all program files)** | **520.5** | **6.90%** | 1,743.0 | **3.35×** | 1 |
|  — `bindStatements` | 14.2 | 0.19% | 38.5 | 2.70× | 123 |
|  — `bindLexicalScopes` | 86.8 | 1.15% | 558.5 | **6.44×** | 123 |
|  — **`FlowGraphBuilder.build`** | **421.0** | **5.58%** | 1,151.0 | **2.73×** | 123 |
|     — B464 `collectReassignedNamesInRange` | 95.5 | 1.27% | 312.0 | 3.27× | 2,014 |
|        — of which the text scan + cache | 94.2 | 1.25% | 308.0 | 3.27× | 2,014 |
|     — B464 `collectClosureLocalNames` | 0.8 | 0.01% | 4.0 | 5.33× | 2,014 |
|     — B467 `collectEnclosingVarDecls` | 8.0 | 0.11% | 20.0 | 2.50× | 2,014 |
|     — **the flow WALK (residue)** | **316.7** | **4.20%** | 815.0 | **2.57×** | — |
| checker construct + `getDiagnostics` | 6,727.0 | 89.18% | 22,342.0 | 3.32× | 1 |
| post-checker (tails; transform/emit have 0 calls) | 143.2 | 1.90% | 182.5 | 1.27× | 1 |

The READ and PRE-PARSE rows are **CPU sums across crawl workers**, not wall —
`PREPARSE` (779 ms) exceeding `CRAWL` (139 ms wall) by 5.6× IS the parallel
speed-up, and neither may be added into a total. That was round 738's caveat and
it is regime-independent; the census behind it is unchanged (78 files,
9,977,097 chars, **78 pre-parses reused / 0 fresh**, 876,201 lexical node pops,
236,587 flow nodes over 123 graphs, 2,014 closure starts, 1,220 text scans over
6,256,904 chars).

**The shape of the warm front end is the opposite of the cold one.** Everything
that touches the filesystem or runs once per program collapses — config 41×, the
crawl 7.5×, `extractRelativeImports` 17.5× — because that cost was page-cache
misses and class loading, and neither recurs. What is left is
`FlowGraphBuilder.build` at **5.58%**, of which the walk itself is **4.20% of
the whole warm artifact**: the largest single region in this compiler outside
`checkSpine`, and it warms 2.57×, below the spine's 3.27×.

**Round 801 measured that region cold and CLOSED it** (`bind-attribution.md`:
1,549 ms = 6.0%, `FlowGraphBuilder.build` ~1,050 of which ~700 the walk at
3.0 µs/node; its B464 suffix-set deferral was the round-801 "produced ≠ deleted"
negative — created 1,143, materialized 1,143). Warm, the walk is 316.7 ms over
236,587 flow nodes = **1.34 µs/node**, a 2.2× improvement on the cold rate, and
its SHARE is nonetheless *higher* (3.30% → 4.20%). Nothing here re-opens round
801's verdict — the ratio is a reason to re-read it before the next agent
re-derives it, not evidence against it.

---

## 7. § CANDIDATES, PRICED

The rule this arc runs on: quote what deleting a region would return **in ms and
in percent of the warm artifact**, and say plainly when there is nothing.

### (WARM.7) — the duplicated UMD regex scan. **98.2 ms = 1.30% warm.** RECOMMENDED.

The only candidate this round found that clears the ±1.0% warm band on its own,
and the first in the warm arc since round 850's four consecutive negatives.

* **Ceiling: 98.2 ms = 1.30% of a warm rebuild** (0.38% cold) — the whole of
  both passes, which is what deleting the scan would approach, since on this
  profile it matches nothing and both passes' remaining AST work is what the
  ~2 ms difference between them buys.
* **Risk-free half: run the scan ONCE. ~49 ms = 0.65% warm.** The two passes
  compile and execute the identical pattern over the identical text; a single
  shared per-file result consumed by both is behaviour-preserving by
  construction.
* **The other half needs a SOUND pre-filter, and the obvious one does not
  fire here.** `indexOf("export as namespace")` is NOT equivalent — the pattern
  admits arbitrary runs of spaces and tabs between the three tokens. A filter on
  the bare token `namespace` *is* sound (the pattern requires that literal), but
  tsc's own sources are full of `namespace` declarations, so on THIS profile it
  would fire on nearly every file and return nothing. **Priced honestly, the
  reachable prize on the compiler profile is the dedup, 0.65%**; the full 1.30%
  needs the scan itself replaced by a hand-written one, which the same round's
  data says is the right shape (our own B464 scanner warms 3.27×; the library
  matcher warms 1.5×).
* **The shape to copy is already in the file.** `checkExportAsNamespaceSelfCycle`
  runs the same pattern and costs **0.0 ms**, because its `.d.ts` test and its
  `export = X` lookup both sit above the scan (§ 4.2). A `.d.ts` gate would
  return the FULL 1.30% on this profile — **it has 0 `.d.ts` files among its
  78** — but whether it is *sound* is a correctness question this round did not
  settle and must not be assumed: `checkUmdGlobalVsDeclareGlobalConst`'s KDoc
  says only that these constructs *live* in `.d.ts` files, which is where they
  are found, not a proof of where they are legal. The implementing round owes
  that gate a corpus run and a hand-written pin, not an inherited parenthetical
  (CLAUDE.md's "a deliberate exclusion is a debt with a named creditor", in the
  other direction).
* **First step is a measurement, not an edit:** one span around each `findAll`
  loop. § 4.2's attribution is strong but is not an in-situ probe.
* **What must not break:** the two passes are the sole owners of TS2451 for a
  UMD-global/`declare global` collision and for an augmentation re-declaring a
  block-scoped export. Both are corpus-pinned; a shared scan must keep each
  pass's own `Occ`/`Decl` positions, which carry the source text for
  `getLineAndCharacterOfPosition`.

### (WARM.8) — the post-checker tails. **143.2 ms = 1.90% warm, UNATTRIBUTED.**

The worst warm-up ratio measured this round (**1.27×**) on a region with no
probe below it. Under `--noEmit` the transform/emit/decl-emit sub-rows have zero
calls, so this is not emit work; what it *is* has never been asked. This is not
a candidate — it is an unmeasured region that ranks above every candidate the
last four warm rounds produced, and sizing it needs one more `FrontEnd`
constant, not a round.

### `init:buildFileLocalTypeMaps` — **268.4 ms = 3.56% warm.** ALREADY PRICED; RE-PRICE BEFORE ACTING.

The one tail pass over 1%, and round 829 already censused it with `--fltmCensus`:
12,738 direct resolves → 4,161 entries of which 1,499 are ever read, and the
deletable part (never read AND never asked again) is **47.1%**, which that round
priced at **0.8–1.0% of the compile** and declined as below the cold A/B band
against a program-wide name-resolution blast radius. **Warm, 47.1% of 3.56% is
~1.68%** — above the warm band. That is an arithmetic projection of a
population share onto a ms row, not a measurement, and it inherits round 829's
two hard constraints unchanged: the `typealias` branch's resolutions exist to
trip `deepInstantiationBailed` (not resolving them DELETES a TS2589/TS2615
rather than deferring it), and the census must be keyed on the CALLS, since
67.3% of its resolutions store nothing at all. **Its warm draw spread is 41%,
the widest in the top 12** — so even the 268.4 ms is n=4 and soft.

### And the ones that are NOT candidates

* **The tail below rank 4.** 412 passes, 1,164 ms, none over 0.35%, 344 of them
  under 5 ms. There is no lever here at any granularity a round could act on.
* **`init:trackAllImportReferences`** (47.3 ms, 0.63%, 1.77×) — under the band
  and with a **59%** draw spread, the widest in the table.
* **The crawl and the parse.** 139.5 ms wall = 1.85% warm, and 78 of 78
  pre-parses are reused (the core's own parse loop is 0 ms, as in round 738).
  Warm, the front end's I/O is already gone.

---

## 8. What this does NOT show

* **One profile.** The 78-file compiler profile, `--noEmit`, sequential. A
  project that actually contains `export as namespace` would make (WARM.7)'s
  passes emit rather than scan-and-return, and a project with fresh (non-reused)
  pre-parses would move § 6's crawl rows.
* **No A/B, nothing optimized, no compiled `commonMain` code changed.**
* **The two slow-warming passes were not sub-partitioned in situ** (§ 4.2).
* **`init:buildFileLocalTypeMaps`'s warm prize is a projection**, not a
  measurement (§ 7).
* **The `spine` tier was not taken**, so no spine sub-rows or per-kind table
  from this round — `warm-spine-attribution.md` (847) and `warm-intra-handler.md`
  (850) remain the authority there.
* **n=4 warm / n=2 cold**, with a 10.5% warm draw spread: a row below ~5 ms is
  not resolved, and a ratio inside ~10% of another is not distinguished from it.
* **Emit mode was not measured**, so nothing here transfers to the CI
  `bench-3way.sh` ratio (`ARCHITECTURE-RETHINK.md` § 0.2).

---

## 9. Reproducing

```bash
# 1. build, then every gradle-invoking gate, THEN the daemon stop (round 851)
./gradlew compileKotlinJvm compileTestKotlinJvm
rm -rf */build/test-results/jvmTest && ./gradlew jvmTest
python3 scripts/cost_gate.py
python3 scripts/huge_methods.py --fail-over 0
DEPS=$(scripts/lib/dep-classpath.sh --print)          # round 858 — never cp.txt
./gradlew --stop; pkill -f 'KotlinCompile[D]aemon'; sleep 6; free -m

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
M=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
T=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test

# 2. WARM — two processes, two draws per tier (round 846: the probe warms up)
java -Xmx4g -cp "$M:$T:$DEPS" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 8 rows,frontend,rows,frontend

# 3. COLD — interleave the three arms, n>=2 each
java -Xmx4g -cp "$M:$DEPS" com.xemantic.typescript.compiler.MainKt --noEmit "$PROJ"
java -Xmx4g -cp "$M:$DEPS" com.xemantic.typescript.compiler.MainKt --noEmit --passTimingRows "$PROJ"
java -Xmx4g -cp "$M:$DEPS" com.xemantic.typescript.compiler.MainKt --noEmit --frontEnd "$PROJ"
```

Raw logs (`cold-{free,rows,frontend}-{1,2}.txt`, `warm-{1,2}.txt`, the standalone
`UmdScan` microbenchmark and `analyze859.py`) were kept in the session
scratchpad and are not committed; every figure above is derivable from a re-run
of the block.

---

## 10. § (WARM.7) LANDED — round 860, and the predicted-vs-actual

*Appended by round 860, which executed § 7's recommendation. Kept here rather
than in a new document because the value of a priced candidate is only visible
next to the price: § 7 predicted a **risk-free 0.65%** and called the other half
gated and unsound-until-proven. Both halves landed, the total is **1.17%**, and
the second half needed no gate at all.*

### 10.1 What landed

* **(a) the dedup** (`5462fa75`). Both passes read
  `scanUmdExportAsNamespace` through `Checker.umdExportAsNamespaceOccurrences`,
  a per-FILE memo whose value is a pure function of the file text — no ambient
  state, so it cannot be the program-ORDER dependency of rounds 754/776/778.
* **(b) the matcher** (`c9b693cd`). The `java.util.regex` scan is replaced by a
  hand-written EXACT equivalent anchored on the literal `namespace` via
  `String.indexOf`. **No gate was added, and § 7's `.d.ts` gate was declined on
  its own terms**: it is a claim about where the construct may legally appear,
  and round 792's law is that a profile with 0 `.d.ts` files cannot falsify such
  a claim. The sound substring filter (`namespace`) was already priced at zero by
  § 7 — tsc's sources are full of `namespace` declarations. Replacing the matcher
  makes no legality claim at all, and is differentially pinned against the
  pattern it replaced (which stays live in the source as the oracle).

### 10.2 The rows, measured the same way § 3 was

`BenchMain <proj> 3 8 rows,rows`, one process per arm, two instrumented draws
each, daemons stopped, box otherwise idle. Three arms from the SAME session and
the same test-class build: **A** = HEAD `213292cb`, **B1** = after (a),
**B2** = after (a)+(b). Round-853 positive control: `UmdExportAsNamespaceKt.class`
is ABSENT from arm A's class dir and present in B1/B2, and B1/B2's copies differ
by md5 — so no arm can be the wrong binary.

| pass | A draws | A mean | B1 draws | B1 mean | B2 draws | B2 mean |
|---|---|---:|---|---:|---|---:|
| `checkCrossFileModuleAugmentationDuplicates` | 49.4, 50.8 | **50.1** | 52.1, 56.3 | **54.2** | 2.7, 5.9 | **4.3** |
| `checkUmdGlobalVsDeclareGlobalConst` | 47.8, 44.8 | **46.3** | 0.7, 0.6 | **0.6** | 0.4, 0.3 | **0.3** |
| **the two together** | | **96.4** | | **54.9** | | **4.7** |
| `checkExportAsNamespaceSelfCycle` (control) | 0.0, 0.0 | 0.0 | 0.0, 0.0 | 0.0 | 0.1, 0.0 | 0.1 |
| `init:collectUmdGlobalsAndModuleFiles` (control) | 0.3, 0.3 | 0.3 | 0.3, 0.2 | 0.2 | 0.3, 0.2 | 0.2 |

Against arm A's own warm wall (median **7,821 ms** over its 8 measured
iterations):

| arm | the two passes | share | saving vs A |
|---|---:|---:|---:|
| A (HEAD) | 96.4 ms | 1.233% | — |
| B1 (dedup) | 54.9 ms | 0.701% | **41.5 ms = 0.531%** |
| B2 (+ hand-written scan) | 4.7 ms | 0.059% | **91.7 ms = 1.173%** |

**§ 4.1's measurement replicates on a different day and a different build**:
96.4 ms here against 98.2 there, with the two passes' individual rows 50.1/46.3
against 48.3/50.0. The *order* of the two swaps between the rounds, which is
inside the 14–19% draw spread § 4.1 reported and means nothing.

**WHICH PASS PAYS, AFTER (a), IS THE PASS-ORDER ANSWER AND IT IS THE OTHER ONE.**
`checkCrossFileModuleAugmentationDuplicates` is init step 73h and
`checkUmdGlobalVsDeclareGlobalConst` is 73j, so B1's memo is FILLED by the
former and SERVED to the latter: the second row collapses to 0.6 ms while the
first is unchanged. § 7 wrote "run the scan once, ~49 ms" without saying which
row would move; it is the later one, and that is worth knowing for anyone
reading a future table.

### 10.3 Is the work MOVED rather than deleted? (round 788)

No, and the produced-vs-consumed argument is available without a new instrument.

* **(a).** The scan is PRODUCED once per file and CONSUMED twice. Before: 78
  productions × 2 = 156. After: 78 productions, 156 consumptions. There is no
  third consumer to move it to — the only other two readers of an
  `export as namespace` pattern use DIFFERENT patterns
  (`checkExportAsNamespaceSelfCycle` has a trailing `;?` inside an outer capture,
  `collectUmdGlobalsAndModuleFiles` uses `\s` for `[ \t]`), and **both rows are
  flat across all three arms** (0.0–0.1 and 0.2–0.3 ms), which is the measured
  form of that argument.
* **(b).** The pattern is no longer executed in the compile path at all; its only
  remaining reference is the test oracle. Its replacement's cost is IN the same
  rows, and those rows read 4.7 ms — so the ~50 ms did not reappear anywhere; the
  4.7 ms residue is the AST work each pass does besides scanning.

### 10.4 Round 793 does not apply here, and the census says so

Removing a section normally removes its probe boundaries, so a row delta
overstates the prize. Not here: **both passes still exist and are still wrapped
in `pass(...)`**, and the boundary census is IDENTICAL in all three arms —
**834 pass-row lines across the two draws** (417 rows × 2) in A, B1 and B2 alike,
with `calls = 1` on each of the four UMD rows in every arm. Nothing is subtracted
from the 91.7 ms.

The per-arm partition also holds: the pass rows sum to **99.71% / 99.68% /
99.65%** of the directly measured `checkerInitNanos` in A / B1 / B2.

### 10.5 The A/B, quoted rather than believed — and it decides nothing

Two `scripts/ab-warm.sh` batches, arm A vs arm B2. Verbatim:

```
VERDICT: NOISE-DOMINATED — the per-pair spread dwarfs the effect. This run decides NOTHING in either direction.
  arm A: n=2 median=7620ms sd=132ms (1.74%)   arm B: n=2 median=7564ms sd=122ms (1.62%)   delta=-57ms (-0.75%)  B wins 1/2
```

```
VERDICT: WIN of 3.0% (B wins 3/3) — outside the +/-1.0% warm band. Warm-only: it is a steady-state COMPUTE claim, not a cold-CLI claim.
  arm A: n=3 median=7852ms sd=122ms (1.55%)   arm B: n=3 median=7614ms sd=141ms (1.85%)   delta=-238ms (-3.03%)
```

**NEITHER IS QUOTABLE, and the second one least of all.** CLAUDE.md's rule is
that a warm run whose printed per-arm sd exceeds ~1% was not measured on a quiet
box and its verdict must be discarded however clean the median looks; **all four
arm sds here are 1.55–1.85%**. Batch 2's `3/3, −3.03%` is 2.6× the effect the
rows measure and is the exact shape round 840(c) warns about — and batch 1, on
the same two binaries, read `1/2, −0.75%` with one pair at **+1.63%**. Two
batches that disagree are the correct outcome for an effect of 1.17% measured by
an instrument whose band is ±1.0% on a box that was not quiet.

**So the primary evidence is the ROWS, which is what the driver itself
recommends** ("decide on an IN-PROCESS counter … deterministic and immune to
load"): a 50 ms row going to 0.3 ms is a 99% change against a 14–19% draw
spread, while the same 91.7 ms is 1.17% of a wall whose draw-to-draw spread is
~10%. The arm-level wall and `checkerInitNanos` totals must NOT be read as the
saving in either direction — arm B1's wall median is *higher* than arm A's, and
arm A's `checkerInitNanos` is 568 ms above arm B2's. Both are draw noise.

### 10.6 What this does NOT show

* **One profile**, `--noEmit`, sequential. A project that actually CONTAINS
  `export as namespace` makes these passes emit rather than scan-and-return; the
  scan is then still cheaper, but the residue is no longer near zero.
* **No cold A/B was taken.** Cold, the two passes were 0.38% (§ 4.1), so the
  saving is a fraction of that and is below the cold band by construction. This
  is a warm-regime lever, exactly as (WARM.7) was written.
* **n=2 instrumented draws per arm.** Sufficient here only because the effect is
  ~99% of the rows it acts on; it would not resolve a 10% row change.
* **The 4.7 ms residue was not sub-partitioned.** It is presumed to be the two
  passes' AST walks, but nothing measured that.

---

## 11. § (WARM.9) — `init:buildFileLocalTypeMaps` PRICED WARM: 85.6 ms = 1.09%, and § 7's projection was 1.54× too high

*Round 861, appended for the same reason § 10 was: a priced candidate is only
readable next to its price. § 7 wrote that 47.1% of 3.56% is "~1.68% — above the
warm band", flagged it as an arithmetic projection rather than a measurement,
and told the next round to replace it with one. This is that measurement, and it
lands at **1.09%**, i.e. essentially where round 829's cold price left it. **The
item stays CLOSED.** What is new is not the verdict but the two ways the
projection went wrong, both of which are general.*

### 11.1 What was BUILT — the `fltm` tier, and why it arms TWO probes

`--fltmCensus` (`FltmCensus.kt`, round 829) had never been run inside a warm
process, for the reason round 859 gave about `FrontEnd` and round 851 gave about
the largest spine handler: **the instrument existed and had no tier name.**
`BenchMain` now has `fltm`.

It is the only tier that arms two probes — the census **and** `PassTiming`'s
`rows` — and the pairing is load-bearing, not a convenience. The census prices a
SUB-POPULATION of a pass whose own row it does not measure; taken from two
rebuilds, prize-over-row would be a cross-draw ratio against a row whose warm
draw spread § 3 recorded as **41%**, the widest in its top twelve. Paired, every
ratio below is **within-rebuild**, and those ratios turn out to be four to six
times tighter than either of their operands.

`BenchFltmTierTest` pins it. Its discrimination problem is one
`BenchFrontEndTierTest` does not have and is worth recording: `FrontEnd`'s
recording entry points **self-gate** (`if (mode != ON) return`), so a fixture
that records through them and finds its values in the report has proved the arm.
`FltmCensus`'s do **not** — every hook in `Checker` is written
`if (FltmCensus.on) FltmCensus.noteX(…)`, i.e. **the guard is at the CALL SITE**,
and a fixture calling `noteX` directly records with the tier armed and with the
arm deleted alike (round 807's blind-pin mechanism). The fixture therefore
reproduces the call-site idiom verbatim, guard included. **Ablated** (the
`FltmCensus.on = true` line deleted from `tierBegin`, on a clean tree after the
harness was committed — round 789): 2 of the 5 pins redden, and they are the two
that name that arm; the pass-rows pin stays green because it names the OTHER
probe the tier arms, which is the correct per-arm attribution.

### 11.2 Method

One binary, one profile (`build/bench/tsc-project-637d5746`, 78 files,
46 errors, 9,977,097 chars), `--noEmit`, this box. Round-851 order: build →
suite → `cost_gate.py` → `huge_methods.py` → classpath through
`scripts/lib/dep-classpath.sh` → **then** `./gradlew --stop` + a
bracket-pattern kotlin-daemon kill → then the first sample, 9.8 GB free, box
untouched while running (round 774). Round-853 positive control: the test class
dir must hold `BenchFltmTierTest.class`, a class that did not exist before this
round — 651 main classes, 708 test classes.

Two processes, **tier order rotated** (`rows,fltm,rows,fltm` and
`fltm,rows,fltm,rows`), two draws of each tier per process = **4 census draws
and 8 row draws**. All 8 instrumented rebuilds answered 78 files / 46 errors;
`BenchMain` aborts if one disagrees with its measured loop and none did.
Process walls: P1 median 7,715.9 ms, P2 median 8,041.5 ms.

### 11.3 The pass row, warm — and the first-draw effect the rotation exposes

| process | tier | draw 1 | draw 2 |
|---|---|---:|---:|
| P1 | `rows` | **335.3** | 256.3 |
| P1 | `fltm` | 242.1 | 227.7 |
| P2 | `fltm` | **270.1** | 218.2 |
| P2 | `rows` | 244.9 | 233.4 |

n=8: mean **253.5 ms**, median 243.5, min 218.2, max 335.3 — a **48% spread**,
worse than § 3's 41%. But **the maximum of each tier, in each process, is that
tier's FIRST draw (2/2)**, which is round 846's law (the probe's own cost warms
up on its first instrumented rebuild) showing up in a pass row rather than in a
wall. Dropping each process's first instrumented rebuild leaves
242.1 / 256.3 / 227.7 / 244.9 / 218.2 / 233.4 — mean **237.1 ms**, spread
**16.0%**. Rotating the tier order is what makes that separable; a fixed order
charges the effect to whichever tier is first.

**The census costs the row nothing measurable.** `rows` draws mean 267.5 ms,
`fltm` draws (which carry 12,738 extra timestamp pairs, ~1.2 ms at the warm
~92 ns boundary) mean 239.5 ms — the census arm is *lower*, i.e. the difference
is draw noise several times the probe's own arithmetic cost.

### 11.4 The census, warm — and the counts replicate round 829 exactly

Bit-identical across all four draws, and identical to round 829's COLD census on
every population count: **12,738** direct resolves, **4,161** entries stored,
**1,499** distinct entries ever read, **2,662** never read, **1,263** of those
asked again anyway, **8,577 (67.3%)** storing nothing at all, **6,008 (47.1%)**
never read AND never asked again, 13,172 distinct symbols touched. Only the read
site moved (`calls` 16,043 → **16,183**, misses 278,355 → **280,408**), which is
32 rounds of checker change and is not a regime effect. **A census whose
population counts reproduce across 32 rounds and two JIT regimes is the strongest
evidence available that it measures a property of the program.**

The nanos, per draw:

| draw | direct-resolve wall | FULL deletable | share of wall | `decl` resolves | `decl` deletable | `typealias` | `var` |
|---|---:|---:|---:|---:|---:|---:|---:|
| P1 #1 | 222.9 | 93.3 | 41.8% | 216.2 | 87.1 | 5.9 | 0.7 |
| P1 #3 | 219.5 | 87.9 | 40.0% | 213.5 | 82.5 | 5.3 | 0.6 |
| P2 #0 | 249.2 | 98.3 | 39.4% | 242.6 | 92.4 | 5.7 | 0.7 |
| P2 #2 | 210.1 | 85.5 | 40.7% | 204.4 | 80.3 | 5.0 | 0.6 |
| **mean** | **225.4** | **91.25** | **40.5%** | **219.2** | **85.6** | **5.5** | **0.65** |

Absolute spreads are 14–18%; **the SHARE's spread is 5.9%** — round 854's law
(quote the instrument-internal ratio, not the absolute) reproducing on a third
instrument.

### 11.5 THE PRIZE, measured

The `typealias` slice is **not** deletable and § 7 already said why: those
resolutions exist to make `deepInstantiationBailed` trip, and the bail is
observable only while `getTypeOfSymbol` runs, so not resolving them **deletes** a
TS2589/TS2615 rather than deferring it. The claimable population is the `decl`
branch's deletable set.

Within-rebuild ratios (the reason the tier arms both probes):

| ratio | P1 #1 | P1 #3 | P2 #0 | P2 #2 | mean |
|---|---:|---:|---:|---:|---:|
| direct-resolve wall / pass row | 92.1% | 96.4% | 92.3% | 96.3% | **94.3%** |
| `decl` deletable / pass row | 36.0% | 36.2% | 34.2% | 36.8% | **35.8%** |
| `decl` deletable / that process's warm wall | 1.129% | 1.069% | 1.149% | 0.999% | **1.086%** |

> **THE WARM DELETABLE PRIZE IS 85.6 ms = 1.09% of a warm rebuild**
> (per-draw range 80.3–92.4 ms, **1.00–1.15%**), against § 7's projected
> **1.68%**.

Warm-vs-cold for the sub-population, which is the question § 7 actually posed:
round 829 measured the `decl` branch at 563–705 ms cold and its deletable slice
at 199–252 ms; warm they are 204–243 and 80.3–92.4, i.e. **2.8–2.9× and
2.5–2.7×**. The pass row warms 2.57× (§ 3). **The deletable sub-population does
not warm differently from the pass it lives in** — which is the whole reason the
warm regime does not turn round 829's negative into a positive.

### 11.6 WHY the projection overshot by 1.54×, in three named deflations

Any future round tempted to project a cold population share onto a warm row
should read this as the general form.

| deflation | factor |
|---|---:|
| **47.1% is a COUNT share of the resolves; the ms share of the direct-resolve wall is 40.5%** | ×0.860 |
| **the direct-resolve wall is only 94.3% of the pass ROW** (the rest is the 78-file loop, the flags tests, the map writes, the bail save/restore) | ×0.943 |
| **6.0% of the deletable ms is the `typealias` DETECTOR and is not deletable** | ×0.940 |
| product | **×0.762** |

1.68% × 0.762 = **1.28%**; the remaining gap to 1.086% is the row's own
cross-round drift (3.56% of the wall in round 859, 3.01–3.22% here), which
CLAUDE.md forbids reading as a change in either direction.

### 11.7 THE DECISION — priced negative, and the reason is not the 1.09%

Stated before anything was built, as the round required: **at 1.09% this is a
priced negative, and it would still be one at 1.5%,** because the 85.6 ms is an
**upper bound whose deduction this census is structurally unable to measure.**

**The census's MOVE test is keyed on SYMBOLS; the pass's cost is not
symbol-level.** Its own report says so in one line that round 829 printed and
nobody read this way: `getTypeOfSymbol` entries during the pass are **14,580**
against **12,738** direct ones, so the 12,738 resolutions make just **1,842**
nested symbol asks between them and reach only **434** distinct symbols they did
not start from. Their 225 ms therefore lives almost entirely in
`getTypeFromTypeNode` / member resolution / type interning — **none of which is
symbol-keyed, and none of which `askedLater` can see.** "Never asked again"
consequently means *never re-asked at the one granularity that carries almost
none of the work*: the census can prove a SYMBOL is not re-resolved, and cannot
prove the TYPE-level work behind it is not re-done by the next asker. Round
788's law is unanswered for this pass, in the direction that makes the prize
smaller.

Two supporting readings, both free from the same data:

* **The deletable resolutions are the CHEAP ones**, exactly as CLAUDE.md's
  round-758/759 law predicts when predicate and cost share a cause:
  **15.2 µs** each against **19.9 µs** for the rest.
* **No instrument in this repo could defend the change if it were made.** The
  wall A/B band is ±1.0% warm and the effect is 1.09% (round 860's 1.17% produced
  two batches that disagreed). The ROWS defended round 860 because a 50 ms row
  went to 0.3 ms against a 14–19% draw spread; here the pass row would fall
  253 → ~168 ms, **34% against a 16–48% draw spread**, and — because a lazy
  rewrite MOVES cost to whichever pass asks first — the row is not even the right
  denominator. The right one is `checkerInitNanos`, whose draw spread across
  these 8 rebuilds is **11.0%**, i.e. **8.7× the effect.**

And round 829's three structural objections are untouched by the warm price and
are not re-derived here: the blast radius (`getTypeOfIdentifier` is the map's
only reader and performs **296,591** lookups per compile, 94.6% of them misses,
so a lazy path is a program-wide name-resolution change), the program-ORDER
hazard of the round-754/776/778 class (invisible in every output diff), and the
fact that a lazy read site must reproduce the per-file keying, the
`any`/`errorType` filter and the bail save/restore **per key**.

**Residue, stated so it is not re-derived a third time:** ~85 ms (1.09% warm,
0.8–1.0% cold) sits in the `decl` branch and is recoverable *in principle* by a
design that resolves a file-level function/class/interface/enum/import-alias
symbol only when `getTypeOfIdentifier` asks for it. Reviving it requires, first,
an instrument that can answer the TYPE-level move question — the symbol-keyed
one provably cannot — and second, a replay ablation (record the deletable
`file|name` keys in one rebuild, skip exactly those in the next, and check the
output is byte-identical), because only that can separate deletion from
relocation. Neither is a round; together they are.

### 11.8 What this does NOT show

* **One profile**, `--noEmit`, sequential, n=4 census draws / n=8 row draws.
* **No ablation was run.** The 85.6 ms is the census's own upper bound; § 11.7
  argues it is loose and does not measure by how much.
* **Nothing was optimized and nothing under `commonMain` changed** — the only
  code this round added is a `commonTest` tier and its pin.
* **The `decl` branch was not sub-partitioned** by symbol kind (function vs
  class vs interface vs enum vs import alias), so nothing here says whether the
  85.6 ms is concentrated in one of them.

---

## 12. § (WARM.8) — the post-checker tails are ONE FUNCTION: `cpcRequireOnlyOrphans`, 130.4 ms = 1.72% warm

*Round 861's second deliverable, taken after § 11 was written up and committed.
§ 5 measured the post-checker tails at 143.2 ms = 1.90% of a warm rebuild with
the worst warm-up ratio of any region (1.27×) and **no probe below them**, and
§ 7 called it "an unmeasured region that ranks above every candidate the last
four warm rounds produced". It is now measured, and it is not a region at all.*

> **HEADLINE. 97.6% of the post-checker tails is a single function —
> `cpcRequireOnlyOrphans`, a corpus-fixture orphan detector — at **130.4 ms =
> 1.72% of a warm rebuild**, with a draw spread of **4.1%**, the tightest number
> in this document. Everything else after the checker sums to ~3.1 ms.** And it
> has exactly ONE consumer, which under `--noEmit` reads an empty map.

### 12.1 What was built

Two levels of `FrontEnd` constants, each a set of blocks that **abut** from
their parent's own `t()` to its `close()`, so the residue is a **partition
check** rather than an unattributed remainder — and both residues read **0 ms**
in every draw.

`PostCheckerPartitionTest` pins the placement, and it drives a real multi-file
`ProjectCompiler` build rather than asserting on the probe object, because the
invariant is *where the boundaries are*: a dropped `close` takes a block's
`calls` to zero and dumps its time into the residue, a misplaced one silently
re-attributes work between blocks, and **neither is visible in any output, in
`cost_gate.py`, or in the corpus** — the probe is OFF in production. It carries
the behaviour-free negative control the `frontend` tier has always depended on
and which nothing asserted for `POST`.

### 12.2 The table (`BenchMain <proj> 3 8 frontend,frontend`, 2 processes × 2 draws)

Process walls: 7,708.2 and 7,494.9 ms median.

| block | draw 1 | draw 2 | draw 3 | draw 4 | mean | % warm wall |
|---|---:|---:|---:|---:|---:|---:|
| **post-checker (POST)** | 133.5 | 132.5 | 136.8 | 131.4 | **133.6** | **1.76%** |
|  post-check diagnostic filters | 1.88 | 1.86 | — | — | ~1.9 | 0.02% |
|  `collectCrossFileNamespaceExports` | 0.87 | 0.82 | — | — | ~0.8 | 0.01% |
|  emit prep + transform/emit call | 0.16 | 0.21 | — | — | ~0.2 | 0.00% |
|  **output assembly + sorting** | 130.6 | 129.6 | 133.7 | 128.4 | **130.6** | **1.72%** |
|    `hasCycle` + companion-`.d.ts` deps | 0.007 | 0.011 | — | — | ~0.01 | 0.00% |
|    `topologicalSort` | 0.159 | 0.171 | — | — | ~0.17 | 0.00% |
|    **`cpcRequireOnlyOrphans`** | **130.4** | **129.4** | **133.5** | **128.1** | **130.4** | **1.72%** |
|    output selection + echo order | 0.033 | 0.028 | — | — | ~0.03 | 0.00% |
| residue (both levels) | 0 | 0 | 0 | 0 | **0** | |

`cpcRequireOnlyOrphans` is **97.6% of `POST` in every one of the four draws**
(97.6 / 97.6 / 97.6 / 97.5), and its own spread is **4.1%** — far tighter than
the 10.5% wall spread, because it is one deterministic tree walk rather than a
JIT-sensitive aggregate.

§ 5's 143.2 ms / 1.90% reproduces here as 133.6 ms / 1.76%, on a different day
and a different build; the difference is inside the cross-round rule.

### 12.3 The prior that was WRONG, by 800×

Before measuring, the obvious suspect was `topologicalSort` — 78 densely
barrel-connected files, a sort called twice, with `hasCycle` above it. It reads
**0.17 ms**, and `hasCycle` + the dep-map construction reads **0.01 ms**. The
cost is none of the graph machinery; it is a walk added for a corpus fixture.
The whole point of the second level was to stop that prior from becoming a
sentence in a document.

### 12.4 What `cpcRequireOnlyOrphans` is, and why it costs this

It implements tsc's `moduleResolutionWithRequire` behaviour: a `.ts` input
reached ONLY by a bare untyped `require('./x')` is not a program file, so it is
never emitted. Detecting that means walking every statement of every program
file looking for `require` call expressions and namespace-internal
`import = require` declarations. Its gate is
`parsed.hasExplicitFilenames && tsFileNames.size > 1` — which a real project of
78 files passes, so **a whole-program AST walk runs on every multi-file compile
to answer a question about a two-file corpus fixture.**

### 12.5 (WARM.8)(c) — the candidate, priced and pre-verified. **130.4 ms = 1.72% warm.** RECOMMENDED.

**It has exactly one consumer** (`grep` gives four hits: the declaration, the
call, the `return`, and one use):

```kotlin
val jsOutputs = sortedTsFiles.filter { it !in requireOnlyOrphans }.mapNotNull { jsOutputMap[it] }
```

and under `--noEmit` `jsOutputMap` is **empty by construction** — round 738's
gate makes `cpcTransformAndEmit` iterate `if (options.skipEmitOutputs)
emptyList()`, and that loop is the map's only writer. So `mapNotNull` yields the
empty list **whatever the filter contains**, and computing the filter is
provably dead work in check-only mode.

* **The change is `if (options.skipEmitOutputs) emptySet() else cpcRequireOnlyOrphans(…)`.**
* **Round 788 is answered by construction, not by a census**: nothing else reads
  the set, and no other caller resolves the symbols it touches — it is a pure
  syntactic scan whose result is discarded.
* **Round 793 needs no subtraction**: the probe boundaries stay where they are;
  the block simply reads ~0.
* **What must not break**: the corpus fixtures this exists for
  (`moduleResolutionWithRequire`, `importInsideModule`) run through the EMIT
  path, where `skipEmitOutputs` is false and the function still runs — so the
  gate is invisible to them, which is exactly the property the implementing
  round must pin (a fixture asserting the orphan is still dropped WITH emit, and
  a check-only control).
* **Honest caveat, and it bounds the claim**: this is a **check-only** lever. In
  emit mode the function is load-bearing and the saving is zero, so it does not
  move the CI `bench-3way.sh` ratio (`ARCHITECTURE-RETHINK` § 0.2). It does move
  `--noEmit`, the daemon, and every number this whole arc has produced.
* **Whether the walk itself can be made cheap** — a substring pre-filter for
  `require` over the file text, or hoisting the scan into the crawl that already
  reads every file — is a second, larger question this round did not price.

### 12.6 What this does NOT show

* **One profile**, `--noEmit`, sequential, 4 draws in 2 processes.
* **The sub-blocks below 2 ms were measured in ONE process only** (2 draws);
  they are reported to show where the cost is *not*, and none of them could
  matter at 0.02%.
* **`cpcRequireOnlyOrphans` was not sub-partitioned**, so nothing here says
  which of its scans costs the 130 ms.
* **No A/B and no change was made**; (WARM.8)(c) is a priced candidate, not a
  result.
