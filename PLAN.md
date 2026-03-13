# Test Fix Plan — Phase 1 Remaining Work

**Current State:** 4509 tests, 150 failing (96.7% passing)

This document covers what remains before **Phase 2: type checker implementation**.
All history of completed fixes lives in the git log. Only actionable or blocked items are tracked here.

---

## QUEUE (execute top-to-bottom)

### Parser fixes (no type checker needed)

- [x] **1. `instanceof` on instantiation expression** (1 test: `instanceofOnInstantiationExpression`) — **File:** `Parser.kt` — **Fix:** added `InstanceOfKeyword` and `InKeyword` to `canFollowTypeArgumentsInExpression`; wrap erased instantiation expressions in `ParenthesizedExpression` except when followed by continuation tokens (`.`, `?.`, `)`, `]`).

- [ ] **2. Internal comments on member access / element access** (2 tests: `propertyAccessExpressionInnerComments`, `elementAccessExpressionInternalComments`) — **File:** `Parser.kt` + `Emitter.kt` — **Fix:** add `beforeDotComments`/`afterDotComments` fields to `PropertyAccessExpression` (for `obj/*a*/./*b*/prop`), and `beforeBracketComments`/`afterBracketComments` to `ElementAccessExpression`. Parse them in the parser; emit them in the Emitter.

- [ ] **3. Enum non-literal initializers** (2 tests: `enumNoInitializerFollowsNonLiteralInitializer`, `enumWithNonLiteralStringInitializer`) — **File:** `Transformer.kt` — **Fix:** when an enum member has a non-literal initializer (runtime expression), the subsequent member's auto-increment value cannot be computed at compile time. Emit the next member as `E[E["next"] = E["prev"] + 1] = "next"` or stop folding and carry the runtime value through subsequent members.

- [ ] **4. `objectRestSpread` async destructuring** (1 test) — **File:** `Transformer.kt` — **Fix:** `async function` with destructuring parameter `{ ...rest }` needs temp var for the spread — the existing `__rest` logic must handle async function parameters with object rest.

- [ ] **5. Static class fields edge cases** (~5 tests: `staticClassProps`, `staticFieldWithInterfaceContext`, `staticsInAFunction`, `staticsInConstructorBodies`, `classUpdateTests`) — **File:** `Transformer.kt` — **Fix:** various edge cases in static field hoisting (fields inside function bodies, interface-typed fields, constructor body interaction).

- [ ] **6. `nestedObjectRest`** (1 test) — **File:** `Transformer.kt` — **Fix:** nested object destructuring with rest at multiple levels needs `__rest` applied at each nesting level.

- [ ] **7. `dynamicImportWithNestedThis_es2015`** (1 test) — check diff, likely a `this` capture issue in dynamic import inside arrow functions.

- [ ] **8. `moduleExportsUnaryExpression`** (1 test) — check diff, likely CommonJS `module.exports = ~expr` or similar unary on exports.

- [ ] **9. `superAccess2`** (1 test) — check diff, likely a `super.prop` access inside a specific expression context.

- [ ] **10. `nestedGlobalNamespaceInClass`** (1 test) — check diff, likely a namespace declared inside a class body.

- [ ] **11. `destructuringAssignmentWithExportedName`** (1 test) — check diff.

- [ ] **12. `destructuringControlFlowNoCrash`** (1 test) — check diff.

- [ ] **13. `parameterDecoratorsEmitCrash`** (1 test) — check diff, likely a crash-recovery decorator emission case.

- [ ] **14. `importInsideModule`** (1 test) — check diff, likely an import-equals inside a module block.

- [ ] **15. `namespaceMergedWithImportAliasNoCrash`** (1 test) — check diff.

- [ ] **16. `emitMemberAccessExpression`** (1 test) — multi-file output ordering: `emitMemberAccessExpression_file1.js` is being emitted when it should not appear (it's a declaration-only file). Likely a file-skip condition in `TypeScriptCompiler.kt`.

- [ ] **17. `ambiguousGenericAssertion1`** (1 test) — check diff, likely a type-assertion-vs-generic ambiguity.

- [ ] **18. `assertInWrapSomeTypeParameter`** (1 test) — check diff.

- [ ] **19. `extendedUnicodePlaneIdentifiers`** (1 test) — check diff, likely extended Unicode chars in identifiers emit wrong.

- [ ] **20. `reactReduxLikeDeferredInferenceAllowsAssignment`** (1 test) — async function with destructuring parameter (separate from `objectRestSpread`).

- [ ] **21. `TransportStream`** (1 test) — check diff, likely a complex multi-file or async issue.

- [ ] **22. `exportDefaultAsyncFunction2`** (1 test) — check diff.

- [ ] **23. `externalModuleAssignToVar`, `externalModuleRefernceResolutionOrderInImportDeclaration`, `externModule`** (3 tests) — check diffs, likely CommonJS/module edge cases.

- [ ] **24. `exportAssignmentImportMergeNoCrash`, `exportAssignmentOfDeclaredExternalModule`** (2 tests) — check diffs.

- [ ] **25. `emitClassExpressionInDeclarationFile2`** (1 test) — check diff.

### Module resolution (infrastructure work)

- [ ] **26. `paths`/`baseUrl` resolution** (~9 tests: `pathMappingBasedModuleResolution3/4/5/8_classic`, `pathMappingBasedModuleResolution8_node`, `pathMappingBasedModuleResolution_rootImport_*`, `pathMappingBasedModuleResolution_withExtension_*`) — **File:** `TypeScriptCompiler.kt` — **Fix:** implement `compilerOptions.paths` and `baseUrl` when resolving relative imports for output path computation.

- [ ] **27. Symlink resolution** (3 tests: `moduleResolutionWithSymlinks`, `moduleResolutionWithSymlinks_notInNodeModules`, `moduleResolutionWithSymlinks_withOutDir`) — **File:** `TypeScriptCompiler.kt` — needs symlink-aware path resolution.

- [ ] **28. `moduleResolutionWithRequire`** (1 test) — check diff.

- [ ] **29. `requireOfJson*` remaining** (4 tests: `requireOfJsonFileNonRelativeWithoutExtensionResolvesToTs`, `requireOfJsonFileWithoutExtensionResolvesToTs`, `requireOfJsonFileWithoutResolveJsonModule`) — JSON file naming / ordering edge cases.

- [ ] **30. `commonSourceDir6`** (1 test) — AMD module naming with rootDir path stripping.

- [ ] **31. `moduleResolutionWithSuffixes` remaining** (3 tests: `dirModuleWithIndex`, `externalTSModule`, `jsonModule`) — module suffix resolution for directory index / external TS modules.

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
