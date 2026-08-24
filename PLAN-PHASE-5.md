# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

### Round (INC.23)+(INC.24) — the census: "+61 member types" is ONE member name and ONE truncated resolution, the write gate is refuted, and `narrowRendersMoreAny` is a substring heuristic

**WHAT THIS ROUND DID.** Produced the census (INC.23) exists for, and it shrank
(INC.22)'s refusal by two orders of magnitude. **Nothing about the shipped default
changed** — every instrument is off by default and every gate is byte-identical to
baseline.

**FINDING 1 — THE OBSTRUCTION IS ONE MEMBER NAME.** (INC.22) recorded "+61 member types
collapse to `any`". Classified per ELEMENT (nesting-aware, so a function-typed member is
not fragmented into its parameters), it is **78 rows carrying exactly ONE member —
`[Symbol.unscopables]`** (the lib's `{ [K in keyof any[]]?: boolean }`) — in 14 files.

| | baseline | partition-scoped arm |
|---|---:|---:|
| divergent spans | 286 / 49 files | 1,457 / 65 files |
| rows LOSING a member to `any` | **0** | **78** |
| **distinct member names lost** | 0 | **1** |
| rows where NARROW is better (`any` -> real) | 2 | 3 |
| other differing elements | 520 / 10 names | 9,397 / 196 names |

Everything else — 1,379 rows over 196 names — is the (INC.11)(a) alias-display family,
which (INC.22) already measured collapsing to **+1 row for 6.68 ms**.

**FINDING 2 — `narrowRendersMoreAny` IS A SUBSTRING HEURISTIC AND IT OVER-REPORTS.**
**ZERO of the shipped baseline's 168 "moreAny" rows actually loses a member type.** So a
NONZERO value is a LEAD, not a finding, and (INC.22)'s headline number was the heuristic
rather than the defect. A ZERO still means what it always did, which is why
`capture-equivalence`'s `narrowRendersMoreAny=0` remains a real gate — but the
capture-CHANNEL sweep's 168 is noise, and every quotation of it in this arc (including
this session's own notes) should be read that way.

**FINDING 3 — THE MECHANISM IS *NOT* ROUND 778's WRITE GATE.** The writer hook prints
`(pass, ambient, persisted, depth, truncated)`, and the victim reads `ambient=empty
persisted=true truncated=false` in the FULL arm against `ambient=empty persisted=true
truncated=TRUE` in the narrow one. **The ambient is empty in BOTH**, so round 778 says
cacheable either way and the suspected mechanism is dead. What differs is that under a
partition the first ask arrives from INSIDE the member-table resolution the mapped type's
`keyof` needs: `resolveStructuredTypeMembersCore` returns silently leaving `properties`
null, and the mapped type degrades to `any`. **The whole narrowed compile has exactly ONE
truncated resolution out of 822; a full build has 0 of 21,315 — and that one IS the
defect.**

**FINDING 4 — THE OBVIOUS FIX IS REFUTED, WITH A POSITIVE CONTROL.** Refusing to persist
a TRUNCATED resolution changes nothing across the whole sweep (same 78 rows, byte-
identical narrow digest). **The arm is not dead**: a single-file control shows
`refusedWrites` 593 -> 594 and the victim going `persisted=true resolves=1` ->
`persisted=false resolves=2`. The re-resolution simply re-enters the same guard and
answers `any` again. **So the lever is the CYCLE HANDLING, not the cache** — a `keyof`
over a type whose member table is IN FLIGHT must answer from the DECLARATIONS rather than
degrade. That is a member-resolution change with corpus-wide blast radius and was
deliberately not attempted.

**FINDING 5 — (INC.22)'s THIRD OBSTRUCTION IS RETIRED.** The pure partition-scoped arm is
**EQUIVALENT on BOTH `partition-gate` arms** (realism 78/78, sensitivity 76/76), even
though `init:buildFileLocalTypeMaps` is one of that fixture's 78 netting passes — its rows
carry the alias's OWN `fileName`, so a row for an out-of-partition file is dropped by the
partition filter anyway. **(INC.22)'s "DIVERGED 1 file" belongs to its MIXED
`TypeAlias`-program-wide configuration, not to partition-scoping.**

**SO WHAT NOW GATES 62-65 ms IS ONE DEFECT ON ONE LIB MEMBER**, not a general
order-dependence of `symbolTypes`. See (INC.25).

**(INC.24) LANDED FIRST, ON ITS OWN COMMIT, AND REPRODUCED ITS OWN RECORDED VALUE.** Both
capture runners now fold their whole answer set into ONE number per arm, **ordered by span
key so it is a property of the ANSWERS and not of `HashMap` iteration**. From a clean tree
the re-landed digest reproduces (INC.22)'s recorded `full=-3718897727265589316` over
381,666 types + 360,152 definitions exactly — which is round 776's rebuild-the-baseline
control, satisfied on an instrument rather than on a binary.

**A PROCESS VIOLATION, RECORDED BECAUSE IT MATTERS MORE THAN THE FACT IT SURVIVED.** A
`compileKotlinJvm` was run while a sweep JVM was live, which CLAUDE.md forbids in both
directions. The sweep survived and its summary matched the earlier run exactly, so no
measurement here is tainted — but the documented failure mode is SILENT (an empty results
dir, or a run against half-written class files), so "it was fine this time" is not
evidence the rule is soft.

**GATES — all at the shipped default, all byte-identical to baseline.** Suite **15,800 /
0 / 3** (15,784 + 16 pins), `cost_gate.py` `output.errors`/`spine.nodes` **+0.00%** with
`mapped.hits` +1.02% (standing), `huge_methods --fail-over 0` clean (778 core classes +
`-project`), `capture-equivalence` **5 / 3, moreAny 0** with digests unchanged,
`capture-channel` **286 / 49, moreAny 168** with digests unchanged,
`partition-equivalence` **EQUIVALENT 78/78** (floor 129 ms, median 173, ratio 29.10x),
`partition-gate` 78/78 and 76/76.

### Round (INC.22) — the floor's largest row is worth 62-65 ms by an axis that provably cannot move a full build, and it is REFUSED because the order it buys is RESOLUTIONS and not only a name

**WHAT THIS ROUND DID.** Re-priced `init:buildFileLocalTypeMaps` — **69.16 ms of a
90.15 ms floor pass table, 77%** — against the NEW floor, found a third axis neither
(INC.10) nor (INC.11) had varied, verified its central claim in the BINARY rather than
arguing it, and refused it on three independent gates. **Nothing landed. The tree is at
`aa3c0629`; the mid-round commit was never pushed and is reset away.**

**THE PRIZE IS REAL AND IT IS THE BIGGEST LEFT** (mean of 2 `both.floor` draws):

| arm | `PT.total both.floor` | `init:buildFileLocalTypeMaps` |
|---|---:|---:|
| shipped (`program`) | **90.15 ms** | **69.16 ms** |
| all phases partition-scoped | **28.05** | **0.014** |
| `TypeAlias` program-wide, rest partitioned | **24.69** | 6.68 |

`partition-equivalence.sh`: floor **131 -> 57 ms**, narrowed-query median **166 -> 116**,
ratio at the median file **29.86x -> 42.61x**, full build unmoved (4,751 vs 4,818, inside
the spread).

**THE THIRD AXIS, AND WHY IT LOOKED SO GOOD.** (INC.10) and (INC.11) both deferred
PHASES — what every file's map CARRIES — which perturbs a FULL build's first-touch order
as much as a narrowed one's, and that is what refused them both (2,722 and 1,665 moved
spans). This round varied **WHICH FILES the eager pass covers**, through the INV.6(6d)
partition view — which **IS** `binderResults` when there is no partition, so an
unpartitioned compile is unchanged BY CONSTRUCTION. That is the same property that
carried the whole (INC.7) gating arc.

**AND THE BY-CONSTRUCTION CLAIM WAS VERIFIED IN THE BINARY, WHICH IS THE ROUND'S BEST
PROCESS OUTPUT.** A per-arm DIGEST over every captured answer was added to
`CaptureEquivalenceMain`: over **381,666 captured types and 360,152 definitions in 76
files**, the full-build digest is `-3718897727265589316` for the pre-round arm **and
identical for both new arms**. Corroborated by `FltmDefer.lazyBuilds == 0` on every
unpartitioned build (`program` arm `eager=78 lazy=0`), `cost_gate.py` at **+0.00%** on
`output.errors`/`spine.nodes`, and `partition-equivalence` EQUIVALENT 78/78. **"A full
build is unchanged" is exactly the claim that is true of the code and false of the
binary; this one was made checkable.**

**THE QUEUE'S PREMISE HAD ALREADY EXPIRED.** (INC.11)'s `TypeAlias`-only arm was recorded
at **137 divergent spans**; re-measured today it is **5 spans / 3 of 76,
`narrowRendersMoreAny=0` — byte-identical to the shipped baseline.** The 137 were closed
by (INC.11)'s own `returnsArgumentUnchanged` fix and the (INC.5)/(INC.16)/(INC.19)-(21)
work since. So the question the item posed ("is ~66 ms worth 137 display rows?") no
longer existed, and **no display fix and no `aliasDisplayMap` re-key was needed or
attempted.**

**WHAT ACTUALLY REFUSES IT — three gates, and only the second is decisive:**

| arm | capture-equivalence (base 5 / 3) | capture-channel (base 286 / 49, moreAny **168**) | partition-gate |
|---|---|---|---|
| all phases partitioned | 2,275 / 46 of 76, moreAny 0 | 1,457 / 65, moreAny **247** | EQUIVALENT |
| `TypeAlias` kept program-wide | **6 / 4** | 348 / 51, moreAny **229** | **DIVERGED 1 file** |

(i) The 2,275 are (INC.11)'s (a) half at scale — id-keyed FIRST-WINS alias display
(`ModuleName` for `ModuleExportName`, `AssignmentPattern` for its unfolded union) — and
keeping the cheap `TypeAlias` phase program-wide (6.68 ms) collapses them to **+1 row**.
So the NAMING half is solved for 6.68 ms. (ii) **But the MEMBER channel loses resolutions
either way: `moreAny` 168 -> 229, i.e. +61 member types collapsing to `any` under a
narrowed build.** That is a WRONG ANSWER, not a naming difference — the exact class
(INC.11) refused the full deferral over (321 there, 61 here). Closing it needs the DECL
phase program-wide, which IS the whole cost. (iii) And `partition-gate`'s SENSITIVITY
arm — the one built to refuse rather than print green — **DIVERGES on a diagnostic**, so
it is not purely a display question either.

**THE READER'S MISS PATH WAS PINNED PROPERLY, WHICH IS WHY THE REFUSAL IS TRUSTWORTHY.**
The map's one reader rebuilds a foreign file's map on demand; the round pinned both that
it FIRES (a count) **and that it produces the SAME map** —
`Checker.fileLocalTypeMapSnapshot` renders a finished map to strings so an eagerly-built
and a lazily-built one can be compared, with a non-emptiness assertion and a negative
control that two files' maps differ. Ablations: forcing `Scope.PROGRAM` reddens 4 pins
INCLUDING the no-mode-install default pin ((INC.16) a1's lesson, applied); disarming the
lazy path reddens exactly the 2 rebuild pins and nothing else. Suite on the change was
**15,795 / 0 / 3** and `huge_methods` clean — but the capture gates are the gates for
this family, and they refuse it.

**THE TRANSFERABLE RESULT, AND IT RE-AIMS THE WHOLE DIRECTION.** The obstruction is NOT
the eager pass's COST but that the pass IS the program's FIRST-TOUCH ORDER — and that
order buys two different things: an alias NAME (cheap, fixable, 6.68 ms) and member
RESOLUTIONS (not fixable without the expensive phase). **A future attempt must make
member resolution ORDER-INDEPENDENT; making the pass cheaper cannot work.** See (INC.23).

**WORTH RE-LANDING SEPARATELY**: the per-arm capture DIGEST is a general strengthening of
`capture-equivalence.sh` — it proves a full build is untouched for ANY partition-shaped
change — and it died with the revert.

### Round (INC.21) — the scanning family gated as ONE batch banks 99.9%, the first ~100% discount in the arc, and the floor is now 75% a single refused row

**WHAT THIS ROUND DID.** Gated the 19 whole-source-scanning passes TOGETHER (the point
of the item: piecemeal banks nothing), four stragglers, and (INC.20)'s escalated design
reversal.

| | before | after |
|---|---:|---:|
| **`PT.total both.floor`** | 123.95 ms | **97.12 ms** |
| `partition-equivalence.sh` floor | 162 ms | **137 ms** |
| narrowed-query median, all 78 | 207 ms | **166 ms** |
| ratio at the median file | 24.16x | **29.86x** |

**THE PREDICTION WAS THE POINT AND IT HELD: 19.064 -> 0.024 ms = a 99.9% DISCOUNT**,
against the arc's 79.0 / 85.5 / 92.9 / 78.2 / 86.3. Gating the family together left the
lazily-built n-gram filter with nowhere to relocate to. **No row outside the batch
rose** — the largest riser is `init:buildFileLocalTypeMaps` +2.4 ms on 70 (+3.5%)
against the arm's own total drifting +9% between draws.
**And WHY nothing catches it was measured, not inferred**: the three whole-program text
gates that remain use a **raw `String.contains`, not round 895's filtered `srcHas`**, so
they never touch the filter at all.

**THE LIST WAS DERIVED BY TWO INDEPENDENT INSTRUMENTS AND THEY AGREE** — a call-graph
walk from each registered `pass("name")` to `srcHas`/`srcIndexOf`/`srcLastIndexOf`, and
a purely lexical scan for a `for (… in binderResults)` loop containing one. Largest row
`checkReverseMappedIntersectionConstraint` **17.752 -> 0.001 ms**; the other 18 are
0.78 ms and below.

**THE STRAGGLERS TAUGHT SOMETHING THE BATCH DID NOT: THREE OF THEM KEEP THEIR COST, AND
THE CONTROL PROVES IT.** All three have a whole-program `.contains` gate ABOVE the loop
— a question about the PROGRAM, which must stay on `binderResults` — so gating the loop
banks **~0.02 ms each**. `checkModulePreserve4Pin` is the control: its loops are
narrowed and its row does NOT move (1.639 -> 1.699). What banks the ms is a **NAME
PRE-GATE**, sound because it asks only what the pass can already do (a `when (basename)`
with no `else`; a loop that `continue`s on any other name):
`checkReexportedSymlinkReference3Pin` **2.509 -> 0.002**,
`checkSubclassThisTypeAssignable01` **2.064 -> 0.002**.

**THE AUTHORISED REVERSAL, AND ITS OBLIGATION WAS DISCHARGED ON BOTH ARMS OF ONE BOX.**
`checkSubsequentVarTypesPerFile` **11.740 -> 0.004 ms**. (INC.17) had deliberately left
it program-wide so a replay never re-enters it; the orchestrating session authorised the
reversal because the replay is EXPERIMENTAL, refused by (INC.19) and reached by nothing
shipped, while that row is paid by every real query. Measured rather than assumed:

| | replayedPasses | replay | freshBuilds | DIVERGED |
|---|---|---|---|---|
| before | 284 of 417 | 11,625 ms | 22,253 ms | 5 of 75 |
| after | **304 of 417** | **11,651 ms** | 19,601 ms | **5 of 75** |

**20 more re-entered passes cost 26 ms over 75 questions (+0.2%)** and the divergence
count is unchanged — it terminates and makes no answer worse. **Its ADVANTAGE fell
1.91x -> 1.68x purely because the fresh build got cheaper, which strengthens (INC.19)'s
refusal of the replay as a default path**: every round that shrinks the floor shrinks
the replay's reason to exist. `ProjectRecheckTest`'s replay-set bound went 300 -> 360,
and that number is not a constant — the classified set IS whatever reads the partition.

**REFUSED, WITH REASONS.** `checkModuleAugmentationReexportDuplicates` and
`checkCjsExportAugmentationConflict` loop `binderResults` and scan source, but
`emitAugReexportDup` adds TWO top-level diagnostics — one on the augmenting file and one
on the augmentation's **TARGET** — so a partition holding only the target loses its row
(rows 0.15 and 0.00 ms, so the refusal is free). A name pre-gate for
`checkModulePreserve4Pin` would change behaviour for a program carrying its needle and
none of the five pinned files. And **routing the three raw `.contains` gates through
`srcHas` would COST ~17.8 ms to build 78 filters to save three ~2 ms scans** — on a
floor build no pass builds a filter any more.

**THE PINS FAIL AGAINST THE UNFIXED BINARY, AND THE ABLATION REPRODUCES THE
SESSION-START BASELINE.** 25 partition pins; the ablation (24 loop headers reverted + 2
pre-gates removed) turns **9 RED**, including the count receipt *a narrowed build builds
fewer whole-source scan filters than the whole program* on `SrcScan.builds`. Every pin
green under ablation is a negative control by construction. **The ablated build
reproduces `checkReverseMappedIntersectionConstraint` at 18.18 ms and the family sum at
19.47** — round 776's rebuild-the-baseline control, satisfied.

**THE ANALYZER CAUGHT A SIXTH DEFECT IN ITSELF.** A raw LINE-based brace matcher ran
away exactly as CLAUDE.md warns — `checkParseUnmatchedTypeAssertion` read as **16,363
lines against its true 15** — and was replaced by one matching on the STRIPPED text.
**The tell was the impossible span, not a verdict.**

**TWO THINGS FOR THE NEXT ROUND.** (1) **The floor is now 75% ONE ROW**:
`init:buildFileLocalTypeMaps` **73.21 ms of 96.57**; everything else is <= 8.5 ms. It is
refused twice over — (INC.11) measured a deferral losing 321 resolutions to `any`, and
round 829 established its `typealias` resolutions are a TS2589/TS2615 **DETECTOR**, so
not resolving them DELETES a diagnostic. (2) `init:moduleTypeNameIndex` is **BIMODAL on
the FIRST sub-draw of a process** — [5.01, 0.32] / [8.80, 0.29] — **and does the same on
the ABLATED binary** (5.55, 0.27), so it is not this round's victim. Another
lazy-first-asker row, worth its own look.

**GATES.** Suite **15,771 -> 15,784 / 0 / 3** (+13 pins, no baseline moved so no
`logicalParityDivergences` entry), `partition-equivalence` EQUIVALENT 78/78,
`partition-gate` realism 78/78 and sensitivity 76/76 with **78 netting passes**,
`capture-equivalence` **5 spans / 3 of 76, `narrowRendersMoreAny=0`** and
`capture-channel` **286 / 49, members=285 scopes=0 signatures=1** — both BASELINE,
`cost_gate.py` largest `mapped.hits` +1.02% (standing) with all others <= 0.32%,
`huge_methods --fail-over 0` core 775/0 and `-project` 50/0.

### Round (INC.20) — the floor pass table nearly HALVES: 13 passes whose "field write" was a per-file ambient, two MIXED splits, and the relocation victim finally has a NAME

**WHAT THIS ROUND DID.** (INC.7) batch 4 closed the loop-header technique and left 83
passes refused by SHAPE, 53 of them on "writes a checker field inside the private
closure". **That verdict was true and the inference from it was wrong**: for nine of
them the write is a per-FILE ambient install (`currentFileLocals = result.locals` /
`currentCheckFileName = fileName`, reset after the loop or save-and-restored per
iteration through a `try`/`finally`). It is gone before the next file is walked and the
resting value after the pass is identical whether the loop ran 78 times or none — which
is exactly (INC.20)'s question ("is the write a property of the PROGRAM or of the
FILE?") answered in the file's favour.

| | before (`d6516785`) | after |
|---|---:|---:|
| **`PT.total both.floor`** | **219.98 ms** | **119.74 ms** |
| the 13 gated rows | 116.57 ms | **0.50 ms** |
| `partition-equivalence.sh` floor | 248 ms | **162 ms** |
| narrowed-query median, all 78 | 313 ms | **207 ms** |
| ratio at the median file | 15.66x | **24.16x** |

**Banked 100.23 ms of a 116.08 ms row removal = 86.3% — a FIFTH discount point**
(79.0 / 85.5 / 92.9 / 78.2 / **86.3**). The whole production diff across both perf
commits collapses to exactly TWO distinct lines.

**SUB-BATCH B IS THE (INC.17) TEMPLATE USED AS INTENDED.**
`checkCircularClassBaseViaDefaultTypeArg` and `checkCircularGenericCallbackVariables`
each build a program-wide INDEX and then emit per file — **only the second loop moved.**
`checkBaseClassImprovedMismatch` (rewrites under `d.fileName != fileName`) and
`checkPreEmitCountMismatchPins` (retracts under `it.fileName == fileName`) are per-file
operations, and `getDiagnostics` drops out-of-partition rows anyway.
**`checkPreEmitCountMismatchPins` is IMPROVED, not merely narrowed**: its TS-1 marker
carries `fileName = null` and so SURVIVES the partition filter, meaning the ungated loop
could emit a global marker about a file nobody asked about.

**THE RELOCATION VICTIM FINALLY HAS A MECHANISM AND A NAME, NOT A RESIDUE.**
`checkReverseMappedIntersectionConstraint` went **0.067 -> 19.431 ms** and is the ONLY
row outside the batch that moved more than 0.2 ms. Cause: round 895's `srcHas` builds
its per-file n-gram filter LAZILY, so the FIRST `srcHas` caller in pass order pays it
for all 78 files. `checkBaseClassImprovedMismatch`'s 19.06 ms was essentially that
build, and gating it handed the bill to the next scanner. Round 788's law with a named
beneficiary — and the same shape batch 2 misread as a walker that "got slower".

**THE ONE PRE-ANALYSED TARGET THIS HANDS THE NEXT ROUND.** **19 registered passes still
iterate `binderResults` AND scan whole source** (`checkReverseMappedIntersection-
Constraint`, `checkShebangError`, `checkMapUpsert`, `checkUnicodeIdentifierName2`, …).
Until ALL 19 are gated that ~19.4 ms filter build cannot be BANKED, only passed along —
it is now the **second-largest row in the floor** after `init:buildFileLocalTypeMaps`.
**Gating them piecemeal is worthless**; it wants one deliberate batch.

**PINS AND BOTH ABLATIONS.** 19 pins, and the discriminating ones assert on (INC.17)'s
partition CENSUS hook — a COUNT, not a time (round 868's law). **Reverting all 14 loop
headers turns 5 of the 7 census assertions RED**; the two that stay green are negative
controls asserting ABSENCE, which must hold in both arms. **Gating the two COLLECTION
loops turns exactly the three cross-file arms RED and nothing else** — that is the
evidence the MIXED split is load-bearing, each fixture putting the collected declaration
in a file the partition does not contain. Batch 5's `-project` arms cannot discriminate
and are NOT claimed to; they guard the other direction (a gated walker that stops
walking the ASKED file loses its row silently). Ownership of every pinned diagnostic was
established in `build/pass-lab.txt`, not assumed.

**REFUSED, WITH REASONS.** `init:buildFileLocalTypeMaps` 65.06 ms — (INC.11)'s, and
forbidden here. `init:computeAllEnumValues` 7.27 / `init:computePerFileVisibility` 1.36
/ `init:buildPerFileScopes` 0.94 — genuine cross-file accumulators. Four more
(`checkReexportedSymlinkReference3Pin` 2.46, `checkSubclassThisTypeAssignable01` 2.06,
`checkModulePreserve4Pin` 1.67, `checkModuleAugmentationReexportDuplicates`) are
gateable in shape and were deferred to keep the batch tight.
**AND ONE ESCALATED RATHER THAN DECIDED**: `checkSubsequentVarTypesPerFile` **11.44 ms**
is a clean per-file emitter and gateable, but (INC.17) DELIBERATELY left it on
`binderResults` so a re-entrant replay never re-enters it, and `PartitionCensusHookTest`
pins that absence. Reversing a landed design decision is not a sub-agent's call. See
(INC.21).

**THE ANALYZER CAUGHT A DEFECT IN ITSELF BEFORE IT PRODUCED A VERDICT** — a Kotlin
`${…}` template containing a nested string desynchronised the stripper at
`Checker.kt:64608`, hiding **2,523 of the file's 4,520 `fun` declarations**, i.e.
failing in the reassuring direction exactly as CLAUDE.md warns. This is the FIFTH
distinct defect in that family. Controls held: length preservation, 4,520 `fun` lines
raw and stripped, five named functions found, every KDoc `pass("name")` sample refused.

**GATES, RUN ON EACH SUB-BATCH.** Suite **15,752 -> 15,771 / 0 / 3** (+19 pins),
`partition-equivalence` EQUIVALENT all 78, `partition-gate` realism 78/78 and
sensitivity 76/76 with **78 netting passes** (seven of sub-batch A's nine walkers are in
its own netting list — the sensitivity arm is again what carried the round),
`capture-equivalence` **5 spans / 3 of 76, `narrowRendersMoreAny=0`** and
`capture-channel` **286 / 49, members=285 scopes=0 signatures=1** — both at BASELINE,
`cost_gate.py` PASS (largest `mapped.hits` +1.02%, the standing drift),
`huge_methods.py --fail-over 0` clean on core AND `-project`.

### Round (INC.16) — the INV.2(c) tables build on first ask, its one program-wide reader is served by a projection, and a narrowed query is 20.5% faster

**WHAT THIS ROUND DID.** `BinderResult.lexicalScopes` — **93% of the bind and, after
(INC.7) batch 4 and (INC.11), the largest single remaining mechanism in the floor** —
now builds on FIRST ASK, and `init:computeAllEnumValues`, the one program-wide reader
that forced every file, is served by a PROJECTION instead of by the tables.

| | eager (pre-round) | deferred (shipped) |
|---|---:|---:|
| scope tables built, FLOOR build | 123 | **3** |
| floor median | 333 ms | **286 ms** |
| **narrowed-query median, all 78 files** | 346 ms | **275 ms (−20.5%)** |
| narrowed-query SUM over 78 | 29,378 ms | **23,909 ms** |
| `FrontEnd` "bind (all program files)" | 70 ms | **6 ms** |
| `bindLexicalScopes` row | 64.5 ms / 123 calls | **~20 ms / 3 calls** |
| full build median | 4,896 ms | 4,993 (inside the 4,575-5,053 spread) |

`partition-equivalence.sh`'s own arms read the same direction on its own recipe:
**floor 248 ms, query median 313 ms, ratio 15.66x** — quote paired numbers from ONE
instrument, since the two recipes differ (batch 4 read 340/367/13.30x on that one).

**HAZARD (a) DOES NOT FIRE — AND WAS THEN REMOVED STRUCTURALLY ANYWAY.** The queue
named it as most likely to kill the round: `moduleLexicalScope` reads the BINDER's
accumulated `nodeToSymbol`, whose `(pos,end)` keys collide across files, so a scope
built at first-ask would see a FULLER map than one built mid-bind — the (INC.9)
refusal's mechanism, one pass over. The instrument is an **ID-FREE FINGERPRINT** of
every file's INV.2(c) tables (scope keys, owner/parent nodeIds, per-scope symbol
names+flags, and the aliased `existing` table's key set) taken at one fixed point and
diffed between arms: **IDENTICAL on all 78 files, three independent runs**
(`scripts/lex-defer.sh`). **That bounds its FREQUENCY, not its existence**, so the
dependence was removed by construction: `Binder.lexOwnerSymbols`, a per-file
`nodeId -> Symbol` table filled by `bindModuleDeclaration`/`bindEnumDeclaration`,
replaces both reads of the shared map. Order-independence is now a property of the
construction rather than a measurement — and arm **a4** (put the shared map back)
reddens exactly one pin, built from two same-length sources whose namespaces share a
node key.

**THE CENSUS CONFIRMED THE QUEUE'S ONE-LINE DIAGNOSIS EXACTLY.** A `forcedBy` census
(recording `PassTiming.currentPass` at each first ask) reads
**`init:computeAllEnumValues` as the SOLE forcer of all 78 program files**, floor and
full build alike. The 45 real-lib `.d.ts` binds are forced by NOBODY — but they are
worth **~2 ms**, so the lib half alone was never the prize.

**THE PROJECTION, AND WHY IT IS NOT A SECOND WALK.** `declareLexical` mints a
`TypeAlias`/`Enum`-flagged scope symbol at exactly two sites, both gated on
`scope.existing == null`. **The two halves are NOT symmetric**: the alias half wants a
NAME, which the binder can hand over directly; the enum half wants the scope-space
SYMBOL itself, because `computeEnumSymbolValues` is id-keyed and that symbol exists
only inside the tables. **So only an `enum` in a fresh scope forces a build.** The
projection costs **two int compares per node on `indexSourceFile`** — a walk that
already runs and is content-cached across compiles — plus a parent-chain ascent per
candidate declaration in `Binder.bind`. Its refinement is measured, not guessed:
parent-is-not-`SourceFile` alone skips 67 of 78, adding the namespace rule 69,
splitting off the alias half **75**.

**AND IT SHIPS WITH ITS OWN POSITIVE CONTROL**, per round 790: `LexDefer.verifySkip`
keeps walking every file and counts what the skip passed over — **75 skipped, 0
violations** on the profile, pinned on a fixture, because a zero is evidence only
beside a NON-EMPTY skipped population.

**THE ABLATION'S FIRST ARM READ 0 RED, AND THAT WAS A REAL FINDING.** a1 (restore the
eager build) reddened NOTHING, because **every other pin installs the mode it wants
and restores it — so the shipped DEFAULT was pinned by nothing at all.** A pin for the
default was added and a1 then discriminates. Four arms, each with a uniquely-its-own
red set: a1 restore-eager **1**; a2 never force for a fresh-scope enum **5** (3x
`FunctionScopedEnumTypePositionTest` + the `verifySkip` control); a3 hand over no alias
names **4** (2x `BlockScopedTypeAliasArityTest` + 2 new); a4 read the shared map again
**1**.

**GATES.** Suite **15,741 -> 15,752 / 0 / 3** (+11 pins), `cost_gate.py` all within
±0.32% except the standing `mapped.hits` +1.02% (`output.errors` 46, `spine.nodes`
unchanged, re-run on the restored tree identical), `huge_methods.py --fail-over 0`
clean on core (775 classes) AND `-project`, `capture-equivalence` **5 spans / 3 of 76,
`narrowRendersMoreAny=0`** and `capture-channel` **286 / 49** — both at BASELINE, which
is what says hazard (b)'s negative-id reordering did not bite — `partition-equivalence`
**EQUIVALENT on all 78**, `partition-gate` realism **78/78** and sensitivity **76/76,
78 netting passes**.

**LEFT OPEN, ~20 ms**: 3 files still force on the floor — the ones with a genuinely
block-scoped `enum`, where the census needs the scope-space SYMBOL rather than a name.
Serving those means minting that symbol outside the scope walk, which is a larger
change than this round's.

### Round (INC.11) — the residual IS a display question, the 66 ms is REFUSED anyway, and the round's product is a shipped hover defect: an unbound `T` in a tooltip

**WHAT THIS ROUND DID.** Classified (INC.10)'s undiagnosed residual, **refuted the
queue's own hypothesis about it**, refused the 66 ms with a sharper table than the
one that motivated the item, and landed the display defect the classification
exposed — which turned out to be **program-wide on the SHIPPED whole-program build**,
not a partition artifact at all.

**(b) IS A DISPLAY QUESTION. The queue said the residual 462 spans "may be two
genuinely different `Type` instances"; they are ONE instance carrying TWO COMPETING
NAMES.** The span is the type reference `ClassLikeDeclaration` in `es2015.ts`'s
`isPartOfClassBody(declaration: ClassLikeDeclaration, …)`. `ClassLikeDeclaration` is
a UNION alias, so it registers through `unionAliasStructural`, while `typeToString`
consults `aliasDisplayMap` FIRST. Two overloads (`classThis.ts:103`,
`namedEvaluation.ts:178`) return `Extract<ClassLikeDeclaration, Pick<T, "kind">>`;
the conditional **cannot decide** (a free `T` in the second argument), so it answers
its own CHECK TYPE — the interned union — and the generic site then writes
`aliasDisplayMap[thatUnion.id] = ("Extract", args)` **unconditionally**.

**THE CENSUS HAD TO BE WITHIN-ARM, AND THE FIRST HOOK WATCHED THE WRONG SITE.** A
cross-arm comparison is impossible here because `Type.id` is minted in resolution
order, so `AliasDisplayCensus` classifies inside ONE arm. The first hook watched
CLOBBERS at the last-wins site and read **0** — and **reading that as "not a display
question" would have been exactly wrong.** The sharpened census reads **86**
instantiations answering an argument unchanged (24x `Partial`, 18x `Extract`) with a
positive control that the instrument is not dead: **6** different-name refusals at
the first-wins site (`IncrementalBuildInfoDiagnosticOfFile` beating
`IncrementalBuildInfoEmitDiagnostic`). Round 849's law again — a zero from an
un-instrumented site reads exactly like a real negative.

**WHAT LANDED IS A CORRECTNESS FIX, NOT A PERF CHANGE.** A generic instantiation that
returns one of its own arguments UNCHANGED no longer registers an alias name for it.
Before it, a caret on `ClassLikeDeclaration` reported
`Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>` — **an unbound `T` in a
hover** — on the ordinary shipped build, for every user. Verified against the ablated
binary through the CLI: `Type 'Pass<Shape, Pick<T, "a">>' is not assignable to type
'number'`.

**THE PIN WAS BLIND ON ITS FIRST DRAFT AND ONLY THE ABLATION SAID SO.** The embedded
lib declares no `Pick`, so the shape degraded to `any` and the pin passed against the
ablated binary. With `@useRealLibs` it goes **RED against the unfixed binary with its
negative control still green.** CLAUDE.md already carries "a repro for this family
MUST set `@useRealLibs`" (round 725); this is a second consumer of that rule.

**THE 66 ms IS REFUSED, AND THE NEW TABLE IS 3.4x / 1.6x BETTER THAN (INC.10)'s AND
STILL REFUSES.** `FltmDefer` splits the pass into three phases so the table is
re-measurable in ONE binary (`XTSC_FLTM_EAGER`, default = shipped, pinned inert by
`FltmDeferArmTest`):

| eager phases | capture divergence | (INC.10)'s |
|---|---|---|
| all (shipped) | **5** spans / 3 of 76 files, `narrowRendersMoreAny=0` | 5 |
| `TYPEALIAS` only | **137** / 10 files, `narrowRendersMoreAny=0` | 462 / 18 |
| none | **1,665** / 47 files, **`narrowRendersMoreAny=321`** | 2,722 / 46 |

The ask-triggered whole-file build beats (INC.10)'s because in a full build every
file's map is still built in check order. **It refuses anyway: the fully-deferred arm
LOSES 321 RESOLUTIONS TO `any`, which is not a naming question at all.** So the 66 ms
is not reachable by fixing display, and the item's premise — that the 65 ms buys only
a first-touch ORDER — is now measured false: part of it buys RESOLUTIONS.

**FLOOR NUMBERS ARE DRAW-TO-DRAW, NOT A SAVING — SAY SO.** The landed tree reads
floor **324 ms**, sweep median **357**, ratio **14.06x** against the round's stated
340 / 367 / 13.30x. **The landed change moves no work**; these are two draws of the
same quantity, and quoting them as an improvement would be exactly the error batch 4's
note warned about one round earlier.

**FOR THE NEXT ROUND.** The remaining (a) half — **302 spans in `checker.ts` alone**
under full deferral — is a DIFFERENT mechanism: two synonymous non-generic aliases
resolving to one interned type, decided first-wins. That one is genuinely ambiguous,
because **tsc picks by the REFERENCE's declaration site, which an id-keyed global map
cannot express** — so closing it is a change of key, not a change of policy.

**GATES.** Suite **15,741 / 0 / 3** (+6 pins), `capture-equivalence` **5 spans / 3 of
76, `narrowRendersMoreAny=0`** and `capture-channel` **286 / 49, moreAny=168** — both
IDENTICAL, `partition-equivalence` EQUIVALENT on all 78, `partition-gate` EQUIVALENT
on both arms (78 netting passes), `cost_gate.py` PASS (`output.errors` 46,
`spine.nodes` +0.00%, largest `mapped.hits` **+1.02%**, the standing drift), and
`huge_methods.py --fail-over 0` clean on core AND `-project`.

### Round (INC.7) batch 4 — 89 walkers gated, the floor is 340 ms, and the one-line technique is now CLOSED: 65% of what is left is refused shapes

**WHAT THIS ROUND DID.** Gated **89** program-wide tail walkers onto the check
partition in two sub-batches, each swept independently. **Floor 378 -> 340 ms,
narrowed query median 422 -> 367, ratio at the median file 12.43x -> 13.30x**
(`partition-equivalence.sh`, the instrument the arc quotes). The diff is 89 loop
headers and nothing else: `for (result in binderResults)` **221 -> 132**,
`checkedResults` **255 -> 344**.

**WHY THIS AND NOT A REUSE MECHANISM.** (INC.15) measured that an edit invalidates
every reuse mechanism, so the FIRST query after a keystroke — the error-reporting
query the owner directive names — reuses nothing and pays the whole floor. Reducing
the floor is the only thing that helps it.

**THE FOURTH DISCOUNT POINT, AND IT IS THE LOWEST.** Summed floor rows of the 89:
**54.23 -> 0.13 ms**; whole floor pass table (417 rows) **254.57 -> 212.16 ms**.
Banked **42.41 ms** against 54.23 of rows = **78.2%**, next to 79.0 / 85.5 / 92.9.
Largest relocation victims `checkPropertyOverride` +6.17, `checkClassImplements-
Interface` +2.11, `checkDerivedConstructorSuper` +1.51.

**DO NOT QUOTE `floor-decomposition.sh`'s WALL FOR THIS.** It reads 424 -> 352 ms at
the identical recipe, but the intermediate post-4a draw read **444 ms — HIGHER than
before** while the deterministic pass table had already fallen 34.7 ms. A 42 ms
effect is not resolvable in a 4-draw floor wall; the pass table is. Round 716's
"counters decide, wall time confirms", one instrument over.

**(INC.19)'s WRITE-ONCE-RACE HAZARD DID NOT FIRE, AND THAT WAS CHECKED RATHER THAN
ASSUMED.** Gating a walker is exactly the operation that changes who wins a race for
a write-once interned field, and that failure is a plausible TYPE, not a diagnostic —
so this batch was graded on BOTH capture sweeps after EACH sub-batch, not only the
partition gate. `capture-equivalence` **5 spans / 3 files, `narrowRendersMoreAny=0`**
and `capture-channel` **286 rows / 49 files**, byte-identical after 4a AND after 4b.
Not luck to be relied on: none of the 89 resolves a type-parameter constraint, and the
only one resolving types at all (`checkSignatureGroupOverloadExcessCalls`, 0.18 ms)
left both sweeps unmoved.

**THE TECHNIQUE IS CLOSED, AND THE REFUSAL LIST IS THE ROUND'S REAL OUTPUT.** The
queue's headline ("174 ungated passes, 268.8 ms") is now **172 / 251.9 ms**, and
**the top TEN rows are 165 ms of it — 65% — and every one is a refused shape**:
`init:buildFileLocalTypeMaps` 62.06 (writes `deepInstantiationBailed`),
`checkTypeArgumentConstraints` 21.69, `checkBaseClassImprovedMismatch` 19.51
(`diagnostics[i] =`), `checkInterfaceMultiBaseConflicts` 12.73,
`checkSubsequentVarTypesPerFile` 10.70, `checkPropertyOverride` 9.61,
`checkDerivedConstructorSuper` 9.04, `init:computeAllEnumValues` 8.75,
`checkCircularClassBaseViaDefaultTypeArg` 6.91, `checkClassImplementsInterface` 5.94.
Analyzer-CLEAN was only 54 ms in total. Histogram of the 83 refused: **53** write a
checker field or retract inside the private closure, 8 of those plus a post-loop, 4
carry more than one `binderResults` reference, 4 hold a cross-file pre-loop
accumulator, and **43 retract via `diagnostics.removeAll`**. **The remaining tail is
not gateable one line at a time** — a successor has to change the SHAPE of a
retracting or field-writing pass, not its loop header.

**THE PIN DISCRIMINATES PER WALKER.** 7 new arms in `ProjectGatedTailWalkerTest` (22
in the class, 0 failed) over four 4a walkers whose diagnostics the PassLab confirmed
they own: TS2331+TS2683 / `checkThisInNamespaceBodies`, TS2335 /
`checkSuperInNonDerived`, TS2411 / `checkIndexSignatureProperties`, TS1340 /
`checkImportTypeUsedAsType`. **The first namespace-`this` fixture was VACUOUS and the
lab said so** — a `this` inside a FUNCTION in a namespace body reports TS2683 from a
different pass, so the row survived the disable; the `this` has to sit directly in the
namespace body. Ablation (the walker's loop made to walk nothing, committed first per
round 789): **exactly 3 of 22 arms redden, and they are exactly the three naming that
walker** — whole-program control, its narrowed arm, and the public-API arm.

**THE ANALYZER'S OWN CONTROLS CAUGHT THREE DEFECTS IN IT, ALL FAILING IN THE
REASSURING DIRECTION — and the third is new.** (a) a `${…}` template desynchronised
the stripper; (b) an expression-bodied `fun isDtsFile(x) = expr` swallowed the next
function; (c) **a MULTI-LINE PARAMETER LIST truncated a function's span to its header,
so the body — and every field write in it — was invisible.** Defect (c) wrongly
cleared `checkSpreadNonIterableIntoFixedArity` and `populateAmbientCyclicBaseClasses`,
i.e. **the queue's own REFUSAL LIST worked as the oracle that caught the analyzer.**
Controls now held: 4,541 `fun` raw = 4,541 stripped (0 blanked), all 10 `pass("name")`
KDoc samples refused, whole-file brace balance 0, 0 depth anomalies, spans
non-overlapping and containing no inner header, `isDtsFile` <= 3 lines, and named body
probes. **Also: a `pass("…")`-REGISTERING HELPER IS NOT A CALLER** — without excluding
the 12 `initCheckPasses*` registrars from the call graph every walker reads as
"reached from elsewhere" and the clean set is **0**.

**THE VICTIM MOVED TWICE IN ONE ROUND**, which is the sharpest evidence yet for the
queue's retirement of the victim heuristic: `checkExportEqualsCloduleReExport` went
0.13 -> 4.33 ms after 4a (it became the first later asker), and gating it in 4b moved
the same cost onto `checkPropertyOverride` (+6.17). The victim is a LOCATION, never a
walker that got slower.

**THE SENSITIVITY FIXTURE IS WHAT CARRIED THIS BATCH.** It nets **16 of the 89** gated
walkers as real netting passes, against **one** (`checkSpine`) on every dashboard
profile — (INC.18)'s whole point, collected one round later.

**GATES.** Suite **15,735 / 0 / 3** (+7 pins), `cost_gate.py` **identical** (largest
`mapped.hits` +1.02%, the standing drift; then `mapped.keyed` +0.32%,
`typeOfExpr.calls` +0.18%), `huge_methods.py --fail-over 0` clean (763 classes),
`partition-equivalence.sh` **EQUIVALENT 78/78 after BOTH sub-batches**,
`partition-gate.sh` **EQUIVALENT on both arms** (realism 78/78; sensitivity 76/76, 78
netting passes), and both capture sweeps unmoved as above.

### Round (INC.19) — the replay's lost constraint was never a replay defect: a write-once interned field, resolved before its own scope, frozen in the SEED build

**WHAT THIS ROUND DID.** (INC.17) landed the re-entrant replay at **3.06x** and
refused it, because `scripts/replay-differential.sh` read `DIVERGED: 8 of 75
file(s)` on the capture channel with the diagnostics channel untouched. This round
built the instrument the queue asked for, used it to refute the queue's own
diagnosis, and fixed the real defect. **The replay is now `DIVERGED: 5 of 75`, 23
spans of 373,879, and NOT ONE of the survivors is a lost constraint.**

**THE QUEUE'S CHARACTERISATION WAS WRONG, AND THE INSTRUMENT IS WHAT SHOWED IT.**
(INC.19) was written as "the replay SET is too small — bisect it". Three causes
were measured and the dominant one is reachable by NO replay-set change:

* **(a) real but small.** `init:computeAllEnumValues` — classified partition-
  INVARIANT, never reads `checkedResults` — repairs `program.ts` when added to the
  replay set (6 -> 5 files, replicated in two draws against a same-session control).
  Its row is `Map<string, SeenPackageName>` and `SeenPackageName` is a **block-scoped
  `const enum`**, i.e. exactly the B83.5-unbound population that pass's
  `result.lexicalScopes` sweep exists for.
* **(b) real.** Replaying is non-idempotent for some passes:
  `init:wireGlobalArrayTypes` **does not terminate** (TIMEOUT at 200 s against a
  ~48 s healthy draw; the 14-pass `init` group holding it timed out at 600 s), and
  `init:mergeLibGlobals` makes the answer **strictly worse** (+1 diverging file) —
  `mergeSingleSymbol`'s `merged.declarations.addAll(...)` is not idempotent. This is
  the all-passes arm's 100x blow-up reproduced in miniature and ATTRIBUTED.
* **(c) DOMINATES, and it is neither.** `Type.TypeParam` is interned per
  TypeParameter node and its `constraint` is **write-once** (`if (constraint ==
  null)`, 24 writers). Two passes race for it: `checkSpine` (dispatch row 28, walks
  only the PARTITION) and `checkTypeArgumentConstraints` (row 261, walks
  `binderResults`, so it reaches every file in every build). A probe on the setter
  (built, measured, reverted) reads, for seed `binder.ts` -> target `debug.ts`:
  `seedWrites=526 replayWrites=6 freshWrites=532`, with the replay writing **ZERO**
  `U` constraints. **The damage happened in the SEED build, before any recheck**;
  exactly 6 constraints move from `checkTypeArgumentConstraints|error` to
  `checkSpine|T` between the arms, and exactly 6 renders differ.

**THE DEFECT, AND IT IS AN ORDINARY BUG.** At `checkConstraintsInStatements`'
`FunctionDeclaration` arm the type-parameter `scope` is built and each
`tp.constraint` resolved **in the same loop**, with `withInstantiationContext`
installed only afterwards — so for `<T extends Node, U extends T>` the sibling `T`
resolves against the OUTER scope, answers `errorType`, and is frozen for the
checker's life. In an UNPARTITIONED build `checkSpine` always wins the race and
writes the right answer, **which is why all ~13k corpus baselines are structurally
blind to it.**

**THE CANDIDATE PATCH WAS NOT SAFE AS DIAGNOSED, AND THE COUNTER-EXAMPLE IS THE
ROUND'S BEST FIND.** Hoisting the resolution inside the scope install at all three
sites of the family reddens two corpus baselines with a REAL meaning regression: a
spurious TS2589 at `(0,0)`, i.e. `reportCheckerStackOverflow` — a genuine
`StackOverflowError` caught by the `init` boundary guard. `withDeclTypeParamScope`
serves the **type-alias** arm, and a type alias may constrain a parameter **by
itself** — `type Shared<I, D extends Shared<I, D>>`, the react-redux shape that
`reactReduxLikeDeferredInferenceAllowsAssignment` and
`circularlyConstrainedMappedTypeContainingConditionalNoInfiniteInstantiationDepth`
pin by name. **Resolving that constraint with the parameters in scope recurses
without bound: the outer-scope resolution was accidentally load-bearing there.**

**WHAT LANDED**, three sites, two treatments:

| site | change |
|---|---|
| `checkConstraintsInStatements` (`FunctionDeclaration` arm) | resolution **hoisted** inside `withInstantiationContext` |
| `checkTpListDefaults` | **hoisted** — its own comment already said the scope exists "so default/constraint TypeNodes resolve to the sibling TPs" |
| `withDeclTypeParamScope` | **NOT hoisted.** Given the `if (p.constraint == null)` guard every other writer has: its write was **unconditional**, so it CLOBBERED a correct constraint an earlier pass had written (measured: `class C<T extends Nd, U extends T>`, `U.constraint` went `T` -> `any`). Sibling refs in a class/interface/alias head still answer `errorType` there; they can no longer overwrite a better answer. |

**THE PIN, VERIFIED IN BOTH DIRECTIONS.** `ProjectRecheckConstraintTest` (3 tests,
`-project` commonTest) seeds a build over `a.ts`, replays onto `b.ts`, and asserts
the replayed captures equal a fresh `recheckOnly={b.ts}` build's. **Against HEAD:
2 of 3 FAIL** on the exact row `@158 <T extends Nd, U extends T>… != <T extends
Nd, U>…`, with the CONTROL passing (the reference arm really does render the
constraint), so the failure is not vacuity. **Against the fix: 0 fail.**
**The shape had to be a NAMESPACE-nested generic function** — an earlier attempt
spent its budget on top-level `declare function` and overload-set shapes that are
all vacuous, because `init:buildFileLocalTypeMaps` (row 13) resolves every
FILE-LEVEL `Function` symbol of every file, partition or not, and writes the
constraint correctly before either racer. The KDoc records this.

**(INC.8)(a) IS **NOT** FOLDED IN, AND THE NEGATIVE IS WORTH MORE THAN THE GUESS.**
The round's hypothesis was that (INC.8)(a)'s 167 `<K>` / `<K extends any>` rows are
this same bug's shipped-path face. They are not: `capture-channel-equivalence.sh`
reads **286 spans in 49 of 76 files, byte-identical** before and after. A probe on
the shape shows the constraint is **already `any` before `checkTypeArgumentConstraints`
runs** (`TPWRITE name=K was=any now=any`) — a **namespace-local type alias failing
to resolve as a constraint**, i.e. a NAME-RESOLUTION defect, not a first-touch
freeze. Same symptom, different mechanism; (INC.8) stays open and its (a) is now
diagnosed one level deeper than the queue entry had it.

**THE FAMILY SWEEP — all 24 `.constraint =` writers read.** Thirteen are correct
(they resolve inside the scope install), and `checkConstraintsForTypeArgs`' own
comment states the law verbatim: *"this is WHY the resolution is a separate loop
and not folded into the intern factory (which runs before the scope is set)."*
**Three sites had drifted from a rule the codebase already knew.** Reported and
deliberately NOT fixed, each resolving a constraint outside the scope its siblings
live in: `Checker.kt:111069` (`getDeclaredTypeOfSymbol`'s class/interface branch —
no scope at all, but it mints FRESH non-interned params so it cannot corrupt the
cache), `Checker.kt:137404` (resolves **inside `typeParamInternCache.getOrPut { }`**,
i.e. a first-touch freeze BY CONSTRUCTION, and the hardest to fix since the factory
runs before any scope exists), `Checker.kt:139240` (`getTypeParametersOfSymbol`),
and `withDeclTypeParamScope` itself for the self-referential-alias hazard above.

**THE INSTRUMENT** (`aca8a60f`, committed BEFORE any ablation per round 789):
`PassTiming.replayExtraPasses` (default empty, union-ed into the replayed set, so
empty is behaviour-free by construction); `PassTiming.recordRegistrations` /
`registeredPasses`, the candidate universe recorded AT RUN TIME — **a source grep of
`pass("…"` reads 480 names against the dispatch's 417, so a grep-derived bisection
could never have closed**; `ReplayDifferentialMain`'s 5th argument plus a
`--dump-passes` mode and a machine-readable `divergentFiles=` line; and
`scripts/replay-bisect.sh` (`dump` / `sweep` / `try` / `narrow`), which compares
ONLY the diverging files (~48 s a draw against ~4 min for all 75) under a
wall-clock cap so a non-terminating pass kills one draw rather than the sweep. It
REFUSES (exit 2) on missing inputs. Positive control: `--dump-passes` answers
`all=417 replayed=207 candidates=210`, the 417 matching the differential's
independently printed row count.

**WHAT THE REPLAY'S SURVIVING 23 SPANS ARE — a different class, and (INC.19) is not
closed.** No lost constraint remains. What is left is lost generic INFERENCE:
`Connection[][]` -> `any[][]`, `Map<string, SeenPackageName>` -> `Map<any, any>`,
`(key: K, valueInNewMap: U) => T` -> `… => any`. 19 of 210 candidate passes were
swept per-pass before the probe showed pass additions cannot reach the dominant
family; `build/bench/replay-bisect/{passes,cand,rest}.txt` hold the universe and the
191-pass remainder, so looping `scripts/replay-bisect.sh try` over `rest.txt`
resumes it.

**GATES.** Suite **15,728 / 0 / 3** (+3, the new pin), `cost_gate.py` all in band
(largest `mapped.hits` **+1.02%**, the standing pre-existing drift; then
`mapped.keyed` +0.32%, `typeOfExpr.calls` +0.18%), `huge_methods.py --fail-over 0`
clean (763 classes), `partition-gate.sh` **EQUIVALENT on BOTH arms** (realism 78
files; sensitivity 76 files, 78 netting passes, floors cleared),
`capture-equivalence.sh` **5 spans / 3 files unchanged**,
`capture-channel-equivalence.sh` **286 / 49 unchanged**, and
`replay-differential.sh realism` **8 -> 5 diverging files**. `Recheck.kt`'s banner
and `ProjectRecheckTest`'s KDoc were corrected in the same commit — both still
carried the now-stale "8 of 75 files — a lost type-parameter constraint".

### Round (INC.17) step 2 — the re-entrant replay is 3.06x, it loses a type-parameter constraint on 8 of 75 files, and the DIAGNOSTICS sweep never noticed

**WHAT THIS ROUND DID.** Closed out (INC.17) step 2, which a previous session left
uncommitted mid-flight. The re-entrant replay — answer a semantic query about a
file the checker was never asked about by re-entering only the partition-dependent
`init` passes instead of rebuilding — is **built, measured, graded, and REFUSED as
a default path.** It is landed EXPERIMENTAL and opt-in, the way `--shareBind` and
`--mergeClone` were landed: available, off, with its known failing case written
down at every entry point.

**THE PRIZE IS REAL.** On tsc's own 78 sources, `replay=12572 ms` against
`freshBuilds=38498 ms` over 75 questions = **3.06x**. It is exactly the shape step
1's census predicted: the floor's **211 partition-invariant passes carry 350.89
ms**, the 205 dependent ones **15.59 ms**, and 204 of those cost **0.69 ms**
between them.

**WHAT REFUSES IT.** `scripts/replay-differential.sh` reads

```
compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5
          typeSpans=373879 definitionSpans=352713
DIVERGED: 8 of 75 file(s)
```

The **diagnostics half is untouched** — every row agrees, on both arms. The
**capture** half diverges in 8 of 75 files, and the shape is a **lost
type-parameter constraint**: the replay renders `<T extends Node, U>` where a
fresh build renders `<T extends Node, U extends T>`. That is a plausible-looking
type, never an error, so it fails in the silent direction. (INC.2) refused capture
narrowing over **45 divergent spans**; 8 divergent FILES is far past that
precedent, and a wrong hover is worse than a slow one.

**THE TRANSFERABLE OUTPUT — and it is a CLAUDE.md entry.** *A partition or replay
change must be graded on the CAPTURE sweep, not only the diagnostics sweep.* On
the tsc profile the diagnostics comparison was **completely silent** about an
8-file defect. (INC.18)'s arm a3 predicted exactly this and was recorded as a
NEGATIVE; this is the same finding with a real defect behind it.

**WHAT LANDED.** The mechanism, marked EXPERIMENTAL at `ProgramRecheck`,
`RecheckHolder`, both `recheckHolder` parameters and
`Checker.recheckAdditionalFiles`, each carrying the divergence and the "do not
serve hover from this" instruction. `scripts/replay-differential.sh` +
`ReplayDifferentialMain` (the oracle — (INC.19) starts from it rather than
rebuilding it). `ProjectRecheckTest` pins **what the replay actually does**: the
diagnostics channel agrees, the walked set grows, three files cost ONE build
(counted on `tsconfig.json` reads, with the seed's own read as the live-instrument
control), the arming is behaviour-free, and the capture channel is asserted to
EXIST and deliberately **not** asserted equivalent — a soundness pin there would
be false. And the `checkSubsequentVarTypes` split the census demanded: one MIXED
pass whose two halves have OPPOSITE partition behaviour, whose SUM the census was
reading as 14.90 ms of replay cost. Split, the replay's fixed cost is 0.69 ms, and
`PartitionCensusHookTest` now pins both halves on opposite sides of the census.

**THE OPT-IN IS THE LOAD-BEARING CHECK, and here is how it was verified.**
`grep -rn 'recheckHolder\|RecheckHolder\|retainForRecheck\|recheckAdditionalFiles'`
over every module: the only non-declaration call sites are `ProjectRecheckTest`
and `ReplayDifferentialMain`. Every parameter defaults to `null`;
`retainForRecheck = recheckHolder != null`; `recheckAdditionalFiles` `require`s
`retainForRecheck`. `Project` — the embedding API a host uses — does not reference
the type at all. And the property is now PINNED rather than argued: arming a
holder must not change the build's own diagnostics, narrowed or whole-program,
with a control asserting the arming actually happened (round 873's rule — two
unarmed builds would agree and prove nothing).

**WHAT DID NOT WORK.** Two attribution arms, both from the previous session, both
recorded here so nobody spends a day rediscovering them. The FIRST died silently
with no output; the probable cause is daemon starvation (BUILD.1 — two multi-hour
daemons were holding ~4.9 GB). The SECOND — `replayAllPasses`, re-entering EVERY
pass — ran **~100x over budget**: 53 minutes of CPU over **7** targets without
finishing, against ~50 s of total compute for the 205-pass replay over **75**.
That ratio is itself evidence: it is the signature of a pass that appends to a
side table or re-emits on each replay, i.e. hypothesis (b) below. The arm is kept
(`PassTiming.replayAllPasses`) and documented as an experiment, not restarted.

**WHAT (INC.19) INHERITS.** Two live hypotheses — the replay SET is too small (a
dependent pass classified invariant, because the classification measures *reads
the partition* where soundness needs *its OUTPUT depends on the partition*), or
replaying at all is non-idempotent. The named next instrument is a **BISECTION
over the replay set** — which passes, added to the 205, repair the 8 files — which
is O(log n) builds against the all-passes arm's unbounded cost and separates the
two hypotheses directly.

### Round (INC.18) — the partition gate was VACUOUS on every profile this repo has, and it is now armed and PROVEN able to fail

**THE MEASUREMENT THAT IS THE ROUND.** `scripts/partition-equivalence.sh` is the
detector (INC.7)'s 68 walker gatings were graded by, (INC.9)'s deferral was graded
by, and the one (INC.17)'s re-entrant replay would be graded by. It is a
DIFFERENTIAL (full build versus `recheckOnly = {file}`), so its resolution is
bounded by how many of the checker's 416 `init` passes contribute a row to the
comparison:

| project | files | diagnostics | files carrying a row | **distinct passes netting one** |
|---|---:|---:|---:|---:|
| `build/bench/tsc-project-*` — the arm that has always run | 78 | 46 | **5** | **1** (`checkSpine`) |
| `test-fixtures/partition-gate` — this round | 71 | 175 | **70** | **78** |

**73 of 78 per-file comparisons on the realism arm are empty against empty**, and
every row that does exist is netted by ONE pass. All eight dashboard profiles are
that same codebase. So a green run there is evidence about ~1 of 416 passes, and
(INC.17)'s refusal was right on its own numbers.

**THE FINDING UNDERNEATH THE FINDING, AND IT EXPLAINS *WHY* NO REAL PROJECT CAN ARM
THIS GATE.** `PassDiagMineMain` compiled every single-file conformance case under
one fixed option set — the case's own `// @directive` header DROPPED, because a
fixture is one tsconfig — and recorded `PassTiming.diagNetByPass`: **6,451 cases
walked, 2,802 netting, 241 DISTINCT passes**. Greedy-covering that record,
`scripts/partition_fixture_compose.py` reaches 44 passes at 20 files and then adds
**exactly ONE new pass per additional file** out to 200. **The tail walkers are
one-shape walkers**; coverage is bought one hand-written shape at a time and a real
codebase reaches one.

**THE FIXTURE IS HAND-WRITTEN, DELIBERATELY.** The miner says WHICH shapes to write;
the files were written from scratch against that map. This repo does not vendor
TypeScript source — `typescript-repo/` is gitignored and even the real lib sources
are GENERATED into `build/` — and a fixture generated at gate time from a clone is a
fixture that drifts. 71 files, every one a module so nothing collides.

**THE RECEIPT IS `diagNetByPass`, NOT `diagsByPass`.** The latter records
`if (d1 > d0)`, so a pass whose net effect is a RETRACTION is absent from it
entirely (73 `removeAll` + 5 `removeAt` + 2 `clear` sites in `Checker.kt`; round
749 already records that such a pass is invisible to a count-based ablation). The
receipt prints `netTotal` beside `diagnostics` as its own positive control — 167
against 175 on the fixture, the eight-row gap being SYNTAX errors, which the Parser
emits through no `pass(...)` wrapper at all; 46 against 46 on the profile.

**THE PROOF THAT IT CAN FAIL — `scripts/partition-gate-ablate.sh`, one mistake at a
time, both arms per arm.**

**GATE ARMS.** Fixture 76 files / 182 diagnostics / 72 carrying / 78 netting passes;
profile 78 / 46 / 5 / 1.

| arm | injected mistake | realism | sensitivity |
|---|---|---|---|
| a1 | `checkMissingImplementations` silent when narrowed | GREEN | **RED** (1 file, TS2389) |
| a2 | `checkConflictMarkers` silent when narrowed | GREEN | **RED** (1 file, 3x TS1185) |
| a3 | round 609: `buildFileLocalTypeMaps` gated on the partition | GREEN | GREEN — **control** |
| a4 | `checkCloduleTest2`, nets on NEITHER — CONTROL | GREEN | GREEN |
| a5 | `checkSpine`, nets EVERY row tsc reports — CONTROL | **RED** (5) | **RED** (36) |

**a5 IS THE SHARPEST NUMBER IN THE ROUND: ABLATING THE ONE PASS THAT NETS EVERY ROW
tsc's OWN SOURCES REPORT REDDENS EXACTLY *5 OF 78* FILES — WHICH IS HOW MANY CARRY A
ROW.** That is the realism arm's entire resolution, measured rather than argued: no
defect whatsoever can make it fail on more than 5 files, and only through one pass.

**a3 IS AN HONEST NEGATIVE, RECORDED AS A CONTROL AND NOT AS COVERAGE.** Starving
that collector onto the partition changes nothing observable on either project, and
the arm was RE-RUN after the fixture gained cross-file structure (a shared
base/interface/enum/alias module and its dependents, a cross-file circular pair, a
cross-file overload set) — still both-green. The arm is REACHED (its loop iterates 1
file instead of 76 when narrowed), so this is a fact about the collector: consistent
with (INC.10), which measured that this map's product is consumed by type DISPLAY,
not diagnostics — deferring it entirely moved **2,722 capture spans and ZERO
diagnostics**. The instrument that owns it is `capture-equivalence.sh`. **A
round-609 starvation of a DIAGNOSTIC-producing collector is still unpinned by any
arm here, and that is this round's honest limit.**

**PIN ARMS**, the same mistakes graded by the two `commonTest` pins, so a pin
recorded as discriminating has been SEEN to fail:

| arm | `PartitionSensitivityTest` | `PassDiagNetSignTest` |
|---|---|---|
| a1 | **RED** (1/3) | GREEN |
| a3 | GREEN | GREEN |
| a4 | GREEN | GREEN |
| a5 | **RED** (1/3) | GREEN |
| a6 — `diagNetByPass` clamped to `d1 > d0` | GREEN | **RED** (1/2) |

Each pin reddens under its OWN mistake and stays green under the other's, which is
what separates the two claims: one is about the partition, the other about the
accumulator the receipt is read from.

**WHAT THIS RETRO-PRICES.** (INC.7)'s 68 gated walkers and (INC.9)'s deferral were
profile-GREEN for a reason that says nothing about them; only the CORPUS, which has
no partition, stood behind them. They are not thereby wrong — they are unmeasured on
this axis and re-runnable now.

**NO COMPILER CHANGE.** Everything here is fixtures, jvmTest runners, scripts and
two `commonTest` pins; `cost_gate.py` and both `huge_methods.py` censuses are
CONTROLS this round rather than gates, and any movement in them would have meant the
change escaped its module.

`docs/partition-gate-sensitivity.md`.

### Round (INC.17) step 1 — the three-bucket census: the prize is 95.7% of the floor, the replay's own cost is 0.69 ms, and the gate that would have to see it is VACUOUS

**MEASURED, tsc's own 78 sources, six draws in a palindrome over three partition
shapes (`scripts/partition-census.sh`):**

| bucket | rows | floor ms | one-file ms |
|---|---:|---:|---:|
| partition-**INVARIANT** (never reads the partition) | **211** | **350.89** | 375.44 |
| partition-**DEPENDENT** (reads `checkedResults` or `assignedFileNames`) | **205** | **15.59** | 55.05 |
| total | 416 | 366.47 | 430.49 |

So **95.7% of the floor's pass time is partition-INVARIANT** — that is (INC.17)'s
prize, and it is the whole 350 ms. And the replay's own fixed cost is smaller than
the bucket suggests: **204 of the 205 dependent passes cost 0.69 ms BETWEEN THEM at
the floor**, because **201 of them read the partition exactly ONCE per build** — they
are `for (result in checkedResults) { … }` and nothing else, the (INC.7) shape. The
205th, `checkSubsequentVarTypes`, is **14.90 ms** with an EMPTY partition, i.e. it is a
MIXED pass doing program-wide work (`…InGlobals`, `…AcrossScriptFiles`) outside its
partition loop; splitting it would take the replay's fixed cost to ~0.7 ms.

**THE MODEL THE CENSUS PRODUCED IS SMALLER THAN THE ONE (INC.14) PRICED, AND THAT IS
THE FINDING.** (INC.14) budgeted "416 pass rows classified, a diagnostics prefix to
reset, every per-file side table to reset". The census says the prefix is not needed:
a program-wide pass iterates `binderResults`, so **it already emitted the newly asked
file's rows during the FIRST build** — `getDiagnostics()` merely filtered them out,
at the very end, because that file was not assigned. A replay therefore does not reset
anything: it re-runs the 205 dependent passes with the new partition (which appends
only the new file's rows, since that file contributed none before) and re-filters.

**WHAT THIS ROUND REFUSES TO LAND, AND THE REFUSAL IS ABOUT THE INSTRUMENT, NOT THE
DESIGN.** A replay that silently produced NOTHING from 204 of those 205 passes would
be **invisible to every gate this repo has for partition work**, and that is measured
rather than argued: on the tsc profile the full build's **46 diagnostics are netted by
exactly ONE pass — `checkSpine`** (the new signed-delta census reads 46 against the
build's own `fullDiagnostics=46`, its positive control). Every one of the other 415
rows moves the diagnostics count by zero. So `scripts/partition-equivalence.sh` —
the designated detector, and the one (INC.7) leaned on — is comparing an essentially
EMPTY population on this profile, and the same is true of the other seven, which are
the same codebase. What carried (INC.7) was the 13k-baseline CORPUS, which has no
partition; there is no instrument here that exercises *many emitting passes* and *a
partition* at once. Landing a re-entrant checker behind a gate that cannot see a
starved replay is rounds 853/873/895 again — a green run that tested nothing.

**AND THE CLASSIFICATION THE HOOK PRODUCES IS NOT YET THE ONE THE REPLAY NEEDS.** It
measures *reads the partition*; soundness needs *its OUTPUT depends on the partition*,
and the two come apart wherever a program-wide pass CONSUMES a side set the spine
fills — the producer/consumer pattern CLAUDE.md names three times
(`spineCollectObjLitVar` → `spineResolveDeferredIterationChecks`,
`spreadNonIterableHandledCalls`, `populateAmbientCyclicBaseClasses`). Such a pass is
recorded INVARIANT and would be skipped. Nothing measured here bounds that class,
because on this profile those passes emit nothing.

**THE INSTRUMENT IS A RUNTIME ONE ON PURPOSE, AND ITS CONTROLS ARE PRINTED.**
`Checker.checkedResults` is now a getter that records `PassTiming.currentPass`, so the
census cannot be wrong about who read it — where a source analyzer over `Checker.kt`
fails silently and in the reassuring direction (CLAUDE.md: a stripper handling `'x'`
desynchronises on `'\''`; a `pass("name") { … }` sample inside a KDoc parses as a real
registration). The two partition reads that BYPASS the property — `checkSpine`'s file
loop and `checkUnresolvedNames`, both testing `assignedFileNames` directly — are hooked
explicitly, and `checkSpine` is asserted present in every arm. Controls, all printed by
the runner: 416 rows and 205 read-sites in **all six draws and all three partition
shapes**, `outside = 0` in every one (so the per-pass sums partition the reads rather
than sampling them), and the diagnostics hook reading exactly the build's own count.

**WHAT WAS CONSIDERED AND REJECTED AS AN EXPERIMENT.** Simulating the replay with
`PassTiming.disabledPasses` (disable the 211 invariant rows, compare) is PESSIMISTIC
and therefore cannot exonerate: a real replay retains those passes' *results* in the
live checker, where the simulation deletes them — `init:buildFileLocalTypeMaps` alone
is 76 ms of invariant work that `checkSpine` consumes. And on this profile it would
also be vacuous in the other direction, comparing 0 diagnostics to 0.

**GATES.** Suite, `cost_gate.py`, `huge_methods.py` on core and `-project`, and the
four equivalence sweeps — all at their baselines; the hook is behind
`PassTiming.enabled`, so every sweep is unchanged by construction and is a control.

**SUCCESSOR — (INC.18), and it is the blocker rather than the feature.** Build a
partition fixture whose diagnostics come from MANY passes: a small multi-file project,
each file carrying a shape a different dedicated walker owns, driven through
`partition-equivalence.sh`'s own comparison. Its receipt is a COUNT — *how many
distinct passes net a diagnostic on it* — and it must be in the tens before any
re-entrant replay may be believed. It costs no compiler change, it re-arms the gate
(INC.7) and (INC.9) were graded by, and only then is (INC.17)'s 350 ms landable.

### Round (INC.14) — a `Checker` now answers a whole WORKING SET, and editor order was the last thing it could have gone wrong on

**EIGHTEEN SEMANTIC QUERIES IN SIX BUFFERS WENT 5,230 ms -> 737, AND SIX PER-BUFFER
ERROR QUERIES 2,338 -> 526 WITH EVERY RE-ASK AT 0 — because `Project.prepare(files)`
performs ONE narrowed build over a declared working set and `diagnosticsOf`'s memo is
now keyed by the PARTITION rather than by the question.** Replicated in a second run
(4,997 -> 704 and 2,376 -> 539); the 15-query block `warm-program-cost.sh` already
drove is a CONTROL and did not move (diagA 2,032, hover1 3,921, hoverB 609, defB 3,
hoverB2 3, semB 20, hlB1 7, hlB2 8 — (INC.13)'s numbers to the ms).

**THE ORDER GAP THE CENSUS LEFT IS CLOSED, AND IT CLOSED CLEANER THAN PROGRAM ORDER.**
The (INC.14) census modelled a SET of queries walked in program order; a host asks in
whatever order a user touches buffers and COMES BACK to a buffer another checker
already answered. The differential's new `editor` arm builds a deterministic shuffled
query SEQUENCE with revisits, chunks THAT into groups and compares POSITION BY
POSITION, and runs the COLD arm over the same sequence so the reference's own
order-dependence is a measured control (`coldSelfDiverged`, which REFUSES the run if
non-zero) rather than an assumption:

| k | cold | shared | ratio | rows that differ |
|---:|---:|---:|---:|---:|
| 3 | 51,996 ms / 101 builds | 24,088 / 34 | **2.16x** | 0 |
| 8 | 50,771 / 101 | 13,080 / 13 | **3.88x** | 0 |
| 26 | 51,728 / 101 | 9,992 / 4 | **5.18x** | 1 |

101 queries over 76 files, 25 revisits, **1,070,012 compared rows per run**, and
`coldSelfDiverged = sharedSelfDiverged = 0` in all three — a revisited file is
answered identically by a fresh checker AND by a reused one, which is the property no
file-keyed census could have tested. The one k=26 row is byte for byte the row program
order already found (`watchPublic.ts@24148`, the COLD arm inventing `X & X`), already
inside `capture-equivalence.sh`'s 5-span baseline.

**WHAT LANDED IS NOT THE RE-ENTRANT CHECKER, AND THE REASON IS THE CENSUS'S OWN
MODEL.** (INC.14) says "what is left is the refactor" — 416 pass rows classified
per-file vs program-wide, a diagnostics prefix to reset, every per-file side table to
reset. But the census's whole trick is that **a checker asked a k-th query IS a checker
whose partition is those k files**, and that arrangement is expressible with NO checker
surgery: hand `recheckOnly` the working set once and capture all of it. So `prepare` is
the census's SHARED arm made public. The refactor buys one thing this does not — a
working set the host did not have to name — and it is now the successor, (INC.17),
priced below.

**THREE RULES, EACH CARRYING ITS PIN, EACH ONE A PLACE THIS COULD HAVE FAILED
SILENTLY.** (a) The prepared slot is SEPARATE from the two-entry capture LRU, so an
ordinary hover in an unprepared buffer cannot evict what a prepare earned. (b) Serving
is decided by CONTAINMENT of the asked spans against the prepared REQUEST's own spans,
never by "is this file prepared" — an answer never asked for is ABSENT rather than
wrong, and a hover served from a check that did not carry its span renders NOTHING with
no error anywhere. (c) A prepared check may not answer `diagnostics`/`diagnosticsOf`:
§ 3's standing rule is that a capture build types nodes the checker had no reason to
type, so its diagnostics are not interchangeable with a plain build's.

**SEVEN ABLATIONS, SEVEN DISCRIMINATING, EACH WITH ITS OWN RED SET** — the first round
this session where no arm had to be recorded as a control:

    A1  `prepared = null` dropped from the 3 invalidation sites   -> 2 RED
    A2  `preparedAnswerFor` always null                           -> 3 RED
    A3  `diagnosticsOf` served only on an EXACT partition match   -> 2 RED
    A4  a subset answered with the SUPERSET's rows                -> 1 RED
    A5  `prepare` rebuilds what is already prepared               -> 1 RED
    A6  document highlights bypass the prepared check             -> 1 RED
    A7  containment weakened to file MEMBERSHIP                   -> 1 RED

A7 is the one worth keeping: it reddens only `a caret on a NON-occurrence in a prepared
file is still answered`, a pin that exists solely for it. The staleness pins edit
`t.ts` and query `a.ts` — (INC.12)'s gotcha is that a pin editing the file it queries is
vacuous, because the edit moves the request key rather than exposing a stale answer.

**WHAT A HELD PREPARED CHECK COSTS, WITH A CONTROL AND NOT AS AN ABSOLUTE.** A heap
reading taken right after the edit that dropped every cached answer and one taken after
the prepared queries are **163 -> 167 MB**, identical to the MB in all six rotations:
**~4 MB for a 415 KB working set of six files**. The bound is ONE prepared check,
replaced wholesale by the next `prepare` and dropped by any edit — a host cannot grow
it however it calls.

**WHAT WAS CONSIDERED AND REFUSED, WITH ITS ARITHMETIC.** Making the working set
AUTOMATIC — grow the partition to `{queried} ∪ {recently queried}` on every miss — was
refused before it was built: at k distinct files it costs `k·floor + k(k+1)/2·perFile`
against a cold `k·floor + k·perFile`, so with the floor at 342 ms and a median file at
47 ms it is a LOSS at every k, and bounding the growth at B files makes every miss
`(B−1)·perFile` dearer (+42% at B=4 on a median file, catastrophic on `checker.ts`).
A host knows its open buffers; this layer does not, and guessing is the one thing the
whole API refuses to do.

**GATES.** Suite **15,701 / 0 / 3** (+13, exactly this round's pins, summed with a
real XML parser over all six modules). `cost_gate.py` **PASS**, largest **+1.02%
`mapped.hits`** — the same pre-existing drift the last six rounds recorded, and the
EXPECTED answer for a round that changed no compiler code, i.e. a control rather than a
green light. `huge_methods.py --fail-over 0` green on core (755 classes, 16,170 methods,
0 over, largest 7,702) and on `-project` (50 classes, 478 methods, 0 over, largest
2,480). **All four equivalence sweeps exactly at their baselines**:
`partition-equivalence.sh` **EQUIVALENT on all 78 files** (median narrowed query 396 ms,
floor 365, ratio **13.37x**); `capture-equivalence.sh` **5 spans / 3 files,
`narrowRendersMoreAny = 0`**; `capture-channel-equivalence.sh` **286 rows / 49 files,
members=285 scopes=0 signatures=1**; `caret-vs-file-capture.sh` **EQUIVALENT, 904
spans**. And `checker-reuse-differential.sh` in BOTH orders, six runs between them.

**SUCCESSOR — (INC.17), and its price is already known.** The re-entrant checker buys
exactly the case `prepare` cannot: a query about a file the host did not name. Its
prize is the same 342 ms floor, its instrument is the differential (which needs no new
work — it already models the arrangement), and its cost is the 416-row classification
plus a diagnostics prefix and every per-file side table. The one thing to measure
FIRST, and it is cheap: how many of the 479 `pass(...)` rows ever touch
`checkedResults` at all. (INC.7) says 376 of 400 tail walkers iterate `binderResults`
and are partition-INVARIANT by construction — if that ratio holds for the whole init,
the replayable set is small and the refactor is a classification, not a rewrite.


### Round (INC.14/INC.15) — the checker CAN be reused: 1 divergent row in 741,864, and it is the shared arm that is right

**A `Checker` SHARED BY EIGHT QUERIES ANSWERS ALL EIGHT EXACTLY AS EIGHT FRESH
CHECKERS DO — 381,666 captured types, 360,152 captured definitions and 46
diagnostics over 76 of tsc's own compiler sources, and ONE row differs. In that
row the shared arm renders `(fileName: string) => boolean` where the cold arm
renders `(fileName: string) => boolean & (fileName: string) => boolean`, so the
divergence is a redundant self-intersection the PARTITION-OF-ONE invents and
sharing removes.** And the same run prices it: **cold 38,404 ms over 76 builds
against shared 12,035 ms over 10 — 3.19x.**

**THE MODEL IS WHAT MADE THIS COST ONE AFTERNOON INSTEAD OF A REFACTOR.** (INC.14)
says "do not start the refactor, start with the differential", and the differential
needs no re-entrant entry point at all: **a checker that has already answered k−1
queries and is asked a k-th IS a checker whose partition is those k files**, because
`recheckOnly` is a SET and the spine walks it in program order either way. So the
arms are `recheckOnly = {file}` per query (today's language service) against
`recheckOnly = group` once (one checker, k queries), and they must agree file for
file. No baseline is recorded and none is needed — both arms claim to answer the
same question.

**REPLICATED AT THREE GROUP SIZES, WHICH RE-GROUPS EVERY FILE.** Changing k changes
which files share a checker with which, so this is not one draw three times:

| k | cold | shared | ratio | divergent rows |
|---:|---:|---:|---:|---:|
| 2 | 39,173 ms / 76 builds | 21,918 ms / 38 builds | **1.79x** | 1 |
| 8 | 38,404 / 76 | 12,035 / 10 | **3.19x** | 1 |
| 26 | 39,508 / 76 | 10,347 / 3 | **3.82x** | 1 |

Every run: `types=1 definitions=0 diagnostics=0`, `sharedRendersMoreAny=0`,
`absentInShared=0`, `absentInCold=0` — and byte for byte THE SAME ROW, at
`watchPublic.ts@24148..24160`. The cold arm's own wall replicates to ±1.4% across
the three, which is the self-consistency check that says the ladder is one binary.

**THE CENSUS CLASSIFIED, IN (INC.2b)'s FIVE-MECHANISM STYLE — and it is one
mechanism, already catalogued.** The row is (INC.6)'s fifth reversed row, the one
its session note recorded as "1 in `watchPublic.ts` rendering a signature twice": it
is inside the **5-span baseline `scripts/capture-equivalence.sh` already gates**, so
checker sharing introduces NOTHING the full-vs-narrow sweep had not already found,
and reproduces only 1 of those 5. **Nothing in the other four mechanism classes: no
lost member resolution, no widening to `any`, no definition changed, no diagnostic
changed.** It fires at k=2 as well as k=26, so it takes ONE companion file, not many
— which is the tell that it is a first-touch display artefact of checking a file
ALONE, not an accumulation effect.

**SO (INC.14) IS NOT REFUSED. Its soundness question is answered with a number, and
what is left is the refactor** — a re-entrant "now check THIS partition" entry point,
deciding for 416 pass rows which are per-file and which program-wide, resetting the
diagnostics list to the program-wide prefix, and resetting every side table a
per-file pass writes. The census says the caches it would carry cost 1 display row.

**(INC.15) IS REFUSED, AND THE REFUSAL IS A RE-PRICING RATHER THAN A SOUNDNESS
FINDING.** The mechanism checks out on today's binary: `--bindMutationCheck` reads
**`binder Symbols checked 15580, changed 0`** over a population reaching
transitively through `locals` + `nodeToSymbol` + every `members`/`exports` table, in
the SAME run as `mergeSingleSymbol: adopts 406, mutates 175 (164 reaching an adopted
symbol)` — all 175 land on LIB symbols, which are in no program `BinderResult`.
`mergeModuleAugmentations` was read as the queue entry asked: three of its four
writes are idempotent by construction and the fourth (`mergeSymbolTable` into an
`exports` table) is NOT, because `mergeSingleSymbol` does a bare
`declarations.addAll` — it simply never reaches binder-owned state here, which is
what the zero says. **What refuses it is the population.** Bind is **66–74 ms of a
359–407 ms floor**, i.e. 12.8% of `diagnosticsOf(binder.ts)`, **10.7%** of a first
hover in that buffer, **3.1%** of a query about `checker.ts`, and **2.75% of the
whole 15-query editor sequence** — and it is worth **0** on the first query after an
edit, which is the error-reporting query the owner directive names. **And it is the
wrong order: a reused `Checker` carries its own bind, so (INC.14) subsumes (INC.15)
by construction** and would throw away its four layers of plumbing.

**A THIRD FACT THAT ONLY READING FOUND: the shape gate (INC.15) demands cannot be
evaluated before a build.** The checker's own merge predicate is
`moduleLocalContributesGlobally`, which reads `umdGlobalNames` and
`mergeSharedKeepNames` — both computed INSIDE `Checker`'s init — so the design is
necessarily "build once fresh, reuse only if that build reported clean", and the
first query of a session never benefits either.

**THE SUCCESSOR THE REFUSAL FOUND, WITH ITS BLOCKER ALREADY NAMED — (INC.16).**
The floor table says `bind` is 74 ms of which **`bindLexicalScopes` is 69** and
`bindStatements` 5: the INV.2(c) tables ARE the bind, and (INC.9)'s deferral
template is the obvious shape. It does not apply as-is, and one line says why:
`Checker.kt:13536` is `for (result in binderResults) for ((_, scope) in
result.lexicalScopes)` — a program-wide block-scoped enum/type-alias collector that
would force every file's table anyway. The question is therefore whether that
collector can be served by a PROJECTION of the pass rather than by all of it.

**THREE ABLATIONS, AND ALL THREE ARE RECORDED AS CONTROLS RATHER THAN CLAIMED.**
`ProjectCheckerSharingTest` (5 tests, one of them the non-vacuity control) stayed
green under every arm, and round 902's rule says that is as often a DEAD ARM as a
blind pin — so each is diagnosed rather than filed as "the guard is redundant":

    A1  (INC.6)'s mint-time `symbolTypes[copy.id] = copyType` removed   -> 0 RED
        NOT provably reached: no cheap positive control says the fixture's
        `Readonly<Program>` enters `materializeModifierUtility` at all.
    A2  `SYMBOL_TYPE_ORDER_GATE = false` — round 778's write gate off,   -> 0 RED
        i.e. `symbolTypes` persists a resolution taken under a non-empty
        instantiation context. Reached on every `getTypeOfSymbol`, but the
        fixture may present no context-bypassed resolution at all.
    A3  `buildFileLocalTypeMaps` starved onto `checkedResults` — round   -> 0 RED
        609's collector mistake, and DEMONSTRABLY DEAD here: that pass
        resolves only Function/Class/Interface/Enum/TypeAlias/Alias
        symbols, and the only such local either checked file holds is the
        import alias `make`, which BOTH arms resolve.

**AND THE FIXTURE HAS A STRUCTURAL HALF-BLINDNESS WORTH RECORDING, because it is a
property of captures and not of this fixture: a capture is recorded DURING the walk,
so a file walked LATER cannot influence an earlier file's captured answers in either
arm.** Only the second-walked file's rows are sensitive, which halves what any such
pin can see and is the reason the sweep — 741,864 rows over 76 files in every walk
position — is the discriminating instrument here and the pin is a regression fence.

**GATES.** Suite **15,688 / 0 / 3** (+5 pins, summed with a real XML parser over all
four modules). `cost_gate.py` **PASS**, largest **+1.02% `mapped.hits`** — the same
pre-existing drift the last five rounds recorded, and the EXPECTED answer for a round
that changed no compiler code, i.e. a control rather than a green light.
`huge_methods.py --fail-over 0` green on core (largest 5,204) and on `-project`
(largest 275). `scripts/partition-equivalence.sh` **EQUIVALENT on all 78 files**
(median narrowed query 421 ms, floor 335, ratio **11.92x** — a redraw of the same
binary, which this round did not touch). `scripts/capture-equivalence.sh` **5 spans /
3 files, `narrowRendersMoreAny = 0`**; `scripts/capture-channel-equivalence.sh`
**286 rows / 49 files, members=285 scopes=0 signatures=1**;
`scripts/caret-vs-file-capture.sh` **EQUIVALENT, 904 spans over 76 files** — all three
exactly at their baselines.


### Round (INC.13) — the question a hover asks is now the BUFFER's, and a free differential said it was safe

**HOVERING AROUND A FILE IS FREE AFTER THE FIRST HOVER. A SECOND CARET IN
`checker.ts` WENT 2,142 ms -> 73, ONE IN `binder.ts` 481 -> 2, AND
`fileSemantics` AFTER A HOVER 575 -> 17 — AND THE ONLY THING THAT PAID FOR IT IS
THE FIRST QUERY IN A BUFFER, +27% ON `binder.ts` AND +65% ON `checker.ts`.**

(INC.12) memoized a capture build on its REQUEST, so a question asked twice was
free. Every caret-scoped query except `documentHighlightsAt` asked about ONE
span, so the caret NEXT DOOR was still a full build — the whole ~345 ms floor for
a question about a buffer the compiler had just walked. `Project.captureAround`
now widens the question to the file: the population is
`SourceIndex.occurrenceNodes()`, which is DELIBERATELY the population
`documentHighlightsAt` already sweeps, so hover, go-to-definition,
`semanticsAt`/`fileSemantics` and highlights ask ONE question per buffer and
share ONE memo entry.

**THE ORACLE WAS BUILT FIRST AND IT COST NO BASELINE — WHICH IS THE PART WORTH
COPYING.** At a fixed partition, a span asked ALONE and the same span asked as
part of its file's whole set are the same question, so any divergence is a defect
in one arm and nothing has to be recorded to compare against.
`scripts/caret-vs-file-capture.sh` (12 evenly spaced carets per file, one build
each, against one whole-file build) reads:

    compared: spans=904 over 76 file(s)
    caret-arm type answers=904  definition answers=848
    file-wide request sizes min=4 median=1,559 max=125,289
    one-caret capture : median 373 ms  mean 412  slowest 2,161
    whole-file capture: median 390 ms  mean 483  slowest 3,949
    EQUIVALENT

**Zero divergences**, and the second finding is the price: a whole-file capture is
**+17 ms at the median file**, because a narrowed build is mostly FLOOR and the
extra spans are cheap beside it. The risk this was built for is real and named —
a capture types nodes the checker had no reason to type, typing populates
`symbolTypes`/`aliasDisplayMap`/lazy member tables, and (INC.10) refused a 66 ms
saving because that mechanism moved capture divergence from 5 spans to 2,722. It
simply does not fire here, and the reason is worth stating: (INC.10) changed WHEN
the compiler resolves a file's declarations, where this only changes how many
spans a walk RECORDS at.

**REPLICATED AT DIFFERENT POSITIONS, WHICH IS THE PART A SINGLE DRAW CANNOT
CLAIM.** The sample is deterministic and evenly spaced, so changing its SIZE
changes every position in it: a second sweep at **13** carets per file compared
**979** spans and read **EQUIVALENT** again. Two draws, **1,883 sampled
positions**, zero divergence. The price replicates too and is therefore quoted as
a range — the whole-file capture is **+9 to +17 ms at the median file**
(372 -> 381 and 373 -> 390).

**MEASURED, BLOCKED ARMS, SAME BINARY WITH THE WIDENING OFF AND ON**
(`scripts/warm-program-cost.sh`, compiler profile, warm, three rotations; blocked
rather than interleaved because one arm's whole point is that some code does not
run — round 871):

| | before | after |
|---|---:|---:|
| first hover, `checker.ts` (3.15 MB, 125,289 spans) | 2,307 | **3,796** |
| a SECOND caret, `checker.ts` | 2,142 | **73** |
| first hover, `binder.ts` (7,787 spans) | 481 | **610** |
| a SECOND caret, `binder.ts` | 481 | **2** |
| `fileSemantics(binder.ts)` after that hover | 575 | **17** |
| `definitionsAt` after that hover | 2 | 2 |
| `diagnosticsOf` rows, and the FrontEnd floor | 2,070 / 557 / 356+332 | 2,325 / 546 / 339+343 |

**Break-even is the SECOND caret**, and the controls say the change is confined
to the API: the floor table and every `diagnosticsOf` row are flat, because
nothing in the compiler was touched.

**THE RECEIPT IS A COUNT, NOT A ms: N carets in one buffer is ONE build.** Pinned
from a FRESH state (`six carets in one buffer are ONE build, batched or not`), so
it is not a statement about the memo's hit rate.

**IT DOES NOT WIDEN UNCONDITIONALLY, AND THAT IS THE ONE PLACE A SILENT WRONG
ANSWER COULD HAVE COME FROM.** A caret can land on a node that is no occurrence —
a call expression, a numeric literal, a `this` — and a file-wide request would
simply not carry it, which renders NOTHING with no error anywhere. So the
widening is conditional on every asked node being in the file's set, and anything
else is asked about alone.

**THREE ABLATIONS, AND ONE OF THEM CAUGHT A BLIND PIN.**

    A1  never widens (the coverage test off by one)  -> 4 RED: the headline pin,
                                                       the four-member sharing pin,
                                                       the six-carets pin, and the
                                                       cost table's build column
    A2  widens unconditionally                       -> 2 RED: the fallback control
                                                       (uniquely its own) and an
                                                       independent hover negative
                                                       control
    A3  the two sides of the shared population drift -> 1 RED, alone... but only
        apart by one method name                        after the fixture was fixed

**A3 read 0 RED on the first pass and the pin was BLIND, not the invariant
redundant**: the fixture declared its member with an identifier key and read it
with a dot, so that file's identifiers and its occurrence nodes were the SAME
set and either population satisfied the pin. One line (`const third = o["p"];`)
puts a member-name LITERAL in the file — the exact element the two populations
differ by — and the arm then reddens exactly one test. The memo's BOUND pin is
reddened by no arm and is recorded as what it is: it tests the bound, which
(INC.12)'s own A3 already discriminated.

**THREE PUBLIC CLAIMS CHANGED AND ALL THREE ARE INVERTED IN PLACE.** `a DIFFERENT
caret's hover still builds` -> `builds NOTHING` (with both answers asserted, so a
memo returning one span's answer for every caret could not satisfy it); `a batched
query over six spans costs ONE build where six single queries cost six` -> six
carets are one build either way; and the memo's BOUND is now about how many
BUFFERS stay warm rather than one caret-scoped entry plus one file-wide one, so it
is pinned with three files and with the LRU's ACCESS order asserted (the third
file is still resident after the eviction). `docs/language-service.md` carried a
34x batching ratio as its headline advice to hosts; that ratio is GONE, and its
disappearance is the finding — batching is now a convenience, not a cost decision.

**WHAT DID NOT WORK / WAS NOT DONE.** The bind is still not reused: (INC.13)'s
queue entry carried a second half ("then, if it holds, the bind", 73-88 ms = 20%)
and it is left whole as **(INC.15)** — it is a soundness change (a program-SHAPE
gate reusing the checker's own merge predicate) and does not belong in the same
round as a change to what every hover asks. The first-query regression was NOT
gated on file size: a size heuristic would be a guess where the differential is a
measurement, and break-even at two carets does not justify one.

Suite **15,683 / 0 / 3**. `cost_gate.py` PASS (largest +1.02% `mapped.hits`, the
same pre-existing drift, and the expected answer for a round that changed no
compiler code — a control, not a green light). `huge_methods.py --fail-over 0`
green on core (755 classes) and `-project` (49). `partition-equivalence` **EQUIVALENT on all 78 files** (median narrowed query 385 ms, floor 365, ratio 12.60x — a redraw of the same compiler, which this round did not touch), and both capture censuses **unmoved at their baselines** (5 spans / 3 files, `narrowRendersMoreAny=0`; 286 rows / 49 files, members=285 scopes=0 signatures=1).

### Round (INC.12) — the warm program PRICED, and the one part of it that needed no compiler change LANDED

**(P1) IS THE WHOLE FLOOR — ~345 ms A QUERY — AND (P2) IS WORTH ESSENTIALLY
NOTHING TODAY. BOTH ARE MEASUREMENTS, AND THE SECOND ONE IS THE MORE USEFUL
FINDING.**

Measured before anything was built (`scripts/warm-program-cost.sh`, the compiler
profile, warm, three rotations, two vacuity controls):

    diagnosticsOf(checker.ts)                    1,999 ms
    the same question again (already memoized)       0
    diagnosticsOf(binder.ts), NOTHING changed      505
    quickInfoAt(checker.ts, caret1)              2,205
    quickInfoAt(checker.ts, caret2), unchanged   1,901
    quickInfoAt(checker.ts, caret1) AGAIN        1,933
    diagnosticsOf(checker.ts) after editing it   2,001
    diagnosticsOf(binder.ts) after editing A       498

**(P1) and (P2) cost the SAME, which is the finding**: outside the content-keyed
parse cache and `diagnosticsOf`'s exact-question memo there was NO cross-query
reuse of any kind — re-asking one hover cost a full build. The FrontEnd phase
table of a floor build says what that build is:

| | ms | reusable when NOTHING changed? |
|---|---:|---|
| config + crawl + imports | ~12 | yes, and parses are already content-cached |
| BIND, all program files | **73-88** | wholesale yes for an all-module program; NOT per file |
| CHECK, the ~190 program-wide `init` passes | **252-254** | only by reusing the `Checker` |
| the queried file's own checking | 47 median, 150 `binder.ts`, ~1,650 `checker.ts` | never |

**LANDED — STAGE 1: A CAPTURE BUILD IS MEMOIZED ON ITS REQUEST (`767f4d5f`).**
`Project.captures`, two entries, access-ordered, dropped wherever `cached` and
`narrowed` are. It collects the part of (P1) that needs no compiler change — the
case where the QUESTION repeats — and two of the editor's commonest sequences
turn out to be exactly that:

    quickInfoAt(caret) then definitionsAt(caret)     506 ms -> 0
    documentHighlightsAt(caret1) then (caret2)       592    -> 19
    quickInfoAt(caret) twice                       1,933    -> 0

Neither is special-cased, and that is the design: **hover and go-to-definition at
one caret build an IDENTICAL `TypeCaptureRequest`** (both name the caret's node as
a single span and read different channels of the one answer), and
**`documentHighlightsAt`'s request is derived from the FILE's occurrence nodes and
not from the caret at all** — the caret only picks the seed afterwards. Keying on
the REQUEST rather than on the caret is what makes both fall out. The 19 rather
than 0 is the honest figure: the build is gone, the per-caret grouping over the
file's occurrences is not. A third hit appeared unlooked-for and is real —
`fileSemantics(f)` and `documentHighlightsAt(f, ·)` can ask the same question,
because a file's identifiers and its occurrence nodes can coincide.

**BOUNDED AT TWO, AND THE BOUND IS THE DESIGN.** A `ProjectCompiler.Result` holds
values only (no AST, no `Symbol`, no `Type`) but a file-wide capture holds one
answer per identifier, which is tens of MB on a file the size of `checker.ts`.
Two covers the editor's actual pair — one caret-scoped request plus one file-wide
one — and the LRU is ACCESS-ordered, which is what keeps the file-wide entry
resident while the caret-scoped one is replaced on every caret move.

**THE PINS DISCRIMINATE, AND THE ABLATION SAYS WHICH DO NOT.** Three arms, one
mistake each, each diffed against the ablation's OWN snapshot (round 922: a
`git diff --shortstat` on a tree carrying the round's work is vacuous).

    A1  `updateFile` no longer drops the memo   -> RED: all three staleness pins
                                                   + the cost-table row
    A2  the memo never HITS                     -> RED: both reuse pins, the
                                                   "an edit drops the memo" pin
                                                   and the cost-table row
    A3  the bound raised 2 -> 4                 -> RED: the bound pin, alone

`a DIFFERENT caret's hover still builds` is green under all three and is recorded
as a CONTROL, not claimed as a pin: it asserts that a build HAPPENS, which every
arm satisfies. What it buys is the statement the two reuse pins need — the memo
answers the question it was asked and no other one.

**THE STALENESS OBLIGATION IS PINNED IN BOTH DIRECTIONS, INCLUDING THE ONE
CLAUDE.md CALLS THE DANGEROUS ONE.** A memo can only ever serve a stale answer
when the REQUEST is byte-identical across a change, so both pins keep the caret's
own file untouched and edit something else: one changes another file's content
(the answer must move `1` -> `"s"`), and one **ADDS A FILE TO THE PROGRAM** — the
direction where a mis-keyed hit is a MISSING FILE and not a wrong type, and the
only shape that sees it is an edit that changes what the program CONTAINS.

**REFUSED FOR THIS ROUND, WITH THE MEASUREMENT: STAGE 3, REUSING THE BIND.** It
is **73-88 ms of a ~402 ms median query, i.e. 20%**, and (INC.9) refused its
per-file form. The WHOLESALE form for an unchanged program is a different
question and is not refused by that argument — `--shareBind` (round 883) already
hands one bind to N concurrent checkers, and round 882 measured the checker
mutating ZERO binder-owned `Symbol`s on an all-module program. What stands
between it and landing is a SHAPE GATE that must reuse the checker's own merge
predicate rather than re-derive it, plus one mutation site this round found by
reading: `mergeModuleAugmentations` writes `targetResult.locals[exportName] =
augSymbol` and mutates `localSymbol.declarations`/`.exports`/`.flags` — every
write idempotent by construction (`if (decl !in ...)`, a same-value put, an `or`
of the same bits), which is WHY round 882's fingerprint reads zero, and which is
an argument that has to be checked rather than assumed for a program that is not
tsc's own sources. 20% is not worth a silent-failure-shaped change in the same
round that landed one; it is the next round's, with a full-vs-reused differential
sweep as its gate.

**REFUSED, WITH THE MEASUREMENT: STAGE 4, REUSING THE CHECKER.** It is the
remaining **252-254 ms, 63% of a query's floor and the largest single thing left**
— and it is the whole of the ~190 program-wide `init` passes, which run inside
`Checker`'s constructor. Reusing them across queries means giving `Checker` a
re-entrant "now check THIS partition" entry point, which means deciding, for 416
pass rows, which are per-file and which are program-wide, resetting the
diagnostics list to the program-wide prefix, and resetting every side table a
per-file pass writes. **And the caches it would carry across queries are the
order-dependent ones this whole session has been fighting**: `symbolTypes`
persists the first resolution (round 778), (INC.2)/(INC.5)/(INC.6) spent three
rounds on hovers rendering `any` because a different file resolved a type first,
and (INC.10) refused a 66 ms deferral because it moved capture divergence from 5
spans to 2,722. A reused checker makes WHICH QUERY RAN FIRST observable, on
purpose, for every query afterwards. The instrument exists
(`scripts/capture-equivalence.sh` as a warm-vs-cold differential) and the price is
worth a round; it is not worth one without that differential built first.

**(P2) IS THE ONE TO STATE PLAINLY, BECAUSE IT IS WHAT "INCREMENTAL" USUALLY
MEANS AND IT IS WORTH ~NOTHING WITH TODAY'S STRUCTURES.** The crawl is already 9
ms; the bind cannot be redone per file (every `BinderResult` from one `Binder`
shares that binder's `(pos, end)`-keyed maps, whose keys collide across files and
are last-wins in bind order — (INC.9)); and the ~190 program-wide passes are
program-wide by construction, which round 609 priced at 1,174 false positives for
one starved collector. So (P2) is not a caching problem at all: it is
"make ~190 program-wide products per-file decomposable", one at a time, and each
one is its own round. **Do not open it as a cache.**

**GATES.** Suite **15,681 / 0 failed / 3 skipped** (15,674 + this round's 7 pins), no corpus baseline moved.
`scripts/partition-equivalence.sh` **EQUIVALENT, all 78 files** (median narrowed query 382 ms,
floor 342, own checking 40, ratio at the median file **13.15x**).
`scripts/capture-equivalence.sh` **5 divergent spans in 3 of 76 files,
`narrowRendersMoreAny = 0`** and
`scripts/capture-channel-equivalence.sh` **286 rows in 49 of 76 files, members=285 scopes=0
signatures=1** — both unmoved, and
both exit non-zero BY DESIGN. `cost_gate.py` PASS, largest delta **+1.02% `mapped.hits`** — the same row and
figure the last three rounds recorded, i.e. pre-existing drift and not this change
(`spine.nodes` +0.00%, `output.errors` 46).
`huge_methods.py --fail-over 0` green on core (largest 5,204) AND on `-project`
(largest 246).

**NEXT LEVER, PRICED — AND IT IS STAGE 2, NOT STAGE 3.** The memo hits when the
QUESTION repeats; the next thing is to make more questions repeat. **Ask
`quickInfoAt`/`definitionsAt` the FILE's whole span set instead of the caret's
one span**, exactly as `documentHighlightsAt` already asks, and every caret in an
unchanged buffer becomes free after the first — the same 506 -> 0 the definition
arm just measured, but for a caret the user has not visited yet. Its price is one
build that is bigger than a single-caret build (`fileSemantics` measured 1,185 ms
against `quickInfoAt`'s 1,015 in round (INC.2b)'s battery) and its RISK is
named and testable: requesting more spans means more `typeToString` calls, which
FORCE resolutions earlier, which is exactly the first-touch mechanism (INC.10)
refused 66 ms over. **The oracle is free** — per-caret answers and whole-file
answers must agree span for span, no baseline needed — so build that differential
first and let it decide, the way (INC.10)'s three-point table decided
`buildFileLocalTypeMaps`.

### Round (INC.10) — the alias walk moved onto the ask, and the 66 ms row is REFUSED with a three-point measurement

**THE TWO ROWS WERE 95.5 ms OF A 305.3 ms FLOOR PASS TABLE. ONE OF THEM WAS
EMIT-ONLY WORK AND IS GONE; THE OTHER IS NOT A COLLECTOR OF ENTRIES AT ALL, IT
IS THE PROGRAM'S FIRST-TOUCH ORDER, AND ITS PRICE IS WHAT THE CAPTURE CHANNEL
RENDERS.**

Baseline re-taken on this binary before anything was touched
(`scripts/floor-decomposition.sh`, floor arm, mean of four instrumented draws):
**417 rows summing 305.3 ms**, `init:buildFileLocalTypeMaps` **66.07 ms**,
`init:trackAllImportReferences` **29.44 ms**, plain floor arms 443 early / 393
late.

**LANDED — `trackAllImportReferences` RUNS ON `isReferencedAliasDeclaration`'s
FIRST ASK (`c3add44a`).** The whole `trackReferences*` family's only mutation is
`referencedAliases.add`; that set's only reader is
`Checker.isReferencedAliasDeclaration`; and that method has exactly ONE caller —
one line of `Transformer`, reached only by `import x = require(…)` under
`module: preserve` without `verbatimModuleSyntax`. Round 738's `skipEmitOutputs`
gate means a `--noEmit` build never constructs a transformer at all, so on every
language-service query the walk filled a set nothing could read.

    pass table, floor arm      305.3 ms over 417 rows -> 274.8 over 416   (-30.5)
    init:trackAllImportReferences   29.44 ms          -> the row does not exist
    alias-reference walks, floor    78 (one per file, per checker) -> 0
    alias-reference walks, full     78                             -> 0   (--noEmit)
    narrowed query, median of 78    422 ms -> 402      ratio 12.43x -> 12.61x

**The banked ms EXCEEDS the row (30.5 against 29.44), which is the first time in
this arc that the (INC.7) discount has not applied** — and the reason is
structural rather than lucky: batches 1-3 banked 79-93% because a gated walker
was driving MEMOIZED type resolutions that the first later asker then paid for.
This walk resolves nothing. It reads the frozen AST, `result.locals` and one
symbol flag, so there is no relocation for a discount to describe.

**Deferring rather than gating on `skipEmitOutputs` is the point.** A static
`if (options.skipEmitOutputs) return` would be correct today, silent the day a
second consumer appears, and — because the corpus EMITS — would leave the
deferred path untested. Built on the ask, the ~13k-baseline corpus, thousands of
whose `.js` baselines pin exactly which imports survive elision, exercises this
path on every suite run. It also deletes N-1 copies of itself: `CheckerPool`
builds N checkers over one bind and each ran the walk in its own `init`, while
only the primary is ever questioned.

**AND THE FIXTURE THAT MEASURED NOTHING — AGAIN, AND CAUGHT BY THE ABLATION AND
NOTHING ELSE.** The first draft's elision pins used an ordinary ESM
`import { a } from "./x"`; both stayed GREEN against a binary with the ask
deleted, because ESM elision decides on its own evidence and never consults this
set. That is round 806's law in a new costume: a shape that looks like it
exercises a mechanism is not a pin until the ablation says so. The fixture is now
the ONE shape that reaches the consumer, as a pair (a referenced alias that must
survive, an unreferenced one that must not), and the ESM case is kept as the
CONTROL that records why — it also bounds the blast radius of the whole change.

    ablation A1, restore the eager pass  -> RED: `a fresh checker has not walked
                                            alias references`, `the pass table no
                                            longer carries an init row for the walk`
    ablation A2, delete the ask          -> RED: `asking … performs the walk`,
                                            `a second ask does not walk again`,
                                            `a referenced import-require survives
                                            elision through the deferred walk`

**REFUSED, WITH A THREE-POINT MEASUREMENT: `buildFileLocalTypeMaps`.** It was
built, measured, and reverted. The design was the one (INC.9) used for the flow
graph and the one the queue asked for — eager over `checkedResults` (a strict
no-op for full builds, since `checkedResults` IS `binderResults` when
`assignedFileNames == null`) plus an on-demand per-file build behind the pass's
ONE reader, which is keyed on `currentCheckFileName`. It works, and the receipt
is excellent: **file-local type maps built go 78 -> 3 on the floor arm and stay
78 -> 78 on a full build**, the row falls **66.07 -> 0.01 ms**, the pass table
falls to 232.1, the narrowed query median to 349 and the ratio to **14.17x**,
`partition-equivalence.sh` says EQUIVALENT on all 78 files, `cost_gate.py` is
unmoved, and the suite does not move a baseline.

**`scripts/capture-equivalence.sh` is what refused it, and no other gate could
have.** (INC.2) recorded that the check path and the capture path are DIFFERENT
RESOLVERS and that a green diagnostics sweep says nothing about captures; this
is that warning being collected.

| phase kept EAGER over all 78 files | `init:buildFileLocalTypeMaps` | capture divergence |
|---|---:|---|
| nothing — fully deferred | **0.01 ms** | **2,722** spans in 46 of 76 files |
| the `TypeAlias` symbols only | **6.81 ms** | **462** spans in 18 of 76 files |
| the whole DECLARATION branch | **64.94 ms** | **5** spans in 3 files — the baseline |
| (unchanged, for reference) | 66.07 ms | 5 spans in 3 files |

**So the deferrable part is 1.13 ms of 66, and the other 65 is buying the
RENDERING of 2,722 spans.** Every divergence is a display one and they run in
both directions — full `ModuleName` vs narrow `ModuleExportName`, full
`AssignmentPattern` vs narrow `ObjectLiteralExpression | ArrayLiteralExpression`,
and the residual 462 the other way round (full
`Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>` vs narrow
`ClassLikeDeclaration`). The mechanism is `aliasDisplayMap`: an alias name is
attached to an interned type at its FIRST mint, so resolving every file's
declarations up front is what makes the program's type display a function of the
program rather than of who walked first.

**THE GENERAL LESSON, AND IT RETIRES A QUESTION ROUND 829 ASKED.** That round
censused this pass as *"12,738 direct resolves producing 4,161 entries of which
1,499 are ever read, 278,355 misses"* and priced the deletable part at 47.1%.
Read-ness of the ENTRY was the wrong question. The pass has a second product it
was never censused for — the whole-program first-touch ORDER — and that product
has a consumer, at 2,722 spans in 46 of 76 files. Round 861 already warned that
any deletable-ms from that census is an UPPER bound because the MOVE test is
keyed on symbols; this is the same warning with a name and a number.

**GATES, on the landed tree.** Suite **15,674 / 0 failed / 3 skipped** (15,666 +
this round's 8 pins), no corpus baseline moved.
`scripts/partition-equivalence.sh` **EQUIVALENT, all 78 files**; sweep median
**402 ms**, floor 355, ratio at the median file **12.61x**. `cost_gate.py` PASS,
largest delta **+1.02% `mapped.hits`** — the same row and the same figure the last
two rounds recorded, i.e. pre-existing drift and not this change (`output.errors`
46, `spine.nodes` +0.00%). `huge_methods.py --fail-over 0` green on core (755
classes, 0 over) AND on `-project`. `scripts/capture-equivalence.sh` **5 spans in
3 of 76 files, `narrowRendersMoreAny = 0`** and
`scripts/capture-channel-equivalence.sh` **286 rows in 49 of 76 files,
members=285 scopes=0 signatures=1, `narrowRendersMoreAny = 168`** — both censuses
identical to baseline.

**NEXT LEVER, PRICED — AND THE TAIL IS NOW FLAT ENOUGH TO SAY SO.** The floor's
pass table is **274.8 ms over 416 rows**, of which `init:buildFileLocalTypeMaps`
is **69.1 ms and is now REFUSED**, eleven rows are over 5 ms, forty-six are over
1 ms, and the remaining **370 rows sum to 11.9 ms between them**. So the
"gate one more walker" arc has run out of population: (INC.7) batch 4's whole
remaining prize is the 46 rows between 1 and 5 ms, and its best-shaped cluster
(the 34 ungated walkers reaching `srcScan`) only banks when gated TOGETHER. The
honest ranking of what is left, largest first: `checkTypeArgumentConstraints`
22.9 (REFUSED — 120+ passes read its fields), `checkBaseClassImprovedMismatch`
18.3 (REFUSED — a rewriter), `checkInterfaceMultiBaseConflicts` 13.3 and
`checkPropertyOverride` 10.7 (both REFUSED by batch 3), `checkSubsequentVarTypes`
12.0 (REFUSED), `checkDerivedConstructorSuper` 9.8 (REFUSED),
`init:computeAllEnumValues` 9.3 — **which is the one large row nobody has looked
at, and the only unrefused member of the top eight.** Its instrument is this
round's: give it a produced-vs-consumed count first, then ask whether its product
has a second consumer the way `buildFileLocalTypeMaps`' turned out to have.

**AND THE 66 ms IS NOT LOST — IT IS BLOCKED ON ONE THING, WHICH IS THE BEST-VALUE
ITEM THIS ROUND PRODUCED.** The refusal above is a statement about
`aliasDisplayMap`, not about `buildFileLocalTypeMaps`: an alias name is attached
to an interned type at its FIRST mint, so the 65 ms is being spent to make that
first mint happen in a program-wide order. **83% of the divergence is plainly
`aliasDisplayMap` and costs 6.81 ms to keep eager; the residual 462 spans are
UNDIAGNOSED, run the other way round, and are what the other 58 ms buys.** So the
item splits: close the alias-display half properly, then classify the residual
before assuming it is the same thing — its rows look more like two different
`Type` instances than two renderings of one.** Two things make it attemptable:
the divergence census is a ready-made differential oracle that costs one
`scripts/capture-equivalence.sh` run and needs no baseline, and the three-point
table above says exactly how much of the divergence each phase owns. Two things
make it dangerous: round 754 already recorded that `aliasDisplayMap` excludes
`Type.Reference` DELIBERATELY (making a bare defaulted generic display as its
alias broke eight baselines of
`typeVariableConstraintedToAliasNotAssignableToUnion`), and display order is
pinned byte-for-byte by ~13k corpus baselines — so this is a logical-parity
conversation (`docs/logical-parity.md` § 2), not a refactor.

### Round (INC.9) — the floor re-decomposed, and the flow graph moved onto the ask: 514 -> 378 ms

**MEASURED FIRST, AND THE RANKING HAD MOVED.** (INC.3)'s decomposition was taken
at a 1,219 ms floor; after (INC.7) gated 68 tail walkers it is a different table,
and it was re-taken rather than scaled (`scripts/floor-decomposition.sh`, one
process, palindrome-drawn, `both.floor` arm, wall 523 ms — plain floor arms 601
early / 498 late in the same process, so read the ms at +-10% and the shares as
the measurement):

|  | (INC.3), 1,219 ms floor | today, ~523 ms floor |
|---|---:|---:|
| the ~400 tail walkers + `init:*` setup (CHECK) | 918.9 (75.4%) | **304.2 (58.2%)** |
| BIND | 240.6 (19.7%) | **197.8 (37.8%)** |
| — of which `FlowGraphBuilder.build` | — | **126.1 (24.1%)** |
| — of which `bindLexicalScopes` | — | 66.7 (12.8%) |
| — of which `bindStatements` | — | 6.8 (1.3%) |
| crawl WALL + config + imports + post | 30.8 (2.5%) | 18.4 (3.5%) |
| `checkSpine` | 0.1 | ~0 |

**So the live hypothesis in the queue — "bind is very likely now the largest
single component" — is HALF right, and the half that is wrong is the half that
matters.** BIND is 37.8% against CHECK's 58.2%, so it is not the largest
COMPONENT; but CHECK is ~190 passes whose largest row is 66 ms, while bind
contains ONE mechanism at **126 ms**, which is the largest single thing a
narrowed query pays for. `FlowGraphBuilder` it is.

**LANDED: `BinderResult.flowGraph` IS BUILT ON FIRST ASK (`a0a6d46b`).**

    floor, sweep harness          514 ms -> 378   (-136, -26%)
    narrowed query, median of 78  542    -> 422   (-120, -22%)
    ratio at the median file      9.70x  -> 12.43x
    the floor as a share of it    95%    -> 90%
    a median file's OWN checking  28 ms  -> 44    (its graph is now charged here)

**THIS IS THE CANDIDATE ROUND 865 PRICED AND REFUSED, IN THE REGIME THAT CHANGES
ITS POPULATION BY 130x.** `docs/perf/warm-flow-graph-attribution.md` § 9.3 rows
"skip a FILE whose graph is never read" at **52 of 123 files, 885 nodes, 0.3% of
the mints** and stops there. That number is right, and it is a number about a
FULL BUILD, where every checked file's spine setup asks for its graph. Under a
partition the same rule reaches **122 of 123 files**. A cost prior does not
transfer across regimes any more than it transfers across families (CLAUDE.md,
round 789) — and the refusal is still correct for the regime it was written in,
which is why this is an amendment and not a reversal.

**DEFER, NEVER OMIT — and the soundness argument is that the builder is PURE.**
Round 865's inverted failure direction is the whole constraint here: a missing
side table degrades to a correct fallback, but a missing FLOW NODE makes `flowAt`
answer null, nothing narrows, and the compiler emits a FALSE POSITIVE. Laziness
satisfies that exactly. It is sound because `FlowGraphBuilder` is a pure function
of the `SourceFile` — fresh builder per file, every cache an instance field, ids
that restart per file, no `Symbol` and no `Type` minted, and every global it
touches is a probe — so nothing about the graph depends on when it is built.
`lazy` (SYNCHRONIZED) rather than a nullable field is load-bearing: `CheckerPool`
and `--shareBind` both hand ONE `BinderResult` set to several threads, and an
unsynchronised field would publish a half-built graph there.

**THE PINS DISCRIMINATE, AND THE ABLATION SAYS WHICH ONES DO NOT.** Two arms,
one mistake each, on a committed tree. Restoring the EAGER build reddens exactly
two: `binding a file does not build its flow graph` and `a partition builds
strictly fewer flow graphs than the whole-program build`. The other laziness
assertions — same-instance-on-second-ask, and the asked file's own graph being
built — stay green and are recorded as CONTROLS, because an eager build satisfies
both. Emptying the deferred graph reddens the narrowing half and leaves the
block-shaped guard green, which is the asymmetry that says the fixture is about
the FLOW GRAPH and not about narrowing in general.

**AND THE FIXTURE THAT MEASURED NOTHING, AGAIN.** The core class's first draft
probed narrowing with a property READ (`x.length` on `string | number`) and was
VACUOUS IN BOTH DIRECTIONS: this checker reports nothing there un-narrowed, so
the positive assertion and the ablation would both have passed forever. Its own
NEGATIVE CONTROL is what caught it — the third time in this arc that a control,
not a pin, is what earned its place. The probe is now an assignment to an
incompatible type (CLAUDE.md's standing rule for narrowing probes), and the
block-shaped guard sits beside it as the control that separates the two
narrowing mechanisms.

**REFUSED, WITH THE MEASUREMENT: REUSING BINDER OUTPUT ACROSS QUERIES.** The
queue's step 2 asked for a bind cache keyed on content — whole-program (a) or
per-file (b). After this round the whole of bind is **72 ms** (decls 7 + lexical
scopes 67) of a 378 ms floor, so the ceiling on (a) is 19% and on (b) rather
less. Against that: every `BinderResult` from one `Binder` SHARES its
`nodeToSymbol` and `moduleInstanceStates` maps — they are the binder's own
fields, accumulated across files and keyed by `(pos, end)`, which COLLIDES across
files — so reusing 77 results and re-binding one does not reproduce today's
tables; and `Checker.mergeSingleSymbol` ADOPTS binder-owned symbols into
`globals` while `declarations.addAll` is not idempotent (round 881: adopts 406,
mutates 175), so a second consumer over reused tables appends duplicates. That is
a large, silent-failure-shaped change for at most 72 ms, when the same 72 ms sits
beside a 304 ms pass table nobody has narrowed yet. **Not attempted; priced.**

**THE RECEIPT IS A COUNT, NOT A ms — AND IT SAYS THE CHANGE ALSO REACHES FULL
BUILDS.** `FrontEnd`'s per-file flow-graph census, same instrument before and
after:

    flow graphs built, FLOOR arm     123 -> 0
    flow graphs built, FULL arm      123 -> 78

The 45 that vanish from the FULL build are the real-lib `.d.ts` files: they were
bound — flow graph included — on every compile this repo has ever run, and no
consumer has ever read one. They are cheap (bind's three sub-rows summed to its
own row before, so the lib binds were ~2 ms between them), which is why no wall
moves and why only the count says it happened. The floor's own decomposition
after the change:

    BIND      197.8 -> 78.8 ms   (decls 7.1 + lexical scopes 73.4, and NO flow row)
    CHECK     304.2 -> 341.2     (drift: the same arm's wall fell 506/541 -> 420/468)
    PLAIN floor arms  601/498 -> 480/384

**GATES.** Suite **15,666 / 0 failed / 3 skipped** (15,655 + this round's 11 pins), no
corpus baseline moved. `scripts/partition-equivalence.sh` **EQUIVALENT, all 78 files**.
`cost_gate.py` PASS — largest delta **+1.02% `mapped.hits`**, which is the SAME row and the
same figure batch 3 recorded, i.e. pre-existing drift and not this change (`spine.nodes`
+0.00%, `output.errors` 46). `huge_methods.py --fail-over 0` green on the core module AND on
`-project`. `scripts/capture-equivalence.sh` **5 divergent spans in 3 of 76 files,
`narrowRendersMoreAny = 0`** — unmoved, and its own timing arms improved with everything else
(narrowed capture median **556 -> 420 ms**; `binder.ts` warm rotated **7.51x -> 8.31x**).
`scripts/capture-channel-equivalence.sh` **286 rows in 49 of 76 files, members=285 scopes=0
signatures=1, `narrowRendersMoreAny = 168`** — every count identical to the baseline, and the
168 is still the 167 `<K extends any>` spellings plus the one real row, not 168 wrong types.
Both capture runners exit non-zero BY DESIGN; what matters is that their censuses are
unmoved, and they are.

**NEXT LEVER, PRICED.** The floor is now CHECK-dominated again: 304 ms over ~190
passes, of which `init:buildFileLocalTypeMaps` is **66 ms** and
`init:trackAllImportReferences` **30 ms** — two program-wide SETUP passes that
are 32% of the whole remaining floor between them, i.e. worth more than every
remaining tail walker put together. Neither is gateable onto the partition as
(INC.7) gates an emitter (both are collectors, and round 609 priced a starved
collector at 1,174 false positives), so the question is whether either can be
made LAZY per file the way the flow graph just was — `buildFileLocalTypeMaps`
already has a census (round 829) saying 1,499 of its 4,161 entries are ever read.

### Round (INC.7) batch 3 — 45 tail walkers gated, floor 789 -> 514 ms, and the discount reached 92.9%

**LANDED**: 45 walkers moved from `binderResults` to `checkedResults` in three
gated sub-batches (`e7ccd21f` 15, `d2c0cb36` 15, `2fb81e83` 15), plus four pins
(`68635ffc`). Every sub-batch was swept with `scripts/partition-equivalence.sh`
before the next was applied, and every sweep read **EQUIVALENT over all 78
files** — with a baseline sweep taken at HEAD first, so the after-runs are
attributable rather than merely green.

    floor (sweep harness)         789 ms -> 621 (3a) -> 533 (3b) -> 514 (3c)
    narrowed query, median of 78  818    -> 681    -> 584    -> 542
    ratio at the median file      6.17x  -> 7.37x  -> 8.57x  -> 9.70x
    floor as a share of a query   96%    -> 91%    -> 91%    -> 95%

**THE DISCOUNT KEPT SHRINKING, WHICH WAS THE ROUND'S LIVE PREDICTION.**

                       batch 1    batch 2    batch 3
    naive sum          162.6 ms   221.3 ms   295.9 ms
    actually banked    128.4      189.1      275
    ratio              79.0%      85.5%      92.9%

The mechanism is the one batch 1 stated: relocation lands on ungated tail
walkers, so the more of them are gated, the more of the moved work is never
asked for at all. **Do not read the PER-SUB-BATCH split as three more data
points** — it reads 137%, 81%, 30%, and the >100% is real but is batch 2's
relocation being collected at the same time (see below), while each sub-batch's
floor is a single four-sample draw against a 19-168 ms effect. The batch-level
92.9% is over a 275 ms delta and is the number to quote.

**THE VICTIM HEURISTIC MISFIRED, AND THAT IS WHY 3a BANKED MORE THAN ITS ROWS.**
The queue said to start by reading `checkBaseClassImprovedMismatch` (0.07 ->
17.89 ms), batch 2's relocation victim, on the rule that the victim is always
the next candidate. It is a REWRITER (`for (i in diagnostics.indices) { …
diagnostics[i] = d.copy(messageChain = …) }`) and can never qualify. Its 17.89
ms was not relocated type work at all but an inherited lazy per-file
`SourceScanFilter` build (`SrcScanCache.filterFor`) — so it was the first
walker to ASK, not the walker that COSTS. Gating batch 3a moved that asking to
walkers that are themselves now gated, which is why 3a shed 168 ms against a
122 ms row sum. **A relocation victim is a lead about WHERE the next ask lands,
not a candidate**; check what kind of work moved before treating it as one. 34
ungated walkers still reach `srcScan`, and that family only banks when gated
together.

**WHAT DID NOT WORK, AND WHAT IT TAUGHT.**

1. **The pin written for the batch's biggest privacy clearance measured
   nothing.** `checkMixinClassConstructor` (9.71 ms) emits TS2545, and the
   fixture written for it produced ZERO rows in either arm — the two equality
   assertions compared empty lists. What failed was the CONTROL, and it failed
   in the way that names the cause: a whole-program build cannot be moved by
   this change at all, so a red control is a statement about the fixture and
   never about the gate. `emitTs2545IfBrokenMixin` needs a type parameter whose
   constraint is a constructor type with an OPTIONAL REST PARAMETER
   (`dotDotDotToken && questionToken`), a corpus-unique B72.1 shape.
   `checkMissingImplementations`/TS2391 carries the privacy-clearance pin
   instead, and the test records why TS2545 is unpinned rather than leaving a
   plausible dead assertion behind.

2. **Two walkers were REFUSED on a rule that turns out to be the wrong way
   round.** `checkImportTypeUsedAsType` and
   `checkBareAtTypesExportEqualsMissingNamedImport` pass every body-level test
   but have a PRIVATE HELPER that itself scans `binderResults`. That looked like
   a cross-file lookup wearing a per-file loop, so both were refused — and then
   `checkMixinClassConstructor`, which was GATED, turned out to have two of
   them (`findTypeParamDeclByName`, `findTypeAliasByName`). Reading them
   settles it: such a helper is **not a pass, so it is never gated**, and it
   keeps resolving against the whole program while the walker's own loop
   narrows — which is exactly the behaviour wanted.
   `visitBareImportType`'s scan is `binderResults.any { … declare module "fs" … }`,
   a whole-program ambient-module RESOLUTION. **The count is how you find the
   sites; the direction is how you judge them.** Both are batch-4 candidates
   (3.3 ms), cleared by reading rather than refused.

3. **33 of the 252 ungated `binderResults` loops are not passes at all** —
   `resolveIdentifierInFile`, `findTypeParamDeclByName`, `getEnumMemberValue`,
   `isReferencedAliasDeclaration`, `computeTypeParamInfo`, … Gating one breaks
   NAME RESOLUTION rather than dropping a diagnostic, they are all 0.00 ms, and
   a mechanical `sed` over the loop header hits every one. This is the single
   most important guard in the round and it is now in CLAUDE.md.

**THE FINAL GATE SET, AND THE FLOOR DECOMPOSED BEFORE AND AFTER.**

    the 45 gated rows          295.86 ms -> 0.00   (they no longer run at the floor)
    pass-table total           581.2     -> 268.8  (shed 312.4)
    floor, sweep harness       789       -> 514
    floor, PLAIN early median  883       -> 548
    floor, PLAIN late median   808       -> 506
    narrowed capture, median   n/a       -> 556 ms; binder.ts warm rotated 7.51x

    suite            15,655 / 0 failed / 3 skipped  (15,648 + the 7 new pins)
    cost_gate.py     PASS, largest counter delta +1.02% (`mapped.hits`)
    huge_methods.py  PASS at --fail-over 0, core AND the -project module
    capture-equivalence      5 spans, narrowRendersMoreAny=0  -- UNMOVED
    capture-channel          286 rows, members=285 scopes=0   -- UNMOVED

**RELOCATION IS ESSENTIALLY GONE, WHICH IS THE STRONGEST FORM OF THE PREDICTION.** The
pass table shed **312.4 ms** against a naive row sum of 295.86, and the single biggest
riser in the whole table is `checkProtectedAssignmentMismatch` at **+4.74 ms** (0.11 ->
4.85) — against batch 2's +17.82. So two of the three floor instruments read a banked
amount ABOVE the naive sum (PLAIN early 335 ms, PLAIN late 302 ms; the sweep harness, the
instrument used for every sub-batch, reads 275 ms = 92.9%), which is what it looks like
when a batch banks all of its own rows PLUS ambient work its walkers were the first to
ask for. **Both capture gates are censuses of the known (INC.2)/(INC.5)/(INC.6)
order-dependence and exit non-zero by design — what matters is that both counts are
unmoved, and they are.**

**METHOD.** The static classification inherited from the prior session was
re-derived rather than trusted: its snapshot (md5 `cdeafb06`) was no longer the
working tree (`bc815431`). The rebuilt analyzer agrees with it on 128 of 130
CLEAN walkers, and its stripper carries the control CLAUDE.md demands — 4,509
`fun` declarations in raw source, 4,509 in stripped, i.e. ZERO blanked, which is
the batch-2 failure mode that reported a confident "no hazard" over an EMPTY
closure. Every one of the 45 bodies was read.

**THE PINS DISCRIMINATE, AND THE ABLATION ALSO SAYS WHICH ONES DO NOT.** One
deliberate mistake — the partition filter at `Checker.kt:112` inverted to
`!in assignedFileNames`, i.e. the gate drops exactly the file it was asked
about — turns **10 of the 15** red: every "a partition of X keeps its own
walker's row" and every narrowed-query-through-the-public-API pin. The five
that stay green are green for a reason worth recording rather than for none:
the three whole-program CONTROLS cannot move (a full build has
`assignedFileNames == null`, so the inverted filter never executes — which is
the same property that makes this whole change a no-op for the corpus), and the
two "invents nothing" pins are filtered downstream by
`diagnostics.filter { it.fileName in assignedFileNames }` (`Checker.kt:12047`),
so a row invented for ANOTHER file cannot reach them. **They guard the round-609
direction — an invented row for the ASKED file — which this ablation does not
inject, and `scripts/partition-equivalence.sh` over 78 real files is their real
instrument.** Recorded as guards, not claimed as discriminating pins.

**(INC.2b) THE INTERACTIVE CAPTURE QUERIES ARE NARROWED — LANDED 2026-08-22, owner
directive. Hover, go-to-definition, completion, signature help, the semantic sweep and
document highlights each hand the compiler the queried BUFFER as its check partition.
Measured end to end through the API, two processes with three FLAT CONTROLS in each:
`quickInfoAt` 5,004 -> 1,015 ms, `fileSemantics` 5,178 -> 1,185, `documentHighlightsAt`
5,050 -> 1,159 — while `referencesAt` (8,846 -> 8,788), `renameAt` (20,597 -> 20,170) and
a plain rebuild (5,272 -> 4,835) do not move, because they are the two queries left
whole-program plus the build itself. Within ONE process, warm and rotated, the same claim
on `binder.ts`'s 7,787 spans is 4,581 -> 979 ms = 4.68x.**

**THE PARTITION IS DERIVED FROM THE REQUEST'S OWN SPANS, AND THAT IS THE WHOLE SAFETY
ARGUMENT.** Narrowing's failure mode is silent: a span in a file the checker never walks
is never walked PAST, so the answer is ABSENT rather than wrong — an empty tooltip, an
empty completion, no error anywhere. So `Project.captureIn` computes the file set from
the request instead of taking one beside it, and a call site cannot forget a file it asked
about because it never states the set. That is also what makes the pins DISCRIMINATE,
which an equivalence pin cannot: dropping any one of the four span lists from
`captureFiles` reddens exactly the queries that use it (`spans` -> hover, definition and
the semantic sweep; `memberSpans` -> member completion; `scopeSpans` -> free-name
completion; `signatureSpans` -> signature help). All four ablations were run.

**`referencesAt` AND THE RENAME SWEEP STAY WHOLE-PROGRAM, AND THE REASON IS THE CLAIM,
NOT THE MACHINERY.** They sweep every file, so a derived partition would name every file
anyway — no win, a different code path, and both additionally read the build's
DIAGNOSTICS, which a partition filters to its own files by design. **The flag that says so
is UNDISCRIMINATED and it is recorded rather than claimed** (round 807): flipping
`referencesAt`'s own `narrow = false` leaves all eight pins green, and must.

**THE EXISTING GATE COVERED TWO OF THE FIVE CAPTURE CHANNELS, WHICH IS A CONTROL AND NOT
A GATE.** `scripts/capture-equivalence.sh` sweeps captured TYPES and DEFINITIONS — what
hover, go-to-definition and the semantic sweep read. It says nothing about
`capturedMembers`, `capturedScopes` or `capturedSignatures`, and two of those three render
TYPE TEXT, i.e. carry exactly the first-touch identity risk the sweep exists to measure.
`scripts/capture-channel-equivalence.sh` is the second gate, deliberately SEPARATE:
adding spans to a request changes what the checker types and therefore the first-touch
order, so a merged runner's numbers would no longer be comparable to the ones every
(INC.2)/(INC.5)/(INC.6) round quoted. The old gate re-ran unchanged at **5 divergent
spans of 381,666 in 3 of 76 files, `narrowRendersMoreAny = 0`**.

**AND IT FOUND 286 DIVERGENT ROWS, WHICH ARE FIVE MECHANISMS — THE CENSUS IS THE FINDING
AND THE COUNT IS NOT.** A row in that channel is a LIST (a type's members, a scope's
names, an overload set), so ONE display mechanism reaches as many rows as the program has
carets on that receiver. The runner therefore aggregates the first DIFFERING ELEMENT and
prints a census of distinct causes; over 21,507 captures in 76 files:

- **x167** a member's own type parameter — `<K>` (full) against `<K extends any>`
  (narrow). NEITHER renders the declared constraint (`keyof typeof assertionCache`), so
  both arms are wrong alike and differ only in whether the unresolved one is spelled or
  omitted. Cosmetic, in a signature LABEL, and the narrow arm is the noisier of the two.
- **x116** `Intl.LocalesArgument` (narrow) against its expanded body with `| undefined`
  DOUBLED (full). tsc renders the alias, so the narrow arm is the better answer — the
  same `aliasDisplayMap` first-touch mechanism (INC.6) diagnosed in `watch.ts`.
- **x2** a generic member's `TData` (narrow) against `any` (full) — round 778's shape,
  narrow better.
- **x1** a signature parameter rendering `any` under the narrowed arm. **This is the only
  row in either channel where a narrowed answer is worse in the way a user would call
  wrong**, and it is 1 of 5,516 signature captures.
- SCOPES diverge NOWHERE (0 of 8,986), so free-name completion is equivalent outright.

`absentInNarrow` and `absentInFull` are both **0** in both channels: no partition ever
LOST an answer, in 402,000 captured spans. **`narrowRendersMoreAny = 168` is reported and
is not 168 wrong types** — 167 of them are the `extends any` spelling, which a substring
classifier cannot tell from a type; the census is what separates them, and a round that
read the flag without the census would have refused this on a false reading.

**WHAT DID NOT WORK.** (a) The mirror pin — "find references still answers about the whole
program" — passed against a sweep cut down to the queried file, i.e. for a reason it did
not name: `referencesOf` adds the SEED's own declaration locations as hits
unconditionally, so a declaration in the other file is reported whatever was swept. Only
a NON-declaration occurrence there discriminates, and the pin now asserts one. (b) Two
harness traps cost real time and both are CLAUDE.md's own, re-learned: a backgrounded
`sleep` is preempted by the next command, so an elapsed-time reading of a running
measurement is meaningless (`ps -o etimes=` said 5 minutes where the transcript implied
50); and `pgrep -f`/`pkill -f` with a pattern that appears in the poller's own command
line matches ITSELF — once reporting a finished sweep as RUNNING, once killing the
compound command that contained it (exit 144).

**(LIB.3) SIX CLI LIBRARIES SCREENED, 126 FALSE POSITIVES ROOT-CAUSED INTO FIVE FAMILIES —
2026-08-22, NO CODE LANDED. The largest is not a type-system defect at all: `// @ts-ignore`
suppresses NOTHING, in a compiler whose grep says the feature exists.**
Owner asked for a TS CLI library to drive a KIR performance comparison, knip having been
disqualified by (LIB.1). Six candidates fetched and put through (LIB.2)'s screen; the import
census alone disqualified `sql-formatter` (`nearley` imported inside `src`) before a compiler ran.

**THE SCREEN'S OWN LESSON CONTRADICTS THE ENTRY THAT COMMISSIONED IT.** (LIB.2) said to pick by
imports; that is necessary and it is not sufficient, because **the library closest to compiling
and the library best for benchmarking are different libraries**. `cronstrue` is the only
candidate the checker already passes — `typeErrors=0` over 52 files and 8,812 lines, agreeing
with tsgo exactly — and the only one whose lowering runs; but its per-call work is small, so it
benchmarks as a loop rather than as one heavy invocation. `marked` is the workload worth
publishing (markdown -> HTML over a big document) and is 15 checker errors plus a 76%-of-files
backend gap away. `fflate` would be the best number of all and is structurally blocked: **183
typed-array uses against a runtime with none.**

**THE ERROR ANALYSIS IS THE ROUND'S PRODUCT.** With `@types/node` present on both sides and each
library's own tsconfig, diffed against tsgo per `(file, line, code)`: marked 0/15, jsonrepair
1/16, fflate 2/17, yaml 0/78, cronstrue 0/0 — **126 ours-only rows, and tsgo at ZERO on two of
the four.** Five families carry 67 of them and each was reduced to a repro or an exact
correspondence, not to an inspection:

**(CHK.31) `@ts-ignore` / `@ts-expect-error` suppress nothing — and we are wrong in BOTH
directions.** A four-file repro: the directive above a TS2322 leaves it emitted, and an
`@ts-expect-error` above a clean line fails to produce tsgo's TS2578 `Unused directive`. On
`fflate` this is all 9 TS2391 rows, and the correspondence is exact — the file contains exactly
9 `@ts-ignore` comments. **It looks already done**, which is the trap: `CompilerOptions.kt:562`
parses both spellings and `Checker.kt:16167` consults one for a narrow commonjs suppression, so a
grep finds the feature. There is no general filter.

**(CHK.32) a primitive is not related to a structural object target through its apparent type.**
`jsonrepair` types its whole scanner against `interface Text { length; charAt; charCodeAt;
substring }` and passes a `string`; all 7 of its TS2345 rows are that call. The repro shows it is
not about `string` — `number` against `{ toFixed(d?): string }` fails identically — and the
object-source control in the same file passes, so it is the primitive side failing to reach
`getApparentType`.

**(CHK.33) a destructuring parameter breaks arity, and the message says so out loud: `Expected
1-0 arguments, but got 1`, 8 rows in `marked`.** This is round 921's documented hazard reaching a
diagnostic for the first time — `getParameterSymbols` drops binding-pattern parameters, so
`parameters` is empty while `minArgumentCount` still counts the pattern. **An inverted range is a
free assertion**: no correct signature has `minArgumentCount > parameters.size`, and requiring
that at signature construction would have caught this before a library did.

**(CHK.34) `isolatedDeclarations` over-reports 32 rows on `yaml`, which ships with the flag ON and
is clean under tsgo** — one member identified as an overload IMPLEMENTATION signature, which the
flag exempts. Deliberately sequenced LAST of the five: biggest row count, narrowest trigger, and
the 8 profiles do not set the flag, so every profile instrument is blind and `yaml` is the gate.

**(CHK.35) a function expression assigned through an index signature gets no contextual
signature** — TS7019 + TS2683×4 in `marked`. Filed with the instruction to check whether it and
(CHK.30) are one path before either is written.

**WHAT IS DELIBERATELY NOT CLAIMED: ~59 rows are NOT root-caused**, led by TS2322×14 (six of them
one shape — an excess `undefined` in `yaml/compose/resolve-props.ts`) and TS2339×7. The captures
regenerate in ~10 s per library and the entry says so rather than implying the tail is understood.

**GATES: none, and deliberately** — three markdown files changed and no Kotlin. The KIR module was
built only because the probe needs it.

**(LIB.1) knip MEASURED 2026-08-22 — NO CODE LANDED, AND THE MEASUREMENT IS THE
DELIVERABLE. 2,634 xtsc errors against tsgo 7.0.2's 23, of which 94.1% are ONE absent
lookup; and the backend is blocked by knip's DEPENDENCIES rather than by its TypeScript.**
Owner question: can we compile `webpro-nl/knip` to JVM bytecode. Answer: not today, and the
two halves fail for unrelated reasons — which is the whole value of running
`docs/kir-library-readiness.md`'s two-command loop instead of arguing about it.

**THE FRONT END IS ONE DEFECT WEARING A LARGE NUMBER.** 498 files / 35,663 lines, 7,131 ms
cold. TS1295×1,959 + TS1287×519 = **2,478 of 2,634**, every one of them saying "this is a
CommonJS file" about a package whose `package.json` says `"type": "module"`. We never read
that field, so under `moduleResolution: nodenext` the format defaults to CommonJS and
`verbatimModuleSyntax` rejects every import and export in the program. **The attribution was
confirmed rather than asserted** — deleting `verbatimModuleSyntax` from the tsconfig reads
**2,634 -> 156**, and tsgo re-run on the SAME config still reads 23, so the option is not
doing anything to the oracle. Queued (CHK.29).

**THE RESIDUAL IS 0.31 FP/file — BETTER THAN `yaml`'s 0.9 — AND IT IS ENTIRELY THE TWO
FAMILIES THE READINESS PAGE ALREADY NAMES.** TS7006×89 is 57% of it and is one shape: an
object-literal shorthand METHOD's parameters are not contextually typed from the annotated
return type ((CHK.30)). TS2339×23 is union member access after a narrow. **The overlap with
tsgo's 23 is ZERO IN BOTH DIRECTIONS**, so the honest figure is 156 false positives AND 23
false negatives — including two real TS2322 and a TS2722 in `util/glob-core.ts` that tsgo
reports and we do not. *A residual FP count is not a conformance number until the misses are
counted too*; the first draft of this note quoted 156 alone and was wrong to.

**WHAT PASSED, AND IT IS NOT NOTHING:** all **1,921** relative specifiers carry an explicit
`.ts` extension and every one resolved. (KIR.EMIT.1)'s `rewriteRelativeImportExtensions` work
holds on a codebase nobody wrote it for.

**THE BACKEND NEVER GOT A TURN, SO IT WAS MEASURED ON ONE FILE.** The project probe refuses
to lower a program the checker rejected, so `KIR_PROBE_FILE` was pointed at
`src/util/graph-sequencer.ts` — 131 lines, no imports, `typeErrors=0` — and the first refusal
is `a spread element is out of the spike subset`. Censusing knip against the 17 refusal
messages in `lower/`: destructuring parameter **51%** of files, spread 33%, destructuring
declaration 24%, `async`/generators 22%, computed property name 12%; the union is **237 of
498 files (48%)**. `async` alone is decisive — knip's entry point IS an `async` arrow.

**BUT THE LADDER IS THE WRONG THING TO COST, AND THAT IS THE ROUND'S REAL LESSON.** knip
imports **two native Rust N-API binaries** (`oxc-parser`, 32 sites; `oxc-resolver`) and **10
`node:` builtins** (`fs`×21, `fs/promises`×5, `util`, `path`, `module`, `crypto`, `url`,
`process`, `perf_hooks`, `child_process`), against a `KirIntrinsics.libraryClass` table of
**six** entries. Those have nothing to lower TO — no amount of language coverage reaches them.
So a candidate library must be screened by **what it imports**, not by its size or its error
count, and the screen is one `grep` over `src` ((LIB.2)). Both refusals to extrapolate here
are the same one the readiness page already made once, from the other side: it generalized
`yaml`'s front-end obstacle into a rule and `mitt` falsified it. **A library's obstacle is a
property of that library.**

**GATES: none run, and deliberately.** This round changed three markdown files and no Kotlin;
the suite, `cost_gate.py` and `huge_methods.py` have nothing to say about it. The KIR module
was built (`:xemantic-typescript-compiler-kir:compileTestKotlinJvm`, BUILD SUCCESSFUL) only
because the probe needed it. Scratch tree with the clone, the deps and both captures is under
the session scratchpad and is not committed.

**(INC.6) THE LAST WRONG-DIRECTION CAPTURE DIVERGENCES ARE GONE — LANDED 2026-08-22. The
sweep reads 9 -> 5 divergent spans of 381,666, and the class a user would call WRONG —
the narrowed build rendering `any` where the type is known — is at ZERO. Suite 15,640 / 0 / 3
(+6, this round's pin), no corpus baseline moved, cost gate a measured no-op, partition
sweep EQUIVALENT on all 78 files.**

**THE DIAGNOSIS HELD, AND THE TRACE SHARPENED IT INTO THE PART THAT EXPLAINS THE PREVIOUS
ROUND'S BLIND SPOT.** The queue said `materializeModifierUtility` mints FRESH copy symbols
per materialization, so a warmed `symbolTypes` entry dies with the instance. True, and not
what was failing: an instrumented run over `builderState.ts` showed `getTypeOfSymbol`
answering `Map<string & { __pathBrand: any; }, FileInfo>` on **every** ask and
`symbolTypes` holding **none** of the eight members — because the only writer of that entry
is `getTypeOfSymbol` itself and **round 778's write gate refuses whenever the ambient
instantiation context is non-empty**, which inside a `namespace` body it always is
(`inferenceNamespaceStack`). So (INC.5)'s capture-time force — ask `getTypeOfSymbol`, then
let `typeToString`'s RAW `symbolTypes[id]` read pick the answer up — is a no-op exactly
there, and is why its own pin was green: **that fixture captures at FILE level, where the
context is empty and the write lands.** The fix is `resolveReferenceMembers`' idiom, one
line: write the copy's type at MINT time, ungated, which is sound where `getTypeOfSymbol`'s
write is not because the id was minted by that materialization and is reachable only from
the type just built.

**WHAT DID NOT WORK, AND IT COST TWO BUILDS: TWO FIXTURES FAILED TO REPRODUCE BEFORE THE
THIRD DID, AND BOTH FAILED BECAUSE THEY VARIED THE WRONG END.** A `Readonly<I>` parameter
on a function inside `export namespace NS`, captured from another file — green. An
interface MERGED with a namespace whose body takes `Readonly<State>` — still green. What
discriminates is where the **CAPTURE** is, not where the `Readonly<>` is: move the capture
inside a namespace body and both arms render `fileInfos: any`. **So the defect is not a
partition defect at all** — the whole-program arm renders it too, and only the
full-vs-narrow sweep on a real project made it visible, because on tsc's sources some other
file's check happened to warm one arm. The pin therefore asserts BOTH arms, and its
whole-program assertion is one of the three that were measured RED on the un-fixed binary.

**THE COST GATE'S DRIFT IS PRE-EXISTING AND THE CONTROL IS WHAT SAYS SO** — (INC.5) recorded
the suspicion, this round measured it. The un-fixed binary prints the same
`typeOfExpr.calls +0.18%` and a *larger* `mapped.hits +1.14%` than the fixed one (6,465
against 6,457); `typeNode.bypassed` likewise moves the other way (111,017 -> 110,988). So
`docs/perf/cost-counters.txt` is stale at HEAD, this change is a no-op on all 20 counters,
and it is deliberately NOT rebaselined here — a rebaseline would silently adopt someone
else's drift.

**THE 5 REVERSED ROWS ARE THREE DISTINCT DISPLAY-ONLY MECHANISMS, NONE A LOST MEMBER
RESOLUTION, AND IN FOUR OF THE FIVE THE NARROWED ARM IS THE BETTER ANSWER.** Diagnosed by
dumping both arms' FULL strings (the sweep truncates at 140 chars, which is why the entry
could only call them "overload-set content"):

- **`watch.ts` x2, `toLocaleTimeString`** — identical overload sets; the third overload's
  parameter renders as the alias `Intl.LocalesArgument` under the narrow arm and as its
  expanded body `string | Locale | readonly (string | Locale)[] | undefined | undefined`
  under the full one. `aliasDisplayMap` registration is first-touch. tsc renders the alias,
  so the narrow arm is right — and the full arm additionally doubles `| undefined`.
- **`tsbuildPublic.ts` x2, `createNewValue`** — `interface MutateMapOptions<K, T, U>`
  declares `createNewValue(key: K, valueInNewMap: U): T` and the FULL arm renders `=> any`
  where the narrow renders `=> T`. This is round 778's shape verbatim, one member over: the
  un-instantiated member type resolved with no type-parameter scope installed answers `any`
  AND is the cacheable one, so the first toucher outside a scope freezes it. Fixing it means
  installing a generic interface's own type parameters before resolving its members — a
  checker-wide change with diagnostic blast radius, not a capture fix.
- **`watchPublic.ts` x1, `compilerHost.fileExists`** — the receiver is
  `CompilerHost & ResolutionCacheHost`, both constituents declare `fileExists`, and the
  narrow arm renders the member as `(fileName: string) => boolean & (fileName: string) =>
  boolean`. Redundant, not wrong: the two constituents' member types are equal in shape and
  distinct in identity under that arm's interning order. A structural dedupe in
  `getIntersectionType` would touch every intersection in the compiler (CLAUDE.md: identity
  and display there are load-bearing), and a display-level dedupe on the capture path would
  MASK an identity difference rather than fix it. Recorded, not attempted.

**SO (INC.2b)'s TRADEOFF HAS INVERTED AND THE ENTRY IS UPDATED WITH THE MEASUREMENT, NOT A
DECISION.** Zero spans where a narrowed hover renders more `any`; the remaining asymmetry
now runs the other way in 4 of 5 rows. The decision stays the owner's.

**(INC.3) THE FLOOR IS DECOMPOSED, AND IT INVERTED ITS OWN LEVER ORDER — 2026-08-22. Of a
1,219 ms floor: tail walkers 806.7 ms (66.2%), `init:*` setup 112.2 (9.2%), BIND 240.6
(19.7%), crawl 27.4 (2.2%), `checkSpine` 0.1 ms, residue 3.1. The queue had ranked bind
first, the passes second and the crawl third; measured, it is passes 75%, bind 20%,
crawl 2%.**

**FOUR INHERITED FIGURES REFUTED, EACH FOR A DIFFERENT REASON WORTH KEEPING.**
(i) **Bind is 241 ms, not ~515** — round 880's number is the bind component of a per-WORKER
fixed term under `--workers 4`, where four whole-program binds run concurrently beside the
JIT threads; the same fit measured +37% contention and round 883's `--shareBind` −5% is
that contention being collected. What DID transfer from round 880, almost exactly, is its
other half: "~930 ms of program-wide checker work" against 943.1 measured.
(ii) **The crawl is 27 ms, not ~138** — the parse half is gone and the instrument says so
directly rather than by inference (`78 reused / 0 fresh`, PARSE row zero calls).
(iii) `init:buildFileLocalTypeMaps` is **1.4%** of a warm compile, not 3.56% — rounds
829/859/861 landed levers in it and the queue's figure predated them.
(iv) The "two whole-program regex passes that never warm, 98 ms, matching zero times" are
**already gone**: 0.44 ms between them, gated by rounds 859/862.
A queue entry's numbers decay, and all four decayed in the direction that would have sent a
round at the wrong target.

**THE 20.3% TAIL FIGURE IS CONFIRMED AND MISREAD, WHICH IS THE ACTUAL FINDING.** The same
400 rows are 951 ms on a full build (19.0%) — but **85% of that survives narrowing**. Only
**24 of the 400** narrow (the ones iterating `checkedResults`); the other **376** iterate
`binderResults` and drive their own whole-tree walk, so they cost the same whether the
checker checks 78 files or none. `checkSpine` reading **0.1 ms** on the floor is the receipt
that `recheckOnly` removes per-file checking completely — which is exactly why nothing but
program-wide work is left to explain.

**AND THE TAIL IS FLAT, WHICH KILLS THE OBVIOUS FIX**: the largest single pass on the floor
is 26.3 ms, the top 10 are 25%, and 100 passes at a mean of **7.9 ms** carry 98%. A
consistency check (not a measurement) puts that at ~9.3 ns per node visit, i.e. what a
`forEachChild`-driven `when` dispatch costs. So (INC.3)(b)'s stated form — "make one
cheaper" — has no one to make cheaper. The lever has to be structural, and (INC.7) is the
cheapest structural form available: a loop RECEIVER per walker, `binderResults` ->
`checkedResults`, which is a strict no-op on every full build and only changes a partition.

**METHOD NOTE, because the ms are soft and the shares are not.** The floor drifted **−9.6%**
across one process with nothing changed (two plain batches bracketing the instrumented
ones), and the first instrumented build read 1,373 ms with its bind and text-scan rows 2-4x
every other draw. Quote the SHARES, which are computed inside one build; treat the ms as
±10%. The phase partition closes at 99.7% and the pass table over `init` at 99.8%, so
nothing above ~25 ms is unattributed.


**(INC.5) A CAPTURED TYPE'S DISPLAY NO LONGER DEPENDS ON WALK ORDER — LANDED 2026-08-22,
and it is a defect (INC.2)'s REFUSAL found rather than one anybody reported. 45 divergent
spans -> 9; the 40 wrong-direction rows -> 4. Suite 15,626 / 0, no corpus baseline moved,
cost gate a strict no-op.**

**THE CAUSE, and the rows are what named it.** `typeToString`'s anonymous-object branch
renders a property as `symbolTypes[p.id]` — a RAW CACHE READ — and prints `any` when the
entry is absent. The two utility materializers never populate it:
`materializeMemberSetUtility` (`Pick`/`Omit`) hands back the SOURCE interface's own member
symbols and `materializeModifierUtility` (`Readonly`) hands back fresh copies carrying the
source declarations, so the member's type is resolvable from its declaration and has
simply never been asked for. Which file asks first decided the display. **Two probes were
spent ruling out the mapped-type path first** (`getIndexedAccessType` never called,
`TRACE size=0` in `getTypeFromMappedType`) — the dedicated materializers are reached
BEFORE `substitutionResultCache`, which is why the obvious suspect was innocent.

**WHY IT IS FORCED AT THE CAPTURE SITES AND NOT IN `typeToString`** — the constraint that
made it a one-round change. `typeToString` is the DIAGNOSTIC renderer: forcing there would
put ~13k corpus baselines in play, and pay for the walk on every compile, for a defect only
a language service can see. Five capture render sites now call `typeCaptureRenderType`;
an ordinary compile executes none of it. The walk follows `typeToString`'s OWN shape rather
than the type graph (an interface and a reference render as a NAME, so neither is walked),
carries a `Type.id` seen-set plus the existing depth horizon, and asks plain
`getTypeOfSymbol` — which writes `symbolTypes` only under round 778's empty-context gate,
so it cannot freeze a context-dependent resolution an ordinary check would have refused.

**THE PIN WAS SHOWN TO FAIL ON THE UN-FIXED BINARY** (4 of 4, with `any` in the rendered
string, reproducing the arm asymmetry), which is the only thing that separates a fix from
an inert one. Its fixture carries a FOURTH file whose only job is to keep the whole-program
control from collapsing as well — without it both arms agree on the wrong answer and the
comparison passes vacuously, which is the same trap that made (INC.2)'s first
false-negative fixture worthless.

**THE COST GATE WAS NOT `+0.00%` AND THE CONTROL IS WHAT SETTLED IT.** Small drift showed
on `typeOfExpr.calls` / `mapped.hits`; running the gate on the UN-FIXED binary printed
byte-identical counters, so the drift is **pre-existing staleness in
`docs/perf/cost-counters.txt` at HEAD**, not this change. Worth knowing before the next
round reads that gate and blames itself: it passes, but its baseline has drifted.

**WHAT IS LEFT IS NAMED AND SMALL.** The 4 surviving wrong-direction spans are all
`Readonly<BuilderState>` in one file: `materializeModifierUtility` mints FRESH copies per
materialization, so warming one dies with the instance, where `Pick`/`Omit` cleared because
their symbols are the source's and their ids are stable. Populating `symbolTypes[copy.id]`
at MINT time is the fix and it is **not capture-scoped** — it would move diagnostic
messages — so it is (INC.6) with the corpus as its gate. The 5 REVERSED rows are a
different family and may not be defects at all.


**(INC.2) REFUSED AND (INC.4) LANDED — 2026-08-22, same session as (INC.1). The refusal is
the product: narrowing the CAPTURE queries would have been 3.73x and it renders a WRONG
TYPE in 45 of 381,666 spans, so hover/completion/definition/signature help stay
whole-program builds.**

**WHAT WAS MEASURED.** `scripts/capture-equivalence.sh` (new, committed) asks every
identifier of every file for its captured type and definition, twice — once whole-program,
once with `recheckOnly = {thatFile}` — and compares span for span: **381,666 spans, 76
files, 45 divergent spans in 11 files, types 45, definitions 0**, of which 40 render MORE
`any` under the partition. Timings, warm and rotated on `binder.ts` (7,787 spans): full
4,719 ms, narrowed 1,264 ms.

**THE MECHANISM IS FIRST-TOUCH CACHE ORDER, AND THE CENSUS PROVES IT RATHER THAN ASSUMING
IT.** The collapsing shape is a type reference INSIDE a foreign file's ANONYMOUS OBJECT
TYPE LITERAL — `{ program?: any }` for `{ program?: Program }` — with the outer signature
intact. **In 5 of the 45 the FULL build is the one rendering `any`** where the narrowed
build renders `T`, which rules out "narrow is simply worse": `symbolTypes` persists the
first resolution (round 778) and the two arms differ in which file asks first. So the
sweep is a DETECTOR for a pre-existing order-dependence in what a hover reports, now
queued as **(INC.5)** with the full-vs-narrow pair as its differential oracle — no
baseline needed, because the two arms must agree.

**AND IT DOES NOT REACH DIAGNOSTICS, WHICH IS THE QUESTION (INC.1) RESTS ON, SO IT WAS
MEASURED AND NOT ARGUED.** The check path is keyed by the node's OWNING file
(`lookupPerFileForNode`, rounds 508/509); the capture path is not. A fixture whose error
exists only while the literal's member keeps its declared type
(`const n: number = make().program`, the literal in a second file, `Program` in a third)
is reported identically by the partition, by the public API and by the whole-project sweep.
**THE FIRST FIXTURE FOR IT WAS VACUOUS AND ITS OWN CONTROL CAUGHT THAT**: an
argument-position error (`use({ program: 1 })`) is not reported by this compiler at all, so
both arms agreed on an empty list and the pin passed having measured nothing. Any
equivalence pin over two arms needs a control asserting the reference arm is NON-EMPTY.

**(INC.4) LANDED**: `ProjectCompiler.build` now REFUSES `recheckOnly` together with emit.
The Transformer queries the checker it is handed, so a partition would decide import
elision from a checker that never walked the files whose uses keep an import alive —
wrong JavaScript, silently, with every diagnostic still agreeing. Nothing in the repo does
it today; the parameter is public and the next caller would have had no way to know.
4 pins including both negative controls.

**A SECOND CAVEAT FOR HOSTS, MEASURED WHILE PROBING: NARROWING ONLY PAYS ON A LARGE
PROGRAM.** On a three-file project the narrow query is **0.87x** — slower than the whole
build — because the floor is 90% of it and there was nothing worth not doing. Recorded in
`docs/language-service.md` § 4a beside the 4.35x, because a host that reads only the ratio
would wire it the wrong way round for small projects.


**(INC.1) THE LANGUAGE SERVICE'S ERROR REPORTING IS NOW A PARTITION — LANDED 2026-08-22,
owner directive ("make the LanguageService truly incremental, as if it were providing
perfect support for an IntelliJ TypeScript plugin"). `Project.diagnosticsOf(fileNames)`
answers the diagnostics of a file set by handing that set to the compiler as its CHECK
PARTITION: 4,818 ms -> 1,107 ms warm on tsc's own 78 sources, with all 78 files agreeing
row for row with the full build.**

**THE SEAM ALREADY EXISTED AND THIS MODULE WAS PASSING NULL TO IT.**
`ProjectCompiler.build` has taken `recheckOnly` all along — it threads to
`Checker(assignedFileNames)`, the INV.6 partition view `--workers` uses — and
`Project.build()`'s KDoc argued it stays null because narrowing "is only sound with a
dependency closure this class does not maintain", while `docs/language-service.md` § 14
listed *no incrementality* as gap 1 and said closing it was "the architectural inversion,
not an API item". **Both are true of the question `--watch` asks and false of the question
an editor asks.** `--watch` must keep EVERY file's diagnostics valid, so it needs the
reverse-dependency closure — which CLAUDE.md already prices at nothing on a
barrel-exporting codebase (touching the leaf `semver.ts` still rechecks 77 of 78). An
editor's annotator asks *what are the errors in THIS buffer*, claims nothing about the
other files, and therefore needs no closure at all. The inversion is still the end state;
it was not the prerequisite for this.

**THE NUMBER THAT REDIRECTS THE ARC: A MEDIAN FILE'S OWN CHECKING IS 15 ms.** The floor —
a partition naming a file the program does not contain, so no file is checked and what
remains is the crawl, the parse, the bind and the program-wide passes — is **1,092 ms,
99% of a narrowed query**. It is free to measure and needs no probe, which is why it is
now an arm of the sweep. Consequences, both worth more than the fix:

- **Narrowing the CHECK is DONE.** (INC.1) collects essentially all of it (4.35x at the
  median file, 1.76x at `checker.ts`, which is 31.6% of that program by itself). Another
  round spent partitioning the checker is chasing 15 ms. Do not spend it.
- **Everything left is the floor**, and its ceiling is the whole remaining query. Bind is
  a known ~515 ms of it; the ~14 program-wide setup passes and the ~416 tail passes
  (20.3% of a warm compile, `buildFileLocalTypeMaps` alone 3.56%) are most of the rest,
  and every one of them iterates `binderResults` by an explicit correctness rule
  (CLAUDE.md: a gated collector cost 1,174 false positives in round 609). That is (INC.3).

**THE GATE IS NOT THE SUITE AND CANNOT BE.** A corpus fixture is one or two files, where
a partition of one is nearly the whole program, so the round-609 failure class is
invisible to it. `scripts/partition-equivalence.sh` runs a partition of one for EVERY
file of a real project and compares that file's rows against the full build's: all 78
agree, 5 of them carrying the program's 46 diagnostics, and the runner REFUSES a verdict
when no file carries any — a green sweep over an all-clean program tests nothing.

**WHAT NOTHING PINS, MEASURED RATHER THAN ASSUMED.** No test in the suite fails if
`diagnosticsOf` stops passing `recheckOnly` and builds the whole program: the answer is
filtered afterwards either way, and the two differ only in wall time, which a timed
assertion over a compile cannot hold. **Ablated it and confirmed — 14/14 green with the
wiring removed** (fresh XML checked by mtime; a stale one reads exactly like a green
ablation). So the narrowing is pinned at the SEAM — the compiler's own partition build
reports the assigned file's rows and not another file's, which fails uniquely — and that
it is REACHED is held by the two harnesses. Recorded rather than papered over (round 807).

**FOUR TRAPS PAID FOR IN THIS ROUND.**
1. **A timing arm's POSITION IN THE PROCESS outweighed the effect it measured.** The
   first floor reading was 1,632 ms — larger than the 1,107 ms median partition it is a
   strict subset of — because it sat at slot 3 while the partitions ran at slots 4-81,
   and the same process read the full build at 9,421 ms against a warm 4,818. Timing arms
   now run last and rotated; four full draws then span 0.7%. An impossible ORDERING
   (a subset costing more than its superset) is the cheapest tell that a ramp is being
   measured — cheaper than any spread statistic.
2. **A real keystroke costs the same as a byte-identical dirty** (4.7-4.9 s), because
   `LanguageServiceCostMain` dirties a file by writing its own bytes back and the
   content-keyed parse cache hits either way. So parse reuse was already complete and
   "cache the parses harder" is retired without building it.
3. **`grep` without `-a` returns NOTHING on `Project.kt` too**, not only `Checker.kt` —
   an anchor-count guard read zero for a string plainly present, which would have aborted
   an ablation as "anchor not found".
4. **The probe's own edit site assumed LF** and the bench profile's tsc sources are CRLF,
   so the first run died before measuring anything.


**(KAPI.1) A TYPESCRIPT LIBRARY'S PUBLIC API, AS A KOTLIN METADATA KLIB — LANDED 2026-08-22,
owner directive.** A checked TypeScript library now exports as the artifact a Kotlin
Multiplatform `commonMain` compiles against: `exportTypeScriptProjectApi(project, entry,
out.klib)` writes a metadata klib holding the library's exported declarations as Kotlin
declarations. 22 pins, `docs/kir-kotlin-metadata.md`.

**The route is generated Kotlin SOURCE through kotlinc's own metadata compiler, and that is
the decision the rest follows from.** Kotlin metadata is a versioned protobuf whose only
writer lives in the compiler, so writing it directly would be a second implementation of a
format that moves every release; going through `KotlinMetadataCompiler` — the THIRD kotlinc
entry point this module drives, beside the JVM pipeline and the native
`IrGenerationExtension` — makes the artifact by construction what kotlinc would have
written, and leaves `KotlinMetadataExport.source` as a readable intermediate. No classpath
is needed, because the exported surface names only Kotlin BUILT-INS: the artifact is
self-contained, and the standard library's common metadata (a separate `-all` artifact this
project does not ship) never enters into it.

**The surface is the ENTRY MODULE's exports, followed through re-exports — not the union of
everything every file marks `export`.** A package's `index.ts` is its statement of what it
offers, and the union publishes names a library deliberately keeps internal; the pin asserts
both directions, including that a module the entry does not re-export is unreachable from
the artifact.

**REFUSALS ARE PER DECLARATION here, where the IR lowering refuses the whole program, and the
asymmetry is the point: an absent declaration is a compile error at the consumer's use site,
a wrongly-typed one is silent.** A rest parameter, an anonymous export, an unresolvable
specifier, an enum whose member values disagree — each is omitted and reported with file,
line and column, rather than guessed.

**VERIFICATION IS BY A CONSUMER AND ONLY BY A CONSUMER.** A metadata klib is a binary nobody
reads by eye and every failure mode it has is silent, so each end-to-end pin compiles Kotlin
against the artifact through the same metadata compiler — with four negative controls,
because a round trip that passes because the consumer compiles whatever it is given would
pass for an EMPTY klib: a non-exported name must not resolve, the erased parameter types must
be ENFORCED (`greet(1)` against a `Double` parameter must fail), a non-re-exported module must
not be reachable, and a program the checker rejects must produce no artifact at all.

**Four traps, each silent, recorded so they are not re-found.** (i) `metadataKlib = true` is
load-bearing: left false the compiler writes the LEGACY layout under the same `.klib` name,
with no diagnostic, and a multiplatform consumer resolves nothing — hence a pin on the layout
itself. (ii) `K2MetadataCompiler` is deprecated in 2.4 and the deprecation is an ERROR in this
build; `KotlinMetadataCompiler` is the live class. (iii) a klib is accepted on a consumer's
classpath both as a DIRECTORY (what the compiler writes) and as a ZIP (what a build publishes),
measured both ways. (iv) a path glob written into a KDoc opens a nested block comment —
CLAUDE.md's own entry, met in the first file that documented the legacy layout.

**What it is worth today, measured on the two real libraries this module already compiles:**
`mitt` exports `mitt(all: Any?): Any?` and `smol-toml` exports `parse(toml: String, options:
Any?): Any?`. Primitives, unions, optionality, classes, enums and callbacks reach the surface
typed; ARRAYS and OBJECT TYPES do not, because they are `JsArray`/`JsObject` at run time and
those are JVM classes with no common metadata artifact. That is a stage, not a verdict — it is
§6's item 2 and the pins are written where it will show.

**(KAPI.3) LANDED IN THE SAME SESSION, and it is what makes an exported library usable.** With
`runtimeKlib =` the export writes a second metadata klib declaring `JsObject`/`JsArray` under
their real fully qualified names and compiles the library against it, so `smol-toml` exports
**`parse(toml: String, options: JsObject?): JsObject`** and a Kotlin consumer reads
`document.get("title")` — measured on the library's own 1,082 lines, pinned end to end. Three
decisions carry it: a bag needs POSITIVE evidence (the lowering's own gate, because a `Date` is
a `JsDate` and a bag-typed one offers members the value lacks); an intersection is a bag only
when EVERY member is one, stricter than `ErasedTypes` and forced by having no library-type
table; and the hand-stated facade is kept honest by a REFLECTION pin over the real classes
rather than by a promise, with two negative controls proving the pin can fail.

**(KAPI.4) LANDED TOO, and it is what makes the erasure PRECISE rather than merely non-`Any?`.**
The facade declares `JsMap`, `JsSet`, `JsDate`, `JsRegExp` and `JsError` beside the first two, and
`KirRuntimeApi.libraryType` mirrors `KirIntrinsics.libraryClass` entry for entry — so `mitt`'s
parameter is **`JsMap?`** (its `EventHandlerMap` is an alias of a `Map`), which is what the compiled
program holds there and is strictly better than a bag. **The round's real finding is a defect in the
gate it was extending: an absent declaration is not evidence of an anonymous shape.** A
`Promise<string>` arrives with no declaration to walk — a `Type.Reference`'s own symbol carries none,
its TARGET's does — and read as a bag it would have offered a Kotlin consumer `get`/`set` on a
promise. Both halves are now pins, and the same shape is queued as a LEAD against `ErasedTypes`
itself ((KIR.LOWER.2)), where the consequence is wrong CODE rather than a wrong declaration.

**NAMED SUCCESSORS (both queued): (KAPI.2)** the platform half — nothing yet pins that the JVM
classes the KIR backend emits match the signatures this metadata declares, and until something
does, the artifact types a consumer's common code without linking its platform code; **(KAPI.3)**
a runtime metadata klib, which is what turns those `Any?` positions into `JsObject`/`JsArray`
with members.


**THE KIR QUEUE — ALL FIVE ITEMS CLOSED (2026-08-21).** Five open (KIR.\*) items
at the start, five checked off.

**(KIR.EMIT.2), the smallest and the one that says where a decision belongs.**
`a + '|' + b` with `b` undefined printed `x|null`. §3.1 puts `undefined` and `null` on one
JVM value, so `string | undefined` and `string | null` are both `String?` and the RUNTIME
cannot tell them apart — but the LOWERING still holds the TypeScript type. `asString`, the
single funnel for `+` and for a template span, now asks whether every nullish member the
operand admits is `undefined`, and a type admitting BOTH keeps `"null"`: the wrong answer
is narrowed to the shapes the collapse cannot separate, not swapped for the opposite wrong
answer. 5 pins.

**(KIR.EMIT.1) — `rewriteRelativeImportExtensions`, at four specifier positions.** The
post-pass position is the load-bearing decision: the specifier TEXT is also how the
transformer ASKS the checker about the target module (`isValueExport`, const-enum inlining,
import elision), so rewriting any earlier asks about a `.js` file the program does not
contain. mitt's EXTENSIONLESS `./mitt` stays a benchmark expedient because tsgo leaves it
alone too — rewriting it would be a divergence, not a fix. 10 pins, each condition of the
population with its own negative control, and the option-OFF control is what shows the
positives discriminate.

**(KIR.PERF.2) — the regex engine, and it beat its own prediction.** −27.5% against the
−18% predicted (47.05 → 34.10 us/parse, 2.08x Node → **1.52x**), because two smaller
members came along: `replace(/_/g,'')` on a literal path and `split` no longer building a
fresh `Regex(source)` per call (which also silently ignored the expression's flags). The
design is three decisions, each of which is what makes it an optimisation rather than a
second semantics: it answers `test` and NOTHING else (the one question on which a DFA's
leftmost-longest and JavaScript's leftmost-first agree by construction); everything outside
the subset is REFUSED at compile time and cached as a refusal; and `java.util.regex` stays
LIVE as the differential oracle. **It found a defect in the oracle rather than in itself** —
Java's `$` matches before a final line terminator where JavaScript's matches only at the
end, so `/^\d+$/.test("12\n")` answered `true` here; three of the matcher's first
differential runs failed on exactly that shape and were right to. 20 pins.

**(KIR.NATIVE.1)(b)+(c) — the native arm, in the gate.** `KIR_BENCH_NATIVE=1` builds both
libraries through the same `kirNativeCompile`, gates their `sink=` with the other three arms
and times them in the same interleave; the run prints the arms it ACTUALLY ran rather than a
fixed count. The regex engine is carried to native verbatim and is worth **−22.5%** there
(163.30 → 126.55 us/parse, 7.26x → **5.70x**) with mitt flat as the control. **The
prediction was directionally right and quantitatively over** — it said native should gain
MORE than the JVM's −27.5% and it gained less — which is what writing a prediction down
before the run is for. One trap that exits 0: konanc appends `.kexe` to whatever `-o` names.

**(KIR.PERF.1) — the container half REFUTED four times, and the nominal half BUILT.**
Censused by OPERATION the bag is 3,333 ops/parse (2,555 `get`, 737 `set` of which 63.5%
OVERWRITE) at ~4.9 ns each — a row that SURVIVES round 896's division, where its neighbour
`jsTruthyBooleanOrNull` implied 8.2 ns for `value != null && value` and was refused without
a build. The new fact is that **the READ side is unimodal**: 93.6% of reads land on a
three-key bag with interned names, where §2's census (of ALLOCATION) had said bimodal. So
the most favourable possible scan was built — no promotion, single-shaped `get`, everything
else cold — and it measured NO EFFECT, as did a `LinkedHashMap` sized to the census.
**Both looked like small regressions until the baseline was replicated and moved 692 → 735
ms on the same bytes**; that correction is the round's methodological result and is now in
`scripts/kir-screen.sh`'s own header. Four designs, no win — which also refuses the guarded
slot hint the entry used to propose, since its claim was that an indexed compare beats a
scan that turns out to be LEVEL with a hash probe.

**So the NOMINAL half was built instead, and its first slice landed: `mitt` −10.7%**
(61.00 → 54.50 ns/emit, ranges disjoint, both Node arms flat, 1.35x → **1.54x FASTER** than
Node). An object literal whose names are statically known becomes a generated JVM class
with one real field per property, EXTENDING `JsObject` — which is the decision that made it
affordable: §7's 12x price is for changing what an object type ERASES to, and here the
erasure is untouched, so structural assignability never enters into it and the dynamic half
stays total (an undeclared property goes to the bag; the first `delete` or `Object.keys`
spills the fields into it in declaration order). `smol-toml` is FLAT and that is the honest
half — its ten shapes fire, but `get` had to become virtual and the parser builds its tables
dynamically, so the gain on the context and the loss on the tables cancel. Two traps that
compile and fail at RUN time: a shape class is per FILE in one shared package (two files
both mint `JsShape0` → `NoSuchMethodError`), and `coerce` decides on classifiers alone, so
a shape widening to the bag it extends has to be told.

**GATES.** Suite **15,563 / 0 failures** (KIR module 83 → 108). Both KIR benchmark runs
passed their equivalence gate before any timing, on all three and then all four arms.

**(KIR.PERF) THE BACKEND, MEASURED FOUR TIMES AND MOVED −17% — AND THE ONE
DIRECTION THAT LOOKS OBVIOUS IS NOW REFUTED TWICE (2026-08-21).**

**THE RESULT.** `smol-toml` on the JVM goes **56.60 → 47.05 us/parse (−16.9%)**,
i.e. 2.49x slower than the same library on Node down to **2.08x**. The last four
runs cover only changes that measured inside the band and read 46.95 / 48.00 /
47.75 / 47.05, so that is a replicated number and not a draw. **`mitt` moves only at the end** — 62.25 -> 61.00,
still 1.41x faster than Node — which is the expected shape: an event emitter
barely compares characters, barely reads properties and never matches a regular
expression, so none of this session's levers has anything to do there. Every figure is a within-round paired delta on
`scripts/kir-bench.sh` with 5 interleaved processes per arm, and **both Node
arms held flat across every pair** (tsgo 452/455/453/451 ms, ours 448/445/447/453),
which is what licenses reading these as backend numbers rather than as box
weather. `docs/perf/kir-backend-levers.md` carries the table.

**LEVER 1, THE WIN: an operand the lowering already typed no longer takes the
boxed path (−13.6% on toml, ranges DISJOINT; nothing on mitt).** `===`, `!==`, `==`, `!=`, a `switch`
clause, a condition and a string conversion all went through an `Any?` entry
point, so `s.charCodeAt(p) === 0x20` — what a hand-written scanner's inner loop
is made of — boxed BOTH operands and then walked an `instanceof` chain to
rediscover what the lowering had proven. `+` has decided by the erased operand
types since the beginning (`addValues`); this is that rule reaching the rest of
the family and nothing else. The semantics are pinned rather than argued
(`KirEqualitySemanticsTest`, `KirPrimitiveOperandTest`): `NaN !== NaN` and
`0 === -0`, `-0`/`NaN` falsy but the STRING `'0'` truthy, `1 == true` and
`null == undefined` true so no MIXED case may specialize, and the left operand
evaluated first in both half-specialized directions.

**LEVER 2, REFUTED, REVERTED, AND IT IS THE MOST USEFUL THING HERE.** A
per-owner leaf census (`scripts/kir-profile.sh`, new) charges **44.3%** of the
toml arm to the property bag — `JsObject.set` 25.6%, `get` 17.3% — so it is
plainly the largest cost. Giving `JsObject` the shape its LITERAL declared,
promoted at the first UNDECLARED key (chosen precisely to leave alone the
dictionary half that killed the 2026-08-21 size-threshold attempt), measured
**+31%, ranges disjoint**. The rule worked — `HashMap` fell from 38.3% of
samples to **4.7%** — and the saving did not exist: counted in SAMPLES rather
than shares, the bag cost **709 before and 771 after**, and the rest of the
regression landed on `program.*` and regex frames that did not change.

So: **two independent attempts at making the dynamic representation cheaper
have now cost 21% and 31%. The bag is expensive in the NUMBER of operations,
not in their unit cost**, and only the nominal half removes them. (KIR.PERF.1)'s
case is now made by measurement from both directions.

**LEVER 3: three rows the census named, two of them pure overhead (−4.0%).**
`jsTruthy` decided its answer with an equality `when`, which Kotlin compiles to
a chain of `Intrinsics.areEqual` — **5.0% of samples spent asking whether a
value equals `false`**. `JsRegExp` allocated a `Matcher` per `test` and per
`exec` (`Matcher.reset` was the largest regex leaf at 10.2%); the two now share
one, which is safe because neither lets other code run between starting a match
and reading its groups. And a regex LITERAL inside a function is a fresh object
per call, so `value.replace(/_/g, '')` was re-parsing its source every time —
every distinct `(source, flags)` now compiles once. `KirRegExpTest` uses ONE
expression many ways at once, because both changes fail the same way.

**LEVER 4: `+` asks the checker what the other arithmetic operators already
ask — MEASURED NEUTRAL and kept, explicitly not counted as a win.** A bag read
erases to `Any?` however precisely the checker typed it, so `ctx.p + 1` reached
`jsAdd` with both sides boxed; asking whether the whole SUM is a `number`
decides both coercions at once and is exact. 46.95 → 48.00 us/parse with the
ranges OVERLAPPING. Kept because it is cost-monotone, pinned, and closes the one
place where `+` disagreed with `-` about whom to ask.

**LEVER 5: Kotlin's null assertions leave the GENERATED program — a fidelity fix
that also measures favourably.** Every generated function opened with an
`Intrinsics.checkNotNullParameter` per non-null reference parameter, which is an
invariant JavaScript does not have: a JS function handed `undefined` for a
declared parameter does not throw at ENTRY. The runtime's own assertions and the
lowering's `as Double` casts are untouched. 47.75 → 47.05 us/parse and mitt
62.25 → 61.00 ns/emit, both ranges overlapping.

**GATES.** KIR module 58 → **83 tests, 0 failures** (+25 pins: 9 equality, 8
primitive-operand, 5 regex, and `KirPropertyBagTest` rebuilt from 7 to 10 and
made to cross BOTH construction routes — it had been building every fixture
with `set`, so an entire representation was untested and read as covered).
`KirPropertyBagTest`'s cases are representation-independent by construction:
both refuted bag attempts passed all of them unchanged, which is exactly what
makes it the grading harness for the next one.

**THE SUCCESSOR, NAMED WITH ITS PRICE AND ITS INSTRUMENT** — see (KIR.PERF.1)
below, which now carries the census, the ceiling (the bag is 44.3%, so a
free property access is worth ~−44% at the limit) and the one design the two
refutations do not rule out.


**(BENCH.1) ANSWERED, AND ONE OF THE TWO PERFORMANCE LEVERS IT LICENSED IS A
MEASURED REFUTATION (2026-08-21, same day).**

**THE THIRD ARM SAYS THE FRONT END IS NOT THE PROBLEM.** `-core`'s own emitted
JavaScript, on the same Node, against tsgo's: **mitt 83.75 vs 84.50 ns/emit
(1.01x), toml 22.35 vs 22.75 us/parse (1.02x)** — i.e. INDISTINGUISHABLE, which
is the prediction the queue entry recorded before the run. So the 2.5x on
`smol-toml` belongs to the KIR BACKEND in its entirety, confirming the leaf
profile by a second instrument rather than by inference, and the arm is now the
standing control: any future backend claim can be read against a JavaScript
number produced by our own front end.

**LEVER 1 LANDED — `jsCall` no longer allocates an array to make one call.**
`jsCall0`..`jsCall3` pass arguments positionally and test the arity they were
called with FIRST. Measured, medians of 5 interleaved processes with both Node
arms flat: **mitt 65.75 -> 61.50 ns/emit (-6.5%, ranges DISJOINT [261..287] ->
[242..250])**, toml 57.25 -> 55.75 us/parse (-2.6%, ranges overlap). mitt is now
**1.35x FASTER** than the same library on Node. The specialization deliberately
keeps ADAPTING the callee's arity — mitt registers a one-parameter wildcard
handler that `emit` calls with two arguments, so an implementation that trusted
the declared arity would compile and fail on the library this backend exists to
run.

**AND THE PIN EXPOSED A DEFECT THAT WAS ALREADY THERE: the chain stopped at
`Function3`, so a FOUR-parameter method of an object literal was a runtime
`JsTypeError: … is not a function`.** Arities 4 and 5 now work. Nothing in the
corpus reached it because no corpus program has a four-parameter bag member.

**LEVER 3 IS REFUTED, BY MEASUREMENT, AND IS REVERTED.** Holding a small bag in
parallel arrays with a linear identity-first scan — aimed at the 28.3% the
profile charges to `HashMap`/`LinkedHashMap` — made `smol-toml` **21% SLOWER**
(55.75 -> **67.35 us/parse**), while mitt moved only 61.50 -> 59.50. **The
mechanism is that the bag population is BIMODAL and the profile's single number
hid it**: `ParseContext` is a four-field scanner state, which the scan suits,
but the parsed document's tables are the OTHER half — the root table alone has
18 keys — and every bag that outgrows the inline capacity pays the arrays AND
the promotion AND the map. The half that dominates the samples is the half the
change taxes. Two corollaries worth carrying: a hash-family share is not
evidence about ANY particular container until the container's key-count
DISTRIBUTION is censused (round 902's law, one runtime over), and an
identity-first compare is a pure loss where keys come from DATA rather than from
emitted literals — a TOML key is never the interned string the scan hopes for.

**WHAT SURVIVES THE REFUTATION: `KirPropertyBagTest`.** Its seven pins are
REPRESENTATION-INDEPENDENT — insertion order across the promotion boundary,
`delete` closing the gap, a deleted-then-reinserted key moving to the end, an
EQUAL-but-not-identical key resolving, `has` distinguishing absent from
`undefined` — so they were written against the array form, pass unchanged
against the map form, and are what the next attempt at this will be graded by.
That is the cheap half of a refuted round and it is worth keeping.

**STILL OPEN, AND NOW THE ONLY NAMED LEVER FOR THE 2.5x:** the NOMINAL half of
`docs/kir-design.md` §3.3's hybrid — `ErasedTypes.mapObject` sends a declared
`class` to a generated JVM class and sends an `interface`, a `type X = {…}` and
every object literal to the bag. `docs/kir-structural-typing.md` §7 prices the
nominal half at 12x. Lever 3's refutation is evidence FOR that direction rather
than against it: what failed was making the dynamic representation cheaper, not
removing the dynamic representation.

**KIR RUNTIME BENCHMARK (2026-08-21) — THE COMPILED LIBRARIES, TIMED AGAINST THE
JavaScript THEY WERE WRITTEN FOR. THE ANSWER DISAGREES BY LIBRARY AND BY *SIGN*,
AND THE LEAF PROFILE SAYS WHY.**

**THE MEASUREMENT.** Same TypeScript source through two toolchains — tsgo 7.0.2 ->
JavaScript -> Node 22.20.0, against xtsc `-kir` -> Kotlin IR -> JVM bytecode -> java
(Zulu 26.0.2) — drivers ours and identical for both arms, 5 interleaved processes per
arm, best-of-10 rounds inside each process, box otherwise idle.

| workload | Node (tsgo) | JVM (xtsc/KIR) | |
|---|---|---|---|
| mitt, 4M `emit`/round | 344 ms · **86.0 ns/emit** | 266 ms · **66.5 ns/emit** | **JVM 1.29x FASTER** |
| smol-toml, 20k parses/round | 452 ms · **22.6 us/parse** | 1128 ms · **56.4 us/parse** | **JVM 2.50x SLOWER** |

One-shot wall clock including startup (10 runs, the acceptance programs as shipped):
mitt **35 -> 92 ms**, toml **39 -> 115 ms**, i.e. the JVM pays ~2.6-2.9x and it is
startup, not work. Compile side: tsgo emits either project in ~0.35 s against the KIR
backend's 4.5 s (mitt) / 5.6 s (toml) in-process, which is mostly kotlinc pipeline
setup rather than lowering.

**THE CONTROL THAT MAKES IT A MEASUREMENT.** Both arms produced IDENTICAL `sink`
accumulators (128,000,000 and -5,440,000) and byte-identical acceptance output against
the `tomllib`-derived expectation — so the two compilations compute the same thing, and
a divergence would have read as a timing result rather than as the bug it is.

**WHY THE TWO LIBRARIES SPLIT — PROFILED, NOT INFERRED** (JFR, `settings=profile`,
leaf frames). **smol-toml on the JVM, 2,159 samples: ~60% is JS-semantics emulation
rather than the library's logic** — `HashMap` get/put/resize + `LinkedHashMap.newNode`
**28.3%**, `java.util.regex` **17.5%**, `Intrinsics.areEqual`/`String.equals`
**11.1%**, `Double.valueOf` **3.4%**; the lowered library code (`program.*`) is
**17.7%**. **mitt on the JVM, 567 samples: `JsRuntimeKt.jsCall` ALONE is ~60% of
leaves**, with `TypeIntrinsics.isFunctionOfArity` behind it — so even the arm that WINS
spends most of its time in the dynamic-call shim.

**THE FOUR LEVERS THE PROFILE NAMES, and their prices, read out of the source rather
than guessed.** (i) `jsCall(callee, vararg)` allocates an `Object[]` per call and walks
an `instanceof` chain; ARITY-SPECIALIZED entry points remove both, and the adaptivity
must SURVIVE — mitt registers a `Function1` wildcard handler that `emit` calls with two
arguments, which is exactly why `lowerFunctionValueCall`'s direct `invoke` is not used
for a bag member (`KirFileLowering.kt:2714`'s comment is the record). (ii) `ErasedTypes.
mapObject` sends a declared `class` to a generated JVM class and sends an `interface`,
a `type X = {…}` and every object literal to the **property bag** — so smol-toml's
`export type ParseContext = {…}` scanner state is a `LinkedHashMap` probe per `ctx.p`,
which IS the 28.3%. The design page's own §7 prices the nominal half at **12x** the
dynamic half. (iii) The bag's keys are literals at the lowering, so INTERNING them and
comparing by identity attacks the 11.1%; a small-object linear-scan representation
attacks the hashing, and neither touches the lowering. (iv) `Double.valueOf` is
BOUNDARY boxing, not the erasure — `ErasedTypes` already maps `number` to a primitive
`double`, and the boxes are minted at bag get/set, `JsArray` elements and
`FunctionN<Any?,…>` edges.

**WHAT IS NOT A LEVER: the regex family.** `JsRegExp` compiles its `Pattern` once per
instance and the profile shows no `Pattern.compile`, so the 17.5% is genuine matching
cost — `java.util.regex` is a backtracking interpreter where V8's Irregexp emits native
code. It caps how close this backend can get on a regex-heavy library, and swapping
engines would change the semantics rather than the speed.

**KOTLIN/NATIVE WAS ASKED FOR AND IS NOT RUNNABLE — a structural answer, not a missing
tool.** `KotlinIrEmitter` drives kotlinc's **JVM** phases and `JsRuntime.kt` is
`jvmMain` with 26 `java.*` references (`java.time` for `JsDate`, `System.
currentTimeMillis`, `java.util.regex`); a native leg needs a K/N pipeline driver AND a
multiplatform runtime, which is the module's own "JVM today, JS/Native/Wasm for free
later" roadmap. The K/N toolchain itself IS on this box
(`~/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin/konanc`), so what is missing
is ours. Compiling hand-written Kotlin and reporting it as a native number would have
measured Kotlin, not this compiler.

**THE HARNESS.** Node 22.20.0 under `tools/` (gitignored, downloaded — no JS runtime
was installed on this box); bench projects, drivers and the interleaved runner in the
session scratchpad; a `KirBench` java main over `compileTypeScriptProjectToJvm` that
leaves the classes on disk so the generated program runs as an ordinary `java` process
with the compiler out of the picture. **NOT COMMITTED YET** — (BENCH.1) is where it
lands if the third arm proceeds. Two protocol notes it earned: a `nohup … &` gradle run
ended with NO `BUILD SUCCESSFUL` line and had to be re-run in the foreground (the
round-851 shape, caught by grepping for the verdict rather than trusting exit status),
and the emitted-JS arms need their import specifiers checked rather than assumed —
tsgo rewrites `./parse.ts` -> `./parse.js` under `rewriteRelativeImportExtensions` but
leaves mitt's extensionless `./mitt` alone, which Node ESM refuses.

**KIR SPIKE (2026-08-21, branch `spike/ts-to-kotlin-ir`) — TWO REAL PUBLISHED
LIBRARIES COMPILE TO JVM BYTECODE AND RUN, AND SIX CHECKER DEFECTS FELL OUT OF
GETTING THERE.**

**THE RESULT.** `mitt` 3.0.1 (123 lines) compiles and runs twice — as a corpus
program, and as a real MODULE that a second file imports. `smol-toml` (1,082
lines over seven files, its own source unmodified) compiles and PARSES a 40-line
TOML document; the expectation is produced by **Python's `tomllib`**, so the test
checks the compiled library against a second, independent implementation rather
than against itself. The checker reports **zero errors** on both, which is what
tsgo 7.0.2 reports.

**THE METHOD, WHICH IS THE PART THAT TRANSFERS.** Point the compiler at code
nobody wrote for it, read the FIRST refusal — it names a file, a line, a column
and a construct — fix exactly that, repeat. Roughly forty iterations produced
the whole backend surface below, and every one of them was a real gap rather
than a guess. Two instruments made it cheap: `LibraryProbe`
(`KIR_PROBE_PROJECT` / `KIR_PROBE_FILE`, an env var because Gradle does not
forward `-D` to the test JVM) and `tsgo --noEmit -p <dir>` as the front-end
oracle.

**`docs/kir-library-readiness.md` PREDICTED THE OPPOSITE AND NOW SAYS SO.** It
concluded from `yaml` and `zod` that "the blocker is the FRONT END, not the
backend"; measured on a third library, mitt reached zero checker errors
untouched and every step between "type-checks" and "runs" was backend work. The
rule is per-library, and the page now names the two commands that answer it
before any planning.

**THE SIX CHECKER DEFECTS**, each a false positive or a silent false negative
that a corpus of ONE codebase's style could not contain, each landed with pins
and an ablation:

- an imported class's `instanceof` narrowed NOTHING — the alias has neither
  `SymbolFlags.Class` nor the value flags the constructor-value leg needs;
- an imported TYPE GUARD narrowed nothing — round 512's dir-relative resolver
  lesson, one resolver over (`computeImportedFunctionLikeDecl`);
- **a guard written `const isX = (n): n is X => …` narrowed nothing at all**,
  local or imported, because it resolves to a VariableDeclaration with no
  parameter list — the style `yaml` writes ALL of its guards in;
- `export default function f` resolved to `any`, so every misuse of a
  default-imported function went unreported;
- a returned LITERAL widened against a literal-containing union (the string
  fallback re-renders the source as its base primitive, and the arrow's concise
  body never had 17.70 at all);
- a MODULE-level `const` widened where a body-local one kept its literal; an
  object literal typed its members in a vacuum in a var-decl where the RETURN
  path has given it context since round 462; and assigning a computed primitive
  dropped a narrow that assigning a CALL kept.

Plus one in the type-of-a-binding family: a `for…of` binding was typed `any`
everywhere except inside `checkPropertyAccessInStatement`, which carries its own
B70.4 copy of the element-type rule.

`yaml` — which nobody worked on — went **80 -> 24 errors** (4 environmental) on
those alone, with TS2339-on-a-union going 21 -> 0.

**ONE CANDIDATE FALSE POSITIVE WAS FOUND AND DESIGNED OUT RATHER THAN SHIPPED.**
Installing the annotation as an object literal's contextual type UNCONDITIONALLY
— which is what the return path does — turned `program.ts:1075` red on the
compiler profile, where an object literal assigns a GENERIC function to a
non-generic member and the relation cannot yet instantiate one against the
other. The context is now installed only where the target's SHAPE asks for it (a
member that is a tuple or contains literals), `output.errors` stayed at 46, and
the relation gap is recorded rather than papered over.

**GATES: 15,492 tests / 0 failures across all modules; `cost_gate.py`
`output.errors` +0.00%, `typeOfExpr.calls` +0.18% (the `for…of` subject is now
typed at the loop's enter); `huge_methods.py --fail-over 0` green.**

**BACKEND SURFACE ADDED** (each with a corpus program that compiles to bytecode
and runs, or with the library acceptance): arrays (`T[]` -> one `JsArray`,
members found by the ERASED receiver), closures (every function type erases
UNIFORMLY to `FunctionN<Any?, …, Any?>`, because TypeScript's assignability is
bivariant and the JVM's is not), object literals and interfaces as property bags
(the DYNAMIC half of the hybrid, which §7 of the structural-typing page measured
as 12x the nominal half), `Map`/`Set`/`RegExp`/`Date`/`Error` runtime classes,
enums as inlined constants, `bigint` literals, the operator families, strings
and templates (never Kotlin's same-named members — `length` is a NUMBER and
`Double.toString()` prints `6.0`), control flow (`switch` with fall-through as a
one-iteration `do…while` plus a `matched` flag, `for…of` as an index walk,
`try`/`catch`, `throw` of any value), classes 2 (`extends` a generated OR a
runtime class, `super`, statics, accessors, `instanceof`), modules with a
dependency-ordered `moduleInit` per file, destructuring with defaults at both
levels, optional chaining, overloads, and the DYNAMIC member operations
(`jsGet`/`jsSet`/`jsInvoke`/`jsIndexGet`/`jsIndexSet`) for an `any` receiver.

**TWO HARNESS DEFECTS THE LIBRARIES EXPOSED.** The corpus runner's
`waitFor(2, MINUTES)` sat one line BELOW a `readText()` of the child's stdout,
which blocks until the child exits — so a generated program that looped without
printing hung the whole suite at 100% CPU with the deadline unreached (it now
redirects to files). And a `continue` inside a `for…of` skipped the increment,
which is the same trampoline `for(;;)` already had.

### QUEUE — work top-to-bottom; promote unblockers per protocol

**OWNER DIRECTIVE 2026-08-22, TOP OF QUEUE: make the language service incremental enough
to carry an IntelliJ-style plugin's error reporting.** (INC.1) landed and MEASURED the
rest of the arc: narrowing the CHECK is finished (a median file's own checking is 15 ms),
so (INC.2) and (INC.3) below are what is left, in that order.

- [ ] **(DOC.1) `CLAUDE.md` HAS SILENTLY REGROWN 91 KB -> 409 KB, WHICH IS THE THIRD
  TIME AND THE LARGEST (measured 2026-08-23).** Its own header records the ladder —
  284 -> 170 KB (2026-06-10), 594 -> ~280 KB (2026-07-06, "after it silently regrew
  — do not regrow it"), 425 -> ~91 KB (2026-07-26, the owner's "less is more"
  context-engineering directive that moved every per-diagnostic and per-walker
  section WHOLESALE to `docs/history/CLAUDE-GOTCHAS-ARCHIVE.md`). It is now **4.5x
  that target** and every session pays it on every request.
  **This is not a judgement call about which entries are good** — the file states
  the residency rule itself: what stays is cross-cutting architecture of LIVE
  subsystems, process/build traps, measured negative knowledge, test conventions,
  Kotlin idioms and the mission; per-walker, per-TS-code, per-round and per-fix
  narrative goes to the archive, which is PROGRESSIVE DISCLOSURE, not deletion.
  So the trim is mechanical against a written rule, and the round that does it must
  (a) move rather than delete, (b) keep the "GREP THE ARCHIVE FIRST" pointer
  accurate, and (c) record the new size in the header ladder so the next regrowth
  is visible. Also worth checking the same axis on `PLAN-PHASE-5.md` (4,903 lines)
  against the ~10-round trim-on-write rule.

- [x] **(INC.1) A NARROWED DIAGNOSTICS QUERY — LANDED 2026-08-22.**
  `Project.diagnosticsOf(fileNames)`, 4,818 -> 1,107 ms warm, all 78 files of the compiler
  profile agreeing row for row. See the session note; the gate is
  `scripts/partition-equivalence.sh` and the prize was measured first by
  `scripts/incremental-cost.sh`.

- [x] **(INC.2) NARROWING THE INTERACTIVE CAPTURE QUERIES — REFUSED 2026-08-22, AND THE
  REFUSAL IS A MEASUREMENT.** It would have been **3.73x** (full capture median 4,614 ms
  against a narrowed 1,110; warm rotated on `binder.ts`, 7,787 spans: 4,719 vs 1,264).
  `scripts/capture-equivalence.sh` compared **381,666 spans over 76 files**, both arms,
  span for span: **45 spans in 11 files diverge — types 45, definitions 0.**
  **THE SHAPE:** a type reference INSIDE a foreign file's ANONYMOUS OBJECT TYPE LITERAL
  renders `any` under the partition where the whole-program build renders the declared
  type — `(state: { program?: any | undefined; compilerOptions: any })` for
  `{ program?: Program | undefined; compilerOptions: CompilerOptions }`. The outer
  signature survives; it is the literal's MEMBERS that collapse.
  **THE MECHANISM IS FIRST-TOUCH CACHE ORDER, NOT THE PARTITION, AND THE CENSUS PROVES IT
  RATHER THAN ASSUMING IT: in 5 of the 45 the FULL build is the one rendering `any` where
  the narrowed one renders `T`** (`(key: K, valueInNewMap: U) => any` against `=> T`).
  `symbolTypes` persists the first resolution (round 778's order-dependence), and which
  file touches a foreign type first differs between the arms. So the diff is a DETECTOR
  for a defect that is already there — see (INC.5) — and narrowing merely makes it
  observable.
  **IT DOES NOT REACH DIAGNOSTICS, AND THAT WAS MEASURED TOO, BECAUSE IT IS THE QUESTION
  (INC.1) RESTS ON.** A fixture whose error exists only while the literal's member keeps
  its declared type (`const n: number = make().program`, where `make(): { program: Program }`
  lives in a second file and `Program` in a third) is reported IDENTICALLY by the
  partition — `ProjectNarrowFalseNegativeTest`, and the whole-project sweep on the same
  fixture agrees. **Its FIRST shape was vacuous** — an argument-position error
  (`use({ program: 1 })`) this compiler does not report at all, so both arms agreed on an
  empty list and the pin passed while measuring nothing. Its own control caught that,
  which is the reason to write one.
  **SUPERSEDED BY (INC.2b), WHICH LANDED THE NARROWING ON 2026-08-22 AFTER (INC.5) AND
  (INC.6) TOOK THE 45 DIVERGENT SPANS TO 5 WITH THE WRONG-DIRECTION COUNT AT ZERO.** The
  refusal below stands as the reasoning it was, and its premise — 45 spans where a
  narrowed hover renders a worse type — no longer holds. What the refusal bought is the
  two defects it found on the way, and the two gates that now watch the whole thing.
  ORIGINAL VERDICT: **hover, completion, go-to-definition and signature help stay whole-program builds.**
  A tooltip that says `any` where the type is `Program` is a worse defect than a slow
  tooltip, and 45 wrong spans is 45 too many for a query whose only job is to tell the
  truth about a type. Re-run the sweep after (INC.5) and this lands for free — the harness
  and the script are committed, so the re-test is one command.

- [x] **(INC.6) THE LAST 4 WRONG-DIRECTION SPANS ARE GONE — LANDED 2026-08-22.** The
  capture sweep reads **5 divergent spans in 3 of 76 files** out of 381,666, and
  `narrowRendersMoreAny = 0`: the whole user-visible class is closed. The fix is one line
  plus its KDoc in `materializeModifierUtility` — the member copy's type is populated AT
  MINT TIME, ungated. **The diagnosis in the entry below HELD and was sharpened by the
  trace**: the copies being fresh is only half of it, and the half that explains why
  (INC.5)'s pin was green is that `getTypeOfSymbol` RESOLVES the member correctly every
  time and round 778's write gate refuses to RECORD it whenever the ambient context is
  non-empty — which inside a `namespace` body it always is. So (INC.5)'s force-then-read-
  the-cache is a no-op exactly there. Suite 15,640 / 0 / 3, no corpus baseline moved, cost
  gate's drift measured PRE-EXISTING against the un-fixed binary. The 5 REVERSED rows are
  diagnosed in the session note and are three separate display-only mechanisms, in four of
  which the NARROW arm is the better answer. ORIGINAL ENTRY: **THE LAST 4 DIVERGENT SPANS,
  AND THEY ARE WHAT STANDS BETWEEN (INC.2) AND A 3.68x LANGUAGE SERVICE.** After (INC.5) the capture sweep reads **9 divergent spans in
  4 of 76 files — 4 wrong-direction and 5 reversed**, out of 381,666. All 4 of the
  wrong-direction rows are `Readonly<BuilderState>` in `builderState.ts`, and the cause is
  named: `materializeModifierUtility` mints FRESH copy symbols on every materialization,
  so warming one dies with the instance, where `Pick`/`Omit` cleared precisely because
  `materializeMemberSetUtility` reuses the SOURCE symbols and their ids are stable. The fix
  is to populate `symbolTypes[copy.id]` AT MINT TIME in the materializer — which
  `getTypeFromTypeLiteral` and `getTypeFromMappedType` already do — and that is **not
  capture-scoped**: it would put diagnostic messages in play, so it needs the corpus as its
  gate rather than the sweep alone. (INC.5) deliberately stopped short of it.
  **The 5 REVERSED rows are a different family and may not be a defect at all**: 2 in
  `tsbuildPublic.ts` where the WHOLE-PROGRAM arm renders `(key: K, valueInNewMap: U) => any`
  and the narrowed one the better `=> T`, 2 in `watch.ts` (overload-set content), 1 in
  `watchPublic.ts` rendering a signature twice. None is a lost member resolution. Diagnose
  them before assuming they are one.

- [x] **(INC.2b) LANDED 2026-08-22, owner directive — the caret-scoped capture queries
  are narrowed.** Hover, go-to-definition, completion, signature help, the semantic sweep
  and document highlights hand the compiler the queried BUFFER as its check partition;
  `referencesAt` and the rename sweep do not, because their claim is program-wide.
  Measured `quickInfoAt` **5,004 -> 1,015 ms** end to end with three flat controls, and
  **4,581 -> 979 ms (4.68x)** within one process on `binder.ts`. The partition is DERIVED
  from the request's spans, which is what makes the pins discriminate. See the session
  note for the second gate this needed (`scripts/capture-channel-equivalence.sh`, for the
  three channels the old one never covered) and for the five display mechanisms it found.
  ORIGINAL ENTRY: **OWNER DECISION: LAND THE CAPTURE NARROWING NOW, OR AFTER (INC.6)?** The
  refusal recorded above was written against 45 divergent spans; after (INC.6) it is
  **ZERO** in the user-visible direction — `narrowRendersMoreAny = 0` over 381,666 spans —
  against **5.26x** measured this round on every hover, completion, go-to-definition and
  signature help. **What is left is 5 spans in 3 files, all display-only and all diagnosed in
  (INC.6)'s session note: 2 where the narrow arm renders the ALIAS name (`Intl.LocalesArgument`)
  and the full arm its expanded body, 2 where the FULL arm renders a generic interface
  member's return as `any` where the narrow renders the declared `T`, and 1 where the narrow
  arm renders an intersection member as the redundant `X & X`. In 4 of the 5 the narrow arm
  is the better answer.** So the correctness argument for waiting has inverted: the
  whole-program arm is now the one rendering a worse type more often, and the wiring is a
  one-line change per call site. **Not decided
  autonomously: it trades a measured correctness regression against a measured latency win,
  which is the owner's call.** Everything needed to execute either way is committed — the
  gate, the census and the call sites are named in (INC.2).

- [ ] **(INC.8) THE TWO DISPLAY MECHANISMS (INC.2b)'s SECOND GATE FOUND, AND NEITHER IS A
  PARTITION DEFECT.** `scripts/capture-channel-equivalence.sh` reads 286 divergent rows of
  21,507 in five mechanisms; three are worth closing and none can be closed on the capture
  path, because the renderer is shared with the diagnostics (the (INC.5) rule: never
  `typeToString`, ~13k baselines).
  (a) **x167 — a member's own type parameter renders `<K>` under one arm and
  `<K extends any>` under the other, and NEITHER renders the declared constraint**
  (`shouldAssertFunction<K extends keyof typeof assertionCache>`). That is a defect in BOTH
  arms, like (INC.6)'s `Readonly<T>`: the sweep only made it visible.
  **DIAGNOSED ONE LEVEL DEEPER 2026-08-23 by (INC.19), which also REFUTED the obvious
  guess.** It is NOT (INC.19)'s first-touch freeze: the fix that took the replay's lost
  constraints from 8 files to 5 left these 167 rows **byte-identical** (the whole
  channel unchanged at 286 spans / 49 files). A probe on the shape reads `TPWRITE
  name=K was=any now=any` — the constraint is **already `any` before
  `checkTypeArgumentConstraints` runs**, so nothing downstream can be blamed. It is a
  **namespace-local type alias failing to resolve in constraint position** — a NAME
  RESOLUTION defect, not an ordering one. Start there, not at the renderer.
  (b) **x116 — an alias's expansion carries `| undefined` TWICE**
  (`string | Locale | readonly (string | Locale)[] | undefined | undefined`). Two defects in
  one row: the duplication, and the fact that a first-touch `aliasDisplayMap` registration
  decides whether the alias name or its body is printed. tsc prints the alias.
  (c) **x1 — a signature parameter renders `any` under the narrowed arm.** The ONLY row in
  either channel where narrowing produces the answer a user would call wrong. Same family
  as (b); worth a trace before (a) or (b), because it is the one with a cost today.
  Not worth a round on its own; fold into whichever round next touches the display of a
  signature or an alias.

- [x] **(INC.3) THE FLOOR IS DECOMPOSED — step 1 DONE 2026-08-22, and it inverted its own
  lever order.** 1,219 ms on the compiler profile: **tail walkers 806.7 (66.2%)**, `init:*`
  setup 112.2 (9.2%), **BIND 240.6 (19.7%)**, crawl 27.4 (2.2%), `checkSpine` **0.1 ms**,
  residue 3.1 (the partition closes at 99.7%). `scripts/floor-decomposition.sh` is the
  instrument; the session note carries the four refuted beliefs — bind is not 515 ms (that
  is a per-WORKER contended term), the crawl is not 138 ms (parses are fully cached),
  `init:buildFileLocalTypeMaps` is not 3.56% (1.4%), and the two never-warming
  whole-program regex passes are already gone (0.44 ms). **What it leaves is (INC.7), a
  bigger lever than either of the two this entry used to rank first.**

- [x] **(INC.9) THE FLOOR RE-DECOMPOSED AND ITS LARGEST MECHANISM DEFERRED — LANDED
  2026-08-22.** Re-measured rather than scaled (the (INC.3) table was taken at a 1,219 ms
  floor; 68 gated walkers later it is a different table): of a ~523 ms floor, CHECK — the
  ~190 surviving `init` passes — is **304.2 ms (58.2%)**, BIND **197.8 (37.8%)**, crawl +
  config + imports + post 18.4 (3.5%). Bind is NOT the largest component, but it holds the
  largest single MECHANISM: `FlowGraphBuilder.build` at **126.1 ms = 24.1% of everything a
  narrowed query costs**, against a pass table whose biggest row is 66 ms.
  **`BinderResult.flowGraph` now builds on first ask** — floor **514 -> 378 ms**, narrowed
  query median **542 -> 422**, ratio at the median file **9.70x -> 12.43x**, and
  `partition-equivalence.sh` EQUIVALENT on all 78 files. This is exactly the candidate
  `docs/perf/warm-flow-graph-attribution.md` § 9.3 priced at **0.3%** and refused — a
  correct number about a FULL build, where every checked file's spine setup asks for its
  graph; under a partition the same rule reaches 122 of 123 files. **REFUSED in the same
  round, with the measurement: a cross-query BIND CACHE.** All of bind is now 72 ms of a
  378 ms floor, so the ceiling is 19%, and against it every `BinderResult` from one
  `Binder` SHARES its `(pos, end)`-keyed `nodeToSymbol`/`moduleInstanceStates` maps (they
  are the binder's fields, accumulated across files, and those keys collide across files),
  while `mergeSingleSymbol` adopts binder-owned symbols and `declarations.addAll` is not
  idempotent. Large, silent-failure-shaped, for 72 ms.

- [x] **(INC.10) ONE OF THE TWO PROGRAM-WIDE SETUP PASSES IS GONE; THE OTHER IS
  REFUSED WITH A THREE-POINT MEASUREMENT.** `init:trackAllImportReferences`
  (**29.44 ms**) is EMIT-ONLY work — its product `referencedAliases` has one
  reader, `isReferencedAliasDeclaration`, which has one caller, one line of
  `Transformer` reached only by `import x = require(…)` under `module: preserve`
  — so it now runs on that first ask and a `--noEmit` build performs it **0**
  times (was one per file per checker, i.e. N under `CheckerPool`). Floor pass
  table **305.3 -> 274.8 ms**, narrowed query median **422 -> 402**, ratio
  **12.43x -> 12.61x**, and the banked ms EXCEEDS the row (30.5 vs 29.44) because
  this walk resolves nothing, so the (INC.7) relocation discount has nothing to
  describe. **`init:buildFileLocalTypeMaps` (66 ms) IS REFUSED, and it was built
  before it was refused**: the deferral works and is cheap (78 -> 3 maps built on
  the floor arm, row 66.07 -> 0.01, query median 349, ratio **14.17x**,
  `partition-equivalence` EQUIVALENT, cost gate and corpus unmoved) and it moves
  the CAPTURE channel from **5 divergent spans to 2,722 in 46 of 76 files**. The
  pass's real product is not the 4,161 entries round 829 censused but the
  whole-program FIRST-TOUCH ORDER for type interning and `aliasDisplayMap`; keep
  the `TypeAlias` symbols eager and it is 6.81 ms / 462 spans, keep the whole
  DECLARATION branch eager and it is **64.94 ms / 5 spans** — i.e. the deferrable
  part is **1.13 ms of 66**. Do NOT re-open it from round 829's read-count
  census: read-ness of the ENTRY is the wrong question.

- [x] **(INC.12) THE WARM PROGRAM IS PRICED, AND STAGE 1 LANDED 2026-08-22.**
  **(P1) — a second query with the program UNCHANGED — is worth the WHOLE ~345 ms
  floor** (config+crawl+imports ~12, BIND 73-88, the ~190 program-wide `init` passes
  252-254), against a queried file's own checking of 47 ms at the median file.
  **(P2) — a query after ONE buffer changed — measured IDENTICAL to (P1)**
  (`diagnosticsOf` after editing the queried file 2,001 ms against 1,999 unedited),
  because outside the content-keyed parse cache there was no cross-query reuse at all.
  **LANDED: `Project.captures`** — a capture build memoized on its REQUEST, two entries,
  dropped by every edit: `quickInfoAt` then `definitionsAt` at one caret is ONE build
  (506 -> 0), `documentHighlightsAt` at every later caret in an unchanged buffer is zero
  builds (592 -> 19, the residue being the per-caret grouping), a repeated hover
  1,933 -> 0. Three ablations, each reddening a different pin set.
  `scripts/warm-program-cost.sh` is the instrument; `docs/language-service.md` §§ 13-14
  carry the table. **REFUSED with the measurement**: reusing the BIND (73-88 ms = 20% of
  a median query — not refused by (INC.9)'s per-file argument, but it needs a shape gate
  reusing the checker's own merge predicate plus a full-vs-reused differential sweep,
  see (INC.13)); and reusing the CHECKER (252-254 ms = 63%, the largest thing left, and
  the one that makes WHICH QUERY RAN FIRST observable — see (INC.14)).

- [x] **(INC.13) STAGE 2 LANDED 2026-08-23 — THE QUESTION A HOVER ASKS IS THE
  BUFFER'S, NOT THE CARET'S.** `Project.captureAround` names
  `SourceIndex.occurrenceNodes()` — deliberately `documentHighlightsAt`'s own
  population — so `quickInfoAt`, `definitionsAt`, `semanticsAt`/`fileSemantics` and
  highlights are **ONE build per buffer between them**. A second caret in `checker.ts`
  **2,142 -> 73 ms**, in `binder.ts` **481 -> 2**, `fileSemantics` after a hover
  **575 -> 17**; the FIRST query in a buffer pays for it, **+27% on `binder.ts`,
  +65% on `checker.ts`**, i.e. break-even at the second caret. **The oracle was built
  first and needed no baseline** (`scripts/caret-vs-file-capture.sh`, 904 sampled
  spans in 76 files: **EQUIVALENT**, and the widening prices at **+17 ms at the
  median file**). It does NOT widen for a caret on a node that is no occurrence — a
  call expression, a literal, a `this` — because a file-wide request would not carry
  it and an absent capture renders nothing with no error anywhere. Three ablations;
  A3 was BLIND until the fixture grew a member-name literal. **The 34x batching ratio
  `docs/language-service.md` advertised to hosts is GONE** — batching a buffer is now
  a convenience, not a cost decision.

- [x] **(INC.15) REUSING THE BIND FOR AN UNCHANGED PROGRAM — REFUSED 2026-08-23,
  AND THE REFUSAL IS A RE-PRICING, NOT A SOUNDNESS FINDING.** The mechanism checks
  out: on today's binary `--bindMutationCheck` reads **`binder Symbols checked
  15580, changed 0`** over a population that reaches transitively through
  `locals` + `nodeToSymbol` + every `members`/`exports` table, in the SAME run as
  `mergeSingleSymbol: adopts 406, mutates 175 (164 reaching an adopted symbol)` —
  every one of those 175 mutating merges lands on a LIB symbol, which is in no
  program `BinderResult`. `mergeModuleAugmentations` was read line by line as the
  queue entry asked: its four writes are `globals[name] = augSymbol` (a same-value
  put), `flags or …` (idempotent), `declarations.add` guarded by `if (decl !in …)`,
  and `mergeSymbolTable` into an `exports` table — and only the LAST of those is
  non-idempotent, because `mergeSingleSymbol`'s existing-name branch does a bare
  `merged.declarations.addAll(symbol.declarations)`. On this program it never fires
  against binder-owned state, which is what the zero says.
  **WHAT REFUSES IT IS THE POPULATION, RE-PRICED AGAINST (INC.13)'s FLOOR.** Bind is
  **66–74 ms of a 359–407 ms floor (18.4%)**, and of that **69 of 74 ms is
  `bindLexicalScopes`**. Against a QUERY it is 12.8% of `diagnosticsOf(binder.ts)`
  (547 ms), **10.7%** of a first hover in that buffer (655 ms), **3.1%** of a query
  about `checker.ts` (2,232 ms), and **2.75% of the whole 15-query editor sequence
  `warm-program-cost.sh` drives** (~10.2 s). And the eligible population is
  "the program is UNCHANGED since the previous build", which **excludes the first
  query after an edit — the error-reporting query the owner directive names — where
  it is worth exactly 0**.
  **AND IT IS THE WRONG ORDER: (INC.14) SUBSUMES IT BY CONSTRUCTION.** A reused
  `Checker` carries its own bind, so bind reuse is 20% of a floor that checker reuse
  removes 100% of, and the plumbing (a content-keyed cache threaded `Project` ->
  `ProjectCompiler` -> `compileParsed` -> `compileParsedCore` -> `cpcBindAndCheck`)
  would be thrown away by it. A third fact against doing it first: the checker's own
  merge predicate is `moduleLocalContributesGlobally`, which reads `umdGlobalNames`
  and `mergeSharedKeepNames` — both computed INSIDE `Checker`'s init — so the shape
  gate the queue entry demands can only be evaluated AFTER a build. The design is
  therefore necessarily "build once fresh, reuse only if that build reported clean",
  and the first query of a session never benefits either.
  **WHAT SURVIVES AS A LEAD, and it is bigger and better shaped**: `bindLexicalScopes`
  is **93% of the bind** and the INV.2(c) tables it builds are read per-FILE, so
  (INC.9)'s exact deferral template applies — see (INC.16).

- [x] **(INC.16) LANDED 2026-08-23 — THE INV.2(c) TABLES BUILD ON FIRST ASK AND A
  NARROWED QUERY IS 20.5% FASTER.** `bindLexicalScopes` was 93% of the bind and, after
  (INC.7) batch 4 and (INC.11), the largest single remaining mechanism in the floor.
  **Scope tables built on a floor build 123 -> 3; `FrontEnd` bind 70 -> 6 ms; floor
  median 333 -> 286 ms; narrowed-query median over all 78 files 346 -> 275 ms
  (−20.5%), the SUM 29,378 -> 23,909 ms.** `partition-equivalence.sh`'s own recipe
  reads floor 248 / median 313 / ratio **15.66x**.
  **THE BLOCKER WAS SERVED BY A PROJECTION, NOT BY GATING.** A `forcedBy` census
  confirms `init:computeAllEnumValues` was the SOLE forcer of all 78 program files.
  `declareLexical`'s two mint sites are NOT symmetric — the alias half wants a NAME
  (the binder hands it over), the enum half wants the scope-space SYMBOL (`compute-
  EnumSymbolValues` is id-keyed) — so only an `enum` in a fresh scope forces a build,
  and the projection costs two int compares per node on a walk that already runs and
  is content-cached. Refinement measured: 67 of 78 skipped, then 69, then **75**.
  **HAZARD (a) DID NOT FIRE AND WAS REMOVED ANYWAY.** An ID-FREE FINGERPRINT of every
  file's tables is IDENTICAL on all 78 across three runs — but that bounds frequency,
  not existence, so `Binder.lexOwnerSymbols` (a per-file `nodeId -> Symbol` table)
  replaces both reads of the shared `(pos,end)`-keyed `nodeToSymbol`. Order-independence
  is now structural; arm a4 reddens a pin built from two same-length sources whose
  namespaces collide on a node key.
  **LEFT OPEN (~20 ms)**: 3 files still force on the floor — those with a genuinely
  block-scoped `enum`, where the census needs the SYMBOL and not a name. Serving them
  means minting that symbol outside the scope walk, a larger change than this round's.
  The 45 real-lib `.d.ts` binds are forced by nobody and are worth only ~2 ms.
- [x] **(INC.14) A `Checker` NOW ANSWERS A WHOLE WORKING SET — LANDED 2026-08-23 as
  `Project.prepare(files)`, plus a partition-keyed `diagnosticsOf` memo beside it.**
  252-254 ms of every query's floor is the ~190 program-wide `init` passes, and the
  census said a checker shared by k queries answers all k exactly as k fresh ones do.
  **The refactor the entry called for was not needed, and the census's own model is
  why**: a checker asked a k-th query IS a checker whose partition is those k files,
  and that arrangement is expressible with no checker surgery — hand `recheckOnly` the
  working set once and capture all of it in the one walk. `prepare` is the census's
  SHARED arm made public.
  **THE ORDER GAP THE ENTRY NAMED IS CLOSED FIRST, AND IT CLOSED CLEANER THAN PROGRAM
  ORDER.** `checker-reuse-differential.sh` grew an `editor` arm — a deterministic
  shuffled query SEQUENCE with revisits, chunked into groups, compared POSITION BY
  POSITION, with the COLD arm run over the same sequence so "is the reference itself
  order-dependent?" is a control (`coldSelfDiverged`, which REFUSES the run) and not an
  assumption. 101 queries over 76 files, 25 revisits, **1,070,012 compared rows per
  run**: **0 divergent rows at k=3 (2.16x) and k=8 (3.88x)**, **1 at k=26 (5.18x)** and
  that one is byte for byte the row program order already found (`watchPublic.ts@24148`,
  the COLD arm inventing `X & X`), already inside `capture-equivalence.sh`'s 5-span
  baseline. `coldSelfDiverged = sharedSelfDiverged = 0` in all three — a revisited file
  is answered identically by a fresh checker AND by a reused one.
  **MEASURED, six mid-sized buffers (55-83 KB, 415 KB together; deliberately not
  `checker.ts`, whose 1.65 s of own checking would bury the floor), three rotations,
  replicated in a second run**: 18 semantic queries **5,230 -> 737 ms and 4,997 -> 704
  (7.1x both)**; six per-buffer `diagnosticsOf` **2,338 -> 526 and 2,376 -> 539**, with
  every re-ask **0**. The existing 15-query block is a CONTROL and did not move.
  **What a held prepared check costs, with a control rather than as an absolute: heap
  163 -> 167 MB, identical to the MB in all six rotations — ~4 MB for that working set.**
  Bound: ONE prepared check, replaced by the next `prepare`, dropped by any edit.
  **Three rules, each with its pin**: the prepared slot is SEPARATE from the two-entry
  capture LRU (an ordinary hover cannot evict what a prepare earned); serving is decided
  by CONTAINMENT of the asked spans against the prepared REQUEST's own spans, never by
  file membership (an answer never asked for is ABSENT, and a hover served from a check
  that did not carry its span renders nothing, silently); and a prepared check may NOT
  answer `diagnostics`/`diagnosticsOf`, because a capture build types nodes the checker
  had no reason to type. **Seven ablations, seven discriminating, each with its own RED
  set** — the first round this session with no arm recorded as a control.
  **REFUSED with its arithmetic: making the working set AUTOMATIC.** Growing the
  partition to `{queried} ∪ {recently queried}` on every miss costs `k·floor +
  k(k+1)/2·perFile` against a cold `k·floor + k·perFile`, i.e. a LOSS at every k with
  the floor at 342-365 ms and a median file at 31-47 ms; bounding the growth at B makes
  every miss `(B−1)·perFile` dearer (+42% at B=4 on a median file, far worse on
  `checker.ts`). A host knows its open buffers and this layer does not.
  `docs/language-service.md` §§ 3, 3a, 13, 14.

- [x] **(INC.17) THE RE-ENTRANT CHECKER — BUILT, MEASURED AT 3.06x, AND **REFUSED AS A
  DEFAULT PATH** 2026-08-23. STEP 1 (THE CENSUS) STANDS.** `prepare` collects the floor for
  files a HOST NAMED; a query about a file it did not name still pays the whole
  342-365 ms. Measured with `scripts/partition-census.sh` (a RUNTIME classification —
  `checkedResults` is a getter recording `PassTiming.currentPass`, so it cannot be
  wrong about who read it — six draws, three partition shapes, tsc's own 78 sources):

  | bucket | rows | floor ms | one-file ms |
  |---|---:|---:|---:|
  | partition-INVARIANT | **211** | **350.89** | 375.44 |
  | partition-DEPENDENT | **205** | **15.59** | 55.05 |
  | total | 416 | 366.47 | 430.49 |

  **The prize is 95.7% of the floor and the replay's own fixed cost is 0.69 ms** —
  204 of the 205 dependent passes cost that BETWEEN them, because 201 read the
  partition exactly once (`for (result in checkedResults)` and nothing else). The
  205th, `checkSubsequentVarTypes`, is 14.90 ms with an EMPTY partition: a MIXED pass
  doing program-wide work outside its partition loop, and splitting it is the whole
  difference between 15.6 and 0.7.
  **The model is SMALLER than (INC.14) priced.** No diagnostics prefix has to be
  reset: a program-wide pass iterates `binderResults`, so it ALREADY emitted the newly
  asked file's rows in the first build and `getDiagnostics()` merely filtered them out
  at the end. A replay re-runs the 205 with the new partition and re-filters.
  **WHAT BLOCKS IT IS THE INSTRUMENT.** On the tsc profile the full build's 46
  diagnostics are netted by exactly ONE pass (`checkSpine`; the new signed-delta
  census reads 46 against the build's own 46, its positive control), so
  `partition-equivalence.sh` — the designated detector — compares an essentially EMPTY
  population, and the other seven profiles are the same codebase. A replay that
  produced nothing from 204 of the 205 passes would be invisible to every gate here.
  **And the classification is not yet the one soundness needs**: it measures *reads the
  partition*, where the replay needs *its OUTPUT depends on the partition*, and the two
  come apart at every spine-produces / program-wide-pass-consumes pair.
  **UNBLOCKED 2026-08-23 by (INC.18)**, which re-armed the gate — 78 netting passes and
  72 of 76 files carrying a row, against the profile's 1 and 5 — and PROVED it can
  fail: a partition-dependent walker made silent under a narrow partition reddens the
  sensitivity arm while the realism arm stays green (arms a1/a2). **Two obligations
  survive.** The classification still measures *reads the partition* where soundness
  needs *its OUTPUT depends on the partition*; and (INC.18)'s arm a3 shows the one
  round-609 collector it tried is invisible to a DIAGNOSTICS gate in BOTH arms (it is
  `capture-equivalence.sh`'s to own), so a replay must be graded on both sweeps.

  **STEP 2 IS BUILT AND IT IS REFUSED. THE PRIZE IS REAL: 3.06x** on tsc's own 78
  sources — `replay=12572 ms` against `freshBuilds=38498 ms` over 75 questions.
  The mechanism is in the tree and OPT-IN by construction (`Recheck.kt`,
  `Checker.recheckAdditionalFiles`, `build(recheckHolder = ...)`); nothing in a
  shipped path passes a holder and `Project` does not know the type exists.
  **WHAT REFUSES IT is the second sweep, exactly as (INC.18)'s arm a3 predicted.**
  `scripts/replay-differential.sh` reads
  `compared: files=75 diagnosticRows=46 filesCarryingDiagnostics=5 typeSpans=373879
  definitionSpans=352713` and then **`DIVERGED: 8 of 75 file(s)`** — with the
  DIAGNOSTICS half completely untouched. The shape is a **lost type-parameter
  constraint**: the replay renders `<T extends Node, U>` where a fresh build renders
  `<T extends Node, U extends T>`. A wrong hover is worse than a slow one, and
  (INC.2) set the precedent by refusing capture narrowing over 45 divergent spans;
  8 divergent FILES is far past it.
  **WHAT LANDED ANYWAY**, so (INC.19) starts from an oracle rather than rebuilding
  one: the mechanism marked EXPERIMENTAL at every entry point, `ProjectRecheckTest`
  pinning what it ACTUALLY does (diagnostics equivalence, the build-count receipt,
  the behaviour-free arming — and deliberately NOT capture equivalence, which would
  be a false pin), `scripts/replay-differential.sh` + `ReplayDifferentialMain`, and
  the `checkSubsequentVarTypes` split the census demanded (15.59 -> 0.69 ms of
  replay cost), pinned on both sides by `PartitionCensusHookTest`.
  **THE ATTRIBUTION ARM THAT DID NOT WORK, so nobody re-runs it:** re-entering ALL
  passes over **7** targets burned **53 minutes of CPU without finishing**, against
  ~50 s of total compute for the 205-pass replay over **75** targets — ~100x, the
  signature of a pass that appends to a side table or re-emits per replay. Killed,
  not completed. (INC.19)'s instrument is a BISECTION, not that arm.

- [ ] **(INC.19) THE LOST CONSTRAINT IS FIXED AND IT WAS NEVER A REPLAY DEFECT —
  8 -> 5 DIVERGING FILES, AND THE SURVIVORS ARE A DIFFERENT CLASS (2026-08-23).**
  The queue entry this replaces said "the replay SET is too small — bisect it".
  The instrument was built (`aca8a60f`) and REFUTED that: three causes were
  measured, and the dominant one is reachable by no replay-set change at all.
  **(c), THE DOMINANT ONE — FIXED (`7b1cc323`).** `Type.TypeParam.constraint` is
  interned per node and WRITE-ONCE, and `checkConstraintsInStatements` resolved it
  BEFORE installing the type-parameter scope, so `U extends T` resolved its sibling
  against the outer scope, answered `errorType`, and froze. `checkSpine` (row 28,
  partition-scoped) races `checkTypeArgumentConstraints` (row 261, program-wide) for
  the field; unpartitioned, `checkSpine` always wins, which is why all ~13k corpus
  baselines are blind. Two sites hoisted, and the third — `withDeclTypeParamScope` —
  **must NOT be hoisted**: a self-referential alias (`type Shared<I, D extends
  Shared<I, D>>`) then recurses without bound and the `init` guard reports a
  spurious TS2589. It got the write-once guard instead, which it lacked, so it can
  no longer CLOBBER a correct constraint. Pinned by `ProjectRecheckConstraintTest`,
  verified 2-of-3 RED against HEAD with its control green.
  **(a) REAL BUT SMALL, NOT LANDED.** `init:computeAllEnumValues` is classified
  partition-INVARIANT and yet repairs `program.ts` when added to the replay set
  (replicated) — its row is a block-scoped `const enum`, the B83.5 population. Worth
  landing only once the replay ships.
  **(b) REAL, AND IT BOUNDS THE WHOLE DIRECTION.** `init:wireGlobalArrayTypes` does
  not TERMINATE when replayed; `init:mergeLibGlobals` makes the answer strictly
  WORSE (+1 file). So the replay set is a PER-PASS question, never a superset or
  subset one, and each addition must be measured.
  **WHAT IS LEFT: 5 files, 23 spans of 373,879, and no lost constraint among them.**
  They are lost generic INFERENCE — `Connection[][]` -> `any[][]`, `Map<string,
  SeenPackageName>` -> `Map<any, any>`, `(key: K, valueInNewMap: U) => T` ->
  `… => any`. Diagnose that class before touching the replay set again.
  **THE INSTRUMENT IS COMMITTED AND RESUMABLE**: `scripts/replay-bisect.sh`
  (`dump`/`sweep`/`try`/`narrow`), `PassTiming.replayExtraPasses`, and a RUN-TIME
  pass universe — a source grep of `pass("…"` reads **480** names against the
  dispatch's **417**, so a grep-derived bisection could never have closed. 19 of 210
  candidates are swept; `build/bench/replay-bisect/rest.txt` holds the other 191.
  **THREE SITES STILL RESOLVE A CONSTRAINT OUTSIDE ITS SIBLINGS' SCOPE** and are
  reported, not fixed: `Checker.kt:111069` (fresh non-interned params, so it cannot
  corrupt the cache), `Checker.kt:137404` (**inside `typeParamInternCache.getOrPut`**,
  i.e. a first-touch freeze BY CONSTRUCTION — the hardest, since the factory runs
  before any scope exists), and `Checker.kt:139240`.
  **DO NOT** wire the recheck into `Project` before this closes; `Recheck.kt`'s
  banner says so and `ProjectRecheckTest` pins that nothing reaches it by default.

- [x] **(INC.18) THE PARTITION GATE WAS VACUOUS ON EVERY PROFILE THIS REPO HAS —
  THE FIXTURE THAT RE-ARMS IT LANDED 2026-08-23, AND IT IS PROVEN ABLE TO FAIL.**
  The receipt is a COUNT — how many DISTINCT passes net a diagnostic, off
  `PassTiming.diagNetByPass` — and the contrast is the finding:

  | project | files | diagnostics | files carrying a row | passes netting one |
  |---|---:|---:|---:|---:|
  | `build/bench/tsc-project-*` | 78 | 46 | **5** | **1** (`checkSpine`) |
  | `test-fixtures/partition-gate` | 71 | 175 | **70** | **78** |

  So 73 of 78 per-file comparisons on the arm that has always run are empty against
  empty, and all eight dashboard profiles are that same codebase.
  **`scripts/partition-gate.sh` runs BOTH arms** — realism unchanged, sensitivity
  added — and the sensitivity arm REFUSES below its floors (40 netting passes, 40
  files carrying a row) rather than printing green.
  **`scripts/partition-gate-ablate.sh` is the proof it can fail**, one injected
  mistake at a time, with a both-GREEN control (`checkCloduleTest2`, a pass netting
  on neither project) and a both-RED control (`checkSpine`) that make the other arms
  attributable. See the session note for the table.
  **WHY IT IS HAND-WRITTEN.** `PassDiagMineMain` mined all 6,451 conformance cases
  for per-pass attribution (2,802 netting, **241 distinct passes**) and
  `scripts/partition_fixture_compose.py` greedy-covers that record — but past ~24
  files each case adds **exactly one** new pass, i.e. the tail walkers are one-shape
  walkers, and this repo does not vendor TypeScript source. The miner says WHICH
  shapes to write; the files are written from scratch.
  **IT RETRO-PRICES LANDED WORK**: (INC.7)'s 68 gated walkers and (INC.9)'s deferral
  were profile-green for a reason that says nothing — only the corpus, which has no
  partition, stood behind them. Unmeasured on this axis, not wrong, and re-runnable.
  `docs/partition-gate-sensitivity.md`.

- [x] **(INC.11) THE 66 ms IS REFUSED 2026-08-23, AND ITS PREMISE IS MEASURED FALSE —
  PART OF THAT COST BUYS *RESOLUTIONS*, NOT A FIRST-TOUCH ORDER.** The item said the
  65 ms buys only a program-wide first-touch ORDER for interning and `aliasDisplayMap`.
  A three-phase re-measurable arm (`FltmDefer` / `XTSC_FLTM_EAGER`, default = shipped,
  pinned inert) says otherwise: fully deferred is **1,665 divergent capture spans in 47
  files with `narrowRendersMoreAny = 321`** — 321 resolutions LOST TO `any`, which is
  not a naming question and cannot be fixed by any display change. (Its numbers beat
  (INC.10)'s 2,722 / 46 by 1.6x, and `TYPEALIAS`-only is 137 / 10 against 462 / 18,
  because an ask-triggered whole-file build still builds every file's map in check
  order on a FULL build.) **Do not re-open this as a display problem.**
  **SUB-PROBLEM (b) IS CLASSIFIED AND THE ITEM'S HYPOTHESIS ABOUT IT IS REFUTED**: the
  residual rows are NOT two `Type` instances but **ONE instance carrying two competing
  names**. A `Extract<ClassLikeDeclaration, Pick<T, "kind">>` whose conditional cannot
  decide (free `T`) answers its own CHECK TYPE — the interned union — and the generic
  site then wrote `aliasDisplayMap[union.id] = ("Extract", args)` unconditionally.
  **That was a SHIPPED, whole-program hover defect** (an unbound `T` in a tooltip) and
  is FIXED — an instantiation that returns one of its own arguments unchanged no longer
  registers a name for it. `AliasDisplayIdentityTest` pins it and needs `@useRealLibs`
  to reach the mechanism at all.
  **WHAT REMAINS, AND IT IS A CHANGE OF KEY, NOT OF POLICY**: the (a) half — 302 spans
  in `checker.ts` alone under full deferral — is two SYNONYMOUS non-generic aliases
  resolving to one interned type, decided first-wins. **tsc picks by the REFERENCE's
  declaration site, which an id-keyed global map cannot express**, so closing it means
  re-keying alias display, against round 754's deliberate `Type.Reference` exclusion and
  a union display order pinned byte-for-byte across ~13k baselines. That is a
  logical-parity conversation (`docs/logical-parity.md` § 2) and is NOT worth opening
  for a 66 ms the table above has already refused.
- [x] **(INC.7) DONE 2026-08-23 — 157 WALKERS GATED ACROSS FOUR BATCHES, AND BATCH 4
  CLOSED THE TECHNIQUE RATHER THAN THE FAMILY.** Batches 1-3 gated 68; batch 4 gated
  **89** more in two independently swept sub-batches. **Floor 1,207 -> 340 ms,
  narrowed query median 1,077 -> 367 ms, ratio at the median file 13.30x.** The batch-4
  diff is 89 loop headers and nothing else (`binderResults` 221 -> 132, `checkedResults`
  255 -> 344). The relocation discount now has FOUR points — 79.0 / 85.5 / 92.9 /
  **78.2%** (54.23 ms of rows for 42.41 ms of floor).
  **WHY IT IS DONE: 65% OF WHAT REMAINS IS REFUSED BY SHAPE.** 172 ungated passes /
  251.9 ms remain, and the top TEN rows are **165 ms** of it, every one refused —
  `init:buildFileLocalTypeMaps` 62.06 (writes `deepInstantiationBailed`),
  `checkTypeArgumentConstraints` 21.69, `checkBaseClassImprovedMismatch` 19.51
  (`diagnostics[i] =`), `checkInterfaceMultiBaseConflicts` 12.73,
  `checkSubsequentVarTypesPerFile` 10.70, `checkPropertyOverride` 9.61,
  `checkDerivedConstructorSuper` 9.04, `init:computeAllEnumValues` 8.75,
  `checkCircularClassBaseViaDefaultTypeArg` 6.91, `checkClassImplementsInterface` 5.94.
  Analyzer-CLEAN was only 54 ms in total. Of the 83 refused: **53** write a checker
  field or retract inside the private closure, 4 carry more than one `binderResults`
  reference, 4 hold a cross-file pre-loop accumulator, and **43 retract via
  `diagnostics.removeAll`**. **A successor must change the SHAPE of a retracting or
  field-writing pass — the loop header is exhausted.** See (INC.20).
  **TWO ANALYZER INVARIANTS WORTH MORE THAN THE BATCH** (both now in CLAUDE.md): a
  MULTI-LINE PARAMETER LIST truncates a function's span to its header, hiding the body
  and every field write in it — it wrongly cleared two passes THIS QUEUE HAD ALREADY
  REFUSED, so the refusal list is the oracle that catches the analyzer; and a
  `pass("…")`-REGISTERING helper is not a caller, so without excluding the 12
  `initCheckPasses*` registrars the clean set is **0**.

- [x] **(INC.20) LANDED 2026-08-23 — 13 PASSES, AND THE FLOOR PASS TABLE NEARLY HALVES:
  `PT.total both.floor` 219.98 -> 119.74 ms.** (INC.7) batch 4 refused 53 passes on
  "writes a checker field inside the private closure"; **the verdict was true and the
  inference from it was wrong** — for nine of them the write is a per-FILE AMBIENT
  install (`currentFileLocals` / `currentCheckFileName`), gone before the next file is
  walked, with the same resting value whether the loop ran 78 times or none. Sub-batch B
  used the (INC.17) template properly: two MIXED passes that build a program-wide INDEX
  then emit per file (**only the second loop moved**) and two per-file retractors — one
  of which, `checkPreEmitCountMismatchPins`, is IMPROVED rather than narrowed, since its
  TS-1 marker carries `fileName = null` and so survived the partition filter.
  **Banked 100.23 ms of 116.08 = 86.3%, the fifth discount point.** Floor 248 -> 162 ms,
  narrowed-query median 313 -> 207, ratio **15.66x -> 24.16x**. 19 pins; reverting the
  14 loop headers reddens 5 of 7 census assertions, and gating the two COLLECTION loops
  reddens exactly the three cross-file arms — the evidence the split is load-bearing.
  **THE VICTIM HAS A MECHANISM NOW, NOT A RESIDUE**: `checkReverseMappedIntersection-
  Constraint` 0.067 -> 19.431 ms, the only row outside the batch to move >0.2 ms, because
  round 895's `srcHas` builds its per-file n-gram filter LAZILY and the FIRST caller in
  pass order pays it for all 78 files. See (INC.21).

- [x] **(INC.21) LANDED 2026-08-23/24 — THE SCANNING FAMILY BANKS 99.9%, THE ARC'S FIRST
  ~100% DISCOUNT.** 19 whole-source-scanning passes gated TOGETHER (**19.064 -> 0.024
  ms**), four stragglers, and (INC.20)'s escalated reversal. `PT.total both.floor`
  **123.95 -> 97.12 ms**; floor **162 -> 137**; narrowed-query median **207 -> 166**;
  ratio **24.16x -> 29.86x**. **No row outside the batch rose** — the lazily-built
  n-gram filter had nowhere left to relocate to, and the three whole-program text gates
  that remain use a RAW `String.contains`, never round 895's filtered `srcHas`, so they
  cannot rebuild it. The list was derived by TWO independent instruments that agree.
  **THE STRAGGLERS TAUGHT THE OPPOSITE LESSON**: three keep their cost because a
  whole-program `.contains` gate sits ABOVE the loop — a question about the PROGRAM, so
  it must stay on `binderResults`, and gating the loop banks ~0.02 ms
  (`checkModulePreserve4Pin` is the control: narrowed and unmoved, 1.639 -> 1.699). What
  banks the ms is a **NAME PRE-GATE**, sound because it asks only what the pass can
  already do: 2.509 -> 0.002 and 2.064 -> 0.002.
  **THE REVERSAL'S OBLIGATION WAS DISCHARGED**: `checkSubsequentVarTypesPerFile`
  **11.740 -> 0.004 ms**, and the replay measured on both arms — 284 -> **304 of 417**
  re-entered passes for **+26 ms over 75 questions (+0.2%)**, divergence unchanged at
  5 of 75. **The replay's ADVANTAGE fell 1.91x -> 1.68x because the fresh build got
  cheaper** — every round that shrinks the floor shrinks the replay's reason to exist,
  which strengthens (INC.19)'s refusal of it as a default path.
  **REFUSED**: `checkModuleAugmentationReexportDuplicates` /
  `checkCjsExportAugmentationConflict` (their emitter adds a row on the augmentation's
  TARGET, so a partition holding only the target loses it — rows 0.15 and 0.00 ms, the
  refusal is free); a name pre-gate for `checkModulePreserve4Pin`; and routing the three
  raw `.contains` gates through `srcHas`, which would **COST ~17.8 ms to build 78 filters
  to save three ~2 ms scans** now that no pass builds one.

- [x] **(INC.22) REFUSED 2026-08-24, WITH THE SHARPEST MEASUREMENT THE ARC HAS OF THIS
  ROW — AND THE REFUSAL RE-AIMS THE DIRECTION.** `init:buildFileLocalTypeMaps` is
  **69.16 ms of a 90.15 ms floor pass table (77%)**, and partition-scoping it would take
  the floor **131 -> 57 ms**, the narrowed-query median **166 -> 116**, and the ratio
  **29.86x -> 42.61x**. The axis is new — (INC.10)/(INC.11) deferred PHASES, this varies
  **WHICH FILES** through the INV.6(6d) partition view, so a full build is unchanged BY
  CONSTRUCTION — **and the claim was verified in the BINARY**: a per-arm DIGEST over
  381,666 captured types and 360,152 definitions is IDENTICAL across arms, with
  `FltmDefer.lazyBuilds == 0` on every unpartitioned build as the corroborating count.
  **THE QUEUE'S PREMISE HAD EXPIRED**: (INC.11)'s "137 divergent spans" for the
  `TypeAlias`-only arm re-measures as **5 / 3 of 76** — byte-identical to baseline —
  closed by (INC.11)'s own fix and the (INC.5)/(INC.16)/(INC.19)-(21) work. So no
  `aliasDisplayMap` re-key was needed, and none was attempted.
  **WHAT REFUSES IT IS THE MEMBER CHANNEL, NOT DISPLAY**: `capture-channel`'s `moreAny`
  goes **168 -> 229**, i.e. **+61 member types collapsing to `any`** under a narrowed
  build — a WRONG ANSWER, the same class (INC.11) refused the full deferral over — and
  `partition-gate`'s SENSITIVITY arm diverges on a DIAGNOSTIC. Keeping the cheap
  `TypeAlias` phase program-wide (6.68 ms) solves the NAMING half completely (2,275
  divergent spans -> +1 row) and does nothing for the member half.
  **THE TRANSFERABLE RESULT**: the obstruction is not the pass's COST but that the pass
  IS the program's FIRST-TOUCH ORDER, and that order buys BOTH an alias name (cheap,
  fixable) AND member resolutions (not fixable without the expensive phase). See (INC.23).

- [x] **(INC.23) THE CENSUS IS DONE 2026-08-24, AND IT SHRANK (INC.22)'s REFUSAL BY TWO
  ORDERS OF MAGNITUDE.** "+61 member types collapse to `any`" is, classified per ELEMENT,
  **78 rows carrying exactly ONE member name — `[Symbol.unscopables]`** (the lib's
  `{ [K in keyof any[]]?: boolean }`) in 14 files. Everything else (1,379 rows, 196 names)
  is the (INC.11)(a) alias-display family, which collapses to **+1 row for 6.68 ms**.
  **ROUND 778's WRITE GATE IS REFUTED AS THE MECHANISM**: the writer hook reads
  `ambient=empty persisted=true` in BOTH arms and differs only in `truncated` — under a
  partition the first ask arrives from INSIDE the member-table resolution the mapped
  type's `keyof` needs, `resolveStructuredTypeMembersCore` returns leaving `properties`
  null, and the type degrades. **The whole narrowed compile has ONE truncated resolution
  of 822; a full build has 0 of 21,315.**
  **THE OBVIOUS FIX IS REFUTED WITH A POSITIVE CONTROL**: refusing to persist a truncated
  resolution changes nothing sweep-wide (same 78 rows, byte-identical digest) while the
  control shows the arm is live (`persisted=true resolves=1` -> `persisted=false
  resolves=2`) — the re-resolution re-enters the same guard.
  **AND `narrowRendersMoreAny` IS A SUBSTRING HEURISTIC THAT OVER-REPORTS**: **zero** of
  the shipped baseline's 168 "moreAny" rows loses a member type. A nonzero value is a
  LEAD; a zero still means what it always did.
  **(INC.22)'s THIRD OBSTRUCTION IS RETIRED**: the PURE partition-scoped arm is EQUIVALENT
  on both `partition-gate` arms — the "DIVERGED 1 file" belonged to its MIXED
  `TypeAlias`-program-wide configuration.

- [x] **(INC.24) LANDED 2026-08-24 — both capture runners fold their whole answer set into
  ONE number per arm, ordered by span key so it is a property of the ANSWERS and not of
  `HashMap` iteration.** From a clean tree it reproduces (INC.22)'s recorded
  `full=-3718897727265589316` over 381,666 types + 360,152 definitions exactly — round
  776's rebuild-the-baseline control, satisfied on an instrument. `Checker.fileLocal-
  TypeMapSnapshot` came with it, plus 4 pins.

- [ ] **(INC.25) ONE LIB MEMBER NOW GATES 62-65 ms — `keyof` OVER A TYPE WHOSE MEMBER
  TABLE IS IN FLIGHT MUST ANSWER FROM THE DECLARATIONS.** (INC.23) reduced (INC.22)'s
  refusal to a single defect: under a partition the first ask for `[Symbol.unscopables]`
  arrives from INSIDE the member-table resolution its own `keyof any[]` needs;
  `resolveStructuredTypeMembersCore` returns silently with `properties` null and the
  mapped type degrades to `any`, in 78 rows across 14 files. **ONE truncated resolution
  out of 822 in a narrowed compile; ZERO of 21,315 in a full one.**
  **THE CACHE IS NOT THE LEVER AND THAT IS MEASURED** — refusing to persist the truncated
  answer re-resolves and re-enters the same guard, answering `any` again (positive control
  recorded). **The lever is the CYCLE HANDLING.**
  **THE PRIZE, ALREADY MEASURED BY (INC.22) AND UNCONTESTED**: floor **131 -> 57 ms**,
  narrowed-query median **166 -> 116**, ratio **29.86x -> 42.61x**, full build unchanged
  BY CONSTRUCTION and verified by an identical whole-answer digest.
  **THE HAZARD IS THE BLAST RADIUS**: this is a member-resolution change, so the corpus is
  the gate as much as the sweeps, and a cycle guard that answers instead of degrading can
  turn a bounded recursion into an unbounded one — (INC.19) hit exactly that shape and its
  tell was a spurious **TS2589 at (0,0)**, `reportCheckerStackOverflow`, never a depth
  diagnostic. Build the repro on the LIB shape first (`@useRealLibs`, round 725 — without
  it the utility type degrades to `any` and the pin is vacuous).

- [x] **(INC.4) LANDED 2026-08-22 — `ProjectCompiler.build` now refuses it, 4 pins
  including the DEFAULT-`noEmit` case and both negative controls. ORIGINAL ENTRY:
  `recheckOnly` + EMIT IS UNSOUND AND `ProjectCompiler.build` DOES NOT REFUSE IT.** The Transformer queries the checker it is handed (`isReferencedAliasDeclaration`
  and friends), so under a partition it asks a checker that walked a SUBSET and elision
  goes wrong. Every driver gates incremental on `--noEmit` and `Project` always passes
  `noEmit = true`, so nothing today is wrong — but the parameter is public and the next
  caller will not know. `require(noEmit || recheckOnly == null)`, with the message naming
  the caller's mistake, exactly as `compileParsed` already does for `checkedSink`.

- [x] **(INC.5) LANDED 2026-08-22 — 45 divergent spans -> 9, and the 40 wrong-direction
  rows -> 4. See the session note; what is left is (INC.6). ORIGINAL ENTRY: WHAT A HOVER REPORTS DEPENDS ON PROGRAM ORDER — A PRE-EXISTING DEFECT
  (INC.2) MADE VISIBLE, AND IT IS NOT ABOUT PARTITIONS.** `symbolTypes` persists the first
  resolution of a symbol's type, and resolving a type reference inside an anonymous object
  type literal answers differently depending on which file asks first: in the same program,
  the whole-program build renders `(key: K, valueInNewMap: U) => any` for a span where a
  narrowed build renders `=> T`, and elsewhere the reverse. **Neither arm is canonically
  right; they are two draws from an order-dependent cache.** Today the order is fixed by
  the crawl (`ProjectCompiler.walk` sorts, and CLAUDE.md records that three orders of the
  same 78 files move `typeNode.bypassed` ~1% with every diagnostic bit-identical), so a
  user sees ONE answer consistently — which is why this has never been reported. It is
  still a wrong answer where the collapse is to `any`.
  **THE INSTRUMENT ALREADY EXISTS**: `scripts/capture-equivalence.sh` reads 45 divergent
  spans out of 381,666 in one run, and the full-vs-narrow pair is a differential ORACLE
  for it — no baseline needed, because the two arms must agree. Start there rather than by
  reading the resolver: the census names the 11 files and the exact spans.
  **THE SEAM IS NAMED BY THE DIVERGENT ROWS THEMSELVES, AND IT IS NOT NAME RESOLUTION.**
  One row loses a KEYWORD type (`{ fileName: string }` -> `{ fileName: any }`) and another
  a mapped-type modifier (`Required<{ reportInferenceFallback(node: Node): void }>` ->
  `Required<{ reportInferenceFallback?: any | undefined }>`). A name resolving in the wrong
  file's scope cannot lose `string` or `-?`; an UNRESOLVED MEMBER TABLE can. So this is
  round 833's hazard one layer up — *a target type's member table is LAZY, so a verdict
  depends on whether an earlier line in the file happened to resolve that type* — with
  `typeToString` as the reader and A DIFFERENT FILE'S CHECK as the "earlier line" that a
  whole-program build always happens to perform.
  **THE FIX IS THEREFORE SMALL AND SURGICAL, AND IT BELONGS IN THE CAPTURE PATH ONLY:**
  force `resolveStructuredTypeMembers` on the type about to be rendered (and on the member
  types it recurses into) before `typeToString`. Doing it inside `typeToString` itself
  would change DIAGNOSTIC MESSAGES program-wide and put ~13k corpus baselines in play for
  a language-service defect; doing it where the capture records its display string cannot
  move a single diagnostic, which is what makes it landable in one round.
  Then re-run `scripts/capture-equivalence.sh`: expect the 40 `any` rows to clear and the
  5 REVERSED rows (where the full build is the one showing `any`) to need their own
  diagnosis — they are the same order-dependence seen from the other side.
  Closing it also unblocks (INC.2)'s 3.73x.


- [x] **(LIB.1) knip MEASURED 2026-08-22 — 2,634 xtsc errors against tsgo's 23, and 94.1%
  of them are ONE missing feature.** `webpro-nl/knip` at `main`, `packages/knip`: **498
  files, 35,663 lines**, `moduleResolution: nodenext`, `"type": "module"`,
  `verbatimModuleSyntax`, every relative import written with an explicit `.ts` extension.
  Front end: xtsc `--noEmit --listAll` reports **2,634 in 7,131 ms**; tsgo 7.0.2 reports
  **23, all environmental** (no `@types/picomatch`, `webpack`, `@jest/types`,
  `codeclimate-types`) — knip itself is clean under the oracle.
  **TWO CODES ARE 2,478 OF THE 2,634 (94.1%): TS1295×1,959 and TS1287×519**, both saying
  the file is CommonJS. **xtsc does not derive a file's module format from the nearest
  `package.json` `"type"`,** so under nodenext every knip file is classified CommonJS and
  every import and export trips the `verbatimModuleSyntax` guard. The attribution was
  CONFIRMED, not inferred: deleting that one option from the tsconfig reads
  **2,634 -> 156**, and tsgo re-run on the same config still reads 23. Queued as (CHK.29).
  **THE RESIDUAL IS 156 = 0.31 FP/file, BETTER THAN THE 0.9/file `docs/kir-library-readiness.md`
  RECORDS FOR `yaml`, AND IT IS THAT PAGE'S TWO KNOWN FAMILIES**: TS7006×89 (57% — an
  object-literal METHOD's parameters are not contextually typed from the annotated return
  type; (CHK.30)), TS2339×23 (union member access where narrowing did not apply), then
  TS2322×16, TS2552×9, TS18048×7, TS2353×3, TS2769/TS2349/TS2304×2, TS2591/TS2345/TS18047×1.
  **THE OVERLAP WITH tsgo's SET IS ZERO IN BOTH DIRECTIONS — so there are also 23 FALSE
  NEGATIVES**, including two genuine TS2322 and a TS2722 in `src/util/glob-core.ts` that
  tsgo reports and we do not. A residual FP count is not a conformance number until the
  misses are counted too.
  **WHAT WORKED AND IS WORTH RECORDING: module resolution.** All **1,921** relative
  specifiers carry an explicit `.ts` extension (`allowImportingTsExtensions` +
  `rewriteRelativeImportExtensions`) and every one resolved — the type errors name real
  imported types (`Configuration`, `TsConfigJson`, `Plugin`), so (KIR.EMIT.1)'s work holds
  on an unfamiliar codebase.
  **BACKEND: the project probe never reaches the lowering** (it will not emit a program the
  checker rejected), so it was measured on ONE self-contained file —
  `src/util/graph-sequencer.ts`, 131 lines, no imports: `typeErrors=0`, then
  `refused: graph-sequencer.ts:22:74 a spread element is out of the spike subset`.
  Censused against the 17 refusal messages in `lower/`: **destructuring parameter 255 files
  (51%), spread 163 (33%), destructuring declaration 121 (24%), `async`/generators 112
  (22%), computed property name 63 (12%), optional element access 29 (5%)** — the union is
  **237 of 498 files (48%)** before counting anything downstream. `async` is decisive on its
  own: knip's entry point IS `export const main = async (options) => …`.
  **BUT knip IS UNREACHABLE FOR REASONS THAT ARE NOT THE LOWERING, AND THAT IS THE FINDING
  THAT MATTERS FOR PLANNING.** It depends on **two native Rust N-API binaries** —
  `oxc-parser` (32 import sites) and `oxc-resolver` — which are not TypeScript and cannot be
  lowered from; on **10 `node:` builtins** (`fs`×21, `fs/promises`×5, `util`, `path`,
  `module`, `crypto`, `url`, `process`, `perf_hooks`, `child_process`) against a
  `KirIntrinsics.libraryClass` table of exactly **six** entries (`Array`, `Map`, `Set`,
  `RegExp`, `Date`, `Error`); and on `createRequire`×9 plus `jiti`, i.e. evaluating config
  files at run time. **A program whose job is to read the filesystem and parse source with a
  native parser needs a Node-API layer on the JVM, which is a bigger project than the
  lowering.** So knip is the right instrument for the FRONT END and the wrong driver for the
  backend ladder — see (LIB.2).
  **REPRODUCTION** (both halves, ~10 s):
  `java -cp <core-classes>:$(bash scripts/lib/dep-classpath.sh --print) com.xemantic.typescript.compiler.MainKt --noEmit --listAll <knip>/packages/knip`
  and `KIR_PROBE_FILE=<knip>/packages/knip/src/util/graph-sequencer.ts ./gradlew :xemantic-typescript-compiler-kir:jvmTest --tests '*LibraryProbe*' --rerun -i`.
  Oracle: `npm i typescript@7` in a side root, then `tsc --noEmit -p <knip>/packages/knip`.

- [ ] **(CHK.29) A FILE'S MODULE FORMAT IS NOT DERIVED FROM THE NEAREST `package.json`
  `"type"` — 2,478 FALSE POSITIVES ON ONE LIBRARY, AND NOTHING IN THE CORPUS CAN SEE IT.**
  Under `module`/`moduleResolution: nodenext` (and `node16`), tsc decides whether a `.ts`
  file is an ES module or CommonJS by walking up to the nearest `package.json` and reading
  its `"type"` field. We do not, so a `"type": "module"` package is classified CommonJS and
  every ESM import/export in it trips `verbatimModuleSyntax`: **TS1295×1,959 + TS1287×519**
  on knip, measured, i.e. 94.1% of that library's error count from one absent lookup
  ((LIB.1)). **THE CORPUS IS STRUCTURALLY BLIND**: tsc's own sources are not
  `"type": "module"`, `usesUnsupportedOption` never skipped these fixtures because the
  option is not in the removed list, and the 8 dashboard profiles all inherit tsc's layout —
  so a green corpus, a green `cost_gate.py` and an `added=0 removed=0` grid are the EXPECTED
  answers here and none of them is evidence. **The pin has to be a project fixture with a
  `package.json` beside the sources** (`-project`'s `ProjectCompiler` path, not `diagnose()`,
  which has no package.json and no directory), asserting both directions: `"type": "module"`
  is silent, and its ABSENCE under nodenext still reports TS1295. Check what else reads the
  format while you are there — `impliedNodeFormat` also decides `esModuleInterop` behaviour,
  the `.mts`/`.cts` extension overrides, and whether a `require()` of an ES module is an
  error, so the fix is one lookup with several consumers.

- [ ] **(CHK.30) AN OBJECT-LITERAL METHOD'S PARAMETERS ARE NOT CONTEXTUALLY TYPED — 89
  TS7006, 57% OF THE RESIDUAL ON knip.** The shape is a shorthand method in a literal
  returned at an annotated type:
  ```ts
  export function createExecaVisitor(ctx: PluginVisitorContext): PluginVisitorObject {
    return { TaggedTemplateExpression(node) { … } }   // TS7006: 'node' implicitly has an 'any' type
  }
  ```
  tsgo is silent. This is the METHOD-member half of the family
  `docs/kir-library-readiness.md` calls "contextual typing does not reach into literal
  members" — the property half of which (a `readonly` literal initializer) landed as
  (WIDEN.1)(b). Expect it to share machinery with `applyContextualParameterTypes`, and note
  CLAUDE.md's standing trap: an un-annotated parameter is invisible to the body walkers
  unless the type reaches `populateParameterLocalTypes`, so a fix written at
  `getTypeOfArrowFunction` measures nothing. The probe that discriminates must FAIL if the
  change is inert — make the parameter's contextual type wrong-typed at a use site and
  require the error to appear.

- [x] **(LIB.2) ANSWERED 2026-08-22 BY (LIB.3)'s SCREEN — and the screen added a second
  criterion the entry did not predict: the library closest to COMPILING and the library best
  for BENCHMARKING are different ones. ORIGINAL ENTRY: THE NEXT LIBRARY MUST BE PICKED BY
  WHAT IT *IMPORTS*, NOT BY ITS SIZE —
  knip cost a session to learn that.** (LIB.1)'s method is right and cheap (two commands,
  ~10 s) but it was pointed at a library the backend can never reach, because the
  disqualifier is not a language construct: **native N-API dependencies and `node:` builtins
  have nothing to lower TO.** Before adopting a candidate, census its non-relative imports
  first — `grep -rhoE "from '[^.'][^']*'"` over `src` answers in one second — and refuse
  anything importing a `.node` binary or a `node:` builtin outside a table we intend to
  write. `yaml` (76 files, no dependencies) is still the right second conformance corpus for
  the FRONT end, and `docs/kir-library-readiness.md` records it moving 80 -> 24 purely from
  defects other libraries exposed. For the BACKEND ladder the candidate wants to be pure
  computation over data — a parser, a formatter, a codec — which is exactly why `mitt` and
  `smol-toml` worked.

- [x] **(LIB.3) SIX CANDIDATE CLI LIBRARIES SCREENED AND THEIR ERRORS ROOT-CAUSED —
  2026-08-22. 126 false positives over four libraries, and FIVE families carry 67 of them.**
  This is (LIB.2)'s screen, executed. All six are TS-source with a CLI; the import census
  disqualified `sql-formatter` (imports `nearley` inside `src`) before any compiler ran.
  Measured with `@types/node` present on both sides, each library's OWN tsconfig (marked's
  minus `verbatimModuleSyntax`, since (CHK.29) already owns that), diffed against tsgo 7.0.2
  per `(file, line, code)`:

  | library | files | lines | deps | tsgo | xtsc | ours-only | refused-construct files |
  |---|---|---|---|---|---|---|---|
  | **cronstrue** | 52 | 8,812 | none | **0** | **0** | **0** | **2 (3%)** |
  | marked | 13 | 3,706 | none | 0 | 15 | 15 | 10 (76%) |
  | jsonrepair | 10 | 2,746 | none | 1 | 16 | 16 | 9 (90%) |
  | fflate | 3 | 3,904 | none | 2 | 17 | 17 | 3 (100%) |
  | yaml | 78 | 10,878 | none | 0 | 78 | 78 | — |

  **THE OURS-ONLY HISTOGRAM (126): TS9008×19, TS2322×14, TS2345×13, TS9023×11, TS2391×9,
  TS2554×8, TS2339×7, TS2591×6, TS2683×4, TS6196×2, TS2366×2, then twelve codes at 1.**
  The five root-caused families are (CHK.31)-(CHK.35) below, in the order their blast radius
  justifies. **THE TAIL IS NOT ROOT-CAUSED AND MUST NOT BE QUOTED AS IF IT WERE**: ~59 rows
  remain, led by TS2322×14 (of which SIX are one shape, `SourceToken | undefined` against
  `SourceToken | null` in `yaml/compose/resolve-props.ts` — an excess `undefined` we add and
  tsgo does not) and TS2339×7. Captures for every row are reproducible in ~10 s per library
  by the (LIB.1) commands.
  **THE RANKING LESSON, WHICH IS NOT THE ONE (LIB.2) PREDICTED: the library closest to
  COMPILING and the library best for BENCHMARKING are different libraries.** `cronstrue` is
  the only one the checker already passes and the only one whose lowering runs — but each of
  its calls is small work, so it benchmarks as a loop over many expressions rather than as one
  heavy invocation. `marked` (markdown -> HTML over a large document) is the workload worth
  publishing a number for, and is 15 checker errors plus a 76%-of-files backend gap away.
  `fflate` would be the best number of all — DEFLATE is tight numeric loops, where a JVM
  should beat Node outright — and is **structurally blocked**: 183 typed-array uses
  (`Uint8Array`×167) against a runtime with none, plus 14 `Worker` references. Do not start
  there; revisit after typed arrays exist.

- [ ] **(LIB.4) `cronstrue` IS THE NEXT BACKEND DRIVER — 0 CHECKER ERRORS ON 8,812 LINES AND
  FIVE NAMED RUNGS TO A RUNNING PROGRAM.** The probe reads `typeErrors=0` over all 52 files
  (it AGREES with tsgo exactly, the only library in the screen that does) and the lowering then
  runs to a first refusal. Walking it by patching a throwaway copy and re-probing gives the
  whole ladder, in order:
  1. `rest parameters are out of the spike subset` — `stringUtilities.ts:10`, **2 sites**
  2. `destructuring in for…of` — `expressionDescriptor.ts:734`, **1 site**
  3. `` `var` is out of the spike subset — its function scoping is not modelled `` — **18 sites in 4 files**
  4. `cannot lower this binary operator` (`??`) — **2 sites**
  5. `cannot coerce Function1 to String` — `String.replace(re, fn)`, i.e. the replacer-CALLBACK
     overload, a RUNTIME gap rather than a language one
  **The count stayed flat as the rungs were peeled — it is not opening into a tail**, which is
  what makes this a bounded piece of work rather than an open-ended one. It is 8x `smol-toml`,
  zero dependencies, zero non-relative imports, and a real CLI (`cronstrue "*/5 * * * *"`), so
  landing it extends `scripts/kir-bench.sh` with a third library and a third workload shape.
  **Rung 3 is the one to price first**: `var`'s function scoping is a real semantic difference,
  not a syntax rewrite, and 18 sites is enough that refusing it keeps blocking libraries.

- [ ] **(CHK.31) `// @ts-ignore` AND `// @ts-expect-error` DO NOT SUPPRESS ANYTHING — MEASURED
  IN BOTH DIRECTIONS, AND THIS IS THE HIGHEST-BLAST-RADIUS ITEM IN THE SCREEN.** A four-file
  repro settles it: `// @ts-ignore` above a TS2322 leaves the TS2322 emitted, `// @ts-expect-error`
  likewise, and an `@ts-expect-error` above a line with NO error fails to produce tsgo's
  **TS2578 `Unused '@ts-expect-error' directive`** — so we are wrong in both directions at once.
  On `fflate` this is **all 9 TS2391 rows** (`Function implementation is missing`), and the
  correspondence is exact: `src/index.ts` contains exactly 9 `@ts-ignore` comments, one above
  each declaration-only class member the library deliberately suppresses.
  **THE TRAP IS THAT IT LOOKS ALREADY DONE**: `CompilerOptions.kt:562` parses both spellings as
  comment directives, and `Checker.kt:16167` consults one for a narrow node/commonjs
  suppression, so a grep says the feature exists. It is not a general diagnostic filter.
  **What the fix needs, beyond the filter itself:** the directive attaches to the NEXT line, so
  it wants the leading-comment channel the parser already records (`NodeBase.leadingComments`)
  rather than a source scan; `@ts-expect-error` must additionally RECORD whether it suppressed
  anything and emit TS2578 when it did not; and a file-level `// @ts-nocheck` is a third
  spelling with **zero** hits in `commonMain` today. **Corpus risk is real and must be measured
  before landing**: any baseline whose fixture carries one of these directives currently records
  the UNSUPPRESSED diagnostics, so run the 8-profile grid and the corpus, and expect the
  `logicalParityDivergence` mechanism to be the wrong tool — a suppressed diagnostic is a
  MEANING change, not a form one.

- [ ] **(CHK.32) A PRIMITIVE SOURCE IS NOT RELATED TO A STRUCTURAL OBJECT TARGET THROUGH ITS
  APPARENT TYPE — 13 TS2345 ROWS, AND IT GENERALISES BEYOND `string`.** `jsonrepair` types its
  whole scanner against `interface Text { length: number; charAt(i): string; charCodeAt(i): number;
  substring(s, e?): string }` and passes a `string` to it; every one of its 7 TS2345 rows is that
  call. Minimal repro, both halves failing where tsgo is silent:
  ```ts
  declare function isWhitespace(text: Text, index: number): boolean
  export function viaString(s: string) { return isWhitespace(s, 0) }        // TS2345, tsgo silent
  declare function wantsToFixed(x: { toFixed(d?: number): string }): string
  export function viaNumber(n: number) { return wantsToFixed(n) }           // TS2345, tsgo silent
  ```
  The control in the same file — an object source against `{ length: number }` — passes, so the
  defect is specifically the PRIMITIVE side: relating `string`/`number` to an object type must
  go through `getApparentType` (the `String`/`Number` wrapper interface), which the relation is
  not consulting on this path. `getApparentType` already exists and CLAUDE.md records it as the
  way to reach a primitive's members, so this is a missing consult rather than missing
  machinery. Check the mirror direction while you are there (an apparent-typed source in a
  RETURN position, and `boolean`/`symbol`/`bigint`), and note the fix is in the RELATION, so
  the corpus is the gate.

- [ ] **(CHK.33) A DESTRUCTURING PARAMETER BREAKS ARITY, AND THE MESSAGE PROVES IT: `Expected
  1-0 arguments, but got 1` — 8 ROWS IN `marked`, ON A LIBRARY tsgo REPORTS ZERO ERRORS FOR.**
  `marked`'s renderer methods are all written `html({ text }: Tokens.HTML | Tokens.Tag):
  RendererOutput`, and every call `renderer.html(token)` is rejected. **This is round 921's
  documented hazard reaching a diagnostic for the first time**: CLAUDE.md already records that
  `getParameterSymbols` DROPS every binding-pattern parameter, so `Signature.parameters` is
  EMPTY while `minArgumentCount` still counts the pattern — which is exactly an inverted range
  of min 1, max 0, printed verbatim. **The inverted range is a free assertion**: no correct
  signature can have `minArgumentCount > parameters.size`, so `require` it where signatures are
  built and this class of defect stops being silent. Fixing arity may not be the whole item —
  the same drop shifts the positional zip of type annotations onto the surviving parameters
  (CLAUDE.md's `f({a}: O, b: string)` example types `b` as `O`), so pin BOTH the arity and the
  parameter TYPES, and prefer `sig.declaration`'s own list as the reference the way
  `typeCaptureSignatureParameters` already does.

- [ ] **(CHK.34) `isolatedDeclarations` OVER-REPORTS — 32 ROWS ON A LIBRARY THAT SHIPS WITH THE
  FLAG ON AND IS CLEAN UNDER tsgo.** `yaml` sets `"isolatedDeclarations": true` and tsgo finds
  **0** errors; we emit TS9008×19, TS9023×11, TS9007×1, TS9009×1. One member is identified:
  `nodes/YAMLMap.ts:232` is the IMPLEMENTATION signature of an overload set, which needs no
  return annotation under `isolatedDeclarations` because the overload signatures above it carry
  one — so the rule is being applied to a signature the flag exempts. TS9023
  (`Assigning properties to functions without declaring them`) fires 11 times at
  `visit.ts:108-109` and is unexamined. **Sequence this AFTER (CHK.31)-(CHK.33)**: it is the
  biggest row count in the screen and the narrowest trigger — it costs nothing on a project that
  does not set the flag, where the other four families cost every project. The 8 profiles do not
  set it either, so `cost_gate.py` and the grid are structurally blind here and `yaml` is the gate.

- [ ] **(CHK.35) A FUNCTION EXPRESSION ASSIGNED THROUGH AN INDEX SIGNATURE GETS NO CONTEXTUAL
  SIGNATURE — 5 ROWS, AND IT IS (CHK.30)'s SIBLING.** In `marked/Instance.ts:118`,
  `extensions.renderers[ext.name] = function(...args) { … ext.renderer.apply(this, args) … }`
  gives **TS7019** for `args` (rest parameter implicitly `any[]`) and **TS2683**×4 for `this`
  (implicitly `any`), where tsgo is silent — because the index signature's value type supplies
  both the parameter list and the `this` type, and we are not reaching it. (CHK.30) is the same
  failure one container over (an object-literal shorthand METHOD's parameters), so **check
  whether one contextual-signature path serves both before writing either** — if it does, the
  two items are one. Same standing trap applies: a contextual parameter type that does not reach
  `populateParameterLocalTypes` is invisible to the body walkers, so a probe must FAIL if the
  change is inert.

- [ ] **(KIR.LOWER.2) THE SAME ABSENT-DECLARATION TRAP MAY BE LIVE IN `ErasedTypes` — a LEAD, not a
  finding.** `ErasedTypes.mapObject` ends `if (declaration == null) return jsObjectType()`, which
  (KAPI.4) measured to be reached by a `Promise<string>` on the API side: a `Type.Reference`'s own
  symbol carries no declaration, so a named library type outside `libraryClass`'s table erases to a
  property BAG rather than being refused. On the lowering that is not a wrong TYPE but wrong CODE —
  a `.then` on it would read a bag slot — and it is untested because neither corpus library uses a
  Promise. Check whether the target-symbol fallback changes any erasure on the two libraries
  (`scripts/kir-bench.sh`'s equivalence gate is the instrument), and if it does not, add the
  refusal: a named type with no reachable declaration is one this backend does not know.

- [ ] **(KAPI.2) THE PLATFORM HALF: pin that the emitted JVM classes match the exported
  metadata.** `(KAPI.1)` declares a library's API as Kotlin metadata for `commonMain`; a
  `jvmMain` compilation links against the CLASSES the KIR backend emits, and nothing asserts
  the two agree on package, name and erased JVM signature. The failure is the worst-shaped
  one available — the consumer's common code type-checks and its platform code does not link
  — so the instrument is a pin that compiles a JVM consumer against the emitted classes and a
  common consumer against the klib FROM ONE EXPORT, and fails when either resolves something
  the other does not. Expect real divergences to fall out: the JVM lowering names a file's
  facade after the file (`MittKt`) where the metadata puts every declaration in one package,
  and module variables are reached through generated `name$get` accessors rather than as
  properties. `docs/kir-kotlin-metadata.md` §6 item 1.

- [x] **(KAPI.3) A RUNTIME METADATA KLIB — LANDED 2026-08-22, same session.** A SECOND metadata
  klib declares `JsObject` and `JsArray` under their real fully qualified names, is written by
  the same machinery and goes on the exported library's compile classpath — opt-in through
  `runtimeKlib =`, so the self-contained artifact stays available. Measured on the two real
  libraries: `mitt(all: JsObject?): JsObject` and **`parse(toml: String, options: JsObject?):
  JsObject`**, both pinned, and a consumer that reads `document.get("title")` compiles against
  the pair. **The gate is the load-bearing part**: a bag needs POSITIVE evidence — the
  lowering's own `isOwnStructuralDeclaration` (a structural kind declared in a program file
  that is not a `.d.ts`), an anonymous object type by construction, and nothing else — because
  a `Date` is a `JsDate` at run time and typing one as a bag offers members the value does not
  have. An INTERSECTION is one bag only when EVERY member is positively one, which is stricter
  than `ErasedTypes.mapIntersection` and forced: with no library-type table, `Date` and an
  unmappable constraint give the same answer, so the permissive reading types `Date & Tag` as a
  bag (a pin holds both directions). The facade is stated by hand — Java reflection cannot see
  nullability and `kotlin-reflect` here is older than the runtime's metadata — so the drift is
  CAUGHT rather than prevented: `KirRuntimeApiTest` reflects over the real classes, with two
  negative controls proving the check can fail. What is left is the library-type table (`Map`,
  `Set`, `Date`, `RegExp`), now (KAPI.4). ORIGINAL ENTRY:**
  Measured today: `smol-toml` exports `parse(toml: String, options: Any?): Any?`, which is the
  difference between "a TOML parser returns something" and "a TOML parser returns something you
  can read". Arrays and object types erase to `Any?` for one reason only — `JsArray`/`JsObject`
  are JVM Kotlin with no COMMON metadata artifact — so the work is to produce one for the
  runtime's public surface and put it on the export's classpath (the parameter already exists,
  `compileMetadataKlib(..., classpath)`). The trap to design against is drift: a hand-written
  common facade of a JVM class is a second copy, so whatever produces it needs a pin that
  reflects over the real class and fails when a member disagrees — `scripts/kir_native_runtime.py`
  is the precedent for deriving one runtime from the other rather than forking it.

- [x] **(KAPI.4) A LIBRARY-TYPE TABLE — LANDED 2026-08-22, same session.** `KirRuntimeApi.libraryType`
  mirrors `KirIntrinsics.libraryClass` entry for entry, so `Map`/`ReadonlyMap`/`WeakMap`,
  `Set`/`ReadonlySet`/`WeakSet`, `RegExp`, `Date` and `Error` name the same runtime class on an
  exported signature as in the compiled program, and the facade declares all five beside
  `JsObject`/`JsArray` (the drift pin covers them, and now checks CONSTRUCTORS as well as members,
  with a third negative control). Measured: `mitt`'s parameter is `JsMap?` — its `EventHandlerMap`
  is an alias of a `Map` — where a bag would have been less precise than what the program holds.
  **It also found the gate's own defect: an ABSENT DECLARATION IS NOT EVIDENCE OF AN ANONYMOUS
  SHAPE.** A `Promise<string>` reached the object mapping with no declaration to walk and read as a
  property bag; two rules fix it and both are pins now — a `Type.Reference`'s own symbol carries no
  declaration where its TARGET's does (which is how `Emitter<Events>` is recognised as the
  program's own interface), and a type with a NAME but no reachable declaration is a library type
  this backend does not know. ORIGINAL ENTRY: `Map`, `Set`, `Date`, `RegExp`
  and `Promise` are runtime classes with no entry on the exported API, so they are `Any?` where
  `JsObject`/`JsArray` are now real — and, worse, they are what makes (KAPI.3)'s intersection
  rule demand positive evidence rather than reading an unmappable member as a constraint.
  `ErasedTypes` already keys such a table BY NAME (`libraryType`), which is the shape to copy;
  the declarations go in `KirRuntimeApi`, where the drift pin already covers whatever is added.


**WORK ORDER NOTE (restored 2026-08-14, round 903).** This section had been ARCHIVED out of the file
during a trim, and nothing noticed for ~15 rounds because rounds 886-902 were self-directing: each
session note named its own successor. **Round 902 ended with a CLOSURE and named none, so round 903
opened with no pool at all** and had to rebuild one by surveying `docs/perf/`. That is the failure
this section exists to prevent. **A round that refuses a candidate must leave at least one named
successor here, with its price and its next instrument** — a refusal is a successful round only if
the arc can continue from it.

**THE LIVE ARC IS (API.\*), ON OWNER DIRECTIVE (2026-08-17, round 909): DELIVER THE PROJECT AND
LANGUAGESERVICE EMBEDDING APIs.** It takes precedence over the (WARM.\*)/(SPINE.\*) perf items below,
which round 908 closed out anyway — the checker-side pool is empty. Shape decided by the owner: a
**Kotlin embedding API first** (LSP / tsserver protocol layered later, not now), in the new
`xemantic-typescript-compiler-project` module. The perf items stay below as the record; (ART.1) /
(ART.2) remain the only open perf work and (ART.1) has been corrected.

**TOP OF QUEUE ON OWNER DIRECTIVE (2026-08-21): (BENCH.1) below runs before the (API.\*) arc
resumes.**

- [x] **(KIR.PERF.2) THE REGULAR-EXPRESSION ENGINE — LANDED 2026-08-21, and it measured
  **−27.5%** of the toml parse rather than the −18% predicted (47.05 -> 34.10 us/parse,
  2.08x Node -> **1.52x**), with mitt flat at 61.25 and both Node arms flat. Per pattern
  against `java.util.regex`: **16.7x / 13.0x / 3.0x / 3.2x**. It beat its own prediction
  because two smaller members came with it — `replace(/_/g,'')` on a LITERAL path, and
  `split` no longer building a fresh `Regex(source)` per call (which also silently ignored
  the expression's flags). **It also found a divergence in the OTHER engine**: Java's `$`
  matches before a final line terminator where JavaScript's does not, so
  `/^\d+$/.test("12\n")` answered `true` here — `jsEndAnchorTranslated` closes it. Carried
  verbatim to Kotlin/Native, where it measured **−22.5%**. `KirRegexEngineTest`, 20 pins.
  ORIGINAL ENTRY:** `java.util.regex` costs **9.5 us per
  document** on `smol-toml` — 20% of the 47.05 us JVM parse, matching § 2's independent
  JFR reading, and **42% of Node's ENTIRE parse budget**. The engine gap alone (9.5 vs
  V8's 3.0 us) is **27% of the whole JVM-vs-Node difference**. It is the pattern SHAPE,
  not the call count: `^\d+$` is 14.7 ns and `^\d(?:_?\d)*$` is 94 ns, because a
  repetition whose body is not a single deterministic character compiles to Java's
  backtracking `Loop` node — and TOML's digit separators are literally `(_?\d)*`. A
  hand-written scan of the same two patterns, gated to agree on the document population
  plus fourteen adversarial inputs, is **9.4 ns and 6.7 ns — 25x and 12x**.

  **TWO CHEAP FIXES ARE ALREADY REFUSED, measured, before being built**: rewriting the
  groups as `(?: )` for `test` (legal, since `test` cannot observe groups) buys **0.6%**,
  and `matches()` in place of `find()` buys nothing.

  **WHAT TO BUILD:** not a per-pattern special case but a matcher for the REGULAR subset
  these patterns live in — no backreferences, no lookaround — compiled once per
  `(source, flags)` beside the existing `Pattern` cache, with `java.util.regex` kept LIVE
  as the differential oracle (the round-792 shape: never a legality gate). Worth
  **−8.6 us = −18%**, taking `smol-toml` from 2.08x Node to **~1.70x**.
  **AND IT COMPOUNDS ON NATIVE**, where `kotlin.text.Regex` is 5.2x `java.util.regex` and
  35x V8, i.e. ~30% of the native parse. `docs/perf/kir-backend-levers.md` § 5.

- [x] **(KIR.NATIVE.1) ALL THREE SUB-ITEMS LANDED 2026-08-21** — (a) the nominal half's
  first slice (see (KIR.PERF.1)), (b) the regex engine, carried to native verbatim and worth
  **−22.5%** there, and (c) the native arm inside `kir-bench.sh`'s own equivalence gate.
  **(a) WAS then verified on Native rather than assumed**: `mitt` compiles, links and runs
  with the shape classes and the right sink — the plugin reports `checked 2 file(s)` and
  konanc accepts the generated classes, so CLAUDE.md's "Native's IR validator REJECTS the
  public fields the JVM backend accepts" does not bite this shape — and it measures **348
  ns/emit against 354.75, i.e. FLAT**. That is the opposite of §6's expectation and the
  mechanism says why: the JVM's −10.7% comes from C2 inlining the override at a monomorphic
  call site and folding the constant name away, and Kotlin/Native has no JIT to do either,
  so the shape's `get` stays a real virtual call. **The nominal half pays on Native only
  once the property access is a direct field read** — the next slice — rather than a
  virtual `get` over fields. ORIGINAL ENTRY: THE NATIVE BACKEND EXISTS AND IS 4-7x THE JVM — AND THE REASON IS
  BOXING, WHICH MAKES (KIR.PERF.1) A CORRECTNESS-OF-DIRECTION QUESTION RATHER THAN A JVM
  OPTIMISATION.** Both libraries now compile to `-opt` Kotlin/Native binaries through the
  same `KirProgramLowering` (`scripts/kir-native.sh`), agreeing with the other three arms
  on the sink: **mitt 353.25 ns/emit against the JVM's 60.75, toml 163.30 us/parse against
  45.50**. Priced primitive by primitive from one source on both backends, every dynamic
  operation is 4-29x: `jsAdd` **0.95 -> 28.05 ns**, `jsCall1` 0.86 -> 12.93, boxing one
  `Double` 0.86 -> 8.61. **On the JVM C2 scalar-replaces most of those boxes; Kotlin/Native
  has no escape analysis, so every `Any?` position is a real allocation.** The open work,
  in order: (a) the nominal half, which is worth far more here than on the JVM; (b) the
  regex engine, (KIR.PERF.2); (c) a native arm in `kir-bench.sh`'s equivalence gate, which
  this round ran by hand — **(b) and (c) are DONE as of 2026-08-21**: the regex engine
  landed and is carried to native verbatim (**−22.5%**, 163.30 -> 126.55 us/parse, 7.26x
  -> **5.70x** Node, with mitt flat at 354.75 as the control), and `kir-bench.sh` now
  carries the native arm itself under `KIR_BENCH_NATIVE=1` — built by the same
  `kirNativeCompile` task, gated on the same `sink=` and timed in the same interleave.
  **(a), the nominal half, is what is left, and the native numbers are its case**:
  §6's per-primitive table says every dynamic position is a real allocation here, and
  the 36.75 us the regex engine removed leaves boxing as the whole remainder.
  Gradle wiring is DONE (owner-approved):
  `:xemantic-typescript-compiler-kir:kirNativeCompile`, with `scripts/kir-native.sh`
  a wrapper over it.
  Traps that cost the session and are recorded so they are not re-derived:
  `docs/perf/kir-backend-levers.md` § 6.

- [x] **(KIR.PERF.1) THE NOMINAL HALF — FIRST SLICE LANDED 2026-08-21, and `mitt` is
  **−10.7%** (61.00 -> **54.50 ns/emit**, 1.35x -> **1.54x FASTER** than Node), with ranges
  DISJOINT ([209..219] against [243..249]) and both Node arms flat. An object LITERAL whose
  property names are statically known now becomes a generated JVM class with one real field
  per property, EXTENDING `JsObject` — so the erasure is untouched, a shape instance IS a
  bag, and structural assignability never enters into it. That is what made the slice
  affordable where `docs/kir-structural-typing.md` §7's 12x price is for changing what an
  object type erases to. `smol-toml` is FLAT: its ten shapes fire, but `JsObject.get` had to
  become virtual and the parser builds its tables dynamically, so the gain on the scanner
  context and the loss on the tables cancel. **What is left is one further slice and one
  hard problem**: a local whose initializer IS a shape construction can keep the shape as
  its IR type and read the field DIRECTLY (the lowering already emits that for a declared
  class), and a shape arriving as a PARAMETER — which is how `smol-toml` passes its context
  — needs the whole-program inference §7 describes. `docs/perf/kir-backend-levers.md` §2b.
  ORIGINAL ENTRY, whose container half is closed by *four* refutations:** A per-owner leaf
  census of the toml JVM arm charges **47-52%** to the property bag — and, censused by
  OPERATION this round, that is **3,333 bag operations per parse** (2,555 `get`, 737 `set`
  of which **63.5% OVERWRITE**, 41 `has`, over 109 bags minted) at **~4.9 ns each**, which
  is exactly what a `String`-keyed `LinkedHashMap` probe on a cached hash costs. The row
  SURVIVES round 896's division test; its neighbour did not — `jsTruthyBooleanOrNull`
  reads 7.2-7.4% of samples over 298 calls per parse, i.e. **8.2 ns for
  `value != null && value`**, impossible by ~20x, so it was refused without a build.

  **THE READ SIDE IS UNIMODAL, WHICH IS WHAT DECIDED IT.** §2 measured the population as
  bimodal; that is true of ALLOCATION and false of READS, and a lookup cost is weighted by
  reads. **93.6% of every property read lands on a bag of exactly THREE keys** (99.1% on
  four or fewer; the 5-18 tail is 0.9%), and the names are the emitted string LITERALS,
  i.e. interned. That is the most favourable population an identity-compared scan could be
  handed — so the cleanest possible scan was built and MEASURED:

  | design | result |
  |---|---|
  | parallel arrays, promoted by SIZE | +21%, refused |
  | parallel arrays, promoted at the first UNDECLARED key | +31%, refused |
  | **identity scan, NO promotion, single-shaped `get`, everything else cold** | **no effect** |
  | **`LinkedHashMap` sized to the censused mean** | **no effect** |

  **THE LAST TWO ARE "NO EFFECT" AND NOT "A REGRESSION", AND THAT DISTINCTION COST A
  REPLICATION TO GET RIGHT**: the array bag read 738 ms against a baseline batch of 692,
  which looked like +6.6% — and a second baseline batch on the SAME BYTES read **735**.
  The baseline drifts 6.2% between batches, so the screen cannot resolve an effect this
  size, and round 858's law arrived on a fourth instrument. What the screen CAN say is
  that neither candidate is a win, which against a **−44%** premise is a refusal whatever
  the sign. `docs/perf/kir-backend-levers.md` §2a.

  **SO THE GUARDED SLOT HINT IS REFUSED TOO, WITHOUT BUILDING IT** — the design this entry
  used to propose. Its whole claim was that an O(1) indexed compare beats the scan the
  first refutation used; measured, that scan is LEVEL with a hash probe on the population
  that matters, so the hint is competing for the difference between level and level. Its
  cost is real — the shaped representation plus the declared member order reaching the
  lowering, which `CheckedFacts` does not expose. And landing that producer with no
  consumer would be round 887's shape exactly, so it is not a half-step worth taking
  either.

  **WHAT IS LEFT IS THE NOMINAL HALF, AND IT IS NOT A CONTAINER CHANGE**: a property read
  that is a `getfield` rather than any kind of lookup, worth **~16.3 us of a 33.65 us
  parse (~48%)**. **THE OBLIGATION TypeScript IMPOSES, unchanged:** assignability is
  STRUCTURAL, so a nominal encoding needs a witness per declared shape plus generated
  implementations, with a bag still reachable for `any`, for an index signature, and for a
  shape the closure cannot name. `docs/kir-structural-typing.md` §7 prices it at 12x the
  dynamic one. It is worth far more on Kotlin/Native, where §6's per-primitive table shows
  every `Any?` position is a real allocation — see (KIR.NATIVE.1)(a).

  **Measure it with `scripts/kir-bench.sh` and refuse it on the same standard as the other
  four: ranges disjoint, both Node arms flat.** The screening harness for a runtime-only
  candidate is five processes of the compiled program with the classes held fixed; its
  band is ~±5%, which is why the +2.3% arm is reported as "not a win" rather than as a
  regression.

- [x] **(KIR.EMIT.1) LANDED 2026-08-21 — `rewriteRelativeImportExtensions` is implemented
  in the emit, at all four specifier positions (ESM import/export declarations via a
  post-pass over the FINAL statement list, every `require` this transformer builds via
  `normalizeModuleSpecifier`, and a dynamic `import()` in the CallExpression arm). The
  post-pass position is load-bearing: the specifier TEXT is also how the transformer ASKS
  the checker about the target module, so rewriting earlier asks about a `.js` file the
  program does not contain. mitt's EXTENSIONLESS `./mitt` stays a benchmark expedient —
  tsgo leaves it alone too, so rewriting it would be a divergence, not a fix.
  `RewriteRelativeImportExtensionsTest`, 10 pins. ORIGINAL ENTRY: OUR ESM OUTPUT IS NOT RUNNABLE ON NODE AS EMITTED — a relative
  specifier keeps the extension it was written with.** tsgo 7.0.2 rewrites `./parse.ts` ->
  `./parse.js` under `rewriteRelativeImportExtensions` and we emit `'./parse.ts'` verbatim;
  Node ESM resolves a specifier LITERALLY and refuses both that and mitt's extensionless
  `'./mitt'`. `scripts/kir-bench.sh` post-processes the emit to run the arm at all, which is
  a benchmark expedient and NOT a fix. **Invisible to every gate we own** — the corpus pins
  emitted BYTES against tsc baselines, and no baseline asks whether Node can load the result.

- [x] **(KIR.EMIT.2) LANDED 2026-08-21.** The decision belongs to the LOWERING, which
  still holds the TypeScript type: `asString` — the one funnel for `+` and for a template
  span — asks whether every nullish member the operand's type admits is `undefined`, and
  picks `jsToStringNullAsUndefined` if so. A type admitting BOTH, and `any`, keep `"null"`,
  so the wrong answer is narrowed to the shapes the §3.1 collapse cannot separate at all
  rather than swapped for the opposite wrong answer. `KirNullishStringTest`, 5 pins.
  ORIGINAL ENTRY: `undefined` RENDERS AS `"null"` IN A STRING CONCATENATION.**
  `a + '|' + b` with `b` undefined prints `x|null` where JavaScript prints `x|undefined` —
  a `string | undefined` erases to `String?` and Kotlin's own `plus` renders the null. Found
  by `KirDynamicCallArityTest`, which was retargeted to avoid pinning it; the fix belongs in
  the concatenation lowering, not in the call path.

- [x] **(BENCH.1) THE THIRD JS ARM — ANSWERED 2026-08-21: the arm lands ON tsgo's (1.01x /
  1.02x), so the front end is performance-neutral and the whole 2.5x is the BACKEND. The
  harness is `scripts/kir-bench.sh` and the arm is now the standing control.** ORIGINAL ENTRY:
  THE THIRD JS ARM — OUR OWN EMITTED JavaScript, ON THE SAME NODE, AS THE CONTROL
  THAT SEPARATES "OUR COMPILER" FROM "OUR BACKEND".** The 2026-08-21 KIR runtime benchmark measured
  two arms — tsgo -> JS -> Node against xtsc `-kir` -> JVM bytecode -> java — and they disagree by
  library and by SIGN: **mitt 86.0 -> 66.5 ns/emit (JVM 1.29x FASTER), smol-toml 22.6 -> 56.4
  us/parse (JVM 2.50x SLOWER)**, medians of 5 interleaved processes, both arms producing identical
  `sink` accumulators and byte-identical acceptance output. **Two candidate causes are tangled in
  that 2.50x and no arm separates them**: the code our FRONT END produces, and the KIR backend's
  object model. The third arm holds the runtime fixed (Node) and varies only the compiler —
  `-core`'s Transformer/Emitter to JavaScript text, against tsgo's JavaScript, same sources, same
  drivers.

  **What each outcome MEANS, stated before the run (a prediction is what makes a refutation
  legible).** Arm 3 landing on arm 1 says the front end is performance-neutral and the whole 2.50x
  belongs to the backend, confirming the leaf profile by a second instrument rather than by
  inference. Arm 3 landing SLOWER than arm 1 is a genuinely new finding about our JS emitter and
  invisible to every gate we own — **the corpus pins emitted BYTES against tsc's baselines, and byte
  parity says nothing about how fast the resulting program runs on a modern JIT.**

  **The harness exists and is reusable** — drivers, projects, timing shape and the interleaved
  5-process protocol are in the 2026-08-21 session note; the only new piece is emitting the two
  bench projects with `-core` instead of tsgo. **Two traps it must carry.** (i) Node ESM needs a
  real extension: tsgo rewrites `./parse.ts` -> `./parse.js` under
  `rewriteRelativeImportExtensions` and leaves mitt's extensionless `./mitt` alone, so whatever our
  emitter does with a specifier has to be checked rather than assumed. (ii) **An arm that fails to
  RUN must fail loudly** — a JS file that throws on import prints nothing and a wall-clock harness
  reads that as a fast arm; assert the acceptance output byte-for-byte in every arm before timing
  anything, which is what caught nothing this round only because it was done first.

- [x] **(API.1) `Project`: open, diagnostics, in-memory edits — LANDED, round 909.** New module
  `xemantic-typescript-compiler-project` (jvm(), `explicitApi()`, `api(project(":…-core"))`);
  `Project.open` / `configPath` / `files` / `diagnostics()` / `diagnostics(file)` / `updateFile` /
  `deleteFile` / `close()` + `internal OverlayVfs`; 30 pins. **A query on a dirty project is a FULL
  rebuild and that is the compiler's property** — `ProjectCompiler.Result` retains no AST/binder/
  checker — so warmth comes from the CONTENT-keyed `CrawlParseCache` alone. Do not build "incremental"
  on it; the seam does not exist yet.

- [x] **(API.2) Position→node lookup — LANDED, round 910**, in two halves: a public `LineMap` /
  `TextPosition` + `Project.positionAt` / `offsetAt` (which read through the overlay and deliberately do
  NOT build, so a host can convert coordinates on a dirty project for free), and
  `Project.nodeInfoAt` (public, value-typed) over an `internal nodeAt` / `SourceIndex`. 53 pins.
  **The queue entry's "cheap and self-contained" was half wrong**: see the two span findings in the
  round-910 note and in CLAUDE.md — `Node.end` is the end of the FOLLOWING token, so `[pos,end)` is not
  a containment test, and the fix is a token snap-back rather than the sibling arithmetic this entry
  originally implied. **Unblocked by ONE word in core**: `computeParserFlags` is now public, because
  INV.1(e) ("the parse a crawl produces is provably the parse the core would produce") is exactly the
  guarantee an out-of-core parse needs, and duplicating it would be drift no test in the consuming
  module could see. Original entry, for the record:

  <details><summary>original (API.2) text</summary>

  **Position→node lookup, the unblocker EVERY editor feature needs.** There is no
  `getTouchingToken` equivalent anywhere in core: `computeLineStarts` is `private` to `Parser.kt:10119`
  and `positionToLineCharacter` is a private top-level fun (`TypeScriptCompiler.kt:6073`), both
  offset→line only, i.e. the direction diagnostics need and not the one an editor does. Needs: a
  public line/offset map, and a node-at-offset walk (`forEachChild`-driven, narrowest-enclosing, with
  the token-boundary rule tsc's `getTouchingPropertyName` uses). **Cheap and self-contained — it needs
  no checker state**, which is why it comes before quick-info.

  </details>

- [x] **(API.3a) QUICK INFO — LANDED, round 911, AND THE DESIGN BELOW IS NOW CONFIRMED BY MEASUREMENT
  RATHER THAN BY READING.** Captured-during-walk vs asked-post-hoc on ONE `Checker` instance: top-level
  annotated `const` **`string` / `string`** (the honest control — post-hoc is not wrong about
  everything), body local shadowing a global **`number` / `string`**, `typeof`-narrowed parameter
  **`string` / `any`**, parameter at its use **`number` / `any`**, arrow-body parameter **`string` /
  `any`**, class-method parameter **`number` / `any`**. **Five of six differ, and the prediction in this
  entry was wrong in the WORSE direction**: the narrowed case does not degrade to `string | number`
  (narrowing merely lost), it degrades to **`any`** — nothing durable binds a parameter at all — which is
  the one answer that is SILENT at every use site, so a post-hoc hover would have looked plausible and
  meant nothing. **THE HOOK'S REAL LESSON, now in CLAUDE.md: a per-node hook on the spine sees NONE of
  the checking ambient**, because the anchors install-and-restore it per dispatch — the position's scope
  is `ctaFrames.last()`, and the capture must reproduce `ctaM3StmtAnchorCore`'s prologue plus
  `withCtaFrameLocals(frame)`. Without that it answered `bodyLocal=string`, `narrowed=any`,
  `parameter=any`. Threaded as an explicit parameter on the `recheckOnly` model (nothing on
  `CompilerOptions`, no process-global mode); node identity is the RAW `(pos, end)` pair, so round 910's
  span semantics stay entirely in `-project`'s `SourceIndex`. **OFF IS FREE and gated as such**:
  `cost_gate.py` +0.00% on all 20 counters, the production cost being one null-valued field read and a
  predicted branch per node, with the NODE as the argument (round 900). Public surface stays value-typed:
  `QuickInfo` + `Project.quickInfoAt`.

- [x] **(API.3b) Go-to-definition — LANDED, round 913.** The entry read: *"the capture mechanism now
  exists and this is the same shape one field over: record the resolved `Symbol`'s `declarations`
  (each a pos/end-bearing node) at the captured position instead of its type, and answer
  `DefinitionLocation(fileName, start, length)`. **Read (API.3a)'s ambient lesson first** — a symbol
  resolved without `withCtaFrameLocals` is the same wrong answer one indirection along."* **The
  premise is WRONG in its most useful sentence, and the correction is the round's product: the
  ambient lesson does NOT transfer, because a definition's walk-scoped input is not the ambient at
  all.** `withCtaFrameLocals` restores `currentLocalTypes`, which holds TYPES and no symbols, so it
  cannot answer "what does this name refer to" for anything. What does is `spineCurrentScope` — the
  INV.2(c) lexical chain — and the spine **maintains that per NODE**, pushing it BEFORE a node's own
  enter handlers, so it is already correct at an arbitrary node and needs no reconstruction. What
  (API.3a) and (API.3b) genuinely share is only that both inputs are gone once the walk is over
  (`spineScopeClear` nulls the chain per file), which is what still makes capture mandatory:
  post-hoc, a body local resolves to a same-named FILE-LEVEL const and a parameter to nothing at all.
  Landed: `CapturedDefinition`/`CapturedDeclaration` in the core (recorded by the SAME hook as the
  type — one request, two facts), `DefinitionLocation` + `Project.definitionsAt` in `-project`,
  import-alias hop through `resolveImportedSymbolGeneral`, and an exact NAME span computed in the
  core by a forward token scan of the declaring file's own text. **19 pins, four-arm ablation, all
  gates green.**

- [x] **(API.3c) Batch a whole file's spans into ONE build.** The core `TypeCaptureRequest` already
  takes a SET of spans and `Project.quickInfoAt` deliberately does not cache its build (a capture build
  types nodes the checker had no reason to type, so its diagnostics are not reusable — pinned). So
  "semantic info for file X" is already one compile away from being one compile; exposing it turns
  hover-per-keystroke from N builds into 1. **This is the item that makes the API practical for an
  editor** and it needs no new mechanism. **LANDED round 914** —
  `Project.semanticsAt(fileName, offsets)` (the primitive) and `Project.fileSemantics(fileName)` (the
  sweep, expressed on it), answering `SemanticInfo(start, end, kind, quickInfo, definitions)`: ONE
  build for any span count, both answers per span, distinct spans sorted `(start, end)`. Measured
  **1 compile / 100 ms against 34 compiles / 3,373 ms and 68 compiles / 6,209 ms** on a
  34-identifier fixture. **THE PREMISE'S ONE ERROR, and it is the round's technical product: "it
  needs no new mechanism" is true of the CAPTURE and false of its KEY.** `TypeCaptureRequest`'s
  packed `(start, end)` key was left un-finalized with a note saying to finalize it "should a caller
  ever request spans in bulk" — and bulk is exactly what this item is: `Long.hashCode` folds
  `(a shl 32) or b` onto `a xor b`, and a node's `end` is its `start` plus a token or two, so a whole
  file's spans collapse onto a few dozen hashes (measured: **>400 spans onto <40 hashes**, round
  889's defect verbatim). It now goes through `packIdPair`, pinned by a measuring test with a raw-pack
  negative control. **26 pins, all gates green.**

  <details><summary>the design decision, recorded round 910 and confirmed round 911</summary>

  **(API.3) Quick info + go-to-definition — THE DESIGN IS NOW DECIDED BY EVIDENCE: *POSITION-DIRECTED
  CAPTURE*, NOT A POST-HOC QUERY, BECAUSE THE CHECKER'S ANSWER TO "WHAT IS THE TYPE HERE" IS A FUNCTION
  OF WALK-SCOPED AMBIENT STATE AND A POST-HOC CALL WOULD BE SILENTLY WRONG FOR EXACTLY THE INTERESTING
  CASES (round 909, by reading `getTypeOfIdentifier`).** `Checker` does all its work in `init`, so the
  instance still HOLDS its tables afterwards and "hand the Checker back and call `getTypeOfExpression`"
  looks free. It is not: `getTypeOfIdentifier` (`Checker.kt:108777`) consults, IN ORDER,
  `currentLocalTypes` (its own comment: *"populated during TS2322 checking walk"*),
  `currentParamBindingNames`, `currentCheckFileName` -> `fileLocalTypeMaps`, `currentFileLocals`, the
  inference-namespace chain, and only THEN the node-keyed `lookupPerFileForNode`. At rest
  `currentLocalTypes` is an empty `HashMap` (`:636`) and the two `current*` file fields are null, so a
  post-hoc query **skips the first five reads** and falls through to globals. **For a
  FUNCTION-BODY LOCAL that does not merely lose narrowing — it can resolve to an unrelated same-named
  global**, which is the `useCaseSensitiveFileNames` failure documented in that very function
  (a destructured param resolving to another file's function, FP TS2345 x9). Two of the ambient reads
  are FILE-scoped and cheaply re-installable from outside; `currentLocalTypes` is
  STATEMENT-POSITION-scoped, built first-wins as the walk proceeds and deliberately leaking across
  blocks in statement order — **it cannot be reconstructed for an arbitrary position without
  re-walking to that position, which is the whole argument for capture.** So: hand the compiler the
  position(s) BEFORE the build and capture type+symbol at those nodes while the real ambient is
  installed. Correct by construction, and it **batches** — one build can capture every identifier in a
  file, so "semantic info for file X" is one compile rather than N. Cost, stated: a query is a compile
  (~5.2 s warm on tsc's own sources, far less on a normal project, repeats warm through
  `CrawlParseCache`); too slow per keystroke, fine for hover-on-demand.
  **IMPLEMENTATION CONSTRAINT A NEW AGENT WILL OTHERWISE LOSE A ROUND TO: a capture handler is a spine
  handler, so it must extend `SpineDispatch.enterClosure` or round 888's `spineEnterMask` means it is
  NEVER CALLED**, and `python3 scripts/spine_closure_audit.py` must be run after touching any
  `spine*EnterNode`. **PUBLIC SURFACE STAYS VALUE-TYPED** (`QuickInfo(kind, displayString, span,
  docs)`, `DefinitionLocation(fileName, start, length)`) — no AST, no `Symbol`, no `Type`.
  **THE FIRST STEP IS STILL A MEASUREMENT, NOT CODE:** pin the above by asking a post-init `Checker`
  for the type at three positions — a top-level `const`, a function-body local, and a guard-narrowed
  reference — and record which answer wrong. That experiment becomes the regression pin for the capture
  path.

  **THE STARTING FACTS** (unchanged, and they are what make capture cheap): everything an editor needs
  is `private` in `Checker.kt` and nothing hands back live state — `getTypeOfExpression` (`:108501`),
  `getTypeOfSymbol` (`:106667`) and `typeToString` (`:120389`) are all `private fun`, and
  `BinderResult.nodeToSymbol` is public but no `BinderResult` ever escapes a compile. Capture needs only
  an `internal` seam plus a handler; it publishes none of them.

  **THE THREE ALTERNATIVES, AND WHY THEY ARE NOT THE NEXT STEP.** (a) **post-hoc query-shaped** —
  narrow `Checker` entry points answering one question after `init`: **superseded by the finding above**,
  because it is silently wrong for body locals and narrowed references (the ONE hover case a user
  notices is `let`/`const` inside a function). Directed capture is (a)'s cheapness without its defect.
  (b) **snapshot-shaped** — return a `ProgramSnapshot` holding ASTs + binder output + the live
  `Checker`: **REJECTED for now, and the reason is this repo's own history** — it freezes as versioned
  API exactly the structures the perf arc keeps rewriting (rounds 889-908 changed packed-key hashing,
  container types and memo layouts, and moved maps onto `LongKeyMap`/`IntKeyMap`, which deliberately
  have NO iterator). Publishing them constrains the work that just delivered -10.5%. It also does not
  even solve the ambient problem: a snapshot hands back the same post-hoc trap. (c) **the full
  inversion** — a lazy, re-entrant checker (`docs/ARCHITECTURE-RETHINK.md:850` names it as the LSP
  prerequisite): **the right end state and the wrong next step**, the largest job in the repo. Do not
  let hover gate on it — and do not let it be "unblocked" by an API that has already published the
  internals it must change.

  </details>

- [x] **(BUG.1) The compiler disagrees with itself about a lone `\r` — DONE, round 915.** The
  convention is now stated ONCE, as `lineBreakWidthAt` in a new `LineStarts.kt`, and every
  offset→line conversion in the compiler goes through it. The sweep the item asked for found **five**
  such converters where the entry named two, four of them wrong: `Checker.lineStartsFor`, its inverse
  `Checker.posOfLineCol`, `TypeScriptCompiler.positionToLineCharacter` (plus its inline TS2688 twin),
  the `Transformer`'s JSX dev-runtime coordinates (EMITTED output, not a diagnostic), and
  `CompilerOptions.computeLineAndColumn` — which implemented a THIRD convention, `\r` as zero-width.
  `-project`'s `LineMap` was already correct and stays a reimplementation, pinned by a differential.
  **The finding that outlives the fix**: `parseMultiFileSource` — the `// @directive` splitter behind
  the whole generated corpus — begins by replacing every `\r\n` and `\r` with `\n`, so the corpus was
  not merely unlucky, it was structurally incapable of carrying a `\r` to the Parser; only the
  project/`Vfs` path can, which is the path the `(API.*)` arc sits on. `LineTerminatorConsistencyTest`
  (core) + `ProjectPositionTest`'s lone-`\r` differential are the gate; 5 pins redden under ablation.

- [x] **(API.3d) Member go-to-definition — LANDED, round 916.** The gap round 913 recorded
  deliberately: *"a scope lookup of a member name finds whatever unrelated binding happens to share
  the spelling, and a confidently wrong navigation target is worse than none. Member definitions need
  the receiver's type resolved and its property symbol found, which is a separate mechanism and not
  this one."* It is now that separate mechanism, in the SAME capture hook and with no new public type:
  `typeCaptureMemberSymbols` resolves a member name through its RECEIVER and hands the resulting
  symbols' declarations to the existing `CapturedDeclaration` path, so a member answer is simply a
  non-empty `definitions` list where one used to be empty. **ANSWERS**: `o.p` / `o.m()` / `this.p` /
  `super.p` / `C.staticP`; a member of an IMPORTED interface (in the declaring file); an INHERITED
  member (the BASE's declaration); a MERGED member (one location per contributing declaration); a
  member of a UNION or INTERSECTION receiver (one per constituent, in constituent order); `N.x` and
  the qualified TYPE `N.T` for a namespace, module alias or enum; a LIB member (in `lib.*.d.ts`, the
  policy `definitionsAt` already documented for a free name). **REFUSED, each with a reason in the
  KDoc**: an element access (`o["p"]` — the argument is a literal, and only identifiers are offered a
  definition); an object-literal key being declared (`{ p: v }` — the useful target is the CONTEXTUAL
  type's property, a third mechanism); a member's own declaration name (it already IS the
  declaration); a chained namespace segment (`A.B.x`); an unresolvable member (silence, never the
  nearest same-named anything). **THE ROUND'S TWO FINDINGS**: the ambient the hook already installs is
  exactly enough — `this` needed `currentClassForThis`, which round 911's install already restores and
  which is deliberately NULL in a static member — and going through the compiler's own
  `resolveStructuredTypeMembers` rather than a hand-rolled table read is what makes the inherited and
  generic cases right for free. **13 pins, five-arm ablation each reddening a DISTINCT set, all gates
  green.**

- [x] **(API.4a) The completion ANCHOR + MEMBER completions — LANDED, round 917.** (API.4) was
  decomposed rather than taken whole; this is the standalone half that needed the genuinely new
  mechanism. **THE ANCHOR** (`SourceIndex.completionAnchorAt` / `CompletionAnchor`, `-project`, where
  round 910's caret already lives) answers a TOKEN-level question, because a completion request has no
  node at the caret by construction: it reports a `CompletionKind` (MEMBER / FREE_NAME / NONE), the
  typed PREFIX, and a replacement span covering the whole word rather than only the prefix. **The
  recovery rule for an incomplete `o.` is that there is nothing to recover**: this parser's `Dot ->`
  arm always builds a `PropertyAccessExpression`, synthesizing a zero-width `Identifier("")` and
  reporting TS1003, so the receiver is a real node at end of file, before a `}` and across a newline
  alike — the anchor descends to the character BEFORE the dot and walks back out to the access whose
  own dot that is (`realEnd(expression) <= dotStart < name.pos`, which at most one node in a path can
  satisfy). A `.` the parse did not turn into an access answers empty rather than guessing a receiver
  from bracket-balanced text. **THE MEMBERS** ride (API.3d)'s resolution one question wider —
  `TypeCaptureRequest.memberSpans` (a SECOND span list, so `fileSemantics` never enumerates) ->
  `CapturedMembers` / `CapturedMember(name, kind, typeText, optional, readonly, accessibility)`.
  **`Project.completionsAt(fileName, offset): CompletionList`.** Free names are an explicit
  `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED`, never a silent empty list.

- [x] **(API.4b) FREE-NAME completions — LANDED, round 918; KEYWORDS REFUSED with a reason.** It did
  land by deleting one refusal: `CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED` is gone and no
  signature moved. **THE MECHANISM** is a THIRD span list (`TypeCaptureRequest.scopeSpans` ->
  `CapturedScope` / `CapturedName(name, kind)`), unioned into `keysByFile` exactly as `memberSpans` is,
  and it is the ONE capture that also admits a NON-`Expression` node — a free caret is anchored at the
  innermost node ENCLOSING it, routinely a Block or the source file. **THE ENUMERATION IS
  `spineScopeLookup`'s OWN WALK, RUN TO EXHAUSTION** — every level's `symbols` then its `existing`,
  innermost first, first sighting wins — then the merged/lib GLOBALS filtered through
  `globalsForFile` (INV.3(c)). That identity is the correctness argument: *a name the list offers is a
  name `definitionsAt` will resolve, and a name it hides is hidden because something nearer binds the
  spelling.* **TWO DIVERGENCES FROM THE ENTRY AS WRITTEN, both deliberate and both ablated.** (i)
  `LexicalScope.existing` IS read: round 748's `symbols`-only rule is about a RESOLVER whose soundness
  is that it cannot change how an existing name resolves, and an enumeration reading `symbols` only
  offers no file-level declaration and no import at all (arm A5, 8 red). (ii) `lexLevelHasName`'s
  UNTRUSTED-level skip is NOT applied: it belongs to a chain with a second, export-filtered threaded
  population to fall back on, and this chain has none — applying it answers nothing inside every
  namespace body (arm A3, 1 red, uniquely its own). **A FREE-NAME ITEM CARRIES NO `typeText`**, decided
  on measurement: at a caret in a real file of the compiler profile the list is **1,628 items**, the
  enumeration itself **0.39-0.64 ms**, adding a type to every item **+2.6-14.3 ms** — and **618 of
  1,629 (37.9%) would render `any`/`error`**, because a free name may name a TYPE. **KEYWORDS ARE
  REFUSED**: a useful list is context-sensitive and the anchor is token-level, so an unconditional one
  offers items that do not compile — the thing the member half already refuses to do. **22 pins**
  (18 `-project`, 4 core `ScopeCaptureMeasurementTest`), **seven-arm ablation, six DISTINCT sets**;
  A7 (drop the writable-name filter) read **0 red** and is recorded in-file as an UNDISCRIMINATED
  guard rather than claimed. All gates green.

  **WHAT IS ALREADY YOURS, do not re-derive it.** The anchor: `completionAnchorAt` already returns
  `FREE_NAME` with the correct prefix and replacement span at every free position, and already answers
  `NONE` inside strings, templates, comments and numeric literals — `CompletionAnchorTest` pins all of
  it, including the caret at the very end of the file. The public value types, the refusal enum, the
  `memberSpans` channel and the "off is free" wiring. The build-free short-circuit (a refused kind does
  not compile) — you will be REMOVING that for FREE_NAME, which makes free-name completion a compile
  where member completion already is one.

  **WHAT MUST BE BUILT, and the one structural fact that decides its shape.** The scope chain is
  **CLEARED PER FILE**: `spineCurrentScope` is nulled by the spine's per-file teardown, which is what
  `DefinitionCaptureMeasurementTest` measures — so the enumeration must happen DURING the walk, at the
  requested position, exactly as `typeCaptureRecordDefinition` does. There is no post-hoc option. The
  natural shape is a third span list (`scopeSpans`) beside `memberSpans`, keyed the same way, recording
  a `CapturedScope` at the node the anchor names — and the anchor must therefore hand in a NODE for a
  free position too, which today it does not (it returns `receiver = null`). Deciding WHICH node a free
  caret names is the first sub-problem: the caret is between nodes, so the honest candidate is the
  nearest enclosing statement or block, and its scope is the scope in force for the position.

  **THE SIZE PROBLEM IS REAL AND IS MEASURED.** CLAUDE.md round 902: `LexicalScope.symbols` holds 1.51
  symbols averaged over SCOPES but **290.94 averaged over a real PROBE**, because the ascent walks
  outwards and 35.5% of probes land on levels holding a mean of **815**. A completion list is that
  whole ascent, flattened — so it is hundreds of items on a real program, every one of which costs a
  `getTypeOfSymbol` + `typeToString` if the item is to carry a type the way a member item does.
  **Decide whether a free-name item carries `typeText` at all before building it**; making it optional
  (null for a free name, present for a member) is a strictly additive change to `CompletionItem` and
  is the cheap escape.

  **SHADOWING AND DEDUP.** Innermost wins: a name bound at two levels must appear ONCE, as the inner
  binding, which is the opposite of the member walk's merge (a member declared twice is one item
  merged from both). `lexLevelHasName`'s ascent is the traversal to copy, with its two live rules —
  `LexicalScope.symbols` only, never `existing` (round 748), and the untrusted Module/Enum levels are
  SKIPPED (INV.4(c)(ii)). Keywords are a separate, purely syntactic list keyed on the anchor's
  position and want their own `CompletionItem.kind`.

  **THE PIN THAT DISCRIMINATES** is (API.4a)'s discriminator inverted: a caret inside a function body
  whose local shadows a same-named binding in ANOTHER FILE must offer the local ONCE and must not
  offer the other file's; and the member pins must stay green, i.e. a free-name enumeration must not
  leak into a member position — the failure round 913 refused and round 916's arm A2 catches.

- [x] **(BUG.2) The `-project` token index de-synchronised at the first `${…}` — LANDED, round 919.**
  Found by (API.5)'s cost measurement, not by a test. `SourceIndex.scanTokens` ran a context-free
  `Scanner.scan()` loop and the parser re-scans the `}` that closes a template substitution
  (`reScanTemplateToken`); without that, the `}` reads as a CloseBrace, whatever follows reads as
  operators, and the CLOSING BACKTICK opens a fresh `NoSubstitutionTemplateLiteral` that runs to the
  next backtick **anywhere in the file**. Unlike a SPLIT (which only adds ends and is why the slash and
  greater-than re-scans are still deliberately absent) a MERGE de-synchronises the stream **for the
  rest of the file**, so every later node's `realEnd` snaps back, `pathAt` cannot descend into it, and
  `nodeInfoAt` / `quickInfoAt` / `definitionsAt` / `completionsAt` all answer about a huge enclosing
  node. Measured on tsc's own `checker.ts`: **50,684 tokens for 3,151,772 characters, the longest
  62,089**, and a caret on a top-level function's name resolving to the whole file's `Block`. The fix
  tracks substitution nesting exactly as `Parser` does (a `TemplateHead` pushes, braces inside are
  counted, the closing `}` is re-scanned into a middle or a tail). `TemplateTokenSyncTest`, 5 pins,
  arm A6.

- [x] **(API.5) FIND REFERENCES + DOCUMENT HIGHLIGHTS — LANDED, round 919.** `ReferenceLocation(
  fileName, start, end, isDeclaration)`; **`Project.referencesAt(fileName, offset)`** (the program)
  and **`Project.documentHighlightsAt(fileName, offset)`** (one file). **ZERO core changes** — the
  whole feature is (API.3c)'s batch turned inside out, above the compiler. **THE IDENTITY QUESTION,
  which the brief said to verify rather than inherit, VERIFIED AND ANSWERED: a DECLARATION-LOCATION SET
  is a sound proxy for "the same symbol", but the relation is INTERSECTION, not equality.** Measured on
  a probe fixture before any code was written: the import alias, its `import { }` clause, every use and
  the export are ONE set (the capture's alias hop already unifies them); two merged `interface I`
  blocks give every occurrence the SAME two-declaration set (equality would not split them); three
  same-spelled `collide` bindings over two files give three DISJOINT sets. Equality FAILS on one shape
  only, and it is a real one: a member of a UNION receiver resolves to one declaration per constituent,
  so `u.p` and a single-constituent `a.p` would be different groups. **THE ONE HOLE, stated and pinned
  rather than papered over:** a MEMBER's own declaration name is bound by no scope and has no receiver,
  so the capture resolves it to nothing (which is exactly why `definitionsAt` answers empty there). It
  is recovered from the sweep's own evidence — an occurrence that resolved TO that span proves the
  caret is a declaration — which leaves exactly one truthful gap: **a member declared and never used
  answers EMPTY rather than a list of one** (tsc answers one). Free names are unaffected. **REFUSED
  with reasons:** read-vs-write (`[x] = pair` / `({x} = o)` / `for (x of xs)` are writes under an array
  literal, an object literal and a `for` head, so a rule built from `x = 1` and `x++` reports them as
  READS and a host cannot tell a complete answer from an incomplete one — the same grammar-position
  mechanism keywords are refused for); lib files are not swept for uses; element access. **MEASURED on
  the compiler profile** (78 files, 9,977,097 chars, **381,670 identifiers**, real libs, warm): plain
  rebuild 5.5-5.9 s; `documentHighlightsAt` **6.0-7.2 s** (1 build); `referencesAt` **8.3-9.9 s** clean
  (1 build) and **13.0-13.5 s** dirty (2 — `files`' build first); the sweep is 2.5-4 s on top of the
  rebuild WHATEVER the caret (168 hits in 1 file and **9,827 hits across 49 files** for `SyntaxKind`
  cost the same); **peak heap ~1.9 GB, so 512 MB is not enough**. Key spread needed nothing: both
  packers were already finalized (round 914's `packIdPair`). **19 pins**, eight-arm ablation, **every
  arm a DISTINCT set**. `docs/language-service.md` § 10b.

- [x] **(GATE.2) A REAL-SOURCE INVARIANT GATE for the language-service position APIs — LANDED, round
  920, and it found FOUR MORE DEFECTS on its first run.** (BUG.2) was live for nine rounds behind a
  green suite because **a hand-written fixture for a lexical API does not contain what real source
  contains**; round 919 fixed the template case and did not build the instrument. This is it.
  **`TokenIndexInvariants`** (commonTest) asserts ten rules true of ANY correct implementation — the
  tokens partition the text and the scan reaches EOF; every gap holds only trivia; a string literal
  never crosses a line break; a non-literal token is short; **every identifier the PARSER found starts
  a token of exactly its length** and `realEndOf` answers that end; a descent to an identifier's own
  position reaches it; a path strictly nests; and offset↔coordinate round-trips against an
  INDEPENDENT restatement of round 915's terminator rule. **The parse is the oracle** — it is the
  context-sensitive lexer this index approximates, so a merge is exactly "an identifier with no token
  starting at it". **THREE CORPORA, and the choice is the point.** Hermetic and permanent
  (`TokenIndexGateTest`): an adversarial shape corpus plus **the real `lib.*.d.ts` sources**
  (`RealLibFiles.files`, 2.39 MB of TypeScript nobody wrote for this test, already embedded, no
  vendored tree and no licensing question). Local-only: `build/bench/tsc-project-*` via
  `scripts/round920-token-gate.sh` + `RealSourceTokenGateMain`, which **REFUSES (exit 2) rather than
  skips** — a gate reading a local artifact that passes quietly where the artifact is absent is round
  853's and round 873's failure mode. **FOUND, all four real, all fixed:** (A) **a backtick inside a
  regular expression** (tsc's own `` /\r\n|[\\`…]/g ``) opened a template literal running to the
  next backtick anywhere in the file — a **25,761-character token** that swallowed the twelve
  identifiers after it, i.e. (BUG.2) in its second costume; (B) a **parenthesis-less arrow parameter**,
  an **index-signature parameter** and a **`catch` variable** were built with the default `[0, 0)`
  span, so no descent could enter them — **328 sites in tsc's 78 sources**, the API's single most
  common wrong answer; (C) `declare global`'s **`global`** name carried an EXACT end where every other
  node carries the following token's; (D) **JSX tag names** did the same, and (E) the synthetic
  **`new`** name of a construct signature was at `[0, 0)`. **THE FIX FOR (A) IS THE MECHANISM WORTH
  KEEPING: ask the parse.** A `RegularExpressionLiteralNode` and a `JsxText` each carry their own RAW
  text, so `pos + text.length` is exact; `SourceIndex` collects them and emits them verbatim, resuming
  the scanner past each. The undecidable "does this `/` divide or quote" is therefore never asked —
  whatever the parser decided, the index reproduces, so the two cannot disagree. **AFTER: 1,327 files,
  101,287,620 characters, 11,299,274 tokens, 3,936,158 identifiers, ZERO violations**, against 50 of
  78 files failing on the compiler profile alone before. **COST**: the oracle is +32 ms on 9,977,097
  chars = **+9.9% of `SourceIndex.of`** (358 vs 326 ms), paid only by a host's position query;
  `cost_gate.py` **+0.00% on all 20 counters** because nothing in the compile path builds an index.
  **POSITIVE CONTROL**: `SourceIndex.of(…, useParseAsLexerOracle = false)` is the in-binary OFF arm —
  the shape `--spineMaskOff` has — and the gate's own control asserts it reddens.

- [x] **(API.7) THE SYNTACTIC-ROLE MECHANISM + THREE OF THE FIVE STANDING REFUSALS — LANDED, round
  922.** The backlog was promoted as ONE item on round 921's premise that all five wanted the same
  missing "where is this caret in the grammar" mechanism. **Three did and two did not, which is the
  round's product.** BUILT: `SyntaxRoles` (`-project`), a PULL-BASED parent-chain ascent —
  `referenceUse(node)` for a node's role, `grammarPositionOf(path)` / `keywordsFor(path)` for a
  caret's — plus a sibling ascent in `Checker.kt` for the half of accessibility that needs symbols and
  heritage (the home is decided PER QUESTION, not forced). Pull rather than push on round 875's
  measurement (a maintained status is 11.1x the work); identity comparisons throughout, because AST
  nodes are `data class`es (round 471). **CASHED: (a) member-completion ACCESSIBILITY** — `private`
  only inside the declaring class, `protected` there or in a derived one, statics alike, the ascent
  reaching out of a nested arrow and the heritage walk following an IMPORT; biased PROVE-TO-HIDE, so
  every unknown leaves the member offered, which is the only answer to round 917's stated objection.
  **(b) KEYWORD completions**, bounded explicitly to STATEMENT / EXPRESSION / TYPE positions with
  `await`, `yield`, `super`, `return`, `break`, `continue` and the module-level declaration starters
  each gated, and every continuation keyword refused outright. **(c) READ-vs-WRITE**
  (`ReferenceLocation.use`), with the write set stated completely and `UNCLASSIFIED` as a fourth state
  rather than a default. **STILL REFUSED, with the reason CORRECTED**: an element access (`o["p"]`)
  and a contextual object-literal key (`{ p: v }`) were never blocked on a grammar position at all —
  recognising either shape is one test on the node's parent — and what each lacks is SEMANTIC (a
  capture channel plus member-lookup-by-text; a contextual type, which is walk-scoped and absent
  outright in a ternary branch). **TWO EXISTING ANSWERS CHANGED** and their round-917 / round-918 pins
  were updated in place: member completions no longer include inaccessible members, and a free-name
  list now carries keyword items (`kind = "Keyword"`). **+45 pins** (32 parse-only), **fourteen-arm
  ablation, all fourteen a DISTINCT set**, all gates green. `docs/language-service.md` §§ 10a, 10b.

- [x] **(API.13) § 14 AUDITED BY EXECUTION AND PINNED — LANDED, round 930; four of its
  claims were false and one of them was a DEFECT.** `docs/language-service.md` § 14 is the
  page a host author and a next agent read instead of twenty session notes, and it was
  three rounds old with a fixed defect still listed as open. Every claim in it was re-run
  — a fixture through the API, `tsc --lsp -stdio` as the oracle where the claim is parity,
  the cost table re-taken on the compiler profile — and the half that a test can defend is
  now `LanguageServiceStateTest` (+15 pins). **THE ONE DEFECT: `definitionsAt` on a
  `super.p` member answered NOTHING** while `quickInfoAt` at the same caret answered
  correctly — § 9's own table and § 14's maturity row both promised the base's declaration
  — because the receiver leg carried a `this` carrier and no `super` one. Fixed (8 lines,
  mirroring `typeCaptureThisMemberType`'s existing super branch) and measured against tsc,
  which navigates to `Base.pb` in the overridden shape and `Base.mb` in the inherited one.
  **THREE CORRECTIONS**: an enum member's declaration name does not "report nothing", it
  reports **`any`** (below, and still open); an object literal's own method
  "refuses a rename loudly" only once a CONTEXTUAL TYPE supplies it — with none it
  **renames completely** from either end, which the correction had in turn to be measured
  to find; a computed key is
  not silently missed, it is **reported in two of its three shapes** and silent only where
  the contextual member is optional. **ONE CLAIM CONFIRMED THE HARD WAY**: a template
  element access really is silent — the rename applies, the template keeps the old name,
  and the resulting program compiles clean. **THE COST TABLE'S BUILD COLUMN IS NOW PINNED
  and its wall column is marked not pinnable**, with `scripts/round930-ls-cost.sh` +
  `LanguageServiceCostMain` as the re-take (one process, one project, three rotations —
  the only comparison CLAUDE.md admits). Re-taken: rebuild 5.0–5.5 s (§ 3 said ~5.2, § 14
  said 5.5–5.9 — both drifted, in opposite directions), highlights 6.3 s on `checker.ts`
  and 5.0–5.5 s on `types.ts` (the row is a statement about a FILE, which is why it looked
  wrong), references 8.3–10.2 clean / 13.2–14.8 dirty, rename 14.3 s (`createTypeChecker`)
  – 21.0 s (`SyntaxKind`). `scripts/lsp_definition.py` is new, the fourth oracle.
  Suite 14,981 → 14,996 / 0 failures / 3 skipped; `cost_gate.py` +0.00% on all 20
  counters; `huge_methods.py --fail-over 0` clean on both modules; the round-920 token
  gate re-run (1,327 files, 101,287,620 chars, zero violations — which is § 14's own
  "101 M characters" claim, verified).

- [ ] **(CHK.5) COMPUTED KEYS — STAGES (a) AND (b) ARE LANDED (rounds 937/938); (c), (d),
  THE INDEX-SIGNATURE AXIS AND FIVE NEWLY MEASURED DUPLICATE GAPS REMAIN.**
  **(a) THE MEMBER-BUILDING SITES — DONE, round 937.** `interface I { [K]: number }`,
  `class C { [K]: number }` and `type T = { [K]: number }` now declare the member, in the
  property, method, get- and set-accessor forms, for every key spelling round 935/936
  resolves. It was NOT one site: six had to be levelled onto one namer, and two of them
  (`checkImplementsClauses`, `classMemberNamesTransitive`) compare a class's AST names to
  a target built from the resolved TYPE, so levelling the type side made a PRE-EXISTING
  Identifier-only drift reachable — two false positives with no computed key in them
  (`interface I { 1: string }` + `class C implements I { 1: string }`, and the same through
  a `static 1`) were closed as part of it. `checkComputedLiteralKeyMembers` now retracts
  before it emits, because the general relation reaches its TS2322 verdict once the key
  binds. Session note has the 40-row table and the 10-arm ablation.
  **(b) A DUPLICATE MEMBER DECLARATION — DONE, round 938, and it corrected its own
  premise.** This compiler ALREADY emitted TS2300 x2 + TS2717 for a plain
  `interface I { p: number; p: string }`, byte-identical to tsc, and for a type literal, a
  class, an enum, two getters, a numeric name and a class property-vs-method. Two things
  were wrong and both are closed: the member map was LAST-WINS where tsc keeps the FIRST
  (eight measured rows, including round 937's spurious TS2322, which was this defect and
  not a computed-key one), and neither duplicate SCAN could name a computed key — the class
  one knew `["a"]`/`[0]`, the interface one had no computed arm at all. Both now ask one
  namer. **The rule that decides the diagnostic came from a PRISTINE baseline, not from
  tsgo**: TS2300/TS2687 are the BINDER's checks and a LATE-BOUND key never reaches them
  (`dynamicNamesErrors` — `interface T0 { [c0]: number; 1: number }` gets NOTHING, `T3` gets
  TS2717 alone), where tsc 7.0.2 emits TS2300 for both; following tsgo reddens that corpus
  test. Same parting on the class `drop(1)` rule. `checkComputedLiteralKeyMembers` now
  retracts before it emits. Session note has the 21-row table and the 9-arm ablation.
  **(b2) NEW — FIVE DUPLICATE GAPS MEASURED IN ROUND 938 WITH tsc's ANSWER, EACH SMALL AND
  EACH SEPARATE.** (i) a MERGED-interface TS2717 — `interface I { p: number }` +
  `interface I { p: string }` is TS2717 at the second in tsc and silent here, because both
  duplicate scans are per-DECLARATION by construction (the first-wins TYPE is already
  right); (ii) an INTERFACE property-vs-METHOD pair is TS2300 x2 in tsc and silent here —
  `checkDuplicateInterfaceMembers` collects `PropertyDeclaration`s only, where its class
  twin collects four kinds; (iii) TS1117 for a late-bound OBJECT-LITERAL key
  (`{ p: 1, [K]: 2 }`) — `getPropertyKeyName`/`evaluateComputedPropertyName` is a THIRD
  namer with its own `__@computed:` scheme and its own numeric normalization, so widening
  it is not the one-line delegation the other two were; (iv) the required-vs-OPTIONAL
  TS2717 (`p: number; p?: number` — tsc says `number | undefined`); (v) **`C.p` reads the
  INSTANCE member's type when a static and an instance member share a name** — that is the
  unfinished `staticMembers` dual-population ("no behavior change yet" in
  `resolveInterfaceMembersCore`), not a duplicate rule, and it is the one of the five that
  is a WRONG TYPE rather than a missing diagnostic.
  **(c) A CONST IMPORTED FROM ANOTHER FILE, AND A CLASS `static readonly` KEY.**
  `import { IK } from "./k"; interface I { [IK]: number }` and `[C.B]` where
  `class C { static readonly B = "p" }`: both bind in tsc, both are still a false positive
  here (measured again round 937, on the DECLARATION side as well as the literal one). The
  syntactic walk cannot cross a file by construction; the route is the frozen binder tables
  (`resolveAlias`), which are deterministic and therefore allowed under round 935's law.
  **(d) THE `unique symbol` TYPE — unchanged, and round 937 CONFIRMED why it cannot land
  alone.** `declare const S: unique symbol` types as plain `symbol` here, so `[S]` and
  `[S2]` are ONE name. Round 936 predicted that naming the key on the literal side alone
  would invert the defect; round 937 measured the SAME inversion already live for a plain
  const (`const x: I = { [K]: 1 }` was TS2353 `'[K]'`, a false positive) and closed it by
  landing both sides together. (d) needs a `unique symbol` type keyed by the DECLARATION
  (tsc's `__@<desc>@<id>`, a name that survives a rename and an import) and both sides in
  ONE commit.
  **(e) NEW — THE INDEX-SIGNATURE AXIS, measured round 937 and belonging to neither (a) nor
  (d).** A computed key whose type is `string` (`let LW = "p"`), a literal UNION, or a
  dotted path through a VALUE (`obj.k`) gives tsc's interface, class and type literal a
  STRING INDEX SIGNATURE rather than a named member — `interface I { [LW]: number }` makes
  `i.p` a `number` in tsc, and `class C { [LW]: number }` likewise, where `c.p` is still
  **TS2339, a false positive** here. Late binding must keep REFUSING these keys; closing
  them is index-signature modelling. Round 936's `{ [L]: number; }`-vs-`{}` display row is
  the same gap seen from the display side.
  **(f) DONE, round 940 — THE TS2741 KEY NAME, the family's ONE measured PRISTINE divergence (round 939).**
  For a missing late-bound member we print `Property 'p' is missing in type '{}' but required
  in type 'I'` where tsc prints `'[K]'`. **Pristine names the key AS WRITTEN wherever it names
  one** — `'[E.A]'` (`assignmentCompatWithEnumIndexer`), `'["a"]'`
  (`duplicateIdentifierComputedName`, an ACTIVE gate), `'[c1]'` (`dynamicNamesErrors`, ACTIVE),
  `'[Symbol.toPrimitive]'` (`symbolProperty21`) — so pristine and tsgo AGREE here and we are
  the outlier. Round 937 recorded it against tsgo; round 939 confirmed the convention against
  pristine and verified our answer live at HEAD. No baseline covers the exact shape
  (`const K = "p"; interface I { [K]: number }; const x: I = {}`), which is why the suite is
  green. **LANDED round 940** at [formatPropertyDisplayName] — the ONE renderer the
  missing-property emitters already route the symbol through, so all twelve of its callers
  moved together — asking round 938's `computedKeyWrittenText`, which answers null for a
  spelling it cannot reproduce exactly. Pinned three ways (`[K]`, `[E.A]`, `["a"]`) with
  the negative controls that a NON-computed member keeps its bare name and a quoted string
  member keeps B291's quoted display; ablation arm A5 reddens exactly the three.
  **WHAT MUST NOT BE UNDONE**: the WELL-KNOWN-symbol route is deliberately not
  `computedSymbolKey` in general (tsc is SILENT for every computed key it cannot late-bind,
  measured over seven of them), and `getMemberName` itself stays unchanged — B451 records
  it as feeding ~20 callers including duplicate detection and abstract tracking, so the
  widening lives in `declaredMemberName` at the member-BUILDING call sites.

- [x] **(CHK.6) THE COMPUTED-KEY FAMILY RE-JUDGED AGAINST *PRISTINE* — DONE, round 939, and
  the verdict is that rounds 933-938 landed NOTHING pristine contradicts.** Rounds 933-937
  established their ground truth by running `tools/tsgo-7.0.2/lib/tsc`, the only reference
  compiler that RUNS on this box; round 938 then found the two references parting on this
  family's own territory, which left every row no corpus baseline covers resting on an oracle
  this project deliberately does not follow. The pristine oracle turned out to be on disk all
  along — `typescript-repo/tests/baselines/reference`, generated by the pinned pristine commit
  — and is now `scripts/pristine_oracle.py` (`--code` / `--pattern` / `--fixture`, every hit
  labelled ACTIVE vs not-generated, plus `--extract DIR`, which writes pristine's own input
  back out so our binary can be run over exactly what pristine saw). **34 landed decisions
  classified: 22 PRISTINE-CONFIRMED, 10 CORPUS-SILENT, 1 tsgo-ONLY, 1 PRISTINE-DIVERGENT** —
  the TS2741 key name, a message FORM round 937 had already recorded, now (CHK.5)(f).
  **The corpus protects much more of this family than the notes claimed**: `dynamicNames`,
  `dynamicNamesErrors`, `duplicateIdentifierComputedName`,
  `destructuredLateBoundNameHasCorrectTypes`, `checkDestructuringShorthandAssigment2`, the
  three `duplicateObjectLiteralProperty_computedName*` and **7 of the 10 TS2717 baselines in
  the whole corpus** are ACTIVE byte-exact gates sitting on these exact decisions.
  **And the strongest evidence is a negative**: `--extract` materialises pristine's own input,
  so our binary was run over **300** ungated pristine fixtures carrying a computed member key
  and differenced (line, code) against pristine's baseline. **277 of 300 emit nothing pristine
  does not**; of the 23 that do, four are (CHK.7) and NOT ONE of the other nineteen is
  attributable to rounds 933-938 — they are unimplemented checks in other families (`using`
  declarations, the private-modifier grammar, index-signature PARAMETER types, super-call
  ordering, a `declare global { interface SymbolConstructor }` that does not merge,
  `Symbol.hasInstance` narrowing, a `never` discriminant, module resolution). The four that
  ARE pristine divergences are older than the family, proved by the diff rather than argued.

- [x] **(CHK.7)(i) AND (iii) — LANDED, round 940, both FALSE POSITIVES, both CLOSED; (ii)
  AND (iv) RE-MEASURED AND RE-QUEUED BELOW, because round 939's entry was wrong about both
  in the direction that decides what to build.** (i) TS1117 was keyed on a computed key's
  SPELLING, so `var s: symbol; ({ [s]: 0, [s]() {}, get [s]() {} })` was TS1117 x2 here and
  silent in `symbolProperty1`/`2`/`3`; the namer now abstains — but ONLY when the key's own
  declaration is IN HAND and late binding still refused it, because a blanket abstain
  regresses `duplicateObjectLiteralProperty_computedName3` (an ACTIVE gate whose keys arrive
  through an `import * as keys`, which pristine binds by TYPE and round 935's syntactic
  resolver cannot follow across a file). (iii) An accessor followed by a PROPERTY is TS2300
  at the property alone — tsc's `PropertyExcludes = None` means a property declared last
  never trips the binder's duplicate check — which reproduces all 83 of
  `privateNameDuplicateField`'s rows and both halves of `duplicateClassElements`.
  **Measured: `privateNameDuplicateField` 3 ours-only rows -> 0; the 630-fixture pristine
  sweep 403 -> 397 ours-only rows with ZERO fixtures regressed; the 8-profile grid
  added=0 removed=0; suite 15,168 -> 15,193 with no baseline moved.**

- [x] **(CHK.8) — THE 630-FIXTURE PRISTINE SWEEP, TRIAGED AND ITS INSTRUMENT REPAIRED;
  TWO FALSE-POSITIVE FAMILIES CLOSED (round 941).** `scripts/pristine_sweep.py` supersedes
  round 940's sweep and **121 of that round's 397 OURS-ONLY rows (30.5%) were the
  instrument's own configuration**: the case-file fallback carried the `// @target:`
  directives tsc STRIPS (a whole-file line shift, 27 fixtures); directives were read from
  the EXTRACTED text, which the `.js` baseline echoes WITHOUT them; and a missing case file
  left no target where the baseline's `(target=…)` suffix still records it. An ALIGNMENT
  ORACLE (each reconstructed input compared line-for-line against pristine's `==== file ====`
  annotation) now makes the first defect impossible to reintroduce silently. **The triage of
  the remaining 334 rows is `docs/pristine-divergences.md` and its cause-class rules are
  `scripts/pristine_triage.py`** — genuine FP 182 (48.8%) / cascade 90 / harness 59 /
  deliberate convention 42. Closed this round: TS2376 (a `super` call need not be FIRST —
  tsc walks the statement list to the first IMMEDIATE `this`/`super` reference, stopping at
  arrows, function declarations/expressions, property declarations and method-like BODIES
  but NOT at their computed NAMES) and TS18028 (the private-identifier gate reads the target
  the user ASKED FOR, not the raw `ES3` default). Sweep **373 -> 334**, zero fixtures
  regressed, pristine-only 777 -> 776 (a true positive GAINED); 8-profile grid added=0
  removed=0 on all eight; suite 15,193 -> 15,214 with no baseline moved.

- [x] **(CHK.9) INDEX-SIGNATURE PARAMETER TYPES — 12 OURS-ONLY TS1268 ROWS -> 0, AND TWO
  TRUE POSITIVES GAINED (`indexSignatures1`, round 945).** tsc's rule, read off the pinned
  sources (`checkGrammarIndexSignatureParameters` + `isValidIndexKeyType`), has three parts we
  had two of. **The intersection arm was missing entirely**, so every BRANDED string
  (`type Id = string & { __tag: 'id' }` — the shape the rule exists for) was TS1268, and an
  `IntersectionType` NODE was not even offered to the type engine, so a syntactic
  `` `${string}xxx${string}` & `${string}yyy${string}` `` never got a verdict either. **And the
  generic test read only a bare `TypeReference`**, which is why `[key: T | number]` and
  `[key: T & string]` were TS1268 where pristine says TS1337 — the cause being that an alias's
  own `T` resolves to `anyType` at that grammar check, so the question has to be asked of the
  AST. Note `someType`/`everyType` distribute over UNIONS only: an intersection is valid when
  SOME constituent is (`string & 'a'` is a legal key), and reading that as `every` is the
  round's B4 arm. Measured: sweep **310 -> 298** ours-only with 0 added, pristine-only
  **775 -> 773**, zero fixtures regressed; 8-profile grid `added=0 removed=0`.

- [ ] **(CHK.10) DEFINITE ASSIGNMENT THROUGH A LATE-BOUND ELEMENT ACCESS — 4 OURS-ONLY
  TS2564 ROWS (`strictPropertyInitialization`, ALIGNED, round 941).** `class C12 { [a]: number;
  [b]: number; ['c']: number; constructor() { this[a] = 1; this[b] = 1; this['c'] = 1 } }`
  with `const a = 'a'; const b = Symbol()`: pristine sees the definite assignment through the
  ELEMENT ACCESS and is silent, we report `Property '…' has no initializer`. Same fixture
  reports `[E.A]` (an enum member key). Small, and squarely in the computed-key arc's own
  family — note that the triage classifier exempts this fixture by name from the
  strict-by-default bucket for exactly this reason. **CONFIRMED GENUINE, round 943**: that
  fixture's case file is not in this clone, so the sweep recovers no directives for it — but
  its own baseline carries **20 TS2564**, i.e. pristine had `strictPropertyInitialization`
  ON, so these four rows are not the convention. (The `--tsc-strict-default` arm deleted them
  until it was guarded on case-file presence; see `docs/pristine-divergences.md` § 0b.)

- [x] **(CHK.11) ELEMENT-ACCESS DISCRIMINANT NARROWING — 11 OURS-ONLY ROWS -> 0
  (`typeGuardNarrowsIndexedAccessOfKnownProperty1`, round 942).** The cause is one sentence:
  **tsc's `isMatchingReference` compares references by SYMBOL and ours compares the path
  STRINGS `getReferencePath` builds**, and every discriminant reader was written against the
  DOTTED spelling alone. FOUR mechanisms, all measured: `singleLevelDiscriminantSegment` (the
  switch reader accepts `name[seg]`); `getTypeOfElementAccess` flow-narrows its UNION
  RECEIVER (B1.1's gate, which its dotted twin has always had); `getReferencePath`
  NORMALISES an identifier-spellable string index onto the dotted segment, because the
  fixture mixes both spellings inside one expression (`s[0]["sub"].under["shape"]`); and
  `requiredEnumSwitchKeys` + `paramMemberChainType` accept an element-access discriminant and
  a multi-segment receiver, which is the two TS2366. **A FIFTH — the 17.34d half, narrowing
  the access's own union RESULT — was written, measured INERT (its ablation arm reddened NONE
  of the 21 pins and no probe could be built where it fires) and REMOVED.** **Measured: 11 -> 0, sweep 334 -> 318 with zero fixtures regressed, 8-profile grid
  added=0 removed=0.** `docs/pristine-divergences.md` § 3.4.

- [x] **(CHK.12) `[Symbol.hasInstance]` NARROWING — 5 OURS-ONLY ROWS -> 0, AND THE ENTRY WAS
  WRONG ABOUT ITS OWN SECOND FIXTURE (round 942).** `instanceof` now asks the RHS type for a
  `[Symbol.hasInstance]` method whose return is a non-`asserts` TYPE PREDICATE over parameter
  0 and uses its target — round 838's `instanceTypeOfConstructorValue` named that leg as its
  one deliberate omission — which answers the three shapes `prototype` and the construct
  signatures cannot: a GENERIC construct signature, SEVERAL construct signatures, and one
  returning `any`. **Two rules read off PRISTINE's baseline and re-read off tsgo 7.0.2: a
  usable predicate DECIDES (a `value is any` target narrows NOTHING and must not fall through
  — pristine's own lines 142/143), and an `instanceof` stays `checkDerived = true` even when
  the candidate came from a predicate, so a UNION candidate is DISTRIBUTED and its
  narrow-down direction is the NOMINAL base-chain test (`C1 | A` narrowed by `C1 | C2` is
  `C1`), scoped to a union candidate so round 425's single-candidate arm is byte-identical.**
  Measured: 5 -> 0 with pristine-only 8 -> 7, i.e. a true positive GAINED.
  **The entry's other fixture is MIS-BUCKETED**: `controlFlowInstanceofWithSymbolHasInstance`
  is 7 rows of which **6 are a PARSER GAP** (`abstract new (...) => infer U`), queued as
  (CHK.14), and 1 is the `instanceof` intersection tail, queued as (CHK.15). Out of scope by
  construction: a `static [Symbol.hasInstance]` on a CLASS declaration, which
  `resolveInstanceOfRhsType` answers from the declared type before the leg is reached.
  `docs/pristine-divergences.md` § 3.5.

- [x] **(CHK.14) `abstract new (…) => T` AND THE CONSTRUCTOR-TYPE `infer` — CLOSED round 947,
  15 ours-only rows (297 -> 282), PRISTINE-ONLY FLAT at 769, zero fixtures regressed.**
  `docs/pristine-divergences.md` § 3f. **This entry's own second half was diagnosed
  backwards and the correction is the round's product**: the defect is NOT "an `infer`
  inside a PARENTHESIZED extends clause does not publish its name" — parentheses are
  irrelevant (`collectInferTypeNames` recurses through `ParenthesizedType` and always has),
  the missing arm was **`ConstructorType`**, and the UNPARENTHESIZED spelling
  `T extends new () => infer U ? U : never` failed identically while the parenthesized
  FUNCTION-type spelling always worked. It is also not a parser item: it is a one-arm gap in
  the INV.4(c)(iii) scope walker, whose sibling `collectInferDecls` carries the arm with a
  comment about keeping parity with it. Landed alongside it: `parsePrimaryType`'s
  `abstract`-then-`new` lookahead (tsc's `isStartOfFunctionTypeOrConstructorType` +
  `parseModifiersForConstructorType`), whose SPAN bound is pinned in `-project` because no
  core diagnostic reads a `ConstructorType`'s `pos`. Held as false NEGATIVES on purpose: the
  `infer` still does not RESOLVE through a constructor type (`D<new () => K>` answers `any`),
  and the recorded `modifiers` set is read by nothing — TS2511 is its named future consumer.

- [x] **(CHK.25) `using` / `await using` DECLARATIONS DID NOT PARSE — 33 OURS-ONLY ROWS OVER
  FOUR FIXTURES, THE LARGEST SINGLE CASCADE IN THE WHOLE PRISTINE POPULATION. LANDED round
  948: ours-only **282 -> 251** over 74 -> 71 fixtures, pristine-only **769 -> 767** (two
  TS2353 GAINED), zero fixtures regressed, zero corpus baselines moved.** `using x = expr;`
  reported TS1434 at the `using` and then TS2304 for every name the failed statement never
  bound. **The representation is tsc's own and needed no new node**: a
  `VariableDeclarationList`'s `flags` field already IS the head token, so `using` is
  `SyntaxKind.UsingKeyword` — no `forEachChild` arm, no `NodeKind`, no binder arm, because the
  binder's `isVar` test already reads any non-`var` head as block-scoped. `await using` is two
  tokens collapsed onto a synthetic `SyntaxKind.AwaitUsingKeyword` the scanner never produces.
  **The whole risk was the CONTEXTUAL KEYWORD and it did NOT materialise anywhere**: the eight
  profiles carry 336 occurrences of `using` as an identifier / property name and zero
  declarations, and the binary grid is byte-identical on all eight. Landed with the grammar
  rules (TS1155 / TS1492 / TS1493 / TS1494 / TS1491 / TS1495), the disposability rule
  (TS2850 / TS2851, positive-evidence-only and switched off unless the lib declares
  `Disposable`), and a VERBATIM emit of the head. `docs/pristine-divergences.md` § 3g.

- [ ] **(CHK.26) `infer U extends T` FOLLOWED BY A CONDITIONAL `?` IS PARSED AS A CONSTRAINED
  INFER WHERE tsc PARSES A CONDITIONAL — 8 OURS-ONLY ROWS, `inferTypesWithExtends1` lines 95 /
  103 / 105 (sub-triaged round 947, § 2.3 P2).** **`infer X extends` itself ALREADY PARSES**
  and has for as long as `parseTypeParameter` has handled a constraint — round 941's label for
  this bucket named the wrong thing. What fails is the DISAMBIGUATION: tsc's
  `tryParseConstraintOfInferType` parses `extends <type>` with conditional types DISALLOWED
  and rolls the whole `extends` back when the next token is `?`, **unless it is already in a
  disallow-conditional context** — so `T extends (infer U extends number ? 1 : 0) ? 1 : 0` is
  a conditional inside the parens (pristine's own comment on the line says *"ok, parsed as
  conditional"*) while `T extends infer U extends string ? U : never` keeps its constraint.
  We take the constraint unconditionally and cascade TS1005 / TS1109 / TS1128. **The rollback
  alone is NOT the fix and would break the second shape**: it needs the
  `disallowConditionalTypes` CONTEXT threaded through `parseType`'s conditional production
  (`extendsType` and a mapped type's `nameType` set it; a parenthesized type clears it) — an
  edit to the production the frozen-subsystem warning is about, which is why round 947 scoped
  it out rather than attempting it beside a landing change. `scanner.tryScan` is already the
  rollback primitive (`tryParseTypeParameters` is the reference shape). Pinned SILENT-side by
  `AbstractConstructorTypeTest.scoped out - an infer constraint is not re-read as the
  enclosing conditional`, which asserts today's TS1005 so the fix has to move it.

- [ ] **(CHK.27) THE `using` FALSE NEGATIVES ROUND 948 LEFT BEHIND — ALL FOUR ARE FEATURES
  THIS COMPILER SIMPLY DOES NOT HAVE, AND NONE COSTS AN OURS-ONLY ROW.** (i) **The DOWNLEVEL
  EMIT.** The head is emitted VERBATIM, which is tsc's own output only at a target with
  explicit resource management (>= ESNext); below it tsc rewrites the block through
  `__addDisposableResource` / `__disposeResources`, and the ~439 `usingDeclarations*` baselines
  upstream are mostly `(module=…,target=…)` variations of exactly that. Verbatim is the SAFE
  half of the choice — rewriting the head to `var` would silently delete the disposal — but a
  low target now emits a `using` a downlevel runtime cannot execute. **This clone carries no
  `using` case file, so the generated corpus still gates none of it**; an emit landing needs
  its own gate (`--outDir` + `diff -r`, since `--noEmit` makes every instrument here blind to
  transform/emit). (ii) **`declare using` — TS1545 `'using' declarations are not allowed in
  ambient contexts.`** (and TS1546); it needs an arm in `parseDeclareDeclaration`, which
  round 948 did not touch, so `declare using x: T;` still cascades. (iii) **The `case` /
  `default`-clause rule, TS1547 / TS1548**, which tsc decides from `declarationList.parent
  .parent` being a clause. (iv) **The `await using` CONTEXT rules — TS2852 / TS2853 / TS2854 and
  TS18054**; a top-level `await using` in a non-module file, or one inside a class static
  block, is silent today. Also unreproduced: TS2850's nested
  `Property '[Symbol.dispose]' is missing …` elaboration and its TS2728 related info.

- [ ] **(CHK.28) A DECORATED CLASS *EXPRESSION* IN AN INITIALIZER IS REFUSED — TS1206
  `Decorators are not valid here.`, 2 OURS-ONLY ROWS
  (`usingDeclarationsNamedEvaluationDecoratorsAndClassFields` lines 14 / 18, round 948).**
  `const C = @dec class { }` and `using C = @dec class { }` both take it; pristine accepts
  both (decorators on class expressions have been legal since TS 5.0). **It is NOT a `using`
  defect** — the `using` parse cascade had merely been masking it, which is why closing
  (CHK.25) took the fixture 10 -> 2 rather than 10 -> 0. Reproduce with
  `const C3 = @dec class { static x = 1; };` at any target; the emitter half (tsc's
  `__esDecorate` for a class expression) is a separate question from the checker's refusal.

- [ ] **(CHK.15) THE `instanceof` POSITIVE BRANCH HAS NO INTERSECTION TAIL — 1 OURS-ONLY ROW,
  BUT A GENERAL RULE (`controlFlowInstanceofWithSymbolHasInstance` line 26, round 942).**
  `s = new Set<number>(); if (s instanceof Promise) {} s.add(42)` reports
  `Property 'add' does not exist on type 'Promise<any> | Set<number>'` where pristine is
  silent: tsc's `getNarrowedType` ends in `maybeTypeOfKind(t, Instantiable) … ?
  getIntersectionType([t, c])`, so the then-branch is `Set<number> & Promise<any>` and the
  JOIN back is `Set<number>`; ours answers the CANDIDATE alone (`narrowByInstanceOf`'s
  `isMatch -> classType`), so the join is a union. `narrowByCallPredicateWorker` already
  carries the equivalent round-425 "positive-empty INTERSECTION fallback" for a PREDICATE
  target — this is the same rule at the `instanceof` site, and its blast radius is every
  `instanceof` in the program, so it needs the 8-profile grid and the 630-fixture sweep, not
  a pin alone.

- [x] **(CHK.16) A DECLARATION'S OWN TYPE PARAMETERS WERE NOT IN SCOPE FOR THE TS2344
  CONSTRAINT WALKER — LANDED, round 943, and it FIXES A FALSE NEGATIVE IN THE SAME MOVE.**
  `checkConstraintsInStatements` pushed them for a `FunctionDeclaration` (round 82, whose
  comment names this exact defect), for a type ALIAS only when the body was an `ImportType`
  (B98a's narrow gate) and for a class or interface never — so a parameter SHADOWED by a
  same-named file-level type was resolved to that type and judged against the callee's
  constraint. `withDeclTypeParamScope` is now the one site, used by the alias, class and
  interface branches, heritage clauses included. Pristine `conditionalTypes1` is two
  ours-only TS2344 from `interface A` (line 309) against `type And<A extends boolean, B
  extends boolean> = If<A, B, false>` (line 171) — **138 lines apart, which is why every
  hand-written reduction was silent and the bisection had to delete the file's TAIL**. The
  other direction was equally wrong, so the fix ADDS diagnostics: `type Loose<Q> = Box<Q>`
  with `interface Box<S extends string>` was silent and now reports TS2344 as pristine does,
  and over 611 fixtures that gained NO ours-only row. **The first cut fixed only the alias
  branch and a "regression guard" pin went RED — that is how the class/interface half was
  found.** Sweep **318 -> 316**, pristine-only 775 -> 775, zero fixtures regressed, 8-profile
  grid added=0 removed=0, suite 15,235 -> 15,248 with no baseline moved.
  `docs/pristine-divergences.md` § 3c.

- [x] **(CHK.17) LIB AVAILABILITY WAS DECIDED FROM THE *RAW* `ES3` TARGET DEFAULT WHERE tsc
  DEFAULTS AN UNSET TARGET TO THE LATEST — LANDED, round 944.** `CompilerOptions.libTarget`
  (unset -> ES2024, explicit -> itself, `es5` included) is now the one input to
  `libFeatureAvailable`, `libProvidesGlobalAt` and the lib-SET resolution in `bindRealLibs` /
  `RealLibSnapshots.prewarmParsedLibFiles`; NOT `effectiveTarget`, which maps an explicit
  `es5` UP to ES2015 and would delete that program's genuine TS2550/TS2583 (round 941's
  TS18028 fork). Sweep **316 -> 313**, pristine-only 775 -> 775, zero fixtures regressed,
  8-profile grid `added=0 removed=0` on all eight (every profile sets BOTH `target: es2020`
  and `lib: ["es2020"]`, so it is a pure control), suite **15,248 -> 15,262 / 0** with NO
  corpus baseline moved. The CLAUDE.md entry that recorded the raw reading as deliberate is
  corrected: it was INVISIBLE, not tested — 0 of 55 case files touching a `LIB_MIN_TARGET`
  member name, 0 of the ~30 referencing a `LIB_GLOBAL_INTRODUCING` global and 0 of the 26
  carrying an `and N more` count omit `@target`/`@lib`.

- [x] **(CHK.21) THE 23 `options.target < ES2015` DOWNLEVEL GATE LINES NOW READ
  `CompilerOptions.defaultedTarget` — AND THE ENTRY'S OWN EVIDENCE WAS MISATTRIBUTED, SO THE
  FAMILY'S SIGN IS THE OPPOSITE OF WHAT IT SAID (round 945).** Round 944 filed this as a
  FALSE-NEGATIVE item on four pristine-only TS2488 rows the gates were assumed to suppress.
  Run at an EXPLICIT `es2015` and `esnext`, where those gates are wide open, we are **still
  silent for all three shapes** — so no gate suppresses them and they are an unimplemented
  iterability check, re-filed as **(CHK.22)**. The real family is a FALSE-POSITIVE one that
  neither instrument could see: the raw target's `ES3` zero value made a tsconfig naming no
  `target` collect **six** diagnostics pristine does not emit (TS1250, TS1501, TS1503,
  TS2659, TS2737, TS18045 — measured on one 14-line file, before vs after, with the explicit
  `es5` and `es2017` columns byte-identical). Oracle: **every** TS1250/TS1501/TS1503/TS2396/
  TS2659/TS2737/TS18045/TS2802 baseline in the pristine corpus comes from a fixture with an
  explicit `@target`. Three raw-target sites are KEPT with reasons in the KDoc (the two
  `target >= ES2015 || …` strict-mode determinations, which a flip makes unconditionally
  strict, and one per-fixture baseline pin). `docs/pristine-divergences.md` § 3d.1.

- [x] **(CHK.22) THE for-of / SPREAD OPERAND'S `[Symbol.iterator]()` RETURN IS NOW CHECKED —
  LANDED, round 946: 4 PRISTINE-ONLY TS2488 ROWS -> 0 WITH OURS-ONLY FLAT, THE FIRST ENTRY IN
  THIS ARC THAT MOVES ONLY THE FALSE-NEGATIVE COLUMN.** `spineCheckIterableOperand` /
  `iterableOperandFailure` reproduce tsc's `getIterationTypesOfIterableSlow` ->
  `getIterationTypesOfMethod("next")` chain for `for...of` and ARRAY-LITERAL spread: an
  OPTIONAL `[Symbol.iterator]?()` is TS2488 (tsc's `method && !(method.flags & Optional)`),
  and a zero-argument `[Symbol.iterator]()` whose RETURN type has no `next` is TS2488 + the
  related **TS2489 `An iterator must have a 'next()' method.`**. **THE CHECK IS
  POSITIVE-EVIDENCE-ONLY AND THAT IS THE WHOLE FP FIREWALL**: it fires only where the member
  is FOUND and provably broken and bails on everything else, so every bail is a false
  negative and no bail is a false positive — which is why a new diagnostic on the commonest
  construct in the language moved **zero** of ~13k corpus baselines. **`this` READS AS `any`
  HERE** (no polymorphic `this` type), so `[Symbol.iterator]() { return this }` — three of
  the four rows — needed `iteratorMethodThisReturn`, a bounded declaration read that answers
  the CARRIER, which is tsc's own answer rather than a widening. Sweep **297 -> 297
  ours-only, pristine-only 773 -> 769**, zero fixtures regressed; 8-profile grid `added=0
  removed=0`; suite **15,294 -> 15,324 / 0 / 3** with no baseline moved; `cost_gate.py`
  `typeOfExpr.calls +0.22%` (the per-operand type read — a reached-ness proof), rebaselined
  in the same commit. 11-arm ablation, every arm at `ran 63`.
  `docs/pristine-divergences.md` § 3e.

- [ ] **(CHK.23) THE MISSING HALF OF THE ITERABILITY CHECK — A TYPE WITH NO
  `[Symbol.iterator]` AT ALL IS STILL ACCEPTED, AND SO ARE FOUR OTHER CONSTRUCTS (round 946,
  scoped out with tsc's answer known for every row).** § 3e.3 of `docs/pristine-divergences.md`
  is the table. The big one is the MISSING-member case, which is where tsc's rule needs a
  complete model of what is iterable — arrays, strings, tuples, `Iterable<T>`, a constrained
  type parameter, every union of them and the built-in iterator families — and one gap in
  such a model is a false positive on `for...of`; note that under the EMBEDDED lib only
  `IterableIterator<T>` declares `[Symbol.iterator]` at all, so the model cannot be built
  from member lookup alone there. The rest, each already pinned SILENT in
  `IterableOperandProtocolTest`: an OPTIONAL `next` (tsc reports it; refused because no
  pristine baseline here measures it), an iterator type with an empty member table or a
  string index signature, `[Symbol.iterator]` requiring an argument on a CLASS (B438e owns
  only the object-literal spelling and its hard-coded TS2322 chain), and the four other
  constructs — CALL-argument spread, array DESTRUCTURING, `yield*` and `for await…of`, whose
  `IterationUse` flags carry different diagnostic families (TS2504 / TS2569 / TS2461).

- [ ] **(CHK.24) THERE IS NO POLYMORPHIC `this` TYPE — `return this` AND `(): this` BOTH
  RESOLVE TO `anyType` (round 946, measured).** `class C { m() { return this } n(): this
  { return this } }` makes `c.m()` and `c.n()` answer `any`, so every `this`-returning
  builder chain in a checked program is untyped and every rule that reads such a return
  bails. Round 946 needed exactly one question answered — "does the carrier have `next`" —
  and got it from `iteratorMethodThisReturn`, a bounded read of the member's DECLARATION;
  that helper is a stopgap and says so. The general fix is tsc's `getThisType` plus the
  `ThisType` type-node arm, and its blast radius is every method-chain return in the
  program, so it needs the 8-profile grid and the 630-fixture sweep.

- [ ] **(CHK.18) `t[k] = v` THROUGH A GENERIC INDEXED ACCESS IS TS2862 WHERE PRISTINE SAYS
  TS2322 — 3 ROWS, A CODE DIVERGENCE RATHER THAN A FALSE POSITIVE
  (`keyofAndIndexedAccessErrors` lines 140-142, round 943).**
  `function test1<T extends Record<string, any>, K extends keyof T>(t: T, k: K) { t[k] = 42 }`:
  we refuse the WRITE (`Type 'T' is generic and can only be indexed for reading`), pristine
  permits it and rejects the VALUE (`Type 'number' is not assignable to type 'T[K]'`). tsc's
  rule reads the receiver's CONSTRAINT for a writable index signature before refusing; ours
  does not. Both compilers error at the same position, so this is FORM under
  `docs/logical-parity.md` § 2 — but the form is a different diagnostic identity, and the
  underlying gate is a real modelling gap that would show as a false POSITIVE the moment a
  program writes through a constrained generic index legally.

- [x] **(CHK.19) A FUNCTION-BODY TYPE ALIAS IS NOT BOUND, SO THE LIB'S `Omit` WON — 1 OURS-ONLY
  TS2314 -> 0 (`conditionalTypes1` line 297, round 945).** `getTypeParamInfo` is a whole-program,
  NAME-keyed scan with no node context, so a block-scoped `type Omit<T>` (CLAUDE.md's B83.5: the
  binder never binds a declaration nested in a function body) was invisible and the LIB's
  two-parameter `Omit` answered the arity question. Closed with round 748's
  `lexicalTypeSymbolForNode` shape one declaration kind over — a name gate computed in the SAME
  sweep that already censuses block-scoped enums, then an ancestor walk over the INV.2(c)
  `lexicalScopes` reading `scope.symbols` ONLY. **It does not re-open the INV.3 minefield the
  B83.5 entry warns about, and the reason is structural**: `declareLexical` skips any name the
  main binder already bound in that container, so a scope-space hit can only be a declaration the
  conventional tables do not have. Measured: sweep **298 -> 297**, 0 added, pristine-only FLAT,
  zero fixtures regressed; 8-profile grid `added=0 removed=0`; `cost_gate.py` moved **−24
  `globals.lookups` (−0.003%)** — tsc's own sources carry block-scoped generic aliases
  (`PropOfRaw<T>` in commandLineParser.ts among them) that now answer locally instead of running
  the global scan, and the grid proves no verdict changed. **STILL OPEN, and named here rather
  than left implicit**: `outerTypeParamNames` is supplied by the TypeAliasDeclaration caller only,
  so a CLASS's or INTERFACE's own type parameters are still `emptySet()` and
  `interface I<T> { [k: T]: string }`-style shapes keep the older answer.

- [ ] **(CHK.20) VARIADIC TUPLE TYPES ARE UNMODELLED — 30 OURS-ONLY ROWS, THE SINGLE
  LARGEST FAMILY LEFT, AND IT IS A FEATURE RATHER THAN A DEFECT (`variadicTuples1`, round
  943).** `getTupleType` maps a `RestType` element through `is RestType ->
  getTypeFromTypeNode(elem.type)` — the arm a PLAIN element gets — so **`[...T]` is built as
  the one-element tuple `[T]`**. Three lines reproduce it:
  `function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports `Type '[T]' is not
  assignable to type 'T'`. What is missing is TypeScript 4.0's variadic tuples in full: a
  tuple type with a variadic/rest element, its normalisation, the three relation rules the
  fixture's own section header states ("for a generic type `T`, `[...T]` is assignable to
  `T`, `T` is assignable to `readonly [...T]`, and `T` is assignable to `[...T]` when `T` is
  constrained to a mutable array or tuple type"), `keyof` over one, spread-argument arity,
  and inference into a leading/trailing rest (the fixture's whole `curry` section). M3-scale;
  do NOT attempt it as a bounded rule.

- [ ] **(CHK.13) THE STRICT-BY-DEFAULT CONVENTION IS THE LARGEST *SYSTEMATIC* DIVERGENCE
  LEFT — 46 OURS-ONLY ROWS (42 by code, plus the four round 943 found wearing TS2683 /
  TS7019 / a `strictNullChecks` TS2322), AND IT IS AN OWNER DECISION, NOT A FIX (round
  941, re-sized round 943).** TS2564 / TS2454 / TS7010 fire in this compiler unless `@strict: false` is
  EXPLICITLY set (`Checker.kt`'s dispatch reads `!options.strictExplicitlyFalse`), where tsc
  requires `strict` (or the individual flag) to be ON. A real project with no `strict` in
  its tsconfig therefore gets `Property 'x' has no initializer and is not definitely
  assigned in the constructor` from us and nothing from tsc — `keyofAndIndexedAccess` alone
  is 17 rows for four plain `name: string;` class fields. Invisible to the corpus, whose
  fixtures set the directive. **Do not "fix" it without the owner**: the convention is
  load-bearing for the generated suite's expectations.

- [ ] **(CHK.7)(ii) A COMPUTED KEY'S *EXPRESSION* IS NEVER CHECKED, SO AN UNRESOLVABLE
  `[Symbol.x]` BECOMES A REQUIRED MEMBER — RE-MEASURED round 940 AND IT IS A MODELLING
  CHANGE, NOT A NAMING ONE.** `symbolProperty52`: pristine reports **TS2339 `Property
  'nonsense' does not exist on type 'SymbolConstructor'` TWICE** — once at the KEY inside
  `var obj = { [Symbol.nonsense]: 0 }` and once at the later `obj[Symbol.nonsense]` — and
  gives the literal NO such member, so `obj = {}` is silent. We emit **neither** the key's
  TS2339 (we get only the element-access one) **and** a TS2741
  `Property '[Symbol.nonsense]' is missing in type '{}'`. So the FP and the FN have ONE
  cause: `computedSymbolKey` invents `"[<dotted>]"` as a STRUCTURAL placeholder (round 723,
  and it is what makes tsc's own `Set<TElement>` literal's `[Symbol.iterator]` match) with
  nothing checking that the key expression resolves at all.
  **TWO SHAPES, and the cheap one is refused with a reason.** (a) The cause-level fix is
  tsc's `checkComputedPropertyName`: check the key EXPRESSION, emit TS2339/TS2464, and
  declare no member when it errors. That also closes pristine's TS2464 across the whole
  `computedPropertyNames*_ES6` set, which the round-939 sweep records as one of the largest
  ours-*missing* families. (b) Narrowing `computedSymbolKey` to keys whose `Symbol.<name>`
  is a REAL `SymbolConstructor` member is cheaper and is REFUSED as written: a hardcoded
  well-known list drifts from the lib and would DELETE a member for any symbol the list
  lacks — a TS2741 false positive in the other direction — while asking the type system
  means a member-resolution call from inside `getTypeOfObjectLiteral`, i.e. exactly the
  round-935 ambient-input hazard one layer down. **The whole population is 1 FP row in an
  ungated fixture on a program pristine already rejects twice; the prize is the FN.**

- [ ] **(CHK.7)(iv) STRING/NUMERIC MEMBER-NAME EQUIVALENCE IS MISSING IN THE *TYPE-LITERAL*
  SCAN ONLY, AND IT IS A FALSE **NEGATIVE** — round 939's entry has both the direction and
  the scope wrong.** Re-measured on `numericStringNamedPropertyEquivalence`: pristine emits
  7 rows, we emit 4, **ours-only is ZERO**. The CLASS scan already normalizes
  (`memberKey`'s `normalizeNumericKey`, so line 6 matches) and the INTERFACE scan matches
  lines 10/12 by accident — `1`'s text is already canonical. What is missing is
  `var a: { "1": number; 1.0: string }`: `checkDuplicateInterfaceMembers` names a numeric
  member through `getMemberNameText`, which returns the RAW text, so `"1"` and `1.0` do not
  collide and pristine's **TS2300 x2 (16,5 / 17,5) + TS2717 (17,5)** are all lost.
  **THE FIX IS ONE LINE PLUS A DISPLAY SPLIT, AND THE SPLIT IS THE REAL WORK**: group by
  `normalizeNumericKey`, but pristine prints **two different names for the same member** —
  TS2300 says `'1'` (tsc's binder message uses the SYMBOL name) and TS2717 says `'1.0'`
  (the checker's `declarationNameToString` of the later declaration, and its related TS6203
  says `'1.0'` too, at the position of the `"1"` member). `PropInfo` carries one `display`
  today, so it needs a second field. Low blast radius (a numeric member name whose text is
  not already canonical, in an interface or type literal) and it can only ADD diagnostics
  pristine already has — but it is an FN, so it does not move the v1 zero-FP metric.

- [x] **(CHK.4) THE QUALIFIED, TYPE-ANNOTATION AND WELL-KNOWN-SYMBOL ROUTES — LANDED,
  round 936, both directions, and the residue is re-scoped as (CHK.5) above.** Three
  capabilities, each a false POSITIVE in the supply direction and a false NEGATIVE in the
  excess one at the same time. (i) QUALIFIED keys — `NS.K`, `NS.Inner.IK`, a dotted
  `namespace A.B`'s const, a MERGED namespace's second block, and a const-or-plain ENUM
  member declared inside a namespace: all bind in tsc, all were TS2741 here and silent
  there. Resolved by descending `ModuleBlock` statements SYNTACTICALLY, because
  `currentFileLocals` is ambient and round 935 measured what that costs a member name; the
  one symbol-table consult left is the enum leaf, whose VALUES are in the binder's frozen
  tables and nowhere in the AST. (ii) The TYPE-ANNOTATION spellings — a no-substitution
  template-literal TYPE and a TYPE ALIAS to a literal, including a chain. **`TemplateLiteralType`
  is not a structured node in this parser** (B65.1: empty spans, the whole raw slice in
  `head.rawText`), so `templateSpans.isEmpty()` is true for a SUBSTITUTING one too and
  `head.text` answers `""` — a name matching no member, which reached the excess check as a
  real member on the first build. The raw text is the only discriminator that exists.
  (iii) WELL-KNOWN SYMBOLS in the excess check, which required one embedded-lib line:
  `IterableIterator<T>` did not declare the `[Symbol.iterator]()` member the real lib
  declares, so a literal supplying it against an `IterableIterator`-extending interface
  read as excess (the round-456 pin, and the ONLY red the suite produced). Refused, with
  tsc agreeing on every row: a widened namespace `let`, a substituting template type, an
  alias to a union, and — measured over seven of them — every computed key tsc cannot
  late-bind, which is why the well-known route demands the receiver be `Symbol` with no
  local binding of that name rather than re-admitting `computedSymbolKey` generally.
  28 pins, 13-arm ablation. The `NS.K` FP is gone; the SYMBOL axis verdict is that the
  well-known half was SMALL and the `unique symbol` half is MODELLING — see (CHK.5)(d).

- [x] **(CHK.3) LATE-BOUND COMPUTED KEYS — LANDED, round 935, BOTH DIRECTIONS IN ONE
  COMMIT. One missing capability was a false POSITIVE on one side and a false NEGATIVE on
  the other, and the round's product is that **tsc's own rule is NOT PORTABLE AS WRITTEN**.**
  Supply: `const K = "p"` / `const enum E { P = "p" }` + `{ [K]: 1 }` / `{ [E.P]: 1 }`
  satisfy a required `p` in tsc and were TS2741 here. Excess: the same keys spelling a name
  the target LACKS are TS2353 in tsc, named as WRITTEN, and were silent here. Both are now
  parity, plus every row the table was extended with before designing: a const ALIAS chain,
  a `let` with a literal ANNOTATION (const-ness is not the criterion), a `declare const`, a
  const whose literal INITIALIZER beats a union annotation, a plain (non-`const`) string
  enum, a NUMERIC enum member and a numeric const (named by the VALUE's canonical string,
  so `1e3` is "1000"), a body-local const and an inner const SHADOWING an outer one.
  Refused, with tsc agreeing on every one: a widened `let`, a genuine literal UNION, a plain
  `symbol`, a bare type parameter, a substituting template, and an AMBIENT non-`const` enum
  member with no initializer (round 746's opaque rule turns out to be tsc's own answer).
  **THE FIRST DRAFT PORTED `isTypeUsableAsPropertyName` LITERALLY — the key expression's
  TYPE — AND IT MEASURED AS A NAME THAT IS NOT A FUNCTION OF THE PROGRAM**: a FILE-LEVEL
  un-annotated `const K = "p"` answers the literal in the assignability pass and the widened
  `string` in the pass behind TS2339, so `const obj = { [K]: 1 }; obj.p` emitted the correct
  TS2322 **and** `Property 'p' does not exist on type '{}'` in ONE compile — round 933's
  two-extraction-sites signature reached through ambient state (round 911) instead of through
  a second `when`. The landed resolution is SYNTACTIC (an enum member's VALUE via
  `enumMemberEntries`; otherwise the declaration a name resolves to, by an innermost-first
  walk of the enclosing statement lists — `lookupPerFileForNode` cannot see a body local at
  all, B83.5, and a scope-chain consult would be ambient again), and the pin that fails if
  the type route returns asserts the two passes AGREE, because each pass alone is green.
  `lateBoundComputedKeyName` is asked BEFORE `computedSymbolKey` at all three naming sites,
  which is also what retires round 934's arm-A4 false positive at its source rather than by
  exclusion. 25 pins, 8-arm ablation (every arm with a uniquely-its-own failure). What is left is (CHK.4) above.

- [x] **(CHK.2) A COMPUTED OBJECT-LITERAL KEY NEVER REACHED THE EXCESS-PROPERTY CHECK —
  LANDED, round 934. A false NEGATIVE in every position, from ONE name-extraction `when`,
  and the diagnostic was being computed in full before it was dropped.** Round 933 measured
  the row and left it: ``{ p: 1, [`zz`]: 2 }`` and `{ p: 1, ["zz"]: 2 }` against
  `interface Opt { p?: number }` are TS2353 in tsc 7.0.2 and were silent here. Extended
  before designing, it is larger: a BARE numeric key `{ 7: 2 }` escapes too (so the omission
  is not about computed keys at all), and every position escapes together — `satisfies`, an
  ARGUMENT, a `return`, a NESTED literal under a computed key, a computed METHOD name.
  **The cause is the exact mirror of (CHK.1)'s**: `getTypeOfObjectLiteral` had named all of
  those keys for years, so the source TYPE carried the member and `checkExcessProperties`
  judged it excess correctly — and then looked for the AST node that declared it with a
  `when` knowing only `Identifier` and `StringLiteralNode`, found nothing, and emitted
  nothing. The lookup is now ONE shared predicate (`objLitElementMemberName`), so the type
  builder and the excess check cannot disagree about what an element names.
  **THE ROUND'S REAL PRODUCT IS THE TWO NEAR MISSES, EACH OF WHICH TURNED THE FN INTO AN
  FP ON A ROW ROUND 933's TABLE DOES NOT CONTAIN.** (i) Admitting a numeric key exposed a
  TARGET-side gap that could not matter before — `collectTargetPropertyNames` bails on a
  STRING index signature and knows nothing of a NUMERIC one — so `{ [7]: 2 }` against
  `{ [k: number]: T }` was reported where tsc is silent. (ii) Naming the key with
  `computedLiteralKey ?: computedSymbolKey` (the obvious delegation) reported `'[E.P]'` for
  `const enum E { P = "p" }` + `{ [E.P]: 1 }`, which tsc late-binds to the existing `p` and
  accepts — **`computedSymbolKey` INVENTS `"[<dotted>]"` so a well-known-symbol member can
  match structurally (round 723); it is not a claim about what the key spells and cannot
  tell `Symbol.iterator` from `E.P`.** Both are guards with a discriminating negative
  control apiece. **So the line is round 933's line in the other direction: the excess check
  acts on a computed key exactly when the key is a LITERAL spelling one fixed name**; every
  key needing the key's TYPE stays out in BOTH directions and is (CHK.3). **The message FORM
  is matched rather than recorded** — tsc keeps the delimiters (`'["zz"]'`, `''zz''`) and
  squiggles the whole written key, the span is in hand, and no ACTIVE corpus test has a
  delimited excess key (ten of the eleven such baselines are not generated; the eleventh
  belongs to another emitter). 20 pins + one round-933 pin rewritten to tsc's own answer
  (it asserted a TS2741 that tsc does not emit); six-arm ablation, all reached, four with a
  uniquely-their-own failure, four pins recorded as undiscriminated rather than claimed.
  **Every profile instrument is a CONTROL and it was measured**: across all eight profiles'
  1,249 `.ts` files an object-literal computed key matches 8 times — all eight the same
  destructuring pattern — so `+0.00%` and `added=0 removed=0` are the expected answers.

- [x] **(CHK.1) A BACKTICK-QUOTED COMPUTED MEMBER KEY NAMES A MEMBER — LANDED, round 933.
  Three FALSE POSITIVES tsc does not have, from ONE missing `when` arm, in a spelling the
  whole tsc corpus never uses.** Round 932 recorded, in passing, that `` { [`p`]: v } ``
  did not supply a required `p`. Measured against `tsc 7.0.2` this round it is three, not
  one: the object-literal supply (TS2741), an INTERFACE's own `` [`ip`] `` member (TS2339)
  and a CLASS's own `` [`cp`] `` member — the last of which resolved for the assignability
  check and simultaneously FP'd TS2339 **in one compile**, because the type-building site
  and the class-AST walker are two independent name extractions and only one of them had
  been widened. **The fix is `computedLiteralKey` growing a `NoSubstitutionTemplateLiteralNode`
  arm, plus `classMemberNameText` DELEGATING to it instead of re-spelling its `when`** — the
  archive's B451 entry says outright that this family has >= 5 independent extraction sites
  and that widening one silently leaves the others FP'ing, and the class row is what that
  looks like from the outside. **What stays refused, measured and pinned in the positive:**
  a SUBSTITUTING template (`` [`p${x}`] ``) names no fixed member and is TS2741 in tsc too.
  **What stays OPEN and is NOT pinned** (round 765's law — a known-open gap is a countdown,
  not a guard), both with tsc's answer measured: `{ [K]: v }` / `{ [E.P]: v }` supply nothing
  here and do in tsc — that needs the key's TYPE, i.e. late binding, not a spelling; and the
  EXCESS-PROPERTY direction never sees a computed key at all, so `` { [`zz`] } `` AND
  `{ ["zz"] }` both escape TS2353 where tsc emits it (a false NEGATIVE, symmetric across the
  spellings, untouched by this round). tsc additionally renders such a key's name WITH its
  delimiters in the TS2353 text (`'"zz"'`, `` '[`zz`]' ``) where we print the bare name — a
  form divergence, noted not acted on. 11 pins (`TemplateComputedMemberKeyTest`, every
  backtick row beside its quote-spelled B451 control); three-arm ablation, all reached.
  **Every profile-based instrument is STRUCTURALLY BLIND here and that is measured, not
  assumed**: the eight tsc profiles contain ZERO backtick-quoted computed member keys (the
  only `` [`…`] `` matches are array literals), which is why `cost_gate.py` reads +0.00%
  on all 20 counters and the 8-profile grid reads `added=0 removed=0` — both are CONTROLS
  here, and the corpus plus the new pins are the gate.

- [x] **(API.17) A COMPUTED OBJECT-LITERAL KEY `{ ["p"]: v }` — LANDED, round 932; § 14's gap 2,
  and the LAST silent shape anywhere in this API.** Round 930 measured a computed key as
  "usually reported" — `WOULD_NOT_COMPILE` where the contextual member is REQUIRED,
  `OCCURRENCES_INCOMPLETE` where the literal has no contextual type — and SILENT in exactly
  one shape: an OPTIONAL member, where stranding the key costs no diagnostic, so the applied
  rename compiled clean with the old name still spelled in the literal and no gate in this
  repository could see it. tsc 7.0.2 counts the key as a reference, hovers it as the member,
  navigates to the member's declaration and renames it (measured, six spans on a fixture
  carrying one). **The landing is a POPULATION change and one predicate**: `occurrenceNodes`
  now sweeps every literal for which `isMemberPosition && isMemberNameLiteral` holds, which
  subsumes (API.9)'s element accesses, (API.16)'s templates, `{ "p": v }`, `{ ["p"]: v }`,
  ``{ [`p`]: v }`` and a class's or an interface's `["p"]` — so the set a caret may land in,
  the set a sweep reports and the set a rename must edit are ONE set by construction rather
  than three definitions kept in step. **A literal the API cannot RESOLVE still belongs in it**:
  seen-and-unplaced is a stated `OCCURRENCES_INCOMPLETE` conflict, unseen is a silent miss.
  **`{ [K]: v }` is deliberately out** — it spells no fixed name and tsc reads it as a
  reference to the binding `K` alone (measured); the asymmetry with the element-access arm is
  stated in `SyntaxRoles.isMemberPosition`, because calling it a member position flips the
  completeness net's polarity for every ordinary `const` rename. **THE ROUND'S SECOND HALF WAS
  AN AUDIT FINDING**: `typeCaptureReportedType` recorded an object-literal key's TYPE as
  deliberately not closed *because the contextual type is walk-scoped state a capture cannot
  read* — and (API.10) built `typeCaptureContextualType`, a purely syntactic walk, one round
  later. Nobody came back. Measured before this round, EVERY key — computed or bare —
  answered `any`, or the COLLIDER's type where a same-spelled binding existed. Closed by
  `typeCaptureObjectLiteralKeyType`, the contextual member's type with the key's own value as
  the fallback, which is what tsc reports in both shapes. +18 pins, four inverted; ten-arm
  ablation. `docs/language-service.md` §§ 8, 9, 10b, 10d, 14.

- [x] **(API.16) A MEMBER NAMED BY A TEMPLATE ELEMENT ACCESS — LANDED, round 931; § 14's
  gap 6, the ONE genuinely silent gap in this API, is closed.** ``o[`p`]`` was outside
  (API.9)'s occurrence population, so `referencesAt` / `documentHighlightsAt` / `renameAt`
  missed it AND SAID NOTHING: round 930 proved it end to end — the rename applies, the
  template keeps spelling the old name, and the applied program has ZERO diagnostics, so
  no gate this API has can see it. tsc 7.0.2 counts it as a reference, renames it, hovers
  it as `(property) I.p: number` and completes inside it (all measured). It is now an
  ordinary occurrence in every one of those queries, with the edit covering the TEXT and
  **not the backticks** — round 926's rule one delimiter over, and the same measured span
  tsc writes. **Round 929's completion refusal is CASHED rather than overruled**: it
  refused for exactly one reason, that the sweep could not find such a member, and the
  sweep now can — the two still share ONE enumeration, so they cannot drift apart about
  what a member name is. **REFUSED, and it is a NODE-CLASS boundary rather than a
  judgement**: a template carrying a SUBSTITUTION (``o[`p${x}`]``) spells no fixed name,
  so it is neither an occurrence nor an obstacle and its caret renames nothing — which is
  tsc's answer there too (zero references, `prepareRename` refuses). **The one place a
  second mechanism was needed is HOVER**: this compiler's element-access typing keys a
  named member off a STRING literal, so routing the template through the access would
  have answered `any` — the (API.15) violation one round later — and the member is
  resolved through the receiver instead. +8 pins, two inverted; seven-arm ablation, five
  distinct red sets plus one MEASURED-REDUNDANT guard with its reach proved by a
  narrowing twin. `docs/language-service.md` §§ 8, 9, 10a, 10b, 10d, 14.

- [x] **(API.15) AN ENUM MEMBER'S DECLARATION NAME REPORTS `any` — LANDED, round 931; the one live violation
  of *prove to offer* in this API.** Measured round 930 on four shapes (plain, valued,
  `const enum`, string enum): `quickInfoAt` on the `Alpha` of `enum Plain { Alpha }`
  answers `QuickInfo(displayString = "any")`, where tsc 7.0.2 answers
  `(enum member) Plain.Alpha = 0` and where our own USE site already answers
  `Plain.Alpha`. Not an absent answer — a plausible wrong one, which is the failure mode
  (BUG.4) and (API.11) each closed one position over. **The mechanism is known and the fix
  is one leg**: `Checker.typeCaptureMemberDeclarationType` resolves a declaration name
  through its OWNER and then asks `typeCaptureCollectMembers` for the member — and an
  enum's own type is a member-LESS `Type.Object` (CLAUDE.md), so the collection finds
  nothing, the leg returns null and the fallback types the identifier as a free name.
  What it needs instead is `getDeclaredTypeOfEnumMember`, which is what the use site
  already reaches. Pinned as a DEFECT by `LanguageServiceStateTest`'s `an enum member's
  declaration name reports the WRONG type and its use reports the right one`, so closing
  it must edit that test, § 8 and § 14's gap 7 together. Definitions and references for
  the same position are already complete; only the TYPE is wrong.
  **LANDED**: `typeCaptureEnumMemberType`, eight lines, minting through
  `getDeclaredTypeOfEnumMember` — and the measured product is that the obvious
  alternative does NOT work (`getTypeOfSymbol` on an enum member symbol answers `any`,
  arm A2). Five shapes report the member's type, the same instance the use site
  reports; tsc's extra decoration is the member's VALUE, which this API deliberately
  does not render (§ 8). The defect pin is inverted in place.

- [x] **(API.12) COMPLETION INSIDE `o["` — LANDED, round 929; the last query that did not
  answer an element access.** A caret in the string of `o["…"]` is a MEMBER caret whose
  receiver is the expression before the `[`, decided by ONE classifier
  (`SourceIndex.stringMemberAnchorAt`) over (API.9)'s OWN enumeration, so "a string literal
  is a member name only in an element-access position" is one predicate shared by the
  occurrence sweep and the anchor. **Zero core changes**: the member enumeration is round
  917's, so the union rule, the accessibility filter and the `this`/export-table legs came
  for free. **The span is the literal's TEXT, quotes excluded** — tsc's own measured edit
  range and the same span a member rename writes into — and a member whose spelling is not
  an identifier (`"has space"`, `"1abc"`) is offered, which is the reason element access
  exists. **THE ROUND'S PRODUCT is that `StringLiteralNode.isUnterminated` is FALSE for a
  lone `"`** (the parser compares the raw text's last character to its first), so `bag["` at
  end of file — the state a completion request is normally made in — parsed as a terminated
  empty string and used to answer FREE_NAME with the whole lexical scope offered INSIDE the
  string; the anchor checks the arithmetic as well as the flag. **Deliberately refused**, each
  measured against tsc: a TEMPLATE `` o[`p`] `` (which tsc completes — refused because
  (API.9)'s population is string literals only, so a member written that way is one a rename
  cannot find), a caret AT the opening quote, an indexed-access TYPE, and a string completed
  from its CONTEXTUAL type. **That last measurement found a SILENT GAP one layer down: tsc
  counts `` o[`p`] `` as a reference**, so this API's references and rename miss it and do not
  say so — now § 14's gap 6. +26 pins, nine-arm ablation (five distinct non-empty sets, three
  MEASURED-REDUNDANT guards and a two-mistake REACH CONTROL), all gates green.
  `docs/language-service.md` §§ 10a, 14.

- [x] **(API.11) A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL — LANDED, round 928;
  the single largest thing refusing a member rename is gone.** A member's own declaration
  name — an interface's, a class field's, a method's, an accessor's, a static's, a
  `#private`'s, a type-literal member's, an enum member's — is bound by no scope and has no
  receiver, so it resolved to nothing: `definitionsAt` answered empty, `quickInfoAt` answered
  `any` (or the COLLIDER's type, (BUG.4) one position over), `referencesAt` answered empty for
  a member never used, and `renameAt` refused whenever another interface declared the same
  member NAME. It now resolves through its **OWNER**, the receiver's exact dual — the fourth
  resolution mechanism (`Checker.typeCaptureMemberDeclarations`). **THE HAZARD THE ITEM NAMED
  IS BIGGER THAN "resolve it to itself"**: round 884's `mergeSingleSymbol` ADOPTS, so a member
  declared in two merged `interface` blocks is one symbol carrying only the SECOND block's
  declaration — measured — and the whole list has to be reconstructed from the OWNER symbol's
  own declarations, each a container. A merged declaration, an OVERLOAD set and an ACCESSOR
  PAIR are therefore one group from any of their declaration names, in every query. Deliberate
  exclusion, in the conservative direction: an object literal's own METHOD, which is outside
  (API.10)'s key leg and stays a loud refusal. +16 pins, two changed meaning in place, nine-arm
  ablation (seven distinct sets; two arms measured REDUNDANT with their reach proved by other
  arms), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9, 10b, 10d, 13, and the new
  **§ 14, State of the API**.

- [x] **(API.10) ONE SPAN, TWO SYMBOLS — LANDED, round 927; the LAST of round 922's five
  refusals.** A contextually typed object-literal KEY (`{ p: v }`) and both SHORTHANDS
  (`{ p }`, `const { p } = o`) are occurrences of the member the literal's CONTEXTUAL
  type supplies. **The capture still files ONE answer per span** — round 926 read that
  as the structural obstacle and it is not: tsc's relation between a shorthand's two
  symbols is ASYMMETRIC (the member's group CONTAINS the token; a caret ON the token
  answers the LOCAL's group alone), so what was missing was a ROLE.
  `CapturedDefinition` now carries three declaration sets differing in which of
  NAVIGATION / SEED / MEMBERSHIP they hold: `locations` all three, `related` seed +
  membership (the heritage edge, and now an object-literal key's OWN property),
  `shorthand` navigation + membership and deliberately NOT seed. The contextual type is
  computed by a SYNTACTIC walk OUT of the literal (`Checker.typeCaptureContextualType`,
  the dual of round 926's `typeCaptureDestructured`) covering eleven positions read out
  of tsc 7.0.2, because the checker's own contextual type is walk-scoped and `cpaCtxAt`
  stops at every statement edge. `renameAt` expands a shorthand in whichever direction
  it was reached from — `{ renamed: p }` vs `{ p: renamed }`, the round's discriminator,
  since both compile and both are one edit. **Still refused**: a second declaration of
  the same member name (pre-existing, and the named successor), a shorthand whose member
  cannot be placed, and a computed key. +19 pins, ten-arm ablation (nine distinct sets;
  A3/A8 share one because the round-925 verification refuses exactly what a wrong
  expansion would write), `cost_gate.py` +0.00%. `docs/language-service.md` §§ 8, 9,
  10b, 10d, 13.

- [x] **(API.9) THE MEMBER OCCURRENCE SET — LANDED, round 926; TWO OF THE THREE KINDS CLOSED
  OUTRIGHT, THE THIRD CLOSED FOR A DECLARED HERITAGE EDGE AND STILL REFUSED FOR A CONTEXTUAL
  ONE.** Round 925 measured a member's occurrence set at 2 spans against tsc's 5 and named the
  three missing kinds. Closed: **(1) a binding element's `propertyName`** (`const { p: local }`
  — a receiver question; the pattern's source is the annotation or initializer one to three
  levels up, `Checker.typeCaptureDestructured`), **(2) an element access `o["p"]`** (a
  POPULATION question; `SourceIndex.occurrenceNodes()` is `identifiers()` plus the string
  literals that name a member, and the edit span is the text BETWEEN the quotes), and **(3) an
  IMPLEMENTOR's member** via `CapturedDefinition.related` — a DECLARED heritage edge, computed
  per OCCURRENCE, which is what makes a `this.p` inside an implementor part of the interface's
  group. **Still refused: a contextually supplied key, and the binding SHORTHAND `const { p }`,
  for the same structural reason** — one span carrying two symbols, which a capture filing one
  answer per span cannot express. `referencesAt`, `documentHighlightsAt` and `renameAt` improve
  together because the set is wired once; `definitionsAt` deliberately does NOT follow the
  heritage edge, because tsc's own go-to-definition on an implementor's member answers that
  member. +20 pins, ten-arm ablation, `cost_gate.py` +0.00%, population 381,670 -> 381,672 on
  tsc's own sources. `docs/language-service.md` §§ 9, 10b, 10d.

- [x] **(API.8) RENAME — LANDED, round 925.** `RenamePlan(oldName, newName, files, refusal,
  conflicts)` / `FileRename(fileName, edits)` / `RenameEdit(start, end, newText)` /
  `RenameConflict(kind, fileName, start, end, detail)` + `RenameRefusal` (11) and
  `RenameConflictKind` (5); **`Project.renameAt(fileName, offset, newName)`**. **ZERO core
  changes** — the whole feature sits above the compiler on (API.5)'s sweep and (API.7)'s parent
  ascent. **STEP 1 WAS tsc ITSELF, and it decided three designs**: `scripts/lsp_rename.py` drives
  `tools/tsgo-7.0.2/lib/tsc --lsp -stdio`'s `textDocument/prepareRename` + `rename` over a
  22-caret fixture and prints the resulting TEXT, so `{ p }` -> `{ p: newName }`, `const { z }`
  -> `{ z: newName }` (local) vs `{ newName: z }` (property), and the lib refusal's exact wording
  were READ rather than reasoned. It also showed **two places to do BETTER than tsc**: tsc
  validates neither the new name (`const class = 1`, `const 1bad = 1`) nor collisions (it writes
  a second `const useZ` beside the first). **THE OCCURRENCE SET WAS MEASURED BEFORE ANY CODE and
  it is NOT complete for members** — on the same fixture tsc's member rename edits 5 spans and
  ours resolves 2, missing a binding element's `propertyName`, an `o["p"]` (a string literal, so
  outside the identifier population by construction) and an IMPLEMENTOR's member (a different
  symbol here). So members are not planned around, they are **refused with the evidence**:
  a spelling scan is used as a SAFETY NET — never as the answer — and an identifier spelling the
  old name that is neither in the group nor resolved elsewhere is a conflict. **The position
  split inside that net is load-bearing**: a member declaration name resolves to nothing, so
  without it an `interface I { p }` anywhere would refuse renaming an unrelated local `p`.
  **THEN THE PLAN IS VERIFIED BY APPLYING IT AND COMPILING AGAIN** (a scratch `OverlayVfs` around
  the project's own, so nothing is observable): it must re-read, it must add no diagnostic
  (**the COLLISION check**), and every renamed occurrence plus every identifier that ALREADY
  spelled the new name must resolve to exactly what it resolved to before (**the CAPTURE check** —
  renaming a file-level `a` to `b` where a body holds its own `b` compiles, produces no
  diagnostic anywhere, and means something else; arm A4 is the only thing that sees it).
  **ONE MEASURED DESIGN CORRECTION**: the expectation for a renamed occurrence is its OWN prior
  answer, not the seed — demanding the seed reports this API's own blind spot (a member's
  declaration name resolves to nothing) as a change of meaning, and refused three correct member
  renames before it was fixed (arm A10). **DIVERGENCE FROM tsc, stated**: a bare `export { p }` /
  `import { p }` is replaced PLAINLY where tsc expands to `newName as p` — our identity crosses
  the alias hop, so the local and the export are one symbol and the whole group renames together;
  expanding would make `export { p }` behave differently from `export const p`. **REFUSED, each
  with a reason**: a declaration in a library, an ALIASED import (`import { a as b }` — one new
  name cannot spell two things, and tsc picks by caret because it has two symbols), an unresolved
  import, a caret on either half of an `as`, a reserved or malformed new name (**no build**), and
  a member whose set cannot be shown complete. **PINS +35** (`-project` 390 -> 425; core UNCHANGED
  at 14,341) — 14 parse-only shape pins written FIRST. THE DISCRIMINATOR is the shorthand, asserted
  as the exact resulting TEXT of both lines, because a plain rewrite passes every count-based
  assertion and renames the object's key. **APPLY-AND-RECHECK** pins apply the plan through
  `updateFile` and assert the diagnostics are byte-identical — an independent oracle of the
  verification `renameAt` runs internally. **TWELVE-ARM ABLATION**, one mistake at a time, anchored
  replacements with an asserted occurrence count, restored from a sha256-verified snapshot.
  **GATES**: suite 14,865 -> **14,900 / 0 failures / 0 errors / 3 skipped = exactly the +35**;
  `cost_gate.py` **+0.00% on all 20 counters** (a control: no core change);
  `huge_methods.py --fail-over 0` clean on core and on `-project` explicitly. **MEASURED ON tsc's
  OWN SOURCES**: renaming `SyntaxKind` in `types.ts` produces **9,827 edits across 49 files** in
  23.9-24.5 s warm (against `referencesAt`'s 10.6-16.0 s); `createTypeChecker` is 3 edits in
  13.3-14.3 s. `docs/language-service.md` § 10d; harness `RenameCostMain`.

- [x] **(BUG.4) Quick info on a MEMBER NAME reports the wrong type, for every receiver — FIXED,
  round 924.** The item said it reports `any`; **measured against tsc 7.0.2's own LSP it reports
  the type of whatever unrelated binding shares the member's spelling**, and `any` only where
  nothing does — 16 of 23 wrong member positions read a collider, 6 read `any`, one was right by
  coincidence. **The fix is tsc's own rule**: `getTypeOfSymbolAtLocation` moves off the right-hand
  side of a property access ONTO THE ACCESS, so the type of the `p` in `o.p` is the type of `o.p`
  — and a probe of exactly that, measured before any design was committed, was already correct for
  the generic instantiation, the inherited member, the union receiver, the type-parameter receiver,
  the static side, the enum and namespace members and the flow-NARROWED member, because
  `computeRawTypeOfPropertyAccess` implements all of them. So the landed fix contains **no member
  walk**: the brief's carrier route was the right instinct at the wrong altitude, and a member-table
  read is exactly what arm A2 shows failing (the two generic pins plus narrowing). The ONE receiver
  needing (API.3d)'s carrier is `this`/`super`, which are plain identifiers in this parser and type
  as `any`; the leg is ADDITIVE, so where it cannot decide the access answers `any` rather than a
  wrong name. **NEIGHBOURS CASHED**: an element access `o["p"]` (the caret is on the literal, whose
  own `string` made the old answer right only by coincidence) and a qualified TYPE name `N.T`
  (through the export table). **STILL REFUSED**: an object literal's own key, on round 922's
  unchanged contextual-type ground. **THREE tsc DIVERGENCES named rather than asserted away**:
  `this` in a static member (`typeof C` is unmodelled), an object-literal member's literal widening,
  and a type rendered under a synonymous alias.

- [x] **(BUG.3) A caret on `this.` inside a NESTED ARROW answers NO members — FIXED, round 923.**
  **THE LAYER QUESTION WAS THE ITEM, AND THE ANSWER IS CAPTURE-ONLY.** Settled by MEASUREMENT before
  any code: a 24-line fixture covering `this` in a method, an arrow, an arrow inside an arrow, a
  `function` expression and declaration, an object-literal method, a getter, a setter, a constructor,
  a property initializer, a static member and a class expression, compiled through the ORDINARY
  diagnostic path, gives **17 diagnostics byte-identical to tsc 7.0.2** — so the CHECKER binds `this`
  in a nested arrow exactly right and the compiler-correctness worry this item raised is answered NO.
  The defect was `typeCaptureVisit` installing `currentClassForThis = frame.classForThis`: a cta
  frame is a TYPE-checking context and does not thread `this`, so the frame an arrow BODY pushes
  carries null. Fixed by **`typeCaptureThisClass`**, a pull-based ascent transparent to arrows and
  opaque to every other `this`-binder — deliberately NOT round 922's `typeCaptureEnclosingClass` (the
  accessibility question, which would answer inside a `function`) and deliberately NOT the checker's
  own `spineCaClassCtx` (right shape, bug-compatibly transparent to a nested `FunctionDeclaration`,
  the one arm where reusing it verbatim fails). Bias PROVE TO OFFER. **Side findings, stated not
  fixed**: an EXPRESSION-bodied arrow already worked (a cta frame is pushed at a `Block` enter, so
  such an arrow pushes none), and **quick info on a member NAME is a separate RECEIVER-INDEPENDENT
  gap** — `o.p`, `this.p` in a method and `this.p` in an arrow all report `any` — so the brief's
  "they share the path" is false; promoted to the successor ranking instead. **+20 pins**,
  **seven-arm ablation** (five distinct sets, one measured-redundant guard, one redundancy
  demonstration), suite 14,818 -> 14,838, `cost_gate.py` +0.00%, **8-profile grid `added=0 removed=0`
  against a rebuilt HEAD binary**. `docs/language-service.md` § 9.

- [x] **(API.6) SIGNATURE HELP — LANDED, round 921.** `SignatureHelp(signatures, activeSignature,
  activeArgument)` / `SignatureInfo(label, parameters, returnTypeText, activeParameter)` /
  `ParameterInfo(name, typeText, optional, isRest, labelStart, labelEnd)`; **`Project.signatureHelpAt(
  fileName, offset)`**, null when the caret is in no argument list and an EMPTY signature list when it
  is in one whose callee has none. A FOURTH capture list — `TypeCaptureRequest.signatureSpans:
  List<SignatureCaptureSpan>`, the only one carrying a payload beyond the span, because the ACTIVE
  ARGUMENT is a property of the COMMAS and `f(a, |)` parses to a call with one argument.
  **THE PREMISE — "three-quarters built" — HELD FOR THE CALLEE AND WAS WRONG ABOUT THE ANCHOR.**
  `getCalleeType` + `getCallSignaturesOfType` answered a method through a receiver, an import, a
  callee that is itself a call and a decorator factory with no rule of their own, exactly as ranked;
  what the completion anchor did NOT already answer is which call and which argument, because
  **signature help is the first query in this arc whose subject is a REGION the parse carries no node
  for**. Three shapes defeat containment: `f(a, b|)` is at the real END of `b` (half-open, so outside
  it) and yet is argument 1; `f(a, |)`'s second argument does not exist in the tree; and for `f(` at
  EOF or `f(a,` before a `}` the call node's own real end lies BEFORE the caret, so no descent reaches
  it. **THE PARSER RECOVERY WAS READ OUT OF `Parser.kt` BEFORE ANY CODE, as round 917 did**:
  `parseArgumentListWorker` breaks on end-of-file and on a `}` and then runs `parseExpected(CloseParen)`,
  so the `CallExpression` EXISTS in every one of those shapes — which is what makes a token-level
  anchor possible at all. So the region is **bracket-matched over the token stream** (stopping early at
  a closer that does not match the top of the stack — an unmatched `}` means the enclosing block is
  closing) and the index is **a count of this list's own commas**, where "its own" is decided by
  testing the ARGUMENTS' spans: a comma inside a nested call, an object literal or a
  `Map<string, number>` type argument is excluded by ONE test, with no per-construct rule and no need
  to lex `<`/`>` (arm A8, 4 red). **THE ACTIVE-SIGNATURE RULE, stated so it can be argued with**: the
  FIRST signature that could still become this call — room for the caret's argument (its index is
  within the parameter list, or the signature ends in a rest, or it takes none and none were passed)
  AND `signatureAcceptsArgs` over the arguments already FINISHED, which is the same verdict
  `resolveCallOverload` selects with, so a host's highlighted overload and the compiler's chosen one
  cannot drift. The argument the caret is IN is deliberately not judged — half-typed by construction,
  so judging it would flip the highlight under the user's hands. Nothing qualifying answers 0,
  reported not hidden. Arms A6 (always 0) and A7 (arity only) redden different sets, so both halves of
  the rule are load-bearing. **ONE COMPILER-SIDE SURPRISE, FIXED**: a parameter declared with a
  BINDING PATTERN is dropped from `Signature.parameters` by `getParameterSymbols` and the survivors
  keep a POSITIONAL zip of the declaration's annotations, so rendering from the symbols alone prints
  `destructured(tail: { a: number; b: number })` — one parameter short AND wearing its neighbour's
  type, i.e. a plausible-looking lie. The DECLARATION is rendered instead whenever its parameter list
  is longer (arm A10, 1 red uniquely its own). **RENDERING reuses `typeToString`** — hover's renderer —
  and deliberately NOT `signatureToString`, whose `p?: string | undefined` is a TS2345 message
  convention; parameter ranges are recorded AS THE LABEL IS BUILT (arm A11), because searching for
  `name: type` finds the wrong occurrence as soon as one parameter's type mentions another's spelling.
  A GENERIC callee renders UNINSTANTIATED (`pickFrom<T>(xs: T[], index: number): T`) — inferring `T`
  means inferring from arguments that are not finished. **REFUSED with reasons**: tagged templates (no
  parenthesized list), type arguments, `super(...)` (an ordinary identifier here, bound to nothing —
  empty list, pinned), and a spread's arity. **NOT refused, and pinned**: decorator factories and a
  call-callee. **PINS +56** (`-project` 242 -> 298; core UNCHANGED at 14,341) — 30 parse-only anchor
  pins written FIRST, 26 end-to-end. THE DISCRIMINATOR is an OVERLOADED callee asserted as an EXACT
  list of three labels: every shortcut (render the callee's type, take the overload resolution picks,
  match by name) answers ONE and passes every other pin. **ELEVEN-ARM ABLATION, one mistake at a time,
  each dry-run for a real diff and restored from a sha256-verified snapshot; all eleven compiled and
  ALL ELEVEN reddened a DISTINCT set** — A1 outermost call 1, A2 first overload only 1 (the
  discriminator), A3 no rest clamp 1, A4 no receiver path 2, A5 no export-table leg 1, A6
  activeSignature always 0 -> 2, A7 arity-only 1 (a strict subset of A6, distinguished by the pin it
  leaves GREEN), A8 all commas 4, A9 region = the call's real end 6, A10 no declaration render 1, A11
  label ranges not followed 1. `scripts/round921-ablate.sh`. **GATES: suite 14,717 -> 14,773 / 0
  failures / 0 errors / 3 skipped = EXACTLY the +56**; `cost_gate.py` **+0.00% on all 20 counters** — a
  real gate, since `Checker.kt` grew ~370 lines reachable from the hook on the hot walk;
  `huge_methods.py --fail-over 0` clean on core (750 classes, 15,976 methods) and on `-project`
  explicitly (28 classes, 280 methods); `spine_closure_audit.py` 46 handlers all supersets;
  `scripts/round920-token-gate.sh` 1,327 files / 101,287,620 chars / ZERO violations. No wall A/B:
  production executes not one new instruction — every addition sits behind a hook that returns on a
  null per-file key set. `docs/language-service.md` § 10c.

DENOMINATORS, so every % below converts. Last MEASURED warm rebuild **5,242.6 ms** (round 899, per-arm
sd 2.51%); JFR profile denominator **5,429 ms**; **1% = 54.3 ms**. Cross-round: 5,859 (pre-887) ->
5,424 (pre-895) -> 5,243 (HEAD) = **-10.5% over rounds 887-898**. **There has been no wall A/B for
twelve rounds**, and round 899 could resolve 1.88% in SIGN alone — so every item below is a fifth to
a half of what this box can judge and must be defended on counters plus a decomposition, never on a
median. `cost_gate.py` reads +0.00% by construction for all of them.

REFUSAL FLOOR: ~**0.31%** (~17 ms) for a LOW-risk change — round 897 refused there, 898 refused
MEDIUM at 0.13-0.20%, 900 refused at 0.07-0.14% and BUILT at 0.39%, 903 refused at 0.085%.

- [x] **(WARM.31) Residual boxed primitive map/set keys — REFUSED, round 904.** 14 sites,
  **2,698,745 ops/rebuild**, premium **6.58 ns**, so **17.7 ms = 0.334% for ALL of them together** and
  **0.064% for the largest single one**. `docs/perf/boxed-primitive-key-price.md`. **Do not re-open
  from a leaf profile**: the 29.4 ms that ranked it is one draw of a number that reads 72.9 and 19.0 ms
  across round 899's own two dumps of the same binary. A next agent can refuse a NEW boxed-key site
  for free — **population x 6.58 ns**, and a site needs ~1.7 M ops to clear the floor while the whole
  spine visits 856,962 nodes.

- [x] **(WARM.32) The iterator-allocation family — REFUSED, round 905.** 215 sites are **495,305
  calls over 925,502 elements** (mean list length **1.99** / **1.72**; 52.4% of `forEachChild`'s list
  positions are SINGLETON, and `anyIdentical` hits 94.4% so a hit stops the scan). Premiums **11.95 ns**
  and **2.75 ns** per call = **3.90 ms = 0.074%**, refused by 4.4x, and that is an UPPER bound (both
  arms fold into a trivial sink). `docs/perf/iterator-allocation-price.md`. **The census refuses it
  without the amplifier**: 17 ms over 495,305 calls needs 34.3 ns/call, where a WHOLE boxed
  `HashMap<Long, .>` probe is 8.53 ns (round 904). **The sibling project's -3.1% is not contradicted —
  the mechanism transfers and the PRICE does not**, because its population is per-token `withIndex()`
  chains and ours is 2-element lists. LANDED ANYWAY: the 215 sites now route through `walkList` /
  `anyIdentical` in `NodeWalk.kt` (one home, so it cannot be re-opened blind), which shrank
  `forEachChild`'s three (JIT.1) partitions **9,256 -> 5,929 bytecodes (-36%)**.

- [x] **(WARM.33) reach-machinery (b), transpose the 43 per-file memos — REFUSED, round 906, AND THE
  CANDIDATE IS A REGRESSION AT EVERY GEOMETRY.** `docs/perf/reach-memo-transposition-price.md`.
  **The whole memo-LAYOUT direction is closed**: the ceiling for ANY layout is **2.65-15.99 ms**,
  below the floor at every cache geometry, and shrinking the cache makes the candidate worse rather
  than better. **Round 875 had the SIGN wrong** — it read the ascent's scatter onto the probe's
  sequential sweep; measured, **42.2% of ascent steps go to `nodeId - 1`, 89.8% stay within 64 ids**,
  the spine walks in PREORDER so each 1-byte array is swept sequentially, and **layout A already
  answers 97.0% of accesses out of L1** (a line serves ~14.2 consultations against a transposed row's
  ~3.8). **Round 875's queued instrument could never have decided it**: an amplifier repeats one probe,
  so from the second repetition the line is L1-hot — *a locality change cannot be amplified*, and the
  round that priced it contains no clock at all, only a census plus a set-associative LRU model.
  Also corrected: this entry's own "deletes 36.9 MB/rebuild" deletes **55 KB of array headers** —
  43 arrays of n bytes and one of 43n are the same bytes. Adjacent direction closed with it: lazily
  allocating the 17 classifiers consulted <1,000x/rebuild is worth ~2-3 ms.

- [x] **(WARM.34) `lexLevelHasName`, the COUNT question — REFUSED by its own census, round 907, AND
  THE WHOLE FAMILY IS NOW CLOSED.** `docs/perf/lex-ascent-count-price.md`. **The queue's premise was
  wrong**: "an O(depth) ascent revisiting the big outer levels" describes the CHAIN (3.69 steps),
  not the PROBES (**1.544** per ascent), because 58% of level visits are refused by the untrusted /
  non-head-fn rules or are hash-free EMPTY maps — *a chain-step population is not a probe
  population*, round 902's law one step along its own family. **563,466 ascents / 870,231 real probes
  = 31.85 ms = 0.602% is the ceiling on EVERYTHING here.** The 80.7% redundancy is real and does not
  help: a repeat ascent performs **1.32** probes and a memo probe replaces them with **1**, so the
  queued ascent memo is **2.42 ms net, 9.92 ms even if free, and −10.7 ms at the measured probe
  cost — a regression**. A per-level memo is refused BY CONSTRUCTION (*a cache keyed by the same name
  at the same granularity as the map it fronts IS that map*), and a per-file absence filter is
  <= 7.30 ms. **Closure is now GENERAL, not per-lever: any one-operation oracle costing one probe
  recovers at most 0.21%.** Container closed by 901 (+0.26%) and 902 (−0.19%).

- [x] **(SPINE.1) The six spine handlers' frame bookkeeping — REFUSED AND CLOSED, round 908.**
  Denominator re-taken: **5,050 ms** (8 probe-free warm process medians), so 1% = 50.5 ms. The six
  are still 62.6% of the probed spine and **40.1% of the rebuild**, but round 733's deflation,
  MEASURED rather than applied (and with `SpineSections` run WARM for the first time), says the
  passes' own checking work is **91.4%** and every frame pop and restore is at or below one probe
  boundary — five of eleven sections read NEGATIVE once their boundary is subtracted. **Nothing
  clears the floor**: the three ancestor climbs are 19.6 ms (0.39%, refused again), the cta
  frame+ambient install 16.0 ms and load-bearing, the cta eligibility gate 14.4 ms with round 888's
  mask having already taken **87% of its population**. **The one row above 1% — 79.8 ms of
  frame-ambient install — has a ~8 ms deletable population** (the rebuild walks 2.91 frames, produces
  nothing on 91.4% of installs, and the save copies ZERO entries on 100% of 147,572) **and fails its
  own division by ~20x, because a timestamp is an OPTIMIZER BARRIER.** Round 847's per-handler ms are
  superseded — they were against 8,095 ms — and the order swapped again (`ccetSpineLeave` #1 -> #3,
  −51% in ms, while `cpaSpineLeave` fell 5% in ms and ROSE 7.62% -> 11.56% in share: round 830 live).
  **Caveat for any successor: the `dispatch` tier bypasses `spineEnterMask`, so that table prices the
  pre-888 regime and is blind to the lever the region already banked.**

- [x] **(WARM.35) The four round-903 hot-path candidates — ALL REFUSED, round 912, AND THE QUEUE'S OWN
  POPULATION FOR THE LARGEST OF THEM WAS A TRANSCRIBED SOURCE COMMENT.**
  `docs/perf/round912-candidate-census.md`. Priced by census plus round 896's divide-and-refuse —
  **no fix built, no amplifier needed**; both census processes agree to the last digit on all 22
  counters and `mappedNodeTypeKey calls = 110,780` reproduces `cost-counters.txt`'s
  `typeNode.bypassed` exactly, which is a second independent control. Against the stated 5,242.6 ms
  denominator (1% = 52.4 ms, the ~17 ms floor = 0.324%):
  **`mappedNodeTypeKey` key build — 25,987 keys of 110,780 calls = 9.36 ms = 0.179%, refused by
  1.8x**; **`narrowTypeFromFlow`'s default-arg `NarrowFlowMemo` — 31,768 = 4.77 ms = 0.091%, by
  3.6x**; **`collectTypeofGuardNames` &c `LinkedHashSet` — 22,798 = 1.48 ms = 0.028%, by 11.5x**;
  **`spineOsWithAmbient` / `spineTcDispatchWithAmbient` — 2,841 = 0.28 ms = 0.005%, KILLED BY READING,
  by 60x**. **ALL FOUR TOGETHER are 15.9 ms = 0.303%, still under the floor for ONE low-risk change.**
  To reach 17 ms they would need **654 / 535 / 746 / 5,983 ns per operation**, against a measured
  **15.09 ns** for a whole `HashMap` get that recursively hashes AND `equals` a 2.76-node AST subtree
  (round 903). **DO NOT RE-RAISE ANY OF THE FOUR.** Three mechanism findings outlive the prices:
  **(a)** the "~88 k/rebuild" this queue attached to `mappedNodeTypeKey` **was never a measurement** —
  it is a transcribed KDoc that is itself 26% stale (real call count **110,780**) applied to the wrong
  quantity (only **25,987**, 3.4x fewer, build a key; 76.5% exit at the foreign-file gate first), so
  the entry was wrong in both directions at once; **(b)** candidate 3's `inline` **is not expressible
  in Kotlin** — both wrappers hand `block` to a RECURSIVE non-inline callee, so `inline` forces
  `noinline`, which re-materialises the lambda, i.e. a candidate can be dead on grounds of the
  LANGUAGE before any population is counted, and reading the CALLEE rather than the wrapper is what
  shows it; **(c)** candidate 4's obvious shared-memo fix is a **SOUNDNESS bug, not merely a small
  prize** — `narrowTypeFromFlowCore` handles re-entrant walks at `narrowLiveDepth == 0` by design, so
  a shared instance would be cleared under a live outer walk and a wrong serve there is a WRONG
  NARROWED TYPE; and **34.2%** of memos outgrow 32 slots, so `clear()` is not obviously cheaper than
  the allocation (round 899: price a container swap NET). **NEW REUSABLE CONSTANT, the allocation twin
  of round 904's ~1.7 M map-ops bar: a pure-allocation candidate needs > 113,000 allocations/rebuild
  at a generous 150 ns, or > 340,000 at a realistic 50 ns, to clear the ~17 ms floor** — which refuses
  most per-node allocation candidates by arithmetic, the whole spine visiting 856,962 nodes.
  **AND THE ONE THING THE AUDIT NEVER NOTICED, still under the floor:** `mappedNodeTypeKey` spends
  **110,780 parent-chain climbs plus 110,780 `String`-keyed map probes (~5.5 ms)** so that 76.5% of
  calls can answer "foreign file" — comparable to the named mechanism, and structurally required by
  the gate; the WHOLE function at these generous rates is ~15 ms, still under the floor.

**SUCCESSOR, PER THE WORK ORDER NOTE ABOVE — a refusing round must name one.** With round 908 closing
the spine side and round 912 pricing the audit residue, **the checker-side pool is empty in the
literal sense: nothing checker-side is left unpriced.** **The successor is the (API.\*) arc, whose
next unchecked item is (API.3b) go-to-definition, with (API.3c) — batching a whole file's spans into
ONE build — as the item that makes the API practical for an editor.** The remaining PERF levers are
ARTIFACT-level and **both are gated, which a next agent must not rediscover**: (ART.1) is gated on the
owner's RELEASE decision and not on engineering (`native.yml` already builds Oracle + PGO and verifies
byte-identity), and (ART.2) is gated on a **CRaC JDK that is NO LONGER INSTALLED on this box**
(`/usr/lib/jvm` holds Zulu 26 and OpenJDK 25; `~/jdks` holds 17 and 21 — none of them a CRaC build), so
neither its `afterRestore` cwd fix nor a re-measurement can be compiled or verified locally.

**THE SEARCH STATE, AFTER SIX CONSECUTIVE REFUSALS (rounds 903-908), AMENDED ROUND 912 — READ THIS
BEFORE PICKING THE NEXT CANDIDATE. THE CHECKER-SIDE POOL IS NOW EMPTY, AND SINCE ROUND 912 IT IS EMPTY
OF UNPRICED CANDIDATES TOO.** 903 refused at 0.085%, 904 at 0.334% (14 sites TOGETHER), 905 at 0.074%, 906
measured a REGRESSION and closed a whole direction, 907 refused by census and closed a family. **Every
candidate ranked off the JFR profile in this arc has come in 2-21x over when measured — nine of ten
in the recorded scoreboard, six of six this session.** Meanwhile 61% of the warm rebuild is
unclassified residue, **no single JFR row is above 1.81%**, and the box cannot resolve below ~1.5%.
**That is what an exhausted search looks like.** It is not a failure — the compiler is -10.5% over
rounds 887-898 and warm xtsc is 2.05x tsc check-only — but a sixth single-row candidate should be
justified against this record rather than picked off a profile.

**THE MEASURED LEVERS THAT ARE *NOT* EXHAUSTED ARE AT THE ARTIFACT LEVEL, AND THEY ARE AN ORDER OF
MAGNITUDE LARGER THAN ANYTHING LEFT HERE.** Both are already measured, not speculative:

- [ ] **(ART.1) Ship the PGO'd native image. -21.2% check-only / -19.1% emit**, 5/5 paired in both
  modes, 46 diagnostics and all 78 emitted `.js` byte-identical (`docs/perf/aot-native-image.md`
  § 10). Needs Oracle GraalVM (`-graal` in SDKMAN; CE's `native-image --help` does not mention the
  word) and an `.iprof` trained on BOTH modes — a check-only-only profile leaves the
  Transformer/Emitter on static heuristics. This is the biggest single lever ever measured in this arc.
  **CORRECTED round 909 — the entry's premise ("CI currently ships the Community Edition arm, which
  has no PGO at all") IS STALE AND MUST NOT BE RE-INHERITED:** `native.yml:60-72` already builds
  **Oracle + PGO** via `scripts/build-native-pgo.sh`, verifies byte-identity against the JVM and
  uploads `xtsc-linux-x64`; `bench.yml` builds the Oracle **BASE** image per push deliberately (the
  PGO cycle is too slow to pay per push for a column that is not the headline). **So the engineering
  exists and what remains is the SHIPPING decision — attaching the binary to releases, already tracked
  as (AOT.1) and explicitly the owner's** (`native.yml:8`). Also **not measurable on the dev box: no
  GraalVM is installed there** (Zulu 26 / OpenJDK 25 only), so any re-measurement is a CI job or an
  install first.

- [ ] **(ART.2) CRaC — ~30 ms restore at FULL WARM SPEED** (6.8-7.3 s against 24-25 s cold, 3.4x,
  output byte-identical bar the `time:` line; `docs/perf/crac-checkpoint.md`). **Blocked on one known
  defect, not on the mechanism**: the restored process keeps the CHECKPOINT's working directory —
  round 873's bug one layer down — so a CRaC CLI must re-install the real cwd through
  `SystemVfs.workingDirectory` in an `afterRestore` hook, exactly as `CompileServer` already does per
  request. Unmeasured risk: the 340 MB image was page-cache-hot in every restore taken so far.
  **CORRECTED round 912 — AND THIS IS ALSO A LOCAL-TOOLING BLOCK, NOT ONLY A CODE ONE: the CRaC JDK
  IS NO LONGER INSTALLED ON THIS BOX.** `/usr/lib/jvm` holds Zulu 26 and OpenJDK 25 and `~/jdks` holds
  17 and 21 — none of them a CRaC build — so neither the `afterRestore` fix nor a re-measurement can
  be compiled or verified locally; it needs a Zulu CRaC install (or CI) first. Do not rediscover this
  by writing the hook and finding nothing to run it on.

**THE ROUND-903 HOT-PATH AUDIT'S FOUR UNPRICED CANDIDATES ARE NOW PRICED AND ALL FOUR ARE REFUSED —
see (WARM.35) above, and do not re-raise them from this block's former wording** (both copies of it
are collapsed into that entry; the record it stood on, "~88 k/rebuild", was a transcribed source
comment rather than a measurement).

**CLOSED IN ROUND 903, DO NOT RE-RAISE** (round 903, `docs/perf/type-node-key-price.md`): the
`nodeTypes` deep AST-value key, **refused at 0.085%** — its premium over a `(file, nodeId)`
`LongKeyMap` is 12.98 ns over 354,131 ops = 4.60 ms, and `A - B` is an UPPER bound. Round 896's
`nodeTypeResolutionInProgress` sentinel falls with it at 1.54 ms. The JFR row's other owner is
`isPerFileDependentRefNode` at 3.70 ms; family 9.04 ms against a 57.1 ms row.
