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

    init {
        // 1. Merge file-level symbols into globals
        for (result in binderResults) {
            mergeSymbolTable(globals, result.locals)
        }
        // 2. Compute all enum member values
        computeAllEnumValues()
        // 3. Track import references across all files
        trackAllImportReferences()
    }

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
                        if (ref is QualifiedName) {
                            val target = resolveQualifiedName(ref) ?: continue
                            symbol.target = target
                            return resolveAlias(target, visited)
                        } else if (ref is Identifier) {
                            val target = globals[ref.text] ?: continue
                            symbol.target = target
                            return resolveAlias(target, visited)
                        }
                    }
                    is ImportDeclaration -> {
                        // import { X } from "mod" — look up X in the target module
                        val specifier = (decl.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                        val targetFile = resolveModuleSpecifier(specifier, decl) ?: continue
                        val targetResult = fileResults[targetFile] ?: continue
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
    private fun resolveModuleSpecifier(specifier: String, contextNode: Node): String? {
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
}
