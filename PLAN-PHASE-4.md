# Phase 4 — Structural Type Checker

## Goal

Implement structural type checking to unlock ~1,500 tests blocked on TS2322/TS2339/TS2345.
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

## Implementation queue

### 0. Type foundation

- [x] **0a. Type sealed class hierarchy**

  Create `Type.kt` (new file) with the core type hierarchy:
  - `Type` sealed class with `flags: TypeFlags`, `id: Int`
  - `TypeFlags` value class (bit field, matching TS/tsgo values)
  - `IntrinsicType` — primitives: any, unknown, string, number, boolean, void, undefined, null, never, object, symbol, bigint
  - `LiteralType` — string/number/boolean/bigint literal types with `value` and `freshType`/`regularType`
  - `ObjectType` — lazily-resolved members, properties, signatures, index infos
  - `UnionType`, `IntersectionType` — constituent types list
  - `TypeParameter` — constraint, default
  - Pre-allocated singleton intrinsic types: `anyType`, `stringType`, `numberType`, `neverType`, etc.

  **File:** `Type.kt` (new), ~200 lines
  **No test impact** — purely additive

- [x] **0b. Signature data class**

  ```kotlin
  class Signature(
      val declaration: Node?,
      val typeParameters: List<TypeParameter>?,
      val parameters: List<Symbol>,
      val resolvedReturnType: Type?,
      val minArgumentCount: Int,
  )
  ```

  **File:** `Type.kt`

- [x] **0c. TypeFlags constants**

  Match TypeScript/tsgo values exactly for cache compatibility:
  ```kotlin
  @JvmInline value class TypeFlags(val value: Int) {
      companion object {
          val Any = TypeFlags(1)
          val Unknown = TypeFlags(1 shl 1)
          val String = TypeFlags(1 shl 2)
          val Number = TypeFlags(1 shl 3)
          // ... all 30+ flags
          // Composite: StructuredType = Object or Union or Intersection
      }
  }
  ```

  **File:** `Type.kt`

### 1. Type resolution from TypeNodes

- [x] **1a. `getTypeFromTypeNode(node: TypeNode): Type`**

  Convert AST TypeNode to Type object. Start with the common cases:
  - `KeywordTypeNode` → intrinsic type singleton
  - `TypeReference` → look up symbol, return declared type (or ObjectType)
  - `UnionType` → `getUnionType(types.map { getTypeFromTypeNode(it) })`
  - `IntersectionType` → `getIntersectionType(...)`
  - `ArrayType` → `TypeReference(globalArrayType, [elementType])`
  - `LiteralType` → `LiteralType(value)`
  - `FunctionType` → anonymous ObjectType with call signature
  - `TypeQuery` (typeof) → `getTypeOfSymbol(resolvedSymbol)`
  - `ParenthesizedType` → recurse
  - `ThisType`, `TypePredicate` → handle or return anyType

  Cache results in `nodeTypes: HashMap<Long, Type>`.

  **File:** `Checker.kt` (new section)
  **No test impact yet** — foundation for later checks

- [x] **1b. `getUnionType(types: List<Type>): Type`**

  Normalize and deduplicate union constituents:
  - Flatten nested unions
  - Remove duplicates (by type identity)
  - Handle `never` removal (never | X = X)
  - Handle `any` absorption (any | X = any)
  - Single constituent → return it directly
  - Cache by constituent type IDs

  **File:** `Checker.kt`

- [x] **1c. `getIntersectionType(types: List<Type>): Type`**

  Similar to union but with intersection semantics:
  - `unknown & X = X`, `never & X = never`, `any & X = any`
  - Flatten nested intersections

  **File:** `Checker.kt`

### 2. Symbol type resolution

- [x] **2a. `getTypeOfSymbol(symbol: Symbol): Type`**

  Compute the type of a symbol from its declarations:
  - Variable/parameter with type annotation → `getTypeFromTypeNode(annotation)`
  - Variable with initializer (no annotation) → `getTypeOfExpression(initializer)` (basic inference)
  - Function → `getFunctionType(decl)` (ObjectType with call signature)
  - Class → `getDeclaredTypeOfClass(symbol)`
  - Interface → `getDeclaredTypeOfInterface(symbol)`
  - Enum → numeric enum type or string enum type
  - TypeAlias → `getTypeFromTypeNode(aliasedType)`
  - Import alias → `getTypeOfSymbol(resolveAlias(symbol))`

  Cache in `symbolTypes: HashMap<Int, Type>`.

  **File:** `Checker.kt`

- [x] **2b. `getDeclaredTypeOfClassOrInterface(symbol: Symbol): InterfaceType`**

  Build InterfaceType from class/interface declarations:
  - Collect type parameters → TypeParameter types
  - Resolve base types (extends/implements) → baseTypes list
  - DON'T resolve members yet (lazy)

  **File:** `Checker.kt`

- [x] **2c. `resolveStructuredTypeMembers(type: ObjectType)`**

  Lazily resolve an ObjectType's members, properties, and signatures:
  - For InterfaceType: collect from all merged declarations + inherited from base types
  - For TypeReference: instantiate target's members with type argument mapper
  - For anonymous types (object literals, function types): from declaration

  **File:** `Checker.kt`

### 3. Basic expression type inference

- [x] **3a. `getTypeOfExpression(expr: Expression): Type`**

  Replace `inferSimpleExprType` (string-based) with proper Type-based inference:
  - Literals → literal types (widened to base type for mutable bindings)
  - Identifier → `getTypeOfSymbol(resolvedSymbol)`
  - PropertyAccess → `getPropertyType(objectType, name)`
  - Call expression → return type of resolved signature
  - Array literal → `ArrayType` or tuple
  - Object literal → anonymous ObjectType
  - Binary expressions → result type based on operator
  - As/type assertion → asserted type
  - Template expression → string
  - Arrow/function expression → function type

  Start with **literals, identifiers, and property access** — these unlock TS2322/TS2339.

  **File:** `Checker.kt`

### 4. Structural typing engine

- [x] **4a. `isSimpleTypeRelatedTo(source: Type, target: Type, relation: Relation): Boolean`**

  Fast flag-based checks (no recursion):
  - `target.flags has Any` → true
  - `source.flags has Never` → true
  - `StringLike → String`, `NumberLike → Number`, etc.
  - `undefined/null` assignable when `!strictNullChecks`

  Replace current `isAssignableTo(sourceType: String, targetType: String)`.

  **File:** `Checker.kt`

- [x] **4b. `checkTypeRelatedTo(source, target, relation, errorNode): Boolean`**

  Main entry point with error reporting. Maintains:
  - Relation cache lookup/store
  - Error chain building for diagnostics
  - Depth limit (100) for cycle prevention

  **File:** `Checker.kt`

- [x] **4c. `structuredTypeRelatedTo(source, target, relation): Ternary`**

  Core structural comparison:
  - Union source: each constituent must relate to target (for assignable)
  - Union target: source must relate to some constituent
  - Intersection target: source must relate to each constituent
  - Object types: fall through to property/signature comparison

  **File:** `Checker.kt`

- [x] **4d. `propertiesRelatedTo(source, target): Ternary`**

  Property-by-property structural comparison:
  - For each required property in target, find matching property in source
  - Compare property types via `isRelatedTo` (recursive)
  - Report `getUnmatchedProperty` for missing properties
  - Handle optional properties (not required in source)

  **File:** `Checker.kt`

- [x] **4e. `signaturesRelatedTo(source, target, kind): Ternary`**

  Compare call/construct signatures:
  - Parameter types compared contravariantly
  - Return types compared covariantly
  - Handle optional parameters and rest parameters

  **File:** `Checker.kt`

### 5. Diagnostic emission — TS2339

- [x] **5a. `checkPropertyAccessExpression(node: PropertyAccessExpression)`**

  The easiest of the three target diagnostics:
  1. Get type of left-hand expression: `getTypeOfExpression(node.expression)`
  2. Get apparent type: `getApparentType(type)` (unwrap type parameters to constraints)
  3. Look up property: `getPropertyOfType(apparentType, node.name.text)`
  4. If not found and no index signature: emit TS2339

  Also implement `getApparentType` which resolves:
  - TypeParameter → constraint type
  - String literal → String apparent type (with string methods)
  - Number literal → Number apparent type

  **File:** `Checker.kt`
  **Target:** ~100 tests (TS2339)

- [x] **5b. Spelling suggestions for non-existent properties**

  When TS2339 fires, suggest similar property names using the existing
  Damerau-Levenshtein infrastructure from TS2552.

  **File:** `Checker.kt`

### 6. Diagnostic emission — TS2322

- [x] **6a. Wire `checkTypeRelatedTo` into existing TS2322 checks** (infrastructure ready; full replacement deferred until expression inference improves)

  Replace `isAssignableTo(sourceString, targetString)` calls with
  `checkTypeRelatedTo(sourceType, targetType, assignableRelation, errorNode)`.

  Update `checkVarDeclAssignability`, `checkReturnAssignability`,
  `checkAssignmentExpression` to use the new Type-based engine.

  **File:** `Checker.kt`
  **Target:** ~293 tests (TS2322), but many need deeper type inference

- [ ] **6b. Error elaboration chains** (blocked on full 6a wiring)

  When structural comparison fails, build message chains:
  - "Type '{ a: string }' is not assignable to type '{ a: number }'"
  - "  Types of property 'a' are incompatible"
  - "    Type 'string' is not assignable to type 'number'"

  **File:** `Checker.kt`

### 7. Diagnostic emission — TS2345

- [x] **7a. `checkCallExpression` argument type checking** (conservative: primitive parameter types only to avoid FPs from incomplete structural comparison)

  For each argument in a call expression:
  1. Resolve the callee to a signature (or set of overload signatures)
  2. Get parameter type at position
  3. Check argument type against parameter type via `checkTypeRelatedTo`
  4. If fails: emit TS2345 with argument/parameter type names

  **File:** `Checker.kt`
  **Target:** ~79 tests (TS2345)

- [x] **7b. Basic overload resolution**

  Try each overload signature in order. Pick the first that succeeds.
  If none succeed, report error against the last signature (TypeScript convention).

  **File:** `Checker.kt`

### 8. Generic type support

- [x] **8a. TypeMapper and generic instantiation**

  ```kotlin
  fun interface TypeMapper {
      fun map(type: TypeParameter): Type?
  }

  fun instantiateType(type: Type, mapper: TypeMapper): Type
  ```

  Create type instances by substituting type parameters:
  - `Array<number>` → TypeReference(ArrayType, [numberType])
  - `Map<string, Foo>` → TypeReference(MapType, [stringType, fooType])

  Cache instantiations on the GenericType: `instantiations: HashMap<String, TypeReference>`

  **File:** `Checker.kt`

- [x] **8b. Basic type inference for generic calls** (type parameter resolution in signatures; full inference deferred)

  For `function identity<T>(x: T): T`, calling `identity("hello")`:
  1. Create InferenceContext with one entry per type parameter
  2. Infer from each argument type against parameter type
  3. Fix inferred types
  4. Instantiate return type with inferred mapper

  Start with **simple single-parameter inference** — covers most common patterns.

  **File:** `Checker.kt`

### 9. Parallel checking preparation

- [x] **9a. Extract mutable checker state into CheckerState class**

  Group all mutable state (caches, counters, diagnostics) into a clearly
  separated `CheckerState` that each parallel checker instance owns.
  The shared data (AST, binder results, compiler options) remains separate.

  **File:** `Checker.kt` (refactor)

- [x] **9b. Immutable binder output**

  Ensure all binder output is effectively immutable after binding:
  - Symbol tables are read-only during checking
  - No Symbol mutation during checking (currently `target` is set by checker — move to LinkStore)

  **File:** `Binder.kt`, `Types.kt`, `Checker.kt`

- [x] **9c. CheckerPool with coroutine-based parallel checking**

  ```kotlin
  class CheckerPool(
      private val options: CompilerOptions,
      private val binderResults: List<BinderResult>,
      private val checkerCount: Int = 4,
  ) {
      private val checkers = List(checkerCount) { Checker(options, binderResults) }

      suspend fun checkAllFiles(): List<Diagnostic> = coroutineScope {
          checkers.mapIndexed { i, checker ->
              async {
                  val myFiles = binderResults.filterIndexed { j, _ -> j % checkerCount == i }
                  checker.checkFiles(myFiles)
              }
          }.awaitAll().flatten()
      }
  }
  ```

  **File:** `CheckerPool.kt` (new)
  **Dependency:** kotlinx-coroutines in commonMain

---

## Phase 4b — Widen Type Checking Coverage

Infrastructure (items 0–9) is complete. The structural comparison engine, type resolution
caches, generic instantiation, and parallel checking pool are in place. The remaining work
is to **remove conservative guards** and **wire the Type-based engine into more checks**.

Currently the checker has TWO type systems running in parallel:
- **OLD** (string-based): `inferSimpleExprType()` → string, `isAssignableTo()` → compares strings.
  Used by TS2322. Very limited — only handles keyword types and "@"-prefixed named types.
- **NEW** (Type-based): `getTypeOfExpression()` → Type, `checkTypeRelatedTo()` → structural
  comparison. Used by TS2345/TS2339. Has conservative guards limiting scope.

**Goal**: Replace the OLD system, relax conservative guards, and add new diagnostic codes.

### 10. Wire TS2322 to the Type-based engine

The single highest-impact change. Replace `inferSimpleExprType`/`isAssignableTo` (string-based)
with `getTypeOfExpression`/`checkTypeRelatedTo` (Type-based) for all TS2322 emission.

- [x] **10a. Replace variable declaration assignability**

  In `checkTypeAssignability`, replace the string-based inference for variable declarations:
  ```
  val init: VariableDeclaration with type annotation + initializer
  OLD: inferSimpleExprType(init.initializer) → string, isAssignableTo(string, string)
  NEW: getTypeOfExpression(init.initializer) → Type, checkTypeRelatedTo(source, target, assignableRelation)
  ```
  Also replace `formatTypeForDisplay` with `typeToString(type)` for error messages.

  **File:** `Checker.kt` — `checkTypeAssignability`, `checkVarDeclAssignability`
  **Target:** ~200 tests (variable assignment is the most common TS2322 pattern)

- [x] **10b. Replace return statement assignability** (wired with conservative guards; gains blocked on structural comparison for non-intrinsic types)

  Wire return type checking to use the Type engine:
  ```
  val ret: ReturnStatement inside function with explicit return type
  NEW: getTypeOfExpression(ret.expression) → Type, checkTypeRelatedTo(source, funcReturnType)
  ```

  **File:** `Checker.kt` — `checkReturnAssignability`
  **Target:** ~50 tests

- [x] **10c. Replace assignment expression assignability** (wired with conservative guards; gains blocked on structural comparison for non-intrinsic types)

  Wire `x = value` assignment checking:
  ```
  val assign: BinaryExpression(=, +=, etc.)
  NEW: getTypeOfExpression(right) vs getTypeOfExpression(left)
  ```

  **File:** `Checker.kt` — `checkAssignmentExpression`
  **Target:** ~30 tests

- [ ] **10d. Remove old string-based inference functions**

  After 10a-10c, the old `inferSimpleExprType`, `isAssignableTo`,
  and `formatTypeForDisplay` functions should be dead code. Remove them.

  **File:** `Checker.kt`

### 11. Improve expression type inference

`getTypeOfExpression` currently returns `anyType` for many expression patterns.
Each TODO resolved here directly improves TS2322/TS2345/TS2339 accuracy.

- [x] **11a. Object literal → anonymous object type**

  `{ a: 1, b: "hello" }` should produce `Type.Object` with properties
  `a: number, b: string`, not `anyType`. Required for object assignability.

  **File:** `Checker.kt` — `getTypeOfExpression` case `ObjectLiteralExpression`
  **Target:** ~80 tests (many TS2322 tests involve object literal assignments)

- [x] **11b. Array literal → Array type** (element type inference done; Array<T> wrapping deferred until globalArrayType)

  `[1, 2, 3]` should produce `Type.Reference(globalArrayType, [numberType])`.
  Requires checking element types and computing union if heterogeneous.

  **File:** `Checker.kt` — `getTypeOfExpression` case `ArrayLiteralExpression`
  **Target:** ~30 tests

- [x] **11c. Arrow/function expression → function type**

  `(x: number) => x + 1` should produce `Type.Object` with a call signature.
  Required for function-typed parameter checking.

  **File:** `Checker.kt` — `getTypeOfExpression` cases `ArrowFunction`, `FunctionExpression`
  **Target:** ~20 tests

- [x] **11d. Identifier type lookup from local scope** (globals already contain merged file locals; also added call/new return type checking via getReturnTypeOfCallExpression)

  `getTypeOfIdentifier` currently only looks up globals. For TS2322 to work on
  local variables, it needs to resolve file-level locals and (ideally) function-scoped
  bindings from the binder's symbol tables.

  **File:** `Checker.kt` — `getTypeOfIdentifier`
  **Target:** ~100 tests (most expressions involve local variables)

### 12. Widen TS2345 argument type checking

- [x] **12a. Relax `isSimpleCheckableType` guard** (added union type support; interface check deferred — recursive interface FPs)

  The structural comparison engine (item 4) now handles object/interface types.
  Remove the primitive-only filter in `checkArgumentsAgainstSignature` and use
  `checkTypeRelatedTo` for ALL parameter types.

  Guard to relax: `if (!isSimpleCheckableType(paramType)) continue` at line ~22456.

  **File:** `Checker.kt` — `checkArgumentsAgainstSignature`, remove `isSimpleCheckableType`
  **Regression risk:** Monitor for FPs from incomplete type resolution. May need to
  keep the guard for TypeParameter types until generic inference improves.
  **Target:** ~100 tests

- [ ] **12b. Handle union type call signatures**

  Currently `getCallSignaturesOfType` returns `emptyList()` for `Type.Union`.
  Implement: collect call signatures from all union constituents.

  **File:** `Checker.kt` — `getCallSignaturesOfType`
  **Target:** ~20 tests

- [ ] **12c. TS2769 — no overload matches this call**

  When ALL overload signatures fail, emit TS2769 with each overload's error
  instead of TS2345 against the last signature.

  **File:** `Checker.kt` — overload resolution in `checkCallTypesInExpr`
  **Target:** ~30 tests

### 13. Widen TS2339 property access checking

- [ ] **13a. Check property access on typed identifiers**

  Remove the `this`-only guard. For any `expr.prop` where `getTypeOfExpression(expr)`
  resolves to a known object type, check if `prop` exists on that type.

  Guard to relax: `if (expr.expression !is Identifier || text != "this") return`

  **File:** `Checker.kt` — `checkSinglePropertyAccess`
  **Regression risk:** HIGH — need to ensure `getTypeOfExpression` returns `anyType`
  (not a concrete type) for unresolvable expressions. Guard with `if (type === anyType) return`.
  **Target:** ~60 tests

- [ ] **13b. Remove base type skip**

  The Phase 4 `resolveInterfaceMembers` now inherits from base types.
  Remove: `if (objectType.baseTypes != null && baseTypes.isNotEmpty()) return`

  **File:** `Checker.kt` — `checkSinglePropertyAccess`
  **Target:** ~30 tests

- [ ] **13c. Check property access on union types**

  For `(A | B).prop`, property must exist on ALL constituents.
  Use `getPropertyOfType` on each union member.

  **File:** `Checker.kt` — `checkSinglePropertyAccess`
  **Target:** ~15 tests

### 14. Operator type checking (new diagnostics)

- [ ] **14a. TS2362/TS2363 — arithmetic operand types**

  For `+`, `-`, `*`, `/`, `%`, `**`, `<<`, `>>`, `>>>`, `&`, `|`, `^`:
  left/right must be `number`, `any`, `bigint`, or enum type.

  Special case: binary `+` allows `string` on either side.

  **File:** `Checker.kt` — new `checkArithmeticOperandTypes`
  **Target:** ~100 tests

- [ ] **14b. TS2365 — operator cannot be applied to types**

  For `+` specifically: if left is `string` and right is not, or vice versa,
  emit "Operator '+' cannot be applied to types 'X' and 'Y'".

  **File:** `Checker.kt`
  **Target:** ~50 tests

### 15. Class/interface structural diagnostics

- [ ] **15a. TS2430 — duplicate property declarations in class**

  Within a single class body, detect duplicate property/method names.
  Getters and setters with the same name are allowed.

  **File:** `Checker.kt` — new `checkDuplicateClassMembers`
  **Target:** ~50 tests

- [ ] **15b. TS2416 — property type incompatible with base type**

  When a class overrides a property from its base, check that the overriding
  type is assignable to the base type.

  **File:** `Checker.kt`
  **Dependency:** 13b (base type resolution working)
  **Target:** ~30 tests

- [ ] **15c. TS2411 — class implements interface property mismatch**

  When a class `implements` an interface, check that each interface property
  has a compatible implementation in the class.

  **File:** `Checker.kt`
  **Dependency:** structural comparison engine (item 4)
  **Target:** ~30 tests

### 16. False positive reduction

- [ ] **16a. TS2554 FP for overloaded functions and rest params**

  Current `checkArgumentCounts` doesn't handle rest parameters (`...args`)
  or multiple overload signatures properly. Fix: check if ANY overload
  accepts the given argument count before emitting TS2554.

  **File:** `Checker.kt` — `checkArgumentCounts`
  **Target:** ~13 tests (direct), prevents cascading FPs

- [ ] **16b. TS2391 FP for abstract methods and interface declarations**

  `checkMissingImplementations` currently flags abstract methods and
  interface method declarations as missing bodies. Skip when declaration
  has `abstract` modifier or is in an interface body.

  **File:** `Checker.kt` — `checkMissingImplementations`
  **Target:** ~14 tests

- [ ] **16c. TS2304/TS2693 FP for cross-file references**

  Improve name resolution to check re-exported names and `declare module`
  ambient references before emitting TS2304.

  **File:** `Checker.kt` — `checkUnresolvedNames`
  **Target:** ~40 tests

### 17. JS emit fixes

- [ ] **17a. Multi-file import ordering**

  Fix topological sort for multi-file compilation where import chains
  create non-obvious ordering requirements.

  **File:** `TypeScriptCompiler.kt`
  **Target:** ~20 tests

- [ ] **17b. CJS transform edge cases**

  Fix remaining CommonJS transform issues: self-referencing exports,
  comment hoisting before `Object.defineProperty`, indirect call patterns.

  **File:** `Transformer.kt`
  **Target:** ~20 tests

- [ ] **17c. Type-only import elimination**

  Improve detection of type-only imports that should be elided in JS output,
  including `export type { X }` re-exports and namespace-only imports.

  **File:** `Checker.kt`, `Transformer.kt`
  **Target:** ~15 tests

---

## Non-goals (explicitly deferred)

- **Control flow analysis / type narrowing**: typeof guards, instanceof, truthiness narrowing
- **Conditional types**: `T extends U ? X : Y` evaluation
- **Mapped types**: `{ [K in keyof T]: ... }` evaluation
- **Template literal types**: `` `${A}${B}` `` type evaluation
- **Excess property checking**: fresh object literal extra properties
- **Type inference from complex expressions**: spread, destructuring, generators
- **`.types` / `.symbols` baselines**: requires full type display infrastructure

---

## Test impact estimates

### Phase 4a — Infrastructure (completed)

| Milestone | New tests passing | Cumulative |
|-----------|-------------------|------------|
| After 0-2 (type foundation) | ~0 | 7,632 |
| After 3 (expression inference) | ~20 | 7,652 |
| After 4 (structural engine) | ~50 | 7,702 |
| After 5 (TS2339) | ~80 | 7,782 |
| After 6 (TS2322) | ~200 | 7,982 |
| After 7 (TS2345) | ~60 | 8,042 |
| After 8 (generics) | ~100 | 8,142 |
| After 9 (parallel prep) | ~0 | 8,142 |

### Phase 4b — Widen coverage (new queue)

| Milestone | New tests passing | Cumulative |
|-----------|-------------------|------------|
| After 10 (TS2322 → Type engine) | ~280 | ~8,420 |
| After 11 (expression inference) | ~230 | ~8,650 |
| After 12 (TS2345 widened) | ~150 | ~8,800 |
| After 13 (TS2339 widened) | ~105 | ~8,905 |
| After 14 (operator checking) | ~150 | ~9,055 |
| After 15 (class diagnostics) | ~110 | ~9,165 |
| After 16 (FP reduction) | ~67 | ~9,232 |
| After 17 (JS emit) | ~55 | ~9,287 |
| **Total Phase 4b** | **~1,147** | **~9,287 (92.2%)** |

Conservative — actual gains depend on test overlap between codes.

---

## Reference

- **tsgo source**: `github.com/microsoft/typescript-go` — `internal/checker/`
- **TS checker**: `microsoft/TypeScript` — `src/compiler/checker.ts` (53,296 lines)
- **Key tsgo files**: `checker.go` (31K), `relater.go` (5K), `types.go` (1.3K), `flow.go` (2.7K), `inference.go` (1.6K)
- **Parallelism model**: `internal/compiler/checkerpool.go` — N independent checkers, round-robin file assignment, shared immutable AST
