# Status

**AN ERROR-REPORTING QUERY IS 104-108 ms -> 25 ms — THE RE-ENTRANT REPLAY IS **2.25-2.30x**, NOT
THE "DECAYING 1.68x" FIVE ROUNDS RECORDED, AND IT IS NOW WIRED FOR DIAGNOSTICS BEHIND A TYPE-LEVEL
VALVE (2026-08-24, (INC.40)).** The lineage was not wrong about the floor shrinking; it was
measuring the wrong thing. **Every figure in it carried a whole-file `TypeCaptureRequest` in BOTH
arms** — the request `replay-differential.sh` needs in order to GRADE the mechanism — and that is
+9-17 ms per query of cost common to both arms, which **dilutes a ratio and leaves no trace in
it**. Measured that way at HEAD the same run still reads 1.34x; the diagnostics channel asks for no
capture at all, and it is exactly the channel the differential grades as EQUIVALENT. Re-priced in
**two independent JVMs**, warm, six warm-ups, leading draw discarded, ABBA-rotated, tsc's own 78
sources: at `k = 1` **10,656 / 10,783 ms fresh against 4,728 / 4,685 replay = 2.25 / 2.30x**, per
query **104 / 108 ms -> 25 / 25**; at `k = 2` 1.72 / 1.81x; at `k = 8` 1.26 / 1.25x. **The ratio
falls with the working set exactly as it must** — the thing the replay deletes is the floor, paid
once per QUERY and not per file — and **the replay arm's TOTAL lands on the whole-program CHECK
cost** (4,728 against ~4,935 ms), i.e. (INC.37)'s **1.39x re-derivation tax being COLLECTED rather
than re-paid**: 77 fresh checkers each re-derive the shared lib and foreign-declaration
resolutions, one live checker does not. Floor **54 ms**, cross-checked against
`partition-equivalence.sh`'s 61 ms at the same commit.
**IT SERVES DIAGNOSTICS AND NOTHING ELSE, AND THE REFUSAL IS A *TYPE* RATHER THAN A COMMENT.**
`replay-differential` at HEAD: **0 `DIVERGE-DIAG`, 0 `DIVERGE-DEF`** on both arms (46 rows over
tsc's sources, 178 over the 71 `partition-gate` files carrying them, 352,713 definition spans),
against **43 `DIVERGE-TYPE` of 75 files** — the pre-existing HEAD state, overwhelmingly the
union-alias display family (INC.26)/(INC.27) in which the FRESH arm is not automatically the right
one. So `Project.diagnosticsOf` holds the live program through `DiagnosticsOnlyRecheck`, a private
one-way valve whose single member takes a `Set<String>` and returns a `List<Diagnostic>`: **no
`TypeCaptureRequest` is expressible at that boundary and no `CapturedType` can leave it**, so the
caret channels cannot reach it even by mistake. The handle is dropped by `updateFile`,
`deleteFile` and `close`. Queued as **(INC.41)**: closing those 43 is what would let captures
through the same valve, and the prize for it is NOT measured.
**THE ABLATION'S ONE TRANSFERABLE ARM.** Four arms, one mistake each, each verified as a real diff
against the committed file — and in a3 (**the handle serves without widening, so it answers an
empty list**) **the build-COUNT pin stays GREEN**. A count-only suite would have shipped a
language service reporting no errors at all, at full speed, with every cost pin green; the value
pins are what redden. One pin is recorded as UNDISCRIMINATED rather than claimed as coverage.
**TWO INSTRUMENT TRAPS, BOTH FAILING TOWARD A PLAUSIBLE TABLE**: a **floor build is its own code
path**, so floor draws taken after whole-program warm-ups read 129/89/96 ms against a true 52-56 —
a 1.7x over-read that would have understated every derived ratio, now drawn at the END and
cross-checked against the other instrument; and **arming is priced, not assumed free** (an armed
77-query sweep reads 10,546 ms against 10,783 plain, changing no diagnostic row in 231 group
comparisons). Suite **15,824 / 0 / 3** (+9 pins), 0 warnings, `partition-equivalence` EQUIVALENT
78/78, `partition-gate` sensitivity 75/75, `cost_gate.py` all counters in band (`mapped.hits`
+1.63%, the standing drift, NOT rebaselined), `huge_methods --fail-over 0` 0 over limit.
`docs/language-service.md` § 4a.

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
