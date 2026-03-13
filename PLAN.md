# Test Fix Plan — Phase 1 Remaining Work

**Current State:** 4509 tests, 150 failing (96.7% passing)

This document covers what remains before **Phase 2: type checker implementation**.
All history of completed fixes lives in the git log. Only actionable or blocked items are tracked here.

---

## QUEUE (execute top-to-bottom)

### Parser fixes (no type checker needed)

- [x] **1. `instanceof` on instantiation expression** (1 test: `instanceofOnInstantiationExpression`) — **File:** `Parser.kt` — **Fix:** added `InstanceOfKeyword` and `InKeyword` to `canFollowTypeArgumentsInExpression`; wrap erased instantiation expressions in `ParenthesizedExpression` except when followed by continuation tokens (`.`, `?.`, `)`, `]`).

- [S] **2. Internal comments on member access / element access** (2 tests: `propertyAccessExpressionInnerComments`, `elementAccessExpressionInternalComments`) — **File:** `Parser.kt` + `Emitter.kt` — **Fix:** add `beforeDotComments`/`afterDotComments` fields to `PropertyAccessExpression` (for `obj/*a*/./*b*/prop`), and `beforeBracketComments`/`afterBracketComments` to `ElementAccessExpression`. Parse them in the parser; emit them in the Emitter.

- [S] **3. Enum non-literal initializers** (2 tests: `enumNoInitializerFollowsNonLiteralInitializer`, `enumWithNonLiteralStringInitializer`) — **File:** `Transformer.kt` — **Fix:** when an enum member has a non-literal initializer (runtime expression), the subsequent member's auto-increment value cannot be computed at compile time. Emit the next member as `E[E["next"] = E["prev"] + 1] = "next"` or stop folding and carry the runtime value through subsequent members.

- [S] **4. `objectRestSpread` async destructuring** (1 test) — **File:** `Transformer.kt` — **Fix:** `async function` with destructuring parameter `{ ...rest }` needs temp var for the spread — the existing `__rest` logic must handle async function parameters with object rest.

- [S] **5. Static class fields edge cases** (~5 tests: `staticClassProps`, `staticFieldWithInterfaceContext`, `staticsInAFunction`, `staticsInConstructorBodies`, `classUpdateTests`) — **File:** `Transformer.kt` — **Fix:** various edge cases in static field hoisting (fields inside function bodies, interface-typed fields, constructor body interaction).

- [S] **6. `nestedObjectRest`** (1 test) — **File:** `Transformer.kt` — **Fix:** nested object destructuring with rest at multiple levels needs `__rest` applied at each nesting level.

- [S] **7. `dynamicImportWithNestedThis_es2015`** — needs `__createBinding`/`__importStar` helpers + CommonJS dynamic import transform.

- [S] **8. `moduleExportsUnaryExpression`** — complex CommonJS post-increment/decrement export tracking with temp vars.

- [S] **9. `superAccess2`** — needs temp var wrapping of extends expression for super access in constructor args.

- [S] **10. `nestedGlobalNamespaceInClass`** — parser error recovery (`global x` in class body).

- [S] **11. `destructuringAssignmentWithExportedName`** — complex CommonJS destructuring with exported name aliasing.

- [S] **12. `destructuringControlFlowNoCrash`** — parser error recovery (type annotation leaking into output).

- [S] **13. `parameterDecoratorsEmitCrash`** — needs `__esDecorate`/`__runInitializers` helpers.

- [S] **14. `importInsideModule`** — file ordering + import-equals inside module block needs type-checker-driven elision.

- [S] **15. `namespaceMergedWithImportAliasNoCrash`** — spurious `__importStar` helper emission on import alias.

- [S] **16. `emitMemberAccessExpression`** — multi-file output ordering (reference resolution order).

- [S] **17. `ambiguousGenericAssertion1`** — parser error recovery (generic-vs-assertion ambiguity).

- [S] **18. `assertInWrapSomeTypeParameter`** — blocked by `reScanGreaterToken` regression (method name becomes `>>`).

- [S] **19. `extendedUnicodePlaneIdentifiers`** — needs private field WeakMap transform (blocked).

- [S] **20. `reactReduxLikeDeferredInferenceAllowsAssignment`** — async function destructuring parameter transform.

- [S] **21. `TransportStream`** — scanner control character handling (binary input splitting into multiple statements).

- [S] **22. `exportDefaultAsyncFunction2`** — `async` parsed as keyword instead of imported identifier + missing `__awaiter`.

- [S] **23. `externalModuleAssignToVar`, `externalModuleRefernceResolutionOrderInImportDeclaration`, `externModule`** — file ordering + parser error recovery.

- [S] **24. `exportAssignmentImportMergeNoCrash`, `exportAssignmentOfDeclaredExternalModule`** — type-checker-driven import elision.

- [S] **25. `emitClassExpressionInDeclarationFile2`** — needs `__setFunctionName` helper.

### Module resolution (infrastructure work)

- [S] **26. `paths`/`baseUrl` resolution** (~9 tests) — requires implementing `compilerOptions.paths` and `baseUrl` in `TypeScriptCompiler.kt`.

- [S] **27. Symlink resolution** (3 tests) — needs symlink-aware path resolution infrastructure.

- [S] **28. `moduleResolutionWithRequire`** (1 test) — module resolution infrastructure.

- [S] **29. `requireOfJson*` remaining** (4 tests) — JSON file naming / ordering edge cases.

- [S] **30. `commonSourceDir6`** (1 test) — AMD module naming with rootDir path stripping.

- [S] **31. `moduleResolutionWithSuffixes` remaining** (3 tests) — module suffix resolution infrastructure.

---

## BLOCKED — do NOT attempt

These require infrastructure not present in Phase 1:

### Needs type checker
- **Import alias elision for non-instantiated modules** (~7 tests): `aliasOnMergedModuleInterface`, `duplicateVarsAcrossFileBoundaries`, `importedAliasesInTypePositions`, `importElisionEnum`, `typeUsedAsValueError2`, `exportDefaultImportedType`, `visibilityOfCrossModuleTypeUsage`, `importedEnumMemberMergedWithExportedAliasIsError`, `isolatedModulesExportImportUninstantiatedNamespace`
- **Const enum cross-file inlining** (~6 tests): `constEnums`, `constEnumExternalModule`, `constEnumNoEmitReexport`, `constEnumNamespaceReferenceCausesNoImport2`, `amdModuleConstEnumUsage`, `isolatedDeclarationErrorsEnums`, `isolatedModulesGlobalNamespacesAndEnums`
- **Decorator metadata with type info** (~3 tests): `decoratorMetadataNoLibIsolatedModulesTypes`, `decoratorMetadataTypeOnlyExport`, `metadataOfUnion`
- **Unused import detection** (~2 tests): `unusedImports_entireImportDeclaration`, `unusedLocalsAndParameters`
- **`isolatedModules` emit blocking** (~2 tests): `isolatedModulesExportDeclarationType`, `isolatedModulesNoEmitOnError`

### Needs `__generator` state machine (complex coroutine transform)
- `classDeclarationShouldBeOutOfScopeInComputedNames`, `classNameReferencesInStaticElements`, `es6ClassTest9` *(if async)*, and other tests requiring async-to-generator downlevel below ES2017

### Needs private field WeakMap transform
- (~4 tests): `constructorWithParameterPropertiesAndPrivateFields_es2015`, `privateFieldsInClassExpressionDeclaration`, `privateNameWeakMapCollision`, `propertyWrappedInTry`

### Parser error recovery — exact TypeScript heuristics required (~45 tests)
`arrowFunctionErrorSpan`, `arrowFunctionsMissingTokens`, `classMemberWithMissingIdentifier2`, `constructorWithIncompleteTypeAnnotation`, `errorRecoveryInClassDeclaration`, `errorRecoveryWithDotFollowedByNamespaceKeyword`, `es6ImportNamedImportParsingError`, `es6ImportParseErrors`, `fatarrowfunctionsErrors`, `fatarrowfunctionsOptionalArgs`, `fatarrowfunctionsOptionalArgsErrors2`, `for`, `genericsManyTypeParameters`, `importTypeWithUnparenthesizedGenericFunctionParsed`, `interfaceDeclaration4`, `invalidLetInForOfAndForIn_ES5/ES6`, `invalidUnicodeEscapeSequance/2/4`, `manyCompilerErrorsInTheTwoFiles`, `moduleElementsInWrongContext/2/3`, `numericLiteralsWithTrailingDecimalPoints01/02`, `overloadConsecutiveness`, `overloadingStaticFunctionsInFunctions`, `parseErrorIncorrectReturnToken`, `parseGenericArrowRatherThanLeftShift`, `parseInvalidNames`, `parseInvalidNullableTypes`, `parseJsxElementInUnaryExpressionNoCrash1/2/3`, `parseUnaryExpressionNoTypeAssertionInJsx4`, `parserPrivateIdentifierInArrayAssignment`, `parserUnparsedTokenCrash2`, `privacyImportParseErrors`, `reachabilityChecksNoCrash1`, `reservedWords2/3`, `restParamModifier`, `strictModeReservedWord`, `unclosedExportClause01/02`, `validRegexp`, `expressionsForbiddenInParameterInitializers`, `expressionWithJSDocTypeArguments`, `dontShowCompilerGeneratedMembers`, `bigintArbirtraryIdentifier`, `parseBigInt`, `jsFileCompilationTypeArgumentSyntaxOfCall`

### Out of scope
- **Declaration emit** (804 additional tests waiting) — requires type analysis; re-enable by removing `hasDtsSection` guard in `build.gradle.kts`
- **Inline sourcemaps** (~4 tests): `jsFileCompilationWithMapFileAsJsWithInlineSourceMap`, `optionsInlineSourceMapMapRoot/SourceRoot/Sourcemap`
- **`module: "preserve"`** (1 test): `modulePreserve1`
- **`outFile` AMD bundling** (~3 tests): `noBundledEmitFromNodeModules`, `requireOfJsonFileWithModuleNodeResolutionEmitAmdOutFile`, `useBeforeDeclaration`
- **Computed property name full hoisting** (1 test: `decoratorsOnComputedProperties` — 52 temp vars) — out of scope
- **SystemJS with type-re-export** (1 test: `systemModule17`) — needs type checker for interface re-exports

---

## Phase 2 enablers (after type checker)

Once a type checker is available, re-enable and fix:
1. Uncomment `.errors.txt` tests in `build.gradle.kts` — search `TODO: Re-enable when type checker is implemented`
2. Uncomment `.d.ts` declaration emit tests — search `hasDtsSection`
3. Fix all "Needs type checker" blocked items above
4. Re-enable `__generator` downlevel async transform
