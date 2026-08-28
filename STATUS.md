# Status

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

**(CHK.65) — A DOMAIN OF EXACTLY ONE LITERAL, MINUS THAT LITERAL, IS **EMPTY**: A SECOND
`!== undefined` GUARD ON THE SAME PROPERTY PATH DID NOT NARROW, AT **TWO** READERS, AND IT
WAS **SHIPPED** (2026-08-28, one commit).** `if (s.p !== undefined) { return s.p; }` twice
over `p: number | undefined` was a false positive on the second read: the first guard's
ELSE branch narrows the path to exactly `undefined`, and [Checker.narrowUnionByLiteral]'s
NON-union `keep = false` arm answered its input UNCHANGED — right for an INFINITE primitive
domain, wrong when the input IS the literal being subtracted. tsc's `filterType` is ONE
function for the union and non-union cases. **An IDENTIFIER subject goes through the M1.9
if-arm machinery and was always correct, which is what hid it — every hand-written
narrowing fixture in this repo uses a local.** Closing the flow walk left a SECOND reader:
the arithmetic/relational operand one, whose flow consult is gated on a UNION base AND
refuses a `never` answer; its suppression must CLAIM the operand or the TS18048 becomes a
TS2365 one line down.

**THE GATE RE-PRICES 7 ROWS -> 6, AND (CHK.63) NOW NEEDS EXACTLY ONE THING.** `armBGR`
(the `canUseTypeEngine` gate + the RETURN/ASSIGNMENT reader consultation +
**(CHK.61)(b)'s checking half, which is MEASURED CORRECT and reproduces tsc 7.0.2 exactly
on a five-reader census**) is 6 rows, **all six the LOOP JOIN**. The loop join itself is
built and re-priced **8 rows -> 3** — the `never` family is closed — and its blocker is
named: **`getUnionType` performs no SUBTYPE reduction**, so a loop join legitimately
produces `ConditionalTypeNode | Node | undefined` where tsc's `UnionReduction.Subtype`
gives `Node | undefined`. Three refusals AT the label were measured; only one does
anything, which proves the offending union is built downstream at a branch join.

**GATES.** Suite **16,339** / 0 / 3 (+13, exactly the new subtests); no corpus baseline
moved. Grid `503774c23b4535130ffdebabef430cf0`, added=0 removed=0. `cost_gate` exit 0,
`output.errors` 46, every counter the standing residual. `huge_methods` 783 classes, 0
over. `partition-equivalence` EQUIVALENT all 78 (floor 56 ms). `capture-equivalence`
DIVERGED 968 / definitions=0 / moreAny=0, and the arm dump is **0 lost, 0 gained, 1
changed** — `checker.ts:30269` going `undefined` -> `never`, tsc's own answer. knip 48,
jsonrepair 4, byte-identical. Vacuity: **7 of 13 pins RED on the parent rebuilt this
session**, exactly the 7 positives. Four ablation arms; a4 read **0 RED until its
separating control existed**, then a unique RED.

**(CHK.63)(a)(c) — THE FOUR RESIDUES ARE **ONE READER GAP PLUS TWO FLOW-WALK DEFECTS**,
AND THREE OF THEM WERE SHIPPED FALSE POSITIVES; THE GATE RE-PRICES **6 ROWS -> 4**
(2026-08-28, three commits).** A per-READER census over PARAMETER sources — the first cut
used a `const` initialised from a call and was VACUOUS, silent on our side even for its own
control — showed that (iii) and the loop family are live FALSE POSITIVES at the
CALL-ARGUMENT reader today, while (iv) and (vi) are correct in the flow walk and were only
ever the READER.

**(a) AN ASSIGNMENT INSIDE A NULLISH GUARD MUST OVERWRITE THAT GUARD'S OWN NARROWING.**
`if (id === undefined) { id = text }` left `id` reading `undefined` for the whole branch —
`narrowByAssignmentRhs`'s resolved-RHS arms FILTER the antecedent and
`narrowUnionByRhsAssignment` answers a non-union receiver unchanged, so the assignment was a
no-op and the join re-minted `string | undefined`. `assignmentReduceBase` applies round 416's
rule there: an assignment OVERWRITES, so the post-state is reduced from the DECLARED type.
**(a2)** its own ablation read **0 RED** and a byte-identical grid, and was UNPINNED rather
than redundant — the separating fixture needs a THREE-member declaration — so the "a nullish
assigned type keeps the antecedent" refusal was deleted too.

**(c) A `!` IS NOT RESPECTED THROUGH PARENTHESES.** `return (t)!` against `string | number`
was a false TS2322 while `return t!` one line away was silent: the operand is admitted by
KIND and a ParenthesizedExpression is not one. `nonNullOperandStrips` reads through parens
and adds the LOGICAL operators — the `server/project.ts:746` shape.

**(b) THE LOOP JOIN IS REFUSED, WITH ITS PRICE AND ITS BLOCKER NAMED.** Every loop erases
every assignment narrowing established before it (`declaredType` at each `FlowLoopLabel`).
The union-with-back-edge-cut was BUILT, makes all ten loop shapes agree with tsc exactly, and
**costs 8 ours-only rows per profile**: **5 are a `never` the loop label was MASKING** (a
negated GENERIC type-guard call), 3 a join over a TRUNCATED antecedent that is LESS reducible
than the declaration. Reverted; the design is kept at
`build/chk63/snap/Checker.kt.gapB-refused`.

**(CHK.63) RE-PRICED: `armG` ALONE is still 6** — the RETURN/ASSIGNMENT readers never consult
the flow walk for a primitive target. **`armGR` (the gate PLUS that consultation; measured,
NOT landed) is 4 distinct rows**: `emitter.ts:1479` + `organizeImports.ts:862` are
**(CHK.61)(b)'s checking half**, and `checker.ts:35649` + `tsserverLogger.ts:28` are **the
refused loop join** (both reads sit inside a loop whose earlier iteration assigns the
reference). So (CHK.63) needs exactly those two things and nothing else.

**GATES.** Suite **16,318 / 16,325 / 16,326**, 0 failed, 3 skipped (+8/+7/+1 — exactly the
new subtests), **no corpus baseline moved on any of the three**. 8-profile grid
`503774c23b4535130ffdebabef430cf0` on all three, byte-identical PER PROFILE against the
recorded parent capture (parent `Checker.class` md5 `eec8ea8f`, rebuilt in this session).
`cost_gate.py` exit 0 three times, `output.errors` **46**, every counter the standing
residual to the digit — the round costs nothing measurable. `huge_methods --fail-over 0`
exit 0, **783** classes. `partition-equivalence` EQUIVALENT all 78 (floors 79 / 63 / 56 ms,
one draw each). knip **48**, jsonrepair **4**, EVERY ROW byte-identical across all three.

**`capture-equivalence` MOVED TWICE AND BOTH MOVES ARE CLASSIFIED PER ELEMENT** with
(CHK.64)'s `XTSC_CAPEQ_DUMP`. DIVERGED **968** in 43 of 76, `definitions=0`, `moreAny=0` —
unchanged throughout. Commit 1: **0 lost, 0 gained, 2 changed**, both DROPPING a spurious
`| undefined`. Commit 2: **0/0/1**, a hover `any` -> the concrete union, and tsc 7.0.2's LSP
answers that same constituent set. Commit 3: **nothing moved**.

**NINE ABLATION ARMS, ONE MISTAKE EACH, EVERY RESTORE `cmp`-VERIFIED PLUS A REBUILT md5.**
a1 (the reduce base never consulted) **4 RED — exactly the (a) positives**; a3 (the nullish-
assigned refusal restored) **1, uniquely the (a2) pin**; b1 (parens not read through) **4**;
b2 (a logical operand does not strip) **3, uniquely the logical pins**; b3 (COMMA admitted)
**1, uniquely the residue pin**. **a2 (the nullish-only antecedent guard dropped) is
UNDISCRIMINATED and NOT shown redundant** — 0 RED and a byte-identical 8-profile grid, so its
KDoc says the bound is REASONED rather than measured. **b4 is UNDISCRIMINATED.** a4 was
WITHDRAWN: its call site belonged to the refused (b) work.

**A KILLED RESTORE LEAVES NO CLASS FILE AND A SCRATCH RUN THEN PRINTS ZERO ROWS**, which
reads exactly like a clean fixture — the 10-minute tool timeout killed an ablation grid
mid-rebuild and the next probe measured a classpath with no `Checker.class` in it.

**(CHK.64)(i)+(ii) — THE FIVE "NARROWING GAPS" ARE **TWO GAPS AT ONE READER**, BOTH LANDED;
(CHK.63)'s PRICE FALLS **11 ROWS -> 6** (2026-08-28, three commits).** Round 784's gate sends
the ASSIGNMENT and RETURN readers to `currentLocalTypes` for a primitive target, and the
legacy filler `extractNullNarrowing` could neither read an `&&` (i) nor look anywhere but a
then-branch (ii). A MEMBER ACCESS, a CALL ARGUMENT and a DECLARATION were already correct in
BOTH families — that census is what collapsed five mechanisms into two, and it is the round's
most transferable finding. `extractNarrowingsFromCondition` now flattens the (left-nested)
`&&` spine ITERATIVELY into a LIST at most one entry per name, and an `if` with no `else`
whose then-branch DEFINITELY EXITS installs the NEGATED condition for the rest of the
enclosing frame — refused unless that frame owns its `localTypes` scope, which is what bounds
it to the block.

**THREE DEFECTS THE GATES FOUND AND READING THE SOURCE DID NOT.** A
`typeof x === "object"` conjunct installed `any` — a WIDENING, 13 captured hovers went from
tsc's own `object`/`unknown` to `any`, and it would DELETE a true positive. Recording the
declared type into the frame's SHARED `narrowedDeclared` (no undo log) leaked across
FUNCTIONS: **21 ours-only rows PER PROFILE**, every one an assignment whose TARGET was read
as another function's same-named binding. And a negated GENERIC type-guard call degraded the
element type — 20 captured spans hovered `any` in tsc's own `path.ts`/`utilities.ts`.

**AND ONE SHIPPED DEFECT FELL OUT AND IS FIXED.** With NESTED narrows on one name the
narrowing frame wrote `narrowedDeclared` UNCONDITIONALLY, recording the OUTER narrow's result
as if it were the declaration, so `if (b) { if (isNs(b)) { b = undefined } }` was a false
TS2322 — reproducible with no early exit anywhere. All three writers are now FIRST-WINS.

**(CHK.63) RE-PRICED: the `armG` arm is 11 -> 6** (`sourcemap.ts:164/165/166` went with (i),
`core.ts:2191` + `path.ts:585` with (ii)). Two of the 6 are (CHK.61)(b)'s absence and (b)
pays them back, so (CHK.64)'s own residue is **4 rows** — and a **SIXTH mechanism the queue
never listed** turned up in it: a NON-NULL ASSERTION `!` is not respected at the return
reader (`server/project.ts:746`).

**GATES.** Suite **16,296 / 16,308 / 16,310**, 0 failed, 3 skipped (+10/+12/+2 — exactly the
new subtests), **no corpus baseline moved on any of the three**. 8-profile grid
`503774c23b4535130ffdebabef430cf0` on both code commits, byte-identical PER PROFILE against a
parent capture rebuilt in this session. `cost_gate.py` exit 0, `output.errors` **46**
(`narrow.walks` +0.84%, well inside the gate). `huge_methods --fail-over 0` exit 0, **783**
classes. `partition-equivalence` EQUIVALENT all 78. knip **48**, jsonrepair **4**, every row
byte-identical against a parent arm rebuilt in this session.

**`capture-equivalence` MOVED AND THE MOVE IS CLASSIFIED PER ELEMENT.** DIVERGED **968** in
43 of 76, `definitions=0`, `moreAny=0` — unchanged. A new instrument (`XTSC_CAPEQ_DUMP`,
committed FIRST) makes the ARM DIGEST's move classifiable, because the digest answers
*whether* and only a per-span dump answers *which*: **0 rows lost**, **+174 definitions
gained**, 2,137 type rows changed — **1,274 drop a spurious `| undefined`**, 300 go `any` ->
concrete, 485 render a narrower/named type (12 of 12 sampled agree with tsc 7.0.2's LSP where
the parent did not), 1 GAINS `| undefined` where tsc agrees with us, and **3 go to `any`** —
all three a member access on a correctly narrowed receiver whose further `&&`-conjunct guard
we do not apply, i.e. the queue's own gap (v).

**SIXTEEN ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, EVERY RESTORE `cmp`-
VERIFIED.** a1 (no decomposition) **5 RED**, a2 (first conjunct only) **2**, a3 (`||`
decomposed) **1** uniquely the `||` control, a4 (`anyType` refusal dropped) **1** uniquely
its guard, a5 (spine site reverted) **2** — the spine is the load-bearing consumer; b1/b3/b9
**7** each, b2 **1**, b4 and b10 **1 each and they redden a RESIDUE pin**, which is what
proves those residues are REFUSALS with a price rather than inabilities; b5 **1**, b7 **1**.
**a6 (the two legacy DISPATCHER sites reverted) is UNDISCRIMINATED and NOT shown redundant** —
a direct CLI probe of that arm produced no row either, because the spine anchors every
`IfStatement` reachable here. **b6 READ 0 AND WAS UNPINNED, THEN FIXED** — two same-named
bindings in one file make it **1 RED, uniquely that row** (the third consecutive round with
that pattern). **b8 is UNDISCRIMINATED and its effect is real but CAPTURE-ONLY**: two
diagnostic fixtures were built and under the embedded lib both narrow precisely, so it is
recorded in the test's KDoc rather than claimed.

**THE INHERITED REPRO WAS SILENT ON BOTH COMPILERS.** `build/chk61b/n1` uses
`string | undefined` against primitive targets, i.e. exactly what the (CHK.63) gate hides. A
NON-NULLISH union (`number | string` + `typeof`) makes all four rows appear on the shipped
binary with no patch — **a nullish fixture is the wrong instrument for anything below that
gate.**


**(CHK.61)(b) — THE DISPLAY HALF **LANDED**; THE CHECKING HALF IS **REFUSED WITH ITS PRICE
FINALLY MEASURED**, AND THE REFUSAL UNCOVERED A SYSTEMATIC **FALSE NEGATIVE** THAT IS NOT (b)
(2026-08-27, three commits).** An optional member's hover now carries `| undefined` and then
RE-NARROWS — `zzzInst.zzzOpt` reads `number | undefined`, and inside `if (o.p)` or an `&&`
chain in either operand position it still reads `number`, all at tsc 7.0.2's own LSP answers.
A UNION receiver is decided PER CONSTITUENT (`memberIsOptionalOnReceiver`), because
`getPropertyOfType`'s union arm answers ONE constituent's symbol (round 916) and the verdict
would otherwise depend on constituent ORDER. Confined to the CAPTURE, which production never
computes, so **every diagnostic gate is byte-identical**.

**THE CHECKING HALF IS NOT SOUND ALONE, AND THE QUEUE'S "3 rows" WAS THE WRONG ARM.**
`build/chk61/patch_b.py` DELETES a true positive on the round's own four-line repro
(`const a: string = o.optNum` reports `Type 'number' …` on the shipped binary and NOTHING
with it) because the source becomes a nullish union and `canUseTypeEngine` refuses those
against a primitive target. Measured on the 8 profiles against a parent capture taken in the
same session: **the gate opened alone is 11 ours-only rows; patch_b AND the gate is 15**
(patch_b FIXES two of the gate's own). `armBG` reproduces tsc EXACTLY on the repro — that is
what the refusal costs, and it is now on record instead of asserted.

**THE SUPPRESSOR HIDES A LARGE FALSE NEGATIVE: `T | undefined` IS SILENTLY ASSIGNABLE TO `T`
at a DECLARATION, an ASSIGNMENT and a RETURN whenever the target is a PRIMITIVE.** Six-line
fixture: tsc 6 rows, us 2 (only the ARGUMENT position and a UNION target work), for
`| undefined` and `| null` alike. Queued as **(CHK.63)** with all 11 rows.

**AND THE FIVE NARROWING GAPS ARE FIVE MECHANISMS, NOT ONE — every one reproduces on the
SHIPPED binary with an EXPLICIT `| undefined` member, no patch and no build.** The `&&`
diagnosis was wrong twice before it was right: the FLOW walk handles `&&` correctly (member
access and argument are both fine), and a DECLARATION with a primitive target narrows. What
does not is an ASSIGNMENT or a RETURN — round 784's gate confines their narrowing block to an
object-ish/union target, so they fall back to `currentLocalTypes`, filled by the legacy
`extractNullNarrowing`, which returns ONE `(name, type)` pair and cannot decompose an `&&`.
**What varies is the READER, not the condition.** Queued as **(CHK.64)**.

**GATES, per commit, all foreground.** Suite **16,281 / 16,283 / 16,286**, 0 failed, 3
skipped (+8/+2/+3, exactly the new subtests), **no corpus baseline moved on any of the
three**. `cost_gate.py` exit 0 on all three, `output.errors` **46**, every counter
digit-identical to (CHK.62)'s standing residual. `huge_methods --fail-over 0` exit 0, **783**
classes, 0 over. **8-profile grid `503774c23b4535130ffdebabef430cf0` on both code commits,
byte-identical PER PROFILE** against a parent capture rebuilt here (`Checker.class`
`e7963e28`). `knip` **48**, `jsonrepair` **4**, EVERY ROW byte-identical against a parent arm
rebuilt in this session. `partition-equivalence` EQUIVALENT all 78 (floors 72 / 66 ms, one
draw each).

**`capture-equivalence` IS THE ONE GATE THAT MOVED, AND EVERY MOVED SPAN WAS CLASSIFIED.**
`DIVERGED` **1,005 -> 985 -> 968** in 43 of 76, `definitions` **360,414 UNCHANGED**,
`narrowRendersMoreAny` 0; both ARM DIGESTs re-recorded per commit (final
`full=2642712547047802314 narrow=6791141519233628706`). Enumerated at
`XTSC_CAPEQ_PRINT=200000`: **all 38 moved spans are the alias-display first-wins family**
((INC.27)) shuffled by a changed first-touch order — not one is an optionality rendering, and
the second commit added ZERO new divergences.

**NINE ABLATION ARMS, ONE MISTAKE EACH (d8 excepted and labelled), EVERY CLASS md5 DISTINCT.**
d0 (widening removed) **4 RED**; **d1 (widened but never RE-NARROWED) 4 — uniquely the four
guarded controls, which is the confinement's own proof**; d2 (optionality gate dropped) **1**,
uniquely the REQUIRED control; d3 (a union decided by ALL not ANY) **1**, uniquely the union
row. **d4 (ask the union ITSELF) READ 0 AND WAS UNPINNED, THEN FIXED** — the fixture wrote
the OPTIONAL constituent first, where `getPropertyOfType` happens to agree; the ORDER
SIBLING makes it **1 RED, uniquely that row** (last round's c2, one round on). **d5/d6/d7
(the `super`, INTERSECTION and already-`undefined` guards) READ 0 AND ARE MEASURED
REDUNDANT** — each has a fixture exercising its shape and each stays green with the guard
removed, because a lower layer already declines; the KDoc now says so instead of claiming a
deliberate refusal, and a two-mistake MECHANISM probe (d8) failed to locate the `super` one
and is reported as a non-result.

**RESIDUE, PINNED WITH THE VALUE WE ANSWER**: `super.<opt>` and an INTERSECTION receiver both
hover `number` where tsc says `number | undefined`.
