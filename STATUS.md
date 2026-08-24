# Status

**`[Symbol.unscopables]` HAS BEEN RENDERING `any` IN EVERY SMALL PROJECT'S HOVERS ON THE ORDINARY
SHIPPED BUILD, AND FIXING IT COLLECTED THE FLOOR 129 -> 58 ms (2026-08-24, (INC.25)).** Three rounds
read this as a partition defect. It is not: a THREE-LINE scratch project (`export const strArr:
string[] = []` plus a `number[]` sibling) reproduces it on a FULL build with no partition and no arm
— `truncatedResolutions=1`, `MEMBER [Symbol.unscopables] : any`. **A hand-written `interface Foo { un:
{[K in keyof Foo]?: boolean} }` resolves CORRECTLY in all four forms tried**, because the spine walks
an interface body and resolves the mapped-type node before any member table is in flight; **`interface
Array<T>`'s body is never spine-walked**, so the first ask arrives from inside
`resolveReferenceMembers`. The 78-file tsc profiles hid it because `init:buildFileLocalTypeMaps`
happens to resolve that member first — which is exactly why it looked partition-shaped.
**THE MECHANISM AND A FIX THAT TERMINATES BY CONSTRUCTION.** `resolveStructuredTypeMembersCore`
returns silently on re-entry leaving `properties` null — correct for circular heritage, TRUNCATED for
any reader of the key set. `getKeyofType` read that null as `string`, the mapped type bailed to `any`,
and round 778's write gate froze it (`ambient=empty` throughout, so the cache was never the lever).
The fix answers such a `keyof` **from the DECLARATIONS**: no resolver call at all, only
already-computed tables plus AST, under a visited set and a depth cap, **REFUSING rather than
returning a partial key domain** (round 463). **No TS2589 at (0,0) appeared anywhere** — (INC.19)'s
self-referential-alias family is unreachable because nothing on this path resolves a constraint.
**The ablation is decisive and the counters are its control**: disabled, the pin reads `typeText=any`;
enabled, the exact 34-member mapped object — and the ablated binary's cost-gate counters are
**identical digit for digit**, so the fix moves zero counters and all standing drift is pre-existing.
**THE PRIZE, COLLECTED.** With the defect gone, `narrowRendersMoreAny` returns **229 -> 168** (the
baseline) — the one observable that refused (INC.22) — and partition-scoping the floor's largest row
is now the shipped default, pinned with **no mode install in it**. **Floor 129 -> 58 ms;
narrowed-query median 173 -> 117; ratio at the median file 30.91x -> 43.07x; the floor is now HALF a
median query instead of three quarters.**
**A GATE BASELINE MOVED AND IT IS THE ONE THIS ARC HAS QUOTED ALL WEEK — DO NOT TRUST AN OLDER
`5 / 3` FIGURE.** `capture-equivalence.sh`'s NARROW arm goes **5 spans / 3 files -> 2,275 of 381,666
(0.60%) in 46 files**. The FULL-build digest is unchanged (`-3718897727265589316`), so an ordinary
compile is untouched; the movement is entirely in what a NARROWED query renders. **It is not the trade
(INC.2) refused** — that was 45 spans rendering `any` where the full build rendered the declared type,
i.e. wrong answers. Here **no row renders more `any`, none is absent, and the direction is MIXED**
(the narrow arm KEEPS `Intl.LocalesArgument` where the full arm expands it, 113 rows; and renders a
signature the full arm gives up on as `any`, 38 rows) — two draws from the id-keyed first-wins
`aliasDisplayMap`, which CLAUDE.md already records as arbitrary in both arms. **It is still a 455x
move in a gate that stood at 5 for the whole (INC.*) arc, and the mitigation is already measured**:
(INC.22)'s `TypeAlias`-phase-program-wide configuration costs **6.68 ms** — 11% of a 58 ms floor — and
collapses 2,275 to **+1 row**. It is queued as (INC.26), which must first diagnose the ONE diagnostic
that configuration diverges on `partition-gate`'s sensitivity arm; the alternative road is a
CAPTURE-LOCAL alias resolution, which cannot move a baseline at all. Suite **15,801 / 0 / 3**, **zero
corpus baselines moved**, `cost_gate.py` exit 0, `huge_methods --fail-over 0` clean (779 core, 50
`-project`), `capture-channel` digest `4065921979171190360` with moreAny 168, `partition-equivalence`
EQUIVALENT 78/78, `partition-gate` 78/78 and 76/76.

**THE 62-65 ms THAT (INC.22) REFUSED IS GATED BY *ONE LIB MEMBER* AND *ONE TRUNCATED RESOLUTION*,
AND THE COUNTER THAT REPORTED IT IS A SUBSTRING HEURISTIC (2026-08-24, (INC.23)+(INC.24)).** (INC.22)
refused partition-scoping the floor's largest row — `init:buildFileLocalTypeMaps`, 69.16 ms of a
90.15 ms pass table — because `capture-channel`'s `moreAny` went 168 -> 229, read as "+61 member
types collapse to `any`". **Classified per ELEMENT (nesting-aware, so a function-typed member is not
fragmented into its parameters) that is 78 rows carrying exactly ONE member name —
`[Symbol.unscopables]`, the lib's `{ [K in keyof any[]]?: boolean }` — in 14 files.** The other 1,379
rows over 196 names are the (INC.11)(a) alias-display family, which (INC.22) already measured
collapsing to **+1 row for 6.68 ms**. **AND `narrowRendersMoreAny` OVER-REPORTS: ZERO of the shipped
baseline's own 168 "moreAny" rows actually loses a member type**, so a nonzero value is a LEAD and
not a finding — a zero still means what it always did, which is why `capture-equivalence`'s
`narrowRendersMoreAny=0` remains a real gate. Every moreAny figure quoted in an older round note,
this session's included, should be re-read that way.
**ROUND 778's WRITE GATE IS REFUTED AS THE MECHANISM.** A writer hook printing `(pass, ambient,
persisted, depth, truncated)` reads `ambient=empty persisted=true` in **both** arms — so round 778
says cacheable either way — and differs only in `truncated`. Under a partition the first ask arrives
from **inside** the member-table resolution the mapped type's own `keyof` needs;
`resolveStructuredTypeMembersCore` returns silently leaving `properties` null and the type degrades.
**A narrowed compile has exactly ONE truncated resolution out of 822; a full build has 0 of 21,315 —
and that one IS the defect.** **THE OBVIOUS FIX IS REFUTED WITH A POSITIVE CONTROL**: refusing to
persist a truncated resolution changes nothing sweep-wide (same 78 rows, byte-identical digest) while
a single-file control proves the arm is live (`refusedWrites` 593 -> 594, the victim going
`persisted=true resolves=1` -> `persisted=false resolves=2`) — the re-resolution simply re-enters the
same guard. **So the lever is the CYCLE HANDLING, not the cache**: a `keyof` over a type whose member
table is IN FLIGHT must answer from the DECLARATIONS rather than degrade. That is a member-resolution
change with corpus-wide blast radius and was deliberately not attempted — queued as (INC.25).
**(INC.22)'s THIRD OBSTRUCTION IS RETIRED**: the PURE partition-scoped arm is EQUIVALENT on BOTH
`partition-gate` arms (78/78 and 76/76), even though that pass is one of the sensitivity fixture's 78
netting passes — its rows carry the alias's own `fileName`, so an out-of-partition row is dropped by
the partition filter anyway. The "DIVERGED 1 file" belonged to the MIXED `TypeAlias`-program-wide
configuration. **(INC.24) LANDED FIRST, ON ITS OWN COMMIT**: both capture runners now fold their whole
answer set into ONE number per arm, **ordered by span key so it is a property of the ANSWERS and not
of `HashMap` iteration**, and from a clean tree it reproduces (INC.22)'s recorded
`full=-3718897727265589316` over 381,666 types + 360,152 definitions **exactly** — round 776's
rebuild-the-baseline control, satisfied on an instrument rather than a binary. **A PROCESS VIOLATION
IS RECORDED**: a `compileKotlinJvm` ran while a sweep JVM was live, which CLAUDE.md forbids in both
directions; the sweep's summary matched the earlier run exactly so no measurement is tainted, but the
documented failure mode is SILENT and "it was fine this time" is not evidence the rule is soft.
Everything is off by default. Suite **15,800 / 0 / 3** (+16 pins), `cost_gate.py` `output.errors` and
`spine.nodes` **+0.00%**, `huge_methods` clean (778 core classes + `-project`), `capture-equivalence`
**5 / 3, moreAny 0** and `capture-channel` **286 / 49** with digests unchanged,
`partition-equivalence` **EQUIVALENT 78/78** (floor 129 ms, median 173, ratio 29.10x),
`partition-gate` 78/78 and 76/76.

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

