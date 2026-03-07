# Test Fix Plan

**Baseline:** 4480 tests, 543 failing (after duplicate `@filename` fix)

## Root Cause Categories (ranked by estimated impact)

### 1. Missing emit helpers (~80-100 tests) — HIGH COMPLEXITY
The compiler does not emit TypeScript helper functions injected at file top:
- `__decorate` (decorator support) — ~20 tests
- `__awaiter` (async/await downlevel) — ~15 tests
- `__classPrivateFieldGet/Set` (private fields) — ~15 tests
- `__importDefault/__importStar` (esModuleInterop) — ~15 tests
- `__createBinding/__exportStar` (export * re-exports) — ~10 tests
- `__rest` (object rest destructuring) — ~5 tests

These are large feature gaps requiring whole subsystems to be implemented.

### 2. `export` inside namespace not transformed to property assignment (~15-20 tests) — MEDIUM
`export var x = 1` inside a `namespace` should become `M.x = 1`. The compiler leaves the `export` keyword in the output instead.

### 3. `const enum` inlining (~15-20 tests) — BLOCKED
Requires type checker to know enum member values. Cannot fix until type checker is implemented.

### 4. `import = require()` elision (~15-20 tests) — BLOCKED
Determining whether `import X = require("mod")` is type-only requires the type checker.

### 5. Constructor parameter properties / class member transforms (~10-15 tests) — MEDIUM
- `public a: number` in constructor params should emit `this.a = a;` — currently drops parameter entirely
- `static f = 3` leaks as `static; f = 3;` instead of being hoisted to `ClassName.f = 3;` after class
- `public`/`private` keywords on constructor params leak as identifier expressions

### 6. Comment emission issues (~24+ tests) — MEDIUM
Multiple sub-bugs in Emitter.kt:
- Comments inside parens dropped: `( /* Preserve */j = f())` → `(j = f())`
- Leading comments on call args produce extra blank lines/unwanted newline breaks
- Missing newline after block comments with `hasTrailingNewLine`
- Comments in switch cases dropped

### 7. Type assertion parentheses (~14 tests) — LOW COMPLEXITY
- `(<any>new a)` → `(new a)` instead of `new a` (unnecessary parens on NewExpression)
- Comments inside type-assertion parens lost when parens stripped
- `castFunctionExpressionShouldBeParenthesized`: paren placement differs from TypeScript output

### 8. AMD module format (~15-20 tests) — MEDIUM
- Missing module name strings for named AMD modules
- Import path resolution using relative instead of resolved paths

### 9. Parser error recovery edge cases (~15-20 tests) — MEDIUM
- `<<T>(x: T) => T>f` misparse as arrow function
- `yield` treated as YieldExpression in type assertion context instead of identifier
- Arrow function expression statements split at `=>`
- `bigint` property names not parsed
- Error recovery for missing tokens

### 10. SystemJS variable hoisting (~10 tests) — MEDIUM
Variables in SystemJS modules not hoisted to top of `execute` function.

### 11. Multi-file baseline formatting (~10-15 tests) — LOW-MEDIUM
- Duplicate file headers (FIXED: +1 test)
- Wrong file ordering
- Missing output file sections

### 12. Misc issues (~10-15 tests) — LOW
- UTF-16 BOM handling (`bom-utf16be`, `bom-utf16le`)
- `export default` unnamed classes missing synthetic name
- CommonJS destructuring with exported names
- Binary expression stack overflow (`binderBinaryExpressionStress`)

## Recommended Fix Order (highest ROI first)

### Wave 1 — Quick wins touching different files
1. **Type assertion parens** (Transformer.kt) — fix `typeAssertionResultNeedsParens` for NewExpression
2. **Comment emission** (Emitter.kt) — fix comment-inside-parens, hasTrailingNewLine newline

### Wave 2 — Class/constructor transforms
3. **Constructor parameter properties** (Transformer.kt) — fix `public`/`private` keyword leaking
4. **Static class field transforms** (Transformer.kt) — hoist static fields outside class

### Wave 3 — Namespace/module transforms
5. **`export` inside namespace** (Transformer.kt) — transform to property assignment
6. **AMD named modules** (Transformer.kt) — add module name to `define()` call

### Wave 4 — Larger features
7. **`__decorate` helper** (Transformer.kt) — implement decorator downlevel transform
8. **`__importDefault/__importStar`** (Transformer.kt) — implement esModuleInterop helpers
9. **Binary expression iterative emit** (Emitter.kt) — prevent stack overflow

### Deferred (blocked on type checker)
- `const enum` inlining
- `import = require()` type-only elision

## Completed Fixes

- [x] Deduplicate consecutive identical `@filename` directives in multi-file parser (+1 test)
