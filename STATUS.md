# Status

**(INC.90) — THE tsgo INCREMENTAL COMPARISON RE-TAKEN ON A SECOND ARM THAT IS FINALLY
LIKE-FOR-LIKE, AND THE SIGNATURE CLIFF REOPENS (INC.35) (2026-09-01).** Every tsgo incremental
number this repo had published came from ONE arm — tsc's 78 huge barrel-exporting sources,
where we report **46** rows against tsgo's **65**. New arm: `many-small-2400-dom`, 2,401 files
in 48 layers, edited at `layer00` (deepest-dependency worst case), where **both compilers
report the identical single row**, so the equivalence gate this comparison always lacked
passes exactly.
**ARM A DID NOT MOVE** (ours 5,523 warm / 226 body / 5,578 signature against the recorded
5,352 / 232 / 5,694) — expected, since the ~25 (INC.\*) rounds since removed per-FILE costs
and that profile has 78 files.
**ARM B IS THE FINDING.** (INC.35) closed the reverse-dependency closure on tsc's sources, and
Arm A corroborates it FOR TSGO TOO (signature edit **1,695 ms against its own 1,667 cold** —
its pruning recovers nothing). On LAYERED code the same mechanism is worth almost everything:
tsgo **304 ms against its own 427 cold**, i.e. a signature edit costs it what a body edit costs
(297), where we rebuild at **3,850**. **12.7x wall, ~96x marginal — the largest gap ever
measured here, and the only one with a named mechanism on the other side.** Queued (INC.91).
**BOTH NUMBERS, BECAUSE ONE GETS IT WRONG:** on the wall we answer a body edit in **137 ms
against 297** and a no-op in **0 against 264**; but tsgo's floor is 89% of its own body cell,
so its MARGINAL body cost is ~33 ms against our ~137. We win the wall on the live-session
model; they win the compute on a real invalidation algorithm.
**THE PLUGIN'S OWN CALL IS IMMUNE TO THE CLIFF** — `incrementalDiagnostics()` is reached from
`diagnostics()` and nowhere else, while the plugin asks `diagnosticsOf` exclusively (narrows at
the SOURCE): **93-106 ms on Arm B, 187-217 on Arm A, independent of edit shape**, corroborating
(INC.86)'s 90 ms per-keystroke figure.
**THREE HARNESS DEFECTS, AND THE FIRST IS THE ONE TO REMEMBER:** the inherited fixture's
`orig.ts` was CRLF while both edit variants were LF, so every "one-line edit" was that line plus
a 3,916-line newline normalisation; the tsgo harness read its row count out of a subshell and
printed stale values; both harnesses were hardcoded to `binder.ts` and to scratchpad paths that
survived by luck. All three fixed, plus two receipts the old runner could not print (a per-cell
row count, and served-vs-fell-back from `Project.incrementalAnswers` — both arms read body 3/3,
signature 0/3).
**AND CLAUDE.md's ROUND-938 CLAIM IS FALSE:** pristine `typescript@6.0.3` IS runnable here and
agrees with tsgo on all 65 rows, so the gap is **19 genuine false negatives of ours, 0 tsgo
divergences** — but 18 of 19 are emission-side on work already done, so it is not a 29% work
gap. `docs/perf/tsgo-diagnostic-gap.md` (new), `docs/perf/incremental-vs-tsgo.md` (rewritten).
Suite **16,677 / 0 failures / 3 skipped**.

**(INC.89) — THREE INHERITED REFUSALS RE-DERIVED, ONE PLUGIN-FACING API MEMBER PINNED, ONE
SPLIT LANDED (2026-09-01).** (INC.88) left a standing instruction — "anything larger needs the
refusals re-derived rather than inherited" — and the first half of this round is that, on
reading alone. Fresh baseline, TWO processes ((INC.52)): WALL **102/106 ms**, init block
**40.0/48.2**, head `init:buildFileLocalTypeMaps` **21.4/15.3**, `init:buildPerFileScopes`
**5.7/5.5**, `init:computeAllEnumValues` **4.8/4.5**.
**THE THREE ANSWERS DIFFER FROM EACH OTHER, WHICH IS THE ARGUMENT FOR DOING IT.**
`buildFileLocalTypeMaps` is **CONFIRMED** — it is partition-scoped already, builds ONE file's
map (`eagerBuilds=1`), and the ms is that file's first real type-resolution cascade; do not
re-open it from its size. The two un-Boyer-Moore-able whole-program regexes in
`collectUmdGlobalsAndModuleFiles` are **already censused honestly** ("0 ms — 0 `.d.ts` files.
LATENT on a `@types` tree"). And `isModuleFile`, recomputed **≥5 times per build** across the
init setup block, is **REFUTED as a lever with no build at all**: it early-returns on the FIRST
import/export, which every file of a module-shaped project has. A repetition count is not a
cost — round 801's law one predicate over.
**(a) `Project.reloadFile` AND `OverlayVfs.revert` ARE NOW PINNED** — the API (INC.75) tells the
IntelliJ plugin to adopt, documented as a first-class third change kind and present in the suite
only as a step inside two `trustFilesystem` tests, with its implementation half unpinned
entirely. 17 pins, no production code changed. **TWO ablations, because one cannot grade both
halves**: emptying `reloadFile` reddens 7; emptying `revert` reddens 12 — all 7 revert pins plus
the 5 reload VALUE pins, cross-validating that reload's promises flow through `revert`. A doc
edge was pinned rather than waved through: for a file existing ONLY in the overlay, "what is on
disk is the truth" means **ABSENCE**, so reload removes it.
**(b) THE (INC.20) SPLIT FOR `checkCrossFileUseBeforeDeclaration`** — its emitter walked all
2,401 files to produce rows `getDiagnostics()` then dropped, because the diagnostic is anchored
at the USE file and the partition filter discards the rest. **The ordinal invariant is the whole
risk**: the verdict compares `decl.fileIdx > useFileIdx`, both ordinals of `binderResults`, so
re-heading on `checkedResults.withIndex()` renumbers `useFileIdx` to ~0 and flips the verdict
toward FALSE POSITIVES silently. The head therefore stays `binderResults.withIndex()` and the
partition is a `continue` AFTER the enumeration. Receipt is a COUNT, never a millisecond.
Ablation reddens **pin 6 only**, and the other six are recorded as structural rather than
claimed as coverage.
**(d) THE BIGGEST PLUGIN-FACING LATENCY ITEM ON THE PAGE IS NOT A DEFECT WORTH FIXING — IT IS
THE FLOOR.** `docs/language-service.md` § 13's "one open defect" was that
`completionsAt`/`signatureHelpAt` cannot reach a prepared check (207 ms after `prepare(6)`
against 194 cold). Both refusals standing in front of it were re-derived at HEAD and **both
hold; (INC.33) is FIRMER than when written** — break-even **1.40 -> 1.52** and **12.1 -> 12.9**,
*because* the floor arc cut the base while per-anchor capture did not; retention unchanged to
the digit (**54.4 M** records for one widened `checker.ts` entry). The queue's own named
successor, the PREPARE-AMORTISED case, is **REFUTED BY MECHANISM**: the typed `.` must reach
`updateFile` or the completion anchor is computed from stale text, and `updateFile` does
`captures.clear(); prepared = null`, so the dominant completion is invoked at a state nothing
can have prepared. **And the prize it assumes does not exist** — `member.caret` costs what
`base.noCapture` costs (224 vs 254 ms; 2,035 vs 2,189), so the ~200 ms **is one narrowed
build**: completion latency IS the incremental floor. § 13 now cites BY SYMBOL after its line
numbers rotted a third time in a day.
**GATES.** Suite **16,677 / 0 / 3** (+24, all this round's pins); `cost_gate.py` exit 0, every
counter +0.00%, `output.errors` 46; `huge_methods.py --fail-over 0` clean; **`partition-gate.sh
sensitivity` EQUIVALENT on all 76 files across 78 netting passes, 72 carrying rows** — the arm
that can see a starved partition. `cost_gate` and the corpus are CONTROLS here, not coverage.

**(INC.88) — THE ROOT-FILE GLOB IS REFUSED, AND THE SPLIT IS WHAT EARNS IT (2026-09-01).**
Re-decomposing after (INC.87)(a) put the glob SECOND at **9.95 ms of a 95-100 ms query**, behind
an init block that is 48.8 and largely refused. Closed in both available directions.
**DIRECTION 1, memoizing the glob across builds under `trustFilesystem`, is refused by a promise
this compiler already SHIPS:** that KDoc says "ADDED and REMOVED files are still discovered from
the backing store on every build. **Nothing about the file SET is taken on trust**", two pins
state it, `OverlayVfs` and `docs/language-service.md` repeat it, and (INC.65) refused the same
shape one layer down. (INC.60)'s policy — a no-promise fix outranks a promise-costing one — is
why the other half was measured first.
**DIRECTION 2 WAS A REAL HYPOTHESIS AND IS REFUTED.** (INC.77) priced this row's syscall half at
~1.8 us/entry and called the residue irreducible — measured over `SystemVfs` ALONE, where the
shipped path is `OverlayVfs` wrapping it plus a per-directory sort, and the row reads 3.4-3.8
us/entry. Two new sub-rows closing against the SAME open timestamp:
`listEntries + sort 8.431 ms` = **sort 0.483 (5.7%)** + **OverlayVfs merge 0.752 (8.9%)** +
**the BACKING STORE's listing 7.196 (85.4%)**. That 85% is `File.listFiles()` plus one `stat`
per entry, and Java exposes no `d_type`, so one syscall per entry is a floor. **(INC.77) is
CONFIRMED on the shipped path** and both wrappers together are 1.2 ms of 8.4.
**WHAT LANDS IS THE INSTRUMENT, NOT A FIX** — the rows are inline no-ops when the probe is off,
so the refusal is reproducible instead of a claim in a note, which matters because the refusal
they confirm had been quoted for three rounds without ever being checked on the path it
described.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.

**(INC.87)(a) — THE POST-CHECKER'S FILTER ROW IS 4.5 ms OF A KEYSTROKE AND 89% OF IT ANSWERS
NOTHING; SPLITTING IT REFUTED ITS OWN SHAPE (2026-09-01).** (INC.86)(a) named
`post-check diagnostic filters` — 4.22 ms of a 90 ms query and a row NO queue item had ever
named. Split into three abutting sub-rows first, per (INC.65): **POST_DIAGS 4.507 -> 0.508 ms**,
of which **TS2688+TS2209+isolatedDecls 3.296 -> 0.492**, the **`modulePreserve4` whole-program
text scan 1.184 -> ABSENT (`calls` 1 -> 0)**, and the parse-cascade `removeAll` chain 0.0022 ->
0.0017 as the untouched control. The three summed to 99.4% of the row.
**THE OBVIOUS CANDIDATE WAS THE SMALLER MEMBER.** Reading the region, the eye lands on one
unconditional whole-program TEXT scan sitting above the guard that is its only consumer — real,
and 26%. The other 73% was `checkMissingTypesReferenceExports`' package.json pass, rooted at an
alternation `(?:^|/)`, so `BnM.optimize` gives it no literal and it is attempted at EVERY
POSITION of every file NAME — on a fixture with no `node_modules` at all, i.e. wholly to answer
NO. Pre-gated on `endsWith("/package.json")`, EXACT because the pattern's own tail anchors
there, regex kept live as the decider (round 792). The text scan is deferred behind a `lazy`
with the cheap basename test moved in front of it — and it is paid TWICE per keystroke in the
shipped design, since the (INC.17) recheck re-runs that very lambda.
**NO WALL IS CLAIMED AND THE SAME RUN SAYS WHY:** WALL read 108 -> 88 ms while `initNanos` read
**51.5 -> 77.9** on untouched code. One `--passTiming` draw is not a measurement ((INC.52)) and
the query wall carries (INC.72)'s ±20 ms term. The receipt is a COUNT — the bracket lives INSIDE
the `lazy`, so `calls == 0` IS the statement that the scan never ran.
**BOTH PIN CLASSES WENT RED FIRST, BOTH INSTRUCTIVELY.** `ProjectCompiler` never puts a
`package.json` into the program at all, so the TS2688 pin had to move to the multi-file harness
— as an absence assertion it would have been green forever; and the count pin's fixture was
named `b.ts`/`c.ts`, two of the twelve `modulePreserve4` basenames, so the scan correctly ran.
That collision is now the POSITIVE control (round 790).
**REFUSED, not shipped:** `init:evolvingArrayUseSiteWalks` (1.835 ms, five throwaway
collections per file) — a rewrite was built and REVERTED, unpriceable and unpinnable locally.
**(INC.86)(b) ANSWERED:** the init block is 418 rows, `rowsTo50pct=5`, tail of 363 rows worth
**1.03 ms between them** — no plateau left.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%, `output.errors`
46; `huge_methods.py --fail-over 0` clean; **two-binary 8-profile grid added=0 removed=0 on all
eight**, its before-arm control verified non-blind.

