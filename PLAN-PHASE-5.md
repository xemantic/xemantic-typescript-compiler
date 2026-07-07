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

**Round 431 (2026-07-07) — M3.2 (STARTED) + M3.1: the TS7006 core falls 301 → 11
(−96%) via contextual typing, and engine return-checking reaches switch/try bodies
behind a foreign-TP source gate. Self-compile (compiler profile) 936 → 672 → 641 →
574 → 551 (−385, −41%; TS7006 301 → 11, TS7019 4 → 0, TS2322 435 → 345, TS2367 kept
0); by-code strictly shrinking at every landed step; suite 9,356 → 9,380 (+24 local,
0 regressions); 4 fix commits (b2411656, 186cb3cd, cceeb26b, f12dfe61).**
- **Fix 1 (b2411656, −264 strictly removals): TS7006 contextual typing — the two
  dominant mechanisms.** (a) Callee RESOLVABILITY: `isCalleeResolvable` falls back to
  the round-418 nested-function name map (`filterType`/`mapType` inside
  `createTypeChecker` are B83.5-unbound ×~140 sites) and a NEW lexical scope stack
  (`implicitAnyScopes` — params incl. binding-pattern names + body locals, push/pop
  in try/finally at every function-like boundary), so param-typed and nested callees
  contextually type their callback args — the same permissive rule file-level
  callees already had. (b) Assignment-RHS contextual typing (tsc
  getContextualTypeForBinaryOperand): `lhs = arrow` resolves the LHS DECLARED type
  (scope-map annotations, `as T` casts, property-access members via the receiver)
  under the single-applicable-signature rule (mirrors B476 — a ≥2-sig LHS gives NO
  ctx, contextualTypingWithGenericAndNonGenericSignature's pinned FIRE; an untyped
  `let mark; mark = tag => …` keeps firing, uncalledFunctionChecksInConditional2's
  pin). Binary propagation: `||`/`??` feed BOTH operands, `&&`/comma the RIGHT only
  (contextuallyTypeLogicalAnd03/CommaOperator03 pin the left firing).
  `contextualCallableArity` sees through single-callable-member unions
  (`WriteFileCallback | undefined` returns) + lazy References. 13 local tests.
- **Fix 2 (186cb3cd, −31 strictly removals): residual receiver shapes.**
  `lookupPropertyTypeForCtx` resolves members through Type.Intersection receivers
  (`x as CompilerHost & ResolutionCacheHost`, watchPublic ×9), lazy-membered
  References (target fallback — arity survives missing substitution), and interface
  `extends` bases (depth-guarded); an un-annotated call-initialized local registers
  its callee's declared RETURN annotation (AST-only, lazily resolved).
- **Fix 3 (cceeb26b, −108/+41): engine return-checking in switch/try + the
  foreign-TP gate + TS2367 anchoring.** `returnTypeNode` now threads through the
  SwitchStatement/TryStatement arms of BOTH assignability dispatchers (+ the
  Stmt-dispatcher IfStatement arm) — a `return undefined` in a switch case
  previously fell to the STRING path which can't resolve alias unions
  (`VisitResult<Node | undefined>` ×12 FP'd). COUPLED (load-bearing pair):
  `checkReturnAssignability` bails on a source containing a FOREIGN type param
  (name ∉ enclosing `typeParams` — an un-inferred generic call result like `return
  append(…)` typing as `T[]`; own-TP sources keep checking, corpus-pinned) — this
  cleared ~95 PRE-EXISTING top-level un-inferred-generic return FPs. The +41 are
  position-exposures of pre-existing M3 families at newly-checked positions
  (round-426 "honestly visible" precedent): NodeArray<X>-vs-NodeArray<Node>
  covariance ×~17 (cross-file heritage relation gap), `Node` narrowing-dependent
  returns ×5, branded `__String` ×2, TransformerFactory ×3. The TS2367
  same-target-Reference disjointness proof now requires a differing arg pair
  anchored in a NON-object type (a first-touch-exposed `nodes ===
  (parent as X).typeArguments` FP; `Array<string>` vs `Array<number>` stays firing).
- **Fix 4 (f12dfe61, −23 strictly removals): the gate walks ANONYMOUS-object
  members/call-sigs** — `SearchResult<T> = { value: T | undefined } | undefined`
  hides the un-inferred TP in a member (`return toSearchResult(undefined)` ×12 +
  `() => T` factory returns ×4); named interfaces stay excluded (Reference args
  carry their TPs; a member walk would be broad + first-touch-shifting).
- **META:** (1) the round-431 TSV row used `--no-emit` (emitted column 0 — not an
  emit regression). (2) The round-428 negative-control lesson RECURRED: the first
  own-TP control asserted a capability the baseline never had (bare `return x`
  own-TP-vs-number is a pre-existing FN) — verify a control fires at BASELINE before
  pinning it; replaced with the B69.1-ordered ternary shape.
- **Residual triage (next-agent):** TS7006×11 — namespace-local interface
  annotations ×5 (builderState `const map: ManyToManyPathMap = {…}` inside
  `namespace BuilderState` — the walker's getTypeFromTypeNode has no namespace
  context), initializer-inferred fn locals ×3 (parenthesizerRules
  `let rule = cache.get(k); rule = node => …`, checker addLazyDiagnostic),
  destructured-member local ×1, object-member ctx ×2 (watchUtilities). TS2322×345 —
  `string`→`string` ×24 (interface-override literal props, M3), assignment-path
  foreign-TP siblings (`T[]`→`TypeParameter[]` ×2, `U | undefined`→`Modifier` ×2 —
  extend the gate to checkVarDeclAssignability/checkAssignmentExpression, same
  principle), `undefined`→ResolutionMode/ElaborationIterator ×8 (non-return
  positions), NodeArray-covariance adds ×~17 (fix `TypeNode <: Node` cross-file
  heritage or catalogue), `Node`→`Declaration | undefined` ×5 (narrowing-dependent,
  M3.4). TS2345×86/TS2769×30 (nested-overload `'true'`/`'false'` ×5,
  string-vs-literal-union ×10). TS2591×43 is env-legit (offline, no @types/node —
  `--node-stub` suppresses).

**Round 430 (2026-07-07) — M3.1: the `append`/`addRange` inference unlocks +
TP-from-predicate binding. Self-compile (compiler profile) 1,000 → 956 → 936 (−64;
TS2322 496 → 435, TS2769 32 → 30); suite 9,348 → 9,356 (+8 local, 0 regressions);
2 fix commits (6a056b95, 83aeceb1).**
- **Fix 1 (6a056b95, −44 with +6 catalogued): the `T extends {}` constraint killed the
  whole `append` inference + readonly-array anchors.** Round 428's nullable-union
  inference worked for UNCONSTRAINED test sigs, but tsc declares `append<T extends
  {}>` — the candidate constraint check `checkTypeRelatedTo(string, {})` FAILED (an
  anonymous empty object target had no primitive-source rule; the apparent-type
  recovery is Type.Interface-gated), so the mapper was null and every `x = append(x,
  item)` kept the un-instantiated `T[]` return. New relation rule: an EMPTY anonymous
  object target accepts any non-nullish non-void source. TWO landmines pinned:
  (a) a `Type.Union` source's own flags carry no nullish bits (documented gotcha) —
  members checked explicitly so `string | null` still fails; (b) a TYPE-PARAM source
  is EXCLUDED — genericPrototypeProperty3 pins tsc's `Type 'T' is not assignable to
  type '{}'` + "might need an `extends {}` constraint" for unconstrained T under
  strict (the ungated first cut suppressed it; the SUITE GATE caught it — the
  corpus-as-regression-net working exactly as designed). Companion:
  `readonly T[]` params/args anchor array-of-tp inference (`Reference(ReadonlyArray,
  [T])` from getTypeFromTypeOperator; both `isArrayOfTypeParam` and the arg-side
  element extraction matched only "Array") — `addRange(to: T[] | undefined, from:
  readonly T[] | undefined)` never inferred. The +6 are precision-exposures of
  documented M3 residuals where anyType used to hide them (brand-string map keys via
  callback-return widening, optional-target ternary props, visitor generics,
  un-inferred `.map` U[], tuple-vs-array B526 ×2) — by-code still strictly shrank.
- **Fix 2 (83aeceb1, −20 strictly removals): TP-from-PREDICATE binding.**
  `getFirstJSDocTag<T extends JSDocTag>(node, predicate: (tag: JSDocTag) => tag is
  T)` called with a NAMED guard (`isJSDocAugmentsTag`) binds T from the guard's own
  predicate target — the `T | undefined` TS2322 bucket (utilitiesPublic's ~20
  getJSDoc*Tag wrappers, 41 → 21). The resolved signature ERASES the predicate
  (TypePredicate resolves to booleanType), so the param gate reads the AST
  (`predicatePositionTpOf`) and the candidate branch reuses round-424's barrel-aware
  `predicateTargetTypeOfGuardExpr`, soft-skipping unresolvable/inline guards. The
  candidate branch runs BEFORE the standard rawArgType path (which would type the
  guard as a callable object and hard-bail at the named-like gate). Single-sig path
  only (the multi-sig named-guard gate is untouched — B136's swap keeps firing).
- **Residual triage (next-agent):** TS2322×435 — `string` ×38 (incl. the ×24
  interface-override literal props, M3), `T` ×29 (dominated by CONTEXTUAL-RETURN
  inference: `parseTokenNode<T extends Node>()` has NO args — T comes from the
  return context, M3.2), `undefined` ×26 (VisitResult family), `T | undefined` ×21
  residue (non-Array single-arg generic anchors: `firstOrUndefinedIterator(it:
  Iterable<T>)` — extend the anchor set to same-target single-arg References),
  `U[]`/`U | undefined` ×31 (`.map`-family callback-return inference, M3.2),
  visitNodes TOut/TIn ×11 (visitor generics). TS7006×301 (M3.2). TS2345×86:
  `'true'` vs `'false'` nested-overload selection ×5, string-vs-literal-union ×10,
  `Node` vs never ×3 (M3.4 exhaustiveness), NodeArray vs SourceFile ×3.

**Round 429 (2026-07-07) — M3.1 histogram burn-down: the TS2345 core falls 261 → 86
(−67%). Self-compile (compiler profile) 1,186 → 1,156 → 1,135 → 1,027 → 1,000 (−186,
−15.7%; TS2345 261 → 86, TS2769 36 → 32, TS2322 501 → 496, TS2367 −2); every step's
by-site diff STRICTLY removals (the one +3 excursion was caught by the diff and gated
before commit); suite 9,315 → 9,348 (+33 local, 0 regressions); 4 fix commits
(577b2c54, 5fbb8caf, bc893882, d1e53cbd).**
- **Fix 1 (577b2c54, −30): call-types pass lexical shadowing — three scope shapes
  resolved a bare-identifier ARG to the WRONG outer declaration.** (1) A NESTED
  function's body-local (`let host = node.parent`) shadowing an ENCLOSING fn's param
  (`createTypeChecker(host: TypeCheckerHost)`): the inherited `currentLocalTypes` entry
  survived because round 428d's branch is gated entry==null —
  `applyCallTypesBodyLocalShadowing` pre-scans the body (statement-level, not
  descending into nested fn-likes) and anyType-overrides colliding local-decl names;
  same-fn param redeclaration excluded (param wins, pinned). (2) The round-428
  "PARAM-shadow mini-repro does not reproduce" mystery RESOLVED: the real shape is a
  DESTRUCTURED param (`{ useCaseSensitiveFileNames }` in sys.ts vs moduleNameResolver's
  same-named function) — binding names live only in the `currentParamBindingNames` side
  set, and `getTypeOfIdentifier` fell through to the merged globals; it now returns
  anyType for side-set names (after `currentLocalTypes`). (3) Arrow/fn-expr params
  (the walker deliberately doesn't type them) leaked the enclosing binding — those
  branches now scope the maps and register anyType for own param names. 8 local tests
  (CallTypesScopeShadowingTest).
- **Fix 2 (5fbb8caf, −21): embedded String.replace/replaceAll/search/split accept
  RegExp** (`searchValue: string | RegExp`, replaceValue `any` per the
  callbacks-are-any doctrine) — tsc regex-replaces pervasively. Corpus byte-identical
  (no "and N more" shifts). Accepted documented FN: a union-with-interface param is
  not simple-checkable, so wrong-typed args to these four params no longer error
  (control pins indexOf still fires).
- **Fix 3 (bc893882, −129, the big one): three arg-typing rules on the call-arg
  path.** (a) A `string | undefined` union arg is legal for an OPTIONAL param
  (`configFileName?: string` — tsc getTypeAtPosition unions undefined under strict);
  only undefined members stripped (null stays), relation on the stripped type,
  suppression-only. (b) A non-null-asserted arg (`readFile(p)!`) types as its
  nullish-stripped union — LOCAL strip (`stripNullishForNonNullArg`), mirroring the
  round-415 arithmetic rule; the round-407 global-strip revert stands. (c) THE
  DOMINANT mechanism (~110 sites): an Identifier arg whose NON-union interface type
  is guard-narrowed DOWN (`isSourceFile(x) && isExternalOrCommonJsModule(x)` — Node
  → SourceFile) substitutes the refined type, relation-gated — generalizes round
  428b's `this`-only branch. LANDMINE caught by the by-site diff: `never`-typed
  params must be EXCLUDED — `assertType<never>(node)` in an exhaustive-switch default
  needs exhaustiveness narrowing we don't model, and a partial case-union refinement
  TAKES THE UNION-ARG EMISSION PATH (interface args stay conservatively silent vs
  never; unions emit) → +3 FPs until gated. No stable local pin exists for the gate
  (tsc itself errors on the in-file non-exhaustive shape; the exhaustive
  discriminated-union shape needs M3.4 exhaustiveness) — pinned by the by-site diff.
  10 local tests (OptionalParamUnionArgTest).
- **Fix 4 (d1e53cbd, −27): typeof-unknown + string-enum + rest-arg narrowing.**
  (a) `typeof x === "<primitive>"` narrows a non-union UNKNOWN to the primitive —
  `narrowByTypeOfGuard`'s non-union flags path returned NEVER for a positive match
  on unknown (no primitive flags), which the relation-gated consumers rejected
  (moduleNameResolver `target: unknown` ×10). (b) An all-string-valued enum is
  assignable to `string` (`isStringEnumObjectType` in `isSimpleTypeRelatedTo`, the
  round-428b numeric sibling; unevaluated values NOT provable, conservative) —
  resolves the round-410 DEFERRED `Extension[][]` cluster: cascades to `Extension[]`
  → `string[]` via same-target covariant element comparison + clears the paired
  TS2367 no-overlap FPs (×8). (c) The rest-args helper mirrors B469 flow narrowing
  (`cond ? diag(…, deprecatedEntity) : …` ×5). 10 local tests
  (UnknownTypeofAndStringEnumArgTest).
- **META:** two process notes. (1) A mid-bench Checker edit poisoned one bench row
  (the 429b TSV row's build raced my 429c edits) — recovered via git-stash patch-split
  and per-commit listalls; batch edits BEFORE launching a suite/bench. (2) The
  round-428 residual note said "probe the pass's nesting entry with a marker before
  theorizing" — the actual fix needed no marker: re-reading the real site showed the
  param was DESTRUCTURED, which the mini-repro had simplified away. Repro fidelity
  beats instrumentation.
- **Residual triage (next-agent):** TS2345×86 — `'true'` vs `'false'` ×5 (parser.ts
  createMissingNode nested OVERLOADS with literal-typed params; the top-level
  mini-repro does NOT reproduce — the nested/closure context matters, probe needed);
  `string` vs literal-union ×10 (`"typings"|"types"|…`, pragma names, comparators —
  likely needs literal-preserving locals or narrowing); `Node` vs `never` ×3 +
  in-file exhaustive discriminated-union `assertType<never>` (needs M3.4
  exhaustiveness narrowing — catalogued, our A|B switch repro still fires);
  `NodeArray<Node>` vs SourceFile ×3, `System` vs IncrementalCompilationOptions ×2,
  `K` vs string ×2 (keyof-TP). TS2322×496 — `string` vs `string` ×24
  (interface-override literal props, M3), `T[]` residuals (~40: rest-param sigs,
  readonly-array params — `addRange(to: T[] | undefined, from: readonly T[] |
  undefined)`'s TypeOperator param defeats the union-mode detection), SearchResult<T>
  ×10, `undefined` vs VisitResult ×12. TS7006×301 (M3.2) untouched.**

**Round 428 (2026-07-06) — M3.1 (first real slice of the TS2322/TS2345 cores): generic
call-site inference for tsc's `append` idiom + the TS2345 histogram top + the array-literal
string-layer union rule + the body-local-shadows-function conflation. Self-compile
(compiler profile) 1,577 → 1,385 → 1,266 → 1,213 → 1,186 (−391, −25%; TS2322 751 → 501,
TS2345 394 → 261, TS2769 45 → 36, TS2339 6 → 7); suite 9,291 → 9,315 (+24 local, 0
regressions); 4 fix commits (67efa224, 14e9d566, 1791e87a, fbda155d).**
- **Fix 1 (67efa224, −192): nullable-union generic params + overloaded generic callees.**
  The single biggest TS2322 shape (`Type 'T[]' is not assignable to type 'Statement[]'`
  ×130+ + siblings) is tsc core.ts's `x = append(x, item)` — every `append` overload is
  GENERIC (single TP each) with `T[] | undefined` / `T | undefined` union params. Four
  coupled mechanisms: (a) `tryInferSingleTypeParamFromArgs` accepts a nullable-union-of-tp
  param (`nullableUnionOfTpMode`) and strips nullish members from a UNION arg (purely
  nullish arg → soft-skip, T still anchors from the other arg); (b) an `anyType` arg (an
  unmodeled local — for-of loop var) contributes NO candidate at the RETURN-TYPE site
  instead of killing the inference (`forReturnType`-gated; the arg-vs-param site keeps the
  hard bail — its consumers EMIT); (c) `getReturnTypeOfCallExpression`'s multi-sig path
  runs single-TP inference for overloaded all-generic callees (chosen sig first, then
  arity-matching sigs; first full mapper wins) — gated on NO named-type-guard Identifier
  arg (`argIsNamedTypeGuardIdentifier`: `filter(arr, isFoo)` selects tsc's guard overload
  whose S binds from the PREDICATE, which we don't model — and the gate is deliberately
  NOT folded into `callHasTypeGuardArg`, whose B136 concrete-overload swap must keep
  firing for named guards); (d) the string-layer `isAssignableTo` treats an array-literal
  source vs `T[]` (T an enclosing fn's TP) as unknowable. By-site: 201 removed, 8
  position-identical message transformations (builder.ts tuple-vs-anon-object — the B526
  representation gap now visible where 'T[]' was), 1 new FP at factory/utilities.ts:713 —
  **tsc 5.5 INFERRED TYPE PREDICATES: `filter(helpers, helper => !helper.scoped)` gets an
  inferred `helper is UnscopedEmitHelper` in tsc, selecting the guard overload; we take
  the boolean overload → EmitHelper keeps the ScopedEmitHelper member → TS2339 on
  `.importName` (catalogued M3.4; needs predicate inference from arrow bodies).**
- **Fix 2 (14e9d566, −119): the TS2345 histogram top — three mechanisms.** (a) explicit
  `this`-PARAM annotation wins over the objlit contextual `this` in the call-types walker
  (`withObjThis` now resolves `value(this: Node)` — debug.ts's Object.defineProperties
  `__tsDebuggerDisplay` FP'd ×36 at every `isFoo(this)` arg). (b)
  `tryEmitOptionalMemberArgVsRequiredNamedTs2345` (the optional-member arg emitter that
  synthesizes `T | undefined` locally) consults `propertyAccessNarrowedNonNull` — a
  truthy-guarded access is not undefined (`if (source.valueDeclaration)
  setValueDeclaration(target, source.valueDeclaration)`, checker.ts mergeSymbol ×24+;
  unguarded + wrong-polarity controls pinned). (c) two exposure companions the by-site
  diff caught: numeric-enum → `number` in `isSimpleTypeRelatedTo` (FlowFlags/Comparison/
  TypeFlags ×8), and a this-typed arg narrowed DOWN by a guard substitutes the refined
  type (relation-gated suppression-only; `isIdentifier(this) ? idText(this) : …`).
- **Fix 3 (1791e87a, −53): 'array' vs union-with-array-member at the string layer.** An
  array-literal source against a union member that is array-ish (`[]`-suffix, tuple,
  `Array<X>`/`ReadonlyArray<X>`) is unknowable at the string layer → permissive
  (`sourcesContent = []` vs `(string | null)[] | undefined`, `return []`); a union WITHOUT
  an array member still fires (pinned).
- **Fix 4 (fbda155d, −27): the body-local-shadows-function half of the conflation
  family.** A body-local `const symbolName = …` colliding with an outer/imported FUNCTION
  resolved through the merged globals to the function in bare-identifier ARG positions
  (checker.ts's `canUsePropertyAccess(symbolName, …)` → TS2345
  `(symbol: Symbol) => string` vs `string` ×15 + TS2769 ×8). The call-types walker's
  VariableStatement branch registers an anyType shadow when the colliding outer symbol
  declares a FUNCTION (AST-only gate) — mirrors M1.11's `shadowNestedFunctionNames`.
  First-cut negative control was WRONG about baseline capability (non-callable body
  locals aren't typed by this pass at all — the suite gate caught it); replaced with a
  param-based control.
- **META:** the ~450 ms scratch-CLI repro loop + temporary `println` tracing (the CLI shows
  stdout, unlike gradle) found the root causes fast; the XDBG probe DISPROVED the assumed
  emitter for fix 2b (checkArgumentsAgainstSignature's B469 narrowing never ran — the
  emitter was the dedicated optional-member walker).
- **Residual triage (next-agent):** TS2345×261 — the PARAM-shadow half of the conflation
  remains (`(state: ModuleResolutionState) => any` vs boolean ×14, `TypeCheckerHost` ×14:
  watch.ts's `useCaseSensitiveFileNames` / checker.ts's `host` are enclosing-fn PARAMS
  shadowing barrel-imported functions, read inside NESTED arrows — the mini-repro of the
  same shape does NOT reproduce, so the real blocker is in how the pass enters those
  specific nestings; probe with a marker before theorizing); `Declaration | undefined`
  guard-shape leftovers. TS2322×501 — `string | string` ×24 (interface-override literal
  props: `TsConfigOnlyOption.type: "object"` — per-prop resolution through the narrowed
  redeclaration, M3), `undefined | VisitResult<Node | undefined>` ×12 (generic alias
  unions), residual `T[]` shapes ×~30 (inference gate misses: rest-params, multi-TP),
  `SearchResult<T>` ×10 (un-inferred generic Reference returns), builder.ts
  tuple-vs-anon-object ×8 (B526). TS7006×301 (M3.2 contextual typing) untouched.**

**Round 427 (2026-07-06) — M3.4: the TS2454 bucket round 426 unmasked — three tsc-faithful
`assumeInitialized`/definiteness rules. Self-compile (compiler profile) 1,593 → 1,577
(−16; TS2454 20 → 4, all else byte-identical); suite 9,282 → 9,291 (+9 local, 0
regressions); 1 fix commit (7b2e3807).**
- **(1) Logical assignments are DEFINITE (tsc `getAssignmentTargetKind`):** `??=`/`||=`/
  `&&=` classify `AssignmentKind.Definite` (same as plain `=`), so
  `isSymbolAssignedDefinitely` → `isNeverInitialized` false → a CAPTURED (cross-closure)
  read of an outer `let` assumes initialized when any definite assignment exists
  ANYWHERE, nested closures included (tsc checker.ts:31196 `assumeInitialized =
  … (isOuterVariable && !isNeverInitialized) …`). Our B78.2 anywhere-scan
  (`collectAssignmentsInExpr`) recognized only `=` — tsc's own
  `(sourceStack ??= []).push(source)` / `(trackedSymbols ??= []).push(…)` closures FP'd
  ×13. Compound assignments (`|=`, `+=`, `++`) stay NON-definite
  (`AssignmentKind.Compound`) — the negative control matches unusedLocalsInMethod4's
  `enabledSubstitutions |= …` baseline expectation.
- **(2) A `!`-asserted read assumes initialized:** the literal `node.parent.kind ===
  SyntaxKind.NonNullExpression` disjunct — tsc's own core.ts `return lastResult!`.
  Applied in BOTH read walkers (`findUninitializedRefs` + `walkExprForFlowTS2454`): a
  bare Identifier DIRECTLY under `!` is exempt (covers `x!` and `x!.prop`);
  `(obj.foo)!` still walks the receiver `obj`.
- **(3) The comma-nested definite assignment (`(!memberName ? (memberName = X, true) :
  …)`, checker.ts getSignaturesOfType) needed TWO coupled fixes, both caught by the
  bench by-site diff:** (a) the anywhere-scan's iterative left-spine walk applied the
  assignment-target rule only to the OUTERMOST BinaryExpression — a COMMA expression
  nests the assignment on the LEFT spine, silently skipped; per spine node now (tsc
  `markNodeAssignments` is a full forEachChild walk). (b) The FLOW-based walker's
  expression-bodied-arrow branch (B86.1a) checks the arrow body against the OUTER
  uninit set when reached via a flagged position (a NESTED if's condition is walked
  `inUncheckedBody=true` — which is why the real site only fired inside the enclosing
  `if (kind === SignatureKind.Call …)` block and a top-level repro was clean); it now
  masks out names with a definite assignment inside the arrow body (the captured-read
  exemption), via the same anywhere-scan expression walker.
- **Residual TS2454×4 (triaged, none bounded):** scanner.ts `resultingToken` ×2
  (assigned inside a `while (true)` body before every exit — `isAssignedAtFlow` follows
  only the loop-ENTRY antecedent at FlowLoopLabel, the deliberate back-edge bound; needs
  loop-aware assignment evidence); checker.ts:14106 `indexInfos` (same-container flow
  precision: `x = concatenate(x, …)` self-read in a for-of after conditional seeding);
  generators.ts:1681 (`for (const variable of …)` SHADOWS the outer `let variable` — the
  name-based block-unaware `uninitialized` set resolves the read to the outer decl;
  needs block-scoped shadow tracking).
- 9 local tests (Ts2454AssumeInitializedTest) with negative controls (compound `|=`
  still fires; never-assigned captured read still fires; plain un-asserted read still
  fires; a plain same-container read after an in-arrow assignment still fires — the
  arrow's assignment is invisible to the outer control flow, which is why the real tsc
  source uses `memberName!` for those reads).
- **Perf note:** bench self-time 126.6 → 112.2 s — likely band movement (three
  consecutive runs trended 149 → 127 → 112 s); treat the M5 single-run baseline as
  ~110–150 s until an iterations run.

**Round 426 (2026-07-06) — M3.4 (absorbs M1.2's TS2563 item): faithful TS2563 — flow-walk
DEPTH-TRIP semantics with per-container disable, replacing the B399 per-file node-count
proxy. Self-compile (compiler profile) 1,600 → 1,594 → 1,593 (−7; TS2563 27 → 1 → 0,
TS2454 0 → 20 — pre-existing walker FPs the proxy's blanket per-file filter had masked,
now honestly visible; all else byte-identical by-code); suite 9,276 → 9,282 (+6 local,
0 regressions); 2 fix commits (4d23738f + db69fe59). (The implementing session was OOM-killed
mid-verification with the work complete-but-uncommitted; this session verified, measured,
landed it, and root-caused + fixed the one residual trip.)**
- **Mechanics (4d23738f):** tsc reports TS2563 ONLY when a flow walk recurses 2000 deep
  (checker.ts `getTypeAtFlowNode` `flowDepth === 2000` → `flowAnalysisDisabled` +
  `reportFlowControlError(reference)` at the containing function-or-module block +
  errorType for that container's flow queries thereafter — so TS2454 is suppressed per
  CONTAINER, tsc's OR-rule). All three flow walkers (`narrowTypeFromFlow` + the
  FollowLoopEntry mirror + `isAssignedAtFlow` — the last rewritten ITERATIVE with the
  round-413 accounting: linear pass-through antecedents free, only branch-join /
  condition / loop-entry recursion consumes depth) set `flowDepthTripped` at the trip;
  every depth-0 entry (11 sites) routes through `flowWalkWithTripCheck(reference)` —
  pre-checks `flowDisabledRanges` (disabled → conservative default WITHOUT walking),
  one-shot TS2563 per container via the flow graph's new `containerStarts` (innermost
  containing function-like body block, else the file); the end-of-init TS2454 filter is
  per-container-RANGE (was per-file `cfaTooLargeFiles`, deleted). The dedicated
  `evolvingArrayWalkTrips` init walk supplies the depth consumer for the corpus pin
  (largeControlFlowGraph: auto-typed `const data = []` + 10k top-level `data[0] = 0`
  writes, one level per relevant mutation; after the first trip the container is
  disabled, so the whole file costs ONE 2000-step walk). Our OWN budgets (visit budget,
  global re-entry depth, cycle bail) still truncate SILENTLY — only the per-walk depth
  limit is tsc's TS2563 semantic.
- **The measured trade (by-code diff, everything else byte-identical):** −26
  by-construction TS2563 proxies; +20 TS2454 = pre-existing definite-assignment FPs on
  the giant files the per-file filter had blanket-suppressed. Triage (next bounded
  burn-down bucket, three shapes): (a) DOMINANT ×~16 — cross-closure reads of an outer
  `let` (`let sourceStack: Type[];` in the outer fn, `(sourceStack ??= []).push(…)`
  inside a NESTED function — checker.ts inferFromTypes/serializer, tsc's
  used-before-assigned check applies only within the declaration's own control-flow
  container; captured reads assume initialized); (b) core.ts:2474 `return lastResult!`
  — a NON-NULL-ASSERTED read (tsc does not report TS2454 through a `!`); (c)
  scanner.ts `resultingToken` ×2 — assigned inside a `while (true)` body before every
  exit, read after `Debug.assert(resultingToken !== undefined)`; our `isAssignedAtFlow`
  follows only the loop-ENTRY antecedent at FlowLoopLabel (the deliberate back-edge
  bound), so in-loop assignment evidence is invisible.
- **Fix 2 (db69fe59): the 27th TS2563 was OURS, not the proxy's — `flowCallMightNarrow`
  needs the asserts-callee check (tsc `getEffectsSignature`).**
  diagnosticInformationMap.generated.ts (~2,100 top-level `diag(…,
  DiagnosticCategory.Error, …)` statements): any walk for a `DiagnosticCategory`
  reference found EVERY call's args mentioning the path, so the round-413
  over-approximating gate recursed per call → 2,100 > 2,000 → trip. tsc resolves the
  callee's effects signature BEFORE deciding (cached per node): a non-assert call is
  followed in the `while` loop, consuming NO flowDepth. `flowCalleeMayHaveAssertEffects`
  gives an EXACT verdict for Identifier callees (map-lookup resolution via
  `resolveFlowCalleeDecl`'s Identifier branch — never types a receiver; same decl +
  same predicate test `narrowByAssertCall` applies, so iterating past a false is
  EQUIVALENT, not just safe) and conservative-TRUE for PropertyAccess callees
  (`Debug.assert(x)`) — resolving those types the RECEIVER (the round-385
  services-hang hazard), they keep the consume-depth behavior. LESSON: with faithful
  TS2563, the round-413 "a too-eager gate only costs a depth level" calculus changed —
  a too-eager CALL gate now manufactures a false TS2563 on any >2000-chain of
  path-mentioning non-assert calls.
- **Local tests (CfaTooLargeBailTest 2 → 8):** deep branch chain trips exactly ONCE +
  suppresses the container's TS2454; per-container disable (a sibling function's TS2454
  SURVIVES a trip — the per-file proxy killed it); straight-line 3000-assignment chain
  does NOT trip; evolving-array 3000-write chain trips once at the first statement
  (+ 100-write control) — the largeControlFlowGraph pin the JS-emit-only corpus test
  never asserted; 2,500 non-assert calls mentioning the reference do NOT trip (426b,
  with the TS2339 control still firing); 2,500 asserts-callee calls DO trip exactly
  once (the too-lax-gate landmine control).
- **Perf watch (M5) — 426b is a WIN, not a cost:** bench self-time 150.9 s (round 425,
  dirty) → 149.4 s (426) → **126.6 s (426b, −15.3%)**. The asserts-callee gate doesn't
  just fix the false trip — every path-mentioning NON-assert call used to break the
  fast-forward loop into recursion (+ a narrowByAssertCall resolution at each), and
  tsc's sources are saturated with calls that mention whatever reference is being
  walked; now those iterate for free.

**Round 425 (2026-07-06) — M3.4/M1.12: the TS2339 never-cluster ROOT CAUSES + eight more
narrowing slices. Self-compile (compiler profile) 1,662 → 1,634 → 1,628 → 1,608 → 1,607 →
1,603 → 1,600 (−62; TS2339 68 → 6, never×21 → 2); EVERY step's by-site diff strictly
removals; suite 9,251 → 9,276 (+25 local tests, 0 regressions); 7 fix commits.**
- **Fix 1 (−28, eb28f0d3): union-target guards distribute over candidates + CANONICAL enum
  discriminant keys.** Two coupled root causes behind the never cluster: (a)
  `narrowByCallPredicate`'s positive union branch tested narrow-DOWN against the WHOLE
  target union (`targetUnion <: member` — requires every candidate, never holds); tsc's
  getNarrowedType distributes `mapType(candidate, c => …)` — now per-candidate, strictly
  more-keeping. (b) THE BIG ONE: the round-411 `"symId#member"` key space SPLIT — the same
  enum reaches the key builders as DIFFERENT Symbol instances (program-global merged vs
  declaring-file local via the barrel resolver), so ALL SyntaxKind keys looked pairwise
  disjoint and `typeGuardMemberDisjoint` dropped every guard-narrowed member.
  `canonicalEnumSymbol` (memoized; prefers the global merged symbol when it shares an
  EnumDeclaration NODE by identity and has enumValues) at all four key-builder sites.
  **Also cleared the round-423 "dead-end" `Identifier | ComputedPropertyName`×8 family and
  the isAccessExpression never×4 — the DISJOINTNESS VERDICTS, not the relation, were the
  blocker all along.** META: the scratch repro cleared while the real corpus didn't budge
  (zero site churn); two rounds of repro-enrichment found nothing — only stderr
  instrumentation on the REAL corpus (print the key sets) found the split.
- **Fix 2 (−6, 4cb59a6c): aliased SWITCH discriminants** (tsc compareTypeMappers:
  `const kind1 = m1.kind; switch (kind1) { case TypeMapKind.Simple: m1.source }`) —
  `narrowBySwitchClause` resolves a bare-Identifier subject through the round-423
  aliased-condition back-walk (the const-ness proof) to `<name>.<prop>`.
- **Fix 3 (08835c06, part of −20): four slices** — (a) `narrowByDiscriminantProperty`:
  a UNION-of-literals discriminant (`type: "list" | "listOrElement"`) matches positively
  when ANY constituent equals / survives a negative when ANY differs; an OBJECT-typed
  discriminant (`type: Map<…>`) can never === a primitive VALUE literal → positive drops
  the member (enum-flavored objects excluded). **LANDMINE (+3 nevers in the first cut,
  caught by the by-site diff): BOTH rules gate on a definite VALUE literal — optionality
  is a symbol attribute NOT folded into the resolved prop type, so `x.body === undefined`
  proves NOTHING** (checkGrammarAccessor/isUncheckedJSSuggestion collapsed). (b) `typeof
  x === "object"` three-way union filter (object-like + null match; primitives/undefined/
  CALLABLES — they report "function" — don't; any/unknown kept both branches). (c)
  truthiness of a BOOLEAN-LITERAL discriminant (`info.isStatic ? info.variableName : …`,
  classFields ×2). (d) a DESTRUCTURING read consults flow narrowing of its initializer
  (`if (!result) return; const { version, paths } = result` — moduleNameResolver/
  programDiagnostics/utilities ×6).
- **Fix 4 (fb6c23f4, part of −20): loop-entry retry for the round-418 single-type
  narrow-DOWN suppression** — a guard before a loop narrows a read inside it; the plain
  walk washes at the FlowLoopLabel (checker.ts tuple-inference `constraint.target` ×3).
  The single-type sibling of round-424 fix 1.
- **Fix 5 (−1, aa00dc51): instanceof narrows a SUPERTYPE member DOWN to the class**
  (`tracker instanceof SymbolTrackerImpl` on `SymbolTracker | undefined`, the class
  implements the interface — the subtype-only filter dropped everything). Approximates
  tsc's intersection fallback with the class type; the structural-identity corpus pin
  (instanceofWithStructurallyIdenticalTypes) verified intact.
- **Fix 6 (−4, 5ff41ffb): aliased `===` discriminants** (commandLineParser
  `const optType = opt.type; if (optType === "listOrElement") { opt.element }`) **+
  switch-DEFAULT negative narrowing** (a default clause alone in its flow range narrows
  by every case literal/enum key of the whole switch — executeCommandLine's
  `option.type.forEach`/`option.deprecatedKeys` + bonus utilities.ts:3466; conservative:
  non-literal case exprs bail, fallthrough ranges bail, only LITERAL-typed members drop
  on the direct path).
- **Fix 7 (−3): tsc's positive-empty INTERSECTION fallback** (`hasDynamicName(accessor)`
  vs an unrelated-in-both-directions target now yields `m & c` for object-capable pairs
  instead of `never` — **REVERSES the round-423 dead-end verdict: the 1,708 → 1,710
  net-negative was an artifact of the enum-key split; re-measure dead-ends when an
  upstream root cause falls**) + `typeof "object"` classifies an ENUM member as
  NOT-object (watchPublic's `ScriptTarget | CreateSourceFileOptions`).
- **Process notes:** (1) do NOT `compileKotlinJvm` while a background self-compile A/B is
  in flight — the recompile clobbers class files the running JVM lazily loads
  (ClassNotFoundException mid-run); concurrent CLI RUNS are safe. (2) The patch-split
  protocol again (5 same-file batches split into 7 bisectable commits, tests distributed
  per commit).
- **Perf watch (M5):** the round-425 bench single-run came in at 151 s self-reported vs the
  ~100–137 s recent band (+10%) — single-run noise vs the new retry/back-walk paths not yet
  disentangled; the retries only run on would-be-FP emissions and the back-walks are memoized,
  but re-measure with iterations at the next M5 touchpoint.
- **Residual TS2339×6 (all triaged):** checker.ts:33288/33289 never×2 — try/finally:
  `bindTryStatement` gives a finally-only block ONLY the try-end antecedent (unreachable
  when the try returns → never) — needs a preTry antecedent for the finally entry (but
  NOT for the post-switch continuation — TS2454 regression risk documented in-session)
  PLUS `??=` non-nullish-call-RHS narrowing; checker.ts:28630 `Type | IncompleteType`
  (`flags === 0` vs `flags: TypeFlags` — needs enum-as-literal-union comparability,
  B425/M3.3); moduleNameResolver.ts:2823 (interface modeling, M3);
  builder.ts:2242 (tuple-index on tuple-union, the B526 representation gap);
  es2020.ts:91 (loop-carried `OptionalChain` reassignment, M3). **Next-agent note —
  TS2563×27 (the whole bucket, diagnosed this session):** tsc emits TS2563 ONLY when a
  flow WALK recurses 2000 deep (`getTypeAtFlowNode` `flowDepth === 2000` → set
  `flowAnalysisDisabled`, report at the containing function-or-module block's
  `statements.pos`, return errorType thereafter — checker.ts:29036/28841); on tsc's own
  sources NO walk trips (the linear fast-forwarding our round-413 iteration mirrors keeps
  depth low), so all 27 per-FILE-node-count proxies are FPs by construction. The faithful
  rebuild: trip-detection + a per-CONTAINER disabled set + one-shot TS2563 at tsc's
  position, threaded through ALL flow walkers (narrowTypeFromFlow + FollowLoopEntry
  mirror, the TS2454 definite-assignment walkers), REPLACING the B399 per-file proxy AND
  its `cfaTooLargeFiles` TS2454 end-of-init filter (tsc's OR-rule then holds per
  container naturally). `CfaTooLargeBailTest` pins the CURRENT proxy deliberately and
  must be REWRITTEN to the depth-trip semantics (its 3000-if "big" shape plausibly DOES
  trip a faithful walk — sequential if-joins recurse per join; verify against
  `largeControlFlowGraph`'s baseline which expects TS2563). RISK: un-suppressing TS2454
  on the 27 files may surface previously-masked TS2454 FPs — measure the trade by-site.
  Next big buckets:
  TS2322×751 / TS2345×394 / TS7006×301 (M3 cores), TS2769×45 (M3.1 generic call-site
  inference), TS2563×27 (B399 heuristic → M3.4), TS2591×43 + TS2304×2 (env-legit).**

**Round 424 (2026-07-06) — M3.4/M1.12: seven flow-narrowing burn-down fixes from the round-423
residual triage. Self-compile (compiler profile) 1,707 → 1,691 → 1,687 → 1,683 → 1,680 → 1,672 →
1,662 (−45; TS2339 104 → 68, TS18048 5 → 1, TS2322 756 → 751); every step's by-site diff STRICTLY
removals; suite 9,223 → 9,251 (+28 local, 0 regressions, 2 deliberate pin flips toward tsc
semantics); 7 fix commits, 7 local test files (28 tests).**
- **Fix 1: union-receiver TS2339 suppression survives loop boundaries (−16).** tsc's own
  `parseResponseFile` (commandLineParser): `const text = tryReadFile(…)` (`string | Diagnostic`),
  pre-loop `if (!isString(text)) return;`, reads inside `while` loops — the plain walk washes to
  the declared union at FlowLoopLabel, so the union elaboration FP'd. The union branch of
  `checkMemberAccessMissing` retries with the loop-entry-following variant, SUPPRESSION-ONLY.
  **The landmine that cost the first cut: the "plain walk didn't narrow" gate must be STRUCTURAL
  (member-id sets) — any `&&`/`||` on the path is a 2-antecedent FlowBranchLabel whose union of
  [declared, declared] MINTS a fresh Type.Union (getUnionType does not intern), so `===` misses
  the wash exactly when a compound condition is present.**
- **Fix 2: `narrowByAssignmentRhs` accepts a CALL RHS with a provably non-nullish return
  annotation** (syntactic `typeNodeDefinitelyNonNullish`; own-TP refs and `?.` calls bail;
  `flowAssignmentMightNarrow` needed NO change — it already over-approximates on the LHS). No
  compiler-profile delta: the motivating checker.ts:21170 (`instantiateType`) is an OVERLOAD
  CLUSTER (2 sigs + impl) → `uniqueFunctionDeclByName` ambiguous → no claim. Selecting the right
  overload's return is genuine overload resolution (M3) — noted, deferred. Capability is real for
  single-decl callees (local tests + other profiles).
- **Fix 3: the aliased-condition back-walk follows closure boundaries, if/else joins, and calls
  (−4: builder.ts:431/433 `canCopyEmitSignatures` + 2 bonus JsxCallLike TS2339 at
  checker.ts:37578).** FlowStart → outer flow gated by the B464 captured-name rules on BOTH the
  alias and the walked root; FlowBranchLabel → every REACHABLE antecedent must independently
  prove value preservation and land on the same decl (unreachable ones contribute nothing);
  FlowCall/FlowArrayMutation are value-preserving (a call can't rebind an enclosing let/const —
  tsc's isConstantVariable gate likewise ignores closure-mediated rebinding); plus a per-call
  node MEMO (a 6-term `||` condition fans out a diamond per term). **TWO invisible blockers the
  repro missed but the real builder.ts hit: `FlowAssignment.node` for an assignment EXPRESSION is
  the whole BinaryExpression (`flowAssignmentRootName` must read its LHS — it bailed at
  `!(oldInfo = oldState!.fileInfos.get(…))`), and the un-memoized fan-out exhausted the budget.**
- **Fix 4: prefix-path guard narrowing (−4: moduleNameResolver.ts:849 + 3 bonus builder.ts
  TS2322).** `usesWildcardTypes(options): options is CompilerOptions & { types: string[] }` with
  walked path `options.types` — the predicate arg's path is a proper dot-PREFIX of the walked
  path; when the tail resolves on the predicate target to a REQUIRED property with a provably
  non-nullish type, the positive branch drops nullish. Minimal claim only. **Landmine: property
  OPTIONALITY is a symbol attribute, NOT folded into the property type (`types?: string[]`
  resolves to `string[]`) — `resolvePrefixTailSegment` consults `isOptionalProperty` per segment;
  on an intersection, required iff ANY constituent declares it required.** The
  `narrowByCallPredicate` pre-check widened (allocation-free) to prefix matches — the old
  "exact-match only" note is superseded.
- **Fix 5: `asserts node is U` with U an INFERRED callee type param (−3: transformers/ts.ts:2012
  `Debug.assertNode(node.name, isIdentifier)` — BOTH its TS18048 and its latent co-located
  TS2339, + a bonus emitter.ts:5263).** THREE coupled pieces, each measured necessary: (a)
  `resolveNamespaceMemberFnDecl` PREFERS a TypePredicate-bearing declaration — an overloaded
  assert's valueDeclaration is the annotation-less IMPL, which made every narrowing consumer bail
  before anything else could work; (b) U resolves from the type-guard TEST argument's own
  predicate target (`predicateTargetTypeOfGuardExpr`, mirroring resolveFlowCalleeDecl's paths
  without its call-keyed memo) — **the constraint-chain drop-nullish claim ALONE just trades the
  TS18048 for a TS2339 on the surviving union members** (`Identifier | StringLit` lacks
  escapedText); (c) the constraint chain (`U → T → Node` all non-nullish) stays as the fallback
  for asserts without a resolvable test arg.
- **META (repro-loop discipline):** every fix was developed against a ~400 ms scratch
  mini-project through the compiled CLI with per-fix NEGATIVE controls (wrong polarity /
  reassignment / optional tail / unconstrained TP), and every self-compile step was verified by
  BY-SITE diff (strictly-removals), not just the count. Three of five fixes needed a second
  iteration only discoverable against the REAL tsc source (the assignment-expression flow-node
  shape, the overload-cluster impl, the union-member trade) — always re-measure on the real
  corpus before calling a repro-verified fix done.
- **Fix 6: assignment-overwrite reset (−8: moduleNameResolver.ts 1924/1931/1950 never×6 + bonus
  checker.ts:7144 / program.ts:4048 TS2322).** A shadowing redeclaration after an outer falsy
  guard collapsed to `never`: the walk crossed the outer falsy branch (→ `undefined`), passed the
  inner `const resolved = loadModuleFromImports(…)` UNCHANGED (unclassifiable call RHS kept the
  stale antecedent), and the inner truthy guard narrowed `undefined` → `never`. An overwrite now
  resets to the PRECISE overwritten type: a DECLARATION to its own annotation / initializer-call
  return annotation (the flow-nearest declaration IS the binding the read lexically refers to —
  the flat name-keyed local map is block-unaware/first-decl-wins), a plain `=` to its call-RHS
  return annotation; `??=`/`||=`/unresolvable keep the antecedent pass-through (for `??=` the
  antecedent IS the correct base). **MEASURED trap: resetting to the reader's flat-map
  declaredType instead injects the OUTER shadowed binding's type — 3 new FPs (builder.ts:1814,
  destructuring.ts:114, moduleNameResolver:1950 reshaped) — the precise-type form has zero.**
- **Fix 7: the DebugTypeMapper slice (−10: debug.ts 832–850, the whole family).**
  `type<TypeMapper>(this); switch (this.kind) { case …: this.source }` — FOUR coupled pieces:
  (a) `asserts value is <TP>` binds the TP from the call's EXPLICIT type arguments; (b) an
  assertion on an `any`/`unknown` reference RE-TYPES it to the target (the relation gate
  trivially passes for `any` and kept the useless `any`); (c) `checkMemberAccessMissing`
  consults flow narrowing for `this` receivers (`getTypeOfExpression(this)` is deliberately
  anyType per B101, so the round-418 suppression never applied) and the exhaustive-switch
  receiver typing recovers an anyType receiver through the same re-type; (d)
  `buildNestedFunctionMap` resolves a name collision to the UNIQUE TypePredicate-bearing
  declaration (Debug's `type` is an overload pair — sig + annotation-less impl — and the plain
  "≥2 → ambiguous" rule made the guard invisible to every narrowing consumer; zero or several
  predicate-bearing decls stay ambiguous). The single-file repro cleared in one pass but the
  REAL debug.ts needed (d) — the faithful multi-file repro (barrel import + namespace-local
  overloaded guard) was what exposed it.
- **Residual TS18048×1: checker.ts:21170 (overload-cluster return selection — M3). Next-agent
  note for the classFields.ts:841–859 never×5 sub-cluster: the shape is a De-Morgan early
  return `if (!isPrivateIdentifierClassElementDeclaration(node) || !shouldTransform…) return;`
  whose positive narrowing target `PrivateClassElementDeclaration` is a UNION OF
  brand-INTERSECTIONS (`PropertyDeclaration & { name: PrivateIdentifier }`, …) — the round-418
  positive-collapse fallback is gated `targetType is Type.Intersection` and misses a union of
  intersections, so the filter drops every member → `never`. Extending that gate (or applying
  the member-vs-intersection fold before the drop) is the candidate mechanism — verify with a
  marker first; the negative-exhaustion never pin (instanceofWithStructurallyIdenticalTypes)
  must stay intact. Next TS2339 buckets: never×21 remaining (per-site M3-relation diagnosis, catalogued round 423), DebugTypeMapper×10 —
  now PARTIALLY unblocked: needs `type<TypeMapper>(this)` = `asserts value is T` with an EXPLICIT
  type-arg call (bind T from `expr.typeArguments` — the fix-5 machinery gives the shape), plus
  the TS2339 `this`-branch consulting flow narrowing for path "this" (the round-418 suppression
  excludes `isThisAccess`), plus `this.kind` switch narrowing over the TypeMapper union.
  `Identifier | ComputedPropertyName`×8 stays a measured dead-end (round 423).**

**Round 423 (2026-07-06) — M3.4: exhaustive-switch receiver narrowing (TS2366 → 0) + union-target
type guards + aliased conditions + truthy optional-chain calls. Self-compile (compiler profile)
1,756 → 1,752 → 1,708 → 1,707 (−49 total); suite 9,202 → 9,223 (+21 local, 0 regressions); 3 fix
commits.**
- **Fix 1 (50297e6a): the four round-422 residual TS2366 sites — TS2366 is now ZERO on the compiler
  profile.** Four mechanisms in `requiredUnionDiscriminantKeys`/`enumSwitchKeysFromTypeNode`, exactly
  the round-422 next-agent note's plan: (a) the discriminant RECEIVER is guard-narrowed via the
  pass-dedicated `implicitReturnFlowGraph` (lifted into `currentFlowGraph` only around the walk —
  the arithmetic-pass landmine pattern), so `if (!target) return;` drops `undefined` and
  `if (!isNamedEvaluationSource(node)) return false;` narrows a `Node` param down to the union
  (`getAssignmentTargetKind`, `isNamedEvaluation`); (b) a body-local `const target = call()` receiver
  types from the callee's return annotation (`localConstCallInitType`; single-decl + non-overloaded
  gates); (c) an OPTIONAL enum discriminant contributes a required `@undefined` key instead of
  bailing (`getNewLineCharacter` + `case undefined:`); (d) `LiteralToken["kind"]` — an
  IndexedAccessType branch reuses the union-member walk (`createLiteralLikeNode`), depth-guarded.
  10 local tests (GuardNarrowedSwitchReceiverTest) incl. per-mechanism negative controls; one
  first-cut control was WRONG against tsc semantics (a reassigned-`let` receiver: tsc computes
  exhaustiveness on the non-nullish part and flags the ACCESS, so TS2366 stays quiet) — flipped
  with a comment.
- **Fix 2: union-target type guards + aliased conditions (TS2339 117 → 104, TS2322 784 → 756,
  TS2345 −2, TS18048 −1).** THREE coupled pieces: (a) PARSER — `x is A | B` predicates on the
  UNION (tsc parseTypePredicate → parseType); the old `parseIntersectionOrHigherType` truncated
  the target at `A` and the union-continuation wrapped the PREDICATE (`(x is A) | B`) — the return
  annotation wasn't a TypePredicate at all, so every union-target guard (`isCallOrNewExpression`,
  `isPropertyNameLiteral`, `isOptionalChain`) silently never narrowed; (b) ALIASED CONDITIONS
  (tsc `narrowType` inlineLevel): `const isJsxOpenFragment = isJsxOpeningFragment(node);
  if (!isJsxOpenFragment) { node.tagName }` (the JsxCallLike ×12 family) — the alias initializer
  is recovered by a memoized value-preserving flow BACK-WALK that bails on branch/loop/call/start
  nodes and on reassignment of the alias or the walked root (the const-ness proof); the UNCACHED
  first cut ran the self-compile 4×+ slower — killed and memoized (`aliasedConditionInitCache`,
  keyed by start-FlowNode identity, immune to the cross-file nodeKey collision); (c) the predicate
  union filters consult the round-411 `.kind` key space — PROVABLY DISJOINT keys beat the
  too-lenient relation (enum-member kinds resolve to `any`, so `!isJsxOpeningFragment` collapsed
  JsxCallLike to `never`); plus the round-418 narrow-DOWN suppression accepts a narrowed UNION when
  every member resolves the property. 9 local tests (AliasedConditionAndUnionPredicateTest).
- **Measured dead-ends (2 extra self-compile A/Bs, reverted):** a key-SUBSET ⇒ matched verdict
  (1,708 → 1,720 — brand-intersection targets like `CallChain = CallExpression &
  {_optionalChainBrand}` share the kind without being matched by it); the same rule gated to
  plain-object targets + a tsc-faithful positive-empty → `declared & candidate` fallback
  (1,708 → 1,710 — fixed 4 nevers, surfaced a 12-site checker.ts alias-resolution cluster);
  same-SYMBOL union membership (exact no-op — the real-tsc member/target instances are not
  symbol-identical, so the relation failure is deeper).
- **Fix 3: truthy optional-chain CALL conditions (TS18048 −1, zero site churn).**
  `if (state.referencedMap?.size()) { state.referencedMap.keys() }` (builder.ts:1332) — a nullish
  receiver short-circuits the chain to `undefined` (falsy), so the truthy branch excludes nullish
  from any `?.`-guarded intermediate. A dedicated walk in `applyConditionNarrowing`'s
  CallExpression branch, positive branch only (a falsy chain proves nothing — the receiver may be
  present with a falsy call result, pinned by a local control). 2 local tests.
- **Residual (by-site diff −68/+24 for fix 2 — the +24 catalogued in the session listalls):**
  never×10 (checker.ts 35055/35094/52738/52739 `isAccessExpression`-family positive collapses,
  factory/utilities 1747/1750, classFields 2689, utilities 5445/6840/6843),
  `Identifier | ComputedPropertyName` ×8 (esDecorators/namedEvaluation — the negative branch
  cannot prove `Identifier <: PropertyNameLiteral` on the real types; same-symbol identity ALSO
  fails, so the member instances differ — an M3 relation/instance question), partial narrowings ×5,
  TS2322×1. All are the SAME M3-relation-gap family newly EXPOSED because union-target guards now
  narrow at all — each was previously invisible behind the parse truncation. Next targets:
  TS2339 never×27 remaining, DebugTypeMapper×10 (`asserts value is T` + `this`-path narrowing),
  `string | Diagnostic`×6 (commandLineParser.ts:2016-2032 — TRIAGED, next-agent note: the shape is
  `const text = tryReadFile(…)` (string | Diagnostic via the call-types local recording) +
  `if (!isString(text)) { …; return; }` — every narrowing piece exists (isString is a plain
  single-target guard, the negative branch drops Diagnostic), so the question is WHY the union
  TS2339 emitter doesn't consult it for this receiver — probe with a marker before theorizing;
  candidate suspects: the emitting site may be a different pass without `currentFlowGraph`, or the
  local-const union type reaches the emitter through a path that bypasses
  `getNarrowedTypeForReference`). **TS18048×5 remaining, all triaged with concrete
  mechanisms:** checker.ts:21170 `type.restrictiveInstantiation = instantiateType(…)` then a
  sub-path read — needs `narrowByAssignmentRhs` to accept a CALL RHS whose resolved callee declares
  a non-nullish return annotation (bounded; mind the flowAssignmentMightNarrow keep-in-sync
  landmine); builder.ts:431/433 `canCopyEmitSignatures` — the aliased-condition back-walk bails at
  the closure FlowStart (alias declared OUTSIDE the `forEach` closure, used INSIDE) — needs
  outerFlow-following with the B464 captured-name gates; moduleNameResolver.ts:849 loop-crossing
  narrowing; transformers/ts.ts:2012 generic `Debug.assertNode(node.name, isIdentifier)` (the
  predicate target is an inferred type param — M3.1-adjacent).

**Round 422 (2026-07-06) — M1.12/M3.4: FIVE bounded FP-safe fixes from a fresh full `--listAll`
bucketing — overload-arg flow narrowing, optional-chain discriminants, mixed enum/literal
discriminant keys, boolean-literal overload narrowing, and union-`.kind` exhaustive switches.
Self-compile (compiler profile) 1,799 → 1,756 (−43, zero new codes); suite 9,178 → 9,202 (+24 local, 0
regressions); 5 fix commits (be6f0645, d504a6c3, fc9780c4, 44cee15e, 02764aaf).** Method (the
M1.12 note): fresh `--listAll` at HEAD reproduced 1,799 exactly; bucketing by normalized shape
put the M3 cores on top (TS2322×784 / TS2345×396 / TS7006×301) with TS2769×60 the biggest
un-triaged non-core family — and sampling its sites found FOUR bounded mechanisms plus a
deferred-list TS2366 slice that round 415's key-space work had just unblocked:
- **(1) overload arg-check flow narrowing (TS2769 60 → 47, −13; be6f0645):** the five overload
  arg-check helpers typed args with raw `getTypeOfExpression`, unlike the single-signature path
  (B469) — so a guard-narrowed union arg (`containingFile ? getDirectoryPath(containingFile) :
  undefined`, `if (typeof version === "string") version = new Version(version)`; tsc's own
  moduleNameResolver.ts:545 / semver.ts:228) failed EVERY overload → FP TS2769. New
  `overloadNarrowedArgType` (Identifier/PropertyAccess + Union → `getNarrowedTypeForReference`)
  routed through all five helpers. Suppression-only by monotonicity. The first negative-control
  attempt exposed a PRE-EXISTING false-negative family, not a fix bug: assigning a NULLISH
  literal after a guard (`if (x !== undefined) { x = undefined; use(x) }`) does not narrow the
  reference to `undefined` (`narrowByAssignmentRhs` nullish-RHS no-op) — even the var-decl path
  misses it; noted for M3.4, control replaced with an unrelated-guard shape.
- **(2) optional-chain discriminant access proves the receiver non-nullish (TS18048 10 → 7,
  −3; d504a6c3):** `x?.kind === RHS` (true branch) can only hold when `x` is non-nullish —
  `undefined?.kind` is `undefined`, never equal to a non-nullish RHS. tsc's checker.ts:8061/8062
  (`signature.declaration?.kind === SyntaxKind.JSDocSignature && signature.declaration.parent…`)
  + 5332 (the `||`-of-two-optional-discriminants ternary). This resolves round 416's dead-end
  note: (a) the flow DOES route through `narrowByDiscriminantProperty` (via
  applyConditionNarrowing on the `&&`-left FlowCondition) — the pre-416 attempt failed only on
  (b), the literal-only RHS gate: the fix gates on "RHS definitely non-nullish"
  (`rhsDefinitelyNonNullishForDiscriminant`: enum member OR non-null/undefined literal), and the
  nullish-drop SURVIVES the per-member filter bail (members without readable annotations are
  kept — including the nullish intrinsics, which was the whole bug). Positive branch only.
- **(3) mixed enum + string-literal discriminant unions (TS2339 134 → 117, −17; fc9780c4):**
  tsc's PrivateIdentifierInfo (`kind: PrivateIdentifierKind.Accessor | … | "untransformed"`,
  classFields.ts ×~19 sites) — the literal-typed member had NO representation in the round-411
  enum key space, so it survived every enum-member case and the over-wide union FP'd TS2339 on
  variant props. String-literal discriminants now carry disjoint `lit:s:` keys
  (`literalDiscriminantKeyOfType`; `enumMemberKeysOfTypeNode` LiteralType branch — which also
  serves the equality path — plus `narrowBySwitchClause`'s enum path accepting all-convertible
  literal cases, still gated ≥1 genuine enum key so pure-literal switches stay on the
  corpus-pinned assignability path). Deliberately string-ONLY and namespace-DISJOINT: a string
  enum member never equals a plain string literal in tsc narrowing, but numeric enums ARE
  number-comparable → numeric literals stay unrepresented (member conservatively KEPT, matching
  tsc), pinned by a local test.
- **(4) boolean args vs literal `true`/`false` overload params (TS2769 −2; 44cee15e):** our
  `boolean` is not modeled as `true | false`, so fix (1)'s Union gate couldn't refine tsc's own
  `if (!allowAmbiguity) … parseParametersWorker(flags, allowAmbiguity)` (parser.ts:5453/5460,
  overloads on literal `true`/`false` params). `overloadNarrowedArgType` now narrows a synthetic
  `true | false` union for a bare-boolean reference arg, accepting only a single-literal result.
- **(5) union-`.kind` exhaustive switches (TS2366 12 → 4, −8; 02764aaf):** rounds 414/415
  deferred "Pattern C2's discriminated-union half" as the larger M3.4 slice — fix (3)'s key
  space unlocked its FP-safe subset: `requiredUnionDiscriminantKeys` claims a `switch (x.kind)`
  exhaustive ONLY when the receiver resolves to a UNION whose EVERY member contributes a
  complete key set from a REQUIRED (non-optional) declared annotation (enum members and/or
  string literals; multi-valued `kind: K.B | K.C` contributes both), and every case converts.
  Any gap — optional `kind?:`, nullish receiver, unreadable/numeric annotation — bails and
  TS2366 STANDS. tsc's own `getMappedType` (TypeMapper) / `getAssignmentTargetKind`. An
  Identifier receiver resolves via its PARAM ANNOTATION first (this pass has no param scope in
  getTypeOfExpression — the first cut was inert until that mirror of requiredEnumSwitchKeys'
  own rule). Strong negative controls per the round-414/415 doctrine (`.errors.txt` disabled =
  the corpus is a weak gate here): missing-member / optional-kind / `| undefined`-receiver all
  still fire.
24 local tests across 4 new files (OverloadArgFlowNarrowingTest ×8,
OptionalChainDiscriminantNarrowingTest ×5, MixedEnumLiteralDiscriminantTest ×5,
UnionKindDiscriminantExhaustiveSwitchTest ×6). Bench rows: 1,766 @ fc9780c4 (fixes 1–3) and 1,756 @ 02764aaf (fixes 4–5), both in bench/self-compile-tsc.tsv. Perf: self-compile time in the
~100–131 s single-run variance band (round 413 note). **META (process): the patch-split
protocol worked well for landing multiple checker fixes from one working tree as separate
bisectable commits (git diff → split hunks by marker → checkout → apply per fix), with the
full suite gating each tree state that got committed. And the fastest repro loop for checker
work is a scratch mini-project run through the compiled CLI (~400 ms/iteration), not a gradle
test cycle.** Residual: TS2769×~45 (generic call-site inference — createNodeArray/
createImportAttributes chains, `Program | T` generic-union callees, lib includes() chains →
M3.1), TS2339×117 (never×29 via alias-collapse, JsxCallLike×12 alias-of-alias unions,
DebugTypeMapper×10 `this`-narrowing, `string | Diagnostic`×6 → M3/M3.4), TS18048×7
(assignment-in-guard variants, deep property paths), TS2366×4 (utilities.ts/nodeFactory.ts —
DIAGNOSED, next-agent note: these need the switch RECEIVER guard-narrowed before
`requiredUnionDiscriminantKeys` reads it — `isNamedEvaluation`'s `node` is a bare `Node` param
narrowed only by the `isNamedEvaluationSource(node)` early-return, and `getAssignmentTargetKind`'s
`target` is a call-initialized LOCAL (`const target = getAssignmentTarget(node)`) invisible to this
pass, narrowed by `if (!target) return`. The fix needs (a) a DEDICATED flow-graph field set in
`checkImplicitReturns`' per-file loop and lifted only around the narrowing call — NOT
`currentFlowGraph` for the whole pass, the arithmetic-pass 78-test landmine — and (b) for the
local-const case, initializer typing from the callee's return annotation), and the M3 cores
TS2322×784 / TS2345×396 / TS7006×301.

**M2 — Real-lib migration (staged; decompose further at start)**

- [x] **M2.1 Lib graph loader.** COMPLETE (round 390, all four sub-steps below). Parse + bind the real `typescript-repo/src/lib/*.d.ts`
  selected per `target`/`lib` (the `/// <reference lib="…" />` DAG: lib.es2020 →
  es2019 + es2020.* pieces), as a process-wide immutable snapshot parsed ONCE and
  shared across programs (this snapshot is deliberately the seed of M5's incremental
  infra). Behind a CompilerOptions flag so corpus A/B comparison is possible.
  **Decomposition (round-389 scoping; work as separate commits):**
  - [x] (a) *Ship the lib text* — DONE (round 390): `generateRealLibSources`
    Gradle codegen (guardrail-approved as part of M2) extracts the non-DOM ES set
    (100 files, 565,732 bytes) from the typescript-repo object DB (`git ls-tree` +
    `git show` at the pin — works offline, the sparse working tree never
    materializes `src/lib`) into `build/generated/real-lib/RealLibFiles.kt`
    (commonMain srcDir; every Kotlin compile task depends on it). The 64 KB
    class-file string-constant TRAP is dodged by chunking each file into
    `sb.append("…")` literals of ≤ 60,000 modified-UTF-8 value bytes split at
    line boundaries (es5 = 4 chunks), reassembled at runtime — never fold chunks
    into one literal / `const val` concat (constant-folds back over the cap).
    Keys are bare lib names (`es5`, `es2015.core`); content byte-faithful (CRLF
    preserved). 3 local tests (RealLibFilesTest) pin multi-chunk reassembly +
    the reference directives (b) will consume.
  - [x] (b) *DAG resolver* — DONE (round 390): `RealLibResolver` (RealLibs.kt)
    ports tsc's `libEntries`/`libMap` verbatim (110 entries incl. the `es6`/`es7`
    aliases + the `esnext.bigint`-style back-compat fallbacks),
    `targetToLibMap`/`getDefaultLibFileName` (target default = the `.full`
    variant; ES2015 → `lib.es6.d.ts`, ES5/ES3 → `lib.d.ts`), the
    `/// <reference lib>` closure (program.ts `processLibReferenceDirectives`),
    and — the non-obvious part — tsc's FINAL order = `getDefaultLibFilePriority`
    (libEntries index; `lib.d.ts`/`lib.es6.d.ts` first), NOT the DFS discovery
    order (es5 references decorators, which still sorts near the END). Unshipped
    DOM/host references and unknown names are returned in `Resolution` side
    channels, not silently dropped. 6 local tests (RealLibResolverTest) against
    the real shipped headers.
  - [x] (c) *Snapshot* — DONE (round 390): `RealLibSnapshots` caches the PARSE
    per lib file process-wide (immutable shared ASTs; fileName = the DISTRIBUTED
    name `lib.es5.d.ts`/`lib.d.ts` that baselines render); BINDING is
    deliberately per-consumer (`bindLibFiles` returns fresh BinderResults) —
    `mergeSymbolTable` MUTATES merged-in symbols (the merge-pollution gotcha),
    so a shared bound table would leak one program's user-declaration merges
    into the next program's lib; revisit bind-sharing at M5.4/M5.5. Not
    thread-safe yet (single-threaded checking today; M5.4 adds sync).
    `CompilerOptions.useRealLibs` (default false) added. The real es5.d.ts
    (218 KB) parses + binds cleanly (Array/Object/Promise/parseInt all bound).
    4 local tests (RealLibSnapshotTest) pin parse-once identity, fresh-bind
    non-identity, and dist naming.
  - [x] (d) *Checker wiring + A/B* — DONE (round 390): `bindRealLibs()` in
    Checker (gated `options.useRealLibs`; `// @useRealLibs` directive added)
    resolves `options.lib`/`target` through `RealLibSnapshots`, merges each
    file's locals in inclusion order (es2016.array.include's `Array<T>` merges
    onto es5's — verified end-to-end by `[1,2,3].includes(2)` type-checking
    clean), and populates the same `builtinLibDecls`/`builtinLibMemberDecls`
    identity sets; `builtinLibSourceFile` keeps the first (es5-layer) file
    (multi-file position lookups are inherently ambiguous; lib diagnostics
    render `:--:--` so only the display name is affected). 4 local smoke tests
    (RealLibsInCheckerTest). **A/B (default temporarily flipped true, full
    corpus): 40 failures out of 8,961 — ALL error-baseline subtests, ZERO
    js-emit regressions, +70% wall time (1:54 → 3:14). The 40 are the predicted
    compensating-hardcode collisions — the M2.2 burn-down list (below).**
- [ ] **M2.2 Corpus A/B and default flip.** Burn down the round-390 A/B diff
  (baselines were produced by real-lib tsc, so divergence generally means one of our
  compensating hardcodes — fix by deletion). Flip the default when green. **Round 391
  fixed 2 (arguments + unaryOperatorsInStrictMode — value-position spelling suggestions).
  Round 392 fixed the TS2728 lib-file-attribution cluster (libMembers + externModule +
  errorMessageOnObjectLiteralType; initializedDestructuringAssignmentTypes also cleared).
  Round 393 fixed the lib-declared utility-alias modifier cluster (omitTypeHelperModifiers01,
  omitTypeTestErrors01, intersectionsAndOptionalProperties, parameterListAsTupleType via
  `isBuiltinUtilityAlias` materializer routing) + redefineArray (construct-sig double-emit
  guard). Round 394 fixed keywordExpressionInternalComments (Object.prototype-member
  fallback in the TS2790 delete check — `delete Array.toString` under real libs).
  Round 394 ALSO fixed jsExportMemberMergedWithModuleAugmentation2 (node-first
  `libFileOfDecl` in the B553 CJS-string-import TS2728 builder, the unwired 4th of
  round 392's attribution sites).
  A/B RECOUNT (round 394): 27 corpus failing testcases remaining
  (`.errors.txt` subtests):** arrayBufferIsViewNarrowsType, builtinIterator,
  consistentAliasVsNonAliasRecordBehavior, correctOrderOfPromiseMethod,
  deleteExpressionMustBeOptional_exactOptionalPropertyTypes (×2 variants),
  dissallowSymbolAsWeakType, divergentAccessorsTypes6/8,
  doYouNeedToChangeYourTargetLibraryES2016Plus, flatArrayNoExcessiveStackDepth,
  genericIndexedAccessVarianceComparisonResultCorrect, implementArrayInterface,
  interfaceAssignmentCompat, isArray,
  keyRemappingKeyofResult,
  mappedTypeGenericWithKnownKeys,
  mappedTypeIndexedAccessConstraint, mergedClassNamespaceRecordCast,
  narrowingPastLastAssignment, requiredMappedTypeModifierTrumpsVariance,
  specialIntersectionsInMappedTypes, stringMappingAssignability,
  templateStringsArrayTypeRedefinedInES6Mode, truthinessCallExpressionCoercion2,
  typedArraysCrossAssignability01, uncalledFunctionChecksInConditional2. Most are
  documented lib-divergence pins (typed-array chains, Date/Array hardcoded counts,
  LIB_MIN_TARGET) — M2.3's unwind list overlaps heavily; work them together. Also
  measure/mitigate the +70% suite wall time before flipping (per-key bound-lib reuse
  within a run, or M5-style sharing). **Triaged failure MODES (round 392 sampling; see
  the round-392 note): TS2322-from-richer-lib-types (correctOrderOfPromiseMethod
  Promise.all tuple, narrowingPastLastAssignment evolving-array concat, keyRemappingKeyofResult
  → engine/M3); SWAP (omitTypeHelperModifiers01 TS2540↔TS2322 — Omit modifier/readonly);
  MISSING (mergedClassNamespaceRecordCast/interfaceAssignmentCompat/divergentAccessorsTypes6 —
  Record cast + documented walkers); double-emit/display (builtinIterator TS2515 dup,
  doYouNeedToChange... `Promise<T>` vs `Promise<unknown>`); keywordExpressionInternalComments
  = we emit NOTHING under real libs (investigate — possible exception).**
- [ ] **M2.3 Unwind lib-divergence pins.** Grep anchors: `LIB_MIN_TARGET`,
  `LIB_MIN_TARGET_SOFT`, `BUILTIN_LIB_VALUE_INTERFACES`, `KNOWN_GLOBALS` (derive from
  the loaded libs), the hardcoded Date TS2740 message, hardcoded "and N more" counts,
  hardcoded overload chains copied from baselines (`WEAKSET_2769_CHAIN` etc.),
  `libFeatureAvailable`. Delete `BUILTIN_LIB_SOURCE` last.
**M3 — Type-engine completion, dashboard-driven (the long pole; re-scope 2026-07-03:
the acceptance bar per item is the self-compile burn-down — handle the shapes tsc's
source uses with the corpus suite as the regression net, NOT conformance completeness;
each item still decomposes into a multi-session campaign — read PLAN-PHASE-4.md §
"Known architectural blockers" for accumulated detail before starting)**

- [ ] **M3.1 Generic instantiation + call-site inference** (remove the
  `hasUnresolvedTypeParams` relation bail; real type-argument inference incl.
  contextual return positions). This is the documented #1 engine blocker. V1 bar
  (re-scope 2026-07-03): burn down the compiler profile's TS2322×777 (top shape
  `Type 'T[]'` ×174), TS7006×301 (call-arg contexts whose callee doesn't resolve),
  and the TS2345 share — tsc-source shapes only; full conformance generality is
  post-v1. **STARTED (round 428, −391: 1,577 → 1,186):** nullable-union generic
  param inference (`nullableUnionOfTpMode`) + overloaded all-generic callee
  inference in `getReturnTypeOfCallExpression` killed the `T[]`-return family
  (TS2322 751 → 501); the TS2345 histogram top (this-param binding, guarded
  optional-member args, enum→number) + the body-local-shadows-function anyType
  registration took 394 → 261 (TS2769 45 → 36). **CONTINUED (round 429, −186:
  1,186 → 1,000, TS2345 261 → 86):** call-types lexical shadowing (body-locals
  vs enclosing params; destructured params — the round-428 "mini-repro does not
  reproduce" residue was DESTRUCTURING, resolved via the
  `currentParamBindingNames` side set in `getTypeOfIdentifier`; arrow
  own-params), String-lib RegExp signatures, optional-param union args,
  NonNull-asserted args, guard-narrowed interface/unknown args (the ~110-site
  dominant mechanism, never-param excluded), string-enum→string (round-410
  deferral resolved), rest-arg flow narrowing. Next sub-slices (triaged in the
  round-429 session note): `'true'` vs `'false'` nested-overload selection ×5,
  string-vs-literal-union args ×10, residual `T[]` inference-gate misses (~40:
  readonly-array `TypeOperator` params defeat `nullableUnionOfTpMode` —
  `addRange(to: T[] | undefined, from: readonly T[] | undefined)`),
  `SearchResult<T>` un-inferred generic Reference returns ×10, `string | string`
  interface-override literal props ×24 (M3), inferred type predicates (tsc 5.5 —
  `helper => !helper.scoped`, M3.4), exhaustive-switch `assertType<never>`
  (M3.4 exhaustiveness). **CONTINUED (round 430, −64: 1,000 → 936):** the
  `T extends {}` constraint was killing the whole `append` inference (empty-object
  relation rule, TP-source excluded per genericPrototypeProperty3), readonly-array
  anchors (`Reference(ReadonlyArray, [T])`), TP-from-PREDICATE binding
  (`getFirstJSDocTag(node, isJSDocAugmentsTag)` → T from the guard's target).
  **CONTINUED (round 431c/d, part of −385: 936 → 551):** engine return-checking
  reaches switch/try bodies (returnTypeNode threading through both dispatchers)
  behind the FOREIGN-TP source gate (`typeContainsForeignTypeParam` — an
  un-inferred generic call result is our inference gap, not a user error;
  cleared the `T[]`/`U | undefined`/`SearchResult<T>` return families, ~130
  sites incl. anonymous-alias-body members). Next: extend the foreign-TP gate
  to the ASSIGNMENT/VAR-DECL paths (×~6 siblings), contextual-RETURN inference
  (`parseTokenNode<T>()`, no args — M3.2), `Iterable<T>`-style single-arg
  generic anchors, `.map`-family callback-return inference (M3.2),
  NodeArray-covariance via cross-file heritage (`TypeNode <: Node`).
- [ ] **M3.2 Contextual typing engine** (parameters, returns, object/array literals,
  generic-context propagation — replaces `applyContextualParamTypesForArrow`-era
  special cases). **STARTED (round 431, −295 of the session's −385): the TS7006
  core fell 301 → 11** — callee resolvability (nested-fn map + the new
  `implicitAnyScopes` lexical scope stack), assignment-RHS contextual typing
  from the LHS declared type (B476 single-applicable-sig rule; `||`/`??` both
  operands, `&&`/comma right-only — corpus-pinned asymmetries), receiver
  member resolution through intersections/lazy References/extends bases, and
  call-return-annotation locals. Residual TS7006×11 triaged in the round-431
  note (namespace-local annotations, initializer-inferred fn locals).
- [ ] **M3.3 Mapped / conditional / template-literal / indexed-access evaluation**
  (replace the AST-shape walkers; delete the superseded dedicated walkers and pins).
- [ ] **M3.4 Flow narrowing unified into identifier typing** (`getTypeOfIdentifier`
  consults the flow graph; retire the per-consumer narrowing carve-outs). **Absorbed
  from M1.2 (round 386): faithful TS2563 walk-exhaustion emission — DONE (round 426,
  earlier than predicted: the existing narrowing/definite-assignment walkers ARE deep
  flow walks, so trip detection didn't need full flow-based identifier typing).**
  Depth-trip at 2000 recursion levels in all three flow walkers → one-shot TS2563 at
  the containing function-or-module block + per-container `flowDisabledRanges`
  (replacing B399's per-file node-count heuristic + its `cfaTooLargeFiles` TS2454
  filter — the 27 self-compile TS2563 FPs are gone: 26 from the proxy removal, the
  27th via round 426b's asserts-callee gate in `flowCallMightNarrow`; TS2454×20
  pre-existing walker FPs the per-file filter had masked are now honestly visible,
  the next bounded burn-down bucket). The corpus largeControlFlowGraph shape
  (top-level evolving-array writes) trips via the dedicated `evolvingArrayWalkTrips`
  init walk (pinned by CfaTooLargeBailTest — the generated corpus test is
  JS-emit-only); GENERAL use-site evolving-array typing (function-local auto arrays)
  still belongs to this item's flow-based identifier typing. **Absorbed from M1.4 (round 387):
  the self-compile TS2339 family's dominant bucket (461 union-receiver sites + the
  named `Type`/tuple ones) is user-type-guard narrowing feeding MEMBER ACCESS on tsc's
  big AST-node unions (`isTypeParameterDeclaration(node) ? …node.name… : …` on
  `HasModifiers`; `isGenericTupleType(type) && type.target.…`) — the narrowing
  consumers exist, but predicate-filtering 40-member merged-interface unions (and
  ternary-position narrowing) under-resolves; measure per-consumer before rebuilding.**
  **DONE (round 409, 8f22d126) for the Identifier-callee case — a user type-guard / assert
  imported through an `export *` barrel now NARROWS.** Two independent gaps blocked it (round
  408's naive "wire resolveAlias into resolveFlowCalleeDecl" was inert because of the FIRST,
  found only this session): (1) `resolveModuleSpecifier` won't strip the ESM `.js` extension
  (TS2459 FP-avoidance) → `resolveAlias` couldn't resolve ANY `.js` import (tsc uses `.js`
  everywhere), so even a DIRECT imported guard failed; (2) `targetFile.locals[name]` misses
  through an `export *` barrel. Fixed FLOW-ONLY via `resolveImportedFunctionLikeDecl` (memoized;
  finds the module `.js`-tolerantly + follows `export *` via `resolveExportedSymbolThroughStars`).
  **Deliberately NOT in the general `resolveAlias` — a first cut there measured a self-compile
  REGRESSION 2,618 → 2,915 (TS2315×466 flood from resolving barrel-imported TYPES, an M3 gap),
  reverted.** Self-compile 2,618 → 2,443 (TS2339 838 → 672); services hang-check clean.
  **ALSO DONE (same session, 4d0192ad): the barrel-imported NAMESPACE-member case**
  (`Debug.assertIsDefined(x)` / `Debug.isString(x)`). `resolveNamespaceMemberFnDecl` resolved the
  receiver `Debug` via the general (byte-identical) `resolveAlias`, so a barrel-imported namespace
  didn't resolve → the member guard/assert never narrowed. Added the flow-only
  `resolveImportedNamespaceSymbol` (the namespace-receiver sibling of
  `resolveImportedFunctionLikeDecl`; memoized in `importedNamespaceSymCache`, never touches the
  resolveAlias cache), consulted only when the general resolveAlias fails to yield a module symbol.
  2 load-bearing tests (both verified to FAIL without it). **DASHBOARD-NEUTRAL on the compiler
  profile (2,443 → 2,443) — the round-408 `Debug.assertIsDefined(machine.onLeft)` cases were
  flagged "unreproducible" (a deeper cause than resolution), so resolving the barrel `Debug` alone
  doesn't flip a compiler-profile FP; landed as a principled capability extension (cf. round-404's
  neutral M1.13) for the other 7 profiles / real projects where barrel-imported namespace guards
  are ubiquitous.** Also pervasive: `some(x)`/`isDefined(x)` Identifier guards across the
  TS18048/TS2339/TS2722 families are now narrowed. **REMAINING M3.4 investigation: the round-408
  `Debug.assertIsDefined` FPs (×3) have a root cause OTHER than resolution — worth a fresh repro
  (generic-class param-property assert + the `asserts x is NonNullable<T>` path through a real-code
  interaction) now that the barrel resolution is no longer a confound.**
  **ALSO DONE (round 411, aba1dcb6 + 7a771a77) — two more union-narrowing slices, −59 (TS2339
  672 → 614): (a) DISCRIMINATED-UNION narrowing keyed on an ENUM-MEMBER discriminant
  (`s.type === Kind.A` / `switch (s.type) { case Kind.B }` where the member declares
  `type: Kind.A`). Enum-member types resolve to `anyType` (not modeled as literals), so neither
  the equality path (`narrowByDiscriminantProperty`) nor the switch path (`narrowBySwitchClause`)
  matched — AST-based fix keyed on the member's declared `type: Enum.Member` annotation; the
  barrel-imported enum resolves FLOW-ONLY via `resolveImportedEnumSymbol` (the enum sibling of
  `resolveImportedNamespaceSymbol`). Unlocked tsc's UpToDateStatus (23→1) / TypeMapper (16→6) /
  PrivateIdentifierInfo (13→0). (b) A type-guard `x is C` narrows a union member DOWN to `C` when
  `C <: member` — `narrowByCallPredicate`'s positive branch only kept `member <: C` and collapsed
  a supertype-only union to `never` (`Expression | PropertyName` narrowed by
  `is TaggedTemplateExpression` → the `never`-receiver TS2339 family, 39 → 20). Both FP-safe /
  suppression-only. Remaining `never`×20 (generic-alias resolution, closure-capture),
  `Type`×46 (closure-capture + `&&`-narrowing into a `findIndex` callback), TS2722×2 (loop-stable
  narrowing of un-reassigned property paths / object-literal-method flow) are the next M3.4
  sub-steps — each needs narrowing to survive a FlowLoopLabel / flow into closures + object-literal
  methods, not a bounded slice.**
  **ALSO DONE (round 413, c4c8850c + 68da80da) — the builder.ts `Debug.assert(isDefined(state))`
  TS18048 family (round-412's flagged "highest-value M3.4 target") is FIXED, −407 (TS2339 614 →
  237). The round-412 "walk hits `NARROW_MAX_DEPTH`" diagnosis was a RED HERRING (an instrumented
  run showed ZERO narrowing-walk truncations; the assert and use are co-located). The real cause:
  `computeExportedSymbolThroughStars`'s leaf lookup returned a non-re-exported IMPORT alias, so the
  `export *` search for `Debug` stopped at `core.ts` (which merely IMPORTS `Debug`) before reaching
  `debug.ts`'s `export namespace Debug` — `Debug.assert` never resolved → its bare-assert narrowing
  never fired. Gated the leaf on genuine export (`name in getModuleNamedExports(file)`, memoized;
  flow-only, FP-safe). Barrel-imported `Debug.*` + every barrel guard now resolves. Companion
  (68da80da, dashboard-neutral): the documented "tsc-shaped budget consumption" sub-item — both
  narrowing walkers follow LINEAR pass-through antecedents iteratively (tsc's `getTypeAtFlowNode`
  `while(true)` loop) WITHOUT consuming `NARROW_MAX_DEPTH`; eliminates all depth truncation but
  the compiler profile never hit it (co-located asserts). Perf: self-compile 72 → 92 s (extra
  narrowing; M5). LESSON: verify a "walk hits the cap" claim by instrumenting the truncation, NOT
  by inferring from a file's node count.**
**M5 — Performance (starts at v1 compliance — the 8 tsc-source profiles compile clean)**

- [ ] **M5.1 Profiling grid**: JFR/async-profiler over the project corpus (cold CLI,
  warm in-process via BenchMain, RSS); publish flamegraph findings in a session note
  before optimizing anything.
- [ ] **M5.2 Allocation discipline in the relation engine** (type interning /
  canonicalization — replace the documented fresh-mint caps like the
  `getPropertyTypeForRelation` depth bound with proper sharing).
- [ ] **M5.3 Cache effectiveness under scope contexts** (today `nodeTypes` is bypassed
  whenever any resolution context is active = recompute on every generic-heavy path).
- [ ] **M5.4 Parallel per-file checking** via the existing-but-unused `CheckerPool`
  (LinkStore side-tables already keep binder output immutable for this).
- [ ] **M5.5 Incremental compilation** (`.tsbuildinfo`-style reuse; the M2.1 shared
  lib snapshot is the first piece).
- [ ] **M5.6 Native target re-enable + tune** (linuxX64 was disabled in c7e3535f;
  native already wins <10 kLOC — fix the big-input inversion, likely GC/allocation).
- [ ] **M5.7 Numeric targets** (proposed; confirm with owner at M5 start): warm ≥ tsc
  throughput on 500k-LOC real code; cold CLI ≤ 1.5× tsc on medium projects; RSS ≤ tsc;
  stretch: approach tsgo on native.

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

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
