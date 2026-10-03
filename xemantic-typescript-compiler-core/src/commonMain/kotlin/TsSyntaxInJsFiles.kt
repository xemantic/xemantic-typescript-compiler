/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

/**
 * (INV.0) (P18.279) — TYPESCRIPT-ONLY SYNTAX IN A JAVASCRIPT FILE (TS8xxx): the pass
 * `checkTsSyntaxInJsFiles` and its statement / class-member / parameter / expression walk with
 * the TS8002 / TS8003 / TS8004 / TS8005 / TS8006 / TS8008 / TS8009 / TS8010 / TS8011 / TS8012 /
 * TS8013 / TS8016 / TS8017 emitters. Extracted VERBATIM from `Checker.kt` (one span, 196623-197218, the class's
 * tail); every Checker member it reads is reached through [checker]. Ambient reads:
 * `docs/inversion-ambient-ledger.md` row 21.
 */
internal class TsSyntaxInJsFiles(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    // TS8xxx: TypeScript syntax in JavaScript files
    // -----------------------------------------------------------------------

    fun checkTsSyntaxInJsFiles() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (!fileName.endsWith(".js") && !fileName.endsWith(".jsx")) continue
            val source = result.sourceFile.text
            checkTsSyntaxInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkTsSyntaxInStatements(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) checkTsSyntaxInStatement(stmt, source, fileName)
    }

    private fun checkTsSyntaxInStatement(stmt: Statement, source: String, fileName: String) {
        when (stmt) {
            is InterfaceDeclaration -> {
                emitTs8xxx(stmt.name, "'interface' declarations can only be used in TypeScript files.", 8006, source, fileName)
            }
            is EnumDeclaration -> {
                emitTs8xxx(stmt.name, "'enum' declarations can only be used in TypeScript files.", 8006, source, fileName)
            }
            is ModuleDeclaration -> {
                if (stmt.name is StringLiteralNode) {
                    // `declare module "foo" {}` - skip, this is ambient module declaration
                } else {
                    emitTs8xxx(stmt.name, "'namespace' declarations can only be used in TypeScript files.", 8006, source, fileName)
                }
            }
            is TypeAliasDeclaration -> {
                emitTs8xxx(stmt.name, "Type aliases can only be used in TypeScript files.", 8008, source, fileName)
            }
            is ImportEqualsDeclaration -> {
                val start = stmt.pos
                val importStart = checker.srcIndexOf(source, "import", start)
                if (importStart >= 0) {
                    // Span covers the full statement including semicolon
                    val semiPos = source.indexOf(';', importStart)
                    val stmtLength = if (semiPos >= 0) semiPos - importStart + 1 else stmt.end - importStart
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, importStart)
                    checker.diagnostics.add(Diagnostic(
                        message = "'import ... =' can only be used in TypeScript files.",
                        category = DiagnosticCategory.Error,
                        code = 8002,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = importStart,
                        length = stmtLength,
                    ))
                }
            }
            is ExportAssignment -> {
                if (stmt.isExportEquals) {
                    val start = stmt.pos
                    // Find "export" in source near stmt.pos
                    val exportStart = checker.srcIndexOf(source, "export", start)
                    if (exportStart >= 0) {
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, exportStart)
                        checker.diagnostics.add(Diagnostic(
                            message = "'export =' can only be used in TypeScript files.",
                            category = DiagnosticCategory.Error,
                            code = 8003,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = exportStart,
                            length = 11, // "export = ..."
                        ))
                    }
                }
            }
            is FunctionDeclaration -> {
                // TS8017: Signature declarations without body — takes priority over other TS8xxx
                if (stmt.body == null) {
                    if (stmt.name != null) {
                        emitTs8017(stmt.pos, stmt.end, source, fileName)
                    }
                } else {
                    // Check type parameters (TS8004) — skip JSDoc-derived ones (B5.3)
                    val firstTsTp = stmt.typeParameters?.firstOrNull { !it.fromJSDoc }
                    if (firstTsTp != null) {
                        emitTs8xxx(firstTsTp.name, "Type parameter declarations can only be used in TypeScript files.", 8004, source, fileName)
                    }
                    // Check parameter types (TS8010), optional params (TS8009)
                    checkTsSyntaxInParams(stmt.parameters, source, fileName)
                    // Check return type (TS8010)
                    if (stmt.type != null) {
                        emitTs8xxxForType(stmt.type, source, fileName)
                    }
                    checkTsSyntaxInStatements(stmt.body.statements, source, fileName)
                }
            }
            is ClassDeclaration -> {
                // Check abstract modifier (TS8009) — search backward from class pos since pos may be at 'class' keyword
                if (ModifierFlag.Abstract in stmt.modifiers) {
                    val searchFrom = maxOf(0, stmt.pos - 30)
                    val idx = checker.srcIndexOf(source, "abstract", searchFrom)
                    if (idx >= 0 && idx < stmt.pos + 2) {
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, idx)
                        checker.diagnostics.add(Diagnostic(
                            message = "The 'abstract' modifier can only be used in TypeScript files.",
                            category = DiagnosticCategory.Error,
                            code = 8009,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = idx,
                            length = 8,
                        ))
                    }
                }
                // Check type parameters (TS8004) — skip JSDoc-derived ones (B5.3).
                // Span = the whole `<...>` list contents (tsc createDiagnosticForNodeArray),
                // so `class B<T: BaseA>` squiggles `T: BaseA`, not just `T`.
                val firstClassTsTp = stmt.typeParameters?.firstOrNull { !it.fromJSDoc }
                if (firstClassTsTp != null) {
                    val tpStart = firstClassTsTp.name.pos
                    val tpLen = angleListSpanLength(source, tpStart, firstClassTsTp.name.text.length)
                    val (tpLine, tpChar) = checker.getLineAndCharacterOfPosition(source, tpStart)
                    checker.diagnostics.add(Diagnostic(
                        message = "Type parameter declarations can only be used in TypeScript files.",
                        category = DiagnosticCategory.Error, code = 8004,
                        fileName = fileName, line = tpLine, character = tpChar,
                        start = tpStart, length = tpLen,
                    ))
                }
                // TS8011: type arguments on a heritage base (`class C extends Base<Arg>`)
                // in a JS file. Span = the type-args list contents.
                for (clause in stmt.heritageClauses.orEmpty()) {
                    for (h in clause.types) {
                        val args = h.typeArguments
                        if (!args.isNullOrEmpty()) {
                            val aStart = args.first().pos
                            val aLen = angleListSpanLength(source, aStart, 1)
                            val (aLine, aChar) = checker.getLineAndCharacterOfPosition(source, aStart)
                            checker.diagnostics.add(Diagnostic(
                                message = "Type arguments can only be used in TypeScript files.",
                                category = DiagnosticCategory.Error, code = 8011,
                                fileName = fileName, line = aLine, character = aChar,
                                start = aStart, length = aLen,
                            ))
                        }
                    }
                }
                // Check implements clause (TS8005)
                val hasImplements = stmt.heritageClauses?.any { it.token == SyntaxKind.ImplementsKeyword } == true
                if (hasImplements) {
                    // Find "implements" keyword position — use name pos + length (not name.end which overshoots)
                    val nameEnd = stmt.name?.let { it.pos + ((it).text.length) } ?: stmt.pos
                    val implIdx = checker.srcIndexOf(source, "implements", nameEnd)
                    if (implIdx >= 0) {
                        // Span covers "implements <types>" — find the end of the implements clause
                        val implClause = stmt.heritageClauses.find { it.token == SyntaxKind.ImplementsKeyword }
                        val implEnd = implClause?.let { clause ->
                            // Compute true end from last type in the clause
                            val lastType = clause.types.lastOrNull()
                            if (lastType != null) {
                                val typeExpr = lastType.expression
                                when (typeExpr) {
                                    is Identifier -> typeExpr.pos + typeExpr.text.length
                                    else -> lastType.end
                                }
                            } else implIdx + 10
                        } ?: (implIdx + 10)
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, implIdx)
                        checker.diagnostics.add(Diagnostic(
                            message = "'implements' clauses can only be used in TypeScript files.",
                            category = DiagnosticCategory.Error,
                            code = 8005,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = implIdx,
                            length = implEnd - implIdx,
                        ))
                    }
                }
                for (member in stmt.members) {
                    checkTsSyntaxInClassMember(member, source, fileName)
                }
            }
            is VariableStatement -> {
                // Check for declare modifier (TS8009)
                if (ModifierFlag.Declare in stmt.modifiers) {
                    emitTs8xxxDeclare(stmt.pos, source, fileName)
                }
                // Check variable type annotations and initializer expressions
                for (decl in stmt.declarationList.declarations) {
                    // 17.62: types synthesized from a leading primitive `@type {...}` JSDoc
                    // comment are valid in JS files — skip TS8010 for that case.
                    if (decl.type != null && !decl.typeFromJSDoc) {
                        emitTs8xxxForType(decl.type, source, fileName)
                    }
                    // Walk into initializer to find ClassExpression type params etc.
                    decl.initializer?.let { checkTsSyntaxInExpression(it, source, fileName) }
                }
            }
            is ExpressionStatement -> {
                checkTsSyntaxInExpression(stmt.expression, source, fileName)
            }
            is ReturnStatement -> {
                stmt.expression?.let { checkTsSyntaxInExpression(it, source, fileName) }
            }
            is Block -> checkTsSyntaxInStatements(stmt.statements, source, fileName)
            is IfStatement -> {
                checkTsSyntaxInStatement(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkTsSyntaxInStatement(it, source, fileName) }
            }
            is ForStatement -> checkTsSyntaxInStatement(stmt.statement, source, fileName)
            is ForInStatement -> checkTsSyntaxInStatement(stmt.statement, source, fileName)
            is ForOfStatement -> checkTsSyntaxInStatement(stmt.statement, source, fileName)
            is WhileStatement -> checkTsSyntaxInStatement(stmt.statement, source, fileName)
            is DoStatement -> checkTsSyntaxInStatement(stmt.statement, source, fileName)
            else -> {}
        }
    }

    private fun checkTsSyntaxInClassMember(member: ClassElement, source: String, fileName: String) {
        // Get modifiers and pos from the specific member type
        val modifiers: Set<ModifierFlag>
        val memberPos: Int
        when (member) {
            is MethodDeclaration -> { modifiers = member.modifiers; memberPos = member.pos }
            is Constructor -> { modifiers = member.modifiers; memberPos = member.pos }
            is PropertyDeclaration -> { modifiers = member.modifiers; memberPos = member.pos }
            is GetAccessor -> { modifiers = member.modifiers; memberPos = member.pos }
            is SetAccessor -> { modifiers = member.modifiers; memberPos = member.pos }
            else -> { modifiers = emptySet(); memberPos = 0 }
        }
        // Check access modifiers (public/private/protected) on class members — TS8009
        checkTsSyntaxAccessModifier(modifiers, memberPos, source, fileName)
        // Check abstract modifier on members — TS8009
        if (ModifierFlag.Abstract in modifiers) {
            emitTs8xxxModifier("abstract", memberPos, 8, source, fileName)
        }
        when (member) {
            is MethodDeclaration -> {
                // TS8017: Signature declarations without body — takes priority over other checks
                if (member.body == null) {
                    emitTs8017(memberPos, member.end, source, fileName)
                    return
                }
                // Check optional ? modifier (TS8009)
                if (member.questionToken) {
                    // Find ? after the method name
                    val nameEnd = member.name.pos + ((member.name as? Identifier)?.text?.length ?: 1)
                    val qIdx = source.indexOf('?', nameEnd)
                    if (qIdx >= 0 && qIdx < nameEnd + 5) {
                        emitTs8xxxQuestionToken(qIdx, source, fileName)
                    }
                }
                // Check type parameters (TS8004)
                if (!member.typeParameters.isNullOrEmpty()) {
                    val typeParam = member.typeParameters.first()
                    emitTs8xxx(typeParam.name, "Type parameter declarations can only be used in TypeScript files.", 8004, source, fileName)
                }
                // Check parameter types, optional params
                checkTsSyntaxInParams(member.parameters, source, fileName)
                if (member.type != null) emitTs8xxxForType(member.type, source, fileName)
                member.body.let { checkTsSyntaxInStatements(it.statements, source, fileName) }
            }
            is Constructor -> {
                // TS8017: Constructor signature without body
                if (member.body == null) {
                    val ctorIdx = checker.srcIndexOf(source, "constructor", memberPos)
                    if (ctorIdx >= 0 && ctorIdx < memberPos + 30) {
                        emitTs8017(ctorIdx, member.end, source, fileName)
                    }
                    return
                }
                // Check constructor parameters — types and modifiers (TS8012)
                for (param in member.parameters) {
                    if (param.type != null) emitTs8xxxForType(param.type, source, fileName)
                    if (param.questionToken) {
                        findQuestionTokenPos(param, source)?.let { emitTs8xxxQuestionToken(it, source, fileName) }
                    }
                    // Check parameter modifiers (public/private/etc.) — TS8012
                    if (param.modifiers.any { checker.isParameterPropertyModifier(it) }) {
                        emitTs8xxxParamModifier(param.pos, source, fileName)
                    }
                }
                member.body.let { checkTsSyntaxInStatements(it.statements, source, fileName) }
            }
            is PropertyDeclaration -> {
                // Check optional ? modifier (TS8009)
                if (member.questionToken) {
                    val nameEnd = member.name.pos + ((member.name as? Identifier)?.text?.length ?: 1)
                    val qIdx = source.indexOf('?', nameEnd)
                    if (qIdx >= 0 && qIdx < nameEnd + 5) {
                        emitTs8xxxQuestionToken(qIdx, source, fileName)
                    }
                }
                // 17.58b: types synthesized from a leading `@type {...}` JSDoc comment
                // are valid in JS files — skip the TS8010 emission for that case.
                if (member.type != null && !member.typeFromJSDoc) emitTs8xxxForType(member.type, source, fileName)
                // Check declare modifier (TS8009)
                if (ModifierFlag.Declare in member.modifiers) {
                    emitTs8xxxDeclare(member.pos, source, fileName)
                }
            }
            is GetAccessor -> {
                if (member.type != null) emitTs8xxxForType(member.type, source, fileName)
                member.body?.let { checkTsSyntaxInStatements(it.statements, source, fileName) }
            }
            is SetAccessor -> {
                for (param in member.parameters) {
                    if (param.type != null) emitTs8xxxForType(param.type, source, fileName)
                }
                member.body?.let { checkTsSyntaxInStatements(it.statements, source, fileName) }
            }
            else -> {}
        }
    }

    /** Check parameters for type annotations (TS8010) and optional ? modifiers (TS8009). */
    private fun checkTsSyntaxInParams(params: List<Parameter>, source: String, fileName: String) {
        for (param in params) {
            // 17.140 / B5.2: skip TS8010 for params whose type was synthesized
            // from a JSDoc `@param {T} name` tag — those are valid in JS files.
            if (param.type != null && !param.typeFromJSDoc) emitTs8xxxForType(param.type, source, fileName)
            if (param.questionToken) {
                findQuestionTokenPos(param, source)?.let { emitTs8xxxQuestionToken(it, source, fileName) }
            }
        }
    }

    /** Find the position of the ? token after a parameter name. */
    private fun findQuestionTokenPos(param: Parameter, source: String): Int? {
        val nameEnd = param.name.pos + when (val n = param.name) {
            is Identifier -> n.text.length
            else -> 1
        }
        val qIdx = source.indexOf('?', nameEnd)
        return if (qIdx >= 0 && qIdx < nameEnd + 5) qIdx else null
    }

    /** Walk expression tree to find TypeScript-only syntax (non-null assertions, type assertions, class expressions). */
    private fun checkTsSyntaxInExpression(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is NonNullExpression -> {
                // TS8013: Non-null assertions can only be used in TypeScript files
                // Span covers the entire expression including the `!`
                val start = expr.pos
                val length = checker.expressionTrueEnd(expr) - start
                if (length > 0) {
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                    checker.diagnostics.add(Diagnostic(
                        message = "Non-null assertions can only be used in TypeScript files.",
                        category = DiagnosticCategory.Error,
                        code = 8013,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = length,
                    ))
                }
                checkTsSyntaxInExpression(expr.expression, source, fileName)
            }
            is ClassExpression -> {
                // Check type parameters on class expressions (TS8004)
                if (!expr.typeParameters.isNullOrEmpty()) {
                    val typeParam = expr.typeParameters.first()
                    emitTs8xxx(typeParam.name, "Type parameter declarations can only be used in TypeScript files.", 8004, source, fileName)
                }
                for (member in expr.members) {
                    checkTsSyntaxInClassMember(member, source, fileName)
                }
            }
            is ParenthesizedExpression -> checkTsSyntaxInExpression(expr.expression, source, fileName)
            is BinaryExpression -> {
                // B64.8: iterative left-spine flatten.
                val rightStack = ArrayDeque<Expression>()
                var cur: Expression = expr
                while (cur is BinaryExpression) {
                    rightStack.addLast(cur.right)
                    cur = cur.left
                }
                checkTsSyntaxInExpression(cur, source, fileName)
                while (rightStack.isNotEmpty()) checkTsSyntaxInExpression(rightStack.removeLast(), source, fileName)
            }
            is ConditionalExpression -> {
                checkTsSyntaxInExpression(expr.condition, source, fileName)
                checkTsSyntaxInExpression(expr.whenTrue, source, fileName)
                checkTsSyntaxInExpression(expr.whenFalse, source, fileName)
            }
            is AsExpression -> {
                // TS8016: Type assertion expressions can only be used in TypeScript files
                // Span covers just the type node (e.g., "number" in "0 as number")
                val typeNode = expr.type
                val start = typeNode.pos
                var trueEnd = typeNode.end
                while (trueEnd > start && source.getOrNull(trueEnd - 1)?.let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == ';' || it == ')' || it == '}' || it == ',' } == true) {
                    trueEnd--
                }
                val length = trueEnd - start
                if (length > 0) {
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                    checker.diagnostics.add(Diagnostic(
                        message = "Type assertion expressions can only be used in TypeScript files.",
                        category = DiagnosticCategory.Error,
                        code = 8016,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = length,
                    ))
                }
            }
            else -> {}
        }
    }

    /** Check for public/private/protected access modifiers — TS8009 */
    private fun checkTsSyntaxAccessModifier(modifiers: Set<ModifierFlag>, pos: Int, source: String, fileName: String) {
        if (ModifierFlag.Public in modifiers) {
            emitTs8xxxModifier("public", pos, 6, source, fileName)
        } else if (ModifierFlag.Private in modifiers) {
            emitTs8xxxModifier("private", pos, 7, source, fileName)
        } else if (ModifierFlag.Protected in modifiers) {
            emitTs8xxxModifier("protected", pos, 9, source, fileName)
        }
    }

    /** Emit TS8009 for a modifier keyword found near the given pos. */
    /**
     * (LEGACY.0b step 3) The span of a bodiless SIGNATURE declaration in a JavaScript file,
     * for TS8017. TypeScript 7 builds that diagnostic for the DECLARATION node
     * (`NewDiagnosticForNode(node, …)`), so `function foo();` squiggles all fifteen characters
     * including the `;`; tsc 6 squiggled the NAME alone.
     *
     * [Node.end] overshoots by the following token (its documented behaviour), so the span is
     * the text from [start] to the FIRST `;` inside that window — a bodiless signature cannot
     * legally carry a statement, so the first `;` in it terminates the declaration. With no
     * `;` at all (a recovery shape) the window is trimmed instead.
     */
    private fun signatureDeclarationSpan(start: Int, nodeEnd: Int, source: String): Int {
        val hardEnd = nodeEnd.coerceAtMost(source.length)
        if (hardEnd <= start) return 1
        val text = source.substring(start, hardEnd)
        val semi = text.indexOf(';')
        val length = if (semi >= 0) semi + 1 else text.trimEnd().length
        return length.coerceAtLeast(1)
    }

    /** TS8017 at a bodiless signature declaration, spanning the declaration itself. */
    private fun emitTs8017(start: Int, nodeEnd: Int, source: String, fileName: String) {
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Signature declarations can only be used in TypeScript files.",
            category = DiagnosticCategory.Error,
            code = 8017,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = signatureDeclarationSpan(start, nodeEnd, source),
        ))
    }

    private fun emitTs8xxxModifier(keyword: String, nearPos: Int, keywordLen: Int, source: String, fileName: String) {
        val idx = checker.srcIndexOf(source, keyword, nearPos)
        if (idx < 0 || idx > nearPos + 30) return
        val (line, character) = checker.getLineAndCharacterOfPosition(source, idx)
        checker.diagnostics.add(Diagnostic(
            message = "The '$keyword' modifier can only be used in TypeScript files.",
            category = DiagnosticCategory.Error,
            code = 8009,
            fileName = fileName,
            line = line,
            character = character,
            start = idx,
            length = keywordLen,
        ))
    }

    /** Emit TS8009 for a ? token at the given position. */
    private fun emitTs8xxxQuestionToken(pos: Int, source: String, fileName: String) {
        val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
        checker.diagnostics.add(Diagnostic(
            message = "The '?' modifier can only be used in TypeScript files.",
            category = DiagnosticCategory.Error,
            code = 8009,
            fileName = fileName,
            line = line,
            character = character,
            start = pos,
            length = 1, // "?"
        ))
    }

    /** Emit TS8012 for parameter modifier (public/private/protected/readonly). */
    private fun emitTs8xxxParamModifier(paramPos: Int, source: String, fileName: String) {
        // Find the first modifier keyword near paramPos
        val modifiers = listOf("public" to 6, "private" to 7, "protected" to 9, "readonly" to 8)
        for ((kw, len) in modifiers) {
            val idx = checker.srcIndexOf(source, kw, paramPos)
            if (idx >= 0 && idx < paramPos + 20) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, idx)
                checker.diagnostics.add(Diagnostic(
                    message = "Parameter modifiers can only be used in TypeScript files.",
                    category = DiagnosticCategory.Error,
                    code = 8012,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = idx,
                    length = len,
                ))
                return
            }
        }
    }

    /** Span length of a `<...>`-delimited list from the first element's pos to the
     *  matching `>` (exclusive) — tsc's createDiagnosticForNodeArray span for
     *  TS8004/TS8011. Falls back to [fallbackLen] when the brackets can't be matched. */
    private fun angleListSpanLength(source: String, firstPos: Int, fallbackLen: Int): Int {
        var lt = firstPos - 1
        while (lt >= 0 && source[lt].isWhitespace()) lt--
        if (lt < 0 || source[lt] != '<') return fallbackLen
        var depth = 0
        var j = lt
        while (j < source.length) {
            when (source[j]) {
                '<' -> depth++
                '>' -> { depth--; if (depth == 0) return (j - firstPos).coerceAtLeast(1) }
            }
            j++
        }
        return fallbackLen
    }

    private fun emitTs8xxx(nameNode: Node, message: String, code: Int, source: String, fileName: String) {
        val start = nameNode.pos
        val length = when (nameNode) {
            is Identifier -> nameNode.text.length
            else -> nameNode.end - nameNode.pos
        }
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
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

    private fun emitTs8xxxForType(type: TypeNode, source: String, fileName: String) {
        val start = type.pos
        // type.end overshoots (includes next token's start), so compute true length
        // by trimming trailing whitespace/semicolons/delimiters from the source span
        var trueEnd = type.end
        while (trueEnd > start && source.getOrNull(trueEnd - 1)?.let { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == ';' || it == ')' || it == '}' || it == ',' || it == '{' } == true) {
            trueEnd--
        }
        val length = trueEnd - start
        if (length <= 0) return
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Type annotations can only be used in TypeScript files.",
            category = DiagnosticCategory.Error,
            code = 8010,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    private fun emitTs8xxxDeclare(stmtPos: Int, source: String, fileName: String) {
        // Search backward from stmtPos since stmt.pos may be at keyword after 'declare'
        val searchFrom = maxOf(0, stmtPos - 20)
        val declareIdx = checker.srcIndexOf(source, "declare", searchFrom)
        if (declareIdx < 0 || declareIdx > stmtPos + 20) return
        val (line, character) = checker.getLineAndCharacterOfPosition(source, declareIdx)
        checker.diagnostics.add(Diagnostic(
            message = "The 'declare' modifier can only be used in TypeScript files.",
            category = DiagnosticCategory.Error,
            code = 8009,
            fileName = fileName,
            line = line,
            character = character,
            start = declareIdx,
            length = 7, // "declare"
        ))
    }
}
