# Status

**(INC.69) — THE INIT-BLOCK DISPATCH IS NOT FLAT, AND A PLATEAU IS A SHARED PER-FILE COST
(2026-08-31).** (INC.66) recorded the ~400-pass table as FLAT, "so there is no row to make
cheaper"; a HISTOGRAM rather than a top-N list refutes it — on `many-small-2400-dom` the
floor table is **418 rows summing to 39.5 ms, 44 of them carrying 37.1 (94%) and 367 carrying
0.82** — and 21 of those 44 sit at an almost identical **0.39-0.55 ms**. A plateau of
near-identical prices across unrelated walkers is not a coincidence of what they do: all 21
are corpus PIN walkers whose whole body is a whole-program loop whose first act is
`fileName.substringAfterLast('/') != "<one literal>"`, i.e. 2,401 iterations and a `String`
allocation each to compare against a name no real project contains.
**ONE BASENAME INDEX, BUILT ON FIRST ASK**, and the 21 loop HEADERS re-pointed at it; the
redundant `!=` guard is kept VERBATIM so every loop body is byte-identical.
**MEASURED — the deterministic half first**: the 21 rows **10.079 -> 0.457 ms** (second
instrumented draw, round 846; 0.438 of the remainder is the FIRST asker paying the one build,
the other twenty are 0.000-0.002), cross-checked against four draws of the unmodified binary
in a separate process at 9.27-12.01. **ABBA-rotated wall, one JVM per arm, 4 processes/arm x
8 draws: floor median-of-medians 157 -> 144.5 ms (-8.0%)**, means 162.5 -> 145.5.
**THE SAME RUN RE-PROVED (INC.68)'s LAW ON ITSELF**: the two unrotated `rows` processes read
whole-table sums of 52.32 -> 54.27 ms — the after arm 4% "worse" — while the 21 rows it
changed fell 22-fold, because that process simply drew slow. An unrotated process compares
rows WITHIN itself, never totals.
**THE PINS ARE NESTED-PATH VALUE PINS BECAUSE THE CORPUS CANNOT REACH THEM**: the harness
materialises no directory, so its names are FLAT and all ~13k baselines exercise the
degenerate key — an index keyed by the full path passes every one and silently stops pinning
a real project's `src/dates/temporal.ts`, a MISSING diagnostic nothing here prints.
**GATES.** Suite **16,553 / 0 / 3** (+5, exactly the new pins); `cost_gate.py` exit 0, every
counter +0.00%; `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`,
labelled a CONTROL in its own header (no profile holds any of the 21 literals). Ablations
a1/a2/a3 redden 2/1/2 of 5; **a4 (widen the index to a suffix match) reddens NOTHING and is
recorded as a round-927 redundant-guard PAIR** — the index buys the speed, the kept guard
keeps the correctness — and only a5, which widens the index AND deletes the guard, reddens
the negative control.

**(INC.68) — 80% OF THE PATHS THIS COMPILER NORMALIZES WERE ALREADY NORMALIZED, AND THE
BLOCKED ARMS INVENTED A REGRESSION THAT ROTATION REMOVED (2026-08-31).** (INC.66) said
"before pricing any row, check it has a SPLIT"; the row it named for re-decomposition —
`config+glob`, the one floor row carrying no soundness promise — had a split already, and the
cost was under it in a function neither row names. `PathUtil.normalize` is called once per
directory entry by `systemListEntries` and once per candidate probe by `PathUtil.join`, and
allocates ~10 objects each time. **THE CENSUS IS THE WHOLE ARGUMENT AND IT COST ONE COUNTER:
11,935 calls per floor build, 9,584 (80.3%) returning the argument UNCHANGED** — not a
property of the fixture, but of the callers (a child path built from an already-normalized
parent; `"<normalized base>/<plain name>"`). So the fix is a one-pass allocation-free
predicate and an early return: no cache, nothing to invalidate. **PRICED BY POPULATION
BEFORE THE FLOOR WAS CONSULTED** ((INC.52)): 1.02-1.22 us/call against <=0.2, i.e. ~9 ms per
floor build, which is what the rows returned. **ABBA-rotated, 4 processes/arm, 32 floor draws
each:** `vfs.listEntries` 10.86 -> 7.76, specifier resolution 14.94 -> 10.66, crawl WALL
39.51 -> 32.18, config+glob 17.96 -> 13.44, **floor median 127 -> 121 ms**.
**THE LESSON OUTRANKS THE MILLISECONDS: the first, BLOCKED, paired run reported +2.70 ms on
`include/exclude regex match` — a region that calls no `normalize` — reproducibly over 12
draws per arm, and read config+glob as +3.39, i.e. it said the glob half was a net loss. Both
signs INVERTED under rotation.** A per-arm draw count does not substitute for rotation, and a
stable delta in a region with no causal path to the edit is the tell that the ORDER is the
variable.
**THE PINS ARE OVER THE ACCEPTANCES, because the directions are asymmetric**: a false
negative costs the old path, a false positive resolves to a DIFFERENT FILE with no diagnostic
anywhere ((CFG.1)). Value pins against a transcribed reference (a second implementation — a
differential whose arms are one function cannot see a fast path), plus idempotence, a
rewrite-count control and a quiescence-independent predicate pin. Ablations a1/a2/a3/a4 redden
5/5/4/3 of 6.
**GATES.** Suite **16,548 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78; `huge_methods.py --fail-over 0` clean; 8-profile grid
`added=0 removed=0` on all eight — **coverage here rather than a control**, since the corpus
materialises no directory and cannot reach the resolver's path arithmetic.

**(INC.67) — READING THE PLUGIN FOUND A DEFECT NO PROFILE COULD, AND IT WAS ONE THIS
SESSION HAD WIDENED (2026-08-31).** The instrument was the CONSUMER'S SOURCE.
`xemantic/xtsc-intellij-plugin` — the first real host of the `Project` API — keeps one
`XtscSession` per `tsconfig.json`, **each owning its own single-thread executor**, so a
monorepo with N configs runs **N compiler threads in one JVM**. That is a shape no fixture,
profile or corpus baseline here produces, and the one every process-global cache implicitly
assumes away. `RealLibSnapshots.parseCache` was a plain `HashMap` mutated in place, and its
KDoc's stated mitigation (`prewarmParsedLibFiles`) covers `--workers` inside ONE compile and
says nothing about two independent sessions — and (INC.63)/(INC.65) had just added two more
such maps. All three now publish **copy-on-write behind `@Volatile`**.
**WHAT IT BUYS, PRECISELY:** a lost race still costs a RECOMPUTATION, and always did, since
`getOrPut` on a `HashMap` is not atomic either; what this removes is the CORRUPTION. **And
the duplicate is harmless for the mirror of round 471's reason** — the identity sets these
feed compare `Node`s STRUCTURALLY, so two parses of the same lib text are interchangeable to
every consumer. `ModuleResolver`'s (INC.65) memo needs none of it: per instance, per build.
**THE FIRST DRAFT OF THE PIN BROKE TWO OF CLAUDE.md's OWN RULES AND ONLY RUNNING IT SAID SO**
— it put a `Map<String, SourceFile>` inside `assert(...)`, so power-assert rendered the AST
and the failure arrived as an **`OutOfMemoryError` in the diagram builder** with the real
cause masked; and it compared two reads by IDENTITY, which assumes a quiescent process, so
it passed in isolation and failed in the full suite. **A pin's ENVIRONMENT is part of its
specification.**
**GATES.** Suite **16,542 / 0 / 3**; `cost_gate.py` exit 0; `huge_methods.py --fail-over 0`
clean; 8-profile grid `added=0 removed=0` on all eight; ablation e1 reddens exactly the
publication pin.
**WHAT ELSE THE PLUGIN REVIEW SHOWED:** it already does what this arc assumed a host would —
`updateFile` for unsaved buffers, `diagnosticsOf` for the file ON SCREEN ONLY, one thread per
project, and (INC.55)'s cancellation wired to `ProcessCanceledException`. Its `configPath`
argument is load-bearing and non-obvious: without it a malformed `tsconfig.json` shows a
clean editor over a program checked with default options. It is also the host that could make
(INC.56)'s promise — but it invalidates on `VFS_CHANGES` rather than owning the read, so the
promise is expressible and not yet made.

**(INC.65) — THE CRAWL RE-ASKED THE FILESYSTEM A QUESTION IT HAD ALREADY ANSWERED, AND THE
SESSION'S FLOOR IS 241 -> 151 / 256 -> 116 ms (2026-08-30).** The previous round named "a
PARTITION question and a HOST PROMISE" as all that was left; that was wrong within the hour,
because **`FrontEnd.CRAWL` had no split below its two elapsed-WITH-SUSPENSION CPU sums** — so
the residue between them and the WALL was unattributed, and on an application-shaped project
that residue is most of the row. Bracketing the crawl's SEQUENTIAL half
(`FrontEnd.CRAWL_RESOLVE`) read **20.6-28.6 ms of a 44-60 ms crawl wall**, ~15% of the whole
floor. **(INC.53)'s "ask what runs OUTSIDE a pass" has a sub-row-shaped twin, and this is the
third time this arc that ADDING an instrument, not reading one, is what found the cost.**
**THE FIX IS EXACT, AND READING THE FUNCTION IS WHAT SAYS SO**: `ModuleResolver.resolve`
reads `importerPath` once, to take its `dirname`, and never again, so `(importerDir,
specifier)` is not a heuristic key but THE key. Censused offline before building anything:
**4,701 resolutions over 2,351 distinct pairs — a duplication factor of exactly 2.0**, and a
codebase with shared barrels has more. Nothing to invalidate — a `ModuleResolver` is
constructed once per `build`, so the memo's lifetime IS one build; deliberately NOT
process-global, since a cross-build cache cannot see an ADDED file ((INC.48)). `null` is a
real answer and is memoized too, or the filesystem is re-probed for every unresolved
specifier — the population a project mid-edit has most of. **CRAWL_RESOLVE 24.0 -> 14.3 ms
mean; crawl wall 44-60 -> 34-44.**
**THE PIN THE DESIGN RESTS ON IS NOT A COUNT**: a memo keyed by the SPECIFIER ALONE passes
every count assertion and silently resolves `./dep` in one directory to another directory's
file — a wrong PROGRAM, which per (CFG.1) this repo has no diagnostic channel to notice.
Ablation d2 makes exactly that mistake and reddens exactly that pin.
**GATES.** Suite **16,539 / 0 / 3**; `cost_gate.py` exit 0 with **`output.programFiles` 78**
(the direct receipt that resolution still finds the same program); `huge_methods.py
--fail-over 0` clean; 8-profile `--noEmit` grid `added=0 removed=0` on all eight; `--outDir`
emit **byte-identical to the PRE-SESSION binary**, 78 files.
**THE SESSION, ONE FIXTURE AND ONE INSTRUMENT: `many-small-2400-dom` floor medians 241 -> 151
(early) and 256 -> 116 (late), -37% / -55%**, across (INC.63), (INC.64)(a)/(b) and (INC.65) —
and **UNDERSTATED**, because the box drifted ~10% slower over the session (`full` median
3,944 -> 4,335), so the floor's SHARE of a full build fell 6.1% -> 3.5%.
**AND THE NUMBER THE HOST ACTUALLY FEELS, measured through the `Project` API itself
(`scripts/incremental-cost.sh`, 2,401 files, 3 rotations):** a body edit re-answers in
**170-248 ms** (warm rotations 159-192), a comment-only edit 164-192, introducing an error
166-193 with the TS2322 correctly found, and a re-query with NO edit is **0 ms** — the memo
serves it. The narrowed build is **149-204 ms against a full build of 4,140-5,897**, i.e.
**~25-30x**, and the partition's answer agrees with the full build's row for row on every
rotation. The floor is the dominant term of that latency, which is what makes this arc the
right one for an editor host.

**SUCCESSOR (INC.66):** checker construct 38-70 (the init pass dispatch, flat — an (INC.7)
partition question), crawl WALL 34-44 (its READ half is (INC.56), the only row costing a
soundness promise), config+glob 13-29 (co-largest on some draws, NO promise attached, and
worth re-decomposing rather than assuming (INC.60) finished it). **And take the lesson
literally: before pricing any row, check it HAS a split.**

**(INC.64) — TWO ROWS PAID ON EVERY KEYSTROKE FOR WORK NOBODY READS, AND THE FLOOR IS
241 -> 146 ms OVER THE SESSION (2026-08-30).** Both found by (INC.62)'s instrument —
divide a row by its own population, refuse an impossible per-op cost.
**(a) THE CRAWL HANDED EVERY FILE TO ANOTHER THREAD TO SCHEDULE A MAP PROBE.**
`readAndScanBatch` read on `Dispatchers.IO` and then hopped to `Dispatchers.Default` for
EVERY file so a parse would never run on an IO thread — but on a warm build every parse is
a `CrawlParseCache` HIT, so the hop scheduled a ~1 us probe onto another thread, `files`
times. Reading all 2,401 files sequentially is **13-21 ms** and the flags over them
1.1-1.8, against a crawl WALL of **51-57**; priced with an ABBA-rotated synthetic arm,
**sequential 14.4 / `flatMapMerge(16)` alone 17.2 / one hop 18.5 / the shipped two hops
32.1 ms**. Only a MISS hops now; the cold crawl is untouched. `pre-parse (CPU sum)` falls
**69-81 ms -> 2.0-2.7**. **The wall could NOT resolve it** (ranges overlap, and that run's
`full` median was itself 9% slower), so the claim rests on the mechanism plus the synthetic
arm and the PIN IS A COUNT — dispatches at two program sizes: cold 5 -> 5 and 20 -> 20,
warm 0, and after one edit exactly ONE.
**(b) A `--noEmit` BUILD COMPUTED A DEPENDENCY ORDER FOR AN EMIT THAT NEVER HAPPENS —
15.0-22.6 ms, ~10% of the floor, AND IT WAS ON NO QUEUE.** `extractRelativeImports` runs
twice per file and every consumer of its product orders EMITTED output. **(INC.59)'s
finding one call deeper.** The obvious edit is wrong — a `continue` also skips
`tsFileNames.add`, which every later phase reads. **AND THE CORPUS IS A CONTROL HERE, NOT
THE GATE**: `skipEmitOutputs` is set only by `ProjectCompiler`, never by the `@noEmit`
corpus directive, so all ~13k baselines run with the branch TAKEN. The 8-profile `--noEmit`
grid (`added=0 removed=0` on all eight) and the new `-project` pins are what see it; the
EMITTING path is verified independently — an `--outDir` build of the compiler profile is
byte-identical across the two binaries, 78 files, `diff -r` clean.
**THE VALUE PIN WAS BLIND ON ITS FIRST FIXTURE AND ONLY THE ABLATION SAID SO**: named the
obvious way round (`dep` imported by `main`), dependency order and ALPHABETICAL order
coincide, so emptying the sort's edges left it green. Renamed `zdep`/`amain` so the two
orders are opposite — a pin over an ORDER needs a fixture whose expected order differs from
every order the system produces by accident.
**MEASURED (many-small-2400-dom, floor median): 241 -> 189 -> 197 -> 146 ms early and
256 -> 166 -> 152 -> 143 late** across this session's three landings, **-39% / -44%**.
**GATES.** Suite **16,535 / 0 / 3** (+7, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR (INC.65):** what is left is a PARTITION question (the init-block pass dispatch,
flat across ~400 passes) and a HOST PROMISE ((INC.56), the crawl's read half) — the era of
finding a stray quadratic in the front end may be over, which is itself worth recording.
