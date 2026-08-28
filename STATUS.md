# Status

**(CHK.72)(a) — THE FLOW WALK'S CALL SHORTCUT DID **NO OVERLOAD SELECTION**, AND knip's ROW
IS A DEFAULT/NAMESPACE IMPORT TYPING AS `any` (2026-08-28).** The queue's attribution was
wrong a tenth time. `resolveFlowCalleeDecl` answers `valueDeclaration ?:
declarations.firstOrNull()`, so both consumers reading a RETURN ANNOTATION off it answered
about the FIRST signature: the post-overwrite reset installed the **wrong overload's return**
and the structural non-nullish claim **stripped a `| undefined` the selected overload
genuinely has**. Universal, not a `declare function` curiosity — an implementation-bearing
overload set, an interface method pair and a `declare namespace` member all read first-wins,
and **arity alone did not discriminate**. Both now route through the engine's own overload
resolution. **The "conservative" first version cost a row**: merely refusing the non-nullish
claim lost round 465's destructured-member proof at `esDecorators.ts:1309` on every profile
(`factory.getGeneratedNameForNode` is two overloads that BOTH return `Identifier`) —
conservatism is not free when the claim is what SUPPRESSES a diagnostic, and only the grid
said so. **knip's row is NOT `statSync`**: `import { statSync } from 'node:fs'` answers
`Stats | undefined` correctly while `import fs from 'node:fs'` makes `fs` ITSELF `any`, so
`glob-cache.ts:62` is unreachable by any narrowing or overload work. Re-queued as (CHK.73)
with the blast radius measured (23-147 relative `import * as` sites per profile against ~5-14
non-relative ones). **GATES.** Suite **16,417 / 0 / 3** (+6, exactly the new pins), no corpus
baseline moved; grid `790c337141b167657e4f1f3a219474aa`, `added=0 removed=0` on all eight;
cost_gate exit 0 with `output.errors` **46** (largest move `narrow.memoServed` +1.55%);
huge_methods 783 scanned / 0 over; partition-equivalence EQUIVALENT all 78, floor 67 ms (one
draw); capture-equivalence DIVERGED **964** in 43 of 76, `definitions=0 moreAny=0` (both arm
digests moved and are re-recorded — expected for a change that alters a type in both arms);
knip **49**, jsonrepair **4**, both unchanged.

**(CHK.70) + (CHK.63) — THE GATE IS **OPEN**: `T | undefined` IS NO LONGER SILENTLY
ASSIGNABLE TO `T` AT A PRIMITIVE TARGET, AND THE 8-PROFILE GRID IS `added=0 removed=0`
FOR THE FIRST TIME (2026-08-28, three commits `2ed1779b` / `acb6d92b` / `7a488783`).**
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
