# TypeScript Type Checker Architecture — Research & Design Document

**Purpose:** Comprehensive analysis of TypeScript's type checker architecture to guide the Kotlin
implementation (Phase 2). This document covers the original TypeScript design, maps it to the
existing Kotlin codebase, and defines the MVP scope.

---

## Table of Contents

1. [Pipeline Overview](#1-pipeline-overview)
2. [Binder Architecture](#2-binder-architecture)
3. [Symbol System](#3-symbol-system)
4. [Type System](#4-type-system)
5. [Checker Architecture](#5-checker-architecture)
6. [Checker-to-Transformer Communication](#6-checker-to-transformer-communication)
7. [MVP Scope for This Project](#7-mvp-scope-for-this-project)
8. [Kotlin Implementation Design](#8-kotlin-implementation-design)

---

## 1. Pipeline Overview

### TypeScript's Pipeline

```
Source → Scanner → Parser → Binder → Checker → Transformer → Emitter
```

Each stage treats previous stages' output as **immutable**. When later stages need to annotate
earlier data structures, they use **look-aside tables** (e.g., the checker's merged-symbols table,
the NodeLinks map).

### Current Kotlin Pipeline

```
Source → Scanner → Parser → Transformer → Emitter
```

Phase 2 inserts the Binder and Checker:

```
Source → Scanner → Parser → Binder → Checker → Transformer → Emitter
```

### Key Design Principle: Immutability

TypeScript's AST nodes are mutable (the binder sets `node.symbol`, `node.locals`, `node.flowNode`
directly). Our Kotlin AST uses immutable `data class` instances. We have two options:

1. **Look-aside maps** — keep AST immutable, store symbol/type info in external maps keyed by
   node identity (pos/end pairs or a unique node ID).
2. **Add optional mutable fields** — add `var symbol: Symbol?` to the `Node` interface.

**Recommendation: Look-aside maps.** This preserves the existing immutable data class architecture,
avoids breaking the `copy()` pattern used extensively in the Transformer, and aligns with
TypeScript's own principle that later phases use look-aside tables. We will use a `BinderResult`
object containing `nodeToSymbol: Map<NodeKey, Symbol>` and `scopeChains`.

---

## 2. Binder Architecture

### 2.1 What the Binder Does

The binder makes a single pass over the AST and:

1. **Creates symbols** for every named declaration (variable, function, class, interface, enum,
   namespace, type alias, parameter, property, import, export).
2. **Builds scope chains** — each scope has a `SymbolTable` (name-to-symbol map). Scopes nest:
   global/module → namespace → function → block.
3. **Handles hoisting** — `var` declarations are hoisted to function/module scope; `let`/`const`
   are block-scoped. Function declarations are bound before other statements.
4. **Handles declaration merging** — multiple declarations can contribute to the same symbol
   (e.g., two `interface Foo` declarations merge).
5. **Builds the control flow graph** (CFG) for type narrowing. **NOT needed for MVP.**
6. **Computes module instance state** — whether a namespace/module contains runtime code
   or only type declarations (crucial for import elision).

### 2.2 AST Traversal

TypeScript's binder uses two core functions:

```
bind(node):
    setParent(node, parent)
    saveParent = parent
    parent = node
    bindWorker(node)     // declare this node's symbol
    bindChildren(node)   // recurse into children
    parent = saveParent
```

`bindWorker` dispatches by `node.kind` to determine what symbol to create:
- `VariableDeclaration` → create symbol with `FunctionScopedVariable` or `BlockScopedVariable`
- `FunctionDeclaration` → create symbol with `Function` flag
- `ClassDeclaration` → create symbol with `Class` flag + initialize `members` table
- `InterfaceDeclaration` → create symbol with `Interface` flag + initialize `members` table
- `EnumDeclaration` → create symbol with `Enum` flag + initialize `exports` table
- `ModuleDeclaration` → create symbol with `Module` flag + initialize `exports` table
- `ImportDeclaration` / `ImportEqualsDeclaration` → create symbol with `Alias` flag
- `Parameter` → create symbol with `FunctionScopedVariable` flag

`bindChildren` recurses into child nodes. For control flow statements (if, while, for, try), it
creates CFG nodes. For simple containers (blocks, functions, classes), it just recurses.

### 2.3 Container Management

Three scope pointers track the current context:

| Pointer | What it tracks | Updated when entering |
|---------|----------------|----------------------|
| `container` | Where `locals`/`exports` live | Functions, classes, namespaces, source files |
| `blockScopeContainer` | Where `let`/`const` live | Blocks, for-loops, catch clauses, switch cases |
| `parent` | Immediate parent node | Every node |

When entering a container (function, class, namespace), the binder:
1. Saves the current container
2. Sets the new container
3. Initializes `container.locals = new SymbolTable()`
4. Binds children
5. Restores the saved container

### 2.4 Symbol Tables on Nodes

In TypeScript, symbols are stored in three locations on container nodes:

| Location | Purpose | Examples |
|----------|---------|---------|
| `node.locals` | Block/function-scoped declarations | `let x`, `function f()` inside a block |
| `node.exports` | Module/namespace exports | `export class C`, top-level in module |
| `node.members` | Type members | Class properties, interface methods |

For our Kotlin implementation, these become maps in `BinderResult` keyed by the container node.

### 2.5 Hoisting

**Function declaration hoisting:** The binder processes function declarations before other
statements in a block/source file:

```
bindEachFunctionsFirst(nodes):
    bindEach(nodes, n => if n is FunctionDeclaration then bind(n))  // functions first
    bindEach(nodes, n => if n is not FunctionDeclaration then bind(n))  // everything else
```

**var hoisting:** `var` declarations are added to the nearest function/module scope's `locals`
table, not the block scope. The binder achieves this by walking up to the `container` (not
`blockScopeContainer`) when declaring a `FunctionScopedVariable`.

**let/const:** Added to the `blockScopeContainer`'s `locals` table.

### 2.6 Declaration Merging

The binder uses an **includes/excludes flag system** for merging:

```
declareSymbol(symbolTable, parent, node, includes, excludes):
    existing = symbolTable.get(name)
    if existing is null:
        create new symbol, add to table
    else if existing.flags & excludes != 0:
        error: conflicting declarations
    else:
        merge: existing.flags |= includes
        existing.declarations.add(node)
```

**Allowed merges (includes/excludes compatibility):**

| Declaration A | Declaration B | Result |
|---------------|---------------|--------|
| Interface | Interface | Merged interface (combined members) |
| Namespace | Namespace | Merged namespace (combined exports) |
| Class | Namespace | Class with static extensions |
| Function | Namespace | Function with properties |
| Enum | Namespace | Enum with additional members |
| Enum | Enum (same const-ness) | Merged enum |
| Variable (var) | Variable (var) | Same symbol |

**Disallowed merges (conflicts):**

| Declaration A | Declaration B | Error |
|---------------|---------------|-------|
| Class | Class | Duplicate identifier |
| Class | Function | Duplicate identifier |
| let/const | let/const | Duplicate identifier |
| Variable | Class | Duplicate identifier |

### 2.7 Module Instance State

The binder computes whether a module/namespace is "instantiated" (produces runtime code):

```
enum ModuleInstanceState:
    NonInstantiated = 0    // only types, const enums, non-export imports
    Instantiated = 1       // contains runtime code
    ConstEnumOnly = 2      // only const enums (no runtime if not preserveConstEnums)
```

This is **critical for import elision**: an import of a non-instantiated module can be removed.
The algorithm walks the module body recursively:

```
getModuleInstanceState(module):
    if module.body is ModuleBlock:
        for each statement in body.statements:
            state = getModuleInstanceStateForStatement(statement)
            if state is Instantiated: return Instantiated
        return NonInstantiated or ConstEnumOnly
    if module.body is ModuleDeclaration:
        return getModuleInstanceState(module.body)  // nested namespace
    return Instantiated  // no body means ambient, treated as instantiated
```

Statement classification:
- `InterfaceDeclaration`, `TypeAliasDeclaration` → NonInstantiated
- `ImportDeclaration` (without export) → NonInstantiated
- `ExportDeclaration` (type-only) → NonInstantiated
- `EnumDeclaration` (const, without preserveConstEnums) → ConstEnumOnly
- `ModuleDeclaration` → recursive check
- Everything else → Instantiated

**Note:** The Kotlin Transformer already has `isTypeOnlyNamespace()` and `isTypeOnlyStatement()`
which are essentially a simplified version of this computation. The binder's version needs to be
more precise and per-symbol.

---

## 3. Symbol System

### 3.1 Symbol Interface (TypeScript)

```typescript
interface Symbol {
    flags: SymbolFlags;           // Bit flags indicating what kind of symbol this is
    escapedName: __String;        // The symbol's name (escaped for internal use)
    declarations?: Declaration[]; // All declaration AST nodes for this symbol
    valueDeclaration?: Declaration; // The "primary" value declaration
    members?: SymbolTable;        // Members (class/interface member symbols)
    exports?: SymbolTable;        // Exports (module/namespace exported symbols)
    id?: number;                  // Unique identifier
    parent?: Symbol;              // Parent scope's symbol
    // ... additional fields added by SymbolLinks (checker-created data)
}
```

### 3.2 SymbolFlags

The most important flags for Phase 2 MVP:

```
SymbolFlags:
    FunctionScopedVariable  = 1 << 0    // var, parameter
    BlockScopedVariable     = 1 << 1    // let, const
    Property                = 1 << 2    // class property, object literal property
    EnumMember              = 1 << 3    // enum member
    Function                = 1 << 4    // function declaration
    Class                   = 1 << 5    // class declaration
    Interface               = 1 << 6    // interface declaration
    ConstEnum               = 1 << 7    // const enum
    RegularEnum             = 1 << 8    // regular (non-const) enum
    ValueModule             = 1 << 9    // namespace with runtime code
    NamespaceModule         = 1 << 10   // namespace (type-only module)
    TypeLiteral             = 1 << 11   // type literal
    ObjectLiteral           = 1 << 12   // object literal
    Method                  = 1 << 13   // method
    Constructor             = 1 << 14   // constructor
    GetAccessor             = 1 << 15   // get accessor
    SetAccessor             = 1 << 16   // set accessor
    Signature               = 1 << 17   // call/construct/index signature
    TypeParameter           = 1 << 18   // type parameter
    TypeAlias               = 1 << 19   // type alias
    ExportValue             = 1 << 20   // exported value
    Alias                   = 1 << 21   // import alias
    Prototype               = 1 << 22   // prototype property
    ExportStar              = 1 << 23   // export * from "..."
    Optional                = 1 << 24   // optional property

    // Composite flags:
    Value = Variable | Property | EnumMember | Function | Class | Enum | ValueModule | Method | GetAccessor | SetAccessor
    Type = Class | Interface | Enum | TypeLiteral | TypeParameter | TypeAlias
    Namespace = ValueModule | NamespaceModule | Enum
    Module = ValueModule | NamespaceModule
    Enum = RegularEnum | ConstEnum
    Variable = FunctionScopedVariable | BlockScopedVariable
```

### 3.3 SymbolTable

A `SymbolTable` is simply `Map<string, Symbol>`. In Kotlin: `MutableMap<String, Symbol>`.

Each scope container maintains its own symbol table. The binder creates them; the checker reads
them and may create additional "merged" tables.

### 3.4 Transient Symbols

Transient symbols are created by the **checker** (not the binder). They handle:

1. **Cross-file declaration merging** — when the same name is declared in multiple files, the
   checker creates a merged symbol combining all declarations.
2. **Synthetic properties** — created by mapped types, spreads, etc.
3. **Resolved aliases** — when following an import chain to its target.

For MVP, transient symbols for cross-file enum merging will be needed.

### 3.5 Symbol-to-Declaration Relationship

- A symbol has a `declarations: List<Declaration>` — all AST nodes that declare it.
- A symbol has a `valueDeclaration: Declaration?` — the primary value-bearing declaration
  (for a merged interface+class, the class is the value declaration).
- The binder stores a reverse mapping: node → symbol (in TypeScript via `node.symbol`).

---

## 4. Type System

### 4.1 Type vs TypeNode

**Critical distinction:**

| Concept | File | Purpose |
|---------|------|---------|
| `TypeNode` | `Ast.kt` | Syntax — parsed type annotations in source code |
| `Type` | New `Types.kt` | Semantics — resolved runtime type representations |

The parser creates `TypeNode` instances. The checker resolves them into `Type` instances.
Multiple `TypeNode`s can resolve to the same `Type` (e.g., `string` appearing in two places
resolves to the singleton `stringType`).

### 4.2 TypeFlags (TypeScript)

```
TypeFlags:
    Any             = 1 << 0
    Unknown         = 1 << 1
    String          = 1 << 2
    Number          = 1 << 3
    Boolean         = 1 << 4
    Enum            = 1 << 5
    BigInt          = 1 << 6
    StringLiteral   = 1 << 7
    NumberLiteral   = 1 << 8
    BooleanLiteral  = 1 << 9
    EnumLiteral     = 1 << 10
    BigIntLiteral   = 1 << 11
    ESSymbol        = 1 << 12
    UniqueESSymbol  = 1 << 13
    Void            = 1 << 14
    Undefined       = 1 << 15
    Null            = 1 << 16
    Never           = 1 << 17
    TypeParameter   = 1 << 18
    Object          = 1 << 19
    Union           = 1 << 20
    Intersection    = 1 << 21
    Index           = 1 << 22
    IndexedAccess   = 1 << 23
    Conditional     = 1 << 24
    Substitution    = 1 << 25
    NonPrimitive    = 1 << 26    // object (lowercase)
    TemplateLiteral = 1 << 27
    StringMapping   = 1 << 28
```

### 4.3 Type Hierarchy (TypeScript)

```
Type (base: flags, id, symbol)
  ├─ IntrinsicType          // any, unknown, string, number, boolean, void, undefined, null, never
  ├─ LiteralType            // "hello", 42, true
  │    ├─ StringLiteralType
  │    ├─ NumberLiteralType
  │    └─ BigIntLiteralType
  ├─ ObjectType             // { members, properties, callSignatures, ... }
  │    ├─ InterfaceType      // named types (classes, interfaces)
  │    │    └─ TypeReference  // generic instantiation: Array<string>
  │    ├─ AnonymousType      // object literals, function types
  │    └─ MappedType
  ├─ UnionOrIntersectionType
  │    ├─ UnionType          // A | B
  │    └─ IntersectionType   // A & B
  ├─ TypeParameter           // T in <T>
  ├─ IndexType               // keyof T
  ├─ IndexedAccessType       // T[K]
  ├─ ConditionalType         // T extends U ? X : Y
  ├─ SubstitutionType        // internal: type parameter replaced during instantiation
  └─ TemplateLiteralType     // `hello${string}`
```

### 4.4 Intrinsic Types

TypeScript maintains pre-created singleton instances for primitive types:

```typescript
const anyType = createIntrinsicType(TypeFlags.Any, "any");
const unknownType = createIntrinsicType(TypeFlags.Unknown, "unknown");
const stringType = createIntrinsicType(TypeFlags.String, "string");
const numberType = createIntrinsicType(TypeFlags.Number, "number");
const booleanType = createIntrinsicType(TypeFlags.Boolean, "boolean");
const voidType = createIntrinsicType(TypeFlags.Void, "void");
const undefinedType = createIntrinsicType(TypeFlags.Undefined, "undefined");
const nullType = createIntrinsicType(TypeFlags.Null, "null");
const neverType = createIntrinsicType(TypeFlags.Never, "never");
const bigintType = createIntrinsicType(TypeFlags.BigInt, "bigint");
const symbolType = createIntrinsicType(TypeFlags.ESSymbol, "symbol");
```

### 4.5 Type Resolution from TypeNodes

The checker's `getTypeFromTypeNode(node)` maps syntax to semantics:

| TypeNode kind | Resolution |
|---------------|------------|
| `KeywordTypeNode(StringKeyword)` | → `stringType` singleton |
| `KeywordTypeNode(NumberKeyword)` | → `numberType` singleton |
| `TypeReference("Foo")` | → look up `Foo` in scope, resolve to its type |
| `TypeReference("Array", [T])` | → instantiate Array generic with T |
| `UnionType([A, B])` | → create `UnionType` from resolved A, B |
| `LiteralType(42)` | → create `NumberLiteralType(42)` |
| `FunctionType(params, ret)` | → create anonymous `ObjectType` with call signature |

### 4.6 Generic Type Instantiation

When a generic type reference like `Array<string>` is encountered:

1. Resolve `Array` to its symbol → get the `InterfaceType` with type parameters `[T]`
2. Resolve `string` to `stringType`
3. Create a `TypeReference` that links the interface to the type arguments `[stringType]`
4. Cache the instantiation so `Array<string>` always returns the same `TypeReference`

---

## 5. Checker Architecture

### 5.1 Initialization

The checker is created lazily by `Program.getTypeChecker()`:

```
createTypeChecker(program):
    // For each source file:
    //   1. Call bindSourceFile(file) — runs the binder
    //   2. Call mergeSymbolTable(globals, file.locals) — merge file-level symbols

    // Return a TypeChecker object with methods:
    //   - getSymbolAtLocation(node)
    //   - getTypeAtLocation(node)
    //   - getEmitResolver(file)
    //   - getDiagnostics(file)
```

### 5.2 Lazy Evaluation

The checker computes types **on demand**. When `getTypeOfSymbol(symbol)` is called:

1. Check if the symbol already has a cached type → return it
2. Otherwise, compute the type:
   - For variables: look at the type annotation, or infer from the initializer
   - For functions: build a function type from parameters and return type
   - For classes: build an object type with all members
   - For enums: build an enum type with all members and their values
3. Cache the result on the symbol (via SymbolLinks)

### 5.3 Const Enum Value Resolution

The checker computes constant values for enum members:

```
getEnumMemberValue(member):
    if member has initializer:
        evaluate(initializer)  // may reference other enum members
    else:
        previous member value + 1, or 0 for first member

evaluate(expr):
    if expr is NumericLiteral: return its value
    if expr is StringLiteral: return its value
    if expr is PropertyAccessExpression on enum: return that member's value (recursive)
    if expr is BinaryExpression: evaluate both sides, apply operator
    if expr is UnaryExpression: evaluate operand, apply operator
    // etc.
```

**Important:** The existing Kotlin Transformer already does this computation in
`evaluateConstantExpression()` and `evaluateConstantStringExpression()` within `transformEnum()`.
However, it can only resolve references within the **same** enum declaration. Cross-file and
cross-enum references require the binder/checker.

### 5.4 Import Elision (isReferencedAliasDeclaration)

This is the most impactful checker feature for Phase 2. The algorithm:

```
isReferencedAliasDeclaration(node):
    // An import/export alias is "referenced" if it's used in a value position
    // (not just in type annotations)

    symbol = getSymbolOfNode(node)
    if symbol has been referenced in a value position:
        return true
    if symbol is only referenced in type positions:
        return false

    // The checker tracks this by examining every Identifier in the program:
    // - If identifier resolves to an alias symbol AND is in a value position → mark as referenced
    // - Type positions: type annotations, implements clauses, type-only imports/exports
    // - Value positions: expressions, extends clauses, export assignments, decorators
```

**How TypeScript actually tracks this:**

During type checking, whenever the checker resolves an identifier to a symbol:
1. If the symbol is an alias (import), check if the reference is in a value position
2. If so, mark the alias as "referenced" (set a flag)
3. Later, `isReferencedAliasDeclaration` just checks that flag

**Simpler approach (Babel-style):**

Babel uses a different strategy that doesn't require a full type checker:
1. For each import binding, collect all references via scope analysis
2. Check if ANY reference is NOT in a type-only position (using `isInType()`)
3. If all references are type-only → elide the import

The Kotlin Transformer **already has** a similar mechanism: `collectValueReferences()` walks
the transformed AST to find all identifiers used in value positions. The current limitation is
that it operates on the **post-transform** AST, so it can't distinguish between:
- An identifier used as a value (keep the import)
- An identifier that appeared in a type annotation (was already erased by the transformer)

**The gap:** The current `collectValueReferences()` approach works for most cases but fails when:
1. An import is used ONLY in type positions in the original source — the transformer erases
   the type annotations, and the import name never appears in the transformed code, so it's
   correctly elided. **This already works.**
2. An import is used in value positions — the name appears in the transformed code, so the
   import is kept. **This already works.**
3. An import is used in both type and value positions — the name appears in the transformed
   code, so the import is kept. **This already works.**
4. An import-equals declaration (`import x = M.N`) where `x` is used in value positions —
   currently only type-only import-equals are elided. **Needs checker for full coverage.**
5. An import of a non-instantiated module (only types/const enums) — even if the import name
   appears in value positions (like `Foo.Bar`), the import should be elided because `Foo`
   has no runtime value. **Needs module instance state computation.**
6. Const enum member references across files — `import { E } from "./e"; E.A` should be
   replaced with the literal value and the import elided. **Needs cross-file const enum resolution.**

### 5.5 Structural Subtyping

TypeScript uses structural typing for assignability checking:

```
isTypeAssignableTo(source, target):
    // Check relation cache first
    if cached(source, target): return cached result

    // Special cases: any, unknown, never
    if target is any/unknown: return true
    if source is never: return true
    if source is any: return true (with errors in strict mode)

    // Primitive identity
    if source and target are same primitive: return true

    // Object structural comparison
    for each property in target:
        if source doesn't have a compatible property: return false
    for each call signature in target:
        if source doesn't have a compatible signature: return false

    return true
```

**NOT needed for MVP.** Structural subtyping is only needed for diagnostic messages, not for
emit transforms.

### 5.6 Type Narrowing

Narrowing uses the control flow graph (built by the binder) to determine the type of a variable
at a specific point in the code:

```
getFlowTypeOfReference(reference, initialType):
    // Walk backwards through the CFG from the reference point
    // At each flow node:
    //   - Assignment: update the type
    //   - Condition (typeof x === "string"): narrow the type
    //   - Branch: union types from multiple predecessors

    return narrowedType
```

**NOT needed for MVP.** Narrowing is only needed for diagnostic messages.

---

## 6. Checker-to-Transformer Communication

### 6.1 The EmitResolver Interface

TypeScript's `EmitResolver` is a subset of the checker's internal functions, exposed as an
interface that the transformer/emitter can call. The transformer obtains it via:

```typescript
const resolver = context.getEmitResolver();
```

### 6.2 Resolver Methods Used by the TypeScript Transformer (ts.ts)

Based on analysis of TypeScript's `src/compiler/transformers/ts.ts`:

| Method | Purpose | Used for |
|--------|---------|----------|
| `resolver.getEnumMemberValue(member)` | Get computed constant value of an enum member | Const enum emit (numeric/string value) |
| `resolver.isValueAliasDeclaration(node)` | Check if an export assignment refers to a value | Export assignment elision |
| `resolver.isReferencedAliasDeclaration(node)` | Check if an import/export is referenced in value positions | Import/export elision |

**That's only 3 resolver methods** used by the main TypeScript transformer. This is a much
smaller surface than expected.

### 6.3 Resolver Methods Used by the Declaration Emitter (declarations.ts)

The declaration emitter uses many more resolver methods (16+), but declaration emit is out of
scope for Phase 2.

### 6.4 Additional Utility Functions

The transformer also uses utility functions that are NOT on the resolver:

| Function | Purpose |
|----------|---------|
| `isInstantiatedModule(node, preserveConstEnums)` | Check if namespace has runtime code |
| `shouldPreserveConstEnums(options)` | Check compiler options |
| `isExternalModuleImportEqualsDeclaration(node)` | Check import-equals type |

`isInstantiatedModule` is a **pure AST utility** that doesn't require the checker — it walks
the module body looking for value declarations. The Kotlin Transformer already has
`isTypeOnlyNamespace()` which is the inverse check.

### 6.5 Type Serialization for Decorator Metadata

When `emitDecoratorMetadata` is true, the transformer calls:

```typescript
typeSerializer.serializeTypeOfNode(info, node, container)
typeSerializer.serializeParameterTypesOfNode(info, node, container)
typeSerializer.serializeReturnTypeOfNode(info, node, container)
```

The type serializer needs to:
1. Resolve a type annotation to a runtime value (e.g., `string` → `String`, `number` → `Number`)
2. Handle `typeof` for class references
3. Handle union types (→ `Object`)
4. Handle missing annotations (→ `Object` or `void 0`)

**This requires the checker** to resolve type references to their symbols.

---

## 7. MVP Scope for This Project

### 7.1 What the 52 Type-Checker-Dependent Tests Need

From `FAILURES.md` category (B):

| Feature | Test Count | Checker Requirement |
|---------|------------|---------------------|
| Const enum inlining | 9 | `getEnumMemberValue()` — resolve enum member values across files |
| Import elision | 24 | `isReferencedAliasDeclaration()` — determine if import is value-referenced |
| Decorator metadata | 3 | `serializeTypeOfNode()` — resolve types to runtime values |
| Enum value resolution | 4 | `getEnumMemberValue()` — cross-file constant resolution |
| Type-only import/export elision | 8 | `isReferencedAliasDeclaration()` + module instance state |
| Other type-checker-dependent | 4 | `isReferencedAliasDeclaration()` + `noEmitOnError` |

### 7.2 MVP Feature Set

**Must implement:**

1. **Symbol creation for all declarations** — binder walks AST, creates symbols, builds
   scope chains. Needed as foundation for everything else.

2. **Module instance state computation** — determine if a namespace/module is instantiated,
   non-instantiated, or const-enum-only. This is largely an AST utility (already partially
   implemented in Transformer).

3. **Cross-file const enum value resolution** — resolve `EnumA.MemberX` to its numeric/string
   value when `EnumA` is defined in a different file. This requires:
   - Building symbols for all enum declarations
   - Computing enum member values (recursive evaluation)
   - Following import chains to resolve the target enum
   - The existing `evaluateConstantExpression()` in Transformer handles single-file;
     extend it to work cross-file via symbol lookup.

4. **Import reference tracking** — determine which imports are used only in type positions:
   - Walk the original (pre-transform) AST
   - For each identifier, check if it resolves to an import symbol
   - Track whether each reference is in a value or type position
   - Expose `isReferencedAliasDeclaration(node): Boolean`

5. **`getEnumMemberValue(member): ConstantValue?`** — expose computed enum values to the
   transformer for const enum inlining.

**Should implement (for decorator metadata, 3 tests):**

6. **Basic type reference resolution** — resolve type annotation identifiers to their symbols.
   Map TypeScript types to runtime type objects for decorator metadata:
   - `string` → `String`
   - `number` → `Number`
   - `boolean` → `Boolean`
   - `SomeClass` → `typeof SomeClass` (runtime reference)
   - Union types → `Object`
   - Missing/unknown → `Object`

**Can skip entirely:**

- Full type inference from initializers
- Structural subtyping / assignability checking
- Control flow narrowing
- Generic type instantiation (beyond basic type references)
- Conditional types, mapped types, indexed access types
- Type widening / literal type inference
- Overload resolution
- Error diagnostics (`.errors.txt` tests are separate and not gated on MVP)
- Declaration emit (`.d.ts` generation)

### 7.3 Estimated Impact

| Feature | Tests unblocked | Effort |
|---------|-----------------|--------|
| Import elision (full) | ~24-32 | Medium — needs symbol table + reference tracking |
| Const enum inlining (cross-file) | ~9-13 | Medium — needs cross-file symbol resolution |
| Decorator metadata | ~3-4 | Low — basic type→runtime mapping |
| **Total** | **~36-49** | |

Some tests in the "import elision" category may also require CommonJS `Object.defineProperty`
patterns or `__importStar`/`__importDefault` helpers, which are independent of the type checker.
The actual unblocked count will be lower for tests with dual causes.

---

## 8. Kotlin Implementation Design

### 8.1 New Files

| File | Purpose | Approximate size |
|------|---------|-----------------|
| `Types.kt` | `Type` sealed class hierarchy, `TypeFlags` | ~200 lines |
| `Symbol.kt` | `Symbol` class, `SymbolFlags`, `SymbolTable` type alias | ~150 lines |
| `Binder.kt` | AST walker, scope chain builder, symbol table populator | ~500-800 lines |
| `Checker.kt` | Type resolution, import elision tracking, const enum values | ~300-500 lines |

### 8.2 Symbol Class Design

```kotlin
class Symbol(
    var flags: SymbolFlags,
    val name: String,
) {
    val declarations: MutableList<Node> = mutableListOf()
    var valueDeclaration: Node? = null
    var members: SymbolTable? = null    // class/interface members
    var exports: SymbolTable? = null    // module/namespace exports
    var parent: Symbol? = null
    var id: Int = 0

    // Checker-populated fields (lazy):
    var resolvedType: Type? = null
    var constEnumValue: ConstantValue? = null  // for enum members
    var isReferenced: Boolean = false           // for import elision
}
```

**Note on SymbolFlags:** Use a Kotlin `EnumSet<SymbolFlag>` or a plain `Int` bit field.
Given that TypeScript uses 30+ flags and does extensive bitwise operations, a plain `Int`
(or `Long`) is more idiomatic for this use case:

```kotlin
@JvmInline
value class SymbolFlags(val value: Int) {
    operator fun contains(flag: SymbolFlags): Boolean = (value and flag.value) != 0
    infix fun or(other: SymbolFlags): SymbolFlags = SymbolFlags(value or other.value)

    companion object {
        val None = SymbolFlags(0)
        val FunctionScopedVariable = SymbolFlags(1 shl 0)
        val BlockScopedVariable = SymbolFlags(1 shl 1)
        val Property = SymbolFlags(1 shl 2)
        val EnumMember = SymbolFlags(1 shl 3)
        val Function = SymbolFlags(1 shl 4)
        val Class = SymbolFlags(1 shl 5)
        val Interface = SymbolFlags(1 shl 6)
        val ConstEnum = SymbolFlags(1 shl 7)
        val RegularEnum = SymbolFlags(1 shl 8)
        val ValueModule = SymbolFlags(1 shl 9)
        val NamespaceModule = SymbolFlags(1 shl 10)
        val TypeAlias = SymbolFlags(1 shl 11)
        val Alias = SymbolFlags(1 shl 12)
        val ExportValue = SymbolFlags(1 shl 13)

        // Composite:
        val Variable = FunctionScopedVariable or BlockScopedVariable
        val Enum = RegularEnum or ConstEnum
        val Value = Variable or Property or EnumMember or Function or Class or Enum or ValueModule
        val Type = Class or Interface or Enum or TypeAlias
    }
}

typealias SymbolTable = MutableMap<String, Symbol>
```

**Multiplatform concern:** Since `commonMain` must avoid JVM-only types, use `Int` bitfield
(not `EnumSet`). The `@JvmInline value class` approach works on all targets.

### 8.3 Type Class Design

```kotlin
sealed class Type(
    val flags: TypeFlags,
) {
    var id: Int = 0
    var symbol: Symbol? = null
}

// Singleton intrinsic types
object AnyType : Type(TypeFlags.Any)
object UnknownType : Type(TypeFlags.Unknown)
object StringType : Type(TypeFlags.String)
object NumberType : Type(TypeFlags.Number)
object BooleanType : Type(TypeFlags.Boolean)
object VoidType : Type(TypeFlags.Void)
object UndefinedType : Type(TypeFlags.Undefined)
object NullType : Type(TypeFlags.Null)
object NeverType : Type(TypeFlags.Never)
object BigIntType : Type(TypeFlags.BigInt)
object SymbolType : Type(TypeFlags.ESSymbol)
object ObjectKeywordType : Type(TypeFlags.NonPrimitive)

// Literal types
class StringLiteralType(val value: String) : Type(TypeFlags.StringLiteral)
class NumberLiteralType(val value: Double) : Type(TypeFlags.NumberLiteral)
class BooleanLiteralType(val value: Boolean) : Type(TypeFlags.BooleanLiteral)

// Object types (classes, interfaces, anonymous)
class ObjectType(
    val members: SymbolTable = mutableMapOf(),
) : Type(TypeFlags.Object)

// Union and intersection
class UnionType(val types: List<Type>) : Type(TypeFlags.Union)
class IntersectionType(val types: List<Type>) : Type(TypeFlags.Intersection)

// Enum type
class EnumType(
    val enumSymbol: Symbol,
    val memberValues: Map<String, ConstantValue>,
) : Type(TypeFlags.Enum)
```

**For MVP, most of these types won't be used.** The checker needs to:
1. Identify enum members and compute their values → `EnumType`, `NumberLiteralType`, `StringLiteralType`
2. Determine if a symbol is a value or type → just needs `SymbolFlags`
3. Resolve type annotations for decorator metadata → needs a small set of types

### 8.4 ConstantValue

```kotlin
sealed interface ConstantValue {
    data class NumberValue(val value: Long) : ConstantValue
    data class DoubleValue(val value: Double) : ConstantValue
    data class StringValue(val value: String) : ConstantValue
}
```

### 8.5 Binder Design

```kotlin
class Binder(
    private val options: CompilerOptions,
) {
    fun bind(sourceFile: SourceFile): BinderResult {
        // Walk the AST
        // Create symbols for declarations
        // Build scope chains
        // Return result
    }
}

class BinderResult(
    val sourceFile: SourceFile,
    /** Symbols declared at file/module level */
    val fileLocals: SymbolTable,
    /** Symbols declared at file level that are exported */
    val fileExports: SymbolTable,
    /** Map from declaration node → its symbol (by pos/end key) */
    val nodeSymbols: Map<Long, Symbol>,
    /** Map from container node → its local symbol table */
    val containerLocals: Map<Long, SymbolTable>,
    /** Flat list of all enum symbols for cross-reference */
    val enumSymbols: List<Symbol>,
    /** Module instance state for namespace declarations */
    val moduleStates: Map<Long, ModuleInstanceState>,
)

/** Pack pos and end into a single Long for map keys */
fun nodeKey(pos: Int, end: Int): Long = (pos.toLong() shl 32) or end.toLong().and(0xFFFFFFFFL)
fun nodeKey(node: Node): Long = nodeKey(node.pos, node.end)
```

### 8.6 Checker Design

```kotlin
class Checker(
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,  // one per source file
) {
    // Merged global symbols (across all files)
    private val globals: SymbolTable = mutableMapOf()

    // Computed enum values: enumSymbolId → (memberName → value)
    private val enumMemberValues: MutableMap<Int, MutableMap<String, ConstantValue>> = mutableMapOf()

    // Import reference tracking: aliasSymbolId → isReferencedInValuePosition
    private val referencedAliases: MutableSet<Int> = mutableSetOf()

    init {
        // Merge symbol tables from all files
        for (result in binderResults) {
            mergeSymbolTable(globals, result.fileLocals)
        }
        // Compute all enum member values
        computeAllEnumValues()
        // Track import references
        trackImportReferences()
    }

    /** Get the constant value of an enum member. */
    fun getEnumMemberValue(memberNode: Node): ConstantValue? { ... }

    /** Check if an import/export alias is referenced in value positions. */
    fun isReferencedAliasDeclaration(node: Node): Boolean { ... }

    /** Check if an export assignment refers to a value symbol. */
    fun isValueAliasDeclaration(node: Node): Boolean { ... }

    /** Get the module instance state for a module/namespace declaration. */
    fun getModuleInstanceState(node: ModuleDeclaration): ModuleInstanceState { ... }

    /** Serialize a type annotation to a runtime type expression (for decorator metadata). */
    fun serializeTypeOfNode(node: Node): Expression? { ... }
}
```

### 8.7 Pipeline Integration

In `TypeScriptCompiler.kt`, the pipeline changes from:

```kotlin
val parser = Parser(content, fileName)
val sourceFile = parser.parse()
val transformer = Transformer(options)
val transformed = transformer.transform(sourceFile)
val emitter = Emitter(options)
val javascript = emitter.emit(transformed, sourceFile)
```

To:

```kotlin
val parser = Parser(content, fileName)
val sourceFile = parser.parse()
val binder = Binder(options)
val binderResult = binder.bind(sourceFile)
val checker = Checker(options, listOf(binderResult))  // or multiple for multi-file
val transformer = Transformer(options, checker)  // pass checker to transformer
val transformed = transformer.transform(sourceFile)
val emitter = Emitter(options)
val javascript = emitter.emit(transformed, sourceFile)
```

For multi-file compilation:
```kotlin
// Parse all files first
val sourceFiles = files.map { Parser(it.content, it.fileName).parse() }
// Bind all files
val binderResults = sourceFiles.map { Binder(options).bind(it) }
// Create shared checker across all files
val checker = Checker(options, binderResults)
// Transform each file with the shared checker
for (sourceFile in sourceFiles) {
    val transformer = Transformer(options, checker)
    val transformed = transformer.transform(sourceFile)
    // ... emit
}
```

### 8.8 Transformer Changes

The `Transformer` class gains an optional `checker: Checker?` parameter (nullable to preserve
backward compatibility during incremental development):

```kotlin
class Transformer(
    val options: CompilerOptions,
    val checker: Checker? = null,
) {
    // Existing import elision logic enhanced:
    private fun elideUnusedESModuleImports(statements: List<Statement>): List<Statement> {
        if (options.verbatimModuleSyntax) return statements
        if (checker != null) {
            // Use checker's reference tracking for precise import elision
            return elideImportsWithChecker(statements)
        }
        // Fallback to existing heuristic-based elision
        return existingElisionLogic(statements)
    }

    // New: const enum member inlining
    private fun transformPropertyAccessOnConstEnum(node: PropertyAccessExpression): Expression? {
        if (checker == null) return null
        val value = checker.resolveConstEnumMemberAccess(node) ?: return null
        // Replace E.A with 0 /* E.A */
        return createConstEnumLiteral(value, node)
    }
}
```

### 8.9 Import Elision Strategy

The MVP import elision should work as follows:

1. **Pre-transform phase (in Binder/Checker):**
   - Build symbols for all imports
   - Walk the original AST (before type erasure)
   - For each identifier reference, check if it resolves to an import symbol
   - If the reference is in a type-only position (type annotation, implements clause,
     type-only import/export), don't mark the alias as referenced
   - If the reference is in a value position, mark the alias as referenced

2. **Transform phase (in Transformer):**
   - When processing an import declaration, ask the checker:
     `checker.isReferencedAliasDeclaration(importDecl) → Boolean`
   - If false, elide the import
   - If true, keep it

3. **Type-only position detection:**

   The following AST positions are "type-only" (references here don't count as value uses):
   - Type annotations (`: TypeNode` on variables, parameters, return types)
   - `implements` clauses (but NOT `extends` — extends is a value reference)
   - Type arguments (in angle brackets)
   - Type-only import/export declarations (`import type { X }`, `export type { X }`)
   - `satisfies` and `as` expressions (type assertions)
   - Type predicates (`x is Foo`)
   - Ambient declarations (`declare class Foo`)

   The following are "value" positions:
   - Expressions (function calls, property access, assignments)
   - `extends` clauses in class declarations
   - Decorator expressions
   - Export assignments (`export = x`, `export default x`)
   - Enum member initializers
   - Variable initializers

### 8.10 Cross-File Const Enum Resolution

For const enum inlining across files:

```
// File a.ts:
export const enum E { A = 1, B = 2, C = A | B }

// File b.ts:
import { E } from "./a";
console.log(E.C);  // Should emit: console.log(3 /* E.C */);
```

The checker needs to:
1. Resolve the import `E` to its source file's enum symbol
2. Look up member `C` in that enum's computed values
3. Return `ConstantValue.NumberValue(3)`

The transformer then replaces `E.C` with `3` and adds a trailing comment `/* E.C */`.

### 8.11 Node Identity Strategy

Since our AST nodes are immutable data classes, we need a way to map nodes to their symbols.
Options:

1. **`pos`/`end` pair as key**: `nodeKey(node) = (pos.toLong() shl 32) or end.toLong()`
   - Pro: simple, no changes to AST
   - Con: two nodes at the same position would collide (rare but possible with synthetic nodes)

2. **Assign unique IDs during parsing**: add `val id: Int` to the Node interface
   - Pro: guaranteed unique
   - Con: requires changes to every node class

3. **Object identity via `IdentityHashMap`**: use reference equality
   - Pro: no changes needed
   - Con: data classes override `hashCode()`/`equals()` to use structural equality,
     so `IdentityHashMap` is needed. But `IdentityHashMap` is JVM-only.

**Recommendation: Use `pos`/`end` pair.** For the MVP, synthetic nodes (pos=-1) won't need
symbol lookups. Real source nodes have unique pos/end pairs within a file. Use a file-specific
`Map<Long, Symbol>` where the key encodes both coordinates.

---

## Appendix A: Existing Transformer Import Elision Code

The Transformer already has import elision logic at lines 377-457 (`elideUnusedESModuleImports`).
It works by:
1. Collecting all identifier names in value positions from non-import statements
2. Checking if each import's binding names appear in that set
3. If no binding names appear, dropping the import

This is a **post-transform heuristic**: it runs after type annotations are erased, so any name
that appeared only in type positions is naturally absent from the transformed code. This handles
~80% of import elision cases correctly.

The remaining ~20% of cases need the checker:
- `import x = M.N` where `M.N` is only types (needs module instance state)
- Re-exports of type-only symbols (`export { X }` where X is an interface from another file)
- Const enum imports (import should be elided when enum is inlined)
- Import aliases that reference non-instantiated modules

## Appendix B: Existing Transformer Const Enum Code

The Transformer has const enum handling at lines 8402-8550 (`transformEnum`). It already:
1. Removes const enum declarations when `preserveConstEnums` is false
2. Computes enum member values within a single declaration
3. Handles forward references and cross-member references
4. Tracks `allEnumMemberValues` for merged enums

What it lacks:
- Cross-file enum value resolution (import E from another file)
- Replacing `E.A` with the literal value `0` in arbitrary expressions

## Appendix C: Key Differences from TypeScript's Architecture

| Aspect | TypeScript | Kotlin Implementation |
|--------|------------|----------------------|
| AST mutability | Mutable nodes (`node.symbol = ...`) | Immutable data classes + look-aside maps |
| SymbolFlags | `const enum` (number) | `value class SymbolFlags(val value: Int)` |
| TypeFlags | `const enum` (number) | Similar value class or enum |
| SymbolTable | `Map<__String, Symbol>` | `MutableMap<String, Symbol>` |
| Checker size | 47,000+ lines (full type system) | ~300-500 lines (MVP subset) |
| CFG / narrowing | Built by binder, used by checker | Skipped for MVP |
| Lazy evaluation | Types computed on demand | Enum values computed eagerly at init |
| Multi-file | Program merges all files | Checker receives list of BinderResults |
