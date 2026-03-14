# Phase 3 Plan — Test Infrastructure & Diagnostic-Driven Type Checker

**Prerequisite:** Phase 2 (complete) built the Binder, Checker, and Types infrastructure.
The pipeline is: Scanner → Parser → Binder → Checker → Transformer → Emitter.

**Phase 3 goal:** Make `.errors.txt` baseline tests the primary metric driving type checker
development, and bring the full TypeScript compiler test suite into the build.

**Reference:** `TYPESCRIPT-TEST-HARNESS.md` documents the original TypeScript test harness
behavior — baseline formats, comparison algorithm, and parameterized test expansion.

---

## Current State

- **10,595 tests**, 5,939 passing (56.0%), 4,653 failing
- **JS emit bare-name:** 5,413 tests, 5,122 passing (94.6%)
- **JS emit parameterized:** 1,114 tests, 498 passing (44.7%)
- **Error baselines:** 4,035 tests, 91 passing (2.3%)
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

- [ ] **3f. Class static property parsing** (Phase 2 item 9a) — *deferred: 1 test, parser recovery*

  `static f = 3;` is misparsed as two expression statements.
  - Key test: `class2`
  - Fix area: `Parser.kt: parseClassElement()`

- [ ] **3g. Non-`this`-prefixed property initializers** (Phase 2 item 9b) — *deferred: 1 test*

  `p1 = 0;` in constructor instead of `this.p1 = 0;`.
  - Key test: `classUpdateTests`
  - Fix area: `Transformer.kt`

- [ ] **3h. Parser error recovery** (Phase 2 items 10a-10b, ~45 tests) — *deferred to after 4a-4b*

  Audit and implement the top error recovery patterns to match TypeScript's output.
  - Fix area: `Parser.kt`

- [ ] **3i. Enum non-literal cross-file initializers** (Phase 2 item 11b) — *deferred: 2 tests*

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

- [ ] **5b. Implement high-frequency checker diagnostics**

  Prioritize by how many `.errors.txt` baselines each code appears in. The most
  impactful codes (estimated from baseline corpus):

  | Code | Message | Frequency |
  |------|---------|-----------|
  | TS2304 | Cannot find name 'X' | Very high |
  | TS2322 | Type 'X' is not assignable to type 'Y' | Very high |
  | TS2339 | Property 'X' does not exist on type 'Y' | High |
  | TS2345 | Argument of type 'X' is not assignable to parameter of type 'Y' | High |
  | TS2564 | Property 'X' has no initializer | Medium |
  | TS2511 | Cannot create an instance of an abstract class | Medium |
  | TS2355 | A function whose declared return type is not 'void'/'any' must return a value | Medium |
  | TS5107 | Option 'X' is deprecated | Already implemented |

  Each diagnostic code requires specific type checking logic. Implement incrementally,
  measuring `.errors.txt` pass rate after each batch.

  **File:** `Checker.kt`

- [ ] **5c. Measure progress and iterate**

  After each batch of diagnostics, run the full suite and record the `.errors.txt`
  pass rate. Track progress toward the goal of matching TypeScript's diagnostic output.

### 6. Decorator metadata diagnostics (Phase 2 item 11a)

- [ ] **6a. Decorator metadata type serialization** (~3 tests)

  `decoratorMetadataNoLibIsolatedModulesTypes`, `decoratorMetadataTypeOnlyExport`,
  `metadataOfUnion`. Requires type serialization — significantly more work than
  other checker integration.

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
