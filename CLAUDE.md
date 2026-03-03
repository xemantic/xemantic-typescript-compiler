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
- Kotlin 2.x disallows `.` in backtick-quoted JVM method names (error: "Name contains illegal characters: .."); sanitize test function names by replacing dots with underscores (e.g. `foo_ts` not `foo.ts`). Some TypeScript test base names contain dots beyond the extension (e.g. `accessors_spec_section-4.5_error-cases`), so the entire `nameWithoutExtension` must have dots replaced, not just the `.ts`/`.js` suffix.

### Scanner/Parser gotchas
- **`lookAhead` vs `tryScan`**: `lookAhead` ALWAYS restores scanner state. `tryScan` keeps scanner state if callback returns truthy. Use `lookAhead` for probe-and-decide patterns; use `tryScan` for "try to parse, keep results if success."
- **`reScanGreaterToken`** (splitting `>>` to `>` for nested generics) was implemented but caused 4-test net regression — left disabled in the parser. The implementation in `Scanner.kt` is correct; the issue is likely subtle interaction with `tryScan` nesting. Re-enable cautiously.
- **`getPos()` in Parser** = `scanner.getTokenPos()` (start of current token), **`getEnd()`** = `scanner.getPos()` (end of current token text). After `parseExpected()`, the scanner has ALREADY advanced to the next token — so `getPos()` is the start of the NEXT token.
- **Case clause singleLine detection**: Check `source.substring(caseStartPos, firstStmtStart).contains('\n')` to determine if statements are on the same line as `case:`. Do NOT use `scanner.getPos()` after `parseExpected(Colon)` since the scanner has advanced past the colon into the next token's trivia.

### Emitter gotchas
- **`if/else` formatting**: `emitEmbeddedStatement` writes a newline after block bodies. For `} else`, the `}` and `else` must be on the same line for non-multiline blocks. The `emitIfStatementCore` method handles this by NOT using `emitEmbeddedStatement` for the then-block when there's an else clause — it calls `emitBlockBody` directly and handles the newline/indentation for `else` itself.
- **Trailing CRLF in baselines**: The `formatBaseline` function adds trailing `\r\n` after JS output. The `toCRLF` conversion must normalize LF→CRLF.

### Transformer gotchas
- **Namespace/enum var dedup**: `declaredNames` set only collects non-`declare` class/function names (NOT enum/variable). Enums and namespaces with the same name as each other need their own var declarations.
- **CommonJS transform**: Applied AFTER all other transforms. The `transformToCommonJS` receives already-transformed statements (so `ImportEqualsDeclaration` is already a `VariableStatement` with `require()` call). The `isModuleFile` check uses the ORIGINAL source file statements to detect module files.
- **Property-to-constructor trailing comments**: When moving class property initializers to the constructor, copy `trailingComments` from the `PropertyDeclaration` to the generated `ExpressionStatement`.

### Kotlin idioms

- **Enum context resolution in `when`** (Kotlin 2.1+): When a `when` subject is an enum type, use unqualified entry names in branch conditions — write `NewKeyword`, not `SyntaxKind.NewKeyword`. Caveat: if a data class has the same name as an enum entry (e.g. `LabeledStatement`), keep the `SyntaxKind.` prefix to avoid ambiguity. This only applies to branch conditions, not expressions inside branch bodies.
- **`in 0..<x` range checks**: Prefer `pos in 0..<end` over `pos >= 0 && end > pos` for range validation — uses Kotlin's `rangeUntil` (`..<`) operator for exclusive upper bound.

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
- Do not use `grep` (without `-a` flag) on Gradle test output — it may contain binary content. Always use `grep -a`.
