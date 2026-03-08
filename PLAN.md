# Test Fix Plan

**Current State:** 4480 tests, 395 failing (4,085 passing — 91.2%)

## Smallest-Diff Failures (best ROI targets)

### 1-line diff failures (21 tests)
Most actionable group. Common root causes:

**Import alias for non-instantiated modules (5 tests):**
`acceptableAlias1`, `duplicateVarsAcrossFileBoundaries`, `exportImportNonInstantiatedModule`, `moduleSharesNameWithImportDeclarationInsideIt3`, `typeofInternalModules`
— Transformer emits/omits `var x = M.Y` incorrectly for import aliases based on whether the target module is instantiated. Requires type checker knowledge — **BLOCKED**.

**Inline sourcemap (3 tests):**
`jsFileCompilationWithMapFileAsJsWithInlineSourceMap`, `optionsInlineSourceMapMapRoot`, `optionsInlineSourceMapSourceRoot`
— Missing inline sourcemap comment generation (`//# sourceMappingURL=data:...`). Out of scope.

**Parser error recovery (4 tests):**
`classMemberWithMissingIdentifier`, `restParamModifier`, `unexpectedStatementBlockTerminator`, `importTypeWithUnparenthesizedGenericFunctionParsed`

**Extra blank line (1 test):**
`isolatedModules_resolveJsonModule_strict_outDir_commonJs` — likely trivial formatting fix.

**Access modifier leaking (1 test):**
`moduleProperty1` — `private;` emitted as identifier instead of being erased.

### Small-diff groups by root cause (from agent analysis)

**Inline/trailing comments dropped after type erasure (~10 tests):**
`commentsArgumentsOfCallExpression2`, `chainedSpecializationToObjectTypeLiteral`, `promiseChaining`, `promiseChaining1`, `overEagerReturnTypeSpecialization`, `narrowedConstInMethod`, `genericChainedCalls`, `privateName`, `genericObjectSpreadResultInSwitch`, `crashInGetTextOfComputedPropertyName`
— Fix area: `Emitter.kt` trailing comment emission. Comments survive in source but lost during emit.

**parseCommaSeparatedNewline (3 tests):**
`parseCommaSeparatedNewlineString`, `parseCommaSeparatedNewlineNew`, `parseCommaSeparatedNewlineNumber`
— `(a,\n    '')` should be `(a, '')`. Call expression argument list single-line detection.

**Single-line if/else not collapsed (3 tests):**
`conditionalExpressions2`, `recursiveClassReferenceTest`, `functionOverloads12`
— Emitter not detecting single-line context for `if/else` inside single-line function bodies.

**Parens + comments on type assertions (2 tests):**
`commentEmitOnParenthesizedAssertionInReturnStatement`, `commentEmitOnParenthesizedAssertionInReturnStatement2`
— When dropping `ParenthesizedExpression` wrapping type assertion, comments inside should be preserved.

**Detached arrow function comments (2 tests):**
`detachedCommentAtStartOfLambdaFunction1`, `detachedCommentAtStartOfLambdaFunction2`
— Triple-slash XML doc comments before arrow function body not preserved.

## Remaining Failure Categories (ranked by estimated count)

### 1. Missing/incomplete emit helpers (~60 tests) — HIGH COMPLEXITY
- `__rest` in destructuring assignments (~15) — declaration form works, assignment form (`[{ ...x }] = expr`) does not
- `__awaiter`/`__generator` (~12) — `__generator` helper not implemented
- `__decorate` metadata edge cases (~15) — `__decorate` exists but `__metadata`, decorator on computed properties, etc.
- `__importDefault`/`__importStar` (~19) — helpers already emitted, but CommonJS export edge cases (missing `exports.default = void 0`, re-export assignments)
- `__esDecorate`/`__setFunctionName` — modern decorator helpers, not implemented
- `__createBinding`/`__exportStar` — CommonJS re-export helpers

### 2. Parser error recovery (~50 tests) — MEDIUM-HIGH COMPLEXITY
TypeScript's parser produces specific error recovery output. Our parser differs in:
- `yield` in type assertion context
- Numeric literals with trailing decimals (`2.toString()`)
- Arrow function misparse in generics (`<T = undefined>`)
- Missing token recovery
- Tagged template incomplete expressions (~6 tests)

### 3. Module transform edge cases (~30 tests) — MEDIUM
- `outFile` AMD bundling with named modules (~27) — requires bundle mode
- CommonJS `exports.default = void 0` initialization
- Module file ordering (topological sort)
- `modulePreserve` mode handling

### 4. Type-checker-driven transforms (~30 tests) — BLOCKED
- Destructuring downlevel (e.g. `let { toString } = 1` → `toString = 1..toString`) — needs type info
- Import alias elision (`import x = M.N` when N is non-instantiated) — needs type checker
- `const enum` cross-file inlining (~15 tests)

### 5. Internal comments (~15 tests) — HIGH COMPLEXITY
Tests like `propertyAccessExpressionInnerComments`, `elementAccessExpressionInternalComments` need per-token comment tracking (`preDotComments`, `postDotComments`, label comments, etc.)

### 6. Other (~50+ tests)
- Static field transform (`static x = 1` → `C.x = 1`) — ~5 tests
- Comment preservation edge cases (orphaned comments, triple-slash references) — ~10 tests
- Unicode/BOM encoding issues — ~3 tests
- Multi-file ordering — ~10 tests
- SystemJS variable hoisting — ~10 tests
- Computed property name hoisting to temp vars — ~10-20 tests

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

## Recommended Next Steps (diminishing returns)

### Easiest wins (3-10 tests each)
1. **parseCommaSeparatedNewline formatting** (3 tests) — call expression argument list single-line detection in Emitter.kt
2. **Inline/trailing comments after type erasure** (~10 tests) — comment transfer in Emitter.kt
3. **Parens + comments on type assertions** (2 tests) — keep parens when inner has comments in Transformer.kt
4. **Extra blank line fix** (1 test) — `isolatedModules_resolveJsonModule_strict_outDir_commonJs`
5. **`moduleProperty1` private keyword leak** (1 test) — access modifier not being erased

### Medium complexity (5-20 tests each)
6. **`__rest` in assignment patterns** — extend destructuring rest to handle assignments, not just declarations
7. **CommonJS `exports.default = void 0` + re-export** — fix export initialization and re-export handling
8. **Static class fields** — transform `static x = 1` to `ClassName.x = 1` after class body
9. **Computed property name hoisting** — extract to temp vars for class bodies

### Blocked / out of scope
- `const enum` inlining — type checker needed
- `import = require()` elision — type checker needed
- Declaration emit (804 tests) — type analysis needed
- `outFile` bundling — significant infrastructure
- `__generator` helper — complex state machine transform
- Inline sourcemaps — not implemented
