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

    fun parse(): SourceFile {
        nextToken()
        val statements = parseStatements()
        return SourceFile(
            fileName = fileName,
            statements = statements,
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

    // ── Statement list ──────────────────────────────────────────────────────

    private fun parseStatements(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (token != SyntaxKind.EndOfFile && token != SyntaxKind.CloseBrace) {
            val savedPos = scanner.getTokenPos()
            val stmt = parseStatement()
            if (stmt != null) stmts.add(stmt)
            // Safety: if no progress was made, skip the current token to avoid infinite loop
            if (scanner.getTokenPos() == savedPos && token != SyntaxKind.EndOfFile) {
                nextToken()
            }
        }
        return stmts
    }

    // ── Statements ──────────────────────────────────────────────────────────

    private fun parseStatement(): Statement? {
        return when (token) {
            SyntaxKind.OpenBrace -> parseBlock()
            SyntaxKind.Semicolon -> parseEmptyStatement()
            SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.ConstKeyword -> parseVariableStatement()
            SyntaxKind.FunctionKeyword -> parseFunctionDeclarationOrExpression()
            SyntaxKind.ClassKeyword -> parseClassDeclaration()
            SyntaxKind.IfKeyword -> parseIfStatement()
            SyntaxKind.DoKeyword -> parseDoStatement()
            SyntaxKind.WhileKeyword -> parseWhileStatement()
            SyntaxKind.ForKeyword -> parseForStatement()
            SyntaxKind.ContinueKeyword -> parseContinueStatement()
            SyntaxKind.BreakKeyword -> parseBreakStatement()
            SyntaxKind.ReturnKeyword -> parseReturnStatement()
            SyntaxKind.WithKeyword -> parseWithStatement()
            SyntaxKind.SwitchKeyword -> parseSwitchStatement()
            SyntaxKind.ThrowKeyword -> parseThrowStatement()
            SyntaxKind.TryKeyword -> parseTryStatement()
            SyntaxKind.DebuggerKeyword -> parseDebuggerStatement()
            SyntaxKind.ImportKeyword -> parseImportDeclaration()
            SyntaxKind.ExportKeyword -> parseExportDeclaration()
            SyntaxKind.InterfaceKeyword -> parseInterfaceDeclaration()
            SyntaxKind.TypeKeyword -> if (isStartOfTypeAlias()) parseTypeAliasDeclaration() else parseExpressionStatement()
            SyntaxKind.EnumKeyword -> parseEnumDeclaration()
            SyntaxKind.NamespaceKeyword, SyntaxKind.ModuleKeyword -> parseModuleDeclaration()
            SyntaxKind.AbstractKeyword -> parseAbstractOrDeclaration()
            SyntaxKind.AsyncKeyword -> parseAsyncOrExpression()
            SyntaxKind.DeclareKeyword -> parseDeclareDeclaration()
            SyntaxKind.At -> {
                parseDecorators(); parseStatement()
            }

            SyntaxKind.LabeledStatement -> null // won't appear as token
            else -> {
                if (isIdentifier() && lookAhead { nextToken(); token == SyntaxKind.Colon }) {
                    parseLabeledStatement()
                } else {
                    parseExpressionStatement()
                }
            }
        }
    }

    private fun parseBlock(): Block {
        val pos = getPos()
        val comments = leadingComments()
        val openBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenBrace)
        val stmts = parseStatements()
        val closeBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBrace)
        val multiLine = if (openBracePos >= 0 && closeBracePos > openBracePos && closeBracePos <= source.length) {
            source.substring(openBracePos, closeBracePos).contains('\n')
        } else true
        return Block(statements = stmts, multiLine = multiLine, pos = pos, end = getEnd(), leadingComments = comments)
    }

    private fun parseEmptyStatement(): EmptyStatement {
        val pos = getPos()
        nextToken()
        return EmptyStatement(pos = pos, end = getEnd())
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
        val decls = mutableListOf<VariableDeclaration>()
        decls.add(parseVariableDeclaration())
        while (parseOptional(SyntaxKind.Comma)) {
            decls.add(parseVariableDeclaration())
        }
        return VariableDeclarationList(declarations = decls, flags = flags, pos = pos, end = getEnd())
    }

    private fun parseVariableDeclaration(): VariableDeclaration {
        val pos = getPos()
        val name = parseBindingNameOrPattern()
        val excl = parseOptional(SyntaxKind.Exclamation)
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
        return VariableDeclaration(
            name = name,
            type = type,
            initializer = init,
            exclamationToken = excl,
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseBindingNameOrPattern(): Expression {
        return when (token) {
            SyntaxKind.OpenBrace -> parseObjectBindingPattern()
            SyntaxKind.OpenBracket -> parseArrayBindingPattern()
            else -> parseIdentifier()
        }
    }

    private fun parseObjectBindingPattern(): ObjectBindingPattern {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val elements = mutableListOf<BindingElement>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            elements.add(parseBindingElement())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBrace)
        return ObjectBindingPattern(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseArrayBindingPattern(): ArrayBindingPattern {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBracket)
        val elements = mutableListOf<Node>()
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            elements.add(parseBindingElement())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBracket)
        return ArrayBindingPattern(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseBindingElement(): BindingElement {
        val pos = getPos()
        val dotDotDot = parseOptional(SyntaxKind.DotDotDot)
        val nameOrProp = parseBindingNameOrPattern()
        return if (token == SyntaxKind.Colon && nameOrProp is Identifier) {
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
        parseSemicolon()
        val trailing = trailingComments()
        return ExpressionStatement(
            expression = expr,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing
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
        val elseStmt = if (parseOptional(SyntaxKind.ElseKeyword)) parseStatement() else null
        return IfStatement(
            expression = expr,
            thenStatement = thenStmt,
            elseStatement = elseStmt,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
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
        return DoStatement(statement = stmt, expression = expr, pos = pos, end = getEnd(), leadingComments = comments)
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
            SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.ConstKeyword -> parseVariableDeclarationList()
            SyntaxKind.Semicolon -> null
            else -> parseExpression()
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
        nextToken()
        val label = if (!canParseSemicolon() && isIdentifier()) parseIdentifier() else null
        parseSemicolon()
        return ContinueStatement(label = label, pos = pos, end = getEnd())
    }

    private fun parseBreakStatement(): BreakStatement {
        val pos = getPos()
        nextToken()
        val label = if (!canParseSemicolon() && isIdentifier()) parseIdentifier() else null
        parseSemicolon()
        return BreakStatement(label = label, pos = pos, end = getEnd())
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
        nextToken()
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        val stmt = parseStatement() ?: EmptyStatement()
        return WithStatement(expression = expr, statement = stmt, pos = pos, end = getEnd())
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
        parseExpected(SyntaxKind.CloseBrace)
        return SwitchStatement(
            expression = expr,
            caseBlock = clauses,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseCaseOrDefaultClause(): Node {
        val pos = getPos()
        return if (token == SyntaxKind.CaseKeyword) {
            nextToken()
            val expr = parseExpression()
            parseExpected(SyntaxKind.Colon)
            val firstStmtStart = getPos() // scanner.getTokenPos() = start of first statement token
            val stmts = parseCaseClauseStatements()
            // Single-line if no newline between case clause start and first statement
            val singleLine = stmts.isNotEmpty() && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n')
            CaseClause(expression = expr, statements = stmts, singleLine = singleLine, pos = pos, end = getEnd())
        } else {
            parseExpected(SyntaxKind.DefaultKeyword)
            parseExpected(SyntaxKind.Colon)
            val firstStmtStart = getPos()
            val stmts = parseCaseClauseStatements()
            val singleLine = stmts.isNotEmpty() && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n')
            DefaultClause(statements = stmts, singleLine = singleLine, pos = pos, end = getEnd())
        }
    }

    private fun parseCaseClauseStatements(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (token != SyntaxKind.CaseKeyword && token != SyntaxKind.DefaultKeyword &&
            token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile
        ) {
            val stmt = parseStatement()
            if (stmt != null) stmts.add(stmt)
        }
        return stmts
    }

    private fun parseThrowStatement(): ThrowStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        val expr = parseExpression()
        parseSemicolon()
        return ThrowStatement(expression = expr, pos = pos, end = getEnd(), leadingComments = comments)
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
            parseExpected(SyntaxKind.CloseParen)
            VariableDeclaration(name = name, type = type)
        } else null
        val block = parseBlock()
        return CatchClause(variableDeclaration = varDecl, block = block, pos = pos, end = getEnd())
    }

    private fun parseDebuggerStatement(): DebuggerStatement {
        val pos = getPos()
        nextToken()
        parseSemicolon()
        return DebuggerStatement(pos = pos, end = getEnd())
    }

    private fun parseLabeledStatement(): LabeledStatement {
        val pos = getPos()
        val label = parseIdentifier()
        parseExpected(SyntaxKind.Colon)
        val stmt = parseStatement() ?: EmptyStatement()
        return LabeledStatement(label = label, statement = stmt, pos = pos, end = getEnd())
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
        val name = if (isIdentifier()) parseIdentifier() else null
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
                        scanner.getToken() != SyntaxKind.Comma && scanner.getToken() != SyntaxKind.CloseBrace
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
                        scanner.getToken() != SyntaxKind.Comma && scanner.getToken() != SyntaxKind.CloseBrace
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
            MethodDeclaration(
                name = name, typeParameters = typeParams, parameters = params,
                type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk,
                questionToken = question, decorators = decorators, pos = pos, end = getEnd(), leadingComments = comments
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
        return Constructor(
            parameters = params,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseGetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): GetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            parseSemicolon(); null
        }
        return GetAccessor(
            name = name,
            parameters = params,
            type = type,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseSetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): SetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            parseSemicolon(); null
        }
        return SetAccessor(
            name = name,
            parameters = params,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parsePropertyName() = when (token) {
        StringLiteral -> parseStringLiteral()
        NumericLiteral -> parseNumericLiteral()
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
            val isAccessor = scanner.lookAhead {
                scanner.scan()
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
            val mName = parsePropertyName()
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            members.add(EnumMember(name = mName, initializer = init, pos = mPos, end = getEnd()))
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseBrace)
        return EnumDeclaration(
            name = name,
            members = members,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
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
            parseExpected(SyntaxKind.CloseBrace)
            ModuleBlock(statements = stmts, pos = bPos, end = getEnd())
        } else if (token == SyntaxKind.Dot) {
            // nested: namespace A.B { }
            nextToken()
            parseModuleDeclaration(modifiers)
        } else null
        return ModuleDeclaration(
            name = name,
            body = body,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseImportDeclaration(): Statement {
        val pos = getPos()
        val comments = leadingComments()
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
            return ImportEqualsDeclaration(
                name = name,
                moduleReference = moduleRef,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
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
                SyntaxKind.FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
                SyntaxKind.ClassKeyword -> parseClassDeclaration(modifiers, comments)
                SyntaxKind.AbstractKeyword -> {
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
            scanner.getToken() == SyntaxKind.OpenBrace || scanner.getToken() == SyntaxKind.Asterisk || isIdentifier()
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

        // export var/let/const/function/class/interface/type/enum/namespace/declare/abstract/async
        val modifiers = setOf(ModifierFlag.Export)
        return when (token) {
            SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.ConstKeyword -> parseVariableStatement(
                modifiers,
                comments
            )

            SyntaxKind.FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
            SyntaxKind.ClassKeyword -> parseClassDeclaration(modifiers, comments)
            SyntaxKind.InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
            SyntaxKind.TypeKeyword -> parseTypeAliasDeclaration(modifiers, comments)
            SyntaxKind.EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            SyntaxKind.NamespaceKeyword, SyntaxKind.ModuleKeyword -> parseModuleDeclaration(modifiers, comments)
            SyntaxKind.DeclareKeyword -> parseDeclareDeclaration(modifiers, comments)
            SyntaxKind.AbstractKeyword -> {
                nextToken(); parseClassDeclaration(modifiers + ModifierFlag.Abstract, comments)
            }

            SyntaxKind.AsyncKeyword -> {
                nextToken(); parseFunctionDeclarationOrExpression(modifiers + ModifierFlag.Async, comments)
            }

            SyntaxKind.ConstKeyword -> {
                nextToken()
                parseEnumDeclaration(modifiers + ModifierFlag.Const, comments)
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
            SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.ConstKeyword -> parseVariableStatement(
                modifiers,
                comments
            )

            SyntaxKind.FunctionKeyword -> parseFunctionDeclarationOrExpression(modifiers, comments)
            SyntaxKind.ClassKeyword -> parseClassDeclaration(modifiers, comments)
            SyntaxKind.InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
            SyntaxKind.TypeKeyword -> parseTypeAliasDeclaration(modifiers, comments)
            SyntaxKind.EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            SyntaxKind.NamespaceKeyword, SyntaxKind.ModuleKeyword -> parseModuleDeclaration(modifiers, comments)
            SyntaxKind.AbstractKeyword -> {
                nextToken(); parseClassDeclaration(modifiers + ModifierFlag.Abstract, comments)
            }

            SyntaxKind.GlobalKeyword -> parseModuleDeclaration(modifiers, comments)
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

        // Check for `async x => expr` (single-param async arrow without parens)
        if (token == SyntaxKind.AsyncKeyword) {
            val isAsyncSingleParamArrow = scanner.lookAhead {
                scanner.scan() // skip async
                if (scanner.hasPrecedingLineBreak()) return@lookAhead false
                val t = scanner.getToken()
                if (t == SyntaxKind.Identifier || t == SyntaxKind.TypeKeyword) {
                    scanner.scan()
                    scanner.getToken() == SyntaxKind.EqualsGreaterThan
                } else false
            }
            if (isAsyncSingleParamArrow) {
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
            nextToken()
            // Right-to-left for ** operator
            val nextMinPrec = if (op == SyntaxKind.AsteriskAsterisk) prec - 1 else prec
            val right = parseBinaryExpression(nextMinPrec)
            left = BinaryExpression(left = left, operator = op, right = right, pos = left.pos, end = getEnd())
        }
        return left
    }

    private fun parseExpressionSuffix(expr: Expression): Expression {
        // Handle 'as' and 'satisfies' that appear after binary expressions
        return when (token) {
            SyntaxKind.AsKeyword -> {
                nextToken()
                val type = parseType()
                parseExpressionSuffix(AsExpression(expression = expr, type = type, pos = expr.pos, end = getEnd()))
            }

            SyntaxKind.SatisfiesKeyword -> {
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
        InstanceOfKeyword, InKeyword,
        AsKeyword, SatisfiesKeyword -> 7

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
            SyntaxKind.PlusPlus, SyntaxKind.MinusMinus -> {
                val op = token; nextToken()
                PrefixUnaryExpression(
                    operator = op,
                    operand = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            SyntaxKind.Plus, SyntaxKind.Minus, SyntaxKind.Tilde, SyntaxKind.Exclamation -> {
                val op = token; nextToken()
                PrefixUnaryExpression(
                    operator = op,
                    operand = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            SyntaxKind.DeleteKeyword -> {
                nextToken(); DeleteExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            SyntaxKind.TypeOfKeyword -> {
                nextToken(); TypeOfExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            SyntaxKind.VoidKeyword -> {
                nextToken(); VoidExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            SyntaxKind.AwaitKeyword -> {
                if (inAsyncContext) {
                    nextToken(); AwaitExpression(
                        expression = parseUnaryExpression(),
                        pos = pos,
                        end = getEnd(),
                        leadingComments = comments
                    )
                } else {
                    parsePostfixExpression() // treat await as identifier when not in async context
                }
            }

            SyntaxKind.LessThan -> {
                // Could be <Type>expr type assertion or JSX — try type assertion
                parseTypeAssertion()
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
            SyntaxKind.NewKeyword -> parseNewExpression()
            SyntaxKind.SuperKeyword -> {
                val pos = getPos(); nextToken(); Identifier(text = "super", pos = pos, end = getEnd())
            }

            SyntaxKind.ImportKeyword -> {
                if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.OpenParen }) {
                    // import(...)
                    val pos = getPos()
                    nextToken()
                    parseExpected(SyntaxKind.OpenParen)
                    val arg = parseAssignmentExpression()
                    parseExpected(SyntaxKind.CloseParen)
                    CallExpression(
                        expression = Identifier("import", pos = pos),
                        arguments = listOf(arg),
                        pos = pos,
                        end = getEnd()
                    )
                } else if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.Dot }) {
                    val pos = getPos()
                    nextToken()
                    parseExpected(SyntaxKind.Dot)
                    val name = parseIdentifier()
                    MetaProperty(keywordToken = SyntaxKind.ImportKeyword, name = name, pos = pos, end = getEnd())
                } else {
                    parseIdentifier()
                }
            }

            else -> parsePrimaryExpression()
        }
        return parseCallAndAccess(expr)
    }

    private fun parseCallAndAccess(expr: Expression): Expression {
        var result = expr
        while (true) {
            result = when (token) {
                SyntaxKind.Dot -> {
                    nextToken()
                    val name = parseIdentifierName()
                    PropertyAccessExpression(expression = result, name = name, pos = result.pos, end = getEnd())
                }

                SyntaxKind.OpenBracket -> {
                    nextToken()
                    val arg = parseExpression()
                    parseExpected(SyntaxKind.CloseBracket)
                    ElementAccessExpression(
                        expression = result,
                        argumentExpression = arg,
                        pos = result.pos,
                        end = getEnd()
                    )
                }

                SyntaxKind.OpenParen -> {
                    val args = parseArgumentList()
                    CallExpression(expression = result, arguments = args, pos = result.pos, end = getEnd())
                }

                SyntaxKind.LessThan -> {
                    // Try type arguments for call/new
                    val typeArgs = tryParseTypeArguments()
                    if (typeArgs != null && token == SyntaxKind.OpenParen) {
                        val args = parseArgumentList()
                        CallExpression(
                            expression = result,
                            typeArguments = typeArgs,
                            arguments = args,
                            pos = result.pos,
                            end = getEnd()
                        )
                    } else {
                        // Sync parser token with scanner after failed/unused type argument parse
                        token = scanner.getToken()
                        return result
                    }
                }

                SyntaxKind.QuestionDot -> {
                    nextToken()
                    when (token) {
                        SyntaxKind.OpenBracket -> {
                            nextToken()
                            val arg = parseExpression()
                            parseExpected(SyntaxKind.CloseBracket)
                            ElementAccessExpression(
                                expression = result,
                                argumentExpression = arg,
                                questionDotToken = true,
                                pos = result.pos,
                                end = getEnd()
                            )
                        }

                        SyntaxKind.OpenParen -> {
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

                SyntaxKind.NoSubstitutionTemplateLiteral, SyntaxKind.TemplateHead -> {
                    val template = parseTemplateLiteral()
                    TaggedTemplateExpression(tag = result, template = template, pos = result.pos, end = getEnd())
                }

                SyntaxKind.Exclamation -> {
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
        parseExpected(SyntaxKind.NewKeyword)
        if (token == SyntaxKind.Dot) {
            nextToken()
            val name = parseIdentifier()
            return MetaProperty(keywordToken = SyntaxKind.NewKeyword, name = name, pos = pos, end = getEnd())
        }
        val expr = parseCallAndAccess(parsePrimaryExpression())
        val typeArgs = tryParseTypeArguments()
        val args = if (token == SyntaxKind.OpenParen) parseArgumentList() else null
        return NewExpression(expression = expr, typeArguments = typeArgs, arguments = args, pos = pos, end = getEnd())
    }

    private fun parsePrimaryExpression(): Expression {
        val pos = getPos()
        val comments = leadingComments()
        return when (token) {
            SyntaxKind.NumericLiteral -> parseNumericLiteral()
            SyntaxKind.BigIntLiteral -> {
                val text = scanner.getTokenValue(); nextToken(); BigIntLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            SyntaxKind.StringLiteral -> parseStringLiteral()
            SyntaxKind.NoSubstitutionTemplateLiteral -> {
                val text = scanner.getTokenText(); nextToken(); NoSubstitutionTemplateLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            SyntaxKind.TemplateHead -> parseTemplateExpression()
            SyntaxKind.RegularExpressionLiteral -> {
                val text = scanner.getTokenText(); nextToken(); RegularExpressionLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            SyntaxKind.Slash, SyntaxKind.SlashEquals -> {
                val regexToken = scanner.reScanSlashToken()
                if (regexToken == SyntaxKind.RegularExpressionLiteral) {
                    val text = scanner.getTokenText(); nextToken()
                    RegularExpressionLiteralNode(text = text, pos = pos, end = getEnd())
                } else {
                    Identifier(text = scanner.getTokenText(), pos = pos, end = getEnd()).also { nextToken() }
                }
            }

            SyntaxKind.OpenParen -> parseParenthesizedOrArrow()
            SyntaxKind.OpenBracket -> parseArrayLiteral()
            SyntaxKind.OpenBrace -> parseObjectLiteral()
            SyntaxKind.FunctionKeyword -> parseFunctionExpression()
            SyntaxKind.ClassKeyword -> parseClassExpression()
            SyntaxKind.TrueKeyword -> {
                nextToken(); Identifier(text = "true", pos = pos, end = getEnd())
            }

            SyntaxKind.FalseKeyword -> {
                nextToken(); Identifier(text = "false", pos = pos, end = getEnd())
            }

            SyntaxKind.NullKeyword -> {
                nextToken(); Identifier(text = "null", pos = pos, end = getEnd())
            }

            SyntaxKind.UndefinedKeyword -> {
                nextToken(); Identifier(text = "undefined", pos = pos, end = getEnd())
            }

            SyntaxKind.ThisKeyword -> {
                nextToken(); Identifier(text = "this", pos = pos, end = getEnd())
            }

            SyntaxKind.AsyncKeyword -> {
                // Could be "async function" or "async () =>"
                if (scanner.lookAhead { scanner.scan(); !scanner.hasPrecedingLineBreak() && scanner.getToken() == SyntaxKind.FunctionKeyword }) {
                    nextToken()
                    return parseFunctionExpression(setOf(ModifierFlag.Async))
                }
                parseIdentifier()
            }

            else -> parseIdentifier()
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

        // Try complex arrow detection
        val maybeArrow = scanner.lookAhead {
            scanner.scan() // skip (
            var depth = 1
            while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                if (scanner.getToken() == SyntaxKind.OpenParen) depth++
                else if (scanner.getToken() == SyntaxKind.CloseParen) depth--
                if (depth > 0) scanner.scan()
            }
            if (depth == 0) {
                scanner.scan()
                scanner.getToken() == SyntaxKind.EqualsGreaterThan || scanner.getToken() == SyntaxKind.Colon
            } else false
        }
        if (maybeArrow) return parseArrowFunction(emptySet())

        val pos = getPos()
        parseExpected(SyntaxKind.OpenParen)
        val expr = parseExpression()
        parseExpected(SyntaxKind.CloseParen)
        return ParenthesizedExpression(expression = expr, pos = pos, end = getEnd())
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
        val elements = mutableListOf<Expression>()
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            if (token == SyntaxKind.DotDotDot) {
                val sPos = getPos()
                nextToken()
                elements.add(SpreadElement(expression = parseAssignmentExpression(), pos = sPos, end = getEnd()))
            } else {
                elements.add(parseAssignmentExpression())
            }
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        val closeBracketPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBracket)
        val multiLine =
            if (openBracketPos >= 0 && closeBracketPos > openBracketPos && closeBracketPos <= source.length) {
                source.substring(openBracketPos, closeBracketPos).contains('\n')
            } else false
        return ArrayLiteralExpression(elements = elements, multiLine = multiLine, pos = pos, end = getEnd())
    }

    private fun parseObjectLiteral(): ObjectLiteralExpression {
        val pos = getPos()
        val openBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenBrace)
        val properties = mutableListOf<Node>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            properties.add(parseObjectLiteralElement())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        val closeBracePos = scanner.getTokenPos()
        parseExpected(SyntaxKind.CloseBrace)
        val multiLine = if (openBracePos >= 0 && closeBracePos > openBracePos && closeBracePos <= source.length) {
            source.substring(openBracePos, closeBracePos).contains('\n')
        } else false
        return ObjectLiteralExpression(properties = properties, multiLine = multiLine, pos = pos, end = getEnd())
    }

    private fun parseObjectLiteralElement(): Node {
        val pos = getPos()

        if (token == SyntaxKind.DotDotDot) {
            nextToken()
            return SpreadAssignment(expression = parseAssignmentExpression(), pos = pos, end = getEnd())
        }

        val modifiers = parseModifiers()
        val asterisk = parseOptional(SyntaxKind.Asterisk)

        if (!asterisk && (isIdentifier() && scanner.getTokenValue() == "get")) {
            val isGet = scanner.lookAhead { scanner.scan(); isPropertyNameToken() }
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
                    end = getEnd()
                )
            }
        }

        if (!asterisk && (isIdentifier() && scanner.getTokenValue() == "set")) {
            val isSet = scanner.lookAhead { scanner.scan(); isPropertyNameToken() }
            if (isSet) {
                nextToken()
                val name = parsePropertyName()
                val params = parseParameterList()
                val body = if (token == SyntaxKind.OpenBrace) parseBlock() else null
                return SetAccessor(name = name, parameters = params, body = body, pos = pos, end = getEnd())
            }
        }

        val name = parsePropertyName()

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
                end = getEnd()
            )
        }

        // Property: name: value
        if (parseOptional(SyntaxKind.Colon)) {
            val value = parseAssignmentExpression()
            return PropertyAssignment(name = name, initializer = value, pos = pos, end = getEnd())
        }

        // Shorthand: { name } or { name = default }
        if (name is Identifier) {
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            return ShorthandPropertyAssignment(
                name = name,
                objectAssignmentInitializer = init,
                pos = pos,
                end = getEnd()
            )
        }

        return PropertyAssignment(name = name, initializer = Identifier(""), pos = pos, end = getEnd())
    }

    private fun parseFunctionExpression(modifiers: Set<ModifierFlag> = emptySet()): FunctionExpression {
        val pos = getPos()
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
            type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk, pos = pos, end = getEnd()
        )
    }

    private fun parseClassExpression(): ClassExpression {
        val pos = getPos()
        parseExpected(SyntaxKind.ClassKeyword)
        val name = if (isIdentifier()) parseIdentifier() else null
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
        val asterisk = parseOptional(SyntaxKind.Asterisk)
        val expr = if (!canParseSemicolon()) parseAssignmentExpression() else null
        return YieldExpression(expression = expr, asteriskToken = asterisk, pos = pos, end = getEnd())
    }

    private fun parseTemplateLiteral(): Expression {
        if (token == SyntaxKind.NoSubstitutionTemplateLiteral) {
            val text = scanner.getTokenText()
            val pos = getPos()
            nextToken()
            return NoSubstitutionTemplateLiteralNode(text = text, pos = pos, end = getEnd())
        }
        return parseTemplateExpression()
    }

    private fun parseTemplateExpression(): TemplateExpression {
        val pos = getPos()
        val headText = scanner.getTokenText()
        nextToken() // consume template head
        val head = StringLiteralNode(text = headText, pos = pos, end = getEnd())
        val spans = mutableListOf<TemplateSpan>()
        while (token != SyntaxKind.EndOfFile) {
            val spanPos = getPos()
            val expr = parseExpression()
            // After expression, rescan to get template middle or tail
            val literalKind = scanner.reScanTemplateToken()
            val literalText = scanner.getTokenText()
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
            nextToken()
            val nextMinPrec = if (op == SyntaxKind.AsteriskAsterisk) prec - 1 else prec
            val right = parseBinaryExpression(nextMinPrec)
            result = BinaryExpression(left = result, operator = op, right = right, pos = result.pos, end = getEnd())
        }
        return result
    }

    private fun parseArgumentList(): List<Expression> {
        parseExpected(SyntaxKind.OpenParen)
        val args = mutableListOf<Expression>()
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.DotDotDot) {
                val pos = getPos()
                nextToken()
                args.add(SpreadElement(expression = parseAssignmentExpression(), pos = pos, end = getEnd()))
            } else {
                args.add(parseAssignmentExpression())
            }
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseParen)
        return args
    }

    // ── Parameters ──────────────────────────────────────────────────────────

    private fun parseParameterList(): List<Parameter> {
        parseExpected(SyntaxKind.OpenParen)
        val params = mutableListOf<Parameter>()
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            params.add(parseParameter())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseParen)
        return params
    }

    private fun parseParameter(): Parameter {
        val pos = getPos()
        val decorators = parseDecorators()
        val modifiers = parseModifiers()
        val dotDotDot = parseOptional(SyntaxKind.DotDotDot)
        val name = parseBindingNameOrPattern()
        val question = parseOptional(SyntaxKind.Question)
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
        return Parameter(
            name = name, type = type, initializer = init, dotDotDotToken = dotDotDot,
            questionToken = question, modifiers = modifiers, decorators = decorators, pos = pos, end = getEnd()
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
            val args = mutableListOf<TypeNode>()
            do {
                args.add(parseType())
            } while (parseOptional(SyntaxKind.Comma))
            if (token != SyntaxKind.GreaterThan) return@tryScan null
            nextToken()
            args
        }
    }

    // ── Type parsing (parse to discard) ─────────────────────────────────────

    private fun parseType(): TypeNode {
        val pos = getPos()
        var type = parseNonUnionType()
        if (token == SyntaxKind.Bar) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Bar)) {
                types.add(parseNonUnionType())
            }
            type = UnionType(types = types, pos = pos, end = getEnd())
        }
        if (token == SyntaxKind.Ampersand) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Ampersand)) {
                types.add(parseNonUnionType())
            }
            type = IntersectionType(types = types, pos = pos, end = getEnd())
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
            return TypeQuery(exprName = name, typeArguments = typeArgs, pos = pos, end = getEnd())
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
            SyntaxKind.AnyKeyword, SyntaxKind.StringKeyword, SyntaxKind.NumberKeyword,
            SyntaxKind.BooleanKeyword, SyntaxKind.BigIntKeyword, SyntaxKind.SymbolKeyword,
            SyntaxKind.VoidKeyword, SyntaxKind.NeverKeyword, SyntaxKind.ObjectKeyword,
            SyntaxKind.UnknownKeyword, SyntaxKind.UndefinedKeyword, SyntaxKind.NullKeyword -> {
                val kw = token; nextToken()
                KeywordTypeNode(kind = kw, pos = pos, end = getEnd())
            }

            SyntaxKind.ThisKeyword -> {
                nextToken(); ThisType(pos = pos, end = getEnd())
            }

            SyntaxKind.OpenParen -> parseFunctionOrParenthesizedType()
            SyntaxKind.OpenBracket -> parseTupleType()
            SyntaxKind.OpenBrace -> parseTypeLiteralOrMappedType()
            SyntaxKind.StringLiteral, SyntaxKind.NumericLiteral, SyntaxKind.TrueKeyword,
            SyntaxKind.FalseKeyword -> {
                val literal = parsePrimaryExpression()
                LiteralType(literal = literal, pos = pos, end = getEnd())
            }

            SyntaxKind.Minus -> {
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

            SyntaxKind.NewKeyword -> parseConstructorType()
            SyntaxKind.ImportKeyword -> parseImportType()
            SyntaxKind.DotDotDot -> {
                nextToken(); RestType(type = parseType(), pos = pos, end = getEnd())
            }

            SyntaxKind.Backtick, SyntaxKind.NoSubstitutionTemplateLiteral, SyntaxKind.TemplateHead -> {
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
        parseExpected(SyntaxKind.OpenParen)
        val params = mutableListOf<Parameter>()
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            params.add(parseParameter())
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        parseExpected(SyntaxKind.CloseParen)
        if (token == SyntaxKind.EqualsGreaterThan) {
            nextToken()
            val returnType = parseType()
            return FunctionType(parameters = params, type = returnType, pos = pos, end = getEnd())
        }
        // Parenthesized type
        if (params.size == 1 && params[0].type != null) {
            return ParenthesizedType(type = params[0].type!!, pos = pos, end = getEnd())
        }
        return FunctionType(
            parameters = params,
            type = KeywordTypeNode(kind = SyntaxKind.VoidKeyword),
            pos = pos,
            end = getEnd()
        )
    }

    private fun parseTupleType(): TypeNode {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBracket)
        val elements = mutableListOf<TypeNode>()
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            elements.add(parseType())
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
        val text = scanner.getTokenValue()
        if (isIdentifier() || isKeyword()) {
            nextToken()
        } else {
            reportError("Identifier expected.")
        }
        return Identifier(text = text, pos = pos, end = getEnd())
    }

    private fun parseIdentifierName(): Identifier {
        val pos = getPos()
        val text = scanner.getTokenValue()
        nextToken()
        return Identifier(text = text, pos = pos, end = getEnd())
    }

    private fun parseStringLiteral(): StringLiteralNode {
        val pos = getPos()
        val raw = scanner.getTokenText()
        val value = scanner.getTokenValue()
        val singleQuote = raw.startsWith("'")
        nextToken()
        return StringLiteralNode(text = value, singleQuote = singleQuote, pos = pos, end = getEnd())
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

    private fun tokenToString(kind: SyntaxKind): String = when (kind) {
        SyntaxKind.OpenBrace -> "{"
        SyntaxKind.CloseBrace -> "}"
        SyntaxKind.OpenParen -> "("
        SyntaxKind.CloseParen -> ")"
        SyntaxKind.OpenBracket -> "["
        SyntaxKind.CloseBracket -> "]"
        SyntaxKind.Semicolon -> ";"
        SyntaxKind.Comma -> ","
        SyntaxKind.Colon -> ":"
        SyntaxKind.Dot -> "."
        SyntaxKind.Equals -> "="
        SyntaxKind.LessThan -> "<"
        SyntaxKind.GreaterThan -> ">"
        SyntaxKind.EqualsGreaterThan -> "=>"
        else -> kind.name
    }
}
