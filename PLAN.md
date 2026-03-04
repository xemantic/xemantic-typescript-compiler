# TypeScript-to-JavaScript Transpiler Implementation Plan

## Current Status (2026-03-04)

**Test results**: 3,190 / 8,627 passing (37.0%)

Previous: 2,957 / 8,627 passing (34.3%)

## Test Suite Structure & Realistic Ceiling

The 8,627 tests split into two fundamentally different categories:

| Category | Count | Requires |
|---|---|---|
| JS emit tests (`compiles to JavaScript matching …`) | 5,413 | Better emit only |
| Error tests (`has expected compilation errors …`) | 3,214 | Type checker |

**Realistic ceiling without a type checker: ~5,413 / 8,627 (62.7%)**

We currently pass 2,957 tests — meaning roughly **~2,456 emit tests still fail** and represent
the entire addressable work surface without implementing a type checker.

### Why error tests need a real type checker

Error test baselines (e.g. `ArrowFunctionExpression1.errors.txt`) contain semantic errors
such as:
- `TS2511: Cannot create an instance of an abstract class`
- `TS2369: A parameter property is only allowed in a constructor implementation`
- Type mismatch errors, unresolved identifier errors, overload errors, etc.

These require symbol resolution and type inference — they cannot be detected from the AST alone.

### TypeScript's `checker.ts` is out of scope

The TypeScript type checker (`checker.ts`) is **~3 MB / ~50,000 lines** — the largest single
file in the TypeScript codebase. Implementing it from scratch would dwarf all other work
combined. **We will not implement a type checker.**

For error tests:
- Tests that fail due to **syntax errors** our parser already catches: free wins
- Tests that require semantic analysis: accept as permanently failing
- Do NOT invest engineering time trying to fake/heuristic type errors

**What exists**:
- Full pipeline: Scanner → Parser → Transformer → Emitter → BaselineFormatter
- Type erasure (interfaces, type aliases, type annotations, declare declarations)
- Class transform (property-to-constructor, parameter properties, access modifier stripping)
- Enum IIFE transform (`var E; (function(E) { ... })(E || (E = {}))`)
- Namespace IIFE transform with exported member qualification
- Multi-file test support (`// @Filename:` directive parsing, separate compilation)
- CommonJS module transform (basic: `Object.defineProperty(exports, "__esModule")`, `require()`, `exports.x = x`)
- Arrow function handling (single-param no-parens, object literal body wrapping)
- AST `multiLine` flags on Block, ObjectLiteralExpression, ArrayLiteralExpression
- Switch/case single-line emission
- ASI for `abstract` keyword before line break
- `await`/`yield` contextual handling (AwaitExpression in async vs sync contexts)
- Try/catch/finally formatting (catch/finally on separate lines)
- Optional catch binding downlevel (ES2019 `catch {}` → `catch (_a)` for older targets)
- Labeled tuple type elements (`[b: string]` syntax)
- `tsconfig.json` excluded from multi-file source echoes

## Known Regressions to Investigate

1. **`reScanGreaterToken`** — was disabled because it caused 4-test net regression. The scanner function exists but the parser doesn't call it.

## Fixes Applied (2026-03-04)

- **`await`/`yield` disambiguation**: `await(...)` in non-async context is a call expression; `await literal` in sync context emits as `yield`
- **Nested paren type erasure collapse**: `((<T>expr))` → `(expr)` — extended `wasTypeErasure` to handle double-paren wrapping
- **Empty statement trailing comments**: `parseEmptyStatement()` now captures trailing comments
- **Labeled tuple type elements**: Parser handles `[b: string]` labeled members
- **Try/catch/finally formatting**: `catch` and `finally` now on new lines after `}` (+29 tests)
- **Optional catch binding downlevel**: `catch {}` → `catch (_a)` for targets < ES2019
- **`tsconfig.json` not echoed**: Multi-file baselines skip `tsconfig.json` files in source echo section (+11 tests)
- **`const enum` at statement level**: `parseStatement()` now handles bare `const enum` (not just inside export/declare) (+6 tests)

## Fixes Applied (2026-03-03)

- **Trailing commas in multiline objects/arrays**: Added `hasTrailingComma` to AST, parse and emit it
- **Generated constructor multiLine**: When param-property initializers added to empty constructor body, set `multiLine=true`
- **Leading comments on erased declarations**: Return `NotEmittedStatement(leadingComments=...)` instead of `emptyList()` from transformer; emitter emits those comments
- **Trailing comments on enum/namespace**: Added `trailingComments` parsing to `parseEnumDeclaration` and `parseModuleDeclaration`
- **`for` loop `<` parsing**: Wrapped `tryParseTypeArguments` in `tryScan` so `i < 10` is not consumed as a type argument when not followed by `(` (+~20 tests)
- **Type assertion parenthesization**: Transformer drops outer `()` from `(<T>expr)` unless result needs parens (object literals, function/class exprs, etc.); re-wraps `CallExpression` for `new (<T>call())` semantics (+~22 tests)
- **Numeric literal property access**: Emitter writes `1..foo` instead of `1.foo` to avoid decimal-point ambiguity
- **Trailing comma in single-line arrays**: Emitter now emits trailing comma in non-multiline path when `hasTrailingComma` is set
- **Labeled statement chaining**: Emitter chains `target1: target2: stmt` on one line via `skipNextIndent` flag (+~25 tests)
- **Empty object destructuring**: Emitter emits `{}` (no spaces) for empty `ObjectBindingPattern`

## Priority Fixes (by impact)

### 1. Multi-file CommonJS improvements (~100+ tests)
The multi-file support and CommonJS transform are implemented but incomplete:
- **Import helpers** (`__importDefault`, `__importStar`, `__createBinding`, `__exportStar`) — needed for CommonJS with default/namespace imports
- **Identifier rewriting** — imported names need to be rewritten (e.g., `import A from './a'` → references to `A` become `a_1.default`)
- **`const` vs `var`** for `import = require` — TypeScript uses `const`, our transform uses `var`
- **Export hoisting** — `exports.x = void 0` should appear right after `__esModule` preamble
- **Re-exports** — `export { X } from "y"` needs CommonJS form

### 2. Comment preservation (~50+ tests)
- Trailing comments after IIFE closings (enum/namespace): `})(E || (E = {})); // comment`
- End-of-file comments not attached to any statement
- Comments inside array/object literals
- Leading comments on exported declarations
- `@removeComments: true` support (strip all comments)

### 3. Type assertion parenthesization ✅ FIXED
### 4. `for` loop `<` parsing ambiguity ✅ FIXED

### 5. AMD module format (~115 tests)
- `define(["require", "exports", ...], function(require, exports, ...) { ... })`
- Needs `@module: amd` support
- `/// <amd-dependency>` and `/// <amd-module>` directives

### 6. System module format (~36 tests)
- `System.register([], function(exports_1, context_1) { ... })`
- Needs `@module: system` support

### 7. `__awaiter` async/generator transform (~32 tests)
- Async functions targeting ES5/ES3 need `__awaiter` + generator rewrite

### 8. `export {}` for empty module files (~68 tests)
- When a file has only type-only imports/exports (all erased), emit `export {};` to preserve module semantics
- Also: we currently emit `export {}` in some cases where we shouldn't (~15 false positives)

### 9. `"use strict"` over-emission (~219 tests)
- We add `"use strict"` at target >= ES2015, but many tests don't expect it
- TypeScript only adds it for CommonJS module files or when `alwaysStrict` is set
- ES module files (with `import`/`export`) are inherently strict — no explicit `"use strict"` needed

### 7. Enum constant folding (~5+ tests)
- `1 << 1` → `2`, `1 << 2` → `4`, etc. in enum member initializers
- Need basic constant expression evaluation for numeric binary operations

### 8. Formatting fidelity
- **Indentation of chained calls**: `.map(...)` on next line should be indented
- **`function ()` with space** before parens in some contexts
- **Semicolons**: some edge cases with semicolons after blocks, empty statements
- **Blank lines**: preserve blank lines between declarations

### 9. Error diagnostics — accepted ceiling
- 3,214 tests require semantic type errors detected by a full type checker
- TypeScript's `checker.ts` is ~50,000 lines — implementing it is out of scope
- **Do not invest time on heuristic type error detection** — it won't scale
- Only free wins: tests where our parser already produces a syntax diagnostic

## Architecture Notes

```
Source String
    │
    ▼
[parseMultiFileSource] ── split // @Filename: sections, parse options
    │
    ▼ (per file)
[Parser] ── recursive descent → AST (source preserved in pos/end)
    │
    ▼
[Transformer] ── strip types, rewrite enums/namespaces/classes, CommonJS module transform
    │
    ▼
[Emitter] ── AST → JavaScript string (respects multiLine flags, indentation)
    │
    ▼
[formatBaseline / formatMultiFileBaseline] ── wrap in //// [...] //// envelope
```

All source files: `src/commonMain/kotlin/com/xemantic/typescript/compiler/`

Key files:
- `TypeScriptCompiler.kt` — Entry point, orchestrates pipeline
- `CompilerOptions.kt` — Directive parsing, multi-file splitting
- `Scanner.kt` — Lexer (pull-based token stream)
- `Parser.kt` — Recursive descent parser → AST
- `Ast.kt` — Sealed class hierarchy for AST nodes
- `Transformer.kt` — TS→JS AST transformations
- `Emitter.kt` — AST → JavaScript text
- `BaselineFormatter.kt` — Baseline output format

## Test Commands

```bash
# Full suite
./gradlew jvmTest 2>&1 | grep -a "tests completed"

# Single batch
./gradlew jvmTest --tests "*.TypeScriptCompilerTests_A" 2>&1 | grep -a "tests completed"

# Specific test
./gradlew jvmTest --tests "*.TypeScriptCompilerTests_A.asiAbstract*js*" 2>&1 | tail -5

# View failure diff (after running specific test)
cat "build/reports/tests/jvmTest/com.xemantic.typescript.compiler.TypeScriptCompilerTests_A/"*.html \
  | sed 's/<[^>]*>/\n/g' | grep -v "^$" | grep -A 30 "expected"
```
