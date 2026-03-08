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

class Parser(private val source: String, private val fileName: String) {

    private val scanner = Scanner(source)
    private var token: SyntaxKind = SyntaxKind.Unknown
    private val diagnostics = mutableListOf<Diagnostic>()
    private var inAsyncContext = false
    private var disallowIn = false

    fun parse(): SourceFile {
        nextToken()
        val statements = parseStatements()
        // Capture any trailing comments at the end of the file (between last statement and EOF)
        val eofComments = leadingComments()
        val finalStatements = if (eofComments != null) {
            statements + NotEmittedStatement(leadingComments = eofComments, pos = -1, end = -1)
        } else {
            statements
        }
        return SourceFile(
            fileName = fileName,
            statements = finalStatements,
            text = source,
            end = source.length,
        )
    }

    fun getDiagnostics(): List<Diagnostic> = diagnostics.toList()

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun nextToken(): SyntaxKind {
        token = scanner.scan()
        return token
    }

    private fun parseExpected(kind: SyntaxKind): Boolean {
        if (token == kind) {
            nextToken(); return true
        }
        reportError("'${tokenToString(kind)}' expected.")
        return false
    }

    private fun parseOptional(kind: SyntaxKind): Boolean {
        if (token == kind) {
            nextToken(); return true
        }
        return false
    }

    private fun parseSemicolon() {
        if (token == SyntaxKind.Semicolon) {
            nextToken(); return
        }
        // ASI: implicit at }, EOF, or after line break
    }

    private fun canParseSemicolon(): Boolean =
        token == SyntaxKind.Semicolon ||
                token == SyntaxKind.CloseBrace ||
                token == SyntaxKind.EndOfFile ||
                scanner.hasPrecedingLineBreak()

    private fun reportError(message: String) {
        diagnostics.add(
            Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = 1005,
                fileName = fileName,
            )
        )
    }

    private fun getPos(): Int = scanner.getTokenPos()
    private fun getEnd(): Int = scanner.getPos()

    private fun leadingComments(): List<Comment>? = scanner.getLeadingComments()
    private fun trailingComments(): List<Comment>? = scanner.getTrailingComments()

    /** Returns a copy of [node] with [comments] merged into its trailingComments. */
    private fun withTrailingComments(node: Node, comments: List<Comment>): Node {
        val merged = (node.trailingComments.orEmpty() + comments).ifEmpty { null }
        return when (node) {
            is PropertyAssignment -> node.copy(trailingComments = merged)
            is ShorthandPropertyAssignment -> node.copy(trailingComments = merged)
            is SpreadAssignment -> node.copy(trailingComments = merged)
            is MethodDeclaration -> node.copy(trailingComments = merged)
            is GetAccessor -> node.copy(trailingComments = merged)
            is SetAccessor -> node.copy(trailingComments = merged)
            else -> node
        }
    }

    // ── Statement list ──────────────────────────────────────────────────────

    private fun parseStatements(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (token != SyntaxKind.EndOfFile && token != SyntaxKind.CloseBrace) {
            val savedPos = scanner.getTokenPos()
            val stmt = parseStatement()
            // Safety: if no progress was made, skip the current token to avoid infinite loop.
            // Also discard any "statement" produced by error recovery (matches TypeScript's
            // behavior of not emitting missing/error nodes).
            if (scanner.getTokenPos() == savedPos && token != SyntaxKind.EndOfFile) {
                nextToken()
            } else if (stmt != null) {
                stmts.add(stmt)
            }
        }
        return stmts
    }

    // ── Statements ──────────────────────────────────────────────────────────

    private fun parseStatement(): Statement? = when (token) {
        OpenBrace -> parseBlock()
        Semicolon -> parseEmptyStatement()
        VarKeyword, LetKeyword -> parseVariableStatement()
        ConstKeyword -> if (lookAhead { nextToken(); token == EnumKeyword }) {
            nextToken(); parseEnumDeclaration(setOf(ModifierFlag.Const))
        } else {
            parseVariableStatement()
        }
        FunctionKeyword -> parseFunctionDeclarationOrExpression()
        ClassKeyword -> parseClassDeclaration()
        IfKeyword -> parseIfStatement()
        DoKeyword -> parseDoStatement()
        WhileKeyword -> parseWhileStatement()
        ForKeyword -> parseForStatement()
        ContinueKeyword -> parseContinueStatement()
        BreakKeyword -> parseBreakStatement()
        ReturnKeyword -> parseReturnStatement()
        WithKeyword -> parseWithStatement()
        SwitchKeyword -> parseSwitchStatement()
        ThrowKeyword -> parseThrowStatement()
        TryKeyword -> parseTryStatement()
        DebuggerKeyword -> parseDebuggerStatement()
        ImportKeyword -> parseImportDeclaration()
        ExportKeyword -> parseExportDeclaration()
        InterfaceKeyword -> parseInterfaceDeclaration()
        TypeKeyword -> if (isStartOfTypeAlias()) parseTypeAliasDeclaration() else parseExpressionStatement()
        EnumKeyword -> parseEnumDeclaration()
        NamespaceKeyword -> parseModuleDeclaration()
        GlobalKeyword -> {
            // `global { }` is a global augmentation (module declaration)
            val isGlobalAug = lookAhead { nextToken(); token == SyntaxKind.OpenBrace }
            if (isGlobalAug) parseModuleDeclaration() else parseExpressionStatement()
        }
        ModuleKeyword -> {
            // `module.exports = ...` is an expression, not a module declaration
            val isDecl = lookAhead {
                nextToken()
                token != SyntaxKind.Dot && token != SyntaxKind.OpenParen &&
                    token != SyntaxKind.Equals && token != SyntaxKind.Comma &&
                    token != SyntaxKind.CloseParen && token != SyntaxKind.Semicolon &&
                    token != SyntaxKind.EndOfFile
            }
            if (isDecl) parseModuleDeclaration() else parseExpressionStatement()
        }
        AbstractKeyword -> parseAbstractOrDeclaration()
        AsyncKeyword -> parseAsyncOrExpression()
        DeclareKeyword -> {
            // Check if next token could start a declaration. If not, treat 'declare' as an identifier.
            val isDeclare = lookAhead {
                nextToken()
                when (token) {
                    VarKeyword, LetKeyword, ConstKeyword, FunctionKeyword, ClassKeyword,
                    InterfaceKeyword, TypeKeyword, EnumKeyword, NamespaceKeyword, ModuleKeyword,
                    AbstractKeyword, GlobalKeyword, ImportKeyword, ExportKeyword -> true
                    else -> false
                }
            }
            if (isDeclare) parseDeclareDeclaration() else parseExpressionStatement()
        }
        DefaultKeyword -> {
            // `default` without `export` — error recovery: skip and parse the declaration
            nextToken()
            val mods = setOf(ModifierFlag.Default)
            when (token) {
                FunctionKeyword -> parseFunctionDeclarationOrExpression(mods)
                ClassKeyword -> parseClassDeclaration(mods)
                AsyncKeyword -> { nextToken(); parseFunctionDeclarationOrExpression(mods + ModifierFlag.Async) }
                else -> parseExpressionStatement()
            }
        }
        At -> { parseDecorators(); parseStatement() }
        SyntaxKind.LabeledStatement -> null // won't appear as token
        else -> {
            if (isIdentifier() && lookAhead { nextToken(); token == Colon }) {
                parseLabeledStatement()
            } else {
                parseExpressionStatement()
            }
        }
    }

    private fun parseBlock(): Block {
        val pos = getPos()
        val comments = leadingComments()
        val openBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenBrace)
        val openBraceTrailingComments = scanner.consumeTrailingComments()
        val stmts = parseStatements()
        // Capture any comments that appear before the closing `}` (e.g., trailing comments
        // inside an otherwise-empty or comment-terminated block).
        val closingComments = leadingComments()
        val closeBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBrace)
        val multiLine = if (openBracePos in 0..<closeBracePos && closeBracePos <= source.length) {
            source.substring(openBracePos, closeBracePos).contains('\n')
        } else true
        val allStmts = if (closingComments != null) {
            stmts + NotEmittedStatement(leadingComments = closingComments, pos = closeBracePos, end = closeBracePos)
        } else {
            stmts
        }
        return Block(statements = allStmts, multiLine = multiLine, openBraceTrailingComments = openBraceTrailingComments, pos = pos, end = getEnd(), leadingComments = comments)
    }

    private fun parseEmptyStatement(): EmptyStatement {
        val pos = getPos()
        nextToken()
        return EmptyStatement(pos = pos, end = getEnd(), trailingComments = trailingComments())
    }

    private fun parseVariableStatement(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): VariableStatement {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        val declList = parseVariableDeclarationList()
        parseSemicolon()
        val trailing = trailingComments()
        return VariableStatement(
            declarationList = declList,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing
        )
    }

    private fun parseVariableDeclarationList(): VariableDeclarationList {
        val pos = getPos()
        val flags = token
        nextToken() // consume var/let/const
        // Capture inline comments between keyword and first declaration (e.g. `var /*c*/ x`)
        val keywordTrailingComments = scanner.getTrailingComments()?.filter { !it.hasPrecedingNewLine }
        val decls = mutableListOf<VariableDeclaration>()
        // Only parse declarations if the current token can start one (identifier or binding pattern)
        if (isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket) {
            decls.add(parseVariableDeclaration(keywordTrailingComments))
            while (parseOptional(SyntaxKind.Comma)) {
                decls.add(parseVariableDeclaration())
            }
        } else {
            // Report error but produce empty declarations list (e.g. bare `let;`)
            reportError("Identifier expected.")
        }
        return VariableDeclarationList(declarations = decls, flags = flags, pos = pos, end = getEnd())
    }

    private fun parseVariableDeclaration(
        keywordTrailingComments: List<Comment>? = null,
    ): VariableDeclaration {
        val pos = getPos()
        val rawName = parseBindingNameOrPattern()
        // Attach inline keyword-trailing comments (e.g. `var /*c*/ x`) to the identifier
        val name = if (!keywordTrailingComments.isNullOrEmpty() && rawName is Identifier) {
            rawName.copy(leadingComments = keywordTrailingComments)
        } else rawName
        // Capture same-line comments between the name and `:` or `=`
        // e.g. `let e/*c*/: T = v` or `let d: T /*c*/ = v`
        val nameTrailingFromName = scanner.getTrailingComments()?.filter { !it.hasPrecedingNewLine }
        val excl = parseOptional(SyntaxKind.Exclamation)
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val nameTrailingFromType = if (type != null) {
            // Only keep block comments (/* */) as name-trailing; line comments (//) should be
            // handled at statement level (after the semicolon).
            scanner.getTrailingComments()?.filter { !it.hasPrecedingNewLine && !it.text.startsWith("//") }
        } else null
        val nameTrailing = (nameTrailingFromName?.ifEmpty { null } ?: nameTrailingFromType?.ifEmpty { null })
        val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
        return VariableDeclaration(
            name = name,
            type = type,
            initializer = init,
            exclamationToken = excl,
            pos = pos,
            end = getEnd(),
            nameTrailingComments = nameTrailing,
        )
    }

    private fun parseBindingNameOrPattern(): Expression {
        return when (token) {
            OpenBrace -> parseObjectBindingPattern()
            OpenBracket -> parseArrayBindingPattern()
            else -> parseIdentifier()
        }
    }

    private fun parseObjectBindingPattern(): ObjectBindingPattern {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val elements = mutableListOf<BindingElement>()
        var hasTrailingComma = false
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            elements.add(parseBindingElement())
            if (parseOptional(SyntaxKind.Comma)) {
                hasTrailingComma = (token == SyntaxKind.CloseBrace)
            } else {
                hasTrailingComma = false
                break
            }
        }
        parseExpected(SyntaxKind.CloseBrace)
        return ObjectBindingPattern(elements = elements, hasTrailingComma = hasTrailingComma, pos = pos, end = getEnd())
    }

    private fun parseArrayBindingPattern(): ArrayBindingPattern {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBracket)
        val elements = mutableListOf<Node>()
        var trailingComma = false
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            elements.add(parseBindingElement())
            if (!parseOptional(SyntaxKind.Comma)) break
            // Check if the comma we just consumed was a trailing comma
            if (token == SyntaxKind.CloseBracket) {
                trailingComma = true
            }
        }
        parseExpected(SyntaxKind.CloseBracket)
        return ArrayBindingPattern(elements = elements, hasTrailingComma = trailingComma, pos = pos, end = getEnd())
    }

    private fun parseBindingElement(): BindingElement {
        val pos = getPos()
        val dotDotDot = parseOptional(SyntaxKind.DotDotDot)
        val postDotComments = if (dotDotDot) leadingComments() ?: scanner.getTrailingComments() else null
        // Detect computed property name [expr]: x or string/numeric property name "foo": x / 0: x
        val nameOrPropIsPropertyKey = when (token) {
            SyntaxKind.OpenBracket -> lookAhead { parseComputedPropertyName(); token == SyntaxKind.Colon }
            SyntaxKind.StringLiteral -> lookAhead { nextToken(); token == SyntaxKind.Colon }
            SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral -> lookAhead { nextToken(); token == SyntaxKind.Colon }
            else -> false
        }
        var nameOrProp: Expression = when {
            nameOrPropIsPropertyKey && token == SyntaxKind.OpenBracket -> parseComputedPropertyName()
            nameOrPropIsPropertyKey && token == SyntaxKind.StringLiteral -> parseStringLiteral()
            nameOrPropIsPropertyKey && token == SyntaxKind.NumericLiteral -> parseNumericLiteral()
            nameOrPropIsPropertyKey && token == SyntaxKind.BigIntLiteral -> {
                val bPos = getPos(); val text = scanner.getTokenValue(); nextToken()
                BigIntLiteralNode(text = text, pos = bPos, end = getEnd())
            }
            else -> parseBindingNameOrPattern()
        }
        if (postDotComments != null) nameOrProp = nameOrProp.withLeadingComments(postDotComments)
        return if (token == SyntaxKind.Colon &&
            (nameOrProp is Identifier || nameOrProp is ComputedPropertyName
                || nameOrProp is StringLiteralNode || nameOrProp is NumericLiteralNode
                || nameOrProp is BigIntLiteralNode)) {
            nextToken()
            val name = parseBindingNameOrPattern()
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            BindingElement(
                propertyName = nameOrProp,
                name = name,
                initializer = init,
                dotDotDotToken = dotDotDot,
                pos = pos,
                end = getEnd()
            )
        } else {
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            BindingElement(name = nameOrProp, initializer = init, dotDotDotToken = dotDotDot, pos = pos, end = getEnd())
        }
    }

    private fun parseExpressionStatement(): ExpressionStatement {
        val pos = getPos()
        val comments = leadingComments()
        val expr = parseExpression()
        // Capture same-line trailing comments between expression and `;`
        // (e.g. the `/*3*/` in `new Array /*3*/;`) before parseSemicolon advances past them.
        val semiInline = if (!scanner.hasPrecedingLineBreak()) scanner.consumeTrailingComments() else null
        parseSemicolon()
        val trailing = trailingComments()
        return ExpressionStatement(
            expression = expr,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
            preSemicolonComments = semiInline
        )
    }

    private fun parseIfStatement(): IfStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.IfKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        val thenStmt = parseStatement() ?: EmptyStatement()
        // Capture trailing comments from the then-block's closing brace BEFORE checking for else.
        // This way `if (p) { } // err` captures the comment even when `else` follows on the next line.
        // We must read now because nextToken() in parseOptional/ElseKeyword will reset trailingComments.
        // Only capture if thenStmt didn't already capture them (e.g., block statements don't capture trailing).
        val thenTrailing = if (token != SyntaxKind.ElseKeyword && thenStmt.trailingComments == null) {
            trailingComments()
        } else null
        val elseStmt = if (parseOptional(SyntaxKind.ElseKeyword)) parseStatement() else null
        val trailing = if (elseStmt != null) trailingComments() else thenTrailing
        return IfStatement(
            expression = expr,
            thenStatement = thenStmt,
            elseStatement = elseStmt,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseDoStatement(): DoStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.DoKeyword)
        val stmt = parseStatement() ?: EmptyStatement()
        parseExpected(SyntaxKind.WhileKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        parseSemicolon()
        val trailing = trailingComments()
        return DoStatement(statement = stmt, expression = expr, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
    }

    private fun parseWhileStatement(): WhileStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.WhileKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        val stmt = parseStatement() ?: EmptyStatement()
        return WhileStatement(
            expression = expr,
            statement = stmt,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseForStatement(): Statement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.ForKeyword)
        val awaitMod = parseOptional(SyntaxKind.AwaitKeyword)
        parseExpected(SyntaxKind.OpenParen)

        val initializer: Node? = when (token) {
            VarKeyword, LetKeyword, ConstKeyword -> {
                disallowIn = true
                val v = parseVariableDeclarationList()
                disallowIn = false
                v
            }
            Semicolon -> null
            else -> {
                disallowIn = true
                val e = parseExpression()
                disallowIn = false
                e
            }
        }

        if (parseOptional(SyntaxKind.InKeyword)) {
            val expr = parseExpression()
            parseExpected(SyntaxKind.CloseParen)
            val body = parseStatement() ?: EmptyStatement()
            return ForInStatement(
                initializer = initializer ?: Identifier(""),
                expression = expr,
                statement = body,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        if (parseOptional(SyntaxKind.OfKeyword) || (isIdentifier() && scanner.getTokenValue() == "of" && run { nextToken(); true })) {
            val expr = parseAssignmentExpression()
            parseExpected(SyntaxKind.CloseParen)
            val body = parseStatement() ?: EmptyStatement()
            return ForOfStatement(
                awaitModifier = awaitMod,
                initializer = initializer ?: Identifier(""),
                expression = expr,
                statement = body,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        parseExpected(SyntaxKind.Semicolon)
        val condition = if (token != SyntaxKind.Semicolon) parseExpression() else null
        parseExpected(SyntaxKind.Semicolon)
        val incrementor = if (token != SyntaxKind.CloseParen) parseExpression() else null
        parseExpected(SyntaxKind.CloseParen)
        val body = parseStatement() ?: EmptyStatement()
        return ForStatement(
            initializer = initializer,
            condition = condition,
            incrementor = incrementor,
            statement = body,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseContinueStatement(): ContinueStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        val kwTrailing = trailingComments()
        val label = if (!canParseSemicolon() && isIdentifier()) parseIdentifier() else null
        val labelTrailing = if (label != null) trailingComments() else null
        parseSemicolon()
        val trailing = trailingComments()
        return ContinueStatement(
            label = label, keywordTrailingComments = kwTrailing, labelTrailingComments = labelTrailing,
            pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing,
        )
    }

    private fun parseBreakStatement(): BreakStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        val kwTrailing = trailingComments()
        val label = if (!canParseSemicolon() && isIdentifier()) parseIdentifier() else null
        val labelTrailing = if (label != null) trailingComments() else null
        parseSemicolon()
        val trailing = trailingComments()
        return BreakStatement(
            label = label, keywordTrailingComments = kwTrailing, labelTrailingComments = labelTrailing,
            pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing,
        )
    }

    private fun parseReturnStatement(): ReturnStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        val expr = if (!canParseSemicolon()) parseExpression() else null
        parseSemicolon()
        val trailing = trailingComments()
        return ReturnStatement(
            expression = expr,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing
        )
    }

    private fun parseWithStatement(): WithStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.WithKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        val stmt = parseStatement() ?: EmptyStatement()
        return WithStatement(expression = expr, statement = stmt, pos = pos, end = getEnd(), leadingComments = comments)
    }

    private fun parseSwitchStatement(): SwitchStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.SwitchKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        parseExpected(SyntaxKind.OpenBrace)
        val clauses = mutableListOf<Node>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            clauses.add(parseCaseOrDefaultClause())
        }
        // Capture any comments before the closing `}` (e.g. `// Comment After` after last clause)
        val closingComments = scanner.getLeadingComments()
        parseExpected(SyntaxKind.CloseBrace)
        val trailingComments = scanner.getTrailingComments()
        return SwitchStatement(
            expression = expr,
            caseBlock = clauses,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailingComments,
            closingComments = closingComments,
        )
    }

    private fun parseCaseOrDefaultClause(): Node {
        val pos = getPos()
        val comments = leadingComments()
        return if (token == SyntaxKind.CaseKeyword) {
            nextToken()
            val expr = parseExpression()
            parseExpected(SyntaxKind.Colon)
            val labelTrailingComments = scanner.consumeTrailingComments()
            val firstStmtStart = getPos() // scanner.getTokenPos() = start of first statement token
            val stmts = parseCaseClauseStatements()
            // Single-line if no newline between case clause start and first statement,
            // and no statement is a multiLine block (which must be emitted multi-line)
            val singleLine = stmts.size == 1 && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n') &&
                    stmts.none { it is Block && it.multiLine }
            CaseClause(expression = expr, statements = stmts, singleLine = singleLine, pos = pos, end = getEnd(),
                labelTrailingComments = labelTrailingComments, leadingComments = comments)
        } else {
            parseExpected(SyntaxKind.DefaultKeyword)
            parseExpected(SyntaxKind.Colon)
            val labelTrailingComments = scanner.consumeTrailingComments()
            val firstStmtStart = getPos()
            val stmts = parseCaseClauseStatements()
            val singleLine = stmts.size == 1 && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n') &&
                    stmts.none { it is Block && it.multiLine }
            DefaultClause(statements = stmts, singleLine = singleLine, pos = pos, end = getEnd(),
                labelTrailingComments = labelTrailingComments, leadingComments = comments)
        }
    }

    private fun parseCaseClauseStatements(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (token != SyntaxKind.CaseKeyword && token != SyntaxKind.DefaultKeyword &&
            token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile
        ) {
            val savedPos = scanner.getTokenPos()
            val stmt = parseStatement()
            if (stmt != null) stmts.add(stmt)
            if (scanner.getTokenPos() == savedPos && token != SyntaxKind.EndOfFile) {
                nextToken()
            }
        }
        return stmts
    }

    private fun parseThrowStatement(): ThrowStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        // Per spec: "No LineTerminator here" — if a line break precedes the expression, parse no expression.
        val expr = if (!scanner.hasPrecedingLineBreak()) parseExpression() else null
        parseSemicolon()
        val trailing = trailingComments()
        return ThrowStatement(expression = expr, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
    }

    private fun parseTryStatement(): TryStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.TryKeyword)
        val tryBlock = parseBlock()
        val catchClause = if (token == SyntaxKind.CatchKeyword) parseCatchClause() else null
        val finallyBlock = if (parseOptional(SyntaxKind.FinallyKeyword)) parseBlock() else null
        return TryStatement(
            tryBlock = tryBlock,
            catchClause = catchClause,
            finallyBlock = finallyBlock,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseCatchClause(): CatchClause {
        val pos = getPos()
        parseExpected(SyntaxKind.CatchKeyword)
        val varDecl = if (parseOptional(SyntaxKind.OpenParen)) {
            val name = parseBindingNameOrPattern()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val initializer = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            parseExpected(SyntaxKind.CloseParen)
            VariableDeclaration(name = name, type = type, initializer = initializer)
        } else null
        val block = parseBlock()
        return CatchClause(variableDeclaration = varDecl, block = block, pos = pos, end = getEnd())
    }

    private fun parseDebuggerStatement(): DebuggerStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        parseSemicolon()
        val trailing = trailingComments()
        return DebuggerStatement(pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
    }

    private fun parseLabeledStatement(): LabeledStatement {
        val pos = getPos()
        val comments = leadingComments()
        val label = parseIdentifier()
        parseExpected(SyntaxKind.Colon)
        val stmt = parseStatement() ?: EmptyStatement()
        return LabeledStatement(label = label, statement = stmt, pos = pos, end = getEnd(), leadingComments = comments)
    }

    // ── Declarations ────────────────────────────────────────────────────────

    private fun parseFunctionDeclarationOrExpression(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): FunctionDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.FunctionKeyword)
        val asterisk = parseOptional(SyntaxKind.Asterisk)
        val name = if (isIdentifier()) parseIdentifier() else null
        val typeParams = parseTypeParametersOpt()
        val params = parseParameterList()
        val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val savedAsync = inAsyncContext
        inAsyncContext = ModifierFlag.Async in modifiers
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else null
        inAsyncContext = savedAsync
        if (body == null) parseSemicolon()
        val trailing = trailingComments()
        return FunctionDeclaration(
            name = name, typeParameters = typeParams, parameters = params,
            type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk,
            pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing
        )
    }

    private fun parseClassDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): ClassDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.ClassKeyword)
        // `implements` and `extends` always start heritage clauses, never class names
        val name = if (isIdentifier() && token != SyntaxKind.ImplementsKeyword && token != SyntaxKind.ExtendsKeyword) parseIdentifier() else null
        val typeParams = parseTypeParametersOpt()
        val heritage = parseHeritageClauses()
        parseExpected(SyntaxKind.OpenBrace)
        val members = parseClassMembers()
        parseExpected(SyntaxKind.CloseBrace)
        val trailing = trailingComments()
        return ClassDeclaration(
            name = name,
            typeParameters = typeParams,
            heritageClauses = heritage,
            members = members,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing
        )
    }

    private fun parseHeritageClauses(): List<HeritageClause>? {
        val clauses = mutableListOf<HeritageClause>()
        while (token == SyntaxKind.ExtendsKeyword || token == SyntaxKind.ImplementsKeyword) {
            val clauseToken = token
            val pos = getPos()
            nextToken()
            val types = mutableListOf<ExpressionWithTypeArguments>()
            do {
                types.add(parseExpressionWithTypeArguments())
            } while (parseOptional(SyntaxKind.Comma))
            clauses.add(HeritageClause(token = clauseToken, types = types, pos = pos, end = getEnd()))
        }
        return clauses.ifEmpty { null }
    }

    private fun parseExpressionWithTypeArguments(): ExpressionWithTypeArguments {
        val pos = getPos()
        val expr = parseLeftHandSideExpression()
        val typeArgs = parseTypeArgumentsOpt()
        return ExpressionWithTypeArguments(expression = expr, typeArguments = typeArgs, pos = pos, end = getEnd())
    }

    private fun parseClassMembers(): List<ClassElement> {
        val members = mutableListOf<ClassElement>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Semicolon) {
                members.add(SemicolonClassElement(pos = getPos(), end = getEnd()))
                nextToken()
                continue
            }
            val member = parseClassMember()
            if (member != null) members.add(member)
        }
        return members
    }

    private fun parseClassMember(): ClassElement? {
        val pos = getPos()
        val comments = leadingComments()
        val decorators = parseDecorators()
        val modifiers = parseModifiers()

        if (token == SyntaxKind.ConstructorKeyword ||
            (isIdentifier() && scanner.getTokenValue() == "constructor")
        ) {
            return parseConstructor(modifiers, comments, pos)
        }

        val isStatic = ModifierFlag.Static in modifiers
        val asterisk = parseOptional(SyntaxKind.Asterisk)

        if (!asterisk && (token == SyntaxKind.GetKeyword || (isIdentifier() && scanner.getTokenValue() == "get"))) {
            val savedToken = token
            val result = scanner.lookAhead {
                scanner.scan()
                scanner.getToken() != SyntaxKind.OpenParen && scanner.getToken() != SyntaxKind.Colon &&
                        scanner.getToken() != SyntaxKind.Semicolon && scanner.getToken() != SyntaxKind.Equals &&
                        scanner.getToken() != SyntaxKind.Comma && scanner.getToken() != SyntaxKind.CloseBrace &&
                        scanner.getToken() != SyntaxKind.LessThan // get<T>() is a generic method, not a getter
            }
            if (result) {
                nextToken() // skip 'get'
                return parseGetAccessor(modifiers, comments, pos)
            }
        }

        if (!asterisk && (token == SyntaxKind.SetKeyword || (isIdentifier() && scanner.getTokenValue() == "set"))) {
            val result = scanner.lookAhead {
                scanner.scan()
                scanner.getToken() != SyntaxKind.OpenParen && scanner.getToken() != SyntaxKind.Colon &&
                        scanner.getToken() != SyntaxKind.Semicolon && scanner.getToken() != SyntaxKind.Equals &&
                        scanner.getToken() != SyntaxKind.Comma && scanner.getToken() != SyntaxKind.CloseBrace &&
                        scanner.getToken() != SyntaxKind.LessThan // set<T>() is a generic method, not a setter
            }
            if (result) {
                nextToken() // skip 'set'
                return parseSetAccessor(modifiers, comments, pos)
            }
        }

        // static block
        if (isStatic && token == SyntaxKind.OpenBrace) {
            val body = parseBlock()
            return ClassStaticBlockDeclaration(body = body, pos = pos, end = getEnd())
        }

        val name = parsePropertyName()
        val question = parseOptional(SyntaxKind.Question)
        val excl = parseOptional(SyntaxKind.Exclamation)

        return if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            // Method
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val savedAsync = inAsyncContext
            inAsyncContext = ModifierFlag.Async in modifiers
            val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
                parseSemicolon(); null
            }
            inAsyncContext = savedAsync
            val methodTrailing = trailingComments()
            MethodDeclaration(
                name = name, typeParameters = typeParams, parameters = params,
                type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk,
                questionToken = question, decorators = decorators, pos = pos, end = getEnd(),
                leadingComments = comments, trailingComments = methodTrailing,
            )
        } else {
            // Property
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            parseSemicolon()
            val trailing = trailingComments()
            PropertyDeclaration(
                name = name, type = type, initializer = init, modifiers = modifiers,
                questionToken = question, exclamationToken = excl, decorators = decorators,
                pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing
            )
        }
    }

    private fun parseConstructor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): Constructor {
        nextToken() // skip 'constructor'
        val params = parseParameterList()
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            parseSemicolon(); null
        }
        val trailing = trailingComments()
        return Constructor(
            parameters = params,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseGetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): GetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            parseSemicolon(); null
        }
        val trailing = trailingComments()
        return GetAccessor(
            name = name,
            parameters = params,
            type = type,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseSetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): SetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        // Setters cannot have a return type annotation, but parse it for error recovery (preserved in emit).
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            parseSemicolon(); null
        }
        val trailing = trailingComments()
        return SetAccessor(
            name = name,
            parameters = params,
            type = type,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parsePropertyName() = when (token) {
        StringLiteral -> parseStringLiteral()
        NumericLiteral -> parseNumericLiteral()
        BigIntLiteral -> {
            val pos = getPos()
            val text = scanner.getTokenValue(); nextToken()
            BigIntLiteralNode(text = text, pos = pos, end = getEnd())
        }
        OpenBracket -> parseComputedPropertyName()
        Hash -> {
            nextToken(); parseIdentifierName()
        }

        else -> parseIdentifierName()
    }

    private fun parseComputedPropertyName(): ComputedPropertyName {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBracket)
        val expr = parseAssignmentExpression()
        parseExpected(SyntaxKind.CloseBracket)
        return ComputedPropertyName(expression = expr, pos = pos, end = getEnd())
    }

    private fun parseInterfaceDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): InterfaceDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.InterfaceKeyword)
        val name = parseIdentifier()
        val typeParams = parseTypeParametersOpt()
        val heritage = parseHeritageClauses()
        parseExpected(SyntaxKind.OpenBrace)
        val members = parseInterfaceMembers()
        parseExpected(SyntaxKind.CloseBrace)
        return InterfaceDeclaration(
            name = name, typeParameters = typeParams, heritageClauses = heritage,
            members = members, modifiers = modifiers, pos = pos, end = getEnd(), leadingComments = comments
        )
    }

    private fun parseInterfaceMembers(): List<ClassElement> {
        val members = mutableListOf<ClassElement>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            val member = parseTypeMember()
            if (member != null) members.add(member)
            parseOptional(SyntaxKind.Semicolon) || parseOptional(SyntaxKind.Comma)
        }
        return members
    }

    private fun parseTypeMember(): ClassElement? {
        val pos = getPos()
        val modifiers = parseModifiers()

        if (token == SyntaxKind.OpenBracket) {
            // Index signature or computed property
            return parseIndexSignatureOrProperty(modifiers, pos)
        }

        if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            // Call signature
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return MethodDeclaration(
                name = Identifier(""),
                typeParameters = typeParams,
                parameters = params,
                type = type,
                modifiers = modifiers,
                pos = pos,
                end = getEnd()
            )
        }

        if (token == SyntaxKind.NewKeyword) {
            nextToken()
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return MethodDeclaration(
                name = Identifier("new"),
                typeParameters = typeParams,
                parameters = params,
                type = type,
                modifiers = modifiers,
                pos = pos,
                end = getEnd()
            )
        }

        val isGet = (token == SyntaxKind.GetKeyword || (isIdentifier() && scanner.getTokenValue() == "get"))
        val isSet = (token == SyntaxKind.SetKeyword || (isIdentifier() && scanner.getTokenValue() == "set"))

        if (isGet || isSet) {
            val isAccessor = lookAhead {
                nextToken()
                isPropertyNameToken()
            }
            if (isAccessor) {
                nextToken()
                val name = parsePropertyName()
                val params = parseParameterList()
                val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
                return if (isGet) {
                    GetAccessor(
                        name = name,
                        parameters = params,
                        type = type,
                        modifiers = modifiers,
                        pos = pos,
                        end = getEnd()
                    )
                } else {
                    SetAccessor(name = name, parameters = params, modifiers = modifiers, pos = pos, end = getEnd())
                }
            }
        }

        val name = parsePropertyName()
        val question = parseOptional(SyntaxKind.Question)

        if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return MethodDeclaration(
                name = name,
                typeParameters = typeParams,
                parameters = params,
                type = type,
                modifiers = modifiers,
                questionToken = question,
                pos = pos,
                end = getEnd()
            )
        }

        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        return PropertyDeclaration(
            name = name,
            type = type,
            modifiers = modifiers,
            questionToken = question,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseIndexSignatureOrProperty(modifiers: Set<ModifierFlag>, pos: Int): ClassElement {
        // Could be [key: type]: type  or [computed]: type
        val isIndex = scanner.lookAhead {
            scanner.scan() // skip [
            if (!isIdentifier()) return@lookAhead false
            scanner.scan() // skip name
            scanner.getToken() == SyntaxKind.Colon
        }
        if (isIndex) {
            parseExpected(SyntaxKind.OpenBracket)
            val params = mutableListOf<Parameter>()
            val paramName = parseIdentifier()
            parseExpected(SyntaxKind.Colon)
            val paramType = parseType()
            params.add(Parameter(name = paramName, type = paramType))
            parseExpected(SyntaxKind.CloseBracket)
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return IndexSignature(parameters = params, type = type, modifiers = modifiers, pos = pos, end = getEnd())
        }
        val name = parseComputedPropertyName()
        val question = parseOptional(SyntaxKind.Question)
        if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return MethodDeclaration(
                name = name,
                typeParameters = typeParams,
                parameters = params,
                type = type,
                modifiers = modifiers,
                questionToken = question,
                pos = pos,
                end = getEnd()
            )
        }
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        return PropertyDeclaration(
            name = name,
            type = type,
            modifiers = modifiers,
            questionToken = question,
            pos = pos,
            end = getEnd()
        )
    }

    private fun isStartOfTypeAlias(): Boolean = scanner.lookAhead {
        scanner.scan() // skip 'type'
        isIdentifier() && scanner.getToken() != SyntaxKind.Dot
    }

    private fun parseTypeAliasDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): TypeAliasDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.TypeKeyword)
        val name = parseIdentifier()
        val typeParams = parseTypeParametersOpt()
        parseExpected(SyntaxKind.Equals)
        val type = parseType()
        parseSemicolon()
        return TypeAliasDeclaration(
            name = name,
            typeParameters = typeParams,
            type = type,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseEnumDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): EnumDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.EnumKeyword)
        val name = parseIdentifier()
        parseExpected(SyntaxKind.OpenBrace)
        val members = mutableListOf<EnumMember>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            val mPos = getPos()
            val mLeading = leadingComments()
            val mName = parsePropertyName()
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            val mTrailing = scanner.getTrailingComments()
            members.add(EnumMember(
                name = mName, initializer = init, pos = mPos, end = getEnd(),
                leadingComments = mLeading,
                trailingComments = mTrailing,
            ))
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBrace)
        val trailing = trailingComments()
        return EnumDeclaration(
            name = name,
            members = members,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseModuleDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): ModuleDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        nextToken() // skip namespace/module
        val name: Expression = if (token == SyntaxKind.StringLiteral) {
            parseStringLiteral()
        } else {
            var ident: Expression = parseIdentifier()
            while (parseOptional(SyntaxKind.Dot)) {
                val right = parseIdentifier()
                ident = PropertyAccessExpression(expression = ident, name = right, pos = pos, end = getEnd())
            }
            ident
        }
        val body: Node? = if (token == SyntaxKind.OpenBrace) {
            val bPos = getPos()
            parseExpected(SyntaxKind.OpenBrace)
            val stmts = parseStatements()
            // Capture comments before closing `}` (e.g., trailing comments in namespace body)
            val closingComments = leadingComments()
            val closeBracePos = scanner.getTokenPos()
            parseExpected(SyntaxKind.CloseBrace)
            val allStmts = if (closingComments != null) {
                stmts + NotEmittedStatement(leadingComments = closingComments, pos = closeBracePos, end = closeBracePos)
            } else stmts
            ModuleBlock(statements = allStmts, pos = bPos, end = getEnd())
        } else if (token == SyntaxKind.Dot) {
            // nested: namespace A.B { }
            nextToken()
            parseModuleDeclaration(modifiers)
        } else null
        val trailing = trailingComments()
        return ModuleDeclaration(
            name = name,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseImportDeclaration(
        outerModifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): Statement {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.ImportKeyword)

        // import type ...
        val isTypeOnly = token == SyntaxKind.TypeKeyword && scanner.lookAhead {
            scanner.scan()
            isIdentifier() || scanner.getToken() == SyntaxKind.OpenBrace || scanner.getToken() == SyntaxKind.Asterisk
        }
        if (isTypeOnly) nextToken()

        // import = require() or import = X.Y
        if (isIdentifier() && scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.Equals }) {
            val name = parseIdentifier()
            parseExpected(SyntaxKind.Equals)
            val moduleRef: Node =
                if (token == SyntaxKind.RequireKeyword || (isIdentifier() && scanner.getTokenValue() == "require")) {
                    nextToken()
                    parseExpected(SyntaxKind.OpenParen)
                    val expr = parseExpression()
                    parseExpected(SyntaxKind.CloseParen)
                    ExternalModuleReference(expression = expr, pos = pos, end = getEnd())
                } else {
                    parseQualifiedName()
                }
            parseSemicolon()
            val trailing = trailingComments()
            return ImportEqualsDeclaration(
                name = name,
                moduleReference = moduleRef,
                isTypeOnly = isTypeOnly,
                modifiers = outerModifiers,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
                trailingComments = trailing,
            )
        }

        // import "module" (side-effect import)
        if (token == SyntaxKind.StringLiteral) {
            val spec = parseStringLiteral()
            parseSemicolon()
            return ImportDeclaration(moduleSpecifier = spec, pos = pos, end = getEnd(), leadingComments = comments)
        }

        // import clause from "module"
        val clause = parseImportClause(isTypeOnly)
        parseExpected(SyntaxKind.FromKeyword)
        val moduleSpec = parseStringLiteral()
        parseSemicolon()
        return ImportDeclaration(
            importClause = clause,
            moduleSpecifier = moduleSpec,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseImportClause(isTypeOnly: Boolean): ImportClause {
        val pos = getPos()
        var name: Identifier? = null
        var namedBindings: Node? = null

        if (isIdentifier()) {
            name = parseIdentifier()
            if (parseOptional(SyntaxKind.Comma)) {
                namedBindings = parseNamedImportsOrNamespaceImport()
            }
        } else {
            namedBindings = parseNamedImportsOrNamespaceImport()
        }

        return ImportClause(
            name = name,
            namedBindings = namedBindings,
            isTypeOnly = isTypeOnly,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseNamedImportsOrNamespaceImport(): Node {
        return if (token == SyntaxKind.Asterisk) {
            val pos = getPos()
            nextToken()
            parseExpected(SyntaxKind.AsKeyword)
            val name = parseIdentifier()
            NamespaceImport(name = name, pos = pos, end = getEnd())
        } else {
            parseNamedImports()
        }
    }

    private fun parseNamedImports(): NamedImports {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val elements = mutableListOf<ImportSpecifier>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            elements.add(parseImportSpecifier())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBrace)
        return NamedImports(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseImportSpecifier(): ImportSpecifier {
        val pos = getPos()
        val isTypeOnly = isIdentifier() && scanner.getTokenValue() == "type" && scanner.lookAhead {
            scanner.scan()
            isIdentifier()
        }
        if (isTypeOnly) nextToken()

        val first = parseIdentifierName()
        return if (parseOptional(SyntaxKind.AsKeyword)) {
            val name = parseIdentifier()
            ImportSpecifier(propertyName = first, name = name, isTypeOnly = isTypeOnly, pos = pos, end = getEnd())
        } else {
            ImportSpecifier(name = first, isTypeOnly = isTypeOnly, pos = pos, end = getEnd())
        }
    }

    private fun parseExportDeclaration(): Statement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.ExportKeyword)

        // export default
        if (parseOptional(SyntaxKind.DefaultKeyword)) {
            val modifiers = setOf(ModifierFlag.Export, ModifierFlag.Default)
            return when (token) {
                FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
                ClassKeyword -> parseClassDeclaration(modifiers, comments)
                InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
                AbstractKeyword -> {
                    if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.ClassKeyword }) {
                        nextToken()
                        parseClassDeclaration(modifiers + ModifierFlag.Abstract, comments)
                    } else {
                        val expr = parseAssignmentExpression()
                        parseSemicolon()
                        ExportAssignment(
                            expression = expr,
                            isExportEquals = false,
                            modifiers = modifiers,
                            pos = pos,
                            end = getEnd(),
                            leadingComments = comments
                        )
                    }
                }

                AsyncKeyword -> {
                    // `export default async function foo()` — parse as FunctionDeclaration with Async modifier
                    // `export default async function*` — same
                    nextToken()
                    parseFunctionDeclarationOrExpression(modifiers + ModifierFlag.Async, comments)
                }

                else -> {
                    val expr = parseAssignmentExpression()
                    parseSemicolon()
                    ExportAssignment(
                        expression = expr,
                        isExportEquals = false,
                        modifiers = modifiers,
                        pos = pos,
                        end = getEnd(),
                        leadingComments = comments
                    )
                }
            }
        }

        // export =
        if (parseOptional(SyntaxKind.Equals)) {
            val expr = parseAssignmentExpression()
            parseSemicolon()
            return ExportAssignment(
                expression = expr,
                isExportEquals = true,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        val isTypeOnly = token == SyntaxKind.TypeKeyword && scanner.lookAhead {
            scanner.scan()
            scanner.getToken() == SyntaxKind.OpenBrace || scanner.getToken() == SyntaxKind.Asterisk
        }
        if (isTypeOnly) nextToken()

        // export * from "module"
        if (token == SyntaxKind.Asterisk) {
            nextToken()
            val nsExport = if (parseOptional(SyntaxKind.AsKeyword)) {
                NamespaceExport(name = parseIdentifier(), pos = pos, end = getEnd())
            } else null
            parseExpected(SyntaxKind.FromKeyword)
            val spec = parseStringLiteral()
            parseSemicolon()
            return ExportDeclaration(
                exportClause = nsExport,
                moduleSpecifier = spec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        // export { ... } from? "module"
        if (token == SyntaxKind.OpenBrace) {
            val namedExports = parseNamedExports()
            val moduleSpec = if (parseOptional(SyntaxKind.FromKeyword)) parseStringLiteral() else null
            parseSemicolon()
            return ExportDeclaration(
                exportClause = namedExports,
                moduleSpecifier = moduleSpec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        // export var/let/const/function/class/interface/type/enum/namespace/declare/abstract/async/import
        val modifiers = setOf(ModifierFlag.Export)
        return when (token) {
            VarKeyword, LetKeyword -> parseVariableStatement(modifiers, comments)
            ConstKeyword -> if (lookAhead { nextToken(); token == EnumKeyword }) {
                nextToken(); parseEnumDeclaration(modifiers + ModifierFlag.Const, comments)
            } else {
                parseVariableStatement(modifiers, comments)
            }

            FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
            ClassKeyword -> parseClassDeclaration(modifiers, comments)
            InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
            TypeKeyword -> parseTypeAliasDeclaration(modifiers, comments)
            EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            NamespaceKeyword, ModuleKeyword -> parseModuleDeclaration(modifiers, comments)
            DeclareKeyword -> parseDeclareDeclaration(modifiers, comments)
            AbstractKeyword -> {
                nextToken(); parseClassDeclaration(modifiers + ModifierFlag.Abstract, comments)
            }

            AsyncKeyword -> {
                nextToken(); parseFunctionDeclarationOrExpression(modifiers + ModifierFlag.Async, comments)
            }

            ImportKeyword -> parseImportDeclaration(modifiers, comments)

            // export export ... (duplicate export keyword — error recovery)
            ExportKeyword -> {
                val inner = parseExportDeclaration()
                // The inner already has Export modifier; just return it
                inner
            }

            else -> parseExpressionStatement()
        }
    }

    private fun parseNamedExports(): NamedExports {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val elements = mutableListOf<ExportSpecifier>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            elements.add(parseExportSpecifier())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBrace)
        return NamedExports(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseExportSpecifier(): ExportSpecifier {
        val pos = getPos()
        val isTypeOnly = isIdentifier() && scanner.getTokenValue() == "type" && scanner.lookAhead {
            scanner.scan()
            isIdentifier()
        }
        if (isTypeOnly) nextToken()
        val first = parseIdentifierName()
        return if (parseOptional(SyntaxKind.AsKeyword)) {
            ExportSpecifier(
                propertyName = first,
                name = parseIdentifierName(),
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd()
            )
        } else {
            ExportSpecifier(name = first, isTypeOnly = isTypeOnly, pos = pos, end = getEnd())
        }
    }

    private fun parseAbstractOrDeclaration(): Statement? {
        val comments = leadingComments()
        val pos = getPos()
        nextToken() // skip 'abstract'
        return when {
            token == SyntaxKind.ClassKeyword && !scanner.hasPrecedingLineBreak() ->
                parseClassDeclaration(setOf(ModifierFlag.Abstract), comments)

            else -> {
                // Treat as identifier expression (ASI before class keyword)
                val id = Identifier(text = "abstract", pos = pos, end = getEnd())
                return finishExpressionStatement(id, pos, comments)
            }
        }
    }

    private fun parseAsyncOrExpression(): Statement {
        val pos = getPos()
        val comments = leadingComments()
        val isAsync = scanner.lookAhead {
            scanner.scan()
            !scanner.hasPrecedingLineBreak() && scanner.getToken() == SyntaxKind.FunctionKeyword
        }
        if (isAsync) {
            nextToken() // skip 'async'
            return parseFunctionDeclarationOrExpression(setOf(ModifierFlag.Async), comments)
        }
        return parseExpressionStatement()
    }

    private fun parseDeclareDeclaration(
        existingModifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): Statement {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        nextToken() // skip 'declare'
        val modifiers = existingModifiers + ModifierFlag.Declare
        return when (token) {
            VarKeyword, LetKeyword -> parseVariableStatement(modifiers, comments)
            ConstKeyword -> if (lookAhead { nextToken(); token == EnumKeyword }) {
                nextToken(); parseEnumDeclaration(modifiers + ModifierFlag.Const, comments)
            } else {
                parseVariableStatement(modifiers, comments)
            }

            FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
            ClassKeyword -> parseClassDeclaration(modifiers, comments)
            InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
            TypeKeyword -> parseTypeAliasDeclaration(modifiers, comments)
            EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            NamespaceKeyword, ModuleKeyword -> parseModuleDeclaration(modifiers, comments)
            AbstractKeyword -> {
                nextToken(); parseClassDeclaration(modifiers + ModifierFlag.Abstract, comments)
            }

            GlobalKeyword -> parseModuleDeclaration(modifiers, comments)
            ImportKeyword -> parseImportDeclaration(modifiers, comments)

            // `declare export function/class/...` — add Export modifier and recurse
            ExportKeyword -> {
                nextToken() // skip 'export'
                val mods = modifiers + ModifierFlag.Export
                when (token) {
                    DefaultKeyword -> {
                        nextToken() // skip 'default'
                        val mods2 = mods + ModifierFlag.Default
                        when (token) {
                            FunctionKeyword -> parseFunctionDeclarationOrExpression(mods2, comments)
                            ClassKeyword -> parseClassDeclaration(mods2, comments)
                            AsyncKeyword -> {
                                nextToken()
                                parseFunctionDeclarationOrExpression(mods2 + ModifierFlag.Async, comments)
                            }
                            else -> parseVariableStatement(mods2, comments)
                        }
                    }
                    FunctionKeyword -> parseFunctionDeclarationOrExpression(mods, comments)
                    ClassKeyword -> parseClassDeclaration(mods, comments)
                    InterfaceKeyword -> parseInterfaceDeclaration(mods, comments)
                    TypeKeyword -> parseTypeAliasDeclaration(mods, comments)
                    EnumKeyword -> parseEnumDeclaration(mods, comments)
                    NamespaceKeyword, ModuleKeyword -> parseModuleDeclaration(mods, comments)
                    AbstractKeyword -> { nextToken(); parseClassDeclaration(mods + ModifierFlag.Abstract, comments) }
                    AsyncKeyword -> { nextToken(); parseFunctionDeclarationOrExpression(mods + ModifierFlag.Async, comments) }
                    else -> parseVariableStatement(mods, comments)
                }
            }

            else -> parseVariableStatement(modifiers, comments)
        }
    }

    private fun finishExpressionStatement(expr: Expression, pos: Int, comments: List<Comment>?): ExpressionStatement {
        val fullExpr = parseExpressionRest(expr)
        parseSemicolon()
        return ExpressionStatement(expression = fullExpr, pos = pos, end = getEnd(), leadingComments = comments)
    }

    // ── Modifiers & Decorators ──────────────────────────────────────────────

    private fun parseModifiers(): Set<ModifierFlag> {
        val mods = mutableSetOf<ModifierFlag>()
        loop@ while (true) {
            val mod = when {
                token == SyntaxKind.PublicKeyword -> ModifierFlag.Public
                token == SyntaxKind.PrivateKeyword -> ModifierFlag.Private
                token == SyntaxKind.ProtectedKeyword -> ModifierFlag.Protected
                token == SyntaxKind.StaticKeyword -> ModifierFlag.Static
                token == SyntaxKind.AbstractKeyword -> ModifierFlag.Abstract
                token == SyntaxKind.ReadonlyKeyword -> ModifierFlag.Readonly
                token == SyntaxKind.OverrideKeyword -> ModifierFlag.Override
                token == SyntaxKind.AsyncKeyword -> ModifierFlag.Async
                token == SyntaxKind.DeclareKeyword -> ModifierFlag.Declare
                token == SyntaxKind.ExportKeyword -> ModifierFlag.Export
                token == SyntaxKind.DefaultKeyword -> ModifierFlag.Default
                token == SyntaxKind.ConstKeyword -> ModifierFlag.Const
                token == SyntaxKind.AccessorKeyword -> ModifierFlag.Accessor
                isIdentifier() && scanner.getTokenValue() == "readonly" -> ModifierFlag.Readonly
                isIdentifier() && scanner.getTokenValue() == "abstract" -> ModifierFlag.Abstract
                isIdentifier() && scanner.getTokenValue() == "override" -> ModifierFlag.Override
                isIdentifier() && scanner.getTokenValue() == "async" -> ModifierFlag.Async
                isIdentifier() && scanner.getTokenValue() == "declare" -> ModifierFlag.Declare
                isIdentifier() && scanner.getTokenValue() == "accessor" -> ModifierFlag.Accessor
                else -> break@loop
            }
            mods.add(mod)
            nextToken()
        }
        return mods
    }

    private fun parseObjectLiteralModifiers(): Set<ModifierFlag> {
        // In object literals, modifier keywords can also be property names.
        // Only consume a modifier if the NEXT token after it is a property name
        // (not `:`, `,`, `}`, `?`, `(`, `!` which indicate it's a property name itself).
        val mods = mutableSetOf<ModifierFlag>()
        loop@ while (true) {
            val mod = when {
                token == SyntaxKind.AsyncKeyword || (isIdentifier() && scanner.getTokenValue() == "async") -> ModifierFlag.Async
                token == SyntaxKind.PublicKeyword -> ModifierFlag.Public
                token == SyntaxKind.PrivateKeyword -> ModifierFlag.Private
                token == SyntaxKind.ProtectedKeyword -> ModifierFlag.Protected
                token == SyntaxKind.StaticKeyword -> ModifierFlag.Static
                token == SyntaxKind.AbstractKeyword -> ModifierFlag.Abstract
                token == SyntaxKind.ReadonlyKeyword -> ModifierFlag.Readonly
                token == SyntaxKind.OverrideKeyword -> ModifierFlag.Override
                isIdentifier() && scanner.getTokenValue() == "readonly" -> ModifierFlag.Readonly
                isIdentifier() && scanner.getTokenValue() == "abstract" -> ModifierFlag.Abstract
                isIdentifier() && scanner.getTokenValue() == "override" -> ModifierFlag.Override
                isIdentifier() && scanner.getTokenValue() == "accessor" -> ModifierFlag.Accessor
                else -> break@loop
            }
            // Check: is the next token a property name? If not, this "modifier" is actually the property name.
            val nextIsPropertyName = lookAhead { nextToken(); isPropertyNameToken() || token == SyntaxKind.Asterisk }
            if (!nextIsPropertyName) break@loop
            mods.add(mod)
            nextToken()
        }
        return mods
    }

    private fun parseParameterModifiers(): Set<ModifierFlag> {
        // Valid parameter modifiers: public/private/protected/readonly/override/declare (for
        // constructor parameter properties). Also consume export/async as modifiers for error
        // recovery, since TypeScript still emits the parameter in error cases.
        val mods = mutableSetOf<ModifierFlag>()
        loop@ while (true) {
            val mod = when {
                token == SyntaxKind.PublicKeyword -> ModifierFlag.Public
                token == SyntaxKind.PrivateKeyword -> ModifierFlag.Private
                token == SyntaxKind.ProtectedKeyword -> ModifierFlag.Protected
                token == SyntaxKind.ReadonlyKeyword -> ModifierFlag.Readonly
                token == SyntaxKind.OverrideKeyword -> ModifierFlag.Override
                token == SyntaxKind.DeclareKeyword -> ModifierFlag.Declare
                token == SyntaxKind.StaticKeyword -> ModifierFlag.Static
                token == SyntaxKind.ExportKeyword -> ModifierFlag.Export
                token == SyntaxKind.AsyncKeyword -> ModifierFlag.Async
                isIdentifier() && scanner.getTokenValue() == "readonly" -> ModifierFlag.Readonly
                isIdentifier() && scanner.getTokenValue() == "override" -> ModifierFlag.Override
                isIdentifier() && scanner.getTokenValue() == "async" -> ModifierFlag.Async
                else -> break@loop
            }
            // Check: is the next token a binding name/pattern? If not, this keyword is the param name.
            val nextIsBindingName = lookAhead {
                nextToken()
                isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket
                        || token == SyntaxKind.DotDotDot
                        // Another modifier keyword followed by a binding name is also valid
                        || token == SyntaxKind.PublicKeyword || token == SyntaxKind.PrivateKeyword
                        || token == SyntaxKind.ProtectedKeyword || token == SyntaxKind.ReadonlyKeyword
                        || token == SyntaxKind.OverrideKeyword || token == SyntaxKind.StaticKeyword
            }
            if (!nextIsBindingName) break@loop
            mods.add(mod)
            nextToken()
        }
        return mods
    }

    private fun parseDecorators(): List<Decorator>? {
        if (token != SyntaxKind.At) return null
        val decorators = mutableListOf<Decorator>()
        while (token == SyntaxKind.At) {
            val pos = getPos()
            nextToken()
            val expr = parseLeftHandSideExpression()
            decorators.add(Decorator(expression = expr, pos = pos, end = getEnd()))
        }
        return decorators
    }

    // ── Expressions ─────────────────────────────────────────────────────────

    private fun parseExpression(): Expression {
        var expr = parseAssignmentExpression()
        while (token == SyntaxKind.Comma) {
            val pos = expr.pos
            nextToken()
            val right = parseAssignmentExpression()
            expr = BinaryExpression(left = expr, operator = SyntaxKind.Comma, right = right, pos = pos, end = getEnd())
        }
        return expr
    }

    private fun parseAssignmentExpression(): Expression {
        if (token == SyntaxKind.YieldKeyword) return parseYieldExpression()

        // Check for single-identifier arrow function: `x => expr`
        if (isIdentifier() && scanner.lookAhead {
                scanner.scan()
                scanner.getToken() == SyntaxKind.EqualsGreaterThan
            }) {
            return parseArrowFunction(emptySet())
        }

        // Check for `async x => expr`, `async () => expr`, `async (params): Type => expr`
        if (token == SyntaxKind.AsyncKeyword) {
            val isAsyncArrow = scanner.lookAhead {
                scanner.scan() // skip async
                if (scanner.hasPrecedingLineBreak()) return@lookAhead false
                val t = scanner.getToken()
                when {
                    // Single-param without parens: async x =>
                    t == SyntaxKind.Identifier || t == SyntaxKind.TypeKeyword -> {
                        scanner.scan()
                        scanner.getToken() == SyntaxKind.EqualsGreaterThan
                    }
                    // With parens: async () => or async (params) => or async (): Type =>
                    t == SyntaxKind.OpenParen -> {
                        scanner.scan() // skip (
                        var depth = 1
                        while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                            when (scanner.getToken()) {
                                SyntaxKind.OpenParen -> depth++
                                SyntaxKind.CloseParen -> depth--
                                else -> {}
                            }
                            if (depth > 0) scanner.scan()
                        }
                        if (depth == 0) {
                            scanner.scan() // skip )
                            val after = scanner.getToken()
                            after == SyntaxKind.EqualsGreaterThan || after == SyntaxKind.Colon
                        } else false
                    }
                    else -> false
                }
            }
            if (isAsyncArrow) {
                nextToken() // consume async
                return parseArrowFunction(setOf(ModifierFlag.Async))
            }
        }

        val expr = parseConditionalExpression()

        if (isAssignmentOperator(token)) {
            val op = token
            nextToken()
            val right = parseAssignmentExpression()
            return BinaryExpression(left = expr, operator = op, right = right, pos = expr.pos, end = getEnd())
        }

        return expr
    }

    private fun parseConditionalExpression(): Expression {
        val expr = parseBinaryExpression(0)
        if (token != SyntaxKind.Question) return expr
        nextToken()
        val whenTrue = parseAssignmentExpression()
        parseExpected(SyntaxKind.Colon)
        val whenFalse = parseAssignmentExpression()
        return ConditionalExpression(
            condition = expr,
            whenTrue = whenTrue,
            whenFalse = whenFalse,
            pos = expr.pos,
            end = getEnd()
        )
    }

    private fun parseBinaryExpression(minPrec: Int): Expression {
        var left = parseUnaryExpression()
        left = parseExpressionSuffix(left)
        while (true) {
            val prec = getBinaryOperatorPrecedence(token)
            if (prec <= minPrec) break

            // Handle 'as' and 'satisfies' as type assertion operators
            if (token == SyntaxKind.AsKeyword) {
                nextToken()
                val type = parseType()
                left = AsExpression(expression = left, type = type, pos = left.pos, end = getEnd())
                continue
            }
            if (token == SyntaxKind.SatisfiesKeyword) {
                nextToken()
                val type = parseType()
                left = SatisfiesExpression(expression = left, type = type, pos = left.pos, end = getEnd())
                continue
            }

            val op = token
            val opLeadingComments = leadingComments()
            val opHasPrecedingLineBreak = scanner.hasPrecedingLineBreak()
            nextToken()
            val opTrailingComments = scanner.consumeTrailingComments()
            // Right-to-left for ** operator
            val nextMinPrec = if (op == SyntaxKind.AsteriskAsterisk) prec - 1 else prec
            val right = parseBinaryExpression(nextMinPrec)
            left = BinaryExpression(
                left = left,
                operator = op,
                right = right,
                pos = left.pos,
                end = getEnd(),
                operatorLeadingComments = opLeadingComments,
                operatorTrailingComments = opTrailingComments,
                operatorHasPrecedingLineBreak = opHasPrecedingLineBreak,
            )
        }
        return left
    }

    private fun parseExpressionSuffix(expr: Expression): Expression {
        // Handle 'as' and 'satisfies' that appear after binary expressions
        return when (token) {
            AsKeyword -> {
                nextToken()
                val type = parseType()
                parseExpressionSuffix(AsExpression(expression = expr, type = type, pos = expr.pos, end = getEnd()))
            }

            SatisfiesKeyword -> {
                nextToken()
                val type = parseType()
                parseExpressionSuffix(
                    SatisfiesExpression(
                        expression = expr,
                        type = type,
                        pos = expr.pos,
                        end = getEnd()
                    )
                )
            }

            else -> expr
        }
    }

    private fun getBinaryOperatorPrecedence(
        kind: SyntaxKind
    ) = when (kind) {
        BarBar -> 1
        AmpersandAmpersand -> 2
        QuestionQuestion -> 1
        Bar -> 3
        Caret -> 4
        Ampersand -> 5
        EqualsEquals, ExclamationEquals,
        EqualsEqualsEquals, ExclamationEqualsEquals -> 6

        LessThan, GreaterThan,
        LessThanEquals, GreaterThanEquals,
        InstanceOfKeyword,
        AsKeyword, SatisfiesKeyword -> 7
        InKeyword -> if (disallowIn) 0 else 7

        LessThanLessThan, GreaterThanGreaterThan,
        GreaterThanGreaterThanGreaterThan -> 8

        Plus, Minus -> 9
        Asterisk, Slash, Percent -> 10
        AsteriskAsterisk -> 11
        else -> 0
    }

    private fun parseUnaryExpression(): Expression {
        val pos = getPos()
        val comments = leadingComments()
        return when (token) {
            PlusPlus, MinusMinus -> {
                val op = token; nextToken()
                PrefixUnaryExpression(
                    operator = op,
                    operand = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            Plus, Minus, Tilde, Exclamation -> {
                val op = token; nextToken()
                PrefixUnaryExpression(
                    operator = op,
                    operand = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            DeleteKeyword -> {
                nextToken(); DeleteExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            TypeOfKeyword -> {
                nextToken(); TypeOfExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            VoidKeyword -> {
                nextToken(); VoidExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            AwaitKeyword -> {
                // In non-async context, `await(...)` is a call expression (await as identifier).
                // `await literal` is AwaitExpression (emits as `yield` in non-async context).
                val nextIsOpenParen = lookAhead { nextToken(); token == SyntaxKind.OpenParen }
                if (!inAsyncContext && nextIsOpenParen) {
                    // await as identifier — fall through to call expression parsing
                    parsePostfixExpression()
                } else {
                    nextToken()
                    // Inline comments between keyword and expression appear as trailing trivia
                    // of the scan (e.g., `await /*c*/ x` — `/*c*/` is trailingComments, not leading).
                    val innerComments = scanner.getTrailingComments()
                    val innerExpr = parseUnaryExpression()
                    AwaitExpression(
                        expression = if (innerComments != null && innerExpr.leadingComments == null)
                            innerExpr.withLeadingComments(innerComments)
                        else
                            innerExpr,
                        inAsyncContext = inAsyncContext,
                        pos = pos,
                        end = getEnd(),
                        leadingComments = comments
                    )
                }
            }

            LessThan -> {
                // Could be <TypeParams>() => body (generic arrow) or <Type>expr (type assertion)
                val isGenericArrow = scanner.lookAhead {
                    // Skip past <...> type parameter list
                    scanner.scan() // skip <
                    var depth = 1
                    while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                        when (scanner.getToken()) {
                            SyntaxKind.LessThan -> depth++
                            SyntaxKind.GreaterThan -> depth--
                            SyntaxKind.Semicolon, SyntaxKind.CloseBrace -> break
                            else -> {}
                        }
                        if (depth > 0) scanner.scan()
                    }
                    if (depth == 0) {
                        scanner.scan() // skip >
                        if (scanner.getToken() == SyntaxKind.OpenParen) {
                            // Skip past (...) parameter list
                            scanner.scan() // skip (
                            var parenDepth = 1
                            while (parenDepth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                                when (scanner.getToken()) {
                                    SyntaxKind.OpenParen -> parenDepth++
                                    SyntaxKind.CloseParen -> parenDepth--
                                    else -> {}
                                }
                                if (parenDepth > 0) scanner.scan()
                            }
                            if (parenDepth == 0) {
                                scanner.scan() // skip )
                                // Check for => or : (return type annotation then =>)
                                when (scanner.getToken()) {
                                    SyntaxKind.EqualsGreaterThan -> true
                                    SyntaxKind.Colon -> {
                                        scanner.scan() // skip :
                                        var typeDepth = 0
                                        var foundArrow = false
                                        loop@ while (scanner.getToken() != SyntaxKind.EndOfFile) {
                                            when (scanner.getToken()) {
                                                SyntaxKind.OpenParen, SyntaxKind.OpenBracket, SyntaxKind.LessThan -> typeDepth++
                                                SyntaxKind.CloseParen, SyntaxKind.CloseBracket -> {
                                                    if (typeDepth == 0) break@loop else typeDepth--
                                                }
                                                SyntaxKind.GreaterThan -> if (typeDepth > 0) typeDepth--
                                                SyntaxKind.EqualsGreaterThan -> if (typeDepth == 0) { foundArrow = true; break@loop }
                                                SyntaxKind.Semicolon, SyntaxKind.OpenBrace, SyntaxKind.CloseBrace -> if (typeDepth == 0) break@loop
                                                else -> {}
                                            }
                                            scanner.scan()
                                        }
                                        foundArrow
                                    }
                                    else -> false
                                }
                            } else false
                        } else false
                    } else false
                }
                if (isGenericArrow) {
                    parseArrowFunction(emptySet())
                } else {
                    parseTypeAssertion()
                }
            }

            else -> parsePostfixExpression()
        }
    }

    private fun parseTypeAssertion(): Expression {
        val pos = getPos()
        nextToken() // skip <
        val type = parseType()
        parseExpected(SyntaxKind.GreaterThan)
        val expr = parseUnaryExpression()
        return TypeAssertionExpression(type = type, expression = expr, pos = pos, end = getEnd())
    }

    private fun parsePostfixExpression(): Expression {
        var expr = parseLeftHandSideExpression()
        // Non-null assertion: expr!
        while (token == SyntaxKind.Exclamation && !scanner.hasPrecedingLineBreak()) {
            nextToken()
            expr = NonNullExpression(expression = expr, pos = expr.pos, end = getEnd())
        }
        // Postfix ++/--
        if ((token == SyntaxKind.PlusPlus || token == SyntaxKind.MinusMinus) && !scanner.hasPrecedingLineBreak()) {
            val op = token; nextToken()
            expr = PostfixUnaryExpression(operand = expr, operator = op, pos = expr.pos, end = getEnd())
        }
        return expr
    }

    private fun parseLeftHandSideExpression(): Expression {
        var expr = when (token) {
            NewKeyword -> parseNewExpression()
            SuperKeyword -> {
                val pos = getPos(); nextToken(); Identifier(text = "super", pos = pos, end = getEnd())
            }

            ImportKeyword -> {
                if (scanner.lookAhead { scanner.scan(); scanner.getToken() == OpenParen }) {
                    // import(...)
                    val pos = getPos()
                    nextToken()
                    parseExpected(OpenParen)
                    val arg = parseAssignmentExpression()
                    parseExpected(CloseParen)
                    CallExpression(
                        expression = Identifier("import", pos = pos),
                        arguments = listOf(arg),
                        pos = pos,
                        end = getEnd()
                    )
                } else if (scanner.lookAhead { scanner.scan(); scanner.getToken() == Dot }) {
                    val pos = getPos()
                    nextToken()
                    parseExpected(Dot)
                    val name = parseIdentifier()
                    MetaProperty(keywordToken = ImportKeyword, name = name, pos = pos, end = getEnd())
                } else {
                    parseIdentifier()
                }
            }

            else -> parsePrimaryExpression()
        }
        return parseCallAndAccess(expr)
    }

    private fun parseCallAndAccess(
        expr: Expression
    ): Expression {
        var result = expr
        while (true) {
            result = when (token) {
                Dot -> {
                    val newLineBefore = scanner.hasPrecedingLineBreak()
                    nextToken()
                    val newLineAfterDot = scanner.hasPrecedingLineBreak()
                    val name = parseIdentifierName()
                    PropertyAccessExpression(expression = result, name = name, newLineBefore = newLineBefore, newLineAfterDot = newLineAfterDot, pos = result.pos, end = getEnd())
                }

                OpenBracket -> {
                    nextToken()
                    // Empty subscript a[] is invalid but TypeScript handles it gracefully
                    val arg = if (token == SyntaxKind.CloseBracket) {
                        OmittedExpression(pos = getPos(), end = getPos())
                    } else {
                        parseExpression()
                    }
                    parseExpected(SyntaxKind.CloseBracket)
                    ElementAccessExpression(
                        expression = result,
                        argumentExpression = arg,
                        pos = result.pos,
                        end = getEnd()
                    )
                }

                OpenParen -> {
                    val args = parseArgumentList()
                    CallExpression(expression = result, arguments = args, pos = result.pos, end = getEnd())
                }

                LessThan -> {
                    // Try type arguments for call/tagged-template — wrap in tryScan so if no `(` or
                    // template follows, scanner is restored to before `<` (fixing `i < 10` in for-loop)
                    val callExpr: Expression? = scanner.tryScan {
                        val typeArgs = tryParseTypeArguments()
                        when {
                            typeArgs != null && token == SyntaxKind.OpenParen -> {
                                val args = parseArgumentList()
                                CallExpression(
                                    expression = result,
                                    typeArguments = typeArgs,
                                    arguments = args,
                                    pos = result.pos,
                                    end = getEnd()
                                )
                            }
                            typeArgs != null && (token == SyntaxKind.NoSubstitutionTemplateLiteral || token == SyntaxKind.TemplateHead) -> {
                                val template = parseTemplateLiteral()
                                TaggedTemplateExpression(
                                    tag = result,
                                    typeArguments = typeArgs,
                                    template = template,
                                    pos = result.pos,
                                    end = getEnd()
                                )
                            }
                            else -> null
                        }
                    }
                    if (callExpr != null) {
                        callExpr
                    } else {
                        // tryScan restored scanner to before `<`; re-sync parser token
                        token = scanner.getToken()
                        return result
                    }
                }

                QuestionDot -> {
                    nextToken()
                    when (token) {
                        OpenBracket -> {
                            nextToken()
                            val arg = if (token == SyntaxKind.CloseBracket) {
                                OmittedExpression(pos = getPos(), end = getPos())
                            } else {
                                parseExpression()
                            }
                            parseExpected(SyntaxKind.CloseBracket)
                            ElementAccessExpression(
                                expression = result,
                                argumentExpression = arg,
                                questionDotToken = true,
                                pos = result.pos,
                                end = getEnd()
                            )
                        }

                        OpenParen -> {
                            val args = parseArgumentList()
                            CallExpression(
                                expression = result,
                                arguments = args,
                                questionDotToken = true,
                                pos = result.pos,
                                end = getEnd()
                            )
                        }

                        else -> {
                            val name = parseIdentifierName()
                            PropertyAccessExpression(
                                expression = result,
                                name = name,
                                questionDotToken = true,
                                pos = result.pos,
                                end = getEnd()
                            )
                        }
                    }
                }

                NoSubstitutionTemplateLiteral, TemplateHead -> {
                    val template = parseTemplateLiteral()
                    TaggedTemplateExpression(tag = result, template = template, pos = result.pos, end = getEnd())
                }

                Exclamation -> {
                    if (!scanner.hasPrecedingLineBreak()) {
                        nextToken()
                        NonNullExpression(expression = result, pos = result.pos, end = getEnd())
                    } else return result
                }

                else -> return result
            }
        }
    }

    private fun parseNewExpression(): Expression {
        val pos = getPos()
        parseExpected(NewKeyword)
        if (token == Dot) {
            nextToken()
            val name = parseIdentifier()
            return MetaProperty(keywordToken = NewKeyword, name = name, pos = pos, end = getEnd())
        }
        // For `new`, the constructor expression allows member access (. and []) and nested `new`,
        // but NOT function calls. parseCallAndAccess would greedily consume `()` and turn
        // `new Foo()` into `new (Foo())` — use parseMemberAccessOnly instead.
        // Handle nested `new` (e.g. `new new Date`) by recursing.
        val baseExpr = if (token == NewKeyword) parseNewExpression() else parsePrimaryExpression()
        val expr = parseMemberAccessOnly(baseExpr)
        val typeArgs = tryParseTypeArguments()
        val args = if (token == OpenParen) parseArgumentList() else null
        return NewExpression(expression = expr, typeArguments = typeArgs, arguments = args, pos = pos, end = getEnd())
    }

    /** Like [parseCallAndAccess] but only handles `.` and `[` member access, not function calls. */
    private fun parseMemberAccessOnly(expr: Expression): Expression {
        var result = expr
        while (true) {
            result = when (token) {
                Dot -> {
                    val newLineBefore = scanner.hasPrecedingLineBreak()
                    nextToken()
                    val newLineAfterDot = scanner.hasPrecedingLineBreak()
                    val name = parseIdentifierName()
                    PropertyAccessExpression(expression = result, name = name, newLineBefore = newLineBefore, newLineAfterDot = newLineAfterDot, pos = result.pos, end = getEnd())
                }
                OpenBracket -> {
                    nextToken()
                    val arg = if (token == SyntaxKind.CloseBracket) {
                        OmittedExpression(pos = getPos(), end = getPos())
                    } else {
                        parseExpression()
                    }
                    parseExpected(SyntaxKind.CloseBracket)
                    ElementAccessExpression(expression = result, argumentExpression = arg, pos = result.pos, end = getEnd())
                }
                else -> return result
            }
        }
    }

    private fun parsePrimaryExpression(): Expression {
        val pos = getPos()
        // Capture leading comments (own-line) OR same-line trailing comments between
        // a preceding keyword/operator and this expression (e.g. `new /*2*/ Array`).
        val leadingCmts = leadingComments()
        val inlineCmts = if (leadingCmts == null && !scanner.hasPrecedingLineBreak()) scanner.getTrailingComments() else null
        val comments = leadingCmts ?: inlineCmts
        val result = when (token) {
            NumericLiteral -> parseNumericLiteral()
            BigIntLiteral -> {
                val text = scanner.getTokenValue(); nextToken(); BigIntLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            StringLiteral -> parseStringLiteral()
            NoSubstitutionTemplateLiteral -> {
                val text = scanner.getTokenValue(); val unterminated = scanner.isTokenUnterminated(); nextToken(); NoSubstitutionTemplateLiteralNode(
                    text = text,
                    isUnterminated = unterminated,
                    pos = pos,
                    end = getEnd()
                )
            }

            TemplateHead -> parseTemplateExpression()
            RegularExpressionLiteral -> {
                val text = scanner.getTokenText(); nextToken(); RegularExpressionLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            Slash, SlashEquals -> {
                val regexToken = scanner.reScanSlashToken()
                if (regexToken == RegularExpressionLiteral) {
                    val text = scanner.getTokenText(); nextToken()
                    RegularExpressionLiteralNode(text = text, pos = pos, end = getEnd())
                } else {
                    Identifier(text = scanner.getTokenText(), pos = pos, end = getEnd()).also { nextToken() }
                }
            }

            OpenParen -> parseParenthesizedOrArrow()
            OpenBracket -> parseArrayLiteral()
            OpenBrace -> parseObjectLiteral()
            FunctionKeyword -> parseFunctionExpression()
            ClassKeyword -> parseClassExpression()
            TrueKeyword -> {
                nextToken(); Identifier(text = "true", pos = pos, end = getEnd())
            }

            FalseKeyword -> {
                nextToken(); Identifier(text = "false", pos = pos, end = getEnd())
            }

            NullKeyword -> {
                nextToken(); Identifier(text = "null", pos = pos, end = getEnd())
            }

            UndefinedKeyword -> {
                nextToken(); Identifier(text = "undefined", pos = pos, end = getEnd())
            }

            ThisKeyword -> {
                nextToken(); Identifier(text = "this", pos = pos, end = getEnd())
            }

            AsyncKeyword -> {
                // Could be "async function" or "async () =>"
                if (scanner.lookAhead { scanner.scan(); !scanner.hasPrecedingLineBreak() && scanner.getToken() == SyntaxKind.FunctionKeyword }) {
                    nextToken()
                    return parseFunctionExpression(setOf(ModifierFlag.Async))
                }
                parseIdentifier()
            }

            else -> parseIdentifier()
        }
        // Attach collected leading comments to the result when the sub-parser didn't store them.
        return if (comments != null && result.leadingComments == null) {
            result.withLeadingComments(comments)
        } else {
            result
        }
    }

    private fun parseParenthesizedOrArrow(): Expression {
        // Try to detect arrow function
        val isArrow = scanner.lookAhead {
            scanner.scan() // skip (
            if (scanner.getToken() == SyntaxKind.CloseParen) {
                scanner.scan()
                scanner.getToken() == SyntaxKind.EqualsGreaterThan || scanner.getToken() == SyntaxKind.Colon
            } else {
                false
            }
        }
        if (isArrow) return parseArrowFunction(emptySet())

        // Try complex arrow detection: (params) => body  or  (params): RetType => body
        val maybeArrow = scanner.lookAhead {
            scanner.scan() // skip (
            var depth = 1
            while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                if (scanner.getToken() == SyntaxKind.OpenParen) depth++
                else if (scanner.getToken() == SyntaxKind.CloseParen) depth--
                if (depth > 0) scanner.scan()
            }
            if (depth == 0) {
                scanner.scan() // skip )
                when (scanner.getToken()) {
                    SyntaxKind.EqualsGreaterThan -> true
                    SyntaxKind.Colon -> {
                        // Could be a return-type annotation: (params): Type => body
                        // Skip past the type expression to see if => follows.
                        // We must NOT match the ternary `:` — after a ternary `:`, the
                        // next expression is never followed by `=>`.
                        // Simple heuristic: skip tokens (respecting bracket depth) until
                        // we hit `=>`, `;`, `{`, or end — if we hit `=>`, it's an arrow.
                        scanner.scan() // skip :
                        var typeDepth = 0
                        var foundArrow = false
                        loop@ while (scanner.getToken() != SyntaxKind.EndOfFile) {
                            when (scanner.getToken()) {
                                SyntaxKind.OpenParen, SyntaxKind.OpenBracket, SyntaxKind.LessThan -> typeDepth++
                                SyntaxKind.CloseParen, SyntaxKind.CloseBracket -> {
                                    if (typeDepth == 0) break@loop else typeDepth--
                                }
                                SyntaxKind.GreaterThan -> if (typeDepth > 0) typeDepth--
                                SyntaxKind.EqualsGreaterThan -> if (typeDepth == 0) { foundArrow = true; break@loop }
                                SyntaxKind.Semicolon, SyntaxKind.OpenBrace,
                                SyntaxKind.CloseBrace, SyntaxKind.Colon -> if (typeDepth == 0) break@loop
                                else -> {}
                            }
                            scanner.scan()
                        }
                        foundArrow
                    }
                    else -> false
                }
            } else false
        }
        if (maybeArrow) return parseArrowFunction(emptySet())

        val pos = getPos()
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        // Capture same-line trailing comments between inner expression and ')' (e.g. `(a => 0 /*t3*/)`).
        val innerTrailing = scanner.getTrailingComments()
        parseExpected(SyntaxKind.CloseParen)
        val exprWithComments = if (!innerTrailing.isNullOrEmpty()) expr.withTrailingComments(innerTrailing) else expr
        return ParenthesizedExpression(expression = exprWithComments, pos = pos, end = getEnd())
    }

    private fun parseArrowFunction(modifiers: Set<ModifierFlag>): ArrowFunction {
        val pos = getPos()
        val async = ModifierFlag.Async in modifiers
        val typeParams = parseTypeParametersOpt()
        val hasParens: Boolean
        val params = if (token == SyntaxKind.OpenParen) {
            hasParens = true
            parseParameterList()
        } else if (isIdentifier()) {
            hasParens = false
            listOf(Parameter(name = parseIdentifier()))
        } else {
            hasParens = true
            parseParameterList()
        }
        val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        parseExpected(SyntaxKind.EqualsGreaterThan)
        val savedAsync = inAsyncContext
        inAsyncContext = async
        val body: Node = if (token == SyntaxKind.OpenBrace) parseBlock() else parseAssignmentExpression()
        inAsyncContext = savedAsync
        return ArrowFunction(
            typeParameters = typeParams, parameters = params, type = returnType,
            body = body, modifiers = modifiers, asteriskToken = false, hasParenthesizedParameters = hasParens,
            pos = pos, end = getEnd()
        )
    }

    private fun parseArrayLiteral(): ArrayLiteralExpression {
        val pos = getPos()
        val openBracketPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenBracket)
        // Capture inline comments right after `[` (no preceding newline, so they went to
        // scanner.trailingComments rather than leadingComments of the first element).
        // Use consumeTrailingComments() to clear them so parsePrimaryExpression doesn't
        // also capture them as inlineCmts (which would cause double-emission).
        val openBracketComments = scanner.consumeTrailingComments()
        val elements = mutableListOf<Expression>()
        var hasTrailingComma = false
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                hasTrailingComma = (token == SyntaxKind.CloseBracket)
                continue
            }
            val elemComments = leadingComments()
            if (token == SyntaxKind.DotDotDot) {
                val sPos = getPos()
                nextToken()
                // Capture comments between `...` and the expression (e.g. `/*#__PURE__*/`)
                val postDotComments = leadingComments() ?: scanner.getTrailingComments()
                var spreadExpr = parseAssignmentExpression()
                if (postDotComments != null) spreadExpr = spreadExpr.withLeadingComments(postDotComments)
                val spread = SpreadElement(expression = spreadExpr, pos = sPos, end = getEnd())
                // Pre-comma own-line comments (in leading trivia of the comma token)
                val preCommaComments = if (token == SyntaxKind.Comma) leadingComments() else null
                elements.add(spread.withLeadingComments(elemComments).withTrailingComments(preCommaComments?.takeIf { it.isNotEmpty() }))
            } else {
                val elem = parseAssignmentExpression()
                // Same-line trailing comments between element and comma
                val elemTrailing = scanner.getTrailingComments()
                // Pre-comma own-line comments (in leading trivia of the comma token)
                val preCommaComments = if (token == SyntaxKind.Comma) leadingComments() else null
                val allTrailing = listOfNotNull(elemTrailing, preCommaComments).flatten().takeIf { it.isNotEmpty() }
                elements.add(elem.withLeadingComments(elemComments).withTrailingComments(allTrailing))
            }
            if (parseOptional(SyntaxKind.Comma)) {
                hasTrailingComma = (token == SyntaxKind.CloseBracket)
            } else {
                hasTrailingComma = false
                break
            }
        }
        // Capture any comments before the closing `]`:
        // - Own-line comments: from leadingComments() on the `]` token (hasPrecedingNewLine=true)
        // - Same-line after last comma: from getTrailingComments() (hasPrecedingNewLine=false)
        // For empty arrays, avoid recapturing openBracketComments: the `nextToken()` in
        // parseExpected(OpenBracket) is the only one that ran, so trailingComments still holds
        // the same value as openBracketComments. For non-empty arrays, the last nextToken()
        // (inside the loop) has reset trailingComments to reflect post-loop trivia.
        val closingComments = leadingComments() ?: if (hasTrailingComma || (elements.isEmpty() && openBracketComments == null)) scanner.getTrailingComments() else null
        val closeBracketPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBracket)
        val multiLine =
            if (openBracketPos in 0..<closeBracketPos && closeBracketPos <= source.length) {
                source.substring(openBracketPos, closeBracketPos).contains('\n')
            } else false
        return ArrayLiteralExpression(
            elements = elements,
            multiLine = multiLine,
            hasTrailingComma = hasTrailingComma,
            pos = pos,
            end = getEnd(),
            trailingComments = closingComments,
            openBracketComments = openBracketComments,
        )
    }

    private fun parseObjectLiteral(): ObjectLiteralExpression {
        val pos = getPos()
        val openBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenBrace)
        val properties = mutableListOf<Node>()
        var hasTrailingComma = false
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            properties.add(parseObjectLiteralElement())
            // TypeScript allows semicolons as property separators in object literals
            val hadComma = parseOptional(SyntaxKind.Comma)
            val hadSemi = !hadComma && parseOptional(SyntaxKind.Semicolon)
            if (hadComma || hadSemi) {
                // Capture any same-line trailing comments that appeared after the comma
                val postCommaTrailing = scanner.getTrailingComments()
                if (postCommaTrailing != null && properties.isNotEmpty()) {
                    val last = properties.last()
                    properties[properties.size - 1] = withTrailingComments(last, postCommaTrailing)
                }
                // Semicolons are not treated as trailing commas (TypeScript emits no trailing comma)
                hasTrailingComma = hadComma && (token == SyntaxKind.CloseBrace)
            } else {
                hasTrailingComma = false
                break
            }
        }
        // Capture any comments before the closing `}` (e.g., trailing comments inside
        // a multi-line object after the last property).
        val closingComments = leadingComments()
        val closeBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBrace)
        val multiLine = if (openBracePos in 0..<closeBracePos && closeBracePos <= source.length) {
            source.substring(openBracePos, closeBracePos).contains('\n')
        } else false
        return ObjectLiteralExpression(properties = properties, multiLine = multiLine, hasTrailingComma = hasTrailingComma, pos = pos, end = getEnd(), trailingComments = closingComments)
    }

    private fun parseObjectLiteralElement(): Node {
        val pos = getPos()
        val comments = leadingComments()

        if (token == SyntaxKind.DotDotDot) {
            nextToken()
            // Capture comments between `...` and the expression (e.g. `/*#__PURE__*/`)
            val postDotComments = leadingComments() ?: scanner.getTrailingComments()
            var spreadExpr = parseAssignmentExpression()
            if (postDotComments != null) spreadExpr = spreadExpr.withLeadingComments(postDotComments)
            return SpreadAssignment(expression = spreadExpr, pos = pos, end = getEnd(), leadingComments = comments)
        }

        // In object literals, modifier keywords (public, private, etc.) can also be property
        // names. Use lookAhead: if the modifier keyword is followed by `:`, `,`, `}`, `?`, or `(`
        // it's a property name, not a modifier.
        val modifiers = parseObjectLiteralModifiers()
        val asterisk = parseOptional(SyntaxKind.Asterisk)

        if (!asterisk && (isIdentifier() && scanner.getTokenValue() == "get")) {
            val isGet = lookAhead { nextToken(); isPropertyNameToken() }
            if (isGet) {
                nextToken()
                val name = parsePropertyName()
                val params = parseParameterList()
                val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
                val body = if (token == SyntaxKind.OpenBrace) parseBlock() else null
                return GetAccessor(
                    name = name,
                    parameters = params,
                    type = type,
                    body = body,
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }
        }

        if (!asterisk && (isIdentifier() && scanner.getTokenValue() == "set")) {
            val isSet = lookAhead { nextToken(); isPropertyNameToken() }
            if (isSet) {
                nextToken()
                val name = parsePropertyName()
                val params = parseParameterList()
                val body = if (token == SyntaxKind.OpenBrace) parseBlock() else null
                return SetAccessor(name = name, parameters = params, body = body, pos = pos, end = getEnd(), leadingComments = comments)
            }
        }

        val name = parsePropertyName()

        // Skip optional '?' (TypeScript optional property marker; erased in JS output)
        parseOptional(SyntaxKind.Question)

        // Method shorthand: foo() { ... }  or *foo() { ... } or async foo() { ... }
        if (asterisk || token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val body = if (token == SyntaxKind.OpenBrace) parseBlock() else null
            return MethodDeclaration(
                name = name,
                typeParameters = typeParams,
                parameters = params,
                type = returnType,
                body = body,
                modifiers = modifiers,
                asteriskToken = asterisk,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        // Property: name: value
        if (parseOptional(SyntaxKind.Colon)) {
            // Capture any inline comments that appeared between ':' and the value expression
            // (e.g., `id: /*! @ngInject */ value`). These go into the scanner's trailingComments
            // rather than the next token's leadingComments.
            // Use consumeTrailingComments() to clear them so parsePrimaryExpression doesn't
            // also capture them as inlineCmts (which would cause double-emission).
            val inlineComments = scanner.consumeTrailingComments()
            val value = parseAssignmentExpression()
            // Capture same-line trailing comments after the value expression (e.g. `f: a => 0 /*t1*/,`).
            // These appear in the scanner's trailingComments between the expression end and the comma.
            val valueTrailing = scanner.getTrailingComments()
            val valueWithComments = if (!inlineComments.isNullOrEmpty()) {
                val merged = inlineComments + (value.leadingComments ?: emptyList())
                value.withLeadingComments(merged)
            } else {
                value
            }.let { v -> if (!valueTrailing.isNullOrEmpty()) v.withTrailingComments(valueTrailing) else v }
            return PropertyAssignment(name = name, initializer = valueWithComments, pos = pos, end = getEnd(), leadingComments = comments)
        }

        // Shorthand: { name } or { name = default }
        if (name is Identifier) {
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            return ShorthandPropertyAssignment(
                name = name,
                objectAssignmentInitializer = init,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        return PropertyAssignment(name = name, initializer = Identifier(""), pos = pos, end = getEnd(), leadingComments = comments)
    }

    private fun parseFunctionExpression(modifiers: Set<ModifierFlag> = emptySet()): FunctionExpression {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.FunctionKeyword)
        val asterisk = parseOptional(SyntaxKind.Asterisk)
        val name = if (isIdentifier()) parseIdentifier() else null
        val typeParams = parseTypeParametersOpt()
        val params = parseParameterList()
        val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val savedAsync = inAsyncContext
        inAsyncContext = ModifierFlag.Async in modifiers
        val body = parseBlock()
        inAsyncContext = savedAsync
        return FunctionExpression(
            name = name, typeParameters = typeParams, parameters = params,
            type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk, pos = pos, end = getEnd(),
            leadingComments = comments,
        )
    }

    private fun parseClassExpression(): ClassExpression {
        val pos = getPos()
        parseExpected(SyntaxKind.ClassKeyword)
        // `implements` and `extends` always start heritage clauses, never class names
        val name = if (isIdentifier() && token != SyntaxKind.ImplementsKeyword && token != SyntaxKind.ExtendsKeyword) parseIdentifier() else null
        val typeParams = parseTypeParametersOpt()
        val heritage = parseHeritageClauses()
        parseExpected(SyntaxKind.OpenBrace)
        val members = parseClassMembers()
        parseExpected(SyntaxKind.CloseBrace)
        return ClassExpression(
            name = name,
            typeParameters = typeParams,
            heritageClauses = heritage,
            members = members,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseYieldExpression(): YieldExpression {
        val pos = getPos()
        nextToken()
        // Inline comments between 'yield' and the next token appear as trailing trivia of the scan
        // (e.g., `yield /*c*/ expr` or `yield /*c*/* expr` — `/*c*/` is trailingComments, not leading).
        val trailingAfterYield = scanner.consumeTrailingComments()
        val asterisk = parseOptional(SyntaxKind.Asterisk)
        // Comments after '*' (e.g., `yield */*c*/ expr`)
        val trailingAfterAsterisk = if (asterisk) scanner.consumeTrailingComments() else null
        val expr = if (!canParseSemicolon()) parseAssignmentExpression() else null
        val exprWithComments = if (expr != null) {
            // For `yield /*c*/ expr` (no asterisk): comments before expression
            // For `yield */*c*/ expr`: comments after '*', before expression
            val innerComments = trailingAfterAsterisk ?: if (!asterisk) trailingAfterYield else null
            if (innerComments != null && expr.leadingComments == null)
                expr.withLeadingComments(innerComments)
            else expr
        } else expr
        return YieldExpression(
            expression = exprWithComments,
            asteriskToken = asterisk,
            yieldAsteriskComments = if (asterisk) trailingAfterYield else null,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseTemplateLiteral(): Expression {
        if (token == SyntaxKind.NoSubstitutionTemplateLiteral) {
            val text = scanner.getTokenValue()
            val unterminated = scanner.isTokenUnterminated()
            val pos = getPos()
            nextToken()
            return NoSubstitutionTemplateLiteralNode(text = text, isUnterminated = unterminated, pos = pos, end = getEnd())
        }
        return parseTemplateExpression()
    }

    private fun parseTemplateExpression(): TemplateExpression {
        val pos = getPos()
        val headText = scanner.getTokenValue()
        nextToken() // consume template head
        val head = StringLiteralNode(text = headText, pos = pos, end = getEnd())
        val spans = mutableListOf<TemplateSpan>()
        while (token != SyntaxKind.EndOfFile) {
            val spanPos = getPos()
            val expr = parseExpression()
            // After expression, rescan to get template middle or tail
            val literalKind = scanner.reScanTemplateToken()
            val literalText = scanner.getTokenValue()
            val litPos = getPos()
            nextToken()
            val literal: Node = if (literalKind == SyntaxKind.TemplateTail) {
                StringLiteralNode(text = literalText, pos = litPos, end = getEnd())
            } else {
                StringLiteralNode(text = literalText, pos = litPos, end = getEnd())
            }
            spans.add(TemplateSpan(expression = expr, literal = literal, pos = spanPos, end = getEnd()))
            if (literalKind == SyntaxKind.TemplateTail) break
        }
        return TemplateExpression(head = head, templateSpans = spans, pos = pos, end = getEnd())
    }

    private fun parseExpressionRest(left: Expression): Expression {
        var expr = parseCallAndAccess(left)
        // Non-null assertions
        while (token == SyntaxKind.Exclamation && !scanner.hasPrecedingLineBreak()) {
            nextToken()
            expr = NonNullExpression(expression = expr, pos = expr.pos, end = getEnd())
        }
        // Postfix
        if ((token == SyntaxKind.PlusPlus || token == SyntaxKind.MinusMinus) && !scanner.hasPrecedingLineBreak()) {
            val op = token; nextToken()
            expr = PostfixUnaryExpression(operand = expr, operator = op, pos = expr.pos, end = getEnd())
        }
        // Binary
        expr = parseBinaryExpressionRest(0, expr)
        // Conditional
        if (token == SyntaxKind.Question) {
            nextToken()
            val whenTrue = parseAssignmentExpression()
            parseExpected(SyntaxKind.Colon)
            val whenFalse = parseAssignmentExpression()
            expr = ConditionalExpression(
                condition = expr,
                whenTrue = whenTrue,
                whenFalse = whenFalse,
                pos = expr.pos,
                end = getEnd()
            )
        }
        // Assignment
        if (isAssignmentOperator(token)) {
            val op = token; nextToken()
            val right = parseAssignmentExpression()
            expr = BinaryExpression(left = expr, operator = op, right = right, pos = expr.pos, end = getEnd())
        }
        // Comma
        while (token == SyntaxKind.Comma) {
            nextToken()
            val right = parseAssignmentExpression()
            expr = BinaryExpression(
                left = expr,
                operator = SyntaxKind.Comma,
                right = right,
                pos = expr.pos,
                end = getEnd()
            )
        }
        return expr
    }

    private fun parseBinaryExpressionRest(minPrec: Int, left: Expression): Expression {
        var result = left
        while (true) {
            val prec = getBinaryOperatorPrecedence(token)
            if (prec <= minPrec) break
            if (token == SyntaxKind.AsKeyword) {
                nextToken()
                val type = parseType()
                result = AsExpression(expression = result, type = type, pos = result.pos, end = getEnd())
                continue
            }
            if (token == SyntaxKind.SatisfiesKeyword) {
                nextToken()
                val type = parseType()
                result = SatisfiesExpression(expression = result, type = type, pos = result.pos, end = getEnd())
                continue
            }
            val op = token
            val opLeadingComments = leadingComments()
            val opHasPrecedingLineBreak = scanner.hasPrecedingLineBreak()
            nextToken()
            val opTrailingComments = scanner.consumeTrailingComments()
            val nextMinPrec = if (op == SyntaxKind.AsteriskAsterisk) prec - 1 else prec
            val right = parseBinaryExpression(nextMinPrec)
            result = BinaryExpression(
                left = result,
                operator = op,
                right = right,
                pos = result.pos,
                end = getEnd(),
                operatorLeadingComments = opLeadingComments,
                operatorTrailingComments = opTrailingComments,
                operatorHasPrecedingLineBreak = opHasPrecedingLineBreak,
            )
        }
        return result
    }

    private fun parseArgumentList(): List<Expression> {
        parseExpected(SyntaxKind.OpenParen)
        val args = mutableListOf<Expression>()
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                // Missing argument slot e.g. foo(a,,b) — create OmittedExpression
                args.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            // Capture inline comments before each argument (e.g. /*label*/ before string arg).
            // Comments on the same line as `(` or `,` are classified as trailingComments by the
            // scanner (no preceding line break), while comments on new lines are leadingComments.
            val argComments = leadingComments() ?: scanner.getTrailingComments()
            if (token == SyntaxKind.DotDotDot) {
                val pos = getPos()
                nextToken()
                // Capture comments between `...` and the expression (e.g. `/*#__PURE__*/`)
                val postDotComments = leadingComments() ?: scanner.getTrailingComments()
                var spreadExpr = parseAssignmentExpression()
                if (postDotComments != null) spreadExpr = spreadExpr.withLeadingComments(postDotComments)
                args.add(SpreadElement(expression = spreadExpr, pos = pos, end = getEnd()))
            } else {
                val expr = parseAssignmentExpression()
                args.add(if (argComments != null) expr.withLeadingComments(argComments) else expr)
            }
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseParen)
        return args
    }

    // ── Parameters ──────────────────────────────────────────────────────────

    private fun parseParameterList(): List<Parameter> {
        parseExpected(SyntaxKind.OpenParen)
        // Capture same-line comments between `(` and first token (e.g., `/** nothing */` in empty lists).
        val openParenComments = scanner.getTrailingComments()
        val params = mutableListOf<Parameter>()
        // Comments to prepend to the next parameter's leadingComments (captured from before a comma).
        // Initialize with openParenComments so inline comments after '(' attach to the first param.
        var pendingLeadingComments: List<Comment>? = openParenComments?.takeIf { it.isNotEmpty() }
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            var param = parseParameter()
            // Prepend any comments collected from before the previous comma into this param's leadingComments.
            if (pendingLeadingComments != null) {
                val merged = pendingLeadingComments!! + (param.leadingComments ?: emptyList())
                param = param.copy(leadingComments = merged.ifEmpty { null })
                pendingLeadingComments = null
            }
            // Before consuming the comma, capture its leading comments (comments between param and comma).
            // These will become the leading comments of the NEXT parameter (comma-first style).
            val preCommaComments = leadingComments()
            if (!parseOptional(SyntaxKind.Comma)) {
                // No comma — we're done. Capture leading comments of `)` as trailing of this last param.
                val preCloseParenComments = leadingComments()
                if (!preCloseParenComments.isNullOrEmpty()) {
                    val merged = (param.trailingComments ?: emptyList()) + preCloseParenComments
                    param = param.copy(trailingComments = merged)
                }
                params.add(param)
                break
            }
            params.add(param)
            // Save pre-comma comments to attach to next parameter.
            if (!preCommaComments.isNullOrEmpty()) {
                pendingLeadingComments = preCommaComments
            }
        }
        parseExpected(SyntaxKind.CloseParen)
        // If no parameters but there are inline comments between ( and ), create a placeholder.
        if (params.isEmpty() && !openParenComments.isNullOrEmpty()) {
            return listOf(Parameter(
                name = Identifier(""),
                isCommentPlaceholder = true,
                leadingComments = openParenComments,
            ))
        }
        return params
    }

    private fun parseParameter(): Parameter {
        val pos = getPos()
        val comments = leadingComments()
        val decorators = parseDecorators()
        val modifiers = parseParameterModifiers()
        val dotDotDot = parseOptional(SyntaxKind.DotDotDot)
        val dotTrailing = if (dotDotDot) trailingComments() else null
        val name = parseBindingNameOrPattern()
        val question = parseOptional(SyntaxKind.Question)
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
        val trailing = trailingComments()
        return Parameter(
            name = name, type = type, initializer = init, dotDotDotToken = dotDotDot,
            questionToken = question, modifiers = modifiers, decorators = decorators, pos = pos, end = getEnd(),
            leadingComments = comments, trailingComments = trailing,
            dotDotDotTrailingComments = dotTrailing,
        )
    }

    // ── Type parameters ─────────────────────────────────────────────────────

    private fun parseTypeParametersOpt(): List<TypeParameter>? {
        if (token != SyntaxKind.LessThan) return null
        return tryParseTypeParameters()
    }

    private fun tryParseTypeParameters(): List<TypeParameter>? {
        return scanner.tryScan {
            if (token != SyntaxKind.LessThan) return@tryScan null
            nextToken()
            val params = mutableListOf<TypeParameter>()
            do {
                val p = parseTypeParameter() ?: return@tryScan null
                params.add(p)
            } while (parseOptional(SyntaxKind.Comma))
            if (token != SyntaxKind.GreaterThan) return@tryScan null
            nextToken()
            params
        }
    }

    private fun parseTypeParameter(): TypeParameter? {
        val pos = getPos()
        val modifiers = parseModifiers()
        if (!isIdentifier()) return null
        val name = parseIdentifier()
        val constraint = if (parseOptional(SyntaxKind.ExtendsKeyword)) parseType() else null
        val default = if (parseOptional(SyntaxKind.Equals)) parseType() else null
        return TypeParameter(
            name = name,
            constraint = constraint,
            default = default,
            modifiers = modifiers,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseTypeArgumentsOpt(): List<TypeNode>? {
        return tryParseTypeArguments()
    }

    private fun tryParseTypeArguments(): List<TypeNode>? {
        return scanner.tryScan {
            if (token != SyntaxKind.LessThan) return@tryScan null
            nextToken()
            // Empty type argument list: <>
            if (token == SyntaxKind.GreaterThan) {
                nextToken()
                return@tryScan emptyList()
            }
            val args = mutableListOf<TypeNode>()
            do {
                // Handle missing type arguments (e.g., `Foo<a,,b>`)
                if (token == SyntaxKind.Comma || token == SyntaxKind.GreaterThan) {
                    args.add(KeywordTypeNode(kind = SyntaxKind.AnyKeyword, pos = getPos(), end = getEnd()))
                } else {
                    args.add(parseType())
                }
            } while (parseOptional(SyntaxKind.Comma))
            // Handle nested generics: Array<Fn<T>> produces '>>' token — rescan to single '>'
            token = scanner.reScanGreaterToken()
            if (token != SyntaxKind.GreaterThan) return@tryScan null
            nextToken()
            args
        }
    }

    // ── Type parsing (parse to discard) ─────────────────────────────────────

    private fun parseType(): TypeNode {
        val pos = getPos()
        // Assertion predicate: asserts x [is T]
        if (token == SyntaxKind.AssertsKeyword) {
            nextToken()  // consume 'asserts'
            if (isIdentifier() || token == SyntaxKind.ThisKeyword) {
                nextToken()  // consume the parameter/this name
            }
            if (token == SyntaxKind.IsKeyword) {
                nextToken()  // consume 'is'
                return parseType()
            }
            return KeywordTypeNode(kind = SyntaxKind.VoidKeyword, pos = pos, end = getEnd())
        }
        // TypeScript allows leading `|` before the first union member:
        //   type A = | string | number;  →  union type
        parseOptional(SyntaxKind.Bar)
        var type = parseIntersectionOrHigherType()
        // Type predicate: X is T (valid as function return type annotations)
        // After parsing X as a type reference, if the next token is `is`, consume it
        // and parse the actual predicate type. Since we erase all types, the exact
        // node returned doesn't matter as long as we consume the right tokens.
        if (token == SyntaxKind.IsKeyword) {
            nextToken()  // consume 'is'
            type = parseIntersectionOrHigherType()
        }
        if (token == SyntaxKind.Bar) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Bar)) {
                types.add(parseIntersectionOrHigherType())
            }
            type = UnionType(types = types, pos = pos, end = getEnd())
        }
        // Conditional type: T extends U ? X : Y
        if (token == SyntaxKind.ExtendsKeyword) {
            nextToken()
            val extendsType = parseType()
            parseExpected(SyntaxKind.Question)
            val trueType = parseType()
            parseExpected(SyntaxKind.Colon)
            val falseType = parseType()
            type = ConditionalType(
                checkType = type,
                extendsType = extendsType,
                trueType = trueType,
                falseType = falseType,
                pos = pos,
                end = getEnd()
            )
        }
        return type
    }

    private fun parseIntersectionOrHigherType(): TypeNode {
        val pos = getPos()
        // TypeScript allows leading `&` before the first intersection member:
        //   type B = & { x: number };    →  intersection type
        parseOptional(SyntaxKind.Ampersand)
        var type = parseNonUnionType()
        if (token == SyntaxKind.Ampersand) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Ampersand)) {
                types.add(parseNonUnionType())
            }
            type = IntersectionType(types = types, pos = pos, end = getEnd())
        }
        return type
    }

    private fun parseNonUnionType(): TypeNode {
        val pos = getPos()
        // Type operators
        if (token == SyntaxKind.KeyOfKeyword) {
            nextToken()
            return TypeOperator(
                operator = SyntaxKind.KeyOfKeyword,
                type = parseNonUnionType(),
                pos = pos,
                end = getEnd()
            )
        }
        if (token == SyntaxKind.UniqueKeyword) {
            nextToken()
            return TypeOperator(
                operator = SyntaxKind.UniqueKeyword,
                type = parseNonUnionType(),
                pos = pos,
                end = getEnd()
            )
        }
        if (token == SyntaxKind.ReadonlyKeyword || (isIdentifier() && scanner.getTokenValue() == "readonly")) {
            nextToken()
            return TypeOperator(
                operator = SyntaxKind.ReadonlyKeyword,
                type = parseNonUnionType(),
                pos = pos,
                end = getEnd()
            )
        }
        if (token == SyntaxKind.InferKeyword) {
            nextToken()
            val tp = parseTypeParameter()
            return InferType(
                typeParameter = tp ?: TypeParameter(name = Identifier("unknown")),
                pos = pos,
                end = getEnd()
            )
        }
        if (token == SyntaxKind.TypeOfKeyword) {
            nextToken()
            val name = parseQualifiedName()
            val typeArgs = parseTypeArgumentsOpt()
            var type: TypeNode = TypeQuery(exprName = name, typeArguments = typeArgs, pos = pos, end = getEnd())
            // Handle array suffix: `typeof X[]` → ArrayType(TypeQuery(X))
            while (token == SyntaxKind.OpenBracket) {
                if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.CloseBracket }) {
                    nextToken(); nextToken()
                    type = ArrayType(elementType = type, pos = pos, end = getEnd())
                } else break
            }
            return type
        }

        var type = parsePrimaryType()

        // Array type suffix: T[], T[][]
        while (token == SyntaxKind.OpenBracket) {
            if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.CloseBracket }) {
                nextToken(); nextToken()
                type = ArrayType(elementType = type, pos = pos, end = getEnd())
            } else {
                // Indexed access type: T[K]
                nextToken()
                val indexType = parseType()
                parseExpected(SyntaxKind.CloseBracket)
                type = IndexedAccessType(objectType = type, indexType = indexType, pos = pos, end = getEnd())
            }
        }

        return type
    }

    private fun parsePrimaryType(): TypeNode {
        val pos = getPos()
        return when (token) {
            AnyKeyword, StringKeyword, NumberKeyword,
            BooleanKeyword, BigIntKeyword, SymbolKeyword,
            VoidKeyword, NeverKeyword, ObjectKeyword,
            UnknownKeyword, UndefinedKeyword, NullKeyword -> {
                val kw = token; nextToken()
                KeywordTypeNode(kind = kw, pos = pos, end = getEnd())
            }

            ThisKeyword -> {
                nextToken(); ThisType(pos = pos, end = getEnd())
            }

            OpenParen -> parseFunctionOrParenthesizedType()
            OpenBracket -> parseTupleType()
            OpenBrace -> parseTypeLiteralOrMappedType()
            StringLiteral, NumericLiteral, TrueKeyword,
            FalseKeyword -> {
                val literal = parsePrimaryExpression()
                LiteralType(literal = literal, pos = pos, end = getEnd())
            }

            Minus -> {
                nextToken()
                val literal = parsePrimaryExpression()
                LiteralType(
                    literal = PrefixUnaryExpression(
                        operator = SyntaxKind.Minus,
                        operand = literal,
                        pos = pos,
                        end = getEnd()
                    ), pos = pos, end = getEnd()
                )
            }

            NewKeyword -> parseConstructorType()
            ImportKeyword -> parseImportType()
            DotDotDot -> {
                nextToken(); RestType(type = parseType(), pos = pos, end = getEnd())
            }

            LessThan -> {
                // Generic function type: <T>(a: T) => R
                val typeParams = parseTypeParametersOpt()
                val params = parseParameterList()
                parseExpected(SyntaxKind.EqualsGreaterThan)
                val returnType = parseType()
                FunctionType(
                    typeParameters = typeParams,
                    parameters = params,
                    type = returnType,
                    pos = pos,
                    end = getEnd()
                )
            }

            Backtick, NoSubstitutionTemplateLiteral, TemplateHead -> {
                // Template literal type — skip for simplicity
                skipTemplateType()
                KeywordTypeNode(kind = SyntaxKind.StringKeyword, pos = pos, end = getEnd())
            }

            else -> {
                // Type reference
                val name = parseQualifiedName()
                val typeArgs = parseTypeArgumentsOpt()
                TypeReference(typeName = name, typeArguments = typeArgs, pos = pos, end = getEnd())
            }
        }
    }

    private fun parseFunctionOrParenthesizedType(): TypeNode {
        val pos = getPos()
        // Try to parse as a function type: (params) => returnType
        // Uses tryScan so scanner state is fully restored if this isn't a function type.
        // This handles cases like (number | string)[] which look like param lists but aren't.
        val funcType = scanner.tryScan {
            if (token != SyntaxKind.OpenParen) return@tryScan null
            nextToken() // consume (
            val params = mutableListOf<Parameter>()
            while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
                params.add(parseParameter())
                if (!parseOptional(SyntaxKind.Comma)) break
            }
            if (token != SyntaxKind.CloseParen) return@tryScan null
            nextToken() // consume )
            if (token != SyntaxKind.EqualsGreaterThan) return@tryScan null
            nextToken() // consume =>
            val returnType = parseType()
            FunctionType(parameters = params, type = returnType, pos = pos, end = getEnd())
        }
        if (funcType != null) return funcType
        // Re-sync parser's token after tryScan restored scanner state (same pattern as line 1879)
        token = scanner.getToken()
        // Fall back to parenthesized type: (type)
        parseExpected(SyntaxKind.OpenParen)
        val innerType = parseType()
        parseExpected(SyntaxKind.CloseParen)
        return ParenthesizedType(type = innerType, pos = pos, end = getEnd())
    }

    private fun parseTupleType(): TypeNode {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBracket)
        val elements = mutableListOf<TypeNode>()
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            // Labeled tuple elements: `name: Type` or `name?: Type` or `...name: Type`
            val isRest = parseOptional(SyntaxKind.DotDotDot)
            val isLabeledElement = !isRest && isIdentifier() && lookAhead {
                nextToken()
                token == SyntaxKind.Colon || token == SyntaxKind.Question
            }
            if (isLabeledElement) {
                // Skip label (identifier) and optional `?`
                nextToken() // consume identifier (label)
                parseOptional(SyntaxKind.Question) // optional `?`
                parseExpected(SyntaxKind.Colon) // consume `:`
            }
            val elementType = parseType()
            elements.add(if (isRest) RestType(type = elementType, pos = pos, end = getEnd()) else elementType)
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBracket)
        return TupleType(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseTypeLiteralOrMappedType(): TypeNode {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val members = parseInterfaceMembers()
        parseExpected(SyntaxKind.CloseBrace)
        return TypeLiteral(members = members, pos = pos, end = getEnd())
    }

    private fun parseConstructorType(): TypeNode {
        val pos = getPos()
        nextToken() // skip 'new'
        val typeParams = parseTypeParametersOpt()
        val params = parseParameterList()
        parseExpected(SyntaxKind.EqualsGreaterThan)
        val type = parseType()
        return ConstructorType(typeParameters = typeParams, parameters = params, type = type, pos = pos, end = getEnd())
    }

    private fun parseImportType(): TypeNode {
        val pos = getPos()
        val isTypeOf = false
        nextToken() // skip 'import'
        parseExpected(SyntaxKind.OpenParen)
        val arg = parseType()
        parseExpected(SyntaxKind.CloseParen)
        var qualifier: Node? = null
        if (parseOptional(SyntaxKind.Dot)) {
            qualifier = parseQualifiedName()
        }
        val typeArgs = parseTypeArgumentsOpt()
        return ImportType(
            argument = arg,
            qualifier = qualifier,
            typeArguments = typeArgs,
            isTypeOf = isTypeOf,
            pos = pos,
            end = getEnd()
        )
    }

    private fun skipTemplateType() {
        // Skip template literal type tokens
        if (token == SyntaxKind.NoSubstitutionTemplateLiteral) {
            nextToken(); return
        }
        if (token == SyntaxKind.TemplateHead) {
            nextToken()
            while (token != SyntaxKind.EndOfFile) {
                parseType() // skip type in span
                val kind = scanner.reScanTemplateToken()
                nextToken()
                if (kind == SyntaxKind.TemplateTail) break
            }
        }
    }

    private fun parseQualifiedName(): Node {
        var name: Node = parseIdentifierName()
        while (parseOptional(SyntaxKind.Dot)) {
            val right = parseIdentifierName()
            name = QualifiedName(left = name, right = right, pos = name.pos, end = getEnd())
        }
        return name
    }

    // ── Identifiers & helpers ───────────────────────────────────────────────

    private fun parseIdentifier(): Identifier {
        val pos = getPos()
        if (isIdentifier() || isKeyword()) {
            val value = scanner.getTokenValue()
            val raw = scanner.getTokenText()
            // Only store rawText if it differs (contains \uXXXX escapes)
            val rawText = if (raw != value) raw else null
            // Report invalid unicode escapes (e.g. \u003 with only 3 hex digits)
            if (scanner.hasInvalidUnicodeEscapeInToken()) {
                reportError("Invalid character.")
            }
            nextToken()
            return Identifier(text = value, rawText = rawText, pos = pos, end = getEnd())
        } else {
            reportError("Identifier expected.")
            return Identifier(text = "", pos = pos, end = getEnd())
        }
    }

    private fun parseIdentifierName(): Identifier {
        val pos = getPos()
        val value = scanner.getTokenValue()
        val raw = scanner.getTokenText()
        val rawText = if (raw != value) raw else null
        // Report invalid unicode escapes (e.g. \u003 with only 3 hex digits)
        if (scanner.hasInvalidUnicodeEscapeInToken()) {
            reportError("Invalid character.")
        }
        nextToken()
        return Identifier(text = value, rawText = rawText, pos = pos, end = getEnd())
    }

    private fun parseStringLiteral(): StringLiteralNode {
        val pos = getPos()
        val raw = scanner.getTokenText()
        val value = scanner.getTokenValue()
        val singleQuote = raw.startsWith("'")
        // Store raw content between quotes to preserve escape sequences (e.g. \u2730, \n)
        val rawContent = if (raw.length >= 2) raw.substring(1, raw.length - 1) else raw
        nextToken()
        return StringLiteralNode(text = value, singleQuote = singleQuote, rawText = rawContent, pos = pos, end = getEnd())
    }

    private fun parseNumericLiteral(): NumericLiteralNode {
        val pos = getPos()
        val text = scanner.getTokenText()
        nextToken()
        return NumericLiteralNode(text = text, pos = pos, end = getEnd())
    }

    private fun isIdentifier(): Boolean =
        token == SyntaxKind.Identifier ||
                token == SyntaxKind.TypeKeyword ||
                token == SyntaxKind.AbstractKeyword ||
                token == SyntaxKind.AsKeyword ||
                token == SyntaxKind.AssertsKeyword ||
                token == SyntaxKind.AsyncKeyword ||
                token == SyntaxKind.AwaitKeyword ||
                token == SyntaxKind.ConstructorKeyword ||
                token == SyntaxKind.DeclareKeyword ||
                token == SyntaxKind.GetKeyword ||
                token == SyntaxKind.GlobalKeyword ||
                token == SyntaxKind.FromKeyword ||
                token == SyntaxKind.ImplementsKeyword ||
                token == SyntaxKind.InterfaceKeyword ||
                token == SyntaxKind.IsKeyword ||
                token == SyntaxKind.KeyOfKeyword ||
                token == SyntaxKind.LetKeyword ||
                token == SyntaxKind.ModuleKeyword ||
                token == SyntaxKind.NamespaceKeyword ||
                token == SyntaxKind.NeverKeyword ||
                token == SyntaxKind.OfKeyword ||
                token == SyntaxKind.OutKeyword ||
                token == SyntaxKind.OverrideKeyword ||
                token == SyntaxKind.ReadonlyKeyword ||
                token == SyntaxKind.RequireKeyword ||
                token == SyntaxKind.SatisfiesKeyword ||
                token == SyntaxKind.SetKeyword ||
                token == SyntaxKind.StaticKeyword ||
                token == SyntaxKind.UniqueKeyword ||
                token == SyntaxKind.UnknownKeyword ||
                token == SyntaxKind.UsingKeyword ||
                token == SyntaxKind.AnyKeyword ||
                token == SyntaxKind.BooleanKeyword ||
                token == SyntaxKind.BigIntKeyword ||
                token == SyntaxKind.NumberKeyword ||
                token == SyntaxKind.ObjectKeyword ||
                token == SyntaxKind.StringKeyword ||
                token == SyntaxKind.SymbolKeyword ||
                token == SyntaxKind.UndefinedKeyword ||
                token == SyntaxKind.AccessorKeyword

    private fun isKeyword(): Boolean = token.name.endsWith("Keyword")

    private fun isPropertyNameToken(): Boolean =
        isIdentifier() || isKeyword() ||
                token == SyntaxKind.StringLiteral ||
                token == SyntaxKind.NumericLiteral ||
                token == SyntaxKind.OpenBracket ||
                token == SyntaxKind.Hash

    private fun <T> lookAhead(callback: () -> T): T {
        val savedToken = token
        val result = scanner.lookAhead(callback)
        token = savedToken
        return result
    }

    private fun tokenToString(
        kind: SyntaxKind
    ) = when (kind) {
        OpenBrace -> "{"
        CloseBrace -> "}"
        OpenParen -> "("
        CloseParen -> ")"
        OpenBracket -> "["
        CloseBracket -> "]"
        Semicolon -> ";"
        Comma -> ","
        Colon -> ":"
        Dot -> "."
        Equals -> "="
        LessThan -> "<"
        GreaterThan -> ">"
        EqualsGreaterThan -> "=>"
        else -> kind.name
    }

}
