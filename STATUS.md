# Status

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

**(CHK.61)(a) — THE ONE LINE THAT CLOSES THE WHOLE `this`-RECEIVER FAMILY — **LANDED**, AT
**ZERO** DASHBOARD ROWS, AFTER THREE ROUNDS OF REFUSALS (2026-08-27, (CHK.62b) +
(CHK.61)(1) + (CHK.61)(a), four commits).** `computeRawTypeOfPropertyAccess` typed its
receiver with `getTypeOfExpression`, which answers `any` for `Identifier("this")` — and
`any` is legal everywhere, so the entire family failed in the FALSE-NEGATIVE direction with
nothing to see. `thisReceiverCarrierType` supplies `currentClassForThis`'s declared instance
type, only where the receiver already typed `any`/`error`. Measured on
`build/chk60/br/b2.ts`: **3 of tsc 7.0.2's 7 rows before, all 7 after, at tsc's own
positions** — and **`definitions` 360,376 -> 360,414**, i.e. go-to-definition on a
`this.<member>` caret now RESOLVES.

**THE PRICE FELL 6 -> 3 -> 1 -> 0 ACROSS FOUR ROUNDS, AND EVERY STEP WAS A DIFFERENT,
PRE-EXISTING DEFECT THE `any` WAS HIDING.** (CHK.62) closed gaps 3 and 4 (6 -> 3);
**(CHK.62b)** closed the third — an assignment whose RHS is a **`this`-method call** did not
narrow the assigned reference, because `rhsIsDefinitelyNonNullish`'s CALL arm resolves the
callee through `resolvePropertyMethodDecl`, which TYPES THE RECEIVER and bails on `any`
(3 -> 1) — and it reproduces on the SHIPPED binary whenever the reference's declared union
comes from something other than `this`; **(CHK.61)(1)** closed the last, merging an
INTERSECTION source in the ACCEPTING direction (1 -> 0).

**THE REVERTED (CHK.61)(1) ATTEMPT WAS THIS RULE BEING *UNSOUND*, NOT AN UNMASKED DEFECT
ELSEWHERE — AND A CENSUS OF THE NEWLY ACCEPTED PAIRS SAID SO IN ONE RUN.** All four were
one shape: `FunctionExpression & { name: undefined; parent: … }` accepted against
`{ name: Identifier }`. The merge rule is "the intersected member is a subtype of EVERY
declaration, so ANY relating declaration suffices" — valid only when each declaration's type
is spelled out INCLUDING its optionality. We model an optional member as plain `T`
((CHK.61)(b)), so `FunctionExpression`'s `name?: Identifier` was picked where the real
intersected member is `undefined`, and the next negative type-predicate narrow then
subtracted that constituent and left `never` — `callHierarchy.ts:199`. A source-side
`| undefined`, LOCAL to the rule, fixes it.

**GATES, per commit, all foreground.** Suite **16,262 / 16,267 / 16,272 / 16,273**, 0
failed, 3 skipped (+5/+5/+5/+1, exactly the new classes), **no corpus baseline moved on any
of the four.** `cost_gate.py` PASSES on all, `output.errors` **46**, every counter within a
hundredth of a percent of (CHK.62)'s standing reading (`typeOfExpr.calls` +1.41/+1.42%,
`globals.lookups` +1.52%). `huge_methods --fail-over 0` exit 0, **783** classes, 0 over.
**8-profile grid `503774c23b4535130ffdebabef430cf0` — the standing value — on all three
code commits INCLUDING the one that lands (a): `added=0 removed=0` on all eight.** `knip`
**48** and `jsonrepair` **4**, every row byte-identical. `partition-equivalence` EQUIVALENT
all 78 (floors 56 / 57 / 66 ms, one draw each).

**`capture-equivalence` MOVED BOTH ARM DIGESTS ON THE (a) COMMIT AND THAT IS THE POINT.**
`full=5591703872112101713 narrow=704838071822341252`, `definitions` **360,376 -> 360,414**,
with `DIVERGED` UNCHANGED at 1,005 spans in 43 of 76 (types=1005, definitions=0, moreAny 0)
— a full-build fix, so the full-vs-narrow relationship is untouched; (INC.26)'s rule.

**NINE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT.** a0/a1 (the (CHK.62b)
carrier removed / present-but-inert) **3 RED each — a ROUND-927 PAIR, one observable**;
a2 (any resolved `this.m()` treated as non-nullish) **1, uniquely the nullable-return row**;
b0 (the acceptance leg) **2**, b1 (the source-side `| undefined` — the earlier attempt's
exact mistake) **1**, b2 (the missing-required-property refusal) **1**; c0 (the (a) carrier)
**3**. **TWO ARMS READ 0 AND ARE RECORDED, NOT CLAIMED**: c1 (consult the carrier SECOND) is
a REDUNDANT guard — the fallback for a bare `this` is exactly `anyType`, so the two orders
are observationally identical — and **c2 (the carrier answers for EVERY identifier receiver)
was UNPINNED**, which the round fixed rather than excused: the added
`zzzM(zzzP: any) { zzzP.zzzReq }` control makes c2 red uniquely.

**RESIDUE, RE-QUEUED**: (CHK.61)(b), the dropped `| undefined` at
`computeRawTypeOfPropertyAccess`'s three `prop != null` returns — still 3 rows, still five
narrowing gaps, and now the reason `this.zzzOpt` reads `Type 'number'` where tsc reads
`Type 'number | undefined'`; and a PROPERTY-access assignment RHS (`p ??= o.zzzFld`) does
not narrow at all, which is not `this`-shaped.

**THREE OF (CHK.61)'s FOUR UNMASKED GAPS ARE CLOSED, **TWO OF ITS FOUR DIAGNOSES WERE
WRONG**, AND THE `this`-RECEIVER PATCH NOW COSTS **3** DASHBOARD ROWS INSTEAD OF 6
(2026-08-27, (CHK.62), three fixes).** Gap **4**: `spreadGuaranteedProps` had no
`Type.Intersection` arm, so `{ ...mk(), insertString }` with an intersection-returning `mk`
(harness `client.ts:242`) reported TS2739 for the five properties the spread supplies — a
union guarantees what EVERY constituent has, an intersection what ANY does. Gap **3**: an
optional source parameter's type is `T | undefined` (tsc's `addOptionality`) and we modelled
it as `T`, so `(x?: string) => void` was not assignable to `(x: string | undefined) => void`
— **not** the recorded "function-type properties are compared covariantly", which is false:
our parameter contravariance was always correct and the METHOD form fails identically. Gap
**2**: a call to a `: never` function now DIVERGES (tsc's `unreachableNeverType`), so
`Debug.assertNever(kind);` in a switch `default:` no longer merges the pre-switch type back
in — but the recorded "a SHORTHAND object-literal property does not flow-narrow" is also
false, and **the row it was written for did not move**.

**RE-BISECTING THE ROW THAT DID NOT MOVE FOUND A THIRD, `this`-MEDIATED GAP, QUEUED AS
(CHK.62b)**: an assignment whose RHS is a **`this`-METHOD CALL** does not narrow the assigned
reference — `let p = this.find(); p ??= this.create(); return { p }` reports
`p: T | undefined` with NO switch in it, while the identical assignment with a free-function
RHS is silent. It is invisible without the (a) patch, because `this.create()` types `any`
today; closing it takes (a) from 3 rows to **1** (the un-merged intersection source alone).

**THE PERF DESIGN OF THE DIVERGING-CALL PREDICATE IS THREE MEASURED GATES.** Resolving the
callee on every flow call reads `typeOfExpr.calls` **+9.61%** / `globals.lookups` **+4.43%**
(COST GATE FAILED) because `callHasNeverReturnAnnotation` reaches `resolvePropertyMethodDecl`,
which **types the receiver**. Requiring an `ExpressionStatement` parent -> +3.19%/+2.22%;
using the symbol-table-only `resolveNamespaceMemberFnDecl` -> `typeOfExpr.calls` **+1.42%,
digit-identical to standing**; pre-gating the namespace receiver on `currentFileLocals` ->
`globals.lookups` **+1.52%**, gate PASSES. A per-request memo measured **exactly zero** — the
population is one ask per call per compile.

**GATES, per commit.** Suite **16,247 / 16,252 / 16,257**, 0 failed, 3 skipped (+4/+5/+5,
exactly the three new classes), **no corpus baseline moved on any of the three**.
`cost_gate.py` PASSES on all three, `output.errors` **46**; `typeOfExpr.calls`,
`narrow.walks`, `narrow.memoServed` and `spine.nodes` digit-identical to (CHK.61c/d).
`huge_methods --fail-over 0` exit 0, **783** classes, 0 over. **8-profile grid
`503774c23b4535130ffdebabef430cf0` on all three** — the standing value, per-profile
`added=0 removed=0` on all eight. **`knip` 48 and `jsonrepair` 4, every row byte-identical.**
`partition-equivalence` EQUIVALENT all 78. `capture-equivalence` **1,005 span(s) in 43 of 76,
moreAny 0, definitions 360,376 — unchanged — but BOTH ARM DIGESTS MOVED on the gap-2 commit**
(`full=-7560141526203174980 narrow=-5179824964953234569`), which is (INC.26)'s expected
behaviour for a FULL-BUILD fix and is re-recorded, not read as a regression: commits 1 and 2
reproduced the old digests exactly.

**FOUR ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, NO ZEROS.** a0 (the
intersection arm reverted, `8b85adc4`) 2 RED; a1 (the optional widening reverted, `d6a568d0`)
3 RED; a2 (`flowCallDiverges` forced false, `d6e9f431`) 3 RED; a3 (only its namespace-member
arm off, `957b605e`) **1** RED, uniquely the `Debug.assertNever` positive. The
`currentFileLocals` pre-gate has NO arm and is NOT claimed as coverage — it is a perf guard
with no observable behaviour, graded by `cost_gate.py`, both readings recorded.

**AND `python3 scripts/cost_gate.py 2>&1 | grep …; echo "exit=$?"` READS THE *GREP's* STATUS**
— a FAILING gate prints a plausible table and `exit=0`. Redirect to a file and read `$?` from
the gate.


**THE TWO DEFECTS (CHK.61) NAMED WERE BUILT, PRICED AND **REFUSED** — EACH UNMASKS
PRE-EXISTING ENGINE GAPS AS DASHBOARD FALSE POSITIVES — AND THE PRICING TURNED UP TWO OTHER
DEFECTS THAT WERE FREE TO FIX (2026-08-27, (CHK.61c)+(CHK.61d), two fixes).** (a) taking
`currentClassForThis` as the receiver type when `this` types `any` is ONE line and closes
**every** row (CHK.60) measured — `build/chk60/br/b2.ts` goes from 3 of tsc's 7 rows to all
7 — at a price of **+4 harness / +2 server** profile rows (compiler profile 46 -> 46, corpus
GREEN, both libraries byte-identical). (b) `| undefined` on an optional property access is
one line and prints tsc's exact text, at **3** compiler-profile rows (its corpus and
library price is UNMEASURED — only the grid was run for (b)). Every one of those 9
rows is a FALSE POSITIVE from a pre-existing gap the `any` was hiding, so under this arc's
own convention ((CHK.51) kept a firewall "worth 43 rows"; (INC.42) narrowed rather than
shipping FPs) both are refused, mapped cause by cause with `this`-free repros, and queued.

**(CHK.61c) A TYPE REFERENCE INSIDE A `namespace` BODY RESOLVED THE *OUTER* SCOPE FIRST** —
`getTypeFromTypeReference` asked the enclosing namespace only as a FALLBACK, so a namespace
member whose name ALSO exists globally resolved to the outer declaration. Silent in the
dangerous direction: the outer type is a REAL type, so the annotation is judged against the
wrong shape rather than against none. It is the CAUSE of (a)'s only corpus regression —
`variableDeclaratorResolvedDuringContextualTyping`, where `namespace WinJS { declare class
Promise { then(): Promise } }` resolved `Promise` to the LIB `Promise<T>` and whose PRISTINE
baseline reports nothing at that line.

**(CHK.61d) `f!()` DISCARDED THE ASSERTION AT BOTH SITES THAT CLASSIFY A CALLEE** —
`getCalleeType`'s NonNull arm and `getReturnTypeOfCallExpression`'s unwrap loop — so a
`T | undefined` callee arrived as a UNION and failed twice: TS2349 where tsc is silent, AND
`if (calleeType !is Type.Object) return anyType`, so the call's RETURN TYPE was never
resolved and `const s: string = f!()` reported NOTHING. **It is the gate on (b)**: with (b)
applied and this present the compiler profile gains **19** rows of which **17** are this one
class (`host.readDirectory!(…)`, `resolutionHost.realpath!(…)`); with it fixed, 3.

**THE MOST TRANSFERABLE FINDING IS A REVERTED FIX.** An acceptance leg for the merged
INTERSECTION SOURCE — consulted only after "some constituent relates" has already answered
false — closes its row and ADDS `'parent' does not exist on type 'never'` on two profiles,
because an acceptance feeds `typeGuardMemberDisjoint` and narrows to `never`. **"Acceptance
only, so it cannot introduce a diagnostic" is true of the RELATION and false of the
COMPILER.**

**GATES.** Suite **16,243 / 0 / 3** (+9, exactly the two new classes), **no corpus baseline
moved**. `cost_gate.py` `output.errors` **46**, all 20 counters digit-identical to
(CHK.60)'s reading. `huge_methods --fail-over 0` exit 0, **783** classes, 0 over.
**8-profile grid md5 `503774c23b4535130ffdebabef430cf0`**, `added=0 removed=0` on all eight
— unmoved since (CHK.54). `partition-equivalence` **EQUIVALENT, all 78**, floor **56 ms**
[55, 56, 57, 54] — one draw. `capture-equivalence` **1,005 / 43 of 76 / moreAny 0**,
`definitions` **360,376**, both ARM DIGESTs unmoved. **`knip` @ `dc7aca5` 48 and
`jsonrepair` 3.13.1 4, EVERY ROW BYTE-IDENTICAL.**

**FIVE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT, NO ZEROS.** a0 (both
changes reverted, parent rebuilt) **5 RED — every positive**; a1 (the namespace reorder
reverted) **2**, uniquely the namespace positives; a2 (`getCalleeType`'s NonNull arm
reverted) **1**; a3 (the return-type restore reverted) **2**; a4 (the `SymbolFlags.Type`
filter dropped) **1**, uniquely the value-export control. **TWO DRAFTS OF ONE PIN WERE BLIND
AND a1 IS WHAT SAID SO** — asserting the ABSENCE of a TS2339 reads GREEN against the ablated
binary in BOTH shapes of the shadowed global, so only a DIFFERING RETURN TYPE discriminates.
**AND THE FIRST a4 WAS A DEAD ARM**: a class's own type parameter does not reach the checker
through `currentTypeParamScope` at all, so that control has no discriminating arm and is not
claimed as coverage.

**AN ENUM MEMBER IS A STRING OR NUMBER **LITERAL** IN tsc, SO ITS APPARENT TYPE IS THE
`String` / `Number` WRAPPER — AND WE WERE REPORTING **13** FALSE POSITIVES BECAUSE OF IT
(2026-08-27, (CHK.60), one fix).** tsc's `TypeFlags.StringLike` is
`String | StringLiteral | TemplateLiteral | StringMapping` and an enum literal type carries
`StringLiteral | EnumLiteral`, so `getApparentType(E.A)` answers `globalStringType`; a
numeric member carries `NumberLiteral | EnumLiteral` and answers `globalNumberType`.
(REL.1)(b) mints a member-LESS `Type.Object` here instead, and `propertiesRelatedTo`'s
`source.members == null` arm answers `targetProps.isEmpty()` — which rejects EVERY target
that declares a property, **including an all-optional (weak) one**. So `zzzG(E.A)` against
`{ length?: number }` was a false positive at the argument AND the assignment position.

**THE WEAK RULE WAS NOT WHAT FIRED, AND THE QUEUE ENTRY SAID SO** — it correctly DECLINES a
target the source shares a property with; the ordinary relation is what emitted. The fix is
in `structuredTypeRelatedTo`'s object/object leg, which — **only after the structural
comparison has already answered false** — retries an enum-literal source as its apparent
PRIMITIVE. Retrying as the PRIMITIVE rather than reaching for a wrapper here is the whole
design: it routes the source through exactly the legs a `string`/`number` source already
takes (B69.8's wrapper/named-interface leg, round 430's empty-`{}` rule, B418's
index-signature rule, (CHK.32)'s anonymous-object leg), each with its own measured guards
intact — which is why a NAMED interface, the `String` wrapper itself and `Object` all come
right for free, and why an INDEX-SIGNATURE target's answer is untouched.

**MEASURED OVER A 30-ROW SOURCE/TARGET MATRIX AGAINST tsc 7.0.2** (`build/chk60/mx`): 13
ours-only rows removed, every remaining row byte-exact, and the three correct refusals
unmoved (a target sharing nothing -> TS2559; string-member-vs-`Number` and
numeric-member-vs-`String` -> TS2345). The matrix is also what showed the boundary is per
MEMBER and not per enum: tsc accepts a MIXED enum's string member against `{ length?: … }`
and its numeric member against `{ toFixed?() }`, and rejects the WHOLE mixed enum against
both — which our per-member value lookup reproduces with no special case.

**GATES.** Suite **16,234 / 0 / 3** (+11, exactly the one new class), **no corpus baseline
moved**. `cost_gate.py` **`output.errors` 46** and — measured against the a0 parent rebuilt
in this session — **all 20 counters DIGIT-IDENTICAL**; the standing +1.42% on
`typeOfExpr.calls` is inherited drift against a baseline last recorded at (CHK.46), not this
round's. `huge_methods --fail-over 0` exit 0, **783** classes, 0 over. **8-profile grid**
md5 **`503774c23b4535130ffdebabef430cf0`**, per-profile `diff` clean: **`added=0 removed=0`
on all eight**, unmoved since (CHK.54). `partition-equivalence` **EQUIVALENT, all 78**, floor
**56 ms** [54, 59, 52, 56] — one draw. `capture-equivalence` **1,005 span(s) in 43 of 76,
types=1005 definitions=0, moreAny 0**, `definitions` **360,376**, ARM DIGESTs
`full=-3735929574989657502 narrow=-2075467818767010709` — the standing state, so the fix
moved no captured type. **`knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, EVERY
ROW BYTE-IDENTICAL** to an a0 arm rebuilt in this session.

**SIX ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT.** a0 (the whole change
reverted — the parent, rebuilt) **6 RED**, exactly the six positives; a3 (the (CHK.32) leg's
anonymous-target scope wrongly inherited) **2**, uniquely the named-interface and
String-wrapper pins; a4 (the flavour collapsed to `string`) **1**, uniquely the
numeric-member-vs-`String` control — and NOT the two numeric positives, because an
ALL-OPTIONAL target cannot discriminate a flavour, which is what makes the wrapper controls
load-bearing. **a1 (the IDENTITY guard dropped) 0** and recorded as UNDISCRIMINATED, not
redundant: B69.8's wrapper arm is not relation-gated, so dropping it would let an enum
member be declared IDENTICAL to `String` — a widening nothing here reaches, and tsc guards
the same work the same way. **a5 (the retry promoted from a FALLBACK to a SUBSTITUTION) 0**,
and re-run against the 30-row matrix it is BYTE-IDENTICAL too — so the "fallback, not
substitution" framing is a claim about blast radius that this repo currently cannot
demonstrate, and it is recorded as such.

**a2 IS THE ARM WORTH READING, AND ITS ZERO IS A DELIBERATE REFUSAL.** Dropping the
positive-evidence rule (defaulting an UNEVALUATED member to numeric, the way
`isNumericEnumObjectType` does for its arithmetic caller) reddens nothing — and it would
fix one further measured FP, `enum E { A = zzzNonConst }` against `{ toFixed?() }`, where
tsc is silent and we say TS2345. It was refused because the neighbouring shape shows the
hazard: an enum whose first member is a TEMPLATE literal and whose second is a plain
string (`build/chk60/ue/u3.ts`) does not fold
here, so a numeric default would relate a STRING member to `Number`-shaped targets, a false
NEGATIVE with no gate. Both rows are re-queued.

**ITEM 2 WAS MAPPED, NOT FIXED, AND THE MAP IS BIGGER THAN THE QUEUE ENTRY.** `this` is
`Identifier("this")` in this parser, so `getTypeOfExpression` types it `any` and every
`this.<member>` with it. The queue read that as a weak-rule/optionality gap; measured
(`build/chk60/br`), **it is neither**: a REQUIRED `this.zzzReq: number` assigned to a
`string` variable is silent too, and the hole is per POSITION rather than per member —
**silent** at a var-decl initializer, at an assignment RHS, at a `return`, and for the
nullish-access checks (TS2532/TS18048); **present** in ARGUMENT position and for TS2722.
The same fixture also isolates a SECOND, independent defect that is NOT `this`-specific: an
OPTIONAL property's `| undefined` is dropped for ANY receiver (`zzzInst.zzzOpt` reports
`Type 'number'` where tsc says `'number | undefined'`, and `string | undefined -> string`
goes missing entirely). Both re-queued with the row-by-row map.
