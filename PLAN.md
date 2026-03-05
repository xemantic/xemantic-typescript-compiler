# TypeScript-to-JavaScript Transpiler Implementation Plan

## Current Status (2026-03-05)

**Test results**: 4,018 / 8,627 passing (46.6%)

Previous: 4,012 / 8,627 passing (46.5%)

## Test Suite Structure & Realistic Ceiling

The 8,627 tests split into two fundamentally different categories:

| Category | Count | Requires |
|---|---|---|
| JS emit tests (`compiles to JavaScript matching …`) | 5,413 | Better emit only |
| Error tests (`has expected compilation errors …`) | 3,214 | Type checker |

**Realistic ceiling without a type checker: ~5,413 / 8,627 (62.7%)**

We currently pass 4,010 tests — meaning roughly **~1,403 emit tests still fail** and represent
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

## Fixes Applied (2026-03-05, session 4)

- **Enum string quoting**: `memberNameToString` in Transformer forces double quotes for enum member string names; `emitStringLiteral` uses `rawText` when available.
- **`parsePrimaryExpression` inline comments**: Captures same-line trailing comments (`inlineCmts`) between a keyword/operator and the next expression (e.g. `new /*2*/ Array`). Uses `consumeTrailingComments()` in parent parsers (`parseArrayLiteral`, `parseObjectLiteralElement`, `parseBlock`, `parseCaseOrDefaultClause`) to prevent double-capture.
- **`ExpressionStatement.preSemicolonComments`**: New field stores inline comments between expression and `;` (e.g. `Array /*3*/;`), emitted by `emitExpressionStatement`.
- **Scanner `consumeTrailingComments()`**: Returns and clears trailing comments atomically, preventing double-capture when parent parsers explicitly need trailing comment values.

## Fixes Applied (2026-03-04, session 3)

- **Enum member comment preservation**: Parser now captures `leadingComments()` and `scanner.getTrailingComments()` per enum member. Transformer copies those to generated `ExpressionStatement` nodes.
- **Constructor prologue ordering**: When inserting parameter-property initializers into an existing constructor body, skip past any prologue directives (`"use strict"`, `"ngInject"`, etc.) — insert after the last prologue, not at index 0.
- **`emitPropertyAssignment` comment fix**: Replaced broken `write(":")` + manual spacing with `write(": ")` + `onNewLine` tracking to avoid extra blank lines between consecutive comments on initializer values. Fixed `commentsOnPropertyOfObjectLiteral1`.
- **Parser: post-colon inline comments on property assignments**: `parseObjectLiteralElement` captures `scanner.getTrailingComments()` after the `:` separator and merges them into the initializer's leading comments. Added `hasPrecedingNewLine` field to `Comment` (set by scanner).
- **Template literal raw content**: Scanner preserves `\` escape sequences verbatim in template literals (no decoding). Fixes many template literal tests.
- **Tagged template type arguments**: Parser's `LessThan` branch now also recognizes template literal tokens (NoSubstitutionTemplateLiteral, TemplateHead) after type args, enabling `tag<T>\`...\`` syntax.
- **Arrow function / function expression leading comments**: `emitArrowFunction` and `parseFunctionExpression` capture and emit leading comments on expression bodies and inline function expressions.
- **Parameter leading comments**: `parseParameter` captures `leadingComments()`; `emitParameters` emits them on separate lines when any parameter has leading comments.
- **LF vs CRLF newline handling**: `BaselineFormatter` respects `@newline: LF` compiler option.
- **TSX extension**: `BaselineFormatter` and `TypeScriptCompiler` emit `.jsx` only when `@jsx: preserve`.
- **Scanner `NumberFormatException` fix**: Empty hex/unicode escape sequences no longer crash (`\x` → `"\\x"`, `\u` → `"\\u"`).
- **`emitBlock` empty single-line**: Standalone empty blocks with `!multiLine` emit `{ }` (not newlined).
- **Detached comment preservation** (+31 tests): `orphanedComments()` in Transformer now emits a `NotEmittedStatement` for leading comments that are separated from the erased declaration by a blank line (≥2 newlines). Adjacent comments (no blank line) are dropped with the declaration. Rule: `source.substring(comment.end, statement.pos).count { it == '\n' } >= 2`. Requires `sourceText` field stored at start of `transform()`.

## Fixes Applied (2026-03-04, session 1)

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

Each entry below is a self-contained subagent task. Start with the test command, read the diff, find the fix area, implement, then run the full suite to confirm net improvement. See the "AI agent workflow" section in CLAUDE.md for the brief template.

### Orchestrator wave dispatch order

Run subagents in waves. Within a wave, dispatch in parallel using `isolation: "worktree"`. Between waves, merge all worktree branches to `main` (resolve conflicts, then `git push`) before starting the next wave.

| Wave | Tasks | Primary files touched |
|------|-------|----------------------|
| 1 ✅ | A (already fixed), G (+2) | Emitter.kt |
| 2 ✅ | B (+3), E (+2), F (+1) | Transformer.kt, Emitter.kt, Parser.kt |
| 3 | D | Transformer.kt (new AMD fn) |
| 4 | C1, C2 | Transformer.kt (CommonJS) |
| 5 | C3, C4, H, I, J | Various (complex / dependent) |

**Merge workflow per wave:**
```bash
git fetch
# For each subagent branch (worktree result):
git merge <branch> --no-ff -m "merge: <task-letter> fix"
# Resolve conflicts (typically just different functions in same file)
git push
```

---

### ✅ A. `"use strict"` over-emission — ALREADY FIXED

ESM check (`hasModuleStatements && isESModuleFormat`) was implemented in a prior session. AMD/System case (no top-level `"use strict"`) requires Task D (AMD/System module wrappers) to be useful. Zero net test gain from further work here.

---

### ✅ B. `export {}` correctness — PARTIALLY FIXED (+3 tests)

Type-only export specifier erasure, computed property key tracking, non-instantiated namespace default export. ~13 remaining cases require cross-file type resolution or unsupported features.

### B. `export {}` correctness (~68 + ~15 tests) ★ HIGH IMPACT

**Symptom A** (~68 tests): Files with only type-only imports/exports (all erased by transformer) must emit `export {};` to preserve ES module semantics. Currently we emit nothing.

**Symptom B** (~15 tests): We emit `export {}` in some cases where we shouldn't (e.g., non-module files).

**Fix area**: `Transformer.kt` — after erasing all declarations, if the original file was a module (had `import`/`export`) and the result is empty or only has `"use strict"`, emit `export {};`.

---

### C. Multi-file CommonJS improvements (~100+ tests) ★ COMPLEX

The CommonJS transform is implemented but incomplete. These sub-fixes are separable:

- **C1. `const` vs `var` for `import = require`** (~10 tests): TypeScript emits `const a_1 = require("./a")`, we emit `var`. Fix in `Transformer.kt` `transformToCommonJS`.
- **C2. Export hoisting** (~20 tests): `exports.x = void 0;` lines should appear immediately after the `__esModule` preamble, before any other code. Fix ordering in `transformToCommonJS`.
- **C3. Import helpers** (`__importDefault`, `__importStar`, `__createBinding`, `__exportStar`) (~30 tests): needed for default/namespace imports. These are emitted as helper functions at the top of the file. See TypeScript's `tslib` helpers.
- **C4. Identifier rewriting** (~40 tests): `import A from './a'` → all references to `A` in the file become `a_1.default`. Requires a symbol table pass in the transformer.

---

### D. AMD module format (~57 tests) ★ LARGE, ISOLATED

**Symptom**: `@module: amd` tests expect `define(["require", "exports", ...], function(...) { ... })` wrapper. We don't emit this format at all.

**Fix area**: New `transformToAMD` function in `Transformer.kt` (similar to `transformToCommonJS`). Also handle `/// <amd-dependency path="..." />` and `/// <amd-module name="..." />` directives in `CompilerOptions.kt`.

**Subtest to start with**: `./gradlew jvmTest --tests '*.amdImportNotUsedInTypePosition1*'`

---

### ✅ E. Comment preservation — binary operator positions — FIXED (+2 tests)

`BinaryExpression` now captures `operatorLeadingComments`, `operatorTrailingComments`, and `operatorHasPrecedingLineBreak` in Parser; Emitter handles the indented-operator-on-new-line case.

### E. Comment preservation — binary operator positions (~10 tests)

**Test**: `./gradlew jvmTest --tests '*.commentOnBinaryOperator1*'`

**Symptom**:
```
Expected: var a = 'some'
              // comment
              + 'text';
Actual:   var a = 'some' + 'text';
```

**Fix area**: `emitBinaryExpression` in `Emitter.kt`. When the right operand has `leadingComments` with `hasPrecedingNewLine=true`, emit a newline + indent before the operator and operand.

---

### ✅ F. Comment preservation — yield/await inner comments — FIXED (+1 test)

`parseYieldExpression` now calls `consumeTrailingComments()` after `yield`/`*` to capture inline comments and attach as `leadingComments` on the inner expression.

### F. Comment preservation — yield/await inner comments (~5 tests)

**Test**: `./gradlew jvmTest --tests '*.yieldExpressionInnerCommentEmit*'`

**Symptom**: `yield /*comment2*/ 2` → emits as `yield 2` (comment dropped).

**Fix area**: `parseYieldExpression` / `parseAwaitExpression` in `Parser.kt` — capture `scanner.getTrailingComments()` after the `yield`/`await` keyword and attach to the inner expression. Look at the existing `AwaitExpression` handling around line 1830.

---

### G. `@removeComments: true` support (~20 tests)

**Symptom**: Tests with `// @removeComments: true` expect all comments stripped from output. We currently emit comments regardless.

**Fix area**: `Emitter.kt` — `options.removeComments` flag already exists in `CompilerOptions`. Add guards around every comment-emit site. Also `BaselineFormatter.kt` — source echo section must strip comments from the echoed source.

---

### H. System module format (~36 tests)

**Symptom**: `@module: system` tests expect `System.register([], function(exports_1, context_1) { ... })`.

**Fix area**: New `transformToSystem` function in `Transformer.kt`.

---

### I. `__awaiter` async/generator transform (~32 tests)

**Symptom**: Async functions targeting ES5/ES3 need `__awaiter` + `__generator` rewrite. Currently emitted as-is.

**Fix area**: `Transformer.kt` — detect `async function` + target < ES2017, rewrite body using `__awaiter/__generator` pattern.

---

### J. Enum constant folding (~5 tests)

**Symptom**: `1 << 1` in enum initializers should emit `2`, not `1 << 1`.

**Fix area**: `Transformer.kt` `transformEnumMember` — evaluate constant binary expressions (only `+`, `-`, `*`, `/`, `<<`, `>>`, `|`, `&`, `^`) when both operands are numeric literals.

---

### ✅ Fixed

- Type assertion parenthesization ✅
- `for` loop `<` parsing ambiguity ✅
- Error diagnostics (~2809 tests) — requires full type checker, **accepted ceiling, do not invest time**
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
