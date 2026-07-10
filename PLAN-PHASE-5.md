# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Re-scope (2026-07-03, owner): the Phase 17 target narrowed from "any TypeScript
project" to the TypeScript compiler itself.** Rationale: "any project" is asymptotic,
while the tsc-source profiles are already the dashboard — v1 becomes a measurable
burn-down (compiler 2,726 / services ~7,145 / server ~7,606 / harness ~8,135 FPs,
same ~4 families ≈85% of every profile). Queue consequences: M2.4 (DOM — tsc sources
don't reference it), M3.0 (conformance adoption — optional extra regression net, not
needed for the burn-down), M3.5 (per-file scopes — revisit only if dashboard FPs trace
to cross-file conflation on tsc sources), and all of M4 (nodenext `exports` maps, decl
emitter, JSX, external sourcemaps, project references — none block self-compiling tsc)
moved to the new § "Post-v1 backlog". M3.1–M3.4 stay live but re-scoped from
completeness campaigns to dashboard-driven burn-down: the acceptance bar is the shapes
tsc's source uses, with the corpus suite as the regression net. M5 unchanged —
performance is the directive's second half and starts at v1 compliance.

*(Numbering note: rounds 432–434 below are the `perf/flow-import-resolution` branch's original
rounds 430–432, renumbered at merge — the branch ran in PARALLEL with main's own rounds 429c–431e,
which own those numbers. The perf rounds' FP baselines (1,148 / 1,665) are the branch's pre-merge
numbers; main's concurrent M3.1/M3.2 work independently took the compiler profile to 482.)*

**Round 462 (2026-07-10) — probe-driven burn-down: SIX fixes; FOUR needed instrumented
whole-program probes (minimal repros clean or misleading — XPROBE prints + a stack-trace probe on
the Diagnostic constructor). Dashboard: compiler 79 → 72 (−7; TS2322 19 → 15, TS2345 4 → 2,
TS2339 3 → 2), services 165 → 154 (−11 cross-profile). Suite 9,782 → 9,794 (+12 local across 5
new test files, 0 regressions); 6 fix commits (a4c8b186 / 9810df0f / f6e2456f / e2e99396 /
99181256).**
- **Fix 1 (a4c8b186, call-arg narrow-DOWN gate; compiler 79 → 77):** the round-428b/429c/438
  branch required the narrowed type to refine the DECLARED type (`n <: declared`) — but tsc's
  getNarrowedType(assumeTrue) legitimately narrows to a guard target OUTSIDE the declared
  hierarchy (isPropertyNameLiteral's PropertyNameLiteral union contains JsxNamespacedName, which
  does not extend Expression), so a genuine narrow was rejected → the wide Expression FP'd TS2345
  ×2 (utilities.ts:4066 isSameEntityName). An XPROBE on the real bench project showed
  refines=false / matchesParam=true — the shape does NOT reproduce minimally (the conservative
  union-param gate skips small repros). The gate now also accepts a narrowed type that makes the
  PARAM relation pass (the standard monotone rule).
- **Fix 2 (9810df0f, objlit property-value contextual narrow-DOWN; 77 → 76):** `return { class:
  node.parent.parent, … }` after `isClassLike(node.parent.parent)` kept the wide Node — the
  round-438 nullish-strip gate alone rejects narrow-DOWNs (utilities.ts:7458). Two pieces:
  checkReturnAssignability provides the objlit CONTEXT (a union target contributes its sole
  non-nullish object member — mirrors the call-arg B83.4g), and getTypeOfObjectLiteral's value
  narrowing extends to PropertyAccess initializers when the contextual property accepts the
  narrow and rejects the raw. **PERF LANDMINE (measured + fixed pre-commit): an unconditional
  getNarrowedTypeForReference on every objlit PropertyAccess value program-wide cost +352%
  self-compile time (28.9 s → 130.5 s) — the walk now runs only when the raw type FAILS the
  cached contextual-property relation (31.4 s, normal band).**
- **Fix 3 (f6e2456f, prefix-path guard tail substitution; 76 → 75):** the round-424 prefix-path
  branch (guard on a RECEIVER prefix of the walked path) made only the drop-nullish claim; tsc
  re-types `x.y` from the NARROWED x — `isComputedPropertyName(parent)` makes `parent.parent`
  ComputedPropertyName's declared `parent: Declaration` (utilities.ts:5085). Now substitutes the
  resolved tail, with TWO measured gates: NON-UNION tails only (an ungated cut was net +1 — a
  precise union tail like `BindingName` fires where the wide type stayed silent when downstream
  discriminant narrowing can't complete: checker.ts:45139/binder.ts:2498), and a non-nullish
  union tail KEEPS the round-424 nullish-strip (dropping it regressed transformers/utilities.ts:643).
  Verified strictly-removal by listAll diff.
- **Fix 4 (e2e99396, ambiguous block locals in the PROPERTY-ACCESS pass; 75 → 74):** the round-460
  rule (≥2 block-scoped decls of one name in ONE body → anyType) ran only in the assignability
  pass; the property-access pass has its own three body-entry sites. moduleNameResolver.ts's
  loadModuleFromTargetExportOrImport has `const result = nodeModuleNameResolverWorker(...)`
  (ResolvedModuleWithFailedLookupLocations) in one block and the recursive SearchResult `const
  result` in another — first-decl-wins made `result.value` (2823) FP TS2339 on the wrong block's
  type. Root-caused by TWO probes (a getTypeOfIdentifier-origin print falsified the
  inherited-entry hypothesis; the B136-recording print found the same-body sibling block).
- **Fix 5 (f6e2456f follow-up in the same commit as fix 3's gates):** see fix 3's measured gates.
- **Fix 6 (99181256, constraint-shape foreign-TP gate; 74 → 72, −2):** typeContainsForeignTypeParam
  matched own TPs by NAME, so a CALLEE's un-inferred TP sharing the name was claimed own —
  `getUpToDateStatusWorker<T extends BuilderProgram>` returning forEachKey's UNCONSTRAINED
  `T | undefined` never hit the foreign-TP bail → bare-T TS2322 (tsbuildPublic.ts:1778, pinned by
  a temporary stack-trace probe on the Diagnostic constructor after the return-path gate looked
  correct). A same-named TP with a mismatched constraint SHAPE (own constrained vs instance
  unconstrained, or vice versa) is now foreign; scoped to names present in currentTypeParamDecls
  so signature-own TP names keep the pure name test (round-431e pins). ALSO cleared
  transformer.ts:271 (the memoize objlit member — same collision, previously scouted
  "whole-program-only").
- **Probe finding (checkConditionalReturnBranches instrumentation):** utilities.ts:5085's
  emission does NOT come from the ternary-arm branch (its narrow related fine both times) — the
  ternary-arm TS2322 anchor at 5085:49 was the DIRECT `return parent.parent` shape, mis-read from
  the earlier scout. Lesson repeated: verify the emitting SITE with a probe before theorizing.
- **Scouted with findings:** factory/utilities.ts:713 (`helper.importName`) needs TS 5.5 INFERRED
  TYPE PREDICATES — `filter(helpers, helper => !helper.scoped)` selects the guard overload only
  because tsc infers `helper is UnscopedEmitHelper` from the boolean-literal discriminant arrow;
  a bounded slice would infer predicates for single-expression discriminant arrows in
  filter-family calls.
- **NEXT (compiler @ 72, ~26 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  the big-objlit M3 family remnant (moduleNameResolver.ts:1300 / emitter.ts:1277 /
  checker.ts:6640); TransformerFactory ×3 (transformer.ts:83/98/100); inferred type predicates
  (factory/utilities.ts:713); program.ts:1220 (checkDefined RETURN TYPE resolution);
  es2020.ts:91 Exclude-narrowing at whole-program scale; typeSerializer.ts:603 (switch-narrowed
  generic clone chain); `string → __String` branding (builder.ts:2390); utilities.ts:9042
  mapped-type-unknowable-keys excess bail; checker.ts:29132 evolving-let FlowType;
  checker.ts:21170 TS18048 restrictiveInstantiation self-write; esDecorators ×2 /
  taggedTemplate:50 / namedEvaluation:434 / parser.ts:9581 / factory/utilities.ts:1688
  (transformer-family intersections + cast-instantiation identity, M3).**

**Round 461 (2026-07-10) — bounded FP burn-down: FIVE fixes, all GENERAL checker/flow correctness
that reproduce minimally. Dashboard: compiler 88 → 79 (−9; TS2322 25 → 19, TS2345 6 → 4),
services 178 → 165 (−13 cross-profile, measured after all five). Suite 9,762 → 9,782 (+20 local
across 5 new test files, 0 regressions); 5 fix commits (c863d04b / 72c3abc5 / b20a695e /
f8cbd1e6 / 1f3726f2).**
- **Fix 1 (c863d04b, TypeParam-source constraint bail, M3.1; compiler 88 → 86):** the round-456
  reverted broad rule landed as the prescribed per-site EMISSION bail — `bareTpConstraintRelatesTo`
  (constraint-chain walk, cycle-guarded for `T extends T`, unconstrained → false) suppresses the
  assignment-path TS2322 when a bare-TypeParam source's constraint chain relates to the target
  (mirrors round 442's arg-check bail; no shared-engine change → no overload-selection
  perturbation). Cleared transformers/classFields.ts:1800 (`currentClassContainer = node`, `node: T
  extends ClassLikeDeclaration`) + utilities.ts:12338 (`clone = getSynthesizedDeepCloneWorker(...)`
  returning bare `T extends Node` — the callee's T shares the enclosing fn's TP name, so the
  foreign-TP gate deliberately passes it). tsbuildPublic.ts:1778 (return path) does NOT reproduce
  minimally — stays.
- **Fix 2 (72c3abc5, element-access reference paths, M3.4; 86 → 85):** an element access with a
  LITERAL index is a narrowable reference (tsc isMatchingReference) — `getReferencePath` gains an
  ElementAccessExpression arm serializing `recv[N]` segments, so
  `!!node.declarationList.declarations[0].initializer && getInternalEmitFlags(<same path>)`
  narrows (transformers/es2015.ts:2730). Companions: exact-path ElementAccess target arms in
  flowAssignmentMightNarrow + narrowByAssignmentRhs; `flowPathRoot` (splits on '[' too) for the
  root-name comparisons; `pathPrefixOf` boundary tests in argMentionsReferencePath. Also cleaned
  two pre-existing round-460 warnings the recompile surfaced.
- **Fix 3 (b20a695e, namespace-member nested-fn shadow; 85 → 82, −3):** `shadowNestedFunctionNames`'
  collision gate also consults the enclosing inference-namespace exports (new
  `nameInEnclosingNamespaceExports`, symbol-presence only) — parser.ts:1865's
  `syntaxCursor = { currentNode }` shorthand referenced the body-nested
  `currentNode(position): Node` but resolved to namespace Parser's
  `currentNode(parsingContext, pos?): Node | undefined` via lookupInInferenceNamespace (which runs
  BEFORE globals in getTypeOfIdentifier). Generalized beyond the pinned site (−3).
- **Fix 4 (f8cbd1e6, checkDefined-shape non-nullish, M3.4; 82 → 81):** a call whose resolved
  callee returns a bare OWN-TP `T` with some param annotated `T | undefined` (/`| null`) proves
  non-nullish (tsc Debug.checkDefined — inference binds T to the arg's non-nullish part); wired
  into callRhsHasNonNullishReturnAnnotation, gated to no-explicit-type-args (a control pins
  `checkDefined<X | undefined>` staying nullable). Cleared program.ts:4041 (`sourceFile =
  Debug.checkDefined(commandLine.options.configFile)` at an if/else join; the callee resolves
  through the barrel via the flow-only resolvers).
- **Fix 5 (1f3726f2, var-decl + tuple-slot narrowing consumers, M3.4; 81 → 79):**
  (a) checkVarDeclAssignability gets the assignment/return paths' relation-passes-gated narrowing
  for named-object/union/intersection targets (parser.ts:6245 — `let expression: PropertyAccess |
  Identifier | ThisExpression = initialExpression` after a negative isJsxNamespacedName guard);
  (b) `elementAssignableToSlot` (round-446 array→variadic-tuple AST check) narrows a reference
  element — builder.ts:518's `isString(old) ? [old] : old[0]` vs `EmitSignature = string |
  [signature: string]`.
- **Scouted, whole-program-only (deferred with findings, all minimal repros CLEAN):**
  transformer.ts:271's `getEmitHelperFactory: memoize(...)` member (the foreign-TP gate walks
  anonymous-object members and a faithful single-file memoize repro passes — the real gap is
  whole-program); utilities.ts:4066 isPropertyNameLiteral &&-guard (faithful single-file repro
  passes — the PropertyNameLiteral alias union's whole-program resolution is the suspect);
  parser.ts:9581 displays the SAME union on both sides (`& { expression: SerializedEntityName }` vs
  the inline union it aliases — alias-display/conflation flavor); factory/utilities.ts:1688 casts
  to `AssignmentExpression<EqualsToken>` but the cast resolves `PunctuationToken<any>` (generic
  instantiation identity, M3).
- **NEXT (compiler @ 79, ~33 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):** the
  whole-program probe batch (instrument narrowByCallPredicate on the real bench project for
  utilities.ts:4066/5085, es2020.ts:91, factory/utilities.ts:713); tsbuildPublic.ts:1778 return-path
  T (extend the fix-1 bail to the return emission after probing why the narrowed `T | undefined`
  reaches it); the big-objlit M3 family (transformer.ts:271 / moduleNameResolver.ts:1300 /
  emitter.ts:1277 / checker.ts:6640); program.ts:1220 (needs the checkDefined call's RETURN TYPE
  resolved — the non-nullish flag alone doesn't type `file`, so `file.referencedFiles[index]`'s
  members stay unresolvable for the round-460 destructuring narrowing); typeSerializer.ts:603
  (switch-narrowed generic clone chain, M3.1); `string → __String` branding (builder.ts:2390);
  utilities.ts:9042 TS2353 (mapped type keyed `keyof T & …` with un-inferred T — the excess check
  needs an unknowable-key-domain bail).**

**Round 460 (2026-07-09/10) — bounded FP burn-down: TEN fixes (9 dashboard wins + 1
repro-pinned capability), all GENERAL checker/flow correctness. Dashboard: compiler 103 → 88
(−15; TS2322 33 → 25, TS2345 11 → 6, TS2454 1 → 0, TS2363 1 → 0), services 194 → 178 (−16
cross-profile, measured at fix 8). Suite 9,731 → 9,762 (+31 local across 10 new test files,
0 regressions); 9 fix commits (9973bf9d / 4c00e5f1 / fcec70f7 / c35e24cb / 739d16ef /
f2d67ef9 / 2c03d8f9 / a0765dd6 / f1849a01).**
- **Fix 1 (9973bf9d, block-scoping-ambiguous locals; compiler 103 → 100):** the round-459 NEXT
  item with root cause pre-diagnosed. A name with ≥2 block-scoped (`let`/`const`) declarations in
  ONE function body can only refer to DIFFERENT blocks' bindings, but the flat first-decl-wins
  `currentLocalTypes` made every later read resolve to whichever decl the walk saw first —
  program.ts findSourceFileWorker's three `const file` made `return file;` read the if-block's
  `SourceFile | false | undefined` → FP TS2322. `applyAmbiguousBlockScopedLocals` registers such
  names anyType at body entry (`ambiguousBlockLocalNames`; loop-header decls excluded);
  `checkVarDeclAssignability` skips re-recording them in all three maps. BONUS: also cleared
  checker.ts:11321 (round-458's "Blocker-#3 name→DeclarationName conflation" was actually this)
  and utilitiesPublic.ts:991 — the family was mis-attributed to whole-program conflation.
- **Fix 2 (4c00e5f1, exhaustive-switch never family; 100 → 97):** all three `Debug.assertNever` /
  `assertType<never>` sites had DIFFERENT root causes: (a) programDiagnostics.ts:346 — a NON-union
  switch subject (single interface after `isReferencedFile(reason)` guard) whose `.kind` is an
  enum-member-union ALIAS fully covered by cases → `narrowBySwitchClause`'s default branch now
  exhausts it to never; (b) declarations/diagnostics.ts:702 — `Debug.type<T>(node)` where T is a
  FUNCTION-BODY-local alias (B83.5-unbound) → new `uniqueNestedTypeAliasByName` program-wide map
  (type-alias sibling of buildNestedFunctionMap); (c) utilities.ts:12082 — HasInferredType contains
  `Exclude<VariableLikeDeclaration, JsxAttribute | EnumMember>` (no union distribution → member
  resolved anyType, poisoning the union) → new `resolveAssertTargetTypeNode` resolves member-wise
  and evaluates lib Exclude (keep members NOT assignable to U). All flow-only.
- **Fix 3 (fcec70f7, flatten double-array anchor, M3.1; 97 → 95):** `flatten<T>(array: T[][] |
  readonly (T | readonly T[] | undefined)[])` — a union param with a `tp[][]` member is now an
  inference ANCHOR (gate (k)); an array-of-array arg binds T from its inner element (enum elements
  accepted). Cleared utilities.ts:9972/9978; the explicit-type-arg control proved the relation
  passes once T binds.
- **Fix 4 (c35e24cb, TS2454 captured reads; 95 → 94):** `isAssignedAtFlow` returned false at the
  closure's FlowStart, so an expression-bodied arrow's captured read never saw the enclosing
  function's assignments (checker.ts:14106 `filter(…, info => !findIndexInfo(indexInfos, …))` after
  an if/else assigning indexInfos on both branches). It now follows `FlowStart.outerFlow` (closures
  only, localNames-gated; OR-semantics → suppression-only; never-assigned captured reads still fire).
- **Fix 5 (739d16ef, nested destructured global shadow; 94 → 93):** `registerNestedGlobalShadowDecls`
  now registers binding-PATTERN element names — checker.ts:37376's nested `const { start, length } =
  getDiagnosticSpanForCallNode(…)` shadowing core.ts's `length` fn (`diagnostic.length = length`
  FP'd fn-vs-number).
- **Fix 6 (f2d67ef9, arithmetic-pass module-var-leak arm; 93 → 92):** the round-442 family —
  `arithOperandType` bails a bare-Identifier operand whose name ∈ moduleFileLocalVarNames (own-file
  + currentLocalTypes gates): program.ts's module `const indent = "    "` poisoned parser.ts's
  body-local `let indent` → `margin - indent` FP'd TS2363 (parser.ts:8974).
- **Fix 7 (2c03d8f9, destructuring-assignment overwrite narrowing — dashboard-neutral capability,
  repro-pinned):** `({ pos, end } = refs(i))` now OVERWRITES pattern names in the narrowing walk:
  Flow.kt threads the ENCLOSING BinaryExpression through the literal target arms (was the leaf
  Identifier — no RHS visible), `flowAssignmentTargetsName` walks pattern LHSes via
  `destructuringAssignTargetHasName` (nested default-value patterns REQUIRED — the
  sourceMapValidationDestructuringForOf… corpus pin caught the first flat-only cut), and
  `narrowByAssignmentRhs` strips nullish from the declared type when the destructured member
  resolves nullish-free. Companion: a DIRECT enum-typed switch subject with all members covered
  exhausts to never in the default clause. The real program.ts:1220 stays FP — its `file` local is
  `Debug.checkDefined(program.getSourceFileByPath(…))` (cross-barrel generic inference), so the RHS
  type doesn't resolve; mechanism verified by repro + 5 local tests.
- **Fix 8 (2c03d8f9 same commit, function-local-alias predicate target; 92 → 91):**
  `narrowByCallPredicate` falls back to `resolveAssertTargetTypeNode` — utilities.ts
  resolveNameHelper's nested `isSelfReferenceLocation(node): node is SelfReferenceLocation` (the
  alias is declared INSIDE the function) never narrowed → `lastSelfReferenceLocation = location`
  FP'd TS2322 (utilities.ts:11856).
- **Fix 9 (a0765dd6, chained assignment RHS; 91 → 90):** `location = node = value` evaluates to
  the ULTIMATE RHS value, but the round-410/438 assignment-path narrowing gate only accepted a
  bare Identifier/PropertyAccess RHS — transformers/destructuring.ts:113's chain inside
  `if (isDestructuringAssignment(value))` kept the declared `Expression | undefined` → FP vs
  TextRange. The gate now unwraps the `=` right-spine before narrowing.
- **Fix 10 (f1849a01, this-param signature alignment; 90 → 88):** `getTypeOfFunction` zipped the
  this-DROPPED paramSymbols against the this-KEPT decl.parameters — every param type shifted by
  one for a `this`-param function (core.ts's `multiMapAdd<K, V>(this: MultiMap<K, V>, key: K,
  value: V)` built `(key: MultiMap<K, V>, value: K)` → FP TS2322 ×2 at `map.add = multiMapAdd`).
  A this-param function now resolves each symbol's type from its own valueDeclaration, and `this`
  no longer counts toward minArgumentCount. LOAD-BEARING: functions WITHOUT a this param KEEP the
  legacy positional zip — a leading BINDING-PATTERN param is also dropped from paramSymbols
  (round-446 gotcha), and the shift keeps sig.parameters[i] carrying decl.parameters[i]'s type,
  which the call-site arg alignment relies on (an unconditional valueDeclaration-based resolution
  regressed 19 sites: moduleSpecifiers getModuleSpecifierPreferences et al). Aligning properly
  needs placeholder symbols for pattern params — a queued follow-up with real blast radius.
- **Scouted, whole-program-only (deferred with findings):** tsbuildPublic.ts:594 TS7006
  (`parseConfigFileHost.onUnRecoverableConfigFileDiagnostic = d => …` — the PropertyAccess
  assignment-target context EXISTS and a faithful generic destructured-receiver repro compiles
  clean; the real gap is cross-barrel); moduleSpecifiers.ts:929 (round-459 finding stands).
- **NEXT (compiler @ 88, ~42 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  program.ts:1220 needs `Debug.checkDefined(<call>)` cross-barrel generic-return inference to feed
  the (now-landed) destructuring narrowing; transformers/transformers/es2015.ts:2730 needs element-access segments (`declarations[0].initializer`) in the
  narrowing reference-path machinery (getReferencePath has no `[0]` support — core change, assess
  blast radius); utilities.ts:4066 isPropertyNameLiteral &&-guard (probe); TransformerFactory
  whole-program probe (round-457 finding); `string → __String` branding (builderState/builder);
  the binding-pattern placeholder-symbol signature alignment (unblocks the round-446 family properly); the big objlit-vs-interface returns
  (parser.ts:1865, emitter.ts:1277, transformer.ts:271, moduleNameResolver.ts:1300 — likely one
  M3 family); utilities.ts:12338/tsbuildPublic.ts:1778 = the round-456 REVERTED TypeParam-constraint
  relation rule family (needs the per-site emission bail variant).

**Round 459 (2026-07-09) — bounded FP burn-down: EIGHT fixes, all GENERAL checker correctness
that reproduce minimally. Dashboard: compiler 116 → 103 (−13; TS2322 43 → 33, TS2339 5 → 3,
TS2349 2 → 0), services 210 → 194 (−16 measured at fix 7; TS2322 94 → 81, TS2345 29 → 25,
TS2339 13 → 11). Suite 9,706 → 9,731 (+25 local across 8 new test files, 0 regressions); 8 fix
commits (b68d6487 / e6b6d990 / 435d7220 / bef95f36 / 4139b47a / 6c630b54 / d209b138 / 47cb985f).**
- **Fix 1 (b68d6487, array-literal→tuple ASSIGNMENT; compiler −3):** the round-458 NEXT item.
  New `currentLocalDeclTypeNodes` map records body-local annotation NODES alongside
  `currentLocalTypes` (first-decl-wins; saved/restored in checkFunctionBody) so
  `checkAssignmentExpression` can feed the DECLARED tuple node to the round-446
  `arrayLiteralSatisfiesTupleTarget` helper for an ArrayLiteralExpression RHS (body locals are
  B83.5-unbound and the resolved Type collapses the rest slot). Cleared checker.ts:22621/22927
  (`lastSkippedInfo = [source, target]`, `relatedInfo = [info]`), esnextAnd2015.ts:248. The map is
  general infra — any new body-local AST-shape check should read it.
- **Fix 2 (e6b6d990, enum-member-literal overload selection; compiler −1):** an enum-member param
  (`kind: SyntaxKind.NamedImports`) resolves to anyType, so an enum-member ARG matched EVERY
  overload and the FIRST won — `parseNamedImportsOrExports(SyntaxKind.NamedExports)` (parser.ts:8717)
  returned the NamedImports overload's type → FP TS2322. `resolveCallOverload` now compares the param
  annotation's canonical enum-member key set (round-411 key space) against the arg's key AST-side.
- **Fix 3 (435d7220, array-element flow narrowing + Array.isArray; compiler −2):**
  (a) `getTypeOfArrayLiteral` narrows a bare-Identifier ELEMENT via getNarrowedTypeForReference —
  the array sibling of round 438's objlit-value narrowing, same nullish-strip gate — clearing
  builderState.ts:388 (`if (!sourceFile) return emptyArray; … return [sourceFile]`);
  (b) `applyConditionNarrowing` gains `Array.isArray(x)` (covers BOTH the round-458 AST path and the
  flow FlowCondition path; the embedded lib deliberately has no ArrayConstructor so the predicate
  can't resolve via declarations) — clearing utilities.ts:8757 (chainDiagnosticMessages'
  `details === undefined || Array.isArray(details) ? details : [details]`). The isArray.ts corpus pin
  (a USE-BEFORE-ASSIGNED file-level var keeps its DECLARED type in tsc — the TS2454 rule; its
  baseline expects the ELSE branch un-narrowed) is honored by skipping the narrowing for a bare
  identifier resolving to a file-level var with no initializer — the first gate run caught this
  as the session's only (fixed-before-commit) corpus regression.
- **Fix 4 (bef95f36, objLitValueNullishStrip union-member-subset):** the gate on flow-narrowed
  objlit property values / array elements also accepts a nullish-FREE strict MEMBER-SUBSET narrow of
  a union source — monotone-safe (if the raw union relates, any member subset relates → can only
  suppress). Cleared commandLineParser.ts:2269 (readConfigFile's `isString(x) ? … : { config: {},
  error: x }` — `string | Diagnostic` → `Diagnostic` is not a nullish strip). The round-438
  shadowing hazard stays excluded (a nullish narrowed type never qualifies).
- **Fix 5 (4139b47a, numeric-literal vs enum-domain discriminant):** `narrowByDiscriminantProperty`
  compares a NUMERIC-literal RHS against an enum-typed discriminant's VALUE domain (enumValues via
  canonicalEnumSymbol): `flowType.flags === 0` drops `Type` (TypeFlags has no 0-valued member),
  keeping IncompleteType — tsc's getTypeFromFlowType/isIncomplete (checker.ts:28630 TS2339 +
  the 29132 TS2322 cascade). An enum WITH a matching value does not narrow (negative control).
- **Fix 6 (d209b138, tuple-union element access; TS2339):** `value[1]` on a receiver NARROWED to
  `[FileId] | [FileId, BuilderFileEmit]` (builder.ts:2242 toBuilderFileEmit) — tsc resolves union
  element access per-member with undefined for out-of-bounds members. The element-access
  missing-member path bails for a numeric key in bounds for SOME member of an all-tuples union;
  the bail tests the NARROWED receiver (the DECLARED type still carries the guard-removed FileId).
- **Fix 7 (6c630b54, nullable-array default-init push on PROPERTY targets; TS2349 2 → 0):**
  `(label.antecedent || (label.antecedent = [])).push(antecedent)` (binder.ts:1375; core.ts:2135
  cleared too). Root cause found by tracing: inside the `||` right operand the read of `x.p` is
  flow-narrowed to the falsy `undefined`, so the round-408 empty-`[]` contextual rule never saw the
  array type. Two coupled rules: combineBinaryTypes' Equals arm recomputes the RAW property type for
  a PropertyAccess target with a fresh empty-array RHS (an assignment TARGET types against its
  DECLARED type — M1.9); contextualAssignmentRhsType accepts a UNION target whose sole non-nullish
  member is Array-family. The local wrong-arg control pins that the receiver collapses to the
  PRECISE `string[]` (an `any[]` would accept the bad arg). This closes the round-452
  "inline-property-receiver" deferral.
- **Fix 8 (47cb985f, truthy ASSIGNMENT condition; compiler 104 → 103):** `while (child =
  tryParse(() => …))` — the assignment evaluates to the assigned value, so the body sees `child`
  truthy; tsc's parser JSDoc loops declare `let child: Tag | … | false` and the `false` member
  otherwise survived every discriminant filter (a primitive literal member has no `.kind` — the
  enum-key filter conservatively keeps it) → FP TS2322 at parser.ts:9638. `applyConditionNarrowing`
  gains a `SyntaxKind.Equals` arm (LHS is the walked reference → narrowByTruthiness; the false
  branch falsy-narrows symmetrically), covering both the flow FlowCondition and the round-458 AST
  paths.
- **NEXT (compiler @ 103, ~57 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console env-legit):**
  the TransformerFactory<T> whole-program conflation probe (transformer.ts:83/98/100 — round-457
  finding: NOT a generic-alias gap, needs an instrumented probe); program.ts:3705 — ROOT CAUSE FOUND
  (no probe needed): findSourceFileWorker has TWO block-scoped `const file` decls
  (`filesByName.get(path)` → `SourceFile | false | undefined`, declared FIRST inside an if-block,
  vs the outer `host.getSourceFile(…)`) colliding in the block-UNAWARE first-decl-wins
  `currentLocalTypes`, so `return file;` reads the wrong binding's type — the B83.5/block-scoping
  family, needs block-aware local types or a scoped bail; program.ts:1220 (switch-exhaustive +
  destructuring-assignment definite assignment); the `assertNever` never-param family ×3 (round 441
  deep); utilities.ts:9978/9972 flatten (M3.1 generic inference); `string → __String` branding
  (whole-program); moduleSpecifiers.ts:929 for-of element mis-inference (whole-program-only —
  minimal repro clean).

**Round 458 (2026-07-09) — logical/ternary operand narrowing + try/finally flow fix +
fresh-objlit interface-target retry + a P0 single-file-CLI crash fix. FOUR fixes, all GENERAL
checker/flow/driver correctness that reproduce minimally. Dashboard: compiler 121 → 116 (−5),
services 217 → 210 (−7), server 407 → 402 (−5), harness 620 → 615 (−5), tsc-cli 118, jsTyping 115,
deprecatedCompat 118, typingsInstallerCore 115. Suite 9,690 → 9,706 (+16 local across 4 test files,
0 regressions); 4 fix commits (a8571636 / 4eb423e7 / b2143c9e / eec58c6a).**
- **Fix 4 (eec58c6a, P0 — `xtsc foo.ts` crashed on ANY bare source-file argument, even
  `const x = 1;`):** discovered mid-session via a probe; per the P0 mandate, fixed before wrapping.
  The bare `.ts` was passed straight into TsConfigLoader (`resolveConfigPath` treats any
  non-directory as a tsconfig), parsed as JSON → garbage config → a corrupt lib binderResult whose
  `sourceFile.text` mismatched its statement positions → StringIndexOutOfBounds in
  `checkMultiBaseInStatement` (`source.substring` at a lib position ~22995 indexed into the 295-char
  user file). Two narrower guards were tried first (a statement-span bounds check; a merged-decl
  in-source filter) — NEITHER stopped the crash (the position mismatch is in the binderResult
  itself, not the cross-file merge), pointing to the ROOT fix: `ProjectCompiler.build` now detects a
  non-`.json` existing-FILE argument and builds it as a single-file program with default options
  (like `tsc foo.ts`): the file is the sole root in a synthesized `LoadedTsConfig(files = [it])`,
  its relative imports are still walked into the program, and type checking works normally.
  Directory/tsconfig arguments untouched (compiler profile confirmed unchanged at 116). +3 local
  (SingleFileBuildTest: clean compile, real-error reporting, relative-import walk).
- **Fix 1 (a8571636, logical/ternary operand narrowing):** `combineBinaryTypes` narrows the
  `||`/`&&` RIGHT operand, and the `ConditionalExpression` typing narrows the TRUE/FALSE branches,
  by the governing condition via a new `narrowOperandByCondition` → `applyConditionNarrowing` (pure
  AST, no flow graph). tsc's binder places a FlowCondition on the operand (`A && B` under "A true";
  `A || B` and a ternary FALSE branch under "A false"; a ternary TRUE branch under "A true"); our
  `getTypeOfExpression` was flow-unaware for bare references, so `insertComment === undefined ||
  insertComment` typed `boolean | undefined` (→ FP TS2322 against `boolean`; services.ts commenting
  logic) and `cond ? ref : …` kept the un-narrowed branch. Fires only for a pure
  Identifier/PropertyAccess operand (`getReferencePath`); FP-safe (type unchanged when the condition
  doesn't mention the operand path). Cleared services.ts:2891/2976, rename.ts:192, checker.ts:51198
  (accessor-`kind` discriminant → the object-literal `{firstAccessor, …, setAccessor, getAccessor}`
  now matches `AllAccessorDeclarations`). Compiler net 0: the 51198 win is offset by exposing
  checker.ts:11321, a Blocker-#3 `name`→DeclarationName whole-program conflation the cleaner
  (undefined-stripped) narrowing surfaces (the relation now checks the wrongly-typed reference the
  coarse union masked). CAVEAT: RETURN-position ternaries/logicals go through the SEPARATE
  `checkConditionalReturnBranches` (per-branch), untouched by this.
- **Fix 2 (4eb423e7, try/finally flow):** `bindTryStatement` made the finally block's entry flow the
  join of ONLY the try/catch NORMAL completion, so a try that always `return`s/throws left that
  completion unreachable → every read in the finally washed to `never` → spurious TS2339 on cleanup
  code. tsc's `checkGrammarRegularExpressionLiteral` resets `scanner` in its finally after a `try {…
  return; }`; our `scanner` washed to `never` → FP TS2339 on `scanner.setText`/`setOnError`
  (checker.ts:33288/33289). Fix: a two-label structure — the finally block's entry joins the pre-try
  flow (exceptional early exit) + the normal completion, while the flow AFTER the whole statement
  stays the normal completion (so the finally's exceptional-inclusive entry can't widen away the
  try/catch narrowing for the following statements). compiler 121 → 119 (−2), no services regression.
  CLAUDE.md gotcha added (do NOT collapse the two-label structure).
- **Fix 3 (b2143c9e, fresh-objlit interface-target retry):** the round-448 return-path
  fresh-object-literal literal retry (recover a returned object literal's un-widened literal property
  per PropertyAssignment via `freshObjLitRange` so `propertiesRelatedTo` relates it) was gated to
  `Type.Union` targets only. A fresh object literal returned against an INTERFACE (or anonymous
  object) with a literal(-union) member hit the same widening — `return { kind: "ambient", … }` vs
  `interface ModuleSpecifierResult { kind: "node_modules" | … | "ambient"; … }` widened `kind` to
  `string` → FP TS2322. Broadened the gate to `Type.Interface`/`Type.Object`. Suppression-only (the
  retry returns only when the relation then PASSES within the fresh scope → a wrong literal / missing
  property still fires; negative controls pinned). Cleared moduleSpecifiers.ts:399/507,
  sourcemap.ts:323 (RawSourceMap). compiler 119 → 116, services 214 → 210. NOTE the var-decl and
  assignment fresh-objlit paths already apply `withFreshObjLitSource` for all targets — only the
  return path carried the union-only gate.
- **INVESTIGATED, deferred (array-literal→tuple ASSIGNMENT, checker.ts:22621/22927,
  esnextAnd2015.ts:248):** `lastSkippedInfo = [source, target]` → `[Type, Type]`; `relatedInfo =
  [info]` → `[X, ...X[]]`. The `arrayLiteralSatisfiesTupleTarget` helper (round 446, return path)
  handles these shapes, but the targets are function-body-local `let`s (B83.5-unbound), so the
  assignment walk has NO declaration-node map to feed the helper the tuple type NODE (it knows the
  local only by resolved type, which collapses the tuple rest). Needs local-declaration-node plumbing
  into the assignment walk (a `currentLocalDeclTypeNodes` map populated alongside `currentLocalTypes`)
  — a broader infra change that also unblocks other AST-based checks for function-body locals.
- **NEXT (compiler @ 116):** the array-literal→tuple assignment plumbing above; the
  `TransformerFactory<T>` generic-type-alias-of-fn relation (M3.3, whole-program — minimal repro
  clean); the `assertNever(reason)` exhaustive-switch enum-union residual (programDiagnostics.ts,
  round 441 — the guarded `ReferencedFile` union must resolve with readable enum `.kind` members);
  `string → __String` branding (whole-program).

**Round 457 (2026-07-09) — parser `as`/`satisfies` precedence bug. ONE fix, GENERAL parser
correctness that reproduces minimally. Dashboard: compiler 124 → 121 (−3, TS2322 49 → 46); services
220 → 217 (−3); the fix generalizes to any `<binary> as T` right-operand cast. Suite 9,681 → 9,686
(+5 local, 0 regressions); 1 fix commit (22b37a95).**
- **Root cause:** `parseBinaryExpression` (Parser.kt:5399) called a greedy `parseExpressionSuffix(left)`
  right after the unary operand, gluing `as`/`satisfies` to the bare RIGHT operand of a binary op
  BEFORE the precedence-respecting binary loop. `as`/`satisfies` have precedence 7 — LOWER than
  additive (`+`/`-` = 9) and multiplicative (`*`/`/`/`%` = 10) — so a trailing cast must bind the WHOLE
  binary result. `a + b as T` was mis-parsed as `a + (b as T)` instead of tsc's `(a + b) as T`.
- **Fix:** removed the greedy `parseExpressionSuffix(left)` call; the binary loop (which attaches
  `as`/`satisfies` only when `7 > minPrec`) is now the sole handler; `parseExpressionSuffix` is dead
  and deleted. The parallel `parseBinaryExpressionRest` loop (7833) already handled the LEFT-operand
  `as` correctly with the same `prec <= minPrec` break — this only fixed the right-operand path.
- **Trip site:** binder.ts `getDeclarationName` returns `tokenToString(op) + operand.text as __String`
  and `"arg" + index as __String` — the whole `+` is the cast source (→ `__String`, assignable to the
  declared `__String | undefined`); the wrong parse yielded `string + __String` = `string` → FP
  `string ⊄ __String | undefined` (binder.ts ×2 + utilities.ts ×1). Cleared by the fix.
- **Local pin:** `AsExpressionPrecedenceTest` (5 tests) — the exact binder.ts shape, a
  multiplicative variant, a `satisfies` smoke, and a negative control (`a + (b as T)` = `string`,
  still fires TS2322 — the fix does not blanket-suppress right-operand casts).
- **Second item (b8fd350d, `asserts node is Exclude<T, U>` narrowing — DASHBOARD-NEUTRAL capability
  extension per the round-411 precedent):** `narrowByAssertCall` gained an `Exclude<T, U>` branch
  (tsc's `Debug.assertNotNode`) beside the `NonNullable<T>` special-case. After the call the walked
  union drops members assignable to U (bound from the sibling `test` arg's predicate target via
  `predicateTargetTypeOfGuardExpr`); suppression-only / FP-safe. Verified end-to-end by a FAITHFUL
  barrel + guard + loop + intersection repro (all pass), so the narrowing is correct — but the one
  compiler site (es2020.ts:91 `chain.questionDotToken` after `Debug.assertNotNode(chain,
  isNonNullChain)`) stays FP: instrumented isolation shows the mechanism works, so the real site is
  blocked by a WHOLE-PROGRAM resolution/relation scale issue (the huge `_namespaces/ts.js` barrel
  Debug resolution, or `OptionalChain`/`NonNullChain` conflation), NOT the narrowing. `AssertNotNode
  ExcludeNarrowingTest` (4 tests) pins direct/loop/intersection exclusion + a precise-exclusion
  negative control. Follow-up: instrument `resolveNamespaceMemberFnDecl`/`checkTypeRelatedTo` on the
  real es2020 profile to unblock the dashboard site.
- **NEXT (compiler @ 121):** the remaining compiler FPs are ALL deep M3 / whole-program — the
  bounded surgical pool for the compiler profile is dry (every family scouted this round). Confirmed
  findings for the next agent:
  - `TransformerFactory<T>` (×3 compiler + ×3 services): NOT a generic-alias-instantiation gap — the
    substitution path (Checker.kt:91386 `getTypeFromTypeNode(decl.type)` WITH `currentTypeAliasArgs`)
    DOES process a FunctionType alias body, and a minimal repro (`type Transformer<T> = (n:T)=>T;
    type TransformerFactory<T> = (c:number)=>Transformer<T>; return src`) compiles CLEAN. The real
    FP is a WHOLE-PROGRAM conflation (Blocker #3 family — `TransformerFactory`/`Transformer`/
    `SourceFile`/`Bundle` resolve differently in the full program); needs an instrumented probe on
    the real profile, NOT the M3.3 generic-alias angle.
  - `checker.ts:28630` `flowType.flags === 0 ? flowType.type` on `Type | IncompleteType`: numeric-
    literal-`0` discriminant narrowing where the member discriminants are an enum (`Type.flags:
    TypeFlags`) vs a literal `0` (`IncompleteType.flags`) — deep discriminant narrowing.
  - `es2020.ts:91` `chain.questionDotToken`: needs `Debug.assertNotNode(x, guard)` NEGATIVE-exclude
    assert narrowing (`asserts x is Exclude<T, U>`) — new narrowing machinery for a tsc idiom.
  - `services.ts:2891/2976` `boolean | false` target: `||`-RHS flow narrowing gap (`a === undefined
    || a` should narrow `a` to non-undefined) + union normalization (`boolean | false` → `boolean`).
  - `(x || (x = [])).push()` TS2349 (core.ts/binder.ts): round 452 reverted — receiver typed in the
    call-type pass via a path `contextualAssignmentRhsType` doesn't reach; property/`??=`-IIFE targets.
  - services missing-property TS2740/TS2741 (`Node` → `Expression`/`PropertyAccessExpression`,
    `_expressionBrand`): brand-narrowing against conflated Node/Expression types (whole-program).

**Round 456 (2026-07-09) — Array/ReadonlyArray `find` type-guard overload + NonNull-identifier
undefined-strip + branded-intersection flow-narrowing + IterableIterator heritage + a build-warning
cleanup; ONE broad relation rule investigated & reverted. Dashboard: compiler 132 → 124 (−8, TS2322
56 → 49, TS2353 2 → 1); services 230 → 220 (−10, all generalize cleanly, no new FP). Suite 9,666 →
9,681 (+15 local, 0 regressions); 5 commits (860046fc find / 042e2551 nonnull / 3ad1ae4d
branded-intersection / 6330194e iterable-iterator / 54e644db warning).**
- **Fix 1d (6330194e, IterableIterator heritage; compiler −1, services −1):** the embedded
  `IterableIterator<T>` was an EMPTY interface `{ }`, so an interface `extends IterableIterator<T>`
  inherited none of `next()`/`return?()`/`throw?()` (they live on `Iterator<T>`) — an object literal
  supplying `next()` against such a target FP-fired TS2353 "'next' does not exist in type
  'MappingsDecoder'" (sourcemap.ts `MappingsDecoder extends IterableIterator<Mapping>` decoder literal).
  `IterableIterator<T>` now `extends Iterator<T>` (as in the real lib.es2015.iterable);
  ArrayIterator/MapIterator/SetIterator left empty (not heritage targets in tsc's sources → minimal
  blast radius). Corpus green (lib change — the critical gate).
- **Fix 1c (3ad1ae4d, branded-intersection flow-narrowing; compiler −2, services −2):** the
  assignment-RHS (round 410/438) and return-path (round 413/438) flow-narrowing gates covered
  Interface/Reference/Object/Union targets but excluded `Type.Intersection`, so a reference narrowed
  non-nullish (`if (!x) return` / `if (x === undefined) { x = … }`) assigned/returned against a BRANDED
  intersection (`Path = string & {__pathBrand}`, `IncrementalBuildInfoFileId = number & {__…Brand}`)
  kept its wider `X | undefined` type → FP `X | undefined ⊄ X`. Added `Type.Intersection` to both gates;
  FP-safe by construction (unchanged: the narrowed type is substituted only when it makes the relation
  pass). Cleared resolutionCache.ts:1518 (`fileOrDirectoryPath = updatedPath`) + builder.ts:1394
  (`return fileId`). Corpus green.
- **Fix 1b (042e2551, NonNull-identifier undefined-strip; compiler −4, services −6):**
  `getTypeOfExpression(NonNullExpression)` kept the union for a bare-identifier `x!` — only
  `undefined!`/`null!` → never and a `<call>()!` all-concrete union stripped (round 439). So
  `writer = _writer!` (emitter.ts, `_writer: EmitTextWriter | undefined`) and `currentFlow =
  preSwitchCaseFlow!` (binder.ts, `FlowNode | undefined`) FP-fired `X | undefined ⊄ X`. Extended the
  round-439 strip to `expr.expression is Identifier` with the SAME all-concrete gate
  (`types.none { typeContainsUnresolvedTypeParam }`). Property-access `.x!` (the object-literal-vs-
  interface member-precision gap the round-407 note documents) and TP-carrying operands stay deferred
  — a bare Identifier is a simple reference and cannot expose the `.x!` object-literal gap. Cleared
  binder.ts:1748/1768, checker.ts:5791 (`(Symbol | undefined)[]` → `Symbol[] | undefined`),
  emitter.ts:1417; transformer.ts:271's pre-existing object-literal-vs-TransformationContext FP only
  shifts a member display (not a new FP). Corpus green (core-path change — the critical gate).
- **Fix 1 (860046fc, `find`/`findLast` type-predicate overload; TS2322 −1 compiler, −1 services):**
  the embedded `Array<T>`/`ReadonlyArray<T>` `find`/`findLast` had only the general
  `(predicate: … => unknown): T | undefined` signature, so `arr.find(isFoo)` returned the base element
  type. Added the type-predicate overload `find<S extends T>(predicate: (value: T, …) => value is S,
  thisArg?): S | undefined` (mirrors round 455's filter/every), so `tryInferPredicateOverloadReturn`
  (round 439) refines the result — cleared utilities.ts:8276 `getClassLikeDeclarationOfSymbol`
  (`symbol.declarations?.find(isClassLike)`). **Adding an OVERLOAD to an EXISTING method (not a new
  member name) does NOT shift "and N more" missing-property counts** — corpus green. Generalizes across
  profiles (`.find(isX)`/`.findLast(isX)` are pervasive in tsc's own source).
- **Fix 2 (54e644db, build-warning cleanup):** removed a redundant `as TypeOfExpression` cast
  (round 451 typeof-switch exhaustiveness, Checker.kt:79547) Kotlin flagged "No cast needed"
  (`stmt.expression` is already smart-cast). The build must stay warning-clean.
- **INVESTIGATED & REVERTED — the broad relation-engine `source is Type.TypeParam` → relate-its-
  constraint-chain rule (cycle-guarded for `T extends T`).** MEASURED net-zero A/B on BOTH compiler and
  services: it fixed exactly one assignment FP (utilities.ts:12338 `getSynthesizedDeepCloneWithReplacements
  <T extends Node>` — `T` assigned to a `Node | undefined` local) but INTRODUCED one via OVERLOAD
  SELECTION — a bare un-inferred `T extends string` (from `getPathFromPathComponents<T extends string>`,
  whose `readonly T[]`-from-`PathPathComponents` inference our engine leaves un-substituted) now matched
  `ensureTrailingDirectorySeparator`'s `(path: string)` overload instead of `(path: Path)`, returning
  `string` → FP `string ⊄ string & {__pathBrand}` at moduleNameResolver.ts:2933. The rule is SOUND
  (T <: constraint <: target) but overload arg-matching shares `checkTypeRelatedTo`, so it perturbs
  signature selection wherever inference under-resolves a TypeParam arg — exactly CLAUDE.md's standing
  warning against the broad rule. If retried: apply as a PER-SITE bail at the assignment/return TS2322
  EMISSION only (not the shared engine), or fix the upstream `getPathFromPathComponents` inference first.
- **NEXT (compiler @ 124, ~81 real excl. TS2591×43 offline-node):** TransformerFactory<T>
  generic-type-alias-of-fn relation (M3.3 — the alias `(context) => Transformer<T>` doesn't
  instantiate its fn-type body; whole-program, minimal repro clean); the exhaustive-switch
  `assertNever(reason)` enum-union residual (programDiagnostics.ts's `ReferencedFile` → `never` in the
  default, round 441 deep); the `OptionalChain.questionDotToken` Exclude<>-narrowing (es2020.ts — needs
  `Exclude<>` materialization + `assertNotNode` narrowing); `string → __String` branding (whole-program).

**Round 455 (2026-07-09) — global-shadow anyType + widenType tuple shape + ReadonlyArray filter
guard + generic-identity-fn assignability: FOUR fixes, all GENERAL correctness that reproduce
minimally. Dashboard: compiler 153 → 132 (−21, TS2322 77 → 56); shared checker/lib so they generalize
to every profile. Suite 9,654 → 9,666 (+12 local across 4 new test files, 0 regressions); 4 fix
commits (4e94a4e1 / b90d79f1 / 7d82a0aa / aada9e31).**
- **Fix 1 (4e94a4e1, body-local shadows a global function; TS2322 −7):** `applyBodyLocalShadowing`
  (the return/argument-assignability pass) recorded an un-annotated top-level local shadowing an outer
  binding by REMOVING it from `currentLocalTypes` (round 351), so a value-position use (`return clone`)
  fell through `getTypeOfIdentifier` to the outer binding. When that outer binding is a GLOBAL (an
  exported fn merged into globals — core.ts's `clone<T>(object: T): T`, `identity<T>`) the reference
  resolved to the generic function ITSELF → FP `'<T>(object: T) => T' is not assignable to type
  'Identifier' / 'Node | undefined' / …`. Two coupled changes (both suppression-only): (a) an
  un-annotated `inGlobals && !inLocal` shadow now registers `currentLocalTypes[nm] = anyType` (a
  concrete inferred local still wins, checked first; a file-level VAR shadow keeps round 351's
  remove-and-re-infer); (b) `applyBodyLocalShadowing` descends into nested blocks (if/loop/try/switch,
  `applyNestedGlobalShadow`, anyType-only, GLOBAL collisions only — never a concrete annotation type,
  which would leak the block-local shape function-wide) so `const clone=…;return clone` inside an `if`
  resolves. Cleared nodeFactory.ts cloneIdentifier/clonePrivateIdentifier, checker.ts:15679,
  expressionToTypeNode.ts:535 (round-454's documented unmasked gap), plus the builder.ts `create`/`map`
  and checker.ts `length` object-literal-factory shadows (bonus).
- **Fix 2 (b90d79f1, widenType preserves tuple shape; TS2322 −8):** `widenType`'s Type.Object branch
  rebuilt a tuple WITHOUT copying `tupleElementTypes` and widened its `length` LITERAL (`2 → number`),
  turning `[SF, FR]` into a `{ 0: SF; 1: FR; length: number; [x: number]: SF | FR }` object that no
  longer related to a tuple target (`length: number ⊄ 2`). tsc's own `map(arr, x => [a, b])` idiom whose
  result flows through a generic wrapper (`concatenate(...)`) and is assigned to a `[A, B][]` variable
  FP-fired TS2322 (declarations.ts/builder.ts/destructuring.ts ×8). widenType now widens a tuple's
  ELEMENT types only + rebuilds via `buildTupleFromTypes` (length literal + tupleElementTypes +
  per-element optionality preserved); no element widens → tuple returned untouched. Strictly more
  precise. NOTE the var-decl path already passed (contextual tuple typing); only the ASSIGNMENT path
  (which widens the RHS) hit the lossy rebuild — that's why it reproduced only as an assignment.
- **Fix 3 (7d82a0aa, ReadonlyArray.filter/every type-predicate overload; TS2322 −3):** the embedded
  `ReadonlyArray<T>.filter`/`every` lacked the `filter<S extends T>(…): S[]` overload that `Array<T>`
  already carried, so `roArr.filter(isFoo)` returned the base `T[]` — utilitiesPublic.ts
  `getJSDocTagsWorker(...).filter(isJSDocParameterTag)` (a `readonly JSDocTag[]`) FP-fired
  `'JSDocTag[]' ⊄ 'readonly JSDocParameterTag[]'` (×3). Added the overload (and `every`), so
  `tryInferPredicateOverloadReturn` (round 439) infers S from the guard's predicate target. Lib change →
  full corpus blast-radius gate clean.
- **Fix 4 (generic-identity-fn → concrete-fn assignability; TS2322 −3):** `signatureRelatedTo` already
  pinned source type params from the target's param positions for the source-generic/target-non-generic
  case (17.10d — `identity<T>(x: T): T` vs `(fileName: string) => string` pins T := string), but did NOT
  substitute those pins into the source RETURN type, so the return `T` FP-rejected against the concrete
  target return `string`. tsc's core.ts `createGetCanonicalFileName` (`return useCS ? identity :
  toFileNameLowerCase` → `GetCanonicalFileName`) and sourcemap.ts `identitySourceMapConsumer` FP-fired
  TS2322, plus the construct-sig variant parser.ts:1737 (`new <TKind extends SyntaxKind>(…): Token<TKind>`
  → `new (…): Node`). The return type is now instantiated with the pins (`tpAssignments` by id + a
  `tpAssignByName` fallback — TypeParam interning is per-AST-position (B199), so the return's `T` may
  carry a different id than the param's) before the covariant compare. Correctness preserved: `identity`
  vs `(x: string) => number` still fails (pinned return `string ⊄ number`). Relation-engine change →
  full corpus gate clean.
- **INVESTIGATED, deferred (whole-program-only or deep M3, confirmed via minimal repro):** the
  `isPropertyNameLiteral(name) && …` TS2345 Expression→Identifier narrowing is 0-error in isolation (a
  whole-program alias/merge issue); the generic-identity-fn `<T>(x: T) => T` → concrete-fn assignability
  (core.ts:2378 `identity` → `GetCanonicalFileName`, sourcemap.ts:820) is a genuine M3.1 generic-signature
  relation; the `Type | IncompleteType` `.type` access is a specialized `flags === 0` enum-domain
  narrowing; the `assertNever(reason)` exhaustive-switch residual (programDiagnostics.ts) needs the
  enum-union `ReferencedFile` to narrow to `never` in the default (round 441 family).
- **NEXT (compiler @ 132):** the `TransformerFactory<T>` generic-type-alias-of-fn relation ×3 (M3.3 —
  `(context) => (x) => x` vs the alias `(context) => Transformer<T>`; likely a generic type-alias
  instantiation gap); the exhaustive-switch enum-union `assertNever`/`assertType<never>` residuals
  (programDiagnostics.ts's `ReferencedFile` union → `never` in the default, round 441 family);
  `string → __String` branding (whole-program); the `.map` DIRECT array-literal-in-assignment
  contextual tuple typing (assignment path lacks the var-decl array-literal-vs-tuple handling).

**Round 454 (2026-07-09) — nested-function-shadow generalization + flow-narrowing + method-param
bivariance: FIVE fixes. Dashboard: compiler 170 → 153 (−17); services 275 → 251 (−24, the fixes
generalize). Suite 9,642 → 9,654 (+12 local across 5 new test files, 0 regressions); 5 fix commits
(0df21c9b / a0c0f3d8 / d4e93ead / 3eb6c99b / a505ce0a).**
- **Fix 1 (0df21c9b, nested-fn shadow in arrow/fn-expr bodies; TS2345 ×3):** `shadowNestedFunctionNames`
  (anyType-bails a body-nested `function NAME` colliding with an outer/global binding, B83.5 —
  suppression-only, round 429) ran ONLY for FunctionDeclaration bodies in the call-types walker. tsc's
  program.ts nests `function createDiagnosticForNodeArray(nodes, message)` inside the
  `runWithCancellationToken(() => { … })` ARROW body, shadowing utilities.ts's exported 3-param
  `createDiagnosticForNodeArray(sourceFile, nodes, message)`; a sibling-nested `walkArray`'s call
  FP-checked `nodes` (NodeArray) against `sourceFile` (SourceFile) → TS2345 ×3 (program.ts:3152/
  3182/3194). Added the call to the ArrowFunction (Block body) + FunctionExpression branches.
- **Fix 2 (a0c0f3d8, cast narrowing; TS2345 −1):** an assignment `x = expr as T` produces a value of the
  cast TARGET T. `rhsIsDefinitelyNonNullish` unwrapped the `as`/`<T>` cast to its inner and classified
  THAT, so `Object.create(Array.prototype) as NodeArray<Node>` (debug.ts) inside `if (!nodeArrayProto)`
  kept the falsy-guard `undefined` narrowing → the next `attachNodeArrayDebugInfoWorker(proto)` FP'd
  TS2345. New `castTargetIsNonNullish`: a cast to a concrete non-nullish target re-narrows to
  declared-minus-nullish; a nullable/any/unknown target still falls through to the inner shape.
- **Fix 3 (d4e93ead, TS2722 loop-entry; −1):** the optional-member-invoke "possibly undefined"
  suppression used only the plain narrower, which washes at a loop's FlowLoopLabel. tsc's
  moduleNameResolver.ts guards optional host methods BEFORE a loop — `if (host.directoryExists &&
  host.getDirectories) { for (…) { if (host.directoryExists(root)) … } }` — so `host.directoryExists(root)`
  FP'd inside; `propertyAccessNarrowedNonNull` now retries with the loop-entry-following variant
  (mirrors emitTs18048ForClosureCaptured…). Suppression-only.
- **Fix 4 (3eb6c99b, nested-fn shadow in checkFunctionBody; TS2322 89 → 78, TS2740 1 → 0):** the
  RETURN/argument-assignability pass now applies `shadowNestedFunctionNames` too (`getTypeOfIdentifier`,
  which `getReturnTypeOfCallExpression` consults, resolves a call to a nested function as anyType rather
  than a same-named merged-globals EXPORTED function). tsc's builderState.ts nests
  `create(forward, reverse, deleted): ManyToManyPathMap` shadowing the exported `create(newProgram:
  Program, …): BuilderState`, so `return create(new Map, new Map, undefined)` FP'd TS2740+TS2345. The
  pervasive object-literal-factory pattern `{ clear, create, … }` (shorthand → nested fn) was poisoned the
  same way — the shorthand resolved to a global's wrong signature and broke the object's assignability to
  its target interface (~13 cases across moduleNameResolver/sys/resolutionCache/emitter/nodeFactory).
- **Fix 5 (a505ce0a, object-literal METHOD-param bivariance; TS2322 −1 + fixes an unmasked FP):** a method
  member (method syntax) compares its params BIVARIANTLY vs an interface target (tsc — `strictFunctionTypes`
  never applies to methods), but our object-literal-vs-interface relation (`propertiesRelatedTo`) compared
  function-typed members contravariantly. `propertiesRelatedTo` now retries a failed member comparison via
  `methodSignaturesBivariantlyRelated` (the same helper as TS2416/TS2430) when BOTH members are methods —
  suppression-only, FP-safe by construction (a method-param bivariance match is not an error in tsc), a
  function-typed PROPERTY stays contravariant. Cleared checker.ts:6310 (`const x:
  SyntacticTypeNodeBuilderResolver = { canReuseTypeNodeAnnotation(…, symbol: Symbol, …) {…} }` vs interface
  `symbol: Symbol | undefined`) — one of the two fix-4 unmasked gaps, and a general correctness win. Corpus
  green (relation-engine change — the critical gate).
- **UNMASKED (one remaining, documented, not manufactured):** fix 4's more-correct resolution exposed two
  latent gaps; fix 5 cleared the first (method-param bivariance). The remaining one: expressionToTypeNode.ts:535
  — an un-annotated block-scoped `const clone = resolver.markNodeReuse(…)` shadowing the global
  `clone<T>(object: T): T` resolves to the GLOBAL in the return check, because `applyBodyLocalShadowing`
  does NOT descend into if-blocks and un-annotated shadowing locals are removed from currentLocalTypes (→
  fall to globals). Needs a broad, careful applyBodyLocalShadowing fix; corpus green.
- **NEXT (compiler @ 153):** the expressionToTypeNode.ts:535 unmasked gap (applyBodyLocalShadowing if-block
  descent); TS2322×77 deep-M3 relation (generic-fn-identity `<T>(object: T) => T`, `.map`-returns-tuple-array
  M3.2, whole-program conflation); TS2353 sourcemap getter/method excess + utilities.ts mapped-type-key
  eval (M3.3); TS2349 `(x || (x = [])).push()` inline-property-receiver; factory/utilities.ts:713 tsc-5.5
  inferred type predicates (`helper => !helper.scoped` → `helper is UnscopedEmitHelper`); moduleNameResolver.ts:2823
  `.value` union-return; checker.ts:21170 restrictiveInstantiation overload-selection TS18048; the three
  `assertType<never>(node)` big-AST-union exhaustive-switch residuals (round 441 deep).

**Round 453 (2026-07-09) — bounded FP burn-down: THREE fixes, all GENERAL checker correctness
that reproduce minimally. Dashboard: compiler 176 → 170 (−6), tsc-cli 179 → 172, jsTyping 176 →
169, deprecatedCompat 179 → 172, typingsInstallerCore 176 → 169, services 280 → 275 (−5), server
485 → 479 (−6), harness 701 → 695 (−6). Suite 9,628 → 9,642 (+14 local across 3 new test files,
0 regressions); 3 fix commits (9f53366d / 1246458a / df18ce2f).**
- **Fix 1 (9f53366d, arithmetic reassignment/guard narrowing; M3.4):** a `number | undefined`
  reference proven non-nullish by a CROSS-STATEMENT reassignment (`length = end - start; length - 5`
  — tsc parser.ts `parseJSDocCommentWorker`) or an enclosing guard (`if (m !== undefined) indent += m`)
  was still rejected as an arithmetic operand (TS2362/TS2363/TS2365) — the arithmetic pass had no
  flow narrowing (only the `x!` NonNull + `arithTruthyNarrowedNames` `&&`/ternary strips). Two coupled
  changes: (a) `arithOperandType` runs a SCOPED flow-narrowing walk (`arithFlowNarrowedNonNullish`) for
  a bare Identifier / PropertyAccess operand — `currentFlowGraph` set ONLY around the walk (setting it
  pass-wide makes getTypeOfExpression's union-receiver path flow-aware and regressed 78 tests, per the
  gotcha), using the flow-narrowed type only when it PROVES non-nullish; (b) `rhsIsDefinitelyNonNullish`
  classifies arithmetic / bitwise / shift / relational / equality / `+` BinaryExpressions AND
  enum-member value accesses (`Flags.None`, incl. imported enums via resolveAlias) as non-nullish, so
  the reassignment idioms narrow the assigned reference. FP-safe (narrowing only removes union
  members). Cleared parser.ts:8911. **Barrel-imported enums** (checker.ts:6639's `NodeBuilderFlags`
  from `_namespaces/ts.js`) stay — resolveAlias can't follow the ESM `.js` barrel (known-hard).
- **Fix 2 (1246458a, optional-target `= undefined`; TS2322):** `<ident> = undefined` where the target's
  DECLARED type includes undefined — an OPTIONAL parameter `x?: T` (effective `T | undefined`, B85.1a)
  or a `T | undefined` local — FP-fired TS2322. The identifier-target assignment check resolves the
  target from the string `varTypes` map, which drops the `?` (resolveSimpleTypeName → "T"); the
  type-engine `currentLocalTypes` (and its pre-narrowing `narrowedDeclaredTypes`) carries the correct
  `T | undefined`. Bail when the RHS is literally `undefined`, `!exactOptionalPropertyTypes`, and the
  declared engine type includes undefined — the identifier-target analog of round 448's
  `this.optionalProp = undefined`. Cleared generators.ts (`leadingElement = undefined`),
  checker.ts:19908 (`aliasSymbol = undefined`), nodeFactory.ts:1331. FP-safe (a non-optional `x: T`
  target keeps firing).
- **Fix 3 (df18ce2f, un-annotated param shadows a leaked module var; TS2345):** program.ts's
  `const indent = "    "` (module const, string) is SHADOWED by `flattenDiagnosticMessageText(diag,
  newLine, indent = 0)`'s param, but `populateParameterLocalTypes` registered annotated (case 1) and
  function-default (case 2) params only, leaving an un-annotated non-function-default param
  unregistered — so the recursive arg `indent` resolved to the leaked const's `string` → FP TS2345
  (program.ts:832). Now an un-annotated Identifier param is added to `currentParamBindingNames`
  (getTypeOfIdentifier → anyType) AND, when a same-named entry is inherited in `currentLocalTypes`
  (checked BEFORE the set), overrides it with anyType (mirrors the binding-pattern branch).
  Un-annotated-only — an ANNOTATED shadowing param is still type-checked (negative control).
- **INVESTIGATED & REVERTED (dashboard no-op):** the varTypes-side (return-path) shadow override for
  fix 3 — the assignment/return walk's string `varTypes` resolves a shadowed-param return via a path
  the `innerTypes` override didn't reach, and the return-path shadowed-param TS2322 is not a confirmed
  dashboard FP (only my synthetic repro). Reverted to keep the change to the proven type-engine side.
- **NEXT (compiler @ 170, TS2591×43 env-legit offline node stub → real ≈127):** TS2322×93 deep-M3
  relation (mostly whole-program contextual inference — `__String` branding, generic-fn identity,
  SourceFileLike conflation); the TS2353 getter/method object-literal excess (sourcemap.ts
  MappingsDecoder) + mapped-type-key eval (utilities.ts createComputedCompilerOptions, M3.3); TS2349
  `(x || (x = [])).push()` inline-property-receiver (binder.ts:1375 / core.ts:2135 — the round-452
  deferred family); the evolving-let `indexInfos` CFA cluster (TS2454 + TS7034 + TS7005).

**Round 452 (2026-07-09) — bounded FP burn-down: TWO fixes, both GENERAL parser/checker
correctness that reproduce minimally (unlike the recent whole-program Blocker #3 families).
Dashboard: compiler 177 → 176 (−1), services 282 → 280 (−2), server 488 → 485 (−3), harness
704 → 701 (−3). Suite 9,619 → 9,628 (+9 local across 2 new test files, 0 regressions); 2 fix
commits (4c47c918 string-index / 0db79740 tuple).**
- **Fix 1 (4c47c918, string-index element access):** a numeric-literal element access on a
  STRING-typed receiver — `(arr[i])[0]` where the inner element access is typed `string`
  (tsc's own jsTyping.ts `pathComponents[pathComponents.length - 3][0] === "@"`) — FP-fired
  "Property '0' does not exist on type 'string'." The B292 bail in the element-access
  missing-member path (checkMemberAccessMissing's caller ~118808) suppressed only NON-numeric
  string keys (`s["s"]`); a numeric-literal key `[0]` (and a numeric-looking string key
  `["0"]`) fell through to the missing-member check. A plain identifier receiver `str[0]`
  resolved elsewhere, but an element-access-typed receiver reached this path. Element access on
  a string primitive is never TS2339 in tsc (String has a numeric index sig; the TS2339
  property-existence path is property-access only), so broadening the bail to any literal key
  is FP-safe. Cleared jsTyping.ts:258 (services/server/harness).
- **Fix 2 (0db79740, optional-tuple assignability):** `return emptyArray as []` against an
  all-optional tuple `readonly [kind?: T, specifiers?: T, …]` (tsc's own moduleSpecifiers.ts)
  FP-fired TS2739 "Type '[]' is missing … 0, 1, 2". The parser DISCARDS a tuple element's `?`
  token AND label from the element node (no NamedTupleMember/OptionalType is produced — `[T?]`'s
  `?` is eaten by parseType's trailing-`?` JSDoc-recovery), so the resolved tuple Type marked
  every numbered member REQUIRED and its `length` a fixed literal. Fix spans parser + checker:
  (a) the parser records per-element optionality on the new `TupleType.elementOptional` metadata
  field — a named `?` (`[a?: T]`) in the labeled branch, an unnamed `[T?]` bridged via the
  `tupleElementConsumedOptionalMarker` flag set where the recovery consumes it (read+reset per
  element so a nested tuple's marker does not leak); (b) `getTupleType` marks optional members
  in the new `optionalTupleMemberIds` side-channel (consulted by `isOptionalProperty`, since
  tuple member symbols carry no declaration) and sets an optional-containing tuple's `length`
  to the union `minLength..maxLength` (so `[]`'s length 0 relates to `[a?, b?]`'s `0 | 1 | 2`).
  FP-safe: a required-leading `[a, b?]` and an all-required `[a, b]` still error; no element
  node kind changes, so no type-node walker is affected. Cleared moduleSpecifiers.ts:344 (all
  profiles — src/compiler is shared).
- **INVESTIGATED & REVERTED (dashboard no-op):** the `(x || (x = [])).push(v)` empty-array /
  UNION-target contextual typing (round-408 gap for property/union targets). Extending
  `contextualAssignmentRhsType` to pick an Array-family member from a union target fixed the
  IDENTIFIER inline case `(x || (x = [])).push(v)`, but the real tsc-source FPs are PROPERTY
  targets (binder.ts:1375 `(label.antecedent || (label.antecedent = [])).push(antecedent)`) and
  the `??=` IIFE-call-return case (core.ts:2135) — both resolve the `.push`/call receiver in the
  call-type pass via a path the fix doesn't reach (the split `const r = …; r.push()` form IS
  fixed, confirming the `||` result typing works, but the inline call FP persists). 0 dashboard
  FPs cleared → reverted to avoid churn. The inline-property-receiver call-type resolution is
  the follow-up if this family resurfaces.
- **NEXT (services @ 280, all deep/whole-program or M3.4):** deep-M3 TS2322×154 fragments
  (`Type 'X' is not assignable to type 'Y'`, max bucket 5 — Node[]/generic-fn-identity/
  SourceFileLike-conflation); the whole-program cross-file `declare module` augmentation
  method-guard family (`isUnion()`/`isStringLiteral()`, Blocker #3); the `length = end - start;
  length - 5` cross-statement reassignment arithmetic-narrowing residual (parser.ts:8911 /
  core.ts:6639, M3.4 — the arithmetic pass has no reassignment narrowing); the evolving-let
  `indexInfos` CFA cluster (TS2454 + TS7034 + TS7005, symbolDisplay.ts).



### Post-v1 backlog — the "any TypeScript project" horizon (parked 2026-07-03)

The top-to-bottom loop SKIPS this section until v1 (the 8 tsc-source profiles at zero
FPs) lands. None of these block self-compiling tsc. Each returns to the live queue
when v1 lands — or earlier if a live item genuinely needs one (promote per protocol,
with a session note saying why). Item IDs are stable; session notes reference them.

- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design). tsc's sources don't
  reference DOM — post-v1.
- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without
  notes. Owner approval (2026-07-02) stands; optionally pull in early as an extra
  regression net if an M3 campaign wants the coverage.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
