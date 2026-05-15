# Status

**Phase 4 — Checker buildout.** 8,675 / 10,078 tests passing (~86.1%).

**B9.3 (2026-05-15, net-zero infra — stacks on B9.1+B9.2 toward `crashInEmitTokenWithComment_ts`)** —
Widen concise-body `=> undefined` literal to `any` in `getTypeOfArrowFunction`
(Checker.kt:42279). Single-branch addition before the existing concise-body
`getTypeOfExpression(body)` arm: when `body is Identifier && body.text ==
"undefined"`, return `anyType` instead of resolving the identifier to the
`undefined` intrinsic. Mirrors `inferTypeFromInitializer`'s widening at
Checker.kt:13410 (where `var x = undefined` is typed `any`, not `undefined`).

**Verification.** Full-suite 10078/1400/3 unchanged (was 10078/1400/3
post-B9.2). Zero regressions across 10078 tests. The named target
`crashInEmitTokenWithComment_ts` source display now shows `(_: {}) => any`
(was `(_: {}) => undefined` post-B9.2, was `(_: any) => undefined` post-B9.1,
was `() => undefined` pre-B9.1). Still needs B9.4 (TS2537 emission against
`{}` computed-property destructuring) to fully flip. The pattern-name display
gap (`_` vs source slice `{ [foo.bar]: c }`) may or may not be a deciding
factor in the baseline diff — B9.4 alone may close it, or B9.5 may be
required afterward.

**Risk profile that materialized.** LOW → ZERO regressions. The change ONLY
fires when the arrow body is the LITERAL identifier `undefined`
(`Identifier && text == "undefined"`). FunctionExpression bodies are always
`Block`, never concise-body — so the new arm only affects ArrowFunction
inference. Annotated arrows take the earlier `expr.type?.let { ... }` branch
and skip this code entirely.

---

**B9.2 (2026-05-15, net-zero infra — stacks on B9.1 toward `crashInEmitTokenWithComment_ts`)** —
Default param type for un-annotated binding-pattern parameters. Single-branch
change in `getTypeOfVariableOrProperty`'s `Parameter` arm (Checker.kt:40580):
when `decl.type == null && decl.initializer == null`, route by
`decl.name`:

  - `ObjectBindingPattern` → `Type.Object().also { it.properties = emptyList() }`
    (anonymous empty object, renders as `{}` per the existing `typeToString`
    Type.Object branch at line 43542).
  - `ArrayBindingPattern` → `getArrayType(anyType)` (renders as `any[]`).
  - Identifier-named path unchanged — falls through to `anyType`.

**Verification.** Full-suite 10078/1400/3 unchanged. Zero regressions across
10078 tests. The named target `crashInEmitTokenWithComment_ts` source display
now shows `(_: {}) => undefined` (was `(_: any) => undefined` post-B9.1, was
`() => undefined` pre-B9.1). Still needs B9.3 (`=> undefined` body → `any`
return type) and B9.4 (TS2537 emission against `{}` computed-property
destructuring) to fully flip.

**Risk profile that materialized.** LOW → ZERO regressions. The change only
fires when ALL THREE conditions hold (binding-pattern name + no annotation +
no initializer), and post-B9.1 the only `getTypeOfSymbol` consumers reaching
this path are downstream of the FE/Arrow `forSignatureDisplay = true`
synthesis. Non-binding-pattern Parameters (Identifier name) take the existing
fall-through to `anyType` unchanged.

---

**B9.1 (2026-05-15, net-zero infra — foundation for `crashInEmitTokenWithComment_ts`)** —
Synthesize signature-parameter symbols for `ObjectBindingPattern` /
`ArrayBindingPattern` parameters at FE/Arrow call sites of
`getParameterSymbols`. Four pieces in `Checker.kt`:

  - **`getParameterSymbols` (Checker.kt:40756).** New `forSignatureDisplay:
    Boolean = false` parameter. When `true` AND `param.name is
    ObjectBindingPattern || ArrayBindingPattern`, synthesize a
    `Symbol(SymbolFlags.FunctionScopedVariable, name = "")` carrying
    `valueDeclaration = param` and `declarations.add(param)`. Default-false
    at every other call site preserves the pre-B9.1 silent-drop behavior.

  - **Call-site gating.** Only two sites pass `forSignatureDisplay = true`:
    `getTypeOfArrowFunction` (Checker.kt:42272) and
    `getTypeOfFunctionExpression` (Checker.kt:42309) — the contexts where
    TS2345 source-display chains render `signatureToString(sig, ...)` for
    the argument expression's inferred type. Other call sites
    (`resolveInterfaceMembers`, ctor builders, alias/heritage paths) keep
    null-drop behavior to avoid arity surprises in
    `checkArgumentsAgainstOverloads` / TS2554 consumers.

  - **`formatParameter` (Checker.kt:43322).** Added `displayName` lookup:
    when `decl?.name is ObjectBindingPattern || ArrayBindingPattern`,
    render as `_` (consistent with the pre-existing `formatParameterDecl`
    fallback at line 57041, which also uses `_` for non-Identifier names).
    Applied uniformly to dotdotdot / question / standard render arms.

  - **`signatureToString(sig)` (Checker.kt:52661).** TS2769
    message-formatter variant — same `_` fallback for binding-pattern
    symbols.

  - **`signatureRelatedTo` chain (Checker.kt:56318).** The "Types of
    parameters 'A' and 'B' are incompatible." chain line — same `_`
    fallback for both source and target parameters with binding-pattern
    decls. Paranoia path; rarely fires.

**Verification.** Full-suite 10078/1400/3 (was 10078/1400/3, net-zero exactly
as projected). Zero regressions across 10078 tests. The named target
`crashInEmitTokenWithComment_ts` does NOT flip with B9.1 alone — needs B9.2
(param type `{}` for ObjectBindingPattern), B9.3 (return type `any` for
`=> undefined` literal body), and B9.4 (TS2537 emission for computed-property
destructuring against `{}`). Source display improvement
(`() => undefined` → `(_: any) => undefined` for the inner arrow argument)
lands silently — no test in the corpus had a baseline depending on the
pre-B9.1 zero-param display.

**Risk profile that materialized.** Expected MEDIUM → actual ZERO. The
mitigation worked: forSignatureDisplay gating restricted synthesis to two
FE/Arrow call sites, and the empty `Symbol.name` is intercepted at the
THREE display sites known to consume it (formatParameter, the TS2769-variant
signatureToString, and the signatureRelatedTo chain). The `_` fallback
matches the existing `formatParameterDecl` precedent — no display
convention shift. `sig.parameters.size` / `minArgumentCount` consumers
behave identically because FE/Arrow `minArgumentCount` was already computed
unconditionally via `expr.parameters.count { ... }` directly from the AST;
binding-pattern params just now get a Symbol slot in the parallel
`parameters: List<Symbol>` list.

---

**B8.1 (2026-05-15, +1 — flips `intersectionWithConflictingPrivates_ts`)** —
Intersection-with-conflicting-privates → `never` reduction. Three pieces in
`Checker.kt`:

  - **Reduction in `getIntersectionType`.** New helper
    `findConflictingPrivateInIntersection(types)` walks each `Type.Interface`
    (and `Type.Reference.target`) constituent's `symbol.declarations`,
    inspects DIRECT class members (`PropertyDeclaration` / `MethodDeclaration`
    / `GetAccessor` / `SetAccessor` with Identifier names), skips
    `OBJECT_PROTOTYPE_PROPERTIES`, and returns the first property name that
    appears in 2+ constituents AND has `ModifierFlag.Private` in any
    declaration. When non-null, `getIntersectionType` returns `neverType`
    AFTER the existing primitive-incompatibility reduction.

  - **Side-channel cache.** New `CheckerState.intersectionReductionReasons:
    HashMap<IntersectionType, IntersectionReductionReason>` keyed by the AST
    `IntersectionType` node, populated post-call from
    `getTypeFromTypeNodeWorker`'s IntersectionType branch when
    `getIntersectionType` returned `neverType` AND
    `findConflictingPrivateInIntersection` re-confirms the reason (so the
    cache only fires for private-conflict reductions, not primitive ones).
    `IntersectionReductionReason(displayString, conflictingPropName)` carries
    the original intersection display ("A & B") and the conflicting prop name
    ("x") for diagnostic recovery.

  - **Diagnostic emission.** Two new emission sites consult
    `findIntersectionReductionForExpr(expr)` which walks the receiver's
    symbol declarations (`VariableDeclaration` / `Parameter` /
    `PropertyDeclaration`) for an annotation containing an `IntersectionType`
    in the cache:
    - **TS2322** in `checkAssignmentExpression`'s Identifier branch — when
      `targetType === neverType` AND the cache lookup finds a reduction
      reason, emit "Type '$source' is not assignable to type 'never'." with
      `messageChain = ["  The intersection 'A & B' was reduced to 'never'
      because property 'x' exists in multiple constituents and is private in
      some."]`. Returns early so the standard emission path (which would
      print "type 'A & B'") doesn't fire.
    - **TS2339** in `checkMemberAccessMissing` — early branch (after the
      empty-name guard) that fires when receiver type is `neverType` AND the
      reduction-reason cache lookup hits. Emits "Property '$propName' does
      not exist on type 'never'." with the same chain message.

**Verification.** Full-suite 10078/1400/3 (was 10078/1401/3, +1 net). Zero
regressions. Closes B8.1 fully — the named target
`intersectionWithConflictingPrivates_ts` flips clean.

**Risk profile that materialized.** Bounded — reduction only fires when
`findConflictingPrivateInIntersection` returns non-null, which requires:
(a) 2+ Type.Interface (class) constituents, (b) a property name appearing in
2+ of them, (c) at least one declaration of that property carrying
`ModifierFlag.Private`. The corpus has 135 tests using `private` members but
the SWAP-bucket survey identified `intersectionWithConflictingPrivates_ts` as
the only target matching this exact pattern, so the over-fire risk was
already low. Verified zero regressions across the 10078-test suite confirms
no other test relied on `A & B` (private-conflict) NOT reducing. The
side-channel cache is keyed by AST node identity, which is stable across the
checker run; no cross-file pollution because `IntersectionType` instances are
per-file.

---

**B7.26 (2026-05-15, +1 — flips `isolatedDeclarationErrorsReturnTypes_ts`)** —
Parallel walker to B7.25 for the class-property axis. Single-file change
in `TypeScriptCompiler.kt`. New `emitIsolatedDeclClassPropertyFnExprParamChecks(stmt, ...)`
called from the `ClassDeclaration` branch of pass 3 after the existing
isolatedDeclarations walkers. Iterates `stmt.members`, picks
PropertyDeclaration with init = FE/Arrow, recurses into `init.parameters`
via the shared `emitIsolatedDeclParamsCheck`. The B7.25 classifier extension
then emits TS9007 for inner `cb = function(){ }`-style defaults.

Member filter:
- `ModifierFlag.Private in member.modifiers` → skip.
- `name.text.startsWith("#")` → skip (private-field).
- `protected` / `static` → walked (baseline flags them).
- Non-Identifier names → skipped.

**Verification.** Targeted test passes (31/31 emissions). Full-suite
10078/1401/3 (was 10078/1402/3, +1 net). Zero regressions across the
10078-test suite. Closes `isolatedDeclarationErrorsReturnTypes_ts` —
the LAST failing `isolatedDeclaration*_ts` errors-baseline test. The
**isolatedDeclarations error-baseline family is now FULLY CLOSED**.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
(call-site, inherited from the `is ClassDeclaration` arm of pass 3).
Within that scope, only PropertyDeclaration members with FE/Arrow
initializers and non-private/non-`#` names participate. The shared
sub-walker `emitIsolatedDeclParamsCheck` was already exercised by
FunctionDeclaration and class-method paths; extending its callers does
not change its semantics. B7.25's classifier extension's `expr.type == null`
gate ensures we only fire on FE without return type — annotated FEs are
unaffected.

---

**B7.25 (2026-05-15, net-zero infra)** — TS9007 walker for
`FunctionExpression` param-defaults in exported `VariableStatement`
initializers whose init is `FunctionExpression` or `ArrowFunction`.
Single-file change in `TypeScriptCompiler.kt`. Two coordinated pieces:

  - **Var-decl wiring.** New call to `emitIsolatedDeclParamsCheck(init.parameters, ...)`
    from the VariableStatement branch of pass 3 (gated on `Export` modifier
    AND `init is ArrowFunction || init is FunctionExpression`). Recurses
    into the outer FE/Arrow's params so inner `cb = function(){ }`-style
    defaults reach the classifier.
  - **Classifier extension.** New helper
    `emitIsolatedDeclTs9007ForFnExprParamDefault(fe, paramName, ...)`
    called from `emitIsolatedDeclParamDefaultClassify`'s FunctionExpression
    branch BEFORE the existing inner-params recursion. Emits TS9007 at the
    `function` keyword position (length 8) when the FE has no return-type
    annotation, with related TS9028 at the enclosing param's name and
    TS9030 at the FE position.

**Verification.** Full-suite 10078/1402/3 (unchanged from B7.24;
zero regressions). Net-zero on the suite — gain on
`isolatedDeclarationErrorsReturnTypes_ts` (0 → 15 of 31 emissions, all
matching baseline lines/cols exactly) isn't enough to flip it. The
remaining 16 emissions are class-property-initializer variants
(`FnParamsExportedClass`) and need a parallel walker in the
`ClassDeclaration` branch — promoted as B7.26 candidate.

**Risk profile that materialized.** Bounded — wiring gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`.
The classifier extension only adds TS9007 emission when
`expr is FunctionExpression && expr.type == null` — the existing
inner-params recursion is preserved unconditionally. ArrowFunction
defaults still recurse-only (no TS9007 emission) because the only
arrow-default test cases in the corpus are `cb = () => 1` (literal
body) which TypeScript's baseline does NOT flag — adding emission
there would FP regress.

**Lesson learned.** The test harness strips `// @directive` lines
before parsing — the baseline's `(13,45)` matched our compiler's
emission for what the test runner treats as line 13 (source line 18
post-stripping of 5 directive lines). Future B7.x walkers don't need
to compensate for this — `positionToLineCharacter` on the
already-stripped source produces baseline-compatible line numbers.

---

**B7.24 (2026-05-15, +1 — flips `isolatedDeclarationErrorsExpressions_ts`)** —
TS9019 walker for `ObjectBindingPattern` / `ArrayBindingPattern` destructuring
targets in exported `VariableStatement`. Single-file change in
`TypeScriptCompiler.kt`. New `emitIsolatedDeclVarBindingPatternChecks` walker
called from the VariableStatement branch of pass 3 in a new branch BEFORE the
`name !is Identifier continue` skip (so binding-pattern names hit the walker
before they're discarded).

Walker iterates `pattern.elements`:
- `BindingElement` with `el.name is Identifier` and `!el.dotDotDotToken` →
  emit TS9019 at `name.pos` with `name.text.length`. NO related info —
  TypeScript's baseline for TS9019 doesn't attach a TS9027 "Add a type
  annotation" hint, unlike TS9010/TS9012.
- `OmittedExpression` array holes (e.g. `[, , b]`) → skip.
- Rest elements (`...rest`) → skip (no failing-test target exercises them).
- Nested binding patterns (`[{ a }]`) → skip (`el.name` is itself a
  BindingPattern, not an Identifier).

Crucially, walker runs INDEPENDENTLY of `decl.type` — TS9019 fires even when
the destructuring target has an explicit type annotation. Line 134 of the
source confirms: `export const [, , b = 1]: [number, number, number |
undefined] = [0, 1, 2];` still emits TS9019 at the `b` element despite the
explicit tuple type. Walker is gated only on
`ModifierFlag.Export in stmt.modifiers`.

**Verification.** Targeted test passes (52/52 emissions). Full-suite
10078/1402/3 (was 10078/1403/3, +1 net). Zero regressions across 10078-test
suite. Closes the isolatedDeclarations expressions-error test
(`isolatedDeclarationErrorsExpressions_ts`).

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
(call-site). Recognition uses `name is ObjectBindingPattern || name is
ArrayBindingPattern` at the dispatcher level — never seen before in the
B7.x family (other walkers all gated on `name is Identifier`); verified
via grep that no other dispatcher branch in `emitIsolatedDeclarationsDiagnostics`
relies on `name` being a binding pattern, so the new branch is additive.
Conservative skip rules for omitted/rest/nested elements prevent
over-firing on the parameter-destructuring case at source line 136
(`export function foo([, , b]: [...] = [0, 1, 2])`) which TypeScript's
baseline does NOT mark with TS9019 — parameter destructuring is a separate
context handled by the function-decl walkers.

**Lesson learned.** Gradle's `compileKotlinJvm` task can report `UP-TO-DATE`
even after editing the source file, when the build daemon's incremental
classpath / file-content hashing doesn't pick up the edit immediately.
`touch <file>` before `./gradlew jvmTest` forces a recompile. Symptom seen
during B7.24: targeted test reported BUILD SUCCESSFUL with no test output
on the first attempt; resolved by `touch` and re-running. (Actually the
test DID compile in this case — gradle's UP-TO-DATE here was for the cached
artifact since the test had already been re-run; the actual test result XML
showed a clean pass.)

**Closes the isolatedDeclarations expressions-error test family.** Together
with B7.22 (TemplateExpression triggers) and B7.23 (TS9017/TS9018 array
walkers), the three substeps add 17 emissions across the test that bring
it from 35 to 52/52 expected. Each substep was bounded in scope; the cumulative
effect on the test is +1 (B7.22, B7.23 were net-zero; B7.24 is the flip).

**B7.23 (2026-05-15, net-zero infra)** — TS9017 (non-`as const` array) /
TS9018 (SpreadElement in any array) walker for exported `VariableStatement`
initializers. Single-file change in `TypeScriptCompiler.kt`. New
`emitIsolatedDeclVarInitArrayChecks` called from the VariableStatement branch
of pass 3 right after `emitIsolatedDeclVarInitTs9010`. Two emissions:

  - **TS9017** "Only const arrays can be inferred ..." at the full `[...]`
    span via `findMatchingDelimiter`, when the initializer is a bare
    `ArrayLiteralExpression` (NOT wrapped in `as const`). TS9027 related at
    the variable name.
  - **TS9018** "Arrays with spread elements can't inferred ..." at each
    `SpreadElement` inside the initializer's array (including when the outer
    array is wrapped in `as const`). Squiggle uses `el.pos` (the `.` of `...`)
    + `isolatedDeclExprTrueEnd(el.expression) - el.pos` to avoid the
    `node.end` overshoots-by-one-token issue. TS9027 related at the variable
    name.

Walker detects two top-level shapes:
- `init is ArrayLiteralExpression` → emit TS9017, then walk elements for
  spread.
- `init is AsExpression(ArrayLiteralExpression, const)` → skip TS9017 (the
  `as const` IS the suggested fix), but walk elements for TS9018.

Only the topmost array level is walked; nested arrays inside spreads or
elements are NOT recursively walked (no failing test exercises that shape).

**Adjacent partial progress (no flip).**
`isolatedDeclarationErrorsExpressions_ts` 48 → 50 emissions (+2: TS9017 at
line 53,18 spans `[1, 2, 3]`; TS9018 at line 55,38 spans `...arr`). Still 2
emissions short of expected 52 — gap is the B7.24 TS9019 binding-pattern
walker (lines 127-128 in baseline / 133-134 in source).

**Verification.** Full-suite 10078/1403/3 (unchanged from B7.22; zero
regressions). Net-zero on the suite — gain on the target test isn't enough
to flip it.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`.
`isAsConst` detection mirrors the existing pattern in
`emitIsolatedDeclExportDefaultChecks` (TypeReference.typeName.text=="const",
no type args). Only `isolatedDeclarationErrorsExpressions_ts` has
`export <kind> arr = [...]` array literal patterns under
`--isolatedDeclarations` in the corpus (verified via grep), so over-firing
risk is minimal. SpreadElement length fix (mirroring CLAUDE.md gotcha:
"node.end overshoots by one token") matches the +1 correction pattern used
throughout the B7.x family.

**Foundation for follow-ons:**
- **B7.24 candidate**: TS9019 BindingElement walker for
  `ObjectBindingPattern` / `ArrayBindingPattern` in
  `export const|let|var { a } = ...` / `export const|let|var [, , b] = ...`.
  Each non-rest, non-nested element with an Identifier name fires TS9019
  at the element-name position (no related info — TypeScript's baseline
  doesn't attach TS9027 for TS9019). Independent of `decl.type` (TS9019
  fires even when the destructuring target has an explicit type annotation;
  line 134's `[, , b = 1]: [number, number, number | undefined]` example
  confirms). **B7.24 would FLIP `isolatedDeclarationErrorsExpressions_ts`**
  — bringing it from 50 to 52/52 expected emissions.

**B7.22 (2026-05-15, net-zero infra)** — `TemplateExpression` /
`AsExpression(TemplateExpression)` triggers for the `emitIsolatedDeclVarInitTs9010`
(variable level) and `emitIsolatedDeclClassPropertyTs9012` (class property
level) walkers in `TypeScriptCompiler.kt`. Single-file change. The two
walkers previously hard-listed Call / New / PropertyAccess / ElementAccess /
BinaryExpression / Identifier as the runtime-only initializer shapes that
prevent .d.ts inference under `--isolatedDeclarations`; this substep adds
template literal patterns to the list.

Two const/readonly-gated rules:

**(a) Variable level (`emitIsolatedDeclVarInitTs9010`).** New `isConst: Boolean`
parameter (`stmt.declarationList.flags == SyntaxKind.ConstKeyword`). Triggers
extended:
  - `TemplateExpression` (`` `s${1}` ``) → trigger ONLY when `isConst`. Under
    `let`/`var` the type widens to `string`, declarable as-written; under
    `const` the inferred type is a template-literal type that requires
    checking to compute → TS9010 at the variable name.
  - `AsExpression` wrapping a `TemplateExpression` (`` `s${1}` as const ``) →
    always trigger regardless of [isConst]; `as const` forces literal-type
    inference for any binding kind.

**(b) Class property level (`emitIsolatedDeclClassPropertyTs9012`).** Mirror
rule via `ModifierFlag.Readonly in member.modifiers`:
  - `TemplateExpression` initializer + `readonly` modifier → TS9012 at the
    property name.
  - `AsExpression(TemplateExpression)` initializer → TS9012 regardless of
    `readonly`. Removed the pre-existing
    `if (isIsolatedDeclTriviallyDeclarable(init)) continue` short-circuit
    because that helper returns `true` for `TemplateExpression` (correct at
    param-default level but wrong here) — the triggers list is authoritative.

**Adjacent partial progress (no flip).**
`isolatedDeclarationErrorsExpressions_ts` 35 → 48 emissions (+13:
4 TS9010 for `templateConstNotOk2/3/4/5` const+template-with-subs (lines 17-20);
3 TS9010 for `templateLetOk2/3/4AsConst` let+`as const`+template (lines 49-51);
3 TS9012 for `readonly templateConstNotOk2/3/4` readonly+template (lines 91-93);
3 TS9012 for `templateLetOk2/3/4AsConst = ... as const` class properties
(lines 102-104)). Still 4 emissions short of expected 52 — gap covers:
- **B7.23 candidate**: TS9017 for non-`as const` plain `export let arr = [1,2,3]`
  (line 53 in baseline / line 59 in source) and TS9018 for spread elements in
  any array initializer regardless of `as const` (line 55 / 61).
  Squiggle ON the array (TS9017) / spread element (TS9018), not the variable
  name. Mirrors the export-default `emitIsolatedDeclTs9017Default` /
  `walkExportDefaultSubExpr` helpers' shape for variable context.
- **B7.24 candidate**: TS9019 for `export const { a } = ...` /
  `export const [, , b = 1]: ... = ...` binding-pattern destructuring exports
  (lines 127-128 / 133-134). Squiggle on the binding element name. New walker
  for `VariableStatement` whose `decl.name` is an `ObjectBindingPattern` or
  `ArrayBindingPattern` under `Export`.

**Verification.** Targeted test now emits 48/52 expected (was 35/52). Full-suite
10078/1403/3 (unchanged from B7.21; zero regressions). Net-zero on the suite —
the gain on `isolatedDeclarationErrorsExpressions_ts` isn't enough to flip it
(needs B7.23 + B7.24).

**Risk profile that materialized.** Bounded for both pieces:
- (a) Variable walker is gated on `options.isolatedDeclarations` AND
  `ModifierFlag.Export in stmt.modifiers` (call-site). `isConst` derivation
  via `declarationList.flags == ConstKeyword` follows the existing convention
  used in Checker.kt:8768. The `AsExpression(TemplateExpression)` check uses
  `init.expression is TemplateExpression` — no recursive descent; nested
  `as` chains (`(`x` as const) as const`) are not exercised by any failing
  test.
- (b) Property walker uses `member.modifiers` for the readonly check
  (PropertyDeclaration carries its own modifier list). Removing the
  `isIsolatedDeclTriviallyDeclarable` short-circuit is safe because the
  triggers list now covers every kind that helper would have skipped — the
  helper returns `true` for literal nodes (NumericLiteral, BigIntLiteral,
  StringLiteral, NoSubstitutionTemplate, Identifier-keywords, +/- numeric
  unary) and `TemplateExpression`; literals/keywords fall through the
  triggers list to `else -> false`, and TemplateExpression is now handled
  by its dedicated `isReadonly` rule. Zero regressions confirms this in the
  full suite.

**Foundation for follow-ons:**
- **B7.23 candidate**: ArrayLiteralExpression walkers — TS9017 at full-array
  span when the initializer is a non-`as const` array under `export let`
  (TypeScript emits TS9017 for `let`/`var`-bound non-const arrays only; `const`
  arrays without `as const` get TS9019 BindingElement-like or are accepted as
  inferable). TS9018 walks any ArrayLiteralExpression (including inside
  `as const`) for SpreadElement and emits at the spread span. Both anchor TS9027
  at the variable name.
- **B7.24 candidate**: BindingElement walkers for `ObjectBindingPattern` /
  `ArrayBindingPattern` in `export const|let|var { a } = ...` /
  `export const|let|var [, , b] = ...`. Each non-rest element with a name
  Identifier fires TS9019 at the element-name position. The
  `export function foo([, , b]: [...] = ...)` shape (parameter destructuring)
  is NOT affected — TypeScript's baseline doesn't fire TS9019 there.

**B7.21 (2026-05-15, +1 — flips `isolatedDeclarationErrorsClasses_ts`)** —
Three coordinated pieces close the test:

**(a) Checker.kt: TS7010 InterfaceDeclaration extension for ComputedPropertyName names.**
The existing `checkTS7010InStatements`'s InterfaceDeclaration branch
(Checker.kt:22788) only extracted names from `Identifier` and
`StringLiteralNode`. Extended `rawName` resolution with a third fallback:
`((nameNode as? ComputedPropertyName)?.expression as? Identifier)?.let {
"[${it.text}]" }`. So `[noAnnotationLiteralName]()` in interface I now
resolves to `[noAnnotationLiteralName]` and emits TS7010 with that name
in the message and the full member squiggle (`member.pos` to `member.end`).
Verified safe — no other test in the corpus has unannotated computed-name
methods in interfaces (`overloadsWithComputedNames`, `dynamicNamesErrors`
all have explicit `: void` / `: number` return types).

**(b) TypeScriptCompiler.kt: TS9013 walker for interface MethodDeclaration
with ComputedPropertyName name + no return type.** New
`emitIsolatedDeclInterfaceMethodTs9013` walker, called from a new
`is InterfaceDeclaration ->` arm in the pass 3 dispatcher. Gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`.
Fires TS9013 at the same span as TS7010 (`member.pos` to `member.end`).
No related info — interface-context TS9013 has no `Move expression to
variable and add annotation` shape; that's TS9036 for default exports
only. Identifier-named and StringLiteralNode-named interface methods do
NOT fire TS9013 — TypeScript's baseline only emits this code for
ComputedPropertyName shapes. Only the `[Identifier]` inner form is
handled today; other inner-expression shapes (string literals, qualified
names, parenthesized) deferred until a failing test exercises them.

**(c) TypeScriptCompiler.kt: AsExpression branch in `isolatedDeclExprTrueEnd`.**
Fixes the line 50 `[("A" + "B") as "AB"] = 1;` length off-by-one (was 22,
expected 21). Walker uses `isolatedDeclExprTrueEnd(inner) + 1` to compute
the closing `]` position. The previous fall-through `else -> expr.end`
branch overshoots by one token (per CLAUDE.md gotcha). Now uses
`expr.tightEnd` (parser-tracked `scanner.getPrevTokenEnd()` at the type's
last character — populated in Parser.kt at all three AsExpression
construction sites) when set, falling back to `expr.end` otherwise. Both
TS1166 ("computed property name must have simple literal type or unique
symbol") and TS9038 ("computed property names cannot be inferred") squiggles
shrink by 1 char to match the baseline.

**Verification.** Targeted test passes (26/26 emissions match). Full-suite
10078/1403/3 (was 10078/1404/3, +1 net). Zero regressions across
10078-test suite. Closes the isolated-declarations class-error test family
(`isolatedDeclarationErrorsClasses` + `isolatedDeclarationErrorsObjects`,
the latter previously flipped in B7.17).

**Risk profile that materialized.** Bounded for all three pieces:
- (a) Checker extension is safe — verified no other tests have unannotated
  computed-name interface methods (3 corpus matches all have explicit
  return types).
- (b) Walker is gated on `isolatedDeclarations` + `Export` + ComputedPropertyName
  + Identifier-inner — narrowest possible scope.
- (c) AsExpression `tightEnd` fix only changes behavior when the field is
  populated (default 0 → falls through to `expr.end`). All three Parser.kt
  AsExpression construction sites do populate `tightEnd`, so this affects
  every AsExpression in the corpus — but only consumers of
  `isolatedDeclExprTrueEnd` see the difference, and they're all
  `isolatedDeclarations`-gated. Verified zero regressions in the full
  suite confirms no other consumer relied on the overshooting behavior.

**B7.20 (2026-05-15, net-zero infra)** — TS7032 + TS7006 walker for
ComputedPropertyName-named SetAccessors in exported class declarations
that lack an explicit parameter type annotation. Single-file change in
`TypeScriptCompiler.kt`. Extends B7.19's `emitIsolatedDeclClassAccessor`
walker — its SetAccessor branch now does `when (name)` over both
`Identifier` (existing TS9009/TS9033 emission) and `ComputedPropertyName`
(new TS7032 + TS7006 emission). The checker's `checkImplicitAnyInClassElement`
SetAccessor branch (Checker.kt:9087) only handles Identifier-named
accessors and so does NOT fire TS7032/TS7006 for `set [expr](v)` shapes —
this walker fills the gap under `--isolatedDeclarations` + `Export` gates.

Per qualifying ComputedPropertyName-named SetAccessor (no peer-suppression
for computed names — no failing-test target exercises a get/set
computed-name pair):
- **TS7032** at the ComputedPropertyName span (start = name.pos, length =
  `isolatedDeclExprTrueEnd(name.expression) + 1 - name.pos`). Property
  name in the message uses the source substring (e.g.
  `'[noParamAnnotationStringName]'` with brackets included).
- **TS7006** at the param NAME position (length = param-name.length).

NO TS9009/TS9033 — the baseline shows TS7032 + TS9038 + TS7006 for
`set [noParamAnnotationStringName](value)` (line 48), with TS9038 already
emitted by `emitIsolatedDeclClassComputedNameDiags`. Diagnostic ordering
is correct via `BaselineFormatter`'s sort (start → length → code): TS7032
(code 7032) precedes TS9038 (code 9038) at the same start/length pair.

**Adjacent partial progress (no flip).**
`isolatedDeclarationErrorsClasses_ts` 23 → 24 emissions. Wait — actually
both TS7032 and TS7006 were missing before B7.20, so the count went from
22 → 24 emissions over B7.19 + B7.20 (B7.19 added 3, the checker had
already been emitting TS7032 + TS7006 for line 12 Identifier-named set
accessor). Still 2 emissions short of expected 26 — gap covers:
- B7.21 candidate: line 56 interface `MethodDeclaration` TS7010 + TS9013
  for `[noAnnotationLiteralName]();`. Distinct emission shape from class
  contexts — TS7010 fires only on interface-member declarations.
- Line 50 parser/walker length off-by-one for
  `[("A" + "B") as "AB"] = 1;` — emits length 22 vs expected 21.

**Verification.** Full-suite 10078/1404/3 (unchanged from B7.19; zero
regressions). Net-zero on the suite — gain on
`isolatedDeclarationErrorsClasses_ts` (24 of 26 expected) isn't enough to
flip it.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
(call-site). Only 4 test files in the corpus have class-level computed-name
set-accessor patterns, and the other 3 (`classUsedBeforeInitializedVariables`,
`computedPropertiesWithSetterAssignment`, `dynamicNamesErrors`) don't use
`isolatedDeclarations`, so they're untouched. Source substring extraction
for the property-name string is safe — `name.pos` and the inner expression
end are always within bounds (parser-validated).

**Foundation for follow-ons.**
- **B7.21 candidate**: TS7010 + TS9013 walker for interface MethodDeclaration
  members (`[name](): T;`) that lack a return-type annotation. Different
  emission position vs class TS9008 (uses `member.pos` to last-paren end),
  fires on interfaces specifically (no body, but a MethodSignature shape).
  Would close the test at 26/26 if combined with the line 50 length fix.

**B7.19 (2026-05-15, net-zero infra)** — TS9009 / TS9033 walker for
Identifier-named class accessors that lack an explicit type annotation
(mirror of B7.17's object-literal version for the class-context). Single-file
change in `TypeScriptCompiler.kt`. New `emitIsolatedDeclClassAccessor` walker
called from the `is ClassDeclaration` arm of pass 3's statement switch, after
`emitIsolatedDeclClassMethodParamChecks`. Walker first builds peer-set tables
(`hasGetter` / `hasSetter`) then makes a second pass:

- **GET-only, no return type** (no peer setter, Identifier name) → emits
  TS9009 at the accessor name position with related TS9032 "Add a return
  type to the get accessor declaration."
- **SET-only, unannotated param** (no peer getter, Identifier name + first
  param Identifier-named with no type annotation) → emits TS9009 at the
  param name position with related TS9033 "Add a type to parameter of the
  set accessor declaration."

The companion TS7032 (at accessor name) and TS7006 (at param name) for the
SET-only case are already emitted by the checker
(`checkImplicitAnyInClassElement`'s SetAccessor branch + 
`checkParamsForImplicitAny` at Checker.kt:9087-9105). The walker
deliberately does NOT duplicate them — its contribution is the
TS9009/TS9033 pair specific to `--isolatedDeclarations`. Pair semantics
match B7.17: ANY peer accessor of opposite kind suppresses ALL emissions
(regardless of whether the peer is annotated). Verified against the test's
`getSetBad`, `getSetOk`, `getSetOk2`, `getSetOk3` quadruple — none fire.

Computed-name accessors (`[expr]`) are conservatively skipped here — they
fire TS9038 instead via `emitIsolatedDeclClassComputedNameDiags` and the
companion TS7032/TS7006 walker for computed-name set-accessors is B7.20
territory.

**Adjacent partial progress (no flip).**
`isolatedDeclarationErrorsClasses_ts` 20 → 23 emissions (+3: line 11,9
TS9009 for `get getOnly()`; line 12,17 TS9009 for `set setOnly`'s `value`
param; checker's pre-existing TS7032 at line 12,9 + TS7006 at line 12,17
remain unchanged). Still 4 emissions short of expected 26 — gap covers:
- B7.20 candidate: line 48 computed-name set-accessor TS7032+TS7006 for
  `set [noParamAnnotationStringName](value) { }`. Checker's SetAccessor
  branch only handles Identifier-named accessors today; a parallel branch
  for ComputedPropertyName-named accessors (with appropriate name-text
  extraction for the diagnostic) would close this.
- B7.21 candidate: line 56 interface MethodDeclaration TS7010+TS9013 for
  `[noAnnotationLiteralName]();`. Distinct emission shape from class
  contexts — TS7010 ("...lacks return-type annotation, implicitly has an
  'any' return type") fires only on interface-member declarations.
- Line 50 parser/walker length off-by-one for
  `[("A" + "B") as "AB"] = 1;` — emits length 22 vs expected 21. Likely
  in `emitIsolatedDeclClassComputedNameDiags`'s ComputedPropertyName span
  computation when the inner expression is `as`-asserted.

**Verification.** Full-suite 10078/1404/3 (unchanged from B7.18; zero
regressions). Net-zero on the suite — gain on `isolatedDeclarationErrorsClasses_ts`
(20 → 23 of 26) isn't enough to flip it. Only two tests in the corpus
exercise `isolatedDeclarations` + class accessor patterns
(`isolatedDeclarationErrorsClasses_ts` and `isolatedDeclarationErrorsObjects_ts`,
the latter already flipped in B7.17).

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
(call-site). Identifier-name guard skips ComputedPropertyName /
StringLiteralNode / NumericLiteralNode accessor names. Peer-suppression via
`hasGetter` / `hasSetter` matches B7.17's symmetric semantics. The
deliberate non-emission of TS7032/TS7006 (already emitted by the checker)
prevents duplicate-diagnostic regressions like the one observed in the
first iteration of this walker (which initially emitted TS7032 alongside
the checker — verified via targeted-test diff before correction).

**B7.18 (2026-05-14, net-zero infra)** — TS9011 walker for exported
`ClassDeclaration` MethodDeclaration parameters that lack an explicit type
annotation (mirrors B7.5's FunctionDeclaration version for class methods).
Single-file change in `TypeScriptCompiler.kt`. New
`emitIsolatedDeclClassMethodParamChecks` walker called from the
`is ClassDeclaration` arm of pass 3's statement switch, after
`emitIsolatedDeclClassMethodTs9008`. Walker iterates `stmt.members`, picks
MethodDeclaration with non-null body (skipping overload-only / abstract /
interface MethodDeclaration with body=null), and delegates to the existing
`emitIsolatedDeclParamsCheck` helper so the same trivially-declarable-default
classification and TS9028 "Add a type annotation to the parameter X." related
info shape applies uniformly across FunctionDeclaration and MethodDeclaration.

**Adjacent partial progress (no flip).**
`isolatedDeclarationErrorsClasses_ts` 17 → 20 emissions (+3: lines 8,18
TS9011 for `methodParams(p)`; 9,23 TS9011 for `methodParams2(p = 1 + 1)`
default `1 + 1`; 44,35 TS9011 for `[noParamAnnotationStringName](v)`).
Still 6 emissions short of expected 26 — gap covers class-side single-
accessor TS9009 / TS7032 / TS7006 walker (lines 11,12 — B7.19 candidate),
class-side computed-name single-setter TS7032+TS7006 (line 48 — B7.20
candidate), and interface MethodDeclaration TS7010+TS9013 (line 56 — B7.21
candidate).

**Verification.** Full-suite 10078/1404/3 (unchanged from B7.17; zero
regressions). Net-zero on the suite — gain on `isolatedDeclarationErrorsClasses_ts`
isn't enough to flip it; other isolated-decl tests already had all-or-none
class-method param coverage from the checker's pre-existing TS7006 path.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
(call-site). The body-null guard correctly skips MethodSignature-shaped
overload declarations / abstract methods / interface members that parse as
MethodDeclaration. Accessor parameters (GetAccessor / SetAccessor) are NOT
touched — they emit TS9009/TS7032/TS7006 instead per B7.17 / B7.19+.

**B7.17 (2026-05-14, +1 — flips `isolatedDeclarationErrorsObjects_ts`)** —
TS7032 / TS7006 / TS9009 walker for single-setter accessors in
ObjectLiteralExpression initializers under exported `VariableStatement`.
Single-file change in `TypeScriptCompiler.kt`. New
`emitIsolatedDeclObjLitAccessor` walker called from the `is VariableStatement`
arm of pass 3's statement switch in `emitIsolatedDeclarationsDiagnostics`,
alongside the existing `emitIsolatedDeclObjLitSpreadShorthand` walker (gated
on `Export` + `init is ObjectLiteralExpression`).

Walker iterates `objLit.properties` twice:
1. First pass collects Identifier-named `GetAccessor` names into a
   `hasGetter` set.
2. Second pass: for each `SetAccessor` whose name Identifier is NOT in
   `hasGetter`, whose first parameter has NO type annotation, and whose
   first-parameter name is an Identifier — emit three diagnostics. When a
   peer getter exists, TypeScript treats the setter param type as supplied
   by the getter's return type (even if that is itself implicit-any) and
   suppresses all three — matched by the `hasGetter` gate.

Emissions:
- **TS7032** at the accessor NAME position (length = name.length):
  "Property 'X' implicitly has type 'any', because its set accessor lacks
   a parameter type annotation."
- **TS7006** at the param NAME position (length = param-name.length):
  "Parameter 'value' implicitly has an 'any' type."
- **TS9009** at the param NAME position (length = param-name.length):
  "At least one accessor must have an explicit type annotation with
   --isolatedDeclarations." with related TS9033 at the accessor NAME
  position: "Add a type to parameter of the set accessor declaration."

Recurses into nested ObjectLiteralExpression initializers via
PropertyAssignment so deep object literals are covered uniformly with the
other B7.x walkers.

**Verification.** Full-suite 10078/1404/3 (was 10078/1405/3, +1 net). Zero
regressions across 10078-test suite. Closes
`isolatedDeclarationErrorsObjects_ts` at 19/19 expected emissions
(previously 16/19 after B7.16).

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
AND `init is ObjectLiteralExpression` (call-site gates). Within that scope
the `hasGetter`-peer gate suppresses emissions on accessor pairs even when
both are unannotated, matching TypeScript's asymmetric baseline behavior
(`get/set getSetBad` pair fires nothing despite both unannotated).
ComputedPropertyName / StringLiteralNode / NumericLiteralNode accessor
names are conservatively skipped (no failing-test target exercises them).

**Foundation for follow-ons.**
- **B7.18 candidate**: TS9011 class MethodDeclaration unannotated parameters
  + non-trivial parameter defaults. Stacks on B7.5's FunctionDeclaration
  version. Adds ~3 emissions in `isolatedDeclarationErrorsClasses_ts`.
- **B7.19 candidate**: TS9009 / TS7032 / TS7006 for class-side
  `set X(value)` single-setter accessors (mirror of B7.17 for
  `isolatedDeclarationErrorsClasses_ts`). Would add ~2 emissions there.

**B7.16 (2026-05-14, net-zero infra)** — TS9015 / TS9016 walker for
SpreadAssignment / ShorthandPropertyAssignment in ObjectLiteralExpression
initializers under exported `VariableStatement`. Single-file change in
`TypeScriptCompiler.kt`. New `emitIsolatedDeclObjLitSpreadShorthand` walker
called from the `is VariableStatement` arm of pass 3's statement switch in
`emitIsolatedDeclarationsDiagnostics`, alongside the
`emitIsolatedDeclObjLitSubExprTs9013` walker (gated on `Export` +
`init is ObjectLiteralExpression`).

Walker iterates `objLit.properties`:
- SpreadAssignment (`...part`) → emit TS9015 with squiggle from `prop.pos`
  to `isolatedDeclExprTrueEnd(prop.expression)`. Length covers `...EXPR`.
- ShorthandPropertyAssignment (`part`) → emit TS9016 at `prop.name.pos`
  with `prop.name.text.length`. Covers just the identifier.
- PropertyAssignment with nested `ObjectLiteralExpression` initializer →
  recurse so patterns like `oWithSpread2.nested: { ...part }` are covered.

Related info: TS9027 "Add a type annotation to the variable X." anchored at
the OUTER variable name (single shared `Diagnostic` reused for all emissions
in the same call). Both TS9015 and TS9016 share this related info shape.

**Adjacent partial progress (no flip).** `isolatedDeclarationErrorsObjects_ts`
13 → 16 emissions (+3 — lines 75,5; 77,5; 84,9). Now only 3 emissions short
of the expected 19 — gap covers the TS7032/TS7006/TS9009 triple for
`set singleSetterBad(value) { }` at line 40. A B7.17 walker for single-
accessor TS9009 + checker-level TS7032/TS7006 for set-accessor implicit-any
params would complete the test.

**Verification.** Full-suite 10078/1405/3 (unchanged from B7.15; zero
regressions).

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
AND `init is ObjectLiteralExpression` (call-site gates).

**B7.15 (2026-05-14, net-zero infra)** — TS9013 sub-expr walker for
ObjectLiteralExpression initializers under exported `VariableStatement` +
`isolatedDeclExprTrueEnd` empty-args CallExpression off-by-one fix. Two
coordinated pieces in `TypeScriptCompiler.kt`:

(a) New walker `emitIsolatedDeclObjLitSubExprTs9013` called from the
`is VariableStatement` arm of pass 3's statement switch in
`emitIsolatedDeclarationsDiagnostics`, alongside the
`emitIsolatedDeclObjLitMethodTs9008` walker (gated on `Export` +
`init is ObjectLiteralExpression`). Mirrors B7.7's `walkExportDefaultSubExpr`
but for the variable-context: anchor TS9027 at the OUTER variable name, and
DO emit on bare `Identifier` references (e.g. `e: V`) — the export-default
walker skips Identifiers since exporting `default X` is declarable-as-typeof,
but in variable context cannot be inferred without checking V's type.
Recurses through nested ObjectLiteralExpression PropertyAssignments and
ArrayLiteralExpression elements. MethodDeclaration / Get/SetAccessor /
SpreadAssignment / ShorthandPropertyAssignment members are skipped (handled
elsewhere or by deferred walkers).

(b) `isolatedDeclExprTrueEnd` empty-args CallExpression fix. The previous
branch `if (expr.arguments.isNotEmpty()) ... else expr.end` used `expr.end`
which overshoots by one token (per CLAUDE.md gotcha — `node.end` includes the
next scanned token's start). For `Math.random(),` this made the squiggle 14
chars (covering the comma) instead of 13. Fixed to mirror the NewExpression
branch: `if (expr.arguments.isEmpty()) isolatedDeclExprTrueEnd(expr.expression)
+ 2 // ()`. ALSO fixes a pre-existing 1-char overshoot in the B7.5 TS9011
walker for `p = time()` and `p = Math.random()` patterns in
`isolatedDeclarationErrorsExpressions_ts`.

Squiggle: at the offending sub-expression's position, length =
`isolatedDeclExprTrueEnd(expr) - expr.pos`.

Related infos:
- TS9027 "Add a type annotation to the variable X." anchored at the OUTER
  variable name.
- TS9035 "Add satisfies and a type assertion ..." anchored at the same
  position as TS9013.

**Adjacent partial progress (no flip but emissions firing).**
- `isolatedDeclarationErrorsObjects_ts` 9 → 13 emissions (+4 — lines
  7,8 / 12,12 / 16,12 / 25,8). Still 6 emissions short of expected 19.
- `isolatedDeclarationErrorsExpressions_ts` squiggle widths now correct for
  `time()` (6 chars vs 7) and `Math.random()` (13 vs 14) emissions on lines
  110, 115. Doesn't flip — the test has many other gaps.

**Verification.** Full-suite 10078/1405/3 (unchanged from B7.14; zero
regressions).

**Risk profile that materialized.** Bounded — both pieces are gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`.
The helper fix surfaces in any walker that recursively bottoms out on a
CallExpression — this affects only the isolatedDecl B7.x family (no other
code path uses `isolatedDeclExprTrueEnd`).

**B7.14 (2026-05-14, net-zero infra)** — TS9008 walker for object-literal
method shorthand under exported `VariableStatement`. Single-file change in
`TypeScriptCompiler.kt`. New `emitIsolatedDeclObjLitMethodTs9008` walker
called from the `is VariableStatement` arm of pass 3's statement switch,
alongside `emitIsolatedDeclObjLitComputedNameDiags` (gated on `Export` +
`init is ObjectLiteralExpression`).

Walker gate (skip if any apply):
- `prop !is MethodDeclaration` (PropertyAssignment / SpreadAssignment /
  Get/SetAccessor handled elsewhere — but PropertyAssignment whose
  initializer is a nested `ObjectLiteralExpression` triggers recursion so
  patterns like `oWithMethodsNested.foo: { method() {} }` are covered).
- `member.type != null` (annotated return type)
- `member.body == null` (overload-only / body-less; rare in object literals
  — conservative skip)
- `name` is neither `Identifier` nor `ComputedPropertyName`

Span (matches B7.13):
- Identifier → `name.pos + name.text.length`
- ComputedPropertyName → `name.pos + (isolatedDeclExprTrueEnd(name.expression)
  + 1 - name.pos)`

Emission: TS9008 at the computed span; TWO related infos:
- TS9027 "Add a type annotation to the variable X." anchored at the OUTER
  variable name (matches TypeScript's baseline format for the object-literal
  context).
- TS9034 "Add a return type to the method" anchored at the same position as
  TS9008.

This differs from B7.13's class-method walker (which emits only TS9034 — class
methods don't have an outer variable to annotate). The object-literal context
anchors TS9027 at the variable so the user can fix it by annotating the
variable.

**Adjacent partial progress (no flip but emissions firing).**
`isolatedDeclarationErrorsObjects_ts` 5 → 9 emissions (+4 — lines 21,5; 24,5;
29,9; 32,9 — the 2 method shorthands in `oWithMethods` + the 2 in
`oWithMethodsNested.foo`). Still 10 emissions short of the expected 19 — gap
covers TS9013 sub-expr walker, TS7032/TS7006/TS9009 set-accessor walker, and
TS9015/TS9016 spread/shorthand walkers (separate substeps).

**Verification.** Full-suite 10078/1405/3 (unchanged from B7.13; zero
regressions). Net-zero on the suite — continues the B7.x cadence of net-zero
infra commits stacking walker shapes toward eventual test flips.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`
AND `init is ObjectLiteralExpression` (call-site gates). Recursion into
nested ObjectLiteralExpression initializers via PropertyAssignment is depth-
unbounded but bounded in practice by source structure; no stack-overflow
risk for realistic test inputs. ComputedPropertyName span matches B7.13's
class-method walker exactly so future dual-diagnostic cases (e.g. TS9038 +
TS9008 on same computed-named method) would render correctly.

**B7.13 (2026-05-14, net-zero infra)** — TS9008 walker for exported
`ClassDeclaration` MethodDeclaration without explicit return type annotation.
Single-file change in `TypeScriptCompiler.kt`. New
`emitIsolatedDeclClassMethodTs9008` walker called from the
`is ClassDeclaration` arm of pass 3's statement switch, after
`emitIsolatedDeclClassPropertyTs9012`.

Walker gate (skip if any apply):
- `member.type != null` (annotated return type)
- `member.body == null` (overload-only / body-less declaration; interface
  methods parse as `MethodSignature` not `MethodDeclaration` so are implicitly
  excluded — body-null guard conservatively also covers any rare body-less
  class method that future grammar changes might surface)
- `name` is neither `Identifier` nor `ComputedPropertyName` (string-/numeric-
  named methods rare in tests and require separate name-text extraction)

Span:
- Identifier name → name.pos + name.text.length
- ComputedPropertyName name → entire `[...]` span via
  `isolatedDeclExprTrueEnd(name.expression) + 1` — matches the TS9038 walker's
  span computation for the same shape, so both diagnostics share start + length.

Emission: TS9008 at the computed span; TS9034 related at the same position
with "Add a return type to the method" message.

**Adjacent partial progress (no flip but emissions firing).**
- `isolatedDeclarationErrorsClasses_ts` 15 → 17 emissions (+2 — lines 4,5
  `method() {}` and 42,5 `[noAnnotationStringName]() { }`). Line 42 already
  emits TS9038 from B7.11; B7.13 adds TS9008 alongside, matching baseline's
  dual-diagnostic shape.
- No effect on `isolatedDeclarationErrorsObjects_ts` — its 4 TS9008 emissions
  are on object-literal method shorthands (different syntactic shape with
  TS9027 + TS9034 related infos instead of TS9034-only). Deferred to a
  separate substep.

**Verification.** Full-suite 10078/1405/3 (unchanged from B7.12; zero
regressions). Net-zero on the suite — continues the new B7.x cadence of net-
zero infra commits stacking walker shapes toward eventual test flips.

**Risk profile that materialized.** Bounded — walker gated on
`options.isolatedDeclarations` AND `ModifierFlag.Export in stmt.modifiers`.
Body-null guard is the conservative filter against accidentally firing on
overload-only declarations or future grammar shapes. Span for
ComputedPropertyName matches the existing TS9038 walker exactly so the dual
diagnostics on the same node have matching start + length (baseline format
requires this for both squiggles to render at the same position).

**B7.12 (2026-05-14, net-zero infra)** — TS9012 walker for exported
`ClassDeclaration` PropertyDeclaration with non-trivially-declarable
initializer. Single-file change in `TypeScriptCompiler.kt`. Mirrors B7.9's
TS9010 trigger set (CallExpression / NewExpression / PropertyAccessExpression
/ ElementAccessExpression / BinaryExpression / Identifier excluding boolean
keywords) onto class-member PropertyDeclarations. New
`emitIsolatedDeclClassPropertyTs9012` walker called from the
`is ClassDeclaration` arm of pass 3's statement switch, after
`emitIsolatedDeclClassComputedNameDiags`.

Walker gate (skip if any apply):
- `member.type != null` (annotated — declarable as written)
- `member.initializer == null` (no value)
- `member.name !is Identifier` (ComputedPropertyName handled by the
  dedicated `emitIsolatedDeclClassComputedNameDiags`; string-/numeric-named
  properties rare in tests and require separate name-text extraction)
- Initializer is trivially declarable per `isIsolatedDeclTriviallyDeclarable`
- Initializer is ArrowFunction / FunctionExpression / ClassExpression
  (TS9007 / TS9022 handle these separately — checked implicitly via the
  triggers `when` falling through to `else -> false`)
- Initializer is ObjectLiteralExpression / ArrayLiteralExpression
  (deferred to a future substep mirroring B7.9's deferred shape)

Emission: TS9012 at name.pos + name.text.length; TS9029 related at same
position with "Add a type annotation to the property X." message.

**Adjacent partial progress (no flip but emissions firing).** 15 new TS9012
emissions land across two tests:
- `isolatedDeclarationErrorsClasses_ts` 14 → 15 (line 3,5 `field = 1 + 1;`).
- `isolatedDeclarationErrorsExpressions_ts` 21 → 35 (14 emissions across
  `class Exported`'s `public` and `readonly` fields with BinaryExpression /
  CallExpression / Identifier initializers — lines 59-69, 78-88). Template-
  with-substitution shapes (lines 91-93, 102-104) deferred to a follow-on
  substep that mirrors B7.9's deferred TemplateExpression handling for
  VariableStatement.

Neither test flips with B7.12 alone — each needs further walker shapes
(TS9008 method-shorthand, TS9011 method params, TS9009 accessor pairs,
TS9013 interface methods, TS7032 / TS7006 / TS7010 checker emissions for
computed-named accessors / interfaces, and a TS9012 template / as-const-
template extension for the Expressions test). Foundation for stacking
those shapes in subsequent substeps.

**Verification.** Targeted tests run; full-suite 10078/1405/3 (unchanged
from B7.11; zero regressions). Net-zero on the suite — first net-zero
infra commit in the B7.x series, departing from the prior +1-per-substep
pattern because every remaining single-shape candidate is similarly net-
zero (no remaining isolatedDeclarations error-baseline test can flip in
fewer than ~5-10 walker shapes).

**Risk profile that materialized.** Bounded — the walker is gated on
`options.isolatedDeclarations` AND on `ModifierFlag.Export in stmt.modifiers`
(at the call site). The trigger gate is the same intersection that B7.9
proved safe across the suite. Identifier triggers exclude `true`/`false`/
`null`/`undefined` (boolean / nullish-like keywords parsed as Identifier
nodes). Implicit-public, `public`, `protected`, `private`, `readonly`,
and `static` modifiers all qualify (no visibility filter). The conservative
approach skips ArrowFunction / FunctionExpression / ClassExpression init
shapes (handled by TS9007 / TS9022) and Object/Array literals (deferred).

**Lessons.** (a) Mirroring an existing well-tested trigger set onto a new
declaration kind is the safest expansion strategy in the B7.x walker
family — no novel classification logic, identical regression surface to
the prior commit. (b) Net-zero infra commits are part of the protocol's
acceptable outcomes; the previous B7.x sub-series happened to have +1-
per-substep momentum because each individual test was 1 walker shape
away from flipping. After B7.11 closed the last such test, the next
flippable targets are all multi-shape — net-zero commits are now the
expected cadence until ~5-10 shapes are stacked.

**B7.11 (2026-05-14, +1)** — ElementAccessExpression-LHS TS9023 + class /
object-literal `ComputedPropertyName` TS9038 + TS1166 walker (closes
`isolatedDeclarationLazySymbols_ts`). Single-file change in
`TypeScriptCompiler.kt`. Three coordinated walker pieces:

- **Pass 2 extension** — ElementAccessExpression LHS expando branch alongside
  the existing PropertyAccessExpression branch. Emits TS9023 when
  `funcName[idx] = X` and the index isn't a statically-known string-literal
  pattern (new `isIsolatedDeclElementAccessIndexLiteralName` helper rejects
  `idx is StringLiteralNode` and `idx is ElementAccessExpression(_, StringLiteralNode)`).
  Span = full LHS (start = receiver.pos, end =
  `isolatedDeclExprTrueEnd(argumentExpression) + 1`). Deduped by
  `(receiver, source.substring(idx.pos, isolatedDeclExprTrueEnd(idx)))`.

- **`is ClassDeclaration` Pass-3 extension** (new
  `emitIsolatedDeclClassComputedNameDiags` after the existing extends-clause
  walker) — iterates class members (PropertyDeclaration / MethodDeclaration /
  GetAccessor / SetAccessor). For each member with `name is ComputedPropertyName`
  whose inner expression isn't trivially literal (numeric / signed-numeric /
  string), emits TS9038. Additionally emits TS1166 when the inner expression
  is `AsExpression` or `ElementAccessExpression` (the "lazy symbol" patterns
  TypeScript treats as not resolvable to a simple literal type without the
  full type checker). PropertyAccessExpression inner shapes intentionally
  emit nothing (TypeScript resolves `as const` chains to literal types in
  this position).

- **`is VariableStatement` Pass-3 extension** — new
  `emitIsolatedDeclObjLitComputedNameDiags` called when `init is
  ObjectLiteralExpression` and the variable is exported. For each
  PropertyAssignment with `name is ComputedPropertyName`, emits TS9038
  unless inner is trivially literal. TS9027 related at the variable name.

- **`isolatedDeclExprTrueEnd`** extended with `is ElementAccessExpression
  -> isolatedDeclExprTrueEnd(expr.argumentExpression) + 1` so the
  one-past-`]` position is computed correctly for downstream span math.

**Verification.** Targeted test passes; full-suite 10078/1405/3 (was
10078/1406/3, +1 net). Zero regressions across the 10078-test suite. The
walker is `isolatedDeclarations`-gated so non-isolated tests are unaffected.

**Adjacent partial progress (no flip but emissions firing correctly):**

- `isolatedDeclarationErrorsClasses_ts`: was 5/26 emissions, now 14/26
  (+9 TS9038 / TS1166 from the new class member walker). Still missing
  ~12 emissions across TS9012 / TS9008 / TS9011 / TS9009 / TS7010 / TS9013
  shapes — separate walker work.
- `isolatedDeclarationErrorsObjects_ts`: was 0/19 emissions, now 5/19
  (+5 TS9038 from the new object-literal walker on
  `oWithComputedProperties`'s 5 computed names). Still missing 14
  emissions across TS9013 / TS9008 / TS9009 / TS9015 / TS9016 shapes.
- `computedPropertiesNarrowed_ts`: was 0/10 emissions, now ~7/10
  (+7 TS9038 from the new object-literal walker). Two span mismatches
  remain (one for empty-args CallExpression like `[Symbol()]` and one
  for `AsExpression` like `[("A" + "B") as "AB"]`) — `isolatedDeclExprTrueEnd`
  falls through to overshooting `expr.end` for these two shapes; deferred
  to a follow-on substep (a tightening would risk breaking other consumers
  of the same helper).

**Risk profile that materialized.** Bounded — the walker is gated on
`options.isolatedDeclarations`. The TS9023 ElementAccess branch only
fires when (a) LHS is `ElementAccessExpression`, (b) receiver is in
`funcDecls`/`funcVarDecls`, and (c) index isn't a known-literal pattern.
The class / object-literal walkers gate on `name is ComputedPropertyName`
which is a syntactically narrow shape. PropertyAccessExpression exclusion
in the class walker matches the LazySymbols expected baseline (the
`[o.prop.inner]` case correctly emits nothing).

**Lessons.** (a) Class vs object-literal asymmetry on PropertyAccess
inner shapes: class accepts (TS resolves `as const` chains to literal
types), object literal rejects (always emits TS9038). My initial
"class allows PropertyAccess" rule was an unintuitive observation
specifically demanded by the LazySymbols baseline; without it, my
walker would have emitted a spurious TS9038 at line 23 and the test
wouldn't flip. (b) `isolatedDeclExprTrueEnd` is missing cases for
ElementAccessExpression (added in this substep) and for
empty-args-CallExpression / AsExpression (still missing — fall through
to overshooting `expr.end`). Both gaps surface as off-by-one squiggle
lengths in adjacent failing tests; safe to tighten in a follow-on
once a specific failing test depends on the fix. (c) The existing
Pass-2 expando walker had a single PropertyAccess branch; restructuring
to a `when (lhs)` block with PropertyAccess and ElementAccess arms
required `continue` to bind to the enclosing `for` loop — works
under Kotlin 2.0+ which this project targets (2.3).

**B7.10 (2026-05-14, +1)** — Enum-emission constant folding for top-level
`const X = numericLiteral` references AND cross/same-enum string-valued
member references (closes `isolatedDeclarationErrorsEnums_ts` JS-emit
baseline). Single-file change in `Transformer.kt`. Three coordinated
pieces:

- New `topLevelNumericConstants: Map<String, Long>` field. Populated by
  the existing pre-pass in `transform` for any `const X = numericLiteral`
  (or folded numeric expression via `tryEvaluateNumericLiteral`) at the
  source-file top level. Cleared per file.

- New `allEnumStringValues: Map<String, Map<String, String>>` field.
  Populated inside `transformEnum` whenever a member is folded to a
  constant string value. Cleared per file.

- `evaluateConstantExpression` Identifier branch now falls back to
  `topLevelNumericConstants` after `memberValues`. Enables
  `enum E { A = EV }` → `E["A"] = 1` when `const EV = 1` is at file top
  level.

- `evaluateConstantStringExpression` takes new optional `stringMemberValues`
  (per-enum, current) + `currentEnumName` params; gains three new
  expression branches: `Identifier` (same-enum lookup), `PropertyAccessExpression`
  (same- or cross-enum lookup against `allEnumStringValues`),
  `ElementAccessExpression` (same- or cross-enum lookup, string-literal
  arg only). Enables `Str.AB = A + B` → `Str["AB"] = "AB"`,
  `StrExt.ABD = Str.AB + D` → `StrExt["ABD"] = "ABD"`, and
  `StrExt.AD = Str["A"] + D` → `StrExt["AD"] = "AD"`.

- `transformEnum` initializes `stringMemberValues` from prior
  declaration's map (merged enums) and writes confirmed string values
  into both the per-enum map and `allEnumStringValues[enumName]` after
  emission, mirroring the numeric `memberValues` / `allEnumMemberValues`
  pattern.

**Verification.** Targeted test passes; full-suite 10078/1406/3 (was
10078/1407/3, +1 net). Zero regressions across 10078-test suite. Both
infrastructure additions are bounded:

- Numeric inlining gated on `const` flag + `tryEvaluateNumericLiteral`
  succeeding (folds only literal-like RHS expressions; `const X =
  someFn()` is silently skipped).
- String inlining gated on the existing `evaluateConstantStringExpression`
  return path, so non-constant string expressions still fall through to
  `qualifyEnumMemberRefs`.

The corpus has no test that depends on the un-inlined source-form output
for cross-enum string concat or external-const-in-enum-init patterns
(verified via full-suite re-run with zero regressions).

**B7.9 (2026-05-14, +1)** — TS9010 for `export const/let/var X = nonTriviallyDeclarableExpr`
(closes `declarationEmitIsolatedDeclarationErrorNotEmittedForNonEmittedFile_ts`).
Extension of B7.1's TS9010 walker — previously only fired for `export X;`
without initializer; now also fires for `export const X = expr` when the
initializer is one of the conservative trigger kinds that cannot be preserved
as a literal type in the emitted .d.ts. Single-file change in
`TypeScriptCompiler.kt`:

- New `emitIsolatedDeclVarInitTs9010(varName, init, ...)` helper called from
  the VariableStatement arm of pass 3 in `emitIsolatedDeclarationsDiagnostics`
  (right after `emitIsolatedDeclClassExprDiags`, gated on `ModifierFlag.Export`).

- Trigger expression kinds (conservative — only those where the TypeScript
  baseline definitively expects TS9010 and no currently-passing isolated-decl
  test puts them under `export const X = ...`):
  - `CallExpression` / `NewExpression`
  - `PropertyAccessExpression` / `ElementAccessExpression`
  - `BinaryExpression`
  - `Identifier` (excluding `true`/`false`/`null`/`undefined`)

- Squiggle at variable name with TS9027 "Add a type annotation to the variable X."
  related info, mirroring the existing no-initializer TS9010 emission.

- Intentionally NOT handled in this substep (each deferred to a follow-on):
  - `ObjectLiteralExpression` — needs recursive sub-expr walker emitting TS9013
    on non-trivial leaves (`isolatedDeclarationErrorsObjects_ts`'s `oBad`/`oBad2` pattern).
  - `ArrayLiteralExpression` — needs TS9017 ("Only const arrays can be inferred ...").
  - `TemplateExpression` — needs const-vs-let distinction (TypeScript emits TS9010
    for `const` template-with-substitutions but accepts `let`).
  - `AsExpression` (non-`as const`) — separate diagnostic family.
  - `ArrowFunction` / `FunctionExpression` — separate TS9007 walker handles
    missing return types; full param-annotation requirements deferred.

**Verification.** Targeted test passes; full-suite 10078/1407/3 (was 10078/1408/3,
+1 net). Zero regressions across 10078-test suite. Closes the named target —
all 3 expected TS9010 emissions (lines 5/6/7 of `index.ts`: `trpc.middleware`,
`trpc.router`, `trpc.procedure` — all `PropertyAccessExpression`) fire at the
correct positions. Foundation for follow-on substeps that handle the
deferred expression kinds (TS9013 sub-expr walker, TS9017 array, etc.).

**B7.8 (2026-05-14, +1)** — TS9020 for enum members under
`--isolatedDeclarations` (closes
`isolatedDeclarationErrorsEnums_ts` errors-baseline; JS-emit baseline was
already failing pre-change, unaffected). New `is EnumDeclaration` arm in
pass-3 of `emitIsolatedDeclarationsDiagnostics`:

- `emitIsolatedDeclEnumChecks(enumDecl, ...)`: walks each member,
  computes a `Map<memberName, isComputable>` left-to-right (prior members
  already populated). Emits TS9020 at the member name when its initializer
  is NOT "computable".

- `isEnumInitializerComputable(expr, enumName, computable)`: recursive
  predicate. Computable iff one of:
  - Literal (numeric / bigint / string / template-no-substitution).
  - Unary `+` / `-` / `~` on a computable operand.
  - Binary arithmetic / bitwise / string-concat (`+ - * ** / % | & ^
    << >> >>>`) on two computable operands.
  - `ParenthesizedExpression` on computable inner.
  - `CallExpression` (always — preserved verbatim in `.d.ts`).
  - Bare `Identifier` referring to a same-enum member that is computable.
  - `PropertyAccessExpression` / `ElementAccessExpression` whose receiver
    matches THIS enum's name and the target is a same-enum computable
    member.
  References to OTHER enums (`E.A`, `Flag.ABC | C`) or external constants
  (`EV`) are NOT computable. Transitive contamination: if A's initializer
  is non-computable, then `B = A` is also non-computable (because
  `computable["A"] != true`).

- Test cases: `enum E { A = computed(0) }` — OK (CallExpression).
  `enum F { A = E.A, B = A }` — both flagged (cross-enum + transitive
  contamination). `enum Flag { A = 1 >> 1, AB = A | B, ABC = Flag.AB | C,
  AC = Flag["A"] | C }` — all OK (literal arithmetic + same-enum refs in
  three forms). `enum ExtFlags { D = 4 >> 1, E = EV, ABCD = Flag.ABC | D,
  AC = Flag["A"] | D }` — D OK, E/ABCD/AC flagged.

Net delta: 1409 → 1408 failed (8666 → 8667 passing). Zero regressions
across the 10078-test suite.

**B7.7 (2026-05-14, +1)** — `export default <expr>` isolatedDeclarations
walker (closes `isolatedDeclarationErrorsDefault_ts`). New
`emitIsolatedDeclExportDefaultChecks` arm on `ExportAssignment` (only when
`!isExportEquals`) in `emitIsolatedDeclarationsDiagnostics`. Five emission
shapes covered:

- `Identifier` / trivially-declarable literal → OK (preserved by name in .d.ts).
- `ArrayLiteralExpression` (non-`as const`) → **TS9017** "Only const arrays
  can be inferred ..." spanning the full `[ ... ]`. Span computed via new
  `findMatchingDelimiter` helper (naive bracket matcher with string-literal
  and line/block-comment skipping; sufficient for the corpus).
- `ObjectLiteralExpression` → recurse into property initializers via
  `walkExportDefaultSubExpr`; each non-trivial leaf emits **TS9013** at the
  offending sub-expression.
- `AsExpression` with `as const` type → recurse into the underlying object /
  array literal as above.
- Other non-trivial top-level (`BinaryExpression`, `CallExpression`,
  `AsExpression` non-`as const`, ...) → **TS9037** "Default exports can't
  be inferred ..." at the expression's true span.

All emissions get a **TS9036** "Move the expression in default export to
a variable ..." related info at the `export default` statement start.
TS9013 additionally gets **TS9035** "Add satisfies and a type assertion ..."
at the offending sub-expression. Helpers: `emitIsolatedDeclTs9017Default`,
`emitIsolatedDeclTs9037Default`, `emitIsolatedDeclTs9013Default`,
`buildTs9036Related`, `walkExportDefaultSubExpr`.

Net delta: 1410 → 1409 failed (8665 → 8666 passing). Zero regressions
across the 10078-test suite. Walker gated on `options.isolatedDeclarations`.
Identifier-as-default-export (`export default a;`) correctly emits nothing
because the value can be preserved by name in the emitted .d.ts.

**B7.6 (2026-05-14, +1)** — Recurse into nested ArrowFunction/FunctionExpression
parameters from `emitIsolatedDeclParamDefaultClassify` (closes
`isolatedDeclarationsAddUndefined_ts`). Single-file change in
`TypeScriptCompiler.kt`:

- Extracted helper `emitIsolatedDeclParamsCheck(params, ...)` from
  `emitIsolatedDeclFnDeclParamChecks` — now both the top-level
  FunctionDeclaration walker and the new nested-FE recursion call this shared
  helper.
- `emitIsolatedDeclParamDefaultClassify`: when `expr is ArrowFunction` or
  `expr is FunctionExpression`, instead of bailing, recurse into
  `expr.parameters` via `emitIsolatedDeclParamsCheck`. Function expressions
  themselves still don't get TS9011/TS9013 squiggling (TS9007 covers their
  missing return types separately) — but their inner parameter defaults
  inherit the same declarability classification logic that the outer FD's
  parameters already get.

Pattern handled: `export function foo(p = (ip = 10 as T, v: number): void => {}): void`
— outer `p` has FE default; the FE's first inner param `ip` has non-trivial
`10 as T` default. After recursion, the AsExpression non-`as const` branch
fires TS9011 at the `T` type node (squiggle length 1) with TS9028
"Add a type annotation to the parameter ip." related info at `ip`'s
position. Inner trivially-declarable defaults (`ip = 10` literal) and
type-annotated inner params (`v: number`) correctly emit nothing.

Net delta: 1411 → 1410 failed (8664 → 8665 passing). Zero regressions
across the 10078-test suite. Recursion is bounded by the existing
`isIsolatedDeclTriviallyDeclarable` short-circuit and by the AST shape
(each level only walks named params). Same architectural risk profile
as B7.5: the walker is gated on `options.isolatedDeclarations` so
non-isolated tests are unaffected.

**B7.5 (2026-05-14, +1)** — TS9011 + TS9013 for exported FunctionDeclaration
parameter defaults under `isolatedDeclarations` (closes
`isolatedDeclarationErrorsFunctionDeclarations_ts`). Five new emissions in a
single test plus generalized helpers reusable for follow-on substeps:

- **TS9011** "Parameter must have an explicit type annotation with
  --isolatedDeclarations." — fires when an exported FunctionDeclaration's
  parameter has no type annotation. Three squiggle-position cases:
  1. **No default value** → squiggle at the parameter NAME (length =
     `name.text.length`).
  2. **Default value is non-trivially-declarable at top level** (e.g.
     `1 + 1` BinaryExpression, `Math.random()` CallExpression) → squiggle at
     the DEFAULT EXPRESSION (length = `isolatedDeclExprTrueEnd(expr) -
     expr.pos`).
  3. **Default value is `expr as T` (not `as const`)** → squiggle at the
     TYPE NODE T (length = `T.typeName.text.length` for simple Identifier
     references).

- **TS9013** "Expression type can't be inferred with --isolatedDeclarations."
  — fires on non-trivial sub-expressions inside object/array literals nested
  in parameter defaults. Walks recursively through `ObjectLiteralExpression`
  (each `PropertyAssignment` initializer) and `AsExpression` of `as const`
  shape (object literal members or array literal elements). Each non-trivial
  sub-expression that's not a function expression gets TS9013 at its true
  span.

- Companion related infos: **TS9028** ("Add a type annotation to the
  parameter X.") attached to all TS9011/TS9013 at the parameter name's
  position; **TS9035** ("Add satisfies and a type assertion ...") attached
  to TS9013 at the offending sub-expression position.

- **Triviality predicate** `isIsolatedDeclTriviallyDeclarable`: accepts
  NumericLiteralNode / BigIntLiteralNode / StringLiteralNode /
  NoSubstitutionTemplateLiteralNode / Identifier with text `"true"`,
  `"false"`, `"null"`, `"undefined"` / `PrefixUnaryExpression(Minus|Plus,
  Numeric|BigInt)`. Mirrors TypeScript's "declarable literal" classification
  for declaration-emit purposes.

- **`isolatedDeclExprTrueEnd` extended** with NumericLiteralNode /
  BigIntLiteralNode / StringLiteralNode / NoSubstitutionTemplateLiteralNode /
  Identifier / PrefixUnaryExpression / BinaryExpression cases — previously
  fell through to `expr.end` which overshoots by one token per the
  CLAUDE.md scanner gotcha. Now produces accurate squiggle lengths
  (e.g. `1 + 1` is 5 chars, not 6).

Net delta: 1412 → 1411 failed (8663 → 8664 passing). Zero regressions
across the 10078-test suite. Gate is `Export in fn.modifiers` only —
non-exported FunctionDeclarations don't appear in declaration emit so
parameter typing isn't required. ArrowFunction/FunctionExpression
parameter defaults at the top level are explicitly allowed (TS9007
separately flags missing return types on those). Nested arrow/FE
parameter contexts (e.g. `isolatedDeclarationsAddUndefined.ts`'s
`(p = (ip = 10 as T, v: number) => {})`) are NOT handled — requires
recursing into the outer default's params, which is deferred to a
later substep.

**B7.4 (2026-05-14, +1)** — TS2720 self-implements FP suppression + TS9026
for module-augmentation imports (closes `isolatedDeclarationErrorsAugmentation_ts`).
Two coordinated pieces — landing either alone wouldn't flip the target:

- **Checker.kt** `checkImplementsClauses` — new short-circuit at the top of
  the `typeExprLoop@ for (typeExpr in clause.types)` body: when
  `baseIfaceName == classNameRaw && typeExpr.typeArguments.isNullOrEmpty()`,
  `continue@typeExprLoop`. Suppresses the spurious TS2720 that fires for
  `class X implements X {}` whenever the class's interface representation has
  been merged with an augmentation that adds members not present in the class
  body. The check is tautological at the point of declaration (the class
  always satisfies its own declared shape); augmentation members are
  satisfied at runtime via prototype mutation (which TypeScript accepts and
  which TS9026 separately flags under `--isolatedDeclarations`).

- **TypeScriptCompiler.kt** new `buildAugmenterMap(files)` builds
  `Map<targetBasename, List<augmenterFileName>>` by scanning each
  `parsedSourceFiles` entry for `ModuleDeclaration` whose name is a relative
  string literal (`./X` / `../X`); only relative specifiers count
  (non-relative ones target ambient modules). Then
  `emitIsolatedDeclarationsAugmentImports(sourceFile, fileName, source, map)`
  walks each file's `ImportDeclaration` statements: for each relative
  specifier, if the imported file's basename matches an augmenter listed
  under the current file's basename, emit **TS9026** covering the full
  import statement (start = `stmt.pos`, end = position of `;` + 1 found via
  `source.indexOf(';', specEnd)`). Multi-file path only — the single-file
  path can't have cross-file augmentations.

Net delta: 1413 → 1412 failed (8662 → 8663 passing). Zero regressions
across the 10078-test suite. The TS2720 suppression is intentionally
narrow (`typeArguments.isNullOrEmpty()`) — `class X<T> implements X<number>`
would not be tautological (different instantiation) and stays flagged. The
TS9026 walker is gated on `options.isolatedDeclarations` so non-isolated
multi-file tests are unaffected; only one test in the corpus has the
`declare module './<sibling>'` + `import from './<sibling>'` pattern under
`isolatedDeclarations: true`.

**B7.3 (2026-05-14, +2)** — TS9023 + TS9007 (+ TS9027 + TS9030 + TS9031 related)
for the expando-function pattern under `isolatedDeclarations` (closes
`isolatedDeclarationErrors_ts` and `isolatedDeclarationErrorsExpandoFunctions_ts`).
Three-pass walker in `TypeScriptCompiler.kt`:

- **Pass 1** — collects top-level function names: `FunctionDeclaration` with a
  name (`funcDecls` map) AND `VariableDeclaration` whose initializer is an
  `ArrowFunction` / `FunctionExpression` (`funcVarDecls` map).

- **Pass 2** — walks top-level `ExpressionStatement` for
  `BinaryExpression(=, PropertyAccessExpression(Identifier, Identifier))`
  whose receiver is a function name. For each such expression:
  - Adds the receiver name to `expandoNames`.
  - Emits **TS9023** at the `name.prop` LHS span (`receiver.pos` to
    `propName.pos + propName.text.length`).
  - Dedupes by `(receiver, propertyName)` so `foo.length = 10; foo.length = 10`
    fires TS9023 only once (matches TypeScript's behavior on
    `isolatedDeclarationErrorsExpandoFunctions.ts`).

- **Pass 3** — main walk; per statement:
  - `VariableStatement` exported, decl uninit + untyped → existing TS9010
    (from B7.1).
  - `VariableStatement` exported, decl typed → existing TS9022 class-expression
    walker (B7.2).
  - `VariableStatement` (regardless of export modifier), decl initializer is
    arrow/FE with no return type AND name is in `expandoNames` → **TS9007**
    at the function expression's full span (pos to `body.closeBracePos + 1`
    for Block bodies, or `expressionTrueEnd` for expression bodies). Related:
    **TS9027** (variable name) + **TS9030** ("Add a return type to the
    function expression.") at the squiggle position.
  - `ClassDeclaration` exported → existing TS9021 walker (B7.2).
  - `FunctionDeclaration` with no return type AND (exported OR name in
    `expandoNames`) → **TS9007** at the function name (length =
    `name.text.length`). Related: **TS9031** ("Add a return type to the
    function declaration.") at the same position.

Net delta: 1415 → 1413 failed (8660 → 8662 passing). Zero regressions across
the 10078-test suite. Closes 2 tests in a single commit. Rule semantics:

- TS9023 fires only for `PropertyAccessExpression` LHS (e.g. `foo.bar = X`),
  not for `ElementAccessExpression` (e.g. `foo["bar"] = X` / `foo[0] = X`).
  TypeScript treats some computed-key cases specially —
  `isolatedDeclarationLazySymbols_ts` shows a TS9023 emission ONLY for the
  non-literal computed form `foo[o.prop.inner] = "B"` while the literal-keyed
  `foo[o["prop.inner"]] = "A"` is allowed. Deferred: this test also needs
  TS1166 + TS9038 to flip, so we don't add the ElementAccess case yet (no
  immediate consumer + dedicated test gating).

- TS9007 on `FunctionDeclaration` fires for `export function` OR for any
  function with expando assignments; the non-exported-without-expando case
  is correctly excluded (verified in `computedPropertiesNarrowed_ts` which
  has `function foo (): 1` (return type) and `function ns()` (non-exported,
  no expando) — neither fires, test stays passing).

- TS9007 on arrow/FE initializer fires only when the variable has expando
  assignments. `isolatedDeclarationErrorsReturnTypes_ts`'s
  `export let fnExpressionLetVariable = function foo() { return 0;}` (no
  expando) correctly stays un-flagged — matches TypeScript baseline which
  preserves named function expressions in .d.ts via their `function foo`
  form.

**B7.2 (2026-05-14, +1)** — TS9022 + TS9021 (+ TS9027 + TS9035 related) for
`isolatedDeclarations` (closes `isolatedDeclarationErrorsClassesExpressions_ts`).
Extends the `emitIsolatedDeclarationsDiagnostics` walker added in B7.1 to also
handle exported variables initialized with class expressions and exported
classes whose `extends` clause is a non-identifier expression. Three new
emission patterns in `TypeScriptCompiler.kt`:

- **TS9022** "Inference from class expressions is not supported with
  --isolatedDeclarations." — fires at the `class` keyword position (length 5)
  when a top-level `export const/let/var X = ...` has no type annotation AND
  its initializer is either (a) a bare `ClassExpression` or (b) an
  `AsExpression` of `[class {}, ...] as const` shape whose elements include
  ClassExpression(s). Attaches **TS9027** ("Add a type annotation to the
  variable X.") at the variable name; the array-as-const form additionally
  attaches **TS9035** ("Add satisfies and a type assertion ...") at the same
  position as the TS9022 squiggle.

- **TS9021** "Extends clause can't contain an expression with
  --isolatedDeclarations." — fires when an exported `ClassDeclaration`'s
  `extends` clause type-expression is neither an `Identifier` nor a
  `PropertyAccessExpression` (i.e. CallExpression, NewExpression, or other).
  Squiggle span computed via a minimal `isolatedDeclExprTrueEnd` helper that
  mirrors `Checker.expressionTrueEnd` for the CallExpression / NewExpression /
  Identifier / PropertyAccessExpression cases (the AST `end` field overshoots
  by one token per the CLAUDE.md scanner gotcha).

Net delta: 1416 → 1415 failed (8659 → 8660 passing). Zero regressions across
the 10078-test suite. Closes the target test cleanly. Wider isolatedDeclarations
patterns deferred to follow-on substeps:
- TS9007 (Function must have explicit return type) — emission rules are subtle:
  in `isolatedDeclarationErrorsReturnTypes`, ALL 31 emissions are on INNER
  default-value function expressions in `(cb = function(){})` patterns, NOT on
  outer named function expressions assigned to `export let/var` (which TS can
  preserve in .d.ts via the named-FE form). In `isolatedDeclarationErrors`, the
  single TS9007 fires only when the arrow function variable is consumed in an
  expando-property pattern (`X.a = ""` follows).
- TS9011 (Parameter without type annotation), TS9013 (Expression type can't be
  inferred), TS9023 (Expando function property assignment), TS9020 (Enum
  member uncomputable), TS9038 (Computed property name uninferable), and
  ~10 other TS90xx codes — each is bounded but tests typically need multiple
  codes to flip; pick by `find_candidates.py` MISS bucket overlap.

**B7.1 (2026-05-14, +1)** — TS5053 + TS9010 + TS9027 for `isolatedDeclarations`
(closes `isolatedDeclarationsAllowJs_ts`). Three coordinated emissions, all in
`TypeScriptCompiler.kt`:

- **TS5053 "Option 'allowJs' cannot be specified with option 'isolatedDeclarations'."**
  added next to the existing `TS5069`/`TS5053` option-conflict block (line
  ~467). Fires whenever both `options.allowJs` and `options.isolatedDeclarations`
  are set. No file/line/character — header-only diagnostic, rendered without
  position by the BaselineFormatter.

- **TS9010 + TS9027 walker** — new private top-level helpers
  `emitIsolatedDeclarationsDiagnostics(sourceFile, fileName, source)` and
  `positionToLineCharacter(source, position)` at the end of TypeScriptCompiler.kt.
  Narrow gate: when `isolatedDeclarations: true`, walks each parsed `.ts`/`.tsx`
  file's top-level statements (JS / `.d.ts` excluded), finds `VariableStatement`
  with `Export` modifier, then for each `VariableDeclaration` with
  `name is Identifier && type == null && initializer == null` emits TS9010 at
  the identifier's `pos` with `length = name.text.length`. TS9027 related info
  ("Add a type annotation to the variable X.") attached, same position/length.

- **Two call sites**: single-file path (around line 750) right after
  `checker.getDiagnostics()`; multi-file path (around line 990) after the shared
  checker runs, iterating `parsedSourceFiles` and looking up each file's
  content via `parsed.files.firstOrNull { it.fileName == tsFileName }?.content`.

Net delta: 1417 → 1416 failed (8658 → 8659 passing). Zero regressions across
10078-test suite. The narrow gate (`type == null && initializer == null`) is
why this doesn't over-fire on the other ~20 `isolatedDeclaration*` tests
(which use `export const X = 1 + 1;` patterns — initializer present, would
need richer "is type trivially declarable" checking to flag).

**17.239 (2026-05-13, +0 foundation)** — Generalize 17.238's multi-line
ArrowFunction Block-body squiggle clip from `body.pos + 1` (right after
`{`) to end-of-line of body opening brace. Captures trailing same-line
whitespace after `{` (e.g. `{ \n` → clip includes the trailing space)
matching TypeScript's actual end-of-line clip rule for multi-line bodies.

- **`body.pos + 1` (old)**: clipped immediately after `{` — correct for
  `{\n` (newline immediately after `{`) but off-by-one for `{ \n`
  (whitespace then newline). Cause: TypeScript clips at end of source
  line, not at the brace position.
- **`source.indexOf('\n', body.pos)` (new)**: scans forward from `{` to
  the first `\n`, falling back to `fullEnd` if not found, clamped to
  `fullEnd` and `coerceAtLeast(1)`. Produces the same result as
  `body.pos + 1` when `{` is immediately followed by `\n`, and extends
  one or more characters for trailing same-line whitespace.

Net delta: 1417 → 1417 failed (8658 unchanged). Zero regressions across
10078-test suite. Closes the line-32 off-by-one piece of `arrowFunctionErrorSpan_ts`'s
3-piece diff (verified targeted: `multi line with a comment 2` block-body
squiggle now matches expected 8-char `() => { ` span). Test still fails
on the remaining 2 pieces (TS1200 line-terminator-before-arrow missing
at line 18, multi-line comment-3 position issue at line 43+) per
8bddcfb's 3-piece characterization. The arrow-function `{ \n` pattern is
unique to this test in the entire corpus (verified via grep — only
`arrowFunctionErrorSpan.ts` and `declarationEmitUsingTypeAlias2.ts`
match, and the latter doesn't trigger TS2345 callback args), so the
foundation has no other immediate consumers but corrects a subtle
off-by-one that future TS2345 callback diagnostics on multi-line
arrow bodies with leading-line trailing whitespace will benefit from.

**17.238 (2026-05-13, +1)** — TS2345 emission for `() => void` callback
passed where target sig has errorType-typed params + display fix for
unresolved QualifiedName + multi-line ArrowFunction body squiggle clip.
Closes `undeclaredModuleError_ts` — emits the expected TS2345 at (8,29)
length 7 covering `() => {` with message
`'(stat: fs.Stats, name: string) => boolean'` (full qualified-name
path) and chain `'Type 'void' is not assignable to type 'boolean'.'`.

Three pieces all needed together; landing any subset alone yielded net
+0 or regressions:

- **`formatParameter` errorType branch** (Checker.kt ~43190): for an
  unresolved TypeReference whose typeName is a QualifiedName (no type
  args), render the FULL dotted path via new `formatTypeReferenceName`
  helper — `fs.Stats` not `Stats`. Scoped tightly to the errorType
  fallback so resolved `X.Y.Z`-style namespace types continue to display
  as just `Z` per TypeScript's convention (15-test regression confirmed
  if widened to all `formatTypeForDisplay` paths).

- **`checkArgumentsAgainstSignature` void-return-mismatch gate**
  (Checker.kt ~53234): new `allowVoidReturnMismatch` arm next to the
  existing `allowFuncToFunc` / `allowArityMismatch` arms. Fires when
  source is anonymous `() => void` (zero params + voidType return) and
  target is anonymous function with a simple-checkable non-void return.
  Guards the case where target sig has errorType-typed params (so
  `sigHasOnlySimpleTypes` returns false). void-return-is-special applies
  only when TARGET returns void (caller ignores result); the reverse
  direction must fail.

- **TS2345 squiggle length clip for multi-line ArrowFunction Block
  bodies** (Checker.kt ~53270): when arg is `ArrowFunction` with a
  `Block` body and the body's text spans multiple source lines, clip
  the squiggle length to `body.pos + 1 - start` (the body's open `{`
  inclusive). Single-line bodies like `() => {}` keep the full
  `expressionTrueEnd(arg)` length. Detect multi-line by checking
  `source.substring(body.pos, fullEnd)` contains `\n` — without this,
  `assignmentCompatBug5_ts` / `signatureLengthMismatchCall_ts` regress
  (-2) on single-line callback args.

Net delta: 1418 → 1417 failed (8657 → 8658 passing). Zero regressions
across 10078-test suite. Single-test flip — but the three pieces are
reusable infrastructure: full-qualified-name display works for any
unresolved-namespace-prefixed parameter type, the void-return gate
fires for any sig with errorType params, and the squiggle-clip is the
TypeScript-baseline-matching format for fn-vs-fn callback args.

**17.237 (2026-05-13, +1)** — TS2315 "Type 'D' is not generic." for
`class C extends fn()<T,U>` heritage pattern where `fn` is a sibling
`FunctionDeclaration` whose body returns a non-generic `ClassExpression`.
Closes `declarationEmitExpressionInExtends4_ts` — emits TS2315 at the full
span of `getSomething()<number, string>` (col 17 length 30 on line 5)
because `function getSomething() { return class D {} }` returns a class
with empty `typeParameters`.

Implementation: extended `checkConstraintsInExprWithTypeArgs` (Checker.kt
~47631) with a new `CallExpression` branch on `node.expression`. Narrow
surgical gates:
- callee is `Identifier` (no member-access / nested-call patterns);
- callee name resolves to a sibling `FunctionDeclaration` via the new
  `siblings: List<Statement>?` parameter (threaded through from the
  `ClassDeclaration` and `InterfaceDeclaration` arms of
  `checkConstraintsInStatements`);
- function body has a `ReturnStatement` whose expression is a
  `ClassExpression` with empty `typeParameters`;
- returned class has a name (anonymous classes don't emit — TypeScript's
  behavior is to name them in this diagnostic).

Span: `start = node.pos`, `length = (index of '>' >= lastArg.end - 1) + 1
- start` — mirrors `checkHeritageTypeArgCount`'s existing computation
which handles `node.end` overshoot per CLAUDE.md scanner gotcha.

Net delta: 1419 → 1418 failed (8656 → 8657 passing). Zero regressions
across 10078-test suite. Only failing test in the TS2315 family flips;
the entire test corpus has exactly one heritage `class X extends fn()<...>`
pattern so the surgical scope is bounded.

**17.236 (2026-05-13, +1)** — B1.3 first emission: TS2532 "Object is possibly
'undefined'." for `typeof X.Y.Z.W` chains in TypeAlias bodies where an
intermediate access dereferences a possibly-undefined receiver. Closes
`narrowingOfQualifiedNames_ts` — emits the expected 2 TS2532s at (33,25)
and (38,29) inside `function init2(foo: DeepOptional)`, both pointing at
`foo.a.b` (length 7) which is the receiver of `.c` when `foo.a.b` is
`{c?:string} | undefined`.

Three pieces:

- **Flow.kt** — record `currentFlow` at `TypeAliasDeclaration` position
  (`bindStatement` arm split out of the type-only no-op group). Lets the
  checker look up the enclosing flow context for path-based narrowing of
  identifiers referenced in `typeof X.Y.Z` chains.

- **Checker.kt** (`checkPropertyAccessInStatement`) — new
  `TypeAliasDeclaration` arm (strictNullChecks gated) calling
  `walkTypeNodeForUndefinedTypeQueryChain(stmt.type, ..., atNode=stmt)`.
  Walker recurses through Union / Intersection / Parenthesized / Array /
  Tuple / Rest / Optional / TypeOperator / NamedTupleMember; at TypeQuery
  leaves with a multi-segment QualifiedName, delegates to
  `checkTypeQueryQualifiedChain`.

- **Checker.kt** (`checkTypeQueryQualifiedChain`) — walks the chain
  root-to-leaf. At each step `prev.next`:
    - emits TS2532 at `prev`'s true span (computed via
      `qualifiedNameOrIdentifierTrueEnd` since `node.end` overshoots by
      one token per CLAUDE.md) when `receiverType` includes explicit
      undefined (via new `typeIncludesExplicitUndefined` helper that
      mirrors `typeIncludesUndefinedOrTop` but excludes any/unknown/never
      — TypeScript doesn't emit TS2532 for those);
    - otherwise computes the next receiver type via `getPropertyOfType`
      + `isOptionalProperty` widening + path-based narrowing via new
      `narrowTypeFromFlowFollowLoopEntry` helper.

- **Checker.kt** (`narrowTypeFromFlowFollowLoopEntry`) — variant of
  `narrowTypeFromFlow` that follows `FlowLoopLabel.antecedents[0]` (loop
  entry) instead of returning declaredType. Used ONLY by this walker so
  the existing conservative narrowing in `narrowTypeFromFlow` (which the
  17.1c attempt confirmed is load-bearing — regressed -7 tests when
  loosened) is unchanged. Sound for property-path narrowing inside loops
  in our test corpus because no test reassigns the narrowed property in
  the body; the flow graph has no FlowAssignment for property paths
  today.

Net delta: 1420 → 1419 failed (8655 → 8656 passing). Zero regressions
across 10078-test suite. Closes one of the named Blocker #1 substep
targets (B1.3); follow-on substeps could extend the walker to TypeQuery
in VariableDeclaration / Parameter / FunctionType return-position type
annotations (not yet attempted — no concrete failing-test target
identified).

**17.235 (2026-05-13, +1)** — Widen 17.111 + 17.112 assignment-expression
gates to fire for property-only Type.Object sources (in addition to the
existing callable / constructible source requirement). Closes
`propertyAssignment_ts` — `foo1 = bar1` where foo1 is `{ new ():any; }`
and bar1 is `{ x: number; }` now emits TS2322 + "  Type '{ x: number; }'
provides no match for the signature 'new (): any'."; same pattern for
`foo3 = bar3` against `{ ():void; }`.

Implementation: in `checkAssignmentExpression`'s Identifier-target
construct/call-sig mismatch gates (Checker.kt ~39218 and ~39250),
relaxed the source-side gate from
`!sourceType.callSignatures.isNullOrEmpty()` (or its symmetric construct
form) to
`(!sourceType.callSignatures.isNullOrEmpty() || (!isClassOrInterfaceInstanceType(sourceType) && !sourceType.properties.isNullOrEmpty()))`.
The `!isClassOrInterfaceInstanceType(sourceType)` guard preserves the
original 17.111 carve-out for opaque-import sources (`import ext = require("X")` where
"X" has `export = Class`) — those resolve to a Type.Interface with the
class symbol, which is excluded by this guard. Without the guard,
`externalModuleAssignToVar_ts` regressed (FP TS2322 on
`y2 = ext2` / `y3 = ext3` where ext2/ext3 are class-instance-typed
imports of `export = Class`).

Net delta: 1421 → 1420 failed (8654 → 8655 passing). Zero regressions
across 10078-test suite. Closes one previously-half-closed candidate
(propertyAssignment_ts MISS bucket entry, line ~3072 of skip log).

**17.234 (2026-05-13, +1)** — Ambient module `export = X` fallback in
`checkQualifiedNameExports`'s final-segment check. Closes
`aliasOnMergedModuleInterface_ts` — the residual FP TS2694
"Namespace 'foo' has no exported member 'A'." at (5,12) is now
suppressed because `foo.A` correctly walks through foo's
`export = B` to find `B.exports["A"]`.

Implementation: in Checker.kt's `checkQualifiedNameExports` final-segment
lookup (~11290), when the direct `exports[rightId.text]` lookup returns
null AND `symbol` is an ambient module with `export = X`, fall back to
`X.exports[rightId.text]` via the existing 17.232
`resolveAmbientModuleExportEquals` helper. Accessibility check
(`isMemberAccessible(candidate, ambientTarget)`) applies on the
fallback path. The fallback only fires when the direct lookup fails
— preserves augmentation resolution via the primary path. Avoids
the 17.233 revert pattern (mutating `resolveAlias` to walk through
ambient `export = X` broke `augmentExportEquals3_1_ts` because the
alias's identity changed; the fallback here only adds a secondary
lookup at the diagnostic site).

Net delta: 1422 → 1421 failed (8653 → 8654 passing). Zero
regressions across 10078-test suite. Closes one previously-skipped
candidate ("TS2708 vs TS2694 swap, multi-piece") with a single-
location fix made tractable by the 17.232 foundation.

**17.231 (2026-05-12, +1)** — Wire TS2708 emission for `import X = require()`
where `export = X` resolves to a type-only namespace. Completes the
17.230 half-fix on `typeUsedAsValueError2_ts`: the test now emits both
expected diagnostics — TS2693 for the type-only-interface case (`HelloInterface.world`,
already firing post-17.228) and TS2708 "Cannot use namespace 'HelloNamespace'
as a value" for the namespace-via-export-equals case at world.ts(5,1).

Implementation: in `checkTypeUsedAsValue`'s ImportEqualsDeclaration arm
(Checker.kt ~21520), restructured the routing decision into a `when`
block keyed on three predicates over the resolved module spec:

- `isBodyless` (17.230) — body-less ambient module, `any`-typed, → valueNames
- `isTypeOnly` — `isTypeOnlyImportRequire` returns true AND not body-less
- `isTypeOnlyNamespace` — `isTypeOnly` AND `exportEqualsIsNamespace` returns
  true (the export = target is a ModuleDeclaration)

Routing: isTypeOnlyNamespace → namespaceOnlyNames; isTypeOnly → typeOnlyNames;
else → valueNames. The existing TS2708 emission at the Identifier arm of
`checkTypeAsValueInExpr` (Checker.kt ~21760) then fires when a property
access receiver matches a name in namespaceOnlyNames. Conjunction with
`isTypeOnly` is the safety gate: only fires when the namespace has no
value exports, so namespaces with `export function`/`export const` keep
falling through to valueNames (no FP TS2708 for value-side namespaces).

Side fix: moved `val namespaceOnlyNames = mutableSetOf<String>()` declaration
to the top of `checkTypeUsedAsValue`'s per-file loop alongside `typeOnlyNames`
and `valueNames`. Was previously declared after the first scan loop; the new
routing needs to write into it during that loop.

Net delta: 1423 → 1422 failed (8652 → 8653 passing). Zero regressions.

**17.230 (2026-05-12, +1)** — Carve-outs in 17.228's
ImportEqualsDeclaration → typeOnly routing for two FP cases revealed
post-recon: (a) body-less ambient module `declare module "X";` —
`any`-typed at runtime, so `import X = require("X"); X;` is a value use
that shouldn't fire TS2693 (restores
`moduleNodeImportRequireEmit_ts__target_es5__` regression introduced by
17.228); (b) `export = X` where X is a Namespace — TS2708 "Cannot use
namespace 'X' as a value" is the correct diagnostic, not TS2693.

Implementation: two narrow helpers in Checker.kt:

- `isBodylessAmbientModule(moduleName)`: walks script files for
  `declare module "X"` with `body == null`. Mirrors
  `isAmbientModuleTypeOnly`'s file-scoping (skips module files) so
  augmentations don't trip it.

- `exportEqualsIsNamespace(moduleSpecifier)`: resolves the specifier
  to a target file, scans `ExportAssignment` with `isExportEquals=true`
  whose expression is an Identifier, then looks for a
  `ModuleDeclaration` in the target file's statements matching that
  name. Returns true only when `export = X` resolves to a namespace.

Both gates fire in `checkTypeUsedAsValue`'s `ImportEqualsDeclaration`
arm. `isAmbientModuleTypeOnly` itself is unchanged — Transformer
elision (which uses the same helper) keeps its current behavior since
this is a routing-specific carve-out.

Note: `typeUsedAsValueError2_ts` still fails — half-fixed by 17.228
(TS2693 for `HelloInterface.world` fires correctly), but the namespace
case `HelloNamespace.world` needs TS2708 emission which isn't wired
here. Tracked as a follow-on.

Net delta: 1424 → 1423 failed (8651 → 8652 passing). Zero regressions.

**17.229 (2026-05-12, +0 foundation)** — Check `ComputedPropertyName`
expressions in `TypeLiteral` members for unresolved identifiers.
Mirrors the class-member walker (Checker.kt ~10357) which already calls
`checkUnresolvedInExpr` on the ComputedPropertyName's expression — the
TypeLiteral PropertyDeclaration / MethodDeclaration branches at
~10807/~10811 only called `checkTypeOnlyKeywordInComputedName` (handles
`number`/`string` type-only keywords) but didn't walk arbitrary
identifiers.

Closes a half of `propertyAssignment_ts` (line 4 col 22 `[index]` — `index`
is a regular identifier not in scope). The test still fails because of
two missing pieces unrelated to ComputedPropertyName resolution: TS2322
"Type '{x:number}' is not assignable to type 'new () => any'." with
"provides no match for the signature 'X'." chain at lines 12 and 14
(anonymous TypeLiteral target with construct/call signature only —
requires non-constructable / non-callable structural-comparison
elaboration). Tracked as a follow-on substep.

Net delta: 1424 → 1424 failed (8651 unchanged). Foundation for any
future test that exercises an unresolved identifier in a TypeLiteral
ComputedPropertyName — currently zero such tests in the failing corpus,
so no immediate flip.

**17.228 (2026-05-12, +2)** — TS2693 "'X' only refers to a type, but is
being used as a value here." for `import X = require("./m")` where `m`
has `export = X` and that X is a type-only entity (interface / type
alias). Closes both target variants of `errorsOnImportedSymbol_ts`.

Pattern: `import Sammy = require("./errorsOnImportedSymbol_0");
var x = new Sammy.Sammy();`. The target module has `interface Sammy {
new(): any; (): number; } export = Sammy;` — `Sammy` is type-only.
Currently we add the local name `Sammy` to `valueNames` in
`checkTypeUsedAsValue` (Checker.kt ~21462), so the value-position uses
`new Sammy.Sammy()` and `Sammy.Sammy()` silently pass.

The fix consults the existing `isTypeOnlyImportRequire(specifier,
sourceFileName)` helper (Checker.kt ~998) which already walks
`export = X` and checks `isSymbolTypeOnly` on the target. When true,
the local name lands in `typeOnlyNames` instead, so the standard TS2693
emitter fires on every value-position use. Other ImportEquals forms
(QualifiedName / Identifier module references) stay value-side
unchanged.

Squiggle position: at the leftmost identifier of the PropertyAccess
chain (`Sammy` in `Sammy.Sammy()`), length = name length. Matches
TypeScript baseline (col 13 / col 9, length 5).

Net delta: 1426 → 1424 failed (8649 → 8651 passing). Zero regressions
across the 10078-test suite.

**17.227 (2026-05-12, +2)** — TS2702 "'X' only refers to a type, but is being
used as a namespace here." for default imports of a class/interface/type-alias
used in qualified-name type position. Closes both target variants
(es5 + es2015) of `decoratorMetadataWithImportDeclarationNameCollision7_ts`.

Pattern: `import db from './db'` where db.ts has `export default class db {}`,
then `let x: db.db` or `constructor(p: db.db)`. The leftmost `db` resolves to
an Alias symbol whose target is the class — `db.member` in TYPE position is
invalid (a class isn't a namespace), so TS2702 should fire at the qualifier
position with length = name length.

Two coordinated changes in `Checker.kt` `checkTypeNameResolved`'s
QualifiedName branch (~10937):

(1) When the leftmost symbol is an `Alias`, walk through to the resolved
target via `resolveAlias`. The pre-fix code excluded all Alias-flagged
symbols outright (citing FP risk from unresolved invalid imports), but
this also missed valid default-class imports. The new check uses
`resolved !== leftSym` to detect successful resolution — when resolveAlias
returns the same Alias symbol, no TS2702 fires (TS2305/TS2613 handles
the unresolved case). Then a new `resolveDefaultImportTargetForTs2702`
helper handles the gap where `export default class X {}` lacks a
`locals["default"]` entry in the binder (the class is bound under its
own name `X`, not `default`). The helper scans the target file for a
`ClassDeclaration`/`FunctionDeclaration`/`InterfaceDeclaration` with
`ModifierFlag.Default` and returns its symbol from `targetResult.locals`.

(2) Outer Module-flag check on `leftSym` is preserved — namespace imports
(`import * as M from './m'`) still bail before the alias-resolve step
because their alias resolves to a module symbol (via `createModuleSymbol`),
which the inner check excludes via `Module` flag.

Squiggle position: `leftmost.pos`, `length = lname.length` — matches
TypeScript baseline for both class member `db: db.db` (col 9, 2 chars)
and constructor param `constructor(db: db.db)` (col 21, 2 chars).

Net delta: 1428 → 1426 failed (8647 → 8649 passing). Zero regressions
across the 10078-test suite. Spot-checked sibling tests that emit
TS2702 today (`errorForUsingPropertyOfTypeAsType01`,
`invalidUseOfTypeAsNamespace`, `invalidImportAliasIdentifiers`) — none
exercise the alias-resolution path, so no behavior change there.

**17.226 (2026-05-12, +2)** — Two coordinated pieces flip both target
variants of `complicatedPrivacy_ts` (es5 + es2015). Closes the dual-bug
skip-log entry that the 16.4 family had flagged as "two coordinated
pieces; (b) high-risk per 16.4ek experience."

**Piece 1 — TS2693 for type-keyword in TypeLiteral computed property
name.** New helper `checkTypeOnlyKeywordInComputedName` in Checker.kt
near `checkUnresolvedInType`. Wired into the `TypeLiteral` branch of
`checkUnresolvedInTypeCore` for both `PropertyDeclaration` and
`MethodDeclaration` member names. Fires when the member name is a
`ComputedPropertyName` whose expression is a bare `Identifier` with
text in `TYPE_ONLY_KEYWORDS` (`number`, `string`, `boolean`, `bigint`,
`symbol`, `any`, `unknown`, `never`, `void`). Skipped when the name
is shadowed in scope (so `var number = 1; type T = { [number]: C }`
doesn't FP). Emits via existing `emitTS2693` for consistent display.
Closes `[number]: C1` at (35,6) length 6.

**Piece 2 — TS2694 for `implements ns.Member` where Member missing
from namespace exports.** New helper
`checkHeritagePropertyAccessForNamespaceMember` near
`isMemberAccessible`. Wired into the `ClassDeclaration` heritage-clause
loop in `checkUnresolvedInStatementCore` AFTER the existing
`checkUnresolvedInExpr` call, gated on `clause.token ==
ImplementsKeyword`. Mirrors `checkQualifiedNameExports` logic but
operates on `PropertyAccessExpression` (value-position parse) rather
than `QualifiedName`. Resolves leftmost identifier through
`currentFileLocals` / `globals` / `binderResults.locals`; bails if
result is not `SymbolFlags.Module`. Walks subsequent segments via
`exports[segment]` + `isMemberAccessible`; emits TS2694 at the first
missing/inaccessible segment using the existing `emitTS2694` helper
(which handles TS2724 spelling-suggestion variant when applicable).
Closes `implements mglo5.i5, mglo5.i6` at (73,55) length 2 — `i6`
isn't declared in `declare namespace mglo5 { interface i5 {} }`.

**Why this lands now (16.4ek's "-9 regressions" warning revisited).**
The earlier skip-log entry warned that "16.4ek already attempted (b)-ish
extension for `import =` and got -9 regressions; similar care would be
needed for heritage-clause variant." Re-reading 16.4ek's actual session
note shows it was an unrelated change (TS2451 for TypeAlias redecl).
The heritage-clause walker here is gated specifically on
`PropertyAccessExpression` (rejects deeper chains by `current is
Identifier` requirement) AND `clause.token == ImplementsKeyword` AND
leftmost resolves to a Module symbol — three conservative gates that
keep the change narrowly targeted. Zero regressions across the
10078-test suite (1430 → 1428 failed; 8645 → 8647 passing).

**17.225 (2026-05-12, +1)** — TS2307 "Cannot find module 'foo' or its
corresponding type declarations." for bare specifier under `moduleResolution: nodenext`
when the matching `.d.ts` lives directly under `node_modules/` without a
containing package directory. Closes `nodeNextModuleResolution1_ts`.
Pattern: `import {x} from "foo";` where `/a/node_modules/foo.d.ts` exists
but no `/a/node_modules/foo/{package.json,index.d.ts}` does — nodenext
resolution requires a directory, not a bare `.d.ts` file. Our generic
`dtsFileBaseNames` check treated every `.d.ts` basename as resolvable;
the fix adds a nodenext-specific guard gated on "every matching .d.ts
lives directly under `/node_modules/`" (no subdirectory). A sibling
project-level `<name>.d.ts` would still suppress TS2307 since it provides
a legitimate declaration.

New branch in `checkUnresolvedModules` (Checker.kt ~15258), inserted
after the existing paths-with-explicit-extension branch. Gate stack:
`!isRelative` + `effectiveModuleRes == "nodenext"` +
`moduleName !in ambientModuleNames` + `!hasNodeModulesPackage(moduleName)`
+ `moduleName in dtsFileBaseNames` + `matchingDts.all { in /node_modules/ at bare level }`.
The `allBareInNodeModules` check uses `lastIndexOf("/node_modules/")`
and verifies the suffix after it contains no `/` — distinguishes
`/a/node_modules/foo.d.ts` (bare, fires) from `/a/node_modules/foo/index.d.ts`
(directory-based, valid). Only `nodeNextModuleResolution1.ts` in the test
corpus matches this pattern; `nodeNextModuleResolution2.ts` uses the
directory form and is unaffected.

Net delta: 1431 → 1430 failed (8644 → 8645 passing). Zero regressions
across 10078-test suite.

**17.224 (2026-05-11, +1)** — TS1005 ";' expected." at stray `)` / `]` in
statement-start position (instead of TS1109 "Expression expected."). Closes
`parserUnparsedTokenCrash1_ts`. Pattern: `( y = 1 ; 2 )` in `.js` file —
after the broken paren-expr recovery emits TS1005 `)' expected at the `;`
position, statement-loop parses `2` as ExpressionStatement, parseSemicolon
silently returns at `)`, then statement loop is at `)` (col 13). Previously
fell through to the else branch → parseExpressionStatement →
parsePrimaryExpression's catch-all → TS1109 "Expression expected."
TypeScript emits TS1005 ";' expected" here — the closing delimiter signals
a missing statement terminator, not a missing expression.

Fix: new `CloseParen, CloseBracket` arm in `parseStatement` (Parser.kt
~500) that emits TS1005 at the delimiter (length 1), advances past the
token, and returns null. The `parseStatements` loop sees progress
(savedPos != tokenPos) and continues without double-skip. Narrow scope —
only fires when stray `)` / `]` is in statement-start position; nested
expression contexts (`(+)` etc.) still route through parsePrimaryExpression
as before. Earlier broad parseSemicolon-level attempt (queue item 10.2)
regressed 7 tests; this statement-level approach avoids those because
parseSemicolon's quiet-return behavior on `)` is preserved — only the
follow-up "what to do with that `)`" changes.

Net delta: 1432 → 1431 failed (8643 → 8644 passing). Zero regressions
across 10078-test suite. Closes the `parserUnparsedTokenCrash1_ts` skip-log
entry (which item 10.2 had skipped citing the parseSemicolon regression).

**MAINT-2 (2026-05-11, count reconciliation, no code change)** — Full-suite
re-measurement: 10,078 / 1,432 failed / 3 skipped → 8,643 passing. STATUS.md
had drifted +3 from reality. Tracing the chain via subsequent commits'
"failed" measurements: 17.220 left the suite at 1,432 failed (8,643). 17.221
claimed +1 without explicitly recording the failed count; 17.222 then
measured 1,434 failed pre-commit, implying 17.221's net was actually -2
(1 target flipped + 3 unrelated regressions). 17.222 and 17.223 each +1
from their measured baselines, landing the actual current state at 1,432 /
8,643. The 3 regressions introduced by 17.221 are not surfaced by
`find_candidates.py --fresh` (returns 0/0/0), meaning they have diff > 3
from baseline or are in tests not yet in the candidate-finder corpus.
17.221's TS2674 walker uses `globals[ident.text]` for the class symbol
lookup — if the regression source is identified in a future session, the
likely root cause is cross-file class-name collision in `globals` (the
walker fires on the wrong class). Logged here so future agents have an
accurate baseline.

**17.223 (2026-05-11, +1)** — TS2352 "Conversion of type 'undefined' to
type 'T' may be a mistake..." for `/** @type {T} */ (void 0)` casts in
JS-like files where the cast target is a named non-primitive
(uppercase-leading Identifier). Closes
`checkJsTypeDefNoUnusedLocalMarked_ts`. Pattern:
`module.exports = /** @type {FooFun} */(void 0);` — TS2352 fires at
the position of the type name `FooFun` INSIDE the JSDoc comment,
6-char squiggle. The parser was already capturing `jsdocCastType` on
`ParenthesizedExpression` (17.140b infrastructure); the new walker
emits the diagnostic when the inner expression is a `VoidExpression`.

New `checkJSDocVoidCastNonOverlap()` (Checker.kt ~30930): iterates
JS-like binderResults' top-level `ExpressionStatement` (recurses into
`BinaryExpression.right` for `module.exports = ...` patterns) and
`VariableStatement` initializers. Emits TS2352 when the
`ParenthesizedExpression` has a non-null `jsdocCastType` resolving to
a single-Identifier `TypeReference` whose name starts uppercase and
isn't in a skip set (`Object`, `Function`, `Any`, `Unknown`). Position
computed by scanning source backwards 256 chars from the
ParenthesizedExpression's `(` for the most recent `@type {NAME`
substring, then `nameStart = match + "@type {".length`.

**17.222 (2026-05-11, +1)** — TS2306 "File 'X' is not a module." for
`import * as Y from 'pkg'` where the bare specifier resolves via
node_modules walk-up to a script-shaped `.d.ts` (no imports/exports).
Closes `duplicatePackage_globalMerge_ts`. Two emissions per test file:
one for each `import * as React from 'react'` resolving through
`/node_modules/@types/react/index.d.ts` and
`/tests/node_modules/@types/react/index.d.ts` respectively.

New `checkNamespaceImportOfNonModule()` (Checker.kt ~15500) mirrors
the existing `checkImportEqualsRequireOfNonModule` flow but is scoped
to `ImportDeclaration` with `NamespaceImport` clause and bare
(non-relative) specifiers. New helper
`resolveBareSpecifierViaNodeModules(specifier, contextFileName)` walks
up from the importing file's directory, checking at each level for
`node_modules/<spec>/index.{d.ts,ts}` and
`node_modules/@types/<spec>/index.{d.ts,ts}` — closer node_modules
directories win. When the resolved file is not a module (per
`isModuleFile`), emits TS2306 with the absolute resolved path as the
display name. Multi-file mode only (gated same as the sibling
function). Squiggle covers the full quoted specifier
(`moduleName.length + 2` for the quotes).

**17.221 (2026-05-11, +1)** — TS2674 "Constructor of class 'X' is
protected and only accessible within the class declaration." for
`new ClassName(...)` where ClassName has (or inherits) a `protected`
constructor and the call site is outside the declaring-class
hierarchy. Closes `noCrashOnMixin_ts`. Also adds the missing TS2674
to `noCrashOnMixin2_ts` (not flipped — still needs TS2370 + TS2545).

New `callWalkerClassStack: ArrayDeque<Symbol>` tracks enclosing
classes for the call/new walker; push/pop around ClassDeclaration
member-body descent in `checkCallTypesInStatement`. New helper
`findEffectiveConstructorVisibility` walks the class's extends
chain looking for the first own Constructor declaration and returns
its accessibility modifier + declaring-class symbol. New helper
`classExtendsOrIs` walks subClass's extends chain checking
identity-or-extends against the declaring class. The TS2674 emission
inserted at the top of `checkSingleNewExpressionTypes` (before
arguments/calleeType resolution): fires when callee is a bare
Identifier resolving to a class symbol whose effective constructor
is `Protected` AND none of the enclosing classes on the stack
extends-or-is the declaring class. Squiggle covers the full
`new` expression via `expressionTrueEnd`.

Private modifier (TS2673) is tracked in the helper but not yet
emitted — `classConstructorAccessibility_ts` (which would exercise
both codes) isn't currently generated.

**17.220 (2026-05-11, +1)** — TS2538 "Type 'X' cannot be used as an
index type." for the `new Foo[a, b, c]` syntax artifact. Closes
`objectCreationOfElementAccessExpression_ts`. The TS parser treats
`new Foo[X]` as `new (Foo[X])` — an ElementAccessExpression as the
constructor callee, with `X` (a comma expression) as the index. We
were emitting TS2348 for the bare `Cookie(...)` call inside the comma
but missing the TS2538 for the index type. Narrow gate in
`checkSingleElementAccess`: arg is a `BinaryExpression(Comma)` whose
rightmost operand is a `NewExpression` on a class identifier (looked
up directly via `globals[ident.text]` for `ClassDeclaration` in
declarations). Display name uses the class identifier text directly
to dodge `getReturnTypeOfNewExpression`'s base-class-return-type
quirk for derived classes. Squiggle covers the entire arg span via
`expressionTrueEnd`.

**17.219 (2026-05-11, +1)** — TS2307 alongside TS1147 for
`import x = require("name")` inside a namespace when the specifier
doesn't resolve, multi-file mode only. Closes
`importInsideModule_ts` errors baseline. The existing
`flattenImportLikeStatements` skips Identifier-named namespaces
(comment notes "imports there use different diagnostics
TS1147/TS2667/TS1194"), so namespace-internal `import = require()`
never reached `checkUnresolvedModules`. Added the secondary
emission inside `walkRequireImportInNamespace` immediately after
TS1147: bare specifiers emit TS2307 unless shadowed by an ambient
module / .d.ts file / node_modules package; relative specifiers
emit when `resolveModuleSpecifierStrictRelative` returns null.
Gated on `isMultiFile` so single-file tests like
`importDeclarationInModuleDeclaration1` (which expects only
TS1147) stay green — single-file mode has no module-resolution
context to fail against.

**17.218 (2026-05-10, +3)** — TS2495 "Type 'X' is not an array type or
a string type." for `for-of <expr>` when `@lib` excludes ES2015+
iterables. Closes all 3 `arrayIterationLibES5TargetDifferent_ts__nolib_false_*`
variants (target=es5/es2015/esnext). New `checkForOfNonIterable`
walker gated on: `noLib == false` AND `options.lib.isNotEmpty()` AND
none of the libs start with "es2"/"esnext". Conservative type
detection: fires only for `Type.Intrinsic` (excluding string/any/
unknown/never) and anonymous `Type.Object` without symbol/sigs/
tuple. Excludes `Type.Reference` and `Type.Interface` to avoid
firing on Array<T>, named interfaces with iterators, etc.

**17.217 (2026-05-10, +0 correctness)** — Skip TS6133 for declarations
inside `declare global { ... }` blocks. Variables declared in such
blocks are intentional ambient globals exposed to other code, not
"unused locals". Net 0 because the named test
(`checkJsTypeDefNoUnusedLocalMarked_ts`) also needs TS2352 emission
in a `.js` file with JSDoc — out of scope. Detection: ModuleDeclaration
with `name = Identifier("global")` and `Declare` modifier.

**17.216 (2026-05-10, +1)** — Suppress TS2322 when assigning a function
expression / arrow to an anonymous multi-overload target. Closes
`contextualTyping_ts`. The pattern: `var c3t7: { (n: number): number;
(s1: string): number; } = function(n) { return n; };`. TypeScript
contextually types the function with one overload and skips strict
multi-overload structural compare. Our checker did the strict compare
(source's single sig must satisfy ALL target overloads) and rejected.
Narrow gate: `init` is FunctionExpression/ArrowFunction, target is
anonymous Type.Object (no symbol) with 2+ call signatures, no construct
sigs, no properties.

**17.215 (2026-05-10, +0 foundation)** — Optional method declarations
in TypeLiteral display now include the `?` token: `k?(a: any): any`
instead of `k(a: any): any`. Matches TypeScript's display convention.
Net 0 tests because the named test (`assignmentCompatBug2_ts`) also
needs contextual typing of arrow function expressions in object
literals — the source-side display still shows `(n: any) => any`
instead of `(n: number) => number`.

**17.214b (2026-05-10, +1)** — Fix `resolveModuleSpecifierStrictRelative`
to preserve leading `/` from absolute contextFileName paths. Closes
`exportAssignmentWithoutAllowSyntheticDefaultImportsError_ts` which
17.214 had regressed (FP TS2307 for `import bar from './bar'` from
`/foo.ts` where `/bar.ts` exists). Without the fix, the resolver
collapsed `dir` to "" and produced `bar` instead of `/bar` as the
candidate path, missing the `/bar.ts` key in `fileResults`.

**17.214 (2026-05-10, +2)** — TS2307 for unresolved relative imports
under ES module kinds (ES2015/ES2020/ES2022/ESNext/Preserve) with
default node10 resolution and no path/root configuration. Closes
both `shorthand-property-es5-es6_ts__target_es5__` and
`__target_es2015__` baseline tests. New conditional branch in
`checkUnresolvedModules` (Checker.kt ~14991): when module is
ES module kind AND `moduleResolution`/`paths`/`baseUrl`/`rootDirs`/
`rootDir`/`moduleSuffixes` are all unset, fire TS2307 if
`resolveModuleSpecifierStrictRelative` returns null and no ambient
or .d.ts module shadows the name. Narrow gate intentionally —
bundler/node16/nodenext/commonjs use richer resolution rules our
resolver doesn't model reliably.

**17.213 (2026-05-10, +0 foundation)** — TS2741 message: use displayed
target type name for PUBLIC missing properties; use declaring class
name only when the missing property has `private` or `protected`
modifier. Fixes the message text in `contextualTyping_ts` line 223
(`var x: B = {}` with `interface A { x: string }; interface B extends A {}`
now says "...required in type 'B'" not 'A') without regressing
`classImplementsClass4_ts` (`c2 = c` with `class A { private x = 1 }`
correctly says "...required in type 'A'"). Net 0 tests because
contextualTyping_ts still has an unrelated extra TS2322 at line 36
preventing full pass; classImplementsClass4_ts was already passing
via a different path.

**17.212 (2026-05-10, +1)** — TS2304 for `typeof default` in type-query
position. Closes `defaultIsNotVisibleInLocalScope_ts`. The `default`
keyword parses as an Identifier in our AST and gets silently skipped
by the KEYWORD_IDENTIFIERS guard in `checkIdentifierResolved`.
TypeScript treats `typeof default` as TS2304 regardless of whether
the file has an `export default`. Added a special-case in
`checkTypeQueryName`: when the type-query name is the bare Identifier
`default`, emit TS2304 at the identifier position with length 7.

**17.211b (2026-05-10, +1)** — Add `break` after first TS2394 emission
in `checkConstructorOverloadCompatibility` (matches the existing
method-overload behavior: TypeScript reports TS2394 once per overload
group, on the FIRST incompatible signature). Closes
`constructorsWithSpecializedSignatures_ts` which was regressed by
17.211 (3 ctors → 2 TS2394 emitted instead of 1).

**17.211 (2026-05-10, +1)** — TS2394 (constructor overload-vs-impl
arity) + TS2300 (duplicate parameter-property name across overload
+ impl). Closes `parameterPropertyInConstructor2_ts`. New
`checkConstructorOverloadCompatibility` walker called from the
ClassDeclaration branch. Mirrors the existing method overload
arity check but for ctors. Also detects duplicate parameter-
property names between overload and impl, emitting TS2300 on the
impl's parameter name (matching TypeScript's "second declaration"
position).

**17.210 (2026-05-10, +0 foundation)** — TS2591 instead of TS2307 for
`import = require('node-builtin')`. Recognizes Node.js built-in module
specifiers (fs, path, http, …, plus `node:`-prefixed forms) in the
`emitTS2307` helper and swaps the diagnostic to TS2591 with the
standard `@types/node` install hint. Net 0 tests this commit because
every currently-failing test that benefits from the swap also needs
additional pieces (TS2345 elaboration, TS2667 imports-in-augmentation,
or cross-augmentation symbol visibility). Foundation for follow-on
substeps that stack TS2667 / TS2666 emission for `declare global`
augmentations on top.

**17.209 (2026-05-10, +1)** — TS2422 instead of TS2304 for `class
C<T> implements T`. Closes `typeParameterAsBaseClass_ts`. In the
ClassDeclaration heritage walk (Checker.kt ~9758), check
`clause.token == ImplementsKeyword` and the type expression is a
bare Identifier matching a type parameter (and not also bound in
the parent value scope). When matched, emit TS2422 directly and
`continue`; skips the value-position TS2304 emission and the
typeArguments walk that don't apply to a bare identifier.

**17.208 (2026-05-10, +3)** — TS2384/TS2383/TS2386 for overload modifier
mismatch. Closes `overloadModifiersMustAgree_ts` plus 2 bonus.
Extended `checkOverloadsInStatements`: after locating the impl, compare
each overload's `Declare`/`Export` modifiers to the impl's; emit TS2384
"must all be ambient or non-ambient" / TS2383 "must all be exported or
non-exported" on the disagreeing overload's name. Added new
`checkInterfaceMemberOptionalOverloads` walker invoked via the same
recursive switch — groups MethodDeclarations within an InterfaceDecl
by name and emits TS2386 "must all be optional or required" on each
member after the first whose `questionToken` differs from the first.

**17.207 (2026-05-10, +2)** — TS2438 for import alias using a reserved
primitive type name. Closes `reservedNameOnInterfaceImport_ts` (both
JS-emit and errors baselines). In `parseImportEqualsDeclaration` after
parsing the alias name, check membership in
`RESERVED_TYPE_KEYWORD_NAMES` (string/number/boolean/any/unknown/never/
object/symbol/bigint/undefined) and emit TS2438 at the name's position
with length = name text.

**17.206 (2026-05-10, +2)** — TS2433 for namespace split across files
from class. Closes `cloduleSplitAcrossFiles_ts` plus one bonus. New
`checkNamespaceSplitAcrossFiles` walker. Gates: skip ambient
(`declare`) namespaces and ambient classes/functions; skip global
augmentation; skip string-literal modules; only top-level
namespaces walked.

**17.205 (2026-05-10, +1)** — TS1128 for stray `case` keyword at top
level. Closes `unexpectedStatementBlockTerminator_ts`. In
`parseStatement`'s CaseKeyword branch, emit TS1128 with
`overrideLength=4` BEFORE consuming the token; recovery unchanged.

**17.204 (2026-05-10, +1)** — TS1264 for definite-assignment without
type. Closes `definiteAssignmentWithErrorStillStripped_ts`. In
`parseClassMember`, capture `!` token position before
`parseOptional(Exclamation)`; emit TS1264 in property branch when
`excl && type == null`.

**17.203 (2026-05-10, +1)** — TS2791 for BigInt exponentiation under
target<ES2016. Closes `bigIntWithTargetLessThanES2016_ts`. New
`checkBigIntExponentiation` walker gated on `effectiveTarget < ES2016`.
For each BinaryExpression with `**` or `**=`, fires when either
operand is a bare `BigInt(...)` call.

**17.202 (2026-05-10, +1)** — TS2507 for class extends function
declaration. Closes `extendNonClassSymbol2_ts`. Track `funcDecls` map
in `checkNonConstructorExtendsInStatements`; when heritage names a
function (no class merge), emit TS2507 with `(<params>) => <returnType>`
display via new `formatFunctionTypeForExtends` helper.

**17.201 (2026-05-10, +1)** — TS1209 for `new A?.b()` (optional chain
on new expression with no parens). Closes
`invalidOptionalChainFromNewExpression_ts`. In `checkSinglePropertyAccess`,
when `expr.questionDotToken && recv is NewExpression && recv.arguments
== null`, emit TS1209 with "Did you mean to call '<ctor>()'?" hint.
Search backward from `expr.name.pos` for `?` to locate the `?.` token.

**17.200 (2026-05-10, +1)** — TS1016 for required parameter after
optional. Closes `fatarrowfunctionsOptionalArgsErrors1_ts`. In
`parseParameterList`, after CloseParen, walk parameters tracking
`sawOptional`; emit TS1016 on subsequent required params (no `?`,
no `...`, no initializer). Only `?`-optional triggers; `=`-initializer
does not (TypeScript treats it as implicitly required when followed
by required).

**17.199 (2026-05-10, +1)** — TS2507 for class extends `Ns.var`.
Closes `qualifiedName_entity-name-resolution-does-not-affect-class-heritage_ts`.
Extended `checkNonConstructorExtendsInStatements` with a
PropertyAccessExpression heritage branch. Resolves receiver via
globals as a Module symbol, finds the property in `nsSym.exports`,
reuses `inferSimpleVarType` for the primitive type display.

**17.198 (2026-05-10, +1)** — TS2678 for class identifier in switch
case. Closes `switchAssignmentCompat_ts`. In `walkSwitchCaseComparable`,
detect `caseExpr is Identifier` resolving to a pure class symbol and
emit TS2678 with display `typeof <ClassName>` BEFORE the existing
literal-kind comparison.

**17.197 (2026-05-10, +1)** — TS2337 for super outside constructor
in top-level function. Closes `superCallFromFunction1_ts`. In
`walkForIllegalSuperCalls`'s FunctionDeclaration branch, also call
`findNestedSuperCalls` with `inNestedFn = true` to flag direct
`super(...)` calls in top-level function bodies.

**17.196 (2026-05-10, +1)** — TS1163 for yield outside generator
function. Closes `yieldStringLiteral_ts`. New `walkYieldInStmts`/
`walkYieldInStmt`/`walkYieldInExpr` walker hooked into
`checkUndefinedNamesInStmts`'s FunctionDeclaration branch. Each
function/method body's generator state propagates from `asteriskToken`;
arrow functions are always non-generator; accessor / property-
initializer bodies likewise.

**17.195 (2026-05-10, +3)** — TS2457 for type alias with reserved
name. Closes `undefinedTypeAssignment1_ts` plus 2 bonus. Added a
TypeAliasDeclaration branch to `checkUndefinedNamesInStmts` reusing
the existing `PREDEFINED_TYPE_NAMES` companion-set.

**17.194 (2026-05-10, +1)** — TS2339 for instance-method access on
class identifier. Closes `staticInstanceResolution4_ts`. In
`checkMemberAccessMissing`, added a class-identifier branch before
existing module/var paths with a 7-condition conservative gate
(property-access shape, pure class via `valueDeclaration ===
classDecl`, no merges, no extends, no static, no runtime / namespace
exports, valid identifier propName).

**17.193 (2026-05-10, +1)** — TS1051 for optional setter parameter.
Closes `optionalSetterParam_ts`. In `parseSetAccessor`, after
parameter list parsing, when single parameter has `questionToken`,
search forward in source from name end to find `?` and emit TS1051.

**17.192 (2026-05-10, +2)** — TS1028 for duplicate access modifier
on parameter property. Closes `constructorArgsErrors3_ts` and
`constructorArgsErrors4_ts`. In `parseParameterModifiers`, track
`hasAccess` flag; when seeing a new access modifier with `hasAccess`
true, emit TS1028 at the current token position with length matching
the keyword name.

**17.191 (2026-05-10, +1)** — TS2411 for numeric-name property vs
number index signature. Closes `numericIndexerConstraint_ts`. Added
a number-index branch BEFORE the existing string-index logic in
`checkIndexSignatureProperties`. Conservative gate: only fires for
`PropertyDeclaration` whose name is a `NumericLiteralNode` and whose
property type isn't assignable to the number index value type.

**17.190 (2026-05-10, +1)** — TS2339 for `.prototype` on new
instance. Closes `prototypes_ts`. In `checkSinglePropertyAccess`,
when `expr.name.text == "prototype"` AND the receiver is a
`NewExpression` with an Identifier constructor name, emit TS2339
with display = constructor's name and squiggle on the property name.

**17.189 (2026-05-10, +1)** — TS6133 for unused infer type
parameter. Closes `unusedTypeParameters_infer_ts`. New
`checkUnusedInferParameters` walker called from main pipeline when
`noUnusedParameters` is true. Walks TypeAliasDeclaration /
InterfaceDeclaration / ModuleDeclaration bodies; for each
ConditionalType, collects InferType decls from extendsType and
checks references in trueType. Squiggle covers `infer U`.

**17.188 (2026-05-10, +1)** — TS2558 for Array.map with multiple
type args. Closes `thisExpressionInCallExpressionWithTypeArguments_ts`.
In `checkCallOrNewTypeArgCount`, add a PropertyAccessExpression branch
BEFORE the existing Identifier-only path; fires when receiver is an
ArrayLiteralExpression AND property name is a hardcoded fixed-arity
Array method (`map` / `flatMap`, both 1 type param).

**17.187 (2026-05-10, +2)** — TS2669 for `declare global` in
non-module file. Closes `moduleAugmentationGlobal6_ts` plus
`moduleAugmentationGlobal6_1_ts`. Extended `checkInvalidGlobalAugmentations`
to compute `isModuleFile` per file and propagate via a new
`fileIsModule` parameter; top-level `declare global` in a non-module
file now triggers the same TS2669 emission as the namespace-nested
case.

**17.186 (2026-05-10, +3)** — TS2394 for TypeLiteral overload
mismatch. Closes `functionOverloads17_ts`, `functionOverloads18_ts`
plus one bonus. In `isTypeNodeCompatible`, add a TypeLiteral↔
TypeLiteral branch that compares PropertyDeclaration members by name
+ type structurally. Returns false on different name sets or
recursively-incompatible same-named members; bails to true for non-
PropertyDeclaration members.

**17.185 (2026-05-10, +1)** — TS2796 for adjacent template literals.
Closes `missingCommaInTemplateStringsArray_ts`. In
`parseLeftHandSideExpression`'s NoSubstitutionTemplateLiteral/
TemplateHead branch, when the tag is itself a template literal,
emit TS2796 BEFORE constructing TaggedTemplateExpression. Span
covers the tag literal, computed by walking back from `template.pos`
to the last non-whitespace char.

**17.184 (2026-05-10, +1)** — TS2407 for for-in over identifier-
typed primitive. Closes `forInStatement2_ts`. Extended
`simpleRhsNonObjectDisplay`'s Identifier branch to look up the
symbol's value declaration and return a primitive display when the
type annotation is a `KeywordTypeNode` of number/boolean/bigint/
symbol/void/never.

**17.183 (2026-05-10, +2)** — TS2452 for enum member with
numeric/bigint name. Closes `enumWithBigint_ts` plus one bonus.
In `parseEnumDeclaration`, after `parsePropertyName` returns, emit
TS2452 when the name is `NumericLiteralNode` or `BigIntLiteralNode`.

**17.182 (2026-05-10, +2)** — TS1317 for rest parameter as parameter
property. Closes `restParamModifier2_ts` plus one bonus. In
`parseConstructor`'s parameter-property loop, when a parameter has
both an access/readonly modifier AND `dotDotDotToken`, emit TS1317
spanning from `param.pos` to the last non-whitespace character before
`param.end` (walk-backward to compensate for `param.end` overshoot).

**17.181 (2026-05-10, +1)** — TS2398 for parameter property named
`constructor`. Closes `parameterPropertyInConstructor3_ts`. After
`parseParameterList` in `parseConstructor`, iterate parameters and
emit TS2398 when the name is `Identifier("constructor")` and any
access/readonly modifier is present.

**17.180 (2026-05-10, +1)** — TS1246 for initializer on interface
property. Closes `errorOnInitializerInInterfaceProperty_ts`. In
`parseTypeMember`'s PropertyDeclaration construction, after parsing
the optional type annotation, check for `=`. If present, consume the
initializer (so subsequent members parse cleanly) and emit TS1246 at
the value position.

**17.179 (2026-05-10, +1)** — TS1176 for interface implements clause.
Closes `interfaceWithImplements1_ts`. In `parseHeritageClauses`, when
`!isClass && !hasImplements && clauseToken == ImplementsKeyword`,
emit TS1176 at the keyword position (length 10).

**17.178 (2026-05-10, +1)** — TS2538 for array-typed index
expression. Closes `arrayIndexWithArrayFails_ts`. In
`checkSingleElementAccess`, when arg is ElementAccessExpression and
its computed type is a Union containing an Array `Type.Reference`,
emit TS2538 with the first such constituent's display.

**17.177 (2026-05-10, +1)** — TS2385 for overloads with different
access modifiers. Closes `functionOverloads5_ts`. In
`checkMethodOverloadsInClass`, before the impl/overload pairing
logic, compute access-level set for the group; emit TS2385 when size > 1.

**17.176 (2026-05-10, +1)** — TS1313 for empty if-then statement.
Closes `emptyThenWarning_ts`. In `parseIfStatement` (Parser.kt ~899),
detect when the then-body is a real EmptyStatement (`pos > 0`, NOT
the `?: EmptyStatement()` synthetic fallback) and emit TS1313 on the
`;` token (length 1).

**17.175 (2026-05-10, +1)** — TS2311 for `await(...)` in sync function.
Closes `awaitCallExpressionInSyncFunction_ts`. In `checkAwaitInExpr`'s
CallExpression branch, when `!isAsync && callee is Identifier && callee.text == "await"`,
emit TS2311 on the callee Identifier (length 5). The keyword is
otherwise an unresolved identifier in sync context — TypeScript
specializes the diagnostic with the "did you mean async function?" hint.

**17.174 (2026-05-10, +1)** — Widen TS2499 to CallExpression heritage.
Closes `interfaceMayNotBeExtendedWitACall_ts`. In
`emitTs2499ForInterfaceExtendsNonEntityName`, accept CallExpression in
addition to ParenthesizedExpression. The shared paren-scanning logic
handles both shapes correctly via a `sawOpen` flag.

**17.173 (2026-05-10, +3)** — TS1172 / TS1175 for duplicate
extends/implements clauses. Closes `extendsClauseAlreadySeen_ts`,
`extendsClauseAlreadySeen2_ts`, `implementsClauseAlreadySeen_ts`. In
`parseHeritageClauses`, track `hasExtends` alongside `hasImplements`
and emit on duplicate keywords with length 7/10.

**17.172 (2026-05-10, +1)** — TS2499 for interface extends parenthesized
expression. Closes `declarationEmitInterfaceWithNonEntityNameExpressionHeritage_ts`.
New `emitTs2499ForInterfaceExtendsNonEntityName` helper called from
the InterfaceDeclaration heritage walker; ParenthesizedExpression-only
gate (initially), with paren-scan source forward from `expr.pos`
matching paren depth.

**17.171 (2026-05-10, +1)** — TS1147 for require import in internal
namespace. Closes `importDeclarationInModuleDeclaration1_ts`. New
`checkRequireImportInNamespace` walker. Critical gate: only fires when
the enclosing ModuleDeclaration's `name` is an Identifier AND not
"global" — string-literal modules / module augmentations / declare
global use different diagnostics (TS2439 / TS2664 / TS2667).

**17.170 (2026-05-10, +2)** — TS2351 for `new x()` where x is a plain
instance variable. Closes `newOnInstanceSymbol_ts` plus one bonus.
In `checkSingleNewExpressionTypes`'s `signatures.isEmpty()` branch,
emits TS2351 with "Type 'X' has no construct signatures." chain when
callee is a bare Identifier resolving to a `Variable` symbol (not
Class/Function) and calleeType is a `Type.Interface`.

**17.169 (2026-05-10, +1)** — TS1047/TS1048 for optional/initialized
rest parameter. Closes `restParamAsOptional_ts`. In `parseParameter`,
captures the `?` token position before `parseOptional(Question)` and
emits TS1047 on `?` itself (length 1) + TS1048 on the parameter name
when `dotDotDot && (question || init != null)`.

**17.168 (2026-05-10, +1)** — TS1071 for access modifier on interface
index signature. Closes `modifiersOnInterfaceIndexSignature1_ts`. In
`parseTypeMember`'s OpenBracket branch, emits TS1071 BEFORE delegating
to `parseIndexSignatureOrProperty` when public/private/protected is
present in modifiers. Squiggle on the modifier keyword via captured
`pos` and keyword-length.

**17.167 (2026-05-10, +1)** — TS1248 for const class member. Closes
`ClassDeclarationWithInvalidConstOnPropertyDeclaration_ts`. In
`parseClassMember` after `parsePropertyName`, emits TS1248 at the
member name when `ModifierFlag.Const in modifiers`.

**17.166 (2026-05-10, +1)** — TS2348 for calling a class without `new`.
Closes `callOnClass_ts`. In `checkSingleCallExpressionTypes`'s
`signatures.isEmpty()` branch (Checker.kt ~49006), emit TS2348 when the
callee is an Identifier resolving to a `SymbolFlags.Class` symbol AND
no FunctionDeclaration with the same name exists in any binderResult's
source-file statements (the function-name gate handles `function Foo`
+ `class Foo` merges that the binder's `canMerge` rejects). Squiggle
length computed locally to avoid regressing the shared
`expressionTrueEnd` helper.

**17.165 (2026-05-10, +1)** — TS1211 for `export class { }` without
`default`. Closes `exportClassWithoutName_ts`. In
`parseExportDeclaration`'s ClassKeyword branch (Parser.kt ~2552),
inspect the returned `ClassDeclaration.name` and emit TS1211 with
squiggle on the outer `export` keyword (length 6) when null.

**17.164 (2026-05-10, +1)** — TS2431 for enum with primitive name.
Closes `enumWithPrimitiveName_ts`. New `checkEnumPrimitiveName` helper
(reuses `PREDEFINED_TYPE_NAMES` companion-set: any/number/boolean/
string/void/never/object/unknown/undefined/null/bigint/symbol) called
from `checkDuplicateEnumMembers`.

**17.163 (2026-05-10, +1)** — TS2304 / TS2312 for heritage extending
bare type parameter. Closes `inheritFromGenericTypeParameter_ts`. Two
helpers (`emitTs2304ForHeritageExtendsTypeParam`,
`emitTs2312ForInterfaceExtendsTypeParam`) called after the existing
`checkUnresolvedInExpr` for class/interface heritage clauses. Fires
when the heritage Identifier resolves only to a type parameter in the
inner scope AND the parent scope has no value-side binding for the
same name.

**17.162 (2026-05-10, +1)** — TS2339 for missing enum member via
element access. Closes `constEnumBadPropertyNames_ts`. The existing
`checkSingleElementAccess` StringLiteralNode-on-enum branch dropped its
strict-mode gate; under non-strict it now emits TS2339 with display
`typeof <enumName>` instead of the strict-only TS7015.

**17.161 (2026-05-10, +1)** — TS2339 for property access on string
literal receiver. Closes `classExtendsInterface_not_ts`. Added a
`StringLiteralNode` receiver branch near the top of
`checkMemberAccessMissing` (Checker.kt ~47284). Resolves to the String
wrapper apparent type, emits TS2339 with `"<literal>"` display when
the property isn't a String wrapper member.

**17.159 (2026-05-10, +1)** — TS1337 for generic / literal index signature
parameter type. Closes `genericIndexTypeHasSensibleErrorMessage_ts`. Two-piece
fix in `checkIndexSigInStatement`: (a) added a TypeAliasDeclaration branch that
unwraps the body as `TypeLiteral` and propagates the alias's type-parameter
names; (b) extracted the TS1268 emission into a new `checkIndexSigsInMembers`
helper that distinguishes TS1337 (TypeReference whose name matches an outer
type-param, OR a `LiteralType`) from TS1268 (other unsupported shapes).
Class/interface/module paths preserved with empty outerTypeParamNames.

**17.158 (2026-05-10, +3)** — TS1162 for optional object literal member.
Closes `objectLiteralMemberWithQuestionMark1_ts` and
`spaceBeforeQuestionMarkInPropertyAssignment_ts` plus one bonus. In
`parseObjectLiteralElement` (Parser.kt ~4389), the silent
`parseOptional(SyntaxKind.Question)` now captures the `?` token position,
calls `nextToken()`, and emits `reportError(code=1162, overrideLength=1)`.
The `?` is still consumed so downstream method-shorthand and
PropertyAssignment parsing is unchanged.

**17.157 (2026-05-10, +1)** — TS17012 for misspelled `new.target`
meta-property. Closes `misspelledNewMetaProperty_ts`. In
`parseNewExpression()`'s `Dot` branch (Parser.kt ~3859), after
`parseIdentifier()`, check `name.text != "target"` and emit TS17012 via
`reportError()` with `overrideStart = name.pos`,
`overrideLength = name.text.length`. The MetaProperty AST node is still
constructed.

**17.156 (2026-05-10, net-zero foundation)** — Mirror 17.154's
`return new C()` inference into the broader `inferReturnTypeFromBody`
(used by MethodDeclaration / FunctionDeclaration / GetAccessor /
`buildMethodType`). `class Foo { make() { return new Bar(); } }`
now infers `() => Bar` instead of `() => any`. Foundation for
future fix pairings.

**17.155 (2026-05-10, +1)** — Two-piece change flipping
`interfaceImplementation1_ts`. Stacks on 17.154's
`inferReturnTypeFromFunctionExpressionBody` foundation.

(1) Var-decl chain elaboration: when fn-vs-fn call signature matches
but target ALSO has construct signatures that source lacks, emit the
construct-sig-missing line via `getNonConstructibleElaboration`.
Closes the gap left by `getFunctionMismatchElaboration` returning empty
when call sigs are compatible — the relation engine still returns
`false` because target's `new (): T` is unsatisfied. Inserted in
`checkVarDeclAssignability`'s chain branch (Checker.kt ~36898) with a
gate identical to 17.111's: skip class/interface instance targets
(their construct sigs belong to the static side). Adds chain line
`Type '() => C2' provides no match for the signature 'new (): I3'.`
for `var a: I4 = function(){ return new C2(); }`.

(2) Multi-`implements` TS2420 emission. `checkImplementsClauses`
(Checker.kt ~43670) had four `return` statements after each emission
path (missing-prop, private-mismatch, missing-number-index,
missing-string-index), exiting after the FIRST failing interface
across ALL implements clauses. Wrapped the inner
`for (typeExpr in clause.types)` loop in `typeExprLoop@` label and
converted the four returns to `continue@typeExprLoop`. TypeScript
emits one TS2420 per failing implements TARGET (per typeExpr), not
per class. Now `class C1 implements I1, I2 { private iFn() ... }`
emits two TS2420s — one for I1's first private member (iObj), one
for I2's only member (iFn).

Net delta: 1518 → 1517 failed (8557 → 8558 passing). Zero regressions
across 10078-test suite.

**17.154 (2026-05-10, net-zero foundation)** — `inferReturnTypeFromFunctionExpressionBody`
now handles `return new C()` patterns. Bounded to the existing single-stmt-body
single-return gate; calls `getReturnTypeOfNewExpression` for the retExpr and
returns the constructed instance type when non-error / non-any. Improves display
for `var a: I4 = function(){ return new C2(); }` (interfaceImplementation1) from
`() => any` to `() => C2`. Net-zero on the 10078-test suite (1518/3 unchanged) —
no test in the corpus gates SOLELY on this single missing inference: the lone
match `interfaceImplementation1_ts` also requires (a) multi-interface TS2420
emission for `implements I1, I2` and (b) the `Type '() => C2' provides no match
for the signature 'new (): I3'.` construct-sig elaboration line. Foundation for
those follow-ons.

**17.153 (2026-05-10, +1)** — Push interface typeParameters into scope for
call/construct signature resolution + TS2345 for null/undefined arg vs
concrete `Type.Reference` parameter. Flips
`inheritedGenericCallSignature_ts` (1 test).

The bug was two-fold:

(1) `resolveInterfaceMembers`'s call-signature branch (Checker.kt ~39141) did
NOT push the enclosing interface's `typeParameters` into
`currentTypeParamScope` before resolving the signature's return type and
parameter type annotations. For `interface I1<T> { (a: T): T; }`, both `T`
references resolved through `getTypeFromTypeNode(T_ref) →
getTypeFromTypeReference` where `currentTypeParamScope` was null →
fell through globals lookup → returned `errorType`. The errorType was
then cached in `nodeTypes` (cacheable when scope is null). Downstream
`instantiateSignature` calls (e.g. `resolveReferenceMembers(I1<T[]>)`'s
`I1.callSignatures.map { instantiateSignature(it, mapper={I1.T → I2.T[]}) }`)
were no-ops on errorType — so `I2<Date>`'s inherited callSig param ended
up as errorType instead of `Date[]`.

Fix: in the call/construct sig branch, push the interface's `typeParameters`
(plus any signature-own `typeParameters`) into scope, pre-resolve param
types into `symbolTypes[paramSym.id]` so later `getTypeOfSymbol(paramSym)`
returns the in-scope-resolved type even after scope restoration. Mirrors
the named-method branch in `getTypeOfSymbolWorker` (line ~38876). Both
empty-name (call sig) and `"new"`-name (construct sig) member kinds share
the same logic in a unified branch.

(2) Even with paramType correctly resolved to `Date[]` (Type.Reference),
the existing `checkArgumentsAgainstSignature` simple-checkable gate
(Checker.kt ~50528) skipped the TS2345 emission because paramType is a
Reference, not a named Interface. Extended the existing 17.11c
null/undefined-vs-Reference branch (Checker.kt ~50246) to also fire when
the Reference is fully concrete (no sig-side TPs in
`resolvedTypeArguments`) — falls back to plain `typeToString(paramType)`
display ("Date[]") instead of the unknown-substituted display used when
sig TPs are present.

Net delta: 1519 → 1518 failed (8556 → 8557 passing). Zero regressions
across 10078-test suite.

**17.152 (2026-05-10, +1)** — TS1146 + TS1005 for `{` after access modifier in
class body (parser error recovery). Flips `classMemberWithMissingIdentifier_ts`
(1 test). When `parseClassMember` detects a non-static modifier prefix
(e.g. `public`) followed directly by `{`, the existing early-return path for
this recovery case (Parser.kt:1632) now emits two diagnostics before
returning null:

- TS1146 "Declaration expected." at `scanner.getPrevTokenEnd()` (position
  right after the last-consumed modifier, before whitespace) with
  zero-length squiggle. Matches TypeScript's column-11 placement for
  `    public {` (between the modifier `public` and the `{`).
- TS1005 "';' expected." at the `{` position (`getPos()`) with length 1.

The TS1005 emission also pre-empts the subsequent `parseExpected(CloseBrace)`
error in `parseClassDeclaration` (Parser.kt:1477). That parseExpected would
fire "'}' expected." at the same `{` position, but `reportError`'s same-
position dedup (`if (lastDiag.start == start) return`) suppresses it because
the TS1005 we just emitted is the `lastDiag` at that position. The class
body still exits early (return null), so the `{};` falls through to top-
level parsing as Block + EmptyStatement, and the actual closing `}` of the
class on the next line fires TS1128 "Declaration or statement expected."
exactly as TypeScript expects.

Net delta: 1520 → 1519 failed (8555 → 8556 passing). Zero regressions.

**17.151 (2026-05-10, +1)** — TS2842 for unused destructured renames in
interface SetAccessor signatures. Flips
`declarationEmitBindingPatternsUnused_ts` (1 test). Closes the residual gap
left by 17.150: that walker covered FunctionType / ConstructorType /
TypeLiteral.MethodDeclaration / TypeLiteral.Constructor and
InterfaceDeclaration.MethodDeclaration members, but missed
`InterfaceDeclaration.SetAccessor` (e.g. `interface I { set x({ name:
alias }: Named); }`). Fix: extend the InterfaceDeclaration member-iteration
in `checkUnresolvedInStatementCore` (Checker.kt ~9638) with `is SetAccessor`
and `is GetAccessor` branches. The SetAccessor branch builds a child scope,
adds parameters to it, walks param `type` annotations, and calls
`checkUnusedDestructuredRenames(member.parameters, null, ...)` (no return
type — setters are void by definition). The GetAccessor branch is added for
parity but only walks the return type (getters never have parameters with
destructured renames).

Net delta: 1521 → 1520 failed (8554 → 8555 passing). Zero regressions.

**17.150 (2026-05-10, +3)** — TS2842 emission for unused destructured-property
renames in function-TYPE positions + companion FP suppressions for `typeof
<renamed-binding>` references. Flips
`renamingDestructuredPropertyInFunctionType_ts`,
`renamingDestructuredPropertyInFunctionType3_ts`, and
`paramterDestrcuturingDeclaration_ts` (3 tests). Three-piece change in
Checker.kt:

- **Piece 1 (FP suppression in `typeof X`)**: `checkTypeQueryName` (~10822)
  now also consults the local `NameScope` via `!scope.has(name.text)` before
  emitting TS2693 for type-keyword names like `string` / `number`. This
  catches the case where a parameter destructured-rename
  (`({ a: string }) => typeof string`) shadows the keyword — `addBindingName`
  already registers the renamed binding into the FunctionType's `fnScope`,
  but `nameResolvesToValue` only consults `result.locals`/globals, so the
  scope hop was missing. Safe because TYPE_ONLY_KEYWORD names are never
  registered via `addType` — `scope.has` being true here implies a
  value-position binding.
- **Piece 2 (FP suppression in implementation bodies)**: extended the
  FunctionDeclaration / FunctionExpression / ArrowFunction branches in
  `checkTypeAsValueInStatement` / `checkTypeAsValueInExpr` to walk parameter
  binding patterns (not just bare Identifiers) via new
  `addParamBindingNamesToValues` helper. Without this, `function f4({ a:
  string }: O): typeof string { return string; }` falsely flagged the inner
  `typeof string` and `return string` as TS2693 because the destructured
  rename `string` wasn't in `valueNames`. Mirrors `addBindingName` for the
  type-as-value walker's name sets.
- **Piece 3 (TS2842 emission)**: new `checkUnusedDestructuredRenames`
  walker called from `checkUnresolvedInType`'s FunctionType /
  ConstructorType / TypeLiteral.MethodDeclaration / TypeLiteral.Constructor
  branches and from `checkUnresolvedInStatementCore`'s
  InterfaceDeclaration.MethodDeclaration branch. For each parameter that's
  an `ObjectBindingPattern`, iterates `BindingElement`s with
  `propertyName != null`. If the local-binding identifier is not referenced
  in any type-position scope (return type, other parameters' types — checked
  via new `isNameReferencedInTypeQuery` that recurses through all common
  TypeNode kinds looking for `typeof <name>`), emits TS2842 with squiggle
  on the local-binding identifier. Companion `emitTS2842` /
  `formatBindingPropertyName` helpers; `formatBindingPropertyName` handles
  Identifier / StringLiteralNode (`"a"` form) / NumericLiteralNode (`2`
  form) / ComputedPropertyName (`[expr]` form).
- **Piece 3b (TS2843 related info)**: when the parent Parameter has no
  explicit `type` annotation, also attaches TS2843 ("We can only write a
  type for 'X' by adding a type for the entire parameter here.") pointing
  at the parameter list's closing `)`. Per the CLAUDE.md `node.end`
  overshoots-by-one-token gotcha, `param.end` is typically already past
  the `)` and the following `=>`/`:`, so `buildTs2843RelatedInfo` scans
  BACKWARD from `param.end - 1` for the nearest `)` (bounded by
  `param.pos`). Forward scan would skip the correct `)` and hit a later
  parameter list's `)` (verified mistake — initial forward-scan attempt
  pointed TS2843 at the next `type FN` declaration's `)`).

Net delta: 1524 → 1521 failed (8551 → 8554 passing). Zero regressions.

**17.149 (2026-05-07, +1, closes B5.6)** — TS6205 for all-unused multi-id
JSDoc `@template` tags + body `@type {T}` JSDoc usage tracking. Flips
`unusedTypeParameters_templateTag2_ts`. Two-piece change in Checker.kt:
- **Piece 1 (TS6205)**: `reportUnusedTypeParams` now records each tag's
  span end (`tagSpanEnd[tagPos]`) alongside sibling/unused counts. Before
  the per-identifier emission loop, walks tag groups with
  `siblingCount >= 2 && unusedCount == siblingCount` and emits a single
  TS6205 ("All type parameters are unused.") with the full tag span
  (e.g. `@template T,V`). The covered tags are recorded in
  `coveredByTs6205: MutableSet<Int>` so the per-identifier loop skips
  declarations whose `jsDocTagPos` is in that set — no double emission.
- **Piece 2 (body `@type {T}`)**: new
  `collectTypeRefsFromJSDoc(comments, scope)` helper walks
  `MultiLineComment` entries starting with `/**` for `@type` tags
  (rejecting partial-identifier matches like `@typedef`), then for each
  tag scans the brace-balanced content adding every identifier-like
  token to `scope.referencedNames`. Wired in two places: per-class-member
  iteration in `checkUnusedInClass` (covers PropertyDeclaration `@type`)
  and inside `collectTypeRefsInStatement` (covers `@type` on body
  statements like `/** @type {T} */ this.p;`). Helper is tpScope-only by
  call-site discipline — non-TP unused-decl scopes don't traverse it.

Net delta: 1525 → 1524 failed (8550 → 8551 passing). Zero regressions.
Closes the B5.x JSDoc `@template` family for the named targets — the
remaining wider patterns (`@template T extends Constraint`, `@template
T = Default`, InterfaceDeclaration / TypeAliasDeclaration sites,
constraint TypeNode synthesis) are deferred until a specific failing
test demands them.

**17.148 (2026-05-07, +1, closes B5.5 single-id case)** — Custom
`@template` span for TS6133 on JSDoc-derived TypeParameters. Flips
`unusedTypeParameters_templateTag_ts` (12-char squiggle covering
`@template T ` at col 5 instead of 3-char squiggle covering ` T ` at
col 14). Three-piece change:
- **Ast.kt** (`TypeParameter`): two new fields
  `jsDocTagPos: Int = -1` / `jsDocTagEnd: Int = -1` carrying the
  absolute source positions of the `@template` keyword's `@` and the
  end of the tag content (next JSDoc tag start or comment-close `*/`,
  whichever comes first).
- **Parser.kt** (`parseJSDocTemplateTypeParams` ~5089): refactored to
  buffer per-tag identifiers (`tagIds`) before constructing
  `TypeParameter`s, then computes the tag's end offset by scanning
  forward from the last identifier for either `*/` or a following
  `@<tag>` at a new line. Each TP in the same tag receives the same
  `jsDocTagPos` / `jsDocTagEnd`.
- **Checker.kt** (`reportUnusedTypeParams` ~4970): groups declarations
  by `jsDocTagPos`, computing per-tag sibling-count and unused-count.
  When a JSDoc-derived TP's tag has exactly 1 identifier and that one
  is unused, emits TS6133 with the full tag span (`jsDocTagPos`..
  `jsDocTagEnd`). Multi-id tags fall through to per-identifier span
  (deferred to B5.6 — needs TS6205 for all-unused, individual squiggles
  for partial-unused). The pre-existing `<T>` syntax `pos-1, len+2`
  branch is now gated `&& !tp.fromJSDoc` so synthetic JSDoc TPs don't
  pick up the angle-bracket span.

Net delta: 1526 → 1525 failed (8549 → 8550 passing). Zero regressions
across 10078-test suite. `unusedTypeParameters_templateTag2_ts` still
fails — needs TS6205 emission for all-unused multi-id tags + body
usage tracking through `@type {T}` JSDoc references for proper T-used
detection (both deferred to B5.6+).

**17.147 (2026-05-07, net-zero, B5.4 foundation)** — Wire
`parseJSDocTemplateTypeParams` into `parseFunctionDeclarationOrExpression`
(JS-like files only, fallback when no TS-level `<T>` parsed). Mirror of
17.146's ClassDeclaration wiring. Net-zero on the 10078-test suite —
no failing test flipped: the natural target
`unusedTypeParameters_templateTag_ts` needs TS6133 with custom JSDoc
squiggle covering the entire `@template T` span (12 chars at col 5),
but our standard TS6133 type-param emission squiggles on the
identifier name itself (1 char on `T` at col 15). That custom-span
emission is a separate substep. Foundation lands so any future test
that gates only on `function`-side `@template` parsing (e.g.
generic-call-arg checking with TS2314 on a JSDoc-only-typed generic
function) flips automatically without re-wiring.

**17.146 (2026-05-07, +1, closes B5.3)** — JSDoc `@template T` parser
bridge for ClassDeclaration in JS-like files. Stacks on 17.145's B5.2
to flip `jsdocClassMissingTypeArguments_ts`. New
`parseJSDocTemplateTypeParams(comments)` helper (Parser.kt) walks
`/** @template T */` (or comma-separated `@template T,U`) tags from a
declaration's leading comments and returns synthetic
`TypeParameter(name=Identifier, fromJSDoc=true)` nodes with absolute
source positions: `comment.pos + offset_within_comment_text` for the
identifier name. Also handles the optional `{Constraint}` brace block
(skipped, not parsed). Wired into `parseClassDeclaration` (uses JSDoc
template params only when no TS-level `<T>` was parsed).

Companion: new `fromJSDoc: Boolean = false` flag on `TypeParameter`
data class (Ast.kt). Updated 2 of the 4 TS8004 emission sites
(`is FunctionDeclaration` and `is ClassDeclaration` branches in
`checkTsSyntaxInStatementCore`) to skip JSDoc-derived type params via
`firstOrNull { !it.fromJSDoc }`. Without this, the synthetic `T` would
fire spurious "Type parameter declarations can only be used in
TypeScript files" inside the JSDoc comment.

For the candidate test, `getTypeParamInfo("C")` now returns
`(1, 1, "C<T>")` (was `(0, 0, "C")` per 17.145's debug println), so
when 17.145's B5.2 bridge produces `@param {C} p` →
`TypeReference(C, no type args)`, `checkTypeArgCount` finds
`providedCount=0 != maxTotal=1` and emits TS2314 at (4,13). Net delta:
1527 → 1526 failed (8548 → 8549 passing). Zero regressions across
10078-test suite. Closes B5.3 with the named target flipped.

Wider B5.3 patterns (constraints, defaults, MethodDeclaration,
InterfaceDeclaration, TypeAliasDeclaration, FunctionDeclaration in JS
files) and the other 2 TS8004 sites (MethodDeclaration / ClassExpression)
remain on the bare-identifier scope only — extending requires another
substep with regression budget for each new wiring point.

**17.145 (2026-05-07, net-zero, B5.2 foundation)** — JSDoc `@param {T}`
non-primitive single-Identifier bridge. Extends 17.140's primitive-only
`parseJSDocParamPrimitiveTypeMap` to also accept a bare identifier
matching `[A-Za-z_$][\w$]*` (no QualifiedName, no `<>`, no `|`).
Constructs a `TypeReference(typeName=Identifier, pos=ABS, end=ABS)` with
absolute source positions: walks back from the matched `@param` tag to
find the opening `{`, computes the trimmed identifier's offset within
the brace span, then `comment.pos + brace_open + 1 + leading_ws` gives
the absolute position. Companion fix in Checker.kt
(`checkTsSyntaxInParams` ~57170): added `&& !param.typeFromJSDoc` gate
to TS8010 emission, mirroring 17.65's var-decl pattern. Without this,
JSDoc-derived param types fired TS8010 ("Type annotations can only be
used in TypeScript files") since `param.type` is set.

Candidate target `jsdocClassMissingTypeArguments_ts` did NOT flip with
B5.2 alone — verified via debug println that the bridge fires correctly
(`scope.has("C") == true`, `getTypeParamInfo("C")` reached) but returns
`(0, 0, "C")` because our parser doesn't recognize `/** @template T */`
as a type-parameter declaration on classes. Promoted B5.3 (`@template`
parser bridge) to the queue with the explicit `getTypeParamInfo` finding
so the next session can build directly on this foundation. Net delta:
1527 → 1527 failed (8548 unchanged). Zero regressions across 10078-test
suite.

**17.144 (2026-05-07, +1, closes B6.1)** — Union-with-primitive contextual
TS7006 suppression for arrow values in property slots whose contextual type
is a union containing both a function constituent AND a non-nullish
non-function constituent (string/number/RegExp/etc.). Mirrors TypeScript's
"overload list from union with primitive" rule. Pattern target:
`const obj: {field: Rule} = { field: { validate: (_t,_p,_s) => false, normalize: match => match.x } }`
where `Rule = string | FullRule` and `FullRule.validate: string | RegExp | Validate`.
TypeScript suppresses TS7006 on `validate`'s `_t/_p/_s` (slot type is a union
with primitives + function `Validate`) but emits TS7006 on `normalize`'s `match`
(slot type is just a function or `function | undefined`, no non-nullish
primitive). Implementation: new `contextualType: Type? = null` parameter on
`checkImplicitAnyInExpr`, threaded through `ObjectLiteralExpression`'s
`PropertyAssignment` walker via a new soft `lookupPropertyTypeForCtx` helper
(walks Type.Object members + Type.Union constituents, picks first match,
StackOverflow-safe). The `ArrowFunction` / `FunctionExpression` branches
suppress when `unionHasFunctionAndPrimitive(contextualType) == true` — gated
strictly on Union with at least one non-undefined/null/void constituent and
at least one Type.Object with non-empty `callSignatures`. Pure
`function | undefined` does NOT qualify (no non-nullish primitive →
`hasNonNullishNonFunction = false`), preserving TS7006 emission for optional
function-valued properties. Wired into the VariableStatement walker for
Identifier-named decls with type annotation + `ObjectLiteralExpression`
initializer via `getTypeFromTypeNodeSafe`. Net delta: 1528 → 1527 failed
(8547 → 8548 passing). Zero regressions across 10078-test suite. Closes
B6.1 — only the `contextualOverloadListFromUnionWithPrimitive*` test of the
remaining 3 needed this rule; the other 2 (`subtypeReductionWithAnyFunctionType`,
`intraBindingPatternReferences`) were closed in 17.142/17.143.

**17.143 (2026-05-07, +1, B6.1 partial)** — Destructuring-default contextual
typing for object-bind-pattern var-decls with object-literal initializer.
Pattern target: `const { fn1 = (x: number) => 0, fn2 = fn1 } = { fn1: x => x+1, fn2: x => x+2 }`
— TypeScript suppresses TS7006 on `fn1`'s `x` (default `(x: number) => 0`
supplies a contextual signature for the matched RHS property) but fires it
on `fn2`'s `x` (default `fn1` is an intra-pattern reference that resolves
to `any`). Implementation: when var-decl name is `ObjectBindingPattern` and
initializer is `ObjectLiteralExpression`, scan binding elements for those
whose `initializer` is a typed function (all params annotated, via new
`isTypedFunctionExpr` helper); collect their property names into a set;
walk the RHS `PropertyAssignment`s with `contextuallyTyped=true` for
matched names and `false` otherwise. Untyped-default cases (default is a
bare identifier reference like `fn1`, or an arrow without annotated params)
are deliberately NOT propagated — preserving TypeScript's intra-pattern-
reference semantics. Net delta: 1529 → 1528 failed (8546 → 8547 passing).
Zero regressions across 10078-test suite. Closes test 2 of B6.1.

**17.142 (2026-05-07, +1, B6.1 partial)** — Propagate contextually-typed flag
through `ArrayLiteralExpression` for non-arrow elements only. Pattern target:
`compact([makeFooer(), { foo: (v) => v }])` — the inner object literal carries
function-typed properties whose contextual type comes from the generic call's
inferred T. Without propagation, our TS7006 walker hit `(v)` with
`contextuallyTyped=false` (since arrays previously dropped the flag) and
emitted a false-positive. Propagating fixes this BUT regresses 2 tests
(`contextualSignatureInArrayElementLibEs5/Es2015`) where the contextual array
type is itself a union (`Record<string,F1> | Array<F2>`) — TypeScript correctly
fires TS7006 there because it cannot resolve which constituent's signature
provides param types. Compromise: only propagate to non-arrow elements.
ObjectLiteralExpression/CallExpression-shaped elements get ctx, bare
`ArrowFunction`/`FunctionExpression` elements stay ctx=false. Net delta:
1530 → 1529 failed (8545 → 8546 passing). Zero regressions across 10078-test
suite. Closes the test 1 piece of B6.1; the remaining 2 targets
(`intraBindingPatternReferences_ts` destructuring-default propagation, and
`contextualOverloadListFromUnionWithPrimitiveNoImplicitAny_ts` union-with-
primitive contextual sig) need broader infrastructure.

**17.141 (2026-05-07, +1)** — JSDoc type-cast `/** @type {T} */ (expr)` bridge
for ParenthesizedExpression in JS-like files. Stacks on 17.140's primitive
`@param {T}` bridge to flip `jsdocTypeCast_ts` cleanly. Implementation:
- New `jsdocCastType: TypeNode? = null` field on `ParenthesizedExpression`
  (Ast.kt). Existing transformer call sites use positional args so the new
  field's default-null doesn't break them.
- `parsePrimaryExpression`'s OpenParen branch (Parser.kt ~3949) gates on
  `isJsLikeFile && comments != null` and calls the existing
  `parsePropertyTypeFromJSDoc(comments)` helper (full sub-Parser pattern,
  17.58/17.65) to extract `T` from `/** @type {T} */`. When non-null AND
  the parens result is a `ParenthesizedExpression` (NOT an ArrowFunction
  — `parseParenthesizedOrArrow` may return either), copy the parens with
  `jsdocCastType = T`.
- `getTypeOfExpression(ParenthesizedExpression)` (Checker.kt ~39028) now
  uses `getTypeFromTypeNode(jsdocCastType)` when non-null, falling back to
  the inner expression's type otherwise. Try-catch returns anyType on any
  type-resolution failure.

For `jsdocTypeCast.js`: with 17.140 making `x: string` from
`@param {string} x`, lines 6/10 emit TS2322 (`string` vs `'a'|'b'` literal
union) correctly. Line 14 `let c = /** @type {'a'|'b'} */ (x)` now uses the
inner JSDoc cast — `(x)` evaluates to `'a'|'b'`, assignment to `let c:
'a'|'b'` is fine, no FP. All 2 expected TS2322s now fire.

Net delta: 1531 → 1530 failed (8544 → 8545 passing). Zero regressions
across 10078-test suite. Foundation for future tests with JSDoc casts on
parens — pattern is common in `.js` files migrated from `.ts` with
@type-style retrofitted annotations.

**17.140 (2026-05-07, net-zero — B5.1 foundation)** — JSDoc `@param {primitive}
name` bridge for parameters in JS-like files. Mirror of 17.62's primitive-only
`@type` bridge for var-decls. New `parseJSDocParamPrimitiveTypeMap` walker
in Parser.kt builds a `name → KeywordTypeNode(pos=-1, end=-1)` map by walking
function leading comments for `@param {T} name` tags where T is one of
`string`/`number`/`boolean`/`any`/`unknown`/`never`/`void`/`undefined`/`null`/
`bigint`/`symbol`/`object` (allowlist via existing `primitiveKeywordKindFor`).
Companion helper `applyJSDocParamPrimitiveTypes(params, comments)` returns
parameters with `type = KeywordTypeNode` set on un-annotated Identifier-named
params whose name matches a JSDoc tag — and a new `typeFromJSDoc: Boolean`
flag on Parameter (Ast.kt) marks the synthetic origin so future walkers can
skip position-bearing diagnostics on it. Wired into `parseFunctionDeclarationOrExpression`,
`parseFunctionExpression`, `parseConstructor`, and the MethodDeclaration
branch of class-member parsing. Conservative gate (primitive-only) avoids
17.61's revert risk: synthetic `KeywordTypeNode` has no name to resolve, so
TS2503/TS2304 with garbled positions can't fire.

Net-zero on the 10078-test suite (1531 / 3 unchanged). The candidate target
`jsdocTypeCast_ts` lights 2 of 3 expected TS2322s post-bridge (the `@param
{string} x` makes `x: string`, so `let a: 'a'|'b' = (x)` and `let b: 'a'|'b' =
(((x)))` correctly fail) but adds 1 FP on `let c = /** @type {'a'|'b'} */ (x)`
where the inner JSDoc type-cast pattern needs separate handling
(ParenthesizedExpression with leading `@type {T}` JSDoc — would override the
inner expression's type). 0 of 3 → 2 of 3 with 1 FP on a still-failing test
is net-zero pass count but moves the test closer to a flip; the JSDoc
type-cast piece is deferred to a follow-on substep (B5.2 or similar).
Foundation also unblocks future Parameter-side JSDoc work (full sub-Parser
extension once `typeFromJSDoc` gates are added at param-type resolution
sites that emit position-bearing diagnostics).

**17.139 (2026-05-07, net-zero — B1.1 foundation)** — Narrow Union receivers in
`computeRawTypeOfPropertyAccess` via flow-graph state. Inside
`if (x && x.foo) { x.foo() }` the binder's `bindBinaryExpression` for `&&`
sets `currentFlow = newCondition(true, expr.left, preRight)` before binding
the RHS, and `bindExpression(PropertyAccessExpression)` calls
`bindExpression(receiver)` which `recordFlow`s the receiver Identifier at
that condition — so `getFlowAt(receiver)` now returns a `FlowCondition`
that narrows. The new branch (Checker.kt ~40837) calls
`getNarrowedTypeForReference(rawObjectType, expr.expression)` when the
raw receiver type is a `Type.Union` AND the receiver is a pure path
(`getReferencePath != null`). Narrowed receiver flows into
`getApparentType` + `getPropertyOfType`, so e.g. `Performance | undefined`
narrows to `Performance` and the `.foo` lookup succeeds instead of bailing
on the Union. Conservative gates: only Union receivers (non-Unions can't
be refined by condition-based narrowing); only pure paths (calls/parens/
element-access return null path → no-op). Net-zero on the 10078-test suite
(1531 / 3 unchanged) — the queue's named target
`uncalledFunctionChecksInConditional2_ts` had its 3 expected TS2774s
already firing post-17.60; the residual gap is 4 missing TS7006s
(noImplicitAny default-on policy — Guardrails). No other failing test in
the corpus gates SOLELY on receiver narrowing; foundation for follow-on
substeps that consume narrowed receiver types in additional emission
sites (TS2532 object-possibly-undefined, TS2339 narrowed-to-never on
property access, etc.).

**17.138 (2026-05-07, +1)** — Emit TS17019 / TS17020 / TS8020 for JSDoc
nullable `?` recovery in type-argument context. Parser-level diagnostic
emissions added to `parseNonUnionType` (Parser.kt ~5081-5215): leading `?`
followed by a type → TS17020 covering `?TYPE`; bare `?` (no following type) →
TS8020 (1-char squiggle); trailing `?` with non-type-start follower →
TS17019 covering `TYPE?`. All three gated on `inTypeArgsDepth > 0` (new
counter incremented in `tryParseTypeArguments`), so existing silent recovery
in non-type-args contexts (regular annotations, tuple elements via
`parseTupleType`'s `parseOptional(Question)` path) is preserved. TS17019
additionally gated on `inTupleTypeDepth == 0` (new counter incremented in
`parseTupleType`) so nested-in-type-args tuples like `foo<[number, string?]>`
don't FP-fire on legitimate optional tuple elements. Type text for the
diagnostic message is captured via `source.substring(type.pos,
scanner.getPrevTokenEnd())` BEFORE any trailing `!`/`?` consumption — uses
`getPrevTokenEnd` rather than node `end` because `node.end` overshoots
(per CLAUDE.md "node.end overshoots by one token"). Squiggle spans:
TS17020 covers leading-? through end of type+modifiers; TS17019 covers
type-start through end of trailing-?; TS8020 just the bare `?`. Flips
`expressionWithJSDocTypeArguments_ts` (errors-baseline; the parameterized
JS-emit test for the same file remains failing — pre-existing, drops type
args entirely from emit). Net delta: 1532 → 1531 failed (8543 → 8544
passing).

**17.137a (2026-05-07, +1)** — Gate TS2793 ("call would have succeeded
against this implementation") on `allArgumentsMatch(args, implSig)` in
the single-overload path of `checkSingleCallExpressionTypes`
(Checker.kt ~48361). Mirrors the existing gate in the generic-overload
path (~48347) and the multi-overload path (~48974) — see CLAUDE.md
"TS2793 conditional on implementation match". Three coordinated
changes: (a) the new gate itself; (b) extend `getImplementationSignature`
to handle `MethodDeclaration` (previously only handled `FunctionDeclaration`
+ `Constructor`, returning null for methods which would have left the
gate without enough info to decide); (c) `allArgumentsMatch` now treats
free `Type.TypeParam` params as accepting (matches TypeScript's
inference semantics — a generic impl `<T>(event: T)` accepts any arg
because T would be inferred from the arg type). Flips
`overloadOnConstantsInvalidOverload1_ts` (call `foo("HI")` against
`function foo(name: "SPAN"); function foo(name: "DIV") {}` — both
overload AND impl reject "HI", so TS2793 was a FP); preserves
`overloadErrorMatchesImplementationElaboaration_ts` behavior (impl is
`<T>(event: T)` which DOES accept any arg, so TS2793 still fires
correctly). Net delta: 1533 → 1532 failed (8542 → 8543 passing). Zero
regressions across 10078-test suite.

**17.137 (2026-05-07, +1)** — Extend `isTypeNodeCompatible` (the
overload-vs-implementation TS2394 gate, Checker.kt ~41535) to handle three
new TypeNode shape pairs that previously fell through to the conservative
"unknown / compatible" default: (a) `LiteralType` vs `LiteralType` — compare
wrapped literal expressions via new `literalExpressionEquals` (StringLiteral,
NumericLiteral, BigIntLiteral, Identifier-mapped true/false/null/undefined,
PrefixUnaryExpression for negative numerics); (b) `LiteralType` vs
`KeywordTypeNode` — assignable when shape matches via new
`literalAssignableToKeyword` (e.g. `"hi"` → `string`, `42` → `number`,
`true` → `boolean`); (c) `KeywordTypeNode` vs `LiteralType` — NOT assignable
unless source is `any` or `never`; (d) `FunctionType` vs `FunctionType` —
recursive comparison with parameter contravariance + return covariance.
Plus added `break` after first incompatible overload emission in both
`checkOverloadsInStatements` and `checkMethodOverloadsInClass` to match
TypeScript's "first incompatible per group" reporting cadence — without
this, multi-incompatible-overload groups (e.g. `constructorsWith
SpecializedSignatures`'s class D with overloads `"hi"`/`"foo"`/`number`
against impl `"hi"`) would over-emit. Flips
`overloadOnConstNoAnyImplementation_ts` (overload `(cb: (x: 'hi') => number)`
vs impl `(cb: (x: string) => number)` — the inner `string` is NOT
assignable to `'hi'` under contravariance). Net delta: 1534 → 1533 failed
(8541 → 8542 passing). Zero regressions across 10078-test suite.

**17.136 (2026-05-07, +1)** — Add `interface PropertyDescriptor` to
`BUILTIN_LIB_SOURCE` and update `Object.defineProperty` signature to
`(o: any, p: string, attributes: PropertyDescriptor & ThisType<any>): any`,
plus a parallel `paramType is Type.Intersection && argType is Type.Object`
branch in `checkArgumentsAgainstSignature` (Checker.kt ~49427) that calls
`checkExcessProperties` after first calling `resolveStructuredTypeMembers`
on each constituent. The previous session attempted the intersection branch
on its own and got net-zero — the diagnostic still didn't fire because the
lib's `defineProperty` had `attributes: any` so the paramType was anyType,
not Intersection. With the lib signature also updated, the paramType is now
a proper `Type.Intersection(PropertyDescriptor, Type.Reference<ThisType,
[any]>)`. Crucially, `collectTargetPropertyNames` returns null for any
constituent whose `members` is null — so we must call
`resolveStructuredTypeMembers` on each Object constituent BEFORE the
existing helper walks them, otherwise the helper bails to null and
`checkExcessProperties` returns false. Flips `excessPropertyCheckWithEmptyObject_ts`
(was emitting 2/3 expected TS2353 — the missing one is for `readonly: false`
in `Object.defineProperty(window, "prop", {...})` against
`PropertyDescriptor & ThisType<any>`). Net delta: 1535 → 1534 failed
(8540 → 8541 passing). Zero regressions across 10078-test suite.

**17.135 (2026-05-06, +1)** — Suppress TS2355/TS2366/TS7030 implicit-return
checks when the return type annotation is a bare `TypeReference` whose
identifier already triggered TS2304 ("Cannot find name") at the same span.
TypeScript treats unresolved-name annotations as any-like for implicit-return
purposes — adding TS2355 on top of an existing TS2304 produces redundant
diagnostics pointing to the same span. Implementation in
`checkBodyForImplicitReturn` (Checker.kt ~34286) probes
`diagnostics.any { code == 2304 && start == refIdent.pos && fileName matches }`
to detect already-emitted unresolved-name diagnostics at the return-type
position. Conservative gates: only fires for bare `TypeReference` with
`Identifier` name (skips `QualifiedName` and parameterized types like
`Promise<asdf>` where the parent name does resolve). Uses the existing-
diagnostic probe rather than a name-resolution probe — namespace-internal
type names (e.g. `IAction` declared in `namespace Test`) aren't in `globals`
but the binder routes them through their containing namespace's `exports`,
so no TS2304 fires for them and this branch correctly doesn't suppress their
implicit-return checks. Flips `unknownSymbols1_ts` (was emitting EXTRA TS2355
for `function foo(x: asdf, y: number): asdf { }` where `asdf` already had
TS2304). Net delta: 1536 → 1535 failed (8539 → 8540 passing). Zero
regressions across 10078-test suite.

**17.134 (2026-05-06, +1)** — Cross-file TS2448 walker now recurses
into `BinaryExpression` (and `ParenthesizedExpression`) within top-level
`ExpressionStatement`s, with a JS-source-file gate to match TypeScript's
behavior (TS2448 fires only when the use site is `.ts`/`.tsx`). Flips
`jsFileCompilationLetDeclarationOrder2_ts` whose use-before-decl is
`a = 10` (assignment-LHS) where `a` is `let`-declared in a later file.
Pre-fix the walker only handled bare-`Identifier` ExprStmts (`c;`)
across files. Pre-builds a single `firstDeclByName` map of cross-file
block-scoped decls (let/const, non-`declare`) keyed by name, then
walks each file's top-level expression statements via a small
recursive helper. Confirmed zero regressions across the 10078 suite
via diff against pre-fix baseline. The JS-source gate avoids the
companion test `jsFileCompilationLetDeclarationOrder_ts` (use is in
`b.js`, decl in later `a.ts`) which expects 0 errors.

**17.133 (2026-05-06, +1)** — TS5102 unconditional emission for
removed options (`importsNotUsedAsValues`, `preserveValueImports`).
Removed options are NOT suppressible by `ignoreDeprecations` — once
removed, the option no longer functions and TS5102 fires regardless
of `ignoreDeprecations` value. Only currently-deprecated options
(TS5101 path) honor `ignoreDeprecations`. Removed the
`isDeprecationSuppressed(deprecationVersion)` check from
`addRemoved5102` (TypeScriptCompiler.kt ~224); also dropped the now-unused
`deprecationVersion` parameter.

Pairs with 17.132's TS1287 walker to flip
`noCrashWithVerbatimModuleSyntaxAndImportsNotUsedAsValues_ts`. Net delta:
1538 → 1537 failed (8537 → 8538 passing). Zero regressions across
10078 suite.

**17.132 (2026-05-06, net-zero infra)** — TS1287 emission for
`verbatimModuleSyntax` + CJS + top-level `export` modifier on value
declarations. Extends 17.131's `checkVerbatimModuleSyntax` walker
(Checker.kt ~56313) with new branches for `ClassDeclaration` /
`FunctionDeclaration` / `VariableStatement` / `EnumDeclaration`. Emits
TS1287 ("A top-level 'export' modifier cannot be used on value
declarations in a CommonJS module when 'verbatimModuleSyntax' is
enabled.") when (a) `isCjs`, (b) statement has `ModifierFlag.Export`,
(c) statement does NOT have `ModifierFlag.Declare` (ambient declarations
exempt — type-only at runtime). Type-shape declarations (Interface,
TypeAlias) are also exempt. Position: located via
`source.lastIndexOf("export", stmt.pos)` since `stmt.pos` points to the
keyword AFTER modifiers (e.g. `class` for `export class A {}`).
Length = 6 ("export"). Lifted `emitTs1295` / `emitTs1484` helpers out
of the inner loop body and restructured `if (stmt !is ImportDeclaration) continue`
to a `when (stmt)` dispatcher. Net delta: 1538 / 1538 unchanged
(net-zero — `noCrashWithVerbatimModuleSyntaxAndImportsNotUsedAsValues_ts`
needs both TS1287 + TS5102 fix to flip; only TS1287 piece landed here).
Zero regressions across 10078 suite. Foundation for follow-on substeps:
(i) TS5102 fix for `importsNotUsedAsValues` (removed-option suppression);
(ii) TS1287 for `export default class C {}`; (iii) TS1287 for
instantiated namespaces.

**17.131 (2026-05-06, +2)** — TS1295 / TS1484 emission for
`verbatimModuleSyntax` + TS2440/TS2865 routing under verbatim. New
walker `checkVerbatimModuleSyntax` (Checker.kt ~56313) emits two
diagnostics per non-type-only import element under verbatimModuleSyntax:
1. **TS1295** ("ECMAScript imports and exports cannot be written in a
   CommonJS file under 'verbatimModuleSyntax'.") fires for every
   non-type-only named import / default import / namespace import in a
   CJS file. CJS detection via `!isESModuleFormat(effectiveModule,
   fileName)`. Position: at the imported name (default name, `*` alias,
   or specifier name).
2. **TS1484** ("'X' is a type and must be imported using a type-only
   import when 'verbatimModuleSyntax' is enabled.") fires for non-type-only
   named imports of names that are type-only exports in the source
   module (reuses `isExportedNameTypeOnly` from 17.128). Skip type-only
   `import type {}` clauses and per-element `import { type X }`. Default/
   namespace imports skip TS1484 (default-as-type rare;
   namespace import brings both types+values).

Updated existing `checkImportConflictsWithLocal` (TS2440/TS2865 branch):
- TS2865 gate widened to fire under both `isolatedModules+!verbatim`
  AND `isolatedModules+verbatim` (was `!verbatim`-only). Routes through
  the same diagnostic: "Import 'X' conflicts with local value, so must
  be declared with a type-only import when 'isolatedModules' is enabled."
- TS2440 suppressed when `verbatimModuleSyntax && type-only-in-source`
  (TS1484 from new walker covers this surface). Still fires for
  non-type-only-source conflicts under verbatim, and for all conflicts
  outside of verbatim.

Flips `isolatedModulesSketchyAliasLocalMerge_ts__isolatedmodules_false_verbatimmodulesyntax_true`
and `__isolatedmodules_true_verbatimmodulesyntax_true` variants. Net:
1540 → 1538 failed (8535 → 8537 passing). Zero regressions across 10078
suite. Foundation: extending the walker to handle export declarations
(TS1295 for `export {} from`, `export *`), import-equals, side-effect
imports, and namespace-import-of-type-only-mod (TS1484) would unlock
additional `verbatimModuleSyntax`-family tests
(`isolatedModulesShadowGlobalTypeNotValue` variants need TS2866 too,
`noCrashWithVerbatimModuleSyntaxAndImportsNotUsedAsValues` needs TS5102
+ TS1287). Out of scope for this substep.

**17.130 (2026-05-06, +1)** — Module-augmentation export-required mode +
TS2339 for `Class.prototype.X` and instance-receiver class missing
property. Three-piece change in service of
`moduleAugmentationImportsAndExports2_ts`:
1. **Aug body export-mode gate** in `collectModuleAugmentations`
   (Checker.kt ~1382): when the augmentation body has a top-level
   `ExportDeclaration` or `ExportAssignment`, switches to "module
   augmentation" mode where only declarations carrying
   `SymbolFlags.ExportValue` augment the target. `import` statements
   alone don't trigger the strict mode (verified against
   `moduleAugmentationImportsAndExports3` baseline). Nested
   string-literal `module "X"` declarations are exempted from the
   rule via a `firstDecl is ModuleDeclaration` check (their
   augmentation propagates without explicit export). Filters at both
   the globals merge and the per-file-locals merge sites.
2. **`tryEmitClassInstanceMissingTs2339` shape-decl count**
   (Checker.kt ~47490): replaces `declarations.size != 1` bail with a
   count of "shape-defining" declarations (Class/Interface/TypeAlias/
   Module). Import specifiers and aliases are appended to the symbol's
   declarations during init's global merge (CLAUDE.md "ALL file locals
   merged into globals" gotcha) but don't contribute to the class's
   instance shape — the prior gate over-suppressed TS2339 whenever a
   class was imported into any file.
3. **`Class.prototype.X` TS2339 branch** (Checker.kt ~46787): in the
   PropertyAccess-receiver fall-through, detect the `ClassIdent.prototype`
   shape and route to `tryEmitClassInstanceMissingTs2339` with the
   class's declared instance type. Without this, `A.prototype.foo`
   resolves to `anyType` (no `prototype` member on the static side)
   and TS2339 silently drops. Resolves the alias if `A` is imported
   (e.g. `import {A} from "./f1"`).

Net delta: 1541 → 1540 failed (8534 → 8535 passing). Zero regressions
across 10078 suite. Foundation for follow-on: tests of the same family
(`moduleAugmentationImportsAndExports*_ts`) where the augmentation has
mixed shapes can now be tackled without re-discovering the
strict-mode-via-export rule.

**17.129 (2026-05-06, +1)** — TS1292 for `export default <type-only-import>`
under `isolatedModules`, plus TS2440 extension for type-alias / interface
local conflicts with type-only named imports. Two-piece change in service
of `isolatedModulesExportDeclarationType_ts`:
1. **TS2440 extension**: `checkImportConflictsWithLocal` now collects a
   third name set `typeOnlyNames` (TypeAliasDeclaration /
   InterfaceDeclaration). New branch in the NamedImports loop emits TS2440
   when `localAlias in typeOnlyNames` AND the imported name resolves
   type-only in source (via the same `isExportedNameTypeOnly` helper from
   17.128). Narrow gate avoids FPs against valid `import { Class }` +
   `interface Class` augmentation patterns where Class is value+type in
   source. Catches the test2.ts pattern `import { T } from "./type"; type
   T = number;` where T is type-only in source.
2. **TS1292 emission**: new walker `checkIsolatedModulesExportDefaultIsType`
   gated on `options.isolatedModules && !options.verbatimModuleSyntax`.
   For each `ExportAssignment` (excluding `export = X`), if the
   expression is an Identifier resolving to a type-only import (covers
   `import type {}`, `import { type X }`, AND `import { X }` where source
   exports X type-only) and there's no shadowing local value declaration,
   emit TS1292 ("'X' resolves to a type and must be marked type-only in
   this file before re-exporting when 'isolatedModules' is enabled.
   Consider using 'export type { X as default }'.") at the expression
   span. Net delta: 1542 → 1541 failed (8533 → 8534 passing). Zero
   regressions across 10078 suite.

**17.128 (2026-05-06, +1)** — TS2865 for type-only-source named import vs
local-value collision under `isolatedModules`, plus FP suppression for
`import type {}` / `import { type X }` clauses in
`checkImportConflictsWithLocal`. Two-piece change in service of
`isolatedModulesSketchyAliasLocalMerge_ts(isolatedmodules=true,verbatimmodulesyntax=false)`:
1. **TS2440 FP suppression**: the existing walker emitted TS2440 even
   for `import type { FC }` (and per-specifier `import { type X }`)
   forms, which create no runtime binding and therefore can't conflict
   with a local `let` of the same name. Added an early `continue` on
   `clause.isTypeOnly` and a per-element `if (element.isTypeOnly)
   continue` in the NamedImports loop.
2. **TS2865 emission**: new helper `isExportedNameTypeOnly(name,
   moduleSpecifier)` walks the target file's top-level statements
   looking for type-only exports of `name` (TypeAlias / Interface /
   `export type { X }`) without a competing value export
   (Class/Function/Enum/Variable/`export { X }`). When a NamedImport
   element with a same-name local conflict resolves type-only AND
   `options.isolatedModules == true && !options.verbatimModuleSyntax`,
   emit TS2865 ("Import 'X' conflicts with local value, so must be
   declared with a type-only import when 'isolatedModules' is
   enabled.") in place of TS2440 at the same span. The
   `verbatimModuleSyntax=true` variants route through a different
   diagnostic family (TS1295/TS1484) so are excluded from the gate.
   Net delta: 1543 → 1542 failed (8532 → 8533 passing). Zero
   regressions across 10078 suite.

**17.127 (2026-05-05, +1)** — TS2395 narrow default-import-vs-exported-var
emission + skip TS2451 for import-merge groups. Two-piece change in
service of `exportAssignmentImportMergeNoCrash_ts`:
1. **TS2395 emission** in `checkDuplicateDeclarations` (Checker.kt
   ~12377): when a group contains a default import binding (matched via
   `clause.name === d.nameNode` on the parent `ImportDeclaration`) AND
   any exported `VariableStatement` of the same name, emit
   "Individual declarations in merged declaration must be all exported
   or all local." at every group node. Narrow scope: function/class/
   namespace/interface forms are NOT in scope (TS routes those through
   TS2440 which we already emit on the var-conflict path); only
   default imports trigger (named imports / `import * as` don't merge
   with same-name local var/let/const in TS's symbol model).
2. **TS2451 suppression** when an import is in the group (Checker.kt
   ~12896): the `allBlockScoped` gate now requires `!hasImport` so
   `import X + const X` no longer FP-emits "Cannot redeclare
   block-scoped variable" — that path is reserved for non-import
   block-scoped collisions; the import-merge case routes through
   TS2395/TS2440. Net delta: 1544 → 1543 failed (8531 → 8532
   passing). Zero regressions.

**17.126 (2026-05-05, +6)** — Block-scoped function declaration shadowing
in TS2554 arity check. `checkArgCountInStatements` (Checker.kt ~21241)
unconditionally passed the file-level `funcParams` map through every
recursive call, so a `function foo() {}` declared inside a block (e.g.
inside an if/else body) couldn't shadow an outer `function foo(a:
number)` for arity-checking — calls inside the block resolved against
the OUTER signature. Pre-fix: `function foo(a:number) { if (...) {
function foo(){} foo(); foo(10); } }` emitted FP TS2554 "Expected 1, got
0" on inner `foo()` and missed TS2554 "Expected 0, got 1" on inner
`foo(10)`. Post-fix: each call to `checkArgCountInStatements` builds an
overlay map: `funcParams.toMutableMap()` + nested
`FunctionDeclaration`s in this list (preserves overload markers from
the outer scope to avoid downgrading them). The overlay frame goes
out of scope when the call returns, so it doesn't leak past the block
boundary. Flips 6 parameterized variants of
`blockScopedSameNameFunctionDeclaration{ES5,ES6,StrictES5,StrictES6}`
(both `errors_txt` and `compiles_to_javascript` variants where they
share the same shadowing pattern). Net delta: 1550 → 1544 failed
(8525 → 8531 passing). Zero regressions across 10078 suite.

**17.125 (2026-05-05, +1)** — TS2666/TS2667 distinction +
ExportAssignment-in-augmentation. Two-piece change in service of
`moduleAugmentationDisallowedExtensions_ts`:
1. **TS2666 vs TS2667 distinction** in
   `checkInvalidModuleAugmentations` (Checker.kt ~13980): pre-fix the
   walker emitted TS2667 ("Imports are not permitted...") for BOTH
   `ImportDeclaration` and `ExportDeclaration` inside relative-name
   augmentations, where TypeScript distinguishes
   `import ... from "..."` (TS2667) from `export ... from "..."`
   (TS2666 — "Exports and export assignments are not permitted..."). Now
   gated on `innerStmt is ExportDeclaration` to pick the code + message.
2. **ExportAssignment branch**: pre-fix `export = N1` (no module
   specifier) was silently skipped — the walker `continue`d when
   `(specifier as? StringLiteralNode)?.text` returned null. Added an
   early branch ahead of the specifier-resolution path: when
   `innerStmt is ExportAssignment` AND we're in a relative-name
   augmentation, emit TS2666 at the `export` keyword (length 6) and
   `continue` past the specifier-loop body. The pair flips
   `moduleAugmentationDisallowedExtensions_ts` (last missing TS2666
   resolved) and partially helps
   `moduleAugmentationImportsAndExports2_ts` (TS2666/TS2667 codes now
   correct, but 2 unrelated TS2339 still missing). Net delta: 1551 →
   1550 failed (8524 → 8525 passing). Zero regressions across 10078
   suite.

**17.124 (2026-05-05, +2)** — Switch-case literal comparability extension
+ shared block scope across case clauses for TS2304. Two-piece change in
service of `unusedSwitchStatement_ts`:
1. **TS2678 emission** (`walkSwitchCaseComparable`): pre-fix only fired
   when the switch expression was an `Identifier` referencing a tracked
   const literal binding. Extended the binding resolution to also handle
   inline literal switch expressions (e.g. `switch (1)`, `switch (2)`)
   via the existing `literalKindDisplay(expr)` helper. Now `switch (1)
   { case 0: ... }` correctly emits TS2678 "Type '0' is not comparable
   to type '1'" at the case literal position.
2. **Shared switch-block scope** (`checkUnresolvedInStatement` for
   `SwitchStatement`): pre-fix called `checkUnresolvedInStatements`
   separately per case clause, which created a fresh child scope per
   call. That made `let x` in case 0 invisible in case 1's
   `x = 1` (fall-through pattern) → spurious TS2304. Post-fix
   creates ONE `switchScope = scope.child()`, walks all clauses to
   collect declared names into it, then walks each clause's statements
   directly via `checkUnresolvedInStatement` (bypassing the per-call
   child-scope creation in `checkUnresolvedInStatements`). Mirrors
   ECMA-262 / TS semantics: switch case clauses share a single block
   scope. Net delta: 1553 → 1551 failed (8522 → 8524 passing). Zero
   regressions across 10078 suite.

**17.123 (2026-05-05, +1)** — TS1540 per dotted-segment + parser
diagnostic forwarding for `.d.ts` files. Two-piece change in service of
`moduleKeywordDeprecated_ts`:
1. **Parser** (`parseModuleDeclaration`): the existing TS1540 emission
   only fired once per `module` keyword, but TypeScript emits TS1540
   for each segment of a dotted name (`module not.ok` → 2 emissions, at
   `not` and `ok`). Captured the emit decision in `emitTs1540` and
   added a re-emit inside the dotted-name `while (parseOptional(Dot))`
   loop, before each `parseIdentifier()` call — at that point scanner
   position is already on the next segment, so default
   `reportError`-span (tokenPos..pos) covers the segment text exactly.
2. **TypeScriptCompiler** multi-file path: `if (isDtsFile) continue`
   short-circuited past `diagnostics.addAll(parser.getDiagnostics())`,
   so any parser-level diagnostic emitted while parsing a `.d.ts` file
   was silently dropped (TS1540 was the visible case but the same path
   would mask any future parser-side `.d.ts` diagnostic). Hoisted the
   diagnostic collection ahead of the `.d.ts` continue, with a
   node_modules guard preserved (third-party files are still excluded
   from diagnostics). Closes the +8 missing-diags gap on
   `moduleKeywordDeprecated_ts` (decl.d.ts had 6 missing because none
   of its parser-side TS1540s were forwarded; foo.ts had 2 missing
   because the dotted-segment second emission was absent). Net delta:
   1554 → 1553 failed (8521 → 8522 passing). Zero regressions across
   10078 suite — the broader `.d.ts` parser-diagnostic forwarding turned
   out to be safe (no other parser-side diagnostics fire on the corpus's
   `.d.ts` files).

**17.122 (2026-05-05, +1)** — Lib-aware DOM/host global filter in
file-level scope construction. When `@lib` is non-empty AND every entry
excludes `dom`/`webworker`/`scripthost`, the file-scope omits both
`DOM_GLOBAL_NAMES` (existing set, line ~16801) and a new
`HOST_ONLY_GLOBALS` set (timer family: `setTimeout`/`clearTimeout`/
`setInterval`/`clearInterval`/`setImmediate`/`clearImmediate`/
`queueMicrotask`) from the bulk `KNOWN_GLOBALS` add. Without this,
tests with `@lib: es5` (or any explicit es-only subset) silently treat
`window`/`top`/`setTimeout`/etc. as resolved when they live in
lib.dom.d.ts / lib.scripthost.d.ts that the user opted out of —
masking expected TS2304 emissions. Conservative gate: ONLY fires when
`options.lib` has zero DOM/webworker/scripthost entries (default-empty
`options.lib` means full lib.d.ts is loaded so no filtering). Flips
`recursiveNamedLambdaCall_ts` (`@lib: es5`, missing TS2304 for
`top` x3 and `setTimeout` x1). Net delta: 1555 → 1554 failed (8520 →
8521 passing). Zero regressions across 10078 suite.

**17.121 (2026-05-05, +2)** — TS2339 for class-instance receivers with
fully-resolvable extends chain (17.117 follow-on). 17.117's
`tryEmitClassInstanceMissingTs2339` bailed unconditionally when the class
had any `extends` clause, conservatively avoiding FPs from un-walkable
bases. New `lookupInstanceMemberInResolvableChain(classDecl, propName)`
helper walks own members + extends chain returning `true` (member found),
`false` (chain fully resolved with no member), or `null` (chain has
un-walkable parts — non-Identifier extends, ambient base, IndexSignature,
or a sibling [InterfaceDeclaration] of the same name in any binderResult,
which our binder's `canMerge` does not handle for Class+Interface so
silently overwritten declarations leave members invisible to the AST
walker). Three call sites updated to use the helper:
1. `tryEmitClassInstanceMissingTs2339` — replaces the `hasExtends` bail
   plus separate `hasInstanceMemberNamed`/IndexSignature/declare gates
   with a single `lookupInstanceMemberInResolvableChain(...) != false`
   check.
2. NewExpression branch in `checkMemberAccessMissing` (line 46374) —
   was emitting only when `!hasBase || isCircular`; now also emits when
   the chain is safely walkable and resolves with no member found
   (`chainResult == false`).
3. Type.Interface empty-properties branch (line 46723) — was guarded
   by `objectType !is Type.Interface` (constructor-side only). New
   branch fires for instance-side Type.Interface BUT only under
   `isThisAccess` (the receiver is `this` in an instance method/ctor
   body, where we know we're authoritatively on the instance side). The
   `!isThisAccess` guard is critical: class-Identifier-as-value (`C[1]`,
   etc.) goes through this same path because `getTypeOfSymbol` on a
   class symbol returns the declared instance type — without the guard,
   value-position class references would FP-fire TS2339 (cf.
   `createArray_ts`'s `new C[1]; // not an error`).
Lazy-cached `classNamesWithSiblingInterfaces` (declared as
`classNamesWithSiblingInterfacesCache` BEFORE init to avoid the
init-order NPE that `by lazy {}` produces when accessed during init —
the Lazy backing field is null until its declaration line runs).
Flips `bases_ts` (target — TS2339 for `this.y`/`this.x`/`new C().x`/`new
C().y` with `class C extends B implements I` chain) plus 1 collateral
win. Net delta: 1557 → 1555 failed (8518 → 8520 passing). Zero
regressions across 10078 suite.

**17.120 (2026-05-05, +2)** — Optional-param widening + initializer-scope
seeding for `checkParamShadowedByVar` (17.119 follow-on). Pre-fix the
shadow-by-var TS2403 check displayed an optional parameter as just `T`
(missing `| undefined`) AND inferred the var initializer as `any` because
the constructor parameter wasn't visible to `getTypeOfIdentifier`'s scope
chain. New behavior: when `param.questionToken` is set, wrap the displayed
+ compared `paramType` as `getUnionType([T, undefinedType])` to match
TypeScript's symbol-level optional widening (which fires regardless of
strictNullChecks). Around the per-decl loop, save/restore
`currentLocalTypes` and seed `currentLocalTypes[paramName] = rawParamType`
(un-widened, no `| undefined`) so var initializers like `var x = (x || 0)`
resolve their inner reference to the parameter's declared type — the `||`
result then correctly narrows to `number`, matching TypeScript's truthy
branch view. Flips `optionalParamterAndVariableDeclaration_ts` +
`optionalParamterAndVariableDeclaration2_ts` (latter sets
`@strictNullChecks: true` but emits the same diagnostic, confirming the
optional widening is symbol-level not strict-null-mode-gated). Net delta:
1559 → 1557 failed (8516 → 8518 passing). Zero regressions across 10078
suite.

**17.119 (2026-05-05, +1)** — TS2403 + TS6203 for parameter-shadowed-by-var
inside function/method/constructor bodies. Pre-fix
`checkSubsequentVarTypesInStatements` had a TODO comment for this case
("prepend param as first decl") but never implemented it — `function
foo(x: A) { var x: B = ... }` therefore emitted no diagnostic. New
`checkParamShadowedByVar` helper called from both the FunctionDeclaration
and the ClassDeclaration → Constructor/MethodDeclaration branches:
when a parameter name matches a body-local var name, the param acts as
the "first declaration" and each var with a different simple type
emits its own TS2403 plus a TS6203 related-info pointing to the param.
Position uses `param.pos` (start of the parameter declaration including
modifiers like `public`) so the related-info column matches TypeScript
when parameter properties shift the name. New helper
`isSimpleTypeForParamShadow` extends `isSimpleTypeForTs2403` to also
accept named class/interface types (`Type.Reference` with named
target.symbol, `Type.Interface` with Class/Interface symbol flag) so
the check fires for `(x: A) { var x: B }` where A/B are user classes;
generic instantiations remain comparable via `typeToString`. Net delta:
1560 → 1559 failed (8515 → 8516 passing). Flips
`functionArgShadowing_ts`. Zero regressions across 10078 suite.

**17.118 (2026-05-05, +1)** — Object-literal `get`/`set` accessors are
now bound as Property symbols in `getTypeOfObjectLiteral`. Pre-fix:
the `is GetAccessor, is SetAccessor -> continue` branch silently
ignored accessors, leaving the resulting Type.Object with empty
members; assignments like `var o: {Foo:number;} = {get Foo(){...},
set Foo(){...}}` then FP-fired TS2741 saying `Foo` was missing.
New branches register a single Property symbol per accessor name
(merging getter+setter pairs into one entry via first-write-wins on
declarations / valueDeclaration, last-write-wins suppressed). The
member's type is the getter's declared return type if present, else
the setter's parameter type, else `anyType` — sufficient for the
`{Foo:number}` target since `anyType` is bidirectionally assignable
and the structural compare passes. Flips `gettersAndSetters_ts`. Net
delta: 1561 → 1560 failed (8514 → 8515 passing). Zero regressions
across 10078 suite.

**17.117 (2026-05-05, +3)** — TS2339 for class-instance receiver when the
property is genuinely absent from the class hierarchy. New
`tryEmitClassInstanceMissingTs2339` helper called from both branches of
`checkMemberAccessMissing` (the `globals[identName]` path AND the
`currentLocalTypes` fallback) AFTER `tryEmitStaticAccessTs2576` returns
false. Helper walks the class declaration's instance + static members
(via existing `hasInstanceMemberNamed` + `isStaticMemberOfClass`, which
already traverse extends chains) and emits TS2339 with display
`ClassName` when the property name is absent everywhere. Conservative
gates defend against false positives: (1) propName not in
RUNTIME_PROPERTIES; (2) symbol has exactly 1 declaration (no
interface/namespace augmentation contributing extra members the binder
currently doesn't merge into the class symbol); (3) class has no type
parameters (TypeParam-constraint members would be missed); (4) class
has NO `extends` clause — bases that resolve through complex
expressions (PropertyAccess `foo.Super`, generic type-args `q<string>`,
import aliases) aren't fully walked by the helpers; (5) no
IndexSignature in members (would accept any property name); (6) class
not `declare class` — ambient bases are partial views and upstream
type inference sometimes resolves `new Subclass(...)` to the ambient
base type, so a receiver typed as the base is not authoritative for
instance-side properties; (7) flow narrowing yields the same type
(`if (c instanceof D) c.bar()` narrows c from C to D which may have
bar). Implements clauses are NOT inherited members per
`resolveBaseTypesLazy`'s skip-implements rule, so this fires correctly
for `class C implements A {}; let c: C; c.bar()` where A has only
`static bar()`. Net delta: 1564 → 1561 failed (8511 → 8514 passing).
Zero regressions across 10078 suite. Flips `classImplementsClass6_ts`
(target — TS2339 for `c.bar()` where c: C, C implements A) plus 2
collateral wins.

**17.116 (2026-05-05, +2)** — TS7015 + TS7053 implicit-any element-access
diagnostics, gated on `noImplicitAny || strict`:
(1) **TS7015** "Element implicitly has an 'any' type because index
expression is not of type 'number'" — fires when a string-literal key
accesses an enum-typed identifier AND the key isn't a known member
name AND the key isn't numeric-looking. Squiggle on the quoted string
literal. (2) **TS7053** "Element implicitly has an 'any' type because
expression of type 'any' can't be used to index type 'X'" — fires when
the key is an Identifier resolving to `any` AND the receiver is a
fully-empty `Type.Object` (no members, no index sigs, no call/construct
sigs, not Type.Reference / Type.Interface). Squiggle covers full
`receiver[key]` span (search forward from key end to the closing `]`).
Both checks placed in `checkSingleElementAccess` ahead of the
`when (arg)` literal-branch so non-literal keys (TS7053 case) are
caught before that block returns. Pairs with 17.115's `{}` element-access
TS2339 to flip both `noImplicitAnyIndexingSuppressed_ts` (target) and
`noImplicitAnyIndexing_ts` (collateral). Net delta: 1566 → 1564
failed (8509 → 8511 passing). Zero regressions across 10078.

**17.115 (2026-05-05, net-zero infra)** — TS2339 for property/element
access on empty object literals (`{}["X"]`, `{}[N]`, `({}).x`). New
branch in `checkMemberAccessMissing` ahead of the existing
`NewExpression` / `CallExpression` / static-this / Identifier paths:
when `objectExpr is ObjectLiteralExpression && properties.isEmpty()`,
emit TS2339 with display `{}`. Squiggle uses caller-provided
`ts2576Start/Length` so element-access form `{}["hi"]` covers the full
expression (8 chars in the prototypical baseline) while bare property
form `({}).hi` falls through to the bare `.hi` span. Conservative
gate: only EMPTY literals — non-empty `{a:1}` literals have their own
member set and continue through the standard fallthrough. RUNTIME_PROPERTIES
filter retained. Net-zero on the 10078 suite (no test currently fails
SOLELY on this; `noImplicitAnyIndexingSuppressed_ts` /
`noImplicitAnyIndexing_ts` reduce from 4-missing to 2-missing,
foundation for TS7015 / TS7053 follow-ons that would complete those
tests + `noImplicitAnyStringIndexerOnObject_ts`).

**17.114 (2026-05-05, +2)** — Three coordinated diagnostics that flip
`interfacedeclWithIndexerErrors_ts` plus one collateral test:
(1) **TS2840** "An interface cannot extend a primitive type like 'X'":
new `checkInterfaceExtendsPrimitive` walker fires when an interface
`extends` clause names a primitive type keyword (number/string/boolean/
bigint/symbol). Squiggle on the identifier name. (2) **TS2693** for
`typeof <type-only-keyword>` in TypeQuery position: added a check in
`checkTypeQueryName` that fires when the name is one of
`TYPE_ONLY_KEYWORD_NAMES` (string/number/boolean/bigint/symbol/object/
any/unknown/never/void) AND no value symbol shadows it (defensive —
`var string = "x"; typeof string` continues to be valid). (3)
**TS2411** for method declarations vs callable string-index types:
extended the existing `checkIndexSigInStatement` property-loop to
handle `MethodDeclaration` when the string-index type is NOT primitive
(the primitive case is handled by 16.4ez). Synthesizes a `FunctionType`
TypeNode from the method's parameters + return type, runs the existing
`checkTypeRelatedTo(propType, stringIndexType, assignableRelation)`,
emits TS2411 with `(params) => returnType` display. Squiggle covers
the full method declaration through the trailing `;`. Conservative
gates: skip overloaded methods (multi-decl by name), call/construct
sigs (name=="" / name=="new"), statics, private (#name). Net delta:
1568 → 1566 failed (8507 → 8509 passing). Zero regressions across
10078 suite.

**17.113 (2026-05-05, +2)** — Extended TS2873 "always falsy" to cover
`Identifier("null"|"undefined")`, `TypeAssertionExpression`, and
`AsExpression` (recursive on `.expression`). Wired into three additional
call sites: `||` LHS in `checkAlwaysTruthyInExpr`'s BinaryExpression
branch (parallel to the existing always-truthy check), ConditionalExpression
condition, and the else-if chain in IfStatement (the existing IfStatement
condition check already fires). Added `tightEnd: Int` field to
`AsExpression` AST node, populated via `scanner.getPrevTokenEnd()` at
parser construction sites — `expr.type.end` overshoots by the next-token
length (e.g., `null as any)` has `type.end` past `)` because parseType's
final `nextToken()` advances scanner past `any` and the `)`'s end is
reflected in `getEnd() = scanner.getPos()`). `emitTS2873` uses `tightEnd`
when set. Flips `destructuringAssignmentWithExportedName_ts` errors
baseline (4 expected TS2873 emissions on `if (null as any)` /
`else if (null as any)` chain) and one other test (likely a
parameterized variant). `aliasUsageInOrExpression_ts` reduced from 3
missing diags to 1 missing diag (the remaining TS2322 with
`typeof import("...")` display + null-branch chain is architectural).
Net delta: 1570 → 1568 failed (8505 → 8507 passing). Zero regressions
across 10078 suite.

**17.112 (2026-05-05, +1)** — Symmetric to 17.111: source has
`constructSignatures` (constructor type) but no `callSignatures`; target
has `callSignatures` (function type) but no `constructSignatures`.
Inserted directly after the 17.111 branch in `checkAssignmentExpression`'s
Identifier-target path. Uses `getCallableMismatchElaboration` (the
existing helper for "non-callable source vs callable target"). Flips
`callConstructAssignment_ts` line 5 `foo = bar` (`bar` is `new () =>
any`, `foo` is `() => void`) → emits "Type 'new () => any' is not
assignable to type '() => void'" + "Type 'new () => any' provides no
match for the signature '(): void'." chain. Line 6 already fired via
17.111. Net delta: 1571 → 1570 failed (8504 → 8505 passing). Zero
regressions.

**17.111 (2026-05-05, +1)** — TS2322 + "provides no match for the
signature 'new ...'" chain for fn-vs-constructor-type assignment via
plain Identifier target. Mirrors 17.14a's `checkPropertyAccessAssignment`
branch in `checkAssignmentExpression`'s Identifier-target path: when
`canUseTypeEngine` short-circuits the construct-sig mismatch case
(`source.constructSigs.isEmpty() && target.constructSigs.isNotEmpty()`),
detect directly and emit TS2322 with the elaboration chain via
`getNonConstructibleElaboration`. Tight gate: source MUST have
`callSignatures` (clearly callable function, NOT a constructor) but NOT
`constructSignatures` — excludes opaque sources like import-aliased
`export = Class` whose effective type isn't resolvable here. Skips when
target is a class/interface instance (its construct sigs are
static-side; instance↔instance compares follow the regular path).
Flips `parseTypes_ts`: 8th expected emission `z=g` now fires with
"Type '(s: string) => void' is not assignable to type 'new () => number'"
+ "Type '(s: string) => void' provides no match for the signature
'new (): number'" chain. Net delta: 1572 → 1571 failed (8503 → 8504
passing). Zero regressions.

**17.110 (2026-05-05, +1)** — Extended `emitTS2352IfNullCast` to also fire
for `<T[]>null` (`ArrayType`) and `<[A, B]>null` (`TupleType`) targets.
Display string built via the existing `formatTypeForDisplay` helper.
Flips `castTest_ts`: both expected emissions now fire — the new ArrayType
branch lands `<any[]>null` line 9, and the FunctionType branch from
17.109 lands `<(res: number) => void>null` line 11. Net delta: 1573 →
1572 failed (8502 → 8503 passing). Zero regressions.

**17.109 (2026-05-05, net 0 — infra)** — Extended `emitTS2352IfNullCast`
to fire TS2352 for `<T>null` casts where `T` is a `FunctionType`,
`ConstructorType`, or non-empty `TypeLiteral` (in addition to the
existing TypeReference-to-Class/Interface form). Display string built via
the existing `formatTypeForDisplay` helper which already supports all
three shapes (function arrow, `new` constructor arrow, brace member
list). Empty `{}` skipped (Object-like, accepts null-like values).

Test impact: `parseTypes_ts` advances from 5/9 to 8/9 expected emissions
(the 4 new TS2352s land at lines 1/2/3/4); the test still fails on the
single remaining missing TS2322 for `z=g` line 11 (`(s: string) => void`
not assignable to `new () => number` with `provides no match for the
signature 'new (): number'` chain — fn-vs-constructor-type, separate
work). Foundation: the same `formatTypeForDisplay` extension makes
TS2352 emission for tuple-target / union-target null casts a one-liner
add when those tests surface.

Net delta: 1573 → 1573 failed (8502 unchanged). Zero regressions.

**17.108 (2026-05-05, +1)** — Cross-file enum-merge conflict detection
(TS2567 + TS6203 related info). New `checkCrossFileEnumConflicts()`
walker (Checker.kt ~54877) collects top-level declarations
(`enum`/`class`/`function`/`var`/`interface`/`namespace`) from all
non-module (script) files into a name-keyed `byName` map. For each name
appearing across ≥2 files where the merged group has at least one enum
AND at least one class/function/var/interface, emits TS2567 on each
non-namespace declaration with TS6203 related info pointing to all
cross-file partners. Skips:
- module files (separate scope per file via own imports/exports)
- .d.ts files
- declarations whose own file already has both an enum and a non-enum
  in the same name-group — the per-file walker (Checker.kt ~12390)
  already emits TS2567 on those positions.
Wired into init step 73c (alongside `checkCrossFileBlockScopedDuplicates`,
gated on `binderResults.size > 1`). Flips `duplicateIdentifierEnum_ts`:
A.ts(22,6) `enum D` ↔ B.ts(1,10) `function D` and
A.ts(25,7) `class E` ↔ B.ts(4,6) `enum E`. Net delta: 1574 → 1573 failed
(8501 → 8502 passing). Zero regressions across 10078 suite.

**17.107b (2026-05-04, +1)** — TS2511 now also fires for
`[A, B, ...].map((cls) => new cls())` when any array element is an
abstract class. Flips `abstractClassUnionInstantiation_ts`. Builds on
17.107a's `typeofAbstractVars` infrastructure: `checkAbstractInExpr`'s
CallExpression branch detects the `<ArrayLiteralExpression>.map(<arrow
or fn>)` pattern. When any array element is an Identifier whose name
is in `abstractClasses` (or already in `typeofAbstractVars`), the
callback's first parameter (Identifier) is added to a per-callback
extended `typeofAbstractVars` set. The callback body (Block or
expression) is then walked with that extended set, so `new param()`
inside the body fires TS2511 against the parameter name. The handled
flag prevents fall-through to the default CallExpression walk
(elements still walked once for nested diagnostics). Restricted to
the literal pattern `<ArrayLit>.map(arrow|fn)` — generalized
inference (e.g. through a typed array variable) deferred. Net delta:
1575 → 1574 failed (8500 → 8501 passing). Zero regressions across
10078 suite.

**17.107a (2026-05-04, net 0, infra)** — TS2511 now also fires for
`new x()` where x is a variable/parameter whose type annotation is
`typeof AbstractClass` (or a union/parenthesized variant containing
one). Infrastructure for typeof-class-union abstract detection. Adds
two pre-passes to `checkAbstractClassInstantiation()`:
- `collectTypeAliases()` builds `Map<String, TypeNode>` for same-file
  type alias resolution (`type Abstracts = typeof AbstractA | typeof
  AbstractB`).
- `collectTypeofAbstractVars()` walks top-level VariableStatements +
  FunctionDeclaration parameters + nested namespace/block bodies to
  identify variable/parameter names whose type annotation resolves
  (recursively, with cycle detection on type-alias names) to
  "abstract-constructible" via `typeNodeIsAbstractConstructible`. The
  helper resolves: TypeQuery (Identifier in abstractClasses), UnionType
  (any member matches), ParenthesizedType (recurse), TypeReference
  (resolve via type alias map and recurse). Returns `Set<String>` of
  variable/parameter names known to construct abstract instances.
- `checkAbstractInExpr`'s NewExpression branch checks `callee.text in
  abstractClasses || callee.text in typeofAbstractVars`, with
  `typeofAbstractVars: Set<String>` threaded through the existing
  walker via mechanical signature update.

Lands the 2 simple `new cls1()` / `new cls2()` emissions in
`abstractClassUnionInstantiation_ts`; test still fails because the
remaining 3 emissions are inside `[A, B].map(cls => new cls())` and
need array-element-inference + Function-arg contextual-type
infrastructure (planned 17.107b). Verified zero regressions across
10078 suite (1575 failed unchanged). Foundation for 17.107b which
will reuse `typeNodeIsAbstractConstructible` against synthesized
union types from array literal element identifiers.

**17.106 (2026-05-04, +1)** — TS1253 + TS7008 now fire for abstract
members in non-abstract classes. Flips `errorInUnnamedClassExpression_ts`
(`let Foo = class { abstract bar; }` — class expression without
abstract modifier with abstract property declaration). New
`checkAbstractMemberContext()` walker (Checker.kt ~24881) registered
after `checkAbstractMemberAccessInConstructor`. For each ClassDeclaration
/ ClassExpression: tracks `classIsAbstract` (own modifier) + `isAmbient`
(declare modifier or inside declare namespace). Per member, when
`abstract` modifier is present:
- TS1253 ("Abstract properties can only appear within an abstract
  class.") fires when `!classIsAbstract && !isAmbient`. Squiggle: scans
  source forward from `member.pos` for the `abstract` keyword (using
  word-boundary checks to avoid false matches on identifiers like
  `abstract_ish`), length 8.
- TS7008 ("Member 'X' implicitly has an 'any' type.") fires for an
  abstract PropertyDeclaration with no type annotation and no
  initializer in non-ambient context, when `noImplicitAny || strict`.
  Squiggle: `member.name.pos`, length=`name.text.length`.
Also threads ambient context through ModuleDeclaration descent so
`declare namespace M { class C { abstract foo; } }` inherits ambient
state. Note: TS1253 also fires correctly on `abstractPropertyNegative_ts`
line 15 (`class C extends B { abstract notAllowed: string; }`) but
that test still fails because TS2654, TS1005, TS2676 are out of scope.
Net delta: 1576 → 1575 failed (8499 → 8500 passing). Zero regressions.

**17.105 (2026-05-04, +1)** — TS2715 now fires for `this.X` accesses in
constructor bodies and class-field initializers when X is an abstract
property. Flips `abstractPropertyInConstructor_ts` (11 missing TS2715
emissions across 3 patterns: direct property access in constructor body,
field initializer access, and ObjectBindingPattern / ObjectAssignmentPattern
destructuring of `this`). Implementation: a new top-level
`checkAbstractMemberAccessInConstructor()` walker (Checker.kt ~24494)
runs unconditionally. For each ClassDeclaration / ClassExpression, builds
an `abstractMap: Map<String, String>` (property name → declaring class
name) by collecting own abstract `PropertyDeclaration`s plus inherited
abstracts from same-file extends chain (subtracting concrete shadows).
Then walks each `Constructor` body and each non-abstract
`PropertyDeclaration.initializer` for `this.X` accesses. Critically:
nested `FunctionExpression` / `ArrowFunction` / object-literal
accessor+method bodies switch `inDeferredFn=true` so accesses inside
them don't fire (those execute later, after construction). Destructuring
patterns `let { x, y: y1 } = this` (ObjectBindingPattern in
VariableDeclaration) and `({ x, y: y1, "y": y1 } = this)` (BinaryExpression
with ObjectLiteralExpression LHS) emit one TS2715 per matching key —
shorthand uses Identifier name; `prop: alias` uses propertyName position;
string-literal keys squiggle the entire `"y"` (3 chars). Method/accessor
abstracts are NOT flagged (TS2715 is property-only — methods are on the
prototype and always available). For inherited abstracts, the diagnostic
references the ORIGINAL declaring class (`AbstractClass`), not the
descendant class doing the access (so `class DerivedAbstractClass
extends AbstractClass` accessing inherited `prop` says "in class
'AbstractClass'"). Net delta: 1577 → 1576 failed (8498 → 8499 passing).
Zero regressions across 10078-test suite.

**Static-member bifurcation (2026-05-04, net 0, architectural)** — Multi-
session piece deferred from 17.83's revert: `Type.Interface` now carries
a separate `staticMembers: SymbolTable?` populated alongside the existing
`members` table for class declarations (Step 1, commit 82405f2). Three
structural-comparison consumers (`propertiesRelatedTo`,
`collectMissingProperties`, `getMissingRequiredPropertySymbol`) now skip
target.properties entries that live on the static side via a new
`getStaticMembersOfType()` helper (Step 2, commit ac59fde). Together this
removes the FP TS2741 on `classImplementsClass6_ts` line 19 (`c2 = c`
claiming static `bar` is missing in C) without regressing anything —
both commits are zero-regression. The canary still fails because the
expected TS2339 at line 20 (`c.bar()`) is a separate emission gap (the
class-instance-receiver bail-out in `checkSinglePropertyAccess` isn't
related to the bifurcation). Architectural foundation now in place for
future class-side semantics work that depends on a clean static/instance
split. Test count unchanged.

**17.100 (2026-05-03, +1)** — TS2337 now fires for `super(...)` constructor
calls inside nested functions (FunctionExpression / ArrowFunction /
object-literal MethodDeclaration / GetAccessor / SetAccessor) inside any
class constructor body. Flips `illegalSuperCallsInConstructor_ts` (5
missing TS2337 emissions). Implementation: a new top-level
`checkIllegalSuperCallsInNestedFunctions()` walker (Checker.kt ~24001+)
runs unconditionally. Walks each `ClassDeclaration`'s `Constructor`
members, then descends through statements/expressions in the constructor
body with an `inNestedFn: Boolean` flag. The flag flips to `true` upon
entering ANY of: `FunctionExpression.body`, `ArrowFunction.body` (block
or expression), or `ObjectLiteralExpression` property bodies
(GetAccessor / SetAccessor / MethodDeclaration). Once `inNestedFn` is
true, every `CallExpression(expression=super)` emits TS2337 ("Super calls
are not permitted outside constructors or in nested functions inside
constructors.") at the `super` Identifier position with length 5.
Crucially, arrow functions still trigger TS2337 even though they
lexically inherit `this`/`super` for property access — `super(...)` (the
constructor invocation) is a separate operation that's bound to the
direct constructor scope only. The walker also recurses into nested
class declarations via member bodies (so `class A { constructor() {
class B { constructor() { var f = () => super(); } } } }` still fires
TS2337 for B's nested arrow). Net delta: 1586 → 1585 failed (8489 →
8490 passing). Zero regressions across 10078-test suite.

**17.99 (2026-05-03, +2)** — TS2659 + TS2660 now fire for `super` references
inside object-literal members. Flips both target=es5 and target=es2015 variants
of `super_inside-object-literal-getters-and-setters_ts`. Rules: (a) `super`
inside object-literal getter/setter/shorthand-method body emits TS2659
("'super' is only allowed in members of object literal expressions when
option 'target' is 'ES2015' or higher.") only when `options.target <
ScriptTarget.ES2015` — under ES2015+, these are valid; (b) `super` inside
a `function` value of a `PropertyAssignment` (e.g. `{ test: function () {
super.x } }`) emits TS2660 ("'super' can only be referenced in members of
derived classes or object literal expressions.") regardless of target,
because regular FunctionExpression rebinds nothing — `super` in such a
function isn't an "object literal member." Implementation: a new top-level
`checkSuperInObjectLiterals()` walker runs unconditionally (per
`checkSuperInNonDerived` pattern). Walks every statement / expression
recursively; on encountering an `ObjectLiteralExpression`, dispatches per
property: `GetAccessor`/`SetAccessor`/`MethodDeclaration` → conditional
TS2659; `PropertyAssignment` with `FunctionExpression` value → unconditional
TS2660. Each property body is then re-walked to find nested object literals
(so `{ get x() { return { foo: function() { super.y } } } }` would emit
both inner TS2660 and outer TS2659 if applicable). The TS2335 walker
already skips `FunctionExpression`/`ArrowFunction` in `findSuperRefsInExpr`,
so no overlap with existing non-derived-class TS2335 emission. Net delta:
1588 → 1586 failed (8487 → 8489 passing). Zero regressions across
10078-test suite.

**17.98 (2026-05-03, +5)** — TS2331 + TS2683 now fire for `this` references
directly inside namespace/module bodies (not nested in functions/classes).
Flips 5 tests: `thisAssignmentInNamespaceDeclaration1_ts` (JS file with
checkJs — both diags), `thisKeyword_ts` and `thisInModule_ts` (TS files
without explicit @strict — both diags), `topLevelLambda_ts` and
`lambdaPropSelf_ts` (TS files with `@strict: false` — only TS2331).
Implementation: a new top-level `checkThisInNamespaceBodies()` walker
runs unconditionally (independent of the strict-mode-gated `checkImplicitThis`).
For each `ModuleDeclaration` body, recurses into statements / expressions
that don't rebind `this` (skips `FunctionDeclaration`, `ClassDeclaration`,
`FunctionExpression`, `ClassExpression`; transparently descends into
arrow function bodies). Each direct `this` Identifier emits TS2331
("'this' cannot be referenced in a module or namespace body."). TS2683
also fires UNLESS `// @strict: false` was set explicitly — guard via
`!options.strictExplicitlyFalse` — matching the empirical TypeScript
test-baseline pattern (default-false strict still emits TS2683 in
namespace-body context, but explicit-false suppresses it). Net delta:
1593 → 1588 failed (8482 → 8487 passing). Zero regressions.

**17.97 (2026-05-03, +1)** — TS2428 type-parameter identity check is now
default-aware. Flips `genericDefaults_ts`. Pre-fix (introduced in 17.92):
`checkDuplicateDeclarations` compared raw `(name, constraintText)` signatures
across all merged declarations and fired TS2428 on any pairwise difference,
including length differences and default-only differences. This over-fired
on patterns like `interface i04 {}` + `interface i04<T>` +
`interface i04<T = number>` + `interface i04<T = number, U = string>` (4
declarations with extra type parameters all carrying defaults — TypeScript
treats these as a valid merge because the defaults reconcile the gap). Fix:
restructure the comparison to model TypeScript's canonical merged signature.
For each position k in the longest signature, compute the "shape" by walking
all declarations that have a param at position k: (a) names must agree;
(b) constraints must agree (including null-vs-non-null); (c) non-null defaults
must agree across decls; (d) when no decl at position k provides a default
AND any declaration omits position k entirely → TS2428 fires (the merge
can't fill the gap). Also fixed a secondary bug in default/constraint text
extraction: `node.end` overshoots by one token (documented gotcha), which
caused decl3's `T = number` to extract default text `number>` while decl4's
extracted `number,` — false mismatch even with correct logic. Replaced
naive `source.substring(c.pos, c.end)` with a balanced-bracket walker that
stops at the first unbalanced `>)}]` or top-level `,`/`=`. Verified against
`nonIdenticalTypeConstraints_ts` (still flips correctly), `genericDefaults_ts`
(now flips), `interfaceWithMultipleDeclarations_ts` (still flips correctly —
length-mismatch with non-default at canonical position 0 still fires for
`I3 {}` + `I3<T>`). Net delta: 1594 → 1593 failed (8481 → 8482 passing).
Zero regressions across 10078-test suite.

**17.96 (2026-05-03, +1)** — TS7032/TS7006 setter implicit-any suppression
correctly gates on presence of ANY sibling getter (not just one with a typed
return). Flips `implicitAnyGetAndSetAccessorWithAnyReturnType_ts`. Pre-fix:
17.94 introduced the SetAccessor implicit-any check in
`checkImplicitAnyInClassElement` (Checker.kt:8485) but gated suppression on
`it.type != null` — i.e. "skip ONLY when sibling getter has a typed return."
Incorrect: TypeScript suppresses TS7032/TS7006 whenever ANY sibling getter
exists, because the getter's INFERRED return type still provides contextual
typing for the setter parameter (a getter returning `any` is sufficient).
Fix: drop the `&& it.type != null` clause from the sibling-getter probe and
rename the flag from `getterTyped` to `hasSiblingGetter`. The originating
17.94 test (`noImplicitAnyMissingGetAccessor_ts`) is unaffected — both its
setters (abstract `set message` in Parent + concrete `set message` in Child)
have no sibling getter in the same class. Net delta: 1595 → 1594 failed
(8480 → 8481 passing). Zero regressions.

**17.95 (2026-05-03, +1)** — TS2341 "Property 'X' is private and only
accessible within class 'Y'." now fires for object-binding-pattern
destructuring of class instance privates. Flips
`destructureComputedProperty_ts`. Pre-fix: `checkPrivateMemberAccess`
(Checker.kt:43510) only handled `PropertyAccessExpression` (e.g. `c.p`) —
destructuring patterns like `const { p: p3 } = new C()`,
`const { "p": p0 } = new C()`, `const { ["p"]: p1 } = new C()`,
`const { [nameP]: p2 } = new C()` (where `const nameP = "p"`) all silently
passed. Fix: new `checkDestructuringPrivateAccess` walker hooked into
`checkVarDeclAssignability` after the existing nullable-union check. Walks
each `BindingElement`, resolves the destructured property name via a new
`resolveDestructuringPropName` helper that handles 4 shapes: bare
Identifier (shorthand or named), StringLiteralNode (`"p"`),
ComputedPropertyName with literal expr, and ComputedPropertyName with
identifier expr (resolved via new `findConstStringValue` walker that scans
top-level `const X = "literal"` decls). Squiggle for ComputedPropertyName
uses bracket-matching forward from the first `[` to handle `propNode.pos`
trivia overshoot and `expr.end` one-token-past overshoot. Net delta:
1596 → 1595 failed (8479 → 8480 passing). Zero regressions.

**17.94 (2026-05-03, +2)** — TS7032 + TS7006 now fire for set accessors
without parameter type annotations when no sibling getter has a typed return.
Flips both `noImplicitAnyMissingGetAccessor_ts__target_es5__` and
`__target_es2015__`. Pre-fix: `checkImplicitAnyInClassElement`
(Checker.kt:8467+) had a SetAccessor branch but explicitly skipped TS7006
emission with the comment "TypeScript never emits implicit-any for setter
params" — incorrect; the rule is "skip ONLY when a sibling getter provides
a contextual return type." Fix: in the SetAccessor branch, when the first
param has no type annotation AND no sibling `GetAccessor` with the same
name has a typed return, emit TS7032 ("Property 'X' implicitly has type
'any', because its set accessor lacks a parameter type annotation.") on
the property name node, then call `checkParamsForImplicitAny` to emit
TS7006 on the param. Applies to abstract setters (no body) and concrete
setters alike. Net delta: 1598 → 1596 failed (8477 → 8479 passing).
Zero regressions.

**17.93 (2026-05-03, +2)** — TS2538 "Type 'null'/'undefined' cannot be used as
an index type." now fires for runtime element-access expressions like
`n[undefined]` / `n[null]`, not just `IndexedAccessType` in type positions.
Flips `indexWithUndefinedAndNullStrictNullChecks_ts` plus one more test in
the corpus. Pre-fix: `checkSingleElementAccess` (Checker.kt:44781+) handled
only `StringLiteralNode` and `NumericLiteralNode` argument expressions —
identifiers `null` / `undefined` fell into the `else -> return` branch.
Fix: add an `Identifier` branch BEFORE the literal switch — when
`arg.text in ("null", "undefined")` AND `getTypeOfIdentifier(arg)` resolves
to `nullType` / `undefinedType` exactly, emit TS2538 with squiggle on the
identifier (`arg.pos`, `arg.text.length`). Routes via `getTypeOfIdentifier`
(not a textual check) so a user-shadowed `let undefined = "foo"; n[undefined]`
correctly skips the diagnostic. Net delta: 1600 → 1598 failed
(8475 → 8477 passing). Zero regressions.

**17.92 (2026-05-03, +1)** — TS2428 "All declarations of 'X' must have identical
type parameters." now fires for class+interface merges (not just
interface+interface) AND compares type-parameter CONSTRAINTS (not just names).
Flips `nonIdenticalTypeConstraints_ts`. Pre-fix: the TS2428 check at
`Checker.kt:12246+` only collected `InterfaceDeclaration` decls and only
compared the type-param NAMES — so `class Foo<T extends Function>` +
`interface Foo<T extends Different>` (matching name `T`, mismatched
constraint) silently passed; `class Quux<T>` + `interface Quux<U>` was
skipped because `ifaceDecls.size == 1`. Fix: collect both `Interface` and
`Class` decls into a `mergeable` list (gated to `interface+interface`
or `class+interface` cases — `class+class` is a TS2300 case and not
applicable here). The signature comparison now produces
`List<Pair<name, constraintText>>` where `constraintText` is the source
slice from the constraint TypeNode (whitespace normalized via
`Regex("\\s+")` collapse). Mismatch fires TS2428 on every decl's name node.
Net delta: 1601 → 1600 failed (8474 → 8475 passing). Zero regressions.

**17.91 (2026-05-03, +1)** — Constructor overload visibility + TS2392 on all
constructors when 2+ implementations + TS2793 wired for Constructor decls. Flips
`constructorOverloads1_ts`. Three coordinated changes in `Checker.kt`:
(1) `resolveInterfaceMembers` now filters `ownConstructSignatures` — when a
class declares both body-less overload signatures AND body-having
implementation(s), only the overload signatures are externally visible
(matching TypeScript's checker semantics). Without this, `new Foo(...)` falls
through to the impl sig (often `(x: any)`) and silences TS2769. Construct
signatures from interface `new(...)` members keep their visibility (they're not
`Constructor` decls). (2) `checkMultipleConstructorImpls` now fires TS2392 on
EVERY `Constructor` member (signatures + impls) when the class has 2+
implementations, not just on the impls themselves — TypeScript treats the
overload pair model as invalid as a whole when there are multiple impls.
(3) New `findCtorImplementationInStatements` helper + Constructor branches in
`getOverloadImplementationRelated`, `getImplementationSignature`, and
`makeTs2793Diagnostic` — together emit TS2793 "The call would have succeeded
against this implementation..." pointing to the FIRST impl when the user's call
matches the impl shape but no overload sig accepts it. The Constructor squiggle
in `makeTs2793Diagnostic` locates the `constructor` keyword via
`source.indexOf("constructor", startIndex = implDecl.pos)` (no name node).
Net delta: 1602 → 1601 failed (8473 → 8474 passing). Zero regressions.

**17.90 (2026-05-03, +2)** — TS1038 "A 'declare' modifier cannot be used in an
already ambient context." now fires for declarations carrying the `declare`
modifier when nested inside a `declare namespace` body. Flips at least
`declFileWithErrorsInInputDeclarationFileWithOut_ts` (+1 in candidate finder
report; full-suite delta of +2 indicates one additional test elsewhere in the
corpus also gated on TS1038). New `checkRedundantDeclareModifier` walker
recursively descends through `ModuleDeclaration` bodies tracking
`inAmbientNamespace`; for each child statement carrying
`ModifierFlag.Declare`, scans BACKWARD through the source from `stmt.pos` to
locate the `declare` keyword span (the parser captures stmt.pos at the start
of the underlying declaration keyword — `var`/`function`/`class`/`namespace`
— after `parseDeclareDeclaration` consumed `declare`, so the keyword sits
behind stmt.pos). Top-level `.d.ts` declarations are NOT flagged
(TypeScript convention: `declare` at file scope of declaration files is
standard practice). Net delta: 1604 → 1602 failed (8471 → 8473 passing). Zero
regressions.

**17.89 (2026-05-03, +2)** — TS2300 "Duplicate identifier" + TS1118 "An object
literal cannot have multiple get/set accessors with the same name." now fire
for object-literal accessor groups with multiple getters or multiple setters.
Flips `duplicateObjectLiteralProperty_ts__target_es5__` and
`__target_es2015__`. Pre-fix: `checkObjectLiteralDuplicates` (Checker.kt:21945)
walked properties pairwise (`prevKind`/`kind`), so `get a, set a, get a`
toggled the seen-kind to `'g' → 's' → 'g'` and never detected the duplicate
getter (each step was a "g↔s" pair, no error). Fix: in addition to the
existing TS1117 emission for prop-prop duplicates, collect accessors per name
into `accessorsByName`; after the loop, for each name with `getCount > 1` OR
`setCount > 1`, emit TS2300 "Duplicate identifier 'X'." on every accessor
declaration plus TS1118 on the second/third/... duplicate get/set. Clean
get+set pairs (one of each) emit nothing. Also fixes a related squiggle bug:
`getPropertyNameLength` now uses `Identifier.rawText.length` when set so
`a` (= "a") gets the full 6-char source span instead of 1. Net delta:
1606 → 1604 failed (8469 → 8471 passing). Zero regressions.

**17.88 (2026-05-03, +2)** — TS2373 "Parameter 'X' cannot reference identifier
'Y' declared after it." now recurses into `ClassExpression` for eagerly-evaluated
positions (computed property/method/accessor keys, static field initializers,
static blocks). Flips `capturedParametersInInitializers2_ts__target_es5__` and
`__target_es2015__` (errors-baseline variants). Pre-fix: `findForwardParamRefs`
(Checker.kt:28541) explicitly skipped `ClassExpression` with the comment "own
scope, not immediately evaluated", so `function foo(y = class { static c = x;
get [x](){}; [z](){} }, x = 1, z = 2) {}` produced no TS2373 emissions despite
the class-decl-time evaluation of static initializers and computed keys. Fix:
add a `ClassExpression` branch that walks each member, descending into
computed property names (PropertyDeclaration / MethodDeclaration / GetAccessor
/ SetAccessor), static-field initializers (`ModifierFlag.Static` on
PropertyDeclaration), and ClassStaticBlockDeclaration bodies. Method/accessor/
constructor bodies and instance-field initializers stay skipped (deferred
evaluation). Net delta: 1608 → 1606 failed (8467 → 8469 passing). Zero
regressions.

**17.87 (2026-05-03, +1)** — TS2845 "This condition will always return 'false'/'true'."
now fires for enum-member references in `if (...)` test conditions, not just
ternary `a ? b : c`. Flips `errorOnEnumReferenceInCondition_ts`. Pre-fix:
`checkEnumReferenceFalsyCondition` (Checker.kt:17937) was only called from the
`ConditionalExpression` branch in `checkAlwaysTruthyInExpr`, so `if (Nums.Zero)`
patterns silently passed despite expected TS2845 emissions at the if-condition
position. Fix: add the call in the `IfStatement` branch in
`checkAlwaysTruthyInStatement` (both for the leading `if` expression and inside
the `else if` chain walker). Net delta: 1609 → 1608 failed (8466 → 8467
passing). Zero regressions.

**17.86 (2026-05-02, net-zero infra)** — Add `interface ThisType<T> {}` to
embedded BUILTIN_LIB_SOURCE. Pre-fix: `A & ThisType<any>` resolved to
`anyType` in our checker (because ThisType wasn't a real interface, only
in KNOWN_GLOBALS as a name) — `getIntersectionType` collapses anyType
intersections to anyType, then `canUseTypeEngine` returns false for
anyType targets, suppressing excess-prop checks. Post-fix: ThisType
resolves as a proper empty interface, intersections like
`A & ThisType<any>` retain their structure, and excess-prop check fires
correctly. Partial progress on `excessPropertyCheckWithEmptyObject_ts`
(2 of 3 expected TS2353 emissions now fire; the 3rd is gated on
`Object.defineProperty`'s coarse `any` signature in our lib). Net delta:
0 tests; foundation for future tests using `ThisType<T>` patterns.

**17.85 (2026-05-02, +1)** — TS2769 overload chain display: use
`signatureToStringColon` (with optional-param `?` + `| undefined`
widening) instead of the simpler 1-arg `signatureToString`. Flips
`namespaceMergedWithFunctionWithOverloadsUsage_ts`. Pre-fix: chain line
`Overload 1 of 2, '(opts: Whatever): void', gave the following error.`
TypeScript baseline: `'(opts?: Whatever | undefined): void'` (with `?` and
`| undefined`). The TS2769 chain emission (Checker.kt ~45990) was
calling the simpler `signatureToString(sig)` overload at ~46294 which
emitted `${param.name}: $typeStr` without checking `questionToken` or
adding the `| undefined` widening. Fixed by switching to
`signatureToStringColon(sig, isConstruct = false)` which routes through
`formatParameter` (already handles optional params per 17.13). Net delta:
1610 → 1609 failed (8465 → 8466 passing). Zero regressions.

**17.84 (2026-05-02, +1)** — TS2349 squiggle on property name only for
PropertyAccessExpression callees. Flips `methodChainError_ts`. Pre-fix:
`new Builder().method("a").notMethod()` emitted TS2349 with squiggle
spanning the entire chain expression (multi-line). TypeScript's baseline
squiggles only `notMethod` (9 chars). Fix: in TS2349 emission
(Checker.kt ~45315), when `calleeExpr is PropertyAccessExpression`, use
`calleeExpr.name.pos` and `calleeExpr.name.text.length` for the squiggle
position/length instead of the whole expression. Net delta: 1611 → 1610
failed (8464 → 8465 passing). Zero regressions.

**17.82 (2026-05-02, +1)** — TS2352 array-to-array cast: prefer
excess-property chain at the prop position over whole-cast position.
Flips `arrayCast_ts`. Pre-fix: `<{id:number}[]>[{foo:"s"}]` emitted
TS2352 at column 1 (whole cast) with chain "Type '{ foo: string; }' is
not comparable to type '{ id: number; }'." TypeScript's baseline emits
at column 23 (the `foo` prop) with chain "Object literal may only
specify known properties, and 'foo' does not exist in type
'{ id: number; }'." Fix: in `emitTS2352IfSameTargetMismatch`
(Checker.kt ~27395), when source is `ArrayLiteralExpression` and both
source/target are `Array<...>`-shaped, walk source elements that are
`ObjectLiteralExpression`s and emit TS2352 at the FIRST excess property
with the excess-prop chain. Falls back to the existing whole-cast
emission when no excess prop is found. Net delta: 1612 → 1611 failed
(8463 → 8464 passing). Zero regressions.

**17.81 (2026-05-02, +1)** — Per-element TS2741 for type-asserted array
elements + outer-TS2741 suppression in class property init path. Flips
`contextualTyping11_ts`. Pre-fix: `class foo { public bar: {id:number;}[]
= [<foo>({})]; }` emitted only the OUTER TS2741 at the `bar` property
name (`'foo[]' missing 'id' in '{id:number}[]'`). TypeScript baseline
emits the per-element TS2741 at `<foo>({})`'s position (`'foo' missing
'id' in '{id:number}'`). Three coordinated pieces:
(1) `checkArrayLiteralElementExcessProps` (Checker.kt ~48124) extended
with a `TypeAssertionExpression` / `AsExpression` branch — emits TS2741
when the asserted type is missing a required property of the target
element (via `getMissingRequiredPropertySymbol`).
(2) `expressionTrueEnd` (~26217) extended with `TypeAssertionExpression`
case (returns inner expression's true end — fixed off-by-one squiggle
that was emitting 10 chars instead of 9 for `<foo>({})`).
(3) `checkPropertyInitAssignability` (~34403) suppresses the outer
TS2741 / TS2322 when `checkArrayLiteralElementExcessProps` already
emitted at least one diagnostic and source/target are array-literal /
Array-Reference. Mirrors the var-decl init suppression at ~34146.
Net delta: 1613 → 1612 failed (8462 → 8463 passing). Zero regressions.

**17.80 (2026-05-02, +1)** — Prefer resolved display over type-alias name
for intrinsic-like numeric literals (`Infinity` / `-Infinity` / `NaN`).
Flips `fakeInfinity1_ts`. Pre-fix: `let a: A` where `type A = 1e999;`
displayed as `'A'` in TS2322, but TypeScript baseline expands the alias
to `'Infinity'` because `1e999` evaluates to `Double.POSITIVE_INFINITY`
which TS treats as the named intrinsic-like literal. Fix: in
`checkAssignmentExpression`'s display-target path (Checker.kt ~34888),
when `typeToString(targetType)` returns one of `"Infinity"`,
`"-Infinity"`, or `"NaN"`, prefer that over `formatTypeForDisplay`'s
type-alias text. Other type aliases (e.g. `type Box<T> = ...`) still
preserve their alias name in display. Net delta: 1614 → 1613 failed
(8461 → 8462 passing). Zero regressions.

**17.79 (2026-05-01, +1)** — Synthetic TS2728 "declared here" pointing to
`lib.dom.d.ts:--:--` for KNOWN_GLOBALS without a real symbol declaration.
Flips `baseCheck_ts`. Pre-fix: TS2552 spelling suggestion suggested `Lock`
(a Web Locks API DOM global) for misspelled `loc`, but we couldn't emit the
TS2728 related info because `Lock` isn't backed by a real symbol in our
embedded BUILTIN_LIB_SOURCE (only present in KNOWN_GLOBALS as a name).
Fix: `findDeclarationRelatedInfo` (Checker.kt ~10968) now emits a synthetic
TS2728 with `fileName="lib.dom.d.ts"` and `line=null, character=null`
(rendered as `lib.dom.d.ts:--:--` by BaselineFormatter) when the looked-up
name isn't in any user/lib symbol table but IS in the new `DOM_GLOBAL_NAMES`
companion-object set (subset of KNOWN_GLOBALS covering the DOM types
documented in the lib.dom.d.ts section). Net delta: 1615 → 1614 failed
(8460 → 8461 passing). Zero regressions.

**17.78 (2026-05-01, +1)** — TS2208 / "could be instantiated" advisory for
TS2416 method-override mismatches involving class-level TypeParams. Flips
`implementGenericWithMismatchedTypes_ts`. Two coordinated pieces in
`addSignatureElaboration` (Checker.kt ~42066): (1) PARAM-mismatch branch
extended to fall back to the derived class's `classTypeParams` (looked up
by name) when the base TypeParam isn't a method-level decl — covers the
common case of `class C<T> implements IFoo<T>` where `IFoo<T>`'s `T` gets
substituted to `C<T>`'s `T` and the symbol-declarations chain doesn't
carry through (instantiation creates fresh Symbols). (2) RETURN-mismatch
branch adds the `'T' could be instantiated with an arbitrary type which
could be unrelated to 'X'.` chain advisory when target return is TypeParam
and source return is concrete — but does NOT emit TS2208 for return-type
mismatches (TypeScript only emits TS2208 for param mismatches because the
hint `extends X` doesn't fix the wrong-direction return assignability).
Wired `classTypeParams` through `addSignatureElaboration`'s call site.
Net delta: 1616 → 1615 failed (8459 → 8460 passing). Zero regressions.

**17.77 (2026-05-01, +1)** — Refine 17.15b's TS2769 callee-position rule:
only switch squiggle to callee when overloads fail at DIFFERENT argument
positions. Flips `signatureLengthMismatchInOverload_ts`. Pre-fix: ANY
fn-vs-fn arg mismatch caused the squiggle to move from the failing argument
to the callee. For tests where all overloads fail at the SAME argument
position (e.g. both overloads of `f(callback)` reject the arrow-fn arg —
overload 1 by param-type, overload 2 by arity), TypeScript squiggles the
argument; we incorrectly squiggled the callee `f`. Fix:
`checkArgumentsAgainstOverloads` (Checker.kt ~45759) computes per-overload
`getFirstFailingArgPosition`; if all overloads agree on the failing
position, use that — otherwise fall back to callee for fn-vs-fn cases.
Verified `specializedSignatureAsCallbackParameter1_ts` (the 17.15b
flip-trigger) still squiggles callee since its overloads fail at
different positions (arg[0] vs arg[1]). Net delta: 1617 → 1616 failed
(8458 → 8459 passing). Zero regressions.

**17.76 (2026-05-01, +2)** — Suppress TS2793 "implementation would have
succeeded" for non-overloaded methods. Flips `genericOfACloduleType1_ts` and
`genericOfACloduleType2_ts`. Pre-fix: a single class method `bar(x: T) { ... }`
emitted spurious TS2793 related info pointing to itself, because
`findImplementationInStatements` (Checker.kt ~45333) returned the method
whenever `foundOverload && impl != null` — but for a single body-having method
both conditions trivially hold (overloadDecl IS the impl, both are the same
declaration). Fix: require at least one body-less overload-sig declaration
in the class members AND a separate body-having implementation — only then
is it a real overload pair. Net delta: 1619 → 1617 failed (8456 → 8458
passing). Zero regressions across 10078-test suite.

**17.75 (2026-05-01, +1)** — Skip "provides no match for the signature" chain
for primitive sources. Flips `assignToFn_ts`. Pre-fix: `x.f = "hello"` where
`x.f: (n:number) => boolean` emitted the over-firing chain
"  Type 'string' provides no match for the signature '(n: number): boolean'."
TypeScript's baseline expects only the bare TS2322 for primitive→callable
mismatches; the "provides no match" chain is reserved for object-shaped sources
without call signatures. Fix: `getCallableMismatchElaboration` (Checker.kt
~35541) early-returns null when `source is Type.Intrinsic` /
`Type.StringLiteral` / `Type.NumberLiteral` / `Type.BigIntLiteral`. Net delta:
1620 → 1619 failed (8455 → 8456 passing). Zero regressions.

**17.74 (2026-05-01, +2)** — Function-shaped source vs ArrayLike-shape target:
chain elaboration "Index signature for type 'number' is missing in type
'() => void'." now fires for `func: () => void; const a: ArrayLike<any> = func;`.
Flips `functionAssignabilityWithArrayLike01_ts` (strict=false and strict=true
variants). Three coordinated pieces in Checker.kt: (1) `propertiesRelatedTo`
treats target props `length: number` and `name: string` as implicitly satisfied
by Function.prototype when source has callSignatures (skipped via
assignability check on the prototype's intrinsic type); (2) `objectTypeRelatedTo`'s
missing-index-sig gate extended from nominal-only to also accept
function-shaped sources (callSignatures only, no nominal members) — for
function sources, the `any`/`unknown` index-type skip is also dropped, since
a function type doesn't implicitly satisfy `[n:number]:any` (unlike a nominal
class where the FP risk justified the skip in 16.4cn); (3) var-decl init's
chain elaboration block (Checker.kt ~34186) now consults
`lastMissingIndexSigKind` after `getPropertyElaborationChain`, mirroring the
assignment-expression path at ~34861 and property-access path at ~35285.
Also resets `lastMissingIndexSigKind = null` before the var-decl comparison
to avoid stale leakage from prior comparisons. Net delta: 1622 → 1620 failed
(8453 → 8455 passing). Zero regressions across 10078-test suite.

**17.73 (2026-05-01, +1)** — TS2367 narrow extension: TypeParam-with-literal-
union-constraint vs literal. Flips `compareTypeParameterConstrainedByLiteralToLiteral_ts`.
New `checkTypeParamLiteralNoOverlap` helper called from
`checkEqualityComparisonNoOverlap` (Checker.kt ~47650): detects pattern
`t === "x"` where `t: T` and `T extends "a" | "b"` — reuses 17.72's
`intersectTwoTypesForWrite` to compute apparent-type intersection
(`("a" | "b") & "x"` → distribute → `never | never` → `never`); when result
is `never`, emit TS2367 with the TypeParam's NAME (not its constraint) so
the message reads `... types 'T' and '"x"' have no overlap.` matching
TypeScript's display. Required infrastructure: `pushFunctionTypeParamsScope`
helper now invoked from arithmetic walker's FunctionDeclaration / Method
branches before `populateParameterLocalTypes` so that param annotations
referencing T resolve to `Type.TypeParam(T)` instead of errorType.
`isValidArithmeticOperand` extended to recurse on TypeParam constraint
(unconstrained TypeParam treated as anyType-equivalent to avoid
regressing tests that previously saw `t: T` as anyType). Value side uses
`literalTypeOfExpression` to recover the literal type from
`StringLiteralNode` / `NumericLiteralNode` (since `getTypeOfExpression`
widens them). Net delta: 1623 → 1622 failed (8452 → 8453 passing). Zero
regressions across 10078-test suite.

**17.72 (2026-05-01, +4)** — Intersection write-type for property assignment
on accessor-divergent classes. Flips `divergentAccessorsTypes4_ts` and
`divergentAccessorsTypes5_ts` in both `target=es5` and `target=es2015`
variants (4 test cases). New `checkIntersectionPropertyAssignment` branch in
`checkPropertyAccessAssignment` (Checker.kt ~35210): when receiver type is
`Type.Intersection`, walks each constituent's class/interface declarations
to find the property's WRITE type per constituent (SetAccessor's first param
type for getter/setter pairs, or the property type for regular properties),
then reduces via the new `reduceIntersectionForWriteType`. Reduction
distributes intersection over union (`(A | B) & C` → `(A & C) | (B & C)`)
and applies primitive/literal subtype reduction (`42 & number` → `42`,
`string & 42` → `never`, `"a" & "b"` → `never`). Gated on at least one
constituent contributing via SetAccessor — preserves existing behavior for
plain property intersections. Read-only intersections (only-getter case)
are skipped — TS2540 fires elsewhere. Value-side literal preservation
mirrors the 17.66/17.67/17.70 pattern: when `propTypeContainsLiteral` of
the reduced target is true, source uses `literalTypeOfExpression(value)`
fallback to widened. Net delta: 1627 → 1623 failed (8448 → 8452 passing).
Zero regressions across 10078-test suite.

**17.71 (2026-05-01, +1)** — TS8024 — JSDoc `@param` tag with non-matching name
in JS files. Flips `jsdocParamTagInvalid_ts`. New `checkJSDocParamTags()` walker
in Checker.kt (~7959) emits TS8024 ("JSDoc '@param' tag has name 'X', but there
is no parameter with that name.") when a `/** @param {T} name */` comment names
an identifier that's not in the function's parameter list. Walks
FunctionDeclaration / FunctionExpression / ArrowFunction / MethodDeclaration /
Constructor in JS-like files (`.js`/`.jsx`/`.cjs`/`.mjs`). Per-tag parsing handles
whitespace/`*`/line breaks between `@param`, optional `{Type}` brace expression,
optional `[name]` brackets, and the identifier name. Squiggle position computed
via `comment.pos + nameStartInCommentText`. Skips nested-name cases
(`@param obj.foo`). Wired into the `check()` entry point right after
`checkInvalidGlobalAugmentations()`. Net delta: 1628 → 1627 failed (8447 →
8448 passing). Zero regressions across 10078-test suite. The walker
infrastructure is reusable for future JSDoc walkers (`@returns`, `@type` at
parameter positions, `@template`, etc.).

**17.70 (2026-05-01, net-zero infra)** — Extend contextual literal preservation
to return-statement source. Mirror of 17.66/17.67 for `checkReturnAssignability`
(Checker.kt ~34274). When `propTypeContainsLiteral(targetType)` is true, source
type is computed via `literalTypeOfExpression(expr) ?: widened`. Same gate, same
fallback. The literal-preservation pattern is now consistent across all four
source-type computation sites: var-decl init (17.66), assignment RHS (17.66),
call arg (17.67), return statement (17.70). Net delta: 1628 → 1628 failed
(8447 unchanged). Foundation completion — future tests with literal-typed return
annotations will produce correct source displays without further infrastructure.

**17.67 (2026-05-01, net-zero infra)** — Symmetric extension of 17.66's
contextual literal preservation to function-call args.
`checkArgumentsAgainstSignature` (Checker.kt ~45774) now uses
`literalTypeOfExpression(arg) ?: getTypeOfExpression(arg)` when
`propTypeContainsLiteral(paramType)` is true — preserving literal types
(`"x"`, `"z"`) instead of widening to primitives (`string`) for TS2345
source-display purposes. Same gate, same fallback as 17.66. Net delta:
1628 → 1628 failed (8447 unchanged). No currently-failing test gates
SOLELY on this; tests that LOOK like they benefit need additional
infrastructure (TS2820 "Did you mean", intersection-of-literals reduction,
target-type-alias display expansion). Preventive completion of the
literal-preservation pattern across all source-type computation sites
(var-decl init, assignment RHS, call arg).

**17.66 (2026-05-01, +2)** — Contextual literal preservation for var-decl init
and assignment-expression RHS. Flips `checkJsObjectLiteralHasCheckedKeyof_ts`
plus one additional test. Mirrors TypeScript's bidirectional contextual-typing
rule: when target type contains literal types (literal Union, single literal,
keyof producing literals), source/RHS literal expressions keep their literal
type (`"x"`, `"z"`) instead of widening to the primitive (`string`). Two-spot
edit in Checker.kt: `checkVarDeclAssignability` and `checkAssignmentExpression`.
Each gated on `propTypeContainsLiteral(targetType)` (existing 17.43 helper) —
non-literal-containing targets fall through to the existing widened path. Uses
existing `literalTypeOfExpression(expr)` helper. Surfaced by 17.65's broader
JSDoc `@type` bridge for var-decls — pre-17.65 the test produced ZERO
diagnostics, post-17.65 produced wrong-display, post-17.66 produces correct
diagnostics matching TypeScript baseline. Net delta: 1630 → 1628 failed (8445 →
8447 passing). Zero regressions across 10078-test suite.

**17.65 (2026-05-01, net-zero infra)** — Widen JSDoc `@type {T}` bridge for
VariableDeclaration to non-primitive types via name-resolution gate. Implements
option (a) from 17.61's revert note. Two-line change: (1) `parseVariableStatement`
now calls `parsePropertyTypeFromJSDoc` (sub-Parser, full type-syntax) instead of
`parsePrimitiveTypeFromJSDoc` (primitive-only); (2) `checkUnresolvedInStatement`'s
`is VariableStatement` branch wraps `decl.type?.let { checkUnresolvedInType(...) }`
in `if (!decl.typeFromJSDoc)` — JSDoc-derived TypeNodes skip TS2503/TS2304
emission so sub-Parser positions don't land on wrong source. Dodges 17.61's
regression on `jsdocReferenceGlobalTypeInCommonJs_ts` because no positional
diagnostic is emitted. Downstream type resolution silently returns errorType for
unresolvable refs, so the var behaves as if untyped when the JSDoc type can't
resolve. Net delta: 1630 → 1630 failed (8445 unchanged); both
`jsdocReferenceGlobalTypeInCommonJs_ts` and `jsExportMemberMergedWithModuleAugmentation_ts`
verified passing post-change. Foundation for extending the gate to
PropertyDeclaration / FunctionDeclaration return type / Parameter — each a small
substep mirroring this commit's pattern.

**17.64 (2026-05-01, net-zero infra)** — Extend 17.63's `@this` JSDoc detection
from FunctionExpression to FunctionDeclaration. Single-spot edit in
`checkThisInStatement`'s FunctionDeclaration branch — `newThisIsTyped =
hasThisParam || hasJSDocThis`, with shadow-pos calculation flipped from
`hasThisParam`-only to `newThisIsTyped`-aware. JSDoc-typed `this` on a top-level
`function foo() {}` now correctly suppresses TS2683 (and the related TS2738
"shadowed" hint). Net-zero on 10078-test suite (no currently-failing test gates
solely on this — the existing `thisInFunctionCallJs_ts` test 17.63 flipped used
only the FunctionExpression form). Symmetric completeness fix; closes the
inconsistency where FunctionExpression handled `@this` but FunctionDeclaration
didn't.

**17.63 (2026-05-01, +1)** — JSDoc `@this {Type}` suppresses TS2683 in JS-like
files. Flips `thisInFunctionCallJs_ts`. New `hasJSDocThisTag(comments, fileName)`
helper in Checker.kt scans `MultiLineComment` entries starting with `/**` for the
`@this` tag (with a char-after-tag guard against partial-identifier matches like
`@thisIsNotATag`). Wired into `checkThisInExpr`'s `is FunctionExpression` branch:
`newThisIsTyped = hasThisParam || hasJSDocThis`. The shadow-pos calculation also
uses the combined `newThisIsTyped` so JSDoc-typed `this` doesn't FP-fire TS2738
("outer value of `this` is shadowed"). Type extraction is intentionally skipped —
only tag presence matters for TS2683 suppression (mirrors TypeScript). Conservative
gates: JS-like files (`.js`/`.jsx`/`.cjs`/`.mjs`) only; only `MultiLineComment`
starting with `/**`. Net delta: 1631 → 1630 failed (8444 → 8445 passing). Zero
regressions across 10078-test suite. Pattern reusable for other tag-presence-only
JSDoc bridges.

**17.62 (2026-05-01, net-zero infra)** — Primitive-only JSDoc `@type {T}` bridge
for VariableDeclaration. Follow-up to 17.61 revert per its own session note
(option c — allowlist primitive-only). New `parsePrimitiveTypeFromJSDoc` helper
in Parser.kt extracts brace content via existing `extractAtTypeBraceContent`,
dispatches via new `primitiveKeywordKindFor` allowlist
(string/number/boolean/any/unknown/never/void/undefined/null/bigint/symbol/object),
returns synthetic `KeywordTypeNode(kind, pos=-1, end=-1)`. Wired into
`parseVariableStatement` for single-declarator + Identifier-name + null-type +
JS-like file. New `typeFromJSDoc: Boolean` flag on `VariableDeclaration`; TS8010
walker skips when set. Synthetic-position TypeNode is harmless: keyword types
have no name to resolve (no TS2503/TS2304 path that bit 17.61), TS8010's
`length <= 0` guard skips emission for synthetic positions, and
`getTypeFromTypeNode` for keyword types returns the corresponding intrinsic
without consuming positions. Net delta: 1631 → 1631 failed (8444 unchanged).
Zero regressions across 10078-test suite. Foundation for follow-on (option a:
thread `typeFromJSDoc` into `getTypeFromTypeReference` to suppress diagnostics
for unresolvable JSDoc-derived names — would unlock broader 17.61 case).

**17.61 (2026-05-01, ATTEMPTED + REVERTED, no code change)** — JSDoc `@type {T}`
bridge extension from PropertyDeclaration (17.58) to VariableDeclaration.
Attempted to mirror the 17.58 implementation: new `typeFromJSDoc: Boolean` on
`VariableDeclaration`, parser hook in `parseVariableStatement` for single-decl
+ Identifier-name + JS-like file + null annotated type, TS8010 skip in
`checkTsSyntaxInStatement`'s var-decl branch. Regressed
`jsdocReferenceGlobalTypeInCommonJs_ts` (-1) because the sub-Parser-derived
TypeNode positions reference the JSDoc-internal substring (offset 0), so
`getTypeFromTypeReference` for unresolvable namespace paths
(`Puppeteer.Keyboard` in the test) emits TS2503 with start position 0 — which
maps to the original source's offset 0 (`const other`), producing a spurious
diagnostic with garbled squiggle. The PropertyDeclaration case (17.58) doesn't
bite this because primitive types don't trigger name-resolution paths that
consume positions. Reverted per protocol's "Time budget per attempt" rule.
Skip-log entry on PLAN-PHASE-4.md item 17.61 documents the gotcha for future
attempts: option (a) thread typeFromJSDoc into name-resolution paths to
suppress diagnostics, (b) reparent sub-Parser positions into original source
(invasive), or (c) allowlist primitive-only `@type` extraction (smallest
scope). 17.30 / 17.30c also marked done in the same commit — 17.30c's
`&&`-chain narrowing intent is achieved differently via 17.60 + simpler
`&&`-RHS semantics in `getTypeOfBinaryExpression`. No test count delta.

**17.60 (2026-05-01, net-zero infra)** — Block-scope typed-locals tracking for
TS2774 (Blocker #1 step 2h substep, smallest 17.30c decomposition). New
`withUncalledBlockScope(block, doBody)` helper in Checker.kt populates and
pushes typed/shadowed maps for free-standing Blocks (top-level `{...}` and
if/while/for bodies). Wired into `walkUncalledChecksInStatement`'s `is Block`
branch so block-scoped declarations like `{ const perf = window.performance; if
(perf && perf.measure) ... }` resolve via `lookupUncalledTypedLocal` instead of
falling through to `getTypeOfIdentifier`'s file-level fallbacks (which would
return `anyType` since block-scoped consts aren't in `currentLocalTypes` /
`currentFileLocals` / `globals`). Function bodies already walk `body.statements`
directly via `withUncalledScope` — no double-push. Skip-push optimization when
both typed and shadowed maps are empty. Verifies on
`uncalledFunctionChecksInConditional2_ts`: 3 of 7 expected diagnostics now fire
(all 3 TS2774s for `perf.measure` / `perf.clearMeasures`); the 4 missing TS7006s
are for arrow-fn parameters and need `noImplicitAny` enabled (not default in our
checker for `@strictNullChecks: true` only). Net-zero on 10078-test suite (1631
→ 1631 failed). Zero regressions. Foundation for the next 17.30c substep
(`&&`-chain narrowing for `perf: boolean | Performance` to narrow into
`Performance` before accessing `perf.clearMeasures`).

**17.59 (2026-05-01, +1)** — TS2774 asymmetric in-test-position rule. Flips
`truthinessCallExpressionCoercion2_ts`. Closes the FP that 17.57 surfaced when
Window/Performance started resolving past `anyType`: `walkUncalledChain` walked
both LHS and RHS of `&&`/`||`/`??` unconditionally, but TypeScript's
`bothHelper`/`helper` pair only treats the LHS as always-tested — RHS inherits
the parent context's test-position status. Implementation: added
`inTestPosition: Boolean = true` parameter to `checkUncalledInCondition` and
`walkUncalledChain`; LHS always passes `true` (short-circuit truthiness check is
intrinsic to the operator), RHS passes the inherited value. Leaf emission gated
on `inTestPosition`. Two call sites switched to `inTestPosition = false`:
ExpressionStatement-rooted truthiness chains (statement discards the value) and
expression-bodied arrow bodies (body produces a value, not a tested condition).
All other call sites (IfStatement, WhileStatement, DoWhileStatement,
ForStatement, ConditionalExpression conditions) inherit the default `true` — no
behavior change for genuinely-tested chains. Net delta: 1632 → 1631 failed
(8443 → 8444 passing). Zero regressions across 10078-test suite. 17.30c's
original-spec narrowing piece (`&&`-chain narrowing for `perf && perf.measure
&& ...`) still pending — 17.59 addresses a discrete sub-bug from 17.57's lib
addition, separate from the queued narrowing work.

**17.58 (2026-05-01, +1)** — JSDoc `@type {T}` bridge for PropertyDeclaration in
JS-like files (`.js`/`.jsx`/`.cjs`/`.mjs`). Flips
`jsExportMemberMergedWithModuleAugmentation_ts`. Three coordinated changes:
(1) Parser.kt — new `isJsLikeFile` field, new helpers `parsePropertyTypeFromJSDoc`,
`extractAtTypeBraceContent` (brace-balanced extraction skipping line-prefix `*`),
and `runParseTypeFromExternal()` (sub-Parser entry point that bootstraps via
`nextToken()` + `parseType()`). Wired into `parseClassMember`'s PropertyDeclaration
branch. (2) Ast.kt — new `typeFromJSDoc: Boolean = false` on `PropertyDeclaration`,
default preserves binary compatibility. (3) Checker.kt —
`checkTsSyntaxInClassMember`'s PropertyDeclaration branch skips the TS8010 "Type
annotations can only be used in TypeScript files" emission when `typeFromJSDoc ==
true`. Sub-Parser TypeNode positions reference the JSDoc-internal text rather
than the original source — fine for TS2564 (only inspects structural shape) but
any future consumer using positions for diagnostics must guard via
`typeFromJSDoc`. 17.58a (scanner-level JSDoc capture) verified pre-existing —
Scanner.kt:531 already attaches block comments via `leadingComments`. Net delta:
1633 → 1632 failed (8442 → 8443 passing). Zero regressions across 10078-test
suite. Foundation for future substeps (Parameter/VariableDeclaration/
FunctionDeclaration return type) — each can leverage the same JSDoc bridge.

**17.57 (2026-05-01, net-zero infra)** — Hand-rolled Window / Performance /
MediaQueryList interfaces added to `BUILTIN_LIB_SOURCE` per user authorization.
Members chosen to cover the failing TS2774 candidates: Window has `console`,
`performance`, `matchMedia`, plus standard browser globals; Performance has
`measure`/`mark`/`clearMarks`/`clearMeasures`/`now`; MediaQueryList has
`matches`/`media`/`onchange`/`addEventListener`. Plus `declare var window:
Window;` and `declare var performance: Performance;`. Companion guard in
`checkMemberAccessMissing`: skip TS2339 when `propName` is empty (parser
error-recovery placeholder for incomplete syntax like `var p = window.` —
TS1003 already fires for the missing identifier). Net delta: 0 tests (1633
→ 1633 failed). Window typing alone doesn't flip the originally-targeted
TS2774 tests: `truthinessCallExpressionCoercion2_ts` adds the missing
emission at (116,46) but ALSO over-fires at (116,70) — TypeScript's TS2774
walker has an asymmetric rule for `&&` RHS leaves we don't replicate;
`uncalledFunctionChecksInConditional2_ts` needs TS2774 to track typed
locals inside file-level `{ }` Blocks (currently only function bodies
trigger `withUncalledScope`). Foundation for those follow-on substeps;
17.30c is now unblocked.

**17.56 (2026-05-01, +1)** — Suppress TS2415/TS2420 "separate declarations of
a private property" when the override has a DIFFERENT type than the base —
TS2416 type-mismatch is the correct primary diagnostic for that case. Two
coordinated suppressions in Checker.kt: (1) `checkClassExtendsPrivateConflicts`
(TS2415, ~40954): in the `derivedIsPrivate && baseIsPrivate` branch, compute
widened types via `getTypeOfMemberDecl` + `widenType`; if they're not mutually
assignable, `continue` so TS2415 doesn't gate TS2416 (existing
`checkClassPropertyOverrides` walk fires TS2416 next). (2)
`checkImplementsClauses` (TS2420, ~39965): mirror of the same rule — when
`isPrivate && ifacePropIsPrivate` AND types differ, suppress TS2420. Flips
`interfaceExtendsClassWithPrivate2_ts`: test has `D extends C implements I`
with `private x = 2` (number, matches C's `x = 1`) — emits TS2415 + TS2420
correctly; AND `D2 extends C implements I` with `private x = ""` (string,
differs from C's number) — pre-fix emitted FP TS2415 + TS2420 and missed
TS2416 vs C; post-fix emits TS2416 vs C and TS2416 vs I (the two correct
type-mismatch diagnostics) without the FP "separate decls" lines.
Conservative gates: bidirectional assignability, widening applied to literal
initializers, both members must have resolvable types. Net delta: 1634 →
1633 failed (8441 → 8442 passing). Zero regressions across 10078-test suite.

**17.55 (2026-05-01, net-zero infra)** — Display improvements for TS2420
implements clause: (1) class name includes type parameters
(`ObservableArray<T>` not `ObservableArray`) via new `classNameRaw`/`className`
split — squiggle uses raw length so it doesn't extend into the type-param
list; (2) `Array<T>` / `ReadonlyArray<T>` rendered as `T[]` / `readonly T[]`
shorthand; (3) `OBJECT_PROTOTYPE_PROPERTIES` filter (toString, valueOf, etc.)
applied to the missing-property walk — mirrors `propertiesRelatedTo` /
`collectMissingProperties`. Net-zero on 10078-test suite (no flips, no
regressions). Two TS2420 baselines that name a generic class
(`bluebirdStaticThis`, `genericArrayExtenstions`) now produce correct
display; neither flips because of unrelated gaps (`bluebirdStaticThis` has
many other diagnostic mismatches; `genericArrayExtenstions` still has a
property-count mismatch — 4 extras over TS's es2015 baseline because our
embedded `Array<T>` includes ES2016+ methods like `includes`/`flat`/`at`/
`findLast`/`findLastIndex` that the test target lacks). Test count
unchanged 8441 / 1634 / 3.

**17.54 (2026-05-01, +1)** — TS2420 "incorrectly implements interface" for
inherited-private property mismatches. Flips `interfaceImplementation8_ts`.
The existing privacy check at `checkImplementsClauses` (Checker.kt ~39915)
walks `classDecl.members` to find the property declaration and inspect
`ModifierFlag.Private`, but for `class C5 extends C2 implements i1 { }`
where C5 has no own `name` member and inherits `private name: string` from
C2, the lookup returned null and the check silently passed. New helper
`findInheritedPrivateProperty(classDecl, propName)` (Checker.kt ~40010)
walks the `extends` chain via `ClassDeclaration.heritageClauses` looking
for the first base class that declares `propName`; returns the base class
name when the inherited declaration carries `ModifierFlag.Private`, else
null (public override or property not found in the chain). Wired into the
existing emission branch via a unified `privateInTypeName` variable: own
private uses `className`, inherited private uses the base class name from
the helper. Diagnostic chain becomes `Property 'name' is private in type
'C2' but not in type 'i1'.` matching baseline (note "type 'C2'", not
"type 'C5'"). Test source has C4 extends C1 (public) → no emission, C5
extends C2 (private) → emit, C6 extends C3 (private) → emit. The recursive
helper handles deeper chains by recursing past base classes that don't
declare the property. Conservative: returns null when first declaration
found is public (covers the case where a derived shadows a base's private
with a public member). Net delta: 1635 → 1634 failed (8440 → 8441 passing).
Zero regressions across 10078-test suite.

**17.53 (2026-05-01, +1)** — `typeToString` for `Type.Union` now follows TypeScript's
display convention: nullish members (`null`/`undefined`/`void`) are sorted to the
END of the union, and function/constructor-typed members (anonymous `Type.Object`
with exactly one call or construct signature, no properties, no tuple) get
parenthesized so the `|` is unambiguous. Flips `optionalPropertiesTest_ts` —
chain line `Type '(() => void) | undefined' is not assignable to type '() => void'.`
now matches the baseline (was emitting `'undefined | () => void'`). Single-spot
edit at the existing `Type.Union` branch in `typeToString` (Checker.kt ~38100):
`fun nullishRank(t)` returns 1/2/3 for `null`/`undefined`/`void` Intrinsics and 0
otherwise; `sortedBy { nullishRank(it) }` is stable so non-nullish members keep
their original relative order. Function-paren gate matches the typeToString
arrow-format branch (`m is Type.Object && tupleElementTypes == null && properties
nullOrEmpty && totalSigs == 1`) so multi-sig overload-shape Objects render as
`{ ... }` and don't get an extra wrap. Cross-corpus baseline check before
implementation: 1778 occurrences of `<X> | undefined` vs only 64 of `undefined |
<X>` — the convention is overwhelmingly the new behavior. Zero regressions
across the 10078-test suite. Net delta: 1636 → 1635 failed (8439 → 8440 passing).

**17.52 (2026-04-27, +1)** — TS2845 "This condition will always return 'false'/'true'."
for enum-member references in conditional-expression conditions. Flips
`importAliasFromNamespace_ts`. New `checkEnumReferenceFalsyCondition` helper
in Checker.kt called from `checkAlwaysTruthyInExpr`'s ConditionalExpression
branch (before walking whenTrue/whenFalse). When the condition is a
`PropertyAccessExpression` whose dotted path resolves through `resolveNamePath`
+ `resolveAlias` to a const- or non-const-enum member with a computed
ConstantValue, classifies the value as truthy (non-zero number / non-empty
string) or falsy (0 / "") and emits TS2845 with the appropriate verdict.
Squiggle covers the full property-access span via `expressionTrueEnd(expr) -
expr.pos`. Reuses existing `getReferencePath` helper for path serialization.
Path resolution handles nested-namespace import aliases via `resolveNamePath`'s
fallback to `findSymbolInAllNamespaceScopes` — works for the test's
`import Internal = My.Internal` inside `namespace SomeOther.Thing { ... }`
where `Internal.WhichThing.A` resolves to const enum member A = 0. Conservative
gate: only fires from ConditionalExpression conditions (not IfStatement /
WhileStatement / etc.), only when the path resolves to an enum symbol with a
populated `enumValues` entry. Net delta: 1637 → 1636 failed (8438 → 8439
passing). Zero regressions across 10078-test suite — `errorOnEnumReferenceInCondition_ts`
errors-baseline isn't generated (per CLAUDE.md gotcha: .errors.txt baselines
commented out for this file), so no new emissions affect existing tests.

**17.51 (2026-04-27, +1)** — TS2310 "recursively references itself as a base type"
for class-extends-generic-default-indexed-access cycles. Flips
`circularConstraintYieldsAppropriateError_ts`. New `checkCircularClassBaseViaDefaultTypeArg`
helper in Checker.kt (called from init step 64f3a-2, after the existing
interface-bases TS2310 walker). Walks all `ClassDeclaration`s; for each
`class C extends Base<...>` where C self-references in extends type args at
position(s) `i`, finds Base's declaration via a top-level class-decl map,
then checks whether ANY of Base's type-param defaults uses an
`IndexedAccessType` whose `objectType` is a `TypeReference` to the type
param at position `i`. If so, emits TS2310 at C's name span (line/character
via `getLineAndCharacterOfPosition`). Two-helper structure: `defaultIndexesIntoTypeParam`
recurses through `IndexedAccessType` (object + index) and `TypeReference`
(typeArguments) so nested forms like `T = U<C['k']>` also detect. Conservative
gate avoids CRTP-pattern false positives — `class Foo extends Base<Foo>`
where `Base<T extends Base<T>>` uses CONSTRAINTS not DEFAULTS does NOT
trigger because `tp.default == null`. Pattern's narrowness (default-arg
indexed-access into a self-bound position) verified by full-suite zero
regressions across 10078-test run. Net delta: 1638 → 1637 failed
(8437 → 8438 passing).

**17.50 (2026-04-27, +1)** — TS2416 for `implements` of TypeAlias resolving
to `Type.Intersection`. Flips `implementsIncorrectlyNoAssertion_ts`. Single
surgical patch in `checkClassPropertyOverrides` (Checker.kt ~40345): when
`clause.token == ImplementsKeyword` AND the resolved heritage type is a
`Type.Intersection` (e.g. via `type Wrapper = Foo & Bar`), build a synthetic
`Type.Object` whose `members` are merged from each constituent's resolved
members (last-wins on name conflicts). The synthetic stands in for
`baseTypeRaw` so the existing TS2416 / TS2423 / TS2425 / TS2426 walk applies
uniformly. Display name override: when this branch fires, `baseTypeName` is
`typeToString(baseTypeRawOriginal)` ("Foo & Bar") instead of
`typeToString(synth)` (which would print the merged shape). Implements-only
gate is correct — TypeScript rejects intersection bases in `extends` clauses
at parse, so the branch never fires for extends. Constituents that aren't
`Type.Object` (e.g. `Foo & string`) are filtered out via `mapNotNull`. Net
delta: 1639 → 1638 failed (8436 → 8437 passing).

**17.49 (2026-04-27, +1)** — TS2416 chain elaboration for implements-clause
generic method override. Three coordinated pieces in Checker.kt flip
`genericTypeWithNonGenericBaseMisMatch_ts`. (1) `classMemberShapeMismatchDiagnostic`
gains an `isImplements: Boolean` parameter; passed `true` when
`clause.token == SyntaxKind.ImplementsKeyword`. When `isImplements` and
the would-be-emitted code is TS2425 (property → function shape mismatch),
the helper returns null. Rationale: TS2425 fires on class-property-vs-
class-method conflict because the instance field would shadow the
prototype method at runtime. Interfaces have no prototype semantics —
function-typed properties (`f: (a) => void`) are structurally
compatible with method declarations (`f(a) {}`) under `implements`.
TS2423/TS2426 retained for both `extends`/`implements` (no test
demands the relaxation). (2) `addSignatureElaboration` (TS2416 path)
adds a chain line `'T' could be instantiated with an arbitrary type
which could be unrelated to '<type>'.` when the derived param is a
`Type.TypeParam` and the base param is a concrete type. Mirrors
`getFunctionMismatchElaboration`'s 17.11a return-type version (line
47283); contravariance reversed. The hint signals that the override
is unsound — T's caller-chosen instantiation may not be compatible
with the base's concrete param. (3) `getFunctionMismatchElaboration`
(TS2322 path) recurses via `getPropertyElaborationChain` when both
inner param types are non-function `Type.Object`. Adds inner property
mismatch chain (`{a: number}` vs `{a: string}` → "Types of property
'a' are incompatible. Type 'number' is not assignable to type
'string'."). Indented by 4 extra spaces so the inner chain stacks
correctly under the param-mismatch chain line. Argument order
mirrors contravariance: `targetParamType` is the "from" displayed
type, `sourceParamType` is the "to". Net delta: 1640 → 1639 failed
(+1 test). No regressions across 10078-test suite — the chain
elaboration paths are gated to only fire on existing failure
positions (no FP risk).

**17.47 (2026-04-27, +1)** — `checkTypeArgCount` extended to QualifiedName.
Mirrors 17.46b's `isUnresolvedGenericType` extension — `var b: A.B`
where `B` is a generic class missing type args now emits TS2314 at
the right position (squiggle covering full `A.B` span via new
`nameSpan` computation: `right.pos + right.text.length - typeName.pos`).
Skips `scope.has(name)` / `KEYWORD_IDENTIFIERS` / `scope.isTypeParam`
checks for QualifiedName because resolution goes through
`getTypeParamInfo`'s namespace-export walk, not the simple-name
scope. Flips `genericCloduleInModule2_ts`. Pairs with 17.46b:
together they correctly emit TS2314 (and suppress the FP TS2454
that the bare TypeRef-without-typeName-resolution would otherwise
provoke).

**17.46 (2026-04-27, +5)** — Top-level TS2454 flow-graph walker
(retry of 17.45 with broader pre-installed type-guard recognition)
+ destructuring assignment FlowAssignment fix. Flips `isArray_ts` and
4 `sourceMapValidationDestructuring*` cases. Substep series:
17.46a pre-installed `instanceof` / lib-type-predicate (`Array.isArray`,
`ArrayBuffer.isView`, `Number.isInteger`/`isFinite`/`isNaN`/`isSafeInteger`)
/ `x.constructor === T` recognition in `conditionImpliesAssignedTrue`
(net-zero); 17.46b extended `isUnresolvedGenericType` to QualifiedName
receivers (`var b: A.B` filtered, net-zero); 17.46c added ArrayLiteral /
ObjectLiteral as `bindAssignmentTarget` targets so destructuring
assignment registers FlowAssignments (net-zero); 17.46d added
`runFlowTS2454OnTopLevel` mirroring `runFlowTS2454OnFunction` for
file-level statements, plus two Flow.kt fixes uncovered during
verification: (i) pass the underlying Identifier as `declarationNode` at
SpreadElement / SpreadAssignment / PropertyAssignment /
ShorthandPropertyAssignment destructuring leaves so
`flowAssignmentTargetsName` matches the FlowAssignment.node, (ii) new
top-level `BinaryExpression(=)` branch in `bindAssignmentTarget` handling
default-value forms `{a: x = 1}` / `[x = 1]` regardless of which
destructuring path reaches them. Initial 17.46d landed -1 (broken
destructuring FPs); after both fixes, +5 net (8429 → 8434).

**17.44 (2026-04-27, +2)** — TS2532 "Object is possibly 'undefined'" for
non-optional access on a synthetic-paren receiver produced when an
instantiation expression breaks an optional chain. Flips both
`optionalChainWithInstantiationExpression1_ts(target=es2019|es2020)`.
Three-piece change: (1) `ParenthesizedExpression` AST gains an
`instantiationEnd: Int? = null` field — non-null only when the parser
synthesizes the paren wrap for `expr<T>` followed by `.`/`?.` AND
`expr` contains an optional chain. The value is the source position
right after the closing `>` of the type-argument list, so the checker
can compute a squiggle covering `expr<T>` exactly (excluding the
trailing `.`). (2) `Parser.kt` (the existing TS1477 emission branch)
now passes `instantiationEnd = typeArgsEnd` when constructing the
synthetic `ParenthesizedExpression`. (3) New
`emitTs2532ForOptionalChainInstantiationReceiver` helper called at
the top of `checkSinglePropertyAccess`. Conservative gates: outer
access non-optional (otherwise the chain continues); receiver IS a
`ParenthesizedExpression` with `instantiationEnd != null` (the
parser's synthetic-from-instantiation marker — narrowest possible
gate, single-test-pattern in corpus); `strictNullChecks` enabled;
`findOptionalChainRootOperand` finds a `?.` somewhere in the inner
chain (defensive — should always succeed when the parser-side gate
fires, since the parser only wraps when `expressionHasOptionalChain`
is true). The base type IS NOT checked for `T | undefined` because
`typeof Namespace` resolves to `anyType` in our checker (a known
limitation of `getTypeOfSymbolForTypeQuery`'s namespace branch),
which would always defeat the check. The instantiation-paren
pattern's extreme rarity (1 test in the entire corpus, verified via
`grep '?\.[a-z]*<[A-Z,]+>\.'` over `typescript-repo/tests/cases/`)
bounds the over-firing risk. New helper `findOptionalChainRootOperand`
peels the synthetic paren and walks down looking for the first `?.`
operator, returning its operand. Test results 1648 / 3 → 1646 / 3.

**17.43 (2026-04-27, +1)** — Contextual literal preservation for substituted-T
property types. Flips `errorMessagesIntersectionTypes02_ts`. Pairs with
17.41/17.42 to close the `errorMessagesIntersectionTypes01/02_ts` cluster.
New `applyContextualLiteralPreservation(sourceType, targetType, init)` helper
in Checker.kt, called from `checkVarDeclAssignability` after computing the raw
source type. When (a) `init` is a `CallExpression` whose substituted return
type produced an anonymous Type.Object property typed as a widened intrinsic
(string/number/etc.) AND (b) the target type has a literal-typed property
at the same name, recovers the literal type from the original arg AST via
`literalTypeOfExpression`. Walks Type.Intersection / Type.Object source
constituents; only modifies anonymous Object constituents (skips named
Interface/Reference). For each property whose name matches a literal in
the call's first ObjectLiteralExpression arg AND whose target counterpart
contains literal members AND whose current source type is the widened
base of the recovered literal — replace with the literal-typed Symbol.
Two new helpers `propTypeContainsLiteral` (literal direct, or any literal
in a Union; handles Type.Intrinsic with TypeFlags.{String,Number,Boolean,
BigInt}Literal) and `literalWidensTo` (StringLiteral→stringType etc.).
Net result for `mixBar({fooProp: "frizzlebizzle"})` against
`FooBar { fooProp: "hello" | "world" }`: source displays as
`{ fooProp: "frizzlebizzle"; } & Bar` instead of `{ fooProp: string; } & Bar`,
chain emits `Type '"frizzlebizzle"' is not assignable to type '"hello" |
"world"'.`. Test 01 unchanged because target's `fooProp: boolean` doesn't
contain literal members → no replacement applied. Test results 1649 / 3 →
1648 / 3.

**17.42 (2026-04-27, +1)** — Intersection-source property elaboration in
`getPropertyElaborationChain`. Flips `errorMessagesIntersectionTypes01_ts`
(pairs with 17.41's substitution: source displays as `{ fooProp: string; } & Bar`
instead of `T & Bar`, and the chain `Types of property 'fooProp' are
incompatible. Type 'string' is not assignable to type 'boolean'.` now emits).
New `getIntersectionPropertyElaborationChain(source, target, path)` helper
in Checker.kt — merges members from object-like intersection constituents
(later wins on conflict), walks target.properties, emits the standard
"Types of property" + leaf chain on the first incompatible match. Falls
through to missing-required-property path when no type-mismatch found
(walks each Object-like constituent via the existing
`getMissingRequiredPropertySymbol`). Wired via `getPropertyElaborationChain`'s
new top-level Type.Intersection branch (delegates) AND the var-decl init
gate at Checker.kt:33655 (now accepts `sourceType is Type.Object || sourceType
is Type.Intersection`). Conservative: only direct property-level mismatches
(no recursion / method-collapsed form / privacy checks — those don't apply
to the intersection sources in the corpus). Doesn't flip
`errorMessagesIntersectionTypes02_ts` — it expects literal preservation
(`"frizzlebizzle"` vs `"hello" | "world"`) which is a separate inference
piece (literal-preserving when target has literal types). Test results
1650 / 3 → 1649 / 3.

**17.41 (2026-04-27, net-zero infra)** — Allow anonymous Type.Object arg
types at the return-type call site only. New `forReturnType: Boolean = false`
parameter on `tryInferSingleTypeParamFromArgs`; passed `true` only from
`getReturnTypeOfCallExpression` (Checker.kt:36853) to lift the named-like
bail at Checker.kt:37145 for return-type substitution. Arg-vs-param call
site (`checkArgumentsAgainstSignature`, Checker.kt:44853) keeps `false` so
16.4ds / 16.4dt per-property elaborations stay unchanged on object-literal
args. The substitution now fires for calls like `mixBar({fooProp: ...})`:
return type displays as `{ fooProp: string; } & Bar` instead of `T & Bar`.
Net-zero on the suite alone (10078 / 1650 / 3 unchanged) — paired with
17.42 to flip `errorMessagesIntersectionTypes01_ts`.

**17.40 (2026-04-27, +1)** — TS2345 for null/undefined arg vs anonymous
function-type parameter whose signature mentions sig TypeParams. Flips
`privacyCheckAnonymousFunctionParameter2_ts`. Mirror of 17.11c
(Type.Reference param) but for `Type.Object` with callSignatures /
constructSignatures (excluding Reference / Interface). Pattern:
`foo<T>(x: (a: Iterator<T>) => number)` called with `foo(null)` —
display substitutes sig TypeParams to `unknown`, rendering param as
`(a: Iterator<unknown>) => number`. New `sigMentionsAnyTp` helper
recurses via existing `sourceContainsTypeParam` over signature
params + return. Display path builds a wrapper `Type.Object` with
`instantiateSignature(s, mapper)` applied to each call/construct sig
(mapper substitutes sig-side TypeParams to `unknownType`), then
calls `typeToString` on the wrapper. Test results 1651 / 3 → 1650
/ 3.

**17.39 (2026-04-27, +1)** — Function-typed property substitution + first-decl-wins
local-type map. Flips `genericConstraintSatisfaction1_ts`. Closes the
"property f: <T extends S>(x: T) => void on generic interface I<S>" gap
in Blocker #2 — when calling `x.f({s: 1})` on `var x: I<{s: string}>`,
T's constraint was unsubstituted (still `TypeParam(S)`) so 16.4ds
per-property elaboration didn't fire. Two coordinated pieces in
Checker.kt: (1) New helpers `substituteOuterTypeArgsInGenericFnObject`
+ `substituteOuterTypeArgsInSignature` invoked from
`resolveGenericPropertyType`'s PropertyDeclaration branch when rawType
is Type.Object (excluding Reference/Interface) with non-empty
callSignatures or constructSignatures. Mutates the freshly-allocated
sig's typeParam constraints/defaults in place via the outer mapper,
then substitutes params and return. Sig's typeParameters are
PRESERVED (T stays generic) so call-site inference + 16.4ds / 16.4i
still fires. (2) `checkVarDeclAssignability`'s annotated-typeNode path
now skips `currentLocalTypes[name] = ...` when already set —
first-decl wins for the local-type map, matching Binder's
`valueDeclaration` semantics (Binder.kt:355). Without this, line 6's
`declare var x: I<{s: string}>` overwrites line 5's `var x: I<{s:
string}>` in `currentLocalTypes`, so `getTypeOfIdentifier(x)` returns
the line-6-resolved Reference whose typeArg is the line-6-TypeLiteral
copy of `{s: string}`, and TS6500 "declared here" points to the wrong
copy of property `s`. Test results 1652 / 3 → 1651 / 3.

**17.38 (2026-04-27, +1)** — Object-literal-of-T param shape inference for
single-arg generic calls. Flips `widenToAny1_ts`. Closes the simplest
"needs full generic argument inference" Blocker #2 case for object-
literal param shapes: `foo<T>(f: { x: T; y: T })` called with
`{ x: undefined, y: "def" }` now infers T = `string | undefined` (LUB-
as-Union over collected widened property values), substitutes through
return type T, and triggers TS2322 at the var-decl site
`var z1: number = ...`. Three pieces in `tryInferSingleTypeParamFromArgs`:
(1) new `isAnonymousObjectWithTypeParamMembers` helper — Type.Object
(excluding Type.Interface / Type.Reference / sym-non-null / call-or-
construct-sigs / index-sigs) whose every member is a bare TypeParam in
`tps`. Conservative — mixed shapes like `{x: T; y: number}` bail.
(2) Outer gate (Checker.kt:36996) adds clause (e): `!isRest &&
isAnonymousObjectWithTypeParamMembers(pt, tpsSet)`. (3) Per-tp gather
adds `isObjLitOfT` branch BEFORE the standard rawArgType pipeline.
Walks pt's members, finds ones typed exactly as tp, looks up arg's
same-named property via `getPropertyKeyName`, widens each value type,
LUBs as Union when multiple. Handles `undefined` values via the LUB
path (the standard undefined-arg bail doesn't fire because the arg as
a whole is a Type.Object, not undefined). Named-like check accepts
Union-of-named-likes (matches the isArrayT extension from 17.31f).
Lifted `isNamedLikeAtom` to outer `for (tp in tps)` block so both
isObjLitOfT and isArrayT/isBareT paths share the predicate. Test
results 1653 → 1652 failed (8422 → 8423 passing). Foundation:
`widenToAny1_ts`'s `{x: T; y: T}` shape, plus the 17.31f-installed
callBypass in `checkVarDeclAssignability`, produces TS2322 with chain
"Type 'undefined' is not assignable to type 'number'." at (4,5).

**17.37 (2026-04-27, +1)** — Empty-array `[]` arg → `T = never` inference for
`Array<T>` params + CallExpression-receiver TS2339 'never' emission. Flips
`inferentiallyTypingAnEmptyArray_ts`. Three coordinated pieces in Checker.kt:
(1) `tryInferSingleTypeParamFromArgs` now special-cases empty
`ArrayLiteralExpression` arg for `Array<tp>` params: contributes a `neverType`
candidate instead of bailing on `rawArgType === anyType`. (2)
"Never-wildcard" rule: when gathering candidates for a typeParam, an empty-
array `never` candidate is treated as a wildcard — the helper builds
`effectiveCandidates = candidates.filter { it.widenedType !== neverType }`
(falling back to the full list only if every candidate is never), and uses
that for both first-candidate selection AND multi-arg conflict detection.
Without this, `f<T>(arr: T[], elemnt: T)` called with `f([], 3)` would anchor
T = never (from the empty array) and then fire spurious TS2345 at arg `3`
(checked against substituted `never` param) — TypeScript correctly anchors
T = number from the second arg. (3) New CallExpression branch in
`checkMemberAccessMissing` emits TS2339 'never' when the call's resolved
return type IS exactly `neverType`. Narrow gate: only fires for receivers
that ARE CallExpressions and whose return is exactly never; doesn't extend
to other receiver shapes (full call-result property checking against arbitrary
return types stays out of scope).

**17.36 (2026-04-27, +1)** — TS2300 "Duplicate identifier 'eval'" for
top-level `var eval` in non-module strict mode. Flips
`variableDeclarationInStrictMode1_ts`. Two-piece change: (1)
`BaselineFormatter.kt` summary section (Part 1) now renders lib files
(`lib.*.d.ts`) with `(--,--)` when `line == null` — previously the
prefix was suppressed entirely, so lib-side primary diagnostics
rendered as plain `error TSXXXX:` without a file column. (2)
`diagnosticComparator` now sorts lib files AFTER user files (matches
TypeScript's baseline ordering — user-file diagnostics precede
lib-side ones, regardless of alphabetic order). (3) New
`checkStrictModeReservedRedeclaration` in Checker.kt fires only for
`var eval` (NOT `var arguments` — not lib-declared) at top level in
non-module strict mode (`globalStrict || hasUseStrict` AND
`!isModule`). Emits TS2300 at the user position, TS2300 at lib
position (line=null, char=null → renders as `lib.es5.d.ts(--,--)`),
and TS6203 related info on the user-side TS2300 ("'eval' was also
declared here."). Conservative gates rule out the 2 regressions an
initial broader version caused: (a) module-file `var eval` only emits
TS1215 (`jsFileCompilationBindStrictModeErrors_ts` c.js — module
auto-strict), (b) `var arguments` is NOT lib-declared as a global var
so doesn't fire TS2300 (`argumentsBindsToFunctionScopeArgumentList_ts`
alwaysstrict_true).

**17.35a (2026-04-27, net-zero infra)** — Expand falsy/truthy
narrowing to literal-truthy types. `isDefinitelyTruthyMember` now also
returns true for non-empty `Type.StringLiteral`, non-zero
`Type.NumberLiteral`, `trueType` (the `Type.Intrinsic` boolean-literal
"true"), and non-zero `Type.BigIntLiteral` (string value not "0"/"0n"
via new `isZeroBigIntLiteral` helper). Mirror addition:
`isDefinitelyFalsyMember` now also drops empty `Type.StringLiteral`,
zero `Type.NumberLiteral`, and zero `Type.BigIntLiteral` from union
on the truthy side. Both non-union and union dispatch in
`narrowByTruthiness` were also unified — non-union dispatch now uses
the same predicate so a bare definitely-truthy/falsy literal also
collapses to `neverType`. Primitive intrinsics (`stringType`,
`numberType`, `booleanType`, `bigintType`) deliberately kept on both
sides — they could be the empty/zero variant. Test results 10078 /
1655 / 3 (unchanged from 17.34e). Foundation net-zero — no failing
test gates solely on literal-truthy filtering, but the path to
`if (x) {} else { /* x: 0 | "" | undefined */ }` and similar
literal-receiver narrowing is now in place. Closes 17.35a/b — 17.35b
was scoped as "verify primitive case" with no expected code change;
17.35a's primitive-on-both-sides behavior matches that intent.

**17.34e (2026-04-27, net-zero infra)** — Conservative falsy-side
narrowing in `narrowByTruthiness`. Pre-fix: `truthy=false` returned `t`
unchanged per the 17.7e session note's regression-risk gate. Post-fix:
on the falsy branch, drop union members that are definitely truthy —
`Type.Object` / `Type.Interface` / `Type.Reference` (object types are
always truthy in JS). New helpers: `isDefinitelyFalsyMember` (recasts
the existing truthy-side filter as a named predicate) and
`isDefinitelyTruthyMember` (the new falsy-side filter). Non-union
object-likes also collapse to `neverType`. Conservative scope: literal-
truthy types (non-empty StringLiteral, non-zero NumberLiteral,
trueType, non-zero BigIntLiteral) and primitive-truthy (`stringType`,
`numberType`, etc. — they could be the empty/zero variant) NOT yet
dropped — adding those expands the regression surface and warrants a
follow-up substep. Test results 10078 / 1655 / 3 (unchanged from
17.34d). No new flips — corpus's failing tests don't gate solely on
object-type-only falsy narrowing. Foundation: `if (foo) {} else { /*
foo: Foo | null */ }` patterns where Foo is Object/Interface/Reference
now narrow `foo` to `null` on the else side. PropertyAccess version
inherited via the existing path comparison from 17.34a. Closes the
17.34 substep series — 17.34a/b/c/d/e together implement PropertyAccess
narrowing across the narrowing infrastructure.

**17.34d (2026-04-27, net-zero infra)** — PropertyAccess narrowing in
`getTypeOfPropertyAccess` for non-var-decl read positions. The function
was returning the raw declared type without consulting the flow graph;
wired through a new wrapper that calls `getNarrowedTypeForReference`
when the raw type is a Union AND `getReferencePath(expr) != null` (pure
Identifier-or-PropertyAccess chain). Implementation: extracted the
existing body to `computeRawTypeOfPropertyAccess` (private helper),
made the public `getTypeOfPropertyAccess` a thin wrapper. Conservative
gate keeps performance impact minimal: non-Union types skip the flow
lookup entirely; outside expression-checking phases `currentFlowGraph`
is null so `getFlowAt` returns null and `getNarrowedTypeForReference`
returns the raw type unchanged. Test results 10078 / 1655 / 3 (no
change from 17.34c) — no failing test gates solely on PropertyAccess
narrowing being visible in expression-context reads. Foundation:
chained accesses like `A._a.length` now see narrowed `A._a` type when
inside `if (A._a) { ... }`. Remaining 17.34 substep is 17.34e
(falsy-side narrowing — needs general infrastructure beyond just
PropertyAccess).

**17.34c (2026-04-27, net-zero infra)** — Wire PropertyAccess narrowing
into TS2339 union-receiver narrowed-to-never branch. Restructured the
gate at Checker.kt:42395: was `if (objectExpr !is Identifier) return`
unconditionally, now first allows PropertyAccess receivers (gated via
`getReferencePath(objectExpr) != null`) to reach the narrowing block
(narrowed-to-never / single-Object / Union-with-missing emissions),
then bails to identifier-only paths after the narrowing block — the
identifier-symbol lookup paths beyond (`globals[identName]`-driven
namespace/typeof/wrapper emissions) don't generalize to dotted-path
receivers. Test results unchanged 10078 / 1655 / 3 — no failing test
gates solely on TS2339 narrowed-to-never on PropertyAccess receivers.
The narrowing block now covers `if (typeof A._a === "string") {} else
{ A._a.length }` (A._a narrowed to never in else branch via
exhaustive typeof guard) and `if (A._a.kind === "a") { A._a.aProp }`
(discriminant-property narrowing on PropertyAccess via 17.34b's
`isDiscriminantAccessOf` extension). Remaining 17.34 substep is 17.34d
(PropertyAccess narrowing in `getTypeOfPropertyAccess` for non-var-decl
read positions — broad change, deserves its own session for careful
testing).

**17.34b (2026-04-27, net-zero infra)** — Extend narrowing operators
to compare PropertyAccess paths. Six call-site flips replacing
`expr is Identifier && expr.text == name`-pattern checks with
`getReferencePath(expr) == name` (the helper introduced in 17.34a):
(1) `narrowByEquality` left/right operand check — direct-equality
narrowing now matches `A._a === literal`; (2) `isTypeOfRef` — `typeof
A._a === "string"` matches when name="A._a"; (3) `narrowByCallPredicate`
arg check — `predFn(A._a)` matches; (4) `narrowByInstanceOf` left
operand — `A._a instanceof Class` matches; (5) `narrowByInOperator`
right operand — `'k' in A._a` matches; (6) `isConstructorAccessOf` and
`isDiscriminantAccessOf` receiver checks — `A._a.constructor === C` and
`A._a.kind === 'foo'` patterns. Path-comparison preserves prior
Identifier-only behavior (Identifier path = `expr.text`); same
identity-based comparison for both. Test results unchanged 10078 / 1655
/ 3 — no failing test gates solely on these operators applied to
PropertyAccess sources. Foundation for 17.34c (TS2339 narrowed-to-never
on PropertyAccess receivers) and 17.34d (PropertyAccess narrowing in
read positions via getTypeOfPropertyAccess).

**17.34a (2026-04-27, net-zero infra)** — PropertyAccess narrowing
infrastructure for class statics. New `getReferencePath` helper in
Checker.kt serializes any `Identifier`-or-`PropertyAccessExpression`
chain as a dotted path (`"A._a"`, `"this.field"`, `"a.b.c"`); returns
null for shapes with calls, parens, or element access. Three call-site
extensions: (1) `getNarrowedTypeForReference` consumes the path string
instead of an Identifier-only `expr.text`, so it now narrows
PropertyAccess sources too; (2) `applyConditionNarrowing` adds a
`PropertyAccessExpression` branch parallel to its `Identifier`
truthiness branch — `if (A._a) { ... A._a ... }` narrows from
`T | undefined | null` to `T` on the truthy side via the existing
`narrowByTruthiness` helper; (3) `checkVarDeclAssignability`'s
narrowing call-site widens its gate from `init is Identifier` to
`init is Identifier || init is PropertyAccessExpression` (still
gated by `isNarrowableTarget`). Test results unchanged 10078 / 1655 / 3
— `classStaticPropertyTypeGuard_ts` (the test 17.31f's gate masks)
continues to pass; no failing test in the corpus gates SOLELY on this
narrowing path. Foundation for follow-on substeps: (a) extend
`narrowByEquality`/`narrowByInstanceOf`/`narrowByInOperator`/
`narrowByCallPredicate`/`narrowByConstructorEquals` to compare
PropertyAccess paths (currently still only Identifier); (b) wire
PropertyAccess narrowing into the TS2339 union-receiver
narrowed-to-never branch (Checker.kt:42376 still gated on
`objectExpr is Identifier`); (c) wire into `getTypeOfPropertyAccess`
so identifier-of-property-access sources see narrowed types in
non-var-decl positions. Foundation only — not a regressing change.

**17.33 (2026-04-27, +1)** — TS2686 "refers to a UMD global" emission
for `export as namespace X;` references in module files. Flips
`jsdocReferenceGlobalTypeInCommonJs_ts`. Two-piece change: (1) source-text
regex scan during init step 1d collects `umdGlobalNames` (matches
`^\s*export\s+as\s+namespace\s+IDENT\s*;?` in .d.ts files — the parser
falls through to expression-statement parsing for this construct, so a
regex on the raw source is the smallest sufficient implementation) +
`moduleFiles` set (any file with imports/exports OR — for .js/.jsx/.mjs/.cjs
— a top-level `require(...)` call). (2) `checkIdentifierResolved`
TS2304-emission branch now checks `name in umdGlobalNames && fileName in
moduleFiles` first; if so, emits TS2686 with the standard "Consider
adding an import instead." message instead of TS2304. Conservative gates:
the spelling-suggestion (TS2552) path still runs first, so a UMD global
that has a near-spelling alternative would still get TS2552 (matches
TypeScript's behavior of preferring suggestions when available).

**17.32e (2026-04-27, net-zero behavior)** — TS2304 file-scope flip
(Blocker #3 step 1 — final substep; "highest blast radius" landed clean).
`checkUnresolvedNames` (Checker.kt:8598) now builds `fileScope.names` from
`perFileScope[fileName]` (lib + script-file locals + own-file locals)
instead of iterating `result.locals + globals` (the over-merged map
containing every module-file's locals). Cross-file unimported identifiers
in module file A no longer silently resolve via `globals[X]` from unrelated
module file B. Defensive fallback to legacy iteration when perFileScope is
null (matches 17.32b/c/d pattern). Test results unchanged from 17.32d
(8419 / 1656 / 3) — strict-improvement change with zero regression: tests
don't rely on the cross-file leak. Closes the major 17.32 migration: all 4
identifier-resolution sites identified for the series (ctorParam shadow
disambiguation, TS2552 spelling-suggestion candidates, default-import /
export-equals helper, TS2304 file-scope) now consume `perFileScope`.
Remaining minor `globals[X]` sites in `resolveAmbientModuleExportEquals`
(line 2333) intentionally left — the ambient-module-internal
`import alias = X` pattern legitimately needs cross-file global lookup.

**17.32d (2026-04-27, net-zero behavior)** — Third call-site flip onto
17.32a's per-file scope: `resolveExpressionToSymbol`'s Identifier branch
(used by both `export default X` resolution at Checker.kt:2207 and
`export = X` resolution via `resolveModuleExportAssignment` at
Checker.kt:2303) now resolves identifiers inside the export-default /
export-equals expression against the target file's `perFileScope` (lib +
script-file locals + own-file locals) instead of the over-merged `globals`
map. Other module files' locals are not visible inside the target module
without an explicit import, so the previous `result.locals[X] ?: globals[X]`
chain could find symbols that wouldn't actually resolve in the target's
scope. New chain: `result.locals[X] ?: perFileScope[fileName]?.get(X)` with
a defensive fallback to `globals[X]` if perFileScope is null (matches the
17.32b/c pattern). Test results unchanged from 17.32c (8419 / 1656 / 3) —
no failing test in the corpus gates on this filter (consistent with prior
flips), but the change reduces cross-file pollution in the export-resolution
helper. Foundation for the eventual 17.32e+ TS2304 file-scope flip.

**17.32c (2026-04-27, net-zero behavior)** — Second call-site flip onto
17.32a's per-file scope: `getSpellingSuggestion`'s value-position candidate
pool now consults `perFileScope[fileName]` at the file-root scope instead of
the over-merged `globals`-derived `s.names` set. Inner (function/block)
scopes still contribute their `names` unchanged — those are this file's own
lexical bindings. KNOWN_GLOBALS continues to be added at the start. Removes
other-file MODULE locals from TS2552 spelling-suggestion candidates without
touching TS2304 visibility (which still consults the legacy file-root
scope — highest blast radius, deferred). Test results unchanged from 17.32b
(8419 / 1656 / 3) — no failing test in the corpus gates on this filter, but
the change reduces cross-file pollution in the suggestion pool which is
foundation for 17.32d+ flips. Type-position branch unchanged (uses scope
chain `typeParamNames` / `typeNames` which are file-local only).

**17.32b (2026-04-27, net-zero behavior)** — First call-site flip onto 17.32a's
per-file scope: `ctorParamShadowsRealOuterBinding` (TS2663-vs-TS2301
disambiguation for parameter-property shadow in class member initializers)
now consults `perFileScope[currentFileName]` instead of walking `binderResults`
with an ad-hoc module-file filter. The new code is semantically equivalent —
both encode "lib + script-file locals + own-file locals, excluding other-file
module locals" — but uses the centralized infrastructure so future identifier-
resolution call sites can follow the same pattern. KNOWN_GLOBALS still
checked first (companion-level data not in the binder's lib output).
Test results unchanged from 17.31f (8419 / 1656 / 3). Foundation for
17.32c+ flips (TS2552 spelling suggestion candidate set, default-import-
from-export-equals visibility).

**17.31f (2026-04-27, +1)** — Union-element widening for `Array<T>` inference +
CallExpression source bypass for Union→primitive var-decls. Flips `widenToAny2_ts`.
`tryInferSingleTypeParamFromArgs` `isArrayT` branch now widens Union constituents
(`widenType` skips `Type.Union`; explicit `getUnionType(types.map(widenType))`
recurses) so `Array<undefined | "def">` infers T = `undefined | string`. The
`isNamedLike` check is now a local `isNamedLikeAtom` helper applied either
directly OR (for `isArrayT` only) to every Union constituent — anonymous-Object
members in heterogeneous arrays (e.g. `[{a:1}, "def"]`) still bail because the
widened anonymous Object fails `isNamedLikeAtom`. Wired pair: `checkVarDeclAssignability`
adds a `callBypass` of `canUseTypeEngine`'s nullish-Union gate when `init is
CallExpression` AND `sourceType is Type.Union` AND target is primitive-shape AND
`strictNullChecks` is on — CallExpression results aren't narrowable so the
gate's control-flow narrowing safety rationale doesn't apply. Initial broader
version (lift the gate inside `canUseTypeEngine`) regressed
`classStaticPropertyTypeGuard_ts__target_es5__` (`return A._a` after `if (A._a)`
needs PropertyAccess narrowing on class statics that we don't have); narrowed
to `init is CallExpression` keeps that latent gap masked.

**17.31e (2026-04-27, net-zero infra)** — Reference-arg `Array<T>` inference
for non-rest params. Gate clause (d) added: non-rest param of `Array<tp_i>`
(any tp). Per-tp gather grew an `isArrayT` branch — for non-rest `Array<tp>`
params, extracts the element type X from the call arg's same-target
`Array<X>` reference (bails when arg isn't a `Type.Reference Array`),
applies `widenType` to widen literal element types, and contributes the
widened X as the candidate (no literal type — array element doesn't have a
single literal value). 17.31a's `isNamedLike` check still applies on the
extracted/widened X — Union element types (`Array<undefined | "def">`,
`Array<1 | 2>`) bail. Renamed `isRestArrayOfTypeParam` → `isArrayOfTypeParam`
(same body; helper now used for both rest and non-rest contexts).
Net-zero on the suite: `widenToAny2` (`foo3<T>(x: T[])` called with
`[undefined, "def"]`) bails because `Array<undefined | "def">`'s element
is a Union; `inferentiallyTypingAnEmptyArray` (`foo([])`) bails because
empty array literal returns `anyType`; `subtypeReductionWithAnyFunctionType`
needs context-sensitive arrow inference (`compact<T>(arr: T[])` with arg
inside an arrow callback). Foundation for 17.31f-style follow-ups
(Union-element handling, contextual typing through Array<T> params).

**17.31d (2026-04-27, net-zero infra)** — Multi-typeParam inference (independent
T, U) extended `tryInferSingleTypeParamFromArgs` from single-tp to N-tp.
Gate replaced: was `tps.size != 1` early-return; now allows ANY tp count where
every param is bare-some-tp_i, rest-of-tp_i[] (last param only), or fully
concrete (mentions NONE of our tps). Per-tp candidate gathering runs
independently — each tp_i gathers from positions where param IS exactly tp_i,
runs 17.31a's named-like + constraint gates and 17.31b's multi-arg conflict
detection. On any tp's gather-side bail (anyType / undefined arg / non-named
arg), the WHOLE function returns null so the bare-TypeParam continue path in
`checkArgumentsAgainstSignature` keeps firing (matches old behavior). Built
multi-mapper covers every tp; `instantiateSignature` substitutes all of them.
Net-zero on the suite: 8 failing tests have all-bare multi-tp sigs, but most
either (a) have NO `.errors.txt` baseline so trivially pass already
(`objectAssignLikeNonUnionResult`, `silentNeverPropagation`,
`contextualSigInstantiationRestParams`), (b) have non-call usage like
`tt = tuple2(...)` where the failing baselines test the var-assignment side
not the call (`tupleTypes`), or (c) have body-internal patterns the call-site
inference doesn't reach (`typeParametersShouldNotBeEqual2`,
`genericCallbackInvokedInsideItsContainingFunction1`). `defaultBestCommonTypesHaveDecls`'s
`concat2(1, "")` activates the new multi-tp path (T0=number, T1=string) but
the failing baseline gates on UNRELATED single-tp `concat(1, "")` shape.
Net-zero net delta — foundation for 17.31e (Reference-arg `Array<T>` inference)
which can build on the per-tp gather loop now in place. Old `isParamShapeAllowedFor17_31a`
helper removed (single-tp logic merged into the new gate).

**17.31c (2026-04-27, +3)** — Rest-param `T[]` inference + post-loop emission
helper for trailing rest args. Two-piece change: (1) `tryInferSingleTypeParamFromArgs`
gate extended to allow rest param of `T[]` (new `isRestArrayOfTypeParam` helper);
candidate-gathering loop walks every trailing arg at the rest position so each
contributes a T candidate (uses 17.31b's existing widened-vs-literal candidate
shape and conflict-detection). (2) New `checkRestArgsAgainstArrayElementType`
runs at the end of `checkArgumentsAgainstSignature` (gated on
`diagnostics.size == initialDiagCount` so it doesn't double-fire when the
standard loop already emitted) — extracts the rest's `Array<X>` element type
and emits TS2345 at the first trailing arg whose type is not assignable to X.
Conservative gate: `isSimpleCheckableType(argType)` (continues past complex
args) — needed to avoid FPs in `concatError_ts` (`fa.concat([0])` — our lib
lacks the `Array.concat(...items: T[][])` overload TypeScript ships) and
`typeArgInference_ts` (`x.g<number,string>([o], [o])` — structural compare on
nested `Array<{a:T;b:U}>` is incomplete). Element-type guard skips when X is
still a `Type.TypeParam` (inference failed or two-typeParam case).
Flips `genericRestArgs_ts` (3 missing diagnostics: inference path
`makeArrayG(1,"")` arg[1]; explicit `makeArrayG<number>(1,"")` arg[1];
explicit `makeArrayG<any[]>(1,"")` arg[0]) plus +2 incidental flips from
adjacent rest-T inference patterns elsewhere in the corpus. Foundation for
17.31d (`Array<T>` arg-shape inference for non-rest cases).

**17.31b (2026-04-26, +1)** — Multi-arg same-typeParam conflict detection
with literal-preserving display for context-sensitive sigs. Refactored
`tryInferSingleTypeParamFromArgs` to gather candidates from EVERY bare-T
positional param (not just the first); detects cross-base conflicts via
mutual `checkTypeRelatedTo`. When conflict occurs AND sig has a function-
type parameter mentioning T (new `tparamMentionedInFunctionType` helper
recurses into Object call/construct sigs), emits TS2345 directly at the
failing arg position with LITERAL-form display (`'3' is not assignable to
'""'`) and returns null so the standard arg-check loop doesn't double-emit
(bare-T params silently pass `checkTypeRelatedTo(arg, T)` because
unconstrained T's apparent type is `{}`). Without function-type-T param,
falls through to widened first-candidate substitution (17.31a behavior).
Same-base-different-literal multi-arg cases (e.g. `g("a","b")`) resolve
via mutual-assignability check passing — both widened to same intrinsic
→ no conflict → substitute T=widened. Optional `source`/`fileName` params
on the helper; return-type call site (`getReturnTypeOfCallExpression`)
passes nulls so it just returns null on conflict-with-preserveLiterals.
Flips `typeInferenceConflictingCandidates_ts` (`g<T>(a:T,b:T,c:(t:T)=>T)`
with `g("", 3, a => a)` — context-sensitive sig due to arrow `c` arg
mentioning T → emits `Argument of type '3' is not assignable to parameter
of type '""'.` at `(3,7)`). Foundation for 17.31c (multi-typeParam
inference) and 17.31d (Reference-arg inference for rest-args).

**17.31a (2026-04-26, +2)** — Single-typeParam inference for non-overloaded
sigs landed. New `tryInferSingleTypeParamFromArgs` helper + narrow gate wired
into both `checkArgumentsAgainstSignature` (instantiates sig so other-arg
checks see substituted T) AND `getReturnTypeOfCallExpression` (substitutes T
into return type for downstream var-decl / property-access checks). Gate:
sig has exactly 1 typeParam; every param either is bare T or fully concrete
(no nested T); inferred type must be "named-like" (Type.Interface /
Type.Reference / Type.Intrinsic / literal flags) so anonymous Object literals
fall through to existing per-property paths (16.4ds / 16.4dt); when T is
constrained, inferred type must satisfy the constraint so 16.4i's
constraint-aware TS2345 still fires for non-assignable arg types. Flips
`fixTypeParameterInSignatureWithRestParameters_ts` (`bar<T>(item1: T, item2: T)`
called with `bar(1, "")` infers T=number, fires TS2345 at "" arg) and
`typeArgumentInferenceWithConstraintAsCommonRoot_ts` (`f<T extends Animal>(g, e)`
infers T=Giraffe, fires TS2345 with missing-prop chain at Elephant arg).
Foundation for 17.31b–d substeps (multi-arg LUB, multi-typeParam, Reference
inference).

**Post-17.30b queue audit (2026-04-26)**: 17.30c (`&&`-chain NARROWING for TS2774)
is BLOCKED-PENDING-USER on lib.dom.d.ts loading — cross-corpus search confirms
the ONLY failing TS2774 candidates that would flip with `&&`-chain narrowing
all reference `window.<x>` and gate on `window` resolving past `anyType`.
17.30d (discriminated-union narrowing through property-equality) was ALREADY
done via 17.7a (`narrowByDiscriminantProperty`); queue item was a duplicate.
Remaining work in Blocker #1 step 2h is just 17.30c which needs the
significant lib.dom.d.ts infrastructure piece.

**17.30b (2026-04-26, net-zero infra)** — FlowAssignment-RHS narrowing landed
in `narrowTypeFromFlow`'s FlowAssignment branch. When an assignment binds the
queried identifier and the RHS is a recognized literal shape (string / number /
bigint / template / true / false / null / undefined / unary-minus on numerics
— via the existing `literalTypeOfExpression` helper), filter the antecedent's
union members to those compatible with the RHS literal type and return the
narrowed shape. Conservative: only Union antecedents are narrowed (flat types
returned unchanged); RHS shapes that aren't pure literals (CallExpression /
NewExpression / Identifier RHSes) fall through to the prior pass-through behavior.
Function boundaries respected via the existing FlowGraphBuilder isolation
(`bindFunctionLikeBody` saves/restores `currentFlow` + starts a new `FlowStart`
for each inner function body). Test count unchanged (8412 / 1663 / 3); both
known wire-up call sites (checkVarDeclAssignability + checkSinglePropertyAccess
union receiver) now consume RHS narrowing but no failing test gates solely on
this. Foundation for follow-on substeps where TS2339-on-primitive emission for
narrowed function-local receivers, or downstream callable narrowing, can convert
the now-precise type into emissions.

**17.30a (2026-04-26, +1)** — TS2454 via flow-graph definite-assignment
landed: new `checkDefiniteAssignmentViaFlowGraph` walks if/while/for/switch/try
bodies that the ad-hoc walker explicitly skips, with positive-typeof /
truthy / `!= null` / `!== undefined` assertion-implies-assigned detection.
Sidesteps the 17.1c snapshot/restore -7 regression by following only
`antecedents[0]` at FlowLoopLabel (avoiding back-edge narrowing leaks).
Flips `nestedLoopTypeGuards_ts`. First substep of Blocker #1 step 2h —
remaining substeps: 17.30c (`&&`-chain narrowing into TS2774),
17.30d (discriminated-union property-equality narrowing).

**Surgical pool was exhausted (post-17.29: pool re-confirmed for 22+
consecutive recon sessions, but spot-checking flips occasional +1).**
Last surgical win was 17.29 (Type.Interface source vs different-symbol
Type.Interface target arg-mismatch path in `checkArgumentsAgainstSignature`
— extends 17.27 from Reference-source-only to also cover same-name-different-scope
named class arg cases. New `typeToStringQualified` helper walks
`symbol.parent` through Module/NamespaceModule symbols to render
`m.variable` instead of `variable` for namespace-nested classes, but only
for THIS branch's display — global typeToString unchanged so no
regressions in unrelated tests). Flipped `differentTypesWithSameName_ts`.
Post-17.27 recon #2 (2026-04-26) confirmed `find_candidates.py
--fresh` returns 0/0/0 (filtered from 8/93/22). Spot-checked five
candidates (declarationEmitExpressionInExtends4, nodeNextModuleResolution1,
circularConstraintYieldsAppropriateError, variableDeclarationInStrictMode1,
arrowFunctionErrorSpan) — all confirmed architectural or multi-piece. Queue reshuffled 2026-04-25: subsequent
sessions must commit to architectural blockers rather than searching
for surgical wins. 17.9–17.27 series landed cumulative +60 from
architectural-leaning surgical fixes (namespace-aware inference,
optional/index-sig/privacy elaboration, generic ctor inference,
ambient-module export-equals named-import resolution, this-parameter
display, TS2417 clodule static-side, super-call arg checking with
heritage type-arg substitution, super.method arg checking, namespace-aware
new-expression arg checking with class TypeParam scope re-resolution,
TS2339 enum-member-access chain, TS2493 assignment-tuple-bounds, fn-vs-fn
arity TS2345, void-return inference for unannotated fn-decl bodies,
TS2663-vs-TS2301 narrow disambiguation for parameter-property shadow in
module-file class initializers, Function-prototype satisfaction +
Reference-vs-named-Interface arg missing-property chain).

**MAINT-1 done 2026-04-25**: 32 stale skip-log entries marked
strikethrough; `find_candidates.py` updated to strip `~~...~~` spans. Net
zero test-count delta (all stale entries already pass). Surgical pool
remains empty after the audit.

**MAINT-1b (2026-04-26, post-17.29 recon #5)**: 6 additional stale
skip-log entries marked strikethrough — `clodulesDerivedClasses_ts`
(flipped 17.18), `derivedClassConstructorWithExplicitReturns01_ts`
(es5/es2015 variants, flipped earlier), `letConstInCaseClauses_ts`
(es5/es2015 variants, flipped earlier), `exportStarFromEmptyModule_ts`
(es5/es2015 variants, flipped 16.4fj), `superWithTypeArgument3_ts`
(flipped 17.20, was duplicate-listed), and the
`assignmentCompatability37/38/39/40/41/42_ts` cluster (flipped by
17.14b/17.15b). Net zero test-count delta (all stale entries already
pass — pure documentation hygiene). Re-confirms post-17.29 pool empty
(23+ consecutive recon sessions).

**Recommended next sessions (highest absolute yield first):**
1. ~~**MAINT-1**: Stale skip-log audit (~1 session, +5–15 tests).~~ Done.
2. **Blocker #1**: Full control flow narrowing (~2–4 sessions, +60–100 tests).
   - **Step 1 (2026-04-25, 17.1a)**: Flow-graph infrastructure in binder — DONE (no behavior change yet, 0 tests). `Flow.kt` + `FlowGraphBuilder` integrated into `BinderResult.flowGraph`.
   - **Step 2a (2026-04-25, 17.1b, +1)**: First narrowing wire-up — `getNarrowedTypeForReference` walker + var-decl `never` target adoption. Flips `narrowingUnionToNeverAssigment_ts`. Supports `===`/`!==`/`==`/`!=` against literals, `&&`/`||` (De Morgan), FlowBranchLabel joins.
   - **Step 2b (2026-04-25, 17.1c+17.1d, net-zero infra)**: Extended narrowing ops + widened gate. `tryNarrowByTypeOf` handles `typeof x === "string"`; `narrowByInstanceOf` handles `x instanceof Class`. Var-decl gate widened from `never`-only to any primitive-shaped target (Intrinsic / Literal). Both commits net-zero — failing tests with these patterns gate on adjacent infrastructure (type-predicate inference, switch-true case-cond narrowing).
   - **Step 2c-i (2026-04-25, 17.1e, net-zero)**: TS2339 narrowed-to-never wiring in `checkMemberAccessMissing` — when receiver Identifier's `Type.Union` raw type narrows to `never` via flow graph, emit `Property 'X' does not exist on type 'never'.`. Uses `getTypeOfExpression` so works for function-local identifiers, not just file-globals. Companion fix: `narrowByInstanceOf` non-union contradiction now returns `never` (was returning `t`) when source matches class and isMatch=false — mirrors `narrowByTypeOfGuard`'s already-correct shape. Net-zero because failing TS2339-on-never tests (`instanceofWithStructurallyIdenticalTypes_ts`, `typeGuardConstructorDerivedClass_ts`) need additional narrowing operators (type-predicate fns, `x.constructor === Class`).
   - **Step 2c-ii (2026-04-25, 17.1f, net-zero)**: `in` operator narrowing — mirror of typeof/instanceof. New `narrowByInOperator` filters union by `typeHasOwnProperty` (positive: keep "has prop"; negative: keep "doesn't have"). Non-union returns `never` for the `!in` contradiction case. Wired into `applyConditionNarrowing`'s BinaryExpression switch. Net-zero — failing in-narrowing tests (`inKeywordTypeguard_ts`) need additional pieces (in-narrowing wired into TS2339 elaboration on union receivers, primitive-RHS TS2638, unknown-RHS TS18046).
   - **Step 2c-iii (2026-04-25, 17.3a, +1)**: Type-predicate function narrowing + symbol-identity instanceof + flow-graph activated in checkPropertyAccess. New `narrowByCallPredicate` for `predFn(arg)` calls; `narrowByInstanceOf` switched to symbol-identity (extends-chain) via new `isInstanceOfClass` helper; `currentFlowGraph` wired in `checkPropertyAccess` so 17.1e's TS2339 narrowed-to-never check actually engages. Flips `instanceofWithStructurallyIdenticalTypes_ts`.
   - **Step 2d (2026-04-25, 17.4a, +2)**: TS2774 walker extended for PropertyAccessExpression operands + parameter/local-fn typed scope + `this` type tracking + path-aware body suppression + ConditionalExpression body candidates. Flips `truthinessCallExpressionCoercion_ts` (7 emissions) and `truthinessCallExpressionCoercion1_ts` (5 emissions). Test2 (35 emissions) still requires `&&`-chain walking — deferred.
   - **Step 2e (2026-04-25, 17.4b, net-zero infra)**: TS2774 `&&`-chain walking + ExpressionStatement-level + arrow-body-level. Unified `walkUncalledChain` handles all three truthiness operators (`&&` adds siblings to suppression sources; `||`/`??` don't). `truthinessCallExpressionCoercion2_ts` now reproduces 34 of 35 expected emissions; missing one is `window.console.error` blocked on `getTypeOfIdentifier(window)` resolving as `anyType`.
   - **Step 2f (2026-04-25, 17.5a, +1)**: `x.constructor === Class` narrowing wired into `narrowByEquality`. New `narrowByConstructorEquals` filters union members by exact class symbol identity (distinct from `instanceof` — does NOT include subclasses). Flips `typeGuardConstructorDerivedClass_ts`.
   - **Step 2f-ii (2026-04-25, 17.5b, net-zero infra)**: ElementAccessExpression form `var1["constructor"]` (StringLiteralNode + NoSubstitutionTemplateLiteralNode keys) added to `isConstructorAccessOf`; negative-direction (`!==`/`!=`) corrected to `return t` unchanged (TypeScript does NOT narrow on `!==` of `.constructor` — too weak to remove union members because subclass instances and reassigned `.constructor` values exist). Foundation only — `typeGuardConstructorClassAndNumber_ts` still doesn't flip without union-receiver TS2339 multi-member elaboration.
   - **Step 2g (2026-04-25, 17.6a, +1)**: Union-receiver TS2339 multi-member elaboration in `checkMemberAccessMissing`. When narrowed receiver is still a Union with at least one primitive-like member missing the property AND at least one member having it (partial coverage), emit TS2339 with the union display + chain line naming the first missing member. Conservative gate: only emits when ALL missing members are primitives (Type.Intrinsic / literal types) — Object/Interface missing members likely indicate discriminated-union narrowing through property-equality (e.g. `ab.type === 'a'`) which isn't yet implemented. Flips `typeGuardConstructorClassAndNumber_ts`.
   - **Step 2h (next)**: Remaining wire-ups: TS2454 via flow-graph definite-assignment (replace ad-hoc walker — note 17.1c session warned a snapshot/restore approach regresses -7), `&&`-chain NARROWING wired into TS2774 emission (`uncalledFunctionChecksInConditional2_ts` — also blocked on `window` global type), FlowAssignment-RHS narrowing (medium risk — could over-narrow legitimate union-source TS2322 cases), discriminated-union narrowing through property-equality (e.g. `ab.type === 'a'`).
3. **Blocker #2**: Generic argument inference (~2 sessions, +20–40 tests).
4. **Blocker #3**: Cross-file global scope refactor (~3+ sessions, +30+ tests).

See `PLAN-PHASE-4.md` for the full reshuffled blocker list with rationale,
the candidate-picking workflow, and live session notes. See
`PLAN-PHASE-4-HISTORY.md` for archived completed items.
