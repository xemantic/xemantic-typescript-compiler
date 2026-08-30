# (INC.57) The emit-order import scan was quadratic in the program's file count

**2026-08-30.** Measured while executing (INC.56)'s own instruction — *"it must be
re-taken on a project with MANY SMALL files rather than tsc's 78 huge ones"*. The
re-measurement refuted (INC.56)'s premise and found a larger, cheaper, soundness-free
target beside it.

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

## 6. The successor

**The `Checker` init-block pass dispatch is itself super-linear in file count**, and
after this round it is ~73% of the floor on the 2,401-file project. Measured (after,
so the quadratic is not in it):

| files | checker construct (draw 1 / draw 2) | growth for 2x files |
|---|---|---|
| 601 | 91.2 / 72.9 ms | — |
| 1201 | 216.8 / 204.3 ms | 2.4–2.8x |
| 2401 | 755.6 / 809.7 ms | 3.5–4.0x |

That is roughly `N^1.5`–`N^1.9`, on the region an editor pays for on every keystroke.
(INC.54)(a) named the pass table as "the largest block now" from the tsc profile and
ranked its rows there; this says the ranking should be re-taken on the many-small
shape first, because a pass whose cost is per-FILE will sort differently — and
because a super-linear term is a different kind of target from a flat 6.9 ms row.
`scripts/floor-decomposition.sh build/bench/many-small-2400 2` is the instrument.
