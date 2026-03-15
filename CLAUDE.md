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
- **Disabled test category** (re-enable when type checker is implemented): `.errors.txt` error baseline tests are commented out in `build.gradle.kts` `generateTypeScriptTests` task — search for `TODO: Re-enable when type checker is implemented`. The `.d.ts` guard (`hasDtsSection`) was removed in Phase 2 since `TypeScriptTestSupport.stripDtsSection()` already strips declaration output from baselines during comparison.
- Kotlin 2.x disallows `.` in backtick-quoted JVM method names (error: "Name contains illegal characters: .."); sanitize test function names by replacing dots with underscores (e.g. `foo_ts` not `foo.ts`). Some TypeScript test base names contain dots beyond the extension (e.g. `accessors_spec_section-4.5_error-cases`), so the entire `nameWithoutExtension` must have dots replaced, not just the `.ts`/`.js` suffix.

### Scanner/Parser gotchas

- **`lookAhead` vs `tryScan`**: `lookAhead` ALWAYS restores scanner state. `tryScan` keeps scanner state if callback returns truthy. Use `lookAhead` for probe-and-decide patterns; use `tryScan` for "try to parse, keep results if success."
- **`reScanGreaterToken`** (splitting `>>` to `>` for nested generics) was implemented but caused 4-test net regression — left disabled in the parser. The implementation in `Scanner.kt` is correct; the issue is likely subtle interaction with `tryScan` nesting. Re-enable cautiously.
- **`getPos()` in Parser** = `scanner.getTokenPos()` (start of current token), **`getEnd()`** = `scanner.getPos()` (end of current token text). After `parseExpected()`, the scanner has ALREADY advanced to the next token — so `getPos()` is the start of the NEXT token.
- **Case clause singleLine detection**: Check `source.substring(caseStartPos, firstStmtStart).contains('\n')` to determine if statements are on the same line as `case:`. Do NOT use `scanner.getPos()` after `parseExpected(Colon)` since the scanner has advanced past the colon into the next token's trivia.

### Emitter gotchas

- **`if/else` formatting**: `emitEmbeddedStatement` writes a newline after block bodies. For `} else`, the `}` and `else` must be on the same line for non-multiline blocks. The `emitIfStatementCore` method handles this by NOT using `emitEmbeddedStatement` for the then-block when there's an else clause — it calls `emitBlockBody` directly and handles the newline/indentation for `else` itself.
- **Trailing CRLF in baselines**: The `formatBaseline` function adds trailing `\r\n` after JS output. The `toCRLF` conversion must normalize LF→CRLF.
- **Tab preservation in error squiggles**: The error baseline formatter must preserve tab characters from source lines in the squiggle indentation — tabs stay as tabs, other characters become spaces. Don't use `" ".repeat(col)`.
- **Numeric literal property access**: `1.foo` is ambiguous in JS (the `.` is a decimal point). Emit `1..foo` when the numeric literal has no decimal point, exponent, or `0x`/`0b`/`0o` prefix.
- **Labeled statement chaining**: TypeScript emits `target1: target2: stmt` all on one line. Use a `skipNextIndent` flag to suppress the body statement's `writeIndent()` call after writing all labels inline.
- **`emitPropertyAssignment` comment tracking**: After emitting `": "`, track `onNewLine` (bool). For each comment: if `hasPrecedingNewLine && !onNewLine` emit newline+indent first; then write comment; if `hasTrailingNewLine` emit newline+indent and set `onNewLine=true`, else write space and `onNewLine=false`. Never double-newline by emitting newline when already at line start.
- **JSX self-closing `/>` spacing**: TypeScript emits a space before `/>` only when there are NO attributes (`<Foo />`) but NOT when attributes are present (`<Foo bar="x"/>`).

### Transformer gotchas

- **Namespace/enum var dedup**: `declaredNames` set only collects non-`declare` class/function names (NOT enum/variable). Enums and namespaces with the same name as each other need their own var declarations.
- **Orphaned comments (erased declarations)**: Only preserve a leading comment from an erased declaration if there is a blank line (≥2 newlines) between the comment's `end` position and the declaration's `pos`. Adjacent comments (only one newline between them and the keyword) are considered part of the declaration and are dropped. Check: `source.substring(comment.end, stmt.pos).count { it == '\n' } >= 2`.
- **CommonJS transform**: Applied AFTER all other transforms. The `transformToCommonJS` receives already-transformed statements (so `ImportEqualsDeclaration` is already a `VariableStatement` with `require()` call). The `isModuleFile` check uses the ORIGINAL source file statements to detect module files.
- **CJS export void0 hoist batching**: TypeScript batches `exports.x = void 0` chains into groups of 50 to avoid deep expression trees. Without batching, files with thousands of exports (e.g., `manyConstExports` with 5000) cause StackOverflow in both Transformer `rewriteId` and Emitter `emitBinaryExpression`.
- **TypeScript does NOT downlevel const/let to var**: Even for target=ES3/ES5, user code keeps `const`/`let`. Only the compiler's OWN synthesized code (CJS `require()` statements, helper variables) uses `var` for ES5. Do not add a general const/let→var transform.
- **Property-to-constructor trailing comments**: When moving class property initializers to the constructor, copy `trailingComments` from the `PropertyDeclaration` to the generated `ExpressionStatement`.
- **Constructor prologue ordering**: When inserting parameter-property initializers into an existing constructor body (no `super()` call), find the end of the prologue-directive block first (`"use strict"`, `"ngInject"`, etc.) and insert AFTER it, not at index 0. Prologue directives are `ExpressionStatement` nodes whose expression is a `StringLiteralNode`.
- **Enum member comments**: Parser must capture `leadingComments()` and `scanner.getTrailingComments()` per enum member in `parseEnumDeclaration`. Transformer must then copy those to each generated `ExpressionStatement` in the IIFE body.
- **Type assertion parens**: `(<T>expr)` — the `()` are syntax for the assertion, not semantically required. The Transformer (not Emitter) must drop them: when `ParenthesizedExpression` wraps a type-erasure node, drop the parens unless the inner result is an `ObjectLiteralExpression`, `FunctionExpression`, `ClassExpression`, `ArrowFunction`, etc. Fix belongs in Transformer because `TypeAssertionExpression` is already stripped before Emitter sees it.
- **`new (<T>call())` semantics**: `new (A())` ≠ `new A()` — after stripping the type assertion, if the constructor expr becomes a `CallExpression`, it must be re-wrapped in `ParenthesizedExpression` to preserve the `new (expr)` form.

### Module detection gotchas

- **`moduleDetection: "force"`** makes ALL files modules regardless of content. Both `isModuleFile()` (Transformer) and `hasModuleStatements()` (Emitter) check for this.
- **`.mts`/`.mjs`/`.cts`/`.cjs` extensions** are always module files. Both functions also check for these extensions.
- **`isolatedModules: true` ≠ `moduleDetection: force`**: `isolatedModules` tells TypeScript to check each file independently but does NOT force module treatment for files without imports/exports. Adding `isModuleFile = true` for `isolatedModules` causes regressions.
- **JSON files re-emitted with trailing comma stripping**: `TypeScriptCompiler.kt` strips trailing commas from JSON output using `stripJsonTrailingCommas()` since TypeScript parses and re-emits JSON, naturally removing them.

### Checker gotchas

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

### Checker diagnostic gotchas (TS2454/TS2564/TS6133)

- **`strict: false` suppresses TS2454/TS2564**: TypeScript test baselines expect these diagnostics by default, but NOT when `@strict: false` is explicitly set. Use `options.strictExplicitlyFalse` to distinguish default-false from explicit-false.
- **Definite assignment assertion `!`**: `var x!: Type` and class property `x!: Type` skip TS2454/TS2564 checking. Check `exclamationToken` on both `VariableDeclaration` and `PropertyDeclaration`.
- **Ambient contexts**: Skip TS2454/TS2564 in `declare` namespaces, classes, and functions. A class inside a `declare namespace` is ambient even if the class itself doesn't have `declare`.
- **TS6133 non-module files**: Don't check file-level unused declarations in non-module files (no imports/exports). Only check inside namespaces, functions, blocks.
- **TS6133 write-only**: Assignment targets (`x = value`) are NOT reads. Left side of `=` operator is write-only. Compound assignments (`x += 1`) ARE reads.

### Test assertion gotchas

- Avoid partial `assert("x" in result)` — always assert the full expected output.

### TS2304 unresolved name checking gotchas

- **Kotlin property initialization order**: Properties declared after `init {}` have default values (0 for Int) during init execution. The `maxCheckDepth` and `checkDepth` variables MUST be declared BEFORE the `init` block, not after, or the depth limit will be 0 and all checking will be skipped.
- **KNOWN_GLOBALS coverage**: ~400 lib.d.ts names in the companion object. Missing a global causes false positive TS2304. When adding new globals, check both value and type positions.
- **KEYWORD_IDENTIFIERS**: Our parser produces `Identifier` nodes for `this`, `super`, `true`, `false`, `null`, TypeScript type keywords (`any`, `number`, `string`, etc.), and access modifiers (`public`, `private`, etc.). These must be excluded from TS2304 checking.
- **Binder scope model**: The binder only creates file-level symbol tables. Function parameters, catch variables, and block-scoped declarations are NOT in the binder's symbol table. The TS2304 checker maintains its own scope chain.
- **Test generator only processes `.ts`**: `.tsx` files are excluded from test generation (`f.extension == "ts"` in build.gradle.kts). This means JSX-related diagnostics (TS7026) are untestable.
- **`arguments` not a global**: Don't include `arguments` in KNOWN_GLOBALS — it's only available inside non-arrow functions via the `hasArguments` flag on NameScope.
- **`var` hoisting for TS2304**: `var` declarations inside nested blocks/loops are function-scoped. `collectHoistedVarNames` recursively finds them and adds to the enclosing scope.
- **`isIdentifier()` vs `isIdentifierToken()`**: Inside `scanner.lookAhead {}`, use `isIdentifierToken(scanner.getToken())` not `isIdentifier()` — the latter checks the Parser's cached `token` field which isn't updated inside lookAhead.

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

**Phase 3c: Incremental Diagnostic & Emit Improvements.** The pipeline is: Scanner → Parser → **Binder → Checker** → Transformer → Emitter. The Checker emits diagnostics: TS6133/TS6196 (unused declarations + type params), TS2454 (used before assigned), TS2564 (property no initializer), TS7006 (implicit any parameter), TS2304 (cannot find name), TS2300 (duplicate identifier + class members + export=), TS7026 (JSX implicit any), plus TS5101/TS5102/TS5107 (deprecation), TS6082/TS5069/TS5070/TS5071/TS5095/TS5053/TS5055 (option validation). **6,720 / 10,595 tests passing (63.4%)**, up from 6,561. Key remaining work: type inference diagnostics (TS2322, TS2339, TS2345).

### Execution protocol (MANDATORY — follow exactly)

PLAN.md contains a **QUEUE** — a numbered list of tasks in order. Execute top-to-bottom:

1. Find the first unchecked (`- [ ]`) item in the QUEUE
2. Implement it — the item describes the deliverable
3. Run the full suite (`./gradlew jvmTest 2>&1 | grep -a "tests completed"`)
4. Verify no regressions from the **6,219 currently passing tests**
5. Check off the item (`- [x]`), add CLAUDE.md gotcha if applicable, commit and push
6. If the queue is empty or all remaining items are blocked/skipped: stop and wait for instructions

**HARD RULES:**
- **Do NOT skip ahead** in the queue — work item 0 before item 1, always.
- **Do NOT switch items** mid-task — finish the current item before moving on.
- **Analysis items** (item 0) should produce written artifacts (design docs, categorized lists) before any code is written.
- **Infrastructure items** (items 1-3) are foundational — correctness matters more than speed. Read TypeScript's architecture first.
- **No regressions** — the 6,700 currently passing tests must continue to pass after every change.

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
