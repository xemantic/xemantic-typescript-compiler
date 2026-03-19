/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import kotlin.math.pow

/**
 * MVP type checker providing the three resolver methods needed by the Transformer:
 * - [isReferencedAliasDeclaration] — import elision
 * - [getEnumMemberValue] — const enum inlining
 * - [isValueAliasDeclaration] — export assignment elision
 *
 * The checker merges symbol tables from all source files, computes enum member
 * values (including cross-file references), and tracks which imports are used
 * in value positions vs. type-only positions.
 */
class Checker(
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
    /** True when the source had @Filename directives (multi-file test input). */
    private val isMultiFileSource: Boolean = false,
) {
    /** Merged symbol tables from all files (global scope). */
    private val globals: SymbolTable = symbolTable()

    /** Per-file binder results for lookup. */
    private val fileResults: Map<String, BinderResult> =
        binderResults.associateBy { it.sourceFile.fileName }

    /** Import symbols that are referenced in value positions. */
    private val referencedAliases: MutableSet<Int> = mutableSetOf()

    /** Computed enum member values: enum symbol ID → (member name → value). */
    private val enumValues: MutableMap<Int, MutableMap<String, ConstantValue>> = mutableMapOf()

    /** Checker-produced diagnostics. */
    private val diagnostics: MutableList<Diagnostic> = mutableListOf()

    /** Maximum recursion depth for AST walking to prevent StackOverflow. */
    private val maxCheckDepth = 200
    private var checkDepth = 0

    /** Check if a file is a declaration file (.d.ts/.d.mts/.d.cts). */
    private fun isDtsFile(fileName: String): Boolean =
        fileName.endsWith(".d.ts") || fileName.endsWith(".d.mts") || fileName.endsWith(".d.cts")

    init {
        // 1. Merge file-level symbols into globals
        for (result in binderResults) {
            mergeSymbolTable(globals, result.locals)
        }
        // 2. Compute all enum member values
        computeAllEnumValues()
        // 3. Track import references across all files
        trackAllImportReferences()
        // 4. Check for unused declarations (TS6133/TS6196)
        if (options.noUnusedLocals || options.noUnusedParameters) {
            checkUnusedDeclarations()
        }
        // 5. Check for variables used before assignment (TS2454)
        if (!options.strictExplicitlyFalse) {
            checkDefiniteAssignment()
        }
        // 6. Check for class properties without initializer (TS2564)
        if (!options.strictExplicitlyFalse) {
            checkPropertyInitialization()
        }
        // 7. Check for implicit any parameters (TS7006)
        if (options.noImplicitAny || options.strict) {
            checkImplicitAnyParameters()
        }
        // 8. Check for unresolved names (TS2304)
        checkUnresolvedNames()
        // 9. Check JSX elements for missing type definitions (TS7026)
        if (options.jsx != null) {
            checkJsxImplicitAny()
        }
        // 10. Check for duplicate identifiers (TS2300)
        checkDuplicateIdentifiers()
        // 11. Check export assignment conflicts (TS2309)
        checkExportAssignmentConflicts()
        // 12. Check strict mode identifier restrictions (TS1100)
        if (options.alwaysStrict == true || options.strict) {
            checkStrictModeIdentifiers()
        }
        // 13. Check export= in ES module files (TS1203)
        checkExportAssignmentInEsModule()
        // 14. Check unresolved module specifiers (TS2307)
        checkUnresolvedModules()
        // 15. Check break/continue crossing function boundaries (TS1107)
        checkJumpTargets()
        // 16. Check call expression argument counts (TS2554)
        checkArgumentCounts()
        // 17. Check missing function implementations (TS2391)
        checkMissingImplementations()
        // 18. Check unreachable code (TS7027)
        if (options.allowUnreachableCode == false) {
            checkUnreachableCode()
        }
        // 19. Check type used as value (TS2693)
        checkTypeUsedAsValue()
        // 20. Check always-truthy expressions (TS2872)
        checkAlwaysTruthy()
        // 20b. Check comma operator left side unused (TS2695)
        checkCommaOperatorUnused()
        // 21. Check null/undefined used in invalid positions (TS18050)
        checkNullUndefinedUsage()
        // 22. Check for implicit this (TS2683)
        if (options.noImplicitThis || options.strict) {
            checkImplicitThis()
        }
        // 23. Check duplicate object literal properties (TS1117)
        checkDuplicateObjectLiteralProperties()
        // 24. Check super called before this in derived constructors (TS17009)
        checkSuperBeforeThis()
        // 25. Check assignment to const variables (TS2540)
        checkConstAssignment()
        // 26. Check parameter properties outside constructor (TS2369)
        checkParameterProperties()
        // 27. Check super in non-derived class (TS2335)
        checkSuperInNonDerived()
        // 28. Check const without initializer (TS1155)
        checkConstWithoutInitializer()
        // 29. Check reserved words in wrong context (TS1359)
        checkReservedWordIdentifiers()
        // 30. Check outFile with non-AMD/System module (TS6131)
        checkOutFileModuleConflict()
        // 31. Check for merge conflict markers (TS1185)
        checkConflictMarkers()
        // 32. Check module=none with imports/exports (TS1148)
        checkModuleNoneConflict()
        // 33. Check export= in system modules (TS1218)
        checkExportAssignmentInSystem()
        // 34. Check reserved name collisions in modules (TS2441)
        checkReservedModuleNames()
        // 35. Check block-scoped function declarations in ES5 strict mode (TS1250)
        if (options.target < ScriptTarget.ES2015) {
            checkBlockScopedFunctionDeclarations()
        }
        // 36. Check import declarations with modifiers (TS1191)
        checkImportModifiers()
        // 37. Check block-scoped variable use before declaration (TS2448)
        checkUseBeforeDeclaration()
        // 38. Check setter parameter count (TS1049)
        checkSetterParameterCount()
        // 39. Check duplicate modifiers (TS1030)
        checkDuplicateModifiers()
        // 40. Check rest parameter is last (TS1014)
        checkRestParameterLast()
        // 41. Check implementation in ambient context (TS1183)
        checkAmbientImplementation()
        // 42. Check arguments collision with rest params (TS2396)
        if (options.target < ScriptTarget.ES2015) {
            checkArgumentsCollision()
        }
        // 43. Check initializers in ambient contexts (TS1039)
        checkAmbientInitializers()
        // 44. Check multiple defaults in switch (TS1113)
        checkMultipleDefaults()
        // 45. Check interface property initializers (TS1246)
        checkInterfacePropertyInitializers()
        // 46. Check await in non-async context (TS1308)
        checkAwaitContext()
        // 47. Check declaration name conflicts with built-in global (TS2397)
        checkBuiltinGlobalConflict()
        // 48. Check parameter question mark with initializer (TS1015)
        checkOptionalParamWithInitializer()
        // 49. Check set accessor parameter initializer (TS1052)
        checkSetAccessorInitializer()
        // 50. Check statements in ambient contexts (TS1036)
        checkAmbientStatements()
        // 51. Check parameter initializer in non-implementation context (TS2371)
        checkParameterInitializerInNonImpl()
        // 52. Check strict mode reserved words as identifiers (TS1212)
        checkStrictModeReservedWords()
        // 53. Check class/interface named 'undefined' (TS2414/TS2427)
        checkUndefinedClassInterfaceName()
        // 54. Check multiple default exports (TS2528)
        checkMultipleDefaultExports()
        // 55. Check derived class constructor must contain super call (TS2377)
        checkDerivedConstructorSuper()
        // 56. Check circular import alias definitions (TS2303)
        checkCircularImportAlias()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns all diagnostics produced by the checker. */
    fun getDiagnostics(): List<Diagnostic> = diagnostics.toList()

    // -----------------------------------------------------------------------
    // Public API — called by Transformer
    // -----------------------------------------------------------------------

    /**
     * Check if an import/export alias is referenced in value positions.
     * If not referenced, the import can be elided from JS output.
     */
    fun isReferencedAliasDeclaration(node: Node): Boolean {
        val key = nodeKey(node)
        for (result in binderResults) {
            val symbol = result.nodeToSymbol[key]
            if (symbol != null) {
                // Check if this specific symbol is referenced
                if (symbol.id in referencedAliases) return true
                // For ImportDeclarations with named imports, check if ANY specifier is referenced
                if (node is ImportDeclaration) {
                    val clause = node.importClause ?: return false
                    val bindings = clause.namedBindings
                    if (bindings is NamedImports) {
                        return bindings.elements.any { spec ->
                            val specSymbol = result.nodeToSymbol[nodeKey(spec)]
                            specSymbol != null && specSymbol.id in referencedAliases
                        }
                    }
                    // Default import or namespace import
                    return false
                }
                return false
            }
        }
        return true // safe default: keep the import
    }

    /**
     * Check if an export assignment refers to a value (not just a type).
     */
    fun isValueAliasDeclaration(node: Node): Boolean {
        if (node !is ExportAssignment) return true
        val expr = node.expression
        if (expr is Identifier) {
            val symbol = resolveIdentifierInFile(expr.text, node)
            if (symbol != null) {
                return symbol.flags.hasAny(SymbolFlags.Value)
            }
        }
        return true // expressions are always values
    }

    /**
     * Check if a re-exported name from a module specifier is a value (not type-only).
     * Used for `export { name } from "module"` — elide specifiers that are type-only
     * (interfaces, type aliases, non-instantiated namespaces).
     */
    fun isValueExport(name: String, moduleSpecifier: String, sourceFileName: String): Boolean {
        val result = fileResults[sourceFileName] ?: return true
        val targetFile = resolveModuleSpecifier(moduleSpecifier, null) ?: return true
        val targetResult = fileResults[targetFile] ?: return true
        val symbol = targetResult.locals[name] ?: return true // safe default: keep
        val resolved = resolveAlias(symbol)
        // Check if the symbol has value flags
        if (resolved.flags.hasAny(SymbolFlags.Value)) return true
        // Non-instantiated namespaces (no value content) are type-only
        if (resolved.flags.hasAny(SymbolFlags.Module) && !resolved.flags.hasAny(SymbolFlags.Value)) {
            // Check module instance state from binder
            for (br in binderResults) {
                for (decl in resolved.declarations) {
                    if (decl is ModuleDeclaration) {
                        val state = br.moduleInstanceStates[nodeKey(decl)]
                        if (state == ModuleInstanceState.Instantiated) return true
                    }
                }
            }
            return false // non-instantiated namespace
        }
        return false
    }

    /**
     * Get the constant value of an enum member node.
     */
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

    /**
     * Resolve a const enum member access like `E.A` to its constant value.
     * Returns null if `enumName` is not a const enum or `memberName` is not found.
     */
    fun resolveConstEnumMemberAccess(
        enumName: String,
        memberName: String,
        sourceFileName: String,
    ): ConstantValue? {
        val result = fileResults[sourceFileName] ?: return null
        val symbol = resolveNamePath(enumName, result) ?: return null
        val target = resolveAlias(symbol)
        if (!target.flags.hasAny(SymbolFlags.ConstEnum)) return null
        return enumValues[target.id]?.get(memberName)
    }

    /**
     * Check if a name resolves to a const enum (directly or through alias chains).
     * Used for eliding import aliases to const enums after inlining.
     */
    fun isConstEnumAlias(name: String, sourceFileName: String): Boolean {
        val result = fileResults[sourceFileName] ?: return false
        val symbol = result.locals[name] ?: globals[name] ?: return false
        val target = resolveAlias(symbol)
        return target.flags.hasAny(SymbolFlags.ConstEnum)
    }

    /**
     * Resolve an enum member value through import aliases.
     * Works for both const and non-const enums. Returns the numeric value or null.
     */
    fun resolveEnumMemberValue(
        enumName: String,
        memberName: String,
        sourceFileName: String,
    ): Long? {
        val result = fileResults[sourceFileName] ?: return null
        val symbol = resolveNamePath(enumName, result) ?: return null
        val target = resolveAlias(symbol)
        if (!target.flags.hasAny(SymbolFlags.Enum)) return null
        val value = enumValues[target.id]?.get(memberName)
        return when (value) {
            is ConstantValue.NumberValue -> value.value.toLong()
            else -> null
        }
    }

    /**
     * Get the module instance state for a module/namespace declaration.
     */
    fun getModuleInstanceState(node: ModuleDeclaration): ModuleInstanceState {
        val key = nodeKey(node)
        for (result in binderResults) {
            val state = result.moduleInstanceStates[key]
            if (state != null) return state
        }
        return ModuleInstanceState.Instantiated // safe default
    }

    // -----------------------------------------------------------------------
    // Symbol table merging
    // -----------------------------------------------------------------------

    private fun mergeSymbolTable(target: SymbolTable, source: SymbolTable) {
        for ((name, symbol) in source) {
            val existing = target[name]
            if (existing != null) {
                // Merge: combine flags and declarations
                existing.flags = existing.flags or symbol.flags
                existing.declarations.addAll(symbol.declarations)
                if (existing.valueDeclaration == null && symbol.valueDeclaration != null) {
                    existing.valueDeclaration = symbol.valueDeclaration
                }
                // Merge enum exports
                if (symbol.exports != null) {
                    if (existing.exports == null) existing.exports = symbolTable()
                    mergeSymbolTable(existing.exports!!, symbol.exports!!)
                }
            } else {
                target[name] = symbol
            }
        }
    }

    // -----------------------------------------------------------------------
    // Enum value computation
    // -----------------------------------------------------------------------

    private fun computeAllEnumValues() {
        for (result in binderResults) {
            for ((_, symbol) in result.locals) {
                computeEnumValuesRecursive(symbol)
            }
        }
    }

    private fun computeEnumValuesRecursive(symbol: Symbol) {
        if (symbol.flags.hasAny(SymbolFlags.Enum)) {
            computeEnumSymbolValues(symbol)
        }
        // Recurse into namespace exports to find nested enums
        if (symbol.flags.hasAny(SymbolFlags.Module)) {
            symbol.exports?.values?.forEach { computeEnumValuesRecursive(it) }
        }
    }

    private fun computeEnumSymbolValues(symbol: Symbol) {
        if (enumValues.containsKey(symbol.id)) return
        val values = mutableMapOf<String, ConstantValue>()
        enumValues[symbol.id] = values

        for (decl in symbol.declarations) {
            if (decl !is EnumDeclaration) continue
            var autoValue = 0.0
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
                    } else {
                        // Cannot evaluate — auto-increment is broken
                        autoValue = Double.NaN
                    }
                } else {
                    if (!autoValue.isNaN()) {
                        values[name] = ConstantValue.NumberValue(autoValue)
                        autoValue++
                    }
                }
            }
        }
    }

    private fun evaluateEnumInitializer(
        expr: Expression,
        currentValues: Map<String, ConstantValue>,
        currentEnum: Symbol,
    ): ConstantValue? {
        return when (expr) {
            is NumericLiteralNode -> {
                val value = expr.text.toDoubleOrNull() ?: return null
                ConstantValue.NumberValue(value)
            }
            is StringLiteralNode -> ConstantValue.StringValue(expr.text)
            is PrefixUnaryExpression -> {
                val operand = evaluateEnumInitializer(expr.operand, currentValues, currentEnum)
                    ?: return null
                if (operand !is ConstantValue.NumberValue) return null
                when (expr.operator) {
                    SyntaxKind.Plus -> operand
                    SyntaxKind.Minus -> ConstantValue.NumberValue(-operand.value)
                    SyntaxKind.Tilde -> ConstantValue.NumberValue(
                        operand.value.toLong().inv().toDouble()
                    )
                    else -> null
                }
            }
            is BinaryExpression -> {
                val left = evaluateEnumInitializer(expr.left, currentValues, currentEnum)
                    ?: return null
                val right = evaluateEnumInitializer(expr.right, currentValues, currentEnum)
                    ?: return null
                if (left is ConstantValue.NumberValue && right is ConstantValue.NumberValue) {
                    evaluateNumericBinary(left.value, expr.operator, right.value)
                } else if (left is ConstantValue.StringValue && right is ConstantValue.StringValue
                    && expr.operator == SyntaxKind.Plus) {
                    ConstantValue.StringValue(left.value + right.value)
                } else null
            }
            is ParenthesizedExpression -> {
                evaluateEnumInitializer(expr.expression, currentValues, currentEnum)
            }
            is Identifier -> {
                // Reference to another member in the same enum
                currentValues[expr.text]
            }
            is PropertyAccessExpression -> {
                // Reference to another enum's member: E.A or A.B.C.E.V1
                val memberName = expr.name.text
                val targetEnum = resolveEnumExpression(expr.expression, currentEnum.name)
                if (targetEnum != null) {
                    if (targetEnum.name == currentEnum.name && targetEnum.id == currentEnum.id) {
                        currentValues[memberName]
                    } else {
                        if (!targetEnum.flags.hasAny(SymbolFlags.Enum)) return null
                        computeEnumSymbolValues(targetEnum) // ensure computed
                        enumValues[targetEnum.id]?.get(memberName)
                    }
                } else {
                    // Simple case: Identifier.member
                    val objName = (expr.expression as? Identifier)?.text ?: return null
                    if (objName == currentEnum.name) {
                        currentValues[memberName]
                    } else {
                        val symbol = globals[objName] ?: return null
                        val target = resolveAlias(symbol)
                        if (!target.flags.hasAny(SymbolFlags.Enum)) return null
                        computeEnumSymbolValues(target)
                        enumValues[target.id]?.get(memberName)
                    }
                }
            }
            is ElementAccessExpression -> {
                // E["member"] or E[`member`] — element access to enum member
                val keyStr = when (val k = expr.argumentExpression) {
                    is StringLiteralNode -> k.text
                    is NoSubstitutionTemplateLiteralNode -> k.text
                    else -> return null
                }
                val objName = (expr.expression as? Identifier)?.text
                if (objName != null) {
                    if (objName == currentEnum.name) {
                        currentValues[keyStr]
                    } else {
                        val symbol = globals[objName] ?: return null
                        val target = resolveAlias(symbol)
                        if (!target.flags.hasAny(SymbolFlags.Enum)) return null
                        computeEnumSymbolValues(target)
                        enumValues[target.id]?.get(keyStr)
                    }
                } else {
                    // Nested: A.B.C.E["V2"]
                    val targetEnum = resolveEnumExpression(expr.expression, currentEnum.name)
                        ?: return null
                    if (targetEnum.name == currentEnum.name && targetEnum.id == currentEnum.id) {
                        currentValues[keyStr]
                    } else {
                        if (!targetEnum.flags.hasAny(SymbolFlags.Enum)) return null
                        computeEnumSymbolValues(targetEnum)
                        enumValues[targetEnum.id]?.get(keyStr)
                    }
                }
            }
            is NoSubstitutionTemplateLiteralNode -> {
                ConstantValue.StringValue(expr.text)
            }
            is TemplateExpression -> {
                // Template literal: `prefix${expr}suffix`
                val sb = StringBuilder(expr.head.text)
                for (span in expr.templateSpans) {
                    val spanValue = evaluateEnumInitializer(span.expression, currentValues, currentEnum)
                        ?: return null
                    sb.append(
                        when (spanValue) {
                            is ConstantValue.StringValue -> spanValue.value
                            is ConstantValue.NumberValue -> spanValue.toString()
                        }
                    )
                    // The literal part is a StringLiteralNode (template middle/tail)
                    val literalText = (span.literal as? StringLiteralNode)?.text
                        ?: (span.literal as? NoSubstitutionTemplateLiteralNode)?.text
                        ?: ""
                    sb.append(literalText)
                }
                ConstantValue.StringValue(sb.toString())
            }
            else -> null
        }
    }

    private fun evaluateNumericBinary(
        left: Double,
        operator: SyntaxKind,
        right: Double,
    ): ConstantValue? {
        val result = when (operator) {
            SyntaxKind.Plus -> left + right
            SyntaxKind.Minus -> left - right
            SyntaxKind.Asterisk -> left * right
            SyntaxKind.Slash -> left / right
            SyntaxKind.Percent -> left % right
            SyntaxKind.AsteriskAsterisk -> left.pow(right)
            SyntaxKind.Bar -> (left.toLong() or right.toLong()).toDouble()
            SyntaxKind.Ampersand -> (left.toLong() and right.toLong()).toDouble()
            SyntaxKind.Caret -> (left.toLong() xor right.toLong()).toDouble()
            SyntaxKind.LessThanLessThan -> (left.toLong() shl right.toInt()).toDouble()
            SyntaxKind.GreaterThanGreaterThan -> (left.toLong() shr right.toInt()).toDouble()
            SyntaxKind.GreaterThanGreaterThanGreaterThan ->
                (left.toLong().toInt().ushr(right.toInt())).toDouble()
            else -> return null
        }
        return ConstantValue.NumberValue(result)
    }

    // -----------------------------------------------------------------------
    // Import reference tracking
    // -----------------------------------------------------------------------

    private fun trackAllImportReferences() {
        for (result in binderResults) {
            trackReferencesInStatements(result.sourceFile.statements, result)
        }
    }

    private fun trackReferencesInStatements(statements: List<Statement>, result: BinderResult) {
        for (stmt in statements) {
            trackReferencesInStatement(stmt, result)
        }
    }

    private fun trackReferencesInStatement(stmt: Statement, result: BinderResult) {
        when (stmt) {
            // Skip type-only declarations (no value references inside)
            is InterfaceDeclaration -> return
            is TypeAliasDeclaration -> return
            // Skip declare statements (ambient — no runtime)
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                // Walk body for value references
                stmt.body?.let { trackReferencesInStatements(it.statements, result) }
                // Walk parameter initializers
                for (param in stmt.parameters) {
                    param.initializer?.let { trackReferencesInExpression(it, result) }
                    // Decorators are value references
                    param.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
                }
                // Decorators on the function itself are irrelevant (only classes/methods have them)
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                // extends clause is a value reference
                stmt.heritageClauses?.forEach { clause ->
                    if (clause.token == SyntaxKind.ExtendsKeyword) {
                        for (type in clause.types) {
                            trackReferencesInExpression(type.expression, result)
                        }
                    }
                    // implements clause is type-only — skip
                }
                // Decorators are value references
                stmt.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
                // Walk class members
                for (member in stmt.members) {
                    trackReferencesInClassElement(member, result)
                }
            }
            is EnumDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                // Enum member initializers are value references
                for (member in stmt.members) {
                    member.initializer?.let { trackReferencesInExpression(it, result) }
                }
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                when (val body = stmt.body) {
                    is ModuleBlock -> trackReferencesInStatements(body.statements, result)
                    is ModuleDeclaration -> trackReferencesInStatement(body, result)
                    else -> {}
                }
            }
            is VariableStatement -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { trackReferencesInExpression(it, result) }
                }
            }
            is ExpressionStatement -> {
                trackReferencesInExpression(stmt.expression, result)
            }
            is ReturnStatement -> {
                stmt.expression?.let { trackReferencesInExpression(it, result) }
            }
            is IfStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                trackReferencesInStatement(stmt.thenStatement, result)
                stmt.elseStatement?.let { trackReferencesInStatement(it, result) }
            }
            is Block -> trackReferencesInStatements(stmt.statements, result)
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let { trackReferencesInExpression(it, result) }
                        }
                    }
                    is Expression -> trackReferencesInExpression(init, result)
                    else -> {}
                }
                stmt.condition?.let { trackReferencesInExpression(it, result) }
                stmt.incrementor?.let { trackReferencesInExpression(it, result) }
                trackReferencesInStatement(stmt.statement, result)
            }
            is ForInStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                trackReferencesInStatement(stmt.statement, result)
            }
            is ForOfStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                trackReferencesInStatement(stmt.statement, result)
            }
            is WhileStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                trackReferencesInStatement(stmt.statement, result)
            }
            is DoStatement -> {
                trackReferencesInStatement(stmt.statement, result)
                trackReferencesInExpression(stmt.expression, result)
            }
            is SwitchStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            trackReferencesInExpression(clause.expression, result)
                            trackReferencesInStatements(clause.statements, result)
                        }
                        is DefaultClause -> {
                            trackReferencesInStatements(clause.statements, result)
                        }
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> {
                stmt.expression?.let { trackReferencesInExpression(it, result) }
            }
            is TryStatement -> {
                trackReferencesInStatements(stmt.tryBlock.statements, result)
                stmt.catchClause?.let {
                    trackReferencesInStatements(it.block.statements, result)
                }
                stmt.finallyBlock?.let {
                    trackReferencesInStatements(it.statements, result)
                }
            }
            is LabeledStatement -> {
                trackReferencesInStatement(stmt.statement, result)
            }
            is WithStatement -> {
                trackReferencesInExpression(stmt.expression, result)
                trackReferencesInStatement(stmt.statement, result)
            }
            is ExportDeclaration -> {
                // export { X } — X is a value reference if not type-only
                if (!stmt.isTypeOnly) {
                    when (val clause = stmt.exportClause) {
                        is NamedExports -> {
                            for (spec in clause.elements) {
                                if (!spec.isTypeOnly) {
                                    val name = spec.propertyName?.text ?: spec.name.text
                                    markAliasReferenced(name, result)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            is ExportAssignment -> {
                trackReferencesInExpression(stmt.expression, result)
            }
            is ImportDeclaration -> { /* imports don't create value references */ }
            is ImportEqualsDeclaration -> { /* handled separately */ }
            else -> { /* other statements */ }
        }
    }

    private fun trackReferencesInClassElement(element: ClassElement, result: BinderResult) {
        when (element) {
            is PropertyDeclaration -> {
                if (ModifierFlag.Declare !in element.modifiers) {
                    element.initializer?.let { trackReferencesInExpression(it, result) }
                }
                element.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
            }
            is MethodDeclaration -> {
                if (ModifierFlag.Declare !in element.modifiers) {
                    element.body?.let { trackReferencesInStatements(it.statements, result) }
                    for (param in element.parameters) {
                        param.initializer?.let { trackReferencesInExpression(it, result) }
                        param.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
                    }
                }
                element.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
            }
            is Constructor -> {
                element.body?.let { trackReferencesInStatements(it.statements, result) }
                for (param in element.parameters) {
                    param.initializer?.let { trackReferencesInExpression(it, result) }
                    param.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
                }
            }
            is GetAccessor -> {
                element.body?.let { trackReferencesInStatements(it.statements, result) }
                element.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
            }
            is SetAccessor -> {
                element.body?.let { trackReferencesInStatements(it.statements, result) }
                element.decorators?.forEach { trackReferencesInExpression(it.expression, result) }
            }
            is ClassStaticBlockDeclaration -> {
                trackReferencesInStatements(element.body.statements, result)
            }
            else -> { /* IndexSignature, SemicolonClassElement — no value refs */ }
        }
    }

    private fun trackReferencesInExpression(expr: Expression, result: BinderResult) {
        when (expr) {
            is Identifier -> {
                markAliasReferenced(expr.text, result)
            }
            is PropertyAccessExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is ElementAccessExpression -> {
                trackReferencesInExpression(expr.expression, result)
                trackReferencesInExpression(expr.argumentExpression, result)
            }
            is CallExpression -> {
                trackReferencesInExpression(expr.expression, result)
                for (arg in expr.arguments) {
                    trackReferencesInExpression(arg, result)
                }
            }
            is NewExpression -> {
                trackReferencesInExpression(expr.expression, result)
                expr.arguments?.forEach { trackReferencesInExpression(it, result) }
            }
            is BinaryExpression -> {
                // Iterative traversal to avoid StackOverflow on deeply nested binaries
                var current: Expression = expr
                while (current is BinaryExpression) {
                    trackReferencesInExpression(current.right, result)
                    current = current.left
                }
                trackReferencesInExpression(current, result)
            }
            is ConditionalExpression -> {
                trackReferencesInExpression(expr.condition, result)
                trackReferencesInExpression(expr.whenTrue, result)
                trackReferencesInExpression(expr.whenFalse, result)
            }
            is PrefixUnaryExpression -> {
                trackReferencesInExpression(expr.operand, result)
            }
            is PostfixUnaryExpression -> {
                trackReferencesInExpression(expr.operand, result)
            }
            is ParenthesizedExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is ArrayLiteralExpression -> {
                for (element in expr.elements) {
                    trackReferencesInExpression(element, result)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            trackReferencesInExpression(prop.initializer, result)
                            val propName = prop.name
                            if (propName is ComputedPropertyName) {
                                trackReferencesInExpression(propName.expression, result)
                            }
                        }
                        is ShorthandPropertyAssignment -> {
                            markAliasReferenced(prop.name.text, result)
                        }
                        is SpreadAssignment -> {
                            trackReferencesInExpression(prop.expression, result)
                        }
                        else -> {}
                    }
                }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> trackReferencesInStatements(body.statements, result)
                    is Expression -> trackReferencesInExpression(body, result)
                    else -> {}
                }
                for (param in expr.parameters) {
                    param.initializer?.let { trackReferencesInExpression(it, result) }
                }
            }
            is FunctionExpression -> {
                trackReferencesInStatements(expr.body.statements, result)
                for (param in expr.parameters) {
                    param.initializer?.let { trackReferencesInExpression(it, result) }
                }
            }
            is ClassExpression -> {
                expr.heritageClauses?.forEach { clause ->
                    if (clause.token == SyntaxKind.ExtendsKeyword) {
                        for (type in clause.types) {
                            trackReferencesInExpression(type.expression, result)
                        }
                    }
                }
                for (member in expr.members) {
                    trackReferencesInClassElement(member, result)
                }
            }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    trackReferencesInExpression(span.expression, result)
                }
            }
            is TaggedTemplateExpression -> {
                trackReferencesInExpression(expr.tag, result)
                when (val template = expr.template) {
                    is TemplateExpression -> {
                        for (span in template.templateSpans) {
                            trackReferencesInExpression(span.expression, result)
                        }
                    }
                    else -> {}
                }
            }
            is SpreadElement -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is AwaitExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is YieldExpression -> {
                expr.expression?.let { trackReferencesInExpression(it, result) }
            }
            is DeleteExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is TypeOfExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is VoidExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is AsExpression -> {
                // The expression part is a value reference, but the type part is not
                trackReferencesInExpression(expr.expression, result)
            }
            is SatisfiesExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is NonNullExpression -> {
                trackReferencesInExpression(expr.expression, result)
            }
            is TypeAssertionExpression -> {
                // <Type>expr — expr is a value reference
                trackReferencesInExpression(expr.expression, result)
            }
            is CommaListExpression -> {
                for (e in expr.elements) {
                    trackReferencesInExpression(e, result)
                }
            }
            is MetaProperty -> { /* import.meta, new.target — no named references */ }
            // Literals — no references
            is StringLiteralNode -> {}
            is NumericLiteralNode -> {}
            is BigIntLiteralNode -> {}
            is RegularExpressionLiteralNode -> {}
            is NoSubstitutionTemplateLiteralNode -> {}
            is OmittedExpression -> {}
            // Binding patterns (in destructuring expressions)
            is ObjectBindingPattern -> {}
            is ArrayBindingPattern -> {}
            is ComputedPropertyName -> {
                trackReferencesInExpression(expr.expression, result)
            }
            // JSX expressions
            is JsxElement -> {
                trackReferencesInExpression(expr.openingElement.tagName, result)
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { trackReferencesInExpression(it, result) }
                    } else if (child is Expression) {
                        trackReferencesInExpression(child, result)
                    }
                }
            }
            is JsxSelfClosingElement -> {
                trackReferencesInExpression(expr.tagName, result)
                for (attr in expr.attributes) {
                    when (attr) {
                        is JsxAttribute -> {
                            val v = attr.value
                            if (v is JsxExpressionContainer) {
                                v.expression?.let { trackReferencesInExpression(it, result) }
                            } else if (v is Expression) {
                                trackReferencesInExpression(v, result)
                            }
                        }
                        is JsxSpreadAttribute -> trackReferencesInExpression(attr.expression, result)
                        else -> {}
                    }
                }
            }
            is JsxFragment -> {
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { trackReferencesInExpression(it, result) }
                    } else if (child is Expression) {
                        trackReferencesInExpression(child, result)
                    }
                }
            }
        }
    }

    /**
     * Mark an alias symbol as referenced if the given name resolves to an import.
     */
    private fun markAliasReferenced(name: String, result: BinderResult) {
        val symbol = result.locals[name] ?: return
        if (symbol.flags.hasAny(SymbolFlags.Alias)) {
            referencedAliases.add(symbol.id)
        }
    }

    // -----------------------------------------------------------------------
    // Symbol resolution helpers
    // -----------------------------------------------------------------------

    private fun resolveIdentifierInFile(name: String, contextNode: Node): Symbol? {
        for (result in binderResults) {
            val symbol = result.locals[name]
            if (symbol != null) return symbol
        }
        return globals[name]
    }

    private fun resolveAlias(symbol: Symbol, visited: MutableSet<Int> = mutableSetOf()): Symbol {
        if (!visited.add(symbol.id)) return symbol // cycle detected
        if (symbol.target != null) return resolveAlias(symbol.target!!, visited)
        // For import aliases, try to resolve the target
        if (symbol.flags.hasAny(SymbolFlags.Alias)) {
            for (decl in symbol.declarations) {
                when (decl) {
                    is ImportEqualsDeclaration -> {
                        val ref = decl.moduleReference
                        when (ref) {
                            is QualifiedName -> {
                                val target = resolveQualifiedName(ref) ?: continue
                                symbol.target = target
                                return resolveAlias(target, visited)
                            }
                            is Identifier -> {
                                val target = globals[ref.text] ?: continue
                                symbol.target = target
                                return resolveAlias(target, visited)
                            }
                            is ExternalModuleReference -> {
                                // import A = require("mod") — resolve module then its export
                                val specifier = (ref.expression as? StringLiteralNode)?.text ?: continue
                                val targetFile = resolveModuleSpecifier(specifier, decl) ?: continue
                                val targetResult = fileResults[targetFile] ?: continue
                                // Look for export = X in the target module
                                val exportTarget = resolveModuleExportAssignment(targetResult, visited)
                                if (exportTarget != null) {
                                    symbol.target = exportTarget
                                    return resolveAlias(exportTarget, visited)
                                }
                                // No export = found — create module symbol
                                val moduleSymbol = createModuleSymbol(symbol.name, targetResult)
                                symbol.target = moduleSymbol
                                return moduleSymbol
                            }
                            else -> {}
                        }
                    }
                    is ImportDeclaration -> {
                        val specifier = (decl.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                        val targetFile = resolveModuleSpecifier(specifier, decl) ?: continue
                        val targetResult = fileResults[targetFile] ?: continue

                        // Namespace import: import * as Foo from "mod"
                        val namedBindings = decl.importClause?.namedBindings
                        if (namedBindings is NamespaceImport) {
                            val moduleSymbol = createModuleSymbol(symbol.name, targetResult)
                            symbol.target = moduleSymbol
                            return moduleSymbol
                        }

                        // Default import: import Foo from "mod"
                        if (decl.importClause?.name != null &&
                            symbol.name == decl.importClause?.name?.text) {
                            // Look for "default" export in target
                            val target = targetResult.locals["default"] ?: continue
                            symbol.target = target
                            return resolveAlias(target, visited)
                        }

                        // Named import: import { X } from "mod"
                        val target = targetResult.locals[symbol.name] ?: continue
                        symbol.target = target
                        return resolveAlias(target, visited)
                    }
                    is ImportSpecifier -> {
                        // Named import — the original name to look up
                        val originalName = decl.propertyName?.text ?: decl.name.text
                        // Find the ImportDeclaration parent for this specifier
                        // Since we don't have parent pointers, search all files
                        for (result in binderResults) {
                            for (stmt in result.sourceFile.statements) {
                                if (stmt is ImportDeclaration) {
                                    val bindings = stmt.importClause?.namedBindings
                                    if (bindings is NamedImports && decl in bindings.elements) {
                                        val specifier2 = (stmt.moduleSpecifier as? StringLiteralNode)?.text
                                            ?: continue
                                        val targetFile2 = resolveModuleSpecifier(specifier2, stmt) ?: continue
                                        val targetResult2 = fileResults[targetFile2] ?: continue
                                        val target = targetResult2.locals[originalName] ?: continue
                                        symbol.target = target
                                        return resolveAlias(target, visited)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return symbol
    }

    /**
     * Resolve a module's export assignment (`export = expr`) to a symbol.
     * Returns null if no export assignment exists.
     */
    private fun resolveModuleExportAssignment(result: BinderResult, visited: MutableSet<Int> = mutableSetOf()): Symbol? {
        for (stmt in result.sourceFile.statements) {
            if (stmt is ExportAssignment && stmt.isExportEquals) {
                return resolveExpressionToSymbol(stmt.expression, result, visited)
            }
        }
        return null
    }

    /**
     * Resolve an expression to a symbol (for export assignment resolution).
     */
    private fun resolveExpressionToSymbol(expr: Expression, result: BinderResult, visited: MutableSet<Int> = mutableSetOf()): Symbol? {
        return when (expr) {
            is Identifier -> {
                val symbol = result.locals[expr.text] ?: globals[expr.text] ?: return null
                resolveAlias(symbol, visited)
            }
            is PropertyAccessExpression -> {
                val parent = resolveExpressionToSymbol(expr.expression, result, visited) ?: return null
                val child = parent.exports?.get(expr.name.text) ?: return null
                resolveAlias(child, visited)
            }
            else -> null
        }
    }

    /**
     * Create a synthetic module symbol whose exports are the target file's locals.
     * Used for namespace imports (`import * as Foo from "mod"`).
     */
    private fun createModuleSymbol(name: String, targetResult: BinderResult): Symbol {
        val moduleSymbol = Symbol(
            name = name,
            flags = SymbolFlags.Module,
        )
        moduleSymbol.exports = targetResult.locals
        return moduleSymbol
    }

    /**
     * Resolve a QualifiedName (e.g., A.B.C.E) to a symbol by walking the namespace chain.
     */
    /**
     * Resolve an expression to an enum symbol for enum member access.
     * Handles nested namespace access like A.B.C.E → the symbol for enum E.
     */
    private fun resolveEnumExpression(expr: Expression, currentEnumName: String): Symbol? {
        return when (expr) {
            is Identifier -> {
                val symbol = globals[expr.text] ?: return null
                resolveAlias(symbol)
            }
            is PropertyAccessExpression -> {
                val parent = resolveEnumExpression(expr.expression, currentEnumName) ?: return null
                val child = parent.exports?.get(expr.name.text) ?: return null
                resolveAlias(child)
            }
            else -> null
        }
    }

    /**
     * Resolve a dotted name path (e.g., "A.B.C.E") to a symbol by walking the namespace chain.
     */
    private fun resolveNamePath(path: String, result: BinderResult): Symbol? {
        val parts = path.split(".")
        var current = result.locals[parts[0]] ?: globals[parts[0]] ?: return null
        for (i in 1 until parts.size) {
            current = resolveAlias(current)
            current = current.exports?.get(parts[i]) ?: return null
        }
        return current
    }

    private fun resolveQualifiedName(qn: QualifiedName): Symbol? {
        val left = when (val l = qn.left) {
            is Identifier -> globals[l.text]
            is QualifiedName -> resolveQualifiedName(l)
            else -> null
        } ?: return null
        val resolved = resolveAlias(left)
        return resolved.exports?.get(qn.right.text)
    }

    /**
     * Simple module specifier resolution: strip leading `./` and append `.ts` / try `.ts`.
     * This is a simplified version for the test suite where module specifiers
     * are relative paths within the same test compilation unit.
     */
    private fun resolveModuleSpecifier(specifier: String, contextNode: Node? = null): String? {
        val baseName = specifier.removePrefix("./").removePrefix("../")
        // Try exact match first, then with extensions
        val candidates = listOf(
            baseName,
            "$baseName.ts",
            "$baseName.tsx",
            "./$baseName",
            "./$baseName.ts",
            "./$baseName.tsx",
        )
        for (candidate in candidates) {
            if (candidate in fileResults) return candidate
        }
        // Try matching by base filename
        for (fileName in fileResults.keys) {
            val fileBase = fileName.removePrefix("./").removeSuffix(".ts").removeSuffix(".tsx")
            if (fileBase == baseName) return fileName
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Unused declaration checking (TS6133/TS6196)
    // -----------------------------------------------------------------------

    /**
     * Tracks declarations and references within a scope for unused checking.
     */
    private class UnusedScope {
        /** All declarations in this scope: name → list of (declaration node, name node, kind). */
        val declarations = mutableListOf<UnusedDecl>()
        /** Names referenced in this scope or any nested scope. */
        val referencedNames = mutableSetOf<String>()
    }

    private data class UnusedDecl(
        val name: String,
        val nameNode: Node,       // Node whose pos gives the error position
        val declNode: Node,       // The full declaration node
        val spanLength: Int = 0,  // Explicit squiggle length (0 = use name.length)
        val isExported: Boolean,
        val isParameter: Boolean,
        val isTypeOnly: Boolean,  // interface, type alias
        val stmtIndex: Int = -1,  // index in parent statement list (for self-reference detection)
        val parentVarStmt: VariableStatement? = null, // parent statement for TS6199 grouping
    )

    private fun checkUnusedDeclarations() {
        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val isModule = isModuleFile(result.sourceFile.statements)
            checkUnusedInStatements(
                result.sourceFile.statements,
                source,
                result.sourceFile.fileName,
                isTopLevel = true,
                isModuleScope = isModule,
            )
        }
    }

    /**
     * Check if a file is a module (has import/export statements).
     * Non-module files' top-level declarations are global and not checked for unused.
     */
    private fun isModuleFile(statements: List<Statement>): Boolean {
        for (stmt in statements) {
            when (stmt) {
                is ImportDeclaration -> return true
                is ImportEqualsDeclaration -> return true
                is ExportDeclaration -> return true
                is ExportAssignment -> return true
                else -> {
                    if (stmt is Declaration) {
                        val modifiers = when (stmt) {
                            is FunctionDeclaration -> stmt.modifiers
                            is ClassDeclaration -> stmt.modifiers
                            is VariableStatement -> stmt.modifiers
                            is EnumDeclaration -> stmt.modifiers
                            is InterfaceDeclaration -> stmt.modifiers
                            is TypeAliasDeclaration -> stmt.modifiers
                            is ModuleDeclaration -> stmt.modifiers
                            else -> emptySet()
                        }
                        if (ModifierFlag.Export in modifiers) return true
                    }
                }
            }
        }
        return false
    }

    private fun checkUnusedInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        isTopLevel: Boolean,
        isModuleScope: Boolean = false,
    ) {
        // Skip file-level declarations in non-module files (they're global)
        if (isTopLevel && !isModuleScope) {
            // Still recurse into nested scopes (namespace bodies, function bodies)
            for (stmt in statements) {
                checkUnusedInNestedScopes(stmt, source, fileName, siblingStatements = statements)
            }
            return
        }

        val scope = UnusedScope()

        // 1. Collect declarations (with statement index for self-reference detection)
        for ((idx, stmt) in statements.withIndex()) {
            collectUnusedDeclarations(stmt, scope, isTopLevel, stmtIndex = idx)
        }

        // 2. Collect references per statement (for self-reference detection)
        val refsPerStmt = Array(statements.size) { mutableSetOf<String>() }
        for ((idx, stmt) in statements.withIndex()) {
            val stmtScope = UnusedScope()
            collectUnusedReferences(stmt, stmtScope)
            refsPerStmt[idx] = stmtScope.referencedNames
            scope.referencedNames.addAll(stmtScope.referencedNames)
        }

        // 3. Report unreferenced declarations
        // A declaration is considered unused if:
        // - It's not referenced at all, OR
        // - It's only referenced from within its own declaration (self-reference)
        val unusedDecls = mutableListOf<UnusedDecl>()
        for (decl in scope.declarations) {
            val isExternallyReferenced = if (decl.stmtIndex >= 0) {
                refsPerStmt.withIndex().any { (idx, refs) ->
                    idx != decl.stmtIndex && decl.name in refs
                }
            } else {
                decl.name in scope.referencedNames
            }
            if (isExternallyReferenced) continue
            if (decl.name.startsWith("_")) continue
            if (decl.isExported) continue

            if (decl.isParameter) {
                if (!options.noUnusedParameters) continue
            } else {
                if (!options.noUnusedLocals) continue
            }

            unusedDecls.add(decl)
        }

        // Check for TS6199: if ALL declarations from a VariableStatement are unused,
        // emit a single "All variables are unused" instead of individual TS6133
        val ts6199Stmts = mutableSetOf<VariableStatement>()
        val declsByVarStmt = unusedDecls.filter { it.parentVarStmt != null }
            .groupBy { it.parentVarStmt!! }
        for ((varStmt, decls) in declsByVarStmt) {
            val totalDeclCount = varStmt.declarationList.declarations.size
            if (decls.size == totalDeclCount && totalDeclCount > 1) {
                ts6199Stmts.add(varStmt)
                // Emit TS6199 for the entire statement
                // Span covers from `var` keyword to the end of the statement line (including `;`)
                val stmtStart = varStmt.pos
                val lineEnd = source.indexOf('\n', stmtStart).let { if (it < 0) source.length else it }
                val spanLength = source.substring(stmtStart, lineEnd).trimEnd().length
                val (line, character) = getLineAndCharacterOfPosition(source, stmtStart)
                diagnostics.add(Diagnostic(
                    message = "All variables are unused.",
                    category = DiagnosticCategory.Error,
                    code = 6199,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = stmtStart,
                    length = spanLength,
                ))
            }
        }

        for (decl in unusedDecls) {
            // Skip declarations already handled by TS6199
            if (decl.parentVarStmt != null && decl.parentVarStmt in ts6199Stmts) continue

            val nameNode = decl.nameNode
            val start = nameNode.pos
            // Compute squiggle length: for imports using whole-statement node,
            // measure the line text; otherwise use identifier text length
            val length = when {
                decl.spanLength > 0 -> decl.spanLength
                nameNode is ImportDeclaration -> {
                    // Squiggle covers the import statement up to semicolon (excluding comments)
                    val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                    var endPos = start
                    var idx = start
                    while (idx < lineEnd) {
                        val ch = source[idx]
                        if (ch == ';') { endPos = idx + 1; break }
                        if (ch == '/' && idx + 1 < lineEnd && (source[idx + 1] == '/' || source[idx + 1] == '*')) {
                            while (endPos > start && source[endPos - 1].let { it == ' ' || it == '\t' }) endPos--
                            break
                        }
                        endPos = idx + 1
                        idx++
                    }
                    if (endPos <= start) endPos = start + 1
                    endPos - start
                }
                else -> decl.name.length
            }
            val (line, character) = getLineAndCharacterOfPosition(source, start)

            // Classes, interfaces, type aliases, enums use TS6196 "declared but never used"
            // Variables, functions, parameters use TS6133 "declared but its value is never read"
            val isTypeDecl = decl.declNode is ClassDeclaration || decl.declNode is InterfaceDeclaration
                    || decl.declNode is TypeAliasDeclaration || decl.declNode is EnumDeclaration
            val code = if (isTypeDecl) 6196 else 6133
            val message = if (isTypeDecl) {
                "'${decl.name}' is declared but never used."
            } else {
                "'${decl.name}' is declared but its value is never read."
            }

            diagnostics.add(Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }

        // 4. Recurse into nested scopes (function bodies, class bodies, etc.)
        for (stmt in statements) {
            checkUnusedInNestedScopes(stmt, source, fileName, siblingStatements = statements)
        }
    }

    private fun collectUnusedDeclarations(
        stmt: Statement,
        scope: UnusedScope,
        isTopLevel: Boolean,
        stmtIndex: Int = -1,
    ) {
        when (stmt) {
            is VariableStatement -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                for (decl in stmt.declarationList.declarations) {
                    collectVarDeclNames(decl.name, decl, isExported, scope, stmtIndex, parentVarStmt = stmt)
                }
            }
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val name = stmt.name ?: return
                val isExported = ModifierFlag.Export in stmt.modifiers ||
                    ModifierFlag.Default in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val name = stmt.name ?: return
                val isExported = ModifierFlag.Export in stmt.modifiers ||
                    ModifierFlag.Default in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is InterfaceDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = true,
                    stmtIndex = stmtIndex,
                ))
            }
            is TypeAliasDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = true,
                    stmtIndex = stmtIndex,
                ))
            }
            is EnumDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val nameNode = stmt.name
                if (nameNode is Identifier) {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    scope.declarations.add(UnusedDecl(
                        name = nameNode.text,
                        nameNode = nameNode,
                        declNode = stmt,
                        isExported = isExported,
                        isParameter = false,
                        isTypeOnly = false,
                        stmtIndex = stmtIndex,
                    ))
                }
            }
            is ImportDeclaration -> {
                if (stmt.importClause?.isTypeOnly == true) return
                val clause = stmt.importClause ?: return
                val bindings = clause.namedBindings
                when (bindings) {
                    is NamedImports -> {
                        // For single-specifier imports: squiggle the entire import statement
                        // For multi-specifier imports: squiggle individual specifiers
                        for (spec in bindings.elements) {
                            if (spec.isTypeOnly) continue
                            val isSingleSpecifier = bindings.elements.size == 1
                            scope.declarations.add(UnusedDecl(
                                name = spec.name.text,
                                nameNode = if (isSingleSpecifier) stmt else spec,
                                declNode = stmt,
                                isExported = false,
                                isParameter = false,
                                isTypeOnly = false,
                                stmtIndex = stmtIndex,
                            ))
                        }
                    }
                    is NamespaceImport -> {
                        scope.declarations.add(UnusedDecl(
                            name = bindings.name.text,
                            nameNode = stmt,
                            declNode = stmt,
                            isExported = false,
                            isParameter = false,
                            isTypeOnly = false,
                            stmtIndex = stmtIndex,
                        ))
                    }
                    else -> {}
                }
                // Default import
                if (clause.name != null) {
                    scope.declarations.add(UnusedDecl(
                        name = clause.name.text,
                        nameNode = stmt,
                        declNode = stmt,
                        isExported = false,
                        isParameter = false,
                        isTypeOnly = false,
                        stmtIndex = stmtIndex,
                    ))
                }
            }
            is ImportEqualsDeclaration -> {
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            else -> {}
        }
    }

    private fun collectVarDeclNames(
        name: Expression,
        declNode: Node,
        isExported: Boolean,
        scope: UnusedScope,
        stmtIndex: Int = -1,
        parentVarStmt: VariableStatement? = null,
    ) {
        when (name) {
            is Identifier -> {
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = declNode,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                    parentVarStmt = parentVarStmt,
                ))
            }
            is ObjectBindingPattern -> {
                // When a rest element exists, non-rest siblings are intentional
                // extractions and should not be flagged as unused
                val hasRest = name.elements.any { it.dotDotDotToken }
                for (element in name.elements) {
                    if (hasRest && !element.dotDotDotToken) continue // skip extraction vars
                    collectVarDeclNames(element.name, element, isExported, scope, stmtIndex, parentVarStmt)
                }
            }
            is ArrayBindingPattern -> {
                val hasRest = name.elements.any { it is BindingElement && it.dotDotDotToken }
                for (element in name.elements) {
                    if (element is BindingElement) {
                        if (hasRest && !element.dotDotDotToken) continue // skip extraction vars
                        collectVarDeclNames(element.name, element, isExported, scope, stmtIndex, parentVarStmt)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Collect destructuring parameter binding element names for unused checking.
     * For `([a])`, collects `a` with span covering the binding pattern.
     */
    private fun collectDestructuringParamNames(
        pattern: Expression,
        param: Parameter,
        scope: UnusedScope,
    ) {
        // For destructuring parameters, the squiggle covers the entire binding pattern
        // (e.g., [a] or {a}). TypeScript uses the binding pattern node for the span.
        when (pattern) {
            is ArrayBindingPattern -> {
                for (element in pattern.elements) {
                    if (element is BindingElement) {
                        val name = element.name
                        if (name is Identifier && !name.text.startsWith("_")) {
                            // Span from '[' to ']' inclusive — end includes trailing trivia
                            scope.declarations.add(UnusedDecl(
                                name = name.text,
                                nameNode = pattern,
                                declNode = param,
                                spanLength = pattern.end - pattern.pos - 1,
                                isExported = false,
                                isParameter = true,
                                isTypeOnly = false,
                            ))
                        }
                    }
                }
            }
            is ObjectBindingPattern -> {
                for (element in pattern.elements) {
                    val name = element.name
                    if (name is Identifier && !name.text.startsWith("_")) {
                        scope.declarations.add(UnusedDecl(
                            name = name.text,
                            nameNode = pattern,
                            declNode = param,
                            spanLength = pattern.end - pattern.pos - 1,
                            isExported = false,
                            isParameter = true,
                            isTypeOnly = false,
                        ))
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Collect all name references from a statement (including nested expressions).
     * This is used for unused declaration checking — it marks names as "referenced"
     * when they appear in value or type positions.
     */
    private fun collectUnusedReferences(stmt: Statement, scope: UnusedScope) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { collectRefsFromExpr(it, scope) }
                    decl.type?.let { collectRefsFromType(it, scope) }
                }
            }
            is ExpressionStatement -> collectRefsFromExpr(stmt.expression, scope)
            is ReturnStatement -> stmt.expression?.let { collectRefsFromExpr(it, scope) }
            is IfStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.thenStatement, scope)
                stmt.elseStatement?.let { collectUnusedReferences(it, scope) }
            }
            is Block -> stmt.statements.forEach { collectUnusedReferences(it, scope) }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let { collectRefsFromExpr(it, scope) }
                        }
                    }
                    is Expression -> collectRefsFromExpr(init, scope)
                    else -> {}
                }
                stmt.condition?.let { collectRefsFromExpr(it, scope) }
                stmt.incrementor?.let { collectRefsFromExpr(it, scope) }
                collectUnusedReferences(stmt.statement, scope)
            }
            is ForInStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is ForOfStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is WhileStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is DoStatement -> {
                collectUnusedReferences(stmt.statement, scope)
                collectRefsFromExpr(stmt.expression, scope)
            }
            is SwitchStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            collectRefsFromExpr(clause.expression, scope)
                            clause.statements.forEach { collectUnusedReferences(it, scope) }
                        }
                        is DefaultClause -> {
                            clause.statements.forEach { collectUnusedReferences(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> stmt.expression?.let { collectRefsFromExpr(it, scope) }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectUnusedReferences(it, scope) }
                stmt.catchClause?.block?.statements?.forEach { collectUnusedReferences(it, scope) }
                stmt.finallyBlock?.statements?.forEach { collectUnusedReferences(it, scope) }
            }
            is LabeledStatement -> collectUnusedReferences(stmt.statement, scope)
            is WithStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is FunctionDeclaration -> {
                // References inside function bodies count as usage of outer scope names
                // Type parameters shadow outer names, so collect into inner scope first
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.body?.statements?.forEach { collectUnusedReferences(it, innerScope) }
                for (param in stmt.parameters) {
                    param.initializer?.let { collectRefsFromExpr(it, innerScope) }
                    param.type?.let { collectRefsFromType(it, innerScope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, innerScope) }
                }
                stmt.type?.let { collectRefsFromType(it, innerScope) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is ClassDeclaration -> {
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, innerScope)
                        type.typeArguments?.forEach { collectRefsFromType(it, innerScope) }
                    }
                }
                stmt.decorators?.forEach { collectRefsFromExpr(it.expression, innerScope) }
                for (member in stmt.members) {
                    collectRefsFromClassElement(member, innerScope)
                }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is InterfaceDeclaration -> {
                // Collect references from extends clause and members
                // Type parameters shadow outer names
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, innerScope)
                        type.typeArguments?.forEach { collectRefsFromType(it, innerScope) }
                    }
                }
                for (member in stmt.members) {
                    collectRefsFromClassElement(member, innerScope)
                }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is TypeAliasDeclaration -> {
                // Type parameters shadow outer names
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.type.let { collectRefsFromType(it, innerScope) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is EnumDeclaration -> {
                for (member in stmt.members) {
                    member.initializer?.let { collectRefsFromExpr(it, scope) }
                }
            }
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> body.statements.forEach { collectUnusedReferences(it, scope) }
                    is ModuleDeclaration -> collectUnusedReferences(body, scope)
                    else -> {}
                }
            }
            is ExportDeclaration -> {
                // export { X } — X is a reference
                when (val clause = stmt.exportClause) {
                    is NamedExports -> {
                        for (spec in clause.elements) {
                            val name = spec.propertyName?.text ?: spec.name.text
                            scope.referencedNames.add(name)
                        }
                    }
                    else -> {}
                }
            }
            is ExportAssignment -> {
                collectRefsFromExpr(stmt.expression, scope)
            }
            else -> {}
        }
    }

    private fun collectRefsFromExpr(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is Identifier -> scope.referencedNames.add(expr.text)
            is PropertyAccessExpression -> {
                collectRefsFromExpr(expr.expression, scope)
            }
            is ElementAccessExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromExpr(expr.argumentExpression, scope)
            }
            is CallExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                expr.arguments.forEach { collectRefsFromExpr(it, scope) }
                expr.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is NewExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                expr.arguments?.forEach { collectRefsFromExpr(it, scope) }
                expr.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    // Simple assignment: left side is write-only (not a read)
                    collectRefsFromExpr(expr.right, scope)
                    collectWriteTargetRefs(expr.left, scope)
                } else {
                    // Non-assignment or compound assignment: iterative traversal
                    var current: Expression = expr
                    while (current is BinaryExpression) {
                        collectRefsFromExpr(current.right, scope)
                        current = current.left
                    }
                    collectRefsFromExpr(current, scope)
                }
            }
            is ConditionalExpression -> {
                collectRefsFromExpr(expr.condition, scope)
                collectRefsFromExpr(expr.whenTrue, scope)
                collectRefsFromExpr(expr.whenFalse, scope)
            }
            is PrefixUnaryExpression -> collectRefsFromExpr(expr.operand, scope)
            is PostfixUnaryExpression -> collectRefsFromExpr(expr.operand, scope)
            is ParenthesizedExpression -> collectRefsFromExpr(expr.expression, scope)
            is ArrayLiteralExpression -> {
                expr.elements.forEach { collectRefsFromExpr(it, scope) }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            collectRefsFromExpr(prop.initializer, scope)
                            val propName = prop.name
                            if (propName is ComputedPropertyName) {
                                collectRefsFromExpr(propName.expression, scope)
                            }
                        }
                        is ShorthandPropertyAssignment -> {
                            scope.referencedNames.add(prop.name.text)
                        }
                        is SpreadAssignment -> collectRefsFromExpr(prop.expression, scope)
                        is MethodDeclaration -> {
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                            prop.parameters.forEach { param ->
                                param.initializer?.let { collectRefsFromExpr(it, scope) }
                            }
                        }
                        is GetAccessor -> {
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                        }
                        is SetAccessor -> {
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> body.statements.forEach { collectUnusedReferences(it, scope) }
                    is Expression -> collectRefsFromExpr(body, scope)
                    else -> {}
                }
                expr.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                expr.type?.let { collectRefsFromType(it, scope) }
            }
            is FunctionExpression -> {
                expr.body.statements.forEach { collectUnusedReferences(it, scope) }
                expr.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                expr.type?.let { collectRefsFromType(it, scope) }
            }
            is ClassExpression -> {
                expr.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, scope)
                        type.typeArguments?.forEach { collectRefsFromType(it, scope) }
                    }
                }
                for (member in expr.members) {
                    collectRefsFromClassElement(member, scope)
                }
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is TaggedTemplateExpression -> {
                collectRefsFromExpr(expr.tag, scope)
                when (val template = expr.template) {
                    is TemplateExpression -> {
                        template.templateSpans.forEach { collectRefsFromExpr(it.expression, scope) }
                    }
                    else -> {}
                }
            }
            is SpreadElement -> collectRefsFromExpr(expr.expression, scope)
            is AwaitExpression -> collectRefsFromExpr(expr.expression, scope)
            is YieldExpression -> expr.expression?.let { collectRefsFromExpr(it, scope) }
            is DeleteExpression -> collectRefsFromExpr(expr.expression, scope)
            is TypeOfExpression -> collectRefsFromExpr(expr.expression, scope)
            is VoidExpression -> collectRefsFromExpr(expr.expression, scope)
            is AsExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is SatisfiesExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is NonNullExpression -> collectRefsFromExpr(expr.expression, scope)
            is TypeAssertionExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is CommaListExpression -> expr.elements.forEach { collectRefsFromExpr(it, scope) }
            is JsxElement -> {
                collectRefsFromExpr(expr.openingElement.tagName, scope)
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { collectRefsFromExpr(it, scope) }
                    } else if (child is Expression) {
                        collectRefsFromExpr(child, scope)
                    }
                }
            }
            is JsxSelfClosingElement -> {
                collectRefsFromExpr(expr.tagName, scope)
                for (attr in expr.attributes) {
                    when (attr) {
                        is JsxAttribute -> {
                            val v = attr.value
                            if (v is JsxExpressionContainer) {
                                v.expression?.let { collectRefsFromExpr(it, scope) }
                            } else if (v is Expression) {
                                collectRefsFromExpr(v, scope)
                            }
                        }
                        is JsxSpreadAttribute -> collectRefsFromExpr(attr.expression, scope)
                        else -> {}
                    }
                }
            }
            is JsxFragment -> {
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { collectRefsFromExpr(it, scope) }
                    } else if (child is Expression) {
                        collectRefsFromExpr(child, scope)
                    }
                }
            }
            else -> {} // literals, omitted expressions, binding patterns, etc.
        }
    }

    /**
     * For write-only targets (left side of `=`), only collect references from
     * property access bases and element access arguments, not the target identifier itself.
     */
    private fun collectWriteTargetRefs(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is Identifier -> {} // Don't add — this is a write target, not a read
            is PropertyAccessExpression -> {
                // obj.prop = value — obj IS read
                collectRefsFromExpr(expr.expression, scope)
            }
            is ElementAccessExpression -> {
                // obj[key] = value — both obj and key are read
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromExpr(expr.argumentExpression, scope)
            }
            is ArrayLiteralExpression -> {
                // [x, y] = [1, 2] — destructuring write: elements are write targets
                for (element in expr.elements) {
                    when (element) {
                        is SpreadElement -> collectWriteTargetRefs(element.expression, scope)
                        is BinaryExpression -> {
                            // [x = default] — x is write target, default IS a read
                            if (element.operator == SyntaxKind.Equals) {
                                collectWriteTargetRefs(element.left, scope)
                                collectRefsFromExpr(element.right, scope)
                            } else {
                                collectRefsFromExpr(element, scope)
                            }
                        }
                        is OmittedExpression -> {} // skip holes
                        is Expression -> collectWriteTargetRefs(element, scope)
                        else -> {}
                    }
                }
            }
            is ObjectLiteralExpression -> {
                // { x, y } = { x: 1, y: 2 } — destructuring write: properties are write targets
                for (prop in expr.properties) {
                    when (prop) {
                        is ShorthandPropertyAssignment -> {
                            // { x } = obj — x is a write target
                            if (prop.objectAssignmentInitializer != null) {
                                // { x = default } = obj — x is write target, default IS a read
                                collectRefsFromExpr(prop.objectAssignmentInitializer!!, scope)
                            }
                            // Don't add prop.name to referencedNames
                        }
                        is PropertyAssignment -> {
                            // { key: target } = obj — key is a read (if computed), target is write
                            if (prop.name is ComputedPropertyName) {
                                collectRefsFromExpr((prop.name as ComputedPropertyName).expression, scope)
                            }
                            collectWriteTargetRefs(prop.initializer, scope)
                        }
                        is SpreadAssignment -> collectWriteTargetRefs(prop.expression, scope)
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> collectWriteTargetRefs(expr.expression, scope)
            else -> collectRefsFromExpr(expr, scope) // fallback: treat as read
        }
    }

    private fun collectRefsFromClassElement(element: ClassElement, scope: UnusedScope) {
        when (element) {
            is PropertyDeclaration -> {
                element.initializer?.let { collectRefsFromExpr(it, scope) }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is MethodDeclaration -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                element.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                }
            }
            is Constructor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                }
            }
            is GetAccessor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is SetAccessor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is ClassStaticBlockDeclaration -> {
                element.body.statements.forEach { collectUnusedReferences(it, scope) }
            }
            else -> {}
        }
    }

    /**
     * Collect name references from type nodes. Type references count as usage
     * for unused declaration checking — `let x: MyType` counts as using `MyType`.
     */
    private fun collectRefsFromType(type: TypeNode, scope: UnusedScope) {
        when (type) {
            is TypeReference -> {
                when (val name = type.typeName) {
                    is Identifier -> scope.referencedNames.add(name.text)
                    is QualifiedName -> {
                        // For A.B.C, only the leftmost name is a scope reference
                        var current: Node = name
                        while (current is QualifiedName) current = current.left
                        if (current is Identifier) scope.referencedNames.add(current.text)
                    }
                    else -> {}
                }
                type.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is ArrayType -> collectRefsFromType(type.elementType, scope)
            is TupleType -> type.elements.forEach { collectRefsFromType(it, scope) }
            is UnionType -> type.types.forEach { collectRefsFromType(it, scope) }
            is IntersectionType -> type.types.forEach { collectRefsFromType(it, scope) }
            is ParenthesizedType -> collectRefsFromType(type.type, scope)
            is FunctionType -> {
                type.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                collectRefsFromType(type.type, scope)
                type.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                    tp.default?.let { collectRefsFromType(it, scope) }
                }
            }
            is ConstructorType -> {
                type.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                collectRefsFromType(type.type, scope)
            }
            is TypeQuery -> {
                when (val name = type.exprName) {
                    is Identifier -> scope.referencedNames.add(name.text)
                    is QualifiedName -> {
                        var current: Node = name
                        while (current is QualifiedName) current = current.left
                        if (current is Identifier) scope.referencedNames.add(current.text)
                    }
                    else -> {}
                }
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> {
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        is MethodDeclaration -> {
                            member.parameters.forEach { param ->
                                param.type?.let { collectRefsFromType(it, scope) }
                            }
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        is IndexSignature -> {
                            member.parameters.forEach { param ->
                                param.type?.let { collectRefsFromType(it, scope) }
                            }
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ConditionalType -> {
                collectRefsFromType(type.checkType, scope)
                collectRefsFromType(type.extendsType, scope)
                collectRefsFromType(type.trueType, scope)
                collectRefsFromType(type.falseType, scope)
            }
            is MappedType -> {
                type.type?.let { collectRefsFromType(it, scope) }
                type.nameType?.let { collectRefsFromType(it, scope) }
                type.typeParameter.constraint?.let { collectRefsFromType(it, scope) }
            }
            is IndexedAccessType -> {
                collectRefsFromType(type.objectType, scope)
                collectRefsFromType(type.indexType, scope)
            }
            is TypeOperator -> collectRefsFromType(type.type, scope)
            is InferType -> {} // infer T — doesn't reference existing names
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span ->
                    collectRefsFromType(span.type, scope)
                }
            }
            is RestType -> collectRefsFromType(type.type, scope)
            is NamedTupleMember -> collectRefsFromType(type.type, scope)
            is OptionalType -> collectRefsFromType(type.type, scope)
            is ImportType -> {
                type.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            else -> {} // keyword types, literal types, this type, etc.
        }
    }

    /**
     * Recurse into nested scopes to check for unused declarations within them.
     */
    private fun checkUnusedInNestedScopes(stmt: Statement, source: String, fileName: String, siblingStatements: List<Statement>? = null) {
        when (stmt) {
            is FunctionDeclaration -> {
                stmt.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, stmt.parameters, source, fileName,
                        typeParameters = stmt.typeParameters,
                        returnType = stmt.type,
                    )
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    checkUnusedInClassElement(member, source, fileName)
                }
                // Check class-level type parameters
                checkUnusedClassTypeParams(stmt, source, fileName, siblingStatements)
                // Check unused private members
                checkUnusedPrivateMembers(stmt.members, source, fileName)
            }
            is InterfaceDeclaration -> {
                checkUnusedInterfaceTypeParams(stmt, source, fileName, siblingStatements)
            }
            is TypeAliasDeclaration -> {
                checkUnusedTypeAliasTypeParams(stmt, source, fileName)
            }
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> checkUnusedInStatements(
                        body.statements, source, fileName, isTopLevel = false,
                    )
                    is ModuleDeclaration -> checkUnusedInNestedScopes(
                        body, source, fileName,
                    )
                    else -> {}
                }
            }
            is VariableStatement -> {
                // Check initializer expressions for nested function-likes
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkUnusedInExpr(it, source, fileName) }
                }
            }
            is ExpressionStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
            }
            is ReturnStatement -> {
                stmt.expression?.let { checkUnusedInExpr(it, source, fileName) }
            }
            is Block -> checkUnusedInStatements(
                stmt.statements, source, fileName, isTopLevel = false,
            )
            is IfStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
                checkUnusedInNestedScopes(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkUnusedInNestedScopes(it, source, fileName) }
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        // Check for unused variables declared in the for-initializer
                        checkForStatementVariable(init, stmt, source, fileName)
                        for (decl in init.declarations) {
                            decl.initializer?.let { checkUnusedInExpr(it, source, fileName) }
                        }
                    }
                    is Expression -> checkUnusedInExpr(init, source, fileName)
                    else -> {}
                }
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is ForInStatement -> {
                checkForLoopVariable(stmt.initializer, stmt.statement, source, fileName)
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is ForOfStatement -> {
                checkForLoopVariable(stmt.initializer, stmt.statement, source, fileName)
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is WhileStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            is DoStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            is SwitchStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    // Check unused declarations within each case/default clause
                    checkUnusedInStatements(clauseStmts, source, fileName, isTopLevel = false)
                    clauseStmts.forEach { checkUnusedInNestedScopes(it, source, fileName) }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
                stmt.catchClause?.block?.statements?.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
                stmt.finallyBlock?.statements?.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
            }
            is LabeledStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            else -> {}
        }
    }

    /**
     * Check for unused declarations inside expression-level function-likes
     * (function expressions, arrow functions, class expressions).
     */
    private fun checkUnusedInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is FunctionExpression -> {
                checkUnusedInFunctionLike(
                    expr.body.statements, expr.parameters, source, fileName,
                    typeParameters = expr.typeParameters,
                    returnType = expr.type,
                )
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkUnusedInFunctionLike(
                        body.statements, expr.parameters, source, fileName,
                        typeParameters = expr.typeParameters,
                        returnType = expr.type,
                    )
                    is Expression -> {
                        // Arrow with expression body — still check parameters
                        if (options.noUnusedParameters) {
                            val scope = UnusedScope()
                            for (param in expr.parameters) {
                                val name = param.name
                                if (name is Identifier && !name.text.startsWith("_")) {
                                    scope.declarations.add(UnusedDecl(
                                        name = name.text,
                                        nameNode = name,
                                        declNode = param,
                                        isExported = false,
                                        isParameter = true,
                                        isTypeOnly = false,
                                    ))
                                }
                            }
                            collectRefsFromExpr(body, scope)
                            for (decl in scope.declarations) {
                                if (decl.name in scope.referencedNames) continue
                                val start = decl.nameNode.pos
                                val length = decl.name.length
                                val (line, character) = getLineAndCharacterOfPosition(source, start)
                                diagnostics.add(Diagnostic(
                                    message = "'${decl.name}' is declared but its value is never read.",
                                    category = DiagnosticCategory.Error,
                                    code = 6133,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = start,
                                    length = length,
                                ))
                            }
                        }
                        checkUnusedInExpr(body, source, fileName)
                    }
                    else -> {}
                }
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    checkUnusedInClassElement(member, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is MethodDeclaration -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                    typeParameters = prop.typeParameters,
                                    returnType = prop.type,
                                )
                            }
                        }
                        is GetAccessor -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                )
                            }
                        }
                        is SetAccessor -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                )
                            }
                        }
                        is PropertyAssignment -> {
                            checkUnusedInExpr(prop.initializer, source, fileName)
                        }
                        is SpreadAssignment -> {
                            checkUnusedInExpr(prop.expression, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                checkUnusedInExpr(expr.left, source, fileName)
                checkUnusedInExpr(expr.right, source, fileName)
            }
            is ConditionalExpression -> {
                checkUnusedInExpr(expr.condition, source, fileName)
                checkUnusedInExpr(expr.whenTrue, source, fileName)
                checkUnusedInExpr(expr.whenFalse, source, fileName)
            }
            is CallExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                expr.arguments.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is NewExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                expr.arguments?.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is AsExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is NonNullExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is PropertyAccessExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is ElementAccessExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                checkUnusedInExpr(expr.argumentExpression, source, fileName)
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { checkUnusedInExpr(it.expression, source, fileName) }
            }
            is TaggedTemplateExpression -> {
                checkUnusedInExpr(expr.tag, source, fileName)
            }
            is AwaitExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is YieldExpression -> expr.expression?.let { checkUnusedInExpr(it, source, fileName) }
            is SpreadElement -> checkUnusedInExpr(expr.expression, source, fileName)
            is PrefixUnaryExpression -> checkUnusedInExpr(expr.operand, source, fileName)
            is PostfixUnaryExpression -> checkUnusedInExpr(expr.operand, source, fileName)
            else -> {} // Literals, identifiers, etc. — no nested function-likes
        }
    }

    /**
     * Check for unused for-in/for-of loop variables.
     */
    /**
     * Check for unused variables declared in a C-style for-loop initializer:
     * `for(var i = 0; condition; increment) { body }`
     * Collects references from condition, incrementor, and body.
     */
    private fun checkForStatementVariable(
        declList: VariableDeclarationList,
        forStmt: ForStatement,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return
        val scope = UnusedScope()
        for (decl in declList.declarations) {
            collectVarDeclNames(decl.name, decl, isExported = false, scope)
        }
        // Collect references from condition, incrementor, and body
        forStmt.condition?.let { collectRefsFromExpr(it, scope) }
        forStmt.incrementor?.let { collectRefsFromExpr(it, scope) }
        collectUnusedReferences(forStmt.statement, scope)
        // Report unused
        for (decl in scope.declarations) {
            if (decl.name in scope.referencedNames) continue
            if (decl.name.startsWith("_")) continue
            val start = decl.nameNode.pos
            val length = decl.name.length
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "'${decl.name}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    private fun checkForLoopVariable(
        initializer: Node?,
        body: Statement,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return
        val declList = initializer as? VariableDeclarationList ?: return
        val scope = UnusedScope()
        for (decl in declList.declarations) {
            collectVarDeclNames(decl.name, decl, isExported = false, scope)
        }
        // Collect references from the body
        collectUnusedReferences(body, scope)
        // Report unused
        for (decl in scope.declarations) {
            if (decl.name in scope.referencedNames) continue
            if (decl.name.startsWith("_")) continue
            val start = decl.nameNode.pos
            val length = decl.name.length
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "'${decl.name}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    private fun checkUnusedInClassElement(
        element: ClassElement,
        source: String,
        fileName: String,
    ) {
        when (element) {
            is MethodDeclaration -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                        typeParameters = element.typeParameters,
                        returnType = element.type,
                    )
                }
            }
            is Constructor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is GetAccessor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is SetAccessor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is ClassStaticBlockDeclaration -> {
                checkUnusedInStatements(
                    element.body.statements, source, fileName, isTopLevel = false,
                )
            }
            is PropertyDeclaration -> {
                element.initializer?.let { checkUnusedInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    /**
     * Check for unused type parameters on a class declaration.
     */
    /**
     * Check for unused private class members (TS6133).
     * Private properties and methods that are never accessed within the class are unused.
     */
    private fun checkUnusedPrivateMembers(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return

        // Collect private members
        data class PrivateMember(val name: String, val nameNode: Node)
        val privateMembers = mutableListOf<PrivateMember>()
        val getterSetterNames = mutableSetOf<String>() // track getter/setter pairs

        for (member in members) {
            val isPrivate = when (member) {
                is PropertyDeclaration -> ModifierFlag.Private in member.modifiers
                is MethodDeclaration -> ModifierFlag.Private in member.modifiers
                is GetAccessor -> ModifierFlag.Private in member.modifiers
                is SetAccessor -> ModifierFlag.Private in member.modifiers
                else -> false
            }
            if (!isPrivate) continue

            val name = when (member) {
                is PropertyDeclaration -> (member.name as? Identifier)?.text
                is MethodDeclaration -> (member.name as? Identifier)?.text
                is GetAccessor -> (member.name as? Identifier)?.text
                is SetAccessor -> (member.name as? Identifier)?.text
                else -> null
            } ?: continue

            val nameNode = when (member) {
                is PropertyDeclaration -> member.name
                is MethodDeclaration -> member.name
                is GetAccessor -> member.name
                is SetAccessor -> member.name
                else -> continue
            }

            // Track getter/setter pairs — don't duplicate
            if (member is GetAccessor || member is SetAccessor) {
                if (name in getterSetterNames) continue
                getterSetterNames.add(name)
            }

            privateMembers.add(PrivateMember(name, nameNode))
        }

        if (privateMembers.isEmpty()) return

        // Collect property access names per member (for self-reference detection)
        val privateMemberNames = privateMembers.map { it.name }.toSet()
        data class MemberRefs(val memberName: String?, val refs: MutableSet<String>)
        val refsPerMember = mutableListOf<MemberRefs>()
        for (member in members) {
            val memberName = when (member) {
                is MethodDeclaration -> (member.name as? Identifier)?.text
                is GetAccessor -> (member.name as? Identifier)?.text
                is SetAccessor -> (member.name as? Identifier)?.text
                is Constructor -> null
                is PropertyDeclaration -> (member.name as? Identifier)?.text
                else -> null
            }
            val refs = mutableSetOf<String>()
            when (member) {
                is MethodDeclaration -> member.body?.let { collectPropertyAccessNames(it, refs) }
                is Constructor -> member.body?.let { collectPropertyAccessNames(it, refs) }
                is GetAccessor -> member.body?.let { collectPropertyAccessNames(it, refs) }
                is SetAccessor -> member.body?.let { collectPropertyAccessNames(it, refs) }
                is PropertyDeclaration -> member.initializer?.let { collectPropertyAccessNamesInExpr(it, refs) }
                else -> {}
            }
            refsPerMember.add(MemberRefs(memberName, refs))
        }

        // Report unused private members
        // A member is unused if it's only accessed from its own body (self-reference)
        for (pm in privateMembers) {
            val isExternallyAccessed = refsPerMember.any { mr ->
                mr.memberName != pm.name && pm.name in mr.refs
            }
            if (isExternallyAccessed) continue
            val start = pm.nameNode.pos
            val length = pm.name.length
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "'${pm.name}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    private fun collectPropertyAccessNames(block: Block, names: MutableSet<String>) {
        for (stmt in block.statements) {
            collectPropertyAccessNamesInStmt(stmt, names)
        }
    }

    private fun collectPropertyAccessNamesInStmt(stmt: Statement, names: MutableSet<String>) {
        when (stmt) {
            is ExpressionStatement -> collectPropertyAccessNamesInExpr(stmt.expression, names)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { collectPropertyAccessNamesInExpr(it, names) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { collectPropertyAccessNamesInExpr(it, names) }
            is IfStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names)
                collectPropertyAccessNamesInStmt(stmt.thenStatement, names)
                stmt.elseStatement?.let { collectPropertyAccessNamesInStmt(it, names) }
            }
            is Block -> stmt.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is Expression -> collectPropertyAccessNamesInExpr(init, names)
                    else -> {}
                }
                stmt.condition?.let { collectPropertyAccessNamesInExpr(it, names) }
                stmt.incrementor?.let { collectPropertyAccessNamesInExpr(it, names) }
                collectPropertyAccessNamesInStmt(stmt.statement, names)
            }
            is WhileStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names)
                collectPropertyAccessNamesInStmt(stmt.statement, names)
            }
            is DoStatement -> {
                collectPropertyAccessNamesInStmt(stmt.statement, names)
                collectPropertyAccessNamesInExpr(stmt.expression, names)
            }
            is SwitchStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            collectPropertyAccessNamesInExpr(clause.expression, names)
                            clause.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
                        }
                        is DefaultClause -> clause.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
                stmt.catchClause?.block?.statements?.forEach { collectPropertyAccessNamesInStmt(it, names) }
                stmt.finallyBlock?.statements?.forEach { collectPropertyAccessNamesInStmt(it, names) }
            }
            is ThrowStatement -> stmt.expression?.let { collectPropertyAccessNamesInExpr(it, names) }
            else -> {}
        }
    }

    private fun collectPropertyAccessNamesInExpr(expr: Expression, names: MutableSet<String>) {
        when (expr) {
            is PropertyAccessExpression -> {
                names.add(expr.name.text)
                collectPropertyAccessNamesInExpr(expr.expression, names)
            }
            is CallExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names)
                expr.arguments.forEach { collectPropertyAccessNamesInExpr(it, names) }
            }
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    // Simple assignment: left side is write-only, don't count as read
                    // But still recurse into sub-expressions of the left side
                    // (e.g. for `this.a[this.b] = 0`, this.b IS a read)
                    val left = expr.left
                    val right = expr.right
                    if (left is ObjectLiteralExpression && right is Identifier && right.text == "this") {
                        // Destructuring from this: ({ x, y } = this) reads this.x, this.y
                        for (prop in left.properties) {
                            when (prop) {
                                is ShorthandPropertyAssignment -> names.add(prop.name.text)
                                is PropertyAssignment -> {
                                    val propName = prop.name
                                    if (propName is Identifier) names.add(propName.text)
                                }
                                else -> {}
                            }
                        }
                    } else if (left is PropertyAccessExpression) {
                        // this.x = ... → only recurse into the base (this), not the property name
                        collectPropertyAccessNamesInExpr(left.expression, names)
                    } else {
                        collectPropertyAccessNamesInExpr(left, names)
                    }
                } else {
                    collectPropertyAccessNamesInExpr(expr.left, names)
                }
                collectPropertyAccessNamesInExpr(expr.right, names)
            }
            is PrefixUnaryExpression -> collectPropertyAccessNamesInExpr(expr.operand, names)
            is PostfixUnaryExpression -> collectPropertyAccessNamesInExpr(expr.operand, names)
            is ParenthesizedExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is ConditionalExpression -> {
                collectPropertyAccessNamesInExpr(expr.condition, names)
                collectPropertyAccessNamesInExpr(expr.whenTrue, names)
                collectPropertyAccessNamesInExpr(expr.whenFalse, names)
            }
            is NewExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names)
                expr.arguments?.forEach { collectPropertyAccessNamesInExpr(it, names) }
            }
            is ElementAccessExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names)
                collectPropertyAccessNamesInExpr(expr.argumentExpression, names)
                // String literal element access like obj["name"] counts as accessing "name"
                val arg = expr.argumentExpression
                if (arg is StringLiteralNode) names.add(arg.text)
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { collectPropertyAccessNamesInExpr(it.expression, names) }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> body.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
                    is Expression -> collectPropertyAccessNamesInExpr(body, names)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                expr.body.statements.forEach { collectPropertyAccessNamesInStmt(it, names) }
            }
            is ArrayLiteralExpression -> expr.elements.forEach { collectPropertyAccessNamesInExpr(it, names) }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> collectPropertyAccessNamesInExpr(prop.initializer, names)
                        is ShorthandPropertyAssignment -> {}
                        is SpreadAssignment -> collectPropertyAccessNamesInExpr(prop.expression, names)
                        else -> {}
                    }
                }
            }
            is AsExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is NonNullExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is TypeAssertionExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is AwaitExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is SpreadElement -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is DeleteExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is VoidExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is TypeOfExpression -> collectPropertyAccessNamesInExpr(expr.expression, names)
            is CommaListExpression -> expr.elements.forEach { collectPropertyAccessNamesInExpr(it, names) }
            else -> {}
        }
    }

    private fun checkUnusedClassTypeParams(
        cls: ClassDeclaration,
        source: String,
        fileName: String,
        siblingStatements: List<Statement>? = null,
    ) {
        val typeParams = cls.typeParameters
        if (typeParams.isNullOrEmpty() || !(options.noUnusedLocals || options.noUnusedParameters)) return
        // Skip if another declaration merges with this class (interface/namespace)
        val className = cls.name?.text
        if (className != null && siblingStatements != null) {
            val hasMerge = siblingStatements.any { stmt ->
                stmt !== cls && when (stmt) {
                    is InterfaceDeclaration -> stmt.name.text == className
                    is ModuleDeclaration -> {
                        val n = stmt.name
                        n is Identifier && n.text == className
                    }
                    else -> false
                }
            }
            if (hasMerge) return
        }

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text,
                    nameNode = tp.name,
                    declNode = tp,
                    isExported = false,
                    isParameter = false,
                    isTypeOnly = true,
                ))
            }
        }

        // Collect type refs from: heritage clauses, member types, constructor params
        cls.heritageClauses?.forEach { clause ->
            for (type in clause.types) {
                type.typeArguments?.forEach { collectTypeRefs(it, tpScope) }
                // The extends expression itself might reference a type param
                if (type.expression is Identifier) {
                    tpScope.referencedNames.add((type.expression as Identifier).text)
                }
            }
        }
        for (member in cls.members) {
            when (member) {
                is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, tpScope) }
                is MethodDeclaration -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.type?.let { collectTypeRefs(it, tpScope) }
                    member.body?.let { body ->
                        for (stmt in body.statements) collectTypeRefsInStatement(stmt, tpScope)
                    }
                }
                is Constructor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.body?.let { body ->
                        for (stmt in body.statements) collectTypeRefsInStatement(stmt, tpScope)
                    }
                }
                is GetAccessor -> member.type?.let { collectTypeRefs(it, tpScope) }
                is SetAccessor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                }
                is IndexSignature -> member.type?.let { collectTypeRefs(it, tpScope) }
                else -> {}
            }
        }

        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    private fun checkUnusedInterfaceTypeParams(
        iface: InterfaceDeclaration,
        source: String,
        fileName: String,
        siblingStatements: List<Statement>? = null,
    ) {
        val typeParams = iface.typeParameters
        if (typeParams.isNullOrEmpty() || !(options.noUnusedLocals || options.noUnusedParameters)) return
        // Skip if another declaration merges or if there are multiple interfaces with same name
        val ifaceName = iface.name.text
        // Check cross-file merges via globals symbol table
        val globalSymbol = globals[ifaceName]
        if (globalSymbol != null && globalSymbol.declarations.size > 1) return
        if (siblingStatements != null) {
            val hasMerge = siblingStatements.any { stmt ->
                stmt !== iface && when (stmt) {
                    is InterfaceDeclaration -> stmt.name.text == ifaceName
                    is ClassDeclaration -> stmt.name?.text == ifaceName
                    is ModuleDeclaration -> {
                        val n = stmt.name
                        n is Identifier && n.text == ifaceName
                    }
                    else -> false
                }
            }
            if (hasMerge) return
        }

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text, nameNode = tp.name, declNode = tp,
                    isExported = false, isParameter = false, isTypeOnly = true,
                ))
            }
        }

        // Collect refs from heritage clauses and members
        iface.heritageClauses?.forEach { clause ->
            for (type in clause.types) {
                type.typeArguments?.forEach { collectTypeRefs(it, tpScope) }
            }
        }
        for (member in iface.members) {
            when (member) {
                is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, tpScope) }
                is MethodDeclaration -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.type?.let { collectTypeRefs(it, tpScope) }
                }
                is IndexSignature -> member.type?.let { collectTypeRefs(it, tpScope) }
                else -> {}
            }
        }

        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    private fun checkUnusedTypeAliasTypeParams(
        alias: TypeAliasDeclaration,
        source: String,
        fileName: String,
    ) {
        val typeParams = alias.typeParameters
        if (typeParams.isNullOrEmpty() || !(options.noUnusedLocals || options.noUnusedParameters)) return

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text, nameNode = tp.name, declNode = tp,
                    isExported = false, isParameter = false, isTypeOnly = true,
                ))
            }
        }

        collectTypeRefs(alias.type, tpScope)
        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    private fun reportUnusedTypeParams(
        scope: UnusedScope,
        typeParams: List<TypeParameter>,
        source: String,
        fileName: String,
    ) {
        val allUnused = scope.declarations.none { it.name in scope.referencedNames }
        for (decl in scope.declarations) {
            if (decl.name in scope.referencedNames) continue
            val tp = decl.declNode as TypeParameter
            val start: Int
            val length: Int
            if (allUnused && scope.declarations.size == 1 && typeParams.size == 1) {
                start = tp.pos - 1
                length = decl.name.length + 2
            } else {
                start = tp.name.pos
                length = decl.name.length
            }
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "'${decl.name}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    private fun checkUnusedInFunctionLike(
        bodyStatements: List<Statement>,
        parameters: List<Parameter>,
        source: String,
        fileName: String,
        typeParameters: List<TypeParameter>? = null,
        returnType: TypeNode? = null,
    ) {
        // Check parameters (if noUnusedParameters is enabled)
        if (options.noUnusedParameters) {
            val scope = UnusedScope()
            // Collect parameter declarations
            for (param in parameters) {
                if (param.isCommentPlaceholder) continue
                val name = param.name
                if (name is Identifier) {
                    // Skip if underscore-prefixed or if it has access modifiers (constructor params)
                    if (!name.text.startsWith("_") &&
                        ModifierFlag.Public !in param.modifiers &&
                        ModifierFlag.Protected !in param.modifiers &&
                        ModifierFlag.Private !in param.modifiers) {
                        scope.declarations.add(UnusedDecl(
                            name = name.text,
                            nameNode = name,
                            declNode = param,
                            isExported = false,
                            isParameter = true,
                            isTypeOnly = false,
                        ))
                    }
                } else {
                    // Destructuring parameters: collect individual binding element names
                    collectDestructuringParamNames(name, param, scope)
                }
            }
            // Collect references from body
            for (stmt in bodyStatements) {
                collectUnusedReferences(stmt, scope)
            }
            // Also collect references from parameter defaults and types
            for (param in parameters) {
                param.initializer?.let { collectRefsFromExpr(it, scope) }
                param.type?.let { collectTypeRefs(it, scope) }
            }
            // Scan return type for typeof references
            returnType?.let { collectTypeRefs(it, scope) }
            // Report unused parameters
            for (decl in scope.declarations) {
                if (decl.name in scope.referencedNames) continue
                val nameNode = decl.nameNode
                val start = nameNode.pos
                val length = if (decl.spanLength > 0) decl.spanLength else decl.name.length
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "'${decl.name}' is declared but its value is never read.",
                    category = DiagnosticCategory.Error,
                    code = 6133,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }

        // Check unused type parameters (TS6133)
        if (typeParameters != null && typeParameters.isNotEmpty() && (options.noUnusedLocals || options.noUnusedParameters)) {
            val tpScope = UnusedScope()
            for (tp in typeParameters) {
                if (!tp.name.text.startsWith("_")) {
                    tpScope.declarations.add(UnusedDecl(
                        name = tp.name.text,
                        nameNode = tp.name,
                        declNode = tp,
                        isExported = false,
                        isParameter = false,
                        isTypeOnly = true,
                    ))
                }
            }
            // Collect type references from: type param constraints, parameter types,
            // return type, and body statements (types in variable declarations etc.)
            for (tp in typeParameters) {
                tp.constraint?.let { collectTypeRefs(it, tpScope) }
                tp.default?.let { collectTypeRefs(it, tpScope) }
            }
            for (param in parameters) {
                param.type?.let { collectTypeRefs(it, tpScope) }
            }
            returnType?.let { collectTypeRefs(it, tpScope) }
            for (stmt in bodyStatements) {
                collectTypeRefsInStatement(stmt, tpScope)
            }
            reportUnusedTypeParams(tpScope, typeParameters, source, fileName)
        }

        // Check local declarations in the body
        checkUnusedInStatements(bodyStatements, source, fileName, isTopLevel = false)
    }

    /** Collect type identifier references from a type node. */
    private fun collectTypeRefs(type: TypeNode, scope: UnusedScope) {
        when (type) {
            is TypeReference -> {
                val name = type.typeName
                if (name is Identifier) scope.referencedNames.add(name.text)
                type.typeArguments?.forEach { collectTypeRefs(it, scope) }
            }
            is KeywordTypeNode -> {} // any, number, string, etc. — no references
            is ArrayType -> collectTypeRefs(type.elementType, scope)
            is TupleType -> type.elements.forEach {
                when (it) {
                    is TypeNode -> collectTypeRefs(it, scope)
                    else -> {}
                }
            }
            is UnionType -> type.types.forEach { collectTypeRefs(it, scope) }
            is IntersectionType -> type.types.forEach { collectTypeRefs(it, scope) }
            is ParenthesizedType -> collectTypeRefs(type.type, scope)
            is FunctionType -> {
                type.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectTypeRefs(it, scope) }
                }
                type.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                type.type?.let { collectTypeRefs(it, scope) }
            }
            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                type.type?.let { collectTypeRefs(it, scope) }
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, scope) }
                        is MethodDeclaration -> {
                            member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                            member.type?.let { collectTypeRefs(it, scope) }
                        }
                        is IndexSignature -> member.type?.let { collectTypeRefs(it, scope) }
                        else -> {}
                    }
                }
            }
            is ConditionalType -> {
                collectTypeRefs(type.checkType, scope)
                collectTypeRefs(type.extendsType, scope)
                collectTypeRefs(type.trueType, scope)
                collectTypeRefs(type.falseType, scope)
            }
            is MappedType -> {
                type.type?.let { collectTypeRefs(it, scope) }
            }
            is TypeQuery -> {
                val name = type.exprName
                if (name is Identifier) scope.referencedNames.add(name.text)
            }
            is IndexedAccessType -> {
                collectTypeRefs(type.objectType, scope)
                collectTypeRefs(type.indexType, scope)
            }
            is TypeOperator -> collectTypeRefs(type.type, scope)
            is RestType -> collectTypeRefs(type.type, scope)
            is OptionalType -> collectTypeRefs(type.type, scope)
            is InferType -> {} // infer T — declares, doesn't reference
            is LiteralType -> {} // string/number literals
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span ->
                    collectTypeRefs(span.type, scope)
                }
            }
            else -> {}
        }
    }

    /** Recursively collect type refs from statements (for unused type param detection). */
    private fun collectTypeRefsInStatement(stmt: Statement, scope: UnusedScope) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.type?.let { collectTypeRefs(it, scope) }
                    decl.initializer?.let { collectTypeRefsInExpr(it, scope) }
                }
            }
            is ExpressionStatement -> collectTypeRefsInExpr(stmt.expression, scope)
            is ReturnStatement -> stmt.expression?.let { collectTypeRefsInExpr(it, scope) }
            is IfStatement -> {
                collectTypeRefsInExpr(stmt.expression, scope)
                collectTypeRefsInStatement(stmt.thenStatement, scope)
                stmt.elseStatement?.let { collectTypeRefsInStatement(it, scope) }
            }
            is Block -> stmt.statements.forEach { collectTypeRefsInStatement(it, scope) }
            is ForStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is ForInStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is ForOfStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is WhileStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is DoStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectTypeRefsInStatement(it, scope) }
                stmt.catchClause?.block?.statements?.forEach { collectTypeRefsInStatement(it, scope) }
                stmt.finallyBlock?.statements?.forEach { collectTypeRefsInStatement(it, scope) }
            }
            else -> {}
        }
    }

    /** Collect type refs from expressions (type assertions, as expressions, etc.). */
    private fun collectTypeRefsInExpr(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is AsExpression -> {
                collectTypeRefs(expr.type, scope)
                collectTypeRefsInExpr(expr.expression, scope)
            }
            is TypeAssertionExpression -> {
                collectTypeRefs(expr.type, scope)
                collectTypeRefsInExpr(expr.expression, scope)
            }
            is CallExpression -> {
                expr.typeArguments?.forEach { collectTypeRefs(it, scope) }
                collectTypeRefsInExpr(expr.expression, scope)
                expr.arguments.forEach { collectTypeRefsInExpr(it, scope) }
            }
            is NewExpression -> {
                expr.typeArguments?.forEach { collectTypeRefs(it, scope) }
            }
            is ParenthesizedExpression -> collectTypeRefsInExpr(expr.expression, scope)
            is BinaryExpression -> {
                collectTypeRefsInExpr(expr.left, scope)
                collectTypeRefsInExpr(expr.right, scope)
            }
            is ConditionalExpression -> {
                collectTypeRefsInExpr(expr.condition, scope)
                collectTypeRefsInExpr(expr.whenTrue, scope)
                collectTypeRefsInExpr(expr.whenFalse, scope)
            }
            is ArrowFunction -> {
                expr.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectTypeRefs(it, scope) }
                }
                expr.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                expr.type?.let { collectTypeRefs(it, scope) }
            }
            is FunctionExpression -> {
                expr.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectTypeRefs(it, scope) }
                }
                expr.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                expr.type?.let { collectTypeRefs(it, scope) }
            }
            else -> {}
        }
    }

    /**
     * Compute 1-based line and character for a position in source text.
     */
    private fun getLineAndCharacterOfPosition(source: String, position: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (i in 0 until position.coerceAtMost(source.length)) {
            if (source[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (position - lineStart + 1)
    }

    // -----------------------------------------------------------------------
    // Definite assignment checking (TS2454)
    // -----------------------------------------------------------------------

    /**
     * Check for variables used before being definitively assigned.
     * Emits TS2454 "Variable 'X' is used before being assigned."
     */
    private fun checkDefiniteAssignment() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkDefiniteAssignmentInStatements(
                result.sourceFile.statements, source, fileName,
            )
        }
    }

    /**
     * Walk statements tracking which variables are uninitialized.
     * When a reference to an uninitialized variable is found, emit TS2454.
     */
    private fun checkDefiniteAssignmentInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        // Track variables declared with type but no initializer
        val uninitialized = mutableSetOf<String>()

        for (stmt in statements) {
            // 1. Collect variable declarations that are uninitialized
            collectUninitializedVars(stmt, uninitialized)

            // 2. Check for uses of uninitialized variables in this statement
            if (uninitialized.isNotEmpty()) {
                checkUsesOfUninitialized(stmt, uninitialized, source, fileName)
            }

            // 3. Mark variables as assigned if they appear on left side of assignment
            markAssignments(stmt, uninitialized)

            // 4. Recurse into nested scopes
            checkDefiniteAssignmentInNestedScopes(stmt, source, fileName)
        }
    }

    private fun collectUninitializedVars(stmt: Statement, uninitialized: MutableSet<String>) {
        when (stmt) {
            is VariableStatement -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                for (decl in stmt.declarationList.declarations) {
                    // Only flag variables with type annotation but no initializer
                    // Skip definite assignment assertions (var x!: Type)
                    if (decl.type != null && decl.initializer == null && !decl.exclamationToken) {
                        // Skip `var x: any` — `any` includes undefined, no assignment needed
                        if (isAnyType(decl.type)) continue
                        // Skip if type annotation is a bare generic reference (TS2314 error type)
                        if (isUnresolvedGenericType(decl.type)) continue
                        val name = decl.name
                        if (name is Identifier) {
                            uninitialized.add(name.text)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun isAnyType(type: Node?): Boolean {
        if (type == null) return false
        // Check for keyword type nodes like `any`
        return type.kind == SyntaxKind.AnyKeyword
    }

    /**
     * Check expression trees for references to uninitialized variables.
     * When found, emit TS2454 and remove from uninitialized set (report only first use).
     */
    private fun checkUsesOfUninitialized(
        stmt: Statement,
        uninitialized: MutableSet<String>,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let {
                        findUninitializedRefs(it, uninitialized, source, fileName)
                    }
                }
            }
            is ExpressionStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is ReturnStatement -> {
                stmt.expression?.let {
                    findUninitializedRefs(it, uninitialized, source, fileName)
                }
            }
            is IfStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is Expression -> {
                        findUninitializedRefs(init, uninitialized, source, fileName)
                        markAssignmentsInExpr(init, uninitialized)
                    }
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let {
                                findUninitializedRefs(it, uninitialized, source, fileName)
                            }
                            // Variable with initializer is assigned
                            if (decl.initializer != null) {
                                val name = decl.name
                                if (name is Identifier) uninitialized.remove(name.text)
                            }
                        }
                    }
                    else -> {}
                }
                stmt.condition?.let { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            is ForInStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is ForOfStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is WhileStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is ThrowStatement -> {
                stmt.expression?.let { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            is SwitchStatement -> {
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is Block -> {
                for (s in stmt.statements) {
                    checkUsesOfUninitialized(s, uninitialized, source, fileName)
                    markAssignments(s, uninitialized)
                }
            }
            is DoStatement -> {
                checkUsesOfUninitialized(stmt.statement, uninitialized, source, fileName)
                findUninitializedRefs(stmt.expression, uninitialized, source, fileName)
            }
            is TryStatement -> {
                for (s in stmt.tryBlock.statements) {
                    checkUsesOfUninitialized(s, uninitialized, source, fileName)
                    markAssignments(s, uninitialized)
                }
            }
            is LabeledStatement -> {
                checkUsesOfUninitialized(stmt.statement, uninitialized, source, fileName)
            }
            else -> {}
        }
    }

    /**
     * Walk an expression tree to find references to uninitialized variables.
     * Emits TS2454 diagnostic at the usage site.
     */
    private fun findUninitializedRefs(
        expr: Expression,
        uninitialized: MutableSet<String>,
        source: String,
        fileName: String,
    ) {
        when (expr) {
            is Identifier -> {
                if (expr.text in uninitialized) {
                    val start = expr.pos
                    val length = expr.text.length
                    val (line, character) = getLineAndCharacterOfPosition(source, start)
                    diagnostics.add(Diagnostic(
                        message = "Variable '${expr.text}' is used before being assigned.",
                        category = DiagnosticCategory.Error,
                        code = 2454,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = length,
                    ))
                }
            }
            is PropertyAccessExpression -> {
                findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            }
            is ElementAccessExpression -> {
                findUninitializedRefs(expr.expression, uninitialized, source, fileName)
                findUninitializedRefs(expr.argumentExpression, uninitialized, source, fileName)
            }
            is CallExpression -> {
                findUninitializedRefs(expr.expression, uninitialized, source, fileName)
                expr.arguments.forEach { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            is NewExpression -> {
                findUninitializedRefs(expr.expression, uninitialized, source, fileName)
                expr.arguments?.forEach { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    // Assignment — right side may use uninitialized, left side is a write
                    findUninitializedRefs(expr.right, uninitialized, source, fileName)
                    // Don't check left side for reads (it's a write target)
                } else {
                    findUninitializedRefs(expr.left, uninitialized, source, fileName)
                    findUninitializedRefs(expr.right, uninitialized, source, fileName)
                }
            }
            is ConditionalExpression -> {
                findUninitializedRefs(expr.condition, uninitialized, source, fileName)
                findUninitializedRefs(expr.whenTrue, uninitialized, source, fileName)
                findUninitializedRefs(expr.whenFalse, uninitialized, source, fileName)
            }
            is PrefixUnaryExpression -> {
                findUninitializedRefs(expr.operand, uninitialized, source, fileName)
            }
            is PostfixUnaryExpression -> {
                findUninitializedRefs(expr.operand, uninitialized, source, fileName)
            }
            is ParenthesizedExpression -> {
                findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            findUninitializedRefs(prop.initializer, uninitialized, source, fileName)
                        }
                        is ShorthandPropertyAssignment -> {
                            findUninitializedRefs(prop.name, uninitialized, source, fileName)
                        }
                        is SpreadAssignment -> {
                            findUninitializedRefs(prop.expression, uninitialized, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach {
                    findUninitializedRefs(it.expression, uninitialized, source, fileName)
                }
            }
            is TaggedTemplateExpression -> {
                findUninitializedRefs(expr.tag, uninitialized, source, fileName)
            }
            is SpreadElement -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is AwaitExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is YieldExpression -> expr.expression?.let { findUninitializedRefs(it, uninitialized, source, fileName) }
            is AsExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is NonNullExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is TypeOfExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is DeleteExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is VoidExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is TypeAssertionExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is SatisfiesExpression -> findUninitializedRefs(expr.expression, uninitialized, source, fileName)
            is CommaListExpression -> {
                expr.elements.forEach { findUninitializedRefs(it, uninitialized, source, fileName) }
            }
            else -> {} // Literals, arrow functions, etc. — no direct references
        }
    }

    /**
     * Mark variables as assigned when they appear on the left side of assignments.
     */
    private fun markAssignments(stmt: Statement, uninitialized: MutableSet<String>) {
        when (stmt) {
            is ExpressionStatement -> {
                markAssignmentsInExpr(stmt.expression, uninitialized)
            }
            is VariableStatement -> {
                // Variable with initializer — it's assigned
                for (decl in stmt.declarationList.declarations) {
                    if (decl.initializer != null) {
                        val name = decl.name
                        if (name is Identifier) {
                            uninitialized.remove(name.text)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun markAssignmentsInExpr(expr: Expression, uninitialized: MutableSet<String>) {
        when (expr) {
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    val left = expr.left
                    when (left) {
                        is Identifier -> uninitialized.remove(left.text)
                        is ObjectLiteralExpression -> collectDestructuringTargets(left, uninitialized)
                        is ArrayLiteralExpression -> collectDestructuringTargets(left, uninitialized)
                        else -> {}
                    }
                }
                // Recurse into both sides for compound expressions
                markAssignmentsInExpr(expr.left, uninitialized)
                markAssignmentsInExpr(expr.right, uninitialized)
            }
            is CommaListExpression -> {
                expr.elements.forEach { markAssignmentsInExpr(it, uninitialized) }
            }
            is ParenthesizedExpression -> markAssignmentsInExpr(expr.expression, uninitialized)
            else -> {}
        }
    }

    /** Extract assigned variable names from destructuring assignment targets. */
    private fun collectDestructuringTargets(expr: Expression, uninitialized: MutableSet<String>) {
        when (expr) {
            is Identifier -> uninitialized.remove(expr.text)
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is ShorthandPropertyAssignment -> uninitialized.remove(prop.name.text)
                        is PropertyAssignment -> collectDestructuringTargets(prop.initializer, uninitialized)
                        is SpreadAssignment -> collectDestructuringTargets(prop.expression, uninitialized)
                        else -> {}
                    }
                }
            }
            is ArrayLiteralExpression -> {
                for (elem in expr.elements) {
                    when (elem) {
                        is SpreadElement -> collectDestructuringTargets(elem.expression, uninitialized)
                        is OmittedExpression -> {} // skip holes
                        else -> collectDestructuringTargets(elem, uninitialized)
                    }
                }
            }
            is BinaryExpression -> {
                // Default value: `x = defaultVal` — the target is `x`
                if (expr.operator == SyntaxKind.Equals) {
                    collectDestructuringTargets(expr.left, uninitialized)
                }
            }
            else -> {}
        }
    }

    /**
     * Recurse into function bodies and other nested scopes for TS2454 checking.
     */
    private fun checkDefiniteAssignmentInNestedScopes(
        stmt: Statement,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                stmt.body?.let {
                    checkDefiniteAssignmentInStatements(it.statements, source, fileName)
                }
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> member.body?.let {
                            checkDefiniteAssignmentInStatements(it.statements, source, fileName)
                        }
                        is Constructor -> member.body?.let {
                            checkDefiniteAssignmentInStatements(it.statements, source, fileName)
                        }
                        is GetAccessor -> member.body?.let {
                            checkDefiniteAssignmentInStatements(it.statements, source, fileName)
                        }
                        is SetAccessor -> member.body?.let {
                            checkDefiniteAssignmentInStatements(it.statements, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return // skip ambient
                when (val body = stmt.body) {
                    is ModuleBlock -> checkDefiniteAssignmentInStatements(
                        body.statements, source, fileName,
                    )
                    is ModuleDeclaration -> checkDefiniteAssignmentInNestedScopes(
                        body, source, fileName,
                    )
                    else -> {}
                }
            }
            is Block -> checkDefiniteAssignmentInStatements(
                stmt.statements, source, fileName,
            )
            is IfStatement -> {
                checkDefiniteAssignmentInNestedScopes(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkDefiniteAssignmentInNestedScopes(it, source, fileName) }
            }
            is ForStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            is ForInStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            is ForOfStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            is WhileStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            is DoStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> clause.statements.forEach {
                            checkDefiniteAssignmentInNestedScopes(it, source, fileName)
                        }
                        is DefaultClause -> clause.statements.forEach {
                            checkDefiniteAssignmentInNestedScopes(it, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach {
                    checkDefiniteAssignmentInNestedScopes(it, source, fileName)
                }
                stmt.catchClause?.block?.statements?.forEach {
                    checkDefiniteAssignmentInNestedScopes(it, source, fileName)
                }
                stmt.finallyBlock?.statements?.forEach {
                    checkDefiniteAssignmentInNestedScopes(it, source, fileName)
                }
            }
            is LabeledStatement -> checkDefiniteAssignmentInNestedScopes(stmt.statement, source, fileName)
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Property initialization checking (TS2564)
    // -----------------------------------------------------------------------

    /**
     * Check for class properties without initializer and not definitely assigned
     * in the constructor. Emits TS2564.
     */
    private fun checkPropertyInitialization() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkPropertyInitInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkPropertyInitInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> {
                    if (ModifierFlag.Declare in stmt.modifiers) continue
                    if (ModifierFlag.Abstract in stmt.modifiers) continue
                    checkClassPropertyInit(stmt.members, source, fileName)
                }
                is ModuleDeclaration -> {
                    // Skip declare (ambient) namespaces — classes inside are ambient
                    if (ModifierFlag.Declare in stmt.modifiers) continue
                    when (val body = stmt.body) {
                        is ModuleBlock -> checkPropertyInitInStatements(
                            body.statements, source, fileName,
                        )
                        else -> {}
                    }
                }
                is FunctionDeclaration -> {
                    if (ModifierFlag.Declare in stmt.modifiers) continue
                    stmt.body?.let {
                        checkPropertyInitInStatements(it.statements, source, fileName)
                    }
                }
                is Block -> checkPropertyInitInStatements(stmt.statements, source, fileName)
                is IfStatement -> {
                    checkPropertyInitInStatements(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let {
                        checkPropertyInitInStatements(listOf(it), source, fileName)
                    }
                }
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        checkPropertyInitInExpr(decl.initializer, source, fileName)
                    }
                }
                is ExpressionStatement -> {
                    checkPropertyInitInExpr(stmt.expression, source, fileName)
                }
                is ReturnStatement -> {
                    checkPropertyInitInExpr(stmt.expression, source, fileName)
                }
                else -> {}
            }
        }
    }

    private fun checkPropertyInitInExpr(expr: Expression?, source: String, fileName: String) {
        when (expr) {
            is ClassExpression -> {
                if (ModifierFlag.Abstract !in expr.modifiers) {
                    checkClassPropertyInit(expr.members, source, fileName)
                }
            }
            is ParenthesizedExpression -> checkPropertyInitInExpr(expr.expression, source, fileName)
            else -> {}
        }
    }

    private fun checkClassPropertyInit(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        // Find the constructor and collect assigned properties
        val constructorAssigned = mutableSetOf<String>()
        for (member in members) {
            if (member is Constructor) {
                member.body?.let { body ->
                    collectConstructorAssignments(body.statements, constructorAssigned)
                }
                // Constructor parameters with access modifiers (public/private/protected)
                // are automatically assigned
                for (param in member.parameters) {
                    if (ModifierFlag.Public in param.modifiers ||
                        ModifierFlag.Protected in param.modifiers ||
                        ModifierFlag.Private in param.modifiers) {
                        val name = param.name
                        if (name is Identifier) {
                            constructorAssigned.add(name.text)
                        }
                    }
                }
            }
        }

        // Check each property
        for (member in members) {
            if (member !is PropertyDeclaration) continue
            // Skip if has initializer, optional, declare, static, abstract, or definite assignment
            if (member.initializer != null) continue
            if (member.questionToken) continue
            if (member.exclamationToken) continue
            if (ModifierFlag.Declare in member.modifiers) continue
            if (ModifierFlag.Static in member.modifiers) continue
            if (ModifierFlag.Abstract in member.modifiers) continue
            // Must have type annotation (no type = any, which is always ok)
            if (member.type == null) continue
            // Skip `any` type — no assignment needed for any
            if (isAnyType(member.type)) continue
            // Skip if type annotation is a bare generic reference (would trigger TS2314)
            // TypeScript doesn't flag TS2564 on error types
            if (isUnresolvedGenericType(member.type)) continue

            // Get property name
            val propName = when (val name = member.name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                is ComputedPropertyName -> {
                    // Computed property name — check for Symbol.X or simple identifier
                    when (val expr = name.expression) {
                        is PropertyAccessExpression -> {
                            val base = expr.expression
                            if (base is Identifier && base.text == "Symbol") {
                                "[Symbol.${expr.name.text}]"
                            } else null
                        }
                        else -> null
                    }
                }
                else -> null
            } ?: continue

            // Check if assigned in constructor
            if (propName in constructorAssigned) continue

            // Emit TS2564
            val nameNode = member.name
            val start = nameNode.pos
            val length = propName.length
            val (line, character) = getLineAndCharacterOfPosition(source, start)

            diagnostics.add(Diagnostic(
                message = "Property '$propName' has no initializer and is not definitely assigned in the constructor.",
                category = DiagnosticCategory.Error,
                code = 2564,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }

        // Recurse into nested class elements for inner classes
        for (member in members) {
            when (member) {
                is MethodDeclaration -> member.body?.let {
                    checkPropertyInitInStatements(it.statements, source, fileName)
                }
                is Constructor -> member.body?.let {
                    checkPropertyInitInStatements(it.statements, source, fileName)
                }
                is GetAccessor -> member.body?.let {
                    checkPropertyInitInStatements(it.statements, source, fileName)
                }
                is SetAccessor -> member.body?.let {
                    checkPropertyInitInStatements(it.statements, source, fileName)
                }
                is ClassStaticBlockDeclaration -> {
                    checkPropertyInitInStatements(member.body.statements, source, fileName)
                }
                else -> {}
            }
        }
    }

    /**
     * Collect property names assigned in constructor body via `this.propName = ...`
     */
    private fun collectConstructorAssignments(
        statements: List<Statement>,
        assigned: MutableSet<String>,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is ExpressionStatement -> {
                    collectThisAssignment(stmt.expression, assigned)
                }
                is IfStatement -> {
                    // Only if BOTH branches assign, consider it assigned
                    // For simplicity, check both branches but don't require both
                    collectConstructorAssignments(
                        listOf(stmt.thenStatement), assigned,
                    )
                    stmt.elseStatement?.let {
                        collectConstructorAssignments(listOf(it), assigned)
                    }
                }
                is Block -> collectConstructorAssignments(stmt.statements, assigned)
                else -> {}
            }
        }
    }

    private fun collectThisAssignment(expr: Expression, assigned: MutableSet<String>) {
        when (expr) {
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    val left = expr.left
                    if (left is PropertyAccessExpression) {
                        val base = left.expression
                        if (base is Identifier && base.text == "this") {
                            assigned.add(left.name.text)
                        }
                    }
                }
            }
            is CommaListExpression -> {
                expr.elements.forEach { collectThisAssignment(it, assigned) }
            }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Implicit any checking (TS7006)
    // -----------------------------------------------------------------------

    /**
     * Check for function/method parameters without type annotations when
     * noImplicitAny is enabled. Emits TS7006.
     */
    private fun checkImplicitAnyParameters() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkImplicitAnyInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkImplicitAnyInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is FunctionDeclaration -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        checkParamsForImplicitAny(stmt.parameters, source, fileName)
                        stmt.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
                        // TS7010: overload signature without return type annotation
                        if (stmt.body == null && stmt.type == null && stmt.name != null) {
                            val name = stmt.name
                            val (line, character) = getLineAndCharacterOfPosition(source, name.pos)
                            diagnostics.add(Diagnostic(
                                message = "'${name.text}', which lacks return-type annotation, implicitly has an 'any' return type.",
                                category = DiagnosticCategory.Error,
                                code = 7010,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = name.pos,
                                length = name.text.length,
                            ))
                        }
                    }
                }
                is ClassDeclaration -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        for (member in stmt.members) {
                            checkImplicitAnyInClassElement(member, source, fileName)
                        }
                    }
                }
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        decl.initializer?.let { checkImplicitAnyInExpr(it, source, fileName) }
                    }
                }
                is ExpressionStatement -> {
                    checkImplicitAnyInExpr(stmt.expression, source, fileName)
                }
                is ModuleDeclaration -> {
                    when (val body = stmt.body) {
                        is ModuleBlock -> checkImplicitAnyInStatements(body.statements, source, fileName)
                        else -> {}
                    }
                }
                is Block -> checkImplicitAnyInStatements(stmt.statements, source, fileName)
                is IfStatement -> {
                    checkImplicitAnyInStatements(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let { checkImplicitAnyInStatements(listOf(it), source, fileName) }
                }
                is ForStatement -> {
                    checkImplicitAnyInStatements(listOf(stmt.statement), source, fileName)
                }
                is ReturnStatement -> {
                    stmt.expression?.let { checkImplicitAnyInExpr(it, source, fileName) }
                }
                else -> {}
            }
        }
    }

    private fun checkImplicitAnyInClassElement(
        element: ClassElement,
        source: String,
        fileName: String,
    ) {
        when (element) {
            is MethodDeclaration -> {
                checkParamsForImplicitAny(element.parameters, source, fileName)
                element.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
            }
            is Constructor -> {
                checkParamsForImplicitAny(element.parameters, source, fileName)
                element.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
            }
            is GetAccessor -> {
                element.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
            }
            is SetAccessor -> {
                checkParamsForImplicitAny(element.parameters, source, fileName)
                element.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
            }
            is PropertyDeclaration -> {
                element.initializer?.let { checkImplicitAnyInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    private fun checkImplicitAnyInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                checkParamsForImplicitAny(expr.parameters, source, fileName)
                when (val body = expr.body) {
                    is Block -> checkImplicitAnyInStatements(body.statements, source, fileName)
                    is Expression -> checkImplicitAnyInExpr(body, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkParamsForImplicitAny(expr.parameters, source, fileName)
                checkImplicitAnyInStatements(expr.body.statements, source, fileName)
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    checkImplicitAnyInClassElement(member, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is MethodDeclaration -> {
                            checkParamsForImplicitAny(prop.parameters, source, fileName)
                            prop.body?.let { checkImplicitAnyInStatements(it.statements, source, fileName) }
                        }
                        is PropertyAssignment -> {
                            checkImplicitAnyInExpr(prop.initializer, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is CallExpression -> {
                expr.arguments.forEach { checkImplicitAnyInExpr(it, source, fileName) }
            }
            is BinaryExpression -> {
                checkImplicitAnyInExpr(expr.left, source, fileName)
                checkImplicitAnyInExpr(expr.right, source, fileName)
            }
            is ParenthesizedExpression -> checkImplicitAnyInExpr(expr.expression, source, fileName)
            is ConditionalExpression -> {
                checkImplicitAnyInExpr(expr.whenTrue, source, fileName)
                checkImplicitAnyInExpr(expr.whenFalse, source, fileName)
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { checkImplicitAnyInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    private fun checkParamsForImplicitAny(
        parameters: List<Parameter>,
        source: String,
        fileName: String,
    ) {
        for (param in parameters) {
            if (param.isCommentPlaceholder) continue
            // Skip if parameter has type annotation or initializer
            if (param.type != null) continue
            if (param.initializer != null) continue
            // Skip `this` parameter
            val name = param.name
            if (name is Identifier && name.text == "this") continue
            // Skip destructured parameters (they get separate diagnostics)
            if (name !is Identifier) continue

            if (param.dotDotDotToken) {
                // TS7019: Rest parameter implicitly has an 'any[]' type
                // Span covers `...name` (3 chars for `...` + name length)
                val start = name.pos - 3 // position of `...`
                val length = 3 + name.text.length
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "Rest parameter '${name.text}' implicitly has an 'any[]' type.",
                    category = DiagnosticCategory.Error,
                    code = 7019,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            } else {
                // TS7006: Parameter implicitly has an 'any' type
                val start = name.pos
                // Include the `?` token in the span if present (e.g., `x?` → length 2)
                val length = if (param.questionToken) name.text.length + 1 else name.text.length
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "Parameter '${name.text}' implicitly has an 'any' type.",
                    category = DiagnosticCategory.Error,
                    code = 7006,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Unresolved name checking (TS2304)
    // -----------------------------------------------------------------------

    /**
     * Scope for name resolution — tracks names declared at each scope level.
     * Lookup walks up the parent chain.
     */
    private class NameScope(
        val parent: NameScope?,
        val names: MutableSet<String> = mutableSetOf(),
        val hasArguments: Boolean = false,
    ) {
        fun has(name: String): Boolean =
            name in names || (hasArguments && name == "arguments") || parent?.has(name) == true

        fun child(
            hasArguments: Boolean = false,
        ): NameScope = NameScope(parent = this, hasArguments = hasArguments)
    }

    /**
     * Check for references to names that cannot be resolved.
     * Emits TS2304: "Cannot find name 'X'."
     */
    private fun checkUnresolvedNames() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            // File-level scope: binder locals + globals + known globals
            val fileScope = NameScope(null)
            for ((name, _) in result.locals) fileScope.names.add(name)
            for ((name, _) in globals) fileScope.names.add(name)
            fileScope.names.addAll(KNOWN_GLOBALS)

            checkUnresolvedInStatements(
                result.sourceFile.statements,
                fileScope,
                source,
                fileName,
            )
        }
    }

    private fun checkUnresolvedInStatements(
        statements: List<Statement>,
        parentScope: NameScope,
        source: String,
        fileName: String,
    ) {
        if (checkDepth > maxCheckDepth) return
        // Create child scope with declarations from this statement list
        val scope = parentScope.child()
        collectDeclaredNames(statements, scope)

        for (stmt in statements) {
            checkUnresolvedInStatement(stmt, scope, source, fileName)
        }
    }

    /**
     * Collect all names declared at this statement-list level.
     * Includes variables, functions, classes, interfaces, type aliases,
     * enums, namespaces, imports. Also hoists `var` declarations from
     * nested blocks/loops (since `var` is function-scoped, not block-scoped).
     */
    private fun collectDeclaredNames(statements: List<Statement>, scope: NameScope) {
        for (stmt in statements) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        addBindingName(decl.name, scope)
                    }
                }
                is FunctionDeclaration -> stmt.name?.let { scope.names.add(it.text) }
                is ClassDeclaration -> stmt.name?.let { scope.names.add(it.text) }
                is InterfaceDeclaration -> scope.names.add(stmt.name.text)
                is TypeAliasDeclaration -> scope.names.add(stmt.name.text)
                is EnumDeclaration -> scope.names.add(stmt.name.text)
                is ModuleDeclaration -> {
                    val name = stmt.name
                    when (name) {
                        is Identifier -> scope.names.add(name.text)
                        is StringLiteralNode -> scope.names.add(name.text)
                        is PropertyAccessExpression -> {
                            // Dotted namespace: namespace m1.m2.m3 {} — extract leftmost segment
                            var cur: Expression = name
                            while (cur is PropertyAccessExpression) cur = cur.expression
                            if (cur is Identifier) scope.names.add(cur.text)
                        }
                        else -> {}
                    }
                }
                is ImportDeclaration -> {
                    val clause = stmt.importClause ?: return
                    clause.name?.let { scope.names.add(it.text) }
                    when (val bindings = clause.namedBindings) {
                        is NamedImports -> {
                            for (spec in bindings.elements) {
                                scope.names.add(spec.name.text)
                            }
                        }
                        is NamespaceImport -> scope.names.add(bindings.name.text)
                        else -> {}
                    }
                }
                is ImportEqualsDeclaration -> scope.names.add(stmt.name.text)
                else -> {}
            }
            // Hoist var declarations from nested blocks/loops
            collectHoistedVarNames(stmt, scope)
        }
    }

    /**
     * Recursively find `var` declarations in nested blocks, loops, if/else, etc.
     * Since `var` is function-scoped (not block-scoped), these names are visible
     * in the enclosing function/file scope. Does NOT recurse into functions
     * (which create their own scope).
     */
    private fun collectHoistedVarNames(stmt: Statement, scope: NameScope) {
        when (stmt) {
            is VariableStatement -> {
                if (stmt.declarationList.flags == SyntaxKind.VarKeyword) {
                    for (decl in stmt.declarationList.declarations) {
                        addBindingName(decl.name, scope)
                    }
                }
            }
            is Block -> stmt.statements.forEach { collectHoistedVarNames(it, scope) }
            is IfStatement -> {
                collectHoistedVarNames(stmt.thenStatement, scope)
                stmt.elseStatement?.let { collectHoistedVarNames(it, scope) }
            }
            is ForStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == SyntaxKind.VarKeyword) {
                    for (decl in init.declarations) addBindingName(decl.name, scope)
                }
                collectHoistedVarNames(stmt.statement, scope)
            }
            is ForInStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == SyntaxKind.VarKeyword) {
                    for (decl in init.declarations) addBindingName(decl.name, scope)
                }
                collectHoistedVarNames(stmt.statement, scope)
            }
            is ForOfStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == SyntaxKind.VarKeyword) {
                    for (decl in init.declarations) addBindingName(decl.name, scope)
                }
                collectHoistedVarNames(stmt.statement, scope)
            }
            is WhileStatement -> collectHoistedVarNames(stmt.statement, scope)
            is DoStatement -> collectHoistedVarNames(stmt.statement, scope)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> clause.statements.forEach { collectHoistedVarNames(it, scope) }
                        is DefaultClause -> clause.statements.forEach { collectHoistedVarNames(it, scope) }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectHoistedVarNames(it, scope) }
                stmt.catchClause?.block?.statements?.forEach { collectHoistedVarNames(it, scope) }
                stmt.finallyBlock?.statements?.forEach { collectHoistedVarNames(it, scope) }
            }
            is LabeledStatement -> collectHoistedVarNames(stmt.statement, scope)
            is WithStatement -> collectHoistedVarNames(stmt.statement, scope)
            // Do NOT recurse into functions/classes — they create their own scope
            else -> {}
        }
    }

    private fun addBindingName(name: Node, scope: NameScope) {
        when (name) {
            is Identifier -> scope.names.add(name.text)
            is ObjectBindingPattern -> {
                for (element in name.elements) {
                    addBindingName(element.name, scope)
                }
            }
            is ArrayBindingPattern -> {
                for (element in name.elements) {
                    if (element is BindingElement) {
                        addBindingName(element.name, scope)
                    }
                }
            }
            else -> {}
        }
    }

    private fun checkUnresolvedInStatement(
        stmt: Statement,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        if (checkDepth > maxCheckDepth) return
        checkDepth++
        try { checkUnresolvedInStatementCore(stmt, scope, source, fileName) }
        finally { checkDepth-- }
    }

    private fun checkUnresolvedInStatementCore(
        stmt: Statement,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkUnresolvedInExpr(it, scope, source, fileName) }
                    decl.type?.let { checkUnresolvedInType(it, scope, source, fileName) }
                }
            }
            is ExpressionStatement -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
            }
            is ReturnStatement -> {
                stmt.expression?.let { checkUnresolvedInExpr(it, scope, source, fileName) }
            }
            is IfStatement -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
                checkUnresolvedInStatement(stmt.thenStatement, scope, source, fileName)
                stmt.elseStatement?.let { checkUnresolvedInStatement(it, scope, source, fileName) }
            }
            is Block -> {
                checkUnresolvedInStatements(stmt.statements, scope, source, fileName)
            }
            is ForStatement -> {
                val forScope = scope.child()
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            addBindingName(decl.name, forScope)
                            decl.initializer?.let { checkUnresolvedInExpr(it, forScope, source, fileName) }
                            decl.type?.let { checkUnresolvedInType(it, forScope, source, fileName) }
                        }
                    }
                    is Expression -> checkUnresolvedInExpr(init, forScope, source, fileName)
                    else -> {}
                }
                stmt.condition?.let { checkUnresolvedInExpr(it, forScope, source, fileName) }
                stmt.incrementor?.let { checkUnresolvedInExpr(it, forScope, source, fileName) }
                checkUnresolvedInStatement(stmt.statement, forScope, source, fileName)
            }
            is ForInStatement -> {
                val forScope = scope.child()
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            addBindingName(decl.name, forScope)
                        }
                    }
                    else -> {}
                }
                checkUnresolvedInExpr(stmt.expression, forScope, source, fileName)
                checkUnresolvedInStatement(stmt.statement, forScope, source, fileName)
            }
            is ForOfStatement -> {
                val forScope = scope.child()
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            addBindingName(decl.name, forScope)
                        }
                    }
                    else -> {}
                }
                checkUnresolvedInExpr(stmt.expression, forScope, source, fileName)
                checkUnresolvedInStatement(stmt.statement, forScope, source, fileName)
            }
            is WhileStatement -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
                checkUnresolvedInStatement(stmt.statement, scope, source, fileName)
            }
            is DoStatement -> {
                checkUnresolvedInStatement(stmt.statement, scope, source, fileName)
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
            }
            is SwitchStatement -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            checkUnresolvedInExpr(clause.expression, scope, source, fileName)
                            checkUnresolvedInStatements(clause.statements, scope, source, fileName)
                        }
                        is DefaultClause -> {
                            checkUnresolvedInStatements(clause.statements, scope, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> {
                stmt.expression?.let { checkUnresolvedInExpr(it, scope, source, fileName) }
            }
            is TryStatement -> {
                checkUnresolvedInStatements(stmt.tryBlock.statements, scope, source, fileName)
                stmt.catchClause?.let { clause ->
                    val catchScope = scope.child()
                    clause.variableDeclaration?.let { decl ->
                        addBindingName(decl.name, catchScope)
                    }
                    checkUnresolvedInStatements(clause.block.statements, catchScope, source, fileName)
                }
                stmt.finallyBlock?.let {
                    checkUnresolvedInStatements(it.statements, scope, source, fileName)
                }
            }
            is LabeledStatement -> {
                checkUnresolvedInStatement(stmt.statement, scope, source, fileName)
            }
            is WithStatement -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
                // Inside with() body, name resolution is dynamic — skip checking
            }
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val fnScope = scope.child(hasArguments = true)
                addParamsToScope(stmt.parameters, fnScope)
                stmt.typeParameters?.forEach { fnScope.names.add(it.name.text) }
                // Check type parameter constraints
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                    tp.default?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                }
                stmt.type?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                for (param in stmt.parameters) {
                    param.type?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                    param.initializer?.let { checkUnresolvedInExpr(it, fnScope, source, fileName) }
                }
                stmt.body?.let {
                    checkUnresolvedInStatements(it.statements, fnScope, source, fileName)
                }
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val classScope = scope.child()
                stmt.typeParameters?.forEach { classScope.names.add(it.name.text) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { checkUnresolvedInType(it, classScope, source, fileName) }
                    tp.default?.let { checkUnresolvedInType(it, classScope, source, fileName) }
                }
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, classScope, source, fileName)
                        checkHeritageTypeArgCount(type, classScope, source, fileName)
                        type.typeArguments?.forEach { checkUnresolvedInType(it, classScope, source, fileName) }
                    }
                }
                for (member in stmt.members) {
                    checkUnresolvedInClassElement(member, classScope, source, fileName)
                }
            }
            is InterfaceDeclaration -> {
                val ifaceScope = scope.child()
                stmt.typeParameters?.forEach { ifaceScope.names.add(it.name.text) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                    tp.default?.let { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                }
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, ifaceScope, source, fileName)
                        checkHeritageTypeArgCount(type, ifaceScope, source, fileName)
                        type.typeArguments?.forEach { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                    }
                }
                for (member in stmt.members) {
                    when (member) {
                        is PropertyDeclaration -> {
                            member.type?.let { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                        }
                        is MethodDeclaration -> {
                            val methodScope = ifaceScope.child()
                            member.typeParameters?.forEach { methodScope.names.add(it.name.text) }
                            member.typeParameters?.forEach { tp ->
                                tp.constraint?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                                tp.default?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            addParamsToScope(member.parameters, methodScope)
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            member.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                        }
                        is IndexSignature -> {
                            member.type?.let { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, ifaceScope, source, fileName) }
                            }
                        }
                        else -> {}
                    }
                }
            }
            is TypeAliasDeclaration -> {
                val typeScope = scope.child()
                stmt.typeParameters?.forEach { typeScope.names.add(it.name.text) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { checkUnresolvedInType(it, typeScope, source, fileName) }
                    tp.default?.let { checkUnresolvedInType(it, typeScope, source, fileName) }
                }
                checkUnresolvedInType(stmt.type, typeScope, source, fileName)
            }
            is EnumDeclaration -> {
                // Enum member initializers can reference other members and the enum itself
                val enumScope = scope.child()
                // Add members from ALL merged enum declarations (via binder symbol)
                // Only add EnumMember symbols, not namespace exports that merged with the same symbol
                val enumResult = fileResults[fileName]
                val enumSymbol = enumResult?.nodeToSymbol?.get(nodeKey(stmt))
                if (enumSymbol?.exports != null) {
                    for ((exportName, sym) in enumSymbol.exports!!) {
                        if (sym.flags.hasAny(SymbolFlags.EnumMember)) {
                            enumScope.names.add(exportName)
                        }
                    }
                }
                // Also add members from this specific declaration (covers cases without binder symbol)
                for (member in stmt.members) {
                    val memberName = member.name
                    if (memberName is Identifier) enumScope.names.add(memberName.text)
                    else if (memberName is StringLiteralNode) enumScope.names.add(memberName.text)
                }
                for (member in stmt.members) {
                    member.initializer?.let { checkUnresolvedInExpr(it, enumScope, source, fileName) }
                }
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                // Build namespace scope that includes merged exports from all declarations
                val nsScope = buildNamespaceScope(stmt, scope, fileName)
                when (val body = stmt.body) {
                    is ModuleBlock -> checkUnresolvedInStatements(body.statements, nsScope, source, fileName)
                    is ModuleDeclaration -> checkUnresolvedInStatement(body, nsScope, source, fileName)
                    else -> {}
                }
            }
            is ExportDeclaration -> {
                // export { X } — check that X exists
                if (stmt.moduleSpecifier == null) {
                    when (val clause = stmt.exportClause) {
                        is NamedExports -> {
                            for (spec in clause.elements) {
                                val name = spec.propertyName?.text ?: spec.name.text
                                val node = spec.propertyName ?: spec.name
                                checkIdentifierResolved(name, node, scope, source, fileName)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is ExportAssignment -> {
                checkUnresolvedInExpr(stmt.expression, scope, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkUnresolvedInClassElement(
        element: ClassElement,
        classScope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (element) {
            is PropertyDeclaration -> {
                element.type?.let { checkUnresolvedInType(it, classScope, source, fileName) }
                element.initializer?.let { checkUnresolvedInExpr(it, classScope, source, fileName) }
            }
            is MethodDeclaration -> {
                val methodScope = classScope.child(hasArguments = true)
                element.typeParameters?.forEach { methodScope.names.add(it.name.text) }
                element.typeParameters?.forEach { tp ->
                    tp.constraint?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                    tp.default?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                }
                addParamsToScope(element.parameters, methodScope)
                for (param in element.parameters) {
                    param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                    param.initializer?.let { checkUnresolvedInExpr(it, methodScope, source, fileName) }
                }
                element.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                element.body?.let {
                    checkUnresolvedInStatements(it.statements, methodScope, source, fileName)
                }
            }
            is IndexSignature -> {
                element.type?.let { checkUnresolvedInType(it, classScope, source, fileName) }
                for (param in element.parameters) {
                    param.type?.let { checkUnresolvedInType(it, classScope, source, fileName) }
                }
            }
            is Constructor -> {
                val ctorScope = classScope.child(hasArguments = true)
                addParamsToScope(element.parameters, ctorScope)
                for (param in element.parameters) {
                    param.type?.let { checkUnresolvedInType(it, ctorScope, source, fileName) }
                    param.initializer?.let { checkUnresolvedInExpr(it, ctorScope, source, fileName) }
                }
                element.body?.let {
                    checkUnresolvedInStatements(it.statements, ctorScope, source, fileName)
                }
            }
            is GetAccessor -> {
                val getScope = classScope.child(hasArguments = true)
                element.type?.let { checkUnresolvedInType(it, getScope, source, fileName) }
                element.body?.let {
                    checkUnresolvedInStatements(it.statements, getScope, source, fileName)
                }
            }
            is SetAccessor -> {
                val setScope = classScope.child(hasArguments = true)
                addParamsToScope(element.parameters, setScope)
                for (param in element.parameters) {
                    param.type?.let { checkUnresolvedInType(it, setScope, source, fileName) }
                }
                element.body?.let {
                    checkUnresolvedInStatements(it.statements, setScope, source, fileName)
                }
            }
            is ClassStaticBlockDeclaration -> {
                checkUnresolvedInStatements(element.body.statements, classScope, source, fileName)
            }
            else -> {}
        }
    }

    private fun addParamsToScope(params: List<Parameter>, scope: NameScope) {
        for (param in params) {
            addBindingName(param.name, scope)
        }
    }

    private fun checkUnresolvedInExpr(
        expr: Expression,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        if (checkDepth > maxCheckDepth) return
        checkDepth++
        try { checkUnresolvedInExprCore(expr, scope, source, fileName) }
        finally { checkDepth-- }
    }

    private fun checkUnresolvedInExprCore(
        expr: Expression,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (expr) {
            is Identifier -> {
                checkIdentifierResolved(expr.text, expr, scope, source, fileName)
            }
            is PropertyAccessExpression -> {
                // Only check the object, not the property name
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is ElementAccessExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
                checkUnresolvedInExpr(expr.argumentExpression, scope, source, fileName)
            }
            is CallExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
                expr.arguments.forEach { checkUnresolvedInExpr(it, scope, source, fileName) }
                expr.typeArguments?.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            }
            is NewExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
                expr.arguments?.forEach { checkUnresolvedInExpr(it, scope, source, fileName) }
                expr.typeArguments?.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            }
            is BinaryExpression -> {
                checkUnresolvedInExpr(expr.left, scope, source, fileName)
                checkUnresolvedInExpr(expr.right, scope, source, fileName)
            }
            is PrefixUnaryExpression -> {
                checkUnresolvedInExpr(expr.operand, scope, source, fileName)
            }
            is PostfixUnaryExpression -> {
                checkUnresolvedInExpr(expr.operand, scope, source, fileName)
            }
            is ConditionalExpression -> {
                checkUnresolvedInExpr(expr.condition, scope, source, fileName)
                checkUnresolvedInExpr(expr.whenTrue, scope, source, fileName)
                checkUnresolvedInExpr(expr.whenFalse, scope, source, fileName)
            }
            is ParenthesizedExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is TypeAssertionExpression -> {
                checkUnresolvedInType(expr.type, scope, source, fileName)
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is AsExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
                checkUnresolvedInType(expr.type, scope, source, fileName)
            }
            is NonNullExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is ArrowFunction -> {
                val arrowScope = scope.child(hasArguments = false)
                arrowScope.names.addAll(
                    expr.typeParameters?.map { it.name.text } ?: emptyList()
                )
                addParamsToScope(expr.parameters, arrowScope)
                for (param in expr.parameters) {
                    param.type?.let { checkUnresolvedInType(it, arrowScope, source, fileName) }
                    param.initializer?.let { checkUnresolvedInExpr(it, arrowScope, source, fileName) }
                }
                expr.type?.let { checkUnresolvedInType(it, arrowScope, source, fileName) }
                when (val body = expr.body) {
                    is Block -> checkUnresolvedInStatements(body.statements, arrowScope, source, fileName)
                    is Expression -> checkUnresolvedInExpr(body, arrowScope, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                val fnScope = scope.child(hasArguments = true)
                expr.name?.let { fnScope.names.add(it.text) }
                expr.typeParameters?.forEach { fnScope.names.add(it.name.text) }
                addParamsToScope(expr.parameters, fnScope)
                for (param in expr.parameters) {
                    param.type?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                    param.initializer?.let { checkUnresolvedInExpr(it, fnScope, source, fileName) }
                }
                expr.type?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                checkUnresolvedInStatements(expr.body.statements, fnScope, source, fileName)
            }
            is ClassExpression -> {
                val classScope = scope.child()
                // Class expression name is in scope within its own body
                expr.name?.let { classScope.names.add(it.text) }
                expr.typeParameters?.forEach { classScope.names.add(it.name.text) }
                expr.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, classScope, source, fileName)
                        checkHeritageTypeArgCount(type, classScope, source, fileName)
                        type.typeArguments?.forEach { checkUnresolvedInType(it, classScope, source, fileName) }
                    }
                }
                for (member in expr.members) {
                    checkUnresolvedInClassElement(member, classScope, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            // Check computed property names
                            if (prop.name is ComputedPropertyName) {
                                checkUnresolvedInExpr(
                                    (prop.name as ComputedPropertyName).expression,
                                    scope, source, fileName
                                )
                            }
                            checkUnresolvedInExpr(prop.initializer, scope, source, fileName)
                        }
                        is ShorthandPropertyAssignment -> {
                            checkUnresolvedInExpr(prop.name, scope, source, fileName)
                        }
                        is SpreadAssignment -> {
                            checkUnresolvedInExpr(prop.expression, scope, source, fileName)
                        }
                        is MethodDeclaration -> {
                            val methodScope = scope.child(hasArguments = true)
                            prop.typeParameters?.forEach { methodScope.names.add(it.name.text) }
                            addParamsToScope(prop.parameters, methodScope)
                            for (param in prop.parameters) {
                                param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                                param.initializer?.let { checkUnresolvedInExpr(it, methodScope, source, fileName) }
                            }
                            prop.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            prop.body?.let {
                                checkUnresolvedInStatements(it.statements, methodScope, source, fileName)
                            }
                        }
                        is GetAccessor -> {
                            prop.body?.let {
                                checkUnresolvedInStatements(it.statements, scope, source, fileName)
                            }
                        }
                        is SetAccessor -> {
                            val setScope = scope.child(hasArguments = true)
                            addParamsToScope(prop.parameters, setScope)
                            prop.body?.let {
                                checkUnresolvedInStatements(it.statements, setScope, source, fileName)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { checkUnresolvedInExpr(it, scope, source, fileName) }
            }
            is SpreadElement -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    checkUnresolvedInExpr(span.expression, scope, source, fileName)
                }
            }
            is TaggedTemplateExpression -> {
                checkUnresolvedInExpr(expr.tag, scope, source, fileName)
                when (val template = expr.template) {
                    is TemplateExpression -> {
                        for (span in template.templateSpans) {
                            checkUnresolvedInExpr(span.expression, scope, source, fileName)
                        }
                    }
                    else -> {}
                }
            }
            is TypeOfExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is VoidExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is DeleteExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is AwaitExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
            }
            is YieldExpression -> {
                expr.expression?.let { checkUnresolvedInExpr(it, scope, source, fileName) }
            }
            is CommaListExpression -> {
                expr.elements.forEach { checkUnresolvedInExpr(it, scope, source, fileName) }
            }
            is SatisfiesExpression -> {
                checkUnresolvedInExpr(expr.expression, scope, source, fileName)
                checkUnresolvedInType(expr.type, scope, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkUnresolvedInType(
        type: TypeNode,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        if (checkDepth > maxCheckDepth) return
        checkDepth++
        try { checkUnresolvedInTypeCore(type, scope, source, fileName) }
        finally { checkDepth-- }
    }

    private fun checkUnresolvedInTypeCore(
        type: TypeNode,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (type) {
            is TypeReference -> {
                // Check the type name (Identifier or QualifiedName)
                checkTypeNameResolved(type.typeName, scope, source, fileName)
                // Check type argument count (TS2314)
                checkTypeArgCount(type, scope, source, fileName)
                type.typeArguments?.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            }
            is ArrayType -> checkUnresolvedInType(type.elementType, scope, source, fileName)
            is TupleType -> type.elements.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            is UnionType -> type.types.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            is IntersectionType -> type.types.forEach { checkUnresolvedInType(it, scope, source, fileName) }
            is ParenthesizedType -> checkUnresolvedInType(type.type, scope, source, fileName)
            is TypeOperator -> checkUnresolvedInType(type.type, scope, source, fileName)
            is IndexedAccessType -> {
                checkUnresolvedInType(type.objectType, scope, source, fileName)
                checkUnresolvedInType(type.indexType, scope, source, fileName)
            }
            is MappedType -> {
                val mappedScope = scope.child()
                type.typeParameter?.let { mappedScope.names.add(it.name.text) }
                type.type?.let { checkUnresolvedInType(it, mappedScope, source, fileName) }
                type.nameType?.let { checkUnresolvedInType(it, mappedScope, source, fileName) }
            }
            is ConditionalType -> {
                checkUnresolvedInType(type.checkType, scope, source, fileName)
                checkUnresolvedInType(type.extendsType, scope, source, fileName)
                // infer creates new type names in the true branch
                val trueScope = scope.child()
                collectInferTypeNames(type.extendsType, trueScope)
                checkUnresolvedInType(type.trueType, trueScope, source, fileName)
                checkUnresolvedInType(type.falseType, scope, source, fileName)
            }
            is FunctionType -> {
                val fnScope = scope.child()
                type.typeParameters?.forEach { fnScope.names.add(it.name.text) }
                addParamsToScope(type.parameters, fnScope)
                for (param in type.parameters) {
                    param.type?.let { checkUnresolvedInType(it, fnScope, source, fileName) }
                }
                checkUnresolvedInType(type.type, fnScope, source, fileName)
            }
            is ConstructorType -> {
                val ctorScope = scope.child()
                type.typeParameters?.forEach { ctorScope.names.add(it.name.text) }
                addParamsToScope(type.parameters, ctorScope)
                for (param in type.parameters) {
                    param.type?.let { checkUnresolvedInType(it, ctorScope, source, fileName) }
                }
                checkUnresolvedInType(type.type, ctorScope, source, fileName)
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> {
                            member.type?.let { checkUnresolvedInType(it, scope, source, fileName) }
                        }
                        is MethodDeclaration -> {
                            val methodScope = scope.child()
                            member.typeParameters?.forEach { methodScope.names.add(it.name.text) }
                            member.typeParameters?.forEach { tp ->
                                tp.constraint?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                                tp.default?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            addParamsToScope(member.parameters, methodScope)
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            member.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                        }
                        is IndexSignature -> {
                            member.type?.let { checkUnresolvedInType(it, scope, source, fileName) }
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, scope, source, fileName) }
                            }
                        }
                        is Constructor -> {
                            val ctorScope = scope.child()
                            addParamsToScope(member.parameters, ctorScope)
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, ctorScope, source, fileName) }
                            }
                        }
                        else -> {}
                    }
                }
            }
            is TypeQuery -> {
                // typeof expr — check the expression name
                checkTypeQueryName(type.exprName, scope, source, fileName)
            }
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span ->
                    checkUnresolvedInType(span.type, scope, source, fileName)
                }
            }
            is InferType -> {
                // infer U — U is introduced, not referenced
            }
            is RestType -> {
                checkUnresolvedInType(type.type, scope, source, fileName)
            }
            is OptionalType -> {
                checkUnresolvedInType(type.type, scope, source, fileName)
            }
            is NamedTupleMember -> {
                checkUnresolvedInType(type.type, scope, source, fileName)
            }
            else -> {
                // LiteralType, KeywordType, ThisType, etc. — no name resolution needed
            }
        }
    }

    private fun collectInferTypeNames(type: TypeNode, scope: NameScope) {
        when (type) {
            is InferType -> scope.names.add(type.typeParameter.name.text)
            is UnionType -> type.types.forEach { collectInferTypeNames(it, scope) }
            is IntersectionType -> type.types.forEach { collectInferTypeNames(it, scope) }
            is TypeReference -> type.typeArguments?.forEach { collectInferTypeNames(it, scope) }
            is ArrayType -> collectInferTypeNames(type.elementType, scope)
            is TupleType -> type.elements.forEach { collectInferTypeNames(it, scope) }
            is ParenthesizedType -> collectInferTypeNames(type.type, scope)
            is ConditionalType -> {
                collectInferTypeNames(type.checkType, scope)
                collectInferTypeNames(type.extendsType, scope)
                collectInferTypeNames(type.trueType, scope)
                collectInferTypeNames(type.falseType, scope)
            }
            is FunctionType -> {
                type.parameters.forEach { p -> p.type?.let { collectInferTypeNames(it, scope) } }
                collectInferTypeNames(type.type, scope)
            }
            is RestType -> collectInferTypeNames(type.type, scope)
            else -> {}
        }
    }

    /**
     * Check if a type reference name (Identifier or QualifiedName) resolves.
     * For Identifiers, checks scope resolution (TS2304).
     * For QualifiedNames, checks leftmost identifier resolves AND that each
     * segment exists in namespace exports (TS2694).
     */
    private fun checkTypeNameResolved(
        name: Node,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (name) {
            is Identifier -> {
                checkIdentifierResolved(name.text, name, scope, source, fileName)
            }
            is QualifiedName -> {
                // Check the leftmost part of A.B.C
                var leftmost: Node = name
                while (leftmost is QualifiedName) leftmost = leftmost.left
                if (leftmost is Identifier) {
                    checkIdentifierResolved(leftmost.text, leftmost, scope, source, fileName)
                }
                // Check QualifiedName segments for TS2694
                checkQualifiedNameExports(name, source, fileName)
            }
            else -> {}
        }
    }

    /**
     * Check that each segment of a QualifiedName resolves through namespace exports.
     * Emits TS2694: "Namespace 'X' has no exported member 'Y'."
     */
    private fun checkQualifiedNameExports(
        qn: QualifiedName,
        source: String,
        fileName: String,
    ) {
        // Build the namespace path segments and resolve through binder symbols
        val segments = mutableListOf<String>()
        val rightId = qn.right

        // Collect all segments from left to right
        fun collectSegments(node: Node) {
            when (node) {
                is Identifier -> segments.add(node.text)
                is QualifiedName -> {
                    collectSegments(node.left)
                    segments.add(node.right.text)
                }
                else -> return
            }
        }
        collectSegments(qn.left)
        // segments now has all path segments except the rightmost

        // Resolve through the namespace chain
        var symbol = globals[segments.firstOrNull() ?: return]
        if (symbol == null) {
            // Also check file-level locals
            for (result in binderResults) {
                symbol = result.locals[segments.first()]
                if (symbol != null) break
            }
        }
        if (symbol == null) return // leftmost doesn't resolve — already flagged by TS2304

        symbol = resolveAlias(symbol)

        // Walk through intermediate segments (skip first, that's the root namespace)
        for (i in 1 until segments.size) {
            val exports = symbol!!.exports ?: return // not a namespace
            val next = exports[segments[i]]
            if (next == null) {
                // Intermediate segment not found — emit TS2694
                val namespacePath = segments.subList(0, i).joinToString(".")
                emitTS2694(namespacePath, segments[i], rightId, source, fileName)
                return
            }
            symbol = resolveAlias(next)
        }

        // Now check the final segment (rightmost identifier)
        val exports = symbol!!.exports
        if (exports == null) return // not a namespace, can't check
        val member = exports[rightId.text]
        val namespacePath = segments.joinToString(".")
        if (member == null) {
            // Member doesn't exist at all
            emitTS2694(namespacePath, rightId.text, rightId, source, fileName)
        } else if (!isMemberAccessible(member, symbol!!)) {
            // Member exists but is not exported from a non-declare namespace
            emitTS2694(namespacePath, rightId.text, rightId, source, fileName)
        }
    }

    /**
     * Check if a member symbol is accessible from outside its namespace.
     * In `declare` namespaces, all members are implicitly exported.
     * In regular namespaces, only members with ExportValue flag are accessible.
     */
    private fun isMemberAccessible(member: Symbol, namespace: Symbol): Boolean {
        // If member has explicit export, always accessible
        if (member.flags.hasAny(SymbolFlags.ExportValue)) return true
        // In declare namespaces, all members are accessible
        for (decl in namespace.declarations) {
            if (decl is ModuleDeclaration && ModifierFlag.Declare in decl.modifiers) {
                return true
            }
        }
        return false
    }

    private fun emitTS2694(
        namespacePath: String,
        memberName: String,
        memberNode: Identifier,
        source: String,
        fileName: String,
    ) {
        val start = memberNode.pos
        val length = memberName.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Namespace '$namespacePath' has no exported member '$memberName'.",
            category = DiagnosticCategory.Error,
            code = 2694,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /**
     * Check typeof expression names (e.g., `typeof x`, `typeof a.b.c`).
     */
    private fun checkTypeQueryName(
        name: Node,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        when (name) {
            is Identifier -> {
                checkIdentifierResolved(name.text, name, scope, source, fileName)
            }
            is QualifiedName -> {
                var leftmost: Node = name
                while (leftmost is QualifiedName) leftmost = leftmost.left
                if (leftmost is Identifier) {
                    checkIdentifierResolved(leftmost.text, leftmost, scope, source, fileName)
                }
            }
            else -> {}
        }
    }

    /**
     * Build a scope for a namespace body that includes all merged exports.
     * This allows `namespace A { export class Foo {} } namespace A { new Foo() }`
     * to resolve `Foo` in the second block.
     */
    private fun buildNamespaceScope(stmt: ModuleDeclaration, parentScope: NameScope, fileName: String): NameScope {
        val result = fileResults[fileName] ?: return parentScope
        // For simple identifier names, look up the binder symbol directly
        val name = stmt.name
        val symbol = when (name) {
            is Identifier -> {
                // Look up the merged symbol for this namespace via nodeToSymbol
                result.nodeToSymbol[nodeKey(stmt)]
            }
            is PropertyAccessExpression -> {
                // Dotted namespace: resolve through globals chain (A.B.C → globals["A"].exports["B"].exports["C"])
                val segments = mutableListOf<String>()
                var cur: Expression = name
                while (cur is PropertyAccessExpression) {
                    segments.add(0, cur.name.text)
                    cur = cur.expression
                }
                if (cur is Identifier) segments.add(0, cur.text)
                var sym: Symbol? = null
                for ((i, seg) in segments.withIndex()) {
                    sym = if (i == 0) {
                        result.locals[seg] ?: globals[seg]
                    } else {
                        sym?.exports?.get(seg)
                    }
                    if (sym == null) break
                }
                sym
            }
            else -> null
        }
        if (symbol?.exports == null || symbol.exports!!.isEmpty()) return parentScope
        val nsScope = parentScope.child()
        for ((exportName, _) in symbol.exports!!) {
            nsScope.names.add(exportName)
        }
        return nsScope
    }

    /**
     * Check if an identifier name can be resolved in the current scope chain.
     * If not, emit TS2304.
     */
    private fun checkIdentifierResolved(
        name: String,
        node: Node,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        // Skip empty/synthetic names or non-identifier text from parser recovery
        if (name.isEmpty()) return
        if (name[0] !in 'A'..'Z' && name[0] !in 'a'..'z' && name[0] != '_' && name[0] != '$') return
        // Skip keywords that parse as identifiers in our AST
        if (name in KEYWORD_IDENTIFIERS) return
        // Skip well-known globals that don't need declaration
        if (scope.has(name)) return

        val start = node.pos
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)

        diagnostics.add(Diagnostic(
            message = "Cannot find name '$name'.",
            category = DiagnosticCategory.Error,
            code = 2304,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // JSX implicit any checking (TS7026)
    // -----------------------------------------------------------------------

    /**
     * Check for JSX elements when no JSX.IntrinsicElements interface is defined.
     * Since we don't have lib.d.ts JSX type definitions, this fires for all JSX
     * elements when JSX is configured.
     * Emits TS7026 at both opening and closing tag names.
     */
    private fun checkJsxImplicitAny() {
        // Skip when jsxFactory is explicitly set or jsx is preserve/react-native
        // (TypeScript doesn't check implicit any in these cases)
        if (options.jsxFactory != null) return
        val jsxMode = options.jsx?.lowercase()
        if (jsxMode == "preserve" || jsxMode == "react-native") return
        for (result in binderResults) {
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            // Only check .tsx/.jsx files
            if (!fileName.endsWith(".tsx") && !fileName.endsWith(".jsx")) continue
            checkJsxInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkJsxInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            checkJsxInStatement(stmt, source, fileName)
        }
    }

    private fun checkJsxInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkJsxInExpr(it, source, fileName) }
                }
            }
            is ExpressionStatement -> checkJsxInExpr(stmt.expression, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkJsxInExpr(it, source, fileName) }
            is FunctionDeclaration -> {
                stmt.body?.let { checkJsxInStatements(it.statements, source, fileName) }
                for (param in stmt.parameters) {
                    param.initializer?.let { checkJsxInExpr(it, source, fileName) }
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            member.body?.let { checkJsxInStatements(it.statements, source, fileName) }
                        }
                        is Constructor -> {
                            member.body?.let { checkJsxInStatements(it.statements, source, fileName) }
                        }
                        is PropertyDeclaration -> {
                            member.initializer?.let { checkJsxInExpr(it, source, fileName) }
                        }
                        is GetAccessor -> {
                            member.body?.let { checkJsxInStatements(it.statements, source, fileName) }
                        }
                        is SetAccessor -> {
                            member.body?.let { checkJsxInStatements(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            is Block -> checkJsxInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkJsxInExpr(stmt.expression, source, fileName)
                checkJsxInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkJsxInStatement(it, source, fileName) }
            }
            is ForStatement -> {
                stmt.condition?.let { checkJsxInExpr(it, source, fileName) }
                checkJsxInStatement(stmt.statement, source, fileName)
            }
            is ForInStatement -> checkJsxInStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkJsxInStatement(stmt.statement, source, fileName)
            is WhileStatement -> {
                checkJsxInExpr(stmt.expression, source, fileName)
                checkJsxInStatement(stmt.statement, source, fileName)
            }
            is DoStatement -> {
                checkJsxInStatement(stmt.statement, source, fileName)
                checkJsxInExpr(stmt.expression, source, fileName)
            }
            is SwitchStatement -> {
                checkJsxInExpr(stmt.expression, source, fileName)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            checkJsxInExpr(clause.expression, source, fileName)
                            checkJsxInStatements(clause.statements, source, fileName)
                        }
                        is DefaultClause -> checkJsxInStatements(clause.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                checkJsxInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkJsxInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkJsxInStatements(it.statements, source, fileName) }
            }
            is LabeledStatement -> checkJsxInStatement(stmt.statement, source, fileName)
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> checkJsxInStatements(body.statements, source, fileName)
                    else -> {}
                }
            }
            is ExportAssignment -> checkJsxInExpr(stmt.expression, source, fileName)
            else -> {}
        }
    }

    private fun checkJsxInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is JsxElement -> {
                emitJsx7026(expr.openingElement.tagName, source, fileName)
                // Check children for nested JSX
                for (child in expr.children) {
                    when (child) {
                        is Expression -> checkJsxInExpr(child, source, fileName)
                        is JsxExpressionContainer -> child.expression?.let { checkJsxInExpr(it, source, fileName) }
                        else -> {}
                    }
                }
                emitJsx7026(expr.closingElement.tagName, source, fileName)
            }
            is JsxSelfClosingElement -> {
                emitJsx7026(expr.tagName, source, fileName)
            }
            is JsxFragment -> {
                for (child in expr.children) {
                    when (child) {
                        is Expression -> checkJsxInExpr(child, source, fileName)
                        is JsxExpressionContainer -> child.expression?.let { checkJsxInExpr(it, source, fileName) }
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> checkJsxInExpr(expr.expression, source, fileName)
            is ConditionalExpression -> {
                checkJsxInExpr(expr.condition, source, fileName)
                checkJsxInExpr(expr.whenTrue, source, fileName)
                checkJsxInExpr(expr.whenFalse, source, fileName)
            }
            is BinaryExpression -> {
                checkJsxInExpr(expr.left, source, fileName)
                checkJsxInExpr(expr.right, source, fileName)
            }
            is CallExpression -> {
                checkJsxInExpr(expr.expression, source, fileName)
                expr.arguments.forEach { checkJsxInExpr(it, source, fileName) }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkJsxInStatements(body.statements, source, fileName)
                    is Expression -> checkJsxInExpr(body, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkJsxInStatements(expr.body.statements, source, fileName)
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { checkJsxInExpr(it, source, fileName) }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> checkJsxInExpr(prop.initializer, source, fileName)
                        is SpreadAssignment -> checkJsxInExpr(prop.expression, source, fileName)
                        else -> {}
                    }
                }
            }
            is AsExpression -> checkJsxInExpr(expr.expression, source, fileName)
            is NonNullExpression -> checkJsxInExpr(expr.expression, source, fileName)
            is CommaListExpression -> {
                expr.elements.forEach { checkJsxInExpr(it, source, fileName) }
            }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    checkJsxInExpr(span.expression, source, fileName)
                }
            }
            is TaggedTemplateExpression -> {
                checkJsxInExpr(expr.tag, source, fileName)
            }
            is SpreadElement -> checkJsxInExpr(expr.expression, source, fileName)
            is AwaitExpression -> expr.expression?.let { checkJsxInExpr(it, source, fileName) }
            is YieldExpression -> expr.expression?.let { checkJsxInExpr(it, source, fileName) }
            else -> {}
        }
    }

    private fun emitJsx7026(tagName: Expression, source: String, fileName: String) {
        val start = tagName.pos
        val length = tagName.end - tagName.pos
        if (length <= 0) return
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "JSX element implicitly has type 'any' because no interface 'JSX.IntrinsicElements' exists.",
            category = DiagnosticCategory.Error,
            code = 7026,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Duplicate identifier checking (TS2300)
    // -----------------------------------------------------------------------

    /**
     * Check for duplicate identifiers: duplicate type parameters,
     * duplicate function parameters, and duplicate declarations in the same scope.
     */
    private fun checkDuplicateIdentifiers() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkDuplicatesInStatements(result.sourceFile.statements, source, fileName)
            // Check file-level duplicate declarations
            checkDuplicateDeclarations(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkDuplicatesInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            checkDuplicatesInStatement(stmt, source, fileName)
        }
    }

    private fun checkDuplicatesInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is FunctionDeclaration -> {
                checkDuplicateTypeParams(stmt.typeParameters, source, fileName)
                checkDuplicateParams(stmt.parameters, source, fileName)
                checkDuplicatesInType(stmt.type, source, fileName)
                for (param in stmt.parameters) checkDuplicatesInType(param.type, source, fileName)
                stmt.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                checkDuplicateTypeParams(stmt.typeParameters, source, fileName)
                for (member in stmt.members) {
                    checkDuplicatesInClassElement(member, source, fileName)
                }
                checkDuplicateClassMembers(stmt.members, source, fileName)
            }
            is InterfaceDeclaration -> {
                checkDuplicateTypeParams(stmt.typeParameters, source, fileName)
                checkDuplicateInterfaceMembers(stmt.members, source, fileName)
                for (member in stmt.members) {
                    checkDuplicatesInTypeMember(member, source, fileName)
                }
            }
            is TypeAliasDeclaration -> {
                checkDuplicateTypeParams(stmt.typeParameters, source, fileName)
                checkDuplicatesInType(stmt.type, source, fileName)
            }
            is EnumDeclaration -> {
                checkDuplicateEnumMembers(stmt, source, fileName)
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkDuplicatesInExpr(it, source, fileName) }
                }
            }
            is ExpressionStatement -> checkDuplicatesInExpr(stmt.expression, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkDuplicatesInExpr(it, source, fileName) }
            is Block -> checkDuplicatesInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkDuplicatesInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkDuplicatesInStatement(it, source, fileName) }
            }
            is ForStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> checkDuplicatesInStatements(clause.statements, source, fileName)
                        is DefaultClause -> checkDuplicatesInStatements(clause.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                checkDuplicatesInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkDuplicatesInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            is LabeledStatement -> checkDuplicatesInStatement(stmt.statement, source, fileName)
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> {
                        checkDuplicatesInStatements(body.statements, source, fileName)
                        checkDuplicateDeclarations(body.statements, source, fileName)
                    }
                    else -> {}
                }
            }
            is ExportDeclaration -> {
                val exportClause = stmt.exportClause
                if (exportClause is NamedExports) {
                    checkDuplicateExportSpecifiers(exportClause.elements, source, fileName)
                }
            }
            else -> {}
        }
    }

    private fun checkDuplicateExportSpecifiers(
        specifiers: List<ExportSpecifier>,
        source: String,
        fileName: String,
    ) {
        data class SpecInfo(val name: String, val nameNode: Identifier)
        val specs = mutableListOf<SpecInfo>()
        for (spec in specifiers) {
            specs.add(SpecInfo(spec.name.text, spec.name))
        }
        val byName = specs.groupBy { it.name }
        for ((_, group) in byName) {
            if (group.size >= 2) {
                for (spec in group) {
                    emitDuplicate2300(spec.name, spec.nameNode, source, fileName)
                }
            }
        }
    }

    private fun checkDuplicatesInClassElement(
        element: ClassElement,
        source: String,
        fileName: String,
    ) {
        when (element) {
            is MethodDeclaration -> {
                checkDuplicateTypeParams(element.typeParameters, source, fileName)
                checkDuplicateParams(element.parameters, source, fileName)
                element.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            is Constructor -> {
                checkDuplicateParams(element.parameters, source, fileName)
                element.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            is GetAccessor -> {
                element.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            is SetAccessor -> {
                checkDuplicateParams(element.parameters, source, fileName)
                element.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
            }
            else -> {}
        }
    }

    private fun checkDuplicatesInExpr(
        expr: Expression,
        source: String,
        fileName: String,
    ) {
        when (expr) {
            is ArrowFunction -> {
                checkDuplicateTypeParams(expr.typeParameters, source, fileName)
                checkDuplicateParams(expr.parameters, source, fileName)
                when (val body = expr.body) {
                    is Block -> checkDuplicatesInStatements(body.statements, source, fileName)
                    is Expression -> checkDuplicatesInExpr(body, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkDuplicateTypeParams(expr.typeParameters, source, fileName)
                checkDuplicateParams(expr.parameters, source, fileName)
                checkDuplicatesInStatements(expr.body.statements, source, fileName)
            }
            is ClassExpression -> {
                checkDuplicateTypeParams(expr.typeParameters, source, fileName)
                for (member in expr.members) {
                    checkDuplicatesInClassElement(member, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is MethodDeclaration -> {
                            checkDuplicateTypeParams(prop.typeParameters, source, fileName)
                            checkDuplicateParams(prop.parameters, source, fileName)
                            prop.body?.let { checkDuplicatesInStatements(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Check for duplicate type parameter names in a type parameter list.
     */
    private fun checkDuplicateTypeParams(
        typeParams: List<TypeParameter>?,
        source: String,
        fileName: String,
    ) {
        if (typeParams == null || typeParams.size < 2) return
        val seen = mutableSetOf<String>()
        for (tp in typeParams) {
            val name = tp.name.text
            if (!seen.add(name)) {
                emitDuplicate2300(name, tp.name, source, fileName)
            }
        }
    }

    /**
     * Check for duplicate parameter names in a parameter list.
     */
    private fun checkDuplicateParams(
        params: List<Parameter>,
        source: String,
        fileName: String,
    ) {
        val seen = mutableMapOf<String, Node>() // name → first occurrence
        val duplicates = mutableListOf<Pair<String, Node>>()
        for (param in params) {
            collectBindingNames(param.name, seen, duplicates)
        }
        // Report duplicates
        for ((name, node) in duplicates) {
            emitDuplicate2300(name, node, source, fileName)
        }
    }

    private fun collectBindingNames(
        name: Node,
        seen: MutableMap<String, Node>,
        duplicates: MutableList<Pair<String, Node>>,
    ) {
        when (name) {
            is Identifier -> {
                val prev = seen[name.text]
                if (prev != null) {
                    // Report both the first occurrence and this one
                    if (prev !== name) {
                        duplicates.add(name.text to prev)
                    }
                    duplicates.add(name.text to name)
                } else {
                    seen[name.text] = name
                }
            }
            is ObjectBindingPattern -> {
                for (element in name.elements) {
                    collectBindingNames(element.name, seen, duplicates)
                }
            }
            is ArrayBindingPattern -> {
                for (element in name.elements) {
                    if (element is BindingElement) {
                        collectBindingNames(element.name, seen, duplicates)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Check for duplicate declarations at the same scope level.
     * Walks statements and tracks declaration names to detect incompatible duplicates.
     */
    private fun checkDuplicateDeclarations(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        // Collect all declaration names with their kind and node
        data class DeclInfo(val name: String, val kind: String, val nameNode: Node, val stmt: Statement? = null)
        val decls = mutableListOf<DeclInfo>()

        for (stmt in statements) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        val name = decl.name
                        if (name is Identifier) {
                            decls.add(DeclInfo(name.text, "var", name, stmt))
                        }
                    }
                }
                is FunctionDeclaration -> {
                    val name = stmt.name ?: continue
                    decls.add(DeclInfo(name.text, "function", name, stmt))
                }
                is ClassDeclaration -> {
                    val name = stmt.name ?: continue
                    decls.add(DeclInfo(name.text, "class", name, stmt))
                }
                is EnumDeclaration -> {
                    decls.add(DeclInfo(stmt.name.text, "enum", stmt.name, stmt))
                }
                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        decls.add(DeclInfo("export=", "export=", stmt.expression))
                    }
                }
                is ModuleDeclaration -> {
                    val name = stmt.name
                    if (name is Identifier) {
                        decls.add(DeclInfo(name.text, "namespace", name, stmt))
                    }
                }
                is InterfaceDeclaration -> {
                    decls.add(DeclInfo(stmt.name.text, "interface", stmt.name, stmt))
                }
                is ImportEqualsDeclaration -> {
                    val name = stmt.name
                    decls.add(DeclInfo(name.text, "import=", name))
                }
                else -> {}
            }
        }

        // Check for duplicate export= assignments
        val exportEquals = decls.filter { it.kind == "export=" }
        if (exportEquals.size >= 2) {
            for (decl in exportEquals) {
                val expr = decl.nameNode
                val len = if (expr is Identifier) expr.text.length else (expr.end - expr.pos)
                emitDuplicate2300("export=", expr, source, fileName, spanLength = len)
            }
        }

        // Group by name and check for incompatible declarations
        val byName = decls.groupBy { it.name }
        for ((_, group) in byName) {
            if (group.size < 2) continue
            val kinds = group.map { it.kind }.toSet()

            // Duplicate declarations → TS2300
            // Allowed: function overloads (multiple function declarations)
            // Allowed: namespace + namespace, namespace + class, namespace + function, namespace + enum
            // Allowed: interface + interface (declaration merging)
            // Error: class + class, class + function, class + enum, class + var
            // Error: var + class, var + function, var + enum
            // Error: enum + class, enum + var
            val hasVar = "var" in kinds
            val hasClass = "class" in kinds
            val hasEnum = "enum" in kinds
            val hasFunc = "function" in kinds
            val classCount = group.count { it.kind == "class" }

            // Check for duplicate import= declarations
            val hasImportEq = "import=" in kinds
            val importEqCount = group.count { it.kind == "import=" }
            if (hasImportEq && importEqCount >= 2) {
                for (decl in group.filter { it.kind == "import=" }) {
                    emitDuplicate2300(decl.name, decl.nameNode, source, fileName)
                }
            }

            // TS2567: Enum declarations can only merge with namespace or other enum declarations
            val hasInterface = "interface" in kinds
            if (hasEnum && (hasClass || hasFunc || hasVar || hasInterface)) {
                for (decl in group) {
                    if (decl.kind == "namespace") continue // namespace+enum is allowed
                    val start = decl.nameNode.pos
                    val (line, character) = getLineAndCharacterOfPosition(source, start)
                    diagnostics.add(Diagnostic(
                        message = "Enum declarations can only merge with namespace or other enum declarations.",
                        category = DiagnosticCategory.Error,
                        code = 2567,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = decl.name.length,
                    ))
                }
            } else {
                val hasNamespace = "namespace" in kinds

                // Check if namespace+var should be allowed:
                // A namespace can merge with a var when:
                // - The namespace is `declare` (ambient), OR
                // - The namespace only contains type declarations (interfaces, types) and no value exports
                // A `declare var` can merge with any namespace
                val namespaceVarAllowed = if (hasVar && hasNamespace) {
                    val allNamespacesValueFree = group.filter { it.kind == "namespace" }.all { decl ->
                        val modDecl = decl.stmt as? ModuleDeclaration ?: return@all true
                        !isNamespaceInstantiated(modDecl)
                    }
                    val allVarsDeclare = group.filter { it.kind == "var" }.all { decl ->
                        val varStmt = decl.stmt as? VariableStatement ?: return@all false
                        ModifierFlag.Declare in varStmt.modifiers
                    }
                    allNamespacesValueFree || allVarsDeclare
                } else false

                // declare function + declare class is a legal merge (function acts as constructor overload)
                val classFuncConflict = if (hasClass && hasFunc) {
                    val allFuncsDeclare = group.filter { it.kind == "function" }.all { decl ->
                        val funcStmt = decl.stmt as? FunctionDeclaration ?: return@all false
                        ModifierFlag.Declare in funcStmt.modifiers
                    }
                    val allClassesDeclare = group.filter { it.kind == "class" }.all { decl ->
                        val classStmt = decl.stmt as? ClassDeclaration ?: return@all false
                        ModifierFlag.Declare in classStmt.modifiers
                    }
                    !(allFuncsDeclare && allClassesDeclare)
                } else false

                val isDuplicate = (hasClass && classCount >= 2) ||
                        classFuncConflict ||
                        (hasVar && (hasClass || hasFunc)) ||
                        (hasVar && hasNamespace && !namespaceVarAllowed)

                if (isDuplicate) {
                    for (decl in group) {
                        emitDuplicate2300(decl.name, decl.nameNode, source, fileName)
                    }
                }
            }
        }
    }

    /**
     * Check for duplicate enum members: members with the same name.
     */
    private fun checkDuplicateEnumMembers(
        decl: EnumDeclaration,
        source: String,
        fileName: String,
    ) {
        data class MemberInfo(val name: String, val nameNode: Node)
        val members = mutableListOf<MemberInfo>()
        for (m in decl.members) {
            val name = m.name
            val text = when (name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                else -> continue
            }
            members.add(MemberInfo(text, name))
        }
        val byName = members.groupBy { it.name }
        for ((_, group) in byName) {
            if (group.size >= 2) {
                for (info in group) {
                    emitDuplicate2300(info.name, info.nameNode, source, fileName)
                }
            }
        }
    }

    /**
     * Check for duplicate interface members: properties with the same name.
     * Unlike classes, interfaces can have method overloads (same name, different params).
     * Only same-name properties are flagged.
     */
    private fun checkDuplicateInterfaceMembers(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        data class PropInfo(val name: String, val nameNode: Node)
        val props = mutableListOf<PropInfo>()
        for (member in members) {
            if (member is PropertyDeclaration) {
                val name = member.name
                val text = getMemberNameText(name) ?: continue
                props.add(PropInfo(text, name))
            }
        }
        val byName = props.groupBy { it.name }
        for ((_, group) in byName) {
            if (group.size >= 2) {
                for (prop in group) {
                    emitDuplicate2300(prop.name, prop.nameNode, source, fileName)
                }
            }
        }
    }

    /**
     * Check for duplicate class members: method + getter/setter, method + property, etc.
     */
    private fun checkDuplicateClassMembers(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        data class MemberInfo(val name: String, val groupKey: String, val kind: String, val nameNode: Node)

        val memberInfos = mutableListOf<MemberInfo>()
        for (member in members) {
            val isStatic = when (member) {
                is MethodDeclaration -> ModifierFlag.Static in member.modifiers
                is PropertyDeclaration -> ModifierFlag.Static in member.modifiers
                is GetAccessor -> ModifierFlag.Static in member.modifiers
                is SetAccessor -> ModifierFlag.Static in member.modifiers
                else -> false
            }
            val staticPrefix = if (isStatic) "static:" else ""
            when (member) {
                is MethodDeclaration -> {
                    val text = getMemberNameText(member.name) ?: continue
                    memberInfos.add(MemberInfo(text, "$staticPrefix${normalizeNumericKey(text)}", "method", member.name))
                }
                is PropertyDeclaration -> {
                    val text = getMemberNameText(member.name) ?: continue
                    memberInfos.add(MemberInfo(text, "$staticPrefix${normalizeNumericKey(text)}", "property", member.name))
                }
                is GetAccessor -> {
                    val text = getMemberNameText(member.name) ?: continue
                    memberInfos.add(MemberInfo(text, "$staticPrefix${normalizeNumericKey(text)}", "getter", member.name))
                }
                is SetAccessor -> {
                    val text = getMemberNameText(member.name) ?: continue
                    memberInfos.add(MemberInfo(text, "$staticPrefix${normalizeNumericKey(text)}", "setter", member.name))
                }
                else -> {}
            }
        }

        val byName = memberInfos.groupBy { it.groupKey }
        for ((_, group) in byName) {
            if (group.size < 2) continue
            val kinds = group.map { it.kind }.toSet()
            // method + getter or method + setter → TS2300
            // method + property → TS2300
            // getter + setter → ALLOWED (single pair only)
            // property + property → TS2300 (unless overloads)
            val hasMethod = "method" in kinds
            val hasGetter = "getter" in kinds
            val hasSetter = "setter" in kinds
            val hasProperty = "property" in kinds
            val getterCount = group.count { it.kind == "getter" }
            val setterCount = group.count { it.kind == "setter" }

            val isDuplicate = (hasMethod && (hasGetter || hasSetter || hasProperty)) ||
                    (hasProperty && (hasGetter || hasSetter)) ||
                    (getterCount >= 2) || (setterCount >= 2)

            if (isDuplicate) {
                for (info in group) {
                    emitDuplicate2300(info.name, info.nameNode, source, fileName)
                }
            }
        }
    }

    /**
     * Recursively walk type annotations checking for duplicates.
     */
    private fun checkDuplicatesInType(
        type: TypeNode?,
        source: String,
        fileName: String,
    ) {
        if (type == null) return
        when (type) {
            is TypeLiteral -> {
                checkDuplicateInterfaceMembers(type.members, source, fileName)
                for (member in type.members) {
                    checkDuplicatesInTypeMember(member, source, fileName)
                }
            }
            is FunctionType -> {
                checkDuplicateTypeParams(type.typeParameters, source, fileName)
                checkDuplicatesInType(type.type, source, fileName)
            }
            is ConstructorType -> {
                checkDuplicateTypeParams(type.typeParameters, source, fileName)
                checkDuplicatesInType(type.type, source, fileName)
            }
            is UnionType -> for (t in type.types) checkDuplicatesInType(t, source, fileName)
            is IntersectionType -> for (t in type.types) checkDuplicatesInType(t, source, fileName)
            is ParenthesizedType -> checkDuplicatesInType(type.type, source, fileName)
            is ArrayType -> checkDuplicatesInType(type.elementType, source, fileName)
            is TypePredicate -> checkDuplicatesInType(type.type, source, fileName)
            else -> {}
        }
    }

    private fun checkDuplicatesInTypeMember(
        member: ClassElement,
        source: String,
        fileName: String,
    ) {
        when (member) {
            is PropertyDeclaration -> checkDuplicatesInType(member.type, source, fileName)
            is MethodDeclaration -> {
                checkDuplicateTypeParams(member.typeParameters, source, fileName)
                checkDuplicatesInType(member.type, source, fileName)
            }
            else -> {}
        }
    }

    /** Extract the effective property name from a member name node. */
    private fun getMemberNameText(name: Node): String? = when (name) {
        is Identifier -> name.text
        is StringLiteralNode -> name.text
        is NumericLiteralNode -> name.text
        else -> null
    }

    /** Normalize numeric string to canonical form for duplicate comparison. */
    private fun normalizeNumericKey(text: String): String {
        val num = text.toDoubleOrNull() ?: return text
        // 0.0 → "0", 1.0 → "1", etc.
        return if (num == num.toLong().toDouble()) num.toLong().toString() else text
    }

    private fun emitDuplicate2300(
        name: String,
        node: Node,
        source: String,
        fileName: String,
        spanLength: Int = when (node) {
            is StringLiteralNode -> name.length + 2 // quotes
            is NumericLiteralNode -> node.text.length
            else -> name.length
        },
    ) {
        val start = node.pos
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Duplicate identifier '$name'.",
            category = DiagnosticCategory.Error,
            code = 2300,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = spanLength,
        ))
    }

    /**
     * Checks if a namespace/module declaration is "instantiated" — meaning it
     * contributes a runtime value. A namespace is instantiated when it is NOT
     * `declare` and its body contains value-producing statements.
     * Value-free namespaces (empty, or only containing interfaces/types) can
     * merge with var declarations without conflict.
     */
    private fun isNamespaceInstantiated(decl: ModuleDeclaration): Boolean {
        if (ModifierFlag.Declare in decl.modifiers) return false
        val body = decl.body as? ModuleBlock ?: return false
        return body.statements.any { s ->
            when (s) {
                is VariableStatement -> true
                is FunctionDeclaration -> true
                is ClassDeclaration -> true
                is EnumDeclaration -> true
                is ModuleDeclaration -> isNamespaceInstantiated(s)
                is ExportDeclaration -> {
                    // export { ... } from "..." or export { ... } — check if there's an actual re-export
                    // of values (not just types). For simplicity, assume value export.
                    true
                }
                else -> false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Export= in ES module checking (TS1203)
    // -----------------------------------------------------------------------

    /**
     * Check for TS1203/TS1202: export=/import= cannot be used with ES modules.
     */
    private fun checkExportAssignmentInEsModule() {
        val effectiveModule = options.effectiveModule
        // ES module kinds: ES2015, ES2020, ES2022, ESNext, Node16, NodeNext
        val isEsModule = effectiveModule >= ModuleKind.ES2015 ||
                effectiveModule == ModuleKind.Node16 || effectiveModule == ModuleKind.NodeNext
        if (!isEsModule) return

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text

            for (stmt in result.sourceFile.statements) {
                when {
                    stmt is ExportAssignment && stmt.isExportEquals -> {
                        emitStatementLineDiagnostic(
                            stmt, source, fileName, 1203,
                            "Export assignment cannot be used when targeting ECMAScript modules. Consider using 'export default' or another module format instead.",
                        )
                    }
                    stmt is ImportEqualsDeclaration -> {
                        emitStatementLineDiagnostic(
                            stmt, source, fileName, 1202,
                            "Import assignment cannot be used when targeting ECMAScript modules. Consider using 'import * as ns from \"mod\"', 'import {a} from \"mod\"', 'import d from \"mod\"', or another module format instead.",
                        )
                    }
                }
            }
        }
    }

    /** Emit a diagnostic for a statement, spanning the statement text (excluding comments). */
    private fun emitStatementLineDiagnostic(
        stmt: Statement, source: String, fileName: String, code: Int, message: String,
    ) {
        var start = stmt.pos
        while (start < source.length && source[start].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) start++
        // Use the statement's end but find the last non-whitespace before it
        // Also find the semicolon if present to end the span there
        val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        // Find the semicolon position (statement end) or trim trailing comment
        var end = start
        var i = start
        while (i < lineEnd) {
            val ch = source[i]
            if (ch == ';') {
                end = i + 1 // include the semicolon
                break
            }
            if (ch == '/' && i + 1 < lineEnd && (source[i + 1] == '/' || source[i + 1] == '*')) {
                // Comment starts — end before it
                while (end > start && source[end - 1].let { it == ' ' || it == '\t' }) end--
                break
            }
            end = i + 1
            i++
        }
        if (end <= start) end = start + 1
        val length = end - start
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = message,
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Unresolved module checking (TS2307)
    // -----------------------------------------------------------------------

    /**
     * Check for TS2307: "Cannot find module 'X' or its corresponding type declarations."
     * Emitted when an import module specifier doesn't resolve to a known file.
     */
    private fun checkUnresolvedModules() {
        // Only check in single-file compilations to avoid false positives
        // in multi-file tests where we can't fully resolve module paths
        if (binderResults.size > 1) return
        // Also skip when the source had @Filename directives — companion files
        // (.json, .js) may not have been parsed but exist as siblings
        if (isMultiFileSource) return

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text

            for (stmt in result.sourceFile.statements) {
                val specifier = when (stmt) {
                    is ImportDeclaration -> stmt.moduleSpecifier
                    is ExportDeclaration -> stmt.moduleSpecifier
                    is ImportEqualsDeclaration -> {
                        val ref = stmt.moduleReference
                        if (ref is ExternalModuleReference) ref.expression else null
                    }
                    else -> null
                }
                if (specifier == null) continue
                val moduleName = when (specifier) {
                    is StringLiteralNode -> specifier.text
                    else -> continue
                }
                // In single-file compilations, all module specifiers are unresolved
                emitTS2307(specifier, moduleName, source, fileName)
            }
        }
    }

    private fun emitTS2307(specifier: Expression, moduleName: String, source: String, fileName: String) {
        val start = specifier.pos
        val length = moduleName.length + 2 // +2 for quotes
        val (line, character) = getLineAndCharacterOfPosition(source, start)

        // TS2792 fires when moduleResolution is not node-based (classic, or default for
        // system/amd/es2015/esnext modules) — suggests switching to nodenext
        val moduleRes = options.moduleResolution?.lowercase()
        val effectiveModuleRes = moduleRes ?: run {
            // Default moduleResolution based on module option
            when (options.module) {
                ModuleKind.CommonJS -> "node10"
                ModuleKind.Node16 -> "node16"
                ModuleKind.NodeNext -> "nodenext"
                // System and AMD use classic resolution
                ModuleKind.System, ModuleKind.AMD, ModuleKind.UMD -> "classic"
                // ES module kinds (es2015, es2020, esnext) and null/none: default to node10 in TS6+
                else -> "node10"
            }
        }
        val isNodeResolution = effectiveModuleRes in setOf("node", "node10", "node16", "nodenext", "bundler")
        val code: Int
        val message: String
        if (isNodeResolution) {
            code = 2307
            message = "Cannot find module '$moduleName' or its corresponding type declarations."
        } else {
            code = 2792
            message = "Cannot find module '$moduleName'. Did you mean to set the 'moduleResolution' option to 'nodenext', or to add aliases to the 'paths' option?"
        }
        diagnostics.add(Diagnostic(
            message = message,
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Jump target checking (TS1107)
    // -----------------------------------------------------------------------

    /**
     * Check for TS1107: "Jump target cannot cross function boundary."
     * break/continue inside a nested function/arrow that targets an outer loop/label.
     */
    private fun checkJumpTargets() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkJumpInStatements(result.sourceFile.statements, source, fileName,
                inIteration = false, inSwitch = false, labelNames = emptySet(),
                crossedFunctionBoundary = false)
        }
    }

    private fun checkJumpInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        inIteration: Boolean,
        inSwitch: Boolean,
        labelNames: Set<String>,
        crossedFunctionBoundary: Boolean,
    ) {
        for (stmt in statements) {
            checkJumpInStatement(stmt, source, fileName, inIteration, inSwitch, labelNames, crossedFunctionBoundary)
        }
    }

    private fun checkJumpInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
        inIteration: Boolean,
        inSwitch: Boolean,
        labelNames: Set<String>,
        crossedFunctionBoundary: Boolean,
    ) {
        when (stmt) {
            is BreakStatement -> {
                val label = stmt.label?.text
                if (label != null) {
                    // Labeled break — check if label is in scope
                    if (label !in labelNames) {
                        if (crossedFunctionBoundary) {
                            emitJumpDiagnostic(stmt, source, fileName, 1107,
                                "Jump target cannot cross function boundary.")
                        } else {
                            emitJumpDiagnostic(stmt, source, fileName, 1116,
                                "A 'break' statement can only jump to a label of an enclosing statement.")
                        }
                    }
                } else {
                    // Unlabeled break — needs to be in a loop or switch
                    if (!inIteration && !inSwitch) {
                        if (crossedFunctionBoundary) {
                            emitJumpDiagnostic(stmt, source, fileName, 1107,
                                "Jump target cannot cross function boundary.")
                        } else {
                            emitJumpDiagnostic(stmt, source, fileName, 1105,
                                "A 'break' statement can only be used within an enclosing iteration or switch statement.")
                        }
                    }
                }
            }
            is ContinueStatement -> {
                val label = stmt.label?.text
                if (label != null) {
                    if (label !in labelNames) {
                        if (crossedFunctionBoundary) {
                            emitJumpDiagnostic(stmt, source, fileName, 1107,
                                "Jump target cannot cross function boundary.")
                        } else {
                            emitJumpDiagnostic(stmt, source, fileName, 1115,
                                "A 'continue' statement can only jump to a label of an enclosing iteration statement.")
                        }
                    }
                } else {
                    if (!inIteration) {
                        if (crossedFunctionBoundary) {
                            emitJumpDiagnostic(stmt, source, fileName, 1107,
                                "Jump target cannot cross function boundary.")
                        } else {
                            emitJumpDiagnostic(stmt, source, fileName, 1104,
                                "A 'continue' statement can only be used within an enclosing iteration statement.")
                        }
                    }
                }
            }
            // Loop statements: set inIteration = true
            is ForStatement -> {
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration = true, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is ForInStatement -> {
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration = true, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is ForOfStatement -> {
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration = true, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is WhileStatement -> {
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration = true, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is DoStatement -> {
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration = true, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    val stmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    checkJumpInStatements(stmts, source, fileName,
                        inIteration, inSwitch = true, labelNames, crossedFunctionBoundary)
                }
            }
            is LabeledStatement -> {
                val newLabels = labelNames + stmt.label.text
                checkJumpInStatement(stmt.statement, source, fileName,
                    inIteration, inSwitch, newLabels, crossedFunctionBoundary)
            }
            is Block -> {
                checkJumpInStatements(stmt.statements, source, fileName,
                    inIteration, inSwitch, labelNames, crossedFunctionBoundary)
            }
            is IfStatement -> {
                checkJumpInStatement(stmt.thenStatement, source, fileName,
                    inIteration, inSwitch, labelNames, crossedFunctionBoundary)
                stmt.elseStatement?.let {
                    checkJumpInStatement(it, source, fileName, inIteration, inSwitch, labelNames, crossedFunctionBoundary)
                }
            }
            is TryStatement -> {
                checkJumpInStatements(stmt.tryBlock.statements, source, fileName,
                    inIteration, inSwitch, labelNames, crossedFunctionBoundary)
                stmt.catchClause?.block?.let {
                    checkJumpInStatements(it.statements, source, fileName,
                        inIteration, inSwitch, labelNames, crossedFunctionBoundary)
                }
                stmt.finallyBlock?.let {
                    checkJumpInStatements(it.statements, source, fileName,
                        inIteration, inSwitch, labelNames, crossedFunctionBoundary)
                }
            }
            // Function boundaries: reset iteration/switch/label state, mark boundary crossed
            is FunctionDeclaration -> {
                stmt.body?.let {
                    checkJumpInStatements(it.statements, source, fileName,
                        inIteration = false, inSwitch = false, labelNames = emptySet(),
                        crossedFunctionBoundary = true)
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    val body = when (member) {
                        is MethodDeclaration -> member.body
                        is Constructor -> member.body
                        is GetAccessor -> member.body
                        is SetAccessor -> member.body
                        else -> null
                    }
                    body?.let {
                        checkJumpInStatements(it.statements, source, fileName,
                            inIteration = false, inSwitch = false, labelNames = emptySet(),
                            crossedFunctionBoundary = true)
                    }
                }
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkJumpInExpr(it, source, fileName) }
                }
            }
            is ExpressionStatement -> checkJumpInExpr(stmt.expression, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkJumpInExpr(it, source, fileName) }
            else -> {}
        }
    }

    private fun checkJumpInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkJumpInStatements(body.statements, source, fileName,
                        inIteration = false, inSwitch = false, labelNames = emptySet(),
                        crossedFunctionBoundary = true)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkJumpInStatements(expr.body.statements, source, fileName,
                    inIteration = false, inSwitch = false, labelNames = emptySet(),
                    crossedFunctionBoundary = true)
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    val body = when (member) {
                        is MethodDeclaration -> member.body
                        is Constructor -> member.body
                        is GetAccessor -> member.body
                        is SetAccessor -> member.body
                        else -> null
                    }
                    body?.let {
                        checkJumpInStatements(it.statements, source, fileName,
                            inIteration = false, inSwitch = false, labelNames = emptySet(),
                            crossedFunctionBoundary = true)
                    }
                }
            }
            is CallExpression -> {
                expr.arguments.forEach { checkJumpInExpr(it, source, fileName) }
            }
            is ParenthesizedExpression -> checkJumpInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                checkJumpInExpr(expr.left, source, fileName)
                checkJumpInExpr(expr.right, source, fileName)
            }
            else -> {}
        }
    }

    private fun emitJumpDiagnostic(stmt: Statement, source: String, fileName: String, code: Int, message: String) {
        var start = stmt.pos
        while (start < source.length && source[start].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) start++
        val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        var end = lineEnd
        while (end > start && source[end - 1].let { it == ' ' || it == '\t' || it == '\r' }) end--
        val length = end - start
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = message,
            category = DiagnosticCategory.Error,
            code = code,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Strict mode identifier checking (TS1100)
    // -----------------------------------------------------------------------

    /**
     * Check for TS1100: "Invalid use of 'arguments'/'eval' in strict mode."
     * In strict mode, `arguments` and `eval` cannot be used as variable/parameter/function names.
     */
    private fun checkStrictModeIdentifiers() {
        val restricted = setOf("arguments", "eval")
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkStrictModeInStatements(result.sourceFile.statements, source, fileName, restricted)
        }
    }

    private fun checkStrictModeInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        for (stmt in statements) {
            checkStrictModeInStatement(stmt, source, fileName, restricted)
        }
    }

    private fun checkStrictModeInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    checkStrictModeBindingName(decl.name, source, fileName, restricted)
                }
            }
            is FunctionDeclaration -> {
                stmt.name?.let { checkStrictModeName(it, source, fileName, restricted) }
                for (param in stmt.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                stmt.body?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    checkStrictModeInClassElement(member, source, fileName, restricted)
                }
            }
            is ExpressionStatement -> checkStrictModeInExpr(stmt.expression, source, fileName, restricted)
            is ReturnStatement -> stmt.expression?.let { checkStrictModeInExpr(it, source, fileName, restricted) }
            is Block -> checkStrictModeInStatements(stmt.statements, source, fileName, restricted)
            is IfStatement -> {
                checkStrictModeInStatement(stmt.thenStatement, source, fileName, restricted)
                stmt.elseStatement?.let { checkStrictModeInStatement(it, source, fileName, restricted) }
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            checkStrictModeBindingName(decl.name, source, fileName, restricted)
                        }
                    }
                    else -> {}
                }
                checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            }
            is ForInStatement -> checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            is ForOfStatement -> checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            is WhileStatement -> checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            is DoStatement -> checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> checkStrictModeInStatements(clause.statements, source, fileName, restricted)
                        is DefaultClause -> checkStrictModeInStatements(clause.statements, source, fileName, restricted)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                checkStrictModeInStatements(stmt.tryBlock.statements, source, fileName, restricted)
                stmt.catchClause?.let {
                    it.variableDeclaration?.let { v ->
                        checkStrictModeBindingName(v.name, source, fileName, restricted)
                    }
                    checkStrictModeInStatements(it.block.statements, source, fileName, restricted)
                }
                stmt.finallyBlock?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            is LabeledStatement -> checkStrictModeInStatement(stmt.statement, source, fileName, restricted)
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> checkStrictModeInStatements(body.statements, source, fileName, restricted)
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun checkStrictModeInClassElement(
        member: ClassElement,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        when (member) {
            is MethodDeclaration -> {
                for (param in member.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                member.body?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            is Constructor -> {
                for (param in member.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                member.body?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            is GetAccessor -> {
                member.body?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            is SetAccessor -> {
                for (param in member.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                member.body?.let { checkStrictModeInStatements(it.statements, source, fileName, restricted) }
            }
            else -> {}
        }
    }

    private fun checkStrictModeInExpr(
        expr: Expression,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        when (expr) {
            is FunctionExpression -> {
                expr.name?.let { checkStrictModeName(it, source, fileName, restricted) }
                for (param in expr.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                checkStrictModeInStatements(expr.body.statements, source, fileName, restricted)
            }
            is ArrowFunction -> {
                for (param in expr.parameters) {
                    checkStrictModeBindingName(param.name, source, fileName, restricted)
                }
                when (val body = expr.body) {
                    is Block -> checkStrictModeInStatements(body.statements, source, fileName, restricted)
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun checkStrictModeBindingName(
        name: Node,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        when (name) {
            is Identifier -> checkStrictModeName(name, source, fileName, restricted)
            // Could recurse into destructuring patterns if needed
            else -> {}
        }
    }

    private fun checkStrictModeName(
        name: Identifier,
        source: String,
        fileName: String,
        restricted: Set<String>,
    ) {
        if (name.text in restricted) {
            val start = name.pos
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "Invalid use of '${name.text}' in strict mode.",
                category = DiagnosticCategory.Error,
                code = 1100,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = name.text.length,
            ))
        }
    }

    // -----------------------------------------------------------------------
    // Export assignment conflict checking (TS2309)
    // -----------------------------------------------------------------------

    /**
     * Check for TS2309: "An export assignment cannot be used in a module with other exported elements."
     * Fires when a file has both `export = X` and other exported declarations.
     */
    private fun checkExportAssignmentConflicts() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            val statements = result.sourceFile.statements

            // Find export assignments (export = X)
            val exportAssignments = statements.filterIsInstance<ExportAssignment>()
                .filter { it.isExportEquals }
            if (exportAssignments.isEmpty()) continue

            // Check for other exported elements
            val hasOtherExports = statements.any { stmt ->
                when (stmt) {
                    is ExportDeclaration -> true
                    is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                    is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is InterfaceDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is TypeAliasDeclaration -> ModifierFlag.Export in stmt.modifiers
                    is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                    else -> false
                }
            }

            if (hasOtherExports) {
                for (ea in exportAssignments) {
                    // Find the start of the actual statement text (skip leading trivia)
                    var start = ea.pos
                    while (start < source.length && source[start].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
                        start++
                    }
                    // Compute length from start to end of current line (or semicolon)
                    val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                    var end = lineEnd
                    // Trim trailing whitespace/CR
                    while (end > start && source[end - 1].let { it == ' ' || it == '\t' || it == '\r' }) {
                        end--
                    }
                    val length = end - start
                    val (line, character) = getLineAndCharacterOfPosition(source, start)
                    diagnostics.add(Diagnostic(
                        message = "An export assignment cannot be used in a module with other exported elements.",
                        category = DiagnosticCategory.Error,
                        code = 2309,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = length,
                    ))
                }
            }
        }
    }

    companion object {
        /** Built-in global identifiers that cannot be redeclared (TS2397). */
        private val BUILTIN_GLOBAL_CONFLICT_NAMES = setOf("undefined", "globalThis")

        /** Words that are reserved as identifiers in strict mode (TS1212). */
        private val STRICT_MODE_RESERVED_WORDS = setOf(
            "let", "public", "private", "protected", "static",
            "package", "yield", "interface", "implements",
        )

        /** Type keywords that are only types, never values.
         * Excludes 'object' which can be used as a variable name in JS. */
        private val TYPE_ONLY_KEYWORDS = setOf(
            "any", "number", "string", "boolean", "symbol", "void",
            "never", "unknown", "bigint",
        )

        /**
         * Keywords/reserved words that parse as Identifier nodes in our AST.
         * These should never trigger TS2304.
         */
        private val KEYWORD_IDENTIFIERS: Set<String> = setOf(
            // JS keywords
            "this", "super", "true", "false", "null",
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "throw", "try", "catch", "finally",
            "new", "delete", "typeof", "instanceof", "in", "of", "with",
            "var", "let", "const", "function", "class", "extends",
            "import", "export", "from", "as",
            "void", "yield", "debugger",
            // TS type keywords (may appear as Identifiers after parser recovery)
            "any", "number", "string", "boolean", "symbol", "bigint",
            "object", "never", "unknown",
            // TS modifiers/contextual keywords
            "public", "private", "protected", "readonly", "abstract",
            "static", "declare", "override", "accessor",
            "async", "await", "type", "namespace", "module",
            "interface", "enum", "implements", "is",
            "infer", "keyof", "unique", "asserts", "satisfies", "out",
            // JS strict mode reserved words
            "package",
        )

        /**
         * Well-known global names from lib.d.ts and common environments.
         * These are always considered "in scope" to avoid false positives
         * when we don't have actual lib.d.ts type definitions.
         */
        private val KNOWN_GLOBALS: Set<String> = setOf(
            // Special identifiers
            "undefined", "globalThis",
            // ES5 globals
            "NaN", "Infinity", "eval",
            "parseInt", "parseFloat", "isNaN", "isFinite",
            "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent",
            "escape", "unescape",
            // ES5 constructors/types
            "Object", "Function", "Boolean", "Symbol",
            "Error", "AggregateError", "EvalError", "RangeError",
            "ReferenceError", "SyntaxError", "TypeError", "URIError",
            "Number", "BigInt", "Math", "Date",
            "String", "RegExp",
            "Array", "Int8Array", "Uint8Array", "Uint8ClampedArray",
            "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
            "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
            "Map", "Set", "WeakMap", "WeakSet", "WeakRef", "FinalizationRegistry",
            "ArrayBuffer", "SharedArrayBuffer", "ArrayBufferView", "DataView", "Atomics",
            "JSON", "Promise", "Proxy", "Reflect", "Intl",
            // Iterators/generators
            "Generator", "GeneratorFunction", "AsyncGenerator", "AsyncGeneratorFunction",
            "Iterator", "AsyncIterator",
            "IteratorResult", "IteratorYieldResult", "IteratorReturnResult", "IteratorObject",
            // TypeScript utility types (used in type positions)
            "Partial", "Required", "Readonly", "Record", "Pick", "Omit",
            "Exclude", "Extract", "NonNullable", "Parameters", "ConstructorParameters",
            "ReturnType", "InstanceType", "ThisType", "ThisParameterType",
            "OmitThisParameter", "Uppercase", "Lowercase", "Capitalize", "Uncapitalize",
            "Awaited", "NoInfer",
            "Iterable", "IterableIterator", "AsyncIterable", "AsyncIterableIterator",
            "RegExpMatchArray", "RegExpExecArray", "FlatArray",
            "PromiseLike", "ArrayLike", "ReadonlyArray", "ReadonlyMap", "ReadonlySet",
            "TemplateStringsArray",
            "PropertyKey", "PropertyDescriptor", "PropertyDescriptorMap",
            "TypedPropertyDescriptor",
            "ClassDecorator", "PropertyDecorator", "MethodDecorator", "ParameterDecorator",
            "PromiseConstructorLike",
            "Exclude", "Extract",
            // Console & timers
            "console",
            "setTimeout", "clearTimeout", "setInterval", "clearInterval",
            "setImmediate", "clearImmediate",
            "queueMicrotask",
            // DOM — common types
            "document", "window", "navigator", "location", "history", "screen",
            "self", "top", "parent", "frames", "opener",
            "alert", "confirm", "prompt", "open", "close", "print",
            "requestAnimationFrame", "cancelAnimationFrame",
            "requestIdleCallback", "cancelIdleCallback",
            "fetch", "Headers", "Request", "Response",
            "URL", "URLSearchParams",
            "FormData", "Blob", "File", "FileReader", "FileList",
            "AbortController", "AbortSignal",
            "TextEncoder", "TextDecoder",
            "atob", "btoa",
            "Event", "CustomEvent", "ErrorEvent",
            "MouseEvent", "KeyboardEvent", "TouchEvent", "FocusEvent",
            "InputEvent", "WheelEvent", "PointerEvent", "DragEvent",
            "AnimationEvent", "TransitionEvent", "UIEvent", "ClipboardEvent",
            "CompositionEvent", "ProgressEvent", "PageTransitionEvent",
            "PopStateEvent", "HashChangeEvent", "StorageEvent",
            "MessageEvent", "BeforeUnloadEvent",
            "EventTarget", "EventListener",
            "Element", "HTMLElement", "SVGElement",
            "Node", "NodeList", "HTMLCollection", "NamedNodeMap",
            "Document", "DocumentFragment", "DocumentType",
            "Window", "Navigator",
            "HTMLDivElement", "HTMLSpanElement", "HTMLInputElement",
            "HTMLButtonElement", "HTMLFormElement", "HTMLAnchorElement",
            "HTMLImageElement", "HTMLVideoElement", "HTMLAudioElement",
            "HTMLCanvasElement", "HTMLTextAreaElement", "HTMLSelectElement",
            "HTMLOptionElement", "HTMLTableElement", "HTMLTableRowElement",
            "HTMLTableCellElement", "HTMLIFrameElement", "HTMLScriptElement",
            "HTMLStyleElement", "HTMLLinkElement", "HTMLMetaElement",
            "HTMLHeadElement", "HTMLBodyElement", "HTMLHtmlElement",
            "HTMLLIElement", "HTMLUListElement", "HTMLOListElement",
            "HTMLParagraphElement", "HTMLHeadingElement", "HTMLBRElement",
            "HTMLHRElement", "HTMLPreElement", "HTMLTemplateElement",
            "HTMLSlotElement", "HTMLLabelElement", "HTMLFieldSetElement",
            "HTMLLegendElement", "HTMLDataListElement", "HTMLOutputElement",
            "HTMLProgressElement", "HTMLMeterElement", "HTMLDetailsElement",
            "HTMLDialogElement", "HTMLMenuElement",
            "SVGSVGElement", "SVGPathElement", "SVGCircleElement",
            "SVGRectElement", "SVGLineElement", "SVGTextElement",
            "Text", "Comment", "CDATASection", "ProcessingInstruction",
            "Attr", "CharacterData", "ChildNode", "ParentNode",
            "DOMRect", "DOMRectReadOnly", "DOMPoint", "DOMPointReadOnly",
            "DOMMatrix", "DOMMatrixReadOnly", "DOMQuad",
            "Range", "Selection", "TreeWalker", "NodeIterator",
            "MutationObserver", "MutationRecord",
            "IntersectionObserver", "IntersectionObserverEntry",
            "ResizeObserver", "ResizeObserverEntry",
            "PerformanceObserver", "PerformanceEntry",
            "CSSStyleDeclaration", "CSSStyleSheet", "CSSRule", "CSSStyleRule",
            "MediaQueryList", "MediaQueryListEvent",
            "Storage", "localStorage", "sessionStorage",
            "XMLHttpRequest", "XMLSerializer", "DOMParser",
            "WebSocket", "EventSource", "BroadcastChannel",
            "MessageChannel", "MessagePort",
            "Worker", "SharedWorker", "ServiceWorker",
            "ServiceWorkerRegistration", "ServiceWorkerContainer",
            "Notification", "PushManager", "PushSubscription",
            "Cache", "CacheStorage",
            "Crypto", "CryptoKey", "SubtleCrypto", "crypto",
            "performance", "Performance", "PerformanceObserver",
            "ReadableStream", "WritableStream", "TransformStream",
            "ReadableStreamDefaultReader", "WritableStreamDefaultWriter",
            "ByteLengthQueuingStrategy", "CountQueuingStrategy",
            "Image", "ImageData", "ImageBitmap",
            "CanvasRenderingContext2D", "WebGLRenderingContext", "WebGL2RenderingContext",
            "OffscreenCanvas",
            "AudioContext", "AudioBuffer", "AudioNode",
            "MediaStream", "MediaRecorder",
            "RTCPeerConnection", "RTCSessionDescription", "RTCIceCandidate",
            "Geolocation", "GeolocationPosition",
            "Clipboard", "ClipboardItem",
            "VisualViewport",
            "indexedDB", "IDBDatabase", "IDBObjectStore", "IDBTransaction",
            "IDBRequest", "IDBCursor", "IDBKeyRange",
            "structuredClone", "reportError",
            // Windows scripting / runtime
            "WScript", "Windows",
            // Node.js
            "require", "module", "exports", "global",
            "process", "Buffer",
            "__dirname", "__filename",
            "__non_webpack_require__",
            // Testing frameworks
            "describe", "it", "test", "expect", "jest", "beforeEach", "afterEach",
            "beforeAll", "afterAll",
            // Common global augmentations
            "Symbol",
            // JSX namespace (available when JSX is enabled)
            "JSX",
        )

        /**
         * Known built-in generic types from lib.d.ts with their type parameter counts
         * and display names. Used for TS2314 checking when lib.d.ts isn't loaded.
         */
        private val KNOWN_GENERIC_BUILTINS: Map<String, Pair<Int, String>> = mapOf(
            "Array" to (1 to "Array<T>"),
            "ReadonlyArray" to (1 to "ReadonlyArray<T>"),
            "Promise" to (1 to "Promise<T>"),
            "PromiseLike" to (1 to "PromiseLike<T>"),
            "ArrayLike" to (1 to "ArrayLike<T>"),
            "Map" to (2 to "Map<K, V>"),
            "ReadonlyMap" to (2 to "ReadonlyMap<K, V>"),
            "Set" to (1 to "Set<T>"),
            "ReadonlySet" to (1 to "ReadonlySet<T>"),
            "WeakMap" to (2 to "WeakMap<K, V>"),
            "WeakSet" to (1 to "WeakSet<T>"),
            "WeakRef" to (1 to "WeakRef<T>"),
            "Partial" to (1 to "Partial<T>"),
            "Required" to (1 to "Required<T>"),
            "Readonly" to (1 to "Readonly<T>"),
            "Record" to (2 to "Record<K, T>"),
            "Pick" to (2 to "Pick<T, K>"),
            "Omit" to (2 to "Omit<T, K>"),
            "Exclude" to (2 to "Exclude<T, U>"),
            "Extract" to (2 to "Extract<T, U>"),
            "NonNullable" to (1 to "NonNullable<T>"),
            "Parameters" to (1 to "Parameters<T>"),
            "ConstructorParameters" to (1 to "ConstructorParameters<T>"),
            "ReturnType" to (1 to "ReturnType<T>"),
            "InstanceType" to (1 to "InstanceType<T>"),
            "ThisType" to (1 to "ThisType<T>"),
            "ThisParameterType" to (1 to "ThisParameterType<T>"),
            "OmitThisParameter" to (1 to "OmitThisParameter<T>"),
            "Awaited" to (1 to "Awaited<T>"),
            "NoInfer" to (1 to "NoInfer<T>"),
            "Iterable" to (1 to "Iterable<T>"),
            "IterableIterator" to (1 to "IterableIterator<T>"),
            "AsyncIterable" to (1 to "AsyncIterable<T>"),
            "AsyncIterableIterator" to (1 to "AsyncIterableIterator<T>"),
            "Generator" to (3 to "Generator<T, TReturn, TNext>"),
            "AsyncGenerator" to (3 to "AsyncGenerator<T, TReturn, TNext>"),
            "TypedPropertyDescriptor" to (1 to "TypedPropertyDescriptor<T>"),
            "ProxyHandler" to (1 to "ProxyHandler<T>"),
            "FinalizationRegistry" to (1 to "FinalizationRegistry<T>"),
        )
    }

    // -----------------------------------------------------------------------
    // Generic type argument count checking (TS2314)
    // -----------------------------------------------------------------------

    /**
     * Type parameter info: (minRequired, maxTotal, displayName).
     * minRequired = count of type params without defaults.
     * maxTotal = total type params count.
     */
    private data class TypeParamInfo(val minRequired: Int, val maxTotal: Int, val displayName: String)

    /**
     * Get type parameter info for a named type. Returns null if the type is not generic.
     */
    private fun getTypeParamInfo(name: String): TypeParamInfo? {
        // Check binder symbols first (user-declared types)
        for (result in binderResults) {
            val symbol = result.locals[name] ?: continue
            val info = getTypeParamInfoFromSymbol(symbol)
            if (info != null) return info
        }
        val globalSymbol = globals[name]
        if (globalSymbol != null) {
            val info = getTypeParamInfoFromSymbol(globalSymbol)
            if (info != null) return info
        }
        // Check known built-in generics (all required, no defaults)
        val builtin = KNOWN_GENERIC_BUILTINS[name] ?: return null
        return TypeParamInfo(builtin.first, builtin.first, builtin.second)
    }

    /**
     * Extract type parameter info from a symbol's declarations.
     */
    private fun getTypeParamInfoFromSymbol(symbol: Symbol): TypeParamInfo? {
        for (decl in symbol.declarations) {
            val typeParams = when (decl) {
                is ClassDeclaration -> decl.typeParameters
                is InterfaceDeclaration -> decl.typeParameters
                is TypeAliasDeclaration -> decl.typeParameters
                else -> null
            }
            if (typeParams != null && typeParams.isNotEmpty()) {
                val isTypeAlias = decl is TypeAliasDeclaration
                val displayName = if (isTypeAlias) {
                    symbol.name
                } else {
                    "${symbol.name}<${typeParams.joinToString(", ") { it.name.text }}>"
                }
                val minRequired = typeParams.count { it.default == null }
                return TypeParamInfo(minRequired, typeParams.size, displayName)
            }
        }
        return null
    }

    /**
     * Check if a type annotation is a bare generic type reference that would trigger TS2314.
     * Used to suppress false-positive TS2564 on error types.
     */
    private fun isUnresolvedGenericType(type: TypeNode?): Boolean {
        if (type !is TypeReference) return false
        val typeName = type.typeName
        val name = when (typeName) {
            is Identifier -> typeName.text
            else -> return false
        }
        val info = getTypeParamInfo(name) ?: return false
        if (info.minRequired != info.maxTotal) return false
        val providedCount = type.typeArguments?.size ?: 0
        return providedCount != info.maxTotal
    }

    /**
     * Check type argument count for a TypeReference node.
     * Emits TS2314: "Generic type 'X' requires N type argument(s)."
     * Only fires when all type params are required (no defaults).
     * Types with default type params need TS2707 (different code) — skipped.
     */
    private fun checkTypeArgCount(
        typeRef: TypeReference,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        val typeName = typeRef.typeName
        val name = when (typeName) {
            is Identifier -> typeName.text
            else -> return // QualifiedName — skip
        }

        // Only check if the name actually resolves (skip if it would be TS2304)
        if (name.isEmpty()) return
        if (name[0] !in 'A'..'Z' && name[0] !in 'a'..'z' && name[0] != '_' && name[0] != '$') return
        if (name in KEYWORD_IDENTIFIERS) return
        if (!scope.has(name)) return

        // Look up type parameter info
        val info = getTypeParamInfo(name) ?: return

        // Skip types with default type params — they need TS2707 not TS2314
        if (info.minRequired != info.maxTotal) return

        val providedCount = typeRef.typeArguments?.size ?: 0
        if (providedCount == info.maxTotal) return // correct count

        // Compute squiggle position
        val start: Int
        val length: Int
        if (providedCount == 0) {
            // No type args — squiggle on just the name
            start = typeName.pos
            length = name.length
        } else {
            // Wrong count — squiggle on the entire type reference including <args>
            start = typeName.pos
            val lastArgEnd = typeRef.typeArguments!!.last().end
            val gtIdx = source.indexOf('>', lastArgEnd - 1)
            length = if (gtIdx >= 0) gtIdx + 1 - start else name.length
        }

        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Generic type '${info.displayName}' requires ${info.maxTotal} type argument(s).",
            category = DiagnosticCategory.Error,
            code = 2314,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /**
     * Check type argument count for an ExpressionWithTypeArguments in heritage clauses.
     * Emits TS2314 when extends/implements clause uses wrong number of type args.
     * Only fires when all type params are required (no defaults).
     */
    private fun checkHeritageTypeArgCount(
        exprWithArgs: ExpressionWithTypeArguments,
        scope: NameScope,
        source: String,
        fileName: String,
    ) {
        val expression = exprWithArgs.expression
        val name = when (expression) {
            is Identifier -> expression.text
            else -> return // PropertyAccessExpression — skip
        }

        // Only check if the name resolves
        if (name.isEmpty()) return
        if (!scope.has(name)) return

        val info = getTypeParamInfo(name) ?: return

        // Skip types with default type params — they need TS2707 not TS2314
        if (info.minRequired != info.maxTotal) return

        val providedCount = exprWithArgs.typeArguments?.size ?: 0
        if (providedCount == info.maxTotal) return

        val start = expression.pos
        val length: Int
        if (providedCount == 0) {
            length = name.length
        } else {
            // Squiggle covers name + <args>
            val lastArgEnd = exprWithArgs.typeArguments!!.last().end
            val gtIdx = source.indexOf('>', lastArgEnd - 1)
            length = if (gtIdx >= 0) gtIdx + 1 - start else name.length
        }

        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Generic type '${info.displayName}' requires ${info.maxTotal} type argument(s).",
            category = DiagnosticCategory.Error,
            code = 2314,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Type used as value checking (TS2693)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Always-truthy expression checking (TS2872)
    // -----------------------------------------------------------------------

    /**
     * Check for expressions that are always truthy in logical OR and if conditions.
     * Emits TS2872 "This kind of expression is always truthy."
     */
    private fun checkAlwaysTruthy() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkAlwaysTruthyInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkAlwaysTruthyInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkAlwaysTruthyInStatement(stmt, source, fileName)
    }

    private fun checkAlwaysTruthyInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ExpressionStatement -> checkAlwaysTruthyInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkAlwaysTruthyInExpr(it, source, fileName) }
            }
            is IfStatement -> {
                // Walk the if-else chain: only flag always-truthy conditions that are
                // UNREACHABLE because a preceding branch was always-truthy
                var prevTruthy = isAlwaysTruthyExpr(stmt.expression)
                checkAlwaysTruthyInStatement(stmt.thenStatement, source, fileName)
                var elseStmt = stmt.elseStatement
                while (elseStmt is IfStatement) {
                    if (prevTruthy) {
                        checkAlwaysTruthyCondition(elseStmt.expression, source, fileName)
                    }
                    if (isAlwaysTruthyExpr(elseStmt.expression)) prevTruthy = true
                    checkAlwaysTruthyInStatement(elseStmt.thenStatement, source, fileName)
                    elseStmt = elseStmt.elseStatement
                }
                elseStmt?.let { checkAlwaysTruthyInStatement(it, source, fileName) }
            }
            is Block -> checkAlwaysTruthyInStatements(stmt.statements, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkAlwaysTruthyInExpr(it, source, fileName) }
            is FunctionDeclaration -> stmt.body?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
            is ClassDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
                    is Constructor -> m.body?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
                    is PropertyDeclaration -> m.initializer?.let { checkAlwaysTruthyInExpr(it, source, fileName) }
                    else -> {}
                }
            }
            is ForStatement -> {
                stmt.condition?.let { checkAlwaysTruthyInExpr(it, source, fileName) }
                checkAlwaysTruthyInStatement(stmt.statement, source, fileName)
            }
            is WhileStatement -> checkAlwaysTruthyInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkAlwaysTruthyInStatement(stmt.statement, source, fileName)
            is SwitchStatement -> for (c in stmt.caseBlock) {
                val clauseStmts = when (c) { is CaseClause -> c.statements; is DefaultClause -> c.statements; else -> emptyList() }
                checkAlwaysTruthyInStatements(clauseStmts, source, fileName)
            }
            is TryStatement -> {
                checkAlwaysTruthyInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkAlwaysTruthyInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    private fun checkAlwaysTruthyInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.BarBar) {
                    if (isAlwaysTruthyExpr(expr.left)) {
                        emitTS2872(expr.left, source, fileName)
                    }
                }
                checkAlwaysTruthyInExpr(expr.left, source, fileName)
                checkAlwaysTruthyInExpr(expr.right, source, fileName)
            }
            is ParenthesizedExpression -> checkAlwaysTruthyInExpr(expr.expression, source, fileName)
            is ConditionalExpression -> {
                checkAlwaysTruthyInExpr(expr.whenTrue, source, fileName)
                checkAlwaysTruthyInExpr(expr.whenFalse, source, fileName)
            }
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkAlwaysTruthyInStatements(body.statements, source, fileName)
                is Expression -> checkAlwaysTruthyInExpr(body, source, fileName)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkAlwaysTruthyInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    private fun checkAlwaysTruthyCondition(expr: Expression, source: String, fileName: String) {
        if (expr is NumericLiteralNode) {
            val value = expr.text.toDoubleOrNull() ?: return
            if (value != 0.0 && !value.isNaN()) {
                emitTS2872(expr, source, fileName)
            }
        }
        if (expr is ParenthesizedExpression) {
            checkAlwaysTruthyCondition(expr.expression, source, fileName)
        }
    }

    /** Only flag simple literal expressions to avoid false positives. */
    private fun isAlwaysTruthyExpr(expr: Expression): Boolean {
        return when (expr) {
            is StringLiteralNode -> expr.text.isNotEmpty()
            is NumericLiteralNode -> {
                val value = expr.text.toDoubleOrNull() ?: return false
                value != 0.0 && !value.isNaN()
            }
            is ParenthesizedExpression -> isAlwaysTruthyExpr(expr.expression)
            else -> false
        }
    }

    private fun emitTS2872(expr: Expression, source: String, fileName: String) {
        val start = expr.pos
        // Use text length for literals to get accurate squiggle
        val length = when (expr) {
            is StringLiteralNode -> (expr.rawText?.length ?: expr.text.length) + 2 // +2 for quotes
            is NumericLiteralNode -> expr.text.length
            else -> (expr.end - 1 - start).coerceAtLeast(1)
        }
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "This kind of expression is always truthy.",
            category = DiagnosticCategory.Error,
            code = 2872,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Comma operator left side unused (TS2695)
    // -----------------------------------------------------------------------

    private fun checkCommaOperatorUnused() {
        // TypeScript suppresses TS2695 when allowUnreachableCode is explicitly true
        if (options.allowUnreachableCode == true) return
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCommaInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkCommaInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkCommaInStatement(stmt, source, fileName)
    }

    private fun checkCommaInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ExpressionStatement -> checkCommaInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkCommaInExpr(it, source, fileName) }
            }
            is ReturnStatement -> stmt.expression?.let { checkCommaInExpr(it, source, fileName) }
            is IfStatement -> {
                checkCommaInExpr(stmt.expression, source, fileName)
                checkCommaInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkCommaInStatement(it, source, fileName) }
            }
            is Block -> checkCommaInStatements(stmt.statements, source, fileName)
            is FunctionDeclaration -> stmt.body?.let { checkCommaInStatements(it.statements, source, fileName) }
            is ClassDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { checkCommaInStatements(it.statements, source, fileName) }
                    is Constructor -> m.body?.let { checkCommaInStatements(it.statements, source, fileName) }
                    is PropertyDeclaration -> m.initializer?.let { checkCommaInExpr(it, source, fileName) }
                    is GetAccessor -> m.body?.let { checkCommaInStatements(it.statements, source, fileName) }
                    is SetAccessor -> m.body?.let { checkCommaInStatements(it.statements, source, fileName) }
                    else -> {}
                }
            }
            is ForStatement -> {
                stmt.initializer?.let { init ->
                    when (init) {
                        is VariableDeclarationList -> for (d in init.declarations) {
                            d.initializer?.let { checkCommaInExpr(it, source, fileName) }
                        }
                        is Expression -> checkCommaInExpr(init, source, fileName)
                        else -> {}
                    }
                }
                stmt.condition?.let { checkCommaInExpr(it, source, fileName) }
                stmt.incrementor?.let { checkCommaInExpr(it, source, fileName) }
                checkCommaInStatement(stmt.statement, source, fileName)
            }
            is ForInStatement -> {
                checkCommaInExpr(stmt.expression, source, fileName)
                checkCommaInStatement(stmt.statement, source, fileName)
            }
            is ForOfStatement -> {
                checkCommaInExpr(stmt.expression, source, fileName)
                checkCommaInStatement(stmt.statement, source, fileName)
            }
            is WhileStatement -> {
                checkCommaInExpr(stmt.expression, source, fileName)
                checkCommaInStatement(stmt.statement, source, fileName)
            }
            is DoStatement -> {
                checkCommaInStatement(stmt.statement, source, fileName)
                checkCommaInExpr(stmt.expression, source, fileName)
            }
            is SwitchStatement -> {
                checkCommaInExpr(stmt.expression, source, fileName)
                for (c in stmt.caseBlock) {
                    when (c) {
                        is CaseClause -> {
                            checkCommaInExpr(c.expression, source, fileName)
                            checkCommaInStatements(c.statements, source, fileName)
                        }
                        is DefaultClause -> checkCommaInStatements(c.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                checkCommaInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkCommaInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkCommaInStatements(it.statements, source, fileName) }
            }
            is ThrowStatement -> stmt.expression?.let { checkCommaInExpr(it, source, fileName) }
            is LabeledStatement -> checkCommaInStatement(stmt.statement, source, fileName)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkCommaInStatements(it.statements, source, fileName) }
            is EnumDeclaration -> for (m in stmt.members) {
                m.initializer?.let { checkCommaInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    /** Check if this comma expression is an indirect call pattern: (0, obj.prop)() */
    private fun isIndirectCallComma(expr: BinaryExpression): Boolean {
        val right = expr.right
        // (0, obj.method)() — indirect call pattern with property/element access
        if (right is PropertyAccessExpression || right is ElementAccessExpression) return true
        // (0, eval)("code") — indirect eval pattern
        if (right is Identifier && right.text == "eval") return true
        return false
    }

    private fun checkCommaInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Comma) {
                    if (!hasSideEffects(expr.left) && !isIndirectCallComma(expr)) {
                        val start = expr.left.pos
                        val length = commaLeftSpanLength(expr.left, start)
                        val (line, character) = getLineAndCharacterOfPosition(source, start)
                        diagnostics.add(Diagnostic(
                            message = "Left side of comma operator is unused and has no side effects.",
                            category = DiagnosticCategory.Error,
                            code = 2695,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = length,
                        ))
                    }
                }
                checkCommaInExpr(expr.left, source, fileName)
                checkCommaInExpr(expr.right, source, fileName)
            }
            is ParenthesizedExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is ConditionalExpression -> {
                checkCommaInExpr(expr.condition, source, fileName)
                checkCommaInExpr(expr.whenTrue, source, fileName)
                checkCommaInExpr(expr.whenFalse, source, fileName)
            }
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkCommaInStatements(body.statements, source, fileName)
                is Expression -> checkCommaInExpr(body, source, fileName)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkCommaInStatements(it.statements, source, fileName) }
            is CallExpression -> {
                checkCommaInExpr(expr.expression, source, fileName)
                for (arg in expr.arguments) checkCommaInExpr(arg, source, fileName)
            }
            is NewExpression -> {
                checkCommaInExpr(expr.expression, source, fileName)
                expr.arguments?.forEach { checkCommaInExpr(it, source, fileName) }
            }
            is PropertyAccessExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is ElementAccessExpression -> {
                checkCommaInExpr(expr.expression, source, fileName)
                checkCommaInExpr(expr.argumentExpression, source, fileName)
            }
            is ArrayLiteralExpression -> for (el in expr.elements) checkCommaInExpr(el, source, fileName)
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is PropertyAssignment -> checkCommaInExpr(prop.initializer, source, fileName)
                    is ShorthandPropertyAssignment -> prop.objectAssignmentInitializer?.let { checkCommaInExpr(it, source, fileName) }
                    is SpreadAssignment -> checkCommaInExpr(prop.expression, source, fileName)
                    else -> {}
                }
            }
            is PrefixUnaryExpression -> checkCommaInExpr(expr.operand, source, fileName)
            is PostfixUnaryExpression -> checkCommaInExpr(expr.operand, source, fileName)
            is TemplateExpression -> for (span in expr.templateSpans) {
                checkCommaInExpr(span.expression, source, fileName)
            }
            is TaggedTemplateExpression -> {
                checkCommaInExpr(expr.tag, source, fileName)
                val template = expr.template
                if (template is TemplateExpression) {
                    for (span in template.templateSpans) {
                        checkCommaInExpr(span.expression, source, fileName)
                    }
                }
            }
            is TypeAssertionExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is AsExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is NonNullExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is SpreadElement -> checkCommaInExpr(expr.expression, source, fileName)
            is AwaitExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is YieldExpression -> expr.expression?.let { checkCommaInExpr(it, source, fileName) }
            is VoidExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is DeleteExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is TypeOfExpression -> checkCommaInExpr(expr.expression, source, fileName)
            is SatisfiesExpression -> checkCommaInExpr(expr.expression, source, fileName)
            else -> {}
        }
    }

    /** Compute squiggle length for the left operand of a comma expression. */
    private fun commaLeftSpanLength(expr: Expression, start: Int): Int {
        return when (expr) {
            // Function expressions: squiggle only covers 'function' keyword (8 chars)
            is FunctionExpression -> 8
            // Arrow functions: squiggle covers the whole expression
            is ArrowFunction -> (expr.end - 1 - start).coerceAtLeast(1)
            // Parenthesized: squiggle covers the whole parenthesized expression
            is ParenthesizedExpression -> (expr.end - 1 - start).coerceAtLeast(1)
            // String literals: text.length + 2 for quotes
            is StringLiteralNode -> (expr.rawText?.length ?: expr.text.length) + 2
            // Template strings: use node span
            is NoSubstitutionTemplateLiteralNode -> (expr.end - 1 - start).coerceAtLeast(1)
            // Regex literals: use node span
            is RegularExpressionLiteralNode -> (expr.end - 1 - start).coerceAtLeast(1)
            // Default: use node span
            else -> (expr.end - 1 - start).coerceAtLeast(1)
        }
    }

    /** Check if an expression has side effects (assignments, calls, increments, etc.) */
    private fun hasSideEffects(expr: Expression): Boolean {
        return when (expr) {
            // Assignments always have side effects
            is BinaryExpression -> when (expr.operator) {
                SyntaxKind.Equals, SyntaxKind.PlusEquals, SyntaxKind.MinusEquals,
                SyntaxKind.AsteriskEquals, SyntaxKind.SlashEquals, SyntaxKind.PercentEquals,
                SyntaxKind.AmpersandEquals, SyntaxKind.BarEquals, SyntaxKind.CaretEquals,
                SyntaxKind.LessThanLessThanEquals, SyntaxKind.GreaterThanGreaterThanEquals,
                SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
                SyntaxKind.AsteriskAsteriskEquals,
                SyntaxKind.BarBarEquals, SyntaxKind.AmpersandAmpersandEquals,
                SyntaxKind.QuestionQuestionEquals -> true
                // Comma: if left has side effects, whole thing does
                SyntaxKind.Comma -> hasSideEffects(expr.left) || hasSideEffects(expr.right)
                // Other binary ops: check both sides
                else -> hasSideEffects(expr.left) || hasSideEffects(expr.right)
            }
            // Increment/decrement have side effects
            is PrefixUnaryExpression -> when (expr.operator) {
                SyntaxKind.PlusPlus, SyntaxKind.MinusMinus -> true
                else -> hasSideEffects(expr.operand)
            }
            is PostfixUnaryExpression -> true // ++ and -- always have side effects
            // Function/method calls have side effects
            is CallExpression -> true
            is NewExpression -> true
            is TaggedTemplateExpression -> true
            // Delete has side effects
            is DeleteExpression -> true
            // Yield/await have side effects
            is YieldExpression -> true
            is AwaitExpression -> true
            // Void expression: TypeScript treats as OK (no TS2695)
            is VoidExpression -> true
            // Type assertions: TypeScript treats as OK (no TS2695)
            is TypeAssertionExpression -> true
            is AsExpression -> true
            is SatisfiesExpression -> true
            // Parenthesized: check inner
            is ParenthesizedExpression -> hasSideEffects(expr.expression)
            // Non-null assertion (x!): no side effect
            is NonNullExpression -> hasSideEffects(expr.expression)
            // Conditional: has side effects if any branch does
            is ConditionalExpression -> hasSideEffects(expr.whenTrue) || hasSideEffects(expr.whenFalse)
            // Everything else (literals, identifiers, typeof, array/object literals,
            // arrow functions, function expressions, etc.) — no side effects
            else -> false
        }
    }

    // -----------------------------------------------------------------------
    // Null/undefined usage checking (TS18050)
    // -----------------------------------------------------------------------

    /**
     * Check for null/undefined used in invalid positions:
     * property access base, binary operator operands, etc.
     * Emits TS18050: "The value 'null'/'undefined' cannot be used here."
     */
    private fun checkNullUndefinedUsage() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkNullUndefinedInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkNullUndefinedInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkNullUndefinedInStatement(stmt, source, fileName)
    }

    private fun checkNullUndefinedInStatement(stmt: Statement, source: String, fileName: String) {
        if (checkDepth > maxCheckDepth) return
        checkDepth++
        try {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        decl.initializer?.let { checkNullUndefinedInExpr(it, source, fileName) }
                    }
                }
                is ExpressionStatement -> checkNullUndefinedInExpr(stmt.expression, source, fileName)
                is ReturnStatement -> stmt.expression?.let { checkNullUndefinedInExpr(it, source, fileName) }
                is IfStatement -> {
                    checkNullUndefinedInExpr(stmt.expression, source, fileName)
                    checkNullUndefinedInStatement(stmt.thenStatement, source, fileName)
                    stmt.elseStatement?.let { checkNullUndefinedInStatement(it, source, fileName) }
                }
                is Block -> checkNullUndefinedInStatements(stmt.statements, source, fileName)
                is ForStatement -> {
                    stmt.initializer?.let { if (it is Expression) checkNullUndefinedInExpr(it, source, fileName) }
                    stmt.condition?.let { checkNullUndefinedInExpr(it, source, fileName) }
                    stmt.incrementor?.let { checkNullUndefinedInExpr(it, source, fileName) }
                    checkNullUndefinedInStatement(stmt.statement, source, fileName)
                }
                is WhileStatement -> {
                    checkNullUndefinedInExpr(stmt.expression, source, fileName)
                    checkNullUndefinedInStatement(stmt.statement, source, fileName)
                }
                is DoStatement -> {
                    checkNullUndefinedInStatement(stmt.statement, source, fileName)
                    checkNullUndefinedInExpr(stmt.expression, source, fileName)
                }
                is SwitchStatement -> {
                    checkNullUndefinedInExpr(stmt.expression, source, fileName)
                    for (clause in stmt.caseBlock) {
                        when (clause) {
                            is CaseClause -> {
                                checkNullUndefinedInExpr(clause.expression, source, fileName)
                                checkNullUndefinedInStatements(clause.statements, source, fileName)
                            }
                            is DefaultClause -> {
                                checkNullUndefinedInStatements(clause.statements, source, fileName)
                            }
                            else -> {}
                        }
                    }
                }
                is FunctionDeclaration -> {
                    stmt.body?.let { checkNullUndefinedInStatements(it.statements, source, fileName) }
                }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> member.body?.let {
                                checkNullUndefinedInStatements(it.statements, source, fileName)
                            }
                            is PropertyDeclaration -> member.initializer?.let {
                                checkNullUndefinedInExpr(it, source, fileName)
                            }
                            is Constructor -> member.body?.let {
                                checkNullUndefinedInStatements(it.statements, source, fileName)
                            }
                            else -> {}
                        }
                    }
                }
                is ModuleDeclaration -> {
                    when (val body = stmt.body) {
                        is ModuleBlock -> checkNullUndefinedInStatements(body.statements, source, fileName)
                        else -> {}
                    }
                }
                is ThrowStatement -> stmt.expression?.let { checkNullUndefinedInExpr(it, source, fileName) }
                else -> {}
            }
        } finally { checkDepth-- }
    }

    private fun checkNullUndefinedInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is BinaryExpression -> {
                // Check if null/undefined is used as operand of arithmetic/bitwise operators
                val op = expr.operator
                val isArithmeticOrBitwise = op in setOf(
                    SyntaxKind.Plus, SyntaxKind.Minus,
                    SyntaxKind.Asterisk, SyntaxKind.Slash, SyntaxKind.Percent,
                    SyntaxKind.AsteriskAsterisk,
                    SyntaxKind.Ampersand, SyntaxKind.Bar, SyntaxKind.Caret,
                    SyntaxKind.LessThanLessThan, SyntaxKind.GreaterThanGreaterThan,
                    SyntaxKind.GreaterThanGreaterThanGreaterThan,
                    SyntaxKind.PlusEquals, SyntaxKind.MinusEquals,
                    SyntaxKind.AsteriskEquals, SyntaxKind.SlashEquals,
                    SyntaxKind.PercentEquals, SyntaxKind.AsteriskAsteriskEquals,
                    SyntaxKind.AmpersandEquals, SyntaxKind.BarEquals,
                    SyntaxKind.CaretEquals, SyntaxKind.LessThanLessThanEquals,
                    SyntaxKind.GreaterThanGreaterThanEquals,
                    SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
                )
                if (isArithmeticOrBitwise) {
                    // For '+' operator, null/undefined is valid when other side is a string
                    val isPlus = op == SyntaxKind.Plus || op == SyntaxKind.PlusEquals
                    val leftIsString = expr.left is StringLiteralNode || expr.left is TemplateExpression ||
                            (expr.left is Identifier && (expr.left as Identifier).text.let { it != "null" && it != "undefined" } && false) // only literals
                    val rightIsString = expr.right is StringLiteralNode || expr.right is TemplateExpression
                    if (!(isPlus && rightIsString)) {
                        checkNullUndefinedLiteral(expr.left, source, fileName)
                    }
                    if (!(isPlus && leftIsString)) {
                        checkNullUndefinedLiteral(expr.right, source, fileName)
                    }
                }
                // Recurse into both sides
                checkNullUndefinedInExpr(expr.left, source, fileName)
                checkNullUndefinedInExpr(expr.right, source, fileName)
            }
            is PropertyAccessExpression -> {
                // null.foo or undefined.foo
                checkNullUndefinedLiteral(expr.expression, source, fileName)
                checkNullUndefinedInExpr(expr.expression, source, fileName)
            }
            is ElementAccessExpression -> {
                // null[x] or undefined[x]
                checkNullUndefinedLiteral(expr.expression, source, fileName)
                checkNullUndefinedInExpr(expr.expression, source, fileName)
                checkNullUndefinedInExpr(expr.argumentExpression, source, fileName)
            }
            is CallExpression -> {
                checkNullUndefinedInExpr(expr.expression, source, fileName)
                for (arg in expr.arguments) checkNullUndefinedInExpr(arg, source, fileName)
            }
            is ParenthesizedExpression -> checkNullUndefinedInExpr(expr.expression, source, fileName)
            is ConditionalExpression -> {
                checkNullUndefinedInExpr(expr.condition, source, fileName)
                checkNullUndefinedInExpr(expr.whenTrue, source, fileName)
                checkNullUndefinedInExpr(expr.whenFalse, source, fileName)
            }
            is PrefixUnaryExpression -> checkNullUndefinedInExpr(expr.operand, source, fileName)
            is PostfixUnaryExpression -> checkNullUndefinedInExpr(expr.operand, source, fileName)
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    checkNullUndefinedInExpr(span.expression, source, fileName)
                }
            }
            is ArrayLiteralExpression -> {
                for (elem in expr.elements) checkNullUndefinedInExpr(elem, source, fileName)
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> checkNullUndefinedInExpr(prop.initializer, source, fileName)
                        is SpreadAssignment -> checkNullUndefinedInExpr(prop.expression, source, fileName)
                        else -> {}
                    }
                }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkNullUndefinedInStatements(body.statements, source, fileName)
                    is Expression -> checkNullUndefinedInExpr(body, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                expr.body?.let { checkNullUndefinedInStatements(it.statements, source, fileName) }
            }
            else -> {}
        }
    }

    private fun checkNullUndefinedLiteral(expr: Expression, source: String, fileName: String) {
        // In our AST, null and undefined are parsed as Identifiers
        val keyword = when {
            expr is Identifier && expr.text == "null" -> "null"
            expr is Identifier && expr.text == "undefined" -> "undefined"
            else -> return
        }
        val start = expr.pos
        val length = keyword.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "The value '$keyword' cannot be used here.",
            category = DiagnosticCategory.Error,
            code = 18050,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /**
     * Check for type-only names used in value positions.
     * Emits TS2693 "'X' only refers to a type, but is being used as a value here."
     */
    private fun checkTypeUsedAsValue() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            // Collect interface/type alias names at file level (type-only declarations)
            // Skip names that also have a class/function/variable declaration (merged)
            val typeOnlyNames = mutableSetOf<String>()
            val valueNames = mutableSetOf<String>()
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is ClassDeclaration -> stmt.name?.text?.let { valueNames.add(it) }
                    is FunctionDeclaration -> stmt.name?.text?.let { valueNames.add(it) }
                    is VariableStatement -> for (decl in stmt.declarationList.declarations) {
                        (decl.name as? Identifier)?.text?.let { valueNames.add(it) }
                    }
                    is EnumDeclaration -> valueNames.add(stmt.name.text)
                    else -> {}
                }
            }
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is InterfaceDeclaration -> {
                        val n = stmt.name.text
                        if (n !in valueNames && n !in KNOWN_GLOBALS) typeOnlyNames.add(n)
                    }
                    is TypeAliasDeclaration -> {
                        val n = stmt.name.text
                        if (n !in valueNames && n !in KNOWN_GLOBALS) typeOnlyNames.add(n)
                    }
                    else -> {}
                }
            }
            checkTypeAsValueInStatements(result.sourceFile.statements, source, fileName, typeOnlyNames)
        }
    }

    private fun checkTypeAsValueInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        typeOnlyNames: Set<String>,
    ) {
        for (stmt in statements) {
            checkTypeAsValueInStatement(stmt, source, fileName, typeOnlyNames)
        }
    }

    private fun checkTypeAsValueInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
        typeOnlyNames: Set<String>,
    ) {
        when (stmt) {
            is ExpressionStatement -> checkTypeAsValueInExpr(stmt.expression, source, fileName, typeOnlyNames)
            is LabeledStatement -> checkTypeAsValueInStatement(stmt.statement, source, fileName, typeOnlyNames)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkTypeAsValueInExpr(it, source, fileName, typeOnlyNames) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { checkTypeAsValueInExpr(it, source, fileName, typeOnlyNames) }
            is Block -> checkTypeAsValueInStatements(stmt.statements, source, fileName, typeOnlyNames)
            is IfStatement -> {
                checkTypeAsValueInExpr(stmt.expression, source, fileName, typeOnlyNames)
                checkTypeAsValueInStatement(stmt.thenStatement, source, fileName, typeOnlyNames)
                stmt.elseStatement?.let { checkTypeAsValueInStatement(it, source, fileName, typeOnlyNames) }
            }
            is FunctionDeclaration -> {
                // Collect type parameters as type-only within this function
                // But exclude names that are also parameter names (parameter shadows type param)
                val innerTypeOnly = typeOnlyNames.toMutableSet()
                stmt.typeParameters?.forEach { innerTypeOnly.add(it.name.text) }
                // Remove parameter names — they are values, not types
                for (p in stmt.parameters) {
                    val pName = p.name
                    if (pName is Identifier) innerTypeOnly.remove(pName.text)
                }
                stmt.body?.let { checkTypeAsValueInStatements(it.statements, source, fileName, innerTypeOnly) }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            val innerTypeOnly = typeOnlyNames.toMutableSet()
                            member.typeParameters?.forEach { innerTypeOnly.add(it.name.text) }
                            member.body?.let { checkTypeAsValueInStatements(it.statements, source, fileName, innerTypeOnly) }
                        }
                        is Constructor -> member.body?.let {
                            checkTypeAsValueInStatements(it.statements, source, fileName, typeOnlyNames)
                        }
                        is PropertyDeclaration -> member.initializer?.let {
                            checkTypeAsValueInExpr(it, source, fileName, typeOnlyNames)
                        }
                        else -> {}
                    }
                }
            }
            else -> {}
        }
    }

    private fun checkTypeAsValueInExpr(
        expr: Expression,
        source: String,
        fileName: String,
        typeOnlyNames: Set<String>,
    ) {
        when (expr) {
            is Identifier -> {
                val name = expr.text
                if (name in TYPE_ONLY_KEYWORDS || name in typeOnlyNames) {
                    emitTS2693(name, expr, source, fileName)
                }
            }
            is NewExpression -> {
                // Check the constructor expression
                val ctorExpr = expr.expression
                if (ctorExpr is Identifier) {
                    val name = ctorExpr.text
                    if (name in TYPE_ONLY_KEYWORDS || name in typeOnlyNames) {
                        emitTS2693(name, ctorExpr, source, fileName)
                    }
                }
                // Recurse into arguments
                expr.arguments?.forEach { checkTypeAsValueInExpr(it, source, fileName, typeOnlyNames) }
            }
            is CallExpression -> {
                checkTypeAsValueInExpr(expr.expression, source, fileName, typeOnlyNames)
                expr.arguments.forEach { checkTypeAsValueInExpr(it, source, fileName, typeOnlyNames) }
            }
            is BinaryExpression -> {
                checkTypeAsValueInExpr(expr.left, source, fileName, typeOnlyNames)
                checkTypeAsValueInExpr(expr.right, source, fileName, typeOnlyNames)
            }
            is TypeOfExpression -> {
                // typeof T where T is a type param → TS2693
                val operand = expr.expression
                if (operand is Identifier) {
                    val name = operand.text
                    if (name in typeOnlyNames) {
                        emitTS2693(name, operand, source, fileName)
                    }
                }
            }
            is ParenthesizedExpression -> checkTypeAsValueInExpr(expr.expression, source, fileName, typeOnlyNames)
            is ConditionalExpression -> {
                checkTypeAsValueInExpr(expr.condition, source, fileName, typeOnlyNames)
                checkTypeAsValueInExpr(expr.whenTrue, source, fileName, typeOnlyNames)
                checkTypeAsValueInExpr(expr.whenFalse, source, fileName, typeOnlyNames)
            }
            is PropertyAccessExpression -> checkTypeAsValueInExpr(expr.expression, source, fileName, typeOnlyNames)
            is ElementAccessExpression -> {
                checkTypeAsValueInExpr(expr.expression, source, fileName, typeOnlyNames)
                checkTypeAsValueInExpr(expr.argumentExpression, source, fileName, typeOnlyNames)
            }
            else -> {}
        }
    }

    private fun emitTS2693(name: String, node: Node, source: String, fileName: String) {
        val start = node.pos
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "'$name' only refers to a type, but is being used as a value here.",
            category = DiagnosticCategory.Error,
            code = 2693,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Unreachable code checking (TS7027)
    // -----------------------------------------------------------------------

    /**
     * Check for unreachable code after return, throw, break, continue,
     * and infinite loops.
     */
    private fun checkUnreachableCode() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkUnreachableInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkUnreachableInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        var termIdx = -1
        for (i in statements.indices) {
            val stmt = statements[i]
            if (termIdx >= 0) {
                // Skip hoisted declarations — they're not unreachable
                if (stmt is FunctionDeclaration || stmt is ClassDeclaration ||
                    stmt is InterfaceDeclaration || stmt is TypeAliasDeclaration ||
                    stmt is EnumDeclaration || stmt is ModuleDeclaration ||
                    stmt is ImportDeclaration || stmt is ImportEqualsDeclaration ||
                    stmt is ExportDeclaration) {
                    continue
                }
                // Found first unreachable non-hoisted statement
                // Collect ALL remaining non-hoisted statements for the span
                val unreachableStmts = mutableListOf(stmt)
                for (j in i + 1 until statements.size) {
                    val s = statements[j]
                    if (s is FunctionDeclaration || s is ClassDeclaration ||
                        s is InterfaceDeclaration || s is TypeAliasDeclaration ||
                        s is EnumDeclaration || s is ModuleDeclaration ||
                        s is ImportDeclaration || s is ImportEqualsDeclaration ||
                        s is ExportDeclaration) continue
                    unreachableStmts.add(s)
                }
                emitTS7027(unreachableStmts, source, fileName)
                break // Only one TS7027 per block
            }
            // Check if this statement makes subsequent code unreachable
            if (isDefinitelyTerminating(stmt)) {
                termIdx = i
            }
            // Recurse into nested blocks
            checkUnreachableInNestedStatement(stmt, source, fileName)
        }
    }

    private fun isDefinitelyTerminating(stmt: Statement): Boolean {
        return when (stmt) {
            is ReturnStatement -> true
            is ThrowStatement -> true
            is BreakStatement -> true
            is ContinueStatement -> true
            is WhileStatement -> {
                // while(true) with no break is an infinite loop
                isAlwaysTrue(stmt.expression) && !containsBreak(stmt.statement)
            }
            is DoStatement -> {
                // do {} while(true) with no break is an infinite loop
                isAlwaysTrue(stmt.expression) && !containsBreak(stmt.statement)
            }
            is ForStatement -> {
                // for(;;) with no break is infinite
                stmt.condition == null && !containsBreak(stmt.statement)
            }
            is IfStatement -> {
                // if (cond) { return } else { return } — both branches terminate
                val thenTerm = isBlockTerminating(stmt.thenStatement)
                val elseTerm = stmt.elseStatement?.let { isBlockTerminating(it) } ?: false
                thenTerm && elseTerm
            }
            is SwitchStatement -> {
                // All cases + default terminate → switch is terminating
                val clauses = stmt.caseBlock
                val hasDefault = clauses.any { it is DefaultClause }
                if (!hasDefault) return false
                clauses.all { clause ->
                    val clauseStmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    // Empty case clauses fall through to the next
                    clauseStmts.isEmpty() || clauseStmts.any { isDefinitelyTerminating(it) }
                }
            }
            is TryStatement -> {
                // Try block terminates and there's no catch that doesn't terminate
                isBlockTerminating(stmt.tryBlock) &&
                    (stmt.catchClause == null || isBlockTerminating(stmt.catchClause!!.block))
            }
            else -> false
        }
    }

    private fun isBlockTerminating(stmt: Statement): Boolean {
        return when (stmt) {
            is Block -> stmt.statements.any { isDefinitelyTerminating(it) }
            else -> isDefinitelyTerminating(stmt)
        }
    }

    private fun isAlwaysTrue(expr: Expression): Boolean {
        return when (expr) {
            is Identifier -> expr.text == "true"
            is ParenthesizedExpression -> isAlwaysTrue(expr.expression)
            else -> false
        }
    }

    private fun containsBreak(stmt: Statement): Boolean {
        return when (stmt) {
            is BreakStatement -> true
            is Block -> stmt.statements.any { containsBreak(it) }
            is IfStatement -> containsBreak(stmt.thenStatement) ||
                (stmt.elseStatement?.let { containsBreak(it) } ?: false)
            is LabeledStatement -> containsBreak(stmt.statement)
            // Don't recurse into nested loops/switches — break applies to them, not the outer
            else -> false
        }
    }

    private fun checkUnreachableInNestedStatement(
        stmt: Statement,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is Block -> checkUnreachableInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkUnreachableInNestedStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkUnreachableInNestedStatement(it, source, fileName) }
            }
            is ForStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is DoStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    checkUnreachableInStatements(clauseStmts, source, fileName)
                }
            }
            is TryStatement -> {
                checkUnreachableInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let {
                    checkUnreachableInStatements(it.block.statements, source, fileName)
                }
                stmt.finallyBlock?.let {
                    checkUnreachableInStatements(it.statements, source, fileName)
                }
            }
            is FunctionDeclaration -> {
                stmt.body?.let { checkUnreachableInStatements(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> member.body?.let {
                            checkUnreachableInStatements(it.statements, source, fileName)
                        }
                        is Constructor -> member.body?.let {
                            checkUnreachableInStatements(it.statements, source, fileName)
                        }
                        is GetAccessor -> member.body?.let {
                            checkUnreachableInStatements(it.statements, source, fileName)
                        }
                        is SetAccessor -> member.body?.let {
                            checkUnreachableInStatements(it.statements, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is LabeledStatement -> checkUnreachableInNestedStatement(stmt.statement, source, fileName)
            is ExpressionStatement -> {
                // Check arrow/function expressions
                when (val expr = stmt.expression) {
                    is ArrowFunction -> when (val body = expr.body) {
                        is Block -> checkUnreachableInStatements(body.statements, source, fileName)
                        else -> {}
                    }
                    is FunctionExpression -> expr.body?.let {
                        checkUnreachableInStatements(it.statements, source, fileName)
                    }
                    else -> {}
                }
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    when (val init = decl.initializer) {
                        is ArrowFunction -> when (val body = init.body) {
                            is Block -> checkUnreachableInStatements(body.statements, source, fileName)
                            else -> {}
                        }
                        is FunctionExpression -> init.body?.let {
                            checkUnreachableInStatements(it.statements, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> {
                val body = stmt.body as? ModuleBlock ?: return
                checkUnreachableInStatements(body.statements, source, fileName)
            }
            else -> {}
        }
    }

    private fun emitTS7027(stmts: List<Statement>, source: String, fileName: String) {
        if (stmts.isEmpty()) return
        val first = stmts.first()
        // Skip leading whitespace on first statement
        var actualStart = first.pos
        while (actualStart < source.length && source[actualStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
            actualStart++
        }
        // Span covers just the first unreachable line
        var endOfLine = actualStart
        while (endOfLine < source.length && source[endOfLine] != '\n' && source[endOfLine] != '\r') {
            endOfLine++
        }
        val length = endOfLine - actualStart
        val (line, character) = getLineAndCharacterOfPosition(source, actualStart)
        diagnostics.add(Diagnostic(
            message = "Unreachable code detected.",
            category = DiagnosticCategory.Error,
            code = 7027,
            fileName = fileName,
            line = line,
            character = character,
            start = actualStart,
            length = length.coerceAtLeast(1),
        ))
    }

    // -----------------------------------------------------------------------
    // Missing function implementation checking (TS2391)
    // -----------------------------------------------------------------------

    /**
     * Check for function/method overload declarations that are missing their
     * implementation. Emits TS2391 "Function implementation is missing or
     * not immediately following the declaration."
     */
    private fun checkMissingImplementations() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkMissingImplInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkMissingImplInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
    ) {
        // Check file-level function declarations
        for (i in statements.indices) {
            val stmt = statements[i]
            when (stmt) {
                is FunctionDeclaration -> {
                    if (stmt.body == null && ModifierFlag.Declare !in stmt.modifiers) {
                        val name = stmt.name?.text ?: continue
                        // Check what follows this overload signature
                        val implResult = findImplementation(statements, i, name)
                        when (implResult) {
                            is ImplResult.Found -> {} // Same name follows
                            is ImplResult.WrongName -> {
                                // Next function has body but different name → TS2389
                                emitTS2389(implResult.nameNode, source, fileName, name)
                            }
                            is ImplResult.Missing -> {
                                emitTS2391(stmt.name!!, source, fileName)
                            }
                        }
                    }
                }
                is ClassDeclaration -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        checkMissingImplInClass(stmt.members, source, fileName)
                    }
                }
                is ModuleDeclaration -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        val body = stmt.body as? ModuleBlock ?: continue
                        checkMissingImplInStatements(body.statements, source, fileName)
                    }
                }
                else -> {}
            }
        }
    }

    private sealed class ImplResult {
        data object Found : ImplResult()
        data class WrongName(val nameNode: Node) : ImplResult()
        data object Missing : ImplResult()
    }

    private fun findImplementation(
        statements: List<Statement>,
        fromIdx: Int,
        name: String,
    ): ImplResult {
        // Look at subsequent statements for a same-name function.
        for (j in fromIdx + 1 until statements.size) {
            val next = statements[j]
            if (next is FunctionDeclaration) {
                if (next.name?.text == name) {
                    return ImplResult.Found // Same name follows
                }
                // Different name with body → TS2389
                if (next.body != null && next.name != null) {
                    return ImplResult.WrongName(next.name!!)
                }
            }
            break // Different statement breaks the chain
        }
        return ImplResult.Missing
    }

    private fun checkMissingImplInClass(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        // Track methods with bodies for TS2393 duplicate implementation detection
        val methodsWithBody = mutableMapOf<String, MethodDeclaration>()

        for (i in members.indices) {
            val member = members[i]
            if (member is MethodDeclaration) {
                val name = when (val n = member.name) {
                    is Identifier -> n.text
                    is StringLiteralNode -> n.text
                    else -> continue
                }
                // Include static qualifier in the key to distinguish instance vs static
                val isStatic = ModifierFlag.Static in member.modifiers
                val key = if (isStatic) "static:$name" else name

                if (member.body != null) {
                    val prev = methodsWithBody[key]
                    if (prev != null) {
                        // Duplicate implementation — TS2393 on both
                        emitTS2393(prev.name, source, fileName)
                        emitTS2393(member.name, source, fileName)
                    } else {
                        methodsWithBody[key] = member
                    }
                } else if (ModifierFlag.Abstract !in member.modifiers) {
                    // Overload without body — check for missing implementation
                    val displayName = when (member.name) {
                        is StringLiteralNode -> "\"$name\""
                        else -> name
                    }
                    val implResult = findMethodImplementation(members, i, name)
                    when (implResult) {
                        is ImplResult.Found -> {} // Same name follows
                        is ImplResult.WrongName -> {
                            emitTS2389(implResult.nameNode, source, fileName, displayName)
                        }
                        is ImplResult.Missing -> {
                            emitTS2391(member.name, source, fileName)
                        }
                    }
                }
            }
            // Recurse into nested class declarations
            if (member is PropertyDeclaration) {
                val init = member.initializer
                if (init is ClassExpression) {
                    checkMissingImplInClass(init.members, source, fileName)
                }
            }
        }
    }

    private fun emitTS2393(nameNode: Node, source: String, fileName: String) {
        val start = nameNode.pos
        val name = when (nameNode) {
            is Identifier -> nameNode.text
            is StringLiteralNode -> nameNode.text
            else -> return
        }
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Duplicate function implementation.",
            category = DiagnosticCategory.Error,
            code = 2393,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    private fun findMethodImplementation(
        members: List<ClassElement>,
        fromIdx: Int,
        name: String,
    ): ImplResult {
        for (j in fromIdx + 1 until members.size) {
            val next = members[j]
            if (next is MethodDeclaration) {
                val nextName = when (val n = next.name) {
                    is Identifier -> n.text
                    is StringLiteralNode -> n.text
                    else -> null
                }
                if (nextName == name) {
                    return ImplResult.Found
                }
                // Different name with body → TS2389
                if (next.body != null && nextName != null) {
                    return ImplResult.WrongName(next.name)
                }
            }
            break // Non-matching member breaks the chain
        }
        return ImplResult.Missing
    }

    private fun emitTS2391(nameNode: Node, source: String, fileName: String) {
        val start = nameNode.pos
        val name = when (nameNode) {
            is Identifier -> nameNode.text
            is StringLiteralNode -> nameNode.text
            else -> return
        }
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Function implementation is missing or not immediately following the declaration.",
            category = DiagnosticCategory.Error,
            code = 2391,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    private fun emitTS2389(nameNode: Node, source: String, fileName: String, expectedName: String) {
        val start = nameNode.pos
        val name = when (nameNode) {
            is Identifier -> nameNode.text
            is StringLiteralNode -> "\"${nameNode.text}\""
            else -> return
        }
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Function implementation name must be '$expectedName'.",
            category = DiagnosticCategory.Error,
            code = 2389,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Argument count checking (TS2554)
    // -----------------------------------------------------------------------

    /**
     * Check call and new expressions for wrong argument counts.
     * Only handles simple, direct function calls and class constructors
     * in the same file. Skips overloaded functions and rest parameters.
     */
    private fun checkArgumentCounts() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text

            // Build function/class declaration maps for this file
            val funcParams = mutableMapOf<String, FuncParamInfo>()
            val classCtorParams = mutableMapOf<String, FuncParamInfo>()
            collectFuncDecls(result.sourceFile.statements, funcParams, classCtorParams)

            // Walk statements checking call expressions
            checkArgCountInStatements(result.sourceFile.statements, funcParams, classCtorParams, source, fileName)
        }
    }

    private data class FuncParamInfo(
        val minParams: Int,     // required params
        val maxParams: Int,     // total params (required + optional)
        val hasRest: Boolean,   // has ...rest param
        val isOverloaded: Boolean, // has multiple declarations (skip checking)
    )

    private fun collectFuncDecls(
        statements: List<Statement>,
        funcParams: MutableMap<String, FuncParamInfo>,
        classCtorParams: MutableMap<String, FuncParamInfo>,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is FunctionDeclaration -> {
                    val name = stmt.name?.text ?: continue
                    if (stmt.body == null) {
                        // Overload signature — mark as overloaded
                        funcParams[name] = FuncParamInfo(0, Int.MAX_VALUE, hasRest = true, isOverloaded = true)
                        continue
                    }
                    if (funcParams[name]?.isOverloaded == true) continue // already marked as overloaded
                    val info = paramInfo(stmt.parameters)
                    funcParams[name] = info
                }
                is ClassDeclaration -> {
                    val name = stmt.name?.text ?: continue
                    val ctor = stmt.members.filterIsInstance<Constructor>().firstOrNull()
                    if (ctor != null) {
                        val info = paramInfo(ctor.parameters)
                        classCtorParams[name] = info
                    } else if (stmt.heritageClauses.isNullOrEmpty()) {
                        // No explicit constructor and no base class → 0 params
                        classCtorParams[name] = FuncParamInfo(0, 0, hasRest = false, isOverloaded = false)
                    }
                    // Skip classes with base class but no explicit constructor —
                    // they inherit the base constructor's param count which we can't resolve
                }
                is ModuleDeclaration -> {
                    val body = stmt.body as? ModuleBlock ?: continue
                    collectFuncDecls(body.statements, funcParams, classCtorParams)
                }
                else -> {}
            }
        }
    }

    private fun paramInfo(parameters: List<Parameter>): FuncParamInfo {
        var required = 0
        var total = 0
        var hasRest = false
        for (p in parameters) {
            // Skip `this` pseudo-parameter
            if (p.name is Identifier && (p.name as Identifier).text == "this") continue
            if (p.dotDotDotToken) {
                hasRest = true
                continue
            }
            total++
            if (!p.questionToken && p.initializer == null) {
                required++
            }
        }
        return FuncParamInfo(required, total, hasRest, isOverloaded = false)
    }

    private fun checkArgCountInStatements(
        statements: List<Statement>,
        funcParams: Map<String, FuncParamInfo>,
        classCtorParams: Map<String, FuncParamInfo>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            checkArgCountInStatement(stmt, funcParams, classCtorParams, source, fileName)
        }
    }

    private fun checkArgCountInStatement(
        stmt: Statement,
        funcParams: Map<String, FuncParamInfo>,
        classCtorParams: Map<String, FuncParamInfo>,
        source: String,
        fileName: String,
    ) {
        when (stmt) {
            is ExpressionStatement -> checkArgCountInExpr(stmt.expression, funcParams, classCtorParams, source, fileName)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
            is IfStatement -> {
                checkArgCountInExpr(stmt.expression, funcParams, classCtorParams, source, fileName)
                checkArgCountInStatement(stmt.thenStatement, funcParams, classCtorParams, source, fileName)
                stmt.elseStatement?.let { checkArgCountInStatement(it, funcParams, classCtorParams, source, fileName) }
            }
            is Block -> checkArgCountInStatements(stmt.statements, funcParams, classCtorParams, source, fileName)
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
                        }
                    }
                    is Expression -> checkArgCountInExpr(init, funcParams, classCtorParams, source, fileName)
                    else -> {}
                }
                stmt.condition?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
                stmt.incrementor?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
                checkArgCountInStatement(stmt.statement, funcParams, classCtorParams, source, fileName)
            }
            is ForInStatement -> checkArgCountInStatement(stmt.statement, funcParams, classCtorParams, source, fileName)
            is ForOfStatement -> checkArgCountInStatement(stmt.statement, funcParams, classCtorParams, source, fileName)
            is WhileStatement -> {
                checkArgCountInExpr(stmt.expression, funcParams, classCtorParams, source, fileName)
                checkArgCountInStatement(stmt.statement, funcParams, classCtorParams, source, fileName)
            }
            is DoStatement -> {
                checkArgCountInStatement(stmt.statement, funcParams, classCtorParams, source, fileName)
                checkArgCountInExpr(stmt.expression, funcParams, classCtorParams, source, fileName)
            }
            is SwitchStatement -> {
                checkArgCountInExpr(stmt.expression, funcParams, classCtorParams, source, fileName)
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    checkArgCountInStatements(clauseStmts, funcParams, classCtorParams, source, fileName)
                }
            }
            is FunctionDeclaration -> {
                stmt.body?.let {
                    checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> member.body?.let {
                            checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                        }
                        is Constructor -> member.body?.let {
                            checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                        }
                        is GetAccessor -> member.body?.let {
                            checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                        }
                        is SetAccessor -> member.body?.let {
                            checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                        }
                        is PropertyDeclaration -> member.initializer?.let {
                            checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                checkArgCountInStatements(stmt.tryBlock.statements, funcParams, classCtorParams, source, fileName)
                stmt.catchClause?.let {
                    checkArgCountInStatements(it.block.statements, funcParams, classCtorParams, source, fileName)
                }
                stmt.finallyBlock?.let {
                    checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                }
            }
            else -> {}
        }
    }

    private var argCountDepth = 0

    private fun checkArgCountInExpr(
        expr: Expression,
        funcParams: Map<String, FuncParamInfo>,
        classCtorParams: Map<String, FuncParamInfo>,
        source: String,
        fileName: String,
    ) {
        if (++argCountDepth > 200) { argCountDepth--; return }
        try { checkArgCountInExprCore(expr, funcParams, classCtorParams, source, fileName) }
        finally { argCountDepth-- }
    }

    private fun checkArgCountInExprCore(
        expr: Expression,
        funcParams: Map<String, FuncParamInfo>,
        classCtorParams: Map<String, FuncParamInfo>,
        source: String,
        fileName: String,
    ) {
        when (expr) {
            is CallExpression -> {
                // Check the callee
                val calleeName = when (val e = expr.expression) {
                    is Identifier -> e.text
                    else -> null
                }
                if (calleeName != null) {
                    val info = funcParams[calleeName]
                    if (info != null && !info.isOverloaded && !info.hasRest) {
                        val argCount = expr.arguments.size
                        if (argCount > info.maxParams) {
                            emitTS2554TooMany(info.minParams, info.maxParams, argCount, expr.arguments, info.maxParams, source, fileName)
                        } else if (argCount < info.minParams) {
                            emitTS2554TooFew(info.minParams, info.maxParams, argCount, expr.expression, source, fileName)
                        }
                    }
                }
                // Recurse into arguments
                for (arg in expr.arguments) {
                    checkArgCountInExpr(arg, funcParams, classCtorParams, source, fileName)
                }
                checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            }
            is NewExpression -> {
                val className = when (val e = expr.expression) {
                    is Identifier -> e.text
                    else -> null
                }
                if (className != null) {
                    val info = classCtorParams[className]
                    if (info != null && !info.isOverloaded && !info.hasRest) {
                        val argCount = expr.arguments?.size ?: 0
                        if (argCount > info.maxParams) {
                            val args = expr.arguments ?: emptyList()
                            emitTS2554TooMany(info.minParams, info.maxParams, argCount, args, info.maxParams, source, fileName)
                        } else if (argCount < info.minParams) {
                            emitTS2554TooFew(info.minParams, info.maxParams, argCount, expr.expression, source, fileName)
                        }
                    }
                }
                // Recurse
                expr.arguments?.forEach { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
            }
            is BinaryExpression -> {
                // Iterative right-spine walk to prevent StackOverflow on deep chains
                var current: Expression = expr
                while (current is BinaryExpression) {
                    checkArgCountInExpr(current.left, funcParams, classCtorParams, source, fileName)
                    current = current.right
                }
                checkArgCountInExpr(current, funcParams, classCtorParams, source, fileName)
            }
            is ParenthesizedExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is ConditionalExpression -> {
                checkArgCountInExpr(expr.condition, funcParams, classCtorParams, source, fileName)
                checkArgCountInExpr(expr.whenTrue, funcParams, classCtorParams, source, fileName)
                checkArgCountInExpr(expr.whenFalse, funcParams, classCtorParams, source, fileName)
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkArgCountInStatements(body.statements, funcParams, classCtorParams, source, fileName)
                    is Expression -> checkArgCountInExpr(body, funcParams, classCtorParams, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                expr.body?.let {
                    checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                }
            }
            is ArrayLiteralExpression -> {
                for (el in expr.elements) {
                    checkArgCountInExpr(el, funcParams, classCtorParams, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> checkArgCountInExpr(prop.initializer, funcParams, classCtorParams, source, fileName)
                        is ShorthandPropertyAssignment -> {}
                        is SpreadAssignment -> checkArgCountInExpr(prop.expression, funcParams, classCtorParams, source, fileName)
                        is MethodDeclaration -> prop.body?.let {
                            checkArgCountInStatements(it.statements, funcParams, classCtorParams, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    checkArgCountInExpr(span.expression, funcParams, classCtorParams, source, fileName)
                }
            }
            is PropertyAccessExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is ElementAccessExpression -> {
                checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
                checkArgCountInExpr(expr.argumentExpression, funcParams, classCtorParams, source, fileName)
            }
            is PrefixUnaryExpression -> checkArgCountInExpr(expr.operand, funcParams, classCtorParams, source, fileName)
            is PostfixUnaryExpression -> checkArgCountInExpr(expr.operand, funcParams, classCtorParams, source, fileName)
            is TypeAssertionExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is AsExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is NonNullExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is SpreadElement -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is AwaitExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is YieldExpression -> expr.expression?.let { checkArgCountInExpr(it, funcParams, classCtorParams, source, fileName) }
            is VoidExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is TypeOfExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is DeleteExpression -> checkArgCountInExpr(expr.expression, funcParams, classCtorParams, source, fileName)
            is TaggedTemplateExpression -> {
                checkArgCountInExpr(expr.tag, funcParams, classCtorParams, source, fileName)
            }
            else -> {}
        }
    }

    /**
     * Emit TS2554 for too many arguments.
     * Squiggle covers args[expectedCount] through args.last().
     */
    private fun formatExpectedArgs(minParams: Int, maxParams: Int): String =
        if (minParams != maxParams) "$minParams-$maxParams" else "$maxParams"

    private fun emitTS2554TooMany(
        minParams: Int,
        maxParams: Int,
        actual: Int,
        args: List<Expression>,
        firstExcessIdx: Int,
        source: String,
        fileName: String,
    ) {
        if (firstExcessIdx >= args.size) return
        val firstExcess = args[firstExcessIdx]
        val lastArg = args.last()
        val start = firstExcess.pos
        // Node.end includes the next token's scan position (always 1 char: ',' or ')'),
        // so subtract 1 to get the actual end of the argument text.
        val length = lastArg.end - 1 - start
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Expected ${formatExpectedArgs(minParams, maxParams)} arguments, but got $actual.",
            category = DiagnosticCategory.Error,
            code = 2554,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    private fun emitTS2554TooFew(
        minParams: Int,
        maxParams: Int,
        actual: Int,
        calleeExpr: Expression,
        source: String,
        fileName: String,
    ) {
        val start = calleeExpr.pos
        val length = calleeExpr.end - start
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Expected ${formatExpectedArgs(minParams, maxParams)} arguments, but got $actual.",
            category = DiagnosticCategory.Error,
            code = 2554,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Implicit this checking (TS2683)
    // -----------------------------------------------------------------------

    /**
     * Check for `this` expressions in functions without a `this:` parameter annotation
     * when noImplicitThis is enabled. Emits TS2683.
     *
     * The "this context" tracks whether `this` is typed (in a class member) or untyped
     * (in a regular function without `this:` param). Arrow functions are transparent —
     * they inherit the outer `this` context.
     *
     * @param shadowFunctionPos when non-null, the position of the function that shadows
     *   an outer typed `this` (for the related TS2738 diagnostic)
     */
    private fun checkImplicitThis() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkThisInStatements(
                result.sourceFile.statements, source, fileName,
                thisIsTyped = false, // file level — this is 'any' but we don't error at file level
                insideFunction = false, // not inside any function yet
                shadowFunctionPos = -1
            )
        }
    }

    private fun checkThisInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        thisIsTyped: Boolean,
        insideFunction: Boolean,
        shadowFunctionPos: Int,
    ) {
        for (stmt in statements) {
            checkThisInStatement(stmt, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
        }
    }

    private fun checkThisInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
        thisIsTyped: Boolean,
        insideFunction: Boolean,
        shadowFunctionPos: Int,
    ) {
        when (stmt) {
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val hasThisParam = hasThisParameter(stmt.parameters)
                val newThisIsTyped = hasThisParam
                val newShadowPos = if (!hasThisParam && thisIsTyped) {
                    // This function shadows a typed this context
                    stmt.name?.pos ?: stmt.pos
                } else if (!hasThisParam && !thisIsTyped) {
                    shadowFunctionPos // keep existing shadow info
                } else -1
                stmt.body?.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = newThisIsTyped,
                        insideFunction = true,
                        shadowFunctionPos = newShadowPos
                    )
                }
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                for (member in stmt.members) {
                    checkThisInClassElement(member, source, fileName)
                }
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let {
                        checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                    }
                }
            }
            is ExpressionStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ReturnStatement -> {
                stmt.expression?.let {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is IfStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInStatement(stmt.thenStatement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                stmt.elseStatement?.let {
                    checkThisInStatement(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is Block -> {
                checkThisInStatements(stmt.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ForStatement -> {
                stmt.initializer?.let {
                    when (it) {
                        is Expression -> checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                        is VariableDeclarationList -> {
                            for (decl in it.declarations) {
                                decl.initializer?.let { init ->
                                    checkThisInExpr(init, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                                }
                            }
                        }
                        else -> {}
                    }
                }
                stmt.condition?.let { checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos) }
                stmt.incrementor?.let { checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos) }
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ForInStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ForOfStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is WhileStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is DoStatement -> {
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is SwitchStatement -> {
                checkThisInExpr(stmt.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            checkThisInExpr(clause.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                            checkThisInStatements(clause.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                        }
                        is DefaultClause -> {
                            checkThisInStatements(clause.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                        }
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> {
                stmt.expression?.let {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is TryStatement -> {
                checkThisInStatements(stmt.tryBlock.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                stmt.catchClause?.let {
                    checkThisInStatements(it.block.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
                stmt.finallyBlock?.let {
                    checkThisInStatements(it.statements, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is LabeledStatement -> {
                checkThisInStatement(stmt.statement, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> checkThisInStatements(
                        body.statements, source, fileName,
                        thisIsTyped = false, insideFunction = false, shadowFunctionPos = -1
                    )
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun checkThisInClassElement(
        element: ClassElement,
        source: String,
        fileName: String,
    ) {
        // Inside class members, `this` is typed — no error
        when (element) {
            is MethodDeclaration -> {
                element.body?.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                    )
                }
            }
            is Constructor -> {
                element.body?.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                    )
                }
            }
            is GetAccessor -> {
                element.body?.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                    )
                }
            }
            is SetAccessor -> {
                element.body?.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                    )
                }
            }
            is PropertyDeclaration -> {
                // Property initializers: `this` is typed (class context)
                // but function expressions in property initializers create new `this` scope
                element.initializer?.let {
                    checkThisInExpr(
                        it, source, fileName,
                        thisIsTyped = true, insideFunction = false, shadowFunctionPos = -1
                    )
                }
            }
            else -> {}
        }
    }

    private fun checkThisInExpr(
        expr: Expression,
        source: String,
        fileName: String,
        thisIsTyped: Boolean,
        insideFunction: Boolean,
        shadowFunctionPos: Int,
    ) {
        when (expr) {
            is Identifier -> {
                if (expr.text == "this" && !thisIsTyped && insideFunction) {
                    emitTS2683(expr, source, fileName, shadowFunctionPos)
                }
            }
            is FunctionExpression -> {
                val hasThisParam = hasThisParameter(expr.parameters)
                val newThisIsTyped = hasThisParam
                val newShadowPos = if (!hasThisParam && thisIsTyped) {
                    // This function expression shadows a typed this context
                    expr.name?.pos ?: expr.pos
                } else if (!hasThisParam && !thisIsTyped) {
                    shadowFunctionPos
                } else -1
                expr.body.let {
                    checkThisInStatements(
                        it.statements, source, fileName,
                        thisIsTyped = newThisIsTyped,
                        insideFunction = true,
                        shadowFunctionPos = newShadowPos
                    )
                }
            }
            is ArrowFunction -> {
                // Arrow functions are transparent — inherit outer this context
                // insideFunction stays as-is: arrows don't create their own this
                when (val body = expr.body) {
                    is Block -> checkThisInStatements(
                        body.statements, source, fileName,
                        thisIsTyped, insideFunction,
                        shadowFunctionPos
                    )
                    is Expression -> checkThisInExpr(
                        body, source, fileName,
                        thisIsTyped, insideFunction,
                        shadowFunctionPos
                    )
                    else -> {}
                }
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    checkThisInClassElement(member, source, fileName)
                }
            }
            is CallExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                expr.arguments.forEach {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is NewExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                expr.arguments?.forEach {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is BinaryExpression -> {
                checkThisInExpr(expr.left, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInExpr(expr.right, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ParenthesizedExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is PropertyAccessExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ElementAccessExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInExpr(expr.argumentExpression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ConditionalExpression -> {
                checkThisInExpr(expr.condition, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInExpr(expr.whenTrue, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                checkThisInExpr(expr.whenFalse, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            val init = prop.initializer
                            if (init is FunctionExpression) {
                                // Function expressions as object literal property values have
                                // typed this (TypeScript infers the object type)
                                init.body.let {
                                    checkThisInStatements(
                                        it.statements, source, fileName,
                                        thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                                    )
                                }
                            } else {
                                checkThisInExpr(init, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                            }
                        }
                        is MethodDeclaration -> {
                            // Object literal methods have typed this (the object type)
                            prop.body?.let {
                                checkThisInStatements(
                                    it.statements, source, fileName,
                                    thisIsTyped = true, insideFunction = true, shadowFunctionPos = -1
                                )
                            }
                        }
                        is SpreadAssignment -> {
                            checkThisInExpr(prop.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                        }
                        is ShorthandPropertyAssignment -> {
                            prop.objectAssignmentInitializer?.let {
                                checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    checkThisInExpr(span.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is TaggedTemplateExpression -> {
                checkThisInExpr(expr.tag, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                when (val template = expr.template) {
                    is TemplateExpression -> {
                        for (span in template.templateSpans) {
                            checkThisInExpr(span.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                        }
                    }
                    else -> {}
                }
            }
            is PrefixUnaryExpression -> {
                checkThisInExpr(expr.operand, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is PostfixUnaryExpression -> {
                checkThisInExpr(expr.operand, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is TypeOfExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is VoidExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is DeleteExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is AwaitExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is SpreadElement -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is YieldExpression -> {
                expr.expression?.let {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            is AsExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is NonNullExpression -> {
                checkThisInExpr(expr.expression, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
            }
            is CommaListExpression -> {
                expr.elements.forEach {
                    checkThisInExpr(it, source, fileName, thisIsTyped, insideFunction, shadowFunctionPos)
                }
            }
            else -> {}
        }
    }

    private fun hasThisParameter(parameters: List<Parameter>): Boolean {
        val first = parameters.firstOrNull() ?: return false
        val name = first.name
        return name is Identifier && name.text == "this"
    }

    private fun emitTS2683(
        thisExpr: Identifier,
        source: String,
        fileName: String,
        shadowFunctionPos: Int,
    ) {
        val start = thisExpr.pos
        val length = 4 // "this".length
        val (line, character) = getLineAndCharacterOfPosition(source, start)

        val related = if (shadowFunctionPos >= 0) {
            val (shadowLine, shadowCol) = getLineAndCharacterOfPosition(source, shadowFunctionPos)
            listOf(Diagnostic(
                message = "An outer value of 'this' is shadowed by this container.",
                category = DiagnosticCategory.Error,
                code = 2738,
                fileName = fileName,
                line = shadowLine,
                character = shadowCol,
            ))
        } else emptyList()

        diagnostics.add(Diagnostic(
            message = "'this' implicitly has type 'any' because it does not have a type annotation.",
            category = DiagnosticCategory.Error,
            code = 2683,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = related,
        ))
    }

    // -----------------------------------------------------------------------
    // Duplicate object literal property checking (TS1117)
    // -----------------------------------------------------------------------

    private fun checkDuplicateObjectLiteralProperties() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForObjectLiterals(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForObjectLiterals(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            walkNodeForObjectLiterals(stmt, source, fileName)
        }
    }

    private fun walkNodeForObjectLiterals(node: Node, source: String, fileName: String, depth: Int = 0) {
        if (depth > 100) return
        when (node) {
            is ObjectLiteralExpression -> {
                checkObjectLiteralDuplicates(node, source, fileName)
                // Also recurse into property values
                for (prop in node.properties) {
                    when (prop) {
                        is PropertyAssignment -> walkNodeForObjectLiterals(prop.initializer, source, fileName, depth + 1)
                        is SpreadAssignment -> walkNodeForObjectLiterals(prop.expression, source, fileName, depth + 1)
                        else -> {}
                    }
                }
            }
            // Skip the left side of assignment expressions (destructuring patterns)
            // but do check the right side
            is VariableStatement -> {
                for (decl in node.declarationList.declarations) {
                    decl.initializer?.let { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
                }
            }
            is ExpressionStatement -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is ReturnStatement -> node.expression?.let { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
            is FunctionDeclaration -> node.body?.let { walkForObjectLiterals(it.statements, source, fileName) }
            is ClassDeclaration -> {
                for (member in node.members) {
                    when (member) {
                        is MethodDeclaration -> member.body?.let { walkForObjectLiterals(it.statements, source, fileName) }
                        is Constructor -> member.body?.let { walkForObjectLiterals(it.statements, source, fileName) }
                        is GetAccessor -> member.body?.let { walkForObjectLiterals(it.statements, source, fileName) }
                        is SetAccessor -> member.body?.let { walkForObjectLiterals(it.statements, source, fileName) }
                        is PropertyDeclaration -> member.initializer?.let { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
                        else -> {}
                    }
                }
            }
            is Block -> walkForObjectLiterals(node.statements, source, fileName)
            is IfStatement -> {
                walkNodeForObjectLiterals(node.thenStatement, source, fileName, depth + 1)
                node.elseStatement?.let { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
            }
            is ForStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is ForInStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is ForOfStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is WhileStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is DoStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is SwitchStatement -> {
                for (clause in node.caseBlock) {
                    when (clause) {
                        is CaseClause -> walkForObjectLiterals(clause.statements, source, fileName)
                        is DefaultClause -> walkForObjectLiterals(clause.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                walkForObjectLiterals(node.tryBlock.statements, source, fileName)
                node.catchClause?.block?.let { walkForObjectLiterals(it.statements, source, fileName) }
                node.finallyBlock?.let { walkForObjectLiterals(it.statements, source, fileName) }
            }
            is ArrowFunction -> {
                when (val body = node.body) {
                    is Block -> walkForObjectLiterals(body.statements, source, fileName)
                    else -> walkNodeForObjectLiterals(body, source, fileName, depth + 1)
                }
            }
            is FunctionExpression -> node.body.let { walkForObjectLiterals(it.statements, source, fileName) }
            is ParenthesizedExpression -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is BinaryExpression -> {
                // Skip left side of assignment if it's a destructuring pattern
                if (node.operator == SyntaxKind.Equals && (node.left is ObjectLiteralExpression || node.left is ArrayLiteralExpression)) {
                    // Only recurse into the right side
                    walkNodeForObjectLiterals(node.right, source, fileName, depth + 1)
                } else {
                    walkNodeForObjectLiterals(node.left, source, fileName, depth + 1)
                    walkNodeForObjectLiterals(node.right, source, fileName, depth + 1)
                }
            }
            is ConditionalExpression -> {
                walkNodeForObjectLiterals(node.whenTrue, source, fileName, depth + 1)
                walkNodeForObjectLiterals(node.whenFalse, source, fileName, depth + 1)
            }
            is CallExpression -> {
                walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
                node.arguments.forEach { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
            }
            is NewExpression -> {
                node.arguments?.forEach { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
            }
            is ArrayLiteralExpression -> {
                node.elements.forEach { walkNodeForObjectLiterals(it, source, fileName, depth + 1) }
            }
            is PropertyAccessExpression -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is TemplateExpression -> {
                node.templateSpans.forEach { walkNodeForObjectLiterals(it.expression, source, fileName, depth + 1) }
            }
            is LabeledStatement -> walkNodeForObjectLiterals(node.statement, source, fileName, depth + 1)
            is ModuleDeclaration -> {
                val body = node.body
                if (body is ModuleBlock) walkForObjectLiterals(body.statements, source, fileName)
            }
            is ExportAssignment -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is SpreadElement -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is AsExpression -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            is TypeAssertionExpression -> walkNodeForObjectLiterals(node.expression, source, fileName, depth + 1)
            else -> {}
        }
    }

    private fun checkObjectLiteralDuplicates(obj: ObjectLiteralExpression, source: String, fileName: String) {
        // Track what kind of property has been seen: 'p' = property/method, 'g' = getter, 's' = setter
        val seen = mutableMapOf<String, Char>()
        for (prop in obj.properties) {
            val name = getPropertyName(prop) ?: continue
            val kind = when (prop) {
                is GetAccessor -> 'g'
                is SetAccessor -> 's'
                else -> 'p' // PropertyAssignment, ShorthandPropertyAssignment, MethodDeclaration
            }
            val prevKind = seen[name]
            val isDuplicate = when {
                prevKind == null -> false
                // getter + setter or setter + getter is OK
                prevKind == 'g' && kind == 's' -> false
                prevKind == 's' && kind == 'g' -> false
                else -> true
            }
            if (isDuplicate) {
                val nameNode = getPropertyNameNode(prop) ?: continue
                val start = nameNode.pos
                val length = getPropertyNameLength(nameNode, name)
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "An object literal cannot have multiple properties with the same name.",
                    category = DiagnosticCategory.Error,
                    code = 1117,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            } else {
                seen[name] = kind
            }
        }
    }

    private fun getPropertyNameLength(nameNode: Node, name: String): Int {
        return when (nameNode) {
            is Identifier -> name.length
            is StringLiteralNode -> name.length + 2 // quotes
            is NumericLiteralNode -> name.length
            is ComputedPropertyName -> nameNode.end - nameNode.pos // [expr]
            else -> nameNode.end - nameNode.pos
        }
    }

    private fun getPropertyName(prop: Node): String? {
        return when (prop) {
            is PropertyAssignment -> when (val name = prop.name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                is NumericLiteralNode -> name.text
                else -> null
            }
            is ShorthandPropertyAssignment -> prop.name.text
            is MethodDeclaration -> when (val name = prop.name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                is NumericLiteralNode -> name.text
                else -> null
            }
            is GetAccessor -> when (val name = prop.name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                else -> null
            }
            is SetAccessor -> when (val name = prop.name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                else -> null
            }
            else -> null
        }
    }

    private fun getPropertyNameNode(prop: Node): Node? {
        return when (prop) {
            is PropertyAssignment -> prop.name
            is ShorthandPropertyAssignment -> prop.name
            is MethodDeclaration -> prop.name
            is GetAccessor -> prop.name
            is SetAccessor -> prop.name
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Super before this checking (TS17009)
    // -----------------------------------------------------------------------

    private fun checkSuperBeforeThis() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForDerivedConstructors(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForDerivedConstructors(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> {
                    // Check if class extends something (derived class)
                    if (stmt.heritageClauses?.any { it.token == SyntaxKind.ExtendsKeyword } == true) {
                        for (member in stmt.members) {
                            if (member is Constructor && member.body != null) {
                                checkConstructorThisBeforeSuper(member.body!!.statements, source, fileName)
                            }
                        }
                    }
                    // Recurse into class members for nested classes
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> member.body?.let { walkForDerivedConstructors(it.statements, source, fileName) }
                            is Constructor -> member.body?.let { walkForDerivedConstructors(it.statements, source, fileName) }
                            is PropertyDeclaration -> {
                                val init = member.initializer
                                if (init is ClassExpression && init.heritageClauses?.any { it.token == SyntaxKind.ExtendsKeyword } == true) {
                                    for (m in init.members) {
                                        if (m is Constructor && m.body != null) {
                                            checkConstructorThisBeforeSuper(m.body!!.statements, source, fileName)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { walkForDerivedConstructors(it.statements, source, fileName) }
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) walkForDerivedConstructors(body.statements, source, fileName)
                }
                is Block -> walkForDerivedConstructors(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    private fun isThisIdentifier(node: Node): Boolean =
        node is Identifier && node.text == "this"

    private fun isSuperIdentifier(node: Node): Boolean =
        node is Identifier && node.text == "super"

    private fun checkConstructorThisBeforeSuper(statements: List<Statement>, source: String, fileName: String) {
        // Walk statements until we find super() call; any this reference before that is TS17009
        for (stmt in statements) {
            // Check for super(this) — this in super call arguments is also an error
            val superCallThisRef = findThisInSuperCallArgs(stmt)
            if (superCallThisRef != null) {
                emitTS17009(superCallThisRef, source, fileName)
                return
            }
            // Check if this statement contains a super() call (and stop if so)
            if (containsSuperCall(stmt)) return
            // Check if this statement references 'this' before super
            val thisRef = findFirstThisReference(stmt)
            if (thisRef != null) {
                emitTS17009(thisRef, source, fileName)
                return // Only report first occurrence
            }
        }
    }

    private fun emitTS17009(thisRef: Identifier, source: String, fileName: String) {
        val start = thisRef.pos
        val length = 4 // "this" is 4 characters
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "'super' must be called before accessing 'this' in the constructor of a derived class.",
            category = DiagnosticCategory.Error,
            code = 17009,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    /** Find 'this' used in super() call arguments. */
    private fun findThisInSuperCallArgs(node: Node): Identifier? {
        if (node !is ExpressionStatement) return null
        val expr = node.expression
        if (expr is CallExpression && isSuperIdentifier(expr.expression)) {
            for (arg in expr.arguments) {
                findFirstThisInExpr(arg)?.let { return it }
            }
        }
        return null
    }

    private fun containsSuperCall(node: Node): Boolean {
        return when (node) {
            is ExpressionStatement -> containsSuperCallInExpr(node.expression)
            else -> false
        }
    }

    private fun containsSuperCallInExpr(expr: Expression): Boolean {
        return when (expr) {
            is CallExpression -> isSuperIdentifier(expr.expression) || containsSuperCallInExpr(expr.expression)
            is BinaryExpression -> containsSuperCallInExpr(expr.left) || containsSuperCallInExpr(expr.right)
            is ParenthesizedExpression -> containsSuperCallInExpr(expr.expression)
            else -> false
        }
    }

    private fun findFirstThisReference(node: Node): Identifier? {
        return when (node) {
            is ExpressionStatement -> findFirstThisInExpr(node.expression)
            is VariableStatement -> {
                for (decl in node.declarationList.declarations) {
                    decl.initializer?.let { findFirstThisInExpr(it) }?.let { return it }
                }
                null
            }
            is ReturnStatement -> node.expression?.let { findFirstThisInExpr(it) }
            is IfStatement -> findFirstThisInExpr(node.expression)
            else -> null
        }
    }

    private fun findFirstThisInExpr(expr: Expression): Identifier? {
        return when {
            isThisIdentifier(expr) -> expr as Identifier
            expr is PropertyAccessExpression -> findFirstThisInExpr(expr.expression)
            expr is CallExpression -> {
                // Check for super(this) — this in arguments is also flagged
                if (isSuperIdentifier(expr.expression)) {
                    // Check arguments for 'this'
                    for (arg in expr.arguments) {
                        findFirstThisInExpr(arg)?.let { return it }
                    }
                    return null
                }
                findFirstThisInExpr(expr.expression)
                    ?: run { for (arg in expr.arguments) { findFirstThisInExpr(arg)?.let { return it } }; null }
            }
            expr is BinaryExpression -> findFirstThisInExpr(expr.left) ?: findFirstThisInExpr(expr.right)
            expr is ParenthesizedExpression -> findFirstThisInExpr(expr.expression)
            expr is ElementAccessExpression -> findFirstThisInExpr(expr.expression)
            expr is ConditionalExpression -> findFirstThisInExpr(expr.condition)
            expr is AsExpression -> findFirstThisInExpr(expr.expression)
            expr is TypeAssertionExpression -> findFirstThisInExpr(expr.expression)
            expr is SpreadElement -> findFirstThisInExpr(expr.expression)
            expr is ArrayLiteralExpression -> {
                for (elem in expr.elements) { findFirstThisInExpr(elem)?.let { return it } }
                null
            }
            expr is ObjectLiteralExpression -> null // Object literal creates new scope for 'this'
            expr is ArrowFunction -> null
            expr is FunctionExpression -> null
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Const assignment checking (TS2540)
    // -----------------------------------------------------------------------

    private fun checkConstAssignment() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkConstAssignmentInStatements(result.sourceFile.statements, source, fileName, mutableSetOf())
        }
    }

    private fun checkConstAssignmentInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        constNames: MutableSet<String>,
    ) {
        for (stmt in statements) {
            // Collect const declarations
            if (stmt is VariableStatement) {
                if (stmt.declarationList.flags == SyntaxKind.ConstKeyword) {
                    for (decl in stmt.declarationList.declarations) {
                        val name = decl.name
                        if (name is Identifier) {
                            constNames.add(name.text)
                        }
                    }
                }
            }
            // Check for assignments to const variables
            checkConstAssignmentInStatement(stmt, source, fileName, constNames)
        }
    }

    private fun checkConstAssignmentInStatement(
        stmt: Statement,
        source: String,
        fileName: String,
        constNames: Set<String>,
    ) {
        when (stmt) {
            is ExpressionStatement -> checkConstAssignmentInExpr(stmt.expression, source, fileName, constNames)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkConstAssignmentInExpr(it, source, fileName, constNames) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { checkConstAssignmentInExpr(it, source, fileName, constNames) }
            is IfStatement -> {
                checkConstAssignmentInExpr(stmt.expression, source, fileName, constNames)
                checkConstAssignmentInStatement(stmt.thenStatement, source, fileName, constNames)
                stmt.elseStatement?.let { checkConstAssignmentInStatement(it, source, fileName, constNames) }
            }
            is Block -> checkConstAssignmentInStatements(stmt.statements, source, fileName, constNames.toMutableSet())
            is ForStatement -> {
                // Collect const in for init
                val forConsts = constNames.toMutableSet()
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        if (init.flags == SyntaxKind.ConstKeyword) {
                            for (decl in init.declarations) {
                                val name = decl.name
                                if (name is Identifier) forConsts.add(name.text)
                            }
                        }
                    }
                    is Expression -> checkConstAssignmentInExpr(init, source, fileName, constNames)
                    else -> {}
                }
                stmt.condition?.let { checkConstAssignmentInExpr(it, source, fileName, forConsts) }
                stmt.incrementor?.let { checkConstAssignmentInExpr(it, source, fileName, forConsts) }
                checkConstAssignmentInStatement(stmt.statement, source, fileName, forConsts)
            }
            is ForInStatement -> checkConstAssignmentInStatement(stmt.statement, source, fileName, constNames)
            is ForOfStatement -> checkConstAssignmentInStatement(stmt.statement, source, fileName, constNames)
            is WhileStatement -> {
                checkConstAssignmentInExpr(stmt.expression, source, fileName, constNames)
                checkConstAssignmentInStatement(stmt.statement, source, fileName, constNames)
            }
            is DoStatement -> {
                checkConstAssignmentInStatement(stmt.statement, source, fileName, constNames)
                checkConstAssignmentInExpr(stmt.expression, source, fileName, constNames)
            }
            is SwitchStatement -> {
                checkConstAssignmentInExpr(stmt.expression, source, fileName, constNames)
                for (clause in stmt.caseBlock) {
                    val stmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    checkConstAssignmentInStatements(stmts, source, fileName, constNames.toMutableSet())
                }
            }
            is TryStatement -> {
                checkConstAssignmentInStatements(stmt.tryBlock.statements, source, fileName, constNames.toMutableSet())
                stmt.catchClause?.block?.let {
                    checkConstAssignmentInStatements(it.statements, source, fileName, constNames.toMutableSet())
                }
                stmt.finallyBlock?.let {
                    checkConstAssignmentInStatements(it.statements, source, fileName, constNames.toMutableSet())
                }
            }
            is FunctionDeclaration -> {
                stmt.body?.let { checkConstAssignmentInStatements(it.statements, source, fileName, mutableSetOf()) }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    val body = when (member) {
                        is MethodDeclaration -> member.body
                        is Constructor -> member.body
                        is GetAccessor -> member.body
                        is SetAccessor -> member.body
                        else -> null
                    }
                    body?.let { checkConstAssignmentInStatements(it.statements, source, fileName, mutableSetOf()) }
                }
            }
            is LabeledStatement -> checkConstAssignmentInStatement(stmt.statement, source, fileName, constNames)
            else -> {}
        }
    }

    private fun checkConstAssignmentInExpr(
        expr: Expression,
        source: String,
        fileName: String,
        constNames: Set<String>,
    ) {
        when (expr) {
            is BinaryExpression -> {
                val op = expr.operator
                // Check for assignment operators
                if (op == SyntaxKind.Equals || op == SyntaxKind.PlusEquals || op == SyntaxKind.MinusEquals
                    || op == SyntaxKind.AsteriskEquals || op == SyntaxKind.SlashEquals
                    || op == SyntaxKind.PercentEquals || op == SyntaxKind.AmpersandEquals
                    || op == SyntaxKind.BarEquals || op == SyntaxKind.CaretEquals
                    || op == SyntaxKind.LessThanLessThanEquals || op == SyntaxKind.GreaterThanGreaterThanEquals
                    || op == SyntaxKind.GreaterThanGreaterThanGreaterThanEquals
                    || op == SyntaxKind.AsteriskAsteriskEquals
                    || op == SyntaxKind.BarBarEquals || op == SyntaxKind.AmpersandAmpersandEquals
                    || op == SyntaxKind.QuestionQuestionEquals) {
                    val left = expr.left
                    if (left is Identifier && left.text in constNames) {
                        emitTS2588(left, source, fileName)
                    }
                }
                checkConstAssignmentInExpr(expr.left, source, fileName, constNames)
                checkConstAssignmentInExpr(expr.right, source, fileName, constNames)
            }
            is PrefixUnaryExpression -> {
                if (expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus) {
                    val operand = expr.operand
                    if (operand is Identifier && operand.text in constNames) {
                        emitTS2588(operand, source, fileName)
                    }
                }
                checkConstAssignmentInExpr(expr.operand, source, fileName, constNames)
            }
            is PostfixUnaryExpression -> {
                if (expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus) {
                    val operand = unwrapNonNull(expr.operand)
                    if (operand is Identifier && operand.text in constNames) {
                        emitTS2588(operand, source, fileName)
                    }
                }
                checkConstAssignmentInExpr(expr.operand, source, fileName, constNames)
            }
            is CallExpression -> {
                checkConstAssignmentInExpr(expr.expression, source, fileName, constNames)
                expr.arguments.forEach { checkConstAssignmentInExpr(it, source, fileName, constNames) }
            }
            is ParenthesizedExpression -> checkConstAssignmentInExpr(expr.expression, source, fileName, constNames)
            is ConditionalExpression -> {
                checkConstAssignmentInExpr(expr.condition, source, fileName, constNames)
                checkConstAssignmentInExpr(expr.whenTrue, source, fileName, constNames)
                checkConstAssignmentInExpr(expr.whenFalse, source, fileName, constNames)
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkConstAssignmentInStatements(body.statements, source, fileName, mutableSetOf())
                    else -> {} // Arrow with expression body — const from outer scope may or may not apply
                }
            }
            is FunctionExpression -> {
                checkConstAssignmentInStatements(expr.body.statements, source, fileName, mutableSetOf())
            }
            else -> {}
        }
    }

    private fun unwrapNonNull(expr: Expression): Expression {
        var e = expr
        while (e is NonNullExpression) e = e.expression
        return e
    }

    private fun emitTS2588(id: Identifier, source: String, fileName: String) {
        val start = id.pos
        val length = id.text.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Cannot assign to '${id.text}' because it is a constant.",
            category = DiagnosticCategory.Error,
            code = 2588,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Parameter property checking (TS2369)
    // -----------------------------------------------------------------------

    private fun checkParameterProperties() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForParameterProperties(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForParameterProperties(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            when (stmt) {
                is FunctionDeclaration -> {
                    // Function parameters with access modifiers → TS2369
                    checkParamPropsInParams(stmt.parameters, source, fileName)
                    checkInvalidParameterModifiers(stmt.parameters, source, fileName)
                    stmt.body?.let { walkForParameterProperties(it.statements, source, fileName) }
                }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> {
                                checkParamPropsInParams(member.parameters, source, fileName)
                                checkInvalidParameterModifiers(member.parameters, source, fileName)
                                member.body?.let { walkForParameterProperties(it.statements, source, fileName) }
                            }
                            is Constructor -> {
                                // Flag parameter properties on constructor declarations without body
                                if (member.body == null) {
                                    checkParamPropsInParams(member.parameters, source, fileName)
                                }
                                // TS1090: invalid modifiers checked on ALL constructors (with or without body)
                                checkInvalidParameterModifiers(member.parameters, source, fileName)
                                member.body?.let { walkForParameterProperties(it.statements, source, fileName) }
                            }
                            is GetAccessor -> {
                                checkParamPropsInParams(member.parameters, source, fileName)
                                checkInvalidParameterModifiers(member.parameters, source, fileName)
                                member.body?.let { walkForParameterProperties(it.statements, source, fileName) }
                            }
                            is SetAccessor -> {
                                checkParamPropsInParams(member.parameters, source, fileName)
                                checkInvalidParameterModifiers(member.parameters, source, fileName)
                                member.body?.let { walkForParameterProperties(it.statements, source, fileName) }
                            }
                            else -> {}
                        }
                    }
                }
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        when (val init = decl.initializer) {
                            is ArrowFunction -> checkParamPropsInParams(init.parameters, source, fileName)
                            is FunctionExpression -> {
                                checkParamPropsInParams(init.parameters, source, fileName)
                                walkForParameterProperties(init.body.statements, source, fileName)
                            }
                            else -> {}
                        }
                    }
                }
                is ExpressionStatement -> walkForParamPropsInExpr(stmt.expression, source, fileName)
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) walkForParameterProperties(body.statements, source, fileName)
                }
                is Block -> walkForParameterProperties(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    private fun walkForParamPropsInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> checkParamPropsInParams(expr.parameters, source, fileName)
            is FunctionExpression -> {
                checkParamPropsInParams(expr.parameters, source, fileName)
                walkForParameterProperties(expr.body.statements, source, fileName)
            }
            is ParenthesizedExpression -> walkForParamPropsInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                walkForParamPropsInExpr(expr.left, source, fileName)
                walkForParamPropsInExpr(expr.right, source, fileName)
            }
            is CallExpression -> {
                walkForParamPropsInExpr(expr.expression, source, fileName)
                expr.arguments.forEach { walkForParamPropsInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    private fun isParameterPropertyModifier(flag: ModifierFlag): Boolean =
        flag == ModifierFlag.Public || flag == ModifierFlag.Private ||
        flag == ModifierFlag.Protected || flag == ModifierFlag.Readonly ||
        flag == ModifierFlag.Override

    private fun isInvalidParameterModifier(flag: ModifierFlag): Boolean =
        flag == ModifierFlag.Static || flag == ModifierFlag.Export ||
        flag == ModifierFlag.Async || flag == ModifierFlag.Declare

    private fun modifierFlagKeyword(flag: ModifierFlag): String = when (flag) {
        ModifierFlag.Static -> "static"
        ModifierFlag.Export -> "export"
        ModifierFlag.Async -> "async"
        ModifierFlag.Declare -> "declare"
        ModifierFlag.Public -> "public"
        ModifierFlag.Private -> "private"
        ModifierFlag.Protected -> "protected"
        ModifierFlag.Readonly -> "readonly"
        ModifierFlag.Override -> "override"
        ModifierFlag.Abstract -> "abstract"
        ModifierFlag.Default -> "default"
        ModifierFlag.Const -> "const"
        ModifierFlag.In -> "in"
        ModifierFlag.Out -> "out"
        ModifierFlag.Accessor -> "accessor"
    }

    private fun checkInvalidParameterModifiers(params: List<Parameter>, source: String, fileName: String) {
        for (param in params) {
            for (mod in param.modifiers) {
                if (!isInvalidParameterModifier(mod)) continue
                val keyword = modifierFlagKeyword(mod)
                // Find keyword position in source between param.pos and param.name.pos
                val searchStart = param.pos
                val searchEnd = param.name.pos
                val idx = source.indexOf(keyword, searchStart)
                if (idx < 0 || idx >= searchEnd) continue
                val (line, character) = getLineAndCharacterOfPosition(source, idx)
                diagnostics.add(Diagnostic(
                    message = "'$keyword' modifier cannot appear on a parameter.",
                    category = DiagnosticCategory.Error,
                    code = 1090,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = idx,
                    length = keyword.length,
                ))
            }
        }
    }

    private fun checkParamPropsInParams(params: List<Parameter>, source: String, fileName: String) {
        for (param in params) {
            if (param.modifiers.any { isParameterPropertyModifier(it) }) {
                // Parameter has access modifier outside constructor implementation → TS2369
                val start = param.pos
                // Skip leading whitespace
                var spanStart = start
                while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                // Calculate span: from modifier to end of type or name
                // Use the type pos + type text length if present, otherwise name
                val contentEnd = param.type?.let { typeNode ->
                    // For keyword types (string, number, etc.), use the type pos + keyword length
                    // For other types, use type.end - 1 to skip trailing trivia
                    var te = typeNode.end
                    while (te > typeNode.pos && te <= source.length && source[te - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == ')' || it == ',' }) te--
                    te
                } ?: run {
                    var ne = param.name.end
                    while (ne > param.name.pos && ne <= source.length && source[ne - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == ')' || it == ',' }) ne--
                    ne
                }
                val spanEnd = contentEnd
                val length = spanEnd - spanStart
                val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
                diagnostics.add(Diagnostic(
                    message = "A parameter property is only allowed in a constructor implementation.",
                    category = DiagnosticCategory.Error,
                    code = 2369,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = spanStart,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Super in non-derived class checking (TS2335)
    // -----------------------------------------------------------------------

    private fun checkSuperInNonDerived() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForSuperInNonDerived(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForSuperInNonDerived(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> {
                    val isDerived = stmt.heritageClauses?.any { it.token == SyntaxKind.ExtendsKeyword } == true
                    if (!isDerived) {
                        // Check all methods/constructors for super references
                        for (member in stmt.members) {
                            val body = when (member) {
                                is MethodDeclaration -> member.body
                                is Constructor -> member.body
                                is GetAccessor -> member.body
                                is SetAccessor -> member.body
                                else -> null
                            }
                            body?.let {
                                findSuperRefsInStatements(it.statements, source, fileName)
                            }
                        }
                    }
                    // Recurse into nested classes (even in derived ones)
                    for (member in stmt.members) {
                        val body = when (member) {
                            is MethodDeclaration -> member.body
                            is Constructor -> member.body
                            is GetAccessor -> member.body
                            is SetAccessor -> member.body
                            else -> null
                        }
                        body?.let { walkForSuperInNonDerived(it.statements, source, fileName) }
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { walkForSuperInNonDerived(it.statements, source, fileName) }
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) walkForSuperInNonDerived(body.statements, source, fileName)
                }
                is Block -> walkForSuperInNonDerived(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    private fun findSuperRefsInStatements(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            findSuperRefsInStatement(stmt, source, fileName)
        }
    }

    private fun findSuperRefsInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ExpressionStatement -> findSuperRefsInExpr(stmt.expression, source, fileName)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { findSuperRefsInExpr(it, source, fileName) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { findSuperRefsInExpr(it, source, fileName) }
            is IfStatement -> {
                findSuperRefsInExpr(stmt.expression, source, fileName)
                findSuperRefsInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { findSuperRefsInStatement(it, source, fileName) }
            }
            is Block -> findSuperRefsInStatements(stmt.statements, source, fileName)
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is Expression -> findSuperRefsInExpr(init, source, fileName)
                    else -> {}
                }
                findSuperRefsInStatement(stmt.statement, source, fileName)
            }
            is WhileStatement -> {
                findSuperRefsInExpr(stmt.expression, source, fileName)
                findSuperRefsInStatement(stmt.statement, source, fileName)
            }
            else -> {}
        }
    }

    private fun findSuperRefsInExpr(expr: Expression, source: String, fileName: String) {
        when {
            isSuperIdentifier(expr) -> {
                emitTS2335(expr as Identifier, source, fileName)
            }
            expr is PropertyAccessExpression -> {
                if (isSuperIdentifier(expr.expression)) {
                    emitTS2335(expr.expression as Identifier, source, fileName)
                } else {
                    findSuperRefsInExpr(expr.expression, source, fileName)
                }
            }
            expr is CallExpression -> {
                if (isSuperIdentifier(expr.expression)) {
                    emitTS2335(expr.expression as Identifier, source, fileName)
                } else {
                    findSuperRefsInExpr(expr.expression, source, fileName)
                }
                expr.arguments.forEach { findSuperRefsInExpr(it, source, fileName) }
            }
            expr is BinaryExpression -> {
                findSuperRefsInExpr(expr.left, source, fileName)
                findSuperRefsInExpr(expr.right, source, fileName)
            }
            expr is ParenthesizedExpression -> findSuperRefsInExpr(expr.expression, source, fileName)
            expr is ArrowFunction -> {} // Don't recurse into nested functions
            expr is FunctionExpression -> {} // Don't recurse into nested functions
            else -> {}
        }
    }

    private fun emitTS2335(superRef: Identifier, source: String, fileName: String) {
        val start = superRef.pos
        val length = 5 // "super" is 5 characters
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "'super' can only be referenced in a derived class.",
            category = DiagnosticCategory.Error,
            code = 2335,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Const without initializer checking (TS1155)
    // -----------------------------------------------------------------------

    private fun checkConstWithoutInitializer() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForConstWithoutInit(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForConstWithoutInit(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            when (stmt) {
                is VariableStatement -> {
                    if (stmt.declarationList.flags == SyntaxKind.ConstKeyword
                        && ModifierFlag.Declare !in stmt.modifiers) {
                        for (decl in stmt.declarationList.declarations) {
                            if (decl.initializer == null) {
                                // const without initializer
                                val nameNode = decl.name
                                if (nameNode is Identifier) {
                                    val start = nameNode.pos
                                    val length = nameNode.text.length
                                    val (line, character) = getLineAndCharacterOfPosition(source, start)
                                    diagnostics.add(Diagnostic(
                                        message = "'const' declarations must be initialized.",
                                        category = DiagnosticCategory.Error,
                                        code = 1155,
                                        fileName = fileName,
                                        line = line,
                                        character = character,
                                        start = start,
                                        length = length,
                                    ))
                                }
                            }
                        }
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { walkForConstWithoutInit(it.statements, source, fileName) }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        val body = when (member) {
                            is MethodDeclaration -> member.body
                            is Constructor -> member.body
                            is GetAccessor -> member.body
                            is SetAccessor -> member.body
                            else -> null
                        }
                        body?.let { walkForConstWithoutInit(it.statements, source, fileName) }
                    }
                }
                is Block -> walkForConstWithoutInit(stmt.statements, source, fileName)
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) walkForConstWithoutInit(body.statements, source, fileName)
                }
                is IfStatement -> {
                    walkForConstWithoutInit(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let { walkForConstWithoutInit(listOf(it), source, fileName) }
                }
                is ForStatement -> walkForConstWithoutInit(listOf(stmt.statement), source, fileName)
                is WhileStatement -> walkForConstWithoutInit(listOf(stmt.statement), source, fileName)
                is DoStatement -> walkForConstWithoutInit(listOf(stmt.statement), source, fileName)
                is SwitchStatement -> {
                    for (clause in stmt.caseBlock) {
                        val stmts = when (clause) {
                            is CaseClause -> clause.statements
                            is DefaultClause -> clause.statements
                            else -> emptyList()
                        }
                        walkForConstWithoutInit(stmts, source, fileName)
                    }
                }
                is TryStatement -> {
                    walkForConstWithoutInit(stmt.tryBlock.statements, source, fileName)
                    stmt.catchClause?.block?.let { walkForConstWithoutInit(it.statements, source, fileName) }
                    stmt.finallyBlock?.let { walkForConstWithoutInit(it.statements, source, fileName) }
                }
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reserved word identifier checking (TS1359)
    // -----------------------------------------------------------------------

    private val reservedWords = setOf("await", "yield", "void", "delete", "typeof",
        "instanceof", "in", "of", "new", "return", "throw", "case", "switch",
        "if", "else", "for", "while", "do", "try", "catch", "finally",
        "with", "debugger", "var", "let", "const", "class", "function",
        "import", "export", "default", "extends", "implements", "enum",
        "interface", "package", "private", "protected", "public", "static")

    private fun checkReservedWordIdentifiers() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForReservedWords(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForReservedWords(statements: List<Statement>, source: String, fileName: String) {
        for (stmt in statements) {
            when (stmt) {
                is FunctionDeclaration -> {
                    if (ModifierFlag.Async in stmt.modifiers) {
                        checkAwaitParams(stmt.parameters, source, fileName)
                    }
                    stmt.body?.let { walkForReservedWords(it.statements, source, fileName) }
                }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> {
                                if (ModifierFlag.Async in member.modifiers) {
                                    checkAwaitParams(member.parameters, source, fileName)
                                }
                                member.body?.let { walkForReservedWords(it.statements, source, fileName) }
                            }
                            is Constructor -> member.body?.let { walkForReservedWords(it.statements, source, fileName) }
                            is GetAccessor -> member.body?.let { walkForReservedWords(it.statements, source, fileName) }
                            is SetAccessor -> member.body?.let { walkForReservedWords(it.statements, source, fileName) }
                            else -> {}
                        }
                    }
                }
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        when (val init = decl.initializer) {
                            is ArrowFunction -> {
                                if (ModifierFlag.Async in init.modifiers) {
                                    checkAwaitParams(init.parameters, source, fileName)
                                }
                            }
                            is FunctionExpression -> {
                                if (ModifierFlag.Async in init.modifiers) {
                                    checkAwaitParams(init.parameters, source, fileName)
                                }
                                walkForReservedWords(init.body.statements, source, fileName)
                            }
                            else -> {}
                        }
                    }
                }
                is ExpressionStatement -> walkForReservedWordsInExpr(stmt.expression, source, fileName)
                is Block -> walkForReservedWords(stmt.statements, source, fileName)
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) walkForReservedWords(body.statements, source, fileName)
                }
                // Check enum names that are reserved words
                is EnumDeclaration -> {
                    val name = stmt.name.text
                    if (name == "void" || name == "await" || name == "yield") {
                        emitTS1359(stmt.name, source, fileName, name)
                    }
                }
                else -> {}
            }
        }
    }

    private fun walkForReservedWordsInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                if (ModifierFlag.Async in expr.modifiers) {
                    checkAwaitParams(expr.parameters, source, fileName)
                }
            }
            is FunctionExpression -> {
                if (ModifierFlag.Async in expr.modifiers) {
                    checkAwaitParams(expr.parameters, source, fileName)
                }
                walkForReservedWords(expr.body.statements, source, fileName)
            }
            is BinaryExpression -> {
                walkForReservedWordsInExpr(expr.left, source, fileName)
                walkForReservedWordsInExpr(expr.right, source, fileName)
            }
            is ParenthesizedExpression -> walkForReservedWordsInExpr(expr.expression, source, fileName)
            is CallExpression -> {
                walkForReservedWordsInExpr(expr.expression, source, fileName)
                expr.arguments.forEach { walkForReservedWordsInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    private fun checkAwaitParams(params: List<Parameter>, source: String, fileName: String) {
        for (param in params) {
            val name = param.name
            if (name is Identifier && name.text == "await") {
                emitTS1359(name, source, fileName, "await")
            }
        }
    }

    private fun emitTS1359(nameNode: Identifier, source: String, fileName: String, word: String) {
        val start = nameNode.pos
        val length = word.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Identifier expected. '$word' is a reserved word that cannot be used here.",
            category = DiagnosticCategory.Error,
            code = 1359,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // TS6131: Cannot compile modules using option 'outFile' unless '--module' is 'amd' or 'system'
    // Only emitted when module kind is NOT explicitly set (defaulted from target).
    // When module is explicitly set, TS6082 handles the conflict at the configuration level.
    private fun checkOutFileModuleConflict() {
        if (options.outFile == null) return
        // When 'out' is set (removed option), TS5102 handles it — don't add TS6131
        if (options.out != null) return
        // When module is explicitly set, TS6082 handles it (from TypeScriptCompiler.kt)
        if (options.module != null) return
        val effectiveModule = options.effectiveModule
        if (effectiveModule == ModuleKind.AMD || effectiveModule == ModuleKind.System
            || effectiveModule == ModuleKind.UMD) return
        if (effectiveModule == ModuleKind.None) return

        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            // Find the first module statement (import/export)
            val firstModuleStmt = findFirstModuleStatement(result.sourceFile.statements)
                ?: continue

            // Determine diagnostic span based on statement type
            val (spanStart, spanLength) = getModuleStatementSpan(firstModuleStmt, source)
            val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
            diagnostics.add(Diagnostic(
                message = "Cannot compile modules using option 'outFile' unless the '--module' flag is 'amd' or 'system'.",
                category = DiagnosticCategory.Error,
                code = 6131,
                fileName = fileName,
                line = line,
                character = character,
                start = spanStart,
                length = spanLength,
            ))
        }
    }

    private fun findFirstModuleStatement(statements: List<Statement>): Statement? {
        for (stmt in statements) {
            when (stmt) {
                is ImportDeclaration -> return stmt
                is ImportEqualsDeclaration -> return stmt
                is ExportDeclaration -> return stmt
                is ExportAssignment -> return stmt
                else -> {
                    val modifiers = when (stmt) {
                        is FunctionDeclaration -> stmt.modifiers
                        is ClassDeclaration -> stmt.modifiers
                        is VariableStatement -> stmt.modifiers
                        is EnumDeclaration -> stmt.modifiers
                        is InterfaceDeclaration -> stmt.modifiers
                        is TypeAliasDeclaration -> stmt.modifiers
                        is ModuleDeclaration -> stmt.modifiers
                        else -> emptySet()
                    }
                    if (ModifierFlag.Export in modifiers) return stmt
                }
            }
        }
        return null
    }

    /**
     * Get the diagnostic span for a module statement.
     * For class/function/enum/interface: span covers the name identifier.
     * For variable statements and imports: span covers the entire statement including export keyword.
     */
    private fun getModuleStatementSpan(stmt: Statement, source: String): Pair<Int, Int> {
        // For class/function declarations, use the name identifier position
        when (stmt) {
            is ClassDeclaration -> stmt.name?.let {
                return Pair(it.pos, it.text.length)
            }
            is FunctionDeclaration -> stmt.name?.let {
                return Pair(it.pos, it.text.length)
            }
            is EnumDeclaration -> {
                return Pair(stmt.name.pos, stmt.name.text.length)
            }
            is InterfaceDeclaration -> {
                return Pair(stmt.name.pos, stmt.name.text.length)
            }
            else -> {}
        }
        // For everything else (export var, import, export =, etc.), use the full statement span.
        // The statement pos may start after the 'export' keyword, so search backwards for it.
        var start = stmt.pos
        if (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) {
            // Find the 'export' keyword before the var/let/const keyword
            var p = start - 1
            while (p >= 0 && source[p] in " \t\r\n") p--
            // p should now be at the 't' of 'export'
            if (p >= 5 && source.substring(p - 5, p + 1) == "export") {
                start = p - 5
            }
        }
        // Find the end of actual content (before trailing whitespace/newline)
        var end = stmt.end
        while (end > start && end - 1 < source.length && source[end - 1] in " \t\r\n") end--
        return Pair(start, (end - start).coerceAtLeast(1))
    }

    // TS1185: Merge conflict marker encountered
    private fun checkConflictMarkers() {
        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            // Scan for conflict markers at the start of lines
            var i = 0
            while (i < source.length) {
                // Check if we're at the start of a line (position 0 or preceded by newline)
                if (i == 0 || source[i - 1] == '\n') {
                    val ch = source[i]
                    val isMarker = when (ch) {
                        '<' -> i + 6 < source.length && source.substring(i, i + 7) == "<<<<<<<"
                        '=' -> i + 6 < source.length && source.substring(i, i + 7) == "======="
                        '>' -> i + 6 < source.length && source.substring(i, i + 7) == ">>>>>>>"
                        '|' -> i + 6 < source.length && source.substring(i, i + 7) == "|||||||"
                        else -> false
                    }
                    if (isMarker) {
                        val (line, character) = getLineAndCharacterOfPosition(source, i)
                        diagnostics.add(Diagnostic(
                            message = "Merge conflict marker encountered.",
                            category = DiagnosticCategory.Error,
                            code = 1185,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = i,
                            length = 7,
                        ))
                    }
                }
                i++
            }
        }
    }

    // TS1148: Cannot use imports, exports, or module augmentations when '--module' is 'none'
    // Only fires for pre-ES2015 targets — ES2015+ supports module syntax natively
    private fun checkModuleNoneConflict() {
        if (options.module != ModuleKind.None) return
        if (options.target >= ScriptTarget.ES2015) return

        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            val firstModuleStmt = findFirstModuleStatement(result.sourceFile.statements)
                ?: continue

            val (spanStart, spanLength) = getModuleStatementSpan(firstModuleStmt, source)
            val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
            diagnostics.add(Diagnostic(
                message = "Cannot use imports, exports, or module augmentations when '--module' is 'none'.",
                category = DiagnosticCategory.Error,
                code = 1148,
                fileName = fileName,
                line = line,
                character = character,
                start = spanStart,
                length = spanLength,
            ))
        }
    }

    // TS1218: Export assignment is not supported when '--module' flag is 'system'
    private fun checkExportAssignmentInSystem() {
        if (options.effectiveModule != ModuleKind.System) return

        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            for (stmt in result.sourceFile.statements) {
                if (stmt is ExportAssignment && stmt.isExportEquals) {
                    // Span covers the entire `export = expr;` statement
                    var end = stmt.end
                    while (end > stmt.pos && end - 1 < source.length && source[end - 1] in " \t\r\n") end--
                    val length = (end - stmt.pos).coerceAtLeast(1)
                    val (line, character) = getLineAndCharacterOfPosition(source, stmt.pos)
                    diagnostics.add(Diagnostic(
                        message = "Export assignment is not supported when '--module' flag is 'system'.",
                        category = DiagnosticCategory.Error,
                        code = 1218,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = stmt.pos,
                        length = length,
                    ))
                }
            }
        }
    }

    // TS2441: Duplicate identifier 'exports'/'require'. Compiler reserves name in top level scope.
    // Applies to CJS/AMD/UMD/System module formats where exports/require are runtime variables.
    private fun checkReservedModuleNames() {
        // Skip when no emit (no runtime conflict possible)
        if (options.noEmit) return
        val module = options.effectiveModule
        // Only applies to module formats that use exports/require at runtime
        if (module != ModuleKind.CommonJS && module != ModuleKind.AMD
            && module != ModuleKind.UMD && module != ModuleKind.System) return

        val reservedNames = setOf("exports", "require")

        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            if (!isModuleFile(result.sourceFile.statements)) continue

            for (stmt in result.sourceFile.statements) {
                // Check top-level function/class/enum/variable declarations
                // Skip ambient (declare) declarations — they don't conflict at runtime
                val names = mutableListOf<Identifier>()
                when (stmt) {
                    is FunctionDeclaration -> {
                        if (ModifierFlag.Declare !in stmt.modifiers) stmt.name?.let { names.add(it) }
                    }
                    is ClassDeclaration -> {
                        if (ModifierFlag.Declare !in stmt.modifiers) stmt.name?.let { names.add(it) }
                    }
                    is EnumDeclaration -> {
                        if (ModifierFlag.Declare !in stmt.modifiers) names.add(stmt.name)
                    }
                    is VariableStatement -> {
                        if (ModifierFlag.Declare !in stmt.modifiers) {
                            for (decl in stmt.declarationList.declarations) {
                                val n = decl.name
                                if (n is Identifier) names.add(n)
                            }
                        }
                    }
                    else -> {}
                }
                for (name in names) {
                    if (name.text in reservedNames) {
                        val (line, character) = getLineAndCharacterOfPosition(source, name.pos)
                        diagnostics.add(Diagnostic(
                            message = "Duplicate identifier '${name.text}'. Compiler reserves name '${name.text}' in top level scope of a module.",
                            category = DiagnosticCategory.Error,
                            code = 2441,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = name.pos,
                            length = name.text.length,
                        ))
                    }
                }
            }
        }
    }

    // TS1250: Function declarations not allowed inside blocks in strict mode targeting ES5
    private fun checkBlockScopedFunctionDeclarations() {
        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            // Determine if file is in strict mode
            val isStrict = options.alwaysStrict == true || options.strict ||
                (result.sourceFile.statements.firstOrNull()?.let {
                    it is ExpressionStatement && it.expression is StringLiteralNode &&
                        ((it.expression as StringLiteralNode).text == "use strict")
                } == true)
            if (!isStrict) continue

            checkBlockFuncDeclInStatements(result.sourceFile.statements, source, fileName, inBlock = false)
        }
    }

    private fun checkBlockFuncDeclInStatements(
        statements: List<Statement>, source: String, fileName: String, inBlock: Boolean
    ) {
        for (stmt in statements) {
            if (inBlock && stmt is FunctionDeclaration) {
                val name = stmt.name ?: continue
                val (line, character) = getLineAndCharacterOfPosition(source, name.pos)
                diagnostics.add(Diagnostic(
                    message = "Function declarations are not allowed inside blocks in strict mode when targeting 'ES5'.",
                    category = DiagnosticCategory.Error,
                    code = 1250,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = name.pos,
                    length = name.text.length,
                ))
            }
            // Recurse into blocks (but NOT function bodies — those create new scopes)
            checkBlockFuncDeclInStatement(stmt, source, fileName)
        }
    }

    private fun checkBlockFuncDeclInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is IfStatement -> {
                val thenStmt = stmt.thenStatement
                if (thenStmt is Block) {
                    checkBlockFuncDeclInStatements(thenStmt.statements, source, fileName, inBlock = true)
                }
                val elseStmt = stmt.elseStatement
                if (elseStmt is Block) {
                    checkBlockFuncDeclInStatements(elseStmt.statements, source, fileName, inBlock = true)
                } else if (elseStmt != null) {
                    checkBlockFuncDeclInStatement(elseStmt, source, fileName)
                }
            }
            is ForStatement -> {
                val body = stmt.statement
                if (body is Block) {
                    checkBlockFuncDeclInStatements(body.statements, source, fileName, inBlock = true)
                }
            }
            is ForInStatement -> {
                val body = stmt.statement
                if (body is Block) {
                    checkBlockFuncDeclInStatements(body.statements, source, fileName, inBlock = true)
                }
            }
            is ForOfStatement -> {
                val body = stmt.statement
                if (body is Block) {
                    checkBlockFuncDeclInStatements(body.statements, source, fileName, inBlock = true)
                }
            }
            is WhileStatement -> {
                val body = stmt.statement
                if (body is Block) {
                    checkBlockFuncDeclInStatements(body.statements, source, fileName, inBlock = true)
                }
            }
            is DoStatement -> {
                val body = stmt.statement
                if (body is Block) {
                    checkBlockFuncDeclInStatements(body.statements, source, fileName, inBlock = true)
                }
            }
            is Block -> {
                // Standalone block
                checkBlockFuncDeclInStatements(stmt.statements, source, fileName, inBlock = true)
            }
            is TryStatement -> {
                checkBlockFuncDeclInStatements(stmt.tryBlock.statements, source, fileName, inBlock = true)
                stmt.catchClause?.let { catch ->
                    checkBlockFuncDeclInStatements(catch.block.statements, source, fileName, inBlock = true)
                }
                stmt.finallyBlock?.let { finally_ ->
                    checkBlockFuncDeclInStatements(finally_.statements, source, fileName, inBlock = true)
                }
            }
            is LabeledStatement -> {
                checkBlockFuncDeclInStatement(stmt.statement, source, fileName)
            }
            // Don't recurse into functions/classes/modules — they create new scopes
            else -> {}
        }
    }

    // TS1191: An import declaration cannot have modifiers
    private fun checkImportModifiers() {
        for (result in binderResults) {
            if (isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            for (stmt in result.sourceFile.statements) {
                if (stmt is ImportDeclaration && ModifierFlag.Export in stmt.modifiers) {
                    // Squiggle covers the 'export' keyword
                    val (line, character) = getLineAndCharacterOfPosition(source, stmt.pos)
                    diagnostics.add(Diagnostic(
                        message = "An import declaration cannot have modifiers.",
                        category = DiagnosticCategory.Error,
                        code = 1191,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = stmt.pos,
                        length = 6, // "export"
                    ))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Block-scoped variable use before declaration (TS2448/TS2450)
    // -----------------------------------------------------------------------

    private fun checkUseBeforeDeclaration() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkUBDInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkUBDInStatements(stmts: List<Statement>, source: String, fileName: String) {
        // Collect all let/const/enum declaration names in this block with their positions
        val blockScopedDecls = collectBlockScopedDeclsEx(stmts, source)
        // Remove names that also have hoisted declarations (function/var) in the same scope
        // because hoisted declarations make the name available before its let/const decl
        val hoistedNames = mutableSetOf<String>()
        for (stmt in stmts) {
            if (stmt is FunctionDeclaration && stmt.name != null) {
                hoistedNames.add(stmt.name!!.text)
            }
            if (stmt is VariableStatement && stmt.declarationList.flags == SyntaxKind.VarKeyword) {
                for (d in stmt.declarationList.declarations) {
                    if (d.name is Identifier) hoistedNames.add((d.name as Identifier).text)
                }
            }
        }
        for (name in hoistedNames) blockScopedDecls.remove(name)

        for (stmt in stmts) {
            checkUBDForwardRefs(stmt, blockScopedDecls, source, fileName)
            checkUBDInStatement(stmt, source, fileName, blockScopedDecls)
        }
    }

    /** Check all identifier usages in this statement for forward references to block-scoped declarations */
    private fun checkUBDForwardRefs(stmt: Statement, blockDecls: Map<String, BlockScopedDecl>, source: String, fileName: String) {
        when (stmt) {
            is ExpressionStatement -> checkUBDForwardInExpr(stmt.expression, blockDecls, source, fileName)
            is VariableStatement -> {
                val kind = stmt.declarationList.flags
                if (kind == SyntaxKind.LetKeyword || kind == SyntaxKind.ConstKeyword) {
                    for (d in stmt.declarationList.declarations) {
                        // Self-referencing initializers (let x = x)
                        val selfNames = mutableMapOf<String, Int>()
                        collectSelfRefNames(d.name, selfNames)
                        d.initializer?.let { checkUBDInInitializer(it, selfNames, source, fileName) }
                        checkUBDInBindingDefaults(d.name, selfNames, source, fileName)
                        // Forward refs to OTHER block-scoped decls (let a = x; let x;)
                        d.initializer?.let { checkUBDForwardInExpr(it, blockDecls, source, fileName) }
                    }
                } else {
                    // var declarations: check for forward refs to block-scoped decls
                    for (d in stmt.declarationList.declarations) {
                        d.initializer?.let { checkUBDForwardInExpr(it, blockDecls, source, fileName) }
                    }
                }
            }
            is ReturnStatement -> stmt.expression?.let { checkUBDForwardInExpr(it, blockDecls, source, fileName) }
            is ThrowStatement -> stmt.expression?.let { checkUBDForwardInExpr(it, blockDecls, source, fileName) }
            is IfStatement -> {
                checkUBDForwardInExpr(stmt.expression, blockDecls, source, fileName)
                checkUBDForwardRefs(stmt.thenStatement, blockDecls, source, fileName)
                stmt.elseStatement?.let { checkUBDForwardRefs(it, blockDecls, source, fileName) }
            }
            is WhileStatement -> {
                checkUBDForwardInExpr(stmt.expression, blockDecls, source, fileName)
                // Don't check body — it runs after condition, which may include the decl
            }
            is ClassDeclaration -> {
                // Check heritage (extends) clause for forward references
                stmt.heritageClauses?.forEach { clause ->
                    if (clause.token == SyntaxKind.ExtendsKeyword) {
                        for (typeExpr in clause.types) {
                            checkUBDForwardInExpr(typeExpr.expression, blockDecls, source, fileName)
                        }
                    }
                }
            }
            is SwitchStatement -> checkUBDForwardInExpr(stmt.expression, blockDecls, source, fileName)
            is LabeledStatement -> checkUBDForwardRefs(stmt.statement, blockDecls, source, fileName)
            else -> {}
        }
    }

    /** Check expression for forward references to block-scoped declarations */
    private fun checkUBDForwardInExpr(expr: Expression, blockDecls: Map<String, BlockScopedDecl>, source: String, fileName: String) {
        when (expr) {
            is Identifier -> {
                val decl = blockDecls[expr.text]
                if (decl != null && expr.pos < decl.pos) {
                    when {
                        decl.isEnum -> emitTS2450(expr, decl.pos, expr.text, source, fileName)
                        decl.isClass -> emitTS2449(expr, decl.pos, expr.text, source, fileName)
                        else -> emitTS2448(expr, decl.pos, expr.text, source, fileName)
                    }
                }
            }
            is BinaryExpression -> {
                checkUBDForwardInExpr(expr.left, blockDecls, source, fileName)
                checkUBDForwardInExpr(expr.right, blockDecls, source, fileName)
            }
            is PrefixUnaryExpression -> checkUBDForwardInExpr(expr.operand, blockDecls, source, fileName)
            is PostfixUnaryExpression -> checkUBDForwardInExpr(expr.operand, blockDecls, source, fileName)
            is ParenthesizedExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is ConditionalExpression -> {
                checkUBDForwardInExpr(expr.condition, blockDecls, source, fileName)
                checkUBDForwardInExpr(expr.whenTrue, blockDecls, source, fileName)
                checkUBDForwardInExpr(expr.whenFalse, blockDecls, source, fileName)
            }
            is CallExpression -> {
                checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
                for (arg in expr.arguments) checkUBDForwardInExpr(arg, blockDecls, source, fileName)
            }
            is PropertyAccessExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is ElementAccessExpression -> {
                checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
                checkUBDForwardInExpr(expr.argumentExpression, blockDecls, source, fileName)
            }
            is ArrayLiteralExpression -> for (el in expr.elements) checkUBDForwardInExpr(el, blockDecls, source, fileName)
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is PropertyAssignment -> checkUBDForwardInExpr(prop.initializer, blockDecls, source, fileName)
                    is SpreadAssignment -> checkUBDForwardInExpr(prop.expression, blockDecls, source, fileName)
                    else -> {}
                }
            }
            is TemplateExpression -> for (span in expr.templateSpans) {
                checkUBDForwardInExpr(span.expression, blockDecls, source, fileName)
            }
            is TypeOfExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is NonNullExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is SpreadElement -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is NewExpression -> {
                checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
                expr.arguments?.forEach { checkUBDForwardInExpr(it, blockDecls, source, fileName) }
            }
            is AsExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is TypeAssertionExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is VoidExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is DeleteExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            is AwaitExpression -> checkUBDForwardInExpr(expr.expression, blockDecls, source, fileName)
            // Don't recurse into functions/arrows (they capture lazily)
            is ArrowFunction, is FunctionExpression -> {}
            else -> {}
        }
    }

    private data class BlockScopedDecl(val pos: Int, val isEnum: Boolean = false, val isClass: Boolean = false)

    private fun collectBlockScopedDeclsEx(stmts: List<Statement>, source: String): MutableMap<String, BlockScopedDecl> {
        val decls = mutableMapOf<String, BlockScopedDecl>()
        for (stmt in stmts) collectBlockScopedDeclEx(stmt, decls, source)
        return decls
    }

    private fun collectBlockScopedDeclEx(stmt: Statement, decls: MutableMap<String, BlockScopedDecl>, source: String) {
        when (stmt) {
            is VariableStatement -> {
                val kind = stmt.declarationList.flags
                if (kind == SyntaxKind.LetKeyword || kind == SyntaxKind.ConstKeyword) {
                    for (d in stmt.declarationList.declarations) {
                        collectBindingNamesEx(d.name, decls)
                    }
                }
            }
            is EnumDeclaration -> {
                // const enum values are inlined at compile time — no temporal dead zone
                if (ModifierFlag.Const !in stmt.modifiers) {
                    decls[stmt.name.text] = BlockScopedDecl(stmt.name.pos, isEnum = true)
                }
            }
            is ClassDeclaration -> {
                val name = stmt.name
                if (name != null) {
                    decls[name.text] = BlockScopedDecl(name.pos, isClass = true)
                }
            }
            else -> {}
        }
    }

    /** Collect binding names into a simple name -> pos map for self-reference checks */
    private fun collectSelfRefNames(name: Node, decls: MutableMap<String, Int>) {
        when (name) {
            is Identifier -> decls[name.text] = name.pos
            is ObjectBindingPattern -> for (el in name.elements) {
                collectSelfRefNames(el.name, decls)
            }
            is ArrayBindingPattern -> for (el in name.elements) {
                if (el is BindingElement) collectSelfRefNames(el.name, decls)
            }
            else -> {}
        }
    }

    private fun collectBindingNamesEx(name: Node, decls: MutableMap<String, BlockScopedDecl>) {
        when (name) {
            is Identifier -> decls[name.text] = BlockScopedDecl(name.pos)
            is ObjectBindingPattern -> for (el in name.elements) {
                collectBindingNamesEx(el.name, decls)
            }
            is ArrayBindingPattern -> for (el in name.elements) {
                if (el is BindingElement) collectBindingNamesEx(el.name, decls)
            }
            else -> {}
        }
    }

    /** Recurse into nested block scopes (functions, classes, blocks) */
    private fun checkUBDInStatement(stmt: Statement, source: String, fileName: String, blockDecls: Map<String, BlockScopedDecl>) {
        when (stmt) {
            is VariableStatement -> {
                // Recurse into nested scopes in initializers
                for (d in stmt.declarationList.declarations) {
                    d.initializer?.let { checkUBDInExprForNested(it, source, fileName) }
                }
            }
            is ExpressionStatement -> checkUBDInExprForNested(stmt.expression, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkUBDInExprForNested(it, source, fileName) }
            is IfStatement -> {
                checkUBDInStatement(stmt.thenStatement, source, fileName, blockDecls)
                stmt.elseStatement?.let { checkUBDInStatement(it, source, fileName, blockDecls) }
            }
            is Block -> checkUBDInStatements(stmt.statements, source, fileName)
            is FunctionDeclaration -> stmt.body?.let { checkUBDInStatements(it.statements, source, fileName) }
            is ClassDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    is Constructor -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    is PropertyDeclaration -> m.initializer?.let { checkUBDInExprForNested(it, source, fileName) }
                    is GetAccessor -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    is SetAccessor -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    else -> {}
                }
            }
            is ForStatement -> {
                // Self-references in for-loop let/const initializers
                val init = stmt.initializer
                if (init is VariableDeclarationList) {
                    val kind = init.flags
                    if (kind == SyntaxKind.LetKeyword || kind == SyntaxKind.ConstKeyword) {
                        for (d in init.declarations) {
                            val selfNames = mutableMapOf<String, Int>()
                            collectSelfRefNames(d.name, selfNames)
                            d.initializer?.let { checkUBDInInitializer(it, selfNames, source, fileName) }
                        }
                    }
                }
                checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            }
            is ForInStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList) {
                    val kind = init.flags
                    if (kind == SyntaxKind.LetKeyword || kind == SyntaxKind.ConstKeyword) {
                        for (d in init.declarations) {
                            val selfNames = mutableMapOf<String, Int>()
                            collectSelfRefNames(d.name, selfNames)
                            checkUBDInInitializer(stmt.expression, selfNames, source, fileName)
                        }
                    }
                }
                checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            }
            is ForOfStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList) {
                    val kind = init.flags
                    if (kind == SyntaxKind.LetKeyword || kind == SyntaxKind.ConstKeyword) {
                        for (d in init.declarations) {
                            val selfNames = mutableMapOf<String, Int>()
                            collectSelfRefNames(d.name, selfNames)
                            checkUBDInInitializer(stmt.expression, selfNames, source, fileName)
                        }
                    }
                }
                checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            }
            is WhileStatement -> checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            is DoStatement -> checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            is SwitchStatement -> for (c in stmt.caseBlock) {
                val clauseStmts = when (c) { is CaseClause -> c.statements; is DefaultClause -> c.statements; else -> emptyList() }
                checkUBDInStatements(clauseStmts, source, fileName)
            }
            is TryStatement -> {
                checkUBDInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkUBDInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkUBDInStatements(it.statements, source, fileName) }
            }
            is LabeledStatement -> checkUBDInStatement(stmt.statement, source, fileName, blockDecls)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkUBDInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    /** Check defaults in destructuring binding patterns for self-references */
    private fun checkUBDInBindingDefaults(name: Node, selfNames: Map<String, Int>, source: String, fileName: String) {
        when (name) {
            is ObjectBindingPattern -> for (el in name.elements) {
                el.initializer?.let { checkUBDInInitializer(it, selfNames, source, fileName) }
                checkUBDInBindingDefaults(el.name, selfNames, source, fileName)
            }
            is ArrayBindingPattern -> for (el in name.elements) {
                if (el is BindingElement) {
                    el.initializer?.let { checkUBDInInitializer(it, selfNames, source, fileName) }
                    checkUBDInBindingDefaults(el.name, selfNames, source, fileName)
                }
            }
            else -> {}
        }
    }

    /** Check an initializer expression for references to the variable being declared */
    private fun checkUBDInInitializer(expr: Expression, selfNames: Map<String, Int>, source: String, fileName: String) {
        when (expr) {
            is Identifier -> {
                val declPos = selfNames[expr.text]
                if (declPos != null) {
                    emitTS2448(expr, declPos, expr.text, source, fileName)
                }
            }
            is BinaryExpression -> {
                checkUBDInInitializer(expr.left, selfNames, source, fileName)
                checkUBDInInitializer(expr.right, selfNames, source, fileName)
            }
            is PrefixUnaryExpression -> checkUBDInInitializer(expr.operand, selfNames, source, fileName)
            is PostfixUnaryExpression -> checkUBDInInitializer(expr.operand, selfNames, source, fileName)
            is ParenthesizedExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is ConditionalExpression -> {
                checkUBDInInitializer(expr.condition, selfNames, source, fileName)
                checkUBDInInitializer(expr.whenTrue, selfNames, source, fileName)
                checkUBDInInitializer(expr.whenFalse, selfNames, source, fileName)
            }
            is CallExpression -> {
                checkUBDInInitializer(expr.expression, selfNames, source, fileName)
                for (arg in expr.arguments) checkUBDInInitializer(arg, selfNames, source, fileName)
            }
            is NewExpression -> {
                checkUBDInInitializer(expr.expression, selfNames, source, fileName)
                expr.arguments?.forEach { checkUBDInInitializer(it, selfNames, source, fileName) }
            }
            is PropertyAccessExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is ElementAccessExpression -> {
                checkUBDInInitializer(expr.expression, selfNames, source, fileName)
                checkUBDInInitializer(expr.argumentExpression, selfNames, source, fileName)
            }
            is ArrayLiteralExpression -> for (el in expr.elements) checkUBDInInitializer(el, selfNames, source, fileName)
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is PropertyAssignment -> checkUBDInInitializer(prop.initializer, selfNames, source, fileName)
                    is ShorthandPropertyAssignment -> {
                        // Shorthand { x } where x is a self-ref
                        val declPos = selfNames[prop.name.text]
                        if (declPos != null) {
                            emitTS2448(prop.name, declPos, prop.name.text, source, fileName)
                        }
                        prop.objectAssignmentInitializer?.let { checkUBDInInitializer(it, selfNames, source, fileName) }
                    }
                    is SpreadAssignment -> checkUBDInInitializer(prop.expression, selfNames, source, fileName)
                    else -> {}
                }
            }
            is TemplateExpression -> for (span in expr.templateSpans) {
                checkUBDInInitializer(span.expression, selfNames, source, fileName)
            }
            is TypeOfExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is NonNullExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is SpreadElement -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is VoidExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is DeleteExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is AsExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is TypeAssertionExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            is SatisfiesExpression -> checkUBDInInitializer(expr.expression, selfNames, source, fileName)
            // Arrow functions, function expressions — DON'T recurse into bodies (lazy evaluation)
            is ArrowFunction, is FunctionExpression -> {}
            else -> {}
        }
    }

    /** Recurse into nested scopes (functions, classes) to find new block-scoped declarations */
    private fun checkUBDInExprForNested(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkUBDInStatements(body.statements, source, fileName)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkUBDInStatements(it.statements, source, fileName) }
            is ClassExpression -> for (m in expr.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    is Constructor -> m.body?.let { checkUBDInStatements(it.statements, source, fileName) }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun emitTS2448(useNode: Identifier, declPos: Int, name: String, source: String, fileName: String) {
        val start = useNode.pos
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        val (declLine, declChar) = getLineAndCharacterOfPosition(source, declPos)
        diagnostics.add(Diagnostic(
            message = "Block-scoped variable '$name' used before its declaration.",
            category = DiagnosticCategory.Error,
            code = 2448,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = listOf(Diagnostic(
                message = "'$name' is declared here.",
                category = DiagnosticCategory.Message,
                code = 2728,
                fileName = fileName,
                line = declLine,
                character = declChar,
                start = declPos,
                length = name.length,
            )),
        ))
    }

    private fun emitTS2450(useNode: Identifier, declPos: Int, name: String, source: String, fileName: String) {
        val start = useNode.pos
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        val (declLine, declChar) = getLineAndCharacterOfPosition(source, declPos)
        diagnostics.add(Diagnostic(
            message = "Enum '$name' used before its declaration.",
            category = DiagnosticCategory.Error,
            code = 2450,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = listOf(Diagnostic(
                message = "'$name' is declared here.",
                category = DiagnosticCategory.Message,
                code = 2728,
                fileName = fileName,
                line = declLine,
                character = declChar,
                start = declPos,
                length = name.length,
            )),
        ))
    }

    private fun emitTS2449(useNode: Identifier, declPos: Int, name: String, source: String, fileName: String) {
        val start = useNode.pos
        val length = name.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        val (declLine, declChar) = getLineAndCharacterOfPosition(source, declPos)
        diagnostics.add(Diagnostic(
            message = "Class '$name' used before its declaration.",
            category = DiagnosticCategory.Error,
            code = 2449,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = listOf(Diagnostic(
                message = "'$name' is declared here.",
                category = DiagnosticCategory.Message,
                code = 2728,
                fileName = fileName,
                line = declLine,
                character = declChar,
                start = declPos,
                length = name.length,
            )),
        ))
    }

    // -----------------------------------------------------------------------
    // Setter parameter count (TS1049)
    // -----------------------------------------------------------------------

    private fun checkSetterParameterCount() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkSetterInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkSetterInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkSetterInStatement(stmt, source, fileName)
    }

    private fun checkSetterInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    if (m is SetAccessor) {
                        checkSetterParams(m.name, m.parameters, source, fileName)
                    }
                    // Recurse into methods/constructors
                    when (m) {
                        is MethodDeclaration -> m.body?.let { checkSetterInStatements(it.statements, source, fileName) }
                        is Constructor -> m.body?.let { checkSetterInStatements(it.statements, source, fileName) }
                        is GetAccessor -> m.body?.let { checkSetterInStatements(it.statements, source, fileName) }
                        is SetAccessor -> m.body?.let { checkSetterInStatements(it.statements, source, fileName) }
                        else -> {}
                    }
                }
            }
            is ExpressionStatement -> checkSetterInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkSetterInExpr(it, source, fileName) }
            }
            is ReturnStatement -> stmt.expression?.let { checkSetterInExpr(it, source, fileName) }
            is FunctionDeclaration -> stmt.body?.let { checkSetterInStatements(it.statements, source, fileName) }
            is Block -> checkSetterInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkSetterInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkSetterInStatement(it, source, fileName) }
            }
            is ForStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is TryStatement -> {
                checkSetterInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkSetterInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkSetterInStatements(it.statements, source, fileName) }
            }
            is SwitchStatement -> for (c in stmt.caseBlock) {
                when (c) {
                    is CaseClause -> checkSetterInStatements(c.statements, source, fileName)
                    is DefaultClause -> checkSetterInStatements(c.statements, source, fileName)
                    else -> {}
                }
            }
            is LabeledStatement -> checkSetterInStatement(stmt.statement, source, fileName)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkSetterInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    private fun checkSetterInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                if (prop is SetAccessor) {
                    checkSetterParams(prop.name, prop.parameters, source, fileName)
                }
            }
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkSetterInStatements(body.statements, source, fileName)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkSetterInStatements(it.statements, source, fileName) }
            is ClassExpression -> {
                for (m in expr.members) {
                    if (m is SetAccessor) {
                        checkSetterParams(m.name, m.parameters, source, fileName)
                    }
                }
            }
            is ParenthesizedExpression -> checkSetterInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                checkSetterInExpr(expr.left, source, fileName)
                checkSetterInExpr(expr.right, source, fileName)
            }
            is ConditionalExpression -> {
                checkSetterInExpr(expr.whenTrue, source, fileName)
                checkSetterInExpr(expr.whenFalse, source, fileName)
            }
            is CallExpression -> {
                checkSetterInExpr(expr.expression, source, fileName)
                for (arg in expr.arguments) checkSetterInExpr(arg, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkSetterParams(name: Node, params: List<Parameter>, source: String, fileName: String) {
        // Count non-comment-placeholder params
        val realParams = params.count { !it.isCommentPlaceholder }
        if (realParams != 1) {
            val nameId = when (name) {
                is Identifier -> name
                is ComputedPropertyName -> null // skip computed names
                else -> null
            }
            val start = nameId?.pos ?: name.pos
            val length = if (nameId != null) nameId.text.length else (name.end - 1 - start).coerceAtLeast(1)
            val (line, character) = getLineAndCharacterOfPosition(source, start)
            diagnostics.add(Diagnostic(
                message = "A 'set' accessor must have exactly one parameter.",
                category = DiagnosticCategory.Error,
                code = 1049,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    // -----------------------------------------------------------------------
    // Duplicate modifiers (TS1030)
    // -----------------------------------------------------------------------

    private fun checkDuplicateModifiers() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkDupModInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkDupModInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkDupModInStatement(stmt, source, fileName)
    }

    private fun checkDupModInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is ClassDeclaration -> {
                checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
                for (m in stmt.members) {
                    when (m) {
                        is PropertyDeclaration -> checkModifiers(m.modifiers, source, fileName, m.pos)
                        is MethodDeclaration -> {
                            checkModifiers(m.modifiers, source, fileName, m.pos)
                            m.body?.let { checkDupModInStatements(it.statements, source, fileName) }
                        }
                        is Constructor -> m.body?.let { checkDupModInStatements(it.statements, source, fileName) }
                        is GetAccessor -> {
                            checkModifiers(m.modifiers, source, fileName, m.pos)
                            m.body?.let { checkDupModInStatements(it.statements, source, fileName) }
                        }
                        is SetAccessor -> {
                            checkModifiers(m.modifiers, source, fileName, m.pos)
                            m.body?.let { checkDupModInStatements(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            is FunctionDeclaration -> {
                checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
                stmt.body?.let { checkDupModInStatements(it.statements, source, fileName) }
            }
            is VariableStatement -> checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
            is InterfaceDeclaration -> {
                checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
                for (m in stmt.members) {
                    when (m) {
                        is PropertyDeclaration -> checkModifiers(m.modifiers, source, fileName, m.pos)
                        is MethodDeclaration -> checkModifiers(m.modifiers, source, fileName, m.pos)
                        else -> {}
                    }
                }
            }
            is EnumDeclaration -> checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
            is TypeAliasDeclaration -> checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
            is ModuleDeclaration -> {
                checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
                (stmt.body as? ModuleBlock)?.let { checkDupModInStatements(it.statements, source, fileName) }
            }
            is Block -> checkDupModInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkDupModInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkDupModInStatement(it, source, fileName) }
            }
            is ExportDeclaration -> checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
            is ImportDeclaration -> checkModifiers(stmt.modifiers, source, fileName, stmt.pos)
            else -> {}
        }
    }

    private fun checkModifiers(modifiers: Set<ModifierFlag>, source: String, fileName: String, stmtPos: Int) {
        if (modifiers.size <= 1) return
        // Check for duplicate modifiers by scanning the source text around the statement
        // Find the modifier keyword positions
        val modifierKeywords = listOf("export", "default", "declare", "abstract", "async",
            "static", "readonly", "public", "private", "protected", "override", "const", "accessor")
        // Scan from stmtPos to find modifier keywords
        var pos = stmtPos
        val seen = mutableSetOf<String>()
        val end = (stmtPos + 200).coerceAtMost(source.length) // scan up to 200 chars
        while (pos < end) {
            // Skip whitespace
            while (pos < end && source[pos].isWhitespace()) pos++
            if (pos >= end) break
            // Try to match a modifier keyword
            var matched = false
            for (kw in modifierKeywords) {
                if (pos + kw.length <= source.length &&
                    source.substring(pos, pos + kw.length) == kw &&
                    (pos + kw.length >= source.length || !source[pos + kw.length].isLetterOrDigit())) {
                    if (kw in seen) {
                        // Duplicate modifier
                        val (line, character) = getLineAndCharacterOfPosition(source, pos)
                        diagnostics.add(Diagnostic(
                            message = "'$kw' modifier already seen.",
                            category = DiagnosticCategory.Error,
                            code = 1030,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = pos,
                            length = kw.length,
                        ))
                    }
                    // Check ordering: visibility modifiers must precede static
                    if ((kw == "public" || kw == "private" || kw == "protected") && "static" in seen) {
                        val (line2, character2) = getLineAndCharacterOfPosition(source, pos)
                        diagnostics.add(Diagnostic(
                            message = "'$kw' modifier must precede 'static' modifier.",
                            category = DiagnosticCategory.Error,
                            code = 1029,
                            fileName = fileName,
                            line = line2,
                            character = character2,
                            start = pos,
                            length = kw.length,
                        ))
                    }
                    // export must precede default
                    if (kw == "export" && "default" in seen) {
                        val (line2, character2) = getLineAndCharacterOfPosition(source, pos)
                        diagnostics.add(Diagnostic(
                            message = "'export' modifier must precede 'default' modifier.",
                            category = DiagnosticCategory.Error,
                            code = 1029,
                            fileName = fileName,
                            line = line2,
                            character = character2,
                            start = pos,
                            length = kw.length,
                        ))
                    }
                    // export must precede declare
                    if (kw == "export" && "declare" in seen) {
                        val (line2, character2) = getLineAndCharacterOfPosition(source, pos)
                        diagnostics.add(Diagnostic(
                            message = "'export' modifier must precede 'declare' modifier.",
                            category = DiagnosticCategory.Error,
                            code = 1029,
                            fileName = fileName,
                            line = line2,
                            character = character2,
                            start = pos,
                            length = kw.length,
                        ))
                    }
                    seen.add(kw)
                    pos += kw.length
                    matched = true
                    break
                }
            }
            if (!matched) break // Hit a non-modifier token → done
        }
    }

    // -----------------------------------------------------------------------
    // Rest parameter must be last (TS1014)
    // -----------------------------------------------------------------------

    private fun checkRestParameterLast() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkRestLastInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkRestLastInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkRestLastInStatement(stmt, source, fileName)
    }

    private fun checkRestLastInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is FunctionDeclaration -> {
                checkRestLastInParams(stmt.parameters, source, fileName)
                stmt.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
            }
            is ClassDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> {
                        checkRestLastInParams(m.parameters, source, fileName)
                        m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    is Constructor -> {
                        checkRestLastInParams(m.parameters, source, fileName)
                        m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    is GetAccessor -> m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    is SetAccessor -> {
                        checkRestLastInParams(m.parameters, source, fileName)
                        m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    else -> {}
                }
            }
            is ExpressionStatement -> checkRestLastInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkRestLastInExpr(it, source, fileName) }
            }
            is ReturnStatement -> stmt.expression?.let { checkRestLastInExpr(it, source, fileName) }
            is Block -> checkRestLastInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkRestLastInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkRestLastInStatement(it, source, fileName) }
            }
            is ForStatement -> checkRestLastInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkRestLastInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkRestLastInStatement(stmt.statement, source, fileName)
            is TryStatement -> {
                checkRestLastInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkRestLastInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkRestLastInStatements(it.statements, source, fileName) }
            }
            is InterfaceDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> checkRestLastInParams(m.parameters, source, fileName)
                    else -> {}
                }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkRestLastInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    private fun checkRestLastInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                checkRestLastInParams(expr.parameters, source, fileName)
                when (val body = expr.body) {
                    is Block -> checkRestLastInStatements(body.statements, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkRestLastInParams(expr.parameters, source, fileName)
                expr.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
            }
            is ClassExpression -> for (m in expr.members) {
                when (m) {
                    is MethodDeclaration -> {
                        checkRestLastInParams(m.parameters, source, fileName)
                        m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    is Constructor -> {
                        checkRestLastInParams(m.parameters, source, fileName)
                        m.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    else -> {}
                }
            }
            is ParenthesizedExpression -> checkRestLastInExpr(expr.expression, source, fileName)
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is MethodDeclaration -> {
                        checkRestLastInParams(prop.parameters, source, fileName)
                        prop.body?.let { checkRestLastInStatements(it.statements, source, fileName) }
                    }
                    is SetAccessor -> checkRestLastInParams(prop.parameters, source, fileName)
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun checkRestLastInParams(params: List<Parameter>, source: String, fileName: String) {
        val realParams = params.filter { !it.isCommentPlaceholder }
        for (i in 0 until realParams.size - 1) {
            val param = realParams[i]
            if (param.dotDotDotToken) {
                val name = param.name
                // Span covers `...name`
                val start = if (name is Identifier) name.pos - 3 else param.pos
                val length = if (name is Identifier) 3 + name.text.length else (param.end - 1 - start).coerceAtLeast(1)
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "A rest parameter must be last in a parameter list.",
                    category = DiagnosticCategory.Error,
                    code = 1014,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Implementation in ambient context (TS1183)
    // -----------------------------------------------------------------------

    private fun checkAmbientImplementation() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkAmbientInStatements(result.sourceFile.statements, source, fileName, isAmbient = false)
        }
    }

    private fun checkAmbientInStatements(stmts: List<Statement>, source: String, fileName: String, isAmbient: Boolean) {
        for (stmt in stmts) checkAmbientInStatement(stmt, source, fileName, isAmbient)
    }

    private fun checkAmbientInStatement(stmt: Statement, source: String, fileName: String, isAmbient: Boolean) {
        val isDeclare = when (stmt) {
            is ClassDeclaration -> ModifierFlag.Declare in stmt.modifiers
            is FunctionDeclaration -> ModifierFlag.Declare in stmt.modifiers
            is ModuleDeclaration -> ModifierFlag.Declare in stmt.modifiers
            else -> false
        }
        val ambient = isAmbient || isDeclare

        when (stmt) {
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> {
                            if (ambient && m.body != null) {
                                emitTS1183(m.body!!, source, fileName)
                            }
                            if (!ambient) m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        }
                        is Constructor -> {
                            if (ambient && m.body != null) {
                                emitTS1183(m.body!!, source, fileName)
                            }
                            if (!ambient) m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        }
                        is GetAccessor -> {
                            if (ambient && m.body != null) {
                                emitTS1183(m.body!!, source, fileName)
                            }
                            if (!ambient) m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        }
                        is SetAccessor -> {
                            if (ambient && m.body != null) {
                                emitTS1183(m.body!!, source, fileName)
                            }
                            if (!ambient) m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        }
                        else -> {}
                    }
                }
            }
            is FunctionDeclaration -> {
                if (ambient && stmt.body != null) {
                    emitTS1183(stmt.body!!, source, fileName)
                }
                if (!ambient) stmt.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
            }
            is InterfaceDeclaration -> {
                // Interface members are always ambient
                for (m in stmt.members) {
                    when (m) {
                        is GetAccessor -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                        is SetAccessor -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                        is MethodDeclaration -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                        else -> {}
                    }
                }
            }
            is TypeAliasDeclaration -> {
                // Type literal members with bodies — walk the type
                checkAmbientInType(stmt.type, source, fileName)
            }
            is ModuleDeclaration -> {
                (stmt.body as? ModuleBlock)?.let { checkAmbientInStatements(it.statements, source, fileName, ambient) }
            }
            is Block -> checkAmbientInStatements(stmt.statements, source, fileName, isAmbient)
            is IfStatement -> {
                checkAmbientInStatement(stmt.thenStatement, source, fileName, isAmbient)
                stmt.elseStatement?.let { checkAmbientInStatement(it, source, fileName, isAmbient) }
            }
            is VariableStatement -> {
                for (d in stmt.declarationList.declarations) {
                    d.initializer?.let { checkAmbientInExpr(it, source, fileName) }
                }
            }
            is ExpressionStatement -> checkAmbientInExpr(stmt.expression, source, fileName)
            is ReturnStatement -> stmt.expression?.let { checkAmbientInExpr(it, source, fileName) }
            else -> {}
        }
    }

    private fun checkAmbientInType(type: TypeNode, source: String, fileName: String) {
        if (type is TypeLiteral) {
            for (m in type.members) {
                when (m) {
                    is GetAccessor -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                    is SetAccessor -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                    is MethodDeclaration -> if (m.body != null) emitTS1183(m.body!!, source, fileName)
                    else -> {}
                }
            }
        }
    }

    private fun checkAmbientInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ClassExpression -> {
                // Class expressions themselves are not ambient
                for (m in expr.members) {
                    when (m) {
                        is MethodDeclaration -> m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        is Constructor -> m.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
                        else -> {}
                    }
                }
            }
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkAmbientInStatements(body.statements, source, fileName, false)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkAmbientInStatements(it.statements, source, fileName, false) }
            else -> {}
        }
    }

    private fun emitTS1183(body: Block, source: String, fileName: String) {
        // Squiggle on the opening `{` only (1 char)
        val start = body.pos
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "An implementation cannot be declared in ambient contexts.",
            category = DiagnosticCategory.Error,
            code = 1183,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = 1,
        ))
    }

    // -----------------------------------------------------------------------
    // Arguments collision with rest parameters (TS2396)
    // -----------------------------------------------------------------------

    private fun checkArgumentsCollision() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkArgsCollisionInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkArgsCollisionInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkArgsCollisionInStatement(stmt, source, fileName)
    }

    private fun checkArgsCollisionInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is FunctionDeclaration -> {
                // Only check implementations (have body), skip overload signatures and declare
                if (stmt.body != null && ModifierFlag.Declare !in stmt.modifiers) {
                    checkArgsCollisionInParams(stmt.parameters, source, fileName)
                }
                stmt.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                val isDeclare = ModifierFlag.Declare in stmt.modifiers
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> {
                            if (!isDeclare && m.body != null) checkArgsCollisionInParams(m.parameters, source, fileName)
                            m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                        }
                        is Constructor -> {
                            if (!isDeclare && m.body != null) checkArgsCollisionInParams(m.parameters, source, fileName)
                            m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                        }
                        is GetAccessor -> m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                        is SetAccessor -> m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                        else -> {}
                    }
                }
            }
            is ExpressionStatement -> checkArgsCollisionInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkArgsCollisionInExpr(it, source, fileName) }
            }
            is ReturnStatement -> stmt.expression?.let { checkArgsCollisionInExpr(it, source, fileName) }
            is Block -> checkArgsCollisionInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkArgsCollisionInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkArgsCollisionInStatement(it, source, fileName) }
            }
            is ForStatement -> checkArgsCollisionInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkArgsCollisionInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkArgsCollisionInStatement(stmt.statement, source, fileName)
            is TryStatement -> {
                checkArgsCollisionInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkArgsCollisionInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare !in stmt.modifiers) {
                    (stmt.body as? ModuleBlock)?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                }
            }
            else -> {}
        }
    }

    private fun checkArgsCollisionInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> {
                checkArgsCollisionInParams(expr.parameters, source, fileName)
                when (val body = expr.body) {
                    is Block -> checkArgsCollisionInStatements(body.statements, source, fileName)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                checkArgsCollisionInParams(expr.parameters, source, fileName)
                expr.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
            }
            is ClassExpression -> for (m in expr.members) {
                when (m) {
                    is MethodDeclaration -> {
                        checkArgsCollisionInParams(m.parameters, source, fileName)
                        m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                    }
                    is Constructor -> {
                        checkArgsCollisionInParams(m.parameters, source, fileName)
                        m.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                    }
                    else -> {}
                }
            }
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is MethodDeclaration -> {
                        checkArgsCollisionInParams(prop.parameters, source, fileName)
                        prop.body?.let { checkArgsCollisionInStatements(it.statements, source, fileName) }
                    }
                    else -> {}
                }
            }
            is ParenthesizedExpression -> checkArgsCollisionInExpr(expr.expression, source, fileName)
            else -> {}
        }
    }

    private fun checkArgsCollisionInParams(params: List<Parameter>, source: String, fileName: String) {
        val hasRest = params.any { it.dotDotDotToken && !it.isCommentPlaceholder }
        if (!hasRest) return
        // Check each parameter for "arguments" name
        for (param in params) {
            if (param.isCommentPlaceholder) continue
            val name = param.name
            if (name is Identifier && name.text == "arguments") {
                // Span includes type annotation if present
                val start = if (param.dotDotDotToken) name.pos - 3 else name.pos
                val end = if (param.type != null) {
                    // end position is after the type text, minus trailing trivia
                    param.type!!.end - 1
                } else if (param.dotDotDotToken) {
                    name.pos + name.text.length
                } else {
                    name.pos + name.text.length
                }
                val length = (end - start).coerceAtLeast(1)
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "Duplicate identifier 'arguments'. Compiler uses 'arguments' to initialize rest parameters.",
                    category = DiagnosticCategory.Error,
                    code = 2396,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Initializers in ambient contexts (TS1039)
    // -----------------------------------------------------------------------

    private fun checkAmbientInitializers() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkAmbientInitInStatements(result.sourceFile.statements, source, fileName, isAmbient = false)
        }
    }

    private fun checkAmbientInitInStatements(stmts: List<Statement>, source: String, fileName: String, isAmbient: Boolean) {
        for (stmt in stmts) {
            val isDeclare = when (stmt) {
                is VariableStatement -> ModifierFlag.Declare in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Declare in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Declare in stmt.modifiers
                else -> false
            }
            val ambient = isAmbient || isDeclare
            when (stmt) {
                is VariableStatement -> {
                    if (ambient) {
                        for (d in stmt.declarationList.declarations) {
                            val init = d.initializer
                            if (init != null) {
                                val start = init.pos
                                val length = (init.end - 1 - start).coerceAtLeast(1)
                                val (line, character) = getLineAndCharacterOfPosition(source, start)
                                diagnostics.add(Diagnostic(
                                    message = "Initializers are not allowed in ambient contexts.",
                                    category = DiagnosticCategory.Error,
                                    code = 1039,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = start,
                                    length = length,
                                ))
                            }
                        }
                    }
                }
                is ClassDeclaration -> {
                    if (ambient) {
                        for (m in stmt.members) {
                            if (m is PropertyDeclaration && m.initializer != null) {
                                val init = m.initializer!!
                                val start = init.pos
                                val length = (init.end - 1 - start).coerceAtLeast(1)
                                val (line, character) = getLineAndCharacterOfPosition(source, start)
                                diagnostics.add(Diagnostic(
                                    message = "Initializers are not allowed in ambient contexts.",
                                    category = DiagnosticCategory.Error,
                                    code = 1039,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = start,
                                    length = length,
                                ))
                            }
                        }
                    }
                }
                is ModuleDeclaration -> {
                    (stmt.body as? ModuleBlock)?.let {
                        checkAmbientInitInStatements(it.statements, source, fileName, ambient)
                    }
                }
                is Block -> checkAmbientInitInStatements(stmt.statements, source, fileName, isAmbient)
                is FunctionDeclaration -> stmt.body?.let { checkAmbientInitInStatements(it.statements, source, fileName, false) }
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // Multiple defaults in switch (TS1113)
    // -----------------------------------------------------------------------

    private fun checkMultipleDefaults() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkMultiDefaultsInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkMultiDefaultsInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkMultiDefaultsInStatement(stmt, source, fileName)
    }

    private fun checkMultiDefaultsInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is SwitchStatement -> {
                var defaultSeen = false
                var errorEmitted = false
                for (c in stmt.caseBlock) {
                    if (c is DefaultClause) {
                        if (defaultSeen && !errorEmitted) {
                            val start = c.pos
                            val (line, character) = getLineAndCharacterOfPosition(source, start)
                            diagnostics.add(Diagnostic(
                                message = "A 'default' clause cannot appear more than once in a 'switch' statement.",
                                category = DiagnosticCategory.Error,
                                code = 1113,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = start,
                                length = 8, // "default:"
                            ))
                            errorEmitted = true
                        }
                        defaultSeen = true
                        checkMultiDefaultsInStatements(c.statements, source, fileName)
                    } else if (c is CaseClause) {
                        checkMultiDefaultsInStatements(c.statements, source, fileName)
                    }
                }
            }
            is Block -> checkMultiDefaultsInStatements(stmt.statements, source, fileName)
            is FunctionDeclaration -> stmt.body?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
            is ClassDeclaration -> for (m in stmt.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
                    is Constructor -> m.body?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
                    else -> {}
                }
            }
            is IfStatement -> {
                checkMultiDefaultsInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkMultiDefaultsInStatement(it, source, fileName) }
            }
            is ForStatement -> checkMultiDefaultsInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkMultiDefaultsInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkMultiDefaultsInStatement(stmt.statement, source, fileName)
            is TryStatement -> {
                checkMultiDefaultsInStatements(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.let { checkMultiDefaultsInStatements(it.block.statements, source, fileName) }
                stmt.finallyBlock?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
            }
            is ExpressionStatement -> checkMultiDefaultsInExpr(stmt.expression, source, fileName)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkMultiDefaultsInExpr(it, source, fileName) }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    private fun checkMultiDefaultsInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> when (val body = expr.body) {
                is Block -> checkMultiDefaultsInStatements(body.statements, source, fileName)
                else -> {}
            }
            is FunctionExpression -> expr.body?.let { checkMultiDefaultsInStatements(it.statements, source, fileName) }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Interface property initializers (TS1246)
    // -----------------------------------------------------------------------

    private fun checkInterfacePropertyInitializers() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                checkInterfacePropInit(stmt, source, fileName)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Await in non-async context (TS1308)
    // -----------------------------------------------------------------------

    private fun checkAwaitContext() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            // Top level of modules is OK for await
            val isModule = result.sourceFile.statements.any { stmt ->
                stmt is ImportDeclaration || stmt is ExportDeclaration ||
                (stmt is ExportAssignment) ||
                (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is ClassDeclaration && ModifierFlag.Export in stmt.modifiers)
            }
            checkAwaitInStatements(result.sourceFile.statements, source, fileName, isAsync = isModule)
        }
    }

    private fun checkAwaitInStatements(stmts: List<Statement>, source: String, fileName: String, isAsync: Boolean, enclosingFunc: Identifier? = null) {
        for (stmt in stmts) checkAwaitInStatement(stmt, source, fileName, isAsync, enclosingFunc)
    }

    private fun checkAwaitInStatement(stmt: Statement, source: String, fileName: String, isAsync: Boolean, enclosingFunc: Identifier? = null) {
        when (stmt) {
            is ExpressionStatement -> checkAwaitInExpr(stmt.expression, source, fileName, isAsync, enclosingFunc)
            is VariableStatement -> for (d in stmt.declarationList.declarations) {
                d.initializer?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            }
            is ReturnStatement -> stmt.expression?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            is FunctionDeclaration -> {
                val async = ModifierFlag.Async in stmt.modifiers
                val funcName = if (!async) stmt.name else null
                stmt.body?.let { checkAwaitInStatements(it.statements, source, fileName, async, funcName) }
            }
            is ClassDeclaration -> {
                for (m in stmt.members) {
                    when (m) {
                        is MethodDeclaration -> {
                            val async = ModifierFlag.Async in m.modifiers
                            val mName = if (!async) m.name as? Identifier else null
                            m.body?.let { checkAwaitInStatements(it.statements, source, fileName, async, mName) }
                        }
                        is Constructor -> m.body?.let { checkAwaitInStatements(it.statements, source, fileName, false, null) }
                        is PropertyDeclaration -> m.initializer?.let { checkAwaitInExpr(it, source, fileName, false, null) }
                        is GetAccessor -> m.body?.let { checkAwaitInStatements(it.statements, source, fileName, false, null) }
                        is SetAccessor -> m.body?.let { checkAwaitInStatements(it.statements, source, fileName, false, null) }
                        else -> {}
                    }
                }
            }
            is Block -> checkAwaitInStatements(stmt.statements, source, fileName, isAsync, enclosingFunc)
            is IfStatement -> {
                checkAwaitInExpr(stmt.expression, source, fileName, isAsync, enclosingFunc)
                checkAwaitInStatement(stmt.thenStatement, source, fileName, isAsync, enclosingFunc)
                stmt.elseStatement?.let { checkAwaitInStatement(it, source, fileName, isAsync, enclosingFunc) }
            }
            is ForStatement -> {
                stmt.condition?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
                stmt.incrementor?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
                checkAwaitInStatement(stmt.statement, source, fileName, isAsync, enclosingFunc)
            }
            is WhileStatement -> {
                checkAwaitInExpr(stmt.expression, source, fileName, isAsync, enclosingFunc)
                checkAwaitInStatement(stmt.statement, source, fileName, isAsync, enclosingFunc)
            }
            is DoStatement -> {
                checkAwaitInStatement(stmt.statement, source, fileName, isAsync, enclosingFunc)
                checkAwaitInExpr(stmt.expression, source, fileName, isAsync, enclosingFunc)
            }
            is TryStatement -> {
                checkAwaitInStatements(stmt.tryBlock.statements, source, fileName, isAsync, enclosingFunc)
                stmt.catchClause?.let { checkAwaitInStatements(it.block.statements, source, fileName, isAsync, enclosingFunc) }
                stmt.finallyBlock?.let { checkAwaitInStatements(it.statements, source, fileName, isAsync, enclosingFunc) }
            }
            is SwitchStatement -> {
                checkAwaitInExpr(stmt.expression, source, fileName, isAsync, enclosingFunc)
                for (c in stmt.caseBlock) {
                    when (c) {
                        is CaseClause -> {
                            checkAwaitInExpr(c.expression, source, fileName, isAsync, enclosingFunc)
                            checkAwaitInStatements(c.statements, source, fileName, isAsync, enclosingFunc)
                        }
                        is DefaultClause -> checkAwaitInStatements(c.statements, source, fileName, isAsync, enclosingFunc)
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> stmt.expression?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            is LabeledStatement -> checkAwaitInStatement(stmt.statement, source, fileName, isAsync, enclosingFunc)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkAwaitInStatements(it.statements, source, fileName, isAsync, enclosingFunc) }
            else -> {}
        }
    }

    private fun checkAwaitInExpr(expr: Expression, source: String, fileName: String, isAsync: Boolean, enclosingFunc: Identifier? = null) {
        when (expr) {
            is AwaitExpression -> {
                if (!isAsync) {
                    val start = expr.pos
                    val (line, character) = getLineAndCharacterOfPosition(source, start)
                    val relatedInfo = if (enclosingFunc != null) {
                        val (relLine, relChar) = getLineAndCharacterOfPosition(source, enclosingFunc.pos)
                        listOf(Diagnostic(
                            message = "Did you mean to mark this function as 'async'?",
                            category = DiagnosticCategory.Message,
                            code = 1356,
                            fileName = fileName,
                            line = relLine,
                            character = relChar,
                            start = enclosingFunc.pos,
                            length = enclosingFunc.text.length,
                        ))
                    } else emptyList()
                    diagnostics.add(Diagnostic(
                        message = "'await' expressions are only allowed within async functions and at the top levels of modules.",
                        category = DiagnosticCategory.Error,
                        code = 1308,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = 5, // "await"
                        relatedInformation = relatedInfo,
                    ))
                }
                expr.expression?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            }
            is ArrowFunction -> {
                val async = ModifierFlag.Async in expr.modifiers
                when (val body = expr.body) {
                    is Block -> checkAwaitInStatements(body.statements, source, fileName, async, null)
                    is Expression -> checkAwaitInExpr(body, source, fileName, async, null)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                val async = ModifierFlag.Async in expr.modifiers
                val fName = if (!async) expr.name else null
                expr.body?.let { checkAwaitInStatements(it.statements, source, fileName, async, fName) }
            }
            is ClassExpression -> {
                for (m in expr.members) {
                    when (m) {
                        is MethodDeclaration -> {
                            val async = ModifierFlag.Async in m.modifiers
                            val mName = if (!async) m.name as? Identifier else null
                            m.body?.let { checkAwaitInStatements(it.statements, source, fileName, async, mName) }
                        }
                        is Constructor -> m.body?.let { checkAwaitInStatements(it.statements, source, fileName, false, null) }
                        is PropertyDeclaration -> m.initializer?.let { checkAwaitInExpr(it, source, fileName, false, null) }
                        else -> {}
                    }
                }
            }
            is BinaryExpression -> {
                checkAwaitInExpr(expr.left, source, fileName, isAsync, enclosingFunc)
                checkAwaitInExpr(expr.right, source, fileName, isAsync, enclosingFunc)
            }
            is CallExpression -> {
                checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
                for (arg in expr.arguments) checkAwaitInExpr(arg, source, fileName, isAsync, enclosingFunc)
            }
            is NewExpression -> {
                checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
                expr.arguments?.forEach { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            }
            is ParenthesizedExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is ConditionalExpression -> {
                checkAwaitInExpr(expr.condition, source, fileName, isAsync, enclosingFunc)
                checkAwaitInExpr(expr.whenTrue, source, fileName, isAsync, enclosingFunc)
                checkAwaitInExpr(expr.whenFalse, source, fileName, isAsync, enclosingFunc)
            }
            is PropertyAccessExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is ElementAccessExpression -> {
                checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
                checkAwaitInExpr(expr.argumentExpression, source, fileName, isAsync, enclosingFunc)
            }
            is ArrayLiteralExpression -> for (el in expr.elements) checkAwaitInExpr(el, source, fileName, isAsync, enclosingFunc)
            is ObjectLiteralExpression -> for (prop in expr.properties) {
                when (prop) {
                    is PropertyAssignment -> checkAwaitInExpr(prop.initializer, source, fileName, isAsync, enclosingFunc)
                    is SpreadAssignment -> checkAwaitInExpr(prop.expression, source, fileName, isAsync, enclosingFunc)
                    is MethodDeclaration -> {
                        val async = ModifierFlag.Async in prop.modifiers
                        val pName = if (!async) prop.name as? Identifier else null
                        prop.body?.let { checkAwaitInStatements(it.statements, source, fileName, async, pName) }
                    }
                    else -> {}
                }
            }
            is TemplateExpression -> for (span in expr.templateSpans) {
                checkAwaitInExpr(span.expression, source, fileName, isAsync, enclosingFunc)
            }
            is PrefixUnaryExpression -> checkAwaitInExpr(expr.operand, source, fileName, isAsync, enclosingFunc)
            is PostfixUnaryExpression -> checkAwaitInExpr(expr.operand, source, fileName, isAsync, enclosingFunc)
            is SpreadElement -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is NonNullExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is AsExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is TypeAssertionExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is YieldExpression -> expr.expression?.let { checkAwaitInExpr(it, source, fileName, isAsync, enclosingFunc) }
            is TaggedTemplateExpression -> checkAwaitInExpr(expr.tag, source, fileName, isAsync, enclosingFunc)
            is VoidExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is DeleteExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            is TypeOfExpression -> checkAwaitInExpr(expr.expression, source, fileName, isAsync, enclosingFunc)
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // TS2397: Declaration name conflicts with built-in global identifier
    // -----------------------------------------------------------------------

    private fun checkBuiltinGlobalConflict() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkBuiltinGlobalInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkBuiltinGlobalInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        val name = decl.name
                        if (name is Identifier && name.text in BUILTIN_GLOBAL_CONFLICT_NAMES) {
                            reportBuiltinGlobalConflict(name, source, fileName)
                        }
                    }
                }
                is ModuleDeclaration -> {
                    val name = stmt.name
                    if (name is Identifier && name.text in BUILTIN_GLOBAL_CONFLICT_NAMES) {
                        reportBuiltinGlobalConflict(name, source, fileName)
                    }
                    (stmt.body as? ModuleBlock)?.let { checkBuiltinGlobalInStatements(it.statements, source, fileName) }
                }
                else -> {}
            }
        }
    }

    private fun reportBuiltinGlobalConflict(name: Identifier, source: String, fileName: String) {
        val start = name.pos
        val length = name.text.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Declaration name conflicts with built-in global identifier '${name.text}'.",
            category = DiagnosticCategory.Error,
            code = 2397,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // Interface property initializer checking (TS1246)
    // -----------------------------------------------------------------------

    private fun checkInterfacePropInit(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is InterfaceDeclaration -> {
                for (m in stmt.members) {
                    if (m is PropertyDeclaration && m.initializer != null) {
                        val init = m.initializer!!
                        val start = init.pos
                        val length = (init.end - 1 - start).coerceAtLeast(1)
                        val (line, character) = getLineAndCharacterOfPosition(source, start)
                        diagnostics.add(Diagnostic(
                            message = "An interface property cannot have an initializer.",
                            category = DiagnosticCategory.Error,
                            code = 1246,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = length,
                        ))
                    }
                }
            }
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let {
                for (s in it.statements) checkInterfacePropInit(s, source, fileName)
            }
            is Block -> for (s in stmt.statements) checkInterfacePropInit(s, source, fileName)
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // TS1015: Parameter cannot have question mark and initializer
    // -----------------------------------------------------------------------

    private fun checkOptionalParamWithInitializer() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForOptionalParams(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForOptionalParams(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) walkForOptionalParamsInStmt(stmt, source, fileName)
    }

    private fun walkForOptionalParamsInStmt(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is FunctionDeclaration -> {
                checkParamsForTS1015(stmt.parameters, source, fileName)
                stmt.body?.let { walkForOptionalParams(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        is Constructor -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        is GetAccessor -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        is SetAccessor -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    walkForOptionalParamsInExpr(decl.initializer, source, fileName)
                }
            }
            is ExpressionStatement -> walkForOptionalParamsInExpr(stmt.expression, source, fileName)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { walkForOptionalParams(it.statements, source, fileName) }
            is Block -> walkForOptionalParams(stmt.statements, source, fileName)
            is ReturnStatement -> walkForOptionalParamsInExpr(stmt.expression, source, fileName)
            is IfStatement -> {
                walkForOptionalParamsInExpr(stmt.expression, source, fileName)
                walkForOptionalParamsInStmt(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { walkForOptionalParamsInStmt(it, source, fileName) }
            }
            else -> {}
        }
    }

    private fun walkForOptionalParamsInExpr(expr: Expression?, source: String, fileName: String) {
        when (expr) {
            is ArrowFunction -> checkParamsForTS1015(expr.parameters, source, fileName, requireType = false)
            is FunctionExpression -> {
                checkParamsForTS1015(expr.parameters, source, fileName, requireType = false)
                walkForOptionalParams(expr.body.statements, source, fileName)
            }
            is ParenthesizedExpression -> walkForOptionalParamsInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                walkForOptionalParamsInExpr(expr.left, source, fileName)
                walkForOptionalParamsInExpr(expr.right, source, fileName)
            }
            is ConditionalExpression -> {
                walkForOptionalParamsInExpr(expr.condition, source, fileName)
                walkForOptionalParamsInExpr(expr.whenTrue, source, fileName)
                walkForOptionalParamsInExpr(expr.whenFalse, source, fileName)
            }
            is CallExpression -> {
                walkForOptionalParamsInExpr(expr.expression, source, fileName)
                for (arg in expr.arguments) walkForOptionalParamsInExpr(arg, source, fileName)
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is MethodDeclaration -> {
                            checkParamsForTS1015(prop.parameters, source, fileName)
                            prop.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        is PropertyAssignment -> walkForOptionalParamsInExpr(prop.initializer, source, fileName)
                        else -> {}
                    }
                }
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        is Constructor -> {
                            checkParamsForTS1015(member.parameters, source, fileName)
                            member.body?.let { walkForOptionalParams(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            else -> {}
        }
    }

    private fun checkParamsForTS1015(params: List<Parameter>, source: String, fileName: String, requireType: Boolean = true) {
        for (param in params) {
            if (param.questionToken && param.initializer != null) {
                // In function/method/constructor declarations, TS1015 only fires when type annotation is present.
                // In arrow/function expressions, it fires regardless.
                if (requireType && param.type == null) continue
                val name = param.name
                val start = name.pos
                val length = when (name) {
                    is Identifier -> name.text.length
                    else -> (name.end - 1 - start).coerceAtLeast(1)
                }
                val (line, character) = getLineAndCharacterOfPosition(source, start)
                diagnostics.add(Diagnostic(
                    message = "Parameter cannot have question mark and initializer.",
                    category = DiagnosticCategory.Error,
                    code = 1015,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS1052: A 'set' accessor parameter cannot have an initializer
    // -----------------------------------------------------------------------

    private fun checkSetAccessorInitializer() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForSetAccessorInit(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForSetAccessorInit(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            when (stmt) {
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        if (member is SetAccessor && member.parameters.any { it.initializer != null }) {
                            val name = member.name
                            val start = name.pos
                            val length = when (name) {
                                is Identifier -> name.text.length
                                else -> (name.end - 1 - start).coerceAtLeast(1)
                            }
                            val (line, character) = getLineAndCharacterOfPosition(source, start)
                            diagnostics.add(Diagnostic(
                                message = "A 'set' accessor parameter cannot have an initializer.",
                                category = DiagnosticCategory.Error,
                                code = 1052,
                                fileName = fileName,
                                line = line,
                                character = character,
                                start = start,
                                length = length,
                            ))
                        }
                    }
                }
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { walkForSetAccessorInit(it.statements, source, fileName) }
                is Block -> walkForSetAccessorInit(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS1036: Statements are not allowed in ambient contexts
    // -----------------------------------------------------------------------

    private fun checkAmbientStatements() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt is ModuleDeclaration && ModifierFlag.Declare in stmt.modifiers) {
                    val body = stmt.body
                    if (body is ModuleBlock) checkStatementsInAmbient(body.statements, source, fileName)
                }
            }
        }
    }

    private fun isValidAmbientStatement(stmt: Statement): Boolean = when (stmt) {
        is VariableStatement -> true
        is FunctionDeclaration -> true
        is ClassDeclaration -> true
        is InterfaceDeclaration -> true
        is TypeAliasDeclaration -> true
        is EnumDeclaration -> true
        is ModuleDeclaration -> true
        is ImportDeclaration -> true
        is ImportEqualsDeclaration -> true
        is ExportDeclaration -> true
        is ExportAssignment -> true
        else -> false
    }

    private fun checkStatementsInAmbient(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            if (!isValidAmbientStatement(stmt)) {
                // Report TS1036 — squiggle on the first token of the statement
                val start = stmt.pos
                val stmtEnd = stmt.end
                // Skip leading whitespace/trivia
                var spanStart = start
                while (spanStart < stmtEnd && spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                if (spanStart >= source.length) continue
                // Find the length of the first token (keyword or semicolon)
                var spanEnd = spanStart
                if (source[spanEnd] == ';') {
                    spanEnd++ // empty statement — just the semicolon
                } else {
                    while (spanEnd < source.length && source[spanEnd].isLetterOrDigit()) spanEnd++
                }
                val length = (spanEnd - spanStart).coerceAtLeast(1)
                val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
                diagnostics.add(Diagnostic(
                    message = "Statements are not allowed in ambient contexts.",
                    category = DiagnosticCategory.Error,
                    code = 1036,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = spanStart,
                    length = length,
                ))
            }
            // Recurse into nested namespaces (including non-declare ones inside declare)
            if (stmt is ModuleDeclaration) {
                val body = stmt.body
                if (body is ModuleBlock) checkStatementsInAmbient(body.statements, source, fileName)
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2371: A parameter initializer is only allowed in a function or
    //         constructor implementation
    // -----------------------------------------------------------------------

    private fun checkParameterInitializerInNonImpl() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForParamInitNonImpl(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForParamInitNonImpl(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            when (stmt) {
                is FunctionDeclaration -> {
                    if (stmt.body == null) {
                        reportTS2371ForParams(stmt.parameters, source, fileName)
                    }
                    stmt.body?.let { walkForParamInitNonImpl(it.statements, source, fileName) }
                }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> {
                                if (member.body == null) {
                                    reportTS2371ForParams(member.parameters, source, fileName)
                                }
                                member.body?.let { walkForParamInitNonImpl(it.statements, source, fileName) }
                            }
                            is Constructor -> {
                                if (member.body == null) {
                                    reportTS2371ForParams(member.parameters, source, fileName)
                                }
                                member.body?.let { walkForParamInitNonImpl(it.statements, source, fileName) }
                            }
                            else -> {}
                        }
                    }
                }
                is InterfaceDeclaration -> {
                    for (member in stmt.members) {
                        if (member is MethodDeclaration) {
                            reportTS2371ForParams(member.parameters, source, fileName)
                        }
                    }
                }
                is VariableStatement -> {
                    // Check function type annotations in variable declarations
                    // e.g., var f: (a = 3) => number
                    for (decl in stmt.declarationList.declarations) {
                        checkFunctionTypeParams(decl.type, source, fileName)
                        // Also check initializers for type assertions with function types
                        checkExprForFunctionTypeParams(decl.initializer, source, fileName)
                    }
                }
                is TypeAliasDeclaration -> {
                    checkFunctionTypeParams(stmt.type, source, fileName)
                }
                is ModuleDeclaration -> {
                    (stmt.body as? ModuleBlock)?.let { walkForParamInitNonImpl(it.statements, source, fileName) }
                }
                is Block -> walkForParamInitNonImpl(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    private fun checkFunctionTypeParams(type: TypeNode?, source: String, fileName: String) {
        when (type) {
            is FunctionType -> reportTS2371ForParams(type.parameters, source, fileName)
            is ParenthesizedType -> checkFunctionTypeParams(type.type, source, fileName)
            is UnionType -> for (t in type.types) checkFunctionTypeParams(t, source, fileName)
            is IntersectionType -> for (t in type.types) checkFunctionTypeParams(t, source, fileName)
            else -> {}
        }
    }

    private fun checkExprForFunctionTypeParams(expr: Expression?, source: String, fileName: String) {
        when (expr) {
            is TypeAssertionExpression -> checkFunctionTypeParams(expr.type, source, fileName)
            is AsExpression -> checkFunctionTypeParams(expr.type, source, fileName)
            is ParenthesizedExpression -> checkExprForFunctionTypeParams(expr.expression, source, fileName)
            else -> {}
        }
    }

    private fun reportTS2371ForParams(params: List<Parameter>, source: String, fileName: String) {
        for (param in params) {
            val init = param.initializer ?: continue
            val nameStart = param.name.pos
            // Span from name start to initializer end, trimming trailing trivia
            var spanEnd = init.end
            while (spanEnd > nameStart && spanEnd <= source.length &&
                source[spanEnd - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == ')' || it == ',' || it == ';' }) {
                spanEnd--
            }
            val length = (spanEnd - nameStart).coerceAtLeast(1)
            val (line, character) = getLineAndCharacterOfPosition(source, nameStart)
            diagnostics.add(Diagnostic(
                message = "A parameter initializer is only allowed in a function or constructor implementation.",
                category = DiagnosticCategory.Error,
                code = 2371,
                fileName = fileName,
                line = line,
                character = character,
                start = nameStart,
                length = length,
            ))
        }
    }

    // -----------------------------------------------------------------------
    // TS1212: Strict mode reserved word as identifier
    // -----------------------------------------------------------------------

    private fun checkStrictModeReservedWords() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            // Determine if file is in strict mode:
            // - target >= ES2015 (implicit strict)
            // - "use strict" prologue directive
            // - module file (ESM is strict)
            val isStrict = options.target >= ScriptTarget.ES2015 ||
                options.strict == true ||
                options.alwaysStrict == true ||
                result.sourceFile.statements.firstOrNull()?.let { stmt ->
                    stmt is ExpressionStatement && stmt.expression is StringLiteralNode &&
                        (stmt.expression as StringLiteralNode).text == "use strict"
                } == true
            if (!isStrict) continue
            walkForStrictReserved(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForStrictReserved(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) walkStmtForStrictReserved(stmt, source, fileName)
    }

    private fun walkStmtForStrictReserved(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    checkNodeForStrictReserved(decl.name, source, fileName)
                }
            }
            is FunctionDeclaration -> {
                val name = stmt.name
                if (name != null) checkIdentForStrictReserved(name, source, fileName)
                // Check parameters
                for (p in stmt.parameters) checkNodeForStrictReserved(p.name, source, fileName)
                stmt.body?.let { walkForStrictReserved(it.statements, source, fileName) }
            }
            is ClassDeclaration -> {
                val name = stmt.name
                if (name != null) checkIdentForStrictReserved(name, source, fileName)
                for (member in stmt.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            for (p in member.parameters) checkNodeForStrictReserved(p.name, source, fileName)
                            member.body?.let { walkForStrictReserved(it.statements, source, fileName) }
                        }
                        is Constructor -> {
                            for (p in member.parameters) checkNodeForStrictReserved(p.name, source, fileName)
                            member.body?.let { walkForStrictReserved(it.statements, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            is ModuleDeclaration -> {
                val name = stmt.name
                checkExprForStrictReservedIdents(name, source, fileName)
                val body = stmt.body
                when (body) {
                    is ModuleBlock -> walkForStrictReserved(body.statements, source, fileName)
                    is ModuleDeclaration -> walkStmtForStrictReserved(body, source, fileName)
                    else -> {}
                }
            }
            is ForInStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList) {
                    for (d in init.declarations) checkNodeForStrictReserved(d.name, source, fileName)
                }
                walkStmtForStrictReserved(stmt.statement, source, fileName)
            }
            is ForOfStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList) {
                    for (d in init.declarations) checkNodeForStrictReserved(d.name, source, fileName)
                }
                walkStmtForStrictReserved(stmt.statement, source, fileName)
            }
            is Block -> walkForStrictReserved(stmt.statements, source, fileName)
            is IfStatement -> {
                walkStmtForStrictReserved(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { walkStmtForStrictReserved(it, source, fileName) }
            }
            is ExpressionStatement -> {
                // Check for `let = 30;` style usage
                checkExprForStrictReserved(stmt.expression, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkNodeForStrictReserved(node: Node, source: String, fileName: String) {
        when (node) {
            is Identifier -> checkIdentForStrictReserved(node, source, fileName)
            is ObjectBindingPattern -> {
                for (el in node.elements) {
                    checkNodeForStrictReserved(el.name, source, fileName)
                }
            }
            is ArrayBindingPattern -> {
                for (el in node.elements) {
                    if (el is BindingElement) checkNodeForStrictReserved(el.name, source, fileName)
                }
            }
            else -> {}
        }
    }

    /** Check all identifiers in an expression tree for strict-mode reserved words */
    private fun checkExprForStrictReservedIdents(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is Identifier -> checkIdentForStrictReserved(expr, source, fileName)
            is PropertyAccessExpression -> {
                checkExprForStrictReservedIdents(expr.expression, source, fileName)
                checkIdentForStrictReserved(expr.name, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkExprForStrictReserved(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is Identifier -> {
                if (expr.text in STRICT_MODE_RESERVED_WORDS) {
                    // Only in assignment context (let = 30)
                    // Actually, all uses of reserved words as identifiers in expressions count
                    reportTS1212(expr, source, fileName)
                }
            }
            is BinaryExpression -> {
                checkExprForStrictReserved(expr.left, source, fileName)
            }
            else -> {}
        }
    }

    private fun checkIdentForStrictReserved(id: Identifier, source: String, fileName: String) {
        if (id.text in STRICT_MODE_RESERVED_WORDS) {
            reportTS1212(id, source, fileName)
        }
    }

    private fun reportTS1212(id: Identifier, source: String, fileName: String) {
        val start = id.pos
        val length = id.text.length
        val (line, character) = getLineAndCharacterOfPosition(source, start)
        diagnostics.add(Diagnostic(
            message = "Identifier expected. '${id.text}' is a reserved word in strict mode.",
            category = DiagnosticCategory.Error,
            code = 1212,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    // -----------------------------------------------------------------------
    // TS2414/TS2427: Class/Interface name cannot be 'undefined'
    // -----------------------------------------------------------------------

    private fun checkUndefinedClassInterfaceName() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkUndefinedNamesInStmts(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkUndefinedNamesInStmts(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            when (stmt) {
                is ClassDeclaration -> {
                    val name = stmt.name
                    if (name != null && name.text == "undefined") {
                        val start = name.pos
                        val (line, character) = getLineAndCharacterOfPosition(source, start)
                        diagnostics.add(Diagnostic(
                            message = "Class name cannot be 'undefined'.",
                            category = DiagnosticCategory.Error,
                            code = 2414,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = 9, // "undefined"
                        ))
                    }
                }
                is InterfaceDeclaration -> {
                    if (stmt.name.text == "undefined") {
                        val start = stmt.name.pos
                        val (line, character) = getLineAndCharacterOfPosition(source, start)
                        diagnostics.add(Diagnostic(
                            message = "Interface name cannot be 'undefined'.",
                            category = DiagnosticCategory.Error,
                            code = 2427,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = 9, // "undefined"
                        ))
                    }
                }
                is ModuleDeclaration -> {
                    (stmt.body as? ModuleBlock)?.let { checkUndefinedNamesInStmts(it.statements, source, fileName) }
                }
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2528: A module cannot have multiple default exports
    // -----------------------------------------------------------------------

    private fun checkMultipleDefaultExports() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text

            // Collect all export default statements
            data class DefaultExportInfo(val stmt: Statement, val start: Int, val length: Int)
            val defaults = mutableListOf<DefaultExportInfo>()

            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is ExportAssignment -> {
                        if (!stmt.isExportEquals) {
                            val start = stmt.pos
                            var spanStart = start
                            while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                            // Span ends at expression end (trimmed) + optional semicolon
                            val exprEnd = stmt.expression.end
                            var spanEnd = exprEnd
                            // Skip whitespace after expression
                            while (spanEnd < source.length && source[spanEnd].let { it == ' ' || it == '\t' }) spanEnd++
                            // Include semicolon if present
                            if (spanEnd < source.length && source[spanEnd] == ';') spanEnd++
                            val length = (spanEnd - spanStart).coerceAtLeast(1)
                            defaults.add(DefaultExportInfo(stmt, spanStart, length))
                        }
                    }
                    is FunctionDeclaration -> {
                        if (ModifierFlag.Export in stmt.modifiers && ModifierFlag.Default in stmt.modifiers) {
                            // Find "export" keyword before the function keyword
                            val funcPos = stmt.pos
                            val searchStart = (funcPos - 30).coerceAtLeast(0)
                            val exportIdx = source.lastIndexOf("export", funcPos)
                            val spanStart = if (exportIdx >= searchStart) exportIdx else funcPos
                            defaults.add(DefaultExportInfo(stmt, spanStart, 6))
                        }
                    }
                    is ClassDeclaration -> {
                        if (ModifierFlag.Export in stmt.modifiers && ModifierFlag.Default in stmt.modifiers) {
                            val classPos = stmt.pos
                            val searchStart = (classPos - 30).coerceAtLeast(0)
                            val exportIdx = source.lastIndexOf("export", classPos)
                            val spanStart = if (exportIdx >= searchStart) exportIdx else classPos
                            defaults.add(DefaultExportInfo(stmt, spanStart, 6))
                        }
                    }
                    else -> {}
                }
            }

            if (defaults.size < 2) continue

            // Report TS2528 on each default export with related info
            for (i in defaults.indices) {
                val d = defaults[i]
                val (line, character) = getLineAndCharacterOfPosition(source, d.start)

                // Related: point to the OTHER default export
                val relatedIdx = if (i == 0) 1 else 0
                val relD = defaults[relatedIdx]
                val (relLine, relChar) = getLineAndCharacterOfPosition(source, relD.start)
                val relCode = if (i == 0) 2752 else 2753
                val relMessage = if (i == 0) "The first export default is here." else "Another export default is here."

                diagnostics.add(Diagnostic(
                    message = "A module cannot have multiple default exports.",
                    category = DiagnosticCategory.Error,
                    code = 2528,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = d.start,
                    length = d.length,
                    relatedInformation = listOf(Diagnostic(
                        message = relMessage,
                        category = DiagnosticCategory.Message,
                        code = relCode,
                        fileName = fileName,
                        line = relLine,
                        character = relChar,
                        start = relD.start,
                        length = relD.length,
                    )),
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2377: Constructors for derived classes must contain a 'super' call
    // -----------------------------------------------------------------------

    private fun checkDerivedConstructorSuper() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            walkForDerivedSuper(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkForDerivedSuper(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) {
            when (stmt) {
                is ClassDeclaration -> {
                    checkClassDerivedSuper(stmt.heritageClauses, stmt.members, source, fileName)
                    // Recurse into class members for nested classes
                    for (m in stmt.members) {
                        when (m) {
                            is MethodDeclaration -> m.body?.let { walkForDerivedSuper(it.statements, source, fileName) }
                            is Constructor -> m.body?.let { walkForDerivedSuper(it.statements, source, fileName) }
                            else -> {}
                        }
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { walkForDerivedSuper(it.statements, source, fileName) }
                is VariableStatement -> {
                    for (d in stmt.declarationList.declarations) {
                        when (val init = d.initializer) {
                            is ClassExpression -> {
                                checkClassDerivedSuper(init.heritageClauses, init.members, source, fileName)
                            }
                            is FunctionExpression -> walkForDerivedSuper(init.body.statements, source, fileName)
                            else -> {}
                        }
                    }
                }
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { walkForDerivedSuper(it.statements, source, fileName) }
                is Block -> walkForDerivedSuper(stmt.statements, source, fileName)
                else -> {}
            }
        }
    }

    private fun checkClassDerivedSuper(
        heritageClauses: List<HeritageClause>?,
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        // Check if class extends something
        val hasExtends = heritageClauses?.any { it.token == SyntaxKind.ExtendsKeyword } == true
        if (!hasExtends) return

        // Find constructors with bodies that don't contain super()
        for (member in members) {
            if (member is Constructor && member.body != null) {
                val hasSuperCall = statementsContainSuperCall(member.body!!.statements)
                if (!hasSuperCall) {
                    // Find "constructor" keyword position
                    val ctorPos = member.pos
                    var spanStart = ctorPos
                    while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                    val idx = source.indexOf("constructor", spanStart)
                    if (idx < 0 || idx > spanStart + 20) continue
                    spanStart = idx
                    // Span covers just "constructor" keyword (11 chars)
                    val length = 11
                    val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
                    diagnostics.add(Diagnostic(
                        message = "Constructors for derived classes must contain a 'super' call.",
                        category = DiagnosticCategory.Error,
                        code = 2377,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = spanStart,
                        length = length,
                    ))
                }
            }
        }
    }

    private fun statementsContainSuperCall(stmts: List<Statement>): Boolean {
        for (stmt in stmts) {
            if (stmtContainsSuperCall(stmt)) return true
        }
        return false
    }

    private fun stmtContainsSuperCall(stmt: Statement): Boolean = when (stmt) {
        is ExpressionStatement -> exprContainsSuperCall(stmt.expression)
        is IfStatement -> {
            exprContainsSuperCall(stmt.expression) ||
                stmtContainsSuperCall(stmt.thenStatement) ||
                (stmt.elseStatement?.let { stmtContainsSuperCall(it) } == true)
        }
        is Block -> stmt.statements.any { stmtContainsSuperCall(it) }
        is ForStatement -> stmtContainsSuperCall(stmt.statement)
        is WhileStatement -> stmtContainsSuperCall(stmt.statement)
        is SwitchStatement -> stmt.caseBlock.any { clause ->
            when (clause) {
                is CaseClause -> clause.statements.any { stmtContainsSuperCall(it) }
                is DefaultClause -> clause.statements.any { stmtContainsSuperCall(it) }
                else -> false
            }
        }
        is TryStatement -> {
            stmt.tryBlock.statements.any { stmtContainsSuperCall(it) } ||
                (stmt.catchClause?.block?.statements?.any { stmtContainsSuperCall(it) } == true) ||
                (stmt.finallyBlock?.statements?.any { stmtContainsSuperCall(it) } == true)
        }
        is ReturnStatement -> stmt.expression?.let { exprContainsSuperCall(it) } == true
        is VariableStatement -> stmt.declarationList.declarations.any { d ->
            d.initializer?.let { exprContainsSuperCall(it) } == true
        }
        else -> false
    }

    private fun exprContainsSuperCall(expr: Expression): Boolean = when (expr) {
        is CallExpression -> {
            val callee = expr.expression
            (callee is Identifier && callee.text == "super") || exprContainsSuperCall(callee)
        }
        is BinaryExpression -> exprContainsSuperCall(expr.left) || exprContainsSuperCall(expr.right)
        is ParenthesizedExpression -> exprContainsSuperCall(expr.expression)
        is ConditionalExpression -> exprContainsSuperCall(expr.whenTrue) || exprContainsSuperCall(expr.whenFalse)
        else -> false
    }

    // -----------------------------------------------------------------------
    // TS2303: Circular definition of import alias
    // -----------------------------------------------------------------------

    private fun checkCircularImportAlias() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularAliasInStmts(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkCircularAliasInStmts(stmts: List<Statement>, source: String, fileName: String) {
        // Collect import= declarations with identifier references within this scope
        val importMap = mutableMapOf<String, ImportEqualsDeclaration>()
        for (stmt in stmts) {
            if (stmt is ImportEqualsDeclaration && stmt.moduleReference is Identifier) {
                importMap[stmt.name.text] = stmt
            }
        }

        // For each import, follow the chain to detect cycles
        val reported = mutableSetOf<String>()
        for ((name, decl) in importMap) {
            if (name in reported) continue
            val visited = mutableListOf(name)
            var current = (decl.moduleReference as Identifier).text
            while (current in importMap && current !in reported) {
                if (current in visited) {
                    // Cycle detected — report on the first import in the cycle
                    val cycleStart = visited.indexOf(current)
                    for (i in cycleStart until visited.size) {
                        val cycleName = visited[i]
                        val cycleDecl = importMap[cycleName] ?: continue
                        if (cycleName in reported) continue
                        reported.add(cycleName)
                        val start = cycleDecl.pos
                        var spanStart = start
                        while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                        // Find the semicolon at end of statement
                        val semiIdx = source.indexOf(';', spanStart)
                        val spanEnd = if (semiIdx in spanStart..cycleDecl.end) semiIdx + 1 else {
                            var e = cycleDecl.end
                            while (e > spanStart && e <= source.length && source[e - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) e--
                            e
                        }
                        val length = (spanEnd - spanStart).coerceAtLeast(1)
                        val (line, character) = getLineAndCharacterOfPosition(source, spanStart)
                        diagnostics.add(Diagnostic(
                            message = "Circular definition of import alias '$cycleName'.",
                            category = DiagnosticCategory.Error,
                            code = 2303,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = spanStart,
                            length = length,
                        ))
                        break // TypeScript only reports on the first import in the cycle
                    }
                    break
                }
                visited.add(current)
                val nextDecl = importMap[current] ?: break
                current = (nextDecl.moduleReference as Identifier).text
            }
        }

        // Recurse into namespaces
        for (stmt in stmts) {
            when (stmt) {
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                else -> {}
            }
        }
    }
}
