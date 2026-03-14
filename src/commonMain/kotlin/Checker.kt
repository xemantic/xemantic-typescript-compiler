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
                                val exportTarget = resolveModuleExportAssignment(targetResult)
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
    private fun resolveModuleExportAssignment(result: BinderResult): Symbol? {
        for (stmt in result.sourceFile.statements) {
            if (stmt is ExportAssignment && stmt.isExportEquals) {
                return resolveExpressionToSymbol(stmt.expression, result)
            }
        }
        return null
    }

    /**
     * Resolve an expression to a symbol (for export assignment resolution).
     */
    private fun resolveExpressionToSymbol(expr: Expression, result: BinderResult): Symbol? {
        return when (expr) {
            is Identifier -> {
                val symbol = result.locals[expr.text] ?: globals[expr.text] ?: return null
                resolveAlias(symbol)
            }
            is PropertyAccessExpression -> {
                val parent = resolveExpressionToSymbol(expr.expression, result) ?: return null
                val child = parent.exports?.get(expr.name.text) ?: return null
                resolveAlias(child)
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
    )

    private fun checkUnusedDeclarations() {
        for (result in binderResults) {
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

        // 1. Collect declarations
        for (stmt in statements) {
            collectUnusedDeclarations(stmt, scope, isTopLevel)
        }

        // 2. Collect references
        for (stmt in statements) {
            collectUnusedReferences(stmt, scope)
        }

        // 3. Report unreferenced declarations
        for (decl in scope.declarations) {
            if (decl.name in scope.referencedNames) continue
            if (decl.name.startsWith("_")) continue
            if (decl.isExported) continue

            if (decl.isParameter) {
                if (!options.noUnusedParameters) continue
            } else {
                if (!options.noUnusedLocals) continue
            }

            val nameNode = decl.nameNode
            val start = nameNode.pos
            // Compute squiggle length: for imports using whole-statement node,
            // measure the line text; otherwise use identifier text length
            val length = when {
                decl.spanLength > 0 -> decl.spanLength
                nameNode is ImportDeclaration -> {
                    // Squiggle covers the entire import statement line
                    val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                    lineEnd - start
                }
                else -> decl.name.length
            }
            val (line, character) = getLineAndCharacterOfPosition(source, start)

            // Classes use TS6196 "declared but never used", others use TS6133
            val isClassDecl = decl.declNode is ClassDeclaration
            val code = if (isClassDecl) 6196 else 6133
            val message = if (isClassDecl) {
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
    ) {
        when (stmt) {
            is VariableStatement -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                for (decl in stmt.declarationList.declarations) {
                    collectVarDeclNames(decl.name, decl, isExported, scope)
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
                ))
            }
            is ModuleDeclaration -> {
                // Namespaces/modules are not reported as unused — they always
                // produce runtime code (var + IIFE) and may be used from other files
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
                ))
            }
            is ObjectBindingPattern -> {
                for (element in name.elements) {
                    collectVarDeclNames(element.name, element, isExported, scope)
                }
            }
            is ArrayBindingPattern -> {
                for (element in name.elements) {
                    if (element is BindingElement) {
                        collectVarDeclNames(element.name, element, isExported, scope)
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
                stmt.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                for (param in stmt.parameters) {
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                }
                stmt.type?.let { collectRefsFromType(it, scope) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                    tp.default?.let { collectRefsFromType(it, scope) }
                }
            }
            is ClassDeclaration -> {
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, scope)
                        type.typeArguments?.forEach { collectRefsFromType(it, scope) }
                    }
                }
                stmt.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                for (member in stmt.members) {
                    collectRefsFromClassElement(member, scope)
                }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                    tp.default?.let { collectRefsFromType(it, scope) }
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
                    when (clause) {
                        is CaseClause -> clause.statements.forEach {
                            checkUnusedInNestedScopes(it, source, fileName)
                        }
                        is DefaultClause -> clause.statements.forEach {
                            checkUnusedInNestedScopes(it, source, fileName)
                        }
                        else -> {}
                    }
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
                )
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkUnusedInFunctionLike(
                        body.statements, expr.parameters, source, fileName,
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

        // Collect all property access names used in the class body
        val accessedNames = mutableSetOf<String>()
        for (member in members) {
            when (member) {
                is MethodDeclaration -> {
                    member.body?.let { collectPropertyAccessNames(it, accessedNames) }
                }
                is Constructor -> {
                    member.body?.let { collectPropertyAccessNames(it, accessedNames) }
                }
                is GetAccessor -> {
                    member.body?.let { collectPropertyAccessNames(it, accessedNames) }
                }
                is SetAccessor -> {
                    member.body?.let { collectPropertyAccessNames(it, accessedNames) }
                }
                is PropertyDeclaration -> {
                    member.initializer?.let { collectPropertyAccessNamesInExpr(it, accessedNames) }
                }
                else -> {}
            }
        }

        // Report unused private members
        for (pm in privateMembers) {
            if (pm.name in accessedNames) continue
            if (pm.name.startsWith("_")) continue
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
                collectPropertyAccessNamesInExpr(expr.left, names)
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
        if (typeParams.isNullOrEmpty() || !options.noUnusedLocals) return
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
        if (typeParams.isNullOrEmpty() || !options.noUnusedLocals) return
        // Skip if another declaration merges or if there are multiple interfaces with same name
        val ifaceName = iface.name.text
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
        if (typeParams.isNullOrEmpty() || !options.noUnusedLocals) return

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
            if (allUnused && scope.declarations.size == 1) {
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
                // Skip rest parameters and destructuring — they have complex rules
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
                }
            }
            // Collect references from body
            for (stmt in bodyStatements) {
                collectUnusedReferences(stmt, scope)
            }
            // Also collect references from parameter defaults and types
            for (param in parameters) {
                param.initializer?.let { collectRefsFromExpr(it, scope) }
            }
            // Report unused parameters
            for (decl in scope.declarations) {
                if (decl.name in scope.referencedNames) continue
                val nameNode = decl.nameNode
                val start = nameNode.pos
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

        // Check unused type parameters (TS6133)
        if (typeParameters != null && typeParameters.isNotEmpty() && options.noUnusedLocals) {
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
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
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
                    if (left is Identifier) {
                        uninitialized.remove(left.text)
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
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
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
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
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
            // Skip if parameter has type annotation, initializer, or rest token
            if (param.type != null) continue
            if (param.initializer != null) continue
            if (param.dotDotDotToken) continue
            // Skip `this` parameter
            val name = param.name
            if (name is Identifier && name.text == "this") continue
            // Skip destructured parameters (they get separate diagnostics)
            if (name !is Identifier) continue

            val start = name.pos
            val length = name.text.length
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
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
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
                    if (name is Identifier) scope.names.add(name.text)
                    else if (name is StringLiteralNode) scope.names.add(name.text)
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
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, classScope, source, fileName)
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
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, ifaceScope, source, fileName)
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
                            addParamsToScope(member.parameters, methodScope)
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            member.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                        }
                        else -> {}
                    }
                }
            }
            is TypeAliasDeclaration -> {
                val typeScope = scope.child()
                stmt.typeParameters?.forEach { typeScope.names.add(it.name.text) }
                checkUnresolvedInType(stmt.type, typeScope, source, fileName)
            }
            is EnumDeclaration -> {
                // Enum member initializers can reference other members
                for (member in stmt.members) {
                    member.initializer?.let { checkUnresolvedInExpr(it, scope, source, fileName) }
                }
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                when (val body = stmt.body) {
                    is ModuleBlock -> checkUnresolvedInStatements(body.statements, scope, source, fileName)
                    is ModuleDeclaration -> checkUnresolvedInStatement(body, scope, source, fileName)
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
                expr.typeParameters?.forEach { classScope.names.add(it.name.text) }
                expr.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        checkUnresolvedInExpr(type.expression, classScope, source, fileName)
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
                            addParamsToScope(member.parameters, methodScope)
                            for (param in member.parameters) {
                                param.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
                            }
                            member.type?.let { checkUnresolvedInType(it, methodScope, source, fileName) }
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
     * For QualifiedName, only check the leftmost identifier.
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
                // Only check the leftmost part of A.B.C
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
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
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
            }
            is TypeAliasDeclaration -> {
                checkDuplicateTypeParams(stmt.typeParameters, source, fileName)
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
            else -> {}
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
        data class DeclInfo(val name: String, val kind: String, val nameNode: Node)
        val decls = mutableListOf<DeclInfo>()

        for (stmt in statements) {
            when (stmt) {
                is VariableStatement -> {
                    for (decl in stmt.declarationList.declarations) {
                        val name = decl.name
                        if (name is Identifier) {
                            decls.add(DeclInfo(name.text, "var", name))
                        }
                    }
                }
                is FunctionDeclaration -> {
                    val name = stmt.name ?: continue
                    decls.add(DeclInfo(name.text, "function", name))
                }
                is ClassDeclaration -> {
                    val name = stmt.name ?: continue
                    decls.add(DeclInfo(name.text, "class", name))
                }
                is EnumDeclaration -> {
                    decls.add(DeclInfo(stmt.name.text, "enum", stmt.name))
                }
                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        decls.add(DeclInfo("export=", "export=", stmt.expression))
                    }
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

            val isDuplicate = (hasClass && classCount >= 2) ||
                    (hasClass && (hasFunc || hasEnum)) ||
                    (hasVar && (hasClass || hasFunc || hasEnum)) ||
                    (hasEnum && hasVar)

            if (isDuplicate) {
                for (decl in group) {
                    emitDuplicate2300(decl.name, decl.nameNode, source, fileName)
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
                if (name is Identifier) {
                    props.add(PropInfo(name.text, name))
                }
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
        data class MemberInfo(val name: String, val kind: String, val nameNode: Node)

        val memberInfos = mutableListOf<MemberInfo>()
        for (member in members) {
            when (member) {
                is MethodDeclaration -> {
                    val name = member.name
                    if (name is Identifier) memberInfos.add(MemberInfo(name.text, "method", name))
                }
                is PropertyDeclaration -> {
                    val name = member.name
                    if (name is Identifier) memberInfos.add(MemberInfo(name.text, "property", name))
                }
                is GetAccessor -> {
                    val name = member.name
                    if (name is Identifier) memberInfos.add(MemberInfo(name.text, "getter", name))
                }
                is SetAccessor -> {
                    val name = member.name
                    if (name is Identifier) memberInfos.add(MemberInfo(name.text, "setter", name))
                }
                else -> {}
            }
        }

        val byName = memberInfos.groupBy { it.name }
        for ((_, group) in byName) {
            if (group.size < 2) continue
            val kinds = group.map { it.kind }.toSet()
            // method + getter or method + setter → TS2300
            // method + property → TS2300
            // getter + setter → ALLOWED (no error)
            // property + property → TS2300 (unless overloads)
            val hasMethod = "method" in kinds
            val hasGetter = "getter" in kinds
            val hasSetter = "setter" in kinds
            val hasProperty = "property" in kinds

            val isDuplicate = (hasMethod && (hasGetter || hasSetter || hasProperty)) ||
                    (hasProperty && (hasGetter || hasSetter))

            if (isDuplicate) {
                for (info in group) {
                    emitDuplicate2300(info.name, info.nameNode, source, fileName)
                }
            }
        }
    }

    private fun emitDuplicate2300(
        name: String,
        node: Node,
        source: String,
        fileName: String,
        spanLength: Int = name.length,
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

    companion object {
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
            // TypeScript utility types (used in type positions)
            "Partial", "Required", "Readonly", "Record", "Pick", "Omit",
            "Exclude", "Extract", "NonNullable", "Parameters", "ConstructorParameters",
            "ReturnType", "InstanceType", "ThisType", "ThisParameterType",
            "OmitThisParameter", "Uppercase", "Lowercase", "Capitalize", "Uncapitalize",
            "Awaited", "NoInfer",
            "Iterable", "IterableIterator", "AsyncIterable", "AsyncIterableIterator",
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
        )
    }
}
