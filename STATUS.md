# Status

**THE FLOOR'S LAST BIG ROW IS WORTH 62-65 ms BY AN AXIS THAT PROVABLY CANNOT MOVE A FULL BUILD, AND
IT IS REFUSED BECAUSE THE ORDER IT BUYS IS *RESOLUTIONS* AND NOT ONLY A NAME (2026-08-24,
(INC.22)).** `init:buildFileLocalTypeMaps` is **69.16 ms of a 90.15 ms floor pass table — 77%** —
and partition-scoping it takes the floor **131 -> 57 ms**, the narrowed-query median **166 -> 116**,
and the ratio at the median file **29.86x -> 42.61x**, with the full build unmoved. **The axis is
new**: (INC.10) and (INC.11) deferred PHASES — what every file's map carries — which perturbs a FULL
build's first-touch order as much as a narrowed one's, and that is what refused them both. This
round varied **WHICH FILES** the eager pass covers, through the INV.6(6d) partition view, which
**IS** `binderResults` when there is no partition — so an ordinary compile is unchanged BY
CONSTRUCTION, the same property that carried the entire (INC.7) gating arc.
**AND THAT CLAIM WAS VERIFIED IN THE BINARY RATHER THAN ARGUED, WHICH IS THE ROUND'S BEST PROCESS
OUTPUT.** A per-arm DIGEST over every captured answer — **381,666 types and 360,152 definitions in
76 files** — reads `-3718897727265589316` for the pre-round arm and **identical for both new arms**,
corroborated by a COUNT (`FltmDefer.lazyBuilds == 0` on every unpartitioned build) and `cost_gate.py`
at **+0.00%** on `output.errors`/`spine.nodes`. "A full build is unchanged by construction" is
exactly the claim that is true of the code and false of the binary; this one was made checkable.
**THE QUEUE'S PREMISE HAD ALREADY EXPIRED**: (INC.11)'s `TypeAlias`-only arm was recorded at **137
divergent spans** and re-measures at **5 / 3 of 76 — byte-identical to baseline**, closed by
(INC.11)'s own `returnsArgumentUnchanged` fix and the work since. So no `aliasDisplayMap` re-key was
needed and none was attempted. **WHAT ACTUALLY REFUSES IT IS THE MEMBER CHANNEL, NOT DISPLAY**:
`capture-channel`'s `moreAny` goes **168 -> 229**, i.e. **+61 member types collapsing to `any`**
under a narrowed build — a WRONG ANSWER, the same class (INC.11) refused the full deferral over —
and `partition-gate`'s SENSITIVITY arm, the one built to refuse rather than print green, **diverges
on a DIAGNOSTIC**. Keeping the cheap `TypeAlias` phase program-wide (6.68 ms) solves the NAMING half
completely (2,275 divergent spans down to **+1 row**) and does nothing for the member half.
**THE READER'S MISS PATH WAS PINNED PROPERLY, WHICH IS WHY THE REFUSAL IS TRUSTWORTHY**: the map's
one reader rebuilds a foreign file's map on demand, and the round pinned both that it FIRES and that
it produces the **SAME map**, with a non-emptiness assertion and a negative control that two files'
maps differ; ablations reddened 4 pins including the no-mode-install DEFAULT pin ((INC.16) a1's
lesson applied) and, separately, exactly the 2 rebuild pins. Suite on the change was **15,795 / 0 /
3** and `huge_methods` clean — but the capture gates are the gates for this family and they refuse
it, so **nothing landed and the tree is back at `aa3c0629`.**
**THE TRANSFERABLE RESULT RE-AIMS THE DIRECTION**: the obstruction is not the pass's COST but that
the pass IS the program's FIRST-TOUCH ORDER, and that order buys BOTH an alias name (cheap, fixable)
AND member resolutions (not fixable without the expensive phase). **A future attempt must make
member resolution ORDER-INDEPENDENT — round 778's `getTypeOfSymbol` write gate is the known
mechanism — not make the pass cheaper.** Queued as (INC.23), with (INC.24) to re-land the capture
digest, a general gate strengthening that died with the revert.

**THE SCANNING FAMILY GATED AS ONE BATCH BANKS 99.9% — THE ARC'S FIRST ~100% DISCOUNT — AND A
NARROWED QUERY IS NOW 29.86x FASTER THAN A FULL BUILD (2026-08-24, (INC.21)).** 19 whole-source-
scanning passes moved TOGETHER (**19.064 -> 0.024 ms**), because round 895's `srcHas` builds its
per-file n-gram filter LAZILY and gating one merely hands the ~19.4 ms to the next scanner — a cost
this repo had already misattributed twice. Gated together it has nowhere to relocate to, and **no
row outside the batch rose**: the largest riser is `init:buildFileLocalTypeMaps` +2.4 ms on 70
(+3.5%) against the arm's own total drifting +9% between draws. **Why nothing rebuilds it was
measured, not inferred**: the three whole-program text gates that remain use a RAW `String.contains`,
never the filtered `srcHas`. `PT.total both.floor` **123.95 -> 97.12 ms**; `partition-equivalence.sh`
floor **162 -> 137 ms**, narrowed-query median **207 -> 166 ms**, ratio at the median file **24.16x
-> 29.86x**. The 19-pass list was derived by TWO independent instruments — a call-graph walk from
each registered `pass("name")` and a purely lexical loop scan — which agree.
**THE FOUR STRAGGLERS TAUGHT THE OPPOSITE LESSON.** Three keep their cost because a whole-program
`.contains` gate sits ABOVE the loop — a question about the PROGRAM, which must stay on
`binderResults` — so gating the loop banks ~0.02 ms each, with `checkModulePreserve4Pin` as the
control (loops narrowed, row unmoved at 1.639 -> 1.699). What banks the ms is a **NAME PRE-GATE**,
sound only because it asks what the pass can already do: **2.509 -> 0.002** and **2.064 -> 0.002**.
**THE AUTHORISED REVERSAL, WITH ITS OBLIGATION DISCHARGED ON BOTH ARMS OF ONE BOX.**
`checkSubsequentVarTypesPerFile` **11.740 -> 0.004 ms**. (INC.17) had deliberately left it
program-wide so a replay never re-enters it; that was reversed because the replay is EXPERIMENTAL,
refused by (INC.19) and reached by nothing shipped, while the row is paid by every real query.
Measured rather than assumed: **284 -> 304 of 417 re-entered passes for +26 ms over 75 questions
(+0.2%)**, divergence unchanged at **5 of 75**. **And the replay's ADVANTAGE fell 1.91x -> 1.68x
purely because the fresh build got cheaper** — every round that shrinks the floor shrinks the
replay's reason to exist, which strengthens (INC.19)'s refusal of it as a default path.
**THE PINS FAIL AGAINST THE UNFIXED BINARY AND THE ABLATION REPRODUCES THE SESSION-START BASELINE**
(round 776's control): 25 partition pins, the ablation turns **9 RED** including the count receipt
*a narrowed build builds fewer whole-source scan filters than the whole program* on `SrcScan.builds`,
and the ablated binary reads `checkReverseMappedIntersectionConstraint` at 18.18 ms with the family
sum at 19.47. **The analyzer caught a SIXTH defect in itself** — a raw LINE-based brace matcher ran
away, reading `checkParseUnmatchedTypeAssertion` as **16,363 lines against its true 15**; the tell
was the impossible span, not a verdict. **REFUSED**: two passes whose emitter adds a row on an
augmentation's TARGET (a partition holding only the target would lose it), a pre-gate for a pass
that wipes rows unconditionally, and routing the three raw `.contains` gates through `srcHas` —
which would now **COST ~17.8 ms to build 78 filters to save three ~2 ms scans.**
**WHAT IS LEFT: THE FLOOR IS 75% ONE ROW.** `init:buildFileLocalTypeMaps` is **73.21 ms of 96.57**;
everything else is <= 8.5 ms. It is refused twice over, but the arithmetic that refused its
alias-display half was written against a 340 ms floor and the floor is now 137 — see (INC.22).
Suite **15,784 / 0 / 3** (+13 pins, no baseline moved), `partition-equivalence` EQUIVALENT 78/78,
`partition-gate` realism 78/78 and sensitivity 76/76 with 78 netting passes, `capture-equivalence`
**5 spans / 3 of 76, `narrowRendersMoreAny=0`** and `capture-channel` **286 / 49** both BASELINE,
`cost_gate.py` largest `mapped.hits` +1.02% (standing) with all others <= 0.32%, `huge_methods
--fail-over 0` core 775/0 and `-project` 50/0.

**THE FLOOR PASS TABLE NEARLY HALVES — 219.98 -> 119.74 ms — AND A NARROWED QUERY IS NOW 24.16x
FASTER THAN A FULL BUILD (2026-08-23, (INC.20)).** (INC.7) batch 4 closed the loop-header technique
and left 83 passes refused by SHAPE, 53 of them on "writes a checker field inside the private
closure". **That verdict was true and the inference from it was wrong**: for nine of them the write
is a per-FILE AMBIENT install (`currentFileLocals` / `currentCheckFileName`), reset after the loop or
save-and-restored per iteration — gone before the next file is walked, with the same resting value
whether the loop ran 78 times or none. Sub-batch B then used (INC.17)'s split template as intended:
two MIXED passes that build a program-wide INDEX and then emit per file (**only the second loop
moved**) and two per-file retractors — one of which, `checkPreEmitCountMismatchPins`, is **IMPROVED
rather than narrowed**, because its TS-1 marker carries `fileName = null` and therefore SURVIVED the
partition filter, so the ungated loop could emit a global marker about a file nobody asked about.
**Banked 100.23 ms of a 116.08 ms row removal = 86.3%, a fifth discount point** (79.0 / 85.5 / 92.9
/ 78.2 / 86.3), and the whole production diff across both perf commits collapses to exactly TWO
distinct lines. `partition-equivalence.sh`: floor **248 -> 162 ms**, narrowed-query median **313 ->
207 ms**, ratio at the median file **15.66x -> 24.16x**.
**THE RELOCATION VICTIM FINALLY HAS A MECHANISM AND A NAME RATHER THAN BEING A RESIDUE.**
`checkReverseMappedIntersectionConstraint` went **0.067 -> 19.431 ms** and is the ONLY row outside
the batch that moved more than 0.2 ms: round 895's `srcHas` builds its per-file n-gram filter
**LAZILY**, so the FIRST such caller in pass order pays it for all 78 files, and gating
`checkBaseClassImprovedMismatch` simply handed the bill to the next scanner. The same shape was
mis-read in batch 2 as a walker that "got slower". **19 registered passes still iterate
`binderResults` AND scan whole source, so that ~19.4 ms cannot be BANKED until all 19 are gated
together** — queued as (INC.21), now the second-largest row in the floor after the refused
`init:buildFileLocalTypeMaps`.
**THE ANALYZER CAUGHT A FIFTH DEFECT IN ITSELF BEFORE PRODUCING A VERDICT** — a Kotlin `${…}`
template containing a nested string desynchronised the stripper at `Checker.kt:64608`, hiding
**2,523 of the file's 4,520 `fun` declarations**, i.e. failing in the reassuring direction exactly as
CLAUDE.md warns. Controls held: length preservation, 4,520 `fun` lines raw and stripped, five named
functions found, every KDoc `pass("name")` sample refused. **PINS: 19**, and the discriminating ones
assert on (INC.17)'s partition CENSUS hook — a COUNT, not a time (round 868). Reverting all 14 loop
headers reddens **5 of 7** census assertions (the two that stay green assert ABSENCE and must hold
in both arms); gating the two COLLECTION loops reddens **exactly the three cross-file arms and
nothing else**, which is the evidence the MIXED split is load-bearing. Ownership of every pinned
diagnostic was established in `build/pass-lab.txt`, not assumed. **GATES, run on EACH sub-batch**:
suite **15,771 / 0 / 3** (+19), `partition-equivalence` EQUIVALENT all 78, `partition-gate` realism
78/78 and sensitivity 76/76 with **78 netting passes** (seven of sub-batch A's nine walkers sit in
its own netting list — the sensitivity arm carried this round too), `capture-equivalence` **5 spans
/ 3 of 76, `narrowRendersMoreAny=0`** and `capture-channel` **286 / 49** both at BASELINE,
`cost_gate.py` PASS (largest `mapped.hits` +1.02%, the standing drift), `huge_methods.py
--fail-over 0` clean on core AND `-project`.

**THE BIND IS 70 ms -> 6 ms AND A NARROWED QUERY IS 20.5% FASTER: THE INV.2(c) TABLES NOW BUILD ON
FIRST ASK, AND THEIR ONE PROGRAM-WIDE READER IS SERVED BY A PROJECTION (2026-08-23, (INC.16)).**
`bindLexicalScopes` was 93% of the bind and — after (INC.7) batch 4 closed the gating technique and
(INC.11) refused `init:buildFileLocalTypeMaps` — the largest single remaining mechanism in the
incremental floor. **Scope tables built on a floor build 123 -> 3; floor median 333 -> 286 ms;
narrowed-query median over all 78 files 346 -> 275 ms, the SUM 29,378 -> 23,909 ms.**
`partition-equivalence.sh`'s own recipe reads floor **248 ms**, query median **313 ms**, ratio at
the median file **15.66x** (batch 4 read 340 / 367 / 13.30x on that same recipe).
**THE BLOCKER WAS SERVED, NOT GATED.** A `forcedBy` census — `PassTiming.currentPass` recorded at
each first ask — confirms `init:computeAllEnumValues` was the **SOLE forcer of all 78 program
files**, and that the 45 real-lib `.d.ts` binds are forced by NOBODY but are worth only ~2 ms, so
the tempting lib half was never the prize. It is a round-609 COLLECTOR and may not be gated, so it
is served by a projection instead: `declareLexical`'s two mint sites turn out **NOT to be
symmetric** — the alias half needs only a NAME, which the binder hands over directly, while the enum
half needs the scope-space SYMBOL itself (`computeEnumSymbolValues` is id-keyed) — so **only an
`enum` in a fresh scope forces a build.** The projection costs two int compares per node on
`indexSourceFile`, a walk that already runs and is content-cached across compiles; its refinement is
measured rather than guessed (67 of 78 skipped, then 69, then **75**), and it ships with round 790's
positive control, `LexDefer.verifySkip` — **75 skipped, 0 violations** — because a zero is evidence
only beside a non-empty skipped population.
**THE HAZARD THE QUEUE CALLED MOST LIKELY TO KILL THE ROUND DID NOT FIRE, AND WAS REMOVED ANYWAY.**
A scope built at first-ask could see a FULLER `nodeToSymbol` than one built mid-bind, since its
`(pos,end)` keys collide across files — the (INC.9) refusal's own mechanism. An **ID-FREE
FINGERPRINT** of every file's tables (scope keys, owner/parent nodeIds, per-scope symbol
names+flags, the aliased `existing` key set) is **IDENTICAL on all 78 files across three runs** —
but that bounds FREQUENCY, not existence, so `Binder.lexOwnerSymbols`, a per-file `nodeId -> Symbol`
table, replaces both reads of the shared map and makes order-independence structural.
**AN ABLATION ARM READ 0 RED AND THAT WAS A REAL FINDING, NOT A PASS**: restoring the eager build
broke nothing in a 15,741-test suite, because **every pin installed the mode it wanted and restored
it, so the shipped DEFAULT was pinned by nothing at all.** A default pin was added and the arm then
discriminates; all four arms now have a uniquely-its-own red set (1 / 5 / 4 / 1). Suite **15,752 / 0
/ 3** (+11 pins), `cost_gate.py` within ±0.32% but for the standing `mapped.hits` +1.02%
(`output.errors` 46, `spine.nodes` unchanged), `huge_methods.py --fail-over 0` clean on core (775
classes) and `-project`, **`capture-equivalence` 5 spans / 3 of 76 with `narrowRendersMoreAny=0` and
`capture-channel` 286 / 49, both at BASELINE** — which is what says the deferral's negative-id
reordering did not bite — `partition-equivalence` EQUIVALENT on all 78, `partition-gate` realism
78/78 and sensitivity 76/76 with 78 netting passes. **Left open, ~20 ms**: 3 files still force, the
ones with a genuinely block-scoped `enum` where the census needs the symbol and not a name.

**A HOVER HAS BEEN RENDERING AN UNBOUND `T` PROGRAM-WIDE ON THE ORDINARY SHIPPED BUILD, AND THE
FLOOR'S BIGGEST ROW IS REFUSED ON A PREMISE ITS OWN QUEUE ITEM HAD BACKWARDS (2026-08-23,
(INC.11)).** The item existed to unblock **66 ms** — `init:buildFileLocalTypeMaps`, the largest
single row in a 212 ms floor pass table — on the belief that the cost buys nothing but a
program-wide FIRST-TOUCH ORDER for interning and `aliasDisplayMap`. **A three-phase re-measurable
arm says part of it buys RESOLUTIONS**: fully deferred is **1,665 divergent capture spans in 47
files with `narrowRendersMoreAny = 321`** — 321 resolutions LOST TO `any`, which no display fix can
reach. (The arm beats (INC.10)'s own table 1.6x-3.4x — 137 / 10 files for `TYPEALIAS`-only against
462 / 18 — and refuses anyway.) **REFUSED, and the item is closed rather than re-tried.**
**CLASSIFYING THE RESIDUAL REFUTED THE QUEUE'S HYPOTHESIS AND FOUND A SHIPPED DEFECT.** The item
guessed the undiagnosed 462 spans "may be two genuinely different `Type` instances". They are **ONE
instance carrying TWO COMPETING NAMES**: an `Extract<ClassLikeDeclaration, Pick<T, "kind">>` whose
conditional CANNOT DECIDE (a free `T` in the second argument) answers its own CHECK TYPE — the
interned union — and the generic site then wrote `aliasDisplayMap[union.id] = ("Extract", args)`
**unconditionally**. So a caret on `ClassLikeDeclaration` reported
`Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>` — **an unbound `T` in a tooltip, for
every user, on the whole-program build** — and nothing in this repo could see it but the capture
sweeps, since no diagnostic moves. **FIXED**: an instantiation that answers one of its own arguments
unchanged no longer registers a name for it, verified against the ablated binary through the CLI
(`Type 'Pass<Shape, Pick<T, "a">>' is not assignable to type 'number'`).
**TWO INSTRUMENT LESSONS, BOTH OF THE FAILING-REASSURINGLY KIND.** The census had to be
**WITHIN-ARM** — `Type.id` is minted in resolution order, so no cross-arm comparison is possible —
and **the first hook watched the wrong site**, reading `0` clobbers at the last-wins site; **reading
that zero as "not a display question" would have been exactly wrong.** The sharpened census reads
**86** instantiations answering an argument unchanged (24x `Partial`, 18x `Extract`) with a positive
control that it is not dead: **6** different-name refusals at the first-wins site. And **the pin was
BLIND on its first draft** — the embedded lib declares no `Pick`, so the shape degraded to `any` and
it passed against the ablated binary; only the ablation said so. With `@useRealLibs` it goes RED
against the unfixed binary with its negative control green.
**WHAT REMAINS IS A CHANGE OF KEY, NOT OF POLICY**: the other half — 302 spans in `checker.ts` alone
under full deferral — is two SYNONYMOUS non-generic aliases resolving to one interned type, decided
first-wins, and **tsc picks by the REFERENCE's declaration site, which an id-keyed global map cannot
express**. Against round 754's deliberate `Type.Reference` exclusion and a union display order
pinned byte-for-byte across ~13k baselines, that is a logical-parity conversation and not worth
opening for a 66 ms already refused. **GATES**: suite **15,741 / 0 / 3** (+6 pins),
`capture-equivalence` **5 spans / 3 of 76, `narrowRendersMoreAny=0`** and `capture-channel` **286 /
49** both IDENTICAL, `partition-equivalence` EQUIVALENT on all 78, `partition-gate` EQUIVALENT on
both arms (78 netting passes), `cost_gate.py` PASS (`output.errors` 46, `spine.nodes` +0.00%,
largest `mapped.hits` **+1.02%**, the standing drift), `huge_methods.py --fail-over 0` clean on core
AND `-project`. **Floor 324 ms / median 357 / ratio 14.06x are DRAW-TO-DRAW against the round's
340 / 367 / 13.30x, NOT a saving — the landed change moves no work.**

