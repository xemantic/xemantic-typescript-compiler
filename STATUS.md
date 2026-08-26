# Status

**THREE MECHANISMS, AND NONE OF THEM WAS THE GAP THE QUEUE ITEM NAMED: A DESTRUCTURED NAME, A
NESTED ACCESS AND AN UN-ANNOTATED BODY-LOCAL WERE EACH UNCHECKED AS A RECEIVER (2026-08-26,
(CHK.46)).** (CHK.45) left three populations measured and open; all three are closed, and in two
of them the TYPE was never missing at all — a write probe (`const p: number = inner`) answers
`Inner` on the UNFIXED binary for every destructured shape, and `cmamGeneralReceiverType` already
reads `Inner` for `h.inner`. What was missing is a CONSUMER. That is the round's most
transferable output and it is the third round running in which the item's framing was wrong:
only the un-annotated body-local is a block-scoping gap.

**(c) A DESTRUCTURED NAME IS TYPED AS A RECEIVER NOWHERE, AND IT FAILS TWO DIFFERENT WAYS.** A
BOUND binding name reaches the `identSymbol` branch, where `getTypeOfSymbol` has **no
`BindingElement` arm**; an UNBOUND one (a body-local pattern, ANY parameter pattern) reaches the
`else` branch, where `getTypeOfIdentifier` answers `anyType` too — `currentLocalTypes` does not
carry it in this pass and `currentParamBindingNames` is a deliberate blanket `anyType`.
`cmamDestructuredReceiverType` finds the `BindingElement` **syntactically**, innermost-first from
the reference (lexical scopes for the unbound half, the per-file symbol for the bound one), so
round 429's fall-through-to-globals defect cannot re-open. Arms a9/a10 are exact COMPLEMENTS —
1 RED and 11 RED — which is what says the two routes serve disjoint declaration sites.

**(d) A NESTED ACCESS WITH A SINGLE-OBJECT LEAF HAD NO EMITTER**, because a UNION receiver is
decided by `cmamCheckUnionReceiverNarrowing` (which accepts a `PropertyAccessExpression`) and
every non-union one falls into the `objectExpr !is Identifier` branch, whose three handlers are
about `X.prototype` and namespace members. Its firewall is (CHK.45)'s trust predicate plus two
refusals that are MEASUREMENTS: an ARRAY-LIKE (the one row the harness profile gained was
`fourslashImpl.ts`'s `options.description.slice(1)` on a tuple, which tsc accepts) and an `in`
GUARD on the path (`if ('zzznope' in h.inner) { h.inner.zzznope }` is LEGAL, and
`narrowByInOperator`'s non-union arm deliberately answers the UNCHANGED type for it, so no
identity test on the narrowed type can see it). **And one dedicated walker now defers**:
`checkMergeTypeMethodChain` emitted `o1.shape.p51` byte-for-byte too, and neither the call
site's emptiness test nor an identity test on `diagnostics` can see that from `checkSpine` —
both were built and measured — because that walker runs LATER.

**(b) AN UN-ANNOTATED BODY-LOCAL `const` HAD NO TYPE AT ALL** (B83.5). Two measured refusals:
`const` only — a `let` is exactly the population (CHK.44)'s three false positives came from —
and a WHITELIST of initializer forms, because a `new X(…)` costs
`isolatedModulesShadowGlobalTypeNotValue` three baselines (`Date` there is a TYPE-ONLY import
shadowing the global `Date` VALUE, so `getTypeOfExpression(new Date(…))` answers the imported
INTERFACE). (CHK.44)'s `typeHasNullishConstituent` line was DROPPED as provably dead rather than
shipped un-gateable — `t !is Type.Object`, moved above it, decides every nullish reading.

**GATES.** Suite **16,050 / 0 / 3** (+52, exactly the three new classes), **zero corpus baselines
moved**. 8-profile grid **`added=0 removed=0` on all eight** and **knip 66 -> 66 with every row
byte-identical**, both against a parent binary **rebuilt in this session** (javap control: the
six new methods are 0 in the before arm, 6 in the after). `cost_gate.py` was **rebaselined once**,
for (c): `typeNode.cacheHits` +2.09% against a baseline recorded 2026-08-25 of which (CHK.45) had
already recorded +1.96%, i.e. ~0.13pp is this round's — the type-node resolutions
`typeCaptureDestructured` performs at the two `any` bails, which are cache HITS. (d) and (b) then
PASS unrebaselined, largest movement `narrow.memoServed` **+0.57%**; `output.errors` **46** and
`spine.nodes` **+0.00%** throughout. `huge_methods --fail-over 0` exit 0, **783** classes scanned.
`partition-equivalence` **EQUIVALENT, all 78**, floor **60 ms** [73, 60, 58, 59] (one draw).
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376** — the standing
state, both digests unmoved.

**28 ABLATION ARMS ACROSS THE THREE MECHANISMS, ONE MISTAKE EACH.** Two findings beyond the
attribution. **Two pins were VACUOUS and only an arm saw it**: a rest-element refusal written on
the shape it is ABOUT (0 RED — the member lookup refuses it anyway; re-pinned in the
FALSE-POSITIVE direction, where a rest element whose NAME is a member of the source would adopt
that member's type, it reads 1 RED), and an `in`-guard negative dressed with a cast to keep the
fixture tidy, which made the receiver the CAST and not the path (0 RED; written plainly, 1 RED).
**And the GENERIC-instantiation refusal is a round-927 PAIR**: guarded by the trust predicate's
`Type.Reference` line AND by the array-like line, neither redundant, only the COMBINED arm
reddens it. Six further arms read 0 RED and are recorded as redundant guards rather than claimed
(round 807), each with the layer that actually refuses.

**WHAT IS LEFT.** A destructured or un-annotated local that SHADOWS a same-named file-level
binding still resolves to the OUTER one — `const inner: Deep` at file level makes a body-local
`const { inner } = h` report `Deep` for an `Inner` — measured on the parent binary too, so it is
PRE-EXISTING and independent; `fileLocalTypeMapFor` / `lookupPerFileForNode` win before any of
this round's helpers is consulted. The (b)+(d) and (b)+(c) COMPOSITIONS are also still silent
(`const c = h; c.inner.zzznope`): the root answers `any`, so the chain never reaches the nested
emission. Recorded here rather than pinned (round 765).

**A PROPERTY MISSING ON *EVERY* UNION CONSTITUENT WAS WHITELISTED TO TWO MEMBER SHAPES — AND
THREE OF (CHK.44)'s FOUR "BLOCK-SCOPED" POPULATIONS ARE NOT ABOUT BLOCK SCOPING AT ALL
(2026-08-26, (CHK.45)).** The union elaboration's two verdicts differ in soundness: PARTIAL
coverage has a WITNESS (some member answered "yes", so its table resolved) and fires on any
member set; ALL-MISSING has none, so it was gated to `allWellResolved` / `allAnonPlainObjects`.
Measured against tsgo 7.0.2, that dropped every union carrying a FUNCTION type, a CONSTRUCTOR
type, a PRIMITIVE, a TUPLE or a TYPE LITERAL beside a named interface — for a PARAMETER and a
FILE-LEVEL `const` exactly as much as for a body-local, which is why (CHK.44) filed it as "a
different emitter". It is the same emitter.

**THE 3x5 MAP IS THE ROUND'S MOST TRANSFERABLE OUTPUT.** (a) a member on NO constituent —
the whitelist, **FIXED**; (b) an un-annotated local — **SPLITS**, its file-level half is (a) and
is fixed, its body-local half is B83.5; (c) a DESTRUCTURED binding — silent for a destructured
PARAMETER too, i.e. a binding-element name is typed as a receiver nowhere; (d) a NESTED access
with a single-OBJECT leaf — silent at every declaration site, while a UNION leaf already
reports. (b)-body-local, (c) and (d) are three INDEPENDENT gaps, queued as **(CHK.46)** and
recorded in the note rather than pinned (round 765: a known-gap pin is a countdown — (CHK.44)'s
own failed this round, the law's third instance).

**THE CALIBRATION IS TWO ROWS ON knip AND NOTHING ELSE.** Deleting the gate ENTIRELY is
`added=0 removed=0` on all eight profiles and moves ZERO corpus baselines, and still costs **2
false positives on knip** — `walk.ts`'s `item.members` / `item.jsDocTags` on `Export |
undefined`, both a CROSS-FILE interface WITH a heritage clause (B153 arriving exactly where the
shipped predicate already refused it). So the widening is a per-member trust predicate whose
Interface arm keeps the shipped rule verbatim; everything it adds is OUTSIDE the named-interface
world. Seven refusals are pinned as refusals with their reason — tsc reports all of them.

**GATES.** Suite **15,998 / 0 / 3** (+19), **zero corpus baselines moved**. `cost_gate.py`
**PASSES with NO rebaseline**, exit 0 — `output.errors` **46**, `spine.nodes` +0.00%, largest
movement `typeNode.cacheHits` **+1.96%**, i.e. the same three counters at the same values
(CHK.44) recorded: an emission gate costs no counter. `huge_methods.py --fail-over 0` exit 0,
**783** classes scanned, 0 over limit. `partition-equivalence` **EQUIVALENT, all 78**, floor
**56 ms** [56, 53, 56, 69] (one draw). `capture-equivalence` **1,005 / 43 of 76 / moreAny 0**,
`definitions` **360,376** — the standing state, both digests unmoved. 8-profile grid vs a parent
rebuilt at session start: **`added=0 removed=0` on all eight**. **knip 66 -> 66, every row
byte-identical, BEFORE arm rebuilt in this session** (parent re-verified by a probe reading 2
rows where the fixed binary reads 7).

**NINE ABLATION ARMS, ONE MISTAKE EACH, EACH DIFFED AGAINST ITS OWN SNAPSHOT.** a1 (the trust
predicate always false) **11 RED — every positive**; a2 (always true) **6 — every refusal**; a3
(drop the heritage check) 1; a4 (drop the `Type.Reference` refusal) 1; a6 (drop the chain) 1; a7
(revert the index-signature precision) 1 — the TUPLE, which a naive "any index signature
provides the property" refusal loses. **a9 (relax the `InterfaceDeclaration` requirement) reddens
the CLASS pin ONLY, which is the finding**: an ENUM is refused not by `isEnumFlavoredObjectType`
but by the LAST clause, because an enum-flavoured type resolves to no members and no signatures
(CLAUDE.md: an enum's members live on `Symbol.exports` and on no type at all). So a5 and a8 read
**0 RED and are REDUNDANT GUARDS, recorded as such rather than claimed** (round 807) — and the
enum pin is NOT blind, since a2 reddens it.

**A BLOCK-SCOPED LOCAL WITH A UNION ANNOTATION WAS NOT A RECEIVER AT ALL — `function f() {
const c: A | F = u; c.files }` REPORTED **NOTHING** WHERE tsc 7.0.2 REPORTS TS2339 (2026-08-26,
(CHK.44)).** CLAUDE.md's B83.5 is the whole cause: `Binder.bindStatement` binds no declaration
nested in a block, so `lookupPerFileForNode` answers null, `getTypeOfIdentifier` falls through to
`anyType`, and every gate below it bails. It held in a function, a method, an arrow, a nested
function, a nested block and a file-level block, for `const`, `let` and `var` alike. The receiver
is now read back out of the INV.2(c) lexical scope tables (round 748's `lexicalScopeSymbol`,
`LexicalScope.symbols` only, so a hit is BY CONSTRUCTION a name the conventional tables do not
have) at the ONE call that asks whether a property exists on it.

**THE QUEUE ITEM'S MEASURED BOUNDARY WAS DIFFERENT FROM ITS STATED ONE, IN BOTH DIRECTIONS.**
(CHK.41) recorded "3 of 4 shapes — file-level `const`, `let`, and inside an arrow — are silent;
only a parameter is checked". Re-measured against tsgo: a **file-level** `const`/`let` IS checked
(one of the round's first probes read otherwise because the receiver was named `top`, which
collides with the DOM global), and the real axis is **declared in a block**. Four further
populations were censused and are still silent, three of them by DECISION and one measured for
the first time: a member on **NO** constituent (a different emitter), an **un-annotated** local,
a **destructured** local, and a nested access `c.files.nope`.

**TWO REFUSALS, BOTH MEASUREMENTS RATHER THAN ARGUMENTS.** A NULLISH union (`T | undefined`) is
refused: without that guard the 8-profile grid gains **11 rows on the compiler profile and 16 on
harness**, `removed=0`, and tsgo reports NONE of them — every site is a `let x: T | undefined`
the code narrows before use. A NON-union declared type is refused: it is decided by the `else`
branch, which consults no narrowing at all, and supplying it costs **3 rows** on
services/server/harness (`let next: Symbol` narrowed by a type guard inside a `while` condition).
`const`-ness is NOT a guard — refusing `let`/`var` measured `added=0 removed=0` on all eight, so
it was redundant and cost the `let` shape the item names. **A FIRST CUT THAT WROTE THE ANNOTATION
INTO `currentLocalTypes` WAS REVERTED**: it closed the same population and cost two corpus
baselines, because that map is read by every consumer of the pass — a TS18048 from the optional
-property emitter and another from B136's chaining arm, both the same missing narrowing reached
through consumers this round does not fix.

**THREE OF THE TWENTY PINS WERE VACUOUS AND ONLY A CONTROL PROBE PER SHAPE SAW IT.** `c.nope` — a
member on NEITHER constituent — is silent for a block-scoped local whatever this round does, so
every negative written that way stayed green with its guard ablated (a3 read `0 RED`);
`A | undefined` + `files` reports TS18048, not TS2339; and the global-shadow suppression does not
even fire on `const isNaN` under the embedded lib. This class **is** the vacuity trap (CLAUDE.md,
(CHK.41)), so the two shapes that were ALWAYS green — a file-level declaration and a parameter —
are in it under names that say they are controls.

**GATES.** Suite **15,979 / 0 / 3** (+20, exactly the new class), **zero corpus baselines moved**.
`cost_gate.py` **PASSES with NO rebaseline** — `output.errors` **46**, `spine.nodes` +0.00%,
largest movement `typeNode.cacheHits` **+1.96%** (one annotation resolution per reached
block-scoped receiver). `huge_methods.py --fail-over 0` exit 0, **783** classes scanned.
`partition-equivalence` **EQUIVALENT, all 78**, floor **65 ms** [79, 61, 65, 61] (one draw).
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376** — the standing
state, both digests unmoved. 8-profile grid against a REBUILT parent, `javap` positive control:
**`added=0 removed=0` on all eight**. **knip 66 -> 66, every row byte-identical.**

**NINE ABLATION ARMS, ONE MISTAKE EACH, EACH DIFFED AGAINST ITS OWN SNAPSHOT.** a1 (the helper
answers null) **10 RED — every positive**; a2 (drop the `currentLocalTypes` suppression refusal)
**1**; a3 (drop the nullish refusal) **1**; a5 (drop the single-declaration refusal) **1**.
**a4 and a4b each read `0 RED` and are a round-927 PAIR** — the union refusal and the ABSENCE of a
second injection point block the same 3-row services false positive, so neither reddens alone and
only **a4c (both together) reddens 1**. **a6 and a7 read `0 RED` and are REDUNDANT GUARDS,
recorded as such rather than claimed** (round 807): a6 is refused a second time by
`valueDeclaration as? VariableDeclaration`, a7 because every shadow registrar writes
`currentLocalTypes` too.

**A GUARDED REASSIGNMENT NOW REDUCES THE *DECLARED* UNION — `if (typeof c === 'function')
c = c();` THEN `c.files` WAS A FALSE TS2339, AND SO WAS ITS ASSERTION SIBLING (2026-08-26,
(CHK.41)).** Both are knip's own source and neither is reachable for any existing arm of
`narrowByAssignmentRhs`: in the CALL form the callee **is** the walked reference, so
`getTypeOfExpression` (which never narrows) asks about the whole union and
`resolvedCallReturnTypeForFlow` wants a `FunctionDeclaration` a parameter is not — while the
ANTECEDENT is exactly the callee's type there, the guard having already narrowed it. The
ASSERTION states its own type syntactically ((CHK.43)), so the `await` and parens around it are
irrelevant. **The reduction is of the DECLARED union, never the antecedent** (round 416's rule,
which the identifier/property arms still predate): in the then-branch the antecedent IS the
constituent being replaced, so filtering it answers `never` or itself and the branch join
re-mints the union — which is exactly why the shape read as "no narrowing at all".

**THE ITEM'S PREMISE WAS TWO-FIFTHS RIGHT, AND THE CORRECTION IS THE ROUND'S MOST USEFUL
OUTPUT.** (CHK.41) recorded the +15 knip rows the two reverted contextual sources cost as
"**every one**" this shape. Recovered from (CHK.39)'s own captures at zero cost and reproduced
one by one with an **annotated** parameter, they are FIVE mechanisms: ava 3 + eleventy 3 (the
guarded reassignment — **fixed**), release-it 2 (`typeof x.y?.z === 'string'` must narrow
`x.y`), mdxlint+remark 4 (the `flatMap` callback's return-type INFERENCE), graphql-codegen 1 (a
nested-ternary predicate) and yarn 2 (a `Plugin` NAME collision, not narrowing at all) — plus
2 rows those sources REMOVE. **So the two sources stay reverted**, with a per-row map instead
of a projection.

**AND A LARGER FINDING FELL OUT OF ISOLATING THE FIRST: THE PROPERTY-ACCESS FAMILY ONLY REACHES
A *PARAMETER*.** `const c: A | F = x; c.files` and `let c: A | F = x; c.files` are SILENT where
tsgo reports TS2339 — 3 of 4 shapes are false negatives. That is why the item, and this
session's first four probes, read "a LOCAL narrows and a PARAMETER does not": the local was
never checked. Queued as **(CHK.44)**.

**GATES.** Suite **15,959 / 0 / 3** (+9, exactly the new class), **zero corpus baselines
moved**. `cost_gate.py` **PASSES with NO rebaseline** — `output.errors` **46**, `spine.nodes`
+0.00%, largest movement `mapped.hits` **+1.46%**. `huge_methods.py --fail-over 0` exit 0,
**783** classes scanned. `partition-equivalence` **EQUIVALENT 78/78**, floor **56 ms**
[56, 66, 51, 53] (one draw). `capture-equivalence` **1,005 / 43 of 76 / moreAny 0**,
`definitions` **360,376** — unmoved, both digests. 8-profile grid against a rebuilt parent,
`javap`-controlled (0 -> 1): **`added=0 removed=0` on all eight**, a CONTROL and not evidence.
**knip 66 -> 66, every row byte-identical, BEFORE arm rebuilt in the same session.**

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, EACH RESTORED FROM ITS OWN SNAPSHOT.** a1 (the whole
fix inert) 6 RED; a2 (the CALL arm) 3 and a3 (the ASSERTION arm) 3 — an exact partition;
a5 (drop the un-callable-union refusal) 1, uniquely the UNGUARDED control. **a4 read `0 RED`
and WAS a dead arm** — a guarded substitution that reproduces the original wherever the
antecedent is not a union, which in a `typeof` then-branch it never is; asking what shape only
that arm can serve gave **a4b, 5 RED**. **a6 read `0 RED` and is a REDUNDANT GUARD, recorded
as one rather than claimed** (round 807): `any`/`never`/`error` keep every member so the call
site's `kept.size < declared.size` test refuses anyway, and `unknown` keeps none so
`kept.isNotEmpty()` does.

**A FUNCTION BODY NESTED IN A `return` EXPRESSION IS NOW CHECKED AT ALL — AND BOTH ROWS THAT
BLOCKED IT WERE FALSE POSITIVES WE ALREADY SHIPPED (2026-08-26, (CHK.42) + (CHK.43)).**
(CHK.40) measured the walk at full parity with tsgo and refused to land it because the
8-profile grid gained **3 rows**. Diagnosed one at a time, all three are OURS and none is a
genuine error: one is **(CHK.43)** and two are one defect in `importFixes.ts` — and BOTH are
reproducible on a rebuilt parent binary in positions the walker has reached for many rounds.
With the two fixes in, the grid is **`added=0 removed=0` on all eight** and the walk shipped.

**(CHK.43) A TYPE ASSERTION'S VALUE HAS THE *ASSERTED* TYPE.** `inferSimpleExprType`'s two
assertion arms answered the OPERAND's type whenever `resolveSimpleTypeName` could not render
the asserted one (an array, tuple, function type, type literal) — and for the
`x as unknown as T` escape hatch the operand's type is `unknown`, i.e. exactly what the outer
assertion exists to assert away. `function m(): B | A | (B|A)[] { return r as unknown as B[] }`
reported `Type 'unknown' is not assignable…`; tsgo 7.0.2 is silent. The item recorded a
">= 3-member union" trigger; the real rule is **"the union carries an ARRAY member"** —
`A | (B|A)[]` fires too — because the string fallback is only reached after the engine has
declined, and the engine declines exactly when the source DOES relate.

**(CHK.42)'s SECOND HALF: A PARAMETER ALWAYS INTRODUCES A BINDING.** `currentLocalTypes` is a
flat COPY of the enclosing scope, so an un-annotated parameter that nothing could type was not
merely untyped — the enclosing scope's same-named entry was still there and every read
resolved to IT. Round 569's refusal to register an un-inferred contextual type parameter is
correct and its comment said "the param stays `any`"; it did not, it stayed ABSENT. That is
`flatMap(exportInfo, (exportInfo, i) => … { …, exportInfo })` in tsc's own `importFixes.ts`,
reported as a TS2322 tsc does not have. A pre-pass registers `anyType` — round 475's value for
exactly this purpose, and the correct one, since such a parameter IS implicitly `any`.

**GATES.** Suite **15,950 / 0 / 3** (+22 pins: 8 + 4 + 10), **zero corpus baselines moved**.
`cost_gate.py` **PASSES with NO rebaseline** — `output.errors` **46**, `spine.nodes` +0.00%,
largest movement `mapped.hits` **+1.43%**. `huge_methods.py --fail-over 0` exit 0, **783**
classes scanned. `partition-equivalence` **EQUIVALENT 78/78**, floor **58 ms** [53, 59, 52,
58] (one draw). `capture-equivalence` **1,005 / 43 of 76 / moreAny 0** — the standing state,
unmoved — with `definitions` 360,361 -> **360,376**, the expected direction. 8-profile grid
against a rebuilt parent, `javap`-controlled (11 `inferSimpleExprType` call sites before, 9
after): **`added=0 removed=0` on all eight**, where (CHK.43) alone was already 0/0 and the
walk alone added the 2 `importFixes.ts` rows. knip **66 -> 66**, every row identical, BEFORE
arm rebuilt in the same session.

**SEVEN ABLATION ARMS, ONE MISTAKE EACH, EACH RESTORED FROM ITS OWN SNAPSHOT.** a1 (restore
the `as` fallback) 3 RED; a2 (restore only the legacy `<T>expr` fallback) 1, uniquely the
angle-bracket row; a3 (drop the LEGACY return-arm walk) **1**, uniquely a `return` nested one
function deeper; a4 (drop the SPINE anchor's) **7** — so the two arms partition by NESTING,
the opposite of the item's guess about which one emits; a5 (drop the annotation
contextualizer) 1; a6 (drop the parameter shadow) 2; a7 (make the shadow a POST-pass) **22**.
**a5 read `0 RED` on its first run and was NOT a dead leg** — it serves exactly one shape the
contextual pull skips by construction, a REST parameter, and a pin for that shape was the
round's cheapest correction.
