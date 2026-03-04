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

/**
 * Transforms a TypeScript AST into a JavaScript-compatible AST suitable for emission.
 *
 * Handles type erasure, class property-to-constructor movement, enum IIFE generation,
 * namespace IIFE generation, and module transforms.
 */
class Transformer(private val options: CompilerOptions) {

    // Modifiers that are TypeScript-only and should be stripped from class members
    private val typeOnlyMemberModifiers = setOf(
        ModifierFlag.Public,
        ModifierFlag.Private,
        ModifierFlag.Protected,
        ModifierFlag.Abstract,
        ModifierFlag.Readonly,
        ModifierFlag.Override,
    )

    // Modifiers that indicate constructor parameter properties
    private val parameterPropertyModifiers = setOf(
        ModifierFlag.Public,
        ModifierFlag.Private,
        ModifierFlag.Protected,
        ModifierFlag.Readonly,
    )

    /**
     * Whether to use `define` semantics for class fields (ES2022+ default).
     * When true, property declarations with initializers stay as class fields.
     * When false (default for older targets), they are moved into the constructor.
     */
    private val useDefineForClassFields: Boolean
        get() = options.useDefineForClassFields
            ?: (options.effectiveTarget >= ScriptTarget.ES2022)

    // -----------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------

    // Class/function names declared BEFORE the current statement (order-tracked).
    // Used so that a namespace whose class/function was declared before it can skip var N;
    private val declaredNames = mutableSetOf<String>()
    // Names that already have a var declaration emitted (by a prior enum or namespace IIFE).
    // Used so that a second enum/namespace with the same name doesn't emit var X; again.
    private val emittedVarNames = mutableSetOf<String>()

    fun transform(sourceFile: SourceFile): SourceFile {
        val transformed = transformStatements(sourceFile.statements)

        // CommonJS module transform
        if (options.effectiveModule == ModuleKind.CommonJS && isModuleFile(sourceFile)) {
            val cjsStatements = transformToCommonJS(transformed, sourceFile)
            return sourceFile.copy(statements = cjsStatements)
        }

        return sourceFile.copy(statements = transformed)
    }

    private fun isModuleFile(
        sourceFile: SourceFile
    ) = sourceFile.statements.any { stmt ->
        stmt is ImportDeclaration || stmt is ExportDeclaration ||
                (stmt is ImportEqualsDeclaration && stmt.moduleReference is ExternalModuleReference) || stmt is ExportAssignment ||
                (stmt is Declaration && ModifierFlag.Export in (stmt as? ClassDeclaration)?.modifiers.orEmpty()) ||
                (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is ClassDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers)
    }

    // -----------------------------------------------------------------
    // CommonJS module transform
    // -----------------------------------------------------------------

    private fun transformToCommonJS(
        statements: List<Statement>,
        originalSourceFile: SourceFile,
    ): List<Statement> {
        val result = mutableListOf<Statement>()

        // Files using `export = X` style (namespace export) don't get __esModule preamble.
        // They use `module.exports = X` directly and are incompatible with ES module syntax.
        val hasExportEquals = originalSourceFile.statements.any { it is ExportAssignment && it.isExportEquals }

        // Add Object.defineProperty(exports, "__esModule", { value: true });
        if (!hasExportEquals) {
            result.add(makeEsModulePreamble())
        }

        // Collect exported names for hoisting (exports.x = void 0)
        val exportedVarNames = mutableListOf<String>()
        // Collect export assignments to emit at the end
        val deferredExportAssignments = mutableListOf<Statement>()

        for (stmt in statements) {
            when (stmt) {
                // import x = require("y") → already transformed to var x = require("y")
                // but it needs to use 'const' not 'var' for CommonJS
                is VariableStatement -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val strippedModifiers = stmt.modifiers - ModifierFlag.Export

                    if (isExported) {
                        // Check if it's a require() call (from import = require transform)
                        val isRequire = stmt.declarationList.declarations.size == 1 &&
                                stmt.declarationList.declarations[0].initializer is CallExpression &&
                                ((stmt.declarationList.declarations[0].initializer as? CallExpression)
                                    ?.expression as? Identifier)?.text == "require"

                        if (isRequire) {
                            // const x = require("y") — keep as-is but remove export modifier
                            result.add(stmt.copy(modifiers = strippedModifiers))
                        } else {
                            // Exported variable: hoist exports.x = void 0, emit var, then exports.x = x
                            var hasInitializer = false
                            for (decl in stmt.declarationList.declarations) {
                                val name = extractIdentifierName(decl.name)
                                if (name != null) {
                                    exportedVarNames.add(name)
                                    if (decl.initializer != null) {
                                        hasInitializer = true
                                        deferredExportAssignments.add(makeExportAssignment(name))
                                    }
                                }
                            }
                            // Only emit the var declaration if at least one var has an initializer;
                            // a declaration without initializer is fully represented by exports.x = void 0
                            if (hasInitializer) {
                                result.add(stmt.copy(modifiers = strippedModifiers))
                            }
                        }
                    } else {
                        result.add(stmt)
                    }
                }

                is FunctionDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers

                    if (isExported) {
                        val strippedModifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default
                        val emitted = stmt.copy(modifiers = strippedModifiers)
                        if (isDefault) {
                            result.add(emitted)
                            val name = stmt.name?.text
                            if (name != null) {
                                result.add(makeExportAssignment("default", syntheticId(name)))
                            }
                        } else {
                            val name = stmt.name?.text
                            if (name != null) {
                                // exports.funcName = funcName before the declaration
                                result.add(makeExportsProperty(name))
                            }
                            result.add(emitted)
                        }
                    } else {
                        result.add(stmt)
                    }
                }

                is ClassDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers

                    if (isExported) {
                        val strippedModifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default
                        val emitted = stmt.copy(modifiers = strippedModifiers)
                        result.add(emitted)
                        val name = stmt.name?.text
                        if (name != null) {
                            if (isDefault) {
                                result.add(makeExportAssignment("default", syntheticId(name)))
                            } else {
                                // Hoist exports.ClassName = void 0 and defer exports.ClassName = ClassName
                                exportedVarNames.add(name)
                                deferredExportAssignments.add(makeExportAssignment(name))
                            }
                        }
                    } else {
                        result.add(stmt)
                    }
                }

                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        // export = expr → module.exports = expr;
                        result.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = syntheticId("module"),
                                        name = syntheticId("exports"),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = stmt.expression,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        )
                    } else {
                        // export default expr → exports.default = expr;
                        result.add(makeExportAssignment("default", stmt.expression))
                    }
                }

                is ImportDeclaration -> {
                    // Transform ES imports to require calls
                    val clause = stmt.importClause
                    val moduleSpecifier = stmt.moduleSpecifier

                    if (clause == null) {
                        // Side-effect import: require("mod")
                        result.add(
                            ExpressionStatement(
                                expression = CallExpression(
                                    expression = syntheticId("require"),
                                    arguments = listOf(moduleSpecifier),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                leadingComments = stmt.leadingComments,
                            )
                        )
                    } else {
                        val bindings = clause.namedBindings
                        if (clause.name != null && bindings == null) {
                            // Default import: const x = require("y")
                            // With esModuleInterop: const x_1 = __importDefault(require("y"))
                            result.add(makeRequireConst(clause.name.text, moduleSpecifier, stmt.leadingComments))
                        } else if (bindings is NamespaceImport) {
                            // import * as x from "y" → const x = require("y")
                            result.add(makeRequireConst(bindings.name.text, moduleSpecifier, stmt.leadingComments))
                        } else if (bindings is NamedImports) {
                            // import { a, b } from "y" → const y_1 = require("y")
                            val tempName = generateModuleTempName(moduleSpecifier)
                            result.add(makeRequireConst(tempName, moduleSpecifier, stmt.leadingComments))
                        } else if (clause.name != null && bindings != null) {
                            // Combined default + named: complex case
                            result.add(makeRequireConst(clause.name.text, moduleSpecifier, stmt.leadingComments))
                        }
                    }
                }

                is ExportDeclaration -> {
                    if (stmt.moduleSpecifier != null) {
                        // Re-export: export { x } from "y" — complex, skip for now
                        result.add(stmt)
                    } else if (stmt.exportClause is NamedExports) {
                        // export { x, y } — emit exports.x = x, exports.y = y
                        for (spec in (stmt.exportClause as NamedExports).elements) {
                            val exportName = spec.propertyName?.text ?: spec.name.text
                            val localName = spec.name.text
                            result.add(makeExportAssignment(exportName, syntheticId(localName)))
                        }
                    }
                }

                else -> result.add(stmt)
            }
        }

        // Emit hoisted exports.x = void 0
        val hoistedExports = exportedVarNames.map { name ->
            ExpressionStatement(
                expression = BinaryExpression(
                    left = PropertyAccessExpression(
                        expression = syntheticId("exports"),
                        name = syntheticId(name),
                        pos = -1, end = -1,
                    ),
                    operator = SyntaxKind.Equals,
                    right = syntheticId("void 0"),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        }

        // Insert hoisted exports after the __esModule preamble
        if (hoistedExports.isNotEmpty()) {
            result.addAll(1, hoistedExports)
        }

        // Append deferred export assignments
        result.addAll(deferredExportAssignments)

        return result
    }

    private fun makeEsModulePreamble(): Statement {
        return ExpressionStatement(
            expression = CallExpression(
                expression = PropertyAccessExpression(
                    expression = syntheticId("Object"),
                    name = syntheticId("defineProperty"),
                    pos = -1, end = -1,
                ),
                arguments = listOf(
                    syntheticId("exports"),
                    StringLiteralNode(text = "__esModule", pos = -1, end = -1),
                    ObjectLiteralExpression(
                        properties = listOf(
                            PropertyAssignment(
                                name = syntheticId("value"),
                                initializer = syntheticId("true"),
                                pos = -1, end = -1,
                            )
                        ),
                        multiLine = false,
                        pos = -1, end = -1,
                    ),
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    private fun makeExportAssignment(name: String, value: Expression? = null): Statement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ),
                operator = SyntaxKind.Equals,
                right = value ?: syntheticId(name),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    private fun makeExportsProperty(name: String): Statement {
        // Emits: exports.name = name;
        return makeExportAssignment(name, syntheticId(name))
    }

    private fun makeRequireConst(
        name: String,
        moduleSpecifier: Expression,
        comments: List<Comment>? = null
    ): Statement {
        return VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = Identifier(text = name, pos = -1, end = -1),
                        initializer = CallExpression(
                            expression = syntheticId("require"),
                            arguments = listOf(moduleSpecifier),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                ),
                flags = SyntaxKind.ConstKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            leadingComments = comments,
        )
    }

    private fun generateModuleTempName(moduleSpecifier: Expression): String {
        val specText = (moduleSpecifier as? StringLiteralNode)?.text ?: "module"
        val baseName = specText.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_]"), "_")
        return "${baseName}_1"
    }

    // -----------------------------------------------------------------
    // Statement transforms
    // -----------------------------------------------------------------

    private fun transformStatements(statements: List<Statement>): List<Statement> {
        val result = mutableListOf<Statement>()
        for (stmt in statements) {
            result.addAll(transformStatement(stmt))
            // Track class/function names in declaration order.
            // This allows namespace/module IIFEs to skip var N; when a class or
            // function with the same name was declared BEFORE the namespace.
            if (stmt is Declaration && !hasDeclareModifier(stmt)) {
                when (stmt) {
                    is ClassDeclaration -> stmt.name?.text?.let { declaredNames.add(it) }
                    is FunctionDeclaration -> stmt.name?.text?.let { declaredNames.add(it) }
                    else -> {}
                }
            }
        }
        return result
    }

    /**
     * Transforms a single statement, potentially producing 0, 1, or many output statements.
     */
    private fun transformStatement(statement: Statement): List<Statement> {
        // Declarations with `declare` modifier produce no output
        if (statement is Declaration && hasDeclareModifier(statement)) {
            return orphanedComments(statement)
        }

        return when (statement) {
            // --- Type erasure: remove type-only declarations ---
            is InterfaceDeclaration -> orphanedComments(statement)
            is TypeAliasDeclaration -> orphanedComments(statement)

            // --- Enum transform ---
            is EnumDeclaration -> transformEnum(statement)

            // --- Namespace / module declaration transform ---
            is ModuleDeclaration -> transformModuleDeclaration(statement)

            // --- Class transform ---
            is ClassDeclaration -> transformClassDeclaration(statement)

            // --- Function declaration ---
            is FunctionDeclaration -> transformFunctionDeclaration(statement)

            // --- Variable statement ---
            is VariableStatement -> transformVariableStatement(statement)

            // --- Expression statement ---
            is ExpressionStatement -> listOf(
                statement.copy(expression = transformExpression(statement.expression))
            )

            // --- Import / Export ---
            is ImportDeclaration -> transformImportDeclaration(statement)
            is ImportEqualsDeclaration -> transformImportEqualsDeclaration(statement)
            is ExportDeclaration -> transformExportDeclaration(statement)
            is ExportAssignment -> listOf(
                statement.copy(expression = transformExpression(statement.expression))
            )

            // --- Control flow: recurse into children ---
            is Block -> listOf(statement.copy(statements = transformStatements(statement.statements)))
            is IfStatement -> listOf(transformIfStatement(statement))
            is DoStatement -> listOf(
                statement.copy(
                    statement = transformStatementSingle(statement.statement),
                    expression = transformExpression(statement.expression),
                )
            )

            is WhileStatement -> listOf(
                statement.copy(
                    expression = transformExpression(statement.expression),
                    statement = transformStatementSingle(statement.statement),
                )
            )

            is ForStatement -> listOf(transformForStatement(statement))
            is ForInStatement -> listOf(transformForInStatement(statement))
            is ForOfStatement -> listOf(transformForOfStatement(statement))
            is ReturnStatement -> listOf(
                statement.copy(expression = statement.expression?.let { transformExpression(it) })
            )

            is SwitchStatement -> listOf(transformSwitchStatement(statement))
            is LabeledStatement -> listOf(
                statement.copy(statement = transformStatementSingle(statement.statement))
            )

            is ThrowStatement -> listOf(
                statement.copy(expression = transformExpression(statement.expression))
            )

            is TryStatement -> listOf(transformTryStatement(statement))
            is WithStatement -> listOf(
                statement.copy(
                    expression = transformExpression(statement.expression),
                    statement = transformStatementSingle(statement.statement),
                )
            )

            // --- VariableDeclaration as top-level statement (rare but possible) ---
            is VariableDeclaration -> listOf(transformVariableDeclaration(statement))

            // --- Leaf / pass-through statements ---
            is EmptyStatement -> listOf(statement)
            is BreakStatement -> listOf(statement)
            is ContinueStatement -> listOf(statement)
            is DebuggerStatement -> listOf(statement)
            is NotEmittedStatement -> listOf(statement)
        }
    }

    /**
     * Transforms a statement that must produce exactly one statement (e.g. body of while/for).
     * If the input is a block-like compound, it stays as one statement.
     */
    private fun transformStatementSingle(statement: Statement): Statement {
        val result = transformStatement(statement)
        return when {
            result.size == 1 -> result.first()
            result.isEmpty() -> EmptyStatement(pos = -1, end = -1)
            else -> Block(statements = result, pos = -1, end = -1)
        }
    }

    // -----------------------------------------------------------------
    // Declare modifier detection
    // -----------------------------------------------------------------

    private fun hasDeclareModifier(
        statement: Declaration
    ) = when (statement) {
        is FunctionDeclaration -> ModifierFlag.Declare in statement.modifiers
        is ClassDeclaration -> ModifierFlag.Declare in statement.modifiers
        is InterfaceDeclaration -> ModifierFlag.Declare in statement.modifiers
        is TypeAliasDeclaration -> ModifierFlag.Declare in statement.modifiers
        is EnumDeclaration -> ModifierFlag.Declare in statement.modifiers
        is ModuleDeclaration -> ModifierFlag.Declare in statement.modifiers
        is VariableDeclaration -> false // handled via VariableStatement
        is ImportDeclaration -> false
        is ImportEqualsDeclaration -> false
        is ExportDeclaration -> false
        is ExportAssignment -> false
    }

    // -----------------------------------------------------------------
    // Function declaration transform
    // -----------------------------------------------------------------

    private fun transformFunctionDeclaration(
        decl: FunctionDeclaration
    ): List<Statement> {
        // Overload signatures (no body, not declare) are erased
        if (decl.body == null) return emptyList()

        val strippedModifiers = stripTypeScriptModifiers(decl.modifiers)
        return listOf(
            decl.copy(
                typeParameters = null,
                parameters = transformParameters(decl.parameters),
                type = null,
                body = transformBlock(decl.body),
                modifiers = strippedModifiers,
            )
        )
    }

    // -----------------------------------------------------------------
    // Variable statement transform
    // -----------------------------------------------------------------

    private fun transformVariableStatement(stmt: VariableStatement): List<Statement> {
        // Declare modifier on the variable statement
        if (ModifierFlag.Declare in stmt.modifiers) return emptyList()

        val transformedList = transformVariableDeclarationList(stmt.declarationList)
        val strippedModifiers = stripTypeScriptModifiers(stmt.modifiers)
        return listOf(
            stmt.copy(
                declarationList = transformedList,
                modifiers = strippedModifiers,
            )
        )
    }

    private fun transformVariableDeclarationList(
        list: VariableDeclarationList
    ): VariableDeclarationList {
        return list.copy(
            declarations = list.declarations.map { transformVariableDeclaration(it) }
        )
    }

    private fun transformVariableDeclaration(decl: VariableDeclaration): VariableDeclaration {
        return decl.copy(
            name = transformBindingName(decl.name),
            type = null,
            initializer = decl.initializer?.let { transformExpression(it) },
            exclamationToken = false,
        )
    }

    private fun transformBindingName(name: Expression): Expression {
        return when (name) {
            is ObjectBindingPattern -> name.copy(
                elements = name.elements.map { transformBindingElement(it) }
            )

            is ArrayBindingPattern -> name.copy(
                elements = name.elements.map { node ->
                    when (node) {
                        is BindingElement -> transformBindingElement(node)
                        else -> node
                    }
                }
            )

            else -> transformExpression(name)
        }
    }

    private fun transformBindingElement(element: BindingElement): BindingElement {
        return element.copy(
            name = transformBindingName(element.name),
            initializer = element.initializer?.let { transformExpression(it) },
        )
    }

    // -----------------------------------------------------------------
    // Expression transforms
    // -----------------------------------------------------------------

    /**
     * Transforms an expression, stripping TypeScript-only constructs.
     * Always returns exactly one expression.
     */
    private fun transformExpression(expr: Expression): Expression {
        return when (expr) {
            // Type assertion / cast expressions: unwrap
            is AsExpression -> transformExpression(expr.expression)
            is NonNullExpression -> transformExpression(expr.expression)
            is SatisfiesExpression -> transformExpression(expr.expression)
            is TypeAssertionExpression -> transformExpression(expr.expression)

            // Parenthesized: if the inner was a type-erasure node (e.g., (<T>expr)),
            // the outer () were just syntax for the assertion — drop them unless the
            // resulting expression really needs parens (object literals, function exprs…)
            // Also handle ((<T>expr)) where an extra layer of parens wraps the assertion.
            is ParenthesizedExpression -> {
                val inner = expr.expression
                val wasTypeErasure = isTypeErasureNode(inner)
                        || (inner is ParenthesizedExpression && isTypeErasureNode(inner.expression))
                val transformed = transformExpression(inner)
                if (wasTypeErasure && !typeAssertionResultNeedsParens(transformed)) {
                    transformed
                } else {
                    expr.copy(expression = transformed)
                }
            }

            // Binary: recurse
            is BinaryExpression -> expr.copy(
                left = transformExpression(expr.left),
                right = transformExpression(expr.right),
            )

            // Call: strip type arguments, recurse
            is CallExpression -> expr.copy(
                expression = transformExpression(expr.expression),
                typeArguments = null,
                arguments = expr.arguments.map { transformExpression(it) },
            )

            // New: strip type arguments, recurse
            is NewExpression -> {
                val constructorExpr = expr.expression
                val transformedConstructor = transformExpression(constructorExpr)
                // When `new` has no explicit arg list and the original constructor was
                // (<T>callExpr), the () are semantically significant:
                //   new (A())  ≠  new A()   (the former calls A first, then newing the result)
                // Re-wrap in () if the type erasure dropped them around a CallExpression.
                val finalConstructor = if (expr.arguments == null
                    && constructorExpr is ParenthesizedExpression
                    && isTypeErasureNode(constructorExpr.expression)
                    && transformedConstructor is CallExpression
                ) {
                    ParenthesizedExpression(
                        expression = transformedConstructor,
                        pos = transformedConstructor.pos,
                        end = transformedConstructor.end,
                    )
                } else {
                    transformedConstructor
                }
                expr.copy(
                    expression = finalConstructor,
                    typeArguments = null,
                    arguments = expr.arguments?.map { transformExpression(it) },
                )
            }

            // Property access: recurse
            is PropertyAccessExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Element access: recurse
            is ElementAccessExpression -> expr.copy(
                expression = transformExpression(expr.expression),
                argumentExpression = transformExpression(expr.argumentExpression),
            )

            // Arrow function: strip types
            is ArrowFunction -> expr.copy(
                typeParameters = null,
                parameters = transformParameters(expr.parameters),
                type = null,
                body = transformArrowBody(expr.body),
                modifiers = stripTypeScriptModifiers(expr.modifiers),
            )

            // Function expression: strip types
            is FunctionExpression -> expr.copy(
                typeParameters = null,
                parameters = transformParameters(expr.parameters),
                type = null,
                body = transformBlock(expr.body),
                modifiers = stripTypeScriptModifiers(expr.modifiers),
            )

            // Class expression: apply class transforms
            is ClassExpression -> transformClassExpression(expr)

            // Unary
            is PrefixUnaryExpression -> expr.copy(
                operand = transformExpression(expr.operand),
            )

            is PostfixUnaryExpression -> expr.copy(
                operand = transformExpression(expr.operand),
            )

            // Conditional (ternary)
            is ConditionalExpression -> expr.copy(
                condition = transformExpression(expr.condition),
                whenTrue = transformExpression(expr.whenTrue),
                whenFalse = transformExpression(expr.whenFalse),
            )

            // Spread
            is SpreadElement -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Template expression
            is TemplateExpression -> expr.copy(
                templateSpans = expr.templateSpans.map { span ->
                    span.copy(expression = transformExpression(span.expression))
                }
            )

            // Tagged template
            is TaggedTemplateExpression -> expr.copy(
                tag = transformExpression(expr.tag),
                typeArguments = null,
            )

            // Yield
            is YieldExpression -> expr.copy(
                expression = expr.expression?.let { transformExpression(it) }
            )

            // Delete
            is DeleteExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Typeof
            is TypeOfExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Void
            is VoidExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Await
            is AwaitExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Array literal
            is ArrayLiteralExpression -> expr.copy(
                elements = expr.elements.map { transformExpression(it) }
            )

            // Object literal
            is ObjectLiteralExpression -> transformObjectLiteral(expr)

            // Computed property name
            is ComputedPropertyName -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Binding patterns (when used as expressions)
            is ObjectBindingPattern -> expr.copy(
                elements = expr.elements.map { transformBindingElement(it) }
            )

            is ArrayBindingPattern -> expr.copy(
                elements = expr.elements.map { node ->
                    when (node) {
                        is BindingElement -> transformBindingElement(node)
                        else -> node
                    }
                }
            )

            // Leaf expressions: pass through
            is Identifier -> expr
            is StringLiteralNode -> expr
            is NumericLiteralNode -> expr
            is BigIntLiteralNode -> expr
            is RegularExpressionLiteralNode -> expr
            is NoSubstitutionTemplateLiteralNode -> expr
            is OmittedExpression -> expr
            is MetaProperty -> expr
        }
    }

    /**
     * Transforms an ObjectLiteralExpression, converting object spreads to Object.assign()
     * for targets below ES2018.
     */
    private fun transformObjectLiteral(node: ObjectLiteralExpression): Expression {
        val transformedProps = node.properties.map { transformObjectLiteralElement(it) }
        // Only transform if target < ES2018 and there are spread elements
        if (options.effectiveTarget >= ScriptTarget.ES2018 ||
            transformedProps.none { it is SpreadAssignment }
        ) {
            return node.copy(properties = transformedProps)
        }
        // Build Object.assign() arguments
        val args = mutableListOf<Expression>()
        val pendingProps = mutableListOf<Node>()
        var firstArgSet = false

        for (prop in transformedProps) {
            if (prop is SpreadAssignment) {
                // Flush any pending non-spread properties as an object literal
                if (pendingProps.isNotEmpty()) {
                    args.add(ObjectLiteralExpression(
                        properties = pendingProps.toList(),
                        multiLine = pendingProps.size > 1 && node.multiLine,
                        hasTrailingComma = false,
                    ))
                    pendingProps.clear()
                    firstArgSet = true
                }
                val spreadExpr = prop.expression
                if (!firstArgSet) {
                    // First argument: use spread expression directly if it's an object literal,
                    // otherwise wrap in Object.assign({}, spreadExpr)
                    if (spreadExpr is ObjectLiteralExpression) {
                        args.add(spreadExpr)
                    } else {
                        args.add(makeObjectAssignCall(listOf(
                            ObjectLiteralExpression(properties = emptyList()),
                            spreadExpr,
                        )))
                    }
                    firstArgSet = true
                } else {
                    args.add(spreadExpr)
                }
            } else {
                pendingProps.add(prop)
            }
        }
        if (pendingProps.isNotEmpty()) {
            args.add(ObjectLiteralExpression(
                properties = pendingProps.toList(),
                multiLine = pendingProps.size > 1 && node.multiLine,
                hasTrailingComma = node.hasTrailingComma,
            ))
        }
        return makeObjectAssignCall(args)
    }

    private fun makeObjectAssignCall(args: List<Expression>): CallExpression {
        return CallExpression(
            expression = PropertyAccessExpression(
                expression = Identifier("Object"),
                name = Identifier("assign"),
            ),
            arguments = args,
        )
    }

    private fun transformObjectLiteralElement(node: Node): Node {
        return when (node) {
            is PropertyAssignment -> node.copy(
                initializer = transformExpression(node.initializer),
            )

            is ShorthandPropertyAssignment -> node.copy(
                objectAssignmentInitializer = node.objectAssignmentInitializer?.let {
                    transformExpression(it)
                }
            )

            is SpreadAssignment -> node.copy(
                expression = transformExpression(node.expression),
            )

            is MethodDeclaration -> transformMethodDeclarationElement(node)
            is GetAccessor -> transformGetAccessorElement(node)
            is SetAccessor -> transformSetAccessorElement(node)
            else -> node
        }
    }

    private fun transformMethodDeclarationElement(method: MethodDeclaration): MethodDeclaration {
        return method.copy(
            typeParameters = null,
            parameters = transformParameters(method.parameters),
            type = null,
            body = method.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(method.modifiers),
        )
    }

    private fun transformGetAccessorElement(accessor: GetAccessor): GetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            type = null,
            body = accessor.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformSetAccessorElement(accessor: SetAccessor): SetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            body = accessor.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformArrowBody(body: Node): Node {
        return when (body) {
            is Block -> transformBlock(body)
            is Expression -> transformExpression(body)
            else -> body
        }
    }

    // -----------------------------------------------------------------
    // Parameter transforms
    // -----------------------------------------------------------------

    private fun transformParameters(params: List<Parameter>): List<Parameter> {
        return params.map { param ->
            param.copy(
                name = transformBindingName(param.name),
                type = null,
                initializer = param.initializer?.let { transformExpression(it) },
                questionToken = false,
                modifiers = emptySet(),
            )
        }
    }

    // -----------------------------------------------------------------
    // Block transform
    // -----------------------------------------------------------------

    private fun transformBlock(block: Block): Block {
        return block.copy(statements = transformStatements(block.statements))
    }

    // -----------------------------------------------------------------
    // Control flow transforms
    // -----------------------------------------------------------------

    private fun transformIfStatement(stmt: IfStatement): Statement {
        return stmt.copy(
            expression = transformExpression(stmt.expression),
            thenStatement = transformStatementSingle(stmt.thenStatement),
            elseStatement = stmt.elseStatement?.let { transformStatementSingle(it) },
        )
    }

    private fun transformForStatement(stmt: ForStatement): Statement {
        val init = when (val i = stmt.initializer) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            null -> null
            else -> i
        }
        return stmt.copy(
            initializer = init,
            condition = stmt.condition?.let { transformExpression(it) },
            incrementor = stmt.incrementor?.let { transformExpression(it) },
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformForInStatement(stmt: ForInStatement): Statement {
        val init = when (val i = stmt.initializer) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            else -> i
        }
        return stmt.copy(
            initializer = init,
            expression = transformExpression(stmt.expression),
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformForOfStatement(stmt: ForOfStatement): Statement {
        val init = when (val i = stmt.initializer) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            else -> i
        }
        return stmt.copy(
            initializer = init,
            expression = transformExpression(stmt.expression),
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformSwitchStatement(stmt: SwitchStatement): Statement {
        return stmt.copy(
            expression = transformExpression(stmt.expression),
            caseBlock = stmt.caseBlock.map { clause ->
                when (clause) {
                    is CaseClause -> clause.copy(
                        expression = transformExpression(clause.expression),
                        statements = transformStatements(clause.statements),
                    )

                    is DefaultClause -> clause.copy(
                        statements = transformStatements(clause.statements),
                    )

                    else -> clause
                }
            }
        )
    }

    private fun transformTryStatement(stmt: TryStatement): Statement {
        return stmt.copy(
            tryBlock = transformBlock(stmt.tryBlock),
            catchClause = stmt.catchClause?.let { cc ->
                val varDecl = cc.variableDeclaration?.let { transformVariableDeclaration(it) }
                    ?: if (options.effectiveTarget < ScriptTarget.ES2019) {
                        // Optional catch binding (ES2019+) needs a dummy `_a` for older targets
                        VariableDeclaration(name = syntheticId("_a"))
                    } else null
                cc.copy(variableDeclaration = varDecl, block = transformBlock(cc.block))
            },
            finallyBlock = stmt.finallyBlock?.let { transformBlock(it) },
        )
    }

    // -----------------------------------------------------------------
    // Import / Export transforms
    // -----------------------------------------------------------------

    private fun transformImportDeclaration(decl: ImportDeclaration): List<Statement> {
        // Type-only import: remove entirely
        if (decl.importClause?.isTypeOnly == true) return emptyList()

        // Strip type-only specifiers from named imports
        val clause = decl.importClause
        if (clause != null) {
            val bindings = clause.namedBindings
            if (bindings is NamedImports) {
                val filtered = bindings.elements.filter { !it.isTypeOnly }
                if (filtered.isEmpty() && clause.name == null) {
                    // All specifiers were type-only and there's no default import
                    return emptyList()
                }
                val newBindings = if (filtered.size == bindings.elements.size) {
                    bindings
                } else if (filtered.isEmpty()) {
                    null
                } else {
                    bindings.copy(elements = filtered)
                }
                return listOf(
                    decl.copy(
                        importClause = clause.copy(namedBindings = newBindings),
                    )
                )
            }
        }

        return listOf(decl)
    }

    private fun transformImportEqualsDeclaration(
        decl: ImportEqualsDeclaration
    ): List<Statement> {
        if (decl.isTypeOnly) return emptyList()

        // `import x = M.N` → `var x = M.N;`
        // `import x = require("mod")` → `const x = require("mod")`
        val ref = decl.moduleReference
        val isRequire = ref is ExternalModuleReference
        val initializer: Expression = when (ref) {
            is ExternalModuleReference -> {
                CallExpression(
                    expression = syntheticId("require"),
                    arguments = listOf(transformExpression(ref.expression)),
                    pos = -1, end = -1,
                )
            }

            is Expression -> transformExpression(ref)
            is QualifiedName -> qualifiedNameToPropertyAccess(ref)
            else -> syntheticId(decl.name.text)
        }

        return listOf(
            VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = decl.name,
                            initializer = initializer,
                            pos = -1, end = -1,
                        )
                    ),
                    flags = if (isRequire) SyntaxKind.ConstKeyword else SyntaxKind.VarKeyword,
                    pos = -1, end = -1,
                ),
                modifiers = stripTypeScriptModifiers(decl.modifiers),
                pos = decl.pos, end = decl.end,
                leadingComments = decl.leadingComments,
                trailingComments = decl.trailingComments,
            )
        )
    }

    private fun transformExportDeclaration(decl: ExportDeclaration): List<Statement> {
        if (decl.isTypeOnly) return emptyList()

        // Strip type-only export specifiers
        val clause = decl.exportClause
        if (clause is NamedExports) {
            val filtered = clause.elements.filter { !it.isTypeOnly }
            if (filtered.isEmpty()) return emptyList()
            if (filtered.size != clause.elements.size) {
                return listOf(
                    decl.copy(exportClause = clause.copy(elements = filtered))
                )
            }
        }

        return listOf(decl)
    }

    // -----------------------------------------------------------------
    // Class declaration transform
    // -----------------------------------------------------------------

    private fun transformClassDeclaration(decl: ClassDeclaration): List<Statement> {
        val result = transformClassBody(
            name = decl.name,
            typeParameters = decl.typeParameters,
            heritageClauses = decl.heritageClauses,
            members = decl.members,
            modifiers = decl.modifiers,
        )

        val transformedClass = decl.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract,
        )

        return listOf(transformedClass) + result.trailingStatements
    }

    private fun transformClassExpression(expr: ClassExpression): Expression {
        val result = transformClassBody(
            name = expr.name,
            typeParameters = expr.typeParameters,
            heritageClauses = expr.heritageClauses,
            members = expr.members,
            modifiers = expr.modifiers,
        )

        // Class expressions can't have trailing statements; static property initializers
        // would need special wrapping. For now, just transform the class body.
        return expr.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(expr.modifiers) - ModifierFlag.Abstract,
        )
    }

    private data class ClassTransformResult(
        val heritageClauses: List<HeritageClause>?,
        val members: List<ClassElement>,
        val trailingStatements: List<Statement>,
    )

    private fun transformClassBody(
        name: Identifier?,
        typeParameters: List<TypeParameter>?,
        heritageClauses: List<HeritageClause>?,
        members: List<ClassElement>,
        modifiers: Set<ModifierFlag>,
    ): ClassTransformResult {

        val isDerived = heritageClauses?.any {
            it.token == SyntaxKind.ExtendsKeyword
        } == true

        // Strip `implements` clauses, keep only `extends`; strip type arguments from extends
        val transformedHeritage = heritageClauses
            ?.filter { it.token == SyntaxKind.ExtendsKeyword }
            ?.map { clause ->
                clause.copy(
                    types = clause.types.map { ewta ->
                        ewta.copy(
                            expression = transformExpression(ewta.expression),
                            typeArguments = null,
                        )
                    }
                )
            }
            ?.ifEmpty { null }

        // Separate members by category
        val instanceProperties = mutableListOf<PropertyDeclaration>()
        val staticProperties = mutableListOf<PropertyDeclaration>()
        val otherMembers = mutableListOf<ClassElement>()
        var existingConstructor: Constructor? = null

        for (member in members) {
            when {
                member is PropertyDeclaration && ModifierFlag.Declare in member.modifiers -> {
                    // `declare` property: remove entirely
                }

                member is PropertyDeclaration && ModifierFlag.Static in member.modifiers -> {
                    staticProperties.add(member)
                }

                member is PropertyDeclaration && ModifierFlag.Static !in member.modifiers -> {
                    instanceProperties.add(member)
                }

                member is Constructor -> {
                    existingConstructor = member
                }

                member is IndexSignature -> {
                    // Index signatures are type-only, remove
                }

                else -> otherMembers.add(member)
            }
        }

        // Determine constructor parameter properties
        val paramProperties = existingConstructor?.parameters
            ?.filter { p -> p.modifiers.any { it in parameterPropertyModifiers } }
            ?: emptyList()

        // Build constructor body statements for property initializers
        val propInitStatements = mutableListOf<Statement>()

        // Parameter properties: this.x = x
        for (param in paramProperties) {
            val paramName = extractIdentifierName(param.name) ?: continue
            propInitStatements.add(
                ExpressionStatement(
                    expression = BinaryExpression(
                        left = PropertyAccessExpression(
                            expression = syntheticId("this"),
                            name = Identifier(text = paramName, pos = -1, end = -1),
                            pos = -1, end = -1,
                        ),
                        operator = SyntaxKind.Equals,
                        right = Identifier(text = paramName, pos = -1, end = -1),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        // Instance property initializers (only when not using define semantics)
        if (!useDefineForClassFields) {
            for (prop in instanceProperties) {
                if (prop.initializer != null) {
                    val propName = extractIdentifierName(prop.name)
                    if (propName != null) {
                        propInitStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = syntheticId("this"),
                                        name = Identifier(text = propName, pos = -1, end = -1),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = transformExpression(prop.initializer),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                trailingComments = prop.trailingComments,
                            )
                        )
                    }
                }
            }
        }

        // Build the transformed constructor
        val needsConstructor = propInitStatements.isNotEmpty() || existingConstructor != null
        val transformedConstructor: Constructor? = if (needsConstructor) {
            if (existingConstructor != null) {
                val transformedParams = transformParameters(existingConstructor.parameters)
                val existingBody = existingConstructor.body?.let { transformBlock(it) }
                    ?: Block(statements = emptyList(), pos = -1, end = -1)

                // Find the position of super() call in existing body
                val bodyStatements = existingBody.statements.toMutableList()
                val superIndex = bodyStatements.indexOfFirst { isSuperCallStatement(it) }

                if (superIndex >= 0) {
                    // Insert after super() call
                    bodyStatements.addAll(superIndex + 1, propInitStatements)
                } else {
                    // Insert at beginning
                    bodyStatements.addAll(0, propInitStatements)
                }

                Constructor(
                    parameters = transformedParams,
                    body = existingBody.copy(
                        statements = bodyStatements,
                        multiLine = bodyStatements.isNotEmpty() || existingBody.multiLine,
                    ),
                    modifiers = stripMemberModifiers(existingConstructor.modifiers),
                    pos = existingConstructor.pos, end = existingConstructor.end,
                    leadingComments = existingConstructor.leadingComments,
                    trailingComments = existingConstructor.trailingComments,
                )
            } else {
                // Synthesize constructor
                val bodyStatements = mutableListOf<Statement>()
                if (isDerived) {
                    // super(...arguments)
                    bodyStatements.add(
                        ExpressionStatement(
                            expression = CallExpression(
                                expression = syntheticId("super"),
                                arguments = listOf(
                                    SpreadElement(
                                        expression = syntheticId("arguments"),
                                        pos = -1, end = -1,
                                    )
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    )
                }
                bodyStatements.addAll(propInitStatements)

                Constructor(
                    parameters = emptyList(),
                    body = Block(statements = bodyStatements, pos = -1, end = -1),
                    pos = -1, end = -1,
                )
            }
        } else {
            null
        }

        // Build the output members list
        val outputMembers = mutableListOf<ClassElement>()

        if (useDefineForClassFields) {
            // Keep instance property declarations but strip types
            for (prop in instanceProperties) {
                outputMembers.add(
                    prop.copy(
                        type = null,
                        initializer = prop.initializer?.let { transformExpression(it) },
                        modifiers = stripMemberModifiers(prop.modifiers),
                        questionToken = false,
                        exclamationToken = false,
                    )
                )
            }
        }
        // When not using define: instance properties are moved to constructor (already done above)

        if (transformedConstructor != null) {
            outputMembers.add(transformedConstructor)
        }

        // Transform remaining members
        for (member in otherMembers) {
            val transformed = transformClassElement(member)
            if (transformed != null) {
                outputMembers.add(transformed)
            }
        }

        // Static properties with useDefineForClassFields stay as class members
        if (useDefineForClassFields) {
            for (prop in staticProperties) {
                outputMembers.add(
                    prop.copy(
                        type = null,
                        initializer = prop.initializer?.let { transformExpression(it) },
                        modifiers = stripMemberModifiers(prop.modifiers),
                        questionToken = false,
                        exclamationToken = false,
                    )
                )
            }
        }

        // Static properties without useDefineForClassFields → trailing statements
        val trailingStatements = mutableListOf<Statement>()
        if (!useDefineForClassFields && name != null) {
            for (prop in staticProperties) {
                if (prop.initializer != null) {
                    val propName = extractIdentifierName(prop.name)
                    if (propName != null) {
                        trailingStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = Identifier(text = name.text, pos = -1, end = -1),
                                        name = Identifier(text = propName, pos = -1, end = -1),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = transformExpression(prop.initializer),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        )
                    }
                }
            }
        }

        return ClassTransformResult(
            heritageClauses = transformedHeritage,
            members = outputMembers,
            trailingStatements = trailingStatements,
        )
    }

    /**
     * Transforms a single class element (method, accessor, etc.).
     * Returns null if the element should be removed.
     */
    private fun transformClassElement(element: ClassElement): ClassElement? {
        return when (element) {
            is MethodDeclaration -> {
                // Overload signatures (no body) are removed
                if (element.body == null) return null
                element.copy(
                    typeParameters = null,
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = transformBlock(element.body),
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is GetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = element.body?.let { transformBlock(it) },
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is SetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    body = element.body?.let { transformBlock(it) },
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is PropertyDeclaration -> {
                // Remaining property declarations (e.g. kept by useDefineForClassFields)
                element.copy(
                    type = null,
                    initializer = element.initializer?.let { transformExpression(it) },
                    modifiers = stripMemberModifiers(element.modifiers),
                    questionToken = false,
                    exclamationToken = false,
                )
            }

            is Constructor -> {
                // Already handled; shouldn't reach here
                element
            }

            is SemicolonClassElement -> element
            is ClassStaticBlockDeclaration -> element.copy(
                body = transformBlock(element.body),
            )

            is IndexSignature -> null // type-only
        }
    }

    private fun isSuperCallStatement(stmt: Statement): Boolean {
        if (stmt !is ExpressionStatement) return false
        val expr = stmt.expression
        if (expr !is CallExpression) return false
        val callee = expr.expression
        return callee is Identifier && callee.text == "super"
    }

    // -----------------------------------------------------------------
    // Enum transform
    // -----------------------------------------------------------------

    /**
     * Transforms an enum declaration into a var declaration + IIFE.
     *
     * ```typescript
     * enum E { A, B = "hello", C }
     * ```
     * becomes:
     * ```javascript
     * var E;
     * (function (E) {
     *     E[E["A"] = 0] = "A";
     *     E["B"] = "hello";
     *     E[E["C"] = 1] = "C";
     * })(E || (E = {}));
     * ```
     */
    private fun transformEnum(decl: EnumDeclaration): List<Statement> {
        // const enum without preserveConstEnums → remove
        if (ModifierFlag.Const in decl.modifiers && !options.preserveConstEnums) {
            return emptyList()
        }

        val enumName = decl.name.text
        val enumId = syntheticId(enumName)

        // Build IIFE body statements
        val iifeBody = mutableListOf<Statement>()
        var nextAutoValue = 0

        for (member in decl.members) {
            val memberName = extractEnumMemberName(member.name)
            val memberNameExpr = memberNameToString(member.name)

            when {
                member.initializer != null -> {
                    val initExpr = transformExpression(member.initializer)
                    if (isStringLiteral(initExpr)) {
                        // String enum member: E["B"] = "hello" (no reverse mapping)
                        iifeBody.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = ElementAccessExpression(
                                        expression = syntheticId(enumName),
                                        argumentExpression = memberNameExpr,
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = initExpr,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        )
                        // After a string member, auto-increment is disrupted; next numeric
                        // member must have explicit initializer. We don't track further.
                    } else {
                        // Numeric / expression initializer: E[E["X"] = expr] = "X"
                        iifeBody.add(makeReverseMapStatement(enumName, memberNameExpr, initExpr))

                        // Try to track auto-increment value
                        val numericValue = tryEvaluateNumericLiteral(initExpr)
                        nextAutoValue = if (numericValue != null) numericValue + 1 else nextAutoValue
                    }
                }

                else -> {
                    // Auto-increment numeric member
                    val valueExpr = NumericLiteralNode(
                        text = nextAutoValue.toString(),
                        pos = -1, end = -1,
                    )
                    iifeBody.add(makeReverseMapStatement(enumName, memberNameExpr, valueExpr))
                    nextAutoValue++
                }
            }
        }

        // Emit var E; unless a class/function declared this name before the enum
        // (in source order), or a prior enum/namespace already emitted var E;
        val needsVarDecl = enumName !in declaredNames && enumName !in emittedVarNames
        emittedVarNames.add(enumName)
        declaredNames.add(enumName)
        val varDecl = if (needsVarDecl) VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = Identifier(text = enumName, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                ),
                flags = SyntaxKind.VarKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            leadingComments = decl.leadingComments,
        ) else null

        // (function (E) { ... })(E || (E = {}));
        val iife = ExpressionStatement(
            expression = CallExpression(
                expression = ParenthesizedExpression(
                    expression = FunctionExpression(
                        parameters = listOf(
                            Parameter(
                                name = Identifier(text = enumName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            )
                        ),
                        body = Block(
                            statements = iifeBody,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                arguments = listOf(
                    // E || (E = {})
                    BinaryExpression(
                        left = enumId,
                        operator = SyntaxKind.BarBar,
                        right = ParenthesizedExpression(
                            expression = BinaryExpression(
                                left = syntheticId(enumName),
                                operator = SyntaxKind.Equals,
                                right = ObjectLiteralExpression(
                                    properties = emptyList(),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            trailingComments = decl.trailingComments,
        )

        return if (varDecl != null) listOf(varDecl, iife) else {
            if (decl.leadingComments != null) {
                listOf(iife.copy(leadingComments = decl.leadingComments))
            } else {
                listOf(iife)
            }
        }
    }

    /**
     * Creates `E[E["name"] = value] = "name";` for numeric reverse mapping.
     */
    private fun makeReverseMapStatement(
        enumName: String,
        memberNameExpr: Expression,
        valueExpr: Expression,
    ): ExpressionStatement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = ElementAccessExpression(
                    expression = syntheticId(enumName),
                    argumentExpression = BinaryExpression(
                        left = ElementAccessExpression(
                            expression = syntheticId(enumName),
                            argumentExpression = memberNameExpr,
                            pos = -1, end = -1,
                        ),
                        operator = SyntaxKind.Equals,
                        right = valueExpr,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                operator = SyntaxKind.Equals,
                right = memberNameExpr,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    private fun extractEnumMemberName(name: NameNode): String {
        return when (name) {
            is Identifier -> name.text
            is StringLiteralNode -> name.text
            is ComputedPropertyName -> "[computed]"
            else -> "unknown"
        }
    }

    private fun memberNameToString(name: NameNode): Expression {
        return when (name) {
            is Identifier -> StringLiteralNode(text = name.text, pos = -1, end = -1)
            is StringLiteralNode -> name
            is ComputedPropertyName -> transformExpression(name.expression)
            else -> StringLiteralNode(text = "unknown", pos = -1, end = -1)
        }
    }

    private fun isStringLiteral(expr: Expression): Boolean {
        return expr is StringLiteralNode
    }

    private fun tryEvaluateNumericLiteral(expr: Expression): Int? {
        return when (expr) {
            is NumericLiteralNode -> expr.text.toIntOrNull()
            is PrefixUnaryExpression -> {
                if (expr.operator == SyntaxKind.Minus) {
                    val inner = tryEvaluateNumericLiteral(expr.operand)
                    inner?.let { -it }
                } else null
            }

            else -> null
        }
    }

    // -----------------------------------------------------------------
    // Namespace / Module declaration transform
    // -----------------------------------------------------------------

    /**
     * Transforms `namespace N { ... }` or `module N { ... }` into:
     * ```javascript
     * var N;
     * (function (N) {
     *     // transformed body
     * })(N || (N = {}));
     * ```
     */
    private fun transformModuleDeclaration(
        decl: ModuleDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
    ): List<Statement> {
        // Ambient (`declare`) module declarations are already handled
        // Type-only namespaces (only types inside) produce no output
        if (isTypeOnlyNamespace(decl)) return emptyList()

        val moduleName = extractIdentifierName(decl.name)
            ?: return emptyList()

        // Handle dotted namespace names: `namespace A.B { ... }` → nested
        // The parser represents A.B as ModuleDeclaration(name="A", body=ModuleDeclaration(name="B", body=...))
        // So we just handle one level at a time

        val body = decl.body
        val bodyStatements: List<Statement> = when (body) {
            is ModuleBlock -> body.statements
            is ModuleDeclaration -> {
                // Nested namespace: recursively transform the inner declaration
                // The inner namespace uses the outer name as its parent (dotted form: A.B)
                return wrapInNamespaceIife(
                    moduleName = moduleName,
                    innerStatements = transformModuleDeclaration(body, nested = true, parentNsName = moduleName),
                    outerDecl = decl,
                    nested = nested,
                    parentNsName = parentNsName,
                )
            }

            null -> return emptyList()
            else -> return emptyList()
        }

        // Transform body statements, rewriting exports
        val transformedBody = transformNamespaceBody(moduleName, bodyStatements)

        return wrapInNamespaceIife(moduleName, transformedBody, decl, nested = nested, parentNsName = parentNsName)
    }

    private fun wrapInNamespaceIife(
        moduleName: String,
        innerStatements: List<Statement>,
        outerDecl: ModuleDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
    ): List<Statement> {
        val nsId = syntheticId(moduleName)

        // Skip var N; if a class/function with the same name was declared BEFORE this
        // namespace (in source order), or if a prior enum/namespace already emitted var N;
        val needsVarDecl = moduleName !in declaredNames && moduleName !in emittedVarNames
        // Track that this name now has a runtime var (for subsequent same-name dedup)
        emittedVarNames.add(moduleName)
        declaredNames.add(moduleName)

        // Inner namespace declarations use `let` (they're inside a function scope).
        // Top-level namespace declarations use `var` (for hoisting compatibility).
        val varKeyword = if (nested) SyntaxKind.LetKeyword else SyntaxKind.VarKeyword

        val varDecl = if (needsVarDecl) {
            VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = Identifier(text = moduleName, pos = -1, end = -1),
                            pos = -1, end = -1,
                        )
                    ),
                    flags = varKeyword,
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
                leadingComments = outerDecl.leadingComments,
            )
        } else null

        // Build the IIFE call argument.
        // When the namespace is exported from a parent namespace (parentNsName != null), use:
        //   (N = Parent.N || (Parent.N = {}))
        // Otherwise use the simple form:
        //   (N || (N = {}))
        val iifeArg: Expression = if (parentNsName != null) {
            // N = Parent.N || (Parent.N = {})
            val parentProp = PropertyAccessExpression(
                expression = syntheticId(parentNsName),
                name = Identifier(text = moduleName, pos = -1, end = -1),
                pos = -1, end = -1,
            )
            BinaryExpression(
                left = nsId,
                operator = SyntaxKind.Equals,
                right = BinaryExpression(
                    left = parentProp,
                    operator = SyntaxKind.BarBar,
                    right = ParenthesizedExpression(
                        expression = BinaryExpression(
                            left = PropertyAccessExpression(
                                expression = syntheticId(parentNsName),
                                name = Identifier(text = moduleName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            ),
                            operator = SyntaxKind.Equals,
                            right = ObjectLiteralExpression(
                                properties = emptyList(),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        } else {
            // N || (N = {})
            BinaryExpression(
                left = nsId,
                operator = SyntaxKind.BarBar,
                right = ParenthesizedExpression(
                    expression = BinaryExpression(
                        left = syntheticId(moduleName),
                        operator = SyntaxKind.Equals,
                        right = ObjectLiteralExpression(
                            properties = emptyList(),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        }

        // (function (N) { ... })(iifeArg);
        val iife = ExpressionStatement(
            expression = CallExpression(
                expression = ParenthesizedExpression(
                    expression = FunctionExpression(
                        parameters = listOf(
                            Parameter(
                                name = Identifier(text = moduleName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            )
                        ),
                        body = Block(
                            statements = innerStatements,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                arguments = listOf(iifeArg),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            trailingComments = outerDecl.trailingComments,
        )

        return if (varDecl != null) listOf(varDecl, iife) else {
            if (outerDecl.leadingComments != null) {
                listOf(iife.copy(leadingComments = outerDecl.leadingComments))
            } else {
                listOf(iife)
            }
        }
    }

    /**
     * Transforms namespace body statements, handling exported members.
     *
     * Exported variables get `N.x = x;` assignments.
     * Exported functions get `N.f = f;` after the declaration.
     * Exported classes get `N.C = C;` after the declaration.
     */
    private fun transformNamespaceBody(
        nsName: String,
        statements: List<Statement>,
    ): List<Statement> {
        // First pass: collect exported variable names
        val exportedNames = mutableSetOf<String>()
        for (stmt in statements) {
            val isExported = when (stmt) {
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
            }
            if (isExported) {
                when (stmt) {
                    is VariableStatement -> for (decl in stmt.declarationList.declarations) {
                        extractIdentifierName(decl.name)?.let { exportedNames.add(it) }
                    }

                    is FunctionDeclaration -> stmt.name?.text?.let { exportedNames.add(it) }
                    is ClassDeclaration -> stmt.name?.text?.let { exportedNames.add(it) }
                    is EnumDeclaration -> exportedNames.add(stmt.name.text)
                    is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { exportedNames.add(it) }
                    is ImportEqualsDeclaration -> exportedNames.add(stmt.name.text)
                    else -> {}
                }
            }
        }

        val result = mutableListOf<Statement>()

        for (stmt in statements) {
            // First, check for type-only statements that should be removed
            when (stmt) {
                is InterfaceDeclaration -> continue
                is TypeAliasDeclaration -> continue
                else -> {}
            }

            if (stmt is Declaration && hasDeclareModifier(stmt)) continue

            val isExported = when (stmt) {
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
            }

            when (stmt) {
                is ImportEqualsDeclaration -> {
                    if (stmt.isTypeOnly) continue
                    // Skip aliases to type-only namespaces (they have no runtime value)
                    val ref = stmt.moduleReference
                    if (ref is Identifier) {
                        val refName = ref.text
                        val referencedNs = statements.filterIsInstance<ModuleDeclaration>()
                            .firstOrNull { extractIdentifierName(it.name) == refName }
                        if (referencedNs != null && isTypeOnlyNamespace(referencedNs)) continue
                    }
                    if (isExported) {
                        // `export import X = R` → `nsName.X = R`
                        val value: Expression = when (ref) {
                            is Expression -> qualifyNamespaceRefs(nsName, exportedNames, transformExpression(ref))
                            is QualifiedName -> qualifyNamespaceRefs(nsName, exportedNames, qualifiedNameToPropertyAccess(ref))
                            else -> syntheticId(stmt.name.text)
                        }
                        result.add(ExpressionStatement(
                            expression = BinaryExpression(
                                left = PropertyAccessExpression(
                                    expression = Identifier(nsName),
                                    name = stmt.name,
                                ),
                                operator = SyntaxKind.Equals,
                                right = value,
                            ),
                        ))
                    } else {
                        // `import R = N` → `var R = N`
                        result.addAll(transformStatement(stmt))
                    }
                }

                is VariableStatement -> {
                    if (isExported) {
                        // export var x = expr → nsName.x = expr;
                        // Multiple declarations in one statement → comma expression: N.a = 1, N.b = 2
                        val assignments = mutableListOf<Expression>()
                        for (decl in stmt.declarationList.declarations) {
                            val varName = extractIdentifierName(decl.name)
                            if (varName != null && decl.initializer != null) {
                                val init =
                                    qualifyNamespaceRefs(nsName, exportedNames, transformExpression(decl.initializer))
                                assignments.add(
                                    BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = Identifier(nsName),
                                            name = Identifier(varName),
                                        ),
                                        operator = SyntaxKind.Equals,
                                        right = init,
                                        pos = -1, end = -1,
                                    )
                                )
                            }
                        }
                        if (assignments.isNotEmpty()) {
                            // Build a comma expression if there are multiple assignments
                            val expr = assignments.reduce { acc, e ->
                                BinaryExpression(left = acc, operator = SyntaxKind.Comma, right = e, pos = -1, end = -1)
                            }
                            result.add(ExpressionStatement(expression = expr, pos = -1, end = -1))
                        }
                    } else {
                        val strippedStmt = stmt.copy(
                            modifiers = stmt.modifiers - ModifierFlag.Export,
                        )
                        val transformed = transformVariableStatement(strippedStmt)
                        result.addAll(transformed)
                    }
                }

                is FunctionDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    val transformed = transformFunctionDeclaration(strippedStmt)
                    result.addAll(transformed)

                    if (isExported && stmt.name != null) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
                    }
                }

                is ClassDeclaration -> {
                    // Qualify heritage clause references to exported members
                    val qualifiedHeritage = stmt.heritageClauses?.map { clause ->
                        clause.copy(types = clause.types.map { type ->
                            type.copy(expression = qualifyNamespaceRefs(nsName, exportedNames, type.expression))
                        })
                    }
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                        heritageClauses = qualifiedHeritage,
                    )
                    val transformed = transformClassDeclaration(strippedStmt)
                    result.addAll(transformed)

                    if (isExported && stmt.name != null) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
                    }
                }

                is EnumDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    val transformed = transformEnum(strippedStmt)
                    result.addAll(transformed)

                    if (isExported) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
                    }
                }

                is ModuleDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    // When exported, embed the parent->child assignment in the IIFE arg
                    // (N = parent.N || (parent.N = {})) rather than a separate assignment
                    val parentForIife = if (isExported) nsName else null
                    val transformed = transformModuleDeclaration(strippedStmt, nested = true, parentNsName = parentForIife)
                    result.addAll(transformed)
                }

                else -> {
                    result.addAll(transformStatement(stmt))
                }
            }
        }

        return result
    }

    /**
     * Replaces identifier references to exported namespace members with
     * `nsName.memberName` property access expressions.
     */
    private fun qualifyNamespaceRefs(
        nsName: String,
        exportedNames: Set<String>,
        expr: Expression,
    ): Expression {
        if (exportedNames.isEmpty()) return expr
        return when (expr) {
            is Identifier -> {
                if (expr.text in exportedNames) {
                    PropertyAccessExpression(
                        expression = Identifier(nsName, pos = -1, end = -1),
                        name = Identifier(expr.text, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                } else expr
            }

            is BinaryExpression -> expr.copy(
                left = qualifyNamespaceRefs(nsName, exportedNames, expr.left),
                right = qualifyNamespaceRefs(nsName, exportedNames, expr.right),
            )

            is CallExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
                arguments = expr.arguments.map { qualifyNamespaceRefs(nsName, exportedNames, it) },
            )

            is PropertyAccessExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
            )

            is ConditionalExpression -> expr.copy(
                condition = qualifyNamespaceRefs(nsName, exportedNames, expr.condition),
                whenTrue = qualifyNamespaceRefs(nsName, exportedNames, expr.whenTrue),
                whenFalse = qualifyNamespaceRefs(nsName, exportedNames, expr.whenFalse),
            )

            is ParenthesizedExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
            )

            else -> expr
        }
    }

    /**
     * Creates `N.memberName = memberName;`
     */
    private fun makeNamespaceExportAssignment(nsName: String, memberName: String): Statement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = PropertyAccessExpression(
                    expression = syntheticId(nsName),
                    name = Identifier(text = memberName, pos = -1, end = -1),
                    pos = -1, end = -1,
                ),
                operator = SyntaxKind.Equals,
                right = syntheticId(memberName),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /**
     * Checks whether a namespace contains only type-level declarations
     * (interfaces, type aliases, etc.) and thus produces no runtime output.
     */
    private fun isTypeOnlyNamespace(decl: ModuleDeclaration): Boolean {
        val body = decl.body ?: return true
        return when (body) {
            is ModuleBlock -> body.statements.all { isTypeOnlyStatement(it) }
            is ModuleDeclaration -> isTypeOnlyNamespace(body)
            else -> false
        }
    }

    private fun isTypeOnlyStatement(stmt: Statement): Boolean {
        return when (stmt) {
            is InterfaceDeclaration -> true
            is TypeAliasDeclaration -> true
            is ImportDeclaration -> stmt.importClause?.isTypeOnly == true
            is ExportDeclaration -> stmt.isTypeOnly
            else -> false
        }
    }

    // -----------------------------------------------------------------
    // Modifier utilities
    // -----------------------------------------------------------------

    /**
     * Strips TypeScript-only modifiers from a declaration modifier set,
     * keeping only those that have JavaScript runtime meaning (Export, Default, Static, Async, Const).
     */
    private fun stripTypeScriptModifiers(modifiers: Set<ModifierFlag>): Set<ModifierFlag> {
        return modifiers - setOf(
            ModifierFlag.Declare,
            ModifierFlag.Abstract,
            ModifierFlag.Public,
            ModifierFlag.Private,
            ModifierFlag.Protected,
            ModifierFlag.Readonly,
            ModifierFlag.Override,
            ModifierFlag.In,
            ModifierFlag.Out,
        )
    }

    /**
     * Strips type-only modifiers from class members.
     */
    private fun stripMemberModifiers(modifiers: Set<ModifierFlag>): Set<ModifierFlag> {
        return modifiers - typeOnlyMemberModifiers
    }

    // -----------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------

    /**
     * Returns an empty list — erased declarations do not preserve leading comments in JS output.
     */
    private fun orphanedComments(statement: Statement): List<Statement> {
        return emptyList()
    }

    private fun syntheticId(name: String): Identifier {
        return Identifier(text = name, pos = -1, end = -1)
    }

    private fun extractIdentifierName(expr: Node): String? {
        return when (expr) {
            is Identifier -> expr.text
            is StringLiteralNode -> expr.text
            else -> null
        }
    }

    /**
     * Converts a [QualifiedName] AST node to a [PropertyAccessExpression] chain.
     * `A.B.C` (QualifiedName) → `A.B.C` (PropertyAccessExpression).
     */
    private fun qualifiedNameToPropertyAccess(qn: QualifiedName): Expression {
        val left: Expression = when (val l = qn.left) {
            is QualifiedName -> qualifiedNameToPropertyAccess(l)
            is Identifier -> l
            else -> syntheticId("unknown")
        }
        return PropertyAccessExpression(
            expression = left,
            name = qn.right,
            pos = qn.pos, end = qn.end,
        )
    }

    private fun isTypeErasureNode(expr: Expression): Boolean =
        expr is TypeAssertionExpression || expr is AsExpression
                || expr is NonNullExpression || expr is SatisfiesExpression

    /**
     * Returns true if [expr] (the result of stripping a type assertion) still needs
     * enclosing parentheses to be unambiguous JavaScript.
     *
     * - ObjectLiteralExpression: needs `()` in statement position to avoid being parsed as a block
     * - FunctionExpression / ClassExpression: needs `()` to avoid being parsed as a declaration
     * - ArrowFunction: needs `()` for immediate invocation `(()=>{})(...)`
     * - NewExpression without arguments: needs `()` for member access — `new A.foo` ≠ `(new A).foo`
     * - Prefix unary / keyword-prefix ops: needs `()` for member access — `(-A).x` ≠ `-A.x`
     */
    private fun typeAssertionResultNeedsParens(expr: Expression): Boolean = when (expr) {
        is ObjectLiteralExpression -> true
        is FunctionExpression, is ClassExpression -> true
        is ArrowFunction -> true
        is NewExpression -> expr.arguments == null
        is PrefixUnaryExpression -> true
        is TypeOfExpression, is VoidExpression, is DeleteExpression -> true
        is AwaitExpression, is YieldExpression -> true
        else -> false
    }
}
