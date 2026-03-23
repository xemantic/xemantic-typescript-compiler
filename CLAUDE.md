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
- **Disabled test category** (re-enable when type checker is implemented): `.errors.txt` error baseline tests are commented out in `build.gradle.kts` `generateTypeScriptTests` task — search for `TODO: Re-enable when type checker is implemented`. The `.d.ts` guard (`hasDtsSection`) was removed in Phase 2 since `TypeScriptTestSupport.stripDtsSection()` already strips declaration output from baselines during comparison.
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

- **`if/else` formatting**: `emitEmbeddedStatement` writes a newline after block bodies. For `} else`, the `}` and `else` must be on the same line for non-multiline blocks. The `emitIfStatementCore` method handles this by NOT using `emitEmbeddedStatement` for the then-block when there's an else clause — it calls `emitBlockBody` directly and handles the newline/indentation for `else` itself.
- **Trailing CRLF in baselines**: The `formatBaseline` function adds trailing `\r\n` after JS output. The `toCRLF` conversion must normalize LF→CRLF.
- **Tab preservation in error squiggles**: The error baseline formatter must preserve tab characters from source lines in the squiggle indentation — tabs stay as tabs, other characters become spaces. Don't use `" ".repeat(col)`.
- **Numeric literal property access**: `1.foo` is ambiguous in JS (the `.` is a decimal point). Emit `1..foo` when the numeric literal has no decimal point, exponent, or `0x`/`0b`/`0o` prefix.
- **Labeled statement chaining**: TypeScript emits `target1: target2: stmt` all on one line. Use a `skipNextIndent` flag to suppress the body statement's `writeIndent()` call after writing all labels inline.
- **`emitPropertyAssignment` comment tracking**: After emitting `": "`, track `onNewLine` (bool). For each comment: if `hasPrecedingNewLine && !onNewLine` emit newline+indent first; then write comment; if `hasTrailingNewLine` emit newline+indent and set `onNewLine=true`, else write space and `onNewLine=false`. Never double-newline by emitting newline when already at line start.
- **JSX self-closing `/>` spacing**: TypeScript emits a space before `/>` only when there are NO attributes (`<Foo />`) but NOT when attributes are present (`<Foo bar="x"/>`).

- **Parameter comment comma-after format**: In `emitParameters`, the first parameter's leading JSDoc comment stays on the same line as `(` — only subsequent parameters get a newline before their comments. TypeScript emits `function foo(/** comment */ a,` not `function foo(\n/** comment */ a,`.
- **Yield expression operand parsing**: Use `isStartOfExpression()` check (not `!canParseSemicolon()`) to determine if yield has an operand. `canParseSemicolon()` returns false for `]` and `)` which would cause yield to try parsing them as expressions. TypeScript checks `!hasPrecedingLineBreak() && (asterisk || isStartOfExpression())`.

### Transformer gotchas

- **String enum syntactic reverse mapping**: Enum members with syntactically-string initializers (template literals, string concat, references to other string-valued members) skip reverse mapping: `Foo["A"] = value` instead of `Foo[Foo["A"] = value] = "A"`. The `isSyntacticallyStringEnum` function checks the expression form, not just constant evaluation.
- **Namespace/enum var dedup**: `declaredNames` set only collects non-`declare` class/function names (NOT enum/variable). Enums and namespaces with the same name as each other need their own var declarations.
- **Orphaned comments (erased declarations)**: Only preserve a leading comment from an erased declaration if there is a blank line (≥2 newlines) between the comment's `end` position and the declaration's `pos`. Adjacent comments (only one newline between them and the keyword) are considered part of the declaration and are dropped. Check: `source.substring(comment.end, stmt.pos).count { it == '\n' } >= 2`.
- **CommonJS transform**: Applied AFTER all other transforms. The `transformToCommonJS` receives already-transformed statements (so `ImportEqualsDeclaration` is already a `VariableStatement` with `require()` call). The `isModuleFile` check uses the ORIGINAL source file statements to detect module files.
- **CJS export void0 hoist batching**: TypeScript batches `exports.x = void 0` chains into groups of 50 to avoid deep expression trees. Without batching, files with thousands of exports (e.g., `manyConstExports` with 5000) cause StackOverflow in both Transformer `rewriteId` and Emitter `emitBinaryExpression`.
- **TypeScript DOES downlevel const/let to var for ES3/ES5**: User code `const`/`let` is emitted as `var` when target < ES2015. The Emitter handles this in `emitVariableDeclarationList`. The Transformer's synthesized code also uses `var` for ES5.
- **Property-to-constructor trailing comments**: When moving class property initializers to the constructor, copy `trailingComments` from the `PropertyDeclaration` to the generated `ExpressionStatement`.
- **Constructor prologue ordering**: When inserting parameter-property initializers into an existing constructor body (no `super()` call), find the end of the prologue-directive block first (`"use strict"`, `"ngInject"`, etc.) and insert AFTER it, not at index 0. Prologue directives are `ExpressionStatement` nodes whose expression is a `StringLiteralNode`.
- **Enum member comments**: Parser must capture `leadingComments()` and `scanner.getTrailingComments()` per enum member in `parseEnumDeclaration`. Transformer must then copy those to each generated `ExpressionStatement` in the IIFE body.
- **Type assertion parens**: `(<T>expr)` — the `()` are syntax for the assertion, not semantically required. The Transformer (not Emitter) must drop them: when `ParenthesizedExpression` wraps a type-erasure node, drop the parens unless the inner result is an `ObjectLiteralExpression`, `FunctionExpression`, `ClassExpression`, `ArrowFunction`, etc. Fix belongs in Transformer because `TypeAssertionExpression` is already stripped before Emitter sees it.
- **`new (<T>call())` semantics**: `new (A())` ≠ `new A()` — after stripping the type assertion, if the constructor expr becomes a `CallExpression`, it must be re-wrapped in `ParenthesizedExpression` to preserve the `new (expr)` form.

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

- **Damerau-Levenshtein not standard Levenshtein**: TypeScript's spelling suggestion algorithm counts transpositions (adjacent character swaps like `tupel`→`tuple`) as distance 1. Standard Levenshtein gives distance 2 for transpositions. Use optimal string alignment (restricted Damerau-Levenshtein) with the 2D DP table to match TypeScript's behavior.
- **Max 10 suggestions per name per file**: TypeScript limits TS2552 to 10 occurrences per unique unresolved name. Additional occurrences fall back to TS2304. Track counts in `spellingSuggestionCounts` map with key `"$fileName:$name"`.
- **Single-char names get suggestions**: Don't filter candidates by `length >= 2` — TypeScript suggests `A` for `a` (case-only mismatch, distance 0). The threshold `max(1, name.length / 3)` handles short names correctly.
- **`typeof a` in type constraints is value position**: `checkTypeQueryName` should use `inTypePosition = false` so TS2552 fires. The TS2662/TS2663 class-member suggestions are still safe because type constraints don't occur in class body scope where those trigger.
- **Type parameter constraints evaluated before function params**: Check type param constraints BEFORE calling `addParamsToScope`. For `function f<T extends typeof a>(a: T)`, `a` is not in scope during the constraint — moving `addParamsToScope` AFTER constraint checking allows TS2552 to fire for `a`.
- **TS2728 related info for file-level declarations only**: `findDeclarationRelatedInfo` looks up suggested names in binder locals (file-level). For function-scoped names (params, local vars) the position isn't tracked, so TS2728 is silently omitted — which matches TypeScript's behavior for those cases.
- **Gradle binary cache inconsistency**: When changes to Checker.kt don't seem to take effect during debugging, a full clean (`rm -rf build`) is needed. The binary test cache can keep stale results even after recompilation.
- **Ambient external modules not in scope**: `declare module "foo"` creates a symbol with string-literal name. The unquoted name `foo` used as an identifier gets TS2304 — TypeScript does NOT consider quoted module names as accessible identifiers. Exclude them from `fileScope` in `checkUnresolvedNames`.
- **NamespaceModule IS a valid suggestion candidate**: `declare namespace Foo` has `SymbolFlags.NamespaceModule` (not `SymbolFlags.Value`), but namespace names ARE usable at value positions (e.g. `Foo.bar`). Don't put them in `typeOnlyNames` — use `!sym.flags.hasAny(Value or Module)` to exclude only pure type declarations.
- **KNOWN_GLOBALS iterated first in spelling suggestions**: TypeScript's lib.d.ts globals are in the symbol table before file-local symbols. Iterating KNOWN_GLOBALS before scope chain ensures lib globals win ties over local declarations (e.g., `Lock` wins over `ELoc` for `loc`).

### Checker diagnostic gotchas (TS18004/TS1103)

- **Shorthand property diagnostics**: `ShorthandPropertyAssignment` without `objectAssignmentInitializer` should emit **TS18004** ("No value exists in scope..."), not **TS2304** ("Cannot find name..."). Handled by `checkShorthandPropertyResolved` in Checker.kt.
- **`for await` in non-async (TS1103)**: `ForOfStatement` with `awaitModifier` in non-async functions gets TS1103. The related TS1356 uses `FuncRef(pos, length)` to track enclosing function position — supports named functions, anonymous function expressions, and arrow functions.
- **`verbatimModuleSyntax` suppresses const enum inlining**: When set, skip `collectConstEnumValues`, skip checker's `resolveConstEnumMemberAccess`, keep const enum IIFE bodies, and don't treat const enums as type-only.

### TS2528 multiple default exports gotchas

- **Position rules**: `ExportAssignment` with identifier → error at identifier pos; with other expr → error at whole-statement start (full span). Named FunctionDeclaration/ClassDeclaration → error at name pos. Anonymous FD/CD → error at `export` keyword (NOT `function`/`class` — `FunctionDeclaration.pos` = `function` keyword, must search backwards for `export`).
- **2752/2753/6204 code rules**: All non-last exports point to the LAST export. The LAST export points to ALL previous. For 3+ defaults: first non-last → TS2753, subsequent non-lasts → TS6204 "and here.", last → TS2752 pointing to each previous. **EXCEPTION**: when the LAST export is a `FunctionDeclaration`, codes are swapped: non-lasts use TS2752 "The first export default is here.", and the last uses TS2753 "Another export default is here."
- **TS2323 vs TS2528 classification**: Use `DefaultDeclKind`: DECL (class/function/interface decls and value-identifier ExportAssignments), REEXPORT (ExportDeclaration `{ X as default }`), REF_TYPE (type-only identifier ExportAssignment), EXPR (non-identifier ExportAssignment). `emitTs2323 = declCount >= 2` (where declCount = DECL + REEXPORT). `emitTs2528 = hasNonDeclInline || !emitTs2323`. Both can fire simultaneously.
- **ExportDeclaration default specifier position**: `ExportSpecifier.name.pos` includes leading whitespace trivia — skip whitespace to get token start position for diagnostics.
- **`stmt.end` is lookahead position**: `ExportAssignment.end` points AFTER the next scanned token, not the end of the current statement. Compute span end from `expr.pos + expr.text.length` for Identifier expressions, or scan forward from `expr.pos` for complex expressions.

### Top-level await gotchas

- **Parser `topLevelAwait` parameter**: When `module` is ES2022+, NodeNext, Preserve, or System, the Parser's `topLevelAwait` flag must be set to `true`. This sets initial `inAsyncContext = true` at the file level, enabling `await` keyword recognition at the top level. Inside function bodies, `inAsyncContext` is still reset per-function based on `async` modifier. In sync functions, `await(x)` remains a call expression (identifier).

### Const enum type-only treatment gotchas

- **Erased const enums in pre-scans**: Const enums (without `preserveConstEnums`/`isolatedModules`/`verbatimModuleSyntax`) must be treated as type-only in ALL pre-scan passes: `earlyPureTypeNames` (for `hasExportEquals` detection), `pureTypeNames` (for export specifier elision), and `directExportedVarNames` (for identifier rewriting). Without this, `export = ConstEnum` incorrectly generates `return E` instead of `Object.defineProperty(exports, "__esModule", ...)`.
- **Imported const enum imports also need type-only treatment**: The `topLevelTypeOnlyNames` and `pureTypeNames` pre-scans must also mark imported const enum names as type-only (not just locally-declared ones). Check `checker?.isConstEnumAlias(name, currentFileName) == true` for each non-type-only import specifier.
- **Binder `export { X }` must not overwrite value symbols**: For local re-exports without `from` clause, `bindExportDeclaration` should NOT create a new Alias symbol that overwrites an existing value symbol (e.g., `declare var b; export { b }` — the var symbol must survive). Skip creating an Alias if the name is already declared as a value.
- **`.d.ts` module resolution only for relative specifiers**: `resolveModuleSpecifier` should only try `.d.ts` extension for relative specifiers (`./X` or `../X`). Non-relative specifiers like `"foo"` might find `foo.d.ts` which has ambient module declarations — but those may be augmented in other files (module augmentation), making TS2694 checks unreliable. Limiting `.d.ts` resolution to relative paths avoids FPs.
- **Ambient module `.d.ts` exports use inner module exports**: When resolving a namespace import to a `.d.ts` file with a single `declare module "X" {}`, the module symbol's exports should come from that ambient module's exports, NOT from the file-level locals. `createModuleSymbol` checks for this pattern.
- **Default import resolution chain**: For `import Foo from "./mod"`, resolve in order: (1) `targetResult.locals["default"]`, (2) scan `ExportAssignment` (not `isExportEquals`) nodes for `export default X`, (3) scan `export { X as default } from "mod"` specifiers.

### Decorator metadata type serialization gotchas

- **`null`/`undefined`/`never` → `void 0`**: Standalone null/undefined/never type nodes and unions that filter down to all-nullish serialize as `VoidExpression(0)`, not `Object`. `never` is always excluded from unions; `null`/`undefined` only filtered when `!strictNullChecks`.
- **Numeric enum type serialization**: `E.A` (QualifiedName) and plain `E` where E is a numeric enum → `Number`. Requires `checker.isNumericEnumType(name, fileName)`. String enums → `Object`.
- **Class-level `__decorate` includes constructor paramtypes**: When `emitDecoratorMetadata` is true and a class has decorators, the class-level `__decorate([...], ClassName)` call must include `__metadata("design:paramtypes", [...])` for constructor parameters. Pass `constructorParams` to `generateClassDecorateStatement`.
- **Type-only export cross-file detection**: `export type { Foo }` in `a.ts` makes `Foo` type-only when imported in `b.ts` even without `import type { Foo }`. Our checker's `isValueExport` looks at the symbol's flags (Class → Value → true) and doesn't detect type-only export specifiers. Fix requires tracking per-name type-only exports in the binder.
- **Default import metadata safety wrapper**: When `emitDecoratorMetadata=true` and a constructor param type is a qualified name from a default import (`db_1.default.Foo`), TypeScript wraps it: `typeof (_a = typeof db_1.default !== "undefined" && db_1.default.Foo) === "function" ? _a : Object`. The `var _a;` is hoisted before `Object.defineProperty`. Track `defaultModuleTempVars` during import processing; post-process `__metadata` args AFTER `rewriteIdInStatement`; insert `var _a;` at index 0 directly (not via `sideEffectTempVars` which is already processed).
- **`makeImportHelperConst` trailing comments**: Add a `trailingComments` parameter and pass `stmt.trailingComments` at the call site — same as `makeRequireConst` already does.
- **`export type { Foo }` makes imports type-only**: When `a.ts` has `export type { Foo }` (not `export { Foo }`), imports in `b.ts` via `import { Foo } from "./a"` are effectively type-only even without `import type`. Check `checker.isTypeOnlyExportName(name, spec, file)` during the `topLevelTypeOnlyNames` and `pureTypeNames` pre-scans. `isTypeOnlyExportName` scans the target file's `ExportDeclaration`s for the name and checks `stmt.isTypeOnly || spec.isTypeOnly`.
- **`export { Foo }` of type-alias is type-only**: Even without `export type`, exporting a `type Foo = ...` via `export { Foo }` should cause `import { Foo } from "./a"` to be type-only. Check `checker.isValueExport(exportedName, moduleSpec, file) == false` in the `topLevelTypeOnlyNames` and `pureTypeNames` pre-scans.
- **`resolveModuleSpecifier` for absolute-path test files**: Multi-file tests using `@Filename: /foo.ts` (absolute paths) need `fileBase == "/$baseName"` check in `resolveModuleSpecifier` to match relative specifiers `"./foo"` to `/foo.ts`. The `endsWith("/$baseName")` pattern is too broad (can match nested paths). Use `fileBase == "/$baseName"` for relative specifiers where `fileBase.startsWith("/")`.
- **`isValueExport` returns `true` (safe default)**: When the checker can't resolve the module or symbol, `isValueExport` returns `true` (keep the import). Only return `false` when definitively type-only (TypeAlias, Interface, non-instantiated namespace).

### TS2454/TS2564 gotchas

- **`any` type skips TS2454/TS2564**: Variables and properties typed as `any` don't need definite assignment checking because `any` includes `undefined`.
- **`var` declarations and TS2454**: Unlike `let`/`const`, TypeScript DOES check `var` declarations for TS2454 (when strict). Only `any`-typed vars are skipped.

### Kotlin idioms

- **No non-stdlib dependencies in `commonMain`**: The project targets Kotlin Native (in addition to JVM/JS), so `commonMain` must use only `kotlin.*` and `kotlinx.*` packages. No `java.*`, no `BigDecimal`, no JVM-only types. Use Kotlin's built-in numeric types and stdlib math (`kotlin.math.*`). The `feat/kt-changes` branch removed the last BigDecimal usage specifically to enable Native compilation.
- **Enum context resolution** (Kotlin 2.1+): When the expected type is an enum, use unqualified entry names — write `Equals`, not `SyntaxKind.Equals`. This applies to `when` branch conditions, named arguments (`operator = Equals`), comparisons (`flags == VarKeyword`), and any other position where the enum type is inferred. Caveat: if a data class has the same name as an enum entry (e.g. `LabeledStatement`), keep the `SyntaxKind.` prefix to avoid ambiguity.
- **`in 0..<x` range checks**: Prefer `pos in 0..<end` over `pos >= 0 && end > pos` for range validation — uses Kotlin's `rangeUntil` (`..<`) operator for exclusive upper bound.
- **No JVM-only APIs in `commonMain`**: `Map.putIfAbsent` → use `getOrPut`; `Math.pow` → use `kotlin.math.pow` extension. Always use Kotlin stdlib equivalents for multiplatform compatibility.
- **`when` guard conditions** (Kotlin 2.1+): Use `when (val ch = x) { '/' if condition -> ... }` instead of `when { ch == '/' && condition -> ... }`. The `if` guard after the match value keeps pattern matching readable and avoids nested `when`/`if` blocks.

## AI agent mission

**Phase 3m: False positive reduction and diagnostic precision.** The pipeline is: Scanner → Parser → **Binder → Checker** → Transformer → Emitter. The Checker emits diagnostics: TS6133/TS6196/TS6199 (unused), TS2454/TS2564 (definite assignment), TS7006 (implicit any), TS2304 (cannot find name), TS2552 (spelling suggestions), TS2300/TS2567 (duplicates), TS7026 (JSX), TS2309 (export conflicts), TS1100/TS1105/TS1104/TS1115/TS1116/TS1117 (syntax), TS2314 (type arg count), TS2683 (implicit this), TS2389/TS2391 (overloads), TS17009 (super before this), TS2588 (const assignment), TS2369 (parameter property), TS5101/TS5102/TS5107/TS5108 (deprecation), TS6082/TS5069/TS5070/TS5071/TS5095/TS5053/TS5055/TS5110 (options), TS2695 (comma operator), TS2448/TS2449/TS2450 (use before decl), TS2396 (arguments collision), TS1029/TS1030/TS1036/TS1039/TS1049/TS1052/TS1090/TS1113/TS1183/TS1212/TS1213/TS1218/TS1308 (syntax), TS1015/TS2371 (param restrictions), TS2377 (super call), TS2397/TS2414/TS2427 (reserved names), TS2528 (multi-default export), TS2882 (side-effect imports), TS2694 (namespace export). **7,453 / 10,077 tests passing (73.9%)**, up from 7,148/10,120 (session 2026-03-21b). ES5/ES3/AMD/System/UMD JS emit tests skipped (475 tests removed — deprecated in TypeScript 6.0, removed in 7.0/tsgo). Key remaining work: type inference diagnostics (TS2322, TS2339, TS2345), CJS export qualification, decorator metadata.

### Execution protocol (MANDATORY — follow exactly)

PLAN.md contains a **QUEUE** — a numbered list of tasks in order. Execute top-to-bottom:

1. Find the first unchecked (`- [ ]`) item in the QUEUE
2. Implement it — the item describes the deliverable
3. Run the full suite (`./gradlew jvmTest 2>&1 | grep -a "tests completed"`)
4. Verify no regressions from the **7,417 currently passing tests**
5. Check off the item (`- [x]`), add CLAUDE.md gotcha if applicable, commit and push
6. If the queue is empty or all remaining items are blocked/skipped: stop and wait for instructions

**HARD RULES:**
- **Do NOT skip ahead** in the queue — work item 0 before item 1, always.
- **Do NOT switch items** mid-task — finish the current item before moving on.
- **Analysis items** (item 0) should produce written artifacts (design docs, categorized lists) before any code is written.
- **Infrastructure items** (items 1-3) are foundational — correctness matters more than speed. Read TypeScript's architecture first.
- **No regressions** — the 7,417 currently passing tests must continue to pass after every change.

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

Example wave grouping for this codebase:
- **Wave 1 (parallel):** `"use strict"` (TypeScriptCompiler.kt), AMD format (new Transformer fn), `removeComments` (Emitter.kt guards)
- **Wave 2 (parallel):** `export {}` (Transformer), binary-op comments (Emitter), yield comments (Parser)
- **Wave 3 (sequential):** CommonJS improvements (deep Transformer rewrite)

### Merge workflow (between waves)

After all subagents in a wave complete, merge their worktree branches sequentially into `main`:

```bash
git fetch
git merge <worktree-branch> --no-ff -m "merge: task <X> fix"
# Conflicts are typically in different functions of the same file — resolve manually
git push
```

### Context discipline

- Keep this file and `PLAN.md` up to date after each session so the next agent/developer starts with accurate state
- `PLAN.md` contains the prioritized fix plan with root cause categories, estimated test impact, and recommended fix order

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
