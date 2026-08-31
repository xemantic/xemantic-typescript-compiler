# Status

**(INC.73) — A 2.5 ms ROW, AND THE TWO REFUTATIONS THAT COST NOTHING TO FIND (2026-08-31).**
`init:moduleTypeNameIndex` — the largest single row left in the floor's per-pass table after
(INC.69)/(INC.70)/(INC.71) — is built on FIRST ASK; GO/NO-GO first, per (INC.16):
`moduleTypeNameIndexBuilds` **0 on a floor build, 1 on a full one**.
**ITS VALUE RECEIPT IS THE 8 PROFILES AND THE CORPUS IS A CONTROL — the ablation that never
builds it reddens ZERO of the ~13k baselines and 3 of the 8 profiles (+2 rows each: harness,
server, services)**, which is exactly where rounds 471 and 513 got their evidence. **A family
can have no corpus coverage at all and still be load-bearing; the way to find out is to ablate
and grid, not to reason about it.**
**AND THE HONEST PART: neither the floor wall (medians 117/124 before against 119/127 after —
no separation) nor a 2-process phase A/B can resolve 2.5 ms.** The receipts are the pass row
from the clean single-binary decomposition plus the deterministic count, and the round is
written up as the 2.5 ms landing it is.
**TWO REFUTATIONS FROM THE SAME RECON, BOTH WORTH MORE THAN THE ROW.**
**(a) `SystemVfs.exists` IS ONE SYSCALL** — 1130.7 ns/call against `java.io.File.exists`'s
1108.8, **1.02x**, ABBA inside one process over the fixture's own 2,401 paths. So (INC.60)'s
five-stat finding is specific to `metadataOrNull` and does NOT generalise; and the resolver
already probes exactly ONCE per resolution (**2,351 `exists` + 10 `isDirectory` for 2,351
distinct pairs**, because `.ts` is first in `allExtensions`), so there is no syscall lever in
the crawl's 11 ms resolution row.
**(b) `init:collectUmdGlobalsAndModuleFiles` (2.32) and `init:mergeFileLocalsIntoGlobals`
(2.06) ARE NOT DEFERRABLE**, and the reason is not their readers but their readers' SCHEDULE:
`umdGlobalNames` is read by the merge itself and `moduleFiles` by `collectModuleAugmentations`,
both LATER INIT PASSES that run unconditionally. Combined prize ~5 ms of a 94 ms floor —
refused on arithmetic.
**GATES.** Suite **16,568 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% (including
`typeNode.bypassed` 145,723, the direct receipt that `multiFileModuleTypeNames` answers
identically); `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR:** the init dispatch has no non-walker row above ~1.4 ms left, so what remains
there is (INC.7)'s partition question one walker at a time; **the floor's largest row is the
CRAWL and its READ half is (INC.56)** — now the only row left with a double-digit prize, and
the one an IntelliJ-class host can simply hand us.

**(INC.72) — THE SURPLUS WAS THE CRAWL, AND BOTH OF THIS SESSION'S WALL FIGURES ARE RETRACTED
(2026-08-31).** (INC.70) and (INC.71) each reported an ABBA-rotated floor wall about **three
times** what their pass row explained, and that gap was queued as a mechanism to hunt. It was
not a mechanism. Running the SAME two binaries with the per-PHASE instrument — two processes
per arm, rotated, second instrumented draw — attributes the change and nothing else:
**init-block pass dispatch 39.87 -> 25.06 ms (-14.81)**, which is what the two pass rows said,
while the UNTOUCHED **import-graph crawl swung +18.01** in the same run, its
elapsed-with-suspension `read+decode` sum moving **147.8 -> 249.9 ms**. Every other phase is
flat to within 0.7 ms.
**So (INC.70)'s "160.0 -> 136.5 (-23.5)" and (INC.71)'s "142.5 -> 120.0 (-22.5)" are each one
batch's reading of a quantity carrying a ±20 ms concurrent term; the same binaries read
128.5 -> 116.5 in this round's batch. What ships is -14.81 ms of init-block dispatch,
phase-attributed, and that is the number to carry.**
**THE LESSON IS NOT "ROTATE MORE" — IT IS "PICK AN INSTRUMENT WHOSE VARIANCE DOES NOT CONTAIN
THE ANSWER".** (INC.68) showed a BLOCKED batch inventing a delta that rotation removed; this is
the next step out — a ROTATED batch of a COMPOSITE quantity still cannot separate two of its
terms, and 4 processes x 8 draws per arm did not help, because the noise is a real, large,
unrelated phase rather than run-to-run jitter. For a checker-side floor change the receipt is
now `FrontEnd`'s phase row plus the deterministic population count; the floor wall is a sanity
check. `FloorAbMain` grows an `fe` mode so that decomposition is a two-BINARY A/B.
**SESSION TOTAL, re-taken on the SAME INSTRUMENT rather than inferred from the A/B arms —
`scripts/floor-decomposition.sh`, same fixture, same warm-ups, same `PLAIN late` slot: the
2,401-file `dom` floor is 122 -> 94 ms (`PLAIN early` 144 -> 105).** Two runs of one recipe
have no arm-rotation problem to get wrong, which is (INC.72)'s lesson applied to the
REPORTING. **The ranking has changed and the next round must start from it: the CRAWL is now
the largest floor row (29 ms, 36%) for the first time in this arc — its READ half is (INC.56),
the one row costing a soundness promise and the one an IntelliJ-class host can hand us — with
the init-block dispatch at 22 (28%), config+glob 12, bind 8, post 5.** The pass table is
**22.43 ms over 418 rows**, headed by three whole-program INDEX builds
(`init:moduleTypeNameIndex` 2.52, `init:collectUmdGlobalsAndModuleFiles` 2.32,
`init:mergeFileLocalsIntoGlobals` 2.06) — none of them a per-file table, so the
(INC.70)/(INC.71) deferral shape does not transfer unchanged, and the GO/NO-GO for each is
(INC.16)'s counter: who forces the index, and is it anyone on a floor build?

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
