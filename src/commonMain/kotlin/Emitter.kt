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
    // When true the next writeIndent() call is a no-op (consumed immediately).
    // Used so that labeled-statement chains (target1: target2: while…) can chain
    // all labels on the same line without the body statement re-indenting.
    private var skipNextIndent = false
    // Original source text, used for multi-line expression formatting decisions.
    private var sourceText: String = ""
    // Tracks whether we are still at the start of the file (before any statement's
    // leading comments have been emitted). Used to preserve /*!...*/ pinned comments
    // at file start even when removeComments=true.
    private var atFileStart = true

    /**
     * Emits the given [sourceFile] as JavaScript and returns the resulting source text.
     */
    fun emit(sourceFile: SourceFile, originalSourceFile: SourceFile? = null): String {
        sourceText = (originalSourceFile ?: sourceFile).text
        emitUseStrict(originalSourceFile ?: sourceFile)
        emitStatements(sourceFile.statements)
        // If the file is a module but all statements were skipped, emit "export {}"
        emitEmptyExportIfNeeded(originalSourceFile ?: sourceFile, sourceFile)
        return sb.toString().trimEnd('\n')
    }

    private fun emitEmptyExportIfNeeded(originalSourceFile: SourceFile, transformedSourceFile: SourceFile) {
        if (!hasModuleStatements(originalSourceFile)) return

        // Only emit export {} for ES module format files (including Node16/NodeNext which use ESM)
        val effectiveModule = options.effectiveModule
        val isESModuleFormat = effectiveModule == ModuleKind.ES2015 ||
                effectiveModule == ModuleKind.ES2020 ||
                effectiveModule == ModuleKind.ES2022 ||
                effectiveModule == ModuleKind.ESNext ||
                effectiveModule == ModuleKind.Node16 ||
                effectiveModule == ModuleKind.NodeNext
        if (!isESModuleFormat) return

        // Emit export {} when the transformed file has no module-level statements remaining.
        // This covers (1) fully erased files and (2) files with non-export code only.
        // TypeScript uses export {} to preserve module semantics even when all export
        // declarations were erased (e.g., export declare class → erased).
        val hasTransformedModuleStatements = transformedSourceFile.statements.any { stmt ->
            if (shouldSkipStatement(stmt)) return@any false
            when (stmt) {
                is ImportDeclaration -> true
                is ExportDeclaration -> {
                    // An ExportDeclaration with NamedExports counts as a module statement only if
                    // at least one specifier is not type-only (i.e., has runtime value).
                    // `export { type A }` or `export {}` (empty) do NOT count.
                    val clause = stmt.exportClause
                    if (clause is NamedExports) clause.elements.any { !it.isTypeOnly }
                    else true // `export *` or `export * as ns` always count
                }
                // In ES module format, `export = X` is not a valid ES module statement
                // (it will be dropped in emitExportAssignment); don't count it here so
                // that `export {}` is emitted to preserve module semantics.
                is ExportAssignment -> !stmt.isExportEquals
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
            }
        }
        if (!hasTransformedModuleStatements) {
            writeLine("export {};")
        }
    }

    private fun hasModuleStatements(sourceFile: SourceFile): Boolean = sourceFile.statements.any {
        it is ImportDeclaration || it is ExportDeclaration || it is ExportAssignment ||
                (it is ImportEqualsDeclaration && it.moduleReference is ExternalModuleReference) ||
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
        // Check if the file is an ES module format — ESM files are inherently strict,
        // no explicit "use strict" needed. Node16/NodeNext produce ES modules.
        val effectiveModule = options.effectiveModule
        val isESModuleFormat = effectiveModule == ModuleKind.ES2015 ||
                effectiveModule == ModuleKind.ES2020 ||
                effectiveModule == ModuleKind.ES2022 ||
                effectiveModule == ModuleKind.ESNext ||
                effectiveModule == ModuleKind.Node16 ||
                effectiveModule == ModuleKind.NodeNext
        if (hasModuleStatements(sourceFile) && isESModuleFormat) return

        // AMD format: for module files, "use strict" goes inside the define() function body.
        // The AMD transformer handles this by inserting it as the first body statement.
        // Non-module AMD files get "use strict" at the top level normally.
        if (effectiveModule == ModuleKind.AMD && hasModuleStatements(sourceFile)) return

        // System format: for module files, "use strict" goes inside the System.register() function body.
        // The System transformer inserts it as the first body statement.
        if (effectiveModule == ModuleKind.System && hasModuleStatements(sourceFile)) return

        // All non-ESM formats (CommonJS, AMD, System, None) always get "use strict"

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
            if (shouldSkipStatement(statement)) {
                // NotEmittedStatement carries orphaned comments from erased declarations
                if (statement is NotEmittedStatement) emitLeadingComments(statement)
                continue
            }
            emitLeadingComments(statement)
            atFileStart = false
            emitStatement(statement)
            emitTrailingCommentsBeforeNewline(statement)
        }
    }

    private fun emitBlockStatements(statements: List<Statement>) {
        for (statement in statements) {
            if (shouldSkipStatement(statement)) {
                if (statement is NotEmittedStatement) emitLeadingComments(statement)
                continue
            }
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
            is DebuggerStatement -> emitDebuggerStatement(statement)
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
            is RawStatement -> {
                sb.append(statement.code)
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
        if (node.declarations.isNotEmpty()) {
            write(" ")
            for ((index, decl) in node.declarations.withIndex()) {
                if (index > 0) write(", ")
                emitVariableDeclaration(decl)
            }
        }
    }

    private fun emitVariableDeclaration(node: VariableDeclaration) {
        emitExpression(node.name)
        // Emit same-line comments between the name/type and `=`
        // e.g. `let e/*c*/: T = v` or `let d: T /*c*/ = v`
        if (!options.removeComments) {
            node.nameTrailingComments?.forEach { write(" "); write(it.text) }
        }
        // skip type annotation
        if (node.initializer != null) {
            write(" = ")
            // Emit inline comment between `=` and initializer value, e.g. `let a = /*c*/ {}`
            if (!options.removeComments) {
                val inlineComment = findInlineInitializerComment(node.initializer)
                if (inlineComment != null) {
                    write(inlineComment.text)
                    write(" ")
                }
            }
            emitExpression(node.initializer)
        }
    }

    /**
     * Finds an inline (same-line) comment attached to the leftmost part of an initializer
     * expression. For a simple expression like [comment] `{}`, the comment is on the object literal
     * itself. For [comment] `d(e)`, it's on the identifier `d` inside the call expression.
     */
    private fun findInlineInitializerComment(expr: Expression): Comment? {
        val own = expr.leadingComments?.firstOrNull { !it.hasPrecedingNewLine }
        if (own != null) return own
        return when (expr) {
            is CallExpression -> findInlineInitializerComment(expr.expression)
            is PropertyAccessExpression -> findInlineInitializerComment(expr.expression)
            is ElementAccessExpression -> findInlineInitializerComment(expr.expression)
            else -> null
        }
    }

    private fun emitExpressionStatement(node: ExpressionStatement) {
        writeIndent()
        emitExpression(node.expression)
        // Emit any inline comments between expression and `;` (e.g. `Array /*3*/;`)
        if (!options.removeComments) {
            node.preSemicolonComments?.filter { !it.hasPrecedingNewLine }?.forEach {
                write(" ")
                write(it.text)
            }
        }
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
                emitBlockBody(block, emitOpenBraceComments = true)
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
                emitTrailingCommentsBeforeNewline(node.thenStatement)
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
            // Synthetic if-statements (pos == -1) with a non-block then-statement emit inline:
            //   if (cond) stmt;
            // This is used by the System module helper (exportStar).
            val stmt = node.thenStatement
            if (node.pos == -1 && stmt !is Block && stmt is ExpressionStatement) {
                write(" ")
                emitExpression(stmt.expression)
                write(";")
                writeNewLine()
            } else {
                emitEmbeddedStatement(node.thenStatement)
            }
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
        write(if (node.condition != null) "; " else ";")
        if (node.condition != null) {
            emitExpression(node.condition)
        }
        write(if (node.incrementor != null) "; " else ";")
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
        emitTrailingComments(node.keywordTrailingComments)
        if (node.label != null) {
            write(" ")
            write(node.label.emitText)
            emitTrailingComments(node.labelTrailingComments)
        }
        write(";")
        writeNewLine()
    }

    private fun emitBreakStatement(node: BreakStatement) {
        writeIndent()
        write("break")
        emitTrailingComments(node.keywordTrailingComments)
        if (node.label != null) {
            write(" ")
            write(node.label.emitText)
            emitTrailingComments(node.labelTrailingComments)
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
        // Emit comments after all clauses but before the closing `}`
        if (!options.removeComments) {
            node.closingComments?.let { comments ->
                indentLevel++
                for (comment in comments) {
                    writeIndent()
                    write(comment.text)
                    writeNewLine()
                }
                indentLevel--
            }
        }
        writeIndent()
        write("}")
        writeNewLine()
        // trailing comments are handled by emitTrailingCommentsBeforeNewline in the outer emitStatements loop
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
            emitTrailingComments(node.statements.last())
            isStartOfLine = saved
            writeNewLine()
        } else {
            // When the single statement is a multiLine block, emit { on the same line as case:
            val singleBlock = node.statements.singleOrNull() as? Block
            if (singleBlock != null && singleBlock.multiLine) {
                emitBlockBody(singleBlock, emitOpenBraceComments = true)
                writeNewLine()
            } else {
                emitTrailingComments(node.labelTrailingComments)
                writeNewLine()
                indentLevel++
                emitBlockStatements(node.statements)
                indentLevel--
            }
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
            emitTrailingComments(node.statements.last())
            isStartOfLine = saved
            writeNewLine()
        } else {
            val singleBlock = node.statements.singleOrNull() as? Block
            if (singleBlock != null && singleBlock.multiLine) {
                emitBlockBody(singleBlock, emitOpenBraceComments = true)
                writeNewLine()
            } else {
                emitTrailingComments(node.labelTrailingComments)
                writeNewLine()
                indentLevel++
                emitBlockStatements(node.statements)
                indentLevel--
            }
        }
        indentLevel--
    }

    private fun emitLabeledStatement(node: LabeledStatement) {
        writeIndent()
        // Chain all nested labels onto the same line: "target1: target2: while …"
        var current: Statement = node
        while (current is LabeledStatement) {
            write(current.label.emitText)
            write(": ")
            current = current.statement
        }
        // Emit the body statement on the same line (suppress its writeIndent call)
        skipNextIndent = true
        emitStatement(current)
    }

    private fun emitThrowStatement(node: ThrowStatement) {
        writeIndent()
        write("throw ")
        if (node.expression != null) {
            emitExpression(node.expression)
        }
        write(";")
        writeNewLine()
        // trailing comments handled by emitTrailingCommentsBeforeNewline in the outer emitStatements loop
    }

    private fun emitTryStatement(node: TryStatement) {
        writeIndent()
        write("try")
        emitBlockBody(node.tryBlock, emitOpenBraceComments = true)
        if (node.catchClause != null) {
            writeNewLine()
            writeIndent()
            write("catch")
            if (node.catchClause.variableDeclaration != null) {
                write(" (")
                emitExpression(node.catchClause.variableDeclaration.name)
                write(")")
            }
            emitBlockBody(node.catchClause.block, emitOpenBraceComments = true)
        }
        if (node.finallyBlock != null) {
            writeNewLine()
            writeIndent()
            write("finally")
            emitBlockBody(node.finallyBlock, emitOpenBraceComments = true)
        }
        writeNewLine()
    }

    private fun emitDebuggerStatement(node: DebuggerStatement) {
        writeIndent()
        write("debugger;")
        writeNewLine()
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
            write(node.name.emitText)
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
            write(node.name.emitText)
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
        // Setters normally cannot have a return type, but emit it as-is for error recovery.
        if (node.type != null) {
            write(": ")
            write(typeNodeToKeywordText(node.type))
        }
        emitBlockBody(node.body)
        writeNewLine()
    }

    /**
     * Converts a [TypeNode] to its keyword text representation.
     * Used for error-recovery cases where type annotations are preserved in JS emit
     * (e.g., a setter with a return type annotation).
     */
    private fun typeNodeToKeywordText(typeNode: TypeNode): String = when (typeNode) {
        is KeywordTypeNode -> KEYWORDS.entries.firstOrNull { it.value == typeNode.kind }?.key ?: "any"
        is TypeReference -> when (val name = typeNode.typeName) {
            is Identifier -> name.emitText
            else -> "any"
        }
        else -> "any"
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
        write(node.name.emitText)
        write(";")
        writeNewLine()
        writeLine("(function (${node.name.emitText}) {")
        indentLevel++
        for (member in node.members) {
            emitEnumMember(node.name.emitText, member)
        }
        indentLevel--
        writeLine("})(${node.name.emitText} || (${node.name.emitText} = {}));")
    }

    private fun emitEnumMember(enumName: String, member: EnumMember) {
        writeIndent()
        val memberName = when (val n = member.name) {
            is Identifier -> n.emitText
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
            is Identifier -> n.emitText
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
        // Named imports are only emitted if there are non-type-only specifiers.
        val namedImports = node.namedBindings as? NamedImports
        val nonTypeSpecifiers = namedImports?.elements?.filter { !it.isTypeOnly } ?: emptyList()
        val hasNamedImports = namedImports != null && nonTypeSpecifiers.isNotEmpty()
        val hasNamespaceImport = node.namedBindings is NamespaceImport
        val hasBindings = hasNamedImports || hasNamespaceImport

        if (hasName) {
            write(node.name.emitText)
            if (hasBindings) {
                write(", ")
            }
        }
        if (hasBindings) {
            when (val bindings = node.namedBindings) {
                is NamespaceImport -> {
                    write("* as ")
                    write(bindings.name.emitText)
                }

                is NamedImports -> {
                    write("{ ")
                    for ((index, specifier) in nonTypeSpecifiers.withIndex()) {
                        if (index > 0) write(", ")
                        if (specifier.propertyName != null) {
                            write(specifier.propertyName.emitText)
                            write(" as ")
                        }
                        write(specifier.name.emitText)
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
        write(node.name.emitText)
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

            is Identifier -> write(node.emitText)
            is QualifiedName -> {
                emitModuleReference(node.left)
                write(".")
                write(node.right.emitText)
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
                val nonTypeSpecifiers = exportClause.elements.filter { !it.isTypeOnly }
                if (nonTypeSpecifiers.isEmpty() && node.moduleSpecifier == null) {
                    // All specifiers are type-only and there's no re-export source — emit `export {}`
                    write(" {}")
                } else {
                    write(" { ")
                    for ((index, specifier) in nonTypeSpecifiers.withIndex()) {
                        if (index > 0) write(", ")
                        if (specifier.propertyName != null) {
                            write(specifier.propertyName.emitText)
                            write(" as ")
                        }
                        write(specifier.name.emitText)
                    }
                    write(" }")
                    if (node.moduleSpecifier != null) {
                        write(" from ")
                        emitExpression(node.moduleSpecifier)
                    }
                }
            }

            is NamespaceExport -> {
                write(" * as ")
                write(exportClause.name.emitText)
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
        if (node.isExportEquals) {
            // `export = X` is a CommonJS-only construct. For ES module format it is
            // simply dropped; TypeScript emits `export {}` to preserve module semantics.
            val effectiveModule = options.effectiveModule
            val isESModuleFormat = effectiveModule == ModuleKind.ES2015 ||
                    effectiveModule == ModuleKind.ES2020 ||
                    effectiveModule == ModuleKind.ES2022 ||
                    effectiveModule == ModuleKind.ESNext
            if (isESModuleFormat) return
            writeIndent()
            write("module.exports = ")
        } else {
            writeIndent()
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
        if (block.statements.isEmpty() && !block.multiLine) {
            write("{ }")
        } else {
            write("{")
            writeNewLine()
            indentLevel++
            emitBlockStatements(block.statements)
            indentLevel--
            writeIndent()
            write("}")
        }
        if (standalone) writeNewLine()
    }

    /**
     * Emits a block `{ ... }` for function/class bodies where the opening brace
     * is placed on the same line as the preceding declaration.
     */
    private fun emitBlockBody(block: Block, singleLineIfEmpty: Boolean = true, emitOpenBraceComments: Boolean = false) {
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
            if (emitOpenBraceComments && !options.removeComments) {
                emitTrailingComments(block.openBraceTrailingComments)
            }
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

            is BreakStatement -> {
                write("break")
                if (stmt.label != null) { write(" "); write(stmt.label.emitText) }
                write(";")
            }

            is ContinueStatement -> {
                write("continue")
                if (stmt.label != null) { write(" "); write(stmt.label.emitText) }
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
                if (stmt.expression != null) {
                    emitExpression(stmt.expression)
                }
                write(";")
            }

            is VariableStatement -> {
                if (ModifierFlag.Export in stmt.modifiers) write("export ")
                emitVariableDeclarationList(stmt.declarationList)
                write(";")
            }

            is DebuggerStatement -> write("debugger;")

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
            // Control-flow bodies (if/while/for) are always emitted multiline,
            // even if the source block was on a single line.
            val forceMultiLine = statement.statements.isNotEmpty()
            emitBlockBody(if (forceMultiLine && !statement.multiLine) statement.copy(multiLine = true) else statement, emitOpenBraceComments = true)
            if (!trailingOnSameLine) {
                writeNewLine()
            }
        } else {
            writeNewLine()
            indentLevel++
            emitStatement(statement)
            emitTrailingCommentsBeforeNewline(statement)
            indentLevel--
        }
    }

    // ---------------------------------------------------------------------------
    // Expression dispatch
    // ---------------------------------------------------------------------------

    private fun emitInlineLeadingComments(node: Node) {
        if (options.removeComments) return
        val comments = node.leadingComments ?: return
        for (comment in comments) {
            if (comment.hasPrecedingNewLine) {
                // Comment is on its own line (e.g. JSDoc before first call argument).
                // End the current line, emit the properly re-indented comment, then
                // position the cursor at the start of the next line so the expression
                // that follows is written at the correct indentation.
                // Always follow with newline+indent (the comment itself is a full line,
                // so the next expression must also start on its own line).
                writeNewLine()
                writeIndent()
                write(reindentComment(comment))
                writeNewLine()
                writeIndent()
            } else {
                write(comment.text)
                if (comment.hasTrailingNewLine) {
                    writeNewLine()
                } else {
                    write(" ")
                }
            }
        }
    }

    private fun emitInlineLeadingComments(comments: List<Comment>?) {
        if (options.removeComments || comments.isNullOrEmpty()) return
        for (comment in comments) {
            write(" ")
            write(comment.text)
        }
    }

    private fun emitExpression(node: Expression) {
        when (node) {
            is Identifier -> emitIdentifier(node)
            is StringLiteralNode -> emitStringLiteral(node)
            is NumericLiteralNode -> {
                write(node.text)
                // Only emit same-line trailing comments (hasPrecedingNewLine=false).
                // Own-line trailing comments are handled by the multiline array emitter.
                if (!options.removeComments) {
                    node.trailingComments?.filter { !it.hasPrecedingNewLine }?.forEach {
                        write(" "); write(it.text)
                    }
                }
            }
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

            is CommaListExpression -> emitCommaListExpression(node)

            is ComputedPropertyName -> emitComputedPropertyName(node)
            is ObjectBindingPattern -> emitObjectBindingPattern(node)
            is ArrayBindingPattern -> emitArrayBindingPattern(node)
        }
    }

    // ---------------------------------------------------------------------------
    // Expression nodes
    // ---------------------------------------------------------------------------

    private fun emitIdentifier(node: Identifier) {
        write(node.emitText)
    }

    private fun emitStringLiteral(node: StringLiteralNode) {
        val quote = if (node.singleQuote) "'" else "\""
        write(quote)
        // rawText preserves original escape sequences (e.g. \u2730) and is already
        // adjusted for the current quote style (singleQuote flag)
        write(if (node.rawText != null) node.rawText else escapeString(node.text, node.singleQuote))
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
            // Check if the first element starts on the same line as `[` (e.g., `[.../* c */\nexpr]`)
            // In that case, emit the first element inline after `[` without initial newline/indent
            val firstElemPos = node.elements.firstOrNull()?.pos ?: -1
            val firstElemOnSameLine = firstElemPos > 0 && node.pos >= 0 && node.pos < firstElemPos &&
                firstElemPos <= sourceText.length &&
                !sourceText.substring(node.pos, firstElemPos).contains('\n')
            write("[")
            if (!firstElemOnSameLine) {
                writeNewLine()
                indentLevel++
            }
            for ((index, element) in node.elements.withIndex()) {
                emitLeadingComments(element)
                if (!firstElemOnSameLine || index > 0) writeIndent()
                emitExpression(element)
                val isLast = index == node.elements.size - 1
                // Split trailing comments: same-line (hasPrecedingNewLine=false) go before comma;
                // own-line (hasPrecedingNewLine=true) go on their own line before ` ,`
                val sameLine = element.trailingComments?.filter { !it.hasPrecedingNewLine }
                val ownLine = element.trailingComments?.filter { it.hasPrecedingNewLine }
                // NumericLiteralNode already emits same-line trailing comments in emitExpression
                if (!options.removeComments && element !is NumericLiteralNode) sameLine?.forEach { write(" "); write(it.text) }
                if (!options.removeComments && !ownLine.isNullOrEmpty()) {
                    // Own-line pre-comma comment: newline, indent, comment, " ,"
                    writeNewLine()
                    for (comment in ownLine) {
                        writeIndent()
                        write(comment.text)
                    }
                    write(" ,")
                } else {
                    if (!isLast || node.hasTrailingComma) write(",")
                }
                writeNewLine()
            }
            // Emit any comments that appeared before the closing `]`
            if (!options.removeComments && node.trailingComments != null) {
                for (comment in node.trailingComments) {
                    if (!comment.hasPrecedingNewLine) {
                        // Same-line comment after the last comma/element: back up before last \n
                        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
                            sb.deleteCharAt(sb.length - 1)
                            write(" ")
                            write(comment.text)
                            sb.append('\n')
                            isStartOfLine = true
                        }
                    } else {
                        // Own-line comment: emit on its own line
                        writeIndent()
                        write(comment.text)
                        writeNewLine()
                    }
                }
            }
            if (!firstElemOnSameLine) indentLevel--
            writeIndent()
            write("]")
        } else {
            write("[")
            // Emit inline comments that appeared right after `[` without a preceding newline
            if (!options.removeComments && node.openBracketComments != null) {
                if (node.elements.isEmpty()) {
                    // Empty array: [ /* comment */]
                    write(" ")
                    for (comment in node.openBracketComments) {
                        write(comment.text)
                    }
                } else {
                    // Non-empty array: [/* comment */ elem, ...]
                    for (comment in node.openBracketComments) {
                        write(comment.text)
                        if (!comment.hasTrailingNewLine) write(" ")
                    }
                }
            }
            for ((index, element) in node.elements.withIndex()) {
                if (index > 0) write(", ")
                emitInlineLeadingComments(element)
                emitExpression(element)
                // NumericLiteralNode already emits same-line trailing comments in emitExpression
                if (element !is NumericLiteralNode) emitTrailingComments(element)
            }
            if (node.hasTrailingComma) write(",")
            emitTrailingComments(node.trailingComments)
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
                val isLast = index == node.properties.size - 1
                // Check if this property continues on the same line as the previous one.
                val sameLineAsPrev = if (index == 0) false else {
                    val prev = node.properties[index - 1]
                    val prevEnd = prev.end; val currStart = prop.pos
                    prevEnd > 0 && currStart > 0 && prevEnd <= currStart && currStart <= sourceText.length &&
                        !sourceText.substring(prevEnd, currStart).contains('\n')
                }
                if (sameLineAsPrev) {
                    write(" ")
                } else {
                    emitLeadingComments(prop)
                    writeIndent()
                }
                emitObjectProperty(prop)
                // Check if next property is on the same line as this one.
                val nextOnSameLine = !isLast && run {
                    val next = node.properties[index + 1]
                    val currEnd = prop.end; val nextStart = next.pos
                    currEnd > 0 && nextStart > 0 && currEnd <= nextStart && nextStart <= sourceText.length &&
                        !sourceText.substring(currEnd, nextStart).contains('\n')
                }
                if (!isLast || node.hasTrailingComma) {
                    write(",")
                }
                if (!nextOnSameLine) {
                    emitTrailingComments(prop)
                    writeNewLine()
                }
            }
            // Emit any comments that appeared before the closing `}`
            if (!options.removeComments && node.trailingComments != null) {
                for (comment in node.trailingComments) {
                    writeIndent()
                    write(comment.text)
                    writeNewLine()
                }
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
        val initComments = if (!options.removeComments) node.initializer.leadingComments else null
        if (!initComments.isNullOrEmpty()) {
            var onNewLine = false
            for (comment in initComments) {
                if (comment.hasPrecedingNewLine && !onNewLine) {
                    writeNewLine()
                    writeIndent()
                }
                write(comment.text)
                if (comment.hasTrailingNewLine) {
                    writeNewLine()
                    writeIndent()
                    onNewLine = true
                } else {
                    write(" ")
                    onNewLine = false
                }
            }
        }
        emitExpression(node.initializer)
        // Emit same-line trailing comments after the value expression, before the comma
        // (e.g. `f: a => 0 /*t1*/,` or `g: (a => 0) /*t2*/,`)
        if (!options.removeComments) {
            node.initializer.trailingComments?.filter { !it.hasPrecedingNewLine }?.forEach {
                write(" "); write(it.text)
            }
        }
    }

    private fun emitShorthandPropertyAssignment(node: ShorthandPropertyAssignment) {
        write(node.name.emitText)
        if (node.objectAssignmentInitializer != null) {
            write(" = ")
            emitExpression(node.objectAssignmentInitializer)
        }
    }

    private fun emitSpreadAssignment(node: SpreadAssignment) {
        write("...")
        emitSpreadComments(node.expression)
        emitExpression(node.expression)
    }

    private fun emitSpreadComments(expr: Expression) {
        if (options.removeComments) return
        val comments = expr.leadingComments ?: return
        for (comment in comments) {
            if (comment.hasPrecedingNewLine) {
                // Own-line comment: newline+indent, then comment
                writeNewLine()
                writeIndent()
                write(comment.text)
                // If comment has trailing newline, next content is on new line; else space before expression
                if (comment.hasTrailingNewLine) {
                    writeNewLine()
                    writeIndent()
                } else {
                    write(" ")
                }
            } else {
                // Same-line comment: space before it
                write(" ")
                write(comment.text)
            }
        }
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
            is Identifier -> write(name.emitText)
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
        if (node.newLineBefore) {
            writeNewLine()
            indentLevel++
            writeIndent()
            indentLevel--
        }
        if (node.questionDotToken) {
            write("?.")
        } else {
            // A plain integer numeric literal followed by `.name` is ambiguous in JS
            // because `1.` is a valid decimal literal.  Emit an extra `.` to fix:
            // NumericLiteralNode("1")  -> write "1" then "." then ".foo"  -> "1..foo"
            val expr = node.expression
            // Extra dot needed for `1.foo` disambiguation, but NOT when the numeric
            // literal has a trailing comment that will be emitted (comments act as
            // whitespace in JS: `1 /*c*/.foo` is unambiguous). When removeComments is
            // true, comments are not emitted so the extra dot is always required.
            val hasEmittedTrailingComment = !options.removeComments && !expr.trailingComments.isNullOrEmpty()
            if (expr is NumericLiteralNode && numericNeedsExtraDot(expr.text)
                && !hasEmittedTrailingComment) {
                write(".")
            }
            write(".")
        }
        if (node.newLineAfterDot) {
            writeNewLine()
            indentLevel++
            writeIndent()
            indentLevel--
        }
        write(node.name.emitText)
    }

    /** True if a numeric literal text needs an extra '.' before property access
     *  to avoid the ambiguity where `1.foo` is parsed as decimal `1.` then identifier. */
    private fun numericNeedsExtraDot(text: String): Boolean =
        !text.contains('.') &&
        !text.lowercase().contains('e') &&
        !text.startsWith("0x") &&
        !text.startsWith("0b") &&
        !text.startsWith("0o")

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
        var firstArg = true
        for (arg in node.arguments) {
            if (arg is OmittedExpression) continue // skip missing arguments
            if (!firstArg) write(", ")
            firstArg = false
            emitInlineLeadingComments(arg)
            emitExpression(arg)
        }
        write(")")
    }

    private fun emitNewExpression(node: NewExpression) {
        write("new ")
        emitInlineLeadingComments(leftmostExpression(node.expression))
        emitExpression(node.expression)
        // type arguments erased
        if (node.arguments != null) {
            write("(")
            var firstArg = true
            for (arg in node.arguments) {
                if (arg is OmittedExpression) continue // skip missing arguments
                if (!firstArg) write(", ")
                firstArg = false
                emitExpression(arg)
            }
            write(")")
        }
    }

    private fun emitTaggedTemplateExpression(node: TaggedTemplateExpression) {
        emitExpression(node.tag)
        write(" ") // TypeScript always emits a space between tag and template
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
        // Emit same-line trailing comments between inner expression and ')' (e.g. `(a => 0 /*t3*/)`)
        if (!options.removeComments) {
            node.expression.trailingComments?.filter { !it.hasPrecedingNewLine }?.forEach {
                write(" "); write(it.text)
            }
        }
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
        } else {
            // Always write space before `(`: `function ()`, `function* ()`, `async function ()`
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
        // Single simple parameter without parens.
        // Async arrow functions always use parens around parameters (TypeScript style).
        val isAsync = ModifierFlag.Async in node.modifiers
        val singleSimpleParam = !isAsync
                && node.parameters.size == 1
                && node.parameters[0].initializer == null
                && !node.parameters[0].dotDotDotToken
                && node.parameters[0].name is Identifier
                && !node.hasParenthesizedParameters
        if (singleSimpleParam) {
            write((node.parameters[0].name as Identifier).emitText)
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
                val bodyComments = if (!options.removeComments) body.leadingComments else null
                if (!bodyComments.isNullOrEmpty()) {
                    // Leading comments on the body expression go on their own line(s)
                    write(" ")
                    writeNewLine()
                    emitLeadingComments(body)
                } else {
                    write(" ")
                }
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
        // For `delete /*2*/ Array.toString`, the `/*2*/` is on the leftmost identifier,
        // not on the outer PropertyAccessExpression. Walk left to find inline comments.
        emitInlineLeadingComments(leftmostExpression(expression))
        // `typeof`/`delete`/`void` have higher precedence than `?:`, so a ConditionalExpression
        // operand must be parenthesized to preserve semantics (e.g. downleveled `?.` chains).
        if (expression is ConditionalExpression) {
            write("(")
            emitExpression(expression)
            write(")")
        } else {
            emitExpression(expression)
        }
    }

    /** Returns the leftmost "leaf" expression (traversing .expression chains). */
    private fun leftmostExpression(expr: Expression): Expression = when (expr) {
        is PropertyAccessExpression -> leftmostExpression(expr.expression)
        is ElementAccessExpression -> leftmostExpression(expr.expression)
        is CallExpression -> leftmostExpression(expr.expression)
        is TaggedTemplateExpression -> leftmostExpression(expr.tag)
        is NonNullExpression -> leftmostExpression(expr.expression)
        else -> expr
    }

    private fun emitPrefixUnaryExpression(node: PrefixUnaryExpression) {
        val opStr = operatorToString(node.operator)
        write(opStr)
        // Avoid `++` or `--` ambiguity: if operator is `+` or `-` and operand is also a
        // prefix unary with the same operator (e.g. `+ +y`), emit a space between them.
        val operand = node.operand
        if ((node.operator == SyntaxKind.Plus || node.operator == SyntaxKind.Minus) &&
            operand is PrefixUnaryExpression && operand.operator == node.operator) {
            write(" ")
        }
        emitExpression(node.operand)
    }

    private fun emitPostfixUnaryExpression(node: PostfixUnaryExpression) {
        emitExpression(node.operand)
        write(operatorToString(node.operator))
    }

    /**
     * Returns true if a [ConditionalExpression] needs wrapping in `()` when used as the right
     * operand of the given binary operator. Ternary has lower precedence than all operators
     * except assignment (`=`, `+=`, …) and comma (`,`).
     */
    private fun rightConditionalNeedsParens(op: SyntaxKind): Boolean = when (op) {
        SyntaxKind.Comma,
        SyntaxKind.Equals, SyntaxKind.PlusEquals, SyntaxKind.MinusEquals,
        SyntaxKind.AsteriskEquals, SyntaxKind.SlashEquals, SyntaxKind.PercentEquals,
        SyntaxKind.AsteriskAsteriskEquals, SyntaxKind.AmpersandEquals,
        SyntaxKind.BarEquals, SyntaxKind.CaretEquals, SyntaxKind.LessThanLessThanEquals,
        SyntaxKind.GreaterThanGreaterThanEquals, SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
        SyntaxKind.BarBarEquals, SyntaxKind.AmpersandAmpersandEquals,
        SyntaxKind.QuestionQuestionEquals -> false
        else -> true
    }

    private fun emitBinaryExpression(node: BinaryExpression) {
        // A ConditionalExpression on the left of a binary operator always needs parentheses
        // because ternary has lower precedence than all binary operators. This arises from
        // downleveled `?.` and `??` transformations where the transformer generates synthetic
        // ConditionalExpression nodes without source-level parentheses.
        if (node.left is ConditionalExpression) {
            write("(")
            emitExpression(node.left)
            write(")")
        } else {
            emitExpression(node.left)
        }
        val op = operatorToString(node.operator)
        // Helper to emit the right operand, adding parens if it's a synthetic ConditionalExpression.
        fun emitRight() {
            if (node.right is ConditionalExpression && rightConditionalNeedsParens(node.operator)) {
                write("(")
                emitExpression(node.right)
                write(")")
            } else {
                emitExpression(node.right)
            }
        }
        if (node.operator == SyntaxKind.InKeyword || node.operator == SyntaxKind.InstanceOfKeyword) {
            write(" $op ")
            emitRight()
        } else if (node.operator == SyntaxKind.Comma) {
            write("$op ")
            emitRight()
        } else if (node.operatorHasPrecedingLineBreak) {
            // Operator is on a new line in the source (possibly with comments before it).
            // Emit: newline, any leading comments, operator at indented position,
            // any inline comments after operator, then right operand.
            val opLeading = if (!options.removeComments) node.operatorLeadingComments else null
            val opTrailing = if (!options.removeComments) node.operatorTrailingComments else null
            val indentStr = "    ".repeat(indentLevel + 1)
            writeNewLine()
            if (opLeading != null && opLeading.isNotEmpty()) {
                for (comment in opLeading) {
                    sb.append(indentStr)
                    write(comment.text)
                    writeNewLine()
                }
            }
            sb.append(indentStr)
            isStartOfLine = false
            write(op)
            // Emit inline comments after the operator (on the same line as operator)
            if (opTrailing != null && opTrailing.isNotEmpty()) {
                for (comment in opTrailing) {
                    write(" ")
                    write(comment.text)
                }
                // If there's a newline between last trailing comment and right operand,
                // emit right on a new extra-indented line
                val lastTrailing = opTrailing.last()
                if (hasNewLineInSource(lastTrailing.end, node.right.pos)) {
                    writeNewLine()
                    repeat(indentLevel + 2) { sb.append("    ") }
                    isStartOfLine = false
                } else {
                    write(" ")
                }
            } else {
                // No trailing comments after operator; check source to see if right is on new line.
                // node.left.end is the position after the operator token (not after left expression),
                // so the range [left.end, right.pos] is the trivia between operator and right operand.
                val rightOnNewLineAfterOp = hasNewLineInSource(node.left.end, node.right.pos)
                if (rightOnNewLineAfterOp) {
                    writeNewLine()
                    repeat(indentLevel + 2) { sb.append("    ") }
                    isStartOfLine = false
                } else {
                    write(" ")
                }
            }
            emitRight()
        } else {
            // Operator is on the same line as left operand (no preceding newline).
            // left.end = position after the left operand token
            // right.pos = start of right operand token (after trivia)
            // If there's a newline between the operator and right operand, the operator stays
            // at end of the current line and the right operand goes to the next indented line.
            // Guard: node.left.end > 0 avoids false positives for synthesized nodes where
            // left.end = 0 (default) but right has a real source position.
            val rightNewLine = node.left.end > 0 && hasNewLineInSource(node.left.end, node.right.pos)
            if (rightNewLine) {
                write(" $op")
                writeNewLine()
                repeat(indentLevel + 1) { sb.append("    ") }
                isStartOfLine = false
            } else {
                write(" $op ")
            }
            emitRight()
        }
    }

    /**
     * Emits a class-expression comma list:
     *   `(_a = class C {\n    },\n    _a.x = 1,\n    _a)`
     * Each element after the first goes on its own line, indented one level.
     */
    /**
     * Emits a class-expression comma list:
     *   `(_a = class C {\n    },\n    _a.x = 1,\n    _a)`
     * Increases indent so the class body closes at the right level, then each
     * subsequent element gets its own line at the same indent.
     */
    private fun emitCommaListExpression(node: CommaListExpression) {
        write("(")
        indentLevel++
        for ((i, elem) in node.elements.withIndex()) {
            emitExpression(elem)
            if (i < node.elements.size - 1) {
                write(",")
                writeNewLine()
                repeat(indentLevel) { sb.append("    ") }
                isStartOfLine = false
            }
        }
        indentLevel--
        write(")")
    }

    private fun emitConditionalExpression(node: ConditionalExpression) {
        // condition.end = pos after '?' (scanner scans '?' as lookahead), so condition.end - 1 = pos of '?'
        // whenTrue.end = pos after ':' (scanner scans ':' as lookahead), so whenTrue.end - 1 = pos of ':'
        val questionMarkPos = node.condition.end - 1
        val colonPos = node.whenTrue.end - 1
        val newlineBeforeQ = hasNewLineInSource(node.condition.pos, questionMarkPos)
        val newlineAfterQ = hasNewLineInSource(node.condition.end, node.whenTrue.pos)
        val newlineBeforeColon = hasNewLineInSource(node.whenTrue.pos, colonPos)
        val newlineAfterColon = hasNewLineInSource(node.whenTrue.end, node.whenFalse.pos)

        val isMultiLine = newlineBeforeQ || newlineAfterQ || newlineBeforeColon || newlineAfterColon
        if (isMultiLine) indentLevel++

        emitExpression(node.condition)
        if (newlineBeforeQ) {
            writeNewLine()
            writeIndent()
            write("? ")
        } else if (newlineAfterQ) {
            write(" ?")
            writeNewLine()
            writeIndent()
        } else {
            write(" ? ")
        }
        emitExpression(node.whenTrue)
        if (newlineBeforeColon) {
            writeNewLine()
            writeIndent()
            write(": ")
        } else if (newlineAfterColon) {
            write(" :")
            writeNewLine()
            writeIndent()
        } else {
            write(" : ")
        }
        emitExpression(node.whenFalse)

        if (isMultiLine) indentLevel--
    }

    /** Returns true if the source text between [startPos] and [endPos] contains a newline. */
    private fun hasNewLineInSource(startPos: Int, endPos: Int): Boolean {
        if (startPos < 0 || endPos < 0 || startPos >= endPos) return false
        val from = startPos.coerceAtMost(sourceText.length)
        val to = endPos.coerceAtMost(sourceText.length)
        if (from >= to) return false
        return sourceText.substring(from, to).contains('\n')
    }

    private fun emitYieldExpression(node: YieldExpression) {
        write("yield")
        if (node.asteriskToken) {
            // Emit any comments between 'yield' and '*' (e.g. `yield /*c*/* expr`)
            emitInlineLeadingComments(node.yieldAsteriskComments)
            write("*")
        }
        if (node.expression != null) {
            write(" ")
            // Emit any inline leading comments on the expression (e.g. `yield /*c*/ expr`)
            emitInlineLeadingComments(leftmostExpression(node.expression))
            emitExpression(node.expression)
        }
    }

    private fun emitSpreadElement(node: SpreadElement) {
        write("...")
        emitSpreadComments(node.expression)
        emitExpression(node.expression)
    }

    private fun emitClassExpression(node: ClassExpression) {
        write("class")
        if (node.name != null) {
            write(" ")
            write(node.name.emitText)
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
        write(node.name.emitText)
    }

    // ---------------------------------------------------------------------------
    // Binding patterns
    // ---------------------------------------------------------------------------

    private fun emitObjectBindingPattern(node: ObjectBindingPattern) {
        if (node.elements.isEmpty()) { write("{}"); return }
        write("{ ")
        for ((index, element) in node.elements.withIndex()) {
            if (index > 0) write(", ")
            emitBindingElement(element)
        }
        if (node.hasTrailingComma) write(",")
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
        // If the last element is an OmittedExpression (elision), emit a trailing comma
        // to distinguish `[a, ,]` (hole at index 1) from `[a,]` (no hole).
        if (node.elements.lastOrNull() is OmittedExpression) {
            write(",")
        }
        write("]")
    }

    private fun emitBindingElement(node: BindingElement) {
        if (node.dotDotDotToken) {
            write("...")
            emitSpreadComments(node.name)
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
            !(param.name is Identifier && param.name.text == "this")
        }
        // Handle comment-only placeholder for empty parameter lists (e.g., `/** nothing */`)
        val placeholder = emittableParams.singleOrNull { it.isCommentPlaceholder }
        if (placeholder != null) {
            if (!options.removeComments) {
                for (comment in placeholder.leadingComments ?: emptyList()) {
                    write(" ")
                    write(comment.text)
                }
            }
            return
        }
        // Use multiline comma-first format when any parameter has block-comment leading comments
        // on their own line (hasPrecedingNewLine=true). Inline comments like /*c1*/ before a param
        // do NOT trigger multiline format — they are emitted inline in the single-line path.
        val anyHasLeadingComments = !options.removeComments && emittableParams.any { param ->
            param.leadingComments?.any { it.hasPrecedingNewLine } == true
        }
        // Also use multiline format when any parameter has newline-separated trailing comments.
        val anyHasNewlineTrailingComments = !options.removeComments && emittableParams.any { param ->
            param.trailingComments?.any { it.hasPrecedingNewLine } == true
        }
        val anyHasLeadingOrTrailingComments = anyHasLeadingComments || anyHasNewlineTrailingComments
        if (anyHasLeadingOrTrailingComments) {
            // Emit parameters with comments using comma-first style:
            // leading comments go on their own lines, then [, ]paramName, then trailing comments.
            // After all params, emit a newline so the closing `)` appears on its own line.
            for ((index, param) in emittableParams.withIndex()) {
                writeNewLine()
                emitLeadingComments(param)
                writeIndent()
                if (index > 0) write(", ")
                emitParameter(param, emitInlineTrailingOnly = false)
                // Emit trailing comments: inline ones stay on same line, newline-separated on their own lines
                val trailing = param.trailingComments
                if (!trailing.isNullOrEmpty()) {
                    for (comment in trailing) {
                        if (comment.hasPrecedingNewLine) {
                            writeNewLine()
                            writeIndent()
                            write(comment.text)
                        } else {
                            write(" ")
                            write(comment.text)
                        }
                    }
                }
            }
            // Newline before the closing `)` so it appears on its own line
            writeNewLine()
            writeIndent()
        } else {
            for ((index, param) in emittableParams.withIndex()) {
                if (index > 0) write(", ")
                // Emit inline leading comments (e.g. `/*c1*/` before parameter name)
                if (!options.removeComments) {
                    param.leadingComments?.filter { !it.hasPrecedingNewLine }?.forEach {
                        write(it.text); write(" ")
                    }
                }
                emitParameter(param)
            }
        }
    }

    private fun emitParameter(node: Parameter, emitInlineTrailingOnly: Boolean = true) {
        if (node.dotDotDotToken) {
            write("...")
            emitTrailingComments(node.dotDotDotTrailingComments)
        }
        emitExpression(node.name)
        // When emitInlineTrailingOnly=true (default), emit trailing comments inline.
        // When false (comma-first / multi-line mode), trailing comments are handled by the caller.
        if (emitInlineTrailingOnly) {
            emitTrailingComments(node.trailingComments)
        }
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
        val comments = node.leadingComments ?: return
        if (options.removeComments) {
            // When removeComments=true, preserve only the first /*!...*/ "pinned" comment
            // at the very start of the file (before any statement has been emitted).
            if (atFileStart && comments.isNotEmpty() && comments[0].text.startsWith("/*!")) {
                val comment = comments[0]
                val commentText = reindentComment(comment)
                writeIndent()
                write(commentText)
                writeNewLine()
            }
            return
        }
        for (comment in comments) {
            val commentText = reindentComment(comment)
            if (!comment.hasTrailingNewLine && comment.kind == SyntaxKind.MultiLineComment) {
                // Inline block comment (e.g. /*comment*/ before a statement on the same line).
                // Emit on the same line; suppress the next writeIndent() so the statement
                // body follows immediately.
                writeIndent()
                write(commentText)
                write(" ")
                skipNextIndent = true
            } else {
                writeIndent()
                write(commentText)
                writeNewLine()
            }
        }
    }

    /**
     * Re-indents a multi-line comment to match the current indentation level.
     * For single-line comments or comments without embedded newlines, returns the text as-is.
     * For block comments like `/** ... */`, adjusts each continuation line's indentation
     * by the delta between the original source indentation and the current emit indentation.
     */
    private fun reindentComment(comment: Comment): String {
        if (comment.kind != SyntaxKind.MultiLineComment) return comment.text
        val text = comment.text
        if (!text.contains('\n')) return text

        // Compute original indentation: spaces before `/*` on its source line
        val origIndent = if (comment.pos >= 0 && comment.pos <= sourceText.length) {
            var lineStart = comment.pos - 1
            while (lineStart >= 0 && sourceText[lineStart] != '\n') lineStart--
            lineStart++
            var spaces = 0
            while (lineStart + spaces < comment.pos && sourceText[lineStart + spaces] == ' ') spaces++
            spaces
        } else 0

        val targetIndent = indentLevel * 4
        val delta = targetIndent - origIndent

        // Re-indent each line after the first and strip trailing whitespace from all lines.
        val lines = text.split('\n')
        return buildString {
            append(lines[0].trimEnd())
            for (i in 1 until lines.size) {
                append('\n')
                val line = lines[i]
                val adjusted = if (delta > 0) {
                    " ".repeat(delta) + line
                } else if (delta < 0) {
                    // Remove up to |delta| leading spaces
                    val toRemove = minOf(-delta, line.length - line.trimStart().length)
                    line.substring(toRemove)
                } else {
                    line
                }
                append(adjusted.trimEnd())
            }
        }
    }

    private fun emitTrailingComments(node: Node) {
        if (options.removeComments) return
        emitTrailingComments(node.trailingComments)
    }

    private fun emitTrailingComments(comments: List<Comment>?) {
        if (options.removeComments) return
        if (comments == null) return
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
        Plus -> "+"
        Minus -> "-"
        Asterisk -> "*"
        AsteriskAsterisk -> "**"
        Slash -> "/"
        Percent -> "%"
        PlusPlus -> "++"
        MinusMinus -> "--"
        LessThan -> "<"
        GreaterThan -> ">"
        LessThanEquals -> "<="
        GreaterThanEquals -> ">="
        EqualsEquals -> "=="
        ExclamationEquals -> "!="
        EqualsEqualsEquals -> "==="
        ExclamationEqualsEquals -> "!=="
        Equals -> "="
        PlusEquals -> "+="
        MinusEquals -> "-="
        AsteriskEquals -> "*="
        AsteriskAsteriskEquals -> "**="
        SlashEquals -> "/="
        PercentEquals -> "%="
        LessThanLessThan -> "<<"
        GreaterThanGreaterThan -> ">>"
        GreaterThanGreaterThanGreaterThan -> ">>>"
        LessThanLessThanEquals -> "<<="
        GreaterThanGreaterThanEquals -> ">>="
        GreaterThanGreaterThanGreaterThanEquals -> ">>>="
        Ampersand -> "&"
        Bar -> "|"
        Caret -> "^"
        AmpersandEquals -> "&="
        BarEquals -> "|="
        CaretEquals -> "^="
        AmpersandAmpersand -> "&&"
        BarBar -> "||"
        QuestionQuestion -> "??"
        BarBarEquals -> "||="
        AmpersandAmpersandEquals -> "&&="
        QuestionQuestionEquals -> "??="
        Exclamation -> "!"
        Tilde -> "~"
        Comma -> ","
        InKeyword -> "in"
        InstanceOfKeyword -> "instanceof"
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
        if (skipNextIndent) { skipNextIndent = false; return }
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
