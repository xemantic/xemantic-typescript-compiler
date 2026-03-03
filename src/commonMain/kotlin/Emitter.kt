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
 * JavaScript emitter that converts a parsed TypeScript AST into JavaScript source text.
 *
 * TypeScript-only constructs (interfaces, type aliases, type annotations, declare
 * declarations, etc.) are erased during emission. The output format matches the
 * TypeScript official baseline: 4-space indentation, specific semicolon and brace
 * placement rules, and preserved comments (unless [CompilerOptions.removeComments]
 * is set).
 */
class Emitter(
    private val options: CompilerOptions = CompilerOptions(),
) {

    private val sb = StringBuilder()
    private var indentLevel = 0
    private var isStartOfLine = true

    /**
     * Emits the given [sourceFile] as JavaScript and returns the resulting source text.
     */
    fun emit(sourceFile: SourceFile, originalSourceFile: SourceFile? = null): String {
        emitUseStrict(originalSourceFile ?: sourceFile)
        emitStatements(sourceFile.statements)
        // If the file is a module but all statements were skipped, emit "export {}"
        emitEmptyExportIfNeeded(originalSourceFile ?: sourceFile, sourceFile)
        return sb.toString().trimEnd('\n')
    }

    private fun emitEmptyExportIfNeeded(originalSourceFile: SourceFile, transformedSourceFile: SourceFile) {
        if (!hasModuleStatements(originalSourceFile)) return

        val hasNonSkippedStatements = transformedSourceFile.statements.any { !shouldSkipStatement(it) }
        if (!hasNonSkippedStatements) {
            writeLine("export {};")
        }
    }

    private fun hasModuleStatements(sourceFile: SourceFile): Boolean = sourceFile.statements.any {
        it is ImportDeclaration || it is ExportDeclaration || it is ExportAssignment ||
                (it is ImportEqualsDeclaration) ||
                (it is VariableStatement && ModifierFlag.Export in it.modifiers) ||
                (it is FunctionDeclaration && ModifierFlag.Export in it.modifiers) ||
                (it is ClassDeclaration && ModifierFlag.Export in it.modifiers) ||
                (it is EnumDeclaration && ModifierFlag.Export in it.modifiers) ||
                (it is ModuleDeclaration && ModifierFlag.Export in it.modifiers) ||
                (it is InterfaceDeclaration && ModifierFlag.Export in it.modifiers) ||
                (it is TypeAliasDeclaration && ModifierFlag.Export in it.modifiers)
    }

    // ---------------------------------------------------------------------------
    // "use strict"
    // ---------------------------------------------------------------------------

    private fun emitUseStrict(sourceFile: SourceFile) {
        val shouldEmitStrict = options.effectiveAlwaysStrict ||
                options.target >= ScriptTarget.ES2015

        if (!shouldEmitStrict) return

        // Check if the file is an ES module (has import/export statements) AND
        // the module format is ES — ES modules are inherently strict
        val effectiveModule = options.effectiveModule
        val isESModuleFormat = effectiveModule == ModuleKind.ES2015 ||
                effectiveModule == ModuleKind.ES2020 ||
                effectiveModule == ModuleKind.ES2022 ||
                effectiveModule == ModuleKind.ESNext
        if (hasModuleStatements(sourceFile) && isESModuleFormat) return

        // Check if the source already has "use strict" as the first statement
        val firstStmt = sourceFile.statements.firstOrNull()
        if (firstStmt is ExpressionStatement) {
            val expr = firstStmt.expression
            if (expr is StringLiteralNode && expr.text == "use strict") {
                return // source already has "use strict"
            }
        }

        writeLine("\"use strict\";")
    }

    // ---------------------------------------------------------------------------
    // Statement list
    // ---------------------------------------------------------------------------

    private fun emitStatements(statements: List<Statement>) {
        for (statement in statements) {
            if (shouldSkipStatement(statement)) continue
            emitLeadingComments(statement)
            emitStatement(statement)
            emitTrailingCommentsBeforeNewline(statement)
        }
    }

    private fun emitBlockStatements(statements: List<Statement>) {
        for (statement in statements) {
            if (shouldSkipStatement(statement)) continue
            emitLeadingComments(statement)
            emitStatement(statement)
            emitTrailingCommentsBeforeNewline(statement)
        }
    }

    private fun shouldSkipStatement(statement: Statement): Boolean = when (statement) {
        is InterfaceDeclaration -> true
        is TypeAliasDeclaration -> true
        is NotEmittedStatement -> true
        is FunctionDeclaration -> {
            ModifierFlag.Declare in statement.modifiers
                    || statement.body == null // overload signature
        }

        is ClassDeclaration -> ModifierFlag.Declare in statement.modifiers
        is EnumDeclaration -> ModifierFlag.Declare in statement.modifiers && !options.preserveConstEnums
        is ModuleDeclaration -> ModifierFlag.Declare in statement.modifiers
        is VariableStatement -> ModifierFlag.Declare in statement.modifiers
        is ImportDeclaration -> statement.importClause?.isTypeOnly == true
        is ImportEqualsDeclaration -> statement.isTypeOnly
        is ExportDeclaration -> statement.isTypeOnly
        else -> false
    }

    // ---------------------------------------------------------------------------
    // Statement dispatch
    // ---------------------------------------------------------------------------

    private fun emitStatement(statement: Statement) {
        when (statement) {
            is Block -> emitBlock(statement, standalone = true)
            is EmptyStatement -> emitEmptyStatement()
            is VariableStatement -> emitVariableStatement(statement)
            is ExpressionStatement -> emitExpressionStatement(statement)
            is IfStatement -> emitIfStatement(statement)
            is DoStatement -> emitDoStatement(statement)
            is WhileStatement -> emitWhileStatement(statement)
            is ForStatement -> emitForStatement(statement)
            is ForInStatement -> emitForInStatement(statement)
            is ForOfStatement -> emitForOfStatement(statement)
            is ContinueStatement -> emitContinueStatement(statement)
            is BreakStatement -> emitBreakStatement(statement)
            is ReturnStatement -> emitReturnStatement(statement)
            is WithStatement -> emitWithStatement(statement)
            is SwitchStatement -> emitSwitchStatement(statement)
            is LabeledStatement -> emitLabeledStatement(statement)
            is ThrowStatement -> emitThrowStatement(statement)
            is TryStatement -> emitTryStatement(statement)
            is DebuggerStatement -> emitDebuggerStatement()
            is FunctionDeclaration -> emitFunctionDeclaration(statement)
            is ClassDeclaration -> emitClassDeclaration(statement)
            is EnumDeclaration -> emitEnumDeclaration(statement)
            is ModuleDeclaration -> emitModuleDeclaration(statement)
            is ImportDeclaration -> emitImportDeclaration(statement)
            is ImportEqualsDeclaration -> emitImportEqualsDeclaration(statement)
            is ExportDeclaration -> emitExportDeclaration(statement)
            is ExportAssignment -> emitExportAssignment(statement)
            is VariableDeclaration -> { /* handled inside VariableDeclarationList */
            }

            is InterfaceDeclaration -> { /* erased */
            }

            is TypeAliasDeclaration -> { /* erased */
            }

            is NotEmittedStatement -> { /* skip */
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Statements
    // ---------------------------------------------------------------------------

    private fun emitEmptyStatement() {
        writeLine(";")
    }

    private fun emitVariableStatement(node: VariableStatement) {
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
        }
        emitVariableDeclarationList(node.declarationList)
        write(";")
        writeNewLine()
    }

    private fun emitVariableDeclarationList(node: VariableDeclarationList) {
        val keyword = when (node.flags) {
            SyntaxKind.LetKeyword -> "let"
            SyntaxKind.ConstKeyword -> "const"
            else -> "var"
        }
        write(keyword)
        write(" ")
        for ((index, decl) in node.declarations.withIndex()) {
            if (index > 0) write(", ")
            emitVariableDeclaration(decl)
        }
    }

    private fun emitVariableDeclaration(node: VariableDeclaration) {
        emitExpression(node.name)
        // skip type annotation
        if (node.initializer != null) {
            write(" = ")
            emitExpression(node.initializer)
        }
    }

    private fun emitExpressionStatement(node: ExpressionStatement) {
        writeIndent()
        emitExpression(node.expression)
        write(";")
        writeNewLine()
    }

    private fun emitIfStatement(node: IfStatement) {
        writeIndent()
        emitIfStatementCore(node)
    }

    private fun emitIfStatementCore(node: IfStatement) {
        write("if (")
        emitExpression(node.expression)
        write(")")
        if (node.elseStatement != null) {
            // Emit then-block without trailing newline so we can put else on same/next line
            if (node.thenStatement is Block) {
                val block = node.thenStatement
                emitBlockBody(block)
                if (block.multiLine || (block.statements.isEmpty() && block.multiLine)) {
                    // } is on its own line, else goes on next line
                    writeNewLine()
                    writeIndent()
                    write("else")
                } else {
                    // } else on same line
                    write(" else")
                }
            } else {
                writeNewLine()
                indentLevel++
                emitStatement(node.thenStatement)
                indentLevel--
                writeIndent()
                write("else")
            }
            if (node.elseStatement is IfStatement) {
                write(" ")
                emitIfStatementCore(node.elseStatement)
            } else {
                emitEmbeddedStatement(node.elseStatement)
            }
        } else {
            emitEmbeddedStatement(node.thenStatement)
        }
    }

    private fun emitDoStatement(node: DoStatement) {
        writeIndent()
        write("do")
        emitEmbeddedStatement(node.statement, trailingOnSameLine = true)
        if (node.statement is Block) {
            write(" while (")
        } else {
            writeIndent()
            write("while (")
        }
        emitExpression(node.expression)
        write(");")
        writeNewLine()
    }

    private fun emitWhileStatement(node: WhileStatement) {
        writeIndent()
        write("while (")
        emitExpression(node.expression)
        write(")")
        emitEmbeddedStatement(node.statement)
    }

    private fun emitForStatement(node: ForStatement) {
        writeIndent()
        write("for (")
        if (node.initializer != null) {
            when (val init = node.initializer) {
                is VariableDeclarationList -> emitVariableDeclarationList(init)
                is Expression -> emitExpression(init)
                else -> { /* should not happen */
                }
            }
        }
        write("; ")
        if (node.condition != null) {
            emitExpression(node.condition)
        }
        write("; ")
        if (node.incrementor != null) {
            emitExpression(node.incrementor)
        }
        write(")")
        emitEmbeddedStatement(node.statement)
    }

    private fun emitForInStatement(node: ForInStatement) {
        writeIndent()
        write("for (")
        when (val init = node.initializer) {
            is VariableDeclarationList -> emitVariableDeclarationList(init)
            is Expression -> emitExpression(init)
            else -> { /* should not happen */
            }
        }
        write(" in ")
        emitExpression(node.expression)
        write(")")
        emitEmbeddedStatement(node.statement)
    }

    private fun emitForOfStatement(node: ForOfStatement) {
        writeIndent()
        write("for ")
        if (node.awaitModifier) {
            write("await ")
        }
        write("(")
        when (val init = node.initializer) {
            is VariableDeclarationList -> emitVariableDeclarationList(init)
            is Expression -> emitExpression(init)
            else -> { /* should not happen */
            }
        }
        write(" of ")
        emitExpression(node.expression)
        write(")")
        emitEmbeddedStatement(node.statement)
    }

    private fun emitContinueStatement(node: ContinueStatement) {
        writeIndent()
        write("continue")
        if (node.label != null) {
            write(" ")
            write(node.label.text)
        }
        write(";")
        writeNewLine()
    }

    private fun emitBreakStatement(node: BreakStatement) {
        writeIndent()
        write("break")
        if (node.label != null) {
            write(" ")
            write(node.label.text)
        }
        write(";")
        writeNewLine()
    }

    private fun emitReturnStatement(node: ReturnStatement) {
        writeIndent()
        write("return")
        if (node.expression != null) {
            write(" ")
            emitExpression(node.expression)
        }
        write(";")
        writeNewLine()
    }

    private fun emitWithStatement(node: WithStatement) {
        writeIndent()
        write("with (")
        emitExpression(node.expression)
        write(")")
        emitEmbeddedStatement(node.statement)
    }

    private fun emitSwitchStatement(node: SwitchStatement) {
        writeIndent()
        write("switch (")
        emitExpression(node.expression)
        write(") {")
        writeNewLine()
        for (clause in node.caseBlock) {
            when (clause) {
                is CaseClause -> emitCaseClause(clause)
                is DefaultClause -> emitDefaultClause(clause)
                else -> { /* should not happen */
                }
            }
        }
        writeIndent()
        write("}")
        writeNewLine()
    }

    private fun emitCaseClause(node: CaseClause) {
        indentLevel++
        writeIndent()
        write("case ")
        emitExpression(node.expression)
        write(":")
        if (node.singleLine && node.statements.isNotEmpty()) {
            write(" ")
            val saved = isStartOfLine
            isStartOfLine = false
            for ((i, stmt) in node.statements.withIndex()) {
                if (i > 0) write(" ")
                emitStatementInline(stmt)
            }
            isStartOfLine = saved
            writeNewLine()
        } else {
            writeNewLine()
            indentLevel++
            emitBlockStatements(node.statements)
            indentLevel--
        }
        indentLevel--
    }

    private fun emitDefaultClause(node: DefaultClause) {
        indentLevel++
        writeIndent()
        write("default:")
        if (node.singleLine && node.statements.isNotEmpty()) {
            write(" ")
            val saved = isStartOfLine
            isStartOfLine = false
            for ((i, stmt) in node.statements.withIndex()) {
                if (i > 0) write(" ")
                emitStatementInline(stmt)
            }
            isStartOfLine = saved
            writeNewLine()
        } else {
            writeNewLine()
            indentLevel++
            emitBlockStatements(node.statements)
            indentLevel--
        }
        indentLevel--
    }

    private fun emitLabeledStatement(node: LabeledStatement) {
        writeIndent()
        write(node.label.text)
        write(":")
        writeNewLine()
        indentLevel++
        emitStatement(node.statement)
        indentLevel--
    }

    private fun emitThrowStatement(node: ThrowStatement) {
        writeIndent()
        write("throw ")
        emitExpression(node.expression)
        write(";")
        writeNewLine()
    }

    private fun emitTryStatement(node: TryStatement) {
        writeIndent()
        write("try")
        emitBlockBody(node.tryBlock)
        if (node.catchClause != null) {
            write(" catch")
            if (node.catchClause.variableDeclaration != null) {
                write(" (")
                emitExpression(node.catchClause.variableDeclaration.name)
                write(")")
            }
            emitBlockBody(node.catchClause.block)
        }
        if (node.finallyBlock != null) {
            write(" finally")
            emitBlockBody(node.finallyBlock)
        }
        writeNewLine()
    }

    private fun emitDebuggerStatement() {
        writeLine("debugger;")
    }

    // ---------------------------------------------------------------------------
    // Declarations
    // ---------------------------------------------------------------------------

    private fun emitFunctionDeclaration(node: FunctionDeclaration) {
        if (node.body == null) return // overload signature — skip
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
            if (ModifierFlag.Default in node.modifiers) {
                write("default ")
            }
        }
        if (ModifierFlag.Async in node.modifiers) {
            write("async ")
        }
        write("function")
        if (node.asteriskToken) {
            write("*")
        }
        if (node.name != null) {
            write(" ")
            write(node.name.text)
        }
        write("(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body)
        writeNewLine()
    }

    private fun emitClassDeclaration(node: ClassDeclaration) {
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
            if (ModifierFlag.Default in node.modifiers) {
                write("default ")
            }
        }
        // abstract is erased
        write("class")
        if (node.name != null) {
            write(" ")
            write(node.name.text)
        }
        // type parameters erased
        emitHeritageClauses(node.heritageClauses)
        write(" {")
        writeNewLine()
        indentLevel++
        emitClassMembers(node.members)
        indentLevel--
        writeIndent()
        write("}")
        writeNewLine()
    }

    private fun emitClassMembers(members: List<ClassElement>) {
        for (member in members) {
            if (shouldSkipClassElement(member)) continue
            emitLeadingComments(member)
            emitClassElement(member)
            emitTrailingCommentsBeforeNewline(member)
        }
    }

    private fun shouldSkipClassElement(element: ClassElement): Boolean = when (element) {
        is PropertyDeclaration -> {
            ModifierFlag.Declare in element.modifiers
                    || ModifierFlag.Abstract in element.modifiers
        }

        is MethodDeclaration -> {
            ModifierFlag.Declare in element.modifiers
                    || ModifierFlag.Abstract in element.modifiers
                    || element.body == null // overload signature
        }

        is Constructor -> element.body == null // overload signature
        is GetAccessor -> {
            ModifierFlag.Abstract in element.modifiers
                    || element.body == null
        }

        is SetAccessor -> {
            ModifierFlag.Abstract in element.modifiers
                    || element.body == null
        }

        is IndexSignature -> true // type-only, erased
        else -> false
    }

    private fun emitClassElement(element: ClassElement) {
        when (element) {
            is PropertyDeclaration -> emitPropertyDeclaration(element)
            is MethodDeclaration -> emitMethodDeclaration(element)
            is Constructor -> emitConstructorDeclaration(element)
            is GetAccessor -> emitGetAccessorDeclaration(element)
            is SetAccessor -> emitSetAccessorDeclaration(element)
            is SemicolonClassElement -> emitSemicolonClassElement()
            is ClassStaticBlockDeclaration -> emitClassStaticBlockDeclaration(element)
            is IndexSignature -> { /* erased */
            }
        }
    }

    private fun emitPropertyDeclaration(node: PropertyDeclaration) {
        writeIndent()
        if (ModifierFlag.Static in node.modifiers) {
            write("static ")
        }
        if (ModifierFlag.Accessor in node.modifiers) {
            write("accessor ")
        }
        emitPropertyName(node.name)
        // skip type annotation
        if (node.initializer != null) {
            write(" = ")
            emitExpression(node.initializer)
        }
        write(";")
        writeNewLine()
    }

    private fun emitMethodDeclaration(node: MethodDeclaration) {
        if (node.body == null) return // overload signature
        writeIndent()
        if (ModifierFlag.Static in node.modifiers) {
            write("static ")
        }
        if (ModifierFlag.Async in node.modifiers) {
            write("async ")
        }
        if (node.asteriskToken) {
            write("*")
        }
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body)
        writeNewLine()
    }

    private fun emitConstructorDeclaration(node: Constructor) {
        if (node.body == null) return // overload signature
        writeIndent()
        write("constructor(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body)
        writeNewLine()
    }

    private fun emitGetAccessorDeclaration(node: GetAccessor) {
        if (node.body == null) return
        writeIndent()
        if (ModifierFlag.Static in node.modifiers) {
            write("static ")
        }
        write("get ")
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body)
        writeNewLine()
    }

    private fun emitSetAccessorDeclaration(node: SetAccessor) {
        if (node.body == null) return
        writeIndent()
        if (ModifierFlag.Static in node.modifiers) {
            write("static ")
        }
        write("set ")
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body)
        writeNewLine()
    }

    private fun emitSemicolonClassElement() {
        writeLine(";")
    }

    private fun emitClassStaticBlockDeclaration(node: ClassStaticBlockDeclaration) {
        writeIndent()
        write("static")
        emitBlockBody(node.body)
        writeNewLine()
    }

    // ---------------------------------------------------------------------------
    // Enum declaration
    // ---------------------------------------------------------------------------

    private fun emitEnumDeclaration(node: EnumDeclaration) {
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
        }
        write("var ")
        write(node.name.text)
        write(";")
        writeNewLine()
        writeLine("(function (${node.name.text}) {")
        indentLevel++
        for (member in node.members) {
            emitEnumMember(node.name.text, member)
        }
        indentLevel--
        writeLine("})(${node.name.text} || (${node.name.text} = {}));")
    }

    private fun emitEnumMember(enumName: String, member: EnumMember) {
        writeIndent()
        val memberName = when (val n = member.name) {
            is Identifier -> n.text
            is StringLiteralNode -> n.text
            else -> "unknown"
        }
        write("$enumName[\"$memberName\"] = ")
        if (member.initializer != null) {
            emitExpression(member.initializer)
        } else {
            write("0") // default value placeholder
        }
        write(";")
        writeNewLine()
    }

    // ---------------------------------------------------------------------------
    // Module/Namespace declaration
    // ---------------------------------------------------------------------------

    private fun emitModuleDeclaration(node: ModuleDeclaration) {
        if (ModifierFlag.Declare in node.modifiers) return
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
        }
        // Emit as IIFE pattern for namespaces
        val name = when (val n = node.name) {
            is Identifier -> n.text
            is StringLiteralNode -> "\"${n.text}\""
            else -> "unknown"
        }
        when (val body = node.body) {
            is ModuleBlock -> {
                write("var $name;")
                writeNewLine()
                writeLine("(function ($name) {")
                indentLevel++
                emitBlockStatements(body.statements)
                indentLevel--
                writeLine("})($name || ($name = {}));")
            }

            is ModuleDeclaration -> {
                // nested namespace
                emitModuleDeclaration(body)
            }

            else -> {
                write("var $name;")
                writeNewLine()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Import/Export declarations
    // ---------------------------------------------------------------------------

    private fun emitImportDeclaration(node: ImportDeclaration) {
        if (node.importClause?.isTypeOnly == true) return
        writeIndent()
        write("import")
        if (node.importClause != null) {
            write(" ")
            emitImportClause(node.importClause)
            write(" from ")
        } else {
            write(" ")
        }
        emitExpression(node.moduleSpecifier)
        write(";")
        writeNewLine()
    }

    private fun emitImportClause(node: ImportClause) {
        val hasName = node.name != null
        val hasBindings = node.namedBindings != null

        if (hasName) {
            write(node.name!!.text)
            if (hasBindings) {
                write(", ")
            }
        }
        if (hasBindings) {
            when (val bindings = node.namedBindings) {
                is NamespaceImport -> {
                    write("* as ")
                    write(bindings.name.text)
                }

                is NamedImports -> {
                    write("{ ")
                    val nonTypeSpecifiers = bindings.elements.filter { !it.isTypeOnly }
                    for ((index, specifier) in nonTypeSpecifiers.withIndex()) {
                        if (index > 0) write(", ")
                        if (specifier.propertyName != null) {
                            write(specifier.propertyName.text)
                            write(" as ")
                        }
                        write(specifier.name.text)
                    }
                    write(" }")
                }

                else -> { /* should not happen */
                }
            }
        }
    }

    private fun emitImportEqualsDeclaration(node: ImportEqualsDeclaration) {
        if (node.isTypeOnly) return
        writeIndent()
        if (ModifierFlag.Export in node.modifiers) {
            write("export ")
        }
        write("var ")
        write(node.name.text)
        write(" = ")
        emitModuleReference(node.moduleReference)
        write(";")
        writeNewLine()
    }

    private fun emitModuleReference(node: Node) {
        when (node) {
            is ExternalModuleReference -> {
                write("require(")
                emitExpression(node.expression)
                write(")")
            }

            is Identifier -> write(node.text)
            is QualifiedName -> {
                emitModuleReference(node.left)
                write(".")
                write(node.right.text)
            }

            else -> { /* should not happen */
            }
        }
    }

    private fun emitExportDeclaration(node: ExportDeclaration) {
        if (node.isTypeOnly) return
        writeIndent()
        write("export")
        when (val exportClause = node.exportClause) {
            null -> {
                write(" *")
                if (node.moduleSpecifier != null) {
                    write(" from ")
                    emitExpression(node.moduleSpecifier)
                }
            }

            is NamedExports -> {
                write(" { ")
                val nonTypeSpecifiers = exportClause.elements.filter { !it.isTypeOnly }
                for ((index, specifier) in nonTypeSpecifiers.withIndex()) {
                    if (index > 0) write(", ")
                    if (specifier.propertyName != null) {
                        write(specifier.propertyName.text)
                        write(" as ")
                    }
                    write(specifier.name.text)
                }
                write(" }")
                if (node.moduleSpecifier != null) {
                    write(" from ")
                    emitExpression(node.moduleSpecifier)
                }
            }

            is NamespaceExport -> {
                write(" * as ")
                write(exportClause.name.text)
                if (node.moduleSpecifier != null) {
                    write(" from ")
                    emitExpression(node.moduleSpecifier)
                }
            }

            else -> { /* should not happen */
            }
        }
        write(";")
        writeNewLine()
    }

    private fun emitExportAssignment(node: ExportAssignment) {
        writeIndent()
        if (node.isExportEquals) {
            write("module.exports = ")
        } else {
            write("export default ")
        }
        emitExpression(node.expression)
        write(";")
        writeNewLine()
    }

    // ---------------------------------------------------------------------------
    // Block / embedded statement helpers
    // ---------------------------------------------------------------------------

    private fun emitBlock(block: Block, standalone: Boolean = false) {
        if (standalone) writeIndent()
        write("{")
        writeNewLine()
        indentLevel++
        emitBlockStatements(block.statements)
        indentLevel--
        writeIndent()
        write("}")
        if (standalone) writeNewLine()
    }

    /**
     * Emits a block `{ ... }` for function/class bodies where the opening brace
     * is placed on the same line as the preceding declaration.
     */
    private fun emitBlockBody(block: Block, singleLineIfEmpty: Boolean = true) {
        if (block.statements.isEmpty() && !block.multiLine) {
            write(" { }")
        } else if (!block.multiLine && block.statements.isNotEmpty()) {
            // Single-line block: emit all statements on one line
            write(" { ")
            val saved = isStartOfLine
            isStartOfLine = false
            for ((i, stmt) in block.statements.withIndex()) {
                if (i > 0) write(" ")
                emitStatementInline(stmt)
            }
            isStartOfLine = saved
            write(" }")
        } else {
            write(" {")
            writeNewLine()
            if (block.statements.isNotEmpty()) {
                indentLevel++
                emitBlockStatements(block.statements)
                indentLevel--
            }
            writeIndent()
            write("}")
        }
    }

    private fun emitStatementInline(stmt: Statement) {
        when (stmt) {
            is ReturnStatement -> {
                write("return")
                if (stmt.expression != null) {
                    write(" ")
                    emitExpression(stmt.expression)
                }
                write(";")
            }

            is ExpressionStatement -> {
                emitExpression(stmt.expression)
                write(";")
            }

            is IfStatement -> {
                write("if (")
                emitExpression(stmt.expression)
                write(") ")
                emitStatementInline(stmt.thenStatement)
                if (stmt.elseStatement != null) {
                    write(" else ")
                    emitStatementInline(stmt.elseStatement)
                }
            }

            is Block -> {
                write("{ ")
                for ((i, s) in stmt.statements.withIndex()) {
                    if (i > 0) write(" ")
                    emitStatementInline(s)
                }
                write(" }")
            }

            is ThrowStatement -> {
                write("throw ")
                emitExpression(stmt.expression)
                write(";")
            }

            is VariableStatement -> {
                emitVariableStatement(stmt)
            }

            else -> {
                // Fallback for complex statements
                emitStatement(stmt)
            }
        }
    }

    /**
     * Emits a statement in an embedded context (after `if`, `for`, `while`, etc.).
     * If the statement is a [Block], emits `{ ... }` on the same line. Otherwise,
     * emits the statement indented on the next line.
     *
     * @param trailingOnSameLine if true, the cursor remains on the same line as the
     *   closing brace (used for do-while).
     */
    private fun emitEmbeddedStatement(
        statement: Statement,
        trailingOnSameLine: Boolean = false,
    ) {
        if (statement is Block) {
            emitBlockBody(statement)
            if (!trailingOnSameLine) {
                writeNewLine()
            }
        } else {
            writeNewLine()
            indentLevel++
            emitStatement(statement)
            indentLevel--
        }
    }

    // ---------------------------------------------------------------------------
    // Expression dispatch
    // ---------------------------------------------------------------------------

    private fun emitExpression(node: Expression) {
        when (node) {
            is Identifier -> emitIdentifier(node)
            is StringLiteralNode -> emitStringLiteral(node)
            is NumericLiteralNode -> write(node.text)
            is BigIntLiteralNode -> write(node.text)
            is RegularExpressionLiteralNode -> write(node.text)
            is NoSubstitutionTemplateLiteralNode -> emitNoSubstitutionTemplateLiteral(node)
            is TemplateExpression -> emitTemplateExpression(node)
            is ArrayLiteralExpression -> emitArrayLiteral(node)
            is ObjectLiteralExpression -> emitObjectLiteral(node)
            is PropertyAccessExpression -> emitPropertyAccess(node)
            is ElementAccessExpression -> emitElementAccess(node)
            is CallExpression -> emitCallExpression(node)
            is NewExpression -> emitNewExpression(node)
            is TaggedTemplateExpression -> emitTaggedTemplateExpression(node)
            is TypeAssertionExpression -> emitExpression(node.expression) // type erasure
            is ParenthesizedExpression -> emitParenthesizedExpression(node)
            is FunctionExpression -> emitFunctionExpression(node)
            is ArrowFunction -> emitArrowFunction(node)
            is DeleteExpression -> emitPrefixKeywordExpression("delete", node.expression)
            is TypeOfExpression -> emitPrefixKeywordExpression("typeof", node.expression)
            is VoidExpression -> emitPrefixKeywordExpression("void", node.expression)
            is AwaitExpression -> emitPrefixKeywordExpression("await", node.expression)
            is PrefixUnaryExpression -> emitPrefixUnaryExpression(node)
            is PostfixUnaryExpression -> emitPostfixUnaryExpression(node)
            is BinaryExpression -> emitBinaryExpression(node)
            is ConditionalExpression -> emitConditionalExpression(node)
            is YieldExpression -> emitYieldExpression(node)
            is SpreadElement -> emitSpreadElement(node)
            is ClassExpression -> emitClassExpression(node)
            is AsExpression -> emitExpression(node.expression) // type erasure
            is NonNullExpression -> emitExpression(node.expression) // type erasure
            is SatisfiesExpression -> emitExpression(node.expression) // type erasure
            is MetaProperty -> emitMetaProperty(node)
            is OmittedExpression -> { /* emit nothing */
            }

            is ComputedPropertyName -> emitComputedPropertyName(node)
            is ObjectBindingPattern -> emitObjectBindingPattern(node)
            is ArrayBindingPattern -> emitArrayBindingPattern(node)
        }
    }

    // ---------------------------------------------------------------------------
    // Expression nodes
    // ---------------------------------------------------------------------------

    private fun emitIdentifier(node: Identifier) {
        write(node.text)
    }

    private fun emitStringLiteral(node: StringLiteralNode) {
        val quote = if (node.singleQuote) "'" else "\""
        write(quote)
        write(escapeString(node.text, node.singleQuote))
        write(quote)
    }

    private fun escapeString(text: String, singleQuote: Boolean): String {
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\u0000' -> sb.append("\\0")
                singleQuote && ch == '\'' -> sb.append("\\'")
                !singleQuote && ch == '"' -> sb.append("\\\"")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun emitNoSubstitutionTemplateLiteral(node: NoSubstitutionTemplateLiteralNode) {
        write("`")
        write(node.text)
        write("`")
    }

    private fun emitTemplateExpression(node: TemplateExpression) {
        write("`")
        write(node.head.text)
        for (span in node.templateSpans) {
            write("\${")
            emitExpression(span.expression)
            write("}")
            when (val literal = span.literal) {
                is StringLiteralNode -> write(literal.text)
                else -> { /* should not happen for well-formed spans */
                }
            }
        }
        write("`")
    }

    private fun emitArrayLiteral(node: ArrayLiteralExpression) {
        if (node.multiLine) {
            write("[")
            writeNewLine()
            indentLevel++
            for ((index, element) in node.elements.withIndex()) {
                writeIndent()
                emitExpression(element)
                if (index < node.elements.size - 1) {
                    write(",")
                }
                writeNewLine()
            }
            indentLevel--
            writeIndent()
            write("]")
        } else {
            write("[")
            for ((index, element) in node.elements.withIndex()) {
                if (index > 0) write(", ")
                emitExpression(element)
            }
            write("]")
        }
    }

    private fun emitObjectLiteral(node: ObjectLiteralExpression) {
        if (node.properties.isEmpty()) {
            write("{}")
            return
        }
        val multiline = isObjectLiteralMultiline(node)
        if (multiline) {
            write("{")
            writeNewLine()
            indentLevel++
            for ((index, prop) in node.properties.withIndex()) {
                writeIndent()
                emitObjectProperty(prop)
                if (index < node.properties.size - 1) {
                    write(",")
                }
                writeNewLine()
            }
            indentLevel--
            writeIndent()
            write("}")
        } else {
            write("{ ")
            for ((index, prop) in node.properties.withIndex()) {
                if (index > 0) write(", ")
                emitObjectProperty(prop)
            }
            write(" }")
        }
    }

    private fun emitObjectProperty(prop: Node) {
        when (prop) {
            is PropertyAssignment -> emitPropertyAssignment(prop)
            is ShorthandPropertyAssignment -> emitShorthandPropertyAssignment(prop)
            is SpreadAssignment -> emitSpreadAssignment(prop)
            is MethodDeclaration -> emitMethodInObjectLiteral(prop)
            is GetAccessor -> emitGetAccessorInObjectLiteral(prop)
            is SetAccessor -> emitSetAccessorInObjectLiteral(prop)
            else -> { /* should not happen */
            }
        }
    }

    private fun isObjectLiteralMultiline(node: ObjectLiteralExpression): Boolean {
        return node.multiLine
    }

    private fun emitPropertyAssignment(node: PropertyAssignment) {
        emitPropertyName(node.name)
        write(": ")
        emitExpression(node.initializer)
    }

    private fun emitShorthandPropertyAssignment(node: ShorthandPropertyAssignment) {
        write(node.name.text)
        if (node.objectAssignmentInitializer != null) {
            write(" = ")
            emitExpression(node.objectAssignmentInitializer)
        }
    }

    private fun emitSpreadAssignment(node: SpreadAssignment) {
        write("...")
        emitExpression(node.expression)
    }

    private fun emitMethodInObjectLiteral(node: MethodDeclaration) {
        if (ModifierFlag.Async in node.modifiers) {
            write("async ")
        }
        if (node.asteriskToken) {
            write("*")
        }
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        if (node.body != null) {
            emitBlockBody(node.body)
        }
    }

    private fun emitGetAccessorInObjectLiteral(node: GetAccessor) {
        write("get ")
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        if (node.body != null) {
            emitBlockBody(node.body)
        }
    }

    private fun emitSetAccessorInObjectLiteral(node: SetAccessor) {
        write("set ")
        emitPropertyName(node.name)
        write("(")
        emitParameters(node.parameters)
        write(")")
        if (node.body != null) {
            emitBlockBody(node.body)
        }
    }

    private fun emitPropertyName(name: Expression) {
        when (name) {
            is Identifier -> write(name.text)
            is StringLiteralNode -> emitStringLiteral(name)
            is NumericLiteralNode -> write(name.text)
            is ComputedPropertyName -> emitComputedPropertyName(name)
            else -> emitExpression(name)
        }
    }

    private fun emitComputedPropertyName(node: ComputedPropertyName) {
        write("[")
        emitExpression(node.expression)
        write("]")
    }

    private fun emitPropertyAccess(node: PropertyAccessExpression) {
        emitExpression(node.expression)
        if (node.questionDotToken) {
            write("?.")
        } else {
            write(".")
        }
        write(node.name.text)
    }

    private fun emitElementAccess(node: ElementAccessExpression) {
        emitExpression(node.expression)
        if (node.questionDotToken) {
            write("?.")
        }
        write("[")
        emitExpression(node.argumentExpression)
        write("]")
    }

    private fun emitCallExpression(node: CallExpression) {
        emitExpression(node.expression)
        if (node.questionDotToken) {
            write("?.")
        }
        // type arguments erased
        write("(")
        for ((index, arg) in node.arguments.withIndex()) {
            if (index > 0) write(", ")
            emitExpression(arg)
        }
        write(")")
    }

    private fun emitNewExpression(node: NewExpression) {
        write("new ")
        emitExpression(node.expression)
        // type arguments erased
        if (node.arguments != null) {
            write("(")
            for ((index, arg) in node.arguments.withIndex()) {
                if (index > 0) write(", ")
                emitExpression(arg)
            }
            write(")")
        }
    }

    private fun emitTaggedTemplateExpression(node: TaggedTemplateExpression) {
        emitExpression(node.tag)
        // type arguments erased
        when (val template = node.template) {
            is NoSubstitutionTemplateLiteralNode -> emitNoSubstitutionTemplateLiteral(template)
            is TemplateExpression -> emitTemplateExpression(template)
            else -> { /* should not happen */
            }
        }
    }

    private fun emitParenthesizedExpression(node: ParenthesizedExpression) {
        write("(")
        emitExpression(node.expression)
        write(")")
    }

    private fun emitFunctionExpression(node: FunctionExpression) {
        if (ModifierFlag.Async in node.modifiers) {
            write("async ")
        }
        write("function")
        if (node.asteriskToken) {
            write("*")
        }
        if (node.name != null) {
            write(" ")
            write(node.name.text)
        } else if (!node.asteriskToken) {
            write(" ")
        }
        write("(")
        emitParameters(node.parameters)
        write(")")
        emitBlockBody(node.body, singleLineIfEmpty = true)
    }

    private fun emitArrowFunction(node: ArrowFunction) {
        if (ModifierFlag.Async in node.modifiers) {
            write("async ")
        }
        // Single simple parameter without parens
        val singleSimpleParam = node.parameters.size == 1
                && node.parameters[0].initializer == null
                && !node.parameters[0].dotDotDotToken
                && node.parameters[0].name is Identifier
                && !node.hasParenthesizedParameters
        if (singleSimpleParam) {
            write((node.parameters[0].name as Identifier).text)
        } else {
            write("(")
            emitParameters(node.parameters)
            write(")")
        }
        write(" =>")
        when (val body = node.body) {
            is Block -> {
                if (body.statements.isEmpty() && !body.multiLine) {
                    write(" { }")
                } else {
                    emitBlockBody(body)
                }
            }

            is Expression -> {
                write(" ")
                if (body is ObjectLiteralExpression) {
                    write("(")
                    emitExpression(body)
                    write(")")
                } else {
                    emitExpression(body)
                }
            }

            else -> { /* should not happen */
            }
        }
    }

    private fun emitPrefixKeywordExpression(keyword: String, expression: Expression) {
        write(keyword)
        write(" ")
        emitExpression(expression)
    }

    private fun emitPrefixUnaryExpression(node: PrefixUnaryExpression) {
        write(operatorToString(node.operator))
        emitExpression(node.operand)
    }

    private fun emitPostfixUnaryExpression(node: PostfixUnaryExpression) {
        emitExpression(node.operand)
        write(operatorToString(node.operator))
    }

    private fun emitBinaryExpression(node: BinaryExpression) {
        emitExpression(node.left)
        val op = operatorToString(node.operator)
        if (node.operator == SyntaxKind.InKeyword || node.operator == SyntaxKind.InstanceOfKeyword) {
            write(" $op ")
        } else if (node.operator == SyntaxKind.Comma) {
            write("$op ")
        } else {
            write(" $op ")
        }
        emitExpression(node.right)
    }

    private fun emitConditionalExpression(node: ConditionalExpression) {
        emitExpression(node.condition)
        write(" ? ")
        emitExpression(node.whenTrue)
        write(" : ")
        emitExpression(node.whenFalse)
    }

    private fun emitYieldExpression(node: YieldExpression) {
        write("yield")
        if (node.asteriskToken) {
            write("*")
        }
        if (node.expression != null) {
            write(" ")
            emitExpression(node.expression)
        }
    }

    private fun emitSpreadElement(node: SpreadElement) {
        write("...")
        emitExpression(node.expression)
    }

    private fun emitClassExpression(node: ClassExpression) {
        write("class")
        if (node.name != null) {
            write(" ")
            write(node.name.text)
        }
        // type parameters erased
        emitHeritageClauses(node.heritageClauses)
        write(" {")
        writeNewLine()
        indentLevel++
        emitClassMembers(node.members)
        indentLevel--
        writeIndent()
        write("}")
    }

    private fun emitMetaProperty(node: MetaProperty) {
        when (node.keywordToken) {
            SyntaxKind.NewKeyword -> write("new")
            SyntaxKind.ImportKeyword -> write("import")
            else -> write("unknown")
        }
        write(".")
        write(node.name.text)
    }

    // ---------------------------------------------------------------------------
    // Binding patterns
    // ---------------------------------------------------------------------------

    private fun emitObjectBindingPattern(node: ObjectBindingPattern) {
        write("{ ")
        for ((index, element) in node.elements.withIndex()) {
            if (index > 0) write(", ")
            emitBindingElement(element)
        }
        write(" }")
    }

    private fun emitArrayBindingPattern(node: ArrayBindingPattern) {
        write("[")
        for ((index, element) in node.elements.withIndex()) {
            if (index > 0) write(", ")
            when (element) {
                is BindingElement -> emitBindingElement(element)
                is OmittedExpression -> { /* empty slot */
                }

                else -> { /* should not happen */
                }
            }
        }
        write("]")
    }

    private fun emitBindingElement(node: BindingElement) {
        if (node.dotDotDotToken) {
            write("...")
        }
        if (node.propertyName != null) {
            emitPropertyName(node.propertyName)
            write(": ")
        }
        emitExpression(node.name)
        if (node.initializer != null) {
            write(" = ")
            emitExpression(node.initializer)
        }
    }

    // ---------------------------------------------------------------------------
    // Parameters
    // ---------------------------------------------------------------------------

    private fun emitParameters(parameters: List<Parameter>) {
        // Filter out parameters that are type-only (e.g., `this` parameter in TS)
        val emittableParams = parameters.filter { param ->
            // skip `this` parameter
            !(param.name is Identifier && (param.name as Identifier).text == "this")
        }
        for ((index, param) in emittableParams.withIndex()) {
            if (index > 0) write(", ")
            emitParameter(param)
        }
    }

    private fun emitParameter(node: Parameter) {
        if (node.dotDotDotToken) {
            write("...")
        }
        emitExpression(node.name)
        // skip type annotation
        if (node.initializer != null) {
            write(" = ")
            emitExpression(node.initializer)
        }
    }

    // ---------------------------------------------------------------------------
    // Heritage clauses
    // ---------------------------------------------------------------------------

    private fun emitHeritageClauses(clauses: List<HeritageClause>?) {
        if (clauses == null) return
        for (clause in clauses) {
            // Only emit `extends`, skip `implements` (type-only)
            if (clause.token == SyntaxKind.ImplementsKeyword) continue
            write(" extends ")
            for ((index, type) in clause.types.withIndex()) {
                if (index > 0) write(", ")
                emitExpression(type.expression)
                // type arguments on heritage clause are erased
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Comments
    // ---------------------------------------------------------------------------

    private fun emitLeadingComments(node: Node) {
        if (options.removeComments) return
        val comments = node.leadingComments ?: return
        for (comment in comments) {
            writeIndent()
            write(comment.text)
            writeNewLine()
        }
    }

    private fun emitTrailingComments(node: Node) {
        if (options.removeComments) return
        val comments = node.trailingComments ?: return
        for (comment in comments) {
            write(" ")
            write(comment.text)
        }
    }

    /**
     * Inserts trailing comments before the last newline written by the statement emitter.
     * Statement emitters typically end with `writeNewLine()`, so we back up past
     * the newline, emit the trailing comments, and re-add the newline.
     */
    private fun emitTrailingCommentsBeforeNewline(node: Node) {
        if (options.removeComments) return
        val comments = node.trailingComments ?: return
        // Back up past the trailing newline if present
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.deleteCharAt(sb.length - 1)
            for (comment in comments) {
                write(" ")
                write(comment.text)
            }
            sb.append('\n')
            isStartOfLine = true
        }
    }

    // ---------------------------------------------------------------------------
    // Operator mapping
    // ---------------------------------------------------------------------------

    private fun operatorToString(op: SyntaxKind): String = when (op) {
        SyntaxKind.Plus -> "+"
        SyntaxKind.Minus -> "-"
        SyntaxKind.Asterisk -> "*"
        SyntaxKind.AsteriskAsterisk -> "**"
        SyntaxKind.Slash -> "/"
        SyntaxKind.Percent -> "%"
        SyntaxKind.PlusPlus -> "++"
        SyntaxKind.MinusMinus -> "--"
        SyntaxKind.LessThan -> "<"
        SyntaxKind.GreaterThan -> ">"
        SyntaxKind.LessThanEquals -> "<="
        SyntaxKind.GreaterThanEquals -> ">="
        SyntaxKind.EqualsEquals -> "=="
        SyntaxKind.ExclamationEquals -> "!="
        SyntaxKind.EqualsEqualsEquals -> "==="
        SyntaxKind.ExclamationEqualsEquals -> "!=="
        SyntaxKind.Equals -> "="
        SyntaxKind.PlusEquals -> "+="
        SyntaxKind.MinusEquals -> "-="
        SyntaxKind.AsteriskEquals -> "*="
        SyntaxKind.AsteriskAsteriskEquals -> "**="
        SyntaxKind.SlashEquals -> "/="
        SyntaxKind.PercentEquals -> "%="
        SyntaxKind.LessThanLessThan -> "<<"
        SyntaxKind.GreaterThanGreaterThan -> ">>"
        SyntaxKind.GreaterThanGreaterThanGreaterThan -> ">>>"
        SyntaxKind.LessThanLessThanEquals -> "<<="
        SyntaxKind.GreaterThanGreaterThanEquals -> ">>="
        SyntaxKind.GreaterThanGreaterThanGreaterThanEquals -> ">>>="
        SyntaxKind.Ampersand -> "&"
        SyntaxKind.Bar -> "|"
        SyntaxKind.Caret -> "^"
        SyntaxKind.AmpersandEquals -> "&="
        SyntaxKind.BarEquals -> "|="
        SyntaxKind.CaretEquals -> "^="
        SyntaxKind.AmpersandAmpersand -> "&&"
        SyntaxKind.BarBar -> "||"
        SyntaxKind.QuestionQuestion -> "??"
        SyntaxKind.BarBarEquals -> "||="
        SyntaxKind.AmpersandAmpersandEquals -> "&&="
        SyntaxKind.QuestionQuestionEquals -> "??="
        SyntaxKind.Exclamation -> "!"
        SyntaxKind.Tilde -> "~"
        SyntaxKind.Comma -> ","
        SyntaxKind.InKeyword -> "in"
        SyntaxKind.InstanceOfKeyword -> "instanceof"
        else -> op.name
    }

    // ---------------------------------------------------------------------------
    // Low-level write helpers
    // ---------------------------------------------------------------------------

    private fun write(text: String) {
        if (text.isEmpty()) return
        if (isStartOfLine) {
            // Don't actually indent — text is not expected to start with indent
            // unless writeIndent() was called first.
        }
        sb.append(text)
        isStartOfLine = false
    }

    private fun writeIndent() {
        repeat(indentLevel) {
            sb.append("    ")
        }
        isStartOfLine = false
    }

    private fun writeNewLine() {
        sb.append('\n')
        isStartOfLine = true
    }

    private fun writeLine(text: String = "") {
        if (text.isNotEmpty()) {
            writeIndent()
            sb.append(text)
        }
        writeNewLine()
    }
}
