# Status

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
**(INC.57)+(INC.58)+(INC.59) — THE FRONT END WAS QUADRATIC IN FILE COUNT **THREE TIMES**,
AND NO PROFILE HERE COULD EXPRESS ANY OF THEM; THE PER-KEYSTROKE FLOOR OF A 2,401-FILE
PROJECT GOES **1,653 -> 279 ms (5.9x)** (2026-08-30).** Working (INC.56) — *"skip the re-read, THE LARGEST
REMAINING FRONT-END ROW"* — its own entry demanded the prize first be re-measured on "a
project with MANY SMALL files rather than tsc's 78 huge ones". That measurement **refuted
the premise and found two independent quadratics beside it**, in different subsystems.
**(INC.58), found by (INC.57)'s own successor instrument (divide the floor pass table by
file count at two sizes): `checkJsxImportResolutions` was **709.74 of a 774.65 ms floor
pass table — 92% — on a project containing NO JSX**, growing 14.6x for 4x the files.**
`resolveJsxTsxCandidate`'s path-suffix fallback walked every file of the program once per
import specifier per extension, and the pass is gated on `--jsx` being **UNSET** — maximum
work on precisely the projects that never use JSX, always answering null. Restricting it to
the `.jsx`/`.tsx` subset is EXACTLY equivalent (every non-null return is such a file; order
preserved, so the FIRST match is unchanged) and takes it to **0.30 ms, linear**.
**(INC.54)(a) had ranked that pass at 1.2 ms from the tsc profile — 600x, so a published
RANKING and not merely a price was invalidated.** A pin lesson from it: the first value pin
went RED on a WORKING binary because a relative specifier is served by an O(1) probe and
never reaches the scan — **an assertion about WHICH path served an answer is not implied by
the answer being right**; there are now two value pins, one per path. Suite **16,503 / 0 /
3**; both gates clean with every cost counter unchanged.
**AND (INC.59), THE THIRD — FOUND BY RE-READING THE FLOOR RATHER THAN TRUSTING THE
RANKING, WHICH IS THE REUSABLE HALF OF THE WHOLE SESSION.** After two rounds had
reordered it twice, `post-checker` had become the LARGEST row — 166-189 ms of a 366 ms
floor (~48%) — and **appeared in no queue item at all**. One expression:
`parsedSourceFiles.filter { it.key !in transformOrder.toSet() }`, with `.toSet()` INSIDE
the lambda, so an N-element set was rebuilt once per entry of an N-entry map — **in the
`--noEmit` path**, i.e. a build that emits nothing was spending 175 ms per keystroke
preparing an emit order it would never use. `POST_EMITPREP` **158.5-175.3 -> 1.8-2.8 ms
(~70x)**; floor 366 -> **279 ms**. Suite **16,504 / 0 / 3**, both gates clean, counters
unchanged. **VERIFIED AT MONOREPO SCALE:** a fourth size (4,801 files) reads a **428 ms
floor against 279 at 2,401 — **1.53x for 2x the files, i.e. SUB-linear**, the cleanest
evidence the quadratics are gone rather than reduced. The two rows still above 2.0x are
single-digit-to-teens ms and sit at or below the noise ((INC.52) read one floor row at
13.16 and 8.42 ms in two draws of ONE binary), so **this class is exhausted at these
sizes** — what made the three findable is that they were 14.6x / 21x / 4x-per-doubling,
one to two orders clear of that band. **SUCCESSOR (INC.60):** `config load + @types +
root glob`, 52.8 / 52.9 ms at 4,801 — two draws **0.2% apart**, the one floor row
measurable without fighting the noise, and it carries no soundness promise where
(INC.56) does.

**AND (INC.57), THE ONE THAT STARTED IT:** `extractRelativeImports` opened with
`allFiles.map { it.fileName }.toSet()` — a fresh list AND set of every program file name —
and the emit-order scan calls it **TWICE per file**: `2 x files^2` string hashes per build,
plus two sibling `parsed.files.any { … }` scans in the same loop. On generated
application-shaped projects (`scripts/gen-many-small-project.py`) the `FrontEnd.IMPORTS`
row grew **4x for 2x the files — 18.9 / 76.3 / 331.6 ms at 601 / 1201 / 2401** — which at
2,401 files is 11.5 M hashes and ~92 MB of garbage on every keystroke. **WHY ~950 ROUNDS
MISSED IT, and it is now a CLAUDE.md entry:** all eight dashboard profiles are ONE
codebase, tsc's own sources at **78 files averaging 128 KB**, where `2 x 78^2` vanishes —
**a cost that is per-FILE rather than per-BYTE is structurally inexpressible on that
shape**, and nothing here had ever been pointed at the opposite one ((INC.9)'s regime law
on a new axis: the SHAPE of the corpus). **The fix is a HOIST, not a cache** — `parsed.files`
is a `val List` on a data class, so the set is loop-invariant by construction and there is
no invalidation story; `.toSet()` is kept verbatim so the container and any iteration order
stay bit-for-bit what the per-call expression produced. IMPORTS -> **5.8 / 7.1 / 16.1 ms**,
per-file cost FLAT where it had been doubling; floor medians 165 -> 142, 409 -> 359,
**1653 -> 1035 ms**. **PINNED AS A COUNT** (`programNameSetBuilds == 1` at 10 files AND at
100) because the claim is about COMPLEXITY and only a count can state one, plus a VALUE pin
on dependency-first emit order. **ONE ABLATION ARM, TWO ANSWERS:** the count pins go RED
reading exactly **20** (`2 x files`) while the value pin stays GREEN; and all **20**
`cost_gate.py` counters are IDENTICAL between arm and HEAD, so this round is provably
counter-neutral and the gate's +0.54%/+1.55% is drift from the **60 commits** since the
baseline was recorded at (CHK.63) — deliberately NOT rebaselined, since folding sixty
commits of unattributed drift into this one would make it un-auditable. **(INC.56) is
re-ranked, not refuted as a saving: it is FOURTH** (crawl wall 25-38 ms of a 409 ms floor)
and the only one of the five costing a soundness promise. **SUCCESSOR (INC.58):** the
`Checker` init-block pass dispatch is itself super-linear — 73-91 / 204-217 / 756-810 ms at
601 / 1201 / 2401 files, ~73% of the floor. **GATES.** Suite **16,499 / 0 / 3** (+3, exactly
the new pins); `cost_gate.py` exit 0, `output.errors` 46, `spine.nodes` 856,962;
`huge_methods.py --fail-over 0` clean.

**(INC.55) — A HOST CAN NOW CANCEL A BUILD, WHICH IS THE CAPABILITY AN IntelliJ PLUGIN
NEEDS AND NO LATENCY WORK CAN REPLACE (2026-08-30).** Asked to judge the language service
as "the best support one could get inside an IntelliJ platform IDE" rather than as
"incremental", the top of the list changes: there was **ZERO cancellation** anywhere in
the compiler or the `Project` API. A build runs on the compiler's own deep-stack thread and
`Project` JOINS it, so the caller is blocked for its whole duration and cannot abandon it
from outside — while `DaemonCodeAnalyzer` restarts analysis on every write action. Without
this an editor must either block a pooled thread producing an answer it has already
discarded, delaying the next one behind it, or not run the analysis in a highlighting pass
at all. `Project.cancellation` takes a `CancellationSignal` (on the platform,
`{ indicator.isCanceled }`), polled at every `pass("…")` boundary AND every **1024 spine
nodes** — the second is what keeps a large buffer's walk (1.65 s on tsc's own `checker.ts`)
interruptible, and the hot loop's own comment refuses interleaved work, so the poll sits
behind a counter (837 volatile reads for 856,962 nodes). **IT IS AN `Error` DELIBERATELY**:
the checker, crawl and `Vfs` carry defensive `catch (Exception)` guards, and a cancellation
they could swallow would let the build continue with a missing file — silently wrong, worse
than not cancelling; `Error` is safe because the 2026-07-04 sweep left no `catch (Throwable)`
anywhere, which is pinned rather than trusted to a KDoc. **STATE SAFETY IS BY CONSTRUCTION**
— every cache assignment in `Project` happens after `build` returns, so a throw skips all of
them — pinned both at the first poll and MID-flight. **THE PIN THAT ALMOST DIDN'T
DISCRIMINATE**: the spine-poll test first compared a 3-file fixture with a 1-file one and
FAILED, because the `pass()` poll count is not constant across programs (405 vs 418) and
swamped the spine's ~12; holding file count and shape fixed and varying only SIZE reads ~417
against ~526. **GATES.** Suite **16,496 / 0 / 3** (+7, exactly the new pins); `cost_gate.py`
exit 0 with **`spine.nodes` UNCHANGED at 856,962** and `output.errors` flat at 46 — the poll
is inert when unarmed; `huge_methods.py --fail-over 0` clean. Also documented: the threading
rule is a CONFINEMENT rule (Symbol/Type ids are thread-local, so two threads on one
`Project` corrupt an id space with no diagnostic), and the GraalVM/AOT/CRaC artifact levers
do NOT apply to a plugin running in-process on the IDE's own JVM.

**(INC.53) — THE INCREMENTAL FLOOR'S LARGEST BLOCK WAS NEVER IN A PASS, AND ~950 ROUNDS OF
INSTRUMENTS COULD NOT SEE IT (2026-08-29).** The floor is what an editor pays per keystroke,
and 32-44 ms of its 63-72 ms is "checker construct + getDiagnostics". Split for the first
time: **`getDiagnostics()` is 2-3 MICROSECONDS**, so the whole phase is the CONSTRUCTOR — and
~20 ms of it is the class's **~494 property initializers**, a constant that reads the same on
a 63 ms floor build and a 5.2 s full one. That is 0.4% of a full compile, which is exactly why
no round noticed, and **~30% of every language-service query**. **A FIELD INITIALIZER IS NOT A
`pass("…")`**, so it contributes to no `--passTiming` row, no `cost_gate.py` counter and no
diagnostic: the whole pass-gating arc ((INC.7)/(INC.20)/(INC.21), 189 walkers) swept loop
headers and structurally could not reach it. **FOUR initializers are essentially all of it**
(the other ~490 are 0.2-1.2 ms between them — 494 allocations cannot be 20 ms, which is what
said a handful were doing whole-program work). Three were whole-program indices with exactly
ONE read site each and now build on FIRST ASK: `localTypeAliasIndex` becomes a per-FILE index
over that file's own frozen statements, in the same DFS order and first-wins per name, the
other two `lazy(NONE)`. **Floor field region, four draws each side: 18.6 / 25.4 / 29.6 / 30.2
ms -> 8.1 / 12.6 / 8.4 / 11.2**, with all three rows reading 0.00 ms and 0 files on a floor
build; even a FULL build needs only **69 of 78** files' alias index. Claimed as a WORK
REDUCTION, not a millisecond ((INC.52)'s law — the same binary reads 13.16 and 8.42 ms for one
row in two draws), so `EagerIndexCensus` counts the population. **THE FOURTH IS REFUSED WITH
ITS PRICE**: `parseBuiltinLib` splits three ways with no dominant part (binds 3.2-5.3 ms, decl
walk 1.9-2.8, resolution + 45 `mergeSymbolTable` 3.1-5.3) — and the round-471 hypothesis that
the data-class-keyed node sets dominate is MEASURED WRONG. Its two larger parts are
per-checker by requirement (the checker mutates lib symbols), so round 884's `mergedSymbols`
clone-on-write is the named unblocker. **GATES.** Suite **16,489 / 0 / 3** (+4, exactly the
new pins); `cost_gate.py` exit 0 with `output.errors` flat at 46; `huge_methods.py
--fail-over 0` clean, and `Checker.<init>` shrank **5,538 -> 5,464** bytecodes, buying back
(JIT.1)(d) headroom.
