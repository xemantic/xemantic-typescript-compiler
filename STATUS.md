# Status

**A MEDIAN NARROWED QUERY IS 108 ms — INDEPENDENTLY REPRODUCED — AND ITS OTHER HALF IS NOW
DECOMPOSED: `checkSpine` IS 89-92% OF A FILE'S OWN CHECK, SCALING IS **LINEAR** WITH
`checker.ts` AT THE *p10* PER NODE, AND Σ`own(F)` IS **1.39x** THE WHOLE-PROGRAM CHECK
(2026-08-24, (INC.37)).** (INC.3) decomposed the incremental FLOOR; nothing here had ever
split the other half — the queried file's own checking, which after (INC.1)-(INC.32) is the
dominant term. `own(F) = build(recheckOnly={F}) − build(recheckOnly={a name not in the
program})`, per wall and per pass, over all 78 of tsc's own sources, with a control built
in: every floor-resident pass row cancels to ~0 in the subtraction and the per-pass sum
reconstructs the wall to 2-4%. **Floor 56 ms; `own(F)` min 4 / median 52 / p90 138 / max
1,726; query median 108 ms** — which reproduces (INC.31)'s 108-113 ms from a completely
different measurement. **THERE ARE TWO LATENCY PROBLEMS AND THEY NEED DIFFERENT LEVERS**: at
the median file the floor is still the LARGER half (52%), and at the tail — the file a user
is most likely to be in — it is **3.1%** and everything is `checkSpine` on one file.
**THE SCALING ANSWER IS A NEGATIVE RESULT AND IT RETIRES A STANDING SUSPICION.** `own(F)` is
LINEAR in the file's NODE count, and `checker.ts` is at the **p10** of per-node cost —
**6.27 µs/node against a population median of 9.71** over the 51 files with >2,000 nodes
(`parser.ts`, 5.8x smaller, reads 6.08). Its 1,726 ms is 275,478 nodes at a below-median
price: **there is no quadratic to find and no structural lever inside the big file, only the
constant factor per node.** Confirmed independently — Σ per-file `spineNodes` over the 78
narrowed builds is **856,962**, the whole-program figure to the node, so the walk partitions
exactly. **AND THE PROXY THAT WOULD HAVE INVENTED THE OPPOSITE ANSWER IS BYTES**: 76 -> 739
µs/KB among files >100 KB, and every byte-based regression in this round's first pass
predicted `checker.ts` LOW by 1.2-4.3x — i.e. would have been read as a super-linearity that
is not there.
**WHAT IS INSIDE `own(F)`.** `checkSpine` is **89-92%** of it above ~15,000 nodes (1,576 of
1,771 ms on `checker.ts`). The ~400 partition-scoped tail walkers are 10.5% there and 5-7%
elsewhere, and they are FLAT — 78 rows above 0.5 ms, largest **11.45 ms = 0.65% of the
query** — so round 830's arithmetic closes them: there is nobody to make cheaper. Inside
`checkSpine` the four disjoint type-system rows (relation 39, type-node 43, member 7, flow
narrowing 167) are **255 ms = 16.2%**, so **84% is the walk and the handler bodies** —
round 758's whole-program result, sharper under a partition.
**ROUND 847's SIX-HANDLER SET IS CONFIRMED (65.7% vs 63.0% whole-program-warm) AND ITS ORDER
IS REFUTED.** `cpaSpineLeave` is **22.9%** here against round 847's third place;
`ccetSpineLeave`, its first at 18.2%, is **10.9%**; and the top-three permutation differs on
all three of `binder.ts` / `parser.ts` / `checker.ts`, with `binder.ts` putting a handler in
nobody's top six second. Round 847 explained its own swap by differing warm-up RATES — every
arm here is warm, so what is left is the POPULATION. **A per-handler ranking is a claim about
a codebase's shape, not about the compiler: quote the file with it.**
**THE SURPRISE — A 1.39x RE-DERIVATION TAX, AND IT IS THE LARGEST MEDIAN-CASE TERM NAMED.**
Σ`own(F)` over 78 files is **6,841 ms** against a whole-program check of **~4,935**. The walk
partitions to the node, so the extra **1,906 ms** is shared type resolution (lib types,
foreign declarations, instantiations) a full build resolves once and every per-file query
re-derives inside its own fresh `Checker` — averaged, **~24 ms per query, roughly HALF the
median file's entire own check.** Same object (INC.14) measured from the other side. Queued
as **(INC.38)**, whose actionable form is NOT automatic working-set growth ((INC.14) refused
that on `k·floor + k(k+1)/2·perFile` arithmetic) but a host asking for its whole open set in
one call: 342 ms once against 771 asked per file. **(INC.39)** carries the other lever — the
three big handlers, 645 ms the object on `checker.ts`, prize NOT measured.
**THREE INSTRUMENT TRAPS, ALL OF WHICH FAILED TOWARD A PLAUSIBLE TABLE**, which is the most
reusable part of the round: the `dispatch` tier needs a warm-up OF ITS OWN (its by-id
dispatcher is not the production walk — run 1 read a handler at **82 µs per consultation**,
a warm-up artifact wearing a handler's name); **a six-point size ladder cannot answer the
scaling question at all**, since `corePublic.ts` (1,337 B) costs 7.5 ms and `es2019.ts`
(1,533 B) costs 26.5 — 3.5x at the same size, so the fitted intercept is whichever small
file you drew, and it was replaced by the 78-file sweep; and round 846's `full`-tier
inflation of `checkSpine` reconfirmed here at **1.08-1.37x**, so every per-pass ms comes from
the `rows` tier and the probe's timestamp pair is measured in situ (34-36 ns) rather than
inherited. Instrument only — no compiler behaviour touched, no checker code, no compiled core
method — so `cost_gate.py` and `huge_methods.py` are CONTROLS and were deliberately not run.
Suite **15,815 / 0 / 3** (unchanged). `docs/perf/file-check-decomposition.md`.

**THE LANGUAGE SERVICE IS 10-24x FASTER THAN ITS OWN DOCUMENTED COST TABLE, AND THE ONE DEFECT
THE NEW TABLE EXPOSED IS FIXED: A MEDIAN NARROWED DIAGNOSTICS QUERY IS 108-113 ms — 43-47x A FULL
REBUILD — AND AN ORDINARY HOVER AFTER TWO OTHER CHANNELS WENT 324 ms -> 4 ms (2026-08-24,
(INC.31)+(INC.32)).** Every wall figure on `docs/language-service.md` was round-930, i.e. taken
before (INC.2b) narrowed the capture path and before (INC.1)-(INC.30) took the incremental floor
from 1,092 ms to 58 ms. Re-taken on tsc's own 78 sources (9,977,097 chars), warm, six warm-up
cycles, medians with their draw lists, **every row reproduced in two independent JVMs**:
`diagnosticsOf(f)` median **1.1-1.2 s -> 108-113 ms** (p90 202-219), `completionsAt`
**~4.7-5.1 s -> 194-202 ms**, `signatureHelpAt` **190-214 ms**, `documentHighlightsAt(binder.ts)`
cold **~15x**, first hover on `binder.ts` **610 -> 290-306 ms**, against a **4,864-5,096 ms** full
rebuild that is unchanged and is the anchor. **A REAL KEYSTROKE COSTS THE NARROWED PATH
NOTHING EXTRA** — identical bytes 212 ms, an appended comment 247, an inserted statement 218,
a statement introducing a TS2322 215 — which no earlier harness here could say, because they
all dirty a buffer by writing its own bytes back.
**THE HALF THAT DID NOT MOVE IS THE HALF A PLUGIN AUTHOR HAS TO DESIGN AROUND, AND IT CANNOT
MOVE.** `referencesAt` (8.8-9.6 / 13.2-13.9 s), `renameAt` (20.0-21.3 / 25.0-26.0 s) and a
project-wide `diagnostics()` (4,864-5,096 ms) never enter `captureIn`'s partition, because
their claim is about every file; that column is now marked on the page rather than left to
be inferred. The heap claim is corrected in the direction an IDE budgets: not "~1.9 GB peak, 512 MB not enough" but
**1,077-1,125 MB peak in G1 old gen with 264 MB RETAINED** after a full GC — green at `-Xmx2g`,
OOM at `-Xmx1g`.
**TWO LEVERS WERE REFUSED BY MEASUREMENT BEFORE BEING BUILT, WHICH IS THE ROUND'S CHEAPEST
PRODUCT.** (a) Memoizing `SourceIndex`'s derived populations — on a memo hit `captureAround`
still re-derives the file's occurrence set — decomposes to **1.21 ms on a 17.9 KB file, 2.27 ms
at 194 KB and 82.7 ms at 3.15 MB**, closing the arithmetic to 0.4% of the measured 83 ms
second-caret hover on `checker.ts`; at the MEDIAN file the whole prize is **1-2 ms**, and it is
not a `referencesAt` lever either (~140 ms of a 9.3 s sweep = 1.5%). (b) "Completions over-capture the
file" is simply **FALSE** — the three call sites already pass a single caret span, so their
194-202 ms IS the narrowed build, and since a completion is by definition asked on a just-edited
buffer, **no cross-edit memo can ever serve it**.
**THE DEFECT: A MEMO BOUNDED BY ENTRY *COUNT* LET A ONE-SPAN ENTRY EVICT A 125,289-SPAN ONE.**
`Project.captures` was an access-ordered LRU bounded at two ENTRIES. Hover, go-to-definition,
highlights and `fileSemantics` ask ONE file-wide question per buffer (125,289 spans on
`checker.ts`); `completionsAt`'s two branches and `signatureHelpAt` each name exactly ONE span.
So **hover -> completion -> signature help -> hover, with no edit anywhere in it**, threw the
hover's file-wide entry out and paid a whole narrowed rebuild for the last step. **The fix is not
a larger limit** — that would double the worst case to buy a case needing no extra memory at all
— but a bound on WEIGHT, in two lanes that cannot evict each other: at most 4 spans is
caret-scoped and bounded at 4 entries, everything above it is buffer-sized and bounded at 2,
unchanged since (INC.13). **Worst-case retention is two buffer captures (UNCHANGED) beside 16
answers = 0.013% of ONE file-wide capture**, and invalidation was re-audited rather than assumed
(`cached = null` at exactly three sites, every one clearing `captures` in the same breath).
**THE PIN LESSON IS THE TRANSFERABLE ONE, AND IT NEEDED A SECOND ARM TO FIND.** Ablating the
count-based eviction put **3 of 13 RED**; the fourth new pin — *"the caret lane is BOUNDED too"* —
stayed **GREEN, correctly, because a stricter bound cannot fail a bound pin**. Only a second arm
(caret lane unbounded, 4 -> 4096) turned it red. So all four pins do fail against the mistake each
actually names, but *"I wrote four pins and three went red"* would have been the wrong summary.
Suite **15,815 / 0 / 3** (+4 pins); `cost_gate.py` and `huge_methods.py` were CONTROLS and
deliberately not run — no checker code and no compiled core method is touched, the change being
confined to the `-project` module's memo policy. **FOUR FOLLOW-UPS QUEUED**: (INC.33) the caret
channels are cold per channel per buffer (a hover's request carries `spans`, not `memberSpans`, so
a completion in an already-hovered buffer still builds at 201-228 ms — correct, and the widening
is unpriced); (INC.34) the `SourceIndex` refusal, recorded with the instrument that can re-open
it; (INC.35) project-wide `diagnostics()` at ~4.9-5.1 s is now the biggest number in the service by
3x and is **BLOCKED-PENDING-USER**, because round 772 measured reverse-dependency closure DEAD on
*this* corpus (tsc's own sources are `export *` barrels) — it would pay off on a layered
application and buy the benchmark nothing; (INC.36) the `-Xmx2g` floor, where the 264 MB retained
is the number to attack rather than the seconds.

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
