# Test Fix Plan

**Current State:** 4508 tests, 383 failing (4,125 passing — 91.5%)

## QUEUE (execute top-to-bottom, do NOT re-order or skip ahead)

- [x] **1. parseCommaSeparatedNewline formatting** (3 tests) — already fixed
- [x] **2. Inline/trailing comments after type erasure** — fixed most: `promiseChaining`, `promiseChaining1` (preSemicolonComments on VariableStatement), `genericObjectSpreadResultInSwitch` (binding element leading comments). Remaining: `crashInGetTextOfComputedPropertyName` (2 sub-issues: trailing comment on binding element initializer + export {} ordering) — complex, skip.
- [x] **3. Parens + comments on type assertions** — already fixed
- [x] **4. Extra blank line fix** — already fixed
- [x] **5. `moduleProperty1` private keyword leak** — already fixed
- [x] **6. Single-line if/else not collapsed** (3 tests: `conditionalExpressions2`, `recursiveClassReferenceTest`, `functionOverloads12`) — FIXED: semi-inline function body format with `isFunctionBody` flag and `forceBlocksMultiLine` context.
- [ ] **7. Detached arrow function comments** (2 tests: `detachedCommentAtStartOfLambdaFunction1`, `detachedCommentAtStartOfLambdaFunction2`) — **File:** `Emitter.kt` — **Fix:** triple-slash XML doc comments before arrow function body not preserved.
- [ ] **8. `__rest` in assignment patterns** (~15 tests) — **File:** `Transformer.kt` — **Fix:** extend destructuring rest to handle assignment form (`[{ ...x }] = expr`), not just declarations.
- [ ] **9. CommonJS `exports.default = void 0` + re-export** (~19 tests) — **File:** `Transformer.kt` — **Fix:** missing `exports.default = void 0` initialization and re-export assignments.
- [ ] **10. Static class fields** (~5 tests) — **File:** `Transformer.kt` — **Fix:** transform `static x = 1` to `ClassName.x = 1` after class body.
- [ ] **11. Computed property name hoisting** (~10-20 tests) — **File:** `Transformer.kt` — **Fix:** extract computed property names to temp vars for class bodies.
- [ ] **12. Parser error recovery** (~50 tests) — **File:** `Parser.kt` — **Fix:** various error recovery differences (yield in type assertion, numeric trailing decimals, arrow misparse in generics, missing token recovery, tagged template incomplete expressions).

### Blocked / out of scope (do NOT attempt)
- `const enum` inlining — type checker needed
- `import = require()` elision — type checker needed
- Declaration emit (804 tests) — type analysis needed
- `outFile` bundling — significant infrastructure
- `__generator` helper — complex state machine transform
- Inline sourcemaps — not implemented
- Import alias for non-instantiated modules (5 tests) — type checker needed

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