# (INC.36) step 1 — WHAT the 264 MB a `referencesAt` sweep retains IS

*2026-08-25, tree at `51a64db3` plus the untracked runner this page describes.
**Nothing was optimised in step 1 — this is an instrument and a table.** Step 2
(the fix, and what it measured) is § 9.*

> **EVERY NUMBER ON THIS PAGE IS A HEAP OCCUPANCY OR A WALL TIME AND IS
> THEREFORE PINNED BY NO TEST.** A heap reading is a collector's decision, not a
> counter (CLAUDE.md round 868: a timed or sized assertion is a coin flip), so
> nothing here is asserted anywhere in the suite; what the suite pins is the
> INVARIANT the fix rests on (§ 9). Re-take a number with
> `scripts/inc36-retention.sh`, never quote it across binaries.

> **HEADLINE — FOUR THINGS.**
>
> **(1) THE PROGRAM IS PARSED TWICE AND BOTH COPIES ARE KEPT.**
> `Project.sourceIndexes` holds 78 parses / 9,977,097 chars / **856,962 nodes**
> — (INC.37)'s whole-program spine figure to the node — and the process-global
> `CrawlParseCache` holds a SECOND parse of the same 78 files at the same
> content under the same `computeParserFlags`. INV.1(e) is exactly the invariant
> that makes them equal trees. Together **217.7 of the 264 MB**.
>
> **(2) The attribution is 43.5% / 39.0% / 1.0% / 16.5%.**
> `Project.sourceIndexes` **114.7–115.1 MB** (`referencesAt`'s own),
> `CrawlParseCache` **103.0 MB** (the BUILD's, process-global, SHARED, and it
> survives `close()`), `RealLibSnapshots.parseCache` 2.6 MB, and 43.7 MB of JVM
> baseline + embedded lib text + the 9,827 answers. **`cached`, `captures`,
> `prepared`, `narrowed`, `recheck` and `lineMaps` are 0.0 MB COMBINED** — every
> memo (INC.12)/(INC.14)/(INC.32)/(INC.40) added is free; the parses are not.
>
> **(3) PER-PROJECT MARGINAL RETENTION IS ~115 MB, NOT 264** — measured, not
> argued. A second `Project` in the same process re-earned 105.9 MB with the
> process-global caches cold and then added only **115.3 MB** of its own;
> `close()` returned 115.0 and left 103 standing. A host with N projects open
> pays `103 + 115·N`, not `264·N`.
>
> **(4) A CORRECTION TO THE STANDING (INC.36) TEXT: `CrawlParseCache` is NOT
> unbounded per edit.** Its map is `HashMap<String, PreParsedFile>` keyed by
> **PATH** with the content stored *inside* the value, and its own KDoc § Memory
> says so — an edit REPLACES the entry rather than adding one. It is bounded by
> the number of distinct paths crawled.

---

## 1. How this was measured

One binary, one profile (`build/bench/tsc-project-637d5746` — the compiler
profile: tsc's own 78 sources, 46 errors), this box (8 cores, 15.6 GB, zero
swap). Two processes minimum for anything quoted, because the reading is a heap
occupancy after a collector's own decisions.

* **The instrument** is `xemantic-typescript-compiler-project/src/jvmTest/kotlin/Inc36RetentionMain.kt`,
  driven by `scripts/inc36-retention.sh` (which REFUSES rather than skips when
  its profile or its class file is absent — rounds 853/873).
* **The quantity** is `liveAfterGc` = six `System.gc()` calls, then
  `totalMemory() − freeMemory()`. Six rather than the four `Inc31ResidueMain`
  uses: this is a SUBTRACTION ladder, so one under-collected row corrupts *two*
  deltas rather than one.
* **`-Xmx6g` deliberately.** A constrained heap makes G1 collect harder and
  changes what a retention reading says. The `-Xmx1g`/`-Xmx2g` floor is
  (INC.36)'s already-measured half and was not re-asked here.
* **Every clear is REFLECTIVE.** A `dropXForMeasurement()` on a public class is
  a production surface; a measurement must not leave one behind. The cost is
  that the runner breaks on a field RENAME, which is why its `field()` helper
  fails loudly with the class's whole field list rather than returning null.

### 1a. The controls, without which a heap ladder is worthless

A heap number from a blind instrument reads exactly like a real one (round 849),
so three things are checked and printed rather than assumed. **All three
passed, in both processes.**

| control | what it rules out | reading |
|---|---|---|
| non-vacuity | the caret resolved to nothing and every row below is a measurement of an early return | `referencesAt` returned **9,827** hits, 17.1 s / 17.3 s |
| positive | the premise of the queue item is wrong and the sweep retains nothing | the `referencesAt` step **rose 115.3 / 116.4 MB** |
| attribution | the ladder is measuring GC noise rather than retainers | the two fields this arm never fills returned **−0.0 MB** |

The third is the one that matters most and is the cheapest to omit: a ladder in
which *every* step returns something is a ladder measuring the collector.

### 1b. The `jcmd` self-attach trap

The class histogram is taken by an EXTERNAL helper the shell script runs, and
the runner asks for it through a file with a 90 s deadline. It is **not** taken
by the runner shelling out to `jcmd` with its own pid: a JVM attaching to itself
HUNG the whole ladder for thirty minutes (2026-08-25), printing a plausible
partial table and then nothing — which reads exactly like a slow measurement
rather than a dead one. A histogram is corroboration here, never the finding, so
it may not be able to cost a round.

---

## 2. The ladder

Ten points, one row per retainer, each delta priced by what dropping *it and
nothing else* returns. Megabytes; P1/P2 are the two processes.

| # | step | P1 live | P1 Δ | P2 live | P2 Δ |
|---|------|--------:|-----:|--------:|-----:|
| 0 | baseline | 1.6 | +1.6 | 1.6 | +1.6 |
| 1 | `Project.open` | 1.8 | +0.3 | 1.8 | +0.3 |
| 2 | `diagnostics()` whole program | 148.6 | +146.8 | 148.2 | +146.4 |
| 3 | `referencesAt` (9,827 hits) | **264.0** | **+115.3** | **264.6** | **+116.4** |
| 4 | drop `sourceIndexes` | 149.3 | **−114.7** | 149.6 | **−115.1** |
| 5 | drop `lineMaps` + `captures` + `prepared` | 149.3 | +0.0 | 149.6 | +0.0 |
| 6 | drop `narrowed` + `recheck` (inert control) | 149.3 | +0.0 | 149.6 | −0.0 |
| 7 | drop `cached` | 149.3 | +0.0 | 149.6 | −0.0 |
| 8 | `close()` | 149.3 | +0.0 | 149.6 | +0.0 |
| 9 | drop `CrawlParseCache` (process-global) | 46.3 | **−103.0** | 46.6 | **−103.0** |
| 10 | drop `RealLibSnapshots.parseCache` | 43.7 | −2.6 | 44.0 | −2.6 |

The two processes agree to **0.6 MB at the peak and 0.4 MB on every attributed
row**, which is what licenses reading the deltas as retainers.

**Reproduced in a second batch of two processes at commit `51a64db3`** (the
before-arm of step 2, same script, same profile): peak **264.5 / 264.1**,
`sourceIndexes` **−115.2 / −114.7**, `CrawlParseCache` **−103.1 / −103.2**,
`RealLibSnapshots` **−2.7 / −2.6**, 9,827 hits both times, both controls green.
Four processes, one table.

Rows 5–8 are the finding people do not expect: **`close()` frees nothing**, and
neither does dropping the build result. The language service's own memo layer —
`cached`, `captures`, `prepared`, `narrowed`, `recheck`, `lineMaps`, i.e. every
mechanism (INC.12), (INC.14), (INC.32) and (INC.40) added — is **0.0 MB
combined**. (INC.32)'s weight-bounded capture lanes are doing their job.

---

## 3. The attribution

| retainer | MB | share | whose | survives `close()` |
|---|---:|---:|---|---|
| `Project.sourceIndexes` | 114.7 / 115.1 | 43.5% | `referencesAt`'s | no |
| `CrawlParseCache` | 103.0 / 103.0 | 39.0% | the BUILD's | **yes** — process-global |
| `RealLibSnapshots.parseCache` | 2.6 / 2.6 | 1.0% | shared lib parses | **yes** |
| JVM baseline + embedded lib text + 9,827 answers | 43.7 / 44.0 | 16.5% | — | — |

`sourceIndexes` is *not* just the trees: it is the trees **plus**
`SourceIndex`'s three parallel token arrays, which is the ~11.7 MB by which its
copy exceeds the crawl's (§ 4's `[I` + `[LSyntaxKind;` rows).

---

## 4. The class histogram corroborates the double parse independently

`jcmd GC.class_histogram` at peak retention (total live 268.9 MB; the ladder's
own reading of the same point is 264.0 — the histogram's walk allocates):

| class | instances | MB |
|---|---:|---:|
| `[B` | 890,163 | 76.1 |
| `Identifier` | **770,460** | **43.1** |
| `String` | 889,501 | 21.3 |
| `[Ljava/lang/Object;` | 278,616 | 15.7 |
| `[I` | 1,842 | 13.8 |
| `[J` | 419 | 10.0 |
| `PropertyAccessExpression` | 135,887 | 8.7 |
| `ArrayList` | 294,747 | 7.1 |
| `CallExpression` | 105,018 | 6.7 |
| `BinaryExpression` | 76,908 | 5.5 |
| `LinkedHashMap` | 76,264 | 4.9 |
| `TypeReference` | 79,703 | 4.5 |
| `[LSyntaxKind;` | 79 | 4.4 |
| `Parameter` | 51,045 | 4.1 |

**770,460 `Identifier`s against 856,962 nodes in ONE copy.** CLAUDE.md's
standing figure is "IDENTIFIER is 44.5% of the nodes"; 44.5% of 856,962 is
381,347, and 770,460 is that **DOUBLED**. The histogram reaches the same
conclusion as the ladder by a completely different route.

After everything is dropped, live is 42.2 MB and the largest xtsc-owned row is
the 9,827 `ReferenceLocation`s at **0.31 MB** — i.e. the ANSWER is free and the
INPUT is the whole cost.

---

## 5. The per-project marginal figure

`mode=second` opens a SECOND `Project` on the same profile after the ladder has
cleared both process-global caches, so it pays for what it re-earns:

* it re-earned **105.9 MB** of shared caches (the crawl's parses plus the lib
  parses), then
* added **115.3 MB** of its own, and
* `close()` returned **115.0**, leaving 103 standing.

So the shape a plugin host should budget against is **`103 + 115·N`**, not
`264·N` — and the 103 is paid once however many projects are open, because it is
keyed by content and shared.

---

## 6. Re-parse cost, for pricing a bound rather than guessing one

`SourceIndex.of` on the profile's own files, median of 5 draws after one
throwaway, per process — so it is a **median of medians over four processes**,
and the spread between them is the honest reading:

| file | chars | ms, the four process medians |
|---|---:|---|
| `path.ts` (median-sized file) | 44,162 | 1, 12, 19, 12 |
| `binder.ts` | 194,463 | 6, 15, 18, 11 |
| `checker.ts` | 3,151,772 | **144, 156, 155, 171** |

Only the `checker.ts` row is stable, because only it is long enough to dominate
its own JIT ramp; the two small rows span 1–19 ms and say nothing more precise
than "a small file re-parses in tens of milliseconds at worst".

A whole-program re-parse EXTRAPOLATES to ~0.35 s against a 15.4–17.8 s
`referencesAt`, i.e. ~2%. **That extrapolation is not a measurement** and is
recorded here only to say that a weight-bounded LRU over `sourceIndexes` is not
obviously unaffordable — see § 8.

---

## 7. The correction: `CrawlParseCache` is bounded

The standing (INC.36) text called the crawl cache unbounded per edit. It is not.
`CrawlParseCache.entries` is `HashMap<String, PreParsedFile>` keyed by **path**,
with the content and flags stored *inside* the value; a hit requires the same
path, the same bytes and the same `ParserFlags`, and an edit therefore REPLACES
the entry rather than adding one. Its own KDoc § Memory states this, and it is
the reason the map is keyed by path rather than by `(path, content)`.

**It is bounded by the number of distinct paths crawled** — one program's trees,
not one per keystroke. What it is *not* bounded by is `Project.close()`: it is
process-global by design (`close()`'s own KDoc says dropping it would slow down
unrelated work to free memory the next build would immediately re-earn), which
is why it is the 103 MB that survives step 8 of the ladder.

---

## 8. What step 1 leaves for step 2

Two candidates, and they are exclusive:

**(a) Stop `Project` parsing a second copy.** `sourceIndexOf` calls
`SourceIndex.of(text, key, flags)` with flags from the same
`computeParserFlags` the compiler uses, over the same overlay text the crawl
read. The crawl has already parsed exactly those bytes under exactly those flags
and kept the tree. Reusing it deletes the duplicate at **zero re-parse cost**,
and the dirty-buffer case is safe by construction: an unsaved buffer is a
content-key MISS, so it parses once, which is correct.

**(b) Bound `Project.sourceIndexes`** the way (INC.32) bounded `captures` — an
access-ordered LRU by source-character weight. Costs the re-parses of § 6 and
leaves the duplication in place for whatever fits under the bound.

(a) is strictly better if it is safe. It rests on CLAUDE.md's standing rule that
a parse may be shared across programs — the AST is written only by
`indexSourceFile` and `Parser` is pure in `(source, fileName, flags)` — which
`RealLibSnapshots` and `CrawlParseCache` already both rely on.

---

## 9. Step 2 — what landed

*(2026-08-25, filled in by the fix commit; see PLAN-PHASE-5.md's (INC.36)
session note.)*
