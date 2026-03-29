# Phase 4 — Structural Type Checker

**Status (2026-03-29):** 7,661 / 10,077 tests passing (76.0%).

## Goal

Implement structural type checking to unlock the ~2,400 remaining test failures.
Design for future parallel checking using Kotlin coroutines (inspired by tsgo's goroutine model).

## Architecture (inspired by tsgo)

### Key design decisions

**1. Type hierarchy — Kotlin sealed classes**

Replace the current string-based type representation (`"number"`, `"@MyType"`, `"|A | B"`)
with a proper `Type` sealed class hierarchy. Kotlin sealed classes give us exhaustive
`when` matching and smart casts — more idiomatic than tsgo's `TypeData` interface approach.

```kotlin
sealed class Type {
    abstract val flags: TypeFlags
    val id: Int = nextId++  // per-checker unique ID
}

class IntrinsicType(override val flags: TypeFlags, val name: String) : Type()
class LiteralType(override val flags: TypeFlags, val value: Any) : Type()  // string/number/boolean
class ObjectType(override val flags: TypeFlags) : Type() {
    var members: SymbolTable? = null       // lazily resolved
    var properties: List<Symbol>? = null
    var callSignatures: List<Signature>? = null
    var constructSignatures: List<Signature>? = null
}
class InterfaceType(...) : ObjectType(...)  // adds typeParameters, baseTypes
class TypeReference(...) : ObjectType(...)  // adds target (generic), resolvedTypeArguments
class UnionType(val types: List<Type>) : Type()
class IntersectionType(val types: List<Type>) : Type()
class TypeParameter(...) : Type()  // adds constraint, default
// ... etc
```

**2. Parallelism preparation — LinkStore pattern**

tsgo's key insight: checker-local metadata stored in side maps, not on shared AST nodes.
We prepare for this from the start:

```kotlin
class Checker(...) {
    // Checker-local type cache — NOT on AST nodes
    private val nodeTypes = HashMap<Long, Type>()        // nodeKey → resolved type
    private val symbolTypes = HashMap<Int, Type>()       // symbol.id → resolved type
    private val declaredTypes = HashMap<Int, Type>()     // symbol.id → declared type
}
```

When we later create N parallel Checker instances, each will have its own maps.
The shared AST and binder output remain immutable.

**3. Lazy type resolution**

Types are resolved on-demand (matching both TS and tsgo):
- `getTypeOfSymbol(symbol)` — checks `symbolTypes` cache, computes if absent
- `getTypeFromTypeNode(node)` — checks `nodeTypes` cache, computes if absent
- `resolveStructuredTypeMembers(type)` — resolves members lazily on ObjectType

**4. Relation engine**

Structural comparison via `isTypeRelatedTo` → `structuredTypeRelatedTo` →
`propertiesRelatedTo`. Cached in relation maps keyed by `(source.id, target.id)`.

```kotlin
enum class Ternary { True, Maybe, False }

class Relation {
    private val cache = HashMap<Long, Ternary>()  // pack two Int IDs into Long
}

// Five relation instances (same as TS/tsgo)
private val subtypeRelation = Relation()
private val assignableRelation = Relation()
private val comparableRelation = Relation()
private val identityRelation = Relation()
```

**5. Future: N-checker parallelism via coroutines**

```kotlin
// Future (Phase 4g) — not yet implemented
class CheckerPool(private val program: Program, private val checkerCount: Int = 4) {
    private val checkers = List(checkerCount) { Checker(program) }
    private val fileAssignments = program.files.withIndex().associate { (i, f) ->
        f to checkers[i % checkerCount]
    }

    suspend fun checkAllFiles() = coroutineScope {
        checkers.map { checker ->
            launch { checker.checkAssignedFiles() }
        }.joinAll()
    }
}
```

---

## Completed infrastructure (Phase 4a, items 0–9)

All complete. Type hierarchy, type resolution, structural comparison engine, generic
instantiation, expression type inference, parallel checking pool are in place.

### Completed Phase 4b items (10–15a)

- [x] **10a/10b/10c** — TS2322 wired to Type engine (var decl, return, assignment — conservative)
- [x] **11a-11d** — Expression type inference (object literal, array, arrow/function, identifier)
- [x] **12a/12b/12c** — TS2345 union types, union call signatures, TS2769 overload diagnostics
- [x] **15a** — TS2300 duplicate class members

---

## Phase 5 — Data-Driven Test Gains

### Failure landscape (2026-03-29 analysis)

```
Total failing: 2,416
  Error baseline: 680  (28% of all tests)
  JS emit:        257  (10%)
  Both:         1,479  (overlap — tests with errors AND JS variants)

Error baseline breakdown:
  426 tests have 0 FPs (only need MORE diagnostics)
   22 tests have 0 FNs (only need FEWER diagnostics)
  243 tests have both FPs and FNs

Eliminating ALL FPs would fix only 22 tests.
The bottleneck is MISSING diagnostic coverage, not incorrect diagnostics.
```

### Impact-ranked code targets

Tests that need ONLY this code (0 other issues):

| Code | Description | Pure | Total | Complexity |
|------|-------------|------|-------|------------|
| TS2322 | Type not assignable | 41 | 102 | Relax existing guards |
| TS2339 | Property doesn't exist | 15 | 44 | Unblock module augmentation |
| TS2345 | Arg type mismatch | 12 | 47 | Relax `isSimpleCheckableType` |
| TS2420 | Class incorrectly implements | 8 | 10 | New check, infra exists |
| TS2454 | Used before assigned | 8 | 18 | Control flow or heuristics |
| TS2416 | Property incompatible w/ base | 7 | 13 | New check, needs base types |
| TS2343 | Type constraint violation | 6 | 6 | Generic constraints |
| TS2792 | Cannot use require in ESM | 6 | 6 | Module format check |
| TS2307 | Cannot find module | 5 | 8 | Module resolution |
| TS2802 | Iterator type checking | 4 | 4 | Type protocol check |

If TS2322+TS2339+TS2345 were perfect: **97 tests pass**.
If ALL checker diagnostics perfect: **231 tests pass**.
If ALL parser diagnostics perfect: **37 tests pass**.
JS emit (257 failures): **74 within 4 diff lines, 148 within 10**.

---

### Track A — Deepen TS2322 (target: +30-40 tests)

The single highest-ROI change. 42 diff tests + 171 none-produced = 213 pure TS2322 tests.

- [x] **A0. Analyze the 41 pure-TS2322 tests**

  **Result:** 42 diff tests + 171 none-produced = 213 total pure TS2322 tests.
  Full analysis in `ANALYSIS-A0-TS2322.md`. Key findings:
  - The bottleneck is the `useNewEngine` guard: only fires for intrinsic↔intrinsic,
    nullish→anything, objectLiteral→anything. Everything else falls to old string system.
  - Top categories: intrinsic↔intrinsic in new contexts (64), named↔intrinsic (52),
    function→function (31), union→type (27), generic→generic (26), null/undef→named (26).
  - 5 FP tests (wrong TS2322 on wrong line/wrong direction).

  **Deliverable:** `ANALYSIS-A0-TS2322.md`

- [x] **A1. Relax `useNewEngine` guard + chained assignments + class property init**

  Extracted `canUseTypeEngine()` helper used by all three check functions.
  Extended guard: null/undefined→Interface/Reference, object literal→intrinsic.
  Wider relaxation caused 42 FPs (object literal→named, intrinsic→union) — reverted to
  conservative expansion. Added chained assignment recursion (`a = b = c = null`),
  PropertyDeclaration initializer checking, `inferSimpleExprType` for BinaryExpression(=),
  `isSimpleLiteral` for assignment chains. **+1 test** (chainedAssignment2).

  **File:** `Checker.kt`
  **Result:** 7,662 / 10,077 (76.0%)

- [ ] **A2. Add class member initializer checking**

  `PropertyDeclaration` with initializer + type annotation not currently traversed.
  Add to class member loop in `checkTypeAssignabilityInStatements`.

  **File:** `Checker.kt` — class member traversal
  **Target:** ~5-10 tests

- [ ] **A3. Relax TS2322 for assignment expressions**

  `x = value` where `x` has a known type from annotation or prior declaration.

  **File:** `Checker.kt` — `checkAssignmentExpression`

- [ ] **A4. Function signature comparison for TS2322**

  Compare function types structurally: parameter compatibility + return type.
  `() => void` not assignable to `() => boolean`, etc.

  **File:** `Checker.kt` — `checkTypeRelatedTo` / signature comparison
  **Target:** ~31 tests (function→function category)

### Track B — JS Emit Fixes (target: +30-40 tests)

Independent of type checker work. 257 tests failing, 74 within 4 diff lines.

- [ ] **B1. CJS export ordering**

  Several tests differ only in `exports.X = ...` ordering or
  `Object.defineProperty(exports, ...)` vs direct assignment.
  Fix export statement ordering in CommonJS transform.

  **File:** `Transformer.kt` — `transformToCommonJS`
  **Target:** ~10 tests

- [ ] **B2. Type-only import elimination**

  Imports that resolve to type-only symbols should be elided from JS output.
  Currently some type imports leak through as `require()` calls.

  **File:** `Checker.kt` (isTypeOnly), `Transformer.kt` (elision)
  **Target:** ~10 tests

- [ ] **B3. Multi-file emit ordering**

  File ordering in multi-file output doesn't always match TypeScript's
  topological sort. Fix dependency resolution.

  **File:** `TypeScriptCompiler.kt`
  **Target:** ~10 tests

- [ ] **B4. Source map improvements**

  Inline source map generation (base64) and URL path calculation.
  ~10 tests need only sourcemap fixes.

  **File:** `Emitter.kt` / new `SourceMapGenerator.kt`
  **Target:** ~10 tests

### Track C — New Diagnostic Categories (target: +30-40 tests)

Well-defined checks using existing infrastructure.

- [ ] **C1. TS2420 — class incorrectly implements interface**

  For each `implements` clause, check that the class has all required
  interface members with compatible types. Uses structural comparison (item 4).

  **File:** `Checker.kt` — new `checkClassImplementsInterface`
  **Target:** 8 pure tests, 10 total

- [ ] **C2. TS2416 — property type incompatible with base type**

  When a class property overrides a base class property, verify the types
  are compatible.

  **File:** `Checker.kt` — new `checkPropertyOverride`
  **Dependency:** base type resolution (already implemented)
  **Target:** 7 pure tests, 13 total

- [ ] **C3. TS2792 — cannot use require() in ES module**

  Emit when `require()` is used in a file detected as ES module format.
  Simple check: module format + `require` call detection.

  **File:** `Checker.kt`
  **Target:** 6 pure tests

- [ ] **C4. TS2343/TS2344 — type does not satisfy constraint**

  When a type argument doesn't satisfy the constraint on a type parameter,
  emit "Type 'X' does not satisfy the constraint 'Y'".

  **File:** `Checker.kt` — generic constraint checking
  **Target:** 10 pure tests combined

### Track D — Unblock Deferred Items (target: +25-35 tests)

Fix the blockers that prevent widening TS2339 and TS2345.

- [ ] **D1. Basic module augmentation resolution**

  Merge `declare module "X" { ... }` exports from all files into the module's
  symbol table. This unblocks TS2339 widening (item 13a) which had 5 FP
  regressions from unresolved augmented interface members.

  **File:** `Checker.kt` — `checkUnresolvedNames` setup
  **Unblocks:** 13a (TS2339 widening)

- [ ] **D2. Typeof narrowing for property access**

  Basic `typeof x === "string"` → narrow x to string in the if body.
  This unblocks the 1 control flow FP in TS2339 widening.

  **File:** `Checker.kt` — new narrowing infrastructure
  **Unblocks:** 13a (TS2339 widening)

- [ ] **D3. Relax TS2339 this-only guard (item 13a)**

  After D1+D2, remove the `this`-only guard for property access checking.
  Check `expr.prop` for any expression with a resolved object type.

  **File:** `Checker.kt` — `checkSinglePropertyAccess`
  **Target:** 15 pure tests, 44 total

- [ ] **D4. Relax TS2345 isSimpleCheckableType guard**

  With structural comparison working, allow checking against
  object/interface parameter types (not just primitives).

  **File:** `Checker.kt` — `checkArgumentsAgainstSignature`
  **Target:** 12 pure tests, 47 total

### Track E — Cross-File Resolution (target: +15-20 tests)

Improve multi-file name resolution and diagnostics.

- [ ] **E1. TS2307 — Cannot find module**

  Emit when an import specifier doesn't resolve to any file in the compilation.
  Check `import ... from "X"` against available files.

  **File:** `Checker.kt` — module resolution checking
  **Target:** 5 pure tests, 8 total

- [ ] **E2. Cross-file TS2300/TS2393 duplicate detection**

  Detect duplicate function/variable declarations across files.

  **File:** `Checker.kt` — `checkDuplicateDeclarations`
  **Target:** 4 pure tests

- [ ] **E3. Cross-file TS2448 — block-scoped variable used before declaration**

  Check when a variable declared in one file is used in another file
  that appears earlier in compilation order.

  **File:** `Checker.kt` — cross-file ordering checks
  **Target:** 3 tests

---

## Execution order

**Phase 5a** (immediate): Track A — TS2322 analysis + guard relaxation
**Phase 5b** (parallel): Track B — JS emit fixes (independent of checker)
**Phase 5c** (after 5a): Track C — New diagnostics (TS2420, TS2416, TS2792, TS2343)
**Phase 5d** (after 5a): Track D — Unblock TS2339 + TS2345 widening
**Phase 5e** (as-needed): Track E — Cross-file resolution

Tracks A+B can run in parallel (different files). Tracks C+D depend on
Track A's structural comparison improvements.

### Projected test gains

| Track | Tests gained | Cumulative |
|-------|-------------|------------|
| A — TS2322 deepening | +30-40 | ~7,700 |
| B — JS emit fixes | +30-40 | ~7,740 |
| C — New diagnostics | +30-40 | ~7,780 |
| D — Unblock deferred | +25-35 | ~7,810 |
| E — Cross-file | +15-20 | ~7,830 |
| **Total Phase 5** | **~130-175** | **~7,830 (77.7%)** |

Conservative — assumes significant overlap between code targets.

---

## Previously deferred items (from Phase 4b)

These remain deferred until their blockers are resolved:

- **10d** (remove old string system): Still used by conservative fallback
- **13a/b/c** (TS2339 widening): → Track D
- **14a/b** (arithmetic checks): 1300+ regressions from naive approach; needs
  expression-level type guards, not global getTypeOfExpression
- **6b** (error elaboration chains): Nice-to-have, not blocking test gains

## Non-goals (explicitly deferred)

- **Full control flow analysis / type narrowing**: Only typeof narrowing for D2
- **Conditional types**: `T extends U ? X : Y` evaluation
- **Mapped types**: `{ [K in keyof T]: ... }` evaluation
- **Template literal types**: `` `${A}${B}` `` type evaluation
- **Excess property checking**: fresh object literal extra properties
- **Type inference from complex expressions**: spread, destructuring, generators
- **`.types` / `.symbols` baselines**: requires full type display infrastructure

---

## Reference

- **tsgo source**: `github.com/microsoft/typescript-go` — `internal/checker/`
- **TS checker**: `microsoft/TypeScript` — `src/compiler/checker.ts` (53,296 lines)
- **Key tsgo files**: `checker.go` (31K), `relater.go` (5K), `types.go` (1.3K), `flow.go` (2.7K), `inference.go` (1.6K)
- **Parallelism model**: `internal/compiler/checkerpool.go` — N independent checkers, round-robin file assignment, shared immutable AST
