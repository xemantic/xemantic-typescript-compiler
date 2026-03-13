# Test Fix Plan

**Current State:** 4508 tests, 200 failing (95.6% passing) — confirmed deterministic (5-run study)

## QUEUE (execute top-to-bottom, do NOT re-order or skip ahead)

- [x] **1. parseCommaSeparatedNewline formatting** (3 tests) — already fixed
- [x] **2. Inline/trailing comments after type erasure** — fixed most: `promiseChaining`, `promiseChaining1` (preSemicolonComments on VariableStatement), `genericObjectSpreadResultInSwitch` (binding element leading comments). Remaining: `crashInGetTextOfComputedPropertyName` (2 sub-issues: trailing comment on binding element initializer + export {} ordering) — complex, skip.
- [x] **3. Parens + comments on type assertions** — already fixed
- [x] **4. Extra blank line fix** — already fixed
- [x] **5. `moduleProperty1` private keyword leak** — already fixed
- [x] **6. Single-line if/else not collapsed** (3 tests: `conditionalExpressions2`, `recursiveClassReferenceTest`, `functionOverloads12`) — FIXED: semi-inline function body format with `isFunctionBody` flag and `forceBlocksMultiLine` context.
- [x] **7. Detached arrow function comments** (2 tests: `detachedCommentAtStartOfLambdaFunction1`, `detachedCommentAtStartOfLambdaFunction2`) — **File:** `Emitter.kt` — **Fix:** triple-slash XML doc comments before arrow function body not preserved.
- [x] **8. `__rest` in assignment patterns** (~15 tests) — **File:** `Transformer.kt` — **Fix:** extend destructuring rest to handle assignment form (`[{ ...x }] = expr`), not just declarations. Fixed: for-of with object rest var, parameter rest destructuring, nested object binding rest. Also added withFreshTempVarCounter for per-function temp var naming.
- [x] **9. CommonJS `exports.default = void 0` + re-export** (~19 tests) — **File:** `Transformer.kt` — **Fix:** Track import stmts by local name; defer re-export assignments to insert right after their import. Also added ImportDeclaration to topLevelRuntimeNames pre-scan. +2 tests.
- [x] **10. Static class fields** (~5 tests) — **File:** `Transformer.kt` — **Fix:** emit static blocks for ES2022+ with useDefineForClassFields=false. Other "static field" failures are parser error recovery issues. +1 test.
- [x] **11. Computed property name hoisting (partial)** — Fixed 3 tests: emit computed key (non-identifier/non-literal PropertyAccessExpression) as trailing statement when !useDefineForClassFields and no initializer. Also fixed index signature detection in parseClassMember using scanner.getToken(). Full temp-var hoisting is out of scope.
- [S] **12. Parser error recovery (wave 1)** — Attempted; most patterns require exact TypeScript recovery heuristics that are deeply intertwined. Skipped.
- [S] **13. Additional CommonJS fixes** — No safe fixes found without type checker.
- [S] **14. Look for more wins** — Exhaustively scanned all ~200 failing tests across two sessions. Found and committed small wins: `__metadata` helper, type-only empty namespace detection, `new <T>Expr` type-arg leading parse, class expression indent in non-multiLine arrays.

---

## NEW QUEUE — Wave 2 (execute top-to-bottom)

- [x] **15. `__makeTemplateObject` helper** (~2 tests: `templateLiteralEscapeSequence`, `templateLiteralIntersection4`) — **File:** `Transformer.kt` — **Fix:** implement `__makeTemplateObject` helper; transform tagged template expressions with invalid escape sequences. Fixed `templateLiteralEscapeSequence` (+1). `templateLiteralIntersection4` is a separate parser bug (mapped type `as` clause with template literal type misparses the `{[K in keyof Store as \`use${...}\`]` member).

- [S] **16. `noEmitOnError` option** (~2 tests: `isolatedModulesNoEmitOnError`, `jsFileCompilationEmitBlockedCorrectly`) — `isolatedModulesNoEmitOnError` needs type checker to detect type errors. `jsFileCompilationEmitBlockedCorrectly` needs JS file conflict detection (a.ts shouldn't emit to a.js when a.js source exists).

- [x] **17. Trailing comment on destructuring binding element** (~1 test: `unusedLocalsAndObjectSpread2`) — **Fix:** (1) Parser: after consuming `,` in `parseObjectBindingPattern`, capture `scanner.getTrailingComments()` and attach to preceding element. (2) Transformer: promote trailing comment of last non-rest element to the `VariableDeclaration`. (3) Emitter: in `emitObjectBindingPattern`, emit trailing line comment after `,` separator for non-last elements; in `emitVariableDeclarationList`, emit trailing line comment between declarators. +2 tests.

- [x] **18. SystemJS live export bindings** (~3 tests: `systemModule8`, `systemModule13`, `systemModule17`) — **File:** `Transformer.kt` — **Fix:** in `system` module format, assignments to exported variables must be wrapped: `x = 100` → `exports_1("x", x = 100)`. Currently the wrapping is absent, and the `_a` destructuring temp var for `exports_1` is not emitted. Fixed `systemModule8` and `systemModule13` (+2). `systemModule17` still fails: needs type checker (exported interface re-exports EI/EI1) and more complex var ordering logic.

- [x] **19. Class expression with decorator — hoisted temp var form** (~3 tests: `classExpressionWithDecorator1`, `decoratorsOnComputedProperties` + others) — **File:** `Parser.kt` + `Ast.kt` — **Fix:** parse `@decorator class C {}` as ClassExpression in expression position (added `At` case to `parsePrimaryExpression`, added `decorators` field to `ClassExpression`). The transformer already handles static field hoisting correctly; decorators on class expressions are silently dropped (TypeScript reports TS1206 for them). Fixed `classExpressionWithDecorator1` (+1). `decoratorsOnComputedProperties` still fails: needs 52 temp vars hoisted for computed property keys — out of scope.

- [x] **20. Internal / inline comments** (~4 tests: `elementAccessExpressionInternalComments`, `parenthesizedExpressionInternalComments`, `propertyAccessExpressionInnerComments`, `lambdaParameterWithTupleArgsHasCorrectAssignability`) — Fixed `lambdaParameterWithTupleArgsHasCorrectAssignability` (NewExpression.innerComments) and `parenthesizedExpressionInternalComments` (ParenthesizedExpression.beforeCloseParenComments + afterCloseParenComments). `elementAccessExpressionInternalComments` and `propertyAccessExpressionInnerComments` still fail — need more per-token comment fields. Net +2 tests.

- [x] **21. `importHelpers`/tslib support** (~1 test: `ctsFileInEsnextHelpers`) — **File:** `Transformer.kt` — **Fix:** when `importHelpers: true` + CJS, suppress inline `__awaiter` and inject `const tslib_1 = require("tslib")` instead; use `tslib_1.__awaiter(...)` in async transforms. Net +1 test.

- [S] **22. Parser error recovery — incremental** (~40 tests) — All sub-items 2-8 are complex TypeScript error recovery patterns requiring exact replication of TypeScript's parser recovery heuristics. Skipped.

- [S] **23. Private field WeakMap transform** (~4 tests) — `propertyWrappedInTry` is parser error recovery; others need complex WeakMap/WeakSet + `__classPrivateFieldGet/__classPrivateFieldSet` helpers + function-scope var hoisting. Skipped.

- [x] **24. Multi-file output ordering** (~20 tests) — **Fixed:** added `moduleSuffixes` support to `extractRelativeImports` (+5 tests: `moduleResolutionWithSuffixes_one`, `threeLastIsBlank1/2`, `oneNotFound`, `one_jsModule`). Fixed `fullEmitPaths` without outDir to preserve full path. Remaining: `pathMappingBasedModuleResolution*` (needs paths/baseUrl resolution), `requireOfJsonFile*` (file naming), `moduleResolutionWithSymlinks*` (symlink+outDir interaction), `moduleResolutionWithExtensions_notSupported` (need to check).

- [ ] **25. `__asyncGenerator` / `__await` helpers** (~2+ tests: `objectRestSpread`, `asyncImportNestedYield`) — **File:** `Transformer.kt` + `Emitter.kt` — **Fix:** implement `__await` and `__asyncGenerator` emit helpers for async generator functions (`async function*`). See `typescript-repo/src/compiler/factory/emitHelpers.ts`.

- [ ] **26. JSX parser + emit** (~23 tests: all `jsx*`, `tsx*`, `parseJsx*`, `parseUnaryExpressionNoTypeAssertionInJsx*`, `doubleUnderscoreReactNamespace`, `contextuallyTypedJsxAttribute`, `checkJsxNotSetError`, `quickIntersectionCheckCorrectlyCachesErrors`, `reactReduxLikeDeferredInferenceAllowsAssignment`) — **File:** `Parser.kt` + `Emitter.kt` — **Fix:** implement JSX parsing (scan `<` in expression position as JSX open element, parse JSX element/fragment/attribute syntax) and emit (transform JSX to `React.createElement` or factory calls).

- [ ] **27. `outFile` bundling** (~6 tests: `filesEmittingIntoSameOutputWithOutOption`, `jsFileCompilationNoErrorWithoutDeclarationsWithJsFileReferenceWithOut`, `requireOfJsonFileWithModuleNodeResolutionEmitAmdOutFile`, `noBundledEmitFromNodeModules`, `useBeforeDeclaration`, `commonSourceDir6`) — **File:** `TypeScriptCompiler.kt` — **Fix:** when `outFile` is set, concatenate all JS output into a single file in dependency order.

### Blocked / out of scope (do NOT attempt)
- `const enum` cross-file inlining — type checker needed (~15 tests: `constEnums`, `constEnumExternalModule`, `constEnumNoEmitReexport`, `amdModuleConstEnumUsage`, etc.)
- Import alias elision for non-instantiated modules — type checker needed (~10 tests: `typeUsedAsValueError2`, `aliasOnMergedModuleInterface`, `importedAliasesInTypePositions`, etc.)
- Declaration emit (804 tests) — type analysis needed
- Inline sourcemaps (~4 tests: `jsFileCompilationWithMapFileAsJsWithInlineSourceMap`, `optionsInlineSourceMapMapRoot/SourceRoot/Sourcemap`) — sourcemap infrastructure not implemented
- `module: "preserve"` (~1 test: `modulePreserve1`) — not implemented
- `__generator` state machine — complex coroutine transform needed for `async/await` downlevel

## Completed Fixes (chronological)

- [x] Deduplicate consecutive identical `@filename` directives in multi-file parser
- [x] `removeComments` pinned comments
- [x] `export {}` for type-only exports
- [x] Binary operator trailing comments
- [x] Yield expression trailing comments
- [x] AMD module format support (+241 tests)
- [x] CommonJS export ordering
- [x] System module + optional chaining + nullish coalescing + ternary formatting (+129 tests)
- [x] Enum folding (+9 tests)
- [x] JSDoc-in-args, param block comments, trailing expr comments (+8 tests)
- [x] Object.assign nested form, destructuring keys, namespace qualification, const spacing (+49 tests)
- [x] ClassExpression parens in extends clause
- [x] Operator trailing comments in inline binary expressions
- [x] Parameter trailing comments before type annotation erasure
- [x] Catch block multiline forcing
- [x] Iterative binary expression chain operator trailing comments
- [x] JSON file trailing comma stripping
- [x] `moduleDetection: force` support for `export {}` and `use strict`
- [x] `.mts/.mjs/.cts/.cjs` file extension module detection
- [x] BigInt binary/octal to decimal conversion
- [x] Correctness: triple-slash directives not preserved from elided import prePreambleStatements; `__metadata` for methods with only parameter decorators
- [x] `__metadata` helper implementation for `emitDecoratorMetadata` option
- [x] Correct type-only detection for empty namespaces and parameter-only decorator metadata
- [x] Parse and emit leading type args on `new` expressions (`new <T>Expr`)
- [x] Class expression closing brace indent in non-multiLine array literals (`isolatedDeclarationErrorsClassesExpressions`)

## Analysis Reference (for context only — DO NOT re-analyze, everything actionable is in QUEUE above)

### 1-line diff failures (21 tests)

**Import alias for non-instantiated modules (5 tests):**
`acceptableAlias1`, `duplicateVarsAcrossFileBoundaries`, `exportImportNonInstantiatedModule`, `moduleSharesNameWithImportDeclarationInsideIt3`, `typeofInternalModules`
— Requires type checker knowledge — **BLOCKED**.

**Inline sourcemap (3 tests):**
`jsFileCompilationWithMapFileAsJsWithInlineSourceMap`, `optionsInlineSourceMapMapRoot`, `optionsInlineSourceMapSourceRoot`
— Out of scope.

**Parser error recovery (4 tests):**
`classMemberWithMissingIdentifier`, `restParamModifier`, `unexpectedStatementBlockTerminator`, `importTypeWithUnparenthesizedGenericFunctionParsed`

**Extra blank line (1 test):**
`isolatedModules_resolveJsonModule_strict_outDir_commonJs`

**Access modifier leaking (1 test):**
`moduleProperty1` — `private;` emitted as identifier instead of being erased.

### Small-diff groups by root cause

**Inline/trailing comments dropped after type erasure (~10 tests):**
`commentsArgumentsOfCallExpression2`, `chainedSpecializationToObjectTypeLiteral`, `promiseChaining`, `promiseChaining1`, `overEagerReturnTypeSpecialization`, `narrowedConstInMethod`, `genericChainedCalls`, `privateName`, `genericObjectSpreadResultInSwitch`, `crashInGetTextOfComputedPropertyName`
— Comments survive in source but lost during emit.

**parseCommaSeparatedNewline (3 tests):**
`parseCommaSeparatedNewlineString`, `parseCommaSeparatedNewlineNew`, `parseCommaSeparatedNewlineNumber`
— `(a,\n    '')` should be `(a, '')`.

**Single-line if/else not collapsed (3 tests):**
`conditionalExpressions2`, `recursiveClassReferenceTest`, `functionOverloads12`

**Parens + comments on type assertions (2 tests):**
`commentEmitOnParenthesizedAssertionInReturnStatement`, `commentEmitOnParenthesizedAssertionInReturnStatement2`

**Detached arrow function comments (2 tests):**
`detachedCommentAtStartOfLambdaFunction1`, `detachedCommentAtStartOfLambdaFunction2`

### Remaining failure categories

- Missing/incomplete emit helpers (~60 tests) — `__rest` assignments, `__generator`, `__decorate` metadata, `__importDefault`/`__importStar` edge cases
- Parser error recovery (~50 tests) — yield in type assertion, numeric trailing decimals, arrow misparse, tagged templates
- Module transform edge cases (~30 tests) — `outFile` bundling, CommonJS init, module ordering
- Type-checker-driven transforms (~30 tests) — **BLOCKED**
- Internal comments (~15 tests) — per-token comment tracking
- Other (~50+ tests) — static fields, comment preservation, Unicode/BOM, multi-file ordering, SystemJS, computed property hoisting