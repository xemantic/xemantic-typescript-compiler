# Status


**A PRIMITIVE SOURCE NOW RELATES TO AN *ANONYMOUS* OBJECT TARGET THROUGH ITS WRAPPER
INTERFACE — 8 OURS-ONLY ROWS OF A 14-ROW tsgo MATRIX, GONE (2026-08-26, (CHK.32)).**
`string` carries `charCodeAt`/`length`/`substring` because `String` declares them, so
`scan(s)` against `(text: { charCodeAt(i: number): number })` is legal. A round-B69.8 leg
has handled a NAMED interface target all along; it is scoped `target is Type.Interface` and
RETURNS, so every anonymous structural target refused every primitive, in argument, return
and annotation position and for `string`/`number`/`boolean`/`symbol`/`bigint` alike. The
matrix now agrees with tsgo 7.0.2 **row for row in both directions** — the 6 rows tsgo
reports are still reported, at its own message and column.

**THE ITEM'S OWN PREMISE WAS WRONG ABOUT `jsonrepair`, AND THE FIX PROVES IT: 11 -> 11 ROWS,
BYTE-IDENTICAL.** (CHK.32) attributed all 7 of that library's TS2345 to this gap, on a repro
whose interface is named `Text`. That is a **NAME COLLISION with the DOM `Text` global**:
rename it `Chars` and the row vanishes on the UNFIXED binary, and `t.wholeText` against the
module-local `Text` is SILENT for us where tsgo says TS2339 — the module-local interface has
been merged into the lib symbol in both directions. Queued as **(CHK.49)**; `Text` joins
`top`/`name`/`files` on the collision list. Fifth round running in which the item's framing
was measurably wrong.

**THE TWO GUARDS WERE FORCED BY A RED SUITE, NOT BY READING.** The first cut cost **13
tests** — every enum-flavoured type is a member-less `Type.Object` ((REL.1)(b)), so a
structural comparison passes VACUOUSLY and the `Number` wrapper "related" to a numeric enum
target. The second cut then cost `assignmentCompat1`: an index-signature target is B418's,
because a `string`'s apparent type has a NUMERIC indexer and no string one while an ordinary
structural comparison of the `String` wrapper against `{ [k: string]: any }` PASSES. The leg
is placed AFTER the round-430 `{}` rule and after B418 so it is strictly a fallback and can
only turn a rejection into an acceptance.

**GATES.** Suite **16,087 / 0 / 3** (+20, exactly the new class), **zero corpus baselines
moved in the landed shape**. `cost_gate.py` **PASSES with NO rebaseline** — `output.errors`
**46**, `spine.nodes` +0.00%, largest movement `narrow.memoServed` **+0.69%**, identical to
(CHK.47)'s, i.e. this change contributes 0.00% on the compiler profile, the expected control
for a leg reached only after a failure. `huge_methods.py --fail-over 0` exit 0, **783**
classes scanned, 0 over limit. 8-profile grid against a parent rebuilt this session
(`javap` control 0 -> 2): **`added=0 removed=0` on all eight**. `partition-equivalence`
**EQUIVALENT 78/78**, floor **69 ms** [80, 69, 60, 60] — one draw, the leading 80 the ramp.
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376**, both
digests unmoved. **knip @ `dc7aca5` 49 -> 49 and `jsonrepair` 3.13.1 11 -> 11, every row
byte-identical, BEFORE arms rebuilt in the same session.**

**SIX ABLATION ARMS, ONE MISTAKE EACH, EACH `cmp`-DIFFED AGAINST ITS OWN SNAPSHOT.** a0 (the
whole leg removed) **8 RED** — every positive pin, over byte-identical source. a5 (a missing
wrapper answers `anyType` as `getApparentType` does) **2 RED**, this round's dedicated pin and
`elaboratedErrorsOnNullableTargets01`. a3 (drop the index-signature refusal) **1 RED**, and it
names a narrower population than the guard reads — a PURE index target declares nothing, so
only a target with BOTH members and an indexer needs that line. **a1 and a2 each read `0 RED`
in 1,410 tests and a12 — both dropped — reads 13: a ROUND-927 PAIR**, one observable, neither
deletable on the strength of its own arm. **a4 (let a `Type.TypeParam` through) read `0 RED`
over the FULL 15,103-test core suite with a class-checksum positive control, and is recorded
as a REDUNDANT guard and KEPT** — the shipped binary already relates a constrained
`T extends string` through another path, so removing a refusal that keeps (INC.30)'s closed
route closed buys nothing measurable.

**AN OUTER BINDING OF THE SAME NAME DEFEATED EVERY BLOCK-SCOPED RECEIVER, AND THE MESSAGE THEN
NAMED THE **OUTER** TYPE — 17 FALSE POSITIVES ON knip, EVERY ONE CONFIRMED SILENT IN tsgo
(2026-08-26, (CHK.47)).** `lookupPerFileForNode` is keyed by the FILE, so a receiver identifier
resolved to the file-level declaration of that spelling however deeply the reference was nested,
and B83.5 keeps the shadowing block-scoped declaration out of the binder tables entirely — so
nothing downstream could notice. Three of the four measured shapes were wrong in the worst
direction a checker can be: a CONFIDENT message naming a type the expression does not have.
All four now match tsgo 7.0.2 exactly, message and column.

**IT WAS THREE MECHANISMS, NOT ONE — the queue item named only the first**, which is the FOURTH
round running in which the item's framing was wrong. (1) `cmamLexicalValueShadow` refuses the
per-file symbol when an inner lexical VALUE binding shadows the name, routing three of the four
into the branch that reads `currentLocalTypes` and (CHK.46)'s helpers. (2) The destructured
PARAMETER shape is not that walker at all: `spineExEnterNode` (the B431 expando anchor) carries
its OWN shadow test, and `spineExFnShadows` compared `(x.name as? Identifier)?.text`, i.e. it was
blind to every destructuring form. (3) The un-annotated body-local additionally needed round
512's un-inferable-shadow bail to stand down where a helper CAN name the inner binding's type.
**`perFileIdentSymbol != null` is part of (1)'s CONDITION, not an optimisation** — without it a
`catch (error)` reached through an `in` guard goes silent on knip for a reason unrelated to
shadowing, which is the arm's only uniquely-its-own failure.

**AND (CHK.46)'s TWO HALVES DID NOT COMPOSE.** `const c = h; c.zzznope` reported and
`p.inner.zzznope` reported, but `const c = h; c.inner.zzznope` was SILENT: the nested path asks
`getTypeOfExpression` for the WHOLE chain and an `any` root makes the whole chain `any` before
either substitution point. `cmamBlockScopedPathType` walks the chain by hand from a root type the
two (CHK.46) helpers can name. The DESTRUCTURING composition stays open, at a different site.

**THE ELEVEN "REFUSALS" (CHK.46) LEFT ARE FIVE MECHANISMS, AND ONE OF THEM IS ALREADY CLOSED.**
Measured with three throwaway arms rather than by reading: (i) lifting `cmamDestructuredReceiverType`'s
own lines produces a WRONG TYPE for the union source (`Inner` for `Holder | Inner`) and the
class instance (`typeof Cls` for `Cls`) — those two need type CONSTRUCTION, not a relaxed guard;
(ii) the rest element and the array pattern are refused by the SHARED `typeCaptureDestructured`,
which the (API.3d) capture channel also reads; (iii) the heritage, generic-instantiation and
tuple leaves are refused downstream by `cmamCheckResolvedObjectType` — B153 territory, the layer
(CHK.45) measured as knip false positives when relaxed; (iv) only the `let` binding wakes with
the CORRECT type, and it is exactly (CHK.44)'s measured 3-false-positive population; (v) a CALL
receiver never reaches the family at all (`narrowingEligible`). **The NULLISH refusal is half
phantom**: `leaf?: Inner` was never refused and already matches tsgo, only the explicit
`Inner | undefined` form is.

**GATES.** Suite **16,067 / 0 / 3** (+17, exactly the two new classes), **zero corpus baselines
moved**. 8-profile grid **`added=0 removed=0` on all eight** against a parent rebuilt in this
session (javap control: the three new methods read 0 before, 3 after). **knip 66 -> 49**,
seventeen removals and no additions, every one a false positive tsgo is silent on — fifteen of
them `Property '0' does not exist on type 'Plugin'` where a `for (const plugin of …)` loop
variable resolved to the file's own `const plugin: Plugin`. `cost_gate.py` **PASSES with NO
rebaseline** — `output.errors` **46**, `spine.nodes` +0.00%, largest movement
`narrow.memoServed` **+0.69%**. `huge_methods.py --fail-over 0` exit 0, **783** classes.
`partition-equivalence` **EQUIVALENT 78/78**, floor **57 ms** [57, 55, 59, 57] (one draw).
`capture-equivalence` **1,005 / 43 of 76 / moreAny 0**, `definitions` **360,376**, both ARM
DIGESTs unmoved.

**FOURTEEN ABLATION ARMS, ONE MISTAKE EACH, EACH DIFFED AGAINST ITS OWN SNAPSHOT.** One leg was
DELETED rather than shipped un-gateable (a10, inert on the pins, on knip AND on the grid); one
arm's uniquely-its-own failure is a knip ROW rather than a pin (a2); and four arms are recorded
as REDUNDANT GUARDS with the layer that actually refuses (a8, a9, b2, b3). **Arm a4 read `0 RED`
on its first run and was NOT a dead leg** — a REST element under a colliding file-level `const`
is the one shape where both helpers refuse and the fallback would restore the outer reading, and
a pin for it took a4 to 1 RED.

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

