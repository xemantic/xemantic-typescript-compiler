# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: one line per lesson, grouped under a heading if a theme emerges.

## Known gotchas

- `project.exec {}` is not available in Gradle 9 Kotlin DSL task `doLast` blocks — use `ProcessBuilder` directly instead.
- TypeScript compiler tests are **generated** by `./gradlew generateTypeScriptTests` into `build/generated/typescript-tests/`; this task requires the TypeScript repo to be cloned first (done automatically via `cloneTypeScriptRepo` dependency). Never edit generated files manually.
- **Deprecated features skipped in JS emit tests**: Parameterized JS emit tests for `target=ES5`/`ES3` and `module=AMD`/`System`/`UMD` are not generated (deprecated in TypeScript 6.0, removed in 7.0/tsgo). Error baseline tests for these combinations ARE still generated since TS5107/TS5108 deprecation diagnostics must still fire. The `effectiveTarget` maps ES3/ES5 → ES2015 so no downlevel transforms are needed.
- **Disabled test category**: `.errors.txt` error baseline tests are commented out in `build.gradle.kts` `generateTypeScriptTests` task — search for `TODO: Re-enable when type checker is implemented`. Re-enabling adds ~9,055 tests (see PLAN.md item 4c).
- Kotlin 2.x disallows `.` in backtick-quoted JVM method names (error: "Name contains illegal characters: .."); sanitize test function names by replacing dots with underscores (e.g. `foo_ts` not `foo.ts`). Some TypeScript test base names contain dots beyond the extension (e.g. `accessors_spec_section-4.5_error-cases`), so the entire `nameWithoutExtension` must have dots replaced, not just the `.ts`/`.js` suffix.

### Scanner/Parser gotchas

- **`lookAhead` vs `tryScan`**: `lookAhead` ALWAYS restores scanner state. `tryScan` keeps scanner state if callback returns truthy. Use `lookAhead` for probe-and-decide patterns; use `tryScan` for "try to parse, keep results if success."
- **`lookAhead` does NOT restore diagnostics**: The parser's `lookAhead` restores scanner position and the `token` field, but any `reportError`/`parseExpected` failures during the callback permanently add to `diagnostics`. When using `lookAhead` with complex parsing (e.g., `parseComputedPropertyName()`), save `diagnostics.size` before and trim after to discard speculative errors.
- **`reScanGreaterToken`** (splitting `>>` to `>` for nested generics) was implemented but caused 4-test net regression — left disabled in the parser. The implementation in `Scanner.kt` is correct; the issue is likely subtle interaction with `tryScan` nesting. Re-enable cautiously.
- **`getPos()` in Parser** = `scanner.getTokenPos()` (start of current token), **`getEnd()`** = `scanner.getPos()` (end of current token text). After `parseExpected()`, the scanner has ALREADY advanced to the next token — so `getPos()` is the start of the NEXT token.
- **Case clause singleLine detection**: Check `source.substring(caseStartPos, firstStmtStart).contains('\n')` to determine if statements are on the same line as `case:`. Do NOT use `scanner.getPos()` after `parseExpected(Colon)` since the scanner has advanced past the colon into the next token's trivia.
- **TS1007 related info for missing close delimiters**: Use `parseExpectedClosing()` (not `parseExpected`) when closing `)` in statement-level constructs (`if`, `while`, `with`, `do-while`). This provides `!!! related TS1007` pointing back to the opening `(`. DO NOT use this globally (in `parseExpected`) — it causes 59+ FP regressions in error recovery tests because the `openTokenStack` doesn't track delimiter types and pops incorrectly in complex error recovery scenarios. For general `parseExpected(CloseParen/CloseBrace)`, only emit TS1007 at EOF (current behavior).
- **Extending squiggle span to include `;`**: When a parser diagnostic should cover the trailing `;` (e.g. `[]: number;` → entire 14-char span), call `parseSemicolon()` first (consuming `;` if present), then use `scanner.getPrevTokenEnd()` for the span end. `getEnd()` is wrong because it returns the scanner position AFTER the next token is scanned. `scanner.getPrevTokenEnd()` = position right after the last-consumed token's text, before the next token's trivia.

### Emitter gotchas

- **Tab preservation in error squiggles**: The error baseline formatter must preserve tab characters from source lines in the squiggle indentation — tabs stay as tabs, other characters become spaces. Don't use `" ".repeat(col)`.
- **Numeric literal property access**: `1.foo` is ambiguous in JS (the `.` is a decimal point). Emit `1..foo` when the numeric literal has no decimal point, exponent, or `0x`/`0b`/`0o` prefix.
- **Yield expression operand parsing**: Use `isStartOfExpression()` check (not `!canParseSemicolon()`) to determine if yield has an operand. `canParseSemicolon()` returns false for `]` and `)` which would cause yield to try parsing them as expressions. TypeScript checks `!hasPrecedingLineBreak() && (asterisk || isStartOfExpression())`.

### Transformer gotchas

- **Orphaned comments (erased declarations)**: Only preserve a leading comment from an erased declaration if there is a blank line (≥2 newlines) between the comment's `end` position and the declaration's `pos`. Adjacent comments are part of the declaration and are dropped.
- **CommonJS transform ordering**: Applied AFTER all other transforms. `transformToCommonJS` receives already-transformed statements. The `isModuleFile` check uses the ORIGINAL source file statements.
- **Constructor prologue ordering**: When inserting parameter-property initializers into an existing constructor body (no `super()` call), insert AFTER prologue directives (`"use strict"`, `"ngInject"`), not at index 0.
- **Type assertion parens**: `(<T>expr)` — the Transformer (not Emitter) must drop them. Fix belongs in Transformer because `TypeAssertionExpression` is already stripped before Emitter sees it.
- **`new (<T>call())` semantics**: `new (A())` ≠ `new A()` — after stripping the type assertion, if the constructor expr becomes a `CallExpression`, it must be re-wrapped in `ParenthesizedExpression`.

### Module detection gotchas

- **`moduleDetection: "force"`** makes ALL files modules regardless of content. Both `isModuleFile()` (Transformer) and `hasModuleStatements()` (Emitter) check for this.
- **`.mts`/`.mjs`/`.cts`/`.cjs` extensions** are always module files. Both functions also check for these extensions.
- **`isolatedModules: true` ≠ `moduleDetection: force`**: `isolatedModules` tells TypeScript to check each file independently but does NOT force module treatment for files without imports/exports. Adding `isModuleFile = true` for `isolatedModules` causes regressions.
- **Node16/Node18/Node20/NodeNext are all CJS for `.ts` files**: `isESModuleFormat` must return `false` for plain `.ts` files — only `.mts`/`.mjs` are ESM. Use `ModuleKind.isNodeNext` helper for checks. Files only get `__esModule` if they have imports/exports or `/// <reference types>` directives — NOT all files in these modes.
- **JSON files re-emitted with trailing comma stripping**: `TypeScriptCompiler.kt` strips trailing commas from JSON output using `stripJsonTrailingCommas()` since TypeScript parses and re-emits JSON, naturally removing them.
- **`esModuleInterop` defaults to `true`**: TypeScript 5.5+ deprecated `esModuleInterop: false`. Test baselines without explicit `@esModuleInterop` expect helpers (`__importStar`, `__importDefault`). Only when `@esModuleInterop: false` is explicitly set should helpers be skipped for default/namespace imports.
- **`__importStar`/`__importDefault` conditional on `esModuleInterop`**: These helpers wrap `require()` only when `esModuleInterop: true`. Without it, use plain `require()`. `__exportStar`/`__createBinding` are ALWAYS used for `export * from` regardless of `esModuleInterop`.
- **Helper ordering depends on first usage**: When both `__importStar` and `__exportStar` are needed, TypeScript emits the one used first in the output before the other. Track `importStarUsedFirst` by checking which `needsXxx = true` is set first.
- **`importHelpers: true` uses tslib**: When set, don't emit inline helper functions. Instead add `const tslib_1 = require("tslib")` (CJS) or `"tslib"` dep + `tslib_1` param (AMD), and use `tslib_1.__helperName(...)` instead of bare `__helperName(...)`. The `helperExpr()` method handles this.

### Binder gotchas

- **`canMerge` must include Variable+Module**: `declare const b: T; declare namespace b {}` is valid TypeScript. The binder's `canMerge` must allow `Variable + Module` and `Module + Variable` merging, otherwise the second declaration silently overwrites the first symbol. This matters for `isTypeOnlyImportRequire` which then sees only the namespace and incorrectly elides the `require()`.

### Type system gotchas

- **Type subclasses are nested inside `sealed class Type`**: To avoid name conflicts with AST TypeNode subclasses (`UnionType`, `IntersectionType`, `TypeReference`, `TypeParameter` in Ast.kt), the checker's semantic type classes are nested: `Type.Intrinsic`, `Type.Union`, `Type.Intersection`, `Type.Object`, `Type.Interface`, `Type.Reference`, `Type.TypeParam`. Use `when (type) { is Type.Union -> ... }` etc.
- **`TypeFlags` companion shadows Kotlin type names**: `TypeFlags.String`, `TypeFlags.Number`, `TypeFlags.Boolean`, `TypeFlags.Object` shadow Kotlin types inside the `TypeFlags` companion. Outside the companion, `String` refers to `kotlin.String` as normal. Inside the companion, use `kotlin.String` if you need the Kotlin type.

### Checker gotchas

- **`isSymbolTypeOnly` for `declare namespace` with value exports**: A `ModuleInstanceState.NonInstantiated` namespace (from `declare namespace`) is NOT type-only if it has value exports. Check `symbol.exports?.values?.any { it.flags.hasAny(SymbolFlags.Value) }` — if true, the namespace is a runtime value shape even though it has no IIFE. This matters for `export = a` where `a` is a `declare namespace { export const x }`.
- **`node.end` overshoots by one token**: In this AST, `node.end` = `scanner.getPos()` AFTER calling `nextToken()`, so it includes the start/end of the NEXT scanned token — not the true end of the node's text. To compute squiggle length, use `expressionTrueEnd(expr)` which traverses the rightmost leaf: `PropertyAccessExpression → name.pos + name.text.length`, `StringLiteralNode → pos + rawText.length + 2`, `NumericLiteralNode → pos + text.length`, `Identifier → pos + text.length`. Never use `node.end - node.pos` for span length in diagnostics.
- **Merged enum autoValue reset**: Each enum declaration block resets auto-increment to 0. `computeEnumSymbolValues` must have `var autoValue = 0.0` INSIDE the loop over declarations, not outside — otherwise merged enums get wrong values (e.g., `Enum1 { A0 = 100 }; Enum1 { A }` → A should be 0, not 101).
- **Nested enum value computation**: `computeAllEnumValues` must recurse into namespace `exports` to find nested const enums. Top-level `result.locals` only has the outer namespace symbols.
- **`resolveAlias` cycle detection**: Import aliases can be circular (import shadowing, re-exports). Must use a `visited: MutableSet<Int>` parameter to prevent StackOverflow.
- **Nested QualifiedName resolution**: `import I = A.B.C.E` creates a QualifiedName where `left` is another QualifiedName, not an Identifier. `resolveAlias` must handle this via `resolveQualifiedName` which recurses.
- **Const enum negative values**: TypeScript does NOT wrap negative const enum values in `ParenthesizedExpression`. The Transformer emits `PrefixUnaryExpression(-, literal)` directly. `parenthesizeForAccess` adds parens only when used as property access base (e.g., `(-1).toString()`).
- **Const enum comment `*/` escaping**: `*/` inside const enum comment labels must be escaped to `*_/` to avoid prematurely closing the `/* ... */` comment.
- **`isolatedModules` and checker const enums**: When `isolatedModules` is true, do NOT use the checker for const enum inlining — the checker has cross-file info that shouldn't be used. Only use local `collectConstEnumValues`.
- **Circular type resolution StackOverflow**: `getDeclaredTypeOfClassOrInterface` must store the type in `declaredTypes` cache BEFORE resolving base types, otherwise `class A extends B` + `class B extends A` causes infinite recursion. Similarly, type alias resolution needs a sentinel cache entry (`errorType`) before resolving the aliased type. Always wrap `getTypeFromTypeNode`/`getDeclaredTypeOfSymbol` calls in `try-catch(StackOverflowError)` when called from visitor passes.
- **TS2339 false positive prevention**: Only check `this.prop` in class bodies (not arbitrary property access) to avoid FPs from incomplete type resolution. Skip classes with base types, .js files, enums, anonymous types, union/intersection types, and RUNTIME_PROPERTIES (prototype, constructor, toString, etc.).

### Multi-file baseline gotchas

- **`tsconfig.json` not echoed**: The TypeScript test harness treats `tsconfig.json` as project configuration, not a source file. Never include it in the `sourceEchoes` list in `formatMultiFileBaseline`. Other JSON files (e.g. `tsconfig1.json`) ARE echoed.
- **Error baseline file ordering**: TypeScript's test harness reorders files in error baselines when the last `@Filename` file contains `require(` or `reference path`, or when `noImplicitReferences` is set. In those cases, the last file is moved to the front. This differs from JS baselines which always use `@Filename` order.
- **CRLF in test source files**: TypeScript test source files use CRLF line endings. `parseMultiFileSource` normalizes to LF early to avoid trailing `\r` characters causing extra blank lines in error baselines.

### Deprecation diagnostic gotchas

- **TS5101 vs TS5102 vs TS5107**: Three different deprecation codes. TS5107 = target/module/moduleResolution deprecations. TS5101 = outFile/baseUrl/downlevelIteration deprecations (still functioning). TS5102 = removed options (out, charset, keyofStringsOnly, noImplicitUseStrict, etc.) with message "has been removed. Please remove it from your configuration."
- **TS5101 `baseUrl` migration URL**: `baseUrl` deprecation has a message chain continuation "  Visit https://aka.ms/ts6 for migration information." — requires `messageChain` in the Diagnostic.
- **`const enum` at statement level**: `parseStatement()` must check for `const enum` (not just inside `export`/`declare` contexts) — otherwise `const enum E {}` is misparse as `const` variable named `enum` + expression `E` + block.
- **ES3 target is removed (TS5108)**: When `target: "ES3"` is set, `effectiveTarget` returns `ES2015` instead — ES3 support was removed in TypeScript 5.5 and the compiler uses a modern default.

### Checker diagnostic gotchas (TS2454/TS2564/TS6133)

- **`strict: false` suppresses TS2454/TS2564**: TypeScript test baselines expect these diagnostics by default, but NOT when `@strict: false` is explicitly set. Use `options.strictExplicitlyFalse` to distinguish default-false from explicit-false.
- **Definite assignment assertion `!`**: `var x!: Type` and class property `x!: Type` skip TS2454/TS2564 checking. Check `exclamationToken` on both `VariableDeclaration` and `PropertyDeclaration`.
- **Ambient contexts**: Skip TS2454/TS2564 in `declare` namespaces, classes, and functions. A class inside a `declare namespace` is ambient even if the class itself doesn't have `declare`.
- **TS6133 non-module files**: Don't check file-level unused declarations in non-module files (no imports/exports). Only check inside namespaces, functions, blocks.
- **TS6133 write-only**: Assignment targets (`x = value`) are NOT reads. Left side of `=` operator is write-only. Compound assignments (`x += 1`) ARE reads.

### Test assertion gotchas

- Avoid partial `assert("x" in result)` — always assert the full expected output.
- **Test ordering sensitivity**: Adding new checker code that increases diagnostics can cause deterministic failures in unrelated JS emit tests (e.g., `commentOnArrayElement3`, `castParentheses`). This appears to be a JIT or test execution order interaction — tests pass individually but fail in the full suite. Always run the full suite to verify no regressions.

### TS2304 unresolved name checking gotchas

- **Kotlin property initialization order**: Properties declared after `init {}` have default values (0 for Int, false for Boolean) during init execution. The `maxCheckDepth`, `checkDepth`, and `strictNullChecks` variables MUST be declared BEFORE the `init` block, not after, or the depth limit will be 0 / null checks disabled and all checking will be skipped.
- **KNOWN_GLOBALS coverage**: ~400 lib.d.ts names in the companion object. Missing a global causes false positive TS2304. When adding new globals, check both value and type positions.
- **KEYWORD_IDENTIFIERS**: Our parser produces `Identifier` nodes for `this`, `super`, `true`, `false`, `null`, TypeScript type keywords (`any`, `number`, `string`, etc.), and access modifiers (`public`, `private`, etc.). These must be excluded from TS2304 checking.
- **Binder scope model**: The binder only creates file-level symbol tables. Function parameters, catch variables, and block-scoped declarations are NOT in the binder's symbol table. The TS2304 checker maintains its own scope chain.
- **Test generator only processes `.ts`**: `.tsx` files are excluded from test generation (`f.extension == "ts"` in build.gradle.kts). This means JSX-related diagnostics (TS7026) are untestable.
- **`arguments` not a global**: Don't include `arguments` in KNOWN_GLOBALS — it's only available inside non-arrow functions via the `hasArguments` flag on NameScope.
- **`var` hoisting for TS2304**: `var` declarations inside nested blocks/loops are function-scoped. `collectHoistedVarNames` recursively finds them and adds to the enclosing scope.
- **`isIdentifier()` vs `isIdentifierToken()`**: Inside `scanner.lookAhead {}`, use `isIdentifierToken(scanner.getToken())` not `isIdentifier()` — the latter checks the Parser's cached `token` field which isn't updated inside lookAhead.

### TS2552 spelling suggestion gotchas

- **Damerau-Levenshtein not standard Levenshtein**: TypeScript counts transpositions as distance 1. Use optimal string alignment (restricted Damerau-Levenshtein).
- **Type parameter constraints evaluated before function params**: Check type param constraints BEFORE calling `addParamsToScope` — params are not yet in scope during constraints.
- **Gradle binary cache inconsistency**: When changes to Checker.kt don't seem to take effect, a full clean (`rm -rf build`) is needed.
- **Ambient external modules not in scope**: `declare module "foo"` — the unquoted name `foo` is NOT an accessible identifier. Exclude from `fileScope`.
- **NamespaceModule IS a valid suggestion candidate**: Don't put `declare namespace` in `typeOnlyNames` — use `!sym.flags.hasAny(Value or Module)`.
- **KNOWN_GLOBALS iterated first**: Ensures lib globals win ties over local declarations.

### Checker diagnostic gotchas (TS18004/TS1103)

- **Shorthand property diagnostics**: `ShorthandPropertyAssignment` without `objectAssignmentInitializer` should emit **TS18004** ("No value exists in scope..."), not **TS2304** ("Cannot find name..."). Handled by `checkShorthandPropertyResolved` in Checker.kt.
- **`for await` in non-async (TS1103)**: `ForOfStatement` with `awaitModifier` in non-async functions gets TS1103. The related TS1356 uses `FuncRef(pos, length)` to track enclosing function position — supports named functions, anonymous function expressions, and arrow functions.
- **`verbatimModuleSyntax` suppresses const enum inlining**: When set, skip `collectConstEnumValues`, skip checker's `resolveConstEnumMemberAccess`, keep const enum IIFE bodies, and don't treat const enums as type-only.

### TS2528 multiple default exports gotchas

- **Anonymous FD/CD position**: Error at `export` keyword, NOT `function`/`class` — `FunctionDeclaration.pos` = `function` keyword, must search backwards for `export`.
- **2752/2753/6204 code exception**: When the LAST export is a `FunctionDeclaration`, codes swap: non-lasts use TS2752, last uses TS2753.
- **TS2323 vs TS2528 can fire simultaneously**: `emitTs2323 = declCount >= 2`; `emitTs2528 = hasNonDeclInline || !emitTs2323`.
- **`ExportSpecifier.name.pos` includes trivia**: Skip whitespace to get token start position for diagnostics.

### Top-level await gotchas

- **Parser `topLevelAwait` parameter**: When `module` is ES2022+, NodeNext, Preserve, or System, the Parser's `topLevelAwait` flag must be set to `true`. This sets initial `inAsyncContext = true` at the file level, enabling `await` keyword recognition at the top level. Inside function bodies, `inAsyncContext` is still reset per-function based on `async` modifier. In sync functions, `await(x)` remains a call expression (identifier).

### Const enum type-only treatment gotchas

- **Erased const enums must be type-only in ALL pre-scans**: `earlyPureTypeNames`, `pureTypeNames`, `directExportedVarNames`, and `topLevelTypeOnlyNames` — both locally-declared and imported. Without this, `export = ConstEnum` generates wrong output.
- **Binder `export { X }` must not overwrite value symbols**: For local re-exports without `from` clause, skip creating an Alias if the name is already declared as a value.
- **`.d.ts` module resolution only for relative specifiers**: Non-relative specifiers might find `.d.ts` files with augmented ambient modules, making TS2694 checks unreliable.
- **Ambient module `.d.ts` exports use inner module exports**: When resolving to a `.d.ts` with `declare module "X" {}`, use that module's exports, NOT file-level locals.
- **Default import resolution chain**: Resolve `import Foo from "./mod"` in order: (1) `locals["default"]`, (2) `ExportAssignment` (not `isExportEquals`), (3) `export { X as default } from`.

### Decorator metadata type serialization gotchas

- **`null`/`undefined`/`never` → `void 0`**: Serialize as `VoidExpression(0)`, not `Object`. `never` always excluded from unions; `null`/`undefined` only filtered when `!strictNullChecks`.
- **Numeric enum type serialization**: `E.A` (QualifiedName) and plain `E` where E is a numeric enum → `Number`. String enums → `Object`.
- **Default import metadata safety wrapper**: For `db_1.default.Foo`, TypeScript wraps it: `typeof (_a = typeof db_1.default !== "undefined" && db_1.default.Foo) === "function" ? _a : Object`. Post-process `__metadata` args AFTER `rewriteIdInStatement`.
- **`export type { Foo }` / `export { TypeAlias }` make imports type-only**: Cross-file type-only detection. Check `checker.isTypeOnlyExportName` and `checker.isValueExport` during pre-scans.
- **`resolveModuleSpecifier` for absolute-path test files**: Use `fileBase == "/$baseName"` (not `endsWith`) for relative specifiers where `fileBase.startsWith("/")`.
- **`isValueExport` returns `true` (safe default)**: Only return `false` when definitively type-only (TypeAlias, Interface, non-instantiated namespace).

### TS2454/TS2564 gotchas

- **`any` type skips checking**: Variables/properties typed as `any` skip TS2454/TS2564.
- **`var` declarations**: TypeScript DOES check `var` for TS2454 (when strict) — only `any`-typed vars are skipped.
- **`ExportAssignment` is a use site**: `export = Foo` must be checked in `checkUsesOfUninitialized`.
- **Abstract classes still need TS2564**: Only `declare` classes are exempt, NOT `abstract`.
- **TS7030 guard**: Requires `strictNullChecks || noImplicitReturns`. TS2355 fires regardless.
- **`NumericLiteralNode` in method names**: All method-name extraction functions must handle it, not just `Identifier` and `StringLiteralNode`.
- **Bare specifier TS2882**: Always emit for bare (non-relative) specifiers except in AMD/System/UMD modules.

### TS1210 class strict mode gotchas

- **TS1210 vs TS1100**: TS1210 fires for `arguments`/`eval` in class bodies (always strict). TS1100 fires in external strict mode contexts (functions, modules). TypeScript NEVER emits both for the same node — TS1210 takes priority in class bodies. Suppress TS1100 inside class bodies for these names.
- **`emitDeclarationOnly` still needs checker**: Multi-file compilation with `emitDeclarationOnly` must still parse/bind/check files for diagnostics like TS1210. But use `declarationOnly = true` to limit checks — running the full checker produces FPs like TS6131 that TypeScript's declaration-only mode suppresses.
- **`isAlwaysTruthyExpr` vs `isAlwaysTruthyForOrExpr`**: In `||` contexts, TypeScript only flags object-like expressions (function, arrow, object, array, class, regex). Numeric/string literals and `new` expressions are NOT flagged in `||` but ARE flagged in `if`/`else if` conditions.

### Kotlin idioms

- **No non-stdlib dependencies in `commonMain`**: The project targets Kotlin Native (in addition to JVM/JS), so `commonMain` must use only `kotlin.*` and `kotlinx.*` packages. No `java.*`, no `BigDecimal`, no JVM-only types. Use Kotlin's built-in numeric types and stdlib math (`kotlin.math.*`).
- **Enum context resolution** (Kotlin 2.1+): When the expected type is an enum, use unqualified entry names — write `Equals`, not `SyntaxKind.Equals`. This applies to `when` branch conditions, named arguments (`operator = Equals`), comparisons (`flags == VarKeyword`), and any other position where the enum type is inferred. Caveat: if a data class has the same name as an enum entry (e.g. `LabeledStatement`), keep the `SyntaxKind.` prefix to avoid ambiguity.
- **`in 0..<x` range checks**: Prefer `pos in 0..<end` over `pos >= 0 && end > pos` for range validation — uses Kotlin's `rangeUntil` (`..<`) operator for exclusive upper bound.
- **No JVM-only APIs in `commonMain`**: `Map.putIfAbsent` → use `getOrPut`; `Math.pow` → use `kotlin.math.pow` extension. Always use Kotlin stdlib equivalents for multiplatform compatibility.
- **`when` guard conditions** (Kotlin 2.1+): Use `when (val ch = x) { '/' if condition -> ... }` instead of `when { ch == '/' && condition -> ... }`. The `if` guard after the match value keeps pattern matching readable and avoids nested `when`/`if` blocks.

## AI agent mission

**Phase 4: Structural type checker.** Pipeline: Scanner → Parser → **Binder → Checker** → Transformer → Emitter. ~7,632 / 10,077 tests passing (~75.7%). Current work: replace string-based type representation with Type sealed class hierarchy, implement structural typing engine (TS2322/TS2339/TS2345), prepare for parallel checking via Kotlin coroutines (inspired by tsgo's N-checker goroutine model).

### Execution protocol (MANDATORY — follow exactly)

PLAN-PHASE-4.md contains the **QUEUE**. Execute top-to-bottom:

1. Find the first unchecked (`- [ ]`) item in the QUEUE
2. Implement it — the item describes the deliverable
3. Run the full suite (`./gradlew jvmTest 2>&1 | grep -a "tests completed"`)
4. Verify no regressions from the currently passing test count
5. Check off the item (`- [x]`), add CLAUDE.md gotcha if applicable, commit and push
6. If the queue is empty or all remaining items are blocked/skipped: stop and wait for instructions

**HARD RULES:**
- **Do NOT skip ahead** in the queue — work item 0 before item 1, always.
- **Do NOT switch items** mid-task — finish the current item before moving on.
- **Analysis items** (item 0) should produce written artifacts (design docs, categorized lists) before any code is written.
- **Infrastructure items** (items 1-3) are foundational — correctness matters more than speed. Read TypeScript's architecture first.
- **No regressions** — the currently passing tests must continue to pass after every change.

### Reference TypeScript sources

The original TypeScript compiler source is in `typescript-repo/src/compiler/` (if cloned — only test fixtures are present in the sparse clone). When a fix is ambiguous or behavior is unclear, consult TypeScript's public documentation and source on GitHub. The 1M context window can accommodate large files when needed.

## AI agent workflow

Long multi-session conversations accumulate dead-end investigations and compacted summaries that dilute signal. Prefer **focused subagent tasks** over extending the main context indefinitely.

### Subagent brief template

A well-formed subagent brief for a fix in this codebase includes:

1. **The failing test(s)**: exact test name(s) to run, e.g. `./gradlew jvmTest --tests '*.commentOnBinaryOperator1*'`
2. **Expected vs actual diff**: paste the `--- expected / +++ actual` output so the agent sees the target immediately
3. **The source file**: path in `typescript-repo/tests/cases/compiler/` so the agent can read the TypeScript input
4. **The likely fix area**: name the file and function (e.g. "look at `emitBinaryExpression` in `Emitter.kt`")
5. **Relevant CLAUDE.md gotchas**: copy any gotcha entries that apply to the area being changed
6. **Regression guard**: "run the full suite (`./gradlew jvmTest 2>&1 | grep -a 'tests completed'`) before finishing and report the before/after count"

### Parallelism and branch isolation

Run parallel subagents in **separate branches** (use `isolation: "worktree"` in the Agent tool call). Limit to **max 2 parallel subagents** to keep resource usage and merge conflicts manageable — nearly every fix touches `Parser.kt`, `Transformer.kt`, or `Emitter.kt`.

Dispatch in **waves** to keep merge conflicts manageable:
- Pick fixes that touch *different* primary files for a wave
- Merge + resolve conflicts between waves before starting the next
- Fixes that touch the same file heavily (e.g. two Transformer changes) should be sequential

### Merge workflow (between waves)

After all subagents in a wave complete, merge their worktree branches sequentially into `main`:

```bash
git fetch
git merge <worktree-branch> --no-ff -m "merge: task <X> fix"
# Conflicts are typically in different functions of the same file — resolve manually
git push
```

### Context discipline

- Keep this file and `PLAN-PHASE-4.md` up to date after each session so the next agent/developer starts with accurate state
- `PLAN-PHASE-4.md` contains the type checker implementation queue; `PLAN-PHASE-3.md` has deferred Phase 3 items

## How to run tests

```bash
# Full suite (always clean binary cache first — stale cache inflates failure count):
rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest 2>&1 | grep -a "tests completed"

# Single test (get expected vs actual diff):
rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest --tests '*.<TestName>*' 2>&1 | grep -a -A 40 "message" | head -50
```

**Note:** All failures are deterministic (confirmed via 5-run study). Count variance between runs is caused entirely by dirty binary cache from interrupted runs, not JVM instability.

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
- Do not use `grep` (without `-a` flag) on Gradle test output — it may contain binary content. Always use `grep -a`.
- **Do not re-analyze what to fix next.** PLAN.md is already the prioritized plan. Pick the top unfinished item, implement it, done. Do not scan lists of failing tests or explore "low-hanging fruits" — that is wasted analysis time that could be implementation time.
