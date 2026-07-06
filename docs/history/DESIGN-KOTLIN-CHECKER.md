# Kotlin Type Checker Architecture — Implementation Design

**Purpose:** Concrete Kotlin implementation design for the MVP type checker. This answers the
questions in PLAN.md item 0c and specifies exactly what to build in items 1-4.

**Prerequisite:** Read `DESIGN-TYPE-CHECKER.md` for the TypeScript architecture research.

---

## 1. MVP Scope — What Unblocks the Most Tests

The 52 type-checker-dependent tests (FAILURES.md category B) need exactly **3 resolver methods**:

| Method | Tests unblocked | Description |
|--------|-----------------|-------------|
| `isReferencedAliasDeclaration(node)` | ~32 | Is this import/export used in a value position? |
| `getEnumMemberValue(node)` | ~13 | What is the computed constant value of this enum member? |
| `isValueAliasDeclaration(node)` | ~4 | Does this export assignment refer to a value? |

Plus one utility function:
| Function | Tests unblocked | Description |
|----------|-----------------|-------------|
| `isInstantiatedModule(node)` | ~3 | Does this namespace contain runtime code? |

Decorator metadata (`serializeTypeOfNode`) is needed for 3 tests but requires significantly more
checker infrastructure. It should be deferred to after the core 3 methods are working.

### What we skip entirely
- Full type inference, structural subtyping, narrowing, generic instantiation
- Conditional types, mapped types, indexed access types
- Error diagnostics (`.errors.txt` tests are separate)
- Declaration emit (`.d.ts` generation)
- Control flow graph

---

## 2. New Files

| File | Purpose | Est. lines |
|------|---------|------------|
| `Binder.kt` | AST walker → symbol tables, scope chains, module instance state | 500-700 |
| `Checker.kt` | Import reference tracking, cross-file enum resolution, resolver API | 300-400 |
| `Types.kt` | `Symbol`, `SymbolFlags`, `SymbolTable`, `ConstantValue`, `ModuleInstanceState` | 150-200 |

All files go in `src/commonMain/kotlin/` under `package com.xemantic.typescript.compiler`.

---

## 3. `Types.kt` — Data Types

### 3.1 SymbolFlags

Use Int bitfield (multiplatform compatible, matches TypeScript's approach):

```kotlin
@JvmInline
value class SymbolFlags(val value: Int) {
    operator fun contains(flag: SymbolFlags): Boolean = (value and flag.value) != 0
    infix fun or(other: SymbolFlags): SymbolFlags = SymbolFlags(value or other.value)
    infix fun and(other: SymbolFlags): SymbolFlags = SymbolFlags(value and other.value)
    fun hasAny(flags: SymbolFlags): Boolean = (value and flags.value) != 0

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
        val Method = SymbolFlags(1 shl 14)
        val GetAccessor = SymbolFlags(1 shl 15)
        val SetAccessor = SymbolFlags(1 shl 16)
        val TypeParameter = SymbolFlags(1 shl 17)

        // Composite flags
        val Variable = FunctionScopedVariable or BlockScopedVariable
        val Enum = RegularEnum or ConstEnum
        val Value = Variable or Property or EnumMember or Function or
                    Class or Enum or ValueModule or Method or GetAccessor or SetAccessor
        val Type = Class or Interface or Enum or TypeAlias or TypeParameter
        val Module = ValueModule or NamespaceModule
    }
}
```

**Note on `@JvmInline`:** This annotation is JVM-only but value classes work on all Kotlin
targets. In `commonMain`, use `value class` without `@JvmInline` — the Kotlin compiler
handles it. The `@JvmInline` annotation is only needed when declaring value classes in
JVM-specific source sets.

### 3.2 Symbol

```kotlin
class Symbol(
    var flags: SymbolFlags,
    val name: String,
) {
    val declarations: MutableList<Node> = mutableListOf()
    var valueDeclaration: Node? = null
    var members: SymbolTable? = null     // class/interface member symbols
    var exports: SymbolTable? = null     // module/namespace exported symbols
    var parent: Symbol? = null
    var id: Int = nextSymbolId++         // unique ID for map keys

    // Checker-populated:
    var constEnumOnlyModule: Boolean? = null  // for module instance state
    var target: Symbol? = null               // resolved alias target

    companion object {
        private var nextSymbolId = 1
    }
}

typealias SymbolTable = MutableMap<String, Symbol>
fun symbolTable(): SymbolTable = mutableMapOf()
```

### 3.3 ConstantValue

```kotlin
sealed interface ConstantValue {
    data class NumberValue(val value: Double) : ConstantValue {
        override fun toString(): String {
            // Emit integers without decimal point: 0.0 → "0", 3.0 → "3"
            return if (value == value.toLong().toDouble()) value.toLong().toString()
            else value.toString()
        }
    }
    data class StringValue(val value: String) : ConstantValue
}
```

Using `Double` for NumberValue (not Long) matches TypeScript's number type and handles
both integer and floating-point enum values.

### 3.4 ModuleInstanceState

```kotlin
enum class ModuleInstanceState {
    NonInstantiated,    // only types
    Instantiated,       // contains runtime code
    ConstEnumOnly,      // only const enums
}
```

### 3.5 Node identity keys

```kotlin
/** Pack pos/end into a single Long for use as map keys. */
fun nodeKey(pos: Int, end: Int): Long = (pos.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
fun nodeKey(node: Node): Long = nodeKey(node.pos, node.end)
```

---

## 4. `Binder.kt` — Symbol Table Population

### 4.1 Core Algorithm

The binder makes a single pass over one `SourceFile`'s AST. It produces a `BinderResult`:

```kotlin
class BinderResult(
    val sourceFile: SourceFile,
    /** Symbols at file scope (both local and exported) */
    val locals: SymbolTable,
    /** Map from declaration node key → its Symbol */
    val nodeToSymbol: MutableMap<Long, Symbol>,
    /** Module instance states for namespace declarations */
    val moduleInstanceStates: MutableMap<Long, ModuleInstanceState>,
)
```

### 4.2 Binder Class

```kotlin
class Binder(private val options: CompilerOptions) {

    // Current scope context
    private var container: Symbol? = null          // current function/module scope
    private var currentLocals: SymbolTable = symbolTable()  // where block-scoped vars go

    // Output
    private val nodeToSymbol = mutableMapOf<Long, Symbol>()
    private val moduleInstanceStates = mutableMapOf<Long, ModuleInstanceState>()

    fun bind(sourceFile: SourceFile): BinderResult {
        val fileLocals = symbolTable()
        currentLocals = fileLocals
        bindStatements(sourceFile.statements)
        return BinderResult(sourceFile, fileLocals, nodeToSymbol, moduleInstanceStates)
    }
}
```

### 4.3 What the Binder Binds (MVP)

For the MVP, we need symbols for:

| Declaration type | Symbol flags | Where stored |
|------------------|-------------|--------------|
| `VariableDeclaration` (var) | `FunctionScopedVariable` | function scope locals |
| `VariableDeclaration` (let/const) | `BlockScopedVariable` | block scope locals |
| `FunctionDeclaration` | `Function` | container locals |
| `ClassDeclaration` | `Class` | container locals |
| `InterfaceDeclaration` | `Interface` | container locals |
| `EnumDeclaration` | `RegularEnum` or `ConstEnum` | container locals |
| `EnumMember` | `EnumMember` | parent enum's `exports` |
| `ModuleDeclaration` | `ValueModule` or `NamespaceModule` | container locals |
| `TypeAliasDeclaration` | `TypeAlias` | container locals |
| `ImportDeclaration` | `Alias` | container locals |
| `ImportEqualsDeclaration` | `Alias` | container locals |
| `ExportDeclaration` | — (handled specially) | — |
| `Parameter` | `FunctionScopedVariable` | function scope locals |

### 4.4 Declaration Merging Rules

MVP merging rules (same as TypeScript):

```kotlin
fun canMerge(existing: SymbolFlags, incoming: SymbolFlags): Boolean {
    // Interface + Interface → merge
    if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Interface)) return true
    // Namespace + Namespace → merge
    if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Module)) return true
    // Class + Namespace → merge
    if (existing.hasAny(SymbolFlags.Class) && incoming.hasAny(SymbolFlags.Module)) return true
    if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Class)) return true
    // Function + Namespace → merge
    if (existing.hasAny(SymbolFlags.Function) && incoming.hasAny(SymbolFlags.Module)) return true
    if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Function)) return true
    // Enum + Namespace → merge
    if (existing.hasAny(SymbolFlags.Enum) && incoming.hasAny(SymbolFlags.Module)) return true
    if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Enum)) return true
    // Enum + Enum (same const-ness) → merge
    if (existing.hasAny(SymbolFlags.Enum) && incoming.hasAny(SymbolFlags.Enum)) return true
    // var + var → merge (same symbol)
    if (existing.hasAny(SymbolFlags.FunctionScopedVariable) &&
        incoming.hasAny(SymbolFlags.FunctionScopedVariable)) return true
    return false
}
```

### 4.5 Module Instance State Computation

This is the key function for import elision. It determines whether a namespace/module
produces any runtime code:

```kotlin
fun getModuleInstanceState(node: ModuleDeclaration): ModuleInstanceState {
    val body = node.body ?: return ModuleInstanceState.Instantiated
    return when (body) {
        is ModuleBlock -> getModuleInstanceStateForBlock(body)
        is ModuleDeclaration -> getModuleInstanceState(body)
        else -> ModuleInstanceState.Instantiated
    }
}

private fun getModuleInstanceStateForBlock(block: ModuleBlock): ModuleInstanceState {
    var hasConstEnum = false
    for (stmt in block.statements) {
        val state = getModuleInstanceStateForStatement(stmt)
        if (state == ModuleInstanceState.Instantiated) return ModuleInstanceState.Instantiated
        if (state == ModuleInstanceState.ConstEnumOnly) hasConstEnum = true
    }
    return if (hasConstEnum) ModuleInstanceState.ConstEnumOnly
           else ModuleInstanceState.NonInstantiated
}

private fun getModuleInstanceStateForStatement(stmt: Statement): ModuleInstanceState {
    return when (stmt) {
        is InterfaceDeclaration -> ModuleInstanceState.NonInstantiated
        is TypeAliasDeclaration -> ModuleInstanceState.NonInstantiated
        is ImportDeclaration -> ModuleInstanceState.NonInstantiated
        is ImportEqualsDeclaration -> {
            if (ModifierFlag.Export !in stmt.modifiers) ModuleInstanceState.NonInstantiated
            else ModuleInstanceState.Instantiated
        }
        is ExportDeclaration -> {
            if (stmt.isTypeOnly) ModuleInstanceState.NonInstantiated
            else ModuleInstanceState.Instantiated
        }
        is EnumDeclaration -> {
            if (ModifierFlag.Const in stmt.modifiers && !options.preserveConstEnums)
                ModuleInstanceState.ConstEnumOnly
            else ModuleInstanceState.Instantiated
        }
        is ModuleDeclaration -> {
            if (ModifierFlag.Declare in stmt.modifiers) ModuleInstanceState.NonInstantiated
            else getModuleInstanceState(stmt)
        }
        else -> ModuleInstanceState.Instantiated
    }
}
```

### 4.6 Export Handling

When a declaration has the `Export` modifier, the binder should also record it in the
container's `exports` symbol table (in addition to `locals`). For MVP, we track this
by adding the `ExportValue` flag to the symbol.

---

## 5. `Checker.kt` — Import Reference Tracking & Const Enum Resolution

### 5.1 Class Structure

```kotlin
class Checker(
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
) {
    // Merged symbol tables (global scope across all files)
    private val globals: SymbolTable = symbolTable()

    // Per-file binder results for lookup
    private val fileResults: Map<String, BinderResult> =
        binderResults.associateBy { it.sourceFile.fileName }

    // Import reference tracking
    private val referencedAliases: MutableSet<Int> = mutableSetOf()  // symbol IDs

    // Computed enum member values (shared across files)
    private val enumValues: MutableMap<Int, MutableMap<String, ConstantValue>> = mutableMapOf()

    init {
        // 1. Merge file-level symbols into globals
        for (result in binderResults) {
            mergeSymbolTable(globals, result.locals)
        }
        // 2. Compute all enum member values
        computeEnumValues()
        // 3. Track import references across all files
        trackImportReferences()
    }
}
```

### 5.2 Import Reference Tracking Algorithm

This is the most impactful feature. The algorithm:

1. Walk each source file's AST (pre-transform)
2. For each `Identifier`, check if it resolves to an import symbol
3. Determine if the identifier is in a type-only position
4. If NOT type-only, mark the import symbol as "referenced"

```kotlin
private fun trackImportReferences() {
    for (result in binderResults) {
        walkForReferences(result.sourceFile.statements, result)
    }
}

private fun walkForReferences(nodes: List<Node>, binderResult: BinderResult) {
    for (node in nodes) {
        walkNodeForReferences(node, binderResult, inTypePosition = false)
    }
}

private fun walkNodeForReferences(
    node: Node,
    binderResult: BinderResult,
    inTypePosition: Boolean,
) {
    // When we encounter a type annotation context, switch to type position
    when (node) {
        // Type annotations are type positions
        is TypeNode -> return  // don't recurse into types for value references
        is HeritageClause -> {
            // 'implements' is type-only; 'extends' is value
            val isTypeOnly = node.token == SyntaxKind.ImplementsKeyword
            for (type in node.types) {
                walkNodeForReferences(type.expression, binderResult, isTypeOnly)
            }
            return
        }
        is Identifier -> {
            if (!inTypePosition) {
                // This identifier is in a value position — check if it's an import
                val symbol = resolveIdentifier(node.text, binderResult)
                if (symbol != null && SymbolFlags.Alias in symbol.flags) {
                    referencedAliases.add(symbol.id)
                }
            }
            return
        }
        // Recursion into children...
    }
    // Recurse into all child nodes
    walkChildrenForReferences(node, binderResult, inTypePosition)
}
```

**Type-only positions** (identifier here does NOT mark import as referenced):
- Type annotations (`: TypeNode`)
- `implements` clauses
- Type arguments `<T>`
- `satisfies` / `as` type assertions
- Type predicates
- Ambient declarations (`declare class ...`)
- Type-only imports/exports (`import type ...`, `export type ...`)

**Value positions** (identifier here DOES mark import as referenced):
- All expressions
- `extends` clauses (class inheritance is runtime)
- Decorator expressions
- Export assignments (`export = x`, `export default x`)
- Enum member initializers
- Variable initializers

### 5.3 Resolver API (called by Transformer)

```kotlin
/** Is this import/re-export referenced in value positions? */
fun isReferencedAliasDeclaration(node: Node): Boolean {
    val key = nodeKey(node)
    for (result in binderResults) {
        val symbol = result.nodeToSymbol[key]
        if (symbol != null) {
            return symbol.id in referencedAliases
        }
    }
    return true  // safe default: keep the import
}

/** Does this export assignment refer to a value (not just a type)? */
fun isValueAliasDeclaration(node: Node): Boolean {
    if (node !is ExportAssignment) return true
    val expr = node.expression
    if (expr is Identifier) {
        val symbol = resolveIdentifier(expr.text, binderResultForNode(node))
        if (symbol != null) {
            // If it only has type flags, it's not a value
            return symbol.flags.hasAny(SymbolFlags.Value)
        }
    }
    return true  // expressions are always values
}

/** Get the computed constant value of an enum member. */
fun getEnumMemberValue(memberNode: Node): ConstantValue? {
    val key = nodeKey(memberNode)
    for (result in binderResults) {
        val symbol = result.nodeToSymbol[key]
        if (symbol != null) {
            val enumSymbol = symbol.parent ?: return null
            return enumValues[enumSymbol.id]?.get(symbol.name)
        }
    }
    return null
}

/** Resolve a property access on a const enum: E.A → literal value. */
fun resolveConstEnumMemberAccess(
    enumName: String,
    memberName: String,
    binderResult: BinderResult,
): ConstantValue? {
    val enumSymbol = resolveIdentifier(enumName, binderResult) ?: return null
    if (SymbolFlags.ConstEnum !in enumSymbol.flags) return null
    // Follow alias if it's an import
    val target = resolveAlias(enumSymbol)
    return enumValues[target.id]?.get(memberName)
}
```

### 5.4 Cross-File Enum Value Resolution

The existing Transformer computes enum member values within a single declaration. The checker
extends this to work across files:

```kotlin
private fun computeEnumValues() {
    for (result in binderResults) {
        for ((_, symbol) in result.locals) {
            if (symbol.flags.hasAny(SymbolFlags.Enum)) {
                computeEnumSymbolValues(symbol)
            }
        }
    }
}

private fun computeEnumSymbolValues(symbol: Symbol) {
    if (enumValues.containsKey(symbol.id)) return  // already computed
    val values = mutableMapOf<String, ConstantValue>()
    enumValues[symbol.id] = values

    var autoValue = 0.0
    for (decl in symbol.declarations) {
        if (decl !is EnumDeclaration) continue
        for (member in decl.members) {
            val name = when (val n = member.name) {
                is Identifier -> n.text
                is StringLiteralNode -> n.text
                is NumericLiteralNode -> n.text
                else -> continue
            }
            if (member.initializer != null) {
                val computed = evaluateEnumInitializer(member.initializer, values, symbol)
                if (computed != null) {
                    values[name] = computed
                    if (computed is ConstantValue.NumberValue) {
                        autoValue = computed.value + 1
                    }
                }
            } else {
                values[name] = ConstantValue.NumberValue(autoValue)
                autoValue++
            }
        }
    }
}
```

### 5.5 Identifier Resolution

Simple scope-based lookup:

```kotlin
private fun resolveIdentifier(name: String, binderResult: BinderResult): Symbol? {
    // Look in file-level scope first
    return binderResult.locals[name] ?: globals[name]
}

private fun resolveAlias(symbol: Symbol): Symbol {
    // Follow import alias to its target
    if (symbol.target != null) return resolveAlias(symbol.target!!)
    return symbol
}
```

For MVP, this flat lookup is sufficient because:
- Import elision only cares about file-level imports
- Const enum references are file-level declarations
- We don't need to resolve nested scopes (function locals, block locals)

---

## 6. Pipeline Integration

### 6.1 TypeScriptCompiler.kt Changes

Single-file path:
```kotlin
val parser = Parser(file.content, file.fileName, forceJsx = forceJsxForJs)
val sourceFile = parser.parse()
diagnostics.addAll(parser.getDiagnostics())

// NEW: Bind and check
val binder = Binder(options)
val binderResult = binder.bind(sourceFile)
val checker = Checker(options, listOf(binderResult))

val transformer = Transformer(options, checker)  // pass checker
val transformed = transformer.transform(sourceFile)
```

Multi-file path:
```kotlin
// Parse all files
val sourceFiles = mutableListOf<SourceFile>()
for (file in filesToCompile) {
    val parser = Parser(file.content, file.fileName)
    sourceFiles.add(parser.parse())
}

// Bind all files
val binder = Binder(options)
val binderResults = sourceFiles.map { binder.bind(it) }

// Create shared checker
val checker = Checker(options, binderResults)

// Transform and emit each file
for (sourceFile in sourceFiles) {
    val transformer = Transformer(options, checker)
    val transformed = transformer.transform(sourceFile)
    // ... emit
}
```

### 6.2 Transformer.kt Changes

Add `checker` parameter:

```kotlin
class Transformer(
    val options: CompilerOptions,
    val checker: Checker? = null,  // nullable for backward compatibility
) {
```

#### Import Elision Enhancement

In `elideUnusedESModuleImports`, add checker-based path:

```kotlin
private fun elideUnusedESModuleImports(statements: List<Statement>): List<Statement> {
    if (options.verbatimModuleSyntax) return statements

    // If checker is available, use precise reference tracking
    if (checker != null) {
        return elideImportsWithChecker(statements)
    }

    // Existing heuristic-based elision (unchanged)
    ...
}

private fun elideImportsWithChecker(statements: List<Statement>): List<Statement> {
    val result = mutableListOf<Statement>()
    for (stmt in statements) {
        when (stmt) {
            is ImportDeclaration -> {
                val clause = stmt.importClause
                if (clause == null) {
                    // Side-effect import: always keep
                    result.add(stmt)
                } else if (checker.isReferencedAliasDeclaration(stmt)) {
                    result.add(stmt)
                }
                // else: drop the import
            }
            is ImportEqualsDeclaration -> {
                if (stmt.isTypeOnly || !checker.isReferencedAliasDeclaration(stmt)) {
                    // Elide type-only import
                } else {
                    result.add(stmt)
                }
            }
            else -> result.add(stmt)
        }
    }
    return result
}
```

#### Const Enum Inlining

In the expression visitor, add const enum member access replacement:

```kotlin
// In visitExpression or transformPropertyAccess:
if (node is PropertyAccessExpression && checker != null) {
    val constValue = tryResolveConstEnumAccess(node)
    if (constValue != null) {
        return createConstEnumLiteral(constValue, node)
    }
}
```

---

## 7. Existing Transformer Code to Integrate With

### 7.1 `collectValueReferences()` (line 10573)

This existing function walks statements to find all identifier names used in value positions.
When the checker is available, this function is bypassed in favor of `checker.isReferencedAliasDeclaration()`.

### 7.2 `evaluateConstantExpression()` (line 9145)

This existing function evaluates enum member initializers within a single enum declaration.
The checker's `computeEnumValues()` replaces this for cross-file resolution. Within-file
enum value computation can continue to use the existing Transformer logic.

### 7.3 `isTypeOnlyNamespace()` (existing)

The Transformer already has logic to check if a namespace is type-only. The checker's
`getModuleInstanceState()` is more precise and should be preferred when available.

### 7.4 `transformImportEqualsDeclaration()` (existing)

Currently transforms `import x = require("mod")` to `var x = require("mod")`. With the
checker, type-only import-equals should be elided entirely.

---

## 8. Implementation Order (maps to PLAN.md items 1-4)

### Item 1: Foundation — `Types.kt`

Create `Types.kt` with:
- `SymbolFlags` value class
- `Symbol` class
- `SymbolTable` type alias
- `ConstantValue` sealed interface
- `ModuleInstanceState` enum
- `nodeKey()` functions

No test impact — this is pure data structure definitions.

### Item 2: Binder — `Binder.kt`

Create `Binder.kt` with:
- AST walker that creates symbols for all declarations
- Scope management (file scope only for MVP — no nested function/block scope needed)
- Module instance state computation
- Declaration merging
- `BinderResult` data class

No test impact — binder just produces data.

### Item 3: Checker — `Checker.kt`

Create `Checker.kt` with:
- Import reference tracking (`isReferencedAliasDeclaration`)
- Cross-file enum value resolution (`getEnumMemberValue`)
- Value alias detection (`isValueAliasDeclaration`)
- Const enum access resolution

Test impact depends on Transformer integration (item 4).

### Item 4: Pipeline Integration

- Wire `Binder` + `Checker` into `TypeScriptCompiler.kt`
- Add `checker` parameter to `Transformer`
- Enhance import elision with checker
- Add const enum inlining for cross-file access
- Run full test suite, verify no regressions + test improvements

Expected impact: 30-49 tests fixed.

---

## 9. Risk Mitigation

### 9.1 Backward Compatibility

The `checker` parameter on `Transformer` is nullable (`checker: Checker? = null`). All
existing code paths work unchanged when `checker` is null. This means:
- Intermediate commits can add the binder/checker without changing any test results
- Integration is incremental — enable checker features one at a time

### 9.2 No Regressions

The checker should never cause a test that currently passes to fail. The design ensures this:
- When `checker` is null, behavior is identical to current
- When `checker` is non-null, it only changes import elision and const enum inlining
- The existing heuristic elision is kept as fallback

### 9.3 Performance

The binder and checker run once per file (or once for the entire multi-file compilation).
Expected overhead is negligible compared to the parser and transformer:
- Binder: single AST pass, O(n) in AST size
- Checker reference tracking: single AST pass, O(n)
- Enum value computation: O(m) where m = total enum members across all files
