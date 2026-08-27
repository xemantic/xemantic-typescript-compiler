# Status

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

**AN OBJECT LITERAL'S LITERAL PROPERTIES WIDEN, AND THAT ONE FACT BIT AT *BOTH*
OVERLOAD SITES — A FALSE TS2769 AT THE DIAGNOSTIC AND A **WRONG TYPE** AT SELECTION
(2026-08-27, (CHK.55)).** The queue item carried (b) an object-literal FP and a third
"mechanism this round did not locate" (matrix row H) as separate holes. Measured: they
are ONE. `getTypeOfExpression` types `{ encoding: "utf8" }` as `{ encoding: string }`
— there is no fresh-literal machinery — so a target property with a literal type
rejects. The DIAGNOSTIC path had round 728's rescue but refused a target INTERFACE with
heritage and a UNION with >1 non-nullish constituent; SELECTION had **no rescue at
all**, so every candidate was passed over and `resolveCallOverload`'s `arityMatches[0]`
fallback answered. **One fixture shows both at once** — a false TS2769 *and* the first
overload's return type where tsc gives the second's — and that co-occurrence is what
identifies them as one mechanism.

**THE HERITAGE REFUSAL WAS NEVER NEEDED.** Round 728 refused a target with base types
because "an inherited required property would not be enumerated below"; measured, that
is not true here — `resolveInterfaceMembersCore` folds base members into the derived
type's own `members` and sets `properties = members.values.toList()`, so both
enumerations already saw them. That refusal was `knip`'s last overload row.

**A THIRD INTERACTION, FOUND BY TRYING TO FALSIFY AN ABLATION ARM RATHER THAN BY READING
CODE.** Round 728 put the rescue on the REJECTING path so the happy path pays nothing —
but a weak constituent accepts any non-nullish value structurally, so for
`{ zzzA?: 0 } | { zzzE: "u" }` the relation SUCCEEDS through the weak constituent, the
rejecting path is never taken, and (CHK.54)'s weak rule refuses the signature having
never asked about the OTHER constituent. Guarded, short-circuit, costing nothing on the
compiler profile. **Its by-product is a retraction**: the `continue` beside the rescue
was documented as load-bearing and its ablation reads **0 RED** once the guard exists —
recorded as PROVABLY UNOBSERVABLE, not as coverage.

**GATES.** Suite **16,144 / 0 / 3** (+11, exactly the one new class); **no corpus
baseline moved by any of the three edits**. `cost_gate.py` exit 0 unrebaselined,
`output.errors` **46**, the largest counter move **+0.59%** (`typeOfExpr.calls`) — the
rescue being consulted on the rejecting path — and the third edit is digit-for-digit
identical to the second, i.e. it costs 0.00% on this profile.
`huge_methods --fail-over 0` exit 0, **783** classes, 0 over limit. 8-profile grid over
a parent rebuilt in this session (md5 `86ec37c3…`, reproducing (CHK.54)'s recorded
landed digest) with a `javap` control of **0 vs 2**: **`added=0 removed=0` on all
eight**, capture md5 `503774c2…` unmoved. `partition-equivalence` **EQUIVALENT 78/78**,
floor **61 ms** [54, 61, 74, 60] — one draw. `capture-equivalence` **1,005 / 43 of 76 /
moreAny 0**, `definitions` **360,376**, digests `full=-3735929574989657502
narrow=-2075467818767010709`.

**LIBRARIES: `knip` @ `dc7aca5` 49 -> 48**, exactly `src/util/git.ts:17:55`
(`execSync(cmd, { encoding: 'utf8', stdio: [...] })`) and nothing added;
**`jsonrepair` 3.13.1 4 -> 4 byte-identical**. BEFORE arms captured on the rebuilt
parent and byte-identical to the session-start capture.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, EVERY CLASS md5 DISTINCT.** a0 (the whole
change reverted — the parent, rebuilt) **4 RED**, exactly the four positives; a1
(heritage refusal restored) **2**; a2 (union back to `singleOrNull`) **1**, uniquely the
union pin; a3 (SELECTION no longer asks) **2**, uniquely row H; a4 (the union fold
accepts unconditionally) **2**, uniquely the union-refusal pin; a6 (the weak-rule guard
reverted) **1**, uniquely its own pin. **a5 (`continue` -> fall-through) 0 RED and
recorded as provably unobservable, not as coverage.**

**(CHK.55)(a) IS DELIBERATELY NOT CLOSED** — the TS2769 path still does not ask the weak
rule, so `zzzU(123)` is silent where tsc says `Type '123' has no properties in common
with type '{ … }'`. That is a MISSING error, the least damaging of the three, and its
elaboration needs its own design; re-queued with tsc's exact message.
