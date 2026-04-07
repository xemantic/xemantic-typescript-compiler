# Phase 4 — Structural Type Checker

**Status (2026-04-07):** 8,003 / 10,077 tests passing (79.4%). Active queue: Phase 10.

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

**Completed session 2026-03-31b (+9 tests, 7,694 → 7,703):**
- E3: Cross-file TS2448 block-scoped variable used before declaration.
  +2 tests (constDeclarations-useBeforeDefinition2, letDeclarations-useBeforeDefinition2).
- TS2511: Abstract class instantiation check — walks nested scopes tracking abstract names.
  +1 test (abstractClassInLocalScopeIsAbstract). Other TS2511 tests need union types or
  cross-file import resolution.
- TS2394: Overload signature compatibility — conservative check for return type mismatch,
  param count mismatch, param type mismatch (intrinsic types). Includes TS2750 related info.
  +6 tests (anyIdenticalToItself, functionOverloads4/11/20, voidAsNonAmbiguousReturnType,
  overloadAssignmentCompat).
- TS2430: Interface incorrectly extends interface — property type comparison using type system.
  +1 test (interfaceDeclaration6). Other TS2430 tests need method signature comparison or
  index signature checks.

**Completed session 2026-04-01 (+7 tests, 7,703 → 7,710):**
- globalArrayType: Synthetic Array interface enabling proper `T[]`/`Array<T>` type resolution.
  `getArrayType` now returns `Type.Reference(globalArrayType, [elementType])` instead of `anyType`.
  Enables null/undefined → array type checking via existing `canUseTypeEngine` guard.
  +2 tests (assignmentCompatability46, genericMemberFunction).
- formatTypeForDisplay: normalize `Array<T>` → `T[]` in error messages.
  +1 test (declFileGenericType).
- TS2506: Circular base class detection — collects class extends relationships in scope,
  follows chains to detect direct (`class A extends A`) and indirect (`A→B→A`) cycles.
  +3 tests (classInheritence, indirectSelfReference, indirectSelfReferenceGeneric).
- TS2315: "Type is not generic" — checks TypeReference and heritage clause type arguments
  against resolved type's parameters. Guards: only for class/interface/type alias/module
  symbols (not variables), resolves import aliases, uses resolveTypeNameToSymbol for
  qualified names.
  +1 test (moduleAndInterfaceSharingName2).
- Infrastructure: BUILTIN_GENERICS set (before init), resolveTypeNameToSymbol helper,
  getTypeParametersOfSymbol follows import aliases.
- TS2411: Property type vs index signature compatibility check. Handles inherited
  index signatures from base classes. Skips private fields (#name).
  +3 tests (classIndexer2, classIndexer3, functionAndInterfaceWithSeparateErrors).
- TS2420: Extended to detect private class members implementing public interface
  members. +4 tests (interfaceImplementation6, publicMemberImplementedAsPrivateInDerivedClass,
  privateInterfaceProperties, implementPublicPropertyAsPrivate).
- TS2320: Interface cannot extend types with conflicting private/public members.
  +3 tests (baseTypePrivateMemberClash, inheritSameNamePropertiesWithDifferentVisibility,
  inheritSameNamePrivatePropertiesFromDifferentOrigins).
- TS2366: Getter implicit return without annotation — emits TS2366 when getter has value
  returns on some paths but not all, under strictNullChecks.
  +2 tests (getterControlFlowStrictNull es5 and es2015 variants).

**Completed session 2026-04-03 (+20 tests, 7,722 → 7,742):**
- TS2415: Class incorrectly extends base class — private member conflict detection.
  +3 tests (derivedClassOverridesPrivateFunction1, shadowPrivateMembers, 
  inheritanceGrandParent* series, scopeTests). Extended to constructor parameter 
  properties. Walks base type chain for grandparent private declarations.
- TS2661: Cannot export non-local declaration. Checks export specifiers (no `from`)
  against file locals. +7 tests (exportSpecifierForAGlobal, reExportGlobalDeclaration1-4,
  exportSpecifierReferencingOuterDeclaration2, reExportUndefined1).
- TS2451 fix: Use TS2451 instead of TS2300 for const/class declaration conflicts.
  +2 tests (exportInterfaceClassAndValue + 1 variant).
- TS2440: Import declaration conflicts with local declaration. Scans file statements
  for import + value name collisions. +2 tests (importAndVariableDeclarationConflict1/4,
  duplicateVarAndImport2, functionAndImportNameConflict).
- TS2709: Cannot use namespace as a type. Checks type reference nodes for names
  resolving to namespace-only symbols. Skips imported names to avoid FPs.
  +6 tests (moduleAssignmentCompat1-4, moduleCrashBug1, moduleWithNoValuesAsType,
  moduleWithValuesAsType).

**Analysis of remaining test landscape (updated 2026-04-03):**
- All formal Track items (A-E) complete, blocked, or deferred
- Single-code failing tests by diagnostic code:
  TS2322 (232), TS2339 (77), TS2345 (69), TS2353 (28), TS2304 (16),
  TS2352 (15), TS2307 (15), TS2403 (15), TS2741 (14), TS2305 (11),
  TS2769 (11), TS2300 (10), TS2367 (10), TS2554 (10)
- Top FP codes: TS1005 (57 tests), TS1109 (36), TS2304 (32), TS2322 (25), TS7006 (24)
- Most test gains now blocked on: (a) wider canUseTypeEngine guard, 
  (b) module resolution (TS2305/TS2307), (c) parser error recovery (TS1005/TS1109)

**Completed session 2026-04-03b (+7 tests, 7,742 → 7,749):**
- TS2417: Class static side incorrectly extends base class static side. Compares
  static members and namespace exports between derived and base classes for type
  incompatibility and private member conflicts. Includes simple return type inference
  from method bodies (string/number literals). Also handles clodule pattern
  (class + merged namespace).
  +7 tests (inheritanceStaticMembersIncompatible, overridingPrivateStaticMembers,
  inheritanceStaticFuncOverridingProperty, inheritanceStaticAccessorOverridingMethod,
  inheritanceStaticFuncOverridingAccessor, inheritanceStaticPropertyOverridingMethod,
  inheritedModuleMembersForClodule).
- TS2729: Property used before initialization. Checks class property initializers
  for `this.X` (instance) or `ClassName.X` (static) references to properties
  declared below or without initializer/`!`. Skips deferred refs (arrow/function).
  Handles inherited properties from extends chain (not implements).
  +5 tests (checkInheritedProperty, initializerWithThisPropertyAccess,
  useBeforeDeclaration_propertyAssignment, useBeforeDeclaration_superClass,
  classMergedWithInterfaceMultipleBasesNoError).
- Small fixes: TS2315 squiggle length for ExpressionWithTypeArguments (use lastArg.end),
  TS2411 string literal property names include quotes in display,
  TS2420 separate private declarations when both sides are private.
  +4 tests (superCallFromClassThatDerivesNonGenericTypeButWithTypeArguments1,
  stringIndexerAndConstructor, stringIndexerAndConstructor1,
  classExtendsInterfaceThatExtendsClassWithPrivates1).

**Completed session 2026-04-03c (+7 tests, 7,758 → 7,765):**
- TS2341: Private member accessibility check. Handles instance access (`c.x`),
  static access (`C.e`), subclass `this` access (`this.options` in derived class),
  and `new X()` type inference for untyped variables. Guards: get/set accessor
  pairs with mixed visibility (public getter + private setter is allowed),
  `getClassNameWithTypeParams` for generic class display names (`D<T>`).
  +5 tests (propertyAccessibility1, propertyAccessibility2, privateVisibility,
  privateAccessInSubclass1, cloduleStaticMembers).
- ModuleBlock fix: `checkPropertyAccessInStatement` was casting namespace body
  to `Block` instead of `ModuleBlock`, so TS2339/TS2341 checks never ran inside
  namespace bodies. This fixed `cloduleStaticMembers` (TS2341 in clodule pattern).
- TS2454 co-emit with TS2448: Block-scoped variable use-before-declaration now
  co-emits TS2454 ("used before being assigned") under strict mode, but only for
  `let` declarations (not `const`). Added `isConst` to `BlockScopedDecl`.
  +2 tests (forwardRefInClassProperties, useBeforeDeclaration_destructuring).

- TS2300 numeric key normalization: `normalizeNumericKey` now handles binary (`0b11`),
  octal (`0o3`), and hex (`0x3`) prefixed literals for duplicate property detection.
  +1 test (duplicateIdentifierDifferentSpelling).
- TS2302: Static members cannot reference class type parameters. Walks static member
  type annotations and initializer expressions to detect references to enclosing class
  type parameters. Guards: method-local type parameters that shadow class type params
  are excluded from checking.
  +5 tests (typeParametersInStaticProperties, typeParametersInStaticMethods,
  staticMethodsReferencingClassTypeParameters, genericClassWithStaticsUsingTypeArguments,
  typeParametersInStaticAccessors).

**Completed session 2026-04-04 (+18 tests, 7,771 → 7,789):**
- Binder: Interface+Function merge rule in `canMerge` — fixes TS2709 FP for merged
  function+interface symbols (privacyCheckExportAssignment test).
- TS2440: Skip internal namespace aliases (`import foo = m1`) when they don't
  conflict with variable declarations. Track varNames vs mergeableNames separately.
  +1 test (importedModuleClassNameClash).
- TS2454: Co-emit with TS2448 only when `let` declaration has initializer.
  Uninitialized `let l1;` doesn't co-emit TS2454, but `let v1 = 0` does.
  +1 test (letDeclarations-useBeforeDefinition).
- TS2802: Downlevel iteration check for `arguments` in for-of and array
  destructuring when target < ES2015 and no `downlevelIteration` flag.
  +2 tests (argumentsObjectIterator01_ES5, argumentsObjectIterator03_ES5).
- TS2507: Check extends clause resolving to variable with primitive type
  (not a class/function) in namespace scope. Conservative — only flags
  primitive types to avoid FPs with constructor interfaces.
  +2 tests (classExtendsClauseClassNotReferringConstructor,
  classExtendsClauseClassMergedWithModuleNotReferingConstructor).
- TS2304: Remove `let` from KEYWORD_IDENTIFIERS to allow co-emission with TS1212
  when `let` is used as bare expression statement in strict mode.
  +2 tests (downlevelLetConst6, downlevelLetConst11).
- JS baseline: symmetric `.d.ts` section stripping in `String?.sameAs(Path)`.
  Input `.d.ts` files were stripped from expected but not actual output.
  +9 tests (declarationEmitForGlobalishSpecifierSymlink x2, duplicatePackage_globalMerge,
  externalModuleResolution2, jsFileCompilationWithDeclarationEmitPathSameAsInput,
  moduleResolutionWithSuffixes x3).
- Investigation: relaxing TS7006/TS7019 gate (always-on noImplicitAny) causes
  net regression (-6). Kept gated behind `noImplicitAny || strict`.

**Completed session 2026-04-04b (+8 tests, 7,789 → 7,797):**
- TS2558: Type argument count mismatch on call/new expressions. Extended
  `getTypeParamInfoFromSymbol` to handle FunctionDeclaration. Checks Identifier
  callees against resolved type param count. Skips overloads and default params.
  +2 tests (constructorInvocationWithTooFewTypeArgs, callWithWrongNumberOfTypeArguments).
- TS2378: Get accessor must return a value. Checks getter bodies for absence
  of any return or throw statement. +1 test (getterMissingReturnError).
- TS2393: Cross-file duplicate function implementation. Only checks outFile
  (bundle) mode with non-module files. +2 tests (jsFileCompilationDuplicateFunction* x2).
- Type display: `{}` instead of `{ ... }` for empty anonymous object types.
  +1 test (noErrorsInCallback).
- TS2437: Module hidden by local declaration. Checks import equals declarations
  inside namespace blocks for shadowed module names. +2 tests
  (internalImport*ModuleMergedWithClassNotReferencingInstance x2).
- FP fix: getTypeParamInfoFromSymbol prioritizes interface/class declarations over
  function declarations for merged symbols. Fixes FP TS2314 for function+interface
  with same name. +1 test (exportClassExtendingIntersection).

**Analysis of remaining test landscape (updated 2026-04-05):**
- 2,128 failing tests (down from 2,276)
- ~61% (1,289) produce zero diagnostics — need deep type checking infrastructure
- 86 near-miss tests (1 missing code, 0 FPs): TS2339 (16), TS2322 (8), TS2345 (5), TS2307 (5)
- 7 FP-only tests (0 missing, only extra diagnostics)
- 237 JS emit failures
- Most gains now blocked on: (a) deeper type engine (TS2322/TS2339/TS2345),
  (b) module resolution (TS2307), (c) contextual typing for lambda params (TS7006 FP)

**Completed session 2026-04-05 (+4 tests, 7,942 → 7,946):**
- TS1212 FP: suppress expression-position check when alwaysStrict:false + strict:false
- TS2300 FP: skip modifier keywords (public/private/protected/...) in class member
  duplicate checking — error recovery artifacts. Excludes 'static'.
- TS1268: index signature parameter type validation — skip rest/optional/multi params
- Transformer: {default as d} in combined import uses __importDefault not __importStar

**Completed session 2026-04-05b (+12 tests, 7,949 → 7,961):**
- TS2339 static method `this`: track `inStaticClassMethod` context, emit TS2339
  "does not exist on type 'typeof C'" for instance-only props in static methods.
  Walks extends chain for inherited static members.
  +3 tests: scopeCheckInsideStaticMethod1, scopeCheckExtendedClassInsideStaticMethod1,
  staticVisibility.
- TS2339 namespace non-exported access: check namespace exports for `M.prop` patterns,
  distinguish exported vs non-exported members via ExportValue flag and VariableStatement
  export modifier scanning.
- Heritage clause traversal: property access checking in class extends expressions.
- FP fixes (+9 additional tests):
  - `declare namespace` (NamespaceModule) members implicitly exported
  - Sub-namespace symbols (Module flag) always accessible from parent
  - Skip namespace check for import aliases (Alias flag)
  - Static method with explicit `this: Type` parameter: don't treat as static this context
  - Tests: constDeclarations-access4, moduledecl, commentsModules,
    esModuleInteropTslibHelpers, unusedParametersThis,
    blockScopedNamespaceDifferentFile (x2), declFileWithClassNameConflicting...,
    internalAlias*InsideLocalModuleWithoutExportAccessError (x4),
    qualifiedModuleLocals, undeclaredBase, classExtendingQualifiedName

**Remaining analysis (updated 2026-04-05b):**
- Chained namespace access (M.foo.x) not yet supported — needed for ~5 more tests
- TS2576 (instance→static suggestion) needs instance type inference — 17 baselines
- Near-miss TS2322: 33 tests, most need generic instantiation (21/33 blocked)
- FP-only: 18 tests found, 7 from namespace (FIXED), rest need contextual typing/module resolution
- Most remaining TS2339 near-miss tests need deeper type resolution (array, union, never)

**Completed session 2026-04-06 (+2 tests, 7,973 → 7,975):**
- Widened `canUseTypeEngine` guard: Union→Primitive, Intrinsic→Union, Primitive↔Literal,
  with safety guards for control flow narrowing FPs.
- Sorted union constituents by TypeFlags value in `getUnionType` (matches TypeScript ordering).
- Array-of-union display parenthesization: `(A | B)[]` not `A | B[]`.
- Union TS2322 elaboration: show last failing constituent (matches TypeScript).
- Negative literal type inference: `-42` → `NumberLiteral(-42)` instead of `numberType`.
- Number → enum assignability in `isSimpleTypeRelatedTo`.
- Intersection-to-never reduction for incompatible primitives (number & boolean → never).
- Tests: conditionalExpression1, errorMessagesIntersectionTypes04.
- KEY FINDING: Fully opening canUseTypeEngine gives ZERO new passes and 10 regressions.
  The real bottleneck is `getTypeOfExpression` returning `anyType` for most non-literal
  expressions (missing lib.d.ts types, no initializer inference, no import resolution).
- Only 9 pure FP-only tests exist, all requiring deep type system features (contextual
  typing, exhaustive switch analysis, module augmentation, etc.).
- Initializer type inference tested → 6 regressions (TS2403 FPs), reverted.
  Partial inference is worse than no inference.

**Completed session 2026-04-06b (+1 test, 7,975 → 7,976):**
- 6.0: Tuple type resolution — `getTupleType` now creates `Type.Object` with:
  - Numbered property symbols ("0", "1", ...) with resolved element types
  - `length` property with `NumberLiteral(n)` type
  - `numberIndexInfo` with union of element types
  - `tupleElementTypes` field on `Type.Object` for display and identification
  - Handles `NamedTupleMember`, `OptionalType`, `RestType` elements
- `typeToString` displays tuples as `[T1, T2, ...]`
- `canUseTypeEngine` guard extended for tuple targets (function/primitive→tuple only;
  array→tuple skipped to avoid FPs from missing contextual typing)
- +1 test: `assigningFunctionToTupleIssuesError`
- 15 other tuple tests improved from "none produced" to "has diff" (need deeper
  tuple-specific checking: element count, positional assignability, TS2493)
- 6.1: TypeQuery (typeof) resolution — `getTypeFromTypeQuery` resolves:
  - Built-in names: `typeof undefined` → undefinedType, `typeof NaN` → numberType
  - Variables/functions: via currentLocalTypes then globals → getTypeOfSymbol
  - Classes: constructor type (Object with construct signature + static members)
  - Import aliases: follows alias chain
  - Qualified names: `typeof A.B.C` via resolveQualifiedName
- `formatTypeForDisplay` extended for TypeQuery → `typeof X` display
- +1 test: `typeofUndefined`
- 6.2: Generic type instantiation — connected existing infrastructure:
  - 6.2a: `resolveReferenceMembers` now applies TypeMapper to properties, signatures,
    and index info. Creates new symbols with instantiated types.
  - 6.2b: `getTypeFromTypeReference` now creates `Type.Reference(target, typeArgs)`
    for user-defined generics like `Foo<number>`, not just Array/ReadonlyArray.
  - 6.2c: `instantiateSignature` now instantiates parameter types (creates new
    parameter symbols with mapped types), not just return types.
  - Infrastructure only — 0 direct test gains. Most generic tests also need namespace
    property resolution (6.4) or import resolution (6.6) for the value side.
  - Guard: skip Reference creation when type args contain errorType (prevents
    `C2<error>` display for unresolved type parameters like `C2<T>`).
- 6.3: Variable type inference from initializers (scoped):
  - `checkVarDeclAssignability` populates `currentLocalTypes` from initializers
    for unannotated variables (`var x = 42` → `x: number`).
  - Widens literal types (42→number, "hello"→string, true→boolean).
  - Skips null/undefined/void initializers (avoid FPs for "declare then assign" pattern).
  - `getTypeFromTypeQuery` uses globals only (not currentLocalTypes) to avoid
    resolving function-scoped variables in type annotation positions.
  - +4 tests: `checkJsFiles`, `checkJsFiles2`, `checkJsFiles3`, `checkJsFiles4`
- 6.4: Namespace property type resolution:
  - `getTypeOfPropertyAccess` now falls back to namespace/module exports when
    object type is anyType. Handles chained access `ns.sub.member` via
    `resolvePropertyAccessToSymbol` recursive helper.
  - Infrastructure only — 0 direct gains. Namespace assignment tests need
    deeper variable inference chains and Object→Object comparison in canUseTypeEngine.

**Session 2026-04-06b summary:** +6 tests (7,975 → 7,981). Implemented items 6.0-6.4.
Most remaining test gains blocked on deep type system features: control flow narrowing,
contextual typing, structural Object→Object comparison in canUseTypeEngine.
Items 6.5-6.8 remain infrastructure-only with uncertain ROI.
Of 2,096 failing tests: 226 need TS2322, 85 need TS2339, 82 need TS2345 (all "none produced").
11 pure FP tests found (extra diagnostics only), requiring: contextual typing (TS7006),
exhaustive switch analysis (TS2366), control flow narrowing, pretty format.

---

## Phase 6 — Type Resolution Queue

**Bottleneck analysis (2026-04-06):** 2,102 failing tests. 1,238 (66%) produce zero diagnostics.
The `canUseTypeEngine` guard is NOT the bottleneck — `getTypeOfExpression` returning `anyType` is.

**Failing test blocker distribution (pure single-code tests):**

| Blocker | TS2322 (149) | TS2339 (34) | TS2345 (36) | TS2353 (17) | Total |
|---------|-------------|-------------|-------------|-------------|-------|
| Generics | 85 (57%) | 14 (41%) | 19 (53%) | 3 | ~121 |
| Namespaces | 38 (26%) | 3 | 4 | 1 | ~46 |
| No annotation | 22 (15%) | — | — | — | ~22 |
| Multi-file | 8 | 8 (24%) | 5 | 3 | ~24 |
| Imports | 3 | 7 (21%) | 3 | 3 | ~16 |
| typeof | 7 | 1 | 1 | 0 | ~9 |
| Lib types | 3 | 2 | 1 | 0 | ~6 |

**Dependencies between items:**
```
6.0 (tuple) ─────────────────────────┐
6.1 (typeof) ────────────────────────┤
6.2 (generics) ──┬── 6.5 (structural)┼── 6.7 (contextual typing)
6.3 (inference)──┘                    │
6.4 (namespaces) ────────────────────┤
6.6 (imports) ───────────────────────┘
6.8 (narrowing) ── independent, FP prevention
```

### QUEUE

- [x] **6.0. Tuple type resolution**

  `getTupleType` currently returns bare `Type.Object()`. Create proper tuple types
  with indexed members (`0: T1, 1: T2, ...`) and `length` property.

  **Implementation:**
  - In `getTupleType(node: TupleType)`: create `Type.Object` with numbered properties
  - Each element type → property symbol with name "0", "1", etc.
  - Add `length: NumberLiteral(n)` property
  - Handle `NamedTupleMember`, `OptionalType`, `RestType` in elements
  - Array-like: set the target to `globalArrayType` for `T[]`-style display

  **Unlocks:** `assigningFunctionToTupleIssuesError` + tuple-related tests
  **File:** `Checker.kt` — `getTupleType`
  **Estimated gain:** 3-5 tests

- [x] **6.1. TypeQuery (typeof) resolution**

  `typeof X` in type annotation position returns `anyType`. Implement resolution
  to the type of value `X`.

  **Implementation:**
  - In `getTypeFromTypeNodeWorker`, case `TypeQuery`:
    - Resolve the entity name to a symbol (via globals/locals)
    - For class symbols: return the constructor type (Object with construct signature)
    - For function symbols: return the function type (via `getTypeOfFunction`)
    - For variable symbols: return the declared/inferred type
  - Handle qualified names: `typeof A.B.C`

  **Unlocks:** `classSideInheritance3`, `assignToFn`, typeof-based tests
  **File:** `Checker.kt` — `getTypeFromTypeNodeWorker`
  **Estimated gain:** 5-10 tests

- [x] **6.2. Generic type instantiation — connect existing infrastructure**

  The #1 blocker (57% of TS2322, 53% of TS2345). Infrastructure exists
  (`instantiateType`, `createTypeMapper`, `Type.Reference`) but is not connected
  to member resolution or call type checking.

  **Implementation — 3 sub-items:**

  **6.2a.** Type.Reference member instantiation:
  - In `resolveReferenceMembers`: after getting target members, apply type mapper
  - Create mapper from `target.typeParameters` → `ref.resolvedTypeArguments`
  - Instantiate each property's type, each call/construct signature's params and return
  - Use existing `instantiateType` and `instantiateSignature`

  **6.2b.** `getTypeFromTypeReference` for user-defined generics:
  - When `Foo<number>` is encountered and `Foo` has type parameters:
  - Create `Type.Reference(fooInterface, [numberType])`
  - Currently only handles Array/ReadonlyArray — extend to all generics
  - Cache instantiated references by (target, typeArgs) to avoid duplicates

  **6.2c.** `instantiateSignature` parameter types:
  - Currently only instantiates return types (TODO at line 30568)
  - Must also instantiate parameter types for TS2345 argument checking
  - Create new parameter symbols with substituted types

  **Unlocks:** ~85 TS2322, ~19 TS2345, ~14 TS2339 tests (with overlap)
  **File:** `Checker.kt` — `resolveReferenceMembers`, `getTypeFromTypeReference`, `instantiateSignature`
  **Estimated gain:** 30-60 tests (the single highest-ROI item)

- [x] **6.3. Variable type inference from initializers (scoped)**

  `var x = 42` should infer `numberType` for x. Previous attempt caused TS2403 FPs
  from partial inference. Fix: only use in TS2322 context, not globally.

  **Implementation:**
  - Do NOT change `getTypeOfVariableOrProperty` (causes TS2403 FPs)
  - Instead, populate `currentLocalTypes` from initializers during TS2322 walk:
    - In `checkVarDeclAssignability`, when `decl.type == null && decl.initializer != null`:
      - `val inferred = getTypeOfExpression(decl.initializer)`
      - If non-anyType: `currentLocalTypes[name.text] = inferred`
  - Also populate in `checkAssignmentExpression` for `checkFunctionBody`
  - This keeps inference scoped to TS2322 context only

  **Unlocks:** ~22 TS2322 tests where vars have no annotation but clear initializers
  **File:** `Checker.kt` — `checkVarDeclAssignability`, `checkFunctionBody`
  **Estimated gain:** 5-15 tests

- [x] **6.4. Namespace property type resolution**

  `ns.member` in expression position should resolve the type of the namespace export.
  `getTypeOfPropertyAccess` works but relies on `getApparentType` which doesn't resolve
  namespace symbols properly.

  **Implementation:**
  - In `getTypeOfPropertyAccess`: when `objectType` is anyType, try namespace lookup:
    - If expr.expression is Identifier, look up in globals
    - If symbol has Module flag, look up propName in symbol.exports
    - Return `getTypeOfSymbol(exportSymbol)`
  - Handle chained access: `ns.sub.member` (recursive)
  - Handle class+namespace merge (clodule): check both class instance and namespace exports

  **Unlocks:** ~38 TS2322 namespace tests (assignmentCompatability11-45 series)
  **File:** `Checker.kt` — `getTypeOfPropertyAccess`, `getTypeOfIdentifier`
  **Estimated gain:** 10-20 tests

- [x] **6.5. Structural member resolution improvements**

  `objectTypeRelatedTo` needs complete member resolution for named types.
  Current gaps: inherited members from base types, method signatures,
  index signatures, construct signatures.

  **Implementation (completed):**
  - Method type resolution: `getTypeOfVariableOrProperty` handles MethodDeclaration (function type
    with overloaded signatures) and GetAccessor (return type)
  - Call/construct signature resolution: `resolveInterfaceMembers` separates call sigs (empty-name)
    and construct sigs ("new"-name) from named property members
  - Overloaded method symbols: reuses symbols for same-name methods instead of overwriting
  - Construct sig comparison skip: `objectTypeRelatedTo` skips for class/interface instances
  - TS2430 method guard: skips method-typed base properties to avoid FPs
  - Eager sig resolution: canUseTypeEngine resolves members before function→function check
  - Interface overload guard: getReturnTypeOfCallExpression returns anyType for multi-sig interfaces
  - Anonymous→named guard: canUseTypeEngine allows anonymous Object → named Interface

  **Result:** 0 direct test gains (anonymous→named doesn't match current test patterns;
  most failing tests are named→named which requires recursive type handling).
  Infrastructure is correct and regression-free. Full Object↔Object opening blocked by:
  (a) recursive types (infinite expansion), (b) incomplete overload resolution.

  **File:** `Checker.kt` — `objectTypeRelatedTo`, `resolveInterfaceMembers`

- [x] **6.6. Import/cross-file type resolution**

  Imported names should resolve to their target types across files.

  **Implementation (completed):**
  - Connected `resolveAliasTarget` → `resolveAlias` for on-demand cross-file resolution
  - Added `SymbolFlags.Alias` handling in `getDeclaredTypeOfSymbolWorker` — follows alias
    chain to get target's declared type (for type references to imported names)
  - `resolveModuleSpecifier`, `resolveAlias`, and cross-file infrastructure already existed

  **Result:** 0 direct test gains — multi-file tests require additional features
  (cross-file diagnostic emission, full checker integration) beyond type resolution.
  Infrastructure is correct and regression-free.

  **File:** `Checker.kt` — `resolveAliasTarget`, `getDeclaredTypeOfSymbolWorker`

- [x] **6.7. Basic contextual typing**

  When a function expression is assigned to a typed variable, infer parameter types
  from the target type's call signature.

  **Implementation (completed):**
  - Added `contextualType` field — set when evaluating function expression initializers
  - `applyContextualParameterTypes` infers parameter types from contextual call signature
  - Applied in both variable declarations and assignment expressions when the target
    type is an Object with call signatures and the source is ArrowFunction/FunctionExpression
  - Parameter types stored via symbolTypes[] so getTypeOfSymbol returns contextual type

  **Result:** 0 direct test gains — TS7006 FP tests also need the TS7006 checker to
  check for contextual types, and most tests don't set `noImplicitAny`. Infrastructure
  is regression-free and enables correct parameter type inference for function expressions.

  **File:** `Checker.kt` — expression type inference

- [x] **6.8. Basic control flow narrowing**

  After `if (x !== null)`, narrow `x: T | null` to `x: T`.

  **Implementation (completed):**
  - `extractNullNarrowing` extracts narrowing info from if-conditions
  - Handles: `x !== null`, `x != null`, `x !== undefined`, `x != undefined`,
    `null !== x`, truthiness `if (x)` — removes null/undefined from unions
  - Loose equality (`!=`) removes both null and undefined (JS semantics)
  - Applied in then-branch of IfStatement within checkTypeAssignabilityInStmt
  - Uses saved/restored currentLocalTypes for proper scoping

  **Result:** 0 direct test gains — the specific tests mentioned in the estimate
  don't exist in the current test suite (disabled error baseline tests).
  Infrastructure is regression-free.

  **File:** `Checker.kt` — `extractNullNarrowing`, `checkTypeAssignabilityInStmt`

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

## Phase 7 — Infrastructure Unblocking Queue

**Status:** 7,981 / 10,077 tests passing (79.2%). 2,096 failing.

**Strategy:** Instead of chasing individual test gains, implement foundational features
in dependency order. Each item unblocks the items below it. The goal is to build the
infrastructure that makes future test gains cascade naturally.

**Dependency graph:**
```
7.0 (recursive type tracking)
 └─▶ 7.2 (open Object↔Object comparison)
      └─▶ 7.5 (overload resolution)
           └─▶ 7.6 (indexed access types)
                └─▶ 7.8 (conditional types)
                └─▶ 7.9 (mapped types)

7.1 (scope-chain identifier resolution)
 └─▶ 7.3 (variable initializer type inference)
      └─▶ 7.4 (property access chain typing)
           └─▶ 7.5 (overload resolution — needs typed arguments)

7.7 (control flow graph) — independent, but enables 7.2 safety
```

### QUEUE

- [x] **7.0. Recursive type cycle detection in structural comparison**

  **Problem:** `canUseTypeEngine` blocks named↔named interface and Reference type
  comparison because recursive types (e.g. `interface List<T> { next: List<T> }`)
  cause infinite expansion. The current `maxRelationDepth` counter is a blunt depth
  limit that doesn't distinguish different comparison pairs.

  **Implementation:**
  - Add a `recursionStack: MutableSet<Long>` to the checker (packed source.id/target.id)
  - In `checkTypeRelatedTo`: before recursing, check if (source.id, target.id) is already
    on the stack. If so, return `Ternary.Maybe` (assume compatible — TypeScript's approach)
  - Push the pair before calling `structuredTypeRelatedTo`, pop after
  - This replaces the blunt `relationDepth >= maxRelationDepth` with precise cycle detection
  - Reference: TypeScript's `overflow` tracking in `structuredTypeRelatedTo`; tsgo's
    `overflowCheckSet` in `relater.go`

  **Unblocks:** 7.2 (Object↔Object comparison) — the primary reason canUseTypeEngine
  rejects named interface pairs and Reference types.

  **File:** `Checker.kt` — `checkTypeRelatedTo`, `structuredTypeRelatedTo`

- [x] **7.1. Scope-chain identifier type resolution**

  **Problem:** `getTypeOfIdentifier` only resolves: literal keywords (`undefined`, `NaN`),
  `currentLocalTypes` (populated only during TS2322 walk), and global symbols. For any
  local variable, parameter, or function-scoped name, it returns `anyType`. This is THE
  fundamental blocker — 58% of failing tests produce zero diagnostics because expressions
  can't be typed.

  **Implementation:**
  - Add `resolveSymbolAtLocation(identifier: Identifier): Symbol?` that walks the AST
    parent chain to find the enclosing scope, then searches:
    1. Block-scoped declarations (let/const in enclosing blocks)
    2. Function parameters
    3. Variable declarations in enclosing functions
    4. Class members (for `this.x` — already partially handled)
    5. File-level locals (binder's `result.locals`)
    6. Globals
  - Integrate with the binder's existing symbol tables — the binder already creates symbols
    for all declarations, we just don't look them up from expression positions
  - In `getTypeOfIdentifier`: call `resolveSymbolAtLocation` instead of only checking globals
  - Use `getTypeOfSymbol` on the resolved symbol to get its type
  - For annotated declarations, this immediately works (type annotations already resolve)

  **Design consideration:** The binder currently only creates file-level symbol tables
  (`result.locals`). Function/block-level symbols are NOT in these tables. Two approaches:
  (a) Extend binder to build nested scope tables (larger change, cleaner long-term)
  (b) Walk the AST parent chain from the identifier to find enclosing declarations (simpler)
  Start with (b) and upgrade to (a) if performance is an issue.

  **Unblocks:** Everything downstream — without typed identifiers, `getTypeOfExpression`
  returns `anyType` for most variables, making all TS2322/TS2345/TS2339 checking impossible
  for non-literal expressions.

  **File:** `Checker.kt` — `getTypeOfIdentifier`, new `resolveSymbolAtLocation`

- [x] **7.2. Open canUseTypeEngine for Object↔Object structural comparison**

  **Problem:** `canUseTypeEngine` currently blocks all named↔named interface comparison
  and Reference type targets. With 7.0's cycle detection in place, these can be safely enabled.

  **Implementation:**
  - Remove the `targetType is Type.Interface && targetType.symbol != null` exclusion for
    anonymous → named (line 24753-24754)
  - Add: named Interface → named Interface (both symbols non-null)
  - Add: Reference → Interface and Interface → Reference
  - Add: Reference → Reference (generic instantiation comparison)
  - Keep guards for: Union → Object (needs narrowing), anyType/errorType (unresolved)
  - Run full test suite after each guard relaxation to catch regressions

  **Depends on:** 7.0 (recursive type cycle detection)

  **Unblocks:** 7.5 (overload resolution — needs to compare argument types against parameter
  types which are often interfaces/classes), generic constraint checking (TS2344), class
  hierarchy checking (TS2416/TS2420), and hundreds of TS2322 tests where both sides are
  named types.

  **File:** `Checker.kt` — `canUseTypeEngine`

- [x] **7.3. Variable initializer type inference (general)** (implemented as part of 7.1)

  **Problem:** `getTypeOfVariableOrProperty` returns `anyType` for unannotated variables
  (comment at line 25688: "initializer inference causes FPs in TS2403/TS2322"). The
  previous scoped approach (6.3, `currentLocalTypes`) only works during TS2322 walk.
  We need general initializer inference that's safe across all checking contexts.

  **Implementation:**
  - In `getTypeOfVariableOrProperty`, for `VariableDeclaration` without type annotation:
    - If the initializer is a literal, infer directly (already exists for numeric/string/bool)
    - If the initializer is a function/arrow expression, create function type
    - If the initializer is a `new X()` call, infer the class type
    - If the initializer is an identifier with a known type, use that type
    - Apply widening: literal types → base types (42 → number, "hello" → string)
    - Skip inference when initializer is `null`/`undefined` (declare-then-assign pattern)
  - Guard against TS2403 FPs: store inferred types in a separate cache (`inferredVarTypes`)
    and don't use them in TS2403 (redeclaration) checking
  - Same treatment for `Parameter` default values and `PropertyDeclaration` initializers

  **Depends on:** 7.1 (to resolve identifier initializers like `let x = y`)

  **Unblocks:** 7.4 (property access needs the base to be typed), contextual typing for
  callbacks, return type inference, most "none produced" tests where the source or target
  type comes from an unannotated variable.

  **File:** `Checker.kt` — `getTypeOfVariableOrProperty`

- [x] **7.4. Property access chain typing**

  **Problem:** `getTypeOfPropertyAccess` works when the base type resolves, but cascading
  `anyType` from `getTypeOfIdentifier` means most property access chains (`obj.prop.method()`)
  return `anyType`. With 7.1 and 7.3 providing base types, property access chains will
  resolve naturally — but we need to handle additional patterns.

  **Implementation:**
  - Handle `ElementAccessExpression` (bracket notation): `obj["prop"]` → resolve string
    literal key to member, `arr[0]` → resolve numeric key for tuples
  - Handle optional chaining: `obj?.prop` → same as `obj.prop` but nullable result
  - Handle `as const` (const assertions): narrow to literal types
  - Handle enum member access: `Enum.Member` → resolve to enum member type
  - Improve `getTypeOfPropertyAccess` to search index signatures when named property
    not found

  **Depends on:** 7.1 (base identifier resolution), 7.3 (base variable inference)

  **Unblocks:** TS2339 checking (property doesn't exist — need to type the base first),
  method call return types (need to resolve method → get its signature → return type).

  **File:** `Checker.kt` — `getTypeOfPropertyAccess`, new `getTypeOfElementAccess`

- [x] **7.5. Overload resolution**

  **Problem:** `getReturnTypeOfCallExpression` returns `anyType` for overloaded functions
  (line 26417: "returns anyType to avoid picking the wrong overload"). This cascades:
  any variable assigned from an overloaded call is `anyType`, losing all downstream typing.
  229 missing diagnostic instances blocked on this (TS2554, TS2769).

  **Implementation:**
  - Implement `resolveCall(signatures: List<Signature>, args: List<Expression>): Signature?`
  - For each overload signature (in order):
    1. Check arity: `args.size >= sig.minArgumentCount && args.size <= sig.parameters.size`
    2. Check argument types: for each arg, `checkTypeRelatedTo(getTypeOfExpression(arg),
       paramType, assignableRelation)`
    3. First matching overload wins (TypeScript's approach)
  - If no overload matches: use the implementation signature's return type (if available)
    or `anyType`
  - For TS2769 diagnostic: when no overload matches, emit the error with elaboration
    showing each overload's incompatibility
  - Handle generic overloads: basic type argument inference from argument types

  **Depends on:** 7.2 (Object↔Object comparison — overload parameters are often interfaces),
  7.1+7.3 (typed arguments)

  **Unblocks:** Typed return values from stdlib functions (Array.map, Promise.then, etc.),
  TS2769 diagnostics, chained call typing, builder pattern APIs.

  **File:** `Checker.kt` — new `resolveCall`, modify `getReturnTypeOfCallExpression`

- [x] **7.6. Indexed access types (T[K])**

  **Problem:** `IndexedAccessType` in type position returns `anyType`. This blocks
  mapped types, many utility types, and real-world patterns like `Config["database"]`.

  **Implementation:**
  - In `getTypeFromTypeNodeWorker`, for `IndexedAccessType`:
    - Resolve `objectType` and `indexType`
    - If indexType is a string literal: look up named property on objectType
    - If indexType is `number`: return index signature type or array element type
    - If indexType is a union: create union of indexed access results
    - If indexType is `keyof T`: create union of all property types
  - Implement `keyof T` in `getTypeFromTypeOperator`:
    - Collect all property names from T's members
    - Create union of string literal types for each name
  - Handle `T[number]` for array/tuple types

  **Depends on:** 7.2 (needs structural member resolution for the object type)

  **Unblocks:** 7.8 (conditional types use indexed access), 7.9 (mapped types use
  `T[K]` in their body), utility types like `Pick`, `Record`, `ReturnType`.

  **File:** `Checker.kt` — `getTypeFromTypeNodeWorker`

- [x] **7.7. Control flow graph and type narrowing** (7.7a: typeof narrowing)

  **Problem:** Only basic null/undefined narrowing in if-then branches exists (6.8).
  No discriminated unions, no `typeof` narrowing, no `instanceof`, no type guard
  functions. Union→Object comparison is blocked in `canUseTypeEngine` because without
  narrowing, comparing `string | number` to `{ length: number }` causes FPs.

  **Implementation (phased):**

  **7.7a.** `typeof` narrowing:
  - In `if (typeof x === "string")`: narrow `x` to `string` in then-branch
  - Handle: `"string"`, `"number"`, `"boolean"`, `"function"`, `"object"`, `"undefined"`
  - Remove narrowed type from else-branch

  **7.7b.** `instanceof` narrowing:
  - In `if (x instanceof Foo)`: narrow `x` to `Foo` in then-branch
  - Requires: resolving `Foo` to a class type and intersecting with current type

  **7.7c.** Discriminated union narrowing:
  - In `if (x.kind === "circle")`: narrow `x` from `Circle | Square` to `Circle`
  - Requires: checking literal property types in union constituents

  **7.7d.** Truthiness narrowing expansion:
  - Remove `null | undefined` from type in truthy branches (already exists)
  - Add: empty string / zero removal for string/number unions
  - Narrow in else-branch (add null/undefined, etc.)

  **Independent** of other items. Each sub-item can be implemented and tested separately.

  **Unblocks:** Safe Union→Object comparison in canUseTypeEngine (with narrowing,
  we can allow these comparisons), discriminated union patterns, type guard functions,
  exhaustiveness checking.

  **File:** `Checker.kt` — `extractNullNarrowing` → `extractNarrowing` (generalized)

- [x] **7.8. Conditional types (basic)**

  **Problem:** `ConditionalType` returns `anyType`. Conditional types are the foundation
  of TypeScript's utility types (`Extract`, `Exclude`, `NonNullable`, `ReturnType`, etc.).

  **Implementation:**
  - In `getTypeFromTypeNodeWorker`, for `ConditionalType` (`T extends U ? X : Y`):
    - Resolve T, U, X, Y
    - If T is concrete (not a type parameter): evaluate `isTypeRelatedTo(T, U)`
    - If true → return X, if false → return Y
    - If T is a union: distribute — `(A | B) extends U ? X : Y` →
      `(A extends U ? X : Y) | (B extends U ? X : Y)`
    - If T is an unresolved type parameter: return the conditional type unevaluated
      (or anyType as conservative fallback)
  - Handle `infer` keyword: in the true branch, extract inferred type from the constraint
    position (e.g., `T extends (...args: any[]) => infer R ? R : never` → extract return type)

  **Depends on:** 7.6 (conditional types often use indexed access in branches),
  7.2 (extends clause uses type relation)

  **Unblocks:** `ReturnType<T>`, `Parameters<T>`, `Extract<T, U>`, `Exclude<T, U>`,
  `NonNullable<T>`, `InstanceType<T>`, and all user-defined conditional types.

  **File:** `Checker.kt` — `getTypeFromTypeNodeWorker`

- [x] **7.9. Mapped types**

  **Problem:** `MappedType` returns `anyType`. Mapped types power TypeScript's most
  common utility types (`Partial<T>`, `Required<T>`, `Readonly<T>`, `Pick<T, K>`,
  `Record<K, V>`).

  **Implementation:**
  - In `getTypeFromTypeNodeWorker`, for `MappedType` (`{ [K in keyof T]: ... }`):
    - Resolve the constraint type (usually `keyof T`)
    - For each key in the constraint: create a property with the mapped type
    - Handle modifiers: `+readonly`, `-readonly`, `+?`, `-?`
    - For `Record<K, V>`: constraint is K, type is V — create object with K-typed keys
  - Create `Type.Object` with computed properties
  - Handle homomorphic mapped types (preserve optional/readonly from source)

  **Depends on:** 7.6 (`keyof` and indexed access for the mapped body)

  **Unblocks:** `Partial<T>`, `Required<T>`, `Readonly<T>`, `Pick<T, K>`,
  `Record<K, V>`, `Omit<T, K>`, and user-defined mapped types.

  **File:** `Checker.kt` — `getTypeFromTypeNodeWorker`

---

## Execution order

**Phase 7** (active): Infrastructure unblocking — work items 7.0–7.9 in dependency order.

| Item | Feature | Depends on | Unblocks |
|------|---------|------------|----------|
| 7.0 | Recursive type cycle detection | — | 7.2 |
| 7.1 | Scope-chain identifier resolution | — | 7.3, 7.4, 7.5 |
| 7.2 | Open Object↔Object comparison | 7.0 | 7.5, 7.6, TS2322/TS2344/TS2416 |
| 7.3 | Variable initializer inference | 7.1 | 7.4, contextual typing |
| 7.4 | Property access chain typing | 7.1, 7.3 | TS2339, method call typing |
| 7.5 | Overload resolution | 7.2, 7.1+7.3 | TS2769/TS2554, stdlib typing |
| 7.6 | Indexed access types (T[K]) | 7.2 | 7.8, 7.9, utility types |
| 7.7 | Control flow narrowing | — | Union→Object safety, discriminated unions |
| 7.8 | Conditional types | 7.6, 7.2 | Extract, Exclude, ReturnType |
| 7.9 | Mapped types | 7.6 | Partial, Required, Record |

**Parallel tracks:** 7.0 + 7.1 can run in parallel (independent). 7.7 is independent
throughout. All other items must follow the dependency chain.

---

## Phase 8 — Harvest Test Gains from Infrastructure

**Status:** 7,981 / 10,077 tests passing (79.2%). 2,096 failing.

**Strategy:** Phase 7 built all foundational type system infrastructure (cycle detection,
structural comparison, overloads, indexed access, conditional/mapped types, narrowing).
The infrastructure is sound but **under-activated** — overly conservative guards prevent
valid comparisons, and type checkers only activate for certain type categories. Phase 8
relaxes guards and activates features to harvest test gains.

**Estimated total gain:** ~300-425 tests (bringing total to ~82-84%).

### QUEUE

- [x] **8.0. Expand TS2339 to check property access on all Object types**

  **Problem:** `checkSinglePropertyAccess` only fires for `Type.Interface` identifiers.
  `Type.Object` (anonymous object literals, function types, type literals) is skipped
  entirely — ~123 "none produced" failures need TS2339 on non-interface object types.

  **Implementation:**
  - In `checkSinglePropertyAccess`: extend the object type gate to include `Type.Object`
    (not just `Type.Interface`)
  - For `this.prop` in object literals: check against the object literal's own type
  - Guard: skip Type.Object with no resolved members (empty/unresolved types)
  - Guard: skip when `getTypeOfExpression` returns `anyType` for the base

  **Estimated gain:** ~40-80 tests (conservative — many need multiple fixes)
  **File:** `Checker.kt` — `checkSinglePropertyAccess`, `checkPropertyAccess`

- [x] **8.1. Relax canUseTypeEngine guards incrementally** (8.1a+b done, 8.1c+d cause regressions — deferred)

  **Problem:** `canUseTypeEngine` has 6 blocking conditions added as FP guards during
  Phase 5-7. With Phase 7's cycle detection and structural comparison in place, several
  guards are now overly conservative:

  **Sub-items (test each independently):**

  **8.1a.** Allow empty source objects (`{}`): Currently blocked because `{}` is assignable
  to most types in TypeScript. But `{}` assigned to a type with required properties SHOULD
  fail. Fix: only skip when target also has no required properties.

  **8.1b.** Allow array element comparison: Currently `isArrayLikeType` blocks all array
  comparisons. Fix: allow when both sides are arrays or when comparing array to non-array
  (always fails). Skip only array→tuple (needs contextual typing).

  **8.1c.** Allow interface→interface where target extends source: Currently blocked for
  "narrowing scenarios." Fix: only block in if-then/switch-case contexts, allow in
  variable declarations and return statements.

  **8.1d.** Allow Union source → Object target when all union constituents are concrete
  (no anyType members).

  **Estimated gain:** ~50-100 tests
  **File:** `Checker.kt` — `canUseTypeEngine`

- [x] **8.2. Expand isSimpleCheckableType for TS2345 argument checking** (safety guard only — Object types cause FPs without control flow narrowing)

  **Problem:** `isSimpleCheckableType` rejects unions containing non-primitive types.
  With Object↔Object comparison now working, union arguments containing object types
  can be safely checked.

  **Implementation:**
  - Allow unions where all constituents are either primitive or resolved Object types
  - Allow function types (Object with call signatures) as checkable
  - Guard: skip unions containing `anyType` or `errorType` constituents

  **Estimated gain:** ~30-60 tests
  **File:** `Checker.kt` — `isSimpleCheckableType`

- [x] **8.3. Propagate parameter types to all checker passes** (infrastructure — no direct test gains)

  **Problem:** `currentLocalTypes` is only populated during the TS2322 walk.
  TS2339/TS2345 checker passes don't have access to function parameter types, so
  `getTypeOfIdentifier` returns `anyType` for parameters in those contexts.

  **Implementation:**
  - In `checkPropertyAccessInStatement` (TS2339): when entering a function/method body,
    save/restore `currentLocalTypes` and populate with parameter types (same pattern as
    `checkFunctionBody` in the TS2322 walk)
  - In `checkCallTypesInStatement` (TS2345): same treatment
  - Share the parameter type population logic in a helper function

  **Estimated gain:** ~20-40 tests
  **File:** `Checker.kt` — `checkPropertyAccessInStatement`, `checkCallTypesInStatement`

- [x] **8.4. Implement binary operator type checking (TS2365/TS2362/TS2363)** (implemented — no direct test gains due to other missing errors in same tests)

  **Problem:** Arithmetic operators (`+`, `-`, `*`, `/`, `%`) don't validate operand types.
  Tests expect TS2365 ("Operator cannot be applied to types"), TS2362 ("Left-hand side
  must be of type 'any', 'number', 'bigint' or an enum type"), TS2363 (right-hand side).

  **Implementation:**
  - In the arithmetic checking pass: for binary expressions with arithmetic operators,
    resolve both operand types via `getTypeOfExpression`
  - Check: both operands must be `number`, `bigint`, `any`, `enum`, or (for `+`) `string`
  - Emit TS2365 when both sides are wrong, TS2362 for left-only, TS2363 for right-only
  - Skip when either side is `anyType` or `errorType`
  - Handle: capital-N `Number` object type is NOT valid for arithmetic (common test pattern)

  **Estimated gain:** ~20-40 tests
  **File:** `Checker.kt` — `checkArithmeticOperandTypes`

- [x] **8.5. Enable TS2322 for more assignment patterns** (partial: fixed duplicate elaboration for element access/type assertions, +2 tests)

  **Problem:** The TS2322 checker only fires in specific AST patterns (variable declarations,
  return statements, assignment expressions). Missing patterns include:
  - Property assignments in object literals: `{ prop: value }` where `value` type doesn't
    match the expected property type from contextual typing
  - Spread assignments: `{ ...obj }` where spread type conflicts
  - Destructuring assignments: `const { a }: T = expr` where expr type mismatches

  **Implementation:**
  - Add contextual type checking in object literal property assignments
  - Handle destructuring pattern type checking
  - Integrate with the existing `checkVarDeclAssignability` infrastructure

  **Estimated gain:** ~20-30 tests
  **File:** `Checker.kt` — `checkTypeAssignabilityInStatements`

- [x] **8.6. Fix typeToString display for complex types** (reviewed — display is already good; remaining diff failures need deeper infrastructure)

  **Problem:** Several "diff" test failures are from incorrect type display in diagnostic
  messages. `typeToString` doesn't handle all type display patterns correctly:
  - Generic types: `Map<string, number>` instead of `Map`
  - Function types: `(x: number) => void` display
  - Intersection types: `A & B` display
  - Qualified names: `Namespace.Type` display

  **Implementation:**
  - Improve `typeToString` to handle Reference types with type arguments
  - Handle intersection display
  - Handle qualified name paths for types from namespaces

  **Estimated gain:** ~10-20 tests (diff tests where diagnostics fire but display wrong)
  **File:** `Checker.kt` — `typeToString`

---

## Phase 9 — Targeted FP Suppression and Emission Fixes

**Status**: 7,983 / 10,077 tests passing (79.2%). 2,094 failing.

**Strategy**: Phase 8 revealed that guard relaxation yields near-zero gains because 56%
of failures produce NO diagnostics (blocked on lib.d.ts / deep type resolution). Phase 9
focuses on the other 44%: suppressing false positives (+22 pure-FP tests), fixing JS emit
regressions (+237 JS tests), and reducing FP rates in mixed-diff tests.

**Failure breakdown**:
- 1,177 (56%) produce zero diagnostics → BLOCKED (need lib.d.ts types)
- 681 (33%) produce wrong diagnostics → FP suppression + checker fixes
- 237 (11%) JS emit failures → emitter/transformer fixes

### QUEUE

- [x] **9.0. Suppress TS6133 FP for indexed property access (LOW)**

  **Problem:** `typeGuardNarrowsIndexedAccessOfKnownProperty9` — TS6133 "declared but
  never read" fires for class properties `a` and `b` that ARE read via indexed access
  (`this[key]`). Our unused-variable checker doesn't track `ElementAccessExpression`
  as a "read" of the accessed property.

  **Fix:** In the TS6133 checker, when scanning for uses of a class member, also check
  `ElementAccessExpression` nodes where the argument matches the property name string.
  
  **Estimated gain:** 1 test
  **File:** `Checker.kt` — unused declaration checker

- [x] **9.1. Fix exhaustive switch fallthrough detection (LOW-MEDIUM)**

  **Problem:** `reachabilityChecks4` — TS7029 fallthrough fires for `case 'SLIDE':` that
  contains a nested switch covering ALL enum values (all cases return). TypeScript recognizes
  exhaustive nested switches as terminating.

  **Fix:** In `clauseStmtsTerminate`, check if the last statement is a `SwitchStatement`
  where ALL clauses terminate AND the switch expression's type is a union/enum with all
  values covered.
  
  **Estimated gain:** 1 test
  **File:** `Checker.kt` — `isDefinitelyTerminating`, `clauseStmtsTerminate`

- [x] **9.2. Add missing KNOWN_GLOBALS for web/test APIs (LOW)**

  **Problem:** FP TS2304 for `importScripts` (web worker API), and FP TS2552 for `$`
  (jQuery), `suite` (test framework). These are well-known globals missing from our list.

  **Fix:** Add to `KNOWN_GLOBALS`: `importScripts`, `$`, `jQuery`, `suite`, `describe`,
  `it`, `expect`, `beforeEach`, `afterEach`, `beforeAll`, `afterAll`, `jest`, `test`,
  `self`, `globalThis`, `queueMicrotask`, `structuredClone`, `atob`, `btoa`, `fetch`,
  `Response`, `Request`, `Headers`, `URL`, `URLSearchParams`, `TextEncoder`, `TextDecoder`,
  `AbortController`, `AbortSignal`, `Blob`, `File`, `FormData`, `MessageChannel`,
  `MessagePort`, `Worker`, `SharedWorker`, `performance`, `navigator`, `location`,
  `console`, `setTimeout`, `clearTimeout`, `setInterval`, `clearInterval`,
  `requestAnimationFrame`, `cancelAnimationFrame`.

  **Estimated gain:** 2-5 tests (reduces FPs in tests with other correct diagnostics)
  **File:** `Checker.kt` — `KNOWN_GLOBALS`

- [x] **9.3. Suppress TS7006 for parameters with contextual types (MEDIUM)**

  **Problem:** 24 tests have FP TS7006 ("Parameter implicitly has 'any' type") for
  parameters that should be contextually typed. 3 pure-FP tests:
  `intraBindingPatternReferences`, `subtypeReductionWithAnyFunctionType`,
  `contextualOverloadListFromUnionWithPrimitiveNoImplicitAny`.

  **Fix:** Before emitting TS7006, check if the parameter's parent function/arrow is
  being assigned to a typed target (variable with type annotation, function parameter,
  return statement). If the target type has call signatures, the parameter's type is
  contextually inferred — suppress TS7006.

  **Estimated gain:** 3 pure-FP tests + reduces FPs in ~21 mixed tests
  **File:** `Checker.kt` — `checkImplicitAnyParameters`

- [x] **9.4. Fix module augmentation export merging for TS2339 (MEDIUM)**

  **Problem:** `moduleAugmentationsImports4` — TS2339 fires for properties exported
  from other files via module augmentation (`declare module "X" { export function y() }`).
  `mergeModuleAugmentations` runs but doesn't fully merge exports into the namespace
  symbol's export table.

  **Fix:** In `mergeModuleAugmentations`, ensure that function/variable declarations
  inside augmentation blocks are added to the target module's exports. Currently only
  handles type-level declarations.

  **Estimated gain:** 1 pure-FP test + 3-5 mixed tests with namespace FP TS2339
  **File:** `Checker.kt` — `mergeModuleAugmentations`

- [x] **9.5. Suppress TS2322 for `as unknown` type assertions (LOW)**

  **Problem:** `privateFieldAssignabilityFromUnknown` has FP TS2322 for `{} as unknown`
  assigned to a class type. The `as unknown` assertion should suppress assignability
  checking — `unknown` is the top type.

  **Fix:** In `canUseTypeEngine` or `checkVarDeclAssignability`, skip the check when
  the source expression is an `AsExpression` with target type `unknown`. TypeScript
  treats `expr as unknown as T` as always-valid (double assertion pattern).

  **Estimated gain:** 1 test (but also has missing TS18028, may need both)
  **File:** `Checker.kt` — `checkVarDeclAssignability`

- [x] **9.6. Fix TS2322 FP for object literal → named interface comparison (MEDIUM)**

  **Problem:** 33 tests have FP TS2322 where an object literal `{ a: 1, b: "x" }` is
  compared to a named interface `Foo` and incorrectly fails. The structural comparison
  resolves the object literal's properties but can't resolve the named interface's
  members (returns anyType for the interface).

  **Fix:** In `objectTypeRelatedTo`, when the target is a named Interface, try resolving
  its members via `resolveStructuredTypeMembers`. If the target has resolved properties,
  do member-by-member comparison. Guard: skip when target has unresolved type parameters
  or base types from imports.

  **Estimated gain:** 5-15 tests (reduces FPs in mixed tests)
  **File:** `Checker.kt` — `objectTypeRelatedTo`, `canUseTypeEngine`

- [x] **9.7. Fix JS emit for CommonJS require/exports patterns (MEDIUM-HIGH)**

  **Problem:** 40 JS emit tests fail on CommonJS patterns. Top issues:
  - Extra `Object.defineProperty(exports, "__esModule", { value: true })` when not needed
  - Missing `exports.X = X` statements
  - Wrong `require()` call patterns for re-exports

  **Fix:** Audit `transformToCommonJS` for these patterns. Key: only emit `__esModule`
  when the file has ES module syntax (import/export). Ensure `exports.X` is emitted
  for all exported declarations.

  **Estimated gain:** 10-20 tests
  **File:** `Transformer.kt` — `transformToCommonJS`

- [x] **9.8. Fix JS emit for import/export helper ordering (MEDIUM)**

  **Problem:** 20 JS emit tests fail on import/export helper function ordering.
  `__importStar`, `__importDefault`, `__exportStar` helpers are emitted in wrong order
  or with wrong conditional checks.

  **Fix:** Review helper emission order against TypeScript's output. Ensure helpers
  appear before their first usage. Fix `esModuleInterop` conditional checks.

  **Estimated gain:** 5-10 tests
  **File:** `Transformer.kt` — helper emission

- [x] **9.9. Fix JS emit for class member transforms (MEDIUM)**

  **Problem:** 14 JS emit tests fail on class member transformations:
  - Static class blocks not transformed
  - Class field initializers in wrong position
  - Missing `#private` field downlevel transforms

  **Fix:** Audit class transform output against TypeScript baselines for these patterns.

  **Estimated gain:** 5-8 tests
  **File:** `Transformer.kt` — class transforms

- [x] **9.10. Reduce TS2322 FPs from function type comparison (MEDIUM)**

  **Problem:** 22 tests have FP TS2322 from incorrect function type comparison.
  Common pattern: `(a: string) => void` reported as not assignable to
  `(a: string) => void` (identical types). The issue is that function parameter
  names or optional modifiers differ in the comparison.

  **Fix:** In function type comparison (`signatureRelatedTo`), check parameter types
  only, not parameter names. Handle optional parameters: a function with fewer
  required params is assignable to one with more optional params.

  **Estimated gain:** 3-8 tests
  **File:** `Checker.kt` — `signatureRelatedTo`

---

## Phase 10 queue — High-ROI Targeted Fixes

**Failure landscape (2,091 remaining):**
- 1,185 (56.7%) produce zero diagnostics — blocked on lib.d.ts/anyType resolution
- 312 (14.9%) partial match — some correct diagnostics, missing others
- 334 (16.0%) mixed — both extra and missing diagnostics
- 236 (11.3%) JS emit failures — module transforms, ordering, private fields
- 12 (0.6%) pure FP — only extra diagnostics
- ~12 position/message diffs, ~119 source echo ordering, ~128 other format

**Strategy:** Target tests fixable without deep type system infrastructure.
Focus on (a) test output formatting, (b) small targeted diagnostics, (c) JS emit ordering.

- [x] **10.0. Fix multi-file error baseline source echo ordering (HIGH)** — DONE (+12 tests, 7998 passing)

  **Problem:** 10 error tests fail ONLY because source file sections appear in
  wrong order. Content and diagnostics are identical — just `==== file.ts ====`
  sections reordered. Tests: `moduleResolutionPackageIdWithRelativeAndAbsolutePath`,
  `moduleResolutionWithExtensions_withPaths`, `moduleResolutionWithSuffixes_one_*` (4),
  `pathMappingBasedModuleResolution7_classic`, `pathMappingBasedModuleResolution7_node`,
  `pathMappingBasedModuleResolution_withExtension_MapedToNodeModules`,
  `requireOfJsonFile_PathMapping`.

  **Fix:** In `toErrorBaseline()` in `BaselineFormatter.kt`, sort `allSourceFiles`
  so user source `.ts` files (non-node_modules, non-library) appear before dependency
  files (`.d.ts`, `node_modules/**`). The existing "last to front" reordering for
  `require()`/`reference path` files is correct but insufficient — these 10 tests
  need a broader sort: user sources first, then library/node_modules `.d.ts` files.

  **Estimated gain:** 10 tests
  **File:** `BaselineFormatter.kt` — `toErrorBaseline`

- [x] **10.1. Fix TS2366 FP for exhaustive typeof switch on any/unknown (LOW)** — DONE (+2 tests, 8000 passing)

  **Problem:** `unreachableSwitchTypeofAny` and `unreachableSwitchTypeofUnknown` have
  FP TS2366 ("Function lacks ending return statement") for functions with switch on
  `typeof x` covering all possible typeof string values. TypeScript recognizes these
  as exhaustive.

  **Fix:** In the TS7030/TS2355/TS2366 implicit return checker, recognize a switch on
  `typeof expr` as exhaustive when all typeof string values are covered by case clauses
  ("string", "number", "bigint", "boolean", "symbol", "undefined", "object", "function").

  **Estimated gain:** 2 tests
  **File:** `Checker.kt` — `isDefinitelyTerminating` or `checkImplicitReturns`

- [ ] **10.2. Fix parser TS1109→TS1005 for unparsed token (LOW)** — SKIPPED (parseSemicolon error reporting causes 7 regressions)

  **Problem:** `parserUnparsedTokenCrash1` emits TS1109 ("Expression expected") where
  TypeScript emits TS1005 ("';' expected"). Parser error recovery picks different
  fallback diagnostic.

  **Fix:** Check the specific parse context where the divergence occurs and adjust
  to match TypeScript's error recovery.

  **Estimated gain:** 1 test
  **File:** `Parser.kt`

- [ ] **10.3. Fix TS7019→TS7006 for contextually typed rest param (LOW)** — SKIPPED (needs advanced contextual typing for both FP suppression and missing diagnostic)

  **Problem:** `contextuallyTypedParametersWithInitializers1` emits TS7019 (rest param
  implicit any[]) for a parameter that should get TS7006 (regular param implicit any).

  **Fix:** Check the specific parameter and adjust the diagnostic code selection logic.

  **Estimated gain:** 1 test
  **File:** `Checker.kt` — `checkParamsForImplicitAny`

- [ ] **10.4. Investigate and fix multi-file JS emit ordering (HIGH)** — INVESTIGATED: topo sort needed for most tests; naive removal causes 59 regressions

  **Problem:** ~36 JS emit tests fail because output file sections appear in wrong
  order. The emitted JS content is correct but file sections are reordered vs baseline.
  This is distinct from the error baseline ordering issue.

  **Fix:** Investigate the compilation ordering in `TypeScriptCompiler.kt` for multi-file
  tests. TypeScript processes files in a specific dependency order. Determine if this is
  a test harness issue (formatMultiFileBaseline) or compiler issue (file processing order).

  **Estimated gain:** 10-36 tests
  **File:** `TypeScriptCompiler.kt`, `BaselineFormatter.kt`

- [x] **10.5. Fix type-only import elision — top patterns (MEDIUM-HIGH)** — DONE (+2 tests, 8003 passing)

  **Problem:** ~25-35 JS emit tests fail because type-only imports/exports are not
  properly elided. Common patterns: `require("./type")` emitted for type-only imports,
  `exports.default = type_1.T` for type re-exports, extra imports inflating `_1`/`_2`
  suffix numbering.

  **Fix:** Two changes: (1) Extended `isTypeOnlyImportRequire` check to also apply for
  exported `import = require()` (removed `!isExported` guard). (2) Added ambient external
  module resolution — when file-based resolution fails, search all script-mode files for
  `declare module "X"` blocks and check if their exports are type-only. Distinguishes
  module definitions (in script files) from module augmentations (in module files).

  **Actual gain:** 2 tests (aliasOnMergedModuleInterface, exportImportNonInstantiatedModule2)
  **Files:** `Checker.kt` — `isTypeOnlyImportRequire`, `isAmbientModuleTypeOnly`; `Transformer.kt` — guard removal

- [x] **10.6. Add TS1042 for modifiers on object literal members (LOW)** — DONE (+1 test, 8000 passing)

  **Problem:** `objectLiteralMemberWithModifiers2` expects TS1042 ("'public' modifier
  cannot be used here") for access modifiers on object literal properties. Our parser
  doesn't emit this diagnostic.

  **Fix:** In the parser or checker, detect access modifiers (public/private/protected)
  on object literal property/method declarations and emit TS1042.

  **Estimated gain:** 1-2 tests
  **File:** `Checker.kt` or `Parser.kt`

- [ ] **10.7. Fix this-parameter display in function typeToString (LOW)** — SKIPPED (test has many deeper type resolution issues beyond this)

  **Problem:** `contextualTyping24` shows function type without `this: void` parameter
  in the display. TypeScript includes `this` parameter in function type display when
  present.

  **Fix:** In `typeToString`, when emitting function types with a `this` parameter,
  include it in the display.

  **Estimated gain:** 1-2 tests
  **File:** `Checker.kt` — `typeToString`

- [x] **10.8. Add TS2708 "Cannot use namespace as value" (LOW-MEDIUM)** — DONE (+1 test, 8001 passing)

  **Problem:** Several tests expect TS2708 when a namespace/module is used in a value
  position (e.g., `let x = MyNamespace`). We don't emit this diagnostic.

  **Fix:** In the checker, when an identifier resolves to a namespace-only symbol
  (Module flag without Value flag) in a value position, emit TS2708.

  **Estimated gain:** 2-4 tests
  **File:** `Checker.kt`

- [x] **10.9. Fix private field WeakMap downlevel — basic pattern (MEDIUM-HIGH)** — DONE (+1 test, 8004 passing)

  **Problem:** ~18 JS emit tests expect `#field` to be downleveled to WeakMap pattern
  (`_ClassName_field = new WeakMap()`, `__classPrivateFieldGet/Set`). Our Transformer
  emits native `#field` syntax regardless of target.

  **Fix:** In the Transformer, when target < ES2022, transform `#field` declarations
  to WeakMap pattern: `var _ClassName_field;` before class, `_ClassName_field.set(this, ...)`
  in constructor, `_ClassName_field = new WeakMap();` after class. Private field read/write
  transforms (`__classPrivateFieldGet/Set`) not yet implemented — needed for more tests.

  **Actual gain:** 1 test (privateFieldAssignabilityFromUnknown)
  **File:** `Transformer.kt` — `transformClassBody`

- [ ] **10.10. Fix computed property Symbol() temp variable emission (LOW-MEDIUM)**

  **Problem:** ~15 JS emit tests expect computed property names using `Symbol()` to be
  extracted to temp variables (`var _a; _a = Symbol("key")`). Our emitter outputs the
  `Symbol()` call inline.

  **Fix:** In the Transformer, when a computed property name is a `Symbol()` call or
  other non-trivial expression, extract to a temp variable declared before the class.

  **Estimated gain:** 5-10 tests
  **File:** `Transformer.kt` — class transforms

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
