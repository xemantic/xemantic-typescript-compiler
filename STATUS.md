# Status

**(CHK.70) + (CHK.63) — THE GATE IS **OPEN**: `T | undefined` IS NO LONGER SILENTLY
ASSIGNABLE TO `T` AT A PRIMITIVE TARGET, AND THE 8-PROFILE GRID IS `added=0 removed=0`
FOR THE FIRST TIME (2026-08-28, three commits `e39e3d13` / `bb41ed96` / `2726021b`).**
Eight rounds refused this on measurement; this one closed the last row and opened it.
**Its cause was NOT the one the queue named.** (CHK.70)(a) — a loop whose back edges only
COMPOUND-assign is bounded by `entry union nonNullish(declaredType)`, no back edge walked
— landed and did NOT move the row: `harness/tsserverLogger.ts:28:5` was **(CHK.70)(c)**,
the LITERAL arm of `narrowByAssignmentRhs`, the one arm (CHK.63)(a) had not routed through
`assignmentReduceBase`. `let r: string | undefined = undefined; r = ""` answered
`undefined`, because a literal cannot restore a member the ANTECEDENT had already lost —
and an assignment OVERWRITES. Both are shipped FALSE POSITIVES in their own right (five and
four, each confirmed against tsgo 7.0.2 through round 784's UNION-target return reader) and
both are grid-identical alone.

**THE GATE NEEDED THREE MORE FIXES, ALL FOUND BY THE SUITE AND NONE BY THE DASHBOARD.**
The RETURN reader must REFUSE a `never` flow answer (its substitution is suppression-only,
so an UNREACHABLE `return undefined` suppressed itself — the corpus baseline
`functionReturn.ts` is the instrument); the weak-type ASSIGNMENT target must see through
the `| undefined` an optional member now carries (tsc distributes the relation over the
union), or TS2559/TS2560 is lost; and `narrowByAssignmentRhs` needed a CONDITIONAL
right-hand-side arm, which no structural test can stand in for. **Three pins INVERTED**,
each a shipped defect the gate closes: two `EarlyExit` RESIDUES recording OUR rows where
tsc is silent, and a `CtaFnBodyAnchorTest` `n == 0` that was a FALSE NEGATIVE tsc reports.

**BOTH REMAINING COSTS ARE NAMED, NOT ABSORBED, AND BOTH ARE PRE-EXISTING GAPS THE GATE
MERELY MAKES VISIBLE.** knip **48 -> 49**: `glob-cache.ts:62:3`, reduced inside knip's own
project to `fs.statSync(dir, { throwIfNoEntry: false })` resolving to `any` for us where
tsc gives `Stats | undefined` — a TYPE-RESOLUTION gap, not a narrowing one (jsonrepair
unchanged at 4). And the capture channel loses **611 of 742,265 spans (0.08%)** from a real
type to `any`, against **451** that correctly GAIN the `| undefined` an optional member has
and 63 that improve: `x?.y` over a `T | undefined` receiver has ALWAYS answered `any` here,
with no optional member needed to show it. **The receiver-half fix was BUILT and MEASURED
and deliberately NOT landed** — it restores all 611 and turns 8 measured false negatives
into true positives, and it costs **2 ours-only rows per profile** at
`moduleNameResolver.ts:706/710`, where it unmasks B83.5 (a nested function's own
`let result: Resolved | undefined` resolving to the ENCLOSING function's `result`).

**GATES.** Suite **16,411 / 0 / 3** (+31 over the round, exactly the new pins), no corpus
baseline moved. Grid `790c337141b167657e4f1f3a219474aa` on all three commits,
`added=0 removed=0` against a parent captured this session from a rebuilt parent binary —
an identical digest. cost_gate rebaselined ONCE, in the gate commit and justified:
`narrow.walks` **+11.17%**, `narrow.memoServed` **+6.61%**, `globals.*` +1.0%, everything
else <= 0.3%, `output.errors` 46, cold self-compile 26,660 ms against the parent's
26.4-26.9 s band (one draw each). huge_methods 783 classes, 0 over.
partition-equivalence EQUIVALENT all 78, floor **62 ms** [62, 60, 52, 73] (one draw).
capture-equivalence DIVERGED **964** in 43 of 76 (from 967), `definitions=0 moreAny=0`.

**(CHK.69) — THE LOOP JOIN'S ~20x IS **MEMOIZATION BEING SWITCHED OFF**, A *SOUND* CUT-KEYED
MEMO RECOVERS **0.003%**, AND THE PRIZE TURNS OUT TO NEED **NO BACK EDGE AT ALL**
(2026-08-28, one commit `92598fb0`).** (CHK.66)(b)'s cost was reproduced digit-for-digit,
then ATTRIBUTED. Removing the `narrowLoopCutUsed` suppression from the memo-store gate
(unsound — the CEILING of what any memo can return) takes `globals.lookups`
15,128,215 -> **1,630,952** and `typeNode.cacheable` 10,831,464 -> **885,424** (-89.2% /
-91.8%) and the cold wall 91.7 s -> 44.1 s: **~90% of the blowup is the suppression**, so
the loop body's paths are ENUMERATED instead of folded. **A SOUND repair of it measures
ZERO** — giving `NarrowFlowMemo` a `cuts: LongArray` (a rolling hash of the in-progress
label set as an extra equality field) reads 15,127,750, i.e. **0.003%**, because the cycle
almost never closes ON the loop label; it closes on the walk's OWN PREFIX, which is
path-dependent by construction. **And the ceiling is still +115% globals lookups, +395%
type-node resolutions and 44.1 s against a parent 26.7 s — refused in its BEST case.**

**WHAT LANDS IS THE LOOP JOIN'S OWN FIXPOINT ARGUMENT USED FORWARD.** `L = E union (union
of narrow_i(L))`; when no back edge ASSIGNS the reference every back edge is a pure
NARROWING, so **the fixpoint IS the entry state** and the label can be answered by
FOLLOWING ITS ENTRY — no traversal, no cut, no suppression. `loopBodyMayAffectName` decides
it by pure graph reachability and answers conservatively on anything it cannot rule out.
**Plus a shipped BINDER false negative:** `bindForInStatement`/`bindForOfStatement` joined
the PRE-loop flow to the post-loop label instead of the LOOP LABEL (tsc sets
`currentFlow = preLoopLabel` first), so a `for-in`/`for-of` body was unreachable BACKWARD
from any read after the loop and `for (const n of xs) { h.req = 1 }` did not invalidate a
narrow. One commit, because each half alone regresses the other's shape. **On 14
hand-written shapes the parent has 5 shipped FALSE POSITIVES and 2 shipped FALSE NEGATIVES
and the shipped binary reproduces tsc 7.0.2 EXACTLY.**

**(CHK.63) IS RE-PRICED TO *ONE ROW ON ONE PROFILE* AND IS NOW AFFORDABLE — AND IS STILL
NOT OPENED.** The combined arm is `added=0 removed=0` on seven profiles and `added=1` on
`tsc-harness` (`tsserverLogger.ts:28:5`); (CHK.66)(b)'s residue `checker.ts:43282:21` is
GONE. Cost: `narrow.walks` +11.2%, `narrow.memoServed` +6.6%, everything else <= 1%, wall
flat. One ours-only row on a dashboard whose v1 exit is zero FPs is a decision to take at
0 — the cause is named and queued as (CHK.70)(a): a COMPOUND assignment (`result += …`)
inside a loop has no post-state rule.

**GATES.** Suite **16,367** / 0 / 3 (+11, exactly the new pins; **16,380** after the rebase onto the (LIB.4) arc, which does not touch the checker — `Checker.class dcaf1594` either side); **no corpus baseline
moved**. Grid `790c337141b167657e4f1f3a219474aa` (a NEW recipe — not comparable to
(CHK.68)'s `503774c2…`), `added=0 removed=0` on all eight against a parent capture taken
this session from a rebuilt parent (`Checker.class b2675304`), and the digest is IDENTICAL
to the parent's. `cost_gate` **REBASELINED** — +0.43 pp on `globals.misses` over the
standing residual pushed it to +2.20%, and the rebaseline also absorbs the residual
accumulated before this round. `huge_methods` 783 classes, 0 over. `partition-equivalence`
EQUIVALENT all 78 (floor **63 ms**, one draw). `capture-equivalence` DIVERGED **967** in 43
of 76 (from 968), definitions=0, moreAny=0, both arm digests re-recorded — classified per
element, **168 of 742,255 spans change, 0 LOST and 11 GAINED**, of which 66 are `any` -> a
real type and 29 are `X | undefined` -> `X`. knip **48**, jsonrepair **4**, byte-identical.
Vacuity: **8 of 11 pins RED on the rebuilt parent**, exactly the 8 positives, 3 controls
green on both. Four ablation arms, one mistake each: a1 **9 RED**, a2 **4 RED** (distinct
sets), a4 **5 RED**; **a3 (the `never` refusal) is 0 RED and UNPINNED — but MEASURED**, the
arm adding exactly the five `emitter.ts` `never` rows on the grid. My first a2 was a **DEAD
ARM** that read 0 RED and looked redundant while passing the `cmp`-against-its-own-snapshot
check, and my first pin family was **vacuous in both directions** because an IDENTIFIER
subject is answered from `currentLocalTypes`, which is loop-blind.

**(LIB.4) — `cronstrue` COMPILES TO JVM BYTECODE; WHAT STOPS IT RUNNING IS THE NOMINAL
HALF (2026-08-28, six commits, corpus 17-29).** Its English entry point — 11 files of
published source, unmodified — reads `successful=true` with the checker at **0 errors,
agreeing with tsgo 7.0.2 exactly**, and fails at RUN time on one thing, twice: a generated
CLASS instance cannot flow into an INTERFACE-typed slot, because an interface erases to the
property bag. That is `docs/kir-structural-typing.md`'s measured-but-unbuilt candidate (1),
now queued as (LIB.6) with a cheaper alternative priced against it.

**THE QUEUE'S FIVE RUNGS WERE HALF THE LADDER — THIRTEEN CAPABILITIES WERE NEEDED**, because
the earlier session peeled its list by patching a throwaway copy, which walks past whatever
the patch removed. Re-probing the UNMODIFIED library after each fix found the other eight.
**Four of the five defects the arc surfaced are SILENT wrong answers**: `for (let j …)` had
no per-iteration binding (every closure shared one variable — `3,3,3` where JavaScript says
`0,1,2`); `toFixed` used the machine's LOCALE (`"2,0"` here, invisible on en-US CI); the
array callbacks were typed `Function1` and truncated JavaScript's `(element, index, array)`;
and this arc's own `var` hoisting emitted into a shape class's synthesized constructor. Every
corpus `.expected` in 17-29 is `node`'s own stdout. Full suite **16,339 / 0**.


**(CHK.68) — `x = y = z` WAS A **SHIPPED** FALSE POSITIVE AND IT LANDS; THE GATE RE-PRICES
**6 ROWS -> 5** AND THE COMBINED ARM IS **EXACTLY 1 ROW** — BUT THE LOOP JOIN IT NEEDS IS A
**~20x COST BLOWUP NOBODY HAD PRICED** (2026-08-28, one commit `2cbb3847`).** `armBGR` was
re-measured on top of (CHK.66)(a) and is UNCHANGED at 6 rows — the subtype reduction closes
none of them. (CHK.67) was then diagnosed and the queue's description of it was half wrong
in the useful direction: of its two named shapes, `index = index! + 1` was ALREADY handled
by the (CHK.33) arm and the CHAINED assignment is the whole gap. It is reachable with NO
gate and NO loop at the UNION-target declaration reader
(`let i: number|undefined; i = c = o.len; const p: number|string = i`), because every arm
of `narrowByAssignmentRhs` classifies the RHS syntactically and `y = z` matches none of
them — a `BinaryExpression` whose operator IS `=`, which (CHK.33) excludes by construction.

**THE GATE IS ONE ROW AWAY IN DIAGNOSTICS AND NOWHERE NEAR IT IN COST.** The COMBINED arm
(gate + RETURN/ASSIGNMENT readers + (CHK.61)(b)'s checking half + (CHK.67) + the loop join)
measures `added=1 removed=0` on all eight profiles, the row being (CHK.66)(b)'s known
`checker.ts:43282:21`; the five `armBGR` survivors were read individually and are ONE
mechanism (a narrow established OUTSIDE a loop, lost inside or after it), all five removed
by the loop join. **But the loop join ALONE measures `globals.lookups` +1,891%,
`typeNode.cacheable` +5,951% (99.45% of them cache HITS), `typeOfExpr.calls` +116%,
`narrow.memoServed` +1,276%, `narrow.walks` +37% and ~3.5x wall, with `spine.nodes` +0.00%
and `typeOfExpr.distinct` +0.98%** — the population is unchanged and the same questions are
re-asked ~20x. It was priced in ROWS for three rounds (8 -> 3 -> 1) and never in counters.
**The gate is refused again, on a reason no earlier round had.**

**GATES.** Suite **16,356** / 0 / 3 (+8, exactly the new subtests); **no corpus baseline
moved**. Grid `503774c23b4535130ffdebabef430cf0`, added=0 removed=0 on all eight against a
parent capture taken this session from a rebuilt parent (`Checker.class 19b32bf2`).
`cost_gate` exit 0, `output.errors` **46**, counters the standing residual to the third
decimal. `huge_methods` 783 classes, 0 over. `partition-equivalence` EQUIVALENT all 78
(floor **61 ms**, one draw). `capture-equivalence` DIVERGED 968 in 43 of 76, definitions=0,
moreAny=0, **both arm digests UNCHANGED**. knip **48**, jsonrepair **4**, byte-identical to
arms from the rebuilt parent. Vacuity: **6 of 8 pins RED on the rebuilt parent**, exactly
the 6 positives. Three ablation arms, **all three uniquely discriminating** (a1 6 RED, a2 1
RED = P5, a3 1 RED = P6) — no zero of any kind to report.

**LAST ROUND'S TWO GAPS ARE CLOSED.** (CHK.66)'s capture digest move, classified per
ELEMENT against the pre-(CHK.66) parent rebuilt to `Checker.class d0997340`: **67 spans of
742,254 moved — 0.009% — and NOT ONE GAINED A MEMBER.** 57 are pure DROPS of a strict
subtype of a survivor, 3 collapse to the alias the source itself spells (better), 3 are
member REORDERINGS, and the two non-improvements are named — one alias NAME lost to the
first-wins (INC.29) family, and 3 go-to-definition location lists that lost the dropped
constituent's own declaration. And a genuine parent library arm was rebuilt: knip 48,
jsonrepair 4, byte-for-byte identical.

**(CHK.66) — A FLOW JOIN NOW REDUCES SUBTYPES: `string | number | "a"` WAS A **SHIPPED**
DIVERGENCE AT A PLAIN BRANCH LABEL, AND THE LOOP JOIN RE-PRICES **3 ROWS -> 1**
(2026-08-28, one commit `ad888740`).** The queue named the loop join's blocker as
`getUnionType`'s missing subtype reduction and located the offending union "downstream, at
a branch join". Measured, that defect needs no loop at all:
`const x = zzzMk(); if (x === "a") { } const p: boolean = x` reported
`string | number | "a"` where tsc 7.0.2 reports `string | number` — four lines, no
partition, no gate. It is not only display: the extra member survives a later DISCRIMINANT
test that would have filtered the supertype away. `flowJoinUnion` applies tsc's
`UnionReduction.Subtype` at the TWO flow joins in `narrowTypeFromFlowCore` and NOWHERE
else — INV.5(a) interns unions by member-id list and union member ORDER is pinned
byte-for-byte across ~13k baselines, so reducing inside `getUnionType` was refused on
sight. Two conservatisms, both pinned: only a member the DECLARATION does not itself
contain may be dropped, and the drop needs a STRICT subtype (**`subtypeRelation` is
declared in this repo and has ZERO readers** — only assignability exists).

**THE LOOP JOIN, RE-PRICED ON TOP OF IT: 3 -> 1.** With both joins routed through the
helper, the loop arm costs exactly ONE ours-only row on every one of the 8 profiles
(`checker.ts:43282:21`); **both `utilities.ts` rows are CLOSED**. The survivor is a
different mechanism — `SignatureDeclaration`'s own 14 constituents plus
`ClassDeclaration | ClassExpression`, i.e. a discriminant/`isFunctionLike` filter gap over
a loop-carried state. (CHK.63)'s own `armBGR` grid was NOT re-run and its 6-row list
stands as last measured.

**GATES.** Suite **16,348** / 0 / 3 (+9, exactly the new subtests); **no corpus baseline
moved**. Grid `503774c23b4535130ffdebabef430cf0`, added=0 removed=0 on all eight — byte
identical to the parent rebuilt this session (`Checker.class d0997340`). `cost_gate` exit
0, `output.errors` 46, every counter the standing residual moved in the third decimal.
`huge_methods` 783 classes, 0 over. `partition-equivalence` EQUIVALENT all 78 (floor
**54 ms**, one draw). `capture-equivalence` DIVERGED 968 in 43 of 76, definitions=0,
moreAny=0 — unchanged in every field; both arm digests moved and were NOT classified per
element this round. knip 48, jsonrepair 4. Vacuity: **7 of 9 pins RED on the parent**,
exactly the 7 positives. Three ablation arms — a1 **7 RED**, a2 **1 RED** uniquely (its
separating control), a3 **0** and named **UNDISCRIMINATED rather than redundant**.
