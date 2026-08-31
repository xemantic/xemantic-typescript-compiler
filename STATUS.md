# Status

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

**(INC.63) — EVERY KEYSTROKE RE-DERIVED THE WHOLE LIB, AND THE HALF THE STANDING REFUSAL
NAMED WAS 3% OF IT (2026-08-30).** (INC.62) asked for the floor on a `dom` fixture before
opening any row; taken, and the largest single addressable row is `parseBuiltinLib` at
**46-50 ms of a 241-256 ms floor**, stable to ~1% across draws where everything else swings
40%, and **O(1) in program size — so it is a BIGGER share the smaller the project**, i.e.
precisely what an IDE-hosted application pays per keystroke. It was invisible at
`"lib": ["es2020"]`, where the same row is 8-11 ms. **(INC.54)(c) had REFUSED it whole,
"BLOCKED on round 884's `mergedSymbols` clone-on-write"** — true of the BIND, which
measures **1.4 ms**. The other 97% is two pure functions of the SHARED parses:
`RealLibResolver.resolve`, whose `/// <reference lib=…/>` closure regexes ~3.7 MB of lib
text and which `bindRealLibs` called **TWICE** per construction (~32 ms), and the
B85.2/M2.2 decl-set walk, ~30k puts into containers keyed by **data-class AST nodes** —
round 471's deep `hashCode` at a scale the es2020 fixtures could not express (~15 ms).
**THE ARITHMETIC NAMED THE MECHANISM BEFORE ANY BUILD**: ~500 ns per `HashMap` put with a
`String` value is 20-40x impossible, which is (INC.62)'s own instrument and the fifth
defect it has found. The recorded split mis-attributed the resolve because it sits INSIDE
the `bindLibFiles` section — `bindLibFiles` **17.4 -> 1.4 ms** is that regex, not a bind.
**A REFUSAL THAT NAMES A BLOCKER MUST CHECK THE BLOCKED HALF IS WHERE THE COST IS.**
**MEASURED (many-small-2400-dom, both arms this session):** `parseBuiltinLib` 47.1 -> 1.65,
50.1 -> 1.69, 46.2 -> 1.46 ms; the decl-set walk 12.0-15.9 -> **0.01**; checker construct
99 -> 55 / 97 -> 44 / 84 -> 43; **PLAIN floor median 241 -> 189 (early) and 256 -> 166
(late)**, the early arm's ranges disjoint. **GATES.** Suite **16,528 / 0 / 3** (+5, exactly
the new pins); `cost_gate.py` exit 0 with all 20 counters +0.00% (the EXPECTED answer — a
CLI compile builds one checker, so a hoist within one construction is a no-op there);
`huge_methods.py --fail-over 0` clean; 8-profile before/after BINARY grid `added=0
removed=0` on all eight, run and LABELLED as a control (the index is a function of the lib
set alone and the eight profiles share one — the corpus, thousands of compiles in one JVM,
is what discriminates the sharing). Ablation: three arms, each reddening exactly the pin it
names, with the embedded-lib negative control green in all three.
**SUCCESSOR (INC.64):** the init-block pass dispatch (40-53 ms, FLAT — an (INC.7)-style
partition question, not a micro-optimisation) and the crawl WALL (51-57 ms, (INC.56), the
only row costing a soundness promise) are now co-largest.

**(INC.61) — THE WHOLE (INC.\*) ARC HAD BEEN MEASURING THE CHEAP `lib`, AND THE FLOOR'S
LARGEST PASS IS NOW 45x SMALLER (2026-08-30).** Re-reading the floor after (INC.60) —
(INC.59)'s own lesson, applied a second time — put **123 of the checker's 137 ms in the
init-block pass dispatch**, whose per-pass table no round had read on the many-small shape
since (INC.58) proved the tsc-profile ranking wrong by 600x. Its largest row was
`init:buildPerFileScopes`, which copies the SHARED half of a file's scope — lib globals,
script-file locals, global augmentations — into a fresh table **per file**, i.e.
`files x libGlobals` insertions. **THEN THE FIXTURE ITSELF TURNED OUT TO BE THE
UNDERSTATEMENT:** it pins `"lib": ["es2020"]` (~185 names) where an ordinary project's
unset `lib` means **`dom`** (~2,242). Copying the fixture and changing **that one line and
nothing else** takes the pass from **13.5 ms to 175.6 ms** on the same 2,401 files — 70%
of the whole floor pass table. So (INC.57)'s law that a profile's FILE SHAPE can make a
cost inexpressible holds equally for its **compilerOptions**, which CLAUDE.md had recorded
once for a library baseline ((CHK.49)) without the general conclusion being drawn.
**THE FIX IS AN OVERLAY, NOT A CACHE** — the base is the same object for every file, so it
is built once and `LayeredSymbolTable` answers `own[k] ?: base[k]`. **Its ORDER is the
load-bearing half**: three consumers iterate a per-file scope, and a `LinkedHashMap` keeps
a shadowed key's ORIGINAL position, so a shadowing local must appear there carrying the
OWN value rather than being appended — the one thing an implementation gets wrong, and
the only pin ablation c1 reddens (**the 16,523-test corpus would not have caught it
either**, since order reaches only cost counters and suggestion ordering). Mutators throw
rather than silently dropping a write. **MEASURED (dom arm, 2,401 files, both arms this
session): the pass 175.64 -> 3.90 ms (45x), init dispatch 334 -> 42, checker construct
393 -> 83, floor phase total 503 -> 200, and the PLAIN floor median 385 -> 202 ms** —
worth its own line, because (INC.60)'s 16 ms sat inside the ±40% single-draw band with the
WRONG SIGN and this one is far outside it, so here the wall corroborates the row instead
of contradicting it. **GATES.** Suite **16,523 / 0 / 3** (+4, exactly the new pins);
`cost_gate.py` exit 0 with every counter unchanged; `huge_methods.py --fail-over 0` clean;
8-profile grid `added=0 removed=0` on all eight, run deliberately because this is the
checker's name-resolution substrate. **SUCCESSOR (INC.62): re-take the floor on a `dom`
fixture before opening any of its rows, and treat that as the default shape from here.**
