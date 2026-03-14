# Test Fix Plan — Phase 2: Type Checker

**Current State:** 5,442 tests, 323 failing (94.1% passing)

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
- `Transformer.kt` (11,333 lines) — module transforms, downlevel emit, decorators
- `Emitter.kt` (3,673 lines) — AST → JavaScript text
- `Scanner.kt` (1,332 lines) — tokenization
- `Ast.kt` (1,901 lines) — all AST node types including full `TypeNode` hierarchy

---

## QUEUE (execute top-to-bottom)

### 0. Analysis — understand current failures and type checker scope

- [ ] **0a. Categorize all 323 failures** — Run the full test suite, collect all failing test names, and categorize them by root cause:
  - (A) Parser error recovery (~50) — tests expecting TypeScript's exact error recovery output
  - (B) Type-checker-driven transforms (~30) — import elision, const enum inlining, decorator metadata
  - (C) Missing helpers (~15) — `__esDecorate`, `__setFunctionName`, `__generator`, WeakMap
  - (D) Module resolution (~20) — paths/baseUrl, symlinks, file ordering, reference resolution
  - (E) Complex transforms (~15) — CommonJS post-increment exports, super access temp vars
  - (F) Newly exposed failures from `.d.ts` tests (~173) — needs triage
  - (G) Internal comments (~10) — per-token comment fields
  - (H) Other (~10) — control chars, `reScanGreaterToken`, binary input

  Save the categorized list to `FAILURES.md` for tracking.

- [ ] **0b. Study the TypeScript checker architecture** — Read TypeScript's checker design from public documentation and any available source. Key concepts to understand:
  - Binder: scope creation, symbol table population, declaration merging
  - Checker: type inference, structural subtyping, assignability, narrowing
  - How the checker communicates with the transformer (symbol flags, const enum values)
  - Which transforms depend on type info vs which are purely syntactic

- [ ] **0c. Design the Kotlin type checker architecture** — Design document (not code) answering:
  - What `Type` sealed class hierarchy is needed?
  - What `Symbol` class fields are needed?
  - How does the Binder walk the AST?
  - Where does the Checker phase fit in the `TypeScriptCompiler.kt` pipeline?
  - Which existing `Transformer.kt` code paths need checker input?
  - What is the MVP scope to unblock the most tests?

### 1. Foundation — Symbol and Type infrastructure

- [ ] **1a. Create `Symbol` data class** — A symbol represents a named entity: variable, function, class, interface, namespace, enum, type alias, parameter, property. Fields: `name`, `flags` (enum set), `declarations` (list of AST nodes), `type` (resolved type), `members` (child symbol table for classes/namespaces).

- [ ] **1b. Create `Type` sealed class hierarchy** — Runtime type representation (distinct from `TypeNode` which is syntax). MVP types:
  - `AnyType`, `UnknownType`, `NeverType`, `VoidType`, `UndefinedType`, `NullType`
  - `StringType`, `NumberType`, `BooleanType`, `BigIntType`, `SymbolType`
  - `LiteralType` (string/number/boolean literal types)
  - `ObjectType` (with members symbol table)
  - `FunctionType` / `SignatureType`
  - `UnionType`, `IntersectionType`
  - `TypeReference` (generic instantiation)
  - `EnumType`
  - `TypeParameter`

- [ ] **1c. Create `SymbolTable` and scope chain** — A `SymbolTable` is `MutableMap<String, Symbol>`. Scopes form a chain: function scope → block scope → module scope → global scope. Each scope has a `SymbolTable` for its declarations.

### 2. Binder — populate symbol tables

- [ ] **2a. Implement `Binder.kt`** — Walk the AST and:
  - Create symbols for all declarations (variables, functions, classes, interfaces, enums, namespaces, type aliases, parameters)
  - Handle `var` hoisting (function-scoped) vs `let`/`const` (block-scoped)
  - Handle function declaration hoisting
  - Build scope chains (module → function → block)
  - Handle `export` flags on symbols

- [ ] **2b. Declaration merging** — TypeScript allows:
  - Interface + interface → merged interface
  - Namespace + namespace → merged namespace
  - Class + namespace → class with static additions
  - Enum + namespace → enum with additional members
  - Function + namespace → function with properties

- [ ] **2c. Module symbol resolution** — Connect import/export declarations to their target symbols across files (for multi-file test cases).

### 3. Type Checker — MVP (unblocks ~30 tests)

- [ ] **3a. Basic type resolution** — Resolve `TypeNode` AST nodes to `Type` objects:
  - Keyword types (`string`, `number`, `boolean`, etc.) → primitive types
  - Type references (`Foo`, `Foo<T>`) → look up symbol, resolve generics
  - Union/intersection types → compose from resolved members
  - Array/tuple types
  - Function types

- [ ] **3b. Type inference from initializers** — Infer types for:
  - `const x = 42` → `x: 42` (literal type)
  - `let x = "hello"` → `x: string` (widened type)
  - `const x = { a: 1 }` → object type with inferred members
  - Enum member values

- [ ] **3c. Const enum folding** — With type info, resolve const enum member references to their computed values. This unblocks ~6 tests: `constEnums`, `constEnumExternalModule`, `constEnumNoEmitReexport`, etc.

- [ ] **3d. Import elision** — With type info, determine which imports are type-only (used only in type positions) and can be elided from JS output. This unblocks ~7 tests: `aliasOnMergedModuleInterface`, `importElisionEnum`, etc.

- [ ] **3e. Unused import/variable detection** — Flag imports and locals that are never referenced in value positions. This unblocks ~2 tests.

### 4. Integrate checker into pipeline

- [ ] **4a. Wire `Binder` + `Checker` into `TypeScriptCompiler.kt`** — Insert after Parser, before Transformer:
  ```
  Parser.parse() → Binder.bind() → Checker.check() → Transformer.transform() → Emitter.emit()
  ```

- [ ] **4b. Pass checker info to Transformer** — The Transformer needs:
  - `isTypeOnly(importDecl)` — for import elision
  - `getConstEnumValue(member)` — for const enum inlining
  - `getSymbol(node)` — for decorator metadata type serialization
  - `isInstantiated(module)` — for module alias elision

- [ ] **4c. Enable `.errors.txt` tests** — Uncomment the error baseline test generation in `build.gradle.kts` (search `TODO: Re-enable when type checker is implemented`). This will add ~9,055 error diagnostic tests. Initially almost all will fail — use them as a progress metric, not a regression guard.

### 5. Remaining Phase 1 fixes (unblocked by type checker)

After the checker MVP is working:

- [ ] **5a. Decorator metadata with type info** (~3 tests) — `decoratorMetadataNoLibIsolatedModulesTypes`, `decoratorMetadataTypeOnlyExport`, `metadataOfUnion`
- [ ] **5b. `isolatedModules` emit blocking** (~2 tests) — `isolatedModulesExportDeclarationType`, `isolatedModulesNoEmitOnError`
- [ ] **5c. Enum non-literal initializers** (~2 tests) — cross-file constant resolution now possible with checker

### 6. Parser error recovery (independent of type checker)

These ~45 tests need TypeScript's exact error recovery behavior. They can be worked on in parallel with the type checker since they only touch `Parser.kt`:

- [ ] **6a. Audit error recovery patterns** — Compare parser error recovery against TypeScript's parser for the most common patterns: missing semicolons, incomplete type annotations, invalid arrow functions, missing identifiers.
- [ ] **6b. Fix recoverable cases** — Implement recovery for the easiest patterns first, measuring net test impact.

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
| JS emit tests (no `.d.ts`) | 4,509 | 4,359 passing (96.7%) |
| JS emit tests (with `.d.ts` stripped) | 933 | 760 passing (81.5%) |
| **Total JS emit tests** | **5,442** | **5,119 passing (94.1%)** |
| Error diagnostic tests (`.errors.txt`) | ~9,055 | Not yet enabled |
| Declaration emit tests (`.d.ts` only) | ~215 | Not applicable without full type checker |

---

## Phase 2 success criteria

1. All type-checker-blocked JS emit tests pass (~30 tests): const enum inlining, import elision, decorator metadata
2. `.errors.txt` test infrastructure is enabled and basic diagnostics are emitted
3. `Symbol` and `Type` infrastructure supports incremental checker expansion
4. No regressions in the 5,119 currently passing tests
