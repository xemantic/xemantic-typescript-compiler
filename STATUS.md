# Status

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

**THE WEAK-TYPE RULE DID NOT DISTRIBUTE OVER A **UNION** TARGET — SO IT WAS ABSENT FROM THE
MAJORITY OF THE POSITIONS WHERE IT FIRES (2026-08-27, (CHK.57)).** `weakTargetProperties`
answers null for a `Type.Union`, so every B482 walker — the ones that EMIT TS2559/TS2560 at
a named position — was blind to a weak type reached through one, while (CHK.54)'s SELECTION
and (CHK.56)'s TS2769 path had folded over constituents all along. `T | null` /
`T | undefined` is the commonest parameter and variable shape in real TypeScript.
`weakUnionRefusalConstituent` composes the verdict (`weakParamRefusesArg`) and the display
(`weakRefusalDisplayTarget`) into the single-signature CALL argument site and
`tryEmitTopLevelWeakVarDecl`, as a branch DISJOINT from the bare-target one — so the bare
path is byte-identical and its controls stay green under every arm.

**BOTH POSITIONS NOW MATCH tsc 7.0.2 EXACTLY** — code, message, line and column, read off
the compiler and never derived — as do the `| undefined`, interface-, alias- and
`Partial<…>`-constituent, non-fresh-object-source and REST-parameter variants.
**Three shapes refuse deliberately, each MEASURED**: two or more non-nullish constituents
(tsc's TS2345/TS2322 naming the whole union needs the RELATION to reject); an object-literal
ARGUMENT ((CHK.56)'s boundary — tsc's excess check squiggles the property two columns
right); and a CALLABLE source, because tsc emits TS2560 only when CALLING the source yields
an assignable value (`() => number` against a weak object is TS**2559**) where we emit 2560
for every callable — a pre-existing BARE-target divergence that would have been inherited as
a wrong-CODE row.

**TWO ABLATION FINDINGS WORTH MORE THAN THE FIX.** The queue entry's own two-constituent
example (`{ zzzA?: null } | string`) is a **DEAD ARM** — with a non-weak FIRST constituent,
dropping the single-survivor test still emits nothing, because the emitter bails on a
`string` target anyway; the discriminating shape is two WEAK object constituents, and with
it the arm went 0 -> 1 RED (round 902). And the helper's `weakParamRefusesArg` call is a
**REDUNDANT guard**, explicable term for term: for the single surviving constituent every
test it makes is re-made by `tryEmitWeakTypeAssignment`. Recorded as such, not claimed as
coverage (round 807).

**GATES.** Suite **16,169 / 0 / 3** (+14, exactly the one new class), **no corpus baseline
moved** — the 13k baselines carry no weak-union shape at all. `cost_gate.py` exit 0
unrebaselined, `output.errors` **46**, the table the parent's to +0.006% on its largest
counter. `huge_methods --fail-over 0` exit 0, **783** classes. 8-profile grid over two
session-built binaries (`javap` control 0 vs 1): capture md5 `503774c2…` on BOTH, per-profile
`diff` clean — **`added=0 removed=0` on all eight**, unmoved since (CHK.54).
`partition-equivalence` **EQUIVALENT, all 78**, floor **63 ms** [63, 62, 51, 81] — one draw.
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376**, both ARM
DIGESTs unmoved. **`knip` 48 -> 48 and `jsonrepair` 4 -> 4, EVERY ROW BYTE-IDENTICAL** — the
queue item's "it ADDS rows … expect it to fire on real code" is measured FALSE, and (CHK.54)
is why: selection already refuses these signatures, so `readFileSync` picks the `string`
overload and the argument site never asks.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, ALL SEVEN CLASS md5s DISTINCT.** a0 (whole change
reverted, parent rebuilt this session) **7 RED — exactly the seven positives**; a1 (object-
literal guard dropped) **1**, a2 (single-survivor test dropped) **1**, a3 (callable guard
dropped) **1**, each uniquely its own pin; a4 (argument site removed) **6**, a5 (var-decl
branch removed) **2**; a6 (the verdict not asked) **0 — a redundant guard**. Residue —
the RETURN and ASSIGNMENT positions have no weak walker at all, and the 2559/2560 split —
queued as (CHK.58) with a fixture apiece.

**THE TS2769 *DIAGNOSTIC* PATH DID NOT ASK THE WEAK-TYPE RULE — AND THE ITEM'S "HARD
PART" WAS A **tsgo RENDERING**, NOT tsc's (2026-08-27, (CHK.56)).** (CHK.54) gave overload
SELECTION the weak rule and left the diagnostic path alone, so `allArgumentsMatch`
accepted what `signatureAcceptsArgs` refused and a call whose every overload has a
disjoint all-optional parameter was SILENT. The queue item recorded the elaboration as the
work — `getFirstArgumentError` walks the plain relation, which ACCEPTS the argument, finds
no failing argument and drops the overload out of the chain. Half of that is right: the
subline really is TS2559's *no properties in common* wording and is now minted beside the
walk, on the path where the relation SUCCEEDED. **The other half is not.** tsgo 7.0.2
prints `The last overload gave the following error.` for **2, 3 and 4** candidates alike;
PRISTINE tsc prints `Overload N of M, '<sig>', gave the following error.` per candidate —
**42** `typescript-repo` baselines against **4** — and
`tsxStatelessFunctionComponentOverload4.errors.txt` carries a *no properties in common*
subline inside exactly that chain. Our chain has had the pristine shape since B418, so no
"which overload" policy was needed at all and the item's "a TS2769 naming the wrong
overload is worse than silence" risk never arose. Round 938's law, paid again.

**TWO THINGS MEASURED RATHER THAN GUESSED.** A UNION parameter names a CONSTITUENT only
when exactly one survives dropping `null`/`undefined` (`ZzzWk | null` -> `'ZzzWk'`); two or
more take the ordinary assignability wording naming the whole union — the verdict is a
refusal either way, only the sentence differs. And an OBJECT-LITERAL argument is refused
outright, because tsc's freshness/excess check runs ABOVE the weak check and a fresh
literal sharing no property name has EVERY property excess: `f({ zzzZ: 1 })` is
`Object literal may only specify known properties…` at the PROPERTY, one column right of
where the weak wording would sit. That shape stays SILENT rather than acquiring a
diagnostic at the wrong span; the NON-fresh source of the identical type is the weak
wording and is pinned.

**A SECOND HOLE MEASURED AND QUEUED AS (CHK.57).** The bare weak target is correct and
byte-identical to tsc in every position; a weak target reached through a **UNION** is
silent here in BOTH the single-signature call and the var-decl positions
(`(o: { zzzA?: null } | null)` with `123`, `const v: {…} | null = "utf8"`) where tsc says
TS2559. Different mechanism — the B482 walkers, not the overload helpers — so it is queued
rather than folded in.

**GATES.** Suite **16,155 / 0 / 3** (+11, exactly the one new class), no corpus baseline
moved — run twice, the first reading 16,155 / 3 / 3 on three of this round's own
hand-derived `character` assertions, since replaced by tsc's own coordinates. `cost_gate.py` exit 0 unrebaselined, `output.errors` **46**, and the table is
**digit-for-digit the PARENT's** (a0 binary, same session) — this change costs 0.00% on
the compiler profile, the expected control for a question asked only after the relation
ACCEPTED. `huge_methods --fail-over 0` exit 0, **783** classes. 8-profile grid over two
session-built binaries (`javap` control 0 vs 2): capture md5 `503774c2…` on both,
**`added=0 removed=0` on all eight**. `partition-equivalence` **EQUIVALENT, all 78**, floor
**58 ms** [79, 58, 55, 56] — one draw. `capture-equivalence` **1,005 / 43 of 76 /
moreAny 0**, `definitions` **360,376**, both ARM DIGESTs unmoved.
**`knip` @ `dc7aca5` 48 -> 48 and `jsonrepair` 3.13.1 4 -> 4, byte-identical** — the queue
item's "it ADDS rows" is measured FALSE on every corpus this repo has.

**EIGHT ABLATION ARMS, ONE MISTAKE EACH, ALL EIGHT CLASS md5s DISTINCT.** a0 (whole change
reverted, parent rebuilt this session) **6 RED — exactly the six positives**; a1 (the
object-literal guard dropped) **1**, uniquely its own pin; a6 (display target always the
whole parameter) **2**, uniquely the two union pins; a7 (a union never names a constituent)
**1**, uniquely the one-constituent pin. **a2/a3/a4 each read 6 and are a ROUND-927 TRIPLE**
— each alone deletes the diagnostic, so none is redundant, but no pin separates which layer
failed. **a5 reads 0 and is recorded as UNDISCRIMINATED, not provably unobservable**: it can
only matter through B418's tie-break, which nothing here exercises.
