# Status

**(INC.71) — THE PER-FILE VISIBILITY SETS, AND A FLOOR WALL THAT KEEPS OUTRUNNING THE PASS
TABLE (2026-08-31).** `init:computePerFileVisibility` walks every program file's `locals` to
publish `moduleOnlyGlobalNames` and `libValueShadowNames`, whose only three readers —
`globalsForFile`, `globalsForFileNode`, `libValueBehindTypeOnlyShadow` — are all NAME
RESOLUTION. So a build that checks nothing reads neither.
**THE POPULATION DECIDED IT BEFORE ANY IMPLEMENTATION, for the price of one temporary
counter: 0 asks on a floor build of the 2,401-file fixture against 335,881 on a full one.**
(INC.16)'s law used as a GO/NO-GO rather than as a post-hoc explanation.
**THE ORDERING CLAIM WAS CHECKED**: the pass compares `globals.keys` against
`init:snapshotPreAugGlobalKeys`' snapshot, and all three writers of `globals` run at earlier
init steps. **The one place it is deliberately NOT lazy is the probe** — the INV.3(a)
classifier is still installed at the pass's moment and FORCES the sets from inside its lambda,
so `globals.lookups` reads 783,383, **+0.00%**.
**MEASURED:** row **-> 0.002-0.003 ms** from 5.5-7.2; ABBA-rotated floor
**142.5 -> 120.0 ms (-15.8%)**.
**THE VALUE RECEIPT IS THE CORPUS, AND THAT IS NOW A RULE RATHER THAN AN ACCIDENT:** ablation
c2 (sets stay empty) reddens **492** core tests, while the hand-written `-project` value pin
stays GREEN — the second round running where a `-project` pin cannot discriminate the
mechanism and the corpus discriminates it in the hundreds. For the INV.3 visibility model the
`-project` pins gate the REGIME (which builds do the work) and the corpus gates the ANSWER.
**GATES.** Suite **16,565 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR IS A MEASUREMENT QUESTION, NOT A ROW ((INC.72)):** twice in a row the rotated
floor WALL moved about **three times** what the pass table explains (-23.5 against ~4 ms,
-22.5 against ~7). Both changes also removed thousands of RETAINED allocations per build,
which round 801 says is a plausible mechanism and not a measured one. Decompose BOTH arms with
`--frontEnd` before opening another init row: either the surplus is outside the init block, or
the `rows`-tier probe under-reports and every ranking taken from it needs re-reading.

**(INC.70) — EVERY BUILD ALLOCATED A NAME-RESOLUTION TABLE FOR EVERY FILE, AND A FLOOR BUILD
READS NONE OF THEM (2026-08-31).** `init:buildPerFileScopes` allocated two maps per program
file, copied that file's own top-level locals into one and precomputed a
`LayeredSymbolTable`'s shadow list — for EVERY file, on EVERY build, whether or not a name was
ever resolved there. **THE POPULATION WAS MEASURED BEFORE ANY TIMING, per (INC.16):
`perFileScopeBuilds` is 2,401 -> 0 on a floor build of the 2,401-file fixture and 2,401 ->
2,401 on a full one.** Not "fewer" — none.
**WHAT MAKES THE DEFERRAL EXACT IS AN INIT-ORDER FACT NEITHER FUNCTION STATES**: the eager
loop SNAPSHOTTED `result.locals` precisely to survive a later mutation, and the checker's ONE
writer of a `BinderResult.locals` is `collectModuleAugmentations`, dispatched at an EARLIER
init step — so the two snapshots are the same table. A writer scheduled after this pass would
make the eager and lazy answers disagree silently.
**MEASURED:** row **4.625 -> 0.750 ms** (second instrumented draw), whole init block
39.34 -> 36.38; ABBA-rotated floor **median-of-medians 160.0 -> 136.5 ms (-14.7%)**, four
process medians DISJOINT. **The wall delta is larger than the row explains (~4 of ~23 ms) and
the surplus is recorded as UNATTRIBUTED, not claimed** — the eager form also retained ~4,800
maps per build, which is a plausible mechanism and not a measured one (round 801).
**THE VALUE HALF IS A MEASUREMENT, NOT AN ASSUMPTION:** ablation b2 (never build a scope)
reddens **503** core-suite tests.
**AND THE THIRD ARM IS RECORDED AS BLIND, which is the round's second finding:** b3 (never
STORE the built scope) reads 0 RED even after the fixture was strengthened, because
`perFileScopeOf`'s one-entry IDENTITY memo absorbs every repeated ask for the same file — so
the map's memoization is pinned by nothing here, and the reason is a second cache one layer
up. Likewise the value pins do not discriminate `perFileScope`'s presence at all: under b2 the
module-local leak is STILL TS2304, because `moduleOnlyGlobalNames` decides that upstream.
**GATES.** Suite **16,559 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0` — COVERAGE here, since
an absent scope makes `perFileScopeOf` answer null and every consumer falls back to the merged
`globals`, i.e. a name resolving to a FOREIGN module's local.
**HARNESS TRAP WORTH THE LINE:** a cross-binary A/B runner may read no census counter that
does not exist in BOTH arms — the older arm dies with `NoSuchMethodError` and the batch prints
one arm's medians as if they were both.

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
