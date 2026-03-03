# TypeScript-to-JavaScript Transpiler Implementation Plan

## Current Status (2026-03-03)

**Test results**: 2,910 / 8,627 passing (33.7%)
- Tests_A: 228/596 passing (38.3%)

Previous: 2,846 / 8,625 passing (33.0%), Tests_A: 216/596 (36.2%)

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

## Known Regressions to Investigate

A ~36-test regression was introduced in a previous session (from 2,882 to 2,846). These regressions were partially recovered by the new fixes. Remaining potential causes:
1. **`emitIfStatementCore` rewrite** — changed how if/else is emitted. The new code handles `} else` on the same line for non-multiline blocks, and `}\nelse` for multiline blocks. May have edge cases.
2. **CommonJS transform** — may incorrectly detect some files as "module files" and add CommonJS preamble. Check `isModuleFile()` logic.
3. **`reScanGreaterToken`** — was disabled because it caused 4-test net regression. The scanner function exists but the parser doesn't call it.

## Fixes Applied (2026-03-03)

- **Trailing commas in multiline objects/arrays**: Added `hasTrailingComma` to AST, parse and emit it
- **Generated constructor multiLine**: When param-property initializers added to empty constructor body, set `multiLine=true`
- **Leading comments on erased declarations**: Return `NotEmittedStatement(leadingComments=...)` instead of `emptyList()` from transformer; emitter emits those comments
- **Trailing comments on enum/namespace**: Added `trailingComments` parsing to `parseEnumDeclaration` and `parseModuleDeclaration`

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

### 3. Type assertion parenthesization (~30+ tests)
- `<T>expr` → after stripping `<T>`, the expression should NOT be wrapped in parentheses
- Currently `<number>[1, 3]` emits `([1, 3])` instead of `[1, 3]`
- The `TypeAssertionExpression` handling in the emitter adds unnecessary parens

### 4. `for` loop `<` parsing ambiguity (~20+ tests)
- `for (let x = 0; x < 1; ++x)` — the `<` is being interpreted as a type parameter start
- Need to disable type parameter/argument parsing in `for` loop context
- Or: make `<` disambiguation more conservative (check for identifier before `<`)

### 5. AMD module format (~15 tests)
- `define(["require", "exports", ...], function(require, exports, ...) { ... })`
- Needs `@module: amd` support
- `/// <amd-dependency>` and `/// <amd-module>` directives

### 6. `export {}` for empty module files (~10+ tests)
- When a file has only type-only imports/exports (all erased), emit `export {};` to preserve module semantics

### 7. Enum constant folding (~5+ tests)
- `1 << 1` → `2`, `1 << 2` → `4`, etc. in enum member initializers
- Need basic constant expression evaluation for numeric binary operations

### 8. Formatting fidelity
- **Indentation of chained calls**: `.map(...)` on next line should be indented
- **`function ()` with space** before parens in some contexts
- **Semicolons**: some edge cases with semicolons after blocks, empty statements
- **Blank lines**: preserve blank lines between declarations

### 9. Error diagnostics (3,212 error tests)
- Currently: if parser produces any diagnostic, `hasErrors = true`
- Many error tests expect `hasErrors = true` for code that parses successfully but has type errors
- Need basic type checking or at least heuristic error detection for common patterns

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
