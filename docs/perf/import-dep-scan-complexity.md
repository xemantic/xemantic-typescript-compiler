# (INC.57)/(INC.58)/(INC.59) The front end was quadratic in the program's FILE COUNT — three times

**2026-08-30.** Measured while executing (INC.56)'s own instruction — *"it must be
re-taken on a project with MANY SMALL files rather than tsc's 78 huge ones"*. The
re-measurement refuted (INC.56)'s premise and found **three** independent quadratics
beside it, in three different subsystems, each invisible to every instrument in this
repo for the same structural reason (§ 2).

**Together they take the per-keystroke floor of a 2,401-file project from 1,653 ms to
279 ms — 5.9x.** §§ 0-5 are (INC.57), the emit-order import scan; § 6 is (INC.58),
`checkJsxImportResolutions`, found by § 6's own instrument in the same session; § 7 is
(INC.59), found by simply RE-READING the floor after § 6 rather than trusting the
ranking two rounds had already changed twice; § 8 is what is left.

## 0. The headline

`FrontEnd.IMPORTS` — `extractRelativeImports`, called twice per program file to build
the emit-order dependency map — grew **4x for 2x the files**:

| program files | before (draw 1 / draw 2) | after | µs/file after |
|---|---|---|---|
| 601 | 37.7 / 18.9 ms | 4.2 / 5.8 ms | 7.6 |
| 1201 | 125.2 / 76.3 ms | 9.2 / 7.1 ms | 6.8 |
| 2401 | 372.2 / 331.6 ms | 14.0 / 16.1 ms | **6.3** |

Per-file cost **doubled with the file count** before and is **flat** after. Floor
medians (`recheckOnly` naming a file the program does not contain — what an editor
pays per keystroke) moved 165 → 142 / 409 → 359 / **1653 → 1035 ms**.

## 1. What it was

```kotlin
private fun extractRelativeImports(sourceFile, currentFileName, allFiles, …) {
    val allTsFileNames = allFiles.map { it.fileName }.toSet()   // <-- per CALL
```

A fresh `ArrayList` **and** a fresh `LinkedHashSet` of every program file name, built
on entry, twice per file (once with `/// <reference path>` edges, once without). Every
one of the ~10 uses below it is a membership probe — the set is never iterated and
never escapes. Two neighbouring scans in the same loop were the same shape:
`parsed.files.any { it.fileName == companionDts }` and the four-way `hasConflictingJs`
scan, both O(files) inside a per-file loop.

So the region cost `2 x files²` string hashes plus `2 x files` container allocations
of size `files` per build. At 2,401 files that is **11.5 M hashes and ~92 MB of
garbage** on every query.

## 2. Why nine hundred rounds of instruments could not see it

**Every dashboard profile is the same codebase in a different slice: tsc's own
sources, 78 files averaging 128 KB.** `2 x 78²` is twelve thousand probes — well
under a millisecond, indistinguishable from noise, and `FrontEnd.IMPORTS` on the
compiler profile is a row nobody had reason to open.

An application project is the *opposite* shape: thousands of files of about a
kilobyte. A per-FILE overhead is invisible on the first shape and dominant on the
second, and **no instrument in this repo had ever been pointed at the second one**.
That is the transferable half of this round, and it generalises past this defect:

> A cost that is per-FILE rather than per-BYTE is structurally invisible on all eight
> profiles, because they are one codebase with an atypically small file count and an
> atypically large mean file size. Before pricing any front-end row, ask whether the
> profile's *shape* can express the cost at all.

It is the same law CLAUDE.md already records for regimes ((INC.9): "a cost prior does
not transfer across regimes — the identical candidate is 0.3% of a full build and 24%
of an incremental floor"), on a new axis: the **corpus shape**.

The generator is `scripts/gen-many-small-project.py` (layered DAG, two imports per
module, one deliberate type error so `FloorDecompositionMain` does not refuse a
silent program).

## 3. The fix

Hoist. `parsed.files` is a `val List` on a `data class` and `parsed` is never
reassigned, so the set is loop-invariant by construction — this is not a caching
question and has no invalidation story. `extractRelativeImports` now takes
`allTsFileNames: Set<String>`; `cpcScanFiles` builds it once, and the two `any {}`
scans read the same set.

`.toSet()` is kept verbatim rather than swapped for a `HashSet`: the container — and
therefore any iteration order a future consumer might depend on — stays bit-for-bit
what the per-call expression produced. CLAUDE.md's rule for `mutableMapOf` →
`HashMap` conversions ("PROVE order is unused") is thereby not engaged at all.

The `/// <reference path>` `Regex` was also constructed per call and is now a
top-level `private val`. `Regex(…)` compiles eagerly, so that was one pattern
compilation per program file per build.

## 4. Why the pin is a COUNT

`EagerIndexCensus.programNameSetBuilds` — **1 per compile, invariant to file count**.

(INC.52)'s law says a floor row read 13.16 ms and 8.42 ms in two draws of one binary,
so a timed assertion would be a coin flip. But the stronger reason is that the claim
is about **complexity**, and only a count can state one: a wall assertion says "this
build was fast", where `programNameSetBuilds == 1` at 10 files *and* at 100 says the
work does not grow with the program. `ImportDepScanComplexityTest` asserts both sizes
plus a VALUE pin (four modules declared in reverse dependency order must emit in
dependency order), because a count cannot distinguish a correctly hoisted set from an
empty one.

(INC.55)'s caveat about comparing two different programs does not apply: that bit
because the `pass("…")` poll count is not constant across programs, so a DIFFERENCE
was swamped. Here the quantity is 1 by construction — an absolute value, with program
size as precisely the axis under test.

## 4a. The ablation — one arm, two answers

The arm is the mistake the pin's KDoc names: move the set build back inside
`extractRelativeImports`, carrying the census increment with it. Applied against the
committed tree (round 789's rule) and diffed against the arm's own snapshot (round
922's rule, since `git diff --shortstat` is vacuous on a tree carrying the round's own
work).

**Answer 1 — the pins discriminate.** Both count pins go RED reading exactly **20** for
a ten-file program, i.e. `2 x files`, precisely what the KDoc predicts. The VALUE pin
stays GREEN, which is the point of having it: the two halves test different things
(complexity vs correctness) and neither is redundant.

**Answer 2 — the change is counter-neutral, measured rather than argued.** All **20**
`cost_gate.py` counters are IDENTICAL between the quadratic arm and HEAD
(`typeOfExpr.calls` 600,215 both, `narrow.memoServed` 46,921 both, `spine.nodes`
856,962 both, …). So this round contributes zero to them, and the `+0.54%` /
`+1.55%` the gate reports against `docs/perf/cost-counters.txt` is accumulated drift
from the **60 commits** since that baseline was recorded at (CHK.63) on 2026-08-28.

The baseline is deliberately **not** rebaselined here. Rebaselining would fold sixty
commits of unattributed drift into this one and make it un-auditable — the counters
are still inside the ±2% tolerance and the gate exits 0, so the honest record is to
name the drift and leave it addressable.

## 5. What this says about (INC.56), which is the item that was being worked

(INC.56) is *"LET AN IntelliJ-CLASS HOST SKIP THE RE-READ — **THE LARGEST REMAINING
FRONT-END ROW**"*. On the shape it demanded be measured, it is **not** the largest —
it is fourth. Floor rows on the 1,201-file project, before this round:

| row | ms (draw 1 / draw 2) | share of a 409 ms floor |
|---|---|---|
| checker construct + init pass dispatch | 215.2 / 218.1 | **53%** |
| `extractRelativeImports` (this round) | 125.2 / 76.3 | 20–31% |
| post-checker | 55.8 / 39.3 | 10–14% |
| **crawl WALL — (INC.56)'s target** | **38.1 / 25.5** | **6–9%** |
| config load + glob | 20.2 / 12.3 | 3–5% |

(INC.56) is not refuted as a *saving* — 25–75 ms of wall is real — but its ranking is,
and it is the only one of the five that costs a **soundness promise** (an opt-in
policy where a file changed on disk without `updateFile` is missed, plus (INC.48)'s
"a content hash cannot see an ADDED file" in a second costume). It should be worked
after the two rows above it, not before them.

## 6. (INC.58) — the successor, worked immediately, and the law held twice

§ 6's instrument (divide the floor pass table by file count at two program sizes) was
run the same session. One pass carried almost all of it:

| pass | 601 files | 2401 files | growth (4 = linear) |
|---|---|---|---|
| **`checkJsxImportResolutions`** | 48.66 ms | **709.74 ms** | **14.6** |
| `init:buildPerFileScopes` | 3.17 | 13.52 | 4.3 |
| `init:computePerFileVisibility` | 1.01 | 6.66 | 6.6 |
| `init:moduleTypeNameIndex` | 0.50 | 3.93 | 7.9 |
| — table total — | 65.00 | 774.65 | 11.9 |

**709.74 of 774.65 ms — 92% of the floor pass table — on a project containing no JSX
at all.** `resolveJsxTsxCandidate`'s last resort is a path-suffix match ("any program
file whose name ends with `/<base>.jsx`"), and it walked `fileResults.keys` once per
import specifier per extension: `2 x files x specifiers`. Worse, the pass is gated on
`--jsx` being **UNSET**, so it did its maximum work on exactly the projects that have
nothing to do with JSX, and always answered null.

**(INC.54)(a) ranked this pass at 1.2 ms** from the tsc profile. Same pass, same
binary, **600x** — § 2's law, confirmed on a second, independent instance within one
session, and this time it also invalidates a published *ranking* rather than a price.

**The narrowing is exactly equivalent.** Every non-null return of
`resolveJsxTsxCandidate` is a `fileResults` member ending in `.jsx`/`.tsx`: the direct
probes build their candidate as `"…$ext"` and test `in fileResults`, and both arms of
the suffix scan (`fn.endsWith("/$base$ext")`, `fn == "$base$ext"`) can only match such
a name. So the filtered scan returns the same file **in the same order** — load-bearing,
since the scan takes the FIRST match — and a program with no such file may return
immediately. The filter is a local in the pass rather than a `Checker` field, because
(INC.53) measured that class of field initializer at 16-30 ms on every build and
invisible to every gate here.

**Result: 709.74 -> 0.30 ms at 2,401 files (2,350x), and now linear** (0.076 / 0.150 /
0.302 ms at 601 / 1201 / 2401 — exactly 2x for 2x the files, which is the O(files)
filter scan and nothing else).

### The two rounds together

| files | original floor | after (INC.57) | after (INC.58) |
|---|---|---|---|
| 601 | 165 ms | 142 | **99** |
| 1201 | 409 ms | 359 | **197** |
| 2401 | **1653 ms** | 1035 | **366** |

A **4.5x** reduction in the per-keystroke floor at the top size, and the gain grows
with the project because both defects were super-linear.

### A pin lesson worth keeping

The first value pin asserted `jsxSuffixScanSteps > 0` for a *relative* specifier and
went RED **on a working binary**: TS6142 fired, but the relative path is served by the
O(1) direct probe and never reaches the scan. **An assertion about WHICH path produced
an answer is not implied by the answer being right.** There are now two value pins, one
per resolution path — a bare specifier is what forces the suffix scan.

## 7. (INC.59) — the third one, in the path that emits nothing

§ 6 changed the ranking for the second time in one session, so the floor was
**re-decomposed rather than assumed**. `post-checker` had become the largest row —
**166-189 ms of a 366 ms floor (~48%)** at 2,401 files, scaling 10 -> 47 -> 177 ms
across the three sizes — and it appeared in no queue item at all. Its sub-rows put
158.5-175.3 ms of that in `POST_EMITPREP`, against 6.8-8.2 ms at 601 files: **21x for
4x the files**.

One expression:

```kotlin
parsedSourceFiles.filter { it.key !in transformOrder.toSet() }
```

`.toSet()` is INSIDE the lambda, so an N-element set is rebuilt once per entry of an
N-entry map. **And this is the `--noEmit` path** — round 738's `skipEmitOutputs` gate
means such a build emits nothing, so it was spending 175 ms per keystroke preparing an
emit order that would never be used.

The hoist is exactly equivalent (a pure membership predicate; `filter` preserves the
map's order either way). **POST_EMITPREP 158.5-175.3 -> 1.8-2.8 ms (~70x)**, the whole
post-checker region 166-189 -> 8.6-12.6, and the floor 366 -> **279 ms**.

### The three together

| files | original floor | +(INC.57) | +(INC.58) | +(INC.59) |
|---|---|---|---|---|
| 601 | 165 ms | 142 | 99 | — |
| 1201 | 409 ms | 359 | 197 | — |
| 2401 | **1653 ms** | 1035 | 366 | **279** |

**5.9x on the per-keystroke floor at the top size**, and the gain grows with project
size because all three defects were super-linear. The reusable half is not any of the
three fixes but the loop that found them: **re-read the floor after every round that
moves it** — the ranking changed three times in one session, and each new top row was
one nothing in the queue had named.

## 8. What is left on this shape, and the next successor

With all three quadratics gone, the 2,401-file floor is **279 ms** (the instrumented
`FE.total` reads 200-207 ms; the plain median carries the probe-free ~279) and its rows
are, at last, all linear. Re-measured after (INC.59):

| row | 2401 files (two draws) | note |
|---|---|---|
| **crawl WALL** | **62-66 ms** | **(INC.56)** — now genuinely first, as its entry claimed |
| **checker construct + init dispatch** | **61-66 ms** | was 756-810 before (INC.58) |
| config load + `@types` + root glob | 29-45 ms | never examined on this shape |
| `extractRelativeImports` | 16-25 ms | was 331.6 before (INC.57) |
| post-checker | 9-13 ms | was 166-189 before (INC.59) |
| bind | 7-10 ms | |

**(INC.56) is now a defensible next item** — it was fourth of five and is now first —
but that was only true *after* these three rounds, which is exactly what §5 records. It
remains the only one of these rows costing a soundness promise, so its opt-in framing
stands. **`config load + @types + root glob` is the row nothing has ever looked at on
this shape** and is now third; it is a cheaper place to start than (INC.56) because it
carries no promise at all.

Inside the (now 55 ms) pass table the largest *growth* factors left are
`init:buildPerFileScopes` (12.9 ms), `init:computePerFileVisibility` (5.5) and
`init:moduleTypeNameIndex` (2.6). Price them before opening one — §2's law cuts both
ways, and this repo's history is mostly of candidates that did not survive division.

**The transferable half of this whole document is not any of the three fixes. It is the
loop that found them:** re-read the floor after every round that moves it. The ranking
changed three times in one session, and each new top row was one that no queue item had
named — twice because the previous top row had been hiding it, once because no
instrument had ever been pointed at this corpus shape at all.
