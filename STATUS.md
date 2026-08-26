# Status

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

**AN `async` FUNCTION'S *INFERRED* RETURN TYPE IS A `Promise`, AND IT WAS WRONG IN BOTH
DIRECTIONS — 3 FALSE POSITIVES AND 4 FALSE NEGATIVES ON ONE SEVEN-SHAPE FIXTURE, tsgo 7.0.2
REPORTING EXACTLY THE COMPLEMENT (2026-08-26, (CHK.40)).** The queue item read its own row (e)
as "an async object-literal method's parameters are not contextually typed"; measured, the
parameters were fine and the RETURN TYPE was not — an `async` function-like with no return
annotation carried its BODY's type, so `async function f() { return 1 }` read `() => number`.
Wrapped at the **eight** inferred-return sites; an ANNOTATED return type is never touched, a
GENERATOR is deliberately excluded, and a lib with no `Promise` is bit-for-bit unchanged.

**(c) HAD ITS ROOT ONE LAYER BELOW THE TS7006 WALKER, AND THE FILE ALREADY SAID SO.**
`getTypeOfSymbolWorker`'s MethodDeclaration arm read `decl.name as? Identifier` and answered
`anyType` for anything else — a residue round 937 named and left — so
`interface VS { "m-x"(node: N): void }` had that member **present and typed `any`** while the
property form was byte-correct. It now takes the name with `declaredMemberName`, the same
helper that REGISTERED the member. (a)/(b)/(d) are one new arm: the contextual type of a
`return` POSITION (the enclosing function's annotation, else the signature that contextually
types it, with one `Promise<>` stripped when it is `async`), fed through an index-aware array
arm so a TUPLE contributes its own slot.

**THE ROUND'S BIGGEST FINDING DID NOT SHIP: A FUNCTION BODY NESTED IN A `return` EXPRESSION IS
NOT CHECKED AT ALL** — the ONE expression position that does not reach
`walkFunctionBodiesInExpr` (a var-decl initializer, a call ARGUMENT and an object-literal
property value all do). With the two-line arm in, both probes reach FULL PARITY with tsgo, the
corpus stays 15,928/0/3 and **knip stays 66 with every row identical** — and the grid gains
**3 rows**, so it is queued as **(CHK.42)** with the cost characterized rather than shipped.
One of those 3 is **(CHK.43)**, a SHIPPED false positive the walk merely exposes: a chained
`x as unknown as T` in a `return` keeps the INNER assertion's type when the annotation is a
≥3-member union, reachable today at top level in four lines.

**GATES.** Suite **15,928 / 0 / 3** (+23 pins over 15,905: 18 core + 5 hover), **zero corpus
baselines moved**. `cost_gate.py` **PASSES with NO rebaseline** — `output.errors` **46**,
largest movement `mapped.hits` **+0.74%**. `huge_methods.py --fail-over 0` exit 0, **783**
classes scanned. `partition-equivalence` **EQUIVALENT 78/78**, floor **79 ms** [79, 87, 56,
56] (one draw). `capture-equivalence` **1,005 / 43 / moreAny 0** — the standing state — with
both digests MOVED and `definitions` 360,336 -> **360,361**, the expected direction. 8-profile
grid against a rebuilt parent, `javap`-controlled: **`added=0 removed=0` on all eight**. knip
**66 -> 66** with a BEFORE arm rebuilt in the same session. **Nine ablation arms, each with
uniquely-its-own failures; a3/a4 partition the return family by LAYER (TYPE vs ARITY) rather
than by shape.**

**CONTEXTUAL TYPING SUPPLIED AN *ARITY*, NOT A *TYPE* — EVERY CONTEXTUALLY-TYPED PARAMETER IN THIS
CHECKER WAS `any`, AND A HOVER ON ONE SAID `any` FOR EVERY CODEBASE (2026-08-25, (CHK.39)). The
item's own six-shape probe went **0 of 6 reported here** to **6 of 6**, matching
`tools/tsgo-7.0.2/lib/tsc` row for row.** `spineIanyFnExprEnter` decided TS7006 from the
contextual signature's parameter COUNT (B224), so a covered parameter went quiet and then stayed
`any` to every reader of a type — a false-NEGATIVE family the whole (CHK.30) arc sat on top of.
`pullContextualTypeAt` is tsc's `getContextualType`, **PULLED from the parent chain** because the
INV.4 spine arrives at a function body carrying no contextual ambient at all (round 911).

**THE STRUCTURAL FINDING: IT NEEDS TWO CALL SITES AND THE ABLATION PARTITIONS THEM EXACTLY.** A
statement nested in a function body is EMISSION-OWNED by the legacy walk — the spine's own anchor
runs `recordOnly` for it and truncates every diagnostic — so the obvious fix (the spine frame
alone) is correct and **completely invisible**. `checkFunctionBody` is the emitting half (7 pins),
`ctaFnBodyFrame` the capture half a hover reads (3 pins), 7 + 3 = the 10 that the whole-pull arm
reddens. B85.1a is load-bearing beside them: an OPTIONAL contextual parameter is `T | undefined`,
and the bare type was the round's one measured false positive (three profiles,
`findAllReferences.ts`'s `baseSymbol?: Symbol`).

**TWO MORE DEFECTS SURFACED BECAUSE THE TYPES DID.** (CHK.39b): an object-literal METHOD's body
was not walked by the assignability walker AT ALL in a `.ts` file — `walkFunctionBodiesInExpr`'s
`if (jsLike)` is a gate about `this`, and it was silently deciding whether the body is CHECKED.
And KIR: `lowerFunctionValueCall` emitted a direct `FunctionN.invoke`, but **TypeScript's function
assignability accepts a LOWER-arity function** (mitt registers a one-parameter wildcard handler
against a two-parameter type), so both mitt corpus tests died with a `ClassCastException` the
moment the callee stopped being `any`. It goes through `jsCallN` now.

**(CHK.39c) REFUSED — and the refusal is the round's most transferable result.** Giving the
PROPERTY-ACCESS family its last two contextual sources takes all four probes to full parity with
tsgo **and leaves all 8 dashboard profiles `added=0 removed=0`** — and costs **+15 false positives
on knip (66 -> 79)**, every one a parameter whose contextual type is a UNION that the body narrows
by ASSIGNMENT (`if (typeof x === 'function') x = x()`). That walker has no assignment/`typeof`
narrowing for a parameter; the grid is structurally blind to it. Re-queued as **(CHK.41)**, and
pinned as KNOWN GAP so the next round sees it rather than rediscovering the chain.

**GATES.** Suite **15,905 / 0 / 3** (+22 pins over 15,883), **zero corpus baselines moved**.
`output.errors` **46** throughout — a real gate here, and it is what caught the optional-parameter
FP. **`typeNode.bypassed` +31.26% REBASELINED**: ~17.7k per call site, essentially all of it
`cpaComputeArgCtxTypes` (the inference-aware resolver, which is what makes `xs.map(x => …)`
work); by round 716's price that is **~+21 ms, ~0.4% of a warm rebuild**, and the unspent lever is
a per-node memo, since the two sites ask the same question about the same node.
`huge_methods.py --fail-over 0` exit 0, **783** classes scanned. `partition-equivalence`
**EQUIVALENT 78/78**, floor **61 ms**. `capture-equivalence` **1,005 / 43 / moreAny 0** with
**both digests MOVED — the expected direction**, a parameter with a real type renders differently
and `definitions` rose 360,152 -> 360,336. 8-profile grid vs a rebuilt parent (positive control on
`javap`): `added=0 removed=0`. **knip 66 -> 66, every row identical**, with the BEFORE arm rebuilt
in the same session against the same re-fetched dependency set.

**A TYPE IMPORTED FROM A `node_modules` PACKAGE RESOLVED TO `any` — SILENTLY, ON EVERY REAL
PROJECT (2026-08-25, (CHK.30)). knip **156 -> 66** ERRORS, TS7006 **89 -> 1**, AND NO ROW
APPEARED THAT WAS NOT THERE BEFORE.** The queue entry called this "an object-literal method's
parameters are not contextually typed" and its diagnosis was WRONG: written out by hand, that
shape and five variants of it are silent on a pre-fix binary. knip's `PluginVisitorObject` is
`VisitorObject`, and `VisitorObject` comes from `'oxc-parser'`. **The mechanism**: the crawl
resolves a bare specifier correctly and the package's `.d.ts` really is in the program, but the
CHECKER re-derives which file a specifier names by string-matching it against the program's file
NAMES, and that corpus-era matcher cannot express a bare specifier — a package's
`types`/`main`/`exports` entry is not a string transformation of `pkg`. Fifteen lines reproduce
it: a `node_modules/tiny/index.d.ts` imported bare gives us **0 errors** where tsgo gives four.
`ParsedSource.moduleResolutions` now carries the crawl's own `(importer, specifier) -> file`
answers into the `Checker` as the last leg of all ten alias ladders.

**IT FAILS IN THE SILENT DIRECTION, WHICH IS WHY IT SURVIVED.** `any` is legal everywhere, so
nothing MOVED at the import — the only thing that surfaced was the false-positive SHADOW, a
TS7006 on every un-annotated callback parameter whose contextual type lived in the package. 89 of
knip's 156 rows, read as a contextual-typing family.

**A SECOND, SMALLER DEFECT LANDED WITH IT**: a concise-body arrow's OWN return annotation was not
a contextual type for its body in either walker, while a BLOCK body always had it at the `return`
edge — so `(): V => { return {…} }` was right and `(): V => ({…})` was not, and nobody noticed
because the two spellings are interchangeable to a reader.

**WHAT DID NOT WORK IS THE ROUND'S MOST TRANSFERABLE FINDING.** The first arrow fix silenced
every TS7006 asked for and the POSITIVE half of the probe showed it had typed NOTHING. Pushing on
that found something larger: **every contextually-typed parameter in this checker is still `any`
to the assignability walker**, back to the plain arrow ARGUMENT — `take((node) => { const bad:
string = node.kind; })` is silent here and TS2322 under tsc. Contextual typing here supplies an
ARITY (which is what decides TS7006) and never enters the parameter into the scope those walkers
read. Queued as **(CHK.39)** with its probe; four further unread contextual SOURCES are
**(CHK.40)**.

**GATES.** Suite **15,883 / 0 / 3** (+12 pins, exactly the two new classes), zero corpus
baselines moved. `cost_gate.py` PASSES, `output.errors` **46** — a real gate here, not a
control: the vector is the standing one plus `typeOfExpr.calls` **+0.18%** / `narrow.walks`
**+0.05%**, which are one extra annotation resolution per reached concise-body arrow, far inside
±2% and not rebaselined. `huge_methods.py --fail-over 0` exit 0, **783** classes scanned
(unchanged, correctly — no new class). `partition-equivalence` **EQUIVALENT, all 78**, floor
**57 ms**. `capture-equivalence` **1,003 / 43 / moreAny 0**, **both digests BIT-IDENTICAL**.
`round895-grid` 8 profiles `added=0 removed=0`, and a BEFORE/AFTER 8-profile grid against a
rebuilt parent (positive control: `javap` finds the new method 0 times before, 1 after) is
`added=0 removed=0` on all eight.

**Ablation, five arms, one mistake each.** a1 (the crawl-map leg never answers) reddens the four
package pins and leaves the negative control green; a3 (the implicit-any walker forgets the
annotation) reddens all five arrow pins; a4 and a5 redden the SAME single pin, so they are ONE
observable, not two. **a2 is `0 RED` across the whole 15,883-test suite** — the `node_modules`
walker legs the first cut also consulted are a REDUNDANT GUARD wherever the crawl can answer, so
they were REMOVED rather than shipped un-gateable.
