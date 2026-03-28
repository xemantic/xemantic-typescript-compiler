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

- [ ] **2a. `getTypeOfSymbol(symbol: Symbol): Type`**

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

- [ ] **2b. `getDeclaredTypeOfClassOrInterface(symbol: Symbol): InterfaceType`**

  Build InterfaceType from class/interface declarations:
  - Collect type parameters → TypeParameter types
  - Resolve base types (extends/implements) → baseTypes list
  - DON'T resolve members yet (lazy)

  **File:** `Checker.kt`

- [ ] **2c. `resolveStructuredTypeMembers(type: ObjectType)`**

  Lazily resolve an ObjectType's members, properties, and signatures:
  - For InterfaceType: collect from all merged declarations + inherited from base types
  - For TypeReference: instantiate target's members with type argument mapper
  - For anonymous types (object literals, function types): from declaration

  **File:** `Checker.kt`

### 3. Basic expression type inference

- [ ] **3a. `getTypeOfExpression(expr: Expression): Type`**

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

- [ ] **4a. `isSimpleTypeRelatedTo(source: Type, target: Type, relation: Relation): Boolean`**

  Fast flag-based checks (no recursion):
  - `target.flags has Any` → true
  - `source.flags has Never` → true
  - `StringLike → String`, `NumberLike → Number`, etc.
  - `undefined/null` assignable when `!strictNullChecks`

  Replace current `isAssignableTo(sourceType: String, targetType: String)`.

  **File:** `Checker.kt`

- [ ] **4b. `checkTypeRelatedTo(source, target, relation, errorNode): Boolean`**

  Main entry point with error reporting. Maintains:
  - Relation cache lookup/store
  - Error chain building for diagnostics
  - Depth limit (100) for cycle prevention

  **File:** `Checker.kt`

- [ ] **4c. `structuredTypeRelatedTo(source, target, relation): Ternary`**

  Core structural comparison:
  - Union source: each constituent must relate to target (for assignable)
  - Union target: source must relate to some constituent
  - Intersection target: source must relate to each constituent
  - Object types: fall through to property/signature comparison

  **File:** `Checker.kt`

- [ ] **4d. `propertiesRelatedTo(source, target): Ternary`**

  Property-by-property structural comparison:
  - For each required property in target, find matching property in source
  - Compare property types via `isRelatedTo` (recursive)
  - Report `getUnmatchedProperty` for missing properties
  - Handle optional properties (not required in source)

  **File:** `Checker.kt`

- [ ] **4e. `signaturesRelatedTo(source, target, kind): Ternary`**

  Compare call/construct signatures:
  - Parameter types compared contravariantly
  - Return types compared covariantly
  - Handle optional parameters and rest parameters

  **File:** `Checker.kt`

### 5. Diagnostic emission — TS2339

- [ ] **5a. `checkPropertyAccessExpression(node: PropertyAccessExpression)`**

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

- [ ] **5b. Spelling suggestions for non-existent properties**

  When TS2339 fires, suggest similar property names using the existing
  Damerau-Levenshtein infrastructure from TS2552.

  **File:** `Checker.kt`

### 6. Diagnostic emission — TS2322

- [ ] **6a. Wire `checkTypeRelatedTo` into existing TS2322 checks**

  Replace `isAssignableTo(sourceString, targetString)` calls with
  `checkTypeRelatedTo(sourceType, targetType, assignableRelation, errorNode)`.

  Update `checkVarDeclAssignability`, `checkReturnAssignability`,
  `checkAssignmentExpression` to use the new Type-based engine.

  **File:** `Checker.kt`
  **Target:** ~293 tests (TS2322), but many need deeper type inference

- [ ] **6b. Error elaboration chains**

  When structural comparison fails, build message chains:
  - "Type '{ a: string }' is not assignable to type '{ a: number }'"
  - "  Types of property 'a' are incompatible"
  - "    Type 'string' is not assignable to type 'number'"

  **File:** `Checker.kt`

### 7. Diagnostic emission — TS2345

- [ ] **7a. `checkCallExpression` argument type checking**

  For each argument in a call expression:
  1. Resolve the callee to a signature (or set of overload signatures)
  2. Get parameter type at position
  3. Check argument type against parameter type via `checkTypeRelatedTo`
  4. If fails: emit TS2345 with argument/parameter type names

  **File:** `Checker.kt`
  **Target:** ~79 tests (TS2345)

- [ ] **7b. Basic overload resolution**

  Try each overload signature in order. Pick the first that succeeds.
  If none succeed, report error against the last signature (TypeScript convention).

  **File:** `Checker.kt`

### 8. Generic type support

- [ ] **8a. TypeMapper and generic instantiation**

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

- [ ] **8b. Basic type inference for generic calls**

  For `function identity<T>(x: T): T`, calling `identity("hello")`:
  1. Create InferenceContext with one entry per type parameter
  2. Infer from each argument type against parameter type
  3. Fix inferred types
  4. Instantiate return type with inferred mapper

  Start with **simple single-parameter inference** — covers most common patterns.

  **File:** `Checker.kt`

### 9. Parallel checking preparation

- [ ] **9a. Extract mutable checker state into CheckerState class**

  Group all mutable state (caches, counters, diagnostics) into a clearly
  separated `CheckerState` that each parallel checker instance owns.
  The shared data (AST, binder results, compiler options) remains separate.

  **File:** `Checker.kt` (refactor)

- [ ] **9b. Immutable binder output**

  Ensure all binder output is effectively immutable after binding:
  - Symbol tables are read-only during checking
  - No Symbol mutation during checking (currently `target` is set by checker — move to LinkStore)

  **File:** `Binder.kt`, `Types.kt`, `Checker.kt`

- [ ] **9c. CheckerPool with coroutine-based parallel checking**

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

| Milestone | New tests passing | Cumulative |
|-----------|-------------------|------------|
| After 0-2 (type foundation) | ~0 | 7,632 |
| After 3 (expression inference) | ~20 | 7,652 |
| After 4 (structural engine) | ~50 | 7,702 |
| After 5 (TS2339) | ~80 | 7,782 |
| After 6 (TS2322) | ~200 | 7,982 |
| After 7 (TS2345) | ~60 | 8,042 |
| After 8 (generics) | ~100 | 8,142 |
| **Total Phase 4** | **~510** | **~8,142 (80.8%)** |

Conservative estimates — actual gains depend on how many tests need ONLY these codes
vs needing additional diagnostics we haven't implemented.

---

## Reference

- **tsgo source**: `github.com/microsoft/typescript-go` — `internal/checker/`
- **TS checker**: `microsoft/TypeScript` — `src/compiler/checker.ts` (53,296 lines)
- **Key tsgo files**: `checker.go` (31K), `relater.go` (5K), `types.go` (1.3K), `flow.go` (2.7K), `inference.go` (1.6K)
- **Parallelism model**: `internal/compiler/checkerpool.go` — N independent checkers, round-robin file assignment, shared immutable AST
