# Phase 4 — Structural Type Checker

**Status (2026-03-31):** 7,695 / 10,077 tests passing (76.4%).

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

### Failure landscape (2026-03-30 reassessment)

```
Total failing: 2,398
  Error baseline: 2,140  (89% of failing tests)
  JS emit:          260  (11%)

Error baseline breakdown (sampled):
  ~71% are "none produced" (need MORE diagnostics — ~1,520 tests)
  ~29% have diffs (emit some but not all — ~620 tests)
```

### Impact-ranked code targets (updated 2026-03-30)

**Pure tests** = tests that need ONLY this code (+ already-emitted codes):

| Code | Description | Pure | Complexity | Status |
|------|-------------|------|------------|--------|
| TS2322 | Type not assignable | **188** | Relax `canUseTypeEngine` guard | **TOP PRIORITY** |
| TS2345 | Arg type mismatch | **66** | Relax `isSimpleCheckableType` | **HIGH PRIORITY** |
| TS2339 | Property doesn't exist | 15 | Needs D1+D2 (module augment + narrowing) | Blocked |
| TS2420 | Class incorrectly implements | — | DONE (+5) | Complete |
| TS2416 | Property incompatible w/ base | — | DONE (+2) | Complete |
| TS2344 | Type constraint violation | — | DONE (+5) | Complete |
| TS2792 | Cannot find module (classic) | 6 | Needs module resolution | Deferred |

**The bottleneck is the `canUseTypeEngine` guard.** 188 pure TS2322 tests produce
zero diagnostics because `canUseTypeEngine` returns false for most type pairs.
The guard currently only allows: intrinsic↔intrinsic, nullish→anything,
object-literal→intrinsic, function→function. All other comparisons (interface→primitive,
array→primitive, named→named) fall through to the old string system which does nothing.

**Top missing diagnostic codes we don't emit at all:**
- TS2769 (910 occurrences): overload resolution — complex
- TS7006 (260): implicit any parameter — simple flag check
- TS2353 (236): object literal type — needs excess property checking
- TS2741 (182): property missing in type — structural comparison
- TS2540 (178): readonly property — simple flag check
- TS1487 (172): `export =` compatibility — module system
- TS2554 (146): wrong number of arguments — call expression checking
- TS2451 (142): block-scoped variable redeclare — scope checking

**Already implemented (discovered during investigation):**
- TS7006 (implicit any parameter) — fully implemented, 260 baseline occurrences
- TS2554 (wrong argument count) — implemented for simple calls, 146 occurrences
- TS2451 (block-scoped variable redeclare) — fully implemented
- TS2693 (type used as value) — fully implemented

**Completed session 2026-03-30:**
- TS2540 (readonly property assignment) — +2 tests. 7,681 passing.
- TS2454 (heritage clause) — +2 tests. 7,683.
- TS2345 (param defaults) — +2 tests. 7,685.
- `canUseTypeEngine` guard relaxed for Object→Intrinsic (safe, no FPs)
- `currentLocalTypes` infrastructure for local variable type resolution

**Completed session 2026-03-31 (+8 tests, 7,685 → 7,693):**
- D1: `mergeModuleAugmentations()` in checker init — merges `declare module "X"` exports
  into corresponding global symbols. Infrastructure only (0 direct gains).
- D2+D3: TS2339 guard relaxation with narrowing-safe heuristic.
  +1 test (deleteExpressionMustBeOptional__strict_false__).
- typeof prefix for class names in TS2339 static access.
  +2 tests (errorSupression1, invalidStaticField).
- TS2403 subsequent var declaration type mismatch.
  +5 tests (duplicateLocalVariable3, duplicateVariablesWithAny,
  capturedLetConstInLoop14 x2, augmentedTypesVar).
- E1 investigated → BLOCKED (resolveModuleSpecifier too simplified).
- E2 investigated → needs analysis (merge validation, not simple duplicates).

**Recommended priority for next session:**

1. **Relax `canUseTypeEngine` guard further** — The single highest-ROI change.
   Object→Intrinsic relaxation is safe but shows no gains because local variable
   identifiers can't yet resolve through `getTypeOfExpression`. Need deeper
   scope chain integration between the Type engine and `checkTypeAssignability`.

2. **TS2540 expansion** — Extend to interface/class readonly properties resolved
   through the type system (needs `declare let` variable types to resolve).

3. **Improve `typeToString` for object types** — Many diff tests need expanded member
   display instead of `{ ... }` placeholder. Fixes TS2322/TS2345 text accuracy.

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

- [x] **A2. Class member init + this.prop assignment + return identifier lookup**

  Added PropertyDeclaration initializer checking in class member traversal.
  Added `this.prop = value` handling in `checkAssignmentExpression` with
  pre-populated class property types in constructor scope.
  Added varTypes lookup for identifiers in return statement context.
  **+4 tests** (classMemberInitializerScoping, memberVariableDeclarations1,
  numberToString, chainedAssignment2). 7,665 passing.

- [x] **A3. null→function/union guard + formatTypeForDisplay**

  Extended canUseTypeEngine for null/undefined → Object types with
  call/construct signatures, properties, and union types.
  Added FunctionType, ConstructorType, IntersectionType, ParenthesizedType,
  TupleType to formatTypeForDisplay. Enabled checkReturnAssignability
  when only returnTypeNode (not string) is available.
  **+1 test** (typeParameterEquality). 7,666 passing.

- [x] **A4. Function signature comparison for TS2322**

  Enabled function→function structural comparison in the type engine:
  - `isSimpleTypeRelatedTo`: added `any` source assignable to everything, `void→undefined`
  - `signatureRelatedTo`: void target return accepts any source return type
  - `canUseTypeEngine`: enabled function→function (both sides have call signatures)
  - `typeToString`: reordered Interface/Reference before Object in `when` block;
    added multi-signature display `{ (sig): ret; (sig): ret; }` and colon notation
  - Added `getFunctionMismatchElaboration` for return type and parameter mismatch chains
  - Integrated elaboration into all TS2322 emission sites

  **+1 test** (errorOnContextuallyTypedReturnType). 7,667 passing.
  Infrastructure ready for more gains when local scope resolution, generics,
  and constructor signature comparison are added.

  **File:** `Checker.kt`

### Track B — JS Emit Fixes (target: +30-40 tests)

Independent of type checker work. 257 tests failing, 74 within 4 diff lines.

- [x] **B1. CJS export ordering** — SKIPPED

  Analysis shows only ~2 tests have pure CJS ordering diffs. Most JS emit
  failures are parser error recovery, module path resolution, or other
  issues. ROI too low to pursue.

  **Target:** ~2 tests (revised from ~10 estimate)

- [ ] **B2. Type-only import elimination** — DEFERRED (complex, multi-file)

  **Target:** ~10 tests

- [ ] **B3. Multi-file emit ordering** — DEFERRED (complex)

  **Target:** ~10 tests

- [ ] **B4. Source map improvements** — DEFERRED (new infrastructure needed)

  **Target:** ~10 tests

### Track C — New Diagnostic Categories (target: +30-40 tests)

Well-defined checks using existing infrastructure.

- [x] **C1. TS2420 — class incorrectly implements interface**

  For each `implements` clause, check that the class has all required
  interface members with compatible types. Collects class's own declared
  members from AST (not inherited from interfaces via resolveStructuredTypeMembers).
  Only checks actual interfaces (SymbolFlags.Interface), not classes.
  Includes index signature checks and TS2728 related info.

  **+5 tests** (declareClassInterfaceImplementation, interfaceImplementation2/3/4,
  optionalPropertiesInClasses). 7,672 passing.

  **File:** `Checker.kt` — `checkClassImplementsInterface`

- [x] **C2. TS2416 — property type incompatible with base type**

  For each class with extends/implements clause, check that overriding
  members have types compatible with the base type's members. Compares
  property types and method signatures using structural type comparison.
  Skips overloaded methods (need full overload resolution), static members,
  and members with multiple base declarations. Includes method parameter
  type inference from initializers, void return type for body-only methods,
  and signature parameter count elaboration. Also improved `signatureRelatedTo`
  with `minArgumentCount` check and `typeToString` for generic Interface types.

  **+2 tests** (instanceSubtypeCheck2, requiredInitializedParameter2). 7,679 passing.

  **File:** `Checker.kt` — `checkPropertyOverride`, `buildMethodType`, `getTypeOfMemberDecl`

- [ ] **C3. TS2792 — cannot find module (classic resolution)** — DEFERRED

  TS2792 is actually "Cannot find module '{0}'. Did you mean to set the
  'moduleResolution' option to 'nodenext'?" — fires when classic module
  resolution can't find an import target. Requires module resolution
  infrastructure (same as E1/TS2307). Original plan description was incorrect.

  **File:** `Checker.kt`
  **Dependency:** Module resolution (Track E)
  **Target:** 6 pure tests

- [x] **C4. TS2344 — type does not satisfy constraint**

  Check type arguments against type parameter constraints in TypeReference
  nodes. Scans declarations for TypeReference nodes with type arguments,
  resolves constraints, and compares using structural comparison. Added
  literal value comparison (StringLiteral/NumberLiteral/BigIntLiteral) in
  `isSimpleTypeRelatedTo` for same-value different-instance literals.
  Added missing property elaboration and TS2728 related info.

  **+5 tests** (constraints0, generics1/2/5, genericTypeConstraints). 7,677 passing.

  **File:** `Checker.kt` — `checkTypeArgumentConstraints`

### Track D — Unblock Deferred Items (target: +25-35 tests)

Fix the blockers that prevent widening TS2339 and TS2345.

- [x] **D1. Basic module augmentation resolution**

  Added `mergeModuleAugmentations()` step in checker init after file-level
  symbol merging. For each `declare module "X" { ... }` across all files,
  resolves X to the target file and merges augmented exports (namespaces,
  interfaces) into the corresponding global symbols. Also added namespace
  export checking in `checkSinglePropertyAccess` for merged class+namespace
  symbols. Experimental validation: fixes 5/7 FPs when TS2339 guard is
  relaxed (all 5 module augmentation FPs + 1 cross-file reference).
  Remaining FPs: 1 narrowing (D2), 1 cross-file not in globals.
  No test gains (infrastructure only). 7,685 passing.

  **File:** `Checker.kt` — `mergeModuleAugmentations`
  **Unblocks:** 13a (TS2339 widening, partially — D2 still needed)

- [x] **D2+D3. Relax TS2339 guard with narrowing-safe heuristic**

  Combined D2 and D3 using a pragmatic approach: instead of implementing
  full control flow narrowing, relaxed the TS2339 guard to check non-this
  property access with these safety constraints:
  - For class/namespace/module identifiers: always check (static shapes)
  - For variable identifiers: only check when the type is an interface (not a class)
  - Class-typed variables are skipped because `instanceof` narrowing might
    apply (e.g., `let x: Base; if (x instanceof Derived) { x.prop }`)
  - Also checks symbol's namespace exports for merged class+namespace (D1 infrastructure)

  **+1 test** (deleteExpressionMustBeOptional__strict_false). 7,686 passing.

  **File:** `Checker.kt` — `checkSinglePropertyAccess`
  **Note:** The original "15 pure tests" estimate assumed full type resolution
  for local variables, which `getTypeOfIdentifier` can't do (returns anyType
  for most locals). Actual gain is limited by type resolution capabilities.

- [x] **D4. Relax TS2345 isSimpleCheckableType guard** — BLOCKED

  Attempted adding Interface and function types: caused 2 regressions
  (inferentialTypingWithFunctionTypeSyntacticScenarios, mutrec) from
  incomplete generic/recursive type resolution. Reverted. Needs proper
  generic instantiation before this can be safely relaxed.

  **Target:** 12 pure tests, 47 total

### Track E — Cross-File Resolution (target: +15-20 tests)

Improve multi-file name resolution and diagnostics.

- [ ] **E1. TS2307 — Cannot find module** — BLOCKED

  Attempted: relative-specifier-only TS2307 in multi-file mode caused 29
  regressions (+6 new passes). FPs from `resolveModuleSpecifier` not handling
  paths config, symlinks, JSON imports, index resolution, custom suffixes.
  Needs proper module resolution infrastructure before this can be enabled.

  **File:** `Checker.kt` — module resolution checking
  **Target:** 5 pure tests, 8 total (15 pure found in analysis, but most
  need resolution features we don't have)

- [ ] **E2. Cross-file TS2300/TS2393 duplicate detection** — NEEDS ANALYSIS

  Analysis found 10 pure TS2300 failing tests, but most are merge validation
  (class static vs namespace export, var+namespace, global interface merge)
  rather than simple cross-file duplicates. Requires different checking logic
  per pattern.

  **File:** `Checker.kt` — `checkDuplicateDeclarations`
  **Target:** 10 pure tests (revised from 4), but complex merge validation

- [x] **E3. Cross-file TS2448 — block-scoped variable used before declaration**

  Added `checkCrossFileUseBeforeDeclaration()` pass after per-file TS2448 checks.
  For each file, checks top-level expression statements for identifiers that
  reference block-scoped (let/const) declarations in later files. Emits TS2448
  with TS2728 related info pointing to the declaration in the later file.
  Skips locally-declared names (handled by same-file checks).

  **+2 tests** (constDeclarations-useBeforeDefinition2, letDeclarations-useBeforeDefinition2).
  7,695 passing.

  **File:** `Checker.kt` — `checkCrossFileUseBeforeDeclaration`, `emitCrossFileTS2448`

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
