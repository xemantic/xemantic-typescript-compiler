# Test Fix Plan — Phase 2: Type Checker

**Current State:** 5,442 tests, 320 failing (94.1% passing, 5,122 passing)

## Phase 1 Summary (complete)

Phase 1 built a full transpiler pipeline: Scanner → Parser → Transformer → Emitter.
Starting from 0, the project reached **4,359 / 4,509 tests passing (96.7%)** on the
JS-emit-only test suite. All remaining 150 JS-emit failures are blocked by type checker,
parser error recovery, missing complex helpers, or module resolution infrastructure.

Re-enabling `.d.ts` baseline tests (previously guarded by `hasDtsSection` in
`build.gradle.kts`) added **933 new tests**, of which **760 pass immediately** since
`TypeScriptTestSupport.stripDtsSection()` already strips declaration output from baselines.
173 new failures are mainly the same blocked categories as Phase 1.

The full history of Phase 1 fixes is in the git log. Key files and line counts:
- `Parser.kt` (4,504 lines) — full TypeScript syntax parsing
- `Transformer.kt` (~11,500 lines) — module transforms, downlevel emit, decorators
- `Emitter.kt` (3,673 lines) — AST → JavaScript text
- `Scanner.kt` (1,332 lines) — tokenization
- `Ast.kt` (1,901 lines) — all AST node types including full `TypeNode` hierarchy
- `Checker.kt` (~930 lines) — MVP type checker (const enum, import elision)
- `Binder.kt` (~1,200 lines) — symbol table population
- `Types.kt` (~180 lines) — Symbol, SymbolFlags, ConstantValue

---

## COMPLETED ITEMS (for reference)

### 0. Analysis (complete)

- [x] **0a. Categorize all failures** → `FAILURES.md`
- [x] **0b. Study TypeScript checker architecture** → `DESIGN-TYPE-CHECKER.md`
- [x] **0c. Design Kotlin type checker architecture**

### 1. Foundation — Symbol and Type infrastructure (complete)

- [x] **1a. Symbol data class**
- [x] **1b. Type sealed class hierarchy**
- [x] **1c. SymbolTable and scope chain**

### 2. Binder (complete)

- [x] **2a. Implement Binder.kt**
- [x] **2b. Declaration merging**
- [x] **2c. Module symbol resolution**

### 3. Type Checker MVP — infrastructure (complete)

- [x] **3c. Const enum folding** — Checker computes enum values incl. cross-file, nested namespaces, complex initializers
- [x] **3d. Import elision** — Checker tracks import references in value positions
- [x] **3e. Unused import/variable detection**

### 4. Pipeline integration (partially complete)

- [x] **4a. Wire Binder + Checker into TypeScriptCompiler.kt**
- [x] **4b. Pass checker info to Transformer** — `resolveConstEnumMemberAccess()`, `isConstEnumAlias()` now called from Transformer

---

## QUEUE (execute top-to-bottom)

### 7. Cross-file namespace import resolution (~15-25 tests)

The biggest infrastructure gap. Currently `resolveAlias` handles `ImportEqualsDeclaration` and `ImportSpecifier`, but NOT `NamespaceImport` (`import * as Foo from "./mod"`). The checker can't follow namespace imports to resolve module exports, which blocks const enum inlining and import elision for cross-file cases.

- [ ] **7a. Add NamespaceImport resolution to Checker.resolveAlias()** — When the alias declaration is a `NamespaceImport`, resolve the module specifier to a file, create a synthetic "module" symbol whose `exports` are the target file's `locals`. This lets `resolveNamePath("Foo.ConstEnum", ...)` traverse `Foo` → module exports → `ConstEnum`.
  - **Key test:** `constEnumNamespaceReferenceCausesNoImport2` — `import * as Foo from "./reexport"` then `Foo.ConstFooEnum.Some` should inline to `0`
  - **Fix area:** `Checker.kt: resolveAlias()`, add `is NamespaceImport` branch
  - **Also fixes:** `constEnumExternalModule`, `constEnumNoEmitReexport`, `constEnumNoPreserveDeclarationReexport`, `amdModuleConstEnumUsage`

- [ ] **7b. Add default import resolution** — `import Foo from "./mod"` creates a symbol for the default binding. Resolve it to the target module's `default` export.
  - **Fix area:** `Checker.kt: resolveAlias()`, handle `ImportDeclaration` with `clause.name` (default import)

- [ ] **7c. Export re-export type elision** — `export { i } from "./server"` where `i` is an interface should be elided. The Transformer needs to check if re-exported names are value or type in the source module.
  - **Key test:** `es6ExportClauseWithoutModuleSpecifier` — `export { i, m as instantiatedModule }` should drop `i` (interface) and `uninstantiated` (type-only namespace)
  - **Fix area:** `Transformer.kt: transformExportDeclaration()` — call `checker.isValueAliasDeclaration()` or a new method to check if re-exported specifier is type-only
  - **Requires:** 7a/7b for cross-file symbol lookup

### 8. CommonJS transform gaps (~10-15 tests)

The CommonJS transform (`transformToCommonJS`) rewrites identifier references to use `require()` bindings, but misses some contexts.

- [ ] **8a. Binding pattern computed property rewriting** — `import { a } from "./a"; function fn({ [a]: value })` — the `[a]` computed property key should become `[a_1.a]` in CommonJS output.
  - **Key test:** `computedPropertyNameWithImportedKey`
  - **Fix area:** `Transformer.kt: transformToCommonJS()` — the identifier rewriting visitor needs to traverse function parameter binding patterns
  - **Also affects:** `declarationEmitComputedNameConstEnumAlias`

- [ ] **8b. Export alias qualification** — `export const pick = () => (0, exports.pick)()` — some exports need `(0, exports.X)` form for correct `this` binding.
  - **Key tests:** `conflictingDeclarationsImportFromNamespace1`, `conflictingDeclarationsImportFromNamespace2`
  - **Fix area:** `Transformer.kt: transformToCommonJS()` — self-referencing exported names need qualification

### 9. Class property transform fixes (~10 tests)

Several tests fail because class properties aren't correctly handled.

- [ ] **9a. Static property with modifier keyword name** — `static f = 3;` is parsed as `ExpressionStatement("static")` + `ExpressionStatement(f = 3)` instead of `PropertyDeclaration(static, f, 3)`.
  - **Key test:** `class2` — expects `foo.f = 3;` outside class, gets `static; f = 3;` inside constructor
  - **Fix area:** `Parser.kt: parseClassElement()` — need to recognize `static` followed by identifier as a property declaration, not two expression statements. This is a parser error recovery issue.

- [ ] **9b. Non-`this`-prefixed property initializers** — `p1 = 0;` in constructor instead of `this.p1 = 0;`
  - **Key test:** `classUpdateTests` — property-to-constructor move missing `this.` prefix
  - **Fix area:** `Transformer.kt: movePropertyToConstructor()` or equivalent

### 10. Parser error recovery (~45 tests)

These tests expect TypeScript's exact error recovery output. Independent of type checker — only touches `Parser.kt`.

- [ ] **10a. Audit the top 10 error recovery patterns** — Compare parser output against TypeScript baselines for the most common failure patterns. Categorize by:
  - Missing semicolons / incomplete statements
  - Invalid arrow function syntax
  - Incomplete type annotations
  - Missing identifiers / unexpected tokens

- [ ] **10b. Implement recovery for high-value patterns** — Fix the patterns that unblock the most tests. Measure net impact after each fix.

### 11. Remaining checker-dependent tests

- [ ] **11a. Decorator metadata with type info** (~3 tests) — `decoratorMetadataNoLibIsolatedModulesTypes`, `decoratorMetadataTypeOnlyExport`, `metadataOfUnion`. Requires type serialization — significantly more work than other checker integration.

- [ ] **11b. Enum non-literal cross-file initializers** (~2 tests) — Regular (non-const) enum member initializers that reference imported values should resolve to their constant value in the enum IIFE body.
  - **Key test:** `importElisionEnum` — `MyEnum { a = MyEnumFromModule.a }` should resolve to `MyEnum { a = 0 }`
  - **Fix area:** `Transformer.kt: transformEnumDeclaration()` — use checker to resolve imported enum member values

### 4c. Enable `.errors.txt` tests (deferred)

- [ ] **4c. Enable `.errors.txt` tests** — Uncomment the error baseline test generation in `build.gradle.kts` (search `TODO: Re-enable when type checker is implemented`). This will add ~9,055 error diagnostic tests. Initially almost all will fail — use them as a progress metric, not a regression guard.

### Deprioritized items (from original plan)

- [ ] **3a. Basic type resolution** — Resolve `TypeNode` AST nodes to `Type` objects. Not needed for current test fixes.
- [ ] **3b. Type inference from initializers** — Infer types for variables/constants. Not needed for current test fixes.

---

## BLOCKED — infrastructure not planned for Phase 2

- **`__generator` state machine** — Complex coroutine transform for async-to-generator downlevel (below ES2017). ~5 tests.
- **Private field WeakMap transform** — ~4 tests.
- **`outFile` AMD bundling** — ~3 tests.
- **Inline sourcemaps** — ~4 tests.
- **`module: "preserve"`** — 1 test.
- **Computed property name full hoisting** (52 temp vars) — 1 test.

---

## Test scale reference

| Category | Count | Status |
|----------|-------|--------|
| JS emit tests (no `.d.ts`) | 4,509 | ~4,362 passing (96.7%) |
| JS emit tests (with `.d.ts` stripped) | 933 | ~760 passing (81.5%) |
| **Total JS emit tests** | **5,442** | **5,122 passing (94.1%)** |
| Error diagnostic tests (`.errors.txt`) | ~9,055 | Not yet enabled |
| Declaration emit tests (`.d.ts` only) | ~215 | Not applicable without full type checker |

---

## Phase 2 success criteria

1. All type-checker-blocked JS emit tests pass (~30 tests): const enum inlining, import elision, decorator metadata
2. `.errors.txt` test infrastructure is enabled and basic diagnostics are emitted
3. `Symbol` and `Type` infrastructure supports incremental checker expansion
4. No regressions in the 5,122 currently passing tests

---

## Remaining 320 failures — breakdown by root cause

Derived from investigation on 2026-03-14. Numbers are approximate (some tests have multiple issues).

| Category | ~Count | Key blocker | Queue item |
|----------|--------|-------------|------------|
| Cross-file module/import resolution | ~50 | Checker doesn't follow namespace imports, re-exports | 7a-7c |
| `.d.ts` baseline differences | ~60 | Declaration emit not implemented | BLOCKED |
| Parser error recovery | ~45 | Parser doesn't match TypeScript's exact recovery | 10a-10b |
| CommonJS transform gaps | ~30 | Binding pattern rewriting, export self-reference | 8a-8b |
| AMD/UMD/System module issues | ~25 | Module format-specific transforms | Partially 7a |
| Class property transforms | ~15 | Static property parsing, `this.` prefix | 9a-9b |
| Missing helpers (`__generator`, WeakMap) | ~15 | Complex state machine / runtime transforms | BLOCKED |
| Decorator metadata with types | ~5 | Type serialization | 11a |
| Other (control chars, edge cases) | ~75 | Various | — |
