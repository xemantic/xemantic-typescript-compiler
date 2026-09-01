# Status

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

**(INC.85) — A WAVE THAT CANNOT BLOCK IS DRAINED WITHOUT THE 16-WAY MERGE, AND THE GATE IS
DEFAULTED OFF (2026-09-01).** (INC.84) measured the crawl's `flatMapMerge` pipeline at
**0.58/0.60/0.60x effective parallelism** on the arm an IntelliJ-class host runs — 16 workers
producing LESS CPU than their own wall, because every read is served from memory and every
parse from the content cache, so there is nothing to overlap. The same pipeline runs at
**7.5-8.9x** for a host that does not promise the filesystem, which is the control that makes
this a statement about the WAVE rather than about concurrency.
`readAndScanBatch` now classifies per path on the caller's thread — resident content AND a
content-cache hit is built directly, anything else defers to the old pipeline moved verbatim —
with both halves feeding the UNCHANGED single-threaded fold, so `CrawlParseCache.store`,
`retainRead` and the counters still run once each and off the flow (round 825).
**`readAndScanBatch` WALL 8.48/9.82/12.28 -> 5.84/5.32/4.73 ms; pipeline 6.63/8.13/9.61 ->
0.81/0.80/0.67.** The receipt is DETERMINISTIC and no wall number is quoted: a warm trusted
keystroke reads **2400 resident / 1 piped** (the merge is entered for the edited file alone)
against **0 / 2401** cold and untrusting, while four rotated batches of the query wall gave
sign-flipping deltas on the untouched CONTROL arm too — (INC.72)'s +-20 ms concurrent term.
**THE ROUND WAS FIRST REPORTED AS A REFUSAL, AND WHAT CHANGED THE VERDICT WAS REMOVING A COST
RATHER THAN RE-MEASURING.** The first design made every host pay a per-path probe (~0.6-0.9 ms
per wave) to serve a regime only some are in. `Vfs.hasResidentContent()` — a whole-store
question **defaulted `false`**, asked ONCE per wave — means every `Vfs` that has not opted in,
`SystemVfs` and so the entire shipped CLI and daemon path, performs **not one probe**.
**AND THE GATE'S SHAPE IS STRUCTURAL, NOT A THRESHOLD:** `OverlayVfs` answers from `retained`
alone and deliberately NOT from overlaid buffers, because `contents` is O(open editors) while
a wave is O(program files) — that disjunct would spend O(program) probes to fast-drain a
handful and can never pay at any project size. Dropping it took the last non-winning regime
from 0.6-0.9 ms to **99-111 NANOseconds**.
**EIGHT ABLATION ARMS, AND a5 IS THE ONE WORTH READING:** it was **DEAD on its first pass**
because the fixture's edit dropped retention, so the shape that matters — an unsaved buffer,
resident with new bytes over a stale cached tree — did not exist. Rebuilt, it reddens exactly
the staleness pin. **Without it this change could have shipped serving the PREVIOUS
KEYSTROKE'S parse tree, with no counter, order pin or corpus baseline noticing.**
**GATES.** Suite **16,645 / 0 / 3**; `cost_gate.py` exit 0, every counter unchanged;
`huge_methods.py --fail-over 0` clean; compiler profile **46**; `--frontEnd` census lines
byte-identical before and after.

**(INC.82) — THE IMPORTER'S DIRECTORY WAS RE-DERIVED PER SPECIFIER, AND THE ISOLATED PROBE
OVER-READ ITS OWN PRIZE BY 3x (2026-08-31).** `ModuleResolver.resolve` read `importerPath`
for nothing but its `dirname` — the (INC.65) KDoc says so in as many words — then joined it
with the specifier into a fresh `String` and probed the memo with it TWICE. The crawl knows
that directory once per FILE and asked once per SPECIFIER: **4,701 asks over 2,401 files**.
**PRICED BEFORE BUILDING** with the probe that already decomposes the row: of 1,314 ns per
specifier, `dirnameOnly` 96 and `keyOnly` 174 — **1.27 ms of a 6.18 ms row**.
**LANDED:** `resolveFrom(specifier, importerDir)` is the entry point and `resolve` a wrapper,
which makes the contract structural rather than a comment; the memo is nested (`dir -> spec`)
so the outer probe hashes a cached-hash instance the caller already holds and the inner one
only the short specifier; a memoized `null` is an identity sentinel, so a served answer costs
one probe; and the crawl hoists both the `dirname` and the per-file resolution map, the map
staying LAZY so a file whose every import is unresolved still contributes no entry.
**AND THE PART WORTH READING IS THE OVER-READ.** In the BUILD, over two class dirs differing
only in these files and rotated across processes, `FERESOLVE` reads **4771/5143/4102 ->
4677/4707/3954 us** — after wins 3/3 batches in both directions, ranges overlapping, delta
**~0.15-0.44 ms, not 1.27**. `hits x mean-call-cost` one layer in from where it is usually
quoted: 96 and 174 ns are what those operations cost **in a tight loop over 4,701 reps**,
inputs in L1 and the branch perfectly predicted. **An isolated per-operation probe prices an
UPPER BOUND on a removal, never the removal.**
**SO THE RECEIPT IS THE COUNT, EXACT TO THE UNIT:** `path normalize: 9577 -> 7277`, i.e.
precisely `4,701 - 2,401`, with the glob, join and resolution-question censuses IDENTICAL
across the arms — the receipt that the same work is done. The floor wall moved 103 -> 93 ms
3/3 and is **not claimed**: (INC.72)'s +-20 ms concurrent term is ten times the effect.
**ABLATION:** three arms, three distinct red sets. a2's pin needed a whole build —
`moduleResolutions` is not on the `Result` and reaches the checker as (CHK.30)'s
bare-specifier answer, so a map written under the wrong importer is a LOST diagnostic.
**ALSO: `docs/language-service.md` § 14 now EXISTS.** (INC.75)(b) claimed it documented
`cancellation`; the § 0 table's rows for `cancellation`, `saveState()` and `restoreState()`
all pointed at a section that was never written. It now carries the signatures, the poll
points, the cancelled-build contract, the exact `null`/`false` conditions, the added-file
limit, and the JVM edge a host hits: the cancellation is an `Error` by design, so
`Future.get` wraps it in `ExecutionException` and a generic failure branch would log a
warning per cancelled keystroke.
**GATES.** Suite **16,629 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; compiler profile **46** diagnostics, unchanged.
