# Status

**`type Box<T> = { v: T }` RENDERED `{ v: any; }` ON ORDINARY BUILDS — A GENERIC ALIAS'S OWN
PARAMETERS WERE NOT IN SCOPE FOR ITS BODY (2026-08-24, (INC.28)).** The fourth shipped correctness
defect this session found while chasing latency, and the third in a row where **the FULL build was
the arm losing information**. `getDeclaredTypeOfSymbolWorker`'s type-alias arm resolved `decl.type`
with **no type-parameter scope**, so the alias's own `T` answered `errorType` — and **`any` ABSORBS A
UNION**, so a union body collapsed entirely rather than partially, which is why the symptom is a
whole type vanishing. **Four lines reproduce it with no partition, and it is not order-dependent**;
the partition divergence was a CONSEQUENCE, since a narrowed build skips
`init:buildFileLocalTypeMaps` and the first toucher becomes `withDeclTypeParamScope`, which DOES
install the scope — and `declaredTypes` has **no write gate at all**, so first touch freezes.
**A WRITER HOOK REFUTED BOTH STANDING SUSPECTS** rather than confirming a guess: the ledger prints
`name=VisitResult pass=init:buildFileLocalTypeMaps ambient=empty depth=sym1/node0 type=any` —
`ambient=empty` kills round 778's write gate, `depth=sym1/node0` kills truncation. Ground truth came
from **tsc 7.0.2 over its own LSP** (`--lsp -stdio`, round 924's instrument) rather than a
hand-written expectation: `type VisitResult<T extends Node | undefined> = T | readonly Node[]`.
**THE FIX IS A SPLIT AND THE SPLIT WAS FORCED BY MEASUREMENT**: `getTypeOfSymbolWorker`'s alias arm
now answers the parametric form, with the constraint fill write-once and OUTSIDE the install per
(INC.19)'s TS2589 hazard; **`getDeclaredTypeOfSymbol` — what a REFERENCE resolves to — is
deliberately untouched**, because handing references the parametric form costs two corpus false
positives, both measured and both reverted.
**THE REFUSAL IS A RECURSION BRAKE, AND IT IS WHY 173 OF THE 298 ROWS REMAIN.** Judging a
`Type.TypeParam` alias argument by its APPARENT type renders `Visitor` exactly as tsc does and costs
a corpus FP: `checkTypeRelatedToCore` has no general "TypeParam source via its constraint" rule — its
`NonPrimitive` leg refuses it deliberately — and **that refusal is what brakes the recursion for
`BuildTree<T, N extends number = -1, I extends any[] = []>`**. Those rows need the RELATION to learn
the rule, not the display; queued as (INC.30), an engine item rather than an (INC.*) one.
**THE PIN LESSON IS THE TRANSFERABLE ONE**: the ablation put 2 of 4 pins RED on the unfixed binary
reading exactly `[{ v: any; }, { v: any; }]` — **while the two-arms-agree test and the negative
control stayed GREEN, because both arms agreed on the WRONG answer.** The capture sweeps are
differentials, so a defect present in BOTH arms is invisible to them by construction; that is how
this survived every gate in the repo. A pin for a display defect must assert the VALUE, never that
two arms agree. Suite **15,811 / 0 / 3** (+4 pins), **zero corpus baselines moved**,
`capture-equivalence` **1,128 -> 1,003 spans with ZERO NEW divergent spans** (a strict subset, 125
fixed), `capture-channel` full digest BIT-IDENTICAL with its narrow arm becoming more correct (+11),
`partition-equivalence` EQUIVALENT 78/78, `partition-gate` 78/78 and 76/76 over 78 netting passes,
floor **60 ms `[59, 72, 57, 60]`** untouched, `huge_methods` exit 0 both modules. Digests moved by
design for the second time in the arc: full `3349895618940861366` -> **`8385940838610938556`**,
narrow `306524840298287433` -> **`-7423700524621287041`**. **ONE THING TO WATCH**: the standing
cost-gate drift grew for the first time this session — `mapped.hits` **+1.02% -> +1.63%** (band is
±2%), understood (parametric resolutions run with a scope installed, so bypassed rather than
cacheable) and justified but NOT rebaselined; the next round in this area should `--update`
deliberately rather than discover the breach.

**(INC.27) REFUSED WITH A PROOF: B416's KEY CANNOT NAME A UNION THE WAY tsc DOES, AND THE OBVIOUS
NARROWING WAS BUILT, MEASURED, AND MADE THE GATE *WORSE* (2026-08-24).** The ~790-row
`unionAliasStructural` residual (INC.26) left splits, per element and nesting-aware, into **432**
rows where SEVERAL aliases claim one member set (arbitrary in BOTH arms), **~393** where a SOLITARY
alias names a union at sites that never spell it — measured, not inferred: `AssignmentPattern` has
**0 references** in binder.ts, `MemberName` **0** in checker.ts, `JsxCallLike` **0** in parser.ts —
and **~303** of the unrelated (INC.28) family. **tsc gives THREE answers for one member set**
(`ModuleName`, `ModuleExportName`, and the bare `Ident | Str` where nobody wrote an alias) because it
keys its union cache by `getTypeListId(types) + getAliasId(aliasSymbol, …)`. **And a fourth probe
named the real mechanism: tsc's union-alias naming is IDENTITY PRESERVATION (`filterType`), not
structural matching** — a join-built `A | B` renders structurally while a narrow of `x: MyType` that
removes nothing renders `MyType`, both visible in one pristine baseline.
**THE PROOF THAT BOUNDS THE DIRECTION**: round 545's INV.5(a) interns our unions by **member-id list
ALONE**, so every one of tsc's instances is a single `Type` here. No id-keyed or member-set-keyed
table can give three answers from one key, and **anything able to name the flow-RECONSTRUCTED union
necessarily also names a union nobody wrote.** The residual is an INTERNING-KEY defect, not a defect
of B416's table — queued as (INC.29), which is an INV.5(a) change and must price the id churn first
(union interning is load-bearing for relation caching and for a display ORDER pinned byte-for-byte
across ~13k baselines).
**THE NARROWING WAS BUILT RATHER THAN ARGUED ABOUT, AND THAT IS WHAT SETTLED IT.** Poisoning a member
set two differently-named aliases claim does exactly what it claims — the `full=name/narrow=name`
bucket collapses **416 -> 2** — and the gate still goes **1,128 -> 1,351 spans, 43 -> 46 files**,
because a `full=structural/narrow=name` bucket of **657** appears: **the poison TRIGGER is itself
coverage-dependent**, converting a small difference in which aliases happened to be resolved into a
difference in whether a name exists at all, and amplifying it. Nor can ambiguity be decided
syntactically — of **407** collisions per compile the largest are aliases whose body is ANOTHER alias
(`type FunctionLike = SignatureDeclaration`), spelling no members at all — so deciding it means
resolving every union alias up front, i.e. (INC.22)'s eager `TypeAlias` phase, already refused for
6.68 ms of the floor and for diverging a DIAGNOSTIC on the sensitivity arm.
**WHAT LANDED IS BEHAVIOUR-FREE AND PROVEN SO BY DIGEST**: the `unionAliasStructural` KDoc carrying
the proof, census hooks placed OUTSIDE the write (`XTSC_ALIAS_CENSUS=1` -> 15,318 registrations, 407
collisions), and two pins guarding what a future round must not lose — a solitary alias names its
member set, and the switch-fallthrough-reconstructed union still displays as `MyType`. **Nothing pins
the open gap** (round 765: a pin on a known-open gap is a countdown, not a guard). Suite **15,807 / 0
/ 3** (+2 pins), zero corpus baselines moved, `cost_gate.py` exit 0, `huge_methods --fail-over 0`
exit 0 on both modules (core 781 classes), `partition-equivalence` EQUIVALENT 78/78, `partition-gate`
78/78 and 76/76 over 78 netting passes, floor **57 ms** untouched, and — the control that makes the
inertness a measurement rather than a claim — **`capture-equivalence` returns full
`3349895618940861366` / narrow `306524840298287433`, 1,128 spans in 43 files, BIT-IDENTICAL to the
recorded baseline.**

**AN ALIAS WAS RENAMING EVERY TYPE THAT ALREADY HAD ITS OWN NAME — IN THE *DIAGNOSTICS* CHANNEL, ON
ORDINARY BUILDS — AND THE GATE THAT WAS SUPPOSED TO CATCH IT HAD THE REFERENCE ARM BACKWARDS
(2026-08-24, (INC.26)).** **OPERATIONAL, READ FIRST: two recorded full-build digests MOVED, by
design, for the first time in this arc — `capture-equivalence` `-3718897727265589316` ->
**`3349895618940861366`**, `capture-channel` `4065921979171190360` -> **`-3278907782584108296`**.
A full build is exactly what this round corrects; re-record them, do not read them as a regression.**
The round was sent to buy back the capture gate (INC.25) moved from 5 to 2,275 spans, with two
candidate routes. **The census inverted the brief and neither route was correct.** Classified per
ELEMENT (nesting-aware, 62 distinct pairs), the `Intl.LocalesArgument` case the queue entry led with
is **2 rows of 2,275**, and the dominant direction is the reverse of the assumption: **the FULL build
attaches a name, the NARROW one renders the honest type** — 421 `HasIllegalExpressionInitializer` vs
`PropertySignature`, 346 `ModuleName` vs `ModuleExportName`, 292 `IsInterface` vs
`InterfaceDeclaration`, 127 `FunctionBody` vs `Block`. In tsc's own `types.ts` those are **aliases
whose body is a single NAMED interface**: we stamped the alias onto that interface's `Type.id`, and
`typeToString` reads `aliasDisplayMap` BEFORE the structural fallback, so every occurrence
program-wide rendered under the alias. **The alias census independently refuted the assumed
mechanism** — `DIFFERENT-NAME CLOBBER: 0`, 80 different-name refusals from one pair, nowhere near
2,275, so (INC.11)'s first-wins hypothesis was simply not what these rows are.
**IT REPRODUCES ON FOUR LINES WITH NO PARTITION, IN THE DIAGNOSTICS CHANNEL** — xtsc
`Type 'FunctionBody' is not assignable to type 'number'.` against tsc 7.0.2's `Type 'Block'`. **That
is the THIRD shipped correctness defect this session found while chasing latency**, after (INC.11)'s
unbound `T` in a tooltip and (INC.25)'s `[Symbol.unscopables]: any`; all three were invisible to the
corpus, the cost gate and all eight profiles. **Both queued routes were treating a symptom**: the
6.68 ms one would have bought the gate back by making narrowed hovers **as wrong as full ones**.
**THE FIX IS THE TEST THE SIBLING ARM ALREADY HAD** — `shouldRegister`'s Object arm never applied the
`symbol == null` check its Intersection arm applies per constituent. Anonymous bodies still register
(`type Foo = { a: number }` -> `Foo`); that boundary is the negative control. **ROUND 754 BIT AND THE
HANDLING IS THE PART WORTH COPYING**: the first version reddened four `Table` rows and **no
logical-parity divergence was taken** — that baseline is pristine tsc's, so switching it off would
have moved AWAY from tsc. The rule was NARROWED to exclude a GENERIC named type (a bare all-defaulted
generic resolves to the raw `Type.Interface` rendering `TableClass<any>`, not a name the source
spells), and the ablation pins it: removing that exclusion reddens **exactly 2 of 504 tests — the new
pin AND the corpus baseline, together**.
**THE GATE READS 2,275 -> 1,128 SPANS (-50%), 46 -> 43 FILES**, `narrowRendersMoreAny=0`,
`absentInNarrow=0`, `absentInFull=0` — **and 1,128 is recorded as the new baseline, not 5**, because
the residual is two measured mechanisms in which the NARROWED arm is again the more correct one:
~790 rows of `unionAliasStructural` (B416), which names ANY union matching a declared alias's member
set first-wins — tsc renders `Ident | Str` where no alias was written and we answer `ModuleName` —
and ~298 rows where the FULL build renders `any` and the narrow renders `T | readonly Node[]`. Queued
as (INC.27)/(INC.28). **So the honest target for that gate is not "narrow agrees with full" but
"neither arm has a wrong name to disagree about", and closing it by making narrow match full should
be refused on sight.** Suite **15,805 / 0 / 3** (+4 pins), **zero corpus baselines moved**,
`cost_gate.py` exit 0, `huge_methods --fail-over 0` exit 0 on both modules, `partition-equivalence`
EQUIVALENT 78/78, `partition-gate` 78/78 and 76/76 over 78 netting passes. **The FLOOR IS UNTOUCHED
and no before-arm was run, deliberately, because the change cannot reach it**; and
`capture-channel`'s DIVERGED count has **no same-session before-arm, so no claim is made about it** —
which is the right way to report a number you did not control for.

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

