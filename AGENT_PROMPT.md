# Agent Session Prompt — xemantic-typescript-compiler

Read this file, then immediately begin the work described below without asking questions.

## Your mission

Maximize the number of passing tests in this TypeScript-to-JavaScript transpiler project by fixing bugs in `Transformer.kt`, `Emitter.kt`, and `Parser.kt`. Work autonomously: pick a fix, implement it, run the full suite, commit if net-positive, repeat.

## Current state (2026-03-06)

**4,458 / 8,627 tests passing (51.7%)**

The pipeline: Scanner → Parser → Transformer → Emitter → BaselineFormatter.
Key files: `src/commonMain/kotlin/` — `Parser.kt`, `Transformer.kt`, `Emitter.kt`, `Ast.kt`, `BaselineFormatter.kt`.

## How to run tests

```bash
# Full suite (always rm binary cache first for reliable count):
rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest 2>&1 | grep -a "tests completed"

# Single test (get expected vs actual diff):
./gradlew jvmTest --tests '*.<TestName>*' 2>&1 | grep -a -A 40 "message" | head -50

# After editing a file, touch it to force Gradle to recompile:
touch src/commonMain/kotlin/Transformer.kt
```

Always use `grep -a` on Gradle output — it may contain binary content.

## What NOT to work on (out of scope)

- `.d.ts` declaration emit (~804 tests) — requires full type checker
- `outFile` bundling (~27 tests) — bundle mode not implemented
- Semantic error tests (3,214 tests) — require type checker

## Priority work (wave 8)

### 1. `__generator` async transform (~23 tests) — HIGH IMPACT

Async functions with `await` inside already get `__awaiter`. But the generator body needs `__generator` for anything beyond trivial `return` — TypeScript emits:

```js
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; t.pop(); break; }
                    if (t[3]) { _.label = t[3]; break; }
                    _ = ops.pop(); _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[2] ? op[1] : void 0, done: true };
    }
};
```

Start with: `./gradlew jvmTest --tests '*.asyncFunctionNoReturnType*'`

The body of the generator function needs its `yield` expressions wrapped in `__generator` calls. This is complex — see how TypeScript's emitter handles `yield` inside async bodies by transforming each `yield expr` to `yield __await(expr)` or labelled switch cases.

### 2. CommonJS import helpers (~70 tests) — MEDIUM IMPACT

Two sub-tasks:

**C3 — Import helpers** (`__importDefault`, `__importStar`, `__createBinding`, `__exportStar`):
Already partially emitted in `transformToCommonJS` in `Transformer.kt`. Check `makeImportHelperConst`. Some tests fail because the helper bodies aren't injected. Start with: `./gradlew jvmTest --tests '*.asyncImportNestedYield*'`

**C4 — Identifier rewriting** (~40 tests):
`import A from './a'` → all references to `A` become `a_1.default`. Requires a symbol table pass. Start with: `./gradlew jvmTest --tests '*.allowImportClausesToMergeWithTypes*'`

### 3. Misc small wins — LOW HANGING FRUIT

Check these patterns across failing tests (scan ~20 diffs quickly):

```bash
./gradlew jvmTest 2>&1 | grep -a "FAILED" | grep "_js" | head -30
# Then for each: ./gradlew jvmTest --tests '*.<name>*' 2>&1 | grep -a "^-\|^+" | grep -v "^---\|^+++" | head -15
```

Look for patterns affecting 3+ tests. Past wins came from single-line changes.

## Workflow rules

1. **Before any fix**: read the test source (`typescript-repo/tests/cases/compiler/<name>.ts`) and expected output (`typescript-repo/tests/baselines/reference/<name>.js`)
2. **After each fix**: run the full suite to confirm net positive before committing
3. **Commit format**:
```bash
git add <files> && git commit -m "$(cat <<'EOF'
fix: <short description>

<detail if needed>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```
4. **Push** after each successful commit: `git push`
5. **Update PLAN.md** with new test count after significant improvements

## Critical gotchas (from CLAUDE.md)

- `lookAhead` ALWAYS restores scanner state; `tryScan` keeps state if truthy — use correctly
- `getPos()` in Parser = start of CURRENT token; after `parseExpected()`, scanner already advanced to NEXT token
- Namespace/enum var dedup: `declaredNames` collects only non-`declare` class/function names
- Orphaned comments: only preserve if `source.substring(comment.end, stmt.pos).count { it == '\n' } >= 2`
- CommonJS transform applied AFTER all others; `isModuleFile` uses ORIGINAL source statements
- Kotlin 2.1+ `when` on enum: use unqualified entry names in branch conditions (e.g. `NewKeyword` not `SyntaxKind.NewKeyword`)
- Test results vary by ~4 between runs; run twice to confirm stable count
- `grep` on Gradle output MUST use `-a` flag

## Do not do

- Do not work on tests that need `.d.ts` output — skip if diff shows `//// [*.d.ts]` missing
- Do not create files unless absolutely necessary
- Do not add docstrings/comments to code you didn't change
- Do not run subagents — work directly in main branch to avoid worktree-nesting issues
