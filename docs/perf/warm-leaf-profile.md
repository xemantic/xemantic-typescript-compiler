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
