# Status

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

**(INC.79) — THE CRAWL ASKED THE FILESYSTEM ABOUT FILES THE GLOB HAD ALREADY LISTED
(2026-08-31).** (INC.73)(a) refused this row's syscall half by arithmetic — "2,351 distinct
resolutions at exactly one `exists` each, so ~2.6 ms is irreducible". **That is true of the
resolver in isolation and false of the BUILD**: the root-file glob has already listed every
directory of the project and proved which files are there, off the same `Vfs`, ~20 ms earlier
in the same build. A per-component refusal can be right about its component and wrong about
the program, and what says so is asking who else already knows the answer.
**DECOMPOSED FIRST** (one binary, ABBA-rotated, population checked against the build's own
4,701 specifiers / 2,351 distinct): `resolve` **9.4-9.8 ms**, of which `existsOnly` **4.4-4.6**
(2,350 probes at ~1.9 us), `joinOnly` **3.7-3.9**, `dirnameOnly` 0.8, `keyOnly` 1.2,
`bookkeeping` 0.5-0.8 — so the syscalls are the largest piece and the path arithmetic the
next, neither of which the row itself could say.
`ModuleResolver` now memoizes `exists`/`isDirectory` for the build and is SEEDED from the
glob. **It adds no assumption**: (INC.65) already memoizes the whole ANSWER per
`(importerDir, specifier)`, strictly stronger, over the same one-build lifetime. **The seed
may only say YES** — a file can exist and be excluded from the program.
**MEASURED:** the row **10.2-12.0 -> 5.8-6.5 ms**, and the receipt is the count the build
prints — **2,351 questions, 0 reached the filesystem**.
**THE ABLATION FOUND THE PIN SET INCOMPLETE, WHICH IS WHAT IT IS FOR:** keying the memo by
BASENAME reddened only the COUNT pins, because every value pin happened to ask about names
existing on both sides — a wrong PROGRAM, silent per (CFG.1). The missing pin was added and
b2 then reddens it. Three arms, three distinct red sets (4 / 3 / 3).
**GATES.** Suite **16,618 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR, measured and named:** `PathUtil.join`/`normalize` at ~810 ns x 4,701
(**3.7-3.9 ms**, a `normalize` that must process `..` segments, which (INC.68)'s fast path
cannot help) and `dirname` + the memo key at **~1.5 ms**, which the crawl loop could hoist
per FILE.

**(INC.78) — THE ROOT-FILE GLOB ASKED AN *ACCEPTING* REGEX PER CANDIDATE, AND NO REFUSAL
FILTER COULD HAVE HELPED IT (2026-08-31).** `collectRootFiles` ran
`excludeRegexes.none { } && includeRegexes.any { }` for every candidate of every build — i.e.
on every keystroke of a language-service host — at **4.66-8.08 ms, 1.9-3.4 us per candidate**
on a ~90-110 ms incremental floor at 2,401 files.
**THE ATTRIBUTION INVERTS THE OBVIOUS FIX.** (INC.77) proposed a cheap prefix/extension
pre-filter; measured standalone on one binary, the EXCLUDE half is **191 ns/candidate** (its
literal prefix fails on the first character) and the INCLUDE half is **2,239** — `src/**/*`
compiles to `^…/src/(?:[^/]+/)*[^/]*(?:\.ts|…)$`, which backtracks over every directory
segment and **runs to a MATCH for every file in the project**. A filter can only refuse, so
the lever is an EXACT shortcut and the proposal was aimed at the half that was already cheap.
`GlobMatcher` keeps the regex as its DEFINITION and answers the
`<literal>` + `**` segment + bare `*` leaf + literal tail shape — `src`, `src/**/*`,
`src/**/*.ts`, `dist`, `**/*.spec.ts`, i.e. what tsconfigs contain — from the head and the
tail. Two corrections came from EXTENDING the differential grid rather than reading it: an
EMPTY SEGMENT is the one remainder `(?:[^/]+/)*[^/]*` cannot match (a doubled separator now
falls back to the oracle), and that test is exact only because the head ends at a directory
boundary. The length guard is provably unreachable and is recorded as a REDUNDANT GUARD.
**THE WALL COULD NOT CARRY THE CLAIM: the same one-process ratio read 12x, then 5x, then 3x
over four processes of one binary** (round 867's arm instability). The receipt is
`FrontEnd.globRegexEvals` — decisions that reach the regex — **4,802 -> 0**, pinned at TWO
program sizes with a positive control that a constrained pattern still runs it once per
candidate. In-build `CFG_MATCH` **4.66 -> 0.61 ms**, root-file glob row **14.46 -> 9.16**.
Gate is a DIFFERENTIAL, not a green suite ((CFG.1): a wrong root-file set is silent here);
4 ablation arms, 4 distinct red sets, and the no-fast-path arm reddens ONLY the cost and
regime pins while every value pin stays green.
**GATES.** Suite **16,610 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78 -> 78; `huge_methods.py --fail-over 0` clean.
