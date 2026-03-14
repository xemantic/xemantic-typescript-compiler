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
                checkUnusedInNestedScopes(stmt, source, fileName)
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
            checkUnusedInNestedScopes(stmt, source, fileName)
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
    private fun checkUnusedInNestedScopes(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is FunctionDeclaration -> {
                stmt.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, stmt.parameters, source, fileName,
                    )
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    checkUnusedInClassElement(member, source, fileName)
                }
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

    private fun checkUnusedInFunctionLike(
        bodyStatements: List<Statement>,
        parameters: List<Parameter>,
        source: String,
        fileName: String,
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

        // Check local declarations in the body
        checkUnusedInStatements(bodyStatements, source, fileName, isTopLevel = false)
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
                    if (decl.type != null && decl.initializer == null) {
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
                    is Expression -> findUninitializedRefs(init, uninitialized, source, fileName)
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let {
                                findUninitializedRefs(it, uninitialized, source, fileName)
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
                else -> {}
            }
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
}
