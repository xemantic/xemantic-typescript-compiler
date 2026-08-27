# Status

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

**THE WEAK-TYPE ANCHOR MOVES TO THE **EXPRESSION** EXACTLY WHEN THE CODE IS **TS2560** —
AND THAT ONE RULE UNBLOCKED THE LARGEST PIECE OF (CHK.58)'S RESIDUE (2026-08-27, (CHK.59),
three fixes).** A CALLABLE source was refused outright at the var-decl / return / assignment
positions because it is not squiggled where a non-callable one is. tsc's
`checkTypeRelatedToAndOptionallyElaborate` runs `elaborateError` first, whose first act is
`elaborateDidYouMeanToCallOrConstruct`: when some signature's return type is related to the
target it RE-REPORTS with the error node set to the EXPRESSION and attaches TS6213/TS6212;
otherwise the position's own error node is used. **That predicate is the SAME one
`weakCallResultSatisfiesTarget` already used to choose 2560 over 2559**, so the two coincide
by construction and the emitter needed one extra, CALL-ONLY anchor. Fifteen missing tsc rows
now land byte-exact over three positions x four source shapes.

**AND TWO FURTHER HOLES FELL OUT OF THE SAME CHANGE**: `topLevelWeakSource` classifies a
cast, an enum member, a `new` and a primitive literal and nothing else, so an ordinary
IDENTIFIER or ARROW source was silent at the VAR DECL while the other two positions reported
it — the var-decl walker now falls back to the shared value walker as its `?:`. A
**FUNCTION EXPRESSION** stays refused and that is measured, not an oversight: tsc's
`getErrorSpanForNode` maps one to its own NAME, so `= function zzzNamed(){}` anchors at
`zzzNamed` and the anonymous form at the var name — two anchors, neither the expression.

**AN ENUM MEMBER IS A WEAK-RULE SOURCE AT EVERY POSITION, AND (CHK.58) HAD THE MECHANISM
WRONG.** It attributed the silence to `getTypeOfExpression` answering `any`; the type
resolves fine (the pre-existing TS2345/TS2322 name it `ZzzE.A`). The refusal is one step on:
an enum-flavoured type is a member-LESS `Type.Object`, so `weakSourcePropertyNames`
enumerates it to the EMPTY set and the vacuous-`{}` guard (`var x: AllOptional = {}` is
legal) refused it. Consulting the AST classifier AT THAT GUARD closes the argument, return,
assignment and object-literal-leaf positions in one place, for **`globals.lookups` +4** on
the whole compiler profile.

**AND A FRESH OBJECT LITERAL ELABORATES *INTO* THE LITERAL — TWO DEFECTS, ONE SHAPE.** The
one-level nested walker (TS2322 at the var NAME) ran BEFORE the leaf walker (TS2559 at the
property KEY), so a fresh literal took the wrong one; and a leaf reports the literal's OWN
property type, i.e. the **WIDENED** one for a string or numeric literal and the literal
itself for a boolean (`"utf8"` -> `string`, `12` -> `number`, a template literal -> `string`,
`false` -> `false`). **THE TOP-LEVEL VAR-DECL POSITION DOES NOT WIDEN** — pristine's
`nestedExcessPropertyChecking.errors.txt` line 18 reports `Type '"A"'` — which is why the
widening lives in the leaf walker and nowhere else, and lines 30/40 (`Type 'false'`) gate
the boolean half.

**GATES.** Suite **16,223 / 0 / 3** (+24: three new classes plus one residue pin), **no
corpus baseline moved at any of the three steps** — load-bearing, since the leaf/nested
order swap is exactly what `nestedExcessPropertyChecking` and `weakType` gate.
`cost_gate.py` exit 0 unrebaselined, `output.errors` **46**, largest counter **+1.42%**
against a baseline (CHK.58) already read **+1.40%** on — i.e. **+0.02% for this whole
round**. `huge_methods --fail-over 0` exit 0, **783** classes. 8-profile grid md5
**`503774c2…`** on every build and per-profile `diff` clean: **`added=0 removed=0` on all
eight**, unmoved since (CHK.54). `partition-equivalence` **EQUIVALENT, all 78**, floor
**64 ms** [56, 63, 64, 66] — one draw. `capture-equivalence` **1,005 / 43 of 76 / moreAny
0**, `definitions` **360,376**, both ARM DIGESTs unmoved. **`knip` @ `dc7aca5` 48 -> 48 and
`jsonrepair` 3.13.1 4 -> 4, EVERY ROW BYTE-IDENTICAL** to an a0 arm rebuilt in this session
(parent `Checker.class` md5 reproduced the session's first build exactly).

**TEN ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT AND EVERY RED SET DISTINCT —
AND NOT ONE ARM READ 0.** a0 (whole change reverted, parent rebuilt this session) **21 RED
— exactly the 21 positives added or converted**; a1 (the 2560 anchor never moves) **4**,
uniquely the four expression-anchor pins; a2 (the FunctionExpression refusal dropped) **1**;
a3 (the var-decl fallback dropped) **6**; a4 (the assignment site's arrow refusal restored)
**3**; a5 (the enum consult at the vacuous guard) **8**; a6 (the enum DISPLAY override)
**2**, uniquely the one-member pins; a7 (the leaf/nested order reverted) **5**; a8 (the leaf
widening dropped) **3 — and NOT the boolean pin**, which is the control that makes the
widening a rule; a9 (the enum consult in the leaf walker) **1**.

**ONE RESIDUE PINNED RATHER THAN FIXED**: an OPTIONAL `any` property renders
`zzzNope?: any | undefined` where tsc renders `zzzNope?: any`, because `any` absorbs
`undefined` in tsc's union construction and our `getUnionType` does not reduce that pair.
It is a `typeToString` divergence reachable from every position that renders a target
through the TYPE rather than the ANNOTATION, and union member text is pinned byte-for-byte
across ~13k baselines — a logical-parity conversation of its own.

**RESIDUE RE-QUEUED AS (CHK.60)**: two-or-more non-nullish constituents (needs the
RELATION); the fresh-literal-vs-bare-weak-ARGUMENT TS2353 boundary; a GENERIC instantiation
source (deliberate and SYMMETRIC); a `this.<member>` assignment target, silent for EVERY
source shape and therefore not the anchor change — `getTypeOfExpression` answers `any` for
`this.<optional member>`; and an enum member vs a weak target it SHARES a property with,
where tsc is silent and we emit TS2345/TS2322 from the ordinary relation.

**THE WEAK-TYPE RULE FIRED AT A VAR DECL AND AT A CALL ARGUMENT AND **NOWHERE ELSE** — SO
`return v` AND `x = v`, TWO OF THE COMMONEST PLACES A DEVELOPER GETS A TYPE WRONG, REPORTED
NOTHING (2026-08-27, (CHK.58), four fixes).** Not a union defect: the BARE target was silent
too, and the one row the return position DID have carried the wrong CODE (TS2322 naming the
whole union where tsc names the surviving constituent). `tryEmitWeakValuePosition` is the
shared emitter and `weakAssignmentTarget` reads the LHS's DECLARED type; the anchors were
read off tsc 7.0.2 and CORROBORATED BY PRISTINE rather than by tsgo alone — a return
squiggles the `return` keyword (`~~~~~~`), an assignment squiggles the LHS reference (one
`~` under the `c` of `c = d` in `assignmentCompatWithObjectMembersOptionality2.errors.txt`).
**Twelve tsc rows that were missing now land byte-exact; one wrong-code row is corrected.**

**AND TS2560 IS "CALLING IT WOULD HAVE WORKED", NOT "THE SOURCE IS CALLABLE"** — four of six
callable shapes carried the wrong code (`() => number`, `() => { zzzZ: string }`,
`() => void` and a disjoint construct signature are all TS**2559**). **THE RELATION ASKED
MUST CARRY THE WEAK RULE ITSELF**, which no reading of tsc's source produces: tsc's weak
check lives INSIDE `isRelatedTo`, so `number` is not related to a weak object there where
ours accepts it vacuously. The corpus is structurally blind — `weakType.errors.txt` is the
only ACTIVE baseline with 2560 rows and every one of its sources has a related call result.

**AND A WEAK MESSAGE NAMES THE ENUM *MEMBER*, EXCEPT WHERE THE ENUM HAS EXACTLY ONE — AND
THE ONE BASELINE GATING IT AGREED WITH THE WRONG ANSWER.** Pristine's
`nestedExcessPropertyChecking.errors.txt` says `Type 'E'` and its `enum E { A = "A" }` has
ONE member, where the enum type and the member's literal type are the same type. Both
flavours, both counts, measured: `{A="A",B="B"}` and `{A,B}` render `E.A`; `{A="A"}` and
`{A}` render `E`. **AND a `new C()` var-decl initializer is now a source** —
`topLevelWeakSource` had branches for a cast, an enum member and a literal but not for a
`NewExpression`, so the same source reported at an argument and was silent at a var decl.

**ORDER IS A COST DECISION.** Asking the VALUE's type before the TARGET's weakness measured
**+6.89% `typeOfExpr.calls`**, and giving the return site its own `getTypeFromTypeNode`
**+2.9% `typeNode.cacheable` / +11.2% `mapped.hits`** — both for BYTE-IDENTICAL output. As
landed every counter is inside the band (largest **+1.40%**).

**GATES.** Suite **16,199 / 0 / 3** (+30, exactly the four new classes), **no corpus baseline
moved**. `cost_gate.py` exit 0 unrebaselined, `output.errors` **46**. `huge_methods
--fail-over 0` exit 0, **783** classes. 8-profile grid over two session-built binaries:
md5 `503774c2…` on the parent AND every ship, per-profile `diff` clean — **`added=0
removed=0` on all eight**, unmoved since (CHK.54). `partition-equivalence` **EQUIVALENT, all
78**, floor **62 ms** [60, 61, 65, 62] — one draw. `capture-equivalence` **1,005 / 43 of 76
/ moreAny 0**, `definitions` **360,376**, both ARM DIGESTs unmoved. **`knip` @ `dc7aca5`
48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, EVERY ROW BYTE-IDENTICAL** to a parent arm built in
this session.

**TWELVE ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT.** a0 (whole change
reverted) **8 RED — exactly the eight positives**; a1/a2 (return / assignment site removed)
**5 / 6**, unique for the position-specific pins and a ROUND-927 PAIR for the three
both-position ones; a3 (object-literal refusal) **1**, a4 (callable refusal) **1**, a5
(single-survivor test) **3 in three classes = one observable**; b1 (2559/2560 split
reverted) **4**, b2 (the weak veto dropped from the call-result relation) **3**; c1/c2 (the
enum member-COUNT boundary dropped either way) **2 each, complementary**; d1 (the `new`
branch) **2**. **THREE ARMS READ 0 AND ARE RECORDED, NOT CLAIMED**: a6 (the declared-type
ladder replaced by `getTypeOfExpression`) and a7 (the target-weakness pre-gate) are cost
choices no output can see, and b3/b3b are DEAD — the pristine `getDefaultSettings` shape
they were written for RESOLVES its inferred return type here.

**RESIDUE, ALL MEASURED, RE-QUEUED AS (CHK.59)**: two-or-more non-nullish constituents
(needs the RELATION), a CALLABLE source at the three non-argument positions (unblocked by
the code split, but tsc anchors those at the EXPRESSION and not at the name/keyword/LHS), an
enum-member CALL ARGUMENT, a generic instantiation (the deliberate `Type.Reference` bail,
now SYMMETRIC across positions), the nested object-literal LEAF walker, and the fresh
object-literal-vs-bare-weak-argument TS2353 boundary.
