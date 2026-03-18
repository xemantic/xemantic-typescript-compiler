# Phase 3 Plan — Test Infrastructure & Diagnostic-Driven Type Checker

**Prerequisite:** Phase 2 (complete) built the Binder, Checker, and Types infrastructure.
The pipeline is: Scanner → Parser → Binder → Checker → Transformer → Emitter.

**Phase 3 goal:** Make `.errors.txt` baseline tests the primary metric driving type checker
development, and bring the full TypeScript compiler test suite into the build.

**Reference:** `TYPESCRIPT-TEST-HARNESS.md` documents the original TypeScript test harness
behavior — baseline formats, comparison algorithm, and parameterized test expansion.

---

## Current State

- **10,595 tests**, 6,962 passing (65.7%), 3,633 failing
- **Session 2026-03-18b**: +20 tests (6,942→6,962) — fix false-positive TS1005, add TS6131, TS7019, TS1185
- **Session 2026-03-18**: +47 tests (6,892→6,939) — new diagnostics TS1105/1104/1116/1115, TS2389, TS5108, TS1117, TS17009, TS2588, TS2369, TS2335, TS1155, TS2393, TS1359; squiggle fixes; FP reductions
- **JS emit bare-name:** 5,413 tests, ~5,104 passing (~94.3%)
- **JS emit parameterized:** 1,114 tests, ~522 passing (~46.9%)
- **Error baselines:** 4,035 tests, ~945 passing (~23.4%)
- **Error baseline failure breakdown:**
  - 2,161 "expected diagnostics but none produced" (need checker TS2xxx codes)
  - 837 diff failures (wrong codes/positions/file order/extra diagnostics)
- **Missing from test suite:**
  - ~2,848 parameterized `.js` baselines from non-compiler test dirs — **not in sparse clone**
  - 14,015 `.symbols` baselines — deferred (requires full type inference)
  - 14,015 `.types` baselines — deferred (requires full type inference)

---

## QUEUE (execute top-to-bottom)

### 0. Redesign test comparison infrastructure

The current `TypeScriptTestSupport.kt` and `BaselineFormatter.kt` were built ad hoc for
`.js` baseline comparison only. Before adding new test categories, unify the infrastructure
to match the TypeScript harness design documented in `TYPESCRIPT-TEST-HARNESS.md`.

- [x] **0a. Extend `Diagnostic` with span information**

  The `.errors.txt` format requires error position spans for squiggle generation (`~~~`).
  The current `Diagnostic` data class has `line` and `character` but lacks the source
  position and span length needed for squiggles.

  Add to `Diagnostic`:
  ```kotlin
  val start: Int? = null,      // 0-based byte offset in source
  val length: Int? = null,     // span length in characters
  ```

  Also add support for related diagnostics (the `!!! related TS...:` lines):
  ```kotlin
  val relatedInformation: List<Diagnostic> = emptyList(),
  ```

  **File:** `TypeScriptCompiler.kt`
  **No test impact** — purely additive data class change.

- [x] **0b. Implement error baseline formatter**

  Create `formatErrorBaseline()` in `BaselineFormatter.kt` (or a new
  `ErrorBaselineFormatter.kt`) that produces the `.errors.txt` format from a
  `CompilationResult`.

  The format has three parts (see `TYPESCRIPT-TEST-HARNESS.md` §3.2):

  1. **Diagnostic summary** — one line per diagnostic:
     `file.ts(line,col): category TScode: message`
     Global diagnostics (no file) omit the file prefix.

  2. **Global error markers** — for diagnostics with no source file:
     `!!! error TSnnnn: message`

  3. **Per-file annotated source** — for each source file:
     ```
     ==== file.ts (N errors) ====
         <source line>
         ~~~~~~~~~~~
     !!! error TSnnnn: message
     ```
     - Source lines are indented with 4 spaces
     - Squiggles (`~`) span from error start column to start+length
     - Multi-line errors continue squiggles across subsequent lines
     - Diagnostics are sorted by position within each file

  If there are zero diagnostics, the formatter returns `null` (no baseline file should
  exist for error-free compilations).

  **Key detail:** The category label in `!!!` lines is lowercased: `error`, `warning`,
  `suggestion`. Related information uses `!!! related TScode file:line:col: message`.

  **File:** `BaselineFormatter.kt` (or new `ErrorBaselineFormatter.kt`)
  **Test:** Write unit tests comparing formatter output against a few real `.errors.txt`
  baselines.

- [x] **0c. Add `toErrorBaseline()` to `CompilationResult`**

  Mirror the existing `toBaseline()` method:
  ```kotlin
  fun CompilationResult.toErrorBaseline(): String? {
      // returns null when diagnostics is empty
      // otherwise formats using formatErrorBaseline()
  }
  ```

  **File:** `BaselineFormatter.kt`

- [x] **0d. Add error baseline comparison helper**

  Add a `sameAsErrors()` method to `TypeScriptTestSupport.kt` that:
  1. If no `.errors.txt` baseline file exists AND the compiler produced no diagnostics →
     test passes (both agree: no errors).
  2. If no `.errors.txt` baseline file exists BUT the compiler produced diagnostics →
     test fails ("unexpected diagnostics produced").
  3. If `.errors.txt` baseline exists BUT the compiler produced no diagnostics →
     test fails ("expected diagnostics but none produced").
  4. If both exist → compare formatted output against baseline using `sameAs`.

  ```kotlin
  fun CompilationResult.errorsMatchBaseline(baselinePath: Path) { ... }
  ```

  **File:** `TypeScriptTestSupport.kt`

### 1. Parameterized test generation

- [x] **1a. Parse multi-value directives from test source files**

  In `build.gradle.kts`, before generating test functions for each `.ts` file, scan for
  multi-value directives (`// @target: ES5, ES2015`) and compute the Cartesian product
  of configurations. This mirrors TypeScript's `varyBy` + `splitVaryBySettingValue()`
  mechanism (see `TYPESCRIPT-TEST-HARNESS.md` §4.2–4.4).

  **Which directives vary:** For our purposes, start with directives that have existing
  parameterized baselines in the repository. The dominant ones are:
  - `target` (ES5, ES2015, ES6, ESNext, ES2017, ES2020, ES2022)
  - `module` (commonjs, esnext, system, amd, es2015, es2020, es2022, node16–nodenext, preserve)
  - `alwaysstrict` (true, false)
  - other options that appear with commas in test sources

  **Detection rule:** A directive triggers variation when its value contains a comma or `*`.
  Single-value directives don't create variations.

  **Safety limit:** Max 25 variations per test case (matching TypeScript's limit).

  **File:** `build.gradle.kts` — `generateTypeScriptTests` task

- [x] **1b. Generate parameterized test functions**

  For each configuration in the Cartesian product, generate a `@Test` function that:
  1. Reads the `.ts` source
  2. Overrides the relevant compiler options (the parameterized ones)
  3. Compiles with those options
  4. Compares against the parameterized baseline file

  Baseline filename construction: `name(key1=value1,key2=value2).ext`
  - Keys sorted alphabetically, lowercased
  - Values lowercased
  - No spaces

  The test function name encodes the configuration:
  ```kotlin
  @Test
  fun `abstractPropertyBasics_ts__target_es5__compiles to JavaScript matching baseline`() {
      val source = Path("$typeScriptCasesDir/abstractPropertyBasics.ts").readText()
      TypeScriptCompiler().compile(source, "abstractPropertyBasics.ts",
          overrideOptions = mapOf("target" to "es5")
      ).toBaseline().sameAs(
          Path("$typeScriptBaselineDir/abstractPropertyBasics(target=es5).js")
      )
  }
  ```

  **Note:** The `compile()` method already parses `// @target:` from the source. For
  parameterized tests, the override must REPLACE the multi-value directive with the
  single value for that variation. This may require a new `compile()` overload or
  an `overrideOptions` parameter.

  **File:** `build.gradle.kts`
  **Expected new tests:** ~3,962 (parameterized `.js` baselines)

- [x] **1c. Handle option override in `TypeScriptCompiler.compile()`**

  Add mechanism for test harness to override specific compiler options parsed from the
  source. Options:
  - Add `optionOverrides: Map<String, String> = emptyMap()` parameter to `compile()`
  - Apply overrides after parsing directives from source, before compilation

  **File:** `TypeScriptCompiler.kt`, `CompilerOptions.kt`

### 2. Enable `.errors.txt` tests

- [x] **2a. Generate `.errors.txt` test functions in `build.gradle.kts`**

  For each `.ts` test case file, if a `.errors.txt` baseline exists (either bare-name or
  parameterized), generate a `@Test` function that:
  1. Compiles the source
  2. Calls `toErrorBaseline()`
  3. Compares against the `.errors.txt` baseline via `errorsMatchBaseline()`

  For tests with NO `.errors.txt` baseline, the test asserts that no diagnostics were
  produced (matching TypeScript's behavior: no baseline file = no errors expected).

  ```kotlin
  @Test
  fun `foo_ts has expected compilation errors matching foo_errors_txt`() {
      val source = Path("$typeScriptCasesDir/foo.ts").readText()
      val result = TypeScriptCompiler().compile(source, "foo.ts")
      result.errorsMatchBaseline(
          Path("$typeScriptBaselineDir/foo.errors.txt")
      )
  }
  ```

  **File:** `build.gradle.kts`
  **Expected new tests:** ~9,055 (`.errors.txt` baselines) — most will fail initially.

- [x] **2b. Run suite and establish baseline counts**

  Baseline counts (2026-03-14):
  - **Total tests:** 10,595 (10,592 ran, 3 skipped)
  - **JS emit (bare-name):** 5,413 tests, 5,122 passing (94.6%)
  - **JS emit (parameterized):** 1,114 tests, 498 passing (44.7%)
  - **Error baseline:** 4,035 tests, 91 passing (2.3%)
  - **Formatter unit tests:** 33 tests, all passing
  - **Overall:** 5,715 / 10,595 passing (53.9%)

### 3. Carry forward: remaining Phase 2 JS emit fixes

These items from Phase 2's queue are still valid and independent of the diagnostic
infrastructure. Execute them to reduce the JS emit failure count.

- [x] **3a. Cross-file namespace import resolution** (Phase 2 item 7a)

  Add `NamespaceImport` resolution to `Checker.resolveAlias()`. When the alias is a
  `NamespaceImport`, resolve the module specifier to a file, create a synthetic module
  symbol whose exports are the target file's locals.
  - Key test: `constEnumNamespaceReferenceCausesNoImport2`
  - Fix area: `Checker.kt: resolveAlias()`

- [x] **3b. Default import resolution** (Phase 2 item 7b)

  `import Foo from "./mod"` — resolve default binding to the target module's default export.
  - Fix area: `Checker.kt: resolveAlias()`

- [x] **3c. Export re-export type elision** (Phase 2 item 7c)

  `export { i } from "./server"` where `i` is an interface should be elided.
  - Key test: `es6ExportClauseWithoutModuleSpecifier`
  - Fix area: `Transformer.kt: transformExportDeclaration()`

- [x] **3d. CommonJS binding pattern computed property rewriting** (Phase 2 item 8a)

  `import { a } from "./a"; function fn({ [a]: value })` — `[a]` should become `[a_1.a]`.
  - Key test: `computedPropertyNameWithImportedKey`
  - Fix area: `Transformer.kt: transformToCommonJS()`

- [x] **3e. CommonJS export alias qualification** (Phase 2 item 8b) — *deferred: 2 tests, complex CJS transform*

  Self-referencing exported names need `(0, exports.X)` form.
  - Key tests: `conflictingDeclarationsImportFromNamespace1/2`
  - Fix area: `Transformer.kt: transformToCommonJS()`

- [x] **3f. Class static property parsing** (Phase 2 item 9a) — *deferred: 1 test, parser recovery*

  `static f = 3;` is misparsed as two expression statements.
  - Key test: `class2`
  - Fix area: `Parser.kt: parseClassElement()`

- [x] **3g. Non-`this`-prefixed property initializers** (Phase 2 item 9b) — *deferred: 1 test*

  `p1 = 0;` in constructor instead of `this.p1 = 0;`.
  - Key test: `classUpdateTests`
  - Fix area: `Transformer.kt`

- [x] **3h. Parser error recovery** (Phase 2 items 10a-10b, ~45 tests) — *deferred to after 4a-4b*

  Audited and implemented tractable fixes: alwaysStrict=false suppression (+13),
  accessor empty body recovery (+1), static/public class member recovery (in 3f/3g).
  Remaining issues are high-risk: reScanGreaterToken (disabled, 4-test regression),
  arrow function missing token recovery, ambiguous generic assertions.
  - Fix area: `Parser.kt`

- [x] **3i. Enum non-literal cross-file initializers** (Phase 2 item 11b) — *deferred: 2 tests*

  `MyEnum { a = MyEnumFromModule.a }` should resolve to `MyEnum { a = 0 }`.
  - Key test: `importElisionEnum`
  - Fix area: `Transformer.kt: transformEnumDeclaration()`

### 4. Parser diagnostic precision

The Parser currently emits all diagnostics with `code = 1005` and no line/character
information. This must be fixed for `.errors.txt` tests to match baselines.

- [x] **4a. Add line/character computation to Parser diagnostics**

  Implement `getLineAndCharacterOfPosition(source: String, pos: Int): Pair<Int, Int>`
  utility (1-based line and character). Use it in `reportError()` to populate the
  `line`, `character`, `start`, and `length` fields of each `Diagnostic`.

  **File:** `Parser.kt` (or a shared utility)

- [x] **4b. Use correct TypeScript diagnostic codes in Parser**

  Replace the hardcoded `code = 1005` with the actual TypeScript error codes. Common
  parser diagnostic codes:
  - TS1002: Unterminated string literal
  - TS1003: Identifier expected
  - TS1005: `'X'` expected (most common — parameterized by expected token)
  - TS1009: Trailing comma not allowed
  - TS1010: Value expected
  - TS1012: Unexpected token
  - TS1109: Expression expected
  - TS1110: Type expected
  - TS1128: Declaration or statement expected
  - TS1136: Property assignment expected
  - TS1141: String literal expected
  - TS1160: Tagged templates only available in ES2015+

  **File:** `Parser.kt`

- [x] **4c. Measure `.errors.txt` pass rate from parser diagnostics alone**

  After 4a-4b: 5,939 / 10,595 passing (56.0%). Error baseline tests: ~310 / 4,035
  passing (7.7%). Most gains from TS5107 deprecation diagnostics (+214). Parser
  diagnostic precision (positions + codes) contributed +5 tests. Remaining error
  baseline failures need type checker diagnostics (TS2xxx+ codes).

### 5. Checker diagnostic emission

This is the core of Phase 3 — teaching the Checker to emit diagnostics that make
`.errors.txt` tests pass. Prioritize by frequency in the baseline corpus.

- [x] **5a. Add diagnostic infrastructure to Checker**

  Add a `diagnostics: MutableList<Diagnostic>` to `Checker.kt` and a public
  `getDiagnostics(): List<Diagnostic>` method. Wire it into `TypeScriptCompiler.kt`
  so checker diagnostics are included in `CompilationResult.diagnostics`.

  **File:** `Checker.kt`, `TypeScriptCompiler.kt`

- [x] **5b. Implement high-frequency checker diagnostics** *(infrastructure phase)*

  Completed infrastructure improvements (+226 tests, 5,959 → 6,185):
  - CRLF normalization in `parseMultiFileSource` (+89)
  - TS5101 deprecation diagnostics for outFile/downlevelIteration/baseUrl (+55)
  - TS5102 "has been removed" for out/charset/keyofStringsOnly/etc. (+9)
  - Full file paths in error baseline formatter (+33)
  - TypeScript test harness file ordering for error baselines (+40)
  - Trailing period in parser error messages (correctness fix)
  - `messageChain` support in Diagnostic for multi-line messages

  **Remaining work** (requires actual type checking, not just diagnostics):

  | Code | Message | Baselines with ONLY this code |
  |------|---------|-------------------------------|
  | TS2322 | Type 'X' is not assignable to type 'Y' | 522 |
  | TS2454 | Variable used before assignment | 281 |
  | TS2304 | Cannot find name 'X' | 249 |
  | TS2564 | Property has no initializer | 186 |
  | TS2339 | Property does not exist on type | 150 |
  | TS2345 | Argument not assignable | 125 |
  | TS6133 | Declared but never used | 106 |

  These require full type inference and are beyond the scope of Phase 3's
  diagnostic infrastructure work. Tracked for future phases.

  **File:** `Checker.kt`, `TypeScriptCompiler.kt`, `BaselineFormatter.kt`

- [x] **5c. Measure progress and iterate**

  Current metrics (2026-03-14):
  - **Total:** 6,219 / 10,595 passing (58.7%)
  - **Error baselines:** ~570 / 4,035 passing (~14.1%)
  - **Error failure breakdown:** ~2,830 "none produced" + ~1,540 diff-based
  - Remaining error test progress requires actual type checker implementation
    (TS2xxx codes needing type inference, control flow analysis, etc.)

### 6. Decorator metadata diagnostics (Phase 2 item 11a)

- [x] **6a. Decorator metadata type serialization** (~3 tests) — *skipped/deferred*

  Skipped: requires type serialization (`design:paramtypes`), significantly more
  complex than other checker work. Only 3 tests affected. Deferred to future phase.

### 7. Type checker diagnostics — Phase 3b

Implement type checker diagnostics that unlock `.errors.txt` test passes. Prioritized
by test count and implementation tractability.

- [x] **7a. TS6133 — "'X' is declared but its value is never read"** (+57 tests)

  Gated by `noUnusedLocals: true` and `noUnusedParameters: true` compiler options.
  No risk of false positives on tests without these options.

  Implementation:
  1. After `trackAllImportReferences()`, run `checkUnusedDeclarations()` when options set
  2. For each source file, walk AST collecting declarations and references per scope
  3. Report TS6133 for declarations that are not referenced, not exported, and not
     underscore-prefixed
  4. Handle: variables, functions, classes, interfaces, type aliases, enums, imports
  5. Handle both `noUnusedLocals` (variables, functions, classes, types) and
     `noUnusedParameters` (function/method parameters)

  **Files:** `Checker.kt`

- [x] **7b. TS2454 — "Variable 'X' is used before being assigned"** (+105 tests)

  Requires basic definite assignment analysis. For the simplest pattern (variable
  declared with type annotation but no initializer, used before any assignment in
  straight-line code), this is tractable without full control flow analysis.

  **Files:** `Checker.kt`

- [x] **7c. TS2304 — "Cannot find name 'X'"** (+46 tests, 6,508 → 6,554)

  Implemented scope-aware name resolution with comprehensive AST walking.
  Checks identifiers in both expression and type positions. Uses scope chain
  (file-level → function → block → catch) with proper type parameter tracking.
  Comprehensive KNOWN_GLOBALS set (~400 lib.d.ts names) prevents false positives.
  Depth protection (maxCheckDepth=200) prevents StackOverflow on stress tests.
  Many remaining pure TS2304 tests also need TS2503 (Cannot find namespace).

  **Files:** `Checker.kt`

- [x] **7d. TS2564 — "Property 'X' has no initializer"** (+104 tests)

  Check class properties with type annotations but no initializer and no definite
  assignment in the constructor.

  **Files:** `Checker.kt`

- [x] **7e. TS7006 — "Parameter 'X' implicitly has an 'any' type"** (+6 tests)

  When `noImplicitAny` is enabled, check function/method/arrow parameters for
  missing type annotations.

  **Files:** `Checker.kt`

- [x] **7f. TS7026 — "JSX element implicitly has type 'any'"** (0 testable — all .tsx, not in test suite)

  Implemented but untestable: all TS7026 baselines are `.tsx` files, and test
  generator only processes `.ts` files. Implementation emits TS7026 for each
  JSX opening/closing/self-closing tag when no JSX.IntrinsicElements exists.
  Will activate when .tsx test support is added.

- [x] **7g. TS2300 — "Duplicate identifier 'X'"** (+7 tests, 6,554 → 6,561)

  Check for duplicate type parameters, duplicate function parameters, and
  incompatible duplicate declarations (var+class, var+function) at file scope.
  Walks all statement lists and parameter lists to detect duplicates.

### 8. Phase 3c — Incremental diagnostic and emit improvements

Picking off tractable fixes to continue improving the pass rate.

- [x] **8a. Fix index signature parsing in lookAhead** (+24 tests, 6,564 → 6,588)

  The `isIndex` lookahead in `parseIndexSignatureOrProperty()` used the Parser's
  cached `token` field instead of `scanner.getToken()` inside `scanner.lookAhead`.
  Refactored `isIdentifier()` into `isIdentifierToken(t)`.

- [x] **8b. Hoist var declarations for TS2304 scope resolution** (+1 test, 6,588 → 6,589)

  Added `collectHoistedVarNames()` to recursively find `var` declarations in
  nested blocks, loops, if/else, switch, try/catch for the TS2304 checker.

- [x] **8c. Skip TS2454 for 'any' type** (+4 tests, 6,589 → 6,593)

  Variables typed as `any` don't need definite assignment checking.

- [x] **8d. Skip TS2564 for 'any' type** (+3 tests, 6,593 → 6,596)

  Same for class properties — `any` includes `undefined`.

- [x] **8e. Extend TS2300 to namespaces and class members** (+5 tests, 6,596 → 6,601)

  Check duplicate declarations inside namespace blocks and conflicting
  class members (method+getter, method+property).

- [x] **8f. Remove 'arguments' from KNOWN_GLOBALS** (+1 test, 6,601 → 6,602)

  `arguments` is only available inside non-arrow functions, not at file level.

- [x] **8g. Recurse into blocks for TS2454** (+1 test, 6,602 → 6,603)

  Added Block, TryStatement, DoStatement, LabeledStatement to TS2454 checker.

- [x] **8h. Extend TS2300 for class+class duplicates** (+1 test, 6,603 → 6,604)

  Detect duplicate class declarations and class+function/enum conflicts.

- [x] **8i. Extend TS2300 for duplicate export assignments** (+2 tests)

  Multiple `export = X` statements produce TS2300 with expression-text squiggle.

- [x] **8j. Unused type parameter detection for TS6133** (+6+2+5 = +13 tests)

  Type parameters on functions, methods, classes, interfaces, and type aliases
  that are never referenced produce TS6133. Single unused type params cover
  `<name>` in squiggle. Merged declarations skip the check.

- [x] **8k. Unused private class members for TS6133** (+13 tests)

  Private properties, methods, getters, setters that are never accessed
  within the class body produce TS6133. Handles getter/setter pairs and
  string-literal element access.

- [x] **8l. TS2564 for class expressions** (+3 tests)

  Class expressions assigned to variables are now checked for property
  initialization.

- [x] **8m. TS2454 for-loop init assignments** (+1 test)

  Track assignments in for-loop initializer expressions (comma expressions).

- [x] **8n. Duplicate interface members and enum members** (+1+1 = +2 tests)

  Interface properties and enum members with duplicate names produce TS2300.

- [x] **8o. Duplicate names in destructuring parameters** (+2 tests)

  Recursively walk destructuring patterns for duplicate binding names.

- [x] **8p. Preserve tab indentation in error squiggle lines** (+9 tests)

  The error baseline formatter now preserves tab characters from source
  lines in squiggle indentation instead of converting all whitespace to spaces.

- [x] **8q. Reduce false-positive TS2304 in multi-file tests** (+2 tests)

  Parse and bind `.d.ts` files for checker globals (without emitting JS).
  Skip all checker diagnostics (TS2304/TS2454/TS2564/TS6133/TS7006/TS2300)
  for `.d.ts` files. Reduced false-positive TS2304 from 155 to 128 tests.

- [x] **8r. Multi-file JS baseline file ordering** (investigated — no specific ordering fix needed)

  Multi-file JS failures (183 tests) are diverse: missing reference directives,
  CommonJS export issues, import elision, parser recovery. No single "ordering" fix.
  Addressed by 8q (.d.ts parsing) and other incremental fixes.

- [x] **8s. Parser related diagnostics (TS1007)** (+4 tests)

  Track opening token positions (braces/brackets/parens) in a stack.
  When `parseExpected` fails for a closing token, add `relatedInformation`
  with TS1007 pointing to the matching opening token position.

- [x] **8t. Compiler option validation diagnostics** (+17 tests)

  TS6082 (outFile with non-AMD/System module), TS5069 (emitDeclarationOnly
  without declaration), TS5070 (resolveJsonModule with classic moduleResolution),
  TS5095 (bundler with incompatible module).

- [x] **8u. TS5101 downlevelIteration explicitly-set** (+1 test)

  Deprecation fires when option is SET (even to false).

- [x] **8v. TS1109 in expression contexts** (+5 tests)

  Use "Expression expected." (TS1109) instead of "Identifier expected."
  (TS1003) in parsePrimaryExpression fallback.

- [x] **8w. AMD/System module specifier and name resolution** (+5 tests)

  Resolve relative module specifiers to bare names in AMD/System outFile
  bundles. Preserve directory paths in module names (app/main, not main).

- [x] **8x. TS5071 bundler implies resolveJsonModule** (+4 tests)

  moduleResolution=bundler implies resolveJsonModule for TS5071 check.

- [x] **8y. TS5053 inlineSourceMap conflicts** (+3 tests)

  Emit TS5053 for mapRoot/sourceMap + inlineSourceMap combinations.

- [x] **8z. TS5069 mapRoot without sourceMap** (+1 test)

- [x] **8aa. TS5055 outFile overwrite detection** (+3 tests)

- [x] **8ab. TS1109 in expression contexts** (+5 tests)

- [x] **8ac. TS7006 optional param squiggle** (+1 test)

- [x] **8ad. TS5101 downlevelIteration explicitly-set** (+1 test)

- [x] **8ae. StackOverflow for deep binary expression chains** (+3 tests)

  `manyConstExports` test (5000 exports) caused StackOverflow in both Transformer
  `rewriteId` and Emitter `emitBinaryExpression` for deep right-associative chains.
  Fixed by: (1) batching CJS `exports.x = void 0` hoists into groups of 50 (matching
  TypeScript), (2) iterative right-spine walk in `rewriteId`, (3) iterative right-deep
  chain handling in Emitter.

### 9. Phase 3d — Continued incremental improvements

- [x] **9a. Honor `ignoreDeprecations` + `targetExplicitlySet` + missing globals + TS5071/5070 exclusion** (+3 tests)

  - Added `ignoreDeprecations` field to `CompilerOptions` with parsing in
    `applyDirective` and `applyTsconfigOptions`. Guards TS5107/TS5101 emission.
  - Added `targetExplicitlySet` flag — only emit TS5107 for target when
    explicitly set (not default ES3).
  - Added missing globals: `RegExpMatchArray`, `RegExpExecArray`, `FlatArray`,
    `IteratorResult`, `IteratorYieldResult`, `IteratorReturnResult`, `IteratorObject`,
    `WScript`, `Windows`.
  - Fixed TS5071/TS5070 mutual exclusion — don't emit both.

  **Files:** `CompilerOptions.kt`, `TypeScriptCompiler.kt`, `Checker.kt`

- [x] **9b. `moduleResolution: "node"` → `node10` alias fires TS5107** (+4 tests)

  Added `moduleresolution` to `allowedTsconfigOptions` so it's parsed from tsconfig.json.
  Added `TsconfigOptionPosition` tracking for all tsconfig options to emit positioned
  diagnostics with file/line/col info. TS5107/TS5101 diagnostics from tsconfig now include
  `Visit https://aka.ms/ts6 for migration information.` messageChain. Also includes
  tsconfig.json in `allSourceFiles` for error baseline annotations.

  **Files:** `CompilerOptions.kt`, `TypeScriptCompiler.kt`, `BaselineFormatter.kt`

- [x] **9c-pre. Namespace and enum merged scope for TS2304** (0 net — correctness fix)

  Build namespace scopes from binder's merged symbol exports so that
  `namespace A { export class Foo {} } namespace A { new Foo() }` resolves.
  Also adds merged enum member names to enum initializer scope.
  No immediate test impact (affected tests also need other diagnostics).

  **Files:** `Checker.kt`

- [x] **9c. TS2300 false positives for valid declaration merging** (+3 tests, not ~33)

  Investigated: actual false positives were much fewer than estimated.
  Only 2 tests had false TS2300 — both were class+enum conflicts that should
  use TS2567 ("Enum declarations can only merge with namespace or other enum
  declarations"). Declaration merging for class+namespace, function+namespace,
  interface+interface was already correctly handled (not tracked in checker).

  **Files:** `Checker.kt`

- [x] **9d. Enum member names in scope within enum body** (+1 test)

  Added enum member names to a child scope in the TS2304 checker so
  `const enum E { A = 1, B = A + 1 }` resolves `A`.

  **Files:** `Checker.kt`

- [x] **9e. Class expression self-reference in class scope** (+1 test)

  Added class expression name to its own scope so
  `class C { static y = C.x }` resolves `C`.

  **Files:** `Checker.kt`

- [x] **9f. Dotted namespace name resolution for TS2304** (+5 tests)

  `namespace m1.m2.m3 {}` produces a PropertyAccessExpression for the name.
  Extract leftmost segment and add to scope in `collectDeclaredNames`.

  **Files:** `Checker.kt`

- [x] **9g-pre. CJS require const→var for target<ES2015** (+4 tests)

  `makeRequireConst` and `makeImportHelperConst` used `ConstKeyword` unconditionally.
  For `target < ES2015`, use `VarKeyword` since `const` didn't exist in ES5.

  **Files:** `Transformer.kt`

- [x] **9g. TS6133 write-only assignment detection** (+1 test, not ~23)

  Basic write-only detection was already implemented. Extended
  `collectWriteTargetRefs` to handle destructuring write targets:
  ArrayLiteralExpression, ObjectLiteralExpression, ParenthesizedExpression.
  Most remaining TS6133 failures are from other issues (TS6198/TS6199
  consolidated diagnostics, self-reference detection, namespace tracking).

  **Files:** `Checker.kt`

- [x] **9h. TS7026 false positives with jsxFactory/preserve** (already implemented)

  Guards for `jsxFactory != null`, `jsx: preserve`, and `jsx: react-native`
  were already in place in `checkJsxImplicitAny()`. Only 1 test has a
  false-positive TS7026 (jsxFactoryMissingErrorInsideAClass) and it needs
  TS2874 instead — different issue.

  **Files:** `Checker.kt`

- [x] **9i. CJS require const→var for target<ES2015** (+4 tests)

  TypeScript does NOT downlevel const/let→var for user code, only for its own
  synthesized CJS require statements. Fixed `makeRequireConst` and
  `makeImportHelperConst` to use `var` for target < ES2015.

  **Files:** `Transformer.kt`

- [x] **9j. Object.defineProperty getter re-exports in CJS** (+7 tests)

  `export { X }` where X came from a named import now uses
  `Object.defineProperty(exports, "X", { get: ... })` instead of direct
  `exports.X = X`. Tracks named import elements in `namedImportLocalNames`.

  **Files:** `Transformer.kt`

### 10. Phase 3e — Further incremental improvements

- [x] **10a. Tsconfig positioned diagnostics** (+4 tests)

  Added `moduleresolution` to allowed tsconfig options, tsconfig position
  tracking for all options, and positioned TS5107/TS5101/TS5102 diagnostics.

- [x] **10b. TS2567 for enum+class conflicts** (+3 tests)

  Use TS2567 instead of TS2300 for class+enum merge conflicts.

- [x] **10c. Destructuring write targets for TS6133** (+1 test)

  Extended `collectWriteTargetRefs` for array/object destructuring assignments.

- [x] **10d. KEY vs VALUE position for tsconfig diagnostics** (+8 tests)

  TS5101/TS5102 point to option KEY, TS5107 points to option VALUE.

- [x] **10e. Migration URL only for moduleResolution=node10** (+1 test)

  Only `moduleResolution=node10` TS5107 gets `Visit https://aka.ms/ts6` chain.

- [x] **10f. ignoreDeprecations suppresses TS5102** (+1 test)

  `ignoreDeprecations: "5.0"` suppresses TS5102 removed-option diagnostics.

- [x] **10g. TS6196 for type declarations + interface refs** (+3 tests)

  Interfaces, type aliases, enums use TS6196. Interface heritage + member
  type references are collected for unused checking.

- [x] **10h. TS2309 export assignment conflicts** (+6 tests)

  Emit TS2309 when `export = X` coexists with other exports in a file.

  **Files:** `Checker.kt`

- [x] **10i. TS1100 strict mode identifier restrictions** (+6 tests)

  `arguments` and `eval` cannot be used as variable/parameter/function
  names in strict mode (alwaysStrict or strict).

  **Files:** `Checker.kt`

- [x] **10j. TS1203/TS1202 export=/import= in ES modules** (+7 tests)

  Emit TS1203 for export= and TS1202 for import= when targeting ES modules.

  **Files:** `Checker.kt`

- [x] **10k. TS6133 namespace declarations as unused** (+3 tests)

  Added `ModuleDeclaration` to `collectUnusedDeclarations` so that
  non-exported, non-declare namespace declarations are tracked for
  unused checking with TS6133.

  **Files:** `Checker.kt`

- [x] **10l. TS6133 self-referencing declarations** (+4 tests)

  Declarations that only reference themselves are now flagged as unused.
  Uses per-statement reference tracking to detect self-references at scope
  level. Also handles private member self-references via per-member tracking.
  Type parameter names are excluded from outer scope references (scoping).
  Switch case/default clause statements now checked for unused declarations.

  **Files:** `Checker.kt`

### 11. Phase 3f — Reduce false positives and fill diagnostic gaps

- [x] **11a-pre. TS6133 for type params in function expressions/arrows** (+3 tests)

  Pass `typeParameters` and `returnType` to `checkUnusedInFunctionLike` for
  ArrowFunction, FunctionExpression, and object literal MethodDeclaration.
  Also handle PropertyDeclaration initializers in `checkUnusedInClassElement`.

  **Files:** `Checker.kt`

- [x] **11a. Reduce TS2304 false positives** (analyzed — ~0 net tests)

  Thorough analysis of all 3,808 failing tests found only 1 test (`dottedModuleName`)
  with false-positive TS2304 in the diff, and that test also has a parser error code
  mismatch (TS1109 vs TS1144). Zero "none produced" tests need only TS2304.
  Zero JS emit tests are affected. The TS2304 checker is already well-calibrated.
  The estimated ~10-20 tests was too high — actual impact is negligible.

  **Files:** `Checker.kt` (no changes needed)

- [x] **11b. Reduce TS7006 false positives** (deferred — 0 net tests)

  Investigated contextual typing suppression for arrow/function expression
  parameters. Doesn't gain tests since affected tests also need other
  diagnostics. Callback argument suppression causes test ordering regression.

  **Files:** `Checker.kt`

- [x] **11b2. TS6133 for destructuring parameters** (+1 test)

  Added `collectDestructuringParamNames` to walk ArrayBindingPattern and
  ObjectBindingPattern in function parameters. Also use `spanLength` in
  the function-like parameter reporting code.

  **Files:** `Checker.kt`

- [x] **11c. TS2300/TS2567 gaps + TS5069 declarationMap + namespace+var merging** (+3 tests)

  Fixed false-positive TS2300 for namespace+var when namespace is type-only:
  - `declare namespace` + var → allowed (no value produced)
  - Namespace with only interfaces/types + var → allowed
  - Empty namespace + var → allowed
  Also: TS2567 for enum+interface conflicts (all sides get TS2567, not TS2300),
  TS5069 for `declarationMap` without `declaration` option.
  Remaining TS2300 issues (prototype, cross-file) won't gain tests alone.

  **Files:** `Checker.kt`, `CompilerOptions.kt`, `TypeScriptCompiler.kt`

- [x] **11d. TS6133 gaps — type param and for-loop unused checking** (+3 tests)

  - Type params checked by `noUnusedParameters` (not just `noUnusedLocals`)
  - Fixed single-param squiggle when underscore params are skipped
  - ForStatement initializer unused variable checking
  Remaining TS6133 gaps (write-only properties, infer type params,
  consolidated codes TS6198/TS6199/TS6192) require more complex changes
  and won't gain many tests individually.

  **Files:** `Checker.kt`

- [x] **11g. TS1107 — jump target cannot cross function boundary** (+6 tests)

  Check break/continue statements that target loops/labels outside the
  enclosing function boundary. Tracks iteration/switch/label context during
  AST walk, resets at function/arrow/class boundaries.

  **Files:** `Checker.kt`

- [x] **11e. TS2554 — wrong argument count** (+2 tests)

  Basic implementation for too-many-arguments: direct function calls and
  class constructors without inheritance. Skips overloaded functions,
  rest parameters, and classes with base constructors.
  Depth-limited expression walker to prevent StackOverflow.

  **Files:** `Checker.kt`

- [x] **11f. TS2307 — cannot find module** (+3 tests)

  Emit TS2307 for relative/empty module specifiers in single-file
  compilations. Bare specifiers skipped to avoid false positives.
  Multi-file tests skipped due to module resolution complexity.

  **Files:** `Checker.kt`

### 12. Phase 3g — Next diagnostic improvements

- [x] **12a. TS2391 — function implementation missing** (+5 tests)

  Check overload declarations without a following implementation body.
  Only flags the LAST signature in an overload chain, not intermediate ones.
  Handles both file-level functions and class methods.
  Skips `declare` contexts.

  **Files:** `Checker.kt`

- [x] **12b. TS7027 — unreachable code detected** (+1 test, infrastructure for ~10)

  Implemented unreachable code detection after return/throw/break/continue,
  infinite loops, and terminating if/switch. Most TS7027 tests need
  multi-line squiggle support in the formatter (not yet implemented).

  **Files:** `Checker.kt`, `CompilerOptions.kt`

- [x] **12c. TS2693 — type used as value** (+5 tests)

  Detect type keywords (any, number, string, etc.) and interface/type alias
  names in value positions. Excludes names that merge with classes/functions
  and known globals. Also fixed TS2391 false positive for abstract methods.

  **Files:** `Checker.kt`

- [x] **12d. TS2872 — expression always truthy** (+5 tests)

  Detect always-truthy expressions on left side of `||` (non-empty
  string literals, non-zero numeric literals) and always-truthy numeric
  literals in if conditions. Conservative to avoid false positives.

  **Files:** `Checker.kt`

- [x] **12e. TS2307 bare specifier support** (+9 tests)

  Emit TS2307 for ALL module specifiers in single-file compilations,
  including bare specifiers (previously only relative/empty were flagged).
  Remaining TS2304 gaps need type constraint and enum scope resolution.

  **Files:** `Checker.kt`

- [x] **12f. TS2304 in type parameter constraints** (+2 tests)

  Check names in type parameter constraint (`extends X`) and default
  positions for unresolved names. Applied to functions, classes,
  interfaces, and type aliases.

  **Files:** `Checker.kt`

- [x] **12g. TS2314 — wrong type argument count** (+17 tests)

  "Generic type 'X' requires N type argument(s)." Check type references
  and heritage clause types against declared type parameter counts.
  Handles user-declared classes/interfaces/type aliases (via binder symbol
  lookup) and built-in generics (Array, Promise, Map, etc. via hardcoded
  table). Skips types with default type parameters (need TS2707 instead).
  Suppresses false-positive TS2564/TS2454 for properties/variables whose
  type annotation has a TS2314 error.

  **Files:** `Checker.kt`

- [x] **12h. TS2694 — namespace has no exported member** (+2 tests)

  "Namespace 'X' has no exported member 'Y'." Check qualified name
  access against binder's namespace exports. Handles simple single-segment
  namespaces. Dotted namespace names (`Foo.Bar`) and module-qualified paths
  need deeper binder support (PropertyAccessExpression names). Also checks
  member accessibility: non-exported members in regular namespaces produce
  TS2694, while `declare namespace` members are implicitly accessible.

  **Files:** `Checker.kt`

- [ ] **12i. TS2305 — module has no exported member** (~11 tests) — *deferred*

  "Module 'X' has no exported member 'Y'." Check named import bindings
  against exported declarations. Complex due to overlapping diagnostics:
  TS2614 (default export suggestion), TS2616 (export= modules), and
  incomplete binder `ExportValue` flag support for `export var`.
  Needs deeper module resolution infrastructure.

  **Files:** `Checker.kt`

### 13. Phase 3h — False positive suppression and new diagnostics

- [x] **13a. Suppress false-positive TS2307 for multi-file sources** (+3 tests)

  Pass `isMultiFileSource` flag from TypeScriptCompiler to Checker when the
  source has `@Filename` directives. Skip TS2307 checking entirely in
  multi-file sources since companion files (.json, .js) may not have been
  parsed but exist as siblings. Also applies to multi-file tests where only
  one .ts file is parsed but other companion files exist.

  **Files:** `Checker.kt`, `TypeScriptCompiler.kt`

- [x] **13a2. TS18050 — null/undefined cannot be used here** (+9 tests)

  Detect null/undefined literals in invalid positions: property access
  base (`null.foo`), element access (`undefined[x]`), and binary operator
  operands (`4 | null`, `null + null`). Handles special case for `+`
  operator where string concatenation with null/undefined is valid
  (`"test" + null` is OK).

  **Files:** `Checker.kt`

- [ ] **13b. TS2451 — block-scoped variable redeclaration** (~1 test) — *deferred*

  Only ~1 test would gain from TS2451 alone. Most TS2451 tests also
  need TS6203/TS6204 or other codes. Low ROI.

- [x] **13b2. TS2304 gaps — type param constraints and index signatures** (+3 tests)

  Add type parameter constraint/default checking for class methods,
  interface methods, and TypeLiteral methods. Also check types in
  IndexSignature declarations (both interface and class members).

  **Files:** `Checker.kt`

- [x] **13b3. TS5110 — module must match moduleResolution** (+7 tests)

  Emit TS5110 when moduleResolution is "nodenext" or "node16" but module
  is not set to the matching value. Simple option validation check.

  **Files:** `TypeScriptCompiler.kt`

- [x] **13c. TS2683 — 'this' implicitly has type 'any'** (+3 tests)

  When `noImplicitThis: true`, flag `this` expressions inside regular
  functions (not arrow functions, not class methods) that don't have
  a `this:` parameter annotation. Includes related TS2738 diagnostic
  when a typed `this` (class) is shadowed by a regular function.
  Object literal property function expressions have typed `this` (skip).
  Arrow functions are transparent — inherit outer `this` context.

  **Files:** `Checker.kt`

- [x] **13d. Extend TS2554 range format for optional parameters** (+0 tests, correctness fix)

  Fixed `questionToken` comparison bug (was always false), added
  "Expected M-N arguments" range format for functions with optional
  parameters, and added too-few-arguments detection. No tests flip
  to passing yet since they also need TS2345/TS2393 etc, but the
  diagnostic output is now correct.

  **Files:** `Checker.kt`

- [x] **13e. TS6133 write-only private property detection** (+4 tests)

  Fixed two bugs: removed incorrect `startsWith("_")` skip that
  suppressed TS6133 for underscore-prefixed private members, and
  added write-only detection by skipping left-side PropertyAccessExpression
  names in simple assignments (the `=` operator) — only the right side
  and sub-expressions count as reads.

  **Files:** `Checker.kt`

### 14. Phase 3i — False positive suppression

- [x] **14a. Suppress false TS6133 for parameters used in typeof** (+0 tests, correctness fix)

  Fixed: unused parameter checker now scans sibling parameter type
  annotations and the return type for `typeof` references. Previously
  `function f(a: number, b: typeof a)` flagged `a` as unused.

  **Files:** `Checker.kt`

- [x] **14b. Suppress false TS6133 for destructuring reads from this** (+0 tests, correctness fix)

  Fixed: `({ x } = this)` destructuring assignment now correctly counts
  as a read of `this.x` for private member usage tracking. Previously
  `private x` was falsely flagged as unused when only accessed via
  destructuring.

  **Files:** `Checker.kt`

- [x] **14c. Suppress false TS2304 for JSX namespace** (+0 tests, correctness fix)

  Added `JSX` to KNOWN_GLOBALS to prevent false TS2304 for JSX namespace
  references like `keyof JSX.IntrinsicElements`. Tests that need this
  also require other missing diagnostics.

  **Files:** `Checker.kt`

- [x] **14d. TS6199 "All variables are unused"** (+2 tests)

  When ALL declarations in a multi-variable statement (`var x, y`) are
  unused, emit TS6199 with span covering the entire statement instead
  of individual TS6133 for each variable.

  **Files:** `Checker.kt`

### 15. Phase 3j — Break/continue codes, TS2389, TS5108, false positives

- [x] **15a. TS1105/TS1104/TS1116/TS1115 — break/continue not in loop** (+3 tests)

  TS1107 is only for when break/continue crosses a function boundary.
  At the top level (no function boundary crossed), use specific codes:
  - TS1105: break not in iteration/switch
  - TS1104: continue not in iteration
  - TS1116: break can only jump to label of enclosing statement
  - TS1115: continue can only jump to label of enclosing iteration
  Added `crossedFunctionBoundary` parameter to jump target checking.

  **Files:** `Checker.kt`

- [x] **15b. TS2389 — function implementation name must be 'X'** (+6 tests)

  When overload signatures have different names (e.g. `foo()` then `bar()`),
  emit TS2389 on the mismatched implementation instead of TS2391. Handles
  both file-level functions and class methods. String literal method names
  use quoted display format (`"foo"` not `foo`).

  **Files:** `Checker.kt`

- [x] **15c. TS5108 — option removed (ES3 target)** (+1 test)

  `target=ES3` uses TS5108 "has been removed" not TS5107 "deprecated".
  ES3 was fully removed in TypeScript 5.5, not just deprecated.

  **Files:** `TypeScriptCompiler.kt`

- [x] **15d. Suppress false-positive TS1005 from destructuring** (+10 tests)

  The `lookAhead` in `parseBindingElement()` called `parseComputedPropertyName()`
  to check if `[...]` was a computed property key. But `parseComputedPropertyName()`
  uses `parseAssignmentExpression()` (not `parseExpression()`), so it stops at
  commas. For binding patterns like `[a = "x", b = "y"]`, the lookAhead only
  consumed the first element, then `parseExpected(CloseBracket)` found `,`
  instead of `]` and reported TS1005. Fix: save/restore diagnostics count
  around the lookAhead to discard speculative errors.

  **Files:** `Parser.kt`

- [x] **15e. Suppress false-positive TS2454 for destructuring assignments** (+3 tests)

  Variables assigned via destructuring patterns (`({ x } = this)`,
  `[a, b] = [1, 2]`) are now tracked as initialized. Added
  `collectDestructuringTargets` to walk object/array destructuring
  assignment targets and remove variables from the uninitialized set.

  **Files:** `Checker.kt`

- [x] **15f. TS1117 — duplicate object literal properties** (+6 tests)

  Detect duplicate property names in object literals. Getter/setter pairs
  are allowed (not flagged as duplicates). Destructuring assignment targets
  (left side of `=`) are skipped to avoid false positives.

  **Files:** `Checker.kt`

- [x] **15g. TS17009 — super must be called before this** (+5 tests)

  Check that `this` is not referenced before `super()` in constructors
  of derived classes. Also handles `super(this)` — `this` in super call
  arguments is also flagged.

  **Files:** `Checker.kt`

- [x] **15h. TS2588 — assignment to const variable** (+1 test)

  Detect assignment to const-declared variables (=, +=, ++, etc.).
  Handles NonNullExpression wrapping (x!++). Simple local const
  tracking per scope; namespace const members not tracked yet.

  **Files:** `Checker.kt`

- [x] **15i. TS2369 — parameter property only in constructor** (+9 tests)

  Detect access modifiers (public/private/protected/readonly) on function
  parameters outside of constructor implementations. Handles arrow functions,
  methods, getters/setters, and declare class constructor overloads.

  **Files:** `Checker.kt`

- [x] **15j. TS2335 — super in non-derived class** (+2 tests)

  Flag `super` references in classes that don't extend anything.

  **Files:** `Checker.kt`

- [x] **15k. TS1155 — const without initializer** (+1 test)

  Flag `const` declarations without initializers (except in `declare` context).

  **Files:** `Checker.kt`

- [x] **15l. TS1359 — reserved word as identifier** (+0 tests net)

  Flag `await` as parameter name in async functions. Infrastructure
  for other reserved word checking. No net test gain (tests already
  passing or offset by other changes).

  **Files:** `Checker.kt`

- [x] **15m. Fix squiggle lengths for TS2300 and TS2307/TS2792** (+5 tests)

  Fixed string literal span in TS2300 (use text.length + 2 for quotes).
  Fixed module specifier span in TS2307/TS2792 (use moduleName.length + 2).

  **Files:** `Checker.kt`

- [x] **15n. Fix default moduleResolution for TS2307 vs TS2792** (+2 tests)

  Updated default moduleResolution mapping: ES module kinds (es2015, esnext)
  and CommonJS default to node10 resolution in TS6+. System/AMD/UMD keep
  classic resolution. This correctly produces TS2307 instead of TS2792 for
  tests with ES module kinds.

  **Files:** `Checker.kt`

### 16. Phase 3k — Options diagnostics and simple checker codes

- [x] **16a. TS6131 — cannot compile modules using outFile** (+3 tests)

  When `outFile` is set, module kind defaults to non-AMD/System, and a
  file is a module (has imports/exports), emit TS6131 per-file. Only fires
  when module is NOT explicitly set (TS6082 handles explicit misconfiguration).
  Also skips when `out` (removed option) is used (TS5102 handles it).
  Span covers statement name for class/function, full statement for variables.

  **Files:** `Checker.kt`

- [ ] **16b. TS2354 — cannot use importHelpers without tslib** (~7 tests) — *deferred*

  Complex: requires detecting helper-needing syntax (extends, decorators,
  esModuleInterop default import/export) per target/module/options. Deferred.

- [ ] **16c. TS1148 — cannot use imports with --module none** (~3 tests)

  When `module: "none"` is set, import/export statements should produce
  TS1148: "Cannot use imports, exports, or module augmentations when
  '--module' is 'none'."

  **Files:** `Checker.kt`

- [ ] **16d. TS1218 — export assignment not allowed in System modules** (~3 tests)

  `export = expr` in system module format should produce TS1218.

  **Files:** `Checker.kt`

- [ ] **16e. TS5055 — cannot write file (output overwrites input)** (~5 tests)

  When output file path would overwrite an input source file, emit
  TS5055. Check `outDir`/`outFile` against input file paths.

  **Files:** `TypeScriptCompiler.kt`

- [x] **16f. TS7019 — rest parameter implicitly has 'any[]' type** (+3 tests)

  Rest parameters without type annotation now emit TS7019 instead of
  being skipped. Span covers `...name`. Previously all rest params were
  silently skipped with `if (param.dotDotDotToken) continue`.

  **Files:** `Checker.kt`

- [ ] **16g. TS2695 — left side of comma operator is unused** (~5 tests)

  Detect `expr, expr` where the left side has no side effects.
  Only for comma operators in non-for-loop positions.

  **Files:** `Checker.kt`

- [x] **16h. TS1185 — merge conflict markers** (+4 tests)

  Scan source for `<<<<<<<`, `=======`, `>>>>>>>`, `|||||||` at the
  start of lines. Report TS1185 with 7-char span. Handles both 2-way
  and 3-way (diff3) merge conflict markers.

  **Files:** `Checker.kt`

- [ ] **16i. TS2450/TS2448 — block-scoped variable used before declaration** (~7 tests)

  Detect use of `let`/`const` variables before their declaration within
  the same block scope. TS2448 for variables, TS2450 for block-scoped.

  **Files:** `Checker.kt`

- [ ] **16j. Fix false-positive TS7006 for rest parameters** (~3 tests)

  Rest parameters shouldn't get TS7006 (should get TS7019 instead).
  Currently both may be emitted. Fix the TS7006 check to skip `...` params.

  **Files:** `Checker.kt`

- [ ] **16k. Fix false-positive TS6133 for type-only imports** (~3 tests)

  `import type { X }` should not flag X as unused since it's type-only.
  Also fix TS6133 for type parameters used only in type positions.

  **Files:** `Checker.kt`

- [ ] **16l. TS2802 — iterators/generators need target ES2015+** (~4 tests)

  When target < ES2015 and code uses `for...of` with iterators or
  `function*` generators, emit TS2802 if downlevelIteration is not set.

  **Files:** `Checker.kt`

---

## BLOCKED — not planned for Phase 3

- **`.symbols` baselines** (14,015 tests) — require full symbol resolution display
- **`.types` baselines** (14,015 tests) — require full type inference and display
- **`.sourcemap.txt` / `.js.map` baselines** — require source map generation
- **`.trace.json` baselines** — require module resolution tracing
- **`__generator` state machine** — complex async-to-generator downlevel
- **Private field WeakMap transform** — ~4 tests
- **`outFile` AMD bundling** — ~3 tests
- **Inline sourcemaps** — ~4 tests
- **`module: "preserve"`** — 1 test

---

## Test scale reference (projected after Phase 3 infrastructure)

| Category | Count | Initial pass rate (est.) |
|----------|-------|--------------------------|
| JS emit (bare name) | ~5,442 | ~94% (current) |
| JS emit (parameterized) | ~3,962 | ~80-90% (most use same code paths) |
| `.errors.txt` (bare + parameterized) | ~9,055 | ~1-5% (mostly parser-only errors initially) |
| **Total** | **~18,459** | **~55-60%** |

---

## Success criteria

1. All TypeScript compiler test cases with `.js` and `.errors.txt` baselines are
   represented as `@Test` functions in the build — including parameterized variants
2. `.errors.txt` tests serve as the primary scorecard for type checker development
3. Parser diagnostics use correct TypeScript error codes and positions
4. Checker emits at least the top-5 highest-frequency diagnostic codes
5. No regressions in currently passing JS emit tests
6. Clear per-session progress metric: `X / 18,459 tests passing`
