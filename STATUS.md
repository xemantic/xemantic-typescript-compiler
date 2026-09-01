# Status

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

**(INC.81) — A LIST PER KEY FOR 9,401 KEYS THAT NEVER GOT A SECOND ENTRY, AND A REFUTED
ROUND-471 HYPOTHESIS (2026-08-31).** `Checker.enclosingImportIndex` is **4.7 ms** of an 87 ms
per-keystroke query and NO queue item had ever named it — it surfaced only from re-taking the
ranking after (INC.78)/(INC.79)/(INC.80), which is what (INC.57)'s law asks for.
**CENSUSED BEFORE ANYTHING WAS DESIGNED:** the build inserts **9,401 specifiers under 9,401
DISTINCT keys**, so every `getOrPut` misses and every one allocated a `MutableList` and a
`Pair` — and **`multiFileKeys=0`**, i.e. not one key is reached from two files, so the
whole-program structural reach matches nothing on a real project.
**THE OBVIOUS HYPOTHESIS WAS MEASURED AND REFUTED.** The key is an AST *data class*, so
`hashCode` recurses both Identifiers and both comment lists (round 471). Priced in ONE
timestamp pair over a second pass: **76.5 ns each, 0.72 ms — 14% of the row**. The walk is
~1.0 ms and **~3.4 ms is the insert plus the two allocations**. So the key is left alone (its
structural semantics are load-bearing) and only the REPRESENTATION changed: the `Pair` itself
for the one-entry case, promoted to a list on a second claim, map presized.
**MEASURED** with two class dirs differing only in this, rotated across processes: **4.60 ->
3.17 ms**, after winning 3/3 batches in both directions with NON-OVERLAPPING ranges, and the
population census identical in both arms.
**THE PIN EXISTS BECAUSE THE CENSUS SAYS NOTHING REACHES THE PROMOTION** — `multiFileKeys=0`
is precisely that statement — so it takes a fixture, and two BYTE-IDENTICAL importers are one
(`ImportSpecifier`'s components include `pos`/`end`, so the same import at the same offsets in
two files IS one key). Two ablation arms, each reddening a DIFFERENT pin.
**GATES.** Suite **16,624 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**RESIDUE REFUSED WITH REASONS:** the walk IS the index's definition, and the hash cannot move
without changing a key whose structural semantics the replaced scan fixes.

**(INC.80) — JOINING A PATH BY ARITHMETIC, AND THE TWO-DRAW READ THAT NEARLY REFUTED IT
(2026-08-31).** `PathUtil.join(base, part)` built `"$base/$part"` and normalized it — and for
a module specifier that is exactly the case `isNormalized` must refuse (a `..` segment), so
(INC.68)'s fast path could never help it and the general body allocates a `split` list, a
`String` per segment, an `ArrayDeque` and a `joinToString` builder: **3.4-4.1 ms over 4,701
calls** in the crawl's specifier resolution. Counting the leading `..`, dropping that many
segments off the base with `lastIndexOf` and concatenating is **131-136 ns** — priced as a
probe arm and checked against the general body on all 4,701 real pairs BEFORE it was built.
**THE MEASUREMENT IS THE PART WORTH READING.** Two draws of the row said NOTHING (6.26/7.22
before, 6.22/7.45 after) and the refutation was already being written. **Six draws per arm,
ROTATED ACROSS PROCESSES over two class dirs differing only in this file: 6.41 -> 4.95 ms at
the median, the after arm winning in ALL THREE batches and BOTH rotation directions.**
(INC.68)'s law bites in this direction too — an unrotated pair cannot see a 23% change in the
very row it measures.
**AND THE FIRST EXPLANATION WAS REFUTED RATHER THAN ASSUMED:** the natural story (the
allocating arm pays GC the build never pays, round 801) is wrong — with a 2 GB young gen the
allocating arm got SLOWER (873 -> 1,264 ns) and so did the arithmetic one (131 -> 257), 20
young pauses in the whole process.
**RECEIPTS:** `pathNormalizeCalls` **11,935 -> 9,577** and every remaining call takes the
already-normalized path — a floor build performs **ZERO allocating normalizations, down from
2,358**.
**PINS** are a DIFFERENTIAL against the general body over a 12-base x 25-part grid ((CFG.1): a
wrong join names a different FILE and nothing here notices). **It caught its own defect on the
first run** — joining at the ROOT spelled `//dep`, a base the 4,701-pair fixture population
does not contain and the adversarial grid does. Four ablation arms; the no-fast-path arm
reddens ONLY the regime pin.
**GATES.** Suite **16,622 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR:** `dirname` + the memo key at **~1.5 ms over 4,701 calls** — the crawl loop knows
the importer's directory once per FILE and re-derives it per SPECIFIER.
