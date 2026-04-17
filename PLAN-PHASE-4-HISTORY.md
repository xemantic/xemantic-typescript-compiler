# PLAN-PHASE-4 history

Archived session notes moved out of `PLAN-PHASE-4.md` on 2026-04-17 to keep the
live plan focused on the active queue + blockers + workflow. Nothing here is
actionable — these are historical records of completed fixes.

**Use this file only when** you need to understand why a past fix was made, what
was tried and reverted, or what the landscape looked like at a given point.
Otherwise skip it and work from the live `PLAN-PHASE-4.md`.

All information here is also in git history (commit messages + individual
Checker.kt / Parser.kt / Transformer.kt / Emitter.kt diffs). This file is a
curated narrative of the full timeline for convenience.

---

## Completed queue items (16.0, 16.1, 16.2, 16.3)

These four items are now DONE (or PARTIAL for 16.3) and removed from the live
queue. The original retrospective with design rationale, sub-step notes,
"Problem" descriptions, "Remaining sub-steps", "Entry points", "Files",
"Expected gain" and "Risk" estimates is below.

---

- [x] **16.0. Contextual typing infrastructure (HIGHEST PRIORITY — ~300 tests realistic) — DONE (+19 tests, 8055 passing)**

  **Session 2026-04-11 (16.0o, +1 test: 8054→8055):** Contextual typing propagation through `checkPropertyAccessInExpr` so un-annotated arrow function parameters in object literal call arguments are typed from the contextual signature. CallExpression→ObjectLiteralExpression→PropertyAssignment→ArrowFunction chain propagates `contextualType`; at ArrowFunction, contextual sig params populate `currentLocalTypes` for TS2339 checks in the body. Added apparent-type lookup in the local-fallback branch of `checkSinglePropertyAccess` so primitive-typed identifiers (e.g. `s: string`) resolve to the String wrapper interface for property-existence checks, with `displayTypeOverride` preserving the primitive name in the diagnostic. Tightened the number-index-signature bail-out to only skip non-numeric property names when we came through the primitive-apparent path (keeps `Array.isArray` static access working). Shadowed outer params in the FunctionExpression branch to prevent outer `(s: string)` leaking into inner un-annotated `function (s) {}`. → +1 test (contextualTypingOfObjectLiterals2).

  **Session 2026-04-11 progress:**
  - 16.0a: TS2353 excess property check for object literal call args (infra, 0 gains — guards too tight for most cases)
  - 16.0b: Contextual type propagation to arrow/function call args + return statements (infra, 0 gains — downstream identifier resolution doesn't consult param symbolTypes)
  - 16.0c: Array literal element TS2353 in var decl + class property init + assignment target → +4 tests (contextualTyping9, contextualTyping12, contextualTyping20, arrayLiteralTypeInference)
  - 16.0d: TS2353 union target constituent handling with display narrowing (inline union → pick constituent; type alias → preserve alias name) → +1 test (excessPropertyErrorForFunctionTypes)
  - 16.0e: Nested object literal recursion in checkExcessProperties (+0 standalone, but enables 16.0f gain)
  - 16.0f: typeToString optional property display for anonymous Object types (`name?: type | undefined`) → +1 test (nonObjectUnionNestedExcessPropertyCheck)
  - 16.0m: Generic class instance type param resolution for property-access assignments. NewExpression honors type arguments → Type.Reference. New `currentTypeParamScope` field + cache bypass in getTypeFromTypeNode. Narrow fix: resolveGenericPropertyType helper only fires in checkPropertyAccessAssignment (avoids FPs in recursive types like infinitelyExpandingTypeAssignability). → +1 test (divergentAccessorsTypes2).
  - 16.0n: Generic property access READING via resolveGenericPropertyType. Extended to MethodDeclaration (instantiated call signatures with substituted return/param types). Hooked into getTypeOfPropertyAccess for Type.Reference objects. Narrow guard: direct member OR all base-type args are pass-through TypeParams (rejects `class D<T> extends C<string>` which would need full chain walking). `getTypeFromBaseTypeExpression` now honors type arguments → Type.Reference. Heritage resolution iterates ALL declarations (interface merging) via resolveBaseTypesLazy, called eagerly in getDeclaredTypeOfClassOrInterface AND lazily in resolveInterfaceMembers if baseTypes is empty (picks up user's `interface Array<T> extends IFoo<T>` after built-in Array was cached at init). Index signature type resolution scoped to interface type params. PropertyAccessExpression excluded from TS2322 elaboration chain (matches TS behavior). → +6 tests (extendGenericArray, extendGenericArray2, indexIntoArraySubclass, genericGetter, genericGetter3, wrappedRecursiveGenericType).

  **Session 2026-04-11 continuation (investigation, +0 tests):** Explored remaining sub-steps; each requires substantial infrastructure beyond surgical fixes:
  - **Arrow param propagation to currentLocalTypes**: Implemented as populateArrowParamLocalTypes helper wrapping getTypeOfArrowFunction body walk. Net-zero — body walking in the TS2322/TS2345 pass doesn't re-enter getTypeOfArrowFunction; the populated scope only affects getTypeOfExpression walks on concise-body expressions whose type only feeds the resolvedReturnType field that's rarely compared. **Reverted** to avoid dead complexity.
  - **assignToFn / namespace-scoped variable resolution**: Needs a new currentNamespaceScope field threaded through getTypeFromTypeReference + getTypeOfIdentifier. `interface I` inside `namespace M` lives in M's `exports`, not globals or currentFileLocals, so `var x: I` inside M resolves I to errorType and `currentLocalTypes["x"]` is never populated. Significant infrastructure — deferred.
  - **contextualTyping11/33/39 etc.**: Every failing contextualTyping\* test needs multiple missing features simultaneously (return-type inference from function body, TS2352 on type assertions, TS2741 with related-info elaboration chains). None is a 1-change win.
  - **checkJsdocTypeTagOnExportAssignment\***: Needs JSDoc `/** @type {Foo} */` comment parsing + type-tag binding. Substantial parser+binder+checker work.

  **Remaining sub-steps deferred to later sessions:**
  - TS2353 call args: loosen guards (currently requires `hasTargetProps`, maybe allow interfaces)
  - Discriminant narrowing for union targets (unlocks discriminatedUnionErrorMessage, missingDiscriminants) — needs literal preservation in object literal prop types
  - Namespace-scoped variable resolution in checkPropertyAccessAssignment (unlocks assignToFn-style tests) — needs currentNamespaceScope field threaded through type resolution
  - JSDoc `@type` annotation parsing for .js file checks (unlocks checkJsdocTypeTagOnExportAssignment* family)

  **Problem:** When checking `foo([1, "a"])` where `foo` takes `number[]`, the checker must propagate the *expected* element type `number` down into each array literal element so `"a"` can be checked as `string` against `number` target. Currently, `getTypeOfExpression` is purely bottom-up — it computes the type of an expression in isolation without knowing what's expected.

  **TypeScript's model:** `checkExpressionWithContextualType(node, contextualType)`. The contextual type flows down through:
  - Variable declaration initializers: `let x: T = expr` → expr gets T as context
  - Function call arguments: `f(expr)` → expr gets param type as context
  - Return statements: `return expr` in annotated function → expr gets return type
  - Object literal properties: `{ p: expr }` in object-typed context → expr gets property type
  - Array literal elements: `[e1, e2]` in array-typed context → each element gets element type
  - Arrow function parameters: `(a, b) => expr` in function-typed context → a, b get param types
  - Ternary branches: `cond ? a : b` → both branches get outer context
  - JSX attribute values
  - Assignment expressions: `x = expr` → expr gets typeof(x)

  **Scope for initial implementation:**
  - Add `contextualType: Type?` stack parameter (or thread-local) to `getTypeOfExpression`
  - Implement contextual typing for: variable initializers, call arguments, return statements, object literal properties, array elements
  - Defer: JSX, generic argument inference, conditional types

  **Entry points in current code:**
  - `checkVarDeclAssignability` (line 26568) — already has target type; pass as context to `getTypeOfExpression(init)`
  - `checkCallExpressionTypes` (needs plumbing) — pass parameter type as context to each argument
  - `getTypeOfArrayLiteral`, `getTypeOfObjectLiteral` — check against contextual target element/property types

  **Files:** `Checker.kt`
  **Expected gain:** ~200-400 tests (touches TS2322, TS2345, TS2353, TS7006)
  **Risk:** HIGH — may cause FPs if target types are wrong or comparison is too eager
  **Estimated effort:** 2-3 sessions

---

- [x] **16.1. Deep structural comparison with error elaboration (HIGH — ~150 tests realistic) — DONE (+13 tests, 8065 passing)**

  **Session 2026-04-12 (16.1d-e, +6 tests: 8060→8066):**
  - TS2561: excess property spelling suggestion in `checkExcessProperties` — when excess property name is close to a target property (Damerau-Levenshtein), emit TS2561 "Did you mean to write 'X'?" instead of TS2353. → +1 test: `nestedFreshLiteral`.
  - TS2793: overload implementation related info — when TS2345 fires on an overloaded function/method call, find the implementation signature (body-having declaration) and add TS2793 "The call would have succeeded against this implementation..." as related info. Uses AST traversal to find sibling method declarations. → +1 test: `overloadErrorMatchesImplementationElaboaration`.
  - Pretty mode: blank line before related info block in pretty error baseline formatting. → +1 test: `prettyContextNotDebugAssertion`.
  - Parser: TS1109 "Expression expected" at EOF uses `prevTokenEnd` position instead of virtual next-line start. → +2 tests: `nestedUnaryExpressionHang`, `parseJsxElementInUnaryExpressionNoCrash2`, +1 side-effect fix.
  - TS1184: emit "Modifiers cannot appear here." alongside TS1042 for access modifiers on object literal members. → +1 test: `objectLiteralMemberWithModifiers1`.
  - INVESTIGATED but not fixed: TS2740 (multiple missing properties) — needs prototype chain resolution for correct property ordering. TS2552 in type position — scope filtering works but KNOWN_GLOBALS `Parameters` wins over local `Parameter` due to edit distance. TS18050→TS2365 for `3+null` — removing TS18050 leaves zero diagnostics since TS2365 not implemented.

  **Session 2026-04-12 (16.1c, +2 tests: 8058→8060):** @pretty error baseline formatting. ANSI-colored diagnostic header with source context (tabs→spaces, squiggle alignment), "Found N error(s)" summary footer. Standard summary omitted in pretty mode. → +2 tests: prettyFileWithErrorsAndTabs, multiLineContextDiagnosticWithPretty. `prettyContextNotDebugAssertion` still needs related info display fix in pretty header.

  **Session 2026-04-12 (16.1b, +1 test: 8057→8058):** Relation cache cycle-break invalidation — `relationUsedCycleBreak` flag tracks whether a comparison used any cycle assumptions. Speculative `true` results from cycle breaks are NOT cached (only `false` results and non-cyclic `true` results are cached). Prevents incorrect assignability in mutually recursive types (A↔C, B↔D). Leaf-preference elaboration: `getPropertyElaborationChain` collects all incompatible properties first, then prefers leaf mismatches (non-Object types) over recursive ones. Cycle detection via `state.elaborationStack` prevents infinite recursion in elaboration. → +1 test: `typeComparisonCaching`.

  **Session 2026-04-12 (16.1a, +2 tests: 8055→8057):**
  - `getPropertyElaborationChain(source, target, path)` — recursively compares Object→Object properties to find the deepest incompatible property path. Single-level uses "Types of property 'x' are incompatible." Nested uses "The types of 'x.y' are incompatible between these types."
  - Hooked into 4 TS2322 emission sites: `checkVarDeclAssignability`, `checkAssignmentExpression`, `checkPropertyInitAssignability`, `checkReturnAssignability`.
  - Index signature parameter name display: `typeToString` now extracts parameter name from `IndexSignature.parameters` declaration (was hardcoded `[x: string]`, now uses actual name like `[index: string]`).
  - +2 tests: `multiLineErrors` (nested property path elaboration A1→A2 via x.y), `stringIndexerAssignments1` (index signature param name + property elaboration).
  - Close misses: `typeComparisonCaching` gets correct first-error elaboration but misses second error (`c = d` where mutually recursive interfaces need relation cache invalidation). `deeplyNestedAssignabilityErrorsCombined` needs `typeof` prefix + function call paths in elaboration text.

  **Session 2026-04-13 (16.1f, +2 tests: 8063→8065):**
  - CJS computed property temp var hoisting: `var _a;` from computed property names now hoisted before `Object.defineProperty` in CommonJS modules. Uses `computedPropHoistNames` field to communicate between class transform and CJS transform. → +2 tests: `variableDeclarationDeclarationEmitUniqueSymbolPartialStatement`, `declarationEmitPrivateSymbolCausesVarDeclarationEmit2`.
  - Construct signature detection in `getTypeFromTypeLiteral`: `MethodDeclaration` with name "new" now correctly added to `constructSignatures` list (not as named property). Single-construct-signature types now display as `new () => T` (was `{ new: () => any; }`).
  - Numeric literal trailing comment preservation: parser captures trailing comments on `NumericLiteralNode` when followed by `.` (property access). Preserves `0 /* comment */.toString()` format. Guarded by Dot token to avoid stealing statement-trailing comments.
  - INVESTIGATED: typeof prefix (no tests pass alone), function return-type elaboration (needs method body inference), cross-line numeric literal comments (needs `.` token leading comment propagation), TS1005/TS1109 swap (22 tests, but known regression risk), multi-file ordering (medium effort, deferred).

  **Remaining sub-steps (DEFERRED — each needs significant infrastructure, 0 individual test gains):**
  - `typeof` prefix for class constructor types in type display
  - Function return-type elaboration path (e.g., `a.b.c.d.e.f().g`)
  - Union target type elaboration (strip null/undefined, narrow to object constituent)
  - Deeper TS2416 property-level elaboration for class method overrides

  **Problem:** Current TS2322 emits "Type X is not assignable to type Y" but doesn't emit the elaboration chain: "The types of 'x.y' are incompatible between these types." / "Type 'string' is not assignable to type 'number'." Tests like `multiLineErrors` and many TS2322 tests expect this chain.

  **Files:** `Checker.kt` — relation engine, TS2322 emission
  **Expected gain:** ~100-200 tests (many TS2322 tests have elaboration chains in baselines)
  **Risk:** MEDIUM — adds detail to existing diagnostics, not new checks
  **Estimated effort:** 1-2 sessions
  **Unblocks:** When paired with 16.0, most TS2322 "none produced" tests

---

- [x] **16.2. Overload resolution (HIGH — ~120 tests realistic) — DONE (+5 tests, 8070 passing)**

  **Session 2026-04-13 (16.2b, +1 test: 8069→8070):**
  - Arity filter: when only one overload matches by argument count, use single-signature TS2345 checking instead of TS2769 error. Avoids reporting "No overload matches" when there's only one viable candidate.
  - Excess argument check in `allArgumentsMatch`: signatures with fewer params than args (and no rest param) now correctly fail matching.
  - +1 test: functionOverloads27.
  - Remaining failing overload tests need: TS2394 (overload/impl compatibility), TS2554 (expected N args), generic type inference, rest param handling. All deferred.

  **Session 2026-04-13 (16.2a, +4 tests: 8065→8069):**
  - **Binder already merges Function+Function** — overload declarations were preserved correctly.
  - **Core fix**: `isSimpleCheckableType` guard removed from `allArgumentsMatch`, `getFirstArgumentError`, `getFirstFailingArgPosition` — these now check ALL types for overload resolution, not just primitives.
  - **Array element type comparison**: Added Array-specific comparison in `structuredTypeRelatedTo` — when both types are `Type.Reference(Array, ...)`, compare element types directly instead of full structural comparison (which passes trivially since Array methods resolve to anyType in built-in lib). Limited to Array only to avoid regressions from invariant comparison on other generic interfaces.
  - **Literal type widening in errors**: `getWidenedLiteralType` helper widens `true`→`boolean`, string literals→`string`, etc. for TS2769 error messages.
  - **TS2793 conditional**: Only emit "implementation would have succeeded" when the implementation signature actually matches the arguments (via `getImplementationSignature` + `allArgumentsMatch`).
  - **TS2793 position fix**: Points to function NAME (`foo`) not declaration start (`function`).
  - **TS6500 related info**: For property type mismatches in object literal args, emit "The expected type comes from property 'X' which is declared here on type 'Y'" pointing to the property declaration in the param type.
  - **TS2728 related info**: For missing property errors, emit "'X' is declared here." pointing to the missing property's declaration.
  - **Object literal position**: Squiggle the property NAME (not value) for property-level mismatches in overload errors.
  - **Generic overload guard**: Skip overload checking when signatures have type parameters (no generic type inference yet).
  - **MethodDeclaration typeParameters**: Added typeParameters to Signature creation for interface method overloads (was missing, causing the generic guard to fail).
  - +4 tests: functionOverloads2, functionOverloads40, functionOverloads41, overloadResolutionTest1.
  - Zero regressions (tested: instantiatedReturnTypeContravariance, objectLiteralParameterResolution both pass).
  
  **Remaining sub-steps (DEFERRED — need generic infrastructure):**
  - Generic type argument inference for overload resolution
  - Rest parameter handling in overload matching
  - Constructor overloads (TS2769 for `new` expressions)
  - Interface method overloads with type parameters

  **Problem:** 511 TS2769 "No overload matches this call" occurrences in "none produced" tests. Also needed for TS2349 ("This expression is not callable") and proper signature resolution when calling overloaded methods.

  **TypeScript's model:** Given a call `f(a1, a2, ...)` and a function type with multiple call signatures:
  1. Filter signatures by arity (fixed + rest params)
  2. For each candidate: check each argument against corresponding parameter type
  3. If any signature matches → return its return type
  4. If none match → emit TS2769 with elaborated list of attempted signatures

  **Requirements:**
  - Multi-signature call checking (`getReturnTypeOfCallExpression` currently uses `sigs[0]` only)
  - Signature compatibility scoring
  - TS2769 diagnostic with candidate list elaboration

  **Entry points:**
  - `getReturnTypeOfCallExpression` (line ~28036) — iterate signatures
  - `checkCallExpressionTypes` — use resolved signature for argument checks
  - Function symbols with multiple declarations (body-less overloads + impl)

  **Files:** `Checker.kt`
  **Expected gain:** ~80-150 tests
  **Risk:** MEDIUM — new check, requires care to avoid FPs from incomplete argument type resolution
  **Estimated effort:** 2 sessions
  **Dependency:** Works best AFTER 16.0 (contextual typing) — so argument types are resolved more often

---

- [x] **16.3. Control flow narrowing (MEDIUM — ~100 tests realistic) — PARTIAL (+14 tests, 8077 passing)**

  **Session 2026-04-16 (16.3b, +10 tests: 8067→8077):** Note: JDK 25 upgrade caused baseline shift from 8077→8067 (10 tests sensitive to JDK version). Surgical fix:
  - **TS1344 message fix**: Removed stray `'` from "A label is not allowed here." diagnostic message (was `"'A label..."`). This single-character fix flipped 10 tests that reference TS1344 in their error baselines (e.g., `sourceMapValidationLabeled`, various `labeledStatement` tests).
  - INVESTIGATED but not fixed: TS2741 fallback for cached relation results (propertiesRelatedTo not called on cached lookups → lastMissingPropertyName not set). Implemented fallback that re-runs propertiesRelatedTo, but net-zero: fixes like `elaboratedErrors` but regresses others because our `{}` type lacks Object.prototype members (valueOf, toString, etc.). TS2322→TS2741 code swap patterns (18 tests) blocked on Object.prototype property resolution.

  **Session 2026-04-13 (16.3a, +4 tests: 8073→8077):** Surgical fixes from close-to-passing test analysis:
  - **TS2793 implementation match check**: In arity-filtered single-overload path, only emit TS2793 "implementation would have succeeded" when `allArgumentsMatch(args, implSig)` is true. Previously always attached TS2793 when an overload had an implementation — now correctly checks whether the implementation param types accept the actual arguments. → +1 test: `functionOverloads`.
  - **TS2739/TS2740 multi-property missing**: When >=2 properties are missing from a type assignment, use TS2739 (2-4 missing) or TS2740 (5+ missing) instead of single-property TS2741. New `collectMissingProperties` helper iterates target's `.properties` list, filtering Object prototype methods (toString, valueOf, etc.) that all objects inherit. Applied in checkVarDeclAssignability, checkPropertyInitAssignability, checkAssignmentExpression, and TS2420 class-implements-interface. → +2 tests: `classWithMultipleBaseClasses`, `interfaceInheritance`.
  - **TS1184 accessor guard**: "Modifiers cannot appear here" only fires for MethodDeclaration in object literals, not for GetAccessor/SetAccessor (TypeScript only emits TS1042 for accessor modifiers). → +1 test: `objectLiteralMemberWithModifiers2`.
  - INVESTIGATED but not fixed: TS2345 primitive→class param (1 regression from namespace-qualified type resolution failure), `arrayAssignmentTest4` (count mismatch due to embedded Array having 4 extra ES2019+/ES2023 methods vs TypeScript's target-specific lib), TS1005/TS1109 parser error recovery (known risky area per CLAUDE.md).

  **Remaining sub-steps (DEFERRED — need significant infrastructure):**
  - Full flow graph construction for function/method bodies
  - Per-node narrowed type map for instanceof, typeof, in, discriminated unions
  - Definite assignment analysis: track which vars are assigned on all paths before use
  - All 11 failing controlFlow* tests need complex features (property-access narrowing, try-catch flow, nested body scanning)

  **Problem:** `if (x instanceof B) { x.foo() }` — our TS2339 check skips class-typed variables because without narrowing, we'd report false positives on valid code. Also blocks TS2454 definite assignment analysis and TS2774 ("forgot to use `await`?").

  **Files:** `Checker.kt` (major addition — flow analysis module)
  **Expected gain:** ~60-120 tests (full implementation)
  **Risk:** HIGH — narrowing interacts with all type queries; bugs cause wide regressions
  **Estimated effort:** 3-4 sessions (most complex item)
  **Dependency:** Independent of 16.0-16.2


---

## Archived 16.4 session notes (16.4a through 16.4ar)

Older session notes from item 16.4 "Generic type instantiation and inference."
Moved out of the live plan on 2026-04-17 because they are historical records
that never need to be re-read for current-session work. The live plan keeps
the ~10 most recent 16.4 sessions (16.4as through 16.4ba) for recent-context.

Ordering here is reverse chronological (most recent at top) matching the
live plan's convention.

---

  **Session 2026-04-17 (16.4ar, +1 test: 8129→8130):** TS1098 "Type parameter list cannot be empty." for `class C<> {}` / `class <>{}`:
  - Extended the empty-type-parameter-list check (previously only in `parseConstructor`) to `parseClassDeclaration` and `parseClassExpression`. After `parseTypeParametersOpt()`, if the returned list is empty, emit TS1098 at `ltPos` with length `scanner.getPrevTokenEnd() - ltPos` so the squiggle covers `<>`.
  - `ltPos` captured pre-call only when the current token is `<` (otherwise left as -1 to signal "no type param list attempted").
  - → +1 test: `classWithEmptyTypeParameter_ts`. Zero regressions.

  **Session 2026-04-17 (16.4aq, +1 test: 8128→8129):** TS18045 for `accessor` modifier on class property with target < ES2015:
  - New `checkAccessorModifierTarget` pass, early-return when `options.target >= ScriptTarget.ES2015`. Walks class/module statements tracking `inAmbient` (set by `declare` modifier on class or namespace). For any `PropertyDeclaration` in a non-ambient class with `ModifierFlag.Accessor`, emits TS18045 "Properties with the 'accessor' modifier are only available when targeting ECMAScript 2015 and higher." at the property name.
  - **Gotcha**: MUST use `options.target` not `options.effectiveTarget` for the comparison — `effectiveTarget` maps ES3/ES5 → ES2015, so the check would never fire. Adding to CLAUDE.md.
  - Factored out from `checkAbstractAccessorReturnTypes` (which is gated on `noImplicitAny/strict`), since the test doesn't enable those options.
  - → +1 test: `accessorInAmbientContextES5_ts__target_es5`. Zero regressions. The companion `target_es2015` JS emit test still fails for an unrelated reason (we don't emit the `__classPrivateFieldGet/Set` + WeakMap transform for `accessor` properties).

  **Session 2026-04-17 (16.4ap, +1 test: 8127→8128):** TS1259 + TS2594 for default import of `export =` module without esModuleInterop:
  - `checkDefaultImports` previously always emitted TS1192 "Module has no default export" when the default binding referenced a module lacking a `default` export. For modules declared with `export =`, TypeScript instead emits TS1259 "Module 'X' can only be default-imported using the 'esModuleInterop' flag" with a TS2594 related-info "This module is declared with 'export =', and can only be used with a default import when using the 'esModuleInterop' flag." pointing to the `export =` statement.
  - Gate: `hasExportEquals && !esModuleInteropActive` → emit TS1259 (not TS1192/TS2613). The existing carve-out `hasExportEquals && esModuleInteropActive` (suppress entirely) is preserved.
  - Related-info: `Diagnostic(category=Message, code=2594, fileName=resolvedFile, start=exportEqStmt.pos, length=1)`. The baseline formatter handles the `!!! related TS2594 ...` rendering.
  - → +1 test: `allowSyntheticDefaultImports6_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ao, +1 test: 8126→8127):** TS2417 for `class X extends null` with declaration-merged interface:
  - In `checkClassDerivedSuper`, the `extendsNull` branch already handled TS17005 (super-call ban). Added TS2417 "Class static side 'typeof X' incorrectly extends base class static side 'null'." fired ONLY when the class is declaration-merged with a same-name interface. Detection: `currentFileLocals[className].declarations.any { it is InterfaceDeclaration }`.
  - **Critical narrow gate**: without the merged-interface requirement, TypeScript suppresses this error — see `classExtendsNull.ts` / `classExtendsNull3.ts` baselines which emit only TS17005 / TS2531, no TS2417. Firing TS2417 unconditionally regresses those tests.
  - Added `classNameNode: Identifier?` parameter to `checkClassDerivedSuper`, passing `stmt.name` from ClassDeclaration and `init.name` from ClassExpression call sites. Set `currentFileLocals` around `walkForDerivedSuper` (with try/finally cleanup) so the merged-interface lookup has file-scoped symbol access.
  - → +1 test: `classExtendsNull2_ts`. Zero regressions across classExtendsNull/classExtendsNull3 variants.

  **Session 2026-04-17 (16.4an, +1 test: 8125→8126):** TS2882 FP suppression for bare side-effect imports resolvable via node_modules:
  - New helper `hasNodeModulesPackage(pkgName)` checks `fileResults.keys` for any path containing `/node_modules/<pkgName>/` (also accepts leading `node_modules/...`). TypeScript resolves bare specifiers like `import "A"` to `/node_modules/A/index.ts` via node_modules lookup; our simplified resolver doesn't, so TS2882 was emitted spuriously for such cases.
  - Guard added to the bare-specifier TS2882 branch alongside existing `ambientModuleNames`/`dtsFileBaseNames` checks. Doesn't affect the relative-path branch.
  - → +1 test: `moduleAugmentationInDependency2_ts`. Zero regressions. The companion JS-emit test still fails (we don't emit `/node_modules/A/index.js` alongside `app.js` — separate multi-file emit-all-files issue).

  **Session 2026-04-17 (16.4am, +2 tests: 8123→8125):** TS2709 for `import X = require(...)` used as type:
  - Previously `checkTypeRefForNamespace` skipped local aliases unconditionally. Now when a TypeReference's identifier resolves to an Alias symbol whose first declaration is an `ImportEqualsDeclaration` with `ExternalModuleReference` (i.e. `import X = require("mod")`), we resolve the target module via `resolveModuleSpecifier` and check whether its statements include an `ExportAssignment` with `isExportEquals`. If NOT, `X` is a namespace alias and using it as a type emits TS2709 "Cannot use namespace 'X' as a type." at the type name.
  - Also extended `checkNamespaceAsTypeInStmt` to recurse into `VariableStatement.initializer`, `ExpressionStatement`, `ReturnStatement`, `IfStatement`, `Block`, and into function/arrow bodies. Added `checkTypeRefsInExpr` that walks `ArrowFunction`/`FunctionExpression` parameter+return types, plus recurses through `CallExpression`/`ParenthesizedExpression`/`BinaryExpression`. Without this, `var x = (w1: WinJS) => { }` wouldn't be inspected because WinJS appears on an ArrowFunction parameter inside the initializer.
  - → +2 tests: `moduleInTypePosition1_ts` plus 1 collateral. Zero regressions.

  **Session 2026-04-17 (16.4al, +1 test: 8122→8123):** TS2576 FP suppression when class has BOTH instance and static member:
  - `checkMemberAccessMissing` TS2576 branch (16.4af) fired for `this.X` whenever the class had a static X, ignoring whether the class ALSO had an instance X. For `class T { constructor(private field: string) {} ; static field: number }`, `this.field` is valid (resolves to the parameter property), but we flagged it as a static-access typo.
  - New `hasInstanceMemberNamed(classDecl, name)` helper checks non-static `PropertyDeclaration`/`MethodDeclaration`/`GetAccessor`/`SetAccessor` AND `Constructor` parameter properties (parameters with any modifier). Walks the `extends` chain like `isStaticMemberOfClass`.
  - TS2576 now gated on `isStaticMemberOfClass && !hasInstanceMemberNamed`.
  - → +1 test: `classMemberInitializerWithLamdaScoping_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ak, +6 tests: 8116→8122):** TS2669 / TS2670 for `global {}` nested in regular namespace:
  - New `checkInvalidGlobalAugmentations` pass walks statements tracking `insideRegularNamespace` (true once entering a ModuleDeclaration with Identifier name that is NOT a `global` block).
  - Any `ModuleDeclaration` with `name.text == "global"` found inside a regular namespace emits TS2669 "Augmentations for the global scope can only be directly nested in external modules or ambient module declarations." at the `global` keyword (length 6).
  - Additionally, if the `global` block lacks a `declare` modifier, emit TS2670 "Augmentations for the global scope should have 'declare' modifier unless they appear in already ambient context." at the same position.
  - Ambient module declarations (`declare module "X" {}` with StringLiteralNode name) do NOT mark as "inside regular namespace" — they're allowed to host `global {}` augmentations.
  - → +6 tests (`moduleAugmentationGlobal8_ts` ts + target_es5 variants, plus collateral from similarly-structured tests). Zero regressions.

  **Session 2026-04-17 (16.4aj, +1 test: 8115→8116):** TS2833 "Cannot find namespace 'X'. Did you mean 'Y'?" for type-qualified names:
  - In `checkTypeReferenceName` QualifiedName branch, when the leftmost name IS in scope but the resolved symbol is NOT a `Module`/`NamespaceModule` (it's a variable/function/etc.), look up candidate namespaces (via new `collectNamespaceNames`) for a spelling suggestion. If one matches, emit TS2833 with TS2728 "declared here" related info — instead of falling through to TS2694 or silently passing.
  - Target: `var m: M = M; var q: m.P;` — `m` is a variable; `M` is a namespace. Previously silent, now emits `Cannot find namespace 'm'. Did you mean 'M'?` at `m`.
  - → +1 test: `primaryExpressionMods_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ai, +1 test: 8114→8115):** TS2339 fires on anonymous-object-typed variables:
  - `checkMemberAccessMissing` (non-this branch): the gate `if (typeSym == null || typeSym.flags.hasAny(SymbolFlags.Class)) return` skipped all anonymous-object types. Inverted: only skip when the type's symbol IS a class. Anonymous types (typeSym == null) — e.g. `declare var x: { a: string }` — are now checkable.
  - Rationale for the gate: class-typed variables may be narrowed via `instanceof`, so we historically skip them. But anonymous object types have fully-known members and never benefit from narrowing, so they're safe to check.
  - → +1 test (collateral; primary target `errorMessageOnObjectLiteralType_ts` still fails due to unrelated TS2728 related-info position issue for lib-declared symbols). Zero regressions.

  **Session 2026-04-17 (16.4ah, +1 test: 8113→8114):** TS7033 for bodyless get accessor without return type annotation:
  - New `checkAbstractAccessorReturnTypes` pass, gated on `noImplicitAny || strict`. Walks class bodies; for any `GetAccessor` whose `body == null` and `type == null` (abstract/interface form), emits TS7033 "Property 'X' implicitly has type 'any', because its get accessor lacks a return type annotation." at the accessor's name identifier.
  - Conservative: does NOT fire for getters with bodies (would need body return-type inference).
  - → +1 test: `noImplicitAnyMissingSetAccessor_ts__target_es5` (plus the base `target_es2015` was already passing). Zero regressions.

  **Session 2026-04-17 (16.4ag, +2 tests: 8111→8113):** TS1039 "Initializers are not allowed in ambient contexts" fires in `.d.ts` files:
  - `checkAmbientInitializers` previously skipped `.d.ts` files entirely. Now `.d.ts` files are processed with `isAmbient = true` at the top level, so declarations inside `.d.ts` (which are implicitly ambient even without `declare`) emit TS1039 for any initializer.
  - Covers tests like `var x = 1;` in a bare `.d.ts` file where TS1046 (missing declare/export) AND TS1039 (initializer) both fire.
  - → +2 tests: `missingRequiredDeclare_d_ts` + 1 collateral. Zero regressions.

  **Session 2026-04-17 (16.4af, +1 test: 8110→8111):** TS2576 for `this.X` where X is a static member of the enclosing class:
  - In `checkMemberAccessMissing`, when the resolved property `prop` is non-null AND the access is `this.X` in an instance method (`isThisAccess && !inStaticClassMethod`), re-check the declaration modifier via existing `isStaticMemberOfClass`. If the property is static, emit TS2576 "Property 'X' does not exist on type 'C'. Did you mean to access the static member 'C.X' instead?" and return, overriding the normal success path.
  - Rationale: `resolveInterfaceMembers` stores static and instance members in the same `members` table, so `getPropertyOfType` returns a static member as a hit for a `this.` access. The modifier check distinguishes.
  - → +1 test: `thisInOuterClassBody_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ae, +1 test: 8109→8110):** TS2370 "A rest parameter must be of an array type":
  - New `checkNonArrayRestParameters()` pass (always-on, not gated on strict/noImplicitAny) walks all function-like declarations and emits TS2370 when a rest parameter's type annotation is a clearly-non-array keyword (number, string, boolean, bigint, symbol, void, never, null, undefined). Conservative: does not resolve TypeReferences, so `function f(...x: Foo)` where `type Foo = number[]` stays silent.
  - Squiggle covers `...name: Type` — length computed as `typeNode.pos + keywordText.length - (name.pos - 3)`. Keyword text length is known from the SyntaxKind (no reliance on `node.end` which overshoots).
  - **FP suppression for downstream checks**: in both local-type populators (`checkTypeAssignabilityInStatements` param loop at 27112 and `populateParameterLocalTypes` at 33003), skip rest params whose annotation is non-array. Otherwise `function f(...rest: number) { rest[0] }` would also emit TS2339 "Property '0' does not exist on type 'number'", which TypeScript does NOT emit once TS2370 fires.
  - → +1 test: `nonArrayRestArgs_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ad, +1 test: 8108→8109):** TS2741 "required in type" uses declaring class for inherited properties:
  - `resolveInterfaceMembers` now sets `propSymbol.parent = symbol` (the class/interface Symbol) on newly-created member Symbols — PropertyDeclaration, MethodDeclaration (when not inherited override), Constructor parameter properties, GetAccessor, SetAccessor. Inherited members already point at their base's Symbol so they naturally retain the declaring-class parent.
  - New `getDeclaringTypeDisplay(propSymbol, targetType, fallback)` returns `propSymbol.parent.name` when the parent exists AND differs from the target type's symbol (inherited-property case). Otherwise returns the annotation-based `displayTarget`. Applied at all three TS2741 emission sites (var decl, property init, assignment expression).
  - Target: `c2 = c` with `class C2 extends A` and `private x` on `A` — TypeScript displays "required in type 'A'" (the declaring class), not "required in type 'C2'" (the assignment target). Previously we displayed 'C2'.
  - → +1 test: `classImplementsClass4_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ac, +1 test: 8107→8108):** TS1029 "'export' must precede 'default'" for bare `default` declaration:
  - Parser's `DefaultKeyword` top-level branch now emits TS1029 at the `default` keyword (length 7) BEFORE consuming it, then continues with the error-recovery parse of the following declaration.
  - Covers the decorated-class form (`@decorator \n default class {}`) which is the case expected to emit TS1029.
  - `defaultKeywordWithoutExport2` (`default function () {}`) still fails because TypeScript's baseline for non-decorated form uses TS1005+TS1003 instead of TS1029, but the test was already failing before this change — no regression.
  - → +1 test: `defaultKeywordWithoutExport1_ts`. Zero regressions.

  **Session 2026-04-17 (16.4ab, +1 test: 8106→8107):** TS1031/TS1039 also for `ClassExpression` members:
  - Extracted the class-member modifier check into `checkClassMemberModifiersForAmbient` so both `ClassDeclaration` and `ClassExpression` reuse the same logic.
  - Added `visitExprForClassExpression` that walks `VariableStatement` initializers (recurses through `ParenthesizedExpression` and `BinaryExpression`) and invokes the shared check on any `ClassExpression` encountered. Covers patterns like `const a = class Cat { declare [x]= 1; export foo = 1; }`.
  - → +1 test: `classExpressionPropertyModifiers_ts`. Zero regressions.

  **Session 2026-04-17 (16.4aa, +1 test: 8105→8106):** TS1031/TS1039 for class-member modifiers:
  - In `checkAmbientInitInStatements` ClassDeclaration branch: emit TS1039 "Initializers are not allowed in ambient contexts." when a `PropertyDeclaration` has an initializer AND either the class is ambient OR the property itself has `declare` modifier. Previously only checked class-level ambient.
  - Added TS1031 "'export' modifier cannot appear on class elements of this kind." for any `ClassElement` with `ModifierFlag.Export`. Squiggle on the `export` keyword (length 6), located via `getModifierKeywordStart` helper that scans source text from `element.pos`.
  - → +1 test: `illegalModifiersOnClassElements_ts`. Zero regressions.

  **Session 2026-04-17 (16.4z, +1 test: 8104→8105):** TS2407 for-in RHS type check (syntactic):
  - Extended `checkForInLhsInStmt` to also emit TS2407 "The right-hand side of a 'for...in' statement must be of type 'any', an object type or a type parameter, but here has type 'X'." for literal RHS expressions: NumericLiteralNode, BigIntLiteralNode, StringLiteralNode (wrapped in `"..."`), Identifier `true`/`false`/`null`/`undefined`, and PrefixUnaryExpression with `+`/`-` operator on a simple-display operand. Complex/unknown expressions are left alone (conservative).
  - Squiggle covers the literal's true end via `expressionTrueEnd`.
  - Gated on `inAmbient` flag threaded through recursion — skip inside `declare namespace` blocks because TypeScript emits TS1036 there and suppresses semantic diagnostics. Without this gate, `ambientWithStatements_ts` regresses (it has `for (x in null)` inside a `declare namespace`).
  - → +1 test: `forIn2_ts`. Zero regressions (flaky `binderBinaryExpressionStress_ts` toggled but stabilized on rerun).

  **Session 2026-04-17 (16.4y, +1 test: 8103→8104):** TS1046 for bare top-level declaration in `.d.ts`:
  - New `checkDtsTopLevelDeclarations` emits TS1046 "Top-level declarations in .d.ts files must start with either a 'declare' or 'export' modifier." on the FIRST top-level declaration statement when it lacks a `declare` or `export` modifier.
  - Scoped to only the FIRST statement per file — the TypeScript semantic is broader ("every bare declaration until module mode is established"), but our parser splits malformed constructs like `export as namespace Foo;` into a phantom `namespace Foo;` statement, and flagging every bare decl triggers FPs. Restricting to the first statement matches the baselines at hand without regressions.
  - Skips: `InterfaceDeclaration`, `TypeAliasDeclaration`, `ImportDeclaration`, `ExportDeclaration`, `ExportAssignment`, `ModuleDeclaration` whose name is a `StringLiteralNode` (`declare module "X"` form).
  - Squiggle on the declaration keyword: `namespace`/`module`/`function`/`class`/`enum`/`const`/`var`/`let` — looked up in source starting from `stmt.pos`.
  - → +1 test: `erasableSyntaxOnlyDeclaration_ts`. Zero regressions (a flaky JS emit test `binderBinaryExpressionStress_ts` toggled between runs but stabilized on the second).

  **Session 2026-04-17 (16.4x, +1 test: 8102→8103):** TS1084 for malformed `<reference>` triple-slash directive:
  - Extended `checkTripleSlashSelfReference` in the Parser to emit TS1084 "Invalid 'reference' directive syntax." when a `///`-prefixed line contains `<reference\b` but doesn't match a valid directive (attribute list with balanced quotes ending in `/>`).
  - Valid pattern allows MULTIPLE `attrName="value"` pairs (e.g. `<reference types="jquery" preserve="true" />`) — first regex version was too strict (single attribute only) and regressed `moduleSymbolMerging_ts` / `declarationFilesGeneratingTypeReferences_ts`. Fixed with `(?:\s+[A-Za-z-]+\s*=\s*(?:"[^"]*"|'[^']*'))+`.
  - Squiggle: whole trimmed directive text, starting at first non-whitespace char of the line.
  - → +1 test: `invalidReferenceSyntax1_ts`. Zero regressions.

  **Session 2026-04-17 (16.4w, +1 test: 8101→8102):** TS2405 for-in LHS type check:
  - New `checkForInLhsTypes` pass walks statements and emits TS2405 "The left-hand side of a 'for...in' statement must be of type 'string' or 'any'." when the initializer is a bare `Identifier` (not a VariableDeclarationList) whose resolved symbol has a value-declaration with an incompatible type annotation.
  - Compatible type nodes: `StringKeyword`, `AnyKeyword`, `UnknownKeyword`, union of compatibles, parenthesized wrapper. Unknown forms (type references, literal types) default to compatible — conservative to avoid FPs.
  - Lookup via `locals[name].valueDeclaration` (top-level scope only for now). Squiggle on the identifier. `.js`/`.jsx`/`.d.ts` files skipped.
  - → +1 test: `forInStatement7_ts`. Zero regressions.

  **Session 2026-04-17 (16.4v, +3 tests: 8098→8101):** TS1092/TS1098/TS2392 for constructor overload edge cases:
  - Parser (TS1092/TS1098): `parseConstructor` now inspects the `<...>` type parameter list that was previously silently consumed for error recovery. Emits TS1098 "Type parameter list cannot be empty." (squiggle = `<>` span) when empty, and ALWAYS emits TS1092 "Type parameters cannot appear on a constructor declaration." at position `<+1` with length 0 (matches TypeScript's zero-length span after the `<`).
  - Checker (TS2392): new `checkMultipleConstructorImpls` — when a class has 2+ `Constructor` elements with `body != null`, each implementation gets TS2392 "Multiple constructor implementations are not allowed." at the `constructor` keyword (squiggle length 11). Hooked into `checkOverloadsInStatements` alongside existing `checkMethodOverloadsInClass`.
  - → +3 tests (8098→8101): `parserConstructorDeclaration12_ts` (the prompted target, 8 duplicated constructors × 3 diagnostics) plus 2 collateral wins from TS2392 firing on other double-implementation class tests. Zero regressions.

  **Session 2026-04-16 (16.4u, +1 test: 8097→8098):** TS2538 for invalid index type in `T[K]`:
  - `checkUnresolvedInType` IndexedAccessType branch now calls `checkIndexTypeValidity(indexType, ...)` which emits TS2538 "Type 'X' cannot be used as an index type." for syntactically-invalid index type nodes: `TupleType`, `TypeLiteral`, `FunctionType`, `ConstructorType`, `ArrayType`. Display via `formatTypeForDisplay`, squiggle length = display length (for `[]` → 2, matching source text).
  - Kept conservative: only handles syntactic forms where the resulting type is clearly non-index-compatible. Does NOT try to resolve the indexType to a semantic `Type` and check assignability to `string | number | symbol` — that path has more moving parts and risks FPs with generic type params / keyof.
  - → +1 test: `anyIndexedAccessArrayNoException_ts`. Zero regressions.

  **Session 2026-04-16 (16.4t, +1 test: 8096→8097):** TS1003/TS1359/TS2503 for invalid `import X = <literal>` RHS:
  - Parser: in `parseImportDeclaration` after `=`, detect `NumericLiteral`/`BigIntLiteral`/`StringLiteral` → emit TS1003 "Identifier expected." (squiggle = full literal including quotes); detect `NullKeyword` → emit TS1359 "Identifier expected. 'null' is a reserved word…". In both cases produce a synthetic `Identifier` carrying the literal text/rawText so the existing Transformer path (`transformImportEqualsDeclaration`) still emits the bare expression statement (`5;`, `"s";`, `null;`).
  - Checker: `import r = undefined;` — `undefined` is a KNOWN_GLOBAL but a VALUE_ONLY alias target → emit TS2503. Extended the `ImportEqualsDeclaration` TS2503 check to fire when `name in VALUE_ONLY_GLOBALS || name == "undefined"`. Suppress TS2503 for parser-synthetic literal refs (string/null) so we don't double-report on top of TS1003/TS1359.
  - → +1 test: `aliasErrors_ts has expected errors matching aliasErrors_errors_txt`. Zero regressions.

  **Session 2026-04-16 (16.4s, +1 test: 8099→8100):** TS2341 for `super.X` when X is a private method:
  - `checkPrivateMemberAccess` handled `this.prop` and `C.prop` but returned early for `super.prop` (because `globals["super"]` is null). Added a `super` branch that walks the enclosing class's `baseTypes`, finds the property, and emits TS2341 when it's a private METHOD.
  - **Method guard is critical** — first attempt without the `decl is MethodDeclaration` check caused a regression on `superPropertyAccess_ts__target_es5` itself: `super.d2` (private data property) already emits TS2340 ("Only public and protected methods..."), and TypeScript doesn't double-report TS2341 for data properties. The rule appears to be: TS2340 fires for non-method super access, TS2341 fires only for private METHOD super access.
  - → +1 test: `superPropertyAccess_ts__target_es5`. Zero regressions.

  **Session 2026-04-16 (16.4r, +1 test: 8098→8099):** TS2497 for `import * as X from` against `export =` module in ESM output, gated on alias usage:
  - In `checkDefaultImports`, added a post-check that emits TS2497 when: (a) the output format is ESM (`isESModuleFormat`), (b) the target module has `export =`, (c) the import uses NamespaceImport binding, AND (d) the namespace alias is referenced as a value somewhere in the file.
  - First attempt without (d) caused a regression: `es6ExportAssignment2` has `import * as a from "./a"` but never uses `a`, and TypeScript omits TS2497 in that case. Added `isIdentifierReferencedAsValue` helper that walks top-level statements looking for the alias name in value-expression positions (Identifier, PropertyAccess base, Call/New, Binary/Unary operands, etc.).
  - Squiggle position: the module specifier StringLiteralNode, length = `moduleName.length + 2` (for quotes).
  - → +1 test: `es6ImportEqualsExportModuleEs2015Error`. Zero regressions.

  **Session 2026-04-16 (16.4q, +1 test: 8097→8098):** TS2693 for `typeof X` in type position where X is type-only:
  - `checkTypeQueryName` only delegated to `checkIdentifierResolved`, which returns early when `scope.has(name)` — so interfaces/type aliases referenced by `typeof` passed silently instead of emitting TS2693.
  - Added `isTypeOnlySymbolName(name, fileName)` helper that looks up the symbol in `fileResults[fileName].locals` or `globals`, returns true when the symbol has `Type` flag but no `Value` flag (and, for modules, no value exports).
  - When the typeof target is type-only, `emitTS2693` fires at the identifier position and the normal resolution path is skipped.
  - → +1 test: `typeofSimple`. Zero regressions.

  **Session 2026-04-16 (16.4p, +2 tests: 8095→8097):** TS1141 "String literal expected" in `export ... from` clauses:
  - `parseExportDeclaration` called `parseStringLiteral()` after `parseExpected(FromKeyword)` without verifying the next token was actually a string — any Identifier was silently accepted and treated as a string literal via `scanner.getTokenValue()`.
  - Added a `token != StringLiteral` guard before the two `parseStringLiteral()` call sites (`export * from X` and `export { ... } from X`). Emits TS1141 at the scanner token position with length = identifier length (via default `reportError` behavior).
  - → +2 tests: `exportDeclarationInInternalModule` plus one collateral win. Zero regressions.

  **Session 2026-04-16 (16.4o, +1 test: 8094→8095):** TS1214 for default-import reserved words:
  - In `walkStmtForStrictReserved` `ImportDeclaration` branch, also check `clause.name` (default import identifier) — not just `clause.namedBindings` (namespace/named imports). Without this, `import public from "./1"` passed through silently even though `public` is a strict-mode reserved word.
  - → +1 test: `strictModeWordInImportDeclaration`. Zero regressions.

  **Session 2026-04-16 (16.4n, +1 test: 8093→8094):** TS2664 "Invalid module name in augmentation" — tangential to generics, small wins:
  - A `declare module "X"` inside a MODULE file (has imports/exports) is an augmentation, not a definition. The augmented module must exist — either by resolving to a file via `resolveModuleSpecifier` or by another `declare module "X"` in a script (non-module) file or a .d.ts file.
  - New `checkAmbientModuleAugmentations()`: collects module-definition names from script and .d.ts files, then iterates each top-level `declare module "X"` in non-.d.ts module files. Emits TS2664 at `name.pos` with length `moduleName.length + 2` (for quotes) when X doesn't resolve.
  - Hooked into Checker init right after `checkUnresolvedModules` (step 14a).
  - → +1 test: `ambientExternalModuleInAnotherExternalModule`. Zero regressions. Conservative scope (skips inside .d.ts, skips nested `declare module` inside namespaces) keeps FP risk low across 165 tests that use `declare module "..."`.

  **Session 2026-04-16 (16.4m, +1 test: 8092→8093):** Override methods get fresh symbol to avoid contaminating base-class symbol:
  - In `resolveInterfaceMembers`, when inheriting members from base types, track `inheritedMemberNames`. When the MethodDeclaration branch encounters a name that was inherited, create a NEW `Symbol` instead of `members.getOrPut`. Otherwise `A.foo + C2 extends A { foo() {} }` ended up mutating A's foo Symbol (`declarations.add(C2's fooDecl)`), so TS2728 "'foo' is declared here" related info could resolve to either A's or C2's declaration unpredictably.
  - `createPropertyDeclaredHereRelatedInfo` still uses `.firstOrNull()`, but now the override symbol's declarations list contains ONLY the overriding declaration.
  - → +1 test: `classImplementsClass2`. Zero regressions.

  **Session 2026-04-16 (16.4l, +1 test: 8091→8092):** TS2720 "Class incorrectly implements class. Did you mean to extend…" for class-implements-class:
  - Previously `checkImplementsClauses` only ran for target symbols with the `Interface` flag; class targets were silently skipped (leaving `class B implements A` where `A` is a class with no diagnostic).
  - Now handles both: emits TS2720 for pure-class targets (with "Did you mean to extend…" hint) and TS2420 for interface targets. Selection: `isClassTarget = hasClass && !hasInterface` (merged class+interface still goes through TS2420).
  - Skip static-only members in the missing-property check for class targets (the instance-member table currently includes statics from the class resolver, so without this filter `class B implements A` spuriously reports A's static members as missing).
  - Also include members inherited via `extends` in `classMemberNames` — a class `D extends C implements C` should not be flagged for missing C's members (it inherits them via extends). Fixes regressions `extendAndImplementTheSameBaseType` and `implementClausePrecedingExtends`.
  - → +1 test: `classImplementsClass7`. Zero regressions. classImplementsClass2/4/5/6 still fail for unrelated reasons (TS2728 related-info line mismatch from method-override symbol contamination, class-instance TS2322 with private elaboration, TS2339/TS2576 static access).

  **Session 2026-04-16 (16.4k, +2 tests: 8089→8091):** `implements` clauses must NOT contribute members to a class's instance type, plus TS2420/TS2344 polish:
  - **Root cause fix**: `resolveBaseTypesLazy` was iterating ALL heritage clauses (both `extends` and `implements`), so a class `C implements I` ended up with I's members inherited as baseType members. This made `propertiesRelatedTo(C, I)` trivially true even when C didn't declare I's members, masking both TS2420 and TS2344-via-constraint failures. Fix: skip `clause.token == SyntaxKind.ImplementsKeyword` when collecting baseTypes.
  - **TS2420 display polish**: `checkImplementsClauses` now formats the interface name with its type arguments — e.g. `Comparable<string>` instead of bare `Comparable`. Uses `formatTypeForDisplay` on each `typeExpr.typeArguments`.
  - **TS2344 elaboration chain + TS2728 related info**: `checkCallTypeArgConstraints` now resets `lastMissingPropertyName`/`lastMissingPropertySymbol` before the relation check, and if a missing-property failure was recorded, emits `"  Property 'X' is missing in type 'A' but required in type 'B'."` as a messageChain line plus a TS2728 "'X' is declared here" related info pointing to the interface property.
  - → +2 tests: `genericConstraint2` (TS2420 with `Comparable<string>` + TS2344 on `compare<ComparableString>(a, b)`), `recursiveInheritance3` (TS2420 on `class C implements I` where I extends C). Zero regressions.

  **Session 2026-04-16 (16.4j, INVESTIGATED — reverted):** Cross-instance generic assignability (`Bar<string>` ↛ `Bar<number>`):
  - Target test: `genericCloneReturnTypes` — expects `TS2322: Type 'Bar<string>' is not assignable to type 'Bar<number>'. Type 'string' is not assignable to type 'number'.`
  - Diagnosis: properties `t: T` on `Bar<T>` resolve to `errorType` because `getTypeOfSymbol(prop)` → `getTypeFromTypeNode(T)` is called without `currentTypeParamScope` active. The errorType gets cached in `symbolTypes`, so subsequent instantiation via mapper is a no-op — comparison incorrectly succeeds.
  - Attempt 1: in `resolveReferenceMembers`, resolve prop type fresh from `decl.type` with target's type params pushed into scope. Result: `TS2322` fires for `genericCloneReturnTypes`, but elaboration format is wrong (emits "Types of property 't' are incompatible." intermediate line that TypeScript omits for single-type-arg references). Net: +1, but **+5 regressions** elsewhere (8088→8084) from leaking scope into downstream code paths.
  - Attempt 2: push scope only for the `getTypeOfSymbol(prop)` calls, keep the fresh-resolve approach reverted. Result: **+2 regressions** (8089→8087) — the scope push still affects caching in ways that break other tests.
  - Root cause of regressions: once `getTypeOfSymbol` caches an errorType for a prop (from an earlier call without scope), subsequent calls in any context return errorType. Retroactively fixing via scope push doesn't help — the cache is already wrong. And bypassing the cache (Attempt 1) loses consistency with all other call sites of `getTypeOfSymbol`.
  - **Deferred**: needs either (a) proper variance analysis so `structuredTypeRelatedTo` can directly compare `Type.Reference` type args like Array already does (see existing CLAUDE.md gotcha), or (b) a checker-wide convention that prop symbol type resolution always runs with the enclosing class's type params in scope (Checker init pre-resolves all class/interface prop types with scope active).
  - No commit made; working tree clean at 8089 / 10,078 after revert.

  **Session 2026-04-16 (16.4i, +1 test: 8087→8088):** TS2345 via constraint for un-instantiated generic parameters:
  - When a parameter's declared type is a `Type.TypeParam` with a simple primitive-checkable constraint (e.g. `y: U` where `U extends number`) and the argument type is primitive, check the arg against the constraint. On failure, emit TS2345 with the CONSTRAINT type as the displayed parameter type (not "U"), because the type param would be inferred as the arg type, which would itself fail the constraint check.
  - Placed before the general `isSimpleCheckableType(paramType)` guard in `checkArgumentsAgainstSignature`; always `continue` after handling so we don't also fall through to the generic TypeParam-as-param check.
  - Enabled by 16.4h which populates `Signature.typeParameters` with instantiated constraints — so `x.bar2(2, "")` on `bar2<U extends T>` with `x: C<number>` now sees paramType U with `constraint=number`.
  - → +1 test: `primitiveConstraints2`. Zero regressions.

  **Session 2026-04-16 (16.4h, +1 test: 8086→8087):** Method-level constraint checking with outer class type parameters:
  - `resolveGenericPropertyType` MethodDeclaration branch now populates the method's own `typeParameters` on the resulting Signature, with each constraint/default resolved in a scope containing both the class's and the method's type params, then instantiated via the class mapper.
  - Enables `x.bar2<string>` where `bar2<U extends T>` is defined on `C<T>` and `x: C<number>` → the method signature has `typeParameters=[U extends number]` so `checkCallTypeArgConstraints` can verify that `string` fails the `number` constraint and emits TS2344.
  - Constraint resolution runs inside a `try/finally` that restores `currentTypeParamScope` per-signature so sibling overloads see the original class-only scope.
  - → +1 test: `genericConstraint1`. Zero regressions.

  **Session 2026-04-16 (16.4g, +1 test: 8085→8086):** TS1477 instantiation expression followed by property access:
  - In the parser's call/access loop, the `LessThan` branch parses possible type arguments via `tryScan { tryParseTypeArguments() }`. When type args parse and the next token is `.` or `?.`, it's an instantiation expression (e.g., `f<number, string>.foo`) — TypeScript emits TS1477.
  - Captured `typeArgsStart = getPos()` BEFORE `scanner.tryScan` (we're at `<`) and `typeArgsEnd = scanner.getPrevTokenEnd()` INSIDE the lambda after `tryParseTypeArguments()` returns non-null (position right after `>`). Both flow out via closure capture.
  - In the existing `Dot, QuestionDot` match branch, call `reportError(..., code = 1477, overrideStart = typeArgsStart, overrideLength = typeArgsEnd - typeArgsStart)` so the squiggle covers the full `<TypeArgs>` span. The branch returns non-null so `tryScan` keeps state and the diagnostic persists.
  - → +1 test: `genericCallWithoutArgs`. Zero regressions.

  **Session 2026-04-16 (16.4f, +1 test: 8084→8085):** TS2552 spelling suggestions in type positions:
  - Added `forTypePosition: Boolean` parameter to `getSpellingSuggestion`. Removed the `!inTypePosition` guard so TS2552 now fires in type positions too.
  - Added `NameScope.typeNames` set + `addType(name)` helper; ClassDeclaration/InterfaceDeclaration/TypeAliasDeclaration/EnumDeclaration now populate it (replaces plain `names.add`). `buildNamespaceScope` marks type-eligible exports in the new set when merging namespace symbols.
  - In type-position mode, candidate set is: KNOWN_GLOBALS ∪ typeEligibleLocalNames (binder locals with Type flag) ∪ scope.typeParamNames ∪ scope.typeNames — MINUS VALUE_ONLY_GLOBALS (new set of pure runtime values like parseInt/console/Math). Primitive type keywords (`string`, `unknown`, etc.) intentionally NOT added — TypeScript's suggestion looks up symbols, and keywords have no symbol.
  - `findDeclarationRelatedInfo` now returns null for `TypeAliasDeclaration` (TypeScript omits TS2728 "declared here" for type-alias suggestions); searches nested namespace exports via new `findSymbolInNestedNamespaces` BFS helper to find classes inside `namespace M { class Foo {} }`.
  - → +1 test: `unspecializedConstraints` (nested class `Parameter` in namespace M now correctly suggested for `TypeParameter` typo, with proper TS2728 related info).

  **Session 2026-04-16 (16.4e, +1 test: 8083→8084):** Element access `obj["prop"]` / `obj[0]` TS2339/TS2551 check:
  - Refactored `checkSinglePropertyAccess` to extract shared `checkMemberAccessMissing` helper taking `objectExpr`, `propName`, `diagStart`, `diagLength`, plus an `emitTs2728RelatedInfo` flag (property access emits TS2728 "'X' is declared here" related info; element access does not — matches TypeScript's behavior).
  - Added `checkSingleElementAccess(expr: ElementAccessExpression, ...)` that extracts the literal key from `StringLiteralNode`/`NumericLiteralNode` argument expression and calls the shared helper. Squiggle span: for string literals, pos + `rawText.length + 2` (includes quotes); for numeric literals, pos + `text.length`.
  - Hooked into `checkPropertyAccessInExpr` ElementAccessExpression branch after recursing into sub-expressions.
  - → +1 test: `indexedAccessImplicitlyAny`. Most other bracket-access failures need additional infrastructure (TS7015 implicit-any index, TS7053 element type, generic property resolution).


  **Session 2026-04-16 (16.4a, +2 tests: 8077→8079):** Generic function call type argument instantiation:
  - **Explicit type argument support**: When CallExpression has explicit type args (e.g., `f<number, string>(...)`), finds matching generic signature, creates TypeMapper, and instantiates both return type and parameter types via `instantiateSignature`.
  - **Type parameter scope in `getTypeOfFunction`**: Set `currentTypeParamScope` when resolving function signature parameter/return types, so `T` in `T[]` resolves to the same `Type.TypeParam` objects as in the signature's type parameter list. Without this, `instantiateType` can't map type parameters.
  - **Parameter type eager resolution**: Resolve parameter types eagerly within the type param scope so `symbolTypes[param.id]` contains the correct `Type.Reference(Array, [TypeParam])` for later instantiation.
  - **Type argument count guard**: Only instantiate when type argument count matches type parameter count — prevents FP TS2322 on calls like `map<number>([1, ""])` where TS2558 should be the only error.
  - → +2 tests (mismatchedExplicitTypeParameterAndArgumentType + 1 other). Infrastructure enables future gains from broader type checking in argument positions.

  **Session 2026-04-16 (16.4b-c, +3 tests: 8079→8082):**
  - **TS2345 union elaboration**: When argument type is a union (e.g., `number | null`) not assignable to parameter type, add elaboration chain showing the failing constituent. → +1 test: `typePredicatesInUnion3`.
  - **TS2344 constraint checking for call expression type args**: `checkCallTypeArgConstraints` validates each explicit type arg against its (instantiated) constraint. Span uses `argDisplay.length` to avoid node.end overshoot. → +1 test: `primitiveConstraints1`.
  - **Qualified name type resolution**: `getTypeFromTypeReference` now uses `resolveTypeNameToSymbol(node.typeName)` for qualified names like `m1.c1`, falling back to `globals[name]`. Previously namespace-qualified type refs returned errorType.
  - **TS2345 broadened for primitive→class**: Allow TS2345 checking when arg is a primitive and param is a named class/interface (primitives are never structurally assignable to class instances). → +1 test: `functionCall7`.
  - **TypeLiteral display in `formatTypeForDisplay`**: Handles PropertyDeclaration (optional → `| undefined`), IndexSignature, MethodDeclaration. Fixes type display like `{ [key: string]: T[]; }` instead of `{ [key: string]: error[]; }`. → +2 tests: `indexerReturningTypeParameter1`, `strictSubtypeAndNarrowing`.
  - **JSDoc comment preservation on destructured exports**: `tryExpandObjectBinding` returns `Triple` with BindingElement `leadingComments`. Element comments take priority over statement comments. → +1 test: `declarationEmitRetainsJsdocyComments` (Transformer fix).
  - INVESTIGATED: Setting `currentTypeParamScope` in `checkFunctionBody` causes 19 regressions (type params resolving in too many contexts). Method-level TS2344 with outer class type params (e.g., `U extends T` where `T` from outer class) needs per-method scope management. TS2552 in type positions skipped by `!inTypePosition` guard — needs type-aware spelling candidate search.


---

*End of archived content. See `PLAN-PHASE-4.md` § 16.4 for live-queue session
notes and § "Known architectural blockers" for the current-state analysis.*
