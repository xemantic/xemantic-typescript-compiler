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
- **Disabled test categories** (re-enable when type checker is implemented): Two test categories are commented out in `build.gradle.kts` `generateTypeScriptTests` task: (1) `.errors.txt` error baseline tests — search for `TODO: Re-enable when type checker is implemented`, (2) `.d.ts` declaration emit tests — search for `hasDtsSection`. Uncomment/remove those guards and regenerate with `./gradlew generateTypeScriptTests`.
- Kotlin 2.x disallows `.` in backtick-quoted JVM method names (error: "Name contains illegal characters: .."); sanitize test function names by replacing dots with underscores (e.g. `foo_ts` not `foo.ts`). Some TypeScript test base names contain dots beyond the extension (e.g. `accessors_spec_section-4.5_error-cases`), so the entire `nameWithoutExtension` must have dots replaced, not just the `.ts`/`.js` suffix.

### Scanner/Parser gotchas

- **`lookAhead` vs `tryScan`**: `lookAhead` ALWAYS restores scanner state. `tryScan` keeps scanner state if callback returns truthy. Use `lookAhead` for probe-and-decide patterns; use `tryScan` for "try to parse, keep results if success."
- **`reScanGreaterToken`** (splitting `>>` to `>` for nested generics) was implemented but caused 4-test net regression — left disabled in the parser. The implementation in `Scanner.kt` is correct; the issue is likely subtle interaction with `tryScan` nesting. Re-enable cautiously.
- **`getPos()` in Parser** = `scanner.getTokenPos()` (start of current token), **`getEnd()`** = `scanner.getPos()` (end of current token text). After `parseExpected()`, the scanner has ALREADY advanced to the next token — so `getPos()` is the start of the NEXT token.
- **Case clause singleLine detection**: Check `source.substring(caseStartPos, firstStmtStart).contains('\n')` to determine if statements are on the same line as `case:`. Do NOT use `scanner.getPos()` after `parseExpected(Colon)` since the scanner has advanced past the colon into the next token's trivia.

### Emitter gotchas

- **`if/else` formatting**: `emitEmbeddedStatement` writes a newline after block bodies. For `} else`, the `}` and `else` must be on the same line for non-multiline blocks. The `emitIfStatementCore` method handles this by NOT using `emitEmbeddedStatement` for the then-block when there's an else clause — it calls `emitBlockBody` directly and handles the newline/indentation for `else` itself.
- **Trailing CRLF in baselines**: The `formatBaseline` function adds trailing `\r\n` after JS output. The `toCRLF` conversion must normalize LF→CRLF.
- **Numeric literal property access**: `1.foo` is ambiguous in JS (the `.` is a decimal point). Emit `1..foo` when the numeric literal has no decimal point, exponent, or `0x`/`0b`/`0o` prefix.
- **Labeled statement chaining**: TypeScript emits `target1: target2: stmt` all on one line. Use a `skipNextIndent` flag to suppress the body statement's `writeIndent()` call after writing all labels inline.
- **`emitPropertyAssignment` comment tracking**: After emitting `": "`, track `onNewLine` (bool). For each comment: if `hasPrecedingNewLine && !onNewLine` emit newline+indent first; then write comment; if `hasTrailingNewLine` emit newline+indent and set `onNewLine=true`, else write space and `onNewLine=false`. Never double-newline by emitting newline when already at line start.

### Transformer gotchas

- **Namespace/enum var dedup**: `declaredNames` set only collects non-`declare` class/function names (NOT enum/variable). Enums and namespaces with the same name as each other need their own var declarations.
- **Orphaned comments (erased declarations)**: Only preserve a leading comment from an erased declaration if there is a blank line (≥2 newlines) between the comment's `end` position and the declaration's `pos`. Adjacent comments (only one newline between them and the keyword) are considered part of the declaration and are dropped. Check: `source.substring(comment.end, stmt.pos).count { it == '\n' } >= 2`.
- **CommonJS transform**: Applied AFTER all other transforms. The `transformToCommonJS` receives already-transformed statements (so `ImportEqualsDeclaration` is already a `VariableStatement` with `require()` call). The `isModuleFile` check uses the ORIGINAL source file statements to detect module files.
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

### Multi-file baseline gotchas

- **`tsconfig.json` not echoed**: The TypeScript test harness treats `tsconfig.json` as project configuration, not a source file. Never include it in the `sourceEchoes` list in `formatMultiFileBaseline`. Other JSON files (e.g. `tsconfig1.json`) ARE echoed.
- **`const enum` at statement level**: `parseStatement()` must check for `const enum` (not just inside `export`/`declare` contexts) — otherwise `const enum E {}` is misparse as `const` variable named `enum` + expression `E` + block.

### Test assertion gotchas

- Avoid partial `assert("x" in result)` — always assert the full expected output.

### Kotlin idioms

- **Enum context resolution** (Kotlin 2.1+): When the expected type is an enum, use unqualified entry names — write `Equals`, not `SyntaxKind.Equals`. This applies to `when` branch conditions, named arguments (`operator = Equals`), comparisons (`flags == VarKeyword`), and any other position where the enum type is inferred. Caveat: if a data class has the same name as an enum entry (e.g. `LabeledStatement`), keep the `SyntaxKind.` prefix to avoid ambiguity.
- **`in 0..<x` range checks**: Prefer `pos in 0..<end` over `pos >= 0 && end > pos` for range validation — uses Kotlin's `rangeUntil` (`..<`) operator for exclusive upper bound.
- **No JVM-only APIs in `commonMain`**: `Map.putIfAbsent` → use `getOrPut`; `Math.pow` → use `kotlin.math.pow` extension. Always use Kotlin stdlib equivalents for multiplatform compatibility.
- **`when` guard conditions** (Kotlin 2.1+): Use `when (val ch = x) { '/' if condition -> ... }` instead of `when { ch == '/' && condition -> ... }`. The `if` guard after the match value keeps pattern matching readable and avoids nested `when`/`if` blocks.

## AI agent mission

Maximize the number of passing tests by fixing bugs in `Transformer.kt`, `Emitter.kt`, and `Parser.kt`. The pipeline: Scanner → Parser → Transformer → Emitter → BaselineFormatter. Key files live in `src/commonMain/kotlin/`.

### Execution protocol (MANDATORY — follow exactly)

PLAN.md contains a **QUEUE** — a numbered list of fixes in strict order. Execute them top-to-bottom:

1. Find the first unchecked (`- [ ]`) item in the QUEUE
2. Implement it — the item already names the test(s), file, and fix area
3. Run the full suite (`./gradlew jvmTest 2>&1 | grep -a "tests completed"`)
4. If net-positive: check off the item (`- [x]`), add CLAUDE.md gotcha if applicable, commit and push
5. If net-negative after **2 attempts**: mark the item `- [S]` (skipped), commit nothing, move to the next item
6. If the queue is empty or all remaining items are blocked/skipped: stop and wait for instructions

**HARD RULES:**
- **Do NOT re-analyze or re-prioritize the queue.** The analysis is already done. Just execute.
- **Do NOT search for "easier" or "better" fixes** outside the queue. The queue IS the plan.
- **Do NOT spend more than 2 tool calls on analysis** before writing code. Read the failing test diff, read the fix area, start coding.
- **Do NOT skip ahead** in the queue — work item 1 before item 2, always.
- **Do NOT switch items** mid-fix — finish or skip the current item before moving on.
- One item at a time. No parallelism within a single session unless using subagent worktrees.

### Reference TypeScript sources

The original TypeScript compiler source is in `typescript-repo/src/compiler/`. When a fix is ambiguous or behavior is unclear, read the corresponding TypeScript source file (e.g. `typescript-repo/src/compiler/emitter.ts`, `parser.ts`, `transformer.ts`, `factory/emitHelpers.ts`) to verify the Kotlin implementation against the original. The 1M context window can accommodate these files.

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
# Full suite:
./gradlew jvmTest 2>&1 | grep -a "tests completed"

# Single test (get expected vs actual diff):
./gradlew jvmTest --tests '*.<TestName>*' 2>&1 | grep -a -A 40 "message" | head -50
```

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
- Do not use `grep` (without `-a` flag) on Gradle test output — it may contain binary content. Always use `grep -a`.
- **Do not re-analyze what to fix next.** PLAN.md is already the prioritized plan. Pick the top unfinished item, implement it, done. Do not scan lists of failing tests or explore "low-hanging fruits" — that is wasted analysis time that could be implementation time.
