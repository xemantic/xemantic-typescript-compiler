# Status

**(INC.57)+(INC.58) — THE FRONT END WAS QUADRATIC IN FILE COUNT **TWICE**, AND NO PROFILE
HERE COULD EXPRESS EITHER; THE PER-KEYSTROKE FLOOR OF A 2,401-FILE PROJECT GOES
**1,653 -> 366 ms** (2026-08-30).** Working (INC.56) — *"skip the re-read, THE LARGEST
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

**(INC.52) — THE INCREMENTAL FLOOR'S DEAREST PASS STOPS WALKING EVERY FILE'S SYMBOL
TABLE, AND ITS PRICE IS BELOW WHAT THIS REPO CAN MEASURE (2026-08-29).** With project
diagnostics incremental ((INC.46)) and restart-proof ((INC.48)), what an editor pays per
keystroke is the FLOOR. Decomposed: **68 ms**, of which the checker is **42 ms (67%)** with
nothing to check, and the largest pass in both draws is `init:computeAllEnumValues` — whose
second loop visited EVERY file's `locals` and recursed through every namespace's `exports`
to find the program's enums. `BinderResult.bindsEnum` answers that from the bind that
already happened: an identity, not an approximation, since `bindEnumDeclaration` is the one
site minting a conventional enum symbol and `enumValues` is ID-keyed. **MEASURED AS A
POPULATION, from ONE binary with the verify arm as the "before": 12,871 top-level symbol
visits -> 8,676 (-32.6%)**, plus every namespace recursion beneath the **45 of 78** files
skipped, with `localsSkipViolations = 0` over a non-empty skipped set. **AND THE TIME IS NOT
RESOLVABLE, WHICH IS THE PART WORTH KEEPING**: the row that motivated the round read 13.16
ms in one draw and **8.42 ms in the next draw of the same binary**; after the change, 7.27
and 9.66; the floor wall reads 68 before and 74 after with draws spanning 57-86. So it is
landed as a WORK REDUCTION with a control and no millisecond is claimed — a single-draw
per-pass row on a 68 ms floor is not a measurement, and that is now a CLAUDE.md entry
because the next agent will read the same table and reach for the same row. **GATES.** Suite
**16,485 / 0 / 3** (+2, exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py
--fail-over 0` clean; warning-clean.

**(INC.48) — THE INCREMENTAL STATE OUTLIVES THE PROCESS, AND A RESTART IS **60x**
(2026-08-29).** (INC.46) made project-wide diagnostics incremental within a process and
every bit of that state died with it: an IDE restart, a plugin reload or a daemon recycle
paid a whole-program build for a tree nobody had touched. `Project.saveState()` encodes
what has to survive — export signatures, escapes, the program's file list, that build's
diagnostics and a content hash per input — and `restoreState()` adopts it, so the next
process starts at the (INC.46) gate instead of at a rebuild. **MEASURED on tsc's own 78
sources, every arm asserted to agree ROW FOR ROW**: warm, **5,855 ms -> 94 ms (62x)**
clean and 259 ms (23x) with a file changed on disk; in a **COLD process — which is what a
restart actually is — 9,625-9,844 ms -> 155-175 ms (~60x)**, the snapshot being **47 KB**
for a 78-file project. The cold column is the one that matters and it is nearly as good as
the warm one, which was not obvious: an IDE restart pays the JIT ramp, and (INC.49)
attributed ~18 s of a 23 s first query to exactly that — but the ramp barely touches a path
that never checks the whole program. **IT WRITES NO FILE**: `encode`/`decode` answer and
take a string, so the host decides where its caches live; the CLI's `--incremental`
(`tsconfig.xtsbuildinfo`, INV.7(d3)) remains the convention for callers who want the other
one. **EVERY PART OF THE CLAIM IS CHECKED, because skipping any of it is a stale answer**:
the compiler build id (never a `.dirty`/`unknown` one — two dirty trees share an id without
sharing behaviour), the config path, a CONTENT hash per file (never mtime — round 871), and
the `.json` INPUTS as well as the sources, since a changed tsconfig or a `package.json`
whose `type` decides a module format makes every stored row suspect rather than one file's.
**AND THE STALENESS CASE NO HASH CAN SEE HAS ITS OWN MECHANISM**: a file ADDED while the
process was down is in no stored hash and no stored list, so a restored state is not
trusted until a build has re-crawled and found the same program — even a clean project runs
the gate once, with an EMPTY partition. Ablated, the naive "trust the snapshot" version
reddens exactly two pins and nothing else. **GATES.** Suite **16,483 / 0 / 3** (+13,
exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean;
warning-clean.
