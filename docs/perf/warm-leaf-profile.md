# (WARM.15) The first LEAF-LEVEL warm profile of this compiler — round 868

## 0. HEADLINE — what this instrument can and cannot conclude

**READ THIS BEFORE READING ANY TABLE BELOW. The tables are a CANDIDATE LIST.
They are not prices, and no number in them may be quoted as a saving.**

Every other instrument this arc owns — `--passTiming`, `FrontEnd`, `CtaSections`,
`CpaSections`, `CcetSections`, `cost_gate.py` — is a PASS or SECTION probe: it can
only find cost that somebody thought to bracket. That is why the four wins of
rounds 860–864 were all *wrongly-shaped work inside an already-named region*, and
why a DIFFUSE cost (boxing, `hashCode`, map probing, allocation, a megamorphic
call site) spread thinly across many regions is structurally invisible to all of
them. This profile is the complement: it finds cost nobody bracketed, and pays
for that by being unable to price anything it finds.

The reason is measured, in this repo, twice:

- **Round 623**: a leaf showing **5.3% of JFR samples** (`computeLineStarts`) was
  eliminated, and the change measured **−0.3% — neutral**. Counted-loop safepoint
  bias inflates tight-loop self samples, and a saving in a parallel worker does
  not move a serial-dominated wall.
- **Round 801**: **an allocation count is not a cost** — removing 367,189 `String`
  allocations from a hot function measured exactly **0 ms**.

So the protocol this round follows, and the one a next agent must follow:

> **a leaf is a candidate; a candidate becomes a finding only after an
> INDEPENDENT instrument (a section probe, a counter census, or round 759's
> amplification) prices it.**

One further caveat that this round discovered the hard way and that applies to
every table here: **leaf (frame-0) attribution is NOT stable across processes.**
`HashMap.getNode` reads 9.66% of samples in one process and 3.70% in another, on
the same binary and the same project, because C2 inlined it into its callers in
the second. Everything below is therefore reported by **nearest non-stdlib
OWNER** — stdlib work is charged to the frame that asked for it — which
replicates to within a few tenths of a percent across the two processes.

## 1. Method

- **Warm, not cold.** `BenchMain <proj> 3 20` — three warm-up rebuilds and twenty
  measured ones, in ONE JVM, over the compiler profile (`build/bench/tsc-project-*`,
  78 files, 46 errors, `--noEmit`). The JFR window is `delay=60s,duration=90s`:
  three warm-ups cost ~26 + 12 + 9 s, so 60 s lands several rebuilds INTO the
  measured loop and 150 s is still inside it (20 × ~7 s). Chosen by arithmetic
  against the known warm figure and confirmed against the recording's own span.
  The regime matters: `--serve` and the AOT artifact ship a warm process, and
  rounds 845–867 all measure there.
- `-XX:StartFlightRecording=settings=profile`, `-XX:FlightRecorderOptions=stackdepth=1024`.
- **Two independent processes**, so a share that does not replicate is discarded.
  Median rebuild 7,814 / 7,717 ms; 8,026 and 8,010 samples on the compile thread
  (`xtsc-deep-stack`) out of 8,301 / 8,309 total.
- Harness: `scripts/round868-warm-leaf.sh` (`setup` | `jfr <n>` | `tier <name>`),
  in round 851's order — every gradle-invoking step BEFORE the daemon stop, class
  dirs positively controlled in between (round 853).

**A TRAP THAT SILENTLY RUINED THE FIRST PASS OF THIS ROUND, AND THAT
`scripts/aggregate_jfr.py` STILL WALKS INTO BY DEFAULT: `jfr print` TRUNCATES
EVERY STACK TO ITS TOP 5 FRAMES.** Nothing says so — the printed stack simply
ends in `...` — so `aggregate_jfr.py`'s "INCLUSIVE time by method" table is, on a
default invocation, the inclusive time *within five frames of the leaf*. The
first pass here read `checkSpine` at 15.6% inclusive and spent ten minutes trying
to reconcile that with this arc's "the spine is 74% of the compile". Pass
`--stack-depth 512` (the deepest compile-thread stack here is 171 frames), and
check `max(len(stack))` before believing any inclusive number. Round 863's note
mentions truncation in passing; this is what it costs.

Second, smaller trap: the recording contains samples from the JFR recorder
thread and from `DefaultDispatcher-worker-*` (the crawl). Filter to the compile
thread or ~3% of the profile is the instrument watching itself.

## 2. The shape of the warm compile, by allocation family

Share of compile-thread samples whose LEAF is in each family (run 1 / run 2):

| family | run 1 | run 2 |
| --- | --- | --- |
| own code (`Checker`, `Parser`, `Binder`, `FlowGraphBuilder`, …) | 59.0% | 58.5% |
| `HashMap` / `HashSet` / `LinkedHashMap` / `LinkedHashSet` | **26.8%** | **25.9%** |
| `String` / `StringBuilder` / `StringsKt` | 6.2% | 6.6% |
| `ArrayList` / `ArrayDeque` | 4.0% | 4.1% |
| `Intrinsics.areEqual` | 2.6% | 3.4% |
| other stdlib | 1.4% | 1.4% |
| `java.util.regex` | **0.0%** | **0.0%** |

Two readings, both replicating:

- **A quarter of this compiler's warm samples are inside a hash map or set.** That
  is a statement about SHAPE, not a lever — the map traffic is spread over
  hundreds of call sites and most of it is the checker legitimately looking
  something up. It is recorded because it bounds what any single map-shaped fix
  can be worth.
- **The whole-program regex class is closed and stays closed.** Rounds 860/862/863
  removed the three known offenders; a warm leaf profile now finds `java.util.regex`
  at **0.0%**, i.e. not one sample in 16,036. Round 863's "the class is exhausted
  on this profile" is confirmed by an instrument that was not built to look for it.

## 3. The ranked table — self time by nearest non-stdlib OWNER

Share of compile-thread samples; run 1 / run 2. **Column 4 maps each row onto
what this arc has already attributed** — and a row that is already-attributed
checking work is NOT a finding.

| # | owner | run 1 | run 2 | already attributed? |
| ---: | --- | ---: | ---: | --- |
| 1 | `Checker.computeExportedFnDeclsThroughStars` | 2.79% | 2.50% | **NEW** — inside `getCalleeType`, never named below it |
| 2 | `Checker.cpaSpineLeave` | 1.71% | 1.52% | yes — round 847 warm per-handler table, #3 |
| 3 | `Checker.computeExportedVarDeclThroughStars` | 1.56% | 1.31% | **NEW** — same family as #1 |
| 4 | `MapsKt__MapsKt.toMutableMap` | 1.52% | 1.56% | partly — the spine's per-scope frame copies (SPINE.1) |
| 5 | `NodeWalkKt.forEachChild` | 1.40% | 1.22% | yes — round 803 split it; it is the walk itself |
| 6 | `Checker.computeTypeParamInfo` | 1.35% | 0.97% | **NEW** — memoized, so this is the MISS population |
| 7 | `Checker.ciaMutualFnDecls$resolve` | 1.13% | 1.24% | **NEW** — circular-inference implicit-any |
| 8 | `FlowGraphBuilder.recordFlow` | 1.12% | 1.56% | yes — round 864 (FLOW_BIND) |
| 9 | `Checker.resolveBarrelStarTarget` | 1.10% | 0.99% | **NEW** — same family as #1 |
| 10 | `Checker.spineWalkFile` | 1.06% | 1.14% | yes — the spine walk |
| 11 | `Checker.spineOsPushCopy` | 0.92% | 0.85% | partly — SPINE.1 frame bookkeeping |
| 12 | `Checker.getUnionType` | 0.86% | 0.97% | yes — type construction |
| 13 | `Checker.ctaFnBodyFrame` | 0.86% | 0.97% | partly — SPINE.1 |
| 14 | `Checker.lexLevelHasName` | 0.74% | 0.46% | yes — INV.4(c)(ii) hybrid scope query |
| 15 | `Checker.narrowTypeFromFlowCore` | 0.72% | 0.79% | yes — round 735 priced the narrowing tail |
| 16 | `Checker.ctaSpineEnter` | 0.72% | 0.57% | yes — round 847 #4 |
| 17 | `Checker.spineEnterNode` | 0.71% | 0.59% | yes — the dispatch spine (WARM.13/14) |
| 18 | `FlowGraphBuilder.scanReassignedEntriesFast` | 0.67% | 0.60% | yes — round 862 rewrote it |
| 19 | `Checker.getTypeOfIdentifier` | 0.66% | 0.59% | yes |
| 20 | `Checker.spinePdPushCopy` | 0.65% | 0.84% | partly — SPINE.1 |

Two rows the file's own history predicted and that did NOT appear: `computeLineStarts`
(round 623's refuted lever — **not in the top 200**) and `LinkedHashMap.afterNodeInsertion`
(round 483's ~5% — **ONE sample in 16,036**, i.e. that sweep held).

## 4. The candidate list

Discarding everything in column 4 marked "yes" — a checker doing its job in a
region this arc has already partitioned is not a finding — three candidates
survive:

- **(C1) the `export *` barrel search** — rows 1 + 3 + 9, **5.45% / 4.80%** of
  compile-thread samples as SELF, **7.81% / 7.60% INCLUSIVE**, entered from
  `getCalleeType` (3.5%), `importedTopLevelVarAnnotationType` (2.1%),
  `checkCircularInferenceImplicitAny` (1.3%) and `computeImportedSymbolGeneral`
  (0.9%). Wrongly-shaped by inspection: four mutually recursive walks that
  re-derive four immutable facts about a parsed file on EVERY visit.
- **(C2) per-scope whole-map COPIES** — row 4 plus rows 11/13/20 and
  `Checker$EpochMap.<init>`: `putMapEntries` is 5.4% of samples INCLUSIVE, and its
  callers are `spineOsPushCopy` / `ctaFnBodyFrame` / `spineArgListOverlay` /
  `spinePdPushCopy` / `ctaSpineEnter`. The REGIONS are attributed (round 847's
  warm per-handler table); the MECHANISM — that their cost is dominated by copying
  a whole `HashMap` per scope frame — is not, and no section probe can say it.
  A fix is a data-structure change (an undo-log or persistent map), i.e. large.
- **(C3) `ArrayList.clear` on the memoized ancestor-walk buffers** — 1.62% / 1.49%
  as a LEAF, spread over ~15 `spine*Status` / `spine*Reached` classifiers, each
  clearing a shared ascent buffer per memo miss. Diffuse and small, and squarely
  in round 623's safepoint-bias class (a counted loop): **priceable only by
  amplification, never by this table.**

(C1) was taken. (C2) and (C3) are recorded for the queue.

## 5. (C1) PRICED — the independent instrument, and the answer

The candidate was priced with a counter+span census (`FrontEnd.STAR`, armed by
`BenchMain`'s `frontend` tier), NOT with this profile. The bracket is
re-entrancy-counted, so a walk of any depth costs one timestamp pair and the
nested levels cost none:

```
star census: walks 8754 (672 ms), visits 290117 (33/walk),
             scan width 25291521 (2889/walk); answered 6298, null 2456 (28%)
```

**672 ms on a 7,460 ms warm rebuild = 9.0%.** Independent of the profile, and
agreeing with it: the profile's 7.6–7.8% inclusive over ~7.5 s is 570–585 ms,
within 15% of the census — two instruments, two mechanisms, one answer.

The population is the finding: **25.3 million top-level statements scanned to
answer 8,754 questions**, and 28% of the walks answer nothing at all after
visiting 33 files each.

## 6. What was built, and why the row comparison is CONTROLLED

`StarExportIndex` (commonMain, `buildStarExportIndex`) computes per file, once:
its exported top-level functions grouped by name, its exported variables
(first-wins), its exported interface names, and its descendable re-export edges
with their targets already resolved. Each field is a transcription of the
predicate it replaces — same modifier gate, same first-wins rule, same source
order, same skipped shapes — so the walks descend the same edges in the same
order and return the same declarations.

That is what makes the before/after row a valid comparison under round 793's law
(a removed section also removes its boundaries): **the change moves no boundary
and no population.**

| | before | after |
| --- | ---: | ---: |
| outermost walks | 8,754 | 8,754 |
| file visits | 290,117 | 290,117 |
| answered / null | 6,298 / 2,456 | 6,298 / 2,456 |
| **scan width** | **25,291,521** | **638,464** |
| **`FrontEnd.STAR`** | **672 ms** | **91 ms** |

Whole-rebuild corroboration, `ab-warm.sh`, two batches of two rotated pairs:
**−426 ms (−5.58%)** and **−304 ms (−4.15%)**, B wins **4/4**, every pair
sign-consistent. The per-arm sd is 1.2–1.9% on n = 2, which is above the
protocol's quiet-box threshold — so **the A/B is corroboration and the
controlled row is the number** (round 840(c): the second batch is what separates
drift from effect, and here both batches agree in sign and band).

Equivalence: 8-profile grid with BOTH arms rebuilt, **added = 0 / removed = 0 on
all eight**; suite 14,120 / 0 / 3; `cost_gate.py` +0.00% on all 20 counters.

## 7. Pins and ablation

`StarExportIndexTest` — 8 pins, on a parsed file rather than through a compile,
because a wrong gate surfaces (if at all) as an unrelated diagnostic in a file no
fixture contains. Seven single-mistake ablations, each reverted before the next
(`scripts/round868-ablate.sh`, one arm at a time per round 807, on a committed
tree per round 789):

| arm | the mistake | pin that reddened |
| --- | --- | --- |
| A1 | export gate dropped from the function index | `a non-exported function is absent from the index` |
| A2 | variables last-wins instead of first-wins | `the variable index is first-wins over statement and declaration order` |
| A3 | `export * as ns from` admitted as an edge | `only bare star and named re-exports are descendable edges` |
| A4 | edge list reversed | `re-export edges keep source order` (+ the edge-set pin) |
| A5 | export gate dropped from the interface set | `only exported interfaces are in the interface set` |
| A6 | only the last overload of a name kept | `exported function overloads are grouped by name in statement order` |
| A7 | binding-pattern declarations admitted | `negative control - unexported and pattern-named variables are absent` |

Seven arms, seven discriminated, each with a uniquely-its-own failure. **One pin
has no ablation and is recorded as such rather than claimed**: `an unresolvable
specifier contributes no edge` cannot be broken at this seam, because
`ReExport.target` is non-null and a null target does not compile — the invariant
is type-enforced and the pin is a guard against a future signature change.

A3's first form was a compile error (`clause` is `Node?` once the guard goes),
and the driver reported it as `ran 0, failed 0` — indistinguishable from "the
mistake changed nothing" (round 808). It was re-cut to a *compiling* mistake.
Any ablation driver must check its build succeeded before recording a zero; this
one prints the per-arm `git diff --shortstat` and the test count for exactly that
reason (rounds 855/856).

## 8. What did NOT work / was not done

- **The first JFR pass was thrown away** — see § 1's `--stack-depth` trap. Its
  inclusive tables were nonsense and its caller attribution was capped at five
  frames.
- **No per-line attribution is quoted.** `javap`/JFR line numbers in `Checker.kt`
  wrap modulo 65,536 and the file is 178,013 lines, so a frame can need +65,536
  OR +131,072, and inlined stdlib appears at SYNTHETIC lines past EOF (the single
  largest line bucket in the raw data was one of those). The line data pointed at
  the `filterIsInstance` branch and that is how the fix was shaped, but the
  numbers are not reportable and are not reported.
- **(C2) and (C3) were not priced.** (C2) needs a data-structure change to test at
  all; (C3) is 1.5% of samples in a counted loop, i.e. exactly the shape round 623
  refuted, so it needs amplification (round 759 / round 867) before anyone builds
  anything.
- **`ObjectAllocationSample` was not reported.** `settings=profile` records it, but
  round 801 already measured that an allocation count is not a cost in this
  codebase (367,189 `String` allocations removed = 0 ms), so an allocation table
  would have added a column nobody may act on.

---

# (WARM.16) The copy candidate, PRICED — round 869 (2026-08-09)

**Nothing above this line is rewritten. § 0-§ 8 are round 868's record; this
section is what its candidate (C2) turned into, and what its candidate (C3) did
NOT.**

## 9. The census — which had to come before any timing (round 801)

`FrontEnd`'s copy counters (`--frontEnd`, the `copyamp*` tiers), compiler
profile, warm. **Deterministic: identical to the unit on all 24 instrumented
rebuilds taken this round.**

| family | pushes | entries copied | mean | max | writes |
| --- | ---: | ---: | ---: | ---: | ---: |
| `EpochMap(localTypes)` | 28,828 | 471,726 | 16.3 | 272 | 44,320 |
| `EpochSet(paramBindings)` | 35,015 | 39,522 | 1.1 | 20 | 7,969 |
| **`spineOs` annotation frames** | **34,155** | **1,841,284** | **53.9** | 143 | **17,600** |
| **`spinePd` annotation frames** | **21,674** | **1,247,050** | **57.5** | 143 | **16,854** |
| `CtaFrame.varTypes` (`toMutableMap`) | 30,433 | 1,145,523 | 37.6 | 100 | 2,564 |
| `CtaFrame` localTypes+declNodes+shadowed | 9,525 | 1,089,527 | 114.3 | 406 | *n/a* |
| **TOTAL** | **159,630** | **5,834,632** | | | **89,307** |

**5.83 M entries copied to serve 89 k writes — 1.5%.** That is round 801's
produced-versus-consumed test in the setting where it decides something: a copy
costs O(size), an undo log costs O(writes), and the two are exactly equivalent
for a stack that is strictly LIFO, never removes a key, and whose readers
neither mutate nor retain the map.

**The `n/a` is deliberate and is round 849's law.** The `CtaFrame` local family's
writes go through a plain `HashMap` that the cta sandwich installs into
`currentLocalTypes`, so the `EpochMap` hook cannot see them: the counter reads 0
and that 0 is UN-INSTRUMENTED, not measured. Reporting it as a finding would be
the round-849 mistake (a census keyed on a boundary the caller short-circuits
read "nothing ever reads a derived lib type back" where the truth is 133.9 reads
per production). Every other row's `writes` is a real count.

## 10. The price — amplification, because a boundary would exceed the quantity

`copyAmp = r` performs `r` EXTRA whole-map copies at every censused site and
takes **no timestamp pair anywhere**; the answer is read off the WHOLE-REBUILD
wall, so `wall(r) = base + r·C` and two values of `r` cancel `base`
algebraically. At a mean copy of 16-114 entries one warm probe boundary
(97-202 ns, round 850) would be the measurement. Falsification is ARITHMETIC:
`ampSink` must equal `r ×` the censused entry count of the ARMED families, and
it did on every one of the 24 rebuilds (e.g. `49,413,344 = 16 × 3,088,334`).

Design: ABBA-rotated inside each process (`r16,r8,r0,r0,r8,r16`), 3 warm-up
rebuilds + 5 measured ones before the first tier, because the FIRST instrumented
rebuild in a process is the slowest draw — a 5-for-5 law in this arc, and here
worth up to **15%** (batch 2 read `r=0` at 8,082 ms in cycle 1 and 6,872 ms in
cycle 2). An un-rotated ladder puts that bias entirely on whichever arm runs
first and silently flattens the slope.

| arm | draws | mean ms | pair | slope |
| --- | ---: | ---: | --- | ---: |
| **whole family** (batch 3) | | | | |
| `r = 0` | 2 | 7,497.0 | | |
| `r = 8` | 2 | 8,971.3 | 0→8 | 184.3 ms |
| `r = 16` | 2 | 10,782.3 | 8→16 | 226.4 ms |
| | | | least squares | **205.3 ms** |
| **`spineOs` + `spinePd` only** (batches 4+5) | | | | |
| `r = 0` | 4 | 7,052.0 | | |
| `r = 8` | 4 | 8,255.9 | 0→8 | 150.5 ms |
| `r = 16` | 4 | 9,126.4 | 8→16 | 108.8 ms |
| | | | least squares | **129.7 ms** |

Against a probe-free warm rebuild of 7,337 ms (batch 3) / ~7,447 ms (batches
4+5):

- the WHOLE per-scope copy family is **205 ms = 2.80%** of a warm rebuild;
- the two ANNOTATION families alone are **129.7 ms = 1.74%** [1.46-2.02%].

The two independent `r`-pairs bracket the point estimate at ±16%, which is
looser than round 867's ±1% and is said out loud rather than smoothed: what the
interval supports is "above the 1% decision floor at both ends", not a third
decimal. The interval is also **conservative in a known direction** — an
amplified copy dies in eden immediately, where a production copy is retained for
its frame's lifetime, so the amplifier under-counts promotion and GC.

Corroboration from the other instrument: round 868's profile put
`putMapEntries` at 5.4% of compile-thread samples INCLUSIVE, i.e. ~400 ms of a
7.5 s rebuild against the amplifier's 205 ms. Same order, factor 2 apart, in the
direction the caveat above predicts.

## 11. Why only two of the six families were taken

**Not "the two biggest".** The classification is by whether copy semantics can
be replaced *provably*, and the discriminator is what the map IS:

| family | replaceable? | why |
| --- | --- | --- |
| `spineOs` / `spinePd` annotation frames | **yes** | strict LIFO; no key is ever removed; all three consumers (`spread2698CheckOperand`, `rest2700Check`, the `pddu*` pair) only LOOK UP, synchronously, inside one spine node's handler |
| `CtaFrame.varTypes` | not yet | deliberately SHARED at some frames and copied at others (`if (node is Block) copy else share`), so an undo log needs a per-frame replay flag; also a `LinkedHashMap` for no reason (round 483) — see § 13 |
| `CtaFrame` localTypes/declNodes/shadowed, `EpochMap`, `EpochSet` | not here | these ARE `currentLocalTypes`, where a wrong scope does not crash — it silently resolves a name to an outer binding (the `applyBodyLocalShadowing` FP class), and where the write census is un-instrumented (§ 9) |

The two taken families are the ones where the FP hazard is **structurally
absent** rather than argued away.

## 12. What landed, and the controlled row

`AnnScopeStack` (commonMain, `internal`, its own file so every rule is pinned
directly rather than through a compile) keeps ONE live map plus an undo log:
`push` records a mark, `put` appends the key's pre-write value (`null` = absent,
unambiguous because a `TypeNode` is never null), `pop` replays this frame's
slice **in REVERSE** — which is what makes a repeated write to one key correct
with no per-frame "already shadowed" set, since the last restore applied to a
key is its first record.

The row is CONTROLLED in round 793's sense — the change moves no boundary and no
population:

| | before | after |
| --- | ---: | ---: |
| `spineOs` pushes / writes | 34,155 / 17,600 | 34,155 / 17,600 |
| `spinePd` pushes / writes | 21,674 / 16,854 | 21,674 / 16,854 |
| **entries copied by the two** | **3,088,334** | **0** |
| undo-log records | 0 | 34,454 |
| entries copied, all six families | 5,834,632 | 2,746,298 |

**89.6× less work over an identical population.** And the amplifier is the
change's own falsifier: re-run on the AFTER binary, `copyampos` reads a slope of
**+5.5 ms over `r = 0..16`** (0→8 `+14.7`, 8→16 `−3.7`) with `ampSink = 0` — it
finds nothing left to amplify at those sites, so the copies are gone rather than
merely uncounted.

Equivalence: 8-profile grid with BOTH arms rebuilt from source and diffed in both
directions, **added = 0 / removed = 0 on all eight** (8 distinct captures;
compiler digest `59d930db…`, the recipe CLAUDE.md records). Suite
**14,139 / 0 / 3**. `cost_gate.py` **+0.00% on all 20 counters**.
`huge_methods.py --fail-over 0` exit 0.

### Pins and ablation

`AnnScopeStackTest`, 11 pins, all over strings and ints — never a `TypeNode`,
whose power-assert rendering is its whole subtree. Nine single-mistake
ablations, each reverted before the next (`scripts/round869-ablate.sh`, one arm
per invocation, on a committed tree):

| arm | the mistake | pins reddened |
| --- | --- | ---: |
| A1 | `pop` drops the frame without restoring | 6 |
| A2 | `pop` replays its slice FORWARD | 1 |
| A3 | `put` records the NEW value | 6 |
| A4 | `put` persists with no frame open | 1 |
| A5 | `push` records mark 0 | 4 |
| A6 | `pop` leaves a key the frame INTRODUCED | 4 |
| A7 | `topOwner` answers the OUTERMOST frame | 1 |
| A8 | `push` starts the scope EMPTY | 4 |
| A9 | `reset` keeps the entries (cross-FILE leak) | 1 |

Every arm reddens, and **every one of the 11 pins is reddened by at least one
arm** — A8 and A9 exist for exactly that reason: after the first seven, `an
inner scope sees the outer scope's entries` and `reset drops every scope and
every entry` had no failure of their own, and round 868's rule is that such a
pin is RECORDED as un-ablated rather than counted as coverage. Cutting an arm
for each was cheaper than the disclaimer, and A9's defect — an annotation
surviving a file boundary — is the one a corpus baseline would find hardest to
attribute.

Eight of the nine red SETS are distinct. **A1 and A3 are not: they are the same
defect in two spellings** (not restoring, and restoring the value that is
already there), so no pin can separate them and this is reported as one
discriminated defect with two spellings rather than as two arms of coverage.

## 13. What did NOT work, and what was left unpriced

**The whole-rebuild `ab-warm.sh` A/B is DISCARDED, not quoted as corroboration.**
Batch 1: **−330 ms (−4.42%), B wins 2/2**, both pairs sign-consistent, with the
winner in the disadvantaged position once. Batch 2: **−77 ms (−1.04%), 1/2**,
and the driver's own verdict line reads `NOISE-DOMINATED — the per-pair spread
dwarfs the effect`. Per-arm sd was 1.40% (batch 1, arm B) and 3.47% (batch 2,
arm A), i.e. above the 1.0% quiet-box threshold in both. This is round 840(c)
and round 858's reference case exactly — **a sign-consistent paired batch is not
a result** — and had only batch 1 been run, this section would be claiming
−4.4% for a change whose controlled instrument says 1.74%.

**(C3), `ArrayList.clear` on the ~15 ancestor-walk buffers, is LEFT UNPRICED —
deliberately, and the leaf share is not acted on.** It is round 623's exact bias
class (a counted loop, whose self-samples safepoint bias inflates; round 623
eliminated a 5.3%-of-samples leaf and measured −0.3%). One structural fact was
established for the next round and is worth the reader's time: there are **47**
`ArrayList<Node>` ascent buffers and **97** `chain.clear()` call sites, i.e. the
idiom is a PAIR per classifier — a leading clear and a trailing one — and
because every filling path ends with the trailing clear, **the leading clear
always runs on an already-empty list, where `ArrayList.clear`'s null-out loop
does not execute at all.** So the leaf's samples are spread over twice as many
call sites as there is work, which is a second reason (on top of safepoint bias)
that its 1.5% over-reads. Pricing it needs a census of ELEMENTS cleared —
invocations that miss the memo × mean chain length — or round 759's
amplification; a share from the table above is not a price and must not be used
as one.

**Also not taken, and now priced rather than guessed:** `CtaFrame.varTypes` is
**1,145,523 entries for 2,564 writes — 0.22%, the most wasteful ratio of the
six** — and it is built with `toMutableMap()`, i.e. a `LinkedHashMap`, although
a grep for `.keys/.values/.entries/.forEach/.map/.iterator/.sorted` over all 211
`varTypes` references finds **zero** iteration anywhere, so nothing consumes its
order. Two separate improvements sit there (the map flavour, and the copy), and
the copy needs a per-frame replay flag because some frames share the map on
purpose. Queued as (WARM.17).

---

# (WARM.17) The profile RE-TAKEN, and its top new candidate priced — round 870 (2026-08-09)

**Nothing above this line is rewritten. § 0–§ 8 are round 868's record and
§ 9–§ 13 are round 869's; this section is the DIFF against them, which is the
deliverable.**

## 14. Why re-take it, and the instrument's own validity check

Rounds 868 and 869 removed ~711 ms from the warm rebuild — 581 ms by
`StarExportIndex` and 129.7 ms by `AnnScopeStack` — on a wall of ~7.5 s. That is
~10% of the artifact, concentrated in two specific places, so **every share in
§ 3's table is a share of a denominator that no longer exists** and the ranking
could have reordered.

Same recipe (`scripts/round870-warm-leaf.sh`, cloned from round 868's), same
window, two processes. The aggregation is now a committed instrument,
`scripts/leaf_owner_profile.py`, which carries round 868's three traps as code:
it REFUSES a `jfr print` dump whose deepest stack is 5 frames (the
`--stack-depth` trap), filters to the compile thread, and charges stdlib leaves
to the nearest non-stdlib OWNER.

**Round 868's dumps were re-aggregated with the SAME script**, so the two tables
below differ in nothing but the binary. (It reproduces § 3's published numbers
row for row, with one deliberate difference: `kotlin.*` is charged through to
its caller here, so round 868's `MapsKt__MapsKt.toMutableMap` row is distributed
into `ctaFnBodyFrame` / `ctaSpineEnter` rather than standing alone.)

| | round 868 | round 870 |
| --- | ---: | ---: |
| median warm rebuild | 7,814 / 7,717 ms | **7,089 / 7,048 ms** |
| compile-thread samples | 8,026 / 8,010 | 8,002 / 7,990 |
| max stack depth | 140 / 171 | 210 / 220 |
| `checkSpine` INCLUSIVE | 73.66% / 72.83% | 75.28% / 74.34% |

The `checkSpine` row is the sanity check the round-868 trap demands: this arc's
independently-known figure is ~74%, and both runs land on it.

### 14.1 THE VALIDITY CHECK — did the two fixed families disappear?

Yes, and this is the table that says whether anything above may be believed.
Shares are of compile-thread samples, run 1 / run 2, by nearest non-stdlib
owner (SELF):

| owner | 868 | 870 |
| --- | ---: | ---: |
| `computeExportedFnDeclsThroughStars` | 2.79% / 2.50% | **0.24% / 0.18%** |
| `computeExportedVarDeclThroughStars` | 1.56% / 1.31% | **0.26% / 0.16%** |
| `resolveBarrelStarTarget` | 1.20% / 0.99% | **0.00% / 0.00%** |
| `spineOsPushCopy` | 0.92% / 0.85% | **0.00% / 0.00%** |
| `spinePdPushCopy` | 0.65% / 0.84% | **0.10% / 0.06%** |
| `buildStarExportIndex` (round 868's replacement) | — | 0.04% / 0.00% |
| `AnnScopeStack` (round 869's replacement) | — | 0.49% / 0.35% |

**Both replacements appear exactly where they should, and cost a fraction of
what they replaced.** Summed by family and converted to ms per rebuild:

| family | 868 | 870 | Δ |
| --- | ---: | ---: | ---: |
| the `export *` barrel search (incl. its index) | 471.2 ms | 61.0 ms | **−410.2** |
| the two annotation scope frames (incl. `AnnScopeStack`) | 153.0 ms | 39.8 ms | **−113.3** |

The second is a genuine cross-instrument agreement: round 869 priced the two
annotation families by AMPLIFICATION at **129.7 ms** and this profile, which
knows nothing about that measurement, reads **113.3 ms** — 13% apart. The first
under-reads the census's 581 ms by 29%, in the expected direction: a leaf
profile charges a removed subtree's callees (`moduleNamedExportsOf`, symbol
resolution) to themselves, where the census's span contained them.

### 14.2 THE DENOMINATOR TRAP — a share is not comparable across rounds

The JFR window is a fixed 90 s of steady state, so the sample count is a
constant (~8,000 in both rounds) and **a share is a share of WALL TIME, not of a
rebuild.** A per-rebuild cost that did not change therefore READS HIGHER after
the compile got faster, by exactly 7,766/7,068 = **1.099**. Every cross-round
comparison below is in **ms per rebuild** (share × that round's median) for that
reason, and a row that "rose" by less than 10% did not rise at all.

## 15. The ranked table — round 868 versus round 870, in ms per rebuild

Share run 1 / run 2 for each round, then ms/rebuild and the delta. Column 6 maps
the row onto what this arc has already attributed; **a row that is
already-attributed checking work is NOT a finding.**

| # | owner | 868 | 870 | ms 868 | ms 870 | Δ | already attributed? |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | `Checker.cpaSpineLeave` | 1.78/1.62% | 1.64/1.63% | 132.2 | 115.4 | −16.8 | yes — round 847 warm handler #3 |
| 2 | `Checker.ctaFnBodyFrame` | 1.17/1.39% | 1.67/1.38% | 99.3 | 107.8 | +8.6 | partly — SPINE.1, and it now absorbs the `toMutableMap` leaf |
| 3 | `Checker.ctaSpineEnter` | 1.22/1.17% | 1.80/1.25% | 93.0 | 107.8 | +14.9 | yes — round 847 #4, same absorption |
| 4 | **`Checker.computeTypeParamInfo`** | 1.35/0.97% | 1.47/1.35% | 90.1 | **99.9** | +9.8 | **NEW — flagged unpriced by round 868; TAKEN this round** |
| 5 | `FlowGraphBuilder.recordFlow` | 1.13/1.57% | 1.22/1.35% | 105.1 | 91.1 | −14.0 | yes — round 864 (FLOW_BIND) |
| 6 | `NodeWalkKt.forEachChild` | 1.40/1.22% | 1.29/1.21% | 101.7 | 88.4 | −13.3 | yes — round 803 split it; it is the walk |
| 7 | `Checker.getUnionType` | 0.92/1.06% | 1.04/1.15% | 77.0 | 77.4 | +0.4 | yes — type construction |
| 8 | `Checker.spineWalkFile` | 1.06/1.14% | 0.96/0.93% | 85.2 | 66.7 | −18.5 | yes — the spine walk |
| 9 | `Checker.lookupPerFileForNode` | 0.61/0.57% | 0.99/0.83% | 46.0 | 64.1 | +18.1 | yes — INV.3(c), ~2 M calls/compile |
| 10 | `Checker.ciaMutualFnDecls$resolve` | 1.13/1.24% | 1.40/0.38% | 92.0 | 62.7 | −29.3 | NEW but **DISCARDED — does not replicate** (3.7× between processes) |
| 11 | `Checker.spineArgListOverlay` | 0.76/0.64% | 0.94/0.80% | 54.2 | 61.4 | +7.2 | partly — round 868's (C2), a per-scope copy |
| 12 | `Checker.narrowTypeFromFlowCore` | 0.72/0.79% | 0.89/0.81% | 58.6 | 60.1 | +1.5 | yes — round 735 priced the narrowing tail |
| 13 | `Checker.spineEnterNode` | 0.71/0.59% | 0.76/0.83% | 50.4 | 56.1 | +5.8 | yes — the dispatch spine (WARM.13/14) |
| 14 | `Checker.getTypeOfIdentifier` | 0.66/0.59% | 0.66/0.84% | 48.4 | 53.0 | +4.6 | yes |
| 15 | `Checker.lexLevelHasName` | 0.74/0.46% | 0.62/0.81% | 46.5 | 50.8 | +4.4 | yes — INV.4(c)(ii) |
| 16 | `Checker.objectLiteralSatisfiesAugmentationMergedInterface` | 0.52/0.41% | 0.65/0.66% | 36.3 | 46.4 | +10.1 | NEW — **0.66%, below the 1% floor** |
| 17 | `Checker.getTypeOfSymbol` | 0.49/0.81% | 0.72/0.56% | 50.4 | 45.5 | −4.9 | yes |
| 18 | `Checker.getTypeFromTypeNodeCore` | 0.51/0.75% | 0.55/0.71% | 48.9 | 44.6 | −4.3 | yes |
| 19 | `FlowGraphBuilder.scanReassignedEntriesFast` | 0.69/0.61% | 0.59/0.64% | 50.4 | 43.3 | −7.0 | yes — round 862 rewrote it |
| 20 | `Checker$Relation.get` | 0.64/0.21% | 0.64/0.59% | 32.9 | 43.3 | +10.4 | yes — relation cache probe |

Six of round 868's top-20 rows are gone entirely (the four star walks and the
two copy pushers). **Nothing NEW rose into their place**: the rows that moved up
are the ones that were already there, at an unchanged ms, against a smaller
denominator — which is § 14.2's trap and the single most likely way to misread
this table.

The allocation-family shape is unchanged and its two readings still hold:
`HashMap`/`HashSet` **26.6% / 27.3%** of compile-thread samples (868: 26.8/25.9),
own code 57.0/58.2, `String` 6.3/5.7, `ArrayList` 4.6/4.3, and
**`java.util.regex` 0.04% / 0.13%** — 3 and 10 samples in 16,000, i.e. round
863's "the class is exhausted on this profile" survives two rounds of change.

## 16. The candidate — and what it cost to establish it is one

After discarding every already-attributed row, one candidate is above the 1%
decision floor and replicates: **`computeTypeParamInfo`, 1.47% / 1.35%**. It is
the MISS body of `getTypeParamInfo`'s memo, and round 868's own table already
carried it as "**NEW** — memoized, so this is the MISS population" without
pricing it.

`ciaMutualFnDecls$resolve` is DISCARDED although it is bigger in run 1 than the
candidate: 1.40% against 0.38% is a 3.7× spread on one binary, and round 868's
rule is that a share which does not replicate across processes is not a share.
`objectLiteralSatisfiesAugmentationMergedInterface` replicates cleanly
(0.65/0.66%) and is simply too small.

### 16.1 The independent price — a counter+span census, not the profile

`FrontEnd.TPI` (armed by `--frontEnd`), one timestamp pair per MISS, with a
census beside it. **Deterministic to the unit across two processes**
(82,316,551 ns and 82,273,289 ns, 0.05% apart; every count identical):

```
tpi census: calls 28663, misses 1077 (3%, 82 ms); file probes 45906,
            ns symbols scanned 2992718 (2778/miss), of which module 2184;
            answered 880, null 197
```

**82 ms on a ~7,050 ms warm rebuild = 1.16%** — agreeing with the profile's
99.9 ms to within 18%, two instruments, two mechanisms.

The population is the finding, and it is not the one the row's size suggests.
The memo works: **96.2% of the 28,663 questions are answered from it.** The cost
is entirely in what a MISS does, and specifically in its second of three
lookups — "is this name exported by some namespace anywhere in the program?",
answered by iterating EVERY entry of EVERY file's `locals` and testing
`SymbolFlags.Module` on each:

> **2,992,718 symbols iterated to reach 2,184 module-flagged ones — 99.93% of
> the scan re-decides a question that is a property of the binder tables and not
> of the name being asked.**

Which symbols are namespaces cannot depend on the name. Only their `exports`
probe can.

## 17. What landed, and the CONTROLLED row

`buildModuleSymbolScanIndex` (commonMain, `internal`, its own file so every rule
is pinned directly rather than through a compile) returns the program's module
symbols in the scan's exact order — `binderResults` order, then each file's
`locals` insertion order, **duplicates preserved**, because one `Symbol`
instance really is reachable from two files' `locals` (that is what
`mergeModuleAugmentations` creates) and the scan probed it once per table.

The `exports` table is deliberately NOT indexed: it is a `var` the checker's own
merging writes to, so the probe stays live and in place.

**When it may be frozen.** The module-symbol SET is settled by init pass 1b
(`mergeModuleAugmentations`, the only place the checker adds an entry to a
file's `locals` or sets a `Module` bit on one), which runs in the setup block
long before any checking pass — and `getTypeParamInfo`'s memo has been freezing
whole ANSWERS over those same tables since round 481, so caching the LIST is
strictly weaker than an assumption this function has relied on for 389 rounds.
It is nonetheless built lazily, at the first miss, so its disagreement window is
contained in that of the memo above it.

The row is CONTROLLED in round 793's sense — the change moves no boundary and no
population:

| | before | after |
| --- | ---: | ---: |
| `getTypeParamInfo` calls | 28,663 | 28,663 |
| memo misses | 1,077 | 1,077 |
| loop-1 file probes | 45,906 | 45,906 |
| module `exports` probes | 2,184 | 2,184 |
| answered / null | 880 / 197 | 880 / 197 |
| **ns symbols scanned** | **2,992,718** | **2,277** |
| **`FrontEnd.TPI`** | **82 ms** | **15 ms** |

**1,314× less scanning over an identical population; 67 ms captured = 0.95% of
a warm rebuild.**

That is said plainly rather than rounded up: the CANDIDATE priced at 1.16%,
above the decision floor, which is what justified building anything; the
CAPTURE is 0.95%, and the 15 ms residue is loop 1's 45,906 per-file hash probes
plus the live `exports` probes — **0.21%, below the floor, and deliberately not
taken.** No whole-rebuild A/B is quoted: rounds 869 § 13 and 858 both measured
that a two-batch `ab-warm.sh` on an effect of this size cannot separate drift
from result, and the controlled row needs no help.

## 18. Pins and ablation

`ModuleSymbolScanIndexTest`, 11 pins, all over strings, ints and identity —
never a `Symbol` or an AST node, whose power-assert rendering is its whole
subtree. Nine single-mistake ablations, one arm per invocation, each reverted
before the next, on a committed tree (`scripts/round870-ablate.sh` +
`round870_ablate_apply.py`; every arm dry-run first for a real diff and a clean
revert, per rounds 855/856):

| arm | the mistake | pins reddened |
| --- | --- | ---: |
| A1 | membership gate reads `ValueModule` only | 1 |
| A2 | membership gate reads `NamespaceModule` only | 8 |
| A3 | no membership gate — every local symbol admitted | 3 |
| A4 | file order reversed | 2 |
| A5 | within-file order sorted by name | 1 |
| A6 | duplicates dropped by Symbol INSTANCE | 1 |
| A7 | duplicates dropped by NAME | 2 |
| A8 | only the first module symbol of each file kept | 1 |
| A9 | the index carries a COPY of each symbol | 1 |

A1 and A2 exist as a PAIR on purpose: CLAUDE.md records that
`SymbolFlags.Module` is the UNION of `ValueModule` and `NamespaceModule`, that
the binder gives them to different syntax, and that a rule written with either
half alone compiles and silently loses the other. A single "the gate is wrong"
arm would have been satisfied by either.

**A6 initially left every pin green, and the honest reading is that the fixture
set could not express it, not that the guard is redundant** — the binder gives
each file its own symbol, so nothing built through it puts one instance into two
`locals` tables, although `mergeModuleAugmentations` does exactly that in a real
program. Following round 869's rule (a pin or an arm without a uniquely-its-own
failure is recorded, not counted), an 11th pin was cut that builds that state by
hand, and A6 now reddens it and nothing else.

**Two things are reported rather than claimed.** `an empty program has an empty
index` is UN-ABLATED and structurally un-ablatable: every arm maps an empty
input to an empty output, so no mistake at this seam can break it — the same
shape as round 868's `an unresolvable specifier contributes no edge`. And **A5
and A8 share a red set**: they are different defects (a re-sort, and a truncation
to one entry per file) that the same within-file-order pin catches, so no pin
separates them and they are one discriminated position, not two.

Every other arm has a distinct red set, and **10 of the 11 pins are reddened by
at least one arm.**

## 19. Gates

- Suite **14,150 / 0 / 3** over all four modules (`xml.etree`), = 14,139 + the
  11 new pins, exactly.
- `cost_gate.py` **+0.00% on all 20 counters**.
- `huge_methods.py --fail-over 0` — `OVER THE LIMIT: 0`, exit 0.
- **8-profile grid, BOTH arms rebuilt from source and diffed in both
  directions: added = 0 / removed = 0 on all eight.** Eight distinct captures;
  compiler digest `59d930db…`, the recipe CLAUDE.md records. Round 853's
  positive control both ways: the AFTER dir holds `ModuleSymbolScanIndexKt` and
  the BEFORE dir does not (665 vs 664 classes), so a stale or mis-pointed dir
  cannot make the arms agree by being the same dir twice.

## 20. What was NOT done

- **`lookupPerFileForNode` (64 ms) and `objectLiteralSatisfiesAugmentation-
  MergedInterface` (46 ms)** are the next NEW-ish rows and both are below the
  1% floor on their own. Recorded, not taken.
- **`ciaMutualFnDecls$resolve` is unpriced and its share is not actionable** —
  1.40% versus 0.38% between two processes of the same binary. If a later round
  wants it, the profile cannot be the instrument; a census can.
- **(C3), `ArrayList.clear` on the ~47 ancestor-walk buffers, is STILL
  unpriced** and stays queued exactly as round 869 left it (§ 13): it is round
  623's bias class, its samples are spread over twice as many call sites as
  there is work, and it needs an ELEMENTS-cleared census or amplification.
- **`CtaFrame.varTypes`** — the worst produced-versus-consumed ratio of round
  869's six copy families (1,145,523 entries for 2,564 writes) and still a
  `LinkedHashMap` with zero order consumers. Its owners (`ctaFnBodyFrame`,
  `ctaSpineEnter`) are rows 2 and 3 of § 15 precisely because they absorb its
  `toMutableMap` leaf. Not taken here: it is deliberately SHARED at some frames
  and copied at others, so an undo log needs a per-frame replay flag, and it is
  `currentLocalTypes` territory — the false-positive class round 869 declined
  for the same reason.

---

# (WARM.21) The profile re-taken a THIRD time — the vein's last coherent candidate, and the arc's close — round 874 (2026-08-09)

**Nothing above this line is rewritten. § 0–§ 8 are round 868's record, § 9–§ 13
round 869's and § 14–§ 20 round 870's; this section is the DIFF against them,
which is the deliverable, plus the closing statement for the instrument.**

## 21. Validity first — do the last four fixes appear as fallen rows?

Same recipe (`scripts/round874-warm-leaf.sh`, cloned from round 870's), same
window, two processes, same committed aggregator (`scripts/leaf_owner_profile.py`).
Round 868's and round 870's dumps are re-aggregated with that same script, so
the three tables differ in nothing but the binary.

| | round 868 | round 870 | round 874 |
| --- | ---: | ---: | ---: |
| median warm rebuild | 7,814 / 7,717 ms | 7,089 / 7,048 ms | **6,591 / 6,603 ms** |
| compile-thread samples | 8,026 / 8,010 | 8,002 / 7,990 | 8,182 / 8,168 |
| max stack depth | 140 / 171 | 210 / 220 | 137 / 199 |
| `checkSpine` INCLUSIVE | 73.66% / 72.83% | 75.28% / 74.34% | **74.09% / 74.05%** |

The `checkSpine` row is the sanity check the round-868 `--stack-depth` trap
demands, against this arc's independently known ~74%. Both runs land on it.

**The four fixes, as rows (share run1/run2, and ms per rebuild):**

| owner | 868 | 870 | 874 |
| --- | ---: | ---: | ---: |
| `computeTypeParamInfo` (round 870) | 1.16% / 90.1 ms | 1.41% / 99.9 ms | **0.18% / 11.7 ms** |
| `computeExportedFnDeclsThroughStars` (round 868) | 2.64% / 205.3 ms | 0.00% | 0.00% |
| `computeExportedVarDeclThroughStars` (round 868) | 1.43% / 111.4 ms | 0.00% | 0.00% |
| `resolveBarrelStarTarget` (round 868) | 1.09% / 84.7 ms | 0.00% | 0.01% / 0.8 ms |
| `spineOsPushCopy` (round 869) | 0.89% / 68.8 ms | 0.00% | 0.00% |
| `spinePdPushCopy` (round 869) | 0.74% / 57.6 ms | 0.08% / 5.7 ms | 0.02% / 1.2 ms |

Round 870's fix reads **99.9 → 11.7 ms**, against the 15 ms residue its own
controlled row predicted. Rounds 868's and 869's stay gone.

**Round 871's cross-request parse cache needed a different reading, and it is
the sharpest validity check of the three rounds.** The crawl parses on
`Dispatchers.Default` workers, which this instrument filters out by design — so
the fix is invisible in the compile-thread table. Counted over ALL threads:

| | 868 | 870 | 874 |
| --- | ---: | ---: | ---: |
| samples whose stack contains `Parser` | 251 / 285 | 265 / 282 | **0 / 0** |
| samples containing `ProjectCompiler`/crawl | 268 / 296 | 282 / 301 | 29 / 24 |

**Not one sample in 16,413 is in the parser.** `BenchMain` rebuilds in one
process, so from the second rebuild on the cache serves all 78 files — which is
what round 871 built it for, observed here by an instrument that knows nothing
about it.

## 22. The ranked table — three rounds, in ms per rebuild

Round 870 § 14.2's denominator trap still governs every number: the JFR window
is a fixed 90 s of steady state, so a SHARE is a share of wall time and an
unchanged per-rebuild cost reads HIGHER after the compile gets faster (868 → 874
is a factor 7,766/6,597 = **1.177**). Everything below is ms/rebuild.

| # | owner | 874 r1/r2 | ms 868 | ms 870 | ms 874 | Δ870 | already attributed? |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | `Checker.cpaSpineLeave` | 1.66/1.64% | 132.2 | 115.4 | 108.9 | −6.4 | yes — round 847 warm handler #3 |
| 2 | `Checker.ctaSpineEnter` | 1.44/1.53% | 93.0 | 107.8 | 98.0 | −9.8 | yes — round 847 #4 |
| 3 | `FlowGraphBuilder.recordFlow` | 1.19/1.48% | 105.1 | 91.0 | 88.0 | −3.1 | yes — round 864 |
| 4 | `Checker.ctaFnBodyFrame` | 1.31/1.31% | 99.3 | 107.8 | 86.3 | −21.5 | partly — SPINE.1 / the `varTypes` copy |
| 5 | `NodeWalkKt.forEachChild` | 1.25/1.31% | 101.7 | 88.4 | 84.3 | −4.1 | yes — round 803 split it |
| 6 | `Checker.spineWalkFile` | 1.06/1.00% | 85.2 | 66.7 | 68.2 | +1.5 | yes — the spine walk |
| 7 | `Checker.ciaMutualFnDecls$resolve` | 1.56/0.48% | 92.0 | 62.7 | 67.4 | +4.6 | **DISCARDED AGAIN — 3.25× between processes** |
| 8 | `Checker.getUnionType` | 0.99/0.95% | 77.0 | 77.3 | 64.2 | −13.2 | yes — type construction |
| 9 | `Checker.narrowTypeFromFlowCore` | 0.84/0.91% | 58.6 | 60.1 | 57.7 | −2.4 | yes — round 735 |
| 10 | `Checker.lookupPerFileForNode` | 0.77/0.84% | 46.0 | 64.1 | 53.3 | −10.8 | yes — INV.3(c) |
| 11 | `Checker.spineArgListOverlay` | 0.78/0.83% | 54.2 | 61.4 | 53.3 | −8.2 | partly — round 868's (C2) |
| 12 | `Checker.spineEnterNode` | 0.86/0.75% | 50.4 | 56.1 | 52.9 | −3.3 | yes — the dispatch spine |
| 13 | `Checker.getTypeFromTypeNodeCore` | 0.72/0.71% | 48.9 | 44.6 | 47.2 | +2.6 | yes |
| 14 | `FlowGraphBuilder.scanReassignedEntriesFast` | 0.79/0.64% | 50.4 | 43.3 | 47.2 | +3.9 | yes — round 862 |
| 15 | `Checker.objectLiteralSatisfiesAugmentationMergedInterface` | 0.71/0.70% | 36.3 | 46.4 | 46.4 | −0.0 | NEW-ish — 0.70%, below the floor, UNMOVED in three rounds |
| 16 | `Checker.lexLevelHasName` | 0.73/0.66% | 46.5 | 50.8 | 46.0 | −4.8 | yes — INV.4(c)(ii) |
| 17 | `Checker.getTypeOfSymbol` | 0.72/0.60% | 50.4 | 45.5 | 43.6 | −1.9 | yes |
| 18 | `Checker$Relation.get` | 0.61/0.67% | 32.9 | 43.3 | 42.4 | −0.9 | yes — relation cache probe |
| 19 | `Checker.getTypeOfIdentifier` | 0.60/0.62% | 48.4 | 53.0 | 40.3 | −12.7 | yes |
| 20 | `FlowGraph.<init>` | 0.62/0.56% | 35.8 | 39.3 | 39.1 | −0.2 | yes — the INV.2(b) side table |

**Rows 21–60 are FLAT: every one of them is between 19 and 32 ms (0.3–0.5%).**
The allocation-family shape is unchanged for the third round running —
`HashMap`/`HashSet` **27.3% / 27.0%** of compile-thread samples, own code
56.2/59.0, `String` 6.9/5.8, `ArrayList` 4.4/4.2 — and `java.util.regex` is
**0.01% / 0.05%**, so round 863's "the class is exhausted on this profile"
survives three rounds of change.

Read row by row this table says the vein is finished: nothing NEW is above the
1% floor, `ciaMutualFnDecls$resolve` fails the replication test for the second
round in a row (1.56% against 0.48% on the same binary), and
`objectLiteralSatisfiesAugmentationMergedInterface` has sat at 0.70% and 46 ms
through three rounds.

## 23. …and reading it row by row is what would have missed this round's finding

The row is the wrong unit when the mechanism is spread across many of them.
Round 868's (C1) was already this shape — three rows of 1.1–2.8% that were ONE
barrel search — and it was caught only because the three rows were adjacent in
the ranking and shared a name prefix. So this round aggregated the whole table
by MECHANISM first, and the answer is not close:

| family | ms 868 | ms 870 | ms 874 | 874 r1/r2 |
| --- | ---: | ---: | ---: | ---: |
| **INV.4 reach classifiers** (`spine*Status`/`*Edge`/`*Reached`/`tav*`) | 439.2 | 503.0 | **458.8** | 6.91% / 7.00% |
| scope frame copies (`EpochMap`/`EpochSet`/`AnnScopeStack`/`*PushCopy`/overlays) | 423.8 | 321.3 | 299.8 | 4.53% / 4.55% |
| `cta*` handlers | 259.6 | 292.1 | 264.7 | 4.00% / 4.03% |
| name resolution (`lookupPerFile*`/`globalsForFile`/`NameScope`/`lexLevel*`) | 239.2 | 236.5 | 213.5 | 3.06% / 3.42% |
| flow-graph build | 253.3 | 221.4 | 211.0 | 3.17% / 3.23% |
| `cpa*` handlers | 221.3 | 207.3 | 194.9 | 2.86% / 3.05% |
| type construction | 212.6 | 195.4 | 187.2 | 2.90% / 2.78% |
| module/import resolution | 456.7 | 123.8 | 139.6 | 2.03% / 2.20% |
| relation engine | 146.2 | 136.1 | 136.4 | 2.18% / 1.96% |
| `ccet*` handlers | 138.0 | 132.2 | 125.9 | 1.85% / 1.97% |
| narrowing walk | 91.0 | 123.7 | 121.5 | 1.55% / 2.13% |
| M3 anchor marking (cta/cpa/ccet) | 97.4 | 104.3 | 108.1 | 1.71% / 1.57% |

**The largest family in the warm compile is the INV.4 migration's own reach
machinery — 458.8 ms = 6.95% — and it is spread over 66 owners of which the
biggest is `spineTavStatus` at 0.57%.** That is not checking work: it is the
scaffolding that answers "would the deleted walker have visited this node?", it
is invisible to every section probe (nobody would bracket 66 functions), and it
is precisely what round 732 said a per-kind dispatch table can NOT remove —
"handlers keyed on PARENT edges, FRAME identity and nodeId REGISTRIES cannot be
closed by the node's own kind AND are the expensive ones".

Within it, one sub-family is coherent and above the floor: **the TAV pass**
(INV.4(c)(iv), the migrated `checkTypeUsedAsValue`). Its entry point
`spineTavIdentifier` reads **2.20% / 2.03% INCLUSIVE = 139.6 ms/rebuild**, and
it replicates. Its members read 0.18–0.57% each and are scattered across rows
22, 31, 34 and below, which is why rounds 868 and 870 both walked past it.

## 24. The census — before any timing (round 801)

`FrontEnd`'s TAV counters, compiler profile, warm. **Deterministic: identical to
the unit on every instrumented rebuild of every process this round took.**

```
tav census: calls 381670, unreached 229034 (60%), reached 152636;
            status hops 695014, level hops 798020, level builds 29862;
            chain queries 169371, probes 467085;
            value-hit 144029, INERT 151137 (99% of reached), emitted 0
```

Per warm rebuild, to emit **zero** diagnostics:

- **381,670** identifier dispatches — the pass runs at 44.5% of all nodes;
- **695,014** reach-classifier ancestor hops, 60% of which end in "this node was
  never walked by the deleted checker";
- **798,020** parent hops looking for the nearest scope level, each testing
  seven node classes (`tavIsLevelOwner`);
- **467,085** `HashSet<String>` probes up the level chain.

And the line that decides the round: **151,137 of the 152,636 reached
identifiers — 99.0% — are INERT.** Their name is in no visible level's
`typeOnly`/`nsOnly` set and is not a type keyword, and this pass owns exactly two
diagnostics, TS2693 ("only refers to a type") and TS2708 ("cannot use namespace
as a value"), both of which require one of those. **No reordering of the pass's
own tests could have emitted anything for them.**

`emitted 0` is the same fact from the other side, and it is also round 793's
warning arriving on schedule: the OFF arm's obvious falsifier — "the error count
moves when the pass stops emitting" — is DEAD on the profile the prize is
measured on, because tsc's own sources never use a type as a value. The
falsification had to move to fixtures and to the 8-profile grid's instrument
control (§ 27).

## 25. The price — a CONTROLLED row, because the wall cannot see it

The whole-rebuild wall was tried first and is **discarded, not quoted**: `plain`
against `tavoff`, ABBA-rotated, 4 draws each in one warm process, read
6,766 vs 6,642 ms — a 124 ms median difference with a per-arm sd of **3.0%** and
**2 of 4 pairs** going the wrong way. That is round 840(c)'s reference case
exactly, and on an effect of this size the wall is not an instrument.

So the price is a controlled row: **one timestamp pair per dispatched
identifier**, and the gate returns early INSIDE it, so the boundary count is
identical on both arms and cancels in the difference (round 793's law, round
795's shape — both arms live in ONE binary, selected by `--tavGateOff`).

| draw (two processes, arms rotated) | gate OFF (pre-874) | gate ON |
| --- | ---: | ---: |
| process A, first / last draw of the arm | 336 / **159** ms | 52 / **38** ms |
| process B, first / last draw of the arm | 325 / **156** ms | 43 / **34** ms |

Round 846's first-draw-is-slowest law is visible in all four arms and the late
draws are the ones read. **The difference is 121 ms (process A) and 122 ms
(process B) — 0.8% apart across two processes — = 1.84% of a 6,597 ms warm
rebuild.** It is independent of the boundary's own price, which is why no
estimate of that price appears in the arithmetic. (For scale: the ON arm's whole
row is 34–43 ms over 381,670 pairs, i.e. under 113 ns per pair including the
work, so the remaining pass cost is a rounding error on top of the instrument.)

Corroboration from the other instrument: the leaf profile put
`spineTavIdentifier` at 139.6 ms INCLUSIVE, against the controlled row's 121–122
ms captured plus a residue — two instruments, two mechanisms, 13% apart.

## 26. What landed, and why the superset is the safe shape

`spineTavCandidates` (a `HashSet<String>` per file) is every name that could
possibly produce this pass's two diagnostics ANYWHERE in the file, as a
deliberate SUPERSET. The gate is the pass's first line — ahead of the reach
classifier, which is where 60% of the dispatches were dying — and a name that is
not a candidate returns immediately.

Its soundness is the claim that four sources are exhaustive over
`tavBuildLevel`, and each is a line of that function:

| source | where it comes from | how it is collected |
| --- | --- | --- |
| the file root's `typeOnly` + `nsOnly` | `tavBuildFileRoot`, already eager | at `spineTavSetup`, free |
| `TYPE_ONLY_KEYWORDS` | the static keyword set | at `spineTavSetup`, so the gate is ONE probe not two |
| `tavFnLevel`'s `typeOnly` | exactly TYPE PARAMETER names | at every `TypeParameter` ENTER |
| `tavModuleLevel`'s `typeOnly` + `nsOnly` | exactly the interface / type-alias / namespace names declared directly in a module block | at every `ModuleBlock` ENTER |

Two properties make this defensible rather than merely plausible:

- **Completeness is structural, not positional.** The two incremental sources are
  keyed on the node KIND and collected by the spine, which enters every
  `TypeParameter` and every `ModuleBlock` in the file. A collector keyed on
  syntactic POSITIONS (class methods, function declarations, class expressions,
  …) is the fragile version of this and was rejected for that reason.
- **`tavModuleLevel`'s filters are deliberately NOT reproduced** (`n !in values`,
  `!tavHasValue(parent, n)`, `n !in KNOWN_GLOBALS`, the sub-module value
  survey). Dropping a filter WIDENS the set, and a wider gate can only let more
  identifiers through to an unchanged pass — so the gate cannot go stale if
  those filters change, only if a new SYNTAX starts feeding a level.

**Order.** The set is filled as the walk proceeds, so a contribution must be in
it before any identifier that can see it is visited. It is: every node this pass
REACHES lies inside a body, a parameter, a heritage expression or an expression
subtree (`spineTavEdge`'s arms — a declaration's own NAME is `TAV_STOP`), and
`forEachChild` visits `typeParameters` before parameters and body, and a
`ModuleBlock` before its own statements. Two pins assert exactly that rather than
leaving it as an argument.

**The row is CONTROLLED — the change moves no boundary and no population:**

| | before | after |
| --- | ---: | ---: |
| dispatches | 381,670 | 381,670 |
| reach-classifier hops | 695,014 | 24,058 |
| level hops | 798,020 | 9,773 |
| level builds | 29,862 | 1,435 |
| chain queries / probes | 169,371 / 467,085 | 1,510 / 2,720 |
| gate refused | 0 | **365,784 (95.8%)** |
| emitted | 0 | 0 |
| **`FrontEnd.TAV`** | **156–159 ms** | **34–43 ms** |

## 27. Gates, and the control the grid needed

- Suite **14,220 / 0 failures / 3 skipped** over all four modules (`xml.etree`)
  = round 873's 14,205 + the 15 new pins, exactly.
- `cost_gate.py` **+0.00% on all 20 counters** — which is the expected answer
  and not a vacuous one: this pass touches no counter the gate records, and 46
  errors / 78 files says it really compiled the profile.
- `huge_methods.py --fail-over 0` — 672 classes, **0 over the limit**.
- **8-profile grid, both arms in ONE binary, diffed in both directions:
  `added=0 removed=0` on all eight.** A one-binary grid is stronger than a
  two-class-dir one (a stale dir cannot make the arms agree by being the same
  dir twice), but it needs its own control, because a pass that emits nothing
  would let two do-nothing arms agree. Both arms therefore run with
  `--frontEnd`, and each profile records its census line: the gated arm refuses
  **365,784 / 365,829 / 366,581 / 366,085 / 367,689 / 493,491 / 525,581 /
  562,693** identifiers and the ungated arm refuses **0** on every one. The
  script FAILS if a gated arm refuses under 1,000, or if either arm's reported
  `gateOff` disagrees with its own flag.

## 28. Pins and ablation

`TavCandidateGateTest`, 15 pins, all over diagnostic CODES (never an AST node,
whose power-assert rendering is its whole subtree). Eight single-mistake
ablations, one arm per invocation, each reverted before the next, on a committed
tree, every arm dry-run first for a real diff and a clean revert
(`scripts/round874-ablate.sh` + `round874_ablate_apply.py`):

| arm | the mistake | pins reddened |
| --- | --- | ---: |
| A1 | the file root's `typeOnly` dropped | 4 |
| A2 | the file root's `nsOnly` dropped | 2 |
| A3 | type-parameter names dropped | 4 |
| A4 | a module block's interfaces dropped | 3 |
| A5 | a module block's type aliases dropped | 2 |
| A6 | a module block's nested namespaces dropped | 2 |
| A7 | `TYPE_ONLY_KEYWORDS` not folded in | 2 |
| A8 | the collector never called at all | 8 |

Every arm reddens, **every red set is distinct**, and A8's is exactly the union
of A3–A6's — which is the structural check that the spine hook feeds those four
sources and nothing else. A5 and A6 initially reddened only their own pin
because the equivalence pin's source list did not carry those two shapes; the
list was extended rather than the coincidence being reported as coverage (round
869's rule), and they now redden two apiece.

**Two pins are reported as UN-ABLATED rather than counted**: `negative control -
an ordinary value name emits neither diagnostic` and `a class extending an
interface still emits TS2689`. Every arm at this seam makes the gate NARROWER,
and a narrower gate can only LOSE an emission — it can never invent one, and it
cannot reach `spineTavClassHeritage`, which is a different dispatch. Both pins
therefore guard a direction no mistake in this construction can take, which is
the same shape as round 868's `an unresolvable specifier contributes no edge`.

## 29. THE CLOSING STATEMENT FOR THE LEAF-PROFILE ARC

Three takes, three fixes, **~800 ms removed from a warm rebuild that started at
~7.8 s** — the `export *` barrel search (581 ms), the two annotation scope
frames (130 ms), the `getTypeParamInfo` namespace scan (67 ms) and now the TAV
pass (121 ms), against a wall that is now ~6.6 s.

**What the instrument found, and why nothing else could have.** All four are
DIFFUSE costs: work spread over many call sites, inside regions that were
already attributed, that no pass or section probe would ever have bracketed. The
three earlier ones were single-row candidates; this one was only visible after
aggregating the table by MECHANISM, which is the reading the next agent should
start from rather than re-derive (§ 23, `scripts/round874_compare.py`).

**What it cannot see, stated so it is not rediscovered.** It samples the compile
thread, so the concurrent crawl is invisible (§ 21 had to count over all threads
to see round 871's fix). It attributes leaves, so C2 inlining moves a row by up
to 3.7× between processes and any share that does not replicate is not a share
(`ciaMutualFnDecls$resolve`, twice now). Its shares are shares of WALL TIME, so
a per-rebuild cost that never moved reads higher after every win. And it is not
a price: round 623 eliminated a 5.3%-of-samples leaf for a measured −0.3%, and
every candidate here was priced by a second instrument before anything was
built.

**Is the vein exhausted? By rows, yes.** After this round nothing NEW replicates
above the 1% floor; rows 21–60 are uniformly 19–32 ms; the two next-largest
NEW-ish rows (`objectLiteralSatisfiesAugmentationMergedInterface` at 46 ms and
`lookupPerFileForNode` at 53 ms) have been recorded and declined by two rounds
running; and `ciaMutualFnDecls$resolve` is unpriceable by this instrument.

**By families, no — but what is left is one question, not a list.** The INV.4
reach machinery is still **~338 ms after this round's 121**, spread over 65
classifiers of which the largest is now 0.4%, and no other one of them has the
TAV pass's property of owning a small, name-keyed emission alphabet that a gate
can decide in one probe. Making the rest cheaper is not a candidate-hunting
question any more; it is a design question about the pull-based memoized
ancestor walk itself (a per-node status ARRAY per classifier, or a push-based
status maintained by the walk that already knows the path). **That is the
successor item, and the leaf profile has said everything it can about it.**

The instrument to reach for next is therefore not a fourth re-take of this
table. It is either (a) a census of the reach-classifier family as ONE
population — how many classifiers are consulted per node, how many of those
consultations are memo hits, and what a shared status word would cost — or (b)
`docs/perf/warm-spine-attribution.md`'s per-handler table, which is where the
remaining ~2 s of `cpaSpineLeave`/`ctaSpineEnter`/`ctaFnBodyFrame`/`recordFlow`
lives and which this instrument keeps pointing at without being able to open.

---

## 30. Round 888 — the FOURTH take, and what thirteen rounds did to the table

Round 874 closed the arc at § 29. Round 888 re-opened it for one reason:
CLAUDE.md's own rule that a number is re-measured before a round is spent
inside it (round 755's target had halved while it sat in the queue), and
rounds 876–887 had landed the sequential-bind removal, the balanced worker
partition, the merge forwarding table and the anchor-mark deletion in between.

Recipe IDENTICAL to round 874 (`scripts/round888-warm-leaf.sh`, same window,
same warm-up ladder, two processes), so the only variable is the binary.

### 30.1 Validity

`checkSpine` INCLUSIVE reads **73.25% / 71.90%** against this arc's known ~74%
— the `--stack-depth` trap's sanity check (§ 1). Max observed stack depth
177 / 236 against the 512 cap, so nothing is truncated. 8,184 and 8,089 samples
on `xtsc-deep-stack`; 44 and 37 on other threads.

Medians: **5,918.6 and 5,891.2 ms**, 46 errors / 78 files in both.

### 30.2 By row — still exhausted, and by a wider margin

| # | owner | r1 | r2 | ms 874 | ms 888 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | `Checker.cpaSpineLeave` | 1.86% | 1.59% | 108.9 | **101.9** |
| 2 | `Checker.ctaFnBodyFrame` | 1.39% | 1.71% | 86.3 | 91.5 |
| 3 | `FlowGraphBuilder.recordFlow` | 1.41% | 1.53% | 88.0 | 86.7 |
| 4 | `Checker.ctaSpineEnter` | 1.37% | 1.50% | 98.0 | 84.6 |
| 5 | `Checker.getUnionType` | 1.20% | 1.08% | 64.2 | 67.1 |
| 6 | `NodeWalkKt.forEachChild` | 1.09% | 1.15% | 84.3 | 66.1 |
| 7 | `Checker.spineWalkFile` | 1.19% | 1.03% | 68.2 | 65.3 |
| 8 | `Checker.narrowTypeFromFlowCore` | 1.08% | 0.84% | 57.7 | 56.6 |
| 9 | `Checker.lookupPerFileForNode` | 0.88% | 0.80% | 53.3 | 49.7 |
| 10 | `Checker$Relation.get` | 0.72% | 0.95% | 42.4 | 49.4 |

Nothing NEW is above 1%, and the top row is now **1.7% / ~102 ms**. Rows 21–40
are 26–36 ms. `ciaMutualFnDecls$resolve` fails replication for the THIRD round
running (0.43% / 0.36% here after 1.56% / 0.48% at round 874) — a leaf that has
never replicated across two processes should be treated as absent.

**One row is a trap and is recorded so the next reader does not lose the time
this one did.** `objectLiteralSatisfiesAugmentationMergedInterface` reads
`0.4 ms -> 35.9 ms` and is NOT a regression: C2 inlined the outer method in this
binary, so the OWNER frame moved to its local `$scanIface`, and the two rows sum
to 41.3 against round 874's 46.4. That is round 868's "leaf attribution is not
stable across processes" operating one level up, at the OWNER, ACROSS ROUNDS —
so a large single-row delta must be checked for a key SPLIT (`--grep` on
`scripts/round888_compare.py`) before it is read as a change in cost.

### 30.3 By mechanism family — where the thirteen rounds actually went

`scripts/round888_families.py` makes round 874 § 23's aggregation reproducible
and runs the SAME patterns over both rounds' dumps (its patterns are slightly
wider than § 23's hand-cut ones, which is why its 874 column reads 484.6 where
§ 23 reads 458.8; only the within-script comparison is meaningful).

| family | ms 874 | ms 888 | 888 r1/r2 |
| --- | ---: | ---: | ---: |
| **INV.4 reach classifiers** | 484.6 | **371.1** | 6.56% / 6.01% |
| type construction | 345.8 | 295.7 | 4.99% / 5.03% |
| name resolution | 261.9 | 247.7 | 4.47% / 3.92% |
| spine walk core | 245.3 | 220.3 | 3.69% / 3.77% |
| `cta*` handlers | 264.7 | 216.0 | 3.40% / 3.92% |
| flow-graph build | 214.7 | 207.2 | 3.37% / 3.65% |
| module/import resolution | 184.4 | 192.3 | 3.23% / 3.29% |
| scope frame copies | 192.1 | 183.2 | 3.13% / 3.08% |
| `cpa*` handlers | 194.9 | 143.6 | 2.59% / 2.27% |
| narrowing walk | 148.5 | 142.2 | 2.63% / 2.19% |
| relation engine | 115.4 | 118.3 | 1.94% / 2.06% |
| `ccet*` handlers | 125.9 | 92.5 | 1.54% / 1.59% |

**The reach classifiers are still the largest family and they fell 113.5 ms** —
which is round 874's own TAV gate arriving, predicted at 121 ms. The family is
now 371 ms over **65 owners whose largest is `spineCeStatus` at 21.8 ms
(0.37%)**, i.e. round 875's census verdict stands: every mechanism in it prices
below 1% and only the memo TRANSPOSITION is open, at <= ~0.8%.

`cta*`/`cpa*`/`ccet*` fell 48.7 / 51.3 / 33.4 ms between them, which is where
round 887's anchor-mark deletion lands (round 874's separate "M3 anchor marking"
family was 108.1 ms and no longer exists).

### 30.4 The caveat that governs every number above

Cross-round ms/rebuild is a CANDIDATE RANKING, not a measurement. CLAUDE.md's
own law — the sequential self-compile anchor moved 12.8% across four rounds with
identical code — applies here too, so a family moving by less than ~10% is not
evidence of anything, and the two big leaf families (`hash-probe` 24.42% ->
19.16% leaf, `string-build` 6.29% -> 4.33%) are quoted as observations, not as
wins claimed by this round. Only within-round paired deltas are quotable.

### 30.5 What the profile decided

Nothing new above ~1%, by row or by family — so the round took the already
priced, already argued implementation item instead: **(WARM.13b), the per-kind
ENTER skip mask**, whose prize round 867 fixed at 73.2 ms and whose skeleton it
measured at ~15% of that. Against this round's 5,905 ms denominator that is
**1.24%**. See § 31.

---

## 31. Round 888 — (WARM.13b): the per-kind ENTER skip mask, landed

`dispatch-table.md` § 9.6 left this as "an IMPLEMENTATION item, with one new
number that changes its outlook". This is that implementation. Nothing here
re-derives `s_p` — § 9's two batches settled it at **2.286 ns [2.148–2.512]**
and its own § 9.6 forbids re-deciding it with `--dispatchGated` (that arm
measures `G − R`, one equation in two unknowns).

### 31.1 The shape

`spineEnterNode` reads `spineEnterMask[(node as NodeBase).kindId]` ONCE and puts
one bit test in front of each CLOSED handler's existing `spineXxActive` guard:

```kotlin
val skip = spineEnterMask[(node as NodeBase).kindId]
...
if (skip and (1L shl 12) == 0L && spineFpActive) spineFpEnterNode(node)
```

`&&` short-circuits, so a skipped handler costs the bit test and **not even the
field read**. The six OPEN handlers carry no test at all. Emitted bytecode,
verified with `javap`: **1 `laload`, 40 `land` / `lcmp` / `ifne`**, method
544 -> **964** bytecodes, far under HotSpot's 8,000 `HugeMethodLimit` (round
845: crossing it is a −33.6% WARM cliff).

### 31.2 LEAVE is deliberately not masked

Only **1 of the 13** leave handlers (`spineIrLeaveNode`) is closed, so a leave
mask could skip at most ~1 slot per node = **≤2.7%** of the 32.0 M, and a
per-node array load there costs more than the one bit test saves. Round 867's
`R` is therefore ~97.3% collectable by the enter mask alone.

### 31.3 Soundness is SYNTACTIC, and the table was re-derived, not trusted

A closure is a claim that the handler does nothing observable outside it, and
its only accepted justifications are syntactic (`SpineDispatch`'s own rule).
The table dates from **round 732** and its handlers have been edited in ~150
rounds since; a closure gone stale silently drops a diagnostic, and round 753's
law says a green corpus bounds frequency, not existence.

So `scripts/spine_closure_audit.py` re-derives each handler's top-level kind
gate from today's `Checker.kt` — brace-matched over a comment/string stripper
that PRESERVES LINE LENGTH and treats `'` as a char literal (round 809) — and
checks the declared closure contains it. It recognises five gate forms:
`if (kindId != K) return`, `if (node !is T) return`, `if (node is T …) {`,
`when (kindId) { … }` with a no-op or absent `else`, and one level of
`...Core` delegation; `is Statement` resolves to `STATEMENT_KINDS`.

**Result: 46 handlers, 6 OPEN, 40 audited, ALL clean.** The table has not gone
stale. A `when` whose `else` does work, or an unrecognised top-level statement,
is reported as a PROBLEM rather than passed — the script fails closed.

### 31.4 What the gates said

* **Corpus**: 14,286 / 0 failures / 3 skipped (14,276 + 9 mask pins + the
  re-pointed CtaSections pin of § 31.6).
* **Output grid**, one binary, `--spineMaskOff` as the OFF arm: the compiler
  profile at 46 diagnostics, **added=0 removed=0**. Only that one profile is
  materialised on this box, so this is a claim about ONE profile (round 782) —
  the 13k-baseline corpus is the primary output gate here, not the grid.
* **`cost_gate.py`: +0.00% on all 20 counters**, `spine.nodes 856962` matching
  round 867's amplified population exactly. That is the EXPECTED answer and is
  read as a control that semantics are untouched (round 876), never as a win.
* **`huge_methods.py --fail-over 0`: 0 over the limit**, 690 classes.

### 31.5 The ablation — three arms, one mistake each

Round 807: a combined ablation cannot attribute. `scripts/round888-ablate.sh`
runs one arm per invocation, dry-runs each edit for a real diff, and reverts
before the next; the arm list is an ARRAY, not `"${@:-…}"` (round 855).

| arm | the mistake | red |
| --- | --- | ---: |
| A1 | `spineCaEnterNode` closure loses `BINARY_EXPRESSION` | **1** |
| A2 | `ctaSpineEnter` (OPEN) given a closure | **3** |
| A3 | `spineCtaM3StatementAnchor` loses `EXPRESSION_STATEMENT` | **6** |

**Every arm reddens and every red set is DISJOINT.** A1 is the sharpest and the
one that matters: a closed handler silently stops being consulted at a kind it
acts on, the compiler loses a diagnostic, and the ONLY pin that sees it is
`masked and unmasked agree where the closed handlers actually emit` — the
two-armed ON-vs-OFF comparison. A2 reddens the two structural pins plus the
pre-existing `SpineDispatchProbeTest`. A3 reddens the re-pointed CtaSections
pin plus five of its siblings.

### 31.6 One instrument DID change, and it is not a regression

`spineCtaM3StatementAnchor` is a `CtaSections` probe wrapper, so the mask now
refuses its call BEFORE the probe's `A_GATE` row can count it — and
`CtaSectionProbeTest`'s `level A opens on the handler so the eligibility gate is
a row` failed on exactly that, asserting `invocationsA > stmtKind.sum()` where
the two are now equal at 11.

That premise — "the handler is consulted about EVERY node, so its invocation
count is far larger than the number of statements that anchor" — WAS the wasted
work. The pin is restated across both arms (the mask must strictly reduce
consultations, change no anchor, and the unmasked arm must still show the old
inequality), which is strictly stronger and is what A3 reddens.

**The consequence a next reader needs: a `CtaSections` table taken with the mask
on is not comparable to a pre-888 one** — round 793's law (removing a section
also removes its boundaries) one region over. `CtaSections.mode` is OFF in
production, so nothing the compiler decides is affected.

### 31.7 What is NOT claimed

**No wall-time number.** The prize is 73.2 ms = 1.24% of this round's 5,905 ms
rebuild, minus a skeleton round 867 measured at ~15% of it — i.e. ~62 ms,
**0.9–1.1%**, which is inside `ab-warm.sh`'s ±1.0% band and below what two
batches on this box have settled in recent memory (rounds 840(c), 858, 886).
`dispatch-table.md` § 9.6 said so in advance: a landed table "could not be
confirmed by the harness that would judge it, and would have to be defended on
`cost_gate.py` counters plus this decomposition". It is.

What IS claimed is COUNTED REMOVED WORK, from the amplifier's own census of the
population: **32,006,965 handler consultations per compiler-profile rebuild that
are entered and immediately decline, ~97.3% of them on the enter side**, now
replaced by 856,962 array loads and one register-resident bit test each.

## 32. Round 893 — the FIFTH take, and the first table this arc can hold against a MEASURED wall delta

Round 893's job was not the profile: it was the **cumulative warm A/B of rounds
887–892**, six rounds that each landed counted removed work and each correctly
declined to claim a wall number because each was ~0.5–1% against a box that
settles at ~1%. The profile was taken in the same session, on the same quiet box,
so for the first time a leaf table sits beside an independently measured delta
for the same code.

### 32.1 The wall delta (the thing the table is held against)

Two BUILDS, one JVM per sample (round 867), `WARMUP=6` (the 2026-08-10
calibration), two batches of six pairs with **opposite leading arms**:

| | arm A = `6a4e3612` (pre-887) | arm B = `abf184ee` (HEAD) |
|---|---:|---:|
| n (process medians) | 12 | 12 |
| median | 5,859 ms | 5,424 ms |
| sd | 130 ms (**2.21%**) | 186 ms (**3.44%**) |

**B wins 12/12. Median paired delta −8.18%; per-pair range [−12.03%, −2.81%],
never crossing zero; batch 1 alone 6/6 (median −7.32%), batch 2 alone 6/6
(median −9.51%).** Both arms answer the same program (78 files, 46 errors on
every one of the 192 measured rebuilds; `--listAll` digest
`59d930db849399aea5e03e25fedb8e4e` identical, zero diff) from structurally
distinct binaries (694 vs 688 classes; `MapScopeStack`/`SetScopeStack`/`SpineMask`
present only in B). **Both arm sds are ABOVE the ~1% quiet-box threshold** — what
carries the verdict is the sign consistency (12/12 is 1-in-4,096 by chance) and
its replication across two oppositely-rotated batches, not the point estimate.

### 32.2 The denominators, and round 870's trap stated once

The JFR window is a fixed 90 s of steady state, so both rounds draw ~8,000
samples however fast the compile is and **a share is a share of WALL TIME**.
Round 888's median rebuild was **5,905 ms**, round 893's is **5,461 ms**
(BenchMain's own `medianMs` from the two JFR processes, the same construction
round 888 used: 5,416.8 / 5,505.7). Every number below is
`share × that round's median` = **ms per rebuild**. A naive share diff reads as a
regression on every unchanged row, because the denominator fell 7.5%.

**The span differs from the A/B's.** Round 888's dump already contains rounds 887
and 888, so `888 → 893` is rounds **889–892** only, while the A/B spans **887–892**.
And it is a CROSS-ROUND absolute comparison, which CLAUDE.md prices at up to 12.8%
of drift on identical code — so the table below ranks mechanisms, and only the
paired A/B measures the arc.

### 32.3 The finding: red-black bucket probing went to EXACTLY ZERO

| leaf-class family | ms888 | ms893 | Δ |
|---|---:|---:|---:|
| own code | 3,650.0 | 3,487.7 | **−162.3** |
| **HashMap / HashSet** | **1,560.7** | **1,300.5** | **−260.2** |
| ArrayList / ArrayDeque | 262.6 | 273.8 | +11.2 |
| String / StringBuilder | 259.8 | 222.5 | −37.3 |
| other stdlib | 148.7 | 157.5 | +8.7 |
| `Intrinsics.areEqual` | 16.0 | 11.6 | −4.4 |
| `java.util.regex` | 7.3 | 7.5 | +0.2 |

and inside that family, split out on its own:

| | round 888 | round 893 |
|---|---:|---:|
| `HashMap$TreeNode.*` as a LEAF | **5.91% = 348.8 ms/rebuild** | **0.00% = 0 ms** (zero samples in 16,055) |
| whole `HashMap`/`HashSet` family | 26.43% = 1,560.7 ms | 23.81% = 1,300.5 ms |

**Rounds 889/890 did not make the map lookups cheaper by a little — they removed
an entire mechanism.** Treeified-bucket probing (`HashMap$TreeNode.find`/`getTreeNode`
and their `compareTo` descent) was **5.9% of warm wall time** at round 888 and does
not appear once in either round-893 process. The honest reading of the two rows
together: of the 348.8 ms of tree probing, ~89 ms came back as ordinary linear
bucket probing and **~260 ms is gone**. That is the single largest mechanism this
arc has moved, and **round 890 priced its own change at ~0.5%** — i.e. it was
under-read by roughly nine-fold, because a per-operation amplifier counts map
OPERATIONS and the cost of a treeified bucket is superlinear in its depth
(round 890 measured max bucket 1,140 → 6 for `Relation.packKey`).

Caveat kept in force: **a JFR leaf share is not a wall-clock price** (§ 0, round
623). What lifts this one above "candidate" is that an independent paired A/B on
the same code, in the same session, measured −8.18% wall.

### 32.4 Owner families (round 874's unit), ms/rebuild

| family | ms888 | ms893 | Δ |
|---|---:|---:|---:|
| INV.4 reach classifiers | 371.1 | 376.4 | +5.3 |
| type construction | 295.7 | 303.2 | +7.5 |
| name resolution | 247.7 | 235.8 | −11.9 |
| spine walk core | 220.3 | 204.4 | −15.9 |
| **cta\* handlers** | **216.0** | **84.3** | **−131.7** |
| **flow-graph build** | **207.2** | **134.0** | **−73.2** |
| **module/import resolution** | **192.3** | **133.9** | **−58.4** |
| scope frame copies | 183.2 | 178.1 | −5.1 |
| narrowing walk | 142.2 | 141.9 | −0.3 |
| cpa\* handlers | 143.6 | 132.6 | −11.0 |
| relation engine | 118.3 | 108.4 | −9.9 |
| ccet\* handlers | 92.5 | 85.0 | −7.5 |
| unclassified residue | 3,474.3 | 3,342.8 | −131.5 |

`cta*` −131.7 ms is rounds 891/892 (the `CtaFrame` copy families) landing exactly
where they were built; the two rounds priced themselves at 0.59% + ~0.8–1.1% =
~83 ms, so they too were under-read, by ~1.6×. `flow-graph build` −73.2 ms and
`module/import resolution` −58.4 ms are round 889's `nodeKey` finalizer
(`nodeToFlow` is a `FlowGraphBuilder` table). At the OWNER level the biggest single
movers are `ctaSpineEnter` −54.6, `FlowGraphBuilder.recordFlow` −51.3,
`cpaSpineLeave` −10.8, `spineEnterNode` −9.0 — i.e. **the mechanism is diffuse and
the ROW is the wrong unit**, round 874's law holding for the fifth take running.

### 32.5 The GC hypothesis, REFUTED — and refuted on BUDGET, not on sign

One process per arm with `-Xlog:gc`, same 6+8 shape:

| | pauses | total pause | max | per rebuild (14) |
|---|---:|---:|---:|---:|
| A (`6a4e3612`) | 68 | 1,375.3 ms | 65.4 ms | 98.2 ms |
| B (`abf184ee`) | 86 | 1,286.6 ms | 43.4 ms | 91.9 ms |

B does **more** GC cycles for **slightly less** total pause, and the whole pause
budget is **~1.7% of a rebuild in both arms** — so eliminating GC entirely could
not produce an 8% delta, and the 6.3 ms/rebuild that separates the arms is 0.1 of
the 8 percentage points. **Removing 2.2 M copied map entries and 32 M consultations
did not win by reducing GC pressure.** (n=1 per arm with logging on, so the −4.4%
those two processes show is not a verdict; the refutation rests on the budget,
which n=1 establishes.)

### 32.6 What is still unexplained

−8.18% measured against ~3–5% of summed per-round prices. Of the three candidate
explanations: **GC is refuted** (§ 32.5); **superlinear red-black probing is
directly supported and is probably most of it** (§ 32.3 — a 5.9%-of-wall mechanism
at zero, under-priced ninefold by the instrument that judged it); and
**non-additivity under a shifting denominator** (round 870) remains open and is not
separately testable from here. The two under-readings the profile exposes
(hash ~9×, `cta*` ~1.6×) together roughly close the gap, but that is an
arithmetic coincidence unless someone prices them independently — so the state of
the arc is: **the excess is largely attributed, not yet explained.**
