# Test Fix Plan

**Current State:** 4480 tests, 395 failing (4,085 passing — 91.2%)

## Remaining Failure Categories (ranked by estimated count)

### 1. Missing/incomplete emit helpers (~60 tests) — HIGH COMPLEXITY
- `__rest` in destructuring assignments (~15) — declaration form works, assignment form (`[{ ...x }] = expr`) does not
- `__awaiter`/`__generator` (~12) — `__generator` helper not implemented
- `__decorate` metadata edge cases (~15) — `__decorate` exists but `__metadata`, decorator on computed properties, etc.
- `__importDefault`/`__importStar` (~19) — helpers already emitted, but CommonJS export edge cases (missing `exports.default = void 0`, re-export assignments)
- `__setFunctionName`, `__createBinding`, `__exportStar`, `__classPrivateFieldGet/Set` — various counts

### 2. Parser error recovery (~50 tests) — MEDIUM-HIGH COMPLEXITY
TypeScript's parser produces specific error recovery output. Our parser differs in:
- `yield` in type assertion context
- Numeric literals with trailing decimals (`2.toString()`)
- Arrow function misparse in generics
- Missing token recovery
- Tagged template incomplete expressions

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

### Potentially tractable (5-20 tests each, complex)
1. **`__rest` in assignment patterns** — extend destructuring rest to handle assignments, not just declarations
2. **CommonJS `exports.default = void 0` + re-export** — fix export initialization and re-export handling
3. **Static class fields** — transform `static x = 1` to `ClassName.x = 1` after class body
4. **Internal comment AST fields** — add `preDotComments`/`postDotComments` to `PropertyAccessExpression` etc.

### Blocked / out of scope
- `const enum` inlining — type checker needed
- `import = require()` elision — type checker needed
- Declaration emit (804 tests) — type analysis needed
- `outFile` bundling — significant infrastructure
- `__generator` helper — complex state machine transform
