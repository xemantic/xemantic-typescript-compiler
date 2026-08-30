# Status

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

**(CFG.1) — A PROJECT THAT HAS EVER BEEN BUILT READ ITS OWN OUTPUT BACK IN, AND THE
CORPUS CANNOT CONTAIN A DIRECTORY (2026-08-30, found by (INC.60) on the way past).**
tsc's rule for an ABSENT `exclude` is `excludeSpecs = filter([outDir, declarationDir],
d => !!d)` (`commandLineParser.ts`); the package folders are not `exclude` entries there
at all but are pruned from every wildcard match by the matcher — which is what
`ProjectCompiler`'s own walk already does by basename. **We had the redundant half and
not the load-bearing one.** Measured against tsgo 7.0.2 on a two-file project with
`outDir: "dist"` and the artifacts a previous `--declaration` build leaves behind:
**tsgo's program is 1 file and ours was 2** — `dist` matches the default everything-include
and a `.d.ts` is a root extension — so such a project crawled, read, parsed, bound and
checked its own emitted tree **on every keystroke**, which is the incremental floor the
(INC.\*) arc has been paying down. After the fix the CLI answers `1 root, 1 in program`,
i.e. tsgo's own. An EXPLICIT `exclude` still REPLACES the default, as in tsc — pinned,
because that is the direction a "just add outDir to the defaults" implementation gets
wrong, and it is ablation arm b2. **THE DIAGNOSTIC HALF IS REAL IN tsc AND UNOBSERVABLE
HERE, WHICH IS ITSELF THE FINDING**: forced in, tsgo answers TS2451 twice for a duplicated
`declare const` and TS5011 for the moved common source directory, and **we report
neither** — so a defect that changed the PROGRAM ITSELF was invisible to every diagnostic
channel in this repo and the only observable left was a file COUNT. A value pin asserting
those codes stay absent **stayed green under the ablation that removes the whole fix** and
was deleted rather than kept (round 808). Both gaps filed as **(CHK.74)** and **(CFG.2)**.
**NOTHING HERE COULD SEE THE DEFECT EITHER**: the generated corpus materialises no
directory, and all eight dashboard profiles scope `include` to a `src` subtree under which
`dist` never matched — the grid is a CONTROL and reads `added=0 removed=0` on all eight,
as predicted before it ran. Only a `-project` fixture through `ProjectCompiler` and a
`Vfs` expresses it, the same instrument (CHK.29) needed and for the same reason.
**GATES.** Suite **16,519 / 0 / 3**; `cost_gate.py` exit 0 with every counter unchanged;
`huge_methods.py --fail-over 0` clean; 8-profile grid clean.

**(INC.60) — THE INCREMENTAL FLOOR'S THIRD ROW WAS A QUESTION ASKED TWICE PER ENTRY, AND
THE SECOND ASK COST FIVE SYSCALLS (2026-08-30).** `FrontEnd.CONFIG` — tsconfig load,
`@types` acquisition and the root-file glob — is what an editor pays on every keystroke,
and no round had separated its three pieces. Split five ways it is **~99% the glob, the
glob is ~99% its directory walk, and 60-70% of THAT is one call the walk did not need to
make**: for every entry the directory listing had just returned it went back to the
filesystem to ask "is this a directory?". tsconfig load is **0.43 ms** and `@types`
**0.01 ms** — neither was ever the row. **WHY THAT BOOLEAN COSTS 7.3-8.6 us IS IN THE
DEPENDENCY, NOT IN OUR SOURCE**: kotlinx-io 0.9.1 compiles `metadataOrNull` to
`File.exists()` + `isFile()` + `isDirectory()` + `isFile()` + `length()` — up to five
`stat` syscalls plus an allocation — on a `Path` rebuilt from the string the listing had
just produced; it is visible only by dividing the row by its population and refusing the
implied per-op cost (7.3 us is impossible for one `stat`). `Vfs.listEntries` answers the
kind WITH the listing; **its default body is literally the two calls it replaces**, so
every other `Vfs` is unchanged and correct without touching it, and `SystemVfs` overrides
it through a new `expect fun systemListEntries` (JVM: one `readdir` + one `stat` per
entry; native: the portable pair). **MEASURED, both arms this session with the same
runner: `CONFIG` 29.2-32.6 -> 11.5-16.3 ms at 2,401 files and 52.8/52.9 -> 20.7-27.1 at
4,801; per entry 9.3 -> 3.1-4.4 us, flat across both sizes** — a constant-factor win on a
linear row, with the population census (`50 dirs / 2451 entries / 2401 candidates / 2401
roots`) IDENTICAL across the change, which is the receipt that nothing was skipped to buy
it. **THE UNINSTRUMENTED FLOOR MEDIANS READ 216 BEFORE AND 222 AFTER**, i.e. the saving
sits inside the ±40% single-draw band and a wall-clock reading of this round would have
concluded the opposite of the truth — which is why the split was built before the fix.
Pinned at two layers and ablated separately: `RootGlobListingTest` (the CALL SHAPE; its
counting `Vfs` must OVERRIDE `listEntries`, or the default *is* the pre-fix sequence and
the pin is vacuous) and `SystemVfsListEntriesTest` (the JVM actual's EQUIVALENCE, whose
divergence would be silent — it includes a directory named `looks-like.ts`). a1 reddens
2 of 3 in the first and none in the second; a2 the reverse. **GATES.** Suite **16,514 /
0 / 3** (+6, exactly the new pins); `cost_gate.py` exit 0 with **every counter
unchanged**; `huge_methods.py --fail-over 0` clean. **SUCCESSOR (CFG.1), a DEFECT found
on the way**: tsc's `commandLineParser.ts:3131-3141` defaults `exclude` to
`[outDir, declarationDir]` when absent and **we implement none of it**, so a project that
has ever emitted pulls its own `dist/**/*.d.ts` back in as ROOT FILES.
