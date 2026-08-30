# Status

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

**(BIND.1) — A DIAGNOSTIC THAT APPEARED AND DISAPPEARED WITH THE BYTE LENGTH OF AN
UNRELATED FILE (2026-08-30, reported from the IntelliJ plugin).** `nodeKey(pos, end)`
carries NO file identity and positions restart at 0 in every file, yet
`Binder.nodeToSymbol` and `moduleInstanceStates` were ONE map shared by every
`BinderResult` a binder produced — so two declarations at coincident offsets in DIFFERENT
files shared a slot, last-wins in bind order. **IT IS NOT A THEORETICAL HAZARD: tsc's OWN
78 SOURCES CARRY 271 KEYS WRITTEN BY TWO OR MORE *DECLARATION* NODES IN DIFFERENT FILES**
(`watchUtilities.ts`/`moduleNameResolver.ts` variable declarations, a dozen
import-specifier pairs), and an ordinary 223-file program (one source file plus `zod` and
`@types/node`) carries 109 of them plus 4,324 shared keys overall. **THE TRIGGER IS
WHITESPACE**, which is why it reads as random: `Node.end` is the end of the FOLLOWING
token, so for a file's LAST statement it is the EOF offset — the one span trailing
newlines move — and **106 of those 223 files have a last statement that appending
newlines ALONE can drive into a collision**. Reduced to four lines: two same-length files
each declaring a merged `namespace` made `buildNamespaceScope` build the scope of the
OTHER file's namespace, so the file's own exports went missing (a false TS2304) and the
foreign file's became visible (a missing one) — **both directions, against tsc 5.9.3** —
and adding ONE character to the sibling file made it vanish. The tables are now per
`bind()`; twelve checker reads holding only a `Node` go through `nodeSymbolOf` /
`moduleInstanceStateOf`, which ask the OWNING file (INV.2(a) parent chain) and treat an
owner that recorded nothing as **null** rather than scanning the others — that scan IS the
collision. **NOTHING HERE COULD HAVE SEEN IT**: it needs two files whose declarations land
on coincident offsets, which no hand-written fixture produces by accident, so
`NodeKeyCollisionTest` hands two exact texts to the pipeline and ASSERTS the collision
precondition; ablated, its two behavioural pins go red while the precondition and the
no-collision control stay green. **GATES.** Suite **16,500 / 0 / 3** (+4, exactly the new
pins); the compiler profile still reports **46 errors on 78 files**; `cost_gate.py` exit 0
with `output.errors` and `spine.nodes` UNCHANGED — `typeOfExpr.calls` +0.54% and
`narrow.memoServed` +1.55% are the 271 collisions on that profile now resolving to the
right file, re-baselined here; `huge_methods.py --fail-over 0` clean; warning-clean.
